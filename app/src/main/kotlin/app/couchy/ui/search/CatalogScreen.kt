package app.couchy.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.couchy.core.sources.CatalogFilter
import app.couchy.core.sources.CatalogSort
import app.couchy.core.sources.CatalogType
import app.couchy.core.sources.RezkaCatalog
import app.couchy.core.sources.Title
import app.couchy.sources.ListQuery
import app.couchy.sources.ListingUi
import app.couchy.ui.components.Chip
import app.couchy.ui.components.FormFactor
import app.couchy.ui.components.LocalFormFactor
import app.couchy.ui.components.SecondaryButton
import app.couchy.ui.components.couchyClickable

class ListingActions(
    val onBack: () -> Unit,
    val onTitle: (Title) -> Unit,
    val onMore: () -> Unit,
    /** The catalogue with other filters. */
    val onFilter: (CatalogFilter) -> Unit,
)

/** A person's films or the catalogue: filters on top, then posters that keep loading as you scroll. */
@Composable
fun ListingContent(state: ListingUi, actions: ListingActions, modifier: Modifier = Modifier) {
    val form = LocalFormFactor.current
    val side = if (form.wide) 40.dp else 18.dp
    val cell = when (form) {
        FormFactor.TV -> 160.dp
        FormFactor.TABLET -> 140.dp
        FormFactor.PHONE -> 104.dp
    }
    val grid = rememberLazyGridState()
    val nearEnd by remember {
        derivedStateOf {
            val info = grid.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 8
        }
    }
    LaunchedEffect(nearEnd, state.titles.size, state.loading) {
        if (nearEnd && !state.loading && !state.end && state.error == null) actions.onMore()
    }
    LazyVerticalGrid(
        GridCells.Adaptive(cell),
        modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        state = grid,
        contentPadding = PaddingValues(start = side, end = side, bottom = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(if (form.isTv) 18.dp else 10.dp),
        verticalArrangement = Arrangement.spacedBy(if (form.isTv) 20.dp else 14.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "head") {
            Column(Modifier.statusBarsPadding().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        Modifier.size(44.dp).couchyClickable(CircleShape, onClick = actions.onBack)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад", modifier = Modifier.size(22.dp)) }
                    Column {
                        Text(
                            if (state.query is ListQuery.Person) state.heading else "Каталог",
                            style = MaterialTheme.typography.headlineMedium,
                            maxLines = 2,
                        )
                        Text(
                            if (state.query is ListQuery.Person) "Фильмы и сериалы с участием" else state.heading,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                (state.query as? ListQuery.Catalog)?.let { Filters(it.filter, actions.onFilter) }
            }
        }
        items(state.titles, key = { it.key }) { t -> PosterCard(t, onClick = { actions.onTitle(t) }) }
        item(span = { GridItemSpan(maxLineSpan) }, key = "tail") {
            Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                when {
                    state.loading -> CircularProgressIndicator(Modifier.size(28.dp), color = MaterialTheme.colorScheme.primary)
                    state.error != null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(state.error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SecondaryButton("Ещё раз", Icons.Rounded.Refresh, onClick = actions.onMore)
                    }
                    state.titles.isEmpty() -> Text(
                        "Здесь пока пусто. Попробуйте другой жанр, год или страну.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Kind and order as chips, genre, year and country as pickers. */
@Composable
private fun Filters(f: CatalogFilter, onFilter: (CatalogFilter) -> Unit) {
    var picking by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(CatalogType.entries.size) { i ->
                val t = CatalogType.entries[i]
                Chip(t.label, t == f.type, onClick = { onFilter(f.copy(type = t)) })
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(CatalogSort.entries.size) { i ->
                val s = CatalogSort.entries[i]
                // Year lists exist only among the best ones, so a year picks that order.
                val on = if (f.year != null && f.country == null) s == CatalogSort.BEST else s == f.sort
                Chip(s.label, on, onClick = { onFilter(f.copy(sort = s, year = if (s == CatalogSort.BEST || f.country != null) f.year else null)) })
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Picker("Жанр", RezkaCatalog.genreName(f.genre) ?: f.genreLabel?.takeIf { f.genre != null }) { picking = "genre" } }
            item { Picker("Год", f.year?.toString()) { picking = "year" } }
            item { Picker("Страна", f.country) { picking = "country" } }
        }
    }
    when (picking) {
        "genre" -> Choose("Жанр", listOf<Pair<String?, String>>(null to "Любой") + app.couchy.core.sources.Genres.all.map { it.slug to it.name }, f.genre, { picking = null }) {
            onFilter(f.copy(genre = it, genreLabel = null))
        }
        "year" -> Choose("Год", listOf(null to "Любой") + years.map { "$it" to "$it" }, f.year?.toString(), { picking = null }) {
            onFilter(f.copy(year = it?.toInt()))
        }
        "country" -> Choose("Страна", listOf(null to "Любая") + app.couchy.core.sources.Countries.codes.keys.map { it to it }, f.country, { picking = null }) {
            onFilter(f.copy(country = it))
        }
    }
}

private val years: List<Int> = (java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) downTo 1970).toList()

/** A filter with its current value; opens the list of choices. */
@Composable
private fun Picker(label: String, value: String?, onClick: () -> Unit) {
    val shape = RoundedCornerShape(999.dp)
    val set = value != null
    Row(
        Modifier
            .heightIn(min = 36.dp)
            .couchyClickable(shape, onClick = onClick)
            .background(if (set) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceContainerHigh, shape)
            .padding(start = 14.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            if (set) "$label: $value" else label,
            style = MaterialTheme.typography.labelLarge,
            color = if (set) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Icon(Icons.Rounded.KeyboardArrowDown, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Choose(title: String, options: List<Pair<String?, String>>, current: String?, onDismiss: () -> Unit, onPick: (String?) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            FlowRow(
                Modifier.verticalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                options.forEach { (value, label) -> Chip(label, value == current, onClick = { onDismiss(); onPick(value) }) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    )
}
