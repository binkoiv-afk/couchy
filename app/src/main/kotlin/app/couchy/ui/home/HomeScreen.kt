package app.couchy.ui.home

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.couchy.HomeState
import app.couchy.core.Progress
import app.couchy.core.Time
import app.couchy.data.MediaEntity
import app.couchy.ui.components.Artwork
import app.couchy.ui.components.CouchMark
import app.couchy.ui.components.FormFactor
import app.couchy.ui.components.LocalFormFactor
import app.couchy.ui.components.MediaCard
import app.couchy.ui.components.PrimaryButton
import app.couchy.ui.components.SecondaryButton

class HomeActions(
    val onPlay: (MediaEntity) -> Unit,
    val onMore: (MediaEntity) -> Unit,
    val onFavorite: (MediaEntity) -> Unit,
    val onOpen: () -> Unit,
)

/**
 * Home: a hero for what you were watching last (or a welcome when the library is empty),
 * then rows of continue watching, favorites and recently added.
 */
@Composable
fun HomeContent(state: HomeState, actions: HomeActions, modifier: Modifier = Modifier) {
    val form = LocalFormFactor.current
    val side = if (form.wide) 32.dp else 18.dp
    val cardWidth = when (form) {
        FormFactor.TV -> 260.dp
        FormFactor.TABLET -> 250.dp
        FormFactor.PHONE -> 200.dp
    }
    val heroFocus = remember { FocusRequester() }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item("hero") {
            val first = state.continueWatching.firstOrNull()
            if (first != null) {
                Hero(first, actions, Modifier.padding(horizontal = side, vertical = 16.dp), heroFocus)
            } else if (state.loaded) {
                Welcome(actions.onOpen, Modifier.padding(horizontal = side, vertical = 16.dp), heroFocus)
            }
        }
        fun row(key: String, title: String, items: List<MediaEntity>) {
            if (items.isEmpty()) return
            item(key) {
                Column(Modifier.padding(top = 18.dp)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(horizontal = side).padding(bottom = 12.dp),
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = side, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(if (form.isTv) 20.dp else 12.dp),
                    ) {
                        items(items, key = { it.uri }) { media ->
                            MediaCard(media, cardWidth, onClick = { actions.onPlay(media) }, onLongClick = { actions.onMore(media) })
                        }
                    }
                }
            }
        }
        row("continue", "Продолжить просмотр", state.continueWatching)
        row("favorites", "Избранное", state.favorites)
        row("recent", "Недавно добавленные", state.recent)
    }
    // The remote starts on the main button, so OK immediately plays.
    LaunchedEffect(form, state.loaded) {
        if (form.isTv && state.loaded) runCatching { heroFocus.requestFocus() }
    }
}

@Composable
private fun Hero(item: MediaEntity, actions: HomeActions, modifier: Modifier, focus: FocusRequester) {
    val form = LocalFormFactor.current
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier
            .fillMaxWidth()
            .height(if (form == FormFactor.PHONE) 260.dp else 340.dp)
            .clip(shape),
    ) {
        Artwork(item.title, Modifier.fillMaxSize())
        Box(
            Modifier.fillMaxSize().background(
                if (form.wide) Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.75f), 0.7f to Color.Transparent)
                else Brush.verticalGradient(0.3f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.8f)),
            ),
        )
        Column(
            Modifier.align(androidx.compose.ui.Alignment.BottomStart).padding(if (form.wide) 32.dp else 18.dp).widthIn(max = 560.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                (Progress.remainingLabel(item.positionMs, item.durationMs) ?: "Продолжить").uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFFFB070),
            )
            Text(
                item.title,
                style = if (form == FormFactor.PHONE) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.displaySmall,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PrimaryButton(
                    "Продолжить · ${Time.clock(item.positionMs)}",
                    Icons.Rounded.PlayArrow,
                    onClick = { actions.onPlay(item) },
                    modifier = Modifier.focusRequester(focus),
                )
                SecondaryButton(
                    null,
                    if (item.favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    onClick = { actions.onFavorite(item) },
                    onImage = true,
                )
                SecondaryButton(null, Icons.Rounded.MoreHoriz, onClick = { actions.onMore(item) }, onImage = true)
            }
        }
    }
}

@Composable
private fun Welcome(onOpen: () -> Unit, modifier: Modifier, focus: FocusRequester) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CouchMark(56.dp)
        Text("Добро пожаловать в Couchy", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Откройте ссылку на видео, поток .m3u8 или файл с устройства. Couchy запомнит, где вы остановились. " +
                "Онлайн-кинотеатры, торренты, IPTV и YouTube появятся в следующих сборках.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 640.dp),
        )
        Spacer(Modifier.height(4.dp))
        PrimaryButton("Открыть видео", Icons.Rounded.Add, onClick = onOpen, modifier = Modifier.focusRequester(focus))
    }
}
