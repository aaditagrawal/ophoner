package dev.ophoner.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.ophoner.R

val GeistSans = FontFamily(
    Font(R.font.geist_light, FontWeight.Light),
    Font(R.font.geist_regular, FontWeight.Normal),
    Font(R.font.geist_medium, FontWeight.Medium),
    Font(R.font.geist_semibold, FontWeight.SemiBold),
    Font(R.font.geist_bold, FontWeight.Bold),
)

val GeistMono = FontFamily(
    Font(R.font.geist_mono_light, FontWeight.Light),
    Font(R.font.geist_mono_regular, FontWeight.Normal),
    Font(R.font.geist_mono_medium, FontWeight.Medium),
    Font(R.font.geist_mono_semibold, FontWeight.SemiBold),
    Font(R.font.geist_mono_bold, FontWeight.Bold),
)

/** User-selectable UI font. Mono affects only text styles, never code blocks (always mono). */
enum class UiFont(val displayName: String) {
    GEIST_SANS("Geist"),
    GEIST_MONO("Geist Mono"),
    SYSTEM("System");

    fun toFontFamily(): FontFamily = when (this) {
        GEIST_SANS -> GeistSans
        GEIST_MONO -> GeistMono
        SYSTEM -> FontFamily.Default
    }
}

/** Lightly-tracked, iOS-tuned type scale parameterized by the chosen UI font. */
fun ophoneTypography(uiFamily: FontFamily): Typography {
    fun s(weight: FontWeight, size: Int, line: Int, tracking: Double = 0.0) = TextStyle(
        fontFamily = uiFamily,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = line.sp,
        letterSpacing = tracking.sp,
    )
    return Typography(
        displayLarge = s(FontWeight.Bold, 32, 40, -0.4),
        displayMedium = s(FontWeight.Bold, 28, 36, -0.3),
        displaySmall = s(FontWeight.SemiBold, 24, 32, -0.2),
        headlineLarge = s(FontWeight.SemiBold, 22, 28, -0.2),
        headlineMedium = s(FontWeight.SemiBold, 20, 26, -0.1),
        headlineSmall = s(FontWeight.SemiBold, 18, 24, -0.1),
        titleLarge = s(FontWeight.SemiBold, 17, 22, -0.1),
        titleMedium = s(FontWeight.SemiBold, 16, 22),
        titleSmall = s(FontWeight.Medium, 14, 20),
        bodyLarge = s(FontWeight.Normal, 16, 22),
        bodyMedium = s(FontWeight.Normal, 15, 20),
        bodySmall = s(FontWeight.Normal, 13, 18),
        labelLarge = s(FontWeight.Medium, 14, 20, 0.1),
        labelMedium = s(FontWeight.Medium, 12, 16, 0.2),
        labelSmall = s(FontWeight.Medium, 11, 14, 0.3),
    )
}

/** Provided by [OphoneTheme]. Use this in code blocks / JSON dumps so the theme drives mono. */
val LocalMonoFontFamily = compositionLocalOf { GeistMono }

/** Convenience for surfaces that always want mono (code, math, raw JSON). */
@Composable
@ReadOnlyComposable
fun monoFamily(): FontFamily = LocalMonoFontFamily.current
