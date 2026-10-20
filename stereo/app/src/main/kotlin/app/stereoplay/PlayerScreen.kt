package app.stereoplay

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import kotlinx.coroutines.delay
import java.util.Locale

private enum class Panel { NONE, STEREO, AUDIO }

@Composable
fun PlayerScreen(activity: MainActivity, video: Video, onBack: () -> Unit) {
    val player = activity.player
    val engine = activity.engine
    var params by remember { mutableStateOf(activity.prefs.params()) }
    val view = remember { StereoView(activity, engine) { surface -> player.setVideoSurface(surface) } }

    var controls by remember { mutableStateOf(true) }
    var panel by remember { mutableStateOf(Panel.NONE) }
    var playing by remember { mutableStateOf(player.isPlaying) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var seeking by remember { mutableStateOf<Float?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var stats by remember { mutableStateOf("") }
    var lastTouch by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var depthFps by remember { mutableFloatStateOf(0f) }
    val playFocus = remember { FocusRequester() }

    fun touch() { lastTouch = System.currentTimeMillis() }
    fun seekBy(ms: Long) {
        player.seekTo((player.currentPosition + ms).coerceAtLeast(0L))
        engine.resetTemporal()
        touch()
    }

    LaunchedEffect(params) {
        view.renderer.params = params
        activity.prefs.save(params)
        if (params.needsDepth) engine.load(params.model)
        view.requestRender()
    }
    LaunchedEffect(video.uri) {
        view.renderer.resetDepth()
        error = null
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(size: VideoSize) {
                if (size.width > 0 && size.height > 0) {
                    view.renderer.videoWidth = (size.width * size.pixelWidthHeightRatio).toInt()
                    view.renderer.videoHeight = size.height
                    view.requestRender()
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlayerError(e: PlaybackException) { error = "Не удалось воспроизвести: ${e.errorCodeName}" }
            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) engine.resetTemporal()
            }
        }
        player.addListener(listener)
        player.videoSize.let { listener.onVideoSizeChanged(it) }
        engine.onResult = { view.requestRender() }
        onDispose {
            player.removeListener(listener)
            engine.onResult = null
            player.clearVideoSurface()
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_PAUSE -> view.onPause()
                Lifecycle.Event.ON_RESUME -> view.onResume()
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(Unit) {
        var tick = 0
        while (true) {
            delay(500)
            position = player.currentPosition
            duration = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
            depthFps = engine.fps
            stats = buildString {
                append(String.format(Locale.US, "Видео %.0f к/с", view.renderer.fps))
                if (params.needsDepth) {
                    when (engine.state) {
                        DepthEngine.State.LOADING -> append(" · загружаю нейросеть…")
                        DepthEngine.State.FAILED -> append(" · нейросеть: ").append(engine.error ?: "ошибка")
                        else -> append(String.format(Locale.US, " · глубина %.1f к/с, %d мс", engine.fps, engine.lastMs))
                    }
                }
            }
            if (++tick % 10 == 0) activity.savePosition()
            if (controls && panel == Panel.NONE && playing && System.currentTimeMillis() - lastTouch > 4000) controls = false
        }
    }
    LaunchedEffect(controls) {
        if (controls) runCatching { delay(50); playFocus.requestFocus() }
    }

    BackHandler {
        when {
            panel != Panel.NONE -> panel = Panel.NONE
            else -> onBack()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                touch()
                when (ev.key) {
                    Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> {
                        if (player.isPlaying) player.pause() else player.play(); true
                    }
                    Key.MediaFastForward -> { seekBy(30_000); true }
                    Key.MediaRewind -> { seekBy(-30_000); true }
                    else -> if (!controls && panel == Panel.NONE) {
                        when (ev.key) {
                            Key.DirectionLeft -> { seekBy(-10_000); true }
                            Key.DirectionRight -> { seekBy(10_000); true }
                            Key.DirectionCenter, Key.Enter, Key.DirectionUp, Key.DirectionDown -> { controls = true; true }
                            else -> false
                        }
                    } else false
                }
            },
    ) {
        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
        Box(
            Modifier
                .fillMaxSize()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    if (panel != Panel.NONE) panel = Panel.NONE else controls = !controls
                    touch()
                },
        )

        if (controls) {
            // Top bar: back, title, live numbers.
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(video.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stats, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, maxLines = 1)
                }
            }
            // Bottom bar: transport, seek, 3D and sound.
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                val dur = duration.coerceAtLeast(1L)
                Slider(
                    value = seeking ?: (position.toFloat() / dur).coerceIn(0f, 1f),
                    onValueChange = { seeking = it; touch() },
                    onValueChangeFinished = {
                        seeking?.let { player.seekTo((it * dur).toLong()); engine.resetTemporal() }
                        seeking = null
                    },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { seekBy(-10_000) }) { Icon(Icons.Filled.Replay10, "Назад 10 с", tint = Color.White) }
                    IconButton(
                        onClick = { if (player.isPlaying) player.pause() else player.play(); touch() },
                        modifier = Modifier.focusRequester(playFocus),
                    ) {
                        Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Пауза", tint = Color.White)
                    }
                    IconButton(onClick = { seekBy(10_000) }) { Icon(Icons.Filled.Forward10, "Вперёд 10 с", tint = Color.White) }
                    Text(
                        time(seeking?.let { (it * dur).toLong() } ?: position) + " / " + time(duration),
                        color = Color.White,
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.weight(1f))
                    FilledTonalButton(onClick = { panel = Panel.STEREO; touch() }) {
                        Icon(Icons.Filled.ViewInAr, null)
                        Spacer(Modifier.width(6.dp))
                        Text(params.layout.label, maxLines = 1)
                    }
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = { panel = Panel.AUDIO; touch() }) {
                        Icon(Icons.Filled.Audiotrack, "Звук", tint = Color.White)
                    }
                }
            }
        }

        error?.let {
            Text(
                it,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0xCC401020), RoundedCornerShape(12.dp))
                    .padding(16.dp),
            )
        }

        when (panel) {
            Panel.STEREO -> SidePanel(Modifier.align(Alignment.CenterEnd)) {
                StereoPanel(params) { params = it; touch() }
            }
            Panel.AUDIO -> SidePanel(Modifier.align(Alignment.CenterEnd)) {
                AudioPanel(player) { panel = Panel.NONE }
            }
            Panel.NONE -> Unit
        }
    }
}

