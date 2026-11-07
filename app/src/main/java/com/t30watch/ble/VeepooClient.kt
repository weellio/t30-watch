package com.t30watch.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import com.inuker.bluetooth.library.Code
import com.inuker.bluetooth.library.Constants
import com.inuker.bluetooth.library.log.VPLocalLogger
import com.inuker.bluetooth.library.model.BleGattProfile
import com.inuker.bluetooth.library.search.SearchResult
import com.inuker.bluetooth.library.search.response.SearchResponse
import com.veepoo.protocol.VPOperateManager
import com.veepoo.protocol.listener.base.IABleConnectStatusListener
import com.veepoo.protocol.listener.base.IABluetoothStateListener
import com.veepoo.protocol.listener.base.IBleWriteResponse
import com.veepoo.protocol.listener.base.IConnectResponse
import com.veepoo.protocol.listener.base.INotifyResponse
import com.veepoo.protocol.listener.data.IBPDetectDataListener
import com.veepoo.protocol.listener.data.IBatteryDataListener
import com.veepoo.protocol.listener.data.IHeartDataListener
import com.veepoo.protocol.listener.data.ICustomSettingDataListener
import com.veepoo.protocol.listener.data.IDeviceFuctionDataListener
import com.veepoo.protocol.listener.data.IPwdCheckTimeoutListener
import com.veepoo.protocol.listener.data.IPwdDataListener
import com.veepoo.protocol.listener.data.ISocialMsgDataListener
import com.veepoo.protocol.model.datas.BatteryData
import com.veepoo.protocol.model.datas.BpData
import com.veepoo.protocol.model.datas.HeartData
import com.veepoo.protocol.model.enums.EBPDetectModel
import com.veepoo.protocol.model.enums.EBPDetectStatus
import com.veepoo.protocol.model.enums.EHeartStatus
import com.veepoo.protocol.model.datas.DeviceFunctionPackage1
import com.veepoo.protocol.model.datas.DeviceFunctionPackage2
import com.veepoo.protocol.model.datas.DeviceFunctionPackage3
import com.veepoo.protocol.model.datas.DeviceFunctionPackage4
import com.veepoo.protocol.model.datas.DeviceFunctionPackage5
import com.veepoo.protocol.model.datas.FunctionDeviceSupportData
import com.veepoo.protocol.model.datas.FunctionSocailMsgData
import com.veepoo.protocol.model.datas.PwdData
import com.veepoo.protocol.model.enums.EPwdStatus
import com.veepoo.protocol.model.settings.CustomSettingData
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The only class that talks to VPOperateManager.
 *
 * Every SDK callback is wrapped in a suspend function or a Flow, every callback is logged to [RawLog],
 * and every watch command goes through [CommandQueue] so nothing runs in parallel.
 * Callers must hold the Bluetooth runtime permissions before calling [scan] or [connect].
 */
