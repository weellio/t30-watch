package com.t30watch.util

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.TemporalAdjusters

/** Small date helpers. Everything uses the phone's current time zone. */
object Time {
    val zone: ZoneId get() = ZoneId.systemDefault()
    val dayKey: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    fun startOf(day: LocalDate): Long = day.atStartOfDay(zone).toInstant().toEpochMilli()
    fun endOf(day: LocalDate): Long = startOf(day.plusDays(1))
    fun dateOf(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    fun key(day: LocalDate): String = dayKey.format(day)
    fun today(): LocalDate = LocalDate.now(zone)

    fun weekStart(day: LocalDate): LocalDate = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    fun monthStart(day: LocalDate): LocalDate = day.withDayOfMonth(1)

    /** Clock time in the phone's locale style ("1:05 PM" in the US). */
    fun hhmm(ms: Long): String = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(Instant.ofEpochMilli(ms).atZone(zone))

    fun duration(minutes: Int): String = if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
}
