package app.couchy.data

import app.couchy.Playback
import app.couchy.core.sources.Rezka
import org.json.JSONObject

/**
 * Where a series' opening titles are, marked once by the person and skipped in every episode,
 * and how long its end credits run, so the next episode is offered as soon as they start.
 */
data class Intro(val startMs: Long, val endMs: Long, val creditsMs: Long = 0) {
    val hasOpening: Boolean get() = endMs > startMs
}

object Intros {
    private const val PREFIX = "intro"

    /** The series an episode belongs to: the HDrezka page or the torrent; null for anything else. */
    fun seriesOf(p: Playback): String? = when {
        p.live != null -> null
        Rezka.isRef(p.key) -> Rezka.parseRef(p.key)?.pageUrl
        p.torrent != null -> "torrent:" + p.torrent.hash
        app.couchy.core.sources.NativeRef.isRef(p.key) -> app.couchy.core.sources.NativeRef.parse(p.key)?.let { "native:${it.source}:${it.id}" }
        else -> null
    }

    fun get(marks: Marks, series: String): Intro? = marks.all.value["$PREFIX|$series"]?.let {
        runCatching { JSONObject(it).let { o -> Intro(o.optLong("start"), o.optLong("end"), o.optLong("credits")) } }.getOrNull()
    }

    fun set(marks: Marks, series: String, intro: Intro?) {
        if (intro == null || !intro.hasOpening && intro.creditsMs <= 0) marks.remove("$PREFIX|$series")
        else marks.put("$PREFIX|$series", JSONObject().put("start", intro.startMs).put("end", intro.endMs).put("credits", intro.creditsMs))
    }
}
