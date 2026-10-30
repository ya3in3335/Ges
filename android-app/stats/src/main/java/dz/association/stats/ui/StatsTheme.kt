package dz.association.stats.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dz.association.app.ui.theme.AppShapes
import dz.association.app.ui.theme.AppTypography
import dz.association.app.ui.theme.Brand

/** Census palette (dark), with the association's warm accent from its logo. */
object StatsColors {
    val Background = Color(0xFF101714)
    val Surface = Color(0xFF17231D)
    val SurfaceHigh = Color(0xFF1E2E26)
    val Line = Color(0xFF2A3D33)
    val Primary = Color(0xFF50C89A)
    val PrimaryDeep = Color(0xFF1F5A44)
    val Text = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFFA5B4AB)
    val Accent = Brand.Sun
    val Danger = Color(0xFFFF7A6E)
    val Warning = Color(0xFFF5C451)
    val Info = Color(0xFF6FB7FF)

    /** Distinct, readable series colours for charts on the dark surface. */
    val Series = listOf(Primary, Accent, Info, Color(0xFFE78AC3), Warning, Color(0xFF9F8CFF), Color(0xFF7AD7E0), Danger)
}

private val Scheme = darkColorScheme(
    primary = StatsColors.Primary,
    onPrimary = Color(0xFF00281A),
    primaryContainer = StatsColors.PrimaryDeep,
    onPrimaryContainer = Color(0xFFB9F2D8),
    secondary = StatsColors.Accent,
    onSecondary = Color(0xFF2F1500),
    secondaryContainer = Color(0xFF3A2A17),
    onSecondaryContainer = Color(0xFFFFDCC4),
    tertiary = StatsColors.Info,
    background = StatsColors.Background,
    onBackground = StatsColors.Text,
    surface = StatsColors.Background,
    onSurface = StatsColors.Text,
    surfaceVariant = StatsColors.Surface,
    onSurfaceVariant = StatsColors.TextSecondary,
    surfaceContainerLowest = Color(0xFF0C120F),
    surfaceContainerLow = Color(0xFF141E19),
    surfaceContainer = StatsColors.Surface,
    surfaceContainerHigh = StatsColors.SurfaceHigh,
    surfaceContainerHighest = Color(0xFF243830),
    outline = Color(0xFF5E7268),
    outlineVariant = StatsColors.Line,
    error = StatsColors.Danger,
    onError = Color(0xFF3B0905),
)

@Composable
fun StatsTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = AppTypography, shapes = AppShapes, content = content)
}
