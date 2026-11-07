# T30 firmware check (read-only)

Device number **3238** · firmware **03.01.01** · test version **03.01.01.00**
Checked 2026-11-07. Nothing was sent to the watch. No firmware was downloaded.

## Result

**Veepoo's server lists no firmware for device 3238.**

Both servers return the same empty answer:

```
{"Version1":null,"Version2":null,"Version3":null}
```

The SDK reads only `Version3`. When it is null, the SDK reports
`onCheckFail(13)` = `SERVER_NOT_HAVE_NEW` ("the server has no such version").
`getOadVersion` would call `onNetOadInfo(3238, "03.01.01", "", netIsNew=false)`.

So, as far as the SDK's update channel goes, there is no newer firmware.

## The endpoint

| Item | Value |
|---|---|
| Method | `GET` (plain `URLConnection`, headers `accept: */*`, `connection: Keep-Alive`) |
| Release server | `https://www.vphband.com:9001/api/` (`isDebug=false`, the default) |
| Debug server | `http://120.79.208.77:88/api/` (`isDebug=true`; the demo app sets this) |
| Path | `Version/GetVersionForSport` |
| Query | `type=android`, `devicetype=<deviceNumber>`, `testVersion=<deviceTestVersion>` |
| Signature / auth | None. Values are just URL-encoded. |
| Not sent | `deviceVersion`, MAC, app version. The local version is only used on the phone for the compare. |

Copy-paste check:

```
curl -s "https://www.vphband.com:9001/api/Version/GetVersionForSport?type=android&devicetype=3238&testVersion=03.01.01.00"
```

`OadSetting.hostUrl`, if set, swaps the base for `<hostUrl>/api/`. The demo leaves it unset.

### Response when an update exists (parsed by `vp_bs.vp_c(String)`)

```
{"Version3": {"DeviceType": "...", "NewVersion": "...", "NewMD5": "...",
              "NewUrl": "...", "NewSize": "...", "Des": "..."}}
```

These map to `OadFileBean` as deviceNumber, deviceVersion, md5, downUrl, size, and des.
All six must be non-empty or the SDK treats the reply as "no version".

**Version compare:** dots are removed, then the result is parsed as a number.
`03.01.01` becomes `30101`. An update counts as newer only when net > local.
`NewUrl` is the firmware download. **Do not fetch it.**

## Which SDK calls are safe

| SDK method | What it does | Touches watch? | Safe now? |
|---|---|---|---|
| `getOadVersion(OadSetting, OnGetOadVersionListener)` | Only the GET above, on a thread. Returns `onNetOadInfo` / `onNetOadDetailInfo`. No download. | No | **Safe** |
| `checkVersionAndFile(...)` with `isAutoDownload=false` | Same GET. Then `onRemoteOadFileGet` + `onCheckSuccess(url)` | No | Safe in practice, but one flag away from unsafe |
| `checkVersionAndFile(...)` with `isAutoDownload=true` | GET, then downloads the firmware to `<extFiles>/vpsdk/<num>_<ver>.<ext>` and checks MD5. **If the MD5 matches, it calls `findOadModelDevice` on its own.** | **Yes** | **Unsafe** |
| `findOadModelDevice(...)` (`vp_br.vp_c`) | If `isOadModel=false`: calls **`enterOad`** (a BLE write that puts the watch into firmware-upgrade/bootloader mode), then disconnects, scans, and connects to the DFU device | **Yes** | **Unsafe** |
| `enterOad(IBleWriteResponse)` | Sends the enter-OAD command over BLE | **Yes** | **Unsafe** |
| `startJLDeviceOTAUpgrade`, `startZkOtaUpgrade`, `startNordicOtaUpgrade`, demo `startOad*` / `DfuService` / `EasyDfu2` | Send firmware to the watch | **Yes** | **Unsafe** |
| `confirmDevicePwd` → `PwdData.deviceNumber/deviceVersion/deviceTestVersion` | Normal connect. This is where the three inputs above come from. | Yes (normal, read-only) | Safe |

If our app adds an "update available?" badge, use only `getOadVersion`,
or the plain GET above. Never use `checkVersionAndFile` with auto-download on.

## Side notes

- **Telemetry.** `vp_s` also calls `System/AddSDKUserInfo` on the same server.
  It sends phone model, Android version, SDK level, DeviceNumber, DeviceTestVersion, **MAC**, and `VPProtocolVersion=2.3.95.15`.
  `VPOperateManager` triggers it internally. Worth knowing for privacy, but it doesn't touch the watch.
- **Other HTTP in vpprotocol:** `system/getthemes` (watch-face list; GET with dialShape, binProtocol, maxLength, deviceNumber, deviceVersion, appType, appVersion) and the AGPS `.pgl` downloads (starcourse.location.io / rx-networks.cn).
- **Other SDK libs** (vpbluetooth, libble, libdfu, libfastdfu, libcomx, JL_Watch, jl_bt_ota, jl_rcsp, BmpConvert, abpartool): **no** hard-coded HTTP URLs. The firmware query exists only in vpprotocol.

## Caveats

- This is the **SDK's** update channel. H Band itself (`com.veepoo.hband`) was not decompiled. It may use another endpoint (for example, one for JieLi-chip watches), so "null" here doesn't prove H Band would show nothing.
- Only `testVersion=03.01.01.00` was tried, exactly as the SDK would send it. Two requests in total: one to each server.

## Evidence (javap)

- `com/veepoo/protocol/http/vp_a`: base URLs and the `sendGet` builder (`?` + `k=v&...`, URL-encoded)
- `com/veepoo/protocol/operate/vp_bs`: request params, path, JSON parse, compare, auto-download, then `vp_br`
- `com/veepoo/protocol/operate/vp_br`: `enterOad`, `disconnectWatch`, `startScanDevice`, `connectDevice`
- `VPOperateManager.getOadVersion / checkVersionAndFile / findOadModelDevice / enterOad`
- Demo: `oad/activity/OadActivity.java` (sets `setDebug(true)`, `setAutoDownload(false)`)
- Disassembly dumps: `F:\Temp\claude\207c9c44772c6492\218912ee-a952-45a6-a329-99fd1d97ae3e\scratchpad\fw\` (vp_bs.txt, vp_a.txt, vp_br.txt, vpom.txt)
