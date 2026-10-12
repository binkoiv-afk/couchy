package app.couchy.sources

import android.content.Context
import app.couchy.Playback
import app.couchy.core.sources.SubHit
import app.couchy.core.sources.Subtitles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/** Looks up subtitles for what is playing on OpenSubtitles and saves the chosen file. */
object SubtitleFinder {
    suspend fun find(p: Playback): List<SubHit> = withContext(Dispatchers.IO) {
        val episode = Subtitles.episodeOf(p.title)
        val query = Subtitles.queryOf(p.title)
        val urls = listOfNotNull(
            p.imdb?.let { Subtitles.searchUrl(it, null, episode?.first, episode?.second) },
            query.takeIf { it.length >= 2 }?.let { Subtitles.searchUrl(null, it, episode?.first, episode?.second) },
        )
        for (url in urls) {
            val hits = runCatching { Subtitles.parse(String(fetch(url), Charsets.UTF_8)) }.getOrDefault(emptyList())
            if (hits.isNotEmpty()) return@withContext hits.take(30)
        }
        emptyList()
    }

    /** The file, unpacked and in UTF-8, as a local address the player reads. */
    suspend fun download(context: Context, hit: SubHit): String = withContext(Dispatchers.IO) {
        var bytes = fetch(hit.url)
        if (bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) bytes = GZIPInputStream(bytes.inputStream()).readBytes()
        val ext = if (hit.name.endsWith(".vtt", ignoreCase = true)) "vtt" else "srt"
        val dir = File(context.cacheDir, "subtitles").apply { mkdirs() }
        val file = File(dir, "${hit.url.hashCode().toUInt()}.$ext")
        file.writeText(Subtitles.decode(bytes, hit.encoding))
        android.net.Uri.fromFile(file).toString()
    }

    /** A subtitles file, from the internet or the device, read into cues for the second line. */
    suspend fun cues(context: Context, uri: String, headers: Map<String, String> = emptyMap()): List<app.couchy.core.sources.Cue> = withContext(Dispatchers.IO) {
        var bytes = if (uri.startsWith("http", ignoreCase = true)) {
            val c = URL(uri).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000
            c.readTimeout = 20_000
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            try { c.inputStream.use { it.readBytes() } } finally { c.disconnect() }
        } else {
            context.contentResolver.openInputStream(android.net.Uri.parse(uri))!!.use { it.readBytes() }
        }
        if (bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) bytes = GZIPInputStream(bytes.inputStream()).readBytes()
        app.couchy.core.sources.SubCues.parse(Subtitles.decode(bytes, null))
    }

    private fun fetch(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", Subtitles.USER_AGENT)
        try {
            if (c.responseCode !in 200..299) throw IllegalStateException("OpenSubtitles: ${c.responseCode}")
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }
}
