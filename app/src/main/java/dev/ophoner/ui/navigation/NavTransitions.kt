package dev.ophoner.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.unit.IntOffset

/** Horizontal stack motion — slide only, no crossfade. */
private val PushNavSpec = spring(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMedium,
    visibilityThreshold = IntOffset.VisibilityThreshold,
)

/** New screen pushes in from the trailing edge (right in LTR). */
fun AnimatedContentTransitionScope<*>.pushEnter(): EnterTransition =
    slideInHorizontally(initialOffsetX = { it }, animationSpec = PushNavSpec)

/** Current screen slides partially toward the leading edge (parallax). */
fun AnimatedContentTransitionScope<*>.pushExit(): ExitTransition =
    slideOutHorizontally(
        targetOffsetX = { -(it * 0.28f).toInt() },
        animationSpec = PushNavSpec,
    )

/** Previous screen returns from the leading edge when popping. */
fun AnimatedContentTransitionScope<*>.popEnter(): EnterTransition =
    slideInHorizontally(
        initialOffsetX = { -(it * 0.28f).toInt() },
        animationSpec = PushNavSpec,
    )

/** Current screen pops off toward the trailing edge. */
fun AnimatedContentTransitionScope<*>.popExit(): ExitTransition =
    slideOutHorizontally(targetOffsetX = { it }, animationSpec = PushNavSpec)
