package com.t30watch.data.notify

/**
 * At most one watch buzz per app per [windowMs]. Anything else from that app inside the window is held
 * and counted; when the window ends the caller sends one "N more" summary.
 */
class BurstGate(private val windowMs: Long = 60_000) {
    sealed interface Decision {
        data object Send : Decision
        /** First held item in this window: flush after [delayMs]. */
        data class HoldAndFlushIn(val delayMs: Long) : Decision
        data object Hold : Decision
    }

    private val lastSent = HashMap<String, Long>()
    private val held = HashMap<String, Int>()

    @Synchronized
    fun offer(app: String, now: Long): Decision {
        val last = lastSent[app]
        if (last == null || now - last >= windowMs) {
            if ((held[app] ?: 0) == 0) {
                lastSent[app] = now
                return Decision.Send
            }
        }
        val n = (held[app] ?: 0) + 1
        held[app] = n
        return if (n == 1) Decision.HoldAndFlushIn(((last ?: now) + windowMs - now).coerceAtLeast(0)) else Decision.Hold
    }

    /** Ends the window: returns how many were held (0 = nothing to send). A summary counts as a send. */
    @Synchronized
    fun drain(app: String, now: Long): Int {
        val n = held.remove(app) ?: 0
        if (n > 0) lastSent[app] = now
        return n
    }
}
