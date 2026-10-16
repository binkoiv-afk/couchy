package app.couchy.core.sources

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Base64

private fun enc(s: String) = URLEncoder.encode(s.trim(), "UTF-8").replace("+", "%20")

/** Height from a label like "1080p", "1080", "HD 720": 0 when none. */
private fun heightIn(label: String) = Regex("""(\d{3,4})""").find(label)?.groupValues?.get(1)?.toIntOrNull() ?: 0

/**
 * Filmix through the API its own Android app and TV boxes use. Without signing in it gives the
 * lower qualities; signing in goes by a code typed on filmix's «Консоли» page, like on a TV.
 */
object Filmix {
    const val ID = "filmix"
    const val NAME = "Filmix"
    /** The API moves between domains; the first that answers is kept. */
    val apis = listOf("http://filmixapp.cyou/api/v2/", "http://filmixapp.vip/api/v2/")
    const val CONSOLES = "filmix.my/consoles"

    data class DeviceCode(val token: String, val userCode: String)

    fun device(devId: String, token: String) =
        "user_dev_apk=2.0.1&user_dev_id=$devId&user_dev_name=Couchy&user_dev_os=11&user_dev_vendor=Couchy&user_dev_token=$token"

    fun tokenRequestUrl(api: String, devId: String) = api + "token_request?" + device(devId, "")
    fun profileUrl(api: String, devId: String, token: String) = api + "user_profile?" + device(devId, token)
    fun searchUrl(api: String, query: String, devId: String, token: String) = api + "search?story=" + enc(query) + "&" + device(devId, token)
    fun itemUrl(api: String, id: String, devId: String, token: String) = api + "post/" + NativeVoice.base(id) + "?" + device(devId, token)

    fun parseDeviceCode(json: String): DeviceCode = JSONObject(json).let { DeviceCode(it.getString("code"), it.getString("user_code")) }

    /** True once the code was typed on the «Консоли» page. */
    fun parseSignedIn(json: String): Boolean = JSONObject(json).optJSONObject("user_data") != null

    fun parseSearch(json: String): List<NativeRelease> {
        val t = json.trim()
        val a = if (t.startsWith("[")) JSONArray(t) else JSONObject(t).optJSONArray("items") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { release(it, null) }
    }

    /** The film or series with the files of [voice], or of the first voice-over when none was picked. */
    fun parseItem(json: String, voice: String?): NativeRelease = release(JSONObject(json), voice)

    private fun release(o: JSONObject, voice: String?): NativeRelease {
        val links = o.optJSONObject("player_links")
        val movies = links?.optJSONArray("movie")
        val playlist = links?.optJSONObject("playlist")
        val voices = mutableListOf<String>()
        val episodes = mutableListOf<NativeEpisode>()
        val name = o.optString("title").trim()
        if (playlist != null && playlist.length() > 0) {
            // season → voice-over → episode → {link, qualities}
            playlist.keys().asSequence().sortedBy { it.toIntOrNull() ?: 0 }.forEach { s ->
                val byVoice = playlist.optJSONObject(s) ?: return@forEach
                byVoice.keys().forEach { v -> if (v !in voices) voices += v }
                val v = voice?.takeIf { byVoice.has(it) } ?: byVoice.keys().asSequence().firstOrNull() ?: return@forEach
                val eps = byVoice.optJSONObject(v) ?: return@forEach
                eps.keys().asSequence().sortedBy { it.toIntOrNull() ?: 0 }.forEach { e ->
                    val ep = eps.optJSONObject(e) ?: return@forEach
                    val files = files(ep.optString("link"), ep.optJSONArray("qualities"))
                    val n = e.toIntOrNull() ?: (episodes.size + 1)
                    if (files.isNotEmpty()) episodes += NativeEpisode(s.toIntOrNull() ?: 1, n, "Серия $n", files)
                }
            }
        } else if (movies != null) {
            val all = (0 until movies.length()).mapNotNull { movies.optJSONObject(it) }
            all.forEach { m -> m.optString("translation").trim().takeIf { it.isNotEmpty() && it !in voices }?.let { voices += it } }
            val m = all.firstOrNull { it.optString("translation").trim() == voice } ?: all.firstOrNull()
            m?.let { files(it.optString("link"), it.optJSONArray("qualities")) }?.takeIf { it.isNotEmpty() }?.let { episodes += NativeEpisode(0, 0, name, it) }
        }
        val id = o.opt("id")?.toString().orEmpty()
        return NativeRelease(
            source = ID,
            id = voice?.let { NativeVoice.of(id, it) } ?: id,
            name = name,
            original = o.optString("original_title").trim().takeIf { it.isNotEmpty() && it != "null" },
            year = o.optInt("year").takeIf { it > 0 },
            series = playlist != null && playlist.length() > 0 || o.optString("last_episode").isNotBlank() && o.optString("last_episode") != "null",
            poster = o.optString("poster").takeIf { it.startsWith("http") },
            imdbId = o.optString("imdb_id").takeIf { it.startsWith("tt") },
            episodes = episodes,
            voices = voices,
        )
    }

