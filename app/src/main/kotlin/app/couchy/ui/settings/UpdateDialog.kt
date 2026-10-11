package app.couchy.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.rounded.SystemUpdate
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

/** The strip at the top of Home while a new version waits: one button, progress while it downloads. */
@Composable
fun UpdateStrip(build: Int, state: UpdateState, onUpdate: () -> Unit) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
    val downloading = state as? UpdateState.Downloading
    androidx.compose.foundation.layout.Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        androidx.compose.material3.Icon(
            androidx.compose.material.icons.Icons.Rounded.SystemUpdate, null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(2.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                when {
                    downloading != null -> "Скачиваю обновление… ${(downloading.progress * 100).toInt()}%"
                    state is UpdateState.Failed -> "Загрузка прервалась"
                    else -> "Вышла новая версия Couchy (сборка $build)"
                },
                style = MaterialTheme.typography.titleSmall,
            )
            if (downloading != null) {
                LinearProgressIndicator(progress = { downloading.progress }, modifier = Modifier.fillMaxWidth())
            } else {
                Text(
                    if (state is UpdateState.Failed) "Продолжу с того же места." else "Можно смотреть дальше: скачается в фоне.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (downloading == null) {
            app.couchy.ui.components.PrimaryButton(if (state is UpdateState.Failed) "Ещё раз" else "Обновить", null, onClick = onUpdate)
        }
    }
}
