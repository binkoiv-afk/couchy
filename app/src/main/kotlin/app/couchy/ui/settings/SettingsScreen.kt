package app.couchy.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.couchy.data.Prefs
import app.couchy.data.ThemeMode
import app.couchy.data.TorrEngineMode
import app.couchy.data.UiMode
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import app.couchy.ui.components.Chip
import app.couchy.ui.components.LocalFormFactor
import app.couchy.ui.components.SecondaryButton

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val build: Int) : UpdateState
    data class Downloading(val progress: Float) : UpdateState
    data class Failed(val message: String) : UpdateState
}

class SettingsActions(
    val onTheme: (ThemeMode) -> Unit,
    val onUiMode: (UiMode) -> Unit,
    val onCheckUpdate: () -> Unit,
    val onInstallUpdate: () -> Unit,
    val onTorrEngine: (TorrEngineMode, String) -> Unit = { _, _ -> },
    /** Checks a TorrServe address and reports the answer as a line of text. */
    val onCheckTorr: (String, (String) -> Unit) -> Unit = { _, _ -> },
    val onRezkaHost: (String) -> Unit = {},
)

@Composable
fun SettingsContent(prefs: Prefs, version: String, update: UpdateState, actions: SettingsActions, modifier: Modifier = Modifier) {
    val side = if (LocalFormFactor.current.wide) 32.dp else 18.dp
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = side, vertical = 20.dp)
            .widthIn(max = 720.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Настройки", style = MaterialTheme.typography.headlineMedium)
        Section("Тема", "Тёмная как в кинотеатре или светлая.") {
            ThemeMode.entries.forEach { Chip(it.label, it == prefs.theme, onClick = { actions.onTheme(it) }) }
        }
        Section("Интерфейс", "Для пульта: крупные карточки и рамка фокуса. Для сенсора: жесты и нижнее меню.") {
            UiMode.entries.forEach { Chip(it.label, it == prefs.uiMode, onClick = { actions.onUiMode(it) }) }
        }
        Section(
            "Торренты",
            "Встроенный движок работает прямо в Couchy. TorrServe в сети: если он уже стоит на приставке или компьютере, укажите его адрес.",
            row = false,
        ) {
            TorrSettings(prefs, actions)
        }
        Section("Онлайн-источники", "Если HDrezka не открывается, укажите рабочее зеркало, например https://hdrezka.me", row = false) {
            var host by rememberSaveable(prefs.rezkaHost) { mutableStateOf(prefs.rezkaHost) }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    host, { host = it },
                    label = { Text("Адрес HDrezka") },
                    placeholder = { Text("https://rezka.ag") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (host.trim() != prefs.rezkaHost) SecondaryButton("Сохранить", null, onClick = { actions.onRezkaHost(normalizeHost(host)) })
            }
        }
        Section("О приложении", "Couchy $version") {
            when (update) {
                UpdateState.Idle -> SecondaryButton("Проверить обновления", null, onClick = actions.onCheckUpdate)
                UpdateState.Checking -> Text("Проверяю…", style = MaterialTheme.typography.bodyMedium)
                UpdateState.UpToDate -> Text("Установлена последняя версия", style = MaterialTheme.typography.bodyMedium)
                is UpdateState.Available -> SecondaryButton("Установить сборку ${update.build}", null, onClick = actions.onInstallUpdate)
                is UpdateState.Downloading -> Text("Скачиваю… ${(update.progress * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
                is UpdateState.Failed -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(update.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SecondaryButton("Проверить ещё раз", null, onClick = actions.onCheckUpdate)
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, hint: String, row: Boolean = true, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(18.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (row) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() } else content()
    }
}

@Composable
private fun TorrSettings(prefs: Prefs, actions: SettingsActions) {
    var address by rememberSaveable(prefs.torrAddress) { mutableStateOf(prefs.torrAddress) }
    var result by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TorrEngineMode.entries.forEach { mode ->
                Chip(mode.label, mode == prefs.torrEngine, onClick = { actions.onTorrEngine(mode, address) })
            }
        }
        if (prefs.torrEngine == TorrEngineMode.REMOTE) {
            OutlinedTextField(
                address,
                { address = it; result = null },
                label = { Text("Адрес TorrServe") },
                placeholder = { Text("192.168.1.20:8090") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                SecondaryButton("Сохранить и проверить", null, onClick = {
                    actions.onTorrEngine(TorrEngineMode.REMOTE, address)
                    result = "Проверяю…"
                    actions.onCheckTorr(address) { result = it }
                })
                result?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

private fun normalizeHost(text: String): String {
    val t = text.trim().trimEnd('/')
    return if (t.isEmpty() || "://" in t) t else "https://$t"
}
