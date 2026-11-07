package com.t30watch.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class StatsTest {
    private val end = LocalDate.of(2026, 11, 7)

    @Test fun percentileInterpolates() {
        assertEquals(1.75, percentile(listOf(1, 2, 3, 4), 0.25)!!, 1e-9)
        assertEquals(5.0, percentile(listOf(5), 0.25)!!, 1e-9)
        assertNull(percentile(emptyList(), 0.5))
    }

    @Test fun clockSpreadAcrossMidnight() {
        // 23:45 and 00:15: average midnight, spread 15 minutes (a naive average would say noon).
        val s = clockSpread(listOf(23 * 60 + 45, 15))!!
        assertEquals(0, s.meanMinute)
        assertEquals(15, s.spreadMinutes)
    }

    @Test fun clockSpreadLateBedtimes() {
        // 22:30, 23:30, 00:30 -> mean 23:30, RMS spread sqrt((60^2+0+60^2)/3) = 49.
        val s = clockSpread(listOf(22 * 60 + 30, 23 * 60 + 30, 30))!!
        assertEquals(23 * 60 + 30, s.meanMinute)
        assertEquals(49, s.spreadMinutes)
    }

    @Test fun clockSpreadSameTime() {
        val s = clockSpread(listOf(600, 600, 600))!!
        assertEquals(600, s.meanMinute)
        assertEquals(0, s.spreadMinutes)
    }

    @Test fun clockDiffWraps() {
        assertEquals(-20.0, clockDiff(23 * 60 + 50.0, 10.0), 1e-9)
        assertEquals(20.0, clockDiff(10.0, 23 * 60 + 50.0), 1e-9)
        assertEquals(-440.0, clockDiff(1000.0, 0.0), 1e-9)
    }

    @Test fun clockTextFormats() {
        assertEquals("23:05", clockText(23 * 60 + 5, true))
        assertEquals("11:05 PM", clockText(23 * 60 + 5, false))
        assertEquals("12:00 AM", clockText(0, false))
        assertEquals("12:30 PM", clockText(12 * 60 + 30, false))
    }

    @Test fun weekOverWeekAverages() {
        val daily = mutableMapOf<LocalDate, Double>()
        (0L..6L).forEach { daily[end.minusDays(it)] = 60.0 }
        (7L..13L).forEach { daily[end.minusDays(it)] = 64.0 }
        val t = weekOverWeek(daily, end)!!
        assertEquals(60.0, t.current, 1e-9)
        assertEquals(64.0, t.previous, 1e-9)
        assertEquals(-4.0, t.delta, 1e-9)
        assertEquals(-6.25, t.pct, 1e-9)
    }

    @Test fun weekOverWeekNeedsDaysInBothWeeks() {
        val daily = (0L..6L).associate { end.minusDays(it) to 70.0 } + mapOf(end.minusDays(8) to 72.0, end.minusDays(9) to 71.0)
        assertNull(weekOverWeek(daily, end))
        assertNotNull(weekOverWeek(daily + (end.minusDays(10) to 73.0), end))
    }

    @Test fun daysUntilTrendCountsForward() {
        // Only today worn: the previous week needs 3 days, so 7 + 3 - 1 = 9 more.
        assertEquals(9, daysUntilTrend(setOf(end), end))
        // Ten days in a row already: trend works now.
        assertEquals(0, daysUntilTrend((0L..9L).map { end.minusDays(it) }.toSet(), end))
        // Eight days: 2 more.
        assertEquals(2, daysUntilTrend((0L..7L).map { end.minusDays(it) }.toSet(), end))
    }
}
