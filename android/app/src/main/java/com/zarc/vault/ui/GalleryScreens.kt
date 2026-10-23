package com.zarc.vault.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
    onOpenWith: (MediaItem) -> Unit,
    onUnlockPrivate: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var optionsOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var editOpen by remember { mutableStateOf(false) }
    var moveDialog by remember { mutableStateOf(false) }
    var newAlbum by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<EditTarget?>(null) }
    var dateTarget by remember { mutableStateOf<EditTarget?>(null) }

    val tab = state.tab
    val openAlbum = state.albumId?.let { id -> state.index.albums.firstOrNull { it.id == id } }
    val albumOpen = tab == GalleryTab.ALBUMS && openAlbum != null
    val title = when {
        albumOpen -> openAlbum.name
        tab == GalleryTab.PHOTOS -> state.filter.title
        tab == GalleryTab.ALBUMS -> "Albums"
        tab == GalleryTab.PRIVATE -> "Private"
        tab == GalleryTab.BIN -> "Bin"
        else -> "Settings"
    }
    val subtitle = when {
        albumOpen -> plural(state.albumItems.size, "item")
        tab == GalleryTab.PHOTOS -> plural(state.roll.size, "item")
        tab == GalleryTab.ALBUMS -> plural(state.index.albums.size, "album")
        tab == GalleryTab.PRIVATE -> when {
            !state.privateAvailable -> "Not set up yet"
            state.privateUnlocked -> plural(state.privateItems.size, "item")
            else -> "Protected by the device authentication"
        }
        tab == GalleryTab.BIN -> plural(state.binnedItems.size, "item")
        else -> "Vault, security and recovery"
    }
    val selectableIds = when {
        albumOpen -> state.albumItems.map { it.id }
        tab == GalleryTab.PHOTOS -> state.roll.map { it.id }
        tab == GalleryTab.PRIVATE -> state.privateItems.map { it.id }
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
            state.searchOpen,
    ) {
        viewModel.goHome()
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
                GalleryTopBar(
                    title = title,
                    subtitle = subtitle,
                    selection = state.selection,
                    onClearSelection = viewModel::clearSelection,
                    onSelectAll = if (selectableIds.isNotEmpty()) {
                        { viewModel.selectAll(selectableIds) }
                    } else {
                        null
                    },
                    onFavorite = if (editable && tab != GalleryTab.PRIVATE) {
                        viewModel::toggleSelectionFavorite
                    } else {
                        null
                    },
                    selectionAllFavorite = state.selectionAllFavorite,
                    onEdit = if (editable) {
                        { editOpen = true }
                    } else {
                        null
                    },
                    onRestore = if (tab == GalleryTab.BIN) viewModel::restoreSelection else null,
                    onDelete = when (tab) {
                        GalleryTab.PHOTOS -> ({ viewModel.moveSelectionToBin() })
                        GalleryTab.BIN -> ({ viewModel.deleteSelectionForever() })
                        else -> null
                    },
                    searchOpen = state.searchOpen,
                    onToggleSearch = if (tab == GalleryTab.SETTINGS) null else viewModel::toggleSearch,
                    query = state.query,
                    onQuery = viewModel::setQuery,
                    onBack = if (tab != GalleryTab.PHOTOS || state.albumId != null) {
                        { viewModel.goHome() }
                    } else {
                        null
                    },
                    onMore = { optionsOpen = true },
                )

                when (tab) {
                    GalleryTab.PHOTOS -> PhotosScreen(state, viewModel)
                    GalleryTab.ALBUMS -> AlbumsScreen(state, viewModel)
                    GalleryTab.PRIVATE -> PrivateScreen(state, viewModel, onUnlockPrivate)
                    GalleryTab.BIN -> BinScreen(state, viewModel)
                    GalleryTab.SETTINGS -> SettingsScreen(state, viewModel)
                }
            }
        }

        FloatingNavBar(
            current = tab,
            onSelect = viewModel::selectTab,
            onMenu = { menuOpen = true },
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        AnimatedVisibility(
            visible = tab.isNavDestination && !state.selecting,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 20.dp, bottom = 104.dp),
            enter = fadeIn() + scaleIn(initialScale = 0.85f),
            exit = fadeOut() + scaleOut(targetScale = 0.85f),
        ) {
            ImportButton(onClick = onImport)
        }

        // The viewer covers everything, including the floating bar.
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
                ViewerScreen(
                    item = item,
                    isPrivate = item.isPrivate,
                    repository = viewModel.repository,
                    onClose = viewModel::closeViewer,
                    onOpenWith = onOpenWith,
                    onOpenInSplit = viewModel::openSplitView,
                    onDelete = {
                        viewModel.binItem(item)
                        viewModel.closeViewer()
                    },
                    onReencrypt = { viewModel.reencrypt(item) },
                    onRename = {
                        renameTarget = EditTarget(
                            ids = listOf(item.id),
                            initialName = item.name,
                            initialDate = item.takenAt,
                            isPrivate = item.isPrivate,
                            selectionMode = false,
                            item = item,
                        )
                    },
                    onEditDate = {
                        dateTarget = EditTarget(
                            ids = listOf(item.id),
                            initialName = item.name,
                            initialDate = item.takenAt,
                            isPrivate = item.isPrivate,
                            selectionMode = false,
                            item = item,
                        )
                    },
                    onToggleFavorite = {
                        viewModel.setItemFavorite(item, !item.isFavorite)
                    },
                )
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
            onImportSamples = viewModel::importSamples,
        )
    }
    if (editOpen) {
        SelectionEditSheet(
            count = state.selection.size,
            canMove = tab != GalleryTab.PRIVATE,
            onDismiss = { editOpen = false },
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
        )
    }
    if (moveDialog) {
        AlbumPickerDialog(
            title = "Move to album",
            albums = state.index.albums,
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
}

/** The action bar of a section; it turns into a selection bar when items are selected. */
@Composable
private fun GalleryTopBar(
    title: String,
    subtitle: String?,
    selection: Set<String>,
    onClearSelection: () -> Unit,
    onSelectAll: (() -> Unit)?,
    onFavorite: (() -> Unit)?,
    selectionAllFavorite: Boolean,
    onEdit: (() -> Unit)?,
    onRestore: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    searchOpen: Boolean,
    onToggleSearch: (() -> Unit)?,
    query: String,
    onQuery: (String) -> Unit,
    onBack: (() -> Unit)?,
    onMore: (() -> Unit)?,
) {
    val selecting = selection.isNotEmpty()
    Column(
        Modifier
            .fillMaxWidth()
            .padding(
                start = if (onBack != null && !selecting) 4.dp else 20.dp,
                end = 8.dp,
                top = 4.dp,
                bottom = 2.dp,
            ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null && !selecting) {
                IconButton(onClick = onBack) {
                    Icon(ZarcIcons.Back, contentDescription = "Back to the roll")
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (selecting) "${selection.size} selected" else title,
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!selecting && subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (selecting) {
                onSelectAll?.let { TopBarAction(ZarcIcons.SelectAll, "Select all", it) }
                onFavorite?.let {
                    TopBarAction(
                        icon = if (selectionAllFavorite) ZarcIcons.Star else ZarcIcons.StarBorder,
                        label = if (selectionAllFavorite) "Remove from favourites" else "Add to favourites",
                        onClick = it,
                    )
                }
                onEdit?.let { TopBarAction(ZarcIcons.Edit, "Edit", it) }
                onRestore?.let { TopBarAction(ZarcIcons.Restore, "Restore", it) }
                onDelete?.let { TopBarAction(ZarcIcons.Delete, "Delete", it) }
                TopBarAction(ZarcIcons.Close, "Clear the selection", onClearSelection)
            } else {
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
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF74C4FF), Color(0xFF2E7BF6)),
                ),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = ZarcIcons.Import,
            contentDescription = "Import photos and videos",
            tint = Color.White,
            modifier = Modifier.size(25.dp),
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
                "Tap the import button to add a picture or a video."
            } else {
                "No item matches \"${state.query}\"."
            },
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
        )
    }
}

