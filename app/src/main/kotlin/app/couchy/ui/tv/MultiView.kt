package app.couchy.ui.tv

import android.content.Context
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.couchy.tv.ChannelEntity
import app.couchy.tv.ChannelNow
import app.couchy.ui.components.Chip
import app.couchy.ui.player.ChannelDrawer
import app.couchy.ui.player.LiveChoice

/**
 * Several channels at once: two side by side or four in a grid. The one in focus has the sound;
 * OK picks another channel for it, holding OK (or the menu key) opens it full screen.
 * Each picture is kept small so a TV box can decode them all.
 */
@Composable
fun MultiView(
    channels: List<ChannelNow>,
    groups: List<String>,
    group: String,
    inGroup: (String) -> List<ChannelNow>,
    start: List<ChannelEntity>,
    onFull: (ChannelEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val context = LocalContext.current
        var count by remember { mutableIntStateOf(MultiPrefs.count(context)) }
        val picked = remember { mutableStateListOf<ChannelEntity?>().apply { addAll(List(4) { start.getOrNull(it) }) } }
        var sound by remember { mutableIntStateOf(0) }
        var choosing by remember { mutableStateOf<Int?>(null) }
        val firstTile = remember { FocusRequester() }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Мультиэкран", style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, softWrap = false, modifier = Modifier.weight(1f))
                Chip("2 экрана", count == 2, onClick = { count = 2; MultiPrefs.setCount(context, 2); if (sound > 1) sound = 0 })
                Chip("4 экрана", count == 4, onClick = { count = 4; MultiPrefs.setCount(context, 4) })
            }
            val rows = if (count == 2) listOf(listOf(0, 1)) else listOf(listOf(0, 1), listOf(2, 3))
            Column(Modifier.weight(1f).fillMaxWidth().padding(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                rows.forEach { row ->
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.forEach { i ->
                            Tile(
                                channel = picked[i],
                                loud = sound == i,
                                modifier = Modifier.weight(1f).fillMaxSize().then(if (i == 0) Modifier.focusRequester(firstTile) else Modifier),
                                onFocus = { sound = i },
                                onPick = { sound = i; choosing = i },
                                onFull = { picked[i]?.let(onFull) },
                            )
                        }
                    }
                }
            }
        }
        LaunchedEffect(Unit) { runCatching { firstTile.requestFocus() } }
        choosing?.let { i ->
            ChannelDrawer(
                groups = groups,
                group = group,
                channels = { g -> inGroup(g).map { LiveChoice(it.channel.id, it.number, it.channel.name, it.now?.title) } },
                currentId = picked[i]?.id,
                onPick = { id, g -> inGroup(g).firstOrNull { it.channel.id == id }?.let { picked[i] = it.channel } },
                onClose = { choosing = null },
            )
        }
        }
    }
}

@Composable
private fun Tile(channel: ChannelEntity?, loud: Boolean, modifier: Modifier, onFocus: () -> Unit, onPick: () -> Unit, onFull: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier
            .onFocusChanged { if (it.isFocused) onFocus() }
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { if (e.nativeKeyEvent.isLongPress || e.nativeKeyEvent.repeatCount > 0) onFull() else onPick(); true }
                    Key.Menu -> { onFull(); true }
                    else -> false
                }
            }
            .focusable(interactionSource = interaction)
            .pointerInput(Unit) { detectTapGestures(onTap = { if (loud) onPick() else onFocus() }, onDoubleTap = { onFull() }, onLongPress = { onPick() }) }
            .background(Color(0xFF111114), shape)
            .border(if (focused || loud) 3.dp else 1.dp, if (focused || loud) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.1f), shape),
        contentAlignment = Alignment.Center,
    ) {
        if (channel == null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.Add, null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(36.dp))
                Text("Выбрать канал", color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelLarge)
            }
        } else {
            TilePlayer(channel, loud)
            Row(
                Modifier.align(Alignment.BottomStart).padding(8.dp).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (loud) Icon(Icons.AutoMirrored.Rounded.VolumeUp, "Звук", tint = Color.White, modifier = Modifier.size(16.dp))
                Text(channel.name, color = Color.White, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** One channel's picture; muted unless [loud]. */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun TilePlayer(channel: ChannelEntity, loud: Boolean) {
    val context = LocalContext.current
    val player = remember(channel.id) { tilePlayer(context, channel) }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(player, loud) { player.volume = if (loud) 1f else 0f }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                keepScreenOn = true
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                this.player = player
            }
        },
        update = { it.player = player },
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * A light player for a tile: no audio focus (the tiles would pause each other), and pictures
 * kept to SD so four decode at once.
 */
@androidx.annotation.OptIn(UnstableApi::class)
private fun tilePlayer(context: Context, channel: ChannelEntity): ExoPlayer {
    val agent = channel.headers.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value
    val http = DefaultHttpDataSource.Factory()
        .setUserAgent(agent ?: "Couchy (Android)")
        .setDefaultRequestProperties(channel.headers.filterKeys { !it.equals("User-Agent", ignoreCase = true) })
        .setAllowCrossProtocolRedirects(true)
    val player = ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http)))
        .build()
    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setMaxVideoSizeSd().build()
    player.repeatMode = Player.REPEAT_MODE_OFF
    player.volume = 0f
    player.setMediaItem(MediaItem.fromUri(channel.url))
    player.prepare()
    player.playWhenReady = true
    // A live stream that drops is started again.
    player.addListener(object : Player.Listener {
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            player.seekToDefaultPosition()
            player.prepare()
        }
    })
    return player
}

private object MultiPrefs {
    private fun prefs(context: Context) = context.getSharedPreferences("tv", Context.MODE_PRIVATE)
    fun count(context: Context) = prefs(context).getInt("multi_count", 4)
    fun setCount(context: Context, n: Int) = prefs(context).edit().putInt("multi_count", n).apply()
}

