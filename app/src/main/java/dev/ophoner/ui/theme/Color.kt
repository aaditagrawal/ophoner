package dev.ophoner.ui.theme

import androidx.compose.ui.graphics.Color

// iOS-inspired neutrals — soft, airy, and tonally consistent across modes.
// Reference: Apple HIG system colors (systemBackground, systemGray*, systemBlue).

// — Dark —
val DarkBackground = Color(0xFF000000)        // True black for OLED comfort
val DarkSurface = Color(0xFF1C1C1E)           // Grouped background tier 1
val DarkSurfaceVariant = Color(0xFF2C2C2E)    // Tier 2 — cards, inputs
val DarkBorder = Color(0xFF3A3A3C)            // Hairline separators
val DarkMuted = Color(0xFF1C1C1E)             // Subtle field tone

val TextPrimary = Color(0xFFF2F2F7)
val TextSecondary = Color(0xFF8E8E93)
val TextTertiary = Color(0xFF636366)

// — Accents (iOS system colors, dark variants) —
val AccentBlue = Color(0xFF0A84FF)            // systemBlue (dark)
val AccentGreen = Color(0xFF30D158)           // systemGreen (dark)
val AccentAmber = Color(0xFFFF9F0A)           // systemOrange (dark)
val AccentRed = Color(0xFFFF453A)             // systemRed (dark)

// — Bubble / card tones (dark) —
val UserBubble = Color(0xFF0A84FF)            // iMessage blue
val AssistantBubble = Color(0xFF1C1C1E)
val ToolCardBg = Color(0xFF1C1C1E)
val ToolCardBorder = Color(0xFF3A3A3C)
val CodeBlockBg = Color(0xFF1C1C1E)

// — Accent palette (iOS system colors, paired light/dark) —
data class AccentSwatch(val light: Color, val dark: Color)

enum class AccentChoice(val displayName: String, val swatch: AccentSwatch) {
    BLUE("Blue", AccentSwatch(Color(0xFF007AFF), Color(0xFF0A84FF))),
    INDIGO("Indigo", AccentSwatch(Color(0xFF5856D6), Color(0xFF5E5CE6))),
    PURPLE("Purple", AccentSwatch(Color(0xFFAF52DE), Color(0xFFBF5AF2))),
    PINK("Pink", AccentSwatch(Color(0xFFFF2D55), Color(0xFFFF375F))),
    RED("Red", AccentSwatch(Color(0xFFFF3B30), Color(0xFFFF453A))),
    ORANGE("Orange", AccentSwatch(Color(0xFFFF9500), Color(0xFFFF9F0A))),
    YELLOW("Yellow", AccentSwatch(Color(0xFFFFCC00), Color(0xFFFFD60A))),
    GREEN("Green", AccentSwatch(Color(0xFF34C759), Color(0xFF30D158))),
    MINT("Mint", AccentSwatch(Color(0xFF00C7BE), Color(0xFF63E6E2))),
    TEAL("Teal", AccentSwatch(Color(0xFF30B0C7), Color(0xFF40CBE0))),
    CYAN("Cyan", AccentSwatch(Color(0xFF32ADE6), Color(0xFF64D2FF))),
    GRAPHITE("Graphite", AccentSwatch(Color(0xFF8E8E93), Color(0xFFAEAEB2)));

    fun color(dark: Boolean): Color = if (dark) swatch.dark else swatch.light
}

// — Light —
val LightBackground = Color(0xFFFFFFFF)       // systemBackground
val LightSurface = Color(0xFFF2F2F7)          // secondarySystemBackground (grouped tier 1)
val LightSurfaceVariant = Color(0xFFE5E5EA)   // systemGray5
val LightBorder = Color(0xFFD1D1D6)           // systemGray4
val LightMuted = Color(0xFFF2F2F7)

val LightTextPrimary = Color(0xFF000000)
val LightTextSecondary = Color(0xFF6C6C70)
val LightTextTertiary = Color(0xFFAEAEB2)

val LightAccentBlue = Color(0xFF007AFF)
val LightAccentGreen = Color(0xFF34C759)
val LightAccentAmber = Color(0xFFFF9500)
val LightAccentRed = Color(0xFFFF3B30)

val LightUserBubble = Color(0xFF007AFF)
val LightAssistantBubble = Color(0xFFE9E9EB)
val LightToolCardBg = Color(0xFFF2F2F7)
val LightToolCardBorder = Color(0xFFD1D1D6)
val LightCodeBlockBg = Color(0xFFF2F2F7)
