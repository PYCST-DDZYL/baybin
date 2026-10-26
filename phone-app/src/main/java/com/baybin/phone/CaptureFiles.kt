package com.baybin.phone

import java.io.File

internal object CaptureFiles {
    private fun directory(cache: File) = File(cache, "capture")

    fun create(cache: File): File {
        val dir = directory(cache)
        check(dir.isDirectory || dir.mkdirs()) { "Cannot create capture cache" }
        prune(cache)
        return File.createTempFile("photo_", ".jpg", dir)
    }

    fun prune(cache: File) {
        // Remove stale leftovers on the next app launch or capture after process death.
        val dir = directory(cache)
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        dir.listFiles()?.filter { it.name.startsWith("photo_") && it.isFile && it.lastModified() < cutoff }?.forEach { it.delete() }
    }

    fun restore(cache: File, name: String?): File? {
        if (name == null || !name.matches(Regex("photo_[A-Za-z0-9_-]+\\.jpg"))) return null
        val file = File(directory(cache), name)
        return file.takeIf { it.isFile && it.canonicalFile.parentFile == directory(cache).canonicalFile }
    }
}
