package app.couchy.ui.player

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.couchy.ui.components.CouchySheet
import app.couchy.ui.components.SheetHeader
import app.couchy.ui.components.SheetRow

/** One choice; [tag] is a short note on the right, [icon] sits in a soft square on the left. */
data class Option(
    val label: String,
    val selected: Boolean,
    val icon: ImageVector? = null,
    val tag: String? = null,
    val onPick: () -> Unit,
)

data class OptionSection(val title: String?, val options: List<Option>)

/** "Name · short note": the note goes to the tag on the right when it is short enough to fit. */
private fun Option.parts(): Pair<String, String?> {
    if (tag != null) return label to tag
    val i = label.indexOf(" · ")
    if (i <= 0) return label to null
    val rest = label.substring(i + 3)
    return if (rest.length <= 22) label.substring(0, i) to rest else label to null
}

/** A list of choices in sections: audio and subtitles, quality, speed. The current one is lit. */
@Composable
fun OptionsDialog(title: String, sections: List<OptionSection>, onDismiss: () -> Unit, step: String? = null, onBack: (() -> Unit)? = null) {
    val selected = remember { FocusRequester() }
    CouchySheet(title, onDismiss, step = step, onBack = onBack) {
        var focusGiven = false
        sections.forEach { section ->
            section.title?.let { SheetHeader(it) }
            if (section.options.isEmpty()) {
                Text(
                    "Нет вариантов",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier,
                )
            }
            section.options.forEach { option ->
                val takeFocus = option.selected && !focusGiven
                if (takeFocus) focusGiven = true
                val (label, tag) = option.parts()
                SheetRow(
                    label,
                    onClick = { option.onPick(); onDismiss() },
                    modifier = if (takeFocus) Modifier.focusRequester(selected) else Modifier,
                    icon = option.icon,
                    tag = tag,
                    selected = option.selected,
                    trailing = if (option.selected) {
                        { Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp)) }
                    } else null,
                )
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { selected.requestFocus() } }
}
