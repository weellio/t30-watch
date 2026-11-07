package com.t30watch.insights

import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

data class ActivityInsight(
    val status: Status,
    val verdict: String,
    val trendText: String?,
    val trend: Trend?,
    val goal: Int,
    /** Average over the last 7 complete days the watch was worn (today is left out: it isn't over yet). */
    val avgSteps7: Int?,
    val daysCounted: Int,
    val daysAtGoal: Int,
    val todaySteps: Int,
    /** Minutes in slots at a brisk pace over the last 7 days, including today. */
    val briskMinutes7: Int,
    /** Steps per worn day over the last 14 days, including today. */
    val recent: Map<LocalDate, Int>,
    val daysMore: Int,
)

/**
 * [dailySteps]: steps per day (the watch's day total, or the sum of its slots).
 * Days under [Thresholds.NOT_WORN_DAY_STEPS] are treated as "not worn" and left out.
 */
fun analyzeActivity(dailySteps: Map<LocalDate, Int>, slots: List<StepSlot>, goal: Int, today: LocalDate, zone: ZoneId): ActivityInsight {
    val t = Thresholds
    val worn = dailySteps.filterValues { it >= t.NOT_WORN_DAY_STEPS }
    val end = today.minusDays(1)
    val week = lastWeek(worn, end)
    val avg = week.takeIf { it.isNotEmpty() }?.average()?.roundToInt()
    val atGoal = week.count { it >= goal }
    val todaySteps = dailySteps[today] ?: 0

    val weekStart = today.minusDays(6)
    val slotMin = (t.SLOT_MS / 60_000L).toInt()
    val brisk = slots.count { s ->
        s.steps >= t.BRISK_MIN_STEPS && dateOf(s.startMs, zone).let { !it.isBefore(weekStart) && !it.isAfter(today) }
    } * slotMin

    val trend = weekOverWeek(worn.mapValues { it.value.toDouble() }, end)
    val more = daysUntilTrend(worn.keys, end)
    val pct = trend?.pct?.roundToInt()
    val trendText = when {
        pct == null && worn.isEmpty() -> null
        pct == null -> "Week-to-week trend: wear the watch ${daysText(more.coerceAtLeast(1))} more."
        abs(pct) < t.STEP_TREND_STEADY_PCT -> "About the same as last week."
        pct > 0 -> "Up $pct% vs last week."
        else -> "Down ${-pct}% vs last week."
    }

    val daysMore = (t.MIN_DAYS_PER_WEEK - week.size).coerceAtLeast(0)
    val (status, verdict) = when {
        avg == null && todaySteps == 0 -> Status.NO_DATA to "No steps yet. Wear the watch and sync."
        avg == null -> Status.NO_DATA to "Today so far: ${countText(todaySteps)} steps. Daily average starts tomorrow."
        week.size < t.MIN_DAYS_PER_WEEK && avg >= goal -> Status.GOOD to "${countText(avg)} steps a day so far: above your goal."
        week.size < t.MIN_DAYS_PER_WEEK -> Status.NO_DATA to "${countText(avg)} steps a day so far. Wear the watch ${daysText(daysMore)} more for a weekly view."
        avg >= goal -> Status.GOOD to "You average ${countText(avg)} steps a day: above your ${countText(goal)} goal."
        else -> Status.WATCH to "You average ${countText(avg)} steps a day, under your ${countText(goal)} goal."
    }

    val recent = worn.filterKeys { !it.isBefore(today.minusDays(13)) && !it.isAfter(today) }.toSortedMap()

    return ActivityInsight(
        status = status,
        verdict = verdict,
        trendText = trendText,
        trend = trend,
        goal = goal,
        avgSteps7 = avg,
        daysCounted = week.size,
        daysAtGoal = atGoal,
        todaySteps = todaySteps,
        briskMinutes7 = brisk,
        recent = recent,
        daysMore = daysMore,
    )
}
