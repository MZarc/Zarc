package com.zarc.vault.archive

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.zarc.vault.core.ZarcNative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

data class ArchivePhotoSource(
    val displayName: String,
    val sizeBytes: Long,
    val uri: Uri? = null,
    val localFile: File? = null,
    val previewBitmap: Bitmap? = null,
)

data class ArchiveObject(
    val id: String,
    val originalName: String,
    val mimeType: String,
    val originalSize: Long,
    val sanitizedSize: Long,
    val offset: Long,
    val length: Long,
    val chunkSize: Int,
    val totalChunks: Int,
    val isCover: Boolean,
)

data class ArchiveManifestData(
    val version: Int,
    val containerId: String,
    val createdAtUnix: Long,
    val coverObjectId: String?,
    val objects: List<ArchiveObject>,
)

class ArchiveRepository(private val context: Context) {

    suspend fun createArchiveFromSources(
        outputFile: File,
        sources: List<ArchivePhotoSource>,
        coverIndex: Int,
        passphrase: String,
        createAsPolyglotJpeg: Boolean = true,
    ): Result<File> = withContext(Dispatchers.IO) {
        val stagingDir = File(context.cacheDir, "archive_staging_${System.currentTimeMillis()}")
        stagingDir.mkdirs()
        try {
            val stagingPaths = mutableListOf<String>()
            val names = mutableListOf<String>()

            for (i in sources.indices) {
                val src = sources[i]
                val name = src.displayName.takeIf { it.isNotBlank() } ?: "photo_$i.jpg"
                val stageFile = File(stagingDir, "stage_$i.dat")
                if (src.localFile != null && src.localFile.exists()) {
                    src.localFile.copyTo(stageFile, overwrite = true)
                } else if (src.uri != null) {
                    context.contentResolver.openInputStream(src.uri)?.use { input ->
                        FileOutputStream(stageFile).use { output ->
                            input.copyTo(output)
                        }
                    } ?: return@withContext Result.failure(Exception("Cannot read photo at ${src.uri}"))
                } else {
                    return@withContext Result.failure(Exception("Invalid photo source at index $i"))
                }

                stagingPaths.add(stageFile.absolutePath)
                names.add(name)
            }

            val validCoverIndex = coverIndex.coerceIn(0, (names.size - 1).coerceAtLeast(0))

            val outerCoverBytes: ByteArray? = if (createAsPolyglotJpeg && stagingPaths.isNotEmpty()) {
                val coverFile = File(stagingPaths[validCoverIndex])
                val rawBytes = coverFile.readBytes()
                if (rawBytes.size >= 4 && rawBytes[0] == 0xFF.toByte() && rawBytes[1] == 0xD8.toByte()) {
                    rawBytes
                } else {
                    val bmp = BitmapFactory.decodeFile(coverFile.absolutePath)
                    if (bmp != null) {
                        val baos = ByteArrayOutputStream()
                        bmp.compress(Bitmap.CompressFormat.JPEG, 92, baos)
                        baos.toByteArray()
                    } else {
                        rawBytes
                    }
                }
            } else {
                null
            }

            val passBytes = passphrase.toByteArray(Charsets.UTF_8)
            ZarcNative.nativeArchiveCreate(
                archivePath = outputFile.absolutePath,
                sourcePaths = stagingPaths.toTypedArray(),
                displayNames = names.toTypedArray(),
                coverIndex = validCoverIndex,
                passphrase = passBytes,
                outerCoverJpeg = outerCoverBytes,
            )

            Result.success(outputFile)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            stagingDir.deleteRecursively()
        }
    }

    suspend fun createArchive(
        outputFile: File,
        inputUris: List<Uri>,
        displayNames: List<String>,
        coverIndex: Int,
        passphrase: String,
        createAsPolyglotJpeg: Boolean = true,
    ): Result<File> {
        val sources = inputUris.mapIndexed { idx, uri ->
            ArchivePhotoSource(
                displayName = displayNames.getOrNull(idx) ?: "photo_$idx.jpg",
                sizeBytes = 0L,
                uri = uri,
            )
        }
        return createArchiveFromSources(
            outputFile = outputFile,
            sources = sources,
            coverIndex = coverIndex,
            passphrase = passphrase,
            createAsPolyglotJpeg = createAsPolyglotJpeg,
        )
    }

