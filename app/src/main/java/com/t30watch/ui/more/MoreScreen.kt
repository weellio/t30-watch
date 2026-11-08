package com.t30watch.ui.more

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.t30watch.data.settings.Profile
import com.t30watch.data.settings.Sex
import com.t30watch.data.settings.DarkMode
import com.t30watch.data.settings.Units
import com.t30watch.ui.theme.Palette
import com.t30watch.ui.theme.Palettes
import com.t30watch.util.Fmt
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import com.t30watch.health.HealthConnectExporter
import com.t30watch.ui.common.Share

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen(onOpenDebug: () -> Unit, vm: MoreViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val hcGranted by vm.hcGranted.collectAsStateWithLifecycle()
    var range by rememberSaveable { mutableStateOf(ExportRange.MONTH) }

    val hcLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { vm.refreshHealthConnect() }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LifecycleResumeEffect(Unit) {
        vm.refreshHealthConnect()
        onPauseOrDispose { }
    }

    Scaffold(topBar = { CenterAlignedTopAppBar(title = { Text("More") }) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            message?.let { m ->
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(m, modifier = Modifier.weight(1f))
                            OutlinedButton(onClick = vm::clearMessage) { Text("OK") }
                        }
                    }
                }
            }

            // ---- Profile
            item { Section("Profile") }
            item { ProfileCard(settings.profile, settings.units, onSave = vm::saveProfile) }

            // ---- Appearance
            item { Section("Appearance") }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Style", style = MaterialTheme.typography.titleSmall)
                        Palettes.all.chunked(3).forEach { rowItems ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                rowItems.forEach { p -> PaletteTile(p, p.id == settings.themeId, Modifier.weight(1f)) { vm.setTheme(p.id) } }
                                repeat(3 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                        Text("Light or dark", style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(DarkMode.SYSTEM to "Follow phone", DarkMode.LIGHT to "Light", DarkMode.DARK to "Dark").forEach { (m, label) ->
                                FilterChip(selected = settings.darkMode == m, onClick = { vm.setDarkMode(m) }, label = { Text(label) })
                            }
                        }
                        SwitchRow("Pure black in dark mode", "Deepest dark. Saves battery on this screen.", settings.amoled, vm::setAmoled)
                        Text("Units", style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = settings.units == Units.IMPERIAL, onClick = { vm.setUnits(Units.IMPERIAL) }, label = { Text("US (ft, lb, mi)") })
                            FilterChip(selected = settings.units == Units.METRIC, onClick = { vm.setUnits(Units.METRIC) }, label = { Text("Metric (cm, kg, km)") })
                        }
                    }
                }
            }

            // ---- Background
            item { Section("Background") }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SwitchRow("Keep the watch connected", "Reconnects by itself and syncs in the background. Shows a small notification.", settings.keepConnected, vm::setKeepConnected)
                        Text("Auto-sync", style = MaterialTheme.typography.bodyLarge)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(0 to "Off", 15 to "15 min", 30 to "30 min", 60 to "1 h", 180 to "3 h").forEach { (m, label) ->
                                FilterChip(selected = settings.autoSyncMinutes == m, onClick = { vm.setAutoSync(m) }, label = { Text(label) })
                            }
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            OutlinedButton(onClick = { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Allow the status notification") }
                        }
                        OutlinedButton(onClick = {
                            context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                        }) { Text("Battery settings (set to Unrestricted)") }
                    }
                }
            }

            // ---- Notifications
            item { Section("Notifications to the watch") }
            item {
                var access by remember { mutableStateOf(vm.hasNotificationAccess()) }
                LifecycleResumeEffect(Unit) { access = vm.hasNotificationAccess(); onPauseOrDispose { } }
                val apps by vm.notifyApps.collectAsStateWithLifecycle()
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SwitchRow("Forward phone notifications", "Calls, texts and app notifications appear on the watch. Paused while the phone is in Do Not Disturb.", settings.forwardNotifications, vm::setForward)
                        if (!access) {
                            Text("Needs Notification access.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            Button(onClick = { context.startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) { Text("Open Notification access") }
                        }
                        SwitchRow("Quiet hours", "The watch stays still overnight. Your phone still rings and shows everything.", settings.quietHours, vm::setQuietHours)
                        if (settings.quietHours) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                TimeButton("From", settings.quietStartMin, vm::setQuietStart)
                                TimeButton("To", settings.quietEndMin, vm::setQuietEnd)
                            }
                            SwitchRow("Let phone calls through", "Calls still buzz the watch during quiet hours.", settings.quietAllowCalls, vm::setQuietAllowCalls)
                        }
                        SwitchRow("Skip silent notifications", "If the phone shows it without a sound or buzz (weather, sync status, promos), the watch won't buzz either.", settings.skipSilent, vm::setSkipSilent)
                        val list = apps.values.sortedByDescending { it.lastSeen }.take(15)
                        if (list.isNotEmpty()) {
                            Text("Recent apps (switch off to never forward)", style = MaterialTheme.typography.titleSmall)
                            list.forEach { a ->
                                SwitchRow(a.label, if (a.sent > 0) "${a.sent} sent" + (a.lastError?.let { " · last error: $it" } ?: "") else a.lastError, a.pkg !in settings.mutedApps) { on -> vm.setMuted(a.pkg, !on) }
                            }
                        }
                    }
                }
            }

            // ---- Export
            item { Section("Export") }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ExportRange.entries.forEach { r -> FilterChip(selected = range == r, onClick = { range = r }, label = { Text(r.label) }) }
                        }
                        Button(enabled = !busy, onClick = { vm.exportCsv(range) { files -> Share.files(context, files, "T30 watch data") } }) {
                            Text("Export CSV files")
                        }
                        Text("One file per data type, opened in the share sheet (email, Drive, Files…).", style = MaterialTheme.typography.bodySmall)

                        Text("Health Connect", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                        when (vm.healthConnect.availability()) {
                            HealthConnectExporter.Availability.NOT_SUPPORTED -> Text("Health Connect isn't available on this phone.")
                            HealthConnectExporter.Availability.NEEDS_UPDATE -> OutlinedButton(onClick = {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata")))
                            }) { Text("Install or update Health Connect") }
                            HealthConnectExporter.Availability.AVAILABLE -> {
                                if (!hcGranted) {
                                    Button(onClick = { hcLauncher.launch(vm.healthConnect.permissions) }) { Text("Allow Health Connect") }
                                } else {
                                    Button(enabled = !busy, onClick = { vm.exportHealthConnect(range) }) { Text("Write to Health Connect") }
                                }
                                SwitchRow("Write after every sync", null, settings.healthConnectAutoExport, vm::setHcAuto)
                            }
                        }
                    }
                }
            }

            // ---- Debug
            item { Section("Troubleshooting") }
            item {
                OutlinedButton(onClick = onOpenDebug, modifier = Modifier.fillMaxWidth()) { Text("Debug: features, logs, connection settings") }
            }
        }
    }
}

