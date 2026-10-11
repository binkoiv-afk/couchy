package app.couchy

import app.couchy.ui.search.WatchDialog
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import app.couchy.ui.components.couchyClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.couchy.core.Links
import app.couchy.data.MediaEntity
import app.couchy.ui.components.ItemActionsDialog
import app.couchy.ui.components.CastDialog
import app.couchy.ui.components.PinDialog
import app.couchy.ui.shell.LocalKidsMode
import app.couchy.core.cast.CastRequest
import app.couchy.ui.components.LocalFormFactor
import app.couchy.ui.components.rememberFormFactor
import app.couchy.ui.home.HomeActions
import app.couchy.ui.home.HomeContent
import app.couchy.sources.SearchMode
import app.couchy.ui.library.LibraryActions
import app.couchy.ui.library.LibraryContent
import app.couchy.ui.open.OpenDialog
import app.couchy.torrent.TorrentViewModel
import app.couchy.sources.SearchViewModel
import app.couchy.ui.search.ChallengeDialog
import app.couchy.ui.search.SiteWindowDialog
import app.couchy.core.sources.SiteRef
import app.couchy.ui.search.SearchActions
import app.couchy.ui.search.SearchContent
import app.couchy.ui.search.TitleActions
import app.couchy.ui.search.TitleContent
import app.couchy.ui.search.ListingActions
import app.couchy.ui.search.ListingContent
import app.couchy.sources.ListQuery
import android.speech.RecognizerIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import app.couchy.tv.ChannelNow
import app.couchy.ui.torrent.TorrentActions
import app.couchy.ui.torrent.TorrentContent
import androidx.activity.compose.BackHandler
import app.couchy.tv.TvState
import app.couchy.tv.TvViewModel
import app.couchy.ui.player.LiveUi
import app.couchy.ui.player.PlayerScreen
import app.couchy.ui.tv.AddPlaylistDialog
import app.couchy.ui.tv.ChannelDialog
import app.couchy.ui.tv.PlaylistsDialog
import app.couchy.ui.tv.TvActions
import app.couchy.ui.tv.TvContent
import app.couchy.ui.settings.SettingsActions
import app.couchy.ui.settings.SettingsContent
import app.couchy.ui.settings.BypassUi
import app.couchy.ui.settings.SyncUi
import app.couchy.sync.SyncStatus
import androidx.lifecycle.repeatOnLifecycle
import app.couchy.bypass.BypassStatus
import app.couchy.ui.settings.UpdateState
import app.couchy.update.UpdateCenter
import app.couchy.ui.settings.UpdateDialog
import app.couchy.ui.shell.Destination
import app.couchy.ui.shell.Shell
import app.couchy.ui.theme.CouchyTheme
import app.couchy.update.AppUpdate
import app.couchy.update.Updates
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private val tvVm: TvViewModel by viewModels()
    private val torrentVm: TorrentViewModel by viewModels()
    private val searchVm: SearchViewModel by viewModels()
    private val ytVm: app.couchy.youtube.YouTubeViewModel by viewModels()

    /** Bumped when the app comes back after a long time away, to start fresh on Home. */
    private val freshStart = MutableStateFlow(0)

    /** A series whose new-episode notification was tapped. */
    private val openTitle = MutableStateFlow<app.couchy.core.sources.Title?>(null)
    private var stoppedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Listens for "watch on TV" from other devices while Couchy is open.
        (application as CouchyApp).cast.start()
        if (savedInstanceState == null) handle(intent)
        setContent {
            val prefs by vm.prefs.collectAsStateWithLifecycle()
            val loaded = prefs ?: return@setContent
            CouchyTheme(loaded.theme) {
                CompositionLocalProvider(LocalFormFactor provides rememberFormFactor(loaded.uiMode), LocalKidsMode provides loaded.kidsMode) {
                    val fresh by freshStart.collectAsStateWithLifecycle()
                    MainScreen(vm, tvVm, torrentVm, searchVm, ytVm, fresh, openTitle)
                }
            }
        }
    }

    /** singleTask: every new launch or shared link lands here instead of reviving an old screen. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Leaving Couchy for good stops the built-in torrent engine; a rotation does not.
        if (isFinishing) {
            (application as CouchyApp).torrEngine.stop()
            (application as CouchyApp).bypass.stop()
            (application as CouchyApp).cast.stop()
        }
    }

    override fun onStop() {
        super.onStop()
        // What was just watched reaches the other devices.
        (application as CouchyApp).sync.syncSoon()
        stoppedAt = SystemClock.elapsedRealtime()
    }

    override fun onStart() {
        super.onStart()
        (application as CouchyApp).sync.syncSoon()
        // Hours in the background on a TV box: come back to a fresh Home, not a stale screen.
        if (stoppedAt > 0 && SystemClock.elapsedRealtime() - stoppedAt > STALE_AFTER_MS && vm.playback.value == null) {
            freshStart.value += 1
        }
    }

    private fun handle(intent: Intent?) {
        app.couchy.episodes.NewEpisodes.titleOf(intent)?.let { openTitle.value = it; return }
        val link = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
                ?.let { text -> Regex("""(https?|magnet)\S+""").find(text)?.value }
            else -> null
        } ?: return
        if (Links.looksLikeLink(link)) vm.open(link)
    }

    companion object {
        private const val STALE_AFTER_MS = 30 * 60 * 1000L
    }
}

@Composable
private fun MainScreen(
    vm: AppViewModel,
    tvVm: TvViewModel,
    torrentVm: TorrentViewModel,
    searchVm: SearchViewModel,
    ytVm: app.couchy.youtube.YouTubeViewModel,
    freshStart: Int,
    openTitle: MutableStateFlow<app.couchy.core.sources.Title?>,
) {
    val form = LocalFormFactor.current
    val playback by vm.playback.collectAsStateWithLifecycle()
    val home by vm.home.collectAsStateWithLifecycle()
    val library by vm.libraryState.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    var destination by rememberSaveable { mutableStateOf(Destination.HOME) }
    var showOpen by rememberSaveable { mutableStateOf(false) }
    var searchFocus by remember { mutableStateOf(0) }
    var actionsFor by remember { mutableStateOf<MediaEntity?>(null) }
    val tv by tvVm.state.collectAsStateWithLifecycle()
    val schedule by tvVm.schedule.collectAsStateWithLifecycle()
    val focusedChannel by tvVm.focusedChannel.collectAsStateWithLifecycle()
    val pendingPlaylist by vm.pendingPlaylist.collectAsStateWithLifecycle()
    var addPlaylist by rememberSaveable { mutableStateOf<String?>(null) }
    var showPlaylists by rememberSaveable { mutableStateOf(false) }
    var channelFor by remember { mutableStateOf<ChannelNow?>(null) }
    val torrent by torrentVm.state.collectAsStateWithLifecycle()
    val torrentAutoPlay by torrentVm.autoPlay.collectAsStateWithLifecycle()
    val pendingTorrent by vm.pendingTorrent.collectAsStateWithLifecycle()
    val search by searchVm.search.collectAsStateWithLifecycle()
    val feed by searchVm.feed.collectAsStateWithLifecycle()
    LaunchedEffect(destination) { if (destination == Destination.HOME) searchVm.loadFeed() }
    val titlePage by searchVm.title.collectAsStateWithLifecycle()
    val route by searchVm.route.collectAsStateWithLifecycle()
    val listing by searchVm.listing.collectAsStateWithLifecycle()
    val challenge by searchVm.challenge.collectAsStateWithLifecycle()
    val sourceAutoPlay by searchVm.autoPlay.collectAsStateWithLifecycle()
    val sourceBusy by searchVm.busy.collectAsStateWithLifecycle()
    val sourceError by searchVm.error.collectAsStateWithLifecycle()
    val youtube by ytVm.state.collectAsStateWithLifecycle()
    val saved by searchVm.saved.collectAsStateWithLifecycle()
    val following by searchVm.following.collectAsStateWithLifecycle()
    val notifiedTitle by openTitle.collectAsStateWithLifecycle()
    LaunchedEffect(notifiedTitle) {
        notifiedTitle?.let { t ->
            searchVm.openTitle(t)
            openTitle.value = null
        }
    }
    val ytAutoPlay by ytVm.autoPlay.collectAsStateWithLifecycle()
    val ytBusy by ytVm.busy.collectAsStateWithLifecycle()
    val ytError by ytVm.error.collectAsStateWithLifecycle()
    LaunchedEffect(destination) { if (destination == Destination.YOUTUBE) ytVm.start() }
    LaunchedEffect(ytAutoPlay) { ytAutoPlay?.let { vm.playDirect(it); ytVm.autoPlayHandled() } }
    val siteWindow by searchVm.siteWindow.collectAsStateWithLifecycle()
    val bypassEngine = (LocalContext.current.applicationContext as CouchyApp).bypass
    val cast = (LocalContext.current.applicationContext as CouchyApp).cast
    var castRequest by remember { mutableStateOf<CastRequest?>(null) }
    val kids = LocalKidsMode.current
    var askPin by remember { mutableStateOf(false) }
    LaunchedEffect(kids) {
        if (kids) {
            destination = Destination.HOME
            searchVm.closeTitle()
            torrentVm.close()
        }
    }
    if (askPin) PinDialog(check = vm::exitKids, onDismiss = { askPin = false })
    LaunchedEffect(cast) { cast.incoming.collect { vm.openCast(it) } }
    val sync = (LocalContext.current.applicationContext as CouchyApp).sync
    val downloads = (LocalContext.current.applicationContext as CouchyApp).downloads
    val offline by downloads.items.collectAsStateWithLifecycle()
    // "Кто смотрит?" once per launch when the family has several profiles.
    val activityContext = LocalContext.current
    var askProfile by rememberSaveable { mutableStateOf(app.couchy.data.Profiles.shouldAsk(activityContext)) }
    if (askProfile) {
        WhoIsWatching(
            onPick = { p ->
                askProfile = false
                if (p.id != app.couchy.data.Profiles.currentId(activityContext)) {
                    (activityContext as? android.app.Activity)?.let { app.couchy.data.Profiles.switch(it, p.id) }
                }
            },
        )
        return
    }
    val syncStatus by sync.status.collectAsStateWithLifecycle()
    // Leaving the player and every few minutes on a TV box that stays open: positions travel.
    LaunchedEffect(playback == null) { if (playback == null) sync.syncSoon() }
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while (true) {
                kotlinx.coroutines.delay(5 * 60_000L)
                sync.syncSoon()
            }
        }
    }
    val bypassStatus by bypassEngine.status.collectAsStateWithLifecycle()
    val bypassUi = BypassUi(
        status = when (val st = bypassStatus) {
            BypassStatus.Idle -> "Не запущен, включится сам, когда понадобится"
            BypassStatus.Starting -> "Подключаю…"
            is BypassStatus.Ready -> "Подключён: ${st.via}"
            is BypassStatus.Failed -> st.message
        },
        available = bypassEngine.available,
    )
    val pendingSource by vm.pendingSource.collectAsStateWithLifecycle()
    LaunchedEffect(freshStart) {
        if (freshStart > 0) {
            destination = Destination.HOME; showOpen = false; actionsFor = null; channelFor = null; showPlaylists = false
            torrentVm.close()
            searchVm.closeTitle()
        }
    }
    LaunchedEffect(pendingSource) {
        pendingSource?.let { ref ->
            when {
                app.couchy.core.youtube.YouTubeLinks.isVideo(ref) -> ytVm.play(ref)
                SiteRef.isRef(ref) -> searchVm.openSiteRef(ref)
                else -> searchVm.playRef(ref)
            }
            vm.sourceHandled()
        }
    }
    LaunchedEffect(sourceAutoPlay) {
        sourceAutoPlay?.let { vm.playDirect(it); searchVm.autoPlayHandled() }
    }
    LaunchedEffect(pendingTorrent) {
        pendingTorrent?.let { link ->
            showOpen = false
            actionsFor = null
            torrentVm.open(link)
            vm.torrentHandled()
        }
    }
    LaunchedEffect(torrentAutoPlay) {
        torrentAutoPlay?.let { vm.playDirect(it); torrentVm.autoPlayHandled() }
    }
    val tvProblem by tvVm.problem.collectAsStateWithLifecycle()
    LaunchedEffect(tvProblem) {
        tvProblem?.let { vm.showNotice(it); tvVm.problemShown() }
    }
    LaunchedEffect(pendingPlaylist) {
        pendingPlaylist?.let { link ->
            destination = Destination.TV
            showOpen = false
            addPlaylist = link
            vm.playlistHandled()
        }
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var googleAnswer by remember { mutableStateOf<kotlinx.coroutines.CompletableDeferred<androidx.activity.result.ActivityResult>?>(null) }
    val googleScreen = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { googleAnswer?.complete(it) }
    var yandexReport by remember { mutableStateOf<((String?) -> Unit)?>(null) }
    // The Yandex SDK reads its id from the manifest; without one it is not created at all.
    val yandexContract = remember {
        if (app.couchy.sync.YandexAuth.configured) runCatching { com.yandex.authsdk.YandexAuthSdkContract(com.yandex.authsdk.YandexAuthOptions(context)) }.getOrNull() else null
    }
    val yandexLogin = yandexContract?.let { contract ->
        rememberLauncherForActivityResult(contract) { result ->
            val report = yandexReport ?: return@rememberLauncherForActivityResult
            when (result) {
                is com.yandex.authsdk.YandexAuthResult.Success -> scope.launch {
                    report(runCatching { sync.connectYandex(result.token.value); null }.getOrElse { it.message ?: "Не удалось подключиться." })
                }
                is com.yandex.authsdk.YandexAuthResult.Failure -> report("Яндекс не пустил: ${result.exception.message}")
                else -> report("Вход отменён.")
            }
        }
    }
    val update by UpdateCenter.state.collectAsStateWithLifecycle()
    val readyApk by UpdateCenter.ready.collectAsStateWithLifecycle()
    // A new build found on start is offered once; after that the strip on Home keeps it in sight.
    var offer by rememberSaveable { mutableStateOf(false) }
    var offered by rememberSaveable { mutableStateOf(false) }
    var pendingApk by remember { mutableStateOf<java.io.File?>(null) }
    LaunchedEffect(Unit) {
        if (UpdateCenter.check(quiet = true) != null && !offered) { offered = true; offer = true }
    }
    fun installUpdate() {
        offer = false
        UpdateCenter.download(context)
    }
    // The download finished, wherever the person is in the app: the installer opens.
    LaunchedEffect(readyApk) {
        readyApk?.let { apk ->
            UpdateCenter.handed()
            // Without the permission the system screen for it opens; installing goes on when the person comes back.
            if (!Updates.install(context, apk)) pendingApk = apk
        }
    }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            val apk = pendingApk
            if (apk != null && Updates.canInstall(context)) {
                pendingApk = null
                Updates.install(context, apk)
            }
        }
    }
    val updateTarget = UpdateCenter.available
    if (offer && updateTarget != null) {
        UpdateDialog(updateTarget, update, onUpdate = ::installUpdate, onLater = { offer = false })
    }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { showOpen = false; vm.open(uri.toString()) }
    }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let {
            destination = Destination.SEARCH
            searchVm.submit(it)
        }
    }
    val voiceIntent = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Что посмотреть?")
    }
    val hasVoice = remember { voiceIntent.resolveActivity(context.packageManager) != null }

    challenge?.let { url ->
        ChallengeDialog(url, searchVm.userAgent, onPassed = searchVm::challengePassed, onDismiss = searchVm::challengeDismissed)
    }
    sourceError?.let { text -> NoticeDialog(text, searchVm::dismissError) }
    ytError?.let { text -> NoticeDialog(text, ytVm::dismissError) }
    siteWindow?.let { w ->
        SiteWindowDialog(
            w,
            searchVm.userAgent,
            onVideo = { url, headers, pageUrl -> scope.launch { vm.playDirect(searchVm.siteCaught(w, url, headers, pageUrl)) } },
            onDismiss = searchVm::closeSite,
        )
    }

    castRequest?.let { request ->
        CastDialog(
            cast,
            request,
            onSent = { device ->
                castRequest = null
                if (playback?.key == request.link || playback?.uri == request.link) vm.closePlayer()
                vm.showNotice("Отправил на ${device.name}.")
            },
            onDismiss = { castRequest = null },
        )
    }

    // Shown over whatever is open: a playlist link can arrive while something plays.
    addPlaylist?.let { initial ->
        AddPlaylistDialog(
            initialUrl = initial,
            onM3u = { name, url, epg -> tvVm.addM3u(name, url, epg) },
            onXtream = { name, server, user, password -> tvVm.addXtream(name, server, user, password) },
            onDismiss = { addPlaylist = null },
            onPickFile = { runCatching { pickFile.launch(arrayOf("*/*")) } },
        )
    }

    // The last two live channels, for the "previous channel" button and key.
    var channelTrail by remember { mutableStateOf(listOf<Long>()) }
    val liveId = playback?.live?.takeIf { it.archiveOf == null }?.channelId
    LaunchedEffect(liveId) {
        if (liveId != null && channelTrail.lastOrNull() != liveId) channelTrail = (channelTrail + liveId).takeLast(2)
    }

    val current = playback
    if (current != null) {
        val live = current.live
        val onAir = live?.takeIf { it.archiveOf == null }
        PlayerScreen(
            playback = current,
            touch = !form.isTv,
            onProgress = { pos, dur -> vm.saveProgress(current.key, pos, dur) },
            onTracksChosen = { audio, subs -> vm.saveTracks(current.key, audio, subs) },
            onExit = vm::closePlayer,
            live = live?.takeIf { it.archiveOf == null }?.let { l -> liveUi(tv, l.channelId, current.title) },
            onZap = live?.takeIf { it.archiveOf == null && it.zapOrder.size > 1 }?.let { l ->
                { delta: Int -> scope.launch { tvVm.zap(l, delta)?.let(vm::playDirect) }; Unit }
            },
            status = current.torrent?.let { torrentVm.statusLine(torrent) },
            onCast = if (current.live != null || CastRequest.isLocalOnly(current.key)) null else ({ pos, dur ->
                castRequest = CastRequest(
                    current.key, current.title, pos, dur,
                    headers = if (current.key == current.uri) current.headers else emptyMap(),
                )
            }),
            onVariant = { url, position -> vm.playDirect(current.copy(uri = url, startMs = position)) },
            onDownload = if (current.live == null && current.uri.startsWith("http")) ({
                downloads.start(current)
            }) else null,
            onSubtitle = { label, uri, language, position ->
                vm.saveTracks(current.key, current.audioLanguage, language)
                vm.playDirect(current.copy(subtitles = current.subtitles + (label to uri), subtitleLanguage = language, startMs = position))
            },
            hasNext = current.torrent?.nextFileId != null || current.nextRef != null,
            onFavorite = onAir?.let { l -> { tvVm.toggleFavorite(l.channelId); Unit } },
            onPrevious = onAir?.let { l ->
                channelTrail.takeIf { it.size == 2 && it.last() == l.channelId }?.first()?.let { prev ->
                    { scope.launch { tvVm.jump(l, prev)?.let(vm::playDirect) }; Unit }
                }
            },
            onNumber = onAir?.takeIf { it.zapOrder.isNotEmpty() }?.let { l ->
                { n: Int ->
                    scope.launch { tvVm.byNumber(l, n)?.let(vm::playDirect) }
                    Unit
                }
            },
            onFinished = {
                // Series: the next episode starts by itself, from the torrent or the online source.
                val next = current.torrent?.nextFileId
                val nextRef = current.nextRef
                when {
                    next != null -> scope.launch { torrentVm.prepare(next)?.let(vm::playDirect) ?: vm.closePlayer() }
                    nextRef != null -> searchVm.playRef(nextRef)
                    else -> vm.closePlayer()
                }
            },
        )
        return
    }

    val openTorrent = torrent
    if (openTorrent != null) {
        BackHandler { torrentVm.close() }
        TorrentContent(
            openTorrent,
            TorrentActions(
                onBack = torrentVm::close,
                onPlay = { f -> scope.launch { torrentVm.prepare(f.file)?.let(vm::playDirect) } },
                onPlayFromStart = { f -> scope.launch { torrentVm.prepare(f.file, fromStart = true)?.let(vm::playDirect) } },
                onRetry = torrentVm::retry,
            ),
        )
        notice?.let { n -> NoticeDialog(n.text, vm::dismissNotice) }
        return
    }

    val openListing = listing
    if (route is app.couchy.sources.Route.Listing && openListing != null) {
        BackHandler { searchVm.back() }
        ListingContent(
            openListing,
            ListingActions(
                onBack = searchVm::back,
                onTitle = searchVm::openTitle,
                onMore = searchVm::loadMore,
                onFilter = searchVm::refilter,
            ),
        )
        notice?.let { n -> NoticeDialog(n.text, vm::dismissNotice) }
        return
    }

    val page = titlePage
    // The film or episode waiting for its voice-over and quality: season, episode, from the start.
    var watchAsk by remember { mutableStateOf<Triple<Int, Int, Boolean>?>(null) }
    if (route is app.couchy.sources.Route.TitlePage && page != null) {
        BackHandler { searchVm.back() }
        Box(Modifier.fillMaxSize()) {
            watchAsk?.let { (s, e, fromStart) ->
                val r = page.rezka
                WatchDialog(
                    translators = r.page?.translators.orEmpty(),
                    current = r.translator,
                    onTranslator = { searchVm.selectTranslator(it) },
                    load = { searchVm.playRezka(s, e, fromStart) },
                    onPlay = { p -> watchAsk = null; vm.playDirect(p) },
                    onDismiss = { watchAsk = null },
                )
            }
            TitleContent(
                page.copy(
                    later = saved.later.any { it.key == page.title.key },
                    loved = saved.loved.any { it.key == page.title.key },
                    following = page.title.imdbId != null && page.title.imdbId in following,
                ),
                TitleActions(
                    onBack = searchVm::back,
                    onTranslator = searchVm::selectTranslator,
                    onSeason = { searchVm.selectSeason(it) },
                    // Voice-over and quality are asked for first, as in LazyMedia.
                    onPlay = { s, e, fromStart -> watchAsk = Triple(s, e, fromStart) },
                    onChallenge = searchVm::retryRezka,
                    onRetryRezka = searchVm::retryRezka,
                    onTorrent = { hit -> torrentVm.open(hit.magnet) },
                    onSite = searchVm::openSite,
                    onSearchSite = searchVm::searchOnSite,
                    onPerson = { p -> searchVm.openListing(ListQuery.Person(p.name, p.url)) },
                    onCatalog = { f -> searchVm.openListing(ListQuery.Catalog(f)) },
                    onTitle = searchVm::openTitle,
                    // Trailers play in Couchy too, without YouTube's ads.
                    onTrailer = { id -> ytVm.play(app.couchy.core.youtube.YouTubeLinks.watch(id)) },
                    onLater = { searchVm.toggleLater(page.title) },
                    onLoved = { searchVm.toggleLoved(page.title) },
                    onFollow = { searchVm.toggleFollow(page.title) },
                ),
            )
            if (sourceBusy || ytBusy) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        notice?.let { n -> NoticeDialog(n.text, vm::dismissNotice) }
        return
    }

    val tvActions = TvActions(
        onGroup = tvVm::selectGroup,
        onPlay = { item -> vm.playDirect(tvVm.live(item.channel, tv.channels.map { it.channel })) },
        onFocus = { tvVm.focus(it) },
        onStar = { tvVm.toggleFavorite(it.channel) },
        onMore = { item ->
            // On a TV the guide already sits next to the list, so the menu key just stars the channel.
            if (form.isTv) tvVm.toggleFavorite(item.channel) else { tvVm.focus(item.channel); channelFor = item }
        },
        onArchive = { channel, program ->
            val archived = tvVm.archive(channel, program)
            if (archived != null) { channelFor = null; vm.playDirect(archived) }
            else vm.showNotice("Архив этого канала не доходит до этой передачи.")
        },
        onAdd = { addPlaylist = "" },
        onRefresh = tvVm::refreshAll,
        onPlaylists = { showPlaylists = true },
    )

    Shell(
        destination,
        onNavigate = { if (kids && it == Destination.SETTINGS) askPin = true else destination = it },
        onOpen = { showOpen = true },
    ) {
        when (destination) {
            Destination.HOME -> HomeContent(
                home,
                HomeActions(
                    onPlay = { vm.play(it) },
                    onMore = { if (!kids) actionsFor = it },
                    onFavorite = vm::toggleFavorite,
                    onOpen = { showOpen = true },
                    onSearch = { destination = Destination.SEARCH; searchFocus += 1 },
                    onVoice = if (hasVoice) ({ runCatching { voice.launch(voiceIntent) }; Unit }) else null,
                    onTitle = searchVm::openTitle,
                    onCatalog = { f -> searchVm.openListing(ListQuery.Catalog(f)) },
                ),
                feed = feed,
                saved = saved,
                update = UpdateCenter.available?.takeIf { update !is UpdateState.UpToDate && update !is UpdateState.Idle }?.let { u ->
                    { app.couchy.ui.settings.UpdateStrip(u.build, update, onUpdate = ::installUpdate) }
                },
            )
            Destination.TV -> TvContent(tv, focusedChannel, schedule, tvActions)
            Destination.YOUTUBE -> Box(Modifier.fillMaxSize()) {
                app.couchy.ui.youtube.YouTubeContent(
                    youtube,
                    app.couchy.ui.youtube.YouTubeActions(
                        onQuery = ytVm::setQuery,
                        onSearch = { ytVm.search(it) },
                        onShelf = ytVm::show,
                        onVideo = { ytVm.play(it.url) },
                        onChannel = { url, name -> ytVm.openChannel(url, name) },
                        onCloseChannel = ytVm::closeChannel,
                        onSubscribe = ytVm::toggleSubscription,
                        onMore = ytVm::loadMore,
                        onRetry = ytVm::retry,
                        onClearHistory = ytVm::clearHistory,
                        onLater = ytVm::toggleLater,
                        onLoved = ytVm::toggleLoved,
                    ),
                )
                if (ytBusy) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Destination.SEARCH -> SearchContent(
                focusKey = searchFocus,
                state = search,
                actions = SearchActions(
                    onQuery = searchVm::setQuery,
                    onSubmit = { searchVm.submit(it) },
                    onMode = searchVm::setMode,
                    onTitle = searchVm::openTitle,
                    onTorrent = { hit -> torrentVm.open(hit.magnet) },
                    onVoice = if (hasVoice) ({ runCatching { voice.launch(voiceIntent) }; Unit }) else null,
                    onOpenLink = { showOpen = true },
                    onClearHistory = { searchVm.clearHistory() },
                    onCatalog = { f -> searchVm.openListing(ListQuery.Catalog(f)) },
                ),
            )
            Destination.LIBRARY -> LibraryContent(
                library.copy(saved = saved),
                downloads = offline,
                onDownloads = { downloads.refresh() },
                actions = LibraryActions(
                    onPlay = { vm.play(it) },
                    onMore = { if (!kids) actionsFor = it },
                    onFilter = vm::setFilter,
                    onSort = { vm.setSort(it) },
                    onSettings = { if (kids) askPin = true else destination = Destination.SETTINGS },
                    onTitle = searchVm::openTitle,
                    onSaved = { s -> if (s.done) vm.open(s.playback.key, s.playback.title) },
                    onRemoveSaved = { s -> downloads.remove(s.playback.key) },
                ),
            )
            Destination.SETTINGS -> prefs?.let { p ->
                SettingsContent(
                    p,
                    BuildConfig.VERSION_NAME,
                    update,
                    SettingsActions(
                        onTheme = { vm.setTheme(it) },
                        onUiMode = { vm.setUiMode(it) },
                        onCheckUpdate = { scope.launch { UpdateCenter.check() } },
                        onTorrEngine = { mode, address -> vm.setTorrEngine(mode, address) },
                        onRezkaHost = { vm.setRezkaHost(it) },
                        onTmdbKey = { vm.setTmdbKey(it) },
                        onBypass = { mode, kind -> vm.setBypass(mode, kind) },
                        onBypassConfig = { vm.setBypassConfig(it) },
                        onForgetBypassHosts = { vm.forgetBypassHosts() },
                        onSyncConnect = { url, login, password, report ->
                            scope.launch {
                                report(
                                    try {
                                        sync.connect(url, login, password)
                                        null
                                    } catch (e: Exception) {
                                        e.message ?: "Не удалось подключиться."
                                    },
                                )
                            }
                        },
                        onSyncNow = { sync.syncSoon() },
                        onGoogleSync = { report ->
                            scope.launch {
                                val activity = generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }
                                    .filterIsInstance<android.app.Activity>().first()
                                val problem = app.couchy.sync.GoogleAuth.signIn(activity) { request ->
                                    val answer = kotlinx.coroutines.CompletableDeferred<androidx.activity.result.ActivityResult>()
                                    googleAnswer = answer
                                    googleScreen.launch(request)
                                    answer.await()
                                }
                                report(problem ?: runCatching { sync.connectGoogle(); null }.getOrElse { it.message ?: "Не удалось подключиться." })
                            }
                        },
                        onYandexSync = { report ->
                            if (!app.couchy.sync.YandexAuth.configured) {
                                report("Вход через Яндекс заработает после регистрации Couchy на oauth.yandex.ru. Пока можно по паролю приложения.")
                            } else {
                                yandexReport = report
                                runCatching { yandexLogin?.launch(com.yandex.authsdk.YandexAuthLoginOptions()) }
                                    .onFailure { report("Яндекс не открылся: ${it.message}") }
                            }
                        },
                        onKidsPin = { vm.setKidsPin(it) },
                        onEnterKids = { vm.enterKids() },
                        onSyncDisconnect = { scope.launch { sync.disconnect() } },
                        onCheckBypass = { report ->
                            scope.launch {
                                report(
                                    try {
                                        bypassEngine.check()
                                    } catch (e: Exception) {
                                        e.message ?: "Не подключился."
                                    },
                                )
                            }
                        },
                        onCheckTorr = { address, report ->
                            scope.launch {
                                report(
                                    try {
                                        "Работает · " + torrentVm.engine.check(address)
                                    } catch (e: Exception) {
                                        "Не отвечает. Проверьте адрес и что TorrServe запущен."
                                    },
                                )
                            }
                        },
                        onInstallUpdate = ::installUpdate,
                    ),
                    bypass = bypassUi,
                    sync = SyncUi(
                        connected = p.syncUrl.isNotBlank(),
                        url = p.syncUrl,
                        login = p.syncLogin,
                        status = when (val st = syncStatus) {
                            SyncStatus.Off -> if (p.syncUrl.isBlank()) "Не подключено" else "Подключено"
                            SyncStatus.Running -> "Синхронизирую…"
                            is SyncStatus.Done -> "Синхронизировано в " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(st.at))
                            is SyncStatus.Failed -> st.message
                        },
                    ),
                    castInfo = "Это устройство: ${cast.deviceName}" + (cast.address()?.let { ", адрес $it" } ?: ""),
                )
            }
        }
    }

    if (showOpen) {
        OpenDialog(
            onOpen = { showOpen = false; vm.open(it) },
            onPickFile = {
                try {
                    pickFile.launch(
                        arrayOf(
                            "video/*", "application/x-bittorrent", "application/octet-stream",
                            "audio/x-mpegurl", "audio/mpegurl", "application/x-mpegurl", "application/vnd.apple.mpegurl", "text/plain",
                        ),
                    )
                } catch (e: ActivityNotFoundException) {
                    showOpen = false
                    vm.showNotice("На этом устройстве нет выбора файлов. Откройте видео по ссылке.")
                }
            },
            onDismiss = { showOpen = false },
        )
    }

    actionsFor?.let { item ->
        ItemActionsDialog(
            item,
            onPlay = { vm.play(item) },
            onFromStart = { vm.play(item, fromStart = true) },
            onFavorite = { vm.toggleFavorite(item) },
            onUnwatched = { vm.markUnwatched(item) },
            onRemove = { vm.remove(item) },
            onDismiss = { actionsFor = null },
            onKids = { vm.toggleKids(item) },
            onCast = if (CastRequest.isLocalOnly(item.uri)) null else ({
                castRequest = CastRequest(item.uri, item.title, item.positionMs, item.durationMs)
            }),
        )
    }



    if (showPlaylists) {
        PlaylistsDialog(
            tv.playlists,
            onDelete = tvVm::delete,
            onAdd = { showPlaylists = false; addPlaylist = "" },
            onDismiss = { showPlaylists = false },
        )
    }

    channelFor?.let { item ->
        val fresh = tv.channels.firstOrNull { it.channel.id == item.channel.id } ?: item
        ChannelDialog(
            fresh,
            schedule,
            tv.nowMs,
            tvActions,
            onFavorite = { tvVm.toggleFavorite(fresh.channel) },
            onDismiss = { channelFor = null },
        )
    }

    notice?.let { n -> NoticeDialog(n.text, vm::dismissNotice) }
}

