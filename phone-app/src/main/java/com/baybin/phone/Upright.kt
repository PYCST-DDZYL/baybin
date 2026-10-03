package com.baybin.phone

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * The glasses camera is mounted sideways (sensor orientation 270) and the glasses don't
 * rotate pixels; they set the EXIF orientation tag and also send CameraX's rotation.
 * Not every vision API honours EXIF, so the phone hands the model an upright image.
 */
object Upright {

    /** Clockwise degrees from the EXIF tag, or null if the tag is missing/normal. */
    fun exifDegrees(jpeg: ByteArray): Int? = try {
        when (ExifInterface(ByteArrayInputStream(jpeg)).getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> null
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Upright JPEG no larger than [maxSide] on its long side. [fallbackDegrees] (the rotation
     * the glasses reported) is used only when the file carries no orientation tag. Returns the
     * input unchanged when it is already upright and small enough.
     */
    fun prepare(jpeg: ByteArray, fallbackDegrees: Int, maxSide: Int, quality: Int = 85): ByteArray {
        val degrees = (exifDegrees(jpeg) ?: fallbackDegrees).mod(360)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        val longSide = maxOf(bounds.outWidth, bounds.outHeight)
        if (longSide <= 0) return jpeg
        if (degrees == 0 && longSide <= maxSide) return jpeg

        var sample = 1
        while (longSide / (sample * 2) >= maxSide) sample *= 2
        val src = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size,
            BitmapFactory.Options().apply { inSampleSize = sample }) ?: return jpeg
        val scale = minOf(1f, maxSide.toFloat() / maxOf(src.width, src.height))
        val m = Matrix().apply {
            if (scale < 1f) postScale(scale, scale)
            if (degrees != 0) postRotate(degrees.toFloat())
        }
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        return try {
            // Re-encoding drops the EXIF block, so the tag can't be applied twice.
            ByteArrayOutputStream(jpeg.size / 2).also { out.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
        } finally {
            if (out !== src) out.recycle()
            src.recycle()
        }
    }
}
