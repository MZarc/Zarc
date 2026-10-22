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
    /** Whether the private area is unlocked. */
    val privateUnlocked: Boolean = false,
    /** Whether the private area has been set up. */
    val privateAvailable: Boolean = false,
    /** A short message for the user. */
    val message: String? = null,
    /** Whether a long running operation is running. */
    val busy: Boolean = false,
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

    /** The items of the private area. */
    val privateItems: List<MediaItem>
        get() = privateIndex.visible.matching(query).orderedBy(sort)

    /** The items in the recycle bin. */
    val binnedItems: List<MediaItem>
        get() = index.deleted.matching(query).orderedBy(sort)

    /** The items the split viewer can offer for the open item. */
    val viewerCandidates: List<MediaItem>
        get() = if (viewerItem?.isPrivate == true) privateItems else roll

    /** Whether the user interface is in selection mode. */
    val selecting: Boolean get() = selection.isNotEmpty()
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

    private val _state = MutableStateFlow(GalleryUiState())
    /** The observable state. */
    val state: StateFlow<GalleryUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    /** Reload the index from the vault. */
    fun refresh() {
        viewModelScope.launch {
            val index = withContext(Dispatchers.IO) { repository.loadIndex() }
            val privateAvailable = withContext(Dispatchers.IO) { repository.hasPrivateArea() }
            _state.value = _state.value.copy(
                index = index,
                privateAvailable = privateAvailable,
            )
        }
    }

    /** Switch the tab. */
    fun selectTab(tab: GalleryTab) {
        _state.value = _state.value.copy(
            tab = tab,
            selection = emptySet(),
            query = "",
            searchOpen = false,
            message = null,
        )
    }

    /** Change the media filter of the roll. */
    fun setFilter(filter: MediaFilter) {
        _state.value = _state.value.copy(filter = filter)
    }

    /** Change the sort order. */
    fun setSort(sort: MediaSort) {
        _state.value = _state.value.copy(sort = sort)
    }

    /** Open or close the search field. */
    fun toggleSearch() {
        val open = !_state.value.searchOpen
        _state.value = _state.value.copy(searchOpen = open, query = if (open) _state.value.query else "")
    }

    /** Change the search query. */
    fun setQuery(query: String) {
        _state.value = _state.value.copy(query = query)
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

    /** Open an item in the viewer. */
    fun openViewer(item: MediaItem) {
        _state.value = _state.value.copy(viewerItem = item, splitMode = false, splitItem = null)
    }

    /** Close the viewer. */
    fun closeViewer() {
        _state.value = _state.value.copy(viewerItem = null, splitMode = false, splitItem = null)
    }

    /** Show the open item next to another one. */
    fun openSplitView() {
        val current = _state.value
        val item = current.viewerItem ?: return
        if (current.splitMode) return
        val second = current.viewerCandidates.firstOrNull { it.id != item.id }
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
        if (item.isPrivate) {
            message("private items cannot be moved to the bin")
            return
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.moveToBin(listOf(item.id)) }
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
    fun import(uri: Uri, albumId: String = VaultIndex.ALBUM_IMPORTED, isPrivate: Boolean = false) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val result = withContext(Dispatchers.IO) {
                runCatching { repository.import(uri, albumId, isPrivate) }
            }
            _state.value = _state.value.copy(busy = false)
            result.onSuccess { refresh() }
                .onFailure { message("the import failed: ${it.message}") }
        }
    }

    /** Import synthetic sample media. */
    fun importSamples(count: Int = 12) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val result = withContext(Dispatchers.IO) {
                runCatching { repository.importSamples(count) }
            }
            _state.value = _state.value.copy(busy = false)
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
            withContext(Dispatchers.IO) { repository.moveToAlbum(ids, albumId) }
            clearSelection()
            refresh()
        }
    }

    /** Move the selected items into the recycle bin. */
    fun moveSelectionToBin() {
        val ids = _state.value.selection.toList()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.moveToBin(ids) }
            clearSelection()
            refresh()
        }
    }

    /** Restore the selected items from the recycle bin. */
    fun restoreSelection() {
        val ids = _state.value.selection.toList()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.restoreFromBin(ids) }
            clearSelection()
            refresh()
        }
    }

    /** Delete the selected items permanently. */
    fun deleteSelectionForever() {
        val ids = _state.value.selection.toList()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.deleteForever(ids) }
            clearSelection()
            refresh()
        }
    }

    /** Empty the recycle bin. */
    fun emptyBin() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.emptyBin() }
            refresh()
        }
    }

    /** Re-encrypt an item with a fresh data key. */
    fun reencrypt(item: MediaItem) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { repository.reencryptObject(item) }
            }
            result.onSuccess { message("the item was encrypted again with a fresh key") }
                .onFailure { message("the re-encryption failed: ${it.message}") }
        }
    }

    /** Rotate the vault master key. */
    fun rotateVaultMasterKey() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { repository.vault.rotateVaultMasterKey() }
            }
            result.onSuccess { message("$it objects were re-wrapped under a new key") }
                .onFailure { message("the rotation failed: ${it.message}") }
        }
    }

    /** Set up the private area. */
    fun enablePrivateArea() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { repository.enablePrivateArea() }
            }
            result.onSuccess {
                refresh()
                message(if (it) "the private area is ready" else "the private area is not available")
            }.onFailure { message("the private area could not be set up: ${it.message}") }
        }
    }

    /** Unlock the private area with the key that the biometric released. */
    fun onPrivateUnlocked(key: ByteArray) {
        viewModelScope.launch {
            val index = withContext(Dispatchers.IO) { repository.loadPrivateIndex(key) }
            repository.unlockedPrivateKey = key
            _state.value = _state.value.copy(privateIndex = index, privateUnlocked = true)
        }
    }

    /** Lock the private area again. */
    fun lockPrivateArea() {
        repository.unlockedPrivateKey?.fill(0)
        repository.unlockedPrivateKey = null
        _state.value = _state.value.copy(privateUnlocked = false, privateIndex = VaultIndex())
    }

    /** The albums of the current area. */
    fun albums(): List<Album> = _state.value.index.albums
}
