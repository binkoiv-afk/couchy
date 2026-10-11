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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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

private enum class Sheet { TRACKS, QUALITY, SPEED, SLEEP, INTRO, FIND_SUBS }

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
) {
    val context = LocalContext.current
    val player = remember(playback.uri, playback.subtitles) { buildPlayer(context, playback) }

    var ui by remember(playback.uri) { mutableStateOf(PlayerUi(playback.title)) }
    var tracks by remember(playback.uri) { mutableStateOf(Tracks.EMPTY) }
    var overlay by remember { mutableStateOf(true) }
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
    // The series' opening titles, marked once and jumped over in every episode after.
    val marks = (context.applicationContext as app.couchy.CouchyApp).marks
    val series = remember(playback.key) { app.couchy.data.Intros.seriesOf(playback) }
    var intro by remember(series) { mutableStateOf(series?.let { app.couchy.data.Intros.get(marks, it) }) }
    var introFrom by remember(playback.key) { mutableStateOf<Long?>(null) }
    var introSkipped by remember(playback.key) { mutableStateOf(false) }
    // Subtitles looked up on the internet for this video.
    var findSubs by remember { mutableStateOf(false) }
    var subHits by remember(playback.key) { mutableStateOf<List<app.couchy.core.sources.SubHit>?>(null) }
    var subError by remember(playback.key) { mutableStateOf<String?>(null) }
    val subScope = rememberCoroutineScope()
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
    androidx.compose.runtime.DisposableEffect(view) {
        val window = (view.context as? android.app.Activity)?.window
        val bars = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
        bars?.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        onDispose {
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

    // Sleep timer "to the end of the episode" and "stay on the credits" both stop here instead of going on.
    fun finished() { if (sleepAfterEpisode || nextCancelled) onExit() else onFinished() }

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
                    finished()
                }
            }

            override fun onTracksChanged(t: Tracks) {
                tracks = t
                ui = ui.copy(qualityLabel = playback.variants.firstOrNull { it.second == playback.uri }?.first ?: qualityLabel(player))
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

    // Phones turn sideways for video, and the rotate button stands them up again; tablets and TVs keep whatever they have.
    val phone = touch && context.resources.configuration.smallestScreenWidthDp < 600
    var upright by remember { mutableStateOf(false) }
    DisposableEffect(touch) {
        val activity = context as? Activity
        val before = activity?.requestedOrientation
        onDispose { if (before != null) activity?.requestedOrientation = before }
    }
    androidx.compose.runtime.LaunchedEffect(upright, phone) {
        if (phone) (context as? Activity)?.requestedOrientation =
            if (upright) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
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
            // Sponsor reads and the like: jump to their end once, the first time playback reaches them.
            if (player.isPlaying) {
                val at = player.currentPosition
                playback.skips.firstOrNull { at in it.startMs until it.endMs - 500 && it !in skipped }?.let { s ->
                    skipped += s
                    player.seekTo(s.endMs)
                    flash(s.label)
                }
                intro?.let { i ->
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

    val remaining = ui.durationMs - ui.positionMs
    val nextIn = if (hasNext && playback.live == null && !nextCancelled && !sleepAfterEpisode && ui.durationMs > 60_000 && remaining in 0..15_000) {
        ((remaining + 999) / 1000).toInt()
    } else null

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
        onRotate = if (phone) ({ poke(); upright = !upright }) else null,
        onSleep = { poke(); sheet = Sheet.SLEEP },
        onIntro = if (series != null) ({ poke(); sheet = Sheet.INTRO }) else null,
        onDownload = onDownload?.let { d -> { poke(); d(); flash("Скачивается. Найдёте в «Моё» → «Загрузки»") } },
        onFavorite = onFavorite?.let { f -> { poke(); f() } },
        onPrevious = onPrevious?.let { f -> { poke(); f() } },
        onNextNow = { save(); onFinished() },
        onNextCancel = { nextCancelled = true; poke() },
        onCast = onCast?.let { cast ->
            {
                poke()
                save()
                cast(player.currentPosition, player.duration.takeIf { it != C.TIME_UNSET } ?: 0)
            }
        },
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
                    this.player = player
                }
            },
            update = { view ->
                view.player = player
                view.resizeMode = Resize.entries[resize].mode
            },
            modifier = Modifier.fillMaxSize(),
        )
        PlayerOverlay(
            ui.copy(
                live = live,
                status = status,
                hint = if (boosting) "2× пока держите" else hint,
                nextIn = nextIn,
                sleepLabel = when {
                    sleepAfterEpisode -> "До конца серии"
                    sleepAt != null -> "Сон в " + java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(java.util.Date(sleepAt!!))
                    else -> null
                },
            ),
            overlay, controls, touch,
        )
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
                    ) + trackOptions(player, tracks, C.TRACK_TYPE_TEXT) { lang -> onTracksChosen(currentAudioLanguage(player), lang) } +
                        (if (onSubtitle != null && playback.live == null) listOf(Option("Найти в интернете…", false) { findSubs = true }) else emptyList()),
                ),
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
        Sheet.INTRO -> {
            val at = player.currentPosition
            val from = introFrom
            val known = intro
            OptionsDialog(
                "Заставка сериала",
                listOf(
                    OptionSection(
                        known?.let { "ПРОПУСКАЕТСЯ: ${clock(it.startMs)} – ${clock(it.endMs)}" }
                            ?: "Отметьте начало и конец заставки один раз, дальше Couchy будет пропускать её в каждой серии",
                        buildList {
                            if (from == null) {
                                add(Option("Заставка начинается здесь · ${clock(at)}", false) { introFrom = at; flash("Начало отмечено. Перемотайте на конец заставки") })
                            } else {
                                add(Option("Заставка кончается здесь · ${clock(at)}", false) {
                                    if (at > from + 5_000 && series != null) {
                                        val marked = app.couchy.data.Intro(from, at)
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
                            if (known != null && series != null) {
                                add(Option("Забыть заставку", false) {
                                    app.couchy.data.Intros.set(marks, series, null)
                                    intro = null
                                    flash("Заставка больше не пропускается")
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
private fun buildPlayer(context: Context, playback: Playback): ExoPlayer {
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
    val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
    val player = ExoPlayer.Builder(context, renderers)
        .setMediaSourceFactory(sources)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        .setHandleAudioBecomingNoisy(true)
        .build()

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
    else -> error.errorCodeName.lowercase().replace('_', ' ')
}

