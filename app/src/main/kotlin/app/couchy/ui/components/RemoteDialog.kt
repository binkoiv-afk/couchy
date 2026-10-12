package app.couchy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Forward30
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Replay30
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.couchy.cast.Cast
import app.couchy.cast.CastDevice
import app.couchy.core.cast.RemoteAction
import app.couchy.core.cast.RemoteState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The phone as the TV's remote: what plays there, play and pause, jumps, the next episode and
 * the volume. Without a [device] it first asks which Couchy in the home network to control.
 */
@Composable
fun RemoteDialog(cast: Cast, device: CastDevice?, onDismiss: () -> Unit) {
    var target by remember { mutableStateOf(device) }
    val t = target
    if (t == null) {
        RemotePicker(cast, onPick = { target = it }, onDismiss = onDismiss)
        return
    }
    var state by remember { mutableStateOf<RemoteState?>(null) }
    var answered by remember { mutableStateOf(false) }
    // Where the finger holds the slider, before it is let go.
    var dragging by remember { mutableStateOf<Float?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(t) {
        while (true) {
            state = cast.state(t)
            answered = true
            delay(1_000)
        }
    }
    fun press(action: String, value: Long = 0) {
        scope.launch {
            cast.control(t, action, value)
            state = cast.state(t)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { SecondaryButton("Закрыть", null, onClick = onDismiss) },
        title = { Text("Пульт · ${t.name}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val s = state
                when {
                    !answered -> CircularProgressIndicator(Modifier.size(24.dp), color = MaterialTheme.colorScheme.primary)
                    s == null -> Text(
                        "На ${t.name} сейчас ничего не играет. Отправьте видео кнопкой «На ТВ» и управляйте отсюда.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> {
                        Text(s.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
                        if (s.durationMs > 0) {
                            val fraction = dragging ?: (s.positionMs.toFloat() / s.durationMs).coerceIn(0f, 1f)
                            Slider(
                                fraction,
                                onValueChange = { dragging = it },
                                onValueChangeFinished = {
                                    dragging?.let { press(RemoteAction.SEEK_TO, (it * s.durationMs).toLong()) }
                                    dragging = null
                                },
                                colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary),
                            )
                            Row(Modifier.fillMaxWidth()) {
                                Text(clock((fraction * s.durationMs).toLong()), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                                Text(clock(s.durationMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                            RemoteKey(Icons.Rounded.Replay30, "Назад 30 секунд") { press(RemoteAction.SEEK_BY, -30_000) }
                            RemoteKey(Icons.Rounded.Replay10, "Назад 10 секунд") { press(RemoteAction.SEEK_BY, -10_000) }
                            RemoteKey(if (s.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Пауза", big = true) { press(RemoteAction.TOGGLE) }
                            RemoteKey(Icons.Rounded.Forward10, "Вперёд 10 секунд") { press(RemoteAction.SEEK_BY, 10_000) }
                            RemoteKey(Icons.Rounded.Forward30, "Вперёд 30 секунд") { press(RemoteAction.SEEK_BY, 30_000) }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                            RemoteKey(Icons.AutoMirrored.Rounded.VolumeDown, "Тише") { press(RemoteAction.VOLUME, -1) }
                            RemoteKey(Icons.AutoMirrored.Rounded.VolumeUp, "Громче") { press(RemoteAction.VOLUME, 1) }
                            if (s.hasNext) RemoteKey(Icons.Rounded.SkipNext, "Следующая серия") { press(RemoteAction.NEXT) }
                            RemoteKey(Icons.Rounded.Stop, "Остановить") { press(RemoteAction.STOP) }
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun RemoteKey(icon: ImageVector, label: String, big: Boolean = false, onClick: () -> Unit) {
    val size = if (big) 64.dp else 48.dp
    Box(
        Modifier
            .size(size)
            .couchyClickable(CircleShape, onClick = onClick)
            .background(if (big) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = if (big) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(if (big) 32.dp else 24.dp))
    }
}

/** Which Couchy to control: those announcing themselves, or one typed by address. */
@Composable
private fun RemotePicker(cast: Cast, onPick: (CastDevice) -> Unit, onDismiss: () -> Unit) {
    val devices by remember { cast.discover() }.collectAsState(initial = emptyList())
    var address by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { SecondaryButton("Отмена", null, onClick = onDismiss) },
        title = { Text("Пульт для ТВ") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (devices.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                        Text("Ищу Couchy в домашней сети. На приставке он должен быть открыт.", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                devices.forEach { d ->
                    Row(
                        Modifier.fillMaxWidth().couchyClickable(RoundedCornerShape(10.dp), focusScale = 1.02f) { onPick(d) }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Icon(Icons.Rounded.Tv, null, tint = MaterialTheme.colorScheme.primary)
                        Text(d.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        address, { address = it },
                        placeholder = { Text("192.168.1.20") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.weight(1f),
                    )
                    SecondaryButton("Подключить", null, onClick = { cast.manual(address)?.let(onPick) })
                }
            }
        },
    )
}

private fun clock(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
