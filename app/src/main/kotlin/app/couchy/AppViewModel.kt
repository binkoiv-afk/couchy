package app.couchy

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.couchy.core.LibrarySort
import app.couchy.core.LinkKind
import app.couchy.core.Links
import app.couchy.core.Progress
import app.couchy.core.SortOrder
import app.couchy.core.sources.Rezka
import app.couchy.data.MediaEntity
import app.couchy.data.Prefs
import app.couchy.data.ThemeMode
import app.couchy.data.TorrEngineMode
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
    /** HTTP headers some IPTV streams need (User-Agent, Referer). */
    val headers: Map<String, String> = emptyMap(),
    /** Set for TV channels and their archive: no position is saved, ▲ ▼ switch channels. */
    val live: LiveChannel? = null,
    /** Where progress is saved: the link itself, or the torrent link plus file number. */
    val key: String = uri,
    /** Set for a file played from a torrent: the next episode starts by itself. */
    val torrent: TorrentPlay? = null,
    /** Subtitles that come as separate files: label and URL. */
    val subtitles: List<Pair<String, String>> = emptyList(),
    /** The next episode from an online source, opened when this one ends. */
    val nextRef: String? = null,
)

data class TorrentPlay(val hash: String, val fileId: Int, val nextFileId: Int?)

