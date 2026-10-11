package app.couchy.data

import app.couchy.Playback
import app.couchy.core.sources.Rezka
import org.json.JSONObject

/** Where a series' opening titles are, marked once by the person and skipped in every episode. */
data class Intro(val startMs: Long, val endMs: Long)

object Intros {
    private const val PREFIX = "intro"

    /** The series an episode belongs to: the HDrezka page or the torrent; null for anything else. */
    fun seriesOf(p: Playback): String? = when {
        p.live != null -> null
        Rezka.isRef(p.key) -> Rezka.parseRef(p.key)?.pageUrl
        p.torrent != null -> "torrent:" + p.torrent.hash
        else -> null
    }

    fun get(marks: Marks, series: String): Intro? = marks.all.value["$PREFIX|$series"]?.let {
        runCatching { JSONObject(it).let { o -> Intro(o.getLong("start"), o.getLong("end")) } }.getOrNull()
    }

    fun set(marks: Marks, series: String, intro: Intro?) {
        if (intro == null) marks.remove("$PREFIX|$series")
        else marks.put("$PREFIX|$series", JSONObject().put("start", intro.startMs).put("end", intro.endMs))
    }
}
