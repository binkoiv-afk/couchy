package app.couchy.core.sources

import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

/** What Kinopoisk says about a title: its own rating and a Russian description. */
data class KpFilm(
    val id: Int,
    val rating: Double? = null,
    val votes: Int? = null,
    val description: String? = null,
) {
    /** "КП 8.2", for the chips on the title page. */
    val chip: String? get() = rating?.takeIf { it > 0 }?.let { "КП " + String.format(Locale.US, "%.1f", it) }
}

/**
 * Kinopoisk through kinopoiskapiunofficial.tech, with the person's own free key. A title is
 * found by its IMDb id, or by name and year when the id is not known.
 */
object Kinopoisk {
    const val BASE = "https://kinopoiskapiunofficial.tech/api"

    fun headers(key: String) = mapOf("X-API-KEY" to key.trim(), "Accept" to "application/json")

    fun byImdbUrl(imdbId: String) = "$BASE/v2.2/films?imdbId=${enc(imdbId)}"

    fun searchUrl(name: String) = "$BASE/v2.1/films/search-by-keyword?keyword=${enc(name)}&page=1"

    fun filmUrl(id: Int) = "$BASE/v2.2/films/$id"

    /** The Kinopoisk id from a lookup by IMDb id. */
    fun parseByImdb(json: String): Int? {
        val items = JSONObject(json).optJSONArray("items") ?: return null
        return items.optJSONObject(0)?.optInt("kinopoiskId")?.takeIf { it > 0 }
    }

    /** The id of the search hit with the same year (a year off is fine: release dates differ by country). */
    fun parseSearch(json: String, year: Int?): Int? {
        val films = JSONObject(json).optJSONArray("films") ?: return null
        val hits = (0 until films.length()).mapNotNull { films.optJSONObject(it) }
        val pick = if (year == null) hits.firstOrNull()
        else hits.firstOrNull { it.optString("year").take(4).toIntOrNull() == year }
            ?: hits.firstOrNull { (it.optString("year").take(4).toIntOrNull() ?: -10).let { y -> kotlin.math.abs(y - year) <= 1 } }
        return pick?.optInt("filmId")?.takeIf { it > 0 }
    }

    fun parseFilm(json: String): KpFilm? {
        val o = JSONObject(json)
        val id = o.optInt("kinopoiskId").takeIf { it > 0 } ?: return null
        return KpFilm(
            id = id,
            rating = o.optDouble("ratingKinopoisk").takeIf { !it.isNaN() && it > 0 },
            votes = o.optInt("ratingKinopoiskVoteCount").takeIf { it > 0 },
            description = text(o, "description") ?: text(o, "shortDescription"),
        )
    }

    private fun text(o: JSONObject, name: String) = o.optString(name).takeIf { it.isNotBlank() && it != "null" }?.trim()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
