package com.t30watch.ui.insights

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.t30watch.insights.ActivityInsight
import com.t30watch.insights.BpCategory
import com.t30watch.insights.BpInsight
import com.t30watch.insights.HeartInsight
import com.t30watch.insights.SleepInsight
import com.t30watch.insights.Status
import com.t30watch.insights.Thresholds
import com.t30watch.insights.clockText
import com.t30watch.insights.countText
import com.t30watch.insights.durationText
import com.t30watch.insights.minuteOfDay
import com.t30watch.ui.charts.Bar
import com.t30watch.ui.charts.BarChart
import com.t30watch.ui.charts.LineChart
import com.t30watch.ui.charts.Series
import com.t30watch.util.Time
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Days shown in the small trend charts. */
private const val CHART_DAYS = 14

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InsightsScreen(onOpenMeasure: () -> Unit, onOpenRewards: () -> Unit, vm: InsightsViewModel = hiltViewModel()) {
    val st by vm.state.collectAsStateWithLifecycle()

    Scaffold(topBar = { CenterAlignedTopAppBar(title = { Text("Insights") }) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { ProgressRow(onOpenRewards) }
            if (st.loading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
            } else {
                st.heart?.let { h -> item { HeartCard(h, st, onOpenMeasure) } }
                st.bp?.let { b -> item { BpCard(b, st, onOpenMeasure) } }
                st.sleep?.let { s -> item { SleepCard(s, st) } }
                st.activity?.let { a -> item { ActivityCard(a, st) } }
            }
        }
    }
}

