package dev.ophoner.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazePerformanceMode
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.hazeSource
import dev.ophoner.ui.theme.GlassStyle
import dev.ophoner.ui.theme.LocalUiPrefs
import dev.ophoner.ui.theme.tokens

/**
 * Frosted-glass controls floating over content, after AndBrowser.
 *
 * A screen creates a [HazeState], provides it through [LocalGlassState], and marks the
 * scrolling content with [glassSource]. Every [GlassSurface] then blurs whatever is
 * behind it. Android 12+ gets a real backdrop blur; older releases fall back to a
 * denser translucent scrim (handled by Haze).
 */
val LocalGlassState = staticCompositionLocalOf<HazeState?> { null }

/** Marks content that glass surfaces on this screen should blur. */
fun Modifier.glassSource(state: HazeState?): Modifier =
    if (state == null) this else this.hazeSource(state)

private val GlassBlurRadius = 24.dp

// A 24 dp frost under a 64% tint hides input downsampling completely, so sample the
// backdrop at reduced resolution instead of letting quality float.
private val GlassPerformance = HazePerformanceMode.Fixed(0.3f)

@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
    elevated: Boolean = true,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit,
) {
    val t = tokens()
    val state = LocalGlassState.current
    val prefs = LocalUiPrefs.current
    val tint = if (t.dark) Color(0xFF1A1D24).copy(alpha = 0.62f) else Color.White.copy(alpha = 0.64f)
    val style = remember(t.dark, t.background, tint) {
        HazeBlurStyle {
            blurRadius(GlassBlurRadius)
            noiseFactor(0f)
            backgroundColor(t.background)
            colorEffects(listOf(HazeColorEffect.tint(tint)))
        }
    }
    // Soft vertical sheen + thin highlight edge complete the frost.
    val sheen = remember(t.dark) {
        Brush.verticalGradient(
            if (t.dark) {
                listOf(Color(0x521A1D24), Color(0x2E181B22), Color(0x4714171D))
            } else {
                listOf(Color(0x42FFFFFF), Color(0x1FFFFFFF), Color(0x33FFFFFF))
            },
        )
    }
    val edge = if (t.dark) Color.White.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.70f)
    val outerEdge = t.line
    val shadowColor = Color.Black.copy(alpha = if (t.dark) 0.30f else 0.10f)

    Box(
        modifier = modifier
            .then(
                if (elevated) {
                    Modifier.shadow(
                        elevation = 10.dp,
                        shape = shape,
                        clip = false,
                        ambientColor = shadowColor,
                        spotColor = shadowColor,
                    )
                } else {
                    Modifier
                },
            )
            .clip(shape)
            .then(
                if (state != null && prefs.glass == GlassStyle.FROSTED) {
                    Modifier.hazeBlur(
                        input = HazeInput.Sources(state),
                        style = style,
                        performanceMode = GlassPerformance,
                    )
                } else {
                    // Solid: an opaque raised surface, no backdrop sampling at all.
                    Modifier.background(if (prefs.glass == GlassStyle.SOLID) t.raised else t.background.copy(alpha = 0.92f))
                },
            )
            .background(sheen)
            .border(0.5.dp, outerEdge, shape)
            .border(1.dp, edge.copy(alpha = edge.alpha * 0.5f), shape),
        contentAlignment = contentAlignment,
        content = content,
    )
}

/**
 * Circular glass control: 48 dp touch target around a 40 dp visible surface.
 */
@Composable
fun GlassIconButton(
    @DrawableRes icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = tokens().icon,
    visibleSize: Dp = 40.dp,
    badge: Color? = null,
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .pressFade(onClickLabel = contentDescription, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        GlassSurface(
            modifier = Modifier.size(visibleSize),
            shape = CircleShape,
            contentAlignment = Alignment.Center,
        ) {
            LucideIcon(icon = icon, contentDescription = contentDescription, tint = tint, size = 19.dp)
            if (badge != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 5.dp)
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(badge),
                )
            }
        }
    }
}
