# Veepoo SDK notes

What the SDK really does, checked against the demo source and the compiled AARs (`javap`).
When the README, the API doc and the demo disagree, this file says which one wins.

Last checked: 2026-11-07, SDK repo commit `02fe17e` (2026-11-06).

---

## 1. Where things are

| What | Path |
|---|---|
| SDK clone (git-ignored) | `vendor/Android_Ble_SDK` |
| Wiki clone (git-ignored) | `vendor/Android_Ble_SDK.wiki` |
| English API doc (newest copy) | `vendor/Android_Ble_SDK/android_sdk_source/sdkdoc/VeepooSDK Android Api - English.md` |
| Demo app | `vendor/Android_Ble_SDK/android_sdk_source/Demo/VpBluetoothSDK` |
| Javadoc | `vendor/Android_Ble_SDK/android_sdk_source/apidoc/index.html` |

The repo copy of the API doc is **newer** than the wiki page (it has extra sections, e.g. ZT79 game clock).
Use the repo copy.

Re-clone:

```sh
git clone --depth 1 https://github.com/HBandSDK/Android_Ble_SDK vendor/Android_Ble_SDK
git clone --depth 1 https://github.com/HBandSDK/Android_Ble_SDK.wiki.git vendor/Android_Ble_SDK.wiki
```

Mirror if GitHub fails: <https://gitee.com/fellowm/Android_Ble_SDK>

---

## 2. Library files and versions

### In `app/libs/` (copied from `android_sdk_source/jar_core/`)

| File | What it is | Needed? |
|---|---|---|
| `vpprotocol-2.3.95.15.aar` | Veepoo protocol, `VPOperateManager`, all models | Yes |
| `vpbluetooth-1.20.aar` | BLE transport (fork of BluetoothKit, `com.inuker.bluetooth.library`) | Yes |
| `JL_Watch_V1.13.1_11214-release.aar` | JieLi watch file system | Yes. vpbluetooth calls it |
| `jl_rcsp_V0.7.2_527-release.aar` | JieLi RCSP protocol + auth | Yes. vpbluetooth calls it |
| `jl_bt_ota_V1.10.0_10931-release.aar` | JieLi OTA core | Yes. vpprotocol and vpbluetooth call it |
| `BmpConvert_V1.6.0_10604-release.aar` | JieLi image converter (watch faces) | Yes. vpbluetooth calls it |
| `abpartool-release.aar` | Bluetrum chip tool | README says required. Nothing references it. Kept to be safe |

### From Maven (`gradle/libs.versions.toml`)

| Artifact | Version | Why |
|---|---|---|
| `com.google.code.gson:gson` | 2.15.0 | Replaces bundled `gson-2.2.4.jar` |
| `no.nordicsemi.android:mcumgr-core` / `mcumgr-ble` | 2.7.4 | vpprotocol references `io.runtime.mcumgr`. **Pinned**: SDK was built against 2.7.4 |
| `no.nordicsemi.android.support.v18:scanner` | 1.4.2 | README says required. Pinned |
| `androidx.localbroadcastmanager` | 1.1.0 | vpprotocol uses it |
| Kotlin stdlib | (from the build) | vpprotocol is partly Kotlin |

### Native libs (`app/src/main/jniLibs/`, from `android_sdk_source/jniLibs/`)

| File | ABIs | Purpose |
|---|---|---|
| `libnative-lib.so` | arm64, armv7, x86, x86_64 | ECG / HRV processing |
| `libopus.so`, `libopusJni.so` | arm64, armv7 only | Opus audio (AI voice features) |

The JieLi and Bluetrum AARs bring more `.so` files of their own (`libjl_*`, `libabpartool`).

### Left out on purpose

| File | Why |
|---|---|
| `jar_base/libble-0.5.aar`, `libcomx-0.5.jar`, `libdfu-1.5.aar`, `libfastdfu-0.5.aar` | Goodix OTA only. No SDK class references `com.goodix`. Add back only for phase 9 if the T30 is Goodix |
| `jar_base/gson-2.2.4.jar` | Using Maven gson |

---

## 3. Connection sequence: what the demo really does

Source: `activity/MainActivity.java` → `activity/PwdConfirmActivity.java`.

