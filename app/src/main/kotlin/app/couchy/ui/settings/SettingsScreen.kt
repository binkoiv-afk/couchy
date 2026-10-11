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
import app.couchy.core.Kids
import app.couchy.core.bypass.BypassKind
import app.couchy.core.bypass.BypassMode
import app.couchy.core.bypass.TunnelConfig
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
    val onBypass: (BypassMode, BypassKind) -> Unit = { _, _ -> },
    val onBypassConfig: (String) -> Unit = {},
    /** Checks the bypass end to end and reports the answer as a line of text. */
    val onCheckBypass: ((String) -> Unit) -> Unit = {},
    val onForgetBypassHosts: () -> Unit = {},
    /** Connects a WebDAV account; the callback gets an error, or null when it worked. */
    val onSyncConnect: (url: String, login: String, password: String, (String?) -> Unit) -> Unit = { _, _, _, _ -> },
    val onSyncNow: () -> Unit = {},
    val onSyncDisconnect: () -> Unit = {},
    val onKidsPin: (String) -> Unit = {},
    val onEnterKids: () -> Unit = {},
)

/** Sync as the settings screen shows it. */
data class SyncUi(val connected: Boolean = false, val url: String = "", val login: String = "", val status: String = "Не подключено")

/** The bypass as the settings screen shows it. */
data class BypassUi(val status: String = "Не запущен", val available: Boolean = true)

