package com.t30watch.ble

import com.inuker.bluetooth.library.Code
import com.veepoo.protocol.listener.base.IBleWriteResponse
import com.veepoo.protocol.listener.data.IAlarm2DataListListener
import com.veepoo.protocol.listener.data.ICameraDataListener
import com.veepoo.protocol.listener.data.ICustomSettingDataListener
import com.veepoo.protocol.listener.data.IHeartWaringDataListener
import com.veepoo.protocol.listener.data.ILanguageDataListener
import com.veepoo.protocol.listener.data.ILongSeatDataListener
import com.veepoo.protocol.listener.data.INightTurnWristeDataListener
import com.veepoo.protocol.listener.data.IScreenLightTimeListener
import com.veepoo.protocol.listener.data.ISocialMsgDataListener
import com.veepoo.protocol.model.datas.AlarmData2
import com.veepoo.protocol.model.datas.FunctionSocailMsgData
import com.veepoo.protocol.model.datas.HeartWaringData
import com.veepoo.protocol.model.datas.LanguageData
import com.veepoo.protocol.model.datas.LongSeatData
import com.veepoo.protocol.model.datas.NightTurnWristeData
import com.veepoo.protocol.model.datas.ScreenLightTimeData
import com.veepoo.protocol.model.datas.TimeData
import com.veepoo.protocol.model.enums.ECameraStatus
import com.veepoo.protocol.model.enums.ECustomStatus
import com.veepoo.protocol.model.enums.EFunctionStatus
import com.veepoo.protocol.model.enums.EHeartWaringStatus
import com.veepoo.protocol.model.enums.ELanguage
import com.veepoo.protocol.model.enums.ELongSeatStatus
import com.veepoo.protocol.model.enums.EMultiAlarmOprate
import com.veepoo.protocol.model.enums.ENightTurnWristeStatus
import com.veepoo.protocol.model.enums.EOprateStauts
import com.veepoo.protocol.model.enums.EScreenLightTime
import com.veepoo.protocol.model.enums.ESocailMsg
import com.veepoo.protocol.model.settings.Alarm2Setting
import com.veepoo.protocol.model.settings.ContentPhoneSetting
import com.veepoo.protocol.model.settings.ContentSetting
import com.veepoo.protocol.model.settings.ContentSmsSetting
import com.veepoo.protocol.model.settings.ContentSocailSetting
import com.veepoo.protocol.model.settings.CustomSetting
import com.veepoo.protocol.model.settings.CustomSettingData
import com.veepoo.protocol.model.settings.HeartWaringSetting
import com.veepoo.protocol.model.settings.LongSeatSetting
import com.veepoo.protocol.model.settings.NightTurnWristSetting
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

// ------------------------------------------------------------------ app models

/** Personal settings packet (0xB8). null = the watch doesn't support that switch. */
data class WatchToggles(
    val use24Hour: Boolean,
    val metric: Boolean?,
    val autoHeartRate: Boolean?,
    val autoBloodPressure: Boolean?,
    val disconnectReminder: Boolean?,
    val sos: Boolean?,
)

data class ScreenOnTime(val seconds: Int, val min: Int, val max: Int, val recommended: Int)
data class WristRaise(val on: Boolean, val startH: Int, val startM: Int, val endH: Int, val endM: Int, val level: Int, val defaultLevel: Int, val customWindow: Boolean)
data class HeartAlarm(val on: Boolean, val high: Int, val low: Int)
data class Sedentary(val on: Boolean, val startH: Int, val startM: Int, val endH: Int, val endM: Int, val minutes: Int)

/** One watch alarm. [days] empty = one-shot on [date]. */
data class WatchAlarm(val id: Int, val hour: Int, val minute: Int, val days: Set<DayOfWeek>, val date: LocalDate?, val on: Boolean)

/** One notification category on the watch. */
data class NotifyCategory(val key: String, val label: String, val on: Boolean)

