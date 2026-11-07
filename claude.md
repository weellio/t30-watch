# CLAUDE.md — T30 Watch App

## What this project is
A personal Android app to replace the "H Band" companion app for my **T30 smartwatch**.
The watch is a **Veepoo** device. H Band (`com.veepoo.hband`) is Veepoo's app.
Goal: support everything the watch can do, with better sync, charts, and data export than H Band.

Open source (MIT). Public on GitHub, with no Veepoo cloud: the app has no internet permission.

## The device (from an nRF Connect scan)
- Advertised name: `T30`
- MAC: shown on the Watch tab after a scan. Don't hardcode it.
- GATT services:
  - `0x1800` Generic Access and `0x1801` Generic Attribute: standard
  - `f0080001-0451-4000-b000-000000000000`: Veepoo proprietary (main data channel)
  - `f0020001-0451-4000-b000-000000000000`: Veepoo proprietary (secondary/OTA, unconfirmed)
  - `0xFEE7`: WeChat service. Ignore it.
  - `0x1812`: HID (likely camera shutter / music control from the watch)
- **No standard Heart Rate Service (0x180D).** All health data goes through the Veepoo protocol.
- The watch accepts **one app connection at a time**. H Band must be force-stopped or unpaired while testing.

## Approach: use Veepoo's official SDK, don't reverse-engineer
SDK: https://github.com/HBandSDK/Android_Ble_SDK
(mirror: https://gitee.com/fellowm/Android_Ble_SDK)
- English API doc: the repo wiki page "VeepooSDK-Android-API-Document"
- Demo app: `android_sdk_source/Demo/VpBluetoothSDK`. **Read it before writing BLE code.** It's the ground truth for call order and callbacks.
- Javadoc: `android_sdk_source/apidoc/`
- Guides: `android_sdk_source/sdkdoc/`

### First-time setup tasks
1. Clone the SDK into `./vendor/Android_Ble_SDK` (add it to `.gitignore`, or use a git submodule).
2. Copy the needed binaries into `app/libs/`:
   - `jar_base/vpbluetooth-*.aar` (or `.jar`; check which one exists)
   - `jar_core/vpprotocol_2.1.*.aar`
   - `jniLibs/` → `app/src/main/jniLibs/` (native ECG/HRV processing)
   - Gson: use the Maven artifact (`com.google.code.gson:gson`) instead of the bundled jar
   - OTA libraries (`libble`, `libcomx`, `libdfu`, `libfastdfu`) are only needed for Goodix-chip firmware upgrades. Skip them for now.
3. The README uses old `compile files(...)`. Use `implementation(files("libs/..."))` in `build.gradle.kts`.
4. Manifest: declare the SDK's `com.inuker.bluetooth.library.BluetoothService`.
5. Permissions: the README lists legacy ones (BLUETOOTH, BLUETOOTH_ADMIN, location). Also add the Android 12+ permissions `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` and request them at runtime. Keep location for API ≤ 30.
6. After setup, write `docs/sdk-notes.md` summarizing the real API surface: method names, listeners, data models, and any oddities you find in the demo. Keep it updated.

### Connection sequence (from the README; verify against the demo)
1. `VPOperateManager.getMangerInstance(applicationContext)` (the misspelling "Manger" is in the SDK)
2. `startScanDevice(...)`. The SDK filters out non-Veepoo devices.
3. `connectDevice(...)`
4. When the notify-success callback fires → `confirmDevicePwd(...)` with the password (try `"0000"` first) and the 24h flag.
   This call returns **device-supported features** through `IDeviceFuctionDataListener` (`FunctionDeviceSupportData`) and social-notification support through `ISocialMsgDataListener`.
5. `syncPersonInfo(...)` (height, weight, age, sex; used for calorie and step math)
6. `registerConnectStatusListener(...)`, plus `registerBluetoothStateListener(...)`

## Hard rules
- **Never run SDK operations concurrently.** The device doesn't support parallel operations and data gets corrupted. Every SDK call goes through a single serialized command queue (coroutine `Mutex` / channel) and waits for its callback or a timeout before the next one starts.
- **Firmware updates (OTA) and watch-face uploads can brick the watch.** Don't implement or trigger them without asking me first. Feature-gate them behind an explicit setting.
- The SDK uses callbacks. Wrap each one in `suspendCancellableCoroutine` / `callbackFlow` inside one repository layer. UI code never touches `VPOperateManager` directly.
- Use the feature-support data from step 4 to show or hide features. Don't assume the T30 supports something because the SDK has an API for it.
- Log every raw SDK callback, behind a debug toggle, to a rotating file I can pull. Debugging BLE without logs is miserable.

## Stack
- Kotlin, Jetpack Compose (Material 3), single-activity
- Coroutines and Flow; Hilt for DI
- Room for local storage of all synced data (the source of truth for the UI)
- Foreground service to keep the connection alive and for background sync
- Health Connect export (heart rate, steps, sleep, SpO2, blood pressure, where supported)
- CSV/JSON export via the share sheet
- minSdk 26, target latest stable

## Suggested layout
```
app/src/main/java/.../
  ble/        VeepooClient (SDK wrapper + command queue), connection state machine
  data/       Room entities/DAOs, repositories, sync engine
  health/     Health Connect mapping/export
  ui/         Compose screens: Dashboard, HeartRate, Sleep, Activity, Settings, Debug
  service/    Foreground connection/sync service
```

## Roadmap (do the phases in order and check in with me after each)
1. **Connect**: scan, connect, password, battery, firmware version, and a **Debug screen that dumps the full FunctionDeviceSupportData** so we know what the T30 actually supports. Stop here and show me the list.
2. **Live data**: real-time heart rate, plus SpO2 and blood pressure if supported.
3. **History sync**: steps, sleep, heart rate, and the 30-minute daily data → Room, with incremental sync.
4. **Charts and dashboard**: day/week/month views.
5. **Export**: Health Connect and CSV.
6. **Settings that write to the watch**: time/24h, alarms, sedentary reminder, wrist-raise, units, language, continuous heart rate monitoring interval, do-not-disturb.
7. **Notifications to the watch**: a NotificationListenerService that forwards app notifications, using only the categories reported by the social-message support data.
8. **Extras, based on the support list**: find device, camera shutter, music control, contacts, sports modes, ECG, temperature, and so on.
9. **Risky, only after I approve**: watch faces and OTA.

## Working with me
- I'm an experienced IT/sysadmin and PowerShell person, but not primarily an Android developer. Explain Android-specific decisions in a sentence when they're not obvious.
- I test on my own Android phone with Android Studio. Tell me exactly what to tap or run when you need me to test on the watch.
- When an SDK behavior is unclear, check the demo app and the docs first. Then tell me what you assumed.
- Keep `docs/sdk-notes.md` and a short `docs/progress.md` current, so a new session can pick up where we left off.