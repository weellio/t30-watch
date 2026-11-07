# What the T30 supports

Read from the watch on 2026-11-07 (first connect, phase 1).
Source: `support-dump.txt` from the app's Debug screen.

| | |
|---|---|
| Name / MAC | T30 / (per watch) |
| Firmware | 03.01.01 (test 03.01.01.00) |
| Model number | 3238 |
| Password | `0000` worked. Status `CHECK_AND_TIME_SUCCESS` (clock set too) |
| Feature packages sent | 1 and 2 only. 3, 4, 5 never arrive = everything in them unsupported |
| Notification packets sent | 1 only |
| Stores on the watch | 5 days of daily data, 3 days of sport-mode data |
| Battery | 4 levels only (no exact %) |

## Supported

| Feature | Detail | Roadmap phase |
|---|---|---|
| Heart rate, manual measure | `heartRateDetect` | 2 |
| Heart rate, auto monitoring | `autoHeartDetect` = ON now | 3 (history), 6 (setting) |
| Heart rate alarm | `heartRateWarning` | 6 |
| Blood pressure | `bp`, `bpTag=1`. Auto BP = ON now | 2 (live), 3 (history) |
| Steps / distance / calories | new algorithm (`newCalcSport`) | 3 |
| Sleep | basic only (`precisionSleep` = no, `sleepTag=0`) | 3 |
| Sport modes | multi-sport, type 1, 3 days kept | 8 |
| Alarms | new-style alarms (`alarm2`, `alarmTag=3`). No text alarms | 6 |
| Sedentary reminder | `longseat` | 6 |
| Wrist-raise to wake | `nightTurnSetting`. ON now | 6 |
| Screen-on duration | `screenLightTime` | 6 |
| Metric / imperial | `metricSystem` | 6 |
| 12 / 24 h clock | settings packet still said 12 h after we sent 24 h. See open question | 6 |
| Wear detection | ON | 6 |
| SOS | ON | 6? (unclear what it does on this watch) |
| Skin-tone setting (HR sensor) | level 1 | 6 |
| Disconnect reminder | OFF now | 6 |
| Low-power mode | `lowPower` | 6 |
| Women's health | `womenTag=3` | 8 |
| Camera shutter | shake-to-shoot | 8 |
| Notifications to the watch | see below | 7 |

## Notifications (packet 1)

| On now | Supported, off now |
|---|---|
| Phone calls, SMS, Line, Other | WhatsApp, Facebook, Instagram, Gmail, Twitter/X, LinkedIn, Snapchat, Skype, WeChat, QQ |

Not supported: Messenger, Telegram, TikTok, Viber, KakaoTalk, Zalo, and the Chinese-only apps.
Message limits: `allMsgLength=20`, `contactMsgLength=5`. The "Other" category is the catch-all for any other app.

## Not supported

SpO2 (all variants) · HRV · ECG · body temperature · blood glucose · stress · breathing rate · fatigue ·
weather · contacts · music control · find-device-from-phone · find-phone · HID (despite the 0x1812 service) ·
countdown · text alarms · drinking · world clock · watch faces (0 market, 0 custom, no screen styles) ·
AGPS · everything in packages 3–5.

## What this changes in the roadmap

| Phase | Change |
|---|---|
| 2 Live data | Heart rate + blood pressure. **No SpO2.** |
| 5 Export | Health Connect: heart rate, steps, distance, calories, sleep, blood pressure. No SpO2. |
| 6 Settings | 24 h goes through the personal-settings packet. HR monitoring interval is fixed (on/off only). **No do-not-disturb API at all**: the phone pauses forwarding during Android DND instead |
| 8 Extras | Find device, music control, contacts, ECG, temperature: **all no**. Camera shutter, sports modes, women's health: yes |
| 9 Risky | Watch faces: looks like none. OTA: not a JieLi chip; SDK reports chip family "UNKONW" |
