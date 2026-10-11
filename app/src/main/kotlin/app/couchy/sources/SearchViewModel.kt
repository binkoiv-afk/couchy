package app.couchy.sources

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.couchy.CouchyApp
import app.couchy.Playback
import app.couchy.core.Progress
import app.couchy.core.sources.EpisodeRef
import app.couchy.core.sources.Rezka
import app.couchy.core.sources.RezkaPage
import app.couchy.core.sources.Title
import app.couchy.core.sources.TitleKind
import app.couchy.core.sources.TitleMerge
import app.couchy.core.sources.TorrentGroup
import app.couchy.core.sources.TorrentHits
import app.couchy.core.sources.Translator
import app.couchy.data.MediaEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SearchMode(val label: String) { ALL("Всё"), TORRENTS("Только торренты") }

data class SearchState(
    val query: String = "",
    val mode: SearchMode = SearchMode.ALL,
    val loading: Boolean = false,
    val searched: Boolean = false,
    val titles: List<Title> = emptyList(),
    val torrents: List<TorrentGroup> = emptyList(),
    val error: String? = null,
    val history: List<String> = emptyList(),
    /** The site needs its check passed before its results can be shown. */
    val siteBlocked: Boolean = false,
)

enum class SourceStatus { LOADING, READY, NOT_FOUND, BLOCKED, ERROR }

/** HDrezka's part of a title page. */
data class RezkaUi(
    val status: SourceStatus = SourceStatus.LOADING,
    val error: String? = null,
    val url: String? = null,
    val page: RezkaPage? = null,
    val translator: Int? = null,
    val episodes: List<EpisodeRef> = emptyList(),
    val season: Int? = null,
    val episodesLoading: Boolean = false,
)

data class TitleUi(
    val title: Title,
    val description: String? = null,
    val rezka: RezkaUi = RezkaUi(),
    val torrents: List<TorrentGroup> = emptyList(),
    val torrentsLoading: Boolean = true,
    /** Progress per library key, for episode marks. */
    val progress: Map<String, Float> = emptyMap(),
    val watched: Set<String> = emptySet(),
)

class SearchViewModel(app: Application) : AndroidViewModel(app) {
    private val couchy = app as CouchyApp
    private val library = couchy.library
    private val web = Web(app)
    val userAgent: String get() = web.userAgent
    private var host = Rezka.DEFAULT_HOST
    private val catalog = Catalog(web, RezkaClient(web) { host })

    private val _search = MutableStateFlow(SearchState())
    val search: StateFlow<SearchState> = _search.asStateFlow()

