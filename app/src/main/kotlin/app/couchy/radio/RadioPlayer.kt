package app.couchy.radio

import android.content.Context
import app.couchy.Playback
import app.couchy.core.radio.RadioBrowser
import app.couchy.core.radio.Station
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** The stations being flipped through, and which one is on. */
data class RadioQueue(val stations: List<Station>, val index: Int) {
    val current: Station get() = stations[index]
}

/**
 * Internet radio: the directory, the person's favourite stations and what plays now. Stations
 * play in the music player, with next and previous going through the list they came from.
 */
object RadioPlayer {
    const val NEXT = "couchy-radio-next"
    private const val KEY = "radio:"

    private val _queue = MutableStateFlow<RadioQueue?>(null)
    val queue: StateFlow<RadioQueue?> = _queue.asStateFlow()

    fun isRadio(key: String) = key.startsWith(KEY)

    /** Starts [stations] at [index]; the playback to hand to the player. */
    fun play(stations: List<Station>, index: Int): Playback {
        val q = RadioQueue(stations, index.coerceIn(0, stations.lastIndex))
        _queue.value = q
        val s = q.current
        Thread { runCatching { get(RadioBrowser.clickUrl(host ?: RadioBrowser.hosts.first(), s.uuid)) } }.start()
        return Playback(
            uri = s.url,
            title = s.name,
            startMs = 0,
            key = KEY + s.uuid,
            nextRef = if (stations.size > 1) NEXT else null,
            artwork = s.favicon,
        )
    }

    /** The station [delta] steps along the list, going round. */
    fun step(delta: Int): Playback? {
        val q = _queue.value ?: return null
        val n = q.stations.size
        return play(q.stations, ((q.index + delta) % n + n) % n)
    }

    fun pick(index: Int): Playback? = _queue.value?.let { play(it.stations, index) }

    // Favourites, kept on the device.
    private fun prefs(context: Context) = context.getSharedPreferences("radio", Context.MODE_PRIVATE)

    fun favourites(context: Context): List<Station> =
        runCatching { RadioBrowser.parse(prefs(context).getString("favourites", "[]")!!) }.getOrDefault(emptyList())

    fun toggleFavourite(context: Context, s: Station): Boolean {
        val now = favourites(context)
        val on = now.none { it.uuid == s.uuid }
        val next = if (on) now + s else now.filter { it.uuid != s.uuid }
        prefs(context).edit().putString("favourites", RadioBrowser.toJson(next)).apply()
        return on
    }

    /** The mirror that answered last, asked first next time. */
    @Volatile private var host: String? = null

    /** Stations from the directory; [url] builds the address for a mirror. */
    suspend fun load(url: (String) -> String): List<Station> = withContext(Dispatchers.IO) {
        val order = listOfNotNull(host) + RadioBrowser.hosts.filter { it != host }
        var last: Throwable? = null
        for (h in order) {
            val body = runCatching { get(url(h)) }.onFailure { last = it }.getOrNull() ?: continue
            host = h
            return@withContext RadioBrowser.parse(body)
        }
        throw last ?: java.io.IOException("Каталог радио не ответил")
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8_000
        c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", "Couchy/1.0")
        try {
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}
