package com.t30watch.data.notify

/** Night-time window when the watch isn't buzzed. Minutes are 0..1439 from midnight. */
object QuietHours {
    /** True if [minute] falls in [start, end). Handles windows that cross midnight (22:00 → 07:00). start == end = off. */
    fun isQuiet(minute: Int, start: Int, end: Int): Boolean = when {
        start == end -> false
        start < end -> minute in start until end
        else -> minute >= start || minute < end
    }
}
