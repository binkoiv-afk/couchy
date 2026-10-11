package app.couchy.ui.components

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.runtime.Composable
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
    // Cheap boxes often report a "normal" UI mode but have no touchscreen at all.
    return !context.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
}

@Composable
fun rememberFormFactor(mode: UiMode): FormFactor {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val tv = remember(context) { isTelevision(context) }
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
