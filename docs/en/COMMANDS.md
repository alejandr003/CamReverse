# COMMANDS — master table for the O-KAM Pro / VStarcam camera (T23-N)

> 🌐 **English** · [Español](../COMANDOS.md)

Living document. Every command CamRevert can send, with its URL, what it does and **the
evidence**. It is updated every time something is discovered or verified.

Evidence sources:

| Code | What it is |
|---|---|
| **[LIVE]** | Verified against the real camera from CamRevert (answer copied verbatim) |
| **[OFFICIAL]** | Template extracted from the official O-KAM binary `libapp.so` (`C:\cam-reverse\endpoints.txt`) |
| **[CAPTURE]** | Observed in the official app's traffic (`vphook.txt` shim, 6,142 CGIs) |
| **[DOES NOT EXIST]** | Tested and the camera answers `var result=-1` (CGI absent in this firmware) |
| **[DISCARDED]** | Accepted by the camera (`result=0`) but with no observable effect → not used |

---

## 1. Conventions (important)

### 1.0 Authentication prelude — MANDATORY

Before any CGI, the session has to be authenticated:

```
eye4_authentication.cgi?loginAccount=<uid>&loginToken=<token>&
```

Without it, **all** commands return `result= 0;  result=-2;  ` (refused).

- In the official app the `loginToken` travels **percent-encoded** (`%2F` `/`, `%2B` `+`,
  `%3D%3D` `==`).
- The token is obtained with `POST https://api.eye4.cn/user/summary`
  (`name=<email>&oemid=VSTC`) and then
  `GET https://api.eye4.cn/login/token?userid=<id>&password=<md5(key)>&type=PC` (header
  `client_version: 10.0.1`).
- The credentials live **only** in `secrets.properties` (gitignored). Never in the repo.

### 1.1 Every `Cgi.*` ends in `&` or `?`

Exceptions: `GET /` and the credentials. The trailing `&` is not cosmetic: the firmware
expects it.

### 1.2 Answer vocabulary

| Answer | Meaning |
|---|---|
| `result= 0;  result=-2;  ` | **Refused** (no auth, or lost session, or two clients fighting) |
| `result= 0;  var result="ok";  ` | **Accepted** |
| `var result=-1` | **That CGI does not exist** in this firmware |
| `result= 0;  var cmd=<N>;  …` | Normal answer: the echo includes the fields sent + the real state |
| A bare `result= 0;  ` | Accepted, no payload (e.g. starting a stream) |

> **Measured nuance (2026-10-17)**: the "bare `result= 0;` = refused" rule applies to
> `trans_cmd_string.cgi` and `set_alarm.cgi`, **not** to `camera_control.cgi`: that one
> answers `result= 0;` **always**, even when it applies the change (`param=5&value=1&` →
> `flip=1`). The only way to know whether `camera_control.cgi` did anything is to **read
> back** `get_camera_params.cgi?`.

### 1.3 Golden rule: **one client only**

Two clients at once (phone + emulator) **fight** over the camera and produce false readings
(e.g. `light=0` when it is actually on). Lesson documented in `AGENTS.md`.

---

## 2. VERIFIED LIVE [LIVE]

These are tested against the camera with the real answer copied. **They are the ones
CamRevert must use.**

### 2.1 White light (LED) — the only real control is the brightness

| Command | What it does | Real answer |
|---|---|---|
| `set_whiteled_value.cgi?whiteled=0&` | LED **off** | `var whiteled=0;` |
| `set_whiteled_value.cgi?whiteled=100&` | LED at full power | `var whiteled=100;` |
| `set_whiteled_value.cgi?whiteled=<0..100>&` | **Intensity** (percentage) | `var whiteled=<n>;` |
| `get_whiteled_value.cgi?` | Read the current brightness | `var whiteled=<n>;` |

- `whiteled` is **the percentage** and it is the only control needed. No `light=1` and no
  `cmd=2109` required.
- **[DISCARDED]** `set_whiteled_value.cgi?mode=<0|1>&` → accepted, no effect.
- **[DOES NOT EXIST]** `display_pwm_ctrl.cgi` (it is in `libapp.so`, but the camera does not
  answer).
- **[DISCARDED]** `set_whiteled_value.cgi?ledtimes=<n>&` → unverified, no longer sent.

### 2.2 Strobe / "alarm flashing light" — **IT DOES EXIST**

| Command | What it does | Real answer |
|---|---|---|
| `trans_cmd_string.cgi?cmd=2109&command=0&alarmLed=1&` | Strobe **ON** | `var sirenStatus=0;  var alarmLed=1;  var alarmLedStatus=1;` |
| `trans_cmd_string.cgi?cmd=2109&command=0&alarmLed=0&` | Strobe **OFF** | `var alarmLed=0;  var alarmLedStatus=0;` |
| `trans_cmd_string.cgi?cmd=2109&command=2&` | **Read state** | `var lightStatus=1;  var sirenStatus=0;  var alarmLedStatus=<0\|1>;` |
| `trans_cmd_string.cgi?cmd=2108&command=1&alarmLedMode=2&` | Strobe mode [OFFICIAL] | to be confirmed |

[LIVE] `command=2` returns the state of the **three** actuators at once (`lightStatus`,
`sirenStatus`, `alarmLedStatus`) → good for syncing the UI.

### 2.3 Siren — **IT DOES EXIST**

