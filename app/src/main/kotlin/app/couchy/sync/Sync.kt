package app.couchy.sync

import android.content.Context
import android.util.Log
import app.couchy.core.cast.CastRequest
import app.couchy.core.sync.LibrarySync
import app.couchy.core.sync.SyncItem
import app.couchy.core.sync.SyncPlaylist
import app.couchy.core.sync.SyncState
import app.couchy.core.bypass.Base64
import app.couchy.data.LibraryRepository
import app.couchy.data.MediaEntity
import app.couchy.data.Prefs
import app.couchy.data.Settings
import app.couchy.tv.PlaylistKind
import app.couchy.tv.TvRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

sealed interface SyncStatus {
    data object Off : SyncStatus
    data object Running : SyncStatus
    data class Done(val at: Long) : SyncStatus
    data class Failed(val message: String) : SyncStatus
}

/** Яндекс Диск or any WebDAV server: one small JSON file in the root. */
class WebDav(private val url: String, private val login: String, private val password: String) : SyncDisk {
    private val file = url.trimEnd('/') + "/" + FILE
    private val auth = "Basic " + Base64.encode("$login:$password".toByteArray())

    override suspend fun read(): String? = withContext(Dispatchers.IO) {
        val c = open("GET")
        try {
            when (val code = c.responseCode) {
                200 -> c.inputStream.bufferedReader().use { it.readText() }
                404 -> null
                else -> throw error(code)
            }
        } finally {
            c.disconnect()
        }
    }

    override suspend fun write(text: String) = withContext(Dispatchers.IO) {
        val c = open("PUT")
        try {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.outputStream.use { it.write(text.toByteArray()) }
            val code = c.responseCode
            if (code !in 200..299) throw error(code)
        } finally {
            c.disconnect()
        }
    }

    private fun open(method: String) = (URL(file).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = 15_000
        readTimeout = 30_000
        setRequestProperty("Authorization", auth)
        setRequestProperty("User-Agent", "Couchy/1.0 (Android)")
    }

    private fun error(code: Int) = IOException(
        when (code) {
            401, 403 -> "Нет доступа: проверьте логин и пароль приложения."
            404, 409 -> "Нет такой папки на сервере."
            507 -> "На диске закончилось место."
            else -> "Сервер ответил $code."
        },
    )

    companion object {
        const val FILE = "couchy-sync.json"
        const val YANDEX = "https://webdav.yandex.ru"
        /** Stand-ins for the address when the account is Google's or Yandex's own sign-in. */
        const val GOOGLE_ACCOUNT = "google:drive"
        const val YANDEX_ACCOUNT = "yandex:oauth"
    }
}

/**
 * Keeps history, positions, favourites, IPTV playlists and the bypass list the same on
 * every device. Each sync merges this device's state with the shared file three ways,
 * against what both agreed on last time, so deletions travel too.
 */
