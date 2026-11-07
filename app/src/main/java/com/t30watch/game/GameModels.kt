package com.t30watch.game

import java.time.LocalDate
import java.time.ZoneId

/*
 * Gamification rules: inputs and outputs.
 * Everything in GameModels / DaySummaries / Levels / Quests / Badges / GameEngine is plain JVM code
 * (no Android, no Room) and deterministic, so the whole game state can be recomputed from the database at any time.
 */

// ---------------------------------------------------------------- inputs

/** One activity slot (5 or 30 minutes). */
data class GameSlot(val startMs: Long, val minutes: Int, val steps: Int, val distanceM: Int? = null, val kcal: Double? = null)

/** One sleep session as the watch reported it. Asleep = deep + light. */
data class GameSleep(val startMs: Long, val endMs: Long, val deepMinutes: Int, val lightMinutes: Int, val awakeMinutes: Int = 0) {
    val asleepMinutes: Int get() = deepMinutes + lightMinutes
}

/** A workout from the watch's sport mode. */
data class GameWorkout(
    val startMs: Long,
    val endMs: Long,
    val sportName: String,
    val steps: Int? = null,
    val distanceM: Int? = null,
    val kcal: Double? = null,
    val avgBpm: Int? = null,
    val maxBpm: Int? = null,
) {
    val minutes: Int get() = ((endMs - startMs) / 60_000L).toInt().coerceAtLeast(0)
    val sport: String get() = sportName.trim().ifEmpty { "Workout" }
}

enum class MeasureKind { HEART_RATE, BLOOD_PRESSURE }

/** A measurement started by hand on the Measure screen. */
data class GameMeasure(val timeMs: Long, val kind: MeasureKind)

data class GameInput(
    val today: LocalDate,
    val zone: ZoneId,
    val stepGoal: Int,
    val sleepGoalMinutes: Int,
    val slots: List<GameSlot> = emptyList(),
    /** Times of automatic heart-rate samples. Only the count per day matters (it means "worn"). */
    val heartTimesMs: List<Long> = emptyList(),
    /** The watch's own daily step totals. */
    val dayTotals: Map<LocalDate, Int> = emptyMap(),
    val sleeps: List<GameSleep> = emptyList(),
    val workouts: List<GameWorkout> = emptyList(),
    val measures: List<GameMeasure> = emptyList(),
)

// ---------------------------------------------------------------- per-day facts

/** Everything the rules need to know about one calendar day. */
data class DaySummary(
    val date: LocalDate,
    val steps: Int = 0,
    val activeMinutes: Int = 0,
    val stepsBeforeNoon: Int = 0,
    val stepsAfter18: Int = 0,
    /** Hours of the day with at least [GameRules.MOVED_HOUR_STEPS] steps. */
    val movedHours: Int = 0,
    val heartSamples: Int = 0,
    /** Minutes asleep in sessions that ended on this day ("last night"). */
    val sleepMinutes: Int = 0,
    /**
     * Sleep start of the main night that ended on this day, in minutes after midnight of the evening before:
     * 23:00 = 1380, 00:30 = 1470. Null if no night of 3 h or more.
     */
    val bedtimeMin: Int? = null,
    val workouts: List<GameWorkout> = emptyList(),
    val bpChecks: Int = 0,
    val hrChecks: Int = 0,
) {
    val hasData: Boolean
        get() = steps > 0 || heartSamples > 0 || sleepMinutes > 0 || workouts.isNotEmpty() || bpChecks + hrChecks > 0
}

// ---------------------------------------------------------------- outputs

data class LevelInfo(
    val level: Int,
    val name: String,
    val nextName: String,
    /** XP earned inside the current level. */
    val xpInLevel: Int,
    /** XP needed to finish the current level. */
    val xpForNext: Int,
    val totalXp: Int,
) {
    val fraction: Float get() = if (xpForNext <= 0) 0f else (xpInLevel.toFloat() / xpForNext).coerceIn(0f, 1f)
}

enum class StreakKind(val emoji: String, val label: String) {
    STEPS("🔥", "Steps"),
    SLEEP("😴", "Sleep"),
    WORN("⌚", "Worn"),
}

/** [count] includes today only if [todayDone]; otherwise it is the run up to yesterday, still alive until midnight. */
data class Streak(val kind: StreakKind, val count: Int, val todayDone: Boolean, val best: Int)

data class QuestProgress(
    val id: String,
    val emoji: String,
    val title: String,
    val current: Int,
    val target: Int,
    /** "steps", "min", "h", or "" for yes/no quests. */
    val unit: String,
    val xp: Int,
    /** True for quests about tonight: they can only be checked after the next morning's sync. */
    val checksTomorrow: Boolean = false,
) {
    val done: Boolean get() = current >= target
    val fraction: Float get() = if (target <= 0) 1f else (current.toFloat() / target).coerceIn(0f, 1f)
}

data class Badge(
    val id: String,
    val emoji: String,
    val name: String,
    val description: String,
    val current: Int,
    val target: Int,
    val unit: String = "",
) {
    val unlocked: Boolean get() = current >= target
    val fraction: Float get() = if (target <= 0) 1f else (current.toFloat() / target).coerceIn(0f, 1f)
    /** Progress clamped to the target, for display. */
    val shown: Int get() = current.coerceAtMost(target)
}

data class WeekStats(
    val steps: Int = 0,
    val activeMinutes: Int = 0,
    /** Average minutes asleep over nights that have data. */
    val sleepAvgMinutes: Int? = null,
    val workouts: Int = 0,
    val daysWithData: Int = 0,
)

/** This week (Monday to today) against the same weekdays of last week. */
data class WeeklyRecap(
    val thisWeek: WeekStats,
    val lastWeek: WeekStats,
    /** Days compared (1 on Monday, 7 on Sunday). */
    val days: Int,
    /** Steps for Monday..Sunday of this week (future days 0). */
    val stepsByDay: List<Int>,
)

enum class PbKind(val label: String) {
    DISTANCE("Farthest"),
    DURATION("Longest"),
    KCAL("Most kcal"),
}

data class SportBests(
    val sport: String,
    val count: Int,
    val distance: GameWorkout?,
    val duration: GameWorkout?,
    val kcal: GameWorkout?,
)

/** A workout plus the personal bests it currently holds (only marked when the sport has 2+ sessions). */
data class WorkoutRow(val workout: GameWorkout, val pbs: Set<PbKind>)

data class XpBreakdown(
    val steps: Int = 0,
    val active: Int = 0,
    val goals: Int = 0,
    val sleep: Int = 0,
    val worn: Int = 0,
    val workouts: Int = 0,
    val measures: Int = 0,
    val quests: Int = 0,
) {
    val total: Int get() = steps + active + goals + sleep + worn + workouts + measures + quests
}

data class GameState(
    val level: LevelInfo,
    val xpToday: Int,
    val streaks: List<Streak>,
    val quests: List<QuestProgress>,
    val badges: List<Badge>,
    val recap: WeeklyRecap,
    /** Newest first. Only workouts from the first sync onwards. */
    val workouts: List<WorkoutRow>,
    val bests: List<SportBests>,
    val stepGoal: Int,
    /** First day the rules count (the day of the first synced data). */
    val since: LocalDate,
) {
    val topStreak: Streak? get() = streaks.maxByOrNull { it.count }?.takeIf { it.count > 0 }
    val unlockedCount: Int get() = badges.count { it.unlocked }
}
