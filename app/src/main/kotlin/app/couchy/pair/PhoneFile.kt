package app.couchy.pair

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import app.couchy.ui.components.CouchySheet
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.io.InputStream

/** A file on its way from the phone: how much of it has come. */
data class FileProgress(val name: String, val done: Long, val total: Long)

/**
 * A file from the phone to the TV by QR: the QR opens a page on the phone, the person picks a
 * video, an .m3u playlist or a torrent, and it comes over the home Wi‑Fi straight into Couchy,
 * written to storage as it arrives, so even a film of several gigabytes fits.
 */
object PhoneFile {
    @Volatile var code: String? = null
        set(value) { synchronized(this) { field = value; misses = 0 } }
    /** Wrong codes; after a few the QR stops working, so it cannot be guessed. */
    private var misses = 0
    @Volatile var onFile: ((File) -> Unit)? = null
    val progress = MutableStateFlow<FileProgress?>(null)
    private lateinit var context: Context

    fun init(context: Context) { this.context = context.applicationContext }

    /** Where received files are kept: the app's own folder, removed with the app. */
    private fun dir(): File = (context.getExternalFilesDir("received") ?: File(context.filesDir, "received")).apply { mkdirs() }

    private fun check(c: String): Boolean = synchronized(this) {
        val expected = code ?: return false
        if (c == expected) return true
        if (++misses >= 5) code = null
        false
    }

    private fun param(path: String, name: String): String =
        path.substringAfter('?', "").split('&').firstOrNull { it.startsWith("$name=") }?.substringAfter('=')
            ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() }.orEmpty()

    /** GET /file?c=…: the page for the phone's browser. */
    fun page(path: String): Pair<Int, String> =
        if (check(param(path, "c"))) 200 to PAGE.replace("{{CODE}}", param(path, "c")) else 403 to PhoneFormExpired

    /** A POST /file request, read as far as its headers: the rest is the file. */
    class Upload(val path: String, val length: Long, val start: ByteArray)

    /** The upload in [raw] when it is one and its headers are all here; null otherwise. */
    fun upload(raw: ByteArray): Upload? {
        if (raw.size < 10 || String(raw, 0, 10, Charsets.ISO_8859_1).let { !it.startsWith("POST /file") }) return null
        var end = -1
        for (i in 0 until raw.size - 3) {
            if (raw[i] == '\r'.code.toByte() && raw[i + 1] == '\n'.code.toByte() && raw[i + 2] == '\r'.code.toByte() && raw[i + 3] == '\n'.code.toByte()) { end = i; break }
        }
        if (end < 0) return null
        val lines = String(raw, 0, end, Charsets.ISO_8859_1).split("\r\n")
        val path = lines.first().split(' ').getOrNull(1) ?: return null
        val length = lines.firstNotNullOfOrNull { l -> l.takeIf { it.startsWith("content-length:", true) }?.substringAfter(':')?.trim()?.toLongOrNull() } ?: -1L
        return Upload(path, length, raw.copyOfRange(end + 4, raw.size))
    }

    /** Writes the file to storage as it comes and hands it to the TV's screen; the answer for the phone. */
    fun receive(u: Upload, input: InputStream): ByteArray {
        if (!check(param(u.path, "c"))) return CastHttp.response(403, "{\"error\":\"Код устарел. Откройте QR на ТВ заново.\"}")
        if (u.length <= 0) return CastHttp.response(411, "{\"error\":\"Браузер не сообщил размер файла.\"}")
        val name = param(u.path, "n").substringAfterLast('/').replace(Regex("[^\\p{L}\\p{N} ._()-]"), "_").take(120).ifBlank { "file" }
        val folder = dir()
        clean(folder)
        if (folder.usableSpace - u.length < 200L shl 20) return CastHttp.response(507, "{\"error\":\"На ТВ не хватает места для этого файла.\"}")
        val target = File(folder, name)
        val part = File(folder, "$name.part")
        progress.value = FileProgress(name, 0, u.length)
        val ok = runCatching {
            part.outputStream().buffered(1 shl 20).use { out ->
                var done = u.start.size.toLong().coerceAtMost(u.length)
                out.write(u.start, 0, done.toInt())
                val chunk = ByteArray(1 shl 16)
                var shown = 0L
                while (done < u.length) {
                    val n = input.read(chunk, 0, minOf(chunk.size.toLong(), u.length - done).toInt())
                    if (n < 0) break
                    out.write(chunk, 0, n)
                    done += n
                    if (done - shown > 1 shl 20) { shown = done; progress.value = FileProgress(name, done, u.length) }
                }
                done == u.length
            }
        }.getOrDefault(false)
        if (!ok) {
            part.delete()
            progress.value = null
            return CastHttp.response(400, "{\"error\":\"Передача прервалась. Попробуйте ещё раз.\"}")
        }
        target.delete()
        part.renameTo(target)
        progress.value = null
        val handler = onFile
        if (handler != null) android.os.Handler(android.os.Looper.getMainLooper()).post { handler(target) }
        return CastHttp.response(200, "{\"name\":\"${name.replace("\"", "")}\"}")
    }

    /** Old videos go, so received films do not fill the TV; playlists stay, the TV reads its channels from them. */
    private fun clean(folder: File) {
        val week = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
        folder.listFiles()?.forEach { f ->
            val ext = f.extension.lowercase()
            if (f.name.endsWith(".part") || (ext != "m3u" && ext != "m3u8" && f.lastModified() < week)) f.delete()
        }
    }
}

