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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.couchy.HomeState
import app.couchy.core.sources.CatalogFilter
import app.couchy.core.sources.CatalogSort
import app.couchy.core.sources.CatalogType
import app.couchy.core.sources.RezkaCatalog
import app.couchy.core.sources.Title
import app.couchy.core.sources.TitleKind
import app.couchy.data.MediaEntity
import app.couchy.sources.Feed
import app.couchy.ui.components.Artwork
import app.couchy.ui.components.CouchMark
import app.couchy.ui.components.FormFactor
import app.couchy.ui.components.LocalFormFactor
import app.couchy.ui.components.MediaCard
import app.couchy.ui.components.PrimaryButton
import app.couchy.ui.components.couchyClickable
import app.couchy.ui.search.PosterCard
import app.couchy.ui.shell.LocalKidsMode
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

class HomeActions(
    val onPlay: (MediaEntity) -> Unit,
    val onMore: (MediaEntity) -> Unit,
    val onFavorite: (MediaEntity) -> Unit,
    val onOpen: () -> Unit,
    /** Opens the search screen with the keyboard up. */
    val onSearch: () -> Unit = {},
    /** Voice search; null where the device has none. */
    val onVoice: (() -> Unit)? = null,
    /** A film or series from the catalogue: its page with every way to watch it. */
    val onTitle: (Title) -> Unit = {},
    /** The catalogue behind a shelf, or a genre. */
    val onCatalog: (CatalogFilter) -> Unit = {},
    /** «Что посмотреть?»: three titles for a mood; null hides it. */
    val onPick: (() -> Unit)? = null,
    /** A muted trailer for the banner: link and user agent; null where trailers are off. */
    val trailer: (suspend (Title) -> Pair<String, String>?)? = null,
)

/**
 * Home is a shop window: a big banner of what is new, the search right on it, then what you
 * are watching and what everybody watches now. Without the catalogue it is your library.
 */
@Composable
fun HomeContent(
    state: HomeState,
    actions: HomeActions,
    modifier: Modifier = Modifier,
    feed: Feed = Feed(),
    saved: app.couchy.data.SavedTitles = app.couchy.data.SavedTitles(),
    /** A new version: shown as a strip at the top of Home until it is installed. */
    update: (@Composable () -> Unit)? = null,
) {
    val form = LocalFormFactor.current
    val kids = LocalKidsMode.current
    val side = if (form.wide) 40.dp else 18.dp
    val searchFocus = remember { FocusRequester() }
    val continueFocus = remember { FocusRequester() }
    val featured = if (kids) emptyList() else feed.featured
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = if (form.wide) 40.dp else 120.dp)) {
        item("hero") {
            if (featured.isNotEmpty()) {
                Hero(featured, actions, side, searchFocus)
            } else {
                Column(Modifier.statusBarsPadding().padding(horizontal = side).padding(top = 14.dp, bottom = 6.dp)) {
                    if (!kids) TopBar(actions, searchFocus, onImage = false)
                    Text(
                        if (kids) "Мультики и любимое" else "Что посмотрим?",
                        style = if (form.wide) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineLarge,
                        modifier = Modifier.padding(top = 22.dp),
                    )
                }
            }
        }
        fun posters(key: String, title: String, items: List<Title>, all: CatalogFilter? = null) {
            if (items.isEmpty()) return
            item(key) { Shelf(title, side, all?.let { f -> { actions.onCatalog(f) } }) { PosterRow(items, side, actions.onTitle) } }
        }
        fun media(key: String, title: String, items: List<MediaEntity>, first: FocusRequester? = null) {
            if (items.isEmpty()) return
            item(key) {
                Shelf(title, side) {
                    val width = when (form) {
                        FormFactor.TV -> 300.dp
                        FormFactor.TABLET -> 270.dp
                        FormFactor.PHONE -> 230.dp
                    }
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = side, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(if (form.isTv) 18.dp else 12.dp),
                    ) {
                        items(items, key = { it.uri }) { m ->
                            MediaCard(m, width, onClick = { actions.onPlay(m) }, onLongClick = { actions.onMore(m) }, focus = first?.takeIf { m == items.first() })
                        }
                    }
                }
            }
        }
        // On a TV what you were watching comes right under the banner, and the remote starts on it.
        if (form.isTv) media("continue", "Продолжить", state.continueWatching, continueFocus)
        if (update != null && !kids) item("update") { Box(Modifier.padding(horizontal = side, vertical = 8.dp)) { update() } }
        if (!kids && feed.loaded) item("genres") { Genres(side, actions) }
        if (!form.isTv) media("continue", "Продолжить", state.continueWatching)
        if (!kids) {
            posters("later", "Смотреть позже", saved.later)
            posters("loved", "Любимое", saved.loved)
        }
        if (!kids) {
            posters("forYou", "Для вас" + (feed.forYouBecause?.let { " · потому что понравился «$it»" } ?: ""), feed.forYou)
            feed.similarTo?.let { name -> posters("similar", "Похоже на «$name»", feed.similar) }
            posters("trending", "В тренде на этой неделе", feed.trending.filter { f -> featured.none { it.key == f.key } })
            posters("fresh", "Новинки", feed.fresh, CatalogFilter(sort = CatalogSort.LAST))
            posters("films", "Популярные фильмы", feed.films, CatalogFilter(sort = CatalogSort.POPULAR))
            posters("series", "Популярные сериалы", feed.series, CatalogFilter(CatalogType.SERIES, CatalogSort.POPULAR))
            posters("cartoons", "Мультфильмы", feed.cartoons, CatalogFilter(CatalogType.CARTOONS, CatalogSort.POPULAR))
            posters("cinema", "Сейчас в кино", feed.cinema)
            posters("best", "Лучшие фильмы всех времён", feed.best, CatalogFilter(sort = CatalogSort.BEST))
            posters("bestSeries", "Лучшие сериалы", feed.bestSeries, CatalogFilter(CatalogType.SERIES, CatalogSort.BEST))
            feed.genres.forEach { (g, list) ->
                posters("genre-${g.slug}", "Топ: ${g.name.lowercase()}", list, CatalogFilter(sort = CatalogSort.POPULAR, genre = g.slug))
            }
            posters("anime", "Аниме", feed.anime, CatalogFilter(CatalogType.ANIME, CatalogSort.POPULAR))
            posters("rezka", "Новое на HDrezka", feed.rezka)
        }
        media("favorites", "Избранное", state.favorites)
        media("recent", "Недавно открытые", state.recent.filter { r -> state.continueWatching.none { it.uri == r.uri } })
        if (state.isEmpty && !kids && featured.isEmpty() && feed.loaded) item("empty") {
            Text(
                "Найдите фильм или сериал в поиске: Couchy ищет сразу на HDrezka, в онлайн-кинотеатрах и на торрент-трекерах.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = side).padding(top = 20.dp).widthIn(max = 640.dp),
            )
        }
    }
    // The remote starts on the search, so OK immediately opens it.
    // Only once: back from the player, the card that was opened keeps the focus instead.
    var startFocused by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(form, state.loaded) {
        if (form.isTv && state.loaded && !kids && !startFocused && app.couchy.ui.components.FocusMemory.key == null) {
            if (state.continueWatching.isNotEmpty()) {
                kotlinx.coroutines.delay(100)
                runCatching { continueFocus.requestFocus() }.onFailure { runCatching { searchFocus.requestFocus() } }
            } else runCatching { searchFocus.requestFocus() }
            startFocused = true
        }
    }
}

