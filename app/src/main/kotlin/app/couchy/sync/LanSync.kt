package app.couchy.sync

import android.content.Context
import android.net.Uri
import android.util.Log
import app.couchy.CouchyApp
import app.couchy.cast.CastDevice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.util.UUID

/** A device this one keeps in step with over Wi‑Fi. */
data class LanPeer(val id: String, val name: String, val key: String, val host: String, val port: Int, val lastSync: Long = 0) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("key", key).put("host", host).put("port", port).put("last", lastSync)

    companion object {
        fun from(o: JSONObject) = LanPeer(o.optString("id"), o.optString("name"), o.optString("key"), o.optString("host"), o.optInt("port"), o.optLong("last"))
    }
}

/** Another device asking to be linked; answered on this screen. */
data class LanAsk(val name: String, val answer: CompletableDeferred<Boolean>)

/**
 * Wi‑Fi sync: devices the person linked keep history, positions, favourites, marks and IPTV
 * playlists (playlist files too) the same, straight between each other in the home network,
 * with no cloud account. It runs when Couchy opens or closes, after watching, and every few
 * minutes while Couchy is open on both. Each pair merges three ways against what the two agreed
 * on last, so deletions travel as well.
 */
object LanSync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = Mutex()
    private lateinit var app: CouchyApp
    @Volatile private var lastRun = 0L

    private val _peers = MutableStateFlow<List<LanPeer>>(emptyList())
    val peers: StateFlow<List<LanPeer>> = _peers.asStateFlow()

    private val _asking = MutableStateFlow<LanAsk?>(null)
    /** A link request from another device waiting for a yes or no here. */
    val asking: StateFlow<LanAsk?> = _asking.asStateFlow()

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    fun init(app: CouchyApp) {
        this.app = app
        _peers.value = load()
        scope.launch {
            while (true) {
                delay(4 * 60_000L)
                syncAll()
            }
        }
    }

    private fun prefs() = app.getSharedPreferences("lan_sync", Context.MODE_PRIVATE)

    /** This device's id among the linked ones. */
    val myId: String
        get() = prefs().getString("id", null) ?: UUID.randomUUID().toString().also { prefs().edit().putString("id", it).apply() }

    private fun load(): List<LanPeer> = runCatching {
        val a = JSONArray(prefs().getString("peers", "[]"))
        (0 until a.length()).map { LanPeer.from(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun save(list: List<LanPeer>) {
        _peers.value = list
        prefs().edit().putString("peers", JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()).apply()
    }

    private fun remember(peer: LanPeer) = synchronized(this) { save(_peers.value.filter { it.id != peer.id } + peer) }

    fun forget(id: String) = synchronized(this) { save(_peers.value.filter { it.id != id }) }

    /** Soon, in the background; often-called, so close calls fold into one. */
    fun soon() {
        if (!::app.isInitialized || _peers.value.isEmpty()) return
        scope.launch { syncAll(minGapMs = 15_000) }
    }

    suspend fun syncAll(minGapMs: Long = 0) {
        if (_peers.value.isEmpty()) return
        if (System.currentTimeMillis() - lastRun < minGapMs) return
        running.withLock {
            lastRun = System.currentTimeMillis()
            var ok = 0
            val list = _peers.value
            list.forEach { p -> if (runCatching { syncWith(p) }.onFailure { Log.w("Couchy", "Wi-Fi sync with ${p.name}: ${it.message}") }.isSuccess) ok++ }
            _status.value = when {
                ok == list.size -> "Синхронизировано в " + clock(System.currentTimeMillis())
                ok > 0 -> "Не все устройства ответили (${list.size - ok}). Couchy на них должен быть открыт"
                else -> "Устройства не ответили. Couchy на них должен быть открыт и в той же сети Wi‑Fi"
            }
        }
    }

    private fun clock(t: Long) = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(t))

    /** Asks [device] to link with this one; the person there says yes. True when linked. */
    suspend fun link(device: CastDevice): Boolean = withContext(Dispatchers.IO) {
        val key = UUID.randomUUID().toString()
        val body = JSONObject().put("id", myId).put("name", app.cast.deviceName).put("key", key)
        val answer = post(device.host, device.port, "/lan/link", body.toString(), readMs = 75_000) ?: return@withContext false
        val o = JSONObject(answer)
        remember(LanPeer(o.getString("id"), o.optString("name", device.name), key, device.host, device.port))
        runCatching { syncAll() }
        true
    }

    /** On the asked device, from the cast server: waits for the person's answer. */
    fun answerLink(body: String): Pair<Int, String> {
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return 400 to "{}"
        val id = o.optString("id").takeIf { it.isNotBlank() } ?: return 400 to "{}"
        val name = o.optString("name").ifBlank { "Устройство" }
        val ask = LanAsk(name, CompletableDeferred())
        _asking.value = ask
        val yes = runBlocking { withTimeoutOrNull(70_000) { ask.answer.await() } } == true
        if (_asking.value === ask) _asking.value = null
        if (!yes) return 403 to "{}"
        remember(LanPeer(id, name, o.optString("key"), "", 0))
        return 200 to JSONObject().put("id", myId).put("name", app.cast.deviceName).toString()
    }

    fun reply(ask: LanAsk, yes: Boolean) {
        ask.answer.complete(yes)
        if (_asking.value === ask) _asking.value = null
    }

    private suspend fun syncWith(p: LanPeer) {
        val body = JSONObject()
            .put("id", myId).put("key", p.key)
            .put("state", app.sync.snapshot())
            .put("files", PlaylistFiles.export(app))
            .toString()
        // The address it had last time, else look for it in the network.
        val known = p.host.takeIf { it.isNotBlank() }?.let { h -> post(h, p.port, "/lan/sync", body)?.let { (h to p.port) to it } }
        val (address, answer) = known ?: find(p)?.let { d -> post(d.host, d.port, "/lan/sync", body)?.let { (d.host to d.port) to it } }
            ?: throw java.io.IOException("не найдено")
        val o = JSONObject(answer)
        app.sync.mergePeer(p.id, o.getString("state"))
        o.optJSONArray("files")?.let { PlaylistFiles.import(app, it) }
        remember(p.copy(host = address.first, port = address.second, lastSync = System.currentTimeMillis()))
    }

    /** On the other device, from the cast server: merges what came and answers with the result. */
    fun answerSync(body: String, from: String?): Pair<Int, String> {
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return 400 to "{}"
        val peer = _peers.value.firstOrNull { it.id == o.optString("id") && it.key == o.optString("key") } ?: return 403 to "{}"
        return runBlocking {
            val merged = app.sync.mergePeer(peer.id, o.getString("state"))
            o.optJSONArray("files")?.let { PlaylistFiles.import(app, it) }
            remember(peer.copy(host = from ?: peer.host, port = if (from != null) app.cast.port else peer.port, lastSync = System.currentTimeMillis()))
            _status.value = "Синхронизировано в " + clock(System.currentTimeMillis())
            200 to JSONObject().put("state", merged).put("files", PlaylistFiles.export(app)).toString()
        }
    }

    /** Looks for [p] among the Couchy devices announcing themselves in the network. */
    private suspend fun find(p: LanPeer): CastDevice? {
        val seen = withTimeoutOrNull(6_000) { app.cast.discover().first { list -> list.any { d -> idOf(d) == p.id } } }
        return seen?.firstOrNull { idOf(it) == p.id }
    }

    private val ids = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun idOf(d: CastDevice): String? = ids["${d.host}:${d.port}"] ?: runCatching {
        val c = URL("http://${d.host}:${d.port}/hello").openConnection(Proxy.NO_PROXY) as HttpURLConnection
        try {
            c.connectTimeout = 2_000
            c.readTimeout = 2_000
            JSONObject(c.inputStream.bufferedReader().use { it.readText() }).optString("id").takeIf { it.isNotBlank() }
        } finally {
            c.disconnect()
        }
    }.getOrNull()?.also { ids["${d.host}:${d.port}"] = it }

    private fun post(host: String, port: Int, path: String, body: String, readMs: Int = 30_000): String? = runCatching {
        val bytes = body.toByteArray()
        val c = URL("http://$host:$port$path").openConnection(Proxy.NO_PROXY) as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 4_000
            c.readTimeout = readMs
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.setFixedLengthStreamingMode(bytes.size)
            c.outputStream.use { it.write(bytes) }
            if (c.responseCode == 200) c.inputStream.bufferedReader().use { it.readText() } else null
        } finally {
            c.disconnect()
        }
    }.getOrNull()
}

