# T30 / Veepoo SDK brief: watch settings, notifications, extras

SDK: `vpprotocol-2.3.95.15` (signatures checked with javap). Demo: `Demo/VpBluetoothSDK`. Doc: `sdkdoc/VeepooSDK Android Api - English.md`.
Packages: `com.veepoo.protocol.{model.settings, model.datas, model.enums, listener.data, listener.base}`.
`IHealthRemindListener` is in `com.veepoo.protocol.listener` (not `.data`).

Notes on wording:
- **write ack** = `IBleWriteResponse.onResponse(code)`. `code == 0` (`Code.REQUEST_SUCCESS`) only means the BLE write went out.
- **device ack** = the data listener firing with a SUCCESS status. That is the real confirmation.

---

## 0. Rules that apply to every call below (read first)

| # | Gotcha | Evidence | What to do |
|---|---|---|---|
| G1 | **Unsupported means silent.** Most setters start with `FunctionCheckUtil.checkXxx()`. If the support flag is false, the method just `return`s: no write ack, no callback. It may also show a Toast. | bytecode of `startCamera`, `settingLongSeat`, `addAlarm2`, `setScreenLightTime`, `settingNightTurnWriste`, `settingHeartWarning`, `settingWomenState`, `setAutoMeasureSettingData` | Give every queued command a timeout. Call `VPOperateManager.setShowFunctionNotSupportToast(false)` once. |
| G2 | **One listener slot per listener type.** Passing a listener stores it in one SDK field (`vp_c.vp_a(listener)` → `putfield`). The next call with the same listener type **replaces** it. That includes `setCameraListener` vs `startCamera`, and `confirmDevicePwd`'s `ICustomSettingDataListener` vs `readCustomSetting`. | `com.veepoo.protocol.vp_c` | Keep **one long-lived listener object per type**, pass that same instance to every call, and route inside it (to the pending request, plus a SharedFlow for unsolicited events). |
| G3 | **Some writes send several packets, and each packet fires `onResponse`.** That covers `changeCustomSetting` (packet list), `sendSocialMsgContent` (up to 20 packets), and `settingSocialMsg` (1–2 packets). | `operate.vp_u.vp_a`, `operate.vp_cl.vp_a` loops calling `send(...)` with the same response | Your `Once` wrapper must ignore repeat `onResponse` calls. A non-zero code on any packet counts as a failure. |
| G4 | **Read, modify, write.** `CustomSetting`, `FunctionSocailMsgData` and the alarm list are whole-object writes. Fields you don't set go out as defaults (`UNSUPPORT`/false). | constructors | Always build the write from the last object you read. |
| G5 | **Kotlin names with typos.** `OnSettingDataChange` and `OnCameraDataChange` start with a capital O. Others: `onLowpowerDataDataChange`, `cliencePhone`, `setttingCheckWear` (3 t), `LongSeatSetting.startMiute`, `NightTurnWristeData.isNightTureWirsteStatusOpen`, `ECameraStatus.OPEN_FALI`, `UNKONW`. | javap | Copy names exactly. |

---

## 1. Personalization: 24h, metric/imperial, auto HR, auto BP, skin, SOS, disconnect reminder

### Signatures
```java
void readCustomSetting(IBleWriteResponse, ICustomSettingDataListener)
void changeCustomSetting(IBleWriteResponse, ICustomSettingDataListener, CustomSetting)
// also delivered once during confirmDevicePwd(..., ICustomSettingDataListener, pwd, is24h) (the 7/8-arg overloads)
```
### Listener (1 method)
```kotlin
interface ICustomSettingDataListener { fun OnSettingDataChange(data: CustomSettingData) }   // capital O
```
`CustomSettingData.status: ECustomStatus` = `FAIL | SETTING_SUCCESS | READ_SUCCESS | UNKNOW`.

### `CustomSettingData` (what you read). Kotlin accessors
| Accessor | Type | Meaning |
|---|---|---|
| `isIs24Hour` / `setIs24Hour(Boolean)` (also `is24Hour()` method) | Boolean | 24 h clock |
| `metricSystem` | EFunctionStatus | `SUPPORT_OPEN` = metric, `SUPPORT_CLOSE` = imperial, `UNSUPPORT` |
| `autoHeartDetect`, `autoBpDetect` | EFunctionStatus | automatic HR / BP monitoring switches |
| `skin` + `skinLevel` (Int) | EFunctionStatus | wear-detect skin tone |
| `disconnectRemind`, `sos` (`getSOS()`) | EFunctionStatus | |
| others: `sportOverRemain, voiceBpHeart, findPhoneUi, secondsWatch, lowSpo2hRemain, autoHrv, autoIncall, ppg, musicControl, longClickLockScreen, messageScreenLight, autoTemperatureDetect, temperatureUnit, ecgAlwaysOpen, bloodGlucoseDetection, metDetect, stressDetect, bloodGlucoseUnit, bloodComponentDetect, uricAcidUnit, bloodFatUnit, fallDetection, gsrUnit` | | leave untouched on the T30 |

`EFunctionStatus` = `UNSUPPORT, SUPPORT, SUPPORT_OPEN, SUPPORT_CLOSE, UNKONW`. Helpers: `isHaveFunction()`, `isOpen()`, `isClose()`.

