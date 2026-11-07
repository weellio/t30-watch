# Progress

## Current state (2026-11-07, ~05:00)

| Phase | State |
|---|---|
| 0 Setup | Done |
| 1 Connect | **Done, tested on the watch** |
| 2 Live data (HR, BP) | **Done, tested on the watch** |
| 3 History sync | **Sync tested 11:38** (log only, phone locked): auto-connect, full + incremental read, sleep, workouts. UI not yet checked |
| 4 Charts + dashboard | Built overnight, compiles, **not tested** |
| 5 Export (CSV, Health Connect) | Built overnight, compiles, **not tested** |
| 6 Watch settings | Built overnight, compiles, **not tested** |
| 7 Notifications to the watch | Built overnight, compiles, **not tested** |
| 8 Extras (remote shutter) | Built overnight, compiles, **not tested** |
| 9 Watch faces / OTA | Not started (needs B's OK). Firmware check done: no update exists |

B asked (04:20) to "build the rest" while asleep. Nothing was sent to the watch overnight, and the new
build was **not installed** (installing auto-starts the service, which would connect and sync).

### Public repo + screenshots (14:35, v0.1.1)

- Public: https://github.com/weellio/t30-watch (MIT). Releases carry the debug APK and the demo APK.
- **Demo build type** (`gradlew :app:installDemo`, id `com.t30watch.demo`): seeds 3 weeks of made-up data
  (`demo/DemoData.kt`), shows a pretend connected watch, never touches Bluetooth. Used for README screenshots
  (`docs/screenshots/`, status bar cropped off). Screenshot driver scripts were in the session scratchpad.
- Fixed while shooting: status-bar icons now follow the app's light/dark choice; History/Progress dates use the
  phone's style (Nov 2); chart corner numbers say "max"/"min"; History sleep goal line uses the profile goal.

### Afternoon build (13:54, installed; 63 unit tests pass; screens not yet seen)

B's asks: US units, explain HR/BP ("good or bad, trending, does exercise matter"), many themes, gamify, workouts, sleep talk.

| Added | Where |
|---|---|
| US units by default (ft/in, lb, mi, 1:05 PM); stored metric. More → Appearance → Units | `util/Units.kt`, `ui/more/MoreScreen.kt`, `util/Time.kt` |
| 15 colour styles + light/dark/system + pure black, fade between | `ui/theme/Palettes.kt`, `Theme.kt`, More → Appearance |
| **Insights tab** (replaces the Measure tab; Measure is now a pushed screen): HR resting/active/zones/trend, BP AHA category + moving-reading flag, sleep consistency + tips, activity trend, "what do these numbers mean" | `insights/` (pure, tested), `ui/insights/` |
| **Progress** screen + Today card: XP/levels, 3 streaks, 3 daily quests, 23 badges, weekly recap, workout log with PBs | `game/` (pure, tested), `ui/rewards/` |
| BP badge + "resting/active" label on Today | `ui/today/TodayScreen.kt` |

Rules worth knowing: data from before the first counted day is ignored by the game; BP readings with ≥100 steps in the slot are
"moving" and left out of averages; Stage 2 = amber "check with a cuff", red only for the AHA severe range.
Watch pattern when worn: HR and BP alternate, roughly every 10 min. Values 0/1/2/6 = no reading (dropped).
Amazon affiliate idea: declined for now (app isn't distributed; Associates bans links in apps without separate approval).

### First real sync (11:38, installed with the phone locked)

- Auto-connect on install → password → features → battery → sync in **~12 s total**. First sync: 95 slots, sleep, 1 workout in ~7 s.
- Second sync (after reinstall) resumed at package 95, read 2 records, skipped workouts (CRC unchanged). Incremental works.
- Watch data starts 03:45 (first connect set its clock). **From 04:25 the heart value is a flat `6` with 0 steps**: looks like the
  watch's "no reading / not worn" value. The 30..200 filter drops it. **Confirmed by B: the watch hadn't been worn in a
  long time**, so the old sleep/workout and the empty days are expected. Re-check auto HR after a day of real wear.
- Days 1–4 come back as one all-zero placeholder (`allPackage=0`). Now marked done so they aren't re-read every sync.
- Sleep: the watch returned one session dated **2026-09-15** (leftover from before). Today now only shows sleep that ended
  in the last ~36 h; History still lists it.
- Workout slot 0: outdoor run **2024-04-30** (factory/previous data). Units confirmed: distance km, kcal.
- Settings packet still says `24小时制=false` → 24-hour switch in Watch settings still to test.

---

## Morning test plan (in this order)

Wear the watch. Keep nRF Connect and H Band uninstalled or closed.

1. **Install:** `.\gradlew.bat :app:installDebug` (or Run ▶ in Android Studio).
   The app should auto-connect within a few seconds and show "Syncing…" on the **Today** tab.
2. **First sync** (all 5 stored days) can take a minute or two. Then check Today: steps, heart-rate curve,
   last night's sleep. If it fails, the error is on the Today tab; logs via More → Debug → Share logs.
3. **History** tab: switch Day / Week / Month and the four metrics.
4. **More → Profile:** enter height, weight, age, sex, goals → Save (sent to the watch).
5. **Watch tab → Watch settings:**
   - Turn **24-hour clock** on → check the watch face shows 24 h (was the open question).
   - Add an alarm for Monday only → check the watch shows **Monday** (bit-order check, see below).
   - Try sedentary, wrist-raise, heart-rate alarm, screen-on time.
6. **More → Notifications:** switch on, tap "Open Notification access", allow T30 Watch.
   Send yourself a WhatsApp / Telegram / SMS → it should buzz the watch.
7. **Watch settings → Remote shutter:** allow camera, shake/tap the watch → photo in Pictures/T30 Watch.
8. **More → Export:** CSV (share sheet), then "Allow Health Connect" → "Write to Health Connect".

Things the code assumes and needs confirming on the watch:
- Alarm repeat bits: rightmost char = Monday (API doc). If a Monday alarm shows as Sunday, flip `repeatString`/`parseRepeat` in `ble/WatchSettingsClient.kt`.
- Workout distance/kcal units (assumed km / kcal) and whether slot 0 is newest.
- Sedentary threshold range the firmware accepts (UI allows 30–240 min).

---

## What was built overnight

| Area | Files |
|---|---|
| History reads: today's totals, 5-minute data per day (resumes from last package; finished days skipped), sleep, workouts (only changed slots by CRC), profile push. Inactivity watchdog + hard cap on every read | `ble/HistoryClient.kt` |
| Watch settings: personal packet (24 h, units, auto HR/BP, disconnect reminder, SOS), screen-on time, wrist-raise, HR alarm, sedentary, alarms (add/edit/delete), notification categories, push messages, language, camera mode | `ble/WatchSettingsClient.kt` |
| Sync engine: after every connect (profile first if saved), every N min (More → Auto-sync), or Sync button; optional Health Connect export after each sync | `data/sync/SyncEngine.kt` |
| Auto-reconnect with backoff (5 s → 5 min) when a wanted connection drops | `data/WatchRepository.kt` |
| Foreground service "keep connected" + boot/update restart | `service/WatchService.kt`, `service/BootReceiver.kt` |
| Notification forwarding (NotificationListenerService): category mapping, "Other" with app name, DND pause, dedupe, mute list, calls from the dialer notification | `service/NotificationForwarder.kt`, `data/notify/NotificationLog.kt` |
| Room v3: activity slots, heart samples, BP samples, sleep sessions, workouts, day totals (auto-migration) | `data/db/HistoryEntities.kt`, `app/schemas/…/3.json` |
| CSV export (one file per type, share sheet) | `export/CsvExporter.kt` |
| Health Connect export (write-only; steps, distance, active kcal, HR, BP, sleep + stages, workouts; stable IDs so re-export updates) + privacy screen | `health/` |
| Charts (bars, lines, sleep-stage strip) drawn on Canvas, no library | `ui/charts/Charts.kt` |
| Bottom navigation: Today · History · Measure · Watch · More | `MainActivity.kt` |
| Today dashboard, History (day/week/month × steps/HR/sleep/BP), Watch settings, More (profile, background, notifications, export, debug), Remote shutter (CameraX) | `ui/today`, `ui/history`, `ui/watchsettings`, `ui/more`, `ui/camera` |

Research saved in `docs/research/`:
- `sdk-history-sync.md`, `sdk-settings-notifications.md`: bytecode-verified API briefs (read before touching those areas)
- `github-veepoo-projects.md`: what exists on GitHub (nothing gives the T30 more than the SDK)
- `firmware-version-check.md`: how to check for firmware without touching the watch (none available)

### Decisions made overnight

- **24 h clock** is set through the personal-settings packet (`changeCustomSetting`); the flag in the password step doesn't change the watch face.
- **Heart-rate interval** can't be changed on the T30 (only on/off; fixed 5 min).
- **Do-not-disturb** doesn't exist on the watch; the phone holds notifications while Android's DND is on.
- **Factory reset / power off** are not exposed anywhere.
- Notifications only go out for categories that are switched on in the watch's own list (same rule the watch applies).
- Calls are detected from the dialer's notification, so no phone/call-log permissions are needed. Answer/reject from the watch is not wired (would need `ANSWER_PHONE_CALLS`).

### Independent code review (05:00), fixed before handing over

Report: `docs/research/code-review-2026-11-07.md`. Fixed:
- History cursors were saved before the data reached Room (a failure could skip days forever) → each day/workout is stored first, then its cursor moves.
- One failed sync step aborted the whole sync → steps are independent; partial errors show on Today.
- Telegram/Messenger never forwarded (T30 lacks those categories) → fall back to "Other" with the app name.
- Auto-reconnect backoff never grew and retried a wrong password forever → waits for each attempt, stops on password rejection; also retries when Bluetooth comes back after a reboot.
- One out-of-range BP value failed the whole Health Connect export → filtered to Health Connect's limits.
- Also: records dated from the SDK's own date (sync across midnight), stale-cursor self-heal, auto-sync interval change can't cancel a running read, Today rolls over at midnight, settings cache dropped if a write isn't confirmed, service start can't crash, single notification worker.

Known, not fixed (low risk, noted for later):
- A late SDK reply after a timeout could be taken by the next command of the same type.
- A live heart-rate session (up to 5 min) holds the queue, so notifications wait until it ends.
- The "stop ringing" message can overtake a queued call notification.

### Privacy (decided 2026-11-07)

- B: "don't send stuff to veepoo's server". `INTERNET` is removed in the manifest (`tools:node="remove"`).
  Checked first: the SDK's `AddSDKUserInfo` POST is inside `catch (Exception)`, so it fails quietly. `ACCESS_NETWORK_STATE`
  is kept (read-only; removing it could throw in library code). Firmware/watch-face lookups now only from the PC.
  Installed 14:01: `dumpsys package` shows no INTERNET; connect, sync and Health Connect export all still work, no crash.

---

## Phase 2 (done, tested 04:13)

Heart rate 21 s session, 16 values, saved. Blood pressure 103/66 in 27 s, saved.
Reconnect-after-update hang solved: nRF Connect was holding the watch (see `docs/sdk-notes.md` gotcha 21).

| Piece | File |
|---|---|
| `liveHeartRate(maxSeconds)` and `measureBloodPressure()` as Flows. Each holds the command queue for the whole session; stop is always sent | `ble/VeepooClient.kt` |
| Session state, one measurement at a time, saves results to Room | `data/MeasureRepository.kt` |
| Measure screen | `ui/measure/` |

## Phase 1 (done, tested 03:44)

- Scan → connect → password `0000` → features → battery in ~2.5 s. Support list: `docs/t30-support.md`.
- The password reply arrives *after* the settings packet; "done" waits for both.
- Chip: not JieLi, SDK says UNKONW.

| Piece | File |
|---|---|
| Serialized command queue (Mutex, per-command timeout, SDK calls on main thread) | `ble/CommandQueue.kt` |
| SDK wrapper: init, scan, connect, password, features, battery, disconnect, `command()` helper | `ble/VeepooClient.kt` |
| Raw callback log, rotating 5 × 1 MB | `ble/RawLog.kt` |
| Reflection dump of SDK models | `ble/SdkDump.kt` |
| Connect screen (now the Watch tab), Debug screen | `ui/connect/`, `ui/debug/` |

Files on the phone (`adb pull /sdcard/Android/data/com.t30watch/files`):
`logs/raw.log*`, `support-dump.txt`, `VpSDK/…`, `exports/…`.

## Build setup

| Thing | Value |
|---|---|
| AGP | 9.3.3 (newest line Android Studio 2026.1.1 supports) |
| Gradle | 9.5.1 (wrapper) |
| Kotlin | 2.3.21, compiled by AGP's built-in Kotlin |
| KSP / Hilt / Room | 2.3.13 / 2.60.1 / 2.8.5 |
| Health Connect / CameraX | 1.1.0 / 1.6.2 |
| compileSdk / targetSdk / minSdk | 37 / 37 / 26 |
| JDK | Android Studio's bundled JBR 21 (`JAVA_HOME` for CLI builds) |
| Package / applicationId | `com.t30watch` |
| Android SDK | `D:\Files\androidSDK` (in `local.properties`, git-ignored) |
| Gradle cache | `G:\cache\gradle` (`GRADLE_USER_HOME`) |

## Next

1. B runs the morning test plan above; fix what breaks.
2. Phase checks with B, then phase 9 only with explicit approval.
