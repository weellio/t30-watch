package com.t30watch.ui.watchsettings

import android.app.TimePickerDialog
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.t30watch.ble.ConnectionState
import com.t30watch.ble.HeartAlarm
import com.t30watch.ble.Sedentary
import com.t30watch.ble.WatchAlarm
import com.t30watch.ble.WristRaise
import java.time.DayOfWeek
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchSettingsScreen(onBack: () -> Unit, onOpenCamera: () -> Unit, vm: WatchSettingsViewModel = hiltViewModel()) {
    val conn by vm.connection.collectAsStateWithLifecycle()
    val st by vm.state.collectAsStateWithLifecycle()
    val info = (conn as? ConnectionState.Ready)?.info
    LaunchedEffect(info != null) { info?.let { i -> vm.loadAll { i.supports(it) } } }
    var editAlarm by remember { mutableStateOf<WatchAlarm?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Watch settings") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = { if (info != null) TextButton(onClick = { vm.loadAll { info.supports(it) } }) { Text("Reload") } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (info == null) {
                item { Text("Connect to the watch to change its settings.") }
                return@LazyColumn
            }
            st.message?.let { m ->
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(m, Modifier.weight(1f))
                            TextButton(onClick = vm::clearMessage) { Text("OK") }
                        }
                    }
                }
            }

            // ---- Clock & units, monitoring, extra switches (one packet on the watch)
            item {
                SectionCard("Clock, units and monitoring", st.toggles.loading, st.toggles.saving, st.toggles.error) {
                    st.toggles.value?.let { t ->
                        SwitchRow("24-hour clock", t.use24Hour, vm::set24h)
                        t.metric?.let { SwitchRow("Metric units (km, kg)", it, vm::setMetric) }
                        t.autoHeartRate?.let { SwitchRow("Measure heart rate every 5 min", it, vm::setAutoHeart) }
                        t.autoBloodPressure?.let { SwitchRow("Measure blood pressure automatically", it, vm::setAutoBp) }
                        t.disconnectReminder?.let { SwitchRow("Buzz when the phone disconnects", it, vm::setDisconnectReminder) }
                        t.sos?.let { SwitchRow("SOS on the watch", it, vm::setSos) }
                    }
                }
            }

            if (info.supports("heartRateWarning")) item {
                SectionCard("Heart-rate alarm", st.heartAlarm.loading, st.heartAlarm.saving, st.heartAlarm.error) {
                    st.heartAlarm.value?.let { HeartAlarmEditor(it, vm::saveHeartAlarm) }
                }
            }

            if (info.supports("sedentaryRemind")) item {
                SectionCard("Sedentary reminder", st.sedentary.loading, st.sedentary.saving, st.sedentary.error) {
                    st.sedentary.value?.let { SedentaryEditor(it, vm::saveSedentary) }
                }
            }

            if (info.supports("nightTurnSetting")) item {
                SectionCard("Raise wrist to wake", st.wrist.loading, st.wrist.saving, st.wrist.error) {
                    st.wrist.value?.let { WristEditor(it, vm::saveWrist) }
                }
            }

            if (info.supports("screenLightTime")) item {
                SectionCard("Screen-on time", st.screen.loading, st.screen.saving, st.screen.error) {
                    st.screen.value?.let { s ->
                        var secs by remember(s) { mutableFloatStateOf(s.seconds.toFloat()) }
                        val lo = s.min.coerceAtLeast(1)
                        val hi = maxOf(s.max, lo + 1)
                        Text("${secs.roundToInt()} s" + if (s.recommended in lo..hi) "  (recommended ${s.recommended} s)" else "")
                        Slider(value = secs.coerceIn(lo.toFloat(), hi.toFloat()), onValueChange = { secs = it }, valueRange = lo.toFloat()..hi.toFloat(), steps = (hi - lo - 1).coerceIn(0, 60))
                        Button(enabled = secs.roundToInt() != s.seconds, onClick = { vm.saveScreen(secs.roundToInt()) }) { Text("Save") }
                    }
                }
            }

            if (info.supports("alarm")) item {
                SectionCard("Alarms", st.alarms.loading, st.alarms.saving, st.alarms.error) {
                    val list = st.alarms.value.orEmpty()
                    if (list.isEmpty() && !st.alarms.loading) Text("No alarms on the watch.")
                    list.forEach { a ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("%02d:%02d".format(a.hour, a.minute), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                Text(daysText(a), style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { editAlarm = a }) { Text("Edit") }
                            Switch(checked = a.on, onCheckedChange = { vm.saveAlarm(a.copy(on = it)) })
                        }
                    }
                    if (list.size < 20) OutlinedButton(onClick = { editAlarm = WatchAlarm(0, 7, 0, DayOfWeek.entries.take(5).toSet(), null, true) }) { Text("Add alarm") }
                }
            }

            item {
                SectionCard("Notifications shown on the watch", st.categories.loading, st.categories.saving, st.categories.error) {
                    Text("Which kinds of phone notifications the watch displays. Forwarding itself is set up under More.", style = MaterialTheme.typography.bodySmall)
                    st.categories.value?.forEach { c -> SwitchRow(c.label, c.on) { vm.setCategory(c.key, it) } }
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Language", style = MaterialTheme.typography.titleMedium)
                        OutlinedButton(onClick = vm::setEnglish) { Text("Set watch language to English") }
                        if (info.supports("camera")) {
                            Text("Camera", style = MaterialTheme.typography.titleMedium)
                            OutlinedButton(onClick = onOpenCamera) { Text("Remote shutter") }
                        }
                    }
                }
            }
        }
    }

    editAlarm?.let { a ->
        AlarmDialog(
            alarm = a,
            onDismiss = { editAlarm = null },
            onSave = { vm.saveAlarm(it); editAlarm = null },
            onDelete = if (a.id != 0) ({ vm.deleteAlarm(a.id); editAlarm = null }) else null,
        )
    }
}

