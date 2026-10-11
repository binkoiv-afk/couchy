package app.couchy.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.couchy.ui.theme.Brand

/** The couch whose seat is a play button. */
@Composable
fun CouchMark(size: Dp = 28.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val u = this.size.width / 32f
        drawRoundRect(Brand.OrangeLight, Offset(6 * u, 5 * u), Size(20 * u, 12 * u), CornerRadius(5 * u))
        drawRoundRect(Brand.Orange, Offset(2 * u, 9 * u), Size(28 * u, 17 * u), CornerRadius(6 * u))
        drawRoundRect(Brand.Orange, Offset(5 * u, 25 * u), Size(3 * u, 4 * u), CornerRadius(1.2f * u))
        drawRoundRect(Brand.Orange, Offset(24 * u, 25 * u), Size(3 * u, 4 * u), CornerRadius(1.2f * u))
        val play = Path().apply {
            moveTo(13.5f * u, 12.5f * u)
            lineTo(13.5f * u, 20.5f * u)
            lineTo(20f * u, 16.5f * u)
            close()
        }
        drawPath(play, Brand.OnOrange)
    }
}

@Composable
fun CouchyLogo(markSize: Dp = 30.dp, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CouchMark(markSize)
        Text(
            "couchy",
            fontSize = (markSize.value * 0.78f).sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-0.5).sp,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}
