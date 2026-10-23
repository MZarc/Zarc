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
private fun formatBytes(bytes: Long): String = when {
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
    item: MediaItem,
    isPrivate: Boolean,
    repository: GalleryRepository,
    onClose: () -> Unit,
    onOpenWith: (MediaItem) -> Unit,
    onOpenInSplit: () -> Unit,
    onDelete: () -> Unit,
    onReencrypt: () -> Unit,
    onRename: () -> Unit,
    onEditDate: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    var menu by remember(item.id) { mutableStateOf(false) }
    var chrome by remember(item.id) { mutableStateOf(true) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (item.isVideo) {
            VideoSurface(
                item = item,
                repository = repository,
                controlsVisible = chrome,
                onToggleChrome = { chrome = !chrome },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            ZoomableMedia(
                repository = repository,
                item = item,
                isPrivate = isPrivate,
                onSingleTap = { chrome = !chrome },
                modifier = Modifier.fillMaxSize(),
            )
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
                            text = item.name,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = repository.formatDate(item.takenAt),
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
                    IconButton(onClick = { menu = true }) {
                        Icon(
                            imageVector = ZarcIcons.MoreVert,
                            contentDescription = "More options",
                            tint = Color.White,
                        )
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
    }

    ImmersiveChrome(hidden = !chrome)

    if (menu) {
        ZarcSheet(onDismiss = { menu = false }) {
            SheetHeader(
                title = item.name,
                subtitle = "${repository.formatDate(item.takenAt)} · ${formatBytes(item.size)}",
            )
            Text(
                text = "${item.width} × ${item.height} · ${item.mime}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
            )
            SheetAction(
                icon = ZarcIcons.Edit,
                title = "Rename",
                subtitle = "Change the display name",
                onClick = {
                    menu = false
                    onRename()
                },
            )
            SheetAction(
                icon = ZarcIcons.DateRange,
                title = "Date and time",
                subtitle = "Change when the media was captured",
                onClick = {
                    menu = false
                    onEditDate()
                },
            )
            if (!item.isPrivate) {
                SheetAction(
                    icon = if (item.isFavorite) ZarcIcons.Star else ZarcIcons.StarBorder,
                    title = if (item.isFavorite) "Remove from favourites" else "Add to favourites",
                    subtitle = if (item.isFavorite) {
                        "Move it back to the roll"
                    } else {
                        "Move it into the favourite album"
                    },
                    tint = ZarcPalette.favourite,
                    onClick = {
                        menu = false
                        onToggleFavorite()
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
                    onOpenWith(item)
                },
            )
            SheetAction(
                icon = ZarcIcons.Shield,
                title = "Encrypt again",
                subtitle = "Re-wrap the object under a fresh data key",
                onClick = {
                    menu = false
                    onReencrypt()
                },
            )
            SheetAction(
                icon = ZarcIcons.Delete,
                title = "Delete",
                subtitle = "Move the item to the bin",
                destructive = true,
                onClick = {
                    menu = false
                    onDelete()
                },
            )
        }
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
                    .pointerInput(item.id, displayedWidth, displayedHeight) {
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

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        val wide = maxWidth > maxHeight
        val minRatio = 0.18f
        val totalWidth = constraints.maxWidth
        val totalHeight = constraints.maxHeight

        if (wide) {
            Row(Modifier.fillMaxSize()) {
                SplitPane(
                    item = primary,
                    repository = repository,
                    chrome = chrome,
                    onSingleTap = { chrome = !chrome },
                    modifier = Modifier
                        .weight(ratio)
                        .fillMaxHeight(),
                    onChange = { picker = 0 },
                )
                SplitDivider(
                    vertical = false,
                    onDrag = { delta ->
                        ratio = (ratio + delta / totalWidth).coerceIn(minRatio, 1f - minRatio)
                    },
                )
                SplitPane(
                    item = secondary,
                    repository = repository,
                    chrome = chrome,
                    onSingleTap = { chrome = !chrome },
                    modifier = Modifier
                        .weight(1f - ratio)
                        .fillMaxHeight(),
                    onChange = { picker = 1 },
                )
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                SplitPane(
                    item = primary,
                    repository = repository,
                    chrome = chrome,
                    onSingleTap = { chrome = !chrome },
                    modifier = Modifier
                        .weight(ratio)
                        .fillMaxWidth(),
                    onChange = { picker = 0 },
                )
                SplitDivider(
                    vertical = true,
                    onDrag = { delta ->
                        ratio = (ratio + delta / totalHeight).coerceIn(minRatio, 1f - minRatio)
                    },
                )
                SplitPane(
                    item = secondary,
                    repository = repository,
                    chrome = chrome,
                    onSingleTap = { chrome = !chrome },
                    modifier = Modifier
                        .weight(1f - ratio)
                        .fillMaxWidth(),
                    onChange = { picker = 1 },
                )
            }
        }

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
                            listOf(Color.Black.copy(alpha = 0.8f), Color.Transparent),
                        ),
                    )
                    .statusBarsPadding(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 2.dp),
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
                            text = "Split view",
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.White,
                        )
                        Text(
                            text = "Drag the divider to resize",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f),
                        )
                    }
                    IconButton(onClick = onSwap, enabled = secondary != null) {
                        Icon(
                            imageVector = ZarcIcons.Swap,
                            contentDescription = "Swap the panes",
                            tint = if (secondary != null) {
                                Color.White
                            } else {
                                Color.White.copy(alpha = 0.3f)
                            },
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
        val choices = candidates.filter { it.id != other?.id }
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

/** A draggable divider between the two split panes. */
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
                    .width(38.dp)
                    .height(4.dp)
            } else {
                Modifier
                    .height(38.dp)
                    .width(4.dp)
            })
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = 0.35f)),
        )
    }
}

