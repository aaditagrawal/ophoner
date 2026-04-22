package dev.ophoner.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = AccentGreen,
    onPrimary = DarkBackground,
    primaryContainer = AccentGreen.copy(alpha = 0.15f),
    onPrimaryContainer = AccentGreen,
    secondary = AccentBlue,
    onSecondary = DarkBackground,
    secondaryContainer = AccentBlue.copy(alpha = 0.15f),
    onSecondaryContainer = AccentBlue,
    tertiary = AccentAmber,
    onTertiary = DarkBackground,
    background = DarkBackground,
    onBackground = TextPrimary,
    surface = DarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    outline = DarkBorder,
    outlineVariant = DarkMuted,
    error = AccentRed,
    onError = TextPrimary,
)

private val LightColorScheme = lightColorScheme(
    primary = AccentGreen,
    onPrimary = LightBackground,
    primaryContainer = AccentGreen.copy(alpha = 0.1f),
    onPrimaryContainer = AccentGreen,
    secondary = AccentBlue,
    onSecondary = LightBackground,
    secondaryContainer = AccentBlue.copy(alpha = 0.1f),
    onSecondaryContainer = AccentBlue,
    tertiary = AccentAmber,
    onTertiary = LightBackground,
    background = LightBackground,
    onBackground = LightTextPrimary,
    surface = LightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightTextSecondary,
    outline = LightBorder,
    outlineVariant = LightMuted,
    error = AccentRed,
    onError = LightBackground,
)

@Composable
fun OphoneTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        typography = OphoneTypography,
        content = content,
    )
}
