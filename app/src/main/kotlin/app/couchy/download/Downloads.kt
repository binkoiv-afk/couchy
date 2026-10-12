package app.couchy.download

import android.app.Notification
import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import app.couchy.CouchyApp
import app.couchy.Playback
import app.couchy.R
import app.couchy.Skip
import app.couchy.core.LinkKind
import app.couchy.core.Links
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** A video saved on the device: what to play and how far the download is. */
data class Saved(val playback: Playback, val percent: Float, val bytes: Long, val state: Int) {
    val done: Boolean get() = state == Download.STATE_COMPLETED
    val failed: Boolean get() = state == Download.STATE_FAILED
}

/**
 * Films, episodes and YouTube videos kept on the device for watching without the internet.
 * Media3 saves the stream (a file or every HLS piece) into its own store; the player reads from
 * that store first, so a saved video plays offline with the same address it was saved under.
 */
@OptIn(UnstableApi::class)
class Downloads(private val context: Context) {
    private val prefs = context.getSharedPreferences("downloads", Context.MODE_PRIVATE)
    private val database by lazy { StandaloneDatabaseProvider(context) }
    val cache: SimpleCache by lazy { SimpleCache(File(context.getExternalFilesDir(null) ?: context.filesDir, "downloads"), NoOpCacheEvictor(), database) }

    /** Headers some sources need (Referer, cookies), by host, remembered with each download. */
    private fun headersFor(uri: Uri): Map<String, String> {
        val all = runCatching { JSONObject(prefs.getString("headers", "{}")!!) }.getOrDefault(JSONObject())
        val o = all.optJSONObject(uri.host ?: return emptyMap()) ?: return emptyMap()
        return o.keys().asSequence().associateWith { o.getString(it) }
    }

    private fun rememberHeaders(uri: String, headers: Map<String, String>) {
        val host = Uri.parse(uri).host ?: return
        if (headers.isEmpty()) return
        val all = runCatching { JSONObject(prefs.getString("headers", "{}")!!) }.getOrDefault(JSONObject())
        all.put(host, JSONObject().apply { headers.forEach { (k, v) -> put(k, v) } })
        prefs.edit().putString("headers", all.toString()).apply()
    }

    private val upstream: DataSource.Factory by lazy {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("Couchy (Android)")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
        ResolvingDataSource.Factory(DefaultDataSource.Factory(context, http)) { spec ->
            val extra = headersFor(spec.uri)
            if (extra.isEmpty()) spec else spec.withAdditionalHeaders(extra)
        }
    }

    val manager: DownloadManager by lazy {
        DownloadManager(context, database, cache, upstream, Executors.newFixedThreadPool(3)).apply {
            maxParallelDownloads = 2
            addListener(object : DownloadManager.Listener {
                override fun onDownloadChanged(manager: DownloadManager, download: Download, finalException: Exception?) = refresh()
                override fun onDownloadRemoved(manager: DownloadManager, download: Download) = refresh()
            })
        }
    }

    private val _items = MutableStateFlow<List<Saved>>(emptyList())
    val items: StateFlow<List<Saved>> = _items.asStateFlow()

    /** For the player: a source reads the saved copy first and the network for the rest. */
    fun readThrough(network: DataSource.Factory): DataSource.Factory =
        CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(network).setCacheWriteDataSinkFactory(null)

    fun start(p: Playback) {
        val clean = p.copy(startMs = 0, subtitles = p.subtitles.filter { it.second.startsWith("http") })
        rememberHeaders(p.uri, p.headers)
        p.audioUri?.let { rememberHeaders(it, p.headers) }
        prefs.edit().putString("item|${p.key}", encode(clean)).apply()
        send(request(p.key, p.uri))
        p.audioUri?.let { send(request(p.key + AUDIO, it)) }
        refresh()
    }

    fun remove(key: String) {
        prefs.edit().remove("item|$key").apply()
        DownloadService.sendRemoveDownload(context, DownloadsService::class.java, key, false)
        DownloadService.sendRemoveDownload(context, DownloadsService::class.java, key + AUDIO, false)
        refresh()
    }

    /** The saved video for a library key, once it is complete. */
    fun ready(key: String): Playback? = items.value.firstOrNull { it.playback.key == key && it.done }?.playback
        ?: prefs.getString("item|$key", null)?.let(::decode)?.takeIf { p ->
            manager.downloadIndex.getDownload(p.key)?.state == Download.STATE_COMPLETED
        }

    fun has(key: String): Boolean = prefs.contains("item|$key")

