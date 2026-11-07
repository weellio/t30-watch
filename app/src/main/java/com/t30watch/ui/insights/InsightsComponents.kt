package com.t30watch.ui.insights

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.t30watch.insights.Status
import com.t30watch.insights.bpCategory
import com.t30watch.insights.heartContext

/*
 * Small building blocks for the Insights screen, plus two public pieces other screens can reuse:
 * BpBadge (category chip for a reading) and HeartContextLabel (what you were doing + whether the bpm fits).
 */

/** Fixed traffic-light colours. Not taken from the theme so they mean the same thing on every palette. */
@Immutable
data class StatusColors(val container: Color, val content: Color, val dot: Color)

private val GoodLight = StatusColors(Color(0xFFD5F0DC), Color(0xFF0B4F22), Color(0xFF1E8E3E))
private val WatchLight = StatusColors(Color(0xFFFFE7B3), Color(0xFF573700), Color(0xFFB86E00))
private val DoctorLight = StatusColors(Color(0xFFFAD9D5), Color(0xFF8A1C16), Color(0xFFC62828))
private val GoodDark = StatusColors(Color(0xFF1C4A29), Color(0xFFC6F1D1), Color(0xFF6DD58C))
private val WatchDark = StatusColors(Color(0xFF573F00), Color(0xFFFFE08F), Color(0xFFF2B33D))
private val DoctorDark = StatusColors(Color(0xFF6A1D19), Color(0xFFFFDAD5), Color(0xFFFF8A80))

/** Colours for a status; dark variants when the current surface is dark. */
@Composable
fun statusColors(status: Status): StatusColors {
    val cs = MaterialTheme.colorScheme
    val dark = cs.surface.luminance() < 0.5f
    return when (status) {
        Status.GOOD -> if (dark) GoodDark else GoodLight
        Status.WATCH -> if (dark) WatchDark else WatchLight
        Status.DOCTOR -> if (dark) DoctorDark else DoctorLight
        Status.NO_DATA -> StatusColors(cs.surfaceVariant, cs.onSurfaceVariant, cs.outline)
    }
}

/** Default chip text for a status. */
fun statusLabel(status: Status): String = when (status) {
    Status.GOOD -> "Good"
    Status.WATCH -> "Keep an eye on"
    Status.DOCTOR -> "Talk to a doctor"
    Status.NO_DATA -> "Not enough data"
}

/** A small rounded chip in the status colour. */
@Composable
fun StatusChip(text: String, status: Status, modifier: Modifier = Modifier) {
    val c = statusColors(status)
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(c.container)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(c.dot))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = c.content)
    }
}

/** Blood-pressure category chip, e.g. "Normal" in green or "High, stage 1" in amber. */
@Composable
fun BpBadge(systolic: Int, diastolic: Int, modifier: Modifier = Modifier) {
    val cat = bpCategory(systolic, diastolic)
    StatusChip(cat.label, cat.status, modifier)
}

/** "Resting · normal at rest". Steps in the same 5-minute slot tell what you were doing; null = unknown. */
@Composable
fun HeartContextLabel(bpm: Int, stepsInSlot: Int?, modifier: Modifier = Modifier) {
    val ctx = heartContext(bpm, stepsInSlot)
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusChip(ctx.level.label, ctx.status)
        Text(ctx.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Card frame shared by all four sections: title + status chip, verdict, then [content]. */
@Composable
internal fun InsightCard(
    title: String,
    status: Status,
    chipText: String,
    verdict: String,
    subline: String?,
    content: @Composable () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                StatusChip(chipText, status)
            }
            Text(verdict, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            subline?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            content()
        }
    }
}

/** A row of big numbers with small labels. */
@Composable
internal fun KeyNumbers(items: List<Pair<String, String>>) {
    if (items.isEmpty()) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items.forEach { (label, value) ->
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** One row of a compact table. [status] adds a coloured dot. */
data class RangeRow(val left: String, val right: String, val status: Status? = null)

@Composable
internal fun CompactTable(rows: List<RangeRow>, header: Pair<String, String>? = null) {
    Column(Modifier.fillMaxWidth()) {
        header?.let { (l, r) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(l, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(r, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            }
            HorizontalDivider()
        }
        rows.forEachIndexed { i, row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    if (row.status != null) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(statusColors(row.status).dot))
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(row.left, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                }
                Text(row.right, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
            if (i < rows.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

/** Horizontal bar row: label, a bar scaled to [fraction], and a value. */
@Composable
internal fun BarRow(label: String, fraction: Float, value: String, color: Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(96.dp))
        Box(
            Modifier
                .weight(1f)
                .height(12.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (fraction > 0f) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction.coerceIn(0.03f, 1f))
                        .height(12.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(color),
                )
            }
        }
        Text(value, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End, modifier = Modifier.width(64.dp))
    }
}

/** "What do these numbers mean?" toggle. Open/closed state survives rotation. */
@Composable
internal fun Explainer(key: String, content: @Composable () -> Unit) {
    var open by rememberSaveable(key) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        TextButton(onClick = { open = !open }) {
            Text(if (open) "Hide explanation ▲" else "What do these numbers mean? ▼")
        }
        if (open) {
            Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
        }
    }
}

@Composable
internal fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
}

@Composable
internal fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun Bullets(items: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { s ->
            Row {
                Text("•", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(16.dp))
                Text(s, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
