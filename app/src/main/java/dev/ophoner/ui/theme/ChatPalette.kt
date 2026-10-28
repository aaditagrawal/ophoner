package dev.ophoner.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * Chat surfaces derived from [OphonerTokens]. Assistant replies sit directly on the page
 * (no bubble) — deliberate empty space; user turns get a soft accent-tinted bubble.
 */
data class ChatPalette(
    val userBubble: Color,
    val userBubbleBorder: Color,
    val onUserBubble: Color,
    val onAssistant: Color,
    val toolCardBg: Color,
    val toolCardBorder: Color,
    val codeBlockBg: Color,
    val codeText: Color,
    val mathText: Color,
    val mathBg: Color,
    val tableHeaderBg: Color,
    val tableRowAltBg: Color,
)

@Composable
@ReadOnlyComposable
fun chatPalette(): ChatPalette {
    val t = tokens()
    return ChatPalette(
        userBubble = t.active,
        userBubbleBorder = t.activeBorder,
        onUserBubble = t.ink,
        onAssistant = t.ink,
        toolCardBg = t.panel,
        toolCardBorder = t.line,
        codeBlockBg = t.code,
        codeText = t.ink,
        mathText = t.accent,
        mathBg = t.active,
        tableHeaderBg = t.panelHigh,
        tableRowAltBg = t.panel,
    )
}
