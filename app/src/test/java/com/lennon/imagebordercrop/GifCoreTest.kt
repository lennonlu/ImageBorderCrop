package com.lennon.imagebordercrop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class GifCoreTest {
    private val processor = GifProcessor(BorderDetector())

    @Test
    fun signatureDetectionUsesFileHeader() {
        assertTrue(processor.hasGifSignature(ByteArrayInputStream("GIF89a-data".toByteArray())))
        assertTrue(processor.hasGifSignature(ByteArrayInputStream("GIF87a-data".toByteArray())))
        assertFalse(processor.hasGifSignature(ByteArrayInputStream("not-a-gif".toByteArray())))
    }

    @Test
    fun allFrameAggregationUsesSafeMinimumForEverySide() {
        val result = GifBorderAggregator.aggregate(
            listOf(
                border(top = 8, bottom = 9, left = 10, right = 11),
                border(top = 6, bottom = 7, left = 1, right = 5),
                border(top = 7, bottom = 2, left = 4, right = 8)
            ),
            threshold = 30
        )

        assertEquals(6, result.top)
        assertEquals(2, result.bottom)
        assertEquals(1, result.left)
        assertEquals(5, result.right)
        assertEquals(DetectionStrategy.GIF_ALL_FRAMES, result.strategy)
        assertEquals(3, result.analyzedFrames)
    }

    @Test
    fun oneFrameWithoutBorderPreventsCroppingThatSide() {
        val result = GifBorderAggregator.aggregate(
            listOf(border(5, 5, 5, 5), border(0, 5, 5, 5)),
            threshold = 30
        )
        assertEquals(0, result.top)
        assertEquals(5, result.bottom)
    }

    @Test
    fun representativeFramesUseFirstMiddleAndLastWithoutDuplicates() {
        assertArrayEquals(intArrayOf(0), GifSampleFrames.indices(1))
        assertArrayEquals(intArrayOf(0, 1), GifSampleFrames.indices(2))
        assertArrayEquals(intArrayOf(0, 1, 2), GifSampleFrames.indices(3))
        assertArrayEquals(intArrayOf(0, 5, 9), GifSampleFrames.indices(10))
    }

    @Test
    fun sampledContentProtectsEverySideFromColorScanOvercrop() {
        val result = GifBorderAggregator.aggregate(
            results = listOf(border(20, 20, 20, 20), border(22, 21, 23, 24)),
            threshold = 30,
            width = 100,
            height = 80,
            sampledContent = listOf(
                ContentRegion(4, 5, 94, 73, confidence = 0.80f),
                ContentRegion(5, 6, 93, 72, confidence = 0.82f)
            ),
            sampledContentFrames = 3
        )

        assertEquals(5, result.top)
        assertEquals(7, result.bottom)
        assertEquals(4, result.left)
        assertEquals(6, result.right)
        assertEquals(3, result.sampledContentFrames)
    }

    @Test
    fun sampledContentNeverIncreasesTheAllFrameSafeCrop() {
        val result = GifBorderAggregator.aggregate(
            results = listOf(border(5, 5, 5, 5), border(7, 7, 7, 7)),
            threshold = 30,
            width = 100,
            height = 80,
            sampledContent = listOf(ContentRegion(10, 10, 90, 70, confidence = 0.90f)),
            sampledContentFrames = 3
        )

        assertEquals(5, result.top)
        assertEquals(5, result.bottom)
        assertEquals(5, result.left)
        assertEquals(5, result.right)
    }

    @Test
    fun unstableSampleBoundaryDoesNotCauseVisibleUndercrop() {
        val result = GifBorderAggregator.aggregate(
            results = listOf(border(20, 20, 20, 20)),
            threshold = 30,
            width = 320,
            height = 240,
            sampledContent = listOf(
                ContentRegion(0, 20, 300, 220, confidence = 0.95f),
                ContentRegion(10, 20, 300, 220, confidence = 0.95f),
                ContentRegion(10, 20, 300, 220, confidence = 0.95f)
            ),
            sampledContentFrames = 3
        )

        assertEquals(20, result.left)
        assertEquals(20, result.top)
    }

    @Test
    fun lowConfidenceSampleDoesNotChangeTheSafeCrop() {
        val result = GifBorderAggregator.aggregate(
            results = listOf(border(12, 12, 12, 12)),
            threshold = 30,
            width = 100,
            height = 80,
            sampledContent = listOf(ContentRegion(2, 2, 98, 78, confidence = 0.59f)),
            sampledContentFrames = 1
        )

        assertEquals(12, result.top)
        assertEquals(12, result.bottom)
        assertEquals(12, result.left)
        assertEquals(12, result.right)
    }

    @Test
    fun gifScanIgnoresDarkPaletteNoiseInsideBlackBars() {
        val width = 20
        val height = 12
        val black = 0xFF000000.toInt()
        val content = 0xFF557799.toInt()
        val paletteNoise = 0xFF001F00.toInt()
        val pixels = IntArray(width * height) { content }
        for (y in 0 until 2) for (x in 0 until width) pixels[y * width + x] = black
        for (y in 9 until height) for (x in 0 until width) pixels[y * width + x] = black
        // 30% 的低亮度调色板抖动不应让整条黑边失效。
        for (x in 7 until 13) pixels[10 * width + x] = paletteNoise

        val result = BorderDetector().detectGifColorBorder(pixels, width, height, threshold = 30)

        assertEquals(2, result.top)
        assertEquals(3, result.bottom)
    }

    @Test
    fun gifScanKeepsSideWhenBlackBarContainsBrightSubtitle() {
        val width = 20
        val height = 12
        val black = 0xFF000000.toInt()
        val content = 0xFF557799.toInt()
        val subtitle = 0xFFFFFFFF.toInt()
        val pixels = IntArray(width * height) { content }
        for (y in 9 until height) for (x in 0 until width) pixels[y * width + x] = black
        for (x in 9 until 11) pixels[11 * width + x] = subtitle

        val result = BorderDetector().detectGifColorBorder(pixels, width, height, threshold = 30)

        assertEquals(0, result.bottom)
    }

    @Test
    fun gifScanKeepsConfirmedBorderBeforeDarkSceneContent() {
        val width = 20
        val height = 12
        val black = 0xFF000000.toInt()
        val bright = 0xFFFFAA33.toInt()
        val pixels = IntArray(width * height) { black }
        // 前三行是边框；第四行仍有 80% 黑色，但亮区已属于暗场画面。
        for (x in 0 until 4) pixels[3 * width + x] = bright
        for (y in 4 until height) for (x in 0 until width) pixels[y * width + x] = bright

        val result = BorderDetector().detectGifColorBorder(pixels, width, height, threshold = 30)

        assertEquals(3, result.top)
    }

    @Test
    fun gifScanStopsForPartialBrightContentInsideMostlyBlackColumn() {
        val width = 20
        val height = 20
        val black = 0xFF000000.toInt()
        val red = 0xFFFF0000.toInt()
        val yellow = 0xFFFFFF00.toInt()
        val pixels = IntArray(width * height) { black }
        for (y in 2 until 18) for (x in 2 until 18) pixels[y * width + x] = red
        // 这一列仍有 65% 黑色，但局部黄色内容已经进入原本的左边框。
        for (y in 7 until 14) pixels[y * width + 1] = yellow

        val result = BorderDetector().detectGifColorBorder(pixels, width, height, threshold = 30)

        assertEquals(1, result.left)
    }

    @Test
    fun gifScanStopsWhenRealContentOccupiesMostOfBorderLine() {
        val width = 20
        val height = 12
        val black = 0xFF000000.toInt()
        val content = 0xFF557799.toInt()
        val pixels = IntArray(width * height) { content }
        for (y in 9 until height) for (x in 0 until width) pixels[y * width + x] = black
        // 最内侧一行超过 40% 是内容，安全扫描必须停在下一行。
        for (x in 0 until 9) pixels[9 * width + x] = content

        val result = BorderDetector().detectGifColorBorder(pixels, width, height, threshold = 30)

        assertEquals(2, result.bottom)
    }

    @Test
    fun safetyLimitsRejectOversizedInputs() {
        assertThrows(IllegalArgumentException::class.java) {
            GifSafetyLimits.validate(
                GifSafetyLimits.MAX_SOURCE_BYTES + 1,
                100,
                100,
                2
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            GifSafetyLimits.validate(1024, 1000, 1000, GifSafetyLimits.MAX_FRAMES + 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GifSafetyLimits.validate(1024, 2000, 2000, 100)
        }
    }

    private fun border(top: Int, bottom: Int, left: Int, right: Int) = BorderResult(
        top = top,
        bottom = bottom,
        left = left,
        right = right,
        borderType = BorderType.BLACK,
        threshold = 30
    )
}
