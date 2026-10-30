package com.zarc.vault.gallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.zarc.vault.core.VaultRepository
import com.zarc.vault.core.ZarcNative
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The gallery's view of the vault.
 *
 * Everything the user interface shows - the items, the albums, the thumbnails
 * and the private area - is derived from *encrypted* material:
 *
 * * the media itself is an encrypted vault object,
 * * the metadata index is sealed with the vault key,
 * * the thumbnails are sealed with the vault key,
 * * the private area uses a subkey that is itself wrapped by a Keystore key
 *   that requires a biometric authentication.
 *
 * The repository never returns plaintext paths of the originals: it returns
 * vault object ids.
 */
class GalleryRepository(private val context: Context) {

    /** The engine facing repository. */
    val vault = VaultRepository(context)

    /** The vault objects of the normal area. */
    val objectsDir: File get() = vault.objectsDir

    /** The vault objects of the private area. */
    private val privateObjectsDir: File get() = File(vault.objectsDir.parentFile, "private/objects")

    /** The recycle bin. */
    private val binDir: File get() = File(vault.objectsDir.parentFile, "bin")

    /** The sealed thumbnails of the normal area. */
    private val thumbsDir: File get() = File(vault.objectsDir.parentFile, "thumbs")

    /** The sealed thumbnails of the private area. */
    private val privateThumbsDir: File get() = File(vault.objectsDir.parentFile, "private/thumbs")

    /** The sealed index of the normal area. */
    private val indexFile: File get() = File(vault.objectsDir.parentFile, "index.bin")

    /** The sealed index of the private area. */
    private val privateIndexFile: File get() = File(vault.objectsDir.parentFile, "private/index.bin")

    /** The wrapped private key, released by a biometric authentication. */
    private val privateGateFile: File get() = File(vault.objectsDir.parentFile, "private/gate.bin")

    /** Transient decrypted files used by the viewer and the video player. */
    private val viewerDir: File get() = File(context.cacheDir, "viewer")

    /** Marker that records an unfinished legacy private-area migration. */
    private val privateMigrationMarker: File get() = File(vault.objectsDir.parentFile, "private/migrating")

    /** A failed index may still be displayed, but it must never be overwritten. */
    @Volatile
    private var normalIndexUnavailable = false

    @Volatile
    private var privateIndexUnavailable = false

    // ----------------------------------------------------------------- index

    /** Load the index of the normal area. */
    fun loadIndex(): VaultIndex {
        val key = vault.vaultMasterKey()
        return try {
            loadIndex(indexFile, key)
        } finally {
            key.fill(0)
        }
    }

    /** Save the index of the normal area. */
    fun saveIndex(index: VaultIndex) {
        val key = vault.vaultMasterKey()
        try {
            saveIndex(indexFile, key, index)
        } finally {
            key.fill(0)
        }
    }

    private fun loadIndex(file: File, key: ByteArray): VaultIndex {
        if (!file.exists()) return emptyIndex()
        return try {
            val plain = ZarcNative.nativeOpenBlob(key, file.readBytes())
            val index = VaultIndex.fromJson(plain).normalised()
            if (file == indexFile) normalIndexUnavailable = false
            if (file == privateIndexFile) privateIndexUnavailable = false
            index
        } catch (error: Exception) {
            // A damaged index must not take the whole gallery down: the media
            // is still in the vault, only the metadata is lost. Keep a copy of
            // the unreadable sealed index and refuse to overwrite it later.
            android.util.Log.e("GalleryRepo", "the sealed index could not be opened", error)
            quarantineCorruptIndex(file)
            if (file == indexFile) normalIndexUnavailable = true
            if (file == privateIndexFile) privateIndexUnavailable = true
            emptyIndex()
        }
    }

    private fun quarantineCorruptIndex(file: File) {
        val quarantine = File(file.parentFile, "${file.name}.corrupt")
        if (quarantine.exists()) return
        runCatching { file.copyTo(quarantine, overwrite = false) }
    }

    private fun emptyIndex(): VaultIndex =
        VaultIndex(albums = VaultIndex.DEFAULT_ALBUMS.toMutableList())