/** One pane of the split viewer. */
@Composable
private fun SplitPane(
    item: MediaItem?,
    repository: GalleryRepository,
    chrome: Boolean,
    onSingleTap: () -> Unit,
    modifier: Modifier = Modifier,
    onChange: () -> Unit,
) {
    Box(
        modifier.background(Color(0xFF050506)),
        contentAlignment = Alignment.Center,
    ) {
        if (item == null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = ZarcIcons.Roll,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.4f),
                    modifier = Modifier.size(40.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Choose a second photo",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.6f),
                )
            }
        } else {
            if (item.isVideo) {
                VideoSurface(
                    item = item,
                    repository = repository,
                    controlsVisible = chrome,
                    onToggleChrome = onSingleTap,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                ZoomableMedia(
                    repository = repository,
                    item = item,
                    isPrivate = item.isPrivate,
                    maxDimension = 1600,
                    onSingleTap = onSingleTap,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            AnimatedVisibility(
                visible = chrome,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Row(
                    modifier = Modifier
                        .padding(10.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 9.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 160.dp),
                    )
                }
            }
        }
        AnimatedVisibility(
            visible = chrome,
            modifier = Modifier.align(Alignment.BottomEnd),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(
                modifier = Modifier
                    .padding(10.dp)
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable(onClick = onChange),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = ZarcIcons.Roll,
                    contentDescription = "Choose another item for this pane",
                    tint = Color.White,
                    modifier = Modifier.size(19.dp),
                )
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
    var report by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        report = withContext(Dispatchers.IO) {
            runCatching { viewModel.repository.vault.keystoreReport().summary() }
                .getOrElse { "unavailable" }
        }
    }

    androidx.compose.foundation.lazy.LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 136.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            SettingsCard("Security") {
                SettingsRow("Key protection", report)
                SettingsRow(
                    "Vault objects",
                    "${viewModel.repository.objectsDir.absolutePath}",
                )
                SettingsRow(
                    "Recovery",
                    if (viewModel.repository.vault.hasRecovery()) {
                        "a recovery passphrase is set"
                    } else {
                        "no recovery passphrase - a lost device means a lost vault"
                    },
                )
                SettingsRow(
                    "Private area",
                    if (state.privateAvailable) "set up" else "not set up",
                )
            }
        }
        item {
            SettingsCard("Maintenance") {
                SettingsAction("Import sample media") { viewModel.importSamples() }
                SettingsAction("Set up the private area") { viewModel.enablePrivateArea() }
                SettingsAction("Rotate the vault master key") { viewModel.rotateVaultMasterKey() }
                SettingsAction("Empty the recycle bin") { viewModel.emptyBin() }
            }
        }
        item {
            SettingsCard("About") {
                SettingsRow("Engine", "libsodium secretstream, XChaCha20-Poly1305")
                SettingsRow("Developer tools", "the Security Lab holds the on-device tests")
            }
        }
        item {
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text(
                text = "Zarc keeps your media encrypted in the application private storage. " +
                    "Nothing is uploaded, and the app holds no network permission.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A titled card of settings. */
@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    androidx.compose.material3.Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        shape = RoundedCornerShape(18.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

/** One read only settings row. */
@Composable
private fun SettingsRow(label: String, value: String) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One actionable settings row. */
@Composable
private fun SettingsAction(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
