package app.couchy.core.sources

import org.json.JSONArray
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** A subtitle file found on OpenSubtitles. */
data class SubHit(
    val name: String,
    val url: String,
    /** "rus", "eng". */
    val lang: String,
    val encoding: String?,
    val downloads: Int,
)

/**
 * Subtitles from OpenSubtitles for a file that came without them: by IMDb id when Couchy
 * knows it, by name otherwise. Russian first, then English.
 */
object Subtitles {
    private const val BASE = "https://rest.opensubtitles.org/search"
    const val USER_AGENT = "TemporaryUserAgent"

    /** The API wants its parts in alphabetical order. */
    fun searchUrl(imdb: String?, query: String?, season: Int?, episode: Int?, langs: String = "rus,eng"): String {
        val parts = buildList {
            if (episode != null) add("episode-$episode")
            imdb?.removePrefix("tt")?.let { add("imdbid-$it") }
            if (imdb == null && !query.isNullOrBlank()) add("query-" + URLEncoder.encode(query.lowercase().trim(), "UTF-8").replace("+", "%20"))
            if (season != null) add("season-$season")
            add("sublanguageid-$langs")
        }
        return BASE + "/" + parts.joinToString("/")
    }

    fun parse(json: String): List<SubHit> {
        val a = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val format = o.optString("SubFormat").lowercase()
            if (format != "srt" && format != "vtt") return@mapNotNull null
            val url = o.optString("SubDownloadLink").ifBlank { return@mapNotNull null }
            SubHit(
                name = o.optString("SubFileName").ifBlank { o.optString("MovieReleaseName") },
                url = url,
                lang = o.optString("SubLanguageID"),
                encoding = o.optString("SubEncoding").ifBlank { null },
                downloads = o.optString("SubDownloadsCnt").toIntOrNull() ?: 0,
            )
        }.distinctBy { it.url }
            .sortedWith(compareBy<SubHit> { if (it.lang == "rus") 0 else 1 }.thenByDescending { it.downloads })
    }

    /** Season and episode from "S01E02", "1x02" or Couchy's own "· S1E2". */
    fun episodeOf(text: String): Pair<Int, Int>? {
        Regex("""[Ss](\d{1,2})\s*[Ee](\d{1,3})""").find(text)?.let { return it.groupValues[1].toInt() to it.groupValues[2].toInt() }
        Regex("""\b(\d{1,2})x(\d{2,3})\b""").find(text)?.let { return it.groupValues[1].toInt() to it.groupValues[2].toInt() }
        return null
    }

    /** "Breaking.Bad.S01E02.1080p.WEB" → "Breaking Bad"; "Дюна (2021) 1080p" → "Дюна". */
    fun queryOf(title: String): String {
        var t = title.substringBefore(" · ").substringAfterLast('/')
        t = t.replace(Regex("""\.(mkv|mp4|avi|m4v|webm)$""", RegexOption.IGNORE_CASE), "")
        t = t.replace('.', ' ').replace('_', ' ')
        val cut = Regex("""(?i)\b(S\d{1,2}\s*E\d{1,3}|\d{1,2}x\d{2,3}|(19|20)\d{2}|\d{3,4}p|web|bdrip|hdrip|webrip|bluray|hdtv|x26[45]|hevc)\b""").find(t)
        if (cut != null && cut.range.first > 0) t = t.substring(0, cut.range.first)
        return t.replace(Regex("""[\[\](){}]"""), " ").replace(Regex("""\s+"""), " ").trim()
    }

    /** UTF-8 when the bytes are valid UTF-8, else the encoding OpenSubtitles names, else Windows-1251. */
    fun decode(bytes: ByteArray, encoding: String?): String {
        val body = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) bytes.copyOfRange(3, bytes.size) else bytes
        try {
            return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString()
        } catch (_: CharacterCodingException) {
        }
        val named = encoding?.let { runCatching { Charset.forName(it) }.getOrNull() }
        return String(body, named ?: Charset.forName("windows-1251"))
    }
}
