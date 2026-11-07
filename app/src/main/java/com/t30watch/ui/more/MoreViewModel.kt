package com.t30watch.ui.more

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.t30watch.data.settings.AppSettings
import com.t30watch.data.settings.Profile
import com.t30watch.data.settings.Settings
import com.t30watch.data.sync.SyncEngine
import com.t30watch.export.CsvExporter
import com.t30watch.health.HealthConnectExporter
import com.t30watch.service.WatchService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

enum class ExportRange(val label: String, val days: Int) { WEEK("Last 7 days", 7), MONTH("Last 30 days", 30), YEAR("Last year", 365), ALL("Everything", 36_500) }

@HiltViewModel
class MoreViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val store: AppSettings,
    private val sync: SyncEngine,
    private val csv: CsvExporter,
    val healthConnect: HealthConnectExporter,
    notifyLog: com.t30watch.data.notify.NotificationLog,
) : ViewModel() {
    val notifyApps = notifyLog.apps
    val settings: StateFlow<Settings> = store.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Settings())
    val syncStatus = sync.status

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _hcGranted = MutableStateFlow(false)
    val hcGranted: StateFlow<Boolean> = _hcGranted.asStateFlow()

    init { refreshHealthConnect() }

    fun refreshHealthConnect() = viewModelScope.launch { _hcGranted.value = healthConnect.hasAllPermissions() }

    fun saveProfile(p: Profile) = viewModelScope.launch {
        store.setProfile(p)
        sync.pushProfile()
        _message.value = "Profile saved. It's sent to the watch now (if connected) and on every connect."
    }

    fun setKeepConnected(on: Boolean) = viewModelScope.launch {
        store.setKeepConnected(on)
        if (on) WatchService.start(context) else WatchService.stop(context)
    }

    fun setUnits(u: com.t30watch.data.settings.Units) = viewModelScope.launch { store.setUnits(u) }
    fun setTheme(id: String) = viewModelScope.launch { store.setThemeId(id) }
    fun setDarkMode(m: com.t30watch.data.settings.DarkMode) = viewModelScope.launch { store.setDarkMode(m) }
    fun setAmoled(on: Boolean) = viewModelScope.launch { store.setAmoled(on) }

    fun setAutoSync(minutes: Int) = viewModelScope.launch { store.setAutoSyncMinutes(minutes) }
    fun setHcAuto(on: Boolean) = viewModelScope.launch { store.setHealthConnectAutoExport(on) }
    fun setForward(on: Boolean) = viewModelScope.launch { store.setForwardNotifications(on) }
    fun setMuted(pkg: String, muted: Boolean) = viewModelScope.launch { store.setAppMuted(pkg, muted) }

    /** True when the user has granted Notification access to this app. */
    fun hasNotificationAccess(): Boolean =
        androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    /** Writes CSVs and hands them to [onReady] (the UI opens the share sheet). */
    fun exportCsv(range: ExportRange, onReady: (List<File>) -> Unit) = viewModelScope.launch {
        _busy.value = true
        try {
            val now = System.currentTimeMillis()
            val files = csv.export(now - range.days * 86_400_000L, now + 1)
            if (files.isEmpty()) _message.value = "No data in that range yet. Sync the watch first."
            else onReady(files)
        } catch (e: Exception) {
            _message.value = "Export failed: ${e.message}"
        } finally {
            _busy.value = false
        }
    }

    fun exportHealthConnect(range: ExportRange) = viewModelScope.launch {
        _busy.value = true
        try {
            val now = System.currentTimeMillis()
            val r = healthConnect.export(now - range.days * 86_400_000L, now + 1)
            _message.value = "Wrote ${r.written} records to Health Connect" +
                if (r.skippedTypes.isNotEmpty()) ". Skipped (no permission): ${r.skippedTypes.joinToString()}" else "."
        } catch (e: Exception) {
            _message.value = "Health Connect export failed: ${e.message}"
        } finally {
            _busy.value = false
        }
    }

    fun clearMessage() { _message.value = null }
}
