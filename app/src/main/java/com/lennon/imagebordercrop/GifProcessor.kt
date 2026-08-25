package com.lennon.imagebordercrop

import android.graphics.Bitmap
import android.graphics.Color
import com.bumptech.glide.gifdecoder.GifDecoder
import com.bumptech.glide.gifdecoder.GifHeader
import com.bumptech.glide.gifdecoder.GifHeaderParser
import com.bumptech.glide.gifdecoder.StandardGifDecoder
import com.bumptech.glide.gifencoder.AnimatedGifEncoder
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

class GifProcessor(
    private val detector: BorderDetector
) {
    fun hasGifSignature(input: InputStream): Boolean {
        val signature = ByteArray(GIF_SIGNATURE_SIZE)
        var offset = 0
        while (offset < signature.size) {
            val read = input.read(signature, offset, signature.size - offset)
            if (read < 0) return false
            offset += read
        }
        val text = signature.toString(Charsets.US_ASCII)
        return text == GIF_87A || text == GIF_89A
    }

    suspend fun loadGif(input: InputStream, cacheDir: File): LoadedImage.Gif {
        val sourceFile = File.createTempFile("image_border_crop_", ".gif", cacheDir)
        try {
            copyWithLimit(input, sourceFile)
            val decoder = createDecoder(sourceFile)
            try {
                GifSafetyLimits.validate(
                    sourceFile.length(),
                    decoder.width,
                    decoder.height,
                    decoder.frameCount
                )
                decoder.advance()
                val firstFrame = checkNotNull(decoder.nextFrame) { "GIF 首帧解码失败" }
                check(decoder.status != GifDecoder.STATUS_FORMAT_ERROR) { "GIF 文件格式损坏" }
                return LoadedImage.Gif(
                    sourceFile = sourceFile,
                    metadata = GifMetadata(
                        width = decoder.width,
                        height = decoder.height,
                        frameCount = decoder.frameCount,
                        netscapeLoopCount = decoder.netscapeLoopCount,
                        frameDelaysMs = List(decoder.frameCount) { decoder.getDelay(it) }
                    ),
                    previewFrame = firstFrame
                )
            } finally {
                decoder.clear()
            }
        } catch (exception: Exception) {
            sourceFile.delete()
            throw exception
        }
    }

    suspend fun detect(
        image: LoadedImage.Gif,
        threshold: Int,
        onProgress: suspend (current: Int, total: Int) -> Unit
    ): BorderResult {
        val decoder = createDecoder(image.sourceFile)
        val results = ArrayList<BorderResult>(image.metadata.frameCount)
        val pixels = IntArray(image.width * image.height)
        val sampleIndices = GifSampleFrames.indices(image.metadata.frameCount)
        val sampleTargets = BooleanArray(image.metadata.frameCount)
        sampleIndices.forEach { sampleTargets[it] = true }
        val sampledContent = ArrayList<ContentRegion>(sampleIndices.size)
        val progressStep = maxOf(1, image.metadata.frameCount / MAX_PROGRESS_UPDATES)
        try {
            repeat(image.metadata.frameCount) { index ->
                currentCoroutineContext().ensureActive()
                decoder.advance()
                val frame = checkNotNull(decoder.nextFrame) { "GIF 第 ${index + 1} 帧解码失败" }
                try {
                    frame.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
                    results += detector.detectGifColorBorder(
                        pixels,
                        image.width,
                        image.height,
                        threshold
                    )
                    if (sampleTargets[index]) {
                        detector.detectGifSampleContent(
                            pixels,
                            image.width,
                            image.height
                        )?.let(sampledContent::add)
                    }
                } finally {
                    frame.recycleSafely()
                }
                val completed = index + 1
                if (completed == 1 || completed == image.metadata.frameCount || completed % progressStep == 0) {
                    onProgress(completed, image.metadata.frameCount)
                }
            }
            check(decoder.status != GifDecoder.STATUS_FORMAT_ERROR) { "GIF 文件格式损坏" }
            return GifBorderAggregator.aggregate(
                results = results,
                threshold = threshold,
                width = image.width,
                height = image.height,
                sampledContent = sampledContent,
                sampledContentFrames = sampleIndices.size
            )
        } finally {
            decoder.clear()
        }
    }

    suspend fun encodeCropped(
        image: LoadedImage.Gif,
        result: BorderResult,
        outputFile: File,
        onProgress: suspend (current: Int, total: Int) -> Unit
    ) {
        val cropWidth = image.width - result.left - result.right
        val cropHeight = image.height - result.top - result.bottom
        require(cropWidth > 0 && cropHeight > 0) { "GIF 裁剪区域无效" }

        val decoder = createDecoder(image.sourceFile)
        val encoder = AnimatedGifEncoder()
        var encoderStarted = false
        try {
            FileOutputStream(outputFile).use { output ->
                encoder.setSize(cropWidth, cropHeight)
                encoder.setQuality(GIF_ENCODING_QUALITY)
                encoder.setDispose(DISPOSE_TO_BACKGROUND)
                if (image.metadata.netscapeLoopCount >= 0) {
                    encoder.setRepeat(image.metadata.netscapeLoopCount)
                }
                check(encoder.start(output)) { "GIF 编码器启动失败" }
                encoderStarted = true

                repeat(image.metadata.frameCount) { index ->
                    currentCoroutineContext().ensureActive()
                    decoder.advance()
                    val frame = checkNotNull(decoder.nextFrame) { "GIF 第 ${index + 1} 帧解码失败" }
                    val cropped = try {
                        Bitmap.createBitmap(
                            frame,
                            result.left,
                            result.top,
                            cropWidth,
                            cropHeight
                        )
                    } finally {
                        frame.recycleSafely()
                    }
                    try {
                        setPerFrameTransparency(encoder, cropped.hasTransparentPixel())
                        encoder.setDelay(image.metadata.frameDelaysMs[index].coerceAtLeast(0))
                        check(encoder.addFrame(cropped)) { "GIF 第 ${index + 1} 帧编码失败" }
                    } finally {
                        cropped.recycleSafely()
                    }
                    onProgress(index + 1, image.metadata.frameCount)
                }
                check(encoder.finish()) { "GIF 编码收尾失败" }
                encoderStarted = false
            }
        } finally {
            if (encoderStarted) encoder.finish()
            decoder.clear()
        }
    }

    private suspend fun copyWithLimit(input: InputStream, destination: File) {
        FileOutputStream(destination).use { output ->
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            var total = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                require(total <= GifSafetyLimits.MAX_SOURCE_BYTES) {
                    "GIF 文件超过 100 MiB 安全上限"
                }
                output.write(buffer, 0, read)
            }
        }
    }

    private fun createDecoder(file: File): StandardGifDecoder {
        val data = mapReadOnly(file)
        val header: GifHeader = GifHeaderParser().setData(data.asReadOnlyBuffer()).parseHeader()
        require(header.status == GifDecoder.STATUS_OK) { "GIF 文件格式损坏" }
        GifSafetyLimits.validate(file.length(), header.width, header.height, header.numFrames)
        return StandardGifDecoder(SimpleBitmapProvider, header, data, 1).apply {
            setDefaultBitmapConfig(Bitmap.Config.ARGB_8888)
        }
    }

    private fun mapReadOnly(file: File): ByteBuffer =
        FileInputStream(file).channel.use { channel ->
            channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
        }

    /**
     * AnimatedGifEncoder exposes setting but not clearing a per-frame transparent color.
     * The dependency is pinned to 4.16.0; clearing its nullable field prevents an opaque frame
     * after a transparent frame from accidentally treating black as transparent.
     */
    private fun setPerFrameTransparency(encoder: AnimatedGifEncoder, transparent: Boolean) {
        TRANSPARENT_FIELD.set(encoder, null)
        if (transparent) encoder.setTransparent(Color.TRANSPARENT)
    }

    private fun Bitmap.hasTransparentPixel(): Boolean {
        if (!hasAlpha()) return false
        val row = IntArray(width)
        for (y in 0 until height) {
            getPixels(row, 0, width, 0, y, width, 1)
            if (row.any { Color.alpha(it) < 128 }) return true
        }
        return false
    }

    private fun Bitmap.recycleSafely() {
        if (!isRecycled) recycle()
    }

    private object SimpleBitmapProvider : GifDecoder.BitmapProvider {
        override fun obtain(width: Int, height: Int, config: Bitmap.Config): Bitmap =
            Bitmap.createBitmap(width, height, config)

        override fun release(bitmap: Bitmap) {
            if (!bitmap.isRecycled) bitmap.recycle()
        }

        override fun obtainByteArray(size: Int): ByteArray = ByteArray(size)
        override fun release(bytes: ByteArray) = Unit
        override fun obtainIntArray(size: Int): IntArray = IntArray(size)
        override fun release(array: IntArray) = Unit
    }

    companion object {
        private const val GIF_SIGNATURE_SIZE = 6
        private const val GIF_87A = "GIF87a"
        private const val GIF_89A = "GIF89a"
        private const val COPY_BUFFER_SIZE = 64 * 1024
        private const val GIF_ENCODING_QUALITY = 10
        private const val DISPOSE_TO_BACKGROUND = 2
        private const val MAX_PROGRESS_UPDATES = 40

        private val TRANSPARENT_FIELD = AnimatedGifEncoder::class.java
            .getDeclaredField("transparent")
            .apply { isAccessible = true }
    }
}
