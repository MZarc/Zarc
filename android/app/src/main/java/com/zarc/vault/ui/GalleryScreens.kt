package com.zarc.vault.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.material3.LocalContentColor
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.ui.text.font.FontWeight
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.widthIn
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zarc.vault.gallery.Album
import com.zarc.vault.gallery.GalleryRepository
import com.zarc.vault.gallery.MediaItem
import com.zarc.vault.gallery.VaultIndex
import com.zarc.vault.ui.theme.ZarcPalette
import java.util.Calendar
import kotlinx.coroutines.flow.distinctUntilChanged

/** The corner radius of a media cell. */
private val CellShape = RoundedCornerShape(8.dp)

/** The distance the floating bar keeps from the bottom edge. */
private val FloatingBarGap = 12.dp

/** How much scroll room the content leaves under the floating bar. */
private val FloatingBarRoom = 136.dp

/** Write a count with the right noun. */
internal fun plural(count: Int, noun: String): String =
    if (count == 1) "1 $noun" else "$count ${noun}s"

/** Format a video duration as `m:ss` or `h:mm:ss`. */
private fun formatDuration(milliseconds: Long): String {
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
 * The gallery shell: one action bar on top, the content of the active section
 * in the middle and a floating navigation bar over the content.
 */
/** The item or the selection that an edit dialog is acting on. */
private data class DeleteSelectionTarget(
    val ids: List<String>,
    val isPermanent: Boolean,
)

private data class EditTarget(
    val ids: List<String>,
    val initialName: String,
    val initialDate: Long,
    val isPrivate: Boolean,
    val selectionMode: Boolean,
    val item: MediaItem? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryApp(
    viewModel: GalleryViewModel,
    onImport: () -> Unit,
    onImportPrivate: () -> Unit = onImport,
    onOpenWith: (MediaItem) -> Unit,
    onUnlockPrivate: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var optionsOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var editOpen by remember { mutableStateOf(false) }
    var moveDialog by remember { mutableStateOf(false) }
    var showSelectionInfo by remember { mutableStateOf(false) }
    var newAlbum by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<EditTarget?>(null) }
    var dateTarget by remember { mutableStateOf<EditTarget?>(null) }
    var deleteConfirmItem by remember { mutableStateOf<MediaItem?>(null) }
    var deleteConfirmSelection by remember { mutableStateOf<DeleteSelectionTarget?>(null) }

    val tab = state.tab
    val openAlbum = state.albumId?.let { id -> state.index.albums.firstOrNull { it.id == id } }
    val albumOpen = tab == GalleryTab.ALBUMS && openAlbum != null
    val title = when {
        albumOpen -> openAlbum.name
        tab == GalleryTab.PHOTOS -> state.filter.title
        tab == GalleryTab.ALBUMS -> "Albums"
        tab == GalleryTab.PRIVATE -> if (state.inPrivateBin) "Private Bin" else "Private"
        tab == GalleryTab.BIN -> "Bin"
        tab == GalleryTab.ARCHIVE -> "Zarc Archive"
        else -> "Settings"
    }
    val subtitle = when {
        albumOpen -> plural(state.albumItems.size, "item")
        tab == GalleryTab.PHOTOS -> plural(state.roll.size, "item")
        tab == GalleryTab.ALBUMS -> plural(state.index.albums.size, "album")
        tab == GalleryTab.PRIVATE -> when {
            !state.privateAvailable -> "Not set up yet"
            state.inPrivateBin -> "${state.privateBinnedItems.size} items • 30 days retention"
            state.privateUnlocked -> plural(state.privateItems.size, "item")
            else -> "Protected by the device authentication"
        }
        tab == GalleryTab.BIN -> plural(state.binnedItems.size, "item")
        tab == GalleryTab.ARCHIVE -> "Custom Containers"
        else -> "Vault, security and recovery"
    }
    val selectableIds: List<String> = when {
        albumOpen -> state.albumItems.map { it.id }
        tab == GalleryTab.PHOTOS -> state.roll.map { it.id }
        tab == GalleryTab.PRIVATE -> if (state.inPrivateBin) state.privateBinnedItems.map { it.id } else state.privateItems.map { it.id }
        tab == GalleryTab.BIN -> state.binnedItems.map { it.id }
        else -> emptyList()
    }
    val editable = tab == GalleryTab.PHOTOS || albumOpen || tab == GalleryTab.PRIVATE

    // Requirement: back always returns to the roll, from the viewer, an album,
    // the private area, the bin, the settings or the search.
    BackHandler(
        enabled = state.viewerItem != null ||
            tab != GalleryTab.PHOTOS ||
            state.albumId != null ||
            state.selecting ||
            state.searchOpen ||
            (tab == GalleryTab.PRIVATE && state.inPrivateBin),
    ) {
        when {
            state.viewerItem != null -> viewModel.closeViewer()
            state.selecting -> viewModel.clearSelection()
            state.searchOpen -> viewModel.toggleSearch()
            tab == GalleryTab.PRIVATE && state.inPrivateBin -> viewModel.closePrivateBin()
            state.albumId != null -> viewModel.closeAlbum()
            else -> viewModel.goHome()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets.systemBars.only(
                WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
            ),
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                if (tab != GalleryTab.ARCHIVE) {
                    GalleryTopBar(
                    title = title,
                    subtitle = subtitle,
                    selection = state.selection,
                    selectableCount = selectableIds.size,
                    onClearSelection = viewModel::clearSelection,
                    onSelectAll = if (selectableIds.isNotEmpty()) {
                        { viewModel.selectAll(selectableIds) }
                    } else {
                        null
                    },
                    searchOpen = state.searchOpen,
                    onToggleSearch = if (tab == GalleryTab.SETTINGS || tab == GalleryTab.ARCHIVE) null else viewModel::toggleSearch,
                    query = state.query,
                    onQuery = viewModel::setQuery,
                    onBack = if (tab != GalleryTab.PHOTOS || state.albumId != null || (tab == GalleryTab.PRIVATE && state.inPrivateBin)) {
                        if (tab == GalleryTab.PRIVATE && state.inPrivateBin) {
                            { viewModel.closePrivateBin() }
                        } else if (state.albumId != null) {
                            { viewModel.closeAlbum() }
                        } else {
                            { viewModel.goHome() }
                        }
                    } else {
                        null
                    },
                    onMore = { optionsOpen = true },
                    onHeaderLongClick = if (tab == GalleryTab.PHOTOS) onUnlockPrivate else null,
                    )
                }

                AnimatedContent(
                    targetState = tab,
                    transitionSpec = {
                        (fadeIn(animationSpec = tween(220, easing = FastOutSlowInEasing)) +
                            scaleIn(initialScale = 0.97f, animationSpec = tween(220, easing = FastOutSlowInEasing)))
                            .togetherWith(
                                fadeOut(animationSpec = tween(180, easing = FastOutSlowInEasing)) +
                                scaleOut(targetScale = 0.98f, animationSpec = tween(180, easing = FastOutSlowInEasing))
                            )
                    },
                    label = "tab_transition",
                ) { targetTab ->
                    when (targetTab) {
                        GalleryTab.PHOTOS -> PhotosScreen(state, viewModel)
                        GalleryTab.ALBUMS -> AlbumsScreen(state, viewModel)
                        GalleryTab.PRIVATE -> PrivateScreen(state, viewModel, onUnlockPrivate)
                        GalleryTab.BIN -> BinScreen(state, viewModel)
                        GalleryTab.SETTINGS -> SettingsScreen(state, viewModel)
                        GalleryTab.ARCHIVE -> ArchiveScreen(
                            onBack = { viewModel.selectTab(GalleryTab.PHOTOS) },
                            viewModel = viewModel,
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = !state.selecting && tab != GalleryTab.ARCHIVE,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
        ) {
            FloatingNavBar(
                current = tab,
                onSelect = viewModel::selectTab,
                onMenu = { menuOpen = true },
            )
        }

        AnimatedVisibility(
            visible = state.selecting,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
        ) {
            SelectionBottomBar(
                tab = tab,
                inPrivateBin = (tab == GalleryTab.PRIVATE && state.inPrivateBin),
                selectionCount = state.selection.size,
                selectionAllFavorite = state.selectionAllFavorite,
                onFavorite = if (tab != GalleryTab.PRIVATE) viewModel::toggleSelectionFavorite else null,
                onDelete = {
                    deleteConfirmSelection = DeleteSelectionTarget(
                        ids = state.selection.toList(),
                        isPermanent = (tab == GalleryTab.BIN || (tab == GalleryTab.PRIVATE && state.inPrivateBin)),
                    )
                },
                onRestore = if (tab == GalleryTab.BIN) {
                    viewModel::restoreSelection
                } else if (tab == GalleryTab.PRIVATE && state.inPrivateBin) {
                    viewModel::restorePrivateSelection
                } else null,
                onMore = { editOpen = true },
            )
        }

        AnimatedVisibility(
            visible = (tab.isNavDestination || (tab == GalleryTab.PRIVATE && state.privateUnlocked)) && !state.selecting && tab != GalleryTab.ARCHIVE,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 20.dp, bottom = 104.dp),
            enter = fadeIn() + scaleIn(initialScale = 0.85f),
            exit = fadeOut() + scaleOut(targetScale = 0.85f),
        ) {
            ImportButton(onClick = if (tab == GalleryTab.PRIVATE) onImportPrivate else onImport)
        }

        // The viewer covers everything, including the floating bar.
        AnimatedVisibility(
            visible = state.viewerItem != null,
            enter = fadeIn(tween(220, easing = FastOutSlowInEasing)) + scaleIn(initialScale = 0.90f, animationSpec = tween(240, easing = FastOutSlowInEasing)),
            exit = fadeOut(tween(180, easing = FastOutSlowInEasing)) + scaleOut(targetScale = 0.92f, animationSpec = tween(200, easing = FastOutSlowInEasing)),
        ) {
            state.viewerItem?.let { item ->
                if (state.splitMode) {
                    SplitViewerScreen(
                        primary = item,
                        secondary = state.splitItem,
                        repository = viewModel.repository,
                        candidates = state.viewerCandidates,
                        onPickPrimary = viewModel::setViewerItem,
                        onPickSecondary = viewModel::setSplitItem,
                        onSwap = viewModel::swapSplitPanes,
                        onClose = viewModel::closeSplitView,
                    )
                } else {
                    val viewerList = when (tab) {
                        GalleryTab.PHOTOS -> state.roll
                        GalleryTab.ALBUMS -> state.albumItems
                        GalleryTab.PRIVATE -> state.privateItems
                        GalleryTab.BIN -> state.binnedItems
                        else -> listOf(item)
                    }.ifEmpty { listOf(item) }

                    ViewerScreen(
                        initialItem = item,
                        items = viewerList,
                        isPrivate = item.isPrivate,
                        repository = viewModel.repository,
                        onClose = viewModel::closeViewer,
                        onOpenWith = onOpenWith,
                        onOpenInSplit = viewModel::openSplitView,
                        onDelete = { target ->
                            deleteConfirmItem = target
                        },
                        onRestore = if (item.isDeleted) {
                            { target ->
                                if (target.isPrivate) {
                                    viewModel.repository.restorePrivateFromBin(listOf(target.id))
                                } else {
                                    viewModel.repository.restoreFromBin(listOf(target.id))
                                }
                                viewModel.closeViewer()
                                viewModel.refresh()
                            }
                        } else null,
                        onMoveToPrivate = if (!item.isPrivate && !item.isDeleted) {
                            { target -> viewModel.moveItemToPrivate(target) }
                        } else null,
                        onSetAsCover = if (state.albumId != null && !item.isDeleted) {
                            { target -> viewModel.setAlbumCover(state.albumId!!, target.id) }
                        } else null,
                        onReencrypt = { target -> viewModel.reencrypt(target) },
                        onRename = { target ->
                            renameTarget = EditTarget(
                                ids = listOf(target.id),
                                initialName = target.name,
                                initialDate = target.takenAt,
                                isPrivate = target.isPrivate,
                                selectionMode = false,
                                item = target,
                            )
                        },
                        onEditDate = { target ->
                            dateTarget = EditTarget(
                                ids = listOf(target.id),
                                initialName = target.name,
                                initialDate = target.takenAt,
                                isPrivate = target.isPrivate,
                                selectionMode = false,
                                item = target,
                            )
                        },
                        onToggleFavorite = { target ->
                            viewModel.setItemFavorite(target, !target.isFavorite)
                        },
                    )
                }
            }
        }
    }

    if (optionsOpen) {
        OptionsSheet(
            state = state,
            viewModel = viewModel,
            onDismiss = { optionsOpen = false },
            onImport = onImport,
            onUnlockPrivate = onUnlockPrivate,
            onNewAlbum = {
                optionsOpen = false
                newAlbum = true
            },
        )
    }
    if (menuOpen) {
        MenuSheet(
            state = state,
            onDismiss = { menuOpen = false },
            onNavigate = {
                menuOpen = false
                viewModel.selectTab(it)
            },
        )
    }
    if (editOpen) {
        SelectionEditSheet(
            count = state.selection.size,
            canMove = tab != GalleryTab.PRIVATE,
            isPrivate = tab == GalleryTab.PRIVATE,
            canSetCover = albumOpen && state.selection.size == 1,
            onDismiss = { editOpen = false },
            onShowInfo = {
                editOpen = false
                showSelectionInfo = true
            },
            onRename = {
                editOpen = false
                val first = state.index.find(state.selection.firstOrNull().orEmpty()) ?: state.privateIndex.find(state.selection.firstOrNull().orEmpty())
                renameTarget = EditTarget(
                    ids = state.selection.toList(),
                    initialName = if (state.selection.size == 1) first?.name.orEmpty() else "",
                    initialDate = first?.takenAt ?: System.currentTimeMillis(),
                    isPrivate = tab == GalleryTab.PRIVATE,
                    selectionMode = true,
                )
            },
            onEditDate = {
                editOpen = false
                val first = state.index.find(state.selection.firstOrNull().orEmpty()) ?: state.privateIndex.find(state.selection.firstOrNull().orEmpty())
                dateTarget = EditTarget(
                    ids = state.selection.toList(),
                    initialName = first?.name.orEmpty(),
                    initialDate = first?.takenAt ?: System.currentTimeMillis(),
                    isPrivate = tab == GalleryTab.PRIVATE,
                    selectionMode = true,
                )
            },
            onMove = {
                editOpen = false
                moveDialog = true
            },
            onMoveToPrivate = {
                editOpen = false
                viewModel.moveSelectionToPrivate()
            },
            onMoveOutOfPrivate = {
                editOpen = false
                viewModel.moveSelectionOutOfPrivate()
            },
            onSetAsCover = {
                editOpen = false
                val coverId = state.selection.firstOrNull()
                if (state.albumId != null && coverId != null) {
                    viewModel.setAlbumCover(state.albumId!!, coverId)
                }
            },
        )
    }
    if (showSelectionInfo) {
        val selectedItems = remember(state.selection) {
            state.selection.mapNotNull { id ->
                state.index.find(id) ?: state.privateIndex.find(id)
                    ?: state.binnedItems.find { it.id == id }
                    ?: state.privateBinnedItems.find { it.id == id }
            }
        }
        MediaInfoDialog(
            items = selectedItems,
            repository = viewModel.repository,
            onDismiss = { showSelectionInfo = false },
        )
    }

    if (moveDialog) {
        AlbumPickerDialog(
            title = "Move to album",
            albums = state.index.albums.filter { it.id != VaultIndex.ALBUM_RECENTS },
            onDismiss = { moveDialog = false },
            onPick = {
                viewModel.moveSelectionToAlbum(it)
                moveDialog = false
            },
        )
    }
    if (newAlbum) {
        NameDialog(
            title = "New album",
            initial = "",
            onDismiss = { newAlbum = false },
            onConfirm = {
                viewModel.createAlbum(it)
                newAlbum = false
            },
        )
    }
    renameTarget?.let { target ->
        NameDialog(
            title = if (target.ids.size == 1) "Rename item" else "Rename ${target.ids.size} items",
            initial = target.initialName,
            hint = if (target.ids.size > 1) {
                "A number is added before the extension of each item."
            } else {
                null
            },
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                if (target.selectionMode) {
                    viewModel.renameSelection(name)
                } else {
                    target.item?.let { viewModel.renameItem(it, name) }
                }
                renameTarget = null
            },
        )
    }
    dateTarget?.let { target ->
        DateTimeDialog(
            initial = target.initialDate,
            onDismiss = { dateTarget = null },
            onConfirm = { timestamp ->
                if (target.selectionMode) {
                    viewModel.setSelectionTakenAt(timestamp)
                } else {
                    target.item?.let { viewModel.setItemTakenAt(it, timestamp) }
                }
                dateTarget = null
            },
        )
    }

    deleteConfirmItem?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteConfirmItem = null },
            icon = {
                Icon(
                    imageVector = ZarcIcons.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = {
                Text(if (target.isDeleted) "Delete permanently?" else "Move to Recycle Bin?")
            },
            text = {
                Text(
                    if (target.isDeleted) {
                        "This item will be permanently deleted from the vault. This cannot be undone."
                    } else {
                        "This item will stay in the Recycle Bin for 30 days before being permanently deleted."
                    }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val itemToDelete = target
                        deleteConfirmItem = null
                        if (itemToDelete.isDeleted) {
                            if (itemToDelete.isPrivate) {
                                viewModel.repository.deletePrivateForever(listOf(itemToDelete.id))
                            } else {
                                viewModel.repository.deleteForever(listOf(itemToDelete.id))
                            }
                            viewModel.refresh()
                        } else {
                            viewModel.binItem(itemToDelete)
                        }
                        viewModel.closeViewer()
                    },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text(if (target.isDeleted) "Delete forever" else "Move to Bin")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmItem = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    deleteConfirmSelection?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteConfirmSelection = null },
            icon = {
                Icon(
                    imageVector = ZarcIcons.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = {
                Text(
                    if (target.isPermanent) {
                        if (target.ids.size == 1) "Delete permanently?" else "Delete ${target.ids.size} items permanently?"
                    } else {
                        if (target.ids.size == 1) "Move to Recycle Bin?" else "Move ${target.ids.size} items to Recycle Bin?"
                    }
                )
            },
            text = {
                Text(
                    if (target.isPermanent) {
                        "Selected items will be permanently erased from your encrypted storage. This cannot be undone."
                    } else {
                        "Selected items will stay in the Recycle Bin for 30 days before being permanently deleted."
                    }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val isPerm = target.isPermanent
                        deleteConfirmSelection = null
                        if (isPerm) {
                            if (tab == GalleryTab.PRIVATE) {
                                viewModel.deletePrivateSelectionForever()
                            } else {
                                viewModel.deleteSelectionForever()
                            }
                        } else {
                            if (tab == GalleryTab.PRIVATE) {
                                viewModel.movePrivateSelectionToBin()
                            } else {
                                viewModel.moveSelectionToBin()
                            }
                        }
                    },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text(if (target.isPermanent) "Delete forever" else "Move to Bin")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmSelection = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    BeautifulLoader(
        visible = state.busy,
        message = state.busyMessage,
    )
}

/** The action bar of a section; it turns into a selection bar when items are selected. */
@Composable
private fun GalleryTopBar(
    title: String,
    subtitle: String?,
    selection: Set<String>,
    selectableCount: Int = 0,
    onClearSelection: () -> Unit,
    onSelectAll: (() -> Unit)?,
    searchOpen: Boolean,
    onToggleSearch: (() -> Unit)?,
    query: String,
    onQuery: (String) -> Unit,
    onBack: (() -> Unit)?,
    onMore: (() -> Unit)?,
    onHeaderLongClick: (() -> Unit)? = null,
) {
    val selecting = selection.isNotEmpty()
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(
                start = if (onBack != null && !selecting) 4.dp else 16.dp,
                end = 8.dp,
                top = 4.dp,
                bottom = 2.dp,
            ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selecting) {
                val isAllSelected = selectableCount > 0 && selection.size >= selectableCount
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(
                            if (isAllSelected) MaterialTheme.colorScheme.primary
                            else Color.White.copy(alpha = 0.12f),
                        )
                        .border(
                            width = if (isAllSelected) 0.dp else 1.5.dp,
                            color = if (isAllSelected) Color.Transparent else Color.White.copy(alpha = 0.5f),
                            shape = CircleShape,
                        )
                        .clickable {
                            ZarcHaptics.click(context)
                            if (isAllSelected) {
                                onClearSelection()
                            } else {
                                onSelectAll?.invoke()
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (isAllSelected) {
                        Icon(
                            imageVector = ZarcIcons.Check,
                            contentDescription = "Deselect all",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }

                Spacer(Modifier.width(14.dp))

                Text(
                    text = "${selection.size} selected",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )

                IconButton(onClick = onClearSelection) {
                    Icon(ZarcIcons.Close, contentDescription = "Clear selection")
                }
            } else {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(ZarcIcons.Back, contentDescription = "Back")
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .combinedClickable(
                                enabled = onHeaderLongClick != null,
                                onClick = {},
                                onLongClick = {
                                    ZarcHaptics.longPress(context)
                                    onHeaderLongClick?.invoke()
                                },
                            ),
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                onToggleSearch?.let {
                    IconButton(onClick = it) {
                        Icon(
                            imageVector = if (searchOpen) ZarcIcons.Close else ZarcIcons.Search,
                            contentDescription = if (searchOpen) "Close the search" else "Search",
                        )
                    }
                }
                onMore?.let {
                    IconButton(onClick = it) {
                        Icon(ZarcIcons.More, contentDescription = "Sort, filter and more")
                    }
                }
            }
        }
        if (searchOpen && !selecting) {
            GallerySearchField(query, onQuery)
        }
    }
}

/** One action of the selection bar. */
@Composable
private fun TopBarAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(icon, contentDescription = label)
    }
}

/** The search field that drops under the title. */
@Composable
private fun GallerySearchField(query: String, onQuery: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
    }
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .focusRequester(focus),
        placeholder = { Text("Search by name") },
        leadingIcon = { Icon(ZarcIcons.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQuery("") }) {
                    Icon(ZarcIcons.Close, contentDescription = "Clear the search")
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(22.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            focusedBorderColor = Color.Transparent,
            unfocusedBorderColor = Color.Transparent,
        ),
    )
}

/** The floating navigation bar: the roll, the albums and the menu. */
@Composable
private fun FloatingNavBar(
    current: GalleryTab,
    onSelect: (GalleryTab) -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(bottom = FloatingBarGap),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.97f),
            shape = RoundedCornerShape(30.dp),
            shadowElevation = 20.dp,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.07f)),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                FloatingNavItem(
                    icon = ZarcIcons.Roll,
                    label = "Roll",
                    selected = current == GalleryTab.PHOTOS,
                    onClick = { onSelect(GalleryTab.PHOTOS) },
                )
                FloatingNavItem(
                    icon = ZarcIcons.Albums,
                    label = "Albums",
                    selected = current == GalleryTab.ALBUMS,
                    onClick = { onSelect(GalleryTab.ALBUMS) },
                )
                FloatingNavItem(
                    icon = ZarcIcons.Menu,
                    label = "Menu",
                    selected = !current.isNavDestination,
                    onClick = onMenu,
                )
            }
        }
    }
}

