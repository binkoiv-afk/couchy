package app.couchy.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Build
import app.couchy.BuildConfig
import app.couchy.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * «Отчёт для разработчика»: what «Проверка связи» found, the last player failures and the app's
 * recent log as one text, so it can be pasted into a chat instead of photographing the screen.
 * It never leaves the device by itself: the person shares it, or on a TV opens it on the phone by
 * QR over the home network. Keys, tokens and passwords are cut out; bypass server settings are
 * never included.
 */
internal object Report {
    /** The report the TV offers to the phone right now, and the code that opens it. */
    @Volatile var shown: Pair<String, String>? = null

    suspend fun build(context: Context, prefs: Prefs, checks: String): String = withContext(Dispatchers.IO) {
        val filmix = context.getSharedPreferences("filmix", Context.MODE_PRIVATE).getString("token", null) != null
        buildString {
            appendLine("Couchy ${BuildConfig.VERSION_NAME} · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}")
            appendLine("Обход: ${prefs.bypassMode} ${prefs.bypassKind} · HDrezka: ${prefs.rezkaHost.ifBlank { "по умолчанию" }} · Filmix вход: $filmix")
            appendLine()
            appendLine("== Проверка связи")
            appendLine(checks)
            appendLine()
            appendLine("== Ошибки плеера")
            LastPlayError.recent.forEach { appendLine(it) }
            appendLine()
            appendLine("== Журнал")
            log().forEach { appendLine(it) }
        }.let(::clean)
    }

    /** The phone's share sheet: Telegram, mail, a chat. */
    fun share(context: Context, text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).putExtra(Intent.EXTRA_SUBJECT, "Отчёт Couchy")
        runCatching { context.startActivity(Intent.createChooser(send, "Отправить отчёт").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** GET /report?c=…: the report as a page on the phone, with a copy button. */
    fun page(path: String): Pair<Int, String> {
        val (code, text) = shown ?: return 403 to "Код устарел."
        val c = path.substringAfter("c=", "").substringBefore('&')
        if (c != code) return 403 to "Код устарел."
        val escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return 200 to """<!doctype html><html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Отчёт Couchy</title><style>body{font-family:system-ui,sans-serif;background:#111;color:#eee;margin:0;padding:16px}
button{background:#e8b04a;color:#111;font-weight:600;font-size:17px;border:0;padding:14px 18px;border-radius:14px;width:100%;margin-bottom:14px}
pre{white-space:pre-wrap;word-break:break-word;font-size:12px;color:#ccc}</style></head><body>
<button id="b">Скопировать отчёт</button><pre id="t">$escaped</pre>
<script>document.getElementById('b').onclick=function(){var t=document.getElementById('t').innerText;
if(navigator.share){navigator.share({text:t}).catch(function(){})}
else{var r=document.createRange();r.selectNodeContents(document.getElementById('t'));var s=getSelection();s.removeAllRanges();s.addRange(r);document.execCommand('copy');this.textContent='Скопировано'}};</script>
</body></html>"""
    }

    /** Couchy's own warnings and errors, and the player's, from the system log. */
    private fun log(): List<String> = runCatching {
        val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "3000", "-v", "time", "--pid=${android.os.Process.myPid()}"))
        val lines = p.inputStream.bufferedReader().readLines()
        p.destroy()
        lines.filter { l ->
            Regex(""" [WEF]/""").containsMatchIn(l) || l.contains("Couchy") || l.contains("ExoPlayer") || l.contains("MediaCodec") || l.contains("VLC")
        }.takeLast(150).map { it.take(300) }
    }.getOrElse { listOf("журнал не прочитать: ${it.message}") }

    /** Tokens, keys and passwords in links and text are replaced with ***. */
    private fun clean(s: String): String =
        s.replace(Regex("""(?i)((?:token|key|apikey|api_key|password|pass|passwd|sig|signature|auth|user_dev_token|cookie|session)[=:]\s*)([^&\s"',;]+)"""), "$1***")
}
