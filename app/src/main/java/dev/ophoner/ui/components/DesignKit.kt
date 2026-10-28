package dev.ophoner.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ophoner.ui.theme.tokens
import kotlinx.coroutines.launch

val CardCorner = RoundedCornerShape(20.dp)
val InnerCorner = RoundedCornerShape(12.dp)
val MarkCorner = RoundedCornerShape(13.dp)

/**
 * AndBrowser press feedback: the pressed element dims to 55% instead of drawing a ripple.
 *
 * Implemented as a shared [IndicationNodeFactory] rather than `composed {}` so rows carry no
 * per-item composition state; the fade only costs a layer while actually pressed.
 */
fun Modifier.pressFade(
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = Role.Button,
    onClick: () -> Unit,
): Modifier = clickable(
    interactionSource = null,
    indication = FadeIndication,
    enabled = enabled,
    onClickLabel = onClickLabel,
    role = role,
    onClick = onClick,
)

private object FadeIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        FadeIndicationNode(interactionSource)

    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = System.identityHashCode(this)
}

private class FadeIndicationNode(
    private val interactionSource: InteractionSource,
) : Modifier.Node(), DrawModifierNode {
    private val alpha = Animatable(1f)
    private val layerPaint = Paint()

    override fun onAttach() {
        coroutineScope.launch {
            var pressCount = 0
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> pressCount++
                    is PressInteraction.Release, is PressInteraction.Cancel -> pressCount = (pressCount - 1).coerceAtLeast(0)
                }
                val pressed = pressCount > 0
                launch {
                    alpha.animateTo(
                        targetValue = if (pressed) 0.55f else 1f,
                        animationSpec = tween(durationMillis = if (pressed) 60 else 160),
                    ) { invalidateDraw() }
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        val a = alpha.value
        if (a >= 1f) {
            drawContent()
            return
        }
        layerPaint.alpha = a
        drawIntoCanvas { canvas ->
            canvas.saveLayer(Rect(Offset.Zero, size), layerPaint)
            drawContent()
            canvas.restore()
        }
    }
}

/** Rounded 20 dp card: faint panel fill with a hairline border. */
@Composable
fun GroupedSection(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val t = tokens()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(CardCorner)
            .background(t.panel)
            .border(1.dp, t.line, CardCorner),
        content = content,
    )
}

@Composable
fun RowDivider(startIndent: Dp = 16.dp, endIndent: Dp = 0.dp) {
    HorizontalDivider(
        modifier = Modifier.padding(start = startIndent, end = endIndent),
        thickness = 0.5.dp,
        color = tokens().line,
    )
}

/** Left-aligned semibold section label ("Favourites", "Saved for later"). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = tokens().ink,
        modifier = modifier
            .padding(start = 4.dp, end = 4.dp, bottom = 12.dp)
            .semantics { heading() },
    )
}

/** Large left-aligned page title with an optional quiet subtitle. */
@Composable
fun ScreenTitle(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    val t = tokens()
    Column(
        modifier = modifier.padding(horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.displayMedium,
            color = t.ink,
            modifier = Modifier.semantics { heading() },
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = t.secondary,
            )
        }
    }
}

/** Accent-filled primary action. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    enabled: Boolean = true,
    height: Dp = 52.dp,
) {
    val t = tokens()
    Row(
        modifier = modifier
            .heightIn(min = height)
            .clip(RoundedCornerShape(height / 2))
            .background(if (enabled) t.accent else t.accent.copy(alpha = 0.3f))
            .pressFade(enabled = enabled, onClick = onClick)
            .padding(horizontal = 22.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            LucideIcon(icon = icon, contentDescription = null, tint = t.onAccent, size = 19.dp)
        }
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = t.onAccent,
        )
    }
}

/** Quiet secondary action: panel fill, hairline border. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    enabled: Boolean = true,
    contentColor: Color = tokens().ink,
) {
    val t = tokens()
    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(t.panel)
            .border(1.dp, t.line, RoundedCornerShape(24.dp))
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .pressFade(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            LucideIcon(icon = icon, contentDescription = null, tint = t.accent, size = 17.dp)
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
        )
    }
}

/** Soft rounded-square icon mark, tinted with the accent. */
@Composable
fun IconMark(
    @DrawableRes icon: Int,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    tint: Color = tokens().accent,
    background: Color = tokens().active,
) {
    val t = tokens()
    val shape = RoundedCornerShape(size * 0.33f)
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(background)
            .border(1.dp, t.line, shape),
        contentAlignment = Alignment.Center,
    ) {
        LucideIcon(icon = icon, contentDescription = null, tint = tint, size = size * 0.45f)
    }
}

/**
 * Solid behind the status bar, then fading out, so scrolled content never collides with
 * system icons while the floating glass controls stay unobstructed.
 */
@Composable
fun StatusBarScrim(modifier: Modifier = Modifier, visible: Boolean = true) {
    val t = tokens()
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val fade = 28.dp
    val total = statusBar + fade
    val solidStop = if (total.value > 0f) statusBar / total else 0f
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(300), label = "scrim")
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(total)
            .graphicsLayer { this.alpha = alpha }
            .background(
                Brush.verticalGradient(
                    0f to t.background,
                    solidStop to t.background.copy(alpha = 0.94f),
                    1f to Color.Transparent,
                ),
            ),
    )
}
