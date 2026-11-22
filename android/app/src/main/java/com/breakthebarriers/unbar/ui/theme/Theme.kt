package com.breakthebarriers.unbar.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.breakthebarriers.unbar.model.AppThemeMode

val UnbarBlue = Color(0xFF0B4F9E)
val UnbarBlueLight = Color(0xFF3E7FD1)
val UnbarOrange = Color(0xFFFF7A1A)
val UnbarDarkBg = Color(0xFF0B1320)
val UnbarDarkSurface = Color(0xFF131E32)
val UnbarDarkSurfaceVariant = Color(0xFF1B2942)
// Light background (before-release #3, option A):
// darker so white cards and borders read as clearly as in dark.
val UnbarLightBg = Color(0xFFEAF0F8)
// Splash background: the pre-1.0.29 light background. The splash
// keeps the old lighter shade; only the main screens (Home and the
// other pages) use the darker UnbarLightBg.
val UnbarLightSplashBg = Color(0xFFF6F8FC)
val UnbarLightSurface = Color(0xFFFFFFFF)
val UnbarLightSurfaceVariant = Color(0xFFEAF0F8)

// Brand palette is ONLY blue (logo blue and its shades) and orange
// (logo orange and its shades), in light and dark forms. No other
// colour is used anywhere in the app — in particular the Material 3
// defaults must NOT leak in: the baseline surfaceContainer colours
// are purple/lavender-tinted and showed up pink in the bottom
// navigation bar. Every surface container tone is therefore set
// explicitly to a blue-tinted neutral below.

private val LightColors = lightColorScheme(
    primary = UnbarBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9E6F8),
    onPrimaryContainer = Color(0xFF062954),
    secondary = UnbarOrange,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE4D1),
    onSecondaryContainer = Color(0xFF5A2500),
    background = UnbarLightBg,
    onBackground = Color(0xFF0F172A),
    surface = UnbarLightSurface,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = UnbarLightSurfaceVariant,
    onSurfaceVariant = Color(0xFF475569),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFEFF4FB),
    surfaceContainer = Color(0xFFE6EDF7),
    surfaceContainerHigh = Color(0xFFDCE6F3),
    surfaceContainerHighest = Color(0xFFD2DFF0),
    surfaceDim = Color(0xFFD9E2EE),
    surfaceBright = Color(0xFFFFFFFF),
    outline = Color(0xFFCBD5E1),
    error = UnbarOrange,
    onError = Color.White
)

private val DarkColors = darkColorScheme(
    primary = UnbarBlueLight,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF0B4F9E),
    onPrimaryContainer = Color.White,
    secondary = UnbarOrange,
    onSecondary = Color(0xFF1F0C00),
    secondaryContainer = Color(0xFF4D2200),
    onSecondaryContainer = Color(0xFFFFD6BA),
    background = UnbarDarkBg,
    onBackground = Color(0xFFF1F5F9),
    surface = UnbarDarkSurface,
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = UnbarDarkSurfaceVariant,
    onSurfaceVariant = Color(0xFF94A3B8),
    surfaceContainerLowest = Color(0xFF070D18),
    surfaceContainerLow = Color(0xFF0E1728),
    surfaceContainer = Color(0xFF131E32),
    surfaceContainerHigh = Color(0xFF1B2942),
    surfaceContainerHighest = Color(0xFF243449),
    surfaceDim = Color(0xFF070D18),
    surfaceBright = Color(0xFF1E2C47),
    outline = Color(0xFF2B3B59),
    error = UnbarOrange,
    onError = Color(0xFF1F0C00)
)

@Composable
fun UnbarTheme(
    themeMode: AppThemeMode,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
    }

    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography(),
        content = content
    )
}
