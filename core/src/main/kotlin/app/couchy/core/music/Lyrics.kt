package app.couchy.core.music

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** A song's words: lines with their start in milliseconds when synced, else plain lines (start -1). */
data class Lyrics(val lines: List<Pair<Long, String>>, val synced: Boolean)

/** Words of songs from LRCLIB, an open database of synced lyrics; no key needed. */
object LrcLib {
    const val API = "https://lrclib.net/api"

    private fun enc(s: String) = URLEncoder.encode(s.trim(), "UTF-8").replace("+", "%20")

    fun getUrl(artist: String, title: String, durationSec: Long): String =
        "$API/get?artist_name=${enc(artist)}&track_name=${enc(title)}" + if (durationSec > 0) "&duration=$durationSec" else ""

    fun searchUrl(artist: String, title: String): String = "$API/search?q=" + enc(listOf(artist, title).filter { it.isNotBlank() }.joinToString(" "))

    /**
     * The song's own name from a YouTube title: without "(Official Video)", "[Lyrics]", "feat. …",
     * and without the artist when the title repeats it ("Artist - Song").
     */
    fun clean(title: String, artist: String): String {
        var t = title
        t = t.replace(Regex("""[(\[][^)\]]*(official|video|audio|lyric|клип|премьера|visualizer|remaster|hd|4k|mv)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE), " ")
        t = t.replace(Regex("""\s(feat\.?|ft\.?)\s.*$""", RegexOption.IGNORE_CASE), " ")
        val dash = Regex("""\s[-–—]\s""").find(t)
        if (dash != null) {
            val before = t.substring(0, dash.range.first).trim()
            if (artist.isBlank() || before.equals(artist, ignoreCase = true) || artist.contains(before, ignoreCase = true) || before.contains(artist, ignoreCase = true)) {
                t = t.substring(dash.range.last + 1)
            }
        }
        return t.replace(Regex("""\s+"""), " ").trim().trim('"', '«', '»')
    }

    /** The artist from a YouTube title "Artist - Song" when the channel says nothing useful. */
    fun artistFrom(title: String): String? = Regex("""^(.+?)\s[-–—]\s""").find(title)?.groupValues?.get(1)?.trim()

    fun parse(json: String): Lyrics? = of(JSONObject(json))

    /** The best of the search hits: synced first, then the length closest to the song's. */
    fun parseSearch(json: String, durationSec: Long): Lyrics? {
        val a = JSONArray(json)
        val hits = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { !it.optBoolean("instrumental") }
        val best = hits.sortedWith(
            compareBy<JSONObject>({ if (it.optString("syncedLyrics").isNullOrBlank() || it.isNull("syncedLyrics")) 1 else 0 }, { if (durationSec > 0) kotlin.math.abs(it.optDouble("duration", 0.0) - durationSec) else 0.0 }),
        ).firstOrNull() ?: return null
        return of(best)
    }

    private fun of(o: JSONObject): Lyrics? {
        val synced = o.optString("syncedLyrics").takeIf { !o.isNull("syncedLyrics") && it.isNotBlank() }
        if (synced != null) parseLrc(synced).takeIf { it.isNotEmpty() }?.let { return Lyrics(it, true) }
        val plain = o.optString("plainLyrics").takeIf { !o.isNull("plainLyrics") && it.isNotBlank() } ?: return null
        return Lyrics(plain.lines().map { -1L to it.trim() }, false)
    }

    /** "[01:02.50] words" lines; several stamps on one line repeat it. */
    fun parseLrc(text: String): List<Pair<Long, String>> {
        val stamp = Regex("""\[(\d+):(\d{2})(?:[.:](\d{1,3}))?]""")
        return text.lines().flatMap { line ->
            val stamps = stamp.findAll(line).toList()
            if (stamps.isEmpty()) return@flatMap emptyList()
            val words = line.substring(stamps.last().range.last + 1).trim()
            stamps.map { m ->
                val frac = m.groupValues[3]
                val ms = when (frac.length) { 0 -> 0L; 1 -> frac.toLong() * 100; 2 -> frac.toLong() * 10; else -> frac.take(3).toLong() }
                (m.groupValues[1].toLong() * 60_000 + m.groupValues[2].toLong() * 1000 + ms) to words
            }
        }.sortedBy { it.first }
    }

    /** The line playing at [positionMs]: the last one that started. */
    fun lineAt(lines: List<Pair<Long, String>>, positionMs: Long): Int = lines.indexOfLast { it.first in 0..positionMs }
}
