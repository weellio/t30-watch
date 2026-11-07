package com.t30watch.ui.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.t30watch.ble.RawLog
import com.t30watch.data.WatchRepository
import com.t30watch.data.settings.AppSettings
import com.t30watch.data.settings.Settings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class DebugViewModel @Inject constructor(
    private val repo: WatchRepository,
    private val settingsStore: AppSettings,
    private val log: RawLog,
) : ViewModel() {
    val connection = repo.connection
    val recentLog = log.recent
    val settings = settingsStore.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Settings())

    val logDirPath: String get() = log.logDir.absolutePath
    val supportDumpFile: File get() = repo.supportDumpFile

    fun setPassword(v: String) = viewModelScope.launch { settingsStore.setPassword(v) }
    fun setUse24Hour(v: Boolean) = viewModelScope.launch { settingsStore.setUse24Hour(v) }
    fun setConfirmOnWatch(v: Boolean) = viewModelScope.launch { settingsStore.setConfirmOnWatch(v) }
    fun setRawLogging(v: Boolean) = viewModelScope.launch { settingsStore.setRawLogging(v) }
    fun setSdkLogging(v: Boolean) = viewModelScope.launch { settingsStore.setSdkLogging(v) }

    fun allLogFiles(): List<File> = log.logFiles() + repo.sdkLogFiles()
    fun clearLogs() = log.clear()
}
