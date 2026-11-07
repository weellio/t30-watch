# Veepoo SDK: History Sync API brief (T30)

SDK: `vpprotocol-2.3.95.15`, `vpbluetooth-1.20`.

Sources used:
- Signatures: `javap` on the compiled classes.
- Behavior: decompiled SDK internals. The reader classes are `operate/vp_bu` (origin data, protocol 0), `operate/vp_ck` plus `operate/parse/sleep/vp_b` (sleep), `operate/vp_cr` (sport mode), `operate/vp_cs` (sport CRC) and `util/vp_t` (30-minute aggregation).
- Usage: the English API doc and the demo app.

Where the doc and the bytecode disagree, this brief follows the bytecode and says so.

T30 facts assumed below: `watchDataDay = 5`, `originProtocolVersion = 0`, sport model supported (3 slots), `newCalcSport` on, precise sleep off.

---

## 0. Read this first: rules that will bite

| # | Gotcha | Source |
|---|---|---|
| 1 | **Protocol 0 needs `IOriginDataListener`. An `IOriginData3Listener` does nothing.** `readOriginDataBySetting` checks `getOriginProtocolVersion()`. If the version is not 3 or 5 and the listener is not `IOriginDataListener`, it **returns silently**: no BLE write, no callback, no error. The v2 demo (`HealthDataReadActivity`) always passes `IOriginData3Listener`, so it would hang on a T30. | `VPOperateManager.readOriginDataBySetting` bytecode |
| 2 | `IOriginData3Listener` does **not** extend `IOriginDataListener`. Both extend `IOriginProgressListener`. The doc's claim that it "inherits from IOriginDataListener" is wrong. | javap |
| 3 | `IOriginProgressListener.onReadTimeout(int)` and `IAllHealthDataListener.onReadTimeout(int)` are abstract, but the doc examples leave them out. Kotlin `object :` must override them. **On protocol 0 they never fire.** Only the v3/v5 reader (`vp_bt`) calls them. | javap and grep of the call sites |
| 4 | **The SDK's own timeouts are inconsistent.** Origin (protocol 0) has a 35 s timer per day. When it expires it **silently finishes that day**, emits the partial half-hour data and moves to the next day. Sleep, sport-mode, step and person-info reads have **no timeout at all**: if the watch goes quiet, the read hangs forever. Your command queue must own every timeout. | `vp_bu` (Timer 35000 ms), `vp_ck`/`vp_cr` (no timer) |
| 5 | Many reads are **silently dropped** when a feature check fails. Example: `readSportModelOrigin` does nothing when `FunctionCheckUtil.checkSportModel()` is false. A queue timeout is your only signal. | bytecode |
| 6 | `IBleWriteResponse.onResponse(code)` fires **once per BLE write**. Multi-day reads write once per day, so it fires several times. `0` = `Code.REQUEST_SUCCESS`; negative values are failures (-1 failed, -2 canceled, -7 timed out, -8 overflow, -9 denied, -10 exception). Never resume a coroutine from `onResponse`; only use it to fail fast when the code is not 0. | `com.inuker.bluetooth.library.Code` |
| 7 | Threading: notifications normally arrive on the **main thread** (inuker `ProxyUtils.getUIProxy`). The origin reader's timeout path runs on a **`java.util.Timer` thread**: `onOringinHalfHourDataChange`, `onReadOriginProgress(1f)` and `onReadOriginComplete` can fire there. Treat every callback as "any thread" and hand off to a `Channel` or `Mutex`-guarded state. | `vp_bu.vp_a` (TimerTask) |
| 8 | Data dates are computed **on the phone at parse time**: `today - dayIndex` in the phone's default time zone (`Calendar.getInstance()`). A read that crosses local midnight, or a watch clock that differs from the phone, mis-dates records. Sync the watch time before reading, and avoid reads around 00:00. | `util/vp_j.vp_a(int)`, `TimeData.toCalendar()` |
| 9 | The SDK keeps **no record of what was already read**. `VpSpGetUtil` only stores capabilities, plus person height (`count_person_height`, default 175). Incremental sync is your job: store the last `packageNumber` per date in Room. | `VpSpGetUtil` decompiled |
| 10 | `OriginHalfHourData.getDate()` is **always null**: the constructor never sets it. Take the date from `item.time` or `item.date`. | constructor bytecode |
| 11 | `OriginHalfHourData.allStep` is **not** a 30-minute total. It is the sum of `stepValue` over all 5-minute records delivered **for that day in this read**. After a partial (resumed) read it is partial too. | `vp_t.vp_a(List)` |
| 12 | `halfHourBps` is **not** aggregated. It holds one entry per 5-minute record with a valid BP reading (60–300 / 20–200), carrying that record's exact time. | `vp_t.vp_b(List)` |
| 13 | `FunctionDeviceSupportData.getWathcDay()` is misspelled in the SDK. `getOriginProtcolVersion()` and `getSportmodelday()` are also odd spellings. | javap |

