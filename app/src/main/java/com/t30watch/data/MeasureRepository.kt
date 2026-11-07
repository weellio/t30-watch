package com.t30watch.data

import com.t30watch.ble.BpReading
import com.t30watch.ble.ConnectionState
import com.t30watch.ble.HeartSample
import com.t30watch.ble.HeartStatus
import com.t30watch.ble.RawLog
import com.t30watch.ble.VeepooClient
import com.t30watch.data.db.MeasurementDao
import com.t30watch.data.db.MeasurementEntity
import com.t30watch.di.AppScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

data class HeartSession(
    val running: Boolean = false,
    val startedAt: Long = 0,
    val samples: List<HeartSample> = emptyList(),
    val error: String? = null,
) {
    val status: HeartStatus? get() = samples.lastOrNull()?.status
    val values: List<Int> get() = samples.mapNotNull { it.bpm }
    val current: Int? get() = samples.lastOrNull()?.bpm ?: values.lastOrNull()
}

data class BpSession(
    val running: Boolean = false,
    val startedAt: Long = 0,
    val last: BpReading? = null,
    /** Set when the watch finished with a valid result. */
    val result: BpReading? = null,
    val error: String? = null,
)

/** Live, hand-started measurements. Only one runs at a time (the watch can't do two). */
@Singleton
class MeasureRepository @Inject constructor(
    private val client: VeepooClient,
    private val dao: MeasurementDao,
    private val log: RawLog,
    @param:AppScope private val scope: CoroutineScope,
) {
    private val _heart = MutableStateFlow(HeartSession())
    val heart: StateFlow<HeartSession> = _heart.asStateFlow()
    private val _bp = MutableStateFlow(BpSession())
    val bp: StateFlow<BpSession> = _bp.asStateFlow()

    val recent: Flow<List<MeasurementEntity>> = dao.observeRecent(20)

    private var heartJob: Job? = null
    private var bpJob: Job? = null

    val busy: Boolean get() = heartJob?.isActive == true || bpJob?.isActive == true

    fun startHeartRate(maxSeconds: Int = HEART_MAX_SECONDS) {
        if (busy) return
        val mac = connectedMac() ?: return
        val started = System.currentTimeMillis()
        _heart.value = HeartSession(running = true, startedAt = started)
        heartJob = scope.launch {
            var error: String? = null
            try {
                client.liveHeartRate(maxSeconds).collect { s -> _heart.update { it.copy(samples = (it.samples + s).takeLast(MAX_SAMPLES)) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("heart rate session", e)
                error = e.message
            } finally {
                withContext(NonCancellable) {
                    _heart.update { it.copy(running = false, error = error) }
                    saveHeart(mac, started, _heart.value.values)
                }
            }
        }
    }

    fun stopHeartRate() {
        heartJob?.cancel()
    }

    fun startBloodPressure() {
        if (busy) return
        val mac = connectedMac() ?: return
        val started = System.currentTimeMillis()
        _bp.value = BpSession(running = true, startedAt = started)
        bpJob = scope.launch {
            var error: String? = null
            try {
                client.measureBloodPressure().collect { r -> _bp.update { it.copy(last = r) } }
                val last = _bp.value.last
                if (last != null && last.valid) {
                    _bp.update { it.copy(result = last) }
                    dao.insert(
                        MeasurementEntity(
                            mac = mac, kind = MeasurementEntity.KIND_BLOOD_PRESSURE, startedAt = started,
                            endedAt = System.currentTimeMillis(), systolic = last.systolic, diastolic = last.diastolic,
                        ),
                    )
                    log.app("bp saved", "${last.systolic}/${last.diastolic}")
                } else {
                    error = "The watch didn't return a valid reading. Keep still and try again."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("blood pressure session", e)
                error = e.message
            } finally {
                withContext(NonCancellable) { _bp.update { it.copy(running = false, error = error) } }
            }
        }
    }

    fun stopBloodPressure() {
        bpJob?.cancel()
    }

    fun stopAll() {
        stopHeartRate()
        stopBloodPressure()
    }

    private fun connectedMac(): String? = (client.state.value as? ConnectionState.Ready)?.mac

    private suspend fun saveHeart(mac: String, started: Long, values: List<Int>) {
        if (values.isEmpty()) return
        dao.insert(
            MeasurementEntity(
                mac = mac, kind = MeasurementEntity.KIND_HEART_RATE, startedAt = started, endedAt = System.currentTimeMillis(),
                bpmLast = values.last(), bpmMin = values.min(), bpmAvg = values.average().roundToInt(), bpmMax = values.max(),
                bpmSamples = values.size,
            ),
        )
        log.app("heart rate saved", "n=${values.size} last=${values.last()}")
    }

    companion object {
        /** Safety cap so a forgotten session can't drain the watch. */
        const val HEART_MAX_SECONDS = 300
        private const val MAX_SAMPLES = 2_000
    }
}
