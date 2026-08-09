package dev.ophoner.ui.theme

import androidx.compose.ui.graphics.Color

// Teenage Engineering / morphic instrument panel neutrals.
// Dark = warm coal. Light = cool paper-gray (not cream).
// Radius discipline elsewhere: prefer 4 / 8 / 12 dp.
// Orange is the brand accent — sharp sparse signal, not glow.

// — Dark — warm coal, not true black
val DarkBackground = Color(0xFF12100E)        // warm coal
val DarkSurface = Color(0xFF1C1916)           // panel tier 1
val DarkSurfaceVariant = Color(0xFF28241F)    // tier 2 — cards, inputs
val DarkBorder = Color(0xFF3D3630)            // hairline separators
val DarkMuted = Color(0xFF1C1916)             // subtle field tone

val TextPrimary = Color(0xFFF0EDE8)           // cool-warm off-white
val TextSecondary = Color(0xFF9A948A)         // muted instrument gray
val TextTertiary = Color(0xFF6E685F)

// — Accents (flat instrument signals) —
val AccentBlue = Color(0xFF0A84FF)
val AccentGreen = Color(0xFF30D158)
val AccentAmber = Color(0xFFFF9F0A)           // brand orange (dark)
val AccentRed = Color(0xFFFF453A)

// — Bubble / card tones (dark) —
val UserBubble = Color(0xFFFF9F0A)            // brand orange fill
val AssistantBubble = Color(0xFF28241F)
val ToolCardBg = Color(0xFF1C1916)
val ToolCardBorder = Color(0xFF3D3630)
val CodeBlockBg = Color(0xFF161412)

// — Accent palette (paired light/dark); ORANGE is the brand default —
data class AccentSwatch(val light: Color, val dark: Color)

enum class AccentChoice(val displayName: String, val swatch: AccentSwatch) {
    ORANGE("Orange", AccentSwatch(Color(0xFFFF9500), Color(0xFFFF9F0A))),
    BLUE("Blue", AccentSwatch(Color(0xFF007AFF), Color(0xFF0A84FF))),
    INDIGO("Indigo", AccentSwatch(Color(0xFF5856D6), Color(0xFF5E5CE6))),
    PURPLE("Purple", AccentSwatch(Color(0xFFAF52DE), Color(0xFFBF5AF2))),
    PINK("Pink", AccentSwatch(Color(0xFFFF2D55), Color(0xFFFF375F))),
    RED("Red", AccentSwatch(Color(0xFFFF3B30), Color(0xFFFF453A))),
    YELLOW("Yellow", AccentSwatch(Color(0xFFFFCC00), Color(0xFFFFD60A))),
    GREEN("Green", AccentSwatch(Color(0xFF34C759), Color(0xFF30D158))),
    MINT("Mint", AccentSwatch(Color(0xFF00C7BE), Color(0xFF63E6E2))),
    TEAL("Teal", AccentSwatch(Color(0xFF30B0C7), Color(0xFF40CBE0))),
    CYAN("Cyan", AccentSwatch(Color(0xFF32ADE6), Color(0xFF64D2FF))),
    GRAPHITE("Graphite", AccentSwatch(Color(0xFF8E8E93), Color(0xFFAEAEB2)));

    fun color(dark: Boolean): Color = if (dark) swatch.dark else swatch.light
}

// — Light — cool paper-gray, never warm cream
val LightBackground = Color(0xFFF3F3F1)       // cool paper
val LightSurface = Color(0xFFEBEBEA)          // panel tier 1
val LightSurfaceVariant = Color(0xFFE0E0DE)   // stone panel
val LightBorder = Color(0xFFD0D0CD)           // cool hairline
val LightMuted = Color(0xFFEBEBEA)

val LightTextPrimary = Color(0xFF1A1A18)      // near-black
val LightTextSecondary = Color(0xFF6A6A66)    // cool gray
val LightTextTertiary = Color(0xFF9A9A96)

val LightAccentBlue = Color(0xFF007AFF)
val LightAccentGreen = Color(0xFF34C759)
val LightAccentAmber = Color(0xFFFF9500)      // brand orange (light)
val LightAccentRed = Color(0xFFFF3B30)

val LightUserBubble = Color(0xFFFF9500)
val LightAssistantBubble = Color(0xFFE4E4E2)
val LightToolCardBg = Color(0xFFEBEBEA)
val LightToolCardBorder = Color(0xFFD0D0CD)
val LightCodeBlockBg = Color(0xFFE8E8E6)
