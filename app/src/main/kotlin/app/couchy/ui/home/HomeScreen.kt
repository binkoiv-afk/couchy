package app.couchy.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddLink
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.couchy.HomeState
import app.couchy.data.MediaEntity
import app.couchy.ui.components.FormFactor
import app.couchy.ui.components.LocalFormFactor
import app.couchy.ui.components.MediaCard
import app.couchy.ui.components.couchyClickable
import app.couchy.ui.shell.LocalKidsMode

/** Quick ways in from Home. */
enum class Shortcut(val label: String, val icon: ImageVector, val from: Color, val to: Color) {
    HISTORY("История", Icons.Rounded.History, Color(0xFFE8772E), Color(0xFFB4501A)),
    FAVORITES("Избранное", Icons.Rounded.Favorite, Color(0xFFD9466F), Color(0xFF9E2A4D)),
    TV("ТВ-каналы", Icons.Rounded.LiveTv, Color(0xFF3B6FD8), Color(0xFF2A4C9E)),
    TORRENTS("Торренты", Icons.Rounded.CloudDownload, Color(0xFF7A4FD6), Color(0xFF55339E)),
    LINK("По ссылке", Icons.Rounded.AddLink, Color(0xFF1E9C8C), Color(0xFF137064)),
    SETTINGS("Настройки", Icons.Rounded.Settings, Color(0xFF5A6070), Color(0xFF3C414D)),
}

private val kidsShortcuts = listOf(Shortcut.HISTORY, Shortcut.FAVORITES, Shortcut.TV)

class HomeActions(
    val onPlay: (MediaEntity) -> Unit,
    val onMore: (MediaEntity) -> Unit,
    val onFavorite: (MediaEntity) -> Unit,
    val onOpen: () -> Unit,
    /** Opens the search screen with the keyboard up. */
    val onSearch: () -> Unit = {},
    /** Repeats a recent search. */
    val onQuery: (String) -> Unit = {},
    /** Voice search; null where the device has none. */
    val onVoice: (() -> Unit)? = null,
    val onShortcut: (Shortcut) -> Unit = {},
)

/**
 * Home starts with the search, since finding something to watch is why people open Couchy.
 * Under it: quick tiles, then what is being watched, favourites and recently added.
 */
@Composable
fun HomeContent(state: HomeState, actions: HomeActions, modifier: Modifier = Modifier, searches: List<String> = emptyList()) {
    val form = LocalFormFactor.current
    val kids = LocalKidsMode.current
    val side = if (form.wide) 32.dp else 18.dp
    val cardWidth = when (form) {
        FormFactor.TV -> 260.dp
        FormFactor.TABLET -> 250.dp
        FormFactor.PHONE -> 200.dp
    }
    val firstFocus = remember { FocusRequester() }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp)) {
        if (!kids) {
            item("search") {
                Column(Modifier.padding(horizontal = side, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Что посмотрим?", style = if (form.isTv) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineSmall)
                    SearchBar(actions, Modifier.focusRequester(firstFocus))
                }
            }
            if (searches.isNotEmpty()) item("searches") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = side, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(searches.take(10)) { q -> RecentQuery(q) { actions.onQuery(q) } }
                }
            }
        }
        item("tiles") {
            val tiles = if (kids) kidsShortcuts else Shortcut.entries
            val perRow = if (form.wide) tiles.size else 3
            Column(
                Modifier.padding(horizontal = side).padding(top = 14.dp).widthIn(max = 1400.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                tiles.chunked(perRow).forEachIndexed { r, row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEachIndexed { i, s ->
                            val focus = if (kids && r == 0 && i == 0) Modifier.focusRequester(firstFocus) else Modifier
                            Tile(s, Modifier.weight(1f).then(focus)) { actions.onShortcut(s) }
                        }
                        repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        fun row(key: String, title: String, items: List<MediaEntity>) {
            if (items.isEmpty()) return
            item(key) {
                Column(Modifier.padding(top = 22.dp)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = side).padding(bottom = 12.dp))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = side, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(if (form.isTv) 20.dp else 12.dp),
                    ) {
                        items(items, key = { it.uri }) { media ->
                            MediaCard(media, cardWidth, onClick = { actions.onPlay(media) }, onLongClick = { actions.onMore(media) })
                        }
                    }
                }
            }
        }
        row("continue", "Продолжить просмотр", state.continueWatching)
        row("favorites", "Избранное", state.favorites)
        row("recent", "Недавно добавленные", state.recent)
        if (state.isEmpty && !kids) item("empty") {
            Text(
                "Здесь появится то, что вы смотрите. Найдите фильм или сериал в поиске: Couchy ищет сразу на HDrezka, " +
                    "в онлайн-кинотеатрах и на торрент-трекерах, и показывает всё в одном месте.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = side).padding(top = 24.dp).widthIn(max = 640.dp),
            )
        }
    }
    // The remote starts on the search, so OK immediately opens it.
    LaunchedEffect(form, state.loaded) {
        if (form.isTv && state.loaded) runCatching { firstFocus.requestFocus() }
    }
}

/** Looks like a search field; opens the real one, so the keyboard does not pop up over Home. */
@Composable
private fun SearchBar(actions: HomeActions, modifier: Modifier = Modifier) {
    val form = LocalFormFactor.current
    val shape = RoundedCornerShape(18.dp)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier
                .weight(1f)
                .height(if (form.isTv) 64.dp else 58.dp)
                .couchyClickable(shape, focusScale = 1.02f, onClick = actions.onSearch)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, shape)
                .border(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f), shape)
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
            Text(
                "Фильм, сериал или мультфильм",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        actions.onVoice?.let { voice ->
            Box(
                Modifier
                    .size(if (form.isTv) 64.dp else 58.dp)
                    .couchyClickable(shape, onClick = voice)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, shape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Mic, "Голосовой поиск", tint = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun RecentQuery(text: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(999.dp)
    Row(
        Modifier
            .height(36.dp)
            .couchyClickable(shape, onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceContainer, shape)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Rounded.History, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
    }
}

/** A coloured tile; white on a saturated colour reads the same in the dark and the light theme. */
@Composable
private fun Tile(shortcut: Shortcut, modifier: Modifier, onClick: () -> Unit) {
    val form = LocalFormFactor.current
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .height(if (form.isTv) 112.dp else 92.dp)
            .couchyClickable(shape, focusScale = 1.05f, onClick = onClick)
            .background(Brush.linearGradient(listOf(shortcut.from, shortcut.to)), shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Icon(shortcut.icon, null, tint = Color.White, modifier = Modifier.size(if (form.isTv) 30.dp else 26.dp))
        Text(shortcut.label, style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
