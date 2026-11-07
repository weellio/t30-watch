package com.t30watch.ui.rewards

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.t30watch.data.settings.Units
import com.t30watch.game.Badge
import com.t30watch.game.GameState
import com.t30watch.game.GameWorkout
import com.t30watch.game.QuestProgress
import com.t30watch.game.Quests
import com.t30watch.game.SportBests
import com.t30watch.game.Streak
import com.t30watch.game.WeekStats
import com.t30watch.game.WorkoutRow
import com.t30watch.game.Xp
import com.t30watch.ui.charts.Bar
import com.t30watch.ui.charts.BarChart
import com.t30watch.util.Fmt
import com.t30watch.util.Time
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Progress: level, streaks, daily quests, badges, weekly recap and the workout log. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RewardsScreen(onBack: () -> Unit, vm: RewardsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val units by vm.units.collectAsStateWithLifecycle()
    val celebrate by vm.toCelebrate.collectAsStateWithLifecycle()
    val newIds by vm.newIds.collectAsStateWithLifecycle()
    var openBadge by remember { mutableStateOf<Badge?>(null) }

    if (celebrate.isNotEmpty()) CelebrationDialog(celebrate) { vm.markSeen(celebrate) }
    openBadge?.let { b -> BadgeDialog(b) { openBadge = null } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Progress") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Text("←", fontSize = 24.sp, fontWeight = FontWeight.Bold) }
                },
            )
        },
    ) { padding ->
        val s = state
        if (s == null) {
            Column(
                Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { CircularProgressIndicator() }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { LevelCard(s) }
            item { StreakRow(s.streaks) }
            item { QuestsCard(s.quests) }

            item { SectionTitle("Badges", "${s.unlockedCount} / ${s.badges.size}") }
            val sorted = s.badges.sortedWith(compareByDescending<Badge> { it.unlocked }.thenByDescending { it.fraction })
            items(sorted.chunked(3)) { row ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { b -> BadgeTile(b, b.id in newIds, Modifier.weight(1f).fillMaxHeight()) { openBadge = b } }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }

            item { RecapCard(s) }

            item { SectionTitle("Workouts", if (s.workouts.isEmpty()) "" else "${s.workouts.size}") }
            if (s.workouts.isEmpty()) {
                item { NoWorkouts() }
            } else {
                if (s.bests.isNotEmpty()) item { BestsCard(s.bests, units) }
                items(s.workouts) { row -> WorkoutCard(row, units) }
            }

            item { XpGuide() }
        }
    }
}

// ---------------------------------------------------------------- level + streaks + quests

