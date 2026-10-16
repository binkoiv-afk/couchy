package app.couchy.core.youtube

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** An album, playlist, mix or artist on YouTube Music, opened by [url]. */
data class MusicTile(val url: String, val title: String, val subtitle: String, val thumb: String?)

/** One shelf of the person's YouTube Music home: "Быстрый выбор", "Миксы для вас", and so on. */
data class MusicShelf(val title: String, val songs: List<YtVideo>, val tiles: List<MusicTile>)

/**
 * The person's own YouTube Music, read as their signed-in browser reads it: the same pages
 * music.youtube.com asks for with the cookies Google gave after sign-in. Nothing here signs in;
 * the person does that on Google's own page.
 */
object MusicAccount {
    const val ORIGIN = "https://music.youtube.com"
    const val HOME = "FEmusic_home"
    const val PLAYLISTS = "FEmusic_liked_playlists"
    const val LIKED = "LM"

    /** The value of cookie [name] in a Cookie header, or null. */
    fun cookie(header: String, name: String): String? = header.split(';')
        .map { it.trim() }
        .firstOrNull { it.startsWith("$name=") }
        ?.substringAfter('=')
        ?.takeIf { it.isNotEmpty() }

    /** Whether [header] holds what a signed-in request needs. */
    fun signedIn(header: String): Boolean = sapisid(header) != null

    private fun sapisid(header: String) = cookie(header, "SAPISID") ?: cookie(header, "__Secure-3PAPISID")

    /** The Authorization header YouTube's own pages send with the cookies: "SAPISIDHASH <time>_<sha1>". */
    fun authorization(header: String, nowSec: Long): String? {
        val id = sapisid(header) ?: return null
        val digest = MessageDigest.getInstance("SHA-1").digest("$nowSec $id $ORIGIN".toByteArray())
        return "SAPISIDHASH ${nowSec}_" + digest.joinToString("") { "%02x".format(it) }
    }

    /** A playlist's or mix's link for its id. */
    fun playlistUrl(id: String) = "$ORIGIN/playlist?list=$id"

    /** The page to ask for [url]: "VL<id>" for a playlist, the id itself for an album or artist. */
    fun browseIdOf(url: String): String? = when {
        "list=" in url -> "VL" + url.substringAfter("list=").substringBefore('&')
        "/browse/" in url -> url.substringAfter("/browse/").substringBefore('?')
        else -> null
    }

    /** The playlist id inside [url], for a mix that opens only as a queue. */
    fun playlistIdOf(url: String): String? = url.takeIf { "list=" in it }?.substringAfter("list=")?.substringBefore('&')

    // ---- Reading the pages ----

    /** Shelves of a home or artist page, in order; empty shelves are left out. */
    fun shelves(page: JSONObject): List<MusicShelf> {
        val out = ArrayList<MusicShelf>()
        walk(page) { key, node ->
            if (key == "musicCarouselShelfRenderer" || key == "musicShelfRenderer") {
                val title = text(
                    node.optJSONObject("header")?.optJSONObject("musicCarouselShelfBasicHeaderRenderer")?.optJSONObject("title")
                        ?: node.optJSONObject("title"),
                )
                val items = items(node.optJSONArray("contents") ?: JSONArray())
                if (title.isNotBlank() && (items.first.isNotEmpty() || items.second.isNotEmpty())) out += MusicShelf(title, items.first, items.second)
                false
            } else true
        }
        return out
    }

    /** Every song and every album or playlist on a page, wherever it sits. */
    fun items(page: Any): Pair<List<YtVideo>, List<MusicTile>> {
        val songs = LinkedHashMap<String, YtVideo>()
        val tiles = LinkedHashMap<String, MusicTile>()
        walk(page) { key, node ->
            when (key) {
                "musicResponsiveListItemRenderer" -> { listItem(node)?.let { it.fold({ v -> songs.putIfAbsent(v.id, v) }, { t -> tiles.putIfAbsent(t.url, t) }) }; false }
                "musicTwoRowItemRenderer" -> { twoRow(node)?.let { it.fold({ v -> songs.putIfAbsent(v.id, v) }, { t -> tiles.putIfAbsent(t.url, t) }) }; false }
                "playlistPanelVideoRenderer" -> { panelItem(node)?.let { songs.putIfAbsent(it.id, it) }; false }
                else -> true
            }
        }
        return songs.values.toList() to tiles.values.toList()
    }

    /** The token for the next part of a long page, if there is one. */
    fun continuation(page: JSONObject): String? {
        var token: String? = null
        walk(page) { key, node ->
            if (token == null && key == "nextContinuationData") token = node.optString("continuation").ifBlank { null }
            if (token == null && key == "continuationCommand") token = node.optString("token").ifBlank { null }
            token == null
        }
        return token
    }

