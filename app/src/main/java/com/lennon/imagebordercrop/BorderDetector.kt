package com.lennon.imagebordercrop

import android.graphics.Bitmap

/** 检测结果来自纯色边框扫描还是主要内容区域检测。 */
enum class DetectionStrategy(val label: String) {
    COLOR_BORDER("纯色边框"),
    CONTENT_REGION("主要内容区域"),
    GIF_ALL_FRAMES("GIF 全帧安全检测")
}

/** 边框检测结果。 */
data class BorderResult(
    val top: Int,
    val bottom: Int,
    val left: Int,
    val right: Int,
    val borderType: BorderType,
    val threshold: Int,
    val strategy: DetectionStrategy = DetectionStrategy.COLOR_BORDER,
    val confidence: Float = 1f,
    val analyzedFrames: Int = 1,
    val sampledContentFrames: Int = 0,
    val manuallyAdjusted: Boolean = false
) {
    fun hasBorder(): Boolean = top > 0 || bottom > 0 || left > 0 || right > 0

    fun summary(): String {
        if (!hasBorder()) {
            return if (strategy == DetectionStrategy.GIF_ALL_FRAMES) {
                "未检测到边框\n${gifDetectionLabel()}${manualAdjustmentLabel()}"
            } else {
                "未检测到边框${manualAdjustmentLine()}"
            }
        }

        val dimensions = "上: ${top}px  下: ${bottom}px  左: ${left}px  右: ${right}px"
        return when (strategy) {
            DetectionStrategy.COLOR_BORDER ->
                "$dimensions\n${strategy.label}  边框类型: ${borderType.label}  强度: $threshold${manualAdjustmentLabel()}"
            DetectionStrategy.CONTENT_REGION ->
                "$dimensions\n${strategy.label}  置信度: ${"%.2f".format(confidence)}${manualAdjustmentLabel()}"
            DetectionStrategy.GIF_ALL_FRAMES ->
                "$dimensions\n${gifDetectionLabel()}${manualAdjustmentLabel()}"
        }
    }

    private fun manualAdjustmentLabel(): String = if (manuallyAdjusted) " · 已手动调整" else ""
    private fun manualAdjustmentLine(): String = if (manuallyAdjusted) "\n已手动调整" else ""

    private fun gifDetectionLabel(): String = buildString {
        append(strategy.label)
        append(" · ")
        append(analyzedFrames)
        append(" 帧")
    }
}

enum class BorderType(val label: String) {
    BLACK("黑边"),
    WHITE("白边"),
    AUTO("自动检测");
}

/**
 * 图片裁剪检测统一入口。
 *
 * 指定黑边或白边时只执行原有纯色扫描；自动模式先扫描纯色边框，结果为空或仍保留
 * 多侧同色空白时，再通过 [ContentRegionDetector] 寻找主要矩形内容区域。
 */
