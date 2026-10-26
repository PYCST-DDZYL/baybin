package com.baybin.phone

import com.baybin.protocol.Proto

/** At most one failed gallery JPEG in memory; never reuse it for a different displayed photo. */
internal class GalleryRetry {
    data class Photo(val jobId: Int, val jpeg: ByteArray, val label: String, val source: String = "gallery")
    private var photo: Photo? = null

    fun remember(jobId: Int, kind: Int, jpeg: ByteArray, label: String, source: String = "gallery") {
        photo = if (kind == Proto.KIND_ERROR || kind == Proto.KIND_NO_CONNECTION) {
            Photo(jobId, jpeg, label, source)
        } else null
    }

    fun forResult(jobId: Int?, source: String, busy: Boolean): Photo? =
        photo?.takeIf { !busy && source in setOf("gallery", "camera") && it.source == source && it.jobId == jobId }

    fun clear() { photo = null }
}
