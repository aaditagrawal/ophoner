package dev.ophoner.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import dev.ophoner.R

val DmSans = FontFamily(
    Font(R.font.dm_sans_light, FontWeight.Light),
    Font(R.font.dm_sans_regular, FontWeight.Normal),
    Font(R.font.dm_sans_medium, FontWeight.Medium),
    Font(R.font.dm_sans_semibold, FontWeight.SemiBold),
    Font(R.font.dm_sans_bold, FontWeight.Bold),
)

/** DM Mono ships Light / Regular / Medium — map heavier weights to Medium. */
val DmMono = FontFamily(
    Font(R.font.dm_mono_light, FontWeight.Light),
    Font(R.font.dm_mono_regular, FontWeight.Normal),
    Font(R.font.dm_mono_medium, FontWeight.Medium),
    Font(R.font.dm_mono_medium, FontWeight.SemiBold),
    Font(R.font.dm_mono_medium, FontWeight.Bold),
)

/** User-selectable UI font. Mono is the brand default; code blocks are always mono. */
enum class UiFont(val displayName: String) {
    DM_MONO("DM Mono"),
    DM_SANS("DM Sans"),
    SYSTEM("System");

    fun toFontFamily(): FontFamily = when (this) {
        DM_MONO -> DmMono
        DM_SANS -> DmSans
        SYSTEM -> FontFamily.Default
    }

    val isMono: Boolean get() = this == DM_MONO

    companion object {
        /** Resolves stored prefs, including legacy Geist values. */
        fun parse(raw: String): UiFont = when (raw) {
            "GEIST_MONO" -> DM_MONO
            "GEIST_SANS" -> DM_SANS
            else -> runCatching { valueOf(raw) }.getOrDefault(DM_MONO)
        }
    }
}

/**
 * Tightly-tuned type scale parameterized by the chosen UI font.
 *
 * Refinements over the default Material scale:
 *  - `includeFontPadding = false` removes Android's legacy phantom top/bottom padding,
 *    so text sits flush in its container instead of floating.
 *  - `LineHeightStyle(alignment = Center, trim = None)` keeps Compose's measured
 *    line box intact (Trim.Both was too aggressive with DM metrics).
 *  - Mono fonts get near-zero tracking — proportional label tracking makes mono
 *    read as loose; instrument-panel UI wants tight monospace.
 */
fun ophoneTypography(uiFamily: FontFamily, isMono: Boolean): Typography {
    val platform = PlatformTextStyle(includeFontPadding = false)
    val lineHeight = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.None,
    )
    // Mono fonts are already evenly spaced; positive tracking just smears them out.
    // Collapse positive label tracking to 0 in mono mode; keep negative display tracking.
    fun trk(sans: Double): Double = when {
        !isMono -> sans
        sans > 0.0 -> 0.0
        else -> sans
    }
    fun s(weight: FontWeight, size: Int, line: Int, tracking: Double = 0.0) = TextStyle(
        fontFamily = uiFamily,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = line.sp,
        letterSpacing = trk(tracking).sp,
        platformStyle = platform,
        lineHeightStyle = lineHeight,
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
val LocalMonoFontFamily = compositionLocalOf { DmMono }

/** Convenience for surfaces that always want mono (code, math, raw JSON). */
@Composable
@ReadOnlyComposable
fun monoFamily(): FontFamily = LocalMonoFontFamily.current