/** Shows a time in the phone's own style (9:00 PM or 21:00) and opens the clock picker. */
@Composable
private fun TimeButton(label: String, minute: Int, onPick: (Int) -> Unit) {
    val ctx = LocalContext.current
    val is24 = android.text.format.DateFormat.is24HourFormat(ctx)
    val shown = java.time.LocalTime.of(minute / 60, minute % 60)
        .format(java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT))
    OutlinedButton(onClick = {
        android.app.TimePickerDialog(ctx, { _, h, m -> onPick(h * 60 + m) }, minute / 60, minute % 60, is24).show()
    }) { Text("$label $shown") }
}

@Composable
private fun Section(title: String) = Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 4.dp))

@Composable
private fun SwitchRow(label: String, hint: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ProfileCard(saved: Profile, units: Units, onSave: (Profile) -> Unit) {
    val us = units == Units.IMPERIAL
    fun ft(p: Profile) = Fmt.cmToFtIn(p.heightCm).first.toString()
    fun inch(p: Profile) = Fmt.cmToFtIn(p.heightCm).second.toString()
    fun w(p: Profile) = (if (us) Fmt.kgToLb(p.weightKg) else p.weightKg).toString()
    var heightCm by remember { mutableStateOf(saved.heightCm.toString()) }
    var feet by remember { mutableStateOf(ft(saved)) }
    var inches by remember { mutableStateOf(inch(saved)) }
    var weight by remember { mutableStateOf(w(saved)) }
    var age by remember { mutableStateOf(saved.age.toString()) }
    var sex by remember { mutableStateOf(saved.sex) }
    var steps by remember { mutableStateOf(saved.stepGoal.toString()) }
    var sleepH by remember { mutableStateOf((saved.sleepGoalMinutes / 60).toString()) }
    LaunchedEffect(saved, units) {
        heightCm = saved.heightCm.toString(); feet = ft(saved); inches = inch(saved); weight = w(saved)
        age = saved.age.toString(); sex = saved.sex; steps = saved.stepGoal.toString(); sleepH = (saved.sleepGoalMinutes / 60).toString()
    }
    val cm = if (us) Fmt.ftInToCm(feet.toIntOrNull() ?: 0, inches.toIntOrNull() ?: 0) else heightCm.toIntOrNull() ?: 0
    val kg = (weight.toIntOrNull() ?: 0).let { if (us) Fmt.lbToKg(it) else it }
    val p = Profile(
        heightCm = cm, weightKg = kg, age = age.toIntOrNull() ?: 0, sex = sex,
        stepGoal = steps.toIntOrNull() ?: 0, sleepGoalMinutes = (sleepH.toIntOrNull() ?: 0) * 60, saved = true,
    )
    val inchesOk = !us || (inches.toIntOrNull() ?: 0) in 0..11
    val valid = inchesOk && p.heightCm in 100..250 && p.weightKg in 30..250 && p.age in 5..110 && p.stepGoal in 1000..50_000 && p.sleepGoalMinutes in 180..720

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!saved.saved) Text("The watch uses these for calories and distance. Nothing is sent until you save.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (us) {
                    Num("Height ft", feet, Modifier.weight(1f)) { feet = it }
                    Num("in", inches, Modifier.weight(1f)) { inches = it }
                } else {
                    Num("Height (cm)", heightCm, Modifier.weight(1f)) { heightCm = it }
                }
                Num(if (us) "Weight (lb)" else "Weight (kg)", weight, Modifier.weight(1.2f)) { weight = it }
                Num("Age", age, Modifier.weight(1f)) { age = it }
            }
            if (us && !inchesOk) Text("Inches: 0 to 11", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Sex.entries.forEach { s -> FilterChip(selected = sex == s, onClick = { sex = s }, label = { Text(s.name.lowercase().replaceFirstChar { it.uppercase() }) }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Num("Step goal", steps, Modifier.weight(1f)) { steps = it }
                Num("Sleep goal (h)", sleepH, Modifier.weight(1f)) { sleepH = it }
            }
            Button(enabled = valid && (p != saved), onClick = { onSave(p) }) { Text("Save") }
        }
    }
}

@Composable
private fun PaletteTile(p: Palette, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier
            .clip(shape)
            .border(
                if (selected) 3.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape,
            )
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(p.primary, p.secondary, p.tertiary).forEach { c -> Box(Modifier.size(18.dp).clip(CircleShape).background(c)) }
        }
        Text(p.name, style = MaterialTheme.typography.labelLarge, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
        Text(p.mood, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
private fun Num(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() }.take(5)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}
