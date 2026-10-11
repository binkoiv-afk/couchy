package app.couchy.ui.torrent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.couchy.core.torrent.Sizes
import app.couchy.torrent.TorrentFileUi
import app.couchy.torrent.TorrentPhase
import app.couchy.torrent.TorrentState
import app.couchy.ui.components.LocalFormFactor
import app.couchy.ui.components.PrimaryButton
import app.couchy.ui.components.SecondaryButton
import app.couchy.ui.components.couchyClickable
import kotlinx.coroutines.delay

class TorrentActions(
    val onBack: () -> Unit,
    val onPlay: (TorrentFileUi) -> Unit,
    /** Long press: the file from the very beginning. */
    val onPlayFromStart: (TorrentFileUi) -> Unit,
    val onRetry: () -> Unit,
)

/**
 * An opened torrent: while the engine looks for peers it says so; then the files, with
 * series sorted as episodes, their progress, and one button to continue where you stopped.
 */
@Composable
fun TorrentContent(state: TorrentState, actions: TorrentActions, modifier: Modifier = Modifier) {
    val wide = LocalFormFactor.current.wide
    val side = if (wide) 48.dp else 18.dp
    Column(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = side, vertical = if (wide) 28.dp else 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SecondaryButton(null, Icons.AutoMirrored.Rounded.ArrowBack, onClick = actions.onBack)
            Column(Modifier.weight(1f)) {
                Text(
                    state.title,
                    style = if (wide) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val facts = listOfNotNull(
                    state.files.size.takeIf { it > 1 }?.let { "$it ${filesWord(it)}" },
                    state.size.takeIf { it > 0 }?.let { Sizes.bytes(it) },
                    if (state.remote) "через TorrServe в сети" else null,
                )
                if (facts.isNotEmpty()) {
                    Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        when (state.phase) {
            TorrentPhase.ERROR -> Problem(state.error ?: "Не удалось открыть торрент", actions)
            TorrentPhase.CONNECTING, TorrentPhase.METADATA -> Waiting(state)
            TorrentPhase.READY -> Files(state, actions, wide)
        }
    }
}

@Composable
private fun Waiting(state: TorrentState) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1_000); now = System.currentTimeMillis() } }
    val long = now - state.startedAt > 30_000
    Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        CircularProgressIndicator(Modifier.size(44.dp), color = MaterialTheme.colorScheme.primary)
        Text(
            if (state.phase == TorrentPhase.CONNECTING) "Запускаю торрент-движок…" else "Получаю список файлов…",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        if (state.phase == TorrentPhase.METADATA) {
            Text(peersLine(state), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (long) {
            Text(
                "Долго нет ответа от раздачи. Возможно, у неё сейчас нет сидов.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Problem(message: String, actions: TorrentActions) {
    Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(message, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.widthIn(max = 560.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton("Повторить", Icons.Rounded.Refresh, onClick = actions.onRetry)
            SecondaryButton("Закрыть", null, onClick = actions.onBack)
        }
    }
}

@Composable
private fun Files(state: TorrentState, actions: TorrentActions, wide: Boolean) {
    val first = state.resume ?: state.files.firstOrNull()
    val focus = remember { FocusRequester() }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        if (first != null) {
            val label = when {
                state.resume != null && state.files.size > 1 -> "Продолжить: ${first.label}"
                state.resume != null -> "Продолжить"
                else -> "Смотреть"
            }
            PrimaryButton(label, Icons.Rounded.PlayArrow, onClick = { actions.onPlay(first) }, modifier = Modifier.focusRequester(focus))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(8.dp).background(if (state.activePeers > 0) Color(0xFF3DD68C) else MaterialTheme.colorScheme.outline, CircleShape))
            Text(peersLine(state), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    if (state.files.isEmpty()) {
        Text("В этом торренте нет видео.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    LazyColumn(
        Modifier.fillMaxWidth().widthIn(max = if (wide) 980.dp else 10_000.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(state.files, key = { it.file.id }) { f ->
            FileRow(f, current = f == state.resume, actions)
        }
    }
}

@Composable
private fun FileRow(item: TorrentFileUi, current: Boolean, actions: TorrentActions) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .couchyClickable(shape, focusScale = 1.02f, onLongClick = { actions.onPlayFromStart(item) }) { actions.onPlay(item) }
            .background(if (current) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer, shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                item.label,
                style = MaterialTheme.typography.titleSmall,
                color = if (item.watched) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.label != app.couchy.core.torrent.TorrFiles.tidy(item.file.name)) {
                Text(item.file.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (item.progress > 0f && !item.watched) {
                Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(3.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(2.dp))) {
                    Box(Modifier.fillMaxWidth(item.progress).height(3.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)))
                }
            }
        }
        if (item.watched) Icon(Icons.Rounded.CheckCircle, "Просмотрено", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(Sizes.bytes(item.file.length), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(2.dp))
    }
}

private fun peersLine(state: TorrentState): String = when {
    state.peers == 0 -> "Ищу пиров…"
    else -> "${state.activePeers} из ${state.peers} пиров · ${Sizes.speed(state.speed)}"
}

private fun filesWord(n: Int): String {
    val m10 = n % 10
    val m100 = n % 100
    return when {
        m10 == 1 && m100 != 11 -> "файл"
        m10 in 2..4 && m100 !in 12..14 -> "файла"
        else -> "файлов"
    }
}
