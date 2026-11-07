package com.t30watch.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A bar for [BarChart]. [highlight] draws it in the accent colour (e.g. goal reached). */
data class Bar(val value: Float, val highlight: Boolean = false)

/**
 * Simple bar chart with an optional dashed goal line and a few x labels under it.
 * [xLabels] are spread evenly across the width (first label at the left edge, last at the right).
 */
@Composable
fun BarChart(
    bars: List<Bar>,
    modifier: Modifier = Modifier,
    height: Dp = 160.dp,
    goal: Float? = null,
    xLabels: List<String> = emptyList(),
) {
    val base = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
    val accent = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val goalColor = MaterialTheme.colorScheme.tertiary
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val maxV = maxOf(bars.maxOfOrNull { it.value } ?: 0f, goal ?: 0f, 1f) * 1.08f
            drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
            if (bars.isNotEmpty()) {
                val slot = size.width / bars.size
                val w = (slot * 0.7f).coerceAtLeast(1f)
                bars.forEachIndexed { i, b ->
                    if (b.value <= 0f) return@forEachIndexed
                    val h = size.height * (b.value / maxV)
                    drawRoundRect(
                        color = if (b.highlight) accent else base,
                        topLeft = Offset(i * slot + (slot - w) / 2, size.height - h),
                        size = Size(w, h),
                        cornerRadius = CornerRadius(minOf(w / 2, 4.dp.toPx())),
                    )
                }
            }
            goal?.let { g ->
                val y = size.height - size.height * (g / maxV)
                drawLine(goalColor, Offset(0f, y), Offset(size.width, y), 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
            }
        }
        AxisLabels(xLabels)
    }
}

/** A series for [LineChart]; null values break the line. */
data class Series(val values: List<Float?>, val color: Color)

/** Line chart over evenly spaced x positions. Y range auto-fits all series with a little padding. */
@Composable
fun LineChart(
    series: List<Series>,
    modifier: Modifier = Modifier,
    height: Dp = 160.dp,
    xLabels: List<String> = emptyList(),
    yLabelSuffix: String = "",
    band: ClosedFloatingPointRange<Float>? = null,
) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val bandColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.10f)
    val all = series.flatMap { it.values }.filterNotNull()
    Column(modifier) {
        if (all.isNotEmpty()) {
            Row(Modifier.fillMaxWidth()) {
                Text("max ${all.max().toInt()}$yLabelSuffix", fontSize = 10.sp, color = labelColor, modifier = Modifier.weight(1f))
                Text("min ${all.min().toInt()}$yLabelSuffix", fontSize = 10.sp, color = labelColor, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
            }
        }
        Canvas(Modifier.fillMaxWidth().height(height)) {
            drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
            if (all.isEmpty()) return@Canvas
            val lo = (all.min() - 5f)
            val hi = (all.max() + 5f)
            val span = (hi - lo).coerceAtLeast(1f)
            fun y(v: Float) = size.height - (v - lo) / span * size.height
            band?.let {
                val top = y(it.endInclusive.coerceIn(lo, hi))
                val bot = y(it.start.coerceIn(lo, hi))
                drawRect(bandColor, Offset(0f, top), Size(size.width, (bot - top).coerceAtLeast(0f)))
            }
            for (s in series) {
                val n = s.values.size
                if (n == 0) continue
                val path = Path()
                var pen = false
                s.values.forEachIndexed { i, v ->
                    val x = if (n == 1) size.width / 2 else size.width * i / (n - 1)
                    if (v == null) { pen = false; return@forEachIndexed }
                    if (!pen) path.moveTo(x, y(v)) else path.lineTo(x, y(v))
                    pen = true
                }
                drawPath(path, s.color, style = Stroke(width = 2.dp.toPx()))
                // Dots for isolated points so single readings are visible.
                s.values.forEachIndexed { i, v ->
                    if (v == null) return@forEachIndexed
                    val prev = s.values.getOrNull(i - 1)
                    val next = s.values.getOrNull(i + 1)
                    if (prev == null && next == null) {
                        val x = if (n == 1) size.width / 2 else size.width * i / (n - 1)
                        drawCircle(s.color, 3.dp.toPx(), Offset(x, y(v)))
                    }
                }
            }
        }
        AxisLabels(xLabels)
    }
}

/** A horizontal strip showing sleep stages: one char per minute ('D' deep, 'L' light, 'W' awake). */
@Composable
fun SleepStrip(stages: String, modifier: Modifier = Modifier, height: Dp = 36.dp) {
    val deep = MaterialTheme.colorScheme.primary
    val light = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
    val awake = MaterialTheme.colorScheme.tertiary
    Canvas(modifier.fillMaxWidth().height(height)) {
        if (stages.isEmpty()) return@Canvas
        val w = size.width / stages.length
        var i = 0
        while (i < stages.length) {
            var j = i
            while (j < stages.length && stages[j] == stages[i]) j++
            val (c, top) = when (stages[i]) {
                'D' -> deep to size.height * 0.45f
                'W' -> awake to 0f
                else -> light to size.height * 0.2f
            }
            drawRect(c, Offset(i * w, top), Size((j - i) * w, size.height - top))
            i = j
        }
    }
}

@Composable
private fun AxisLabels(labels: List<String>) {
    if (labels.isEmpty()) return
    val c = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth()) {
        labels.forEachIndexed { i, l ->
            val align = when (i) {
                0 -> TextAlign.Start
                labels.lastIndex -> TextAlign.End
                else -> TextAlign.Center
            }
            Text(l, fontSize = 10.sp, color = c, textAlign = align, modifier = Modifier.weight(1f))
        }
    }
}
