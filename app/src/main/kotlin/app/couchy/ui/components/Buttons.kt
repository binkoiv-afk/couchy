package app.couchy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

private val ButtonShape = RoundedCornerShape(12.dp)

/** The orange main action, "Смотреть". */
@Composable
fun PrimaryButton(text: String, icon: ImageVector?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .height(48.dp)
            .couchyClickable(ButtonShape, onClick = onClick)
            .background(MaterialTheme.colorScheme.primary, ButtonShape)
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimary, maxLines = 1)
    }
}

/** A quieter glassy button next to the main one. */
@Composable
fun SecondaryButton(
    text: String?,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onImage: Boolean = false,
) {
    val content = if (onImage) Color.White else MaterialTheme.colorScheme.onSurface
    val fill = if (onImage) Color.White.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceContainerHigh
    Row(
        modifier
            .height(48.dp)
            .couchyClickable(ButtonShape, onClick = onClick)
            .background(fill, ButtonShape)
            .border(1.dp, content.copy(alpha = 0.08f), ButtonShape)
            .padding(horizontal = if (text == null) 14.dp else 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = content, modifier = Modifier.size(20.dp))
        if (text != null) Text(text, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
    }
}

/** A pill used for filters and sort options. */
@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val shape = RoundedCornerShape(999.dp)
    val bg = if (selected) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.surfaceContainerHigh
    val fg = if (selected) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier
            .height(36.dp)
            .couchyClickable(shape, onClick = onClick)
            .background(bg, shape)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = fg, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1, softWrap = false)
    }
}
