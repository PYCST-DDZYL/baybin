package com.baybin.phone

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class QwenClientTest {
    @Test fun supersededPhotoStopsBeforeImageSerializationOrRequestConstruction() {
        // Deliberately unusable connection settings: cancellation must happen before
        // Base64 encoding, header validation, URL parsing, or any network access.
        val client = QwenClient("not a URL", "invalid\nheader", "example-model")
        val error = assertThrows(IOException::class.java) {
            client.ask(byteArrayOf(1, 2, 3), "example prompt", longArrayOf(2000), 7000,
                isCurrent = { false })
        }
        assertTrue(error.message.orEmpty().contains("superseded"))
    }
}
