package app.couchy.together

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import app.couchy.CouchyApp
import app.couchy.cast.RemoteBridge
import app.couchy.core.cast.CastRequest
import app.couchy.core.cast.RemoteAction
import app.couchy.core.together.LocalPlay
import app.couchy.core.together.Together
import app.couchy.core.together.TogetherMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class ChatLine(val name: String, val text: String, val mine: Boolean)

data class RoomUi(
    val code: String? = null,
    val connected: Boolean = false,
    /** Others in the room by device id, with their names. */
    val people: Map<String, String> = emptyMap(),
    val chat: List<ChatLine> = emptyList(),
)

/**
 * The room this device is in, if any. It listens to the relay, follows what friends play, and
 * tells them when the person here plays, pauses, jumps or starts something else.
 */
object Room {
    private val _ui = MutableStateFlow(RoomUi())
    val ui: StateFlow<RoomUi> = _ui.asStateFlow()

    /** Asks the main screen to show the room sheet: from Home or an invitation link. */
    val sheetRequest = MutableStateFlow(false)

    /** Set while the room sheet is open: chat shows there instead of as a toast. */
    @Volatile var sheetOpen = false

    private val me = java.util.UUID.randomUUID().toString().take(8)
    private var jobs = emptyList<Job>()
    private var last: LocalPlay? = null
    /** Until then a change here is the echo of following a friend, not the person's own. */
    @Volatile private var quietUntil = 0L

    fun join(context: Context, code: String) {
        val app = context.applicationContext as CouchyApp
        if (_ui.value.code == code) return
        leave(context)
        _ui.value = RoomUi(code = code)
        last = null
        val name = app.cast.deviceName
        jobs = listOf(
            app.scope.launch { listen(app, code) },
            app.scope.launch { watchHere(code) },
            app.scope.launch { delay(1_500); publish(code, TogetherMessage.Hello(me, name)) },
        )
    }

    fun leave(context: Context) {
        val code = _ui.value.code ?: return
        val app = context.applicationContext as CouchyApp
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        _ui.value = RoomUi()
        app.scope.launch { publish(code, TogetherMessage.Bye(me, app.cast.deviceName)) }
    }

    fun say(context: Context, text: String) {
        val code = _ui.value.code ?: return
        val t = text.trim().ifEmpty { return }
        val app = context.applicationContext as CouchyApp
        _ui.update { it.copy(chat = (it.chat + ChatLine("Вы", t, true)).takeLast(50)) }
        app.scope.launch { publish(code, TogetherMessage.Chat(me, app.cast.deviceName, t)) }
    }

    /** The relay's stream of the room's messages; it reconnects after a drop. */
    private suspend fun listen(app: CouchyApp, code: String) = withContext(Dispatchers.IO) {
        while (isActive) {
            runCatching {
                val c = URL("${Together.RELAY}/${Together.topic(code)}/json").openConnection() as HttpURLConnection
                c.connectTimeout = 10_000
                c.readTimeout = 90_000
                try {
                    _ui.update { it.copy(connected = true) }
                    c.inputStream.bufferedReader().useLines { lines ->
                        for (line in lines) {
                            if (!isActive) break
                            val o = runCatching { JSONObject(line) }.getOrNull() ?: continue
                            if (o.optString("event") != "message") continue
                            val m = TogetherMessage.decode(o.optString("message")) ?: continue
                            if (m.from != me) Handler(Looper.getMainLooper()).post { receive(app, code, m) }
                        }
                    }
                } finally {
                    c.disconnect()
                }
            }
            _ui.update { it.copy(connected = false) }
            delay(3_000)
        }
    }

    private fun receive(app: CouchyApp, code: String, m: TogetherMessage) {
        if (_ui.value.code != code) return
        when (m) {
            is TogetherMessage.Hello -> {
                _ui.update { it.copy(people = it.people + (m.from to m.name)) }
                toast(app, "${m.name} в комнате")
                // The newcomer catches up with what plays here.
                here()?.let { p -> app.scope.launch { publish(code, TogetherMessage.State(me, p.key, p.title, p.positionMs, p.playing)) } }
            }
            is TogetherMessage.Bye -> {
                _ui.update { it.copy(people = it.people - m.from) }
                toast(app, "${m.name} вышел")
            }
            is TogetherMessage.Chat -> {
                _ui.update { it.copy(people = it.people + (m.from to m.name), chat = (it.chat + ChatLine(m.name, m.text, false)).takeLast(50)) }
                if (!sheetOpen) toast(app, "${m.name}: ${m.text}")
            }
            is TogetherMessage.State -> {
                if (m.from !in _ui.value.people) _ui.update { it.copy(people = it.people + (m.from to "Друг")) }
                quietUntil = System.currentTimeMillis() + 3_000
                for (f in Together.follow(here(), m)) when (f) {
                    is Together.Follow.Open -> app.cast.deliver(CastRequest(f.key, f.title, f.positionMs, from = _ui.value.people[m.from].orEmpty()))
                    is Together.Follow.Seek -> RemoteBridge.handler?.invoke(RemoteAction.SEEK_TO, f.positionMs)
                    is Together.Follow.Play -> RemoteBridge.handler?.invoke(if (f.playing) RemoteAction.PLAY else RemoteAction.PAUSE, 0)
                }
            }
        }
    }

    /** Every half second: did the person here do something the friends should follow? */
    private suspend fun watchHere(code: String) {
        while (true) {
            delay(500)
            val now = here()
            val before = last
            last = now
            if (now == null || System.currentTimeMillis() < quietUntil) continue
            if (before == null || Together.changed(before, now)) {
                publish(code, TogetherMessage.State(me, now.key, now.title, now.positionMs, now.playing))
            }
        }
    }

    private fun here(): LocalPlay? = RemoteBridge.state?.takeIf { it.key.isNotEmpty() }?.let {
        LocalPlay(it.key, it.title, it.positionMs, it.playing, System.currentTimeMillis())
    }

    private suspend fun publish(code: String, m: TogetherMessage) = withContext(Dispatchers.IO) {
        runCatching {
            val body = m.encode().toByteArray()
            val c = URL("${Together.RELAY}/${Together.topic(code)}").openConnection() as HttpURLConnection
            try {
                c.requestMethod = "POST"
                c.doOutput = true
                c.connectTimeout = 10_000
                c.readTimeout = 10_000
                // Not kept on the relay: someone who comes later asks for the state instead.
                c.setRequestProperty("Cache", "no")
                c.setRequestProperty("Firebase", "no")
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
                c.responseCode
            } finally {
                c.disconnect()
            }
        }
    }

    private fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}