@Composable
private fun SidePanel(modifier: Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .fillMaxHeight()
            .width(340.dp)
            .background(Color(0xE6101420))
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

@Composable
private fun StereoPanel(p: StereoParams, onChange: (StereoParams) -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { delay(50); first.requestFocus() } }
    Heading("Вид")
    Layout.entries.forEachIndexed { i, l ->
        Choice(l.label, p.layout == l, if (i == 0) Modifier.focusRequester(first) else Modifier) { onChange(p.copy(layout = l)) }
    }
    Heading("Объём")
    Stepper(String.format(Locale("ru"), "%.1f %%", p.strength * 100)) { d ->
        onChange(p.copy(strength = (p.strength + d * 0.005f).coerceIn(0f, 0.10f)))
    }
    Heading("Вылет из экрана")
    Stepper("${(p.popout * 100).toInt()} %") { d ->
        onChange(p.copy(popout = (p.popout + d * 0.05f).coerceIn(0f, 0.6f)))
    }
    Heading("Чёткость глубины")
    Text("Выше чёткость — медленнее и горячее телефон", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
    DepthDetail.entries.forEach { d -> Choice("${d.label} (${d.shortSide})", p.detail == d) { onChange(p.copy(detail = d)) } }
    Heading("Нейросеть")
    DepthModel.entries.forEach { m -> Choice(m.label, p.model == m) { onChange(p.copy(model = m)) } }
    Heading("Глаза")
    Choice("Поменять левый и правый местами", p.swapEyes) { onChange(p.copy(swapEyes = !p.swapEyes)) }
}

@Composable
private fun AudioPanel(player: Player, onDone: () -> Unit) {
    Heading("Звуковая дорожка")
    val groups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
    if (groups.isEmpty()) Text("Дорожек нет", color = Color.White.copy(alpha = 0.6f))
    groups.forEachIndexed { gi, g: Tracks.Group ->
        for (t in 0 until g.length) {
            if (!g.isTrackSupported(t)) continue
            val f = g.getTrackFormat(t)
            val name = listOfNotNull(
                f.label,
                f.language?.let { Locale(it).getDisplayLanguage(Locale("ru")) },
                f.channelCount.takeIf { it > 0 }?.let { "$it кан." },
                f.sampleMimeType?.substringAfter('/')?.uppercase(),
            ).distinct().joinToString(" · ").ifEmpty { "Дорожка ${gi + 1}" }
            Choice(name, g.isTrackSelected(t)) {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, t))
                    .build()
                onDone()
            }
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
}

@Composable
private fun Choice(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    if (selected) {
        FilledTonalButton(onClick = onClick, modifier = modifier.fillMaxWidth()) { Text("✓ $text", maxLines = 1) }
    } else {
        TextButton(onClick = onClick, modifier = modifier.fillMaxWidth()) {
            Text(text, color = Color.White, maxLines = 1, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun Stepper(value: String, onStep: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = { onStep(-1) }) { Text("−", fontSize = 18.sp) }
        Text(value, color = Color.White, fontSize = 16.sp, modifier = Modifier.width(80.dp))
        OutlinedButton(onClick = { onStep(1) }) { Text("+", fontSize = 18.sp) }
    }
}

private fun time(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec) else String.format(Locale.US, "%d:%02d", m, sec)
}
