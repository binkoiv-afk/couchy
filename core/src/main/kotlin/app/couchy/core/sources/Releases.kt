package app.couchy.core.sources

import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** «Жду»: when a film comes out to watch at home, not only in cinemas. */
object Releases {
    private const val DIGITAL = 4
    private const val PHYSICAL = 5

    /** The earliest digital release anywhere (or on disc, when no digital one is listed) from TMDB's release_dates. */
    fun homeRelease(json: String): Long? {
        val results = JSONObject(json).optJSONArray("results") ?: return null
        val dates = (0 until results.length()).mapNotNull { results.optJSONObject(it)?.optJSONArray("release_dates") }
            .flatMap { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }
        fun earliest(type: Int) = dates.filter { it.optInt("type") == type }.mapNotNull { date(it.optString("release_date")) }.minOrNull()
        return earliest(DIGITAL) ?: earliest(PHYSICAL)
    }

    /** Cinemeta's disc or digital date, when it has one. */
    fun cinemetaHome(json: String): Long? = JSONObject(json).optJSONObject("meta")?.optString("dvdRelease")?.let(::date)

    private fun date(text: String): Long? = text.takeIf { it.length >= 10 }?.let {
        runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
            ?: runCatching { LocalDate.parse(it.take(10)).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()
    }
}
