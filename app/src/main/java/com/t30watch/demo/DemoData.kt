package com.t30watch.demo

import com.t30watch.ble.BatteryInfo
import com.t30watch.ble.ConnectionState
import com.t30watch.ble.DumpField
import com.t30watch.ble.DumpSection
import com.t30watch.ble.WatchInfo
import com.t30watch.data.db.ActivitySlotEntity
import com.t30watch.data.db.BpSampleEntity
import com.t30watch.data.db.DayTotalEntity
import com.t30watch.data.db.HeartSampleEntity
import com.t30watch.data.db.HistoryDao
import com.t30watch.data.db.KnownDeviceDao
import com.t30watch.data.db.KnownDeviceEntity
import com.t30watch.data.db.MeasurementDao
import com.t30watch.data.db.MeasurementEntity
import com.t30watch.data.db.SleepSessionEntity
import com.t30watch.data.db.WorkoutEntity
import com.t30watch.data.settings.AppSettings
import com.t30watch.data.settings.Profile
import com.t30watch.data.settings.Sex
import com.t30watch.util.Time
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Demo build only (BuildConfig.DEMO): made-up data for screenshots and for trying the app without a watch.
 * Nothing here runs in the real app.
 */
object Demo {
    const val MAC = "00:00:00:00:DE:30"

    val connection = ConnectionState.Ready(
        MAC, "T30",
        WatchInfo(
            deviceNumber = 3238, firmwareVersion = "03.01.01", testVersion = "03.01.01.00",
            passwordStatus = "CHECK_AND_TIME_SUCCESS", inFirmwareUpdateMode = false, cpuType = "UNKONW",
            isJieLi = false, partial = false,
            sections = listOf(
                DumpSection(
                    "Feature package 1",
                    listOf(WatchInfo.HEART_RATE, WatchInfo.BLOOD_PRESSURE, "heartRateWarning", "sedentaryRemind", "alarm", "camera")
                        .map { DumpField(it, "SUPPORT", true) },
                ),
            ),
        ),
    )

    val battery = BatteryInfo(percent = 75, isExactPercent = false, charging = false, low = false, raw = "demo")
}