---

## (e) syncPersonInfo

```java
public void syncPersonInfo(IBleWriteResponse, IPersonInfoDataListener, PersonInfoData);
```

`PersonInfoData` constructors:
- `PersonInfoData(ESex sex, int heightCm, int weightKg, int age, int stepAim)`
- `PersonInfoData(ESex sex, int heightCm, int weightKg, int age, int stepAim, int sleepAimMinutes)`

`ESex` values: `MAN`, `WOMEN`.

Getters: `getESex()`, `getHeight()`, `getWeight()`, `getAge()`, `getStepAim()`, `getSleepAim()`.

```java
interface IPersonInfoDataListener extends IListener {
    void OnPersoninfoDataChange(EOprateStauts status); // capital "O"; OPRATE_SUCCESS / OPRATE_FAIL / UNKNOW
}
```

- It has one abstract method, so a Kotlin SAM lambda works. It fires once.
- The SDK saves `height` to SharedPreferences **before** the write. That stored height feeds the SDK's step-to-calorie and step-to-distance math in the legacy origin parser and in `readSportStep`.
- Call it once after `confirmDevicePwd`, and before any step or history read.
- No SDK timeout. Use about 10 s in the queue.

```kotlin
suspend fun syncPersonInfo(p: PersonInfoData): EOprateStauts = suspendCancellableCoroutine { c ->
    vp.syncPersonInfo({ code -> if (code != Code.REQUEST_SUCCESS && c.isActive) c.resumeWithException(BleWriteFailed(code)) },
                      { st -> if (c.isActive) c.resume(st) }, p)
}
```

---

## (a) Current step count (real time)

```java
public void readSportStep(IBleWriteResponse, ISportDataListener);
interface ISportDataListener extends IListener { void onSportDataChange(SportData d); }  // SAM, fires once
```

On a T30, `isParseSportModel()` is true, so the SDK sends the `read_current_sport_sportmodel_oprate` variant.

`SportData` getters:

| Getter | Type | Meaning |
|---|---|---|
| `getStep()` | int | Steps today |
| `getDis()` | double | Distance today, in km |
| `getKcal()` | double | Calories today, in kcal |
| `getCalcType()` | int | 0 = old algorithm, 1 = new formula, 2 = sport-mode table |
| `getTriaxialX/Y/Z()` | int | Accelerometer axes, debug only |

- These are today's running totals. They update live, unlike the 5-minute history, which lags. Poll this, for example every 60 s while the dashboard is visible.
- No SDK timeout. Use 5–10 s.

---

## (b) 5-minute origin data and 30-minute data

### Signatures (compiled)

```java
public void readOriginData         (IBleWriteResponse, IOriginProgressListener, int watchday);
public void readOriginDataFromDay  (IBleWriteResponse, IOriginProgressListener, int day, int position, int watchday);
public void readOriginDataSingleDay(IBleWriteResponse, IOriginProgressListener, int day, int position, int watchday);
public void readOriginDataBySetting(IBleWriteResponse, IOriginProgressListener, ReadOriginSetting);
// ReadOriginSetting(int day, int position, boolean onlyReadOneDay, int watchday)
```

All four wrappers end in `readOriginDataBySetting`. Their exact mapping:

