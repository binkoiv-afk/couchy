package app.couchy.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.couchy.CouchyApp
import app.couchy.cast.CastDevice
import app.couchy.sync.LanAsk
import app.couchy.sync.LanSync
import app.couchy.ui.components.SecondaryButton
import kotlinx.coroutines.launch

/**
 * Wi‑Fi sync: the devices linked with this one, and the others in the network to link. Shown
 * at the top of «Синхронизация»; it needs no account.
 */
@Composable
fun LanSyncSettings() {
    val app = LocalContext.current.applicationContext as CouchyApp
    val peers by LanSync.peers.collectAsState()
    val status by LanSync.status.collectAsState()
    val scope = rememberCoroutineScope()
    var looking by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("По Wi‑Fi", style = MaterialTheme.typography.titleMedium)
        Text(
            "Связанные устройства сами обмениваются историей, местами остановки, избранным, отметками и плейлистами ТВ, пока Couchy открыт на обоих. Аккаунт не нужен.",
            style = MaterialTheme.typography.bodySmall,
            color = muted,
        )
        peers.forEach { p ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Rounded.Devices, null, tint = muted)
                Column(Modifier.weight(1f)) {
                    Text(p.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                    Text(
                        if (p.lastSync > 0) "Последний раз: " + java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale("ru")).format(java.util.Date(p.lastSync)) else "Ещё не синхронизировались",
                        style = MaterialTheme.typography.bodySmall,
                        color = muted,
                    )
                }
                SecondaryButton(null, Icons.Rounded.Close, onClick = { LanSync.forget(p.id) })
            }
        }
        if (status.isNotBlank() && peers.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall, color = muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton("Связать устройство", null, onClick = { looking = !looking; note = null })
            if (peers.isNotEmpty()) SecondaryButton("Синхронизировать сейчас", null, onClick = { scope.launch { LanSync.syncAll() } })
        }
        if (looking) {
            val found by remember { app.cast.discover() }.collectAsState(initial = emptyList())
            val fresh = found.filter { d -> peers.none { it.host == d.host } }
            Text(
                if (fresh.isEmpty()) "Ищу Couchy в этой сети Wi‑Fi… Откройте Couchy на другом устройстве." else "Выберите устройство, на нём появится вопрос:",
                style = MaterialTheme.typography.bodySmall,
                color = muted,
            )
            fresh.forEach { d: CastDevice ->
                SecondaryButton(d.name, Icons.Rounded.Devices, onClick = {
                    note = "Подтвердите на «${d.name}»…"
                    scope.launch {
                        val ok = LanSync.link(d)
                        note = if (ok) "Связано с «${d.name}»" else "«${d.name}» не подтвердил"
                        if (ok) looking = false
                    }
                })
            }
        }
        note?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}

/** The question on the device another one asks to link with. */
@Composable
fun LanLinkQuestion(ask: LanAsk) {
    AlertDialog(
        onDismissRequest = { LanSync.reply(ask, false) },
        title = { Text("Синхронизация по Wi‑Fi") },
        text = { Text("«${ask.name}» хочет синхронизироваться с этим устройством: история, места остановки, избранное и плейлисты ТВ станут общими.") },
        confirmButton = { TextButton(onClick = { LanSync.reply(ask, true) }) { Text("Разрешить") } },
        dismissButton = { TextButton(onClick = { LanSync.reply(ask, false) }) { Text("Нет") } },
    )
}
