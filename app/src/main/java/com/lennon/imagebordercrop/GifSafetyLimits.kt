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

            val vertical = normalizeSymmetricSeams(
                first = top,
                second = bottom,
                firstValues = results.map { it.top },
                secondValues = results.map { it.bottom },
                dimension = height
            )
            top = vertical.first
            bottom = vertical.second

            val horizontal = normalizeSymmetricSeams(
                first = left,
                second = right,
                firstValues = results.map { it.left },
                secondValues = results.map { it.right },
                dimension = width
            )
            left = horizontal.first
            right = horizontal.second

            val usesOpaqueColorScan = results.all { it.borderType != BorderType.AUTO }
            val verticalSingleSide = trimStableSingleSidedSeam(
                first = top,
                second = bottom,
                firstValues = results.map { it.top },
                secondValues = results.map { it.bottom },
                dimension = height,
                enabled = usesOpaqueColorScan
            )
            top = verticalSingleSide.first
            bottom = verticalSingleSide.second

            val horizontalSingleSide = trimStableSingleSidedSeam(
                first = left,
                second = right,
                firstValues = results.map { it.left },
                secondValues = results.map { it.right },
                dimension = width,
                enabled = usesOpaqueColorScan
            )
            left = horizontalSingleSide.first
            right = horizontalSingleSide.second
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

    /**
     * GIF 调色板和缩放边缘偶尔会让同一条黑边在不同帧间抖动 1px。严格取所有帧
     * 最小值会让这条接缝在部分帧重新露出。只有两侧都存在、基本对称，而且至少
     * 一侧的逐帧结果恰好只抖动 1px 时，才统一向内收至较深的边界。
     */
    private fun normalizeSymmetricSeams(
        first: Int,
        second: Int,
        firstValues: List<Int>,
        secondValues: List<Int>,
        dimension: Int
    ): Pair<Int, Int> {
        if (first <= 0 || second <= 0 || firstValues.isEmpty() || secondValues.isEmpty()) {
            return first to second
        }
        // 代表帧的内容区域已经把边界向外保护时，不能再用调色板抖动结果覆盖它。
        if (first < firstValues.min() || second < secondValues.min()) return first to second

        val firstRange = firstValues.max() - firstValues.min()
        val secondRange = secondValues.max() - secondValues.min()
        val hasOnePixelJitter = firstRange == MAX_SEAM_JITTER_PX ||
            secondRange == MAX_SEAM_JITTER_PX
        if (!hasOnePixelJitter || firstRange > MAX_SEAM_JITTER_PX || secondRange > MAX_SEAM_JITTER_PX) {
            return first to second
        }

        if (kotlin.math.abs(first - second) > MAX_OPPOSITE_EDGE_DIFFERENCE_PX) {
            return first to second
        }
        val minimumDepth = maxOf(MIN_SEAM_DEPTH_PX, (dimension * MIN_SEAM_DEPTH_RATIO).toInt())
        if (minOf(first, second) < minimumDepth) return first to second

        val normalized = maxOf(
            first,
            second,
            firstValues.max(),
            secondValues.max()
        )
        if (normalized * 2 >= dimension) return first to second
        return normalized to normalized
    }

    /**
     * 不透明 GIF 的单侧色条在缩放或调色板量化后常留下一条 1px 过渡接缝。只有
     * 所有帧在该侧完全一致、对侧始终为 0，且代表帧没有向外保护边界时才内收 1px。
     * 透明 GIF 使用 Alpha 外接范围，绝不能应用这项视觉接缝修整。
     */
    private fun trimStableSingleSidedSeam(
        first: Int,
        second: Int,
        firstValues: List<Int>,
        secondValues: List<Int>,
        dimension: Int,
        enabled: Boolean
    ): Pair<Int, Int> {
        if (!enabled || firstValues.isEmpty() || secondValues.isEmpty()) return first to second
        val minimumDepth = maxOf(MIN_SEAM_DEPTH_PX, (dimension * MIN_SEAM_DEPTH_RATIO).toInt())

        if (
            first >= minimumDepth && second == 0 &&
            firstValues.all { it == first } && secondValues.all { it == 0 } &&
            first + 1 < dimension
        ) {
            return first + 1 to second
        }
        if (
            second >= minimumDepth && first == 0 &&
            secondValues.all { it == second } && firstValues.all { it == 0 } &&
            second + 1 < dimension
        ) {
            return first to second + 1
        }
        return first to second
    }

    private const val MIN_SAMPLE_CONTENT_CONFIDENCE = 0.60f
    private const val STABLE_EDGE_TOLERANCE_RATIO = 0.01f
    private const val MIN_STABLE_EDGE_TOLERANCE_PX = 3
    private const val MAX_SEAM_JITTER_PX = 1
    private const val MAX_OPPOSITE_EDGE_DIFFERENCE_PX = 1
    private const val MIN_SEAM_DEPTH_PX = 2
    private const val MIN_SEAM_DEPTH_RATIO = 0.01f
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