    private val _title = MutableStateFlow<TitleUi?>(null)
    val title: StateFlow<TitleUi?> = combine(_title, library.all) { t, all -> t?.withProgress(all) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** A page the person should open in the browser window to pass the site's check. */
    private val _challenge = MutableStateFlow<String?>(null)
    val challenge: StateFlow<String?> = _challenge.asStateFlow()
    private var retry: (() -> Unit)? = null

    /** Playback asked for from outside a screen (the library reopening an episode). */
    private val _autoPlay = MutableStateFlow<Playback?>(null)
    val autoPlay: StateFlow<Playback?> = _autoPlay.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var searchJob: Job? = null
    private var titleJob: Job? = null

    init {
        viewModelScope.launch {
            couchy.settings.prefs.collect { p ->
                host = p.rezkaHost.ifBlank { Rezka.DEFAULT_HOST }
                _search.update { it.copy(history = p.searchHistory) }
            }
        }
    }

    fun setQuery(text: String) {
        _search.update { it.copy(query = text) }
        // Typing on a phone searches by itself after a pause; the remote's keyboard submits.
        searchJob?.cancel()
        if (text.trim().length >= 3) searchJob = viewModelScope.launch { delay(700); run(text) }
    }

    fun setMode(mode: SearchMode) {
        _search.update { it.copy(mode = mode) }
        submit()
    }

    fun submit(text: String = _search.value.query) {
        _search.update { it.copy(query = text) }
        searchJob?.cancel()
        if (text.isNotBlank()) searchJob = viewModelScope.launch { run(text, remember = true) }
    }

    fun clearHistory() = viewModelScope.launch { couchy.settings.setSearchHistory(emptyList()) }

    private suspend fun run(text: String, remember: Boolean = false) {
        val query = text.trim()
        _search.update { it.copy(loading = true, error = null) }
        try {
            if (_search.value.mode == SearchMode.TORRENTS) {
                val hits = catalog.rawTorrents(query)
                _search.update { it.copy(loading = false, searched = true, torrents = TorrentHits.group(hits), titles = emptyList(), siteBlocked = false) }
            } else {
                val result = catalog.search(query)
                _search.update { it.copy(loading = false, searched = true, titles = result.titles, torrents = emptyList(), siteBlocked = result.challengeUrl != null) }
                if (result.challengeUrl != null) ask(result.challengeUrl) { submit(query) }
            }
            if (remember) {
                val history = (listOf(query) + _search.value.history.filter { !it.equals(query, ignoreCase = true) }).take(12)
                couchy.settings.setSearchHistory(history)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _search.update { it.copy(loading = false, searched = true, error = "Не получилось найти. Проверьте интернет.") }
        }
    }

    // ---- Title page ----

    fun openTitle(title: Title) {
        _title.value = TitleUi(title)
        titleJob?.cancel()
        titleJob = viewModelScope.launch {
            launch { loadTorrents(title) }
            launch { loadRezka(title) }
        }
    }

    fun closeTitle() {
        titleJob?.cancel()
        _title.value = null
    }

    private suspend fun loadTorrents(start: Title) {
        var title = start
        if (title.imdbId == null) catalog.findImdb(title)?.let { id -> title = title.copy(imdbId = id); patch { it.copy(title = it.title.copy(imdbId = id)) } }
        val hits = runCatching { catalog.torrents(title) }.getOrDefault(emptyList())
        patch { it.copy(torrents = TorrentHits.group(hits), torrentsLoading = false) }
    }

    private suspend fun loadRezka(title: Title) {
        patchRezka { RezkaUi(SourceStatus.LOADING) }
        try {
            val url = title.rezkaUrl ?: findOnRezka(title)
            if (url == null) {
                patchRezka { RezkaUi(SourceStatus.NOT_FOUND) }
                return
            }
            val page = catalog.rezka.page(url)
            val translator = page.defaultTranslator ?: page.translators.firstOrNull()?.id
            val firstSeason = page.episodes.firstOrNull()?.season
            patchRezka { RezkaUi(SourceStatus.READY, url = url, page = page, translator = translator, episodes = page.episodes, season = firstSeason) }
            patch { it.copy(description = page.description ?: it.description, title = it.title.copy(rezkaUrl = url, poster = it.title.poster ?: page.poster)) }
        } catch (e: ChallengeException) {
            patchRezka { RezkaUi(SourceStatus.BLOCKED, url = title.rezkaUrl) }
            ask(e.url) { _title.value?.let { t -> viewModelScope.launch { loadRezka(t.title) } } }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            patchRezka { RezkaUi(SourceStatus.ERROR, error = e.message) }
        }
    }

    private suspend fun findOnRezka(title: Title): String? {
        val names = listOfNotNull(title.original, title.name).distinct()
        for (name in names) {
            val found = catalog.rezka.search(name)
            val wanted = (names + title.altNames).map(TitleMerge::normalize).toSet()
            found.firstOrNull { f ->
                val fNames = (listOf(f.name) + f.altNames + listOfNotNull(f.original)).map(TitleMerge::normalize)
                fNames.any { it in wanted } && TitleMerge.yearsClose(title.year, f.year)
            }?.rezkaUrl?.let { return it }
        }
        return null
    }

    fun retryRezka() { _title.value?.let { t -> viewModelScope.launch { loadRezka(t.title) } } }

    fun selectTranslator(id: Int) {
        val r = _title.value?.rezka ?: return
        val page = r.page ?: return
        patchRezka { it.copy(translator = id) }
        if (!page.series) return
        viewModelScope.launch {
            patchRezka { it.copy(episodesLoading = true) }
            try {
                val eps = catalog.rezka.episodes(page.id, id, r.url!!)
                patchRezka { it.copy(episodes = eps, season = it.season?.takeIf { s -> eps.any { e -> e.season == s } } ?: eps.firstOrNull()?.season, episodesLoading = false) }
            } catch (e: ChallengeException) {
                patchRezka { it.copy(episodesLoading = false) }
                ask(e.url) { selectTranslator(id) }
            } catch (e: Exception) {
                patchRezka { it.copy(episodesLoading = false) }
                _error.value = "Не удалось загрузить серии в этой озвучке."
            }
        }
    }

    fun selectSeason(season: Int) = patchRezka { it.copy(season = season) }

    /** Starts the film, or an episode of a series, from HDrezka. */
    suspend fun playRezka(season: Int = 0, episode: Int = 0, fromStart: Boolean = false): Playback? {
        val t = _title.value ?: return null
        val r = t.rezka
        val page = r.page ?: return null
        val url = r.url ?: return null
        val translator = page.translators.firstOrNull { it.id == r.translator } ?: Translator(r.translator ?: return null, "")
        val name = t.title.name
        return guarded({ viewModelScope.launch { playRezka(season, episode, fromStart)?.let { _autoPlay.value = it } } }) {
            build(url, page.id, page.series, translator, season, episode, name, r.episodes, fromStart)
        }
    }

    /** Reopens an episode saved in the library. */
    fun playRef(ref: String) {
        val parsed = Rezka.parseRef(ref) ?: return
        val id = Rezka.idFromUrl(parsed.pageUrl) ?: return
        viewModelScope.launch {
            val playback = guarded({ playRef(ref) }) {
                val series = parsed.season > 0
                val eps = if (series) runCatching { catalog.rezka.episodes(id, parsed.translator, parsed.pageUrl) }.getOrDefault(emptyList()) else emptyList()
                val entry = library.get(ref)
                build(parsed.pageUrl, id, series, Translator(parsed.translator, ""), parsed.season, parsed.episode, entry?.title?.substringBefore(" · ") ?: "HDrezka", eps, false)
            }
            playback?.let { _autoPlay.value = it }
        }
    }

    fun autoPlayHandled() { _autoPlay.value = null }

    private suspend fun build(
        url: String, id: Int, series: Boolean, translator: Translator, season: Int, episode: Int,
        name: String, episodes: List<EpisodeRef>, fromStart: Boolean,
    ): Playback {
        val stream = if (series) catalog.rezka.episode(id, translator.id, season, episode, url) else catalog.rezka.movie(id, translator, url)
        val best = stream.options.maxByOrNull { it.rank } ?: throw IllegalStateException("Нет видео")
        val key = Rezka.ref(url, translator.id, season, episode)
        val title = if (series) "$name · S${season}E$episode" else name
        val entry = library.open(key, title)
        if (fromStart) library.clearProgress(key)
        val next = if (series) {
            val i = episodes.indexOfFirst { it.season == season && it.episode == episode }
            episodes.getOrNull(i + 1)?.takeIf { i >= 0 }?.let { Rezka.ref(url, translator.id, it.season, it.episode) }
        } else null
        return Playback(
            uri = best.url,
            title = entry.title,
            startMs = if (fromStart) 0 else Progress.resumeFrom(entry.positionMs, entry.durationMs),
            audioLanguage = entry.audioLanguage,
            subtitleLanguage = entry.subtitleLanguage,
            key = key,
            headers = mapOf("User-Agent" to web.userAgent),
            subtitles = stream.subtitles.map { it.label to it.url },
            nextRef = next,
        )
    }

    private suspend fun <T> guarded(again: () -> Unit, block: suspend () -> T): T? {
        _busy.value = true
        return try {
            block()
        } catch (e: ChallengeException) {
            ask(e.url, again)
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _error.value = e.message?.takeIf { it.length < 120 } ?: "Не удалось открыть видео."
            null
        } finally {
            _busy.value = false
        }
    }

    fun dismissError() { _error.value = null }

    // ---- Visitor checks ----

    private fun ask(url: String, again: () -> Unit) {
        retry = again
        _challenge.value = url
    }

    fun challengePassed() {
        _challenge.value = null
        retry?.invoke()
        retry = null
    }

    fun challengeDismissed() {
        _challenge.value = null
        retry = null
    }

    private fun patch(f: (TitleUi) -> TitleUi) = _title.update { it?.let(f) }
    private fun patchRezka(f: (RezkaUi) -> RezkaUi) = patch { it.copy(rezka = f(it.rezka)) }

    private fun TitleUi.withProgress(all: List<MediaEntity>): TitleUi {
        val url = rezka.url ?: return this
        val prefix = url.substringBefore('#') + "#couchy-rezka="
        val mine = all.filter { it.uri.startsWith(prefix) }
        // Marks follow the episode, whatever voice-over it was watched in.
        fun episodeKey(uri: String) = uri.substringAfter(prefix).substringAfter(':')
        return copy(
            progress = mine.associate { episodeKey(it.uri) to Progress.fraction(it.positionMs, it.durationMs) },
            watched = mine.filter { Progress.isWatched(it.positionMs, it.durationMs) }.map { episodeKey(it.uri) }.toSet(),
        )
    }
}
