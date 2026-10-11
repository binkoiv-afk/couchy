package app.couchy.sources

import app.couchy.core.sources.EpisodeRef
import app.couchy.core.sources.Imdb
import app.couchy.core.sources.Rezka
import app.couchy.core.sources.RezkaPage
import app.couchy.core.sources.RezkaStream
import app.couchy.core.sources.SiteHit
import app.couchy.core.sources.SiteSearch
import app.couchy.core.sources.Sites
import app.couchy.core.sources.Title
import app.couchy.core.sources.TitleKind
import app.couchy.core.sources.TitleMerge
import app.couchy.core.sources.TorrentHit
import app.couchy.core.sources.TorrentHits
import app.couchy.core.sources.Translator
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.net.URLEncoder

/** HDrezka through the same requests its web player makes. */
class RezkaClient(private val web: Web, private val host: () -> String) {
    private fun base() = host().trimEnd('/').ifBlank { Rezka.DEFAULT_HOST }

    suspend fun search(query: String): List<Title> = coroutineScope {
        val q = URLEncoder.encode(query, "UTF-8")
        val full = async { web.get("${base()}/search/?do=search&subaction=search&q=$q") }
        val quick = async {
            runCatching {
                web.post("${base()}/engine/ajax/search.php", mapOf("q" to query), mapOf("X-Requested-With" to "XMLHttpRequest"))
            }.getOrNull()
        }
        val titles = Rezka.parseSearch(full.await())
        val names = quick.await()?.let { Rezka.parseQuickSearch(it) }.orEmpty()
        titles.map { t ->
            val (alt, rating) = names[t.rezkaUrl] ?: return@map t
            t.copy(original = alt.firstOrNull(), altNames = alt, rating = rating?.let { "КП $it" })
        }
    }

    /** A catalogue page of the site (new arrivals, what people watch now), in the search result format. */
    suspend fun list(path: String): List<Title> = Rezka.parseSearch(web.get(base() + path))

    /** A person's filmography or a catalogue page, by its address on the site. */
    suspend fun listing(path: String): List<Title> = app.couchy.core.sources.RezkaDetails.parseListing(web.get(base() + path))

    suspend fun page(url: String): RezkaPage = Rezka.parsePage(web.get(Rezka.onHost(url, base())))

    suspend fun episodes(id: Int, translator: Int, referer: String): List<EpisodeRef> =
        Rezka.parseEpisodesAnswer(ajax(mapOf("id" to "$id", "translator_id" to "$translator", "action" to "get_episodes"), referer))

    suspend fun movie(id: Int, translator: Translator, referer: String): RezkaStream =
        Rezka.parseStreamAnswer(
            ajax(
                mapOf(
                    "id" to "$id", "translator_id" to "${translator.id}",
                    "is_camrip" to flag(translator.camrip), "is_ads" to flag(translator.ads), "is_director" to flag(translator.director),
                    "action" to "get_movie",
                ),
                referer,
            ),
        )

    suspend fun episode(id: Int, translator: Int, season: Int, episode: Int, referer: String): RezkaStream =
        Rezka.parseStreamAnswer(
            ajax(mapOf("id" to "$id", "translator_id" to "$translator", "season" to "$season", "episode" to "$episode", "action" to "get_stream"), referer),
        )

    private suspend fun ajax(form: Map<String, String>, referer: String): String =
        web.post(
            "${base()}/ajax/get_cdn_series/?t=${System.currentTimeMillis()}",
            form,
            mapOf("X-Requested-With" to "XMLHttpRequest", "Referer" to Rezka.onHost(referer, base()), "Origin" to base()),
        )

    private fun flag(b: Boolean) = if (b) "1" else "0"
}

/** Film search across IMDb and HDrezka, and torrents for a title across Jacred and Torrentio. */
class Catalog(private val web: Web, val rezka: RezkaClient) {
    suspend fun imdb(query: String): List<Title> = Imdb.parse(web.get(Imdb.suggestUrl(query)))

    suspend fun search(query: String): SearchResult = coroutineScope {
        val imdb = async { runCatching { Imdb.parse(web.get(Imdb.suggestUrl(query))) } }
        val site = async { runCatching { rezka.search(query) } }
        val i = imdb.await()
        val s = site.await()
        val challenge = (s.exceptionOrNull() as? ChallengeException)?.url
        if (i.isFailure && s.isFailure && challenge == null) throw i.exceptionOrNull()!!
        SearchResult(TitleMerge.merge(s.getOrDefault(emptyList()), i.getOrDefault(emptyList())), challenge)
    }

