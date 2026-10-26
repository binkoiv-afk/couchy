package app.couchy.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.couchy.core.bypass.BypassMode
import app.couchy.core.sources.Cinemeta
import app.couchy.core.sources.Sites
import app.couchy.data.Prefs
import app.couchy.data.TorrEngineMode
import app.couchy.sources.ChallengeException
import app.couchy.sources.Web
import app.couchy.ui.components.SecondaryButton
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private enum class Health { RUNNING, OK, WARN, FAIL }

private data class Check(val name: String, val health: Health = Health.RUNNING, val text: String = "Проверяю…")

/**
 * «Проверка связи»: one look at everything Couchy needs from the network, each with what to do
 * in plain words when it does not work.
 */
@Composable
internal fun ConnectionDoctor(prefs: Prefs, actions: SettingsActions) {
    val context = LocalContext.current
    val web = remember { Web(context) }
    val checks = remember { mutableStateListOf<Check>() }
    var round by remember { mutableIntStateOf(0) }

    LaunchedEffect(round) {
        val rezka = prefs.rezkaHost.ifBlank { "https://rezka.ag" }.trimEnd('/')
        val sites = listOf("Интернет" to "https://www.gstatic.com/generate_204", "Каталог и описания" to "${Cinemeta.BASE}/manifest.json", "HDrezka" to rezka) +
            Sites.mirrors.keys.map { id -> (Sites.all.firstOrNull { it.id == id }?.name ?: id) to Sites.host(id) }
        val extra = buildList {
            if (prefs.bypassMode != BypassMode.OFF) add("Обход блокировок")
            if (prefs.torrEngine == TorrEngineMode.REMOTE) add("TorrServe")
        }
        checks.clear()
        checks.addAll((sites.map { it.first } + extra).map { Check(it) })
        // This device: what differs between a phone that plays and a box or projector that does not.
        checks.add(Check("Устройство", Health.OK, DeviceChecks.device(context)))
        DeviceChecks.decoders().let { (ok, text) -> checks.add(Check("Декодеры видео", if (ok) Health.OK else Health.FAIL, text)) }
        DeviceChecks.webView(context).let { (ok, text) -> checks.add(Check("WebView", if (ok) Health.OK else Health.WARN, text)) }
        checks.add(Check("Тестовое видео"))
        LastPlayError.text?.let { checks.add(Check("Последняя ошибка плеера", Health.WARN, it)) }
        fun put(name: String, health: Health, text: String) {
            val i = checks.indexOfFirst { it.name == name }
            if (i >= 0) checks[i] = Check(name, health, text)
        }
        coroutineScope {
            val internet = async { site(web, sites[0].second) }
            sites.drop(1).forEach { (name, url) ->
                async {
                    val error = site(web, url)
                    when {
                        error == null -> put(name, Health.OK, "Открывается")
                        error is ChallengeException -> put(name, Health.WARN, "Просит проверку «я не робот». Откройте любой фильм с этого сайта и пройдите её один раз.")
                        internet.await() != null -> put(name, Health.FAIL, "Нет интернета, поэтому не открывается.")
                        name == "Каталог и описания" -> put(name, Health.FAIL, "Не открывается. Включите обход блокировок или добавьте ключ TMDB в «Источники и инфо».")
                        else -> put(
                            name, Health.FAIL,
                            "Не открывается: ${error.message ?: "нет ответа"}. Найдите рабочий адрес в поисковике и впишите его в «Источники и инфо»" +
                                if (prefs.bypassMode == BypassMode.OFF) " или включите обход блокировок." else ".",
                        )
                    }
                }
            }
            put(
                "Интернет",
                if (internet.await() == null) Health.OK else Health.FAIL,
                if (internet.await() == null) "Есть" else "Нет связи. Проверьте Wi‑Fi или кабель и роутер.",
            )
            async {
                val error = DeviceChecks.testVideo(context)
                put("Тестовое видео", if (error == null) Health.OK else Health.FAIL, error?.let { "Не играет: $it" } ?: "Играет: плеер и декодер на этом устройстве в порядке.")
            }
            if (prefs.bypassMode != BypassMode.OFF) async {
                val text = report { actions.onCheckBypass(it) }
                val ok = text.startsWith("Работает")
                put("Обход блокировок", if (ok) Health.OK else Health.FAIL, if (ok) text else "$text Попробуйте другой тип обхода или свой конфиг в «Обход блокировок».")
            }
            if (prefs.torrEngine == TorrEngineMode.REMOTE) async {
                val text = report { actions.onCheckTorr(prefs.torrAddress, it) }
                put("TorrServe", if (text.startsWith("Работает")) Health.OK else Health.FAIL, text)
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val bad = checks.count { it.health == Health.FAIL || it.health == Health.WARN }
        val running = checks.any { it.health == Health.RUNNING }
        Text(
            when {
                running -> "Проверяю…"
                bad == 0 -> "Всё работает."
                else -> "Нужно поправить: $bad."
            },
            style = MaterialTheme.typography.titleMedium,
        )
        checks.forEach { CheckRow(it) }
        // An old WebView cannot pass the sites' «я не робот» checks; the update is one press away where the store is.
        if (checks.any { it.name == "WebView" && it.health == Health.WARN }) {
            SecondaryButton("Обновить WebView", Icons.Rounded.Refresh, onClick = { DeviceChecks.updateWebView(context) })
        }
        if (!running) SecondaryButton("Проверить ещё раз", Icons.Rounded.Refresh, onClick = { round++ })
        if (!running) ReportButton(prefs, checks.joinToString("\n") { "${it.name} [${it.health}]: ${it.text}" })
    }
}

@Composable
private fun CheckRow(check: Check) {
    val (icon, tint) = when (check.health) {
        Health.RUNNING -> Icons.Rounded.HourglassTop to MaterialTheme.colorScheme.onSurfaceVariant
        Health.OK -> Icons.Rounded.CheckCircle to Color(0xFF4CAF50)
        Health.WARN -> Icons.Rounded.Warning to Color(0xFFFFB300)
        Health.FAIL -> Icons.Rounded.Error to MaterialTheme.colorScheme.error
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = tint, modifier = Modifier.padding(top = 2.dp).size(22.dp))
        Column {
            Text(check.name, style = MaterialTheme.typography.titleSmall)
            Text(check.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Null when [url] opens, else what went wrong. */
private suspend fun site(web: Web, url: String): Exception? = try {
    web.get(url)
    null
} catch (e: Exception) {
    e
}

/** Waits for a check that answers through a callback. */
private suspend fun report(start: ((String) -> Unit) -> Unit): String = suspendCancellableCoroutine { c ->
    start { if (c.isActive) c.resume(it) }
}

/** «Отчёт для разработчика»: the phone shares it; a TV shows a QR that opens it on the phone. */
@Composable
private fun ReportButton(prefs: Prefs, checks: String) {
    val context = LocalContext.current
    val tv = app.couchy.ui.components.LocalFormFactor.current.isTv
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var qr by remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    SecondaryButton("Отчёт для разработчика", Icons.AutoMirrored.Rounded.Send, onClick = {
        scope.launch {
            val text = Report.build(context, prefs, checks)
            if (!tv) {
                Report.share(context, text)
                return@launch
            }
            val couchy = context.applicationContext as app.couchy.CouchyApp
            couchy.cast.start()
            val address = couchy.cast.address()
            if (address == null) {
                Report.share(context, text)
                return@launch
            }
            val code = app.couchy.core.pair.PairLink.newCode()
            Report.shown = code to text
            qr = "http://${address.substringBefore(':')}:${couchy.cast.port}/report?c=$code"
        }
    })
    qr?.let { link ->
        androidx.compose.runtime.DisposableEffect(link) { onDispose { Report.shown = null } }
        app.couchy.ui.components.CouchySheet("Отчёт на телефон", onDismiss = { qr = null }, scroll = false) {
            val bitmap = remember(link) { app.couchy.pair.qrBitmap(link) }
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                androidx.compose.foundation.Image(
                    bitmap.asImageBitmap(), "QR", filterQuality = androidx.compose.ui.graphics.FilterQuality.None,
                    modifier = Modifier.background(Color.White, androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).padding(12.dp).size(200.dp),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Наведите камеру телефона на код и откройте ссылку.", style = MaterialTheme.typography.bodyLarge)
                    Text("Нажмите «Скопировать отчёт» и вставьте его в чат.", style = MaterialTheme.typography.bodyLarge)
                    Text("Телефон должен быть в той же сети Wi‑Fi.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
