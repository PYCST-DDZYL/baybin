package com.baybin.phone

import android.graphics.BitmapFactory

/** Reject only clearly unusable exposure, not a guessed subject or background. No files. */
object PhotoQuality {
    fun help(jpeg: ByteArray): Recognition.Help? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return Recognition.Help.INVALID
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 96) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size,
            BitmapFactory.Options().apply { inSampleSize = sample }) ?: return Recognition.Help.INVALID
        return try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val light = IntArray(pixels.size) { i ->
                val c = pixels[i]
                (77 * ((c shr 16) and 255) + 150 * ((c shr 8) and 255) + 29 * (c and 255)) shr 8
            }.also { it.sort() }
            exposure(light)
        } finally {
            bitmap.recycle()
        }
    }

    internal fun exposure(sorted: IntArray): Recognition.Help? {
        if (sorted.isEmpty()) return Recognition.Help.INVALID
        val low = sorted[(sorted.size - 1) / 20]
        val high = sorted[(sorted.size - 1) * 19 / 20]
        return when {
            high < 28 -> Recognition.Help.LIGHT
            low > 245 -> Recognition.Help.GLARE
            high - low < 3 -> Recognition.Help.CLOSER
            else -> null
        }
    }
}