| Command | What it does | Real answer |
|---|---|---|
| `trans_cmd_string.cgi?cmd=2109&command=0&siren=1&` | Siren **ON** | `var siren=1;  var sirenStatus=1;` |
| `trans_cmd_string.cgi?cmd=2109&command=0&siren=0&` | Siren **OFF** | `var siren=0;  var sirenStatus=0;` |
| `trans_cmd_string.cgi?cmd=2108&command=1&sirenMode=<n>&` | Siren mode [OFFICIAL] | to be confirmed |

> **How CamRevert uses it (2026-10-17).** With `command=0` the answer **echoes the request**
> (`siren`/`alarmLed` are just what was sent), so it cannot be used to paint the UI: for
> that, `cmd=2109&command=2&` is sent separately, which **is** the real state (§2.2). The
> Light tab sends the command and **reads back**; the siren row changes colour (translucent
> red = off, solid red = sounding) and text according to what the device answers, and the
> strobe switch is synchronised the same way.
>
> **Physical check still missing** (hearing the siren, seeing the strobe blink) in front of
> the camera: the state is read and written correctly, but whether the speaker actually
> sounds depends on the device.

### 2.4 Detection: sensitivity, humanoid frame and tracking

> **Audit on the device (2026-10-17): `cmd=4500&command=11&…` is a NO-OP in this
> firmware.** The two controls the official app's AI panel uses for sensitivity and tracking
> **do nothing**: they only answer `result= 0;  var cmd=4500;` and reading back leaves the
> previous value. The real controls are `cmd=2126` and `cmd=2127`.
>
> | Sent | Answer | Read back |
> |---|---|---|
> | `cmd=4500&command=11&sensitivity=1&` | `result= 0;  var cmd=4500;` | `sensitive=3` (**unchanged**) |
> | `cmd=4500&command=11&track=1&` | `result= 0;  var cmd=4500;` | `enable=0` (**unchanged**) |
> | `cmd=2126&command=0&sensitive=1&mark=123456789&` | `var sensitive=1;` | `sensitive=1` ✅ |
> | `cmd=2127&command=0&enable=1&` | `var enable=1;` | `enable=1` ✅ |

| Command | What it does | Real answer |
|---|---|---|
| `trans_cmd_string.cgi?cmd=2126&command=0&sensitive=<1\|2\|3>&mark=123456789&` | Sensitivity | `var sensitive=<n>;  var bHumanoidFrame=<0\|1>;` |
| `trans_cmd_string.cgi?cmd=2126&command=0&bHumanoidFrame=<0\|1>&` | Humanoid frame | `var sensitive=<n>;  var bHumanoidFrame=1;` |
| `trans_cmd_string.cgi?cmd=2126&command=0&humanoid_zoom=<n>&` | Humanoid zoom [OFFICIAL] | to be confirmed |
| `trans_cmd_string.cgi?cmd=2126&command=1&mark=123456789&` | **Read** detection | `var sensitive=<n>;  var bHumanoidFrame=<0\|1>;` |
| `trans_cmd_string.cgi?cmd=2127&command=0&enable=<0\|1>&` | Human tracking | `var enable=1;` |
| `trans_cmd_string.cgi?cmd=2127&command=1&` | **Read** tracking | `var enable=<0\|1>;  var track_status=<n>;` |
| `trans_cmd_string.cgi?cmd=4500&command=11&track=<0\|1>&` | **[DISCARDED]** tracking the official way | only answers `var cmd=4500;` — **changes nothing** |
| `trans_cmd_string.cgi?cmd=4500&command=11&sensitivity=<n>&` | **[DISCARDED]** sensitivity the official way | only answers `var cmd=4500;` — **changes nothing** |

**Careful, the scale is INVERTED!**

| UI | `sensitive` |
|---|---|
| High | **1** |
| Medium | **2** |
| Low | **3** |

`mark=123456789` is mandatory in the setter. The inverted scale is what CamRevert uses; the
physical check (that "High" triggers earlier than "Low") is still pending on the device.

**The three real-state readers** (so the UI does not lie if the command did not arrive):

| What | Read from |
|---|---|
| On/off and currently tracking | `cmd=2127&command=1&` → `enable`, `track_status` |
| Sensitivity and detection frame | `cmd=2126&command=1&mark=123456789&` → `sensitive`, `bHumanoidFrame` |
| Image flip | `get_camera_params.cgi?` → `flip` |

### 2.5 Detection zones / grid

| Command | What it does | Real answer |
|---|---|---|
| `trans_cmd_string.cgi?cmd=2123&command=3&sensor=0&` | **Read** 18 zones | `var pd_reign0..17=4194303;` |
| `trans_cmd_string.cgi?cmd=2123&command=3&sensor=1&` | Read 2nd sensor | same |
| `trans_cmd_string.cgi?cmd=2123&command=<N>&` | **Write** zones [OFFICIAL] | `N` and the `pd_reignX` values still to be confirmed |

- `sensor` seems to be ignored on this camera (same values with 0 and with 1).
- The value `4194303` = `0b111111111111111111111` = **21 bits** → the grid has 21 cells per
  sensor.
- The official app **does not send the write through `writeCgi`** (see §6, binary channel) →
  it is up to CamRevert to try the setter directly.

### 2.6 Hide the status LED

