package com.phouton.phooto.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the framework-free gallery grouping / filtering logic. These
 * deliberately avoid [android.net.Uri] so they run without Robolectric.
 */
class GalleryGroupingTest {

    // ---- canonicalDirPath -------------------------------------------------

    @Test
    fun `canonicalDirPath for SAF item strips storage prefix`() {
        val path = canonicalDirPath(
            safTreeUri = "content://tree/primary%3ADCIM%2FCamera",
            bucketId = "primary:DCIM/Camera",
            relativePath = null,
        )
        assertEquals("DCIM/Camera", path)
    }

    @Test
    fun `canonicalDirPath for MediaStore item trims trailing slash`() {
        val path = canonicalDirPath(
            safTreeUri = null,
            bucketId = "1234",
            relativePath = "DCIM/Camera/",
        )
        assertEquals("DCIM/Camera", path)
    }

    @Test
    fun `canonicalDirPath is null when there is no directory`() {
        assertNull(canonicalDirPath(null, "1234", null))
    }

    // ---- canonicalMediaPath ----------------------------------------------

    @Test
    fun `canonicalMediaPath for SAF uses the doc id path`() {
        val path = canonicalMediaPath(
            safTreeUri = "content://tree/x",
            relativePath = null,
            displayName = null,
            lastPathSegment = "primary:DCIM/Camera/img.jpg",
        )
        assertEquals("dcim/camera/img.jpg", path)
    }

    @Test
    fun `canonicalMediaPath for MediaStore joins relative path and name`() {
        val path = canonicalMediaPath(
            safTreeUri = null,
            relativePath = "DCIM/Camera/",
            displayName = "IMG.JPG",
            lastPathSegment = null,
        )
        assertEquals("dcim/camera/img.jpg", path)
    }

    @Test
    fun `canonicalMediaPath is null when the name is missing`() {
        assertNull(canonicalMediaPath(null, "DCIM/Camera/", null, null))
        assertNull(canonicalMediaPath(null, null, "img.jpg", null))
    }

    // ---- shouldIncludeMedia ----------------------------------------------

    @Test
    fun `deny-list excludes media under a selected prefix`() {
        val settings = AppSettings(requireExplicit = false)
        assertFalse(
            shouldIncludeMedia("DCIM/Camera/a.jpg", settings, listOf("DCIM/Camera/"), emptySet()),
        )
        assertTrue(
            shouldIncludeMedia("DCIM/Other/a.jpg", settings, listOf("DCIM/Camera/"), emptySet()),
        )
    }

    @Test
    fun `allow-list keeps only media under a selected prefix`() {
        val settings = AppSettings(requireExplicit = true)
        assertTrue(
            shouldIncludeMedia("DCIM/Camera/a.jpg", settings, listOf("DCIM/Camera/"), emptySet()),
        )
        assertFalse(
            shouldIncludeMedia("DCIM/Other/a.jpg", settings, listOf("DCIM/Camera/"), emptySet()),
        )
    }

    @Test
    fun `allow-list with no folders hides everything`() {
        val settings = AppSettings(requireExplicit = true)
        assertFalse(shouldIncludeMedia("DCIM/a.jpg", settings, emptyList(), emptySet()))
        assertFalse(shouldIncludeMedia(null, settings, emptyList(), emptySet()))
    }

    @Test
    fun `nomedia directories are excluded when respected`() {
        val respected = AppSettings(respectNoMedia = true)
        assertFalse(
            shouldIncludeMedia("DCIM/Secret/a.jpg", respected, emptyList(), setOf("DCIM/Secret/")),
        )

        val ignored = AppSettings(respectNoMedia = false)
        assertTrue(
            shouldIncludeMedia("DCIM/Secret/a.jpg", ignored, emptyList(), setOf("DCIM/Secret/")),
        )
    }

    // ---- isAnimatedMime ---------------------------------------------------

    @Test
    fun `animated mime detection is case-insensitive and excludes stills`() {
        assertTrue(isAnimatedMime("image/gif"))
        assertTrue(isAnimatedMime("image/WEBP"))
        assertTrue(isAnimatedMime("image/heif"))
        assertTrue(isAnimatedMime("image/avif"))
        assertFalse(isAnimatedMime("image/jpeg"))
        assertFalse(isAnimatedMime("video/mp4"))
        assertFalse(isAnimatedMime(null))
    }
}
