package app.couchy.ui.components

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.couchy.core.sources.PickEra
import app.couchy.core.sources.PickLength
import app.couchy.core.sources.PickMood
import app.couchy.core.sources.PickOptions
import app.couchy.core.sources.PickOrigin
import app.couchy.core.sources.PickScore
import app.couchy.core.sources.Title
import app.couchy.ui.search.PosterCard
import kotlinx.coroutines.launch

/**
 * «Что посмотреть?»: mood and time first, then years, country, how well liked and the person's
 * own taste; three titles out, spin again for three others. The choice is kept for next time.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PickDialog(
    pick: suspend (PickOptions, Set<String>) -> List<Title>,
    onTitle: (Title) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var o by remember { mutableStateOf(PickStore.load(context)) }
    var more by remember { mutableStateOf(PickStore.hasExtras(o)) }
    var result by remember { mutableStateOf<List<Title>?>(null) }
    var loading by remember { mutableStateOf(false) }
    val shown = remember { mutableSetOf<String>() }
    val scope = rememberCoroutineScope()
    fun set(next: PickOptions) {
        o = next
        shown.clear()
        PickStore.save(context, next)
    }
    fun spin() {
        loading = true
        scope.launch {
            val found = pick(o, shown)
            shown += found.map { it.key }
            result = found
            loading = false
        }
    }
    CouchySheet("Что посмотреть?", onDismiss) {
        Group("Настроение") {
            PickMood.entries.forEach { m -> Chip(m.label, m == o.mood, onClick = { set(o.copy(mood = m)) }) }
        }
        Group("Сколько есть времени") {
            PickLength.entries.forEach { l -> Chip(l.label, l == o.length, onClick = { set(o.copy(length = l)) }) }
        }
        if (more) {
            Group("Годы") {
                PickEra.entries.forEach { e -> Chip(e.label, e == o.era, onClick = { set(o.copy(era = e)) }) }
            }
            Group("Страна") {
                PickOrigin.entries.forEach { c -> Chip(c.label, c == o.origin, onClick = { set(o.copy(origin = c)) }) }
            }
            Group("Оценка зрителей") {
                PickScore.entries.forEach { s -> Chip(s.label, s == o.score, onClick = { set(o.copy(score = s)) }) }
            }
            Group("Ещё") {
                Chip("По моему вкусу", o.taste, onClick = { set(o.copy(taste = !o.taste)) })
                Chip("Малоизвестное", o.lesserKnown, onClick = { set(o.copy(lesserKnown = !o.lesserKnown)) })
                Chip("Из «Смотреть позже»", o.fromLater, onClick = { set(o.copy(fromLater = !o.fromLater)) })
            }
        } else {
            SheetRow("Ещё фильтры: годы, страна, оценка", onClick = { more = true }, icon = Icons.Rounded.Tune)
        }
        when {
            loading -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
            result?.isEmpty() == true -> Text(
                if (o.fromLater) "В «Смотреть позже» нет подходящего. Снимите «Из «Смотреть позже»» или другие фильтры."
                else "Ничего не нашлось. Ослабьте фильтры: другие годы, страна или оценка.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(8.dp),
            )
            result != null -> Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                result!!.forEach { t -> PosterCard(t, onClick = { onTitle(t) }, modifier = Modifier.weight(1f)) }
            }
        }
        PrimaryButton(
            if (result == null) "Подобрать" else "Ещё варианты",
            if (result == null) Icons.Rounded.AutoAwesome else Icons.Rounded.Casino,
            onClick = { if (!loading) spin() },
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

/** The last choice, so the next «Что посмотреть?» starts from it. */
private object PickStore {
    private fun prefs(context: Context) = context.getSharedPreferences("pick", Context.MODE_PRIVATE)

    fun load(context: Context): PickOptions {
        val p = prefs(context)
        val d = PickOptions()
        fun <E : Enum<E>> read(key: String, all: Array<E>, default: E): E = p.getString(key, null)?.let { n -> all.firstOrNull { it.name == n } } ?: default
        return PickOptions(
            mood = read("mood", PickMood.entries.toTypedArray(), d.mood),
            length = read("length", PickLength.entries.toTypedArray(), d.length),
            era = read("era", PickEra.entries.toTypedArray(), d.era),
            origin = read("origin", PickOrigin.entries.toTypedArray(), d.origin),
            score = read("score", PickScore.entries.toTypedArray(), d.score),
            lesserKnown = p.getBoolean("lesser", d.lesserKnown),
            fromLater = p.getBoolean("later", d.fromLater),
            taste = p.getBoolean("taste", d.taste),
        )
    }

    fun save(context: Context, o: PickOptions) {
        prefs(context).edit()
            .putString("mood", o.mood.name).putString("length", o.length.name).putString("era", o.era.name)
            .putString("origin", o.origin.name).putString("score", o.score.name)
            .putBoolean("lesser", o.lesserKnown).putBoolean("later", o.fromLater).putBoolean("taste", o.taste)
            .apply()
    }

    /** Extra filters already set: the section opens with them shown. */
    fun hasExtras(o: PickOptions): Boolean {
        val d = PickOptions()
        return o.era != d.era || o.origin != d.origin || o.score != d.score || o.lesserKnown || o.fromLater || o.taste != d.taste
    }
}