| Step | Call | Notes |
|---|---|---|
| 1 | `VPOperateManager.getInstance().init(context)` | Once per process. Doc says use app context. Demo passes an Activity (leak) |
| 2 | `setAutoConnectBTBySdk(false)` | Stops the SDK auto-pairing classic BT (calls/audio) |
| 3 | `VPLocalLogger.startMonitor(context)` | SDK's own log files (see §6) |
| 4 | `setPwdCheckTimeoutListener { }` | Fires if the password step times out |
| 5 | `registerBluetoothStateListener(IABluetoothStateListener)` | Phone BT on/off |
| 6 | `startScanDevice(seconds, SearchResponse)` | Demo uses 8 s. `stopScanDevice()` before connecting |
| 7 | `registerConnectStatusListener(mac, IABleConnectStatusListener)` | Demo registers this **before** connecting. README says "after" |
| 8 | `connectDevice(mac, name, IConnectResponse, INotifyResponse)` | See callbacks below |
| 9 | Wait for `INotifyResponse.notifyState(Code.REQUEST_SUCCESS)` | Only now can you send commands |
| 10 | `setDeviceShowConfirm(true/false)` | `true` = watch shows a "confirm connection" prompt (if supported). Use `false` for auto-reconnect |
| 11 | `confirmDevicePwd(...)` 7-arg version | Password `"0000"`, 24h `true`. See §4 |
| 12 | `syncPersonInfo(...)` | **The v2 demo never calls this.** Only the old `OperaterActivity` menu does |

Callback shapes (from `javap`):

```kotlin
IConnectResponse { fun connectState(code: Int, profile: BleGattProfile, isOadModel: Boolean) }
INotifyResponse  { fun notifyState(state: Int) }   // Code.REQUEST_SUCCESS == 0
IABleConnectStatusListener.onConnectStatusChanged(mac: String, status: Int)
    // Constants.STATUS_CONNECTED / Constants.STATUS_DISCONNECTED
```

`isOadModel = true` means the watch is stuck in firmware-update mode. Don't send normal commands then.

---

## 4. Password + feature discovery

### Default password

`"0000"`. The doc says: 4 digits, factory value 0000.

Demo quirk: `PwdConfirmActivity` has a password text box but **ignores it**. It always sends `"0000"`.

### The call

Three overloads exist. Use the 7-arg one (or 8-arg to set a custom time):

```kotlin
confirmDevicePwd(
    IBleWriteResponse,          // command was written (not "it worked")
    IPwdDataListener,           // PwdData + onConnectionConfirmTimeout()
    IDeviceFuctionDataListener, // feature support, in up to 5 packages
    ISocialMsgDataListener,     // notification categories, 2 packages
    ICustomSettingDataListener, // current watch settings
    pwd: String,                // "0000"
    is24h: Boolean,
    // optional: DeviceTimeSetting  (default = phone time; this call also sets the watch clock)
)
```

### What comes back: order is NOT fixed

Observed on the real T30 (fw 03.01.01), all within ~450 ms:

1. Feature package 1, then the merged `onFunctionSupportDataChange`
2. Feature package 2, then the merged one again
3. `onSocialMsgSupportDataChange` (packet 1 only)
4. `OnSettingDataChange(CustomSettingData)`
5. `onPwdDataChange(PwdData)`: **last**, ~30 ms after the settings packet

The demo treats the settings packet as "done". That would miss the password reply on the T30.
Our client waits for **both** PwdData and CustomSettingData.

### PwdData

| Getter | Type | Meaning |
|---|---|---|
| `getmStatus()` | `EPwdStatus` | `CHECK_SUCCESS`, `CHECK_AND_TIME_SUCCESS`, `CHECK_FAIL`, … |
| `getDeviceNumber()` | Int | Model number (used for OTA) |
| `getDeviceVersion()` | String | Firmware version to show the user |
| `getDeviceTestVersion()` | String | Internal version (used for OTA) |
| `isHaveDrinkData()` | Boolean | |
| `isOpenNightTurnWriste()` | EFunctionStatus | Wrist-raise at night |
| `getFindPhoneFunction()` | EFunctionStatus | |
| `getWearDetectFunction()` | EFunctionStatus | |

Firmware version comes from here. There's no separate "read firmware" call needed.

### Feature support: use the packages, not FunctionDeviceSupportData

`onFunctionSupportDataChange(FunctionDeviceSupportData)` is **@Deprecated**.
It fires several times; only the last one is complete.

Use `onDeviceFunctionPackage1Report` … `Package5Report` instead.
A package only arrives if the watch supports it. A missing package = all its features unsupported.

All 6 listener methods are abstract, so a Kotlin `object :` must implement every one.

