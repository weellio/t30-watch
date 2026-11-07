package com.t30watch.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class BpCategoryTest {

    @Test fun normal() {
        assertEquals(BpCategory.NORMAL, bpCategory(119, 79))
        assertEquals(BpCategory.NORMAL, bpCategory(90, 60))
    }

    @Test fun elevatedEdges() {
        assertEquals(BpCategory.ELEVATED, bpCategory(120, 79))
        assertEquals(BpCategory.ELEVATED, bpCategory(129, 79))
        // Bottom number 80 moves it to stage 1 even with a normal top number.
        assertEquals(BpCategory.STAGE_1, bpCategory(125, 80))
    }

    @Test fun stage1Edges() {
        assertEquals(BpCategory.STAGE_1, bpCategory(130, 70))
        assertEquals(BpCategory.STAGE_1, bpCategory(139, 89))
        assertEquals(BpCategory.STAGE_1, bpCategory(110, 80))
        assertEquals(BpCategory.STAGE_1, bpCategory(110, 89))
    }

    @Test fun stage2Edges() {
        assertEquals(BpCategory.STAGE_2, bpCategory(140, 70))
        assertEquals(BpCategory.STAGE_2, bpCategory(115, 90))
        assertEquals(BpCategory.STAGE_2, bpCategory(180, 120))
    }

    @Test fun severeEdges() {
        assertEquals(BpCategory.SEVERE, bpCategory(181, 100))
        assertEquals(BpCategory.SEVERE, bpCategory(150, 121))
        assertEquals(BpCategory.SEVERE, bpCategory(200, 130))
        assertEquals(Status.DOCTOR, BpCategory.SEVERE.status)
    }

    @Test fun onlySevereMeansDoctor() {
        BpCategory.entries.filter { it != BpCategory.SEVERE }.forEach { assertTrue(it.status != Status.DOCTOR) }
    }

    @Test fun movingReadingsAreLeftOutOfAverage() {
        val zone = ZoneOffset.UTC
        val today = LocalDate.of(2026, 11, 7)
        val base = today.atStartOfDay(zone).toInstant().toEpochMilli() + 9 * 3_600_000L
        val slot = Thresholds.SLOT_MS
        val bp = listOf(
            BpPoint(base, 118, 76),
            BpPoint(base + slot * 12, 120, 78),
            BpPoint(base + slot * 24, 116, 74),
            BpPoint(base + slot * 36, 150, 95), // taken while walking
        )
        val slots = listOf(StepSlot(base + slot * 36, 600))
        val r = analyzeBp(bp, slots, today, zone)
        assertEquals(4, r.readings7)
        assertEquals(1, r.moving7)
        assertEquals(118, r.avgSys7)
        assertEquals(76, r.avgDia7)
        assertEquals(BpCategory.NORMAL, r.avgCategory)
        assertTrue(r.latestMoving)
        assertEquals(Status.GOOD, r.status)
    }

    @Test fun severeLatestAsksForRetest() {
        val zone = ZoneOffset.UTC
        val today = LocalDate.of(2026, 11, 7)
        val t = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val r = analyzeBp(listOf(BpPoint(t, 185, 100)), emptyList(), today, zone)
        assertEquals(Status.DOCTOR, r.status)
        assertFalse(r.latestMoving)
    }

    @Test fun noReadings() {
        val r = analyzeBp(emptyList(), emptyList(), LocalDate.of(2026, 11, 7), ZoneOffset.UTC)
        assertEquals(Status.NO_DATA, r.status)
    }
}
