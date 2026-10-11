package app.couchy.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.couchy.core.Time
import app.couchy.ui.components.couchyClickable

data class PlayerUi(
    val title: String,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val bufferedMs: Long = 0,
    val playing: Boolean = false,
    val buffering: Boolean = true,
    val error: String? = null,
    val speedLabel: String = "1×",
    val qualityLabel: String = "Авто",
    val resizeLabel: String = "Вписать",
    /** Set while watching a TV channel live; null for videos and archive recordings. */
    val live: LiveUi? = null,
    /** Torrent peers and speed, shown under the spinner while buffering. */
    val status: String? = null,
    /** A short note in the middle of the top edge: volume, brightness, 2×, a channel number being typed. */
    val hint: String? = null,
    /** Seconds until the next episode starts by itself; null when no countdown runs. */
    val nextIn: Int? = null,
    /** Set while the sleep timer runs: when it stops. */
    val sleepLabel: String? = null,
)

/** What a live channel shows instead of a timeline: the programme now and the next one. */
data class LiveUi(
    val channel: String,
    val number: Int?,
    val nowTitle: String?,
    val nowStart: Long = 0,
    val nowStop: Long = 0,
    val nextTitle: String? = null,
    val nextStart: Long = 0,
    val nowMs: Long = System.currentTimeMillis(),
    val favorite: Boolean = false,
)

class PlayerControls(
    val onBack: () -> Unit,
    val onTogglePlay: () -> Unit,
    val onSeekTo: (Long) -> Unit,
    val onSeekBy: (Long) -> Unit,
    val onTracks: () -> Unit,
    val onQuality: () -> Unit,
    val onSpeed: () -> Unit,
    val onResize: () -> Unit,
    val onRetry: () -> Unit,
    /** Any interaction, so the overlay stays up while it is being used. */
    val onInteract: () -> Unit,
    /** Hands the video to a TV box in the home network; null where there is nothing to send. */
    val onCast: (() -> Unit)? = null,
    /** Turns a phone between sideways and upright; null elsewhere. */
    val onRotate: (() -> Unit)? = null,
    val onSleep: () -> Unit = {},
    /** Marks a series' opening titles; null outside series. */
    val onIntro: (() -> Unit)? = null,
    /** Saves the video for watching offline. */
    val onDownload: (() -> Unit)? = null,
    /** Shrinks the player into a window over other apps. */
    val onPip: (() -> Unit)? = null,
    /** Stars or unstars the live channel. */
    val onFavorite: (() -> Unit)? = null,
    /** Back to the channel watched before this one. */
    val onPrevious: (() -> Unit)? = null,
    /** The next episode right now, and keeping this one to its end. */
    val onNextNow: () -> Unit = {},
    val onNextCancel: () -> Unit = {},
)

/**
 * The player chrome from the mockups: title on top, a timeline and four buttons at the bottom.
 * On a remote the play button gets focus first; on the timeline ◀ ▶ jump by 10 seconds.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun PlayerOverlay(ui: PlayerUi, visible: Boolean, controls: PlayerControls, touch: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize()) {
        if (ui.buffering && ui.error == null) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                CircularProgressIndicator(Modifier.size(56.dp), color = Color.White)
                if (ui.status != null) {
                    Text(
                        ui.status,
                        color = Color.White,
                        fontSize = 14.sp,
                        modifier = Modifier.background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
        if (ui.error != null) {
            ErrorPanel(ui.error, controls, Modifier.align(Alignment.Center))
            return@Box
        }
        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
            Chrome(ui, controls, touch)
        }
        if (ui.hint != null) {
            Text(
                ui.hint,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 72.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        ui.nextIn?.let { NextCard(it, controls, Modifier.align(Alignment.BottomEnd).padding(horizontal = 24.dp, vertical = if (visible) 120.dp else 28.dp)) }
    }
}

/** Netflix-style: the next episode is coming, start it now or stay on the credits. */
@Composable
private fun NextCard(seconds: Int, controls: PlayerControls, modifier: Modifier) {
    val now = remember { FocusRequester() }
    Column(
        modifier.background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Следующая серия через $seconds", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill(Icons.Rounded.PlayArrow, "Смотреть", Modifier.focusRequester(now), highlighted = true, onClick = controls.onNextNow)
            Pill(Icons.Rounded.Close, "Остаться", onClick = controls.onNextCancel)
        }
    }
    LaunchedEffect(Unit) { runCatching { now.requestFocus() } }
}

