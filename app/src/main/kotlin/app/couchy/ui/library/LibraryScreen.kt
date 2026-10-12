package app.couchy.ui.library

import androidx.compose.runtime.setValue

import androidx.compose.runtime.getValue

import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Folder

import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Delete

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import app.couchy.ui.components.couchyClickable
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
    val onSettings: (() -> Unit)? = null,
    val onTitle: (app.couchy.core.sources.Title) -> Unit = {},
    val onSaved: (app.couchy.download.Saved) -> Unit = {},
    val onRemoveSaved: (app.couchy.download.Saved) -> Unit = {},
    /** Deletes one of the person's folders; the titles in it stay everywhere else. */
    val onRemoveFolder: (id: String) -> Unit = {},
)

/** Everything you have opened, filtered and sorted, as a grid of cards. */
@Composable
fun LibraryContent(
    state: LibraryState,
    actions: LibraryActions,
    modifier: Modifier = Modifier,
    downloads: List<app.couchy.download.Saved> = emptyList(),
    /** Called every second while the downloads tab is open, to read progress again. */
    onDownloads: () -> Unit = {},
) {
    val form = LocalFormFactor.current
    val side = if (form.wide) 32.dp else 18.dp
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = side, end = side, top = 20.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Моё", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
            // Phones keep settings here; TV and tablets have them in the side menu.
            if (!form.wide) actions.onSettings?.let { open ->
                app.couchy.ui.components.SecondaryButton("Настройки", Icons.Rounded.Settings, onClick = open)
            }
        }
        // Null: everything opened; otherwise one of the film lists.
        var list by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf<String?>(null) }
        LazyRow(
            contentPadding = PaddingValues(horizontal = side, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Chip("Смотреть позже · ${state.saved.later.size}", list == "later", onClick = { list = if (list == "later") null else "later" }, icon = Icons.Rounded.Bookmark)
            }
            item {
                Chip("Любимое · ${state.saved.loved.size}", list == "loved", onClick = { list = if (list == "loved") null else "loved" }, icon = Icons.Rounded.Favorite)
            }
            item {
                Chip("Загрузки · ${downloads.size}", list == "saved", onClick = { list = if (list == "saved") null else "saved" }, icon = Icons.Rounded.Download)
            }
            item {
                Chip("Просмотрено · ${state.saved.seen.size}", list == "seen", onClick = { list = if (list == "seen") null else "seen" }, icon = Icons.Rounded.CheckCircle)
            }
            item {
                Chip("Мои оценки · ${state.saved.rated.size}", list == "rated", onClick = { list = if (list == "rated") null else "rated" }, icon = Icons.Rounded.Star)
            }
            items(state.saved.folders, key = { "folder:" + it.id }) { f ->
                val id = "folder:" + f.id
                Chip("${f.name} · ${f.titles.size}", list == id, onClick = { list = if (list == id) null else id }, icon = Icons.Rounded.Folder)
            }
            items(LibraryFilter.entries) { f -> Chip(f.label, list == null && f == state.filter, onClick = { list = null; actions.onFilter(f) }) }
            item {
                // One chip that cycles through the orders: easy on a remote, no menus to dig through.
                val next = SortOrder.entries[(state.sort.ordinal + 1) % SortOrder.entries.size]
                Chip(state.sort.label, false, onClick = { actions.onSort(next) }, icon = Icons.AutoMirrored.Rounded.Sort)
            }
        }
        if (list == "saved") {
            androidx.compose.runtime.LaunchedEffect(Unit) {
                while (true) { onDownloads(); kotlinx.coroutines.delay(1_000) }
            }
            SavedList(downloads, actions, side)
            return@Column
        }
        val titles = when (list) {
            "later" -> state.saved.later
            "loved" -> state.saved.loved
            "seen" -> state.saved.seen
            "rated" -> state.saved.rated.sortedByDescending { state.saved.scores[it.key] ?: 0 }
            else -> state.saved.folders.firstOrNull { "folder:" + it.id == list }?.titles
        }
        val folder = state.saved.folders.firstOrNull { "folder:" + it.id == list }
        if (folder != null) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = side, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(folder.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                var confirm by androidx.compose.runtime.remember(folder.id) { androidx.compose.runtime.mutableStateOf(false) }
                app.couchy.ui.components.SecondaryButton(if (confirm) "Точно удалить?" else "Удалить папку", null, onClick = {
                    if (confirm) { actions.onRemoveFolder(folder.id); list = null } else confirm = true
                })
            }
        }
        if (titles != null) {
            if (titles.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(side), contentAlignment = Alignment.Center) {
                    Text(
                        if (list == "seen" || list == "rated") "Отмечайте на странице фильма «Просмотрено» и ставьте оценку: по ним Couchy подбирает «Для вас»."
                        else if (list == "later") "Откройте фильм и нажмите «Смотреть позже»: он появится здесь и на главной."
                        else if (folder != null) "Пока пусто. На странице фильма нажмите «В папку» и выберите эту."
                        else "Откройте фильм и нажмите «Любимое»: так собирается ваша подборка.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(
                        when (form) {
                            FormFactor.TV -> 160.dp
                            FormFactor.TABLET -> 140.dp
                            FormFactor.PHONE -> 104.dp
                        },
                    ),
                    contentPadding = PaddingValues(start = side, end = side, top = 14.dp, bottom = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(if (form.isTv) 18.dp else 10.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(titles, key = { it.key }) { t -> app.couchy.ui.search.PosterCard(t, onClick = { actions.onTitle(t) }) }
                }
            }
        } else if (state.items.isEmpty()) {
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

/** Videos saved on the device: progress while they download, play and delete once they are there. */
@Composable
private fun SavedList(items: List<app.couchy.download.Saved>, actions: LibraryActions, side: androidx.compose.ui.unit.Dp) {
    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(side), contentAlignment = Alignment.Center) {
            Text(
                "Здесь будут скачанные фильмы и видео. В плеере нажмите «Скачать», и их можно будет смотреть без интернета.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    androidx.compose.foundation.lazy.LazyColumn(
        contentPadding = PaddingValues(start = side, end = side, top = 14.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(items, key = { it.playback.key }) { s ->
            Row(
                Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainer, androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                    .couchyClickable(androidx.compose.foundation.shape.RoundedCornerShape(14.dp), focusScale = 1.02f) { actions.onSaved(s) }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(s.playback.title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                    val mb = s.bytes / 1_048_576
                    Text(
                        when {
                            s.done -> "Скачано · $mb МБ · можно смотреть без интернета"
                            s.failed -> "Не скачалось. Удалите и попробуйте снова, ссылка могла устареть"
                            else -> "Скачивается · ${s.percent.toInt()}% · $mb МБ"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (s.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!s.done && !s.failed) {
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { s.percent / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                Box(
                    Modifier.size(44.dp).couchyClickable(androidx.compose.foundation.shape.CircleShape) { actions.onRemoveSaved(s) },
                    contentAlignment = Alignment.Center,
                ) { androidx.compose.material3.Icon(Icons.Rounded.Delete, "Удалить", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}
