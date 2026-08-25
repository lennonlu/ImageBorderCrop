package com.lennon.imagebordercrop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bumptech.glide.gifencoder.AnimatedGifEncoder
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class GifProcessorInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val files = mutableListOf<File>()
    private val processor = GifProcessor(BorderDetector())

    @After
    fun cleanUp() {
        files.forEach(File::delete)
    }

    @Test
    fun detectsAllFramesAndPreservesAnimationMetadata() = runBlocking {
        val source = newTemp("source", ".gif")
        writeTwoFrameGif(source)

        val image = FileInputStream(source).use { processor.loadGif(it, context.cacheDir) }
        files += image.sourceFile
        assertEquals(2, image.metadata.frameCount)
        assertEquals(2, image.metadata.netscapeLoopCount)
        assertEquals(listOf(100, 250), image.metadata.frameDelaysMs)

        val result = processor.detect(image, 30) { _, _ -> }
        assertEquals(2, result.analyzedFrames)
        assertEquals(2, result.sampledContentFrames)
        assertEquals(2, result.top)
        assertEquals(2, result.bottom)
        assertEquals(1, result.left)
        assertEquals(2, result.right)

        val output = newTemp("output", ".gif")
        processor.encodeCropped(image, result, output) { _, _ -> }
        val encoded = FileInputStream(output).use { processor.loadGif(it, context.cacheDir) }
        files += encoded.sourceFile
        assertEquals(17, encoded.width)
        assertEquals(16, encoded.height)
        assertEquals(2, encoded.metadata.frameCount)
        assertEquals(2, encoded.metadata.netscapeLoopCount)
        assertEquals(listOf(100, 250), encoded.metadata.frameDelaysMs)
    }

    @Test
    fun transparentBackgroundRemainsTransparentAfterCrop() = runBlocking {
        val source = newTemp("transparent", ".gif")
        val frame = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.TRANSPARENT)
            for (y in 2 until 18) for (x in 2 until 18) setPixel(x, y, Color.RED)
        }
        FileOutputStream(source).use { output ->
            val encoder = AnimatedGifEncoder()
            encoder.setSize(20, 20)
            encoder.setRepeat(0)
            encoder.setTransparent(Color.TRANSPARENT)
            assertTrue(encoder.start(output))
            assertTrue(encoder.addFrame(frame))
            assertTrue(encoder.finish())
        }
        frame.recycle()

        val image = FileInputStream(source).use { processor.loadGif(it, context.cacheDir) }
        files += image.sourceFile
        val result = BorderResult(1, 1, 1, 1, BorderType.AUTO, 30)
        val output = newTemp("transparent-output", ".gif")
        processor.encodeCropped(image, result, output) { _, _ -> }
        val encoded = FileInputStream(output).use { processor.loadGif(it, context.cacheDir) }
        files += encoded.sourceFile
        assertEquals(0, Color.alpha(encoded.previewFrame.getPixel(0, 0)))
    }

    private fun writeTwoFrameGif(file: File) {
        val first = borderedFrame()
        val second = borderedFrame().apply {
            for (y in 2 until height - 2) setPixel(1, y, Color.YELLOW)
        }
        FileOutputStream(file).use { output ->
            val encoder = AnimatedGifEncoder()
            encoder.setSize(20, 20)
            encoder.setRepeat(2)
            assertTrue(encoder.start(output))
            encoder.setDelay(100)
            assertTrue(encoder.addFrame(first))
            encoder.setDelay(250)
            assertTrue(encoder.addFrame(second))
            assertTrue(encoder.finish())
        }
        first.recycle()
        second.recycle()
    }

    private fun borderedFrame(): Bitmap =
        Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLACK)
            for (y in 2 until 18) for (x in 2 until 18) setPixel(x, y, Color.RED)
        }

    private fun newTemp(prefix: String, suffix: String): File =
        File.createTempFile(prefix, suffix, context.cacheDir).also(files::add)
}
