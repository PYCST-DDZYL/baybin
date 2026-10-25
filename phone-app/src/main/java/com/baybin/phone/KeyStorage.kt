package com.baybin.phone

/** Validate header-safe input before encryption; report failures without exposing the key. */
internal object KeyStorage {
    enum class Result { SAVED, INVALID, FAILED }

    fun normalize(input: String): String? = input.trim().takeIf { key ->
        key.isNotEmpty() && key.all { it in '!'..'~' }
    }

    fun save(input: String, encrypt: (String) -> String, persist: (String) -> Boolean): Result {
        val key = normalize(input) ?: return Result.INVALID
        return try {
            if (persist(encrypt(key))) Result.SAVED else Result.FAILED
        } catch (_: Exception) {
            Result.FAILED
        }
    }
}