@Composable
fun SettingsContent(
    prefs: Prefs,
    version: String,
    update: UpdateState,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
    bypass: BypassUi = BypassUi(),
    sync: SyncUi = SyncUi(),
    /** How this device is seen in the home network, for "watch on TV". */
    castInfo: String? = null,
) {
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
        Section(
            "Детский режим",
            "Ребёнок видит только то, что вы отметили в медиатеке (долгое нажатие → «Показывать детям»), и детские ТВ-каналы. Поиска и ссылок нет, выйти можно только по PIN.",
            row = false,
        ) {
            KidsSettings(prefs, actions)
        }
        Section(
            "Синхронизация",
            "История, места остановки, избранное, плейлисты ТВ и список заблокированных сайтов одинаковые на всех устройствах. Хранится в одном файле на вашем диске.",
            row = false,
        ) {
            SyncSettings(sync, actions)
        }
        Section(
            "Обход блокировок",
            "Авто: Couchy сам включает обход только для заблокированных сайтов и выключает, когда он не нужен. Всегда: весь интернет Couchy идёт через обход.",
            row = false,
        ) {
            BypassSettings(prefs, bypass, actions)
        }
        castInfo?.let { info ->
            Section(
                "Смотреть на ТВ",
                "На телефоне нажмите кнопку ТВ в плеере или «Смотреть на ТВ» в медиатеке, и видео продолжится здесь с того же места. Couchy на приставке должен быть открыт.",
            ) {
                Text(info, style = MaterialTheme.typography.bodyMedium)
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

@Composable
private fun KidsSettings(prefs: Prefs, actions: SettingsActions) {
    var changing by rememberSaveable { mutableStateOf(false) }
    var pin by rememberSaveable { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (prefs.kidsPin.isEmpty() || changing) {
            OutlinedTextField(
                pin,
                { pin = it.filter(Char::isDigit).take(4) },
                label = { Text("Придумайте PIN из 4 цифр") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth(),
            )
            if (Kids.validPin(pin)) SecondaryButton("Сохранить PIN", null, onClick = {
                actions.onKidsPin(pin)
                changing = false
                pin = ""
            })
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Включить детский режим", null, onClick = actions.onEnterKids)
                SecondaryButton("Сменить PIN", null, onClick = { changing = true })
            }
        }
    }
}

@Composable
private fun SyncSettings(sync: SyncUi, actions: SettingsActions) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (sync.connected) {
            Text(
                (if (sync.url.trimEnd('/') == YANDEX_DAV) "Яндекс Диск" else sync.url) + " · " + sync.login,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(sync.status, style = MaterialTheme.typography.bodySmall, color = muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Синхронизировать", null, onClick = actions.onSyncNow)
                SecondaryButton("Отключить", null, onClick = actions.onSyncDisconnect)
            }
            return@Column
        }
        var yandex by rememberSaveable { mutableStateOf(true) }
        var url by rememberSaveable { mutableStateOf("") }
        var login by rememberSaveable { mutableStateOf("") }
        var password by rememberSaveable { mutableStateOf("") }
        var result by remember { mutableStateOf<String?>(null) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Яндекс Диск", yandex, onClick = { yandex = true })
            Chip("Другой WebDAV", !yandex, onClick = { yandex = false })
        }
        if (yandex) {
            Text(
                "Нужен пароль приложения: id.yandex.ru → Безопасность → Пароли приложений → Файлы (WebDAV).",
                style = MaterialTheme.typography.bodySmall,
                color = muted,
            )
        } else {
            OutlinedTextField(
                url, { url = it; result = null },
                label = { Text("Адрес WebDAV") },
                placeholder = { Text("https://cloud.example.com/remote.php/dav/files/me") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        OutlinedTextField(
            login, { login = it; result = null },
            label = { Text(if (yandex) "Логин Яндекса" else "Логин") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            password, { password = it; result = null },
            label = { Text(if (yandex) "Пароль приложения" else "Пароль") },
            singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            SecondaryButton("Подключить", null, onClick = {
                val address = if (yandex) YANDEX_DAV else normalizeHost(url)
                if (login.isBlank() || password.isBlank() || address.isBlank()) {
                    result = "Заполните все поля."
                    return@SecondaryButton
                }
                result = "Подключаю…"
                actions.onSyncConnect(address, login, password) { error -> result = error }
            })
            result?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = muted, modifier = Modifier.weight(1f)) }
        }
    }
}

private const val YANDEX_DAV = "https://webdav.yandex.ru"

@Composable
private fun BypassSettings(prefs: Prefs, bypass: BypassUi, actions: SettingsActions) {
    var config by rememberSaveable(prefs.bypassConfig) { mutableStateOf(prefs.bypassConfig) }
    var result by remember { mutableStateOf<String?>(null) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!bypass.available) {
            Text("На этом процессоре обход не работает.", style = MaterialTheme.typography.bodyMedium, color = muted)
            return@Column
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BypassMode.entries.forEach { mode -> Chip(mode.label, mode == prefs.bypassMode, onClick = { actions.onBypass(mode, prefs.bypassKind) }) }
        }
        if (prefs.bypassMode == BypassMode.OFF) return@Column
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BypassKind.entries.forEach { kind -> Chip(kind.label, kind == prefs.bypassKind, onClick = { actions.onBypass(prefs.bypassMode, kind) }) }
        }
        if (prefs.bypassKind == BypassKind.WARP) {
            Text("Бесплатный WARP от Cloudflare, ключи Couchy получит сам. Пакеты маскируются, чтобы провайдер не узнал WireGuard.", style = MaterialTheme.typography.bodySmall, color = muted)
        } else {
            OutlinedTextField(
                config,
                { config = it; result = null },
                label = { Text("Конфиг WireGuard или AmneziaWG") },
                placeholder = { Text("[Interface]\nPrivateKey = …\n\n[Peer]\nEndpoint = …") },
                minLines = 4,
                maxLines = 10,
                modifier = Modifier.fillMaxWidth(),
            )
            if (config.trim() != prefs.bypassConfig) {
                val problem = TunnelConfig.problem(config)
                if (problem != null && config.isNotBlank()) Text(problem, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                else if (problem == null) SecondaryButton("Сохранить конфиг", null, onClick = { actions.onBypassConfig(config) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            SecondaryButton("Проверить", null, onClick = {
                result = "Подключаю…"
                actions.onCheckBypass { result = it }
            })
            Text(result ?: bypass.status, style = MaterialTheme.typography.bodyMedium, color = muted, modifier = Modifier.weight(1f))
        }
        if (prefs.bypassMode == BypassMode.AUTO && prefs.bypassHosts.isNotEmpty()) {
            Text(
                "Найдены заблокированными: " + prefs.bypassHosts.sorted().joinToString(", "),
                style = MaterialTheme.typography.bodySmall,
                color = muted,
            )
            SecondaryButton("Забыть этот список", null, onClick = actions.onForgetBypassHosts)
        }
    }
}

private fun normalizeHost(text: String): String {
    val t = text.trim().trimEnd('/')
    return if (t.isEmpty() || "://" in t) t else "https://$t"
}
