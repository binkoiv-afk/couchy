package app.couchy.pair

import android.content.Context
import android.content.Intent
import app.couchy.CouchyApp
import app.couchy.core.pair.PairLink
import app.couchy.sync.WebDav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * "Перенос на приставку": the TV shows a QR, the phone scans it and sends over everything the TV
 * would otherwise need typed with a remote: sync and Kino.pub sign-ins, the bypass, site addresses,
 * YouTube subscriptions, the library and IPTV playlists. Only inside the home network, only to the
 * TV whose screen shows the code.
 */
object Transfer {
    /** Settings kept in plain preference files that travel as they are. */
    private val files = listOf("kinopub", "filmix", "sources", "voices", "player", "youtube", "marks")

    /** The code on the TV's screen right now; null when it shows no QR. */
    @Volatile var code: String? = null
        set(value) { synchronized(this) { field = value; misses = 0 } }
    /** Wrong codes in a row; after a few the QR stops working, so the code cannot be guessed. */
    private var misses = 0
    /** Set when wrong codes closed the QR: the TV shows a fresh one. */
    @Volatile var onExpired: (() -> Unit)? = null
    /** Called on the TV when a phone sent its settings with the right code. */
    @Volatile var onReceived: ((String) -> Unit)? = null

    /** Set on the TV when the phone synced through Google Drive. */
    const val ASK_GOOGLE = "ask_google"

    /** On the phone: everything to send, as one JSON. */
    suspend fun bundle(context: Context): String {
        val app = context.applicationContext as CouchyApp
        val prefs = JSONObject()
        files.forEach { name ->
            val all = context.getSharedPreferences(name, Context.MODE_PRIVATE).all
            if (all.isNotEmpty()) prefs.put(name, JSONObject().apply { all.forEach { (k, v) -> put(k, typed(v) ?: return@forEach) } })
        }
        return JSONObject()
            .put("settings", JSONObject(app.settings.exportTransfer()))
            .put("prefs", prefs)
            .put("state", runCatching { app.sync.snapshot() }.getOrNull())
            .put("google", app.settings.prefs.first().syncUrl == WebDav.GOOGLE_ACCOUNT)
            .toString()
    }

    /** On the TV: takes the phone's settings in; the app restarts afterwards so everything picks them up. */
    suspend fun apply(context: Context, json: String) {
        val app = context.applicationContext as CouchyApp
        val o = JSONObject(json)
        o.optJSONObject("settings")?.let { s -> app.settings.importTransfer(s.keys().asSequence().associateWith { s.getString(it) }) }
        o.optJSONObject("prefs")?.let { p ->
            p.keys().forEach { name ->
                if (name !in files) return@forEach
                val values = p.getJSONObject(name)
                val edit = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
                values.keys().forEach { k -> put(edit, k, values.getJSONObject(k)) }
                edit.commit()
            }
        }
        // Google Drive needs this device's own account: Couchy asks for it right after the restart.
        if (o.optBoolean("google")) context.getSharedPreferences("pair", Context.MODE_PRIVATE).edit().putBoolean(ASK_GOOGLE, true).commit()
        o.optString("state").takeIf { it.isNotBlank() }?.let { runCatching { app.sync.absorb(it) } }
        // In the background: a slow sync server must not keep the TV waiting on this screen.
        runCatching { app.sync.syncSoon() }
    }

    /** On the phone: sends to the TV from the QR. True when the TV took it. */
    suspend fun send(context: Context, link: PairLink): Boolean = withContext(Dispatchers.IO) {
        val body = JSONObject().put("c", link.code).put("b", bundle(context)).toString().toByteArray()
        val c = URL("http://${link.host}:${link.port}/pair").openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 8_000
            c.readTimeout = 60_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }
            c.responseCode == 200
        } finally {
            c.disconnect()
        }
    }

    /** On the TV, from the cast server: the phone's request, checked against the code on screen. */
    fun receive(body: String): Boolean {
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return false
        val handler = synchronized(this) {
            val expected = code ?: return false
            if (o.optString("c") != expected) {
                if (++misses >= 5) {
                    code = null
                    onExpired?.let { e -> android.os.Handler(android.os.Looper.getMainLooper()).post { e() } }
                }
                return false
            }
            val h = onReceived ?: return false
            code = null
            h
        }
        val bundle = o.optString("b")
        android.os.Handler(android.os.Looper.getMainLooper()).post { handler(bundle) }
        return true
    }

    /** Starts Couchy again from the beginning, with what was just saved. */
    fun restart(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        Runtime.getRuntime().exit(0)
    }

    private fun typed(v: Any?): JSONObject? = when (v) {
        is String -> JSONObject().put("t", "s").put("v", v)
        is Boolean -> JSONObject().put("t", "b").put("v", v)
        is Int -> JSONObject().put("t", "i").put("v", v)
        is Long -> JSONObject().put("t", "l").put("v", v)
        is Float -> JSONObject().put("t", "f").put("v", v.toDouble())
        is Set<*> -> JSONObject().put("t", "ss").put("v", JSONArray(v.map { it.toString() }))
        else -> null
    }

    private fun put(edit: android.content.SharedPreferences.Editor, key: String, o: JSONObject) {
        when (o.optString("t")) {
            "s" -> edit.putString(key, o.optString("v"))
            "b" -> edit.putBoolean(key, o.optBoolean("v"))
            "i" -> edit.putInt(key, o.optInt("v"))
            "l" -> edit.putLong(key, o.optLong("v"))
            "f" -> edit.putFloat(key, o.optDouble("v").toFloat())
            "ss" -> edit.putStringSet(key, o.optJSONArray("v")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet())
        }
    }
}