### `CustomSetting` (what you write)
Constructors:
```java
CustomSetting(CustomSettingData)                       // <-- USE THIS (read-modify-write, what the v2 demo does)
CustomSetting(boolean isHaveMetricSystem, boolean isMetric, boolean is24Hour, boolean autoHeart, boolean autoBp)
CustomSetting(5 booleans, sportRemain, voiceBpHeart, findPhoneUI, stopWatch, spo2hLowRemind,
              wearDetectSkin, autoInCall, autoHRV, disconnectRemind)     // 14-arg; positional order is
              // ...skin, AUTO_IN_CALL, AUTO_HRV, DISCONNECT (the doc table lists HRV before InCall; bytecode says InCall first)
(+15-arg, +17-arg variants)
```
`CustomSetting(data)` copies: `isHaveMetricSystem = data.metricSystem.isHaveFunction()`, `isMetricSystem = data.metricSystem.isOpen()`, `is24Hour = data.isIs24Hour`, `isOpenAutoHeartDetect = data.autoHeartDetect.isOpen()`, and so on for every EFunctionStatus field, plus `skinType = data.skinLevel`.
Setters on CustomSetting: `setIs24Hour, setMetricSystem(Boolean), setHaveMetricSystem, setOpenAutoHeartDetect, setOpenAutoBpDetect, setIsOpenWearDetectSkin, setSkinType(Int), setIsOpenDisconnectRemind, setIsOpenSOS, ...`.

### Recipe
```kotlin
// 1) read (or reuse the packet from confirmDevicePwd)
val cur: CustomSettingData = repo.readCustomSetting()             // waits for READ_SUCCESS
// 2) modify the DATA object, then wrap it
cur.setIs24Hour(true)
cur.metricSystem = EFunctionStatus.SUPPORT_OPEN                   // metric; SUPPORT_CLOSE = imperial
cur.autoHeartDetect = EFunctionStatus.SUPPORT_OPEN
cur.disconnectRemind = EFunctionStatus.SUPPORT_CLOSE
vp.changeCustomSetting(writeResp, customListener, CustomSetting(cur))
// 3) done when OnSettingDataChange(status == SETTING_SUCCESS) arrives; it carries the new state
```
Doc rule: if a field is unsupported you still send it as `UNSUPPORT`. `CustomSetting(data)` does that for you.

### Wire format (from `operate.vp_u`)
Command `0xB8`, byte1 = 1 (write).
- byte2: metric `1`, imperial `2`.
- byte3: 24 h `1`, 12 h `2`.
- The auto-HR byte is only written when `isSupportFivemiuteHeart()` is true.

### Units / quirks
- Imperial only shows on the watch when the watch language is English or Traditional Chinese (doc).
- Skin: `SUPPORT_OPEN` = light skin, `SUPPORT_CLOSE` = dark. `skinType` 0–6 only counts when `VpSpGetUtil.getVpSpVariInstance(ctx).getSkinType() == 2`.
- **Your open 24 h question.** The `is24Hour` boolean in `confirmDevicePwd` goes with the password/time-sync command. The 12/24 flag the watch reports is byte3 of the `0xB8` personalization packet, which only `changeCustomSetting` writes. So if `confirmDevicePwd(..., true)` left `is24Hour=false`, send `changeCustomSetting(CustomSetting(data.apply { setIs24Hour(true) }))` and re-read.
- Second lever: `settingTime(IBleWriteResponse, IResponseListener, DeviceTimeSetting(y,mo,d,h,mi,s, ETimeMode.MODE_24))`. Command `0xA5` carries a time-mode byte. `ETimeMode = MODE_12 | MODE_24`. `IResponseListener.response(int)`.

---

## 2. Auto heart-rate detection interval: **not available on the T30**

- The only HR auto switch is `CustomSetting.isOpenAutoHeartDetect` (on/off). The SDK calls it "five-minute heart" (`isSupportFivemiuteHeart`). The demo labels it "5-minute measurement switch (HR/BP)". So the interval is fixed at 5 min (it matches the 5-minute daily data).
- An interval API exists but is gated:
  ```java
  void readAutoMeasureSettingData(IBleWriteResponse, IAutoMeasureSettingDataListener)
  void setAutoMeasureSettingData(IBleWriteResponse, AutoMeasureData, IAutoMeasureSettingDataListener)
  // listener: onSettingDataChange(List<AutoMeasureData>), onSettingDataChangeFail(), onSettingDataChangeSuccess()
  // AutoMeasureData: funType(EAutoMeasureType.PULSE_RATE..), isSwitchOpen, measureInterval(min), stepUnit,
  //   isIntervalModify, isSlotModify, supportStart/EndMinute, currentStart/EndMinute, sensitivity, lowOxygenAlertSwitch
  ```
  Both are guarded by `checkB3()` → `isSupportAutoMeasure()`, a flag in feature package 4. The T30 never sends packages 3–5, so these calls return silently (G1).
- Others ruled out:
  - `readAutoDetectStateFromS22` / `setAutoDetectStateToS22`: S22 only.
  - `setHealthAlarmInterval`: G08W demo, temperature/BP/SpO2 ceilings, not an interval.
  - `settingSpo2hAutoDetect` (`EAllSetType` has only `SPO2H_NIGHT_AUTO_DETECT`).

---

## 3. Do-not-disturb: **no API exists**

