package com.baybin.phone

import com.baybin.protocol.Proto
import org.junit.Assert.*
import org.junit.Test

class GalleryRetryTest {
    private val jpeg = byteArrayOf(1, 2, 3)

    @Test fun networkAndServiceFailuresCanRetryTheSamePhoto() {
        for (kind in listOf(Proto.KIND_ERROR, Proto.KIND_NO_CONNECTION)) {
            val retry = GalleryRetry()
            retry.remember(10, kind, jpeg, "photo")
            val photo = retry.forResult(10, "gallery", false)
            assertNotNull(photo)
            assertSame(jpeg, photo!!.jpeg)
            assertEquals("photo", photo.label)
        }
    }

    @Test fun anOlderCityResultOrGlassesResultCannotRetryTheLatestGalleryPhoto() {
        val retry = GalleryRetry()
        retry.remember(10, Proto.KIND_ERROR, jpeg, "photo")
        assertNull(retry.forResult(9, "gallery", false))
        assertNull(retry.forResult(null, "gallery", false))
        assertNull(retry.forResult(10, "glasses", false))
        assertNull(retry.forResult(10, "gallery", true))
    }

    @Test fun successOrRetakeGuidanceDoesNotKeepRetryingAnUnchangedImage() {
        val retry = GalleryRetry()
        for (kind in listOf(Proto.KIND_OK, Proto.KIND_UNSURE, Proto.KIND_INFO)) {
            retry.remember(10, Proto.KIND_ERROR, jpeg, "failed")
            retry.remember(11, kind, jpeg, "new")
            assertNull(retry.forResult(10, "gallery", false))
            assertNull(retry.forResult(11, "gallery", false))
        }
    }

    @Test fun cancellationOrANewJobReleasesTheRetainedPhoto() {
        val retry = GalleryRetry()
        retry.remember(10, Proto.KIND_ERROR, jpeg, "photo")
        retry.clear()
        assertNull(retry.forResult(10, "gallery", false))
    }
}
