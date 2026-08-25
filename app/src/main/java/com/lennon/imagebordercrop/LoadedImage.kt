package com.lennon.imagebordercrop

import android.graphics.Bitmap
import java.io.File

sealed class LoadedImage {
    abstract val width: Int
    abstract val height: Int
    abstract val previewFrame: Bitmap

    data class Static(
        val bitmap: Bitmap
    ) : LoadedImage() {
        override val width: Int = bitmap.width
        override val height: Int = bitmap.height
        override val previewFrame: Bitmap = bitmap
    }

    data class Gif(
        val sourceFile: File,
        val metadata: GifMetadata,
        override val previewFrame: Bitmap
    ) : LoadedImage() {
        override val width: Int = metadata.width
        override val height: Int = metadata.height
    }
}

data class GifMetadata(
    val width: Int,
    val height: Int,
    val frameCount: Int,
    /** Netscape loop count: 0 means forever, -1 means the extension was absent. */
    val netscapeLoopCount: Int,
    val frameDelaysMs: List<Int>
)
