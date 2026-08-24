package com.lennon.imagebordercrop

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** 以右、下为开区间的主要内容区域。 */
data class ContentRegion(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val confidence: Float
)

/**
 * 不依赖 Android 图像 API 的主要矩形内容检测器。
 *
 * 检测使用最长边不超过 512 像素的缩略像素、8x8 网格活跃度、长直边界候选和
 * 中心加权评分；最终在原始像素上吸附到最强边界。
 */
class ContentRegionDetector {
    fun detect(pixels: IntArray, width: Int, height: Int): ContentRegion? {
        require(width > 0 && height > 0 && pixels.size >= width * height) { "图片数据无效" }
        if (width < 8 || height < 8) return null

        val sample = downsample(pixels, width, height)
        val grid = buildGrid(sample.pixels, sample.width, sample.height)
        val candidates = mutableListOf<Rect>()

        candidates += buildPixelBoundaryCandidates(sample)
        candidates += buildBoundaryCandidates(grid, sample.width, sample.height)
        candidates += buildComponentCandidates(grid, sample.width, sample.height)

        val uniqueCandidates = deduplicate(candidates)
        var best: ScoredRect? = null
        for (candidate in uniqueCandidates) {
            val scored = score(candidate, sample, grid) ?: continue
            if (best == null || scored.confidence > best.confidence) best = scored
        }

        val selected = best ?: return null
        val refined = refineOnOriginal(selected, pixels, width, height, sample.width, sample.height)
        val refinedAreaRatio = (refined.right - refined.left).toLong() * (refined.bottom - refined.top) /
            (width.toDouble() * height)
        if (refined.right - refined.left < width * MIN_WIDTH_RATIO ||
            refined.bottom - refined.top < height * MIN_HEIGHT_RATIO ||
            refinedAreaRatio < MIN_AREA_RATIO
        ) return null
        return refined
    }

    private fun downsample(pixels: IntArray, width: Int, height: Int): SampleImage {
        val scale = minOf(1.0, MAX_SAMPLE_SIDE.toDouble() / maxOf(width, height))
        val sampleWidth = maxOf(1, (width * scale).roundToInt())
        val sampleHeight = maxOf(1, (height * scale).roundToInt())
        if (sampleWidth == width && sampleHeight == height) {
            return SampleImage(pixels, width, height)
        }

        val sampled = IntArray(sampleWidth * sampleHeight)
        for (y in 0 until sampleHeight) {
            val sourceY = minOf(height - 1, (y.toLong() * height / sampleHeight).toInt())
            for (x in 0 until sampleWidth) {
                val sourceX = minOf(width - 1, (x.toLong() * width / sampleWidth).toInt())
                sampled[y * sampleWidth + x] = pixels[sourceY * width + sourceX]
            }
        }
        return SampleImage(sampled, sampleWidth, sampleHeight)
    }

    private fun buildGrid(pixels: IntArray, width: Int, height: Int): TileGrid {
        val columns = (width + TILE_SIZE - 1) / TILE_SIZE
        val rows = (height + TILE_SIZE - 1) / TILE_SIZE
        val tiles = Array(columns * rows) { Tile(0, 0f) }

        for (tileY in 0 until rows) {
            val top = tileY * TILE_SIZE
            val bottom = minOf(height, top + TILE_SIZE)
            for (tileX in 0 until columns) {
                val left = tileX * TILE_SIZE
                val right = minOf(width, left + TILE_SIZE)
                var sumR = 0L
                var sumG = 0L
                var sumB = 0L
                var sumLuma = 0.0
                var sumLumaSquared = 0.0
                var gradient = 0L
                var gradientSamples = 0
                var count = 0

                for (y in top until bottom) {
                    for (x in left until right) {
                        val pixel = pixels[y * width + x]
                        val r = red(pixel)
                        val g = green(pixel)
                        val b = blue(pixel)
                        val luma = luminance(r, g, b)
                        sumR += r
                        sumG += g
                        sumB += b
                        sumLuma += luma
                        sumLumaSquared += luma * luma
                        if (x > left) {
                            gradient += colorDistance(pixel, pixels[y * width + x - 1])
                            gradientSamples++
                        }
                        if (y > top) {
                            gradient += colorDistance(pixel, pixels[(y - 1) * width + x])
                            gradientSamples++
                        }
                        count++
                    }
                }

                val meanLuma = sumLuma / count
                val variance = maxOf(0.0, sumLumaSquared / count - meanLuma * meanLuma)
                val standardDeviation = sqrt(variance)
                val averageGradient = if (gradientSamples == 0) 0.0 else gradient.toDouble() / gradientSamples
                val varianceScore = (standardDeviation / LUMA_STD_NORMALIZER).coerceIn(0.0, 1.0)
                val gradientScore = (averageGradient / GRADIENT_NORMALIZER).coerceIn(0.0, 1.0)
                val activity = maxOf(varianceScore, gradientScore).toFloat()
                val meanColor = rgb(
                    (sumR / count).toInt(),
                    (sumG / count).toInt(),
                    (sumB / count).toInt()
                )
                tiles[tileY * columns + tileX] = Tile(meanColor, activity)
            }
        }

        return TileGrid(columns, rows, tiles)
    }

