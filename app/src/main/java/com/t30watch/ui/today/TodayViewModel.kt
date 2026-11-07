package com.t30watch.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.t30watch.data.WatchRepository
import com.t30watch.data.db.ActivitySlotEntity
import com.t30watch.data.db.BpSampleEntity
import com.t30watch.data.db.DayTotalEntity
import com.t30watch.data.db.HeartSampleEntity
import com.t30watch.data.db.HistoryDao
import com.t30watch.data.db.SleepSessionEntity
import com.t30watch.data.db.WorkoutEntity
import com.t30watch.data.settings.AppSettings
import com.t30watch.data.settings.Settings
import com.t30watch.data.sync.SyncEngine
import com.t30watch.util.Time
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class TodayData(
    val total: DayTotalEntity? = null,
    val slots: List<ActivitySlotEntity> = emptyList(),
    val heart: List<HeartSampleEntity> = emptyList(),
    val latestBp: BpSampleEntity? = null,
    val lastSleep: SleepSessionEntity? = null,
    val workouts: List<WorkoutEntity> = emptyList(),
) {
    /** Watch's own daily total if we have it, else the sum of today's slots. */
    val steps: Int get() = maxOf(total?.steps ?: 0, slots.sumOf { it.steps })
    val distanceM: Int? get() = total?.distanceM ?: slots.mapNotNull { it.distanceM }.takeIf { it.isNotEmpty() }?.sum()
    val kcal: Double? get() = total?.kcal ?: slots.mapNotNull { it.kcal }.takeIf { it.isNotEmpty() }?.sum()

    /** Steps per hour 0..23. */
    fun hourly(): List<Int> {
        val start = Time.startOf(Time.today())
        val out = IntArray(24)
        slots.forEach { s -> ((s.startMs - start) / 3_600_000L).toInt().takeIf { it in 0..23 }?.let { out[it] += s.steps } }
        return out.toList()
    }
}

@HiltViewModel
class TodayViewModel @Inject constructor(
    dao: HistoryDao,
    settings: AppSettings,
    watch: WatchRepository,
    private val sync: SyncEngine,
) : ViewModel() {
    /** Ticks over at midnight so "today" never shows yesterday. */
    private val today = flow {
        while (true) {
            emit(Time.today())
            delay(30_000)
        }
    }.distinctUntilChanged()

    val connection = watch.connection
    val battery = watch.battery
    val syncStatus = sync.status
    val settings: StateFlow<Settings> = settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Settings())

    @OptIn(ExperimentalCoroutinesApi::class)
    val data: StateFlow<TodayData> = today.flatMapLatest { day ->
        val from = Time.startOf(day)
        val to = Time.endOf(day)
        val activity = combine(dao.dayTotals(Time.key(day), Time.key(day)), dao.slots(from, to)) { t, s -> t.firstOrNull() to s }
        combine(
            activity,
            dao.heart(from, to),
            dao.latestBp(),
            dao.latestSleep(),
            dao.workouts(from - 6 * 86_400_000L, to),
        ) { (total, slots), heart, bp, sleep, workouts ->
            // Only last night's sleep: the watch can hand back weeks-old sessions (seen: September on first sync).
            val lastNight = sleep?.takeIf { it.endMs >= from - 12 * 3_600_000L }
            TodayData(total, slots, heart, bp, lastNight, workouts.take(3))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayData())

    fun syncNow() = sync.syncNow()
}
