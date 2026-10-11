package app.couchy.torrent

import app.couchy.core.torrent.TorrFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** What the engine reports about one torrent. */
data class TorrStatus(
    val hash: String,
    val name: String,
    /** 0 added, 1 getting info, 2 preloading, 3 working, 4 closed, 5 only in the database. */
    val stat: Int,
    val files: List<TorrFile>,
    val size: Long,
    val peers: Int,
    val activePeers: Int,
    val seeders: Int,
    val speed: Double,
    val preloaded: Long,
    val preloadSize: Long,
)

/**
 * Talks to a TorrServer (MatriX API): the one Couchy runs itself or a TorrServe elsewhere in
 * the home network. Torrents are added once, then their files are played as plain HTTP streams.
 */
class TorrServer(val base: String) {
    suspend fun echo(): String = withContext(Dispatchers.IO) { request("GET", "/echo", null, timeout = 3_000).trim() }

    /** Adds a magnet, an http(s) .torrent link or a hash; the engine keeps it in its own list. */
    suspend fun add(link: String, title: String?): TorrStatus = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("action", "add")
            .put("link", link)
            .put("title", title.orEmpty())
            .put("poster", "")
            .put("save_to_db", true)
        parse(JSONObject(request("POST", "/torrents", body.toString())))
    }

    /** Sends a .torrent file's bytes (for files opened from storage). */
    suspend fun upload(name: String, input: InputStream): TorrStatus = withContext(Dispatchers.IO) {
        val boundary = "couchy" + System.nanoTime()
        val connection = connect("POST", "/torrent/upload", 30_000)
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        connection.outputStream.use { out ->
            fun line(text: String) = out.write((text + "\r\n").toByteArray())
            line("--$boundary")
            line("Content-Disposition: form-data; name=\"save\"")
            line("")
            line("true")
            line("--$boundary")
            line("Content-Disposition: form-data; name=\"file\"; filename=\"${name.replace("\"", "")}\"")
            line("Content-Type: application/x-bittorrent")
            line("")
            input.copyTo(out)
            line("")
            line("--$boundary--")
        }
        val text = read(connection)
        val json = text.trim().let { if (it.startsWith("[")) JSONArray(it).getJSONObject(0) else JSONObject(it) }
        parse(json)
    }

    suspend fun get(hash: String): TorrStatus = withContext(Dispatchers.IO) {
        val body = JSONObject().put("action", "get").put("hash", hash)
        parse(JSONObject(request("POST", "/torrents", body.toString(), timeout = 8_000)))
    }

    /** A URL the player can open; the engine fetches pieces in order as the player reads. */
    fun streamUrl(hash: String, file: TorrFile): String =
        "$base/stream/" + encode(file.name) + "?link=$hash&index=${file.id}&play"

    private fun parse(o: JSONObject): TorrStatus {
        val files = o.optJSONArray("file_stats")?.let { a ->
            (0 until a.length()).map { i ->
                val f = a.getJSONObject(i)
                TorrFile(f.optInt("id"), f.optString("path"), f.optLong("length"))
            }
        }.orEmpty()
        return TorrStatus(
            hash = o.optString("hash"),
            name = o.optString("name").ifBlank { o.optString("title") },
            stat = o.optInt("stat"),
            files = files,
            size = o.optLong("torrent_size"),
            peers = o.optInt("total_peers"),
            activePeers = o.optInt("active_peers"),
            seeders = o.optInt("connected_seeders"),
            speed = o.optDouble("download_speed", 0.0).takeIf { !it.isNaN() } ?: 0.0,
            preloaded = o.optLong("preloaded_bytes"),
            preloadSize = o.optLong("preload_size"),
        )
    }

    private fun connect(method: String, path: String, timeout: Int): HttpURLConnection =
        (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = timeout.coerceAtMost(5_000)
            readTimeout = timeout
            useCaches = false
        }

    private fun request(method: String, path: String, json: String?, timeout: Int = 30_000): String {
        val connection = connect(method, path, timeout)
        if (json != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(json.toByteArray()) }
        }
        return read(connection)
    }

    private fun read(connection: HttpURLConnection): String {
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }?.take(200).orEmpty()
                throw IOException("TorrServer ответил $code ${detail.trim()}".trim())
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(text: String) = URLEncoder.encode(text, "UTF-8").replace("+", "%20")
}
