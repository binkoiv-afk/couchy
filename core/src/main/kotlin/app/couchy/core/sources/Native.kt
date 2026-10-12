package app.couchy.core.sources

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Sources whose videos come straight from their own API, played in Couchy's player without a
 * site window: AniLibria, and Kino.pub with the person's subscription.
 */
data class NativeRelease(
    val source: String,
    /** What the source finds it by again: AniLibria's alias, Kino.pub's number. */
    val id: String,
    val name: String,
    val original: String?,
    val year: Int?,
    val series: Boolean,
    val poster: String?,
    val imdbId: String? = null,
    val episodes: List<NativeEpisode> = emptyList(),
)

/** A film (season 0, episode 0) or an episode, with its files by quality, best first. */
data class NativeEpisode(
    val season: Int,
    val episode: Int,
    val name: String,
    val files: List<Pair<String, String>>,
    /** Opening and ending titles, in milliseconds, to jump over. */
    val skips: List<Triple<Long, Long, String>> = emptyList(),
)

/** A library key for a native episode: "<page>#couchy-src=source:id:season:episode". */
object NativeRef {
    private const val MARK = "#couchy-src="

    fun of(source: String, id: String, season: Int, episode: Int): String =
        pageOf(source, id) + MARK + listOf(source, id, season, episode).joinToString(":")

    fun isRef(link: String) = MARK in link

    data class Ref(val source: String, val id: String, val season: Int, val episode: Int)

    fun parse(link: String): Ref? {
        val parts = link.substringAfter(MARK, "").split(':')
        if (parts.size != 4) return null
        return Ref(parts[0], parts[1], parts[2].toIntOrNull() ?: return null, parts[3].toIntOrNull() ?: return null)
    }

    private fun pageOf(source: String, id: String) = when (source) {
        Anilibria.ID -> "https://anilibria.top/anime/releases/release/$id"
        KinoPub.ID -> "https://kino.pub/item/view/$id"
        else -> "https://$source/$id"
    }
}

object Anilibria {
    const val ID = "anilibria"
    const val NAME = "AniLibria"
    const val HOST = "https://anilibria.top"

    fun searchUrl(query: String) = "$HOST/api/v1/app/search/releases?query=" + URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
    fun releaseUrl(alias: String) = "$HOST/api/v1/anime/releases/$alias"

    fun parseSearch(json: String): List<NativeRelease> {
        val a = JSONArray(json)
        return (0 until a.length()).map { release(a.getJSONObject(it), withEpisodes = false) }
    }

    fun parseRelease(json: String): NativeRelease = release(JSONObject(json), withEpisodes = true)

    private fun release(o: JSONObject, withEpisodes: Boolean): NativeRelease {
        val name = o.optJSONObject("name")
        val type = o.optJSONObject("type")?.optString("value").orEmpty()
        val poster = o.optJSONObject("poster")?.let { it.optString("src").ifEmpty { null } }?.let { if (it.startsWith("/")) HOST + it else it }
        val episodes = if (!withEpisodes) emptyList() else o.optJSONArray("episodes")?.let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it) }.mapNotNull { e ->
                val files = listOf("hls_1080" to "1080p", "hls_720" to "720p", "hls_480" to "480p")
                    .mapNotNull { (k, label) -> e.optString(k).takeIf { it.startsWith("http") }?.let { label to it } }
                if (files.isEmpty()) return@mapNotNull null
                val number = e.optDouble("ordinal", 0.0).toInt()
                fun part(key: String, label: String): Triple<Long, Long, String>? = e.optJSONObject(key)?.let { p ->
                    val start = p.optInt("start", -1)
                    val stop = p.optInt("stop", -1)
                    if (start < 0 || stop <= start) null else Triple(start * 1000L, stop * 1000L, label)
                }
                NativeEpisode(
                    season = if (type == "MOVIE") 0 else 1,
                    episode = if (type == "MOVIE") 0 else number,
                    name = e.optString("name").takeIf { it.isNotBlank() && it != "null" } ?: "Серия $number",
                    files = files,
                    skips = listOfNotNull(part("opening", "Заставка"), part("ending", "Титры")),
                )
            }
        }.orEmpty()
        return NativeRelease(
            source = ID,
            id = o.optString("alias").ifEmpty { o.optInt("id").toString() },
            name = name?.optString("main").orEmpty(),
            original = name?.optString("english")?.takeIf { it.isNotBlank() && it != "null" },
            year = o.optInt("year").takeIf { it > 0 },
            series = type != "MOVIE",
            poster = poster,
            episodes = episodes,
        )
    }
}

/**
 * Kino.pub, with the person's subscription. Signing in goes by a code shown on screen and
 * typed on kino.pub/device, like on a TV.
 */
object KinoPub {
    const val ID = "kinopub"
    const val NAME = "Kino.pub"
    const val API = "https://api.service-kp.com"
    const val CLIENT_ID = "xbmc"
    const val CLIENT_SECRET = "cgg3gtifu46urtfp2zp1nqtba0k2ezxh"

