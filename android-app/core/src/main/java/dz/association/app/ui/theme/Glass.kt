package dz.association.app.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** True in the members' app: screens sit on the aurora backdrop and cards become frosted glass. */
val LocalGlass = staticCompositionLocalOf { false }

@Composable
fun isDarkTheme(): Boolean = MaterialTheme.colorScheme.surface.luminance() < 0.5f

/** Frosted-glass fill + light-catching gradient edge + coloured depth shadow. */
@Composable
fun Modifier.glass(shape: Shape, elevation: Dp = 14.dp): Modifier {
    val dark = isDarkTheme()
    val fill = if (dark) Brush.linearGradient(listOf(Color.White.copy(alpha = 0.11f), Color.White.copy(alpha = 0.04f)))
    else Brush.linearGradient(listOf(Color.White.copy(alpha = 0.78f), Color.White.copy(alpha = 0.52f)))
    val edge = Brush.linearGradient(
        if (dark) listOf(Color.White.copy(alpha = 0.28f), Color.White.copy(alpha = 0.04f), Color.White.copy(alpha = 0.12f))
        else listOf(Color.White, Color.White.copy(alpha = 0.35f), Brand.Sun.copy(alpha = 0.25f)),
    )
    val glow = if (dark) Color.Black.copy(alpha = 0.6f) else Brand.Maroon.copy(alpha = 0.22f)
    return this
        .shadow(elevation, shape, clip = false, ambientColor = glow, spotColor = glow)
        .background(fill, shape)
        .border(BorderStroke(1.dp, edge), shape)
}

/**
 * 3D press effect: the element tilts towards the finger (rotationX/Y), sinks slightly and
 * springs back on release. Pointer events are only observed, so clicks still work.
 */
fun Modifier.pressTilt(maxDegrees: Float = 8f): Modifier = composed {
    val rx = remember { Animatable(0f) }
    val ry = remember { Animatable(0f) }
    val sc = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val springy = spring<Float>(dampingRatio = 0.5f, stiffness = 300f)
    this
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val w = size.width.toFloat().coerceAtLeast(1f)
                val h = size.height.toFloat().coerceAtLeast(1f)
                val dx = (down.position.x / w - 0.5f) * 2f
                val dy = (down.position.y / h - 0.5f) * 2f
                scope.launch { ry.animateTo(dx * maxDegrees, springy) }
                scope.launch { rx.animateTo(-dy * maxDegrees, springy) }
                scope.launch { sc.animateTo(0.97f, springy) }
                do {
                    val e = awaitPointerEvent(PointerEventPass.Initial)
                } while (e.changes.any { it.pressed })
                scope.launch { rx.animateTo(0f, springy) }
                scope.launch { ry.animateTo(0f, springy) }
                scope.launch { sc.animateTo(1f, springy) }
            }
        }
        .graphicsLayer {
            rotationX = rx.value
            rotationY = ry.value
            scaleX = sc.value
            scaleY = sc.value
            cameraDistance = 14f * density
        }
}

/** Gentle floating motion with a slight 3D turn, for hero elements such as the logo. */
fun Modifier.float3d(amplitude: Dp = 5.dp, turnDegrees: Float = 7f): Modifier = composed {
    val t = rememberInfiniteTransition(label = "float")
    val p by t.animateFloat(-1f, 1f, infiniteRepeatable(tween(3600, easing = LinearEasing), RepeatMode.Reverse), label = "p")
    graphicsLayer {
        translationY = p * amplitude.toPx()
        rotationY = p * turnDegrees
        cameraDistance = 16f * density
    }
}

/**
 * Animated "aurora" backdrop in the brand colours. Each colour blob is drawn once and only its
 * layer is moved, so the animation is cheap (no per-frame redraw of the gradients).
 */
@Composable
fun AuroraBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val dark = isDarkTheme()
    val base = if (dark) listOf(Color(0xFF0E080A), Color(0xFF1A0C11), Color(0xFF0B0709))
    else listOf(Color(0xFFFFF7F2), Color(0xFFFBEDEA), Color(0xFFF7F1F4))
    val w = LocalConfiguration.current.screenWidthDp.dp
    val t = rememberInfiniteTransition(label = "aurora")
    val a by t.animateFloat(0f, 1f, infiniteRepeatable(tween(16000, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    val b by t.animateFloat(0f, 1f, infiniteRepeatable(tween(21000, easing = LinearEasing), RepeatMode.Reverse), label = "b")
    Box(modifier.fillMaxSize().background(Brush.verticalGradient(base))) {
        Blob(Brand.Sun, if (dark) 0.30f else 0.42f, w * 1.1f, Modifier.align(Alignment.TopEnd)) { translationX = (a - 0.5f) * 140f; translationY = -size.height * 0.35f + b * 120f }
        Blob(Brand.Maroon, if (dark) 0.45f else 0.30f, w * 1.2f, Modifier.align(Alignment.CenterStart)) { translationX = -size.width * 0.45f + b * 160f; translationY = (a - 0.5f) * 220f }
        Blob(Brand.Ember, if (dark) 0.22f else 0.26f, w, Modifier.align(Alignment.BottomEnd)) { translationX = size.width * 0.3f - a * 180f; translationY = size.height * 0.3f - b * 140f }
        // Text and icons drawn straight on the backdrop must use the theme's on-background colour.
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) { content() }
    }
}

@Composable
private fun Blob(color: Color, alpha: Float, diameter: Dp, modifier: Modifier, move: androidx.compose.ui.graphics.GraphicsLayerScope.() -> Unit) {
    Box(
        modifier.size(diameter)
            .graphicsLayer(move)
            .drawBehind {
                drawCircle(
                    Brush.radialGradient(listOf(color.copy(alpha = alpha), color.copy(alpha = alpha * 0.35f), Color.Transparent), center = center, radius = size.minDimension / 2f),
                    radius = size.minDimension / 2f, center = center,
                )
            },
    )
}

/** Glossy top highlight for coloured 3D surfaces ("clay" tiles, call-to-action cards). */
fun Modifier.gloss(): Modifier = drawBehind {
    drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.32f), Color.Transparent), startY = 0f, endY = size.height * 0.55f))
}

