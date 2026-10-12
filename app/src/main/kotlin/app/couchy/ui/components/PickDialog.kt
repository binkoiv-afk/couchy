package app.couchy.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.couchy.core.sources.PickLength
import app.couchy.core.sources.PickMood
import app.couchy.core.sources.Title
import app.couchy.ui.search.PosterCard
import kotlinx.coroutines.launch

/** «Что посмотреть?»: mood and time in, three titles out; spin again for three others. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PickDialog(
    pick: suspend (PickMood, PickLength, Set<String>) -> List<Title>,
    onTitle: (Title) -> Unit,
    onDismiss: () -> Unit,
) {
    var mood by rememberSaveable { mutableStateOf(PickMood.ANY) }
    var length by rememberSaveable { mutableStateOf(PickLength.MOVIE) }
    var result by remember { mutableStateOf<List<Title>?>(null) }
    var loading by remember { mutableStateOf(false) }
    val shown = remember { mutableSetOf<String>() }
    val scope = rememberCoroutineScope()
    fun spin() {
        loading = true
        scope.launch {
            val found = pick(mood, length, shown)
            shown += found.map { it.key }
            result = found
            loading = false
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            PrimaryButton(if (result == null) "Подобрать" else "Ещё варианты", if (result == null) Icons.Rounded.AutoAwesome else Icons.Rounded.Casino, onClick = { if (!loading) spin() })
        },
        dismissButton = { SecondaryButton("Закрыть", null, onClick = onDismiss) },
        title = { Text("Что посмотреть?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Настроение", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PickMood.entries.forEach { m -> Chip(m.label, m == mood, onClick = { mood = m; shown.clear() }) }
                }
                Text("Сколько есть времени", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PickLength.entries.forEach { l -> Chip(l.label, l == length, onClick = { length = l; shown.clear() }) }
                }
                when {
                    loading -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                    result?.isEmpty() == true -> Text(
                        "Ничего не нашлось. Попробуйте другое настроение.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    result != null -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        result!!.forEach { t ->
                            PosterCard(t, onClick = { onTitle(t) }, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        },
    )
}
