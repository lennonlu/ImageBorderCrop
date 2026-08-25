package com.lennon.imagebordercrop

import android.graphics.Bitmap
import android.graphics.Canvas
import com.bumptech.glide.load.Key
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import java.nio.ByteBuffer
import java.security.MessageDigest

class GifCropTransformation(
    private val left: Int,
    private val top: Int,
    private val width: Int,
    private val height: Int
) : BitmapTransformation() {

    override fun transform(
        pool: BitmapPool,
        toTransform: Bitmap,
        outWidth: Int,
        outHeight: Int
    ): Bitmap {
        require(left >= 0 && top >= 0 && width > 0 && height > 0)
        require(left + width <= toTransform.width && top + height <= toTransform.height)
        val result = pool.get(width, height, Bitmap.Config.ARGB_8888)
        result.eraseColor(0)
        result.setHasAlpha(true)
        Canvas(result).drawBitmap(toTransform, -left.toFloat(), -top.toFloat(), null)
        return result
    }

    override fun updateDiskCacheKey(messageDigest: MessageDigest) {
        messageDigest.update(ID_BYTES)
        messageDigest.update(
            ByteBuffer.allocate(16)
                .putInt(left)
                .putInt(top)
                .putInt(width)
                .putInt(height)
                .array()
        )
    }

    override fun equals(other: Any?): Boolean =
        other is GifCropTransformation &&
            left == other.left && top == other.top &&
            width == other.width && height == other.height

    override fun hashCode(): Int {
        var result = ID.hashCode()
        result = 31 * result + left
        result = 31 * result + top
        result = 31 * result + width
        result = 31 * result + height
        return result
    }

    companion object {
        private const val ID = "com.lennon.imagebordercrop.GifCropTransformation.v1"
        private val ID_BYTES = ID.toByteArray(Key.CHARSET)
    }
}
