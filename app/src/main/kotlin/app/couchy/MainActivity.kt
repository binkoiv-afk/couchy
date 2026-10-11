package app.couchy

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

    /** Bumped when the app comes back after a long time away, to start fresh on Home. */
    private val freshStart = MutableStateFlow(0)
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
                    MainScreen(vm, tvVm, torrentVm, searchVm, fresh)
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
private fun MainScreen(vm: AppViewModel, tvVm: TvViewModel, torrentVm: TorrentViewModel, searchVm: SearchViewModel, freshStart: Int) {
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
    val challenge by searchVm.challenge.collectAsStateWithLifecycle()
    val sourceAutoPlay by searchVm.autoPlay.collectAsStateWithLifecycle()
    val sourceBusy by searchVm.busy.collectAsStateWithLifecycle()
    val sourceError by searchVm.error.collectAsStateWithLifecycle()
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
            if (SiteRef.isRef(ref)) searchVm.openSiteRef(ref) else searchVm.playRef(ref)
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
    var update by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }
    var available by remember { mutableStateOf<AppUpdate?>(null) }

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

    val current = playback
    if (current != null) {
        val live = current.live
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

    val page = titlePage
    if (page != null) {
        BackHandler { searchVm.closeTitle() }
        Box(Modifier.fillMaxSize()) {
            TitleContent(
                page,
                TitleActions(
                    onBack = searchVm::closeTitle,
                    onTranslator = searchVm::selectTranslator,
                    onSeason = { searchVm.selectSeason(it) },
                    onPlay = { s, e, fromStart -> scope.launch { searchVm.playRezka(s, e, fromStart)?.let(vm::playDirect) } },
                    onChallenge = searchVm::retryRezka,
                    onRetryRezka = searchVm::retryRezka,
                    onTorrent = { hit -> torrentVm.open(hit.magnet) },
                    onSite = searchVm::openSite,
                    onSearchSite = searchVm::searchOnSite,
                ),
            )
            if (sourceBusy) {
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
                ),
                feed = feed,
            )
            Destination.TV -> TvContent(tv, focusedChannel, schedule, tvActions)
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
                ),
            )
            Destination.LIBRARY -> LibraryContent(
                library,
                LibraryActions(
                    onPlay = { vm.play(it) },
                    onMore = { if (!kids) actionsFor = it },
                    onFilter = vm::setFilter,
                    onSort = { vm.setSort(it) },
                    onSettings = { if (kids) askPin = true else destination = Destination.SETTINGS },
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
                        onCheckUpdate = {
                            update = UpdateState.Checking
                            scope.launch {
                                update = try {
                                    Updates.check()?.also { available = it }?.let { UpdateState.Available(it.build) } ?: UpdateState.UpToDate
                                } catch (e: Exception) {
                                    UpdateState.Failed("Не удалось проверить обновления. Проверьте интернет.")
                                }
                            }
                        },
                        onTorrEngine = { mode, address -> vm.setTorrEngine(mode, address) },
                        onRezkaHost = { vm.setRezkaHost(it) },
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
                        onInstallUpdate = {
                            val target = available
                            if (target != null) scope.launch {
                                update = try {
                                    val apk = Updates.download(context, target) { update = UpdateState.Downloading(it) }
                                    Updates.install(context, apk)
                                    UpdateState.Available(target.build)
                                } catch (e: Exception) {
                                    UpdateState.Failed("Не удалось скачать обновление.")
                                }
                            }
                        },
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
    )
}
