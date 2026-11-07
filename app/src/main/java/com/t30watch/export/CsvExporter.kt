package com.t30watch.export

import android.content.Context
import com.t30watch.data.db.HistoryDao
import com.t30watch.data.db.MeasurementDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes one CSV file per data type for a time range, ready for the share sheet.
 * Times are ISO-8601 local time with offset, so spreadsheets and scripts both read them.
 */
@Singleton
class CsvExporter @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val history: HistoryDao,
    private val measurements: MeasurementDao,
) {
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val iso = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    private fun t(ms: Long) = iso.format(Instant.ofEpochMilli(ms).atZone(zone))

    /** Returns the files written (empty types are skipped). */
    suspend fun export(fromMs: Long, toMs: Long): List<File> = withContext(Dispatchers.IO) {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "exports").apply {
            mkdirs()
            listFiles()?.forEach { it.delete() }
        }
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd").format(Instant.ofEpochMilli(toMs).atZone(zone))
        val out = mutableListOf<File>()

        fun write(name: String, header: String, rows: List<String>) {
            if (rows.isEmpty()) return
            val f = File(dir, "t30_${name}_$stamp.csv")
            f.bufferedWriter().use { w ->
                w.appendLine(header)
                rows.forEach { w.appendLine(it) }
            }
            out += f
        }

        write(
            "activity", "start,minutes,steps,distance_m,kcal",
            history.slotsOnce(fromMs, toMs).map { "${t(it.startMs)},${it.minutes},${it.steps},${it.distanceM ?: ""},${it.kcal ?: ""}" },
        )
        write(
            "heart_rate", "time,bpm",
            history.heartOnce(fromMs, toMs).map { "${t(it.timeMs)},${it.bpm}" },
        )
        write(
            "blood_pressure", "time,systolic,diastolic",
            history.bpOnce(fromMs, toMs).map { "${t(it.timeMs)},${it.systolic},${it.diastolic}" },
        )
        write(
            "sleep", "start,end,deep_min,light_min,awake_min,wake_count,quality,stages",
            history.sleepOnce(fromMs, toMs).map {
                "${t(it.startMs)},${t(it.endMs)},${it.deepMinutes},${it.lightMinutes},${it.awakeMinutes},${it.wakeCount},${it.quality ?: ""},${it.stages}"
            },
        )
        write(
            "workouts", "start,end,sport,steps,distance_m,kcal,avg_bpm,max_bpm",
            history.workoutsOnce(fromMs, toMs).map {
                "${t(it.startMs)},${t(it.endMs)},${csv(it.sportName)},${it.steps ?: ""},${it.distanceM ?: ""},${it.kcal ?: ""},${it.avgBpm ?: ""},${it.maxBpm ?: ""}"
            },
        )
        val dayFmt = DateTimeFormatter.ISO_LOCAL_DATE
        write(
            "daily_totals", "day,steps,distance_m,kcal",
            history.dayTotalsOnce(
                dayFmt.format(Instant.ofEpochMilli(fromMs).atZone(zone)),
                dayFmt.format(Instant.ofEpochMilli(toMs).atZone(zone)),
            ).map { "${it.day},${it.steps},${it.distanceM ?: ""},${it.kcal ?: ""}" },
        )
        write(
            "manual_measurements", "start,end,kind,bpm_last,bpm_min,bpm_avg,bpm_max,systolic,diastolic",
            measurements.between(fromMs, toMs).map {
                "${t(it.startedAt)},${t(it.endedAt)},${it.kind},${it.bpmLast ?: ""},${it.bpmMin ?: ""},${it.bpmAvg ?: ""},${it.bpmMax ?: ""},${it.systolic ?: ""},${it.diastolic ?: ""}"
            },
        )
        out
    }

    private fun csv(s: String) = if (s.contains(',') || s.contains('"')) "\"" + s.replace("\"", "\"\"") + "\"" else s
}
