package com.t30watch.ui.watchsettings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.t30watch.ble.ConnectionState
import com.t30watch.ble.HeartAlarm
import com.t30watch.ble.NotifyCategory
import com.t30watch.ble.ScreenOnTime
import com.t30watch.ble.Sedentary
import com.t30watch.ble.WatchAlarm
import com.t30watch.ble.WatchSettingsClient
import com.t30watch.ble.WatchToggles
import com.t30watch.ble.WristRaise
import com.t30watch.data.WatchRepository
import com.t30watch.data.settings.AppSettings
import com.veepoo.protocol.model.enums.ELanguage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One section of the screen: what we last read from the watch, and what's happening. */
data class Part<T>(val value: T? = null, val loading: Boolean = false, val saving: Boolean = false, val error: String? = null)

data class WatchSettingsState(
    val toggles: Part<WatchToggles> = Part(),
    val screen: Part<ScreenOnTime> = Part(),
    val wrist: Part<WristRaise> = Part(),
    val heartAlarm: Part<HeartAlarm> = Part(),
    val sedentary: Part<Sedentary> = Part(),
    val alarms: Part<List<WatchAlarm>> = Part(),
    val categories: Part<List<NotifyCategory>> = Part(),
    val message: String? = null,
)

@HiltViewModel
class WatchSettingsViewModel @Inject constructor(
    private val client: WatchSettingsClient,
    watch: WatchRepository,
    private val store: AppSettings,
) : ViewModel() {
    val connection = watch.connection
    private val _state = MutableStateFlow(WatchSettingsState())
    val state: StateFlow<WatchSettingsState> = _state.asStateFlow()

    /** Reads every supported section, one command at a time. */
    fun loadAll(has: (String) -> Boolean) {
        if (connection.value !is ConnectionState.Ready) return
        viewModelScope.launch {
            load({ client.readToggles() }) { s, p -> s.copy(toggles = p(s.toggles)) }
            if (has("heartRateWarning")) load({ client.readHeartAlarm() }) { s, p -> s.copy(heartAlarm = p(s.heartAlarm)) }
            if (has("sedentaryRemind")) load({ client.readSedentary() }) { s, p -> s.copy(sedentary = p(s.sedentary)) }
            if (has("nightTurnSetting")) load({ client.readWristRaise() }) { s, p -> s.copy(wrist = p(s.wrist)) }
            if (has("screenLightTime")) load({ client.readScreenOnTime() }) { s, p -> s.copy(screen = p(s.screen)) }
            if (has("alarm")) load({ client.readAlarms() }) { s, p -> s.copy(alarms = p(s.alarms)) }
            load({ client.readNotifyCategories() }) { s, p -> s.copy(categories = p(s.categories)) }
        }
    }

    private suspend fun <T> load(read: suspend () -> T, put: (WatchSettingsState, (Part<T>) -> Part<T>) -> WatchSettingsState) {
        _state.update { put(it) { p -> p.copy(loading = true, error = null) } }
        try {
            val v = read()
            _state.update { put(it) { Part(value = v) } }
        } catch (e: Exception) {
            _state.update { put(it) { p -> p.copy(loading = false, error = e.message) } }
        }
    }

    private fun <T> save(write: suspend () -> T, put: (WatchSettingsState, (Part<T>) -> Part<T>) -> WatchSettingsState, done: String = "Saved on the watch") {
        viewModelScope.launch {
            _state.update { put(it) { p -> p.copy(saving = true, error = null) } }
            try {
                val v = write()
                _state.update { put(it) { Part(value = v) }.copy(message = done) }
            } catch (e: Exception) {
                _state.update { put(it) { p -> p.copy(saving = false, error = e.message) } }
            }
        }
    }

    // ---- toggles
    fun set24h(on: Boolean) {
        viewModelScope.launch { store.setUse24Hour(on) } // also used for the time sync on connect
        save({ client.writeToggles(use24Hour = on) }, { s, p -> s.copy(toggles = p(s.toggles)) })
    }
    fun setMetric(on: Boolean) = save({ client.writeToggles(metric = on) }, { s, p -> s.copy(toggles = p(s.toggles)) })
    fun setAutoHeart(on: Boolean) = save({ client.writeToggles(autoHeartRate = on) }, { s, p -> s.copy(toggles = p(s.toggles)) })
    fun setAutoBp(on: Boolean) = save({ client.writeToggles(autoBloodPressure = on) }, { s, p -> s.copy(toggles = p(s.toggles)) })
    fun setDisconnectReminder(on: Boolean) = save({ client.writeToggles(disconnectReminder = on) }, { s, p -> s.copy(toggles = p(s.toggles)) })
    fun setSos(on: Boolean) = save({ client.writeToggles(sos = on) }, { s, p -> s.copy(toggles = p(s.toggles)) })

    // ---- others
    fun saveHeartAlarm(a: HeartAlarm) = save({ client.writeHeartAlarm(a) }, { s, p -> s.copy(heartAlarm = p(s.heartAlarm)) })
    fun saveSedentary(v: Sedentary) = save({ client.writeSedentary(v) }, { s, p -> s.copy(sedentary = p(s.sedentary)) })
    fun saveWrist(v: WristRaise) = save({ client.writeWristRaise(v) }, { s, p -> s.copy(wrist = p(s.wrist)) })
    fun saveScreen(seconds: Int) = save({ client.writeScreenOnTime(seconds) }, { s, p -> s.copy(screen = p(s.screen)) })
    fun saveAlarm(a: WatchAlarm) = save({ client.saveAlarm(a) }, { s, p -> s.copy(alarms = p(s.alarms)) })
    fun deleteAlarm(id: Int) = save({ client.deleteAlarm(id) }, { s, p -> s.copy(alarms = p(s.alarms)) }, done = "Alarm deleted")
    fun setCategory(key: String, on: Boolean) = save({ client.setNotifyCategory(key, on) }, { s, p -> s.copy(categories = p(s.categories)) })

    fun setEnglish() = viewModelScope.launch {
        try {
            client.setLanguage(ELanguage.ENGLISH)
            _state.update { it.copy(message = "Watch language set to English") }
        } catch (e: Exception) {
            _state.update { it.copy(message = e.message) }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }
}
