package com.t30watch.ui.history

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.t30watch.ui.charts.Bar
import com.t30watch.ui.charts.BarChart
import com.t30watch.ui.charts.LineChart
import com.t30watch.ui.charts.Series
import com.t30watch.ui.charts.SleepStrip
import com.t30watch.util.Time
import java.text.NumberFormat
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(vm: HistoryViewModel = hiltViewModel()) {
    val v by vm.view.collectAsStateWithLifecycle()
    val nf = NumberFormat.getIntegerInstance()

    Scaffold(topBar = { CenterAlignedTopAppBar(title = { Text("History") }) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Metric.entries.forEach { m -> FilterChip(selected = v.metric == m, onClick = { vm.setMetric(m) }, label = { Text(m.label) }) }
                }
            }
            item {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    Span.entries.forEachIndexed { i, s ->
                        SegmentedButton(
                            selected = v.span == s, onClick = { vm.setSpan(s) },
                            shape = SegmentedButtonDefaults.itemShape(i, Span.entries.size),
                        ) { Text(s.label) }
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = vm::previous) { Text("‹") }
                    Text(rangeTitle(v), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                    TextButton(onClick = vm::next, enabled = vm.canGoNext()) { Text("›") }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (v.metric) {
                            Metric.STEPS -> StepsBlock(v, nf)
                            Metric.HEART -> HeartBlock(v)
                            Metric.SLEEP -> SleepBlock(v)
                            Metric.BP -> BpBlock(v)
                        }
                    }
                }
            }
        }
    }
}

private fun rangeTitle(v: HistoryView): String = when (v.span) {
    Span.DAY -> DateTimeFormatter.ofPattern("EEE d MMM yyyy").format(v.anchor)
    Span.WEEK -> DateTimeFormatter.ofPattern("d MMM").format(v.from) + " – " + DateTimeFormatter.ofPattern("d MMM").format(v.toExclusive.minusDays(1))
    Span.MONTH -> DateTimeFormatter.ofPattern("MMMM yyyy").format(v.from)
}

private fun dayLabels(v: HistoryView): List<String> = when (v.span) {
    Span.DAY -> listOf("0", "6", "12", "18", "24")
    Span.WEEK -> v.days.map { it.day.dayOfWeek.name.take(1) }
    Span.MONTH -> listOf("1", "8", "15", "22", v.days.size.toString())
}

@Composable
private fun Empty() = Text("No data for this period yet.", style = MaterialTheme.typography.bodyMedium)

@Composable
private fun Headline(big: String, small: String) {
    Text(big, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    Text(small, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun StepsBlock(v: HistoryView, nf: NumberFormat) {
    if (v.span == Span.DAY) {
        val total = v.days.firstOrNull()?.steps ?: 0
        if (total == 0) return Empty()
        Headline(nf.format(total) + " steps", "goal ${nf.format(v.stepGoal)}")
        BarChart(v.hourlySteps.map { Bar(it.toFloat()) }, xLabels = dayLabels(v))
    } else {
        val withData = v.days.filter { it.steps > 0 }
        if (withData.isEmpty()) return Empty()
        Headline(
            "avg ${nf.format(withData.map { it.steps }.average().toInt())} / day",
            "total ${nf.format(withData.sumOf { it.steps })} · goal reached ${withData.count { it.steps >= v.stepGoal }} of ${withData.size} days",
        )
        BarChart(v.days.map { Bar(it.steps.toFloat(), highlight = it.steps >= v.stepGoal) }, goal = v.stepGoal.toFloat(), xLabels = dayLabels(v))
    }
}

@Composable
private fun HeartBlock(v: HistoryView) {
    val red = MaterialTheme.colorScheme.error
    if (v.span == Span.DAY) {
        if (v.heart.isEmpty()) return Empty()
        val b = v.heart.map { it.bpm }
        Headline("avg ${b.average().toInt()} bpm", "min ${b.min()} · max ${b.max()} · ${b.size} readings")
        LineChart(listOf(Series(b.map { it.toFloat() }, red)), xLabels = listOf(Time.hhmm(v.heart.first().timeMs), Time.hhmm(v.heart.last().timeMs)))
    } else {
        if (v.days.all { it.hrAvg == null }) return Empty()
        val avgs = v.days.mapNotNull { it.hrAvg }
        Headline("avg ${avgs.average().toInt()} bpm", "lowest ${v.days.mapNotNull { it.hrMin }.min()} · highest ${v.days.mapNotNull { it.hrMax }.max()}")
        LineChart(
            listOf(
                Series(v.days.map { it.hrMax?.toFloat() }, red.copy(alpha = 0.4f)),
                Series(v.days.map { it.hrAvg?.toFloat() }, red),
                Series(v.days.map { it.hrMin?.toFloat() }, red.copy(alpha = 0.4f)),
            ),
            xLabels = dayLabels(v),
        )
        Text("Middle line: daily average. Outer lines: daily min and max.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SleepBlock(v: HistoryView) {
    if (v.span == Span.DAY) {
        val nights = v.sleep
        if (nights.isEmpty()) return Empty()
        nights.forEach { s ->
            Headline(Time.duration(s.deepMinutes + s.lightMinutes), "${Time.hhmm(s.startMs)}–${Time.hhmm(s.endMs)} · deep ${Time.duration(s.deepMinutes)} · light ${Time.duration(s.lightMinutes)}" + if (s.wakeCount > 0) " · woke ${s.wakeCount}×" else "")
            if (s.stages.isNotEmpty()) SleepStrip(s.stages)
        }
    } else {
        val nights = v.days.mapNotNull { it.sleepMin }
        if (nights.isEmpty()) return Empty()
        Headline("avg ${Time.duration(nights.average().toInt())}", "${nights.size} nights · shortest ${Time.duration(nights.min())} · longest ${Time.duration(nights.max())}")
        BarChart(v.days.map { Bar((it.sleepMin ?: 0) / 60f, highlight = (it.sleepMin ?: 0) >= 420) }, goal = 8f, xLabels = dayLabels(v))
        Text("Hours asleep per night. Dashed line: 8 h.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun BpBlock(v: HistoryView) {
    val sysC = MaterialTheme.colorScheme.error
    val diaC = MaterialTheme.colorScheme.primary
    if (v.span == Span.DAY) {
        if (v.bp.isEmpty()) return Empty()
        Headline(
            "avg ${v.bp.map { it.systolic }.average().toInt()}/${v.bp.map { it.diastolic }.average().toInt()} mmHg",
            "${v.bp.size} readings",
        )
        LineChart(
            listOf(Series(v.bp.map { it.systolic.toFloat() }, sysC), Series(v.bp.map { it.diastolic.toFloat() }, diaC)),
            xLabels = listOf(Time.hhmm(v.bp.first().timeMs), Time.hhmm(v.bp.last().timeMs)),
        )
    } else {
        if (v.days.all { it.sys == null }) return Empty()
        val s = v.days.mapNotNull { it.sys }
        val d = v.days.mapNotNull { it.dia }
        Headline("avg ${s.average().toInt()}/${d.average().toInt()} mmHg", "${s.size} days with readings")
        LineChart(
            listOf(Series(v.days.map { it.sys?.toFloat() }, sysC), Series(v.days.map { it.dia?.toFloat() }, diaC)),
            xLabels = dayLabels(v),
        )
        Text("Top line: systolic. Bottom line: diastolic. Daily averages.", style = MaterialTheme.typography.bodySmall)
    }
}