/** What kind of message to push. */
sealed interface WatchMessage {
    data class Call(val name: String?, val number: String) : WatchMessage
    data class Sms(val name: String?, val number: String, val text: String) : WatchMessage
    data class App(val category: String, val title: String, val text: String) : WatchMessage
}

enum class CameraEvent { READY, SHUTTER, NOT_READY, OPENED_FROM_WATCH, CLOSED, FAILED }

/**
 * Settings that live on the watch, plus notifications and the camera shutter.
 * Every write starts from the last object read (whole-object writes). Every call has a timeout, because
 * the SDK silently ignores calls for features the watch doesn't support.
 */
@Singleton
class WatchSettingsClient @Inject constructor(private val core: VeepooClient) {
    private val log get() = core.log
    private val vp get() = core.vp

    private var lastCustom: CustomSettingData? = null
    private var lastWrist: NightTurnWristeData? = null
    private var lastAlarms: List<Alarm2Setting> = emptyList()
    private var lastSocial: FunctionSocailMsgData? = null

    private fun fail(name: String) = { code: Int -> WatchException("$name: write failed (code $code)") }

    // ------------------------------------------------------------ personal settings

    suspend fun readToggles(): WatchToggles {
        val d: CustomSettingData = core.command("readCustomSetting") { once ->
            vp.readCustomSetting(core.writeLogger("readCustomSetting") { once.fail(fail("readCustomSetting")(it)) }, customListener { d ->
                if (d.status == ECustomStatus.READ_SUCCESS) once.ok(d) else once.fail(WatchException("Couldn't read watch settings (${d.status})"))
            })
        }
        lastCustom = d
        return d.toToggles()
    }

    /** Changes the given switches (null = leave as is) and returns what the watch reports back. */
    suspend fun writeToggles(
        use24Hour: Boolean? = null, metric: Boolean? = null, autoHeartRate: Boolean? = null,
        autoBloodPressure: Boolean? = null, disconnectReminder: Boolean? = null, sos: Boolean? = null,
    ): WatchToggles {
        val cur = lastCustom ?: run { readToggles(); lastCustom!! }
        use24Hour?.let { cur.setIs24Hour(it) }
        metric?.let { if (cur.metricSystem.has()) cur.metricSystem = it.status() }
        autoHeartRate?.let { if (cur.autoHeartDetect.has()) cur.autoHeartDetect = it.status() }
        autoBloodPressure?.let { if (cur.autoBpDetect.has()) cur.autoBpDetect = it.status() }
        disconnectReminder?.let { if (cur.disconnectRemind.has()) cur.disconnectRemind = it.status() }
        sos?.let { if (cur.sos.has()) cur.setSOS(it.status()) }
        // cur is our cached copy, already modified: if the watch doesn't confirm, drop the cache so the
        // next write starts from a fresh read instead of a value the watch never accepted.
        lastCustom = null
        val d: CustomSettingData = core.command("changeCustomSetting") { once ->
            vp.changeCustomSetting(core.writeLogger("changeCustomSetting") { once.fail(fail("changeCustomSetting")(it)) }, customListener { d ->
                if (d.status == ECustomStatus.SETTING_SUCCESS) once.ok(d) else once.fail(WatchException("Watch didn't save the setting (${d.status})"))
            }, CustomSetting(cur))
        }
        lastCustom = d
        return d.toToggles()
    }

    private fun customListener(onData: (CustomSettingData) -> Unit) = object : ICustomSettingDataListener {
        override fun OnSettingDataChange(d: CustomSettingData?) { log.cb("OnSettingDataChange", d); if (d != null) onData(d) }
    }

    private fun CustomSettingData.toToggles() = WatchToggles(
        use24Hour = isIs24Hour,
        metric = metricSystem.flag(),
        autoHeartRate = autoHeartDetect.flag(),
        autoBloodPressure = autoBpDetect.flag(),
        disconnectReminder = disconnectRemind.flag(),
        sos = sos.flag(),
    )

    // ------------------------------------------------------------ screen-on time

