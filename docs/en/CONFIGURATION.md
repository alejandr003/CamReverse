# Configuration — every key, explained

> 🌐 **English** · [Español](../CONFIGURACION.md)

CamRevert needs **two different sets of credentials** plus several identifiers. This is the
part that confuses people the most, so here it all is, together, using the names you will see
in the app dialog.

> **One-line summary**: `CAMPASSWORD` is the password of **the camera** (video) and
> `PASSWORD` is the password of **your Eye4 account** (authorising commands). They are not the
> same.

---

## 1. The two ways to configure it

### A) Key file — `secrets.properties` (the project's `.env`)

It is read **at build time** and its values are injected into `BuildConfig` as factory values
(that is, the first time you open the app they are already filled in).

```powershell
Copy-Item secrets.properties.example secrets.properties
notepad secrets.properties
```

- You may also call it **`.env`** at the root (same `KEY=VALUE` syntax). If both exist,
  `secrets.properties` wins.
- Both names are in `.gitignore`: they are **never** pushed to GitHub.
- Without that file the project **still compiles**, with empty values.

### B) From the phone — the *Settings* dialog

The settings button in the top bar. Everything is stored in local `SharedPreferences`
(`camrevert_config`), 100% local, and the app reconnects when you save.

Quick backup:

```powershell
adb shell "su -c 'cat /data/data/com.lgsusdev.camrevert/shared_prefs/camrevert_config.xml'"
```

---

## 2. Full key table

The three columns are: **the name used to explain it to you**, the **file key** and the
**dialog field**.

### 2.1 Your camera

| Name | Key in `secrets.properties` | Settings field | What it is and where it comes from |
|---|---|---|---|
| `YOUR_IP` | `cam.ip` | IP | Camera IP on your Wi-Fi. You can see it in the router, in the official app, or with the **"Scan"** button in the dialog. |
| **`VUID`** | `cam.vuid` (and `cam.uid`) | VUID / UID | Camera identifier: **13 characters** starting with `VE`. Printed on the device label and visible in the official app. It is what the SDK uses for the *rendezvous*. |
| `P2P ID` | `cam.p2pDid` | P2P ID | **Real P2P ID, 17 characters** (usually starting with `VPTN`). It is different from the VUID and is **mandatory**: the SDK only brings the tunnel up if it is exactly 17. |
| `DAS` | `cam.dasAddress` | DAS | The device's P2P address: `DAS-` + 128 hex characters = **132**. The SDK requires it as `serverParam`. |
| `CAMUSER` | `cam.user` | User | **Camera** user. On these models it is almost always `admin`. |
| **`CAMPASSWORD`** | `cam.password` | Password | **Camera password**: the one the official app asks for in order to see video. It is used for RTSP and for the P2P tunnel `login()`. |
| `PORT_FIXED` | `cam.portFixed` | Fixed-lens RTSP port | `10555` by default. |
| `PORT_PTZ` | `cam.portPtz` | PTZ RTSP port | `10554` by default. |

### 2.2 Your Eye4 account

| Name | Key in `secrets.properties` | Settings field | What it is |
|---|---|---|---|
| **`YOUREMAIL`** | `cam.accountUser` | EYE4 account (email) | The **email** you sign in with in the official O-KAM Pro app. |
| **`PASSWORD`** | `cam.accountPassword` | Account password | The **password of that account** (not the camera's). |

They are used **only** locally, to request a token over HTTPS from `api.eye4.cn`; see §3.

---

## 3. Why is `YOUREMAIL` / `PASSWORD` needed?

The camera **executes no command** (PTZ, light, siren…) until it is presented, inside the same
session, with a signed token from your Eye4 account:

```
eye4_authentication.cgi?loginAccount=<userid>&loginToken=<token>&
      → the camera answers:  result= 0;  eye4_auth=1;
```

Without it, it answers `result= 0;  result=-2;  ` to **every** CGI except `get_status.cgi`:
video works, but the dome does not move. This is **verified on real hardware**
(see [`PTZ-FINDINGS.md`](PTZ-FINDINGS.md)).

The token is obtained like this, on every connection and without storing it:

| Step | Request |
|---|---|
| 1 | `POST https://api.eye4.cn/user/summary` — `name=<YOUREMAIL>&oemid=VSTC` → `{"userid":…}` |
| 2 | `GET https://api.eye4.cn/login/token?userid=<id>&password=<md5(PASSWORD)>&type=PC` → `{"token":"…"}` |

Practical consequences:

- **It is the app's only internet connection.** Video never leaves your network.
- If you leave `YOUREMAIL`/`PASSWORD` empty, the app still works but the controls will not
  answer; the P2P console warns with `SIN TOKEN DE CUENTA`.
- The account password **is turned into MD5** on the phone for the request; it is sent the
  same way the official viewer does over HTTPS. If that worries you, use a different password
  for that account.

---

## 4. How to obtain the P2P ID and the `DAS-` address

The app **does not** guess them: the official app downloads them from its cloud. Two ways:

### A) From the official app's preferences (Android with root)

```bash
# The file contains the p2p_did JSON, with the ID and the address (DAS-...)
adb shell "su -c 'cat /data/data/com.okampro.oksmart/shared_prefs/FlutterSharedPreferences.xml'"
```

Look for the keys ending in `_p2p_did`. From there you get `cam.p2pDid` and
`cam.dasAddress`.

### B) From the accounts API (no root, with `curl`)

```bash
# 1) userid
curl -s -X POST "https://api.eye4.cn/user/summary" \
     -H "client_version: 10.0.1" -H "oemid: VSTC" \
     --data-urlencode "name=YOUREMAIL" --data "oemid=VSTC"

# 2) token (md5 of the password)
curl -s "https://api.eye4.cn/login/token?userid=<id>&password=<md5(PASSWORD)>&type=PC" \
     -H "client_version: 10.0.1"

# 3) device list (uid, nickname...)
curl -s "https://api.eye4.cn/PC/device/show?userid=<id>&pwd=<md5(PASSWORD)>" \
     -H "client_version: 10.0.1"
```

On the tested device, `PC/device/show` returned the `uid` and the `nickname`, but **not** the
P2P ID or the `DAS`: those come from option **A**.

---

## 5. Typical configuration errors

| Message / symptom | What is happening |
|---|---|
| `AVISO: p2pDid mide N, no 17` (console) | The P2P ID was copied wrong or truncated. It must be **17** characters. |
| `Ninguna combinación abrió el túnel` | Wrong `p2pDid` or `dasAddress`. The console lists every attempt with its error code. |
| `SIN TOKEN DE CUENTA` | `YOUREMAIL`/`PASSWORD` are missing, or the account does not exist / the password is wrong. |
| `result=-2` in every answer | Session not authorised (same cause as above). |
| `get_status.cgi` → `result=-1;vuid=…` | **Normal** on this firmware. It is not a credential failure. |
| Video works but there is no audio | Intercom audio depends on the tunnel and on reception through `ClientCommandListener` (known limitation). |
