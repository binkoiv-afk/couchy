package app.couchy.ui.components

import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/** Speech recognition on the device: a recogniser to ask, or none. */
object Voice {
    fun intent(prompt: String) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        .putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)

    fun available(context: Context) = intent("").resolveActivity(context.packageManager) != null
}

/**
 * Listens once as soon as it is shown and hands over what was heard, best guess first. Shown only
 * while listening, so screens without it keep no activity launcher.
 */
@Composable
fun VoiceCapture(prompt: String, onResult: (List<String>) -> Unit, onCancel: () -> Unit) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val heard = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS).orEmpty()
        if (heard.isEmpty()) onCancel() else onResult(heard)
    }
    LaunchedEffect(Unit) { runCatching { launcher.launch(Voice.intent(prompt)) }.onFailure { onCancel() } }
}