    suspend fun readScreenOnTime(): ScreenOnTime = core.command("readScreenLightTime") { once ->
        vp.readScreenLightTime(core.writeLogger("readScreenLightTime") { once.fail(fail("readScreenLightTime")(it)) }, screenListener { d ->
            if (d.screenLightState == EScreenLightTime.READ_SUCCESS) once.ok(d.toApp()) else once.fail(WatchException("Couldn't read screen time (${d.screenLightState})"))
        })
    }

    suspend fun writeScreenOnTime(seconds: Int): ScreenOnTime = core.command("setScreenLightTime") { once ->
        vp.setScreenLightTime(core.writeLogger("setScreenLightTime") { once.fail(fail("setScreenLightTime")(it)) }, screenListener { d ->
            if (d.screenLightState == EScreenLightTime.SETTING_SUCCESS) once.ok(d.toApp()) else once.fail(WatchException("Watch didn't save screen time (${d.screenLightState})"))
        }, seconds)
    }

    private fun screenListener(onData: (ScreenLightTimeData) -> Unit) = object : IScreenLightTimeListener {
        override fun onScreenLightTimeDataChange(d: ScreenLightTimeData?) { log.cb("onScreenLightTimeDataChange", d); if (d != null) onData(d) }
    }

    private fun ScreenLightTimeData.toApp() = ScreenOnTime(currentDuration, minDuration, maxDuration, recommendDuration)

    // ------------------------------------------------------------ wrist raise

    suspend fun readWristRaise(): WristRaise {
        val d: NightTurnWristeData = core.command("readNightTurnWriste") { once ->
            vp.readNightTurnWriste(core.writeLogger("readNightTurnWriste") { once.fail(fail("readNightTurnWriste")(it)) }, wristListener { d ->
                if (d.oprateStauts == ENightTurnWristeStatus.SUCCESS) once.ok(d) else once.fail(WatchException("Couldn't read wrist-raise (${d.oprateStauts})"))
            })
        }
        lastWrist = d
        return d.toApp()
    }

    suspend fun writeWristRaise(w: WristRaise): WristRaise {
        val setting = NightTurnWristSetting(w.on, TimeData(w.startH, w.startM), TimeData(w.endH, w.endM), w.level.coerceIn(1, 10))
        val d: NightTurnWristeData = core.command("settingNightTurnWriste") { once ->
            vp.settingNightTurnWriste(core.writeLogger("settingNightTurnWriste") { once.fail(fail("settingNightTurnWriste")(it)) }, wristListener { d ->
                if (d.oprateStauts == ENightTurnWristeStatus.SUCCESS) once.ok(d) else once.fail(WatchException("Watch didn't save wrist-raise (${d.oprateStauts})"))
            }, setting)
        }
        lastWrist = d
        return d.toApp()
    }

    private fun wristListener(onData: (NightTurnWristeData) -> Unit) = object : INightTurnWristeDataListener {
        override fun onNightTurnWristeDataChange(d: NightTurnWristeData?) { log.cb("onNightTurnWristeDataChange", d); if (d != null) onData(d) }
    }

    private fun NightTurnWristeData.toApp() = WristRaise(
        on = isNightTureWirsteStatusOpen,
        startH = startTime?.hour ?: 0, startM = startTime?.minute ?: 0,
        endH = endTime?.hour ?: 0, endM = endTime?.minute ?: 0,
        level = level.takeIf { it in 1..10 } ?: defaultLevel.takeIf { it in 1..10 } ?: 5,
        defaultLevel = defaultLevel.takeIf { it in 1..10 } ?: 5,
        customWindow = isSupportCustomSettingTime,
    )

    // ------------------------------------------------------------ heart-rate alarm

    suspend fun readHeartAlarm(): HeartAlarm = core.command("readHeartWarning") { once ->
        vp.readHeartWarning(core.writeLogger("readHeartWarning") { once.fail(fail("readHeartWarning")(it)) }, heartAlarmListener { d ->
            if (d.status == EHeartWaringStatus.READ_SUCCESS) once.ok(d.toApp()) else once.fail(WatchException("Couldn't read the heart-rate alarm (${d.status})"))
        })
    }

