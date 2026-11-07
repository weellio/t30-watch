# Code review: phases 3–8 (T30 Watch)

Date: 2026-11-07. Read-only review; no project files changed.

How things were checked:
- Read all the files listed in the brief.
- `javap` on `vpprotocol-2.3.95.15` (feature getter names, `ContentPhoneSetting` / `ContentSmsSetting` constructors, notification send loop in `operate/vp_cl`, null handling in `util/vp_ag`).
- `javap` on `connect-client-1.1.0` (record validation).
- Checked the Hilt-generated `Hilt_BootReceiver` and `NotificationForwarder_MembersInjector`.
- Compared the Room schemas 2.json and 3.json.

Severity key:
- **crash**: the app (or a whole operation) dies.
- **data-loss / wrong-behavior**: a user-visible bug.
- **minor**: an edge case or polish.

---

## Top priority (fix before the watch test)

### 1. History cursor is saved before the data is saved, so synced data is lost for good (data-loss)

**Where**
- `ble/HistoryClient.kt:152-156`: `pos_<date>` / `done_<date>` are written inside the per-day loop.
- `ble/HistoryClient.kt:160`: the batch is only returned after **all** days are read.
- `data/sync/SyncEngine.kt:120-124`: Room upserts happen after `readHistory` returns.

**What happens**
- If any later day fails (watchdog, disconnect, write code ≠ 0), `readHistory` throws and the whole `HistoryBatch` is dropped.
- But the cursors of the days already read have moved on:
  - past days are marked `done_` and are never read again;
  - today resumes from `maxPkg`.
- So those records never reach Room. The same thing happens if the process dies between `readHistory` returning and the upserts.

**Same bug in workouts**
- `HistoryClient.kt:255` saves `sport_crc_<slot>` before the rows are stored.
- If a later slot throws, `out` is lost, and `SyncEngine.kt:133` swallows the error with `runCatching`. Those workouts are never re-read.

**Fix**
- Commit per day, after the DB write:
  - give `readHistory` a `suspend (HistoryBatch) -> Unit` callback (or return a per-day list of `(batch, cursorUpdate)`);
  - `SyncEngine` upserts the slots, heart rate and BP for that day, **then** writes `pos_`/`done_`.
- Same for workouts: upsert the slot's workout, then write `sport_crc_<slot>`.

### 2. One failed step aborts the whole sync (wrong-behavior)

**Where:** `SyncEngine.kt:117`. `history.readTodayTotals()` (`readSportStep`, 10 s timeout) runs first, with no `runCatching`.

**What happens:** if the watch ignores or drops the step read, the sync throws before any history is read, and every sync fails the same way.

**Fix:** wrap steps 1 (totals), 3 (sleep) and 4 (workouts) separately. Record each error in `lastError`, but keep going.

### 3. Telegram and Messenger notifications are never forwarded (wrong-behavior)

**Where**
- `service/NotificationForwarder.kt:140-141` maps `org.telegram.messenger` → `"telegram"` and `com.facebook.orca` → `"messenger"`.
- `ble/WatchSettingsClient.kt:359`: `categoryOn("telegram")` checks for `SUPPORT_OPEN`.
- On the T30 both are `UNSUPPORT` (`docs/t30-support.md`), so `NotificationForwarder.kt:50` `continue`s and they are silently dropped.
- Even if they got through, `categoryEnum()` (`WatchSettingsClient.kt:496`) would send `ESocailMsg.TELEGRAM`, which the watch doesn't know.

**Also:** when `lastSocial` is null (the category read failed), `categoryOn` returns false for everything except other/msg/phone. WhatsApp and the rest are then dropped too.

**Fix:** resolve the category against the watch's table. If the mapped key's status isn't supported (`!has()`), use `"other"`. Do this both for the `categoryOn` check and for the enum. The "other" branch already puts the app name in the title, so do that for the fallback too.

### 4. Auto-reconnect backoff never grows; it retries about every 35 s forever (wrong-behavior, battery)

**Where:** `data/WatchRepository.kt:96-112`, plus the collector at `86-92`.

