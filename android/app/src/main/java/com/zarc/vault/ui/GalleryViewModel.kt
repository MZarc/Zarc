package com.zarc.vault.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zarc.vault.gallery.Album
import com.zarc.vault.gallery.GalleryRepository
import com.zarc.vault.gallery.MediaItem
import com.zarc.vault.gallery.VaultIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The sections of the gallery. */
enum class GalleryTab(val title: String) {
    /** The roll of pictures and videos. */
    PHOTOS("Roll"),

    /** The albums. */
    ALBUMS("Albums"),

    /** The private area, behind a biometric authentication. */
    PRIVATE("Private"),

    /** The recycle bin. */
    BIN("Bin"),

    /** The settings and the developer tools. */
    SETTINGS("Settings"),

    /** The custom .zarc photo archive creator and extractor. */
    ARCHIVE("Archive"),
}

/** Whether a section is reached through the floating navigation bar itself. */
val GalleryTab.isNavDestination: Boolean
    get() = this == GalleryTab.PHOTOS || this == GalleryTab.ALBUMS

/** The media filter of the roll. */
enum class MediaFilter(val title: String) {
    /** Pictures and videos. */
    ALL("All"),

    /** Only pictures. */
    PHOTOS("Photos"),

    /** Only videos. */
    VIDEOS("Videos"),
}

/** The order in which the roll is shown. */
enum class MediaSort(val title: String) {
    /** The most recent capture first. */
    NEWEST("Newest first"),

    /** The oldest capture first. */
    OLDEST("Oldest first"),

    /** The file name, in the natural order. */
    NAME("Name"),

    /** The largest object first. */
    SIZE("Largest first"),
}

/** How many items are materialised in a grid before more are requested. */
private const val PAGE_SIZE = 240

/** Apply the search query to a list of media. */
private fun List<MediaItem>.matching(query: String): List<MediaItem> {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return this
    return filter { it.name.lowercase().contains(needle) }
}

/** Apply the sort order to a list of media. */
private fun List<MediaItem>.orderedBy(sort: MediaSort): List<MediaItem> = when (sort) {
    MediaSort.NEWEST -> sortedByDescending { it.takenAt }
    MediaSort.OLDEST -> sortedBy { it.takenAt }
    MediaSort.NAME -> sortedBy { it.name.lowercase() }
    MediaSort.SIZE -> sortedByDescending { it.size }
}