`EFunctionStatus` values: `UNSUPPORT`, `SUPPORT`, `SUPPORT_OPEN`, `SUPPORT_CLOSE`, `UNKONW` (sic).
"Supported" = `SUPPORT`, `SUPPORT_OPEN` or `SUPPORT_CLOSE`.

| Package | Highlights (full list: `javap` or the API doc) |
|---|---|
| 1 | heart rate detect, blood pressure (+`bpTag`), **SpO2** (+type, calibration, apnea), sedentary, drinking, alarm, wrist-raise, women, camera, fatigue, text alarm, skin type |
| 2 | HRV, ECG (+type), weather, sport modes (+days), precise sleep, sleep tag, breathing, HID, screen style, low power, `watchDataDayNumber`, message lengths |
| 3 | temperature (+type), stress, blood glucose, contacts, music style, find device, AGPS, **`cpuType`**, watch-face counts |
| 4 | blood/body composition, world clock, auto-measure, temperature alarm, wallet, AI Q&A, AI dial, photo album, mini check-up |
| 5 | screen type (1 band, 2 ring, 3 square, 4 round), GSR, barometer, emotion, motor, wear hand, remind event, sport status, PTT |

`FunctionDeviceSupportData` still exists and has 132 getters (it merges all packages).
`toString()` on each object dumps every field. Good enough for the phase 1 Debug screen.

### Social message support

`FunctionSocailMsgData` (sic): one `EFunctionStatus` per app.
phone, msg (SMS), wechat, qq, sina, facebook, twitter, flickr, linkin, whats, line, instagram,
snapchat, skype, gmail, dingding, wxWork, kakaoTalk, tikTok, telegram, connected2_me,
shieldPolice, messenger, zalo, viber, other.

---

## 5. Battery and live heart rate

### Battery

```kotlin
readBattery(IBleWriteResponse, IBatteryDataListener { onDataChange(BatteryData) })
```

| Getter | Meaning |
|---|---|
| `isPercent()` | `true` → use `getBatteryPercent()` (1–100). `false` → use `getBatteryLevel()` (1–4). **T30: false** (levels only) |
| `getPowerModel()` | 0 normal, 1 charging, 2 low voltage, 3 full |
| `isLowBattery()` | |
| `getState()` | 0 awake, 1 asleep |

The old demo shows `level * 25 %`. That's only right when `isPercent()` is false.

### Live heart rate

```kotlin
startDetectHeart(IBleWriteResponse, IHeartDataListener { onDataChange(HeartData) })
stopDetectHeart(IBleWriteResponse)
```

`HeartData.getData()` = bpm (20–300). `getHeartStatus()`:

| EHeartStatus | Meaning |
|---|---|
| `STATE_INIT` | starting |
| `STATE_HEART_BUSY` | watch busy (another measurement running) |
| `STATE_HEART_DETECT` | measuring, no value yet |
| `STATE_HEART_WEAR_ERROR` | not worn properly |
| `STATE_LOW_BATTERY` | battery too low |
| `STATE_HEART_NORMAL` | `getData()` is valid |

The watch keeps measuring until `stopDetectHeart` (demo has a Stop button; nothing auto-ends it).
Our client holds the command queue for the whole session and caps it at 5 minutes.

### Live blood pressure

```kotlin
startDetectBP(IBleWriteResponse, IBPDetectDataListener { onDataChange(BpData) }, EBPDetectModel.DETECT_MODEL_PUBLIC)
stopDetectBP(IBleWriteResponse, EBPDetectModel.DETECT_MODEL_PUBLIC)
```

| BpData | Meaning |
|---|---|
| `getProgress()` | 0–100, only if `isHaveProgress()` |
| `isHaveProgress()` | false → no progress; the result arrives ~55 s after start |
| `getHighPressure()` / `getLowPressure()` | valid only in 60–300 / 20–200; otherwise "invalid measurement" |
| `getStatus()` | `EBPDetectStatus`: `STATE_BP_NORMAL`, `STATE_BP_BUSY` (doc: call stop), `STATE_BP_LOW_BATTERY`, `STATE_BP_CHARGING`, `STATE_BP_WEAR_OFF` |

Modes: `DETECT_MODEL_PUBLIC` (general) or `DETECT_MODEL_PRIVATE` (uses the user's calibration values,
set with `settingDetectBP`). We use PUBLIC. "Done" = progress 100, or first non-zero result on no-progress watches.

SpO2 has the same start/stop shape (`startDetectSPO2H` / `stopDetectSPO2H`) but the T30 doesn't support it.

