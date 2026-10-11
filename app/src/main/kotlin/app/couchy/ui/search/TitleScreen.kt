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
                    PosterCard(state.title.copy(rating = state.title.rating), onClick = {}, modifier = Modifier.fillMaxWidth())
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
    LazyColumn(modifier, contentPadding = padding, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item("head") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (phoneHeader) {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        SecondaryButton(null, Icons.AutoMirrored.Rounded.ArrowBack, onClick = actions.onBack)
                        Spacer(Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        PosterCard(t.copy(name = ""), onClick = {}, modifier = Modifier.width(112.dp))
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
        item("online") { Online(state, actions) }
        item("torrents-head") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle("Торренты")
                if (state.torrentsLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                else Text("${state.torrents.sumOf { it.hits.size }} раздач", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (!state.torrentsLoading && state.torrents.isEmpty()) {
            item("torrents-none") { Muted("Раздач не нашлось.") }
        }
        state.torrents.forEach { group ->
            item("g-${group.label}") {
                var open by rememberSaveable(group.label) { mutableStateOf(false) }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(group.label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Online(state: TitleUi, actions: TitleActions) {
    val r: RezkaUi = state.rezka
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(18.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Онлайн", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
            Text(
                "HDrezka",
                color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.background(Color(0xFF3B6EA8), RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 2.dp),
            )
        }
        when (r.status) {
            SourceStatus.LOADING -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                Muted("Ищу на HDrezka…")
            }
            SourceStatus.NOT_FOUND -> Muted("На HDrezka этого нет.")
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
