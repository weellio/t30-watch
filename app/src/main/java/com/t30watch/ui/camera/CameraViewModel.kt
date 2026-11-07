package com.t30watch.ui.camera

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.t30watch.ble.CameraEvent
import com.t30watch.ble.ConnectionState
import com.t30watch.ble.RawLog
import com.t30watch.ble.VeepooClient
import com.t30watch.ble.WatchSettingsClient
import com.t30watch.di.AppScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CameraViewModel @Inject constructor(
    private val watch: WatchSettingsClient,
    private val client: VeepooClient,
    private val log: RawLog,
    @param:AppScope private val appScope: CoroutineScope,
) : ViewModel() {
    val events: SharedFlow<CameraEvent> = watch.camera
    private val _status = MutableStateFlow("Starting watch camera mode…")
    val status: StateFlow<String> = _status.asStateFlow()
    private var started = false

    init {
        viewModelScope.launch {
            watch.camera.collect { e ->
                _status.value = when (e) {
                    CameraEvent.READY -> "Ready: shake or tap the watch to take a photo"
                    CameraEvent.SHUTTER -> "Click!"
                    CameraEvent.NOT_READY -> "The watch isn't ready yet"
                    CameraEvent.OPENED_FROM_WATCH -> "Camera mode opened on the watch"
                    CameraEvent.CLOSED -> "Watch camera mode off"
                    CameraEvent.FAILED -> "The watch couldn't switch camera mode"
                }
            }
        }
    }

    fun start() {
        if (started) return
        if (client.state.value !is ConnectionState.Ready) {
            _status.value = "Watch not connected. You can still use the button below."
            return
        }
        started = true
        viewModelScope.launch {
            runCatching { watch.startCamera() }
                .onSuccess { _status.value = "Ready: shake or tap the watch to take a photo" }
                .onFailure { _status.value = it.message ?: "Couldn't start watch camera mode"; log.error("startCamera", it) }
        }
    }

    /** Always take the watch out of camera mode when the screen closes. */
    override fun onCleared() {
        if (started && client.state.value is ConnectionState.Ready) {
            appScope.launch { runCatching { watch.stopCamera() }.onFailure { log.error("stopCamera", it) } }
        }
    }
}
