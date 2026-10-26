package com.baybin.phone

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CaptureFilesTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun shotsHaveDistinctPrivateCachePathsAndCanBeRestoredAfterRotation() {
        val one = CaptureFiles.create(temp.root)
        val two = CaptureFiles.create(temp.root)
        assertNotEquals(one, two)
        assertEquals(File(temp.root, "capture").canonicalFile, one.canonicalFile.parentFile)
        assertEquals(one.canonicalFile, CaptureFiles.restore(temp.root, one.name)!!.canonicalFile)
        one.delete()
        assertNull(CaptureFiles.restore(temp.root, one.name))
    }

    @Test fun savedNamesCannotEscapeTheCaptureDirectory() {
        for (name in listOf(null, "../photo_other.jpg", "photo_a/../../secret.jpg", "settings.xml", "/photo_a.jpg")) {
            assertNull(CaptureFiles.restore(temp.root, name))
        }
    }

    @Test fun staleCapturesArePrunedWithoutTouchingOtherCacheFilesOrCurrentShot() {
        val stale = CaptureFiles.create(temp.root)
        stale.setLastModified(System.currentTimeMillis() - 48 * 60 * 60 * 1000L)
        val current = CaptureFiles.create(temp.root)
        val unrelated = File(temp.root, "other-cache").apply { writeText("keep") }
        assertFalse(stale.exists())
        assertTrue(current.exists())
        assertTrue(unrelated.exists())
    }
}