**What happens**
1. `connectNow()` only launches a job and returns.
2. On the next loop pass, after `delay(15 s)`, the state is `Connecting`, so the loop `break`s and the job ends.
3. When that connect fails (about 32 s: 15 s, a 2 s pause, then 15 s), the state becomes `Disconnected(reason)`.
4. The collector calls `scheduleReconnect()`, which starts a **new** job at `attempt = 0` (5 s).

Result: with the watch out of range, a full connect attempt runs every ~37 s, all day. The same happens on "Watch rejected the password", which can never succeed.

**Fix**
- Inside the reconnect loop, call the connect and wait for it (`connectNow(...); connectJob?.join()`).
- Keep `attempt` at repository level, and reset it only on `Ready`.
- Don't reconnect after a password failure. Throw a typed exception for it.

### 5. After a phone reboot, the app may never reconnect (wrong-behavior)

**Where**
- `WatchRepository.kt:115-121`, `autoConnectLast()`: `if (!BlePermissions.bluetoothEnabled(context)) return`.
- `BOOT_COMPLETED` often arrives before the Bluetooth adapter is ON.
- `wantConnected` stays false and `lastTarget` stays null, so nothing retries.
- The SDK's Bluetooth-state listener isn't registered yet either, because `ensureInit` only runs on connect or scan.

**Fix:** in `autoConnectLast`, set `wantConnected = true` and `lastTarget`, then go through `scheduleReconnect()`. It already skips attempts while Bluetooth is off. Optionally also listen for `BluetoothAdapter.ACTION_STATE_CHANGED` to retry as soon as Bluetooth comes on.

### 6. Health Connect blood-pressure range kills the whole export (crash of the export)

**Where:** `health/HealthConnectExporter.kt:121`. The filter is `systolic in 60..300 && diastolic in 20..200`.

**What happens**
- The `BloodPressureRecord` constructor in connect-client 1.1.0 enforces **systolic 20–200** and **diastolic 10–180** mmHg (checked in bytecode: `bipush 20 / sipush 200 / bipush 10 / sipush 180`).
- A single reading like 205/120 throws `IllegalArgumentException` while the list is being built. Nothing is written for any type.
- Auto-export logs the error and stops. The manual export shows "Health Connect export failed".
- The manual-measurement BP path (`:130`) has the same gap.

**Fix:** filter to `systolic in 20..200 && diastolic in 10..180` in both places, or wrap each record construction in `runCatching` and count the ones skipped.

---

## Medium

### 7. Origin record dates use the sync-start date, not the record's own date (wrong-behavior)

**Where:** `HistoryClient.kt:127, 130, 142` (`toMs(date, t)` with `date = today.minusDays(day)`, computed before the loop).

**What happens**
- A sync that starts at 23:58 and reads day 0 after midnight gets the watch's *new* day.
- It stores those records under yesterday's date, overwriting yesterday's 00:00–00:xx slots.
- It also writes yesterday's `pos_` cursor with today's package numbers.

**Fix:** build the timestamp from `t.year / t.month / t.day` (`getmTime()` carries the full date), or from `OriginData.date`. Then compute `key` per record.

### 8. A stale cursor can block today's sync permanently (wrong-behavior)

**Where:** `HistoryClient.kt:133`, `137-156`.

**What happens**
- The cursor is keyed by date only, not by MAC. It is never lowered.
- If the watch has fewer packages than the stored `pos_` (watch reset or re-paired, data cleared, watch clock changed, a second watch), the read starts beyond the end and returns nothing.
- `maxPkg` stays at `start`, so today never syncs again until the date changes.

**Fix**
- Key the cursors by `mac + date`.
- If an observed `allPackage < start`, or a read returns 0 records while `start > 1`, reset the cursor to 1 and re-read.

### 9. NotificationForwarder consumer leaks and can run twice (wrong-behavior, minor)

**Where:** `NotificationForwarder.kt:42-59`.

**What happens**
- Each `onListenerConnected()` launches another `for (item in queue)` consumer in the **app** scope, and nothing cancels it.
  - The same instance can get disconnected then connected again (rebind): two consumers on one channel. Items aren't duplicated, but ordering is lost.
  - After the service instance is destroyed, its consumer and the instance stay alive forever.

**Fix**
- Keep `private var consumer: Job?`.
- Start it only if `consumer?.isActive != true`.
- Cancel it in `onListenerDisconnected()` and `onDestroy()`. A service-owned `CoroutineScope` is cleaner.

