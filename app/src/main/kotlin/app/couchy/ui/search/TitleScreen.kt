package app.couchy.ui.search

import app.couchy.ui.components.SheetHeader
import app.couchy.ui.components.SheetRow
import app.couchy.ui.components.CouchySheet
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.CreateNewFolder
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
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Tv
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
import androidx.compose.ui.focus.focusRequester
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
    /** Marks episodes as watched or not: season, episode, watched, and whether every one before it too. */
    val onMarkEpisode: (season: Int, episode: Int, watched: Boolean, before: Boolean) -> Unit = { _, _, _, _ -> },
    /** Puts the title in a folder or takes it out. */
    val onFolder: (id: String) -> Unit = {},
    /** A new folder with the title in it. */
    val onNewFolder: (name: String) -> Unit = {},
    /** A legal service that has the title: its app, or its page. */
    val onProvider: (app.couchy.core.sources.WatchProvider) -> Unit = {},
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

/** The next thing to go on with: the film or the episode started and not finished. Null when nothing was started. */
private fun continuing(state: TitleUi): Pair<Int, Int>? {
    val r = state.rezka
    val page = r.page ?: return null
    if (r.status != SourceStatus.READY) return null
    fun started(key: String) = (state.progress[key] ?: 0f) > 0f && key !in state.watched
    if (!page.series) return (0 to 0).takeIf { started("0:0") }
    val episodes = r.episodes.sortedWith(compareBy({ it.season }, { it.episode }))
    episodes.firstOrNull { started("${it.season}:${it.episode}") }?.let { return it.season to it.episode }
    // Some episodes watched: the first one not watched yet.
    if (episodes.none { "${it.season}:${it.episode}" in state.watched }) return null
    return episodes.firstOrNull { "${it.season}:${it.episode}" !in state.watched }?.let { it.season to it.episode }
}

/** The torrent most likely to play well: 1080p if there is one, the most seeded in it. */
private fun bestTorrent(state: TitleUi): Pair<String, TorrentHit>? {
    val group = state.torrents.firstOrNull { it.label.contains("1080") } ?: state.torrents.firstOrNull() ?: return null
    return group.hits.maxByOrNull { it.seeds }?.let { group.label to it }
}

/** Whether there is anything at all to watch from, or still something being looked for. */
private fun anyWay(state: TitleUi): Boolean =
    state.rezka.status != SourceStatus.NOT_FOUND || state.sites.isNotEmpty() || state.torrents.isNotEmpty() ||
        state.sitesLoading || state.torrentsLoading || state.info?.providers.orEmpty().isNotEmpty()

