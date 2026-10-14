package app.couchy.ui.tv

import androidx.compose.foundation.shape.CircleShape

import androidx.compose.material.icons.rounded.StarBorder

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.couchy.tv.ChannelEntity
import app.couchy.tv.ChannelNow
import app.couchy.tv.ProgramEntity
import app.couchy.tv.TvState
import app.couchy.ui.components.Chip
import app.couchy.ui.components.LocalFormFactor
import app.couchy.ui.components.PrimaryButton
import app.couchy.ui.components.SecondaryButton
import app.couchy.ui.components.couchyClickable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TvActions(
    val onGroup: (String) -> Unit,
    val onPlay: (ChannelNow) -> Unit,
    val onFocus: (ChannelEntity) -> Unit,
    /** Long press / menu on a channel: favorite and guide. */
    val onMore: (ChannelNow) -> Unit,
    val onArchive: (ChannelEntity, ProgramEntity) -> Unit,
    val onAdd: () -> Unit,
    val onRefresh: () -> Unit,
    val onPlaylists: () -> Unit,
    /** The star on a channel row: favorite in one tap. */
    val onStar: (ChannelNow) -> Unit = {},
)

private val time = SimpleDateFormat("HH:mm", Locale.getDefault())
fun clockOf(ms: Long): String = time.format(Date(ms))

/**
 * IPTV in the TiviMate layout on wide screens: groups, channels, and the guide of the channel
 * under focus. On a phone: group chips over a channel list.
 */
