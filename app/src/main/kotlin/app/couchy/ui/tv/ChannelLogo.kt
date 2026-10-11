package app.couchy.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.couchy.ui.components.artColorsFor
import coil.compose.AsyncImage

/** The channel's logo from the playlist, or its first letter on a color of its own until (or unless) it loads. */
@Composable
fun ChannelLogo(name: String, logo: String?, size: Dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(size * 0.26f)
    var failed by remember(logo) { mutableStateOf(logo == null) }
    Box(modifier.size(size).clip(shape).background(if (failed) artColorsFor(name).base else Color(0xFF24272F)), contentAlignment = Alignment.Center) {
        if (failed) {
            Text(
                name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "•",
                color = Color.White,
                fontWeight = FontWeight.ExtraBold,
                fontSize = (size.value * 0.45f).sp,
            )
        } else {
            AsyncImage(
                model = logo,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                onError = { failed = true },
                modifier = Modifier.fillMaxSize().padding(size * 0.1f),
            )
        }
    }
}
