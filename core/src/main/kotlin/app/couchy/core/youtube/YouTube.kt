package app.couchy.core.youtube

import org.json.JSONArray

/** A video as lists show it. */
data class YtVideo(
    val id: String,
    val title: String,
    val channel: String = "",
    val channelUrl: String? = null,
    val durationSec: Long = 0,
    val views: Long = -1,
    /** "2 дня назад" as YouTube or the feed says it. */
    val ago: String? = null,
    val publishedMs: Long = 0,
    val live: Boolean = false,
    val thumb: String = YouTubeLinks.thumb(id),
) {
    val url: String get() = YouTubeLinks.watch(id)
}

data class YtChannel(
    val url: String,
    val name: String,
    val avatar: String? = null,
    val subscribers: Long = -1,
) {
    val id: String? get() = YouTubeLinks.channelId(url)
}

object YouTubeLinks {
    private val idPatterns = listOf(
        Regex("""(?:youtube\.com|youtube-nocookie\.com)/(?:watch\?(?:.*&)?v=|embed/|shorts/|live/|v/)([A-Za-z0-9_-]{11})"""),
        Regex("""youtu\.be/([A-Za-z0-9_-]{11})"""),
    )

    /** The video id inside any YouTube link, or null when it is not a video link. */
    fun videoId(link: String): String? = idPatterns.firstNotNullOfOrNull { it.find(link.trim())?.groupValues?.get(1) }

    fun isVideo(link: String) = videoId(link) != null

    fun watch(id: String) = "https://www.youtube.com/watch?v=$id"

    fun thumb(id: String) = "https://i.ytimg.com/vi/$id/hqdefault.jpg"

    /** "UC…" from a channel link; handles (@name) have no id in the link. */
    fun channelId(url: String): String? = Regex("""/channel/(UC[A-Za-z0-9_-]{22})""").find(url)?.groupValues?.get(1)

    fun feedUrl(channelId: String) = "https://www.youtube.com/feeds/videos.xml?channel_id=$channelId"
}

/** A channel's public feed: the latest 15 videos with their dates, without any key. */
object YouTubeFeed {
    private val entry = Regex("""<entry>(.*?)</entry>""", RegexOption.DOT_MATCHES_ALL)

    fun parse(xml: String, channelUrl: String? = null): List<YtVideo> = entry.findAll(xml).mapNotNull { m ->
        val e = m.groupValues[1]
        val id = tag(e, "yt:videoId") ?: return@mapNotNull null
        val title = tag(e, "title") ?: return@mapNotNull null
        val author = Regex("""<author>.*?<name>(.*?)</name>""", RegexOption.DOT_MATCHES_ALL).find(e)?.groupValues?.get(1)
        val views = Regex("""<media:statistics views="(\d+)"""").find(e)?.groupValues?.get(1)?.toLongOrNull() ?: -1
        val published = tag(e, "published")?.let(::parseDate) ?: 0
        // Shorts sit in the same feed; their links say so.
        val link = Regex("""<link rel="alternate" href="([^"]+)"""").find(e)?.groupValues?.get(1).orEmpty()
        if ("/shorts/" in link) return@mapNotNull null
        YtVideo(id, unescape(title), channel = unescape(author.orEmpty()), channelUrl = channelUrl, views = views, publishedMs = published)
    }.toList()

    private fun tag(text: String, name: String) =
        Regex("""<$name>(.*?)</$name>""", RegexOption.DOT_MATCHES_ALL).find(text)?.groupValues?.get(1)?.trim()

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")

    /** "2026-10-09T15:00:12+00:00" to milliseconds. */
    fun parseDate(s: String): Long = runCatching {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US)
        f.parse(s)!!.time
    }.getOrDefault(0)
}

/**
 * SponsorBlock: parts of videos that viewers marked as sponsor reads, self-promotion, intros
 * and reminders to subscribe. The player jumps over them.
 */
object SponsorBlock {
    val categories = listOf("sponsor", "selfpromo", "interaction", "intro", "outro", "music_offtopic")

    fun url(videoId: String): String {
        val cats = categories.joinToString(",", "[", "]") { "\"$it\"" }
        return "https://sponsor.ajay.app/api/skipSegments?videoID=$videoId&categories=" + java.net.URLEncoder.encode(cats, "UTF-8")
    }

    data class Segment(val startMs: Long, val endMs: Long, val category: String)

    fun parse(json: String): List<Segment> {
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            if (o.optString("actionType", "skip") != "skip") return@mapNotNull null
            val seg = o.optJSONArray("segment") ?: return@mapNotNull null
            val start = (seg.optDouble(0) * 1000).toLong()
            val end = (seg.optDouble(1) * 1000).toLong()
            if (end - start < 1_000) null else Segment(start, end, o.optString("category"))
        }.sortedBy { it.startMs }
    }

    fun label(category: String) = when (category) {
        "sponsor" -> "Пропущена реклама"
        "selfpromo" -> "Пропущена самореклама"
        "interaction" -> "Пропущено «подпишитесь»"
        "intro" -> "Пропущена заставка"
        "outro" -> "Пропущены титры"
        "music_offtopic" -> "Пропущено вступление"
        else -> "Пропущено"
    }
}
