package com.baybin.phone

import org.junit.Assert.*
import org.junit.Test

class KeyStorageTest {
    @Test fun surroundingWhitespaceIsRemovedBeforeEncryption() {
        var stored: String? = null
        val result = KeyStorage.save(" \nexample-key\r\n", { key ->
            assertEquals("example-key", key)
            "encrypted-value"
        }, { ciphertext -> stored = ciphertext; true })
        assertEquals(KeyStorage.Result.SAVED, result)
        assertEquals("encrypted-value", stored)
    }

    @Test fun unsafeInputNeverReachesEncryptionOrStorage() {
        for (input in listOf("", " \n", "key\nvalue", "key\rvalue", "key\tvalue", "key value",
            "key\u0000value", "key\u007fvalue", "key密钥", "key\u00a0value")) {
            val result = KeyStorage.save(input, { fail("Invalid input reached encryption"); it },
                { fail("Invalid input reached storage"); true })
            assertEquals(KeyStorage.Result.INVALID, result)
        }
    }

    @Test fun encryptionFailureDoesNotWritePlaintext() {
        val result = KeyStorage.save("example-key", { throw IllegalStateException("Keystore unavailable") },
            { fail("Encryption failed; nothing should be stored"); true })
        assertEquals(KeyStorage.Result.FAILED, result)
    }

    @Test fun failedDiskWriteIsNotReportedAsSuccess() {
        assertEquals(KeyStorage.Result.FAILED,
            KeyStorage.save("example-key", { "encrypted-value" }, { false }))
    }

    @Test fun storageExceptionBecomesARetryableFailure() {
        assertEquals(KeyStorage.Result.FAILED,
            KeyStorage.save("example-key", { "encrypted-value" }, { throw IllegalStateException("Unavailable") }))
    }

    @Test fun failureMessagesExistInBothLanguages() {
        for (language in listOf("zh", "en")) {
            assertTrue(Lang.s(language, "key_invalid").isNotBlank())
            assertTrue(Lang.s(language, "key_save_failed").isNotBlank())
        }
    }
}