    /**
     * Filmix writes one link for every quality: "…_[480,720,1080,].mp4" or "…_%s.mp4" with the
     * qualities beside it. Best first.
     */
    fun files(link: String, qualities: JSONArray?): List<Pair<String, String>> {
        if (!link.startsWith("http")) return emptyList()
        val listed = qualities?.let { q -> (0 until q.length()).mapNotNull { q.opt(it)?.toString()?.toIntOrNull() } }.orEmpty()
        val bracket = Regex("""\[([\d,]+)]""").find(link)
        val out = when {
            "%s" in link -> listed.map { q -> "${q}p" to link.replace("%s", q.toString()) }
            bracket != null -> {
                val qs = bracket.groupValues[1].split(',').mapNotNull { it.trim().toIntOrNull() }.ifEmpty { listed }
                qs.map { q -> "${q}p" to link.replaceRange(bracket.range, q.toString()) }
            }
            else -> listOf((listed.maxOrNull()?.let { "${it}p" } ?: "SD") to link)
        }
        return out.distinctBy { it.first }.sortedByDescending { heightIn(it.first) }
    }
}

/**
 * Seasonvar: series only, one page per season. Its player's playlist is read the way the page
 * reads it: a list of episodes per voice-over with the files written in base64.
 */
object Seasonvar {
    const val ID = "seasonvar"
    const val NAME = "Seasonvar"
    const val HOST = "https://seasonvar.ru"

    fun searchUrl(query: String) = "$HOST/autocomplete.php?query=" + enc(query)

    /** Each hit is the series' first page: "serial-12345-Name.html". */
    fun parseSearch(json: String): List<NativeRelease> {
        val o = JSONObject(json)
        val sugg = o.opt("suggestions")
        val names = (sugg as? JSONArray) ?: (sugg as? JSONObject)?.optJSONArray("valu") ?: return emptyList()
        val pages = o.optJSONArray("data") ?: (sugg as? JSONObject)?.optJSONArray("data") ?: return emptyList()
        return (0 until minOf(names.length(), pages.length())).mapNotNull { i ->
            val page = pages.optString(i).trim().removePrefix("/").takeIf { it.startsWith("serial-") } ?: return@mapNotNull null
            val label = names.optString(i).replace(Regex("<[^>]+>"), "").trim()
            val parts = label.split("/").map { it.trim() }.filter { it.isNotEmpty() }
            NativeRelease(ID, page, parts.firstOrNull() ?: label, parts.getOrNull(1), null, true, null)
        }
    }

