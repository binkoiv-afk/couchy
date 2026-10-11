package app.couchy.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ripple
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import app.couchy.ui.theme.LocalCouchyColors

/**
 * Clickable that also reads well with a remote: the focused element grows a little and gets a
 * ring, like on the TV mockups. The menu key (and a long press) opens [onLongClick].
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.couchyClickable(
    shape: Shape = RoundedCornerShape(12.dp),
    focusScale: Float = 1.05f,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) focusScale else 1f, label = "focus")
    val ring = LocalCouchyColors.current.focusRing
    this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .then(if (focused) Modifier.border(3.dp, ring, shape) else Modifier)
        .onKeyEvent { event ->
            if (onLongClick != null && event.type == KeyEventType.KeyUp && event.key == Key.Menu) {
                onLongClick(); true
            } else {
                false
            }
        }
        .combinedClickable(
            interactionSource = interaction,
            indication = ripple(),
            onLongClick = onLongClick,
            onClick = onClick,
        )
}
