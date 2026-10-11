package app.couchy.ui.search

import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.NotificationsNone
import androidx.compose.material.icons.rounded.NotificationsActive

import androidx.compose.material.icons.rounded.FavoriteBorder

import androidx.compose.material.icons.rounded.Favorite

import androidx.compose.material.icons.rounded.BookmarkBorder

import androidx.compose.material.icons.rounded.Bookmark

import androidx.compose.foundation.border

import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import app.couchy.ui.components.Artwork
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.couchy.core.sources.CatalogFilter
import app.couchy.core.sources.CatalogSort
import app.couchy.core.sources.CatalogType
import app.couchy.core.sources.Link
import app.couchy.core.sources.RezkaCatalog
import app.couchy.core.sources.Site
import app.couchy.core.sources.Title
import app.couchy.core.sources.TitleDetails
import app.couchy.core.sources.SiteHit
import app.couchy.core.sources.SiteSearch
import app.couchy.core.sources.Sites
import app.couchy.core.sources.TitleKind
import app.couchy.core.sources.TorrentHit
import app.couchy.sources.RezkaUi
import app.couchy.sources.SourceStatus
import app.couchy.sources.TitleUi
import app.couchy.ui.components.Chip
import app.couchy.ui.components.LocalFormFactor
import app.couchy.ui.components.PrimaryButton
import app.couchy.ui.components.SecondaryButton
import app.couchy.ui.components.couchyClickable

class TitleActions(
    val onBack: () -> Unit,
    val onTranslator: (Int) -> Unit,
    val onSeason: (Int) -> Unit,
    val onPlay: (season: Int, episode: Int, fromStart: Boolean) -> Unit,
    val onChallenge: () -> Unit,
    val onRetryRezka: () -> Unit,
    val onTorrent: (TorrentHit) -> Unit,
    val onSite: (SiteHit) -> Unit = {},
    val onSearchSite: (Site) -> Unit = {},
    /** An actor's or director's films. */
    val onPerson: (Link) -> Unit = {},
    /** The catalogue narrowed to a genre, country or year. */
    val onCatalog: (CatalogFilter) -> Unit = {},
    /** Another title: a part of the franchise or a similar one. */
    val onTitle: (Title) -> Unit = {},
    /** A trailer on YouTube, by its id. */
    val onTrailer: (String) -> Unit = {},
    val onLater: () -> Unit = {},
    val onLoved: () -> Unit = {},
    /** Hearing about new episodes of a series. */
    val onFollow: () -> Unit = {},
    val onSeen: () -> Unit = {},
    /** The person's score, 1 to 10, or null to take it away. */
    val onRate: (Int?) -> Unit = {},
)

/** A film or series with every way to watch it: one main button, the other sources under it. */
@Composable
fun TitleContent(state: TitleUi, actions: TitleActions, modifier: Modifier = Modifier) {
    val form = LocalFormFactor.current
    val bg = MaterialTheme.colorScheme.background
    Box(modifier.fillMaxSize().background(bg)) {
        // The poster, large and soft at the edges, stands in for a still from the film.
        Box(
            if (form.wide) Modifier.align(Alignment.TopEnd).fillMaxHeight().fillMaxWidth(0.62f)
            else Modifier.fillMaxWidth().height(470.dp),
        ) {
            Artwork(state.title.name, Modifier.fillMaxSize()) {
                // A wide screen gets the wide still; a phone the poster, which fills its tall top better.
                val picture = if (form.wide) state.title.backdrop ?: state.title.poster else state.title.poster ?: state.title.backdrop
                picture?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            }
            Box(
                Modifier.fillMaxSize().background(
                    if (form.wide) Brush.horizontalGradient(0f to bg, 0.45f to bg.copy(alpha = 0.6f), 1f to bg.copy(alpha = 0.15f))
                    else Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.4f), 0.25f to Color.Transparent, 0.6f to bg.copy(alpha = 0.55f), 1f to bg),
                ),
            )
            if (form.wide) Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to bg)))
        }
        val side = if (form.wide) 48.dp else 18.dp
        Sections(
            state,
            actions,
            if (form.wide) Modifier.fillMaxHeight().widthIn(max = 760.dp) else Modifier.fillMaxSize(),
            PaddingValues(start = side, end = side, top = if (form.wide) 96.dp else 300.dp, bottom = 48.dp),
        )
        Box(
            Modifier
                .statusBarsPadding()
                .padding(start = side - 4.dp, top = 10.dp)
                .size(44.dp)
                .couchyClickable(CircleShape, onClick = actions.onBack)
                .background(Color(0xFF16181F).copy(alpha = 0.7f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад", tint = Color.White, modifier = Modifier.size(22.dp))
        }
    }
}