@Composable
fun TvContent(
    state: TvState,
    focused: ChannelEntity?,
    schedule: List<ProgramEntity>,
    actions: TvActions,
    modifier: Modifier = Modifier,
) {
    val form = LocalFormFactor.current
    if (state.loaded && state.playlists.isEmpty()) {
        EmptyTv(actions, modifier)
        return
    }
    if (form.wide) {
        Row(modifier.fillMaxSize()) {
            // The guide keeps at least a third of the screen for programme names.
            Groups(state, actions, Modifier.width(180.dp).fillMaxHeight())
            Channels(state, actions, Modifier.width(320.dp).fillMaxHeight())
            Guide(state, focused ?: state.channels.firstOrNull()?.channel, schedule, actions, Modifier.weight(1f).fillMaxHeight())
        }
    } else {
        Column(modifier.fillMaxSize()) {
            Header(state, actions, Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.groups) { g -> Chip(g, g == state.group, onClick = { actions.onGroup(g) }) }
            }
            Channels(state, actions, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun Header(state: TvState, actions: TvActions, modifier: Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("ТВ", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
        SecondaryButton(null, Icons.AutoMirrored.Rounded.PlaylistPlay, onClick = actions.onPlaylists)
        SecondaryButton(null, Icons.Rounded.Refresh, onClick = actions.onRefresh)
        SecondaryButton(null, Icons.Rounded.Add, onClick = actions.onAdd)
    }
    if (state.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 18.dp))
}

@Composable
private fun Groups(state: TvState, actions: TvActions, modifier: Modifier) {
    Column(modifier.background(MaterialTheme.colorScheme.surfaceContainer).padding(top = 20.dp)) {
        Text("ТВ", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 24.dp, bottom = 12.dp))
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
            items(state.groups) { g ->
                val selected = g == state.group
                Text(
                    g,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (selected) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                        .couchyClickable(RoundedCornerShape(10.dp), focusScale = 1.03f) { actions.onGroup(g) }
                        .background(if (selected) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent, RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (state.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp))
            SideButton(Icons.Rounded.Add, "Добавить плейлист", actions.onAdd)
            SideButton(Icons.Rounded.Refresh, "Обновить", actions.onRefresh)
            SideButton(Icons.AutoMirrored.Rounded.PlaylistPlay, "Плейлисты", actions.onPlaylists)
        }
    }
}

@Composable
private fun SideButton(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().couchyClickable(RoundedCornerShape(10.dp), focusScale = 1.03f, onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Channels(state: TvState, actions: TvActions, modifier: Modifier) {
    LazyColumn(modifier, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (state.channels.isEmpty() && state.loaded) {
            item {
                Text(
                    if (state.refreshing) "Загружаю каналы…" else state.playlists.firstNotNullOfOrNull { it.lastError }?.let { "Не удалось загрузить: $it" } ?: "Здесь пока нет каналов.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
        items(state.channels, key = { it.channel.id }) { item -> ChannelRow(item, state.nowMs, actions) }
    }
}

@Composable
private fun ChannelRow(item: ChannelNow, nowMs: Long, actions: TvActions) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .onFocusChanged { if (it.isFocused) actions.onFocus(item.channel) }
            .couchyClickable(shape, focusScale = 1.02f, onLongClick = { actions.onMore(item) }) { actions.onPlay(item) }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "${item.number}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(30.dp),
        )
        ChannelLogo(item.channel.name, item.channel.logo, 44.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(item.channel.name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (item.channel.favorite && LocalFormFactor.current.isTv) Icon(Icons.Rounded.Star, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                if (item.channel.catchup != null) Icon(Icons.Rounded.History, "Архив", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val now = item.now
            Text(
                now?.title ?: "Нет программы",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (now != null) ProgressLine(now, nowMs)
        }
        // On a remote the menu key stars a channel; a finger gets the star itself.
        if (!LocalFormFactor.current.isTv) {
            Icon(
                if (item.channel.favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                if (item.channel.favorite) "Убрать из избранного" else "В избранное",
                tint = if (item.channel.favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(44.dp).couchyClickable(CircleShape) { actions.onStar(item) }.padding(10.dp),
            )
        }
    }
}

@Composable
fun ProgressLine(program: ProgramEntity, nowMs: Long, modifier: Modifier = Modifier) {
    val fraction = ((nowMs - program.start).toFloat() / (program.stop - program.start).coerceAtLeast(1)).coerceIn(0f, 1f)
    Box(modifier.fillMaxWidth().height(3.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(2.dp))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)))
    }
}

@Composable
private fun Guide(state: TvState, channel: ChannelEntity?, schedule: List<ProgramEntity>, actions: TvActions, modifier: Modifier) {
    Column(modifier.padding(start = 12.dp, end = 28.dp, top = 24.dp)) {
        if (channel == null) return@Column
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ChannelLogo(channel.name, channel.logo, 56.dp)
            Column {
                Text(channel.name, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(channel.groupName, channel.catchup?.let { "архив ${it.days} дн." }).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        ScheduleList(channel, schedule, state.nowMs, actions, Modifier.weight(1f))
    }
}

/** The day's programmes; past ones with an archive can be played, the current one is highlighted. */
@Composable
fun ScheduleList(channel: ChannelEntity, schedule: List<ProgramEntity>, nowMs: Long, actions: TvActions, modifier: Modifier = Modifier) {
    if (schedule.isEmpty()) {
        Text(
            if (channel.epgId == null) "Для этого канала нет телепрограммы." else "Телепрограмма загружается…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
        return
    }
    val current = schedule.indexOfFirst { it.start <= nowMs && it.stop > nowMs }
    val list = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = (current - 2).coerceAtLeast(0))
    LazyColumn(modifier, state = list, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items(schedule, key = { it.id }) { p ->
            val isNow = p.start <= nowMs && p.stop > nowMs
            val past = p.stop <= nowMs
            val archive = past && channel.catchup != null && p.start >= nowMs - channel.catchupDays * 86_400_000L
            val shape = RoundedCornerShape(10.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(if (archive) Modifier.couchyClickable(shape, focusScale = 1.02f) { actions.onArchive(channel, p) } else Modifier)
                    .background(if (isNow) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent, shape)
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    clockOf(p.start),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isNow) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(46.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        p.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (past && !archive) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (isNow) ProgressLine(p, nowMs, Modifier.padding(top = 5.dp))
                }
                if (archive) Icon(Icons.Rounded.History, "Архив", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun EmptyTv(actions: TvActions, modifier: Modifier) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .widthIn(max = 620.dp)
                .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(24.dp))
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Rounded.LiveTv, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary)
            Text("Добавьте IPTV-плейлист", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Ссылка на M3U от провайдера или логин Xtream Codes. Couchy загрузит каналы, телепрограмму и архив передач, если провайдер его даёт.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            PrimaryButton("Добавить плейлист", Icons.Rounded.Add, onClick = actions.onAdd)
        }
    }
}