The `continue` inside `try` is fine, and so is the DND check: `INTERRUPTION_FILTER_UNKNOWN = 0` is not treated as DND.

### 10. Stale and out-of-order call notifications (wrong-behavior)

**Where:** `NotificationForwarder.kt:86-91`, `112-117`, and `WatchSettingsClient.push`.

**What happens**
- The queue waits behind long commands: a live heart-rate session holds it up to 315 s, and an origin day read up to 120 s.
- A call can then be pushed after it has ended (the watch rings for nothing).
- `callEnded()` is a separate `scope.launch`, so it can take the mutex **before** the call push that is still waiting in the channel. The "stop ringing" message then arrives first, and the ring arrives after it.

**Fix**
- Stamp each `Item` with `postedAt`. In the consumer, drop items older than about 30 s.
- For `Call`, skip it if `activeCallKey != item.key` (the call is already gone).
- Route `callEnded` through the same channel so the order is kept.

Also: `WatchMessage.Call(number = "")` with a blank title shows an empty caller. Fall back to `text`.

### 11. A cancelled streaming read lets the next command run into leftover packets (wrong-behavior)

**Where:** `SyncEngine.kt:70-78`, `149`, and `HistoryClient.watched`.

**What happens**
- `collectLatest { while (true) { delay; sync() } }` runs the sync **inside** the `collectLatest` body. Changing the Auto-sync setting while a timer sync is running cancels that sync mid-read.
- `CommandQueue` frees the mutex at once, but the SDK has no cancel API and the watch keeps streaming into the next command. The history brief warns about exactly this.
- `catch (e: Exception)` in `sync()` also swallows the `CancellationException`. `lastError` then shows "StandaloneCoroutine was cancelled".

**Fix**
- In the timer, `scope.launch { sync("timer") }` instead of calling it inline. The timer can be cancelled; the sync isn't.
- Rethrow `CancellationException`.
- Optionally, in `watched`, catch cancellation and `withContext(NonCancellable) { delay(3_000) }` before releasing.

### 12. Today screen goes stale after midnight (wrong-behavior)

**Where:** `ui/today/TodayViewModel.kt:53-55`. `today`, `from` and `to` are fixed when the ViewModel is created. With the activity kept in the background (the foreground service keeps the process alive), Today shows yesterday's data the next morning.

**Fix:** derive the day from a flow that ticks at midnight (or on `ACTION_DATE_CHANGED` / resume), and `flatMapLatest` the DAO queries on it.

### 13. Cached settings are changed before the watch confirms the write (wrong-behavior)

