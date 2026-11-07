package com.t30watch.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class HeartInsightsTest {
    private val zone = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 11, 7)
    private val slot = Thresholds.SLOT_MS
    private fun at(day: LocalDate, h: Int, m: Int = 0): Long = day.atStartOfDay(zone).toInstant().toEpochMilli() + (h * 60 + m) * 60_000L

    @Test fun restingUsesOnlyQuietReadings() {
        val hr = mutableListOf<HrPoint>()
        val slots = mutableListOf<StepSlot>()
        // 14:00-14:55 sitting still: 60..71 bpm, 0 steps.
        for (i in 0 until 12) {
            hr += HrPoint(at(today, 14) + i * slot, 60 + i)
            slots += StepSlot(at(today, 14) + i * slot, 0)
        }
        // 16:00-16:55 walking: 400 steps per slot, 110 bpm. Not resting.
        for (i in 0 until 12) {
            hr += HrPoint(at(today, 16) + i * slot, 110)
            slots += StepSlot(at(today, 16) + i * slot, 400)
        }
        // 17:00: 0 steps, but right after the walk. Not resting.
        hr += HrPoint(at(today, 17), 50)
        slots += StepSlot(at(today, 17), 0)
        // 18:00-18:25 workout with no steps (e.g. cycling). Not resting.
        val workout = TimeSpan(at(today, 18), at(today, 18, 30))
        for (i in 0 until 6) {
            hr += HrPoint(at(today, 18) + i * slot, 45)
            slots += StepSlot(at(today, 18) + i * slot, 0)
        }

        val r = restingByDay(hr, slots, emptyList(), listOf(workout), zone)
        // Lower quartile of 60..71: index 2.75 -> 62.75 -> 63.
        assertEquals(mapOf(today to 63), r)
    }

    @Test fun sleepReadingsCountWithoutSlots() {
        val night = TimeSpan(at(today, 1), at(today, 7))
        val hr = (0 until 8).map { HrPoint(at(today, 2) + it * slot, 55) }
        assertEquals(mapOf(today to 55), restingByDay(hr, emptyList(), listOf(night), emptyList(), zone))
        // Same readings with no sleep and no step data: we can't tell, so nothing.
        assertTrue(restingByDay(hr, emptyList(), emptyList(), emptyList(), zone).isEmpty())
    }

    @Test fun dayNeedsEnoughQuietReadings() {
        val n = Thresholds.MIN_RESTING_SAMPLES_PER_DAY - 1
        val hr = (0 until n).map { HrPoint(at(today, 2) + it * slot, 55) }
        val slots = (0 until n).map { StepSlot(at(today, 2) + it * slot, 0) }
        assertTrue(restingByDay(hr, slots, emptyList(), emptyList(), zone).isEmpty())
    }

    /** 8 sleeping readings per day at [bpm]. */
    private fun nights(days: IntRange, bpm: (Int) -> Int): Pair<List<HrPoint>, List<TimeSpan>> {
        val hr = mutableListOf<HrPoint>()
        val sleep = mutableListOf<TimeSpan>()
        for (back in days) {
            val d = today.minusDays(back.toLong())
            sleep += TimeSpan(at(d, 1), at(d, 6))
            for (i in 0 until 8) hr += HrPoint(at(d, 2) + i * slot, bpm(back))
        }
        return hr to sleep
    }

    @Test fun trendDownIsGood() {
        val (hr, sleep) = nights(0..13) { back -> if (back <= 6) 62 else 66 }
        val r = analyzeHeart(hr, emptyList(), sleep, emptyList(), age = 40, today = today, zone = zone)
        assertEquals(-4.0, r.trend!!.delta, 1e-9)
        assertTrue(r.trendText!!.startsWith("Down 4 bpm"))
        assertEquals(62, r.restingAvg7)
        assertEquals(Status.GOOD, r.status)
        assertEquals(0, r.daysUntilTrend)
    }

    @Test fun bigRiseIsWatch() {
        val (hr, sleep) = nights(0..13) { back -> if (back <= 6) 72 else 65 }
        val r = analyzeHeart(hr, emptyList(), sleep, emptyList(), 40, today, zone)
        assertEquals(Status.WATCH, r.status)
        assertTrue(r.trendText!!.startsWith("Up 7 bpm"))
    }

    @Test fun consistentlyHighRestingSaysDoctor() {
        val (hr, sleep) = nights(0..2) { 105 }
        val r = analyzeHeart(hr, emptyList(), sleep, emptyList(), 40, today, zone)
        assertEquals(Status.DOCTOR, r.status)
        assertNull(r.trend)
    }

    @Test fun oneHighDayIsOnlyWatch() {
        val (hr, sleep) = nights(0..0) { 105 }
        val r = analyzeHeart(hr, emptyList(), sleep, emptyList(), 40, today, zone)
        assertEquals(Status.WATCH, r.status)
    }

    @Test fun noDataState() {
        val r = analyzeHeart(emptyList(), emptyList(), emptyList(), emptyList(), 40, today, zone)
        assertEquals(Status.NO_DATA, r.status)
    }

    @Test fun zonesFromAge() {
        assertEquals(180, maxHeartRate(40))
        val z = heartZones(180)
        assertEquals(90, z[0].lowBpm)
        assertEquals(107, z[0].highBpm)
        assertEquals(162, z[4].lowBpm)
        assertEquals(180, z[4].highBpm)
        assertNull(zoneIndex(89, z))
        assertEquals(0, zoneIndex(90, z))
        assertEquals(1, zoneIndex(108, z))
        assertEquals(4, zoneIndex(200, z))
    }

    @Test fun contextFromSteps() {
        assertEquals(ActivityLevel.RESTING, heartContext(72, 0).level)
        assertEquals(Status.GOOD, heartContext(72, 0).status)
        assertEquals(Status.WATCH, heartContext(110, 0).status)
        assertEquals(ActivityLevel.LIGHT, heartContext(90, 120).level)
        assertEquals(ActivityLevel.ACTIVE, heartContext(110, 350).level)
        assertEquals(ActivityLevel.BRISK, heartContext(130, 600).level)
        assertEquals(Status.GOOD, heartContext(130, 600).status)
        assertEquals(ActivityLevel.UNKNOWN, heartContext(70, null).level)
    }
}
