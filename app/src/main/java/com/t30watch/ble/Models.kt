package com.t30watch.ble

/** App-side models. Nothing outside ble/ sees an SDK class. */

data class ScannedDevice(val mac: String, val name: String?, val rssi: Int)

sealed interface ConnectionState {
    /** [reason] is set when we got here because something went wrong. */
    data class Disconnected(val reason: String? = null) : ConnectionState
    data class Connecting(val mac: String, val name: String?, val step: String) : ConnectionState
    data class Ready(val mac: String, val name: String?, val info: WatchInfo) : ConnectionState
}

data class WatchInfo(
    val deviceNumber: Int,
    val firmwareVersion: String,
    val testVersion: String,
    val passwordStatus: String,
    val inFirmwareUpdateMode: Boolean,
    val cpuType: String,
    val isJieLi: Boolean,
    /** True when the watch never sent its settings packet and we stopped waiting. */
    val partial: Boolean,
    /** Everything the watch reported during the password step, for the Debug screen. */
    val sections: List<DumpSection>,
) {
    /** Feature-flag names (SDK getter names) the watch reported as supported, e.g. "heartRateDetect". */
    val supported: Set<String> = sections
        .filter { it.title.startsWith("Feature package") }
        .flatMap { s -> s.fields.filter { it.supported == true }.map { it.name } }
        .toSet()

    fun supports(feature: String): Boolean = feature in supported

    companion object Features {
        const val HEART_RATE = "heartRateDetect"
        const val BLOOD_PRESSURE = "bloodPressure"
        const val SPO2 = "spo2H"
    }
}

enum class HeartStatus(val label: String) {
    STARTING("Starting…"),
    BUSY("Watch is busy"),
    MEASURING("Measuring…"),
    NOT_WORN("Watch not worn properly"),
    LOW_BATTERY("Watch battery too low"),
    OK("Measuring"),
}

/** One live heart-rate callback. [bpm] is null until the watch has a valid value. */
data class HeartSample(val timeMs: Long, val bpm: Int?, val status: HeartStatus)

enum class BpStatus(val label: String) {
    OK("Measuring"),
    BUSY("Watch is busy"),
    LOW_BATTERY("Watch battery too low"),
    CHARGING("Watch is charging"),
    NOT_WORN("Watch not worn properly"),
}

/** One blood-pressure callback. [progress] is null on watches that don't report progress. */
data class BpReading(val progress: Int?, val systolic: Int, val diastolic: Int, val status: BpStatus) {
    /** Ranges from the SDK doc; anything outside means the measurement is invalid. */
    val valid: Boolean get() = systolic in 60..300 && diastolic in 20..200
}

data class BatteryInfo(
    /** 0–100. Converted from the 1–4 level when the watch doesn't report a percentage. */
    val percent: Int,
    val isExactPercent: Boolean,
    val charging: Boolean,
    val low: Boolean,
    val raw: String,
)

/** One SDK object turned into name/value rows. */
data class DumpSection(val title: String, val fields: List<DumpField>, val missing: Boolean = false)

data class DumpField(
    val name: String,
    val value: String,
    /** true/false for feature flags (EFunctionStatus), null for plain values. */
    val supported: Boolean?,
)

class WatchException(message: String) : Exception(message)