@Composable
private fun NoticeDialog(text: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Понятно") } },
        text = { Text(text, style = MaterialTheme.typography.bodyLarge) },
    )
}

/** Name, number and programme of the channel on air, from the guide already loaded for the TV screen. */
private fun liveUi(tv: TvState, channelId: Long, title: String): LiveUi {
    val item = tv.channels.firstOrNull { it.channel.id == channelId } ?: tv.favorites.firstOrNull { it.channel.id == channelId }
    return LiveUi(
        channel = item?.channel?.name ?: title,
        number = item?.number,
        nowTitle = item?.now?.title,
        nowStart = item?.now?.start ?: 0,
        nowStop = item?.now?.stop ?: 0,
        nextTitle = item?.next?.title,
        nextStart = item?.next?.start ?: 0,
        nowMs = tv.nowMs,
        favorite = item?.channel?.favorite == true,
    )
}

/** Netflix-style profile choice on launch. */
@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
private fun WhoIsWatching(onPick: (app.couchy.data.Profile) -> Unit) {
    val context = LocalContext.current
    val profiles = remember { app.couchy.data.Profiles.list(context) }
    val first = remember { androidx.compose.ui.focus.FocusRequester() }
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterVertically),
    ) {
        Text("Кто смотрит?", style = MaterialTheme.typography.headlineMedium)
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            profiles.forEachIndexed { i, p ->
                Column(
                    Modifier
                        .then(if (i == 0) Modifier.focusRequester(first) else Modifier)
                        .couchyClickable(RoundedCornerShape(18.dp)) { onPick(p) }
                        .padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    app.couchy.ui.settings.ProfileAvatar(p, 88.dp)
                    Text(p.name, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}
