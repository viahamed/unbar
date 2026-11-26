package com.breakthebarriers.unbar.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import com.breakthebarriers.unbar.model.AppThemeMode

// 1.0.2 "Cloud" palette: softer, cleaner light and dark themes.
// Logo, icon and website use the same blue and orange.
val UnbarBlue = Color(0xFF1A5BAA)
val UnbarBlueLight = Color(0xFF6E9FDB)
val UnbarOrange = Color(0xFFF7853A)
val UnbarOrangeDark = Color(0xFFF7954A)
val UnbarDarkBg = Color(0xFF101722)
val UnbarDarkSurface = Color(0xFF18212E)
val UnbarDarkSurfaceVariant = Color(0xFF202B3A)
val UnbarLightBg = Color(0xFFF2F6FB)
// Splash background: a slightly lighter shade of the light background.
val UnbarLightSplashBg = Color(0xFFF6F9FD)
val UnbarLightSurface = Color(0xFFFFFFFF)
val UnbarLightSurfaceVariant = Color(0xFFE9F0F8)
// Connected status card and floating menu capsule, per theme.
val UnbarHeroBlueDark = Color(0xFF26548F)
val UnbarCapsuleLight = Color(0xFF173A6B)
val UnbarCapsuleDark = Color(0xFF212C3C)

private val LocalUnbarDark = staticCompositionLocalOf { false }

val UnbarHeroBlue: Color
    @Composable
    @ReadOnlyComposable
    get() = if (LocalUnbarDark.current) UnbarHeroBlueDark else UnbarBlue

val UnbarCapsuleBlue: Color
    @Composable
    @ReadOnlyComposable
    get() = if (LocalUnbarDark.current) UnbarCapsuleDark else UnbarCapsuleLight

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
    primaryContainer = Color(0xFFE2ECF9),
    onPrimaryContainer = Color(0xFF0B2E5A),
    secondary = UnbarOrange,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFEEDF),
    onSecondaryContainer = Color(0xFF6E330A),
    background = UnbarLightBg,
    onBackground = Color(0xFF172338),
    surface = UnbarLightSurface,
    onSurface = Color(0xFF172338),
    surfaceVariant = UnbarLightSurfaceVariant,
    onSurfaceVariant = Color(0xFF5B6B82),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF6F9FD),
    surfaceContainer = Color(0xFFEEF3F9),
    surfaceContainerHigh = Color(0xFFE9F0F8),
    surfaceContainerHighest = Color(0xFFE2EAF4),
    surfaceDim = Color(0xFFDCE5F0),
    surfaceBright = Color(0xFFFFFFFF),
    outline = Color(0xFFDCE5F0),
    error = UnbarOrange,
    onError = Color.White
)

private val DarkColors = darkColorScheme(
    primary = UnbarBlueLight,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1E3352),
    onPrimaryContainer = Color.White,
    secondary = UnbarOrangeDark,
    onSecondary = Color(0xFF1F0C00),
    secondaryContainer = Color(0xFF3B2716),
    onSecondaryContainer = Color(0xFFFFD3B2),
    background = UnbarDarkBg,
    onBackground = Color(0xFFE3EAF3),
    surface = UnbarDarkSurface,
    onSurface = Color(0xFFE3EAF3),
    surfaceVariant = UnbarDarkSurfaceVariant,
    onSurfaceVariant = Color(0xFF8FA0B6),
    surfaceContainerLowest = Color(0xFF0C121B),
    surfaceContainerLow = Color(0xFF141C28),
    surfaceContainer = Color(0xFF18212E),
    surfaceContainerHigh = Color(0xFF202B3A),
    surfaceContainerHighest = Color(0xFF283445),
    surfaceDim = Color(0xFF0C121B),
    surfaceBright = Color(0xFF263142),
    outline = Color(0xFF273345),
    error = UnbarOrangeDark,
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

    CompositionLocalProvider(LocalUnbarDark provides darkTheme) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = UnbarTypography,
            content = content
        )
    }
}

// (1.0.2) UX rule, app-wide: text is centred in its line box, so an
// icon beside a label, or a label inside a chip, button or pill, has
// equal space above and below. Same sizes as Material 3's defaults.
private val CenteredLine = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None
)

private fun TextStyle.centred(): TextStyle = copy(lineHeightStyle = CenteredLine)

private val UnbarTypography: Typography = Typography().let { t ->
    Typography(
        displayLarge = t.displayLarge.centred(),
        displayMedium = t.displayMedium.centred(),
        displaySmall = t.displaySmall.centred(),
        headlineLarge = t.headlineLarge.centred(),
        headlineMedium = t.headlineMedium.centred(),
        headlineSmall = t.headlineSmall.centred(),
        titleLarge = t.titleLarge.centred(),
        titleMedium = t.titleMedium.centred(),
        titleSmall = t.titleSmall.centred(),
        bodyLarge = t.bodyLarge.centred(),
        bodyMedium = t.bodyMedium.centred(),
        bodySmall = t.bodySmall.centred(),
        labelLarge = t.labelLarge.centred(),
        labelMedium = t.labelMedium.centred(),
        labelSmall = t.labelSmall.centred()
    )
}
