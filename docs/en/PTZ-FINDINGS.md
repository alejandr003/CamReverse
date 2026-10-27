# PTZ findings — the investigation notebook

> 🌐 **English** · [Español](../HALLAZGOS-PTZ.md)

> **Result**: ✅ **solved**. The camera demands an **Eye4 account token** in every session
> (`eye4_authentication.cgi`) before executing any command. As soon as it is sent, the dome
> moves: the 4 axes, their stops, centring, the cruise commands and the presets.
>
> This is the *publishable* version of the notebook (identifiers replaced by
> placeholders). The raw notebook with the full captures lives on the machine where the
> research was done and is **not versioned**, because it contains camera credentials.

---

## 1. The symptom

CamRevert brought the P2P tunnel up, `login()` returned `true`, and `get_status.cgi?`
returned real data from the camera (2.5 KB). **But every other CGI** always returned the
same thing:

```
result= 0;  result=-2;                       (24 bytes)
```

The dome did not move. And worse: the same CGIs the official app sent successfully to that
very camera did nothing from CamRevert. Called directly with `curl` against the camera they
answered `200 OK` **and still moved nothing**.

Initial hypotheses (all reasonable, all wrong):

| Hypothesis | Why it looked right | Why it was wrong |
|---|---|---|
| The PTZ command was wrong | We did not know the mapping | It was exact: `command=N&onestep=0&` |
| The `writeCgi` timeout was too short | The official app used 5 ms and we used 5000 | The timeout was not the problem |
| Different `loginuse`/`loginpas` credentials were missing | The official app sent an extra `userId=` | That was not it |
| The `trans_cmd_string.cgi?cmd=…` prelude was missing | The official app sends a lot of them | None of them was necessary |
| The SDK had not been initialised (`PPCS_Initialize`) | And that was indeed missing… | …but it was already fixed: the tunnel came up |

---

## 2. The technique: standing in the middle of the official app

The requirement was to **capture what the official app sends without Frida** and without
modifying the APK in a detectable way. What we tried:

| Route | Result |
|---|---|
| Frida / code injection | **Ruled out** by the project's requirements |
| `LD_PRELOAD` / `wrap.<pkg>` | **Dead**: it disables the emulator's ARM translation bridge (`has unexpected e_machine: 183 (EM_AARCH64)`). With x86_64 `.so` files the app's ARM libraries fail just the same |
| Replacing `libOKSMARTPPCS.so` | Unviable: **1510 exported symbols** |
| Defining `Java_com_vstarcam_JNIApi_*` ourselves | **Loses**: the `.so` itself defines the same symbols and wins in its own `dlsym` |
| `dlsym(RTLD_DEFAULT, …)` from a shim | It would find **our** definition → ours are stripped |
| logcat from a translated shim | **Total silence** |

### 2.1 The one that worked: replacing `libvp_log.so`

`libOKSMARTPPCS.so` has **a single non-system dependency**:

```
NEEDED: libvp_log.so, libm.so, libc.so, libz.so, liblog.so, libandroid.so, libdl.so
```

…and from `libvp_log.so` it only imports **two** functions (`vp_printf`, `vp_printf_p2p`).
In other words: a **13 KB** library through which **all** of the SDK's internal log passes.

We built an **arm64 shim** and installed it into the official app under the same name
(`…/lib/arm64/libvp_log.so`), preserving owner, mode and SELinux label. The shim:

1. Registers the same symbols as the original (so the SDK links without noticing).
2. **Writes to a file** everything the SDK passes to it (logcat was useless because of the
   ARM bridge).
3. Lets the SDK keep working normally.

That produced **the official SDK's complete conversation**: `create`, `clientSetVuid`,
`connect`, `login`, every `writeCgi` and every answer from the camera.

> Restoration: a copy of the original `.so` is kept (known md5, 13,440 bytes) together with
> a script to put it back; the official app is left untouched when nothing is being
> captured.

---

## 3. What we saw by reading the official app

Simplified (real identifiers → placeholders):