| Command | What it does | Real answer |
|---|---|---|
| `trans_cmd_string.cgi?cmd=2125&command=0&hide_led_disable=<0\|1>&` | Hide the status LED | `var hide_led_disable=1;` |
| `trans_cmd_string.cgi?cmd=2125&command=1&` | Read | `var hide_led_disable=<0\|1>;` |

**[LIVE] Verified on the device**: `hide_led_disable=1` → read back `1`; back to `0` → read
back `0`. It works, but **CamRevert does not expose it in the UI** (it is not in the user's
Functions list).

### 2.7 Motion (motion alarm)

| Command | What it does | Real answer |
|---|---|---|
| `set_alarm.cgi?enable_alarm_audio=0&motion_armed=<0\|1>&` | Arm/disarm detection | `result= 0;  var result="ok";` |
| `set_alarm.cgi?` | **Reads nothing**: write only, answers `var result="ok"` and nothing else | — |
| `get_params.cgi?` → `alarm_motion_armed` / `alarm_motion_sensitivity` | Read | `var alarm_motion_armed=1;  var alarm_motion_sensitivity=5;` |
| **`get_status.cgi?` → `alarm_status`** | **Read without leaking secrets** ✅ | `var alarm_status=<0\|1>;` |

**[LIVE] The good reader is `alarm_status`.** Measured with both values:

| `set_alarm.cgi?motion_armed=` | `get_status.cgi?` → `alarm_status` | `get_params.cgi?` → `alarm_motion_armed` |
|---|---|---|
| `0` | **`0`** | `0` |
| `1` | **`1`** | `1` |

That is why CamRevert uses `alarm_status` and **not** `get_params.cgi?`: the latter dumps
`WebPwd` and `wifi_wpa_psk` in the clear (§5) and its answer is 24 KB, while
`get_status.cgi?` is already requested for the card and carries no secrets.

`enable_alarm_audio=0` makes no practical difference (audio recording goes through
`set_recordsch.cgi?record_audio=`).

Related fields in `get_params.cgi?`: `alarm_motion_armed`, `alarm_motion_sensitivity`,
`alarm_snapshot`, `alarm_record`, `disable_audio`.

### 2.8 PTZ (9 commands verified on the device)

| Command | What it does |
|---|---|
| `decoder_control.cgi?command=0&onestep=0&` | Up |
| `decoder_control.cgi?command=1&onestep=0&` | Stop |
| `decoder_control.cgi?command=2&onestep=0&` | Down |
| `decoder_control.cgi?command=3&onestep=0&` | Stop |
| `decoder_control.cgi?command=4&onestep=0&` | Left |
| `decoder_control.cgi?command=5&onestep=0&` | Stop |
| `decoder_control.cgi?command=6&onestep=0&` | Right |
| `decoder_control.cgi?command=7&onestep=0&` | Stop |
| `decoder_control.cgi?command=25&onestep=0&channel=0&` | **Centre** (back to position) |

There are **no diagonals** and no `command=8`: the firmware exposes 4 axes, each with its
own stop. Cruise: `command=26/27` (vertical on/off) and `command=28/29` (horizontal on/off).
Presets: save with `set_sensor_preset.cgi?sensorid=255&presetid=N&`, go with
`command=64+N`.

Without the auth prelude (§1.0) they all return `result=-2`. Full detail in
[`PTZ-FINDINGS.md`](PTZ-FINDINGS.md).

### 2.9 Live video

| Command | What it does | Answer |
|---|---|---|
| `livestream.cgi?streamid=16&substream=0&` | Main stream (2,304×1,296) | `result= 0;` |
| `livestream.cgi?streamid=16&substream=1&` | Substream | `result= 0;` |
| `livestream.cgi?streamid=10&substream=4&` | **Preview** stream (the official app uses it when opening zones) | `result= 0;` |
| `audiostream.cgi?streamid=16&` | Audio | `result= 0;` |
| `callstatus.cgi?status=3&` | Call state | `result= 0;` |
| `snapshot.cgi?res=1&` | Snapshot | — |

### 2.10 MicroSD: disk, recording and clips

| Command | What it does | Real answer |
|---|---|---|
| `get_record.cgi?` | Disk state | `sdtotal=14816;  sdfree=5750;  record_sd_status=2;  tf_enable=1;  record_timer=5;  record_audio=1;` |
| `get_record_file.cgi?GetType=date&` | **Dates with recordings** | `record_date[0]="20261017";  var record_datenum=1;` |
| `get_record_file.cgi?GetType=file&dirname=20261017&PageSize=1000&` | **Clip list** (28,135 B = **399 clips**) | `record_name[0]="20261017000040_100.mp4";  record_size[0]=9600553;  …` |
| `del_file.cgi?name=<file>&` | Delete a clip | — |
| `set_formatsd.cgi?6` | Format the SD card [OFFICIAL] | — |

**List format — corrected on the device (2026-10-17).** It uses `[N]=`, not the bare form:

```
result= 0;  var record_name0=new Array();  var record_size0=new Array();
record_name[0]="20261017000040_100.mp4";  record_size[0]=9600553;
record_name[1]="20261017005401_100.mp4";  record_size[1]=5443004;   …
var record_filenum=47;  var totol_page=2;  var current_page=2;
```

Three things the listing hides, each of which cost a real bug:

1. **`PageSize` is not optional.** Without it the camera sends only the **last page** (~96
   clips, the end of the day) and it looks like "the day only has that". With
   `PageSize=1000` it returns the whole day in a single answer (399 clips, 28 KB).
