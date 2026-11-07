package com.t30watch.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.t30watch.data.db.BpSampleEntity
import com.t30watch.data.db.HeartSampleEntity
import com.t30watch.data.db.HistoryDao
import com.t30watch.data.db.SleepSessionEntity
import com.t30watch.data.settings.AppSettings
import com.t30watch.util.Time
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import javax.inject.Inject

enum class Metric(val label: String) { STEPS("Steps"), HEART("Heart rate"), SLEEP("Sleep"), BP("Blood pressure") }
enum class Span(val label: String) { DAY("Day"), WEEK("Week"), MONTH("Month") }

data class DayStat(val day: LocalDate, val steps: Int, val hrMin: Int?, val hrAvg: Int?, val hrMax: Int?, val sleepMin: Int?, val sys: Int?, val dia: Int?)

data class HistoryView(
    val metric: Metric = Metric.STEPS,
    val span: Span = Span.WEEK,
    val anchor: LocalDate = Time.today(),
    val from: LocalDate = Time.today(),
    val toExclusive: LocalDate = Time.today().plusDays(1),
    /** One per day in range (week/month), or one entry for day view. */
    val days: List<DayStat> = emptyList(),
    /** Day view only: steps per hour. */
    val hourlySteps: List<Int> = emptyList(),
    /** Day view only: raw samples. */
    val heart: List<HeartSampleEntity> = emptyList(),
    val bp: List<BpSampleEntity> = emptyList(),
    val sleep: List<SleepSessionEntity> = emptyList(),
    val stepGoal: Int = 8000,
    val sleepGoalMinutes: Int = 480,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val dao: HistoryDao,
    settings: AppSettings,
) : ViewModel() {
    private val metric = MutableStateFlow(Metric.STEPS)
    private val span = MutableStateFlow(Span.WEEK)
    private val anchor = MutableStateFlow(Time.today())

    fun setMetric(m: Metric) { metric.value = m }
    fun setSpan(s: Span) { span.value = s }
    fun previous() { anchor.value = step(anchor.value, -1) }
    fun next() { if (canGoNext()) anchor.value = step(anchor.value, +1) }
    fun canGoNext(): Boolean = step(anchor.value, +1) <= Time.today()

    private fun step(d: LocalDate, dir: Long): LocalDate = when (span.value) {
        Span.DAY -> d.plusDays(dir)
        Span.WEEK -> d.plusWeeks(dir)
        Span.MONTH -> d.plusMonths(dir)
    }

    private fun range(s: Span, a: LocalDate): Pair<LocalDate, LocalDate> = when (s) {
        Span.DAY -> a to a.plusDays(1)
        Span.WEEK -> Time.weekStart(a).let { it to it.plusDays(7) }
        Span.MONTH -> Time.monthStart(a).let { it to it.plusMonths(1) }
    }

    val view: StateFlow<HistoryView> = combine(metric, span, anchor) { m, s, a -> Triple(m, s, a) }
        .flatMapLatest { (m, s, a) ->
            val (from, to) = range(s, a)
            // Sleep that ends on the first morning starts the evening before.
            val fromMs = Time.startOf(from)
            val toMs = Time.endOf(to.minusDays(1))
            combine(
                dao.slots(fromMs, toMs),
                dao.heart(fromMs, toMs),
                dao.bp(fromMs, toMs),
                dao.sleep(fromMs - 12 * 3_600_000L, toMs),
                dao.dayTotals(Time.key(from), Time.key(to.minusDays(1))),
            ) { slots, heart, bp, sleep, totals ->
                val days = generateSequence(from) { it.plusDays(1) }.takeWhile { it < to }.map { d ->
                    val ds = Time.startOf(d)
                    val de = Time.endOf(d)
                    val hr = heart.filter { it.timeMs in ds until de }.map { it.bpm }
                    val b = bp.filter { it.timeMs in ds until de }
                    val night = sleep.filter { Time.dateOf(it.endMs) == d }
                    DayStat(
                        day = d,
                        steps = maxOf(totals.firstOrNull { it.day == Time.key(d) }?.steps ?: 0, slots.filter { it.startMs in ds until de }.sumOf { it.steps }),
                        hrMin = hr.minOrNull(), hrAvg = hr.takeIf { it.isNotEmpty() }?.average()?.toInt(), hrMax = hr.maxOrNull(),
                        sleepMin = night.takeIf { it.isNotEmpty() }?.sumOf { it.deepMinutes + it.lightMinutes },
                        sys = b.takeIf { it.isNotEmpty() }?.map { it.systolic }?.average()?.toInt(),
                        dia = b.takeIf { it.isNotEmpty() }?.map { it.diastolic }?.average()?.toInt(),
                    )
                }.toList()
                val hourly = if (s == Span.DAY) {
                    IntArray(24).also { arr -> slots.forEach { sl -> ((sl.startMs - fromMs) / 3_600_000L).toInt().takeIf { it in 0..23 }?.let { arr[it] += sl.steps } } }.toList()
                } else emptyList()
                HistoryView(
                    metric = m, span = s, anchor = a, from = from, toExclusive = to, days = days, hourlySteps = hourly,
                    heart = if (s == Span.DAY) heart else emptyList(),
                    bp = if (s == Span.DAY) bp else emptyList(),
                    sleep = sleep.filter { Time.dateOf(it.endMs) >= from && Time.dateOf(it.endMs) < to },
                )
            }
        }
        .combine(settings.settings.map { it.profile }) { v, p -> v.copy(stepGoal = p.stepGoal, sleepGoalMinutes = p.sleepGoalMinutes) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryView())
}
