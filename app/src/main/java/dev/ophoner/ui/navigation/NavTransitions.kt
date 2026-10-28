package dev.ophoner.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.unit.IntOffset
import androidx.navigation.NavBackStackEntry

/*
 * Two kinds of horizontal motion, slide only (no crossfade):
 *  - Push: pages like Settings arrive from the trailing edge; the page below parallaxes left.
 *  - Drawer: the conversation list arrives from the leading edge (it opens from the
 *    left-hand panel button); the chat below parallaxes right.
 * Emphasized easing front-loads the movement so navigation feels immediate.
 */

private val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

private val EnterSpec = tween<IntOffset>(durationMillis = 320, easing = EmphasizedDecelerate)
private val ExitSpec = tween<IntOffset>(durationMillis = 320, easing = EmphasizedDecelerate)
private val DismissSpec = tween<IntOffset>(durationMillis = 220, easing = EmphasizedAccelerate)

private const val Parallax = 0.28f

private val AnimatedContentTransitionScope<NavBackStackEntry>.fromDrawer: Boolean
    get() = initialState.destination.route == Routes.CONVERSATIONS

private val AnimatedContentTransitionScope<NavBackStackEntry>.toDrawer: Boolean
    get() = targetState.destination.route == Routes.CONVERSATIONS

// — Pages (chat, settings) —

fun AnimatedContentTransitionScope<NavBackStackEntry>.pageEnter(): EnterTransition =
    if (fromDrawer) {
        // Opening a chat from the drawer: the chat settles in from the right as the drawer leaves.
        slideInHorizontally(initialOffsetX = { (it * Parallax).toInt() }, animationSpec = EnterSpec)
    } else {
        slideInHorizontally(initialOffsetX = { it }, animationSpec = EnterSpec)
    }

fun AnimatedContentTransitionScope<NavBackStackEntry>.pageExit(): ExitTransition =
    if (toDrawer) {
        slideOutHorizontally(targetOffsetX = { (it * Parallax).toInt() }, animationSpec = ExitSpec)
    } else {
        slideOutHorizontally(targetOffsetX = { -(it * Parallax).toInt() }, animationSpec = ExitSpec)
    }

fun AnimatedContentTransitionScope<NavBackStackEntry>.pagePopEnter(): EnterTransition =
    if (fromDrawer) {
        slideInHorizontally(initialOffsetX = { (it * Parallax).toInt() }, animationSpec = EnterSpec)
    } else {
        slideInHorizontally(initialOffsetX = { -(it * Parallax).toInt() }, animationSpec = EnterSpec)
    }

fun AnimatedContentTransitionScope<NavBackStackEntry>.pagePopExit(): ExitTransition =
    slideOutHorizontally(targetOffsetX = { it }, animationSpec = DismissSpec)

// — Drawer (conversation list) —

fun AnimatedContentTransitionScope<NavBackStackEntry>.drawerEnter(): EnterTransition =
    slideInHorizontally(initialOffsetX = { -it }, animationSpec = EnterSpec)

fun AnimatedContentTransitionScope<NavBackStackEntry>.drawerExit(): ExitTransition =
    slideOutHorizontally(targetOffsetX = { -it }, animationSpec = DismissSpec)
