package app.couchy.core.sources

import java.net.URLEncoder

/** An online cinema opened inside Couchy; [search] has {q} where the query goes. */
data class Site(val id: String, val name: String, val search: String) {
    fun searchUrl(query: String): String = search.replace("{q}", URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20"))
    val home: String get() = Regex("^https?://[^/]+").find(search)?.value ?: search
}

/**
 * Sites that keep their videos in third-party players: Couchy opens them in its own
 * browser window with ads cut out, and takes over as soon as a video stream starts.
 */
object Sites {
    /** Sites that move between addresses: the default, and a working one can be set in the settings. */
    val mirrors = linkedMapOf("kinogo" to "https://kinogo.so", "lordfilm" to "https://lordfilm.top")
    @Volatile var hosts: Map<String, String> = emptyMap()

    fun host(id: String): String = (hosts[id]?.takeIf { it.isNotBlank() } ?: mirrors.getValue(id)).trimEnd('/')

    private fun dle(id: String, name: String) = Site(id, name, host(id) + "/index.php?do=search&subaction=search&story={q}")

    val all: List<Site> get() = listOf(
        dle("kinogo", "Kinogo"),
        dle("lordfilm", "LordFilm"),
        Site("gidonline", "GidOnline", "https://gidonline.eu/index.php?do=search&subaction=search&story={q}"),
        Site("zona", "Zona", "https://w140.zona.plus/search/{q}"),
        Site("filmix", "Filmix", "https://filmix.gg/search/{q}"),
        Site("kinokrad", "Kinokrad", "https://kinokrad.cc/index.php?do=search&subaction=search&story={q}"),
        Site("baskino", "Baskino", "https://baskino.me/index.php?do=search&subaction=search&story={q}"),
        Site("seasonvar", "Seasonvar", "https://seasonvar.ru/search?q={q}"),
        Site("vk", "VK Видео", "https://vkvideo.ru/?q={q}"),
        Site("rutube", "Rutube", "https://rutube.ru/search/?query={q}"),
    )

    private val adHosts = listOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "google-analytics.com", "googletagmanager.com",
        "adservice.google", "an.yandex.ru", "yandex.ru/ads", "mc.yandex", "adfox.ru", "adfox.yandex", "adriver.ru", "betweendigital",
        "otm-r.com", "ad.mail.ru", "top-fwz1.mail.ru", "propellerads", "propu.sh", "clickadu", "exoclick", "adsterra", "mgid.com",
        "marketgid", "trafficstars", "hilltopads", "popads", "popcash", "onclickads", "adcash", "bongacams", "chaturbate",
        "1xbet", "1win", "vavada", "pin-up", "pinup", "joycasino", "casino", "betwinner", "melbet", "mostbet", "leon.ru",
        "buzzoola", "videoroll", "hyperadx", "adhigh", "smi2", "24smi", "lentainform", "relap.io", "yadro.ru", "acint.net",
        "imasdk.googleapis.com", "/vast", "vast.xml", "vpaid", "/ads/", "/adv/", "/advert", "/banner", "/preroll", "/promo/",
    )

    /** Requests to ad networks, trackers and casino banners get an empty answer. */
    fun isAd(url: String): Boolean {
        val u = url.lowercase()
        return adHosts.any { it in u }
    }

    /** A video stream the player can take over: HLS, DASH or a plain file, not an ad. */
    fun isVideo(url: String): Boolean {
        val u = url.lowercase()
        if (!u.startsWith("http") || isAd(u)) return false
        val path = u.substringBefore('?')
        if (adClips.any { it in path }) return false
        // Segments and subtitles are parts of a video, not the video.
        if (path.endsWith(".ts") || path.endsWith(".m4s") || path.endsWith(".vtt") || path.endsWith(".srt")) return false
        return ".m3u8" in path || path.endsWith(".mpd") || path.endsWith(".mp4") || path.endsWith(".mkv") || path.endsWith(".webm") ||
            "/hls/" in path && path.endsWith("/index") || "manifest.m3u8" in u ||
            // VK and OK serve files without an extension, the format in the query.
            (("okcdn.ru" in path || "vkuser" in path || "mycdn.me" in path) && ("type=" in u || "/video" in path)) ||
            "mime=video" in u || "format=m3u8" in u
    }

