package app.couchy.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Live TV's channel list over the picture, sliding in from the left like on a TV box: sections
 * on the left, their channels beside them. Moving through the sections shows their channels at
 * once; OK on a channel switches to it. The picture keeps playing on the right.
 */
@Composable
fun ChannelDrawer(
    groups: List<String>,
    group: String?,
    channels: (String) -> List<LiveChoice>,
    currentId: Long?,
    onPick: (Long, String) -> Unit,
    onClose: () -> Unit,
) {
    var shown by remember { mutableStateOf(group ?: groups.firstOrNull().orEmpty()) }
    val list = remember(shown) { channels(shown) }
    val channelFocus = remember { FocusRequester() }
    val groupFocus = remember { FocusRequester() }
    val start = remember(list) { list.indexOfFirst { it.id == currentId }.coerceAtLeast(0) }
    val listState = rememberLazyListState()
    val groupState = rememberLazyListState(initialFirstVisibleItemIndex = groups.indexOf(shown).coerceAtLeast(0))
    var opened by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        listState.scrollToItem((start - 3).coerceAtLeast(0))
        runCatching { channelFocus.requestFocus() }
        opened = true
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.92f), 0.55f to Color.Black.copy(alpha = 0.75f), 1f to Color.Transparent))
            .pointerInput(Unit) { detectTapGestures(onTap = { onClose() }) },
    ) {
        Row(Modifier.fillMaxHeight().padding(vertical = 24.dp, horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            // Sections: the one in focus shows its channels; Right goes over to them.
            LazyColumn(
                Modifier.width(220.dp).fillMaxHeight(),
                state = groupState,
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(groups, key = { _, g -> "g:$g" }) { _, g ->
                    DrawerRow(
                        g, null, g == shown,
                        modifier = if (g == shown) Modifier.focusRequester(groupFocus) else Modifier,
                        onFocus = { if (opened) shown = g },
                        onRight = { runCatching { channelFocus.requestFocus() } },
                        onClick = { shown = g; runCatching { channelFocus.requestFocus() } },
                    )
                }
            }
            LazyColumn(
                Modifier.width(420.dp).fillMaxHeight(),
                state = listState,
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (list.isEmpty()) item { Text("В этом разделе нет каналов", color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(12.dp)) }
                itemsIndexed(list, key = { _, c -> "c:${c.id}" }) { i, c ->
                    DrawerRow(
                        "${c.number}  ${c.name}", c.now, c.id == currentId,
                        modifier = if (i == start) Modifier.focusRequester(channelFocus) else Modifier,
                        onLeft = { runCatching { groupFocus.requestFocus() } },
                        onClick = { if (c.id != currentId) onPick(c.id, shown); onClose() },
                    )
                }
            }
        }
    }
}

@Composable
private fun DrawerRow(
    title: String,
    note: String?,
    current: Boolean,
    modifier: Modifier = Modifier,
    onFocus: () -> Unit = {},
    onLeft: (() -> Unit)? = null,
    onRight: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(12.dp)
    val accent = MaterialTheme.colorScheme.primary
    Column(
        modifier
            .fillMaxWidth()
            .onFocusChanged { if (it.isFocused) onFocus() }
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { onClick(); true }
                    Key.DirectionLeft -> onLeft?.let { it(); true } ?: false
                    Key.DirectionRight -> onRight?.let { it(); true } ?: false
                    else -> false
                }
            }
            .focusable(interactionSource = interaction)
            .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) }
            .background(
                when {
                    focused -> Color.White
                    current -> accent.copy(alpha = 0.22f)
                    else -> Color.Transparent
                },
                shape,
            )
            .then(if (current && !focused) Modifier.border(1.dp, accent.copy(alpha = 0.6f), shape) else Modifier)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
            color = if (focused) Color.Black else Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        note?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = if (focused) Color.Black.copy(alpha = 0.65f) else Color.White.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The channel the remote is flipping to, shown before it switches: number, name and what is on. */
@Composable
fun ZapBanner(target: LiveChoice?, steps: Int, modifier: Modifier = Modifier) {
    Row(
        modifier
            .background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(16.dp))
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val number = if (target == null) (if (steps > 0) "+$steps" else "$steps") else target.number.takeIf { it > 0 }?.toString()
        number?.let {
            Text(it, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }
        Column(Modifier.width(320.dp)) {
            Text(target?.name ?: "Переключаю…", style = MaterialTheme.typography.titleLarge, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            target?.now?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}
