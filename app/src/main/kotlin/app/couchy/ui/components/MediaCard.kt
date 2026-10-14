package app.couchy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.couchy.core.LinkKind
import app.couchy.core.Progress
import app.couchy.data.MediaEntity

private val CardShape = RoundedCornerShape(12.dp)

/** A 16:9 card with the title on the artwork, a progress line and a caption underneath. */
@Composable
fun MediaCard(
    item: MediaEntity,
    width: Dp?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    caption: String? = captionFor(item),
) {
    Column(
        modifier.then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .couchyClickable(CardShape, onLongClick = onLongClick, memoryKey = item.uri, onClick = onClick)
                .clip(CardShape),
        ) {
            Artwork(item.title, Modifier.fillMaxSize()) {
                item.poster?.let { url ->
                    coil.compose.AsyncImage(
                        model = url,
                        contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.6f))),
            )
            Text(
                item.title,
                color = Color.White,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 17.sp,
                lineHeight = 19.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            )
            if (item.favorite) {
                Icon(
                    Icons.Rounded.Favorite, null,
                    tint = Color.White,
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).width(18.dp).height(18.dp),
                )
            }
            KindBadge(item, Modifier.align(Alignment.TopStart).padding(8.dp))
            val fraction = Progress.fraction(item.positionMs, item.durationMs)
            if (fraction > 0f) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = 0.25f))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(MaterialTheme.colorScheme.primary))
                }
            }
        }
        if (caption != null) {
            Text(
                caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun KindBadge(item: MediaEntity, modifier: Modifier) {
    val label = when (item.kind) {
        LinkKind.HLS.name, LinkKind.DASH.name -> "ПОТОК"
        else -> if (item.uri.startsWith("content://") || item.uri.startsWith("file://")) "ФАЙЛ" else null
    } ?: return
    Text(
        label,
        color = Color.White,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(5.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

fun captionFor(item: MediaEntity): String = when {
    Progress.isWatched(item.positionMs, item.durationMs) -> "Просмотрено"
    item.positionMs >= Progress.MIN_RESUME_MS ->
        Progress.remainingLabel(item.positionMs, item.durationMs) ?: "Начато"
    else -> "Не начато"
}
