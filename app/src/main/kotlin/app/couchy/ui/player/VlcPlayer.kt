package app.couchy.ui.player

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.couchy.Playback
import app.couchy.ui.components.CouchySheet
import app.couchy.ui.components.SecondaryButton
import app.couchy.ui.components.SheetHeader
import app.couchy.ui.components.SheetRow
import kotlinx.coroutines.delay
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout

/** Which player plays videos: Couchy's own (Media3), or VLC for every codec. */
object PlayerEngine {
    private fun prefs(c: Context) = c.getSharedPreferences("player", Context.MODE_PRIVATE)
    fun vlc(c: Context) = prefs(c).getBoolean("vlc", false)
    fun setVlc(c: Context, on: Boolean) = prefs(c).edit().putBoolean("vlc", on).apply()

    /** One VLC for the app: starting it reads its plugins, which takes a moment. */
    @Volatile private var lib: LibVLC? = null
    fun lib(c: Context): LibVLC = lib ?: synchronized(this) {
        lib ?: LibVLC(
            c.applicationContext,
            arrayListOf("--network-caching=2000", "--live-caching=2000", "--http-reconnect", "--audio-time-stretch", "--sub-text-scale=110"),
        ).also { lib = it }
    }
}

/**
 * The same video in VLC: for codecs and containers the device or Media3 cannot play (old AVI,
 * WMV, some HEVC and DTS on cheap boxes and projectors). Plain controls that work with a remote:
 * OK pauses, left and right jump 10 seconds, Back leaves.
 */