/** What the big button does: the best way to watch, picked for the person. */
private data class MainAction(val label: String, val note: String?, val icon: androidx.compose.ui.graphics.vector.ImageVector?, val run: (() -> Unit)?)

private fun mainAction(state: TitleUi, actions: TitleActions): MainAction {
    val r = state.rezka
    val page = r.page
    if (r.status == SourceStatus.READY && page != null) {
        val voice = page.translators.firstOrNull { it.id == r.translator }?.name
        val note = listOfNotNull("HDrezka", voice, "без рекламы").joinToString(" · ")
        if (!page.series) {
            val started = (state.progress["0:0"] ?: 0f) > 0f && "0:0" !in state.watched
            return MainAction(if (started) "Продолжить" else "Смотреть", note, Icons.Rounded.PlayArrow) { actions.onPlay(0, 0, false) }
        }
        val episodes = r.episodes.sortedWith(compareBy({ it.season }, { it.episode }))
        val next = episodes.firstOrNull { e -> (state.progress["${e.season}:${e.episode}"] ?: 0f) > 0f && "${e.season}:${e.episode}" !in state.watched }
            ?: episodes.firstOrNull { e -> "${e.season}:${e.episode}" !in state.watched }
            ?: episodes.firstOrNull()
        if (next != null) {
            val started = (state.progress["${next.season}:${next.episode}"] ?: 0f) > 0f
            return MainAction("${if (started) "Продолжить" else "Смотреть"} · ${next.season} сезон, ${next.episode} серия", note, Icons.Rounded.PlayArrow) {
                actions.onPlay(next.season, next.episode, false)
            }
        }
    }
    if (r.status == SourceStatus.BLOCKED) return MainAction("Открыть HDrezka", "Сайт просит подтвердить, что вы не бот", Icons.Rounded.VerifiedUser, actions.onChallenge)
    if (r.status == SourceStatus.LOADING) return MainAction("Ищу, где посмотреть…", null, null, null)
    state.sites.firstOrNull()?.let { hit ->
        return MainAction("Смотреть на ${siteName(hit.site)}", "Плеер сайта, без рекламы", Icons.Rounded.PlayArrow) { actions.onSite(hit) }
    }
    val group = state.torrents.firstOrNull { it.label.contains("1080") } ?: state.torrents.firstOrNull()
    val hit = group?.hits?.maxByOrNull { it.seeds }
    if (group != null && hit != null) {
        return MainAction("Смотреть торрент · ${group.label}", "${hit.tracker} · ▲ ${hit.seeds}", Icons.Rounded.PlayArrow) { actions.onTorrent(hit) }
    }
    if (state.sitesLoading || state.torrentsLoading) return MainAction("Ищу, где посмотреть…", null, null, null)
    return MainAction("Не нашлось, где посмотреть", "Попробуйте поиск на сайтах ниже", null, null)
}