@Singleton
class DemoSeeder @Inject constructor(
    private val history: HistoryDao,
    private val measurements: MeasurementDao,
    private val devices: KnownDeviceDao,
    private val settings: AppSettings,
) {
    private val mac = Demo.MAC

    /** Fills the database once (first launch of the demo build). */
    suspend fun seedIfEmpty() {
        if (history.countSlots() > 0) return
        val rnd = Random(30)
        val today = Time.today()
        val now = System.currentTimeMillis()
        settings.setProfile(Profile(heightCm = 178, weightKg = 84, age = 42, sex = Sex.MALE, stepGoal = 8_000, sleepGoalMinutes = 450, saved = true))
        devices.upsert(KnownDeviceEntity(mac, "T30", now))

        for (d in 20 downTo 0) {
            val day = today.minusDays(d.toLong())
            seedDay(day, isToday = d == 0, rnd, now, daysAgo = d)
        }
    }

    private suspend fun seedDay(day: LocalDate, isToday: Boolean, rnd: Random, now: Long, daysAgo: Int) {
        val start = Time.startOf(day)
        val weekend = day.dayOfWeek.value >= 6
        // Gentle improvement over three weeks: more steps, lower resting HR.
        val fitness = (20 - daysAgo) / 20f
        val restBase = 66 - (fitness * 4).roundToInt()
        val slots = mutableListOf<ActivitySlotEntity>()
        val heart = mutableListOf<HeartSampleEntity>()
        val bp = mutableListOf<BpSampleEntity>()
        val walkHours = if (weekend) setOf(9, 10, 15, 16) else setOf(7, 12, 17, 18)
        val workoutHour = if (day.dayOfWeek.value in listOf(2, 4, 6)) 18 else null

        for (i in 0 until 288) {
            val t = start + i * 300_000L
            if (t > now) break
            val hour = i / 12
            val asleep = hour < 6 || (hour == 6 && i % 12 < 9) || hour >= 23
            val steps = when {
                asleep -> 0
                workoutHour == hour && i % 12 < 8 -> 520 + rnd.nextInt(160)
                hour in walkHours && rnd.nextFloat() < 0.35f + fitness * 0.15f -> 200 + rnd.nextInt(250)
                hour in 8..21 -> if (rnd.nextFloat() < 0.45f) rnd.nextInt(90) else 0
                else -> 0
            }
            slots += ActivitySlotEntity(mac, t, 5, steps, (steps * 0.76).roundToInt(), steps * 0.045)
            // Watch alternates heart rate and blood pressure every ~10 minutes.
            if (i % 2 == 0) {
                val bpm = when {
                    asleep -> restBase - 6 + rnd.nextInt(6)
                    steps >= 500 -> 118 + rnd.nextInt(30)
                    steps >= 250 -> 95 + rnd.nextInt(15)
                    steps > 0 -> restBase + 6 + rnd.nextInt(10)
                    else -> restBase + rnd.nextInt(8)
                }
                heart += HeartSampleEntity(mac, t, bpm)
            } else if (!asleep && i % 12 == 1) {
                val moving = steps >= 100
                val sys = 117 + rnd.nextInt(10) + (if (moving) 12 else 0) - (fitness * 3).roundToInt()
                val dia = 74 + rnd.nextInt(7) + (if (moving) 6 else 0)
                bp += BpSampleEntity(mac, t, sys, dia)
            }
        }
        val total = slots.sumOf { it.steps }
        history.upsertSlots(slots)
        history.upsertHeart(heart)
        history.upsertBp(bp)
        history.upsertDayTotal(DayTotalEntity(mac, Time.key(day), total, (total * 0.76).roundToInt(), total * 0.045, now))

        // Last night's sleep ends this morning.
        val bedMin = 22 * 60 + 40 + rnd.nextInt(50) // 22:40–23:30
        val sleepStart = start - (24 * 60 - bedMin) * 60_000L
        val lengthMin = 400 + rnd.nextInt(70)
        val stages = buildString {
            var m = 0
            while (m < lengthMin) {
                val cycle = m % 90
                val c = when {
                    cycle in 20..45 && m < lengthMin * 0.7 -> 'D'
                    cycle in 86..89 && rnd.nextFloat() < 0.5f -> 'W'
                    else -> 'L'
                }
                repeat(5) { append(c) }
                m += 5
            }
        }.take(lengthMin)
        val deep = stages.count { it == 'D' }
        val awake = stages.count { it == 'W' }
        history.upsertSleep(
            listOf(
                SleepSessionEntity(
                    mac, sleepStart, sleepStart + lengthMin * 60_000L, deep, lengthMin - deep - awake, awake,
                    wakeCount = awake / 5, quality = 3 + rnd.nextInt(3), stages = stages,
                ),
            ),
        )

        if (workoutHour != null && !(isToday && now < start + (workoutHour + 1) * 3_600_000L)) {
            val ws = start + workoutHour * 3_600_000L + 5 * 60_000L
            val mins = 32 + rnd.nextInt(25)
            val dist = (mins * 120 + rnd.nextInt(800))
            history.upsertWorkouts(
                listOf(
                    WorkoutEntity(
                        mac, ws, ws + mins * 60_000L, 1, if (weekend) "Outdoor walk" else "Outdoor run",
                        steps = mins * 140, distanceM = dist, kcal = mins * 9.5, avgBpm = 128 + rnd.nextInt(14), maxBpm = 152 + rnd.nextInt(16),
                    ),
                ),
            )
        }

        // A few manual checks from the Measure screen.
        if (daysAgo % 2 == 0) {
            val t = start + 8 * 3_600_000L + 15 * 60_000L
            if (t < now) {
                measurements.insert(MeasurementEntity(mac = mac, kind = MeasurementEntity.KIND_BLOOD_PRESSURE, startedAt = t, endedAt = t + 30_000, systolic = 118 + rnd.nextInt(6), diastolic = 76 + rnd.nextInt(4)))
                measurements.insert(MeasurementEntity(mac = mac, kind = MeasurementEntity.KIND_HEART_RATE, startedAt = t + 60_000, endedAt = t + 90_000, bpmLast = restBase + 2, bpmMin = restBase, bpmAvg = restBase + 2, bpmMax = restBase + 5, bpmSamples = 15))
            }
        }
    }
}
