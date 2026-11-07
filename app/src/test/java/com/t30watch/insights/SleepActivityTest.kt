package com.t30watch.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class SleepActivityTest {
    private val zone = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 11, 7)
    private fun at(day: LocalDate, h: Int, m: Int = 0): Long = day.atStartOfDay(zone).toInstant().toEpochMilli() + (h * 60 + m) * 60_000L

    /** A night that starts the evening of [night] (or after midnight if [h] < 12), asleep [asleep] minutes. */
    private fun session(night: LocalDate, h: Int, m: Int, asleep: Int, wakes: Int = 1): SleepIn {
        val start = at(if (h < 12) night.plusDays(1) else night, h, m)
        val deep = asleep / 5
        return SleepIn(start, start + asleep * 60_000L, deep, asleep - deep, 0, wakes)
    }

    @Test fun bedtimeSpreadHandlesMidnight() {
        val n = (1..4).map { today.minusDays(it.toLong()) }
        val s = listOf(
            session(n[0], 23, 40, 480),
            session(n[1], 0, 20, 480),
            session(n[2], 23, 50, 480),
            session(n[3], 0, 10, 480),
        )
        val r = analyzeSleep(s, 480, today, zone, use24Hour = true)
        assertEquals(4, r.week.size)
        assertEquals(0, r.bedtime!!.meanMinute)
        // Deviations -20, +20, -10, +10 -> RMS 15.8.
        assertEquals(16, r.bedtime.spreadMinutes)
        assertEquals(Status.GOOD, r.status)
        assertFalse(r.suggestions.any { it.contains("same 30 minutes") })
        assertTrue(r.suggestions.size in 2..4)
    }

    @Test fun irregularBedtimesGetATip() {
        val n = (1..3).map { today.minusDays(it.toLong()) }
        val s = listOf(session(n[0], 21, 30, 480), session(n[1], 1, 30, 480), session(n[2], 23, 30, 480))
        val r = analyzeSleep(s, 480, today, zone, true)
        assertTrue(r.bedtime!!.spreadMinutes > Thresholds.BEDTIME_SPREAD_WATCH_MIN)
        assertTrue(r.suggestions.any { it.contains("same 30 minutes") })
        assertEquals(Status.WATCH, r.status)
    }

    @Test fun shortSleepSuggestsABedtime() {
        val n = (1..3).map { today.minusDays(it.toLong()) }
        val s = n.map { session(it, 0, 0, 360) }
        val r = analyzeSleep(s, 480, today, zone, true)
        assertEquals(Status.WATCH, r.status)
        // Wake 06:00 - 8 h goal - 15 min to fall asleep = 21:45.
        assertTrue(r.suggestions.first(), r.suggestions.first().contains("21:45"))
    }

    @Test fun splitNightsMergeAndNapsAreDropped() {
        val night = today.minusDays(1)
        val s = listOf(
            session(night, 23, 0, 200),   // 23:00-02:20
            session(night, 2, 40, 240),   // 02:40-06:40, same night
            session(today, 14, 0, 200),   // afternoon nap
        )
        val nights = mergeNights(s, zone)
        assertEquals(1, nights.size)
        assertEquals(night, nights[0].nightOf)
        assertEquals(440, nights[0].asleepMinutes)
    }

    @Test fun fewNightsMeansNotEnough() {
        val r = analyzeSleep(listOf(session(today.minusDays(1), 23, 0, 450)), 480, today, zone, true)
        assertEquals(Status.NO_DATA, r.status)
        assertEquals(2, r.nightsMore)
        assertTrue(r.verdict.contains("2 nights"))
    }

    @Test fun oldJunkIsIgnored() {
        val r = analyzeSleep(listOf(session(LocalDate.of(2026, 9, 10), 23, 0, 450)), 480, today, zone, true)
        assertTrue(r.week.isEmpty())
        assertEquals(Status.NO_DATA, r.status)
    }

    @Test fun stepsAverageSkipsTodayAndUnwornDays() {
        val daily = mapOf(
            today to 2_000,              // partial day, left out of the average
            today.minusDays(1) to 9_000,
            today.minusDays(2) to 7_000,
            today.minusDays(3) to 50,    // not worn
            today.minusDays(4) to 8_000,
        )
        val r = analyzeActivity(daily, emptyList(), 8_000, today, zone)
        assertEquals(8_000, r.avgSteps7)
        assertEquals(3, r.daysCounted)
        assertEquals(2, r.daysAtGoal)
        assertEquals(Status.GOOD, r.status)
    }

    @Test fun stepsTrendInPercent() {
        val daily = (1L..14L).associate { today.minusDays(it) to if (it <= 7) 11_000 else 10_000 }
        val r = analyzeActivity(daily, emptyList(), 8_000, today, zone)
        assertNotNull(r.trend)
        assertEquals(10.0, r.trend!!.pct, 1e-9)
        assertEquals("Up 10% vs last week.", r.trendText)
    }

    @Test fun briskMinutesCountFastSlots() {
        val slots = listOf(
            StepSlot(at(today, 9), 600),
            StepSlot(at(today, 9, 5), 550),
            StepSlot(at(today, 9, 10), 200),
            StepSlot(at(today.minusDays(10), 9), 600), // outside the week
        )
        val r = analyzeActivity(emptyMap(), slots, 8_000, today, zone)
        assertEquals(10, r.briskMinutes7)
    }
}