| Call | Equivalent setting | Reads |
|---|---|---|
| `readOriginData(r, l, wd)` | `ReadOriginSetting(0, 1, false, wd)` | Days 0..wd-1, all records |
| `readOriginDataFromDay(r, l, d, p, wd)` | `ReadOriginSetting(d, p, false, wd)` | Day d from package p, then days d+1..wd-1 from package 1 |
| `readOriginDataSingleDay(r, l, d, p, wd)` | `ReadOriginSetting(d, p, true, wd)` | Only day d, from package p |

- A `position` below 1 is coerced to 1.
- The BLE command is `D1 <posLo> <posHi> <day>`.
- `position` only applies to the **first** day. Each following day is requested with position 1.

**Recommended for the T30:** `readOriginDataSingleDay(resp, listener, day, startPos, 5)`, called once per day from your sync engine. This gives you:
- clean per-day completion,
- per-day resume positions,
- per-day error isolation,
- one command per queue slot.

`readOriginData(resp, l, 5)` is fine for a first full sync.

### Listener (protocol 0): implement all 6

```java
interface IOriginProgressListener extends IListener {
    void onReadOriginProgressDetail(int day, String date, int allPackage, int currentPackage);
    void onReadOriginProgress(float progress);     // 0..1
    void onReadOriginComplete();
    void onReadTimeout(int day);                    // never called on protocol 0
}
interface IOriginDataListener extends IOriginProgressListener {
    void onOringinFiveMinuteDataChange(OriginData d);         // note "Oringin" spelling
    void onOringinHalfHourDataChange(OriginHalfHourData d);
}
```

