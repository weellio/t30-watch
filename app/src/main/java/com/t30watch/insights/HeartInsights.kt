package com.t30watch.insights

import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

/** What the wearer was doing, judged from steps in the same 5-minute slot. */
enum class ActivityLevel(val label: String) {
    RESTING("Resting"),
    LIGHT("Light movement"),
    ACTIVE("Walking"),
    BRISK("Brisk or exercise"),
    WORKOUT("Workout"),
    UNKNOWN("No step data"),
}

fun activityLevel(stepsInSlot: Int?): ActivityLevel = when {
    stepsInSlot == null -> ActivityLevel.UNKNOWN
    stepsInSlot >= Thresholds.BRISK_MIN_STEPS -> ActivityLevel.BRISK
    stepsInSlot >= Thresholds.ACTIVE_MIN_STEPS -> ActivityLevel.ACTIVE
    stepsInSlot >= Thresholds.LIGHT_MIN_STEPS -> ActivityLevel.LIGHT
    else -> ActivityLevel.RESTING
}

/** A heart reading put in context: what you were doing and whether the number fits that. */
data class HeartContext(val level: ActivityLevel, val note: String, val status: Status)

fun heartContext(bpm: Int, stepsInSlot: Int?): HeartContext {
    val level = activityLevel(stepsInSlot)
    val t = Thresholds
    return when (level) {
        ActivityLevel.RESTING, ActivityLevel.UNKNOWN -> {
            val rest = if (level == ActivityLevel.RESTING) "at rest" else "if resting"
            when {
                bpm < t.RESTING_FIT_LOW -> HeartContext(level, "very low $rest", Status.WATCH)
                bpm < t.RESTING_TYPICAL_LOW -> HeartContext(level, "low $rest, common if fit", Status.GOOD)
                bpm <= t.RESTING_TYPICAL_HIGH -> HeartContext(level, "normal $rest", Status.GOOD)
                else -> HeartContext(level, "high $rest", Status.WATCH)
            }
        }
        ActivityLevel.LIGHT ->
            if (bpm > t.LIGHT_MOVEMENT_HIGH_BPM) HeartContext(level, "high for light movement", Status.WATCH)
            else HeartContext(level, "normal while moving", Status.GOOD)
        ActivityLevel.ACTIVE, ActivityLevel.BRISK, ActivityLevel.WORKOUT ->
            HeartContext(level, "raised by exercise, expected", Status.GOOD)
    }
}

/** A heart-rate zone as a share of max heart rate. */
data class HrZone(val number: Int, val name: String, val lowPct: Int, val highPct: Int, val lowBpm: Int, val highBpm: Int, val meaning: String)

data class ZoneTime(val zone: HrZone, val minutes: Int)

/** Estimated max heart rate (Tanaka): 208 - 0.7 x age. */
fun maxHeartRate(age: Int): Int = (208 - 0.7 * age).roundToInt()

fun heartZones(maxHr: Int): List<HrZone> {
    fun bpm(pct: Int) = (maxHr * pct / 100.0).roundToInt()
    val defs = listOf(
        Triple("Easy", 50, "Warm-up, easy walk"),
        Triple("Steady", 60, "Builds base fitness"),
        Triple("Moderate", 70, "Cardio, gets you puffing"),
        Triple("Hard", 80, "Short, hard efforts"),
        Triple("Max", 90, "All-out bursts"),
    )
    return defs.mapIndexed { i, (name, low, meaning) ->
        val high = low + 10
        HrZone(i + 1, name, low, high, bpm(low), if (i == defs.lastIndex) maxHr else bpm(high) - 1, meaning)
    }
}

/** Zone for a reading, or null below zone 1. Readings above max count as zone 5. */
fun zoneIndex(bpm: Int, zones: List<HrZone>): Int? {
    if (zones.isEmpty() || bpm < zones.first().lowBpm) return null
    return zones.indexOfLast { bpm >= it.lowBpm }
}

/** Level for one reading, with workouts (sport mode on the watch) taking priority over steps. */
fun levelFor(t: Long, steps: StepIndex, workouts: List<TimeSpan>): ActivityLevel =
    if (workouts.any { t in it }) ActivityLevel.WORKOUT else activityLevel(steps.stepsAt(t))

/**
 * Resting heart rate per day: the lower quartile of that day's quiet readings.
 * Quiet = asleep, or 0 steps in the slot and in the 10 minutes before. Workouts never count.
 * Days with fewer than [Thresholds.MIN_RESTING_SAMPLES_PER_DAY] quiet readings are left out.
 */
fun restingByDay(
    hr: List<HrPoint>,
    slots: List<StepSlot>,
    sleep: List<TimeSpan>,
    workouts: List<TimeSpan>,
    zone: ZoneId,
): Map<LocalDate, Int> {
    val steps = StepIndex(slots)
    val quiet = hr.filter { p ->
        when {
            workouts.any { p.timeMs in it } -> false
            sleep.any { p.timeMs in it } -> true
            else -> steps.stepsAt(p.timeMs) == 0 && steps.quietBefore(p.timeMs, Thresholds.RESTING_QUIET_MS)
        }
    }
    return quiet.groupBy { dateOf(it.timeMs, zone) }
        .filterValues { it.size >= Thresholds.MIN_RESTING_SAMPLES_PER_DAY }
        .mapValues { (_, v) -> percentile(v.map { it.bpm }, Thresholds.RESTING_PERCENTILE)!!.roundToInt() }
        .toSortedMap()
}

