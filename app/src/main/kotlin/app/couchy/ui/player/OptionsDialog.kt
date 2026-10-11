package app.couchy.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import app.couchy.ui.components.couchyClickable

data class Option(val label: String, val selected: Boolean, val onPick: () -> Unit)

data class OptionSection(val title: String?, val options: List<Option>)

/** A list of choices in sections: audio and subtitles, quality, speed. The current one is ticked. */
@Composable
fun OptionsDialog(title: String, sections: List<OptionSection>, onDismiss: () -> Unit) {
    val selected = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                var focusGiven = false
                sections.forEach { section ->
                    if (section.title != null) {
                        Text(
                            section.title,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 10.dp, bottom = 4.dp, start = 12.dp),
                        )
                    }
                    if (section.options.isEmpty()) {
                        Text(
                            "Нет вариантов",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                    section.options.forEach { option ->
                        val takeFocus = option.selected && !focusGiven
                        if (takeFocus) focusGiven = true
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .then(if (takeFocus) Modifier.focusRequester(selected) else Modifier)
                                .couchyClickable(RoundedCornerShape(10.dp), focusScale = 1.02f) { option.onPick(); onDismiss() }
                                .padding(horizontal = 12.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                Icons.Rounded.Check, null,
                                tint = if (option.selected) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(option.label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        },
    )
    LaunchedEffect(Unit) { runCatching { selected.requestFocus() } }
}