/** The state of the gallery. */
data class GalleryUiState(
    /** The index of the normal area. */
    val index: VaultIndex = VaultIndex(),
    /** The index of the private area. */
    val privateIndex: VaultIndex = VaultIndex(),
    /** The active tab. */
    val tab: GalleryTab = GalleryTab.PHOTOS,
    /** The active media filter. */
    val filter: MediaFilter = MediaFilter.ALL,
    /** The order in which the roll is shown. */
    val sort: MediaSort = MediaSort.NEWEST,
    /** The current search query. */
    val query: String = "",
    /** Whether the search field is open. */
    val searchOpen: Boolean = false,
    /** The ids of the selected items. */
    val selection: Set<String> = emptySet(),
    /** The item that is open in the viewer. */
    val viewerItem: MediaItem? = null,
    /** Whether the viewer shows two items side by side. */
    val splitMode: Boolean = false,
    /** The second item of the split viewer. */
    val splitItem: MediaItem? = null,
    /** The album that is open inside the albums section, or null for the list. */
    val albumId: String? = null,
    /** The number of items currently materialised in a grid. */
    val pageLimit: Int = PAGE_SIZE,
    /** The number of columns of the media grid, between 2 and 6. */
    val gridColumns: Int = 4,
    /** Whether the private area is unlocked. */
    val privateUnlocked: Boolean = false,
    /** Whether the private area has been set up. */
    val privateAvailable: Boolean = false,
    /** A short message for the user. */
    val message: String? = null,
    /** Whether a long running operation is running. */
    val busy: Boolean = false,
    /** A descriptive status message for the loader. */
    val busyMessage: String? = null,
    /** Whether app-level biometric lock is enabled. */
    val appLockEnabled: Boolean = false,
    /** Whether stealth time-based PIN is enabled for the whole app (default false). */
    val stealthLockEnabled: Boolean = false,
    /** Whether the app-level stealth PIN dialog is currently open. */
    val appStealthDialogOpen: Boolean = false,
    /** Whether viewing the private recycle bin inside the private area. */
    val inPrivateBin: Boolean = false,
) {
    /** The items of the roll after the filter and the search. */
    val roll: List<MediaItem>
        get() {
            val filtered = when (filter) {
                MediaFilter.ALL -> index.visible
                MediaFilter.PHOTOS -> index.visible.filter { !it.isVideo }
                MediaFilter.VIDEOS -> index.visible.filter { it.isVideo }
            }
            return filtered.matching(query).orderedBy(sort)
        }

    /** The items of the open album after the filter and the search. */
    val albumItems: List<MediaItem>
        get() = albumId?.let { id ->
            index.inAlbum(id)
                .filter { item ->
                    when (filter) {
                        MediaFilter.ALL -> true
                        MediaFilter.PHOTOS -> !item.isVideo
                        MediaFilter.VIDEOS -> item.isVideo
                    }
                }
                .matching(query)
                .orderedBy(sort)
        } ?: emptyList()

    /** The items of the private area. */
    val privateItems: List<MediaItem>
        get() = privateIndex.visible.matching(query).orderedBy(sort)

    /** The items in the recycle bin. */
    val binnedItems: List<MediaItem>
        get() = index.deleted.matching(query).orderedBy(sort)

    /** The deleted items in the private recycle bin. */
    val privateBinnedItems: List<MediaItem>
        get() = privateIndex.deleted.matching(query).orderedBy(sort)

    /** The roll, materialised up to the current page limit. */
    val visibleRoll: List<MediaItem> get() = roll.take(pageLimit)

    /** The open album, materialised up to the current page limit. */
    val visibleAlbumItems: List<MediaItem> get() = albumItems.take(pageLimit)

    /** The private items, materialised up to the current page limit. */
    val visiblePrivateItems: List<MediaItem> get() = privateItems.take(pageLimit)

    /** The binned items, materialised up to the current page limit. */
    val visibleBinnedItems: List<MediaItem> get() = binnedItems.take(pageLimit)

    /** The private binned items, materialised up to the current page limit. */
    val visiblePrivateBinnedItems: List<MediaItem> get() = privateBinnedItems.take(pageLimit)

    /** The number of items in the current section, across every page. */
    val activeTotal: Int
        get() = when (tab) {
            GalleryTab.PHOTOS -> roll.size
            GalleryTab.ALBUMS -> if (albumId != null) albumItems.size else 0
            GalleryTab.PRIVATE -> if (inPrivateBin) privateBinnedItems.size else privateItems.size
            GalleryTab.BIN -> binnedItems.size
            GalleryTab.SETTINGS -> 0
            GalleryTab.ARCHIVE -> 0
        }

    /** Whether another page can be requested. */
    val hasMore: Boolean get() = activeTotal > pageLimit

    /** The items the viewer can swipe through and offer for the split pane. */
    val viewerCandidates: List<MediaItem>
        get() = when {
            viewerItem?.isDeleted == true && viewerItem?.isPrivate == true -> privateBinnedItems
            viewerItem?.isDeleted == true -> binnedItems
            viewerItem?.isPrivate == true -> if (inPrivateBin) privateBinnedItems else privateItems
            tab == GalleryTab.PRIVATE && inPrivateBin -> privateBinnedItems
            tab == GalleryTab.PRIVATE -> privateItems
            albumId != null -> albumItems
            else -> roll
        }

    /** Whether the user interface is in selection mode. */
    val selecting: Boolean get() = selection.isNotEmpty()

    /** Whether every selected item is already a favourite. */
    val selectionAllFavorite: Boolean
        get() = selection.isNotEmpty() && selection.all { id -> index.find(id)?.isFavorite == true }
}

/**
 * The view model of the gallery.
 *
 * All the work happens on the IO dispatcher; the state is exposed as a single
 * immutable snapshot so that the user interface never sees a half applied
 * change.
 */
class GalleryViewModel(application: Application) : AndroidViewModel(application) {

    /** The gallery data. */
    val repository = GalleryRepository(application)

    private val preferences =
        application.getSharedPreferences("zarc.gallery", Application.MODE_PRIVATE)