**Where**
- `WatchSettingsClient.kt:121-127` (`writeToggles` changes `lastCustom` in place).
- `:337-340` (`setNotifyCategory` changes `lastSocial`, which is often **the SDK's own cached object** returned by `vp.functionSocailMsgData`, `:329`).
- `:292-296` (`modifyAlarm2` changes the entry in `lastAlarms`).

**What happens:** if the write fails or times out, the cache now holds the new value. The next write sends it again, and the UI state is wrong after Reload. That's the G4 read-modify-write rule broken on failure.

**Fix:** copy before changing. Build a new `CustomSettingData` / `FunctionSocailMsgData` / `Alarm2Setting` (or snapshot and restore it in `catch`).

### 14. Late replies after a timeout reach the next command's listener (minor, wrong-behavior)

**Where:** `VeepooClient.command()` with a one-off listener per call. The SDK keeps one listener slot per type (G2).

**What happens:** `readCustomSetting` times out at 8 s. The user taps a switch, and `changeCustomSetting` installs a new listener. The late `READ_SUCCESS` packet goes to that listener, which reports "Watch didn't save the setting (READ_SUCCESS)".

**Fix:** in the success lambdas, ignore statuses that belong to the other operation. Leave them pending; don't fail. For example, in `writeToggles`, only fail on `FAIL`, not on `READ_SUCCESS`. Apply the same idea to alarms (`READ_*` versus `SETTING_*`), heart alarm and long-seat.

---

## Minor

| # | Where | What | Fix |
|---|---|---|---|
| 15 | `MainActivity.kt:57`, `WatchService.start` | `ContextCompat.startForegroundService` throws `ForegroundServiceStartNotAllowedException` (API 31+) if the app goes to the background between resume and the DataStore read finishing. It's uncaught in `lifecycleScope`, so the app crashes. Rare race | `try/catch` in `WatchService.start` |
| 16 | `WatchService.kt:53-60` | When `startForeground` throws after a `startForegroundService()` start, `stopSelf()` still triggers the system's "did not call startForeground" crash. Unlikely, because `start()` checks the permission first | Accept, or call `startForeground` with type 0 as a fallback before stopping |
| 17 | `WatchSettingsClient.kt:278` | A one-shot alarm saved for a time that has already passed today gets today's date, so it never rings | Use `LocalDate.now().plusDays(1)` when `hour:minute <= now` |
| 18 | `WatchRepository.disconnect` + `VeepooClient.connect:225-230` | Tapping Disconnect during a connect: the cancelled connect's `catch` overwrites the state with `Disconnected("StandaloneCoroutine was cancelled")`, and the UI shows that as an error | `catch (e: CancellationException) { …; throw e }` without setting a reason |
| 19 | `SyncEngine.kt:113-120` | `days` is computed but `readHistory(maxDays)` ignores it, because it always walks all 5 days using `done_` flags. Only the summary text and the Health Connect range use it | Remove the parameter or use it; fix the summary |
| 20 | `HealthConnectExporter.kt:82-100` | Today's current 5-minute slot ends in the future. Unverified whether Health Connect rejects future end times; if it does, a whole 500-record chunk fails | Skip slots with `startMs + 5 min > now` |
| 21 | `HealthConnectExporter.kt:116` | A manual heart-rate sample at `endTime`. The platform check is inclusive (`isAfter(end)`), so it's OK; using `st` is safer | Optional |
| 22 | `HistoryClient.kt:330` | DST fall-back: the two 01:xx hours map to the same `startMs`, so the second overwrites the first (and the same for heart rate and BP) | Accept, or use the record's offset |
| 23 | `CameraScreen.kt:107` | `onDispose { future.get() }` blocks the main thread if the provider hasn't finished initializing (leaving the screen right after opening it) | `if (future.isDone)` |
| 24 | `CameraScreen.kt:112` | No 1 s debounce on `TAKEPHOTO_CAN`. The brief advises one; a shake can fire it twice | Debounce |
| 25 | `NotificationForwarder.appLabel` | Package visibility (API 30+) can hide other apps, so the mute list shows package names | Add a `<queries>` launcher intent |
| 26 | `WatchRepository.kt:107` | With Bluetooth off, attempts still count up to 300 s, so after Bluetooth comes back on it can take up to 5 min to reconnect | Don't increment `attempt` while Bluetooth is off |
| 27 | `HistoryEntities.kt:54` | The `quality` comment says "0–100"; the T30 gives 1–5 | Comment |

---

## Answers to your 10 questions

**1. CommandQueue, Timer-thread callbacks, mutex held forever?**
- No deadlock. `done.complete` / `Once.resume` are thread-safe and non-blocking, and the main thread is suspended, not blocked.
- `out` is a `synchronizedList` (its `toList()` goes through the synchronized `toArray()`), and `last` is atomic.
- The mutex is always released:
  - `withTimeout` wraps every block;
  - the only `NonCancellable` sections (the HR/BP stop) have a 3 s cap.
- Caveat: waiting **for** the mutex has no timeout. A live heart-rate session holds it up to 315 s, so notifications and settings writes queue up behind it (see item 10).

**2. Nested `queue.run`?**
- None. I checked every `queue.run`, `command(` and `watched(` call site:
  - `callEnded` → `sendAndAwaitWrite`, which is not queued;
  - `cameraCall` → `command`, single level;
  - `saveAlarm` → `readAlarms`, `writeToggles` → `readToggles` and `setNotifyCategory` → `readNotifyCategories` all run *before* their own `command`, not inside it;
  - `SyncEngine` and the forwarder call the clients one after another.

**3. Hilt and Application injection**
- No cycle (Dagger would fail the build). `SyncEngine` → `WatchRepository` / `HistoryClient` / `WatchSettingsClient` → `VeepooClient` → `RawLog` → `AppSettings`; `HealthConnectExporter` → DAOs.
- Startup work on the main thread is small:
  - `SharedPreferences.getLong` in the `SyncEngine` constructor (one small file read);
  - `mkdirs` for the log directory.
- Room only opens on the first query, and DataStore reads on IO.
- `HealthConnectExporter` doesn't touch `HealthConnectClient` at construction.
- `@AppScope` field injection in the forwarder is resolved: the generated `MembersInjector` has `@QualifierMetadata("…AppScope")`.
- `BootReceiver` injection works: the Hilt transform inserts `super.onReceive` (checked in bytecode).

**4. WatchService on Android 14–16**
- Manifest type `connectedDevice` plus `FOREGROUND_SERVICE_CONNECTED_DEVICE` is correct.
- `start()` checks `BLUETOOTH_CONNECT`, which satisfies the Android 14 type prerequisite.
- Starts from `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` are exempt from the background-start ban, and `connectedDevice` is not in the Android 15 boot-restricted list.
- Repeated `startForegroundService` calls while already foreground are fine (the system clears `fgRequired`).
- Remaining risks: items 15 and 16, plus the reboot no-reconnect bug (item 5).

**5. NotificationForwarder**
- Hilt injection: OK.
- `continue` inside `try`: OK.
- DND check: OK.
- Dedupe: OK, in a 30 s window keyed by package + title + text.
- Problems:
  - the consumer can be launched twice and leaks (item 9);
  - Telegram and Messenger are dropped (item 3);
  - stale calls (item 10).

**6. Health Connect**
- `Metadata.autoRecorded` / `manualEntry(device=…, clientRecordId, clientRecordVersion)` compile against 1.1.0 and are used correctly. Stable IDs make re-export an update.
- Heart-rate samples:
  - hourly records span `[hour, last+1 ms)`, so all samples are inside;
  - the manual heart-rate sample at `endTime` passes the inclusive check.
- No record has `end == start`:
  - workouts use `max(en, st+1)`;
  - heart rate uses `+1`;
  - sleep and workouts are also filtered with `end > start`.
- Sleep stages are clipped to the session (the client checks first ≥ start, last ≤ end, no overlap). OK.
- 500 per `insertRecords` is safe.
- **Bug: the blood-pressure range (item 6).**

**7. Room**
- The 2→3 AutoMigration only adds 6 tables and indices; that's fine.
- 3.json keeps the v2 `known_device` / `measurement` SQL unchanged.
- The DAO queries match the entities.

**8. SyncEngine**
- `readyEvents` is safe. `SyncEngine` is built in `Application.onCreate` (Hilt), and `scope.launch` on `Main.immediate` subscribes synchronously before any connect can emit.
- `mutex.isLocked` racing is harmless, because everything runs on Main.
- Problems:
  - `days` is ignored (item 19);
  - the cursor commits too early (item 1);
  - the totals step aborts everything (item 2);
  - the timer cancels a running sync (item 11).

**9. Compose**
- LazyColumn keys are unique:
  - Measure: Room `id`;
  - Connect: `mac`, de-duplicated in the repository;
  - Debug: section title + field name, after `distinctBy`.
- `rememberSaveable` values are an enum and a Set, both Serializable.
- `hiltViewModel` in the NavHost destinations and the activity-level `AppViewModel` are fine.
- The camera binds to the NavBackStackEntry lifecycle and unbinds on dispose. That's fine, apart from items 23 and 24.
- No crash found. The Today screen stays on the old date after midnight (item 12).

**10. Automatic writes to the watch (without a tap)**
- Expected:
  - `confirmDevicePwd` on every connect and auto-reconnect (sets the watch clock and 24 h flag; existing behavior);
  - `syncPersonInfo` on connect, only when the profile is saved;
  - `syncPersonInfo` after Profile Save.
- Triggered by the watch, not the user:
  - `startCamera` runs automatically when the watch opens camera mode from its own menu (`AppNav` navigates to the Camera screen, then `vm.start()`);
  - `stopCamera` runs when you leave that screen.
- Only when you switch on "Forward notifications" (off by default):
  - `sendSocialMsgContent` and `offhookOrIdlePhone`.
- Housekeeping: `disconnectWatch` after a failed connect; HR/BP stop commands.
- Everything else that runs on its own is a read: steps, origin, sleep, sport CRC/slots, and the Watch-settings screen reads on open.
- Nothing else writes settings by itself.
