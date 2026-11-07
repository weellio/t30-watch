package com.t30watch.ui.debug

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.t30watch.ble.ConnectionState
import com.t30watch.ble.DumpField
import com.t30watch.ble.DumpSection
import com.t30watch.ble.SdkDump
import com.t30watch.ui.common.Share

private enum class Filter(val label: String) { ALL("All"), YES("Supported"), NO("Not supported") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(onBack: () -> Unit, vm: DebugViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val connection by vm.connection.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val recent by vm.recentLog.collectAsStateWithLifecycle()

    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }
    var expanded by rememberSaveable { mutableStateOf(setOf<String>()) }
    var showLog by rememberSaveable { mutableStateOf(false) }

    val ready = connection as? ConnectionState.Ready
    val sections = ready?.info?.sections.orEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Debug") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---------------- Watch features
            item { Text("Watch features", style = MaterialTheme.typography.titleLarge) }
            if (ready == null) {
                item {
                    Text("Connect to the watch to read its feature list.", style = MaterialTheme.typography.bodyMedium)
                }
                if (vm.supportDumpFile.exists()) {
                    item {
                        OutlinedButton(onClick = { Share.files(context, listOf(vm.supportDumpFile), "T30 feature dump") }) {
                            Text("Share last saved list")
                        }
                    }
                }
            } else {
                item {
                    val info = ready.info
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            KeyValue("Watch", "${ready.name.orEmpty()} ${ready.mac}")
                            KeyValue("Firmware", info.firmwareVersion)
                            KeyValue("Test version", info.testVersion)
                            KeyValue("Model number", info.deviceNumber.toString())
                            KeyValue("Password", info.passwordStatus)
                            KeyValue("CPU type", info.cpuType)
                            KeyValue("JieLi chip", if (info.isJieLi) "yes" else "no")
                            KeyValue("Supported features", countSupported(sections).toString())
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Filter.entries.forEach { f ->
                            FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) })
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { Share.text(context, SdkDump.toText(sections), "T30 feature dump") }) { Text("Share list") }
                        OutlinedButton(onClick = {
                            expanded = if (expanded.size == sections.size) emptySet() else sections.map { it.title }.toSet()
                        }) { Text(if (expanded.size == sections.size) "Collapse all" else "Expand all") }
                    }
                }
                sections.forEach { section ->
                    val rows = section.fields.filter {
                        when (filter) {
                            Filter.ALL -> true
                            Filter.YES -> it.supported == true
                            Filter.NO -> it.supported == false
                        }
                    }
                    val open = section.title in expanded
                    item(key = "h:" + section.title) {
                        SectionHeader(section, rows.size, open) {
                            expanded = if (open) expanded - section.title else expanded + section.title
                        }
                    }
                    if (open) {
                        items(rows, key = { "f:" + section.title + ":" + it.name }) { FieldRow(it) }
                    }
                }
            }

            // ---------------- Settings
            item { Text("Connection settings", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp)) }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        PasswordField(settings.password, vm::setPassword)
                        SwitchRow("24-hour clock on the watch", settings.use24Hour, vm::setUse24Hour)
                        SwitchRow("Ask to confirm on the watch", settings.confirmOnWatch, vm::setConfirmOnWatch)
                        Text("These apply the next time you connect.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // ---------------- Logs
            item { Text("Logs", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp)) }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SwitchRow("Raw SDK log to file", settings.rawLogging, vm::setRawLogging)
                        SwitchRow("SDK's own BLE byte log", settings.sdkLogging, vm::setSdkLogging)
                        Text(vm.logDirPath, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                            Button(onClick = { Share.files(context, vm.allLogFiles(), "T30 logs") }) { Text("Share logs") }
                            OutlinedButton(onClick = vm::clearLogs) { Text("Clear") }
                            OutlinedButton(onClick = { showLog = !showLog }) { Text(if (showLog) "Hide recent" else "Show recent") }
                        }
                    }
                }
            }
            if (showLog) {
                items(recent.asReversed()) { line ->
                    Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 13.sp)
                }
            }
        }
    }
}

private fun countSupported(sections: List<DumpSection>): Int =
    sections.filter { it.title.startsWith("Feature package") }.sumOf { s -> s.fields.count { it.supported == true } }

@Composable
private fun SectionHeader(section: DumpSection, shown: Int, open: Boolean, onToggle: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onToggle)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(section.title, style = MaterialTheme.typography.titleSmall)
                Text(
                    if (section.missing) "Watch didn't send this" else "$shown of ${section.fields.size} fields",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(if (open) "▲" else "▼")
        }
    }
}

@Composable
private fun FieldRow(f: DumpField) {
    val color = when (f.supported) {
        true -> Color(0xFF2E9E5B)
        false -> MaterialTheme.colorScheme.error
        null -> MaterialTheme.colorScheme.onSurface
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Text(f.name, fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(f.value, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = color, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun KeyValue(k: String, v: String) {
    Row {
        Text(k, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(150.dp))
        Text(v, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
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
private fun PasswordField(saved: String, onSave: (String) -> Unit) {
    val focus = LocalFocusManager.current
    var text by remember { mutableStateOf(saved) }
    LaunchedEffect(saved) { text = saved }
    val valid = text.length == 4 && text.all { it.isDigit() }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { v -> text = v.filter { it.isDigit() }.take(4) },
            label = { Text("Watch password") },
            singleLine = true,
            isError = !valid,
            supportingText = { Text("4 digits. Factory default is 0000.") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (valid) onSave(text); focus.clearFocus() }),
            modifier = Modifier.weight(1f),
        )
        TextButton(enabled = valid && text != saved, onClick = { onSave(text); focus.clearFocus() }) { Text("Save") }
    }
}
