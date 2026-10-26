package com.zarc.vault.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import android.content.Intent
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem as PlaybackItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.zarc.vault.gallery.GalleryRepository
import com.zarc.vault.gallery.MediaItem
import com.zarc.vault.ui.theme.ZarcPalette
import java.io.File
import java.util.Locale
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Format a byte count for the details of the viewer. */
internal fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    else -> String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
}

/**
 * The full screen viewer.
 *
 * The media is decrypted into memory for the duration of the view; the
 * decrypted copy is never written to the disk.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    initialItem: MediaItem,
    items: List<MediaItem> = listOf(initialItem),
    isPrivate: Boolean,
    repository: GalleryRepository,
    onClose: () -> Unit,
    onOpenWith: (MediaItem) -> Unit,
    onOpenInSplit: () -> Unit,
    onDelete: (MediaItem) -> Unit,
    onReencrypt: (MediaItem) -> Unit,
    onRename: (MediaItem) -> Unit,
    onEditDate: (MediaItem) -> Unit,
    onToggleFavorite: (MediaItem) -> Unit,
    onRestore: ((MediaItem) -> Unit)? = null,
    onMoveToPrivate: ((MediaItem) -> Unit)? = null,
    onSetAsCover: ((MediaItem) -> Unit)? = null,
) {
    val initialPage = remember(initialItem.id, items) {
        val idx = items.indexOfFirst { it.id == initialItem.id }
        if (idx >= 0) idx else 0
    }
    val pagerState = rememberPagerState(initialPage = initialPage) { items.size }
    LaunchedEffect(initialItem.id) {
        val idx = items.indexOfFirst { it.id == initialItem.id }
        if (idx >= 0 && idx != pagerState.currentPage) {
            pagerState.scrollToPage(idx)
        }
    }
    val currentItem = items.getOrNull(pagerState.currentPage) ?: initialItem

    var menu by remember(currentItem.id) { mutableStateOf(false) }
    var chrome by remember(currentItem.id) { mutableStateOf(true) }
    var showInfo by remember(currentItem.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        HorizontalPager(
            state = pagerState,
            key = { items.getOrNull(it)?.id ?: it },
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val pageItem = items.getOrNull(page) ?: currentItem
            if (pageItem.isVideo) {
                VideoSurface(
                    item = pageItem,
                    repository = repository,
                    controlsVisible = chrome,
                    onToggleChrome = { chrome = !chrome },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                ZoomableMedia(
                    repository = repository,
                    item = pageItem,
                    isPrivate = isPrivate,
                    onSingleTap = { chrome = !chrome },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        AnimatedVisibility(
            visible = chrome,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
        ) {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = currentItem.name,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = repository.formatDate(currentItem.takenAt),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f),
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(ZarcIcons.Back, contentDescription = "Close", tint = Color.White)
                    }
                },
                actions = {
                    if (!currentItem.isDeleted) {
                        IconButton(onClick = { menu = true }) {
                            Icon(
                                imageVector = ZarcIcons.MoreVert,
                                contentDescription = "More options",
                                tint = Color.White,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                modifier = Modifier.background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.72f), Color.Transparent),
                    ),
                ),
            )
        }

        // Dedicated bottom action bar for Recycle Bin items (Samsung Gallery style)
        AnimatedVisibility(
            visible = chrome && currentItem.isDeleted,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
        ) {
            val now = System.currentTimeMillis()
            val daysPassed = ((now - currentItem.deletedAt) / (24L * 60 * 60 * 1000L)).toInt()
            val daysLeft = maxOf(0, 30 - daysPassed)
            val deletedAtFormatted = if (currentItem.deletedAt > 0L) repository.formatDate(currentItem.deletedAt) else "Recently"
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f)),
                        ),
                    )
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Deleted on $deletedAtFormatted · $daysLeft days left",
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                    color = Color.White.copy(alpha = 0.85f),
                )
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (onRestore != null) {
                        Surface(
                            onClick = {
                                ZarcHaptics.click(context)
                                onRestore(currentItem)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            shape = RoundedCornerShape(24.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            tonalElevation = 6.dp,
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = ZarcIcons.Restore,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "Restore",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                    Surface(
                        onClick = {
                            ZarcHaptics.click(context)
                            onDelete(currentItem)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.9f),
                        tonalElevation = 6.dp,
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = ZarcIcons.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Delete",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
            }
        }

        // Small Tile Filmstrip (Samsung Gallery Carousel) at the bottom
        AnimatedVisibility(
            visible = chrome && !currentItem.isDeleted && items.size > 1,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
        ) {
            val filmstripState = rememberLazyListState()

            LaunchedEffect(pagerState.currentPage) {
                filmstripState.animateScrollToItem(maxOf(0, pagerState.currentPage - 2))
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)),
                        ),
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                LazyRow(
                    state = filmstripState,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(items, key = { _, it -> it.id }) { index, tileItem ->
                        val isSelected = index == pagerState.currentPage
                        Box(
                            modifier = Modifier
                                .size(if (isSelected) 48.dp else 40.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .then(
                                    if (isSelected) {
                                        Modifier.border(2.dp, Color.White, RoundedCornerShape(8.dp))
                                    } else {
                                        Modifier
                                    },
                                )
                                .clickable {
                                    ZarcHaptics.click(context)
                                    scope.launch {
                                        pagerState.animateScrollToPage(index)
                                    }
                                },
                        ) {
                            MediaThumbnail(
                                repository = repository,
                                item = tileItem,
                                isPrivate = isPrivate,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }
    }

    ImmersiveChrome(hidden = !chrome)

    if (menu) {
        ZarcSheet(onDismiss = { menu = false }) {
            SheetHeader(
                title = currentItem.name,
                subtitle = "${repository.formatDate(currentItem.takenAt)} · ${formatBytes(currentItem.size)}",
            )
            Text(
                text = "${currentItem.width} × ${currentItem.height} · ${currentItem.mime}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
            )
            SheetAction(
                icon = ZarcIcons.Info,
                title = "Details",
                subtitle = "View full metadata, dimensions and encryption details",
                onClick = {
                    menu = false
                    showInfo = true
                },
            )
            SheetAction(
                icon = ZarcIcons.Edit,
                title = "Rename",
                subtitle = "Change the display name",
                onClick = {
                    menu = false
                    onRename(currentItem)
                },
            )
            SheetAction(
                icon = ZarcIcons.DateRange,
                title = "Date and time",
                subtitle = "Change when the media was captured",
                onClick = {
                    menu = false
                    onEditDate(currentItem)
                },
            )
            if (!currentItem.isPrivate) {
                SheetAction(
                    icon = if (currentItem.isFavorite) ZarcIcons.Star else ZarcIcons.StarBorder,
                    title = if (currentItem.isFavorite) "Remove from favourites" else "Add to favourites",
                    subtitle = if (currentItem.isFavorite) {
                        "Move it back to the roll"
                    } else {
                        "Move it into the favourite album"
                    },
                    tint = ZarcPalette.favourite,
                    onClick = {
                        menu = false
                        onToggleFavorite(currentItem)
                    },
                )
            }
            SheetAction(
                icon = ZarcIcons.Split,
                title = "Open in split window",
                subtitle = "See two media items at the same time",
                onClick = {
                    menu = false
                    onOpenInSplit()
                },
            )
            SheetAction(
                icon = ZarcIcons.OpenWith,
                title = "Open with another app",
                subtitle = "Hand a decrypted copy to another application",
                onClick = {
                    menu = false
                    onOpenWith(currentItem)
                },
            )
            if (!currentItem.isPrivate && onMoveToPrivate != null) {
                SheetAction(
                    icon = ZarcIcons.Lock,
                    title = "Move to Private Vault",
                    subtitle = "Double-encrypt and hide from gallery",
                    onClick = {
                        menu = false
                        onMoveToPrivate(currentItem)
                    },
                )
            }
            if (onSetAsCover != null) {
                SheetAction(
                    icon = ZarcIcons.Albums,
                    title = "Set as album cover",
                    subtitle = "Use this photo as the album thumbnail",
                    onClick = {
                        menu = false
                        onSetAsCover(currentItem)
                    },
                )
            }
            SheetAction(
                icon = ZarcIcons.Shield,
                title = "Encrypt again",
                subtitle = "Re-wrap the object under a fresh data key",
                onClick = {
                    menu = false
                    onReencrypt(currentItem)
                },
            )
            SheetAction(
                icon = ZarcIcons.Delete,
                title = "Delete",
                subtitle = "Move the item to the bin",
                destructive = true,
                onClick = {
                    menu = false
                    onDelete(currentItem)
                },
            )
        }
    }

    if (showInfo) {
        MediaInfoDialog(
            items = listOf(currentItem),
            repository = repository,
            onDismiss = { showInfo = false },
        )
    }
}

/** The decoded full-size images of the viewer, kept in memory only. */
object ViewerImageCache {
    private val cache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun get(key: String): Bitmap? = cache.get(key)