/** The grid of media items. */
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
) {
    val gridState = rememberLazyGridState()
    val currentColumns by rememberUpdatedState(columns)
    var pinchZoom by remember { mutableFloatStateOf(1f) }

    LaunchedEffect(gridState, items.size, hasMore) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .collect { last ->
                if (hasMore && last >= items.size - 9) onLoadMore()
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

    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = gridState,
        modifier = Modifier
            .fillMaxSize()
            .transformable(transform),
        contentPadding = PaddingValues(start = 2.dp, end = 2.dp, bottom = FloatingBarRoom),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(items, key = { it.id }) { item ->
            val selected = item.id in selection
            val selectedAlpha by animateFloatAsState(
                targetValue = if (selected) 1f else 0f,
                label = "selection",
            )
            Box(
                modifier = Modifier
                    .animateItem()
                    .aspectRatio(1f)
                    .clip(CellShape)
                    .combinedClickable(
                        onClick = { onClick(item) },
                        onLongClick = { onLongClick(item) },
                    ),
            ) {
                MediaThumbnail(
                    repository = repository,
                    item = item,
                    isPrivate = isPrivate,
                    contentDescription = item.name,
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
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.32f * selectedAlpha),
                        ),
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(22.dp)
                        .graphicsLayer {
                            alpha = selectedAlpha
                            scaleX = 0.75f + 0.25f * selectedAlpha
                            scaleY = 0.75f + 0.25f * selectedAlpha
                        }
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

/** The albums. */
@Composable
fun AlbumsScreen(state: GalleryUiState, viewModel: GalleryViewModel) {
    var renaming by remember { mutableStateOf<Album?>(null) }
    val openAlbum = state.albumId?.let { id -> state.index.albums.firstOrNull { it.id == id } }

    if (openAlbum != null) {
        if (state.albumItems.isEmpty()) {
            EmptyState(
                title = if (state.query.isEmpty()) "This album is empty" else "Nothing found",
                subtitle = if (state.query.isEmpty()) {
                    "Add media to it with Edit > Move to album."
                } else {
                    "No item matches \"${state.query}\"."
                },
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
            title = if (needle.isEmpty()) "No albums" else "Nothing found",
            subtitle = if (needle.isEmpty()) {
                "Create an album from the menu to group your media."
            } else {
                "No album matches \"$needle\"."
            },
        )
    } else {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, FloatingBarRoom),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(albums, key = { it.id }) { album ->
                val members = state.index.inAlbum(album.id)
                AlbumCard(
                    album = album,
                    count = members.size,
                    cover = members.firstOrNull(),
                    repository = viewModel.repository,
                    onClick = { viewModel.openAlbum(album.id) },
                    onLongClick = if (album.system) null else ({ renaming = album }),
                    onRename = if (album.system) null else ({ renaming = album }),
                )
            }
        }
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
    onRename: (() -> Unit)?,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onClick() },
                onLongClick = onLongClick,
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
                        imageVector = if (album.id == VaultIndex.ALBUM_FAVOURITES) {
                            ZarcIcons.Star
                        } else {
                            ZarcIcons.Albums
                        },
                        contentDescription = null,
                        tint = if (album.id == VaultIndex.ALBUM_FAVOURITES) {
                            ZarcPalette.favourite
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.align(Alignment.Center).size(32.dp),
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
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
                if (onRename != null) {
                    IconButton(onClick = onRename) {
                        Icon(ZarcIcons.MoreVert, contentDescription = "Rename the album")
                    }
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
        !state.privateAvailable -> EmptyState(
            title = "No private area yet",
            subtitle = "Set up the private area in the settings to protect it with the device authentication.",
        )

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
            Text("The private album is locked", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Authenticate with the device to open it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onUnlock) {
                Icon(ZarcIcons.Lock, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Unlock")
            }
        }

        state.privateItems.isEmpty() -> EmptyState(
            "The private album is empty",
            "Import into it from the private section.",
        )

        else -> MediaGrid(
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
        )
    }
}

/** The recycle bin. */
@Composable
fun BinScreen(state: GalleryUiState, viewModel: GalleryViewModel) {
    if (state.binnedItems.isEmpty()) {
        EmptyState("The bin is empty", "Deleted items stay here until you empty the bin.")
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
            onClick = { viewModel.toggleSelection(it.id) },
            onLongClick = { viewModel.toggleSelection(it.id) },
        )
    }
}

/** The empty state of a screen. */
@Composable
private fun EmptyState(title: String, subtitle: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 32.dp, end = 32.dp, top = 32.dp, bottom = FloatingBarRoom),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
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
                SheetHeader("Private", if (state.privateUnlocked) "Unlocked" else "Locked")
                if (state.privateUnlocked) {
                    SheetAction(
                        icon = ZarcIcons.Lock,
                        title = "Lock the private album",
                        subtitle = "Drop the key and hide the items again",
                        tint = ZarcPalette.privateArea,
                        onClick = {
                            viewModel.lockPrivateArea()
                            onDismiss()
                        },
                    )
                } else {
                    SheetAction(
                        icon = ZarcIcons.Lock,
                        title = "Unlock the private album",
                        subtitle = "Authenticate with the device",
                        tint = ZarcPalette.privateArea,
                        onClick = {
                            onDismiss()
                            onUnlockPrivate()
                        },
                    )
                }
                SheetAction(
                    icon = ZarcIcons.SelectAll,
                    title = "Select all",
                    subtitle = "Pick every visible item",
                    onClick = {
                        viewModel.selectAll(state.privateItems.map { it.id })
                        onDismiss()
                    },
                )
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
    onImportSamples: () -> Unit,
) {
    ZarcSheet(onDismiss = onDismiss) {
        SheetHeader("Menu", "Private, bin and the vault")
        SheetAction(
            icon = ZarcIcons.Lock,
            title = "Private",
            subtitle = when {
                !state.privateAvailable -> "Not set up yet"
                state.privateUnlocked -> "${plural(state.privateItems.size, "item")} unlocked"
                else -> "Locked behind the device authentication"
            },
            tint = ZarcPalette.privateArea,
            chevron = true,
            onClick = { onNavigate(GalleryTab.PRIVATE) },
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
        SheetDivider()
        SheetAction(
            icon = ZarcIcons.Import,
            title = "Import sample media",
            subtitle = "Add synthetic pictures for a quick look",
            onClick = {
                onDismiss()
                onImportSamples()
            },
        )
    }
}

/** The shared bottom sheet container. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ZarcSheet(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        dragHandle = { BottomSheetDefaults.DragHandle(color = MaterialTheme.colorScheme.outline) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 12.dp, end = 12.dp, bottom = 20.dp),
            content = content,
        )
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
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onEditDate: () -> Unit,
    onMove: () -> Unit,
) {
    ZarcSheet(onDismiss = onDismiss) {
        SheetHeader("Edit", plural(count, "item"))
        SheetAction(
            icon = ZarcIcons.Edit,
            title = "Rename",
            subtitle = if (count == 1) "Change the display name" else "Number the selected names",
            onClick = onRename,
        )
        SheetAction(
            icon = ZarcIcons.DateRange,
            title = "Date and time",
            subtitle = "Change when the media was captured",
            onClick = onEditDate,
        )
        if (canMove) {
            SheetAction(
                icon = ZarcIcons.Move,
                title = "Move to album",
                subtitle = "Group the selected items",
                onClick = onMove,
            )
        }
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