    suspend fun writeHeartAlarm(a: HeartAlarm): HeartAlarm = core.command("settingHeartWarning") { once ->
        vp.settingHeartWarning(core.writeLogger("settingHeartWarning") { once.fail(fail("settingHeartWarning")(it)) }, heartAlarmListener { d ->
            if (d.status == EHeartWaringStatus.OPEN_SUCCESS || d.status == EHeartWaringStatus.CLOSE_SUCCESS) once.ok(d.toApp())
            else once.fail(WatchException("Watch didn't save the heart-rate alarm (${d.status})"))
        }, HeartWaringSetting(a.high, a.low, a.on)) // NB: high first
    }

    private fun heartAlarmListener(onData: (HeartWaringData) -> Unit) = object : IHeartWaringDataListener {
        override fun onHeartWaringDataChange(d: HeartWaringData?) { log.cb("onHeartWaringDataChange", d); if (d != null) onData(d) }
    }

    private fun HeartWaringData.toApp() = HeartAlarm(isOpen, heartHigh, heartLow)

    // ------------------------------------------------------------ sedentary

    suspend fun readSedentary(): Sedentary = core.command("readLongSeat") { once ->
        vp.readLongSeat(core.writeLogger("readLongSeat") { once.fail(fail("readLongSeat")(it)) }, longSeatListener { d ->
            if (d.status == ELongSeatStatus.READ_SUCCESS) once.ok(d.toApp()) else once.fail(WatchException("Couldn't read the sedentary reminder (${d.status})"))
        })
    }

    suspend fun writeSedentary(s: Sedentary): Sedentary = core.command("settingLongSeat") { once ->
        vp.settingLongSeat(
            core.writeLogger("settingLongSeat") { once.fail(fail("settingLongSeat")(it)) },
            LongSeatSetting(s.startH, s.startM, s.endH, s.endM, s.minutes, s.on),
            longSeatListener { d ->
                if (d.status == ELongSeatStatus.OPEN_SUCCESS || d.status == ELongSeatStatus.CLOSE_SUCCESS) once.ok(d.toApp())
                else once.fail(WatchException("Watch didn't save the sedentary reminder (${d.status})"))
            },
        )
    }

    private fun longSeatListener(onData: (LongSeatData) -> Unit) = object : ILongSeatDataListener {
        override fun onLongSeatDataChange(d: LongSeatData?) { log.cb("onLongSeatDataChange", d); if (d != null) onData(d) }
    }

    private fun LongSeatData.toApp() = Sedentary(isOpen, startHour, startMinute, endHour, endMinute, threshold)

    // ------------------------------------------------------------ alarms (Alarm2)

    /** Reads the alarm list. If the watch stays quiet (its list matches the SDK cache), uses the cache. */
    suspend fun readAlarms(): List<WatchAlarm> {
        val list: List<Alarm2Setting> = try {
            core.command("readAlarm2", 6_000) { once ->
                vp.readAlarm2(core.writeLogger("readAlarm2") { once.fail(fail("readAlarm2")(it)) }, alarmListener { d ->
                    when (d.oprate) {
                        EMultiAlarmOprate.READ_SUCCESS, EMultiAlarmOprate.READ_SUCCESS_SAVE,
                        EMultiAlarmOprate.READ_SUCCESS_SAME_CRC, EMultiAlarmOprate.READ_SUCCESS_NULL -> once.ok(d.alarm2SettingList.orEmpty())
                        else -> once.fail(WatchException("Couldn't read alarms (${d.oprate})"))
                    }
                })
            }
        } catch (e: CommandTimeoutException) {
            log.app("readAlarm2 quiet; using SDK cache")
            vp.alarm2List.orEmpty()
        }
        lastAlarms = list
        return list.map { it.toApp() }.sortedWith(compareBy({ it.hour }, { it.minute }))
    }