    private fun buildBoundaryCandidates(grid: TileGrid, width: Int, height: Int): List<Rect> {
        val horizontal = mutableListOf<LineSegment>()
        val vertical = mutableListOf<LineSegment>()
        val minHorizontalRun = maxOf(2, (grid.columns * MIN_WIDTH_RATIO).roundToInt())
        val minVerticalRun = maxOf(2, (grid.rows * MIN_HEIGHT_RATIO).roundToInt())

        for (row in 1 until grid.rows) {
            val strengths = FloatArray(grid.columns) { column ->
                boundaryStrength(grid[column, row - 1].meanColor, grid[column, row].meanColor)
            }
            horizontal += findSegments(row, strengths, minHorizontalRun)
        }
        for (column in 1 until grid.columns) {
            val strengths = FloatArray(grid.rows) { row ->
                boundaryStrength(grid[column - 1, row].meanColor, grid[column, row].meanColor)
            }
            vertical += findSegments(column, strengths, minVerticalRun)
        }

        val candidates = mutableListOf<Rect>()
        val minimumWidth = maxOf(2, (width * MIN_WIDTH_RATIO).roundToInt())
        val minimumHeight = maxOf(2, (height * MIN_HEIGHT_RATIO).roundToInt())

        // 使用成对的内部水平边界。左右允许落在图片边缘，以支持全宽照片。
        for (topIndex in horizontal.indices) {
            val top = horizontal[topIndex]
            for (bottomIndex in topIndex + 1 until horizontal.size) {
                val bottom = horizontal[bottomIndex]
                if (bottom.position <= top.position) continue
                if (!segmentsDescribeSameSideSpan(top, bottom)) continue
                val overlapStart = maxOf(top.start, bottom.start)
                val overlapEnd = minOf(top.end, bottom.end)
                if (overlapEnd - overlapStart < minHorizontalRun) continue

                val leftCell = (((top.start + bottom.start) / 2).coerceAtMost(overlapStart) - 2)
                    .coerceAtLeast(0)
                val rightCell = (((top.end + bottom.end + 1) / 2).coerceAtLeast(overlapEnd) + 2)
                    .coerceAtMost(grid.columns)
                val rect = Rect(
                    left = (leftCell * TILE_SIZE).coerceIn(0, width),
                    top = (top.position * TILE_SIZE).coerceIn(0, height),
                    right = (rightCell * TILE_SIZE).coerceIn(0, width),
                    bottom = (bottom.position * TILE_SIZE).coerceIn(0, height)
                )
                if (rect.width >= minimumWidth && rect.height >= minimumHeight) candidates += rect
            }
        }

        // 使用成对的内部垂直边界。上下允许落在图片边缘，以支持全高内容。
        for (leftIndex in vertical.indices) {
            val left = vertical[leftIndex]
            for (rightIndex in leftIndex + 1 until vertical.size) {
                val right = vertical[rightIndex]
                if (right.position <= left.position) continue
                if (!segmentsDescribeSameSideSpan(left, right)) continue
                val overlapStart = maxOf(left.start, right.start)
                val overlapEnd = minOf(left.end, right.end)
                if (overlapEnd - overlapStart < minVerticalRun) continue

                val topCell = (((left.start + right.start) / 2).coerceAtMost(overlapStart) - 2)
                    .coerceAtLeast(0)
                val bottomCell = (((left.end + right.end + 1) / 2).coerceAtLeast(overlapEnd) + 2)
                    .coerceAtMost(grid.rows)
                val rect = Rect(
                    left = (left.position * TILE_SIZE).coerceIn(0, width),
                    top = (topCell * TILE_SIZE).coerceIn(0, height),
                    right = (right.position * TILE_SIZE).coerceIn(0, width),
                    bottom = (bottomCell * TILE_SIZE).coerceIn(0, height)
                )
                if (rect.width >= minimumWidth && rect.height >= minimumHeight) candidates += rect
            }
        }

        return candidates
    }

