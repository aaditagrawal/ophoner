package dev.ophoner.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Soft blurred color blobs painted behind the chat. Provides visual texture
 * and a subtle hint of the active accent without overwhelming content.
 *
 * Uses [Modifier.blur] (RenderEffect on API 31+; no-op on older API but the
 * radial gradients are already soft enough to read fine).
 */
@Composable
fun AccentBackdrop(
    modifier: Modifier = Modifier,
    intensity: Float = 1f,
) {
    val isDark = isDarkTheme()
    val accent = accentColor()
    val secondary = accent.shiftHue()

    // Blob alpha scales with theme — slightly stronger on light, more present on dark.
    val primaryAlpha = if (isDark) 0.38f else 0.22f
    val secondaryAlpha = if (isDark) 0.30f else 0.18f

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .blur(80.dp),
    ) {
        val w = size.width
        val h = size.height

        // Upper-right blob, primary accent
        drawBlob(
            color = accent.copy(alpha = primaryAlpha * intensity),
            center = Offset(w * 0.85f, h * 0.15f),
            radius = w * 0.7f,
        )

        // Lower-left blob, shifted accent for two-tone
        drawBlob(
            color = secondary.copy(alpha = secondaryAlpha * intensity),
            center = Offset(w * 0.1f, h * 0.85f),
            radius = w * 0.65f,
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBlob(
    color: Color,
    center: Offset,
    radius: Float,
) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color, color.copy(alpha = 0f)),
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
}

/** Quick perceptual hue shift via HSV — used to source a complementary blob tone. */
private fun Color.shiftHue(degrees: Float = 60f): Color {
    val argb = android.graphics.Color.argb(
        (alpha * 255).toInt(),
        (red * 255).toInt(),
        (green * 255).toInt(),
        (blue * 255).toInt(),
    )
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(argb, hsv)
    hsv[0] = (hsv[0] + degrees) % 360f
    val shifted = android.graphics.Color.HSVToColor(hsv)
    return Color(shifted)
}

/** Unused helper retained for symmetry — radial gradient brush over the entire canvas. */
@Suppress("unused")
internal fun fullBleed(size: Size, color: Color): Brush = Brush.radialGradient(
    colors = listOf(color, Color.Transparent),
    center = Offset(size.width / 2, size.height / 2),
    radius = maxOf(size.width, size.height),
)
