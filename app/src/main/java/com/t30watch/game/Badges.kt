package com.t30watch.game

import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.abs

/** Facts the badges are judged on. Built by [GameEngine]. */
class BadgeContext(
    val days: List<DaySummary>,
    val stepGoal: Int,
    val sleepGoalMinutes: Int,
    val bestStepStreak: Int,
    val bestSleepStreak: Int,
    val bestWornStreak: Int,
    /** Workouts from the first sync onwards. */
    val workouts: List<GameWorkout>,
    val level: Int,
    /** Days where all three quests were done. */
    val perfectDays: Int,
)

object Badges {
    /** Bedtimes within this many minutes of the night before count as "the same". */
    const val STEADY_BEDTIME_WINDOW_MIN = 30
    /** "Early bird" means asleep before 23:00. */
    const val EARLY_BEDTIME_MIN = 23 * 60

    fun evaluate(c: BadgeContext): List<Badge> {
        val days = c.days
        val byDate = days.associateBy { it.date }
        val goalDays = days.count { GameRules.stepGoalMet(it, c.stepGoal) }
        val bestSteps = days.maxOfOrNull { it.steps } ?: 0
        val totalSteps = days.sumOf { it.steps.toLong() }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val bestActive = days.maxOfOrNull { it.activeMinutes } ?: 0
        val bestNight = days.maxOfOrNull { it.sleepMinutes } ?: 0
        val earlyNights = days.count { b -> b.bedtimeMin?.let { it < EARLY_BEDTIME_MIN } == true }
        val measured = days.sumOf { it.bpChecks + it.hrChecks }
        val longestWorkout = c.workouts.maxOfOrNull { it.minutes } ?: 0
        val synced = if (days.any { it.hasData }) 1 else 0

        val first = days.firstOrNull()?.date
        val last = days.lastOrNull()?.date
        val bpRun = if (first == null || last == null) 0 else GameRules.bestRun(first, last) { (byDate[it]?.bpChecks ?: 0) > 0 }

        return listOf(
            Badge("first_sync", "🔗", "Hello, watch", "Sync your first data.", synced, 1),
            Badge("first_goal", "🎯", "Goal getter", "Hit your step goal once.", goalDays, 1),
            Badge("first_measure", "❤️", "Check-in", "Take a heart rate or BP reading by hand.", measured, 1),
            Badge("first_workout", "🏃", "First workout", "Record a workout from the watch.", c.workouts.size, 1),
            Badge("full_night", "🌙", "Full night", "Sleep 6 hours with the watch on.", bestNight, 360, "min"),
            Badge("power_hour", "⚡", "Power hour", "60 active minutes in one day.", bestActive, 60, "min"),
            Badge("perfect_day", "⭐", "Perfect day", "Finish all 3 daily quests.", c.perfectDays, 1),
            Badge("ten_k", "🚶", "10K day", "10,000 steps in one day.", bestSteps, 10_000, "steps"),
            Badge("twenty_k", "🏔️", "20K day", "20,000 steps in one day.", bestSteps, 20_000, "steps"),
            Badge("weekend_warrior", "🎉", "Weekend warrior", "Hit your step goal on Saturday and Sunday.", weekendBest(days, byDate, c.stepGoal), 2, "days"),
            Badge("on_fire", "🔥", "On fire", "Step goal 7 days in a row.", c.bestStepStreak, 7, "days"),
            Badge("unstoppable", "☄️", "Unstoppable", "Step goal 30 days in a row.", c.bestStepStreak, 30, "days"),
            Badge("well_rested", "😴", "Well rested", "Sleep goal 7 nights in a row.", c.bestSleepStreak, 7, "nights"),
            Badge("early_bird", "🐦", "Early bird", "Asleep before 11 PM on 5 nights.", earlyNights, 5, "nights"),
            Badge("steady_bedtime", "⏰", "Steady bedtime", "Same bedtime (±30 min) 5 nights in a row.", steadyBedtimeRun(days), 5, "nights"),
            Badge("always_on", "⌚", "Always on", "Wear the watch 7 days in a row.", c.bestWornStreak, 7, "days"),
            Badge("part_of_you", "💎", "Part of you", "Wear the watch 30 days in a row.", c.bestWornStreak, 30, "days"),
            Badge("pressure_check", "🩺", "Pressure check", "A BP reading 7 days in a row.", bpRun, 7, "days"),
            Badge("five_workouts", "💪", "Five workouts", "Record 5 workouts.", c.workouts.size, 5),
            Badge("regular", "🏅", "Regular", "Record 25 workouts.", c.workouts.size, 25),
            Badge("hour_of_power", "⏱️", "Hour of power", "One workout of 60 minutes.", longestWorkout, 60, "min"),
            Badge("trailblazer", "👑", "Trailblazer", "Reach level 10.", c.level, 10),
            Badge("million", "🌍", "Million steps", "1,000,000 steps in total.", totalSteps, 1_000_000, "steps"),
        )
    }

    /** Best weekend: how many of Saturday + Sunday hit the step goal (0–2). */
    internal fun weekendBest(days: List<DaySummary>, byDate: Map<LocalDate, DaySummary>, stepGoal: Int): Int =
        days.filter { it.date.dayOfWeek == DayOfWeek.SATURDAY || it.date.dayOfWeek == DayOfWeek.SUNDAY }
            .maxOfOrNull { d ->
                val sat = if (d.date.dayOfWeek == DayOfWeek.SATURDAY) d.date else d.date.minusDays(1)
                listOf(sat, sat.plusDays(1)).count { GameRules.stepGoalMet(byDate[it], stepGoal) }
            } ?: 0

    /** Longest run of consecutive nights where each bedtime is within the window of the night before. */
    internal fun steadyBedtimeRun(days: List<DaySummary>): Int {
        var best = 0
        var run = 0
        var prev: DaySummary? = null
        for (d in days.sortedBy { it.date }) {
            val b = d.bedtimeMin
            if (b == null) {
                run = 0
                prev = null
                continue
            }
            val p = prev
            val pb = p?.bedtimeMin
            val follows = p != null && pb != null && p.date.plusDays(1) == d.date && abs(b - pb) <= STEADY_BEDTIME_WINDOW_MIN
            run = if (follows) run + 1 else 1
            if (run > best) best = run
            prev = d
        }
        return best
    }
}
