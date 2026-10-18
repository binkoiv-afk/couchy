package app.couchy.ui.open

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import app.couchy.ui.components.SheetRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A place files can be read from: the device's own memory or a flash drive. */
private data class Root(val name: String, val dir: File, val usb: Boolean)

private val VIDEO = setOf("mp4", "mkv", "avi", "mov", "webm", "ts", "m2ts", "mts", "mpg", "mpeg", "wmv", "flv", "3gp", "m4v", "vob")
private val AUDIO = setOf("mp3", "flac", "m4a", "aac", "ogg", "wav", "opus")
private val PLAYLIST = setOf("m3u", "m3u8")
private const val TORRENT = "torrent"

/**
 * Couchy's own file browser: a flash drive or the device's memory, folder by folder, with the
 * remote. TV boxes and projectors often have no system file picker at all, so this one is used
 * there. Videos and music open in the player, .m3u becomes an IPTV playlist, .torrent a torrent.
 */
@Composable
fun FilesDialog(onOpen: (String) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val context = LocalContext.current
        var allowed by remember { mutableStateOf(canRead(context)) }
        val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { allowed = canRead(context) }
        LaunchedEffect(Unit) { if (!allowed) runCatching { ask.launch(permissions()) } }
        var roots by remember { mutableStateOf<List<Root>>(emptyList()) }
        var dir by remember { mutableStateOf<File?>(null) }
        var entries by remember { mutableStateOf<List<File>?>(null) }
        LaunchedEffect(allowed) { roots = withContext(Dispatchers.IO) { roots(context) } }
        LaunchedEffect(dir, allowed) {
            val d = dir ?: return@LaunchedEffect
            entries = null
            entries = withContext(Dispatchers.IO) { list(d) }
        }
        val root = roots.firstOrNull { r -> dir?.path?.startsWith(r.dir.path) == true }
        fun up() {
            val d = dir ?: return onDismiss()
            dir = if (root == null || d.path == root.dir.path) null else d.parentFile
        }
        BackHandler { up() }
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { up() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") }
                Column(Modifier.weight(1f)) {
                    Text(dir?.name?.takeIf { root == null || dir?.path != root.dir.path } ?: root?.name ?: "Флешка и память", style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    dir?.let { d ->
                        Text(
                            (root?.name ?: "") + d.path.removePrefix(root?.dir?.path ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Закрыть") }
            }
            val first = remember { FocusRequester() }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!allowed) item("allow") {
                    SheetRow("Разрешить доступ к файлам", onClick = { runCatching { ask.launch(permissions()) } }, icon = Icons.Rounded.Folder, selected = true)
                }
                val d = dir
                if (d == null) {
                    if (roots.isEmpty()) item("none") {
                        Text("Флешка не найдена. Вставьте её и подождите несколько секунд.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
                    }
                    items(roots, key = { it.dir.path }) { r ->
                        SheetRow(
                            r.name, onClick = { dir = r.dir },
                            modifier = if (r == roots.first()) Modifier.focusRequester(first) else Modifier,
                            icon = if (r.usb) Icons.Rounded.Usb else Icons.Rounded.PhoneAndroid,
                            tag = r.dir.path,
                        )
                    }
                } else {
                    val list = entries
                    if (list == null) item("loading") { Text("Открываю…", modifier = Modifier.padding(8.dp)) }
                    else if (list.isEmpty()) item("empty") {
                        Text(
                            "Здесь нет видео, музыки, плейлистов и торрентов.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                    else items(list, key = { it.path }) { f ->
                        val ext = f.extension.lowercase()
                        SheetRow(
                            f.name,
                            onClick = { if (f.isDirectory) dir = f else onOpen(Uri.fromFile(f).toString()) },
                            modifier = if (f == list.first()) Modifier.focusRequester(first) else Modifier,
                            icon = when {
                                f.isDirectory -> Icons.Rounded.Folder
                                ext in AUDIO -> Icons.Rounded.MusicNote
                                ext in PLAYLIST -> Icons.Rounded.LiveTv
                                ext == TORRENT -> Icons.Rounded.Download
                                else -> Icons.Rounded.Movie
                            },
                            tag = when {
                                f.isDirectory -> null
                                ext in PLAYLIST -> "плейлист ТВ"
                                else -> size(f.length())
                            },
                        )
                    }
                }
            }
            // The remote starts on the first row of every folder.
            LaunchedEffect(dir, entries, roots) { runCatching { first.requestFocus() } }
        }
    }
}

private fun size(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(java.util.Locale("ru"), "%.1f ГБ", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "${bytes shr 20} МБ"
    else -> "${(bytes shr 10).coerceAtLeast(1)} КБ"
}

private fun permissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
    else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

private fun canRead(context: Context): Boolean = permissions().any {
    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
}

/** Folders first, then the files Couchy can open, by name; hidden ones left out. */
private fun list(dir: File): List<File> {
    val all = dir.listFiles()?.filter { !it.name.startsWith(".") } ?: return emptyList()
    val folders = all.filter { it.isDirectory }.sortedBy { it.name.lowercase() }
    val files = all.filter { it.isFile && it.extension.lowercase().let { e -> e in VIDEO || e in AUDIO || e in PLAYLIST || e == TORRENT } }
        .sortedBy { it.name.lowercase() }
    return folders + files
}

/** The device's memory and every flash drive or card plugged in. */
private fun roots(context: Context): List<Root> {
    val out = linkedMapOf<String, Root>()
    val internal = runCatching { Environment.getExternalStorageDirectory() }.getOrNull()
    val manager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
    if (manager != null && Build.VERSION.SDK_INT >= 24) {
        manager.storageVolumes.forEach { v ->
            val dir = if (Build.VERSION.SDK_INT >= 30) v.directory else runCatching { v.javaClass.getMethod("getPath").invoke(v) as? String }.getOrNull()?.let(::File)
            if (dir != null && dir.canRead()) out[dir.path] = Root(v.getDescription(context) ?: dir.name, dir, v.isRemovable)
        }
    }
    // Boxes that list their USB drives only under /storage or /mnt.
    listOf(File("/storage"), File("/mnt"), File("/mnt/usb"), File("/mnt/media_rw")).forEach { base ->
        base.listFiles()?.forEach { d ->
            if (d.isDirectory && d.canRead() && d.name !in setOf("self", "emulated", "sdcard", "runtime", "secure", "asec", "obb", "user", "expand", "appfuse", "vendor", "product", "installer", "pass_through", "media_rw", "usb") &&
                out.keys.none { it == d.path } && (d.listFiles()?.isNotEmpty() == true)
            ) out[d.path] = Root("Флешка ${d.name}", d, true)
        }
    }
    if (internal != null && internal.canRead() && out.keys.none { it == internal.path }) out[internal.path] = Root("Память устройства", internal, false)
    // Flash drives first: that is what this is opened for.
    return out.values.sortedBy { if (it.usb) 0 else 1 }
}
