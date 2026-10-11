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
import app.couchy.ui.player.PlayerScreen
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
                    MainScreen(vm, fresh)
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
private fun MainScreen(vm: AppViewModel, freshStart: Int) {
    val form = LocalFormFactor.current
    val playback by vm.playback.collectAsStateWithLifecycle()
    val home by vm.home.collectAsStateWithLifecycle()
    val library by vm.libraryState.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    var destination by rememberSaveable { mutableStateOf(Destination.HOME) }
    var showOpen by rememberSaveable { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<MediaEntity?>(null) }
    LaunchedEffect(freshStart) { if (freshStart > 0) { destination = Destination.HOME; showOpen = false; actionsFor = null } }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var update by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }
    var available by remember { mutableStateOf<AppUpdate?>(null) }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { showOpen = false; vm.open(uri.toString()) }
    }

    val current = playback
    if (current != null) {
        PlayerScreen(
            playback = current,
            touch = !form.isTv,
            onProgress = { pos, dur -> vm.saveProgress(current.uri, pos, dur) },
            onTracksChosen = { audio, subs -> vm.saveTracks(current.uri, audio, subs) },
            onExit = vm::closePlayer,
        )
        return
    }

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

    notice?.let { n ->
        AlertDialog(
            onDismissRequest = vm::dismissNotice,
            confirmButton = { TextButton(onClick = vm::dismissNotice) { Text("Понятно") } },
            text = { Text(n.text, style = MaterialTheme.typography.bodyLarge) },
        )
    }
}
