package app.couchy.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.couchy.core.Links
import app.couchy.tv.ChannelNow
import app.couchy.tv.PlaylistEntity
import app.couchy.tv.ProgramEntity
import app.couchy.ui.components.Chip
import app.couchy.ui.components.SecondaryButton

/** Adds a playlist: an M3U link (with an optional guide address) or an Xtream Codes login. */
@Composable
fun AddPlaylistDialog(
    initialUrl: String = "",
    onM3u: (name: String, url: String, epg: String?) -> Unit,
    onXtream: (name: String, server: String, user: String, password: String) -> Unit,
    onDismiss: () -> Unit,
    /** Picks an .m3u file on the device instead of typing a link. */
    onPickFile: (() -> Unit)? = null,
) {
    // An Xtream panel link fills the login form; it keeps working when the panel changes the list.
    val panel = remember(initialUrl) { Links.xtream(initialUrl) }
    var xtream by rememberSaveable { mutableStateOf(panel != null) }
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf(initialUrl) }
    var epg by rememberSaveable { mutableStateOf("") }
    var server by rememberSaveable { mutableStateOf(panel?.first.orEmpty()) }
    var user by rememberSaveable { mutableStateOf(panel?.second.orEmpty()) }
    var password by rememberSaveable { mutableStateOf(panel?.third.orEmpty()) }
    val valid = if (xtream) server.isNotBlank() && user.isNotBlank() && password.isNotBlank() else Links.looksLikeLink(url)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новый плейлист") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Ссылка M3U", !xtream, onClick = { xtream = false })
                    Chip("Xtream Codes", xtream, onClick = { xtream = true })
                }
                OutlinedTextField(name, { name = it }, label = { Text("Название (необязательно)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (xtream) {
                    OutlinedTextField(
                        server, { server = it }, label = { Text("Сервер") }, placeholder = { Text("http://host:8080") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(user, { user = it }, label = { Text("Логин") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        password, { password = it }, label = { Text("Пароль") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    OutlinedTextField(
                        url, { url = it }, label = { Text("Ссылка на плейлист .m3u") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
                    )
                    if (onPickFile != null && !url.startsWith("file:")) {
                        app.couchy.ui.components.SecondaryButton("Выбрать файл на устройстве", Icons.Rounded.FolderOpen, onClick = { onDismiss(); onPickFile() })
                    }
                    OutlinedTextField(
                        epg, { epg = it }, label = { Text("Телепрограмма XMLTV (необязательно)") }, singleLine = true,
                        supportingText = { Text("Если в плейлисте уже есть адрес программы, оставьте пустым") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
                    )
                }
                // Long addresses and passwords are a pain on a remote: type them on the phone.
                app.couchy.pair.PhoneInputButton(
                    "Плейлист",
                    if (xtream) listOf(
                        app.couchy.core.pair.PhoneForm.Field("Сервер", value = server),
                        app.couchy.core.pair.PhoneForm.Field("Логин", value = user),
                        app.couchy.core.pair.PhoneForm.Field("Пароль", secret = true),
                    ) else listOf(
                        app.couchy.core.pair.PhoneForm.Field("Ссылка на плейлист", value = url),
                        app.couchy.core.pair.PhoneForm.Field("Телепрограмма XMLTV (необязательно)", value = epg),
                    ),
                ) { v ->
                    if (xtream) {
                        server = v.getOrNull(0)?.trim() ?: server
                        user = v.getOrNull(1)?.trim() ?: user
                        v.getOrNull(2)?.takeIf { it.isNotEmpty() }?.let { password = it }
                    } else {
                        url = v.getOrNull(0)?.trim() ?: url
                        epg = v.getOrNull(1)?.trim() ?: epg
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                if (xtream) onXtream(name.ifBlank { "Xtream" }, server, user, password)
                else onM3u(name.ifBlank { Links.titleFor(url).ifBlank { "Плейлист" } }, url, epg.ifBlank { null })
                onDismiss()
            }) { Text("Добавить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
fun PlaylistsDialog(playlists: List<PlaylistEntity>, onDelete: (PlaylistEntity) -> Unit, onAdd: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Плейлисты") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                playlists.forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                p.lastError?.let { "Ошибка: $it" } ?: "${p.channelCount} каналов" + if (p.epgUpdatedAt > 0) " · программа обновлена в ${clockOf(p.epgUpdatedAt)}" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (p.lastError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { onDelete(p) }) { Icon(Icons.Rounded.Delete, "Удалить") }
                    }
                }
                if (playlists.isEmpty()) Text("Плейлистов пока нет.")
            }
        },
        confirmButton = { TextButton(onClick = { onDismiss(); onAdd() }) { Text("Добавить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}

/** On a phone a long press opens the channel: favorite and its guide with the archive. */
@Composable
fun ChannelDialog(
    item: ChannelNow,
    schedule: List<ProgramEntity>,
    nowMs: Long,
    actions: TvActions,
    onFavorite: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onDismiss(); actions.onPlay(item) }) { Text("Смотреть") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ChannelLogo(item.channel.name, item.channel.logo, 40.dp)
                Text(item.channel.name, maxLines = 1)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton(
                    if (item.channel.favorite) "В избранном" else "В избранное",
                    if (item.channel.favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                    onClick = onFavorite,
                )
                ScheduleList(item.channel, schedule, nowMs, actions, Modifier.height(360.dp).padding(top = 4.dp))
            }
        },
    )
}
