package app.couchy.youtube

import app.couchy.data.Marks
import app.couchy.data.SavedTitles
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.couchy.CouchyApp
import app.couchy.Playback
import app.couchy.Skip
import app.couchy.core.Progress
import app.couchy.core.youtube.SponsorBlock
import app.couchy.core.youtube.YtChannel
import app.couchy.core.youtube.YtVideo
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.Page

/** What the list shows: the channels followed, one of YouTube's sections, or search results. */
sealed interface YtShelf {
    data object Subscriptions : YtShelf
    data class Kiosk(val id: String?, val name: String) : YtShelf
    data class Search(val query: String) : YtShelf
}

data class YtListUi(
    val videos: List<YtVideo> = emptyList(),
    val channels: List<YtChannel> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val end: Boolean = false,
)

data class YtChannelUi(val channel: YtChannel, val list: YtListUi = YtListUi(loading = true))

data class YouTubeUi(
    val shelf: YtShelf? = null,
    val kiosks: List<YtShelf.Kiosk> = emptyList(),
    val list: YtListUi = YtListUi(),
    val query: String = "",
    val subscriptions: List<YtChannel> = emptyList(),
    val channel: YtChannelUi? = null,
    val history: List<String> = emptyList(),
)

class YouTubeViewModel(app: Application) : AndroidViewModel(app) {
    private val library = (app as CouchyApp).library
    private val store = app.getSharedPreferences("youtube", android.content.Context.MODE_PRIVATE)
    private val marks = (app as CouchyApp).marks

    private val _state = MutableStateFlow(YouTubeUi(subscriptions = loadSubs(), history = loadHistory()))
    val state: StateFlow<YouTubeUi> = _state.asStateFlow()