private val DAY_LETTERS = listOf("M", "T", "W", "T", "F", "S", "S")

private fun daysText(a: WatchAlarm): String = when {
    a.days.isEmpty() -> "Once" + (a.date?.let { " ($it)" } ?: "")
    a.days.size == 7 -> "Every day"
    a.days == DayOfWeek.entries.take(5).toSet() -> "Weekdays"
    a.days == setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) -> "Weekends"
    else -> DayOfWeek.entries.filter { it in a.days }.joinToString(" ") { it.name.take(3).lowercase().replaceFirstChar { c -> c.uppercase() } }
}

@Composable
private fun SectionCard(title: String, loading: Boolean, saving: Boolean, error: String?, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (loading || saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            content()
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun TimeButton(label: String, h: Int, m: Int, onPick: (Int, Int) -> Unit) {
    val ctx = LocalContext.current
    OutlinedButton(onClick = { TimePickerDialog(ctx, { _, hh, mm -> onPick(hh, mm) }, h, m, true).show() }) {
        Text("$label %02d:%02d".format(h, m))
    }
}

@Composable
private fun HeartAlarmEditor(a: HeartAlarm, onSave: (HeartAlarm) -> Unit) {
    var on by remember(a) { mutableStateOf(a.on) }
    var low by remember(a) { mutableFloatStateOf(a.low.coerceIn(30, 100).toFloat()) }
    var high by remember(a) { mutableFloatStateOf(a.high.coerceIn(100, 200).toFloat()) }
    SwitchRow("Alarm on", on) { on = it }
    Text("Buzz below ${low.roundToInt()} bpm")
    Slider(value = low, onValueChange = { low = it }, valueRange = 30f..100f)
    Text("Buzz above ${high.roundToInt()} bpm")
    Slider(value = high, onValueChange = { high = it }, valueRange = 100f..200f)
    val next = HeartAlarm(on, high.roundToInt(), low.roundToInt())
    Button(enabled = next != a && next.low < next.high, onClick = { onSave(next) }) { Text("Save") }
}

@Composable
private fun SedentaryEditor(s: Sedentary, onSave: (Sedentary) -> Unit) {
    var v by remember(s) { mutableStateOf(s) }
    SwitchRow("Remind me to move", v.on) { v = v.copy(on = it) }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TimeButton("From", v.startH, v.startM) { h, m -> v = v.copy(startH = h, startM = m) }
        TimeButton("To", v.endH, v.endM) { h, m -> v = v.copy(endH = h, endM = m) }
    }
    var mins by remember(s) { mutableIntStateOf(s.minutes.coerceIn(30, 240)) }
    Text("After $mins minutes without moving")
    Slider(value = mins.toFloat(), onValueChange = { mins = (it / 5).roundToInt() * 5 }, valueRange = 30f..240f)
    val next = v.copy(minutes = mins)
    Button(enabled = next != s, onClick = { onSave(next) }) { Text("Save") }
}

@Composable
private fun WristEditor(w: WristRaise, onSave: (WristRaise) -> Unit) {
    var v by remember(w) { mutableStateOf(w) }
    SwitchRow("Raise wrist to light the screen", v.on) { v = v.copy(on = it) }
    if (w.customWindow) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TimeButton("From", v.startH, v.startM) { h, m -> v = v.copy(startH = h, startM = m) }
            TimeButton("To", v.endH, v.endM) { h, m -> v = v.copy(endH = h, endM = m) }
        }
    }
    Text("Sensitivity ${v.level} (default ${w.defaultLevel})")
    Slider(value = v.level.toFloat(), onValueChange = { v = v.copy(level = it.roundToInt()) }, valueRange = 1f..10f, steps = 8)
    Button(enabled = v != w, onClick = { onSave(v) }) { Text("Save") }
}

@Composable
private fun AlarmDialog(alarm: WatchAlarm, onDismiss: () -> Unit, onSave: (WatchAlarm) -> Unit, onDelete: (() -> Unit)?) {
    var v by remember(alarm) { mutableStateOf(alarm) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (alarm.id == 0) "New alarm" else "Edit alarm") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TimeButton("Time", v.hour, v.minute) { h, m -> v = v.copy(hour = h, minute = m) }
                Text("Repeat (none = once)", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    DayOfWeek.entries.forEachIndexed { i, d ->
                        FilterChip(
                            selected = d in v.days,
                            onClick = { v = v.copy(days = if (d in v.days) v.days - d else v.days + d) },
                            label = { Text(DAY_LETTERS[i]) },
                        )
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onSave(v.copy(on = true)) }) { Text("Save") } },
        dismissButton = {
            Row {
                onDelete?.let { TextButton(onClick = it) { Text("Delete") } }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
