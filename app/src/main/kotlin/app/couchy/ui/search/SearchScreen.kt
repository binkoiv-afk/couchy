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
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.couchy.core.sources.CatalogFilter
import app.couchy.core.sources.CatalogSort
import app.couchy.core.sources.CatalogType
import app.couchy.core.sources.RezkaCatalog
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
    /** The catalogue, narrowed to a kind or a genre. */
    val onCatalog: (CatalogFilter) -> Unit = {},
)

/**
 * One search for everything: films and series from IMDb and the online sources, each opening
 * a page with every way to watch it. "Только торренты" searches trackers directly.
 */
@Composable
fun SearchContent(state: SearchState, actions: SearchActions, modifier: Modifier = Modifier, focusKey: Int = 0) {
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
        item(span = { GridItemSpan(maxLineSpan) }) { Header(state, actions, focusKey) }

        if (state.loading) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
        } else if (!state.searched || state.query.isBlank()) {
            if (state.history.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { History(state.history, actions) }
            if (state.mode == SearchMode.TORRENTS) {
                item(span = { GridItemSpan(maxLineSpan) }) { Hint("Ищу раздачи на rutracker, rutor, kinozal и других трекерах.") }
            } else {
                // Nothing typed yet: the catalogue to browse, by kind and by genre.
                item(span = { GridItemSpan(maxLineSpan) }) { Label("Каталог") }
                item(span = { GridItemSpan(maxLineSpan) }) { Kinds(actions) }
                item(span = { GridItemSpan(maxLineSpan) }) { Label("Жанры") }
                items(app.couchy.core.sources.Genres.all.take(18), key = { "genre:" + it.slug }) { g -> val slug = g.slug; val name = g.name
                    GenreTile(name, slug) { actions.onCatalog(CatalogFilter(genre = slug)) }
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
                        SecondaryButton("Все фильтры", androidx.compose.material.icons.Icons.Rounded.Tune, onClick = { actions.onCatalog(CatalogFilter()) })
                        SecondaryButton("Лучшие за год", null, onClick = {
                            actions.onCatalog(CatalogFilter(sort = CatalogSort.BEST, year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) - 1))
                        })
                    }
                }
            }
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
            if (state.moreLoading) item(span = { GridItemSpan(maxLineSpan) }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                    Text("Ещё ищу…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val shown = when (state.mode) {
                SearchMode.MOVIES -> state.titles.filter { it.kind == TitleKind.MOVIE }
                SearchMode.SERIES -> state.titles.filter { it.kind == TitleKind.SERIES }
                else -> state.titles
            }
            if (shown.isEmpty() && !state.moreLoading) item(span = { GridItemSpan(maxLineSpan) }) { Hint("Ничего не нашлось. Попробуйте название на русском или на английском.") }
            state.corrected?.let { fixed ->
                item(span = { GridItemSpan(maxLineSpan) }, key = "corrected") {
                    Text(
                        "По «${state.query}» ничего, показываю «$fixed»",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val best = shown.firstOrNull()
            if (best != null) {
                item(span = { GridItemSpan(maxLineSpan) }) { Label("Лучшее совпадение") }
                item(span = { GridItemSpan(maxLineSpan) }, key = "best") { BestMatch(best, onClick = { actions.onTitle(best) }) }
                if (shown.size > 1) item(span = { GridItemSpan(maxLineSpan) }) { Label("Ещё") }
            }
            items(shown.drop(1), key = { it.key }) { t -> PosterCard(t, onClick = { actions.onTitle(t) }) }
        }
    }
}

@Composable
private fun Header(state: SearchState, actions: SearchActions, focusKey: Int) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // Coming from the search bar on Home: the field is ready to type into.
    LaunchedEffect(focusKey) {
        if (focusKey > 0) runCatching { focus.requestFocus(); keyboard?.show() }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Поиск", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(top = 4.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                state.query,
                actions.onQuery,
                placeholder = { Text("Фильм, сериал или мультфильм") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                singleLine = true,
                shape = RoundedCornerShape(999.dp),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); actions.onSubmit(state.query) }),
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
            actions.onVoice?.let { SecondaryButton(null, Icons.Rounded.Mic, onClick = it) }
            SecondaryButton(null, Icons.Rounded.AddLink, onClick = actions.onOpenLink)
        }
        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(SearchMode.entries.size) { i -> val m = SearchMode.entries[i]; Chip(m.label, m == state.mode, onClick = { actions.onMode(m) }) }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
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
        // Tags: one tap shows that search's results again, kept from last time.
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            history.forEach { q -> Chip(q, false, onClick = { actions.onSubmit(q) }, icon = Icons.Rounded.History) }
        }
    }
}

/** Films, series, cartoons and anime as four big buttons. */
@Composable
private fun Kinds(actions: SearchActions) {
    val colors = listOf(Color(0xFFFF7A3D), Color(0xFF7B5CFF), Color(0xFF2BB673), Color(0xFFE6417A))
    val wide = LocalFormFactor.current.wide
    // Two by two on a phone, so "Мультфильмы" fits; one row on wide screens.
    val rows = if (wide) listOf(CatalogType.entries) else CatalogType.entries.chunked(2)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { type ->
                    val color = colors[type.ordinal]
                    val shape = RoundedCornerShape(16.dp)
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(2.4f)
                            .couchyClickable(shape) { actions.onCatalog(CatalogFilter(type, CatalogSort.WATCHING)) }
                            .clip(shape)
                            .background(Brush.linearGradient(listOf(color, color.copy(alpha = 0.55f)))),
                        contentAlignment = Alignment.BottomStart,
                    ) {
                        Text(type.label, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, modifier = Modifier.padding(12.dp))
                    }
                }
            }
        }
    }
}

/** A genre as a dark tile with a stripe of its own color. */
@Composable
private fun GenreTile(name: String, slug: String, onClick: () -> Unit) {
    val hue = ((slug.hashCode() and 0xFFFF) % 360).toFloat()
    val accent = Color.hsv(hue, 0.6f, 0.85f)
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1.7f)
            .couchyClickable(shape, onClick = onClick)
            .clip(shape)
            .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.38f), MaterialTheme.colorScheme.surfaceContainerHigh))),
        contentAlignment = Alignment.BottomStart,
    ) {
        Text(name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(10.dp))
    }
}

@Composable
private fun Label(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
}

/** The first result, larger, with its poster and a button: most searches end here. */
@Composable
private fun BestMatch(title: Title, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .couchyClickable(shape, focusScale = 1.02f, onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceContainer, shape)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PosterCard(title, onClick = onClick, modifier = Modifier.width(92.dp), caption = false)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title.name, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(title.year?.toString(), if (title.kind == TitleKind.SERIES) "сериал" else "фильм", title.rating).joinToString(" · ")
            Text(meta, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            title.original?.takeIf { it != title.name }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            androidx.compose.foundation.layout.Spacer(Modifier.size(6.dp))
            app.couchy.ui.components.PrimaryButton("Смотреть", androidx.compose.material.icons.Icons.Rounded.PlayArrow, onClick = onClick)
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
