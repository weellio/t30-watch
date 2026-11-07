package com.t30watch.ui.connect

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.t30watch.R
import com.t30watch.ble.BatteryInfo
import com.t30watch.ble.ConnectionState
import com.t30watch.ble.ScannedDevice
import com.t30watch.util.BlePermissions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(
    onOpenDebug: () -> Unit,
    onOpenMeasure: () -> Unit,
    onOpenWatchSettings: () -> Unit,
    vm: ConnectViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val connection by vm.connection.collectAsStateWithLifecycle()
    val sdkBluetoothOn by vm.bluetoothOn.collectAsStateWithLifecycle()
    val results by vm.scanResults.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val scanError by vm.scanError.collectAsStateWithLifecycle()
    val battery by vm.battery.collectAsStateWithLifecycle()
    val batteryError by vm.batteryError.collectAsStateWithLifecycle()
    val lastWatch by vm.lastWatch.collectAsStateWithLifecycle()

    var permitted by remember { mutableStateOf(BlePermissions.granted(context)) }
    var askedOnce by rememberSaveable { mutableStateOf(false) }
    var btOn by remember { mutableStateOf(BlePermissions.bluetoothEnabled(context)) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permitted = BlePermissions.granted(context)
        askedOnce = true
    }
    val enableBtLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        btOn = BlePermissions.bluetoothEnabled(context)
    }
    LifecycleResumeEffect(Unit) {
        permitted = BlePermissions.granted(context)
        btOn = BlePermissions.bluetoothEnabled(context)
        onPauseOrDispose { }
    }
    LaunchedEffect(sdkBluetoothOn) { btOn = BlePermissions.bluetoothEnabled(context) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Watch") },
                actions = { TextButton(onClick = onOpenDebug) { Text("Debug") } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                !permitted -> item {
                    NoticeCard(
                        title = "Bluetooth access needed",
                        body = "Allow \"Nearby devices\" so the app can find and talk to the watch.",
                    ) {
                        Button(onClick = { permissionLauncher.launch(BlePermissions.required) }) { Text("Allow") }
                        if (askedOnce) {
                            OutlinedButton(onClick = {
                                context.startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                                )
                            }) { Text("App settings") }
                        }
                    }
                }
                !btOn -> item {
                    NoticeCard(title = "Bluetooth is off", body = "Turn on Bluetooth to connect to the watch.") {
                        Button(onClick = { enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }) { Text("Turn on") }
                    }
                }
                else -> {
                    item {
                        StatusCard(
                            connection = connection,
                            battery = battery,
                            batteryError = batteryError,
                            onRefreshBattery = vm::refreshBattery,
                            onDisconnect = vm::disconnect,
                            onOpenDebug = onOpenDebug,
                            onOpenMeasure = onOpenMeasure,
                            onOpenWatchSettings = onOpenWatchSettings,
                        )
                    }
                    if (connection is ConnectionState.Disconnected) {
                        lastWatch?.let { w ->
                            item {
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text("Last watch", style = MaterialTheme.typography.labelMedium)
                                            Text(w.name, style = MaterialTheme.typography.titleMedium)
                                            Text(w.mac, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                                        }
                                        Button(onClick = { vm.connect(w.mac, w.name) }) { Text("Connect") }
                                    }
                                }
                            }
                        }
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Nearby watches", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                if (scanning) {
                                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                    TextButton(onClick = vm::stopScan) { Text("Stop") }
                                } else {
                                    Button(onClick = vm::scan) { Text(if (results.isEmpty()) "Scan" else "Scan again") }
                                }
                            }
                        }
                        scanError?.let { err -> item { Text(err, color = MaterialTheme.colorScheme.error) } }
                        if (!scanning && results.isEmpty()) {
                            item {
                                Text(
                                    "Close H Band first. The watch only talks to one app at a time.",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                        items(results, key = { it.mac }) { d -> DeviceRow(d, onClick = { vm.connect(d) }) }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(
    connection: ConnectionState,
    battery: BatteryInfo?,
    batteryError: String?,
    onRefreshBattery: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenDebug: () -> Unit,
    onOpenMeasure: () -> Unit,
    onOpenWatchSettings: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (connection) {
                is ConnectionState.Disconnected -> {
                    Text("Not connected", style = MaterialTheme.typography.titleMedium)
                    connection.reason?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
                is ConnectionState.Connecting -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("${connection.step}…", style = MaterialTheme.typography.titleMedium)
                    }
                    Text("${connection.name.orEmpty()} ${connection.mac}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = onDisconnect) { Text("Cancel") }
                }
                is ConnectionState.Ready -> {
                    val info = connection.info
                    Text(connection.name ?: "Watch", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    InfoRow("MAC", connection.mac)
                    InfoRow("Firmware", info.firmwareVersion.ifEmpty { "?" })
                    InfoRow("Model number", info.deviceNumber.toString())
                    InfoRow(
                        "Battery",
                        when {
                            battery != null -> buildString {
                                append(if (battery.isExactPercent) "${battery.percent}%" else "about ${battery.percent}%")
                                if (battery.charging) append(", charging")
                                if (battery.low) append(", low")
                            }
                            batteryError != null -> "couldn't read"
                            else -> "reading…"
                        },
                    )
                    if (info.inFirmwareUpdateMode) {
                        Text("Watch is in firmware-update mode. Don't use it until that's sorted.", color = MaterialTheme.colorScheme.error)
                    }
                    if (info.partial) {
                        Text("Some setup data didn't arrive. Reconnect if features look missing.", color = MaterialTheme.colorScheme.error)
                    }
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onOpenWatchSettings) { Text("Watch settings") }
                        OutlinedButton(onClick = onOpenMeasure) { Text("Measure") }
                        OutlinedButton(onClick = onOpenDebug) { Text("Features") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onRefreshBattery) { Text("Battery") }
                        OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(110.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun DeviceRow(d: ScannedDevice, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(d.name ?: "(no name)", style = MaterialTheme.typography.titleMedium)
                Text(d.mac, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
            Text("${d.rssi} dBm", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun NoticeCard(title: String, body: String, actions: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
        }
    }
}
