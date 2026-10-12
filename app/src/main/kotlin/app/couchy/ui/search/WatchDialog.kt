package app.couchy.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.couchy.Playback
import app.couchy.core.sources.Translator
import app.couchy.ui.player.Option
import app.couchy.ui.player.OptionSection
import app.couchy.ui.player.OptionsDialog

/**
 * Like in LazyMedia: "Смотреть" asks for the voice-over when there is a choice, then for the
 * quality the source has, and starts the video.
 */
@Composable
fun WatchDialog(
    translators: List<Translator>,
    current: Int?,
    onTranslator: (Int) -> Unit,
    load: suspend () -> Playback?,
    onPlay: (Playback) -> Unit,
    onDismiss: () -> Unit,
    /** The voice-over was picked for this title before: start with the quality, offering a change. */
    voiceKnown: Boolean = false,
    /** The video could not be had from here; by default the dialog just closes. */
    onFailed: () -> Unit = onDismiss,
) {
    var voiceChosen by remember { mutableStateOf(translators.size <= 1 || voiceKnown) }
    // Set when an option moves on to the next step, so the dialog closing behind it is not a cancel.
    var moving by remember { mutableStateOf(false) }
    if (!voiceChosen) {
        OptionsDialog(
            "Озвучка",
            listOf(
                OptionSection(
                    null,
                    translators.map { t ->
                        val marks = listOfNotNull("режиссёрская".takeIf { t.director }, "экранка".takeIf { t.camrip }, "с рекламой".takeIf { t.ads })
                        Option(t.name + if (marks.isEmpty()) "" else " · " + marks.joinToString(", "), t.id == current) {
                            moving = true
                            if (t.id != current) onTranslator(t.id)
                            voiceChosen = true
                        }
                    },
                ),
            ),
            onDismiss = { if (moving) moving = false else onDismiss() },
        )
        return
    }
    var playback by remember { mutableStateOf<Playback?>(null) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val p = load()
        loaded = true
        when {
            p == null -> onFailed()
            p.variants.size <= 1 -> onPlay(p)
            else -> playback = p
        }
    }
    val p = playback
    if (p == null) {
        if (!loaded) {
            AlertDialog(
                onDismissRequest = onDismiss,
                confirmButton = {},
                title = { Text("Качество") },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        CircularProgressIndicator(Modifier.size(22.dp), color = MaterialTheme.colorScheme.primary)
                        Text("Смотрю, какое качество есть…", style = MaterialTheme.typography.bodyLarge)
                    }
                },
            )
        }
        return
    }
    val voice = translators.firstOrNull { it.id == current }?.takeIf { translators.size > 1 }
    OptionsDialog(
        "Качество",
        listOfNotNull(
            voice?.let { v ->
                OptionSection("ОЗВУЧКА", listOf(Option("${v.name} · сменить", false) { moving = true; voiceChosen = false }))
            },
            OptionSection(
                null,
                p.variants.map { (label, url) ->
                    Option(label, url == p.uri) {
                        moving = true
                        onPlay(p.copy(uri = url))
                    }
                },
            ),
        ),
        onDismiss = { if (moving) moving = false else onDismiss() },
    )
}