    /**
     * 在缩略图逐像素寻找长直颜色跳变，避免真实边界落在 8x8 网格内部时被均值稀释。
     */
    private fun buildPixelBoundaryCandidates(image: SampleImage): List<Rect> {
        val horizontal = mutableListOf<LineSegment>()
        val vertical = mutableListOf<LineSegment>()
        val minimumHorizontalRun = maxOf(2, (image.width * MIN_WIDTH_RATIO).roundToInt())
        val minimumVerticalRun = maxOf(2, (image.height * MIN_HEIGHT_RATIO).roundToInt())
        val horizontalGap = maxOf(2, image.width / 100)
        val verticalGap = maxOf(2, image.height / 100)

        for (y in 1 until image.height) {
            val strengths = FloatArray(image.width) { x ->
                boundaryStrength(image.pixels[(y - 1) * image.width + x], image.pixels[y * image.width + x])
            }
            if (isFullSpanBoundary(strengths)) {
                horizontal += LineSegment(y, 0, image.width, strengths.average().toFloat())
            }
            horizontal += findSegments(y, strengths, minimumHorizontalRun, horizontalGap)
        }
        for (x in 1 until image.width) {
            val strengths = FloatArray(image.height) { y ->
                boundaryStrength(image.pixels[y * image.width + x - 1], image.pixels[y * image.width + x])
            }
            if (isFullSpanBoundary(strengths)) {
                vertical += LineSegment(x, 0, image.height, strengths.average().toFloat())
            }
            vertical += findSegments(x, strengths, minimumVerticalRun, verticalGap)
        }

        val candidates = mutableListOf<Rect>()
        val minimumWidth = maxOf(2, (image.width * MIN_WIDTH_RATIO).roundToInt())
        val minimumHeight = maxOf(2, (image.height * MIN_HEIGHT_RATIO).roundToInt())
        for (topIndex in horizontal.indices) {
            val top = horizontal[topIndex]
            for (bottomIndex in topIndex + 1 until horizontal.size) {
                val bottom = horizontal[bottomIndex]
                if (bottom.position - top.position < minimumHeight || !segmentsDescribeSameSideSpan(top, bottom)) continue
                val overlapStart = maxOf(top.start, bottom.start)
                val overlapEnd = minOf(top.end, bottom.end)
                if (overlapEnd - overlapStart < minimumWidth) continue
                candidates += Rect(
                    left = ((top.start + bottom.start) / 2).coerceIn(0, image.width),
                    top = top.position,
                    right = ((top.end + bottom.end + 1) / 2).coerceIn(0, image.width),
                    bottom = bottom.position
                )
            }
        }
        for (leftIndex in vertical.indices) {
            val left = vertical[leftIndex]
            for (rightIndex in leftIndex + 1 until vertical.size) {
                val right = vertical[rightIndex]
                if (right.position - left.position < minimumWidth || !segmentsDescribeSameSideSpan(left, right)) continue
                val overlapStart = maxOf(left.start, right.start)
                val overlapEnd = minOf(left.end, right.end)
                if (overlapEnd - overlapStart < minimumHeight) continue
                candidates += Rect(
                    left = left.position,
                    top = ((left.start + right.start) / 2).coerceIn(0, image.height),
                    right = right.position,
                    bottom = ((left.end + right.end + 1) / 2).coerceIn(0, image.height)
                )
            }
        }
        return candidates
    }

    /** 首尾都有稳定跳变时，将中间颜色相近的部分也视为同一条整幅边界。 */
    private fun isFullSpanBoundary(strengths: FloatArray): Boolean {
        if (strengths.size < 4) return false
        val edgeLength = maxOf(1, strengths.size / 10)
        val overall = strengths.average()
        val leading = strengths.take(edgeLength).average()
        val trailing = strengths.takeLast(edgeLength).average()
        return overall >= FULL_SPAN_MEAN_STRENGTH &&
            leading >= FULL_SPAN_EDGE_STRENGTH && trailing >= FULL_SPAN_EDGE_STRENGTH
    }

    /** 避免把照片上边与下方无关按钮的边界组合成一个大矩形。 */
    private fun segmentsDescribeSameSideSpan(first: LineSegment, second: LineSegment): Boolean {
        val firstLength = first.end - first.start
        val secondLength = second.end - second.start
        val shorter = minOf(firstLength, secondLength)
        val longer = maxOf(firstLength, secondLength)
        if (shorter.toFloat() / longer < MIN_PAIRED_SPAN_RATIO) return false
        val allowedOffset = maxOf(2, (shorter * MAX_PAIRED_EDGE_OFFSET_RATIO).roundToInt())
        return abs(first.start - second.start) <= allowedOffset && abs(first.end - second.end) <= allowedOffset
    }

    private fun findSegments(
        position: Int,
        strengths: FloatArray,
        minimumLength: Int,
        maximumGap: Int = MAX_SEGMENT_GAP
    ): List<LineSegment> {
        val raw = mutableListOf<LineSegment>()
        var start = -1
        var sum = 0f
        var count = 0
        for (index in strengths.indices) {
            if (strengths[index] >= MIN_BOUNDARY_CELL_STRENGTH) {
                if (start < 0) start = index
                sum += strengths[index]
                count++
            } else if (start >= 0) {
                raw += LineSegment(position, start, index, sum / count)
                start = -1
                sum = 0f
                count = 0
            }
        }
        if (start >= 0) raw += LineSegment(position, start, strengths.size, sum / count)

        if (raw.isEmpty()) return emptyList()
        val merged = mutableListOf<LineSegment>()
        var current = raw.first()
        for (next in raw.drop(1)) {
            if (next.start - current.end <= maximumGap) {
                val firstLength = current.end - current.start
                val nextLength = next.end - next.start
                current = LineSegment(
                    position,
                    current.start,
                    next.end,
                    (current.strength * firstLength + next.strength * nextLength) / (firstLength + nextLength)
                )
            } else {
                if (current.end - current.start >= minimumLength) merged += current
                current = next
            }
        }
        if (current.end - current.start >= minimumLength) merged += current
        return merged
    }

