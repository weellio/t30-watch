package com.t30watch.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Pressure
import com.t30watch.ble.RawLog
import com.t30watch.data.db.HistoryDao
import com.t30watch.data.db.MeasurementDao
import com.t30watch.data.db.MeasurementEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Copies synced watch data into Health Connect (Android's shared health store, used by Google Fit,
 * Samsung Health, etc.). Every record gets a stable clientRecordId, so exporting the same range again
 * updates records instead of duplicating them.
 */
@Singleton
class HealthConnectExporter @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val history: HistoryDao,
    private val measurements: MeasurementDao,
    private val log: RawLog,
) {
    val permissions: Set<String> = setOf(
        HealthPermission.getWritePermission(StepsRecord::class),
        HealthPermission.getWritePermission(DistanceRecord::class),
        HealthPermission.getWritePermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getWritePermission(HeartRateRecord::class),
        HealthPermission.getWritePermission(BloodPressureRecord::class),
        HealthPermission.getWritePermission(SleepSessionRecord::class),
        HealthPermission.getWritePermission(ExerciseSessionRecord::class),
    )

    enum class Availability { AVAILABLE, NEEDS_UPDATE, NOT_SUPPORTED }

    fun availability(): Availability = when (HealthConnectClient.getSdkStatus(context)) {
        HealthConnectClient.SDK_AVAILABLE -> Availability.AVAILABLE
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> Availability.NEEDS_UPDATE
        else -> Availability.NOT_SUPPORTED
    }

    private fun client() = HealthConnectClient.getOrCreate(context)

    suspend fun grantedPermissions(): Set<String> =
        if (availability() != Availability.AVAILABLE) emptySet()
        else runCatching { client().permissionController.getGrantedPermissions() }.getOrDefault(emptySet())

    suspend fun hasAllPermissions(): Boolean = grantedPermissions().containsAll(permissions)

    data class Result(val written: Int, val skippedTypes: List<String>)

    /** Exports everything stored between [fromMs] and [toMs]. Types without permission are skipped. */
    suspend fun export(fromMs: Long, toMs: Long, mac: String = "T30"): Result {
        val granted = grantedPermissions()
        fun allowed(cls: kotlin.reflect.KClass<out Record>) = HealthPermission.getWritePermission(cls) in granted
        val device = Device(manufacturer = "Veepoo", model = "T30", type = Device.TYPE_WATCH)
        val version = System.currentTimeMillis()
        fun meta(id: String) = Metadata.autoRecorded(device = device, clientRecordId = id, clientRecordVersion = version)
        fun manual(id: String) = Metadata.manualEntry(device = device, clientRecordId = id, clientRecordVersion = version)

        val records = mutableListOf<Record>()
        val skipped = mutableListOf<String>()

        val slots = history.slotsOnce(fromMs, toMs).filter { it.steps > 0 || (it.distanceM ?: 0) > 0 || (it.kcal ?: 0.0) > 0 }
        if (allowed(StepsRecord::class)) {
            slots.filter { it.steps > 0 }.forEach { s ->
                val (st, en) = s.startMs.i() to (s.startMs + s.minutes * 60_000L).i()
                records += StepsRecord(st, off(st), en, off(en), s.steps.toLong(), meta("steps-${s.mac}-${s.startMs}"))
            }
        } else skipped += "steps"
        if (allowed(DistanceRecord::class)) {
            slots.filter { (it.distanceM ?: 0) > 0 }.forEach { s ->
                val (st, en) = s.startMs.i() to (s.startMs + s.minutes * 60_000L).i()
                records += DistanceRecord(st, off(st), en, off(en), Length.meters(s.distanceM!!.toDouble()), meta("dist-${s.mac}-${s.startMs}"))
            }
        } else skipped += "distance"
        if (allowed(ActiveCaloriesBurnedRecord::class)) {
            slots.filter { (it.kcal ?: 0.0) > 0 }.forEach { s ->
                val (st, en) = s.startMs.i() to (s.startMs + s.minutes * 60_000L).i()
                records += ActiveCaloriesBurnedRecord(st, off(st), en, off(en), Energy.kilocalories(s.kcal!!), meta("kcal-${s.mac}-${s.startMs}"))
            }
        } else skipped += "calories"

        if (allowed(HeartRateRecord::class)) {
            // One record per hour, holding that hour's samples.
            history.heartOnce(fromMs, toMs).groupBy { it.timeMs / HOUR }.forEach { (hour, rows) ->
                val st = Instant.ofEpochMilli(hour * HOUR)
                val en = Instant.ofEpochMilli(maxOf(rows.last().timeMs + 1, hour * HOUR + 1))
                records += HeartRateRecord(
                    st, off(st), en, off(en),
                    rows.map { HeartRateRecord.Sample(it.timeMs.i(), it.bpm.toLong()) },
                    meta("hr-${rows.first().mac}-${hour * HOUR}"),
                )
            }
            measurements.between(fromMs, toMs).filter { it.kind == MeasurementEntity.KIND_HEART_RATE && it.bpmLast != null }.forEach { m ->
                val st = m.startedAt.i()
                val en = maxOf(m.endedAt, m.startedAt + 1).i()
                records += HeartRateRecord(st, off(st), en, off(en), listOf(HeartRateRecord.Sample(en, m.bpmLast!!.toLong())), manual("hr-manual-${m.id}"))
            }
        } else skipped += "heart rate"

        if (allowed(BloodPressureRecord::class)) {
            history.bpOnce(fromMs, toMs).filter { it.systolic in HC_SYS && it.diastolic in HC_DIA }.forEach { b ->
                val t = b.timeMs.i()
                records += BloodPressureRecord(
                    time = t, zoneOffset = off(t),
                    systolic = Pressure.millimetersOfMercury(b.systolic.toDouble()),
                    diastolic = Pressure.millimetersOfMercury(b.diastolic.toDouble()),
                    metadata = meta("bp-${b.mac}-${b.timeMs}"),
                )
            }
            measurements.between(fromMs, toMs).filter { it.kind == MeasurementEntity.KIND_BLOOD_PRESSURE && (it.systolic ?: -1) in HC_SYS && (it.diastolic ?: -1) in HC_DIA }.forEach { m ->
                val t = m.endedAt.i()
                records += BloodPressureRecord(
                    time = t, zoneOffset = off(t),
                    systolic = Pressure.millimetersOfMercury(m.systolic!!.toDouble()),
                    diastolic = Pressure.millimetersOfMercury(m.diastolic!!.toDouble()),
                    metadata = manual("bp-manual-${m.id}"),
                )
            }
        } else skipped += "blood pressure"

        if (allowed(SleepSessionRecord::class)) {
            history.sleepOnce(fromMs, toMs).filter { it.endMs > it.startMs }.forEach { s ->
                val st = s.startMs.i()
                val en = s.endMs.i()
                records += SleepSessionRecord(
                    startTime = st, startZoneOffset = off(st), endTime = en, endZoneOffset = off(en),
                    title = "Sleep (T30)",
                    stages = stagesOf(s.startMs, s.endMs, s.stages),
                    metadata = meta("sleep-${s.mac}-${s.startMs}"),
                )
            }
        } else skipped += "sleep"

        if (allowed(ExerciseSessionRecord::class)) {
            history.workoutsOnce(fromMs, toMs).filter { it.endMs > it.startMs }.forEach { w ->
                val st = w.startMs.i()
                val en = w.endMs.i()
                records += ExerciseSessionRecord(
                    startTime = st, startZoneOffset = off(st), endTime = en, endZoneOffset = off(en),
                    exerciseType = SportTypes.healthConnectType(w.sportName),
                    title = w.sportName,
                    metadata = meta("workout-${w.mac}-${w.startMs}"),
                )
            }
        } else skipped += "workouts"

        var written = 0
        records.chunked(500).forEach { chunk ->
            client().insertRecords(chunk)
            written += chunk.size
        }
        log.app("health connect export", "written=$written skipped=$skipped range=$fromMs..$toMs mac=$mac")
        return Result(written, skipped)
    }

    /** Turns the per-minute stage string into Health Connect stages; empty string → no stages. */
    private fun stagesOf(startMs: Long, endMs: Long, stages: String): List<SleepSessionRecord.Stage> {
        if (stages.isEmpty()) return emptyList()
        val out = mutableListOf<SleepSessionRecord.Stage>()
        var runStart = 0
        for (i in 1..stages.length) {
            if (i == stages.length || stages[i] != stages[runStart]) {
                val st = startMs + runStart * 60_000L
                val en = minOf(startMs + i * 60_000L, endMs)
                if (en > st) {
                    val type = when (stages[runStart]) {
                        'D' -> SleepSessionRecord.STAGE_TYPE_DEEP
                        'L' -> SleepSessionRecord.STAGE_TYPE_LIGHT
                        'W' -> SleepSessionRecord.STAGE_TYPE_AWAKE
                        else -> SleepSessionRecord.STAGE_TYPE_SLEEPING
                    }
                    out += SleepSessionRecord.Stage(st.i(), en.i(), type)
                }
                runStart = i
            }
        }
        return out
    }

    private fun Long.i(): Instant = Instant.ofEpochMilli(this)
    private fun off(i: Instant): ZoneOffset = ZoneId.systemDefault().rules.getOffset(i)

    private companion object {
        const val HOUR = 3_600_000L
        // Health Connect rejects values outside these (one bad reading would fail the whole insert).
        val HC_SYS = 20..200
        val HC_DIA = 10..180
    }
}
