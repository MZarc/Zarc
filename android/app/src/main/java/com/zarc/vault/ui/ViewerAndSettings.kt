package com.zarc.vault.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zarc.vault.gallery.GalleryRepository
import com.zarc.vault.gallery.MediaItem
import java.util.Locale
import kotlinx.coroutines.Dispatchers
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
) {
    var menu by remember(item.id) { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        ZoomableMedia(
            repository = repository,
            item = item,
            isPrivate = isPrivate,
            modifier = Modifier.fillMaxSize(),
        )

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
        )
    }

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
                icon = ZarcIcons.Split,
                title = "Open in split window",
                subtitle = "See two photos at the same time",
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

/** A decoded media item with pinch to zoom and pan. */
@Composable
private fun ZoomableMedia(
    repository: GalleryRepository,
    item: MediaItem,
    isPrivate: Boolean,
    modifier: Modifier = Modifier,
) {
    var scale by remember(item.id) { mutableFloatStateOf(1f) }
    var offsetX by remember(item.id) { mutableFloatStateOf(0f) }
    var offsetY by remember(item.id) { mutableFloatStateOf(0f) }
    var bitmap by remember(item.id) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var failed by remember(item.id) { mutableStateOf(false) }

    LaunchedEffect(item.id, isPrivate) {
        val bytes = withContext(Dispatchers.IO) { repository.readThumbnail(isPrivate, item.id) }
        if (bytes == null) {
            failed = true
        } else {
            bitmap = withContext(Dispatchers.IO) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
        }
    }

    Box(modifier, contentAlignment = Alignment.Center) {
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
                    .pointerInput(item.id) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 6f)
                            offsetX += pan.x
                            offsetY += pan.y
                            if (scale == 1f) {
                                offsetX = 0f
                                offsetY = 0f
                            }
                        }
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

/**
 * Two media items next to each other.
 *
 * Each pane can be replaced from the roll, so two pictures can be compared
 * without leaving the application. The panes stack vertically in portrait and
 * sit side by side in landscape.
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

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        val wide = maxWidth > maxHeight
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                SplitPane(
                    item = primary,
                    repository = repository,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    onChange = { picker = 0 },
                )
                Box(
                    Modifier
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(Color.White.copy(alpha = 0.08f)),
                )
                SplitPane(
                    item = secondary,
                    repository = repository,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    onChange = { picker = 1 },
                )
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                SplitPane(
                    item = primary,
                    repository = repository,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    onChange = { picker = 0 },
                )
                Box(
                    Modifier
                        .height(2.dp)
                        .fillMaxWidth()
                        .background(Color.White.copy(alpha = 0.08f)),
                )
                SplitPane(
                    item = secondary,
                    repository = repository,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    onChange = { picker = 1 },
                )
            }
        }

        Box(
            Modifier
                .align(Alignment.TopCenter)
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
                    Icon(ZarcIcons.Close, contentDescription = "Close the split view", tint = Color.White)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Split view",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                    )
                    Text(
                        text = "Two photos at once",
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

/** One pane of the split viewer. */
@Composable
private fun SplitPane(
    item: MediaItem?,
    repository: GalleryRepository,
    modifier: Modifier = Modifier,
    onChange: () -> Unit,
) {
    Box(
        modifier
            .background(Color(0xFF050506)),
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
            ZoomableMedia(
                repository = repository,
                item = item,
                isPrivate = item.isPrivate,
                modifier = Modifier.fillMaxSize(),
            )
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
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
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
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
