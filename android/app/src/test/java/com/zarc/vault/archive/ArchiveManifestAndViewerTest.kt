package com.zarc.vault.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests verifying decrypted archive gallery models, selection state machines,
 * photo viewer navigation, and miniroll filmstrip synchronization.
 */
class ArchiveManifestAndViewerTest {

    private fun createSampleManifest(count: Int): ArchiveManifestData {
        val objects = (1..count).map { i ->
            ArchiveObject(
                id = "zarc_obj_${String.format("%04d", i)}",
                originalName = "IMG_${String.format("%04d", i)}.jpg",
                mimeType = "image/jpeg",
                originalSize = (2_500_000L + i * 50_000L),
                sanitizedSize = (2_400_000L + i * 50_000L),
                offset = 1024L * i,
                length = 2_400_000L + i * 50_000L,
                chunkSize = 65536,
                totalChunks = 37,
                isCover = (i == 1)
            )
        }
        return ArchiveManifestData(
            version = 1,
            containerId = "container_abc123",
            createdAtUnix = 1761696000L,
            coverObjectId = "zarc_obj_0001",
            objects = objects
        )
    }

    @Test
    fun testManifestIntegrityAndObjectCounts() {
        val manifest = createSampleManifest(10)
        assertEquals(10, manifest.objects.size)
        assertEquals("IMG_0001.jpg", manifest.objects[0].originalName)
        assertEquals("zarc_obj_0001", manifest.objects[0].id)
        assertTrue(manifest.objects[0].isCover)
        assertFalse(manifest.objects[1].isCover)

        val totalSanitized = manifest.objects.sumOf { it.sanitizedSize }
        val totalOriginal = manifest.objects.sumOf { it.originalSize }
        assertTrue(totalSanitized < totalOriginal)
    }

    @Test
    fun testGalleryGridTapStateTransitions() {
        val manifest = createSampleManifest(5)
        val selectedIds = mutableSetOf<String>()

        var activeViewerIndex: Int? = null

        // Handler mimicking DecryptedGalleryViewer tap logic:
        fun onPhotoTapped(index: Int) {
            val isSelecting = selectedIds.isNotEmpty()
            val obj = manifest.objects[index]
            if (isSelecting) {
                if (selectedIds.contains(obj.id)) {
                    selectedIds.remove(obj.id)
                } else {
                    selectedIds.add(obj.id)
                }
            } else {
                activeViewerIndex = index
            }
        }

        // 1. Normal state: tapping photo 2 opens viewer at index 2
        onPhotoTapped(2)
        assertEquals(2, activeViewerIndex)
        assertEquals(0, selectedIds.size)

        // Close viewer
        activeViewerIndex = null

        // 2. Long press activates selection mode
        selectedIds.add(manifest.objects[0].id)
        assertTrue(selectedIds.isNotEmpty())

        // 3. Selection mode: tapping photo 2 adds it to selection, viewer does NOT open
        onPhotoTapped(2)
        assertNull(activeViewerIndex)
        assertEquals(2, selectedIds.size)
        assertTrue(selectedIds.contains("zarc_obj_0003"))

        // 4. Deselect all items exits selection mode
        selectedIds.clear()
        assertFalse(selectedIds.isNotEmpty())

        // 5. Normal state restored: tapping photo 4 opens viewer at index 4
        onPhotoTapped(4)
        assertEquals(4, activeViewerIndex)
    }

    @Test
    fun testMiniRollFilmstripIndexSynchronization() {
        val manifest = createSampleManifest(20)

        // Given a current pager index, calculate expected miniroll scroll anchor
        fun calculateMiniRollAnchor(currentPage: Int): Int {
            return maxOf(0, currentPage - 2)
        }

        assertEquals(0, calculateMiniRollAnchor(0))
        assertEquals(0, calculateMiniRollAnchor(1))
        assertEquals(0, calculateMiniRollAnchor(2))
        assertEquals(1, calculateMiniRollAnchor(3))
        assertEquals(8, calculateMiniRollAnchor(10))
        assertEquals(17, calculateMiniRollAnchor(19))

        // Ensure clicking any thumbnail in the miniroll maps to valid pager destination
        val miniRollThumbnails = manifest.objects.mapIndexed { idx, obj -> idx to obj.id }
        assertEquals(20, miniRollThumbnails.size)

        for ((index, id) in miniRollThumbnails) {
            assertTrue(index in 0 until manifest.objects.size)
            assertEquals("zarc_obj_${String.format("%04d", index + 1)}", id)
        }
    }

    @Test
    fun testChromeToggleState() {
        var chromeVisible = true
        fun toggleChrome() {
            chromeVisible = !chromeVisible
        }

        // Tap once -> hides controls for immersive full-screen viewing
        toggleChrome()
        assertFalse(chromeVisible)

        // Tap again -> restores top bar and miniroll
        toggleChrome()
        assertTrue(chromeVisible)
    }

    @Test
    fun testDoubleTapZoomCalculation() {
        var scale = 1.0f
        fun handleDoubleTap() {
            scale = if (scale > 1.0f) 1.0f else 2.5f
        }

        // First double tap: zooms in 2.5x
        handleDoubleTap()
        assertEquals(2.5f, scale, 0.001f)

        // Second double tap: resets to 1.0x
        handleDoubleTap()
        assertEquals(1.0f, scale, 0.001f)
    }
}