@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
private fun Chrome(ui: PlayerUi, controls: PlayerControls, touch: Boolean) {
    val playFocus = remember { FocusRequester() }
    androidx.compose.foundation.layout.BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.6f),
                    0.25f to Color.Transparent,
                    0.55f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.85f),
                ),
            ),
    ) {
        // A phone held upright has room for about six buttons in a row: the rest go up, next to the title.
        val upright = touch && maxWidth < 560.dp
        val tools = buildList {
            if (!touch) add(Tool(if (ui.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (ui.playing) "Пауза" else "Играть", highlighted = true, focus = playFocus, onClick = controls.onTogglePlay))
            if (touch) controls.onCast?.let { add(Tool(Icons.Rounded.Cast, "На ТВ", onClick = it)) }
            add(Tool(Icons.Rounded.Subtitles, "Озвучка", onClick = controls.onTracks))
            add(Tool(Icons.Rounded.HighQuality, ui.qualityLabel, onClick = controls.onQuality))
            if (ui.live == null) add(Tool(Icons.Rounded.Speed, ui.speedLabel, onClick = controls.onSpeed))
            add(Tool(Icons.Rounded.AspectRatio, ui.resizeLabel, onClick = controls.onResize))
            controls.onIntro?.let { add(Tool(Icons.Rounded.FastForward, "Заставка", top = true, onClick = it)) }
            controls.onDownload?.let { add(Tool(Icons.Rounded.Download, "Скачать", top = true, onClick = it)) }
            controls.onPip?.let { add(Tool(Icons.Rounded.PictureInPictureAlt, "Окно", top = true, onClick = it)) }
            add(Tool(Icons.Rounded.Bedtime, ui.sleepLabel ?: "Сон", highlighted = ui.sleepLabel != null, top = true, onClick = controls.onSleep))
            if (ui.live != null) {
                controls.onFavorite?.let { add(Tool(if (ui.live.favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder, "Избранное", highlighted = ui.live.favorite, top = true, onClick = it)) }
                controls.onPrevious?.let { add(Tool(Icons.Rounded.History, "Предыдущий", top = true, onClick = it)) }
            }
            controls.onRotate?.let { add(Tool(Icons.Rounded.ScreenRotation, "Поворот", onClick = it)) }
        }
        val bottom = if (upright) tools.filter { !it.top } else tools
        val up = if (upright) tools.filter { it.top } else emptyList()
        Row(
            Modifier.align(Alignment.TopStart).fillMaxWidth().padding(horizontal = if (upright) 8.dp else 28.dp, vertical = if (upright) 12.dp else 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (touch) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack, "Назад", tint = Color.White,
                    modifier = Modifier.size(40.dp).couchyClickable(CircleShape, onClick = controls.onBack).padding(8.dp),
                )
            }
            Text(
                ui.title,
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            up.forEach { t -> ToolButton(t, false, Modifier) }
            if (!upright && ui.durationMs > 0 && ui.live == null) {
                val endsAt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(System.currentTimeMillis() + ui.durationMs - ui.positionMs))
                Text("Закончится в $endsAt", color = Color.White.copy(alpha = 0.75f), fontSize = 14.sp)
            }
        }

        if (touch) {
            Row(
                Modifier.align(Alignment.Center),
                horizontalArrangement = Arrangement.spacedBy(56.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundIcon(Icons.Rounded.Replay10, "Назад на 10 секунд", 44.dp) { controls.onSeekBy(-10_000) }
                RoundIcon(if (ui.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Пауза", 72.dp, filled = true, onClick = controls.onTogglePlay)
                RoundIcon(Icons.Rounded.Forward10, "Вперёд на 10 секунд", 44.dp) { controls.onSeekBy(10_000) }
            }
        }

        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = if (touch) 16.dp else 28.dp, vertical = if (touch) 10.dp else 20.dp)) {
            if (ui.live == null) Text(
                "${Time.clock(ui.positionMs)} / ${if (ui.durationMs > 0) Time.clock(ui.durationMs) else "--:--"}",
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 4.dp),
            )
            if (ui.live != null) LiveInfo(ui.live, touch) else Timeline(ui, controls)
            // One row, always: the buttons share the width, with captions when there is room for them.
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                val cell = maxWidth / bottom.size.coerceAtLeast(1)
                val captions = cell >= 52.dp
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                    bottom.forEach { t -> ToolButton(t, captions, Modifier.width(cell.coerceAtMost(110.dp))) }
                }
            }
        }
    }
    LaunchedEffect(Unit) { if (!touch) runCatching { playFocus.requestFocus() } }
}