data class HeartInsight(
    val status: Status,
    /** One-line headline. */
    val verdict: String,
    /** Week-over-week line, or a "wear it N more days" line when there's not enough yet. */
    val trendText: String?,
    val trend: Trend?,
    val daysUntilTrend: Int,
    /** Most recent day's resting HR. */
    val latestResting: Int?,
    val latestRestingDay: LocalDate?,
    /** Average of daily resting HR over the last 7 days. */
    val restingAvg7: Int?,
    val restingByDay: Map<LocalDate, Int>,
    /** Mean of all readings in the last 7 days, by what you were doing. */
    val avgAtRest: Int?,
    val avgLight: Int?,
    val avgMoving: Int?,
    val maxHr: Int,
    val zones: List<HrZone>,
    /** Minutes in each zone over the last 7 days (each reading stands for 5 minutes). */
    val zoneTimes: List<ZoneTime>,
    val samples7: Int,
)

fun analyzeHeart(
    hr: List<HrPoint>,
    slots: List<StepSlot>,
    sleep: List<TimeSpan>,
    workouts: List<TimeSpan>,
    age: Int,
    today: LocalDate,
    zone: ZoneId,
): HeartInsight {
    val t = Thresholds
    val steps = StepIndex(slots)
    val resting = restingByDay(hr, slots, sleep, workouts, zone)
    val weekStart = today.minusDays(6)
    val week = hr.filter { !dateOf(it.timeMs, zone).isBefore(weekStart) && !dateOf(it.timeMs, zone).isAfter(today) }

    val byLevel = week.groupBy { p -> if (sleep.any { p.timeMs in it }) ActivityLevel.RESTING else levelFor(p.timeMs, steps, workouts) }
    fun avgOf(vararg levels: ActivityLevel): Int? =
        levels.flatMap { byLevel[it].orEmpty() }.takeIf { it.isNotEmpty() }?.map { it.bpm }?.average()?.roundToInt()

    val maxHr = maxHeartRate(age)
    val zones = heartZones(maxHr)
    val counts = IntArray(zones.size)
    week.forEach { p -> zoneIndex(p.bpm, zones)?.let { counts[it]++ } }
    val slotMin = (t.SLOT_MS / 60_000L).toInt()
    val zoneTimes = zones.mapIndexed { i, z -> ZoneTime(z, counts[i] * slotMin) }

    val restWeek = lastWeek(resting, today)
    val restingAvg7 = restWeek.takeIf { it.isNotEmpty() }?.average()?.roundToInt()
    val latestDay = resting.keys.lastOrNull()
    val latest = latestDay?.let { resting[it] }

    val trend = weekOverWeek(resting.mapValues { it.value.toDouble() }, today)
    val more = daysUntilTrend(resting.keys, today)
    val d = trend?.delta?.roundToInt()
    val trendText = when {
        d == null && resting.isEmpty() -> null
        d == null -> "Week-to-week trend: wear the watch ${daysText(more.coerceAtLeast(1))} more."
        abs(d) < t.HR_TREND_STEADY_BPM -> "Resting heart rate is steady vs last week."
        d < 0 -> "Down ${-d} bpm vs last week: usually a sign of better fitness or recovery."
        else -> "Up $d bpm vs last week. Stress, poor sleep, illness, or hard training can do this."
    }

    val highDays = restWeek.count { it > t.RESTING_TYPICAL_HIGH }
    val lowDays = restWeek.count { it < t.RESTING_FIT_LOW }
    val ref = restingAvg7 ?: latest
    val (status, verdict) = when {
        ref == null && hr.isEmpty() -> Status.NO_DATA to "No heart rate yet. Wear the watch and sync."
        ref == null -> Status.NO_DATA to "Not enough quiet readings yet. Wear the watch 1 more day and night."
        highDays >= t.RESTING_CONSISTENT_DAYS -> Status.DOCTOR to "Resting heart rate over 100 on $highDays days. Talk to a doctor."
        lowDays >= t.RESTING_CONSISTENT_DAYS -> Status.DOCTOR to "Resting heart rate under 40 on $lowDays days. Talk to a doctor."
        ref > t.RESTING_TYPICAL_HIGH -> Status.WATCH to "Resting $ref bpm: higher than typical. Check again in a day or two."
        ref < t.RESTING_FIT_LOW -> Status.WATCH to "Resting $ref bpm: lower than typical. Check again in a day or two."
        d != null && d >= t.HR_TREND_WATCH_BPM -> Status.WATCH to "Resting $ref bpm, up $d from last week."
        ref >= t.RESTING_HIGH_SIDE -> Status.WATCH to "Resting $ref bpm: high end of typical."
        ref < t.RESTING_TYPICAL_LOW -> Status.GOOD to "Resting $ref bpm: in the fit range."
        else -> Status.GOOD to "Resting $ref bpm: in the typical adult range."
    }

    return HeartInsight(
        status = status,
        verdict = verdict,
        trendText = trendText,
        trend = trend,
        daysUntilTrend = more,
        latestResting = latest,
        latestRestingDay = latestDay,
        restingAvg7 = restingAvg7,
        restingByDay = resting,
        avgAtRest = avgOf(ActivityLevel.RESTING),
        avgLight = avgOf(ActivityLevel.LIGHT),
        avgMoving = avgOf(ActivityLevel.ACTIVE, ActivityLevel.BRISK, ActivityLevel.WORKOUT),
        maxHr = maxHr,
        zones = zones,
        zoneTimes = zoneTimes,
        samples7 = week.size,
    )
}

/** "1 day", "3 days". */
fun daysText(n: Int): String = if (n == 1) "1 day" else "$n days"
