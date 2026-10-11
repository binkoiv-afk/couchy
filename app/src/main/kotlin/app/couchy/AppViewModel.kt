package app.couchy

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.couchy.core.LibrarySort
import app.couchy.core.LinkKind
import app.couchy.core.Links
import app.couchy.core.Progress
import app.couchy.core.SortOrder
import app.couchy.data.MediaEntity
import app.couchy.data.Prefs
import app.couchy.data.ThemeMode
import app.couchy.data.UiMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the player is asked to play. */
data class Playback(
    val uri: String,
    val title: String,
    val startMs: Long,
    val audioLanguage: String? = null,
    val subtitleLanguage: String? = null,
)

data class HomeState(
    val continueWatching: List<MediaEntity> = emptyList(),
    val favorites: List<MediaEntity> = emptyList(),
    val recent: List<MediaEntity> = emptyList(),
    val loaded: Boolean = false,
) {
    val isEmpty get() = loaded && continueWatching.isEmpty() && favorites.isEmpty() && recent.isEmpty()
}

enum class LibraryFilter(val label: String) { ALL("Все"), UNFINISHED("Недосмотренные"), FAVORITES("Избранное"), WATCHED("Просмотренные") }

data class LibraryState(
    val items: List<MediaEntity> = emptyList(),
    val filter: LibraryFilter = LibraryFilter.ALL,
    val sort: SortOrder = SortOrder.RECENTLY_WATCHED,
    val total: Int = 0,
)

/** Why a link was not played, in the user's words. */
data class Notice(val text: String)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val couchy = app as CouchyApp
    private val library = couchy.library
    private val settings = couchy.settings

    val prefs: StateFlow<Prefs?> = settings.prefs.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val home: StateFlow<HomeState> = library.all.map { all ->
        HomeState(
            continueWatching = LibrarySort.continueWatching(all).take(12),
            favorites = all.filter { it.favorite }.sortedByDescending { it.lastWatchedAt.coerceAtLeast(it.addedAt) },
            recent = all.sortedByDescending { it.addedAt }.take(12),
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())

    private val filter = MutableStateFlow(LibraryFilter.ALL)

    val libraryState: StateFlow<LibraryState> = combine(library.all, filter, settings.prefs) { all, f, p ->
        val filtered = when (f) {
            LibraryFilter.ALL -> all
            LibraryFilter.UNFINISHED -> LibrarySort.continueWatching(all)
            LibraryFilter.FAVORITES -> all.filter { it.favorite }
            LibraryFilter.WATCHED -> all.filter { Progress.isWatched(it.positionMs, it.durationMs) }
        }
        LibraryState(LibrarySort.sort(filtered, p.sort), f, p.sort, all.size)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryState())

    private val _playback = MutableStateFlow<Playback?>(null)
    val playback: StateFlow<Playback?> = _playback.asStateFlow()

    private val _notice = MutableStateFlow<Notice?>(null)
    val notice: StateFlow<Notice?> = _notice.asStateFlow()

    fun dismissNotice() { _notice.value = null }

    fun showNotice(text: String) { _notice.value = Notice(text) }

    /** Adds the link to the library if it is new and plays it, resuming where it stopped. */
    fun open(link: String, title: String? = null, fromStart: Boolean = false) {
        val kind = Links.classify(link)
        if (!Links.isPlayable(kind)) {
            _notice.value = Notice(unsupportedText(kind))
            return
        }
        viewModelScope.launch {
            val entry = library.open(link, title)
            if (fromStart) library.clearProgress(entry.uri)
            _playback.value = Playback(
                uri = entry.uri,
                title = entry.title,
                startMs = if (fromStart) 0 else Progress.resumeFrom(entry.positionMs, entry.durationMs),
                audioLanguage = entry.audioLanguage,
                subtitleLanguage = entry.subtitleLanguage,
            )
        }
    }

    fun play(item: MediaEntity, fromStart: Boolean = false) = open(item.uri, item.title, fromStart)

    fun closePlayer() { _playback.value = null }

    fun saveProgress(uri: String, positionMs: Long, durationMs: Long) {
        viewModelScope.launch { library.saveProgress(uri, positionMs, durationMs) }
    }

    fun saveTracks(uri: String, audio: String?, subtitles: String?) {
        viewModelScope.launch { library.saveTracks(uri, audio, subtitles) }
    }

    fun toggleFavorite(item: MediaEntity) {
        viewModelScope.launch { library.setFavorite(item.uri, !item.favorite) }
    }

    fun markUnwatched(item: MediaEntity) {
        viewModelScope.launch { library.clearProgress(item.uri) }
    }

    fun remove(item: MediaEntity) {
        viewModelScope.launch { library.delete(item.uri) }
    }

    fun setFilter(value: LibraryFilter) { filter.value = value }
    fun setSort(value: SortOrder) = viewModelScope.launch { settings.setSort(value) }
    fun setTheme(value: ThemeMode) = viewModelScope.launch { settings.setTheme(value) }
    fun setUiMode(value: UiMode) = viewModelScope.launch { settings.setUiMode(value) }

    private fun unsupportedText(kind: LinkKind) = when (kind) {
        LinkKind.TORRENT -> "Торренты появятся в одной из следующих сборок: встроенный движок и TorrServe."
        LinkKind.PLAYLIST -> "Плейлисты IPTV появятся в разделе «ТВ» в следующих сборках."
        LinkKind.YOUTUBE -> "YouTube появится отдельным разделом в следующих сборках."
        else -> "Эту ссылку пока не получается открыть."
    }
}
