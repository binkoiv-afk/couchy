package app.couchy.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.couchy.data.ThemeMode

/** Couchy's palette from the mockups: near-black cinema background, popcorn-orange accent. */
object Brand {
    val Orange = Color(0xFFFF8A3D)
    val OrangeLight = Color(0xFFFFB070)
    val OrangeDeep = Color(0xFFEA6A1A)
    val OnOrange = Color(0xFF1A0E05)
    val Green = Color(0xFF3DD68C)
    val Red = Color(0xFFFF5C5C)
}

/** Colors Material has no slot for. */
@Immutable
data class CouchyColors(
    val focusRing: Color,
    val textMuted: Color,
    val success: Color,
    val danger: Color,
    val isDark: Boolean,
)

val LocalCouchyColors = staticCompositionLocalOf {
    CouchyColors(Color.White, Color.Gray, Brand.Green, Brand.Red, true)
}

private val Dark = darkColorScheme(
    primary = Brand.Orange,
    onPrimary = Brand.OnOrange,
    primaryContainer = Color(0xFF4A2A14),
    onPrimaryContainer = Brand.OrangeLight,
    secondary = Brand.OrangeLight,
    onSecondary = Brand.OnOrange,
    secondaryContainer = Color(0xFF2A2E38),
    onSecondaryContainer = Color(0xFFF3F3F6),
    background = Color(0xFF0D0E12),
    onBackground = Color(0xFFF3F3F6),
    surface = Color(0xFF0D0E12),
    onSurface = Color(0xFFF3F3F6),
    surfaceVariant = Color(0xFF1F222A),
    onSurfaceVariant = Color(0xFFA4A8B4),
    surfaceContainerLowest = Color(0xFF08090C),
    surfaceContainerLow = Color(0xFF121419),
    surfaceContainer = Color(0xFF16181E),
    surfaceContainerHigh = Color(0xFF1F222A),
    surfaceContainerHighest = Color(0xFF2A2E38),
    outline = Color(0xFF4A4F5C),
    outlineVariant = Color(0xFF24272F),
    error = Brand.Red,
)

private val Light = lightColorScheme(
    primary = Brand.OrangeDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE2CC),
    onPrimaryContainer = Color(0xFF3A1A04),
    secondary = Color(0xFFB65516),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3DDD5),
    onSecondaryContainer = Color(0xFF16171B),
    background = Color(0xFFF7F4F0),
    onBackground = Color(0xFF16171B),
    surface = Color(0xFFF7F4F0),
    onSurface = Color(0xFF16171B),
    surfaceVariant = Color(0xFFEFEAE4),
    onSurfaceVariant = Color(0xFF5B5F6B),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFBF9F6),
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFEFEAE4),
    surfaceContainerHighest = Color(0xFFE3DDD5),
    outline = Color(0xFFB8B2AA),
    outlineVariant = Color(0xFFE3DDD5),
    error = Color(0xFFE04545),
)

private val CouchyTypography = Typography(
    displaySmall = TextStyle(fontSize = 40.sp, lineHeight = 44.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp),
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold),
    labelMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp),
)

private val CouchyShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun CouchyTheme(mode: ThemeMode = ThemeMode.DARK, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val scheme = if (dark) Dark else Light
    val extra = CouchyColors(
        focusRing = scheme.onBackground,
        textMuted = if (dark) Color(0xFF6D7280) else Color(0xFF8D919B),
        success = if (dark) Brand.Green else Color(0xFF1FA765),
        danger = scheme.error,
        isDark = dark,
    )
    CompositionLocalProvider(LocalCouchyColors provides extra) {
        MaterialTheme(colorScheme = scheme, typography = CouchyTypography, shapes = CouchyShapes) {
            // Text with no color of its own follows the theme everywhere, also outside the main frame
            // (title page, torrent page, site window), instead of falling back to black.
            CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides scheme.onBackground, content = content)
        }
    }
}

object CouchyTheme {
    val colors: CouchyColors @Composable get() = LocalCouchyColors.current
}
