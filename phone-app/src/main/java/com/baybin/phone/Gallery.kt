package com.baybin.phone

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * Gallery test mode input: any photo the phone can show (JPEG, HEIC, PNG, WebP…) turned into
 * what the glasses would send — an upright JPEG, at most [maxSide] px on the long side, q80.
 * ImageDecoder applies the EXIF orientation and decodes straight to the target size.
 */
object Gallery {

    fun toJpeg(source: ImageDecoder.Source, maxSide: Int, quality: Int = 80): ByteArray {
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val scale = minOf(1f, maxSide.toFloat() / maxOf(w, h))
            if (scale < 1f) {
                decoder.setTargetSize((w * scale).roundToInt().coerceAtLeast(1), (h * scale).roundToInt().coerceAtLeast(1))
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE // hardware bitmaps can't be compressed
        }
        return try {
            ByteArrayOutputStream(256 * 1024).also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }
}