    fun put(key: String, bitmap: Bitmap) {
        cache.put(key, bitmap)
    }

    fun clear() = cache.evictAll()
}

/**
 * A decoded image with smooth pinch to zoom, centroid aware panning, double
 * tap to zoom in and out, and a single tap callback that lets the viewer hide
 * its chrome.
 */
@Composable
private fun ZoomableMedia(
    repository: GalleryRepository,
    item: MediaItem,
    isPrivate: Boolean,
    maxDimension: Int = 2560,
    onSingleTap: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val cacheKey = "${item.id}@$maxDimension"
    var scale by remember(item.id) { mutableFloatStateOf(1f) }
    var offsetX by remember(item.id) { mutableFloatStateOf(0f) }
    var offsetY by remember(item.id) { mutableFloatStateOf(0f) }
    var bitmap by remember(item.id) {
        mutableStateOf(if (isPrivate) null else ViewerImageCache.get(cacheKey))
    }
    var failed by remember(item.id) { mutableStateOf(false) }
    var animationJob by remember(item.id) { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(item.id, isPrivate) {
        if (bitmap == null) {
            val decoded = withContext(Dispatchers.IO) {
                repository.decodeForViewer(item, maxDimension)
            }
            if (decoded == null) {
                failed = true
            } else {
                if (!isPrivate) ViewerImageCache.put(cacheKey, decoded)
                bitmap = decoded
            }
        }
    }

    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        val viewportWidth = constraints.maxWidth.toFloat()
        val viewportHeight = constraints.maxHeight.toFloat()
        val aspect = when {
            bitmap != null -> bitmap!!.width.toFloat() / bitmap!!.height.coerceAtLeast(1)
            item.width > 0 && item.height > 0 -> item.width.toFloat() / item.height
            else -> 1f
        }
        val displayedWidth = min(viewportWidth, viewportHeight * aspect)
        val displayedHeight = min(viewportHeight, viewportWidth / aspect)

        fun maxOffsetX(candidate: Float): Float =
            ((displayedWidth * candidate - viewportWidth) / 2f).coerceAtLeast(0f)

        fun maxOffsetY(candidate: Float): Float =
            ((displayedHeight * candidate - viewportHeight) / 2f).coerceAtLeast(0f)

        fun clampOffsets(candidate: Float) {
            offsetX = offsetX.coerceIn(-maxOffsetX(candidate), maxOffsetX(candidate))
            offsetY = offsetY.coerceIn(-maxOffsetY(candidate), maxOffsetY(candidate))
        }

        fun animateTo(targetScale: Float, targetX: Float, targetY: Float) {
            animationJob?.cancel()
            val startScale = scale
            val startX = offsetX
            val startY = offsetY
            animationJob = scope.launch {
                animate(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = tween(220),
                ) { fraction, _ ->
                    scale = startScale + (targetScale - startScale) * fraction
                    offsetX = startX + (targetX - startX) * fraction
                    offsetY = startY + (targetY - startY) * fraction
                }
            }
        }

        val current = bitmap
        if (current != null) {
            Image(
                bitmap = current.asImageBitmap(),
                contentDescription = item.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offsetX
                        translationY = offsetY
                    }
                    .then(
                        if (scale > 1.05f) {
                            Modifier.pointerInput(item.id, displayedWidth, displayedHeight) {
                                detectTransformGestures(panZoomLock = true) { centroid, pan, zoom, _ ->
                                    animationJob?.cancel()
                                    val oldScale = scale
                                    val nextScale = (oldScale * zoom).coerceIn(1f, 6f)
                                    if (nextScale != oldScale) {
                                        val factor = nextScale / oldScale
                                        val centerX = centroid.x - size.width / 2f
                                        val centerY = centroid.y - size.height / 2f
                                        offsetX = offsetX * factor + centerX * (1f - factor)
                                        offsetY = offsetY * factor + centerY * (1f - factor)
                                    }
                                    offsetX += pan.x
                                    offsetY += pan.y
                                    scale = nextScale
                                    clampOffsets(nextScale)
                                }
                            }
                        } else {
                            Modifier
                        }
                    )
                    .pointerInput(item.id) {
                        detectTapGestures(
                            onTap = { onSingleTap() },
                            onDoubleTap = { position ->
                                val target = if (scale > 1.05f) 1f else 2.5f
                                if (target == 1f) {
                                    animateTo(1f, 0f, 0f)
                                } else {
                                    val factor = target / scale
                                    val centerX = position.x - size.width / 2f
                                    val centerY = position.y - size.height / 2f
                                    val targetX = (offsetX * factor + centerX * (1f - factor))
                                        .coerceIn(-maxOffsetX(target), maxOffsetX(target))
                                    val targetY = (offsetY * factor + centerY * (1f - factor))
                                        .coerceIn(-maxOffsetY(target), maxOffsetY(target))
                                    animateTo(target, targetX, targetY)
                                }
                            },
                        )
                    },
            )
        } else {
            Text(
                text = if (failed) "The media could not be decoded" else "Loading…",
                color = Color.White,
            )
        }
    }
}

