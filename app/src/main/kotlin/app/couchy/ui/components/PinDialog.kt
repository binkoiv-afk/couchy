package app.couchy.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/** Asks for the four-digit PIN that leaves kids mode; [check] says whether it was right. */
@Composable
fun PinDialog(check: (String) -> Boolean, onDismiss: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    fun submit() {
        if (check(pin)) onDismiss() else {
            wrong = true
            pin = ""
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Выйти из детского режима") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    pin,
                    { text ->
                        pin = text.filter(Char::isDigit).take(4)
                        wrong = false
                        if (pin.length == 4) submit()
                    },
                    label = { Text("PIN") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.focusRequester(focus),
                )
                if (wrong) Text("Неверный PIN", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { SecondaryButton("Войти", null, onClick = ::submit) },
        dismissButton = { SecondaryButton("Отмена", null, onClick = onDismiss) },
    )
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}