class Sync(
    private val context: Context,
    private val settings: Settings,
    private val library: LibraryRepository,
    private val tv: TvRepository,
    private val marks: app.couchy.data.Marks,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val baseFile get() = File(context.filesDir, "sync-base.json")

    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Off)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    /** Syncs in the background when an account is set up; quiet about failures except in [status]. */
    fun syncSoon() {
        scope.launch { runCatching { syncNow() } }
    }

    /** Checks an account with a real sync and keeps it only if that works. */
    suspend fun connect(url: String, login: String, password: String) {
        withContext(Dispatchers.IO) { baseFile.delete() }
        run(disk(url, login, password))
        settings.setSyncAccount(url, login, password)
    }

    /** After the person agreed on Google's screen. */
    suspend fun connectGoogle() = connect(WebDav.GOOGLE_ACCOUNT, "Google", "")

    /** With the token the Yandex account on the device gave. */
    suspend fun connectYandex(token: String) = connect(WebDav.YANDEX_ACCOUNT, YandexDisk(token).login(), token)

    private fun disk(url: String, login: String, password: String): SyncDisk = when (url) {
        WebDav.GOOGLE_ACCOUNT -> GoogleDrive { GoogleAuth.token(context) }
        WebDav.YANDEX_ACCOUNT -> YandexDisk(password)
        else -> WebDav(url, login, password)
    }

    suspend fun disconnect() {
        settings.setSyncAccount("", "", "")
        withContext(Dispatchers.IO) { baseFile.delete() }
        _status.value = SyncStatus.Off
    }

    suspend fun syncNow() {
        val p = settings.prefs.first()
        if (p.syncUrl.isBlank()) {
            _status.value = SyncStatus.Off
            return
        }
        run(disk(p.syncUrl, p.syncLogin, p.syncPassword))
    }

    private suspend fun run(disk: SyncDisk) = lock.withLock {
        _status.value = SyncStatus.Running
        try {
            val prefs = settings.prefs.first()
            val local = localState(prefs)
            val remoteText = disk.read()
            val remote = remoteText?.let { runCatching { SyncState.fromJson(it) }.getOrNull() } ?: SyncState()
            val base = withContext(Dispatchers.IO) { baseFile.takeIf { it.exists() }?.readText() }
                ?.let { runCatching { SyncState.fromJson(it) }.getOrNull() } ?: SyncState()
            val merged = LibrarySync.merge(base, local, remote)
            apply(local, merged)
            val text = merged.toJson()
            if (remoteText == null || merged != remote) disk.write(text)
            withContext(Dispatchers.IO) { baseFile.writeText(text) }
            _status.value = SyncStatus.Done(System.currentTimeMillis())
        } catch (e: Exception) {
            Log.w("Couchy", "Sync failed", e)
            _status.value = SyncStatus.Failed(e.message ?: "Не удалось синхронизировать.")
            throw e
        }
    }

    private suspend fun localState(prefs: Prefs): SyncState = SyncState(
        items = library.snapshot().filter { !CastRequest.isLocalOnly(it.uri) }.map { it.toSync() }.associateBy { it.key },
        // A playlist file lives on this device only; other devices could not read it.
        playlists = tv.allPlaylists().filter { !it.url.startsWith("file:") }.map {
            SyncPlaylist(it.name, it.kind, it.url, it.username, it.password, it.epgOverride)
        }.associateBy { it.key },
        bypassHosts = prefs.bypassHosts,
        marks = marks.all.value,
    )

    private suspend fun apply(local: SyncState, merged: SyncState) {
        val (write, delete) = LibrarySync.changes(local, merged)
        write.forEach { item ->
            val current = library.get(item.key)
            library.put(
                MediaEntity(
                    uri = item.key, title = item.title, kind = item.kind, addedAt = item.addedAt,
                    lastWatchedAt = item.lastWatchedAt, positionMs = item.positionMs, durationMs = item.durationMs,
                    favorite = item.favorite,
                    kids = item.kids,
                    audioLanguage = item.audioLanguage ?: current?.audioLanguage,
                    subtitleLanguage = item.subtitleLanguage ?: current?.subtitleLanguage,
                ),
            )
        }
        delete.forEach { library.delete(it) }

        val playlists = tv.allPlaylists()
        playlists.filter { p -> !p.url.startsWith("file:") && SyncPlaylist(p.name, p.kind, p.url, p.username, p.password).key !in merged.playlists }
            .forEach { tv.delete(it.id) }
        (merged.playlists.keys - local.playlists.keys).forEach { key ->
            val p = merged.playlists.getValue(key)
            runCatching {
                when (p.kind) {
                    PlaylistKind.XTREAM.name -> tv.addXtream(p.name, p.url, p.username.orEmpty(), p.password.orEmpty())
                    else -> tv.addM3u(p.name, p.url, p.epg)
                }
            }
        }
        if (merged.bypassHosts != local.bypassHosts) settings.setBypassHosts(merged.bypassHosts)
        if (merged.marks != local.marks) marks.replaceAll(merged.marks)
    }

    private fun MediaEntity.toSync() = SyncItem(
        uri, title, kind, addedAt, lastWatchedAt, positionMs, durationMs, favorite, audioLanguage, subtitleLanguage, kids,
    )
}