    suspend fun saveAlarm(a: WatchAlarm): List<WatchAlarm> {
        if (lastAlarms.isEmpty() && a.id == 0) readAlarms() // the SDK picks the new ID from its cache
        val existing = lastAlarms.firstOrNull { it.alarmId == a.id }
        val repeat = repeatString(a.days)
        val oneShot = if (a.days.isEmpty()) (a.date ?: LocalDate.now()).toString() else "0000-00-00"
        val name = if (existing == null) "addAlarm2" else "modifyAlarm2"
        if (existing != null) lastAlarms = emptyList() // existing is mutated below; re-read on failure
        val result: List<Alarm2Setting> = core.command(name) { once ->
            val listener = alarmListener { d ->
                when (d.oprate) {
                    EMultiAlarmOprate.SETTING_SUCCESS -> once.ok(d.alarm2SettingList.orEmpty())
                    EMultiAlarmOprate.ALARM_FULL -> once.fail(WatchException("The watch already has the maximum of 20 alarms"))
                    else -> once.fail(WatchException("Watch didn't save the alarm (${d.oprate})"))
                }
            }
            val resp = core.writeLogger(name) { once.fail(fail(name)(it)) }
            if (existing == null) {
                vp.addAlarm2(resp, listener, Alarm2Setting(a.hour, a.minute, repeat, 0, oneShot, a.on))
            } else {
                existing.alarmHour = a.hour
                existing.alarmMinute = a.minute
                existing.repeatStatus = repeat
                existing.unRepeatDate = oneShot
                existing.isOpen = a.on
                vp.modifyAlarm2(resp, listener, existing)
            }
        }
        lastAlarms = result
        return result.map { it.toApp() }.sortedWith(compareBy({ it.hour }, { it.minute }))
    }

    suspend fun deleteAlarm(id: Int): List<WatchAlarm> {
        val existing = lastAlarms.firstOrNull { it.alarmId == id } ?: return lastAlarms.map { it.toApp() }
        val result: List<Alarm2Setting> = core.command("deleteAlarm2") { once ->
            vp.deleteAlarm2(core.writeLogger("deleteAlarm2") { once.fail(fail("deleteAlarm2")(it)) }, alarmListener { d ->
                if (d.oprate == EMultiAlarmOprate.CLEAR_SUCCESS) once.ok(d.alarm2SettingList.orEmpty())
                else once.fail(WatchException("Watch didn't delete the alarm (${d.oprate})"))
            }, existing)
        }
        lastAlarms = result
        return result.map { it.toApp() }.sortedWith(compareBy({ it.hour }, { it.minute }))
    }

    private fun alarmListener(onData: (AlarmData2) -> Unit) = object : IAlarm2DataListListener {
        override fun onAlarmDataChangeListListener(d: AlarmData2?) { log.cb("onAlarmDataChangeListListener", d?.oprate.toString() + " n=" + d?.alarm2SettingList?.size); if (d != null) onData(d) }
    }

    private fun Alarm2Setting.toApp(): WatchAlarm {
        val days = parseRepeat(repeatStatus.orEmpty())
        val date = if (days.isEmpty()) runCatching { LocalDate.parse(unRepeatDate) }.getOrNull() else null
        return WatchAlarm(alarmId, alarmHour, alarmMinute, days, date, isOpen)
    }

    // ------------------------------------------------------------ notification categories

    suspend fun readNotifyCategories(): List<NotifyCategory> {
        val d: FunctionSocailMsgData = vp.functionSocailMsgData ?: core.command("readSocialMsg") { once ->
            vp.readSocialMsg(core.writeLogger("readSocialMsg") { once.fail(fail("readSocialMsg")(it)) }, socialListener { once.ok(it) })
        }
        lastSocial = d
        return d.toCategories()
    }

