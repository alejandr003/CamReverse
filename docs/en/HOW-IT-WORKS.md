# How CamRevert works (and how it was figured out)

> 🌐 **English** · [Español](../COMO-FUNCIONA.md)

This document explains **what the app does internally** and **how it was discovered**. The
raw evidence (memory addresses, disassemblies, error tables) lives in
[`../../AGENTS.md`](../../AGENTS.md) (Spanish); what you can follow is here.

---

## 1. Overview

The app does **two independent things** with the same camera:

```
                ┌─────────────────────────────────────────────┐
   VIDEO   ──►  │  Direct RTSP (port 10555 / 10554)            │  ← does not go through the P2P SDK
                └─────────────────────────────────────────────┘

                ┌─────────────────────────────────────────────┐
   CONTROL ──►  │  P2P tunnel (libOKSMARTPPCS.so)              │  ← the CGIs travel INSIDE the tunnel
                │  + session authorisation (Eye4 token)        │
                └─────────────────────────────────────────────┘

   AUTHORISATION ► api.eye4.cn (HTTPS)  ← the only internet access, only when connecting
```

**Why this way:** video over RTSP is far cheaper (hardware decode, zero SDK CPU) and control
**has to** go through the P2P tunnel because it is the only path the firmware really honours
(see §5).

---

## 2. The native SDK: why we reuse manufacturer binaries

`libOKSMARTPPCS.so` (728 KB) implements VStarcam's **proprietary P2P protocol** ("kaven"):
rendezvous, relay, NAT traversal and the CS2 framing. Reimplementing it is a project in
itself, so the app **reuses the original `.so`, unmodified**:

- `app/src/main/jniLibs/arm64-v8a/libOKSMARTPPCS.so` (from the official XAPK)
- `app/src/main/jniLibs/arm64-v8a/libvp_log.so` (its logger, a `NEEDED` dependency)

On top of it sits a Java facade with **the exact names** the binary expects
(`com.vstarcam.JNIApi` with its 20 native methods and `com.veepai.vp_log.VPLogApi`), because
JNI binding resolves **by symbol** (`Java_com_vstarcam_JNIApi_<method>`): renaming any of
those methods breaks the binding.

### 2.1 The detail that blocked everything: `PPCS_Initialize`

In the XAPK, **nobody calls `PPCS_Initialize`**: neither `.init_array` (it does not exist)
nor any of the 20 JNI stubs. Without initialising, the SDK returns `-5` and the tunnel never
comes up.

CamRevert solves it with **its own C shim** (`app/src/main/cpp/ppcs_shim.c` → 4.8 KB) that
does `dlopen`/`dlsym` on `PPCS_Initialize` and passes it the initialisation string. With
that, `connect` stopped returning `-5`.

### 2.2 Seeing inside the SDK: the native log

The SDK writes its log through `vp_printf` (level 4) and `libvp_log.so` only hands it to the
listener callback if the level matches. CamRevert registers that listener and **dumps the
`.so`'s internal log to the app console**. Without it, a failed `connect()` only returns a
normalised code and the real reason is invisible.

> Alternative method that also worked during the investigation: replacing `libvp_log.so` in
> the official app with a shim that writes to a file. That is where the `run2`/`run3`
> captures came from, and they revealed the authorisation prelude.

---

## 3. The P2P tunnel: how it is brought up

Mandatory sequence (SDK internal flags):

```
JNIApi.create(did, did2) → clientSetVuid(handle, vuid)
   → connect(handle, mode, target, p2pType, cachedIps)
   → writeCgi(probe)           ← disambiguates code 4
   → login(handle, user, CAMPASSWORD)
   → ★ eye4_authentication.cgi (session authorisation) ★
```

Three things that took a while to understand (all verified in the binary):

1. **`target` is the TargetID (DID/VUID), not an IP.** Passing an IP produces
   `ERROR_PPCS_INVALID_ID`. The IP goes in the 5th argument, `cachedIps`.
2. **The 2nd argument of `create` is not a licence: it is a *second* DID**, and the SDK only
   uses it if it is **exactly 17 characters** long. That is why you must pass the **real P2P
   ID** and not the 13-character VUID.
