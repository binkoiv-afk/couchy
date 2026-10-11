package app.couchy.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.ChildCare
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.couchy.core.Progress
import app.couchy.core.Time
import app.couchy.data.MediaEntity

/** Everything you can do with a title, opened by a long press or the menu key. */
@Composable
fun ItemActionsDialog(
    item: MediaEntity,
    onPlay: () -> Unit,
    onFromStart: () -> Unit,
    onFavorite: () -> Unit,
    onUnwatched: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
    /** "Watch on TV"; null when the item only exists on this device. */
    onCast: (() -> Unit)? = null,
    /** Allows or hides the title in kids mode. */
    onKids: (() -> Unit)? = null,
) {
    val first = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        title = { Text(item.title, maxLines = 2) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val resume = Progress.resumeFrom(item.positionMs, item.durationMs)
                ActionRow(
                    Icons.Rounded.PlayArrow,
                    if (resume > 0) "Продолжить с ${Time.clock(resume)}" else "Смотреть",
                    Modifier.focusRequester(first),
                ) { onDismiss(); onPlay() }
                if (resume > 0) ActionRow(Icons.Rounded.Replay, "Смотреть сначала") { onDismiss(); onFromStart() }
                onCast?.let { cast -> ActionRow(Icons.Rounded.Cast, "Смотреть на ТВ") { onDismiss(); cast() } }
                ActionRow(
                    if (item.favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    if (item.favorite) "Убрать из избранного" else "В избранное",
                ) { onDismiss(); onFavorite() }
                onKids?.let { kids ->
                    ActionRow(Icons.Rounded.ChildCare, if (item.kids) "Не показывать детям" else "Показывать детям") { onDismiss(); kids() }
                }
                if (item.positionMs > 0) ActionRow(Icons.Rounded.VisibilityOff, "Отметить непросмотренным") { onDismiss(); onUnwatched() }
                ActionRow(Icons.Rounded.Delete, "Удалить из медиатеки") { onDismiss(); onRemove() }
            }
        },
    )
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

@Composable
private fun ActionRow(icon: ImageVector, text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .couchyClickable(RoundedCornerShape(10.dp), focusScale = 1.02f, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, null, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}