    suspend fun setNotifyCategory(key: String, on: Boolean): List<NotifyCategory> {
        val cur = lastSocial ?: run { readNotifyCategories(); lastSocial!! }
        val field = CATEGORIES.firstOrNull { it.key == key } ?: return cur.toCategories()
        if (!field.get(cur).has()) return cur.toCategories() // never flip an UNSUPPORT field
        field.set(cur, on.status())
        lastSocial = null // re-read on failure (see writeToggles)
        val d: FunctionSocailMsgData = core.command("settingSocialMsg") { once ->
            vp.settingSocialMsg(core.writeLogger("settingSocialMsg") { once.fail(fail("settingSocialMsg")(it)) }, socialListener { once.ok(it) }, cur)
        }
        lastSocial = d
        return d.toCategories()
    }

    /** Loads the watch's category switches once per connection (the SDK caches them after the password step). */
    suspend fun loadNotifyCategoriesIfNeeded() {
        if (lastSocial == null) readNotifyCategories()
    }

    /** Forget cached watch state (call on disconnect, so the next watch/connection is read fresh). */
    fun clearCache() {
        lastCustom = null; lastWrist = null; lastAlarms = emptyList(); lastSocial = null
    }

    /** True if the watch has this category at all (supported, on or off). Unknown before the first read. */
    fun categorySupported(key: String): Boolean =
        lastSocial?.let { s -> CATEGORIES.firstOrNull { it.key == key }?.get(s).has() } ?: (key == "other" || key == "msg" || key == "phone")

    /** Category key a phone app's notifications should use, if the watch has that category switched on. */
    fun categoryOn(key: String): Boolean = lastSocial?.let { s -> CATEGORIES.firstOrNull { it.key == key }?.get(s) == EFunctionStatus.SUPPORT_OPEN } ?: (key == "other" || key == "msg" || key == "phone")

    private fun socialListener(onData: (FunctionSocailMsgData) -> Unit) = object : ISocialMsgDataListener {
        override fun onSocialMsgSupportDataChange(d: FunctionSocailMsgData?) { log.cb("onSocialMsgSupportDataChange", d); if (d != null) onData(d) }
        override fun onSocialMsgSupportDataChange2(d: FunctionSocailMsgData?) { log.cb("onSocialMsgSupportDataChange2", d) }
    }

    private fun FunctionSocailMsgData.toCategories() = CATEGORIES.mapNotNull { c ->
        val st = c.get(this)
        if (st.has()) NotifyCategory(c.key, c.label, st == EFunctionStatus.SUPPORT_OPEN) else null
    }

    // ------------------------------------------------------------ push a notification

    /** Sends one message to the watch. No device confirmation exists; we wait for the last packet's write ack. */
    suspend fun push(m: WatchMessage) {
        val content: ContentSetting = when (m) {
            is WatchMessage.Call -> ContentPhoneSetting(ESocailMsg.PHONE, m.name?.let { trimUtf8(it, 70) }, m.number)
            is WatchMessage.Sms -> ContentSmsSetting(m.name?.let { trimUtf8(it, 70) }, m.number, trimUtf8(m.text, 200))
            is WatchMessage.App -> {
                val title = trimUtf8(clean(m.title), 70)
                val body = trimUtf8(clean(m.text), (279 - utf8Len(title)).coerceAtLeast(0))
                ContentSocailSetting(categoryEnum(m.category), title, body)
            }
        }
        core.queue.run("sendSocialMsgContent", 8_000) {
            core.requireReady()
            log.app("sendSocialMsgContent", m.javaClass.simpleName)
            val lastAck = AtomicLong(0)
            val failed = CompletableDeferred<Int>()
            vp.sendSocialMsgContent(object : IBleWriteResponse {
                override fun onResponse(code: Int) {
                    lastAck.set(System.currentTimeMillis())
                    if (code != Code.REQUEST_SUCCESS) failed.complete(code)
                }
            }, content)
            // One ack per packet (up to 20). Done when acks stop for 400 ms.
            while (true) {
                val code = withTimeoutOrNull(100) { failed.await() }
                if (code != null) throw WatchException("Message write failed (code $code)")
                val t = lastAck.get()
                if (t > 0 && System.currentTimeMillis() - t > 400) break
            }
            log.app("sendSocialMsgContent done")
        }
    }

