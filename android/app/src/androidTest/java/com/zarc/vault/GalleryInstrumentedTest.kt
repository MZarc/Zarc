package com.zarc.vault

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zarc.vault.core.ZarcNative
import com.zarc.vault.gallery.Album
import com.zarc.vault.gallery.GalleryRepository
import com.zarc.vault.gallery.VaultIndex
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device tests for the gallery layer.
 *
 * The gallery must never weaken the engine: the media stays encrypted, the
 * index and the thumbnails are sealed, and the recycle bin only moves
 * encrypted files around.
 */
@RunWith(AndroidJUnit4::class)
class GalleryInstrumentedTest {

    private lateinit var context: Context
    private lateinit var gallery: GalleryRepository

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        gallery = GalleryRepository(context)
        gallery.vault.ensureDirectories()
    }

    @After
    fun tearDown() {
        // Every test starts from an empty vault: the objects, the sealed index
        // and the sealed thumbnails are removed.
        gallery.objectsDir.listFiles()?.forEach { it.delete() }
        gallery.vault.exportDir.listFiles()?.forEach { it.delete() }
        val root = gallery.objectsDir.parentFile
        java.io.File(root, "index.bin").delete()
        java.io.File(root, "thumbs").deleteRecursively()
        java.io.File(root, "bin").deleteRecursively()
    }

    @Test
    fun theImportedMediaIsEncryptedAndTheIndexIsSealed() {
        val ids = gallery.importSamples(3)
        assertEquals(3, ids.size)

        // The index holds the items ...
        val index = gallery.loadIndex()
        assertEquals(3, index.visible.size)
        assertTrue(index.visible.all { it.name.endsWith(".jpg") })

        // ... and it is sealed on the disk.
        val indexFile = java.io.File(gallery.objectsDir.parentFile, "index.bin")
        assertTrue(indexFile.exists())
        val raw = indexFile.readBytes()
        assertFalse(
            "the index must not be readable in the clear",
            String(raw, Charsets.ISO_8859_1).contains("ZARC_0001"),
        )

        // The stored object is not a readable image either.
        val stored = java.io.File(gallery.objectsDir, ids.first()).readBytes()
        assertFalse(
            "the object must not contain a JPEG header",
            stored.size > 3 && stored[0] == 0xFF.toByte() && stored[1] == 0xD8.toByte(),
        )
    }

    @Test
    fun theThumbnailsAreSealedAndDecryptInMemory() {
        val ids = gallery.importSamples(2)
        val thumbFile = java.io.File(
            java.io.File(gallery.objectsDir.parentFile, "thumbs"),
            "${ids.first()}.blob",
        )
        assertTrue(thumbFile.exists())
        val raw = thumbFile.readBytes()
        assertFalse(
            "the thumbnail must not be a readable JPEG",
            raw.size > 3 && raw[0] == 0xFF.toByte() && raw[1] == 0xD8.toByte(),
        )

        val decrypted = gallery.readThumbnail(false, ids.first())
        assertTrue("the thumbnail must decrypt", decrypted != null && decrypted.size > 100)
    }

    @Test
    fun theMediaDecryptsBackToTheOriginal() {
        val ids = gallery.importSamples(1)
        val item = gallery.loadIndex().find(ids.first())!!
        val file = gallery.decrypt(item, false)
        assertTrue(file.exists())
        assertTrue(file.length() > 0)
    }

    @Test
    fun theRecycleBinMovesTheEncryptedObjectAndRestoresIt() {
        val ids = gallery.importSamples(1)
        val id = ids.first()

        gallery.moveToBin(listOf(id))
        val binned = gallery.loadIndex().find(id)!!
        assertTrue(binned.isDeleted)
        assertTrue(java.io.File(gallery.objectsDir, id).exists() == false)

        gallery.restoreFromBin(listOf(id))
        val restored = gallery.loadIndex().find(id)!!
        assertFalse(restored.isDeleted)
        assertTrue(java.io.File(gallery.objectsDir, id).exists())
    }

    @Test
    fun theAlbumsWork() {
        gallery.importSamples(2)
        val album = gallery.createAlbum("Holiday")
        assertEquals("Holiday", album.name)

        val ids = gallery.loadIndex().visible.map { it.id }
        gallery.moveToAlbum(ids, album.id)
        assertEquals(2, gallery.loadIndex().inAlbum(album.id).size)

        gallery.renameAlbum(album.id, "Holiday 2026")
        assertEquals("Holiday 2026", gallery.loadIndex().albums.first { it.id == album.id }.name)

        gallery.deleteAlbum(album.id)
        assertTrue(gallery.loadIndex().albums.none { it.id == album.id })
        assertTrue(gallery.loadIndex().visible.none { it.albumId == album.id })
    }

    @Test
    fun thePrivateSubkeyIsSeparateFromTheVaultKey() {
        val vmk = gallery.vault.vaultMasterKey()
        val subkey = ZarcNative.nativeDeriveSubkey(vmk, "zarc.private.v1")
        val other = ZarcNative.nativeDeriveSubkey(vmk, "zarc.other.v1")
        try {
            assertNotEquals(
                "the private subkey must differ from the vault key",
                String(vmk, Charsets.ISO_8859_1),
                String(subkey, Charsets.ISO_8859_1),
            )
            assertNotEquals(
                "two different purposes must produce two different keys",
                String(subkey, Charsets.ISO_8859_1),
                String(other, Charsets.ISO_8859_1),
            )
        } finally {
            vmk.fill(0)
            subkey.fill(0)
            other.fill(0)
        }
    }

    @Test
    fun theBlobSealRoundTrips() {
        val vmk = gallery.vault.vaultMasterKey()
        val secret = "the quick brown fox".toByteArray()
        try {
            val sealed = ZarcNative.nativeSealBlob(vmk, secret)
            assertFalse(
                "the sealed blob must not contain the plaintext",
                String(sealed, Charsets.ISO_8859_1).contains("quick brown fox"),
            )
            val opened = ZarcNative.nativeOpenBlob(vmk, sealed)
            assertEquals(String(secret), String(opened))

            // A wrong key does not open it.
            val wrong = ZarcNative.nativeDeriveSubkey(vmk, "zarc.wrong.v1")
            try {
                var failed = false
                try {
                    ZarcNative.nativeOpenBlob(wrong, sealed)
                } catch (error: Exception) {
                    failed = true
                }
                assertTrue("a wrong key must not open the blob", failed)
            } finally {
                wrong.fill(0)
            }
        } finally {
            vmk.fill(0)
            secret.fill(0)
        }
    }

    @Test
    fun theFavouriteAlbumExistsOnAFreshVault() {
        val index = gallery.loadIndex()
        assertEquals(2, index.albums.size)
        assertTrue(index.albums.any { it.id == VaultIndex.ALBUM_RECENTS })
        assertTrue(index.albums.any { it.id == VaultIndex.ALBUM_FAVOURITES })
    }

    @Test
    fun theFavouriteAlbumMarksAndReleasesItems() {
        val id = gallery.importSamples(1).first()

        gallery.setFavorite(listOf(id), true)
        val favourite = gallery.loadIndex().find(id)!!
        assertTrue(favourite.isFavorite)
        assertEquals(VaultIndex.ALBUM_FAVOURITES, favourite.albumId)
        assertEquals(1, gallery.loadIndex().inAlbum(VaultIndex.ALBUM_FAVOURITES).size)

        gallery.setFavorite(listOf(id), false)
        val released = gallery.loadIndex().find(id)!!
        assertFalse(released.isFavorite)
        assertEquals(VaultIndex.ALBUM_NONE, released.albumId)
        assertTrue(gallery.loadIndex().inAlbum(VaultIndex.ALBUM_FAVOURITES).isEmpty())
    }

    @Test
    fun renamingKeepsTheExtensionAndNumbersABatch() {
        val ids = gallery.importSamples(2)

        gallery.renameItems(ids.take(1), "Trip.jpg")
        assertEquals("Trip.jpg", gallery.loadIndex().find(ids.first())!!.name)

        gallery.renameItems(ids, "Day.jpg")
        val names = gallery.loadIndex().items.map { it.name }
        assertTrue(names.contains("Day 1.jpg"))
        assertTrue(names.contains("Day 2.jpg"))
    }

    @Test
    fun theCaptureDateCanBeChanged() {
        val id = gallery.importSamples(1).first()
        val timestamp = 1_700_000_000_000L
        gallery.setTakenAt(listOf(id), timestamp)
        assertEquals(timestamp, gallery.loadIndex().find(id)!!.takenAt)
    }

    @Test
    fun theLegacySystemAlbumsAreMigratedToTheRoll() {
        gallery.importSamples(1)
        val legacy = gallery.loadIndex()
        legacy.albums.add(Album("camera", "Camera", system = true))
        legacy.items.replaceAll { it.copy(albumId = "camera") }
        gallery.saveIndex(legacy)

        val migrated = gallery.loadIndex()
        assertEquals(2, migrated.albums.size)
        assertTrue(migrated.albums.any { it.id == VaultIndex.ALBUM_RECENTS })
        assertTrue(migrated.albums.any { it.id == VaultIndex.ALBUM_FAVOURITES })
        assertEquals(VaultIndex.ALBUM_NONE, migrated.items.first().albumId)
    }
}
