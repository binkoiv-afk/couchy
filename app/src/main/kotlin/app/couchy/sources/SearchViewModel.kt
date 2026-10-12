package app.couchy.sources

import app.couchy.data.Marks
import app.couchy.episodes.NewEpisodes
import app.couchy.core.sources.QueryFix
import app.couchy.data.SavedTitles
import app.couchy.BuildConfig
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.couchy.CouchyApp
import app.couchy.Playback
import app.couchy.core.Progress
import app.couchy.core.sources.CatalogFilter
import app.couchy.core.sources.EpisodeRef
import app.couchy.core.sources.RezkaCatalog
import app.couchy.core.sources.RezkaDetails
import app.couchy.core.sources.TitleJson
import app.couchy.core.sources.Rezka
import app.couchy.core.sources.RezkaPage
import app.couchy.core.sources.Site
import app.couchy.core.sources.SiteHit
import app.couchy.core.sources.SiteRef
import app.couchy.core.sources.Title
import app.couchy.core.sources.TitleKind
import app.couchy.core.sources.TitleMerge
import app.couchy.core.sources.TorrentGroup
import app.couchy.core.sources.TorrentHits
import app.couchy.core.sources.Translator
import app.couchy.core.sources.Sites
import app.couchy.data.MediaEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SearchMode(val label: String) {
    ALL("Всё"), MOVIES("Фильмы"), SERIES("Сериалы"), TORRENTS("Торренты");

    /** Films, series and "all" are one search shown three ways; torrents are a different search. */
    val titles: Boolean get() = this != TORRENTS
}

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
    /** Some results are shown and a slower source is still being asked. */
    val moreLoading: Boolean = false,
    /** Nothing was found for the query as said or typed, so these are for a corrected one. */
    val corrected: String? = null,
    /** Quick guesses while typing: titles from IMDb's suggest and matching past searches. */
    val suggestions: List<Title> = emptyList(),
    val hints: List<String> = emptyList(),
    /** The query was edited and its full results are not on screen yet. */
    val typing: Boolean = false,
)

/** What Home shows to pick from: HDrezka's catalogue. Empty when the site cannot be reached. */
data class Feed(
    val fresh: List<Title> = emptyList(),
    val films: List<Title> = emptyList(),
    val series: List<Title> = emptyList(),
    val cartoons: List<Title> = emptyList(),
    /** Picked by the site as similar to what was watched last. */
    val similar: List<Title> = emptyList(),
    val similarTo: String? = null,
    /** What everybody watches this week (TMDB). */
    val trending: List<Title> = emptyList(),
    /** HDrezka's new arrivals, when the site answers. */
    val rezka: List<Title> = emptyList(),
    /** Picked for the person from what they loved and scored high. */
    val forYou: List<Title> = emptyList(),
    val forYouBecause: String? = null,
    /** In cinemas now (TMDB). */
    val cinema: List<Title> = emptyList(),
    val best: List<Title> = emptyList(),
    val bestSeries: List<Title> = emptyList(),
    val anime: List<Title> = emptyList(),
    /** The most popular in a few genres, each its own row. */
    val genres: List<Pair<app.couchy.core.sources.Genre, List<Title>>> = emptyList(),
    val loaded: Boolean = false,
) {
    /** Titles for the big banner at the top of Home: wide stills first. */
    val featured: List<Title> get() = (trending + films + fresh + rezka).filter { it.backdrop != null || it.poster != null }
        .sortedBy { if (it.backdrop != null) 0 else 1 }.distinctBy { it.key }.take(6)
}

/** A list of titles opened from a title page or the catalogue. */
sealed interface ListQuery {
    data class Person(val name: String, val url: String) : ListQuery
    data class Catalog(val filter: CatalogFilter) : ListQuery
}

data class ListingUi(
    val query: ListQuery,
    val titles: List<Title> = emptyList(),
    val loading: Boolean = true,
    val page: Int = 0,
    val end: Boolean = false,
    val error: String? = null,
) {
    val heading: String get() = when (query) {
        is ListQuery.Person -> query.name
        is ListQuery.Catalog -> RezkaCatalog.describe(query.filter)
    }
}

/** What is open over the tabs, newest last: title pages and lists, so Back walks back through them. */
sealed interface Route {
    data class TitlePage(val title: Title) : Route
    data class Listing(val query: ListQuery) : Route
}

enum class SourceStatus { LOADING, READY, NOT_FOUND, BLOCKED, ERROR }

/** A series from AniLibria or Kino.pub: which episodes were watched, and the one to go on with. */
data class NativePick(val release: app.couchy.core.sources.NativeRelease, val watched: Set<Pair<Int, Int>>, val next: Pair<Int, Int>?)

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
    /** A voice-over was picked for this title before, so "Смотреть" does not ask again. */
    val voiceKnown: Boolean = false,
)