---

## 6. Gotchas

| # | Gotcha | What we do |
|---|---|---|
| 1 | vpprotocol's manifest sets `allowBackup="true"`. Merge fails if the app sets anything else | `tools:replace="android:allowBackup"` |
| 2 | vpbluetooth's manifest has `BluetoothService` **commented out** | App manifest declares it, `exported="false"` (it's a plain bound service, no foreground) |
| 3 | JieLi AARs add storage permissions | Removed with `tools:node="remove"` |
| 4 | Demo declares `BluetoothService` with `foregroundServiceType` | Not needed: the class never calls `startForeground` (checked with `javap`). Our own foreground service comes later |
| 5 | Old enums moved to `com.veepoo.protocol.model.enums` in SDK 2.x | Use the `E*` names |
| 6 | Misspellings are real API: `getMangerInstance`, `IDeviceFuctionDataListener`, `FunctionSocailMsgData`, `getWathcDay`, `EOprateStauts`, `UNKONW`, `OnPersoninfoDataChange` | Copy names exactly |
| 7 | `IBleWriteResponse` success only means "written". The data callback may never come | Every queued command needs its own timeout |
| 8 | The API doc's `confirmDevicePwd` Kotlin example has its `CHECK_FAIL` check backwards | Check `getmStatus()` ourselves |
| 9 | `confirmDevicePwd` also sets the watch clock (phone time by default) | Nothing extra for time sync on connect |
| 10 | `IConnectResponse.connectState` with a non-success code is also how a drop shows up | Treat as "disconnected" |
| 11 | `SearchResult.getName()` can be null (demo would crash on it) | Null-check |
| 12 | Doc says scan "filters out non-company devices". Demo has its own filter (`isBeyondVp` / `isBrandDevice`) commented out | Expect a T30 to show; filter by name/MAC in our UI if noise |
| 13 | JieLi chips need extra steps (`openJLDataNotify`, `startJLDeviceAuth`, file-system init) | **Only** for watch faces and OTA (phase 9). Check `isJLDevice()` / `cpuType` |
| 14 | SDK has its own logger, `com.inuker.bluetooth.library.log.VPLocalLogger`. It records raw BLE writes, notifications and connect events to `Android/data/com.t30watch/files/VpSDK/` | Turn it on with our debug toggle. `VPLocalLogger.getInstance().shareLogFile(activity, authority)` needs a FileProvider |
| 15 | Debug APK is ~31 MB, mostly SDK native libs for 4 ABIs | Fine for a personal app |
| 16 | `startScanDevice(int, …)`: the int is assumed to be seconds (demo: 8 and 20) | Verify in phase 1 |
| 17 | Password-step callback order isn't what the demo assumes (see §4) | Wait for PwdData **and** CustomSettingData |
| 18 | `toString()` on SDK models is in Chinese | Use our `SdkDump` (getter names) for anything shown to people |
| 19 | Packages 3–5 and notification packet 2 simply never arrive on the T30 | Missing = unsupported |
| 20 | While Windows Phone Link mirrors the phone, `adb shell input tap` hits Phone Link's overlay, not the app | Test by hand, or use key events |
| 21 | **Solved.** After our app was killed, connects hung for 1–3 min with no `openGatt`, and scans found nothing. Cause: **nRF Connect**'s background foreground service (`no.nordicsemi.android.mcp/.ble.BluetoothLeService`) re-attaches to the watch every time we connect, then keeps the link alive after our process dies, so the watch stops advertising. Seen in `dumpsys bluetooth_manager` ("ACL holders gatt_if: no.nordicsemi.android.mcp"). Link closed ~1 s after force-stopping nRF Connect | Before connecting we check Android's GATT state. If the watch is already linked and our connect times out → "Another app on this phone is connected to the watch… close it". 15 s timeout, one retry otherwise. Keep nRF Connect (and H Band) closed |
| 22 | Password step sets the time (`CHECK_AND_TIME_SUCCESS`) but the settings packet still says `is24Hour=false` after sending `true`, on two connects | Open question: check the watch face |

---

## 7. Questions answered on the real watch (2026-11-07)

Full support list: `docs/t30-support.md`.

