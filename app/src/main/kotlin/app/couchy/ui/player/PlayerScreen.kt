package app.couchy.ui.player

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.draw.blur
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.NightsStay
import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.couchy.BuildConfig
import app.couchy.Playback
import app.couchy.core.LinkKind
import app.couchy.core.Links
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.math.abs

private val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

private enum class Resize(val label: String, val mode: Int) {
    FIT("Вписать", AspectRatioFrameLayout.RESIZE_MODE_FIT),
    ZOOM("Заполнить", AspectRatioFrameLayout.RESIZE_MODE_ZOOM),
    STRETCH("Растянуть", AspectRatioFrameLayout.RESIZE_MODE_FILL),
}

private enum class Sheet { TRACKS, QUALITY, SPEED, SLEEP, INTRO, FIND_SUBS, MORE, QUEUE, EPISODES, CHANNELS, SYNC, SUBSTYLE }

/** One channel in the player's «Каналы» list. */
data class LiveChoice(val id: Long, val number: Int, val name: String, val now: String?)

/** One episode in the player's «Серии» list. */
data class EpisodeItem(val season: Int, val episode: Int, val name: String, val current: Boolean, val play: () -> Unit)

private val sleepMinutes = listOf(15, 30, 45, 60, 90)

/**
 * Full-screen playback on Media3. Saves the position every few seconds and on exit, remembers
 * the audio and subtitle language per title, and works the same with a remote or a finger.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    playback: Playback,
    touch: Boolean,
    onProgress: (positionMs: Long, durationMs: Long) -> Unit,
    onTracksChosen: (audio: String?, subtitles: String?) -> Unit,
    onExit: () -> Unit,
    /** Programme info for a live channel; null for videos and archive recordings. */
    live: LiveUi? = null,
    /** Switches to the neighbouring channel; null when there is nothing to zap through. */
    onZap: ((Int) -> Unit)? = null,
    /** TV groups for the «Каналы» list: names, the one the channel came from, and the channels of a group. */
    liveGroups: List<String> = emptyList(),
    liveGroup: String? = null,
    liveChannels: (String) -> List<LiveChoice> = { emptyList() },
    /** A channel picked in the list, with the group it was picked from. */
    onLivePick: ((Long, String) -> Unit)? = null,
    /** Torrent peers and speed for the buffering spinner. */
    status: String? = null,
    /** Called when the video plays to the end; by default the player closes. */
    onFinished: () -> Unit = onExit,
    /** "On TV": gets the current position and length; null when this video cannot be sent. */
    onCast: ((positionMs: Long, durationMs: Long) -> Unit)? = null,
    /** Another quality of the same video, to continue from the given position. */
    onVariant: ((url: String, positionMs: Long) -> Unit)? = null,
    /** Set when [onFinished] starts the next episode: a countdown card shows over the credits. */
    hasNext: Boolean = false,
    /** The video would not start: another source is tried instead of showing the error; false when there is none. */
    onUnplayable: (() -> Boolean)? = null,
    /** The series' episodes, to jump to any of them without leaving the player. */
    episodes: List<EpisodeItem> = emptyList(),
    /** Stars or unstars the live channel. */
    onFavorite: (() -> Unit)? = null,
    /** Back to the channel watched before; also the remote's "last channel" key. */
    onPrevious: (() -> Unit)? = null,
    /** A channel number typed on the remote. */
    onNumber: ((Int) -> Unit)? = null,
    /** Subtitles found on the internet: label, local file, language, and where to go on from. */
    onSubtitle: ((label: String, uri: String, language: String, positionMs: Long) -> Unit)? = null,
    /** Saves the video on the device; null where it cannot be saved. */
    onDownload: (() -> Unit)? = null,
    /** Another of the site's voice-overs (Playback.voices), from the given position. */
    onVoice: ((id: Int, positionMs: Long) -> Unit)? = null,
    /** Back to the title's sources, to pick another one; null when there is no title page behind. */
    onSources: (() -> Unit)? = null,
    /** Hands the video to another player app from the given position; null where it cannot. */
    onExternal: ((positionMs: Long) -> Unit)? = null,
    /** «Ранее в сериале»: the episode before, after a break; null when not wanted. */
    recap: (suspend () -> app.couchy.core.sources.EpisodeNote?)? = null,
    /** Small in a corner over the app, still playing (YouTube after Back). */
    mini: Boolean = false,
    /** Back shrinks the player into the corner instead of closing it. */
    onMinimize: (() -> Unit)? = null,
    onExpand: () -> Unit = {},
    /** Saves the sound alone, split into tracks by the video's chapters (YouTube). */
    onSaveAudio: (() -> Unit)? = null,
    /** Music: the songs in a row (artist — title), which one plays, and a jump to another. */
    queue: List<String> = emptyList(),
    queueAt: Int = 0,
    onQueuePick: ((Int) -> Unit)? = null,
    /** YouTube Music: a music player instead of a video, its controls in the notification shade too. */
    music: Boolean = false,
    /** Internet radio: the song comes from the stream, next and previous go round the stations. */
    radio: Boolean = false,
    /** The album or playlist the songs come from, shown over the cover. */
    musicAlbum: String? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // Sound moved against the picture, as set for this device.
    val audioShift = remember(playback.uri, playback.subtitles) { AudioShift().also { it.shiftMs = PlayerTuning.audioShift(context) } }
    val player = remember(playback.uri, playback.subtitles) { buildPlayer(context, playback, audioShift) }
    MatchFrameRate(player, app.couchy.ui.components.LocalFormFactor.current.isTv && playback.live == null && PlayerTuning.frameRate(context))

    // The system knows what plays: "Окей Гугл, пауза", the Now Playing card, TV and Bluetooth media keys.
    val nextNow by androidx.compose.runtime.rememberUpdatedState(if (hasNext && playback.live == null) ({ onFinished() }) else null)
    val previousNow by androidx.compose.runtime.rememberUpdatedState(
        when {
            radio && queue.size > 1 && onQueuePick != null -> ({ onQueuePick((queueAt - 1 + queue.size) % queue.size) })
            music && queueAt > 0 && onQueuePick != null -> ({ onQueuePick(queueAt - 1) })
            else -> null
        },
    )
    // What a radio station says is playing (ICY), as "Artist — Title".
    var onAirSong by remember(playback.key) { mutableStateOf<String?>(null) }
    androidx.compose.runtime.DisposableEffect(player, radio) {
        if (!radio) return@DisposableEffect onDispose { }
        val listener = object : Player.Listener {
            override fun onMetadata(metadata: androidx.media3.common.Metadata) {
                for (i in 0 until metadata.length()) {
                    (metadata[i] as? androidx.media3.extractor.metadata.icy.IcyInfo)?.let { onAirSong = app.couchy.core.radio.RadioBrowser.songLine(it.title) }
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    val radioSong by androidx.compose.runtime.rememberUpdatedState(onAirSong)
    androidx.compose.runtime.DisposableEffect(player) {
        val shown = object : androidx.media3.common.ForwardingPlayer(player) {
            private fun canNext() = nextNow != null
            private fun canPrevious() = previousNow != null
            override fun getAvailableCommands(): androidx.media3.common.Player.Commands =
                super.getAvailableCommands().buildUpon().apply {
                    if (canNext()) addAll(androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT, androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    if (canPrevious()) addAll(androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS, androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                }.build()
            override fun isCommandAvailable(command: Int): Boolean =
                (canNext() && (command == androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT || command == androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)) ||
                    (canPrevious() && (command == androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS || command == androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)) ||
                    super.isCommandAvailable(command)
            override fun seekToNext() { nextNow?.invoke() ?: super.seekToNext() }
            override fun seekToNextMediaItem() { nextNow?.invoke() ?: super.seekToNextMediaItem() }
            // A song past its first seconds starts over, as in any music app; earlier, the one before.
            override fun seekToPrevious() { if (!radio && player.currentPosition > 3_000 || previousNow == null) player.seekTo(0) else previousNow?.invoke() }
            override fun seekToPreviousMediaItem() { previousNow?.invoke() ?: super.seekToPreviousMediaItem() }
            override fun getMediaMetadata(): androidx.media3.common.MediaMetadata =
                super.getMediaMetadata().buildUpon()
                    .setTitle(playback.title)
                    .apply { playback.artwork?.let { setArtworkUri(android.net.Uri.parse(it)) } }
                    .apply { if (music) songParts(radioSong ?: playback.title).let { (t, a) -> setTitle(t); setArtist(a.ifBlank { null }); setAlbumTitle(if (radio) playback.title else musicAlbum) } }
                    .build()
        }
        val session = runCatching {
            androidx.media3.session.MediaSession.Builder(context, shown).setId("couchy-${System.nanoTime()}").build()
        }.getOrNull()
        // Music sits in the notification shade from the first song, not only once Couchy is left.
        session?.let { BackgroundAudio.session = it }
        if (music) BackgroundAudio.start(context, player, playback.title, session)
        onDispose {
            if (BackgroundAudio.session === session) BackgroundAudio.session = null
            session?.release()
        }
    }
    val fallback by androidx.compose.runtime.rememberUpdatedState(onUnplayable)
    val variantNow by androidx.compose.runtime.rememberUpdatedState(onVariant)
    var ui by remember(playback.uri) { mutableStateOf(PlayerUi(playback.title)) }
    var tracks by remember(playback.uri) { mutableStateOf(Tracks.EMPTY) }
    var overlay by remember { mutableStateOf(true) }
    var seekingByKey by remember { mutableStateOf(false) }
    val thumbs = remember(playback.uri) {
        if (playback.live == null && Thumbs.supported(playback.uri)) Thumbs(context, playback.uri, playback.headers) else null
    }
    DisposableEffect(thumbs) { onDispose { thumbs?.release() } }
    var lastTouch by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var speed by remember { mutableFloatStateOf(1f) }
    var resize by remember { mutableIntStateOf(0) }
    val rootFocus = remember { FocusRequester() }
    var hint by remember { mutableStateOf<String?>(null) }
    var hintAt by remember { mutableLongStateOf(0L) }
    var typed by remember { mutableStateOf("") }
    var boosting by remember { mutableStateOf(false) }
    // The sleep timer outlives the episode, so it is not keyed to the video.
    var sleepAt by remember { mutableStateOf<Long?>(null) }
    var sleepAfterEpisode by remember { mutableStateOf(false) }
    var nextCancelled by remember(playback.uri) { mutableStateOf(false) }
    val skipped = remember(playback.key) { mutableSetOf<app.couchy.Skip>() }
    val pip by PlayerWindow.pip.collectAsState()
    // YouTube goes on in the background, like YouTube Premium.
    val background = playback.live == null && (music || playback.audioUri != null || app.couchy.core.youtube.YouTubeLinks.isVideo(playback.key))
    // Closing the music takes it out of the shade.
    DisposableEffect(music) { onDispose { if (music) BackgroundAudio.stop(context) } }
    // The series' opening titles, marked once and jumped over in every episode after.
    val marks = (context.applicationContext as app.couchy.CouchyApp).marks
    val stats = (context.applicationContext as app.couchy.CouchyApp).stats
    var night by remember { mutableStateOf(NightSound.isOn(context)) }
    DisposableEffect(player, night) {
        val effect = if (night) NightSound.attach(player.audioSessionId) else null
        onDispose { effect?.close() }
    }
    val series = remember(playback.key) { app.couchy.data.Intros.seriesOf(playback) }
    var intro by remember(series) { mutableStateOf(series?.let { app.couchy.data.Intros.get(marks, it) }) }
    var introFrom by remember(playback.key) { mutableStateOf<Long?>(null) }
    var introSkipped by remember(playback.key) { mutableStateOf(false) }
    // Subtitles looked up on the internet for this video.
    var findSubs by remember { mutableStateOf(false) }
    var subHits by remember(playback.key) { mutableStateOf<List<app.couchy.core.sources.SubHit>?>(null) }
    var subError by remember(playback.key) { mutableStateOf<String?>(null) }
    val subScope = rememberCoroutineScope()
    // A second line of subtitles on top (say English over Russian), drawn by Couchy from a file.
    var second by remember(playback.key) { mutableStateOf<Pair<String, List<app.couchy.core.sources.Cue>>?>(null) }
    var secondWanted by remember { mutableStateOf<String?>(null) }
    var secondText by remember(playback.key) { mutableStateOf<String?>(null) }
    var findSecond by remember { mutableStateOf(false) }
    LaunchedEffect(findSubs) {
        if (!findSubs) return@LaunchedEffect
        findSubs = false
        sheet = Sheet.FIND_SUBS
        if (subHits.isNullOrEmpty()) {
            subError = null
            subHits = null
            subHits = runCatching { app.couchy.sources.SubtitleFinder.find(playback) }
                .getOrElse { subError = "OpenSubtitles не ответил, попробуйте позже"; emptyList() }
        }
    }

    fun flash(text: String) { hint = text; hintAt = System.currentTimeMillis() }
    var recapNote by remember(playback.key) { mutableStateOf<app.couchy.core.sources.EpisodeNote?>(null) }
    LaunchedEffect(playback.key) {
        val note = recap?.invoke() ?: return@LaunchedEffect
        recapNote = note
        player.pause()
    }
    fun loadSecond(label: String, uri: String) {
        subScope.launch {
            runCatching { app.couchy.sources.SubtitleFinder.cues(context, uri, playback.headers) }
                .onSuccess { cues ->
                    if (cues.isEmpty()) flash("В файле нет субтитров") else { second = label to cues; secondWanted = label; flash("Вторые субтитры: $label") }
                }
                .onFailure { flash("Не удалось загрузить субтитры") }
        }
    }
    // The next episode keeps the second line in the same language when the site has it.
    LaunchedEffect(playback.key) {
        secondWanted?.let { w -> playback.subtitles.firstOrNull { it.first == w }?.let { (label, url) -> loadSecond(label, url) } }
    }
    LaunchedEffect(second) {
        val cues = second?.second ?: run { secondText = null; return@LaunchedEffect }
        while (true) {
            secondText = app.couchy.core.sources.SubCues.at(cues, player.currentPosition)
            delay(150)
        }
    }
    // Subtitles are drawn by Couchy: in the person's style, and moved in time when asked.
    var subSize by remember { mutableStateOf(PlayerTuning.subSize(context)) }
    var subBack by remember { mutableStateOf(PlayerTuning.subBack(context)) }
    var subPlace by remember { mutableStateOf(PlayerTuning.subPlace(context)) }
    var audioMs by remember { mutableStateOf(PlayerTuning.audioShift(context)) }
    var subMs by remember(playback.key) { mutableStateOf(0L) }
    var subCues by remember(player) { mutableStateOf<List<androidx.media3.common.text.Cue>>(emptyList()) }
    // What the player said to show and when (by position), for showing it later.
    val cueLog = remember(player) { ArrayDeque<Pair<Long, List<androidx.media3.common.text.Cue>>>() }
    var fileCues by remember(player) { mutableStateOf<List<app.couchy.core.sources.Cue>?>(null) }
    androidx.compose.runtime.DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) {
                synchronized(cueLog) {
                    cueLog.addLast(player.currentPosition to cueGroup.cues)
                    while (cueLog.size > 600) cueLog.removeFirst()
                }
                if (subMs == 0L) subCues = cueGroup.cues
            }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) synchronized(cueLog) { cueLog.clear() }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    // A shifted subtitle file is read by Couchy, so it can go earlier as well as later.
    LaunchedEffect(player, subMs != 0L, tracks) {
        fileCues = null
        if (subMs == 0L) return@LaunchedEffect
        val label = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.isSelected }
            .flatMap { g -> (0 until g.length).filter { g.isTrackSelected(it) }.map { g.getTrackFormat(it).label } }.firstOrNull()
        val url = playback.subtitles.firstOrNull { it.first == label }?.second ?: return@LaunchedEffect
        fileCues = runCatching { app.couchy.sources.SubtitleFinder.cues(context, url, playback.headers) }.getOrNull()?.takeIf { it.isNotEmpty() }
    }
    LaunchedEffect(player, subMs, fileCues) {
        if (subMs == 0L) {
            subCues = player.currentCues.cues
            return@LaunchedEffect
        }
        while (true) {
            val at = player.currentPosition - subMs
            val file = fileCues
            subCues = if (file != null) {
                app.couchy.core.sources.SubCues.at(file, at)?.let { listOf(androidx.media3.common.text.Cue.Builder().setText(it).build()) } ?: emptyList()
            } else {
                synchronized(cueLog) { cueLog.lastOrNull { it.first <= at }?.second } ?: emptyList()
            }
            delay(100)
        }
    }
    // Foreign subtitles put into Russian on the device; the original can stay on top.
    var translateMode by remember { mutableStateOf(SubTranslate.mode(context)) }
    var translatedCues by remember(player) { mutableStateOf<List<androidx.media3.common.text.Cue>?>(null) }
    var originalLine by remember(player) { mutableStateOf<String?>(null) }
    LaunchedEffect(subCues, translateMode) {
        val from = SubTranslate.source(currentTextLanguage(player))
        if (translateMode == SubTranslation.OFF || from == null) { translatedCues = null; originalLine = null; return@LaunchedEffect }
        val text = subCues.mapNotNull { it.text?.toString() }.joinToString("\n").trim()
        if (text.isEmpty()) { translatedCues = emptyList(); originalLine = null; return@LaunchedEffect }
        translatedCues = emptyList()
        val ru = SubTranslate.translate(from, text)
        translatedCues = ru?.let { listOf(androidx.media3.common.text.Cue.Builder().setText(it).build()) }
        originalLine = if (ru != null && translateMode == SubTranslation.BOTH) text else null
    }
    // The model comes down once; a subtitle file is then translated ahead, so lines show on time.
    LaunchedEffect(player, translateMode, tracks) {
        if (translateMode == SubTranslation.OFF) return@LaunchedEffect
        val from = SubTranslate.source(currentTextLanguage(player)) ?: return@LaunchedEffect
        if (!SubTranslate.hasModel(from)) {
            flash("Загружаю переводчик, около 30 МБ…")
            if (!SubTranslate.prepare(from)) { flash("Переводчик не загрузился: нужен интернет и сервисы Google"); return@LaunchedEffect }
            flash("Переводчик готов")
        }
        val label = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.isSelected }
            .flatMap { g -> (0 until g.length).filter { g.isTrackSelected(it) }.map { g.getTrackFormat(it).label } }.firstOrNull()
        val url = playback.subtitles.firstOrNull { it.first == label }?.second ?: return@LaunchedEffect
        val cues = runCatching { app.couchy.sources.SubtitleFinder.cues(context, url, playback.headers) }.getOrNull().orEmpty()
        SubTranslate.ahead(from, cues.map { it.text })
    }
    fun setAudioShift(ms: Long) {
        val v = ms.coerceIn(-5_000, 5_000)
        audioMs = v
        audioShift.shiftMs = v
        PlayerTuning.setAudioShift(context, v)
        // The sound starts over from here with the new shift.
        if (playback.live == null) player.seekTo(player.currentPosition)
    }
    var goTo by remember { mutableStateOf<Sheet?>(null) }
    LaunchedEffect(goTo) { goTo?.let { sheet = it; goTo = null } }
    LaunchedEffect(hintAt) { if (hint != null) { delay(1_200); hint = null } }
    // Digits on the remote collect into a channel number, which opens after a short pause.
    LaunchedEffect(typed) {
        if (typed.isEmpty()) return@LaunchedEffect
        delay(1_500)
        typed.toIntOrNull()?.let { onNumber?.invoke(it) }
        typed = ""
    }

    fun poke() { lastTouch = System.currentTimeMillis(); overlay = true }

    // Full screen: the status and navigation bars hide and come back with a swipe from the edge.
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(view, mini) {
        if (mini || music) return@DisposableEffect onDispose { if (music) stats.flush() }
        val window = (view.context as? android.app.Activity)?.window
        val bars = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
        bars?.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        onDispose {
            stats.flush()
            bars?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            // Brightness set by a swipe belongs to the video only.
            window?.attributes = window?.attributes?.apply { screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
        }
    }

    fun save() {
        if (playback.live != null) return
        val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: 0
        if (player.currentPosition > 0) onProgress(player.currentPosition, duration)
    }

    fun exit() { save(); onExit() }

    // YouTube: Back and the arrow shrink the video into a corner; it goes on playing there.
    fun leave() {
        val m = onMinimize
        if (m != null) { save(); sheet = null; overlay = false; m() } else exit()
    }

    // Sleep timer "to the end of the episode" and "stay on the credits" both stop here instead of going on.
    fun finished() { if (sleepAfterEpisode || nextCancelled) onExit() else onFinished() }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            /** The video played at least once; a failure after that is a dropped stream, not a broken source. */
            var started = false

            override fun onEvents(p: Player, events: Player.Events) {
                if (p.isPlaying) started = true
                ui = ui.copy(
                    playing = p.isPlaying,
                    buffering = p.playbackState == Player.STATE_BUFFERING || p.playbackState == Player.STATE_IDLE && ui.error == null,
                )
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED && playback.live != null) {
                    onExit()
                } else if (state == Player.STATE_ENDED) {
                    val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: 0
                    onProgress(duration, duration)
                    finished()
                }
            }

            override fun onTracksChanged(t: Tracks) {
                tracks = t
                ui = ui.copy(qualityLabel = playback.variants.firstOrNull { it.second == playback.uri }?.first ?: qualityLabel(player))
            }

            override fun onPlayerError(error: PlaybackException) {
                // A source that never started (not a dropped connection) gives way to the next one by itself.
                val offline = error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                    error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
                // A quality that will not open (a paid one, a dead file) gives way to the next one down first.
                val variants = playback.variants
                val at = variants.indexOfFirst { it.second == playback.uri }
                val lower = variants.getOrNull(at + 1)
                if (!offline && !started && at >= 0 && lower != null && variantNow != null) {
                    flash("${variants[at].first} не открылось, включаю ${lower.first}")
                    variantNow?.invoke(lower.second, player.currentPosition)
                    return
                }
                if (!offline && !started && fallback?.invoke() == true) return
                ui = ui.copy(error = errorText(error), buffering = false)
                overlay = true
            }
        }
        player.addListener(listener)
        onDispose {
            save()
            player.removeListener(listener)
            player.release()
        }
    }

    // Phones turn sideways for video, and the rotate button stands them up again; tablets and TVs keep whatever they have.
    val phone = touch && context.resources.configuration.smallestScreenWidthDp < 600
    var upright by remember { mutableStateOf(false) }
    val orientationBefore = remember { (context as? Activity)?.requestedOrientation }
    DisposableEffect(touch) {
        val activity = context as? Activity
        onDispose { if (orientationBefore != null) activity?.requestedOrientation = orientationBefore }
    }
    androidx.compose.runtime.LaunchedEffect(upright, phone, mini) {
        if (phone) (context as? Activity)?.requestedOrientation = when {
            mini || music -> orientationBefore ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            upright -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    // Leaving the app pauses playback and saves the spot; coming back does not auto-play.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, player) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    save()
                    if (background && player.isPlaying) {
                        // The sound goes on with the screen off; the picture is not decoded meanwhile.
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build()
                        if (!music) BackgroundAudio.start(context, player, playback.title)
                    } else {
                        player.pause()
                    }
                }
                Lifecycle.Event.ON_START -> {
                    if (!music) BackgroundAudio.stop(context)
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false).build()
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(player) {
        var sinceSave = 0L
        while (true) {
            val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: 0
            ui = ui.copy(positionMs = player.currentPosition, durationMs = duration, bufferedMs = player.bufferedPosition)
            app.couchy.cast.RemoteBridge.state = app.couchy.core.cast.RemoteState(playback.title, player.currentPosition, duration, player.isPlaying, hasNext, key = if (playback.live == null) playback.key else "")
            delay(500)
            sinceSave += 500
            if (player.isPlaying && playback.live == null) stats.played(playback.title, 500)
            if (sinceSave >= 5_000 && player.isPlaying) { save(); sinceSave = 0 }
            // Sponsor reads and the like: jump to their end once, the first time playback reaches them.
            if (player.isPlaying) {
                val at = player.currentPosition
                playback.skips.firstOrNull { at in it.startMs until it.endMs - 500 && it !in skipped && !(hasNext && it.label == CREDITS) }?.let { s ->
                    skipped += s
                    player.seekTo(s.endMs)
                    flash(s.label)
                }
                intro?.takeIf { it.hasOpening }?.let { i ->
                    if (!introSkipped && at in (i.startMs - 1_500).coerceAtLeast(0) until i.endMs - 1_000) {
                        introSkipped = true
                        player.seekTo(i.endMs)
                        flash("Заставка пропущена")
                    }
                }
            }
            // The sleep timer turns the sound down over the last ten seconds, then stops.
            sleepAt?.let { at ->
                val left = at - System.currentTimeMillis()
                player.volume = (left / 10_000f).coerceIn(0f, 1f)
                if (left <= 0) { player.volume = 1f; sleepAt = null; exit() }
            }
        }
    }

    // The phone remote presses the same buttons as the person in front of the screen.
    DisposableEffect(player) {
        val audio = context.getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager
        app.couchy.cast.RemoteBridge.handler = { action, value ->
            when (action) {
                app.couchy.core.cast.RemoteAction.TOGGLE -> if (player.isPlaying) player.pause() else player.play()
                app.couchy.core.cast.RemoteAction.PLAY -> player.play()
                app.couchy.core.cast.RemoteAction.PAUSE -> player.pause()
                app.couchy.core.cast.RemoteAction.SEEK_BY -> player.seekTo((player.currentPosition + value).coerceAtLeast(0))
                app.couchy.core.cast.RemoteAction.SEEK_TO -> player.seekTo(value.coerceAtLeast(0))
                app.couchy.core.cast.RemoteAction.NEXT -> if (hasNext) { save(); onFinished() }
                app.couchy.core.cast.RemoteAction.STOP -> exit()
                app.couchy.core.cast.RemoteAction.VOLUME -> audio?.adjustStreamVolume(
                    android.media.AudioManager.STREAM_MUSIC,
                    if (value > 0) android.media.AudioManager.ADJUST_RAISE else android.media.AudioManager.ADJUST_LOWER,
                    android.media.AudioManager.FLAG_SHOW_UI,
                )
            }
            poke()
        }
        onDispose {
            app.couchy.cast.RemoteBridge.handler = null
            app.couchy.cast.RemoteBridge.state = null
        }
    }

    LaunchedEffect(overlay, lastTouch, ui.playing, sheet) {
        if (overlay && ui.playing && sheet == null && ui.error == null) {
            // Long enough to read the buttons and reach the one wanted.
            delay(if (touch) 7_000 else 8_000)
            overlay = false
        }
    }
    LaunchedEffect(overlay) { if (!overlay) { seekingByKey = false; runCatching { rootFocus.requestFocus() } } }
    // A new channel shows its name and programme for a moment.
    LaunchedEffect(playback.uri) { if (playback.live != null) poke() }

    // Screen lock against little hands and pockets: touches and Back do nothing until a long press.
    var locked by remember { mutableStateOf(false) }
    var lockHintAt by remember { mutableLongStateOf(0L) }

    // Flipping channels: presses add up, a banner shows where they land, and the switch happens
    // once the remote rests, so several channels go by without each one starting to load.
    var zapSteps by remember { mutableIntStateOf(0) }
    var zapAt by remember { mutableLongStateOf(0L) }
    fun zapBy(delta: Int) {
        if (onZap == null) return
        zapSteps += delta
        zapAt = System.currentTimeMillis()
    }
    LaunchedEffect(zapAt) {
        if (zapSteps == 0) return@LaunchedEffect
        delay(750)
        val steps = zapSteps
        zapSteps = 0
        if (steps != 0) onZap?.invoke(steps)
    }
    val liveList = remember(liveGroup, playback.live?.channelId) { if (playback.live != null) liveChannels(liveGroup ?: liveGroups.firstOrNull().orEmpty()) else emptyList() }
    val zapTarget = playback.live?.takeIf { zapSteps != 0 }?.let { l ->
        val order = l.zapOrder
        val i = order.indexOf(l.channelId)
        if (i < 0 || order.isEmpty()) null else order[Math.floorMod(i + zapSteps, order.size)].let { id -> liveList.firstOrNull { it.id == id } }
    }
    // Closing the channel list hands the remote back to the player.
    var drawerOpen by remember { mutableStateOf(false) }
    LaunchedEffect(sheet) {
        if (sheet == Sheet.CHANNELS) drawerOpen = true
        else if (drawerOpen) { drawerOpen = false; runCatching { rootFocus.requestFocus() } }
    }
    // After a switch the new channel's name and programme show for a moment.
    var channelInfo by remember { mutableStateOf(false) }
    LaunchedEffect(playback.live?.channelId) {
        if (playback.live == null || playback.live?.archiveOf != null) return@LaunchedEffect
        channelInfo = true
        delay(3500)
        channelInfo = false
    }
    BackHandler(enabled = !mini) {
        when {
            locked -> lockHintAt = System.currentTimeMillis()
            sheet != null -> sheet = null
            overlay && ui.playing && ui.error == null && onMinimize == null -> overlay = false
            else -> leave()
        }
    }

    val remaining = ui.durationMs - ui.positionMs
    // End credits: where the site says they start, or where the person marked them for the series.
    val creditsAt = playback.skips.firstOrNull { it.label == CREDITS }?.startMs
        ?: intro?.creditsMs?.takeIf { it > 0 && ui.durationMs > it * 3 }?.let { ui.durationMs - it }
    val nextAllowed = hasNext && playback.live == null && playback.artwork == null && !nextCancelled && !sleepAfterEpisode && ui.durationMs > 60_000
    val nextIn = when {
        !nextAllowed || remaining < 0 -> null
        creditsAt != null && ui.positionMs >= creditsAt -> (10 - (ui.positionMs - creditsAt) / 1000).toInt().coerceAtLeast(0)
        remaining <= 15_000 -> ((remaining + 999) / 1000).toInt()
        else -> null
    }
    // On the credits the countdown runs to the next episode without waiting for the very end.
    var wentNext by remember(playback.uri) { mutableStateOf(false) }
    LaunchedEffect(nextIn == 0 && creditsAt != null) {
        if (nextIn == 0 && creditsAt != null && !wentNext) { wentNext = true; save(); onFinished() }
    }

    // Spoken commands: from the remote's or the phone's microphone, or the Couchy remote on a phone.
    val canListen = remember { app.couchy.ui.components.Voice.available(context) }
    var listening by remember { mutableStateOf(false) }
    fun obey(cmd: app.couchy.core.VoiceCommand?) {
        poke()
        when (cmd) {
            null -> flash("Не понял. Скажите, например, «перемотай на 10 минут»")
            is app.couchy.core.VoiceCommand.SeekBy -> {
                player.seekTo((player.currentPosition + cmd.ms).coerceAtLeast(0))
                flash((if (cmd.ms < 0) "Назад на " else "Вперёд на ") + spoken(kotlin.math.abs(cmd.ms)))
            }
            is app.couchy.core.VoiceCommand.SeekTo -> { player.seekTo(cmd.ms); flash("Перешёл на ${clock(cmd.ms)}") }
            app.couchy.core.VoiceCommand.Pause -> player.pause()
            app.couchy.core.VoiceCommand.Play -> player.play()
            app.couchy.core.VoiceCommand.Next -> if (hasNext) { save(); onFinished() } else flash("Следующей серии нет")
            app.couchy.core.VoiceCommand.SubtitlesOn -> {
                if (tracks.groups.none { it.type == C.TRACK_TYPE_TEXT }) flash("Субтитров нет. «Озвучка» → «Найти в интернете…»")
                else {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build()
                    flash("Субтитры включены")
                }
            }
            app.couchy.core.VoiceCommand.SubtitlesOff -> {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                flash("Субтитры выключены")
            }
            app.couchy.core.VoiceCommand.Louder, app.couchy.core.VoiceCommand.Quieter -> {
                val audio = context.getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager
                audio?.adjustStreamVolume(
                    android.media.AudioManager.STREAM_MUSIC,
                    if (cmd == app.couchy.core.VoiceCommand.Louder) android.media.AudioManager.ADJUST_RAISE else android.media.AudioManager.ADJUST_LOWER,
                    android.media.AudioManager.FLAG_SHOW_UI,
                )
            }
            app.couchy.core.VoiceCommand.NightSound -> if (NightSound.supported) {
                night = !night
                NightSound.set(context, night)
                flash(if (night) "Ночной звук включён" else "Обычный звук")
            }
            is app.couchy.core.VoiceCommand.Speed -> {
                speed = cmd.value
                player.setPlaybackSpeed(cmd.value)
                ui = ui.copy(speedLabel = speedLabel(cmd.value))
                flash("Скорость ${speedLabel(cmd.value)}")
            }
            app.couchy.core.VoiceCommand.Exit -> exit()
        }
    }
    fun hear(heard: List<String>) = obey(heard.firstNotNullOfOrNull { app.couchy.core.VoiceCommand.parse(it) })
    DisposableEffect(player) {
        app.couchy.cast.RemoteBridge.say = { text -> hear(listOf(text)) }
        onDispose { app.couchy.cast.RemoteBridge.say = null }
    }

    val controls = PlayerControls(
        onOtherSource = onSources?.let { back -> { save(); back() } },
        onExternal = onExternal?.let { open -> { save(); open(player.currentPosition) } },
        onBack = ::leave,
        onTogglePlay = { poke(); if (player.isPlaying) player.pause() else player.play() },
        onSeekTo = { poke(); player.seekTo(it) },
        onSeekBy = { poke(); player.seekTo((player.currentPosition + it).coerceAtLeast(0)) },
        onTracks = { poke(); sheet = Sheet.TRACKS },
        onQuality = { poke(); sheet = Sheet.QUALITY },
        onSpeed = { poke(); sheet = Sheet.SPEED },
        onResize = {
            poke()
            resize = (resize + 1) % Resize.entries.size
            ui = ui.copy(resizeLabel = Resize.entries[resize].label)
        },
        onRetry = {
            ui = ui.copy(error = null, buffering = true)
            player.prepare()
            player.play()
        },
        onInteract = ::poke,
        onRotate = if (phone) ({ poke(); upright = !upright }) else null,
        onSleep = { poke(); sheet = Sheet.SLEEP },
        onMore = { poke(); sheet = Sheet.MORE },
        onEpisodes = if (episodes.size > 1) ({ poke(); sheet = Sheet.EPISODES }) else null,
        onChannels = if (onLivePick != null && playback.live != null) ({ overlay = false; sheet = Sheet.CHANNELS }) else null,
        onZap = if (onZap != null) ({ d -> poke(); zapBy(d) }) else null,
        thumbnail = thumbs?.let { t -> t::at },
        onIntro = if (series != null) ({ poke(); sheet = Sheet.INTRO }) else null,
        onPip = if (touch && PlayerWindow.supported(context)) ({ (context as? android.app.Activity)?.let { PlayerWindow.enter(it) }; Unit }) else null,
        onDownload = onDownload?.let { d -> { poke(); d(); flash("Скачивается. Найдёте в «Моё» → «Загрузки»") } },
        onFavorite = onFavorite?.let { f -> { poke(); f() } },
        onPrevious = onPrevious?.let { f -> { poke(); f() } },
        onNextNow = { save(); onFinished() },
        onNextCancel = { nextCancelled = true; poke() },
        onVoice = if (canListen && playback.live == null) ({ poke(); listening = true }) else null,
        onLock = if (touch) ({ locked = true; overlay = false; lockHintAt = System.currentTimeMillis() }) else null,
        onNight = if (NightSound.supported) ({
            poke()
            night = !night
            NightSound.set(context, night)
            flash(if (night) "Ночной звук: взрывы тише, голоса громче" else "Обычный звук")
        }) else null,
        onCast = onCast?.let { cast ->
            {
                poke()
                save()
                cast(player.currentPosition, player.duration.takeIf { it != C.TIME_UNSET } ?: 0)
            }
        },
    )

    if (mini) {
        MiniPlayer(
            player, ui, playback.artwork, modifier, onExpand = onExpand, onToggle = { if (player.isPlaying) player.pause() else player.play() }, onClose = ::exit,
            music = music, onNext = if (music && hasNext) ({ save(); onFinished() }) else null,
        )
        return
    }

    if (music && touch) {
        MusicView(
            ui = ui,
            artwork = playback.artwork,
            track = if (radio) onAirSong ?: playback.title else queue.getOrNull(queueAt) ?: playback.title,
            album = if (radio) (if (onAirSong != null) playback.title else "Радио") else musicAlbum,
            queueAt = queueAt,
            queueSize = queue.size,
            sleepLabel = when {
                sleepAfterEpisode -> "До конца песни"
                sleepAt != null -> java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(java.util.Date(sleepAt!!))
                else -> null
            },
            onDown = ::leave,
            onToggle = { if (player.isPlaying) player.pause() else player.play() },
            onSeekTo = { player.seekTo(it) },
            onPrevious = { if (radio) previousNow?.invoke() else if (player.currentPosition > 3_000 || queueAt == 0 || onQueuePick == null) player.seekTo(0) else { save(); onQueuePick(queueAt - 1) } },
            onNext = if (hasNext) ({ save(); onFinished() }) else null,
            onQueue = if (queue.size > 1 && onQueuePick != null) ({ sheet = Sheet.QUEUE }) else null,
            onSave = onSaveAudio,
            onSleep = { sheet = Sheet.SLEEP },
        )
    } else Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) lastTouch = System.currentTimeMillis()
                // Media keys work whatever has focus.
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                // The channel list is open: its rows take the arrows and OK.
                if (sheet == Sheet.CHANNELS) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.MediaPlayPause -> { controls.onTogglePlay(); true }
                    Key.MediaPlay -> { player.play(); poke(); true }
                    Key.Search, Key.VoiceAssist, Key.Assist -> { if (canListen && playback.live == null) { listening = true; true } else false }
                    Key.MediaPause -> { player.pause(); poke(); true }
                    Key.MediaNext -> { if (hasNext && playback.live == null) { save(); onFinished() }; hasNext && playback.live == null }
                    Key.Captions -> { poke(); sheet = Sheet.TRACKS; true }
                    Key.Info -> { poke(); true }
                    Key.MediaFastForward -> { controls.onSeekBy(30_000); true }
                    Key.MediaRewind -> { controls.onSeekBy(-30_000); true }
                    // Live TV: up and down switch channels whatever is on screen, like any TV.
                    Key.DirectionUp -> { zapBy(-1); onZap != null }
                    Key.DirectionDown -> { zapBy(1); onZap != null }
                    Key.ChannelUp -> { zapBy(1); onZap != null }
                    Key.ChannelDown -> { zapBy(-1); onZap != null }
                    Key.LastChannel -> { onPrevious?.invoke(); onPrevious != null }
                    Key.Zero, Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine,
                    Key.NumPad0, Key.NumPad1, Key.NumPad2, Key.NumPad3, Key.NumPad4, Key.NumPad5, Key.NumPad6, Key.NumPad7, Key.NumPad8, Key.NumPad9 -> {
                        if (onNumber == null) return@onPreviewKeyEvent false
                        val digit = digitOf(event.key)
                        if (typed.length < 4) typed += digit
                        flash("Канал $typed")
                        true
                    }
                    else -> false
                }
            }
            .focusRequester(rootFocus)
            // With the overlay hidden, the remote controls playback directly.
            .onKeyEvent { event ->
                if (overlay || sheet == Sheet.CHANNELS || event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        // On a channel OK opens the list of channels; pausing live TV is rarely wanted.
                        if (onLivePick != null && playback.live != null) { overlay = false; sheet = Sheet.CHANNELS } else controls.onTogglePlay()
                        true
                    }
                    // Live TV: left and right switch channels too; a held key does not race through the list.
                    Key.DirectionLeft -> {
                        if (onZap != null) { if (event.nativeKeyEvent.repeatCount % 3 == 0) zapBy(-1) } else { seekingByKey = true; controls.onSeekBy(-seekStep(event.nativeKeyEvent.repeatCount)) }
                        true
                    }
                    Key.DirectionRight -> {
                        if (onZap != null) { if (event.nativeKeyEvent.repeatCount % 3 == 0) zapBy(1) } else { seekingByKey = true; controls.onSeekBy(seekStep(event.nativeKeyEvent.repeatCount)) }
                        true
                    }
                    Key.DirectionUp -> { if (onZap != null) zapBy(-1) else poke(); true }
                    Key.DirectionDown -> { if (onZap != null) zapBy(1) else poke(); true }
                    Key.Menu -> { poke(); true }
                    else -> false
                }
            }
            .focusable()
            .pointerInput(touch) {
                detectTapGestures(
                    onTap = { if (overlay) overlay = false else poke() },
                    onDoubleTap = { offset ->
                        when {
                            offset.x < size.width / 3f -> controls.onSeekBy(-10_000)
                            offset.x > size.width * 2f / 3f -> controls.onSeekBy(10_000)
                            else -> controls.onTogglePlay()
                        }
                    },
                )
            }
            // Videos on a phone: a swipe on the left half sets brightness, on the right half volume.
            .pointerInput(touch, onZap == null) {
                if (!touch || onZap != null) return@pointerInput
                val activity = context as? Activity ?: return@pointerInput
                val audio = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                val maxVolume = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                var left = true
                var level = 0f
                detectVerticalDragGestures(
                    onDragStart = { start ->
                        left = start.x < size.width / 2f
                        level = if (left) {
                            activity.window.attributes.screenBrightness.takeIf { it >= 0 }
                                ?: (android.provider.Settings.System.getInt(context.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS, 128) / 255f)
                        } else audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC).toFloat() / maxVolume
                    },
                    onVerticalDrag = { change, dy ->
                        change.consume()
                        level = (level - dy / (size.height * 0.8f)).coerceIn(0f, 1f)
                        val percent = (level * 100).toInt()
                        if (left) {
                            activity.window.attributes = activity.window.attributes.apply { screenBrightness = level.coerceAtLeast(0.01f) }
                            flash("Яркость $percent%")
                        } else {
                            audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, (level * maxVolume).toInt(), 0)
                            flash("Громкость $percent%")
                        }
                    },
                )
            }
            // Holding a finger on a video plays it at double speed until the finger lifts.
            .pointerInput(touch, onZap == null) {
                if (!touch || onZap != null) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val moved = withTimeoutOrNull(500) {
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed || change.isConsumed || (change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                        }
                        true
                    }
                    if (moved == null && player.isPlaying) {
                        player.setPlaybackSpeed(2f)
                        boosting = true
                        do { val event = awaitPointerEvent() } while (event.changes.any { it.pressed })
                        player.setPlaybackSpeed(speed)
                        boosting = false
                    }
                }
            }
            // On a touch screen a vertical swipe switches channels.
            .pointerInput(onZap != null) {
                if (onZap == null) return@pointerInput
                var dragged = 0f
                detectVerticalDragGestures(
                    onDragStart = { dragged = 0f },
                    onVerticalDrag = { _, dy -> dragged += dy },
                    onDragEnd = { if (abs(dragged) > size.height / 6f) onZap(if (dragged < 0) 1 else -1) },
                )
            },
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    keepScreenOn = true
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    // Couchy draws the subtitles itself, just above.
                    subtitleView?.visibility = android.view.View.GONE
                    this.player = player
                }
            },
            update = { view ->
                view.player = player
                view.resizeMode = Resize.entries[resize].mode
            },
            modifier = Modifier.fillMaxSize(),
        )
        AndroidView(
            factory = { ctx -> androidx.media3.ui.SubtitleView(ctx) },
            update = { v ->
                PlayerTuning.style(v, subSize, subBack, subPlace)
                v.setCues(translatedCues ?: subCues)
            },
            modifier = Modifier.fillMaxSize(),
        )
        playback.artwork?.let { Cover(it, ui.title) }
        if (listening) {
            app.couchy.ui.components.VoiceCapture(
                "Скажите: «перемотай на 10 минут», «включи субтитры», «следующая серия»",
                onResult = { heard -> listening = false; hear(heard) },
                onCancel = { listening = false },
            )
        }
        recapNote?.let { note ->
            RecapCard(note, Modifier.align(Alignment.Center)) { recapNote = null; player.play(); runCatching { rootFocus.requestFocus() } }
        }
        (secondText ?: originalLine)?.let { text ->
            Text(
                text,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium.copy(
                    shadow = androidx.compose.ui.graphics.Shadow(Color.Black, blurRadius = 6f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                ),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = if (overlay) 84.dp else 28.dp, start = 48.dp, end = 48.dp)
                    .background(Color.Black.copy(alpha = 0.45f), androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        PlayerOverlay(
            ui.copy(
                seeking = seekingByKey,
                hasNext = hasNext,
                live = live,
                status = status,
                hint = if (boosting) "2× пока держите" else hint,
                nextIn = nextIn,
                night = night,
                sleepLabel = when {
                    sleepAfterEpisode -> "До конца серии"
                    sleepAt != null -> "Сон в " + java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(java.util.Date(sleepAt!!))
                    else -> null
                },
            ),
            overlay && !pip && !locked, controls, touch,
        )
        if (locked) LockShield(lockHintAt, onTap = { lockHintAt = System.currentTimeMillis() }, onUnlock = { locked = false; poke() })
        if (zapSteps != 0) {
            ZapBanner(zapTarget, zapSteps, Modifier.align(Alignment.BottomStart).padding(32.dp))
        } else if (channelInfo && !overlay && sheet == null && live != null) {
            ZapBanner(LiveChoice(0, live.number ?: 0, live.channel, live.nowTitle), 0, Modifier.align(Alignment.BottomStart).padding(32.dp))
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = sheet == Sheet.CHANNELS && onLivePick != null,
            enter = androidx.compose.animation.slideInHorizontally { -it / 2 } + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.slideOutHorizontally { -it / 2 } + androidx.compose.animation.fadeOut(),
        ) {
            ChannelDrawer(
                groups = liveGroups,
                group = liveGroup,
                channels = liveChannels,
                currentId = playback.live?.channelId,
                onPick = { id, g -> onLivePick?.invoke(id, g) },
                onClose = { sheet = null; runCatching { rootFocus.requestFocus() } },
            )
        }
    }

    when (sheet) {
        Sheet.TRACKS -> OptionsDialog(
            "Озвучка и субтитры",
            listOfNotNull(
                // The site's voice-overs are separate videos: picking one goes on from the same moment.
                if (playback.voices.size > 1 && onVoice != null) {
                    val current = app.couchy.core.sources.Rezka.parseRef(playback.key)?.translator
                    OptionSection("ОЗВУЧКА", playback.voices.map { (id, name) ->
                        Option(name, id == current) { if (id != current) { save(); onVoice(id, player.currentPosition) } }
                    })
                } else null,
                trackOptions(player, tracks, C.TRACK_TYPE_AUDIO) { lang -> onTracksChosen(lang, currentTextLanguage(player)) }
                    .takeIf { playback.voices.size <= 1 || onVoice == null || it.size > 1 }
                    ?.let { OptionSection(if (playback.voices.size > 1 && onVoice != null) "ДОРОЖКА" else "ОЗВУЧКА", it) },
                OptionSection(
                    "СУБТИТРЫ",
                    listOf(
                        Option("Выключены", textDisabled(player)) {
                            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                            onTracksChosen(currentAudioLanguage(player), "")
                        },
                    ) + trackOptions(player, tracks, C.TRACK_TYPE_TEXT) { lang -> onTracksChosen(currentAudioLanguage(player), lang) } +
                        (if (onSubtitle != null && playback.live == null) listOf(Option("Найти в интернете…", false) { findSecond = false; findSubs = true }) else emptyList()),
                ),
                if (playback.live == null) OptionSection(
                    "ПЕРЕВОД НА РУССКИЙ",
                    SubTranslation.entries.map { m ->
                        Option(m.label, translateMode == m) {
                            translateMode = m
                            SubTranslate.setMode(context, m)
                            if (m != SubTranslation.OFF && textDisabled(player)) flash("Включите субтитры на другом языке, и они будут переведены")
                        }
                    },
                ) else null,
                if (playback.live == null) OptionSection(
                    "ВТОРЫЕ СУБТИТРЫ · СВЕРХУ",
                    listOf(Option("Выключены", second == null) { second = null; secondWanted = null }) +
                        playback.subtitles.map { (label, url) -> Option(label, second?.first == label) { loadSecond(label, url) } } +
                        listOf(Option("Найти в интернете…", false) { findSecond = true; findSubs = true }),
                ) else null,
                OptionSection(
                    "НАСТРОЙКА",
                    listOf(
                        Option("Вид субтитров…", false, tag = subSize.label) { goTo = Sheet.SUBSTYLE },
                        Option("Синхронизация…", audioMs != 0L || subMs != 0L) { goTo = Sheet.SYNC },
                    ),
                ),
                listOfNotNull(
                    onSources?.let { back -> Option("Другой источник…", false) { save(); back() } },
                    onExternal?.let { open -> Option("Открыть во внешнем плеере", false) { save(); open(player.currentPosition) } },
                ).takeIf { it.isNotEmpty() }?.let { OptionSection("ИСТОЧНИК", it) },
            ),
            onDismiss = { sheet = null },
        )
        Sheet.QUALITY -> OptionsDialog(
            "Качество",
            listOf(
                OptionSection(
                    null,
                    // A source with a file per quality switches files; a stream switches its own tracks.
                    if (playback.variants.size > 1 && onVariant != null) {
                        playback.variants.map { (label, url) ->
                            Option(label, url == playback.uri) {
                                // Only this video changes; the default stays as set on the title page.
                                if (url != playback.uri) {
                                    save()
                                    onVariant(url, player.currentPosition)
                                }
                            }
                        }
                    } else {
                        qualityOptions(player, tracks) { ui = ui.copy(qualityLabel = it) }
                    },
                ),
            ),
            onDismiss = { sheet = null },
        )
        Sheet.SPEED -> OptionsDialog(
            "Скорость",
            listOf(
                OptionSection(null, speeds.map { s ->
                    Option(speedLabel(s), s == speed) {
                        speed = s
                        player.setPlaybackSpeed(s)
                        ui = ui.copy(speedLabel = speedLabel(s))
                    }
                }),
            ),
            onDismiss = { sheet = null },
        )
        Sheet.EPISODES -> OptionsDialog(
            "Серии",
            episodes.groupBy { it.season }.map { (season, list) ->
                OptionSection(
                    if (episodes.map { it.season }.distinct().size > 1) "СЕЗОН $season" else null,
                    list.map { e ->
                        Option("${e.episode}. " + e.name.ifBlank { "Серия ${e.episode}" }, e.current, icon = Icons.Rounded.PlayArrow) { if (!e.current) { save(); e.play() } }
                    },
                )
            },
            onDismiss = { sheet = null },
        )
        // Drawn over the picture as a side panel, above.
        Sheet.CHANNELS -> Unit
        Sheet.QUEUE -> OptionsDialog(
            "Очередь",
            listOf(OptionSection(null, queue.mapIndexed { i, t -> Option(t, i == queueAt, icon = Icons.Rounded.MusicNote) { save(); onQueuePick?.invoke(i) } })),
            onDismiss = { sheet = null },
        )
        Sheet.SLEEP -> OptionsDialog(
            "Таймер сна",
            listOf(
                OptionSection(
                    null,
                    listOf(Option("Выключен", sleepAt == null && !sleepAfterEpisode) { sleepAt = null; sleepAfterEpisode = false; player.volume = 1f }) +
                        sleepMinutes.map { m ->
                            Option("$m мин", false) { sleepAfterEpisode = false; sleepAt = System.currentTimeMillis() + m * 60_000L }
                        } +
                        (if (playback.live == null) listOf(Option("До конца серии", sleepAfterEpisode) { sleepAt = null; sleepAfterEpisode = true }) else emptyList()),
                ),
            ),
            onDismiss = { sheet = null },
        )
        Sheet.FIND_SUBS -> {
            val found = subHits
            OptionsDialog(
                "Субтитры из интернета",
                listOf(
                    OptionSection(
                        when {
                            subError != null -> subError
                            found == null -> "Ищу на OpenSubtitles…"
                            found.isEmpty() -> "Ничего не нашлось для «${app.couchy.core.sources.Subtitles.queryOf(playback.title)}»"
                            else -> "OPENSUBTITLES"
                        },
                        found.orEmpty().map { hit ->
                            Option("${if (hit.lang == "rus") "Русские" else "Английские"} · ${hit.name}", false) {
                                subScope.launch {
                                    flash("Загружаю субтитры…")
                                    runCatching { app.couchy.sources.SubtitleFinder.download(context, hit) }
                                        .onSuccess { uri ->
                                            if (findSecond) {
                                                findSecond = false
                                                sheet = null
                                                loadSecond(if (hit.lang == "rus") "Русские (интернет)" else "Английские (интернет)", uri)
                                                return@onSuccess
                                            }
                                            save()
                                            val lang = if (hit.lang == "rus") "ru" else "en"
                                            onSubtitle?.invoke(if (lang == "ru") "Русские (интернет)" else "Английские (интернет)", uri, lang, player.currentPosition)
                                        }
                                        .onFailure { flash("Не удалось скачать субтитры") }
                                }
                            }
                        },
                    ),
                ),
                onDismiss = { sheet = null },
            )
        }
        Sheet.MORE -> app.couchy.ui.components.CouchySheet("Ещё", onDismiss = { sheet = null }) {
            app.couchy.ui.components.TileGrid(
                listOfNotNull(
                    controls.onNight?.takeIf { playback.live == null }?.let { toggle ->
                        app.couchy.ui.components.Tile("Ночной звук", Icons.Rounded.NightsStay, night, if (night) "Включён" else "Голоса громче") { sheet = null; toggle() }
                    },
                    app.couchy.ui.components.Tile(
                        "Таймер сна", Icons.Rounded.Bedtime, sleepAt != null || sleepAfterEpisode,
                        when {
                            sleepAfterEpisode -> "До конца серии"
                            sleepAt != null -> "Идёт"
                            else -> "Выключен"
                        },
                    ) { sheet = Sheet.SLEEP },
                    controls.onDownload?.let { d -> app.couchy.ui.components.Tile("Скачать", Icons.Rounded.Download, state = "На устройство") { sheet = null; d() } },
                    onSaveAudio?.let { a -> app.couchy.ui.components.Tile("Сохранить звук", Icons.Rounded.MusicNote, state = "Музыка, по трекам") { sheet = null; a(); flash("Сохраняю звук. Прогресс в уведомлении") } },
                    onQueuePick?.takeIf { queue.size > 1 }?.let { app.couchy.ui.components.Tile("Очередь", Icons.Rounded.QueueMusic, true, "${queueAt + 1} из ${queue.size}") { sheet = Sheet.QUEUE } },
                    controls.onPip?.let { p -> app.couchy.ui.components.Tile("Окно", Icons.Rounded.PictureInPictureAlt, state = "Поверх приложений") { sheet = null; p() } },
                    app.couchy.ui.components.Tile("Синхронизация", Icons.Rounded.Sync, audioMs != 0L || subMs != 0L, if (audioMs != 0L || subMs != 0L) "Сдвинуто" else "Звук и субтитры") { sheet = Sheet.SYNC },
                    app.couchy.ui.components.Tile("Вид субтитров", Icons.Rounded.ClosedCaption, state = subSize.label) { sheet = Sheet.SUBSTYLE },
                    if (app.couchy.ui.components.LocalFormFactor.current.isTv && playback.live == null) {
                        var on by remember { mutableStateOf(PlayerTuning.frameRate(context)) }
                        app.couchy.ui.components.Tile("Частота кадров", Icons.Rounded.Tv, on, if (on) "Как у фильма" else "Выключено") {
                            on = !on
                            PlayerTuning.setFrameRate(context, on)
                            flash(if (on) "Экран будет переключаться под фильм со следующего видео" else "Частота экрана не меняется")
                        }
                    } else null,
                ),
            )
        }
        Sheet.SYNC -> SyncSheet(
            audioMs, subMs,
            subNote = if (subMs < 0 && fileCues == null) "Встроенные субтитры можно только задержать. Раньше сдвигаются субтитры из файла." else null,
            onAudio = ::setAudioShift,
            onSub = { subMs = it.coerceIn(-10_000, 10_000) },
            onDismiss = { sheet = null },
        )
        Sheet.SUBSTYLE -> SubtitleStyleSheet(subSize, subBack, subPlace, onChange = { a, b, c ->
            subSize = a; subBack = b; subPlace = c
            PlayerTuning.setSub(context, a, b, c)
        }, onDismiss = { sheet = null })
        Sheet.INTRO -> {
            val at = player.currentPosition
            val from = introFrom
            val known = intro
            OptionsDialog(
                "Заставка и титры",
                listOf(
                    OptionSection(
                        known?.takeIf { it.hasOpening }?.let { "ПРОПУСКАЕТСЯ: ${clock(it.startMs)} – ${clock(it.endMs)}" }
                            ?: "Отметьте начало и конец заставки один раз, дальше Couchy будет пропускать её в каждой серии",
                        buildList {
                            if (from == null) {
                                add(Option("Заставка начинается здесь · ${clock(at)}", false) { introFrom = at; flash("Начало отмечено. Перемотайте на конец заставки") })
                            } else {
                                add(Option("Заставка кончается здесь · ${clock(at)}", false) {
                                    if (at > from + 5_000 && series != null) {
                                        val marked = app.couchy.data.Intro(from, at, known?.creditsMs ?: 0)
                                        app.couchy.data.Intros.set(marks, series, marked)
                                        intro = marked
                                        introSkipped = true
                                        introFrom = null
                                        flash("Заставка запомнена")
                                    } else {
                                        flash("Конец должен быть позже начала")
                                    }
                                })
                                add(Option("Отметить начало заново", false) { introFrom = null })
                            }
                            if (known?.hasOpening == true && series != null) {
                                add(Option("Забыть заставку", false) {
                                    val left = known.copy(startMs = 0, endMs = 0)
                                    app.couchy.data.Intros.set(marks, series, left)
                                    intro = left.takeIf { it.creditsMs > 0 }
                                    flash("Заставка больше не пропускается")
                                })
                            }
                        },
                    ),
                    OptionSection(
                        known?.creditsMs?.takeIf { it > 0 }?.let { "ТИТРЫ: ПОСЛЕДНИЕ ${clock(it)} СЕРИИ" }
                            ?: "Отметьте, где начинаются финальные титры, и следующая серия будет предлагаться сразу с них",
                        buildList {
                            val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: 0
                            add(Option("Титры начинаются здесь · ${clock(at)}", false) {
                                if (series != null && duration > 0 && at > duration / 2) {
                                    val marked = (known ?: app.couchy.data.Intro(0, 0)).copy(creditsMs = duration - at)
                                    app.couchy.data.Intros.set(marks, series, marked)
                                    intro = marked
                                    flash("Титры запомнены")
                                } else {
                                    flash("Перемотайте ближе к концу серии")
                                }
                            })
                            if ((known?.creditsMs ?: 0) > 0 && series != null) {
                                add(Option("Забыть титры", false) {
                                    val left = known!!.copy(creditsMs = 0)
                                    app.couchy.data.Intros.set(marks, series, left)
                                    intro = left.takeIf { it.hasOpening }
                                })
                            }
                        },
                    ),
                ),
                onDismiss = { sheet = null },
            )
        }
        null -> Unit
    }
}

/** Takes every touch while the screen is locked; a long press anywhere unlocks it. */
/** Music: the album cover large in the middle over a dim, blurred copy of itself. */
@Composable
private fun Cover(url: String, title: String) {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        coil.compose.AsyncImage(
            url, null,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier.fillMaxSize().then(if (android.os.Build.VERSION.SDK_INT >= 31) Modifier.blur(40.dp) else Modifier),
        )
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
        coil.compose.AsyncImage(
            url, title,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier.fillMaxHeight(0.62f).aspectRatio(1f).shadow(24.dp, androidx.compose.foundation.shape.RoundedCornerShape(20.dp)).clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp)),
        )
    }
}

