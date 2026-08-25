package com.lennon.imagebordercrop

internal object GifSafetyLimits {
    const val MAX_SOURCE_BYTES: Long = 100L * 1024L * 1024L
    const val MAX_DIMENSION = 8192
    const val MAX_FRAME_PIXELS: Long = 40_000_000L
    const val MAX_FRAMES = 500
    const val MAX_TOTAL_PIXELS: Long = 250_000_000L

    fun validate(fileSize: Long, width: Int, height: Int, frameCount: Int) {
        require(fileSize in 1..MAX_SOURCE_BYTES) {
            if (fileSize > MAX_SOURCE_BYTES) "GIF 文件超过 100 MiB 安全上限" else "GIF 文件为空"
        }
        require(width > 0 && height > 0) { "GIF 画布尺寸无效" }
        require(width <= MAX_DIMENSION && height <= MAX_DIMENSION) {
            "GIF 最长边超过 8192 px 安全上限"
        }
        val framePixels = width.toLong() * height
        require(framePixels <= MAX_FRAME_PIXELS) { "GIF 单帧画布超过 4000 万像素安全上限" }
        require(frameCount in 1..MAX_FRAMES) {
            if (frameCount > MAX_FRAMES) "GIF 帧数超过 500 帧安全上限" else "GIF 没有可用帧"
        }
        require(framePixels * frameCount <= MAX_TOTAL_PIXELS) {
            "GIF 累计画布超过 2.5 亿像素安全上限"
        }
    }
}

internal object GifBorderAggregator {
    fun aggregate(results: List<BorderResult>, threshold: Int): BorderResult {
        return aggregate(
            results = results,
            threshold = threshold,
            width = 0,
            height = 0,
            sampledContent = emptyList(),
            sampledContentFrames = 0
        )
    }

    fun aggregate(
        results: List<BorderResult>,
        threshold: Int,
        width: Int,
        height: Int,
        sampledContent: List<ContentRegion>,
        sampledContentFrames: Int
    ): BorderResult {
        require(results.isNotEmpty()) { "GIF 没有可分析帧" }
        var top = results.minOf { it.top }
        var bottom = results.minOf { it.bottom }
        var left = results.minOf { it.left }
        var right = results.minOf { it.right }

        if (width > 0 && height > 0) {
            val protectiveRegions = sampledContent.filter {
                it.confidence >= MIN_SAMPLE_CONTENT_CONFIDENCE &&
                    it.left in 0 until it.right && it.right <= width &&
                    it.top in 0 until it.bottom && it.bottom <= height
            }
            if (protectiveRegions.isNotEmpty()) {
                val minimumMatches = if (sampledContentFrames == 1) 1 else 2
                val horizontalTolerance = maxOf(MIN_STABLE_EDGE_TOLERANCE_PX, (width * STABLE_EDGE_TOLERANCE_RATIO).toInt())
                val verticalTolerance = maxOf(MIN_STABLE_EDGE_TOLERANCE_PX, (height * STABLE_EDGE_TOLERANCE_RATIO).toInt())

                // 只采用多个代表帧中位置稳定的单侧边界，避免某个误候选造成明显漏裁。
                stableProtection(protectiveRegions.map { it.top }, minimumMatches, verticalTolerance)
                    ?.let { top = minOf(top, it) }
                stableProtection(protectiveRegions.map { height - it.bottom }, minimumMatches, verticalTolerance)
                    ?.let { bottom = minOf(bottom, it) }
                stableProtection(protectiveRegions.map { it.left }, minimumMatches, horizontalTolerance)
                    ?.let { left = minOf(left, it) }
                stableProtection(protectiveRegions.map { width - it.right }, minimumMatches, horizontalTolerance)
                    ?.let { right = minOf(right, it) }
            }
        }

        return BorderResult(
            top = top,
            bottom = bottom,
            left = left,
            right = right,
            borderType = BorderType.AUTO,
            threshold = threshold,
            strategy = DetectionStrategy.GIF_ALL_FRAMES,
            confidence = results.minOf { it.confidence },
            analyzedFrames = results.size,
            sampledContentFrames = sampledContentFrames
        )
    }

    private fun stableProtection(values: List<Int>, minimumMatches: Int, tolerance: Int): Int? {
        if (values.size < minimumMatches) return null
        // 候选贴住原图边缘时只有图片边缘的默认满分，没有独立边界证据，不能推翻纯色扫描。
        if (values.any { it <= 0 }) return null
        val minimum = values.min()
        val maximum = values.max()
        return if (maximum - minimum <= tolerance) minimum else null
    }

    private const val MIN_SAMPLE_CONTENT_CONFIDENCE = 0.60f
    private const val STABLE_EDGE_TOLERANCE_RATIO = 0.01f
    private const val MIN_STABLE_EDGE_TOLERANCE_PX = 3
}

internal object GifSampleFrames {
    fun indices(frameCount: Int): IntArray {
        require(frameCount > 0) { "GIF 没有可分析帧" }
        return intArrayOf(0, frameCount / 2, frameCount - 1)
            .distinct()
            .sorted()
            .toIntArray()
    }
}