/**
 * IPTV playlists added from a file live only in this device's storage: they travel as their text,
 * so the TV gets the same channels the phone has.
 */
object PlaylistFiles {
    private fun dir(context: Context) = File(context.filesDir, "playlists").apply { mkdirs() }

    suspend fun export(app: CouchyApp): JSONArray = withContext(Dispatchers.IO) {
        val out = JSONArray()
        app.tv.allPlaylists().filter { it.url.startsWith("file:") }.forEach { p ->
            val file = runCatching { File(java.net.URI(p.url)) }.getOrNull()?.takeIf { it.exists() && it.length() < 6_000_000 } ?: return@forEach
            out.put(JSONObject().put("name", p.name).put("file", file.name).put("epg", p.epgOverride ?: "").put("text", file.readText()))
        }
        out
    }

    suspend fun import(app: CouchyApp, files: JSONArray) = withContext(Dispatchers.IO) {
        val have = app.tv.allPlaylists().filter { it.url.startsWith("file:") }
        for (i in 0 until files.length()) {
            val o = files.optJSONObject(i) ?: continue
            val name = o.optString("file").replace(Regex("[^\\p{L}\\p{N} ._-]"), "_")
            val text = o.optString("text")
            if (name.isBlank() || text.isBlank()) continue
            val target = File(dir(app), name)
            val existing = have.firstOrNull { runCatching { File(java.net.URI(it.url)).name }.getOrNull() == name }
            if (existing != null && target.exists() && target.readText() == text) continue
            target.writeText(text)
            runCatching {
                if (existing != null) app.tv.refresh(existing.id)
                else app.tv.addM3u(o.optString("name").ifBlank { name.substringBeforeLast('.') }, Uri.fromFile(target).toString(), o.optString("epg").ifBlank { null })
            }
        }
    }
}
