package app.couchy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.couchy.core.Stats
import app.couchy.core.StatsPeriod
import app.couchy.core.StatsSummary

/** «Итоги»: hours watched, top titles, favourite genres and actors for a month, a year or all time. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StatsDialog(summary: (StatsPeriod) -> StatsSummary, onDismiss: () -> Unit) {
    var period by rememberSaveable { mutableStateOf(StatsPeriod.MONTH) }
    val s = remember(period) { summary(period) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { SecondaryButton("Закрыть", null, onClick = onDismiss) },
        title = { Text("Итоги") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatsPeriod.entries.forEach { p -> Chip(p.label, p == period, onClick = { period = p }) }
                }
                if (s.isEmpty) {
                    Text(
                        "Здесь пока пусто. Посмотрите что-нибудь, и Couchy посчитает часы, любимые жанры и актёров.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    return@Column
                }
                Column {
                    Text(Stats.duration(s.ms), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${s.count} ${plural(s.count, "фильм или сериал", "фильма или сериала", "фильмов и сериалов")} · ${s.activeDays} ${plural(s.activeDays, "день", "дня", "дней")} у экрана",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Section("Больше всего") {
                    val most = s.titles.firstOrNull()?.second ?: 1L
                    s.titles.forEach { (name, ms) -> Bar(name, Stats.duration(ms), ms.toFloat() / most) }
                }
                if (s.genres.isNotEmpty()) Section("Любимые жанры") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        s.genres.forEachIndexed { i, (g, _) -> Chip(g, i == 0, onClick = {}) }
                    }
                }
                if (s.actors.isNotEmpty()) Section("Любимые актёры") {
                    s.actors.forEach { (a, ms) -> Text("$a · ${Stats.duration(ms)}", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        },
    )
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        content()
    }
}

@Composable
private fun Bar(name: String, value: String, fraction: Float) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
        }
        val shape = RoundedCornerShape(3.dp)
        Box(Modifier.fillMaxWidth().height(4.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, shape)) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0.02f, 1f)).height(4.dp).background(MaterialTheme.colorScheme.primary, shape))
        }
    }
}

private fun plural(n: Int, one: String, few: String, many: String): String {
    val m10 = n % 10
    val m100 = n % 100
    return when {
        m10 == 1 && m100 != 11 -> one
        m10 in 2..4 && m100 !in 12..14 -> few
        else -> many
    }
}