2. **The answer arrives split by the tunnel.** The same day arrives in **two**
   `commandListener` callbacks (24,583 B + 3,552 B ≈ 28 KB). A parser that *replaces* the
   list on each chunk keeps only the 49 clips of the last one (it showed "49 clips ·
   99.1 MB" with 399 real ones). You have to **accumulate**. And
   `record_filenum`/`totol_page`/`current_page` arrive **out of sync** (they said 47 and 2
   with 399 clips): they are useless for paging.
3. **The `[N]` in the middle is mandatory in the regex.** The previous parser looked for
   `"name":"…"`, a form that **does not exist** in the answer; and because it also looked
   for `\b20\d{6}\b` in any answer, the file names themselves injected fake dates into the
   date row.

**Suffixes**: `_100` (262 clips, 2,879 MB) and `_011` (137, 411 MB) — they are the device's
**two simultaneous series**; the `_100` ones weigh ~4× more, so they are the main stream.
There is no parameter to filter by lens: `sensor=0|1` and `channel=0|1` were **ignored**
(all four probes returned both series, 399 clips).

**Recording by hour (real data)**: the day's 399 clips are spread over **18 hours**
(`00…14` and `20,21,22`); **between 15:00 and 19:59 there is not a single clip** on the
card. CamRevert's browser groups by hour and reflects that gap as it is.

#### Clip playback — the official app's route

> **REMOVED FROM THE UI (2026-10-17, item 3).** The ▶ on each row and the
> "Play"/"Stop" buttons **no longer exist** in CamRevert: they played nothing (the file
> stream does not come out through the tunnel) and **on top of that they left the tunnel
> deaf** — `writeCgi` returned `true` but the camera stopped answering any CGI for the rest
> of the session (see §2.10.1). The methods `playRecord`/`stopRecord`/`recordLiveStream`
> remain in `P2PControlManager` because they are the official app's and will be used by the
> native player, but **nobody sends them from the interface any more**. Every command in
> this table is **forbidden from the UI** until the native player exists.

| Command | What it does | Real answer |
|---|---|---|
| `record_fastplay.cgi?ctrl=1&playlist=<file>&` | **Start playing the clip on the device** [OFFICIAL] ⚠️ **leaves the tunnel deaf** | `result= 0;` (accepted) |
| `livestream.cgi?streamid=4&filename=<file>&` | **Open the file stream** [OFFICIAL] | `result= 0;` (12 B, no payload) |
| `record_fastplay.cgi?ctrl=0&` | Stop playback (does not recover the deaf tunnel) | `result= 0;` |
| `get_record_idx.cgi?dirname=…&offset=0&` | **[DOES NOT EXIST]** — the official app sends it, the camera does not answer | — |

The two commands the official app sends are sent correctly from CamRevert:

```
[writeCgi] OK   record_fastplay.cgi?ctrl=1&playlist=20261017221402_100.mp4&
[writeCgi] OK   livestream.cgi?streamid=4&filename=20261017221402_100.mp4&
```

**The file stream does not come out through the CGI tunnel** (measured, not assumed):

| Attempt | Result |
|---|---|
| `livestream.cgi?streamid=4&filename=<clip>&` | `result= 0;` and **12 bytes**: no payload |
| `record_fastplay.cgi?ctrl=1&playlist=<clip>&` + watching the RTSP from the PC | the live stream **stays** live (the OSD advances with the clock, not with the clip) |
| `<clip>?`, `sd/<clip>`, `record/20261017/<clip>`, `20261017/<clip>` through the tunnel | **no answer at all** |
| `download.cgi?file=<clip>&` | `result= 0;` (12 B) |

And **there is no way to read the stream through JNI**: `libOKSMARTPPCS.so` exposes `write`
but **not** `client_read` (§6), and `client_check_buffer` only returns metadata
(`client_check_buffer(h, arg, out1, out2)` → `iface->fn(...)`, no bytes).

**The real way out exists and it is in the XAPK**: `libOKSMARTPLAY.so` (10.8 MB) exports a
**complete Java P2P player API**, with `Java_com_veepai_AppPlayerApi_*` symbols:

```
createPlayer · setPlayerSource · checkPlayerSource · start · pause · resume · stop
speed · setProgress (seek) · setProgressCallback · screenshot · changeSize
setCacheDir · getTmpfileDirPath · switchCamera · enableSubPlayer …
startDown · stopDown · saveMP4 · saveMP4Progress · tsToMP4 · mergeTSFiles   ← download to MP4
startVoice · stopVoice · startVoiceRecord · stopVoiceRecord
```

Both the player **and** the downloader of the official app are in the package. What is
missing is not a data path, but **mapping that API** (the JNI stubs carry no signature: the
argument types have to be deduced from the disassembly) and moving the render to a
`SurfaceView` (the native side draws into a `Surface`, not a `TextureView`). That is an item
of its own, not a UI fix.

#### 2.10.1 Measured side effect: the tunnel goes **deaf**

Reproduced three times (23:43, 23:55 and 00:03 on 2026-10-17/18), between 30 s and 2 min
after sending `ctrl=1` + `livestream`:

| Symptom | Measurement |
|---|---|
| `writeCgi` returns | `true` (the `handle+0x204` flag stays at 1) |
| `commandListener` | **zero** callbacks: not one more answer |
| The `get_status.cgi?` probe (the only one that always answers) | **silence** |
| `stateListener` | still `2` (`TCP_ERR_WORKING`): the app paints "online" |
| RTSP | keeps playing normally |
| `record_fastplay.cgi?ctrl=0&` to undo it | **never arrives**: its answer no longer gets in |
| Recovery | restart the app only |

The trigger is **intermittent**: the same commands, repeated in ten other tests, did not
cause it. Design consequence: **the ▶ was removed** and the app gained a watchdog (§6.1)
that detects the silence and abandons the deaf handle **without closing it** — closing it the
native way (`disconnect`+`destroy`, or `clientConnectBreak`) **aborts the app with
`SIGABRT`** (`FORTIFY: pthread_mutex_lock called on a destroyed mutex`), because the SDK
thread stays stuck in the file stream and wakes up after `destroy` has already freed the
memory of the `handle+0x1dc` mutex.

### 2.11 Identity and state

| Command | What it does | Real answer |
|---|---|---|
| `get_params.cgi?` | **All** the state (careful, it leaks secrets!) | see §5 |
| `get_status.cgi?vuid=<vuid>&` | State by VUID | — |
| `get_camera_params.cgi?` | Video parameters | `cameratype=3;  resolution=5;  flip=0;  enc_bitrate=768;  MainStreamWidth=1280;  MainStreamHeight=720;  night_vision_mode=0;  full_color_mode=0;  full_color_default=1;  ircut=1;` |
| `get_image_param.cgi?` | Image parameters | — |
| `wifi_scan.cgi?` | WiFi networks | `ap_number=1;  ap_ssid[]…` |
| `reboot.cgi?` | **Reboot the camera** | `result= 0;` but **the real reboot takes ~25 s** (the camera answers before going down). **Measured:** of 3 consecutive attempts (~4-5 min apart) only the **first two** took effect; the third was accepted but the camera stayed up (`runtime=402`). It seems to have a **cooldown between reboots** — for the test in point 6, either wait or cut the traffic with `iptables` |
| `set_alias.cgi?alias=<name>&` | Camera name [OFFICIAL] | — |
| `set_datetime.cgi?tz=<n>&` | Time zone [OFFICIAL] | — |

### 2.12 Real connection sequence of the official app [CAPTURE]

Exact order O-KAM follows when connecting (we copy it because it works):

```
get_params                            → full state
cmd=2017&type=N                       → plans/calendars
cmd=2127                              → tracking heartbeat (×2575 times!)
cmd=8000&command=0                    → board version (audio_16k, get_board_version=10)
cmd=4121&action=3                     → handshake
cmd=2126                              → detection
cmd=2123&sensor=0|1                   → zones
cmd=4109&command=0                    → 12h mode (osd_12h_mode)
cmd=4500&command=0                    → handshake
cmd=2131&command=1&DevActiveTime=10   → periodic keepalive!
cmd=2109&command=2                    → light/siren/led state
get_status?vuid=…
livestream.cgi?streamid=16&substream=0 + audiostream.cgi?streamid=16& + callstatus.cgi?status=3&
```

`cmd=2131&command=1&DevActiveTime=10&` is the **keepalive**: the official app repeats it
endlessly. A direct candidate for CamRevert's session stability (pending item).

### 2.13 Night vision and image flip — verified

| Command | What it does | Real answer |
|---|---|---|
| `camera_control.cgi?param=37&value=0&fullcolormode=<0\|1\|2>&fullcolordefault=0&` | Night-vision mode | `result= 0;` (always) → read back 👇 |
| `get_camera_params.cgi?` → `full_color_mode` | **Read** the mode | `var full_color_mode=<0\|1\|2>;  var night_vision_mode=<n>;` |
| **`camera_control.cgi?param=5&value=<0\|1>&`** | **Flip the image** ✅ | `result= 0;` → read back 👇 |
| `get_camera_params.cgi?` → `flip` | **Read** the flip | `var flip=<0\|1>;` |
| `camera_control.cgi?param=3&value=<0\|1>&` | **[DISCARDED]** this was CamRevert's "flip" | `flip` **does not change** (measured) |

**[LIVE] Measured with read-back:**

| Sent | `get_camera_params.cgi?` afterwards |
|---|---|
| `param=3&value=1&` | `flip=0` (**touches nothing**) |
| `param=5&value=1&` | **`flip=1`** ✅ |
| `param=5&value=0&` | **`flip=0`** ✅ |
| `param=37&value=0&fullcolormode=1&fullcolordefault=0&` | **`full_color_mode=1`** ✅ |
| `param=37&value=0&fullcolormode=0&fullcolordefault=0&` | **`full_color_mode=0`** ✅ |

`param=3` does exist in the official binary (`camera_control.cgi?param=3&value=`), but on
**this** firmware it does not move `flip`. The one that moves it is `param=5`.

### 2.14 "Functions" tab audit — verdict (2026-10-17)

| Function | What CamRevert sends | Does it work? | Real-state reader | Verdict |
|---|---|---|---|---|
| PTZ | `decoder_control.cgi?command=N&onestep=0&` | ✅ yes | — (visible in the video) | correct |
| Sound | player `volume` | local | — | correct |
| Call | `callstatus.cgi?status=<0\|3>&` | ✅ accepted | — | correct |
| **Motion detection** | `set_alarm.cgi?…motion_armed=` | ✅ yes (`var result="ok"`) | **`get_status.cgi?` → `alarm_status`** | **fixed**: the checkbox now reflects the camera |
| Night vision | `camera_control.cgi?param=37&…fullcolormode=` | ✅ yes | `get_camera_params.cgi?` → `full_color_mode` | correct |
| White light | `set_whiteled_value.cgi?whiteled=<0..100>&` | ✅ yes | `get_whiteled_value.cgi?` | correct |
| **Siren** | `cmd=2109&command=0&siren=` | ✅ yes | `cmd=2109&command=2&` → `sirenStatus` | **fixed** (real state) |
| **Strobe** | `cmd=2109&command=0&alarmLed=` | ✅ yes | `cmd=2109&command=2&` → `alarmLedStatus` | **fixed** (real state) |
| **People tracking** | `cmd=2127&command=0&enable=` | ✅ yes | `cmd=2127&command=1&` → `enable` | **fixed**: it used to send `cmd=4500` (no-op) |
| Detection frame | `cmd=2126&command=0&bHumanoidFrame=` | ✅ yes | `cmd=2126&command=1&` → `bHumanoidFrame` | correct |
| **Flip image** | `camera_control.cgi?param=5&value=` | ✅ yes | `get_camera_params.cgi?` → `flip` | **fixed**: it used to send `param=3` (no-op) |
| Take photo | local `TextureView` | local | — | correct |
| P2P console | — | local | — | correct |
| **Sensitivity** | `cmd=2126&command=0&sensitive=&mark=123456789&` | ✅ yes | `cmd=2126&command=1&` → `sensitive` | **fixed**: it used to send `cmd=4500` (no-op) |
| **Human detection** | `cmd=2106&command=4&humanDetection=` | ✅ accepted | **no reader exists** | **unverified** |
| Hide status LED | *(not in the UI)* | ✅ yes | `cmd=2125&command=1&` | verified, not exposed |

**The exception is `humanDetection`**: `cmd=2106&command=4&humanDetection=1&` only answers
`var cmd=2106;` and **no** CGI publishes its state (`get_alarm.cgi?` does not exist;
`get_params.cgi?` and `get_camera_params.cgi?` carry no human-detection field, only the
*capabilities* `support_humanDetect` / `support_humanoidFrame` / `support_AI`). Practical
consequence: the "Human detection" checkbox **cannot reflect reality**, so "Apply detection"
may turn it off without it being visible. It is left that way, documented, until it can be
confirmed in front of the camera.

---

## 3. DISCOVERED IN THE OFFICIAL BINARY [OFFICIAL] — still to be tested

The 305 templates come from `C:\cam-reverse\endpoints.txt` (strings from `libapp.so`). Many
belong to **other models** (doorbells, smoke sensors, timers…). These are the ones that
could apply to this camera:

### 3.1 Audio / custom voice

| Command | What it would do |
|---|---|
| `trans_cmd_string.cgi?cmd=2135&command=0&switch=<n>&` | Enable custom audio |
| `trans_cmd_string.cgi?cmd=2135&command=0&urlJson=<json>&` | Upload audio definitions |
| `trans_cmd_string.cgi?cmd=2135&command=1&voicetype=<n>&` | Choose voice |
| `trans_cmd_string.cgi?cmd=5001&command=2&audio_play_mode=<n>&` | Playback mode |
| `trans_cmd_string.cgi?cmd=5001&command=3&audio_play_status=<n>&` | State |
| `trans_cmd_string.cgi?cmd=5001&command=6&urlJson==<json>&` | Audio list (note the `==`!) |

### 3.2 Plans / calendars (`cmd=2017`)

| Command | What it would do |
|---|---|
| `trans_cmd_string.cgi?cmd=2017&command=1&mark=212&motion_record_plan1=<…>&` | Motion recording calendar |
| `trans_cmd_string.cgi?cmd=2017&command=2&mark=212&motion_push_plan1=<…>&` | Notification calendar |
| `trans_cmd_string.cgi?cmd=2017&command=3&mark=212&record_plan1=<…>&` | Continuous recording calendar |
| `trans_cmd_string.cgi?cmd=2017&command=5&mark=213&light_plan1=<…>&` | Light calendar |
| `trans_cmd_string.cgi?cmd=2017&command=6&mark=212&privacy_plan1=<…>&` | Privacy calendar |
| `trans_cmd_string.cgi?cmd=2017&command=11&mark=<m>&type=<1\|2\|3\|5\|6\|10\|14\|25>&` | Read plans by type |
| `trans_cmd_string.cgi?cmd=2017&command=9&` | — |

[LIVE] Read with `type=1|2|3`: `motion_record_plan1..21`, `motion_record_enable=0`,
`motion_push_plan…`/`motion_push_enable=5`, `record_plan…`/`record_plan_enable=0`. All plans
`-1` = undefined.

### 3.3 Recording

| Command | What it would do |
|---|---|
| `set_recordsch.cgi?record_audio=<0\|1>&` | Audio in the recording |
| `set_recordsch.cgi?record_cover=1&record_timer=<n>&` | Overwrite + block duration |
| `set_recordsch.cgi?record_smart_mode=<n>&` | Smart recording |
| `trans_cmd_string.cgi?cmd=4149&command=2&recordType=<n>&` | Recording type |
| `trans_cmd_string.cgi?cmd=2204&command=2&record_resolution=<n>&` | Recording resolution |

### 3.4 Image / video

| Command | What it would do |
|---|---|
| `camera_control.cgi?param=<1..40>&value=<v>&` | Image parameters (brightness, contrast, flip…) |
| `camera_control.cgi?param=37&value=0&fullcolormode=<0\|1\|2>&fullcolordefault=<0\|1>&fullcolorstart=<…>&` | **Night vision / full colour** |
| `camera_control.cgi?param=39&value=0&x_percent=<n>&` | Crop/zoom |
| `set_image_param.cgi?set_default=1&` | Restore default image |
| `trans_cmd_string.cgi?cmd=2105&command=2&videoFormat=<n>&` | Video format |
| `trans_cmd_string.cgi?cmd=4109&command=1&osd_12h_mode=<0\|1>&` | **12h/24h clock** |
| `trans_cmd_string.cgi?cmd=4106&command=1&black_mode=<n>&` | Black mode |
| `trans_cmd_string.cgi?cmd=4120&command=11&play_mode=<n>&` | OSD playback mode |

### 3.5 Light and advanced alarms

| Command | What it would do |
|---|---|
| `trans_cmd_string.cgi?cmd=2017&command=5&mark=213&light_plan1=<…>&` | Schedule the light |
| `trans_cmd_string.cgi?cmd=4500&command=11&enable=<n>&alarm_light=<n>&alarm_sound=<n>&alarm_sound_volume=<n>&sensitivity=<n>&track=<n>&frame=<n>&` | **Full alarm linkage** (light + sound + volume + sensitivity + tracking + frame) |
| `trans_cmd_string.cgi?cmd=4500&command=19&detect_type=<n>&alarm_light=<n>&alarm_sound=<n>&enable=<n>&` | Same, by detection type |
| `trans_cmd_string.cgi?cmd=2109&command=0&light=<n>&` | Direct light (variant of `whiteled`) |
| `trans_cmd_string.cgi?cmd=2108&command=1&lightMode=<n>&` | Light mode — **[DISCARDED]**: tested, `LightingLamp` does not change |

### 3.6 Network / system / misc

| Command | What it would do |
|---|---|
| `trans_cmd_string.cgi?cmd=2106&command=15&WifiEnhancedMode=<n>&` | Enhanced WiFi |
| `trans_cmd_string.cgi?cmd=2106&command=4&humanDetection=<n>&` | Human detection (another route) |
| `trans_cmd_string.cgi?cmd=2106&command=4&trigger_interval=<n>&` | Interval between triggers |
| `trans_cmd_string.cgi?cmd=2106&command=2&lowPower=<n>&` / `command=18&Smart_Electricity_Sleep_Switch=` | Low power |
| `trans_cmd_string.cgi?cmd=2106&command=21&aov_wake_interval=<n>&` | Wake-up interval (AOV) |
| `trans_cmd_string.cgi?cmd=2131&command=1&DevActiveTime=10&` | **Session keepalive** (to use) |
| `trans_cmd_string.cgi?cmd=4105&command=1&reboot_time=<…>&` | Scheduled reboot |
| `trans_cmd_string.cgi?cmd=4104&command=1&sleepflag=<n>&` | Sleep |
| `trans_cmd_string.cgi?cmd=4113&command=2&power_off=<n>&` | Power off |
| `trans_cmd_string.cgi?cmd=4121&action=3&reconnect_jd=1&` | Handshake variant with reconnect |
| `trans_cmd_string.cgi?cmd=8000&command=0&` | `audio_16k=0;  get_board_version=10` |
| `trans_cmd_string.cgi?cmd=2400&command=<0\|1>&AiType=<n>&` | AI type |
| `trans_cmd_string.cgi?cmd=2401&command=1&record_mode=<n>&` | AI recording mode |
| `trans_cmd_string.cgi?cmd=2201&command=21&data=<…>&` | Generic data |
| `set_users.cgi?pwd_change_realtime=1&…` | Password change (**do not use without asking!**) |
| `set_ipc_binding_info.cgi?binding_body=<…>&` | Binding |
| `set_update_push_user.cgi?` | Push |
| `auto_download_file.cgi?server=<…>&` | File download |
| `get_factory_param.cgi?` / `get_feeder_param.cgi?` / `get_gps_info.cgi?` | Misc parameters |

### 3.7 From other models (probably irrelevant here)

`cmd=2206` (smoke/fire), `cmd=2301`/`2310`/`2311` (sensors), `cmd=4114` (doorbell/music),
`cmd=4115` (lamp), `cmd=4119` (SIM), `cmd=4120&command=9&list=` (playlist),
`cmd=4149&…recordType=`, `mutil_sensor_mipi.cgi`, `set_sensor_preset.cgi`, `cmd=2499&CmdType=`,
`cmd=2160`/`2161`/`2163`, `cmd=4100`/`4101` (gblinkage), `cmd=4116`, `cmd=4161`/`4162`,
`cmd=9000&userid=`.

---

## 4. THEY DO NOT EXIST in this firmware [DOES NOT EXIST]

| Command | Evidence |
|---|---|
| `get_record_idx.cgi?dirname=…&offset=0&` | No answer (the official app sends it, the camera ignores it) |
| `display_pwm_ctrl.cgi?…` | No answer |
| `trans_cmd_string.cgi?cmd=4121&action=3&` | Only answers `var cmd=4121;` |
| `trans_cmd_string.cgi?cmd=4500&command=0&` | Only answers `var cmd=4500;` |
| `trans_cmd_string.cgi?cmd=2131&command=1&DevActiveTime=10&` | Answers `var cmd=2131;` — **but it works as a keepalive** |

---

## 5. SECURITY — `get_params.cgi?` leaks secrets in the clear

The answer contains, **in plain text**:

- `WebPwd="…"` ← the camera's web password
- `wifi_ssid` and `wifi_wpa_psk` ← the WiFi key
- `CPU="T23-N"`, `rtsp_auth_enable=1`, `onvif_support_enable=0`, `port=55580`

CamRevert **MUST mask** them before showing or logging them. Never push these answers to
GitHub.

---

## 6. Binary channel (`JNIApi.write`) — the known hole

The capture shim (`C:\cam-reverse\hook\vplogshim.c`) hooks:

| JNI method | Status |
|---|---|
| `writeCgi` | ✅ hooked (6,359 calls captured) |
| `writeCgiNVR` | ✅ hooked (0 calls seen) |
| `write` (binary channel) | ✅ hooked **but 0 calls seen** |
| `create`, `connect`, `login`, `clientSetVuid` | ✅ hooked |

**Conclusion:** the switches on the official app's "Monitoring function" screen (humanoid
frame, tracking, **flashing light**, saving the grid) **do not go through `writeCgi` nor
through `write`** → they use another native route that has not been identified yet.

