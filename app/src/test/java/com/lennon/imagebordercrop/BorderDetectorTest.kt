package com.lennon.imagebordercrop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BorderDetectorTest {
    private val detector = BorderDetector()

    @Test
    fun explicitBlackBorderKeepsExistingBehavior() {
        val image = patternedImage(96, 72, rgb(0, 0, 0))
        fillPattern(image, 96, 8, 6, 88, 66)

        val result = detector.detect(image, 96, 72, BorderType.BLACK, 30)

        assertEquals(8, result.left)
        assertEquals(8, result.right)
        assertEquals(6, result.top)
        assertEquals(6, result.bottom)
        assertEquals(DetectionStrategy.COLOR_BORDER, result.strategy)
    }

    @Test
    fun nearBlackBorderIsAcceptedByThreshold() {
        val image = patternedImage(80, 60, rgb(24, 27, 29))
        fillPattern(image, 80, 5, 5, 75, 55)

        val result = detector.detect(image, 80, 60, BorderType.BLACK, 30)

        assertEquals(5, result.left)
        assertEquals(5, result.right)
        assertEquals(5, result.top)
        assertEquals(5, result.bottom)
    }

    @Test
    fun explicitModeDoesNotInvokeContentRegionFallback() {
        val image = patternedImage(120, 160, rgb(255, 255, 255))
        fillPattern(image, 120, 24, 40, 96, 120)

        val result = detector.detect(image, 120, 160, BorderType.BLACK, 30)

        assertFalse(result.hasBorder())
        assertEquals(DetectionStrategy.COLOR_BORDER, result.strategy)
    }

    @Test
    fun autoModeKeepsThinBlackBorderResultOnBrightFullFrameImage() {
        val width = 120
        val height = 100
        val image = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val variation = (x * 7 + y * 11) % 35
                image[y * width + x] = rgb(165 + variation, 175 + variation, 185 + variation)
            }
        }
        for (x in 0 until width) image[(height - 1) * width + x] = rgb(0, 0, 0)

        val result = detector.detect(image, width, height, BorderType.AUTO, 30)

        assertEquals(0, result.top)
        assertEquals(1, result.bottom)
        assertEquals(0, result.left)
        assertEquals(0, result.right)
        assertEquals(BorderType.BLACK, result.borderType)
        assertEquals(DetectionStrategy.COLOR_BORDER, result.strategy)
    }

    @Test
    fun autoModePrefersThinBlackArtifactOverWhiteDocumentMargin() {
        val width = 120
        val height = 100
        val white = rgb(250, 250, 250)
        val ink = rgb(80, 80, 80)
        val image = IntArray(width * height) { white }

        // 顶部 8px 是正文自然留白；之后每行都有少量文字或纹理。
        for (y in 8 until height - 1) {
            val start = (y * 7) % (width - 12)
            for (x in start until start + 12) image[y * width + x] = ink
        }
        // 底部只有一条近黑压缩线，应只清理这一行。
        for (x in 0 until width) image[(height - 1) * width + x] = rgb(4, 4, 4)

        val result = detector.detect(image, width, height, BorderType.AUTO, 30)

        assertEquals(0, result.top)
        assertEquals(1, result.bottom)
        assertEquals(0, result.left)
        assertEquals(0, result.right)
        assertEquals(BorderType.BLACK, result.borderType)
        assertEquals(DetectionStrategy.COLOR_BORDER, result.strategy)
    }

    private fun patternedImage(width: Int, height: Int, color: Int) = IntArray(width * height) { color }

    private fun fillPattern(image: IntArray, width: Int, left: Int, top: Int, right: Int, bottom: Int) {
        for (y in top until bottom) {
            for (x in left until right) {
                val variation = (x * 17 + y * 29) % 96
                image[y * width + x] = rgb(48 + variation, 72 + variation / 2, 112 + variation)
            }
        }
    }

    private fun rgb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or
            (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
}