3. **Code `4` is ambiguous**: in the SDK it means both "session 4" and "error". It is
   disambiguated with a probe `writeCgi`, which returns `false` immediately if the tunnel is
   not up.

Since there is no documentation of which exact combination opens the tunnel on each
firmware, `connect()` tries a **cascade** of combinations, each with **its own handle**, and
the console shows every attempt with its code and label
(`[das/p2ptype1-nullips] connect: rc=4 en 1 ms — …`). On the tested device, the one that
opens the tunnel is **`das/p2ptype1-nullips`**.

---

## 4. Control: the CGIs travel **inside** the tunnel

The key detail that explains a thousand lost hours: **the app does not send HTTP**.
`client_write_cgi` from the `.so` **wraps** the string internally:

```c
snprintf(buf, len+1024, "GET /%sloginuse=%s&loginpas=%s&user=%s&pwd=%s&", cgi, …);
```

Hence three golden rules of the project:

1. The strings do **not** carry `GET /` or `loginuse=`/`loginpas=` (the native adds them).
2. They **must end in `&` or `?`** (the native writes `loginuse=` right after).
3. The 3rd argument of `writeCgi` is a **timeout**, not the length (passing the length
   produced a ~40 ms timeout and the commands were lost).

That is why testing the CGIs with `curl` against the camera **does not work** for control:
the embedded HTTP server is a leftover from the SoC SDK and answers `200 OK` without
dispatching the command to the motors.

---

## 5. Session authorisation: `eye4_authentication.cgi`

**This was the missing piece**, the one that solved the problem (see
[`PTZ-FINDINGS.md`](PTZ-FINDINGS.md)).

Even with the tunnel up and `login()` returning `true`, the camera **refuses to execute any
command** until it receives a signed token from the Eye4 account:

```
eye4_authentication.cgi?loginAccount=<userid>&loginToken=<token>&
      → result= 0;  eye4_auth=1;
```

- **Without** the prelude: `result= 0;  result=-2;  ` for everything (the only exception is
  `get_status.cgi`, which always answers — that is why the probe seemed to say everything
  was fine).
- **With** the prelude: `var result="ok"` and the dome obeys.

The token is requested from `api.eye4.cn` on every connection (`/user/summary` →
`/login/token`) and **is not stored**. Details and implementation in
[`CONFIGURATION.md`](CONFIGURATION.md) and `p2p/Eye4Auth.kt`.

---

## 6. Command table

Everything goes through `decoder_control.cgi` / `trans_cmd_string.cgi` / etc., with the
wrapper from point 4. Status = what the **real hardware** returned.

| Action | String | Status |
|---|---|---|
| Up / stop | `decoder_control.cgi?command=0&onestep=0&` / `…=1…` | ✅ verified visually |
| Down / stop | `…command=2…` / `…=3…` | ✅ |
| Left / stop | `…command=4…` / `…=5…` | ✅ |
| Right / stop | `…command=6…` / `…=7…` | ✅ |
| Centre the head | `…command=25&onestep=0&channel=0&` | ✅ |
| Vertical cruise on/off | `…command=26…` / `…=27…` | ✅ accepted and executed |
| Horizontal cruise on/off | `…command=28…` / `…=29…` | ✅ |
| Save preset N | `set_sensor_preset.cgi?sensorid=255&presetid=N&` | ✅ accepted |
| Go to preset N | `…command=64+N&onestep=0&` | ✅ accepted (visual check pending) |
| Read preset | `get_sensor_preset.cgi?` | ❌ does not exist in this firmware (`result=-1`) |
| White light: brightness **and** on/off | `set_whiteled_value.cgi?whiteled=0..100&` — **`0` = off**, `100` = maximum | ✅ verified visually |
| Light state | `get_whiteled_value.cgi?` → `var whiteled=0..100; var light=0\|1;` | ✅ returns the exact value set |
| "PWM" brightness | `display_pwm_ctrl.cgi?common=1&value=1..100&` | ❌ **does not exist** in this firmware (0 bytes of answer) |
| `cmd=2109&command=0&light=0\|1&` (the official one) | — | ➖ **redundant**: `whiteled` already turns it on and off. Its `lightStatus`/`sirenStatus` are **echo**, not state |
| Siren / strobe | `trans_cmd_string.cgi?cmd=2109&command=0&siren=1&` / `…alarmLed=1&` | ✅ |
| Night vision | `camera_control.cgi?param=37&value=0&fullcolormode=X&…` | ✅ |
| Human detection / tracking | `trans_cmd_string.cgi?cmd=2106&command=4&humanDetection=1&`, `cmd=4500&command=11&track=1&` | ✅ |
| General status and MicroSD | `get_status.cgi?`, `get_record.cgi?`, `set_recordsch.cgi?…` | ✅ |
| Heartbeat (the one the official app uses) | `trans_cmd_string.cgi?cmd=2127&command=1&` | **Not needed**: in the 25-minute test the session answered at 5/10/15/20 min **with no heartbeat at all**. It was left out to avoid introducing an unverified command. |