    /** The season pages listed on any season's page, in order; the page itself when none are. */
    fun parseSeasons(html: String, self: String): List<String> {
        val block = html.substringAfter("pgs-seaslist", "")
        val found = Regex("""href="/?(serial-\d+-[^"#?]+\.html)"""").findAll(block.substringBefore("</ul>").ifEmpty { block.take(20_000) })
            .map { it.groupValues[1] }.distinct().toList()
        return found.ifEmpty { listOf(self) }
    }

    data class SeasonPage(val season: Int, val seasonId: String, val secure: String, val time: String, val voices: List<Pair<String, String>>, val poster: String?)

    fun parseSeasonPage(html: String): SeasonPage? {
        val seasonId = Regex("""data-id-season="(\d+)"""").find(html)?.groupValues?.get(1) ?: return null
        val secure = Regex("""secureMark['"]?\s*:\s*['"]([^'"]+)""").find(html)?.groupValues?.get(1) ?: return null
        val time = Regex("""['"]?time['"]?\s*:\s*['"]?(\d+)""").find(html.substringAfter("data4play", html))?.groupValues?.get(1).orEmpty()
        val season = Regex("""(\d+)\s*сезон""").find(html.substringAfter("<title>", "").substringBefore("</title>"))?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val voices = Regex("""<li[^>]*data-translate="([^"]*)"[^>]*>(.*?)</li>""", RegexOption.DOT_MATCHES_ALL).findAll(html)
            .map { it.groupValues[1] to it.groupValues[2].replace(Regex("<[^>]+>"), "").trim() }
            .filter { it.second.isNotEmpty() }.distinctBy { it.first }.toList()
        val poster = Regex("""<meta property="og:image" content="([^"]+)"""").find(html)?.groupValues?.get(1)
        return SeasonPage(season, seasonId, secure, time, voices, poster)
    }

    /** The playlist of one season in one voice-over; "" is the site's own default voice. */
    fun playlistUrl(p: SeasonPage, translate: String) =
        "$HOST/playls2/${p.secure}/trans${enc(translate)}/${p.seasonId}/plist.txt" + if (p.time.isNotEmpty()) "?time=${p.time}" else ""

    fun parsePlaylist(json: String, season: Int): List<NativeEpisode> {
        val t = json.trim()
        if (!t.startsWith("[") && !t.startsWith("{")) return emptyList()
        val items = mutableListOf<JSONObject>()
        fun walk(a: JSONArray) {
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                o.optJSONArray("playlist")?.let { walk(it) } ?: o.optJSONArray("folder")?.let { walk(it) } ?: items.add(o)
            }
        }
        if (t.startsWith("[")) walk(JSONArray(t)) else JSONObject(t).optJSONArray("playlist")?.let { walk(it) }
        return items.mapIndexedNotNull { i, o ->
            val file = decode(o.optString("file")).takeIf { it.startsWith("http") } ?: return@mapIndexedNotNull null
            val title = o.optString("title").replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
            val n = Regex("""(\d+)\s*серия""").find(title)?.groupValues?.get(1)?.toIntOrNull() ?: (i + 1)
            val quality = if (Regex("""\bHD\b""").containsMatchIn(title) || "hd" in file.substringAfterLast('/').lowercase()) "720p" else "480p"
            NativeEpisode(season, n, "Серия $n", listOf(quality to file))
        }.distinctBy { it.episode }
    }

    /** "#2" + base64 with "//b2xvbG8=" dropped in to spoil it; plain links pass as they are. */
    fun decode(file: String): String {
        if (!file.startsWith("#2")) return file
        val body = file.removePrefix("#2").replace("//b2xvbG8=", "").replace("\\/", "/")
        return runCatching { String(Base64.getMimeDecoder().decode(body), Charsets.UTF_8) }.getOrDefault("").substringBefore(" or ")
    }
}

/**
 * Rutube: films and series people uploaded, found by name and played from its HLS link.
 * A film's id is "v" + the video id; a series is "q" + the name it was searched by.
 */
object Rutube {
    const val ID = "rutube"
    const val NAME = "Rutube"
    const val API = "https://rutube.ru/api"
    const val OPTIONS = "$API/play/options/"

    fun searchUrl(query: String, page: Int = 1) = "$API/search/video/?query=" + enc(query) + "&page=$page&format=json"
    fun optionsUrl(videoId: String) = "$OPTIONS$videoId/?no_404=true&referer=https%3A%2F%2Frutube.ru&format=json"
    fun pageOf(id: String) = if (id.startsWith("v")) "https://rutube.ru/video/${id.drop(1)}/" else "https://rutube.ru/search/?query=${id.drop(1)}"

    data class Video(val id: String, val title: String, val minutes: Int, val thumb: String?)

    fun parseSearch(json: String): List<Video> {
        val a = JSONObject(json).optJSONArray("results") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { o ->
            val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Video(id, o.optString("title").trim(), o.optInt("duration") / 60, o.optString("thumbnail_url").takeIf { it.startsWith("http") })
        }
    }

    /** True when [video]'s title is about [names]: the name in it, and no trailer or review. */
    private fun about(video: Video, names: List<String>): Boolean {
        val t = TitleMerge.normalize(video.title)
        if (Regex("""трейлер|trailer|обзор|тизер|teaser|review|разбор|реакция|клип""").containsMatchIn(t)) return false
        return names.map(TitleMerge::normalize).filter { it.length >= 3 }.any { n -> Regex("""(^| )${Regex.escape(n)}( |$)""").containsMatchIn(t) }
    }

    /** The full film among the hits: about it, an hour long or more, the year in the title when it says one. */
    fun film(videos: List<Video>, names: List<String>, year: Int?): Video? =
        videos.filter { it.minutes >= 60 && about(it, names) }
            .sortedByDescending { v -> if (year != null && year.toString() in v.title) 1 else 0 }
            .firstOrNull { v -> year == null || Regex("""(19|20)\d\d""").findAll(v.title).map { it.value.toInt() }.toList().let { ys -> ys.isEmpty() || ys.any { kotlin.math.abs(it - year) <= 1 } } }

    /** Episodes among the hits: season from "N сезон", episode from "N серия" or "серия N". */
    fun episodes(videos: List<Video>, names: List<String>): List<NativeEpisode> =
        videos.filter { it.minutes >= 15 && about(it, names) }.mapNotNull { v ->
            val t = v.title.lowercase()
            val e = Regex("""(\d+)\s*(?:-?я\s*)?серия""").find(t)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""серия\s*(\d+)""").find(t)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""s\d+\s*e(\d+)""").find(t)?.groupValues?.get(1)?.toIntOrNull()
                ?: return@mapNotNull null
            val s = Regex("""(\d+)\s*(?:-?й\s*)?сезон""").find(t)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""сезон\s*(\d+)""").find(t)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""s(\d+)\s*e\d+""").find(t)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            NativeEpisode(s, e, "Серия $e", listOf("Авто" to optionsUrl(v.id)))
        }.distinctBy { it.season to it.episode }.sortedWith(compareBy({ it.season }, { it.episode }))

    /** The HLS link from the play options; it carries every quality. */
    fun parseOptions(json: String): String? {
        val b = JSONObject(json).optJSONObject("video_balancer") ?: return null
        return b.optString("m3u8").takeIf { it.startsWith("http") } ?: b.optString("default").takeIf { it.startsWith("http") }
    }
}
