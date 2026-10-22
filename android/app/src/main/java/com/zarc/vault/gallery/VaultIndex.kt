package com.zarc.vault.gallery

import org.json.JSONArray
import org.json.JSONObject

/**
 * One media item in the vault.
 *
 * The item describes an encrypted vault object. The metadata is *not* stored in
 * the object itself: it lives in the encrypted index, so the file name, the
 * capture time and the media type are protected exactly like the media.
 */
data class MediaItem(
    /** The vault object id (32 hexadecimal characters). */
    val id: String,
    /** The original display name, for example `IMG_20260718_101530.jpg`. */
    val name: String,
    /** The MIME type, for example `image/jpeg`. */
    val mime: String,
    /** The size of the original file in bytes. */
    val size: Long,
    /** The capture time in milliseconds since the epoch. */
    val takenAt: Long,
    /** The duration of a video in milliseconds, 0 for images. */
    val durationMs: Long,
    /** The width of the media in pixels. */
    val width: Int,
    /** The height of the media in pixels. */
    val height: Int,
    /** The album the item belongs to. */
    val albumId: String,
    /** Whether the item lives in the private area. */
    val isPrivate: Boolean = false,
    /** Whether the item is in the recycle bin. */
    val isDeleted: Boolean = false,
) {
    /** Whether the item is a video. */
    val isVideo: Boolean get() = mime.startsWith("video/")

    /** The file extension of the original name, lower case, without the dot. */
    val extension: String
        get() = name.substringAfterLast('.', "").lowercase()

    /** The aspect ratio of the media, 1.0 when it is unknown. */
    val aspectRatio: Float
        get() = if (width > 0 && height > 0) width.toFloat() / height.toFloat() else 1f
}

/** A group of media items. */
data class Album(
    /** The album id. */
    val id: String,
    /** The display name of the album. */
    val name: String,
    /** Whether the album was created by the system (camera, screenshots, ...). */
    val system: Boolean = false,
)

/**
 * The metadata index of one vault area.
 *
 * The index is serialised as JSON and sealed with the area's key, so it never
 * exists on disk in the clear.
 */
class VaultIndex(
    /** The items of the area. */
    val items: MutableList<MediaItem> = mutableListOf(),
    /** The albums of the area. */
    val albums: MutableList<Album> = mutableListOf(),
) {
    /** The items that are not in the recycle bin. */
    val visible: List<MediaItem> get() = items.filter { !it.isDeleted }

    /** The items in the recycle bin. */
    val deleted: List<MediaItem> get() = items.filter { it.isDeleted }

    /** The items of one album. */
    fun inAlbum(albumId: String): List<MediaItem> = visible.filter { it.albumId == albumId }

    /** Look an item up by its object id. */
    fun find(id: String): MediaItem? = items.firstOrNull { it.id == id }

    /** Serialise the index. */
    fun toJson(): ByteArray = JSONObject().apply {
        put("version", VERSION)
        put("items", JSONArray().also { array ->
            items.forEach { item ->
                array.put(JSONObject().apply {
                    put("id", item.id)
                    put("name", item.name)
                    put("mime", item.mime)
                    put("size", item.size)
                    put("taken", item.takenAt)
                    put("duration", item.durationMs)
                    put("width", item.width)
                    put("height", item.height)
                    put("album", item.albumId)
                    put("private", item.isPrivate)
                    put("deleted", item.isDeleted)
                })
            }
        })
        put("albums", JSONArray().also { array ->
            albums.forEach { album ->
                array.put(JSONObject().apply {
                    put("id", album.id)
                    put("name", album.name)
                    put("system", album.system)
                })
            }
        })
    }.toString().toByteArray(Charsets.UTF_8)

    companion object {
        /** The version of the index format. */
        const val VERSION = 1

        /** The id of the album that holds the imported media by default. */
        const val ALBUM_IMPORTED = "imported"

        /** The id of the system album for camera shots. */
        const val ALBUM_CAMERA = "camera"

        /** The id of the system album for screenshots. */
        const val ALBUM_SCREENSHOTS = "screenshots"

        /** The id of the system album for downloads. */
        const val ALBUM_DOWNLOADS = "downloads"

        /** The default system albums of a fresh vault. */
        val SYSTEM_ALBUMS = listOf(
            Album(ALBUM_CAMERA, "Camera", system = true),
            Album(ALBUM_SCREENSHOTS, "Screenshots", system = true),
            Album(ALBUM_DOWNLOADS, "Downloads", system = true),
            Album(ALBUM_IMPORTED, "Imported", system = true),
        )

        /** Parse an index. */
        fun fromJson(bytes: ByteArray): VaultIndex {
            val root = JSONObject(String(bytes, Charsets.UTF_8))
            val index = VaultIndex()
            val items = root.optJSONArray("items") ?: JSONArray()
            for (position in 0 until items.length()) {
                val item = items.getJSONObject(position)
                index.items.add(
                    MediaItem(
                        id = item.getString("id"),
                        name = item.optString("name", "item"),
                        mime = item.optString("mime", "application/octet-stream"),
                        size = item.optLong("size", 0L),
                        takenAt = item.optLong("taken", 0L),
                        durationMs = item.optLong("duration", 0L),
                        width = item.optInt("width", 0),
                        height = item.optInt("height", 0),
                        albumId = item.optString("album", ALBUM_IMPORTED),
                        isPrivate = item.optBoolean("private", false),
                        isDeleted = item.optBoolean("deleted", false),
                    ),
                )
            }
            val albums = root.optJSONArray("albums") ?: JSONArray()
            for (position in 0 until albums.length()) {
                val album = albums.getJSONObject(position)
                index.albums.add(
                    Album(
                        id = album.getString("id"),
                        name = album.optString("name", "Album"),
                        system = album.optBoolean("system", false),
                    ),
                )
            }
            return index
        }
    }
}
