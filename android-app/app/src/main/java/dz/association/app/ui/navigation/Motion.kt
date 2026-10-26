package dz.association.app.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.navigation.NavBackStackEntry

/**
 * Screen transitions (Material "emphasized" motion):
 *  - between bottom tabs: fade-through with a subtle zoom;
 *  - into a detail screen: shared-axis slide in the reading direction (mirrored automatically in RTL);
 *  - weak devices: a short cross-fade only.
 * Only alpha / translation / scale are animated, which run on the GPU layer without re-layout.
 */
object Motion {
    private val emphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    private val emphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    private const val IN = 340
    private const val OUT = 180

    private fun isTabSwitch(s: AnimatedContentTransitionScope<NavBackStackEntry>) =
        s.initialState.destination.route in Routes.TABS && s.targetState.destination.route in Routes.TABS

    private fun fromIntro(s: AnimatedContentTransitionScope<NavBackStackEntry>) = s.initialState.destination.route == Routes.INTRO

    fun enter(s: AnimatedContentTransitionScope<NavBackStackEntry>, lowEnd: Boolean): EnterTransition = when {
        fromIntro(s) -> fadeIn(tween(600))
        lowEnd -> fadeIn(tween(160))
        isTabSwitch(s) -> fadeIn(tween(260, delayMillis = 60, easing = emphasizedDecelerate)) + scaleIn(tween(320, delayMillis = 60, easing = emphasizedDecelerate), initialScale = 0.96f)
        else -> s.slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(IN, easing = emphasizedDecelerate)) { it / 4 } +
            fadeIn(tween(240, easing = emphasizedDecelerate))
    }

    fun exit(s: AnimatedContentTransitionScope<NavBackStackEntry>, lowEnd: Boolean): ExitTransition = when {
        lowEnd -> fadeOut(tween(120))
        isTabSwitch(s) -> fadeOut(tween(100, easing = emphasizedAccelerate))
        else -> s.slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(IN, easing = emphasizedDecelerate)) { it / 10 } +
            fadeOut(tween(OUT, easing = emphasizedAccelerate))
    }

    fun popEnter(s: AnimatedContentTransitionScope<NavBackStackEntry>, lowEnd: Boolean): EnterTransition = when {
        lowEnd -> fadeIn(tween(160))
        isTabSwitch(s) -> enter(s, lowEnd)
        else -> s.slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(IN, easing = emphasizedDecelerate)) { it / 10 } +
            fadeIn(tween(240, easing = emphasizedDecelerate))
    }

    fun popExit(s: AnimatedContentTransitionScope<NavBackStackEntry>, lowEnd: Boolean): ExitTransition = when {
        lowEnd -> fadeOut(tween(120))
        isTabSwitch(s) -> exit(s, lowEnd)
        else -> s.slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(260, easing = emphasizedAccelerate)) { it / 4 } +
            fadeOut(tween(OUT, easing = emphasizedAccelerate)) + scaleOut(tween(260), targetScale = 0.98f)
    }
}