    /** Tell the watch a ringing call was answered or ended, so it stops showing it. */
    suspend fun callEnded() = core.queue.run("offhookOrIdlePhone", 4_000) {
        core.requireReady()
        core.sendAndAwaitWrite("offhookOrIdlePhone") { vp.offhookOrIdlePhone(it) }
    }

    // ------------------------------------------------------------ language

    suspend fun setLanguage(lang: ELanguage) {
        val d: LanguageData = core.command("settingDeviceLanguage") { once ->
            vp.settingDeviceLanguage(core.writeLogger("settingDeviceLanguage") { once.fail(fail("settingDeviceLanguage")(it)) }, object : ILanguageDataListener {
                override fun onLanguageDataChange(d: LanguageData?) { log.cb("onLanguageDataChange", d); if (d != null) once.ok(d) }
            }, lang)
        }
        if (d.stauts != EOprateStauts.OPRATE_SUCCESS) throw WatchException("Watch didn't change language (${d.stauts})")
    }

    // ------------------------------------------------------------ camera shutter

    private val _camera = MutableSharedFlow<CameraEvent>(extraBufferCapacity = 8)
    /** Shutter presses and camera-mode changes coming from the watch. */
    val camera: SharedFlow<CameraEvent> = _camera.asSharedFlow()

    /** One listener object for every camera call (the SDK keeps a single camera listener slot). */
    private val cameraListener = object : ICameraDataListener {
        override fun OnCameraDataChange(s: ECameraStatus?) {
            log.cb("OnCameraDataChange", s)
            val e = when (s) {
                ECameraStatus.OPEN_SUCCESS -> CameraEvent.READY
                ECameraStatus.TAKEPHOTO_CAN -> CameraEvent.SHUTTER
                ECameraStatus.TAKEPHOTO_CAN_NOT -> CameraEvent.NOT_READY
                ECameraStatus.DEVICE_OPEN_SUCCESS -> CameraEvent.OPENED_FROM_WATCH
                ECameraStatus.CLOSE_SUCCESS -> CameraEvent.CLOSED
                ECameraStatus.OPEN_FALI, ECameraStatus.CLOSE_FAIL -> CameraEvent.FAILED
                else -> null
            }
            e?.let { _camera.tryEmit(it) }
            pendingCamera?.invoke(s)
        }
    }
    @Volatile private var pendingCamera: ((ECameraStatus?) -> Unit)? = null

    /** Register once after connecting, so "camera" opened from the watch's menu reaches the app. */
    fun listenForCamera() = runCatching { vp.setCameraListener(cameraListener) }

    suspend fun startCamera() = cameraCall("startCamera", ECameraStatus.OPEN_SUCCESS) { r -> vp.startCamera(r, cameraListener) }
    suspend fun stopCamera() = cameraCall("stopCamera", ECameraStatus.CLOSE_SUCCESS) { r -> vp.stopCamera(r, cameraListener) }

    private suspend fun cameraCall(name: String, success: ECameraStatus, call: (IBleWriteResponse) -> Unit) {
        core.command<Unit>(name, 6_000) { once ->
            pendingCamera = { s ->
                when (s) {
                    success -> once.ok(Unit)
                    ECameraStatus.OPEN_FALI, ECameraStatus.CLOSE_FAIL -> once.fail(WatchException("Watch camera mode failed ($s)"))
                    else -> Unit
                }
            }
            call(core.writeLogger(name) { once.fail(fail(name)(it)) })
        }
        pendingCamera = null
    }

    // ------------------------------------------------------------ helpers

    private fun EFunctionStatus?.has() = this == EFunctionStatus.SUPPORT || this == EFunctionStatus.SUPPORT_OPEN || this == EFunctionStatus.SUPPORT_CLOSE
    private fun EFunctionStatus?.flag(): Boolean? = if (has()) this == EFunctionStatus.SUPPORT_OPEN else null
    private fun Boolean.status() = if (this) EFunctionStatus.SUPPORT_OPEN else EFunctionStatus.SUPPORT_CLOSE

