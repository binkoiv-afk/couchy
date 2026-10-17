package app.couchy.ui.tv

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.couchy.tv.ChannelEntity
import app.couchy.tv.ChannelNow
import app.couchy.tv.ProgramEntity
import app.couchy.tv.TvRepository
import app.couchy.ui.components.Chip

private const val HOUR = TvRepository.HOUR
/** How wide an hour of the grid is. */
private val HOUR_WIDTH = 300.dp
private val CHANNEL_WIDTH = 170.dp
private val ROW_HEIGHT = 64.dp

/**
 * The TV guide as a grid, like on a TV box: channels down, hours across, the red line at now.
 * What is on now plays the channel; a past programme plays from the archive where the channel
 * keeps one. The section chips on top switch the channels.
 */
@Composable
fun GuideGrid(
    groups: List<String>,
    group: String,
    channels: List<ChannelNow>,
    load: suspend (from: Long, to: Long) -> Map<String, List<ProgramEntity>>,
    onGroup: (String) -> Unit,
    onLive: (ChannelEntity) -> Unit,
    onArchive: (ChannelEntity, ProgramEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val now = remember { System.currentTimeMillis() }
        // From the half hour two hours back (the archive) to twelve hours ahead.
        val from = remember { (now - 2 * HOUR) / (HOUR / 2) * (HOUR / 2) }
        val to = remember { from + 14 * HOUR }
        var programs by remember { mutableStateOf<Map<String, List<ProgramEntity>>?>(null) }
        LaunchedEffect(Unit) { programs = runCatching { load(from, to) }.getOrDefault(emptyMap()) }
        val scroll = rememberScrollState()
        val density = LocalDensity.current
        fun width(ms: Long): Dp = HOUR_WIDTH * (ms.toFloat() / HOUR)
        LaunchedEffect(programs != null) {
            // Starts at half an hour before now.
            with(density) { scroll.scrollTo(width(now - from - HOUR / 2).roundToPx().coerceAtLeast(0)) }
        }
        val nowFocus = remember { FocusRequester() }
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Телегид", style = MaterialTheme.typography.headlineSmall, maxLines = 1, softWrap = false, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Закрыть") }
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(groups) { g -> Chip(g, g == group, onClick = { onGroup(g) }) }
            }
            // The hours along the top, moving with the grid.
            Row(Modifier.fillMaxWidth().height(28.dp)) {
                Spacer(Modifier.width(CHANNEL_WIDTH))
                Box(Modifier.weight(1f).horizontalScroll(scroll)) {
                    Box(Modifier.width(width(to - from))) {
                        var t = from
                        while (t < to) {
                            Text(
                                clockOf(t),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.offset(x = width(t - from)).padding(start = 6.dp),
                            )
                            t += HOUR / 2
                        }
                    }
                }
            }
            val list = programs
            if (list == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = MaterialTheme.colorScheme.primary) }
                return@Column
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(channels, key = { it.channel.id }) { c ->
                    Row(Modifier.fillMaxWidth().height(ROW_HEIGHT).padding(vertical = 2.dp)) {
                        Row(
                            Modifier.width(CHANNEL_WIDTH).fillMaxHeight().padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("${c.number}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(c.channel.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(scroll)) {
                            Box(Modifier.width(width(to - from)).fillMaxHeight()) {
                                val shows = c.channel.epgId?.let { list[it] }.orEmpty()
                                val archive = c.channel.catchup != null
                                if (shows.isEmpty()) {
                                    // No guide: one long cell that plays the channel.
                                    val first = c == channels.first()
                                    Cell(
                                        "Нет программы", null, live = true, past = false, archive = false,
                                        Modifier.offset(x = width(now - from - HOUR / 2)).width(width(3 * HOUR)).then(if (first) Modifier.focusRequester(nowFocus) else Modifier),
                                        onClick = { onLive(c.channel) },
                                    )
                                } else shows.forEach { p ->
                                    val start = maxOf(p.start, from)
                                    val end = minOf(p.stop, to)
                                    if (end <= start) return@forEach
                                    val onAir = p.start <= now && p.stop > now
                                    val past = p.stop <= now
                                    // The remote starts on what is on now on the top channel.
                                    val first = onAir && c == channels.first()
                                    Cell(
                                        p.title, clockOf(p.start), live = onAir, past = past, archive = past && archive,
                                        Modifier.offset(x = width(start - from)).width(width(end - start)).then(if (first) Modifier.focusRequester(nowFocus) else Modifier),
                                        onClick = {
                                            when {
                                                onAir -> onLive(c.channel)
                                                past && archive -> onArchive(c.channel, p)
                                                else -> Unit
                                            }
                                        },
                                    )
                                }
                            }
                            // The red line at now.
                            Box(Modifier.offset(x = width(now - from)).width(2.dp).fillMaxHeight().background(Color(0xFFE53935)))
                        }
                    }
                }
            }
            LaunchedEffect(list, group) { runCatching { nowFocus.requestFocus() } }
        }
    }
}

@Composable
private fun Cell(title: String, time: String?, live: Boolean, past: Boolean, archive: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(10.dp)
    val accent = MaterialTheme.colorScheme.primary
    Column(
        modifier
            .fillMaxHeight()
            .padding(horizontal = 1.dp)
            .onKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && (e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter)) { onClick(); true } else false
            }
            .focusable(interactionSource = interaction)
            .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) }
            .background(
                when {
                    focused -> MaterialTheme.colorScheme.onBackground
                    live -> accent.copy(alpha = 0.22f)
                    past -> MaterialTheme.colorScheme.surfaceContainer
                    else -> MaterialTheme.colorScheme.surfaceContainerHigh
                },
                shape,
            )
            .then(if (live && !focused) Modifier.border(1.dp, accent.copy(alpha = 0.6f), shape) else Modifier)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        val fg = if (focused) MaterialTheme.colorScheme.background else if (past && !archive) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
        Text(title, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (archive) Icon(Icons.Rounded.History, "Архив", tint = fg.copy(alpha = 0.8f), modifier = Modifier.width(14.dp).height(14.dp))
            time?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.7f), maxLines = 1) }
        }
    }
}
