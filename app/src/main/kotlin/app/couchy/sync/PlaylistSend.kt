package app.couchy.sync

import android.net.Uri
import android.widget.Toast
import app.couchy.CouchyApp
import app.couchy.cast.CastDevice
import app.couchy.tv.PlaylistEntity
import app.couchy.tv.PlaylistKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL

/**
 * One IPTV playlist from this device to another Couchy in the home network, in one press:
 * a link or an Xtream login travels as is, a playlist file as its text, so the other device
 * gets the same channels with nothing to pick or type.
 */
object PlaylistSend {
    private const val MAX = 20 * 1024 * 1024

    suspend fun send(app: CouchyApp, device: CastDevice, p: PlaylistEntity) = withContext(Dispatchers.IO) {
        val body = JSONObject().put("name", p.name).put("kind", p.kind).put("url", p.url)
            .put("user", p.username ?: "").put("password", p.password ?: "").put("epg", p.epgOverride ?: "")
        if (p.url.startsWith("file:") || p.url.startsWith("content:")) {
            val text = runCatching {
                app.contentResolver.openInputStream(Uri.parse(p.url))?.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(65536)
                    while (out.size() <= MAX) {
                        val n = input.read(chunk)
                        if (n < 0) break
                        out.write(chunk, 0, n)
                    }
                    if (out.size() > MAX) null else out.toString("UTF-8")
                }
            }.getOrNull() ?: throw IOException("Не получилось прочитать файл плейлиста на этом устройстве.")
            body.put("file", fileName(p)).put("text", text)
        }
        val bytes = body.toString().toByteArray()
        val c = URL("http://${device.host}:${device.port}/playlist").openConnection(Proxy.NO_PROXY) as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 5_000
            c.readTimeout = 30_000
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.setFixedLengthStreamingMode(bytes.size)
            c.outputStream.use { it.write(bytes) }
            if (c.responseCode != 200) throw IOException("${device.name} не принял плейлист.")
        } catch (e: IOException) {
            throw IOException(e.message?.takeIf { it.contains("не принял") } ?: "${device.name} не отвечает. Проверьте, что Couchy на нём открыт.", e)
        } finally {
            c.disconnect()
        }
    }

    private fun fileName(p: PlaylistEntity): String {
        val last = Uri.parse(p.url).lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':').orEmpty()
        return (if (last.endsWith(".m3u", true) || last.endsWith(".m3u8", true)) last else p.name + ".m3u")
            .replace(Regex("[^\\p{L}\\p{N} ._-]"), "_")
    }

    /** On the receiving device, from the cast server: adds the playlist, or refreshes the same one. */
    fun receive(app: CouchyApp, body: String): Int {
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return 400
        val name = o.optString("name").ifBlank { "Плейлист" }
        val epg = o.optString("epg").ifBlank { null }
        runBlocking { runCatching { add(app, o, name, epg) } }
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(app, "Плейлист «$name» получен, каналы в разделе ТВ", Toast.LENGTH_LONG).show()
        }
        return 200
    }

    private suspend fun add(app: CouchyApp, o: JSONObject, name: String, epg: String?) {
        run {
            val have = app.tv.allPlaylists()
            val text = o.optString("text")
            when {
                text.isNotBlank() -> {
                    val file = o.optString("file").replace(Regex("[^\\p{L}\\p{N} ._-]"), "_").ifBlank { "$name.m3u" }
                    val target = File(File(app.filesDir, "playlists").apply { mkdirs() }, file)
                    target.writeText(text)
                    val url = Uri.fromFile(target).toString()
                    val same = have.firstOrNull { it.url == url }
                    if (same != null) app.tv.refresh(same.id) else app.tv.addM3u(name, url, epg)
                }
                o.optString("kind") == PlaylistKind.XTREAM.name -> {
                    val same = have.firstOrNull { it.url == o.optString("url") && it.username == o.optString("user") }
                    if (same != null) app.tv.refresh(same.id) else app.tv.addXtream(name, o.optString("url"), o.optString("user"), o.optString("password"))
                }
                o.optString("url").startsWith("http") -> {
                    val same = have.firstOrNull { it.url == o.optString("url") }
                    if (same != null) app.tv.refresh(same.id) else app.tv.addM3u(name, o.optString("url"), epg)
                }
                else -> Unit
            }
        }
    }
}