@Composable
private fun LevelCard(s: GameState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("Level ${s.level.level}", fontSize = 40.sp, fontWeight = FontWeight.Bold)
                Text(
                    "  ${s.level.name}",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            XpBar(s.level.fraction, height = 14.dp)
            Row(Modifier.fillMaxWidth()) {
                Text("${fmt(s.level.xpInLevel)} / ${fmt(s.level.xpForNext)} XP", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (s.xpToday > 0) {
                    Text("+${fmt(s.xpToday)} today", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                }
            }
            Text("Next: ${s.level.nextName}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StreakRow(streaks: List<Streak>) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        streaks.forEach { st ->
            Card(Modifier.weight(1f).fillMaxHeight()) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(st.kind.emoji, fontSize = 26.sp, modifier = Modifier.alpha(if (st.count > 0) 1f else 0.4f))
                    Text("${st.count}", fontSize = 34.sp, fontWeight = FontWeight.Bold)
                    Text(st.kind.label, style = MaterialTheme.typography.labelLarge)
                    Text(
                        if (st.todayDone) "✓ Today" else "Today: not yet",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (st.todayDone) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (st.todayDone) FontWeight.Bold else FontWeight.Normal,
                    )
                    if (st.best > 0) {
                        Text("Best ${st.best}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun QuestsCard(quests: List<QuestProgress>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Today's quests", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text("${quests.count { it.done }} / ${quests.size}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            quests.forEach { q ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    QuestRing(q, 48.dp)
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(q.title, style = MaterialTheme.typography.titleMedium, fontWeight = if (q.done) FontWeight.Bold else FontWeight.Medium)
                        Text(
                            questStatus(q),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (q.done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Text(
                "All 3 done: +${Quests.XP_ALL_BONUS} XP bonus",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------- badges

@Composable
private fun SectionTitle(title: String, trailing: String) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (trailing.isNotEmpty()) Text(trailing, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 10,000 → 10k, 1,000,000 → 1M; small numbers as they are. */
private fun short(n: Int): String = when {
    n >= 1_000_000 -> if (n % 1_000_000 == 0) "${n / 1_000_000}M" else "%.1fM".format(n / 1_000_000.0)
    n >= 10_000 -> "${n / 1_000}k"
    else -> fmt(n)
}

@Composable
private fun BadgeTile(b: Badge, isNew: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val gold = badgeGold()
    Card(
        onClick = onClick,
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = if (b.unlocked) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        border = if (b.unlocked) BorderStroke(2.dp, gold) else null,
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(min = 120.dp).padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (isNew) Text("NEW", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black, color = gold)
            Text(b.emoji, fontSize = 34.sp, modifier = Modifier.alpha(if (b.unlocked) 1f else 0.3f))
            Text(
                b.name,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (b.unlocked) FontWeight.Bold else FontWeight.Normal,
                color = if (b.unlocked) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!b.unlocked) {
                XpBar(b.fraction, height = 6.dp)
                Text("${short(b.shown)} / ${short(b.target)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun BadgeDialog(b: Badge, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        icon = { Text(b.emoji, fontSize = 48.sp, modifier = Modifier.alpha(if (b.unlocked) 1f else 0.4f)) },
        title = { Text(b.name, textAlign = TextAlign.Center) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(b.description, style = MaterialTheme.typography.bodyLarge)
                if (b.unlocked) {
                    Text("Unlocked", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = badgeGold())
                } else {
                    XpBar(b.fraction)
                    Text(
                        "${fmt(b.shown)} / ${fmt(b.target)}" + if (b.unit.isNotEmpty()) " ${b.unit}" else "",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        },
    )
}

// ---------------------------------------------------------------- weekly recap

@Composable
private fun RecapCard(s: GameState) {
    val r = s.recap
    val hasLast = r.lastWeek.daysWithData > 0
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("This week", style = MaterialTheme.typography.titleMedium)
            if (hasLast) {
                Text(
                    if (r.days == 7) "vs last week" else "vs the same ${r.days} days last week",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            BarChart(
                bars = r.stepsByDay.map { Bar(it.toFloat(), highlight = it >= s.stepGoal) },
                height = 90.dp,
                goal = s.stepGoal.toFloat(),
                xLabels = listOf("M", "T", "W", "T", "F", "S", "S"),
            )
            RecapLine("Steps", fmt(r.thisWeek.steps), pctDelta(r.thisWeek.steps, r.lastWeek.steps, hasLast))
            RecapLine("Active", "${fmt(r.thisWeek.activeMinutes)} min", pctDelta(r.thisWeek.activeMinutes, r.lastWeek.activeMinutes, hasLast))
            RecapLine("Sleep avg", r.thisWeek.sleepAvgMinutes?.let { Time.duration(it) } ?: "–", sleepDelta(r.thisWeek, r.lastWeek))
            RecapLine("Workouts", "${r.thisWeek.workouts}", countDelta(r.thisWeek.workouts, r.lastWeek.workouts, hasLast))
        }
    }
}

/** A change against last week: text and whether it went up. */
private data class Delta(val text: String, val up: Boolean?)

private fun pctDelta(now: Int, before: Int, hasLast: Boolean): Delta? {
    if (!hasLast || before <= 0) return null
    val pct = ((now - before) * 100.0 / before).roundToInt()
    return when {
        pct > 0 -> Delta("▲ $pct%", true)
        pct < 0 -> Delta("▼ ${-pct}%", false)
        else -> Delta("same", null)
    }
}

private fun countDelta(now: Int, before: Int, hasLast: Boolean): Delta? {
    if (!hasLast) return null
    val d = now - before
    return when {
        d > 0 -> Delta("▲ $d", true)
        d < 0 -> Delta("▼ ${-d}", false)
        else -> Delta("same", null)
    }
}

private fun sleepDelta(now: WeekStats, before: WeekStats): Delta? {
    val a = now.sleepAvgMinutes ?: return null
    val b = before.sleepAvgMinutes ?: return null
    val d = a - b
    return when {
        d > 0 -> Delta("▲ ${Time.duration(d)}", true)
        d < 0 -> Delta("▼ ${Time.duration(-d)}", false)
        else -> Delta("same", null)
    }
}

@Composable
private fun RecapLine(label: String, value: String, delta: Delta?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        if (delta != null) {
            Text(
                delta.text,
                style = MaterialTheme.typography.labelLarge,
                color = if (delta.up == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.padding(start = 10.dp).widthIn(min = 72.dp),
            )
        }
    }
}

// ---------------------------------------------------------------- workouts

private val dayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())

private fun workoutWhen(w: GameWorkout): String =
    dayFormat.format(java.time.Instant.ofEpochMilli(w.startMs).atZone(Time.zone)) + " · " + Time.hhmm(w.startMs)

@Composable
private fun NoWorkouts() {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("🏃  No workouts yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Start one on the watch: open its sport menu, pick a sport, press start.", style = MaterialTheme.typography.bodyLarge)
            Text("It shows up here after the next sync.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BestsCard(bests: List<SportBests>, units: Units) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("🏆 Personal bests", style = MaterialTheme.typography.titleMedium)
            bests.forEach { b ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("${b.sport} · ${b.count}×", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        b.distance?.let { Stat("Farthest", Fmt.distance(it.distanceM, units) ?: "–") }
                        b.duration?.let { Stat("Longest", Time.duration(it.minutes)) }
                        b.kcal?.let { Stat("Most kcal", Fmt.kcal(it.kcal) ?: "–") }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkoutCard(row: WorkoutRow, units: Units) {
    val w = row.workout
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(w.sport, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(workoutWhen(w), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Stat("Time", Time.duration(w.minutes))
                Fmt.distance(w.distanceM?.takeIf { it > 0 }, units)?.let { Stat("Distance", it) }
                Fmt.kcal(w.kcal?.takeIf { it > 0 })?.let { Stat("Energy", it) }
                w.avgBpm?.takeIf { it > 0 }?.let { Stat("Avg HR", "$it bpm") }
            }
            if (row.pbs.isNotEmpty()) {
                val gold = badgeGold()
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.pbs.sortedBy { it.ordinal }.forEach { pb ->
                        Surface(shape = RoundedCornerShape(50), color = gold.copy(alpha = 0.18f), border = BorderStroke(1.dp, gold)) {
                            Text(
                                "🏆 ${pb.label}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

// ---------------------------------------------------------------- how XP works

@Composable
private fun XpGuide() {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("How to earn XP", style = MaterialTheme.typography.titleMedium)
            listOf(
                "Steps" to "+1 per ${Xp.STEPS_PER_XP} (max ${Xp.STEPS_CAP})",
                "Active minute" to "+${Xp.PER_ACTIVE_MIN} (max ${Xp.ACTIVE_CAP})",
                "Step goal" to "+${Xp.STEP_GOAL}",
                "Sleep goal" to "+${Xp.SLEEP_GOAL}",
                "Night of 3 h+" to "+${Xp.NIGHT_LOGGED}",
                "Worn 6 h+" to "+${Xp.WORN_DAY}",
                "Workout" to "+${Xp.WORKOUT}, +1 per min",
                "Manual reading" to "+${Xp.MEASURE} (${Xp.MEASURE_DAILY_MAX} a day)",
                "Quest" to "+${Quests.XP_EACH}, all 3 +${Quests.XP_ALL_BONUS}",
            ).forEach { (k, v) ->
                Row {
                    Text(k, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(v, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
            }
            Text(
                "Active minute: a 5-minute block with 300+ steps.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
