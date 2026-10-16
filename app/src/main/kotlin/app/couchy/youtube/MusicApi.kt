package app.couchy.youtube

import android.content.Context
import android.webkit.CookieManager
import app.couchy.core.youtube.MusicAccount
import app.couchy.core.youtube.MusicShelf
import app.couchy.core.youtube.MusicTile
import app.couchy.core.youtube.YtVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The person's YouTube Music after they signed in to Google in Couchy's window: their playlists,
 * liked songs and the home page with the mixes made for them. Only the cookies Google gave are
 * kept, on this device; signing out forgets them.
 */
object MusicApi {
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    private const val CLIENT_VERSION = "1.20250310.01.00"

    @Volatile private var cookies: String? = null

    private fun store(context: Context) = context.getSharedPreferences("ytmusic", Context.MODE_PRIVATE)

    fun load(context: Context) {
        cookies = store(context).getString("cookies", null)?.takeIf { MusicAccount.signedIn(it) }
    }

    val signedIn: Boolean get() = cookies != null

    /** Takes the cookies the sign-in window left for music.youtube.com; false when there are none yet. */
    fun takeFromWebView(context: Context): Boolean {
        val header = CookieManager.getInstance().getCookie(MusicAccount.ORIGIN) ?: return false
        if (!MusicAccount.signedIn(header)) return false
        cookies = header
        store(context).edit().putString("cookies", header).apply()
        return true
    }

    /** Forgets the account here and in the sign-in window; other sites' cookies stay. */
    fun signOut(context: Context) {
        val header = cookies ?: store(context).getString("cookies", null)
        cookies = null
        store(context).edit().remove("cookies").apply()
        val manager = CookieManager.getInstance()
        header?.split(';')?.map { it.substringBefore('=').trim() }?.filter { it.isNotEmpty() }?.forEach { name ->
            manager.setCookie(MusicAccount.ORIGIN, "$name=; Max-Age=0; Domain=.youtube.com; Path=/")
            manager.setCookie(MusicAccount.ORIGIN, "$name=; Max-Age=0; Path=/")
        }
        manager.flush()
    }

    /** Playlists the person made or saved, liked songs first. */
    suspend fun playlists(): List<MusicTile> {
        val (_, tiles) = MusicAccount.items(browse(MusicAccount.PLAYLISTS))
        val liked = tiles.firstOrNull { it.url.endsWith("list=${MusicAccount.LIKED}") }
            ?: MusicTile(MusicAccount.playlistUrl(MusicAccount.LIKED), "Понравившиеся", "", null)
        return listOf(liked) + tiles.filter { it.url != liked.url }
    }

    /** The home page made for the person: quick picks, mixes, recommendations; two parts of it. */
    suspend fun home(): List<MusicShelf> {
        val first = browse(MusicAccount.HOME)
        val shelves = MusicAccount.shelves(first).toMutableList()
        var token = MusicAccount.continuation(first)
        repeat(2) {
            val t = token ?: return@repeat
            val more = runCatching { more(t) }.getOrNull() ?: return@repeat
            shelves += MusicAccount.shelves(more)
            token = MusicAccount.continuation(more)
        }
        return shelves
    }

    /** The songs of a playlist, album, mix or artist page, as the account sees it. */
    suspend fun tracks(url: String): List<YtVideo> {
        val songs = ArrayList<YtVideo>()
        MusicAccount.browseIdOf(url)?.let { id ->
            val page = runCatching { browse(id) }.getOrNull()
            if (page != null) {
                songs += MusicAccount.items(page).first
                var token = MusicAccount.continuation(page)
                while (token != null && songs.size < 500) {
                    val t = token
                    val more = runCatching { more(t) }.getOrNull() ?: break
                    val known = songs.map { it.id }.toSet()
                    val fresh = MusicAccount.items(more).first.filter { it.id !in known }
                    if (fresh.isEmpty()) break
                    songs += fresh
                    token = MusicAccount.continuation(more)
                }
            }
        }
        // A mix opens only as a queue.
        if (songs.isEmpty()) MusicAccount.playlistIdOf(url)?.let { id ->
            songs += MusicAccount.items(post("next", JSONObject().put("playlistId", id).put("enablePersistentPlaylistPanel", true).put("isAudioOnly", true))).first
        }
        return songs
    }

    /** The next part of a long page; old pages want the token in the address, new ones in the body. */
    private suspend fun more(token: String) = post("browse?ctoken=$token&continuation=$token&type=next", JSONObject().put("continuation", token))

    private suspend fun browse(id: String): JSONObject = post("browse", JSONObject().put("browseId", id))

    private suspend fun post(endpoint: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val header = cookies ?: throw IllegalStateException("Войдите в YouTube Music")
        body.put(
            "context",
            JSONObject().put("client", JSONObject().put("clientName", "WEB_REMIX").put("clientVersion", CLIENT_VERSION).put("hl", "ru").put("gl", "RU")),
        )
        val sep = if ('?' in endpoint) '&' else '?'
        val c = URL("${MusicAccount.ORIGIN}/youtubei/v1/$endpoint${sep}prettyPrint=false").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Cookie", header)
        MusicAccount.authorization(header, System.currentTimeMillis() / 1000)?.let { c.setRequestProperty("Authorization", it) }
        c.setRequestProperty("Origin", MusicAccount.ORIGIN)
        c.setRequestProperty("X-Origin", MusicAccount.ORIGIN)
        c.setRequestProperty("Referer", "${MusicAccount.ORIGIN}/")
        c.setRequestProperty("X-Goog-AuthUser", "0")
        c.setRequestProperty("X-YouTube-Client-Name", "67")
        c.setRequestProperty("X-YouTube-Client-Version", CLIENT_VERSION)
        try {
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            if (code == 401 || code == 403) throw IllegalStateException("YouTube Music не принял вход. Войдите ещё раз.")
            if (code >= 400) throw IllegalStateException("YouTube Music не ответил ($code)")
            JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally {
            c.disconnect()
        }
    }
}
