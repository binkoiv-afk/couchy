package app.couchy.torrent

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.couchy.CouchyApp
import app.couchy.Playback
import app.couchy.TorrentPlay
import app.couchy.core.Progress
import app.couchy.core.torrent.Magnet
import app.couchy.core.torrent.TorrFile
import app.couchy.core.torrent.TorrFiles
import app.couchy.core.torrent.TorrentRef
import app.couchy.data.MediaEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException

enum class TorrentPhase { CONNECTING, METADATA, READY, ERROR }

/** A file of the open torrent with what the library knows about it. */
data class TorrentFileUi(
    val file: TorrFile,
    val label: String,
    val key: String,
    val progress: Float,
    val watched: Boolean,
    val lastWatchedAt: Long,
)

data class TorrentState(
    val link: String,
    val title: String,
    val phase: TorrentPhase = TorrentPhase.CONNECTING,
    val error: String? = null,
    val hash: String? = null,
    val size: Long = 0,
    val files: List<TorrentFileUi> = emptyList(),
    /** The episode to continue with: the last one started, or the one after the last finished. */
    val resume: TorrentFileUi? = null,
    val peers: Int = 0,
    val activePeers: Int = 0,
    val seeders: Int = 0,
    val speed: Double = 0.0,
    val startedAt: Long = System.currentTimeMillis(),
    val remote: Boolean = false,
)

private data class Session(
    val link: String,
    val title: String,
    val phase: TorrentPhase = TorrentPhase.CONNECTING,
    val error: String? = null,
    val status: TorrStatus? = null,
    val startedAt: Long = System.currentTimeMillis(),
    val remote: Boolean = false,
)

/**
 * One open torrent at a time: adds it to the engine, waits for its file list, lists the
 * episodes with their progress, and turns a pick into a playback of the engine's stream.
 */
class TorrentViewModel(app: Application) : AndroidViewModel(app) {
    private val couchy = app as CouchyApp
    private val library = couchy.library
    val engine = couchy.torrEngine

    private val session = MutableStateFlow<Session?>(null)
    private var server: TorrServer? = null
    private var poll: Job? = null

    /** Set when a file should start by itself once the list arrives (opened from the library). */
    private val _autoPlay = MutableStateFlow<Playback?>(null)
    val autoPlay: StateFlow<Playback?> = _autoPlay.asStateFlow()
    private var autoFile: Int? = null

