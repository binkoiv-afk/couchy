package app.couchy.core.sources

import org.json.JSONObject

enum class TitleKind { MOVIE, SERIES }

/** A film or series found by search, with whatever each source knows about it. */
data class Title(
    val name: String,
    val original: String? = null,
    /** Other names the title goes by, used to match it across sources. */
    val altNames: List<String> = emptyList(),
    val year: Int? = null,
    val kind: TitleKind = TitleKind.MOVIE,
    val poster: String? = null,
    val imdbId: String? = null,
    val rezkaUrl: String? = null,
    /** "2024, США, Фантастика" or the lead actors. */
    val info: String? = null,
    val rating: String? = null,
) {
    /** A stable key for lists and navigation. */
    val key: String get() = imdbId ?: rezkaUrl ?: "${name.lowercase()}|$year"
}

object Imdb {
    private val kinds = mapOf("movie" to TitleKind.MOVIE, "tvMovie" to TitleKind.MOVIE, "tvSeries" to TitleKind.SERIES, "tvMiniSeries" to TitleKind.SERIES)

    /** The suggestion endpoint: https://v3.sg.media-imdb.com/suggestion/x/QUERY.json */
    fun suggestUrl(query: String): String {
        val q = query.trim().lowercase()
        val first = q.firstOrNull { it.isLetterOrDigit() }?.takeIf { it in 'a'..'z' || it in '0'..'9' } ?: 'x'
        return "https://v3.sg.media-imdb.com/suggestion/$first/" + java.net.URLEncoder.encode(q, "UTF-8").replace("+", "%20") + ".json"
    }

    fun parse(json: String): List<Title> {
        val list = JSONObject(json).optJSONArray("d") ?: return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            val o = list.getJSONObject(i)
            val kind = kinds[o.optString("qid")] ?: return@mapNotNull null
            val id = o.optString("id").takeIf { it.startsWith("tt") } ?: return@mapNotNull null
            val name = o.optString("l").ifBlank { return@mapNotNull null }
            Title(
                name = name,
                original = name,
                year = o.optInt("y").takeIf { it > 0 },
                kind = kind,
                poster = o.optJSONObject("i")?.optString("imageUrl")?.takeIf { it.isNotBlank() }?.let(::smallPoster),
                imdbId = id,
                info = o.optString("s").ifBlank { null },
            )
        }
    }

    /** IMDb image URLs take a size suffix; 400px wide is plenty for a card. */
    private fun smallPoster(url: String): String =
        if ("._V1_" in url) url.substringBefore("._V1_") + "._V1_QL75_UX400_.jpg" else url
}

object TitleMerge {
    fun normalize(name: String): String =
        name.lowercase().replace('ё', 'е').replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    /**
     * Joins results of the Russian site search with IMDb: a pair is the same title when one
     * of the site's original names equals IMDb's and the years are at most one apart. The
     * site's order comes first (it understands Russian queries best), then IMDb's leftovers.
     */
    fun merge(site: List<Title>, imdb: List<Title>): List<Title> {
        val left = imdb.toMutableList()
        val joined = site.map { s ->
            val names = (listOfNotNull(s.original) + s.altNames).map(::normalize).filter { it.isNotEmpty() }.toSet()
            val match = left.firstOrNull { i ->
                normalize(i.name) in names && (s.year == null || i.year == null || kotlin.math.abs(s.year - i.year) <= 1)
            }
            if (match == null) s else {
                left.remove(match)
                s.copy(imdbId = match.imdbId, poster = s.poster ?: match.poster, original = s.original ?: match.name)
            }
        }
        return joined + left
    }
}