(For reference only: `IOriginData3Listener` adds `onOriginFiveMinuteListDataChange(List<OriginData3>)`, `onOriginHalfHourDataChange(OriginHalfHourData)`, `onOriginHRVOriginListDataChange(List<HRVOriginData>)` and `onOriginSpo2OriginListDataChange(List<Spo2hOriginData>)`. Don't use it on the T30.)

### Callback order (from `vp_bu`)

1. On each request, the SDK resets state and starts a **35 s timer for that day**.
2. For **every 20-byte packet**, in order:
   1. `onReadOriginProgressDetail(day, "yyyy-MM-dd", allPackage, packageNumber)`
   2. `onReadOriginProgress(p)`, skipped when `p == 1.0`
   3. `onOringinFiveMinuteDataChange(originData)`, only if `mTime != null`
3. The day ends when the SDK sees `packageNumber == allPackage`, or `allPackage == 0` (an empty day), or `packageNumber == 0`. It then:
   - cancels the timer,
   - fires `onOringinHalfHourDataChange(...)` **once for that day**, and only if at least one record was collected,
   - requests the next day if `day < watchday-1` and this is not a one-day read.
4. If the 35 s timer fires first, the SDK does the same thing as step 3 with whatever it has. You get no error and no `onReadTimeout`.
5. After the last day: `onReadOriginProgress(1.0f)`, then `onReadOriginComplete()`.

Progress formula:
- One-day read: `pkg / all`.
- Multi-day read: `day/wd + (pkg/all)/wd`.

**Completion rule for your wrapper:** `onReadOriginComplete()` is the only end signal. Add an **inactivity watchdog**: restart it on every callback and fail after about 45 s with no callbacks. Also set a hard cap, for example `40 s × days + 15 s`.

### `OriginData` (5-minute record), as parsed for the T30

The T30 uses the sport-model parser (`vp_bu.vp_c`), because `isParseSportModel()` = `isSupportSportModel() || protocol in {4,5}` = true.

| Getter | Type | T30 value / unit |
|---|---|---|
| `getDate()` | String | `"yyyy-MM-dd"`, computed as phone-today minus `day` |
| `getmTime()` | `TimeData` | year, month (1-based), day, hour, minute; second = 0. Hour comes from the packet; minute from byte 19 |
| `getAllPackage()` | int | Records stored that day so far (max 288) |
| `getPackageNumber()` | int | 1-based index of this record within the day. **This is your resume cursor** |
| `getRateValue()` | int | Average heart rate for the slot, bpm. 0 = not measured. Valid range 30–200 |
| `getStepValue()` | int | Steps **in this 5-minute slot**, not cumulative |
| `getSportValue()` | int | Activity amount, 0–65535. `getSportValueLevel()` maps it to 1..5 (≤220, ≤700, ≤1400, ≤3200, else) |
| `getHighValue()` / `getLowValue()` | int | BP mmHg. 0 = no reading. Valid 60–300 / 20–200 |
| `getDisValue()` | double | **Device-reported** distance for the slot, km (raw/1000, 3 dp) |
| `getCalValue()` | double | **Device-reported** kcal for the slot (raw/10, 1 dp) |
| `getCalcType()` | int | Always **2** on this path |
| `getWear()` | int | Wear flag (raw byte 17) |
| `getTempOne()` | int | Raw byte 18, reserved |
| `getTempTwo()` | int | Always -1 here |
| `getDrinkPartOne/Two()`, `getTemperature()`, `getBaseTemperature()` | — | Not populated; temperature is unsupported |

Legacy parser, for comparison (protocol 0 without sport model, so **not** the T30):
- It derives cal/dis from steps and stored height, with `calcType` 0 or 1.
- It only knows days 0–2. Days 3+ get "today's" date and are then **dropped**, because the day-of-month doesn't match.
- Mention this if you ever support other Veepoo watches.

Defensive filters for your code (inferred, verify with logs):
- Drop records with `allPackage == 0 || packageNumber == 0`. That is the empty-day marker; on this path it isn't filtered and may arrive as an all-zero record.
- Treat `rate == 0` as "no HR", and BP of 0/0 as "no BP".

### `OriginHalfHourData` (once per day, built by the SDK from that read's 5-minute list)

| Getter | Content |
|---|---|
| `getHalfHourRateDatas(): List<HalfHourRateData>` | One entry per 30-minute slot with a non-zero average. Average of `rateValue` in 30..200. For day 0 only slots up to now. `time` = slot start (HH:00 or HH:30). `getRateValue()`, `getTime()`, `getDate()` ("yyyy-MM-dd"), `getEcgCount()`/`getPpgCount()` (0 on protocol 0) |
| `getHalfHourSportDatas(): List<HalfHourSportData>` | Per 30-minute slot sums: `getStepValue()`, `getSportValue()`, `getDisValue()` (km), `getCalValue()` (kcal). `getTime()` = slot start. **Only slots whose summed sportValue is not 0 are emitted** |
| `getHalfHourBps(): List<HalfHourBpData>` | **Per 5-minute record**, not per 30 min (see gotcha 12). `getHighValue()`, `getLowValue()`, `getTime()`, `getDate()` |
| `getAllStep()` | Sum of steps over this read's records for the day (see gotcha 11) |
| `getDate()` | Always null (see gotcha 10) |

**Recommendation:** store only the 5-minute `OriginData` rows (Room primary key: `date + hour*60+minute`, upsert). **Compute 30-minute, day, week and month aggregates yourself in SQL.** The SDK's half-hour object is partial after a resumed read, and its BP list isn't aggregated. Log it in debug mode; don't persist it.

### Day indexing and incremental sync

- `day` 0 = today and 1 = yesterday, up to `watchday-1` = 4 on the T30. Days are by the **watch's** calendar, mapped to dates with the **phone's** clock.
- `position` is the 1-based `packageNumber`. The doc says: *"remember the location of this package; next time pass it in to avoid repeated reading."*

Suggested algorithm:

```text
for d in 0..4:
    date = LocalDate.now().minusDays(d)
    last = dao.maxPackageNumber(date) ?: 0
    if d > 0 and last > 0 and dao.dayComplete(date): continue   // past days never change once fully read
    start = max(1, last)                // re-read the last record (upsert); safer than last+1 when last == allPackage
    readOriginDataSingleDay(resp, listener, d, start, 5)
    mark dayComplete(date) when d > 0 and observed packageNumber == allPackage
```

- Today (d = 0) grows by about 1 record every 5 minutes.
- A past day can still gain its final records just after midnight. Read it once more on the first sync after midnight.

---

## (c) Sleep (normal sleep; precise sleep is off)

### Signatures (compiled)

```java
public void readSleepData         (IBleWriteResponse, ISleepDataListener, int watchday);          // = FromDay(0, wd)
public void readSleepDataFromDay  (IBleWriteResponse, ISleepDataListener, int day, int watchday); // ReadSleepSetting(day,false,wd)
public void readSleepDataSingleDay(IBleWriteResponse, ISleepDataListener, int day, int watchday); // ReadSleepSetting(day,true,wd)
public void readSleepDataBySetting(IBleWriteResponse, ISleepDataListener, ReadSleepSetting);
// ReadSleepSetting(int dayInt, boolean onlyReadOneDay, int watchDataDay)
```

There is no `position` parameter; sleep reads are per whole day. The command is `E0 <day>`.

**Recommended:** `readSleepData(resp, l, 5)`. It is small, and it re-reads all 5 days each sync; dedupe with upserts. Or use `readSleepDataSingleDay(resp, l, 0, 5)` for a quick "last night" refresh.

### Listener: implement all 4 (it does *not* extend `IListener`)

```java
interface ISleepDataListener {
    void onSleepDataChange(String day, SleepData d);  // day = "0","1",... (String.valueOf(dayIndex)), NOT a date
    void onSleepProgress(float progress);             // 0..1
    void onSleepProgressDetail(String day, int packageNumber); // debug; packages count DOWN to 0
    void onReadSleepComplete();
}
```

Callback order (from `vp_ck` and `parse/sleep/vp_b`):
1. Per packet: `onSleepProgressDetail(day, pkg)`, then `onSleepProgress(p)` (skipped when `p == 1`).
2. When the packet with `packageNumber == 0` arrives, the day is finished. The SDK parses it:
   - Each 96-byte block is one sleep segment, so **a day can produce several `onSleepDataChange` calls** (main sleep plus naps).
   - A day with fewer than 6 packets produces nothing.
3. It then requests the next day. After the last day, or after the single day: `onSleepProgress(1f)`, then `onReadSleepComplete()`.

**No SDK timeout.** Use an inactivity watchdog of about 20 s and a hard cap of about 60 s.

### `SleepData` (public fields and getters)

| Getter | Type | Meaning |
|---|---|---|
| `getDate()` (field `Date`) | String | `"yyyy-MM-dd"` = **the night of**: the date of `sleepDown`, **minus 1 day if `sleepDown` is before 08:00**. A sleep from 23:10 on Nov 6 to 07:00 on Nov 7 has Date `2026-11-06`; one starting 01:00 on Nov 7 also has `2026-11-06` |
| `getSleepDown()` / `getSleepUp()` | `TimeData` | Fell-asleep and woke times. Year is inferred from phone today minus day (January wrap handled). These **span midnight** routinely |
| `getAllSleepTime()` | int | Minutes (raw count × 5). Records with a raw count above 248 (over 1240 min) are discarded |
| `getDeepSleepTime()` / `getLowSleepTime()` | int | Minutes (raw × 5) |
| `getSleepQulity()` | int | 1..5. Records with 255 are dropped by the SDK |
| `getWakeCount()` | int | Number of wake periods |
| `getSleepLine()` | String | One char = **5 min**: `'0'` light, `'1'` deep, `'2'` awake. Length = `allSleepTime/5` |
| `getCali_flag()` | int | Always 0 |

- The SDK silently drops a record if either time string is invalid, if `sleepDown > sleepUp`, or if quality is 255.
- `compareTo` orders by `sleepDown`.
- Precise sleep (`SleepPrecisionData`, extends `SleepData`) is only produced when `isSupportPreciseSleep()` is true. On the T30 you always get plain `SleepData`. Still guard with `if (d is SleepPrecisionData)`.
- Demo convention (`SleepActivity.kt`): `allSleepTime >= 120` counts as "long sleep", anything shorter as a nap.

Dedupe key: `sleepDown.getDateAndClockForDb()`, for example `"2026-11-06-23:10"`. Upsert on it, because the same sleep comes back on every sync for 5 days. The watch may also extend or merge a segment later, so prefer "replace by (Date, sleepDown)".

---

## (d) Sport-mode (workout) history

### Signatures (compiled)

```java
public void readSportModelOrigin(IBleWriteResponse, ISportModelOriginListener);            // all slots
public void readSportModelOrigin(IBleWriteResponse, ISportModelOriginListener, int slot);  // one slot
public void readSportDataCrc(com.inuker.bluetooth.library.connect.response.BleWriteResponse, // NOTE: plain BleWriteResponse
                             ISportModelDataCrcListener);
```

- Both reads are gated by `FunctionCheckUtil.checkSportModel()`. If the check fails, the call is a **silent no-op**.
- The all-slots overload reads slots `0 .. VpSpGetUtil.getSportModelDay()-1`. That value comes from SharedPreferences `count_sport_model_day` (default 3; the T30 reports 3), and is the same as `FunctionDeviceSupportData.getSportmodelday()`.
- The command is `D4 <slot> 00…` (20 bytes).
- The doc calls the index "day". It is a storage slot. Whether 0 is the newest slot is **unverified**; check `startTime` in the logs.

### Listener: implement all 6

```java
interface ISportModelOriginListener extends IListener {
    void onReadOriginProgress(float progress);
    void onReadOriginProgressDetail(int day, String date, int allPackage, int currentPackage); // NEVER called by this reader
    void onHeadChangeListListener(SportModelOriginHeadData head);                  // non-GPS watches (T30)
    void onGPSWatchSportModeHeadChange(SportModelGPSWatchOriginHeadData head);     // only if SP "MULTSPORTMODEL_TYPE" in 4,5,6
    void onItemChangeListListener(List<SportModelOriginItemData> items);
    void onReadOriginComplete();
}
interface ISportModelDataCrcListener extends IListener {
    void onSportCrcDataChange(int[] crcPerSlot);   // length = getSportModelDay()
    void onReadFail();
}
```

Callback order (from `vp_cr`):
1. For each slot, packets are buffered and `onReadOriginProgress` is fired.
2. On the last packet (`pkg == all`):
   - If the slot has at least 3 packets and a valid head: **`onHeadChangeListListener(head)`, then `onItemChangeListListener(items)`**.
   - Otherwise the slot is silently skipped (empty slot).
3. The next slot is then requested. After the last slot (or after the single slot of the one-slot overload): `onReadOriginComplete()`.

**No SDK timeout.** Use an inactivity watchdog of about 20 s.

### `SportModelOriginHeadData` (constructor mapping verified in bytecode)

| Getter | Unit / meaning |
|---|---|
| `getDate()` | `"yyyy-MM-dd"` of the start |
| `getStartTime()` / `getStopTime()` | `TimeData`, with seconds |
| `getSportTime()` | **Seconds**, `|stop - start|` (includes pauses) |
| `getStepCount()` | Total steps |
| `getSportCount()` | Activity amount |
| `getDistance()` | Raw/1000, 4 dp, **probably km**. Unverified, compare with the watch screen |
| `getKcals()` | Raw/1000, 1 dp, **probably kcal**. Unverified |
| `getRecordCount()` | Number of item records |
| `getPauseCount()` / `getPauseTime()` | Count / time paused |
| `getCrc()` | Record checksum. Use it as part of the dedupe key |
| `getPeisu()` | Pace = `(sportTime - pauseTime) / distance`, so seconds per km. `getPeisuFormat()` gives a string |
| `getAverRate()` | Average of item rates in 30–200, computed by the SDK |
| `getOxsporttimes()` | Computed from items; unreliable (depends on `sportDuration`, which is 0 on non-GPS). Ignore it |
| `getSportType()` | `ESportType` value (1 outdoor run, 2 outdoor walk, 3 indoor run, … 0 = default) |
| `isHasGpsInfo()`, `getSportDuration()`, `getDataType()`, `getA7ProtectSportType()` | GPS-watch fields, likely 0/false on the T30 |

### `SportModelOriginItemData` (about one per minute)

| Getter | Meaning |
|---|---|
| `getStartTime()` / `getDate()` | The **head's** start time and date (the same for every item) |
| `getMinute()` | Item index (packet index minus 3 header packets). Item time ≈ `startTime + minute` minutes |
| `getAllMinute()` | Total item count |
| `getRate()` | Heart rate, bpm |
| `getStepCount()`, `getSportCount()`, `getDistance()`, `getKcal()` | Ints in raw device units. Unclear whether per-minute or cumulative; check against the head totals |
| `getBeathPause()` | Pause flag |
| `getCrc()` | The head's CRC |
| `getLngArray()`, `getLatArray()`, `getAltitude()`, `getSignalArray()`, `getSportStateArray()` | GPS only |

**Incremental sport sync:**
1. Run `readSportDataCrc` to get an `int[]` with one CRC per slot.
2. Compare each CRC with the stored head CRC for that slot.
3. Read only changed slots with `readSportModelOrigin(resp, l, slot)`.

A CRC of 0 likely means an empty slot. Dedupe key: `startTime.getDateAndClockAndSecondForDb() + crc`.

---

## (f) "Read all health data" and incremental APIs

```java
public void readAllHealthData(IAllHealthDataListener, int watchday);
public void readAllHealthDataBySettingOrigin(IAllHealthDataListener, int day, int position, int watchday); // NOTE arg order
```

There is no `IBleWriteResponse` parameter; the SDK uses an internal one.

Listener: implement all 7.

```java
interface IAllHealthDataListener {
    void onProgress(float p);                    // sleep phase scaled to 0..0.1, origin phase to 0.1..1.0
    void onSleepDataChange(String day, SleepData d);
    void onReadSleepComplete();
    void onOringinFiveMinuteDataChange(OriginData d);
    void onOringinHalfHourDataChange(OriginHalfHourData d);
    void onReadOriginComplete();                 // final end signal
    void onReadTimeout(int day);                 // not fired on protocol 0
}
```

Behavior (bytecode):
- **`readAllHealthData`** runs `readSleepData(…, wd)`. On `onReadSleepComplete` it continues with `readOriginData(…, wd)`, using an internal `IOriginDataListener` wrapper.
  - `onReadOriginProgressDetail` is **not forwarded**, so you lose the per-day cursor. `OriginData.getPackageNumber()` and `getAllPackage()` still carry it.
  - On protocol 3 the origin phase is **skipped entirely**: `onReadOriginComplete` never comes. On protocol 5 it is a silent no-op. Neither affects the T30.
- **`readAllHealthDataBySettingOrigin(l, day, position, wd)`**:
  - The sleep phase is `readSleepDataFromDay(day, wd)`: sleep also starts at `day`, not at today as the doc says.
  - The origin phase is `readOriginDataFromDay(day, position, wd)`.
  - It handles protocols 0, 3 and 5.
- **Recommendation:** don't use these. Issue separate `readSleepData` and per-day `readOriginDataSingleDay` commands through your queue. You get per-day cursors, separate timeouts and simpler failure handling. The convenience wrapper only chains the same two reads.

Tracking what was already read: the SDK does none (see gotcha 9). Persist the following in Room or DataStore:

| Data | What to store |
|---|---|
| Origin | `lastPackageNumber` per date, plus a `dayComplete` flag |
| Sleep | Nothing; upsert by `sleepDown` |
| Sport | `crc` per slot |

Capability values to read (all available after `confirmDevicePwd`):
- `VpSpGetUtil.getVpSpVariInstance(ctx).getOriginProtocolVersion()` (SP `count_origin_protocol_version`, default 0)
- `.isSupportSportModel()`
- `.getSportModelDay()`
- `.isSupportPreciseSleep()`
- `.isSupportBp()`

`FunctionDeviceSupportData.getWathcDay()` is the source for `watchday`. The demo stores it in `MyDeviceInfo.watchDataDay` (`PwdConfirmActivity.java:188`).

---

## Recommended sync order (one queue, one command at a time)

1. `syncPersonInfo`, once per connection.
2. `readSportStep`, for the dashboard tile.
3. `readSleepData(…, 5)`.
4. `readOriginDataSingleDay(d, start, 5)` for each day that needs it: day 0 always; past days only if not complete.
5. `readSportDataCrc`, then `readSportModelOrigin(…, slot)` for each changed slot.

Each step completes only on its terminal callback, or fails on its watchdog. After a watchdog failure:
- Log the failure.
- Consider the connection suspect. The demo's advice on timeout is "re-read, or disconnect, toggle Bluetooth and reconnect".
- Continue or abort the remaining steps.

### Kotlin wrapper sketch (origin, protocol 0)

```kotlin
sealed interface OriginEvent {
    data class Record(val d: OriginData) : OriginEvent
    data class HalfHour(val d: OriginHalfHourData) : OriginEvent
    data class Progress(val day: Int, val date: String?, val all: Int, val cur: Int) : OriginEvent
}

fun readOriginDay(day: Int, startPos: Int, watchDay: Int): Flow<OriginEvent> = callbackFlow {
    val listener = object : IOriginDataListener {
        override fun onReadOriginProgressDetail(day: Int, date: String?, allPackage: Int, currentPackage: Int) {
            trySend(OriginEvent.Progress(day, date, allPackage, currentPackage))
        }
        override fun onReadOriginProgress(progress: Float) {}
        override fun onOringinFiveMinuteDataChange(originData: OriginData?) {
            originData?.takeIf { it.getmTime() != null && it.packageNumber > 0 && it.allPackage > 0 }
                ?.let { trySend(OriginEvent.Record(it)) }
        }
        override fun onOringinHalfHourDataChange(originHalfHourData: OriginHalfHourData?) {
            originHalfHourData?.let { trySend(OriginEvent.HalfHour(it)) }   // log only; date is null
        }
        override fun onReadOriginComplete() { channel.close() }
        override fun onReadTimeout(day: Int) { close(OriginTimeout(day)) } // v3/v5 only
    }
    check(VpSpGetUtil.getVpSpVariInstance(ctx).originProtocolVersion !in setOf(3, 5)) // else needs IOriginData3Listener
    vp.readOriginDataSingleDay({ code -> if (code != Code.REQUEST_SUCCESS) close(BleWriteFailed(code)) },
                               listener, day, startPos, watchDay)
    awaitClose { /* SDK has no cancel API; the queue must wait for the watchdog or completion before the next command */ }
}.timeoutOnInactivity(45.seconds)   // your own operator; also wrap the whole thing in withTimeout(hardCap)
```

- The SDK has **no cancel or abort API** for these reads. If you cancel the collector, the watch may keep streaming packets that belong to this read.
- So after a cancel or timeout, either wait a few seconds before issuing the next command, or disconnect and reconnect.
- Keep the queue `Mutex` held until completion or the watchdog fires.

---

## Demo app references

- `activity/OperaterActivity.java`:
  - steps/person info: lines 970–987
  - sleep (all / FromDay / SingleDay): lines 2011–2110
  - origin (`readOriginData`, `FromDay`, `SingleDay` with protocol switch): lines 2121–2388
  - `readAllHealthData`: lines 2390–2435
  - `readSportModelOrigin`: lines 2507–2552
  - the `getDay(String)` helper treats the sleep `day` as "0"/"1"/"2": line 4489
- Note: the demo hardcodes `protype = 3` / `originProtocolVersion = 3`. Don't copy that.
- `activity/v2/health/HealthDataReadActivity.java` and `HealthDataReadOptActivity.java`: `IOriginData3Listener` only, with spinners for day 0–2 and position 1–288. Their `onReadTimeout` message translates to "re-read, or disconnect, restart Bluetooth and read again".
- `activity/v2/health/SleepActivity.kt`: `readSleepDataBySetting(ReadSleepSetting(day, onlyOneDay, watchDay))`. It groups multiple segments per day, splits long sleep from naps at 120 min, and wraps UI updates in `runOnUiThread`.
- `activity/v2/health/StepActivity.kt`: `readSportStep` lambda.
- `activity/PwdConfirmActivity.java:188`: `watchDataDay = functionSupport.getWathcDay()`.
