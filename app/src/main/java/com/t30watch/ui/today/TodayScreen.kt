package com.t30watch.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.t30watch.ble.ConnectionState
import com.t30watch.data.sync.SyncStatus
import com.t30watch.ui.charts.Bar
import com.t30watch.ui.charts.BarChart
import com.t30watch.ui.charts.LineChart
import com.t30watch.ui.charts.Series
import com.t30watch.ui.charts.SleepStrip
import com.t30watch.ui.insights.BpBadge
import com.t30watch.ui.insights.HeartContextLabel
import com.t30watch.ui.rewards.RewardsSummaryCard
import com.t30watch.util.Fmt
import com.t30watch.util.Time
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(onOpenWatch: () -> Unit, onOpenRewards: () -> Unit, vm: TodayViewModel = hiltViewModel()) {
    val data by vm.data.collectAsStateWithLifecycle()
    val conn by vm.connection.collectAsStateWithLifecycle()
    val battery by vm.battery.collectAsStateWithLifecycle()
    val sync by vm.syncStatus.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val nf = NumberFormat.getIntegerInstance()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Today") },
                actions = {
                    TextButton(onClick = vm::syncNow, enabled = conn is ConnectionState.Ready && !sync.running) { Text("Sync") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { StatusLine(conn, battery?.percent, sync, onOpenWatch) }
            item { RewardsSummaryCard(onClick = onOpenRewards) }

            // ---- Steps
            item {
                val goal = settings.profile.stepGoal
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Steps", style = MaterialTheme.typography.titleMedium)
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(nf.format(data.steps), fontSize = 44.sp, fontWeight = FontWeight.Bold)
                            Text("  / ${nf.format(goal)}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
                        }
                        LinearProgressIndicator(progress = { (data.steps / goal.toFloat()).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            Fmt.distance(data.distanceM, settings.units)?.let { Stat("Distance", it) }
                            data.kcal?.let { Stat("Calories", "${it.toInt()} kcal") }
                        }
                        val hourly = data.hourly()
                        if (hourly.any { it > 0 }) {
                            BarChart(
                                bars = hourly.map { Bar(it.toFloat(), highlight = it >= 1000) },
                                height = 90.dp,
                                xLabels = listOf("12a", "6a", "12p", "6p", "12a"),
                            )
                        }
                    }
                }
            }

            // ---- Heart rate
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Heart rate", style = MaterialTheme.typography.titleMedium)
                        val v = data.heart.map { it.bpm }
                        if (v.isEmpty()) {
                            Text("No heart rate synced today yet.", style = MaterialTheme.typography.bodyMedium)
                        } else {
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text("${v.last()}", fontSize = 44.sp, fontWeight = FontWeight.Bold)
                                Text(" bpm  at ${Time.hhmm(data.heart.last().timeMs)}", modifier = Modifier.padding(bottom = 8.dp))
                            }
                            val last = data.heart.last()
                            HeartContextLabel(last.bpm, data.slots.firstOrNull { it.startMs == last.timeMs }?.steps)
                            Text("min ${v.min()} · avg ${v.average().toInt()} · max ${v.max()}", style = MaterialTheme.typography.bodySmall)
                            LineChart(
                                series = listOf(Series(v.map { it.toFloat() }, MaterialTheme.colorScheme.error)),
                                height = 100.dp,
                                xLabels = listOf(Time.hhmm(data.heart.first().timeMs), Time.hhmm(data.heart.last().timeMs)),
                            )
                        }
                    }
                }
            }

            // ---- Blood pressure
            data.latestBp?.let { bp ->
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Blood pressure", style = MaterialTheme.typography.titleMedium)
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text("${bp.systolic}/${bp.diastolic}", fontSize = 36.sp, fontWeight = FontWeight.Bold)
                                Text(" mmHg  ${whenText(bp.timeMs)}", modifier = Modifier.padding(bottom = 6.dp))
                            }
                            BpBadge(bp.systolic, bp.diastolic)
                        }
                    }
                }
            }

            // ---- Sleep
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Sleep", style = MaterialTheme.typography.titleMedium)
                        val s = data.lastSleep
                        if (s == null) {
                            Text("No sleep synced yet.", style = MaterialTheme.typography.bodyMedium)
                        } else {
                            val asleep = s.deepMinutes + s.lightMinutes
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(Time.duration(asleep), fontSize = 32.sp, fontWeight = FontWeight.Bold)
                                Text("  ${Time.hhmm(s.startMs)}–${Time.hhmm(s.endMs)}", modifier = Modifier.padding(bottom = 6.dp))
                            }
                            Text(
                                "deep ${Time.duration(s.deepMinutes)} · light ${Time.duration(s.lightMinutes)}" +
                                    (if (s.wakeCount > 0) " · woke ${s.wakeCount}×" else "") +
                                    (s.quality?.let { " · score $it" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (s.stages.isNotEmpty()) SleepStrip(s.stages)
                            Text(whenText(s.endMs), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            // ---- Workouts
            if (data.workouts.isNotEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Recent workouts", style = MaterialTheme.typography.titleMedium)
                            data.workouts.forEach { w ->
                                val mins = ((w.endMs - w.startMs) / 60_000L).toInt()
                                Text(
                                    "${w.sportName} · ${Time.duration(mins)}" + (w.kcal?.let { " · ${it.toInt()} kcal" } ?: "") +
                                        "  (${whenText(w.startMs)})",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusLine(conn: ConnectionState, battery: Int?, sync: SyncStatus, onOpenWatch: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val text = when (conn) {
            is ConnectionState.Ready -> "${conn.name ?: "Watch"} connected" + (battery?.let { " · battery ~$it%" } ?: "")
            is ConnectionState.Connecting -> "${conn.step}…"
            is ConnectionState.Disconnected -> "Watch not connected"
        }
        Column(Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            val syncText = when {
                sync.running -> sync.step ?: "Syncing…"
                sync.lastError != null -> "Sync failed: ${sync.lastError}"
                sync.lastSuccessAt != null -> "Last sync ${whenText(sync.lastSuccessAt)}"
                else -> "Not synced yet"
            }
            Text(
                syncText,
                style = MaterialTheme.typography.bodySmall,
                color = if (sync.lastError != null && !sync.running) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (sync.running) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        if (conn is ConnectionState.Disconnected) TextButton(onClick = onOpenWatch) { Text("Connect") }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

/** "14:05" for today, "Mon 14:05" within a week, else a short date. */
internal fun whenText(ms: Long): String {
    val d = Time.dateOf(ms)
    val today = Time.today()
    return when {
        d == today -> "today " + Time.hhmm(ms)
        d == today.minusDays(1) -> "yesterday " + Time.hhmm(ms)
        d.isAfter(today.minusDays(7)) -> d.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() } + " " + Time.hhmm(ms)
        else -> DateFormat.getDateInstance(DateFormat.SHORT).format(Date(ms))
    }
}
