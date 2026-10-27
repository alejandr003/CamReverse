# CamRevert

> 🌐 **English** · [Español](README.md)

A **native Android client** (Kotlin) for **O-KAM Pro / VStarcam / Eye4** dual-lens cameras
(fixed lens + **PTZ dome**): video over direct RTSP, real PTZ control, light, night vision,
siren, motion detection and MicroSD access.

**No ads. No telemetry. No account of ours. Your video never leaves your network.**

Rebuilt through **reverse engineering** of the official XAPK (`com.okampro.oksmart`), reusing
its own native libraries: that way the controls speak exactly the same protocol the camera
expects, without inventing anything.

> Status: **PTZ control really works** (the 4 axes, their stops, centring, the cruise
> commands and the presets), verified against real hardware. See
> [`docs/en/PTZ-FINDINGS.md`](docs/en/PTZ-FINDINGS.md).

---

## What it does today

| Feature | Status |
|---|---|
| **RTSP video** (HD `av0_0` / SD `av0_1`, both lenses at once) | ✅ |
| Digital zoom 1.0x–8.0x, pan, double tap, full screen | ✅ |
| Lens selector `Fixed / Both / PTZ`, HUD (state / resolution / KB/s) | ✅ |
| Automatic reconnection + HD→SD fallback + RTP TCP/UDP switch | ✅ |
| Snapshot, global sound | ✅ |
| **P2P tunnel** (`libOKSMARTPPCS.so`) + **session authorisation** | ✅ |
| **PTZ**: 4 axes with their own stop, centring (`command=25`) | ✅ Verified on hardware |
| PTZ touch feedback (button turns green while pressed) | ✅ |
| **Cruise** vertical (26/27) and horizontal (28/29) | ✅ Accepted and executed |
| **Presets**: save (`set_sensor_preset.cgi`) and go to (`command=64+N`) | ✅ Accepted (go-to still to be checked visually) |
| **Automatic connection** of the P2P tunnel when the app starts | ✅ Toggle in Settings |
| Decoding with **software fallback** (+ force the decoder) | ✅ |
| White light (on/off, brightness), night vision | ✅ Brightness is both the switch and the level; the camera reports it back |
| Police siren and strobe | ✅ over CGI, with the real state read back from the camera |
| Human / motion detection / tracking | ✅ over CGI |
| MicroSD: info, mark file, format, clip browser | ✅ info and browser; clip playback needs the native player (see *Limitations*) |
| G.711 A-law intercom (duplex and push-to-talk) | ⚠️ Sending works; **receiving** is unconfirmed |
| **Remote access** (outside your Wi-Fi) | ⚠️ Through the P2P tunnel; currently used on the LAN |
| Running on a **32-bit** device | ❌ No alternative arm64-v8a binaries |

---

## Which cameras does it work with?

It is tested on **one** specific camera: a **dual-lens O-KAM Pro** ("gun-ball": fixed lens on
top + PTZ dome below), firmware from the **VStarcam/Eye4** family with the **"kaven" P2P SDK**
(`libOKSMARTPPCS.so`) and RTSP on ports **10555** (fixed) and **10554** (PTZ).

It should work with any device that meets **all three** conditions:

1. **It uses the VStarcam P2P SDK** (`libOKSMARTPPCS.so` / `cs2p2p_PPPP_*`). That is the
   O-KAM / O-KAM Pro / VStarcam / Eye4 family and some "gun-ball" clones.
2. **It publishes RTSP** on the LAN (on these devices, 10554/10555 with the
   `/tcp/av0_0` and `/tcp/av0_1` paths).
3. **It accepts the classic CGI commands** `decoder_control.cgi` / `camera_control.cgi`,
   which is the standard Foscam/VStarcam protocol.

**What you need to find out about your device** (the app does not guess it, see
[`docs/en/CONFIGURATION.md`](docs/en/CONFIGURATION.md)):

- `VUID` — printed on the label and visible in the official app.
- **The real 17-character P2P ID** and the **`DAS-…` address** — the official app downloads
  them from its cloud; you have to copy them from its preferences
  (`FlutterSharedPreferences` → `p2p_did`).
- **Camera user and password** — the ones the official app asks for in order to get video.
- **Your Eye4 account email and password** — only to request the session token.
- **Whether your firmware requires the `eye4_authentication.cgi` prelude.** The tested device
  requires it; if yours does not, it simply ignores that step (the app sends it anyway and it
  does no harm).

If your camera has **a single lens**, the app still works: the "PTZ" lens will show the same
stream or fail to connect, and you just use the selector in `Fixed` mode.

---

## Installation

### Option A — prebuilt APK

1. Download `app-debug.apk` (or build it yourself, below).
2. Copy it to the phone and install it (allow "install unknown apps").
3. When it opens, fill in **Settings** with your data (or build it with your
   `secrets.properties` and it will already be filled in).

> Requires **Android 7.0+** and an **arm64-v8a** CPU (any phone from the last ~8 years).

### Option B — build it

Requirements: **JDK 17**, Android SDK with **NDK 27.1.12297006** and **CMake 3.22.1** (the
Android Studio SDK manager installs them for you).

