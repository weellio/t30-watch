package com.t30watch

import androidx.lifecycle.ViewModel
import com.t30watch.ble.CameraEvent
import com.t30watch.ble.WatchSettingsClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharedFlow
import javax.inject.Inject

/** App-wide events the navigation reacts to (e.g. "camera opened on the watch"). */
@HiltViewModel
class AppViewModel @Inject constructor(watchSettings: WatchSettingsClient) : ViewModel() {
    val cameraEvents: SharedFlow<CameraEvent> = watchSettings.camera
}
