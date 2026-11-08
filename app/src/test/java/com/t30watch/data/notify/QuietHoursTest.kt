package com.t30watch.data.notify

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuietHoursTest {
    private val ten = 22 * 60
    private val seven = 7 * 60

    @Test fun overnightWindow() {
        assertTrue(QuietHours.isQuiet(ten, ten, seven))
        assertTrue(QuietHours.isQuiet(1 * 60 + 40, ten, seven))
        assertTrue(QuietHours.isQuiet(6 * 60 + 59, ten, seven))
        assertFalse(QuietHours.isQuiet(seven, ten, seven))
        assertFalse(QuietHours.isQuiet(12 * 60, ten, seven))
        assertFalse(QuietHours.isQuiet(21 * 60 + 59, ten, seven))
    }

    @Test fun sameDayWindow() {
        assertTrue(QuietHours.isQuiet(13 * 60, 12 * 60, 14 * 60))
        assertFalse(QuietHours.isQuiet(14 * 60, 12 * 60, 14 * 60))
        assertFalse(QuietHours.isQuiet(11 * 60, 12 * 60, 14 * 60))
    }

    @Test fun equalStartEndIsOff() {
        assertFalse(QuietHours.isQuiet(0, 600, 600))
        assertFalse(QuietHours.isQuiet(600, 600, 600))
    }
}
