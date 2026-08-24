package com.lennon.imagebordercrop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentRegionDetectorTest {
    private val detector = ContentRegionDetector()

    @Test
    fun findsFullWidthPhotoBetweenUiBars() {
        val width = 240
        val height = 400
        val image = IntArray(width * height) { rgb(31, 34, 39) }
        fillPattern(image, width, 0, 72, width, 304)
        fillRect(image, width, 24, 18, 46, 28, rgb(240, 240, 240))
        fillRect(image, width, 68, 330, 172, 372, rgb(10, 10, 10))

        val result = detector.detect(image, width, height)

        assertRegion(result, 0, 72, width, 304, tolerance = 2)
        assertTrue(result!!.confidence >= 0.60f)
    }

    @Test
    fun findsCenteredPhotoAndIgnoresIconsAndButton() {
        val width = 240
        val height = 400
        val image = IntArray(width * height) { rgb(255, 255, 255) }
        fillPattern(image, width, 52, 110, 188, 250)
        fillRect(image, width, 18, 18, 28, 28, rgb(20, 150, 80))
        fillRect(image, width, 82, 300, 158, 322, rgb(244, 58, 72))
        fillRect(image, width, 102, 382, 138, 386, rgb(170, 170, 170))

        val result = detector.detect(image, width, height)

        assertRegion(result, 52, 110, 188, 250, tolerance = 2)
        assertTrue(result!!.confidence >= 0.60f)
    }

    @Test
    fun areaOutweighsCenterForOffCenterPhoto() {
        val width = 300
        val height = 400
        val image = IntArray(width * height) { rgb(250, 250, 250) }
        fillPattern(image, width, 18, 92, 205, 310)
        fillRect(image, width, 105, 335, 195, 360, rgb(230, 45, 60))

        val result = detector.detect(image, width, height)

        assertRegion(result, 18, 92, 205, 310, tolerance = 3)
    }

    @Test
    fun ignoresCandidateBelowMinimumSize() {
        val width = 240
        val height = 400
        val image = IntArray(width * height) { rgb(255, 255, 255) }
        fillPattern(image, width, 90, 190, 150, 210)

        assertNull(detector.detect(image, width, height))
    }

    @Test
    fun doesNotCropPatternThatFillsWholeImage() {
        val width = 200
        val height = 300
        val image = IntArray(width * height)
        fillPattern(image, width, 0, 0, width, height)

        assertNull(detector.detect(image, width, height))
    }

    private fun assertRegion(
        actual: ContentRegion?,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        tolerance: Int
    ) {
        assertNotNull(actual)
        actual!!
        assertEquals(left.toDouble(), actual.left.toDouble(), tolerance.toDouble())
        assertEquals(top.toDouble(), actual.top.toDouble(), tolerance.toDouble())
        assertEquals(right.toDouble(), actual.right.toDouble(), tolerance.toDouble())
        assertEquals(bottom.toDouble(), actual.bottom.toDouble(), tolerance.toDouble())
    }

    private fun fillPattern(image: IntArray, width: Int, left: Int, top: Int, right: Int, bottom: Int) {
        for (y in top until bottom) {
            for (x in left until right) {
                val variation = (x * 19 + y * 31 + (x * y) % 47) % 110
                image[y * width + x] = rgb(30 + variation, 58 + variation, 95 + variation)
            }
        }
    }

    private fun fillRect(
        image: IntArray,
        width: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        color: Int
    ) {
        for (y in top until bottom) {
            for (x in left until right) image[y * width + x] = color
        }
    }

    private fun rgb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or
            (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
}
