package com.lennon.imagebordercrop

import java.io.File
import java.io.DataInputStream
import java.io.FileInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 用户提供样图的本地回归入口。图片不提交到仓库；设置对应环境变量时才执行。
 */
class ProvidedImageRegressionTest {
    @Test
    fun fullWidthNightPhotoIsExtracted() {
        val path = System.getenv("IMAGE_BORDER_SAMPLE_1_RAW")
        assumeTrue(!path.isNullOrBlank() && File(path).isFile)

        val image = readImage(path!!)
        val result = BorderDetector().detect(image.pixels, image.width, image.height, BorderType.AUTO, 30)
        val content = ContentRegionDetector().detect(image.pixels, image.width, image.height)

        assertEquals("auto=$result content=$content", DetectionStrategy.CONTENT_REGION, result.strategy)
        assertWithin(0, result.left)
        assertWithin(374, result.top)
        assertWithin(0, result.right)
        assertWithin(616, result.bottom) // 2560 - 1944
        assertTrue(result.confidence >= 0.60f)
    }

    @Test
    fun centeredMemeIsExtractedFromWhiteAppScreen() {
        val path = System.getenv("IMAGE_BORDER_SAMPLE_2_RAW")
        assumeTrue(!path.isNullOrBlank() && File(path).isFile)

        val image = readImage(path!!)
        val result = BorderDetector().detect(image.pixels, image.width, image.height, BorderType.AUTO, 30)

        assertEquals(DetectionStrategy.CONTENT_REGION, result.strategy)
        assertWithin(208, result.left, result.toString())
        assertWithin(561, result.top, result.toString())
        assertWithin(209, result.right, result.toString()) // 882 - 673
        assertWithin(889, result.bottom, result.toString()) // 1920 - 1031
        assertTrue(result.confidence >= 0.60f)
    }

    @Test
    fun fullFrameMemeWithSubtitlesIsNotCropped() {
        val path = System.getenv("IMAGE_BORDER_NO_CROP_RAW")
        assumeTrue(!path.isNullOrBlank() && File(path).isFile)

        val image = readImage(path!!)
        val result = BorderDetector().detect(image.pixels, image.width, image.height, BorderType.AUTO, 30)

        assertFalse("unexpected crop: $result", result.hasBorder())
        assertEquals(DetectionStrategy.COLOR_BORDER, result.strategy)
    }

    private fun readImage(path: String): TestImage {
        DataInputStream(FileInputStream(path).buffered()).use { input ->
            val width = input.readInt()
            val height = input.readInt()
            val pixels = IntArray(width * height) { input.readInt() }
            return TestImage(pixels, width, height)
        }
    }

    private fun assertWithin(expected: Int, actual: Int, context: String = "") {
        assertTrue("expected $expected ± 3, actual $actual; $context", actual in expected - 3..expected + 3)
    }

    private data class TestImage(val pixels: IntArray, val width: Int, val height: Int)
}
