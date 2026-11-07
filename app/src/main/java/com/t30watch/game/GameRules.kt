package com.t30watch.game

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Thresholds and the day-by-day facts every other rule is built on. */
object GameRules {
    /** A slot counts as active minutes when it averages at least this many steps per minute (300 per 5 min). */
    const val ACTIVE_STEPS_PER_MIN = 60
    /** An hour counts as "moved in" with at least this many steps. */
    const val MOVED_HOUR_STEPS = 250
    /** Heart samples (one per 5 min while worn) needed for a "worn" day: about 6 hours. */
    const val WORN_MIN_SAMPLES = 72
    /** Heart samples per hour of wear, for the "wear it N hours" quest. */
    const val SAMPLES_PER_HOUR = 12
    /** A sleep session this long or longer is a "night" (for bedtime rules). */
    const val NIGHT_MIN_MINUTES = 180
    /** Sessions longer than this are treated as bad data. */
    private const val MAX_SESSION_MS = 20 * 3_600_000L

    fun dateOf(ms: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    /**
     * First day the game counts: the day of the earliest activity slot, watch day total or manual measurement.
     * The watch keeps about 5 days of slots, so this is roughly "first sync minus a few days". Older leftovers
     * (a 2024 workout, a months-old sleep) fall before it and are ignored. No data yet → today.
     */
    fun epochDay(input: GameInput): LocalDate {
        val candidates = listOfNotNull(
            input.slots.minOfOrNull { it.startMs }?.let { dateOf(it, input.zone) },
            input.measures.minOfOrNull { it.timeMs }?.let { dateOf(it, input.zone) },
            input.dayTotals.keys.minOrNull(),
        )
        val first = candidates.minOrNull() ?: input.today
        return if (first.isAfter(input.today)) input.today else first
    }

    /** Minutes after midnight of the evening before: 23:00 → 1380, 00:30 → 1470. Starts before 15:00 count as after midnight. */
    fun bedtimeMinutes(startMs: Long, zone: ZoneId): Int {
        val t = Instant.ofEpochMilli(startMs).atZone(zone)
        val m = t.hour * 60 + t.minute
        return if (t.hour >= 15) m else m + 1440
    }

    /** One summary per day from [epoch] to [GameInput.today] inclusive. */
    fun summarize(input: GameInput, epoch: LocalDate = epochDay(input)): Map<LocalDate, DaySummary> {
        val zone = input.zone
        val today = input.today
        fun inRange(d: LocalDate) = !d.isBefore(epoch) && !d.isAfter(today)

        val slotsByDay = input.slots.groupBy { dateOf(it.startMs, zone) }.filterKeys(::inRange)
        val heartByDay = input.heartTimesMs.groupingBy { dateOf(it, zone) }.eachCount()
        val sleepByDay = input.sleeps
            .filter { it.endMs > it.startMs && it.endMs - it.startMs <= MAX_SESSION_MS }
            .groupBy { dateOf(it.endMs, zone) }
        val workoutsByDay = input.workouts.filter { it.endMs >= it.startMs }.groupBy { dateOf(it.startMs, zone) }
        val measuresByDay = input.measures.groupBy { dateOf(it.timeMs, zone) }

        val out = LinkedHashMap<LocalDate, DaySummary>()
        var d = epoch
        while (!d.isAfter(today)) {
            val slots = slotsByDay[d].orEmpty()
            val hourly = IntArray(24)
            slots.forEach { s -> hourly[Instant.ofEpochMilli(s.startMs).atZone(zone).hour] += s.steps }
            val slotSteps = slots.sumOf { it.steps }
            val sleeps = sleepByDay[d].orEmpty()
            val mainNight = sleeps.filter { it.asleepMinutes >= NIGHT_MIN_MINUTES }.maxByOrNull { it.asleepMinutes }
            val measures = measuresByDay[d].orEmpty()
            out[d] = DaySummary(
                date = d,
                steps = maxOf(input.dayTotals[d] ?: 0, slotSteps),
                activeMinutes = slots.filter { it.minutes > 0 && it.steps >= ACTIVE_STEPS_PER_MIN * it.minutes }.sumOf { it.minutes },
                stepsBeforeNoon = (0 until 12).sumOf { hourly[it] },
                stepsAfter18 = (18 until 24).sumOf { hourly[it] },
                movedHours = hourly.count { it >= MOVED_HOUR_STEPS },
                heartSamples = heartByDay[d] ?: 0,
                sleepMinutes = sleeps.sumOf { it.asleepMinutes },
                bedtimeMin = mainNight?.let { bedtimeMinutes(it.startMs, zone) },
                workouts = workoutsByDay[d].orEmpty().sortedBy { it.startMs },
                bpChecks = measures.count { it.kind == MeasureKind.BLOOD_PRESSURE },
                hrChecks = measures.count { it.kind == MeasureKind.HEART_RATE },
            )
            d = d.plusDays(1)
        }
        return out
    }

    // ---------------------------------------------------------------- goals

    fun stepGoalMet(s: DaySummary?, stepGoal: Int): Boolean = s != null && s.steps >= stepGoal.coerceAtLeast(1)
    fun sleepGoalMet(s: DaySummary?, sleepGoalMinutes: Int): Boolean = s != null && s.sleepMinutes >= sleepGoalMinutes.coerceAtLeast(1)
    fun worn(s: DaySummary?): Boolean = s != null && s.heartSamples >= WORN_MIN_SAMPLES

    // ---------------------------------------------------------------- streaks

    /**
     * Current streak, counted back from today. If today already meets the rule it counts; if not, the run up to
     * yesterday is still alive (it only breaks at midnight). Days before [epoch] never count.
     */
    fun streak(kind: StreakKind, today: LocalDate, epoch: LocalDate, met: (LocalDate) -> Boolean): Streak {
        val todayDone = !today.isBefore(epoch) && met(today)
        var d = if (todayDone) today else today.minusDays(1)
        var n = 0
        while (!d.isBefore(epoch) && met(d)) {
            n++
            d = d.minusDays(1)
        }
        return Streak(kind, n, todayDone, maxOf(n, bestRun(epoch, today, met)))
    }

    /** Longest run of consecutive days from [from] to [to] where [met] holds. */
    fun bestRun(from: LocalDate, to: LocalDate, met: (LocalDate) -> Boolean): Int {
        var best = 0
        var run = 0
        var d = from
        while (!d.isAfter(to)) {
            if (met(d)) {
                run++
                if (run > best) best = run
            } else {
                run = 0
            }
            d = d.plusDays(1)
        }
        return best
    }
}
