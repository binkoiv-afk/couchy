package app.couchy.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ripple
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
 * What was opened last, so coming back from the player puts the remote's focus on it again
 * instead of at the top of the screen.
 */
object FocusMemory {
    @Volatile var key: String? = null
}

/**
 * Clickable that also reads well with a remote: the focused element grows a little and gets a
 * ring, like on the TV mockups. The menu key (and a long press) opens [onLongClick].
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.couchyClickable(
    shape: Shape = RoundedCornerShape(12.dp),
    focusScale: Float = 1.05f,
    onLongClick: (() -> Unit)? = null,
    /** A card's identity: after the player closes, the card that opened it takes the focus back. */
    memoryKey: String? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val requester = remember { FocusRequester() }
    if (memoryKey != null) {
        LaunchedEffect(memoryKey) {
            if (FocusMemory.key == memoryKey) {
                runCatching { requester.requestFocus() }
                FocusMemory.key = null
            }
        }
    }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) focusScale else 1f, label = "focus")
    val ring = LocalCouchyColors.current.focusRing
    this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        // A dark line inside the ring keeps it visible on white buttons too.
        .then(if (focused) Modifier.border(5.dp, androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.7f), shape).border(3.dp, ring, shape) else Modifier)
        .onKeyEvent { event ->
            if (onLongClick != null && event.type == KeyEventType.KeyUp && event.key == Key.Menu) {
                onLongClick(); true
            } else {
                false
            }
        }
        .then(if (memoryKey != null) Modifier.focusRequester(requester) else Modifier)
        .combinedClickable(
            interactionSource = interaction,
            indication = ripple(),
            onLongClick = onLongClick,
            onClick = { if (memoryKey != null) FocusMemory.key = memoryKey; onClick() },
        )
}