```powershell
$env:JAVA_HOME="C:\Program Files\Java\jdk-17"
.\gradlew.bat assembleDebug --console=plain
```

The output is `app\build\outputs\apk\debug\app-debug.apk` (~11 MB).

---

## Configuration: your keys (and how not to leak them)

**Your** camera and **your** account data are **not in the code**. Two ways:

### 1. At build time: `secrets.properties`

`secrets.properties` is **the `.env` of this project** (you can also call it `.env`; it is in
`.gitignore` and is **never pushed to GitHub**).

```powershell
Copy-Item secrets.properties.example secrets.properties
notepad secrets.properties
```

| Key | What goes there | Where to get it |
|---|---|---|
| `cam.ip` | Camera IP on your network | router / official app |
| `cam.vuid` (= `cam.uid`) | **VUID** (13 chars, starts with `VE`) | device label |
| `cam.p2pDid` | **Real P2P ID** (17 chars) | official app preferences |
| `cam.dasAddress` | **`DAS-…` address** (132 chars) | official app preferences |
| `cam.user` | **Camera** user (`admin`) | official app |
| `cam.password` | **CAMPASSWORD** — the **camera** password | official app |
| `cam.accountUser` | **YOUREMAIL** — your **Eye4** account email | the one you use in O-KAM Pro |
| `cam.accountPassword` | **PASSWORD** — that **account** password | idem |
| `cam.portFixed` / `cam.portPtz` | RTSP ports (`10555` / `10554`) | official app |
| `cam.ppcsLicense` | SDK licence string (vendor's, 108 chars) | optional; see below |

> ⚠️ **`CAMPASSWORD` is not `PASSWORD`.** The first one is the **camera** password (video and
> the SDK `login`). The second one is your **Eye4 account** password, and it is only used to
> request the session authorisation token (`eye4_authentication.cgi`). See
> [`docs/en/CONFIGURATION.md`](docs/en/CONFIGURATION.md).
>
> `cam.ppcsLicense` is the **manufacturer's** licence for the P2P SDK, not your data. It is
> optional: without it the app compiles and the tunnel still works through `cachedIps`; only
> `PPCS_Initialize` is skipped. It lives in `secrets.properties` so that it does not travel in
> the repository.

### 2. From the phone itself: the *Settings* dialog

Ignore the files completely and type everything in the app. It is stored in local
`SharedPreferences` (`camrevert_config`), with no cloud. The tab includes
**"EYE4 account (email)"** and **"Account password"**.

On save, the app reconnects video and the P2P tunnel automatically.

---

## Usage

- **Top bar**: P2P status (tap to connect/disconnect), global sound, P2P console and settings.
- **View selector**: `Fixed | Both | PTZ`. In landscape they sit side by side; in portrait,
  stacked.
- **PTZ tab**: 4-way pad + centre (`command=25`), "PTZ correction", "Cruise", presets
  (go / save) and X/Y axis inversion in case your dome moves the wrong way.
- **Light tab**: night vision (IR / colour / smart / starlight), white light (brightness
  slider, flash), siren row and strobe switch, both painted with the real state the camera
  reports.
- **Functions tab**: O-KAM-style grid (PTZ, sound, call, detection, night vision, light,
  siren, tracking, detection frame, flip image, **snapshot**, P2P console).
- **MicroSD tab**: card (total/free/state), scheduled recording, mark file, format,
  dates → hours → clips browser, status log and reboot.
- **Full screen** `[⛶]`: full-screen video with translucent floating controls
  (PTZ, zoom, sound, snapshot, microphone) that **do not block** pinch-zoom.
- **P2P console**: live log of *everything* happening in the tunnel — every connection
  attempt, every CGI and every raw answer from the camera. It is the tool this project was
  debugged with, and it is still useful when something does not answer.

---

## How it works (summary)

- **Video**: direct RTSP to the camera. It does not go through the P2P SDK (less CPU and RAM).
- **Control**: commands travel over the **P2P tunnel** of VStarcam's own SDK
  (`libOKSMARTPPCS.so`, from the original XAPK). The CGI strings are **not** sent over HTTP:
  the native library wraps them and pushes them into the tunnel.
- **Authorisation**: the camera executes no command until it receives a token from your Eye4
  account (`eye4_authentication.cgi`). That token is requested over HTTPS on every connection
  and is **not stored**. It is the app's **only** internet access.

All the detail (protocol, binary evidence, reverse-engineering method) is in
[`docs/en/HOW-IT-WORKS.md`](docs/en/HOW-IT-WORKS.md) and in the internal specification
[`AGENTS.md`](AGENTS.md) (Spanish).

---

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| **Video works but PTZ does nothing** (`result=-2` answers) | The Eye4 account is missing: fill in `cam.accountUser` / `cam.accountPassword` (or the Settings dialog). The P2P console says `SIN TOKEN DE CUENTA`. |
| `get_status.cgi` answers `result=-1;vuid=…` | **Normal** on this firmware, not a credential failure. |
| `SIN TÚNEL, se descarta` | The P2P tunnel is not up: tap the P2P status in the top bar and watch the console. |
| Video stutters / keeps reconnecting | Try `HD→SD` and let the app switch between RTP/UDP and TCP (or force it in Settings). |
| The dome moves the wrong way | PTZ tab → invert the X/Y axes. |
| `Ninguna combinación abrió el túnel` | Check `cam.p2pDid` (must be **17** characters) and `cam.dasAddress`. The console shows every attempt with its code. |
| A CGI answers `var result=-1` | That CGI **does not exist** in your firmware (e.g. `get_sensor_preset.cgi` here). |
| The P2P tunnel goes silent (controls stop answering but video keeps playing) | The built-in watchdog detects it, abandons the handle and rebuilds the tunnel once. If it happens again in the same run, close and reopen the app. |

---

## Privacy

- Video and audio **never** leave your network: they go over RTSP to the camera's IP.
- The app has **no** analytics, advertising or third-party SDKs.
- The **only** internet connection is `api.eye4.cn` (HTTPS), and only to request the session
  token the camera itself demands. If you do not configure the account, there is none.
- Your keys live in `secrets.properties` (on your PC, ignored by git) and in the phone's
  `SharedPreferences`.

---

## Project structure

```
app/src/main/cpp/            C bridge to PPCS_Initialize (libpcs_shim.so, ~5 KB)
app/src/main/jniLibs/        libOKSMARTPPCS.so + libvp_log.so (from the XAPK, unmodified)
app/src/main/java/
  com/lgsusdev/camrevert/    UI, configuration, RTSP, audio, LAN scanner, debug
  com/veepai/vp_log/         JNI facade to the SDK's native log
  com/vstarcam/              JNI facade to the P2P SDK (20 natives, names untouched)
  com/vstarcam/app_p2p_api/  SDK callback interfaces
docs/                        documentation (Spanish)
docs/en/                     English documentation
secrets.properties.example   key template (yours goes in secrets.properties)
AGENTS.md                    internal technical spec with the RE evidence
```

> The package and method names of `com.vstarcam.*` and `com.veepai.vp_log.*` **cannot be
> renamed**: JNI binding resolves by symbol (`Java_com_vstarcam_JNIApi_<method>`).

---

## Documentation

| Document | Content |
|---|---|
| [`docs/en/CONFIGURATION.md`](docs/en/CONFIGURATION.md) | Every key explained (what `YOUREMAIL`, `PASSWORD`, `CAMPASSWORD`, `VUID` are), `.env` vs `secrets.properties`, and how to obtain the P2P ID and the `DAS-` address. |
| [`docs/en/HOW-IT-WORKS.md`](docs/en/HOW-IT-WORKS.md) | Architecture, protocols (RTSP + P2P + CGI), session authorisation, and **how it was done**: reverse-engineering method, what was read (binaries, logs, captures), what was tested. |
| [`docs/en/COMMANDS.md`](docs/en/COMMANDS.md) | The full CGI command table: what each one does, its real answer, and which ones are useless on this firmware. |
| [`docs/en/PTZ-FINDINGS.md`](docs/en/PTZ-FINDINGS.md) | The PTZ investigation notebook: how the official app was intercepted, what was discovered and what the root cause was. |
| [`AGENTS.md`](AGENTS.md) | Technical specification with memory addresses, disassemblies and SDK error tables (Spanish). |

---

## Credits and acknowledgements

This project builds on other people's tools and work. Without them it would have been far
slower:

- **[`morluto/rea`](https://github.com/morluto/rea)** — the assisted **reverse-engineering**
  skill used to plan and execute the RE methodically: evidence first, artefacts second. This
  repository's way of working (hypothesis → binary evidence → test on the device) comes from
  there.
- **[`oleandor/okam-ha-native`](https://github.com/oleandor/okam-ha-native)** — Python client
  for the **CS2 P2P protocol** and the **Eye4 account flow**. It helped discover how to request
  the `userid` and the `loginToken` from `api.eye4.cn` (the key to the PTZ fix) and to
  understand the CS2 framing with lab probes.

Tools used during the research: **JADX** (DEX → Java), **llvm-objdump** (disassembly of
`libOKSMARTPPCS.so`), **Wireshark/pcap**, **adb + logcat**, a custom `libvp_log.so` shim to
read the SDK's internal log, and **AI assistance** to read disassemblies and cross-check
hypotheses.

---

## Licence

**MIT** for this project's source code — see [`LICENSE`](LICENSE).

⚠️ **The manufacturer's binaries are not MIT.** `app/src/main/jniLibs/arm64-v8a/` contains
`libOKSMARTPPCS.so` and `libvp_log.so`, **owned by VStarcam/Veepai**, extracted from the
official XAPK and **unmodified**. They are included for the sole purpose of
**interoperability** with hardware the user has bought. They are not covered by the MIT
licence, the original APK is not redistributed, and this project has no relationship with the
manufacturer.

If you are going to publish this repository, review that decision: the alternative is **not to
version the `.so` files** and ask the user to extract them from their own XAPK (or their own
phone).