```
>>> JNIApi.create("<ID_P2P_17>", "<ID_P2P_17>")        ← did2 = 17 characters
>>> JNIApi.clientSetVuid(h, "<VUID_13>")
>>> JNIApi.connect(… mode=126, target="<ID_P2P_17>", p2pType=1, ips=NULL)
      client_connect : version:4 clientId:<ID_P2P_17> connectType:0x7E result:1
>>> JNIApi.login(h, user="admin&userId=<ID_CUENTA>", pass="<TOKEN_LOGIN_19>")
>>> JNIApi.writeCgi(h, "eye4_authentication.cgi?loginAccount=<ID_CUENTA>&loginToken=<TOKEN>&")
>>> JNIApi.writeCgi(h, "decoder_control.cgi?command=6&onestep=0&")
      respuesta: result= 0;  var result="ok";
```

Two findings that **looked** like the answer and were not:

1. **The official app's `login` does not use the camera password**, but a
   `user="admin&userId=<id>"` and a 15-hex `pass` that differed on every session. We looked
   for its origin (341 md5/sha1/sha256 candidates) **without finding it**: it was the
   session token, not a derivable credential.
2. **`loginuse` injects an extra `&userId=…`** into the CGI query (visible in the capture
   of the `GET /…` wrapper).

Both things were **symptoms** of the same thing: the official app's session was
authenticated against the account. CamRevert's was not.

### 3.1 The line that explained everything

Among the official prelude there appeared, **once per connection**:

```
>>> JNIApi.writeCgi(h, "eye4_authentication.cgi?loginAccount=<ID_CUENTA>&loginToken=<TOKEN>&")
      respuesta: result= 0;  eye4_auth=1;
```

---

## 4. The root cause (and its proof)

**The camera executes no command until it receives a signed token from the Eye4 account
inside the session.**

Direct proof, on the real device:

| Moment | CGI sent | Answer |
|---|---|---|
| Tunnel up, **without** prelude | `decoder_control.cgi?command=0&onestep=0&` | `result= 0;  result=-2;` |
| After `eye4_authentication.cgi` (`eye4_auth=1`) | `decoder_control.cgi?command=0&onestep=0&` | `result= 0;  var result="ok";` |

With that, **all nine** commands (0..7 and 25) returned `var result="ok"` and the movement
was confirmed **visually**.

`get_status.cgi` is the only exception (it always answers), and that detail is what made
the diagnosis take so long: the tunnel probe seemed to confirm that everything was fine.

### 4.1 Where the token comes from

From the Eye4 account flow, **cross-checked against
[`oleandor/okam-ha-native`](https://github.com/oleandor/okam-ha-native)**:

```
POST https://api.eye4.cn/user/summary      name=<YOUREMAIL>&oemid=VSTC   → {"userid":…}
GET  https://api.eye4.cn/login/token       userid=<id>&password=<md5(PASSWORD)>&type=PC
                                                                         → {"token":"…"}
```

A subtle detail that cost a while: **the `token` already arrives percent-encoded** (`%2F`,
`%2B`, `%3D`). It must be passed **as is**; encoding it again corrupts it (`%` → `%25`).

---

## 5. What was definitively ruled out

- **The `-5` / `rc=4` error from `connect`** and the whole `serverStr`/`iface2` theory: the
  tunnel comes up with `das/p2ptype1-nullips` and the `login` passes. The blockage was not
  there.
- **The pure-Python client** (`okam-ha-native`) to control **this** camera: with the old
  CS2 scheme, the relay routed to **another device** (a `vuid` that was not ours). It stayed
  as a protocol lab and as **the source of the account flow**, which is what it did
  contribute.
- **The official app's whole prelude** (`trans_cmd_string.cgi?cmd=…`, sensor selection,
  "activate device"): viewer functionality, not an unlock.
- **Testing the CGIs over HTTP with `curl`**: the embedded HTTP server answers `200 OK`
  without dispatching the command to the motors. The real control goes **inside** the P2P
  tunnel.

---

## 6. Implementation (already in the app)

| Piece | What it does |
|---|---|
| `p2p/Eye4Auth.kt` | Requests the token (2 HTTPS calls) and parses it. The app's only internet access. |
| `P2PControlManager.authenticateSession()` | Sends the prelude and **waits for the verdict** `eye4_auth=1` (with a timeout). |
| `commandListener` → `handleSessionAuth()` | Completes that wait; if a `result=-2` appears on an already authorised session (expired token), **re-authorises once**. |
| `CamConfig.accountUser` / `accountPassword` | Eye4 account, stored locally. |
| Settings (UI) | "EYE4 account (email)" and "Account password" fields. |
| `CgiDebugReceiver --ez reauth true` | Repeats the prelude without recompiling (DEBUG only). |

