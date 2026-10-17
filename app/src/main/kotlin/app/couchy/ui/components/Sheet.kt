package app.couchy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * The one look of every menu and step in Couchy: a rounded card with a large title, an optional
 * step line above it ("Шаг 2 из 3") and a back arrow to the step before.
 */
@Composable
fun CouchySheet(
    title: String,
    onDismiss: () -> Unit,
    step: String? = null,
    onBack: (() -> Unit)? = null,
    scroll: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Back goes one step back where there is one; a tap outside still closes the whole sheet.
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = onBack == null)) {
        if (onBack != null) androidx.activity.compose.BackHandler(onBack = onBack)
        val shape = RoundedCornerShape(28.dp)
        // On a remote something is always focused when a sheet opens, so OK works at once.
        val tv = LocalFormFactor.current.isTv
        var hasFocus by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
        val body = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
        androidx.compose.runtime.LaunchedEffect(Unit) {
            if (!tv) return@LaunchedEffect
            kotlinx.coroutines.delay(150)
            // Nothing took the focus itself: the first choice gets it, not the close button.
            if (!hasFocus) runCatching { body.requestFocus() }
        }
        Column(
            Modifier
                .onFocusChanged { hasFocus = it.hasFocus }
                .padding(horizontal = 16.dp, vertical = 24.dp)
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer, shape)
                .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), shape)
                .padding(top = 18.dp, bottom = 14.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = if (onBack != null) 8.dp else 22.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                onBack?.let { back -> RoundIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад", back) }
                Column(Modifier.weight(1f).padding(start = if (onBack != null) 4.dp else 0.dp)) {
                    step?.let {
                        Text(it.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    }
                    Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                RoundIcon(Icons.Rounded.Close, "Закрыть", onDismiss)
            }
            Column(
                Modifier
                    .focusRequester(body)
                    .focusGroup()
                    .padding(top = 12.dp)
                    .heightIn(max = 560.dp)
                    .then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                    .padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun RoundIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).couchyClickable(CircleShape, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, description, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
    }
}

/** A small section header inside a sheet. */
@Composable
fun SheetHeader(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 8.dp, top = 10.dp, bottom = 2.dp),
    )
}

/**
 * One choice in a sheet: an icon in a soft square, the name, one short tag on the right.
 * The chosen one is tinted with the accent; [badge] marks the recommended one.
 */
@Composable
fun SheetRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tag: String? = null,
    selected: Boolean = false,
    badge: String? = null,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    val shape = RoundedCornerShape(16.dp)
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .couchyClickable(shape, focusScale = 1.02f) { if (enabled) onClick() }
            .background(if (selected) accent.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f), shape)
            .then(if (selected) Modifier.border(1.dp, accent.copy(alpha = 0.55f), shape) else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) {
            Box(
                Modifier.size(38.dp).background(if (selected) accent.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(11.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = if (selected) accent else MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(21.dp))
            }
        }
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            badge?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.background,
                    maxLines = 1,
                    modifier = Modifier.background(accent, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        tag?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // A long tag is cut short instead of squeezing the name into a column of letters.
                modifier = Modifier
                    .widthIn(max = 150.dp)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        trailing?.invoke()
    }
}

/** A square tile with a large icon: the player's «Ещё» tools. [on] lights it as switched on. */
data class Tile(val label: String, val icon: ImageVector, val on: Boolean = false, val state: String? = null, val onClick: () -> Unit)

@Composable
fun TileGrid(tiles: List<Tile>, columns: Int = 2) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { t -> TileBox(t, Modifier.weight(1f)) }
                repeat(columns - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun TileBox(t: Tile, modifier: Modifier) {
    val shape = RoundedCornerShape(20.dp)
    val accent = MaterialTheme.colorScheme.primary
    Column(
        modifier
            .heightIn(min = 108.dp)
            .couchyClickable(shape, focusScale = 1.04f, onClick = t.onClick)
            .background(if (t.on) accent.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainerHigh, shape)
            .then(if (t.on) Modifier.border(1.dp, accent.copy(alpha = 0.6f), shape) else Modifier)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(42.dp).background(if (t.on) accent else MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(t.icon, null, tint = if (t.on) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(t.label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            t.state?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = if (t.on) accent else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
        }
    }
}
