package app.couchy.core.iptv

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** How a channel's archive (catch-up) is reached, as the playlist declares it. */
data class Catchup(val type: Type, val source: String?, val days: Int) {
    enum class Type { DEFAULT, APPEND, SHIFT, FLUSSONIC, XTREAM }

    companion object {
        fun from(type: String?, source: String?, days: Int?): Catchup? {
            val kind = when (type?.lowercase()?.trim()) {
                null, "" -> return if (source != null) Catchup(Type.DEFAULT, source, days ?: 3) else null
                "default" -> Type.DEFAULT
                "append" -> Type.APPEND
                "shift", "timeshift", "archive" -> Type.SHIFT
                "flussonic", "flussonic-hls", "flussonic-ts", "fs" -> Type.FLUSSONIC
                "xc", "xtream" -> Type.XTREAM
                else -> return null
            }
            return Catchup(kind, source, days ?: 3)
        }
    }
}

/** Builds the address of a recorded programme for a channel with an archive. */
object CatchupUrl {
    /**
     * [startMs]/[endMs] are the programme's times; [nowMs] is passed in so tests are stable.
     * Returns null when the channel's archive does not reach that far back.
     */
    fun build(channelUrl: String, catchup: Catchup, startMs: Long, endMs: Long, nowMs: Long): String? {
        if (startMs < nowMs - catchup.days * 86_400_000L || startMs > nowMs) return null
        val start = startMs / 1000
        val end = minOf(endMs, nowMs) / 1000
        val duration = (end - start).coerceAtLeast(60)
        return when (catchup.type) {
            Catchup.Type.DEFAULT -> catchup.source?.let { fill(it, start, end, nowMs / 1000) }
                ?: appendQuery(channelUrl, "utc=$start&lutc=${nowMs / 1000}")
            Catchup.Type.APPEND -> channelUrl + (catchup.source?.let { fill(it, start, end, nowMs / 1000) } ?: "")
            Catchup.Type.SHIFT -> appendQuery(channelUrl, "utc=$start&lutc=${nowMs / 1000}")
            Catchup.Type.FLUSSONIC -> flussonic(channelUrl, start, duration)
            Catchup.Type.XTREAM -> xtream(channelUrl, startMs, duration)
        }
    }

    /** Substitutes the placeholders players agree on: ${start}, {utc}, {Y}-{m}-{d}, {duration} and the like. */
    fun fill(template: String, start: Long, end: Long, now: Long): String {
        val utc = TimeZone.getTimeZone("UTC")
        fun fmt(pattern: String, seconds: Long) = SimpleDateFormat(pattern, Locale.US).apply { timeZone = utc }.format(Date(seconds * 1000))
        val duration = end - start
        val values = mapOf(
            "start" to "$start", "utc" to "$start", "timestamp" to "$now", "lutc" to "$now", "now" to "$now",
            "end" to "$end", "utcend" to "$end", "duration" to "$duration", "offset" to "${now - start}",
            "Y" to fmt("yyyy", start), "m" to fmt("MM", start), "d" to fmt("dd", start),
            "H" to fmt("HH", start), "M" to fmt("mm", start), "S" to fmt("ss", start),
        )
        var out = template
        // ${start}, {utc:YmdHMS}-style and plain {utc}
        out = Regex("""\$?\{(utc|start|end|utcend|lutc|now|timestamp):([^}]+)}""").replace(out) { m ->
            val seconds = if (m.groupValues[1] in setOf("end", "utcend")) end else if (m.groupValues[1] in setOf("lutc", "now", "timestamp")) now else start
            fmt(m.groupValues[2].map { strftime[it] ?: "'$it'" }.joinToString(""), seconds)
        }
        out = Regex("""\$?\{([A-Za-z]+)}""").replace(out) { m -> values[m.groupValues[1]] ?: m.value }
        out = Regex("""\{duration:(\d+)}""").replace(out) { m -> "${duration / (m.groupValues[1].toLongOrNull() ?: 1)}" }
        return out
    }

    private val strftime = mapOf('Y' to "yyyy", 'm' to "MM", 'd' to "dd", 'H' to "HH", 'M' to "mm", 'S' to "ss")

    private fun appendQuery(url: String, query: String) = url + (if ('?' in url) "&" else "?") + query

    /** http://host/channel/index.m3u8 -> http://host/channel/archive-START-DURATION.m3u8 (or timeshift_abs for .ts). */
    private fun flussonic(url: String, start: Long, duration: Long): String {
        val base = url.substringBefore('?')
        val query = url.substringAfter('?', "").let { if (it.isEmpty()) "" else "?$it" }
        val dir = base.substringBeforeLast('/')
        val file = base.substringAfterLast('/')
        return if (file.endsWith(".ts") || file == "mpegts") "$dir/timeshift_abs-$start.ts$query"
        else "$dir/${if (file.startsWith("video")) "video" else "index"}-$start-$duration.m3u8$query"
    }

    /** http://host:port/live/user/pass/123.ts -> http://host:port/timeshift/user/pass/MIN/YYYY-MM-DD:HH-MM/123.ts */
    private fun xtream(url: String, startMs: Long, duration: Long): String? {
        val match = Regex("""^(https?://[^/]+)/(?:live/)?([^/]+)/([^/]+)/(\d+)(\.\w+)?$""").find(url.substringBefore('?')) ?: return null
        val (host, user, pass, id, ext) = match.destructured
        val time = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US).format(Date(startMs))
        return "$host/timeshift/$user/$pass/${(duration + 59) / 60}/$time/$id${ext.ifEmpty { ".ts" }}"
    }
}