@Composable
private fun LiveInfo(live: LiveUi, touch: Boolean) {
    val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "● ПРЯМОЙ ЭФИР",
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.background(Color(0xFFE5484D), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
            )
            Text(
                listOfNotNull(live.number?.toString(), live.channel).joinToString("  "),
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            if (!touch) Text("▲ ▼ каналы", color = Color.White.copy(alpha = 0.55f), fontSize = 13.sp)
        }
        if (live.nowTitle != null) {
            Text(live.nowTitle, color = Color.White, fontSize = if (touch) 16.sp else 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(time.format(java.util.Date(live.nowStart)), color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp)
                val fraction = ((live.nowMs - live.nowStart).toFloat() / (live.nowStop - live.nowStart).coerceAtLeast(1)).coerceIn(0f, 1f)
                Box(Modifier.weight(1f).height(5.dp).background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(3.dp))) {
                    Box(Modifier.fillMaxWidth(fraction).height(5.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp)))
                }
                Text(time.format(java.util.Date(live.nowStop)), color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp)
            }
            // On a phone the next programme stays off, so the picture is not half covered.
            if (live.nextTitle != null && !touch) {
                Text(
                    "Далее в ${time.format(java.util.Date(live.nextStart))}: ${live.nextTitle}",
                    color = Color.White.copy(alpha = 0.65f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            Text(live.channel, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun Timeline(ui: PlayerUi, controls: PlayerControls) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val duration = ui.durationMs.coerceAtLeast(1)
    val value = dragging ?: (ui.positionMs.toFloat() / duration).coerceIn(0f, 1f)
    Slider(
        value = value,
        onValueChange = { dragging = it; controls.onInteract() },
        onValueChangeFinished = {
            dragging?.let { controls.onSeekTo((it * duration).toLong()) }
            dragging = null
        },
        enabled = ui.durationMs > 0,
        colors = SliderDefaults.colors(
            thumbColor = Color.White,
            activeTrackColor = MaterialTheme.colorScheme.primary,
            inactiveTrackColor = Color.White.copy(alpha = 0.3f),
        ),
        // A remote moves by 10 seconds per press instead of the slider's own tiny steps.
        modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (event.key) {
                Key.DirectionLeft -> { controls.onSeekBy(-10_000); true }
                Key.DirectionRight -> { controls.onSeekBy(10_000); true }
                else -> false
            }
        },
    )
}

@Composable
private fun Pill(icon: ImageVector, text: String?, modifier: Modifier = Modifier, highlighted: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier
            .height(42.dp)
            .couchyClickable(shape, onClick = onClick)
            .background(if (highlighted) Color.White else Color.White.copy(alpha = 0.14f), shape)
            .padding(horizontal = if (text == null) 10.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val tint = if (highlighted) Color(0xFF111111) else Color.White
        Icon(icon, text, tint = tint, modifier = Modifier.size(20.dp))
        if (text != null) Text(text, color = tint, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun RoundIcon(icon: ImageVector, label: String, size: androidx.compose.ui.unit.Dp, filled: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .size(size)
            .couchyClickable(CircleShape, onClick = onClick)
            .background(if (filled) Color.White.copy(alpha = 0.18f) else Color.Transparent, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(size * 0.6f))
    }
}

@Composable
private fun ErrorPanel(message: String, controls: PlayerControls, modifier: Modifier) {
    val retry = remember { FocusRequester() }
    Column(
        modifier.background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(20.dp)).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Не получается воспроизвести", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(message, color = Color.White.copy(alpha = 0.75f), fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill(Icons.Rounded.PlayArrow, "Повторить", Modifier.focusRequester(retry), highlighted = true, onClick = controls.onRetry)
            Pill(Icons.AutoMirrored.Rounded.ArrowBack, "Назад", onClick = controls.onBack)
        }
    }
    LaunchedEffect(Unit) { runCatching { retry.requestFocus() } }
}

/** One of the player's buttons. */
private class Tool(
    val icon: ImageVector,
    val label: String,
    val highlighted: Boolean = false,
    val focus: FocusRequester? = null,
    /** On an upright phone it sits in the top bar. */
    val top: Boolean = false,
    val onClick: () -> Unit,
)

/** An icon with its caption under it, as in the players of streaming apps; the caption goes when space is short. */
@Composable
private fun ToolButton(t: Tool, caption: Boolean, modifier: Modifier) {
    Column(
        modifier
            .then(if (t.focus != null) Modifier.focusRequester(t.focus) else Modifier)
            .couchyClickable(RoundedCornerShape(12.dp), onClick = t.onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier.size(36.dp).background(if (t.highlighted) Color.White else Color.White.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(t.icon, t.label, tint = if (t.highlighted) Color(0xFF111111) else Color.White, modifier = Modifier.size(20.dp))
        }
        if (caption) {
            Text(
                t.label,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