/** One destination of the floating navigation bar. */
@Composable
private fun FloatingNavItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = Modifier
            .width(84.dp)
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .width(42.dp)
                .height(26.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(if (selected) tint.copy(alpha = 0.16f) else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(21.dp),
            )
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
        )
    }
}

/** The icon only import button that hovers over the roll. */
@Composable
private fun ImportButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(56.dp)
            .shadow(16.dp, CircleShape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = ZarcIcons.Add,
            contentDescription = "Import photos and videos",
            tint = Color.White,
            modifier = Modifier.size(28.dp),
        )
    }
}

/** The roll of pictures and videos. */
@Composable
fun PhotosScreen(state: GalleryUiState, viewModel: GalleryViewModel) {
    if (state.roll.isEmpty()) {
        EmptyState(
            title = if (state.query.isEmpty()) "No photos yet" else "Nothing found",
            subtitle = if (state.query.isEmpty()) {
                "Tap the import button to protect pictures and videos."
            } else {
                "No item matches \"${state.query}\"."
            },
            icon = if (state.query.isEmpty()) ZarcIcons.Roll else ZarcIcons.Search,
        )
    } else {
        MediaGrid(
            items = state.visibleRoll,
            repository = viewModel.repository,
            isPrivate = false,
            selection = state.selection,
            columns = state.gridColumns,
            hasMore = state.hasMore,
            onColumnsChange = viewModel::setGridColumns,
            onLoadMore = viewModel::loadMore,
            onClick = { item ->
                if (state.selecting) viewModel.toggleSelection(item.id)
                else viewModel.openViewer(item)
            },
            onLongClick = { viewModel.toggleSelection(it.id) },
            onSelectBatch = viewModel::selectBatch,
            onDeselectBatch = viewModel::deselectBatch,
        )
    }
}

