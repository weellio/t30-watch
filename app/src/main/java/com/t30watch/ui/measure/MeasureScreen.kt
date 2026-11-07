package com.t30watch.ui.measure

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.t30watch.ble.ConnectionState
import com.t30watch.ble.WatchInfo
import com.t30watch.data.BpSession
import com.t30watch.data.HeartSession
import com.t30watch.data.MeasureRepository
import com.t30watch.data.db.MeasurementEntity
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeasureScreen(onBack: (() -> Unit)?, vm: MeasureViewModel = hiltViewModel()) {
    val connection by vm.connection.collectAsStateWithLifecycle()
    val heart by vm.heart.collectAsStateWithLifecycle()
    val bp by vm.bp.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()

    val info = (connection as? ConnectionState.Ready)?.info
    val anyRunning = heart.running || bp.running

    // Keep the phone screen on while a measurement runs.
    val view = LocalView.current
    DisposableEffect(anyRunning) {
        view.keepScreenOn = anyRunning
        onDispose { view.keepScreenOn = false }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Measure") },
                navigationIcon = { onBack?.let { TextButton(onClick = it) { Text("Back") } } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (info == null) {
                item { Text("Connect to the watch first.", style = MaterialTheme.typography.bodyLarge) }
            } else {
                if (info.supports(WatchInfo.HEART_RATE)) {
                    item { HeartCard(heart, otherRunning = bp.running, onStart = vm::startHeart, onStop = vm::stopHeart) }
                }
                if (info.supports(WatchInfo.BLOOD_PRESSURE)) {
                    item { BpCard(bp, otherRunning = heart.running, onStart = vm::startBp, onStop = vm::stopBp) }
                }
            }
            if (recent.isNotEmpty()) {
                item { Text("Recent", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
                items(recent, key = { it.id }) { RecentRow(it) }
            }
        }
    }
}

@Composable
private fun HeartCard(s: HeartSession, otherRunning: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Heart rate", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(s.current?.toString() ?: "--", fontSize = 56.sp, fontWeight = FontWeight.Bold)
                Text(" bpm", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 10.dp))
            }
            val statusText = when {
                s.running -> s.status?.label ?: "Starting…"
                s.error != null -> s.error
                s.values.isNotEmpty() -> "Saved"
                else -> null
            }
            statusText?.let {
                Text(it, color = if (!s.running && s.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            }
            if (s.running) Elapsed(s.startedAt, MeasureRepository.HEART_MAX_SECONDS)
            val v = s.values
            if (v.size >= 2) {
                HeartChart(v, Modifier.fillMaxWidth().height(96.dp))
                Text("min ${v.min()} · avg ${v.average().toInt()} · max ${v.max()}", style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (s.running) {
                    Button(onClick = onStop) { Text("Stop") }
                } else {
                    Button(onClick = onStart, enabled = !otherRunning) { Text(if (s.values.isEmpty()) "Start" else "Measure again") }
                }
            }
        }
    }
}

@Composable
private fun BpCard(s: BpSession, otherRunning: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Blood pressure", style = MaterialTheme.typography.titleMedium)
            val shown = s.result ?: s.last?.takeIf { it.valid && !s.running }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(shown?.let { "${it.systolic}/${it.diastolic}" } ?: "--/--", fontSize = 56.sp, fontWeight = FontWeight.Bold)
                Text(" mmHg", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 10.dp))
            }
            when {
                s.running -> {
                    val p = s.last?.progress
                    if (p != null) {
                        LinearProgressIndicator(progress = { p / 100f }, modifier = Modifier.fillMaxWidth())
                        Text("$p %  ·  keep your arm still", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("Measuring, about a minute. Keep your arm still.", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                s.error != null -> Text(s.error, color = MaterialTheme.colorScheme.error)
                s.result != null -> Text("Saved", style = MaterialTheme.typography.bodyMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (s.running) {
                    OutlinedButton(onClick = onStop) { Text("Cancel") }
                } else {
                    Button(onClick = onStart, enabled = !otherRunning) { Text(if (s.result == null) "Start" else "Measure again") }
                }
            }
        }
    }
}

@Composable
private fun Elapsed(startedAt: Long, maxSeconds: Int) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val secs = ((now - startedAt) / 1000).coerceAtLeast(0)
    Text("${secs / 60}:${"%02d".format(secs % 60)}  (stops by itself at ${maxSeconds / 60} min)", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun HeartChart(values: List<Int>, modifier: Modifier) {
    val color = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        val lo = (values.min() - 5).toFloat()
        val hi = (values.max() + 5).toFloat()
        val span = (hi - lo).coerceAtLeast(1f)
        drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = if (values.size == 1) 0f else size.width * i / (values.size - 1)
            val y = size.height - (v - lo) / span * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 2.5.dp.toPx()))
    }
}

@Composable
private fun RecentRow(m: MeasurementEntity) {
    val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(m.startedAt))
    val (label, value) = when (m.kind) {
        MeasurementEntity.KIND_BLOOD_PRESSURE -> "Blood pressure" to "${m.systolic}/${m.diastolic} mmHg"
        else -> "Heart rate" to "${m.bpmLast} bpm  (avg ${m.bpmAvg}, ${m.bpmMin}–${m.bpmMax})"
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(time, style = MaterialTheme.typography.bodySmall)
        }
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(190.dp))
    }
}
