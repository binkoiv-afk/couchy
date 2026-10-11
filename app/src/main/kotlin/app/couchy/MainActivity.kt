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
import app.couchy.ui.components.LocalFormFactor
import app.couchy.ui.components.rememberFormFactor
import app.couchy.ui.home.HomeActions
import app.couchy.ui.home.HomeContent
import app.couchy.ui.library.LibraryActions
import app.couchy.ui.library.LibraryContent
import app.couchy.ui.open.OpenDialog
import app.couchy.tv.ChannelNow
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

    /** Bumped when the app comes back after a long time away, to start fresh on Home. */
    private val freshStart = MutableStateFlow(0)
    private var stoppedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handle(intent)
        setContent {
            val prefs by vm.prefs.collectAsStateWithLifecycle()
            val loaded = prefs ?: return@setContent
            CouchyTheme(loaded.theme) {
                CompositionLocalProvider(LocalFormFactor provides rememberFormFactor(loaded.uiMode)) {
                    val fresh by freshStart.collectAsStateWithLifecycle()
                    MainScreen(vm, tvVm, fresh)
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

    override fun onStop() {
        super.onStop()
        stoppedAt = SystemClock.elapsedRealtime()
    }

    override fun onStart() {
        super.onStart()
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
private fun MainScreen(vm: AppViewModel, tvVm: TvViewModel, freshStart: Int) {
    val form = LocalFormFactor.current
    val playback by vm.playback.collectAsStateWithLifecycle()
    val home by vm.home.collectAsStateWithLifecycle()
    val library by vm.libraryState.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    var destination by rememberSaveable { mutableStateOf(Destination.HOME) }
    var showOpen by rememberSaveable { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<MediaEntity?>(null) }
    val tv by tvVm.state.collectAsStateWithLifecycle()
    val schedule by tvVm.schedule.collectAsStateWithLifecycle()
    val focusedChannel by tvVm.focusedChannel.collectAsStateWithLifecycle()
    val pendingPlaylist by vm.pendingPlaylist.collectAsStateWithLifecycle()
    var addPlaylist by rememberSaveable { mutableStateOf<String?>(null) }
    var showPlaylists by rememberSaveable { mutableStateOf(false) }
    var channelFor by remember { mutableStateOf<ChannelNow?>(null) }
    LaunchedEffect(freshStart) {
        if (freshStart > 0) { destination = Destination.HOME; showOpen = false; actionsFor = null; channelFor = null; showPlaylists = false }
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

    val current = playback
    if (current != null) {
        val live = current.live
        PlayerScreen(
            playback = current,
            touch = !form.isTv,
            onProgress = { pos, dur -> vm.saveProgress(current.uri, pos, dur) },
            onTracksChosen = { audio, subs -> vm.saveTracks(current.uri, audio, subs) },
            onExit = vm::closePlayer,
            live = live?.takeIf { it.archiveOf == null }?.let { l -> liveUi(tv, l.channelId, current.title) },
            onZap = live?.takeIf { it.archiveOf == null && it.zapOrder.size > 1 }?.let { l ->
                { delta: Int -> scope.launch { tvVm.zap(l, delta)?.let(vm::playDirect) }; Unit }
            },
        )
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

    Shell(destination, onNavigate = { destination = it }, onOpen = { showOpen = true }) {
        when (destination) {
            Destination.HOME -> HomeContent(
                home,
                HomeActions(
                    onPlay = { vm.play(it) },
                    onMore = { actionsFor = it },
                    onFavorite = vm::toggleFavorite,
                    onOpen = { showOpen = true },
                ),
            )
            Destination.TV -> TvContent(tv, focusedChannel, schedule, tvActions)
            Destination.LIBRARY -> LibraryContent(
                library,
                LibraryActions(
                    onPlay = { vm.play(it) },
                    onMore = { actionsFor = it },
                    onFilter = vm::setFilter,
                    onSort = { vm.setSort(it) },
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
                )
            }
        }
    }

    if (showOpen) {
        OpenDialog(
            onOpen = { showOpen = false; vm.open(it) },
            onPickFile = {
                try {
                    pickFile.launch(arrayOf("video/*"))
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
        )
    }

    addPlaylist?.let { initial ->
        AddPlaylistDialog(
            initialUrl = initial,
            onM3u = { name, url, epg -> tvVm.addM3u(name, url, epg) },
            onXtream = { name, server, user, password -> tvVm.addXtream(name, server, user, password) },
            onDismiss = { addPlaylist = null },
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

    notice?.let { n ->
        AlertDialog(
            onDismissRequest = vm::dismissNotice,
            confirmButton = { TextButton(onClick = vm::dismissNotice) { Text("Понятно") } },
            text = { Text(n.text, style = MaterialTheme.typography.bodyLarge) },
        )
    }
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