No method, class, enum or doc entry for "disturb", "DND" or 勿扰 exists in:
- `vpprotocol-2.3.95.15` (VPOperateManager dump plus a class-name scan)
- the English API doc and the Chinese API doc
- the integration doc and the demo source

Options:
- **Phone side (recommended):** in your NotificationListenerService, skip forwarding when `getCurrentInterruptionFilter() != INTERRUPTION_FILTER_ALL`, or during an in-app quiet-hours window.
- **Watch-side partial substitutes:**
  - the wrist-raise time window (§5)
  - the sedentary window (§7)
  - turning categories off (§9.2)

---

## 4. Screen-on duration (supported). Brightness `settingScreenLight` is NOT supported

```java
void readScreenLightTime(IBleWriteResponse, IScreenLightTimeListener)
void setScreenLightTime(IBleWriteResponse, IScreenLightTimeListener, int seconds)
interface IScreenLightTimeListener { void onScreenLightTimeDataChange(ScreenLightTimeData) }
```
- `ScreenLightTimeData`: `screenLightState: EScreenLightTime`, `currentDuration`, `recommendDuration`, `maxDuration`, `minDuration` (all seconds).
- `EScreenLightTime` = `SETTING_SUCCESS, SETTING_FAIL, READ_SUCCESS, READ_FAIL, UNKONW`.
- Read first and clamp the slider to `[minDuration, maxDuration]`. Guard: `isSupportScreenlightTime`.

---

## 5. Wrist-raise to wake (`nightTurnSetting`)

```java
void readNightTurnWriste(IBleWriteResponse, INightTurnWristeDataListener)
void settingNightTurnWriste(IBleWriteResponse, INightTurnWristeDataListener, NightTurnWristSetting)  // USE THIS
// deprecated: (…, boolean isOpen) and (…, boolean, TimeData start, TimeData end)
interface INightTurnWristeDataListener { void onNightTurnWristeDataChange(NightTurnWristeData) }
```
- `NightTurnWristSetting(isOpen: Boolean, startTime: TimeData, endTime: TimeData, level: Int)`. There is also a no-arg constructor with setters.
- **level 1–10, default 5.** Use `defaultLevel` from the read.
- `NightTurnWristeData` fields:
  - `oprateStauts: ENightTurnWristeStatus` (`SUCCESS | FAIL | UNKONW`), the same status for both read and set
  - `isSupportCustomSettingTime` (false means the start/end window is ignored)
  - `isNightTureWirsteStatusOpen`, `startTime`, `endTime`, `level`, `defaultLevel`
- `TimeData(hour, minute)`: the 2-int constructor sets `hour` and `minute` (verified).
- Demo: `NightTurnWristSetting(true, TimeData(20,0), TimeData(8,0), 5)`.

---

## 6. Heart-rate alarm (`heartWaring`)

```java
void readHeartWarning(IBleWriteResponse, IHeartWaringDataListener)
void settingHeartWarning(IBleWriteResponse, IHeartWaringDataListener, HeartWaringSetting)
interface IHeartWaringDataListener { void onHeartWaringDataChange(HeartWaringData) }
```
- `HeartWaringSetting(heartHigh: Int, heartLow: Int, isOpen: Boolean)`, in bpm. **Argument order is high first.**
- The demo rejects `low >= high`. Doc example `(120, 40, true)`.
- `HeartWaringData`: `status: EHeartWaringStatus`, `isOpen`, `heartHigh`, `heartLow`.
- `EHeartWaringStatus` = `OPEN_SUCCESS, OPEN_FAIL, CLOSE_SUCCESS, CLOSE_FAIL, READ_SUCCESS, READ_FAIL, UNSUPPORT, UNKONW`.
- Success on write = `OPEN_SUCCESS` (when isOpen) or `CLOSE_SUCCESS`.

---

## 7. Sedentary reminder (`longseat`)

```java
void readLongSeat(IBleWriteResponse, ILongSeatDataListener)
void settingLongSeat(IBleWriteResponse, LongSeatSetting, ILongSeatDataListener)   // NOTE: setting before listener
interface ILongSeatDataListener { void onLongSeatDataChange(LongSeatData) }
```
- `LongSeatSetting(startHour, startMinute, endHour, endMinute, threshold, isOpen)`. All Int except isOpen. Getter typo: `startMiute`.
- **One time window.** `threshold` = minutes without movement before the buzz. Demo hint: 10–120. Doc example: `LongSeatSetting(10,40,12,40,40,false)`. Read back the echoed value, the firmware may round it.
- `LongSeatData`: `status: ELongSeatStatus`, `startHour, startMinute, endHour, endMinute, threshold, isOpen`.
- `ELongSeatStatus` = `OPEN_SUCCESS, OPEN_FAIL, CLOSE_SUCCESS, CLOSE_FAIL, READ_SUCCESS, READ_FAIL, UNSUPPORT, UNKONW`.
- Alternative API `readHealthRemind` / `settingHealthRemind` (`HealthRemindType.SEDENTARY` and others) is gated by `FunctionDeviceSupportData.healthRemind` (check your dump; most likely UNSUPPORT on the T30). Its listener has **8** methods: `functionNotSupport, onHealthRemindRead, onHealthRemindReadingComplete, onHealthRemindReadFailed, onHealthRemindReport, onHealthRemindReportFailed, onHealthRemindSettingSuccess, onHealthRemindSettingFailed(HealthRemindType)`. The doc omits `onHealthRemindReadingComplete`. `HealthRemind(type, TimeData start, TimeData end, interval: Int, status: Boolean)`. **Use LongSeat on the T30.**

