package dz.association.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import dz.association.app.R
import dz.association.app.core.prefs.ThemeMode

// Palette taken from the official logo: tower orange, sun red-orange and the maroon lettering.
object Brand {
    val Sun = Color(0xFFF2832C)
    val Ember = Color(0xFFE8432A)
    val Maroon = Color(0xFF8B2B3D)
    val MaroonDeep = Color(0xFF5E1A28)
    val Night = Color(0xFF170C0F)
}

private val Light = lightColorScheme(
    primary = Brand.Maroon,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD9DE),
    onPrimaryContainer = Color(0xFF3B0712),
    secondary = Color(0xFFB4561B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDCC4),
    onSecondaryContainer = Color(0xFF331200),
    tertiary = Brand.Ember,
    onTertiary = Color.White,
    background = Color(0xFFFFFBFA),
    onBackground = Color(0xFF221A1B),
    surface = Color(0xFFFFFBFA),
    onSurface = Color(0xFF221A1B),
    surfaceVariant = Color(0xFFF4DDDF),
    onSurfaceVariant = Color(0xFF524345),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFFF4F2),
    surfaceContainer = Color(0xFFFBEDEB),
    surfaceContainerHigh = Color(0xFFF5E7E5),
    outline = Color(0xFF857375),
    outlineVariant = Color(0xFFD7C1C3),
    error = Color(0xFFBA1A1A),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFFFB2BD),
    onPrimary = Color(0xFF561D28),
    primaryContainer = Brand.Maroon,
    onPrimaryContainer = Color(0xFFFFD9DE),
    secondary = Color(0xFFFFB68B),
    onSecondary = Color(0xFF532200),
    secondaryContainer = Color(0xFF763300),
    onSecondaryContainer = Color(0xFFFFDCC4),
    tertiary = Brand.Sun,
    onTertiary = Color(0xFF2F1500),
    background = Color(0xFF140D0F),
    onBackground = Color(0xFFEFDFE0),
    surface = Color(0xFF140D0F),
    onSurface = Color(0xFFEFDFE0),
    surfaceVariant = Color(0xFF524345),
    onSurfaceVariant = Color(0xFFD7C1C3),
    surfaceContainerLowest = Color(0xFF0F080A),
    surfaceContainerLow = Color(0xFF1D1517),
    surfaceContainer = Color(0xFF22191B),
    surfaceContainerHigh = Color(0xFF2C2325),
    outline = Color(0xFFA08C8E),
    outlineVariant = Color(0xFF524345),
)

val Plex = FontFamily(
    Font(R.font.ibm_plex_sans_arabic_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_sans_arabic_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_sans_arabic_semibold, FontWeight.SemiBold),
    Font(R.font.ibm_plex_sans_arabic_bold, FontWeight.Bold),
)

private fun style(size: Int, weight: FontWeight, line: Int) = TextStyle(fontFamily = Plex, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp)

// Arabic needs generous line height for diacritics and descenders.
val AppTypography = Typography(
    displaySmall = style(32, FontWeight.Bold, 44),
    headlineLarge = style(28, FontWeight.Bold, 40),
    headlineMedium = style(24, FontWeight.SemiBold, 34),
    headlineSmall = style(21, FontWeight.SemiBold, 30),
    titleLarge = style(19, FontWeight.SemiBold, 28),
    titleMedium = style(16, FontWeight.SemiBold, 24),
    titleSmall = style(14, FontWeight.Medium, 22),
    bodyLarge = style(16, FontWeight.Normal, 27),
    bodyMedium = style(14, FontWeight.Normal, 23),
    bodySmall = style(12, FontWeight.Normal, 19),
    labelLarge = style(14, FontWeight.SemiBold, 20),
    labelMedium = style(12, FontWeight.Medium, 18),
    labelSmall = style(11, FontWeight.Medium, 16),
)

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun AssociationTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = AppTypography, shapes = AppShapes, content = content)
}
