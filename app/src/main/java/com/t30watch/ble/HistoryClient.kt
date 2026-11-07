package com.t30watch.ble

import android.content.Context
import com.inuker.bluetooth.library.Code
import com.inuker.bluetooth.library.connect.response.BleWriteResponse
import com.t30watch.data.db.ActivitySlotEntity
import com.t30watch.data.db.BpSampleEntity
import com.t30watch.data.db.DayTotalEntity
import com.t30watch.data.db.HeartSampleEntity
import com.t30watch.data.db.SleepSessionEntity
import com.t30watch.data.db.WorkoutEntity
import com.t30watch.data.settings.Profile
import com.t30watch.data.settings.Sex
import com.t30watch.data.sync.storedDaysFromFeatures
import com.t30watch.util.Time
import com.veepoo.protocol.listener.base.IBleWriteResponse
import com.veepoo.protocol.listener.data.IOriginDataListener
import com.veepoo.protocol.listener.data.IPersonInfoDataListener
import com.veepoo.protocol.listener.data.ISleepDataListener
import com.veepoo.protocol.listener.data.ISportDataListener
import com.veepoo.protocol.listener.data.ISportModelDataCrcListener
import com.veepoo.protocol.listener.data.ISportModelOriginListener
import com.veepoo.protocol.model.datas.OriginData
import com.veepoo.protocol.model.datas.OriginHalfHourData
import com.veepoo.protocol.model.datas.PersonInfoData
import com.veepoo.protocol.model.datas.SleepData
import com.veepoo.protocol.model.datas.SportData
import com.veepoo.protocol.model.datas.SportModelGPSWatchOriginHeadData
import com.veepoo.protocol.model.datas.SportModelOriginHeadData
import com.veepoo.protocol.model.datas.SportModelOriginItemData
import com.veepoo.protocol.model.datas.TimeData
import com.veepoo.protocol.model.enums.EOprateStauts
import com.veepoo.protocol.model.enums.ESex
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/** What one history read produced, already in app shapes. */
data class HistoryBatch(
    val slots: List<ActivitySlotEntity>,
    val heart: List<HeartSampleEntity>,
    val bp: List<BpSampleEntity>,
    val dayTotals: List<DayTotalEntity>,
)

/**
 * History reads (steps, 5-minute data, sleep, workouts, profile).
 * Rules from docs/sdk-notes.md + the history brief:
 * - The T30 is origin protocol 0 → must use IOriginDataListener (IOriginData3Listener is silently ignored).
 * - The SDK has no timeouts for most reads and no cancel API → every read here has an inactivity watchdog
 *   and a hard cap, and after a watchdog failure we pause before freeing the queue (the watch may still stream).
 * - The SDK doesn't remember what was read → we store the last package number per date and resume from it.
 */
