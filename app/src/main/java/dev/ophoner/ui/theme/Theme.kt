package dev.ophoner.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

enum class ThemeMode(val displayName: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark");
}

/** Exposes whether we're rendering in dark theme so other surfaces can tone themselves. */
val LocalIsDark = compositionLocalOf { false }

/** The active accent color (already resolved for the current theme mode). Brand default: orange. */
val LocalAccent = compositionLocalOf { Color(0xFFFF9500) }

@Composable
@ReadOnlyComposable
fun isDarkTheme(): Boolean = LocalIsDark.current

@Composable
@ReadOnlyComposable
fun accentColor(): Color = LocalAccent.current

private fun highContrastOn(container: Color, background: Color): Color {
    val resolved = container.compositeOver(background)
    return if (resolved.luminance() > 0.5f) Color.Black else Color.White
}

private fun buildDarkScheme(accent: Color) = run {
    val primaryContainer = accent.copy(alpha = 0.18f)
    val secondaryContainer = AccentGreen.copy(alpha = 0.18f)
    darkColorScheme(
        primary = accent,
        onPrimary = Color.White,
        primaryContainer = primaryContainer,
        onPrimaryContainer = highContrastOn(primaryContainer, DarkBackground),
        secondary = AccentGreen,
        onSecondary = Color.White,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = highContrastOn(secondaryContainer, DarkBackground),
        tertiary = AccentAmber,
        onTertiary = Color.Black,
        background = DarkBackground,
        onBackground = TextPrimary,
        surface = DarkBackground,
        onSurface = TextPrimary,
        surfaceVariant = DarkSurfaceVariant,
        onSurfaceVariant = TextSecondary,
        surfaceContainerLowest = DarkBackground,
        surfaceContainerLow = DarkSurface,
        surfaceContainer = DarkSurface,
        surfaceContainerHigh = DarkSurfaceVariant,
        surfaceContainerHighest = DarkSurfaceVariant,
        outline = DarkBorder,
        outlineVariant = DarkMuted,
        error = AccentRed,
        onError = Color.White,
    )
}

private fun buildLightScheme(accent: Color) = run {
    val primaryContainer = accent.copy(alpha = 0.12f)
    val secondaryContainer = LightAccentGreen.copy(alpha = 0.12f)
    lightColorScheme(
        primary = accent,
        onPrimary = Color.White,
        primaryContainer = primaryContainer,
        onPrimaryContainer = highContrastOn(primaryContainer, LightBackground),
        secondary = LightAccentGreen,
        onSecondary = Color.White,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = highContrastOn(secondaryContainer, LightBackground),
        tertiary = LightAccentAmber,
        onTertiary = Color.White,
        background = LightBackground,
        onBackground = LightTextPrimary,
        surface = LightBackground,
        onSurface = LightTextPrimary,
        surfaceVariant = LightSurfaceVariant,
        onSurfaceVariant = LightTextSecondary,
        surfaceContainerLowest = LightBackground,
        surfaceContainerLow = LightSurface,
        surfaceContainer = LightSurface,
        surfaceContainerHigh = LightSurfaceVariant,
        surfaceContainerHighest = LightSurfaceVariant,
        outline = LightBorder,
        outlineVariant = LightMuted,
        error = LightAccentRed,
        onError = Color.White,
    )
}

@Composable
fun OphoneTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    uiFont: UiFont = UiFont.DM_MONO,
    accent: AccentChoice = AccentChoice.ORANGE,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val accentColor = accent.color(darkTheme)
    val colorScheme = if (darkTheme) buildDarkScheme(accentColor) else buildLightScheme(accentColor)

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    CompositionLocalProvider(
        LocalMonoFontFamily provides DmMono,
        LocalIsDark provides darkTheme,
        LocalAccent provides accentColor,
    ) {
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            motionScheme = MotionScheme.expressive(),
            typography = ophoneTypography(uiFont.toFontFamily(), uiFont.isMono),
            content = content,
        )
    }
}
