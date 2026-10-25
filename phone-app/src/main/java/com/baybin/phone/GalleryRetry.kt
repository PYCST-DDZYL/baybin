package com.baybin.phone

import com.baybin.protocol.Proto

/** At most one failed gallery JPEG in memory; never reuse it for a different displayed photo. */
internal class GalleryRetry {
    data class Photo(val jobId: Int, val jpeg: ByteArray, val label: String)
    private var photo: Photo? = null

    fun remember(jobId: Int, kind: Int, jpeg: ByteArray, label: String) {
        photo = if (kind == Proto.KIND_ERROR || kind == Proto.KIND_NO_CONNECTION) {
            Photo(jobId, jpeg, label)
        } else null
    }

    fun forResult(jobId: Int?, source: String, busy: Boolean): Photo? =
        photo?.takeIf { !busy && source == "gallery" && it.jobId == jobId }

    fun clear() { photo = null }
}