/** The steps behind «Смотреть»: where from, which voice-over, which episode; the quality comes last, in the player's dialog. */
private enum class Step { SOURCE, VOICE, EPISODE, TORRENT_VOICE, TORRENT }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Sections(state: TitleUi, actions: TitleActions, modifier: Modifier, padding: PaddingValues) {
    var step by remember(state.title.key) { mutableStateOf<Step?>(null) }
    // Picking a voice from the episodes card changes only the voice; from «Смотреть» it goes on to the episode.
    var voiceOnly by remember { mutableStateOf(false) }
    // The voice picked among the torrents; null for any.
    var torrentVoice by remember(state.title.key) { mutableStateOf<String?>(null) }
    step?.let { s ->
        WatchSteps(state, actions, s, voiceOnly, torrentVoice, onTorrentVoice = { torrentVoice = it }, onStep = { step = it }, onClose = { step = null; voiceOnly = false })
    }
    val r = state.rezka
    LazyColumn(modifier, contentPadding = padding, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        val details = mergedDetails(state)
        item("head") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Heading(state, details)
                Spacer(Modifier.height(4.dp))
                // Step one is right here on the page: where to watch. The voice-over and the quality follow.
                WhereToWatch(state, actions, onStep = { voiceOnly = false; step = it })
                Spacer(Modifier.height(4.dp))
                Keep(state, actions)
                state.info?.trailer?.let { id ->
                    SecondaryButton("Трейлер", Icons.Rounded.PlayArrow, onClick = { actions.onTrailer(id) }, modifier = Modifier.fillMaxWidth())
                }
                description(state)?.let { About(it, details.slogan) }
                Facts(state.title, details, actions)
            }
        }
        // A series keeps its episodes on the page: what was watched, and any episode in one tap.
        if (r.status == SourceStatus.READY && r.page?.series == true) item("episodes") {
            Card("Серии") { Ready(state, r, actions, onVoice = { voiceOnly = true; step = Step.VOICE }) }
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

/**
 * «Смотреть», step by step, as the person asked: where from, then the voice-over, then for a series the
 * episode; the quality is asked last, by the dialog that starts the video.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WatchSteps(
    state: TitleUi,
    actions: TitleActions,
    step: Step,
    voiceOnly: Boolean,
    torrentVoice: String?,
    onTorrentVoice: (String?) -> Unit,
    onStep: (Step) -> Unit,
    onClose: () -> Unit,
) {
    val r = state.rezka
    val page = r.page
    val voices = page?.translators.orEmpty()
    val series = page?.series == true
    fun playRezka(season: Int, episode: Int) { onClose(); actions.onPlay(season, episode, false) }
    fun fromVoice() { if (series) onStep(Step.EPISODE) else playRezka(0, 0) }
    when (step) {
        Step.SOURCE -> CouchySheet("Где смотреть", onClose, step = "Шаг 1 · источник") {
            SourceRows(state, actions, onClose, onRezka = { onStep(rezkaFirst(state)) }, onTorrents = { onStep(torrentFirst(state)) })
        }
        Step.VOICE -> CouchySheet(
            "Озвучка", onClose,
            step = if (voiceOnly) null else "Шаг 2 · озвучка · HDrezka",
            onBack = if (voiceOnly) null else { { onClose() } },
        ) {
            voices.forEach { t ->
                val mark = when {
                    t.director -> "режиссёрская"
                    t.camrip -> "экранка"
                    t.ads -> "с рекламой"
                    else -> null
                }
                SheetRow(
                    t.name,
                    onClick = {
                        actions.onTranslator(t.id)
                        if (voiceOnly) onClose() else fromVoice()
                    },
                    icon = Icons.Rounded.RecordVoiceOver,
                    tag = mark,
                    selected = t.id == r.translator,
                )
            }
        }
        Step.EPISODE -> CouchySheet(
            "Серия", onClose,
            step = "Шаг 3 · серия",
            onBack = { if (voices.isNotEmpty()) onStep(Step.VOICE) else onClose() },
        ) {
            val seasons = r.episodes.map { it.season }.distinct()
            val season = r.season ?: seasons.firstOrNull()
            if (seasons.size > 1) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
                    seasons.forEach { s -> Chip("Сезон $s", s == season, onClick = { actions.onSeason(s) }) }
                }
                Spacer(Modifier.height(6.dp))
            }
            if (r.episodesLoading || season == null) Loading("Загружаю серии…")
            else FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
                r.episodes.filter { it.season == season }.forEach { e ->
                    val key = "${e.season}:${e.episode}"
                    EpisodeTile(e.episode, state.progress[key] ?: 0f, key in state.watched) { playRezka(e.season, e.episode) }
                }
            }
        }
        Step.TORRENT_VOICE -> CouchySheet("Озвучка", onClose, step = "Шаг 2 · озвучка · торрент", onBack = onClose) {
            val all = state.torrents.flatMap { it.hits }
            val counts = all.flatMap { h -> h.voices.distinct() }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
            SheetRow("Любая", onClick = { onTorrentVoice(null); onStep(Step.TORRENT) }, icon = Icons.Rounded.RecordVoiceOver, tag = "${all.size}", selected = torrentVoice == null)
            counts.forEach { (voice, n) ->
                SheetRow(voice, onClick = { onTorrentVoice(voice); onStep(Step.TORRENT) }, icon = Icons.Rounded.RecordVoiceOver, tag = "$n", selected = voice == torrentVoice)
            }
        }
        Step.TORRENT -> CouchySheet(
            "Качество", onClose, step = "Шаг 3 · качество" + (torrentVoice?.let { " · $it" } ?: ""),
            onBack = { if (state.torrents.any { g -> g.hits.any { it.voices.isNotEmpty() } }) onStep(Step.TORRENT_VOICE) else onClose() },
        ) {
            val groups = state.torrents.map { g -> g.copy(hits = g.hits.filter { torrentVoice == null || torrentVoice in it.voices }) }.filter { it.hits.isNotEmpty() }
            val best = (groups.firstOrNull { it.label.contains("1080") } ?: groups.firstOrNull())?.label
            groups.forEach { g ->
                val hit = g.hits.maxByOrNull { it.seeds } ?: return@forEach
                SheetRow(
                    g.label,
                    onClick = { onClose(); actions.onTorrent(hit) },
                    icon = Icons.Rounded.HighQuality,
                    tag = app.couchy.core.torrent.Sizes.bytes(hit.size).takeIf { hit.size > 0 },
                    badge = "ЛУЧШЕЕ".takeIf { g.label == best },
                )
            }
        }
    }
}

/** HDrezka after the source: the voice-over when there is one to pick, else the episode, else the quality. */
private fun rezkaFirst(state: TitleUi): Step = when {
    state.rezka.page?.translators.orEmpty().isNotEmpty() -> Step.VOICE
    else -> Step.EPISODE
}

/** Torrents after the source: the voice-over when the trackers name them, else the quality. */
private fun torrentFirst(state: TitleUi): Step =
    if (state.torrents.any { g -> g.hits.any { it.voices.isNotEmpty() } }) Step.TORRENT_VOICE else Step.TORRENT

/** Step one on the page itself: every source, the best one marked; going on where the person stopped is above them. */
@Composable
private fun WhereToWatch(state: TitleUi, actions: TitleActions, onStep: (Step) -> Unit) {
    val next = continuing(state)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (next != null) {
            SheetRow(
                "Продолжить",
                onClick = { actions.onPlay(next.first, next.second, false) },
                icon = Icons.Rounded.PlayArrow,
                tag = if (state.rezka.page?.series == true) "${next.first} сезон · ${next.second} серия" else "HDrezka",
                selected = true,
            )
            Spacer(Modifier.height(6.dp))
        }
        Text("ГДЕ СМОТРЕТЬ", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
        SourceRows(state, actions, onClose = {}, onRezka = {
            val first = rezkaFirst(state)
            if (first == Step.EPISODE && state.rezka.page?.series != true) actions.onPlay(0, 0, false) else onStep(first)
        }, onTorrents = { onStep(torrentFirst(state)) })
        if (!anyWay(state)) Muted("Нигде не нашлось.")
    }
}

/** Step one: every source that has the title, each with one short word on how it plays; the best is marked. */
@Composable
private fun SourceRows(state: TitleUi, actions: TitleActions, onClose: () -> Unit, onRezka: () -> Unit, onTorrents: () -> Unit) {
    val r = state.rezka
    var bestGiven = false
    // The mark waits until everything above has loaded, so it does not jump from row to row.
    var loadingAbove = false
    fun badge(): String? = if (bestGiven || loadingAbove) null else { bestGiven = true; "ЛУЧШИЙ" }
    val kp = state.sites.firstOrNull { it.site == app.couchy.core.sources.KinoPub.ID }
    kp?.let { SheetRow("Kino.pub", onClick = { onClose(); actions.onSite(it) }, icon = Icons.Rounded.PlayCircle, tag = "до 4K", badge = badge()) }
    when (r.status) {
        SourceStatus.READY -> {
            val n = r.page?.translators?.size ?: 0
            SheetRow("HDrezka", onClick = onRezka, icon = Icons.Rounded.PlayCircle, tag = if (n > 1) "1080p · озвучек: $n" else "1080p", badge = badge())
        }
        SourceStatus.LOADING -> { loadingAbove = true; SheetRow("HDrezka", onClick = {}, icon = Icons.Rounded.PlayCircle, enabled = false, trailing = { Spinner() }) }
        SourceStatus.BLOCKED -> SheetRow("HDrezka", onClick = { onClose(); actions.onChallenge() }, icon = Icons.Rounded.VerifiedUser, tag = "проверка")
        SourceStatus.ERROR -> SheetRow("HDrezka", onClick = actions.onRetryRezka, icon = Icons.Rounded.Refresh, tag = "повторить")
        SourceStatus.NOT_FOUND -> Unit
    }
    // Sources that play in Couchy's own player first, then the torrent, and the sites' own players last.
    val (native, embedded) = state.sites.filter { it != kp }.partition { it.site in app.couchy.sources.NativeClient.ids }
    native.forEach { hit ->
        SheetRow(siteName(hit.site), onClick = { onClose(); actions.onSite(hit) }, icon = Icons.Rounded.PlayCircle, tag = "1080p", badge = badge())
    }
    if (state.sitesLoading) {
        loadingAbove = true
        SheetRow("Онлайн-кинотеатры", onClick = {}, icon = Icons.Rounded.Language, enabled = false, trailing = { Spinner() })
    }
    bestTorrent(state)?.let { (label, _) ->
        // The best picture on offer, so 4K is seen before opening the list.
        val uhd = state.torrents.any { it.label.contains("2160") || it.label.contains("4K", ignoreCase = true) }
        SheetRow("Торрент", onClick = onTorrents, icon = Icons.Rounded.Download, tag = if (uhd) "до 4K" else label, badge = badge())
    } ?: run {
        if (state.torrentsLoading) SheetRow("Торрент", onClick = {}, icon = Icons.Rounded.Download, enabled = false, trailing = { Spinner() })
    }
    embedded.forEach { hit ->
        SheetRow(siteName(hit.site), onClick = { onClose(); actions.onSite(hit) }, icon = Icons.Rounded.Language, tag = "плеер сайта")
    }
    val legal = state.info?.providers.orEmpty()
    if (legal.isNotEmpty()) {
        SheetHeader("Легально, в приложении сервиса")
        legal.forEach { p -> SheetRow(app.couchy.sources.Providers.name(p.name), onClick = { onClose(); actions.onProvider(p) }, icon = Icons.Rounded.Tv, tag = p.how) }
    }
    // Cinemas without a readable search, and those that did not find the title by name: their own search.
    val found = state.sites.map { it.site }.toSet()
    val manual = Sites.all.filter { it.id !in found && (it.id !in SiteSearch.parsed || !state.sitesLoading) }
    if (manual.isNotEmpty()) {
        var open by remember { mutableStateOf(false) }
        SheetHeader("Другие сайты")
        if (!open) SheetRow("Искать вручную", onClick = { open = true }, icon = Icons.Rounded.Search, tag = "${manual.size}")
        else manual.forEach { site -> SheetRow(site.name, onClick = { onClose(); actions.onSearchSite(site) }, icon = Icons.Rounded.Language, tag = "поиск ↗") }
    }
}

@Composable
private fun Spinner() {
    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** TMDB's Russian data goes first; with Cinemeta (English) the site's Russian names do. */
private fun mergedDetails(state: TitleUi): TitleDetails {
    val site = state.rezka.page?.details ?: TitleDetails()
    val info = state.info ?: return site
    return if (info.russian) info.details.or(site) else site.or(info.details)
}

private fun description(state: TitleUi): String? {
    val info = state.info
    return if (info?.russian == true) info.description ?: state.description ?: state.kp?.description
    else state.description ?: state.kp?.description ?: info?.description
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

private fun siteName(id: String) = app.couchy.sources.NativeClient.name(id) ?: Sites.all.firstOrNull { it.id == id }?.name ?: id

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
    // Kinopoisk's own score first when there is one; one chip per source.
    val chips = listOfNotNull(state.kp?.chip, t.rating, state.rezka.page?.rating, state.info?.rating)
        .distinctBy { it.substringBefore(' ').uppercase().replace("KP", "КП") }
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Ready(state: TitleUi, r: RezkaUi, actions: TitleActions, onVoice: () -> Unit) {
    val page = r.page ?: return
    if (page.translators.size > 1) {
        val name = page.translators.firstOrNull { it.id == r.translator }?.name ?: "Озвучка"
        Chip(name, false, onClick = onVoice, icon = Icons.Rounded.RecordVoiceOver)
    }

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
    // A long press on an episode: mark it, or everything up to it, as watched.
    var menu by remember { mutableStateOf<app.couchy.core.sources.EpisodeRef?>(null) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        r.episodes.filter { it.season == season }.forEach { e ->
            val key = "${e.season}:${e.episode}"
            EpisodeTile(e.episode, state.progress[key] ?: 0f, key in state.watched, onLongClick = { menu = e }) { actions.onPlay(e.season, e.episode, false) }
        }
    }
    menu?.let { e ->
        val watched = "${e.season}:${e.episode}" in state.watched
        app.couchy.ui.player.OptionsDialog(
            "Сезон ${e.season}, серия ${e.episode}",
            listOf(
                app.couchy.ui.player.OptionSection(
                    null,
                    listOfNotNull(
                        app.couchy.ui.player.Option(if (watched) "Снять отметку «просмотрено»" else "Отметить просмотренной", false) {
                            actions.onMarkEpisode(e.season, e.episode, !watched, false); menu = null
                        },
                        app.couchy.ui.player.Option("Отметить все серии до этой", false) {
                            actions.onMarkEpisode(e.season, e.episode, true, true); menu = null
                        }.takeIf { e.episode > 1 },
                        app.couchy.ui.player.Option("Смотреть с начала", false) { menu = null; actions.onPlay(e.season, e.episode, true) },
                    ),
                ),
            ),
            onDismiss = { menu = null },
        )
    }
}

@Composable
private fun EpisodeTile(number: Int, progress: Float, watched: Boolean, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .width(64.dp)
            .height(46.dp)
            .couchyClickable(shape, onLongClick = onLongClick, onClick = onClick)
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
    // One even row of icons with short captions, like the actions under a film in a store.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        val cell = Modifier.weight(1f)
        KeepAction(if (state.later) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, "Позже", state.later, actions.onLater, cell)
        KeepAction(if (state.loved) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "Любимое", state.loved, actions.onLoved, cell)
        KeepAction(if (state.seen) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, "Смотрел", state.seen, actions.onSeen, cell)
        KeepAction(
            if (state.score != null) Icons.Rounded.Star else Icons.Rounded.StarBorder,
            state.score?.let { "Моя: $it" } ?: "Оценить",
            state.score != null,
            { rating = true },
            cell,
        )
        if (state.title.kind == app.couchy.core.sources.TitleKind.SERIES && state.title.imdbId != null) {
            FollowAction(state.following, actions.onFollow, cell)
        } else if (state.title.imdbId != null && (state.title.year ?: Int.MAX_VALUE) >= java.time.LocalDate.now().year - 1) {
            // A new film: «Жду» tells the day it can be watched at home.
            FollowAction(state.following, actions.onFollow, cell, on = "Жду ✓", off = "Жду")
        }
        val inSome = state.inFolders.isNotEmpty()
        KeepAction(
            if (inSome) Icons.Rounded.Folder else Icons.Rounded.CreateNewFolder,
            if (state.inFolders.size == 1) state.folders.firstOrNull { it.id in state.inFolders }?.name ?: "В папке" else if (inSome) "В папках" else "В папку",
            inSome,
            { picking = true },
            cell,
        )
    }
    if (picking) FolderDialog(state, actions, onDismiss = { picking = false })
}

/**
 * The person's folders as a list of rows, one tap puts the title in or takes it out; a row at the
 * end makes a new folder with the title already in it. Rows, not chips, so a remote reaches each.
 */
@Composable
private fun FolderDialog(state: TitleUi, actions: TitleActions, onDismiss: () -> Unit) {
    var creating by remember { mutableStateOf(state.folders.isEmpty()) }
    var name by remember { mutableStateOf("") }
    val create = {
        if (name.isNotBlank()) {
            actions.onNewFolder(name)
            name = ""
            creating = false
        }
    }
    app.couchy.ui.components.CouchySheet("В папку", onDismiss) {
        if (state.folders.isEmpty()) {
            Text(
                "Свои списки: «На Новый год», «С детьми», «Ужасы на выходные». Они в «Моё» и синхронизируются.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        state.folders.forEach { f ->
            val inside = f.id in state.inFolders
            app.couchy.ui.components.SheetRow(
                f.name,
                onClick = { actions.onFolder(f.id) },
                icon = if (inside) Icons.Rounded.Folder else Icons.Rounded.CreateNewFolder,
                tag = if (inside) "здесь" else f.titles.size.takeIf { it > 0 }?.toString(),
                selected = inside,
                trailing = {
                    Icon(
                        if (inside) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                        null,
                        tint = if (inside) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        }
        if (creating) {
            val field = remember { androidx.compose.ui.focus.FocusRequester() }
            androidx.compose.material3.OutlinedTextField(
                name,
                { name = it },
                placeholder = { Text("Название папки", maxLines = 1) },
                singleLine = true,
                trailingIcon = {
                    if (name.isNotBlank()) androidx.compose.material3.IconButton(onClick = create) {
                        Icon(Icons.Rounded.CheckCircle, "Создать")
                    }
                },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { create() }),
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp).focusRequester(field),
            )
            if (state.folders.isNotEmpty()) androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
        } else {
            app.couchy.ui.components.SheetRow("Новая папка", onClick = { creating = true }, icon = Icons.Rounded.Add)
        }
    }
}

/** An icon in a soft circle over a one-line caption; filled with the accent when on. */
@Composable
private fun KeepAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    on: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
    Column(
        modifier.couchyClickable(shape, onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.size(44.dp).background(
                if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceContainerHigh,
                androidx.compose.foundation.shape.CircleShape,
            ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

/** Following a series for new episodes; asks for notifications the first time. */
@Composable
private fun FollowAction(following: Boolean, onFollow: () -> Unit, modifier: Modifier, on: String = "Слежу", off: String = "Следить") {
    val context = androidx.compose.ui.platform.LocalContext.current
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { }
    KeepAction(
        if (following) Icons.Rounded.NotificationsActive else Icons.Rounded.NotificationsNone,
        if (following) on else off,
        following,
        {
            if (!following && !app.couchy.episodes.NewEpisodes.canNotify(context) && android.os.Build.VERSION.SDK_INT >= 33) {
                ask.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
            onFollow()
        },
        modifier,
    )
}
