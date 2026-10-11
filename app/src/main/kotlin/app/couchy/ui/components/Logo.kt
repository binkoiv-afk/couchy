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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The ring "C" around a play button, in Couchy's sunset gradient. */
@Composable
fun CouchMark(size: Dp = 28.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val u = this.size.width / 64f
        val brush = Brush.linearGradient(
            listOf(Color(0xFFFF9A3D), Color(0xFFFF4D6D), Color(0xFFA24DFF)),
            start = Offset(12 * u, 12 * u),
            end = Offset(52 * u, 52 * u),
        )
        // An open ring: from about one o'clock round to five o'clock.
        drawArc(
            brush, startAngle = -40f, sweepAngle = -280f, useCenter = false,
            topLeft = Offset(15.3f * u, 15.3f * u), size = Size(33.4f * u, 33.4f * u),
            style = Stroke(width = 7 * u, cap = StrokeCap.Round),
        )
        val play = Path().apply {
            moveTo(28f * u, 24.5f * u)
            lineTo(28f * u, 39.5f * u)
            lineTo(40f * u, 32f * u)
            close()
        }
        drawPath(play, brush)
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
            fontFamily = MaterialTheme.typography.headlineMedium.fontFamily,
            letterSpacing = (-1).sp,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}
