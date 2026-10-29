package com.zarc.vault

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zarc.vault.archive.ArchivePhotoSource
import com.zarc.vault.archive.ArchiveRepository
import com.zarc.vault.gallery.GalleryRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device instrumented tests for Zarc Disguised Archive and Gallery Extraction.
 *
 * Verifies:
 * 1. Creation of genuine JPEG polyglot container with encrypted inner photos.
 * 2. Unauthenticated outer cover extraction.
 * 3. Authenticated manifest decryption and tamper resistance.
 * 4. In-memory thumbnail and full-resolution bitmap decoding for DecryptedPhotoViewer.
 * 5. Single item extraction integrity.
 */
@RunWith(AndroidJUnit4::class)
class ArchiveInstrumentedTest {

    private lateinit var context: Context
    private lateinit var gallery: GalleryRepository
    private lateinit var archiveRepo: ArchiveRepository
    private lateinit var tempArchiveFile: File

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        gallery = GalleryRepository(context)
        archiveRepo = ArchiveRepository(context)
        gallery.vault.ensureDirectories()
        tempArchiveFile = File(context.cacheDir, "test_polyglot_${System.currentTimeMillis()}.jpg")
    }

    @After
    fun tearDown() {
        if (tempArchiveFile.exists()) {
            tempArchiveFile.delete()
        }
        gallery.objectsDir.listFiles()?.forEach { it.delete() }
        gallery.vault.exportDir.listFiles()?.forEach { it.delete() }
    }

    @Test
    fun testPolyglotArchiveRoundtripAndGalleryViewerExtraction() {
        runBlocking {
            // 1. Import sample images into vault to use as sources
        val sampleIds = gallery.importSamples(3)
        assertEquals(3, sampleIds.size)
        val index = gallery.loadIndex()

        val sources = sampleIds.mapNotNull { id ->
            val item = index.find(id) ?: return@mapNotNull null
            val decryptedFile = gallery.decrypt(item, false)
            ArchivePhotoSource(
                displayName = item.name,
                sizeBytes = decryptedFile.length(),
                localFile = decryptedFile,
            )
        }
        assertEquals(3, sources.size)

        // 2. Package into a disguised polyglot archive
        val passphrase = "SecureDisguise2026!"
        val createResult = archiveRepo.createArchiveFromSources(
            outputFile = tempArchiveFile,
            sources = sources,
            coverIndex = 0,
            passphrase = passphrase,
            createAsPolyglotJpeg = true,
        )
        assertTrue("Archive creation must succeed", createResult.isSuccess)
        assertTrue("Archive file must exist on disk", tempArchiveFile.exists())
        assertTrue("Archive file must have non-zero length", tempArchiveFile.length() > 0)

        // 3. Inspect outer cover WITHOUT passphrase (gallery/cloud backup compatibility)
        val coverResult = archiveRepo.readOuterCover(tempArchiveFile)
        assertTrue("Cover reading must succeed without password", coverResult.isSuccess)
        val coverBytes = coverResult.getOrNull()
        assertNotNull("Cover bytes must not be null", coverBytes)
        assertTrue("Cover must be valid JPEG SOI", coverBytes!!.size > 4 && coverBytes[0] == 0xFF.toByte() && coverBytes[1] == 0xD8.toByte())

        // 4. Authenticate and read manifest
        val wrongAuth = archiveRepo.readManifest(tempArchiveFile, "WrongPassword!")
        assertTrue("Authentication with incorrect password must fail", wrongAuth.isFailure)

        val manifestResult = archiveRepo.readManifest(tempArchiveFile, passphrase)
        assertTrue("Manifest reading with correct password must succeed", manifestResult.isSuccess)
        val manifest = manifestResult.getOrNull()!!
        assertEquals("Manifest must contain 3 objects", 3, manifest.objects.size)
        assertTrue("Cover object must be designated", manifest.coverObjectId != null)

        // 5. In-memory thumbnail extraction for DecryptedGalleryViewer grid & miniroll
        for (obj in manifest.objects) {
            val thumbResult = archiveRepo.extractEntryToBitmap(tempArchiveFile, passphrase, obj.id, maxDimension = 320)
            assertTrue("Thumbnail bitmap extraction must succeed for ${obj.originalName}", thumbResult.isSuccess)
            val bmp = thumbResult.getOrNull()
            assertNotNull("Thumbnail bitmap must not be null", bmp)
            assertTrue("Thumbnail width must be > 0", bmp!!.width > 0)
            assertTrue("Thumbnail height must be > 0", bmp.height > 0)
        }

        // 6. High-res bitmap extraction for DecryptedPhotoViewer full-screen pager
        val firstObj = manifest.objects.first()
        val fullBmpResult = archiveRepo.extractEntryToBitmap(tempArchiveFile, passphrase, firstObj.id, maxDimension = 2048)
        assertTrue("Full-res bitmap extraction must succeed", fullBmpResult.isSuccess)
        val fullBmp = fullBmpResult.getOrNull()
        assertNotNull("Full bitmap must not be null", fullBmp)
        assertTrue("Full bitmap width must be > 0", fullBmp!!.width > 0)

        // 7. Single photo extraction to temporary export file
        val singleExportFile = File(context.cacheDir, "extracted_${firstObj.originalName}")
        val singleExtractRes = archiveRepo.extractSingleEntry(tempArchiveFile, passphrase, firstObj.id, singleExportFile)
        assertTrue("Single extract must succeed", singleExtractRes.isSuccess)
        assertTrue("Extracted file must exist", singleExportFile.exists())
        assertEquals("Extracted file length must equal sanitized size", firstObj.sanitizedSize, singleExportFile.length())
        singleExportFile.delete()
        }
    }
}
