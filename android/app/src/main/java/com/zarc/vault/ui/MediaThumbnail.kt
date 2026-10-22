package com.zarc.vault.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import com.zarc.vault.gallery.GalleryRepository
import com.zarc.vault.gallery.MediaItem
import com.zarc.vault.ui.theme.ZarcPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A small in-memory cache of the decrypted thumbnails.
 *
 * The thumbnails are decrypted on demand and kept in memory only. Nothing is
 * written to the cache directory in the clear.
 */
object ThumbnailCache {
    private val cache = object : LruCache<String, android.graphics.Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: android.graphics.Bitmap): Int = value.byteCount
    }

    /** Put a bitmap into the cache. */
    fun put(id: String, bitmap: android.graphics.Bitmap) {
        cache.put(id, bitmap)
    }

    /** Read a bitmap from the cache. */
    fun get(id: String): android.graphics.Bitmap? = cache.get(id)

    /** Drop every cached bitmap. */
    fun clear() = cache.evictAll()
}

/**
 * The thumbnail of a media item.
 *
 * The thumbnail is sealed inside the vault; it is decrypted in memory and
 * never written to the disk.
 */
@Composable
fun MediaThumbnail(
    repository: GalleryRepository,
    item: MediaItem,
    isPrivate: Boolean,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    var bitmap by remember(item.id) { mutableStateOf(ThumbnailCache.get(item.id)) }

    LaunchedEffect(item.id, isPrivate) {
        if (bitmap == null) {
            val bytes = withContext(Dispatchers.IO) { repository.readThumbnail(isPrivate, item.id) }
            if (bytes != null) {
                val decoded = withContext(Dispatchers.IO) {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
                if (decoded != null) {
                    ThumbnailCache.put(item.id, decoded)
                    bitmap = decoded
                }
            }
        }
    }

    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        val current = bitmap
        if (current != null) {
            Image(
                bitmap = current.asImageBitmap(),
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = if (item.isVideo) "▶" else "·",
                style = MaterialTheme.typography.titleMedium,
                color = if (item.isVideo) ZarcPalette.videos else ZarcPalette.photos,
            )
        }
    }
}
