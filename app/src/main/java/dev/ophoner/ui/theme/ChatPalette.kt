package dev.ophoner.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * Theme-aware palette for chat surfaces. Reads LocalIsDark + LocalAccent
 * so light/dark + accent picks propagate without re-plumbing every call site.
 *
 * Tool cards stay flat/solid (hairline border, no glass translucency).
 * User bubbles keep accent fill for turn readability against panel surfaces.
 */
data class ChatPalette(
    val userBubble: Color,
    val onUserBubble: Color,
    val assistantBubble: Color,
    val onAssistantBubble: Color,
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
    val isDark = isDarkTheme()
    val accent = accentColor()
    return if (isDark) {
        ChatPalette(
            userBubble = accent,
            onUserBubble = Color.White,
            assistantBubble = AssistantBubble,
            onAssistantBubble = TextPrimary,
            toolCardBg = ToolCardBg,
            toolCardBorder = ToolCardBorder,
            codeBlockBg = CodeBlockBg,
            codeText = Color(0xFFE8E4DC),
            mathText = accent,
            mathBg = accent.copy(alpha = 0.12f),
            tableHeaderBg = DarkSurface,
            tableRowAltBg = DarkSurfaceVariant,
        )
    } else {
        ChatPalette(
            userBubble = accent,
            onUserBubble = Color.White,
            assistantBubble = LightAssistantBubble,
            onAssistantBubble = LightTextPrimary,
            toolCardBg = LightToolCardBg,
            toolCardBorder = LightToolCardBorder,
            codeBlockBg = LightCodeBlockBg,
            codeText = Color(0xFF1A1A18),
            mathText = accent,
            mathBg = accent.copy(alpha = 0.10f),
            tableHeaderBg = LightSurface,
            tableRowAltBg = LightSurfaceVariant,
        )
    }
}