The full sequence, already implemented:

```
create → clientSetVuid → connect → writeCgi(probe) → login
       → ★ eye4_authentication.cgi (wait for eye4_auth=1) ★
       → decoder_control.cgi?command=N&onestep=0&   ⇒ var result="ok"
```

---

## 7. Final verification (2026-10-17, real camera)

```
[eye4]  token de cuenta obtenido (userid=<id>, token=<N> chars)
[writeCgi] OK   eye4_authentication.cgi?loginAccount=<id>&loginToken=<token>&
[commandListener] respuesta="result= 0;  eye4_auth=1;  "
[eye4]  AUTORIZADO (eye4_auth=1): la cámara ya acepta órdenes

command=0  → var result="ok"   (arriba)        command=1  → ok (stop)
command=2  → var result="ok"   (abajo)         command=3  → ok (stop)
command=4  → var result="ok"   (izquierda)     command=5  → ok (stop)
command=6  → var result="ok"   (derecha)       command=7  → ok (stop)
command=25 → var result="ok"   (centrar)
command=26/27 → ok (crucero vertical)   command=28/29 → ok (crucero horizontal)
set_sensor_preset.cgi?sensorid=255&presetid=1& → result=0;  command=65 → ok
get_whiteled_value.cgi? → var whiteled=0;  var light=1;
```

Movement **confirmed visually** on the four axes, their stops and centring.

---

## 8. White light (verified on hardware, 2026-10-17)

**`whiteled` is the brightness as a percentage, not a boolean**, and it is the **only**
control the light needs:

```
set_whiteled_value.cgi?whiteled=0&     → LED off
set_whiteled_value.cgi?whiteled=100&   → LED on at maximum
set_whiteled_value.cgi?whiteled=N&     → LED at N %   (0 ≤ N ≤ 100)
get_whiteled_value.cgi?                → var whiteled=<0..100>;  var light=<0|1>;
```

| Action | Reading afterwards | LED |
|---|---|---|
| `whiteled=0` | `var whiteled=0;  var light=1;` | off |
| `whiteled=100` | `var whiteled=100;  var light=1;` | **on at full power** |
| app slider, 0 ↔ 80 ↔ 100 | returns the exact value set | **off / 80 % / full power** (confirmed by the user) |

`whiteled` **is enough**: it is the brightness and the switch at the same time. The `light`
field in the answer is not used by the app: it showed up as `0` with `whiteled=100` in one
test, and the cause was having **two clients at once** (the phone app and the emulator's)
sending light commands on the same account. With a single client, the slider rules and the
state comes back exactly as set.

The original mistake was believing `whiteled` was a boolean and sending `whiteled=1` to
turn it on: that leaves the LED at **1 %**, invisible in daylight, and made it look like the
light did not work.

Ruled out with evidence, not for convenience:

- `display_pwm_ctrl.cgi` — **does not exist** in this firmware (no answer).
- `trans_cmd_string.cgi?cmd=2109&command=0&light=0|1&` — **redundant**: `whiteled` already
  turns it on and off. Its `lightStatus`/`sirenStatus`/`alarmLedStatus` fields are an
  **echo of the request**, never the real state of the device.
- `set_whiteled_value.cgi?mode=0|1&` — **no-op**: that is exactly what the official app's
  direct button sends, and that is why its button "does not respond".
- `set_whiteled_value.cgi?ledtimes=N&` — accepted, **does not change the brightness**; the
  delayed switch-off is unverified.

Consequence in the app: the Light tab ended up with **a single control**, a `0..100` slider
(0 = off), and the Functions checkbox gives one-tap on/off.

---

## 9. Method notes

- The whole investigation was organised with the reverse-engineering skill
  **[`morluto/rea`](https://github.com/morluto/rea)**: evidence before hypotheses, and
  testing on the device before accepting anything as true.
- None of the conclusions in this document is "because it looks like it": each one has a
  log, a capture or a disassembly behind it (in [`../../AGENTS.md`](../../AGENTS.md)).
- The most useful lesson of the whole process: **a `result=-2` does not mean "invalid
  command", it means "session not authorised"**. Before touching the syntax of a command,
  make sure the session has permission to run it.
