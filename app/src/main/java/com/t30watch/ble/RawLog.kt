package com.t30watch.ble

import android.content.Context
import android.util.Log
import com.t30watch.BuildConfig
import com.t30watch.data.settings.AppSettings
import com.t30watch.di.AppScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Log for every raw SDK callback.
 *
 * - Always goes to logcat in debug builds.
 * - Goes to a rotating file when Settings → "Raw SDK log" is on:
 *   Android/data/com.t30watch/files/logs/raw.log (+ raw.1.log … raw.4.log), 1 MB each.
 *   Pull with: adb pull /sdcard/Android/data/com.t30watch/files/logs
 * - The last [RECENT_LINES] lines are kept in memory for the Debug screen.
 */
@Singleton
class RawLog @Inject constructor(
    @ApplicationContext context: Context,
    settings: AppSettings,
    @AppScope scope: CoroutineScope,
) {
    val logDir: File = File(context.getExternalFilesDir(null) ?: context.filesDir, "logs").apply { mkdirs() }
    private val current = File(logDir, "raw.log")

    @Volatile private var fileEnabled = true
    private val lines = Channel<String>(Channel.UNLIMITED)
    private val _recent = MutableStateFlow<List<String>>(emptyList())
    val recent: StateFlow<List<String>> = _recent.asStateFlow()

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    init {
        scope.launch { settings.settings.collect { fileEnabled = it.rawLogging } }
        scope.launch(Dispatchers.IO) {
            for (line in lines) {
                runCatching {
                    rotateIfNeeded()
                    current.appendText(line + "\n")
                }.onFailure { Log.w(TAG, "log write failed", it) }
            }
        }
    }

    /** One SDK callback (or call). [what] is the callback name, [payload] usually the model's toString(). */
    fun cb(what: String, payload: Any? = null) = write("CB", what, payload)

    /** Something the app did (a call into the SDK, a queue step, a state change). */
    fun app(what: String, payload: Any? = null) = write("APP", what, payload)

    fun error(what: String, t: Throwable?) = write("ERR", what, t?.let { "${it.javaClass.simpleName}: ${it.message}" })

    private fun write(kind: String, what: String, payload: Any?) {
        val text = if (payload == null) what else "$what: $payload"
        if (BuildConfig.DEBUG) Log.d(TAG, "[$kind] $text")
        val line = synchronized(timeFormat) { timeFormat.format(Date()) } + " [$kind] $text"
        _recent.update { (it + line).takeLast(RECENT_LINES) }
        if (fileEnabled) lines.trySend(line)
    }

    fun logFiles(): List<File> = logDir.listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()

    fun clear() {
        logFiles().forEach { it.delete() }
        _recent.value = emptyList()
    }

    private fun rotateIfNeeded() {
        if (!current.exists() || current.length() < MAX_BYTES) return
        File(logDir, "raw.${KEEP_FILES - 1}.log").delete()
        for (i in KEEP_FILES - 2 downTo 1) {
            File(logDir, "raw.$i.log").takeIf { it.exists() }?.renameTo(File(logDir, "raw.${i + 1}.log"))
        }
        current.renameTo(File(logDir, "raw.1.log"))
    }

    private companion object {
        const val TAG = "T30Raw"
        const val MAX_BYTES = 1_000_000L
        const val KEEP_FILES = 5
        const val RECENT_LINES = 300
    }
}