| # | Question | Answer |
|---|---|---|
| 1 | Password `0000`? | **Yes.** `CHECK_AND_TIME_SUCCESS` |
| 2 | Which chip? | `getCpuType()` = `UNKONW`, `isJLDevice()` = false, merged `cpuType=0`. **Not JieLi, family unknown.** Matters only for phase 9 OTA |
| 3 | Connection confirmation on the watch? | Not tried (setting off). Connect works without it |
| 4 | Does the scan hide non-Veepoo devices? | **Yes.** A 4 s scan in a busy room returned only the T30 |
| 5 | Does live HR block other commands? | Phase 2 |
| 6 | `syncPersonInfo` every connect? | Not called yet. Phase 3 with the profile |
| 7 | Classic BT? | Connect is pure BLE (`services=6`). HID function = UNSUPPORT despite the 0x1812 service |
| 8 | targetSdk 37 problems? | **None seen.** Scan, connect, notify, commands all fine on Android 16 |

### Still open

1. We send `is24h=true`; the settings packet right after still says `is24Hour=false`.
   Either the packet is read before the change applies, or the flag doesn't stick. **Check what the watch shows.**
2. Chip family: not JieLi, SDK reports UNKONW. Phase 9 problem only.
3. Do-not-disturb: no support flag anywhere. Phase 6.
4. ~~Stale link after app update~~: it was nRF Connect (gotcha 21).

### Live measurements on the real T30 (2026-11-07)

- Heart rate: ~6 s of `STATE_INIT` (data=0), then `STATE_HEART_NORMAL` about once a second (73 → 53 bpm over 15 s).
- Blood pressure: has progress. 0 → 100 % in ~27 s, one callback every ~1.3 s; values are 0 until the final 100 % packet (103/66).
- Both stop commands acknowledged (`code=0`).

---

## 8. History, settings and notifications (added 2026-11-07)

Full bytecode-verified briefs: `docs/research/sdk-history-sync.md` and `docs/research/sdk-settings-notifications.md`.
The rules that matter most:

| # | Rule | Where it's handled |
|---|---|---|
| H1 | T30 = origin protocol 0 → pass `IOriginDataListener`. `IOriginData3Listener` (what the demo uses) is **silently ignored** | `HistoryClient.readOriginDay` |
| H2 | Sleep, workout, step, profile reads have **no SDK timeout**; origin has a silent 35 s per-day timer; there is **no cancel API** | `HistoryClient.watched()`: inactivity watchdog + hard cap, 3 s pause after a watchdog trip |
| H3 | The SDK doesn't remember what it read. Resume cursor = `packageNumber` per date | SharedPreferences `history_cursor` (`pos_<date>`, `done_<date>`, `sport_crc_<slot>`) |
| H4 | `OriginHalfHourData` is misleading (null date, partial totals, unaggregated BP) | Not stored; we store 5-minute rows and aggregate ourselves |
| H5 | Sleep `day` arg is "0"/"1"…, `sleepLine` is one char per **5 min** ('0' light, '1' deep, '2' awake); one day can hold several segments | `SleepData.toEntity` |
| S1 | Unsupported setters **return silently** (no callback). Every command has a timeout; `setShowFunctionNotSupportToast(false)` is called at init | `VeepooClient.command()`, `ensureInit` |
| S2 | One SDK listener slot per listener type; the latest call wins | Calls are serialized; camera uses one long-lived listener |
| S3 | Personal settings, notification switches, alarms are **whole-object writes** → read, modify, write | `WatchSettingsClient` keeps `lastCustom`, `lastSocial`, `lastAlarms` |
| S4 | **24 h** is byte 3 of the personal packet (`changeCustomSetting`), not the password-step flag | Watch settings → 24-hour clock |
| S5 | Calls must use `ContentPhoneSetting`, SMS `ContentSmsSetting`; wrong class = silently dropped. Title ≤ 70 B, title+":"+body ≤ 280 B (UTF-8) | `WatchSettingsClient.push` trims on code-point boundaries |
| S6 | `readAlarm2` may never answer (cache CRC matches) → fall back to `getAlarm2List()`; `addAlarm2` picks the ID from the SDK cache, so read first | `readAlarms`, `saveAlarm` |
| S7 | Alarm repeat string: doc says rightmost char = Monday; demo builds it the other way. **Unverified** | `WatchSettingsClient.repeatString` |
| S8 | No DND API; no adjustable HR interval on the T30 (package 4 never sent) | Phone-side DND check in `NotificationForwarder` |
| S9 | `clearDeviceData` = factory reset, no guard; `powerOffDevice` often "succeeds" by disconnecting | Not exposed |

Firmware check (read-only, no watch contact): `docs/research/firmware-version-check.md`. Result 2026-11-07: **no newer firmware** for device 3238.
