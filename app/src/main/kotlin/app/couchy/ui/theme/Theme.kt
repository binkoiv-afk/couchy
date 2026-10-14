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
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import app.couchy.R
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
    background = Color(0xFF07080B),
    onBackground = Color(0xFFF5F5F7),
    surface = Color(0xFF07080B),
    onSurface = Color(0xFFF5F5F7),
    surfaceVariant = Color(0xFF1C1E25),
    onSurfaceVariant = Color(0xFF9EA2AE),
    surfaceContainerLowest = Color(0xFF040506),
    surfaceContainerLow = Color(0xFF0E1015),
    surfaceContainer = Color(0xFF121419),
    surfaceContainerHigh = Color(0xFF1B1D24),
    surfaceContainerHighest = Color(0xFF262932),
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

/** Inter for reading, Inter Display for the big type: crisp at every size, on a phone and across a room. */
private val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semi_bold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
    Font(R.font.inter_display_extra_bold, FontWeight.ExtraBold),
)
private val InterDisplay = FontFamily(
    Font(R.font.inter_display_bold, FontWeight.Bold),
    Font(R.font.inter_display_extra_bold, FontWeight.ExtraBold),
)

private val CouchyTypography = Typography(
    displayLarge = TextStyle(fontFamily = InterDisplay, fontSize = 52.sp, lineHeight = 54.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1.8).sp),
    displayMedium = TextStyle(fontFamily = InterDisplay, fontSize = 44.sp, lineHeight = 46.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1.5).sp),
    displaySmall = TextStyle(fontFamily = InterDisplay, fontSize = 38.sp, lineHeight = 41.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1.2).sp),
    headlineLarge = TextStyle(fontFamily = InterDisplay, fontSize = 32.sp, lineHeight = 36.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.9).sp),
    headlineMedium = TextStyle(fontFamily = InterDisplay, fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.7).sp),
    headlineSmall = TextStyle(fontFamily = InterDisplay, fontSize = 24.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    titleLarge = TextStyle(fontFamily = InterDisplay, fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontFamily = Inter, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.1).sp),
    titleSmall = TextStyle(fontFamily = Inter, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontFamily = Inter, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Inter, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Inter, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = Inter, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold),
    labelMedium = TextStyle(fontFamily = Inter, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
    labelSmall = TextStyle(fontFamily = Inter, fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp),
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

/** From the sofa small text is hard to read: on a TV the small styles grow by about a sixth. */
@Composable
fun TvReadable(tv: Boolean, content: @Composable () -> Unit) {
    if (!tv) return content()
    val t = MaterialTheme.typography
    fun TextStyle.up() = copy(fontSize = fontSize * 1.15f, lineHeight = lineHeight * 1.15f)
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme,
        shapes = MaterialTheme.shapes,
        typography = t.copy(
            titleSmall = t.titleSmall.up(),
            bodyMedium = t.bodyMedium.up(),
            bodySmall = t.bodySmall.up(),
            labelMedium = t.labelMedium.up(),
            labelSmall = t.labelSmall.up(),
        ),
        content = content,
    )
}

object CouchyTheme {
    val colors: CouchyColors @Composable get() = LocalCouchyColors.current
}
