package app.couchy.ui.player

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.couchy.ui.components.couchyClickable

/** "Artist — Title" as the queue writes it: the title first, then who sings it. */
fun songParts(line: String): Pair<String, String> {
    val i = line.indexOf(" — ")
    return if (i < 0) line to "" else line.substring(i + 3) to line.substring(0, i)
}

/**
 * Music from YouTube as a music player, not a video: the cover large, the song and artist under
 * it, the timeline and the buttons a music app has. The arrow at the top folds it into the mini
 * player; the controls also sit in the notification shade.
 */
@Composable
fun MusicView(
    ui: PlayerUi,
    artwork: String?,
    track: String,
    album: String?,
    queueAt: Int,
    queueSize: Int,
    sleepLabel: String?,
    onDown: () -> Unit,
    onToggle: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onPrevious: () -> Unit,
    onNext: (() -> Unit)?,
    onQueue: (() -> Unit)?,
    onSave: (() -> Unit)?,
    onSleep: () -> Unit,
) {
    val (title, artist) = songParts(track.ifBlank { ui.title })
    // The words: shown over the cover's place, the line being sung lit and kept in view.
    var showWords by remember { mutableStateOf(false) }
    var words by remember(track) { mutableStateOf<app.couchy.core.music.Lyrics?>(null) }
    var wordsLoading by remember(track) { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(track, showWords, ui.durationMs > 0) {
        if (!showWords || words != null || wordsLoading) return@LaunchedEffect
        wordsLoading = true
        words = app.couchy.youtube.LyricsApi.find(title, artist, ui.durationMs / 1000)
        wordsLoading = false
    }
    Box(Modifier.fillMaxSize().background(Color(0xFF0B0B0F))) {
        // The cover again, blurred, as the backdrop; older Android gets the plain dark one.
        if (artwork != null && Build.VERSION.SDK_INT >= 31) {
            coil.compose.AsyncImage(artwork, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().blur(70.dp))
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.35f), Color.Black.copy(alpha = 0.85f)))))
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDown) { Icon(Icons.Rounded.KeyboardArrowDown, "Свернуть", tint = Color.White, modifier = Modifier.size(30.dp)) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Сейчас играет", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f), maxLines = 1)
                    album?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                if (onQueue != null) IconButton(onClick = onQueue) { Icon(Icons.Rounded.QueueMusic, "Очередь", tint = Color.White) }
                else Spacer(Modifier.size(48.dp))
            }
            Spacer(Modifier.weight(1f))
            if (showWords) Words(words, wordsLoading, ui.positionMs, onSeekTo, Modifier.widthIn(max = 420.dp).fillMaxWidth().aspectRatio(1f))
            else Box(
                Modifier.widthIn(max = 420.dp).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center,
            ) {
                if (artwork != null) {
                    coil.compose.AsyncImage(artwork, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Icon(Icons.Rounded.MusicNote, null, tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(96.dp))
                }
                if (ui.buffering) CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp)
            }
            Spacer(Modifier.height(28.dp))
            Column(Modifier.widthIn(max = 420.dp).fillMaxWidth()) {
                Text(title, style = MaterialTheme.typography.headlineSmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (artist.isNotBlank()) Text(artist, style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                ui.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 2) }
                Spacer(Modifier.height(12.dp))
                // While the thumb is dragged it shows where it will land; the song moves on release.
                var dragging by remember { mutableStateOf<Float?>(null) }
                val total = ui.durationMs.coerceAtLeast(1)
                val at = dragging ?: (ui.positionMs.toFloat() / total).coerceIn(0f, 1f)
                Slider(
                    value = at,
                    onValueChange = { dragging = it },
                    onValueChangeFinished = { dragging?.let { onSeekTo((it * total).toLong()) }; dragging = null },
                    enabled = ui.durationMs > 0,
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.25f)),
                )
                Row(Modifier.fillMaxWidth()) {
                    Text(musicClock((at * total).toLong()), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.weight(1f))
                    Text(if (ui.durationMs > 0) musicClock(ui.durationMs) else "", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f))
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                Round(Icons.Rounded.SkipPrevious, "Предыдущая", 56.dp, filled = false, onClick = onPrevious)
                Round(if (ui.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (ui.playing) "Пауза" else "Играть", 76.dp, filled = true, onClick = onToggle)
                Round(Icons.Rounded.SkipNext, "Следующая", 56.dp, filled = false, enabled = onNext != null, onClick = { onNext?.invoke() })
            }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.widthIn(max = 420.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Small(Icons.Rounded.Lyrics, "Текст", showWords) { showWords = !showWords }
                Small(Icons.Rounded.Bedtime, sleepLabel ?: "Таймер", sleepLabel != null, onSleep)
                onSave?.let { Small(Icons.Rounded.Download, "Сохранить", false, it) }
                if (onQueue != null && queueSize > 1) Small(Icons.Rounded.QueueMusic, "${queueAt + 1} из $queueSize", false, onQueue)
            }
            Spacer(Modifier.weight(1f))
        }
    }
}

/** The song's words; synced ones follow the song, and a tap on a line jumps there. */
@Composable
private fun Words(lyrics: app.couchy.core.music.Lyrics?, loading: Boolean, positionMs: Long, onSeekTo: (Long) -> Unit, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(20.dp)).background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
        when {
            loading -> CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp)
            lyrics == null -> Text("Текст этой песни не нашёлся", style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.7f))
            else -> {
                val state = androidx.compose.foundation.lazy.rememberLazyListState()
                val at = if (lyrics.synced) app.couchy.core.music.LrcLib.lineAt(lyrics.lines, positionMs) else -1
                androidx.compose.runtime.LaunchedEffect(at) { if (at >= 0) state.animateScrollToItem((at - 2).coerceAtLeast(0)) }
                androidx.compose.foundation.lazy.LazyColumn(
                    Modifier.fillMaxSize(),
                    state = state,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(lyrics.lines.size) { i ->
                        val (start, line) = lyrics.lines[i]
                        Text(
                            line.ifBlank { "♪" },
                            style = if (lyrics.synced) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge,
                            color = when {
                                !lyrics.synced -> Color.White.copy(alpha = 0.9f)
                                i == at -> Color.White
                                else -> Color.White.copy(alpha = 0.4f)
                            },
                            modifier = if (lyrics.synced && start >= 0) Modifier.couchyClickable(RoundedCornerShape(8.dp)) { onSeekTo(start) } else Modifier,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Round(icon: ImageVector, label: String, size: Dp, filled: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier
            .size(size)
            .couchyClickable(CircleShape) { if (enabled) onClick() }
            .background(if (filled) Color.White else Color.Transparent, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon, label,
            tint = when {
                filled -> Color.Black
                enabled -> Color.White
                else -> Color.White.copy(alpha = 0.3f)
            },
            modifier = Modifier.size(size * 0.55f),
        )
    }
}

@Composable
private fun Small(icon: ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).couchyClickable(RoundedCornerShape(12.dp), onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, label, tint = if (on) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.85f), modifier = Modifier.size(22.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.75f), maxLines = 1, softWrap = false)
    }
}

private fun musicClock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
