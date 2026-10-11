package app.couchy.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.couchy.cast.Cast
import app.couchy.cast.CastDevice
import app.couchy.core.cast.CastRequest
import kotlinx.coroutines.launch

/**
 * Picks a TV box (or any other Couchy) in the home network and sends it [request].
 * Devices announce themselves; an address can be typed where the network hides them.
 */
@Composable
fun CastDialog(cast: Cast, request: CastRequest, onSent: (CastDevice) -> Unit, onDismiss: () -> Unit) {
    val devices by remember { cast.discover() }.collectAsState(initial = emptyList())
    var sending by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var address by rememberSaveable { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    fun send(device: CastDevice) {
        sending = device.name
        error = null
        scope.launch {
            try {
                cast.send(device, request)
                onSent(device)
            } catch (e: Exception) {
                error = e.message
            } finally {
                sending = null
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { SecondaryButton("Отмена", null, onClick = onDismiss) },
        title = { Text("Смотреть на ТВ") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(request.title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                if (devices.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                        Text(
                            "Ищу Couchy в домашней сети. На приставке он должен быть открыт.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                devices.forEach { d ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .couchyClickable(RoundedCornerShape(10.dp), focusScale = 1.02f) { if (sending == null) send(d) }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Icon(Icons.Rounded.Tv, null, tint = MaterialTheme.colorScheme.primary)
                        Text(d.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        if (sending == d.name) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                }
                Text(
                    "Не видно приставку? Введите её адрес из настроек Couchy на ней.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        address,
                        { address = it; error = null },
                        placeholder = { Text("192.168.1.20") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.weight(1f),
                    )
                    SecondaryButton("Отправить", null, onClick = { cast.manual(address)?.let(::send) })
                }
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        },
    )
}