    /** Pre-rolls and banners that players load as video files. */
    private val adClips = listOf("reklam", "preroll", "/creatives/", "/creative/", "/vast/", "/ad/", "/ads/", "/adv/", "_ad_", "-ad-", "commercial")

    /** A stream that is the film itself rather than a single file, which is more often an advert. */
    fun isStream(url: String): Boolean {
        val u = url.lowercase().substringBefore('?')
        return ".m3u8" in u || u.endsWith(".mpd") || "/hls/" in u || "format=m3u8" in url.lowercase()
    }

    /** Several requests for one video (variants, segments' playlists) count as one. */
    fun sameVideo(a: String, b: String): Boolean = a.substringBefore('?').substringBeforeLast('/') == b.substringBefore('?').substringBeforeLast('/')

    /** A master playlist is better than a single quality's playlist. */
    fun preferred(urls: List<String>): String? =
        urls.firstOrNull { isStream(it) && ("master" in it.lowercase() || "manifest" in it.lowercase()) }
            ?: urls.firstOrNull { isStream(it) } ?: urls.lastOrNull()
}

/** A title found on one of the [Sites]. */
data class SiteHit(val site: String, val name: String, val year: Int?, val url: String, val poster: String?)

/** Search pages of the sites whose results come as plain HTML. */
object SiteSearch {
    /** Sites with a readable search page; the rest are searched in the site window. */
    val parsed = setOf("kinogo", "lordfilm", "gidonline", "kinokrad", "baskino", "seasonvar")

    fun parse(siteId: String, html: String, base: String): List<SiteHit> = when (siteId) {
        "kinogo" -> blocks(html, "<article class=\"pcard\"").mapNotNull { b ->
            val m = Regex("class=\"pcard-title\" href=\"([^\"]+)\">([^<]+)</a>").find(b) ?: return@mapNotNull null
            val (name, year) = splitYear(m.groupValues[2])
            val poster = Regex("<img[^>]*?src=\"([^\"]+)\"").find(b)?.groupValues?.get(1)
            SiteHit(siteId, name, year ?: Regex("xfsearch/year/(\\d{4})").find(b)?.groupValues?.get(1)?.toIntOrNull(), abs(m.groupValues[1], base)!!, abs(poster, base))
        }
        "lordfilm" -> blocks(html, "<div class=\"item expand-link").mapNotNull { b ->
            val m = Regex("item__title[^>]*href=\"([^\"]+)\">([^<]+)</a>").find(b) ?: return@mapNotNull null
            val poster = Regex("<img src=\"([^\"]+)\"").find(b)?.groupValues?.get(1)
            SiteHit(siteId, clean(m.groupValues[2]), Regex("item__year\">\\((\\d{4})\\)").find(b)?.groupValues?.get(1)?.toIntOrNull(), abs(m.groupValues[1], base)!!, abs(poster, base))
        }
        "gidonline" -> blocks(html, "<div class=\"mainlink\">").mapNotNull { b ->
            val url = attr(b, "<a href=\"") ?: return@mapNotNull null
            val name = Regex("<span>([^<]+)</span>").find(b)?.groupValues?.get(1) ?: return@mapNotNull null
            SiteHit(siteId, clean(name), Regex("class=\"mqn\">(\\d{4})").find(b)?.groupValues?.get(1)?.toIntOrNull(), url, abs(attr(b, "src=\""), base))
        }
        "kinokrad" -> blocks(html, "<div class=\"shorbox\"").mapNotNull { b ->
            val m = Regex("<h2>\\s*<a href=\"([^\"]+)\">([^<]+)</a>").find(b) ?: return@mapNotNull null
            val (name, year) = splitYear(m.groupValues[2])
            val poster = Regex("postershort\">.*?<img[^>]*?src=\"([^\"]+)\"", RegexOption.DOT_MATCHES_ALL).find(b)?.groupValues?.get(1)
            SiteHit(siteId, name, year ?: Regex("xfsearch/year/(\\d{4})").find(b)?.groupValues?.get(1)?.toIntOrNull(), m.groupValues[1], abs(poster, base))
        }
        "baskino" -> blocks(html, "<div class=\"shortpost\">").mapNotNull { b ->
            val m = Regex("class=\"posttitle\">\\s*<a href=\"([^\"]+)\">([^<]+)</a>").find(b) ?: return@mapNotNull null
            val poster = Regex("<img title=\"[^\"]*\" src=\"([^\"]+)\"").find(b)?.groupValues?.get(1)
            SiteHit(siteId, clean(m.groupValues[2]), Regex("Год выпуска:\\s*(\\d{4})").find(b)?.groupValues?.get(1)?.toIntOrNull(), m.groupValues[1], abs(poster, base))
        }
        "seasonvar" -> blocks(html, "<div class=\"pgs-search-wrap\">").mapNotNull { b ->
            val m = Regex("pgs-search-info\">\\s*<a href=\"([^\"]+)\">([^<]+)</a>").find(b) ?: return@mapNotNull null
            SiteHit(siteId, clean(m.groupValues[2]), null, abs(m.groupValues[1], base)!!, abs(attr(b, "<img src=\""), base))
        }
        else -> emptyList()
    }.distinctBy { it.url }

