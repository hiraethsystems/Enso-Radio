package systems.hiraeth.dxgarden

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/*
 * DX Garden's own colours: night-sky blues with the amber of the place
 * dots (earth.c) as the accent, and a sea-green second colour. Or the
 * phone's Material You colours, if chosen in Settings.
 */

private val DxgDark = darkColorScheme(
    primary = Color(0xFFF4B04A),
    onPrimary = Color(0xFF3A2300),
    primaryContainer = Color(0xFF5A3A00),
    onPrimaryContainer = Color(0xFFFFDDAF),
    secondary = Color(0xFF7FCFC8),
    onSecondary = Color(0xFF003734),
    secondaryContainer = Color(0xFF1C4A47),
    onSecondaryContainer = Color(0xFFA6EEE6),
    tertiary = Color(0xFF9FC2FF),
    onTertiary = Color(0xFF0A2F5F),
    background = Color(0xFF0A0D14),
    onBackground = Color(0xFFE3E6EE),
    surface = Color(0xFF0E121A),
    onSurface = Color(0xFFE3E6EE),
    surfaceVariant = Color(0xFF2A303C),
    onSurfaceVariant = Color(0xFFBEC4D2),
    surfaceContainerLowest = Color(0xFF080B11),
    surfaceContainerLow = Color(0xFF131824),
    surfaceContainer = Color(0xFF171D2A),
    surfaceContainerHigh = Color(0xFF1E2533),
    surfaceContainerHighest = Color(0xFF262E3E),
    outline = Color(0xFF8A92A3),
    outlineVariant = Color(0xFF3A4252),
)

private val DxgLight = lightColorScheme(
    primary = Color(0xFF825500),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDDAF),
    onPrimaryContainer = Color(0xFF2A1800),
    secondary = Color(0xFF006A64),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFA6EEE6),
    onSecondaryContainer = Color(0xFF00201E),
    tertiary = Color(0xFF34609E),
    background = Color(0xFFF7F8FC),
    onBackground = Color(0xFF181C24),
    surface = Color(0xFFF7F8FC),
    onSurface = Color(0xFF181C24),
    surfaceVariant = Color(0xFFDDE2EE),
    onSurfaceVariant = Color(0xFF424856),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F2F8),
    surfaceContainer = Color(0xFFEAEDF4),
    surfaceContainerHigh = Color(0xFFE4E7EF),
    surfaceContainerHighest = Color(0xFFDEE2EA),
    outline = Color(0xFF727887),
    outlineVariant = Color(0xFFC2C7D3),
)

@Composable
fun DxgTheme(content: @Composable () -> Unit) {
    val dark = when (Prefs.theme) {
        Prefs.THEME_LIGHT -> false
        Prefs.THEME_DARK -> true
        else -> isSystemInDarkTheme()
    }
    val context = LocalContext.current
    val scheme: ColorScheme = if (Prefs.colours == Prefs.COLOURS_PHONE) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (dark) DxgDark else DxgLight
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
