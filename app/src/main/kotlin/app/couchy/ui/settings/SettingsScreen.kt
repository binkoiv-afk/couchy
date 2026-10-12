package app.couchy.ui.settings

import androidx.compose.foundation.background
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ChildCare
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.VpnLock
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import app.couchy.ui.components.couchyClickable
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
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    val onTmdbKey: (String) -> Unit = {},
    val onBypass: (BypassMode, BypassKind) -> Unit = { _, _ -> },
    val onBypassConfig: (String) -> Unit = {},
    /** Checks the bypass end to end and reports the answer as a line of text. */
    val onCheckBypass: ((String) -> Unit) -> Unit = {},
    val onForgetBypassHosts: () -> Unit = {},
    /** Connects a WebDAV account; the callback gets an error, or null when it worked. */
    val onSyncConnect: (url: String, login: String, password: String, (String?) -> Unit) -> Unit = { _, _, _, _ -> },
    val onSyncNow: () -> Unit = {},
    /** Sign-in with the Google or Yandex account on the device; reports an error or null. */
    val onGoogleSync: ((String?) -> Unit) -> Unit = {},
    val onYandexSync: ((String?) -> Unit) -> Unit = {},
    val onSyncDisconnect: () -> Unit = {},
    val onKidsPin: (String) -> Unit = {},
    val onEnterKids: () -> Unit = {},
    /** The phone as a remote for Couchy on the TV. */
    val onRemote: () -> Unit = {},
)

/** Sync as the settings screen shows it. */
data class SyncUi(val connected: Boolean = false, val url: String = "", val login: String = "", val status: String = "Не подключено")

/** The bypass as the settings screen shows it. */
data class BypassUi(val status: String = "Не запущен", val available: Boolean = true)

