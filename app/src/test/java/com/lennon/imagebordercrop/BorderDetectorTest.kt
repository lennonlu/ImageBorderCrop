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