**But it no longer matters:** the templates from `libapp.so` (§2.2, §2.4) give the exact
commands, and **all of them have been verified live against the camera**. There is no need to
extend the shim.

The binary channel **is** used for: audio (§6, `JNIApi.write`) and probably clip playback.

### 6.1 Deaf-tunnel watchdog (2026-10-17/18)

The CGI channel can die **silently** (§2.10.1): `writeCgi` → `true`, `stateListener` stuck at
`2`, RTSP playing, and **not one answer**. Since the state never leaves `ONLINE`, the retry
supervisor did not notice. CamRevert watches it like this:

| Piece | Value / behaviour |
|---|---|
| Pending window | `sendRaw` opens it after every accepted `writeCgi`; **idempotent** (not rewritten while it stays open) |
| Closing it | Any `commandListener` payload closes it |
| Ticker | Every `CGI_WATCHDOG_TICK_MS = 2_000`; only when `ONLINE` **and** `authorized` (zero traffic at rest) |
| Probe | `CGI_WATCHDOG_PROBE = get_status.cgi?` — **the only one that always answers** |
| Thresholds | `CGI_ANSWER_TIMEOUT_MS = 8_000` (silence → send the probe) + `CGI_PROBE_GRACE_MS = 6_000` (probe silent too → deaf) |
| Recovery | `abandonHandle()` (**no native call at all**) + a new cascade → verified: deaf → `TÚNEL CONFIRMADO` → `eye4_auth=1` in ~10 s, without closing the app |
| Ceiling | **`DEAF_RECOVERY_MAX = 1`** per run; on the second episode it warns "close and reopen the app" and **does not insist** |
| Test hook | `adb shell am broadcast -n com.lgsusdev.camrevert/.debug.CgiDebugReceiver --ez deaf true` |