/** Slot for the rewards card. Kept as a plain row so this screen doesn't depend on it. */
@Composable
private fun ProgressRow(onOpenRewards: () -> Unit) {
    OutlinedCard(onClick = onOpenRewards, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Your progress", style = MaterialTheme.typography.titleMedium)
                Text("Streaks and rewards", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("›", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

// ---------------------------------------------------------------- Heart rate

@Composable
private fun HeartCard(h: HeartInsight, st: InsightsState, onOpenMeasure: () -> Unit) {
    InsightCard("Heart rate", h.status, statusLabel(h.status), h.verdict, h.trendText) {
        val nums = buildList {
            h.latestResting?.let { add("Resting" to "$it bpm") }
            h.restingAvg7?.let { add("7-day resting" to "$it bpm") }
            add("Your max" to "${h.maxHr} bpm")
        }
        KeyNumbers(nums)

        val days = chartDays(st.today)
        val values = days.map { d -> h.restingByDay[d]?.toFloat() }
        if (values.count { it != null } >= 2) {
            SectionLabel("Resting heart rate, last 2 weeks")
            LineChart(
                series = listOf(Series(values, MaterialTheme.colorScheme.primary)),
                height = 100.dp,
                xLabels = dayLabels(days, st.today),
                yLabelSuffix = " bpm",
                band = Thresholds.RESTING_TYPICAL_LOW.toFloat()..Thresholds.RESTING_TYPICAL_HIGH.toFloat(),
            )
            Note("Shaded: typical range, 60–100.")
        }

        if (h.avgAtRest != null || h.avgLight != null || h.avgMoving != null) {
            SectionLabel("Resting vs moving, last 7 days")
            CompactTable(
                header = "What you were doing" to "Average",
                rows = buildList {
                    h.avgAtRest?.let { add(RangeRow("At rest", "$it bpm")) }
                    h.avgLight?.let { add(RangeRow("Light movement", "$it bpm")) }
                    h.avgMoving?.let { add(RangeRow("Walking or more", "$it bpm")) }
                },
            )
        }

        if (h.samples7 > 0) {
            SectionLabel("Time in zones, last 7 days")
            val most = h.zoneTimes.maxOf { it.minutes }
            if (most == 0) {
                Note("No time in exercise zones this week.")
            } else {
                h.zoneTimes.forEach { zt ->
                    BarRow(
                        label = "${zt.zone.number} ${zt.zone.name}",
                        fraction = zt.minutes / most.toFloat(),
                        value = durationText(zt.minutes),
                        color = zoneColor(zt.zone.number),
                    )
                }
            }
        }

        MeasureButton(onOpenMeasure)

        Explainer("heart") {
            SectionLabel("Resting heart rate")
            CompactTable(
                listOf(
                    RangeRow("40–59 bpm", "Common in fit people", Status.GOOD),
                    RangeRow("60–100 bpm", "Typical adult", Status.GOOD),
                    RangeRow("Over 100 most days", "Talk to a doctor", Status.DOCTOR),
                    RangeRow("Under 40 most days", "Talk to a doctor", Status.DOCTOR),
                ),
            )
            Note("Lower is usually better. The app uses readings taken while you're still or asleep.")

            SectionLabel("Exercise or not?")
            Note("Steps in the same 5 minutes tell the app what you were doing.")
            CompactTable(
                header = "Steps in 5 min" to "Counts as",
                rows = listOf(
                    RangeRow("0", "Resting"),
                    RangeRow("${Thresholds.LIGHT_MIN_STEPS}–${Thresholds.ACTIVE_MIN_STEPS - 1}", "Light movement"),
                    RangeRow("${Thresholds.ACTIVE_MIN_STEPS}–${Thresholds.BRISK_MIN_STEPS - 1}", "Walking"),
                    RangeRow("${Thresholds.BRISK_MIN_STEPS}+", "Brisk or exercise"),
                ),
            )
            Note("Cycling or weights show few steps. Start a sport on the watch so it counts as a workout.")

            SectionLabel("Heart-rate zones")
            Note(
                "Your max is about ${h.maxHr} bpm, from your age (${st.age})." +
                    if (!st.profileSaved) " Set your age in your profile." else "",
            )
            CompactTable(
                header = "Zone" to "Heart rate",
                rows = h.zones.map { z -> RangeRow("${z.number} ${z.name}", "${z.lowBpm}–${z.highBpm} bpm\n${z.meaning}") },
            )
        }
    }
}

@Composable
private fun zoneColor(n: Int): Color {
    val p = MaterialTheme.colorScheme.primary
    return p.copy(alpha = 0.35f + 0.13f * n)
}

// ---------------------------------------------------------------- Blood pressure

@Composable
private fun BpCard(b: BpInsight, st: InsightsState, onOpenMeasure: () -> Unit) {
    val chip = when {
        b.status == Status.DOCTOR -> statusLabel(Status.DOCTOR)
        b.readings7 >= Thresholds.MIN_BP_READINGS && b.avgCategory != null -> b.avgCategory.label
        b.latestCategory != null -> b.latestCategory.label
        else -> statusLabel(Status.NO_DATA)
    }
    InsightCard("Blood pressure", b.status, chip, b.verdict, b.trendText) {
        val nums = buildList {
            if (b.avgSys7 != null && b.avgDia7 != null) add("7-day average" to "${b.avgSys7}/${b.avgDia7}")
            b.latest?.let { add("Latest" to "${it.systolic}/${it.diastolic}") }
        }
        KeyNumbers(nums)

        b.latest?.let { l ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BpBadge(l.systolic, l.diastolic)
                Note("Latest, " + whenShort(l.timeMs, st.today, st.use24Hour))
            }
        }
        if (b.latestMoving) Note("Latest was taken while moving: less reliable.")
        if (b.moving7 > 0 && b.moving7 < b.readings7) {
            Note("${b.moving7} of ${b.readings7} readings this week were taken while moving. Left out of the average.")
        }

        val days = chartDays(st.today)
        val sys = days.map { d -> b.byDay[d]?.first?.toFloat() }
        val dia = days.map { d -> b.byDay[d]?.second?.toFloat() }
        if (sys.count { it != null } >= 2) {
            SectionLabel("Daily average, last 2 weeks")
            val top = MaterialTheme.colorScheme.primary
            val bottom = MaterialTheme.colorScheme.tertiary
            LineChart(
                series = listOf(Series(sys, top), Series(dia, bottom)),
                height = 110.dp,
                xLabels = dayLabels(days, st.today),
            )
            Legend(listOf("Top number" to top, "Bottom number" to bottom))
        }

        MeasureButton(onOpenMeasure)

        Explainer("bp") {
            CompactTable(
                header = "Top / bottom" to "Category",
                rows = BpCategory.entries.map { c ->
                    RangeRow(c.range, if (c == BpCategory.SEVERE) "${c.label}. ${c.meaning}." else c.label, c.status)
                },
            )
            Note("Top number: pressure as your heart beats. Bottom: between beats. The higher category of the two counts.")
            SectionLabel("Exercise or not?")
            Note("Blood pressure rises while you move. Readings taken while walking are left out of the average.")
            SectionLabel("For the best reading")
            Note("Sit still 5 minutes, wrist at heart level, then tap Measure now.")
            Note("Wrist readings are estimates. A cuff is more accurate.")
        }
    }
}

// ---------------------------------------------------------------- Sleep

@Composable
private fun SleepCard(s: SleepInsight, st: InsightsState) {
    InsightCard("Sleep", s.status, statusLabel(s.status), s.verdict, s.trendText) {
        val first = buildList {
            s.avgAsleep?.let { add("Average" to durationText(it)) }
            add("Goal" to durationText(s.goalMinutes))
            s.deepPct?.let { add("Deep" to "$it%") }
        }
        KeyNumbers(first)
        if (s.week.isNotEmpty()) {
            val second = buildList {
                s.bedtime?.let { add("Usual bedtime" to clockText(it.meanMinute, st.use24Hour)) }
                if (s.week.size >= 2) s.bedtime?.let { add("Varies by" to "± ${it.spreadMinutes} min") }
                s.avgWakes?.let { add("Wakes a night" to "%.1f".format(it)) }
            }
            KeyNumbers(second)
        }

        val lastNight = st.today.minusDays(1)
        val nights = (CHART_DAYS - 1 downTo 0).map { lastNight.minusDays(it.toLong()) }
        val byNight = s.recent.associateBy { it.nightOf }
        if (s.recent.isNotEmpty()) {
            SectionLabel("Hours asleep, last 2 weeks")
            BarChart(
                bars = nights.map { d ->
                    val m = byNight[d]?.asleepMinutes ?: 0
                    Bar(m / 60f, highlight = m >= s.goalMinutes)
                },
                height = 90.dp,
                goal = s.goalMinutes / 60f,
                xLabels = listOf(shortDate(nights.first()), shortDate(nights[nights.size / 2]), "Last night"),
            )
            Note("Dashed line: your goal.")
        }

        if (s.suggestions.isNotEmpty()) {
            SectionLabel("Try this")
            Bullets(s.suggestions)
        }

        Explainer("sleep") {
            CompactTable(
                listOf(
                    RangeRow("7–9 h a night", "Recommended for adults", Status.GOOD),
                    RangeRow("Under 6 h, often", "Too little", Status.WATCH),
                    RangeRow("Deep 15–25%", "Typical share of sleep", Status.GOOD),
                    RangeRow("Waking 1–3 times", "Normal, you may not remember", Status.GOOD),
                    RangeRow("Bedtime within 30 min", "Steady routine", Status.GOOD),
                    RangeRow("Bedtime varies 45+ min", "Harder on your body clock", Status.WATCH),
                ),
            )
            Note("Deep sleep is when your body recovers most. \"Varies by\" is how far your bedtime usually moves from its average.")
            Note("Your goal is ${durationText(s.goalMinutes)}. Change it in your profile.")
        }
    }
}

// ---------------------------------------------------------------- Activity

@Composable
private fun ActivityCard(a: ActivityInsight, st: InsightsState) {
    InsightCard("Activity", a.status, if (a.status == Status.WATCH) "Below goal" else statusLabel(a.status), a.verdict, a.trendText) {
        val nums = buildList {
            a.avgSteps7?.let { add("Daily average" to countText(it)) }
            add("Goal" to countText(a.goal))
            if (a.daysCounted > 0) add("Days at goal" to "${a.daysAtGoal} of ${a.daysCounted}")
        }
        KeyNumbers(nums)
        if (a.avgSteps7 != null && a.todaySteps > 0) Note("Today so far: ${countText(a.todaySteps)} steps.")

        SectionLabel("Brisk time, last 7 days")
        BarRow(
            label = "${a.briskMinutes7} min",
            fraction = a.briskMinutes7 / Thresholds.WEEKLY_BRISK_GOAL_MIN.toFloat(),
            value = "of ${Thresholds.WEEKLY_BRISK_GOAL_MIN}",
            color = statusColors(if (a.briskMinutes7 >= Thresholds.WEEKLY_BRISK_GOAL_MIN) Status.GOOD else Status.WATCH).dot,
        )

        val days = chartDays(st.today)
        if (a.recent.isNotEmpty()) {
            SectionLabel("Steps, last 2 weeks")
            BarChart(
                bars = days.map { d -> (a.recent[d] ?: 0).let { Bar(it.toFloat(), highlight = it >= a.goal) } },
                height = 90.dp,
                goal = a.goal.toFloat(),
                xLabels = dayLabels(days, st.today),
            )
            Note("Dashed line: your goal.")
        }

        Explainer("activity") {
            CompactTable(
                header = "Steps a day" to "Means",
                rows = listOf(
                    RangeRow("Under 5,000", "Low", Status.WATCH),
                    RangeRow("5,000–7,499", "Somewhat active"),
                    RangeRow("7,500–9,999", "Active", Status.GOOD),
                    RangeRow("10,000+", "Very active", Status.GOOD),
                ),
            )
            CompactTable(
                listOf(RangeRow("${Thresholds.WEEKLY_BRISK_GOAL_MIN} brisk min a week", "Recommended for adults", Status.GOOD)),
            )
            Note("Brisk = about 100 steps a minute or faster, like walking with purpose.")
            Note("Today isn't in the average until it's over. Days under ${Thresholds.NOT_WORN_DAY_STEPS} steps count as not worn.")
        }
    }
}

// ---------------------------------------------------------------- helpers

@Composable
private fun MeasureButton(onOpenMeasure: () -> Unit) {
    FilledTonalButton(onClick = onOpenMeasure) { Text("Measure now") }
}

@Composable
private fun Legend(items: List<Pair<String, Color>>) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        items.forEach { (label, color) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(6.dp))
                Text(label, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** The last [CHART_DAYS] days ending today, oldest first. */
private fun chartDays(today: LocalDate): List<LocalDate> = (CHART_DAYS - 1 downTo 0).map { today.minusDays(it.toLong()) }

private val shortDateFmt: DateTimeFormatter get() = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())

private fun shortDate(d: LocalDate): String = d.format(shortDateFmt)

private fun dayLabels(days: List<LocalDate>, today: LocalDate): List<String> =
    listOf(shortDate(days.first()), shortDate(days[days.size / 2]), if (days.last() == today) "Today" else shortDate(days.last()))

/** "today 2:05 PM", "yesterday 14:05", "Mon 14:05", else "Oct 3". */
private fun whenShort(ms: Long, today: LocalDate, use24Hour: Boolean): String {
    val d = Time.dateOf(ms)
    val clock = clockText(minuteOfDay(ms, Time.zone), use24Hour)
    return when {
        d == today -> "today $clock"
        d == today.minusDays(1) -> "yesterday $clock"
        d.isAfter(today.minusDays(7)) -> d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()) + " " + clock
        else -> shortDate(d)
    }
}
