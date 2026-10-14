package app.couchy.pair

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.couchy.CouchyApp
import app.couchy.core.cast.CastHttp
import app.couchy.core.pair.PairLink
import app.couchy.core.pair.PhoneForm
import app.couchy.ui.components.CouchySheet
import app.couchy.ui.components.LocalFormFactor
import app.couchy.ui.components.SecondaryButton

/** The form on the TV's screen right now, waiting for the phone. */
object PhoneType {
    @Volatile var code: String? = null
        set(value) { synchronized(this) { field = value; misses = 0 } }
    /** Wrong codes; after a few the QR stops working, so it cannot be guessed. */
    private var misses = 0
    @Volatile var title: String = ""
    @Volatile var fields: List<PhoneForm.Field> = emptyList()
    @Volatile var onText: ((List<String>) -> Unit)? = null

    /** GET /type?c=…: the page for the phone's browser. */
    fun page(path: String): Pair<Int, String> {
        val c = path.substringAfter("c=", "").substringBefore('&')
        val expected = check(c) ?: return 403 to PhoneForm.expired()
        return 200 to PhoneForm.page(title, fields, expected)
    }

    /** The code when [c] is it; a wrong one counts towards closing the QR. */
    private fun check(c: String): String? = synchronized(this) {
        val expected = code ?: return null
        if (c == expected) return expected
        if (++misses >= 5) code = null
        null
    }

    /** POST /type: the typed text, handed to the TV's screen. */
    fun receive(body: String): Pair<Int, String> {
        val (c, values) = PhoneForm.parse(body)
        val handler = onText ?: return 403 to PhoneForm.expired()
        check(c) ?: return 403 to PhoneForm.expired()
        code = null
        android.os.Handler(android.os.Looper.getMainLooper()).post { handler(values) }
        return 200 to PhoneForm.done()
    }
}

/**
 * On a TV: «Ввести с телефона» under a form. The QR opens a page on the phone with the same
 * fields; what is sent fills them here. Nothing shows on phones and tablets.
 */
@Composable
fun PhoneInputButton(title: String, fields: List<PhoneForm.Field>, onFill: (List<String>) -> Unit) {
    if (!LocalFormFactor.current.isTv) return
    var open by remember { mutableStateOf(false) }
    SecondaryButton("Ввести с телефона", Icons.Rounded.Smartphone, onClick = { open = true })
    if (open) PhoneInputDialog(title, fields, onFill = { onFill(it); open = false }, onDismiss = { open = false })
}

@Composable
private fun PhoneInputDialog(title: String, fields: List<PhoneForm.Field>, onFill: (List<String>) -> Unit, onDismiss: () -> Unit) {
    val app = LocalContext.current.applicationContext as CouchyApp
    val code = remember { PairLink.newCode() }
    val address = remember { app.cast.start(); app.cast.address() }
    DisposableEffect(code) {
        PhoneType.title = title
        PhoneType.fields = fields
        PhoneType.onText = onFill
        PhoneType.code = code
        onDispose {
            PhoneType.code = null
            PhoneType.onText = null
        }
    }
    CouchySheet("Ввести с телефона", onDismiss, scroll = false) {
        val qr = remember(address) {
            address?.let {
                val host = it.substringBefore(':')
                val port = it.substringAfter(':', "").toIntOrNull() ?: CastHttp.PORT
                qrBitmap("http://$host:$port/type?c=$code")
            }
        }
        if (qr == null) {
            Text("Приставка не в домашней сети. Подключите Wi‑Fi или кабель.", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(8.dp))
            return@CouchySheet
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
            Box(Modifier.background(Color.White, RoundedCornerShape(16.dp)).padding(12.dp)) {
                Image(qr.asImageBitmap(), "QR", filterQuality = FilterQuality.None, modifier = Modifier.size(200.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Наведите камеру телефона на код и откройте ссылку.", style = MaterialTheme.typography.bodyLarge)
                Text("Введите текст на телефоне и нажмите «Отправить на ТВ»: он появится здесь.", style = MaterialTheme.typography.bodyLarge)
                Text("Телефон должен быть в той же сети Wi‑Fi.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