private sealed class GridEntry {
    data class DateHeader(
        val id: String,
        val dateText: String,
        val timestamp: Long,
        val itemIds: List<String> = emptyList(),
    ) : GridEntry()
    data class Media(val item: MediaItem) : GridEntry()
}

private fun formatDateHeader(timestamp: Long): String {
    if (timestamp <= 0L) return "Undated"
    val cal = Calendar.getInstance()
    val todayYear = cal.get(Calendar.YEAR)
    val todayDay = cal.get(Calendar.DAY_OF_YEAR)

    cal.timeInMillis = timestamp
    val itemYear = cal.get(Calendar.YEAR)
    val itemDay = cal.get(Calendar.DAY_OF_YEAR)

    return when {
        todayYear == itemYear && todayDay == itemDay -> "Today"
        todayYear == itemYear && (todayDay - itemDay) == 1 -> "Yesterday"
        todayYear == itemYear -> SimpleDateFormat("d MMMM", Locale.getDefault()).format(Date(timestamp))
        else -> SimpleDateFormat("d MMMM yyyy", Locale.getDefault()).format(Date(timestamp))
    }
}

/** The grid of media items with date headers and fast scrollbar. */
@Composable
fun MediaGrid(
    items: List<MediaItem>,
    repository: GalleryRepository,
    isPrivate: Boolean,
    selection: Set<String>,
    columns: Int,
    hasMore: Boolean,
    onColumnsChange: (Int) -> Unit,
    onLoadMore: () -> Unit,
    onClick: (MediaItem) -> Unit,
    onLongClick: (MediaItem) -> Unit,
    onSelectBatch: ((List<String>) -> Unit)? = null,
    onDeselectBatch: ((List<String>) -> Unit)? = null,
) {
    val gridState = rememberLazyGridState()
    val currentColumns by rememberUpdatedState(columns)
    var pinchZoom by remember { mutableFloatStateOf(1f) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    val entries = remember(items) {
        val list = ArrayList<GridEntry>(items.size + (items.size / 4) + 10)
        var lastDateText = ""
        var headerSeq = 0
        var currentBatch = mutableListOf<String>()
        var lastHeaderIndex = -1

        for (item in items) {
            val dateText = formatDateHeader(item.takenAt)
            if (dateText != lastDateText) {
                if (lastHeaderIndex >= 0) {
                    val prevHeader = list[lastHeaderIndex] as GridEntry.DateHeader
                    list[lastHeaderIndex] = prevHeader.copy(itemIds = currentBatch.toList())
                }
                currentBatch = mutableListOf()
                lastDateText = dateText
                lastHeaderIndex = list.size
                list.add(GridEntry.DateHeader("header_${headerSeq++}_$dateText", dateText, item.takenAt))
            }
            currentBatch.add(item.id)
            list.add(GridEntry.Media(item))
        }
        if (lastHeaderIndex >= 0) {
            val prevHeader = list[lastHeaderIndex] as GridEntry.DateHeader
            list[lastHeaderIndex] = prevHeader.copy(itemIds = currentBatch.toList())
        }
        list
    }

    LaunchedEffect(gridState, entries.size, hasMore) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .collect { last ->
                if (hasMore && last >= entries.size - 12) onLoadMore()
            }
    }
    LaunchedEffect(columns) {
        pinchZoom = 1f
    }

    val transform = rememberTransformableState { zoomChange, _, _ ->
        val next = pinchZoom * zoomChange
        when {
            next >= 1.3f -> {
                onColumnsChange((currentColumns + 1).coerceAtMost(6))
                pinchZoom = 1f
            }
            next <= 0.77f -> {
                onColumnsChange((currentColumns - 1).coerceAtLeast(2))
                pinchZoom = 1f
            }
            else -> pinchZoom = next
        }
    }

    var isDraggingBar by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(0f) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .transformable(transform),
    ) {
        val containerHeightPx = constraints.maxHeight.toFloat()

        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 2.dp, end = 2.dp, bottom = FloatingBarRoom),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(
                items = entries,
                key = { entry ->
                    when (entry) {
                        is GridEntry.DateHeader -> entry.id
                        is GridEntry.Media -> entry.item.id
                    }
                },
                span = { entry ->
                    when (entry) {
                        is GridEntry.DateHeader -> GridItemSpan(maxLineSpan)
                        is GridEntry.Media -> GridItemSpan(1)
                    }
                },
                contentType = { entry ->
                    when (entry) {
                        is GridEntry.DateHeader -> "date_header"
                        is GridEntry.Media -> "media_item"
                    }
                },
            ) { entry ->
                when (entry) {
                    is GridEntry.DateHeader -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 12.dp, top = 16.dp, bottom = 6.dp, end = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = entry.dateText,
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            if (selection.isNotEmpty()) {
                                val allDateSelected = entry.itemIds.isNotEmpty() && entry.itemIds.all { it in selection }
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (allDateSelected) MaterialTheme.colorScheme.primary
                                            else Color.White.copy(alpha = 0.12f),
                                        )
                                        .border(
                                            width = if (allDateSelected) 0.dp else 1.5.dp,
                                            color = if (allDateSelected) Color.Transparent else Color.White.copy(alpha = 0.5f),
                                            shape = CircleShape,
                                        )
                                        .clickable {
                                            ZarcHaptics.click(context)
                                            if (allDateSelected) {
                                                onDeselectBatch?.invoke(entry.itemIds)
                                            } else {
                                                onSelectBatch?.invoke(entry.itemIds)
                                            }
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (allDateSelected) {
                                        Icon(
                                            imageVector = ZarcIcons.Check,
                                            contentDescription = "Deselect date group",
                                            tint = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.size(15.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    is GridEntry.Media -> {
                        val item = entry.item
                        val selected = item.id in selection
                        val daysLeftBadge = if (item.isDeleted && item.deletedAt > 0L) {
                            val now = System.currentTimeMillis()
                            val daysPassed = ((now - item.deletedAt) / (24L * 60 * 60 * 1000L)).toInt()
                            val daysLeft = maxOf(0, 30 - daysPassed)
                            "${daysLeft}d left"
                        } else null

                        Box(
                            modifier = Modifier
                                .aspectRatio(1f)
                                .clip(CellShape)
                                .combinedClickable(
                                    onClick = {
                                        if (selection.isNotEmpty()) {
                                            ZarcHaptics.click(context)
                                        }
                                        onClick(item)
                                    },
                                    onLongClick = {
                                        ZarcHaptics.longPress(context)
                                        onLongClick(item)
                                    },
                                ),
                        ) {
                            MediaThumbnail(
                                repository = repository,
                                item = item,
                                isPrivate = isPrivate,
                                contentDescription = item.name,
                                daysLeftBadge = daysLeftBadge,
                                modifier = Modifier.fillMaxSize(),
                            )
                            if (item.isVideo) {
                                Row(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(5.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color.Black.copy(alpha = 0.55f))
                                        .padding(horizontal = 5.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = ZarcIcons.Play,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(11.dp),
                                    )
                                    if (item.durationMs > 0) {
                                        Spacer(Modifier.width(2.dp))
                                        Text(
                                            text = formatDuration(item.durationMs),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.White,
                                        )
                                    }
                                }
                            }
                            if (selected) {
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .background(
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.32f),
                                        ),
                                )
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(6.dp)
                                        .size(22.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = ZarcIcons.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(15.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Floating sticky date header with smooth lingering delay (Samsung Gallery style)
        val isScrolling = gridState.isScrollInProgress || isDraggingBar
        var showStickyDate by remember { mutableStateOf(false) }
        LaunchedEffect(gridState.isScrollInProgress, isDraggingBar) {
            if (gridState.isScrollInProgress || isDraggingBar) {
                showStickyDate = true
            } else if (showStickyDate) {
                delay(1400L)
                showStickyDate = false
            }
        }

        val currentVisibleDate by remember(entries) {
            derivedStateOf {
                val topEntry = entries.getOrNull(gridState.firstVisibleItemIndex)
                when (topEntry) {
                    is GridEntry.DateHeader -> topEntry.dateText
                    is GridEntry.Media -> formatDateHeader(topEntry.item.takenAt)
                    null -> ""
                }
            }
        }
        AnimatedVisibility(
            visible = showStickyDate && currentVisibleDate.isNotEmpty(),
            enter = fadeIn(animationSpec = tween(150)) + slideInVertically(animationSpec = tween(150)) { -it },
            exit = fadeOut(animationSpec = tween(400)) + slideOutVertically(animationSpec = tween(400)) { -it },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 10.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.94f),
                shadowElevation = 8.dp,
                border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.12f)),
            ) {
                Text(
                    text = currentVisibleDate,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }

        // Fast Scrollbar (Samsung Gallery smooth, accurate physics)
        if (entries.size > 15) {
            val totalItems = entries.size
            val topInset = 8.dp
            val bottomInset = FloatingBarRoom + 8.dp
            val thumbHeightDp = 48.dp

            // Map each grid entry to its true logical row in the grid layout
            val entryToRow = remember(entries, columns) {
                val map = IntArray(entries.size)
                var row = 0
                var colInRow = 0
                for (i in entries.indices) {
                    val entry = entries[i]
                    if (entry is GridEntry.DateHeader) {
                        if (colInRow > 0) {
                            row++
                            colInRow = 0
                        }
                        map[i] = row
                        row++
                    } else {
                        map[i] = row
                        colInRow++
                        if (colInRow >= columns) {
                            row++
                            colInRow = 0
                        }
                    }
                }
                map
            }
            val totalRows = remember(entryToRow) {
                if (entryToRow.isEmpty()) 1 else (entryToRow.last() + 1).coerceAtLeast(1)
            }

            // High precision row-level scroll fraction calculation
            val scrollFraction by remember(totalRows, entryToRow) {
                derivedStateOf {
                    if (!gridState.canScrollForward && gridState.canScrollBackward) {
                        1f
                    } else if (!gridState.canScrollBackward) {
                        0f
                    } else {
                        val layoutInfo = gridState.layoutInfo
                        val visibleItems = layoutInfo.visibleItemsInfo
                        if (visibleItems.isEmpty()) return@derivedStateOf 0f
                        val firstVisibleItem = visibleItems.first()
                        val firstIndex = firstVisibleItem.index.coerceIn(0, (entries.size - 1).coerceAtLeast(0))
                        val itemRow = entryToRow.getOrElse(firstIndex) { 0 }
                        val rowHeight = firstVisibleItem.size.height.toFloat().coerceAtLeast(1f)
                        val subRowOffset = (-firstVisibleItem.offset.y.toFloat() / rowHeight).coerceIn(0f, 1f)
                        val currentRow = itemRow.toFloat() + subRowOffset
                        (currentRow / (totalRows - 1).coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
                    }
                }
            }

            // Direct 1:1 instantaneous response during scroll or drag to eliminate initial lag & jitter
            val currentFraction = if (isDraggingBar) dragProgress else scrollFraction
            val usableHeightDp = (maxHeight - topInset - bottomInset - thumbHeightDp).coerceAtLeast(0.dp)
            val thumbOffsetDp = topInset + usableHeightDp * currentFraction

            // Active date label: during drag, based on target; during normal
            // scroll, based on the top visible item. Both sources change on
            // every scroll frame, so the label is derived instead of read
            // directly in composition.
            val activeDateLabel by remember(entries, totalItems) {
                derivedStateOf {
                    val activeItemIndex = if (isDraggingBar) {
                        (dragProgress * (totalItems - 1)).toInt()
                            .coerceIn(0, (totalItems - 1).coerceAtLeast(0))
                    } else {
                        gridState.firstVisibleItemIndex
                            .coerceIn(0, (totalItems - 1).coerceAtLeast(0))
                    }
                    when (val entry = entries.getOrNull(activeItemIndex)) {
                        is GridEntry.DateHeader -> entry.dateText
                        is GridEntry.Media -> formatDateHeader(entry.item.takenAt)
                        null -> ""
                    }
                }
            }

            // Floating date pill anchored next to scrollbar thumb
            if (isDraggingBar && activeDateLabel.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.inverseSurface,
                    shadowElevation = 8.dp,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = (-46).dp, y = (thumbOffsetDp + 6.dp).coerceAtLeast(topInset)),
                ) {
                    Text(
                        text = activeDateLabel,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                }
            }

            // Scrollbar Track & Thumb with larger hit area (36dp) and premium pill design
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = topInset, bottom = bottomInset)
                    .fillMaxHeight()
                    .width(36.dp)
                    .pointerInput(totalItems, totalRows) {
                        val thumbHeightPx = 48.dp.toPx()
                        detectVerticalDragGestures(
                            onDragStart = { offset ->
                                isDraggingBar = true
                                val usablePx = (size.height - thumbHeightPx).coerceAtLeast(1f)
                                val fraction = ((offset.y - thumbHeightPx / 2f) / usablePx).coerceIn(0f, 1f)
                                dragProgress = fraction
                                val target = (fraction * (totalItems - 1)).toInt().coerceIn(0, totalItems - 1)
                                ZarcHaptics.click(context)
                                scope.launch { gridState.scrollToItem(target) }
                            },
                            onDragEnd = { isDraggingBar = false },
                            onDragCancel = { isDraggingBar = false },
                            onVerticalDrag = { change, _ ->
                                change.consume()
                                val usablePx = (size.height - thumbHeightPx).coerceAtLeast(1f)
                                val fraction = ((change.position.y - thumbHeightPx / 2f) / usablePx).coerceIn(0f, 1f)
                                dragProgress = fraction
                                val target = (fraction * (totalItems - 1)).toInt().coerceIn(0, totalItems - 1)
                                scope.launch { gridState.scrollToItem(target) }
                            },
                        )
                    },
            ) {
                val thumbOffsetInsideTrack = usableHeightDp * currentFraction
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(y = thumbOffsetInsideTrack)
                        .padding(end = 4.dp)
                        .width(if (isDraggingBar) 7.dp else 4.dp)
                        .height(thumbHeightDp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            if (isDraggingBar) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        ),
                )
            }
        }
    }
}

/** The albums. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumsScreen(state: GalleryUiState, viewModel: GalleryViewModel) {
    var renaming by remember { mutableStateOf<Album?>(null) }
    var actionAlbum by remember { mutableStateOf<Album?>(null) }
    var chooseCoverAlbum by remember { mutableStateOf<Album?>(null) }
    var deleteConfirmAlbum by remember { mutableStateOf<Album?>(null) }
    val openAlbum = state.albumId?.let { id -> state.index.albums.firstOrNull { it.id == id } }

    if (openAlbum != null) {
        if (state.albumItems.isEmpty()) {
            EmptyState(
                title = if (state.query.isEmpty()) "This album is empty" else "Nothing found",
                subtitle = if (state.query.isEmpty()) {
                    "Add media to it with More > Move to album."
                } else {
                    "No item matches \"${state.query}\"."
                },
                icon = ZarcIcons.Albums,
            )
        } else {
            MediaGrid(
                items = state.visibleAlbumItems,
                repository = viewModel.repository,
                isPrivate = false,
                selection = state.selection,
                columns = state.gridColumns,
                hasMore = state.hasMore,
                onColumnsChange = viewModel::setGridColumns,
                onLoadMore = viewModel::loadMore,
                onClick = { item ->
                    if (state.selecting) viewModel.toggleSelection(item.id)
                    else viewModel.openViewer(item)
                },
                onLongClick = { viewModel.toggleSelection(it.id) },
                onSelectBatch = viewModel::selectBatch,
                onDeselectBatch = viewModel::deselectBatch,
            )
        }
        return
    }

    val needle = state.query.trim()
    val albums = state.index.albums.filter {
        needle.isEmpty() || it.name.contains(needle, ignoreCase = true)
    }

    if (albums.isEmpty()) {
        EmptyState(
            title = if (needle.isEmpty()) "No albums yet" else "Nothing found",
            subtitle = if (needle.isEmpty()) {
                "Create an album from the menu to group your media."
            } else {
                "No album matches \"$needle\"."
            },
            icon = ZarcIcons.Albums,
        )
    } else {
        val context = androidx.compose.ui.platform.LocalContext.current
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, FloatingBarRoom),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(albums, key = { it.id }) { album ->
                val members = state.index.inAlbum(album.id)
                val coverItem = album.coverId?.let { cid -> state.index.find(cid) } ?: members.firstOrNull()
                AlbumCard(
                    album = album,
                    count = members.size,
                    cover = coverItem,
                    repository = viewModel.repository,
                    onClick = { viewModel.openAlbum(album.id) },
                    onLongClick = if (album.system) null else ({ actionAlbum = album }),
                )
            }
        }
    }

    actionAlbum?.let { album ->
        val members = state.index.inAlbum(album.id)
        ZarcSheet(
            onDismiss = { actionAlbum = null },
        ) {
            SheetHeader(album.name, plural(members.size, "item"))
            SheetAction(
                icon = ZarcIcons.Edit,
                title = "Rename album",
                subtitle = "Change the name of this album",
                onClick = {
                    val target = album
                    actionAlbum = null
                    renaming = target
                },
            )
            SheetAction(
                icon = ZarcIcons.Albums,
                title = "Change cover image",
                subtitle = "Choose a photo as album cover",
                onClick = {
                    val target = album
                    actionAlbum = null
                    chooseCoverAlbum = target
                },
            )
            SheetAction(
                icon = ZarcIcons.Delete,
                title = "Delete album",
                subtitle = "Photos and videos inside will remain safe in your photos",
                destructive = true,
                onClick = {
                    val target = album
                    actionAlbum = null
                    deleteConfirmAlbum = target
                },
            )
        }
    }

    chooseCoverAlbum?.let { album ->
        val members = state.index.inAlbum(album.id)
        ZarcSheet(onDismiss = { chooseCoverAlbum = null }) {
            SheetHeader("Change cover image", album.name)
            if (members.isEmpty()) {
                Text(
                    text = "This album contains no photos yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(340.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                    contentPadding = PaddingValues(8.dp),
                ) {
                    items(members, key = { it.id }) { item ->
                        Box(
                            modifier = Modifier
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    viewModel.setAlbumCover(album.id, item.id)
                                    chooseCoverAlbum = null
                                },
                        ) {
                            MediaThumbnail(
                                repository = viewModel.repository,
                                item = item,
                                isPrivate = false,
                                contentDescription = item.name,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }
    }

    deleteConfirmAlbum?.let { album ->
        AlertDialog(
            onDismissRequest = { deleteConfirmAlbum = null },
            icon = {
                Icon(
                    imageVector = ZarcIcons.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = { Text("Delete album?") },
            text = {
                Text("Delete \"${album.name}\"? Photos and videos inside this album will not be deleted and will remain in your photos.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        val id = album.id
                        deleteConfirmAlbum = null
                        viewModel.deleteAlbum(id)
                    },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmAlbum = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    renaming?.let { album ->
        NameDialog(
            title = "Rename album",
            initial = album.name,
            onDismiss = { renaming = null },
            onConfirm = {
                viewModel.renameAlbum(album.id, it)
                renaming = null
            },
        )
    }

    BeautifulLoader(
        visible = state.busy,
        message = state.busyMessage,
    )
}

/** One album card. */
@Composable
private fun AlbumCard(
    album: Album,
    count: Int,
    cover: MediaItem?,
    repository: GalleryRepository,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    ZarcHaptics.click(context)
                    onClick()
                },
                onLongClick = if (onLongClick != null) {
                    {
                        ZarcHaptics.longPress(context)
                        onLongClick()
                    }
                } else null,
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.35f)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                if (cover != null) {
                    MediaThumbnail(
                        repository = repository,
                        item = cover,
                        isPrivate = false,
                        contentDescription = album.name,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        imageVector = when (album.id) {
                            VaultIndex.ALBUM_FAVOURITES -> ZarcIcons.Star
                            VaultIndex.ALBUM_RECENTS -> ZarcIcons.Roll
                            else -> ZarcIcons.Albums
                        },
                        contentDescription = null,
                        tint = when (album.id) {
                            VaultIndex.ALBUM_FAVOURITES -> ZarcPalette.favourite
                            VaultIndex.ALBUM_RECENTS -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.align(Alignment.Center).size(32.dp),
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = album.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = plural(count, "item"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** The private area, behind the biometric gate. */
@Composable
fun PrivateScreen(
    state: GalleryUiState,
    viewModel: GalleryViewModel,
    onUnlock: () -> Unit,
) {
    when {
        !state.privateAvailable -> Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = ZarcIcons.Lock,
                contentDescription = null,
                tint = ZarcPalette.privateArea,
                modifier = Modifier.size(56.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text("Private Vault", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Hidden, double-encrypted vault protected by hardware biometrics.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "This vault is bound to your device's biometrics. " +
                    "Removing every biometric or resetting the device makes it unrecoverable.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onUnlock) {
                Icon(ZarcIcons.Shield, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Set up Private Vault")
            }
        }

        !state.privateUnlocked -> Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = ZarcIcons.Lock,
                contentDescription = null,
                tint = ZarcPalette.privateArea,
                modifier = Modifier.size(56.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text("Private Vault is locked", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Authenticate with biometrics to open.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onUnlock) {
                Icon(ZarcIcons.Lock, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Unlock Private Vault")
            }
        }

        state.inPrivateBin -> Column(Modifier.fillMaxSize()) {
            if (state.privateBinnedItems.isEmpty()) {
                EmptyState("Private bin is empty", "Deleted private items stay here for 30 days before permanent deletion.", icon = ZarcIcons.Bin)
            } else {
                MediaGrid(
                    items = state.visiblePrivateBinnedItems,
                    repository = viewModel.repository,
                    isPrivate = true,
                    selection = state.selection,
                    columns = state.gridColumns,
                    hasMore = state.hasMore,
                    onColumnsChange = viewModel::setGridColumns,
                    onLoadMore = viewModel::loadMore,
                    onClick = { item ->
                        if (state.selecting) viewModel.toggleSelection(item.id)
                        else viewModel.openViewer(item)
                    },
                    onLongClick = { viewModel.toggleSelection(it.id) },
                    onSelectBatch = viewModel::selectBatch,
                    onDeselectBatch = viewModel::deselectBatch,
                )
            }
        }

        state.privateItems.isEmpty() -> Column(Modifier.fillMaxSize()) {
            EmptyState(
                title = "Private vault is empty",
                subtitle = "Confidential photos moved here are secured behind hardware biometrics.",
                icon = ZarcIcons.Shield,
            )
        }

        else -> Column(Modifier.fillMaxSize()) {
            MediaGrid(
                items = state.visiblePrivateItems,
                repository = viewModel.repository,
                isPrivate = true,
                selection = state.selection,
                columns = state.gridColumns,
                hasMore = state.hasMore,
                onColumnsChange = viewModel::setGridColumns,
                onLoadMore = viewModel::loadMore,
                onClick = { item ->
                    if (state.selecting) viewModel.toggleSelection(item.id)
                    else viewModel.openViewer(item)
                },
                onLongClick = { viewModel.toggleSelection(it.id) },
                onSelectBatch = viewModel::selectBatch,
                onDeselectBatch = viewModel::deselectBatch,
            )
        }
    }
}

/** The recycle bin. */
@Composable
fun BinScreen(state: GalleryUiState, viewModel: GalleryViewModel) {
    if (state.binnedItems.isEmpty()) {
        EmptyState("Recycle bin is clean", "Deleted items stay here for 30 days before being permanently deleted.", icon = ZarcIcons.Bin)
    } else {
        MediaGrid(
            items = state.visibleBinnedItems,
            repository = viewModel.repository,
            isPrivate = false,
            selection = state.selection,
            columns = state.gridColumns,
            hasMore = state.hasMore,
            onColumnsChange = viewModel::setGridColumns,
            onLoadMore = viewModel::loadMore,
            onClick = { item ->
                if (state.selecting) viewModel.toggleSelection(item.id)
                else viewModel.openViewer(item)
            },
            onLongClick = { viewModel.toggleSelection(it.id) },
            onSelectBatch = viewModel::selectBatch,
            onDeselectBatch = viewModel::deselectBatch,
        )
    }
}

/** Hallmark-crafted empty state: contextual icon in circular surface, clear headline and explanation. */
@Composable
private fun EmptyState(
    title: String,
    subtitle: String,
    icon: ImageVector? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp)
            .padding(bottom = FloatingBarRoom),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(30.dp),
                )
            }
            Spacer(Modifier.height(18.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.widthIn(max = 300.dp),
        )
    }
}

/** The sheet behind the three dot button: sorting, filtering and bulk actions. */
@Composable
private fun OptionsSheet(
    state: GalleryUiState,
    viewModel: GalleryViewModel,
    onDismiss: () -> Unit,
    onImport: () -> Unit,
    onUnlockPrivate: () -> Unit,
    onNewAlbum: () -> Unit,
) {
    ZarcSheet(onDismiss = onDismiss) {
        when (state.tab) {
            GalleryTab.PHOTOS -> {
                SheetHeader("Sort & filter", plural(state.roll.size, "item"))
                SheetLabel("Sort by")
                MediaSort.entries.forEach { sort ->
                    SheetAction(
                        icon = ZarcIcons.Sort,
                        title = sort.title,
                        selected = state.sort == sort,
                        onClick = {
                            viewModel.setSort(sort)
                            onDismiss()
                        },
                    )
                }
                Spacer(Modifier.height(6.dp))
                SheetLabel("Show")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MediaFilter.entries.forEach { entry ->
                        FilterChip(
                            selected = state.filter == entry,
                            onClick = {
                                viewModel.setFilter(entry)
                                onDismiss()
                            },
                            label = { Text(entry.title) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ),
                        )
                    }
                }
                SheetDivider()
                SheetAction(
                    icon = ZarcIcons.SelectAll,
                    title = "Select all",
                    subtitle = "Pick every visible item",
                    onClick = {
                        viewModel.selectAll(state.roll.map { it.id })
                        onDismiss()
                    },
                )
                SheetAction(
                    icon = ZarcIcons.Import,
                    title = "Import media",
                    subtitle = "Add pictures and videos to the vault",
                    onClick = {
                        onDismiss()
                        onImport()
                    },
                )
            }

            GalleryTab.ALBUMS -> {
                val album = state.albumId?.let { id -> state.index.albums.firstOrNull { it.id == id } }
                if (album != null) {
                    SheetHeader(album.name, plural(state.albumItems.size, "item"))
                    SheetLabel("Sort by")
                    MediaSort.entries.forEach { sort ->
                        SheetAction(
                            icon = ZarcIcons.Sort,
                            title = sort.title,
                            selected = state.sort == sort,
                            onClick = {
                                viewModel.setSort(sort)
                                onDismiss()
                            },
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    SheetLabel("Show")
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MediaFilter.entries.forEach { entry ->
                            FilterChip(
                                selected = state.filter == entry,
                                onClick = {
                                    viewModel.setFilter(entry)
                                    onDismiss()
                                },
                                label = { Text(entry.title) },
                            )
                        }
                    }
                    SheetDivider()
                    SheetAction(
                        icon = ZarcIcons.SelectAll,
                        title = "Select all",
                        subtitle = "Pick every visible item",
                        onClick = {
                            viewModel.selectAll(state.albumItems.map { it.id })
                            onDismiss()
                        },
                    )
                } else {
                    SheetHeader("Albums", plural(state.index.albums.size, "album"))
                    SheetAction(
                        icon = ZarcIcons.Add,
                        title = "New album",
                        subtitle = "Group the media of the vault",
                        onClick = onNewAlbum,
                    )
                    SheetAction(
                        icon = ZarcIcons.Import,
                        title = "Import media",
                        subtitle = "Add pictures and videos to the vault",
                        onClick = {
                            onDismiss()
                            onImport()
                        },
                    )
                }
            }

            GalleryTab.PRIVATE -> {
                if (state.inPrivateBin) {
                    SheetHeader("Private Recycle Bin", plural(state.privateBinnedItems.size, "item"))
                    if (state.privateBinnedItems.isNotEmpty()) {
                        SheetAction(
                            icon = ZarcIcons.EmptyBin,
                            title = "Empty private bin",
                            subtitle = "Delete all private items forever",
                            destructive = true,
                            onClick = {
                                viewModel.emptyPrivateBin()
                                onDismiss()
                            },
                        )
                    }
                    SheetAction(
                        icon = ZarcIcons.SelectAll,
                        title = "Select all",
                        subtitle = "Pick every visible item",
                        onClick = {
                            viewModel.selectAll(state.privateBinnedItems.map { it.id })
                            onDismiss()
                        },
                    )
                } else {
                    SheetHeader("Private Vault", if (state.privateUnlocked) "Unlocked" else "Locked")
                    if (state.privateUnlocked) {
                        SheetAction(
                            icon = ZarcIcons.Bin,
                            title = "Recycle Bin",
                            subtitle = if (state.privateBinnedItems.isEmpty()) "Empty" else "${state.privateBinnedItems.size} items",
                            tint = ZarcPalette.bin,
                            chevron = true,
                            onClick = {
                                viewModel.openPrivateBin()
                                onDismiss()
                            },
                        )
                        SheetAction(
                            icon = ZarcIcons.Lock,
                            title = "Lock Private Vault",
                            subtitle = "Drop key and hide private items immediately",
                            tint = ZarcPalette.privateArea,
                            onClick = {
                                viewModel.lockPrivateArea()
                                onDismiss()
                            },
                        )
                        SheetAction(
                            icon = ZarcIcons.SelectAll,
                            title = "Select all",
                            subtitle = "Pick every visible item",
                            onClick = {
                                viewModel.selectAll(state.privateItems.map { it.id })
                                onDismiss()
                            },
                        )
                    } else {
                        SheetAction(
                            icon = ZarcIcons.Lock,
                            title = "Unlock Private Vault",
                            subtitle = "Authenticate with biometrics to unlock",
                            tint = ZarcPalette.privateArea,
                            onClick = {
                                onDismiss()
                                onUnlockPrivate()
                            },
                        )
                    }
                }
            }

            GalleryTab.BIN -> {
                SheetHeader("Bin", plural(state.binnedItems.size, "item"))
                SheetAction(
                    icon = ZarcIcons.EmptyBin,
                    title = "Empty the bin",
                    subtitle = "Delete every item for good",
                    destructive = true,
                    onClick = {
                        viewModel.emptyBin()
                        onDismiss()
                    },
                )
                SheetAction(
                    icon = ZarcIcons.SelectAll,
                    title = "Select all",
                    subtitle = "Pick every visible item",
                    onClick = {
                        viewModel.selectAll(state.binnedItems.map { it.id })
                        onDismiss()
                    },
                )
            }

            GalleryTab.ARCHIVE -> {
                SheetHeader("Archive", "Custom photo containers")
            }
            GalleryTab.SETTINGS -> {
                SheetHeader("Vault actions", "Maintenance of the sealed store")
                SheetAction(
                    icon = ZarcIcons.Shield,
                    title = "Rotate the vault master key",
                    subtitle = "Re-wrap every object under a fresh key",
                    onClick = {
                        viewModel.rotateVaultMasterKey()
                        onDismiss()
                    },
                )
                SheetAction(
                    icon = ZarcIcons.EmptyBin,
                    title = "Empty the recycle bin",
                    subtitle = "Delete every binned item for good",
                    destructive = true,
                    onClick = {
                        viewModel.emptyBin()
                        onDismiss()
                    },
                )
                SheetAction(
                    icon = ZarcIcons.Import,
                    title = "Import sample media",
                    subtitle = "Add synthetic pictures for a quick look",
                    onClick = {
                        viewModel.importSamples()
                        onDismiss()
                    },
                )
            }
        }
    }
}

/** The sheet behind the menu destination of the floating bar. */
@Composable
private fun MenuSheet(
    state: GalleryUiState,
    onDismiss: () -> Unit,
    onNavigate: (GalleryTab) -> Unit,
) {
    ZarcSheet(onDismiss = onDismiss) {
        SheetHeader("Menu", "Bin, settings and tools")
        SheetAction(
            icon = ZarcIcons.Shield,
            title = "Disguised Archive",
            subtitle = "Covert JPEG containers & extractor",
            tint = MaterialTheme.colorScheme.primary,
            chevron = true,
            onClick = { onNavigate(GalleryTab.ARCHIVE) },
        )
        SheetAction(
            icon = ZarcIcons.Bin,
            title = "Bin",
            subtitle = if (state.binnedItems.isEmpty()) "Empty" else plural(state.binnedItems.size, "item"),
            tint = ZarcPalette.bin,
            chevron = true,
            onClick = { onNavigate(GalleryTab.BIN) },
        )
        SheetAction(
            icon = ZarcIcons.Settings,
            title = "Settings",
            subtitle = "Security, recovery and maintenance",
            chevron = true,
            onClick = { onNavigate(GalleryTab.SETTINGS) },
        )
    }
}

/** The shared bottom sheet container with scroll affordance & indicator. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ZarcSheet(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        dragHandle = { BottomSheetDefaults.DragHandle(color = MaterialTheme.colorScheme.outline) },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .navigationBarsPadding(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(start = 12.dp, end = 14.dp, bottom = 28.dp),
                ) {
                content()
            }

            // Right edge scrollbar thumb when content is scrollable. The
            // scroll position changes on every frame, so the fraction is
            // derived instead of read directly in composition.
            val scrollbarFraction by remember {
                derivedStateOf {
                    if (scrollState.maxValue <= 0) null
                    else (scrollState.value.toFloat() / scrollState.maxValue.toFloat())
                        .coerceIn(0f, 1f)
                }
            }
            if (scrollbarFraction != null) {
                BoxWithConstraints(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .fillMaxHeight()
                        .padding(end = 4.dp, top = 4.dp, bottom = 4.dp),
                ) {
                    val trackHeight = maxHeight
                    val thumbHeight = 36.dp
                    val usableHeight = (trackHeight - thumbHeight).coerceAtLeast(0.dp)
                    val thumbOffset = usableHeight * (scrollbarFraction ?: 0f)

                    Box(
                        modifier = Modifier
                            .offset(y = thumbOffset)
                            .width(4.dp)
                            .height(thumbHeight)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)),
                    )
                }
            }

            // Bottom gradient fade & "Scroll for more ▾" floating cue
            androidx.compose.animation.AnimatedVisibility(
                visible = scrollState.canScrollForward,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
                                    MaterialTheme.colorScheme.surfaceContainerHigh,
                                )
                            )
                        )
                        .padding(bottom = 6.dp, top = 18.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        onClick = {
                            scope.launch {
                                scrollState.animateScrollTo((scrollState.value + 280).coerceAtMost(scrollState.maxValue))
                            }
                        },
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.95f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                        shadowElevation = 3.dp,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "Scroll for more",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Icon(
                                imageVector = ZarcIcons.Chevron,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .size(13.dp)
                                    .graphicsLayer { rotationZ = 90f },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The title block of a sheet. */
@Composable
internal fun SheetHeader(title: String, subtitle: String? = null) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 10.dp, top = 2.dp, bottom = 12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A small label above a group of sheet actions. */
@Composable
private fun SheetLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 10.dp, top = 6.dp, bottom = 4.dp),
    )
}

/** A hairline between two groups of sheet actions. */
@Composable
private fun SheetDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/** One row of a sheet. */
@Composable
internal fun SheetAction(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    tint: Color = MaterialTheme.colorScheme.primary,
    selected: Boolean = false,
    destructive: Boolean = false,
    chevron: Boolean = false,
    onClick: () -> Unit,
) {
    val accent = if (destructive) MaterialTheme.colorScheme.error else tint
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(21.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        when {
            selected -> Icon(
                imageVector = ZarcIcons.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )

            chevron -> Icon(
                imageVector = ZarcIcons.Chevron,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** A dialog that asks for a name. */
@Composable
fun NameDialog(
    title: String,
    initial: String,
    hint: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                )
                if (hint != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (value.isNotBlank()) onConfirm(value.trim()) }) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A dialog that lists the albums. */
@Composable
fun AlbumPickerDialog(
    title: String,
    albums: List<Album>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.height(280.dp)) {
                items(albums) { album ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(album.id) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(album.name, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The sheet that groups the edit actions of a selection. */
@Composable
private fun SelectionEditSheet(
    count: Int,
    canMove: Boolean,
    isPrivate: Boolean = false,
    canSetCover: Boolean = false,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onEditDate: () -> Unit,
    onMove: () -> Unit,
    onShowInfo: () -> Unit,
    onMoveToPrivate: (() -> Unit)? = null,
    onMoveOutOfPrivate: (() -> Unit)? = null,
    onSetAsCover: (() -> Unit)? = null,
) {
    ZarcSheet(onDismiss = onDismiss) {
        SheetHeader("More options", plural(count, "item selected"))
        if (!isPrivate && onMoveToPrivate != null) {
            SheetAction(
                icon = ZarcIcons.Lock,
                title = "Move to Private Vault",
                subtitle = "Double-encrypt and hide selected items",
                onClick = onMoveToPrivate,
            )
        }
        if (isPrivate && onMoveOutOfPrivate != null) {
            SheetAction(
                icon = ZarcIcons.OpenWith,
                title = "Move to Photos",
                subtitle = "Move selected items back to public roll",
                onClick = onMoveOutOfPrivate,
            )
        }
        if (canMove) {
            SheetAction(
                icon = ZarcIcons.Move,
                title = "Move to album",
                subtitle = "Group the selected items into an album",
                onClick = onMove,
            )
        }
        SheetAction(
            icon = ZarcIcons.Edit,
            title = "Rename",
            subtitle = if (count == 1) "Change the display name" else "Batch rename selected items",
            onClick = onRename,
        )
        SheetAction(
            icon = ZarcIcons.DateRange,
            title = "Date and time",
            subtitle = "Change when the media was captured",
            onClick = onEditDate,
        )
        SheetAction(
            icon = ZarcIcons.Info,
            title = "Show info",
            subtitle = "View details and resolution of selected media",
            onClick = onShowInfo,
        )
        if (canSetCover && onSetAsCover != null) {
            SheetAction(
                icon = ZarcIcons.Albums,
                title = "Set as album cover",
                subtitle = "Use this photo as album thumbnail",
                onClick = onSetAsCover,
            )
        }
    }
}

@Composable
private fun SelectionBottomBar(
    tab: GalleryTab,
    inPrivateBin: Boolean,
    selectionCount: Int,
    selectionAllFavorite: Boolean,
    onFavorite: (() -> Unit)?,
    onDelete: () -> Unit,
    onRestore: (() -> Unit)?,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(bottom = FloatingBarGap),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = Color(0xFF222226),
            contentColor = Color.White,
            shape = RoundedCornerShape(30.dp),
            shadowElevation = 24.dp,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (tab == GalleryTab.BIN || inPrivateBin) {
                    SelectionBottomItem(
                        icon = ZarcIcons.Restore,
                        label = "Restore",
                        tint = Color.White,
                        onClick = { onRestore?.invoke() },
                    )
                    SelectionBottomItem(
                        icon = ZarcIcons.Delete,
                        label = "Delete forever",
                        tint = MaterialTheme.colorScheme.error,
                        onClick = onDelete,
                    )
                } else {
                    if (tab != GalleryTab.PRIVATE && onFavorite != null) {
                        SelectionBottomItem(
                            icon = if (selectionAllFavorite) ZarcIcons.Star else ZarcIcons.StarBorder,
                            label = "Favourite",
                            tint = if (selectionAllFavorite) ZarcPalette.favourite else Color.White,
                            onClick = onFavorite,
                        )
                    }
                    SelectionBottomItem(
                        icon = ZarcIcons.Delete,
                        label = "Delete",
                        tint = MaterialTheme.colorScheme.error,
                        onClick = onDelete,
                    )
                    SelectionBottomItem(
                        icon = ZarcIcons.More,
                        label = "More",
                        tint = Color.White,
                        onClick = onMore,
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectionBottomItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color = Color.White,
    onClick: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(
        modifier = Modifier
            .widthIn(min = 72.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable {
                ZarcHaptics.click(context)
                onClick()
            }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp),
            color = tint,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            maxLines = 2,
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

/** A native date picker followed by a native time picker. */
@Composable
fun DateTimeDialog(
    initial: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    val context = LocalContext.current
    val seed = remember(initial) {
        Calendar.getInstance().apply {
            timeInMillis = if (initial > 0L) initial else System.currentTimeMillis()
        }
    }
    DisposableEffect(context, seed) {
        var finished = false
        var timeDialog: TimePickerDialog? = null
        fun cancel() {
            if (!finished) {
                finished = true
                onDismiss()
            }
        }
        fun confirm(timestamp: Long) {
            if (!finished) {
                finished = true
                onConfirm(timestamp)
            }
        }
        val chosen = (seed.clone() as Calendar)
        val dateDialog = DatePickerDialog(
            context,
            { _, year, month, day ->
                chosen.set(Calendar.YEAR, year)
                chosen.set(Calendar.MONTH, month)
                chosen.set(Calendar.DAY_OF_MONTH, day)
                timeDialog = TimePickerDialog(
                    context,
                    { _, hour, minute ->
                        chosen.set(Calendar.HOUR_OF_DAY, hour)
                        chosen.set(Calendar.MINUTE, minute)
                        chosen.set(Calendar.SECOND, 0)
                        chosen.set(Calendar.MILLISECOND, 0)
                        confirm(chosen.timeInMillis)
                    },
                    chosen.get(Calendar.HOUR_OF_DAY),
                    chosen.get(Calendar.MINUTE),
                    true,
                ).apply {
                    setOnCancelListener { cancel() }
                    show()
                }
            },
            chosen.get(Calendar.YEAR),
            chosen.get(Calendar.MONTH),
            chosen.get(Calendar.DAY_OF_MONTH),
        )
        dateDialog.setOnCancelListener { cancel() }
        dateDialog.show()
        onDispose {
            dateDialog.dismiss()
            timeDialog?.dismiss()
            finished = true
        }
    }
}

/** A beautiful full-screen loader overlay for vault operations. */
@Composable
fun BeautifulLoader(visible: Boolean, message: String? = null) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xCC000000))
                .clickable(enabled = false) {},
            contentAlignment = Alignment.Center,
        ) {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                elevation = CardDefaults.cardElevation(defaultElevation = 12.dp),
                modifier = Modifier
                    .padding(32.dp)
                    .widthIn(min = 220.dp, max = 320.dp),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(46.dp),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 4.dp,
                    )
                    Spacer(Modifier.height(18.dp))
                    Text(
                        text = "Zarc Vault",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = message ?: "Processing...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** Detailed explanation of the stealth PIN calculation, shown in the Private screen and Private options. */
@Composable
fun StealthGuideDialog(onDismiss: () -> Unit) {
    val now = Calendar.getInstance()
    val min = now.get(Calendar.MINUTE)
    val h24 = now.get(Calendar.HOUR_OF_DAY)
    val mm = "%02d".format(min)
    val h24Rev = "%02d".format(h24).reversed()
    val samplePin = "$mm$h24Rev"

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(ZarcIcons.Shield, contentDescription = null, tint = ZarcPalette.privateArea) },
        title = { Text("Stealth Dynamic PIN") },
        text = {
            Column {
                Text(
                    text = "Double protection for Private Vault. When enabled, after biometrics succeed, you must enter a dynamically changing 4-digit PIN based on the current time:",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            text = "Formula: MM + Reversed(HH)",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "• First 2 digits: Current Minute (MM)\n• Last 2 digits: Reversed Hour (HH)",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Right now (%02d:%02d):\n• Minute: %s\n• Hour: %02d (reversed: %s)\nCurrent PIN: %s".format(
                        h24, min, mm, h24, h24Rev, samplePin
                    ),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Got it")
            }
        },
    )
}

/** A stealth dynamic PIN keypad dialog for double protection on Private Vault. */
@Composable
fun StealthLockDialog(
    onDismiss: () -> Unit,
    onVerified: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    fun checkPin(entered: String) {
        if (entered.length < 4) return
        val now = Calendar.getInstance()
        val candidates = listOf(
            now.clone() as Calendar,
            (now.clone() as Calendar).apply { add(Calendar.MINUTE, -1) },
            (now.clone() as Calendar).apply { add(Calendar.MINUTE, 1) },
        )
        var valid = false
        for (cal in candidates) {
            val min = cal.get(Calendar.MINUTE)
            val h24 = cal.get(Calendar.HOUR_OF_DAY)
            val h12 = cal.get(Calendar.HOUR).let { if (it == 0) 12 else it }
            val mm = "%02d".format(min)
            val h24Rev = "%02d".format(h24).reversed()
            val h12Rev = "%02d".format(h12).reversed()
            if (entered == "$mm$h24Rev" || entered == "$mm$h12Rev") {
                valid = true
                break
            }
        }
        if (valid) {
            ZarcHaptics.success(context)
            onVerified()
        } else {
            ZarcHaptics.error(context)
            error = "Incorrect PIN. Try again."
            pin = ""
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = ZarcIcons.Lock,
                    contentDescription = null,
                    tint = ZarcPalette.privateArea,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Stealth Lock",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Double protection for Private Vault",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))

                // PIN indicator dots
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    repeat(4) { index ->
                        val filled = index < pin.length
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(
                                    if (filled) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceContainerHighest,
                                ),
                        )
                    }
                }

                if (error != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = error!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Spacer(Modifier.height(20.dp))

                val keys = listOf(
                    listOf("1", "2", "3"),
                    listOf("4", "5", "6"),
                    listOf("7", "8", "9"),
                    listOf("C", "0", "⌫"),
                )
                keys.forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        row.forEach { key ->
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier
                                    .size(60.dp)
                                    .padding(4.dp)
                                    .clickable {
                                        ZarcHaptics.click(context)
                                        error = null
                                        when (key) {
                                            "C" -> pin = ""
                                            "⌫" -> if (pin.isNotEmpty()) pin = pin.dropLast(1)
                                            else -> {
                                                if (pin.length < 4) {
                                                    val newPin = pin + key
                                                    pin = newPin
                                                    if (newPin.length == 4) {
                                                        checkPin(newPin)
                                                    }
                                                }
                                            }
                                        }
                                    },
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = key,
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
