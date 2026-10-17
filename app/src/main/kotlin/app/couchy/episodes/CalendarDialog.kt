package app.couchy.episodes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.couchy.CouchyApp
import app.couchy.core.sources.CalendarDay
import app.couchy.core.sources.Cinemeta
import app.couchy.core.sources.EpisodeCalendar
import app.couchy.core.sources.Episodes
import app.couchy.core.sources.Title
import app.couchy.core.sources.TitleKind
import app.couchy.sources.Web
import app.couchy.ui.components.CouchySheet
import app.couchy.ui.components.SheetHeader
import app.couchy.ui.components.SheetRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/** The episodes of the followed series, by day: what came out this week and what is coming. */
object Calendar {
    suspend fun load(app: CouchyApp): List<CalendarDay<Title>> = withContext(Dispatchers.IO) {
        val web = Web(app)
        val series = coroutineScope {
            NewEpisodes.followed(app.marks).map { t ->
                async {
                    runCatching { Episodes.parseCinemeta(web.get(Cinemeta.metaUrl(t.imdbId!!, TitleKind.SERIES))) }
                        .getOrNull()?.let { Triple(t, t.name, it) }
                }
            }.awaitAll().filterNotNull()
        }
        EpisodeCalendar.build(series, System.currentTimeMillis())
    }
}

@Composable
fun CalendarDialog(onTitle: (Title) -> Unit, onDismiss: () -> Unit) {
    val app = LocalContext.current.applicationContext as CouchyApp
    var days by remember { mutableStateOf<List<CalendarDay<Title>>?>(null) }
    val following = remember { NewEpisodes.followed(app.marks).isNotEmpty() }
    LaunchedEffect(Unit) { if (following) days = runCatching { Calendar.load(app) }.getOrDefault(emptyList()) }
    CouchySheet("Календарь серий", onDismiss) {
        val context = LocalContext.current
        var notify by remember { mutableStateOf(NewEpisodes.canNotify(context)) }
        var background by remember { mutableStateOf(NewEpisodes.backgroundAllowed(context)) }
        val ask = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
        ) { notify = it || NewEpisodes.canNotify(context) }
        if (following && !notify) {
            SheetRow("Уведомления выключены: включить", onClick = {
                if (android.os.Build.VERSION.SDK_INT >= 33) ask.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                else runCatching {
                    context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName))
                }
            }, icon = Icons.Rounded.NotificationsOff, selected = true)
        }
        if (following && !background) {
            // Honor and Huawei stop apps in the background, and the check for new episodes with them.
            SheetRow("Разрешить проверку в фоне, иначе уведомления не придут", onClick = {
                runCatching {
                    context.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:" + context.packageName)),
                    )
                }
                background = true
            }, icon = Icons.Rounded.BatteryAlert, selected = true)
        }
        val now = System.currentTimeMillis()
        val list = days
        when {
            !following -> Hint("Здесь появятся серии сериалов, за которыми вы следите. Откройте сериал и нажмите «Следить».")
            list == null -> Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                CircularProgressIndicator(Modifier.size(22.dp), color = MaterialTheme.colorScheme.primary)
                Text("Смотрю расписание…", style = MaterialTheme.typography.bodyLarge)
            }
            list.isEmpty() -> Hint("За последнюю неделю и на два месяца вперёд новых серий нет.")
            else -> list.forEach { day ->
                SheetHeader(day.label)
                day.entries.forEach { e ->
                    val out = e.episode.releasedMs <= now
                    SheetRow(
                        "${e.name} · ${e.episode.season} сезон, ${e.episode.episode} серия",
                        onClick = { onDismiss(); onTitle(e.series) },
                        icon = if (out) Icons.Rounded.NewReleases else Icons.Rounded.Schedule,
                        tag = EpisodeCalendar.tag(e.episode, now),
                        selected = out && day.label == "Сегодня",
                    )
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
}
