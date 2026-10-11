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
import app.couchy.data.UiMode
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
private fun Section(title: String, hint: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(18.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}
