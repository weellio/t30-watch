package com.t30watch.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class GameRulesTest {
    private val zone = ZoneOffset.UTC
    private val today: LocalDate = LocalDate.of(2026, 11, 7)

    private fun ms(day: LocalDate, h: Int, m: Int = 0): Long = day.atTime(h, m).toInstant(ZoneOffset.UTC).toEpochMilli()
    private fun ago(n: Long): LocalDate = today.minusDays(n)

    /** One 5-minute slot at 10:00 with [steps] steps. */
    private fun slot(day: LocalDate, steps: Int, h: Int = 10, m: Int = 0, minutes: Int = 5) = GameSlot(ms(day, h, m), minutes, steps)

    private fun input(
        slots: List<GameSlot> = emptyList(),
        heart: List<Long> = emptyList(),
        sleeps: List<GameSleep> = emptyList(),
        workouts: List<GameWorkout> = emptyList(),
        measures: List<GameMeasure> = emptyList(),
        stepGoal: Int = 8_000,
        sleepGoal: Int = 420,
    ) = GameInput(today, zone, stepGoal, sleepGoal, slots, heart, emptyMap(), sleeps, workouts, measures)

    private fun stepStreak(s: GameState) = s.streaks.first { it.kind == StreakKind.STEPS }

    // ---------------------------------------------------------------- streaks

    @Test
    fun streakCountsTodayWhenAlreadyMet() {
        val s = GameEngine.compute(input(slots = listOf(slot(ago(2), 9_000), slot(ago(1), 9_000), slot(today, 9_000))))
        val st = stepStreak(s)
        assertEquals(3, st.count)
        assertTrue(st.todayDone)
    }

    @Test
    fun streakStaysAliveUntilMidnightWhenTodayNotMetYet() {
        val s = GameEngine.compute(input(slots = listOf(slot(ago(2), 9_000), slot(ago(1), 9_000), slot(today, 1_000))))
        val st = stepStreak(s)
        assertEquals(2, st.count)
        assertFalse(st.todayDone)
    }

    @Test
    fun gapBreaksTheStreakButBestRemembersTheRun() {
        val slots = listOf(
            slot(ago(6), 9_000), slot(ago(5), 9_000), slot(ago(4), 9_000), // run of 3
            slot(ago(3), 500),                                             // gap
            slot(ago(2), 9_000), slot(ago(1), 9_000),                      // run of 2 up to yesterday
            slot(today, 100),
        )
        val st = stepStreak(GameEngine.compute(input(slots = slots)))
        assertEquals(2, st.count)
        assertEquals(3, st.best)
    }

    @Test
    fun missedYesterdayMeansZeroEvenIfOlderDaysMet() {
        val slots = listOf(slot(ago(3), 9_000), slot(ago(2), 9_000), slot(ago(1), 10), slot(today, 10))
        assertEquals(0, stepStreak(GameEngine.compute(input(slots = slots))).count)
    }

    @Test
    fun streakNeverCountsDaysBeforeEpoch() {
        val st = GameRules.streak(StreakKind.WORN, today, today.minusDays(1)) { true }
        assertEquals(2, st.count)
        assertEquals(2, st.best)
    }

    @Test
    fun noDataMeansEpochIsTodayAndNoStreaks() {
        val s = GameEngine.compute(input())
        assertEquals(today, s.since)
        assertTrue(s.streaks.all { it.count == 0 })
        assertTrue(s.badges.none { it.unlocked })
    }

    @Test
    fun wornStreakNeedsEnoughHeartSamples() {
        val day = ago(1)
        val enough = (0 until GameRules.WORN_MIN_SAMPLES).map { ms(day, 8) + it * 300_000L }
        val tooFew = (0 until GameRules.WORN_MIN_SAMPLES - 1).map { ms(today, 8) + it * 300_000L }
        val s = GameEngine.compute(input(slots = listOf(slot(ago(1), 100)), heart = enough + tooFew))
        val worn = s.streaks.first { it.kind == StreakKind.WORN }
        assertEquals(1, worn.count)
        assertFalse(worn.todayDone)
    }

    // ---------------------------------------------------------------- ancient data

    @Test
    fun ancientWorkoutAndSleepAreIgnored() {
        val oldWorkout = GameWorkout(
            startMs = ms(LocalDate.of(2024, 4, 30), 9), endMs = ms(LocalDate.of(2024, 4, 30), 11),
            sportName = "Run", distanceM = 20_000, kcal = 900.0,
        )
        val oldSleep = GameSleep(ms(LocalDate.of(2026, 9, 14), 22), ms(LocalDate.of(2026, 9, 15), 7), 200, 300)
        val s = GameEngine.compute(input(slots = listOf(slot(ago(4), 500), slot(today, 500)), sleeps = listOf(oldSleep), workouts = listOf(oldWorkout)))

        assertEquals(ago(4), s.since)
        assertTrue(s.workouts.isEmpty())
        assertTrue(s.bests.isEmpty())
        assertFalse(s.badges.first { it.id == "first_workout" }.unlocked)
        assertFalse(s.badges.first { it.id == "full_night" }.unlocked)
        assertEquals(0, s.streaks.first { it.kind == StreakKind.SLEEP }.count)
    }

    @Test
    fun epochIsTheFirstSlotDay() {
        val inp = input(slots = listOf(slot(ago(3), 100), slot(ago(1), 100)))
        assertEquals(ago(3), GameRules.epochDay(inp))
        assertEquals(4, GameRules.summarize(inp).size)
    }

    // ---------------------------------------------------------------- day facts

    @Test
    fun activeMinutesNeedSixtyStepsPerMinute() {
        val d = GameRules.summarize(input(slots = listOf(slot(today, 300, h = 9), slot(today, 299, h = 10))))[today]!!
        assertEquals(5, d.activeMinutes)
        assertEquals(599, d.steps)
        assertEquals(599, d.stepsBeforeNoon)
    }

    @Test
    fun sleepCountsForTheWakeDayAndBedtimeWrapsPastMidnight() {
        val night = GameSleep(ms(ago(1), 23, 30), ms(today, 7), 120, 300)
        val late = GameSleep(ms(ago(1), 0, 30), ms(ago(1), 8), 150, 250)
        val days = GameRules.summarize(input(slots = listOf(slot(ago(2), 10)), sleeps = listOf(night, late)))
        assertEquals(420, days[today]!!.sleepMinutes)
        assertEquals(23 * 60 + 30, days[today]!!.bedtimeMin)
        assertEquals(24 * 60 + 30, days[ago(1)]!!.bedtimeMin)
    }

    @Test
    fun shortNapIsNotANight() {
        val nap = GameSleep(ms(today, 14), ms(today, 15), 20, 40)
        val d = GameRules.summarize(input(slots = listOf(slot(today, 10)), sleeps = listOf(nap)))[today]!!
        assertEquals(60, d.sleepMinutes)
        assertNull(d.bedtimeMin)
    }

    // ---------------------------------------------------------------- levels

    @Test
    fun levelCurveIsQuickEarlyAndSlowerLater() {
        assertEquals(100, Levels.cost(1))
        assertEquals(280, Levels.cost(2))
        assertEquals(520, Levels.cost(3))
        for (l in 1..40) assertTrue("cost must grow at $l", Levels.cost(l + 1) > Levels.cost(l))
        // Each step costs more extra than the one before (convex) at the start.
        assertTrue(Levels.cost(3) - Levels.cost(2) > Levels.cost(2) - Levels.cost(1))
    }

    @Test
    fun levelForXpBoundaries() {
        assertEquals(1, Levels.forXp(0).level)
        Levels.forXp(99).let { assertEquals(1, it.level); assertEquals(99, it.xpInLevel); assertEquals(100, it.xpForNext) }
        Levels.forXp(100).let { assertEquals(2, it.level); assertEquals(0, it.xpInLevel) }
        assertEquals(3, Levels.forXp(380).level)
        assertEquals(2, Levels.forXp(379).level)
        assertEquals(10, Levels.forXp(Levels.totalFor(10)).level)
        assertEquals(9, Levels.forXp(Levels.totalFor(10) - 1).level)
        assertEquals(1, Levels.forXp(-50).level)
    }

    @Test
    fun levelNames() {
        assertEquals("Fresh Start", Levels.name(1))
        assertEquals("Legend", Levels.name(20))
        assertEquals("Legend 2", Levels.name(21))
        for (l in 1..60) assertTrue(Levels.name(l).isNotBlank())
    }

    // ---------------------------------------------------------------- quests

    @Test
    fun questsAreDeterministicPerDate() {
        var d = LocalDate.of(2026, 1, 1)
        val seen = HashSet<List<String>>()
        repeat(120) {
            val a = Quests.idsFor(d)
            assertEquals(a, Quests.idsFor(d))
            assertEquals(Quests.PER_DAY, a.size)
            assertEquals(Quests.PER_DAY, a.toSet().size)
            assertEquals("one quest per group", Quests.PER_DAY, a.map { Quests.def(it)!!.group }.toSet().size)
            seen += a
            d = d.plusDays(1)
        }
        assertTrue("quests should vary from day to day", seen.size > 10)
    }

    @Test
    fun bedtimeQuestChecksTheNightThatStartsThatDay() {
        val q = Quests.pool.first { it.id == "bed_2330" }
        val early = DaySummary(today, bedtimeMin = 23 * 60)
        val late = DaySummary(today, bedtimeMin = 24 * 60 + 15)
        assertEquals(1, q.value(QuestContext(DaySummary(ago(1)), null, early, 8_000)))
        assertEquals(0, q.value(QuestContext(DaySummary(ago(1)), null, late, 8_000)))
        assertEquals(0, q.value(QuestContext(DaySummary(today), null, null, 8_000)))
    }

    @Test
    fun allQuestsGiveTheBonus() {
        val done = QuestProgress("a", "", "", 1, 1, "", Quests.XP_EACH)
        val notDone = done.copy(current = 0)
        assertEquals(3 * Quests.XP_EACH + Quests.XP_ALL_BONUS, Quests.xpFor(listOf(done, done, done)))
        assertEquals(2 * Quests.XP_EACH, Quests.xpFor(listOf(done, done, notDone)))
    }

    // ---------------------------------------------------------------- XP

    @Test
    fun dayXpRules() {
        val s = DaySummary(today, steps = 10_000, activeMinutes = 30, heartSamples = 100, sleepMinutes = 480, bpChecks = 5)
        val xp = Xp.forDay(s, emptyList(), stepGoal = 8_000, sleepGoalMinutes = 420)
        assertEquals(100, xp.steps)
        assertEquals(60, xp.active)
        assertEquals(Xp.STEP_GOAL, xp.goals)
        assertEquals(Xp.SLEEP_GOAL + Xp.NIGHT_LOGGED, xp.sleep)
        assertEquals(Xp.WORN_DAY, xp.worn)
        assertEquals(Xp.MEASURE_DAILY_MAX * Xp.MEASURE, xp.measures)
        val capped = Xp.forDay(DaySummary(today, steps = 90_000, activeMinutes = 500), emptyList(), 8_000, 420)
        assertEquals(Xp.STEPS_CAP, capped.steps)
        assertEquals(Xp.ACTIVE_CAP, capped.active)
    }

    @Test
    fun workoutXpCapsMinutes() {
        val w = GameWorkout(ms(today, 8), ms(today, 12), "Walk")
        val xp = Xp.forDay(DaySummary(today, workouts = listOf(w)), emptyList(), 8_000, 420)
        assertEquals(Xp.WORKOUT + Xp.WORKOUT_MIN_CAP, xp.workouts)
    }

    // ---------------------------------------------------------------- workouts + PBs

    @Test
    fun personalBestsPerSport() {
        val runA = GameWorkout(ms(ago(3), 7), ms(ago(3), 7, 30), "Run", distanceM = 5_000, kcal = 300.0)
        val runB = GameWorkout(ms(ago(1), 7), ms(ago(1), 7, 45), "Run", distanceM = 3_000, kcal = 300.0)
        val walk = GameWorkout(ms(ago(2), 18), ms(ago(2), 19), "Walk", distanceM = 4_000, kcal = 200.0)
        val s = GameEngine.compute(input(slots = listOf(slot(ago(3), 100)), workouts = listOf(runA, runB, walk)))

        val run = s.bests.first { it.sport == "Run" }
        assertEquals(2, run.count)
        assertEquals(runA, run.distance)
        assertEquals(runB, run.duration)
        assertEquals("tie goes to the earlier session", runA, run.kcal)

        val rows = s.workouts
        assertEquals(listOf(runB, walk, runA), rows.map { it.workout })
        assertEquals(setOf(PbKind.DISTANCE, PbKind.KCAL), rows.first { it.workout == runA }.pbs)
        assertEquals(setOf(PbKind.DURATION), rows.first { it.workout == runB }.pbs)
        assertTrue("a single session has nothing to beat", rows.first { it.workout == walk }.pbs.isEmpty())
    }

    @Test
    fun zeroValuesNeverHoldARecord() {
        val a = GameWorkout(ms(today, 7), ms(today, 7, 20), "Yoga")
        val b = GameWorkout(ms(today, 9), ms(today, 9, 10), "Yoga")
        val best = GameEngine.personalBests(listOf(a, b))["Yoga"]!!
        assertNull(best.distance)
        assertNull(best.kcal)
        assertEquals(a, best.duration)
    }

    // ---------------------------------------------------------------- badges + recap

    @Test
    fun steadyBedtimeNeedsConsecutiveCloseNights() {
        val base = 23 * 60
        val days = listOf(
            DaySummary(ago(6), bedtimeMin = base),
            DaySummary(ago(5), bedtimeMin = base + 20),
            DaySummary(ago(4), bedtimeMin = base + 45),  // within 30 of the night before (+25)
            DaySummary(ago(3), bedtimeMin = base + 120), // jump: run restarts
            DaySummary(ago(1), bedtimeMin = base + 120), // not consecutive with ago(3)
        )
        assertEquals(3, Badges.steadyBedtimeRun(days))
    }

    @Test
    fun badgeIdsAreUniqueAndCountInRange() {
        val badges = GameEngine.compute(input()).badges
        assertEquals(badges.size, badges.map { it.id }.toSet().size)
        assertTrue(badges.size in 15..25)
    }

    @Test
    fun firstDayBadgesUnlock() {
        val w = GameWorkout(ms(today, 7), ms(today, 7, 30), "Walk")
        val s = GameEngine.compute(input(slots = listOf(slot(today, 10_500)), workouts = listOf(w)))
        val unlocked = s.badges.filter { it.unlocked }.map { it.id }.toSet()
        assertTrue("first_sync" in unlocked)
        assertTrue("first_goal" in unlocked)
        assertTrue("ten_k" in unlocked)
        assertTrue("first_workout" in unlocked)
        assertFalse("on_fire" in unlocked)
    }

    @Test
    fun weeklyRecapComparesSameDays() {
        val n = today.dayOfWeek.value
        val monday = today.minusDays((n - 1).toLong())
        val slots = listOf(slot(monday, 4_000), slot(monday.minusWeeks(1), 2_000), slot(monday.minusDays(1), 99_999))
        val s = GameEngine.compute(input(slots = slots))
        assertEquals(n, s.recap.days)
        assertEquals(4_000, s.recap.thisWeek.steps)
        assertEquals("last Sunday is outside the compared days", 2_000, s.recap.lastWeek.steps)
        assertEquals(7, s.recap.stepsByDay.size)
        assertEquals(4_000, s.recap.stepsByDay[0])
        assertNotNull(s.recap)
    }
}