    private fun buildComponentCandidates(grid: TileGrid, width: Int, height: Int): List<Rect> {
        val active = BooleanArray(grid.columns * grid.rows) { index ->
            grid.tiles[index].activity >= ACTIVE_TILE_THRESHOLD
        }
        val closed = erode(dilate(active, grid.columns, grid.rows), grid.columns, grid.rows)
        val visited = BooleanArray(closed.size)
        val candidates = mutableListOf<Rect>()
        val queue = IntArray(closed.size)

        for (start in closed.indices) {
            if (!closed[start] || visited[start]) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true
            var minX = start % grid.columns
            var maxX = minX
            var minY = start / grid.columns
            var maxY = minY

            while (head < tail) {
                val current = queue[head++]
                val x = current % grid.columns
                val y = current / grid.columns
                minX = minOf(minX, x)
                maxX = maxOf(maxX, x)
                minY = minOf(minY, y)
                maxY = maxOf(maxY, y)
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nextX = x + dx
                        val nextY = y + dy
                        if (nextX !in 0 until grid.columns || nextY !in 0 until grid.rows) continue
                        val next = nextY * grid.columns + nextX
                        if (closed[next] && !visited[next]) {
                            visited[next] = true
                            queue[tail++] = next
                        }
                    }
                }
            }