    private fun listItem(o: JSONObject): Either? {
        val columns = o.optJSONArray("flexColumns") ?: return null
        fun column(i: Int) = columns.optJSONObject(i)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text")
        val title = text(column(0)).ifBlank { return null }
        val byline = byline(text(column(1)))
        val thumb = thumbOf(o.optJSONObject("thumbnail"))
        val videoId = o.optJSONObject("playlistItemData")?.optString("videoId")?.ifBlank { null }
            ?: o.optJSONObject("overlay")?.let { find(it, "watchEndpoint") }?.optString("videoId")?.ifBlank { null }
            ?: column(0)?.let { find(it, "watchEndpoint") }?.optString("videoId")?.ifBlank { null }
        if (videoId != null) {
            val length = o.optJSONArray("fixedColumns")?.optJSONObject(0)?.optJSONObject("musicResponsiveListItemFixedColumnRenderer")?.optJSONObject("text")
            return Either.Song(YtVideo(videoId, title, byline, durationSec = seconds(text(length)), thumb = thumb ?: YouTubeLinks.thumb(videoId)))
        }
        val url = urlOf(o.optJSONObject("navigationEndpoint")) ?: return null
        return Either.Tile(MusicTile(url, title, byline, thumb))
    }

    private fun twoRow(o: JSONObject): Either? {
        val title = text(o.optJSONObject("title")).ifBlank { return null }
        val subtitle = text(o.optJSONObject("subtitle"))
        val thumb = thumbOf(o.optJSONObject("thumbnailRenderer"))
        val nav = o.optJSONObject("navigationEndpoint")
        nav?.optJSONObject("watchEndpoint")?.optString("videoId")?.ifBlank { null }?.let { id ->
            return Either.Song(YtVideo(id, title, byline(subtitle), thumb = thumb ?: YouTubeLinks.thumb(id)))
        }
        val url = urlOf(nav) ?: return null
        return Either.Tile(MusicTile(url, title, subtitle, thumb))
    }

    private fun panelItem(o: JSONObject): YtVideo? {
        val id = o.optString("videoId").ifBlank { return null }
        return YtVideo(
            id,
            text(o.optJSONObject("title")),
            byline(text(o.optJSONObject("shortBylineText"))),
            durationSec = seconds(text(o.optJSONObject("lengthText"))),
            thumb = thumbOf(o) ?: YouTubeLinks.thumb(id),
        )
    }

    private fun urlOf(nav: JSONObject?): String? {
        nav ?: return null
        nav.optJSONObject("browseEndpoint")?.optString("browseId")?.ifBlank { null }?.let { id ->
            return if (id.startsWith("VL")) playlistUrl(id.removePrefix("VL")) else "$ORIGIN/browse/$id"
        }
        nav.optJSONObject("watchPlaylistEndpoint")?.optString("playlistId")?.ifBlank { null }?.let { return playlistUrl(it) }
        nav.optJSONObject("watchEndpoint")?.optString("playlistId")?.ifBlank { null }?.let { return playlistUrl(it) }
        return null
    }

    /** "Песня • Artist • Album" → "Artist". */
    private fun byline(text: String): String = text.split(" • ").map { it.trim() }
        .firstOrNull { it.isNotEmpty() && it !in KINDS } ?: ""

    private val KINDS = setOf("Song", "Песня", "Video", "Видео", "Трек", "Single", "Сингл", "Album", "Альбом", "EP", "Playlist", "Плейлист")

    private fun text(o: JSONObject?): String {
        o ?: return ""
        o.optString("simpleText").takeIf { it.isNotEmpty() }?.let { return it }
        val runs = o.optJSONArray("runs") ?: return ""
        return (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() }
    }

    private fun thumbOf(o: JSONObject?): String? {
        val list = o?.let { findArray(it, "thumbnails") } ?: return null
        return list.optJSONObject(list.length() - 1)?.optString("url")?.ifBlank { null }?.let { if (it.startsWith("//")) "https:$it" else it }
    }

    private fun seconds(text: String): Long {
        val parts = text.trim().split(':').mapNotNull { it.toLongOrNull() }
        if (parts.isEmpty() || parts.size > 3) return 0
        return parts.fold(0L) { acc, n -> acc * 60 + n }
    }

    private fun find(o: JSONObject, name: String): JSONObject? {
        var hit: JSONObject? = null
        walk(o) { key, node -> if (hit == null && key == name) hit = node; hit == null }
        return hit
    }

    private fun findArray(o: Any, name: String): JSONArray? {
        when (o) {
            is JSONObject -> for (k in o.keys()) {
                val v = o.opt(k)
                if (k == name && v is JSONArray) return v
                if (v is JSONObject || v is JSONArray) findArray(v, name)?.let { return it }
            }
            is JSONArray -> for (i in 0 until o.length()) {
                val v = o.opt(i)
                if (v is JSONObject || v is JSONArray) findArray(v, name)?.let { return it }
            }
        }
        return null
    }

    /** Visits every object under [node] by the key it sits at; [visit] returns false to skip what is inside. */
    private fun walk(node: Any, key: String = "", visit: (String, JSONObject) -> Boolean) {
        when (node) {
            is JSONObject -> {
                if (key.isNotEmpty() && !visit(key, node)) return
                for (k in node.keys()) {
                    val v = node.opt(k)
                    if (v is JSONObject || v is JSONArray) walk(v, k, visit)
                }
            }
            is JSONArray -> for (i in 0 until node.length()) {
                val v = node.opt(i)
                if (v is JSONObject || v is JSONArray) walk(v, key, visit)
            }
        }
    }

    private sealed interface Either {
        data class Song(val video: YtVideo) : Either
        data class Tile(val tile: MusicTile) : Either
    }

    private fun Either.fold(onSong: (YtVideo) -> Unit, onTile: (MusicTile) -> Unit) = when (this) {
        is Either.Song -> onSong(video)
        is Either.Tile -> onTile(tile)
    }
}
