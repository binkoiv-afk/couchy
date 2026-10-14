package app.couchy.together

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.couchy.core.together.Together
import app.couchy.ui.components.CouchySheet
import app.couchy.ui.components.SecondaryButton
import app.couchy.ui.components.SheetHeader
import app.couchy.ui.components.SheetRow

/** «Смотреть вместе»: make a room or enter a friend's code, then who is in and the chat. */
@Composable
fun RoomSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val ui by Room.ui.collectAsState()
    DisposableEffect(Unit) {
        Room.sheetOpen = true
        onDispose { Room.sheetOpen = false }
    }
    CouchySheet("Смотреть вместе", onDismiss) {
        val code = ui.code
        if (code == null) {
            Text(
                "Вы и друзья в разных местах смотрите одно и то же: пауза, перемотка и выбор серии у всех сразу. " +
                    "У каждого должен быть Couchy и тот же источник. Сообщения идут через открытый сервер ntfy.sh, видно только название и время.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            SheetRow("Создать комнату", onClick = { Room.join(context, Together.newCode()) }, icon = Icons.Rounded.Add)
            SheetHeader("Есть код от друга")
            var typed by rememberSaveable { mutableStateOf("") }
            val valid = Together.normalize(typed)
            OutlinedTextField(
                typed, { typed = it.take(12) },
                label = { Text("Код комнаты") },
                placeholder = { Text("ABCD-EFGH") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { valid?.let { Room.join(context, it) } }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
            if (valid != null) SheetRow("Войти", onClick = { Room.join(context, valid) }, icon = Icons.AutoMirrored.Rounded.Send)
        } else {
            Column(Modifier.fillMaxWidth().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Код комнаты", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(Together.pretty(code), fontSize = 40.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 4.sp, color = MaterialTheme.colorScheme.primary)
                Text(
                    when {
                        !ui.connected -> "Подключаюсь…"
                        ui.people.isEmpty() -> "Ждём друзей. Отправьте им код."
                        else -> "В комнате: " + ui.people.values.joinToString(", ")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(horizontal = 8.dp)) {
                SecondaryButton("Поделиться", Icons.Rounded.Share, onClick = {
                    val text = "Смотрим вместе в Couchy! Код комнаты: ${Together.pretty(code)}\n${Together.link(code)}"
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                    runCatching { context.startActivity(Intent.createChooser(send, "Пригласить").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                })
                SecondaryButton("Выйти", Icons.AutoMirrored.Rounded.Logout, onClick = { Room.leave(context) })
            }
            SheetHeader("Чат")
            if (ui.chat.isEmpty()) {
                Text("Сообщений пока нет. Во время фильма они появляются внизу экрана.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
            }
            ui.chat.takeLast(12).forEach { line ->
                Text(
                    buildString { append(line.name); append(": "); append(line.text) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (line.mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            var message by rememberSaveable { mutableStateOf("") }
            OutlinedTextField(
                message, { message = it.take(500) },
                label = { Text("Сообщение") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { Room.say(context, message); message = "" }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
            if (message.isNotBlank()) SheetRow("Отправить", onClick = { Room.say(context, message); message = "" }, icon = Icons.AutoMirrored.Rounded.Send)
        }
    }
}
