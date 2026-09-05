package com.lennon.imagebordercrop

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lennon.imagebordercrop.databinding.ActivityMainBinding
import com.lennon.imagebordercrop.databinding.BottomSheetCropAdjustmentBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.slider.Slider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var currentImageUri: Uri? = null
    private var currentImage: LoadedImage? = null
    private var lastResult: BorderResult? = null
    private var automaticResult: BorderResult? = null
    private var lastDetectionSettings: DetectionSettings? = null
    private var detectionJob: Job? = null
    private var automaticDetectionJob: Job? = null
    private var recentImageJob: Job? = null
    private var imageLoadJob: Job? = null
    private var saveJob: Job? = null
    private var cropAdjustmentDialog: BottomSheetDialog? = null
    private var staticCroppedPreview: Bitmap? = null
    private var recentImageUri: Uri? = null
    private var recentImageSelectable = true
    private var batchSession: BatchSession<Uri>? = null
    private var queueReplacementConfirmationVisible = false
    private val detector = BorderDetector()
    private val gifProcessor = GifProcessor(detector)

    // 原图显示名称，用于裁剪保存时的文件命名
    private var originalDisplayName: String? = null

    // Photo Picker 选图回调
    private val pickMedia = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(BatchSession.MAX_ITEMS)
    ) { selectedUris ->
        val uris = selectedUris.distinct().take(BatchSession.MAX_ITEMS)
        if (uris.isEmpty()) return@registerForActivityResult

        uris.forEach(::takePersistableReadPermission)
        batchSession = if (uris.size > 1) BatchSession(uris) else null
        updateActionState()
        loadImage(uris.first(), takePersistablePermission = false)
    }

    private val requestRecentImagePermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.any { it }) {
            refreshRecentImage()
        } else {
            showRecentPermissionState(R.string.recent_image_permission_denied)
        }
    }

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            saveCroppedImage()
        } else {
            Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Color.TRANSPARENT
        } else {
            Color.BLACK
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        configureEdgeToEdge()
        cleanStaleCacheFiles()

        binding.sbThreshold.addOnChangeListener { _, value, fromUser ->
            binding.tvThresholdValue.text = value.toInt().toString()
            if (fromUser) scheduleAutomaticDetection()
        }

        // 选择图片（Photo Picker）
        binding.btnSelect.setOnClickListener {
            val batch = batchSession
            if (batch != null && !batch.isLast) {
                skipCurrentBatchItem(batch)
            } else {
                launchImagePicker()
            }
        }

        binding.cardOriginalPreview.setOnClickListener {
            launchImagePicker()
        }

        binding.cardRecentImage.setOnClickListener {
            if (batchSession != null) {
                launchImagePicker()
            } else {
                recentImageUri?.let { uri ->
                    loadImage(uri, takePersistablePermission = false)
                } ?: requestRecentImageAccess()
            }
        }

        binding.cardBorderInfo.setOnClickListener {
            showCropAdjustmentPanel()
        }

        // 裁剪并保存
        binding.btnCrop.setOnClickListener {
            cropAndSave()
        }

        // 处理通过系统"分享"接收到的图片
        handleShareIntent(intent)
    }

    private fun launchImagePicker() {
        if (isBusy()) return
        if (batchSession?.isLast == false) {
            if (queueReplacementConfirmationVisible) return
            queueReplacementConfirmationVisible = true
            val dialog = MaterialAlertDialogBuilder(this)
                .setTitle(R.string.batch_replace_title)
                .setMessage(R.string.batch_replace_message)
                .setNegativeButton(R.string.manual_adjust_cancel, null)
                .setPositiveButton(R.string.batch_replace_confirm) { _, _ -> openImagePicker() }
                .create()
            dialog.setOnDismissListener { queueReplacementConfirmationVisible = false }
            dialog.show()
            return
        }
        openImagePicker()
    }

    private fun openImagePicker() {
        pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    private fun takePersistableReadPermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // 部分 Photo Picker 或云端 URI 不支持持久化权限，当前会话仍可正常读取。
        }
    }

    private fun isBusy(): Boolean = imageLoadJob != null || detectionJob != null || saveJob != null

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) refreshRecentImage()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    override fun onDestroy() {
        imageLoadJob?.cancel()
        detectionJob?.cancel()
        automaticDetectionJob?.cancel()
        recentImageJob?.cancel()
        saveJob?.cancel()
        cropAdjustmentDialog?.setOnDismissListener(null)
        cropAdjustmentDialog?.dismiss()
        cropAdjustmentDialog = null
        clearCurrentImage()
        super.onDestroy()
    }

    private fun configureEdgeToEdge() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootContainer) { _, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val horizontalPadding = resources.getDimensionPixelSize(R.dimen.screen_horizontal_padding)
            binding.rootScrollView.updatePadding(
                left = insets.left,
                top = insets.top,
                right = insets.right,
                bottom = insets.bottom + resources.getDimensionPixelSize(
                    R.dimen.floating_action_scroll_clearance
                )
            )
            binding.bottomActionContainer.updatePadding(
                left = insets.left + horizontalPadding,
                top = resources.getDimensionPixelSize(R.dimen.floating_action_top_padding),
                right = insets.right + horizontalPadding,
                bottom = insets.bottom + resources.getDimensionPixelSize(
                    R.dimen.floating_action_bottom_padding
                )
            )
            binding.statusBarScrim.updateLayoutParams {
                height = insets.top + resources.getDimensionPixelSize(
                    R.dimen.status_bar_fade_extension
                )
            }
            windowInsets
        }
        val fadeScrollDistance = resources.getDimensionPixelSize(
            R.dimen.status_bar_fade_scroll_distance
        ).coerceAtLeast(1)
        binding.rootScrollView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            binding.statusBarScrim.alpha =
                (scrollY.toFloat() / fadeScrollDistance).coerceIn(0f, 1f)
        }
        ViewCompat.requestApplyInsets(binding.rootContainer)
    }

    private fun requestRecentImageAccess() {
        if (hasFullRecentImageAccess()) {
            refreshRecentImage()
            return
        }
        val permissions = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES
            )
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        requestRecentImagePermissions.launch(permissions)
    }

    private fun hasFullRecentImageAccess(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasRecentImageAccess(): Boolean {
        if (hasFullRecentImageAccess()) return true
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            ) == PackageManager.PERMISSION_GRANTED
    }

    private fun refreshRecentImage() {
        recentImageJob?.cancel()
        if (!hasRecentImageAccess()) {
            showRecentPermissionState(R.string.recent_image_permission)
            return
        }

        recentImageJob = lifecycleScope.launch {
            val recent = withContext(Dispatchers.IO) { queryMostRecentImage() }
            if (recent == null) {
                recentImageUri = null
                binding.ivRecentThumbnail.setImageResource(R.drawable.recent_image_placeholder)
                binding.tvRecentTitle.setText(R.string.recent_image)
                binding.tvRecentSubtitle.setText(R.string.recent_image_unavailable)
                recentImageSelectable = !hasFullRecentImageAccess()
                updateActionState()
            } else {
                showRecentImage(recent)
            }
        }
    }

    private fun queryMostRecentImage(): RecentImage? {
        return try {
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME
            )
            val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                "${MediaStore.Images.Media.IS_PENDING} = 0"
            } else {
                null
            }
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return null
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                val nameIndex = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                val displayName = if (nameIndex >= 0) cursor.getString(nameIndex) else null
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                RecentImage(uri, displayName ?: "image_$id", loadRecentThumbnail(uri))
            }
        } catch (exception: Exception) {
            Log.w(TAG, "query recent image failed", exception)
            null
        }
    }

    private fun loadRecentThumbnail(uri: Uri): Bitmap? {
        return try {
            val targetSize = (96 * resources.displayMetrics.density).toInt().coerceAtLeast(96)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentResolver.loadThumbnail(uri, Size(targetSize, targetSize), null)
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sampleSize = 1
                while (bounds.outWidth / sampleSize > targetSize * 2 ||
                    bounds.outHeight / sampleSize > targetSize * 2
                ) {
                    sampleSize *= 2
                }
                val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            }
        } catch (exception: Exception) {
            Log.w(TAG, "load recent thumbnail failed: $uri", exception)
            null
        }
    }

    private fun showRecentImage(recent: RecentImage) {
        recentImageUri = recent.uri
        if (recent.thumbnail != null) {
            binding.ivRecentThumbnail.setImageBitmap(recent.thumbnail)
        } else {
            binding.ivRecentThumbnail.setImageResource(R.drawable.recent_image_placeholder)
        }
        binding.tvRecentTitle.setText(R.string.recent_image)
        binding.tvRecentSubtitle.setText(R.string.recent_image_tap)
        recentImageSelectable = true
        updateActionState()
    }

    private fun showRecentPermissionState(messageRes: Int) {
        recentImageUri = null
        binding.ivRecentThumbnail.setImageResource(R.drawable.recent_image_placeholder)
        binding.tvRecentTitle.setText(R.string.recent_image)
        binding.tvRecentSubtitle.setText(messageRes)
        recentImageSelectable = true
        updateActionState()
    }

    /**
     * 如果 Activity 是通过 ACTION_SEND 启动（来自系统分享），从中提取图片 URI 并加载。
     */
    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            val sharedUri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            sharedUri?.let {
                batchSession = null
                updateActionState()
                loadImage(it, takePersistablePermission = false)
            }
        }
    }

    /**
     * 加载图片并查询其显示名称。
     *
     * @param uri 图片 URI
     * @param takePersistablePermission Photo Picker URI 是否尝试持久化读取权限。
     *                                  分享 URI 和 MediaStore URI 不需要执行此操作。
     */
    private fun loadImage(uri: Uri, takePersistablePermission: Boolean) {
        if (saveJob != null) {
            Toast.makeText(this, R.string.save_in_progress, Toast.LENGTH_SHORT).show()
            return
        }
        imageLoadJob?.cancel()
        invalidateDetectionResult()
        clearCurrentImage()
        binding.originalSelectHint.visibility = View.GONE
        currentImageUri = null
        originalDisplayName = null
        binding.tvBorderDetail.setText(R.string.loading_image)
        updateActionState()

        // Photo Picker 返回的 URI 支持持久化权限；分享或 MediaStore URI 不支持/不需要
        if (takePersistablePermission) {
            takePersistableReadPermission(uri)
        }

        imageLoadJob = lifecycleScope.launch {
            var loadedImage: LoadedImage? = null
            try {
                val loaded = withContext(Dispatchers.IO) {
                    val info = queryOriginalFileInfo(uri)
                    val isGif = contentResolver.openInputStream(uri)?.use(gifProcessor::hasGifSignature)
                        ?: error("无法打开所选图片")
                    val image = if (isGif) {
                        contentResolver.openInputStream(uri)?.use { input ->
                            gifProcessor.loadGif(input, cacheDir)
                        } ?: error("无法打开 GIF")
                    } else {
                        val bitmap = contentResolver.openInputStream(uri)?.use {
                            BitmapFactory.decodeStream(it)
                        } ?: error("图片解码失败")
                        LoadedImage.Static(bitmap)
                    }
                    image to info
                }
                loadedImage = loaded.first
                if (imageLoadJob !== coroutineContext[Job]) return@launch

                currentImageUri = uri
                originalDisplayName = loaded.second.displayName
                currentImage = loaded.first
                loadedImage = null
                showOriginalPreview(loaded.first)
                binding.tvBorderDetail.text = when (val image = loaded.first) {
                    is LoadedImage.Static -> "图片尺寸: ${image.width} x ${image.height}"
                    is LoadedImage.Gif ->
                        "GIF 尺寸: ${image.width} x ${image.height} · ${image.metadata.frameCount} 帧"
                }
                startDetection(saveAfterDetection = false)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Log.w(TAG, "load image failed: $uri", exception)
                binding.originalSelectHint.visibility = View.VISIBLE
                binding.tvBorderDetail.text = getString(
                    R.string.image_load_failed_detail,
                    exception.message ?: getString(R.string.image_load_failed)
                )
                Toast.makeText(this@MainActivity, R.string.image_load_failed, Toast.LENGTH_SHORT).show()
            } finally {
                loadedImage?.let(::disposeLoadedImage)
                if (imageLoadJob === coroutineContext[Job]) {
                    imageLoadJob = null
                    updateActionState()
                }
            }
        }
        updateActionState()
    }

    private fun showOriginalPreview(image: LoadedImage) {
        Glide.with(this).clear(binding.ivOriginal)
        binding.originalSelectHint.visibility = View.GONE
        when (image) {
            is LoadedImage.Static -> binding.ivOriginal.setImageBitmap(image.bitmap)
            is LoadedImage.Gif -> Glide.with(this)
                .asGif()
                .load(image.sourceFile)
                .into(binding.ivOriginal)
        }
        clearCroppedPreview()
    }

    private fun clearCurrentImage() {
        if (::binding.isInitialized) {
            Glide.with(this).clear(binding.ivOriginal)
            clearCroppedPreview()
            binding.ivOriginal.setImageDrawable(null)
            binding.originalSelectHint.visibility = View.VISIBLE
        }
        currentImage?.let(::disposeLoadedImage)
        currentImage = null
    }

    private fun disposeLoadedImage(image: LoadedImage) {
        when (image) {
            is LoadedImage.Static -> if (!image.bitmap.isRecycled) image.bitmap.recycle()
            is LoadedImage.Gif -> {
                if (!image.previewFrame.isRecycled) image.previewFrame.recycle()
                image.sourceFile.delete()
            }
        }
    }

    private fun cleanStaleCacheFiles() {
        cacheDir.listFiles()?.forEach { file ->
            if (file.name.startsWith("image_border_crop_") || file.name.startsWith("cropped_")) {
                file.delete()
            }
        }
    }

    /**
     * 查询原图 URI 的显示名称（DISPLAY_NAME）。
     * 如果查询失败或 DISPLAY_NAME 疑似 MediaStore 数字 ID（如 "1000092505.jpg"），
     * 返回 null，由调用方（buildSaveInfo）通过 MIME 类型推断格式并使用时间戳命名兜底。
     */
    private fun queryOriginalFileInfo(uri: Uri): OriginalFileInfo {
        Log.d(TAG, "queryOriginalFileInfo: uri=$uri, lastSegment=${uri.lastPathSegment}")

        var displayName: String? = null
        try {
            contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        displayName = cursor.getString(nameIndex)
                    }
                }
            }
        } catch (e: Exception) {
            // 查询失败（如非 MediaStore URI），使用默认值
        }

        Log.d(TAG, "query result: displayName=$displayName")

        // 如果是 MediaStore ID（如 "1000092505.jpg"），视为无效，返回 null
        val effectiveDisplayName = if (!displayName.isNullOrEmpty() && !isLikelyMediaStoreId(displayName)) {
            displayName
        } else {
            null
        }

        Log.d(TAG, "final: displayName=$effectiveDisplayName")
        return OriginalFileInfo(effectiveDisplayName)
    }

    /**
     * 判断 DISPLAY_NAME 是否疑似 MediaStore 数字 ID 而非真实文件名。
     * MIUI 等 ROM 上 Photo Picker URI 查询 DISPLAY_NAME 可能返回 "1000092505.jpg"
     * （数字 ID + 扩展名），此时应忽略该值，改用 MIME 类型推断格式。
     *
     * 判断逻辑：先去掉扩展名（最后一个点之后的部分），再检查剩余部分是否纯数字。
     * 例如 "1000092505.jpg" → 去掉 ".jpg" → "1000092505" → 纯数字 → 返回 true。
     *
     * @param name 待校验的显示名称
     * @return true 表示疑似 MediaStore ID（去扩展名后纯数字），应忽略
     */
    private fun isLikelyMediaStoreId(name: String?): Boolean {
        if (name.isNullOrEmpty()) return true
        // 先去掉扩展名再判断是否纯数字
        val nameWithoutExt = name.substringBeforeLast('.', name)
        return nameWithoutExt.all { it.isDigit() }
    }

    private fun currentDetectionSettings(): DetectionSettings = DetectionSettings(
        borderType = BorderType.AUTO,
        threshold = binding.sbThreshold.value.toInt()
    )

    private fun scheduleAutomaticDetection() {
        invalidateDetectionResult()
        if (currentImage == null) return
        automaticDetectionJob = lifecycleScope.launch {
            delay(AUTOMATIC_DETECTION_DEBOUNCE_MS)
            automaticDetectionJob = null
            startDetection(saveAfterDetection = false)
        }
    }

    private fun startDetection(saveAfterDetection: Boolean) {
        val image = currentImage ?: run {
            Toast.makeText(this, R.string.no_image_selected, Toast.LENGTH_SHORT).show()
            return
        }
        val settings = currentDetectionSettings()
        detectionJob?.cancel()
        updateActionState()
        binding.tvBorderDetail.setText(R.string.detecting)

        detectionJob = lifecycleScope.launch {
            try {
                val result = when (image) {
                    is LoadedImage.Static -> withContext(Dispatchers.Default) {
                        detector.detect(image.bitmap, settings.borderType, settings.threshold)
                    }
                    is LoadedImage.Gif -> withContext(Dispatchers.Default) {
                        gifProcessor.detect(image, settings.threshold) { current, total ->
                            withContext(Dispatchers.Main.immediate) {
                                if (currentImage === image && currentDetectionSettings() == settings) {
                                    binding.tvBorderDetail.text = getString(
                                        R.string.detecting_gif_frame,
                                        current,
                                        total
                                    )
                                }
                            }
                        }
                    }
                }
                if (currentImage !== image || currentDetectionSettings() != settings) return@launch

                automaticResult = result
                lastResult = result
                lastDetectionSettings = settings
                binding.tvBorderDetail.text = result.summary()

                if (result.hasBorder()) {
                    showCroppedPreview(image, result)
                    if (saveAfterDetection) saveDetectedResult(image, result)
                } else {
                    clearCroppedPreview()
                    if (saveAfterDetection) {
                        Toast.makeText(this@MainActivity, R.string.no_border_detected, Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Log.e(TAG, "detect failed", exception)
                if (currentImage === image) {
                    binding.tvBorderDetail.text = getString(R.string.detect_failed, exception.message ?: "")
                    Toast.makeText(this@MainActivity, R.string.detect_failed_short, Toast.LENGTH_SHORT).show()
                }
            } finally {
                if (detectionJob === coroutineContext[Job]) {
                    detectionJob = null
                    updateActionState()
                }
            }
        }
        updateActionState()
    }

    private fun showCroppedPreview(image: LoadedImage, result: BorderResult) {
        clearCroppedPreview()
        when (image) {
            is LoadedImage.Static -> {
                val preview = detector.crop(image.bitmap, result)
                if (preview !== image.bitmap) staticCroppedPreview = preview
                binding.ivCropped.setImageBitmap(preview)
            }
            is LoadedImage.Gif -> {
                val cropWidth = image.width - result.left - result.right
                val cropHeight = image.height - result.top - result.bottom
                Glide.with(this)
                    .asGif()
                    .load(image.sourceFile)
                    .transform(
                        GifCropTransformation(
                            result.left,
                            result.top,
                            cropWidth,
                            cropHeight
                        )
                    )
                    .into(binding.ivCropped)
            }
        }
    }

    private fun clearCroppedPreview() {
        Glide.with(this).clear(binding.ivCropped)
        binding.ivCropped.setImageDrawable(null)
        staticCroppedPreview?.let { preview ->
            if (!preview.isRecycled) preview.recycle()
        }
        staticCroppedPreview = null
    }

    private fun restoreCroppedPreview(image: LoadedImage, result: BorderResult) {
        if (result.hasBorder() || result.manuallyAdjusted) {
            showCroppedPreview(image, result)
        } else {
            clearCroppedPreview()
        }
    }

    private fun installRepeatingPress(
        view: View,
        onSingleClick: () -> Unit,
        onRepeatStep: () -> Unit,
        onRepeatFinished: () -> Unit
    ): () -> Unit {
        var pressing = false
        var repeated = false

        fun finishPress(refreshPreview: Boolean) {
            val shouldRefresh = refreshPreview && repeated
            pressing = false
            repeated = false
            view.isPressed = false
            if (shouldRefresh) onRepeatFinished()
        }

        val repeatAction = object : Runnable {
            override fun run() {
                if (!pressing || !view.isEnabled) {
                    finishPress(refreshPreview = true)
                    return
                }
                repeated = true
                onRepeatStep()
                if (pressing && view.isEnabled) {
                    view.postDelayed(this, MANUAL_REPEAT_INTERVAL_MS)
                } else {
                    finishPress(refreshPreview = true)
                }
            }
        }

        fun cancelCallbacks(refreshPreview: Boolean) {
            view.removeCallbacks(repeatAction)
            finishPress(refreshPreview)
        }

        view.setOnClickListener { onSingleClick() }
        view.setOnTouchListener { touchedView, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!touchedView.isEnabled) return@setOnTouchListener false
                    cancelCallbacks(refreshPreview = false)
                    pressing = true
                    touchedView.isPressed = true
                    touchedView.postDelayed(
                        repeatAction,
                        ViewConfiguration.getLongPressTimeout().toLong()
                    )
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val inside = event.x >= 0f && event.x < touchedView.width &&
                        event.y >= 0f && event.y < touchedView.height
                    if (!inside) {
                        cancelCallbacks(refreshPreview = true)
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    val shouldClick = pressing && !repeated && touchedView.isPressed
                    val shouldRefresh = repeated
                    cancelCallbacks(refreshPreview = shouldRefresh)
                    if (shouldClick) touchedView.performClick()
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    cancelCallbacks(refreshPreview = true)
                    true
                }

                else -> true
            }
        }

        return { cancelCallbacks(refreshPreview = false) }
    }

    private fun configureAdjustmentDialogWindow(dialog: BottomSheetDialog, sheetRoot: View) {
        dialog.window?.let { sheetWindow ->
            val surfaceColor = ContextCompat.getColor(this, R.color.surface)
            WindowCompat.setDecorFitsSystemWindows(sheetWindow, false)
            sheetWindow.navigationBarColor = surfaceColor
            sheetWindow.setDimAmount(0f)
            sheetWindow.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                sheetWindow.navigationBarDividerColor = surfaceColor
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                sheetWindow.isNavigationBarContrastEnforced = false
            }
            WindowCompat.getInsetsController(sheetWindow, sheetRoot).apply {
                isAppearanceLightNavigationBars = true
            }
        }
    }

    private fun showCropAdjustmentPanel() {
        val image = currentImage ?: return
        val original = lastResult ?: return
        if (imageLoadJob != null || detectionJob != null || saveJob != null) return
        val automatic = automaticResult ?: original.copy(manuallyAdjusted = false)

        cropAdjustmentDialog?.dismiss()
        val sheet = BottomSheetCropAdjustmentBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(sheet.root)
        configureAdjustmentDialogWindow(dialog, sheet.root)
        cropAdjustmentDialog = dialog

        val state = CropAdjustmentState(image.width, image.height, original, automatic)
        val originalScrollBottomPadding = binding.rootScrollView.paddingBottom
        val controls = listOf(
            CropControl(
                CropSide.TOP,
                R.string.manual_adjust_top,
                sheet.rowTop.tvSideLabel,
                sheet.rowTop.sliderCrop,
                sheet.rowTop.btnMinus,
                sheet.rowTop.btnPlus,
                sheet.rowTop.tvValue
            ),
            CropControl(
                CropSide.BOTTOM,
                R.string.manual_adjust_bottom,
                sheet.rowBottom.tvSideLabel,
                sheet.rowBottom.sliderCrop,
                sheet.rowBottom.btnMinus,
                sheet.rowBottom.btnPlus,
                sheet.rowBottom.tvValue
            ),
            CropControl(
                CropSide.LEFT,
                R.string.manual_adjust_left,
                sheet.rowLeft.tvSideLabel,
                sheet.rowLeft.sliderCrop,
                sheet.rowLeft.btnMinus,
                sheet.rowLeft.btnPlus,
                sheet.rowLeft.tvValue
            ),
            CropControl(
                CropSide.RIGHT,
                R.string.manual_adjust_right,
                sheet.rowRight.tvSideLabel,
                sheet.rowRight.sliderCrop,
                sheet.rowRight.btnMinus,
                sheet.rowRight.btnPlus,
                sheet.rowRight.tvValue
            )
        )
        var rendering = false
        var applied = false
        var previewJob: Job? = null
        val repeatPressCleanups = mutableListOf<() -> Unit>()

        fun adjustedResult(): BorderResult = state.toResult(original)

        fun showPreviewNow() {
            previewJob?.cancel()
            previewJob = null
            if (currentImage === image && lastResult === original) {
                val result = adjustedResult()
                binding.tvBorderDetail.text = result.summary()
                showCroppedPreview(image, result)
            }
        }

        fun schedulePreview() {
            previewJob?.cancel()
            previewJob = lifecycleScope.launch {
                delay(MANUAL_PREVIEW_DEBOUNCE_MS)
                showPreviewNow()
            }
        }

        fun renderControls() {
            rendering = true
            controls.forEach { control ->
                val value = state.value(control.side)
                val maximum = state.maximum(control.side)
                control.value.text = getString(R.string.manual_adjust_value, value)
                control.minus.isEnabled = value > 0
                control.plus.isEnabled = value < maximum
                control.slider.valueFrom = 0f
                control.slider.valueTo = maxOf(1, state.rangeMaximum(control.side)).toFloat()
                control.slider.isEnabled = state.rangeMaximum(control.side) > 0
                if (control.slider.value.roundToInt() != value) {
                    control.slider.value = value.toFloat()
                }
            }
            rendering = false
            binding.tvBorderDetail.text = adjustedResult().summary()
        }

        fun update(side: CropSide, value: Int, immediatePreview: Boolean) {
            state.update(side, value)
            renderControls()
            if (immediatePreview) showPreviewNow() else schedulePreview()
        }

        controls.forEach { control ->
            val sideName = getString(control.labelRes)
            control.label.text = sideName
            control.minus.contentDescription = "$sideName，${getString(R.string.manual_adjust_decrease)}"
            control.plus.contentDescription = "$sideName，${getString(R.string.manual_adjust_increase)}"
            repeatPressCleanups += installRepeatingPress(
                control.minus,
                onSingleClick = {
                    update(control.side, state.value(control.side) - 1, immediatePreview = true)
                },
                onRepeatStep = {
                    update(control.side, state.value(control.side) - 1, immediatePreview = false)
                },
                onRepeatFinished = ::showPreviewNow
            )
            repeatPressCleanups += installRepeatingPress(
                control.plus,
                onSingleClick = {
                    update(control.side, state.value(control.side) + 1, immediatePreview = true)
                },
                onRepeatStep = {
                    update(control.side, state.value(control.side) + 1, immediatePreview = false)
                },
                onRepeatFinished = ::showPreviewNow
            )
            control.slider.addOnChangeListener { _, value, fromUser ->
                if (fromUser && !rendering) {
                    update(control.side, value.roundToInt(), immediatePreview = false)
                }
            }
            control.slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) = Unit

                override fun onStopTrackingTouch(slider: Slider) {
                    if (!rendering) showPreviewNow()
                }
            })
        }

        sheet.btnReset.setOnClickListener {
            state.resetToAutomatic()
            renderControls()
            showPreviewNow()
        }
        sheet.btnCancel.setOnClickListener { dialog.dismiss() }
        sheet.btnApply.setOnClickListener {
            if (currentImage === image && lastResult === original) {
                previewJob?.cancel()
                val result = adjustedResult()
                lastResult = result
                binding.tvBorderDetail.text = result.summary()
                restoreCroppedPreview(image, result)
                applied = true
                updateActionState()
            }
            dialog.dismiss()
        }
        dialog.setOnDismissListener {
            previewJob?.cancel()
            repeatPressCleanups.forEach { it() }
            if (cropAdjustmentDialog === dialog) cropAdjustmentDialog = null
            binding.rootScrollView.updatePadding(bottom = originalScrollBottomPadding)
            if (!applied && currentImage === image && lastResult === original) {
                binding.tvBorderDetail.text = original.summary()
                restoreCroppedPreview(image, original)
            }
            updateActionState()
        }

        val baseBottomPadding = resources.getDimensionPixelSize(R.dimen.manual_sheet_bottom_padding)
        ViewCompat.setOnApplyWindowInsetsListener(sheet.root) { view, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.updatePadding(bottom = baseBottomPadding + insets.bottom)
            windowInsets
        }

        dialog.setOnShowListener {
            configureAdjustmentDialogWindow(dialog, sheet.root)
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { bottomSheet ->
                val sheetHeight = (resources.displayMetrics.heightPixels * MANUAL_SHEET_HEIGHT_RATIO)
                    .roundToInt()
                binding.rootScrollView.updatePadding(
                    bottom = maxOf(
                        originalScrollBottomPadding,
                        sheetHeight + resources.getDimensionPixelSize(R.dimen.manual_preview_top_margin)
                    )
                )
                bottomSheet.setBackgroundColor(Color.TRANSPARENT)
                bottomSheet.layoutParams = bottomSheet.layoutParams.apply { height = sheetHeight }
                BottomSheetBehavior.from(bottomSheet).apply {
                    isDraggable = true
                    isHideable = true
                    skipCollapsed = false
                    peekHeight = minOf(
                        sheetHeight,
                        resources.getDimensionPixelSize(R.dimen.manual_sheet_collapsed_height)
                    )
                    this.state = BottomSheetBehavior.STATE_EXPANDED
                }
            }
            ViewCompat.requestApplyInsets(sheet.root)
            binding.rootScrollView.post {
                val previewTopMargin = resources.getDimensionPixelSize(R.dimen.manual_preview_top_margin)
                binding.rootScrollView.smoothScrollTo(
                    0,
                    (binding.cardCroppedPreview.top - previewTopMargin).coerceAtLeast(0)
                )
            }
        }

        showCroppedPreview(image, adjustedResult())
        renderControls()
        dialog.show()
    }

    private fun cropAndSave() {
        val image = currentImage ?: run {
            Toast.makeText(this, R.string.no_image_selected, Toast.LENGTH_SHORT).show()
            return
        }
        val result = lastResult
        if (result == null || lastDetectionSettings != currentDetectionSettings()) {
            startDetection(saveAfterDetection = true)
            return
        }

        if (!result.hasBorder()) {
            Toast.makeText(this, R.string.no_border_detected, Toast.LENGTH_SHORT).show()
            return
        }
        saveDetectedResult(image, result)
    }

    private fun saveDetectedResult(image: LoadedImage, result: BorderResult) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        startSave(image, result)
    }

    private fun saveCroppedImage() {
        val image = currentImage ?: return
        val result = lastResult ?: return
        if (lastDetectionSettings != currentDetectionSettings()) return
        saveDetectedResult(image, result)
    }

    private fun invalidateDetectionResult() {
        automaticDetectionJob?.cancel()
        automaticDetectionJob = null
        detectionJob?.cancel()
        detectionJob = null
        lastResult = null
        automaticResult = null
        lastDetectionSettings = null
        cropAdjustmentDialog?.dismiss()
        cropAdjustmentDialog = null
        if (::binding.isInitialized) {
            clearCroppedPreview()
            updateActionState()
        }
    }

    private fun updateActionState() {
        if (!::binding.isInitialized) return
        val loading = imageLoadJob != null
        val detecting = detectionJob != null
        val saving = saveJob != null
        val idle = !loading && !detecting && !saving
        updateBatchUi(saving)
        binding.btnCrop.isEnabled = currentImage != null && idle
        binding.btnSelect.isEnabled = idle
        binding.cardOriginalPreview.isEnabled = idle
        binding.cardRecentImage.isEnabled = idle && (batchSession != null || recentImageSelectable)
        binding.sbThreshold.isEnabled = !loading && !saving
        val adjustable = lastResult != null && lastDetectionSettings == currentDetectionSettings()
        binding.cardBorderInfo.isEnabled = adjustable && idle
        binding.tvAdjustHint.visibility = if (adjustable) View.VISIBLE else View.GONE
    }

    private fun updateBatchUi(saving: Boolean = saveJob != null) {
        val batch = batchSession
        binding.tvBatchProgress.visibility = if (batch == null) View.GONE else View.VISIBLE
        if (batch != null) {
            binding.tvBatchProgress.text = getString(
                R.string.batch_progress,
                batch.position,
                batch.total
            )
        }
        binding.btnSelect.setText(
            if (batch != null && !batch.isLast) R.string.batch_skip else R.string.select_image
        )
        binding.btnCrop.setText(
            when {
                saving -> R.string.saving_action
                batch != null && !batch.isLast -> R.string.batch_save_next
                else -> R.string.crop_border
            }
        )
    }

    private fun skipCurrentBatchItem(batch: BatchSession<Uri>) {
        if (batchSession !== batch || batch.isLast || isBusy()) return
        val next = batch.skipAndAdvance() ?: return
        updateActionState()
        loadImage(next, takePersistablePermission = false)
    }

    private fun startSave(image: LoadedImage, result: BorderResult) {
        if (saveJob != null) return
        val batchAtStart = batchSession
        showSavingHint(image)
        saveJob = lifecycleScope.launch {
            var outputTemp: File? = null
            var savedSuccessfully = false
            try {
                val saveInfo = buildSaveInfo(image is LoadedImage.Gif)
                val saved = when (image) {
                    is LoadedImage.Static -> {
                        val cropped = withContext(Dispatchers.Default) {
                            detector.crop(image.bitmap, result)
                        }
                        val uri = withContext(Dispatchers.IO) {
                            publishImage(saveInfo) { output ->
                                check(cropped.compress(saveInfo.compressFormat!!, 100, output)) {
                                    "图片编码失败"
                                }
                            }
                        }
                        SavedImage(uri, cropped)
                    }
                    is LoadedImage.Gif -> {
                        val temp = File.createTempFile("cropped_", ".gif", cacheDir)
                        outputTemp = temp
                        withContext(Dispatchers.Default) {
                            gifProcessor.encodeCropped(image, result, temp) { current, total ->
                                withContext(Dispatchers.Main.immediate) {
                                    if (saveJob === coroutineContext[Job]) {
                                        val progress = getString(
                                            R.string.encoding_gif_frame,
                                            current,
                                            total
                                        )
                                        binding.tvBorderDetail.text = progress
                                    }
                                }
                            }
                        }
                        val uri = withContext(Dispatchers.IO) {
                            publishImage(saveInfo) { output ->
                                FileInputStream(temp).use { it.copyTo(output) }
                            }
                        }
                        val thumbnail = withContext(Dispatchers.Default) {
                            detector.crop(image.previewFrame, result)
                        }
                        SavedImage(uri, thumbnail)
                    }
                }

                binding.tvBorderDetail.text = result.summary()
                showRecentImage(RecentImage(saved.uri, saveInfo.filename, saved.thumbnail))
                savedSuccessfully = true
                if (batchAtStart == null) {
                    Toast.makeText(this@MainActivity, R.string.saved_success, Toast.LENGTH_SHORT).show()
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Log.e(TAG, "save image failed", exception)
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.save_failed, exception.message ?: ""),
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                outputTemp?.delete()
                if (saveJob === coroutineContext[Job]) {
                    saveJob = null
                    updateActionState()
                    if (savedSuccessfully) finishSuccessfulSave(batchAtStart)
                }
            }
        }
        updateActionState()
    }

    private fun finishSuccessfulSave(batchAtStart: BatchSession<Uri>?) {
        if (batchAtStart == null || batchSession !== batchAtStart) return
        val next = batchAtStart.saveAndAdvance()
        if (next != null) {
            updateActionState()
            loadImage(next, takePersistablePermission = false)
            return
        }

        val savedCount = batchAtStart.savedCount
        val skippedCount = batchAtStart.skippedCount
        batchSession = null
        updateActionState()
        Toast.makeText(
            this,
            getString(R.string.batch_complete, savedCount, skippedCount),
            Toast.LENGTH_LONG
        ).show()
    }

    private fun showSavingHint(image: LoadedImage) {
        if (image is LoadedImage.Gif) {
            Toast.makeText(this, R.string.saving_gif_toast, Toast.LENGTH_LONG).show()
            binding.tvBorderDetail.text = getString(
                R.string.encoding_gif_frame,
                0,
                image.metadata.frameCount
            )
        }
        binding.btnCrop.setText(R.string.saving_action)
    }

    private fun publishImage(saveInfo: SaveInfo, writer: (java.io.OutputStream) -> Unit): Uri {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return publishLegacyImage(saveInfo, writer)
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, saveInfo.filename)
            put(MediaStore.Images.Media.MIME_TYPE, saveInfo.mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, saveInfo.relativePath)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        var uri: Uri? = null
        try {
            uri = checkNotNull(
                contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ) { "无法创建媒体文件" }
            checkNotNull(contentResolver.openOutputStream(uri)).use(writer)
            contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                null,
                null
            )
            return uri
        } catch (exception: Exception) {
            uri?.let { contentResolver.delete(it, null, null) }
            throw exception
        }
    }

    @Suppress("DEPRECATION")
    private fun publishLegacyImage(
        saveInfo: SaveInfo,
        writer: (java.io.OutputStream) -> Unit
    ): Uri {
        val pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val directory = File(pictures, "ImageBorderCrop")
        check(directory.exists() || directory.mkdirs()) { "无法创建保存目录" }
        val destination = uniqueDestination(directory, saveInfo.filename)
        try {
            FileOutputStream(destination).use(writer)
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, destination.name)
                put(MediaStore.Images.Media.MIME_TYPE, saveInfo.mimeType)
                put(MediaStore.Images.Media.DATA, destination.absolutePath)
                put(MediaStore.Images.Media.SIZE, destination.length())
            }
            return checkNotNull(
                contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ) { "无法登记媒体文件" }
        } catch (exception: Exception) {
            destination.delete()
            throw exception
        }
    }

    private fun uniqueDestination(directory: File, filename: String): File {
        val requested = File(directory, filename)
        if (!requested.exists()) return requested
        val dot = filename.lastIndexOf('.')
        val base = if (dot > 0) filename.substring(0, dot) else filename
        val extension = if (dot > 0) filename.substring(dot) else ""
        var suffix = 1
        while (true) {
            val candidate = File(directory, "$base ($suffix)$extension")
            if (!candidate.exists()) return candidate
            suffix++
        }
    }

    /**
     * 根据原图元信息构建保存参数（文件名、MIME 类型、压缩格式、保存目录）。
     * - 文件名：有原文件名时用原文件名，无原文件名时用 image_{timestamp}.{ext}
     * - 格式：有原文件名从扩展名推断；无原文件名通过 contentResolver.getType() 检测 MIME 推断，
     *   检测失败默认 JPEG（照片最常见格式），保证 JPG 原图裁剪后仍为 JPG
     * - 保存目录：固定 Pictures/ImageBorderCrop
     */
    private fun buildSaveInfo(isGif: Boolean): SaveInfo {
        val originalName = originalDisplayName
        val baseName: String
        val ext: String

        if (!originalName.isNullOrEmpty() && originalName.contains('.')) {
            // 有原文件名且含扩展名，从扩展名推断格式
            val dotIndex = originalName.lastIndexOf('.')
            baseName = originalName.substring(0, dotIndex)
            ext = originalName.substring(dotIndex + 1).lowercase()
        } else if (!originalName.isNullOrEmpty()) {
            // 有文件名但无扩展名，通过 MIME 检测格式
            baseName = originalName
            ext = detectExtFromMime()
        } else {
            // 查询失败或为 MediaStore ID，回退到时间戳命名，通过 MIME 检测格式
            baseName = "image_${System.currentTimeMillis()}"
            ext = detectExtFromMime()
        }

        val (effectiveExt, mimeType, compressFormat) = when {
            isGif -> Triple("gif", "image/gif", null)
            ext == "jpg" || ext == "jpeg" -> Triple("jpg", "image/jpeg", Bitmap.CompressFormat.JPEG)
            ext == "png" -> Triple("png", "image/png", Bitmap.CompressFormat.PNG)
            ext == "webp" -> Triple("webp", "image/webp", Bitmap.CompressFormat.WEBP)
            else -> Triple("jpg", "image/jpeg", Bitmap.CompressFormat.JPEG)
        }

        val filename = "${baseName}.$effectiveExt"
        val relativePath = "Pictures/ImageBorderCrop"

        return SaveInfo(filename, mimeType, compressFormat, relativePath)
    }

    /**
     * 通过 contentResolver.getType() 获取当前图片的 MIME 类型，推断文件扩展名。
     * 检测失败或无法识别时默认返回 "jpg"（照片最常见格式）。
     */
    private fun detectExtFromMime(): String {
        val uri = currentImageUri ?: return "jpg"
        return try {
            when (contentResolver.getType(uri)) {
                "image/jpeg" -> "jpg"
                "image/png" -> "png"
                "image/webp" -> "webp"
                "image/gif" -> "gif"
                else -> "jpg"
            }
        } catch (e: Exception) {
            "jpg"
        }
    }

    /** 原图元信息查询结果 */
    private data class OriginalFileInfo(
        val displayName: String?
    )

    private data class RecentImage(
        val uri: Uri,
        val displayName: String,
        val thumbnail: Bitmap?
    )

    /** 与检测结果绑定的界面参数，参数改变后旧结果立即失效。 */
    private data class DetectionSettings(
        val borderType: BorderType,
        val threshold: Int
    )

    /** 裁剪图保存参数 */
    private data class SaveInfo(
        val filename: String,
        val mimeType: String,
        val compressFormat: Bitmap.CompressFormat?,
        val relativePath: String
    )

    private data class SavedImage(
        val uri: Uri,
        val thumbnail: Bitmap
    )

    private data class CropControl(
        val side: CropSide,
        val labelRes: Int,
        val label: TextView,
        val slider: Slider,
        val minus: View,
        val plus: View,
        val value: TextView
    )

    companion object {
        private const val TAG = "ImageBorderCrop"
        private const val AUTOMATIC_DETECTION_DEBOUNCE_MS = 250L
        private const val MANUAL_PREVIEW_DEBOUNCE_MS = 80L
        private const val MANUAL_REPEAT_INTERVAL_MS = 55L
        private const val MANUAL_SHEET_HEIGHT_RATIO = 0.46f
    }
}
