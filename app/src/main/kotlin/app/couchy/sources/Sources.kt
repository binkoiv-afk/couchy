package app.couchy.sources

import app.couchy.core.sources.EpisodeRef
import app.couchy.core.sources.Imdb
import app.couchy.core.sources.Rezka
import app.couchy.core.sources.RezkaPage
import app.couchy.core.sources.RezkaStream
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
            ?.firstOrNull { TitleMerge.normalize(it.name) == TitleMerge.normalize(name) && (title.year == null || it.year == null || kotlin.math.abs(title.year - it.year) <= 1) }
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
