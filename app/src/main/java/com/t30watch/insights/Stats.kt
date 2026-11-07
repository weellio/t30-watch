package com.t30watch.insights

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Percentile with linear interpolation, [p] in 0..1. Null for an empty list. */
fun percentile(values: List<Int>, p: Double): Double? {
    if (values.isEmpty()) return null
    val s = values.sorted()
    val pos = p.coerceIn(0.0, 1.0) * (s.size - 1)
    val lo = floor(pos).toInt()
    val hi = ceil(pos).toInt()
    return s[lo] + (s[hi] - s[lo]) * (pos - lo)
}

fun dateOf(ms: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

fun minuteOfDay(ms: Long, zone: ZoneId): Int = Instant.ofEpochMilli(ms).atZone(zone).let { it.hour * 60 + it.minute }

/** This week's average vs last week's. */
data class Trend(val current: Double, val previous: Double, val currentDays: Int, val previousDays: Int) {
    val delta: Double get() = current - previous
    /** Change in percent of last week (0 when last week was 0). */
    val pct: Double get() = if (previous == 0.0) 0.0 else delta / previous * 100.0
}

/** Values for the 7 days ending [end] (inclusive). */
fun <T> lastWeek(daily: Map<LocalDate, T>, end: LocalDate): List<T> = (0L..6L).mapNotNull { daily[end.minusDays(it)] }

/**
 * Average of [daily] over the 7 days ending [end] vs the 7 days before that.
 * Null unless both weeks have at least [minDays] days with data.
 */
fun weekOverWeek(daily: Map<LocalDate, Double>, end: LocalDate, minDays: Int = Thresholds.MIN_DAYS_PER_WEEK): Trend? {
    val cur = lastWeek(daily, end)
    val prev = lastWeek(daily, end.minusDays(7))
    if (cur.size < minDays || prev.size < minDays) return null
    return Trend(cur.average(), prev.average(), cur.size, prev.size)
}

/**
 * How many more days of wearing the watch until [weekOverWeek] has enough data,
 * assuming it's worn every day from tomorrow on. 0 when a trend is already possible.
 */
fun daysUntilTrend(daysWithData: Set<LocalDate>, end: LocalDate, minDays: Int = Thresholds.MIN_DAYS_PER_WEEK): Int {
    for (n in 0..14) {
        val e = end.plusDays(n.toLong())
        val has = { d: LocalDate -> d in daysWithData || d.isAfter(end) }
        val cur = (0L..6L).count { has(e.minusDays(it)) }
        val prev = (7L..13L).count { has(e.minusDays(it)) }
        if (cur >= minDays && prev >= minDays) return n
    }
    return 14
}

/** Average clock time and how much it varies, both in minutes. */
data class ClockSpread(val meanMinute: Int, val spreadMinutes: Int)

private const val DAY_MIN = 1440.0

/** Signed difference a - b on a 24 h clock, in -720..720 minutes (23:50 vs 00:10 = -20). */
fun clockDiff(a: Double, b: Double): Double = ((a - b) % DAY_MIN + DAY_MIN + 720.0) % DAY_MIN - 720.0

/**
 * Circular mean and RMS spread of clock times given as minutes after midnight.
 * Handles the midnight wrap: 23:45 and 00:15 average to 00:00 with a 15 minute spread.
 */
fun clockSpread(minutesOfDay: List<Int>): ClockSpread? {
    if (minutesOfDay.isEmpty()) return null
    val k = 2 * PI / DAY_MIN
    val sx = minutesOfDay.sumOf { cos(it * k) }
    val sy = minutesOfDay.sumOf { sin(it * k) }
    var mean = atan2(sy, sx) / k
    if (mean < 0) mean += DAY_MIN
    val rms = sqrt(minutesOfDay.map { clockDiff(it.toDouble(), mean).let { d -> d * d } }.average())
    return ClockSpread(mean.roundToInt() % DAY_MIN.toInt(), rms.roundToInt())
}

/** "23:05" or "11:05 PM". */
fun clockText(minuteOfDay: Int, use24Hour: Boolean): String {
    val m = ((minuteOfDay % 1440) + 1440) % 1440
    val h = m / 60
    val mm = m % 60
    return if (use24Hour) "%02d:%02d".format(h, mm)
    else "%d:%02d %s".format(if (h % 12 == 0) 12 else h % 12, mm, if (h < 12) "AM" else "PM")
}

/** "7 h 40 min", "8 h", "45 min". */
fun durationText(minutes: Int): String = when {
    minutes < 60 -> "$minutes min"
    minutes % 60 == 0 -> "${minutes / 60} h"
    else -> "${minutes / 60} h ${minutes % 60} min"
}

/** "8,000". */
fun countText(n: Int): String = "%,d".format(n)

/** Looks up steps for a heart/BP timestamp. Records share the slot's start time; falls back to the 5-minute floor. */
class StepIndex(slots: List<StepSlot>) {
    private val byStart: Map<Long, Int> = slots.associate { it.startMs to it.steps }

    fun stepsAt(t: Long): Int? = byStart[t] ?: byStart[t - Math.floorMod(t, Thresholds.SLOT_MS)]

    /** True if no slot in the [ms] before [t] has steps. Missing slots count as quiet. */
    fun quietBefore(t: Long, ms: Long): Boolean {
        var x = t - Thresholds.SLOT_MS
        while (x >= t - ms) {
            if ((stepsAt(x) ?: 0) > 0) return false
            x -= Thresholds.SLOT_MS
        }
        return true
    }
}
