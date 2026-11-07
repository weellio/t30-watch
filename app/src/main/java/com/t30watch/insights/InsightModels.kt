package com.t30watch.insights

/*
 * Plain inputs for the insight maths. No Android or Room types here, so everything in this
 * package runs in a plain JVM unit test. The ViewModel maps Room entities onto these.
 */

/** One heart-rate reading. */
data class HrPoint(val timeMs: Long, val bpm: Int)

/** Steps in one 5-minute activity slot starting at [startMs]. */
data class StepSlot(val startMs: Long, val steps: Int)

/** One blood-pressure reading. */
data class BpPoint(val timeMs: Long, val systolic: Int, val diastolic: Int)

/** One sleep session as the watch reports it (a night may be split into several). */
data class SleepIn(
    val startMs: Long,
    val endMs: Long,
    val deepMinutes: Int,
    val lightMinutes: Int,
    val awakeMinutes: Int,
    val wakeCount: Int,
)

/** A half-open time range [startMs, endMs). Used for sleep sessions and workouts. */
data class TimeSpan(val startMs: Long, val endMs: Long) {
    operator fun contains(t: Long): Boolean = t >= startMs && t < endMs
}

/** Colour-coded status shown on every card. */
enum class Status { GOOD, WATCH, DOCTOR, NO_DATA }

/** All tunable numbers in one place. */
object Thresholds {
    const val SLOT_MS = 5 * 60_000L

    // ---- Steps in one 5-minute slot -> what you were doing
    /** 0 steps = resting. */
    const val LIGHT_MIN_STEPS = 1
    /** ~60 steps a minute: steady walking. */
    const val ACTIVE_MIN_STEPS = 300
    /** ~100 steps a minute: brisk walking, moderate exercise. */
    const val BRISK_MIN_STEPS = 500

    // ---- Resting heart rate
    /** A resting sample also needs no steps in the 10 minutes before it (so recovery after a walk doesn't count). */
    const val RESTING_QUIET_MS = 10 * 60_000L
    /** Resting HR for a day = this percentile of that day's quiet samples. */
    const val RESTING_PERCENTILE = 0.25
    /** Need at least 30 minutes of quiet readings to call a day's resting HR. */
    const val MIN_RESTING_SAMPLES_PER_DAY = 6
    const val RESTING_FIT_LOW = 40
    const val RESTING_TYPICAL_LOW = 60
    const val RESTING_TYPICAL_HIGH = 100
    /** 90-100 is still typical, but on the high side. */
    const val RESTING_HIGH_SIDE = 90
    /** "Consistently" = this many of the last 7 days. */
    const val RESTING_CONSISTENT_DAYS = 3
    const val HR_TREND_STEADY_BPM = 2
    const val HR_TREND_WATCH_BPM = 5
    /** Light movement but heart rate above this: worth a note. */
    const val LIGHT_MOVEMENT_HIGH_BPM = 150

    // ---- Trends
    /** Each week needs this many days with data before comparing weeks. */
    const val MIN_DAYS_PER_WEEK = 3

    // ---- Blood pressure
    /** A reading in a slot with this many steps or more was taken while moving. */
    const val BP_MOVING_STEPS = 100
    const val MIN_BP_READINGS = 3
    /** mmHg change in the top number that counts as a real move. */
    const val BP_TREND_STEADY = 3

    // ---- Sleep
    /** Shorter than this asleep = a nap, not a night. */
    const val MIN_NIGHT_ASLEEP_MIN = 180
    /** Sessions that start between these hours (local) are naps. */
    const val NAP_START_HOUR_FROM = 10
    const val NAP_START_HOUR_TO = 18
    const val MIN_NIGHTS = 3
    const val BEDTIME_SPREAD_OK_MIN = 30
    const val BEDTIME_SPREAD_WATCH_MIN = 45
    const val WAKE_SPREAD_WATCH_MIN = 60
    /** Average this far under the goal = short sleep. */
    const val SLEEP_SHORT_MARGIN_MIN = 30
    const val DEEP_LOW_PCT = 12
    const val WAKES_HIGH = 4.0
    /** Typical time to fall asleep, added when suggesting a bedtime. */
    const val FALL_ASLEEP_MIN = 15

    // ---- Steps per day
    /** A day under this many steps is treated as "watch not worn" and left out. */
    const val NOT_WORN_DAY_STEPS = 200
    const val STEP_TREND_STEADY_PCT = 10
    /** Brisk minutes a week recommended for adults. */
    const val WEEKLY_BRISK_GOAL_MIN = 150
}