    /** Reads progress again; the screen calls it every second while it shows downloads. */
    fun refresh() {
        val saved = prefs.all.filterKeys { it.startsWith("item|") }.values.mapNotNull { (it as? String)?.let(::decode) }
        _items.value = saved.map { p ->
            val parts = listOfNotNull(manager.downloadIndex.getDownload(p.key), p.audioUri?.let { manager.downloadIndex.getDownload(p.key + AUDIO) })
            val state = when {
                parts.isEmpty() -> Download.STATE_QUEUED
                parts.any { it.state == Download.STATE_FAILED } -> Download.STATE_FAILED
                parts.all { it.state == Download.STATE_COMPLETED } -> Download.STATE_COMPLETED
                parts.any { it.state == Download.STATE_DOWNLOADING } -> Download.STATE_DOWNLOADING
                else -> parts.first().state
            }
            val live = manager.currentDownloads.filter { it.request.id == p.key || it.request.id == p.key + AUDIO }
            val percent = (live.ifEmpty { parts }).map { it.percentDownloaded.coerceAtLeast(0f) }.average().toFloat().takeIf { !it.isNaN() } ?: 0f
            Saved(p, if (state == Download.STATE_COMPLETED) 100f else percent, parts.sumOf { it.bytesDownloaded }, state)
        }
    }

    private fun send(r: DownloadRequest) = DownloadService.sendAddDownload(context, DownloadsService::class.java, r, false)

    private fun request(id: String, uri: String): DownloadRequest = DownloadRequest.Builder(id, Uri.parse(uri)).apply {
        when (Links.classify(uri)) {
            LinkKind.HLS -> setMimeType(MimeTypes.APPLICATION_M3U8)
            LinkKind.DASH -> setMimeType(MimeTypes.APPLICATION_MPD)
            else -> Unit
        }
    }.build()

    private fun encode(p: Playback): String = JSONObject()
        .put("uri", p.uri).put("title", p.title).put("key", p.key).put("audio", p.audioUri ?: "").put("imdb", p.imdb ?: "").put("next", p.nextRef ?: "")
        .put("headers", JSONObject().apply { p.headers.forEach { (k, v) -> put(k, v) } })
        .put("subs", JSONArray().apply { p.subtitles.forEach { (l, u) -> put(JSONObject().put("l", l).put("u", u)) } })
        .put("skips", JSONArray().apply { p.skips.forEach { s -> put(JSONObject().put("s", s.startMs).put("e", s.endMs).put("l", s.label)) } })
        .put("variants", JSONArray().apply { p.variants.forEach { (l, u) -> put(JSONObject().put("l", l).put("u", u)) } })
        .put("at", System.currentTimeMillis())
        .toString()

    private fun decode(s: String): Playback? = runCatching {
        val o = JSONObject(s)
        val h = o.optJSONObject("headers") ?: JSONObject()
        val subs = o.optJSONArray("subs") ?: JSONArray()
        val skips = o.optJSONArray("skips") ?: JSONArray()
        Playback(
            uri = o.getString("uri"),
            title = o.optString("title"),
            startMs = 0,
            key = o.getString("key"),
            headers = h.keys().asSequence().associateWith { h.getString(it) },
            audioUri = o.optString("audio").ifBlank { null },
            imdb = o.optString("imdb").ifBlank { null },
            nextRef = o.optString("next").ifBlank { null },
            subtitles = (0 until subs.length()).map { subs.getJSONObject(it).let { x -> x.getString("l") to x.getString("u") } },
            skips = (0 until skips.length()).map { skips.getJSONObject(it).let { x -> Skip(x.getLong("s"), x.getLong("e"), x.getString("l")) } },
        )
    }.getOrNull()

    companion object {
        const val AUDIO = "#audio"
        const val CHANNEL = "downloads"
    }
}

/** Keeps downloads going with the app closed, with a progress notification. */
@OptIn(UnstableApi::class)
class DownloadsService : DownloadService(
    7301,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    Downloads.CHANNEL,
    R.string.downloads_channel,
    0,
) {
    private val helper by lazy { DownloadNotificationHelper(this, Downloads.CHANNEL) }

    override fun getDownloadManager(): DownloadManager = (application as CouchyApp).downloads.manager

    override fun getScheduler(): Scheduler? = null

    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: Int): Notification =
        helper.buildProgressNotification(this, R.drawable.ic_notification, null, "Couchy скачивает видео", downloads, notMetRequirements)
}
