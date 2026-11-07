package com.t30watch.data

import android.content.Context
import com.t30watch.ble.BatteryInfo
import com.t30watch.ble.ConnectionState
import com.t30watch.ble.RawLog
import com.t30watch.ble.ScannedDevice
import com.t30watch.ble.SdkDump
import com.t30watch.ble.VeepooClient
import com.t30watch.data.db.KnownDeviceDao
import com.t30watch.data.db.KnownDeviceEntity
import com.t30watch.data.settings.AppSettings
import com.t30watch.di.AppScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import com.t30watch.util.BlePermissions
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the UI talks to. Owns long-running work (scan, connect) in the app scope, so leaving a screen
 * doesn't cancel a connection half-way.
 */
@Singleton
class WatchRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val client: VeepooClient,
    private val settings: AppSettings,
    private val knownDevices: KnownDeviceDao,
    private val log: RawLog,
    @param:AppScope private val scope: CoroutineScope,
) {
    /** Demo build: always "connected" to a pretend watch; nothing touches Bluetooth. */
    val connection: StateFlow<ConnectionState> =
        if (com.t30watch.BuildConfig.DEMO) MutableStateFlow(com.t30watch.demo.Demo.connection) else client.state
    val bluetoothOn: StateFlow<Boolean> = client.bluetoothOn
    val lastWatch: Flow<KnownDeviceEntity?> = knownDevices.observeAll().map { it.firstOrNull() }

    private val _scanResults = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val scanResults: StateFlow<List<ScannedDevice>> = _scanResults.asStateFlow()
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()
    private val _scanError = MutableStateFlow<String?>(null)
    val scanError: StateFlow<String?> = _scanError.asStateFlow()

    private val _battery = MutableStateFlow<BatteryInfo?>(if (com.t30watch.BuildConfig.DEMO) com.t30watch.demo.Demo.battery else null)
    val battery: StateFlow<BatteryInfo?> = _battery.asStateFlow()
    private val _batteryError = MutableStateFlow<String?>(null)
    val batteryError: StateFlow<String?> = _batteryError.asStateFlow()

    private var scanJob: Job? = null
    private var connectJob: Job? = null
    private var reconnectJob: Job? = null
    @Volatile private var sdkReady = false

    /** True from the moment the user (or the service) asks to connect until the user taps Disconnect. */
    @Volatile private var wantConnected = false
    /** Failed auto-reconnect attempts since the last good connection (drives the backoff). */
    @Volatile private var reconnectAttempts = 0
    private var lastTarget: Pair<String, String?>? = null

    /** Emits the MAC each time a connection becomes fully ready (password done, battery read). Sync listens. */
    private val _readyEvents = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val readyEvents: SharedFlow<String> = _readyEvents.asSharedFlow()

    init {
        // Apply the SDK-logger switch live once the SDK is up.
        scope.launch {
            settings.settings.drop(1).collect { s -> if (sdkReady) client.setSdkLogging(s.sdkLogging) }
        }
        // Auto-reconnect: when a connection we wanted drops, try again with growing gaps.
        scope.launch {
            connection.collect { st ->
                when (st) {
                    is ConnectionState.Ready -> { reconnectJob?.cancel(); reconnectJob = null }
                    is ConnectionState.Disconnected -> when {
                        !wantConnected || st.reason == null -> Unit
                        // A wrong password won't fix itself; wait for the user.
                        st.reason.startsWith("Watch rejected the password") -> { wantConnected = false; log.app("auto-reconnect stopped", st.reason) }
                        else -> scheduleReconnect()
                    }
                    is ConnectionState.Connecting -> Unit
                }
            }
        }
        // Phone Bluetooth turned back on (e.g. after a reboot) → try straight away.
        scope.launch {
            bluetoothOn.collect { on ->
                if (on && wantConnected && connection.value is ConnectionState.Disconnected) {
                    reconnectJob?.cancel()
                    scheduleReconnect()
                }
            }
        }
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        val target = lastTarget ?: return
        reconnectJob = scope.launch {
            while (wantConnected && connection.value !is ConnectionState.Ready) {
                if (!settings.current().keepConnected) return@launch
                val wait = RECONNECT_BACKOFF_S.getOrElse(reconnectAttempts) { RECONNECT_BACKOFF_S.last() }
                log.app("auto-reconnect", "attempt ${reconnectAttempts + 1} in ${wait}s")
                delay(wait * 1000L)
                if (!wantConnected || connection.value is ConnectionState.Ready) break
                reconnectAttempts++
                if (!BlePermissions.granted(context) || !BlePermissions.bluetoothEnabled(context)) continue
                // Wait for this attempt to finish before deciding on the next one.
                connectNow(target.first, target.second)?.join()
            }
        }
    }

    /**
     * Used by the background service at start-up: connect to the last watch if we aren't connected.
     * If Bluetooth isn't ready yet (just after a reboot), the reconnect loop keeps trying.
     */
    suspend fun autoConnectLast() {
        if (com.t30watch.BuildConfig.DEMO) return
        if (connection.value !is ConnectionState.Disconnected) return
        val last = knownDevices.observeAll().first().firstOrNull() ?: return
        log.app("auto-connect", last.mac)
        if (BlePermissions.granted(context) && BlePermissions.bluetoothEnabled(context)) {
            connect(last.mac, last.name)
        } else {
            wantConnected = true
            lastTarget = last.mac to last.name
            scheduleReconnect()
        }
    }

    private suspend fun initSdk() {
        client.ensureInit(settings.current().sdkLogging)
        sdkReady = true
    }

    /** Call only after the Bluetooth permissions are granted. */
    fun startScan(seconds: Int = 12) {
        if (scanJob?.isActive == true) return
        _scanResults.value = emptyList()
        _scanError.value = null
        scanJob = scope.launch {
            _scanning.value = true
            try {
                initSdk()
                client.scan(seconds).collect { found ->
                    _scanResults.update { list ->
                        (list.filterNot { it.mac == found.mac } + found)
                            .sortedWith(compareByDescending<ScannedDevice> { it.name?.contains("T30", true) == true }.thenByDescending { it.rssi })
                    }
                }
            } catch (e: Exception) {
                _scanError.value = e.message
            } finally {
                _scanning.value = false
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
    }

    /** Call only after the Bluetooth permissions are granted. */
    fun connect(mac: String, name: String?) {
        wantConnected = true
        lastTarget = mac to name
        reconnectAttempts = 0
        reconnectJob?.cancel()
        connectNow(mac, name)
    }

    private fun connectNow(mac: String, name: String?): Job? {
        if (connectJob?.isActive == true) return connectJob
        stopScan()
        connectJob = scope.launch {
            try {
                initSdk()
                val s = settings.current()
                val info = client.connect(mac, name, s.password, s.use24Hour, s.confirmOnWatch)
                knownDevices.upsert(KnownDeviceEntity(mac, name ?: "Watch", System.currentTimeMillis()))
                writeSupportDump(
                    mac, name,
                    "firmware: ${info.firmwareVersion} (test ${info.testVersion}), model number ${info.deviceNumber}\n" +
                        "chip: cpuType=${info.cpuType}, JieLi=${info.isJieLi}, firmware-update mode=${info.inFirmwareUpdateMode}",
                    SdkDump.toText(info.sections),
                )
                refreshBatteryNow()
                reconnectAttempts = 0
                _readyEvents.tryEmit(mac)
            } catch (e: Exception) {
                // client already moved state to Disconnected(reason)
            }
        }
        return connectJob
    }

    fun disconnect() {
        wantConnected = false
        reconnectJob?.cancel()
        connectJob?.cancel()
        client.disconnect()
        _battery.value = null
    }

    fun refreshBattery() {
        scope.launch { refreshBatteryNow() }
    }

    private suspend fun refreshBatteryNow() {
        try {
            _battery.value = client.readBattery()
            _batteryError.value = null
        } catch (e: Exception) {
            log.error("readBattery", e)
            _batteryError.value = e.message
        }
    }

    /** Latest feature dump as a file next to the logs, so it can be pulled or shared. */
    val supportDumpFile: File get() = File(log.logDir.parentFile, "support-dump.txt")

    private suspend fun writeSupportDump(mac: String, name: String?, details: String, body: String) = withContext(Dispatchers.IO) {
        val header = "T30 Watch feature dump\nwatch: ${name.orEmpty()} $mac\n$details\nwritten: ${java.util.Date()}\n\n"
        runCatching { supportDumpFile.writeText(header + body) }
            .onSuccess { log.app("support dump written", supportDumpFile.absolutePath) }
            .onFailure { log.error("support dump write", it) }
    }

    fun sdkLogFiles(): List<File> {
        val dir = client.sdkLogDir()?.let(::File) ?: return emptyList()
        return dir.walkTopDown().filter { it.isFile }.toList()
    }

    private companion object {
        /** Seconds to wait before each reconnect attempt; the last value repeats. */
        val RECONNECT_BACKOFF_S = listOf(5, 15, 30, 60, 120, 300)
    }
}
