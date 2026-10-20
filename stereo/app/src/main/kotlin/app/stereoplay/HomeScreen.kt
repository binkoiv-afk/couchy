package app.stereoplay

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun HomeScreen(recent: List<Recent>, onOpen: (Uri, String?) -> Unit) {
    val context = LocalContext.current
    var askLink by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            onOpen(uri, null)
        }
    }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0B1020), Color(0xFF120B1C))))
            .safeDrawingPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        Text("Объём 3D", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(Modifier.height(6.dp))
        Text(
            "Обычное видео превращается в стереопару прямо при просмотре",
            color = Color.White.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    try {
                        pick.launch(arrayOf("video/*"))
                    } catch (e: ActivityNotFoundException) {
                        Toast.makeText(context, "На устройстве нет выбора файлов, откройте видео из файлового менеджера", Toast.LENGTH_LONG).show()
                    }
                },
                modifier = Modifier.focusRequester(first),
            ) {
                Icon(Icons.Filled.Folder, null)
                Spacer(Modifier.width(8.dp))
                Text("Открыть файл")
            }
            OutlinedButton(onClick = { askLink = true }) {
                Icon(Icons.Filled.Link, null)
                Spacer(Modifier.width(8.dp))
                Text("Открыть ссылку")
            }
        }
        Spacer(Modifier.height(28.dp))
        if (recent.isNotEmpty()) {
            Text("Недавние", color = Color.White.copy(alpha = 0.6f), modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                items(recent, key = { it.uri.toString() }) { r ->
                    TextButton(onClick = { onOpen(r.uri, r.title) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.PlayArrow, null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            r.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        } else {
            Text(
                "Видео можно открыть и из другого приложения: «Открыть с помощью» → Объём 3D.",
                color = Color.White.copy(alpha = 0.5f),
            )
        }
    }

    if (askLink) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { askLink = false },
            title = { Text("Ссылка на видео") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    placeholder = { Text("https://…") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val t = text.trim()
                        if (t.isNotEmpty()) {
                            askLink = false
                            onOpen(Uri.parse(t), null)
                        }
                    },
                ) { Text("Смотреть") }
            },
            dismissButton = { TextButton(onClick = { askLink = false }) { Text("Отмена") } },
        )
    }
}
