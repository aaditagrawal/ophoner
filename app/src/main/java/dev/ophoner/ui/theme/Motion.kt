package dev.ophoner.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.progressSemantics
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Shared motion. Feedback that arrives on screen uses [EaseOut] (fast start, soft landing).
 * [Press] is the same family with a steeper first third, for the down state of a tap.
 * Loops stay linear. Nothing here eases in, and nothing interactive runs past 300ms.
 */
object Motion {
    const val PressIn = 60
    const val PressOut = 140
    const val BlinkClose = 70
    const val BlinkOpen = 120
    const val Fast = 160
    const val Standard = 220
    const val Travel = 240
    const val HopLaunch = 200
    const val HopLand = 160

    /** One revolution. Material's indeterminate arc is about 1332ms; the expressive loader rotates for several seconds. */
    const val Spinner = 700

    /** One sweep of the indeterminate bar. Material's linear indicator is 1800ms. */
    const val Bar = 800

    /** cubic-bezier(0.16, 1, 0.3, 1). Most of the travel happens immediately. */
    val EaseOut: Easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

    /** cubic-bezier(0.05, 0.9, 0.1, 1). Steeper first third than [EaseOut]. */
    val Press: Easing = CubicBezierEasing(0.05f, 0.9f, 0.1f, 1f)

    fun <T> spec(durationMillis: Int, easing: Easing = EaseOut): FiniteAnimationSpec<T> =
        tween(durationMillis = durationMillis, easing = easing)

    fun <T> pressIn(): FiniteAnimationSpec<T> = spec(PressIn, Press)
    fun <T> pressOut(): FiniteAnimationSpec<T> = spec(PressOut, EaseOut)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
fun ophonerMotionScheme(): MotionScheme = OphonerMotionScheme

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private object OphonerMotionScheme : MotionScheme {
    override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = Motion.spec(Motion.Fast)
    override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = Motion.spec(Motion.Standard)
    override fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> = Motion.spec(Motion.Travel)
    override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = Motion.spec(Motion.PressOut)
    override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = Motion.spec(Motion.Standard)
    override fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> = Motion.spec(Motion.Travel)
}

@Composable
fun SnappyProgressIndicator(
    modifier: Modifier = Modifier,
    color: Color = tokens().accent,
    strokeWidth: Dp = 2.dp,
    size: Dp = 18.dp,
) {
    val strokePx = with(androidx.compose.ui.platform.LocalDensity.current) { strokeWidth.toPx() }
    val stroke = remember(strokePx) { Stroke(width = strokePx, cap = StrokeCap.Round) }
    val rotation = rememberInfiniteTransition(label = "snappy-spinner").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = Motion.Spinner, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "rotation",
    )
    Canvas(
        modifier
            .size(size)
            .progressSemantics(),
    ) {
        drawArc(
            color = color,
            startAngle = rotation.value - 90f,
            sweepAngle = 270f,
            useCenter = false,
            style = stroke,
        )
    }
}

@Composable
fun SnappyLinearProgress(
    modifier: Modifier = Modifier,
    color: Color = tokens().accent,
    trackColor: Color = tokens().line,
) {
    val head = rememberInfiniteTransition(label = "snappy-linear").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = Motion.Bar, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "head",
    )
    Canvas(modifier.fillMaxWidth().height(2.dp).progressSemantics()) {
        drawRect(trackColor)
        val width = size.width * 0.35f
        val travel = head.value
        drawRect(
            color = color,
            topLeft = Offset((size.width + width) * travel - width, 0f),
            size = Size(width, size.height),
        )
    }
}