class BorderDetector(
    private val contentRegionDetector: ContentRegionDetector = ContentRegionDetector()
) {
    fun detect(bitmap: Bitmap, borderType: BorderType, threshold: Int): BorderResult {
        val width = bitmap.width
        val height = bitmap.height
        require(width > 0 && height > 0) { "图片尺寸不能为0" }

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        return detect(pixels, width, height, borderType, threshold)
    }

    /** IntArray 入口让检测逻辑可在本地 JVM 测试中直接验证。 */
    internal fun detect(
        pixels: IntArray,
        width: Int,
        height: Int,
        borderType: BorderType,
        threshold: Int
    ): BorderResult {
        require(width > 0 && height > 0 && pixels.size >= width * height) { "图片数据无效" }

        val colorResult = if (borderType == BorderType.AUTO) {
            detectAutoColorBorder(pixels, width, height, threshold)
        } else {
            detectColorBorder(pixels, width, height, borderType, threshold)
        }

        if (borderType != BorderType.AUTO || !isSuspiciousAutoResult(pixels, width, height, colorResult)) {
            return colorResult
        }

        val content = contentRegionDetector.detect(pixels, width, height) ?: return colorResult
        val colorArea = cropArea(width, height, colorResult)
        val contentArea = (content.right - content.left).toLong() * (content.bottom - content.top)
        val contentAreaRatio = contentArea.toDouble() / (width.toLong() * height)
        val requiredConfidence = if (contentAreaRatio < SMALL_CONTENT_AREA_RATIO) {
            MIN_SMALL_CONTENT_CONFIDENCE
        } else {
            MIN_CONTENT_CONFIDENCE
        }
        val removesEnoughExtraArea = contentArea <= (colorArea * CONTENT_AREA_RATIO_LIMIT).toLong()
        if (content.confidence < requiredConfidence || !removesEnoughExtraArea) return colorResult

        return BorderResult(
            top = content.top,
            bottom = height - content.bottom,
            left = content.left,
            right = width - content.right,
            borderType = BorderType.AUTO,
            threshold = threshold,
            strategy = DetectionStrategy.CONTENT_REGION,
            confidence = content.confidence
        )
    }

    /**
     * GIF 专用的轻量纯色扫描。
     *
     * 动图黑边中经常混有字幕、调色板抖动或少量 disposal 残留，因此不能要求一整行
     * 100% 同色。这里允许有限前景像素，但仍由 GIF 聚合器取所有帧的安全最小值。
     * 不运行主要内容区域检测，避免在数百帧上重复执行昂贵的全图分析。
     */
    internal fun detectGifColorBorder(
        pixels: IntArray,
        width: Int,
        height: Int,
        threshold: Int
    ): BorderResult {
        require(width > 0 && height > 0 && pixels.size >= width * height) { "图片数据无效" }
        val blackResult = detectColorBorder(
            pixels,
            width,
            height,
            BorderType.BLACK,
            threshold,
            GIF_BORDER_PIXEL_RATIO
        )
        val whiteResult = detectColorBorder(
            pixels,
            width,
            height,
            BorderType.WHITE,
            threshold,
            GIF_BORDER_PIXEL_RATIO
        )
        val fullArea = width.toLong() * height
        val blackRemovedArea = fullArea - cropArea(width, height, blackResult)
        val whiteRemovedArea = fullArea - cropArea(width, height, whiteResult)
        return when {
            blackRemovedArea > whiteRemovedArea -> blackResult
            whiteRemovedArea > blackRemovedArea -> whiteResult
            autoDetectBorderType(pixels, width, height) == BorderType.BLACK -> blackResult
            else -> whiteResult
        }
    }

    /** GIF 代表帧使用与静态截图相同的主要内容区域检测，结果只用于限制误裁。 */
    internal fun detectGifSampleContent(
        pixels: IntArray,
        width: Int,
        height: Int
    ): ContentRegion? = contentRegionDetector.detect(pixels, width, height)

    fun crop(bitmap: Bitmap, result: BorderResult): Bitmap {
        if (!result.hasBorder()) return bitmap

        val cropWidth = bitmap.width - result.left - result.right
        val cropHeight = bitmap.height - result.top - result.bottom
        require(cropWidth > 0 && cropHeight > 0) { "裁剪区域无效" }
        return Bitmap.createBitmap(bitmap, result.left, result.top, cropWidth, cropHeight)
    }

    private fun detectColorBorder(
        pixels: IntArray,
        width: Int,
        height: Int,
        borderType: BorderType,
        threshold: Int,
        requiredRatio: Float = 1f
    ): BorderResult {
        val top = scanTopBorder(pixels, width, height, borderType, threshold, requiredRatio)
        val bottom = scanBottomBorder(pixels, width, height, borderType, threshold, requiredRatio)
        val left = scanLeftBorder(pixels, width, height, borderType, threshold, requiredRatio)
        val right = scanRightBorder(pixels, width, height, borderType, threshold, requiredRatio)
        return BorderResult(top, bottom, left, right, borderType, threshold)
    }

    /**
     * 自动模式分别扫描黑边和白边，优先采用实际去除面积更大的纯色结果。
     * 这能处理画面整体偏亮、但只有底部存在一条纯黑边的情况。
     */
    private fun detectAutoColorBorder(
        pixels: IntArray,
        width: Int,
        height: Int,
        threshold: Int
    ): BorderResult {
        val blackResult = detectColorBorder(pixels, width, height, BorderType.BLACK, threshold)
        val whiteResult = detectColorBorder(pixels, width, height, BorderType.WHITE, threshold)
        val fullArea = width.toLong() * height
        val blackRemovedArea = fullArea - cropArea(width, height, blackResult)
        val whiteRemovedArea = fullArea - cropArea(width, height, whiteResult)
        return when {
            blackRemovedArea > whiteRemovedArea -> blackResult
            whiteRemovedArea > blackRemovedArea -> whiteResult
            autoDetectBorderType(pixels, width, height) == BorderType.BLACK -> blackResult
            else -> whiteResult
        }
    }

    /**
     * 自动结果为空，或裁剪后至少两侧仍有颜色相近的大片同色区域时，视为截图型图片。
     * 同色检测使用 4 bit/channel 量化，可容忍截图压缩产生的轻微色差。
     */
    private fun isSuspiciousAutoResult(
        pixels: IntArray,
        width: Int,
        height: Int,
        result: BorderResult
    ): Boolean {
        if (!result.hasBorder()) return true
        val detectedSides = listOf(result.top, result.bottom, result.left, result.right).count { it > 0 }
        // 截图中的底部纯黑控制条可能让旧算法只裁掉一小侧，而主体区域仍完全未识别。
        if (detectedSides <= 1) return true

        val left = result.left.coerceIn(0, width - 1)
        val top = result.top.coerceIn(0, height - 1)
        val right = (width - result.right).coerceIn(left + 1, width)
        val bottom = (height - result.bottom).coerceIn(top + 1, height)
        val cropWidth = right - left
        val cropHeight = bottom - top
        if (cropWidth <= 1 || cropHeight <= 1) return false

        val horizontalDepth = maxOf(1, (cropHeight * HOMOGENEOUS_BAND_DEPTH).toInt())
        val verticalDepth = maxOf(1, (cropWidth * HOMOGENEOUS_BAND_DEPTH).toInt())
        val bands = listOfNotNull(
            dominantBandColor(pixels, width, left, top, right, minOf(bottom, top + horizontalDepth)),
            dominantBandColor(pixels, width, left, maxOf(top, bottom - horizontalDepth), right, bottom),
            dominantBandColor(pixels, width, left, top, minOf(right, left + verticalDepth), bottom),
            dominantBandColor(pixels, width, maxOf(left, right - verticalDepth), top, right, bottom)
        )

        for (first in bands.indices) {
            for (second in first + 1 until bands.size) {
                if (colorDistance(bands[first], bands[second]) <= HOMOGENEOUS_COLOR_DISTANCE) {
                    return true
                }
            }
        }
        return false
    }

    private fun dominantBandColor(
        pixels: IntArray,
        width: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): Int? {
        if (left >= right || top >= bottom) return null
        val sampleStride = maxOf(1, kotlin.math.sqrt(((right - left) * (bottom - top) / 4096.0)).toInt())
        val counts = HashMap<Int, Int>()
        var samples = 0
        var bestKey = 0
        var bestCount = 0
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                val pixel = pixels[y * width + x]
                val key = (((pixel ushr 20) and 0xF) shl 8) or
                    (((pixel ushr 12) and 0xF) shl 4) or
                    ((pixel ushr 4) and 0xF)
                val count = (counts[key] ?: 0) + 1
                counts[key] = count
                if (count > bestCount) {
                    bestCount = count
                    bestKey = key
                }
                samples++
                x += sampleStride
            }
            y += sampleStride
        }
        if (samples == 0 || bestCount.toFloat() / samples < HOMOGENEOUS_RATIO) return null
        val r = ((bestKey ushr 8) and 0xF) * 17
        val g = ((bestKey ushr 4) and 0xF) * 17
        val b = (bestKey and 0xF) * 17
        return (r shl 16) or (g shl 8) or b
    }

    private fun cropArea(width: Int, height: Int, result: BorderResult): Long {
        val cropWidth = (width - result.left - result.right).coerceAtLeast(0)
        val cropHeight = (height - result.top - result.bottom).coerceAtLeast(0)
        return cropWidth.toLong() * cropHeight
    }

    private fun autoDetectBorderType(pixels: IntArray, width: Int, height: Int): BorderType {
        var totalLuminance = 0L
        var sampleCount = 0
        val sampleDepth = minOf(10, height / 2, width / 2)

        for (y in 0 until sampleDepth) {
            for (x in 0 until width) {
                totalLuminance += luminance(pixels[y * width + x])
                sampleCount++
            }
        }
        for (y in (height - sampleDepth) until height) {
            for (x in 0 until width) {
                totalLuminance += luminance(pixels[y * width + x])
                sampleCount++
            }
        }
        for (y in sampleDepth until (height - sampleDepth)) {
            for (x in 0 until sampleDepth) {
                totalLuminance += luminance(pixels[y * width + x])
                sampleCount++
            }
        }
        for (y in sampleDepth until (height - sampleDepth)) {
            for (x in (width - sampleDepth) until width) {
                totalLuminance += luminance(pixels[y * width + x])
                sampleCount++
            }
        }

        val average = if (sampleCount > 0) totalLuminance / sampleCount else 128
        return if (average < 128) BorderType.BLACK else BorderType.WHITE
    }

    private fun scanTopBorder(
        pixels: IntArray, width: Int, height: Int, borderType: BorderType, threshold: Int,
        requiredRatio: Float
    ): Int {
        if (requiredRatio < 1f) {
            var transitionUsed = false
            for (y in 0 until height) {
                val stats = rowStats(pixels, width, y, borderType, threshold)
                if (stats.hasEmbeddedForeground(borderType)) return y
                if (stats.borderRatio >= requiredRatio) continue
                if (!transitionUsed && stats.isBorderTransition(borderType, threshold)) {
                    transitionUsed = true
                    continue
                }
                return y
            }
            return 0
        }
        for (y in 0 until height) {
            if (!isBorderRow(pixels, width, y, borderType, threshold, requiredRatio)) return y
        }
        return 0
    }

    private fun scanBottomBorder(
        pixels: IntArray, width: Int, height: Int, borderType: BorderType, threshold: Int,
        requiredRatio: Float
    ): Int {
        if (requiredRatio < 1f) {
            var transitionUsed = false
            for (y in height - 1 downTo 0) {
                val stats = rowStats(pixels, width, y, borderType, threshold)
                if (stats.hasEmbeddedForeground(borderType)) return height - 1 - y
                if (stats.borderRatio >= requiredRatio) continue
                if (!transitionUsed && stats.isBorderTransition(borderType, threshold)) {
                    transitionUsed = true
                    continue
                }
                return height - 1 - y
            }
            return 0
        }
        for (y in height - 1 downTo 0) {
            if (!isBorderRow(pixels, width, y, borderType, threshold, requiredRatio)) {
                return height - 1 - y
            }
        }
        return 0
    }

    private fun scanLeftBorder(
        pixels: IntArray, width: Int, height: Int, borderType: BorderType, threshold: Int,
        requiredRatio: Float
    ): Int {
        if (requiredRatio < 1f) {
            var transitionUsed = false
            for (x in 0 until width) {
                val stats = columnStats(pixels, width, height, x, borderType, threshold)
                if (stats.hasEmbeddedForeground(borderType)) return x
                if (stats.borderRatio >= requiredRatio) continue
                if (!transitionUsed && stats.isBorderTransition(borderType, threshold)) {
                    transitionUsed = true
                    continue
                }
                return x
            }
            return 0
        }
        for (x in 0 until width) {
            if (!isBorderColumn(pixels, width, height, x, borderType, threshold, requiredRatio)) return x
        }
        return 0
    }

    private fun scanRightBorder(
        pixels: IntArray, width: Int, height: Int, borderType: BorderType, threshold: Int,
        requiredRatio: Float
    ): Int {
        if (requiredRatio < 1f) {
            var transitionUsed = false
            for (x in width - 1 downTo 0) {
                val stats = columnStats(pixels, width, height, x, borderType, threshold)
                if (stats.hasEmbeddedForeground(borderType)) return width - 1 - x
                if (stats.borderRatio >= requiredRatio) continue
                if (!transitionUsed && stats.isBorderTransition(borderType, threshold)) {
                    transitionUsed = true
                    continue
                }
                return width - 1 - x
            }
            return 0
        }
        for (x in width - 1 downTo 0) {
            if (!isBorderColumn(pixels, width, height, x, borderType, threshold, requiredRatio)) {
                return width - 1 - x
            }
        }
        return 0
    }

    private fun isBorderRow(
        pixels: IntArray, width: Int, rowY: Int, borderType: BorderType, threshold: Int,
        requiredRatio: Float
    ): Boolean {
        val offset = rowY * width
        val requiredCount = kotlin.math.ceil(width * requiredRatio.toDouble()).toInt()
        var borderCount = 0
        for (x in 0 until width) {
            if (isBorderColor(pixels[offset + x], borderType, threshold)) borderCount++
            if (borderCount >= requiredCount) return true
            if (borderCount + width - x - 1 < requiredCount) return false
        }
        return borderCount >= requiredCount
    }

    private fun rowStats(
        pixels: IntArray,
        width: Int,
        rowY: Int,
        borderType: BorderType,
        threshold: Int
    ): GifLineStats {
        val offset = rowY * width
        var borderCount = 0
        var luminanceTotal = 0L
        var foregroundLuminanceTotal = 0L
        for (x in 0 until width) {
            val pixel = pixels[offset + x]
            val value = luminance(pixel)
            luminanceTotal += value
            if (isBorderColor(pixel, borderType, threshold)) {
                borderCount++
            } else {
                foregroundLuminanceTotal += value
            }
        }
        return GifLineStats(width, borderCount, luminanceTotal, foregroundLuminanceTotal)
    }

    private fun columnStats(
        pixels: IntArray,
        width: Int,
        height: Int,
        colX: Int,
        borderType: BorderType,
        threshold: Int
    ): GifLineStats {
        var borderCount = 0
        var luminanceTotal = 0L
        var foregroundLuminanceTotal = 0L
        for (y in 0 until height) {
            val pixel = pixels[y * width + colX]
            val value = luminance(pixel)
            luminanceTotal += value
            if (isBorderColor(pixel, borderType, threshold)) {
                borderCount++
            } else {
                foregroundLuminanceTotal += value
            }
        }
        return GifLineStats(height, borderCount, luminanceTotal, foregroundLuminanceTotal)
    }

    private data class GifLineStats(
        val size: Int,
        val borderCount: Int,
        val luminanceTotal: Long,
        val foregroundLuminanceTotal: Long
    ) {
        val borderRatio: Float
            get() = borderCount.toFloat() / size

        private val foregroundCount: Int
            get() = size - borderCount

        fun hasEmbeddedForeground(borderType: BorderType): Boolean {
            if (borderRatio < GIF_EMBEDDED_CONTENT_MIN_BORDER_RATIO) return false
            if (foregroundCount < kotlin.math.ceil(size * GIF_EMBEDDED_CONTENT_MIN_RATIO).toInt()) {
                return false
            }
            val foregroundLuminance = foregroundLuminanceTotal.toFloat() / foregroundCount
            return when (borderType) {
                BorderType.BLACK -> foregroundLuminance >= GIF_DARK_BORDER_FOREGROUND_LUMINANCE
                BorderType.WHITE -> foregroundLuminance <= GIF_LIGHT_BORDER_FOREGROUND_LUMINANCE
                BorderType.AUTO -> false
            }
        }

        fun isBorderTransition(borderType: BorderType, threshold: Int): Boolean {
            val averageLuminance = luminanceTotal.toFloat() / size
            val foregroundLuminance = if (foregroundCount == 0) {
                averageLuminance
            } else {
                foregroundLuminanceTotal.toFloat() / foregroundCount
            }
            val transitionLimit = minOf(GIF_TRANSITION_LUMINANCE_CAP, threshold + GIF_TRANSITION_EXTRA)
            return when (borderType) {
                BorderType.BLACK ->
                    averageLuminance <= transitionLimit &&
                        foregroundLuminance < GIF_DARK_BORDER_FOREGROUND_LUMINANCE
                BorderType.WHITE ->
                    averageLuminance >= 255 - transitionLimit &&
                        foregroundLuminance > GIF_LIGHT_BORDER_FOREGROUND_LUMINANCE
                BorderType.AUTO -> false
            }
        }
    }

    private fun isBorderColumn(
        pixels: IntArray, width: Int, height: Int, colX: Int, borderType: BorderType, threshold: Int,
        requiredRatio: Float
    ): Boolean {
        val requiredCount = kotlin.math.ceil(height * requiredRatio.toDouble()).toInt()
        var borderCount = 0
        for (y in 0 until height) {
            if (isBorderColor(pixels[y * width + colX], borderType, threshold)) borderCount++
            if (borderCount >= requiredCount) return true
            if (borderCount + height - y - 1 < requiredCount) return false
        }
        return borderCount >= requiredCount
    }

    private fun isBorderColor(pixel: Int, borderType: BorderType, threshold: Int): Boolean {
        val r = red(pixel)
        val g = green(pixel)
        val b = blue(pixel)
        return when (borderType) {
            BorderType.BLACK -> r <= threshold && g <= threshold && b <= threshold
            BorderType.WHITE -> minOf(r, g, b) >= 255 - threshold
            BorderType.AUTO -> false
        }
    }

    private fun luminance(pixel: Int): Int =
        (0.299 * red(pixel) + 0.587 * green(pixel) + 0.114 * blue(pixel)).toInt()

    private fun colorDistance(first: Int, second: Int): Int =
        (kotlin.math.abs(red(first) - red(second)) +
            kotlin.math.abs(green(first) - green(second)) +
            kotlin.math.abs(blue(first) - blue(second))) / 3

    private fun red(pixel: Int): Int = (pixel ushr 16) and 0xFF
    private fun green(pixel: Int): Int = (pixel ushr 8) and 0xFF
    private fun blue(pixel: Int): Int = pixel and 0xFF

    companion object {
        private const val MIN_CONTENT_CONFIDENCE = 0.60f
        private const val MIN_SMALL_CONTENT_CONFIDENCE = 0.70f
        private const val SMALL_CONTENT_AREA_RATIO = 0.10
        private const val CONTENT_AREA_RATIO_LIMIT = 0.95
        private const val HOMOGENEOUS_BAND_DEPTH = 0.05f
        private const val HOMOGENEOUS_RATIO = 0.90f
        private const val HOMOGENEOUS_COLOR_DISTANCE = 24
        private const val GIF_BORDER_PIXEL_RATIO = 0.60f
        private const val GIF_EMBEDDED_CONTENT_MIN_BORDER_RATIO = GIF_BORDER_PIXEL_RATIO
        private const val GIF_EMBEDDED_CONTENT_MIN_RATIO = 0.02f
        private const val GIF_DARK_BORDER_FOREGROUND_LUMINANCE = 90f
        private const val GIF_LIGHT_BORDER_FOREGROUND_LUMINANCE = 165f
        private const val GIF_TRANSITION_EXTRA = 25
        private const val GIF_TRANSITION_LUMINANCE_CAP = 70
    }
}
