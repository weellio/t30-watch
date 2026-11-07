package com.t30watch.data.sync

import android.content.Context
import com.t30watch.ble.ConnectionState
import com.t30watch.ble.HistoryClient
import com.t30watch.ble.RawLog
import com.t30watch.ble.VeepooClient
import com.t30watch.ble.WatchException
import com.t30watch.ble.WatchInfo
import com.t30watch.data.WatchRepository
import com.t30watch.data.db.HistoryDao
import com.t30watch.data.settings.AppSettings
import com.t30watch.di.AppScope
import com.t30watch.health.HealthConnectExporter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil

data class SyncStatus(
    val running: Boolean = false,
    val step: String? = null,
    val lastSuccessAt: Long? = null,
    val lastError: String? = null,
    /** Short human summary of the last successful sync, e.g. "5 days · 1,440 slots · 288 HR · 2 nights". */
    val lastSummary: String? = null,
)

/**
 * Pulls history from the watch into Room:
 * - right after every connect (and pushes the profile first, if one is saved),
 * - every N minutes while connected (Settings → Auto-sync),
 * - when the user taps Sync.
 * The watch keeps only a few days (T30: 5), so each sync re-reads from the day of the last sync (or all
 * stored days the first time). Upserts make re-reading harmless.
 */
@Singleton
class SyncEngine @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val watch: WatchRepository,
    private val client: VeepooClient,
    private val history: HistoryClient,
    private val watchSettings: com.t30watch.ble.WatchSettingsClient,
    private val dao: HistoryDao,
    private val settings: AppSettings,
    private val healthConnect: HealthConnectExporter,
    private val log: RawLog,
    @param:AppScope private val scope: CoroutineScope,
) {
    private val prefs = context.getSharedPreferences("sync", Context.MODE_PRIVATE)
    private val _status = MutableStateFlow(
        SyncStatus(lastSuccessAt = prefs.getLong(KEY_LAST_SYNC, 0L).takeIf { it > 0 }, lastSummary = prefs.getString(KEY_SUMMARY, null)),
    )
    val status: StateFlow<SyncStatus> = _status.asStateFlow()
    private val mutex = Mutex()

    init {
        scope.launch { watch.readyEvents.collect { onConnected() } }
        scope.launch {
            settings.settings.map { it.autoSyncMinutes }.distinctUntilChanged().collectLatest { minutes ->
                if (minutes <= 0) return@collectLatest
                while (true) {
                    delay(minutes * 60_000L)
                    // Launched separately: changing the interval cancels this loop, but must never
                    // cancel a sync halfway through a read (the watch would keep streaming).
                    if (client.state.value is ConnectionState.Ready) scope.launch { sync(reason = "timer") }
                }
            }
        }
    }

    fun syncNow() {
        scope.launch { sync(reason = "manual") }
    }

    private suspend fun onConnected() {
        watchSettings.clearCache()
        watchSettings.listenForCamera()
        val s = settings.current()
        if (s.profile.saved) {
            runCatching { history.syncPersonInfo(s.profile) }.onFailure { log.error("syncPersonInfo", it) }
        }
        sync(reason = "connect")
    }

    /** Re-sends the profile now (after the user edits it). */
    fun pushProfile() {
        scope.launch {
            if (client.state.value !is ConnectionState.Ready) return@launch
            runCatching { history.syncPersonInfo(settings.current().profile) }.onFailure { log.error("syncPersonInfo", it) }
        }
    }

    private suspend fun sync(reason: String) {
        if (mutex.isLocked) return
        mutex.withLock {
            val ready = client.state.value as? ConnectionState.Ready ?: return
            val info = ready.info
            log.app("sync start", reason)
            _status.update { it.copy(running = true, step = "Starting", lastError = null) }
            try {
                val stored = history.storedDays(info).coerceAtLeast(1)
                val last = _status.value.lastSuccessAt
                val days = if (last == null) stored
                else (ceil((System.currentTimeMillis() - startOfDay(last)) / DAY.toDouble()).toInt()).coerceIn(1, stored)
                // Each step stands alone: one failing read doesn't stop the others.
                val errors = mutableListOf<String>()
                suspend fun <T> stepRun(name: String, block: suspend () -> T): T? = try {
                    step(name)
                    block()
                } catch (e: Exception) {
                    log.error("sync step $name", e)
                    errors += "$name: ${e.message ?: e.javaClass.simpleName}"
                    null
                }

                stepRun("Today's totals") { history.readTodayTotals()?.let { dao.upsertDayTotal(it) } }

                var heartN = 0
                var bpN = 0
                val slotN = stepRun("Activity, heart rate, blood pressure") {
                    history.readHistory(onProgress = { p -> step(p) }) { b ->
                        dao.upsertSlots(b.slots)
                        dao.upsertHeart(b.heart)
                        dao.upsertBp(b.bp)
                        heartN += b.heart.size
                        bpN += b.bp.size
                    }
                } ?: 0

                val nights = stepRun("Sleep") { history.readSleep(days).also { dao.upsertSleep(it) } }?.size ?: 0

                var workouts = 0
                if (info.supports("sportModelFunction") || info.supports("multSportMode")) {
                    workouts = stepRun("Workouts") { history.readWorkouts { dao.upsertWorkouts(it) } } ?: 0
                }

                val now = System.currentTimeMillis()
                val summary = "$slotN slots · $heartN HR · $bpN BP · $nights sleep" + if (workouts > 0) " · $workouts workouts" else ""
                if (errors.size >= 4) throw WatchException(errors.first())
                prefs.edit().putLong(KEY_LAST_SYNC, now).putString(KEY_SUMMARY, summary).apply()
                _status.update {
                    SyncStatus(running = false, lastSuccessAt = now, lastSummary = summary, lastError = errors.takeIf { e -> e.isNotEmpty() }?.joinToString("; "))
                }
                log.app("sync done", summary + if (errors.isNotEmpty()) " (errors: $errors)" else "")

                if (settings.current().healthConnectAutoExport && healthConnect.availability() == HealthConnectExporter.Availability.AVAILABLE) {
                    runCatching { healthConnect.export(now - (days + 1) * DAY, now, ready.mac) }
                        .onFailure { log.error("health connect auto-export", it) }
                }
            } catch (e: Exception) {
                log.error("sync failed", e)
                _status.update { it.copy(running = false, step = null, lastError = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    private fun step(text: String) = _status.update { it.copy(step = text) }

    private fun startOfDay(ms: Long): Long {
        val z = java.time.ZoneId.systemDefault()
        return java.time.Instant.ofEpochMilli(ms).atZone(z).toLocalDate().atStartOfDay(z).toInstant().toEpochMilli()
    }

    private companion object {
        const val DAY = 86_400_000L
        const val KEY_LAST_SYNC = "last_sync_ms"
        const val KEY_SUMMARY = "last_summary"
    }
}

/** Stored-days helper kept here so the rule is in one place. */
internal fun WatchInfo.storedDaysFromFeatures(): Int {
    val v = sections.firstOrNull { it.title == "Feature package 2" }?.fields?.firstOrNull { it.name == "watchDataDayNumber" }?.value?.toIntOrNull()
    return v ?: 3
}