---

## 8. Alarms: scene/new alarm (`alarm2`). Old 3-alarm and text alarms are not for this watch

The device supports exactly one alarm type, reported at password time. T30 = Alarm2.

```java
void readAlarm2  (IBleWriteResponse, IAlarm2DataListListener)
void addAlarm2   (IBleWriteResponse, IAlarm2DataListListener, Alarm2Setting)
void modifyAlarm2(IBleWriteResponse, IAlarm2DataListListener, Alarm2Setting)
void deleteAlarm2(IBleWriteResponse, IAlarm2DataListListener, Alarm2Setting)
List<Alarm2Setting> getAlarm2List()                               // SDK's local cache
void setOnDeviceAlarm2ChangedListener(OnDeviceAlarm2ChangedListener)
interface IAlarm2DataListListener { void onAlarmDataChangeListListener(AlarmData2) }
interface OnDeviceAlarm2ChangedListener { void onDeviceAlarm2Changed() }   // user edited on watch -> call readAlarm2
```
### `Alarm2Setting`
All fields are public, with setters.
```java
Alarm2Setting()                                                     // + setters (demo style)
Alarm2Setting(int hour, int minute, String repeatStatus, int scene, String unRepeatDate, boolean isOpen)     // add
Alarm2Setting(int alarmId, int hour, int minute, String repeatStatus, int scene, String unRepeatDate, boolean isOpen) // modify/delete
fields: MAFlag, BluetoothAddress, alarmId(1–20), alarmHour, alarmMinute (24h), repeatStatus, unRepeatDate, scene, isOpen
helpers: getRepeatIntStatus() = Integer.valueOf(repeatStatus, 2); implements Comparable (sort by time)
```
| Field | Format |
|---|---|
| `repeatStatus` | 7-char `0/1` string. Doc (EN and CN): **read right to left = Mon, Tue, Wed, Thu, Fri, Sat, Sun**, so the rightmost char is Monday (bit0) and the leftmost is Sunday. `"0000011"` = Tue+Mon. `"1100000"` = Sun+Sat. `"0011111"` = weekdays. `"0000000"` = one-shot. |
| `unRepeatDate` | `"yyyy-MM-dd"` for a one-shot. `"0000-00-00"` when repeating. |
| `scene` | 0–20. 0 = plain alarm icon (1 sleep, 2 get up, 3 drink water, 4 medicine, 5 date, 6 read, 7 movie, 8 music, 9 shopping, 10 haircut, 11 birthday, 12 propose, 13 work, 14 parenting, 15 family trip, 16 save money, 17 doctor, 18 dog walk, 19 fishing, 20 travel). Use 0 unless the T30 shows icons. |
| max count | **20**. SDK constant in `Alarm2Util.isAlarmListFull`. |

**Bit-order conflict.** The v2 demo (`NewAlarmActivity.getRepeatState`) builds the string left to right as Mon..Sun, which is the opposite of the doc. The SDK only does `Integer.valueOf(s, 2)`, so the firmware decides. Follow the doc. **Test on the watch:** add an alarm with `"0000001"` and check whether the watch shows Monday (doc) or Sunday (demo).

### `AlarmData2`
- `oprate: EMultiAlarmOprate` and `alarm2SettingList` (the full list after the operation).
- `EMultiAlarmOprate` values:
  - `SETTING_SUCCESS, SETTING_FAIL` (add/modify)
  - `CLEAR_SUCCESS, CLEAR_FAIL` (delete)
  - `READ_SUCCESS, READ_FAIL, READ_SUCCESS_NULL, READ_SUCCESS_SAVE, READ_SUCCESS_SAME_CRC`
  - `ALARM_FULL, ALARM_REPORT, ALARM_REPORT_DATE_ERROR, DEVICE_ALARM_MODIFY`
  - `DEVICE_ADD_ONE_TEXT_ALARM, DEVICE_DELETE_ONE_TEXT_ALARM, DEVICE_TEXT_ALARM_SWITCH_CHANGED, UNKONW`

### Alarm gotchas
- **Read may never call back.** Javadoc: when the SDK's cached CRC matches the device, the device sends nothing, so there is no callback. Treat `READ_SUCCESS | READ_SUCCESS_SAVE | READ_SUCCESS_SAME_CRC | READ_SUCCESS_NULL` as OK. **On timeout, fall back to `getAlarm2List()`.**
- **Add assigns the ID itself.** `addAlarm2` ignores your `alarmId`. It uses `Alarm2Util.getUnAddId(getAlarm2List())` and also sets `MAFlag` and `BluetoothAddress`. So **always `readAlarm2` before adding**, or IDs collide.
- **`ALARM_FULL` comes back synchronously.** If the cached list already has 20, `addAlarm2` calls your listener with `ALARM_FULL` immediately: no BLE write, no write ack. Don't wait for `onResponse` before reading the listener result.
- **Modify and delete need the real `alarmId`.** Take the object from the last list and mutate it, as the demo does with `mSettings.get(position)`.

---

## 9. Notifications