    /** IMDb id for a title the site found but IMDb search did not match. */
    suspend fun findImdb(title: Title): String? {
        val name = title.original ?: return null
        return runCatching { Imdb.parse(web.get(Imdb.suggestUrl(name))) }.getOrNull()
            ?.firstOrNull { TitleMerge.normalize(it.name) == TitleMerge.normalize(name) && TitleMerge.yearsClose(title.year, it.year) }
            ?.imdbId
    }

    suspend fun torrents(title: Title): List<TorrentHit> = coroutineScope {
        val jac = async {
            runCatching {
                val params = buildList {
                    add("search" to title.name)
                    title.original?.takeIf { it != title.name }?.let { add("altname" to it) }
                    add("exact" to "true")
                    if (title.kind == TitleKind.MOVIE) title.year?.let { add("year" to "$it") }
                }
                TorrentHits.parseJacred(web.get(JACRED + "?" + params.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }))
            }.getOrDefault(emptyList())
        }
        val tio = async {
            runCatching {
                val id = title.imdbId ?: return@runCatching emptyList()
                val path = if (title.kind == TitleKind.MOVIE) "movie/$id" else "series/$id:1:1"
                TorrentHits.parseTorrentio(web.get("$TORRENTIO/stream/$path.json"))
            }.getOrDefault(emptyList())
        }
        jac.await() + tio.await()
    }

    /** "Только торренты": a loose search by any words, like on a tracker. */
    suspend fun rawTorrents(query: String): List<TorrentHit> =
        TorrentHits.parseJacred(web.get(JACRED + "?search=" + URLEncoder.encode(query, "UTF-8")))

    companion object {
        const val JACRED = "https://jac.red/api/v1.0/torrents"
        const val TORRENTIO = "https://torrentio.strem.fun/providers=yts,eztv,rarbg,1337x,thepiratebay,kickasstorrents,torrentgalaxy,magnetdl,nyaasi,rutor,rutracker%7Csort=seeders"
    }
}

data class SearchResult(val titles: List<Title>, val challengeUrl: String?)

/** Online cinemas with plain search pages: each one is asked for the title by its Russian and original names. */
class SiteClient(private val web: Web) {
    suspend fun find(title: Title): List<SiteHit> = coroutineScope {
        val names = (listOf(title.name) + listOfNotNull(title.original) + title.altNames).distinct()
        Sites.all.filter { it.id in SiteSearch.parsed }.map { site ->
            async {
                runCatching {
                    val hits = SiteSearch.parse(site.id, web.get(site.searchUrl(title.name)), site.home)
                    SiteSearch.match(hits, names, title.year)
                }.getOrNull()
            }
        }.mapNotNull { it.await() }
    }
}

/**
 * Film data independent of any site that plays films: TMDB in Russian with the person's key,
 * Cinemeta without one.
 */
class MetaClient(private val web: Web, private val key: () -> String) {
    val tmdb: Boolean get() = key().isNotBlank()

    private suspend fun tmdbGet(path: String, params: Map<String, String> = emptyMap()): String {
        val (url, headers) = app.couchy.core.sources.Tmdb.request(key(), path, params)
        return web.get(url, headers)
    }

    /** Everything about one title; ids are found by IMDb id or by name and year when missing. */
    suspend fun info(title: Title): app.couchy.core.sources.MetaInfo? {
        if (tmdb) {
            val (id, kind) = title.tmdbId?.let { it to title.kind }
                ?: title.imdbId?.let { imdb ->
                    app.couchy.core.sources.Tmdb.parseFind(tmdbGet("/find/$imdb", mapOf("external_source" to "imdb_id")))
                }
                ?: findTmdb(title)
                ?: return cinemeta(title)
            return runCatching {
                app.couchy.core.sources.Tmdb.parseDetails(
                    tmdbGet(app.couchy.core.sources.Tmdb.detailsPath(id, kind), mapOf("append_to_response" to app.couchy.core.sources.Tmdb.APPEND)),
                    kind,
                )
            }.getOrElse { cinemeta(title) }
        }
        return cinemeta(title)
    }

    private suspend fun cinemeta(title: Title): app.couchy.core.sources.MetaInfo? {
        val imdb = title.imdbId ?: return null
        val kinds = listOf(title.kind, if (title.kind == TitleKind.MOVIE) TitleKind.SERIES else TitleKind.MOVIE)
        for (k in kinds) {
            runCatching { app.couchy.core.sources.Cinemeta.parseMeta(web.get(app.couchy.core.sources.Cinemeta.metaUrl(imdb, k))) }
                .getOrNull()?.let { return it }
        }
        return null
    }

