package com.t30watch.ui.common

import java.time.format.DateTimeFormatter
import java.util.Locale

/** Date formats in the phone's own style ("Nov 2" in the US, "2 Nov" in the UK). */
object Dates {
    private fun best(skeleton: String): DateTimeFormatter =
        DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton), Locale.getDefault())

    val monthDay: DateTimeFormatter by lazy { best("MMMd") }
    val weekdayMonthDay: DateTimeFormatter by lazy { best("EEEMMMd") }
    val weekdayMonthDayYear: DateTimeFormatter by lazy { best("EEEMMMdyyyy") }
    val monthYear: DateTimeFormatter by lazy { best("MMMMyyyy") }
}