@Composable
private fun WatchButton(state: TitleUi, actions: TitleActions) {
    val action = mainAction(state, actions)
    val shape = RoundedCornerShape(16.dp)
    val enabled = action.run != null
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .couchyClickable(shape, focusScale = 1.03f) { action.run?.invoke() }
                .background(if (enabled) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.surfaceContainerHigh, shape)
                .padding(horizontal = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val fg = if (enabled) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onSurfaceVariant
            if (action.icon != null) Icon(action.icon, null, tint = fg, modifier = Modifier.size(24.dp))
            else if (!enabled && action.label.endsWith("…")) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = fg)
            Text(action.label, style = MaterialTheme.typography.titleMedium, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        action.note?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

@Composable
private fun Sections(state: TitleUi, actions: TitleActions, modifier: Modifier, padding: PaddingValues) {
    val t = state.title
    var selected by rememberSaveable(t.name, t.year) { mutableStateOf<String?>(null) }
    val current = sources(state).firstOrNull { it.id == selected } ?: firstReady(state)
    LazyColumn(modifier, contentPadding = padding, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        val details = mergedDetails(state)
        item("head") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Heading(state, details)
                Spacer(Modifier.height(4.dp))
                WatchButton(state, actions)
                Keep(state, actions)
                state.info?.trailer?.let { id ->
                    SecondaryButton("Трейлер", Icons.Rounded.PlayArrow, onClick = { actions.onTrailer(id) }, modifier = Modifier.fillMaxWidth())
                }
                description(state)?.let { About(it, details.slogan) }
                Facts(state.title, details, actions)
            }
        }
        item("services") { Services(state, selected, onSelect = { selected = it }, actions) }
        when (val src = current) {
            Source.Rezka -> item("rezka") { Card("HDrezka") { RezkaBody(state, actions) } }
            is Source.OnSite -> item("site-${src.hit.site}") { Card(siteName(src.hit.site)) { SiteBody(state, src.hit, actions) } }
            Source.Torrents -> {
                if (state.torrentsLoading) item("t-loading") { Card("Торренты") { Loading("Ищу раздачи…") } }
                else if (state.torrents.isEmpty()) item("t-none") { Card("Торренты") { Muted("Раздач не нашлось.") } }
                state.torrents.forEach { group ->
                    item("g-${group.label}") {
                        var open by rememberSaveable(group.label) { mutableStateOf(false) }
                        Card("Торренты · ${group.label}", "${group.hits.size}") {
                            val shown = if (open) group.hits else group.hits.take(4)
                            shown.forEach { hit -> TorrentRow(hit, onClick = { actions.onTorrent(hit) }) }
                            if (group.hits.size > 4) {
                                Text(
                                    if (open) "Свернуть" else "Ещё ${group.hits.size - 4}",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.couchyClickable(RoundedCornerShape(8.dp)) { open = !open }.padding(8.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
        val people = (details.directors.map { it to "режиссёр" } + details.actors.map { it to null }).distinctBy { it.first.url }
        if (people.isNotEmpty()) item("cast") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle("Актёры и съёмочная группа")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                    items(people, key = { it.first.url }) { (p, role) -> PersonTile(p, role, details.photos[p.url]) { actions.onPerson(p) } }
                }
            }
        }
        if (details.parts.isNotEmpty()) item("parts") { Shelf("Все части", details.parts, actions.onTitle) }
        if (details.similar.isNotEmpty()) item("similar") { Shelf("Похожее", details.similar, actions.onTitle) }
    }
}

/** TMDB's Russian data goes first; with Cinemeta (English) the site's Russian names do. */
private fun mergedDetails(state: TitleUi): TitleDetails {
    val site = state.rezka.page?.details ?: TitleDetails()
    val info = state.info ?: return site
    return if (info.russian) info.details.or(site) else site.or(info.details)
}

private fun description(state: TitleUi): String? {
    val info = state.info
    return if (info?.russian == true) info.description ?: state.description else state.description ?: info?.description
}

/** The description, cut to a few lines until tapped. */
@Composable
private fun About(text: String, slogan: String?) {
    var open by rememberSaveable(text) { mutableStateOf(false) }
    Column(
        Modifier.padding(top = 6.dp).couchyClickable(RoundedCornerShape(10.dp), focusScale = 1.01f) { open = !open },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        slogan?.let { Text("«$it»", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground) }
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (open) Int.MAX_VALUE else 4,
            overflow = TextOverflow.Ellipsis,
        )
        if (!open && text.length > 220) Text("Читать полностью", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

/** Genres, director, country and release date; each name opens what else it has. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Facts(title: Title, d: TitleDetails, actions: TitleActions) {
    if (d.genres.isEmpty() && d.directors.isEmpty() && d.countries.isEmpty() && d.released == null) return
    val type = if (title.kind == TitleKind.SERIES) CatalogType.SERIES else CatalogType.FILMS
    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (d.genres.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            d.genres.forEach { g ->
                Tag(g.name) {
                    val filter = if (g.url.startsWith("genre:")) CatalogFilter(type, CatalogSort.POPULAR, genre = g.url.removePrefix("genre:"))
                    else RezkaCatalog.fromGenreLink(g)
                    filter?.let(actions.onCatalog)
                }
            }
        }
        if (d.directors.isNotEmpty()) FactRow("Режиссёр") {
            d.directors.forEach { p -> Tag(p.name) { actions.onPerson(p) } }
        }
        if (d.countries.isNotEmpty()) FactRow("Страна") {
            d.countries.forEach { c -> Tag(c.name) { actions.onCatalog(CatalogFilter(type, CatalogSort.POPULAR, country = c.name)) } }
        }
        d.released?.let { r ->
            FactRow("Премьера") {
                Text(r, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(vertical = 6.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FactRow(label: String, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(84.dp).padding(top = 7.dp))
        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

/** A name that leads somewhere: a pill with a soft outline. */
@Composable
private fun Tag(text: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(999.dp)
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onBackground,
        maxLines = 1,
        modifier = Modifier
            .couchyClickable(shape, onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, shape)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

/** A person as a round monogram with the name under it. */
@Composable
private fun PersonTile(person: Link, role: String?, photo: String?, onClick: () -> Unit) {
    val initials = person.name.split(' ').filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1) }.uppercase()
    val hue = (person.name.hashCode() and 0xFFFF) % 360
    val color = Color.hsv(hue.toFloat(), 0.45f, 0.55f)
    Column(
        Modifier.width(84.dp).couchyClickable(RoundedCornerShape(14.dp), onClick = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.size(64.dp).background(Brush.linearGradient(listOf(color, color.copy(alpha = 0.55f))), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(initials, color = Color.White, style = MaterialTheme.typography.titleMedium)
            photo?.let { AsyncImage(it, person.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(CircleShape)) }
        }
        Text(
            person.name,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        role?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
    }
}

@Composable
private fun Shelf(title: String, items: List<Title>, onTitle: (Title) -> Unit) {
    val width = if (LocalFormFactor.current.wide) 140.dp else 112.dp
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle(title)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
            items(items, key = { it.key }) { t -> PosterCard(t, onClick = { onTitle(t) }, modifier = Modifier.width(width)) }
        }
    }
}

/** One place a title can be watched from; the page shows one at a time, all in the same card style. */
private sealed interface Source {
    val id: String

    data object Rezka : Source {
        override val id = "rezka"
    }

    data class OnSite(val hit: SiteHit) : Source {
        override val id = "site:" + hit.site
    }

    data object Torrents : Source {
        override val id = "torrents"
    }
}

private fun siteName(id: String) = Sites.all.firstOrNull { it.id == id }?.name ?: id

private fun sources(state: TitleUi): List<Source> = listOf(Source.Rezka) + state.sites.map { Source.OnSite(it) } + Source.Torrents

/** Opens on the first source that has the title: HDrezka, then the cinemas, then torrents. */
private fun firstReady(state: TitleUi): Source = when {
    state.rezka.status == SourceStatus.READY || state.rezka.status == SourceStatus.BLOCKED -> Source.Rezka
    state.sites.isNotEmpty() -> Source.OnSite(state.sites.first())
    state.rezka.status == SourceStatus.LOADING -> Source.Rezka
    // HDrezka failed: torrents rather than an error card, while the cinemas are still being asked.
    state.torrents.isNotEmpty() -> Source.Torrents
    state.sitesLoading -> Source.Rezka
    state.torrents.isNotEmpty() || state.torrentsLoading -> Source.Torrents
    else -> Source.Rezka
}

/** The row of services: each with what it has, the chosen one highlighted. */
@Composable
private fun Services(state: TitleUi, selected: String?, onSelect: (String) -> Unit, actions: TitleActions) {
    val current = sources(state).firstOrNull { it.id == selected } ?: firstReady(state)
    var moreSites by remember(state.title.key) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("ИСТОЧНИКИ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 2.dp)) {
            items(sources(state), key = { it.id }) { src ->
                val label = when (src) {
                    Source.Rezka -> "HDrezka" + when (state.rezka.status) {
                        SourceStatus.LOADING -> " · …"
                        SourceStatus.NOT_FOUND -> " · нет"
                        SourceStatus.BLOCKED -> " · проверка"
                        SourceStatus.ERROR -> " · ошибка"
                        SourceStatus.READY -> state.rezka.page?.translators?.size?.takeIf { it > 1 }?.let { " · $it озвучек" } ?: ""
                    }
                    is Source.OnSite -> siteName(src.hit.site)
                    Source.Torrents -> "Торренты · " + if (state.torrentsLoading) "…" else "${state.torrents.sumOf { it.hits.size }}"
                }
                Chip(label, src.id == current.id, onClick = { onSelect(src.id) })
            }
            if (state.sitesLoading) item("sites-loading") { Chip("Ищу на сайтах…", false, onClick = {}) }
            // Cinemas without a readable search, and those that did not find the title by name, open their own search.
            // They show the site itself, so they stay folded until asked for.
            val found = state.sites.map { it.site }.toSet()
            val manual = Sites.all.filter { it.id !in found && (it.id !in SiteSearch.parsed || !state.sitesLoading) }
            if (manual.isNotEmpty() && !moreSites) item("more-sites") { Chip("Искать на сайте вручную…", false, onClick = { moreSites = true }) }
            if (moreSites) items(manual, key = { "find:" + it.id }) { site ->
                Chip("${site.name} ↗", false, onClick = { actions.onSearchSite(site) })
            }
        }
    }
}

/** The card every service's results sit in. */
@Composable
private fun Card(title: String, meta: String? = null, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(18.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
            meta?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        content()
    }
}

@Composable
private fun Loading(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
        Muted(text)
    }
}

@Composable
private fun SiteBody(state: TitleUi, hit: SiteHit, actions: TitleActions) {
    Muted(listOfNotNull(hit.name, hit.year?.toString()).joinToString(" · "))
    Muted("Видео идёт из плеера сайта: озвучку и серию выбираете там, смотрите в Couchy без рекламы.")
    PrimaryButton("Смотреть", Icons.Rounded.PlayArrow, onClick = { actions.onSite(hit) })
}

@Composable
private fun Heading(state: TitleUi, details: TitleDetails) {
    val t = state.title
    val wide = LocalFormFactor.current.wide
    Text(
        t.name,
        style = if (wide) MaterialTheme.typography.displayMedium else MaterialTheme.typography.headlineLarge,
        color = MaterialTheme.colorScheme.onBackground,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
    val line = listOfNotNull(
        t.original?.takeIf { it != t.name },
        t.year?.toString(),
        if (t.kind == TitleKind.SERIES) "сериал" else "фильм",
        details.duration,
        details.age,
    ).joinToString(" · ")
    Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val chips = listOfNotNull(t.rating, state.rezka.page?.rating, state.info?.rating).distinct()
    if (chips.isNotEmpty()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { chips.forEach { RatingChip(it) } }
    }
    t.info?.takeIf { details.isEmpty && it.isNotBlank() && !it.startsWith(t.year?.toString() ?: "-") }?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
    }
}

/**
 * "КП 8.2", "IMDb 7.1": a dark plate so it reads over any poster; the source keeps its colour,
 * the score is white and bold.
 */
@Composable
private fun RatingChip(text: String) {
    val imdb = text.startsWith("IMDb", ignoreCase = true)
    val color = when {
        imdb -> Color(0xFFF5C518)
        text.startsWith("TMDB") -> Color(0xFF01B4E4)
        else -> Color(0xFF3DD68C)
    }
    val source = text.substringBefore(' ', "")
    val score = text.substringAfter(' ')
    Row(
        Modifier.background(Color(0xE6101218), RoundedCornerShape(8.dp))
            .border(1.dp, color.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
            .padding(horizontal = 9.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (source.isNotEmpty()) Text(source, style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.Bold)
        Text(score, style = MaterialTheme.typography.labelLarge, color = Color.White, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
}

@Composable
private fun Muted(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun RezkaBody(state: TitleUi, actions: TitleActions) {
    val r: RezkaUi = state.rezka
    when (r.status) {
        SourceStatus.LOADING -> Loading("Ищу на HDrezka…")
        SourceStatus.NOT_FOUND -> Muted("На HDrezka этого нет. Посмотрите другие сервисы выше.")
        SourceStatus.ERROR -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Muted(r.error ?: "HDrezka не ответила.")
            SecondaryButton("Ещё раз", Icons.Rounded.Refresh, onClick = actions.onRetryRezka)
        }
        SourceStatus.BLOCKED -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Muted("HDrezka просит подтвердить, что вы не бот. Это займёт пару секунд.")
            PrimaryButton("Пройти проверку", Icons.Rounded.VerifiedUser, onClick = actions.onChallenge)
        }
        SourceStatus.READY -> Ready(state, r, actions)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Ready(state: TitleUi, r: RezkaUi, actions: TitleActions) {
    val page = r.page ?: return
    if (page.translators.size > 1) {
        Text("Озвучка", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 2.dp)) {
            items(page.translators, key = { it.id }) { tr -> Chip(tr.name, tr.id == r.translator, onClick = { actions.onTranslator(tr.id) }) }
        }
    } else page.translators.firstOrNull()?.let { Muted("Озвучка: ${it.name}") }

    if (!page.series) {
        val progress = state.progress["0:0"] ?: 0f
        val watched = "0:0" in state.watched
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton(if (progress > 0f && !watched) "Продолжить" else "Смотреть", Icons.Rounded.PlayArrow, onClick = { actions.onPlay(0, 0, false) })
            if (progress > 0f) SecondaryButton("С начала", null, onClick = { actions.onPlay(0, 0, true) })
        }
        return
    }

    val seasons = r.episodes.map { it.season }.distinct()
    if (seasons.size > 1) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 2.dp)) {
            items(seasons) { s -> Chip("Сезон $s", s == r.season, onClick = { actions.onSeason(s) }) }
        }
    }
    if (r.episodesLoading) {
        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
        return
    }
    val season = r.season ?: seasons.firstOrNull() ?: return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        r.episodes.filter { it.season == season }.forEach { e ->
            val key = "${e.season}:${e.episode}"
            EpisodeTile(e.episode, state.progress[key] ?: 0f, key in state.watched) { actions.onPlay(e.season, e.episode, false) }
        }
    }
}

@Composable
private fun EpisodeTile(number: Int, progress: Float, watched: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .width(64.dp)
            .height(46.dp)
            .couchyClickable(shape, onClick = onClick)
            .background(if (watched) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerHigh, shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "$number",
            style = MaterialTheme.typography.titleMedium,
            color = if (watched) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onBackground,
        )
        if (watched) Text("✓", color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, modifier = Modifier.align(Alignment.TopEnd).padding(end = 6.dp, top = 2.dp))
        if (progress > 0f && !watched) {
            Box(Modifier.align(Alignment.BottomStart).padding(horizontal = 8.dp, vertical = 6.dp).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(2.dp))) {
                Box(Modifier.fillMaxWidth(progress).height(3.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)))
            }
        }
    }
}