    /**
     * Write a sealed index without ever replacing a valid index with a partial
     * one. The bytes are flushed to a temporary file first and the rename is
     * atomic on the app's private storage.
     */
    @Synchronized
    private fun saveIndex(file: File, key: ByteArray, index: VaultIndex) {
        if (file == indexFile && normalIndexUnavailable) {
            throw IOException(
                "the gallery index is unreadable; a preserved copy is available " +
                    "and the live index will not be overwritten",
            )
        }
        if (file == privateIndexFile && privateIndexUnavailable) {
            throw IOException(
                "the private index is unreadable; a preserved copy is available " +
                    "and the live index will not be overwritten",
            )
        }
        val parent = file.parentFile
            ?: throw IOException("the index has no parent directory")
        parent.mkdirs()
        val sealed = ZarcNative.nativeSealBlob(key, index.toJson())
        val temporary = File.createTempFile("${file.name}.", ".tmp", parent)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(sealed)
                output.fd.sync()
            }
            if (!temporary.renameTo(file)) {
                throw IOException("the sealed index could not be committed")
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    // --------------------------------------------------------------- import

    /**
     * Import a document into the vault.
     *
     * The document is copied into app private storage, encrypted, and a
     * thumbnail is generated and sealed. The original is never modified.
     *
     * A private import requires an unlocked private area; falling back to the
     * vault master key would seal the private index with the wrong key.
     *
     * @return the id of the new vault object.
     */
    @Synchronized
    fun import(uri: Uri, albumId: String, isPrivate: Boolean = false): String {
        if (isPrivate && unlockedPrivateKey == null) {
            throw IllegalStateException("unlock the private area before importing into it")
        }
        val staged = File.createTempFile("import-", ".tmp", vault.workDir)
        var objectId: String? = null
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                staged.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IllegalArgumentException("the document could not be opened")

            val name = displayName(uri, staged.name)
            val mime = context.contentResolver.getType(uri) ?: mimeOf(name)
            val taken = takenAt(uri, staged)
            val (width, height, duration) = mediaProperties(staged, mime)

            val key = if (isPrivate) unlockedPrivateKey!! else vault.vaultMasterKey()
            try {
                val id = ZarcNative.nativeEncryptFile(
                    key,
                    staged.absolutePath,
                    objectsDirOf(isPrivate).absolutePath,
                )
                objectId = id
                val item = MediaItem(
                    id = id,
                    name = name,
                    mime = mime,
                    size = File(objectsDirOf(isPrivate), id).length(),
                    takenAt = taken,
                    durationMs = duration,
                    width = width,
                    height = height,
                    albumId = albumId,
                    isPrivate = isPrivate,
                )
                // The thumbnail is generated from the staged copy, so it has
                // to happen before the staged file is removed.
                writeThumbnail(isPrivate, item, staged)

                val index = if (isPrivate) loadPrivateIndex(key) else loadIndex()
                index.items.add(item)
                if (isPrivate) savePrivateIndex(key, index) else saveIndex(index)
                return id
            } finally {
                if (!isPrivate) key.fill(0)
            }
        } catch (error: Exception) {
            // An object that never made it into an index must not stay behind.
            objectId?.let { id ->
                File(objectsDirOf(isPrivate), id).delete()
                val thumbs = if (isPrivate) privateThumbsDir else thumbsDir
                File(thumbs, "$id.blob").delete()
            }
            throw error
        } finally {
            staged.delete()
        }
    }