/** Hide the system bars while the viewer is immersive, and restore them. */
@Composable
private fun ImmersiveChrome(hidden: Boolean) {
    val view = LocalView.current
    val context = LocalContext.current
    if (view.isInEditMode) return
    DisposableEffect(hidden) {
        val window = (context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (hidden) {
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

/** Format a playback position as `m:ss` or `h:mm:ss`. */
private fun formatPlayback(milliseconds: Long): String {
    val total = (milliseconds / 1000).coerceAtLeast(0)
    val seconds = total % 60
    val minutes = total / 60
    return if (minutes >= 60) {
        "%d:%02d:%02d".format(minutes / 60, minutes % 60, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

/**
 * A decrypted video played by ExoPlayer.
 *
 * The player has no platform controller: the controls are Compose, so the
 * same tap that hides the viewer chrome also hides the video controls. The
 * decrypted file lives in the cache and is deleted when the surface leaves
 * the composition.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun VideoSurface(
    item: MediaItem,
    repository: GalleryRepository,
    controlsVisible: Boolean,
    onToggleChrome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val player = remember(item.id) { ExoPlayer.Builder(context).build() }
    var file by remember(item.id) { mutableStateOf<File?>(null) }
    var failed by remember(item.id) { mutableStateOf(false) }
    var playing by remember(item.id) { mutableStateOf(false) }
    var position by remember(item.id) { mutableLongStateOf(0L) }
    var duration by remember(item.id) { mutableLongStateOf(0L) }

    LaunchedEffect(item.id) {
        val prepared = withContext(Dispatchers.IO) {
            runCatching { repository.preparePlayback(item) }.getOrNull()
        }
        if (prepared == null) {
            failed = true
        } else {
            file = prepared
            player.setMediaItem(PlaybackItem.fromUri(Uri.fromFile(prepared)))
            player.prepare()
            player.playWhenReady = true
        }
    }

    DisposableEffect(item.id) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    duration = player.duration.coerceAtLeast(0L)
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
            file?.delete()
            file = null
        }
    }

    LaunchedEffect(player, file) {
        if (file == null) return@LaunchedEffect
        while (isActive) {
            position = player.currentPosition.coerceAtLeast(0L)
            duration = player.duration.coerceAtLeast(0L)
            delay(250)
        }
    }

    Box(modifier.background(Color.Black)) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    this.player = player
                }
            },
            update = { view -> view.player = player },
            modifier = Modifier.fillMaxSize(),
        )

        // A tap anywhere on the video toggles the chrome and the controls.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(item.id) {
                    detectTapGestures(onTap = { onToggleChrome() })
                },
        )

        if (failed) {
            Text(
                text = "The video could not be decoded",
                color = Color.White,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        if (controlsVisible && !failed) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable {
                        if (playing) {
                            player.pause()
                        } else {
                            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                            player.play()
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (playing) ZarcIcons.Pause else ZarcIcons.Play,
                    contentDescription = if (playing) "Pause" else "Play",
                    tint = Color.White,
                    modifier = Modifier.size(30.dp),
                )
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)),
                        ),
                    )
                    .navigationBarsPadding()
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            ) {
                Slider(
                    value = if (duration > 0L) {
                        (position.toFloat() / duration).coerceIn(0f, 1f)
                    } else {
                        0f
                    },
                    onValueChange = { fraction ->
                        if (duration > 0L) {
                            player.seekTo((fraction * duration).toLong())
                            position = (fraction * duration).toLong()
                        }
                    },
                )
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        text = formatPlayback(position),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = formatPlayback(duration),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.75f),
                    )
                }
            }
        }
    }
}

/**
 * Two media items next to each other.
 *
 * Each pane can be replaced from the roll, so two pictures can be compared
 * without leaving the application. The panes stack vertically in portrait and
 * sit side by side in landscape; the divider is draggable so the split can be
 * adjusted, like the Samsung gallery.
 */
@Composable
fun SplitViewerScreen(
    primary: MediaItem,
    secondary: MediaItem?,
    repository: GalleryRepository,
    candidates: List<MediaItem>,
    onPickPrimary: (MediaItem) -> Unit,
    onPickSecondary: (MediaItem) -> Unit,
    onSwap: () -> Unit,
    onClose: () -> Unit,
) {
    var picker by remember { mutableStateOf<Int?>(null) }
    var chrome by remember { mutableStateOf(true) }
    var ratio by remember { mutableFloatStateOf(0.5f) }

    val safeCandidates = remember(candidates, primary, secondary) {
        if (candidates.isNotEmpty()) candidates
        else listOfNotNull(primary, secondary)
    }

    val primaryInitIndex = remember(primary.id, safeCandidates) {
        val idx = safeCandidates.indexOfFirst { it.id == primary.id }
        if (idx >= 0) idx else 0
    }
    val secondaryInitIndex = remember(secondary?.id, safeCandidates) {
        val idx = if (secondary != null) safeCandidates.indexOfFirst { it.id == secondary.id } else 0
        if (idx >= 0) idx else 0
    }

    val primaryPagerState = rememberPagerState(
        initialPage = primaryInitIndex.coerceIn(0, maxOf(0, safeCandidates.size - 1)),
    ) { safeCandidates.size }

    val secondaryPagerState = rememberPagerState(
        initialPage = secondaryInitIndex.coerceIn(0, maxOf(0, safeCandidates.size - 1)),
    ) { safeCandidates.size }

    val scope = rememberCoroutineScope()

    LaunchedEffect(primaryPagerState) {
        snapshotFlow { primaryPagerState.currentPage }
            .collect { page ->
                safeCandidates.getOrNull(page)?.let { item ->
                    if (item.id != primary.id) onPickPrimary(item)
                }
            }
    }

    LaunchedEffect(secondaryPagerState) {
        snapshotFlow { secondaryPagerState.currentPage }
            .collect { page ->
                safeCandidates.getOrNull(page)?.let { item ->
                    if (secondary != null && item.id != secondary.id) onPickSecondary(item)
                }
            }
    }

    LaunchedEffect(primary.id) {
        val idx = safeCandidates.indexOfFirst { it.id == primary.id }
        if (idx >= 0 && idx != primaryPagerState.currentPage) {
            primaryPagerState.scrollToPage(idx)
        }
    }
    LaunchedEffect(secondary?.id) {
        if (secondary != null) {
            val idx = safeCandidates.indexOfFirst { it.id == secondary.id }
            if (idx >= 0 && idx != secondaryPagerState.currentPage) {
                secondaryPagerState.scrollToPage(idx)
            }
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        val wide = maxWidth > maxHeight
        val minRatio = 0.2f
        val totalWidth = constraints.maxWidth
        val totalHeight = constraints.maxHeight

        if (wide) {
            Row(Modifier.fillMaxSize()) {
                SplitPaneView(
                    item = primary,
                    candidates = safeCandidates,
                    pagerState = primaryPagerState,
                    repository = repository,
                    chrome = chrome,
                    isTopPane = true,
                    isBottomPane = true,
                    onSingleTap = { chrome = !chrome },
                    onChange = { picker = 0 },
                    onPickItem = { idx, item ->
                        scope.launch { primaryPagerState.animateScrollToPage(idx) }
                        onPickPrimary(item)
                    },
                    modifier = Modifier
                        .weight(ratio)
                        .fillMaxHeight(),
                )
                SplitDivider(
                    vertical = false,
                    onDrag = { delta ->
                        ratio = (ratio + delta / totalWidth).coerceIn(minRatio, 1f - minRatio)
                    },
                )
                SplitPaneView(
                    item = secondary,
                    candidates = safeCandidates,
                    pagerState = if (secondary != null) secondaryPagerState else null,
                    repository = repository,
                    chrome = chrome,
                    isTopPane = true,
                    isBottomPane = true,
                    onSingleTap = { chrome = !chrome },
                    onChange = { picker = 1 },
                    onPickItem = { idx, item ->
                        scope.launch { secondaryPagerState.animateScrollToPage(idx) }
                        onPickSecondary(item)
                    },
                    modifier = Modifier
                        .weight(1f - ratio)
                        .fillMaxHeight(),
                )
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                SplitPaneView(
                    item = primary,
                    candidates = safeCandidates,
                    pagerState = primaryPagerState,
                    repository = repository,
                    chrome = chrome,
                    isTopPane = true,
                    isBottomPane = false,
                    onSingleTap = { chrome = !chrome },
                    onChange = { picker = 0 },
                    onPickItem = { idx, item ->
                        scope.launch { primaryPagerState.animateScrollToPage(idx) }
                        onPickPrimary(item)
                    },
                    modifier = Modifier
                        .weight(ratio)
                        .fillMaxWidth(),
                )
                SplitDivider(
                    vertical = true,
                    onDrag = { delta ->
                        ratio = (ratio + delta / totalHeight).coerceIn(minRatio, 1f - minRatio)
                    },
                )
                SplitPaneView(
                    item = secondary,
                    candidates = safeCandidates,
                    pagerState = if (secondary != null) secondaryPagerState else null,
                    repository = repository,
                    chrome = chrome,
                    isTopPane = false,
                    isBottomPane = true,
                    onSingleTap = { chrome = !chrome },
                    onChange = { picker = 1 },
                    onPickItem = { idx, item ->
                        scope.launch { secondaryPagerState.animateScrollToPage(idx) }
                        onPickSecondary(item)
                    },
                    modifier = Modifier
                        .weight(1f - ratio)
                        .fillMaxWidth(),
                )
            }
        }

        // Top App Bar
        AnimatedVisibility(
            visible = chrome,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.88f), Color.Transparent),
                        ),
                    )
                    .statusBarsPadding(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onClose) {
                        Icon(
                            ZarcIcons.Close,
                            contentDescription = "Close the split view",
                            tint = Color.White,
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "Split View",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = Color.White,
                        )
                        Text(
                            text = "Swipe left/right or tap mini-roll to switch",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f),
                        )
                    }
                    IconButton(onClick = onSwap, enabled = secondary != null) {
                        Icon(
                            imageVector = ZarcIcons.Swap,
                            contentDescription = "Swap the panes",
                            tint = if (secondary != null) Color.White else Color.White.copy(alpha = 0.3f),
                        )
                    }
                }
            }
        }
    }

    ImmersiveChrome(hidden = !chrome)

    val target = picker
    if (target != null) {
        val other = if (target == 0) secondary else primary
        val choices = safeCandidates.filter { it.id != other?.id }
        ZarcSheet(onDismiss = { picker = null }) {
            SheetHeader(
                title = if (target == 0) "Replace the first photo" else "Choose the second photo",
                subtitle = plural(choices.size, "item"),
            )
            if (choices.isEmpty()) {
                Text(
                    text = "The roll holds no other item yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(10.dp),
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(360.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    items(choices, key = { it.id }) { candidate ->
                        Box(
                            modifier = Modifier
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    if (target == 0) onPickPrimary(candidate) else onPickSecondary(candidate)
                                    picker = null
                                },
                        ) {
                            MediaThumbnail(
                                repository = repository,
                                item = candidate,
                                isPrivate = candidate.isPrivate,
                                contentDescription = candidate.name,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SplitDivider(
    vertical: Boolean,
    onDrag: (Float) -> Unit,
) {
    val dragModifier = Modifier.pointerInput(vertical) {
        detectDragGestures { change, amount ->
            change.consume()
            onDrag(if (vertical) amount.y else amount.x)
        }
    }
    Box(
        modifier = (if (vertical) {
            Modifier
                .fillMaxWidth()
                .height(20.dp)
        } else {
            Modifier
                .width(20.dp)
                .fillMaxHeight()
        }).then(dragModifier),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = (if (vertical) {
                Modifier
                    .width(42.dp)
                    .height(4.dp)
            } else {
                Modifier
                    .height(42.dp)
                    .width(4.dp)
            })
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = 0.4f)),
        )
    }
}

/** One pane of the split viewer, with media swiping and embedded mini-roll. */
@Composable
private fun SplitPaneView(
    item: MediaItem?,
    candidates: List<MediaItem>,
    pagerState: androidx.compose.foundation.pager.PagerState?,
    repository: GalleryRepository,
    chrome: Boolean,
    isTopPane: Boolean,
    isBottomPane: Boolean,
    onSingleTap: () -> Unit,
    onChange: () -> Unit,
    onPickItem: (Int, MediaItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val filmstripState = rememberLazyListState()

    if (pagerState != null) {
        LaunchedEffect(pagerState.currentPage) {
            filmstripState.animateScrollToItem(maxOf(0, pagerState.currentPage - 2))
        }
    }

    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        if (item == null) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(onClick = onChange)
                    .padding(24.dp),
            ) {
                Icon(
                    imageVector = ZarcIcons.Roll,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.size(44.dp),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Tap to choose a second photo",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f),
                )
            }
        } else if (pagerState != null) {
            HorizontalPager(
                state = pagerState,
                key = { candidates.getOrNull(it)?.id ?: it },
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val pageItem = candidates.getOrNull(page) ?: item
                if (pageItem.isVideo) {
                    VideoSurface(
                        item = pageItem,
                        repository = repository,
                        controlsVisible = chrome,
                        onToggleChrome = onSingleTap,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    ZoomableMedia(
                        repository = repository,
                        item = pageItem,
                        isPrivate = pageItem.isPrivate,
                        maxDimension = 1600,
                        onSingleTap = onSingleTap,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            // Chrome: Item name badge (TopStart) with proper clearance so it never hides behind top header
            AnimatedVisibility(
                visible = chrome,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .then(if (isTopPane) Modifier.statusBarsPadding().padding(top = 56.dp) else Modifier)
                    .padding(start = 12.dp, top = 8.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Black.copy(alpha = 0.65f),
                    border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.2f)),
                ) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                            .widthIn(max = 180.dp),
                    )
                }
            }

            // Chrome: Change photo button (TopEnd) with proper clearance
            AnimatedVisibility(
                visible = chrome,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .then(if (isTopPane) Modifier.statusBarsPadding().padding(top = 56.dp) else Modifier)
                    .padding(end = 12.dp, top = 8.dp),
            ) {
                Surface(
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.65f),
                    border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.2f)),
                    modifier = Modifier
                        .size(36.dp)
                        .clickable(onClick = onChange),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = ZarcIcons.Roll,
                            contentDescription = "Choose photo",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            // Chrome: Mini-Roll (BottomCenter) anchored firmly inside this pane!
            AnimatedVisibility(
                visible = chrome && candidates.size > 1,
                enter = fadeIn() + slideInVertically { it },
                exit = fadeOut() + slideOutVertically { it },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .then(if (isBottomPane) Modifier.navigationBarsPadding() else Modifier)
                    .padding(bottom = 8.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color.Black.copy(alpha = 0.72f),
                    border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.2f)),
                    modifier = Modifier.padding(horizontal = 10.dp),
                ) {
                    LazyRow(
                        state = filmstripState,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.height(48.dp),
                    ) {
                        itemsIndexed(candidates, key = { _, c -> c.id }) { index, tileItem ->
                            val isSelected = index == pagerState.currentPage
                            Box(
                                modifier = Modifier
                                    .size(if (isSelected) 38.dp else 32.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .then(
                                        if (isSelected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp))
                                        else Modifier.border(0.5.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(6.dp))
                                    )
                                    .clickable {
                                        ZarcHaptics.click(context)
                                        onPickItem(index, tileItem)
                                    },
                            ) {
                                MediaThumbnail(
                                    repository = repository,
                                    item = tileItem,
                                    isPrivate = tileItem.isPrivate,
                                    contentDescription = tileItem.name,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The settings: the security status, the recovery, the rotation and the
 * developer tools.
 */
@Composable
fun SettingsScreen(state: GalleryUiState, viewModel: GalleryViewModel) {
    var showStealthGuide by remember { mutableStateOf(false) }
    var showKeyRotationDialog by remember { mutableStateOf(false) }
    var showEmptyBinDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    if (showStealthGuide) {
        StealthGuideDialog(onDismiss = { showStealthGuide = false })
    }

    if (showKeyRotationDialog) {
        AlertDialog(
            onDismissRequest = { showKeyRotationDialog = false },
            icon = { Icon(ZarcIcons.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("Rotate Master Key") },
            text = {
                Text("Generate a new cryptographic master key and re-encrypt the vault envelope. Your media will remain intact.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.rotateVaultMasterKey()
                        showKeyRotationDialog = false
                    },
                ) {
                    Text("Rotate Key")
                }
            },
            dismissButton = {
                TextButton(onClick = { showKeyRotationDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (showEmptyBinDialog) {
        AlertDialog(
            onDismissRequest = { showEmptyBinDialog = false },
            icon = { Icon(ZarcIcons.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Empty Recycle Bin?") },
            text = {
                Text("All ${state.binnedItems.size} items in the Recycle Bin will be permanently deleted. This action cannot be undone.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.emptyBin()
                        showEmptyBinDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text("Empty Bin")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEmptyBinDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 120.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Hero Header Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .size(68.dp)
                            .background(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                                CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = ZarcIcons.Shield,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(36.dp),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "Zarc Vault",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Zero-Knowledge Military-Grade Encrypted Gallery",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ) {
                            Text(
                                text = "100% Offline",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ) {
                            Text(
                                text = "XChaCha20-Poly1305",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }
        }

        // App Security & Lock
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = ZarcIcons.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Security & App Lock",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.height(12.dp))

                    SettingsToggle(
                        title = "Biometric app lock",
                        subtitle = "Require fingerprint, face, or screen lock credentials to open Zarc",
                        checked = state.appLockEnabled,
                        onCheckedChange = viewModel::setAppLockEnabled,
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )

                    SettingsToggle(
                        title = "Stealth Dynamic PIN lock",
                        subtitle = "Require 4-digit PIN based on current time (Minute + Reversed Hour)",
                        checked = state.stealthLockEnabled,
                        onCheckedChange = viewModel::setStealthLockEnabled,
                    )

                    Spacer(Modifier.height(4.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showStealthGuide = true }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = ZarcIcons.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "How Stealth Dynamic PIN works",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }

        // Vault Storage & Management
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = ZarcIcons.Roll,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Storage & Vault",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.height(12.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Total Encrypted Media", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = "${state.roll.size} items (${state.index.albums.size} albums)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Recycle Bin", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = "${state.binnedItems.size} items pending 30-day deletion",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (state.binnedItems.isNotEmpty()) {
                            TextButton(onClick = { showEmptyBinDialog = true }) {
                                Text("Empty", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showKeyRotationDialog = true }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Rotate Master Key", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = "Re-encrypt vault key envelope with a new master key",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        // Developer Credits Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = ZarcIcons.Star,
                            contentDescription = null,
                            tint = Color(0xFFFFC44D),
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Developer Credits",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Spacer(Modifier.height(12.dp))

                    Text(
                        text = "Developed by Meet Mistry",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "Crafted with security, privacy, and performance in mind.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.height(14.dp))

                    // Email Link
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                ZarcHaptics.click(context)
                                runCatching {
                                    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:meetzarc@gmail.com"))
                                    context.startActivity(intent)
                                }
                            },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("✉", fontSize = 16.sp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Email", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("meetzarc@gmail.com", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
                            }
                            Icon(ZarcIcons.OpenWith, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // Website Link
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                ZarcHaptics.click(context)
                                runCatching {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://meetmistry.vercel.app"))
                                    context.startActivity(intent)
                                }
                            },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("🌐", fontSize = 16.sp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Portfolio / Website", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("https://meetmistry.vercel.app", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
                            }
                            Icon(ZarcIcons.OpenWith, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // GitHub Link
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                ZarcHaptics.click(context)
                                runCatching {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/MZarc"))
                                    context.startActivity(intent)
                                }
                            },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("💻", fontSize = 16.sp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("GitHub", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("Github/MZarc", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
                            }
                            Icon(ZarcIcons.OpenWith, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        // Privacy Guarantee Footer
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Zarc keeps all media encrypted on this device with zero network traffic. Your privacy is absolute.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Zarc v1.0.0",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
        }
    }
}

/** One toggle switch settings row. */
@Composable
private fun SettingsToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        androidx.compose.material3.Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}