    val state: StateFlow<TorrentState?> = combine(session, library.all) { s, all ->
        s?.let { build(it, all) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun open(ref: String) {
        val (link, file) = TorrentRef.parse(ref.trim())
        autoFile = file
        val title = if (link.startsWith("content://")) displayName(Uri.parse(link)) ?: "Торрент" else app.couchy.core.Links.titleFor(link)
        session.value = Session(link, title)
        start()
    }

    fun retry() {
        val s = session.value ?: return
        session.value = Session(s.link, s.title)
        start()
    }

    fun close() {
        poll?.cancel()
        session.value = null
        autoFile = null
    }

    fun autoPlayHandled() { _autoPlay.value = null }

    private fun start() {
        poll?.cancel()
        poll = viewModelScope.launch {
            val opened = session.value ?: return@launch
            try {
                val prefs = couchy.settings.prefs.first()
                val torr = engine.server(prefs).also { server = it }
                val remote = torr.base != TorrEngine.LOCAL
                session.value = opened.copy(phase = TorrentPhase.METADATA, remote = remote)
                var status = if (opened.link.startsWith("content://")) {
                    val uri = Uri.parse(opened.link)
                    val input = getApplication<Application>().contentResolver.openInputStream(uri) ?: throw IOException("Не удалось прочитать файл")
                    input.use { torr.upload(opened.title, it) }
                } else {
                    torr.add(opened.link, opened.title)
                }
                // A local file is remembered by its hash, so the library can reopen it later.
                val link = if (opened.link.startsWith("content://") && status.hash.isNotBlank()) Magnet.fromHash(status.hash, status.name) else opened.link
                session.value = session.value?.copy(link = link, status = status)
                while (true) {
                    delay(if (status.files.isEmpty()) 1_000 else 2_000)
                    status = try { torr.get(status.hash) } catch (e: IOException) { status }
                    val current = session.value ?: break
                    val ready = status.files.isNotEmpty()
                    session.value = current.copy(status = status, phase = if (ready) TorrentPhase.READY else TorrentPhase.METADATA)
                    if (ready) autoFile?.let { id ->
                        autoFile = null
                        status.files.firstOrNull { it.id == id }?.let { f -> _autoPlay.value = prepare(f) }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                session.value = session.value?.copy(phase = TorrentPhase.ERROR, error = e.message ?: "Не удалось открыть торрент")
            }
        }
    }

    /** A playback for [file], resuming where the library says it stopped. */
    suspend fun prepare(file: TorrFile, fromStart: Boolean = false): Playback? {
        val s = session.value ?: return null
        val status = s.status ?: return null
        val torr = server ?: return null
        val single = TorrFiles.playable(status.files).size <= 1
        val key = TorrentRef.of(s.link, file.id)
        val entry = library.open(key, TorrFiles.title(status.name.ifBlank { s.title }, file, single))
        if (fromStart) library.clearProgress(key)
        return Playback(
            uri = torr.streamUrl(status.hash, file),
            title = entry.title,
            startMs = if (fromStart) 0 else Progress.resumeFrom(entry.positionMs, entry.durationMs),
            audioLanguage = entry.audioLanguage,
            subtitleLanguage = entry.subtitleLanguage,
            key = key,
            torrent = TorrentPlay(status.hash, file.id, TorrFiles.next(status.files, file.id)?.id),
        )
    }

    suspend fun prepare(fileId: Int): Playback? =
        session.value?.status?.files?.firstOrNull { it.id == fileId }?.let { prepare(it) }

    /** "12 пиров · 3,4 МБ/с" while a torrent plays, for the player's buffering line. */
    fun statusLine(s: TorrentState?): String? {
        if (s == null || s.phase != TorrentPhase.READY) return null
        return "${s.activePeers} из ${s.peers} пиров · ${app.couchy.core.torrent.Sizes.speed(s.speed)}"
    }

    private fun build(s: Session, all: List<MediaEntity>): TorrentState {
        val status = s.status
        val playable = status?.files?.let { TorrFiles.playable(it) }.orEmpty()
        val byUri = all.associateBy { it.uri }
        val files = playable.map { f ->
            val key = TorrentRef.of(s.link, f.id)
            val e = byUri[key]
            TorrentFileUi(
                file = f,
                label = TorrFiles.label(f, playable.size <= 1),
                key = key,
                progress = e?.let { Progress.fraction(it.positionMs, it.durationMs) } ?: 0f,
                watched = e?.let { Progress.isWatched(it.positionMs, it.durationMs) } ?: false,
                lastWatchedAt = e?.lastWatchedAt ?: 0,
            )
        }
        val last = files.filter { it.lastWatchedAt > 0 }.maxByOrNull { it.lastWatchedAt }
        val resume = when {
            last == null -> null
            !last.watched -> last
            else -> files.getOrNull(files.indexOf(last) + 1)
        }
        return TorrentState(
            link = s.link,
            title = status?.name?.takeIf { it.isNotBlank() } ?: s.title,
            phase = s.phase,
            error = s.error,
            hash = status?.hash,
            size = status?.size ?: 0,
            files = files,
            resume = resume,
            peers = status?.peers ?: 0,
            activePeers = status?.activePeers ?: 0,
            seeders = status?.seeders ?: 0,
            speed = status?.speed ?: 0.0,
            startedAt = s.startedAt,
            remote = s.remote,
        )
    }

    private fun displayName(uri: Uri): String? = try {
        getApplication<Application>().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.removeSuffix(".torrent") else null
        }
    } catch (e: Exception) {
        null
    }
}
