package dev.ophoner.ui.theme

import androidx.compose.ui.graphics.Color

// AndBrowser aesthetic: predominantly neutral surfaces, deliberate empty space,
// frosted floating controls, and colour reserved for functional accents.
// Radius discipline: 12 (inner) / 20 (cards, rows) / 28 (sheets, dialogs) / full (capsules).

// — Light —
val LightInk = Color(0xFF1B1D21)
val LightSecondary = Color(0xFF6A6E77)
val LightBackground = Color(0xFFFAFAFC)
val LightLine = Color(0x1A232837)             // rgba(35,40,55,0.10)
val LightLineStrong = Color(0x2E232837)       // field borders need a touch more contrast
val LightSurface = Color(0x52FFFFFF)          // rgba(255,255,255,0.32)
val LightSurfaceStrong = Color(0x94FFFFFF)    // rgba(255,255,255,0.58)
val LightIcon = Color(0xFF424958)
val LightMutedIcon = Color(0xFF707786)
val LightInverse = Color(0xFFFFFFFF)
val LightDanger = Color(0xFFA34B43)
val LightSuccess = Color(0xFF2F7D55)
val LightWarning = Color(0xFFB45309)

// Opaque panel tiers — translucent tokens don't read on a near-white page.
val LightPanel = Color(0xFFF2F3F7)            // cards, rows
val LightPanelHigh = Color(0xFFE9EBF1)        // segmented tracks, code
val LightRaised = Color(0xFFFFFFFF)           // selected segment, dialogs
val LightCode = Color(0xFFF0F2F6)

// — Dark —
val DarkInk = Color(0xFFF1F2F5)
val DarkSecondary = Color(0xFFB1B5C0)
val DarkBackground = Color(0xFF15171D)
val DarkLine = Color(0x1AEBF0FF)              // rgba(235,240,255,0.10)
val DarkLineStrong = Color(0x2EEBF0FF)
val DarkSurface = Color(0x0BFFFFFF)           // rgba(255,255,255,0.045)
val DarkSurfaceStrong = Color(0x13FFFFFF)     // rgba(255,255,255,0.075)
val DarkIcon = Color(0xFFD8DDE7)
val DarkMutedIcon = Color(0xFFA3ABB9)
val DarkInverse = Color(0xFF1B1D21)
val DarkDanger = Color(0xFFF0A49D)
val DarkSuccess = Color(0xFF8FD3AE)
val DarkWarning = Color(0xFFF2B872)

val DarkPanel = Color(0xFF1C1F26)
val DarkPanelHigh = Color(0xFF232731)
val DarkRaised = Color(0xFF252933)            // AndBrowser selected-tab tone
val DarkCode = Color(0xFF191C22)

// — Start-page gradient (muted orange-red, diagonal) —
val LightStartGradient = listOf(
    Color(0xFFFFFFFF), Color(0xFFFCF3EC), Color(0xFFF8E3CF), Color(0xFFF3CFB9), Color(0xFFEEC0AD),
)
val DarkStartGradient = listOf(
    Color(0xFF15171D), Color(0xFF231C19), Color(0xFF35251C), Color(0xFF40281F), Color(0xFF492720),
)
val StartGradientStops = listOf(0f, 0.32f, 0.62f, 0.82f, 1f)

// — Accent palette (paired light/dark); BLUE is the AndBrowser default —
data class AccentSwatch(val light: Color, val dark: Color)

enum class AccentChoice(val displayName: String, val swatch: AccentSwatch) {
    BLUE("Blue", AccentSwatch(Color(0xFF176ED2), Color(0xFF86BBFF))),
    INDIGO("Indigo", AccentSwatch(Color(0xFF4F46E5), Color(0xFFA5B4FC))),
    PURPLE("Purple", AccentSwatch(Color(0xFF7C3AED), Color(0xFFC4B5FD))),
    PINK("Pink", AccentSwatch(Color(0xFFBE185D), Color(0xFFF9A8D4))),
    RED("Red", AccentSwatch(Color(0xFFB91C1C), Color(0xFFFCA5A5))),
    ORANGE("Orange", AccentSwatch(Color(0xFFB45309), Color(0xFFFDBA74))),
    YELLOW("Yellow", AccentSwatch(Color(0xFFA16207), Color(0xFFFDE047))),
    GREEN("Green", AccentSwatch(Color(0xFF15803D), Color(0xFF86EFAC))),
    MINT("Mint", AccentSwatch(Color(0xFF0F766E), Color(0xFF5EEAD4))),
    TEAL("Teal", AccentSwatch(Color(0xFF0E7490), Color(0xFF67E8F9))),
    CYAN("Cyan", AccentSwatch(Color(0xFF0369A1), Color(0xFF7DD3FC))),
    GRAPHITE("Graphite", AccentSwatch(Color(0xFF424958), Color(0xFFD8DDE7)));

    fun color(dark: Boolean): Color = if (dark) swatch.dark else swatch.light
}

/** Tab-group palette from AndBrowser — used for folder/project marks. */
val GroupPalette = listOf(
    Color(0xFF2563EB), Color(0xFF7C3AED), Color(0xFFB45309),
    Color(0xFF15803D), Color(0xFFBE185D), Color(0xFF0F766E),
)
