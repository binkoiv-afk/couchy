package app.couchy.ui.youtube

import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.MusicNote
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Subscriptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.couchy.core.youtube.YtChannel
import app.couchy.core.youtube.YtVideo
import app.couchy.ui.components.Artwork
import app.couchy.ui.components.Chip
import app.couchy.ui.components.FormFactor
import app.couchy.ui.components.LocalFormFactor
import app.couchy.ui.components.PrimaryButton
import app.couchy.ui.components.SecondaryButton
import app.couchy.ui.components.couchyClickable
import app.couchy.youtube.YouTubeUi
import app.couchy.youtube.YtListUi
import app.couchy.youtube.YtShelf
import coil.compose.AsyncImage

class YouTubeActions(
    val onQuery: (String) -> Unit,
    val onSearch: (String) -> Unit,
    val onShelf: (YtShelf) -> Unit,
    val onVideo: (YtVideo) -> Unit,
    val onChannel: (url: String, name: String) -> Unit,
    val onCloseChannel: () -> Unit,
    val onSubscribe: (YtChannel) -> Unit,
    val onMore: () -> Unit,
    val onRetry: () -> Unit,
    val onClearHistory: () -> Unit,
    val onLater: (YtVideo) -> Unit,
    val onLoved: (YtVideo) -> Unit,
    /** Music: a song with the songs around it as the queue. */
    val onSong: (List<YtVideo>, Int) -> Unit = { _, _ -> },
    val onAlbum: (app.couchy.youtube.YtAlbum, shuffle: Boolean) -> Unit = { _, _ -> },
    val onSaveAlbum: (app.couchy.youtube.YtAlbum) -> Unit = {},
    /** After Google's sign-in window: load the person's own music. */
    val onMusicSignedIn: () -> Unit = {},
    val onMusicSignOut: () -> Unit = {},
)

/**
 * YouTube without ads: search, YouTube's own sections and the channels followed, all on this
 * device without an account. Videos open in Couchy's player.
 */
@Composable
fun YouTubeContent(state: YouTubeUi, actions: YouTubeActions, modifier: Modifier = Modifier) {
    val channel = state.channel
    if (channel != null) {
        BackHandler(onBack = actions.onCloseChannel)
        val subscribed = state.subscriptions.any { it.url == channel.channel.url }
        VideoGrid(channel.list, state, actions, modifier, onMore = {}) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "channel") { ChannelHeader(channel.channel, subscribed, actions) }
        }
        return
    }
    if (state.shelf is YtShelf.Music) {
        MusicList(state, actions, modifier)
        return
    }
    VideoGrid(state.list, state, actions, modifier, onMore = actions.onMore) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "head") { Header(state, actions) }
        if (state.shelf == YtShelf.Subscriptions && state.subscriptions.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "subs") { ChannelRow(state.subscriptions, actions) }
        }
        if (state.list.channels.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "found") { ChannelRow(state.list.channels, actions) }
        }
    }
}

