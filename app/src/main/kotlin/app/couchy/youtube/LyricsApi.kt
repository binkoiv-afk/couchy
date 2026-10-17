package app.couchy.youtube

import app.couchy.core.music.LrcLib
import app.couchy.core.music.Lyrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** The words of the song playing, from LRCLIB: by name and length first, then by search. */
object LyricsApi {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Lyrics>()

    suspend fun find(title: String, artist: String, durationSec: Long): Lyrics? {
        val who = artist.ifBlank { LrcLib.artistFrom(title).orEmpty() }
        val song = LrcLib.clean(title, who)
        val key = "$who|$song"
        cache[key]?.let { return it }
        val found = runCatching { get(LrcLib.getUrl(who, song, durationSec))?.let(LrcLib::parse) }.getOrNull()
            ?: runCatching { get(LrcLib.searchUrl(who, song))?.let { LrcLib.parseSearch(it, durationSec) } }.getOrNull()
        found?.let { cache[key] = it }
        return found
    }

    /** The body, or null on 404 (no such song). */
    private suspend fun get(url: String): String? = withContext(Dispatchers.IO) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", "Couchy (https://github.com/binkoiv-afk/couchy)")
        try {
            if (c.responseCode == 404) null else c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}
