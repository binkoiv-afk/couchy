package app.couchy.pair

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.couchy.CouchyApp
import app.couchy.core.cast.CastHttp
import app.couchy.core.pair.PairLink
import app.couchy.ui.components.CouchySheet
import app.couchy.ui.components.PrimaryButton
import app.couchy.ui.components.SecondaryButton
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** A pairing link that came from the phone's camera app, waiting for the person to confirm. */
val incomingPair = MutableStateFlow<PairLink?>(null)

private enum class Phase { WAITING, APPLYING, DONE, FAILED }

/** On the TV: the QR the phone scans. While it is on screen, the phone's settings are accepted. */
@Composable
fun TvPairDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as CouchyApp
    val scope = rememberCoroutineScope()
    val code = remember { PairLink.newCode() }
    val address = remember { app.cast.start(); app.cast.address() }
    val link = remember(address) {
        address?.let {
            val host = it.substringBefore(':')
            val port = it.substringAfter(':', "").toIntOrNull() ?: CastHttp.PORT
            PairLink(host, port, code, android.os.Build.MODEL.orEmpty().ifBlank { "Приставка" })
        }
    }
    var phase by remember { mutableStateOf(Phase.WAITING) }
    DisposableEffect(code) {
        Transfer.code = code
        Transfer.onReceived = { bundle ->
            phase = Phase.APPLYING
            scope.launch {
                runCatching { Transfer.apply(context, bundle) }
                    .onSuccess { phase = Phase.DONE; delay(1_800); Transfer.restart(context) }
                    .onFailure { phase = Phase.FAILED }
            }
        }
        onDispose {
            Transfer.code = null
            Transfer.onReceived = null
        }
    }
    CouchySheet("Перенос с телефона", onDismiss, scroll = false) {
        when (phase) {
            Phase.WAITING -> Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                val qr = remember(link) { link?.let { qrBitmap(it.toLink()) } }
                if (qr != null) {
                    Box(Modifier.background(Color.White, RoundedCornerShape(16.dp)).padding(12.dp)) {
                        Image(qr.asImageBitmap(), "QR", filterQuality = FilterQuality.None, modifier = Modifier.size(220.dp))
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (qr == null) {
                        Text("Приставка не в домашней сети. Подключите Wi‑Fi или кабель и откройте снова.", style = MaterialTheme.typography.bodyLarge)
                    } else {
                        Step("1", "На телефоне откройте Couchy → Настройки → «Перенос на приставку»")
                        Step("2", "Нажмите «Сканировать» и наведите камеру на код")
                        Step("3", "Входы, обход, подписки, медиатека и плейлисты появятся здесь сами")
                        Text("Код: $code", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Phase.APPLYING -> Busy("Получаю настройки с телефона…")
            Phase.DONE -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
                Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                Text("Готово. Couchy перезапускается.", style = MaterialTheme.typography.titleMedium)
            }
            Phase.FAILED -> Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(8.dp)) {
                Text("Не получилось принять настройки. Попробуйте ещё раз.", style = MaterialTheme.typography.bodyLarge)
                SecondaryButton("Закрыть", null, onClick = onDismiss)
            }
        }
    }
}

@Composable
private fun Step(n: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(26.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
            Text(n, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.background)
        }
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Busy(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
        CircularProgressIndicator(Modifier.size(24.dp), color = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

internal fun qrBitmap(text: String, size: Int = 600): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 0))
    val pixels = IntArray(size * size) { i -> if (matrix.get(i % size, i / size)) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

/** On the phone: opens Play services' scanner; a Couchy QR comes back as a link. */
fun scanPair(context: android.content.Context, onLink: (PairLink) -> Unit, onFail: (String) -> Unit) {
    runCatching {
        com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(context).startScan()
            .addOnSuccessListener { code ->
                val link = PairLink.parse(code.rawValue)
                if (link != null) onLink(link) else onFail("Это не QR Couchy. Откройте на приставке Настройки → «Перенос на приставку».")
            }
            .addOnFailureListener { onFail("Сканер не открылся: на телефоне нужны сервисы Google Play.") }
    }.onFailure { onFail("Сканер не открылся: на телефоне нужны сервисы Google Play.") }
}

/** On the phone, after the scan: what moves to the TV, and one button. */
@Composable
fun PhonePairDialog(link: PairLink, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf(Phase.WAITING) }
    CouchySheet("Перенести на «${link.name}»?", onDismiss) {
        when (phase) {
            Phase.WAITING -> Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(8.dp)) {
                Text(
                    "На приставку уйдут вход в синхронизацию и Kino.pub, обход блокировок, адреса сайтов, подписки YouTube, медиатека и плейлисты ТВ. Телефон и приставка должны быть в одной сети.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                PrimaryButton("Перенести", Icons.Rounded.Tv, modifier = Modifier.fillMaxWidth(), onClick = {
                    phase = Phase.APPLYING
                    scope.launch {
                        val ok = runCatching { Transfer.send(context, link) }.getOrDefault(false)
                        phase = if (ok) Phase.DONE else Phase.FAILED
                    }
                })
            }
            Phase.APPLYING -> Busy("Отправляю на приставку…")
            Phase.DONE -> Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                    Text("Готово. Приставка перезапустится с вашими настройками.", style = MaterialTheme.typography.titleMedium)
                }
                SecondaryButton("Закрыть", null, onClick = onDismiss)
            }
            Phase.FAILED -> Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(8.dp)) {
                Text(
                    "Приставка не ответила. Проверьте, что она в той же сети Wi‑Fi и QR ещё на экране, и отсканируйте снова.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                SecondaryButton("Закрыть", null, onClick = onDismiss)
            }
        }
    }
}

/** The settings page: the TV shows its QR, the phone scans it. */
@Composable
fun TransferSettings(tv: Boolean) {
    val context = LocalContext.current
    var showQr by remember { mutableStateOf(false) }
    var scanned by remember { mutableStateOf<PairLink?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (tv) {
            PrimaryButton("Показать QR", Icons.Rounded.Tv, onClick = { showQr = true })
        } else {
            PrimaryButton("Сканировать QR с приставки", Icons.Rounded.QrCodeScanner, onClick = {
                problem = null
                scanPair(context, onLink = { scanned = it }, onFail = { problem = it })
            })
        }
        problem?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    if (showQr) TvPairDialog(onDismiss = { showQr = false })
    scanned?.let { PhonePairDialog(it, onDismiss = { scanned = null }) }
}