    data class DeviceCode(val code: String, val userCode: String, val verifyUrl: String, val interval: Int, val expiresIn: Int)
    data class Tokens(val access: String, val refresh: String, val expiresAt: Long)

    fun deviceCodeForm() = mapOf("grant_type" to "device_code", "client_id" to CLIENT_ID, "client_secret" to CLIENT_SECRET)
    fun deviceTokenForm(code: String) = mapOf("grant_type" to "device_token", "client_id" to CLIENT_ID, "client_secret" to CLIENT_SECRET, "code" to code)
    fun refreshForm(refresh: String) = mapOf("grant_type" to "refresh_token", "client_id" to CLIENT_ID, "client_secret" to CLIENT_SECRET, "refresh_token" to refresh)

    fun parseDeviceCode(json: String): DeviceCode = JSONObject(json).let {
        DeviceCode(it.getString("code"), it.getString("user_code"), it.optString("verification_uri", "https://kino.pub/device"), it.optInt("interval", 5), it.optInt("expires_in", 300))
    }

    /** Null while the person has not typed the code yet. */
    fun parseTokens(json: String, now: Long): Tokens? {
        val o = JSONObject(json)
        val access = o.optString("access_token").ifEmpty { return null }
        return Tokens(access, o.optString("refresh_token"), now + o.optLong("expires_in", 3600) * 1000)
    }

    fun searchUrl(query: String, token: String) =
        "$API/v1/items/search?q=" + URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20") + "&access_token=$token"

    fun itemUrl(id: String, token: String) = "$API/v1/items/$id?access_token=$token"

    fun parseSearch(json: String): List<NativeRelease> {
        val a = JSONObject(json).optJSONArray("items") ?: return emptyList()
        return (0 until a.length()).map { item(a.getJSONObject(it)) }
    }

    fun parseItem(json: String): NativeRelease = item(JSONObject(json).getJSONObject("item"))

    private fun item(o: JSONObject): NativeRelease {
        // "Дюна / Dune": Russian name, then the original.
        val names = o.optString("title").split(" / ").map { it.trim() }.filter { it.isNotEmpty() }
        val imdb = o.opt("imdb")?.toString()?.filter { it.isDigit() }?.takeIf { it.isNotEmpty() && it != "0" }?.let { "tt" + it.padStart(7, '0') }
        val series = o.optJSONArray("seasons")?.let { it.length() > 0 } ?: (o.optString("type") in setOf("serial", "docuserial", "tvshow"))
        val episodes = mutableListOf<NativeEpisode>()
        o.optJSONArray("seasons")?.let { seasons ->
            for (i in 0 until seasons.length()) {
                val s = seasons.getJSONObject(i)
                val eps = s.optJSONArray("episodes") ?: continue
                for (j in 0 until eps.length()) {
                    val e = eps.getJSONObject(j)
                    val files = files(e.optJSONArray("files"))
                    if (files.isEmpty()) continue
                    val n = e.optInt("number", j + 1)
                    episodes += NativeEpisode(s.optInt("number", i + 1), n, e.optString("title").ifBlank { "Серия $n" }, files)
                }
            }
        }
        if (episodes.isEmpty()) o.optJSONArray("videos")?.let { videos ->
            if (videos.length() > 0) {
                val v = videos.getJSONObject(0)
                files(v.optJSONArray("files")).takeIf { it.isNotEmpty() }?.let { episodes += NativeEpisode(0, 0, names.firstOrNull().orEmpty(), it) }
            }
        }
        return NativeRelease(
            source = ID,
            id = o.optInt("id").toString(),
            name = names.firstOrNull().orEmpty(),
            original = names.getOrNull(1),
            year = o.optInt("year").takeIf { it > 0 },
            series = series,
            poster = o.optJSONObject("posters")?.let { it.optString("medium").ifEmpty { it.optString("small") } }?.ifEmpty { null },
            imdbId = imdb,
            episodes = episodes,
        )
    }

    /** Files by quality, best first; the HLS link that carries every voice-over is preferred. */
    private fun files(arr: JSONArray?): List<Pair<String, String>> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }.mapNotNull { f ->
            val url = f.optJSONObject("url") ?: return@mapNotNull null
            val link = listOf("hls4", "hls2", "hls", "http").firstNotNullOfOrNull { k -> url.optString(k).takeIf { it.startsWith("http") } } ?: return@mapNotNull null
            val q = f.optString("quality").ifEmpty { f.optInt("h").takeIf { it > 0 }?.let { "${it}p" } ?: "?" }
            q to link
        }.sortedByDescending { it.first.filter(Char::isDigit).toIntOrNull() ?: 0 }
    }

}

/** The release that is this title: by IMDb id when there is one, else the same name a year apart at most. */
fun List<NativeRelease>.matching(names: List<String>, year: Int?, imdbId: String?): NativeRelease? {
    imdbId?.let { id -> firstOrNull { it.imdbId == id }?.let { return it } }
    val wanted = names.map(TitleMerge::normalize).filter { it.isNotEmpty() }.toSet()
    return firstOrNull { r -> listOfNotNull(r.name, r.original).any { TitleMerge.normalize(it) in wanted } && TitleMerge.yearsClose(r.year, year) }
}
