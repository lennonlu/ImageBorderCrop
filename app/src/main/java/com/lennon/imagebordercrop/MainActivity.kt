package com.lennon.imagebordercrop

import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.lennon.imagebordercrop.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var currentImageUri: Uri? = null
    private var currentBitmap: Bitmap? = null
    private var lastResult: BorderResult? = null
    private val detector = BorderDetector()

    // 原图显示名称，用于裁剪保存时的文件命名
    private var originalDisplayName: String? = null

    // Photo Picker 选图回调
    private val pickMedia = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            loadImage(uri, fromShare = false)
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
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 默认选中"自动检测"
        binding.rgBorderType.check(R.id.rbAuto)

        // 阈值滑块
        binding.sbThreshold.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                binding.tvThresholdValue.text = progress.toString()
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        // 选择图片（Photo Picker）
        binding.btnSelect.setOnClickListener {
            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }

        // 检测边框
        binding.btnDetect.setOnClickListener {
            detectBorder()
        }

        // 裁剪并保存
        binding.btnCrop.setOnClickListener {
            cropAndSave()
        }

        // 处理通过系统"分享"接收到的图片
        handleShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
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
            sharedUri?.let { loadImage(it, fromShare = true) }
        }
    }

    /**
     * 加载图片并查询其显示名称。
     *
     * @param uri 图片 URI
     * @param fromShare 是否来自系统"分享"。分享 URI 不能 takePersistableUriPermission，
     *                  其读取权限仅在当前 Activity 生命周期内有效。
     */
    private fun loadImage(uri: Uri, fromShare: Boolean = false) {
        currentImageUri = uri

        // Photo Picker 返回的 URI 支持持久化权限；分享来的 URI 不支持
        if (!fromShare) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: SecurityException) {
                // 某些 URI 不支持持久化权限，忽略即可
            }
        }

        // 查询原图显示名称，用于后续保存时的文件命名
        val fileInfo = queryOriginalFileInfo(uri)
        originalDisplayName = fileInfo.displayName

        // 解码图片（按原始尺寸）
        val inputStream = contentResolver.openInputStream(uri)
        currentBitmap = BitmapFactory.decodeStream(inputStream)
        inputStream?.close()

        currentBitmap?.let { bitmap ->
            binding.ivOriginal.setImageBitmap(bitmap)
            binding.tvBorderDetail.text = "图片尺寸: ${bitmap.width} x ${bitmap.height}"
            binding.ivCropped.setImageBitmap(null)
            lastResult = null
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

    private fun getSelectedBorderType(): BorderType {
        return when (binding.rgBorderType.checkedRadioButtonId) {
            R.id.rbBlack -> BorderType.BLACK
            R.id.rbWhite -> BorderType.WHITE
            else -> BorderType.AUTO
        }
    }

    private fun detectBorder() {
        val bitmap = currentBitmap ?: run {
            Toast.makeText(this, R.string.no_image_selected, Toast.LENGTH_SHORT).show()
            return
        }

        val borderType = getSelectedBorderType()
        val threshold = binding.sbThreshold.progress

        // 在后台线程处理大图
        val result = detector.detect(bitmap, borderType, threshold)
        lastResult = result

        binding.tvBorderDetail.text = result.summary()

        if (result.hasBorder()) {
            // 预览裁剪结果
            val cropped = detector.crop(bitmap, result)
            binding.ivCropped.setImageBitmap(cropped)
        } else {
            binding.ivCropped.setImageBitmap(null)
            Toast.makeText(this, R.string.no_border_detected, Toast.LENGTH_SHORT).show()
        }
    }

    private fun cropAndSave() {
        val bitmap = currentBitmap ?: run {
            Toast.makeText(this, R.string.no_image_selected, Toast.LENGTH_SHORT).show()
            return
        }

        val result = lastResult ?: detectBorder().let {
            lastResult ?: run {
                Toast.makeText(this, R.string.no_border_detected, Toast.LENGTH_SHORT).show()
                return
            }
        }

        if (!result.hasBorder()) {
            Toast.makeText(this, R.string.no_border_detected, Toast.LENGTH_SHORT).show()
            return
        }

        val cropped = detector.crop(bitmap, result)

        // 检查保存权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveCroppedImageDirect(cropped)
        } else {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED
            ) {
                saveCroppedImageDirect(cropped)
            } else {
                requestPermission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }

    private fun saveCroppedImage() {
        val bitmap = currentBitmap ?: return
        val result = lastResult ?: return
        val cropped = detector.crop(bitmap, result)
        saveCroppedImageDirect(cropped)
    }

    /**
     * 将裁剪后的 Bitmap 保存到相册。
     * - 文件名：有原文件名时用原文件名，无原文件名时用 image_{timestamp}.{ext}
     * - 保存目录：固定 Pictures/ImageBorderCrop
     * - 格式：有原扩展名从扩展名推断；无原扩展名通过 MIME 类型推断，检测失败默认 JPEG
     */
    private fun saveCroppedImageDirect(bitmap: Bitmap) {
        try {
            val saveInfo = buildSaveInfo()

            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, saveInfo.filename)
                put(MediaStore.Images.Media.MIME_TYPE, saveInfo.mimeType)
                put(MediaStore.Images.Media.RELATIVE_PATH, saveInfo.relativePath)
            }

            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            uri?.let {
                contentResolver.openOutputStream(it)?.use { os ->
                    bitmap.compress(saveInfo.compressFormat, 100, os)
                }
            }

            binding.ivCropped.setImageBitmap(bitmap)
            Toast.makeText(this, R.string.saved_success, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "保存失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 根据原图元信息构建保存参数（文件名、MIME 类型、压缩格式、保存目录）。
     * - 文件名：有原文件名时用原文件名，无原文件名时用 image_{timestamp}.{ext}
     * - 格式：有原文件名从扩展名推断；无原文件名通过 contentResolver.getType() 检测 MIME 推断，
     *   检测失败默认 JPEG（照片最常见格式），保证 JPG 原图裁剪后仍为 JPG
     * - 保存目录：固定 Pictures/ImageBorderCrop
     */
    private fun buildSaveInfo(): SaveInfo {
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

        val (effectiveExt, mimeType, compressFormat) = when (ext) {
            "jpg", "jpeg" -> Triple("jpg", "image/jpeg", Bitmap.CompressFormat.JPEG)
            "png" -> Triple("png", "image/png", Bitmap.CompressFormat.PNG)
            "webp" -> Triple("webp", "image/webp", Bitmap.CompressFormat.WEBP)
            else -> Triple("jpg", "image/jpeg", Bitmap.CompressFormat.JPEG) // 默认 JPEG（照片最常见格式）
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

    /** 裁剪图保存参数 */
    private data class SaveInfo(
        val filename: String,
        val mimeType: String,
        val compressFormat: Bitmap.CompressFormat,
        val relativePath: String
    )

    companion object {
        private const val TAG = "ImageBorderCrop"
    }
}
