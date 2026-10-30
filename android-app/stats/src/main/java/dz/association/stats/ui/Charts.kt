package dz.association.stats.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Locale

data class ChartItem(val label: String, val value: Int, val secondary: Int? = null, val color: Color? = null)

private fun pct(part: Int, total: Int) = if (total == 0) "0%" else String.format(Locale.ROOT, "%.0f%%", part * 100.0 / total)

/** Grows from zero when the data appears or changes. */
@Composable
private fun rememberGrowth(key: Any?): Float {
    val a = remember { Animatable(0f) }
    LaunchedEffect(key) { a.snapTo(0f); a.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
    return a.value
}

/**
 * Horizontal bars (works for long Arabic labels). Tapping a row highlights it and shows its share;
 * [ChartItem.secondary] draws an inner bar (e.g. attended out of registered).
 */
@Composable
fun BarChart(items: List<ChartItem>, modifier: Modifier = Modifier, secondaryLabel: String? = null) {
    if (items.isEmpty()) return
    val max = items.maxOf { it.value }.coerceAtLeast(1)
    val total = items.sumOf { it.value }
    val growth = rememberGrowth(items)
    var picked by rememberSaveable(items.size) { mutableIntStateOf(-1) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEachIndexed { i, it ->
            val color = it.color ?: StatsColors.Series[i % StatsColors.Series.size]
            val active = picked == i
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { picked = if (active) -1 else i }
                    .background(if (active) StatsColors.SurfaceHigh else Color.Transparent).padding(horizontal = 6.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(it.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = StatsColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (active) "${it.value} · ${pct(it.value, total)}" + (it.secondary?.let { s -> "  ($secondaryLabel ${s})" } ?: "") else it.value.toString(),
                        style = MaterialTheme.typography.labelLarge, color = if (active) color else StatsColors.TextSecondary,
                    )
                }
                Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(6.dp)).background(StatsColors.Line)) {
                    Box(Modifier.fillMaxWidth(growth * it.value / max).height(10.dp).clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.45f)))
                    it.secondary?.let { s ->
                        Box(Modifier.fillMaxWidth(growth * s / max).height(10.dp).clip(RoundedCornerShape(6.dp)).background(color))
                    }
                }
            }
        }
    }
}

/** Donut with a legend; tapping a legend row highlights its slice. */
@Composable
fun DonutChart(items: List<ChartItem>, modifier: Modifier = Modifier, size: Dp = 150.dp, centerLabel: String? = null) {
    val total = items.sumOf { it.value }
    val growth = rememberGrowth(items)
    var picked by rememberSaveable(items.size) { mutableIntStateOf(-1) }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(size)) {
                val stroke = this.size.minDimension * 0.16f
                val d = this.size.minDimension - stroke
                val tl = Offset((this.size.width - d) / 2, (this.size.height - d) / 2)
                drawArc(StatsColors.Line, 0f, 360f, false, tl, Size(d, d), style = Stroke(stroke))
                if (total == 0) return@Canvas
                var start = -90f
                items.forEachIndexed { i, it ->
                    val sweep = 360f * it.value / total * growth
                    val color = it.color ?: StatsColors.Series[i % StatsColors.Series.size]
                    val w = if (picked == i) stroke * 1.25f else stroke
                    if (sweep > 0f) drawArc(color.copy(alpha = if (picked == -1 || picked == i) 1f else 0.35f), start, (sweep - 1.5f).coerceAtLeast(0.5f), false, tl, Size(d, d), style = Stroke(w, cap = StrokeCap.Butt))
                    start += sweep
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val shown = if (picked in items.indices) items[picked] else null
                Text(shown?.let { pct(it.value, total) } ?: total.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = StatsColors.Text)
                Text(shown?.label ?: centerLabel.orEmpty(), style = MaterialTheme.typography.labelSmall, color = StatsColors.TextSecondary, maxLines = 1)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items.forEachIndexed { i, it ->
                val color = it.color ?: StatsColors.Series[i % StatsColors.Series.size]
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { picked = if (picked == i) -1 else i }
                        .background(if (picked == i) StatsColors.SurfaceHigh else Color.Transparent).padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.size(10.dp).background(color, CircleShape))
                    Text(it.label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = StatsColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${it.value}", style = MaterialTheme.typography.labelLarge, color = StatsColors.TextSecondary)
                }
            }
        }
    }
}

/** A ring showing one ratio (attendance rate). */
@Composable
fun RateRing(rate: Double?, label: String, modifier: Modifier = Modifier, size: Dp = 110.dp) {
    val growth = rememberGrowth(rate)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(size)) {
                val stroke = this.size.minDimension * 0.12f
                val d = this.size.minDimension - stroke
                val tl = Offset(stroke / 2, stroke / 2)
                drawArc(StatsColors.Line, 0f, 360f, false, tl, Size(d, d), style = Stroke(stroke))
                if (rate != null) drawArc(StatsColors.Primary, -90f, (360f * rate.toFloat() * growth), false, tl, Size(d, d), style = Stroke(stroke, cap = StrokeCap.Round))
            }
            Text(rate?.let { String.format(Locale.ROOT, "%.0f%%", it * 100) } ?: "—", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = StatsColors.Text)
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = StatsColors.TextSecondary)
    }
}

/** A thin stacked bar (registered / present / late / absent) for a meeting row. */
@Composable
fun StackedBar(parts: List<Pair<Int, Color>>, modifier: Modifier = Modifier) {
    val total = parts.sumOf { it.first }.coerceAtLeast(1)
    Row(modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(StatsColors.Line)) {
        parts.forEach { (n, c) -> if (n > 0) Box(Modifier.weight(n.toFloat() / total).height(8.dp).background(c)) }
        val rest = 1f - parts.sumOf { it.first }.toFloat() / total
        if (rest > 0.001f) Box(Modifier.weight(rest).height(8.dp))
    }
}

@Composable
fun LegendDot(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Text(text, style = MaterialTheme.typography.labelSmall, color = StatsColors.TextSecondary)
        Box(Modifier.width(4.dp))
    }
}