### 9.1 Category switches (what the watch will show)
```java
void readSocialMsg   (IBleWriteResponse, ISocialMsgDataListener)
void settingSocialMsg(IBleWriteResponse, ISocialMsgDataListener, FunctionSocailMsgData)
FunctionSocailMsgData getFunctionSocailMsgData()        // SDK cache after pwd; null -> call readSocialMsg
void setFunctionSocailMsgData(FunctionSocailMsgData)
void settingSocialMsgDataListener(ISocialMsgDataListener) // global slot (G2)
interface ISocialMsgDataListener {
    void onSocialMsgSupportDataChange (FunctionSocailMsgData)  // packet 1  <-- the T30 only sends this one
    void onSocialMsgSupportDataChange2(FunctionSocailMsgData)  // packet 2 (watches with >17 apps)
}
```
The same listener answers both read and set; there is no separate status enum. Success = callback received after the set. Compare the fields to what you sent.

**`FunctionSocailMsgData` Kotlin properties.** All are `EFunctionStatus`, default `UNSUPPORT`.

| Property | ESocailMsg | T30 |
|---|---|---|
| `phone` | `PHONE` (0x00) | supported, ON |
| `msg` | `SMS` (0x01) | supported, ON |
| `wechat` | `WECHAT` (0x02) | supported |
| `qq` | `QQ` (0x03) | supported |
| `sina` | `SINA` (0x04) | no |
| `facebook` | `FACEBOOK` (0x05) | supported |
| `twitter` | `TWITTER` (0x06) | supported |
| `flickr` | `FLICKR` (0x07) | no |
| `linkin` (field `Linkin`) | `LINKIN` (0x08) | supported |
| `whats` | `WHATS` (0x09) | supported |
| `line` | `LINE` (0x0A) | supported, ON |
| `instagram` | `INSTAGRAM` (0x0B) | supported |
| `snapchat` | `SNAPCHAT` (0x0C) | supported |
| `skype` | `SKYPE` (0x0D) | supported |
| `gmail` | `GMAIL` (0x0E) | supported |
| `dingding` | `DINGDING` (0x0F) | no |
| `wxWork` | `WXWORK` (0x10) | no |
| `other` | `OTHER` (0x11) | supported, ON |
| `tikTok` | `TIKTOK` (0x12) | no |
| `telegram` | `TELEGRAM` (0x13) | no |
| `connected2_me` | `CONNECTED2_ME` (0x14) | no |
| `kakaoTalk` | `KAKAO_TALK` (0x15) | no |
| `shieldPolice` | `SHIELD_POLICE` (0x16) | no |
| `messenger` | `MESSENGER` (0x17) | no |
| `zalo` | `ZALO` (0x18) | no |
| `viber` | `VIBER` (0x19) | no |

Extra enum values: `G15MSG` (0xFE, G15 only) and `APP_CUSTOM_MSG` (0x1A, not documented). Ignore both.

Toggle recipe (G4):
```kotlin
val cur = vp.functionSocailMsgData ?: repo.readSocialMsg()
if (cur.whats != EFunctionStatus.UNSUPPORT) cur.whats = if (on) EFunctionStatus.SUPPORT_OPEN else EFunctionStatus.SUPPORT_CLOSE
vp.settingSocialMsg(writeResp, socialListener, cur)   // done on onSocialMsgSupportDataChange
```
Never flip an `UNSUPPORT` field. Demo bug to avoid: `MessagePushSwitchActivity` writes `FACEBOOK` toggles into `phone`.

### 9.2 Pushing a notification
```java
void sendSocialMsgContent(IBleWriteResponse, ContentSetting)   // fire-and-forget: NO data callback, no device ack
void offhookOrIdlePhone(IBleWriteResponse)                     // call answered/ended -> stop watch ringing UI
```
`ContentSetting` is abstract. **Use the correct subclass.** The SDK casts PHONE to `ContentPhoneSetting` and SMS to `ContentSmsSetting`. A `ContentSocailSetting(PHONE, …)` hits a ClassCastException that the SDK catches, and nothing is sent.

| Kind | Constructor to use | Shows on watch |
|---|---|---|
| Incoming call | `ContentPhoneSetting(ESocailMsg.PHONE, contactName: String?, number: String)` or `ContentPhoneSetting(ESocailMsg.PHONE, number)` | the name if given, otherwise the number |
| SMS | `ContentSmsSetting(ESocailMsg.SMS, contactName: String?, number, content)` or the non-enum `ContentSmsSetting(contactName, number, content)` (javadoc marks the enum overloads deprecated; both exist) | the name if given |
| Any app | `ContentSocailSetting(ESocailMsg.X, title: String, content: String)` | category icon + "title:content" |

Do not use the `(…, int contactLength, int allLength, …)` overloads. The SDK overwrites both values with the cached device values anyway.

**Lengths** (from `operate.vp_cl` and `util.vp_ag`):
- The SDK sets `allLength = VpSpGetUtil.getAllLength()` (T30: `allMsgLength = 20` packets) and `contactLength = getNickNameLength()` (T30: `contactMsgLength = 5` packets).
- Text is encoded **UTF-8, 14 payload bytes per packet**.
- Title is cut to `contactLength × 14` = **70 bytes**.
- Payload = `title + ":" + content`, cut to `allLength × 14` = **280 bytes total**.
- Non-ASCII characters take 2–3 bytes, and emoji take 4.
- The SDK truncates by byte count, which can split a multi-byte character at the end. Pre-trim yourself on a code-point boundary (title ≤ 70 B, title + ":" + body ≤ 280 B) and strip newlines/emoji if they render badly.
- If the title is empty, only the content is sent (and the other way round).