    private val _state = MutableStateFlow(
        GalleryUiState(
            gridColumns = preferences.getInt("grid.columns", 4).coerceIn(2, 6),
            appLockEnabled = preferences.getBoolean("security.app_lock", false),
            stealthLockEnabled = preferences.getBoolean("security.stealth_lock", false),
        ),
    )
    /** The observable state. */
    val state: StateFlow<GalleryUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                repository.vault.recoverPendingRotation()
                repository.vault.purgeStaleTransientFiles()
                repository.clearViewerCache()
            }
        }
        refresh()
    }

    /** Reload the index from the vault. Also automatically purges items in bin >= 30 days. */
    fun refresh() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val index = repository.loadIndexAndPurgeExpired()
                    val key = repository.unlockedPrivateKey
                    val privateIndex = if (key != null) repository.loadPrivateIndex(key) else null
                    Triple(index, privateIndex, repository.hasPrivateArea())
                }
            }
            result.onSuccess { (index, privateIndex, privateAvailable) ->
                // Re-read the state after the IO work: the user may have
                // switched tabs, opened a viewer or changed the selection
                // while the index was loading.
                val latest = _state.value
                _state.value = latest.copy(
                    index = index,
                    privateIndex = when {
                        !latest.privateUnlocked -> VaultIndex()
                        privateIndex != null -> privateIndex
                        else -> latest.privateIndex
                    },
                    privateAvailable = privateAvailable,
                    viewerItem = latest.viewerItem?.let { item ->
                        if (item.isPrivate) privateIndex?.find(item.id) else index.find(item.id)
                    },
                    splitItem = latest.splitItem?.let { item ->
                        if (item.isPrivate) privateIndex?.find(item.id) else index.find(item.id)
                    },
                    albumId = latest.albumId?.takeIf { id -> index.albums.any { it.id == id } },
                    selection = latest.selection.filterTo(mutableSetOf()) { id ->
                        index.find(id) != null || privateIndex?.find(id) != null
                    },
                )
            }.onFailure { error ->
                message("the vault index could not be loaded: ${error.message}")
            }
        }
    }

    /** Toggle app-level biometric lock. */
    fun setAppLockEnabled(enabled: Boolean) {
        preferences.edit().putBoolean("security.app_lock", enabled).apply()
        _state.value = _state.value.copy(appLockEnabled = enabled)
    }

    /** Toggle stealth dynamic time-based PIN protection for private vault. */
    fun setStealthLockEnabled(enabled: Boolean) {
        preferences.edit().putBoolean("security.stealth_lock", enabled).apply()
        _state.value = _state.value.copy(stealthLockEnabled = enabled)
    }

    /** Close the current album and return to the albums list. */
    fun closeAlbum() {
        _state.value = _state.value.copy(
            albumId = null,
            selection = emptySet(),
            query = "",
            searchOpen = false,
            pageLimit = PAGE_SIZE,
        )
    }

    /** Switch the tab. */
    fun selectTab(tab: GalleryTab) {
        if (_state.value.tab == GalleryTab.PRIVATE && tab != GalleryTab.PRIVATE) {
            lockPrivateArea()
        }
        _state.value = _state.value.copy(
            tab = tab,
            inPrivateBin = false,
            albumId = null,
            selection = emptySet(),
            query = "",
            searchOpen = false,
            pageLimit = PAGE_SIZE,
            message = null,
        )
    }

    /** Return to the roll from any section, viewer or nested screen. */
    fun goHome() {
        if (_state.value.tab == GalleryTab.PRIVATE) {
            lockPrivateArea()
        }
        _state.value = _state.value.copy(
            tab = GalleryTab.PHOTOS,
            inPrivateBin = false,
            albumId = null,
            selection = emptySet(),
            query = "",
            searchOpen = false,
            pageLimit = PAGE_SIZE,
            viewerItem = null,
            splitMode = false,
            splitItem = null,
            message = null,
        )
    }

    /** Open an album inside the albums section. */
    fun openAlbum(albumId: String) {
        _state.value = _state.value.copy(
            albumId = albumId,
            selection = emptySet(),
            query = "",
            searchOpen = false,
            pageLimit = PAGE_SIZE,
        )
    }

    /** Change the media filter of the roll. */
    fun setFilter(filter: MediaFilter) {
        _state.value = _state.value.copy(filter = filter, pageLimit = PAGE_SIZE)
    }

    /** Change the sort order. */
    fun setSort(sort: MediaSort) {
        _state.value = _state.value.copy(sort = sort, pageLimit = PAGE_SIZE)
    }

    /** Open or close the search field. */
    fun toggleSearch() {
        val open = !_state.value.searchOpen
        _state.value = _state.value.copy(
            searchOpen = open,
            query = if (open) _state.value.query else "",
            pageLimit = PAGE_SIZE,
        )
    }

    /** Change the search query. */
    fun setQuery(query: String) {
        _state.value = _state.value.copy(query = query, pageLimit = PAGE_SIZE)
    }

    /** Set the number of columns of the media grid and remember it. */
    fun setGridColumns(columns: Int) {
        val value = columns.coerceIn(2, 6)
        if (value == _state.value.gridColumns) return
        preferences.edit().putInt("grid.columns", value).apply()
        _state.value = _state.value.copy(gridColumns = value)
    }

    /** Materialise the next page of the current section. */
    fun loadMore() {
        val current = _state.value
        if (!current.hasMore) return
        _state.value = current.copy(
            pageLimit = (current.pageLimit + PAGE_SIZE).coerceAtMost(current.activeTotal),
        )
    }

    /** Toggle the selection of an item. */
    fun toggleSelection(id: String) {
        val selection = _state.value.selection.toMutableSet()
        if (!selection.remove(id)) selection.add(id)
        _state.value = _state.value.copy(selection = selection)
    }

    /** Select every given item at once. */
    fun selectAll(ids: Collection<String>) {
        _state.value = _state.value.copy(selection = ids.toSet())
    }

    /** Clear the selection. */
    fun clearSelection() {
        _state.value = _state.value.copy(selection = emptySet())
    }

    /** Rename the selected items; a number is appended when several are selected. */
    fun renameSelection(name: String) {
        val current = _state.value
        val ids = current.selection
        if (ids.isEmpty()) return
        val isPrivate = current.tab == GalleryTab.PRIVATE
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { repository.renameItems(ids, name, isPrivate) }
            }
            result.onSuccess {
                clearSelection()
                refresh()
                message(if (ids.size == 1) "renamed" else "${ids.size} items renamed")
            }.onFailure { message("the rename failed: ${it.message}") }
        }
    }

    /** Change the date and time of the selected items. */
    fun setSelectionTakenAt(timestamp: Long) {
        val current = _state.value
        val ids = current.selection
        if (ids.isEmpty()) return
        val isPrivate = current.tab == GalleryTab.PRIVATE
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { repository.setTakenAt(ids, timestamp, isPrivate) }
            }
            result.onSuccess {
                clearSelection()
                refresh()
                message("date and time updated")
            }.onFailure { message("the date could not be changed: ${it.message}") }
        }
    }

    /** Add or remove the selection from the favourite album. */
    fun toggleSelectionFavorite() {
        val current = _state.value
        if (current.selection.isEmpty()) return
        if (current.tab == GalleryTab.PRIVATE) {
            message("favourites are only available outside the private area")
            return
        }
        val favorite = !current.selectionAllFavorite
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { repository.setFavorite(current.selection, favorite) }
            }
            result.onSuccess {
                clearSelection()
                refresh()
                message(if (favorite) "added to favourites" else "removed from favourites")
            }.onFailure { message("the favourite could not be saved: ${it.message}") }
        }
    }

    /** Rename a single item, for example from the viewer. */
    fun renameItem(item: MediaItem, name: String) {
        renameSelectionFor(listOf(item.id), name, item.isPrivate)
    }

    /** Change the date and time of a single item. */
    fun setItemTakenAt(item: MediaItem, timestamp: Long) {
        setTakenAtFor(listOf(item.id), timestamp, item.isPrivate)
    }

    /** Toggle the favourite state of a single item. */
    fun setItemFavorite(item: MediaItem, favorite: Boolean) {
        if (item.isPrivate) {
            message("favourites are only available outside the private area")
            return
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { repository.setFavorite(listOf(item.id), favorite) }
            }
            result.onSuccess {
                refresh()
                message(if (favorite) "added to favourites" else "removed from favourites")
            }.onFailure { message("the favourite could not be saved: ${it.message}") }
        }
    }

    private fun renameSelectionFor(ids: Collection<String>, name: String, isPrivate: Boolean) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { repository.renameItems(ids, name, isPrivate) }
            }
            result.onSuccess {
                refresh()
                message("renamed")
            }.onFailure { message("the rename failed: ${it.message}") }
        }
    }

    private fun setTakenAtFor(ids: Collection<String>, timestamp: Long, isPrivate: Boolean) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { repository.setTakenAt(ids, timestamp, isPrivate) }
            }
            result.onSuccess {
                refresh()
                message("date and time updated")
            }.onFailure { message("the date could not be changed: ${it.message}") }
        }
    }

    /** Open an item in the viewer. */
    fun openViewer(item: MediaItem) {
        _state.value = _state.value.copy(viewerItem = item, splitMode = false, splitItem = null)
    }

    /** Close the viewer. */
    fun closeViewer() {
        _state.value = _state.value.copy(viewerItem = null, splitMode = false, splitItem = null)
    }

    /** Show the open item next to another one (defaults automatically to the next item). */
    fun openSplitView() {
        val current = _state.value
        val item = current.viewerItem ?: return
        if (current.splitMode) return
        val list = current.viewerCandidates
        val currentIndex = list.indexOfFirst { it.id == item.id }
        val second = if (currentIndex != -1 && list.size > 1) {
            val nextIndex = (currentIndex + 1) % list.size
            list[nextIndex]
        } else {
            list.firstOrNull { it.id != item.id }
        }
        _state.value = current.copy(splitMode = true, splitItem = second)
    }

    /** Leave the split viewer and return to the single view. */
    fun closeSplitView() {
        _state.value = _state.value.copy(splitMode = false, splitItem = null)
    }

    /** Replace the second item of the split viewer. */
    fun setSplitItem(item: MediaItem) {
        _state.value = _state.value.copy(splitItem = item)
    }

    /** Replace the first item of the split viewer. */
    fun setViewerItem(item: MediaItem) {
        _state.value = _state.value.copy(viewerItem = item)
    }

    /** Exchange the two panes of the split viewer. */
    fun swapSplitPanes() {
        val current = _state.value
        val second = current.splitItem ?: return
        _state.value = current.copy(viewerItem = second, splitItem = current.viewerItem)
    }

    /** Move a single item that is open in the viewer into the bin. */
    fun binItem(item: MediaItem) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                if (item.isPrivate) {
                    repository.movePrivateToBin(listOf(item.id))
                } else {
                    repository.moveToBin(listOf(item.id))
                }
            }
            refresh()
        }
    }

    /** Clear the current message. */
    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }

    /** Show a short message. */
    fun message(text: String) {
        _state.value = _state.value.copy(message = text)
    }

    /** Import a document into the vault. */
    fun import(uri: Uri, albumId: String = VaultIndex.ALBUM_NONE, isPrivate: Boolean = false) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, busyMessage = "Encrypting and storing media...")
            val result = withContext(Dispatchers.IO) {
                runCatching { repository.import(uri, albumId, isPrivate) }
            }
            _state.value = _state.value.copy(busy = false, busyMessage = null)
            result.onSuccess { refresh() }
                .onFailure { message("the import failed: ${it.message}") }
        }
    }

    /** Import multiple documents into the vault. */
    fun importMultiple(uris: List<Uri>, albumId: String = VaultIndex.ALBUM_NONE, isPrivate: Boolean = false) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, busyMessage = "Importing 1 of ${uris.size}...")
            var count = 0
            withContext(Dispatchers.IO) {
                uris.forEachIndexed { i, uri ->
                    _state.value = _state.value.copy(busyMessage = "Importing ${i + 1} of ${uris.size}...")
                    runCatching {
                        repository.import(uri, albumId, isPrivate)
                        count++
                    }
                }
            }
            _state.value = _state.value.copy(busy = false, busyMessage = null)
            refresh()
            message(if (count == 1) "1 item imported into vault" else "$count items imported into vault")
        }
    }

    /** Import synthetic sample media. */
    fun importSamples(count: Int = 12) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, busyMessage = "Generating $count sample items...")
            val result = withContext(Dispatchers.IO) {
                runCatching { repository.importSamples(count) }
            }
            _state.value = _state.value.copy(busy = false, busyMessage = null)
            result.onSuccess { refresh(); message("${it.size} sample items imported") }
                .onFailure { message("the import failed: ${it.message}") }
        }
    }

    /** Create an album. */
    fun createAlbum(name: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.createAlbum(name) }
            refresh()
        }
    }

    /** Rename an album. */
    fun renameAlbum(albumId: String, name: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.renameAlbum(albumId, name) }
            refresh()
        }
    }

    /** Delete an album. */
    fun deleteAlbum(albumId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.deleteAlbum(albumId) }
            refresh()
        }
    }

    /** Move the selected items into an album. */
    fun moveSelectionToAlbum(albumId: String) {
        val ids = _state.value.selection.toList()
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, busyMessage = "Moving to album...")
            withContext(Dispatchers.IO) { repository.moveToAlbum(ids, albumId) }
            clearSelection()
            _state.value = _state.value.copy(busy = false, busyMessage = null)
            refresh()
        }
    }

    /** Move the selected items into the recycle bin. */
    fun moveSelectionToBin() {
        val ids = _state.value.selection.toList()
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, busyMessage = "Moving to recycle bin...")
            withContext(Dispatchers.IO) { repository.moveToBin(ids) }
            clearSelection()
            _state.value = _state.value.copy(busy = false, busyMessage = null)
            refresh()
        }
    }

    /** Restore the selected items from the recycle bin. */
    fun restoreSelection() {
        val ids = _state.value.selection.toList()
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, busyMessage = "Restoring items...")
            withContext(Dispatchers.IO) { repository.restoreFromBin(ids) }
            clearSelection()
            _state.value = _state.value.copy(busy = false, busyMessage = null)
            refresh()
        }
    }

    /** Delete the selected items permanently. */
    fun deleteSelectionForever() {
        val ids = _state.value.selection.toList()
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, busyMessage = "Permanently deleting...")
            withContext(Dispatchers.IO) { repository.deleteForever(ids) }
            clearSelection()
            _state.value = _state.value.copy(busy = false, busyMessage = null)
            refresh()
        }
    }

    /** Empty the recycle bin. */
    fun emptyBin() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, busyMessage = "Emptying recycle bin...")
            withContext(Dispatchers.IO) { repository.emptyBin() }
            _state.value = _state.value.copy(busy = false, busyMessage = null)
            refresh()
        }
    }

    /** Set album cover image. */
    fun setAlbumCover(albumId: String, mediaId: String?) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.setAlbumCover(albumId, mediaId) }
            refresh()
            message("Album cover updated")
        }
    }

    /** Move selected items into the private vault. */
    fun moveSelectionToPrivate() {
        val ids = _state.value.selection.toList()
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, busyMessage = "Moving to Private Vault...")
            withContext(Dispatchers.IO) { repository.moveToPrivate(ids) }
            clearSelection()
            _state.value = _state.value.copy(busy = false, busyMessage = null)
            refresh()
            message(if (ids.size == 1) "1 item moved to Private Vault" else "${ids.size} items moved to Private Vault")
        }
    }

    /** Move single item to private vault. */
    fun moveItemToPrivate(item: MediaItem) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, busyMessage = "Moving to Private Vault...")
            withContext(Dispatchers.IO) { repository.moveToPrivate(listOf(item.id)) }
            closeViewer()
            _state.value = _state.value.copy(busy = false, busyMessage = null)
            refresh()
            message("Moved to Private Vault")
        }
    }

    /** Move private selection to private bin. */
    fun movePrivateSelectionToBin() {
        val ids = _state.value.selection.toList()
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, busyMessage = "Moving to recycle bin...")
            withContext(Dispatchers.IO) { repository.movePrivateToBin(ids) }
            clearSelection()
            _state.value = _state.value.copy(busy = false, busyMessage = null)
            refresh()
        }
    }

    /** Restore private selection from private bin. */
    fun restorePrivateSelection() {
        val ids = _state.value.selection.toList()
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, busyMessage = "Restoring private items...")
            withContext(Dispatchers.IO) { repository.restorePrivateFromBin(ids) }
            clearSelection()
            _state.value = _state.value.copy(busy = false, busyMessage = null)
            refresh()
        }
    }

    /** Permanently delete private selection. */
    fun deletePrivateSelectionForever() {
        val ids = _state.value.selection.toList()
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, busyMessage = "Permanently deleting...")
            withContext(Dispatchers.IO) { repository.deletePrivateForever(ids) }
            clearSelection()
            _state.value = _state.value.copy(busy = false, busyMessage = null)
            refresh()
        }
    }

    /** Move a single item (public or private) to recycle bin. */
    /** Move items from private vault back to public photos. */
    fun moveSelectionOutOfPrivate() {
        val ids = _state.value.selection.toList()
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, busyMessage = "Moving to Photos...")
            withContext(Dispatchers.IO) { repository.moveOutOfPrivate(ids) }
            clearSelection()
            _state.value = _state.value.copy(busy = false, busyMessage = null)
            refresh()
            message(if (ids.size == 1) "1 item moved to Photos" else "${ids.size} items moved to Photos")
        }
    }

    /** Re-encrypt an item with a fresh data key. */
    fun reencrypt(item: MediaItem) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, busyMessage = "Re-encrypting object...")
            val result = withContext(Dispatchers.IO) {
                runCatching { repository.reencryptObject(item) }
            }
            _state.value = _state.value.copy(busy = false, busyMessage = null)
            result.onSuccess { message("the item was encrypted again with a fresh key") }
                .onFailure { message("the re-encryption failed: ${it.message}") }
        }
    }

    /** Rotate the vault master key. */
    fun rotateVaultMasterKey() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, busyMessage = "Rotating master key...")
            val result = withContext(Dispatchers.IO) {
                runCatching { repository.vault.rotateVaultMasterKey() }
            }
            _state.value = _state.value.copy(busy = false, busyMessage = null)
            result.onSuccess {
                val skipped = repository.vault.lastRotationSkipped
                refresh()
                if (skipped > 0) {
                    message(
                        "$it objects were re-wrapped; $skipped damaged object(s) " +
                            "were left untouched",
                    )
                } else {
                    message("$it objects were re-wrapped under a new key")
                }
            }.onFailure { message("the rotation failed: ${it.message}") }
        }
    }

    /** Show app-level stealth PIN prompt. */
    fun showAppStealthDialog() {
        _state.value = _state.value.copy(appStealthDialogOpen = true)
    }

    /** Dismiss app-level stealth PIN prompt. */
    fun dismissAppStealthDialog() {
        _state.value = _state.value.copy(appStealthDialogOpen = false)
    }

    /** Unlock the private area with the key that the biometric released. */
    fun onPrivateUnlocked(key: ByteArray) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, busyMessage = "Unlocking Private Vault...")
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    // If a legacy private area was upgraded, finish moving its
                    // objects to the independent subkey before showing it.
                    repository.finishPrivateMigration(key)
                    repository.loadPrivateIndex(key)
                }
            }
            _state.value = _state.value.copy(busy = false, busyMessage = null)
            result.onSuccess { index ->
                repository.unlockedPrivateKey?.fill(0)
                repository.unlockedPrivateKey = key
                _state.value = _state.value.copy(
                    privateIndex = index,
                    privateUnlocked = true,
                    tab = GalleryTab.PRIVATE,
                )
            }.onFailure { error ->
                key.fill(0)
                message("the private area could not be opened: ${error.message}")
            }
        }
    }

    /** Lock the private area again. */
    fun lockPrivateArea() {
        repository.unlockedPrivateKey?.fill(0)
        repository.unlockedPrivateKey = null
        ThumbnailCache.clear()
        ViewerImageCache.clear()
        _state.value = _state.value.copy(
            privateUnlocked = false,
            inPrivateBin = false,
            privateIndex = VaultIndex(),
            tab = if (_state.value.tab == GalleryTab.PRIVATE) GalleryTab.PHOTOS else _state.value.tab,
        )
        // Deleting decrypted sessions and exports touches the disk; keep it off
        // the main thread (onStop calls this).
        viewModelScope.launch(Dispatchers.IO) {
            ArchiveVaultSessionCache.trimAndClear()
            repository.clearViewerCache()
            runCatching { repository.purgeTransientExports() }
        }
    }

    fun openPrivateBin() {
        _state.value = _state.value.copy(inPrivateBin = true, selection = emptySet())
    }

    fun closePrivateBin() {
        _state.value = _state.value.copy(inPrivateBin = false, selection = emptySet())
    }

    fun selectBatch(ids: Collection<String>) {
        _state.value = _state.value.copy(selection = _state.value.selection + ids)
    }

    fun deselectBatch(ids: Collection<String>) {
        _state.value = _state.value.copy(selection = _state.value.selection - ids.toSet())
    }

    fun emptyPrivateBin() {
        val ids = _state.value.privateBinnedItems.map { it.id }
        if (ids.isEmpty()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, busyMessage = "Emptying private bin...")
            withContext(Dispatchers.IO) { repository.deletePrivateForever(ids) }
            _state.value = _state.value.copy(busy = false, busyMessage = null)
            refresh()
            message("private bin emptied")
        }
    }

    /** The albums of the current area. */
    fun albums(): List<Album> = _state.value.index.albums
}
