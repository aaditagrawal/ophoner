package dev.ophoner.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

enum class ThemeMode(val displayName: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark");
}

/**
 * AndBrowser design tokens. Prefer these over raw Material roles in app surfaces —
 * Material roles are mapped from them for stock components (dialogs, fields, menus).
 */
@Immutable
data class OphonerTokens(
    val dark: Boolean,
    val ink: Color,
    val secondary: Color,
    val background: Color,
    val line: Color,
    val lineStrong: Color,
    val accent: Color,
    val onAccent: Color,
    val surface: Color,
    val surfaceStrong: Color,
    val panel: Color,
    val panelHigh: Color,
    val raised: Color,
    val code: Color,
    val active: Color,
    val activeBorder: Color,
    val icon: Color,
    val mutedIcon: Color,
    val inverse: Color,
    val danger: Color,
    val success: Color,
    val warning: Color,
    val startGradient: List<Color>,
)

private fun tokensFor(dark: Boolean, accent: Color): OphonerTokens = if (dark) {
    OphonerTokens(
        dark = true,
        ink = DarkInk,
        secondary = DarkSecondary,
        background = DarkBackground,
        line = DarkLine,
        lineStrong = DarkLineStrong,
        accent = accent,
        onAccent = onColorFor(accent),
        surface = DarkSurface,
        surfaceStrong = DarkSurfaceStrong,
        panel = DarkPanel,
        panelHigh = DarkPanelHigh,
        raised = DarkRaised,
        code = DarkCode,
        active = accent.copy(alpha = 0.20f),
        activeBorder = accent.copy(alpha = 0.26f),
        icon = DarkIcon,
        mutedIcon = DarkMutedIcon,
        inverse = DarkInverse,
        danger = DarkDanger,
        success = DarkSuccess,
        warning = DarkWarning,
        startGradient = accentGradient(DarkBackground, accent, dark = true),
    )
} else {
    OphonerTokens(
        dark = false,
        ink = LightInk,
        secondary = LightSecondary,
        background = LightBackground,
        line = LightLine,
        lineStrong = LightLineStrong,
        accent = accent,
        onAccent = onColorFor(accent),
        surface = LightSurface,
        surfaceStrong = LightSurfaceStrong,
        panel = LightPanel,
        panelHigh = LightPanelHigh,
        raised = LightRaised,
        code = LightCode,
        active = accent.copy(alpha = 0.11f),
        activeBorder = accent.copy(alpha = 0.24f),
        icon = LightIcon,
        mutedIcon = LightMutedIcon,
        inverse = LightInverse,
        danger = LightDanger,
        success = LightSuccess,
        warning = LightWarning,
        startGradient = accentGradient(LightBackground, accent, dark = false),
    )
}

/**
 * Home-page wash derived from the chosen accent so the start page always matches Settings:
 * near-neutral at the top-left, deepening diagonally towards the accent.
 */
private fun accentGradient(background: Color, accent: Color, dark: Boolean): List<Color> {
    val stops = if (dark) listOf(0f, 0.07f, 0.14f, 0.20f, 0.27f) else listOf(0f, 0.05f, 0.11f, 0.17f, 0.24f)
    val base = if (dark) background else Color.White
    return stops.map { lerp(base, accent, it) }
}

private fun onColorFor(color: Color): Color =
    if (color.luminance() > 0.45f) LightInk else Color.White

val LocalTokens = staticCompositionLocalOf { tokensFor(dark = false, accent = AccentChoice.BLUE.swatch.light) }

/** Exposes whether we're rendering in dark theme so other surfaces can tone themselves. */
val LocalIsDark = compositionLocalOf { false }

/** The active accent color (already resolved for the current theme mode). */
val LocalAccent = compositionLocalOf { AccentChoice.BLUE.swatch.light }

@Composable
@ReadOnlyComposable
fun tokens(): OphonerTokens = LocalTokens.current

@Composable
@ReadOnlyComposable
fun isDarkTheme(): Boolean = LocalIsDark.current

@Composable
@ReadOnlyComposable
fun accentColor(): Color = LocalAccent.current

private fun colorSchemeFor(t: OphonerTokens) = if (t.dark) {
    darkColorScheme(
        primary = t.accent,
        onPrimary = t.onAccent,
        primaryContainer = t.active,
        onPrimaryContainer = t.ink,
        secondary = t.success,
        onSecondary = t.inverse,
        secondaryContainer = t.panelHigh,
        onSecondaryContainer = t.ink,
        tertiary = t.warning,
        onTertiary = t.inverse,
        background = t.background,
        onBackground = t.ink,
        surface = t.background,
        onSurface = t.ink,
        surfaceVariant = t.panelHigh,
        onSurfaceVariant = t.secondary,
        surfaceContainerLowest = t.background,
        surfaceContainerLow = t.panel,
        surfaceContainer = t.panel,
        surfaceContainerHigh = t.raised,
        surfaceContainerHighest = t.panelHigh,
        inverseSurface = t.ink,
        inverseOnSurface = t.inverse,
        inversePrimary = AccentChoice.BLUE.swatch.light,
        outline = t.lineStrong,
        outlineVariant = t.line,
        error = t.danger,
        onError = t.inverse,
        scrim = Color.Black.copy(alpha = 0.3f),
    )
} else {
    lightColorScheme(
        primary = t.accent,
        onPrimary = t.onAccent,
        primaryContainer = t.active,
        onPrimaryContainer = t.ink,
        secondary = t.success,
        onSecondary = t.inverse,
        secondaryContainer = t.panelHigh,
        onSecondaryContainer = t.ink,
        tertiary = t.warning,
        onTertiary = t.inverse,
        background = t.background,
        onBackground = t.ink,
        surface = t.background,
        onSurface = t.ink,
        surfaceVariant = t.panelHigh,
        onSurfaceVariant = t.secondary,
        surfaceContainerLowest = t.raised,
        surfaceContainerLow = t.panel,
        surfaceContainer = t.panel,
        surfaceContainerHigh = t.raised,
        surfaceContainerHighest = t.panelHigh,
        inverseSurface = t.ink,
        inverseOnSurface = t.inverse,
        inversePrimary = AccentChoice.BLUE.swatch.dark,
        outline = t.lineStrong,
        outlineVariant = t.line,
        error = t.danger,
        onError = t.inverse,
        scrim = Color(0xFF181E2F).copy(alpha = 0.12f),
    )
}

private fun Density.scaledText(scale: Float): Density =
    if (scale == 1f) this else Density(density = density, fontScale = fontScale * scale)

/** Generous, soft radii: fields and menus 14, cards 20, sheets and dialogs 28. */
private val OphonerShapes = Shapes(
    extraSmall = RoundedCornerShape(14.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun OphoneTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    uiFont: UiFont = UiFont.DM_SANS,
    accent: AccentChoice = AccentChoice.BLUE,
    uiPrefs: UiPrefs = UiPrefs(),
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val accentColor = accent.color(darkTheme)
    val tokens = tokensFor(darkTheme, accentColor)

    val density = LocalDensity.current
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
        LocalTokens provides tokens,
        LocalUiPrefs provides uiPrefs,
        LocalDensity provides density.scaledText(uiPrefs.textSize.scale),
    ) {
        MaterialExpressiveTheme(
            colorScheme = colorSchemeFor(tokens),
            motionScheme = MotionScheme.expressive(),
            shapes = OphonerShapes,
            typography = ophoneTypography(uiFont.toFontFamily(), uiFont.isMono),
            content = content,
        )
    }
}