**Does `OTHER` work for arbitrary apps?** Yes, as far as the SDK goes:
- `other` is supported and ON on this T30.
- `sendSocialMsgContent` does **not** check the switch table. It always sends, and the watch filters by its own table.

Map unknown packages (Telegram, Messenger, Signal, banking and so on) to `OTHER`. Put the app name in the title (for example `"Telegram · Alice"`), because the OTHER icon is generic. Mirror the demo and only send when the category's status is `SUPPORT_OPEN`, so your app's toggles match the watch's.

Package → category suggestions:
| Package | Category |
|---|---|
| `com.whatsapp`, `com.whatsapp.w4b` | WHATS |
| `com.facebook.katana` | FACEBOOK (but `com.facebook.orca` Messenger → OTHER) |
| `com.twitter.android` | TWITTER |
| `com.linkedin.android` | LINKIN |
| `jp.naver.line.android` | LINE |
| `com.instagram.android` | INSTAGRAM |
| `com.snapchat.android` | SNAPCHAT |
| `com.skype.raider` | SKYPE |
| `com.google.android.gm` | GMAIL |
| `com.tencent.mm` | WECHAT |
| `com.tencent.mobileqq` | QQ |
| the default SMS app (`Telephony.Sms.getDefaultSmsPackage`) | use ContentSmsSetting, not OTHER |
| everything else | OTHER |

**Call flow:**
1. Ringing (from TelephonyCallback / PhoneStateListener, not from the notification): send `ContentPhoneSetting(PHONE, name, number)`.
2. OFFHOOK or IDLE: call `offhookOrIdlePhone(resp)`. The javadoc says to call it after the user answers or hangs up.
3. Watch buttons arrive through `settingDeviceControlPhone` (§10.1): `rejectPhone()` (hang up), `cliencePhone()` (mute ringer), `appAnswerCall()`.
   - The app acts with `TelecomManager.endCall()` / `acceptRingingCall()`. Both need the runtime permission `ANSWER_PHONE_CALLS` (API 28+).
   - Muting needs `AudioManager` ringer changes (requires DND access) or `TelecomManager.silenceRinger()` (default dialer or MODIFY_PHONE_STATE only).

Throughput: each message is up to 20 BLE packets with no device ack. Push them through the serialized queue, complete on the last write ack (G3), and debounce bursts.

---

## 10. Extras

### 10.1 Watch → phone control events (SOS, call buttons, knocks)
```java
void settingDeviceControlPhone(IDeviceControlPhoneModelState)   // single slot; demo: register BEFORE confirmDevicePwd
void settingPhoneListener(IPhoneListener)                       // separate slot, call buttons only
void settingSOSListener(ISOSListener)                           // separate slot
```
`IDeviceControlPhoneModelState` extends `IKnocknotifyListener, IMusicControlListener, ISOSListener, IPttModelStateListener, IPhoneListener, IMultiEcgModelStateListener`. **All 18 methods must be implemented:**
```kotlin
object : IDeviceControlPhoneModelState {
    override fun knocknotify(type: Int) {}            // 1 single tap, 2 double tap
    override fun nextMusic() {}; override fun previousMusic() {}; override fun pauseAndPlayMusic() {}
    override fun pauseMusic() {}; override fun playMusic() {}; override fun voiceUp() {}; override fun voiceDown() {}
    override fun oprateMusicSuccess() {}; override fun oprateMusicFail() {}
    override fun sos() {}                              // watch SOS pressed (T30 SOS = ON)
    override fun inPttModel() {}; override fun outPttModel() {}
    override fun rejectPhone() {}; override fun cliencePhone() {}; override fun appAnswerCall() {}
    override fun inMultiEcgModel() {}; override fun outMultiEcgModel() {}
}
```
- Music is unsupported on the T30; leave those methods empty and log them.
- It is not clear whether SOS fires through `settingSOSListener` or through this interface, so register both and point them at the same handler.
- The SOS on/off switch is `CustomSettingData.sos` (§1).

### 10.2 Camera shutter
```java
void setCameraListener(ICameraDataListener)                     // global slot (same slot startCamera writes!)
void startCamera(IBleWriteResponse, ICameraDataListener)        // put watch in camera mode
void stopCamera (IBleWriteResponse, ICameraDataListener)
interface ICameraDataListener { void OnCameraDataChange(ECameraStatus) }   // capital O
ECameraStatus = OPEN_SUCCESS, DEVICE_OPEN_SUCCESS, OPEN_FALI, TAKEPHOTO_CAN, TAKEPHOTO_CAN_NOT, CLOSE_SUCCESS, CLOSE_FAIL, UNKONW
```
Flow (javadoc: "enter camera mode → OPEN_SUCCESS, then shaking the device returns TAKEPHOTO_CAN"):
1. The app opens its own CameraX screen and calls `startCamera`. Wait for `OPEN_SUCCESS`. `OPEN_FALI` means it failed.
2. The user shakes or taps the watch, which sends **`TAKEPHOTO_CAN`**. The app must take the picture itself; the SDK does nothing. Debounce about 1 s.
3. `TAKEPHOTO_CAN_NOT` means the watch refused (not ready). Show a hint.
4. When the app leaves the screen, call `stopCamera` and wait for `CLOSE_SUCCESS`. Always stop, otherwise the watch stays in camera UI.
5. `DEVICE_OPEN_SUCCESS` means the user opened camera mode **from the watch menu**. Keep a global `setCameraListener` registered from connect, so you can bring up the camera screen.

