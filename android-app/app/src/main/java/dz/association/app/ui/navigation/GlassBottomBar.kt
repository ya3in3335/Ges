package dz.association.app.ui.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dz.association.app.ui.theme.Brand
import dz.association.app.ui.theme.LocalLowEnd
import dz.association.app.ui.theme.glass
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

data class BarItem(val route: String, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

private val BAR_HEIGHT = 72.dp
private val INNER = 6.dp            // equal margin between the circle and the bar's edges
private val MAX_DOT = BAR_HEIGHT - INNER * 2

/**
 * Floating glass navigation bar. The selection indicator is a circle sized to the bar's inner
 * margin; it slides with a "liquid" motion (its leading edge springs ahead, the trailing edge
 * follows, so it stretches into a capsule while moving and settles back into a circle).
 * Only offsets/sizes/graphics layers animate, so it stays smooth on modest phones.
 */
@Composable
fun GlassBottomBar(items: List<BarItem>, selectedIndex: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    val lowEnd = LocalLowEnd.current
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(BAR_HEIGHT)
            .glass(RoundedCornerShape(BAR_HEIGHT / 2), elevation = 20.dp)
            .padding(horizontal = INNER)
            .testTag("bottom_bar"),
    ) {
        val density = LocalDensity.current
        val slot = maxWidth / items.size
        // The circle fills the bar height minus its margins, so it is concentric with the bar's rounded ends.
        val DOT = minOf(MAX_DOT, slot - 2.dp)
        val dotPx = with(density) { DOT.toPx() }
        fun startOf(i: Int) = with(density) { (slot * i + (slot - DOT) / 2).toPx() }

        // Two edges animated independently = stretchy, liquid indicator.
        val lead = remember { Animatable(startOf(selectedIndex.coerceAtLeast(0))) }
        val tail = remember { Animatable(startOf(selectedIndex.coerceAtLeast(0)) + dotPx) }
        LaunchedEffect(selectedIndex, slot) {
            if (selectedIndex < 0) return@LaunchedEffect
            val s = startOf(selectedIndex)
            val forward = s > lead.value
            val fast = spring<Float>(dampingRatio = 0.72f, stiffness = if (lowEnd) Spring.StiffnessMedium else 900f)
            val slow = spring<Float>(dampingRatio = 0.8f, stiffness = if (lowEnd) Spring.StiffnessMedium else 260f)
            coroutineScope {
                launch { lead.animateTo(s, if (forward) slow else fast) }
                launch { tail.animateTo(s + dotPx, if (forward) fast else slow) }
            }
        }

        // Indicator (drawn behind the icons), vertically aligned on the icon row.
        if (selectedIndex >= 0) {
            val left = with(density) { lead.value.toDp() }
            val widthPx = (tail.value - lead.value).coerceAtLeast(dotPx)
            val width = with(density) { widthPx.toDp() }
            // While stretching it also squashes a little, like a drop of liquid, then rounds out again.
            val squash = ((widthPx - dotPx) / dotPx).coerceIn(0f, 1f) * 0.18f
            val height = DOT * (1f - squash)
            Box(
                Modifier
                    .offset(x = left, y = (BAR_HEIGHT - height) / 2)
                    .width(width)
                    .height(height)
                    .then(if (lowEnd) Modifier else Modifier.shadow(12.dp, CircleShape, ambientColor = Brand.Ember, spotColor = Brand.Ember))
                    .background(Brush.linearGradient(listOf(Color(0xFFF07A3A), Brand.Maroon)), CircleShape)
                    .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.35f), Color.Transparent)), CircleShape),
            )
        }

        Row(Modifier.fillMaxWidth().fillMaxHeight(), horizontalArrangement = Arrangement.SpaceEvenly) {
            items.forEachIndexed { i, item ->
                val selected = i == selectedIndex
                val scale by animateFloatAsState(if (selected) 1.12f else 1f, spring(dampingRatio = 0.45f, stiffness = 500f), label = "s")
                val lift by animateFloatAsState(if (selected) -2f else 0f, spring(dampingRatio = 0.5f), label = "l")
                val iconColor by animateColorAsState(if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, label = "c")
                val labelColor by animateColorAsState(if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, label = "t")
                Column(
                    Modifier.weight(1f).fillMaxHeight()
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            if (!selected) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelect(i)
                        }
                        .semantics { role = Role.Tab; this.selected = selected; contentDescription = item.label }
                        .testTag("tab_${item.route}"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
                        Icon(
                            if (selected) item.selectedIcon else item.icon, null, tint = iconColor,
                            modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale; translationY = lift * this.density },
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(item.label, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = labelColor, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
                }
            }
        }
    }
}
