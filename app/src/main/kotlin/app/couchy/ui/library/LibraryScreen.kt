package app.couchy.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.couchy.LibraryFilter
import app.couchy.LibraryState
import app.couchy.core.SortOrder
import app.couchy.data.MediaEntity
import app.couchy.ui.components.Chip
import app.couchy.ui.components.FormFactor
import app.couchy.ui.components.LocalFormFactor
import app.couchy.ui.components.MediaCard

class LibraryActions(
    val onPlay: (MediaEntity) -> Unit,
    val onMore: (MediaEntity) -> Unit,
    val onFilter: (LibraryFilter) -> Unit,
    val onSort: (SortOrder) -> Unit,
)

/** Everything you have opened, filtered and sorted, as a grid of cards. */
@Composable
fun LibraryContent(state: LibraryState, actions: LibraryActions, modifier: Modifier = Modifier) {
    val form = LocalFormFactor.current
    val side = if (form.wide) 32.dp else 18.dp
    Column(modifier.fillMaxSize()) {
        Text(
            "Медиатека",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(start = side, end = side, top = 20.dp, bottom = 12.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = side, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(LibraryFilter.entries) { f -> Chip(f.label, f == state.filter, onClick = { actions.onFilter(f) }) }
            item {
                // One chip that cycles through the orders: easy on a remote, no menus to dig through.
                val next = SortOrder.entries[(state.sort.ordinal + 1) % SortOrder.entries.size]
                Chip(state.sort.label, false, onClick = { actions.onSort(next) }, icon = Icons.AutoMirrored.Rounded.Sort)
            }
        }
        if (state.items.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(side), contentAlignment = Alignment.Center) {
                Text(
                    if (state.total == 0) "Здесь появится всё, что вы откроете в Couchy." else "Под этот фильтр ничего не подходит.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(
                    when (form) {
                        FormFactor.TV -> 250.dp
                        FormFactor.TABLET -> 220.dp
                        FormFactor.PHONE -> 160.dp
                    },
                ),
                contentPadding = PaddingValues(start = side, end = side, top = 14.dp, bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(if (form.isTv) 20.dp else 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(state.items, key = { it.uri }) { media ->
                    MediaCard(media, null, onClick = { actions.onPlay(media) }, onLongClick = { actions.onMore(media) })
                }
            }
        }
    }
}