Because of G2, pass the **same** listener object to `setCameraListener`, `startCamera` and `stopCamera`.
Guard: `isSupportCamera`. HID (0x1812) is UNSUPPORT on the T30, so the shutter only works through this path.

### 10.3 Language
```java
void settingDeviceLanguage(IBleWriteResponse, ILanguageDataListener, ELanguage)   // no read API exists
interface ILanguageDataListener { void onLanguageDataChange(LanguageData) }
LanguageData: stauts: EOprateStauts (OPRATE_SUCCESS | OPRATE_FAIL | UNKNOW), language: ELanguage
```
- `ELanguage`, in order: `CHINA, CHINA_TRADITIONAL, ENGLISH, JAPAN, KOREA, DEUTSCH, RUSSIA, SPANISH, ITALIA, FRENCH, VIETNAM, PORTUGUESA, THAI, POLISH, SWEDISH, TURKISH, DUTCH, CZECH, ARABIC, HUNGARY, GREEK, ROMANIAN, SLOVAK, INDONESIAN, BRAZIL_PORTUGAL, CROATIAN, LITHUANIAN, UKRAINE, HINDI, HEBREW, DANISH, PERSIAN, FINNISH, MALAY, UNKONW`.
- **English = `ELanguage.ENGLISH`.**
- Doc note: many watches only have a CN/EN system language. Setting another language can return `OPRATE_SUCCESS` while the UI stays English; only the women's-health prompts change.
- Store the last value you set in DataStore, because the language can't be read back.

### 10.4 Women's health (`womenTag 3`)
```java
void settingWomenState(IBleWriteResponse, IWomenDataListener, WomenSetting)
void readWomenState(IBleWriteResponse, IWomenDataListener)
interface IWomenDataListener { void onWomenDataChange(WomenData) }
WomenData: oprateStatus: EWomenOprateStatus (SETTING_SUCCESS, SETTING_FAIL, READ_SUCCESS, READ_FAIL, UNKONW), womenSetting: WomenSetting?  (filled on read)
```
`WomenSetting` constructors, by status:
```java
WomenSetting(EWomenStatus.MENES | PREREADY, int menseLength, int menesInterval, TimeData lastPeriod)          // period / trying
WomenSetting(EWomenStatus.MAMAMI, int menseLength, int menesInterval, TimeData lastPeriod, ESex babySex, TimeData babyBirthday)
WomenSetting(EWomenStatus.PREING, TimeData lastPeriod, TimeData dueDate)                                      // pregnant
```
- `EWomenStatus` = `NONE(0), MENES(1), PREREADY(2), PREING(3), MAMAMI(4)`. `ESex` = `MAN, WOMEN`.
- Dates: `TimeData(year, month 1–12, day)` (verified field mapping).
- `menseLength` = period length in days. Doc range 4–28; demo uses 4–5.
- `menesInterval` = cycle length in days (demo 28).
- Guard: `isSupportWomenSetting`. The meaning of `getWomenTag() == 3` isn't documented.

### 10.5 Sport mode (control and state only)
```java
void readSportModelState(IBleWriteResponse, ISportModelStateListener)
void startMultSportModel(IBleWriteResponse, ISportModelStateListener, ESportType)   // T30: multSportModel supported
void startSportModel    (IBleWriteResponse, ISportModelStateListener)              // single, untyped
void stopSportModel     (IBleWriteResponse, ISportModelStateListener)
void setNewSportDataReportListener(ISportModelStateListener)                        // unsolicited state changes
interface ISportModelStateListener { void onSportModelStateChange(SportModelStateData); void onSportStopped(); }
SportModelStateData: oprateStauts: ECheckWear (OPEN_SUCCESS, OPEN_FAIL, CLOSE_SUCCESS, CLOSE_FAIL, READ_SUCCESS, READ_FAIL, UNKONW),
                     deviceStauts: ESportModelStateStauts (DEVICE_FREE, DEVICE_BUSY, DEVICE_HAD_START_BEFORE, UNKNOW),
                     sportModeType: Int  (ESportType.getSportType(int))
```
- `ESportType` begins `NONE, OUTDOOR_RUNNING, OUTDOOR_WALK, INDOOR_RUNNING, INDOOR_WALK, HIKE, TREADMILLS, OUTDOOR_RIDING, INDOOR_RIDING, ELLIPTICAL, ROWING_MACHINE, Mountaineering, SWIM, …` (about 190 values). The SDK doesn't say which ones the T30 offers.
- Workouts **shorter than 60 s are not stored**.
- Check `deviceStauts == DEVICE_FREE` before starting.

