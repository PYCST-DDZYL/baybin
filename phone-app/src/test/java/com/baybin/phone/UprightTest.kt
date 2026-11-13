package com.baybin.phone

import org.junit.Test

class UprightTest {
    @Test(expected = IllegalArgumentException::class)
    fun zeroMaxSideIsRejectedBeforeDecoding() {
        Upright.prepare(byteArrayOf(), 0, 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidJpegQualityIsRejectedBeforeDecoding() {
        Upright.prepare(byteArrayOf(), 0, 1024, quality = 101)
    }
}
