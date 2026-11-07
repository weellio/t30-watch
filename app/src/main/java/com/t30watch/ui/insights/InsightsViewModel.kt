package com.t30watch.ui.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.t30watch.data.db.HistoryDao
import com.t30watch.data.settings.AppSettings
import com.t30watch.data.settings.Settings
import com.t30watch.insights.ActivityInsight
import com.t30watch.insights.BpInsight
import com.t30watch.insights.BpPoint
import com.t30watch.insights.HeartInsight
import com.t30watch.insights.HrPoint
import com.t30watch.insights.SleepIn
import com.t30watch.insights.SleepInsight
import com.t30watch.insights.StepSlot
import com.t30watch.insights.TimeSpan
import com.t30watch.insights.analyzeActivity
import com.t30watch.insights.analyzeBp
import com.t30watch.insights.analyzeHeart
import com.t30watch.insights.analyzeSleep
import com.t30watch.util.Time
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import javax.inject.Inject

data class InsightsState(
    val loading: Boolean = true,
    val today: LocalDate = Time.today(),
    val heart: HeartInsight? = null,
    val bp: BpInsight? = null,
    val sleep: SleepInsight? = null,
    val activity: ActivityInsight? = null,
    val age: Int = 40,
    /** False until the user has saved a profile: zones then use a default age. */
    val profileSaved: Boolean = false,
    val use24Hour: Boolean = true,
)

/** Raw rows for the look-back window, already mapped to the plain insight inputs. */
private data class Raw(
    val slots: List<StepSlot>,
    val hr: List<HrPoint>,
    val bp: List<BpPoint>,
    val sleep: List<SleepIn>,
    val workouts: List<TimeSpan>,
    val dailySteps: Map<LocalDate, Int>,
)

@HiltViewModel
class InsightsViewModel @Inject constructor(
    dao: HistoryDao,
    settings: AppSettings,
    @param:dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
) : ViewModel() {
    /** Follow the phone's clock style (12 h in the US), not the watch setting. */
    private val phone24h: Boolean get() = android.text.format.DateFormat.is24HourFormat(context)

    private companion object {
        /** Two full weeks for trends, plus a margin. Older rows (e.g. a 2024 workout) never reach the maths. */
        const val LOOKBACK_DAYS = 27L
        const val DAY_MS = 86_400_000L
    }

    /** Ticks over at midnight so "this week" moves on. */
    private val today = flow {
        while (true) {
            emit(Time.today())
            delay(60_000)
        }
    }.distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<InsightsState> = today.flatMapLatest { day ->
        val fromDay = day.minusDays(LOOKBACK_DAYS)
        val from = Time.startOf(fromDay)
        val to = Time.endOf(day)
        val activity = combine(dao.slots(from, to), dao.dayTotals(Time.key(fromDay), Time.key(day))) { slots, totals -> slots to totals }
        val raw = combine(
            activity,
            dao.heart(from, to),
            dao.bp(from, to),
            dao.sleep(from - DAY_MS / 2, to),
            dao.workouts(from, to),
        ) { (slots, totals), heart, bp, sleep, workouts ->
            val stepSlots = slots.map { StepSlot(it.startMs, it.steps) }
            // Watch's own day total if we have it, else the sum of that day's slots (same rule as Today).
            val fromSlots = slots.groupBy { Time.dateOf(it.startMs) }.mapValues { (_, v) -> v.sumOf { it.steps } }
            val fromTotals = totals.associate { LocalDate.parse(it.day) to it.steps }
            val daily = (fromSlots.keys + fromTotals.keys).associateWith { maxOf(fromSlots[it] ?: 0, fromTotals[it] ?: 0) }
            Raw(
                slots = stepSlots,
                hr = heart.map { HrPoint(it.timeMs, it.bpm) },
                bp = bp.map { BpPoint(it.timeMs, it.systolic, it.diastolic) },
                sleep = sleep.map { SleepIn(it.startMs, it.endMs, it.deepMinutes, it.lightMinutes, it.awakeMinutes, it.wakeCount) },
                workouts = workouts.map { TimeSpan(it.startMs, it.endMs) },
                dailySteps = daily,
            )
        }
        combine(raw, settings.settings) { r, s -> compute(day, r, s) }
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InsightsState())

    private fun compute(day: LocalDate, r: Raw, s: Settings): InsightsState {
        val zone = Time.zone
        val p = s.profile
        val sleepSpans = r.sleep.map { TimeSpan(it.startMs, it.endMs) }
        return InsightsState(
            loading = false,
            today = day,
            heart = analyzeHeart(r.hr, r.slots, sleepSpans, r.workouts, p.age, day, zone),
            bp = analyzeBp(r.bp, r.slots, day, zone),
            sleep = analyzeSleep(r.sleep, p.sleepGoalMinutes, day, zone, phone24h),
            activity = analyzeActivity(r.dailySteps, r.slots, p.stepGoal, day, zone),
            age = p.age,
            profileSaved = p.saved,
            use24Hour = phone24h,
        )
    }
}
