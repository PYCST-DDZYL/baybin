package com.baybin.phone

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.util.ArrayDeque

/**
 * Stops a photo that clearly shows two different things before the model names one of them.
 * A single object, or a pile of the same thing, is left for the model.
 *
 * The check is local and conservative: two interior blobs, similar in size, different in
 * color, with a gap between them. On the checked photo sets that fires only for the lid
 * beside the blue cap. Anything less clear still goes to the model.
 */
object TwoThings {

    fun seesTwo(jpeg: ByteArray): Boolean = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        val long = maxOf(bounds.outWidth, bounds.outHeight)
        if (long <= 0) false
        else {
            var sample = 1
            while (long / (sample * 2) >= SIDE) sample *= 2
            val raw = BitmapFactory.decodeByteArray(
                jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample })
            if (raw == null) false
            else {
                val scale = SIDE.toFloat() / maxOf(raw.width, raw.height)
                val bmp = if (scale < 0.999f) {
                    Bitmap.createScaledBitmap(
                        raw,
                        (raw.width * scale).toInt().coerceAtLeast(1),
                        (raw.height * scale).toInt().coerceAtLeast(1),
                        true,
                    )
                } else raw
                try {
                    separated(bmp)
                } finally {
                    if (bmp !== raw) raw.recycle()
                    bmp.recycle()
                }
            }
        }
    } catch (_: Exception) {
        false
    }

    private fun separated(bmp: Bitmap): Boolean {
        val w = bmp.width
        val h = bmp.height
        if (w < 8 || h < 8) return false
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val n = w * h
        val border = ArrayList<Int>(2 * (w + h))
        for (x in 0 until w) {
            border.add(px[x])
            border.add(px[(h - 1) * w + x])
        }
        for (y in 0 until h) {
            border.add(px[y * w])
            border.add(px[y * w + w - 1])
        }
        val bgR = median(border) { (it shr 16) and 0xff }
        val bgG = median(border) { (it shr 8) and 0xff }
        val bgB = median(border) { it and 0xff }
        val fg = BooleanArray(n)
        for (i in 0 until n) {
            val c = px[i]
            val d = Math.abs(((c shr 16) and 0xff) - bgR) +
                Math.abs(((c shr 8) and 0xff) - bgG) +
                Math.abs((c and 0xff) - bgB)
            if (d >= DIST) fg[i] = true
        }
        // A region that touches the frame is the table or the wall, not a second item.
        val seen = BooleanArray(n)
        val stack = ArrayDeque<Int>()
        for (x in 0 until w) {
            stack.add(x)
            stack.add((h - 1) * w + x)
        }
        for (y in 0 until h) {
            stack.add(y * w)
            stack.add(y * w + w - 1)
        }
        while (stack.isNotEmpty()) {
            val i = stack.removeLast()
            if (i < 0 || i >= n || seen[i] || !fg[i]) continue
            seen[i] = true
            fg[i] = false
            val x = i % w
            if (x > 0) stack.add(i - 1)
            if (x + 1 < w) stack.add(i + 1)
            if (i - w >= 0) stack.add(i - w)
            if (i + w < n) stack.add(i + w)
        }
        seen.fill(false)
        val areas = ArrayList<IntArray>()
        val colors = ArrayList<IntArray>()
        for (start in 0 until n) {
            if (!fg[start] || seen[start]) continue
            var area = 0
            var sr = 0
            var sg = 0
            var sb = 0
            var minX = w
            var minY = h
            var maxX = 0
            var maxY = 0
            stack.add(start)
            seen[start] = true
            while (stack.isNotEmpty()) {
                val p = stack.removeLast()
                area++
                val x = p % w
                val y = p / w
                val c = px[p]
                sr += (c shr 16) and 0xff
                sg += (c shr 8) and 0xff
                sb += c and 0xff
                if (x < minX) minX = x
                if (y < minY) minY = y
                if (x > maxX) maxX = x
                if (y > maxY) maxY = y
                if (x > 0 && fg[p - 1] && !seen[p - 1]) {
                    seen[p - 1] = true
                    stack.add(p - 1)
                }
                if (x + 1 < w && fg[p + 1] && !seen[p + 1]) {
                    seen[p + 1] = true
                    stack.add(p + 1)
                }
                if (y > 0 && fg[p - w] && !seen[p - w]) {
                    seen[p - w] = true
                    stack.add(p - w)
                }
                if (y + 1 < h && fg[p + w] && !seen[p + w]) {
                    seen[p + w] = true
                    stack.add(p + w)
                }
            }
            if (area >= MIN_FRAC * n) {
                areas.add(intArrayOf(area, minX, minY, maxX, maxY))
                colors.add(intArrayOf(sr / area, sg / area, sb / area))
            }
        }
        if (areas.size < 2) return false
        var first = 0
        var second = 1
        if (areas[1][0] > areas[0][0]) {
            first = 1
            second = 0
        }
        for (i in 2 until areas.size) {
            val a = areas[i][0]
            if (a > areas[first][0]) {
                second = first
                first = i
            } else if (a > areas[second][0]) {
                second = i
            }
        }
        val a = areas[first]
        val b = areas[second]
        val gapX = gap(a[1], a[3], b[1], b[3])
        val gapY = gap(a[2], a[4], b[2], b[4])
        if (gapX < MIN_GAP && gapY < MIN_GAP) return false
        val ca = colors[first]
        val cb = colors[second]
        val color = Math.abs(ca[0] - cb[0]) + Math.abs(ca[1] - cb[1]) + Math.abs(ca[2] - cb[2])
        if (color < MIN_COLOR) return false
        val ratio = minOf(a[0], b[0]).toFloat() / maxOf(a[0], b[0])
        return ratio >= MIN_RATIO
    }

    private fun gap(a1: Int, a2: Int, b1: Int, b2: Int): Int = when {
        a2 < b1 -> b1 - a2 - 1
        b2 < a1 -> a1 - b2 - 1
        else -> 0
    }

    private fun median(values: List<Int>, channel: (Int) -> Int): Int {
        val s = IntArray(values.size) { channel(values[it]) }
        s.sort()
        return s[s.size / 2]
    }

    private const val SIDE = 160
    private const val DIST = 70
    private const val MIN_FRAC = 0.06f
    private const val MIN_GAP = 1
    private const val MIN_COLOR = 80
    private const val MIN_RATIO = 0.4f
}