**Why the handle is not closed the native way** (measured, two aborts): killing the deaf
handle with `disconnect`+`destroy` causes
`SIGABRT — FORTIFY: pthread_mutex_lock called on a destroyed mutex (handle+0x1dc)`, because
the SDK thread is still stuck in the file stream. `abandonHandle()` leaves the handle to the
SDK (controlled leak) and the app rebuilds the tunnel with a new `create`. The timings come
from a **real false positive**: a healthy session took 4.46 s to answer a 2.5 KB
`get_status.cgi?`, which is why the threshold is 8 s and not 4 s.

---

## 7. Pending items of this table

- [ ] Confirm the grid setter: `cmd=2123&command=<N>&pd_reign0..20=<bits>&`
- [ ] Confirm `cmd=2108&command=1&alarmLedMode=<n>&` and `sirenMode=<n>&`
- [x] ~~Confirm `cmd=2125&command=0&hide_led_disable=<0|1>&`~~ → **it works** (§2.6); not in the UI
- [x] ~~Test `camera_control.cgi?param=37&…fullcolormode=` (night colour)~~ → **it works**, read `full_color_mode` (§2.13)
- [ ] Test `cmd=4109&command=1&osd_12h_mode=` (12/24 h clock)
- [x] ~~Test full playback: `streamid=4&filename=` + reading the stream over the binary channel~~ → **route closed** (§2.10): no payload over the tunnel and no `client_read` in the JNI. The only way out is `libOKSMARTPLAY.so` (item of its own). **Forbidden from the UI**: `record_fastplay.cgi?ctrl=1` left the tunnel **deaf** (§2.10.1)
- [ ] **Item of its own**: integrate `libOKSMARTPLAY.so` (player or MP4 downloader) to watch clips without the ▶
- [ ] **Confirm physically** that the siren sounds and the strobe blinks (§2.3)
- [ ] **Confirm physically** that "High" detects earlier than "Low" (§2.4, inverted scale)
- [ ] Find out whether a reader exists for `humanDetection` (§2.14): today the checkbox cannot reflect reality
- [ ] Confirm `camera_control.cgi?param=39&value=0&x_percent=<n>&` (digital zoom)
- [ ] Mask `get_params.cgi?` secrets in CamRevert
- [ ] **Deaf tunnel**: identify the exact trigger (intermittent; there is no path from the UI any more) and evaluate process self-restart as a second recovery (§2.10.1, §6.1)