/** The video small in a corner, over the rest of Couchy: tap to open it again, ✕ to stop. */
@Composable
private fun MiniPlayer(
    player: Player, ui: PlayerUi, artwork: String?, modifier: Modifier, onExpand: () -> Unit, onToggle: () -> Unit, onClose: () -> Unit,
    music: Boolean = false, onNext: (() -> Unit)? = null,
) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
    if (music) {
        // Music: a slim bar with the cover, the song, play and next; no picture to show.
        Row(
            modifier
                .width(300.dp)
                .shadow(16.dp, shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, shape)
                .clip(shape)
                .pointerInput(Unit) { detectTapGestures(onTap = { onExpand() }) }
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(44.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).background(Color.Black)) {
                artwork?.let { coil.compose.AsyncImage(it, null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            }
            val (title, artist) = songParts(ui.title)
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                if (artist.isNotBlank()) Text(artist, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            androidx.compose.material3.IconButton(onClick = onToggle) {
                androidx.compose.material3.Icon(if (ui.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (ui.playing) "Пауза" else "Играть", tint = MaterialTheme.colorScheme.onSurface)
            }
            onNext?.let { next ->
                androidx.compose.material3.IconButton(onClick = next) {
                    androidx.compose.material3.Icon(Icons.Rounded.SkipNext, "Следующая", tint = MaterialTheme.colorScheme.onSurface)
                }
            }
            androidx.compose.material3.IconButton(onClick = onClose) {
                androidx.compose.material3.Icon(Icons.Rounded.Close, "Закрыть", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }
    Column(
        modifier
            .width(232.dp)
            .shadow(16.dp, shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, shape)
            .clip(shape),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Color.Black)
                .pointerInput(Unit) { detectTapGestures(onTap = { onExpand() }) },
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        this.player = player
                    }
                },
                update = { it.player = player },
                modifier = Modifier.fillMaxSize(),
            )
            artwork?.let { coil.compose.AsyncImage(it, null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            if (ui.buffering) {
                androidx.compose.material3.CircularProgressIndicator(Modifier.align(Alignment.Center).size(26.dp), color = Color.White, strokeWidth = 2.dp)
            }
            // How far along: a thin line at the bottom of the picture.
            if (ui.durationMs > 0) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.2f))) {
                    Box(Modifier.fillMaxWidth((ui.positionMs.toFloat() / ui.durationMs).coerceIn(0f, 1f)).height(3.dp).background(MaterialTheme.colorScheme.primary))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                ui.title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).pointerInput(Unit) { detectTapGestures(onTap = { onExpand() }) },
            )
            androidx.compose.material3.IconButton(onClick = onToggle) {
                androidx.compose.material3.Icon(
                    if (ui.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    if (ui.playing) "Пауза" else "Играть",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            androidx.compose.material3.IconButton(onClick = onClose) {
                androidx.compose.material3.Icon(Icons.Rounded.Close, "Закрыть", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun LockShield(hintAt: Long, onTap: () -> Unit, onUnlock: () -> Unit) {
    var visible by remember { mutableStateOf(true) }
    LaunchedEffect(hintAt) { visible = true; delay(2_500); visible = false }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
            }
            .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }, onLongPress = { onUnlock() }) },
    ) {
        if (visible) {
            androidx.compose.foundation.layout.Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 28.dp)
                    .background(Color.Black.copy(alpha = 0.7f), androidx.compose.foundation.shape.RoundedCornerShape(999.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
            ) {
                androidx.compose.material3.Icon(Icons.Rounded.Lock, null, tint = Color.White)
                Text("Экран заблокирован. Удерживайте палец, чтобы снять", color = Color.White, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** «Ранее в сериале»: the episode before, over the paused picture, until "Смотреть". */
@Composable
private fun RecapCard(note: app.couchy.core.sources.EpisodeNote, modifier: Modifier, onDone: () -> Unit) {
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.foundation.layout.Column(
        modifier
            .padding(24.dp)
            .widthIn(max = 560.dp)
            .background(Color.Black.copy(alpha = 0.82f), androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
            .padding(22.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
    ) {
        Text("РАНЕЕ В СЕРИАЛЕ", color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelMedium)
        Text(
            "${note.season} сезон, ${note.episode} серия" + (note.name?.let { " · $it" } ?: ""),
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(note.overview, color = Color.White.copy(alpha = 0.88f), style = MaterialTheme.typography.bodyMedium, maxLines = 9, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        app.couchy.ui.components.PrimaryButton("Смотреть", Icons.Rounded.PlayArrow, onClick = onDone, modifier = Modifier.focusRequester(focus))
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

/** The label AniLibria's end credits come with: those wait for the next-episode card instead of being jumped. */
private const val CREDITS = "Титры"

/** "10 минут", "30 секунд", "1 ч 5 мин" for what a spoken seek did. */
private fun spoken(ms: Long): String {
    val s = ms / 1000
    return when {
        s >= 3600 -> app.couchy.core.Stats.duration(ms)
        s >= 60 && s % 60 == 0L -> "${s / 60} мин"
        s >= 60 -> "${s / 60} мин ${s % 60} с"
        else -> "$s с"
    }
}

private fun clock(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

private fun digitOf(key: Key): String = when (key) {
    Key.Zero, Key.NumPad0 -> "0"
    Key.One, Key.NumPad1 -> "1"
    Key.Two, Key.NumPad2 -> "2"
    Key.Three, Key.NumPad3 -> "3"
    Key.Four, Key.NumPad4 -> "4"
    Key.Five, Key.NumPad5 -> "5"
    Key.Six, Key.NumPad6 -> "6"
    Key.Seven, Key.NumPad7 -> "7"
    Key.Eight, Key.NumPad8 -> "8"
    else -> "9"
}

@OptIn(UnstableApi::class)
private fun buildPlayer(context: Context, playback: Playback, shift: AudioShift): ExoPlayer {
    val agent = playback.headers.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value
    val http = DefaultHttpDataSource.Factory()
        .setUserAgent(agent ?: "Couchy/${BuildConfig.VERSION_NAME} (Android)")
        .setDefaultRequestProperties(playback.headers.filterKeys { !it.equals("User-Agent", ignoreCase = true) })
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(20_000)
    val network = DefaultDataSource.Factory(context, http)
    // A saved copy plays from the device, without the internet.
    val downloads = (context.applicationContext as app.couchy.CouchyApp).downloads
    val sources = DefaultMediaSourceFactory(if (downloads.has(playback.key)) downloads.readThrough(network) else network)
    // Cheap TV boxes often have a broken first decoder; falling back to the next one plays the video anyway.
    // AC3, E-AC3, DTS and other sound the device cannot decode itself goes through FFmpeg, not silence.
    val renderers = object : DefaultRenderersFactory(context) {
        override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): androidx.media3.exoplayer.audio.AudioSink =
            androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessors(arrayOf(shift))
                .build()
    }.setEnableDecoderFallback(true).setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
    val player = ExoPlayer.Builder(context, renderers)
        .setMediaSourceFactory(sources)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        .setHandleAudioBecomingNoisy(true)
        .build()
    // YouTube may go on with the screen off: the network and the CPU stay awake for it.
    if (playback.audioUri != null || app.couchy.core.youtube.YouTubeLinks.isVideo(playback.key)) player.setWakeMode(C.WAKE_MODE_NETWORK)

    val params = player.trackSelectionParameters.buildUpon()
        .setPreferredAudioLanguage(playback.audioLanguage ?: Locale.getDefault().language)
    // Streams with several qualities start no higher than the one wanted (1080p unless changed).
    app.couchy.QualityChoice.maxHeight?.let { params.setMaxVideoSize(Int.MAX_VALUE, it) }
    when (playback.subtitleLanguage) {
        null -> Unit
        "" -> params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        else -> params.setPreferredTextLanguage(playback.subtitleLanguage)
    }
    player.trackSelectionParameters = params.build()

    val subtitles = playback.subtitles.map { (label, url) ->
        MediaItem.SubtitleConfiguration.Builder(Uri.parse(url))
            .setMimeType(if (url.substringBefore('?').endsWith(".srt", ignoreCase = true)) MimeTypes.APPLICATION_SUBRIP else MimeTypes.TEXT_VTT)
            .setLabel(label)
            .setLanguage(languageOf(label))
            .build()
    }
    val item = MediaItem.Builder().setUri(Uri.parse(playback.uri)).setSubtitleConfigurations(subtitles).apply {
        when (Links.classify(playback.uri)) {
            LinkKind.HLS -> setMimeType(MimeTypes.APPLICATION_M3U8)
            LinkKind.DASH -> setMimeType(MimeTypes.APPLICATION_MPD)
            else -> Unit
        }
    }.build()
    // Resuming steps back a few seconds, so the last line before the stop is heard again.
    val start = if (playback.startMs > 30_000) playback.startMs - 4_000 else playback.startMs
    val audio = playback.audioUri
    if (audio != null) {
        // YouTube keeps picture and sound apart above 360p; they play as one.
        val picture = sources.createMediaSource(item)
        val sound = sources.createMediaSource(MediaItem.fromUri(Uri.parse(audio)))
        player.setMediaSource(androidx.media3.exoplayer.source.MergingMediaSource(true, picture, sound), start)
    } else {
        player.setMediaItem(item, start)
    }
    player.prepare()
    player.playWhenReady = true
    return player
}

/** Sites label subtitle files by language name; the player wants a code to remember the choice. */
private fun languageOf(label: String): String? {
    val l = label.lowercase()
    return when {
        "рус" in l || "russian" in l -> "ru"
        "укр" in l || "ukrain" in l -> "uk"
        "англ" in l || "english" in l || "eng" == l -> "en"
        else -> null
    }
}

@OptIn(UnstableApi::class)
private fun trackOptions(player: Player, tracks: Tracks, type: Int, onChosen: (String?) -> Unit): List<Option> {
    val options = mutableListOf<Option>()
    var n = 0
    tracks.groups.filter { it.type == type }.forEach { group ->
        for (i in 0 until group.length) {
            if (!group.isTrackSupported(i)) continue
            n++
            val format = group.getTrackFormat(i)
            val selected = group.isTrackSelected(i) && !(type == C.TRACK_TYPE_TEXT && textDisabled(player))
            options += Option(trackLabel(format, n, type), selected) {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(type, false)
                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                    .build()
                onChosen(format.language)
            }
        }
    }
    return options
}

@OptIn(UnstableApi::class)
private fun qualityOptions(player: Player, tracks: Tracks, onLabel: (String) -> Unit): List<Option> {
    val overridden = player.trackSelectionParameters.overrides.keys.any { it.type == C.TRACK_TYPE_VIDEO }
    val limit = app.couchy.QualityChoice.maxHeight
    val auto = Option(if (limit != null) "Авто, до ${heightLabel(limit)}" else "Авто", !overridden) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO).build()
        onLabel("Авто")
    }
    val variants = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }.flatMap { group ->
        (0 until group.length).filter { group.isTrackSupported(it) }.map { i -> Triple(group, i, group.getTrackFormat(i)) }
    }.sortedByDescending { it.third.height }.distinctBy { it.third.height }
    // One file, one picture: say so instead of offering a choice that changes nothing.
    if (variants.size <= 1) {
        val only = variants.firstOrNull()?.third?.let(::videoLabel) ?: "одно"
        return listOf(Option("Качество $only: у этого источника других нет. Выберите другую раздачу или источник.", true) {})
    }
    return listOf(auto) + variants.map { (group, i, format) ->
        val label = videoLabel(format)
        Option(label, overridden && group.isTrackSelected(i)) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i)).build()
            onLabel(label)
        }
    }
}

@OptIn(UnstableApi::class)
private fun qualityLabel(player: Player): String {
    val overridden = player.trackSelectionParameters.overrides.keys.any { it.type == C.TRACK_TYPE_VIDEO }
    val height = player.videoSize.height
    return when {
        !overridden && height > 0 -> "Авто · ${heightLabel(height)}"
        height > 0 -> heightLabel(height)
        else -> "Авто"
    }
}

private fun heightLabel(height: Int) = when {
    height >= 2000 -> "4K"
    height >= 1400 -> "1440p"
    else -> "${height}p"
}

private fun videoLabel(format: Format): String {
    val res = if (format.height > 0) heightLabel(format.height) else "Видео"
    val rate = if (format.bitrate > 0) " · ${"%.1f".format(format.bitrate / 1_000_000f)} Мбит/с" else ""
    return res + rate
}

private fun trackLabel(format: Format, n: Int, type: Int): String {
    val language = format.language?.takeIf { it != C.LANGUAGE_UNDETERMINED && it.isNotBlank() }
        ?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale("ru")).replaceFirstChar { c -> c.uppercase() } }
    val name = format.label?.takeIf { it.isNotBlank() }
    val base = listOfNotNull(name, language?.takeIf { it != name }).joinToString(" · ")
        .ifEmpty { if (type == C.TRACK_TYPE_AUDIO) "Дорожка $n" else "Субтитры $n" }
    val channels = if (type == C.TRACK_TYPE_AUDIO && format.channelCount >= 6) " · 5.1" else ""
    return base + channels
}

private fun textDisabled(player: Player) = C.TRACK_TYPE_TEXT in player.trackSelectionParameters.disabledTrackTypes

private fun currentLanguage(player: Player, type: Int): String? =
    player.currentTracks.groups.firstOrNull { it.type == type && it.isSelected }?.let { group ->
        (0 until group.length).firstOrNull { group.isTrackSelected(it) }?.let { group.getTrackFormat(it).language }
    }

private fun currentAudioLanguage(player: Player) = currentLanguage(player, C.TRACK_TYPE_AUDIO)

private fun currentTextLanguage(player: Player): String? =
    if (textDisabled(player)) "" else currentLanguage(player, C.TRACK_TYPE_TEXT)

private fun speedLabel(speed: Float) = (if (speed % 1f == 0f) "${speed.toInt()}" else "$speed").replace('.', ',') + "×"

private fun errorText(error: PlaybackException): String = when (error.errorCode) {
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Нет соединения с сервером. Проверьте интернет."
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "Сервер не отдал видео. Возможно, ссылка устарела."
    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "Файл не найден."
    PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> "Нет доступа к файлу. Откройте его заново."
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> "Это не видео или формат не поддерживается."
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "Устройство не умеет декодировать это видео."
    else -> "Видео не загрузилось. Попробуйте другой источник или повторите."
}

