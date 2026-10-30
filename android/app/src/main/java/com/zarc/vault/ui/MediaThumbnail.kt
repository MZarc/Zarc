package com.zarc.vault.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zarc.vault.gallery.GalleryRepository
import com.zarc.vault.gallery.MediaItem
import com.zarc.vault.ui.theme.ZarcPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * An optimized in-memory cache of the decrypted thumbnails.
 *
 * Thumbnails use RGB_565 to cut memory footprint in half, allowing hundreds of
 * thumbnails to remain cached simultaneously for 60/120fps stutter-free scrolling.
 */
object ThumbnailCache {
    private val maxBytes = (Runtime.getRuntime().maxMemory() / 4).toInt().coerceIn(32 * 1024 * 1024, 160 * 1024 * 1024)

    private val cache = object : LruCache<String, android.graphics.Bitmap>(maxBytes) {
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

    /** Reduce the cache under memory pressure. */
    fun trim(level: Int) {
        when {
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> cache.evictAll()
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE -> cache.trimToSize(maxBytes / 4)
            else -> cache.trimToSize(maxBytes / 2)
        }
    }
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
    daysLeftBadge: String? = null,
) {
    var bitmap by remember(item.id) { mutableStateOf(ThumbnailCache.get(item.id)) }

    LaunchedEffect(item.id, isPrivate) {
        if (bitmap == null) {
            val bytes = withContext(Dispatchers.IO) { repository.readThumbnail(isPrivate, item.id) }
            if (bytes != null) {
                val decoded = withContext(Dispatchers.IO) {
                    val opts = BitmapFactory.Options().apply {
                        inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                    }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
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

        if (daysLeftBadge != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(5.dp)
                    .background(Color(0xCC111318), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = daysLeftBadge,
                    color = Color(0xFFFFB4AB),
                    fontSize = 11.sp,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        if (item.isVideo) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(5.dp)
                    .background(Color(0x99000000), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            ) {
                Text(
                    text = "▶",
                    color = Color.White,
                    fontSize = 10.sp,
                )
            }
        }
    }
}
