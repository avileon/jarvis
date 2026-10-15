package il.co.vibit.jarvis.assistant.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp

object JarvisColors {
    val Background = Color(0xFF02060D)
    val Surface = Color(0xFF07131F)
    val SurfaceHigh = Color(0xFF0C1E2E)
    val Cyan = Color(0xFF22E5FF)
    val CyanSoft = Color(0xFF7FF6FF)
    val Blue = Color(0xFF0A84FF)
    val Teal = Color(0xFF00D1B2)
    val Amber = Color(0xFFFFB020)
    val Red = Color(0xFFFF4D5E)
    val Green = Color(0xFF3DFFA2)
    val TextDim = Color(0xFF8AA4B8)
}

private val scheme = darkColorScheme(
    primary = JarvisColors.Cyan,
    onPrimary = Color(0xFF00141A),
    secondary = JarvisColors.Blue,
    tertiary = JarvisColors.Teal,
    background = JarvisColors.Background,
    onBackground = Color(0xFFE6F7FF),
    surface = JarvisColors.Surface,
    onSurface = Color(0xFFE6F7FF),
    surfaceVariant = JarvisColors.SurfaceHigh,
    onSurfaceVariant = JarvisColors.TextDim,
    error = JarvisColors.Red,
    outline = Color(0xFF1E4A5E),
)

private val typography = Typography(
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Light, letterSpacing = 6.sp),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
)

/** Dark JARVIS theme, always right-to-left (Hebrew UI). */
@Composable
fun JarvisTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl, content = content)
    }
}
