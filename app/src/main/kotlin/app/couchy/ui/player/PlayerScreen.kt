package app.couchy.ui.player

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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import java.util.Locale
import kotlin.math.abs

private val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

private enum class Resize(val label: String, val mode: Int) {
    FIT("Вписать", AspectRatioFrameLayout.RESIZE_MODE_FIT),
    ZOOM("Заполнить", AspectRatioFrameLayout.RESIZE_MODE_ZOOM),
    STRETCH("Растянуть", AspectRatioFrameLayout.RESIZE_MODE_FILL),
}

private enum class Sheet { TRACKS, QUALITY, SPEED }

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
    /** Torrent peers and speed for the buffering spinner. */
    status: String? = null,
    /** Called when the video plays to the end; by default the player closes. */
    onFinished: () -> Unit = onExit,
) {
    val context = LocalContext.current
    val player = remember(playback.uri) { buildPlayer(context, playback) }

    var ui by remember(playback.uri) { mutableStateOf(PlayerUi(playback.title)) }
    var tracks by remember(playback.uri) { mutableStateOf(Tracks.EMPTY) }
    var overlay by remember { mutableStateOf(true) }
    var lastTouch by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var speed by remember { mutableFloatStateOf(1f) }
    var resize by remember { mutableIntStateOf(0) }
    val rootFocus = remember { FocusRequester() }

    fun poke() { lastTouch = System.currentTimeMillis(); overlay = true }

    fun save() {
        if (playback.live != null) return
        val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: 0
        if (player.currentPosition > 0) onProgress(player.currentPosition, duration)
    }

    fun exit() { save(); onExit() }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) {
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
                    onFinished()
                }
            }

            override fun onTracksChanged(t: Tracks) {
                tracks = t
                ui = ui.copy(qualityLabel = qualityLabel(player))
            }

            override fun onPlayerError(error: PlaybackException) {
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

    // Phones turn sideways for video; tablets and TVs keep whatever they have.
    DisposableEffect(touch) {
        val activity = context as? Activity
        val before = activity?.requestedOrientation
        if (touch && context.resources.configuration.smallestScreenWidthDp < 600) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        onDispose { if (before != null) activity?.requestedOrientation = before }
    }

    // Leaving the app pauses playback and saves the spot; coming back does not auto-play.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, player) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { player.pause(); save() }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(player) {
        var sinceSave = 0L
        while (true) {
            val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: 0
            ui = ui.copy(positionMs = player.currentPosition, durationMs = duration, bufferedMs = player.bufferedPosition)
            delay(500)
            sinceSave += 500
            if (sinceSave >= 5_000 && player.isPlaying) { save(); sinceSave = 0 }
        }
    }

    LaunchedEffect(overlay, lastTouch, ui.playing, sheet) {
        if (overlay && ui.playing && sheet == null && ui.error == null) {
            delay(4_000)
            overlay = false
        }
    }
    LaunchedEffect(overlay) { if (!overlay) runCatching { rootFocus.requestFocus() } }
    // A new channel shows its name and programme for a moment.
    LaunchedEffect(playback.uri) { if (playback.live != null) poke() }

    BackHandler {
        when {
            sheet != null -> sheet = null
            overlay && ui.playing && ui.error == null -> overlay = false
            else -> exit()
        }
    }

    val controls = PlayerControls(
        onBack = ::exit,
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
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) lastTouch = System.currentTimeMillis()
                // Media keys work whatever has focus.
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.MediaPlayPause -> { controls.onTogglePlay(); true }
                    Key.MediaPlay -> { player.play(); poke(); true }
                    Key.MediaPause -> { player.pause(); poke(); true }
                    Key.MediaFastForward -> { controls.onSeekBy(30_000); true }
                    Key.MediaRewind -> { controls.onSeekBy(-30_000); true }
                    Key.ChannelUp -> { onZap?.invoke(1); onZap != null }
                    Key.ChannelDown -> { onZap?.invoke(-1); onZap != null }
                    else -> false
                }
            }
            .focusRequester(rootFocus)
            // With the overlay hidden, the remote controls playback directly.
            .onKeyEvent { event ->
                if (overlay || event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { controls.onTogglePlay(); true }
                    Key.DirectionLeft -> { controls.onSeekBy(-10_000); true }
                    Key.DirectionRight -> { controls.onSeekBy(10_000); true }
                    Key.DirectionUp -> { if (onZap != null) onZap(-1) else poke(); true }
                    Key.DirectionDown -> { if (onZap != null) onZap(1) else poke(); true }
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
                    this.player = player
                }
            },
            update = { view ->
                view.player = player
                view.resizeMode = Resize.entries[resize].mode
            },
            modifier = Modifier.fillMaxSize(),
        )
        PlayerOverlay(ui.copy(live = live, status = status), overlay, controls, touch)
    }

    when (sheet) {
        Sheet.TRACKS -> OptionsDialog(
            "Озвучка и субтитры",
            listOf(
                OptionSection("ОЗВУЧКА", trackOptions(player, tracks, C.TRACK_TYPE_AUDIO) { lang -> onTracksChosen(lang, currentTextLanguage(player)) }),
                OptionSection(
                    "СУБТИТРЫ",
                    listOf(
                        Option("Выключены", textDisabled(player)) {
                            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                            onTracksChosen(currentAudioLanguage(player), "")
                        },
                    ) + trackOptions(player, tracks, C.TRACK_TYPE_TEXT) { lang -> onTracksChosen(currentAudioLanguage(player), lang) },
                ),
            ),
            onDismiss = { sheet = null },
        )
        Sheet.QUALITY -> OptionsDialog(
            "Качество",
            listOf(OptionSection(null, qualityOptions(player, tracks) { ui = ui.copy(qualityLabel = it) })),
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
        null -> Unit
    }
}

@OptIn(UnstableApi::class)
private fun buildPlayer(context: Context, playback: Playback): ExoPlayer {
    val agent = playback.headers.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value
    val http = DefaultHttpDataSource.Factory()
        .setUserAgent(agent ?: "Couchy/${BuildConfig.VERSION_NAME} (Android)")
        .setDefaultRequestProperties(playback.headers.filterKeys { !it.equals("User-Agent", ignoreCase = true) })
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(20_000)
    val sources = DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http))
    // Cheap TV boxes often have a broken first decoder; falling back to the next one plays the video anyway.
    val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
    val player = ExoPlayer.Builder(context, renderers)
        .setMediaSourceFactory(sources)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        .setHandleAudioBecomingNoisy(true)
        .build()

    val params = player.trackSelectionParameters.buildUpon()
        .setPreferredAudioLanguage(playback.audioLanguage ?: Locale.getDefault().language)
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
    player.setMediaItem(item, playback.startMs)
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
    val auto = Option("Авто", !overridden) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO).build()
        onLabel("Авто")
    }
    val variants = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }.flatMap { group ->
        (0 until group.length).filter { group.isTrackSupported(it) }.map { i -> Triple(group, i, group.getTrackFormat(i)) }
    }.sortedByDescending { it.third.height }
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
    else -> error.errorCodeName.lowercase().replace('_', ' ')
}

