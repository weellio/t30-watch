# Veepoo / H Band / T30: what exists beyond the official SDK

Researched 2026-11-07. Sources: GitHub (`gh search`), Codeberg Gadgetbridge tracker, web, and a
string scan of our own `vpprotocol-2.3.95.15.aar`. Nothing was downloaded or flashed apart from text files.

## Bottom line

| Question | Answer |
|---|---|
| A better open-source stack for the T30? | **No.** Everything found either wraps the same SDK or reverse-engineers the same opcodes the SDK already sends. |
| Most useful outside source | **Gadgetbridge Veepoo PR (#7100) + fork**: a full byte-level opcode map for the `f008` protocol. Good for decoding raw logs and checking what the SDK sends. |
| Hidden features worth trying on the T30 | `sendCustomCMD`, `bleDeviceRename`, `settingScreenLight`, `setMotorLevel`, `pushTextMsg`, `readOriginData*` (5-minute raw data), extra `CustomSetting` toggles. All of these are in the SDK already, and none are in our support dump. |
| Raw PPG / accelerometer | The SDK has the APIs (`readPPGRawData`, `startPPGRealTimeTransmission`). Veepoo said in SDK issue #22 that only "a very small number of customized devices" support them. Expect UNSUPPORT on the T30. |
| Custom firmware | **None found for any Veepoo watch.** |
| Firmware version server | Yes, at `vphband.com`. Use the SDK's own `getOadVersion` call to read it (details below). |
| Surprise | The SDK has a **phone-home** call (`System/AddSDKUserInfo`), and our merged manifest has INTERNET because of a JieLi library. See "Privacy". |

## 1. Open-source projects

| Repo | What it is | Updated | Useful to us? |
|---|---|---|---|
| [Gadgetbridge PR #7100](https://codeberg.org/Freeyourgadget/Gadgetbridge/pulls/7100) (and closed #6789), fork [dpile103/Gadgetbridge-Veepoo](https://codeberg.org/dpile103/Gadgetbridge-Veepoo) branch `mr-veepoo-kr96pro` | Native Gadgetbridge driver for the Veepoo KR96 Pro (G Band app, Nordic, device 5931). No SDK. Covers steps, sleep, HR, BP, SpO2, ECG, alarms (0xB9), notifications (0xC2), find device (0xB5), auto-measure (0xB3), custom settings (0xB8), and workouts (0xDA). The author says it was written with AI help. Open WIP, no maintainer review yet | Oct 2026 | **Yes, as a reference.** `devices/veepoo/VeepooConstants.java` is the best public opcode and byte-layout doc. It might run the T30 one day, but it's KR96-only and unmerged. |
| [cristianfodor-cloud/HeartBeets](https://github.com/cristianfodor-cloud/HeartBeets) `driver-veepoo/VeePooProtocol.kt` | Raw-BLE Veepoo driver taken from btsnoop captures of H Band (ET585). Lists H Band's whole post-connect init sequence: A1 auth+time, A3 person, E1 long-seat, AA wrist, B1 brightness, B4 screen timeout, AB alarm, D8 status, E0 sleep, D0 live HR | Jun 2026 | Yes. A simple list of what H Band sends, in order. |
| [whad-team/whad-ble-workshop](https://github.com/whad-team/whad-ble-workshop) `clients/b36.py` | Python client for a Veepoo "B36" band from a BLE-hacking workshop. A1 version/auth, C2 notifications with **arbitrary icon 1–15 and free text**, C7 built-in face switch (0–4) | Jul 2025 | Small. Shows that C2 takes any text. |
| [Jelp200/BTN](https://github.com/Jelp200/BTN) | C# backend + docs for an H Band watch over raw GATT (A1/A7/D0/D2) | Oct 2026 | Low |
| [fraschizzato/MyHBand](https://github.com/fraschizzato/MyHBand) (+ MyHBandPC) | Android app on the official SDK. Room archive, keeps raw SDK payloads, CSV/JSON export, server sync | Sep 2026 | **Closest to our project.** Worth a skim for sync/dedup ideas. Nothing new on the protocol. |
| [geekswamp/flutter_veepoo_sdk_plus](https://github.com/geekswamp/flutter_veepoo_sdk_plus) | Flutter wrapper, Android only, small subset (HR, SpO2, battery) | Feb 2025 | No |
| [Salvatorebgg/expo-hband-bridge](https://github.com/Salvatorebgg/expo-hband-bridge) | Expo/React Native bridge to the Android SDK | Sep 2026 | No |
| [HBandSDK/iOS_Ble_SDK](https://github.com/HBandSDK/iOS_Ble_SDK), [WeChat](https://github.com/HBandSDK/WeChat_Mini_Program_Ble_SDK), [uni-app](https://github.com/HBandSDK/uni-appSDK), [HarmonyOS](https://github.com/HBandSDK/HarmonyOS_BLE_SDK) | Official SDKs for other platforms. Same features. The WeChat JS bundle is obfuscated | Nov 2026 | No new commands |
| [hpcslag/V07S-hband-decompile-for-arduino](https://github.com/hpcslag/V07S-hband-decompile-for-arduino) | 2017 decompile of the H Band protocol for an Arduino | 2017 | Historical only |
| iOS apps on the SDK: Risego2000/VeepooHealthBridge, fatih-uzlmz/personal-smart-band, yifwudeswa/ringwood | Wrappers around the official SDK | 2026 | No |

Not found: any Home Assistant or ESPHome integration, or a bleak library for Veepoo.
The Python code that does exist (b36.py, Qibo-Lingshu `health_collector.py`) is one-off scripts.
Gadgetbridge issue #4422 is a *different* "T30" (a FitPro watch), not ours.

### Opcode cheat-sheet (all on `f0080003` write / `f0080002` notify, 20-byte frames, byte[0] = opcode)
A0 battery · A1 password+time · A3 person info · A5 time · A7 feature report (pushed) · A8 live steps ·
AA wrist-raise · AB alarm · AC HR warning · B1 brightness · B3 auto-measure / all-set · B4 screen timeout ·
B5 find device · B8 custom settings · B9 text alarms · C2 notification text · C7 built-in face index ·
C8 weather · D0 live HR · D1/DF 5-minute daily data · D4 sport history · D8 today's totals ·
E0 sleep · E1 sedentary · 90 BP · 80 SpO2 · 93 ECG/body composition · FC production info.
The second service, `f0020001` ("light-origin"), carries **raw ECG/PPG waveform streams**, not OTA
(from the Gadgetbridge comments). That answers our "unconfirmed" note in CLAUDE.md.

## 2. Hidden or undocumented features already in our SDK

Found by scanning `VPOperateManager` method names and the English API doc. **Gate every one on the support dump, and try each one once by hand.**

| Feature | SDK call | T30 chance | Risk |
|---|---|---|---|
| Free-text message to the watch | `pushTextMsg`, `sendSocialMsgContent` with type OTHER | Good (Other is ON) | Safe |
| Rename the BLE name | `bleDeviceRename(name, …)` | Unknown | Low. Re-scan by MAC afterwards |
| Screen brightness levels / night schedule | `readScreenLight` / `settingScreenLight` (`isSupportScreenlight`) | Unknown, read first | Safe |
| Vibration strength | `readMotorLevel` / `setMotorLevel` | Unknown | Safe |
| Hidden toggles in `CustomSetting` | `changeCustomSetting`: stopwatch, long-press lock, message screen-on, find-phone UI, disconnect reminder, SOS | Any field that isn't UNSUPPORT is settable, even if H Band hides it | Safe |
| Raw 5-minute data (HR, BP, steps, …) | `readOriginData`, `readOriginDataSingleDay`, `readOriginDataBySetting` | Yes (this is the history path) | Safe. SDK issue #41 reports these calls going silent in a long-running background service; plan a reconnect/timeout |
| Auto-measure interval below 30 min | `setAutoMeasureSettingData` | Probably not (package 4 is missing) | Safe |
| Raw PPG / IMU | `readPPGRawData`, `startPPGRealTimeTransmission`, `startGsensorSport` | Very low (see bottom line) | Safe to probe |
| Raw command | `sendCustomCMD` | Lets us send any opcode from the table above through the SDK's own queue | **Medium.** Read-only opcodes only |
| Do-not-disturb | **No API and no CustomSetting field.** The only "Dnd" call is for ECG patches | No | — |
| Factory reset / power off | `resetDeviceData`, `powerOffDevice` | Yes | **Reset wipes the watch's data.** Hide it behind a confirm, or leave it out |

## 3. Custom firmware and chip families

- **No custom or alternative firmware exists for any Veepoo watch.** The custom-firmware projects
  for cheap watches (fanoush/ds-d6, ATCwatch for the P8, chrzwatch) target other OEMs' nRF52 boards.
- Chip families named inside the SDK: `ECpuType` = `NORIC`, `HUITOP`, `UNKONW`. `ECPUPlatform` =
  `NORDIC`, `GOODIX`. Separate OTA stacks: **JieLi** (`JL_*`, `jl_bt_ota`), **Nordic** (bundled Nordic
  DFU + `McuMgrOtaManager` for newer Zephyr parts), **Bluetrum** (`ZkOtaManager`; ZK = 中科蓝讯),
  and **Goodix** (`libdfu`, `libfastdfu`). The KR96 also shows a "Sinilink" handshake service.
- The T30 reports `UNKONW` / cpuType 0 and is not JieLi. That matches older Nordic/HUITOP-style firmware.
  Firmware images from these vendors are unsigned and model-specific. A wrong image bricks the watch.
- **Risky repo:** [mocikate/come-to-telegram-rickluminari1--hbandfw](https://github.com/mocikate/come-to-telegram-rickluminari1--hbandfw)
  hosts dozens of H Band firmware zips under a spam-style name. **Untrusted. Do not download it or flash from it.**
  [OpenWearOTA](https://pypi.org/project/openwearota/) (alpha, multi-chip OTA tool) is also off-limits for this watch.

## 4. Firmware version server

| Item | Value |
|---|---|
| SDK entry point | `getOadVersion(OadSetting, OnGetOadVersionListener)`. Fields: `deviceAddress`, `deviceNumber`=3238, `deviceVersion`=03.01.01, `deviceTestVersion`, `isOadModel`, `isDebug`, `hostUrl`, `isAutoDownload` |
| Base URLs in the SDK | `https://www.vphband.com:9001/api/` (release), `http://120.79.208.77:88/api/` (probably `isDebug`) |
| Path | `Version/GetVersionForSport`. Exact query keys not confirmed. Strings near it: `DeviceType`, `devicetype`, `type`, `android`, `testVersion`, `Version3` |
| Response | JSON with `Version3{NewVersion, NewUrl, NewMD5, NewSize, Des, DeviceType, …}` → `onNetOadInfo(deviceNumber, version, des, netIsNew)` |
| File URL pattern | `https://www.vphband.com/<folder>/<PRODUCT>_<8-digit version>_<deviceType>.zip`, e.g. `vph_2/VPH_2_00063300_14.zip` (from atc1441's 2019 gist [HBand Firmware URLS](https://gist.github.com/atc1441/48ccd3f7065c9b27f0841163cb12b267)) |
| Other endpoints | Watch faces: `system/getthemes` (deviceNumber, deviceVersion, appType, appVersion, binProtocol, dialShape). AGPS: `starcourse.location.io` / `rx-networks.cn` (not used on the T30) |
| Safe use | A read-only "is there newer firmware?" line on the Debug screen: `isAutoDownload=false`, show the result, never download or flash (phase 9) |

## Privacy

- `com.veepoo.protocol.util.vp_s` posts `System/AddSDKUserInfo` to vphband.com with DeviceNumber,
  DeviceTestVersion, PhoneModel, Android version, and SDK version. It looks like a phone-home; I didn't confirm when it fires.
- Our merged manifest **does have `INTERNET`**. It comes from `jl_bt_ota_V1.10.0_10931-release.aar`,
  not from our own manifest (see `app/build/outputs/logs/manifest-merger-debug-report.txt`).
- Fix if wanted: `<uses-permission android:name="android.permission.INTERNET" tools:node="remove"/>`.
  That blocks the telemetry and also the `getOadVersion` check. The app needs no network otherwise:
  Health Connect is on-device and CSV export goes through the share sheet.