@Singleton
class HistoryClient @Inject constructor(
    @ApplicationContext context: Context,
    private val core: VeepooClient,
) {
    private val log get() = core.log
    private val vp get() = core.vp
    private val prefs = context.getSharedPreferences("history_cursor", Context.MODE_PRIVATE)

    fun storedDays(info: WatchInfo): Int = info.storedDaysFromFeatures()

    // ------------------------------------------------------------------ profile

    suspend fun syncPersonInfo(p: Profile) {
        val status: EOprateStauts? = core.command("syncPersonInfo", 10_000) { once ->
            vp.syncPersonInfo(
                core.writeLogger("syncPersonInfo") { once.fail(WatchException("Couldn't send the profile (code $it)")) },
                object : IPersonInfoDataListener {
                    override fun OnPersoninfoDataChange(s: EOprateStauts?) {
                        log.cb("OnPersoninfoDataChange", s)
                        once.ok(s)
                    }
                },
                PersonInfoData(
                    if (p.sex == Sex.FEMALE) ESex.WOMEN else ESex.MAN,
                    p.heightCm, p.weightKg, p.age, p.stepGoal, p.sleepGoalMinutes,
                ),
            )
        }
        if (status != EOprateStauts.OPRATE_SUCCESS) throw WatchException("Watch didn't accept the profile ($status)")
    }

    // ------------------------------------------------------------------ today's totals

    suspend fun readTodayTotals(): DayTotalEntity? {
        val mac = core.connectedMac ?: return null
        val d: SportData = core.command("readSportStep", 10_000) { once ->
            vp.readSportStep(
                core.writeLogger("readSportStep") { once.fail(WatchException("Couldn't ask for today's steps (code $it)")) },
                object : ISportDataListener {
                    override fun onSportDataChange(s: SportData?) {
                        log.cb("onSportDataChange", s)
                        if (s != null) once.ok(s)
                    }
                },
            )
        }
        return DayTotalEntity(
            mac = mac, day = Time.key(Time.today()), steps = d.step,
            distanceM = (d.dis * 1000).toInt(), kcal = d.kcal, updatedAt = System.currentTimeMillis(),
        )
    }

    // ------------------------------------------------------------------ 5-minute data

    /**
     * Reads the 5-minute records for today and any past day not yet fully read, resuming each day from the
     * last package we stored. [onProgress] gets short human text.
     */
    suspend fun readHistory(onProgress: (String) -> Unit, save: suspend (HistoryBatch) -> Unit): Int {
        val mac = core.connectedMac ?: throw WatchException("Watch not connected")
        val info = (core.state.value as? ConnectionState.Ready)?.info ?: throw WatchException("Watch not connected")
        val watchDays = storedDays(info)
        var total = 0

        for (day in 0 until watchDays) {
            // Recompute "today" per day: a sync that crosses midnight must not shift dates.
            val date = Time.today().minusDays(day.toLong())
            val key = Time.key(date)
            if (day > 0 && prefs.getBoolean("done_$key", false)) continue
            val start = prefs.getInt("pos_$key", 0).coerceAtLeast(1)
            onProgress(if (day == 0) "Activity: today" else "Activity: ${date.dayOfWeek.name.take(3).lowercase()} ($day d ago)")

            var records = readOriginDay(day, start, watchDays)
            if (records.isEmpty() && start > 1) {
                // Stale cursor (watch reset or a different watch): start the day over.
                log.app("origin day $day", "no records from pkg $start; re-reading from 1")
                records = readOriginDay(day, 1, watchDays)
            }
            val slots = mutableListOf<ActivitySlotEntity>()
            val heart = mutableListOf<HeartSampleEntity>()
            val bp = mutableListOf<BpSampleEntity>()
            var maxPkg = 1
            var allPkg = 0
            for (r in records) {
                val t = r.getmTime() ?: continue
                if (r.packageNumber <= 0 || r.allPackage <= 0) continue
                // The SDK dates each record at parse time ("yyyy-MM-dd"); prefer that over our loop date.
                val recDate = r.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: date
                val ms = toMs(recDate, t) ?: continue
                maxPkg = maxOf(maxPkg, r.packageNumber)
                allPkg = maxOf(allPkg, r.allPackage)
                slots += ActivitySlotEntity(
                    mac = mac, startMs = ms, minutes = 5, steps = r.stepValue.coerceAtLeast(0),
                    distanceM = (r.disValue * 1000).toInt().takeIf { it >= 0 }, kcal = r.calValue.takeIf { it >= 0 },
                )
                if (r.rateValue in 30..200) heart += HeartSampleEntity(mac, ms, r.rateValue)
                if (r.highValue in 60..300 && r.lowValue in 20..200) bp += BpSampleEntity(mac, ms, r.highValue, r.lowValue)
            }
            // Store first, then move the cursor: a failure later never skips data.
            save(HistoryBatch(slots, heart, bp, emptyList()))
            total += slots.size
            if (records.isNotEmpty()) {
                // An empty day comes back as one all-zero placeholder (allPackage=0). A past empty day stays empty.
                val emptyPastDay = day > 0 && records.all { it.allPackage <= 0 }
                prefs.edit().apply {
                    putInt("pos_$key", maxPkg)
                    // Past days never grow once read to the end. Today keeps growing, so it's never "done".
                    if (day > 0 && allPkg > 0 && maxPkg >= allPkg) putBoolean("done_$key", true)
                    if (emptyPastDay) putBoolean("done_$key", true)
                }.commit()
            }
            log.app("origin day $day", "$key records=${records.size} pkg up to $maxPkg of $allPkg")
        }
        pruneCursorKeys(Time.today().minusDays(watchDays.toLong() + 2))
        return total
    }

    /** One day of 5-minute data (one queued command). */
    private suspend fun readOriginDay(day: Int, startPos: Int, watchDays: Int): List<OriginData> {
        val out = Collections.synchronizedList(mutableListOf<OriginData>())
        watched("readOriginDataSingleDay d=$day from=$startPos", inactivityMs = 45_000, hardCapMs = 120_000) { touch, done ->
            vp.readOriginDataSingleDay(
                core.writeLogger("readOriginDataSingleDay") { done.complete(Result.failure(WatchException("Couldn't ask for activity data (code $it)"))) },
                object : IOriginDataListener {
                    override fun onReadOriginProgressDetail(d: Int, date: String?, all: Int, cur: Int) { touch(); log.cb("onReadOriginProgressDetail", "day=$d date=$date $cur/$all") }
                    override fun onReadOriginProgress(p: Float) { touch() }
                    override fun onReadOriginComplete() { log.cb("onReadOriginComplete"); done.complete(Result.success(Unit)) }
                    override fun onReadTimeout(d: Int) { log.cb("onReadTimeout", d); done.complete(Result.success(Unit)) }
                    override fun onOringinFiveMinuteDataChange(o: OriginData?) { touch(); if (o != null) { log.cb("onOringinFiveMinuteDataChange", o); out += o } }
                    override fun onOringinHalfHourDataChange(h: OriginHalfHourData?) { touch(); log.cb("onOringinHalfHourDataChange", "allStep=${h?.allStep}") }
                },
                day, startPos, watchDays,
            )
        }
        return out.toList()
    }

    // ------------------------------------------------------------------ sleep

    suspend fun readSleep(@Suppress("UNUSED_PARAMETER") days: Int): List<SleepSessionEntity> {
        val mac = core.connectedMac ?: throw WatchException("Watch not connected")
        val info = (core.state.value as? ConnectionState.Ready)?.info ?: throw WatchException("Watch not connected")
        val out = Collections.synchronizedList(mutableListOf<SleepData>())
        watched("readSleepData", inactivityMs = 20_000, hardCapMs = 90_000) { touch, done ->
            vp.readSleepData(
                core.writeLogger("readSleepData") { done.complete(Result.failure(WatchException("Couldn't ask for sleep data (code $it)"))) },
                object : ISleepDataListener {
                    override fun onSleepDataChange(day: String?, d: SleepData?) { touch(); log.cb("onSleepDataChange", "day=$day $d"); if (d != null) out += d }
                    override fun onSleepProgress(p: Float) { touch() }
                    override fun onSleepProgressDetail(day: String?, pkg: Int) { touch() }
                    override fun onReadSleepComplete() { log.cb("onReadSleepComplete"); done.complete(Result.success(Unit)) }
                },
                storedDays(info),
            )
        }
        return out.toList().mapNotNull { s -> s.toEntity(mac) }
    }

    private fun SleepData.toEntity(mac: String): SleepSessionEntity? {
        val down = sleepDown ?: return null
        val up = sleepUp ?: return null
        val startMs = toMs(down) ?: return null
        val endMs = toMs(up) ?: return null
        if (endMs <= startMs) return null
        // sleepLine: one char per 5 minutes: '0' light, '1' deep, '2' awake. Expand to one char per minute.
        val line = sleepLine.orEmpty()
        val stages = buildString {
            line.forEach { c ->
                val s = when (c) { '1' -> 'D'; '2' -> 'W'; else -> 'L' }
                repeat(5) { append(s) }
            }
        }
        val awake = line.count { it == '2' } * 5
        return SleepSessionEntity(
            mac = mac, startMs = startMs, endMs = endMs,
            deepMinutes = deepSleepTime.coerceAtLeast(0), lightMinutes = lowSleepTime.coerceAtLeast(0),
            awakeMinutes = awake, wakeCount = wakeCount.coerceAtLeast(0),
            quality = sleepQulity.takeIf { it in 1..5 }, stages = stages,
        )
    }

    // ------------------------------------------------------------------ workouts

    /** Reads only the sport-mode slots whose checksum changed since last time. */
    suspend fun readWorkouts(save: suspend (List<WorkoutEntity>) -> Unit): Int {
        val mac = core.connectedMac ?: throw WatchException("Watch not connected")
        var crcs = IntArray(0)
        watched("readSportDataCrc", inactivityMs = 15_000, hardCapMs = 20_000) { _, done ->
            vp.readSportDataCrc(
                object : BleWriteResponse {
                    override fun onResponse(code: Int) {
                        log.cb("onResponse(readSportDataCrc)", "code=$code")
                        if (code != Code.REQUEST_SUCCESS) done.complete(Result.failure(WatchException("Couldn't ask for workouts (code $code)")))
                    }
                },
                object : ISportModelDataCrcListener {
                    override fun onSportCrcDataChange(c: IntArray?) { log.cb("onSportCrcDataChange", c?.joinToString()); crcs = c ?: IntArray(0); done.complete(Result.success(Unit)) }
                    override fun onReadFail() { log.cb("onReadFail(sportCrc)"); done.complete(Result.failure(WatchException("Watch couldn't list workouts"))) }
                },
            )
        }
        var saved = 0
        crcs.forEachIndexed { slot, crc ->
            val known = prefs.getInt("sport_crc_$slot", Int.MIN_VALUE)
            if (crc == 0 || crc == known) return@forEachIndexed
            val (head, items) = readSportSlot(slot)
            val w = head?.toEntity(mac, items)
            if (w != null) { save(listOf(w)); saved++ }
            prefs.edit().putInt("sport_crc_$slot", crc).commit() // only after the workout is stored
        }
        return saved
    }

    private suspend fun readSportSlot(slot: Int): Pair<SportModelOriginHeadData?, List<SportModelOriginItemData>> {
        var head: SportModelOriginHeadData? = null
        val items = Collections.synchronizedList(mutableListOf<SportModelOriginItemData>())
        watched("readSportModelOrigin slot=$slot", inactivityMs = 20_000, hardCapMs = 90_000) { touch, done ->
            vp.readSportModelOrigin(
                core.writeLogger("readSportModelOrigin") { done.complete(Result.failure(WatchException("Couldn't read workout (code $it)"))) },
                object : ISportModelOriginListener {
                    override fun onReadOriginProgress(p: Float) { touch() }
                    override fun onReadOriginProgressDetail(d: Int, date: String?, all: Int, cur: Int) { touch() }
                    override fun onHeadChangeListListener(h: SportModelOriginHeadData?) { touch(); log.cb("onHeadChangeListListener", h); head = h }
                    override fun onGPSWatchSportModeHeadChange(h: SportModelGPSWatchOriginHeadData?) { touch(); log.cb("onGPSWatchSportModeHeadChange", h) }
                    override fun onItemChangeListListener(list: MutableList<SportModelOriginItemData>?) { touch(); log.cb("onItemChangeListListener", "n=${list?.size}"); list?.let { items += it } }
                    override fun onReadOriginComplete() { log.cb("onReadOriginComplete(sport)"); done.complete(Result.success(Unit)) }
                },
                slot,
            )
        }
        return head to items.toList()
    }

    private fun SportModelOriginHeadData.toEntity(mac: String, items: List<SportModelOriginItemData>): WorkoutEntity? {
        val st = startTime?.let { toMs(it) } ?: return null
        val en = stopTime?.let { toMs(it) } ?: (st + sportTime * 1000L)
        val rates = items.map { it.rate }.filter { it in 30..220 }
        return WorkoutEntity(
            mac = mac, startMs = st, endMs = maxOf(en, st + 1),
            sportType = sportType, sportName = sportName(sportType),
            steps = stepCount.takeIf { it > 0 },
            distanceM = (distance * 1000).toInt().takeIf { it > 0 },
            kcal = kcals.takeIf { it > 0 },
            avgBpm = averRate.takeIf { it in 30..220 } ?: rates.takeIf { it.isNotEmpty() }?.average()?.toInt(),
            maxBpm = rates.maxOrNull(),
        )
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Runs one SDK read under the command queue with an inactivity watchdog ([touch] resets it) and a hard cap.
     * [start] kicks off the SDK call, completes `done` when the read ends, and returns a getter for the result.
     */
    private suspend fun watched(
        name: String,
        inactivityMs: Long,
        hardCapMs: Long,
        start: (touch: () -> Unit, done: CompletableDeferred<Result<Unit>>) -> Unit,
    ): Unit = core.queue.run(name, hardCapMs + 10_000) {
        core.requireReady()
        log.app(name)
        val last = AtomicLong(System.currentTimeMillis())
        val done = CompletableDeferred<Result<Unit>>()
        start({ last.set(System.currentTimeMillis()) }, done)
        val deadline = System.currentTimeMillis() + hardCapMs
        while (true) {
            val r = withTimeoutOrNull(1_000) { done.await() }
            if (r != null) {
                r.getOrThrow()
                break
            }
            val now = System.currentTimeMillis()
            if (core.state.value !is ConnectionState.Ready) throw WatchException("Watch disconnected during $name")
            if (now - last.get() > inactivityMs || now > deadline) {
                log.app("watchdog", "$name: no data for ${(now - last.get()) / 1000}s")
                // The watch may still be streaming this read; give it a moment before the next command.
                delay(3_000)
                throw WatchException("The watch stopped answering ($name)")
            }
        }
    }

    private fun toMs(date: LocalDate, t: TimeData): Long? = runCatching {
        LocalDateTime.of(date.year, date.monthValue, date.dayOfMonth, t.hour, t.minute).atZone(Time.zone).toInstant().toEpochMilli()
    }.getOrNull()

    private fun toMs(t: TimeData): Long? = runCatching {
        LocalDateTime.of(t.year, t.month, t.day, t.hour, t.minute, t.second.coerceIn(0, 59)).atZone(Time.zone).toInstant().toEpochMilli()
    }.getOrNull()

    private fun pruneCursorKeys(olderThan: LocalDate) {
        val e = prefs.edit()
        prefs.all.keys.filter { it.startsWith("pos_") || it.startsWith("done_") }.forEach { k ->
            val d = runCatching { LocalDate.parse(k.substringAfter('_')) }.getOrNull()
            if (d != null && d < olderThan) e.remove(k)
        }
        e.apply()
    }

    companion object {
        /** Index = SDK sport type (ESportType order). */
        private val SPORT_NAMES = listOf(
            "Workout", "Outdoor run", "Outdoor walk", "Indoor run", "Indoor walk", "Hike", "Treadmill",
            "Outdoor cycling", "Indoor cycling", "Elliptical", "Rowing machine", "Mountaineering", "Swim",
            "Sit-ups", "Ski", "Jump rope", "Yoga", "Table tennis", "Basketball", "Volleyball", "Football",
            "Badminton", "Tennis", "Stair climbing",
        )

        fun sportName(type: Int): String = SPORT_NAMES.getOrElse(type) { "Workout ($type)" }
    }
}