@Composable
private fun VideoGrid(
    list: YtListUi,
    state: YouTubeUi,
    actions: YouTubeActions,
    modifier: Modifier,
    onMore: () -> Unit,
    head: androidx.compose.foundation.lazy.grid.LazyGridScope.() -> Unit,
) {
    val form = LocalFormFactor.current
    val side = if (form.wide) 32.dp else 16.dp
    val grid = rememberLazyGridState()
    val nearEnd by remember {
        derivedStateOf {
            val info = grid.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 6
        }
    }
    LaunchedEffect(nearEnd, list.videos.size, list.loading) {
        if (nearEnd && !list.loading && !list.end && list.error == null) onMore()
    }
    LazyVerticalGrid(
        GridCells.Adaptive(
            when (form) {
                FormFactor.TV -> 250.dp
                FormFactor.TABLET -> 260.dp
                FormFactor.PHONE -> 320.dp
            },
        ),
        modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        state = grid,
        contentPadding = PaddingValues(start = side, end = side, top = 12.dp, bottom = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        head()
        items(list.videos, key = { it.id }) { v -> VideoCard(v, v.id in state.later, v.id in state.loved, actions) }
        item(span = { GridItemSpan(maxLineSpan) }, key = "tail") {
            Box(Modifier.fillMaxWidth().padding(vertical = 20.dp), contentAlignment = Alignment.Center) {
                when {
                    list.loading -> CircularProgressIndicator(Modifier.size(28.dp), color = MaterialTheme.colorScheme.primary)
                    list.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(list.error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SecondaryButton("Ещё раз", Icons.Rounded.Refresh, onClick = actions.onRetry)
                    }
                    list.videos.isEmpty() -> Text(
                        if (state.shelf is YtShelf.Saved && state.channel == null) "Пока пусто. Нажмите закладку или сердечко под видео, и оно появится здесь." else "Здесь пока пусто.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Header(state: YouTubeUi, actions: YouTubeActions) {
    val keyboard = LocalSoftwareKeyboardController.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(34.dp, 24.dp).background(Color(0xFFFF0033), RoundedCornerShape(7.dp)), contentAlignment = Alignment.Center) {
                Text("▶", color = Color.White, fontSize = 12.sp)
            }
            Text("YouTube", style = MaterialTheme.typography.headlineMedium)
            Text("без рекламы", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedTextField(
            state.query,
            actions.onQuery,
            placeholder = { Text(if (state.shelf is YtShelf.Music) "Песня, артист или альбом" else "Видео, канал или ссылка") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = if (state.query.isNotEmpty()) ({
                Icon(Icons.Rounded.Close, "Очистить", Modifier.couchyClickable(CircleShape) { actions.onQuery("") }.padding(6.dp))
            }) else null,
            singleLine = true,
            shape = RoundedCornerShape(999.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); actions.onSearch(state.query) }),
            modifier = Modifier.fillMaxWidth(),
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // YouTube Music comes first: it is easy to miss further along the row.
            item {
                Chip("Музыка", state.shelf is YtShelf.Music, onClick = { actions.onShelf(YtShelf.Music()) }, icon = Icons.Rounded.MusicNote)
            }
            item {
                Chip("Подписки", state.shelf == YtShelf.Subscriptions, onClick = { actions.onShelf(YtShelf.Subscriptions) }, icon = Icons.Rounded.Subscriptions)
            }
            item {
                val later = YtShelf.Saved(app.couchy.youtube.YouTubeViewModel.LATER, "Смотреть позже")
                Chip(later.name, state.shelf == later, onClick = { actions.onShelf(later) }, icon = Icons.Rounded.Bookmark)
            }
            item {
                val loved = YtShelf.Saved(app.couchy.youtube.YouTubeViewModel.LOVED, "Любимое")
                Chip(loved.name, state.shelf == loved, onClick = { actions.onShelf(loved) }, icon = Icons.Rounded.Favorite)
            }
            // YouTube's own music shelf is inside «Музыка», as its first page.
            val kiosks = state.kiosks.filter { it.id?.contains("music", ignoreCase = true) != true }.ifEmpty { listOf(YtShelf.Kiosk(null, "Популярное")) }
            items(kiosks) { k ->
                val shelf = state.shelf
                Chip(k.name, shelf is YtShelf.Kiosk && (shelf.id == k.id || shelf.id == null && k == kiosks.first()), onClick = { actions.onShelf(k) })
            }
            (state.shelf as? YtShelf.Search)?.let { s -> item { Chip("«${s.query}»", true, onClick = {}, icon = Icons.Rounded.Search) } }
        }
        if (state.history.isNotEmpty() && state.shelf !is YtShelf.Search && (state.shelf as? YtShelf.Music)?.query == null) {
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.history.take(8).forEach { q -> Chip(q, false, onClick = { actions.onSearch(q) }, icon = Icons.Rounded.History) }
                Chip("Очистить", false, onClick = actions.onClearHistory, icon = Icons.Rounded.Close)
            }
        }
        if (state.shelf == YtShelf.Subscriptions && state.subscriptions.isEmpty()) {
            Text(
                "Подписок пока нет. Откройте канал и нажмите «Подписаться»: новые видео будут собираться здесь. Подписки хранятся только в Couchy, без аккаунта Google.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ChannelRow(channels: List<YtChannel>, actions: YouTubeActions) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items(channels, key = { it.url }) { c ->
            Column(
                Modifier.width(76.dp).couchyClickable(RoundedCornerShape(12.dp)) { actions.onChannel(c.url, c.name) }.padding(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Avatar(c, 60.dp)
                Text(c.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun Avatar(c: YtChannel, size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(size).clip(CircleShape)) {
        Artwork(c.url, Modifier.fillMaxSize()) {
            Text(c.name.take(1).uppercase(), Modifier.align(Alignment.Center), color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value / 2.4f).sp)
        }
        if (c.avatar != null) AsyncImage(c.avatar, c.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun ChannelHeader(c: YtChannel, subscribed: Boolean, actions: YouTubeActions) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(
            Modifier.size(44.dp).couchyClickable(CircleShape, onClick = actions.onCloseChannel).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад", modifier = Modifier.size(22.dp)) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Avatar(c, 72.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(c.name.ifBlank { "Канал" }, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (c.subscribers > 0) {
                    Text("${count(c.subscribers)} подписчиков", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (subscribed) SecondaryButton("Вы подписаны", Icons.Rounded.Check, onClick = { actions.onSubscribe(c) })
        else PrimaryButton("Подписаться", Icons.Rounded.NotificationsActive, onClick = { actions.onSubscribe(c) })
    }
}

@Composable
private fun VideoCard(v: YtVideo, later: Boolean, loved: Boolean, actions: YouTubeActions) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.couchyClickable(shape, focusScale = 1.03f, onLongClick = v.channelUrl?.let { url -> { actions.onChannel(url, v.channel) } }) { actions.onVideo(v) },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(shape)) {
            Artwork(v.id, Modifier.fillMaxSize())
            AsyncImage(v.thumb, v.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            val badge = when {
                v.live -> "В ЭФИРЕ"
                v.durationSec > 0 -> duration(v.durationSec)
                else -> null
            }
            badge?.let {
                Text(
                    it,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
                        .background(if (v.live) Color(0xFFE5202E) else Color.Black.copy(alpha = 0.78f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(v.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Meta(v)
            }
            Mark(
                if (later) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                if (later) "Убрать из «Смотреть позже»" else "Смотреть позже",
                later,
            ) { actions.onLater(v) }
            Mark(
                if (loved) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                if (loved) "Убрать из любимого" else "В любимое",
                loved,
            ) { actions.onLoved(v) }
        }
    }
}

@Composable
private fun Mark(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(40.dp).couchyClickable(CircleShape, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, Modifier.size(22.dp), tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Meta(v: YtVideo) {
        Text(
            listOfNotNull(
                v.channel.takeIf { it.isNotBlank() },
                v.views.takeIf { it > 0 }?.let { "${count(it)} просмотров" },
                v.ago ?: v.publishedMs.takeIf { it > 0 }?.let(::agoOf),
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
}

fun duration(sec: Long): String {
    val h = sec / 3600
    val m = sec % 3600 / 60
    val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** 1 234 → «1,2 тыс.», 3 400 000 → «3,4 млн». */
fun count(n: Long): String {
    val (value, unit) = when {
        n >= 1_000_000_000 -> n / 1e9 to " млрд"
        n >= 1_000_000 -> n / 1e6 to " млн"
        n >= 1_000 -> n / 1e3 to " тыс."
        else -> return n.toString()
    }
    val text = if (value >= 10 || value % 1.0 < 0.05) value.toLong().toString() else "%.1f".format(java.util.Locale.US, value).replace('.', ',')
    return text + unit
}

private fun agoOf(ms: Long): String {
    val minutes = (System.currentTimeMillis() - ms) / 60_000
    return when {
        minutes < 60 -> "$minutes мин. назад"
        minutes < 24 * 60 -> "${minutes / 60} ч. назад"
        minutes < 30 * 24 * 60 -> "${minutes / 60 / 24} дн. назад"
        minutes < 365 * 24 * 60 -> "${minutes / 60 / 24 / 30} мес. назад"
        else -> "${minutes / 60 / 24 / 365} г. назад"
    }
}

/** YouTube Music: albums and playlists in rows, songs in a list; a song plays with the rest after it. */
@Composable
private fun MusicList(state: YouTubeUi, actions: YouTubeActions, modifier: Modifier) {
    val form = LocalFormFactor.current
    val side = if (form.wide) 32.dp else 16.dp
    val music = state.music
    var albumMenu by remember { mutableStateOf<app.couchy.youtube.YtAlbum?>(null) }
    var login by remember { mutableStateOf(false) }
    if (login) MusicLoginDialog(onSignedIn = { login = false; actions.onMusicSignedIn() }, onDismiss = { login = false })
    val front = (state.shelf as? YtShelf.Music)?.query == null
    androidx.compose.foundation.lazy.LazyColumn(
        modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(start = side, end = side, top = 12.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item("head") { Header(state, actions) }
        if (state.list.loading) item("loading") {
            Box(Modifier.fillMaxWidth().padding(vertical = 28.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(28.dp), color = MaterialTheme.colorScheme.primary)
            }
        }
        state.list.error?.let { e ->
            item("error") {
                Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(e, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SecondaryButton("Ещё раз", Icons.Rounded.Refresh, onClick = actions.onRetry)
                }
            }
        }
        if (front && !music.signedIn && !state.list.loading) item("login") {
            app.couchy.ui.components.SheetRow(
                "Войти в YouTube Music",
                onClick = { login = true },
                icon = Icons.Rounded.MusicNote,
                tag = "плейлисты и миксы",
            )
        }
        music.accountError?.let { e ->
            item("account-error") {
                Text(e, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
            }
        }
        if (music.mine.isNotEmpty()) item("mine") { AlbumRow("Мои плейлисты", music.mine, actions) { albumMenu = it } }
        music.shelves.forEachIndexed { i, shelf ->
            if (shelf.albums.isNotEmpty()) item("shelf-$i-albums") { AlbumRow(shelf.title, shelf.albums, actions) { albumMenu = it } }
            if (shelf.songs.isNotEmpty()) {
                item("shelf-$i-title") {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (shelf.albums.isEmpty()) shelf.title else "${shelf.title}: песни", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        SecondaryButton("Слушать", Icons.Rounded.PlayArrow, onClick = { actions.onSong(shelf.songs, 0) })
                    }
                }
                val shown = shelf.songs.take(8)
                itemsIndexed(shown, key = { j, v -> "shelf-$i-song-$j-" + v.id }) { j, v -> SongRow(v) { actions.onSong(shelf.songs, j) } }
            }
        }
        if (music.albums.isNotEmpty()) item("albums") { AlbumRow("Альбомы", music.albums, actions) { albumMenu = it } }
        if (music.playlists.isNotEmpty()) item("playlists") { AlbumRow("Плейлисты", music.playlists, actions) { albumMenu = it } }
        if (music.songs.isNotEmpty()) item("songs-title") {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (front) "Сейчас слушают" else "Песни", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                SecondaryButton("Перемешать", Icons.Rounded.Shuffle, onClick = { actions.onSong(music.songs.shuffled(), 0) })
            }
        }
        itemsIndexed(music.songs, key = { _, v -> "song-" + v.id }) { i, v -> SongRow(v) { actions.onSong(music.songs, i) } }
        if (front && music.signedIn && !state.list.loading) item("sign-out") {
            androidx.compose.material3.TextButton(onClick = actions.onMusicSignOut, modifier = Modifier.padding(top = 12.dp)) {
                Text("Выйти из YouTube Music", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (!state.list.loading && state.list.error == null && music.songs.isEmpty() && music.albums.isEmpty() && music.mine.isEmpty() && music.shelves.isEmpty()) item("empty") {
            Text(
                "Найдите песню, артиста или альбом. Музыка играет без картинки, в фоне и в маленьком плеере, песни идут одна за другой.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }
    }
    albumMenu?.let { a ->
        app.couchy.ui.player.OptionsDialog(
            a.title,
            listOf(
                app.couchy.ui.player.OptionSection(
                    null,
                    listOf(
                        app.couchy.ui.player.Option("Слушать", false, icon = Icons.Rounded.PlayArrow) { actions.onAlbum(a, false) },
                        app.couchy.ui.player.Option("Перемешать", false, icon = Icons.Rounded.Shuffle) { actions.onAlbum(a, true) },
                        app.couchy.ui.player.Option("Сохранить в «Музыку»", false, icon = Icons.Rounded.Download, tag = "по трекам") { actions.onSaveAlbum(a) },
                    ),
                ),
            ),
            onDismiss = { albumMenu = null },
        )
    }
}

@Composable
private fun AlbumRow(title: String, albums: List<app.couchy.youtube.YtAlbum>, actions: YouTubeActions, onMenu: (app.couchy.youtube.YtAlbum) -> Unit) {
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(albums, key = { it.url }) { a ->
                val shape = RoundedCornerShape(14.dp)
                Column(
                    Modifier.width(148.dp).couchyClickable(shape, focusScale = 1.04f, onLongClick = { onMenu(a) }) { actions.onAlbum(a, false) },
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(Modifier.size(148.dp).clip(shape)) {
                        Artwork(a.url, Modifier.fillMaxSize())
                        a.thumb?.let { AsyncImage(it, a.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                        Box(
                            Modifier.align(Alignment.BottomEnd).padding(8.dp).size(36.dp).background(Color.Black.copy(alpha = 0.6f), CircleShape)
                                .couchyClickable(CircleShape) { onMenu(a) },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Rounded.MoreHoriz, "Ещё", tint = Color.White, modifier = Modifier.size(20.dp)) }
                    }
                    Text(a.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(a.artist.takeIf { it.isNotBlank() }, a.count.takeIf { it > 0 }?.let { "$it треков" }).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun SongRow(v: YtVideo, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().couchyClickable(shape, focusScale = 1.02f, onClick = onClick).padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(10.dp))) {
            Artwork(v.id, Modifier.fillMaxSize())
            AsyncImage(v.thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(v.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(v.channel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (v.durationSec > 0) Text(duration(v.durationSec), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
