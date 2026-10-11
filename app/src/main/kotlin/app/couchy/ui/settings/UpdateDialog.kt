package app.couchy.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.couchy.update.AppUpdate

/** "A new version is out": one button downloads it with progress and opens the installer. */
@Composable
fun UpdateDialog(target: AppUpdate, state: UpdateState, onUpdate: () -> Unit, onLater: () -> Unit) {
    val downloading = state as? UpdateState.Downloading
    AlertDialog(
        onDismissRequest = { if (downloading == null) onLater() },
        title = { Text("Вышла новая версия") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Сборка ${target.build}. Обновление займёт минуту, все данные останутся на месте.", style = MaterialTheme.typography.bodyMedium)
                target.notes.lines().map { it.trim() }.filter { it.isNotEmpty() }.take(4).joinToString("\n").ifEmpty { null }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 6, overflow = TextOverflow.Ellipsis)
                }
                if (downloading != null) {
                    LinearProgressIndicator(progress = { downloading.progress }, modifier = Modifier.fillMaxWidth())
                    Text("Скачиваю… ${(downloading.progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                }
                (state as? UpdateState.Failed)?.let { Text(it.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = onUpdate, enabled = downloading == null) { Text(if (state is UpdateState.Failed) "Ещё раз" else "Обновить") }
        },
        dismissButton = { if (downloading == null) TextButton(onClick = onLater) { Text("Позже") } },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    )
}
