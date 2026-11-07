package com.t30watch.ui.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.t30watch.ble.ScannedDevice
import com.t30watch.data.WatchRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class ConnectViewModel @Inject constructor(private val repo: WatchRepository) : ViewModel() {
    val connection = repo.connection
    val bluetoothOn = repo.bluetoothOn
    val scanResults = repo.scanResults
    val scanning = repo.scanning
    val scanError = repo.scanError
    val battery = repo.battery
    val batteryError = repo.batteryError
    val lastWatch = repo.lastWatch.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun scan() = repo.startScan()
    fun stopScan() = repo.stopScan()
    fun connect(device: ScannedDevice) = repo.connect(device.mac, device.name)
    fun connect(mac: String, name: String?) = repo.connect(mac, name)
    fun disconnect() = repo.disconnect()
    fun refreshBattery() = repo.refreshBattery()
}
