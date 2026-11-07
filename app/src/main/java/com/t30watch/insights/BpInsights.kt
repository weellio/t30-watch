package com.t30watch.insights

import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

/** American Heart Association blood-pressure categories. [range] is the plain-words rule (top / bottom number). */
enum class BpCategory(val label: String, val status: Status, val range: String, val meaning: String) {
    NORMAL("Normal", Status.GOOD, "Under 120 and under 80", "Healthy range"),
    ELEVATED("Elevated", Status.WATCH, "120–129 and under 80", "A bit above normal"),
    STAGE_1("High, stage 1", Status.WATCH, "130–139 or 80–89", "High: check with a cuff"),
    STAGE_2("High, stage 2", Status.WATCH, "140+ or 90+", "High: check with a cuff"),
    SEVERE("Very high", Status.DOCTOR, "Over 180 and/or over 120", "Retest. Still high: talk to a doctor now"),
}

/** AHA category for one reading. The higher of the two numbers decides. */
fun bpCategory(sys: Int, dia: Int): BpCategory = when {
    sys > 180 || dia > 120 -> BpCategory.SEVERE
    sys >= 140 || dia >= 90 -> BpCategory.STAGE_2
    sys >= 130 || dia >= 80 -> BpCategory.STAGE_1
    sys >= 120 -> BpCategory.ELEVATED
    else -> BpCategory.NORMAL
}

/** Readings taken while walking are less reliable (and BP really does rise while moving). */
fun isMovingReading(stepsInSlot: Int?): Boolean = stepsInSlot != null && stepsInSlot >= Thresholds.BP_MOVING_STEPS

data class BpInsight(
    val status: Status,
    val verdict: String,
    val trendText: String?,
    val latest: BpPoint?,
    val latestCategory: BpCategory?,
    val latestMoving: Boolean,
    /** 7-day average of still readings (all readings if none were still). */
    val avgSys7: Int?,
    val avgDia7: Int?,
    val avgCategory: BpCategory?,
    val readings7: Int,
    /** Readings in the last 7 days taken while moving (left out of the average when possible). */
    val moving7: Int,
    val trendSys: Trend?,
    val trendDia: Trend?,
    /** Daily averages of still readings, for the chart. */
    val byDay: Map<LocalDate, Pair<Int, Int>>,
    val readingsMore: Int,
)

fun analyzeBp(bp: List<BpPoint>, slots: List<StepSlot>, today: LocalDate, zone: ZoneId): BpInsight {
    val t = Thresholds
    val steps = StepIndex(slots)
    val sorted = bp.sortedBy { it.timeMs }
    val latest = sorted.lastOrNull()
    val moving = { p: BpPoint -> isMovingReading(steps.stepsAt(p.timeMs)) }
    val still = sorted.filterNot(moving)

    val weekStart = today.minusDays(6)
    fun inWeek(p: BpPoint) = dateOf(p.timeMs, zone).let { !it.isBefore(weekStart) && !it.isAfter(today) }
    val week = sorted.filter(::inWeek)
    val weekStill = week.filterNot(moving)
    val avgSet = weekStill.ifEmpty { week }
    val avgSys = avgSet.takeIf { it.isNotEmpty() }?.map { it.systolic }?.average()?.roundToInt()
    val avgDia = avgSet.takeIf { it.isNotEmpty() }?.map { it.diastolic }?.average()?.roundToInt()
    val avgCat = if (avgSys != null && avgDia != null) bpCategory(avgSys, avgDia) else null

    val byDayPts = still.groupBy { dateOf(it.timeMs, zone) }
    val byDay = byDayPts.mapValues { (_, v) -> v.map { it.systolic }.average().roundToInt() to v.map { it.diastolic }.average().roundToInt() }.toSortedMap()
    val trendSys = weekOverWeek(byDay.mapValues { it.value.first.toDouble() }, today)
    val trendDia = weekOverWeek(byDay.mapValues { it.value.second.toDouble() }, today)
    val more = daysUntilTrend(byDay.keys, today)

    val ds = trendSys?.delta?.roundToInt()
    val trendText = when {
        latest == null -> null
        ds == null -> "Week-to-week trend: wear the watch ${daysText(more.coerceAtLeast(1))} more."
        abs(ds) < t.BP_TREND_STEADY -> "Steady vs last week."
        ds < 0 -> "Top number down ${-ds} vs last week."
        else -> "Top number up $ds vs last week."
    }

    val latestCat = latest?.let { bpCategory(it.systolic, it.diastolic) }
    val readingsMore = (t.MIN_BP_READINGS - week.size).coerceAtLeast(0)
    val (status, verdict) = when {
        latest == null -> Status.NO_DATA to "No readings yet. Wear the watch, or tap Measure now."
        latestCat == BpCategory.SEVERE -> Status.DOCTOR to "Latest reading is very high. Sit still 5 minutes and measure again. Still this high: talk to a doctor now."
        avgCat == null || week.size < t.MIN_BP_READINGS ->
            (latestCat?.status ?: Status.NO_DATA) to "Latest: ${latestCat!!.label.lowercase()}. Need ${readingsMore.coerceAtLeast(1)} more readings for a weekly average."
        avgCat == BpCategory.SEVERE -> Status.DOCTOR to "Your week's average is very high. Talk to a doctor now."
        avgCat == BpCategory.NORMAL -> Status.GOOD to "Your 7-day average is in the normal range."
        avgCat == BpCategory.ELEVATED -> Status.WATCH to "Your 7-day average is a bit above normal."
        avgCat == BpCategory.STAGE_1 -> Status.WATCH to "Your 7-day average is in the high range (stage 1). Check with a home cuff."
        else -> Status.WATCH to "Your 7-day average is high (stage 2). Check with a home cuff, and bring it up at your next checkup."
    }

    return BpInsight(
        status = status,
        verdict = verdict,
        trendText = trendText,
        latest = latest,
        latestCategory = latestCat,
        latestMoving = latest?.let(moving) ?: false,
        avgSys7 = avgSys,
        avgDia7 = avgDia,
        avgCategory = avgCat,
        readings7 = week.size,
        moving7 = week.size - weekStill.size,
        trendSys = trendSys,
        trendDia = trendDia,
        byDay = byDay,
        readingsMore = readingsMore,
    )
}