            candidates += Rect(
                left = ((minX - 2) * TILE_SIZE).coerceAtLeast(0),
                top = ((minY - 2) * TILE_SIZE).coerceAtLeast(0),
                right = minOf(width, (maxX + 3) * TILE_SIZE),
                bottom = minOf(height, (maxY + 3) * TILE_SIZE)
            )
        }
        return candidates
    }

    private fun dilate(mask: BooleanArray, width: Int, height: Int): BooleanArray {
        val result = BooleanArray(mask.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var value = false
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val sourceX = x + dx
                        val sourceY = y + dy
                        if (sourceX in 0 until width && sourceY in 0 until height && mask[sourceY * width + sourceX]) {
                            value = true
                        }
                    }
                }
                result[y * width + x] = value
            }
        }
        return result
    }

    private fun erode(mask: BooleanArray, width: Int, height: Int): BooleanArray {
        val result = BooleanArray(mask.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var value = true
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val sourceX = x + dx
                        val sourceY = y + dy
                        if (sourceX !in 0 until width || sourceY !in 0 until height || !mask[sourceY * width + sourceX]) {
                            value = false
                        }
                    }
                }
                result[y * width + x] = value
            }
        }
        return result
    }

    private fun deduplicate(candidates: List<Rect>): List<Rect> {
        val valid = candidates.filter { it.width > 0 && it.height > 0 }.sortedByDescending { it.area }
        val unique = mutableListOf<Rect>()
        for (candidate in valid) {
            if (unique.none { intersectionOverUnion(it, candidate) >= DUPLICATE_IOU }) unique += candidate
        }
        return unique
    }

    private fun score(rect: Rect, image: SampleImage, grid: TileGrid): ScoredRect? {
        val imageArea = image.width.toLong() * image.height
        val areaRatio = rect.area.toDouble() / imageArea
        if (rect.width < image.width * MIN_WIDTH_RATIO ||
            rect.height < image.height * MIN_HEIGHT_RATIO ||
            areaRatio < MIN_AREA_RATIO || areaRatio >= MAX_AREA_RATIO
        ) return null

        val top = bestHorizontalBoundaryScore(image, rect.top, rect.left, rect.right)
        val bottom = bestHorizontalBoundaryScore(image, rect.bottom, rect.left, rect.right)
        val left = bestVerticalBoundaryScore(image, rect.left, rect.top, rect.bottom)
        val right = bestVerticalBoundaryScore(image, rect.right, rect.top, rect.bottom)
        val hasOpposingBoundaryEvidence =
            (top >= MIN_SIDE_EVIDENCE && bottom >= MIN_SIDE_EVIDENCE) ||
                (left >= MIN_SIDE_EVIDENCE && right >= MIN_SIDE_EVIDENCE)
        if (!hasOpposingBoundaryEvidence) return null

        val boundaryScore = (top + bottom + left + right) / 4f
        if (boundaryScore < MIN_AVERAGE_BOUNDARY_SCORE) return null
        val activityScore = activityScore(rect, grid)
        val surroundingActivity = surroundingBandActivityScore(rect, grid)
        if (surroundingActivity >= activityScore * MAX_SURROUNDING_ACTIVITY_RATIO) return null
        if (areaRatio >= LARGE_CANDIDATE_AREA_RATIO && edgeActivityScore(grid) >= activityScore * 0.8f) {
            return null
        }
        val areaScore = sqrt(areaRatio).toFloat().coerceIn(0f, 1f)
        val centerX = (rect.left + rect.right) / 2f
        val centerY = (rect.top + rect.bottom) / 2f
        val normalizedDistance = hypot(
            ((centerX - image.width / 2f) / (image.width / 2f)).toDouble(),
            ((centerY - image.height / 2f) / (image.height / 2f)).toDouble()
        ) / sqrt(2.0)
        val centerScore = (1.0 - normalizedDistance).coerceIn(0.0, 1.0).toFloat()
        val confidence = (
            boundaryScore * BOUNDARY_WEIGHT +
                activityScore * ACTIVITY_WEIGHT +
                areaScore * AREA_WEIGHT +
                centerScore * CENTER_WEIGHT
            ).coerceIn(0f, 1f)
        return ScoredRect(rect, confidence)
    }

    private fun activityScore(rect: Rect, grid: TileGrid): Float {
        val firstColumn = (rect.left / TILE_SIZE).coerceIn(0, grid.columns - 1)
        val lastColumn = ((rect.right - 1) / TILE_SIZE).coerceIn(firstColumn, grid.columns - 1)
        val firstRow = (rect.top / TILE_SIZE).coerceIn(0, grid.rows - 1)
        val lastRow = ((rect.bottom - 1) / TILE_SIZE).coerceIn(firstRow, grid.rows - 1)
        var total = 0f
        var active = 0
        var count = 0
        for (row in firstRow..lastRow) {
            for (column in firstColumn..lastColumn) {
                val value = grid[column, row].activity
                total += value
                if (value >= ACTIVE_TILE_THRESHOLD) active++
                count++
            }
        }
        if (count == 0) return 0f
        val meanScore = (total / count / TARGET_MEAN_ACTIVITY).coerceIn(0f, 1f)
        val activeRatioScore = (active.toFloat() / count / TARGET_ACTIVE_RATIO).coerceIn(0f, 1f)
        return meanScore * 0.55f + activeRatioScore * 0.45f
    }

    /** 候选外侧仍与内部同样活跃时，它更可能只是完整照片中的一块，而不是截图内嵌图片。 */
    private fun surroundingBandActivityScore(rect: Rect, grid: TileGrid): Float {
        val firstColumn = (rect.left / TILE_SIZE).coerceIn(0, grid.columns - 1)
        val lastColumn = ((rect.right - 1) / TILE_SIZE).coerceIn(firstColumn, grid.columns - 1)
        val firstRow = (rect.top / TILE_SIZE).coerceIn(0, grid.rows - 1)
        val lastRow = ((rect.bottom - 1) / TILE_SIZE).coerceIn(firstRow, grid.rows - 1)
        val outerLeft = maxOf(0, firstColumn - SURROUNDING_BAND_TILES)
        val outerRight = minOf(grid.columns - 1, lastColumn + SURROUNDING_BAND_TILES)
        val outerTop = maxOf(0, firstRow - SURROUNDING_BAND_TILES)
        val outerBottom = minOf(grid.rows - 1, lastRow + SURROUNDING_BAND_TILES)
        var total = 0f
        var active = 0
        var count = 0
        for (row in outerTop..outerBottom) {
            for (column in outerLeft..outerRight) {
                if (column in firstColumn..lastColumn && row in firstRow..lastRow) continue
                val value = grid[column, row].activity
                total += value
                if (value >= ACTIVE_TILE_THRESHOLD) active++
                count++
            }
        }
        if (count == 0) return 0f
        val meanScore = (total / count / TARGET_MEAN_ACTIVITY).coerceIn(0f, 1f)
        val activeRatioScore = (active.toFloat() / count / TARGET_ACTIVE_RATIO).coerceIn(0f, 1f)
        return meanScore * 0.55f + activeRatioScore * 0.45f
    }

    /** 满屏照片的外缘与内部同样活跃，不应把其中的近全屏区域误认为截图内嵌图片。 */
    private fun edgeActivityScore(grid: TileGrid): Float {
        var total = 0f
        var count = 0
        for (column in 0 until grid.columns) {
            total += grid[column, 0].activity
            count++
            if (grid.rows > 1) {
                total += grid[column, grid.rows - 1].activity
                count++
            }
        }
        for (row in 1 until grid.rows - 1) {
            total += grid[0, row].activity
            count++
            if (grid.columns > 1) {
                total += grid[grid.columns - 1, row].activity
                count++
            }
        }
        return if (count == 0) 0f else (total / count / TARGET_MEAN_ACTIVITY).coerceIn(0f, 1f)
    }

    private fun horizontalBoundaryScore(image: SampleImage, y: Int, left: Int, right: Int): Float {
        if (y <= 0 || y >= image.height) return IMAGE_EDGE_SCORE
        val stride = maxOf(1, (right - left) / MAX_BOUNDARY_SAMPLES)
        var distanceSum = 0L
        var strong = 0
        var count = 0
        var x = left
        while (x < right) {
            val distance = colorDistance(image.pixels[(y - 1) * image.width + x], image.pixels[y * image.width + x])
            distanceSum += distance
            if (distance >= STRONG_PIXEL_BOUNDARY) strong++
            count++
            x += stride
        }
        return combinedBoundaryScore(distanceSum, strong, count)
    }

    private fun bestHorizontalBoundaryScore(
        image: SampleImage,
        approximateY: Int,
        left: Int,
        right: Int
    ): Float {
        if (approximateY <= 0 || approximateY >= image.height) return IMAGE_EDGE_SCORE
        var best = 0f
        val radius = TILE_SIZE * REFINEMENT_TILES
        for (y in maxOf(1, approximateY - radius)..minOf(image.height - 1, approximateY + radius)) {
            best = maxOf(best, horizontalBoundaryScore(image, y, left, right))
        }
        return best
    }

    private fun verticalBoundaryScore(image: SampleImage, x: Int, top: Int, bottom: Int): Float {
        if (x <= 0 || x >= image.width) return IMAGE_EDGE_SCORE
        val stride = maxOf(1, (bottom - top) / MAX_BOUNDARY_SAMPLES)
        var distanceSum = 0L
        var strong = 0
        var count = 0
        var y = top
        while (y < bottom) {
            val distance = colorDistance(image.pixels[y * image.width + x - 1], image.pixels[y * image.width + x])
            distanceSum += distance
            if (distance >= STRONG_PIXEL_BOUNDARY) strong++
            count++
            y += stride
        }
        return combinedBoundaryScore(distanceSum, strong, count)
    }

    private fun bestVerticalBoundaryScore(
        image: SampleImage,
        approximateX: Int,
        top: Int,
        bottom: Int
    ): Float {
        if (approximateX <= 0 || approximateX >= image.width) return IMAGE_EDGE_SCORE
        var best = 0f
        val radius = TILE_SIZE * REFINEMENT_TILES
        for (x in maxOf(1, approximateX - radius)..minOf(image.width - 1, approximateX + radius)) {
            best = maxOf(best, verticalBoundaryScore(image, x, top, bottom))
        }
        return best
    }

    private fun combinedBoundaryScore(distanceSum: Long, strong: Int, count: Int): Float {
        if (count == 0) return 0f
        val meanPart = (distanceSum.toFloat() / count / TARGET_BOUNDARY_DISTANCE).coerceIn(0f, 1f)
        val strongPart = (strong.toFloat() / count / TARGET_STRONG_BOUNDARY_RATIO).coerceIn(0f, 1f)
        return meanPart * 0.5f + strongPart * 0.5f
    }

    private fun refineOnOriginal(
        scored: ScoredRect,
        pixels: IntArray,
        width: Int,
        height: Int,
        sampleWidth: Int,
        sampleHeight: Int
    ): ContentRegion {
        val scaleX = width.toDouble() / sampleWidth
        val scaleY = height.toDouble() / sampleHeight
        val approximate = Rect(
            left = (scored.rect.left * scaleX).roundToInt().coerceIn(0, width),
            top = (scored.rect.top * scaleY).roundToInt().coerceIn(0, height),
            right = (scored.rect.right * scaleX).roundToInt().coerceIn(0, width),
            bottom = (scored.rect.bottom * scaleY).roundToInt().coerceIn(0, height)
        )
        val radiusX = maxOf(2, (TILE_SIZE * REFINEMENT_TILES * scaleX).roundToInt())
        val radiusY = maxOf(2, (TILE_SIZE * REFINEMENT_TILES * scaleY).roundToInt())

        var top = if (scored.rect.top == 0) {
            snapFromTopEdge(pixels, width, height, approximate.left, approximate.right, radiusY)
        } else findBestHorizontalBoundary(
            pixels, width, height, approximate.top, approximate.left, approximate.right, radiusY
        )
        var bottom = if (scored.rect.bottom == sampleHeight) {
            snapFromBottomEdge(pixels, width, height, approximate.left, approximate.right, radiusY)
        } else findBestHorizontalBoundary(
            pixels, width, height, approximate.bottom, approximate.left, approximate.right, radiusY
        )
        var left = if (scored.rect.left == 0) {
            snapFromLeftEdge(pixels, width, height, top, bottom, radiusX)
        } else findBestVerticalBoundary(
            pixels, width, height, approximate.left, top, bottom, radiusX
        )
        var right = if (scored.rect.right == sampleWidth) {
            snapFromRightEdge(pixels, width, height, top, bottom, radiusX)
        } else findBestVerticalBoundary(
            pixels, width, height, approximate.right, top, bottom, radiusX
        )

        if (bottom <= top) {
            top = approximate.top
            bottom = approximate.bottom
        }
        if (right <= left) {
            left = approximate.left
            right = approximate.right
        }
        return ContentRegion(left, top, right, bottom, scored.confidence)
    }

    private fun snapFromTopEdge(
        pixels: IntArray, width: Int, height: Int, left: Int, right: Int, radius: Int
    ): Int {
        val candidate = findBestHorizontalBoundary(pixels, width, height, 1, left, right, radius + 1)
        return if (horizontalBoundaryAverage(pixels, width, candidate, left, right) >= EDGE_SNAP_DISTANCE &&
            isUniformStrip(pixels, width, left, 0, right, candidate)
        ) candidate else 0
    }

    private fun snapFromBottomEdge(
        pixels: IntArray, width: Int, height: Int, left: Int, right: Int, radius: Int
    ): Int {
        val candidate = findBestHorizontalBoundary(pixels, width, height, height - 1, left, right, radius + 1)
        return if (horizontalBoundaryAverage(pixels, width, candidate, left, right) >= EDGE_SNAP_DISTANCE &&
            isUniformStrip(pixels, width, left, candidate, right, height)
        ) candidate else height
    }

    private fun snapFromLeftEdge(
        pixels: IntArray, width: Int, height: Int, top: Int, bottom: Int, radius: Int
    ): Int {
        val candidate = findBestVerticalBoundary(pixels, width, height, 1, top, bottom, radius + 1)
        return if (verticalBoundaryAverage(pixels, width, candidate, top, bottom) >= EDGE_SNAP_DISTANCE &&
            isUniformStrip(pixels, width, 0, top, candidate, bottom)
        ) candidate else 0
    }

    private fun snapFromRightEdge(
        pixels: IntArray, width: Int, height: Int, top: Int, bottom: Int, radius: Int
    ): Int {
        val candidate = findBestVerticalBoundary(pixels, width, height, width - 1, top, bottom, radius + 1)
        return if (verticalBoundaryAverage(pixels, width, candidate, top, bottom) >= EDGE_SNAP_DISTANCE &&
            isUniformStrip(pixels, width, candidate, top, width, bottom)
        ) candidate else width
    }

    private fun isUniformStrip(
        pixels: IntArray,
        width: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): Boolean {
        if (left >= right || top >= bottom) return false
        val area = (right - left).toLong() * (bottom - top)
        val stride = maxOf(1, sqrt(area / 4096.0).toInt())
        val counts = HashMap<Int, Int>()
        var samples = 0
        var best = 0
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
                best = maxOf(best, count)
                samples++
                x += stride
            }
            y += stride
        }
        return samples > 0 && best.toFloat() / samples >= EDGE_UNIFORM_RATIO
    }

    private fun horizontalBoundaryAverage(
        pixels: IntArray, width: Int, y: Int, left: Int, right: Int
    ): Double {
        if (y <= 0) return 0.0
        val stride = maxOf(1, (right - left) / MAX_REFINEMENT_SAMPLES)
        var total = 0L
        var count = 0
        var x = left.coerceIn(0, width - 1)
        val end = right.coerceIn(x + 1, width)
        while (x < end) {
            total += colorDistance(pixels[(y - 1) * width + x], pixels[y * width + x])
            count++
            x += stride
        }
        return if (count == 0) 0.0 else total.toDouble() / count
    }

    private fun verticalBoundaryAverage(
        pixels: IntArray, width: Int, x: Int, top: Int, bottom: Int
    ): Double {
        if (x <= 0) return 0.0
        val stride = maxOf(1, (bottom - top) / MAX_REFINEMENT_SAMPLES)
        var total = 0L
        var count = 0
        var y = top.coerceAtLeast(0)
        val height = pixels.size / width
        val end = bottom.coerceIn(y + 1, height)
        while (y < end) {
            total += colorDistance(pixels[y * width + x - 1], pixels[y * width + x])
            count++
            y += stride
        }
        return if (count == 0) 0.0 else total.toDouble() / count
    }

    private fun findBestHorizontalBoundary(
        pixels: IntArray,
        width: Int,
        height: Int,
        approximateY: Int,
        left: Int,
        right: Int,
        radius: Int
    ): Int {
        val from = maxOf(1, approximateY - radius)
        val to = minOf(height - 1, approximateY + radius)
        val stride = maxOf(1, (right - left) / MAX_REFINEMENT_SAMPLES)
        var bestY = approximateY.coerceIn(from, to)
        var bestScore = -1.0
        for (y in from..to) {
            var total = 0L
            var count = 0
            var x = left.coerceIn(0, width - 1)
            val end = right.coerceIn(x + 1, width)
            while (x < end) {
                total += colorDistance(pixels[(y - 1) * width + x], pixels[y * width + x])
                count++
                x += stride
            }
            val score = if (count == 0) 0.0 else total.toDouble() / count
            if (score > bestScore) {
                bestScore = score
                bestY = y
            }
        }
        return bestY
    }

    private fun findBestVerticalBoundary(
        pixels: IntArray,
        width: Int,
        height: Int,
        approximateX: Int,
        top: Int,
        bottom: Int,
        radius: Int
    ): Int {
        val from = maxOf(1, approximateX - radius)
        val to = minOf(width - 1, approximateX + radius)
        val stride = maxOf(1, (bottom - top) / MAX_REFINEMENT_SAMPLES)
        var bestX = approximateX.coerceIn(from, to)
        var bestScore = -1.0
        for (x in from..to) {
            var total = 0L
            var count = 0
            var y = top.coerceIn(0, height - 1)
            val end = bottom.coerceIn(y + 1, height)
            while (y < end) {
                total += colorDistance(pixels[y * width + x - 1], pixels[y * width + x])
                count++
                y += stride
            }
            val score = if (count == 0) 0.0 else total.toDouble() / count
            if (score > bestScore) {
                bestScore = score
                bestX = x
            }
        }
        return bestX
    }

    private fun boundaryStrength(first: Int, second: Int): Float =
        (colorDistance(first, second).toFloat() / TILE_BOUNDARY_NORMALIZER).coerceIn(0f, 1f)

    private fun intersectionOverUnion(first: Rect, second: Rect): Double {
        val left = maxOf(first.left, second.left)
        val top = maxOf(first.top, second.top)
        val right = minOf(first.right, second.right)
        val bottom = minOf(first.bottom, second.bottom)
        if (left >= right || top >= bottom) return 0.0
        val intersection = (right - left).toLong() * (bottom - top)
        val union = first.area + second.area - intersection
        return intersection.toDouble() / union
    }

    private fun colorDistance(first: Int, second: Int): Int =
        (abs(red(first) - red(second)) + abs(green(first) - green(second)) + abs(blue(first) - blue(second))) / 3

    private fun luminance(r: Int, g: Int, b: Int): Double = 0.299 * r + 0.587 * g + 0.114 * b
    private fun rgb(r: Int, g: Int, b: Int): Int = (r shl 16) or (g shl 8) or b
    private fun red(pixel: Int): Int = (pixel ushr 16) and 0xFF
    private fun green(pixel: Int): Int = (pixel ushr 8) and 0xFF
    private fun blue(pixel: Int): Int = pixel and 0xFF

    private data class SampleImage(val pixels: IntArray, val width: Int, val height: Int)
    private data class Tile(val meanColor: Int, val activity: Float)
    private data class TileGrid(val columns: Int, val rows: Int, val tiles: Array<Tile>) {
        operator fun get(column: Int, row: Int): Tile = tiles[row * columns + column]
    }
    private data class LineSegment(
        val position: Int,
        val start: Int,
        val end: Int,
        val strength: Float
    )
    private data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
        val area: Long get() = width.toLong() * height
    }
    private data class ScoredRect(val rect: Rect, val confidence: Float)

    companion object {
        private const val MAX_SAMPLE_SIDE = 512
        private const val TILE_SIZE = 8
        private const val REFINEMENT_TILES = 2

        private const val LUMA_STD_NORMALIZER = 32.0
        private const val GRADIENT_NORMALIZER = 20.0
        private const val ACTIVE_TILE_THRESHOLD = 0.18f
        private const val TARGET_MEAN_ACTIVITY = 0.25f
        private const val TARGET_ACTIVE_RATIO = 0.45f
        private const val SURROUNDING_BAND_TILES = 2
        private const val MAX_SURROUNDING_ACTIVITY_RATIO = 0.55f

        private const val TILE_BOUNDARY_NORMALIZER = 64f
        private const val MIN_BOUNDARY_CELL_STRENGTH = 0.28f
        private const val MAX_SEGMENT_GAP = 1
        private const val MIN_PAIRED_SPAN_RATIO = 0.50f
        private const val MAX_PAIRED_EDGE_OFFSET_RATIO = 0.15f
        private const val MIN_SIDE_EVIDENCE = 0.40f
        private const val MIN_AVERAGE_BOUNDARY_SCORE = 0.45f
        private const val IMAGE_EDGE_SCORE = 1.0f
        private const val STRONG_PIXEL_BOUNDARY = 18
        private const val TARGET_BOUNDARY_DISTANCE = 40f
        private const val TARGET_STRONG_BOUNDARY_RATIO = 0.85f
        private const val FULL_SPAN_MEAN_STRENGTH = 0.18
        private const val FULL_SPAN_EDGE_STRENGTH = 0.18
        private const val MAX_BOUNDARY_SAMPLES = 128
        private const val MAX_REFINEMENT_SAMPLES = 512
        private const val EDGE_SNAP_DISTANCE = 16.0
        private const val EDGE_UNIFORM_RATIO = 0.85f

        private const val MIN_WIDTH_RATIO = 0.20f
        private const val MIN_HEIGHT_RATIO = 0.08f
        private const val MIN_AREA_RATIO = 0.03
        private const val MAX_AREA_RATIO = 0.98
        private const val LARGE_CANDIDATE_AREA_RATIO = 0.80
        private const val DUPLICATE_IOU = 0.85

        private const val BOUNDARY_WEIGHT = 0.35f
        private const val ACTIVITY_WEIGHT = 0.30f
        private const val AREA_WEIGHT = 0.20f
        private const val CENTER_WEIGHT = 0.15f
    }
}