### 10.6 Low-power mode, wear detection
```java
void readLowPower(IBleWriteResponse, ILowPowerListener); void settingLowpower(IBleWriteResponse, ILowPowerListener, boolean isOpen)
interface ILowPowerListener { void onLowpowerDataDataChange(LowPowerData) }   // LowPowerData: oprateType, readState, openState, notify, trunwrister, normallight, shockdelay, shocktime (ints)
void setttingCheckWear(IBleWriteResponse, ICheckWearDataListener, CheckWearSetting)  // 3 t's; no read API
CheckWearSetting().apply { isOpen = true }; interface ICheckWearDataListener { void onCheckWearDataChange(CheckWearData) }  // checkWearState: ECheckWear
```
`LowPowerData` int semantics aren't documented. Log the raw values and treat `openState == 1` as ON (assumption, check against the watch).

### 10.7 Device control (document only; hide behind a confirm dialog)
```java
void resetDeviceData(IBleWriteResponse)                       // "复位数据" reset data (gated by FunctionDeviceSupportData.resetData)
void clearDeviceData(IBleWriteResponse)                       // "清除数据" = FACTORY RESET / wipe. Demo calls finish() right after.
void powerOffDevice(IBleWriteResponse, IResponseListener)     // response(1)=ok, 0=fail; often no callback: watch drops BLE -> treat disconnect as success
void disconnectWatch(IBleWriteResponse)                       // plain disconnect
```
None of these has a support guard in `VPOperateManager`; they send immediately. Per CLAUDE.md, don't expose `clearDeviceData` casually.

---

## 11. Not supported on the T30. Don't build UI

| Feature | API that exists | Why not |
|---|---|---|
| Brightness | `settingScreenLight` / `readScreenLight` + `ScreenSetting` | `ScreenLight` = UNSUPPORT |
| Text alarm | `addTextAlarm`… | UNSUPPORT |
| Old alarm | `settingAlarm` (3× `AlarmSetting(h, m, open)`) | the watch uses Alarm2 |
| Weather | `settingWeatherStatusInfo` / `settingWeatherData` | UNSUPPORT |
| Music | `settingMusicData` | UNSUPPORT |
| Contacts / SOS contacts | `addContact` / `setContactSOSState` | UNSUPPORT |
| Find device | `startFindDeviceByPhone`, `settingFindDevice` | UNSUPPORT |
| Find phone | `settingFindPhoneListener` | UNSUPPORT |
| Countdown | `settingCountDown` | UNSUPPORT |
| Drink | `readDrinkData` | UNSUPPORT |
| Auto-measure interval | `setAutoMeasureSettingData` | package 4 never sent |
| Motor / vibration level | `setMotorLevel` | package 5 never sent |
| World clock | `addWorldClock` | package 4 never sent |
| DND | none | no API at all |

---

## 12. Repository wrapper pattern (one per settings call)

```kotlin
suspend fun setLongSeat(s: LongSeatSetting): LongSeatData = queue.run("settingLongSeat", 8_000) {
    suspendCancellableCoroutine { cont ->
        val once = Once(cont)                       // ignores duplicate resumes (G3)
        longSeatRouter.pending = { d ->             // ONE ILongSeatDataListener instance, reused (G2)
            when (d.status) {
                ELongSeatStatus.OPEN_SUCCESS, ELongSeatStatus.CLOSE_SUCCESS -> once.ok(d)
                ELongSeatStatus.UNSUPPORT -> once.fail(WatchException("Sedentary not supported"))
                else -> once.fail(WatchException("Sedentary set failed: ${d.status}"))
            }
        }
        vp.settingLongSeat({ code -> if (code != 0) once.fail(WatchException("write $code")) }, s, longSeatRouter)
    }   // queue timeout covers the silent-unsupported case (G1)
}
```
Success statuses per call:

| Call | Device ack = success |
|---|---|
| `changeCustomSetting` | `SETTING_SUCCESS` |
| `readCustomSetting` | `READ_SUCCESS` |
| `settingNightTurnWriste` | `SUCCESS` |
| `setScreenLightTime` | `SETTING_SUCCESS` |
| `settingHeartWarning` | `OPEN_SUCCESS` / `CLOSE_SUCCESS` |
| `settingLongSeat` | `OPEN_SUCCESS` / `CLOSE_SUCCESS` |
| `addAlarm2`, `modifyAlarm2` | `SETTING_SUCCESS` |
| `deleteAlarm2` | `CLEAR_SUCCESS` |
| `readAlarm2` | `READ_SUCCESS*` or timeout → `getAlarm2List()` |
| `settingSocialMsg` | `onSocialMsgSupportDataChange` received |
| `sendSocialMsgContent` | last write ack only |
| `settingDeviceLanguage` | `OPRATE_SUCCESS` |
| `settingWomenState` | `SETTING_SUCCESS` |
| `startCamera` | `OPEN_SUCCESS` |
| `stopCamera` | `CLOSE_SUCCESS` |

## 13. Things to verify on the watch (assumptions above)
1. Alarm repeat bit order: set `"0000001"`. Monday means the doc is right; Sunday means the demo is right.
2. 24 h: does `changeCustomSetting` with `setIs24Hour(true)` stick (re-read shows `isIs24Hour=true`, watch face shows 24 h)?
3. Long-sit `threshold` range the firmware accepts; screen-on `min/maxDuration` values.
4. Whether `sos()` arrives through `settingSOSListener`, `settingDeviceControlPhone`, or both.
5. How the `OTHER` category displays a long title and body (cut at 70 / 280 bytes).
6. `FunctionDeviceSupportData.healthRemind` and `resetData` values in your support dump.