    private suspend fun findTmdb(title: Title): Pair<Int, TitleKind>? {
        val names = listOfNotNull(title.original, title.name).distinct()
        for (name in names) {
            val found = runCatching { search(name) }.getOrDefault(emptyList())
            found.firstOrNull { f ->
                f.kind == title.kind && TitleMerge.yearsClose(title.year, f.year) &&
                    (listOf(f.name) + f.altNames).map(TitleMerge::normalize).any { it == TitleMerge.normalize(name) }
            }?.tmdbId?.let { return it to title.kind }
        }
        return null
    }

    /** TMDB's search, in Russian; nothing without a key. */
    suspend fun search(query: String): List<Title> =
        if (!tmdb) emptyList()
        else app.couchy.core.sources.Tmdb.parseList(tmdbGet("/search/multi", mapOf("query" to query, "include_adult" to "false")))

    suspend fun catalog(f: app.couchy.core.sources.CatalogFilter, page: Int): List<Title> = catalogPage(f, page).first

    /** One page as shown, and how many titles the page had before narrowing (zero: the end). */
    suspend fun catalogPage(f: app.couchy.core.sources.CatalogFilter, page: Int): Pair<List<Title>, Int> {
        if (tmdb) {
            val (path, params) = app.couchy.core.sources.Tmdb.discover(f, page)
            val kind = if (path.endsWith("tv")) TitleKind.SERIES else TitleKind.MOVIE
            val list = app.couchy.core.sources.Tmdb.parseList(tmdbGet(path, params), kind)
            return list to list.size
        }
        val kind = if (f.type == app.couchy.core.sources.CatalogType.FILMS || f.type == app.couchy.core.sources.CatalogType.CARTOONS) TitleKind.MOVIE else TitleKind.SERIES
        val json = web.get(app.couchy.core.sources.Cinemeta.catalogUrl(f, page))
        val all = app.couchy.core.sources.Cinemeta.parseCatalog(json, kind)
        return app.couchy.core.sources.Cinemeta.parseCatalog(json, kind, f) to all.size
    }

    /** A person's films: from TMDB by id or name; without a key, Cinemeta's search by the name. */
    suspend fun person(link: app.couchy.core.sources.Link): List<Title> {
        val id = app.couchy.core.sources.MetaLinks.tmdbPersonId(link.url)
            ?: if (tmdb) app.couchy.core.sources.Tmdb.parsePersonSearch(tmdbGet("/search/person", mapOf("query" to link.name))) else null
        if (id != null && tmdb) return app.couchy.core.sources.Tmdb.parseCredits(tmdbGet("/person/$id/combined_credits"))
        return listOf(TitleKind.MOVIE, TitleKind.SERIES).flatMap { k ->
            runCatching { app.couchy.core.sources.Cinemeta.parseCatalog(web.get(app.couchy.core.sources.Cinemeta.searchUrl(link.name, k)), k) }.getOrDefault(emptyList())
        }
    }

    /** Home's shelves: what is popular now, films, series and cartoons. */
    suspend fun feed(): Feed = kotlinx.coroutines.coroutineScope {
        fun shelf(f: app.couchy.core.sources.CatalogFilter) = async { runCatching { catalog(f, 1) }.getOrDefault(emptyList()) }
        val trending = async {
            runCatching { if (tmdb) app.couchy.core.sources.Tmdb.parseList(tmdbGet("/trending/all/week")) else emptyList() }.getOrDefault(emptyList())
        }
        val films = shelf(app.couchy.core.sources.CatalogFilter(app.couchy.core.sources.CatalogType.FILMS, app.couchy.core.sources.CatalogSort.POPULAR))
        val series = shelf(app.couchy.core.sources.CatalogFilter(app.couchy.core.sources.CatalogType.SERIES, app.couchy.core.sources.CatalogSort.POPULAR))
        val cartoons = shelf(app.couchy.core.sources.CatalogFilter(app.couchy.core.sources.CatalogType.CARTOONS, app.couchy.core.sources.CatalogSort.POPULAR))
        val fresh = shelf(app.couchy.core.sources.CatalogFilter(app.couchy.core.sources.CatalogType.FILMS, app.couchy.core.sources.CatalogSort.LAST))
        Feed(
            fresh = fresh.await(),
            films = films.await(),
            series = series.await(),
            cartoons = cartoons.await(),
            trending = trending.await(),
            loaded = true,
        )
    }
}