private const val PhoneFormExpired = """<!doctype html><html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Couchy</title><style>body{font-family:system-ui,sans-serif;background:#111;color:#eee;padding:32px;text-align:center}</style></head>
<body><h2>Код устарел</h2><p>Откройте на ТВ «С телефона» ещё раз и наведите камеру на новый QR.</p></body></html>"""

private const val PAGE = """<!doctype html><html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Couchy: файл на ТВ</title>
<style>
body{font-family:system-ui,sans-serif;background:#111;color:#eee;margin:0;padding:28px 20px;text-align:center}
h2{margin:8px 0 6px}p{color:#aaa;margin:6px 0 22px}
label{display:block;background:#e8b04a;color:#111;font-weight:600;font-size:18px;padding:18px;border-radius:16px;cursor:pointer}
input{display:none}
.bar{height:10px;background:#333;border-radius:5px;overflow:hidden;margin:24px 0 10px;display:none}
.bar div{height:100%;width:0;background:#e8b04a;transition:width .3s}
#s{font-size:16px;min-height:24px}
</style></head><body>
<h2>Файл на ТВ</h2>
<p>Видео, плейлист .m3u или .torrent. Телефон должен быть в той же сети Wi‑Fi.</p>
<label>Выбрать файл<input id="f" type="file"></label>
<div class="bar" id="b"><div id="p"></div></div>
<div id="s"></div>
<script>
var f=document.getElementById('f'),b=document.getElementById('b'),p=document.getElementById('p'),s=document.getElementById('s');
function mb(x){return x>=1073741824?(x/1073741824).toFixed(1)+' ГБ':Math.max(1,Math.round(x/1048576))+' МБ'}
f.onchange=function(){
  var file=f.files[0];if(!file)return;
  var x=new XMLHttpRequest();
  x.open('POST','/file?c={{CODE}}&n='+encodeURIComponent(file.name));
  b.style.display='block';p.style.width='0';s.textContent='Отправляю '+file.name+'…';
  x.upload.onprogress=function(e){if(e.lengthComputable){p.style.width=(100*e.loaded/e.total)+'%';s.textContent=mb(e.loaded)+' из '+mb(e.total)}};
  x.onload=function(){
    if(x.status==200){p.style.width='100%';s.textContent='Готово: файл открыт на ТВ. Можно отправить ещё один.'}
    else{var m='Не получилось.';try{m=JSON.parse(x.responseText).error||m}catch(e){}s.textContent=m}
    f.value='';
  };
  x.onerror=function(){s.textContent='Связь с ТВ прервалась. Проверьте, что телефон в той же сети Wi‑Fi.';f.value=''};
  x.send(file);
};
</script></body></html>"""

/**
 * On the TV: a QR that opens the upload page on the phone. What comes is opened right away:
 * a video plays, a playlist becomes IPTV channels, a torrent opens its files.
 */
@Composable
fun PhoneFileDialog(onFile: (Uri) -> Unit, onDismiss: () -> Unit) {
    val app = LocalContext.current.applicationContext as CouchyApp
    val code = remember { PairLink.newCode() }
    val address = remember { app.cast.start(); app.cast.address() }
    DisposableEffect(code) {
        PhoneFile.init(app)
        PhoneFile.onFile = { f -> onFile(Uri.fromFile(f)) }
        PhoneFile.code = code
        onDispose {
            PhoneFile.code = null
            PhoneFile.onFile = null
        }
    }
    val progress by PhoneFile.progress.collectAsState()
    CouchySheet("Файл с телефона", onDismiss, scroll = false) {
        val qr = remember(address) {
            address?.let {
                val host = it.substringBefore(':')
                val port = it.substringAfter(':', "").toIntOrNull() ?: CastHttp.PORT
                qrBitmap("http://$host:$port/file?c=$code")
            }
        }
        if (qr == null) {
            Text("Устройство не в домашней сети. Подключите Wi‑Fi или кабель.", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(8.dp))
            return@CouchySheet
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
            Box(Modifier.background(Color.White, RoundedCornerShape(16.dp)).padding(12.dp)) {
                Image(qr.asImageBitmap(), "QR", filterQuality = FilterQuality.None, modifier = Modifier.size(200.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val p = progress
                if (p != null) {
                    Text("Принимаю ${p.name}", style = MaterialTheme.typography.titleMedium)
                    LinearProgressIndicator(progress = { if (p.total > 0) p.done.toFloat() / p.total else 0f })
                    Text("${p.done shr 20} из ${p.total shr 20} МБ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text("Наведите камеру телефона на код и откройте ссылку.", style = MaterialTheme.typography.bodyLarge)
                    Text("Выберите на телефоне видео, плейлист .m3u или торрент: он сразу откроется здесь.", style = MaterialTheme.typography.bodyLarge)
                    Text("Телефон должен быть в той же сети Wi‑Fi.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
