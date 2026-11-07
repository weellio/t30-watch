package com.t30watch.insights

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/** One night, merged from however many sessions the watch split it into. [nightOf] is the evening's date. */
data class Night(
    val nightOf: LocalDate,
    val bedMs: Long,
    val wakeMs: Long,
    val deepMinutes: Int,
    val lightMinutes: Int,
    val awakeMinutes: Int,
    val wakeCount: Int,
) {
    val asleepMinutes: Int get() = deepMinutes + lightMinutes
}

/** Is this session a daytime nap (starts 10:00–17:59)? */
fun isNap(s: SleepIn, zone: ZoneId): Boolean {
    val h = Instant.ofEpochMilli(s.startMs).atZone(zone).hour
    return h >= Thresholds.NAP_START_HOUR_FROM && h < Thresholds.NAP_START_HOUR_TO
}

/** The evening a session belongs to: anything that starts before noon belongs to the night before. */
fun nightOf(startMs: Long, zone: ZoneId): LocalDate = dateOf(startMs - 12 * 3_600_000L, zone)

/** Drops naps, merges split sessions into one night, and drops nights shorter than [Thresholds.MIN_NIGHT_ASLEEP_MIN]. */
fun mergeNights(sessions: List<SleepIn>, zone: ZoneId): List<Night> =
    sessions.filterNot { isNap(it, zone) }
        .groupBy { nightOf(it.startMs, zone) }
        .map { (d, ss) ->
            Night(
                nightOf = d,
                bedMs = ss.minOf { it.startMs },
                wakeMs = ss.maxOf { it.endMs },
                deepMinutes = ss.sumOf { it.deepMinutes },
                lightMinutes = ss.sumOf { it.lightMinutes },
                awakeMinutes = ss.sumOf { it.awakeMinutes },
                wakeCount = ss.sumOf { it.wakeCount },
            )
        }
        .filter { it.asleepMinutes >= Thresholds.MIN_NIGHT_ASLEEP_MIN }
        .sortedBy { it.nightOf }

data class SleepInsight(
    val status: Status,
    val verdict: String,
    /** Nights in the last 7 days, oldest first. */
    val week: List<Night>,
    /** Nights in the last 14 days, for the chart. */
    val recent: List<Night>,
    val goalMinutes: Int,
    val avgAsleep: Int?,
    val bedtime: ClockSpread?,
    val wake: ClockSpread?,
    val deepPct: Int?,
    val avgWakes: Double?,
    val trend: Trend?,
    val trendText: String?,
    val suggestions: List<String>,
    val nightsMore: Int,
)

private val FILLER_TIPS = listOf(
    "Screens off 30 minutes before bed helps you fall asleep.",
    "Get daylight soon after you wake up.",
    "Keep caffeine to the morning.",
)

fun analyzeSleep(sessions: List<SleepIn>, goalMinutes: Int, today: LocalDate, zone: ZoneId, use24Hour: Boolean): SleepInsight {
    val t = Thresholds
    val nights = mergeNights(sessions, zone)
    // Last night is keyed yesterday. A night keyed today only exists if it started after noon today.
    val end = if (nights.any { it.nightOf == today }) today else today.minusDays(1)
    val start = end.minusDays(6)
    val week = nights.filter { !it.nightOf.isBefore(start) && !it.nightOf.isAfter(end) }
    val recent = nights.filter { !it.nightOf.isBefore(end.minusDays(13)) && !it.nightOf.isAfter(end) }

    val avgAsleep = week.takeIf { it.isNotEmpty() }?.map { it.asleepMinutes }?.average()?.roundToInt()
    val bedtime = clockSpread(week.map { minuteOfDay(it.bedMs, zone) })
    val wake = clockSpread(week.map { minuteOfDay(it.wakeMs, zone) })
    val totalAsleep = week.sumOf { it.asleepMinutes }
    val deepPct = if (totalAsleep > 0) (week.sumOf { it.deepMinutes } * 100.0 / totalAsleep).roundToInt() else null
    val avgWakes = week.takeIf { it.isNotEmpty() }?.map { it.wakeCount }?.average()
    val avgAwake = week.takeIf { it.isNotEmpty() }?.map { it.awakeMinutes }?.average()?.roundToInt() ?: 0

    val daily = nights.associate { it.nightOf to it.asleepMinutes.toDouble() }
    val trend = weekOverWeek(daily, end)
    val trendText = trend?.let {
        val d = it.delta.roundToInt()
        when {
            kotlin.math.abs(d) < 15 -> "About the same as last week."
            d > 0 -> "${durationText(d)} more a night than last week."
            else -> "${durationText(-d)} less a night than last week."
        }
    }

    val enough = week.size >= t.MIN_NIGHTS
    val short = avgAsleep != null && avgAsleep < goalMinutes - t.SLEEP_SHORT_MARGIN_MIN
    val bedSpread = bedtime?.spreadMinutes ?: 0
    val wakeSpread = wake?.spreadMinutes ?: 0

    val tips = mutableListOf<String>()
    if (enough) {
        if (short && wake != null) {
            val target = wake.meanMinute - goalMinutes - avgAwake - t.FALL_ASLEEP_MIN
            val rounded = Math.floorMod(target, 1440) / 15 * 15
            tips += "Aim for bed by ${clockText(rounded, use24Hour)} to reach your ${durationText(goalMinutes)} goal."
        }
        if (bedSpread > t.BEDTIME_SPREAD_WATCH_MIN) tips += "Going to bed within the same 30 minutes helps."
        if (wakeSpread > t.WAKE_SPREAD_WATCH_MIN) tips += "Wake up at the same time every day, weekends too."
        if (deepPct != null && deepPct < t.DEEP_LOW_PCT) tips += "For deeper sleep: be active by day, keep the room cool and dark."
        if (avgWakes != null && avgWakes > t.WAKES_HIGH) tips += "Waking a lot? Stop drinks 2 hours before bed and skip late alcohol."
        for (f in FILLER_TIPS) if (tips.size < 2) tips += f
    }

    val nightsMore = (t.MIN_NIGHTS - week.size).coerceAtLeast(0)
    val (status, verdict) = when {
        week.isEmpty() -> Status.NO_DATA to "No sleep this week yet. Wear the watch to bed tonight."
        !enough -> Status.NO_DATA to "Not enough data yet. Wear the watch to bed ${nightsText(nightsMore)} more."
        short -> Status.WATCH to "You average ${durationText(avgAsleep)} a night, under your ${durationText(goalMinutes)} goal."
        bedSpread > t.BEDTIME_SPREAD_WATCH_MIN -> Status.WATCH to "Enough sleep, but your bedtime varies a lot."
        else -> Status.GOOD to "You average ${durationText(avgAsleep!!)} a night, on track for your goal."
    }

    return SleepInsight(
        status = status,
        verdict = verdict,
        week = week,
        recent = recent,
        goalMinutes = goalMinutes,
        avgAsleep = avgAsleep,
        bedtime = bedtime,
        wake = wake,
        deepPct = deepPct,
        avgWakes = avgWakes,
        trend = trend,
        trendText = trendText,
        suggestions = tips.take(4),
        nightsMore = nightsMore,
    )
}

fun nightsText(n: Int): String = if (n == 1) "1 night" else "$n nights"