    private val _autoPlay = MutableStateFlow<Playback?>(null)
    val autoPlay: StateFlow<Playback?> = _autoPlay.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        // Follows can arrive from another device through sync.
        viewModelScope.launch {
            marks.all.collect {
                val subs = marks.list(Marks.YOUTUBE).map { o -> YtChannel(o.getString("url"), o.optString("name"), o.optString("avatar").ifBlank { null }) }
                if (subs != _state.value.subscriptions) _state.update { s -> s.copy(subscriptions = subs) }
            }
        }
    }

    private var next: Page? = null
    private var channelNext: Page? = null
    private var listJob: Job? = null

    /** First visit: the followed channels when there are any, YouTube's front shelf otherwise. */
    fun start() {
        if (_state.value.shelf != null) return
        viewModelScope.launch {
            val kiosks = runCatching { YouTubeClient.kiosks().map { (id, name) -> YtShelf.Kiosk(id, name) } }.getOrDefault(emptyList())
            _state.update { it.copy(kiosks = kiosks) }
        }
        show(if (_state.value.subscriptions.isNotEmpty()) YtShelf.Subscriptions else YtShelf.Kiosk(null, "Популярное"))
    }

    fun setQuery(q: String) = _state.update { it.copy(query = q) }

    fun search(q: String = _state.value.query) {
        val query = q.trim().ifEmpty { return }
        // A pasted video link plays right away.
        if (app.couchy.core.youtube.YouTubeLinks.isVideo(query)) { play(query); return }
        rememberQuery(query)
        _state.update { it.copy(query = query, channel = null) }
        show(YtShelf.Search(query))
    }

    fun show(shelf: YtShelf) {
        listJob?.cancel()
        next = null
        _state.update { it.copy(shelf = shelf, list = YtListUi(loading = true), channel = null) }
        listJob = viewModelScope.launch {
            try {
                when (shelf) {
                    YtShelf.Subscriptions -> {
                        val videos = YouTubeClient.subscriptionsFeed(_state.value.subscriptions)
                        _state.update { it.copy(list = YtListUi(videos, end = true)) }
                    }
                    is YtShelf.Kiosk -> {
                        val page = YouTubeClient.kiosk(shelf.id)
                        next = page.next
                        _state.update { it.copy(list = YtListUi(page.videos, end = page.next == null)) }
                    }
                    is YtShelf.Search -> {
                        val page = YouTubeClient.search(shelf.query)
                        next = page.next
                        _state.update { it.copy(list = YtListUi(page.videos, page.channels, end = page.next == null)) }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(list = it.list.copy(loading = false, error = problem(e))) }
            }
        }
    }

    fun retry() { _state.value.shelf?.let(::show) }

    fun loadMore() {
        val shelf = _state.value.shelf ?: return
        val page = next ?: return
        if (_state.value.list.loading) return
        _state.update { it.copy(list = it.list.copy(loading = true, error = null)) }
        listJob = viewModelScope.launch {
            try {
                val more = when (shelf) {
                    is YtShelf.Kiosk -> YouTubeClient.kiosk(shelf.id, page)
                    is YtShelf.Search -> YouTubeClient.search(shelf.query, page)
                    YtShelf.Subscriptions -> return@launch
                }
                next = more.next
                _state.update { s ->
                    val known = s.list.videos.map { it.id }.toSet()
                    s.copy(list = s.list.copy(videos = s.list.videos + more.videos.filter { it.id !in known }, loading = false, end = more.next == null))
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(list = it.list.copy(loading = false, error = problem(e))) }
            }
        }
    }

    fun openChannel(url: String, name: String = "") {
        channelNext = null
        _state.update { it.copy(channel = YtChannelUi(it.subscriptions.firstOrNull { c -> c.url == url } ?: YtChannel(url, name))) }
        viewModelScope.launch {
            try {
                val (channel, page) = YouTubeClient.channel(url)
                channelNext = page.next
                _state.update { s -> if (s.channel?.channel?.url != url) s else s.copy(channel = YtChannelUi(channel, YtListUi(page.videos, end = true))) }
                // A followed channel keeps its picture and name up to date.
                if (_state.value.subscriptions.any { it.url == url }) saveSubs(_state.value.subscriptions.map { if (it.url == url) channel else it })
            } catch (e: Exception) {
                _state.update { s -> s.copy(channel = s.channel?.copy(list = YtListUi(error = problem(e)))) }
            }
        }
    }

    fun closeChannel() = _state.update { it.copy(channel = null) }

    fun toggleSubscription(channel: YtChannel) {
        val subs = _state.value.subscriptions
        saveSubs(if (subs.any { it.url == channel.url }) subs.filter { it.url != channel.url } else listOf(channel) + subs)
    }

    fun clearHistory() {
        store.edit().remove("history").apply()
        _state.update { it.copy(history = emptyList()) }
    }

    /** Opens a video by its link, from a list, the library or a link shared from another app. */
    fun play(link: String, fromStart: Boolean = false) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                val s = YouTubeClient.stream(link)
                val key = s.video.url
                val entry = library.open(key, s.video.title)
                if (fromStart) library.clearProgress(key)
                _autoPlay.value = Playback(
                    uri = s.uri,
                    title = s.video.title,
                    startMs = if (fromStart || s.video.live) 0 else Progress.resumeFrom(entry.positionMs, entry.durationMs),
                    audioLanguage = entry.audioLanguage,
                    subtitleLanguage = entry.subtitleLanguage ?: "",
                    headers = mapOf("User-Agent" to s.userAgent),
                    key = key,
                    subtitles = s.subtitles,
                    variants = s.variants,
                    audioUri = s.audioUri,
                    skips = s.skips.map { Skip(it.startMs, it.endMs, SponsorBlock.label(it.category)) },
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = problem(e)
            } finally {
                _busy.value = false
            }
        }
    }

    fun autoPlayHandled() { _autoPlay.value = null }
    fun dismissError() { _error.value = null }

    private fun problem(e: Exception): String = when {
        e is java.net.UnknownHostException || e is java.net.SocketTimeoutException -> "YouTube не отвечает. Проверьте интернет или включите обход блокировок."
        e.javaClass.simpleName == "ReCaptchaException" -> "YouTube временно ограничил запросы. Попробуйте через несколько минут."
        e.javaClass.simpleName.contains("AgeRestricted") -> "Видео с ограничением по возрасту: YouTube показывает его только после входа."
        e.javaClass.simpleName.contains("Unavailable") || e.javaClass.simpleName.contains("Private") -> "Видео недоступно."
        else -> e.message?.takeIf { it.length < 120 } ?: "Не удалось загрузить YouTube."
    }

    // ---- Stored lists ----

    private fun loadSubs(): List<YtChannel> {
        // Follows made before they synced lived in the YouTube settings; they move over once.
        store.getString("subs", null)?.let { old ->
            runCatching {
                val array = JSONArray(old)
                (0 until array.length()).map { array.getJSONObject(it) }.forEach { o ->
                    marks.put("${Marks.YOUTUBE}|${o.getString("url")}", JSONObject().put("url", o.getString("url")).put("name", o.optString("name")).put("avatar", o.optString("avatar")))
                }
            }
            store.edit().remove("subs").apply()
        }
        return marks.list(Marks.YOUTUBE).map { o ->
            YtChannel(o.getString("url"), o.optString("name"), o.optString("avatar").ifBlank { null })
        }
    }

    private fun saveSubs(list: List<YtChannel>) {
        val prefix = Marks.YOUTUBE
        val now = System.currentTimeMillis()
        val keep = marks.all.value.filterKeys { !it.startsWith("$prefix|") }
        // Newest follow first: the order is kept in the time stamps.
        val subs = list.mapIndexed { i, c ->
            "$prefix|${c.url}" to JSONObject().put("url", c.url).put("name", c.name).put("avatar", c.avatar ?: "").put("at", now - i).toString()
        }.toMap()
        marks.replaceAll(keep + subs)
        _state.update { it.copy(subscriptions = list) }
        (getApplication<CouchyApp>()).sync.syncSoon()
    }

    private fun loadHistory(): List<String> = runCatching {
        val array = JSONArray(store.getString("history", "[]"))
        (0 until array.length()).map { array.getString(it) }
    }.getOrDefault(emptyList())

    private fun rememberQuery(q: String) {
        val list = (listOf(q) + _state.value.history.filter { !it.equals(q, ignoreCase = true) }).take(12)
        store.edit().putString("history", JSONArray(list).toString()).apply()
        _state.update { it.copy(history = list) }
    }
}
