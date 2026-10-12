package dev.ophoner.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

/** User personalisation that shapes the look of the app beyond theme / accent / font. */
@Immutable
data class UiPrefs(
    val mascotEnabled: Boolean = true,
    val mascotStyle: MascotStyle = MascotStyle.SOFT,
    val homeBackground: HomeBackground = HomeBackground.GRADIENT,
    val glass: GlassStyle = GlassStyle.FROSTED,
    val textSize: TextSize = TextSize.DEFAULT,
    val showShortcuts: Boolean = true,
)

/** Both styles are flat 2D; Sticker adds a white die-cut edge. */
enum class MascotStyle(val displayName: String) {
    SOFT("Soft"),
    STICKER("Sticker"),
}

enum class HomeBackground(val displayName: String) {
    GRADIENT("Gradient"),
    PLAIN("Plain"),
}

enum class GlassStyle(val displayName: String) {
    FROSTED("Frosted"),
    SOLID("Solid"),
}

enum class TextSize(val displayName: String, val scale: Float) {
    SMALL("Small", 0.92f),
    DEFAULT("Default", 1f),
    LARGE("Large", 1.1f),
}

internal inline fun <reified T : Enum<T>> parseEnum(raw: String?, fallback: T): T =
    raw?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback

val LocalUiPrefs = staticCompositionLocalOf { UiPrefs() }