    suspend fun readOuterCover(archiveFile: File): Result<ByteArray?> = withContext(Dispatchers.IO) {
        try {
            val bytes = ZarcNative.nativeArchiveReadOuterCover(archiveFile.absolutePath)
            Result.success(bytes)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun readManifest(
        archiveFile: File,
        passphrase: String,
    ): Result<ArchiveManifestData> = withContext(Dispatchers.IO) {
        try {
            val passBytes = passphrase.toByteArray(Charsets.UTF_8)
            val jsonStr = ZarcNative.nativeArchiveReadManifest(
                archivePath = archiveFile.absolutePath,
                passphrase = passBytes,
            )
            val manifest = parseManifestJson(jsonStr)
            Result.success(manifest)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun extractCoverThumbnail(
        archiveFile: File,
        passphrase: String,
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val cacheFile = File(context.cacheDir, "cover_${archiveFile.nameWithoutExtension}.jpg")
            val passBytes = passphrase.toByteArray(Charsets.UTF_8)
            ZarcNative.nativeArchiveExtractCoverThumbnail(
                archivePath = archiveFile.absolutePath,
                passphrase = passBytes,
                cacheDestinationFile = cacheFile.absolutePath,
            )
            Result.success(cacheFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun extractSingleEntry(
        archiveFile: File,
        passphrase: String,
        objectId: String,
        destinationFile: File,
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val passBytes = passphrase.toByteArray(Charsets.UTF_8)
            ZarcNative.nativeArchiveExtractEntry(
                archivePath = archiveFile.absolutePath,
                passphrase = passBytes,
                objectId = objectId,
                destinationPath = destinationFile.absolutePath,
            )
            Result.success(destinationFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun extractEntryToBitmap(
        archiveFile: File,
        passphrase: String,
        objectId: String,
        maxDimension: Int = 1080,
    ): Result<Bitmap> = withContext(Dispatchers.IO) {
        val tempFile = File(context.cacheDir, "preview_${objectId}_${System.currentTimeMillis()}.tmp")
        try {
            val res = extractSingleEntry(archiveFile, passphrase, objectId, tempFile)
            if (res.isFailure) return@withContext Result.failure(res.exceptionOrNull() ?: Exception("Extraction failed"))
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(tempFile.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return@withContext Result.failure(Exception("Decoded image bounds invalid"))
            }
            var sample = 1
            while (bounds.outWidth / sample > maxDimension * 2 || bounds.outHeight / sample > maxDimension * 2) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bmp = BitmapFactory.decodeFile(tempFile.absolutePath, opts)
            if (bmp != null) Result.success(bmp) else Result.failure(Exception("Failed to decode bitmap"))
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            tempFile.delete()
        }
    }

    suspend fun extractAllEntries(
        archiveFile: File,
        passphrase: String,
        destinationDir: File,
    ): Result<List<File>> = withContext(Dispatchers.IO) {
        try {
            destinationDir.mkdirs()
            val passBytes = passphrase.toByteArray(Charsets.UTF_8)
            val jsonArrStr = ZarcNative.nativeArchiveExtractAll(
                archivePath = archiveFile.absolutePath,
                passphrase = passBytes,
                destinationDir = destinationDir.absolutePath,
            )
            val jsonArr = JSONArray(jsonArrStr)
            val files = mutableListOf<File>()
            for (i in 0 until jsonArr.length()) {
                files.add(File(jsonArr.getString(i)))
            }
            Result.success(files)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseManifestJson(jsonString: String): ArchiveManifestData {
        val root = JSONObject(jsonString)
        val version = root.getInt("version")
        val containerId = root.getString("container_id")
        val createdAtUnix = root.optLong("created_at_unix", 0L)
        val coverObjectId = if (root.has("cover_object_id") && !root.isNull("cover_object_id")) {
            root.getString("cover_object_id")
        } else {
            null
        }

        val objectsArray = root.getJSONArray("objects")
        val objects = mutableListOf<ArchiveObject>()
        for (i in 0 until objectsArray.length()) {
            val obj = objectsArray.getJSONObject(i)
            objects.add(
                ArchiveObject(
                    id = obj.getString("id"),
                    originalName = obj.getString("original_name"),
                    mimeType = obj.optString("mime_type", "image/jpeg"),
                    originalSize = obj.optLong("original_size", 0L),
                    sanitizedSize = obj.optLong("sanitized_size", 0L),
                    offset = obj.optLong("offset", 0L),
                    length = obj.optLong("length", 0L),
                    chunkSize = obj.optInt("chunk_size", 65536),
                    totalChunks = obj.optInt("total_chunks", 1),
                    isCover = obj.optBoolean("is_cover", false),
                )
            )
        }
        return ArchiveManifestData(version, containerId, createdAtUnix, coverObjectId, objects)
    }
}