data class LiveChannel(
    val channelId: Long,
    val epgId: String?,
    /** Channel ids in the order ▲ ▼ go through. */
    val zapOrder: List<Long>,
    /** Start of the archived programme being watched, or null for the live broadcast. */
    val archiveOf: Long? = null,
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

    /** The library as the current mode shows it: in kids mode only what a parent allowed. */
    private val visible = combine(library.all, settings.prefs) { all, p -> if (p.kidsMode) all.filter { it.kids } else all }

    val home: StateFlow<HomeState> = visible.map { all ->
        HomeState(
            continueWatching = LibrarySort.continueWatching(all).take(12),
            favorites = all.filter { it.favorite }.sortedByDescending { it.lastWatchedAt.coerceAtLeast(it.addedAt) },
            recent = all.sortedByDescending { it.addedAt }.take(12),
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())

    private val filter = MutableStateFlow(LibraryFilter.ALL)

    val libraryState: StateFlow<LibraryState> = combine(visible, filter, settings.prefs) { all, f, p ->
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

    /** A playlist link opened from anywhere; the TV section offers to add it. */
    private val _pendingPlaylist = MutableStateFlow<String?>(null)
    val pendingPlaylist: StateFlow<String?> = _pendingPlaylist.asStateFlow()

    fun playlistHandled() { _pendingPlaylist.value = null }

    /** A magnet or .torrent opened from anywhere; the torrent screen takes it. */
    private val _pendingTorrent = MutableStateFlow<String?>(null)
    val pendingTorrent: StateFlow<String?> = _pendingTorrent.asStateFlow()

    fun torrentHandled() { _pendingTorrent.value = null }

    /** An episode saved from an online source; its stream link has to be fetched again. */
    private val _pendingSource = MutableStateFlow<String?>(null)
    val pendingSource: StateFlow<String?> = _pendingSource.asStateFlow()

    fun sourceHandled() { _pendingSource.value = null }

    /** Adds the link to the library if it is new and plays it, resuming where it stopped. */
    fun open(link: String, title: String? = null, fromStart: Boolean = false) {
        if (Rezka.isRef(link) || app.couchy.core.sources.SiteRef.isRef(link)) {
            _pendingSource.value = link
            return
        }
        val kind = if (library.isTorrentFile(link)) LinkKind.TORRENT else Links.classify(link)
        if (kind == LinkKind.TORRENT) {
            _pendingTorrent.value = link
            return
        }
        if (kind == LinkKind.PLAYLIST) {
            _pendingPlaylist.value = link
            return
        }
        if (!Links.isPlayable(kind)) {
            _notice.value = Notice(unsupportedText(kind))
            return
        }
        viewModelScope.launch {
            // A channel list behind a bare address or a .m3u8 name goes to TV, not to the player.
            if ((kind == LinkKind.UNKNOWN || kind == LinkKind.HLS) && link.trim().startsWith("http", ignoreCase = true) &&
                library.get(link) == null && sniff(link.trim()) == LinkKind.PLAYLIST
            ) {
                _pendingPlaylist.value = link.trim()
                return@launch
            }
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

    /** The first few kilobytes of a link, read to tell an IPTV list from a stream; null when unreachable. */
    private suspend fun sniff(link: String): LinkKind? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val c = java.net.URL(link).openConnection() as java.net.HttpURLConnection
            try {
                c.connectTimeout = 8_000
                c.readTimeout = 8_000
                c.setRequestProperty("Range", "bytes=0-8191")
                c.setRequestProperty("User-Agent", "Couchy/1.0 (Android)")
                if (c.responseCode !in 200..299) return@runCatching null
                val type = c.contentType.orEmpty().lowercase()
                if (type.startsWith("video/")) return@runCatching LinkKind.VIDEO
                val buffer = ByteArray(8192)
                var read = 0
                c.inputStream.use { input ->
                    while (read < buffer.size) {
                        val n = input.read(buffer, read, buffer.size - read)
                        if (n < 0) break
                        read += n
                    }
                }
                Links.sniff(String(buffer, 0, read, Charsets.UTF_8))
            } finally {
                c.disconnect()
            }
        }.getOrNull()
    }

    fun play(item: MediaEntity, fromStart: Boolean = false) = open(item.uri, item.title, fromStart)

    /** Another device sent something to watch here: it continues from where it was there. */
    fun openCast(request: app.couchy.core.cast.CastRequest) {
        if (request.headers.isNotEmpty() && !Rezka.isRef(request.link)) {
            _playback.value = Playback(request.link, request.title, request.positionMs, headers = request.headers)
            return
        }
        viewModelScope.launch {
            library.open(request.link, request.title)
            if (request.positionMs > 0) library.saveProgress(request.link, request.positionMs, request.durationMs)
            open(request.link, request.title)
        }
    }

    fun closePlayer() { _playback.value = null }

    fun playDirect(playback: Playback) { _playback.value = playback }

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

    fun toggleKids(item: MediaEntity) {
        viewModelScope.launch { library.setKids(item.uri, !item.kids) }
    }

    fun setKidsPin(pin: String) = viewModelScope.launch { settings.setKidsPin(pin) }
    fun enterKids() = viewModelScope.launch { settings.setKidsMode(true) }

    /** Leaves kids mode when [pin] is right. */
    fun exitKids(pin: String): Boolean {
        val p = prefs.value ?: return false
        if (pin != p.kidsPin) return false
        viewModelScope.launch { settings.setKidsMode(false) }
        return true
    }

    fun remove(item: MediaEntity) {
        viewModelScope.launch { library.delete(item.uri) }
    }

    fun setFilter(value: LibraryFilter) { filter.value = value }
    fun setSort(value: SortOrder) = viewModelScope.launch { settings.setSort(value) }
    fun setTheme(value: ThemeMode) = viewModelScope.launch { settings.setTheme(value) }
    fun setUiMode(value: UiMode) = viewModelScope.launch { settings.setUiMode(value) }
    fun setTorrEngine(mode: TorrEngineMode, address: String) = viewModelScope.launch { settings.setTorrEngine(mode, address) }
    fun setRezkaHost(value: String) = viewModelScope.launch { settings.setRezkaHost(value) }
    fun setBypass(mode: app.couchy.core.bypass.BypassMode, kind: app.couchy.core.bypass.BypassKind) = viewModelScope.launch { settings.setBypass(mode, kind) }
    fun setBypassConfig(value: String) = viewModelScope.launch { settings.setBypassConfig(value) }
    fun forgetBypassHosts() = viewModelScope.launch { settings.clearBypassHosts() }

    private fun unsupportedText(kind: LinkKind) = when (kind) {
        LinkKind.YOUTUBE -> "YouTube появится отдельным разделом в следующих сборках."
        else -> "Эту ссылку пока не получается открыть."
    }
}