data class TitleUi(
    val title: Title,
    val description: String? = null,
    /** From TMDB or Cinemeta: description, cast with photos, recommendations. */
    val info: app.couchy.core.sources.MetaInfo? = null,
    val rezka: RezkaUi = RezkaUi(),
    val torrents: List<TorrentGroup> = emptyList(),
    val torrentsLoading: Boolean = true,
    /** The same title on other online cinemas, played through their own players. */
    val sites: List<SiteHit> = emptyList(),
    val sitesLoading: Boolean = true,
    /** Progress per library key, for episode marks. */
    val progress: Map<String, Float> = emptyMap(),
    val watched: Set<String> = emptySet(),
    /** In "Смотреть позже" and "Любимое". */
    val later: Boolean = false,
    val loved: Boolean = false,
    val following: Boolean = false,
    val seen: Boolean = false,
    /** The person's own score, 1 to 10. */
    val score: Int? = null,
    /** The person's folders, and the ids of those this title is in. */
    val folders: List<app.couchy.data.Folder> = emptyList(),
    val inFolders: Set<String> = emptySet(),
)

/**
 * A site page open in Couchy's window: its player runs there with ads cut out, and the
 * first video it starts is handed to Couchy's player.
 */
data class SiteWindow(
    val url: String,
    val site: Site?,
    /** Shown in the player and the library. */
    val title: String,
    val series: Boolean = false,
    /** Library key for resuming; null while the person still has to find the film on the site. */
    val key: String? = null,
)

class SearchViewModel(app: Application) : AndroidViewModel(app) {
    private val couchy = app as CouchyApp
    private val library = couchy.library
    private val web = Web(app)
    val userAgent: String get() = web.userAgent
    private var host = Rezka.DEFAULT_HOST
    private val catalog = Catalog(web, RezkaClient(web) { host })
    private val siteClient = SiteClient(web)
    private val native = NativeClient(web, couchy.kinopub)
    private var tmdbKey = ""
    private val meta = MetaClient(web) { tmdbKey }

    private val marks = couchy.marks

