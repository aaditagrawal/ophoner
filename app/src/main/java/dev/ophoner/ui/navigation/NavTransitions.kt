package dev.ophoner.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.unit.IntOffset
import androidx.navigation.NavBackStackEntry
import dev.ophoner.ui.theme.Motion

/*
 * Two kinds of horizontal motion, slide only (no crossfade):
 *  - Push: pages like Settings arrive from the trailing edge; the page below parallaxes left.
 *  - Drawer: the conversation list arrives from the leading edge (it opens from the
 *    left-hand panel button); the chat below parallaxes right.
 * Ease-out front-loads the movement so navigation feels immediate. Enter and exit
 * share one spec so a pop does not keep moving after the cover has already left.
 */

private val Travel = Motion.spec<IntOffset>(Motion.Travel)

private const val Parallax = 0.28f

private val AnimatedContentTransitionScope<NavBackStackEntry>.fromDrawer: Boolean
    get() = initialState.destination.route == Routes.CONVERSATIONS

private val AnimatedContentTransitionScope<NavBackStackEntry>.toDrawer: Boolean
    get() = targetState.destination.route == Routes.CONVERSATIONS

// — Pages (chat, settings) —

fun AnimatedContentTransitionScope<NavBackStackEntry>.pageEnter(): EnterTransition =
    if (fromDrawer) {
        // Opening a chat from the drawer: the chat settles in from the right as the drawer leaves.
        slideInHorizontally(initialOffsetX = { (it * Parallax).toInt() }, animationSpec = Travel)
    } else {
        slideInHorizontally(initialOffsetX = { it }, animationSpec = Travel)
    }

fun AnimatedContentTransitionScope<NavBackStackEntry>.pageExit(): ExitTransition =
    if (toDrawer) {
        slideOutHorizontally(targetOffsetX = { (it * Parallax).toInt() }, animationSpec = Travel)
    } else {
        slideOutHorizontally(targetOffsetX = { -(it * Parallax).toInt() }, animationSpec = Travel)
    }

fun AnimatedContentTransitionScope<NavBackStackEntry>.pagePopEnter(): EnterTransition =
    if (fromDrawer) {
        slideInHorizontally(initialOffsetX = { (it * Parallax).toInt() }, animationSpec = Travel)
    } else {
        slideInHorizontally(initialOffsetX = { -(it * Parallax).toInt() }, animationSpec = Travel)
    }

fun AnimatedContentTransitionScope<NavBackStackEntry>.pagePopExit(): ExitTransition =
    slideOutHorizontally(targetOffsetX = { it }, animationSpec = Travel)

// — Drawer (conversation list) —

fun AnimatedContentTransitionScope<NavBackStackEntry>.drawerEnter(): EnterTransition =
    slideInHorizontally(initialOffsetX = { -it }, animationSpec = Travel)

fun AnimatedContentTransitionScope<NavBackStackEntry>.drawerExit(): ExitTransition =
    slideOutHorizontally(targetOffsetX = { -it }, animationSpec = Travel)