@Composable
fun VlcPlayerScreen(
    playback: Playback,
    touch: Boolean,
    onProgress: (positionMs: Long, durationMs: Long) -> Unit,
    onExit: () -> Unit,
    onFinished: () -> Unit,
    /** Back to Couchy's own player; null where that does not apply. */
    onCouchyPlayer: ((positionMs: Long) -> Unit)?,
) {
    val context = LocalContext.current
    val player = remember(playback.key, playback.uri) { MediaPlayer(PlayerEngine.lib(context)) }
    var position by remember { mutableLongStateOf(playback.startMs) }
    var length by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var overlay by remember { mutableStateOf(true) }
    var poked by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var tracks by remember { mutableStateOf(false) }
    val finished by rememberUpdatedState(onFinished)
    fun poke() { overlay = true; poked = System.currentTimeMillis() }
    fun save() { if (playback.live == null && player.time > 0) onProgress(player.time, player.length) }
    fun leave() { save(); onExit() }

    DisposableEffect(player) {
        val media = Media(PlayerEngine.lib(context), Uri.parse(playback.uri)).apply {
            setHWDecoderEnabled(true, false)
            playback.headers.forEach { (k, v) ->
                when {
                    k.equals("User-Agent", true) -> addOption(":http-user-agent=$v")
                    k.equals("Referer", true) -> addOption(":http-referrer=$v")
                }
            }
            if (playback.startMs > 0 && playback.live == null) addOption(":start-time=${playback.startMs / 1000}")
            playback.audioUri?.let { addSlave(IMedia.Slave(IMedia.Slave.Type.Audio, 4, it)) }
        }
        player.media = media
        media.release()
        playback.subtitles.firstOrNull()?.let { (_, url) -> runCatching { player.addSlave(IMedia.Slave.Type.Subtitle, Uri.parse(url), false) } }
        player.setEventListener { e ->
            when (e.type) {
                MediaPlayer.Event.Playing -> { playing = true; buffering = false; error = null }
                MediaPlayer.Event.Paused, MediaPlayer.Event.Stopped -> playing = false
                MediaPlayer.Event.Buffering -> buffering = e.buffering < 100f
                MediaPlayer.Event.TimeChanged -> { position = e.timeChanged; buffering = false }
                MediaPlayer.Event.LengthChanged -> length = e.lengthChanged
                MediaPlayer.Event.EndReached -> { save(); finished() }
                MediaPlayer.Event.EncounteredError -> { error = "VLC тоже не смог открыть это видео."; buffering = false }
            }
        }
        player.play()
        onDispose {
            save()
            player.setEventListener(null)
            runCatching { player.stop() }
            runCatching { player.detachViews() }
            player.release()
        }
    }
    LaunchedEffect(player) {
        while (true) {
            delay(10_000)
            save()
        }
    }
    LaunchedEffect(poked, playing) {
        delay(5_000)
        if (playing) overlay = false
    }
    BackHandler { if (overlay && playing) overlay = false else leave() }

    fun seekBy(ms: Long) {
        if (playback.live != null) return
        val to = (player.time + ms).coerceIn(0, (player.length - 1_000).coerceAtLeast(0))
        player.setTime(to)
        position = to
    }
    fun toggle() { if (player.isPlaying) player.pause() else player.play() }

    val root = remember { FocusRequester() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(root)
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || tracks) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.MediaPlayPause, Key.Spacebar ->
                        if (overlay) false else { toggle(); poke(); true }
                    Key.DirectionLeft -> if (overlay) { poke(); false } else { seekBy(-10_000); poke(); true }
                    Key.DirectionRight -> if (overlay) { poke(); false } else { seekBy(10_000); poke(); true }
                    Key.MediaFastForward -> { seekBy(30_000); poke(); true }
                    Key.MediaRewind -> { seekBy(-30_000); poke(); true }
                    else -> { poke(); false }
                }
            }
            .pointerInput(Unit) { detectTapGestures(onTap = { if (overlay) overlay = false else poke() }, onDoubleTap = { toggle() }) },
    ) {
        AndroidView(
            factory = { ctx -> VLCVideoLayout(ctx).also { layout -> player.attachViews(layout, null, true, false) } },
            modifier = Modifier.fillMaxSize(),
        )
        if (buffering && error == null) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        error?.let { text ->
            Column(
                Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.7f), MaterialTheme.shapes.large).padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(text, color = Color.White, style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    onCouchyPlayer?.let { back -> SecondaryButton("Плеер Couchy", Icons.Rounded.SwapHoriz, onClick = { back(position) }, onImage = true) }
                    SecondaryButton("Назад", Icons.AutoMirrored.Rounded.ArrowBack, onClick = ::leave, onImage = true)
                }
            }
        }
        AnimatedVisibility(overlay && error == null, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent))).padding(horizontal = 24.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(playback.title, color = Color.White, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text("VLC", color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelLarge)
                }
                Column(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))))
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (playback.live == null && length > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(clockOf(position), color = Color.White, style = MaterialTheme.typography.labelLarge)
                            Slider(
                                value = position.toFloat() / length,
                                onValueChange = { f -> poke(); position = (f * length).toLong() },
                                onValueChangeFinished = { player.setTime(position) },
                                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = MaterialTheme.colorScheme.primary, inactiveTrackColor = Color.White.copy(alpha = 0.3f)),
                                modifier = Modifier.weight(1f),
                            )
                            Text(clockOf(length), color = Color.White, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    val first = remember { FocusRequester() }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (playback.live == null) SecondaryButton(null, Icons.Rounded.Replay10, onClick = { poke(); seekBy(-10_000) }, onImage = true)
                        SecondaryButton(
                            null, if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            onClick = { poke(); toggle() }, onImage = true, modifier = Modifier.focusRequester(first),
                        )
                        if (playback.live == null) SecondaryButton(null, Icons.Rounded.Forward10, onClick = { poke(); seekBy(10_000) }, onImage = true)
                        SecondaryButton("Дорожки", Icons.Rounded.Subtitles, onClick = { poke(); tracks = true }, onImage = true)
                        onCouchyPlayer?.let { back -> SecondaryButton("Плеер Couchy", Icons.Rounded.SwapHoriz, onClick = { save(); back(player.time.coerceAtLeast(0)) }, onImage = true) }
                    }
                    LaunchedEffect(Unit) { if (!touch) runCatching { first.requestFocus() } }
                }
            }
        }
        LaunchedEffect(overlay) { if (!overlay) runCatching { root.requestFocus() } }
    }

    if (tracks) {
        CouchySheet("Дорожки", onDismiss = { tracks = false }) {
            val audio = player.audioTracks.orEmpty().filter { it.id >= 0 }
            if (audio.isNotEmpty()) {
                SheetHeader("Озвучка")
                audio.forEach { t -> SheetRow(t.name, onClick = { player.setAudioTrack(t.id); tracks = false }, selected = player.audioTrack == t.id) }
            }
            SheetHeader("Субтитры")
            SheetRow("Выключены", onClick = { player.setSpuTrack(-1); tracks = false }, selected = player.spuTrack == -1)
            player.spuTracks.orEmpty().filter { it.id >= 0 }.forEach { t ->
                SheetRow(t.name, onClick = { player.setSpuTrack(t.id); tracks = false }, selected = player.spuTrack == t.id)
            }
        }
    }
}

private fun clockOf(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) String.format(java.util.Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    else String.format(java.util.Locale.ROOT, "%d:%02d", s / 60, s % 60)
}
