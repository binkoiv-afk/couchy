package app.couchy.update

import android.content.Context
import app.couchy.ui.settings.UpdateState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * The app's update, independent of screens: the download keeps going when the dialog is closed
 * or Back is pressed, continues from where it stopped after a break, and installs once done.
 */
object UpdateCenter {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** The newer build, once found. */
    @Volatile var available: AppUpdate? = null
        private set

    /** A finished download waiting for the installer; the screen hands it over when it can. */
    private val _ready = MutableStateFlow<File?>(null)
    val ready: StateFlow<File?> = _ready.asStateFlow()

    val downloading: Boolean get() = job?.isActive == true

    /** Looks for a newer build. [quiet] keeps the "checking" state off the screen. */
    suspend fun check(quiet: Boolean = false): AppUpdate? {
        if (downloading) return available
        if (!quiet) _state.value = UpdateState.Checking
        val found = runCatching { Updates.check() }
        found.exceptionOrNull()?.let {
            if (!quiet) _state.value = UpdateState.Failed("Не удалось проверить обновления. Проверьте интернет.")
            return null
        }
        val update = found.getOrNull()
        available = update
        _state.value = if (update != null) UpdateState.Available(update.build) else if (quiet) UpdateState.Idle else UpdateState.UpToDate
        return update
    }

    /** Starts, or keeps going with, the download; the installer opens when it is done. */
    fun download(context: Context) {
        val update = available ?: return
        if (downloading) return
        val app = context.applicationContext
        job = scope.launch {
            try {
                _state.value = UpdateState.Downloading(0f)
                val apk = Updates.download(app, update) { _state.value = UpdateState.Downloading(it) }
                _state.value = UpdateState.Available(update.build)
                _ready.value = apk
            } catch (e: Exception) {
                _state.value = UpdateState.Failed("Загрузка прервалась. Нажмите ещё раз: она продолжится с того же места.")
            }
        }
    }

    /** The screen took the file to the installer. */
    fun handed() { _ready.value = null }
}
