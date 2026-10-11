package app.couchy.ui.open

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.couchy.core.Links
import app.couchy.ui.components.SecondaryButton

/** "Открыть видео": paste or type a link, or pick a file on the device. */
@Composable
fun OpenDialog(onOpen: (String) -> Unit, onPickFile: () -> Unit, onDismiss: () -> Unit) {
    var link by rememberSaveable { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    val valid = Links.looksLikeLink(link)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Открыть видео") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it },
                    label = { Text("Ссылка на видео или поток") },
                    placeholder = { Text("https://…/video.mp4 или .m3u8") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { if (valid) onOpen(link.trim()) }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton("Вставить", Icons.Rounded.ContentPaste, onClick = {
                        clipboard.getText()?.text?.trim()?.let { link = it }
                    })
                    SecondaryButton("Файл", Icons.Rounded.FolderOpen, onClick = onPickFile)
                }
                Text(
                    "Поддерживаются mp4, mkv, webm, потоки HLS (.m3u8) и DASH (.mpd), торренты и плейлисты IPTV (.m3u).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onOpen(link.trim()) }, enabled = valid) {
                androidx.compose.material3.Icon(Icons.Rounded.PlayArrow, null)
                Text("Смотреть")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
