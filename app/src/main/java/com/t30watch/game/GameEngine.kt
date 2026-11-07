package com.t30watch.game

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** XP points. Every day from the first sync to today is scored; the total is recomputed, never stored. */
object Xp {
    /** 1 XP per 100 steps, up to 300 a day. */
    const val STEPS_PER_XP = 100
    const val STEPS_CAP = 300
    /** 2 XP per active minute, up to 240 a day. */
    const val PER_ACTIVE_MIN = 2
    const val ACTIVE_CAP = 240
    const val STEP_GOAL = 50
    const val SLEEP_GOAL = 50
    /** For any night of 3 h or more, goal or not. */
    const val NIGHT_LOGGED = 15
    const val WORN_DAY = 20
    /** Per workout: 40 + 1 per minute (minutes capped at 120). */
    const val WORKOUT = 40
    const val WORKOUT_MIN_CAP = 120
    /** Per manual measurement, up to 3 a day. */
    const val MEASURE = 10
    const val MEASURE_DAILY_MAX = 3

    fun forDay(s: DaySummary, quests: List<QuestProgress>, stepGoal: Int, sleepGoalMinutes: Int): XpBreakdown = XpBreakdown(
        steps = (s.steps / STEPS_PER_XP).coerceAtMost(STEPS_CAP),
        active = (s.activeMinutes * PER_ACTIVE_MIN).coerceAtMost(ACTIVE_CAP),
        goals = if (GameRules.stepGoalMet(s, stepGoal)) STEP_GOAL else 0,
        sleep = (if (GameRules.sleepGoalMet(s, sleepGoalMinutes)) SLEEP_GOAL else 0) +
            (if (s.sleepMinutes >= GameRules.NIGHT_MIN_MINUTES) NIGHT_LOGGED else 0),
        worn = if (GameRules.worn(s)) WORN_DAY else 0,
        workouts = s.workouts.sumOf { WORKOUT + it.minutes.coerceAtMost(WORKOUT_MIN_CAP) },
        measures = (s.bpChecks + s.hrChecks).coerceAtMost(MEASURE_DAILY_MAX) * MEASURE,
        quests = Quests.xpFor(quests),
    )
}

object GameEngine {
    fun compute(input: GameInput): GameState {
        val today = input.today
        val epoch = GameRules.epochDay(input)
        val days = GameRules.summarize(input, epoch)
        val list = days.values.toList()

        // XP and quests, day by day.
        var totalXp = 0
        var xpToday = 0
        var perfectDays = 0
        for (s in list) {
            val quests = Quests.progressFor(s.date, days, input.stepGoal)
            if (quests.isNotEmpty() && quests.all { it.done }) perfectDays++
            val xp = Xp.forDay(s, quests, input.stepGoal, input.sleepGoalMinutes).total
            totalXp += xp
            if (s.date == today) xpToday = xp
        }
        val level = Levels.forXp(totalXp)

        val streaks = listOf(
            GameRules.streak(StreakKind.STEPS, today, epoch) { GameRules.stepGoalMet(days[it], input.stepGoal) },
            GameRules.streak(StreakKind.SLEEP, today, epoch) { GameRules.sleepGoalMet(days[it], input.sleepGoalMinutes) },
            GameRules.streak(StreakKind.WORN, today, epoch) { GameRules.worn(days[it]) },
        )

        val workouts = list.flatMap { it.workouts }
        val badges = Badges.evaluate(
            BadgeContext(
                days = list,
                stepGoal = input.stepGoal,
                sleepGoalMinutes = input.sleepGoalMinutes,
                bestStepStreak = streaks[0].best,
                bestSleepStreak = streaks[1].best,
                bestWornStreak = streaks[2].best,
                workouts = workouts,
                level = level.level,
                perfectDays = perfectDays,
            ),
        )

        val bests = personalBests(workouts)
        return GameState(
            level = level,
            xpToday = xpToday,
            streaks = streaks,
            quests = Quests.progressFor(today, days, input.stepGoal),
            badges = badges,
            recap = weeklyRecap(today, days),
            workouts = workoutRows(workouts, bests),
            bests = bests.values.sortedByDescending { it.count },
            stepGoal = input.stepGoal,
            since = epoch,
        )
    }

    // ---------------------------------------------------------------- weekly recap

    fun weeklyRecap(today: LocalDate, days: Map<LocalDate, DaySummary>): WeeklyRecap {
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val n = (today.toEpochDay() - monday.toEpochDay()).toInt() + 1
        fun stats(from: LocalDate): WeekStats {
            val ds = (0 until n).mapNotNull { days[from.plusDays(it.toLong())] }
            val nights = ds.filter { it.sleepMinutes > 0 }
            return WeekStats(
                steps = ds.sumOf { it.steps },
                activeMinutes = ds.sumOf { it.activeMinutes },
                sleepAvgMinutes = if (nights.isEmpty()) null else nights.sumOf { it.sleepMinutes } / nights.size,
                workouts = ds.sumOf { it.workouts.size },
                daysWithData = ds.count { it.hasData },
            )
        }
        val byDay = (0 until 7).map { days[monday.plusDays(it.toLong())]?.steps ?: 0 }
        return WeeklyRecap(stats(monday), stats(monday.minusWeeks(1)), n, byDay)
    }

    // ---------------------------------------------------------------- workouts

    /** Best distance, duration and kcal per sport. Ties go to the earlier session (it got there first). */
    fun personalBests(workouts: List<GameWorkout>): Map<String, SportBests> =
        workouts.groupBy { it.sport }.mapValues { (sport, ws) ->
            SportBests(
                sport = sport,
                count = ws.size,
                distance = best(ws) { (it.distanceM ?: 0).toDouble() },
                duration = best(ws) { it.minutes.toDouble() },
                kcal = best(ws) { it.kcal ?: 0.0 },
            )
        }

    private fun best(ws: List<GameWorkout>, value: (GameWorkout) -> Double): GameWorkout? =
        ws.filter { value(it) > 0.0 }
            .sortedWith(compareByDescending<GameWorkout> { value(it) }.thenBy { it.startMs })
            .firstOrNull()

    /** Newest first, with PB markers. A sport with one session has nothing to beat yet, so no markers. */
    fun workoutRows(workouts: List<GameWorkout>, bests: Map<String, SportBests>): List<WorkoutRow> =
        workouts.sortedByDescending { it.startMs }.map { w ->
            val b = bests[w.sport]
            val pbs = if (b == null || b.count < 2) emptySet() else buildSet {
                if (b.distance === w) add(PbKind.DISTANCE)
                if (b.duration === w) add(PbKind.DURATION)
                if (b.kcal === w) add(PbKind.KCAL)
            }
            WorkoutRow(w, pbs)
        }
}
