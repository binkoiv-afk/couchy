package app.couchy.core.radio

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** An internet radio station from the Radio Browser directory. */
data class Station(
    val uuid: String,
    val name: String,
    val url: String,
    val favicon: String?,
    val tags: List<String>,
    val country: String?,
    val codec: String?,
    val bitrate: Int,
) {
    /** One short line under the name: style and sound quality. */
    val about: String
        get() = listOfNotNull(
            tags.take(2).joinToString(", ").ifBlank { null },
            country?.ifBlank { null },
            if (bitrate > 0) "$bitrate кбит/с" else codec?.ifBlank { null },
        ).joinToString(" · ")

    fun toJson(): JSONObject = JSONObject()
        .put("stationuuid", uuid).put("name", name).put("url_resolved", url).put("favicon", favicon ?: "")
        .put("tags", tags.joinToString(",")).put("country", country ?: "").put("codec", codec ?: "").put("bitrate", bitrate)
}

/** The genres offered as chips, with the tag Radio Browser files them under. */
enum class RadioGenre(val label: String, val tag: String) {
    POP("Поп", "pop"),
    ROCK("Рок", "rock"),
    DANCE("Танцевальное", "dance"),
    CHILL("Спокойное", "chillout"),
    JAZZ("Джаз", "jazz"),
    CLASSIC("Классика", "classical"),
    RETRO("Ретро", "oldies"),
    NEWS("Новости", "news"),
    RAP("Рэп", "hip-hop"),
    KIDS("Детское", "kids"),
}

/**
 * Radio Browser (radio-browser.info): a free, open directory of stations, served by several
 * mirrors. Only working stations are asked for, the most listened first.
 */
object RadioBrowser {
    /** Mirrors, tried in turn. */
    val hosts = listOf("de1.api.radio-browser.info", "de2.api.radio-browser.info", "fi1.api.radio-browser.info", "at1.api.radio-browser.info")

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private const val COMMON = "hidebroken=true&order=clickcount&reverse=true"

    fun searchUrl(host: String, query: String, limit: Int = 60) =
        "https://$host/json/stations/search?name=${enc(query.trim())}&$COMMON&limit=$limit"

    fun countryUrl(host: String, code: String = "RU", limit: Int = 80) =
        "https://$host/json/stations/search?countrycode=$code&$COMMON&limit=$limit"

    fun tagUrl(host: String, tag: String, code: String? = null, limit: Int = 60) =
        "https://$host/json/stations/search?tag=${enc(tag)}&tagExact=false" + (code?.let { "&countrycode=$it" } ?: "") + "&$COMMON&limit=$limit"

    fun topUrl(host: String, limit: Int = 80) = "https://$host/json/stations/topclick/$limit?hidebroken=true"

    /** Counts a listen, which keeps the directory's order honest. */
    fun clickUrl(host: String, uuid: String) = "https://$host/json/url/$uuid"

    fun parse(json: String): List<Station> {
        val a = JSONArray(json)
        return (0 until a.length()).mapNotNull { i -> station(a.optJSONObject(i)) }
            .distinctBy { it.url }
            // The same station is often filed several times under slightly different names.
            .distinctBy { it.name.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "") }
    }

    fun station(o: JSONObject?): Station? {
        o ?: return null
        val url = o.optString("url_resolved").ifBlank { o.optString("url") }.trim()
        val name = o.optString("name").trim().replace(Regex("\\s+"), " ")
        if (url.isEmpty() || name.isEmpty() || !url.startsWith("http")) return null
        return Station(
            uuid = o.optString("stationuuid"),
            name = name,
            url = url,
            favicon = o.optString("favicon").trim().takeIf { it.startsWith("http") },
            tags = o.optString("tags").split(',').map { it.trim() }.filter { it.isNotEmpty() && it.length < 24 },
            country = o.optString("country").trim().takeIf { it.isNotEmpty() }?.let(::countryRu),
            codec = o.optString("codec").trim().takeIf { it.isNotEmpty() && it != "UNKNOWN" },
            bitrate = o.optInt("bitrate", 0),
        )
    }

    fun toJson(stations: List<Station>): String = JSONArray().apply { stations.forEach { put(it.toJson()) } }.toString()

    private fun countryRu(c: String) = when (c) {
        "The Russian Federation", "Russia", "Russian Federation" -> "Россия"
        "Ukraine" -> "Украина"
        "Belarus" -> "Беларусь"
        "Kazakhstan" -> "Казахстан"
        "Germany" -> "Германия"
        "France" -> "Франция"
        "The United States Of America", "United States" -> "США"
        "The United Kingdom Of Great Britain And Northern Ireland", "United Kingdom" -> "Великобритания"
        else -> c
    }

    /** A stream's "Artist - Title" as Couchy writes songs: "Artist — Title"; null for empty or station adverts. */
    fun songLine(icy: String?): String? {
        val s = icy?.trim()?.takeIf { it.length > 1 } ?: return null
        val i = s.indexOf(" - ")
        return if (i > 0) s.substring(0, i).trim() + " — " + s.substring(i + 3).trim() else s
    }
}