/** The new films, one at a time, turning by themselves; search and logo sit over the picture. */
@Composable
private fun Hero(items: List<Title>, actions: HomeActions, side: Dp, searchFocus: FocusRequester) {
    val form = LocalFormFactor.current
    val pager = rememberPagerState { items.size }
    var interacted by remember { mutableStateOf(false) }
    // After a moment on a banner its trailer plays silently; the banners wait for it to end.
    var clip by remember { mutableStateOf<Pair<String, String>?>(null) }
    var clipPage by remember { mutableStateOf(-1) }
    LaunchedEffect(pager.settledPage, items) {
        clip = null
        val t = items.getOrNull(pager.settledPage) ?: return@LaunchedEffect
        delay(2_000)
        val found = actions.trailer?.invoke(t)
        clipPage = pager.settledPage
        clip = found
    }
    LaunchedEffect(items, interacted, clip) {
        while (!interacted && clip == null && items.size > 1) {
            delay(7_000)
            if (clip != null) break
            pager.animateScrollToPage((pager.currentPage + 1) % items.size)
        }
    }
    val height = when (form) {
        FormFactor.PHONE -> 560.dp
        FormFactor.TABLET -> 480.dp
        FormFactor.TV -> 330.dp
    }
    val bg = MaterialTheme.colorScheme.background
    Box(Modifier.fillMaxWidth().height(height)) {
        HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
            val t = items[page]
            Box(Modifier.fillMaxSize()) {
                Artwork(t.name, Modifier.fillMaxSize()) {
                    AsyncImage(if (form.wide) t.backdrop ?: t.poster else t.poster ?: t.backdrop, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                clip?.takeIf { page == clipPage && page == pager.settledPage }?.let { (url, agent) ->
                    app.couchy.ui.components.TrailerVideo(url, agent, Modifier.fillMaxSize()) {
                        clip = null
                        if (!interacted && items.size > 1) clipPage = -1
                    }
                }
                Box(
                    Modifier.fillMaxSize().background(
                        if (form.wide) Brush.horizontalGradient(0f to bg, 0.55f to bg.copy(alpha = 0.55f), 1f to Color.Transparent)
                        else Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.45f), 0.22f to Color.Transparent, 0.55f to bg.copy(alpha = 0.35f), 1f to bg),
                    ),
                )
                if (form.wide) Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to bg)))
                Column(
                    Modifier.align(Alignment.BottomStart).padding(horizontal = side).padding(bottom = 34.dp).widthIn(max = 560.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        if (t.kind == TitleKind.SERIES) "ПОПУЛЯРНЫЙ СЕРИАЛ" else "СЕЙЧАС СМОТРЯТ",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFFFB070),
                    )
                    Text(
                        t.name,
                        style = if (form == FormFactor.TABLET) MaterialTheme.typography.displayMedium else MaterialTheme.typography.displaySmall,
                        color = Color.White,
                        maxLines = if (form.isTv) 1 else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val meta = listOfNotNull(t.info ?: t.year?.toString(), t.rating).joinToString(" · ")
                    if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.72f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(6.dp))
                    // While the remote is on the button the banners stay put, so it never opens a film that scrolled away.
                    PrimaryButton(
                        "Смотреть", Icons.Rounded.PlayArrow,
                        onClick = { interacted = true; actions.onTitle(t) },
                        modifier = Modifier.onFocusChanged { if (it.isFocused) interacted = true },
                    )
                }
            }
        }
        // Over the picture: the logo and the search, always one tap away.
        Box(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = side).padding(top = 12.dp)) {
            TopBar(actions, searchFocus, onImage = true)
        }
        if (items.size > 1) {
            Row(
                Modifier.align(Alignment.BottomStart).padding(horizontal = side).padding(bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                repeat(items.size) { i ->
                    val on = i == pager.currentPage
                    Box(
                        Modifier.height(6.dp).width(if (on) 18.dp else 6.dp).clip(CircleShape)
                            .background(if (on) Color.White else Color.White.copy(alpha = 0.35f)),
                    )
                }
            }
        }
    }
}

