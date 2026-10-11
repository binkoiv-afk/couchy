package app.couchy.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddLink
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.couchy.core.sources.Title
import app.couchy.core.sources.TitleKind
import app.couchy.core.sources.TorrentHit
import app.couchy.sources.SearchMode
import app.couchy.sources.SearchState
import app.couchy.ui.components.Artwork
import app.couchy.ui.components.Chip
import app.couchy.ui.components.FormFactor
import app.couchy.ui.components.LocalFormFactor
import app.couchy.ui.components.SecondaryButton
import app.couchy.ui.components.couchyClickable
import coil.compose.AsyncImage

class SearchActions(
    val onQuery: (String) -> Unit,
    val onSubmit: (String) -> Unit,
    val onMode: (SearchMode) -> Unit,
    val onTitle: (Title) -> Unit,
    val onTorrent: (TorrentHit) -> Unit,
    /** Voice input; null where the device has none. */
    val onVoice: (() -> Unit)?,
    val onOpenLink: () -> Unit,
    val onClearHistory: () -> Unit,
)

/**
 * One search for everything: films and series from IMDb and the online sources, each opening
 * a page with every way to watch it. "Только торренты" searches trackers directly.
 */
@Composable
fun SearchContent(state: SearchState, actions: SearchActions, modifier: Modifier = Modifier) {
    val form = LocalFormFactor.current
    val side = if (form.wide) 32.dp else 18.dp
    val cell = when (form) {
        FormFactor.TV -> 170.dp
        FormFactor.TABLET -> 150.dp
        FormFactor.PHONE -> 108.dp
    }
    LazyVerticalGrid(
        GridCells.Adaptive(cell),
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = side, end = side, top = 16.dp, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(if (form.isTv) 18.dp else 12.dp),
        verticalArrangement = Arrangement.spacedBy(if (form.isTv) 20.dp else 14.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { Header(state, actions) }

        if (state.loading) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
        } else if (!state.searched || state.query.isBlank()) {
            if (state.history.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { History(state.history, actions) }
        } else if (state.error != null) {
            item(span = { GridItemSpan(maxLineSpan) }) { Hint(state.error) }
        } else if (state.mode == SearchMode.TORRENTS) {
            if (state.torrents.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Hint("Раздач не нашлось. Попробуйте другое название.") }
            state.torrents.forEach { group ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(group.label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(top = 6.dp))
                }
                group.hits.take(30).forEach { hit ->
                    item(span = { GridItemSpan(maxLineSpan) }) { TorrentRow(hit, onClick = { actions.onTorrent(hit) }) }
                }
            }
        } else {
            if (state.siteBlocked) item(span = { GridItemSpan(maxLineSpan) }) { Hint("HDrezka просит пройти проверку: результаты с неё появятся после неё.") }
            if (state.titles.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Hint("Ничего не нашлось. Попробуйте название на русском или на английском.") }
            items(state.titles, key = { it.key }) { t -> PosterCard(t, onClick = { actions.onTitle(t) }) }
        }
    }
}

@Composable
private fun Header(state: SearchState, actions: SearchActions) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                state.query,
                actions.onQuery,
                placeholder = { Text("Фильм, сериал, мультфильм…") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { actions.onSubmit(state.query) }),
                modifier = Modifier.weight(1f),
            )
            actions.onVoice?.let { SecondaryButton(null, Icons.Rounded.Mic, onClick = it) }
            SecondaryButton(null, Icons.Rounded.AddLink, onClick = actions.onOpenLink)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SearchMode.entries.forEach { m -> Chip(m.label, m == state.mode, onClick = { actions.onMode(m) }) }
        }
    }
}

@Composable
private fun History(history: List<String>, actions: SearchActions) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Вы искали", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
            Text(
                "Очистить",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.couchyClickable(RoundedCornerShape(8.dp), onClick = actions.onClearHistory).padding(8.dp),
            )
        }
        history.forEach { q ->
            Row(
                Modifier.fillMaxWidth().couchyClickable(RoundedCornerShape(10.dp), focusScale = 1.02f) { actions.onSubmit(q) }.padding(horizontal = 6.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Rounded.History, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Text(q, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
}

private val PosterShape = RoundedCornerShape(12.dp)

/** A 2:3 poster with the name under it; the gradient artwork stands in until the image loads. */
@Composable
fun PosterCard(title: Title, onClick: () -> Unit, modifier: Modifier = Modifier, caption: Boolean = true) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .couchyClickable(PosterShape, onClick = onClick)
                .clip(PosterShape),
        ) {
            Artwork(title.name, Modifier.fillMaxSize()) {
                var failed by remember(title.poster) { mutableStateOf(title.poster == null) }
                if (failed) {
                    Text(
                        title.name,
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 15.sp,
                        lineHeight = 17.sp,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
                    )
                } else {
                    AsyncImage(
                        model = title.poster,
                        contentDescription = title.name,
                        contentScale = ContentScale.Crop,
                        onError = { failed = true },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            if (title.kind == TitleKind.SERIES) {
                Text(
                    "СЕРИАЛ",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopStart).padding(7.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(5.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            title.rating?.let { r ->
                Text(
                    r.substringAfter(' '),
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopEnd).padding(7.dp)
                        .background(Color(0xFF2E7D32), RoundedCornerShape(5.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        if (!caption) return@Column
        Text(title.name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val sub = listOfNotNull(title.year?.toString(), title.original?.takeIf { it != title.name }).joinToString(" · ")
        if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** One torrent: name, then size, seeds, tracker and voice-overs. */
@Composable
fun TorrentRow(hit: TorrentHit, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .fillMaxWidth()
            .couchyClickable(shape, focusScale = 1.02f, onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceContainer, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(hit.title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (hit.hdr) Badge("HDR", Color(0xFF6A1B9A))
            Text(
                listOfNotNull(
                    app.couchy.core.torrent.Sizes.bytes(hit.size).takeIf { hit.size > 0 },
                    "▲ ${hit.seeds}",
                    hit.tracker,
                    hit.voices.take(3).joinToString(", ").ifEmpty { null },
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = if (hit.seeds > 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Text(
        text, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.background(Brush.horizontalGradient(listOf(color, color.copy(alpha = 0.8f))), RoundedCornerShape(5.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