> **Session lifetime (measured)**: after authorisation, the tested device's session kept
> accepting commands for **≈20 minutes** (last correct reading at 19 min 57 s after
> `connect`) with no maintenance traffic. The app protects itself anyway: if a `result=-2`
> ever appears on an already authorised session, it **re-authorises once** (new token).

> There is no `command=8` and **there are no diagonals**: the firmware exposes 4 axes, each
> with its own stop, plus centring and the cruise commands.

---

## 7. Video: RTSP

| Lens | HD (main) | SD (sub) |
|---|---|---|
| Fixed | `rtsp://USER:CAMPASSWORD@IP:10555/tcp/av0_0` | `…av0_1` |
| PTZ | `…@IP:10554/tcp/av0_0` | `…av0_1` |

Design decisions (all from testing on the device):

- **RTP over UDP first, with TCP as fallback.** In Media3 the UDP path has an automatic
  fallback to TCP; the TCP interleaved path **has none**. The app switches on its own after
  3 consecutive failures and remembers it.
- **Socket timeout at 30 s.** `RtspMediaSource.setTimeoutMs` is the data socket's
  *read-timeout*: with the 8 s default, any pause from a WiFi camera produced constant
  flicker.
- **Generous `LoadControl`** in the `ExoPlayer.Builder`, because the defaults are calibrated
  for files and with an irregular bitrate cause unnecessary *rebuffering*.
- **`TextureView` + `CENTER_CROP`** on every version (SurfaceView ignores `scaleX` before
  Android 10 and produces z-order artefacts with the overlaid controls).
- **No TCP health probes**: measuring with our own sockets to 10554/10555 opened connections
  the RTSP server ended up closing and contributed to the dropouts. The HUD measures
  passively.
- **Decoder fallback**: `ExoPlayer` is built with
  `DefaultRenderersFactory.setEnableDecoderFallback(true)`, so if the hardware decoder
  rejects the profile or the resolution (main is 2304×1296) Media3 falls back to the
  **software** decoder. Settings can also **force** the software decoder
  (`MediaCodecSelector.PREFER_SOFTWARE`) for devices with very little headroom.

---

## 8. How all of this was found out (method)

