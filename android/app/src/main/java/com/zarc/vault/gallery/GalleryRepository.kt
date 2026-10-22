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
import java.security.MessageDigest
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

    // ----------------------------------------------------------------- index

    /** Load the index of the normal area. */
    fun loadIndex(): VaultIndex = loadIndex(indexFile, vault.vaultMasterKey())

    /** Save the index of the normal area. */
    fun saveIndex(index: VaultIndex) = saveIndex(indexFile, vault.vaultMasterKey(), index)

    private fun loadIndex(file: File, key: ByteArray): VaultIndex {
        if (!file.exists()) return VaultIndex(albums = VaultIndex.SYSTEM_ALBUMS.toMutableList())
        return try {
            val plain = ZarcNative.nativeOpenBlob(key, file.readBytes())
            VaultIndex.fromJson(plain)
        } catch (error: Exception) {
            // A damaged index must not take the whole gallery down: the media
            // is still in the vault, only the metadata is lost.
            VaultIndex(albums = VaultIndex.SYSTEM_ALBUMS.toMutableList())
        }
    }

    private fun saveIndex(file: File, key: ByteArray, index: VaultIndex) {
        file.parentFile?.mkdirs()
        val sealed = ZarcNative.nativeSealBlob(key, index.toJson())
        file.writeBytes(sealed)
    }

    // --------------------------------------------------------------- import

    /**
     * Import a document into the vault.
     *
     * The document is copied into app private storage, encrypted, and a
     * thumbnail is generated and sealed. The original is never modified.
     *
     * @return the id of the new vault object.
     */
    fun import(uri: Uri, albumId: String, isPrivate: Boolean = false): String {
        val staged = File(vault.workDir, "import-${System.currentTimeMillis()}")
        context.contentResolver.openInputStream(uri)?.use { input ->
            staged.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalArgumentException("the document could not be opened")

        val name = displayName(uri, staged.name)
        val mime = context.contentResolver.getType(uri) ?: mimeOf(name)
        val taken = takenAt(uri, staged)
        val (width, height, duration) = mediaProperties(staged, mime)

        val key = if (isPrivate) privateKey() ?: vault.vaultMasterKey() else vault.vaultMasterKey()
        val objectId = ZarcNative.nativeEncryptFile(key, staged.absolutePath, objectsDirOf(isPrivate).absolutePath)

        val item = MediaItem(
            id = objectId,
            name = name,
            mime = mime,
            size = File(objectsDirOf(isPrivate), objectId).length(),
            takenAt = taken,
            durationMs = duration,
            width = width,
            height = height,
            albumId = albumId,
            isPrivate = isPrivate,
        )
        // The thumbnail is generated from the staged copy, so it has to happen
        // before the staged file is removed.
        writeThumbnail(isPrivate, item, staged)
        staged.delete()

        val index = if (isPrivate) loadPrivateIndex(key) else loadIndex()
        index.items.add(item)
        if (isPrivate) savePrivateIndex(key, index) else saveIndex(index)
        return objectId
    }

    /**
     * Generate synthetic sample media and import it.
     *
     * This is a developer convenience: it gives the user interface something
     * to show on a fresh installation.
     */
    fun importSamples(count: Int): List<String> {
        val ids = mutableListOf<String>()
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

            val staged = File(vault.workDir, "sample-${System.currentTimeMillis()}-$position.jpg")
            staged.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            bitmap.recycle()

            val objectId = ZarcNative.nativeEncryptFile(
                vault.vaultMasterKey(),
                staged.absolutePath,
                objectsDir.absolutePath,
            )
            val item = MediaItem(
                id = objectId,
                name = "ZARC_${String.format(Locale.US, "%04d", position + 1)}.jpg",
                mime = "image/jpeg",
                size = File(objectsDir, objectId).length(),
                takenAt = System.currentTimeMillis() - position * 3_600_000L,
                durationMs = 0,
                width = 1080,
                height = 1440,
                albumId = VaultIndex.ALBUM_IMPORTED,
            )
            writeThumbnail(false, item, staged)
            staged.delete()
            val index = loadIndex()
            index.items.add(item)
            saveIndex(index)
            ids.add(objectId)
        }
        return ids
    }

    // ------------------------------------------------------------- albums

    /** Create a new album. */
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
    fun renameAlbum(albumId: String, name: String) {
        val index = loadIndex()
        index.albums.replaceAll { album -> if (album.id == albumId) album.copy(name = name) else album }
        saveIndex(index)
    }

    /** Move items into an album. */
    fun moveToAlbum(ids: List<String>, albumId: String) {
        val index = loadIndex()
        index.items.replaceAll { item -> if (item.id in ids) item.copy(albumId = albumId) else item }
        saveIndex(index)
    }

    /** Delete an album. Its items move to the imported album. */
    fun deleteAlbum(albumId: String) {
        val index = loadIndex()
        index.albums.removeAll { it.id == albumId && !it.system }
        index.items.replaceAll { item -> if (item.albumId == albumId) item.copy(albumId = VaultIndex.ALBUM_IMPORTED) else item }
        saveIndex(index)
    }

    // --------------------------------------------------------- recycle bin

    /** Move items into the recycle bin. */
    fun moveToBin(ids: List<String>) {
        binDir.mkdirs()
        val index = loadIndex()
        ids.forEach { id ->
            val source = File(objectsDir, id)
            if (source.exists()) source.renameTo(File(binDir, id))
            val thumb = File(thumbsDir, "$id.blob")
            if (thumb.exists()) thumb.renameTo(File(binDir, "$id.blob"))
        }
        index.items.replaceAll { item -> if (item.id in ids) item.copy(isDeleted = true) else item }
        saveIndex(index)
    }

    /** Restore items from the recycle bin. */
    fun restoreFromBin(ids: List<String>) {
        binDir.mkdirs()
        val index = loadIndex()
        ids.forEach { id ->
            val source = File(binDir, id)
            if (source.exists()) source.renameTo(File(objectsDir, id))
            val thumb = File(binDir, "$id.blob")
            if (thumb.exists()) thumb.renameTo(File(thumbsDir, "$id.blob"))
        }
        index.items.replaceAll { item -> if (item.id in ids) item.copy(isDeleted = false) else item }
        saveIndex(index)
    }

    /** Delete items permanently. */
    fun deleteForever(ids: List<String>) {
        val index = loadIndex()
        ids.forEach { id ->
            File(binDir, id).delete()
            File(binDir, "$id.blob").delete()
            File(objectsDir, id).delete()
            File(thumbsDir, "$id.blob").delete()
        }
        index.items.removeAll { it.id in ids }
        saveIndex(index)
    }

    /** Empty the recycle bin. */
    fun emptyBin() = deleteForever(loadIndex().deleted.map { it.id })

    // ------------------------------------------------------------ private

    /**
     * Enable the private area.
     *
     * A subkey is derived from the vault master key and wrapped by a Keystore
     * key that requires a biometric authentication. The private media is
     * encrypted with that subkey, so the private area is protected twice: by
     * the vault key *and* by the device authentication.
     */
    fun enablePrivateArea(): Boolean {
        val gate = PrivateGate(context)
        if (!gate.ensureKey()) return false
        val key = privateSubkey() ?: return false
        privateGateFile.parentFile?.mkdirs()
        privateGateFile.writeBytes(gate.wrap(key))
        return true
    }

    /** Whether the private area has been set up. */
    fun hasPrivateArea(): Boolean = privateGateFile.exists()

    /** The private index, using the unlocked private key. */
    fun loadPrivateIndex(key: ByteArray): VaultIndex = loadIndex(privateIndexFile, key)

    /** Save the private index. */
    fun savePrivateIndex(key: ByteArray, index: VaultIndex) = saveIndex(privateIndexFile, key, index)

    /** The private key, if it has been unlocked. */
    var unlockedPrivateKey: ByteArray? = null

    /** The subkey of the private area, derived from the vault master key. */
    private fun privateSubkey(): ByteArray? {
        val vmk = vault.vaultMasterKey()
        return try {
            ZarcNative.nativeDeriveSubkey(vmk, "zarc.private.v1")
        } finally {
            vmk.fill(0)
        }
    }

    /** The key that encrypts the private media: the unlocked private key. */
    private fun privateKey(): ByteArray? = unlockedPrivateKey

    /** The directory that holds the objects of an area. */
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
        val sealed = ZarcNative.nativeSealBlob(key, bytes)
        val directory = if (isPrivate) privateThumbsDir else thumbsDir
        directory.mkdirs()
        File(directory, "${item.id}.blob").writeBytes(sealed)
    }

    /** Read the sealed thumbnail of an item. */
    fun readThumbnail(isPrivate: Boolean, id: String): ByteArray? {
        val file = File(if (isPrivate) privateThumbsDir else thumbsDir, "$id.blob")
        if (!file.exists()) return null
        return try {
            val key = if (isPrivate) unlockedPrivateKey ?: return null else vault.vaultMasterKey()
            ZarcNative.nativeOpenBlob(key, file.readBytes())
        } catch (error: Exception) {
            null
        }
    }

    // ------------------------------------------------------------- helpers

    /**
     * Re-encrypt an item with a fresh data key.
     *
     * The identity and the plaintext stay the same; only the encrypted
     * material changes.
     */
    fun reencryptObject(item: MediaItem): String {
        val key = if (item.isPrivate) unlockedPrivateKey ?: vault.vaultMasterKey() else vault.vaultMasterKey()
        val stagedName = "reencrypt-${System.currentTimeMillis()}"
        val path = ZarcNative.nativeDecryptFile(
            key,
            item.id,
            objectsDirOf(item.isPrivate).absolutePath,
            vault.workDir.absolutePath,
            stagedName,
        )
        val source = File(path)
        val id = ZarcNative.nativeEncryptFile(
            key,
            source.absolutePath,
            objectsDirOf(item.isPrivate).absolutePath,
        )
        source.delete()
        return id
    }

    /** Decrypt an object into the export directory and return the file. */
    fun decrypt(item: MediaItem, isPrivate: Boolean): File {
        val key = if (isPrivate) unlockedPrivateKey ?: vault.vaultMasterKey() else vault.vaultMasterKey()
        val path = ZarcNative.nativeDecryptFile(
            key,
            item.id,
            objectsDirOf(isPrivate).absolutePath,
            vault.exportDir.absolutePath,
            "${item.id}.${item.extension.ifEmpty { "bin" }}",
        )
        return File(path)
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
        val target = File(exports, "${item.id}.${item.extension.ifEmpty { "bin" }}")
        val key = if (isPrivate) unlockedPrivateKey ?: vault.vaultMasterKey() else vault.vaultMasterKey()
        val path = ZarcNative.nativeDecryptFile(
            key,
            item.id,
            objectsDirOf(isPrivate).absolutePath,
            exports.absolutePath,
            target.name,
        )
        return File(path)
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
}
