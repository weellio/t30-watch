package com.t30watch.game

import com.t30watch.data.db.HistoryDao
import com.t30watch.data.db.MeasurementDao
import com.t30watch.data.db.MeasurementEntity
import com.t30watch.data.settings.AppSettings
import com.t30watch.di.AppScope
import com.t30watch.util.Time
import kotlinx.coroutines.CoroutineScope
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Feeds the database into [GameEngine] and shares one live [GameState] between the Today card and the
 * Progress screen. The rules themselves are pure; this class is only the plumbing.
 *
 * It reads all history (XP is lifetime). That is fine for months of data; if it ever gets slow, add
 * per-day aggregate queries to HistoryDao and build [DaySummary] from those instead.
 */
@Singleton
class GameRepository @Inject constructor(
    dao: HistoryDao,
    measurements: MeasurementDao,
    settings: AppSettings,
    @AppScope scope: CoroutineScope,
) {
    /** Ticks over at midnight so "today" is always today. */
    private val today = flow {
        while (true) {
            emit(Time.today())
            delay(30_000)
        }
    }.distinctUntilChanged()

    private val goals = settings.settings.map { it.profile.stepGoal to it.profile.sleepGoalMinutes }.distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<GameState?> = today.flatMapLatest { day ->
        val to = Time.endOf(day)
        val activity = combine(dao.slots(0L, to), dao.heart(0L, to), dao.dayTotals(MIN_DAY, Time.key(day))) { s, h, t -> Triple(s, h, t) }
        val other = combine(dao.sleep(0L, to), dao.workouts(0L, to), measurements.observeRecent(MAX_MEASUREMENTS)) { s, w, m -> Triple(s, w, m) }
        combine(activity, other, goals) { (slots, heart, totals), (sleep, workouts, measures), (stepGoal, sleepGoal) ->
            GameEngine.compute(
                GameInput(
                    today = day,
                    zone = Time.zone,
                    stepGoal = stepGoal,
                    sleepGoalMinutes = sleepGoal,
                    slots = slots.map { GameSlot(it.startMs, it.minutes, it.steps, it.distanceM, it.kcal) },
                    heartTimesMs = heart.map { it.timeMs },
                    dayTotals = totals.mapNotNull { t -> runCatching { LocalDate.parse(t.day) }.getOrNull()?.let { it to t.steps } }
                        .groupBy({ it.first }, { it.second }).mapValues { (_, v) -> v.max() },
                    sleeps = sleep.map { GameSleep(it.startMs, it.endMs, it.deepMinutes, it.lightMinutes, it.awakeMinutes) },
                    workouts = workouts.map {
                        GameWorkout(it.startMs, it.endMs, it.sportName, it.steps, it.distanceM, it.kcal, it.avgBpm, it.maxBpm)
                    },
                    measures = measures.mapNotNull { m ->
                        when (m.kind) {
                            MeasurementEntity.KIND_HEART_RATE -> GameMeasure(m.startedAt, MeasureKind.HEART_RATE)
                            MeasurementEntity.KIND_BLOOD_PRESSURE -> GameMeasure(m.startedAt, MeasureKind.BLOOD_PRESSURE)
                            else -> null
                        }
                    },
                ),
            )
        }
    }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    private companion object {
        const val MIN_DAY = "0000-01-01"
        const val MAX_MEASUREMENTS = 100_000
    }
}