    private fun clean(s: String) = s.replace(Regex("[\\r\\n]+"), " ").replace(Regex("[\\p{So}\\p{Cs}]"), "").trim()
    private fun utf8Len(s: String) = s.toByteArray(Charsets.UTF_8).size

    /** Cuts to at most [maxBytes] UTF-8 bytes without splitting a character. */
    private fun trimUtf8(s: String, maxBytes: Int): String {
        if (utf8Len(s) <= maxBytes) return s
        val sb = StringBuilder()
        var used = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val n = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (used + n > maxBytes) break
            sb.appendCodePoint(cp)
            used += n
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    private class Cat(val key: String, val label: String, val sdk: ESocailMsg, val get: (FunctionSocailMsgData) -> EFunctionStatus?, val set: (FunctionSocailMsgData, EFunctionStatus) -> Unit)

    private fun categoryEnum(key: String): ESocailMsg = CATEGORIES.firstOrNull { it.key == key }?.sdk ?: ESocailMsg.OTHER

    companion object {
        /**
         * Repeat days ↔ the SDK's 7-char string. Per the API doc, read right to left: Mon, Tue … Sun,
         * so the rightmost char is Monday. (The demo builds it the other way; unverified on the T30.)
         */
        fun repeatString(days: Set<DayOfWeek>): String {
            val bits = CharArray(7) { '0' }
            days.forEach { d -> bits[6 - (d.value - 1)] = '1' }
            return String(bits)
        }

        fun parseRepeat(s: String): Set<DayOfWeek> {
            if (s.length != 7) return emptySet()
            return (0 until 7).filter { s[6 - it] == '1' }.map { DayOfWeek.of(it + 1) }.toSet()
        }

        private val CATEGORIES = listOf(
            Cat("phone", "Calls", ESocailMsg.PHONE, { it.phone }, { d, v -> d.phone = v }),
            Cat("msg", "Text messages", ESocailMsg.SMS, { it.msg }, { d, v -> d.msg = v }),
            Cat("whats", "WhatsApp", ESocailMsg.WHATS, { it.whats }, { d, v -> d.whats = v }),
            Cat("facebook", "Facebook", ESocailMsg.FACEBOOK, { it.facebook }, { d, v -> d.facebook = v }),
            Cat("instagram", "Instagram", ESocailMsg.INSTAGRAM, { it.instagram }, { d, v -> d.instagram = v }),
            Cat("gmail", "Gmail", ESocailMsg.GMAIL, { it.gmail }, { d, v -> d.gmail = v }),
            Cat("twitter", "X / Twitter", ESocailMsg.TWITTER, { it.twitter }, { d, v -> d.twitter = v }),
            Cat("linkin", "LinkedIn", ESocailMsg.LINKIN, { it.linkin }, { d, v -> d.linkin = v }),
            Cat("snapchat", "Snapchat", ESocailMsg.SNAPCHAT, { it.snapchat }, { d, v -> d.snapchat = v }),
            Cat("skype", "Skype", ESocailMsg.SKYPE, { it.skype }, { d, v -> d.skype = v }),
            Cat("line", "LINE", ESocailMsg.LINE, { it.line }, { d, v -> d.line = v }),
            Cat("wechat", "WeChat", ESocailMsg.WECHAT, { it.wechat }, { d, v -> d.wechat = v }),
            Cat("qq", "QQ", ESocailMsg.QQ, { it.qq }, { d, v -> d.qq = v }),
            Cat("telegram", "Telegram", ESocailMsg.TELEGRAM, { it.telegram }, { d, v -> d.telegram = v }),
            Cat("messenger", "Messenger", ESocailMsg.MESSENGER, { it.messenger }, { d, v -> d.messenger = v }),
            Cat("other", "All other apps", ESocailMsg.OTHER, { it.other }, { d, v -> d.other = v }),
        )
    }
}

