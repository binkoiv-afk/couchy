package app.couchy.core.sources

import org.json.JSONObject
import java.time.Instant

/** One episode of a series as the film database lists it. */
data class AiredEpisode(val season: Int, val episode: Int, val releasedMs: Long, val name: String? = null)

/**
 * New episodes of followed series: Couchy remembers how many have aired and tells the person
 * when the count grows. Specials (season 0) do not count.
 */
object Episodes {
    /** The `videos` of a Cinemeta series page. */
    fun parseCinemeta(json: String): List<AiredEpisode> {
        val videos = JSONObject(json).optJSONObject("meta")?.optJSONArray("videos") ?: return emptyList()
        return (0 until videos.length()).mapNotNull { i ->
            val o = videos.optJSONObject(i) ?: return@mapNotNull null
            val season = o.optInt("season")
            val episode = o.optInt("episode", o.optInt("number"))
            if (season <= 0 || episode <= 0) return@mapNotNull null
            val released = runCatching { Instant.parse(o.optString("released")).toEpochMilli() }.getOrNull() ?: return@mapNotNull null
            AiredEpisode(season, episode, released, o.optString("name").ifBlank { o.optString("title") }.ifBlank { null })
        }.distinctBy { it.season to it.episode }
            .sortedWith(compareBy({ it.season }, { it.episode }))
    }

    /** Episodes already out at [now]. */
    fun aired(list: List<AiredEpisode>, now: Long): List<AiredEpisode> = list.filter { it.releasedMs <= now }

    /**
     * What came out since the person last heard: nothing on the first look ([known] is null),
     * since everything aired before following is old news.
     */
    fun fresh(known: Int?, list: List<AiredEpisode>, now: Long): List<AiredEpisode> {
        val out = aired(list, now)
        if (known == null || out.size <= known) return emptyList()
        return out.drop(known)
    }

    /** "Вышла 3 серия 2 сезона" or "Вышло 4 новых серии". */
    fun announce(fresh: List<AiredEpisode>): String? {
        val last = fresh.lastOrNull() ?: return null
        return if (fresh.size == 1) "Вышла ${last.episode} серия ${last.season} сезона"
        else "Вышло новых серий: ${fresh.size}, последняя — ${last.episode} серия ${last.season} сезона"
    }
}