@Composable
private fun TopBar(actions: HomeActions, searchFocus: FocusRequester, onImage: Boolean) {
    val form = LocalFormFactor.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!form.wide) CouchMark(36.dp)
        SearchPill(actions, Modifier.weight(1f).widthIn(max = 720.dp).focusRequester(searchFocus), onImage)
    }
}

/** Looks like a search field; opens the real one, so the keyboard does not pop up over Home. */
@Composable
private fun SearchPill(actions: HomeActions, modifier: Modifier, onImage: Boolean) {
    val form = LocalFormFactor.current
    val shape = RoundedCornerShape(999.dp)
    val fill = if (onImage) Color(0xFF16181F).copy(alpha = 0.62f) else MaterialTheme.colorScheme.surfaceContainerHigh
    val fg = if (onImage) Color.White else MaterialTheme.colorScheme.onSurface
    Row(
        modifier
            .height(if (form.isTv) 56.dp else 48.dp)
            .couchyClickable(shape, focusScale = 1.03f, onClick = actions.onSearch)
            .background(fill, shape)
            .border(1.dp, fg.copy(alpha = 0.14f), shape)
            .padding(start = 16.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Rounded.Search, null, tint = fg, modifier = Modifier.size(22.dp))
        Text(
            "Фильмы и сериалы",
            style = MaterialTheme.typography.bodyLarge,
            color = fg.copy(alpha = 0.65f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        actions.onVoice?.let { voice ->
            Box(Modifier.size(38.dp).couchyClickable(CircleShape, onClick = voice), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Mic, "Голосовой поиск", tint = fg, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun Shelf(title: String, side: Dp, onAll: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Column(Modifier.padding(top = 22.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = side).padding(bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            onAll?.let {
                Text(
                    "Все",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.couchyClickable(RoundedCornerShape(8.dp), onClick = it).padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        content()
    }
}

/** Genres in a row under the banner: one tap to a whole catalogue of them. */
@Composable
private fun Genres(side: Dp, actions: HomeActions) {
    LazyRow(
        contentPadding = PaddingValues(start = side, end = side, top = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        actions.onPick?.let { open ->
            item("pick") {
                val shape = RoundedCornerShape(999.dp)
                Text(
                    "✦ Что посмотреть?",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.background,
                    maxLines = 1,
                    modifier = Modifier
                        .couchyClickable(shape, onClick = open)
                        .background(MaterialTheme.colorScheme.onBackground, shape)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
        items(app.couchy.core.sources.Genres.all.take(14), key = { it.slug }) { g -> val slug = g.slug; val name = g.name
            val shape = RoundedCornerShape(999.dp)
            Text(
                name,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                modifier = Modifier
                    .couchyClickable(shape) { actions.onCatalog(CatalogFilter(genre = slug)) }
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, shape)
                    .border(1.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f), shape)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun PosterRow(items: List<Title>, side: Dp, onTitle: (Title) -> Unit) {
    val form = LocalFormFactor.current
    val width = when (form) {
        FormFactor.TV -> 170.dp
        FormFactor.TABLET -> 150.dp
        FormFactor.PHONE -> 118.dp
    }
    LazyRow(
        contentPadding = PaddingValues(horizontal = side, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(if (form.isTv) 18.dp else 10.dp),
    ) {
        items(items, key = { it.key }) { t -> PosterCard(t, onClick = { onTitle(t) }, modifier = Modifier.width(width)) }
    }
}
