package dev.ophoner.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * Theme-aware palette for chat surfaces. Reads from MaterialTheme + LocalIsDark
 * so light/dark + accent picks propagate without re-plumbing every call site.
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
            assistantBubble = Color.Transparent,
            onAssistantBubble = TextPrimary,
            toolCardBg = DarkSurface.copy(alpha = 0.7f),
            toolCardBorder = DarkBorder,
            codeBlockBg = Color(0xFF161618),
            codeText = Color(0xFFE8E8EA),
            mathText = accent,
            mathBg = accent.copy(alpha = 0.12f),
            tableHeaderBg = DarkSurface,
            tableRowAltBg = DarkSurfaceVariant.copy(alpha = 0.5f),
        )
    } else {
        ChatPalette(
            userBubble = accent,
            onUserBubble = Color.White,
            assistantBubble = Color.Transparent,
            onAssistantBubble = LightTextPrimary,
            toolCardBg = LightSurface.copy(alpha = 0.85f),
            toolCardBorder = LightBorder,
            codeBlockBg = LightSurface,
            codeText = Color(0xFF1C1C1E),
            mathText = accent,
            mathBg = accent.copy(alpha = 0.10f),
            tableHeaderBg = LightSurface,
            tableRowAltBg = LightSurfaceVariant.copy(alpha = 0.6f),
        )
    }
}
