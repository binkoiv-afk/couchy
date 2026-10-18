package app.couchy.ui.components

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import app.couchy.data.UiMode

/** Which of the three layouts is shown. */
enum class FormFactor {
    /** Remote control: side rail, big cards, focus rings, no touch gestures. */
    TV,
    /** Touch on a wide screen: side rail, more columns. */
    TABLET,
    /** Touch on a phone: bottom navigation. */
    PHONE;

    val isTv get() = this == TV
    val wide get() = this != PHONE
}

val LocalFormFactor = staticCompositionLocalOf { FormFactor.PHONE }

fun isTelevision(context: Context): Boolean {
    val modeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    if (modeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true
    val pm = context.packageManager
    if (pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) || pm.hasSystemFeature("android.software.leanback_only")) return true
    if (context.resources.configuration.touchscreen == Configuration.TOUCHSCREEN_NOTOUCH) return true
    if (RemoteSeen.seen(context)) return true
    // Cheap boxes often report a "normal" UI mode but have no touchscreen at all.
    return !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
}

/**
 * Projectors and cheap boxes (Wanbo and the like) often say they have a touchscreen. The first
 * press of a remote's arrows on a screen with no finger on it yet tells the truth: from then on
 * the automatic layout is the remote one.
 */
object RemoteSeen {
    val flow = kotlinx.coroutines.flow.MutableStateFlow(false)
    private var touched = false

    fun seen(context: Context): Boolean =
        flow.value || context.getSharedPreferences("ui", Context.MODE_PRIVATE).getBoolean("remote_seen", false).also { if (it) flow.value = true }

    fun onTouch() { touched = true }

    /** An arrow or OK from a remote, before any touch: remember the device as a TV. */
    fun onRemoteKey(context: Context) {
        if (touched || flow.value) return
        flow.value = true
        context.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putBoolean("remote_seen", true).apply()
    }
}

@Composable
fun rememberFormFactor(mode: UiMode): FormFactor {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val remote by RemoteSeen.flow.collectAsState()
    val tv = remember(context, remote) { isTelevision(context) }
    val wide = configuration.screenWidthDp >= 600
    return when (mode) {
        UiMode.TV -> FormFactor.TV
        UiMode.TOUCH -> if (wide) FormFactor.TABLET else FormFactor.PHONE
        UiMode.AUTO -> when {
            tv -> FormFactor.TV
            wide -> FormFactor.TABLET
            else -> FormFactor.PHONE
        }
    }
}