/** The settings, each group on its own page, like on a phone's system settings. */
enum class SettingsPage(val title: String, val icon: ImageVector, val hint: String) {
    PROFILES(
        "Профили", Icons.Rounded.People,
        "У каждого в семье своя история, места остановки, «Смотреть позже», «Любимое» и подписки YouTube. Настройки, плейлисты и загрузки общие.",
    ),
    APPEARANCE("Оформление", Icons.Rounded.Palette, "Тема и то, чем вы управляете: пультом или пальцем."),
    PLAYBACK("Просмотр", Icons.Rounded.PlayCircle, "Качество, в котором начинается видео, и каким плеером его смотреть."),
    SOURCES("Источники и инфо", Icons.Rounded.Language, "Откуда брать описания и каталог, и рабочие адреса HDrezka, Kinogo и LordFilm, если основные не открываются."),
    TORRENTS(
        "Торренты", Icons.Rounded.CloudDownload,
        "Встроенный движок работает прямо в Couchy. TorrServe в сети: если он уже стоит на приставке или компьютере, укажите его адрес.",
    ),
    BYPASS(
        "Обход блокировок", Icons.Rounded.VpnLock,
        "Авто: Couchy сам включает обход только для заблокированных сайтов. Всегда: весь интернет Couchy идёт через обход.",
    ),
    SYNC(
        "Синхронизация", Icons.Rounded.Sync,
        "История, места остановки, избранное, плейлисты ТВ и список заблокированных сайтов одинаковые на всех устройствах. Хранится в одном файле на вашем диске.",
    ),
    KIDS(
        "Детский режим", Icons.Rounded.ChildCare,
        "Ребёнок видит только то, что вы отметили в медиатеке (долгое нажатие → «Показывать детям»), и детские ТВ-каналы. Поиска и ссылок нет, выйти можно только по PIN.",
    ),
    CAST(
        "Смотреть на ТВ", Icons.Rounded.Cast,
        "На телефоне нажмите кнопку ТВ в плеере или «Смотреть на ТВ» в медиатеке, и видео продолжится здесь с того же места. Couchy на приставке должен быть открыт.",
    ),
    ABOUT("О приложении", Icons.Rounded.Info, "Версия и обновления."),
}

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
    initialPage: SettingsPage? = null,
) {
    val form = LocalFormFactor.current
    val side = if (form.wide) 32.dp else 18.dp
    val pages = SettingsPage.entries.filter { it != SettingsPage.CAST || castInfo != null }
    val context = androidx.compose.ui.platform.LocalContext.current
    val profileName = remember { runCatching { app.couchy.data.Profiles.current(context).name }.getOrDefault("Основной") }
    var page by rememberSaveable { mutableStateOf(initialPage) }
    fun summary(p: SettingsPage): String = when (p) {
        SettingsPage.PROFILES -> profileName
        SettingsPage.APPEARANCE -> "${prefs.theme.label} · ${prefs.uiMode.label}"
        SettingsPage.PLAYBACK -> app.couchy.QualityChoice.name(app.couchy.QualityChoice.label) + " · " + if (app.couchy.ExternalPlayer.always) "внешний плеер" else "плеер Couchy"
        SettingsPage.SOURCES -> (if (prefs.tmdbKey.isNotBlank() || app.couchy.BuildConfig.TMDB_KEY.isNotBlank()) "Инфо: TMDB" else "Инфо: Cinemeta") + " · HDrezka: " + prefs.rezkaHost.ifBlank { "rezka.ag" }.substringAfter("://")
        SettingsPage.TORRENTS -> if (prefs.torrEngine == TorrEngineMode.REMOTE) "TorrServe · ${prefs.torrAddress}" else "Встроенный движок"
        SettingsPage.BYPASS -> if (prefs.bypassMode == BypassMode.OFF) "Выключен" else "${prefs.bypassMode.label} · ${bypass.status}"
        SettingsPage.SYNC -> sync.status
        SettingsPage.KIDS -> if (prefs.kidsPin.isEmpty()) "Не настроен" else "PIN задан"
        SettingsPage.CAST -> castInfo.orEmpty()
        SettingsPage.ABOUT -> "Couchy $version"
    }

    @Composable
    fun Body(p: SettingsPage) {
        when (p) {
            SettingsPage.APPEARANCE -> Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Group("Тема") { ThemeMode.entries.forEach { Chip(it.label, it == prefs.theme, onClick = { actions.onTheme(it) }) } }
                Group("Управление", "Пульт: крупные карточки и рамка фокуса. Сенсор: жесты и нижнее меню. Авто: по устройству.") {
                    UiMode.entries.forEach { Chip(it.label, it == prefs.uiMode, onClick = { actions.onUiMode(it) }) }
                }
            }
            SettingsPage.PLAYBACK -> PlaybackSettings()
            SettingsPage.SOURCES -> {
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
                    // Kinogo and LordFilm move often: a working address from a search engine goes here.
                    app.couchy.core.sources.Sites.mirrors.forEach { (id, default) -> MirrorField(id, default) }
                    KinoPubLogin()
                    var key by rememberSaveable(prefs.tmdbKey) { mutableStateOf(prefs.tmdbKey) }
                    Text(
                        if (app.couchy.BuildConfig.TMDB_KEY.isNotBlank()) {
                            "Описания, актёры, каталог и рекомендации идут из TMDB на русском: ключ уже встроен. Своё поле можно оставить пустым."
                        } else {
                            "Описания, актёры, каталог и рекомендации. Без ключа данные идут из Cinemeta (IMDb) на английском. " +
                                "С бесплатным ключом TMDB всё на русском: themoviedb.org → Настройки → API → ключ API или токен."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    OutlinedTextField(
                        key, { key = it },
                        label = { Text("Ключ TMDB") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (key.trim() != prefs.tmdbKey) SecondaryButton("Сохранить", null, onClick = { actions.onTmdbKey(key) })
                    // TMDB asks apps that use its API to say so.
                    Text(
                        "Данные о фильмах: TMDB и Cinemeta. This product uses the TMDB API but is not endorsed or certified by TMDB.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            SettingsPage.PROFILES -> ProfilesSettings()
            SettingsPage.TORRENTS -> TorrSettings(prefs, actions)
            SettingsPage.BYPASS -> BypassSettings(prefs, bypass, actions)
            SettingsPage.SYNC -> SyncSettings(sync, actions)
            SettingsPage.KIDS -> KidsSettings(prefs, actions)
            SettingsPage.CAST -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(castInfo.orEmpty(), style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Пульт: пауза, перемотка, громкость и следующая серия на приставке прямо с телефона. Открывается сам после «На ТВ».",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SecondaryButton("Пульт для ТВ", Icons.Rounded.Cast, onClick = actions.onRemote)
            }
            SettingsPage.ABOUT -> when (update) {
                UpdateState.Idle -> SecondaryButton("Проверить обновления", null, onClick = actions.onCheckUpdate)
                UpdateState.Checking -> Text("Проверяю…", style = MaterialTheme.typography.bodyMedium)
                UpdateState.UpToDate -> Text("Установлена последняя версия", style = MaterialTheme.typography.bodyMedium)
                is UpdateState.Available -> SecondaryButton("Установить сборку ${update.build}", null, onClick = actions.onInstallUpdate)
                is UpdateState.Downloading -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.LinearProgressIndicator(progress = { update.progress }, modifier = Modifier.fillMaxWidth())
                    Text("Скачиваю… ${(update.progress * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
                }
                is UpdateState.Failed -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(update.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SecondaryButton("Проверить ещё раз", null, onClick = actions.onCheckUpdate)
                }
            }
        }
    }

    @Composable
    fun PageView(p: SettingsPage, onBack: (() -> Unit)?, pageModifier: Modifier) {
        Column(
            pageModifier.verticalScroll(rememberScrollState()).padding(horizontal = side, vertical = 20.dp).widthIn(max = 720.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (onBack != null) SecondaryButton(null, Icons.AutoMirrored.Rounded.ArrowBack, onClick = onBack)
                Text(p.title, style = MaterialTheme.typography.headlineSmall)
            }
            Text(p.hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(18.dp)).padding(18.dp),
            ) { Body(p) }
        }
    }

    @Composable
    fun ListView(selected: SettingsPage?, listModifier: Modifier) {
        Column(
            listModifier.verticalScroll(rememberScrollState()).padding(horizontal = side, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Настройки", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(bottom = 10.dp))
            pages.forEach { p -> PageRow(p, summary(p), p == selected) { page = p } }
        }
    }

    if (form.wide) {
        // Remote and big screens: the list stays on the left, the open page on the right.
        val open = page ?: SettingsPage.APPEARANCE
        Row(modifier.fillMaxSize()) {
            ListView(open, Modifier.width(if (form.isTv) 380.dp else 340.dp).fillMaxHeight())
            PageView(open, null, Modifier.weight(1f).fillMaxHeight())
        }
    } else {
        val open = page
        if (open == null) {
            ListView(null, modifier.fillMaxSize())
        } else {
            if (LocalOnBackPressedDispatcherOwner.current != null) BackHandler { page = null }
            PageView(open, { page = null }, modifier.fillMaxSize())
        }
    }
}

@Composable
private fun PageRow(page: SettingsPage, summary: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .couchyClickable(shape, focusScale = 1.02f, onClick = onClick)
            .background(if (selected) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainer, shape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(page.icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(page.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A labelled set of choices that wraps onto the next line when it does not fit. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Group(title: String, hint: String? = null, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

/** Signing in to Kino.pub with a code typed on kino.pub/device, like on a TV. */
@Composable
private fun KinoPubLogin() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val account = remember { (context.applicationContext as app.couchy.CouchyApp).kinopub }
    val signedIn by account.signedIn.collectAsState()
    var code by remember { mutableStateOf<app.couchy.core.sources.KinoPub.DeviceCode?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var asking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Kino.pub", style = MaterialTheme.typography.titleSmall)
        val c = code
        when {
            signedIn -> {
                Text("Вы вошли: фильмы и сериалы из Kino.pub появляются среди источников на странице фильма.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SecondaryButton("Выйти", null, onClick = { account.signOut() })
            }
            c != null -> {
                Text("Откройте ${c.verifyUrl.substringAfter("://")} на телефоне или компьютере и введите код:", style = MaterialTheme.typography.bodyMedium)
                Text(c.userCode, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
                Text("Как только код введён, Couchy войдёт сам.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LaunchedEffect(c.code) {
                    val until = System.currentTimeMillis() + c.expiresIn * 1000L
                    while (System.currentTimeMillis() < until) {
                        kotlinx.coroutines.delay(c.interval.coerceAtLeast(3) * 1000L)
                        if (account.poll(c.code)) { code = null; return@LaunchedEffect }
                    }
                    code = null
                    error = "Время вышло. Нажмите «Войти» ещё раз."
                }
            }
            else -> {
                Text("С подпиской Kino.pub его фильмы и сериалы, со всеми озвучками и в 4K, играют прямо в Couchy.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                SecondaryButton(if (asking) "Получаю код…" else "Войти", null, onClick = {
                    if (asking) return@SecondaryButton
                    asking = true
                    error = null
                    scope.launch {
                        runCatching { account.start() }
                            .onSuccess { code = it }
                            .onFailure { error = "Kino.pub не ответил: ${it.message}" }
                        asking = false
                    }
                })
            }
        }
    }
}

/** The address of a site that moves between domains, kept in prefs "sources". */
@Composable
private fun MirrorField(id: String, default: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("sources", android.content.Context.MODE_PRIVATE) }
    var saved by remember { mutableStateOf(prefs.getString(id, "").orEmpty()) }
    var text by rememberSaveable { mutableStateOf(saved) }
    val name = app.couchy.core.sources.Sites.all.firstOrNull { it.id == id }?.name ?: id
    OutlinedTextField(
        text, { text = it },
        label = { Text("Адрес $name") },
        placeholder = { Text(default) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
    if (text.trim() != saved) SecondaryButton("Сохранить", null, onClick = {
        val value = normalizeHost(text)
        prefs.edit().putString(id, value).apply()
        app.couchy.core.sources.Sites.hosts = app.couchy.core.sources.Sites.hosts + (id to value)
        saved = value
        text = value
    })
}

@Composable
private fun PlaybackSettings() {
    var quality by remember { mutableStateOf(app.couchy.QualityChoice.label) }
    var external by remember { mutableStateOf(app.couchy.ExternalPlayer.always) }
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Group("Качество", "Видео начинается в нём, а если такого нет, то в ближайшем ниже. Касается онлайн-источников и YouTube.") {
            app.couchy.QualityChoice.options.forEach { option ->
                Chip(app.couchy.QualityChoice.name(option), option == quality, onClick = { app.couchy.QualityChoice.label = option; quality = option })
            }
        }
        Group("Плеер", "Внешний: VLC, MX Player или другой установленный. Место остановки он в Couchy не возвращает. Из плеера Couchy видео тоже можно отдать: «Озвучка» → «Открыть во внешнем плеере».") {
            Chip("Couchy", !external, onClick = { app.couchy.ExternalPlayer.always = false; external = false })
            Chip("Внешний", external, onClick = { app.couchy.ExternalPlayer.always = true; external = true })
        }
        var auto by remember { mutableStateOf(app.couchy.AutoDownload.on) }
        Group("Следующие серии", "Пока смотрите серию, две следующие скачиваются по Wi‑Fi и потом играют без ожидания. Просмотренные удаляются сами.") {
            Chip("Скачивать заранее", auto, onClick = { app.couchy.AutoDownload.on = true; auto = true })
            Chip("Не скачивать", !auto, onClick = { app.couchy.AutoDownload.on = false; auto = false })
        }
    }
}

/** Choices in a row that wrap instead of running off the screen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Choices(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@Composable
private fun TorrSettings(prefs: Prefs, actions: SettingsActions) {
    var address by rememberSaveable(prefs.torrAddress) { mutableStateOf(prefs.torrAddress) }
    var result by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Choices {
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
private fun ProfilesSettings() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var all by remember { mutableStateOf(app.couchy.data.Profiles.list(context)) }
    val current = remember { app.couchy.data.Profiles.currentId(context) }
    var name by rememberSaveable { mutableStateOf("") }
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        all.forEach { p ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ProfileAvatar(p, 40.dp)
                Text(
                    p.name + if (p.id == current) " · сейчас" else "",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (p.id != current) SecondaryButton("Включить", null, onClick = { (context as? android.app.Activity)?.let { app.couchy.data.Profiles.switch(it, p.id) } })
                SecondaryButton("Имя", null, onClick = { renaming = p.id; name = p.name })
                if (p.id != current && p.id != app.couchy.data.Profiles.MAIN) SecondaryButton("Удалить", null, onClick = {
                    app.couchy.data.Profiles.remove(context, p.id)
                    all = app.couchy.data.Profiles.list(context)
                })
            }
        }
        OutlinedTextField(
            name,
            { name = it.take(24) },
            label = { Text(if (renaming != null) "Новое имя" else "Имя нового профиля") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Choices {
            if (renaming != null) {
                SecondaryButton("Сохранить имя", null, onClick = {
                    app.couchy.data.Profiles.rename(context, renaming!!, name)
                    all = app.couchy.data.Profiles.list(context)
                    renaming = null
                    name = ""
                })
                SecondaryButton("Отмена", null, onClick = { renaming = null; name = "" })
            } else if (name.isNotBlank()) {
                SecondaryButton("Добавить профиль", null, onClick = {
                    app.couchy.data.Profiles.add(context, name)
                    all = app.couchy.data.Profiles.list(context)
                    name = ""
                })
            }
        }
        Text(
            "Когда профилей больше одного, Couchy при запуске спрашивает, кто смотрит. Детский режим с PIN работает в любом профиле.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A coloured circle with the first letter of the name. */
@Composable
fun ProfileAvatar(p: app.couchy.data.Profile, size: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier.size(size).background(androidx.compose.ui.graphics.Color(p.color), androidx.compose.foundation.shape.CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            p.name.take(1).uppercase(),
            color = androidx.compose.ui.graphics.Color.White,
            fontSize = (size.value / 2.3f).sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
        )
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
            Choices {
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
            val where = when (sync.url.trimEnd('/')) {
                YANDEX_DAV, app.couchy.sync.WebDav.YANDEX_ACCOUNT -> "Яндекс Диск"
                app.couchy.sync.WebDav.GOOGLE_ACCOUNT -> "Google Диск"
                else -> sync.url
            }
            Text(listOf(where, sync.login).filter { it.isNotBlank() && it != "Google" }.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
            Text(sync.status, style = MaterialTheme.typography.bodySmall, color = muted)
            Choices {
                SecondaryButton("Синхронизировать", null, onClick = actions.onSyncNow)
                SecondaryButton("Отключить", null, onClick = actions.onSyncDisconnect)
            }
            return@Column
        }
        // 0: Google, 1: Яндекс, 2: other WebDAV
        var kind by rememberSaveable { mutableStateOf(0) }
        var url by rememberSaveable { mutableStateOf("") }
        var login by rememberSaveable { mutableStateOf("") }
        var password by rememberSaveable { mutableStateOf("") }
        var byPassword by rememberSaveable { mutableStateOf(false) }
        var result by remember { mutableStateOf<String?>(null) }
        Text(
            "История, места просмотра, избранное и плейлисты одинаковые на телефоне, планшете и ТВ.",
            style = MaterialTheme.typography.bodySmall,
            color = muted,
        )
        Choices {
            Chip("Google Диск", kind == 0, onClick = { kind = 0; result = null })
            Chip("Яндекс Диск", kind == 1, onClick = { kind = 1; result = null })
            Chip("Другой WebDAV", kind == 2, onClick = { kind = 2; result = null })
        }
        val fields = kind == 2 || (kind == 1 && byPassword)
        when {
            kind == 0 -> {
                Text("Войдите аккаунтом Google с этого устройства. Couchy хранит данные в своей скрытой папке и не видит остальные файлы.", style = MaterialTheme.typography.bodySmall, color = muted)
                app.couchy.ui.components.PrimaryButton("Войти через Google", null, onClick = {
                    result = "Подключаю…"
                    actions.onGoogleSync { error -> result = error ?: "Готово." }
                })
            }
            kind == 1 && !byPassword -> {
                Text("Войдите аккаунтом Яндекса с этого устройства, пароль не нужен. Данные лягут в папку «Приложения/Couchy».", style = MaterialTheme.typography.bodySmall, color = muted)
                app.couchy.ui.components.PrimaryButton("Войти через Яндекс", null, onClick = {
                    result = "Подключаю…"
                    actions.onYandexSync { error -> result = error ?: "Готово." }
                })
                Text(
                    "Войти по паролю приложения",
                    style = MaterialTheme.typography.labelLarge,
                    color = muted,
                    modifier = Modifier.couchyClickable(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)) { byPassword = true }.padding(vertical = 6.dp),
                )
            }
            kind == 1 -> Text(
                "Нужен пароль приложения: id.yandex.ru → Безопасность → Пароли приложений → Файлы (WebDAV).",
                style = MaterialTheme.typography.bodySmall,
                color = muted,
            )
            else -> OutlinedTextField(
                url, { url = it; result = null },
                label = { Text("Адрес WebDAV") },
                placeholder = { Text("https://cloud.example.com/remote.php/dav/files/me") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (fields) {
            OutlinedTextField(
                login, { login = it; result = null },
                label = { Text(if (kind == 1) "Логин Яндекса" else "Логин") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                password, { password = it; result = null },
                label = { Text(if (kind == 1) "Пароль приложения" else "Пароль") },
                singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            SecondaryButton("Подключить", null, onClick = {
                val address = if (kind == 1) YANDEX_DAV else normalizeHost(url)
                if (login.isBlank() || password.isBlank() || address.isBlank()) {
                    result = "Заполните все поля."
                    return@SecondaryButton
                }
                result = "Подключаю…"
                actions.onSyncConnect(address, login, password) { error -> result = error }
            })
        }
        result?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = muted) }
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
        Choices {
            BypassMode.entries.forEach { mode -> Chip(mode.label, mode == prefs.bypassMode, onClick = { actions.onBypass(mode, prefs.bypassKind) }) }
        }
        if (prefs.bypassMode == BypassMode.OFF) return@Column
        Choices {
            BypassKind.entries.forEach { kind -> Chip(kind.label, kind == prefs.bypassKind, onClick = { actions.onBypass(prefs.bypassMode, kind) }) }
        }
        if (prefs.bypassKind == BypassKind.WARP) {
            Text("Бесплатный WARP от Cloudflare, ключи Couchy получит сам. Пакеты маскируются, чтобы провайдер не узнал WireGuard.", style = MaterialTheme.typography.bodySmall, color = muted)
        } else {
            OutlinedTextField(
                config,
                { config = it; result = null },
                label = { Text("Конфиг WireGuard/AmneziaWG или ссылка VLESS") },
                placeholder = { Text("vless://…\nили\n[Interface]\nPrivateKey = …\n[Peer]\nEndpoint = …") },
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