/** Saving for later, loving it, and the quality to start in, picked before pressing play. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Keep(state: TitleUi, actions: TitleActions) {
    var quality by remember { mutableStateOf(app.couchy.QualityChoice.label) }
    var picking by remember { mutableStateOf(false) }
    var rating by remember { mutableStateOf(false) }
    if (rating) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { rating = false },
            confirmButton = {},
            title = { Text("Ваша оценка") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Оценки 7 и выше подбирают ряд «Для вас» на главной.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        (1..10).forEach { n ->
                            app.couchy.ui.components.Chip("$n", n == state.score, onClick = { actions.onRate(n); rating = false })
                        }
                        if (state.score != null) app.couchy.ui.components.Chip("Убрать", false, onClick = { actions.onRate(null); rating = false })
                    }
                }
            },
        )
    }
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        app.couchy.ui.components.Chip(
            if (state.later) "В «Смотреть позже»" else "Смотреть позже",
            state.later,
            onClick = actions.onLater,
            icon = if (state.later) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
        )
        app.couchy.ui.components.Chip(
            "Любимое",
            state.loved,
            onClick = actions.onLoved,
            icon = if (state.loved) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
        )
        app.couchy.ui.components.Chip(
            "Просмотрено",
            state.seen,
            onClick = actions.onSeen,
            icon = if (state.seen) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
        )
        app.couchy.ui.components.Chip(
            state.score?.let { "Моя оценка: $it" } ?: "Оценить",
            state.score != null,
            onClick = { rating = true },
            icon = if (state.score != null) Icons.Rounded.Star else Icons.Rounded.StarBorder,
        )
        if (state.title.kind == app.couchy.core.sources.TitleKind.SERIES && state.title.imdbId != null) {
            val context = androidx.compose.ui.platform.LocalContext.current
            val ask = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
            ) { }
            app.couchy.ui.components.Chip(
                if (state.following) "Сообщу о новых сериях" else "Сообщать о новых сериях",
                state.following,
                onClick = {
                    if (!state.following && !app.couchy.episodes.NewEpisodes.canNotify(context) && android.os.Build.VERSION.SDK_INT >= 33) {
                        ask.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                    actions.onFollow()
                },
                icon = if (state.following) Icons.Rounded.NotificationsActive else Icons.Rounded.NotificationsNone,
            )
        }
        app.couchy.ui.components.Chip(
            "Качество: ${app.couchy.QualityChoice.name(quality)}",
            false,
            onClick = { picking = true },
            icon = Icons.Rounded.HighQuality,
        )
    }
    if (picking) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text("Качество видео") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Видео начнётся в этом качестве, а если такого нет, то в ближайшем ниже. Это касается онлайн-источников и YouTube.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        app.couchy.QualityChoice.options.forEach { option ->
                            app.couchy.ui.components.Chip(app.couchy.QualityChoice.name(option), option == quality, onClick = {
                                app.couchy.QualityChoice.label = option
                                quality = option
                                picking = false
                            })
                        }
                    }
                }
            },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { picking = false }) { Text("Закрыть") } },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        )
    }
}