    /** The hit that is this title: same name (ignoring punctuation and case), year at most one apart. */
    fun match(hits: List<SiteHit>, names: List<String>, year: Int?): SiteHit? {
        val wanted = names.map(TitleMerge::normalize).filter { it.isNotEmpty() }.toSet()
        return hits.firstOrNull { TitleMerge.normalize(it.name) in wanted && TitleMerge.yearsClose(it.year, year) }
    }

    private fun blocks(html: String, marker: String): List<String> = html.split(marker).drop(1)

    private fun attr(block: String, prefix: String): String? = block.substringAfter(prefix, "").substringBefore('"').ifEmpty { null }

    private fun splitYear(text: String): Pair<String, Int?> {
        val m = Regex("^(.*?)\\s*\\((\\d{4})\\)\\s*$").find(text.trim()) ?: return clean(text) to null
        return clean(m.groupValues[1]) to m.groupValues[2].toInt()
    }

    private fun clean(text: String) = Rezka.unescape(text).trim()

    private fun abs(url: String?, base: String): String? = when {
        url == null -> null
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> base.trimEnd('/') + url
        else -> url
    }
}

/** A film page on one of the [Sites], kept in the library as `pageUrl#couchy-site=siteId`. */
object SiteRef {
    private const val MARK = "#couchy-site="

    fun of(pageUrl: String, siteId: String): String = pageUrl.substringBefore('#') + MARK + siteId

    fun isRef(link: String): Boolean = MARK in link

    /** Page URL and site id. */
    fun parse(ref: String): Pair<String, String>? {
        if (!isRef(ref)) return null
        return ref.substringBefore(MARK) to ref.substringAfter(MARK)
    }

    /** Which site a page belongs to, by its host. */
    fun siteOf(url: String): Site? {
        val host = hostOf(url) ?: return null
        return Sites.all.firstOrNull { s -> hostOf(s.home)?.let { h -> host == h || host.endsWith(".$h") || h.endsWith(".$host") } == true }
    }

    fun hostOf(url: String): String? = Regex("^https?://(?:www\\.)?([^/:?#]+)").find(url)?.groupValues?.get(1)?.lowercase()
}