    /**
     * Generate synthetic sample media and import it.
     *
     * This is a developer convenience: it gives the user interface something
     * to show on a fresh installation.
     */
    @Synchronized
    fun importSamples(count: Int): List<String> {
        val ids = mutableListOf<String>()
        val index = loadIndex()
        try {
            repeat(count) { position ->
                val bitmap = Bitmap.createBitmap(1080, 1440, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bitmap)
                val hue = (position * 37) % 360
                canvas.drawColor(android.graphics.Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.45f, 0.55f)))
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                paint.color = android.graphics.Color.WHITE
                paint.textSize = 96f
                canvas.drawText("ZARC ${position + 1}", 120f, 720f, paint)
                paint.textSize = 48f
                canvas.drawText("synthetic sample", 120f, 810f, paint)

                val staged = File.createTempFile("sample-", ".jpg", vault.workDir)
                try {
                    staged.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                } finally {
                    bitmap.recycle()
                }

                val vmk = vault.vaultMasterKey()
                val objectId = try {
                    ZarcNative.nativeEncryptFile(
                        vmk,
                        staged.absolutePath,
                        objectsDir.absolutePath,
                    )
                } finally {
                    vmk.fill(0)
                }
                ids.add(objectId)
                val item = MediaItem(
                    id = objectId,
                    name = "ZARC_${String.format(Locale.US, "%04d", position + 1)}.jpg",
                    mime = "image/jpeg",
                    size = File(objectsDir, objectId).length(),
                    takenAt = System.currentTimeMillis() - position * 3_600_000L,
                    durationMs = 0,
                    width = 1080,
                    height = 1440,
                    albumId = VaultIndex.ALBUM_NONE,
                )
                writeThumbnail(false, item, staged)
                staged.delete()
                index.items.add(item)
            }
            saveIndex(index)
            return ids
        } catch (error: Exception) {
            ids.forEach { id ->
                File(objectsDir, id).delete()
                File(thumbsDir, "$id.blob").delete()
            }
            throw error
        }
    }

    // ------------------------------------------------------------- albums

    /** Create a new album. */
    @Synchronized
    fun createAlbum(name: String): Album {
        val index = loadIndex()
        val album = Album(
            id = "album-" + Integer.toHexString(name.hashCode()) + "-" + System.currentTimeMillis(),
            name = name,
        )
        index.albums.add(album)
        saveIndex(index)
        return album
    }

    /** Rename an album. */
    @Synchronized
    fun setAlbumCover(albumId: String, mediaId: String?) {
        val index = loadIndex()
        val pos = index.albums.indexOfFirst { it.id == albumId }
        if (pos >= 0) {
            index.albums[pos] = index.albums[pos].copy(coverId = mediaId)
            saveIndex(index)
        }
    }

    @Synchronized
    fun renameAlbum(albumId: String, name: String) {
        val index = loadIndex()
        index.albums.replaceAll { album -> if (album.id == albumId) album.copy(name = name) else album }
        saveIndex(index)
    }

    /**
     * Move items into an album.
     *
     * Moving into the favourite album marks the item as a favourite; moving
     * out of it clears the flag. The favourite album is a normal album with a
     * reserved id, so sorting, filtering and search keep working unchanged.
     */
    @Synchronized
    fun moveToAlbum(ids: List<String>, albumId: String) {
        val index = loadIndex()
        index.items.replaceAll { item ->
            if (item.id in ids) {
                item.copy(
                    albumId = albumId,
                    isFavorite = albumId == VaultIndex.ALBUM_FAVOURITES,
                )
            } else {
                item
            }
        }
        saveIndex(index)
    }

    /** Delete an album. Its items return to the roll. */
    @Synchronized
    fun deleteAlbum(albumId: String) {
        if (albumId == VaultIndex.ALBUM_FAVOURITES) return
        val index = loadIndex()
        index.albums.removeAll { it.id == albumId && !it.system }
        index.items.replaceAll { item ->
            if (item.albumId == albumId) item.copy(albumId = VaultIndex.ALBUM_NONE) else item
        }
        saveIndex(index)
    }

    /** Load the index of an area, private or normal. */
    private fun loadAreaIndex(isPrivate: Boolean): VaultIndex {
        if (!isPrivate) return loadIndex()
        val key = unlockedPrivateKey ?: return VaultIndex()
        return loadPrivateIndex(key)
    }

    /** Save the index of an area, private or normal. */
    private fun saveAreaIndex(isPrivate: Boolean, index: VaultIndex) {
        if (!isPrivate) {
            saveIndex(index)
        } else {
            val key = unlockedPrivateKey ?: return
            savePrivateIndex(key, index)
        }
    }

    /** Rename a set of items while preserving their file extensions. */
    @Synchronized
    fun renameItems(
        ids: Collection<String>,
        requestedName: String,
        isPrivate: Boolean = false,
    ) {
        val index = loadAreaIndex(isPrivate)
        val targets = index.items.filter { it.id in ids }
        if (targets.isEmpty()) return
        val root = requestedName.substringBeforeLast('.', requestedName).trim()
        if (root.isEmpty()) return
        val explicitExtension = requestedName.substringAfterLast('.', "")
        val positions = index.items.withIndex().associate { (position, item) -> item.id to position }
        targets.forEachIndexed { position, item ->
            val extension = explicitExtension.ifEmpty { item.extension }
            val suffix = if (targets.size > 1) " ${position + 1}" else ""
            val name = buildString {
                append(root)
                append(suffix)
                if (extension.isNotEmpty()) append('.').append(extension)
            }
            val target = positions[item.id] ?: return@forEachIndexed
            index.items[target] = item.copy(name = name)
        }
        saveAreaIndex(isPrivate, index)
    }

    /** Change the capture time of a set of items. */
    @Synchronized
    fun setTakenAt(
        ids: Collection<String>,
        timestamp: Long,
        isPrivate: Boolean = false,
    ) {
        val index = loadAreaIndex(isPrivate)
        index.items.replaceAll { item ->
            if (item.id in ids) item.copy(takenAt = timestamp) else item
        }
        saveAreaIndex(isPrivate, index)
    }

    /** Add or remove a set of items from the favourite album. */
    @Synchronized
    fun setFavorite(ids: Collection<String>, favorite: Boolean) {
        val index = loadIndex()
        index.albums.removeAll { it.id == VaultIndex.ALBUM_FAVOURITES }
        index.albums.add(0, VaultIndex.FAVOURITE_ALBUM)
        index.items.replaceAll { item ->
            if (item.id in ids) {
                item.copy(
                    isFavorite = favorite,
                    albumId = when {
                        favorite -> VaultIndex.ALBUM_FAVOURITES
                        item.albumId == VaultIndex.ALBUM_FAVOURITES -> VaultIndex.ALBUM_NONE
                        else -> item.albumId
                    },
                )
            } else {
                item
            }
        }
        saveIndex(index)
    }

    // --------------------------------------------------------- recycle bin

    /**
     * Move a ciphertext file, reporting whether the move really happened.
     *
     * A missing source with an existing target is treated as already moved, so
     * a retried operation is idempotent.
     */
    private fun moveCiphertext(source: File, target: File): Boolean {
        if (!source.exists()) return target.exists()
        if (target.exists()) return false
        target.parentFile?.mkdirs()
        return source.renameTo(target)
    }

    /** Move items into the recycle bin. */
    @Synchronized
    fun moveToBin(ids: List<String>) {
        binDir.mkdirs()
        val index = loadIndex()
        val now = System.currentTimeMillis()
        val moved = mutableSetOf<String>()
        ids.forEach { id ->
            val source = File(objectsDir, id)
            if (!source.exists()) return@forEach
            val target = File(binDir, id)
            if (!moveCiphertext(source, target)) {
                android.util.Log.e("GalleryRepo", "could not move $id into the recycle bin")
                return@forEach
            }
            val thumb = File(thumbsDir, "$id.blob")
            val thumbTarget = File(binDir, "$id.blob")
            if (thumb.exists() && !moveCiphertext(thumb, thumbTarget)) {
                // Roll the object back so the index never points at a
                // half-moved item.
                moveCiphertext(target, source)
                android.util.Log.e("GalleryRepo", "could not move the thumbnail of $id")
                return@forEach
            }
            moved.add(id)
        }
        if (moved.isEmpty()) return
        index.items.replaceAll { item ->
            if (item.id in moved) item.copy(isDeleted = true, deletedAt = now) else item
        }
        try {
            saveIndex(index)
        } catch (error: Exception) {
            // The index still describes the old location, so put the files
            // back before reporting the failure.
            moved.forEach { id ->
                moveCiphertext(File(binDir, id), File(objectsDir, id))
                moveCiphertext(File(binDir, "$id.blob"), File(thumbsDir, "$id.blob"))
            }
            throw error
        }
    }

    /** Restore items from the recycle bin. */
    @Synchronized
    fun restoreFromBin(ids: List<String>) {
        binDir.mkdirs()
        val index = loadIndex()
        val moved = mutableSetOf<String>()
        ids.forEach { id ->
            val source = File(binDir, id)
            if (!source.exists()) return@forEach
            val target = File(objectsDir, id)
            if (!moveCiphertext(source, target)) {
                android.util.Log.e("GalleryRepo", "could not restore $id from the recycle bin")
                return@forEach
            }
            val thumb = File(binDir, "$id.blob")
            val thumbTarget = File(thumbsDir, "$id.blob")
            if (thumb.exists() && !moveCiphertext(thumb, thumbTarget)) {
                moveCiphertext(target, source)
                android.util.Log.e("GalleryRepo", "could not restore the thumbnail of $id")
                return@forEach
            }
            moved.add(id)
        }
        if (moved.isEmpty()) return
        index.items.replaceAll { item ->
            if (item.id in moved) item.copy(isDeleted = false, deletedAt = 0L) else item
        }
        try {
            saveIndex(index)
        } catch (error: Exception) {
            moved.forEach { id ->
                moveCiphertext(File(objectsDir, id), File(binDir, id))
                moveCiphertext(File(thumbsDir, "$id.blob"), File(binDir, "$id.blob"))
            }
            throw error
        }
    }

    /** Delete items permanently. */
    @Synchronized
    fun deleteForever(ids: List<String>) {
        val index = loadIndex()
        val existing = ids.filter { id -> index.find(id) != null }.toSet()
        if (existing.isEmpty()) return
        index.items.removeAll { it.id in existing }
        // Commit the metadata first. If this fails, the ciphertext is still
        // there and the item remains reachable; deleting files first would
        // leave an index that points at nothing.
        saveIndex(index)
        existing.forEach { id ->
            File(binDir, id).delete()
            File(binDir, "$id.blob").delete()
            File(objectsDir, id).delete()
            File(thumbsDir, "$id.blob").delete()
        }
    }

    /**
     * Remove expired binned items from one already-loaded index.
     *
     * The metadata is saved before any ciphertext is deleted.
     */
    private fun purgeExpired(index: VaultIndex): Int {
        val now = System.currentTimeMillis()
        val expired = index.items.filter {
            it.isDeleted && it.deletedAt > 0L && (now - it.deletedAt >= BIN_EXPIRATION_MILLIS)
        }
        if (expired.isEmpty()) return 0
        val expiredIds = expired.map { it.id }.toSet()
        index.items.removeAll { it.id in expiredIds }
        saveIndex(index)
        expiredIds.forEach { id ->
            File(binDir, id).delete()
            File(binDir, "$id.blob").delete()
            File(objectsDir, id).delete()
            File(thumbsDir, "$id.blob").delete()
        }
        return expired.size
    }

    /**
     * Permanently delete items that have been in the recycle bin for 30 or more days.
     * Returns the count of purged items.
     */
    @Synchronized
    fun purgeExpiredBinnedItems(): Int = purgeExpired(loadIndex())

    /** Load the index once and purge expired binned items from that copy. */
    @Synchronized
    fun loadIndexAndPurgeExpired(): VaultIndex {
        val index = loadIndex()
        purgeExpired(index)
        return index
    }

    /** Empty the recycle bin. */
    @Synchronized
    fun emptyBin() = deleteForever(loadIndex().deleted.map { it.id })

    // ------------------------------------------------------------ private

    /**
     * Save the private gate blob.
     *
     * The blob was already wrapped by the authentication-bound Keystore key;
     * this method only commits it atomically.
     */
    @Synchronized
    fun savePrivateGate(wrapped: ByteArray) {
        privateGateFile.parentFile?.mkdirs()
        writeFileAtomically(privateGateFile, wrapped)
    }

    /** Generate the independent random subkey of the private area. */
    fun newPrivateSubkey(): ByteArray =
        ByteArray(32).also { SecureRandom().nextBytes(it) }

    /** Whether the private gate still uses the deterministic legacy subkey. */
    fun hasLegacyPrivateGate(): Boolean {
        if (!privateGateFile.isFile) return false
        return runCatching { PrivateGate.isLegacy(privateGateFile.readBytes()) }.getOrDefault(true)
    }

    /** Whether private data exists even if the gate blob is missing. */
    fun hasPrivateData(): Boolean =
        privateIndexFile.isFile ||
            listOf(privateObjectsDir, privateBinDir, privateThumbsDir).any { directory ->
                directory.listFiles()?.isNotEmpty() == true
            }

    /** The deterministic subkey used by private areas created before v2. */
    fun legacyPrivateSubkey(): ByteArray {
        val vmk = vault.vaultMasterKey()
        return try {
            ZarcNative.nativeDeriveSubkey(vmk, "zarc.private.v1")
        } finally {
            vmk.fill(0)
        }
    }

    /** Record that legacy private data still has to be migrated. */
    @Synchronized
    fun markPrivateMigration() {
        privateMigrationMarker.parentFile?.mkdirs()
        privateMigrationMarker.writeText("1")
    }

    /** Whether an unfinished legacy private-area migration exists. */
    fun hasPendingPrivateMigration(): Boolean = privateMigrationMarker.exists()

    /**
     * Re-encrypt every legacy private artifact under the independent [newKey].
     *
     * The operation is idempotent. The marker is only removed after every
     * object, the private index and the thumbnails have been migrated, so an
     * interrupted migration is simply retried on the next unlock.
     */
    @Synchronized
    fun finishPrivateMigration(newKey: ByteArray) {
        if (!privateMigrationMarker.exists()) return
        val oldKey = legacyPrivateSubkey()
        try {
            ZarcNative.nativeRotateMasterKey(oldKey, newKey, privateObjectsDir.absolutePath)
            ZarcNative.nativeRotateMasterKey(oldKey, newKey, privateBinDir.absolutePath)
            resealBlobFile(privateIndexFile, oldKey, newKey)
            resealBlobTree(privateThumbsDir, oldKey, newKey)
            resealBlobTree(privateBinDir, oldKey, newKey)
            privateMigrationMarker.delete()
        } finally {
            oldKey.fill(0)
        }
    }

    /** Whether the private area has been set up. */
    fun hasPrivateArea(): Boolean = privateGateFile.exists()

    /** The private index, using the unlocked private key. */
    @Synchronized
    fun loadPrivateIndex(key: ByteArray): VaultIndex = loadIndex(privateIndexFile, key)

    /** Save the private index. */
    @Synchronized
    fun savePrivateIndex(key: ByteArray, index: VaultIndex) = saveIndex(privateIndexFile, key, index)

    /** The private key, if it has been unlocked. */
    @Volatile
    var unlockedPrivateKey: ByteArray? = null

    /** Re-seal one metadata blob under the new key, if it still uses the old one. */
    private fun resealBlobFile(file: File, oldKey: ByteArray, newKey: ByteArray): Boolean {
        if (!file.isFile) return false
        val sealed = runCatching { file.readBytes() }.getOrElse { return false }
        val alreadyNew = runCatching { ZarcNative.nativeOpenBlob(newKey, sealed) }
        if (alreadyNew.isSuccess) {
            alreadyNew.getOrNull()?.fill(0)
            return false
        }
        val plain = runCatching { ZarcNative.nativeOpenBlob(oldKey, sealed) }.getOrElse {
            android.util.Log.e("GalleryRepo", "a private blob could not be re-sealed: ${file.name}", it)
            return false
        }
        try {
            writeFileAtomically(file, ZarcNative.nativeSealBlob(newKey, plain))
            return true
        } finally {
            plain.fill(0)
        }
    }

    /** Re-seal every `.blob` file in a private metadata directory. */
    private fun resealBlobTree(directory: File, oldKey: ByteArray, newKey: ByteArray) {
        val files = directory.listFiles() ?: return
        for (file in files) {
            if (file.isDirectory) {
                resealBlobTree(file, oldKey, newKey)
            } else if (file.isFile && file.name.endsWith(".blob")) {
                resealBlobFile(file, oldKey, newKey)
            }
        }
    }

    /** The directory that holds the objects of an area. */
    /** The directory that holds the objects of an area or item. */
    fun objectsDirOf(item: MediaItem): File =
        if (item.isDeleted) {
            if (item.isPrivate) privateBinDir else binDir
        } else if (item.isPrivate) {
            privateObjectsDir
        } else {
            objectsDir
        }

    private fun objectsDirOf(isPrivate: Boolean): File =
        if (isPrivate) privateObjectsDir else objectsDir

    // ---------------------------------------------------------- thumbnails

    /** Write the sealed thumbnail of an item. */
    private fun writeThumbnail(isPrivate: Boolean, item: MediaItem, source: File) {
        val bitmap = decodeScaled(source, 320) ?: return
        val bytes = java.io.ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 82, output)
            output.toByteArray()
        }
        bitmap.recycle()
        val key = if (isPrivate) unlockedPrivateKey ?: return else vault.vaultMasterKey()
        try {
            val sealed = ZarcNative.nativeSealBlob(key, bytes)
            val directory = if (isPrivate) privateThumbsDir else thumbsDir
            directory.mkdirs()
            File(directory, "${item.id}.blob").writeBytes(sealed)
        } finally {
            if (!isPrivate) key.fill(0)
        }
    }

    /** Read the sealed thumbnail of an item. */
    fun readThumbnail(isPrivate: Boolean, id: String): ByteArray? {
        val dir = if (isPrivate) privateThumbsDir else thumbsDir
        var file = File(dir, "$id.blob")
        if (!file.exists()) {
            file = if (isPrivate) File(privateBinDir, "$id.blob") else File(binDir, "$id.blob")
        }
        if (!file.exists()) return null
        val key = if (isPrivate) unlockedPrivateKey ?: return null else vault.vaultMasterKey()
        return try {
            ZarcNative.nativeOpenBlob(key, file.readBytes())
        } catch (error: Exception) {
            null
        } finally {
            if (!isPrivate) key.fill(0)
        }
    }

    /**
     * Decrypt an image into a temporary file, decode it at a bounded
     * resolution and remove the temporary file again.
     *
     * The viewer needs more than the 320 px thumbnail to make pinch to zoom
     * useful. The plaintext file exists only for the duration of the decode.
     */
    fun decodeForViewer(item: MediaItem, maxDimension: Int): Bitmap? {
        viewerDir.mkdirs()
        val staged = File(viewerDir, "image-${item.id}.${item.extension.ifEmpty { "bin" }}")
        val key = if (item.isPrivate) {
            unlockedPrivateKey ?: return null
        } else {
            vault.vaultMasterKey()
        }
        return try {
            ZarcNative.nativeDecryptFile(
                key,
                item.id,
                objectsDirOf(item).absolutePath,
                viewerDir.absolutePath,
                staged.name,
            )
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(staged.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (
                bounds.outWidth / sample > maxDimension * 2 ||
                bounds.outHeight / sample > maxDimension * 2
            ) {
                sample *= 2
            }
            BitmapFactory.decodeFile(
                staged.absolutePath,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                },
            )
        } catch (error: Exception) {
            null
        } finally {
            if (!item.isPrivate) key.fill(0)
            staged.delete()
        }
    }

    /**
     * Decrypt a video into the cache for playback and return the file.
     *
     * The caller owns the file and must delete it when the player goes away.
     * The previous copy of the same object is replaced, so a stale playable
     * file never survives a newer decryption.
     */
    fun preparePlayback(item: MediaItem): File {
        viewerDir.mkdirs()
        val file = File(viewerDir, "playback-${item.id}.${item.extension.ifEmpty { "bin" }}")
        file.delete()
        val key = if (item.isPrivate) {
            unlockedPrivateKey ?: throw IllegalStateException("the private area is locked")
        } else {
            vault.vaultMasterKey()
        }
        try {
            ZarcNative.nativeDecryptFile(
                key,
                item.id,
                objectsDirOf(item).absolutePath,
                viewerDir.absolutePath,
                file.name,
            )
            return file
        } finally {
            if (!item.isPrivate) key.fill(0)
        }
    }

    /** Remove every transient decrypted file left behind by an older run. */
    fun clearViewerCache() {
        viewerDir.listFiles()?.forEach { it.delete() }
    }

    /** Remove plaintext files staged for in-app consumption. */
    fun purgeTransientExports() {
        vault.purgeTransientExports()
    }

    // ------------------------------------------------------------- helpers

    /**
     * Re-encrypt an item with a fresh data key.
     *
     * The Rust core keeps the object's identity and replaces the ciphertext
     * atomically, so the index entry stays valid and no orphan is created.
     */
    @Synchronized
    fun reencryptObject(item: MediaItem): String {
        val key = if (item.isPrivate) {
            unlockedPrivateKey ?: throw IllegalStateException("the private area is locked")
        } else {
            vault.vaultMasterKey()
        }
        try {
            return ZarcNative.nativeReencryptObject(
                key,
                item.id,
                objectsDirOf(item).absolutePath,
            )
        } finally {
            if (!item.isPrivate) key.fill(0)
        }
    }

    /** Decrypt an object into the transient export directory and return the file. */
    fun decrypt(item: MediaItem, isPrivate: Boolean): File {
        val key = if (isPrivate) {
            unlockedPrivateKey ?: throw IllegalStateException("the private area is locked")
        } else {
            vault.vaultMasterKey()
        }
        try {
            vault.exportDir.mkdirs()
            val path = ZarcNative.nativeDecryptFile(
                key,
                item.id,
                objectsDirOf(item).absolutePath,
                vault.exportDir.absolutePath,
                "${item.id}.${item.extension.ifEmpty { "bin" }}",
            )
            return File(path)
        } finally {
            if (!isPrivate) key.fill(0)
        }
    }

    /**
     * Decrypt an item into the cache directory so that another application
     * can be given access to it.
     *
     * The copy lives in the cache, which the system may delete, and it is
     * handed out through a `FileProvider` with a temporary permission.
     */
    fun exportForSharing(item: MediaItem, isPrivate: Boolean): File {
        val exports = File(context.cacheDir, "exports").apply { mkdirs() }
        // Replace an older plaintext copy of the same object instead of
        // leaving several of them behind.
        exports.listFiles()?.forEach { it.delete() }
        val target = File(exports, "${item.id}.${item.extension.ifEmpty { "bin" }}")
        val key = if (isPrivate) {
            unlockedPrivateKey ?: throw IllegalStateException("the private area is locked")
        } else {
            vault.vaultMasterKey()
        }
        try {
            val path = ZarcNative.nativeDecryptFile(
                key,
                item.id,
                objectsDirOf(item).absolutePath,
                exports.absolutePath,
                target.name,
            )
            return File(path)
        } finally {
            if (!isPrivate) key.fill(0)
        }
    }

    private fun displayName(uri: Uri, fallback: String): String {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) {
                val name = cursor.getString(index)
                if (!name.isNullOrBlank()) return name
            }
        }
        return fallback
    }

    private fun takenAt(uri: Uri, file: File): Long {
        val fromProvider = try {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATE_TAKEN), null, null, null)
                ?.use { cursor ->
                    val index = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
                    if (index >= 0 && cursor.moveToFirst()) cursor.getLong(index) else 0L
                } ?: 0L
        } catch (error: Exception) {
            0L
        }
        if (fromProvider > 0L) return fromProvider
        return file.lastModified()
    }

    private fun mediaProperties(file: File, mime: String): Triple<Int, Int, Long> {
        if (mime.startsWith("video/")) {
            val retriever = MediaMetadataRetriever()
            return try {
                retriever.setDataSource(file.absolutePath)
                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                Triple(width, height, duration)
            } catch (error: Exception) {
                Triple(0, 0, 0L)
            } finally {
                retriever.release()
            }
        }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return Triple(options.outWidth, options.outHeight, 0L)
    }

    private fun decodeScaled(file: File, target: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > target * 2 || bounds.outHeight / sample > target * 2) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }

    private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "heic" -> "image/heic"
        "gif" -> "image/gif"
        "mp4" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "webm" -> "video/webm"
        "3gp" -> "video/3gpp"
        else -> "application/octet-stream"
    }

    /** The date of an item, formatted for the user interface. */
    fun formatDate(timestamp: Long): String =
        if (timestamp <= 0L) {
            ""
        } else {
            SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(timestamp))
        }

    /** A short, stable fingerprint of a file, used to avoid duplicates. */
    fun fingerprint(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().take(8).joinToString("") { "%02x".format(it) }
    }

    /** Move items from the private area back into the normal roll. */
    val privateBinDir: File get() = File(context.noBackupFilesDir, "vault/private_bin")

    /** Move items from the normal gallery into the private vault. */
    @Synchronized
    fun moveToPrivate(ids: List<String>) {
        val privateKey = unlockedPrivateKey
            ?: throw IllegalStateException("unlock the private area before moving items into it")
        val vmk = vault.vaultMasterKey()
        try {
            val privateIndex = loadPrivateIndex(privateKey)
            val normalIndex = loadIndex()
            val moved = mutableListOf<Pair<String, String>>()

            ids.forEach { id ->
                val item = normalIndex.find(id) ?: return@forEach
                val ext = if (item.extension.isEmpty()) "bin" else item.extension
                val staged = File.createTempFile("toprivate-$id-", ".$ext", vault.workDir)
                var createdId: String? = null
                try {
                    ZarcNative.nativeDecryptFile(
                        vmk,
                        id,
                        objectsDir.absolutePath,
                        vault.workDir.absolutePath,
                        staged.name,
                    )
                    val newId = ZarcNative.nativeEncryptFile(
                        privateKey,
                        staged.absolutePath,
                        privateObjectsDir.absolutePath,
                    )
                    createdId = newId
                    val newItem = item.copy(id = newId, isPrivate = true, albumId = VaultIndex.ALBUM_NONE, isFavorite = false)
                    writeThumbnailWithKey(privateKey, newItem, staged)
                    privateIndex.items.add(newItem)
                    normalIndex.items.removeAll { it.id == id }
                    moved.add(id to newId)
                } catch (e: Exception) {
                    // The new object is not referenced by any index yet.
                    createdId?.let {
                        File(privateObjectsDir, it).delete()
                        File(privateThumbsDir, "$it.blob").delete()
                    }
                    android.util.Log.e("GalleryRepo", "moveToPrivate error: ${e.message}", e)
                } finally {
                    staged.delete()
                }
            }

            // Persist the destination index first. If the second write fails
            // the item is visible in both areas, which is recoverable; deleting
            // the source before the indexes were saved would make the only copy
            // unreachable.
            savePrivateIndex(privateKey, privateIndex)
            try {
                saveIndex(normalIndex)
            } catch (error: Exception) {
                // Roll the destination back so the normal index still matches
                // the only saved copy. Remove the entries first, commit the
                // destination index, and only then delete the new ciphertext.
                moved.forEach { (_, newId) ->
                    privateIndex.items.removeAll { it.id == newId }
                }
                runCatching { savePrivateIndex(privateKey, privateIndex) }
                moved.forEach { (_, newId) ->
                    File(privateObjectsDir, newId).delete()
                    File(privateThumbsDir, "$newId.blob").delete()
                }
                throw error
            }

            // Only now is it safe to remove the old ciphertext.
            moved.forEach { (oldId, _) ->
                File(objectsDir, oldId).delete()
                File(thumbsDir, "$oldId.blob").delete()
            }
        } finally {
            vmk.fill(0)
        }
    }

    private fun writeThumbnailWithKey(key: ByteArray, item: MediaItem, source: File) {
        val bitmap = decodeScaled(source, 320) ?: return
        val bytes = java.io.ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 82, output)
            output.toByteArray()
        }
        bitmap.recycle()
        val sealed = ZarcNative.nativeSealBlob(key, bytes)
        privateThumbsDir.mkdirs()
        File(privateThumbsDir, "${item.id}.blob").writeBytes(sealed)
    }

    /** Move private items into the private recycle bin. */
    @Synchronized
    fun movePrivateToBin(ids: List<String>) {
        val privateKey = unlockedPrivateKey ?: throw IllegalStateException("the private area is locked")
        privateBinDir.mkdirs()
        val index = loadPrivateIndex(privateKey)
        val now = System.currentTimeMillis()
        val moved = mutableSetOf<String>()
        ids.forEach { id ->
            val source = File(privateObjectsDir, id)
            if (!source.exists()) return@forEach
            if (!moveCiphertext(source, File(privateBinDir, id))) return@forEach
            val thumb = File(privateThumbsDir, "$id.blob")
            val thumbTarget = File(privateBinDir, "$id.blob")
            if (thumb.exists() && !moveCiphertext(thumb, thumbTarget)) {
                moveCiphertext(File(privateBinDir, id), source)
                return@forEach
            }
            moved.add(id)
        }
        if (moved.isEmpty()) return
        index.items.replaceAll { item ->
            if (item.id in moved) item.copy(isDeleted = true, deletedAt = now) else item
        }
        try {
            savePrivateIndex(privateKey, index)
        } catch (error: Exception) {
            moved.forEach { id ->
                moveCiphertext(File(privateBinDir, id), File(privateObjectsDir, id))
                moveCiphertext(File(privateBinDir, "$id.blob"), File(privateThumbsDir, "$id.blob"))
            }
            throw error
        }
    }

    /** Restore private items from private recycle bin. */
    @Synchronized
    fun restorePrivateFromBin(ids: List<String>) {
        val privateKey = unlockedPrivateKey ?: throw IllegalStateException("the private area is locked")
        val index = loadPrivateIndex(privateKey)
        val moved = mutableSetOf<String>()
        ids.forEach { id ->
            val source = File(privateBinDir, id)
            if (!source.exists()) return@forEach
            if (!moveCiphertext(source, File(privateObjectsDir, id))) return@forEach
            val thumb = File(privateBinDir, "$id.blob")
            val thumbTarget = File(privateThumbsDir, "$id.blob")
            if (thumb.exists() && !moveCiphertext(thumb, thumbTarget)) {
                moveCiphertext(File(privateObjectsDir, id), source)
                return@forEach
            }
            moved.add(id)
        }
        if (moved.isEmpty()) return
        index.items.replaceAll { item ->
            if (item.id in moved) item.copy(isDeleted = false, deletedAt = 0L) else item
        }
        try {
            savePrivateIndex(privateKey, index)
        } catch (error: Exception) {
            moved.forEach { id ->
                moveCiphertext(File(privateObjectsDir, id), File(privateBinDir, id))
                moveCiphertext(File(privateThumbsDir, "$id.blob"), File(privateBinDir, "$id.blob"))
            }
            throw error
        }
    }

    /** Permanently delete private items. */
    @Synchronized
    fun deletePrivateForever(ids: List<String>) {
        val privateKey = unlockedPrivateKey ?: throw IllegalStateException("the private area is locked")
        val index = loadPrivateIndex(privateKey)
        val existing = ids.filter { id -> index.find(id) != null }.toSet()
        if (existing.isEmpty()) return
        index.items.removeAll { it.id in existing }
        // Metadata first; the ciphertext stays reachable if the index write
        // fails.
        savePrivateIndex(privateKey, index)
        existing.forEach { id ->
            File(privateBinDir, id).delete()
            File(privateBinDir, "$id.blob").delete()
            File(privateObjectsDir, id).delete()
            File(privateThumbsDir, "$id.blob").delete()
        }
    }

    @Synchronized
    fun moveOutOfPrivate(ids: List<String>) {
        val privateKey = unlockedPrivateKey ?: throw IllegalStateException("the private area is locked")
        val vmk = vault.vaultMasterKey()
        try {
            val privateIndex = loadPrivateIndex(privateKey)
            val normalIndex = loadIndex()
            val moved = mutableListOf<Pair<String, String>>()

            ids.forEach { id ->
                val item = privateIndex.find(id) ?: return@forEach
                val ext = if (item.extension.isEmpty()) "bin" else item.extension
                val staged = File.createTempFile("unprivate-$id-", ".$ext", vault.workDir)
                var createdId: String? = null
                try {
                    ZarcNative.nativeDecryptFile(
                        privateKey,
                        id,
                        privateObjectsDir.absolutePath,
                        vault.workDir.absolutePath,
                        staged.name,
                    )
                    val newId = ZarcNative.nativeEncryptFile(
                        vmk,
                        staged.absolutePath,
                        objectsDir.absolutePath,
                    )
                    createdId = newId
                    val newItem = item.copy(id = newId, isPrivate = false)
                    writeThumbnail(false, newItem, staged)
                    normalIndex.items.add(newItem)
                    privateIndex.items.removeAll { it.id == id }
                    moved.add(id to newId)
                } catch (e: Exception) {
                    createdId?.let {
                        File(objectsDir, it).delete()
                        File(thumbsDir, "$it.blob").delete()
                    }
                } finally {
                    staged.delete()
                }
            }

            // Persist the destination index first: a failure of the second
            // write leaves a recoverable duplicate instead of an unreachable
            // object.
            saveIndex(normalIndex)
            try {
                savePrivateIndex(privateKey, privateIndex)
            } catch (error: Exception) {
                moved.forEach { (_, newId) ->
                    normalIndex.items.removeAll { it.id == newId }
                }
                runCatching { saveIndex(normalIndex) }
                moved.forEach { (_, newId) ->
                    File(objectsDir, newId).delete()
                    File(thumbsDir, "$newId.blob").delete()
                }
                throw error
            }

            moved.forEach { (oldId, _) ->
                File(privateObjectsDir, oldId).delete()
                File(privateThumbsDir, "$oldId.blob").delete()
            }
        } finally {
            vmk.fill(0)
        }
    }

    /** Replace [file] only after every byte of [bytes] is on disk. */
    private fun writeFileAtomically(file: File, bytes: ByteArray) {
        val parent = file.parentFile
            ?: throw IOException("the file has no parent directory")
        parent.mkdirs()
        val temporary = File.createTempFile("${file.name}.", ".tmp", parent)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            if (!temporary.renameTo(file)) {
                throw IOException("the file could not be committed")
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    companion object {
        /** 30 days in milliseconds for the recycle bin expiration timer. */
        const val BIN_EXPIRATION_MILLIS = 30L * 24 * 60 * 60 * 1000L
    }
}