    /** "Смотреть позже" and "Любимое": the person's own lists of films and series. */
    val saved: StateFlow<SavedTitles> = marks.all
        .map { SavedTitles(marks.titles(Marks.LATER), marks.titles(Marks.LOVED), marks.titles(Marks.SEEN), marks.titles(Marks.RATED), marks.scores(), marks.folders()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SavedTitles())

    fun toggleLater(t: Title) = keep(Marks.LATER, t)
    fun toggleLoved(t: Title) = keep(Marks.LOVED, t)

    /** A series put in a list is followed too, so its new episodes are announced. */
    private fun keep(list: String, t: Title) {
        val adding = !marks.hasTitle(list, t)
        marks.toggleTitle(list, t)
        if (adding && t.kind == TitleKind.SERIES && t.imdbId != null && !marks.hasTitle(Marks.FOLLOW, t)) {
            marks.toggleTitle(Marks.FOLLOW, t)
            NewEpisodes.checkSoon(couchy)
        }
        couchy.sync.syncSoon()
    }

    /** Series the person hears about when a new episode is out. */
    val following: StateFlow<Set<String>> = marks.all
        .map { NewEpisodes.followed(marks).mapNotNull { it.imdbId }.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** "Просмотрено": a watched film leaves "Смотреть позже" and the picks made for the person. */
    fun toggleInFolder(id: String, t: Title) {
        marks.toggleInFolder(id, t)
        couchy.sync.syncSoon()
    }

    /** A new folder, with [t] already in it when given. */
    fun addFolder(name: String, t: Title? = null) {
        if (name.isBlank()) return
        val id = marks.addFolder(name)
        t?.let { marks.toggleInFolder(id, it) }
        couchy.sync.syncSoon()
    }

    fun renameFolder(id: String, name: String) {
        if (name.isBlank()) return
        marks.renameFolder(id, name)
        couchy.sync.syncSoon()
    }

    fun removeFolder(id: String) {
        marks.removeFolder(id)
        couchy.sync.syncSoon()
    }

    fun toggleSeen(t: Title) {
        val adding = !marks.hasTitle(Marks.SEEN, t)
        marks.toggleTitle(Marks.SEEN, t)
        if (adding && marks.hasTitle(Marks.LATER, t)) marks.toggleTitle(Marks.LATER, t)
        couchy.sync.syncSoon()
    }

    fun rate(t: Title, score: Int?) {
        marks.rate(t, score)
        if (score != null && !marks.hasTitle(Marks.SEEN, t)) marks.toggleTitle(Marks.SEEN, t)
        couchy.sync.syncSoon()
        viewModelScope.launch { loadForYou() }
    }

    fun toggleFollow(t: Title) {
        val on = marks.hasTitle(Marks.FOLLOW, t)
        marks.toggleTitle(Marks.FOLLOW, t)
        couchy.sync.syncSoon()
        if (!on) NewEpisodes.checkSoon(couchy)
    }

    private val _siteWindow = MutableStateFlow<SiteWindow?>(null)
    val siteWindow: StateFlow<SiteWindow?> = _siteWindow.asStateFlow()

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

    private val _routes = MutableStateFlow<List<Route>>(emptyList())
    val route: StateFlow<Route?> = _routes.map { it.lastOrNull() }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _listing = MutableStateFlow<ListingUi?>(null)
    val listing: StateFlow<ListingUi?> = _listing.asStateFlow()
    private var listingJob: Job? = null

    /** Pages left behind, so Back shows them as they were. */
    private val savedTitles = HashMap<String, TitleUi>()
    private val savedListings = HashMap<ListQuery, ListingUi>()

    /** Past searches' results, so a search from the history opens at once. */
    private val cacheFile = java.io.File(app.filesDir, "search-cache.json")
    /** The voice-over picked per title, and the last one picked at all. */
    private val voices = app.getSharedPreferences("voices", android.content.Context.MODE_PRIVATE)
    private val cache: MutableMap<String, List<Title>> by lazy {
        runCatching {
            val o = org.json.JSONObject(cacheFile.readText())
            o.keys().asSequence().associateWith { k -> TitleJson.decode(o.getJSONArray(k)) }.toMutableMap()
        }.getOrDefault(mutableMapOf())
    }

    init {
        viewModelScope.launch {
            couchy.settings.prefs.collect { p ->
                host = p.rezkaHost.ifBlank { Rezka.DEFAULT_HOST }
                // A key typed in settings wins over the one built into the app.
                val key = p.tmdbKey.ifBlank { BuildConfig.TMDB_KEY }
                if (key != tmdbKey) {
                    val changed = _feed.value.loaded
                    tmdbKey = key
                    // A new key changes the language of everything: Home is loaded again.
                    if (changed) loadFeed(force = true)
                }
                _search.update { it.copy(history = p.searchHistory) }
            }
        }
    }

    private val _feed = MutableStateFlow(Feed())
    val feed: StateFlow<Feed> = _feed.asStateFlow()
    private var feedAt = 0L

    /** Loads the catalogue for Home; again only after a while, so coming back to Home is instant. */
    fun loadFeed(force: Boolean = false) {
        if (!force && System.currentTimeMillis() - feedAt < 30 * 60_000L && _feed.value.loaded) return
        feedAt = System.currentTimeMillis()
        viewModelScope.launch {
            kotlinx.coroutines.coroutineScope {
                val rezka = async { runCatching { catalog.rezka.list("/new/") }.getOrDefault(emptyList()) }
                val main = runCatching { meta.feed() }.getOrDefault(Feed(loaded = true))
                _feed.value = main.copy(similar = _feed.value.similar, similarTo = _feed.value.similarTo)
                // The TV screensaver shows these stills while the box rests.
                app.couchy.dream.CouchyDream.keep(getApplication(), main.trending + main.cinema + main.best + main.bestSeries)
                _feed.update { it.copy(rezka = rezka.await()) }
            }
            loadSimilar()
            loadForYou()
        }
    }

    /**
     * "Для вас": TMDB's recommendations for what the person loved or scored 7 and up, newest
     * first, without what they have seen or kept already.
     */
    private suspend fun loadForYou() {
        if (!meta.tmdb) return
        val scores = marks.scores()
        val liked = (marks.titles(Marks.RATED).filter { (scores[it.key] ?: 0) >= 7 } + marks.titles(Marks.LOVED)).distinctBy { it.key }.take(4)
        if (liked.isEmpty()) return
        val known = (marks.titles(Marks.SEEN) + marks.titles(Marks.RATED) + marks.titles(Marks.LOVED) + marks.titles(Marks.LATER)).map { it.key }.toSet()
        val lists = liked.map { t -> runCatching { meta.info(t)?.details?.similar }.getOrNull().orEmpty() }
        // Taking turns between the lists keeps one film from filling the row.
        val mixed = (0 until (lists.maxOfOrNull { it.size } ?: 0)).flatMap { i -> lists.mapNotNull { it.getOrNull(i) } }
            .filter { it.key !in known }.distinctBy { it.key }.take(24)
        _feed.update { it.copy(forYou = mixed, forYouBecause = liked.first().name) }
    }

    /** "Похоже на …": the site's picks for the last title watched from it. */
    private suspend fun loadSimilar() {
        val last = runCatching { library.snapshot() }.getOrDefault(emptyList())
            .filter { Rezka.isRef(it.uri) && it.lastWatchedAt > 0 }
            .maxByOrNull { it.lastWatchedAt } ?: return
        val ref = Rezka.parseRef(last.uri) ?: return
        val name = last.title.substringBefore(" · ")
        val page = runCatching { catalog.rezka.page(ref.pageUrl) }.getOrNull()
        val title = Title(page?.name ?: name, page?.original, kind = if (page?.series == true || ref.season > 0) TitleKind.SERIES else TitleKind.MOVIE,
            year = Regex("-(\\d{4})\\.html").find(ref.pageUrl)?.groupValues?.get(1)?.toIntOrNull())
        val fromMeta = if (meta.tmdb) runCatching { meta.info(title)?.details?.similar }.getOrNull().orEmpty() else emptyList()
        val picks = fromMeta.ifEmpty { page?.details?.let { (it.similar + it.parts) }.orEmpty() }.distinctBy { it.key }
        if (picks.isEmpty()) return
        _feed.update { it.copy(similar = picks, similarTo = page?.name ?: name) }
    }

    private var suggestJob: Job? = null

    fun setQuery(text: String) {
        val q = text.trim()
        val hints = if (q.isEmpty()) emptyList() else _search.value.history
            .filter { it.contains(q, ignoreCase = true) && !it.equals(q, ignoreCase = true) }.take(3)
        _search.update { it.copy(query = text, typing = q.isNotEmpty(), hints = hints, suggestions = if (q.length < 2) emptyList() else it.suggestions) }
        // Guesses come in a moment; the full search, from every source, after a pause.
        suggestJob?.cancel()
        if (q.length >= 2 && _search.value.mode.titles) suggestJob = viewModelScope.launch {
            delay(220)
            val found = runCatching { catalog.imdb(q) }.getOrNull() ?: return@launch
            _search.update { if (it.query.trim() == q) it.copy(suggestions = found.take(6)) else it }
        }
        // Typing on a phone searches by itself after a pause; the remote's keyboard submits.
        searchJob?.cancel()
        if (q.length >= 3) searchJob = viewModelScope.launch { delay(700); run(text) }
    }

    fun setMode(mode: SearchMode) {
        val before = _search.value.mode
        _search.update { it.copy(mode = mode) }
        // Between "all", films and series only the filter changes; the results are already here.
        if (before.titles && mode.titles && _search.value.searched) return
        submit()
    }

    /** Other ways the speech recogniser heard the last request, tried when the first finds nothing. */
    private var heardAlso: List<String> = emptyList()

    fun submitVoice(heard: List<String>) {
        val first = heard.firstOrNull { it.isNotBlank() } ?: return
        heardAlso = heard.drop(1)
        submit(first)
    }

    fun submit(text: String = _search.value.query) {
        _search.update { it.copy(query = text, typing = false, hints = emptyList()) }
        suggestJob?.cancel()
        searchJob?.cancel()
        if (text.isNotBlank()) searchJob = viewModelScope.launch { run(text, remember = true) }
    }

    /**
     * IMDb answers in a moment, HDrezka can take long (a slow mirror, or the bypass starting up).
     * Whatever comes first is shown at once and the rest is merged in when it arrives.
     */
    private suspend fun searchTitles(query: String, quiet: Boolean = false) {
        var imdb: Result<List<Title>>? = null
        var site: Result<List<Title>>? = null
        // TMDB answers only with a key; without one it counts as answered at once.
        var tmdb: Result<List<Title>>? = if (meta.tmdb) null else Result.success(emptyList())
        fun publish() {
            // Results from the history are on screen already: they are swapped only for complete ones.
            if (quiet && (imdb == null || site == null || tmdb == null)) return
            val challenge = (site?.exceptionOrNull() as? ChallengeException)?.url
            val joined = TitleMerge.joinTmdb(tmdb?.getOrNull().orEmpty(), site?.getOrNull().orEmpty())
            val titles = TitleMerge.merge(joined, imdb?.getOrNull().orEmpty())
            val pending = imdb == null || site == null || tmdb == null
            val failed = imdb?.isFailure == true && site?.isFailure == true && challenge == null
            _search.update {
                it.copy(
                    typing = it.typing && titles.isEmpty() && pending,
                    loading = pending && titles.isEmpty(),
                    moreLoading = pending && titles.isNotEmpty(),
                    searched = true,
                    titles = titles,
                    torrents = emptyList(),
                    siteBlocked = challenge != null,
                    error = if (failed) "Не получилось найти. Проверьте интернет." else null,
                )
            }
            if (quiet && titles.isEmpty()) cache[query.lowercase()]?.let { old -> _search.update { it.copy(titles = old, error = null) } }
        }
        kotlinx.coroutines.coroutineScope {
            if (tmdb == null) launch {
                tmdb = runCatching { meta.search(query) }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                publish()
            }
            launch {
                imdb = runCatching { catalog.imdb(query) }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                publish()
            }
            launch {
                site = runCatching { catalog.rezka.search(query) }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                publish()
                (site?.exceptionOrNull() as? ChallengeException)?.url?.let { url -> ask(url) { submit(query) } }
            }
        }
    }

    fun clearHistory() = viewModelScope.launch {
        couchy.settings.setSearchHistory(emptyList())
        cache.clear()
        saveCache()
    }

    private suspend fun rememberQuery(query: String) {
        val history = (listOf(query) + _search.value.history.filter { !it.equals(query, ignoreCase = true) }).take(12)
        couchy.settings.setSearchHistory(history)
        saveCache()
    }

    private suspend fun saveCache() = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val keep = (_search.value.history.map { it.lowercase() } + _search.value.query.trim().lowercase()).toSet()
        val o = org.json.JSONObject()
        cache.filterKeys { it in keep }.forEach { (k, v) -> o.put(k, TitleJson.encode(v.take(40))) }
        runCatching { cacheFile.writeText(o.toString()) }
    }

    private suspend fun run(text: String, remember: Boolean = false) {
        val query = text.trim()
        val cached = cache[query.lowercase()]?.takeIf { _search.value.mode.titles }
        if (cached != null) {
            _search.update { it.copy(typing = false, loading = false, moreLoading = false, searched = true, error = null, titles = cached, torrents = emptyList(), siteBlocked = false) }
        } else {
            _search.update { it.copy(loading = true, moreLoading = false, error = null, titles = emptyList()) }
        }
        try {
            if (_search.value.mode == SearchMode.TORRENTS) {
                val hits = catalog.rawTorrents(query)
                _search.update { it.copy(typing = false, loading = false, searched = true, torrents = TorrentHits.group(hits), titles = emptyList(), siteBlocked = false) }
            } else {
                _search.update { it.copy(corrected = null) }
                searchTitles(query, quiet = cached != null)
                // Nothing for the words as they came: try what they probably meant.
                if (_search.value.titles.isEmpty() && !_search.value.siteBlocked) {
                    val tries = (heardAlso + QueryFix.variants(query)).map { it.trim() }
                        .filter { it.isNotBlank() && !it.equals(query, ignoreCase = true) }.distinct().take(5)
                    for (t in tries) {
                        _search.update { it.copy(loading = true, error = null) }
                        searchTitles(t)
                        if (_search.value.titles.isNotEmpty()) {
                            _search.update { it.copy(corrected = t) }
                            break
                        }
                    }
                    heardAlso = emptyList()
                }
                val found = _search.value.titles
                if (found.isNotEmpty()) {
                    cache[query.lowercase()] = found
                    if (remember) saveCache()
                }
            }
            if (remember) rememberQuery(query)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _search.update { it.copy(typing = false, loading = false, searched = true, error = "Не получилось найти. Проверьте интернет.") }
        }
    }

    // ---- Title page ----

    fun openTitle(title: Title) {
        // Opening a result means the search found it: the query goes to the history.
        val q = _search.value.query.trim()
        if (q.isNotEmpty() && _search.value.titles.any { it.key == title.key }) viewModelScope.launch { rememberQuery(q) }
        leaveTop()
        _routes.update { it + Route.TitlePage(title) }
        loadTitle(title)
    }

    private fun loadTitle(title: Title) {
        _title.value = TitleUi(title)
        titleJob?.cancel()
        titleJob = viewModelScope.launch {
            launch { loadTorrents(title) }
            launch { loadRezka(title) }
            launch { loadSites(title) }
            launch { loadInfo(title) }
        }
    }

    private suspend fun loadInfo(start: Title) {
        var title = start
        if (title.imdbId == null && title.tmdbId == null && !meta.tmdb) catalog.findImdb(title)?.let { title = title.copy(imdbId = it) }
        val info = runCatching { meta.info(title) }.getOrNull() ?: return
        patch {
            it.copy(
                info = info,
                title = it.title.copy(
                    imdbId = it.title.imdbId ?: info.imdbId,
                    tmdbId = it.title.tmdbId ?: info.tmdbId,
                    original = it.title.original ?: info.original,
                    poster = it.title.poster ?: info.poster,
                    backdrop = it.title.backdrop ?: info.backdrop,
                    year = it.title.year ?: info.year,
                    rating = it.title.rating ?: info.rating,
                ),
            )
        }
    }

    /** An actor's or director's films, or the catalogue narrowed some way. */
    fun openListing(query: ListQuery) {
        leaveTop()
        _routes.update { it + Route.Listing(query) }
        startListing(query)
    }

    /** The catalogue with other filters, in place of the one on screen. */
    fun refilter(filter: CatalogFilter) {
        val routes = _routes.value
        if (routes.lastOrNull() !is Route.Listing) return openListing(ListQuery.Catalog(filter))
        _routes.value = routes.dropLast(1) + Route.Listing(ListQuery.Catalog(filter))
        startListing(ListQuery.Catalog(filter))
    }

    private fun startListing(query: ListQuery) {
        _listing.value = ListingUi(query)
        listingJob?.cancel()
        listingJob = viewModelScope.launch { loadListingPage() }
    }

    fun loadMore() {
        val l = _listing.value ?: return
        if (l.loading || l.end) return
        listingJob = viewModelScope.launch { loadListingPage() }
    }

    private suspend fun loadListingPage() {
        val start = _listing.value ?: return
        _listing.update { it?.copy(loading = true, error = null) }
        try {
            var page = start.page
            var added = emptyList<Title>()
            var end = false
            // A country's list holds every kind of title: a few pages may be needed for enough of the wanted kind.
            var tries = 0
            while (added.size < 12 && !end && tries < 4) {
                page += 1
                tries += 1
                val (raw, size) = when (val q = start.query) {
                    is ListQuery.Person ->
                        (if (q.url.startsWith("http")) catalog.rezka.listing(RezkaDetails.path(q.url))
                        else meta.person(app.couchy.core.sources.Link(q.name, q.url))).let { it to it.size }
                    is ListQuery.Catalog -> meta.catalogPage(q.filter, page)
                }
                val known = (start.titles + added).map { it.key }.toSet()
                val fresh = raw.filter { it.key !in known }
                end = size == 0 || start.query is ListQuery.Person
                added = added + fresh
                // Cinemeta narrows some filters after loading, so a page can come out short: then the next one is read too.
                if (meta.tmdb || start.query is ListQuery.Person || added.size >= 12) break
            }
            _listing.update { cur -> if (cur?.query == start.query) cur.copy(titles = cur.titles + added, page = page, end = end, loading = false) else cur }
        } catch (e: ChallengeException) {
            _listing.update { it?.copy(loading = false, error = "HDrezka просит пройти проверку.") }
            ask(e.url) { listingJob = viewModelScope.launch { loadListingPage() } }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _listing.update { it?.copy(loading = false, error = "HDrezka не ответила. Проверьте интернет.") }
        }
    }

    /** Keeps the page on top as it is, to show it again on the way back. */
    private fun leaveTop() {
        when (val top = _routes.value.lastOrNull()) {
            is Route.TitlePage -> _title.value?.let { savedTitles[top.title.key] = it }
            is Route.Listing -> _listing.value?.let { savedListings[top.query] = it }
            null -> {}
        }
    }

    /** One step back: the page under the top one, as it was left. */
    fun back() {
        val routes = _routes.value.dropLast(1)
        _routes.value = routes
        titleJob?.cancel()
        listingJob?.cancel()
        when (val top = routes.lastOrNull()) {
            is Route.TitlePage -> {
                val saved = savedTitles[top.title.key]
                if (saved == null || saved.rezka.status == SourceStatus.LOADING || saved.torrentsLoading || saved.sitesLoading) loadTitle(top.title)
                else _title.value = saved
            }
            is Route.Listing -> {
                val saved = savedListings[top.query]
                if (saved == null || saved.loading) startListing(top.query) else _listing.value = saved
            }
            null -> closeTitle()
        }
    }

    /** Back to the tabs, whatever was open. */
    fun closeTitle() {
        titleJob?.cancel()
        listingJob?.cancel()
        _routes.value = emptyList()
        _title.value = null
        _listing.value = null
        savedTitles.clear()
        savedListings.clear()
    }

    private suspend fun loadTorrents(start: Title) {
        var title = start
        if (title.imdbId == null) catalog.findImdb(title)?.let { id -> title = title.copy(imdbId = id); patch { it.copy(title = it.title.copy(imdbId = id)) } }
        val hits = runCatching { catalog.torrents(title) }.getOrDefault(emptyList())
        patch { it.copy(torrents = TorrentHits.group(hits), torrentsLoading = false) }
    }

    private suspend fun loadSites(title: Title) {
        val hits = kotlinx.coroutines.coroutineScope {
            val own = async { runCatching { native.find(title) }.getOrDefault(emptyList()) }
            val sites = async { runCatching { siteClient.find(title) }.getOrDefault(emptyList()) }
            // Sources that play straight from their own API come first: no site window at all.
            own.await() + sites.await()
        }
        patch { it.copy(sites = hits, sitesLoading = false, title = it.title.copy(poster = it.title.poster ?: hits.firstNotNullOfOrNull { h -> h.poster })) }
    }

    // ---- Site windows ----

    /** The film's page on a site, found by Couchy. */
    fun openSite(hit: SiteHit) {
        if (hit.site in NativeClient.ids) return openNative(hit.site, hit.url)
        val t = _title.value?.title
        val site = Sites.all.firstOrNull { it.id == hit.site }
        _siteWindow.value = SiteWindow(hit.url, site, t?.name ?: hit.name, t?.kind == TitleKind.SERIES, SiteRef.of(hit.url, hit.site))
    }

    /** A site whose search runs only in the browser: the person picks the film there. */
    fun searchOnSite(site: Site) {
        val t = _title.value?.title
        _siteWindow.value = SiteWindow(site.searchUrl(t?.name ?: _search.value.query), site, t?.name ?: site.name, t?.kind == TitleKind.SERIES)
    }

    /** Reopens a site film saved in the library. */
    fun openSiteRef(ref: String) {
        val (url, id) = SiteRef.parse(ref) ?: return
        viewModelScope.launch {
            val entry = library.get(ref)
            _siteWindow.value = SiteWindow(url, Sites.all.firstOrNull { it.id == id }, entry?.title ?: url, key = ref)
        }
    }

    fun closeSite() { _siteWindow.value = null }

    // ---- AniLibria and Kino.pub: episodes and files from their API ----

    /** A series from a native source, waiting for an episode to be picked. */
    private val _nativePick = MutableStateFlow<NativePick?>(null)
    val nativePick: StateFlow<NativePick?> = _nativePick.asStateFlow()

    fun closeNativePick() { _nativePick.value = null }

    private fun openNative(source: String, id: String) {
        viewModelScope.launch {
            val release = guarded({ openNative(source, id) }) { native.release(source, id) } ?: return@launch
            if (release.episodes.isEmpty()) {
                _error.value = "${NativeClient.name(source)}: видео для этого тайтла нет."
                return@launch
            }
            if (!release.series || release.episodes.size == 1) {
                val e = release.episodes.first()
                playNative(release, e.season, e.episode, false)?.let { _autoPlay.value = it }
                return@launch
            }
            // Ticks for what was watched, and the next episode to go on with.
            val prefix = app.couchy.core.sources.NativeRef.of(source, id, 0, 0).substringBeforeLast(":0:0")
            val mine = library.snapshot().filter { it.uri.startsWith(prefix) }
            val watched = mine.filter { Progress.isWatched(it.positionMs, it.durationMs) }
                .mapNotNull { app.couchy.core.sources.NativeRef.parse(it.uri) }.map { it.season to it.episode }.toSet()
            val last = mine.maxByOrNull { it.lastWatchedAt }?.let { app.couchy.core.sources.NativeRef.parse(it.uri) }
            val next = last?.let { l ->
                val i = release.episodes.indexOfFirst { it.season == l.season && it.episode == l.episode }
                if ((l.season to l.episode) in watched) release.episodes.getOrNull(i + 1) else release.episodes.getOrNull(i)
            } ?: release.episodes.firstOrNull { (it.season to it.episode) !in watched }
            _nativePick.value = NativePick(release, watched, next?.let { it.season to it.episode })
        }
    }

    fun pickNative(season: Int, episode: Int) {
        val release = _nativePick.value?.release ?: return
        _nativePick.value = null
        viewModelScope.launch { playNative(release, season, episode, false)?.let { _autoPlay.value = it } }
    }

    private suspend fun playNative(release: app.couchy.core.sources.NativeRelease, season: Int, episode: Int, fromStart: Boolean): Playback? =
        guarded({ viewModelScope.launch { playNative(release, season, episode, fromStart)?.let { _autoPlay.value = it } } }) {
            val i = release.episodes.indexOfFirst { it.season == season && it.episode == episode }
            val e = release.episodes.getOrNull(i) ?: throw IllegalStateException("Нет такой серии")
            val key = app.couchy.core.sources.NativeRef.of(release.source, release.id, season, episode)
            val title = if (release.series) "${release.name} · S${season}E$episode" else release.name
            val entry = library.open(key, title)
            if (fromStart) library.clearProgress(key)
            val wanted = app.couchy.youtube.QualityPick.choose(e.files.map { it.first })
            val file = e.files.firstOrNull { it.first == wanted } ?: e.files.first()
            Playback(
                uri = file.second,
                title = entry.title,
                startMs = if (fromStart) 0 else Progress.resumeFrom(entry.positionMs, entry.durationMs),
                audioLanguage = entry.audioLanguage,
                subtitleLanguage = entry.subtitleLanguage,
                key = key,
                headers = mapOf("User-Agent" to web.userAgent),
                nextRef = release.episodes.getOrNull(i + 1)?.takeIf { release.series }?.let { app.couchy.core.sources.NativeRef.of(release.source, release.id, it.season, it.episode) },
                variants = e.files,
                skips = e.skips.map { (start, end, label) -> app.couchy.Skip(start, end, label) },
                imdb = release.imdbId ?: _title.value?.title?.imdbId,
            )
        }

    /** An episode saved in the library or the next one: its release is asked again for fresh links. */
    private fun playNativeRef(ref: String) {
        val r = app.couchy.core.sources.NativeRef.parse(ref) ?: return
        viewModelScope.launch {
            val release = guarded({ playNativeRef(ref) }) { native.release(r.source, r.id) } ?: return@launch
            playNative(release, r.season, r.episode, false)?.let { _autoPlay.value = it }
        }
    }

    /** The site's player started [video]; it continues in Couchy's player with the same headers. */
    suspend fun siteCaught(window: SiteWindow, video: String, headers: Map<String, String>, pageUrl: String): Playback {
        _siteWindow.value = null
        val key = window.key ?: SiteRef.siteOf(pageUrl)?.let { SiteRef.of(pageUrl, it.id) } ?: SiteRef.of(pageUrl, window.site?.id ?: "web")
        val entry = library.open(key, window.title)
        // A series page plays whichever episode was picked in the site's player, so it starts where the site does.
        val start = if (window.series) 0L else Progress.resumeFrom(entry.positionMs, entry.durationMs)
        return Playback(
            uri = video,
            title = entry.title,
            startMs = start,
            audioLanguage = entry.audioLanguage,
            subtitleLanguage = entry.subtitleLanguage,
            key = key,
            headers = headers,
        )
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
            val default = page.defaultTranslator ?: page.translators.firstOrNull()?.id
            // The voice-over picked for this title before, or else the studio picked last anywhere.
            val own = voices.getInt("id:${page.id}", -1).takeIf { id -> page.translators.any { it.id == id } }
            val remembered = own
                ?: voices.getString("last", null)?.let { name -> page.translators.firstOrNull { it.name.equals(name, ignoreCase = true) }?.id }
            val firstSeason = page.episodes.firstOrNull()?.season
            voicesOf[url] = page.translators
            patchRezka { RezkaUi(SourceStatus.READY, url = url, page = page, translator = default, episodes = page.episodes, season = firstSeason, voiceKnown = own != null) }
            patch { it.copy(description = page.description ?: it.description, title = it.title.copy(rezkaUrl = url, poster = it.title.poster ?: page.poster)) }
            if (remembered != null && remembered != default) selectTranslator(remembered, remember = false)
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

    fun selectTranslator(id: Int, remember: Boolean = true) {
        val r = _title.value?.rezka ?: return
        val page = r.page ?: return
        patchRezka { it.copy(translator = id, voiceKnown = it.voiceKnown || remember) }
        if (remember) {
            voices.edit()
                .putInt("id:${page.id}", id)
                .apply { page.translators.firstOrNull { it.id == id }?.name?.takeIf { it.isNotBlank() }?.let { putString("last", it) } }
                .apply()
        }
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

    /**
     * "Просмотрено" set by hand on an episode, or on every episode of its season up to it.
     * Unmarking clears the episode's progress in every voice-over.
     */
    fun markEpisode(season: Int, episode: Int, watched: Boolean, before: Boolean) {
        val t = _title.value ?: return
        val r = t.rezka
        val url = r.url ?: return
        val translator = r.translator ?: return
        val targets = r.episodes.filter { it.season == season && (it.episode == episode || (before && it.episode < episode)) }
        viewModelScope.launch {
            val all = library.snapshot()
            val prefix = url.substringBefore('#') + "#couchy-rezka="
            targets.forEach { e ->
                if (watched) {
                    val key = Rezka.ref(url, translator, e.season, e.episode)
                    library.open(key, "${t.title.name} · S${e.season}E${e.episode}")
                    // A full mark: the episode counts as seen and is not offered to continue.
                    library.saveProgress(key, MARKED_MS, MARKED_MS)
                } else {
                    all.filter { it.uri.startsWith(prefix) && it.uri.substringAfter(prefix).substringAfter(':') == "${e.season}:${e.episode}" }
                        .forEach { library.clearProgress(it.uri) }
                }
            }
        }
    }

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
        if (app.couchy.core.sources.NativeRef.isRef(ref)) return playNativeRef(ref)
        val parsed = Rezka.parseRef(ref) ?: return
        val id = Rezka.idFromUrl(parsed.pageUrl) ?: return
        viewModelScope.launch {
            val playback = guarded({ playRef(ref) }) {
                val series = parsed.season > 0
                // The player offers the other voice-overs; they come from the title's page.
                if (voicesOf[parsed.pageUrl] == null) runCatching { catalog.rezka.page(parsed.pageUrl) }.getOrNull()?.let { voicesOf[parsed.pageUrl] = it.translators }
                val eps = if (series) runCatching { catalog.rezka.episodes(id, parsed.translator, parsed.pageUrl) }.getOrDefault(emptyList()) else emptyList()
                val entry = library.get(ref)
                build(parsed.pageUrl, id, series, Translator(parsed.translator, ""), parsed.season, parsed.episode, entry?.title?.substringBefore(" · ") ?: "HDrezka", eps, false)
            }
            playback?.let { _autoPlay.value = it }
        }
    }

    fun autoPlayHandled() { _autoPlay.value = null }

    /** HDrezka's voice-overs by page, so the player can offer them for any episode. */
    private val voicesOf = java.util.concurrent.ConcurrentHashMap<String, List<Translator>>()

    /**
     * Another voice-over for what is playing, from the same moment. It is remembered for the title,
     * so the next episode and the next "Смотреть" use it too.
     */
    suspend fun switchVoice(key: String, translatorId: Int, positionMs: Long): Playback? {
        val parsed = Rezka.parseRef(key) ?: return null
        val id = Rezka.idFromUrl(parsed.pageUrl) ?: return null
        val list = voicesOf[parsed.pageUrl].orEmpty()
        val translator = list.firstOrNull { it.id == translatorId } ?: Translator(translatorId, "")
        voices.edit().putInt("id:$id", translatorId).apply { translator.name.takeIf { it.isNotBlank() }?.let { putString("last", it) } }.apply()
        if (_title.value?.rezka?.url == parsed.pageUrl) selectTranslator(translatorId, remember = false)
        val playback = guarded({ viewModelScope.launch { switchVoice(key, translatorId, positionMs)?.let { _autoPlay.value = it } } }) {
            val series = parsed.season > 0
            val eps = if (series) runCatching { catalog.rezka.episodes(id, translatorId, parsed.pageUrl) }.getOrDefault(emptyList()) else emptyList()
            val name = library.get(key)?.title?.substringBefore(" · ") ?: _title.value?.title?.name ?: "HDrezka"
            build(parsed.pageUrl, id, series, translator, parsed.season, parsed.episode, name, eps, false)
        }
        return playback?.copy(startMs = positionMs)
    }

    private suspend fun build(
        url: String, id: Int, series: Boolean, translator: Translator, season: Int, episode: Int,
        name: String, episodes: List<EpisodeRef>, fromStart: Boolean,
    ): Playback {
        val stream = if (series) catalog.rezka.episode(id, translator.id, season, episode, url) else catalog.rezka.movie(id, translator, url)
        // The site sometimes gives the same file under two names; one entry per file is enough.
        val ranked = stream.options.sortedByDescending { it.rank }.distinctBy { it.url }
        // The quality picked last time, or the best there is.
        val wanted = app.couchy.youtube.QualityPick.choose(ranked.map { it.quality })
        val best = ranked.firstOrNull { it.quality == wanted } ?: ranked.firstOrNull() ?: throw IllegalStateException("Нет видео")
        val key = Rezka.ref(url, translator.id, season, episode)
        val title = if (series) "$name · S${season}E$episode" else name
        val entry = library.open(key, title)
        if (fromStart) library.clearProgress(key)
        val next = if (series) {
            val i = episodes.indexOfFirst { it.season == season && it.episode == episode }
            episodes.getOrNull(i + 1)?.takeIf { i >= 0 }?.let { Rezka.ref(url, translator.id, it.season, it.episode) }
        } else null
        return Playback(
            voices = voicesOf[url].orEmpty().map { it.id to it.name }.filter { it.second.isNotBlank() },
            uri = best.url,
            title = entry.title,
            startMs = if (fromStart) 0 else Progress.resumeFrom(entry.positionMs, entry.durationMs),
            audioLanguage = entry.audioLanguage,
            subtitleLanguage = entry.subtitleLanguage,
            key = key,
            headers = mapOf("User-Agent" to web.userAgent),
            subtitles = stream.subtitles.map { it.label to it.url },
            nextRef = next,
            variants = ranked.map { it.quality to it.url },
            imdb = _title.value?.title?.takeIf { it.rezkaUrl == null || it.rezkaUrl == url }?.imdbId,
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

    private companion object {
        /** Length saved for an episode marked by hand, whose real length is not known. */
        const val MARKED_MS = 60_000L
    }
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
