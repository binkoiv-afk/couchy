package app.couchy.core.sources

import org.json.JSONObject
import java.util.Base64

/** A voice-over on HDrezka. */
data class Translator(val id: Int, val name: String, val camrip: Boolean = false, val ads: Boolean = false, val director: Boolean = false)

data class EpisodeRef(val season: Int, val episode: Int, val name: String)

/** What a title page says: ids, voice-overs, and for series the episodes of the default voice-over. */
data class RezkaPage(
    val id: Int,
    val series: Boolean,
    val name: String?,
    val original: String?,
    val poster: String?,
    val description: String?,
    val rating: String?,
    val translators: List<Translator>,
    val defaultTranslator: Int?,
    val episodes: List<EpisodeRef>,
    val details: TitleDetails = TitleDetails(),
)

data class StreamOption(val quality: String, val url: String) {
    /** For picking the best: 2160 for "4K", 1080 for "1080p Ultra" (plus a bit), etc. */
    val rank: Int get() {
        val q = quality.lowercase()
        val base = Regex("(\\d{3,4})p").find(q)?.groupValues?.get(1)?.toInt() ?: when {
            "4k" in q -> 2160
            "2k" in q -> 1440
            else -> 0
        }
        return base * 10 + if ("ultra" in q) 5 else 0
    }
}

data class Subtitle(val label: String, val url: String)

data class RezkaStream(val options: List<StreamOption>, val subtitles: List<Subtitle>)

/**
 * HDrezka pages and its own player's answers, read the way its web player reads them.
 * Pages behind a visitor check are recognised by [isChallenge]; the visitor passes those
 * themselves in a browser window.
 */
object Rezka {
    const val DEFAULT_HOST = "https://rezka.ag"

    fun isChallenge(html: String): Boolean =
        "anubis_challenge" in html || "Проверяем, что вы не бот" in html ||
            "challenge-platform" in html || "<title>Just a moment" in html