@SuppressLint("MissingPermission")
@Singleton
class VeepooClient @Inject constructor(
    @param:ApplicationContext private val context: Context,
    internal val log: RawLog,
) {
    internal val vp: VPOperateManager get() = VPOperateManager.getInstance()
    internal val queue = CommandQueue(log)
    private var initialized = false
    private var sdkLoggerRunning = false

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected())
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _bluetoothOn = MutableStateFlow(true)
    val bluetoothOn: StateFlow<Boolean> = _bluetoothOn.asStateFlow()

    private var statusListenerMac: String? = null

    private val bluetoothStateListener = object : IABluetoothStateListener() {
        override fun onBluetoothStateChanged(open: Boolean) {
            log.cb("onBluetoothStateChanged", "open=$open")
            _bluetoothOn.value = open
            if (!open && _state.value !is ConnectionState.Disconnected) {
                _state.value = ConnectionState.Disconnected("Phone Bluetooth was turned off")
            }
        }
    }

    private val connectStatusListener = object : IABleConnectStatusListener() {
        override fun onConnectStatusChanged(mac: String?, status: Int) {
            val text = when (status) {
                Constants.STATUS_CONNECTED -> "CONNECTED"
                Constants.STATUS_DISCONNECTED -> "DISCONNECTED"
                else -> status.toString()
            }
            log.cb("onConnectStatusChanged", "mac=$mac status=$text")
            if (status == Constants.STATUS_DISCONNECTED && _state.value is ConnectionState.Ready) {
                _state.value = ConnectionState.Disconnected("Watch disconnected")
            }
        }
    }

    /** SDK one-time setup. Safe to call repeatedly. Must run on the main thread. */
    fun ensureInit(sdkLogging: Boolean) {
        if (!initialized) {
            log.app("SDK init")
            vp.init(context.applicationContext)
            vp.setAutoConnectBTBySdk(false)
            // Unsupported calls would otherwise pop a Toast; we report failures ourselves.
            VPOperateManager.setShowFunctionNotSupportToast(false)
            vp.registerBluetoothStateListener(bluetoothStateListener)
            vp.setPwdCheckTimeoutListener(object : IPwdCheckTimeoutListener {
                override fun onPwdCheckTimeout() = log.cb("onPwdCheckTimeout")
            })
            _bluetoothOn.value = vp.isBluetoothOpened
            initialized = true
        }
        setSdkLogging(sdkLogging)
    }

    fun setSdkLogging(on: Boolean) {
        if (on && !sdkLoggerRunning) {
            VPLocalLogger.startMonitor(context.applicationContext)
            sdkLoggerRunning = true
            log.app("SDK logger started", VPLocalLogger.getInstance().logDirPath)
        } else if (!on && sdkLoggerRunning) {
            VPLocalLogger.stopMonitor()
            sdkLoggerRunning = false
            log.app("SDK logger stopped")
        }
    }

    fun sdkLogDir(): String? = runCatching { VPLocalLogger.getInstance().logDirPath }.getOrNull()

    // ---------------------------------------------------------------- scan

    /** Scans for [seconds]; completes when the SDK says the scan stopped. Cancel the collector to stop early. */
    fun scan(seconds: Int): Flow<ScannedDevice> = callbackFlow {
        val response = object : SearchResponse {
            override fun onSearchStarted() = log.cb("onSearchStarted")
            override fun onDeviceFounded(result: SearchResult?) {
                if (result == null) return
                log.cb("onDeviceFounded", "${result.name} ${result.address} rssi=${result.rssi}")
                trySend(ScannedDevice(result.address, result.name, result.rssi))
            }
            override fun onSearchStopped() {
                log.cb("onSearchStopped")
                close()
            }
            override fun onSearchCanceled() {
                log.cb("onSearchCanceled")
                close()
            }
        }
        log.app("startScanDevice", "${seconds}s")
        try {
            vp.startScanDevice(seconds, response)
        } catch (e: SecurityException) {
            log.error("startScanDevice", e)
            close(WatchException("Bluetooth permission missing"))
        }
        awaitClose {
            log.app("stopScanDevice")
            runCatching { vp.stopScanDevice() }
        }
    }.flowOn(Dispatchers.Main)

    // ---------------------------------------------------------------- connect

    /**
     * Full connect: BLE link → notifications on → password check (which also reports features and sets
     * the watch clock) → battery. Updates [state] as it goes. Throws on failure, leaving [state] Disconnected.
     */
    suspend fun connect(mac: String, name: String?, password: String, use24Hour: Boolean, confirmOnWatch: Boolean): WatchInfo {
        try {
            _state.value = ConnectionState.Connecting(mac, name, "Connecting")
            // If Android already has a GATT link to the watch before we start, another app on the phone holds it
            // (seen: nRF Connect's background service re-attaches whenever we connect, and keeps the link after
            // our process dies). The watch then stops advertising and the SDK's connect hangs.
            val heldByOtherApp = systemGattConnected(mac)
            val oadMode = try {
                linkUp(mac, name)
            } catch (first: CommandTimeoutException) {
                if (heldByOtherApp && systemGattConnected(mac)) {
                    throw WatchException(
                        "Another app on this phone is connected to the watch (for example nRF Connect or H Band). " +
                            "Close or force-stop it, then try again.",
                    )
                }
                log.app("connect retry", "after timeout; ${systemLinkState(mac)}")
                runCatching { vp.disconnectWatch(writeLogger("disconnectWatch")) }
                delay(2_000)
                _state.value = ConnectionState.Connecting(mac, name, "Connecting (2nd try)")
                try {
                    linkUp(mac, name)
                } catch (second: CommandTimeoutException) {
                    throw WatchException(
                        "The watch didn't answer. Check it's nearby and awake. If the app was just updated or " +
                            "restarted, wait a minute, or turn phone Bluetooth off and on, then try again.",
                    )
                }
            }
            if (oadMode) {
                log.app("watch is in firmware-update (OAD) mode")
            }
            _state.value = ConnectionState.Connecting(mac, name, "Checking password")
            val info = verifyPassword(password, use24Hour, confirmOnWatch, oadMode)
            _state.value = ConnectionState.Ready(mac, name, info)
            return info
        } catch (e: Exception) {
            log.error("connect failed", e)
            runCatching { vp.disconnectWatch(writeLogger("disconnectWatch")) }
            dropStatusListener()
            _state.value = ConnectionState.Disconnected(e.message ?: e.javaClass.simpleName)
            throw e
        }
    }

    /** Returns isOadModel (true = watch stuck in firmware-update mode). */
    private suspend fun linkUp(mac: String, name: String?): Boolean = queue.run("connectDevice", 15_000) {
        log.app("before connect", systemLinkState(mac))
        vp.stopScanDevice()
        dropStatusListener()
        vp.registerConnectStatusListener(mac, connectStatusListener)
        statusListenerMac = mac
        suspendCancellableCoroutine { cont ->
            val once = Once(cont)
            var oad = false
            val connectResponse = object : IConnectResponse {
                override fun connectState(code: Int, profile: BleGattProfile?, isOadModel: Boolean) {
                    log.cb("connectState", "code=$code isOadModel=$isOadModel services=${profile?.services?.size}")
                    oad = isOadModel
                    if (code != Code.REQUEST_SUCCESS) once.fail(WatchException("Couldn't connect (code $code). Is the watch nearby and H Band closed?"))
                }
            }
            val notifyResponse = object : INotifyResponse {
                override fun notifyState(state: Int) {
                    log.cb("notifyState", "state=$state")
                    if (state == Code.REQUEST_SUCCESS) once.ok(oad)
                    else once.fail(WatchException("Connected, but the watch didn't open its data channel (code $state)"))
                }
            }
            log.app("connectDevice", "$mac ${name.orEmpty()}")
            if (name.isNullOrEmpty()) vp.connectDevice(mac, connectResponse, notifyResponse)
            else vp.connectDevice(mac, name, connectResponse, notifyResponse)
            cont.invokeOnCancellation { runCatching { vp.disconnectWatch(writeLogger("disconnectWatch")) } }
        }
    }

    private suspend fun verifyPassword(password: String, use24Hour: Boolean, confirmOnWatch: Boolean, oadMode: Boolean): WatchInfo =
        queue.run("confirmDevicePwd", 45_000) {
            val got = PasswordReplies()
            // "Done" = we have both the password reply and the settings packet. The order isn't fixed:
            // on the T30 (fw 03.01.01) the password reply arrives LAST, ~30 ms after the settings packet,
            // even though the demo treats the settings packet as the end.
            // If the settings packet never comes but the password was accepted, carry on with what we have.
            val complete = withTimeoutOrNull(30_000) {
                suspendCancellableCoroutine { cont ->
                    val once = Once(cont)
                    fun doneIfComplete() { if (got.pwd != null && got.custom != null) once.ok(Unit) }
                    vp.setDeviceShowConfirm(confirmOnWatch)
                    log.app("confirmDevicePwd", "pwd=${"*".repeat(password.length)} is24h=$use24Hour confirmOnWatch=$confirmOnWatch")
                    vp.confirmDevicePwd(
                        writeLogger("confirmDevicePwd") { once.fail(WatchException("Couldn't send the password command (code $it)")) },
                        object : IPwdDataListener {
                            override fun onPwdDataChange(data: PwdData?) {
                                log.cb("onPwdDataChange", data)
                                got.pwd = data
                                if (data?.getmStatus() == EPwdStatus.CHECK_FAIL) {
                                    once.fail(WatchException("Watch rejected the password. Change it on the Debug screen."))
                                } else {
                                    doneIfComplete()
                                }
                            }
                            override fun onConnectionConfirmTimeout() {
                                log.cb("onConnectionConfirmTimeout")
                                once.fail(WatchException("Nobody confirmed the connection on the watch"))
                            }
                        },
                        object : IDeviceFuctionDataListener {
                            @Deprecated("SDK marks this deprecated; kept for the merged dump")
                            override fun onFunctionSupportDataChange(d: FunctionDeviceSupportData?) { log.cb("onFunctionSupportDataChange", d); got.merged = d }
                            override fun onDeviceFunctionPackage1Report(p: DeviceFunctionPackage1?) { log.cb("onDeviceFunctionPackage1Report", p); got.p1 = p }
                            override fun onDeviceFunctionPackage2Report(p: DeviceFunctionPackage2?) { log.cb("onDeviceFunctionPackage2Report", p); got.p2 = p }
                            override fun onDeviceFunctionPackage3Report(p: DeviceFunctionPackage3?) { log.cb("onDeviceFunctionPackage3Report", p); got.p3 = p }
                            override fun onDeviceFunctionPackage4Report(p: DeviceFunctionPackage4?) { log.cb("onDeviceFunctionPackage4Report", p); got.p4 = p }
                            override fun onDeviceFunctionPackage5Report(p: DeviceFunctionPackage5?) { log.cb("onDeviceFunctionPackage5Report", p); got.p5 = p }
                        },
                        object : ISocialMsgDataListener {
                            override fun onSocialMsgSupportDataChange(d: FunctionSocailMsgData?) { log.cb("onSocialMsgSupportDataChange", d); got.social1 = d }
                            override fun onSocialMsgSupportDataChange2(d: FunctionSocailMsgData?) { log.cb("onSocialMsgSupportDataChange2", d); got.social2 = d }
                        },
                        object : ICustomSettingDataListener {
                            override fun OnSettingDataChange(d: CustomSettingData?) {
                                log.cb("OnSettingDataChange", d)
                                got.custom = d
                                doneIfComplete()
                            }
                        },
                        password,
                        use24Hour,
                    )
                }
            }
            val pwd = got.pwd ?: throw WatchException("Watch never answered the password check")
            if (complete == null) log.app("settings packet never arrived; using partial data")
            got.toInfo(pwd, partial = complete == null, oadMode = oadMode)
        }

    private fun PasswordReplies.toInfo(pwd: PwdData, partial: Boolean, oadMode: Boolean): WatchInfo {
        @Suppress("DEPRECATION") // only way to read the chip family; package 3 also has the raw cpuType int
        val cpu = runCatching { vp.cpuType?.name ?: "null" }.getOrElse { "error: ${it.message}" }
        val jl = runCatching { vp.isJLDevice }.getOrDefault(false)
        log.app("chip", "cpuType=$cpu isJLDevice=$jl")
        val sections = listOf(
            SdkDump.section("Password reply (PwdData)", pwd),
            SdkDump.section("Feature package 1", p1),
            SdkDump.section("Feature package 2", p2),
            SdkDump.section("Feature package 3", p3),
            SdkDump.section("Feature package 4", p4),
            SdkDump.section("Feature package 5", p5),
            SdkDump.section("All features merged (FunctionDeviceSupportData)", merged),
            SdkDump.section("Notification apps, packet 1", social1),
            SdkDump.section("Notification apps, packet 2", social2),
            SdkDump.section("Watch settings (CustomSettingData)", custom),
        )
        return WatchInfo(
            deviceNumber = pwd.deviceNumber,
            firmwareVersion = pwd.deviceVersion.orEmpty(),
            testVersion = pwd.deviceTestVersion.orEmpty(),
            passwordStatus = pwd.getmStatus()?.name ?: "null",
            inFirmwareUpdateMode = oadMode,
            cpuType = cpu,
            isJieLi = jl,
            partial = partial,
            sections = sections,
        )
    }

    // ---------------------------------------------------------------- commands

    suspend fun readBattery(): BatteryInfo = queue.run("readBattery", 8_000) {
        requireReady()
        suspendCancellableCoroutine { cont ->
            val once = Once(cont)
            log.app("readBattery")
            vp.readBattery(
                writeLogger("readBattery") { once.fail(WatchException("Couldn't send the battery command (code $it)")) },
                object : IBatteryDataListener {
                    override fun onDataChange(b: BatteryData?) {
                        log.cb("onDataChange(battery)", b)
                        if (b == null) return
                        once.ok(
                            BatteryInfo(
                                percent = if (b.isPercent) b.batteryPercent else (b.batteryLevel * 25).coerceIn(0, 100),
                                isExactPercent = b.isPercent,
                                charging = b.powerModel == 1,
                                low = b.isLowBattery,
                                raw = b.toString(),
                            ),
                        )
                    }
                },
            )
        }
    }

    // ---------------------------------------------------------------- live measurements

    /**
     * Live heart rate. The watch keeps measuring until told to stop, so this holds the command queue for
     * the whole session. Ends when the collector is cancelled (user pressed Stop / left the screen),
     * after [maxSeconds], or on an error/disconnect. The stop command is always sent on the way out.
     */
    fun liveHeartRate(maxSeconds: Int): Flow<HeartSample> = channelFlow {
        val end = CompletableDeferred<Result<Unit>>()
        val watcher = launch { _state.first { it !is ConnectionState.Ready }; end.complete(Result.failure(WatchException("Watch disconnected"))) }
        try {
            queue.run("heartRate session", (maxSeconds + 15) * 1000L) {
                requireReady()
                log.app("startDetectHeart")
                vp.startDetectHeart(
                    writeLogger("startDetectHeart") { end.complete(Result.failure(WatchException("Couldn't start heart rate (code $it)"))) },
                    object : IHeartDataListener {
                        override fun onDataChange(d: HeartData?) {
                            log.cb("onDataChange(heart)", d)
                            if (d == null) return
                            val status = d.heartStatus.toApp()
                            val bpm = d.data.takeIf { status == HeartStatus.OK && it in 20..300 }
                            trySend(HeartSample(System.currentTimeMillis(), bpm, status))
                            if (status == HeartStatus.BUSY || status == HeartStatus.LOW_BATTERY) {
                                end.complete(Result.failure(WatchException(status.label)))
                            }
                        }
                    },
                )
                try {
                    val result = withTimeoutOrNull(maxSeconds * 1000L) { end.await() }
                    if (result == null) log.app("heart rate session reached its ${maxSeconds}s limit")
                    result?.getOrThrow()
                } finally {
                    withContext(NonCancellable) {
                        if (_state.value is ConnectionState.Ready) sendAndAwaitWrite("stopDetectHeart") { vp.stopDetectHeart(it) }
                    }
                }
            }
        } finally {
            watcher.cancel()
        }
    }

    /**
     * One manual blood-pressure measurement (general mode). Emits progress; completes when the watch reports
     * 100 % (or, on watches without progress, the first result ~55 s in). Stop is always sent on the way out.
     */
    fun measureBloodPressure(): Flow<BpReading> = channelFlow {
        val end = CompletableDeferred<Result<Unit>>()
        val watcher = launch { _state.first { it !is ConnectionState.Ready }; end.complete(Result.failure(WatchException("Watch disconnected"))) }
        val mode = EBPDetectModel.DETECT_MODEL_PUBLIC
        try {
            queue.run("bloodPressure session", 150_000) {
                requireReady()
                log.app("startDetectBP", mode)
                vp.startDetectBP(
                    writeLogger("startDetectBP") { end.complete(Result.failure(WatchException("Couldn't start blood pressure (code $it)"))) },
                    object : IBPDetectDataListener {
                        override fun onDataChange(d: BpData?) {
                            log.cb("onDataChange(bp)", d)
                            if (d == null) return
                            val reading = BpReading(
                                progress = d.progress.takeIf { d.isHaveProgress },
                                systolic = d.highPressure,
                                diastolic = d.lowPressure,
                                status = d.status.toApp(),
                            )
                            trySend(reading)
                            when {
                                reading.status != BpStatus.OK -> end.complete(Result.failure(WatchException(reading.status.label)))
                                d.isHaveProgress && d.progress >= 100 -> end.complete(Result.success(Unit))
                                !d.isHaveProgress && d.highPressure > 0 -> end.complete(Result.success(Unit))
                            }
                        }
                    },
                    mode,
                )
                try {
                    val result = withTimeoutOrNull(120_000) { end.await() }
                        ?: throw WatchException("No blood pressure result after 2 minutes")
                    result.getOrThrow()
                } finally {
                    withContext(NonCancellable) {
                        if (_state.value is ConnectionState.Ready) sendAndAwaitWrite("stopDetectBP") { vp.stopDetectBP(it, mode) }
                    }
                }
            }
        } finally {
            watcher.cancel()
        }
    }

    private fun EHeartStatus?.toApp(): HeartStatus = when (this) {
        EHeartStatus.STATE_INIT -> HeartStatus.STARTING
        EHeartStatus.STATE_HEART_BUSY -> HeartStatus.BUSY
        EHeartStatus.STATE_HEART_DETECT -> HeartStatus.MEASURING
        EHeartStatus.STATE_HEART_WEAR_ERROR -> HeartStatus.NOT_WORN
        EHeartStatus.STATE_LOW_BATTERY -> HeartStatus.LOW_BATTERY
        EHeartStatus.STATE_HEART_NORMAL -> HeartStatus.OK
        null -> HeartStatus.MEASURING
    }

    private fun EBPDetectStatus?.toApp(): BpStatus = when (this) {
        EBPDetectStatus.STATE_BP_BUSY -> BpStatus.BUSY
        EBPDetectStatus.STATE_BP_LOW_BATTERY -> BpStatus.LOW_BATTERY
        EBPDetectStatus.STATE_BP_CHARGING -> BpStatus.CHARGING
        EBPDetectStatus.STATE_BP_WEAR_OFF -> BpStatus.NOT_WORN
        EBPDetectStatus.STATE_BP_NORMAL, null -> BpStatus.OK
    }

    /** Sends a fire-and-forget command and waits (max 3 s) for the write to be acknowledged. */
    internal suspend fun sendAndAwaitWrite(name: String, call: (IBleWriteResponse) -> Unit) {
        val ack = CompletableDeferred<Int>()
        log.app(name)
        runCatching {
            call(object : IBleWriteResponse {
                override fun onResponse(code: Int) {
                    log.cb("onResponse($name)", "code=$code")
                    ack.complete(code)
                }
            })
        }.onFailure { log.error(name, it) }
        if (withTimeoutOrNull(3_000) { ack.await() } == null) log.app("$name: no write ack within 3 s")
    }

    /**
     * The common shape of a watch command: take the queue, require a connection, call the SDK, and wait for
     * the one callback that answers it. [call] gets a [Once] to resolve; extra callbacks are ignored.
     */
    internal suspend fun <T> command(name: String, timeoutMs: Long = 8_000, call: (Once<T>) -> Unit): T =
        queue.run(name, timeoutMs) {
            requireReady()
            log.app(name)
            suspendCancellableCoroutine { cont -> call(Once(cont)) }
        }

    /** The MAC of the connected watch, or null. */
    val connectedMac: String? get() = (_state.value as? ConnectionState.Ready)?.mac

    /** Not queued: disconnect must be able to cut in while a command is waiting. */
    fun disconnect() {
        log.app("disconnectWatch (user)")
        runCatching { vp.disconnectWatch(writeLogger("disconnectWatch")) }
        dropStatusListener()
        _state.value = ConnectionState.Disconnected()
    }

    // ---------------------------------------------------------------- helpers

    private fun systemGattConnected(mac: String): Boolean = runCatching {
        val bm = context.getSystemService(BluetoothManager::class.java)
        bm.getConnectionState(bm.adapter.getRemoteDevice(mac), BluetoothProfile.GATT) == BluetoothProfile.STATE_CONNECTED
    }.getOrDefault(false)

    /** Android's own view of the link, for the log: tells a stale connection apart from an absent watch. */
    private fun systemLinkState(mac: String): String = runCatching {
        val bm = context.getSystemService(BluetoothManager::class.java)
        val device = bm.adapter.getRemoteDevice(mac)
        val gatt = when (bm.getConnectionState(device, BluetoothProfile.GATT)) {
            BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
            BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
            BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
            else -> "DISCONNECTED"
        }
        val others = bm.getConnectedDevices(BluetoothProfile.GATT).joinToString { it.address }
        "android GATT=$gatt, sdk isDeviceConnected=${vp.isDeviceConnected(mac)}, all GATT links=[$others]"
    }.getOrElse { "link state unavailable: ${it.message}" }

    internal fun requireReady() {
        if (_state.value !is ConnectionState.Ready) throw WatchException("Watch not connected")
    }

    private fun dropStatusListener() {
        statusListenerMac?.let { mac -> runCatching { vp.unregisterConnectStatusListener(mac, connectStatusListener) } }
        statusListenerMac = null
    }

    /** IBleWriteResponse that logs, and reports a failed write to [onFail]. Success only means "written". */
    internal fun writeLogger(command: String, onFail: (Int) -> Unit = {}) = object : IBleWriteResponse {
        override fun onResponse(code: Int) {
            log.cb("onResponse($command)", "code=$code")
            if (code != Code.REQUEST_SUCCESS) onFail(code)
        }
    }

    /** Guards a continuation: SDK callbacks can fire more than once, the coroutine may resume only once. */
    internal class Once<T>(private val cont: CancellableContinuation<T>) {
        private val done = AtomicBoolean(false)
        fun ok(value: T) { if (done.compareAndSet(false, true) && cont.isActive) cont.resume(value) }
        fun fail(e: Throwable) { if (done.compareAndSet(false, true) && cont.isActive) cont.resumeWithException(e) }
    }

    private class PasswordReplies {
        var pwd: PwdData? = null
        var p1: DeviceFunctionPackage1? = null
        var p2: DeviceFunctionPackage2? = null
        var p3: DeviceFunctionPackage3? = null
        var p4: DeviceFunctionPackage4? = null
        var p5: DeviceFunctionPackage5? = null
        var merged: FunctionDeviceSupportData? = null
        var social1: FunctionSocailMsgData? = null
        var social2: FunctionSocailMsgData? = null
        var custom: CustomSettingData? = null
    }
}
