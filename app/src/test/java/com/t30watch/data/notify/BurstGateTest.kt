package com.t30watch.data.notify

import com.t30watch.data.notify.BurstGate.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class BurstGateTest {
    @Test fun firstSendsRestHeldThenOneSummary() {
        val g = BurstGate(60_000)
        assertEquals(Decision.Send, g.offer("gmail", 0))
        assertEquals(Decision.HoldAndFlushIn(59_000), g.offer("gmail", 1_000))
        assertEquals(Decision.Hold, g.offer("gmail", 1_500))
        assertEquals(Decision.Hold, g.offer("gmail", 2_000))
        assertEquals(3, g.drain("gmail", 60_000))
        assertEquals(0, g.drain("gmail", 60_000))
    }

    @Test fun appsAreIndependent() {
        val g = BurstGate(60_000)
        assertEquals(Decision.Send, g.offer("gmail", 0))
        assertEquals(Decision.Send, g.offer("x", 100))
    }

    @Test fun afterWindowSendsAgain() {
        val g = BurstGate(60_000)
        assertEquals(Decision.Send, g.offer("gmail", 0))
        assertEquals(Decision.Send, g.offer("gmail", 60_000))
    }

    @Test fun summaryStartsANewWindow() {
        val g = BurstGate(60_000)
        g.offer("gmail", 0)
        g.offer("gmail", 10_000)
        assertEquals(1, g.drain("gmail", 60_000))
        // 30 s after the summary is still inside its window.
        assertEquals(Decision.HoldAndFlushIn(30_000), g.offer("gmail", 90_000))
    }

    @Test fun lateArrivalBeforeFlushStaysHeld() {
        val g = BurstGate(60_000)
        g.offer("gmail", 0)
        g.offer("gmail", 10_000)
        // Window has ended but the flush hasn't run yet: keep counting, don't send out of order.
        assertEquals(Decision.Hold, g.offer("gmail", 61_000))
        assertEquals(2, g.drain("gmail", 61_500))
    }
}