    /** Results of the full search page: name, poster, year, country and genre. */
    fun parseSearch(html: String): List<Title> {
        val items = Regex("""<div class="b-content__inline_item"[^>]*data-url="([^"]+)"(.*?)</div>\s*</div>""", RegexOption.DOT_MATCHES_ALL)
        return items.findAll(html).mapNotNull { m ->
            val url = m.groupValues[1]
            val body = m.groupValues[2]
            val poster = Regex("""<img src="([^"]+)"""").find(body)?.groupValues?.get(1)
            val name = Regex("""b-content__inline_item-link">\s*<a href="[^"]+">([^<]+)</a>""").find(body)?.groupValues?.get(1)?.let(::unescape)
                ?: return@mapNotNull null
            val info = Regex("""b-content__inline_item-link">.*?<div>([^<]+)""", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1)?.trim()
            val entity = Regex("""<i class="entity">([^<]+)</i>""").find(body)?.groupValues?.get(1).orEmpty()
            Title(
                name = name,
                year = info?.let { Regex("^(\\d{4})").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: yearFromUrl(url),
                kind = if (entity == "Фильм" || "/films/" in url) TitleKind.MOVIE else TitleKind.SERIES,
                poster = poster,
                rezkaUrl = url,
                info = info,
            )
        }.toList()
    }

    /** The quick-search answer adds original names: "Дюна (Dune: Part Two / Dune 2, 2024)". */
    fun parseQuickSearch(html: String): Map<String, Pair<List<String>, String?>> {
        val item = Regex("""<li><a href="([^"]+)"><span class="enty">([^<]*)</span>\s*\(([^)]*)\)(?:<span class="rating"><i[^>]*>([\d.]+)</i>)?""")
        return item.findAll(html).associate { m ->
            val inside = m.groupValues[3].split(",").map { it.trim() }
            val names = inside.filter { part -> !part.matches(Regex("\\d{4}.*")) && part != "сериал" && part != "мультфильм" && part != "аниме" }
                .flatMap { it.split(" / ") }.map { unescape(it.trim()) }.filter { it.isNotEmpty() }
            m.groupValues[1] to (names to m.groupValues[4].ifEmpty { null })
        }
    }

    fun parsePage(html: String): RezkaPage {
        val id = Regex("""id="post_id"[^>]*value="(\d+)"""").find(html)?.groupValues?.get(1)?.toInt()
            ?: Regex("""initCDN(?:Series|Movies)Events\((\d+)""").find(html)?.groupValues?.get(1)?.toInt()
            ?: Regex("""data-id="(\d+)"""").find(html)?.groupValues?.get(1)?.toInt()
            ?: error("На странице нет id")
        val series = "video.tv_series" in html || "initCDNSeriesEvents" in html
        val list = html.substringAfter("id=\"translators-list\"", "").substringBefore("</ul>")
        val activeId = Regex("""<li[^>]*>""").findAll(list).map { it.value }
            .firstOrNull { tag -> Regex("""class="[^"]*\bactive\b""").containsMatchIn(tag) }
            ?.let { Regex("""data-translator_id="(\d+)"""").find(it)?.groupValues?.get(1)?.toInt() }
        val translators = Regex("""<li[^>]*data-translator_id="(\d+)"[^>]*>(.*?)</li>""", RegexOption.DOT_MATCHES_ALL).findAll(
            list,
        ).map { m ->
            val tag = m.value.substringBefore('>')
            val title = Regex("""title="([^"]+)"""").find(tag)?.groupValues?.get(1)
            val text = unescape(m.groupValues[2].replace(Regex("<[^>]+>"), "").trim())
            val flag = Regex("""<img[^>]*title="([^"]+)"""").find(m.groupValues[2])?.groupValues?.get(1)
            Translator(
                id = m.groupValues[1].toInt(),
                name = listOfNotNull(text.ifEmpty { title }, flag).joinToString(" · "),
                camrip = Regex("""data-camrip="1"""").containsMatchIn(tag),
                ads = Regex("""data-ads="1"""").containsMatchIn(tag),
                director = Regex("""data-director="1"""").containsMatchIn(tag),
            )
        }.toList()
        val default = Regex("""initCDN(?:Series|Movies)Events\(\d+,\s*(\d+)""").find(html)?.groupValues?.get(1)?.toInt()
        val translatorName = Regex("""<td class="l"><h2>В переводе</h2>:</td>\s*<td>([^<]+)</td>""").find(html)?.groupValues?.get(1)
        return RezkaPage(
            id = id,
            series = series,
            name = Regex("""<div class="b-post__title">\s*<h1[^>]*>([^<]+)</h1>""").find(html)?.groupValues?.get(1)?.let(::unescape)?.trim(),
            original = Regex("""<div class="b-post__origtitle"[^>]*>([^<]+)</div>""").find(html)?.groupValues?.get(1)?.let(::unescape)?.trim(),
            poster = Regex("""<div class="b-sidecover">\s*<a href="([^"]+)"""").find(html)?.groupValues?.get(1),
            description = Regex("""<div class="b-post__description_text">(.*?)</div>""", RegexOption.DOT_MATCHES_ALL).find(html)
                ?.groupValues?.get(1)?.replace(Regex("<[^>]+>"), "")?.let(::unescape)?.trim(),
            rating = Regex("""b-post__info_rates imdb">.*?<span class="bold">([\d.]+)</span>""", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)?.let { "IMDb $it" }
                ?: Regex("""b-post__info_rates kp">.*?<span class="bold">([\d.]+)</span>""", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)?.let { "КП $it" },
            translators = translators.ifEmpty { if (default != null) listOf(Translator(default, translatorName?.trim() ?: "Оригинальная озвучка")) else emptyList() },
            defaultTranslator = activeId ?: default ?: translators.firstOrNull()?.id,
            episodes = parseEpisodes(html.substringAfter("id=\"simple-episodes-tabs\"", "")),
            details = runCatching { RezkaDetails.parse(html, Regex("""<meta property="og:url" content="([^"]+)""").find(html)?.groupValues?.get(1)) }
                .getOrDefault(TitleDetails()),
        )
    }

    /** Episodes from the page or from the "get_episodes" answer's html. */
    fun parseEpisodes(html: String): List<EpisodeRef> =
        Regex("""<li[^>]*data-season_id="(\d+)"[^>]*data-episode_id="(\d+)"[^>]*>(.*?)</li>""", RegexOption.DOT_MATCHES_ALL).findAll(html).map { m ->
            EpisodeRef(m.groupValues[1].toInt(), m.groupValues[2].toInt(), unescape(m.groupValues[3].replace(Regex("<[^>]+>"), "").trim()))
        }.distinctBy { it.season to it.episode }.toList()

    /** The "get_episodes" answer: episodes html for the chosen voice-over. */
    fun parseEpisodesAnswer(json: String): List<EpisodeRef> {
        val o = JSONObject(json)
        if (!o.optBoolean("success", false)) throw IllegalStateException(o.optString("message").ifBlank { "Нет серий для этой озвучки" })
        return parseEpisodes(o.optString("episodes"))
    }

    /** The "get_movie" / "get_stream" answer: encoded qualities and subtitles. */
    fun parseStreamAnswer(json: String): RezkaStream {
        val o = JSONObject(json)
        if (!o.optBoolean("success", false)) throw IllegalStateException(o.optString("message").ifBlank { "Видео недоступно" })
        val url = o.optString("url")
        if (url.isBlank() || url == "false") throw IllegalStateException("Видео недоступно в этой озвучке")
        val subs = o.opt("subtitle").let { if (it is String) parseSubtitles(it) else emptyList() }
        return RezkaStream(parseQualities(decode(url)), subs)
    }

    /** "[720p]a.m3u8 or a.mp4,[1080p]b.m3u8 or b.mp4" to options; the plain mp4 is preferred. */
    fun parseQualities(text: String): List<StreamOption> =
        Regex("""\[([^\]]+)\]([^,\[]+)""").findAll(text).mapNotNull { m ->
            // Qualities for signed-in or paying visitors only come wrapped in a marked span; they do not play here.
            if (Regex("registered|premium", RegexOption.IGNORE_CASE).containsMatchIn(m.groupValues[1])) return@mapNotNull null
            val label = m.groupValues[1].replace(Regex("<[^>]+>"), "").trim()
            val urls = m.groupValues[2].split(" or ").map { it.trim() }.filter { it.startsWith("http") }
            val url = urls.lastOrNull { it.endsWith(".mp4") } ?: urls.firstOrNull() ?: return@mapNotNull null
            StreamOption(label, url)
        }.toList()

    fun parseSubtitles(text: String): List<Subtitle> =
        Regex("""\[([^\]]+)\](https?://[^,\[]+)""").findAll(text).map { Subtitle(it.groupValues[1], it.groupValues[2].trim()) }.toList()

    private val trash: List<String> by lazy {
        val chars = listOf("@", "#", "!", "^", "$")
        val combos = mutableListOf<String>()
        for (a in chars) for (b in chars) {
            combos += a + b
            for (c in chars) combos += a + b + c
        }
        combos.map { Base64.getEncoder().encodeToString(it.toByteArray()) }.sortedByDescending { it.length }
    }

    /** The player's own obfuscation: base64 with marker pieces mixed in. */
    fun decode(data: String): String {
        if (!data.startsWith("#h")) return data
        var s = data.removePrefix("#h").split("//_//").joinToString("")
        for (t in trash) s = s.replace(t, "")
        val padded = s + "=".repeat((4 - s.length % 4) % 4)
        return String(Base64.getDecoder().decode(padded), Charsets.UTF_8)
    }

    /** Link reference stored in the library: page URL plus voice-over, season and episode. */
    fun ref(pageUrl: String, translator: Int, season: Int, episode: Int): String =
        pageUrl.substringBefore('#') + "#couchy-rezka=$translator:$season:$episode"

    fun isRef(link: String) = "#couchy-rezka=" in link

    data class Ref(val pageUrl: String, val translator: Int, val season: Int, val episode: Int)

    fun parseRef(link: String): Ref? {
        val page = link.substringBefore("#couchy-rezka=")
        val parts = link.substringAfter("#couchy-rezka=", "").split(":").map { it.toIntOrNull() ?: return null }
        if (parts.size != 3) return null
        return Ref(page, parts[0], parts[1], parts[2])
    }

    /** Numeric id from a page URL: ".../57233-dyuna-chast-vtoraya-2024.html" to 57233. */
    fun idFromUrl(url: String): Int? = Regex("/(\\d+)-[^/]*\\.html").find(url)?.groupValues?.get(1)?.toIntOrNull()

    /** Same page on another mirror. */
    fun onHost(url: String, host: String): String {
        val path = url.substringAfter("://").substringAfter('/', "")
        return host.trimEnd('/') + "/" + path
    }

    private fun yearFromUrl(url: String) = Regex("-(\\d{4})\\.html").find(url)?.groupValues?.get(1)?.toIntOrNull()

    fun unescape(text: String): String = text
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#039;", "'").replace("&laquo;", "«").replace("&raquo;", "»")
        .replace("&nbsp;", " ").replace("&mdash;", "—").replace("&ndash;", "–").replace("&hellip;", "…")
}