The work was organised with the reverse-engineering skill
**[`morluto/rea`](https://github.com/morluto/rea)**: **evidence first, then the hypothesis,
and finally the test on the device**. Every claim in `AGENTS.md` has a memory address, a log
or a capture behind it.

### 8.1 What we used

| Tool / source | Purpose |
|---|---|
| **[`morluto/rea`](https://github.com/morluto/rea)** (REA skill) | Method: unpack the artefact, locate the symbols, read the disassembly, form hypotheses and verify them. |
| **[`oleandor/okam-ha-native`](https://github.com/oleandor/okam-ha-native)** | Python client for the CS2 protocol and the Eye4 account flow: this is where **how to request the `userid` and the `loginToken`** came from (the key to the fix), plus the CS2 framing for lab probes. |
| **JADX 1.5.6** | Decompile the APK's 5 DEX files to Java: CGI inventory, ports, constants. |
| **llvm-objdump** | Full disassembly of `libOKSMARTPPCS.so` (`ppcs_disasm.txt`): this is how `client_create`, `client_connect`, `client_write_cgi`, `client_login`, the PPCS error table and the `serverParam` validations were read. |
| **Our own `libvp_log.so` shim** | Installed into the official app to **capture its internal log** to a file. That is where the captures of the authorisation prelude came from. |
| **pcap (Wireshark)** | The official app's LAN conversation with the camera, to see the CS2 framing in the clear. |
| **adb + logcat + a CGI injector** | Live probes against the camera without recompiling (a debug `BroadcastReceiver` that accepts a base64 CGI). |
| **AI assistance** | Reading long disassemblies, cross-checking hypotheses, generating the probe clients and keeping track across libraries. |

### 8.2 What was ruled out (and why)

- **Frida / code injection**: ruled out by project decision.
- **`LD_PRELOAD` / `wrap.<pkg>`**: breaks the emulator's ARM translation bridge
  (`has unexpected e_machine: 183`).
- **Replacing `libOKSMARTPPCS.so`**: unviable (1510 exported symbols).
- **Defining the `Java_com_vstarcam_JNIApi_*` symbols ourselves**: the `.so` defines the same
  ones and wins in its own `dlsym`.
- **A 100 % Python P2P client** (`okam-ha-native`) for this camera: the old CS2 scheme routed
  to **another device**. It stayed as a protocol lab, not as a control path.

### 8.3 How it was tested

1. **Capturing the official app**: its prelude and its CGIs were intercepted, including the
   exact joystick command (`decoder_control.cgi?command=N&onestep=0&`).
2. **Systematic probing on the device**: a CGI injector over *broadcast* made it possible to
   try dozens of variants against the real camera without recompiling, seeing the raw answer
   in logcat.
3. **Visual confirmation**: the movements were verified **by watching the camera** (4 axes,
   stops and centring), not just by the `ok` answer.

---

## 9. What was achieved (and with what evidence)

| Achievement | Evidence |
|---|---|
| **Understanding the manufacturer's control protocol** | Disassembly of `libOKSMARTPPCS.so` with addresses and symbol names. |
| **Bringing the P2P tunnel up from our app** | `TÚNEL CONFIRMADO (connect + writeCgi + login)` in the SDK log. |
| **Discovering the session authorisation** | Capture of the official app's log with `eye4_authentication.cgi` + proof: without it `result=-2`, with it `var result="ok"`. |
| **Discovering the Eye4 account flow** | Reproduced with `POST /user/summary` + `GET /login/token` (cross-checked with `okam-ha-native`). |
| **Actually moving the dome** | All 9 PTZ commands accepted and movement confirmed visually. |
| **Documenting everything** | `AGENTS.md` (evidence) + these documents (explanation). |

---

## 10. Known limitations

- **`client_read` is not exposed to JNI**, so the video stream and the MicroSD clips
  **cannot** be received over the tunnel: video always goes over RTSP, and clip playback over
  the tunnel is out of scope.
- **Intercom audio reception**: it depends on the camera sending it over the command channel
  (`ClientCommandListener`). **Sending** does work; reception is unconfirmed.
- **Audio channel** (default 1) is not determined in the binary: it is adjustable.
- The app does **not discover** the P2P ID or the `DAS-` address on its own: they have to be
  copied (see [`CONFIGURATION.md`](CONFIGURATION.md)).
- Flip parameters, night-vision sub-modes and the units of `sdtotal`/`sdfree` are still
  **inferred**.
- **arm64-v8a only** (there are no 32-bit binaries in the original XAPK).

---

## 11. Credits

- **[`morluto/rea`](https://github.com/morluto/rea)** — the reverse-engineering skill with
  which all the work was organised. Without its "evidence first" discipline this would have
  been a succession of guesses.
- **[`oleandor/okam-ha-native`](https://github.com/oleandor/okam-ha-native)** — Python client
  for the CS2 protocol and the Eye4 account flow: the clue that led to
  `loginAccount`/`loginToken`, and the lab probes of the framing.
- **VStarcam / Veepai**: authors of the SDK the app reuses (and without which this would not
  exist). See the legal note in [`../../LICENSE`](../../LICENSE).
