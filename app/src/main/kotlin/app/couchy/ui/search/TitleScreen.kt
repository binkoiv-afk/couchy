package app.couchy.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import app.couchy.core.sources.Site
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
)

/** A film or series with every way to watch it: online sources first, then torrents by quality. */
@Composable
fun TitleContent(state: TitleUi, actions: TitleActions, modifier: Modifier = Modifier) {
    val wide = LocalFormFactor.current.wide
    val side = if (wide) 40.dp else 18.dp
    Box(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        if (wide) {
            Row(Modifier.fillMaxSize().padding(horizontal = side, vertical = 24.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                Column(Modifier.width(250.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SecondaryButton("Назад", Icons.AutoMirrored.Rounded.ArrowBack, onClick = actions.onBack)
                    PosterCard(state.title, onClick = {}, modifier = Modifier.fillMaxWidth(), caption = false)
                }
                Sections(state, actions, Modifier.weight(1f).widthIn(max = 1000.dp), PaddingValues(bottom = 32.dp))
            }
        } else {
            Sections(state, actions, Modifier.fillMaxSize(), PaddingValues(start = side, end = side, top = 12.dp, bottom = 32.dp), phoneHeader = true)
        }
    }
}

@Composable
private fun Sections(state: TitleUi, actions: TitleActions, modifier: Modifier, padding: PaddingValues, phoneHeader: Boolean = false) {
    val t = state.title
    var selected by rememberSaveable(t.name, t.year) { mutableStateOf<String?>(null) }
    val current = sources(state).firstOrNull { it.id == selected } ?: firstReady(state)
    LazyColumn(modifier, contentPadding = padding, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item("head") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (phoneHeader) {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        SecondaryButton(null, Icons.AutoMirrored.Rounded.ArrowBack, onClick = actions.onBack)
                        Spacer(Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        PosterCard(t, onClick = {}, modifier = Modifier.width(112.dp), caption = false)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) { Heading(state) }
                    }
                } else {
                    Heading(state)
                }
                state.description?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 5, overflow = TextOverflow.Ellipsis)
                }
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
    state.rezka.status == SourceStatus.LOADING || state.sitesLoading -> Source.Rezka
    state.torrents.isNotEmpty() || state.torrentsLoading -> Source.Torrents
    else -> Source.Rezka
}

/** The row of services: each with what it has, the chosen one highlighted. */
@Composable
private fun Services(state: TitleUi, selected: String?, onSelect: (String) -> Unit, actions: TitleActions) {
    val current = sources(state).firstOrNull { it.id == selected } ?: firstReady(state)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle("Где смотреть")
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
            val found = state.sites.map { it.site }.toSet()
            items(Sites.all.filter { it.id !in found && (it.id !in SiteSearch.parsed || !state.sitesLoading) }, key = { "find:" + it.id }) { site ->
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
private fun Heading(state: TitleUi) {
    val t = state.title
    Text(t.name, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground, maxLines = 3, overflow = TextOverflow.Ellipsis)
    val line = listOfNotNull(
        t.original?.takeIf { it != t.name },
        t.year?.toString(),
        if (t.kind == TitleKind.SERIES) "сериал" else "фильм",
    ).joinToString(" · ")
    Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val extra = listOfNotNull(t.rating, t.info).joinToString(" · ")
    if (extra.isNotEmpty()) Text(extra, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
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
