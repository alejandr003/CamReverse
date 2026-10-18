# CamRevert — Especificación técnica

Cliente nativo Android (Kotlin) para cámaras **O-KAM Pro / VStarcam** de doble lente
(lente fija + domo PTZ). Sin anuncios, sin telemetría, 100% local, consumo de RAM objetivo < 70 MB.

| Item | Valor |
|---|---|
| Módulo | `:app` (`com.lgsusdev.camrevert`) |
| minSdk / targetSdk | 24 (Android 7.0) / 37 |
| ABI empaquetada | `arm64-v8a` (única disponible, ver §2) |
| Stack | Kotlin, Material 3, Media3 ExoPlayer + RTSP, Coroutines |

---

## 1. Origen de los datos

Todo lo que sigue sale de la ingeniería inversa del APK original en `C:\cam-reverse\`:

| Artefacto | Contenido |
|---|---|
| `okam.apk` | XAPK original de `com.okampro.oksmart` v3.0.37 (versionCode 825) |
| `okam_base.apk` | APK base extraída (manifest + 5 DEX) |
| `src\sources\com\vstarcam\` | Código Java descompilado (JADX 1.5.6) |
| `lib\*.so` | 18 librerías nativas extraídas de `split_config.arm64_v8a.apk` |
| `endpoints.txt` | Inventario de 305 cadenas `.cgi` / onvif / puertos |
| `lib\libapp.so` | Snapshot Dart AOT de Flutter (fuente real de los comandos CGI) |
| `ppcs_disasm.txt` | **Desensamblado completo de `libOKSMARTPPCS.so`** (4.9 MB, llvm-objdump) — fuente de §5.6 y §5.7 |

Símbolos internos relevantes de `libOKSMARTPPCS.so` (base `0x0`):
JNI stubs `0x3f6c4–0x41a00`; `client_create` `0x41ac0`, `client_setvuid` `0x421bc`,
`client_connect` `0x42290`, `client_write` `0x43500`, `client_write_command` `0x43788`,
`client_write_cgi` `0x43fac`, `client_write_cgi_nvr` `0x441c8`, `client_login` `0x44428`,
`client_read` `0x445f0`.

---

## 2. Arquitecturas ABI

El XAPK contiene **únicamente** `split_config.arm64_v8a.apk`. No existe `split_config.armeabi_v7a.apk`
ni ninguna librería de 32 bits en todo el paquete.

Consecuencias:
- `app/build.gradle.kts` declara `ndk.abiFilters += listOf("arm64-v8a")`.
- Un dispositivo de **32 bits no podrá instalar la APK** (sin binarios compatibles).
- `P2PControlManager.nativeAvailable` protege la app ante una `UnsatisfiedLinkError`
  inesperada: si la librería no carga, el control P2P se deshabilita pero el
  reproductor RTSP sigue funcionando.

Librerías copiadas a `app/src/main/jniLibs/arm64-v8a/`:

| Librería | Tamaño | Motivo |
|---|---|---|
| `libOKSMARTPPCS.so` | 728 KB | SDK P2P (rendezvous + túnel CGI). Cargada por `JNIApi` |
| `libvp_log.so` | 13 KB | Dependencia `NEEDED` de la anterior (tabla ELF) |
| `libpcs_shim.so` | 4.8 KB | **Propio** (`app/src/main/cpp/`): llama a `PPCS_Initialize`, que ningún `.so` del XAPK invoca (§10.9) |

Tabla `NEEDED` verificada de `libOKSMARTPPCS.so`: `libandroid`, `libc`, `libdl`, `liblog`,
`libm`, `libvp_log`, `libz` — todas del sistema salvo `libvp_log`.

No se copia `libOKSMARTPLAY.so` (10 MB) ni `libapp.so` (54 MB): son el reproductor P2P
propio y el runtime Flutter de la app original. CamRevert decodifica con Media3.
**Pendiente decidido (§5.13, §10.6):** si se integra el reproductor nativo para ver los
clips de la MicroSD habrá que copiar `libOKSMARTPLAY.so` (+10,8 MB de APK); es la única vía
conocida, porque el flujo del fichero no sale por el túnel CGI.

---

## 3. Capa nativa (`com.vstarcam`)

### 3.1 `JNIApi` — fachada estática JNI

`System.loadLibrary("OKSMARTPPCS")` en el `<clinit>`. Los nombres de clase y método
**no se pueden renombrar**: el enlace usa símbolos `Java_com_vstarcam_JNIApi_<metodo>`.

```java
public class JNIApi {
    static { System.loadLibrary("OKSMARTPPCS"); }

    // Ciclo de vida
    public static native long   create(String uid, String arg1);
    public static native boolean init(ClientStateListener, ClientCommandListener,
                                      ClientReleaseListener, ClientIpsListener);
    public static native int    connect(long handle, int mode, String target,
                                        int p2pType, String cachedIps);
    public static native boolean login(long handle, String user, String password);
    public static native boolean disconnect(long handle);
    public static native void   destroy(long handle);
    public static native boolean clientConnectBreak(long handle);
    public static native void   release(long handle);
    public static native void   retain(long handle);

    // Transporte CGI
    public static native boolean writeCgi(long handle, String cgi, int timeoutMs);
    public static native boolean writeCgiNVR(long handle, String cgi, int timeoutMs);
    public static native int     write(long handle, int channel, byte[] data, int timeoutMs);
    public static native boolean sendBinaryFile(long handle, String path, int length);

    // Estado
    public static native boolean changeId(long handle, String uid);
    public static native boolean clientSetVuid(long handle, String vuid);
    public static native int     clientDevInfo(long handle, String a, String b);
    public static native int[]   checkBuffer(long handle, int arg);
    public static native int[]   checkMode(long handle);
    public static native String  getDid(long handle);
    public static native void    clearIpsListener();
}
```

> Los listeners viven en **`com.vstarcam.app_p2p_api`**, no en `com.vstarcam`. Ese es el
> descriptor exacto del `init` original y debe conservarse.

### 3.2 Firmas de los listeners

```java
package com.vstarcam.app_p2p_api;
public interface ClientStateListener  { void stateListener(long handle, int state); }
public interface ClientCommandListener{ void commandListener(long handle, byte[] data, int length); }
public interface ClientReleaseListener{ void releaseListener(long handle); }
public interface ClientIpsListener   { void ipsListener(String uid, String ips); }
```

### 3.3 Flujo observado en `AppP2PApiPlugin` (la app original)

```
onAttachedToEngine()
  ├─ MethodChannel   "app_p2p_api_channel"
  ├─ EventChannel    "app_p2p_api_event_channel/connect"   ← stateListener
  ├─ EventChannel    "app_p2p_api_event_channel/command"   ← commandListener
  └─ JNIApi.init(this, this, this, this)

client_create   → JNIApi.create(get(0):String, get(1):String?)
client_connect  → JNIApi.connect(handle, mode=get(3)?:126, target=get(2):String,
                                 p2pType=get(4)?:0, getCachedIps(handle))
client_login    → JNIApi.login(handle, get(1):String, get(2):String)
client_write_cig → JNIApi.writeCgi(handle, get(1):String, get(2):Integer)
client_write     → JNIApi.write(handle, get(1):Int, get(2):byte[], get(3):Integer)
client_setvuid   → JNIApi.clientSetVuid(handle, get(0):String)
```

Puntos clave:
- **Toda la parametrización llega de Dart** por el MethodChannel; el Java no fija IPs ni puertos.
- `commandListener` recibe los bytes de respuesta de la cámara y los empuja al
  EventChannel `.../command` como `[handle, length, byte[]]`. **Este es el callback donde
  llegan las respuestas CGI** (`get_status.cgi`, `get_record_file.cgi`, …).
- `stateListener` recibe `[handle, state]` por el EventChannel `.../connect`.
- `ipsListener` persiste direcciones alternativas en SharedPreferences `app_p2p_api_ips`
  con clave `did_<uid>`.

### 3.4 Puertos 10080 / 9001 / 9002

**No existen** en el binario. Se buscaron literales (ASCII y UTF-16) y constantes enteras
`const`/`const/16` en los 5 DEX y en las 18 librerías `.so`: las únicas coincidencias son
constantes ajenas a la red (`MBridgeConstans.IVREWARD_VALUETYPE_PER = 10080`,
`GoogleLoginChannel.RC_SIGN_IN = 9001`, códigos de error de Zalo/Facebook).

El único puerto HTTP con literales en todo el binario es el **:81**
(`http://192.168.168.1:81/get_status.cgi?loginuse=admin&loginpas=888888`, modo AP).

### 3.5 ONVIF

Cero ocurrencias de `onvif`, `SOAP` o `devicemgmt` en DEX ni en `.so`. Esta app **no usa ONVIF**.

---

### 3.6 Log nativo del SDK (`com.veepai.vp_log`)

`libvp_log.so` (13 KB) es quien provee `vp_printf` a `libOKSMARTPPCS.so`. CamRevert
añade una fachada mínima —**`com.veepai.vp_log.VPLogApi` + `VPLogListener`** con los
nombres intactos, porque el enlace resuelve por símbolo
`Java_com_veepai_vp_1log_VPLogApi_<metodo>` (`_1` codifica el `_` de `vp_log`)— para
volcar ese log a la consola diagnóstica. Sin él el motivo real de un `connect()`
fallido es invisible: el `rc` llega ya normalizado (§5.5) y las cadenas que explican
el fallo (`ERROR_PPCS_*`, `unknown version:...`, `_se_try_other_netcard() ...
net_ips_len=...`) solo se imprimen dentro del `.so`.

Circuito verificado en el binario:

```
VPLogApi.setLogListener(l)
  → Java_com_veepai_vp_1log_VPLogApi_setListener
      struct@0xACD8 = { env, NewGlobalRef(l),
                        GetMethodID(GetObjectClass(l), "fileLogListener",
                                    "(Ljava/lang/String;)V") }   // literales 0x1101 / 0x1202
      vp_print_set_file(log_file_callback)      // .got@0x6BD0 = 0x1914 → escribe 0xACF0

client_connect → vp_printf(nivel 4)             // "mov w0, #0x4" en cada log
  0x21D8  cmp w19, #4                           // solo el nivel 4 despacha al callback
  0x21E8  blr [0xACF0] → log_file_callback
             → CallVoidMethod(l, "fileLogListener", texto)
```

`setp2pListener` **no sirve**, pese a empatjar por nombre: su cola es `vp_p2p_set_file`,
que está vacío (`ret` en `0x1f00`), de modo que `log_p2p_callback` (`0x1a2c`) queda sin
ningún llamante. El que funciona es `setListener`.

Respaldo: `vp_printf` también escribe a logcat con tag **`flutter`** cuando
`nivelGlobal <= nivelMensaje`; el nivel global arranca en 0 (.bss) y los mensajes del
SDK van a nivel 4, así que `adb logcat -s flutter` muestra lo mismo durante un connect.

---

## 4. Video: RTSP directo (sin túnel P2P)

El video se consume por RTSP TCP para no pasar por el SDK nativo (menor CPU/RAM):

| Lente | HD (main) | SD (sub) |
|---|---|---|
| Fija (superior) | `rtsp://USER:PASS@IP:10555/tcp/av0_0` | `rtsp://USER:PASS@IP:10555/tcp/av0_1` |
| PTZ (inferior) | `rtsp://USER:PASS@IP:10554/tcp/av0_0` | `rtsp://USER:PASS@IP:10554/tcp/av0_1` |

Implementación:
- **Transporte RTP** (`RtspMediaSource.Factory.setForceUseRtpTcp`, ajustable en
  `CamConfig.rtspTransportTcp`, defecto **TCP**). La asimetría entre los dos caminos
  está verificada en `media3-exoplayer-rtsp` 1.9.4 con `javap` y es lo que decide el
  diseño:

  | Camino | Fábrica | ¿Tiene respaldo? |
  |---|---|---|
  | UDP (`RTP/AVP`) | `UdpDataSourceRtpDataChannelFactory` | **Sí** — `createFallbackDataChannelFactory()` devuelve la fábrica TCP |
  | TCP interleaved | `TransferRtpDataChannelFactory` | **No** — el método no existe |

  Es decir, *UDP primero* degrada con elegancia y *TCP primero* es calle sin salida:
  si el equipo no se lleva bien con el interleaved, Media3 **nunca** lo negocia solo.
  Por eso `CameraStreamView.maybeFlipTransport()` invierte el transporte (TCP↔UDP) a
  los `TRANSPORT_FLIP_AFTER = 3` fallos seguidos y lo deja **persistido** en prefs:
  se prueba la otra vía sin recompilar. El contador es **propio**
  (`transportErrors`), no el de los fallos de stream, porque el bajón HD→SD reinicia
  `consecutiveErrors` y si no, uno de los dos mecanismos se come los fallos del otro.
- **`setTimeoutMs` NO es solo el timeout del handshake RTSP** — es el origen del
  *refresh* periódico. Verificado con `javap` en 1.9.4: `RtspMediaSource.Factory`
  entrega el valor a `TransferRtpDataChannelFactory(long timeoutMs)`, que se lo pasa
  a `TransferRtpDataChannel(long)`, y a `UdpDataSourceRtpDataChannelFactory(long
  socketTimeoutMs)`. **Es decir, es el read-timeout del socket de datos RTP**, y
  vale para ambos transportes. Con los 8 s originales, cualquier pausa real de la
  cámara de más de 8 s producía una excepción de E/S, `onPlayerError`, y el
  reconector volvía a empezar el ciclo: un parpadeo visible cada pocos segundos,
  mientras que la misma URL en VLC (sin ese timeout) se quedaba estable. Está
  subido a `RTSP_TIMEOUT_MS = 30_000`, que sigue detectando un stream muerto pero
  tolera las pausas normales de una cámara WiFi barata.
- **`LoadControl` va en el `ExoPlayer.Builder`, no en la fuente**: en Media3 1.9.4
  `MediaSourceFactory` **no** expone `setLoadControl` (comprobado con `javap`; solo
  la tienen `ExoPlayer.Builder` y `SimpleExoPlayer.Builder`). Se usa un
  `DefaultLoadControl` holgado para directo
  (25 s/60 s/2 s/5 s + `setPrioritizeTimeOverSizeThresholds(true)`), porque los
  valores por defecto están calibrados para ficheros progresivos y hacen
  rebuffering innecesario con un bitrate que salta de picos de Mbit/s a cientos de
  kbit/s.
- **Reconexión automática con backoff** (`reconnectRunnable`): 0,5 s → 1 s → 2 s → 4 s
  → 8 s (tope), reiniciado al volver a `STATE_READY`; a `HD_FALLBACK_AFTER = 4` fallos
  seguidos en HD se baja a SD automáticamente. `onPlayerError` vuelca a logcat la
  **cadena de causas completa** (hasta 6 niveles) además del `errorCode` y el
  `errorCodeName`, y la fuente RTSP se abre con `setDebugLoggingEnabled(true)` para
  que el diálogo DESCRIBE/SETUP/PLAY completo salga en `adb logcat -s
  ExoPlayer:RtspMediaSource*`.

#### La recarga «cada N segundos» — diagnosticada y cerrada

**Medido en el equipo (2026-10-17), no inferido:**

| Observación | Valor |
|---|---|
| Peticiones RTSP en 200 s continuos | **14** = 7 por lente, **una cada 30 s** |
| Método de todas ellas | `OPTIONS` de keep-alive, **mismo `Session:`**, `CSeq` creciente |
| `DESCRIBE`/`SETUP`/`PLAY`/`TEARDOWN` en esos 200 s | **cero** |
| Errores de CamRevert en esos 200 s | **cero** |

O sea: **la sesión RTSP no se caía**. El `OPTIONS` cada 30 s **no es una recarga**, es
la norma del protocolo: la cámara anuncia `Session: timeout=60` y Media3 instala un
`KeepAliveMonitor` con intervalo `sessionTimeoutMs / 2` (`RtspClient.java`,
`DEFAULT_RTSP_KEEP_ALIVE_INTERVAL_DIVISOR = 2`). **Es obligatorio**: suprimirlo haría
que la cámara cerrase la sesión a los 60 s y *entonces sí* habría una recarga real.
Nada que tocar ahí.

El parpadeo que se percibía venía de la **interfaz**, no del stream:

| Antes | Ahora |
|---|---|
| `playSource()` hacía `tvPlaceholder=GONE` + `progressBuffer=VISIBLE` **en cada intento**, incluidos los cambios de calidad | El spinner se **pospone** `BUFFER_OVERLAY_DELAY_MS = 1500 ms`: un microcorte más corto no parpadea nada |
| `onPlayerError` ponía el **recuadro central encima del vídeo** | Con imagen ya decodificada (`hasVideo`, que fija `AnalyticsListener.onRenderedFirstFrame`) **nunca más se tapa**: se conserva el último fotograma y el estado se cuenta en el HUD |
| `STATE_ENDED` se quedaba en «Finalizado» **para siempre** | Un directo que «termina» significa que el servidor cerró el stream → **reintento automático** |
| Con la cámara caída el reproductor se queda en `BUFFERING` **eternamente**: el HUD decía «Buffering» para siempre y el usuario no sabía si era la cámara o el móvil | **Detector de atasco**: si no llega ningún fotograma nuevo en `STALL_AFTER_MS = 6 s`, el HUD dice **`Sin conexión`** y el caudal pasa a `0 KB/s` (antes se quedaba el último valor, así que se leía «Sin conexión» junto a «69 KB/s») |
| No había forma de saber si había habido cortes | El HUD muestra **`↻n`** con el número de reconexiones y el estado pasa a **`Reconectando…`** mientras hay un reintento en vuelo |
| El transporte se invertía a los 3 fallos y HD caía a SD a los 2 | Umbrales a **5** y **4** (ambos cambios fuerzan un cambio de stream, es decir, un parpadeo real) |
| Reanudación tras rebuffering a 2 s/5 s | **1 s/1,5 s**: los valores por defecto están calibrados para ficheros progresivos y en directo solo alargan el hueco |

**Verificado en el equipo** cortando con `iptables` el tráfico del emulador hacia la
cámara: la tarjeta pasó a `Reproduciendo ↻1` y luego `↻2`, recuperándose **sola, sin
cerrar la app**, las dos veces.
- **Render sobre `TextureView` en todas las versiones** (`ZoomableVideoLayout`).
  Se abandonó la bifurcación `SurfaceView` (API 29+) / `TextureView` (API 24-28):
  `SurfaceView` ignora `scaleX`/`translationX` por debajo de Android 10, recorta el video
  a la ventana y da artefactos de z-order con los controles superpuestos.
  El zoom se aplica con `TextureView.setTransform(Matrix)`, con encuadre base
  **`CENTER_CROP`** (punto 2): la imagen **cubre** el contenedor y lo sobrante se
  recorta, así que a 1.0x **no hay bandas negras**. `TextureView` estira el buffer
  sobre la vista, por eso la corrección de aspecto es **no uniforme**
  (`setScale(sx, sy, w/2, h/2)` con `sx = contentAspect/viewAspect` si el contenido es
  más ancho que la vista, o `sy = viewAspect/contentAspect` si es más alto). Sobre esa
  base van el zoom y el `postTranslate` del usuario. El aspecto real se sincroniza
  desde `AnalyticsListener.onVideoSizeChanged`. `clampPan()` trabaja sobre el tamaño ya
  cubierto (`sx·w` × `sy·h`) y `CameraStreamView.captureBitmap()` recorta la foto con
  el **mismo** `CENTER_CROP`, así que la instantánea tampoco lleva bandas.
- **Zoom digital 1.0x–8.0x** (`ZoomableVideoLayout.MIN_SCALE`/`MAX_SCALE`), pinch
  acumulativo + arrastre con clamping + doble toque (1.0x ↔ 3.0x). Cada cámara lleva la
  barra compacta `[-] 1.0x [+]` en su parte inferior.
- **Medición pasiva, sin sondas TCP** (punto 2): `StreamHealthMonitor` fue eliminado junto
  con todos los sockets de sondeo a 10554/10555 (eran 2 conexiones por cámara y por
  segundo que el propio servidor RTSP terminaba cerrando y que contribuían a los cortes).
  El HUD tiene **dos fuentes redundantes de caudal**, para que nunca se quede fijo en
  `0 KB/s`:
  - **KB/s (principal)** → `DefaultBandwidthMeter`, que **implementa** `androidx.media3.datasource.TransferListener`.
    Media3 lo registra en `RtspMediaSource.mediaTransferListener` → `UdpDataSourceRtpDataChannel.addTransferListener`
    / `TransferRtpDataChannel` (extends `BaseDataSource`), así que cuenta los bytes que ya
    viajan. Aviso por `BandwidthMeter.EventListener.onBandwidthSample(elapsedMs, bytesTransferred, bitrateEstimate)`.
    (No existe `AnalyticsListener.onBytesTransferred`: esa firma es de `TransferListener`.
    `AnalyticsListener` no expone `setTransferListener` para RTSP.)
    **En el equipo real este canal no emite muestras**, de ahí el `0 KB/s` observado.
  - **KB/s (respaldo)** → estimación a partir de los **fotogramas decodificados**: una vez
    por segundo se lee `ExoPlayer.getVideoDecoderCounters()` (llamar `ensureUpdated()`
    antes) y se mide el delta de `renderedOutputBufferCount`, que se multiplica por
    ≈**0,05 bits/píxel** a la resolución real del stream (1920·1080·0,05 = 103 680
    bit/trama · 25 fps ≈ 2,6 Mbit/s ≈ 324 KB/s). Se usa automáticamente cada vez que la
    fuente principal lleva > 2,5 s sin reportar, y el HUD marca ese valor con un «~»
    para distinguirlo del medido.
  - **Resolución** → `AnalyticsListener.onVideoSizeChanged`.
  - **Estado/error** → `Player.Listener.onPlaybackStateChanged` / `onPlayerError`.
- **Respaldo de decodificador (punto 3)**: el `ExoPlayer` se construye con un
  `DefaultRenderersFactory` y `setEnableDecoderFallback(true)`, el mecanismo de Media3
  para caer al decodificador por **software** (`c2.android.avc.decoder` /
  `OMX.google.h264.decoder`) si el de hardware rechaza el perfil o la resolución: en una
  Lenovo Tab M10 el main de 2304×1296 no lo instanciaba el decoder de hardware y el
  reproductor se quedaba en bucle de rebuffering. `CamConfig.preferSoftwareDecoder`
  (`prefer_software_decoder`, por defecto `false`) permite además **empezar** por software
  con `MediaCodecSelector.PREFER_SOFTWARE`; se cambia desde Configuración y obliga a
  recrear el player (`restartStreams()`).
- **El `ExoPlayer` no se recrea por cambios de UI**: cambiar calidad hace
  `setMediaSource` + `prepare`; mute/es zoom/pantalla completa usan `volume`,
  `setTransform` y visibilidad. Solo `stop()`/`start()` (rotación, settings, `onStart`/`onStop`)
  construyen un player nuevo.

> **Nota**: las rutas `/tcp/av0_0` y los puertos 10554/10555 provienen de la especificación
> del equipo. **No aparecen como literales en el binario** (la app original usa su propio
> reproductor P2P). Si la cámara expone RTSP en el puerto estándar 554, cambiarlo en el
> diálogo de Configuración.

---

## 5. Control: comandos CGI (evidencia binaria)

### 5.1 Envío: `writeCgi` envuelve la cadena dentro del nativo

**Hallazgo crítico** (desensamblado de `client_write_cgi`, `0x43fac`): la cadena que se
pasa desde Java **no** es la petición HTTP. El nativo hace:

```c
snprintf(buf, cgiLen + 1024, "GET /%sloginuse=%s&loginpas=%s&user=%s&pwd=%s&",
         cgi, handle+0x98, handle+0x199, "admin"@0x213b2, "888888"@0x27670);
```

Reglas obligatorias para **todo** `Cgi.*`:
1. **No** anteponer `GET /` ni `loginuse=`/`loginpas=`: el nativo los añade.
2. La cadena **debe terminar en `&` o `?`**, porque `loginuse=` se escribe justo detrás
   sin separador propio.
3. Sin `loginuse`/`loginpas` propios: el túnel ya está autenticado con `login()`.

El tercer argumento de `writeCgi` es un **timeout en milisegundos**, no la longitud de la
cadena (la longitud real la calcula el nativo con `strlen`). `client_login` lo invoca con
`0x1388 = 5000` (`0x445c4 mov w2,#0x1388`). Pasar `cgi.toByteArray().size` (~40) producía
un timeout de 40 ms y los comandos se perdían en la primera retransmisión.

### 5.2 Envío binario: `write(handle, channel, data, timeoutMs)`

- `client_write` (`0x43500`) obtiene la longitud real con **`GetArrayLength(data)`**; el
  cuarto argumento es de nuevo un **timeout en ms** (bucle de reintento
  `add w25,w25,#0x14; cmp w25,w23; usleep(20000)`), con troceado a 1 MB.
- El **canal es un parámetro libre**: el 0 está reservado para CGI/comandos.
  El audio de intercomunicador usa `CamConfig.voiceChannel` (defecto **1**), ajustable
  en Configuración — **el canal exacto no está determinado en el binario** (ver §5.4).

### 5.3 `client_write_command`: cabecera de 8 bytes en canal 0

```
01 0A 00 00 <payloadLen u16 LE> 00 00
```
(`mov w8,#0xa01; str x8,[sp]; strh w2,[sp,#0x4]`), escrita en canal 0 y después el payload.
Es lo que va detrás de `trans_cmd_string.cgi`.

### 5.4 Audio / intercomunicador

Evidencia de código:
- `libOKSMARTPPCS.so` **solo expone `client_write` al JNI**; `client_read` (`0x445f0`) no
  tiene símbolo `Java_com_vstarcam_JNIApi_*`.
- `libOKSMARTPLAY.so` exporta `app_g711_encoder_create`, `app_g711_u_encoder_create`,
  `app_adpcm_encoder_create`, `app_adpcm_encoder_multiple`, `app_aac_encoder_create`,
  `app_check_sample_rate`, `app_player_set_voice_channel` y
  `Java_com_veepai_AppPlayerApi_startVoice/startVoiceRecord/stopVoice/stopVoiceRecord`.
- `libOKSMARTSHENGYIN.so` exporta `createPlayer2, sampleRate:%d, channel:%d`.
- `libapp.so` contiene la cadena `supportG711`.

Formate implementado en `audio/G711.kt` + `audio/VoiceLink.kt`:

| Campo | Valor |
|---|---|
| Muestreo | PCM **8000 Hz · 16 bits · mono** |
| Códec por defecto | **G.711 A-law** (`CamConfig.CODEC_G711A`); alternativas µ-law y PCM crudo |
| Trama | 20 ms = 160 muestras = 320 B PCM → 160 B G.711 |
| Fuente de audio | `MediaRecorder.AudioSource.VOICE_COMMUNICATION` |
| Efectos | `NoiseSuppressor` siempre; `AcousticEchoCanceler` **solo en modo dúplex** (sin AEC el altavoz de la cámara + micrófono del móvil forman un lazo) |
| Canal P2P | `CamConfig.voiceChannel` (defecto 1) vía `JNIApi.write` |
| Llamada | `callstatus.cgi?status=1&` al entrar / `?status=0&` al salir (solo modo dúplex) |

Codificadores por **cuantizador de vecino más próximo**: se precalculan las tablas de los
256 niveles de reconstrucción con las fórmulas de referencia ITU-T G.711 (`a_val ^= 0x55`,
segmento/`QUANT_MASK`/`BIAS=0x84`) y se elige el código más cercano — regla de decisión
óptima para un cuantizador no uniforme.

**Limitación heredada del SDK** (documentada a propósito): como `client_read` no está
expuesto, **la recepción de audio depende de que llegue por `ClientCommandListener`**.
`P2PControlManager` enruta hacia `VoiceLink.onIncoming()` todo payload que no parezca CGI
(>2 bytes de control en los primeros 64). Si el firmware no entrega el audio por ese canal,
el modo dúplex no se escuchará; el **envío** (PTT y dúplex) sí funciona por `JNIApi.write`.

### 5.5 Orden obligatorio del ciclo

```
create(did, "") → clientSetVuid(handle, vuid) → connect(handle, mode, target, p2pType, cachedIps)
   → login(handle, user, pass) → writeCgi(...)
   → ★ eye4_authentication.cgi (preludio de sesión; §5.14) ★
```

- `create(did, did2)` — `Java_com_vstarcam_JNIApi_create` (`0x3fc6c`) → `client_create`
  (`0x41ac0`):

  ```c
  jstring d  = did  ? GetStringUTFChars(did,  NULL) : NULL;   // 0x3fca0
  jstring d2 = did2 ? GetStringUTFChars(did2, NULL) : NULL;   // 0x3fcc0
  long h = (long) client_create(d, d2);                       // 0x3fd40
  ```

  ```c
  void* client_create(const char* did, const char* did2)
  {
      if (!did) return NULL;                       // 0x41ad8
      size_t len = strlen(did);                    // 0x41ae4
      if (!(len >= 1 && len <= 63)) return NULL;   // 0x41ae8 sub x8,len,#0x40
                                                   //         0x41aec cmn x8,#0x40 ; b.hi ok
      void* p = calloc(1, 0x298);                  // 0x41b70  (664 B)
      strcpy(p + 0x00, did);                       // did[64]
      void* iface1 = GetIface(did);                // 0x41ba0  vtable por prefijo del DID
      *(void**)(p + 0x88) = iface1;                // 0x41ba8
      iface1->init();                              // 0x41bac  [iface1+0x10]()
      RegisterCallback(cb @ 0x41d44);              // 0x41bb8

      if (!did2)                    goto no_did2;  // 0x41c24
      if (strlen(did2) != 17)       goto no_did2;  // 0x41c2c / 0x41c30 cmp x0,#0x11
      strcpy(p + 0x40, did2);                      // did2[64]  (¡solo si mide 17!)
      *(void**)(p + 0x90) = GetIface(did2);        // 0x41c4c..0x41c54
  no_did2:
      *(void**)(p + 0x90) = NULL;                  // 0x41cc8
      pthread_mutex_init(p + 0x1dc);
      return p;                                    // handle
  }
  ```

  **El 2.º parámetro de `create` NO es la licencia ni un `serverParam`: es un *segundo
  DID*** (`handle+0x40`), el de la segunda lente, y solo se usa si mide **exactamente
  17**. `""` (longitud 0 ≠ 17) recorre exactamente la misma rama que `NULL`, así que
  `create(did, "")` es correcto y **no hay nada que arreglar en ese argumento**. La
  licencia PPCS del original (`BJHJAABPPKMB…`, 108 chars en `0x25d10`) **no** se inyecta
  por este camino. `PPCS_Initialize` solo lo llama `Initialize_V1` (`0x44b7c`), que es
  la entrada `+0x00` de la vtable de interfaz (`0xb6838`) — y **ninguna función invoca
  esa entrada**: `client_create` usa la `+0x10` (`NetworkDetect_V1` `0x450a4`) y no
  existe `.init_array`. Ver §10.9.
  CamRevert lo inicializa desde su propio shim — `app/src/main/cpp/ppcs_shim.c` →
  `libpcs_shim.so`, enlazado por `p2p/PpcsShim` y llamado una vez en `init()` —
  **antes** de la cascada. Ver §10.9.

  **Se pasa `""`, nunca `null`** (en el desensamblado el `NULL` sí está vigilado; `""`
  evita exponer un `jstring` nulo al JNI sin cambiar de rama).

- `connect(handle, mode, target, p2pType, cachedIps)` —
  `Java_com_vstarcam_JNIApi_connect` (`0x40320`) → `client_connect` (`0x42290`):

  ```c
  // JNI: handle==0 ⇒ return 0 (0x4034c); target/cachedIps NULL ⇒ se pasan como NULL.
  int client_connect(void* h, int mode, const char* target, int p2pType, const char* cachedIps)
  {
      if (!h)                   return 0;                 // 0x4233c
      if (*(u8*)(h+0x204) != 0) return 3;                 // 0x422c4  «ya conectado», sin red

      void* iface1 = *(void**)(h+0x88);                   // 0x423a8
      void* iface2 = *(void**)(h+0x90);                   //          NULL si did2 no medía 17
      char* targetId;  char* serverStr;                   // x25 / [sp+0x50]
      if (target && target[0]) {                          // 0x423bc
          targetId = target;                              //   ¡target es el TargetID!
          if (!iface2) { serverStr = NULL; w22 = 0; }     // 0x423cc → 0x424f8
          else        { serverStr = target; w22 = 1; }    // 0x42428
      } else {
          targetId = GetServerString(h);                  // 0x423dc
          ...
      }

      // 0x426fc: p2pType==1 y iface2!=NULL ⇒ vía iface2 (did2 de 17 chars);
      //          cualquier otra combinación ⇒ vía iface1 (nuestro caso).
      if (sp[0x50] != NULL && (mode & 0xFF) != 121) {     // 0x42a94 / 0x42a9c
          log("unknown version:%d clientPtr:%ld clientId:%s connectType:0x%02X serverParam:%s error");
          *(u8*)(h+0x204) = 0;
          return 4;                                       // 0x42b28
      }

      rc = iface1->connect(h, mode, 0, targetId, &outSession,
                           cachedIps[0] ? cachedIps : NULL, verStruct);   // 0x42b70 / 0x42eb4

      if (rc < 0) {                                       // 0x43074 tbnz x24,#0x3f
          *(u8*)(h+0x204) = 0;                            // 0x43100
          return (rc <= -21) ? TABLA[-rc] : 4;            // 0x4310c / 0x4311c
      }
      *(u8*)(h+0x204) = 1;                                // 0x430a0  ★ SOLO aquí
      *(void**)(h+0x80)  = iface1;                        // 0x430a4
      *(void**)(h+0x208) = (ver == 3) ? outSession : rc;  // 0x43090 / 0x4309c
      return rc;                                          // ≥ 0 = session handle
  }
  ```

  **Cuatro hechos que condicionan toda la app:**

  1. **`target` es el TargetID (DID/VUID), no una IP.** Pasarle `"<ip-de-la-cámara>"` produce
     `ERROR_PPCS_INVALID_ID (Hint: TargetID is not a valid DID string)` (log @ `0x22ab0`).
     **La IP va en `cachedIps`**, que es el campo `net_ips` (log
     `_se_try_other_netcard() sess=%d aid=%u net_ips_len=%d net_ips_idx=%d lip=%s`, @
     `0x28ac0`).
  2. **`rc >= 0` solo dice que el SDK aceptó la petición** (session handle 0,1,2,…, con
     `handle+0x204 = 1`); **no prueba que el túnel esté montado**, así que el éxito real
     lo fija el `writeCgi` de sondeo. **`rc < 0` es siempre error**: el nativo limpia el
     flag (`0x43100`) y traduce el código crudo contra la tabla de `0x28c00`
     (`add x8,x8,x24,lsl #2` en `0x43110`): crudos −1..−19 → 4/6/7/8/9/10, y
     **cualquier crudo ≤ −20 → 4** (`cmn x24,#0x14; b.ls 0x4311c`). Por eso
     **`rc = 4` jamás significa éxito**: es el cajón de sastre (lo producen los crudos
     −2, −5, −7, −11…−16, −18 **y todo ≤ −20**), y lo único que lo desambigua es el flag
     `handle+0x204`.

     **Excepción: el modo 121** sale por `0x43030 mov w24,w0` y se salta la
     normalización, de modo que su `rc` es el código crudo tal cual. Conviene usarlo
     como sonda: si `121+lan` devuelve `rc=-5` y `tabla[-5] = 4`, se deduce que **todos
     los intentos fallan con el mismo error PPCS crudo −5**, que los demás solo muestran
     traducido.

     **El crudo −5 ya está descifrado.** `P2ptErrStrGet` (`0x7da88`) es un `switch`
     código→nombre: cada rama termina en `adrp x3, 0x29000` + `add x3, x3, #imm` y
     entrega esa cadena a `snprintf(dest, 64, "[%s]", x3)`. La rama de `w0 == -5`
     (`0x7de58`, `add x3,x3,#0xb38`) apunta a **`0x29b38` = `ERROR_PPPP_INVALID_PARAMETER`**.
     Tabla completa leída del binario:

     | Crudo | Nombre | Crudo | Nombre |
     |---|---|---|---|
     | −1 | `ERROR_PPPP_NOT_INITIALIZED` | −14 | `ERROR_PPPP_SESSION_CLOSED_CALLED` |
     | −3 | `ERROR_PPPP_TIME_OUT` | −16 | `ERROR_PPPP_USER_LISTEN_BREAK` |
     | **−5** | **`ERROR_PPPP_INVALID_PARAMETER`** | −18 | `ERROR_PPPP_UDP_PORT_BIND_FAILED` |
     | −7 | `ERROR_PPPP_FAIL_TO_RESOLVE_NAME` | −19 | `ERROR_PPPP_USER_CONNECT_BREAK` |
     | −9 | `ERROR_PPPP_ID_OUT_OF_DATE` | −26 | `ERROR_PPPP_UNAUTHORIZ` |
     | −11 | `ERROR_PPPP_INVALID_SESSION_HANDLE` | −28 | `ERROR_PPPP_DB_OPERATION_FAILED` |
     | −12 | `ERROR_PPPP_SESSION_CLOSED_REMOTE` | −30 | `ERROR_PPPP_BUFFER_ERROR` |
     | −99 | `ERROR_PPPP_SERVER_CHANGED` | −9001 | `ERROR_PPPP_DEVICE_MAX_SESSION` |

     Cualquier otro código → `"[ERROR_PPPP_UNKNOWN]"` (`0x297a8`).
     **`ERROR_PPPP_NOT_INITIALIZED` es el −1, no el −5**: el `result:-5` del equipo no
     significa «SDK sin inicializar».

     **El otro `return 4` del binario no es alcanzable con estos argumentos**: el de
     `0x42b28` (log «unknown version») se llega por `0x42918 cbz x26`, donde
     `x26 = sp[0x48] = iface1`, y `iface1` no puede ser NULL (si lo fuera, `create` ya
     habría crasheado al llamar a `iface1->init()`; además `sp[0x48]` se escribe una
     sola vez, en `0x423b4`). Tampoco entra el de `0x42a94`: exige `sp[0x50] != NULL`,
     y ese `serverStr` queda en cero por `0x424f8 str xzr,[sp,#0x50]` cuando `iface2` es
     NULL, que es nuestro caso al no medir 17 el `did2`. Queda descartado, así que el
     `4` viene sí o sí de la normalización.
  3. `writeCgi` comprueba `handle+0x204 != 0` **antes** de esperar, así que un `writeCgi`
     de sondeo devuelve `false` **al instante** cuando el túnel no se montó: es la forma
     barata y exacta de separar «`4` = error» de «`4` = session handle 4».
  4. Un segundo `connect()` sobre el mismo handle devuelve `3` sin tocar la red
     (`0x422c4`): **cada intento necesita su propio `create()`**, y `destroy()` libera la
     entrada de la tabla de handles (`0xbe140`).

  `mode`: 126 por defecto; 123 (`0x42c70`, con comprobación de prefijos `VSGG/VSGM/VSGB/
  VSGS` por `strncmp(..,4)`) y 121 (`0x43014`, que a su vez pasa por `get_dev_off_time`)
  tienen rama propia. `p2pType==1` solo selecciona la ruta de `iface2`, que está en NULL
  al no pasar un `did2` de 17 chars, así que con `p2pType` 0 y 1 se llega al mismo sitio.

- **Cascada de conexión** (`P2PControlManager.buildAttempts`/`tryAttempt`): como ni el
  firmware ni el binario fijan qué combinación es la válida, `connect()` prueba en orden
  y **crea un handle nuevo en cada intento**:

  | # | `mode` | `target` | `p2pType` | `cachedIps` | etiqueta |
  |---|---|---|---|---|---|
  | 1 | 126 | DID | 0 | `null` | `did+ips-null` (el **primer** connect de O-KAM Pro) |
  | 2 | 126 | DID | 0 | IP | `did+lan` |
  | 3 | 126 | DID | 0 | `""` | `did-sin-ips` |
  | 4 | 126 | DID | 1 | IP | `did+p2ptype1` |
  | 5 | 123 | DID | 0 | IP | `123+lan` |
  | 6 | 121 | DID | 0 | IP | `121+lan` (devuelve el error PPCS **crudo**) |
  | 7 | 126 | VUID | 0 | IP | `vuid+lan` (solo si ≠ DID) |
  | 8 | 126 | IP | 0 | IP | `ip-directa` (compatibilidad, la que se usaba antes) |

  Cada intento hace `create → clientSetVuid → connect → writeCgi(sondeo) → login` y solo
  se conserva el handle si **los tres** pasan; todo queda en la consola diagnóstica con
  el `rc`, los milisegundos y la etiqueta del intento.

  **`cachedIps = null` es seguro** (es el único `String` de JNI que se pasa nulo): el
  wrapper lo detecta (`cbz x25` en `0x40388`) y entrega `x26 = 0`; `client_connect` lo
  reenvía sin dereferenciar (`mov x5, x24` en `0x42784`). La única lectura de ese
  registro es `csel x8,x8,x24,eq` en `0x42544`, que solo elige entre el puntero y la
  cadena literal `"NULL"` **para imprimirla en el log**.

- `clientSetVuid` (`0x421bc`) copia la cadena en `handle+0x210` (buffer de 64 bytes, acepta
  1..63) y **`client_connect` la lee de ahí** (refs `0x42664`, `0x429a0`, `0x42f70`) para
  resolver el rendezvous. Debe llamarse **antes** de `connect`.
- `writeCgi` exige: handle no nulo, `handle+0x80` (interfaz) no nula, y `handle+0x204 != 0`
  — este último solo se pone a 1 en la ruta de éxito de `client_connect` (`0x430a0`).
  `login` además almacena usuario/contraseña en `handle+0x98` y `handle+0x199`, que son los
  que rellena el `GET /…loginuse=…`.

Todas las llamadas nativas corren en `Dispatchers.IO`: el SDK bloquea durante el
rendezvous P2P y **nunca** debe ejecutarse en el hilo principal.

### 5.6 VUID

El VUID real de la cámara es de 13 caracteres y **empieza por `VE`** (prefijo **`VE`** →
`JNIApi.clientSetVuid`); el de este equipo se define en `secrets.properties`
(`cam.vuid`). Es el valor por defecto de `CamConfig.DEFAULT_VUID` y de `DEFAULT_UID`
(en O-KAM Pro el UID de etiqueta y el VUID de rendezvous son el mismo código). Se
guarda en SharedPreferences `camrevert_config` y se reenvía en cada reconexión.

### 5.7 Estados del canal (`stateListener`)

Códigos leídos del binario (nombres de la macro de estado PPCS):

| # | Nombre | # | Nombre |
|---|---|---|---|
| 0 | `PPCS_SUCCESS` | 7 | `TCP_ERR_USER_DISCONNECT` |
| 1 | `TCP_ERR_INITIALING` | 8 | `TCP_ERR_SERVER_DISCONNECT` |
| 2 | `TCP_ERR_WORKING` | 9 | `TCP_ERR_DEVICE_DISCONNECT` |
| 3 | `TCP_ERR_INVAILD_SESSION` | 10 | `TCP_ERR_RELAY_TEST` |
| 4 | `TCP_ERR_TIME_OUT` | 11 | `TCP_ERR_GET_HOSTNAME` |
| 5 | `TCP_ERR_CONNECT_FAILURE` | 12 | `TCP_ERR_UUID_INVALID` |
| 6 | `TCP_ERR_NETWORK` | 13 | `TCP_ERR_DEVICE_EXIST` |

La app pasa a `ONLINE` con **2**, y a `ERROR` con 3..9.

### 5.8 PTZ — `decoder_control.cgi?command=N&onestep=0&`

Comandos observados en `libapp.so`: `0,1,2,3,4,5,6,7,22,23,25,26,27,28,29,78,79,80` y
`command=84&param=`, `command=25&onestep=0&channel=`.

| Código | Dirección (convención VStarcam, **inferida**) |
|---|---|
| 0 | Arriba |
| 1 | Stop |
| 2 | Abajo |
| 3 | Izquierda |
| 4 | Derecha |
| 5 | Arriba-Izquierda |
| 6 | Arriba-Derecha |
| 7 | Abajo-Izquierda |
| 8 | Abajo-Derecha |
| 25 | Centrar cabezal / **corrección** (evidenciado con `&channel=`) |

La asignación dirección↔código vive en `PtzDirection` (Kotlin) y la app permite invertir
ejes (X, Y) e intercambiar diagonales desde la pestaña PTZ, porque el orden físico de los
motores varía según modelo. `stopPtz()` envía `command=1` en `ACTION_UP`.

Presets: `set_sensor_preset.cgi?sensorid=255&presetid=N&` (evidenciado).
"Ir a preset" usa `decoder_control.cgi?command=64+N&onestep=0&` — **no hay cadena literal
en el binario**, se asume la convención VStarcam; la base es `Cgi.GOTO_PRESET_BASE`.
Patrulla automática: ciclo de direcciones UP→RIGHT→DOWN→LEFT gestionado por la app.

### 5.9 Visión nocturna — `camera_control.cgi`

```
camera_control.cgi?param=37&value=0&fullcolormode=X&fullcolordefault=0&
```
`X`: 0 = infrarrojo (B/N), 1 = full color, 2 = inteligente. **Evidenciado** con
`fullcolormode=2&fullcolordefault=0` y `=1` en el binario.

La UI expone **4 modos**: Infrarrojo (0), Color (sub-modo 1/2/3), Inteligente (2) y
Starlight (3, que la app oficial refuerza con `set_whiteled_value.cgi?mode=X&` — un
**no-op** verificado en este firmware, §5.10).
Se conoce el parámetro `fullcolormode` 0/1/2; el cuarto modo (starlight) y los
sub-modos de color son **mapeo de la app sobre los mismos valores** y quedan por
confirmar en el equipo.

Otros parámetros observados: `param=1,2,3,5,11,14,16,33,34,36,38,39,40`
(`39`/`40` con `x_percent=` → zoom digital).

**Lector del modo real**: `get_camera_params.cgi?` → `full_color_mode` (y
`night_vision_mode`). Medido el 2026-10-17: `fullcolormode=1` → la relectura da
`full_color_mode=1`; `fullcolormode=0` → `0`. La orden **sí** funciona.

#### Volteo de imagen — `param=5`, no `param=3`

**Medido en el equipo (2026-10-17), con relectura de `get_camera_params.cgi?`:**

| Enviado | `flip` después |
|---|---|
| `camera_control.cgi?param=3&value=1&` | `flip=0` — **no toca nada** |
| `camera_control.cgi?param=5&value=1&` | **`flip=1`** ✅ |
| `camera_control.cgi?param=5&value=0&` | **`flip=0`** ✅ |

`param=3` sí existe en el binario oficial (`camera_control.cgi?param=3&value=`) pero en
**este** firmware no mueve `flip`; el que lo mueve es `param=5`. CamRevert mandaba `param=3`
desde el principio: la casilla «Voltear imagen» era un **no-op silencioso**.

> **Trampa de lectura**: `camera_control.cgi` contesta **`result= 0;` siempre**, incluso
> cuando aplica el cambio. El «`result= 0;` a secas = rechazado» de §5.1 vale para
> `trans_cmd_string.cgi` / `set_alarm.cgi`, no para éste: la única forma de saber si hizo
> algo es **releer** `get_camera_params.cgi?`.

### 5.10 Luz blanca

```
set_whiteled_value.cgi?whiteled=0&      → LED APAGADO
set_whiteled_value.cgi?whiteled=100&    → LED encendido al máximo
set_whiteled_value.cgi?whiteled=N&      → LED al N %   (0 ≤ N ≤ 100) ← EL ÚNICO MANDO
get_whiteled_value.cgi?                 → result= 0;  var whiteled=<0..100>;  var light=<0|1>;
```

Comandos **descartados con evidencia** (no se implementan):

| Comando | Veredicto |
|---|---|
| `display_pwm_ctrl.cgi?common=1&value=X&` | **No existe** en este firmware: no responde ni un byte. Era el «brillo PWM» de la primera UI. |
| `trans_cmd_string.cgi?cmd=2109&command=0&light=0\|1&` (lo que manda la app oficial) | **Redundante**: `whiteled` ya enciende y apaga. Sus campos `lightStatus`/`sirenStatus`/`alarmLedStatus` son **eco de la petición**, jamás estado real. |
| `set_whiteled_value.cgi?mode=0\|1&` | **No-op**: es exactamente lo que envía el botón directo de la app oficial, y por eso su botón «no responde». |
| `set_whiteled_value.cgi?ledtimes=N&` | Aceptado (`var result="ok"`), **no altera el brillo ya puesto**; el apagado diferido **no está verificado**. **RETIRADO de la UI** (2026-10-17): con él se quitó el «Temporizador de Luz» de la pestaña Luz, a petición del usuario, porque ofrecía un mando sin efecto comprobado. CamRevert ya no lo envía nunca. |
| `trans_cmd_string.cgi?cmd=2108&command=1&lightMode=X&` | Aceptado (`var result="ok"`), **efecto no observado**. |
```

**RESUELTO y verificado físicamente en el equipo (2026-10-17, con el usuario mirando el
LED).** `whiteled` **no es un booleano: es el brillo en porcentaje**, y es el **único**
mando que la luz necesita.

| Acción | Lectura de `get_whiteled_value.cgi?` | LED |
|---|---|---|
| `whiteled=0` | `var whiteled=0;  var light=1;` | apagado |
| `whiteled=100` | `var whiteled=100;  var light=1;` | **encendido a tope** |
| deslizador de la app, 0 ↔ 80 ↔ 100 | devuelve el valor exacto puesto | **apaga / 80 % / enciende a tope** (confirmado por el usuario) |

**El fallo histórico estaba aquí**: se creía que `whiteled` era booleano, así que se
mandaba `whiteled=1` para «encender». Eso deja el LED al **1 %** de brillo — invisible de
día — y hacía parecer que la luz no funcionaba. De ahí venían también las dos creencias
que el barrido desmintió:

- «`cmd=2109&command=0&light=1&` es el único que devuelve el estado real» → **falso**: su
  respuesta **ecoa la petición** (`lightStatus`/`sirenStatus`/`alarmLedStatus` son los
  mismos valores que se acaban de enviar). El estado real sólo lo da
  `get_whiteled_value.cgi?`.
- «`light` y `whiteled` son dos campos independientes» → **`whiteled` basta**: manda el
  brillo y hace de interruptor. El campo `light` **no se usa** (CamRevert lo ignora): en
  una prueba apareció en `0` con `whiteled=100` y el LED seguía obedeciendo al
  deslizador; el valor raro venía de tener **dos clientes a la vez** (la app del móvil y
  la del emulador) manipulando la luz sobre la misma cuenta. Con un solo cliente, el
  deslizador manda y el estado vuelve tal cual.

> **Lección de operación**: no manejes la luz desde dos instalaciones a la vez (móvil +
> emulador). Se pisan los comandos y el estado que se lee deja de corresponder a lo que
> acabas de mandar.

#### Implementación

| Pieza | Qué hace |
|---|---|
| `P2PControlManager.setWhiteLedBrightness(0..100)` | **El mando**: `set_whiteled_value.cgi?whiteled=N&` |
| `P2PControlManager.setWhiteLed(on, brightness)` | Conveniencia: enciende a `brightness` (forzado a ≥1) o manda `0` |
| `P2PControlManager.queryWhiteLed()` | `get_whiteled_value.cgi?` |
| `P2PControlManager.flashWhiteLed(ms, brightness)` | Sube a `brightness`, espera y vuelve a `0` |
| `MainActivity.setLightLevel(v)` | Deslizador de la pestaña **Luz** (`0` = apagado) |
| `MainActivity.applyWhiteLed(on)` | Casilla **Luz blanca** de Funciones: un toque, usa el último brillo |
| `MainActivity.syncLightState(whiteled)` | Pinta la UI con lo que reporta la cámara, sin reenviar nada |
| `onP2pEvent` → `AUTORIZADO` | Consulta sola el estado para pintar el deslizador al conectar |
| `bindLightTab` (fin) | **Vuelve a preguntar** luz y sirena/estrobo al abrir la pestaña (si la sesión ya estaba autorizada, las respuestas llegaron antes de que existieran los `View`) |

**La UI se simplificó a propósito**: la pestaña Luz tiene **un solo control de luz**, el
deslizador de brillo `0..100` (el brillo *es* el interruptor), más el destello. El
interruptor separado y el «barrido de diagnóstico» (`probeWhiteLed()`, ya retirado) se
eliminaron con la evidencia recogida; el **temporizador** (`ledtimes=`) se retiró después,
al no poder verificarse su efecto (§5.11).

### 5.11 Sirena policial y estrobo

```
trans_cmd_string.cgi?cmd=2109&command=0&siren=1&        ← mandar
trans_cmd_string.cgi?cmd=2109&command=0&alarmLed=1&     ← mandar
trans_cmd_string.cgi?cmd=2109&command=2&                ← LEER estado real (§5.10)
trans_cmd_string.cgi?cmd=2108&command=1&sirenMode=1&    ← modo (efecto no observado)
trans_cmd_string.cgi?cmd=2108&command=1&alarmLedMode=2& ← modo (efecto no observado)
```

**El estrobo NO es un invento: existe y se controla.** `alarmLedStatus` es un campo
**legible** (`cmd=2109&command=2&`), así que la casilla puede pintar el estado real que
reporta el equipo. Y la sirena tampoco: `sirenStatus` sale en la misma respuesta.

| Pieza | Qué hace |
|---|---|
| `P2PControlManager.setSiren(on)` | `cmd=2109&command=0&siren=<0\|1>&` |
| `P2PControlManager.setStrobe(on)` | `cmd=2109&command=0&alarmLed=<0\|1>&` |
| `P2PControlManager.querySirenStrobe()` | `cmd=2109&command=2&` — **el único lector** de `alarmLedStatus` |
| `MainActivity.refreshSirenStrobe(delayMs)` | Manda la orden y **relee** a los 700 ms (0 ms al autorizar) |
| `MainActivity.paintSirenStrobe()` | Pinta fila y switch con lo que contestó la cámara |
| `MainActivity.syncSirenState(siren, strobe)` | Estado real → variables + repintado |

La pestaña **Luz** tiene ahora una **fila de sirena** (no un botón ciego): rojo
**translúcido** con «Activar sirena / Apagada · toca para activar» cuando está apagada, y
rojo **sólido** con «Detener sirena / Sonando ahora mismo» cuando el equipo dice que está
sonando. El icono se tiñe a juego. El interruptor del estrobo lleva debajo el texto del
estado real («Estado real: encendida/apagada»).

Al **abrir** la pestaña (y al autorizar la sesión) se relanza la lectura: si la sesión ya
estaba autorizada antes de crear los `View`, las respuestas llegaban cuando esos widgets
no existían y la UI se quedaba con el valor recordado en prefs (se veía el deslizador de
luz a 100 mientras la cámara decía 0).

> **Pendiente de confirmación física**: que la sirena **suene** y que el estrobo
> **parpadee**. El estado se lee y se escribe correctamente, pero eso solo se comprueba
> oyendo/viendo el equipo.

**El «Temporizador de Luz» se eliminó** (2026-10-17, petición del usuario): 4 botones
(`Sin temporizador` / `5 min` / `10 min` / `Siempre`) que enviaban
`set_whiteled_value.cgi?ledtimes=` — aceptado pero **sin efecto verificado** (§5.10). Con
él se retiraron `setWhiteLedTimer()`, `CamConfig.whiteLedTimerSec` y las 5 cadenas.

### 5.12 Detección IA — auditada en el equipo (2026-10-17)

**Los mandos del panel de IA del app oficial (`cmd=4500&command=11&…`) son un NO-OP en
este firmware.** Medido con relectura:

| Enviado | Respuesta | Relectura | Veredicto |
|---|---|---|---|
| `cmd=4500&command=11&track=1&` | `result= 0;  var cmd=4500;` | `enable=0` | **NO-OP** |
| `cmd=4500&command=11&sensitivity=1&` | `result= 0;  var cmd=4500;` | `sensitive=3` | **NO-OP** |
| `cmd=2127&command=0&enable=1&` | `var enable=1;` | `enable=1` | ✅ manda |
| `cmd=2126&command=0&sensitive=1&mark=123456789&` | `var sensitive=1;` | `sensitive=1` | ✅ manda |
| `cmd=2126&command=0&bHumanoidFrame=1&` | `var bHumanoidFrame=1;` | `bHumanoidFrame=1` | ✅ manda |
| `set_alarm.cgi?enable_alarm_audio=0&motion_armed=0&` | `var result="ok";` | `alarm_status=0` | ✅ manda |
| `cmd=2125&command=0&hide_led_disable=1&` | `var hide_led_disable=1;` | `=1` | ✅ manda |
| `cmd=2106&command=4&humanDetection=1&` | `var cmd=2106;` | **no hay lector** | ⚠️ sin verificar |

Mandos y lectores que usa CamRevert:

```
// Detección de movimiento (armado) — el lector bueno NO es get_params.cgi (filtra secretos):
set_alarm.cgi?enable_alarm_audio=0&motion_armed=1&
get_status.cgi?                    → var alarm_status=<0|1>;      ← sigue a motion_armed

// Sensibilidad y cuadro de detección humanoide:
trans_cmd_string.cgi?cmd=2126&command=0&sensitive=<1|2|3>&mark=123456789&
trans_cmd_string.cgi?cmd=2126&command=0&bHumanoidFrame=<0|1>&
trans_cmd_string.cgi?cmd=2126&command=1&mark=123456789&   ← leer (sensitive + bHumanoidFrame)

// Seguimiento de personas (PTZ):
trans_cmd_string.cgi?cmd=2127&command=0&enable=<0|1>&
trans_cmd_string.cgi?cmd=2127&command=1&                  ← leer (enable + track_status)

// Volteo de imagen (está en Funciones, no en Luz):
camera_control.cgi?param=5&value=<0|1>&
get_camera_params.cgi?             → var flip=<0|1>;              ← leer

// Ocultar el LED de estado (funciona, pero no está en la UI):
trans_cmd_string.cgi?cmd=2125&command=0&hide_led_disable=<0|1>&
trans_cmd_string.cgi?cmd=2125&command=1&                          ← leer

// Sin lector conocido:
trans_cmd_string.cgi?cmd=2106&command=4&humanDetection=<0|1>&
trans_cmd_string.cgi?cmd=4500&command=11&alarm_sound=<0|1>&
trans_cmd_string.cgi?cmd=4500&command=19&alarm_sound_volume=<n>&
trans_cmd_string.cgi?cmd=2106&command=9&pirPushSwitch=<0|1>&
```

**Escala de sensibilidad — inversa:**

| UI | `sensitive` |
|---|---|
| Alta | **1** |
| Media | **2** |
| Baja | **3** |

El `mark=123456789` es **obligatorio** en la escritura de `cmd=2126`.

**Los tres arreglos que salieron de la auditoría** (antes, tres casillas mentían):

1. **Seguimiento de personas** mandaba `cmd=4500&command=11&track=` → no hacía nada. Ahora
   `setTracking()` manda `cmd=2127&command=0&enable=`.
2. **Sensibilidad** mandaba `cmd=4500&command=11&sensitivity=` → no hacía nada. Ahora
   `setMotionSensitivity()` manda `cmd=2126&command=0&sensitive=&mark=123456789&`.
3. **Voltear imagen** mandaba `camera_control.cgi?param=3` → no movía `flip`. Ahora manda
   `param=5`.

Y una cuarta cosa que era peor que un mando muerto: la casilla **Detección de movimiento**
no leía nada y arrancaba en `false`, mientras la cámara **tenía la detección armada**
(`alarm_status=1`). Consecuencia real: pulsar «Aplicar detección» **apagaba la detección de
la cámara** sin que se viera. Ahora `syncMotionArmed()` la pinta con `alarm_status`.

**Sin lector (documentado a propósito):** «Detección humana» (`humanDetection`). No existe
ningún CGI que publique su estado — `get_alarm.cgi?` no existe y `get_params.cgi?` /
`get_camera_params.cgi?` solo traen las *capacidades* (`support_humanDetect`,
`support_humanoidFrame`, `support_AI`) — así que esa casilla es la única del panel que no
puede reflejar la realidad. Se deja como recordatorio local y **queda pendiente** confirmar
su efecto delante de la cámara.

Humo/incendio (de otros modelos): `cmd=2206&command=1&smoke_sensitivity=` /
`fire_sensitivity=`.

### 5.13 Salud del equipo y MicroSD

```
get_status.cgi?                                    estado general (y tarjeta)
get_camera_params.cgi?                             parámetros
reboot.cgi?                                        reinicio
get_record.cgi?                                    esquema de grabación
trans_cmd_string.cgi?cmd=2017&command=11&mark=212&type=6&   marca de archivo
get_record_file.cgi?GetType=date&                  fechas con grabaciones
get_record_file.cgi?GetType=file&dirname=YYYYMMDD&PageSize=1000&   lista de clips del día
record_fastplay.cgi?ctrl=1&playlist=FILENAME&      reproducir archivo TF
record_fastplay.cgi?ctrl=0&                        detener reproducción
livestream.cgi?streamid=4&filename=FILENAME&       stream del archivo TF
auto_download_file.cgi?server=                     descarga
del_file.cgi?name=FILENAME&                        borrar archivo
set_formatsd.cgi?                                  formatear tarjeta
set_recordsch.cgi?enable=1&                        planificación de grabación
```

**Claves de la tarjeta** (`get_status.cgi` / `get_record.cgi`):
`sdtotal`, `sdfree`, `sdstatus`, `record_sd_status` (también `sd_total`, `sd_free`,
`record_status`). La UI las lee con un regex tolerante a `"`/`:`/`=`.

**Unidades**: los firmwares VStarcam devuelven `sdtotal`/`sdfree` en **KB** cuando el valor
es grande (una tarjeta de 64 GB aparece como ≈ 63 896 576); si el valor es < 1 000 000 se
asume MB. El valor crudo siempre queda en el log de estado para poder verificar la firma
real en el equipo.

`sdstatus`: 0 = sin tarjeta, 1 = OK, 2 = formateando, 3 = error.

#### Navegador de clips (fechas → horas → clips) — formato real y decisiones

**Medido en el equipo el 2026-10-17**, no inferido:

```
get_record_file.cgi?GetType=date&
    record_date[0]="20261017";  var record_datenum=1;

get_record_file.cgi?GetType=file&dirname=20261017&PageSize=1000&
    result= 0;  var record_name0=new Array();  var record_size0=new Array();
    record_name[0]="20261017000040_100.mp4";  record_size[0]=9600553;  …
    var record_filenum=47;  var totol_page=2;  var current_page=2;
```

Tres trampas, cada una de ellas un bug:

| Trampa | Qué pasaba | Arreglo |
|---|---|---|
| **`PageSize` es obligatorio** | Sin él la cámara devuelve sólo la **última página** (~96 clips del final del día) y parece que el día es eso | `PageSize=1000` → el día entero en una respuesta (399 clips, 28 KB) |
| **El túnel trocea la respuesta** | El día llega en **dos** devoluciones de `commandListener` (24 583 B + 3 552 B); reemplazar la lista en cada trozo dejaba **49 clips** de 399 | Acumular en un mapa y repintar **400 ms después del último trozo** (si no, se repinta una vez por trozo) |
| **Formato con `[N]=`** | El parser buscaba `"name":"…"` (una forma que **no existe**) y `\b20\d{6}\b` en cualquier respuesta, así que los propios nombres de fichero metían fechas falsas en la fila de fechas | `record_name\[(\d+)]="…"` + `record_size\[(\d+)]=(\d+)` |

`record_filenum`/`totol_page`/`current_page` llegan **desfasados** (decían 47 y 2 con 399
clips reales): no sirven para paginar.

**Sufijos**: `_100` (262 clips, 2 879 MB) y `_011` (137, 411 MB) son las **dos series
simultáneas** del equipo; las de `_100` pesan ~4× más (flujo principal). **No hay filtro
por lente**: `sensor=0|1` y `channel=0|1` fueron ignorados (los cuatro sondeos devolvieron
las dos series). Los 399 clips del día se reparten en 18 horas: **entre las 15:00 y las
19:59 no hay ni uno**.

**UI: fecha → hora → clips.** El panel de pestañas mide ~140 px de alto (≈700 en vertical),
así que 399 filas no se pueden ni usar ni pagar: pintarlas de golpe subía el heap nativo de
19 MB a 45 MB en el emulador. El navegador abre el día **agrupado por horas**
(`22:00–22:59 · 3 · 11,7 MB`) cuando trae más de `SD_DIRECT_LIMIT = 40` clips, y con menos
lista los clips directamente. Nunca hay más de ~24 filas pintadas, y el «Atrás» es
jerárquico (`‹ Horas` → `‹ Fechas`).

#### Reproducción de clip TF — por qué NO está integrada

> **El ▶ se retiró de la UI (2026-10-17, punto 3).** La fila del clip ya **no** es pulsable
> y el panel **no** tiene botones «Reproducir»/«Detener»: era un botón que no reproducía
> nada **y además dejaba la sesión sorda** (§5.15). En su lugar queda una nota de una línea
> en la pestaña MicroSD. Los comandos `record_fastplay`/`livestream` siguen en
> `P2PControlManager` (`playRecord`/`stopRecord`/`recordLiveStream`) porque son los del app
> oficial y los usará el reproductor nativo, pero **ya no los envía nadie desde la UI**.

Los dos comandos del app oficial **se envían correctamente** (verificado en logcat) y la
cámara los acepta:

```
[writeCgi] OK   record_fastplay.cgi?ctrl=1&playlist=20261017221402_100.mp4&
[writeCgi] OK   livestream.cgi?streamid=4&filename=20261017221402_100.mp4&
```

Pero **el flujo del fichero no sale por el túnel CGI**: `livestream.cgi?streamid=4` responde
`result= 0;` con 12 bytes y ninguna carga útil, y las rutas directas por el túnel
(`<clip>?`, `sd/<clip>`, `record/20261017/<clip>`, `20261017/<clip>`) **no devuelven ni
respuesta**. Del lado JNI tampoco hay salida: `libOKSMARTPPCS.so` expone `write` pero **no**
`client_read` (§5.4), y `client_check_buffer` sólo entrega metadatos. Y
`record_fastplay.cgi?ctrl=1` **no cambia el directo por RTSP**: el OSD del vídeo sigue
avanzando con el reloj real, no con el clip.

**La salida real ya está en el XAPK**: `libOKSMARTPLAY.so` (10,8 MB) exporta una **API Java
completa de reproductor P2P** (`Java_com_veepai_AppPlayerApi_*`): `createPlayer`,
`setPlayerSource`, `start/pause/resume/stop`, `speed`, `setProgress`/`setProgressCallback`
(búsqueda), `screenshot` y además **`startDown`/`stopDown`/`saveMP4`/`saveMP4Progress`/
`tsToMP4`/`mergeTSFiles`** — el **descargador a MP4**. Falta **mapear esa API** (los *stubs*
JNI no llevan firma: los tipos de argumento hay que deducirlos del desensamblado) y pasar el
render a `SurfaceView` (el nativo dibuja en un `Surface`, no en un `TextureView`). Queda como
**ítem propio** (§10).

Y **el tercer efecto era el peor**: tras `ctrl=1`/`livestream` el túnel puede quedarse
**sordo** (la cámara empieza a servir el fichero y el canal CGI deja de contestar). Medido
con el disparo intermitente: el túnel moría entre 30 s y 2 min después, `ctrl=0` no lo
recuperaba (su respuesta ya no llegaba) y la app seguía diciendo «en línea». Es lo que
motiva el vigilante de §5.15 y, sobre todo, la retirada del ▶.

---

## 5.14 Autorización de sesión — `eye4_authentication.cgi` (**obligatorio**)

> **Ésta era la pieza que faltaba para que la cámara obedeciera.** Verificado en
> equipo, no inferido: sin este preludio **todos** los CGI devuelven
> `result= 0;  result=-2;  ` y el domo no se mueve; con él responden
> `result= 0;  var result="ok";  ` y el domo se mueve en los cuatro ejes, sus paradas
> (`command` 0..7) y el centrado (`25`).

El túnel P2P, `login()` y el `get_status.cgi` de sondeo **no autorizan nada**: son
condiciones necesarias pero no suficientes. La cámara exige, dentro de la propia
sesión, un **token firmado de la cuenta EYE4**:

```
eye4_authentication.cgi?loginAccount=<userId>&loginToken=<token>&
    → result= 0;  eye4_auth=1;
```

Después de eso cualquier orden se ejecuta. Es decir, el ciclo real es:

```
create → clientSetVuid → connect → writeCgi(sondeo) → login
      → ★ eye4_authentication.cgi  (esperar eye4_auth=1)  ★
      → decoder_control.cgi / camera_control.cgi / …
```

**Única excepción**: `get_status.cgi?` contesta siempre (por eso el `result=-1;vuid=…`
del sondeo es normal y no delata nada).

### De dónde sale el token (2 peticiones HTTPS, solo al conectar)

Se reproduce el flujo del visor oficial (`okam_native/account.py`, ya validado):

| Paso | Petición | Respuesta |
|---|---|---|
| 1 | `POST https://api.eye4.cn/user/summary` — form `name=<email>&oemid=VSTC` | `{"userid":<id numérico>,…}` |
| 2 | `GET https://api.eye4.cn/login/token?userid=<id>&password=<md5(clave)>&type=PC` | `{"token":"O1WG…%2F…%3D%3D"}` |

Cabecera `client_version: 10.0.1` en ambas.

**El token ya viene percent-encoded** (`%2F` = `/`, `%2B` = `+`, `%3D` = `=`, que es el
base64 del payload). Va **tal cual** al CGI: volver a codificarlo lo corrompe
(`%` → `%25`). La firma cambia en cada petición aunque el payload se repita.

### Implementación en CamRevert

| Pieza | Qué hace |
|---|---|
| `p2p/Eye4Auth.kt` | Las dos peticiones HTTPS y el parseo del JSON (regex, sin dependencias). |
| `CamConfig.accountUser` / `accountPassword` | Cuenta EYE4 en `SharedPreferences` (`account_user`, `account_password`); por defecto desde `secrets.properties` (`cam.accountUser`, `cam.accountPassword`). |
| `P2PControlManager.authenticateSession(cfg, force)` | Pide el token, envía el preludio y **espera el veredicto** (`CompletableDeferred` que completa `commandListener` al ver `eye4_auth=`), con timeout de 8 s. |
| `P2PControlManager.authorized` | `true` solo tras `eye4_auth=1`. |
| `commandListener` → `handleSessionAuth()` | Completa la espera y, si llega un `result=-2` sobre una sesión **ya autorizada** (token caducado), **reautoriza una vez** (`reauthArmed` evita bucles). |
| `CgiDebugReceiver --ez reauth true` | Repite el preludio sin recompilar (solo DEBUG). |

Si faltan las credenciales de la cuenta, la conexión **sigue en pie** (el vídeo RTSP no
depende de esto), pero la consola avisa `SIN TOKEN DE CUENTA` y los controles no
responderán. `Eye4Auth` es la **única** salida a Internet de CamRevert.

---

## 5.15 Vigilante de túnel sordo (`P2PControlManager`)

> **El problema.** El SDK puede quedarse **sordo**: `writeCgi` devuelve `true` (el flag
> `handle+0x204` sigue a 1), `stateListener` **no baja de `2`**, el RTSP sigue
> reproduciendo y la app pinta «en línea» — pero **no llega ni una respuesta**:
> `commandListener` deja de dispararse. Como el estado nunca sale de `ONLINE`, el
> supervisor de §7.1 **no se entera** y la sesión queda muerta en apariencia de sana.
> Medido en el equipo: la única salida era reiniciar la app.

**Cómo se detecta** (implementado y verificado en equipo, 2026-10-17/18):

| Pieza | Comportamiento |
|---|---|
| `sendRaw` marca pendiente | Tras cada `writeCgi` aceptado (`ok=true`) abre la ventana con `pendingSinceMs` (idempotente: **no** se reescribe mientras siga abierta, así la sonda no reinicia el reloj que mide) |
| `commandListener` la cierra | Cualquier carga que entre del equipo llama a `clearCgiPending()` |
| Ticker (2 s) | Solo actúa con `ONLINE` **y** `authorized`; si hay ventana abierta y lleva [CGI_ANSWER_TIMEOUT_MS] sin respuesta, manda la sonda; si la sonda tampoco contesta en [CGI_PROBE_GRACE_MS], declara el túnel sordo |
| Sonda | `CGI_WATCHDOG_PROBE = get_status.cgi?` — el único CGI que contesta siempre, autorizado o no (§5.14) |

**Tiempos (medidos, no elegidos a ojo).** La primera versión usaba 4 s y dio un **falso
positivo**: con la sesión sana, un `get_status.cgi?` de 2,5 KB tardó **4,46 s** en
contestar (envío 23:51:10.581 → respuesta 23:51:15.044). Quedan:

```
CGI_ANSWER_TIMEOUT_MS = 8_000   CGI_PROBE_GRACE_MS = 6_000   CGI_WATCHDOG_TICK_MS = 2_000
```

⇒ 8 s de silencio disparan la sonda y **14 s** de silencio total declaran el túnel sordo.
**Coste en reposo: cero tráfico** (sin ventana abierta el tick no hace nada).

**Cómo se recupera.** `forceReconnect()` **no cierra el handle por la vía nativa**. Tres
intentos medidos, dos abortos nativos:

| Intento | Cómo cerraba | Resultado |
|---|---|---|
| 1.º | `clientConnectBreak` + `disconnect` + `destroy` | **SIGABRT** |
| 2.º | `disconnect` + `destroy` | **SIGABRT** |
| 3.º (el que queda) | nada: `abandonHandle()` | ✅ recupera |

```
Abort message: 'FORTIFY: pthread_mutex_lock called on a destroyed mutex (0x76388a32c5e0)'
signal 6 (SIGABRT)   Thread-22   com.lgsusdev.camrevert
backtrace: … libc (pthread_mutex_lock) ← libtcb.so ← [anon:Mem_0x20000000]
```

`libtcb.so` + `[anon:Mem_0x20000000]` es el **traductor ARM→x86 del emulador**, así que el
aborto nace dentro de `libOKSMARTPPCS.so`. El mutex `0x…c5e0` es exactamente
`handle 0x76388a32c404 + 0x1dc` — el que inicializa `client_create`
(`pthread_mutex_init(p + 0x1dc)`): el hilo del SDK está atascado con el flujo del fichero,
despierta **después** de que `destroy` libere esa memoria y revienta al bloquear el mutex
ya destruido. Quitar `clientConnectBreak` no bastó: el responsable es el `destroy`.

`abandonHandle()` suelta el handle **solo en el lado Kotlin** (`handle = 0`, `authorized =
false`, `authGate = null`, ventana cerrada) y deja que el supervisor rehaga la cascada con
un `client_create` **nuevo**. Verificado de punta a punta con el gancho de depuración:
sordo → abandono → túnel confirmado → `eye4_auth=1` en **~10 s**, sin cerrar la app.

**Techo de recuperaciones: una por ejecución** (`DEAF_RECOVERY_MAX = 1`, sin reinicio de
ventana). Está medido que cada abandono deja su **sesión viva** en el equipo y que **con
dos la cascada ya no reconstruye el túnel** (dos recuperaciones dejan la app peor que
una). Al segundo episodio el vigilante **no insiste**: abandona el handle (para que un
«Conectar» manual no intente destruirlo y provoque el SIGABRT), deja el estado en `ERROR`
y avisa en pantalla: *«El túnel P2P no responde: cierra y vuelve a abrir la app»* — que es
exactamente el remedio que el usuario ya conocía.

**Gancho de depuración** (solo DEBUG, en `CgiDebugReceiver`), porque el disparo real es
intermitente (se reprodujo a las 23:43, 23:55 y 00:03 del 2026-10-17/18 y ya no):

```
adb shell am broadcast -n com.lgsusdev.camrevert/.debug.CgiDebugReceiver --ez deaf true
```

`simulateDeafTunnel()` abre la ventana 20 s en el pasado y marca la sonda como enviada: el
siguiente tick declara el túnel sordo y ejecuta la recuperación.

---

## 6. Credenciales por defecto

> Los identificadores de esta tabla son **placeholders**: los valores reales de cada
> equipo viven en `secrets.properties` (ignorado por git) o en el diálogo de
> Configuración. Ver `docs/CONFIGURACION.md`.

| Parámetro | Clave en `secrets.properties` | Clave en prefs |
|---|---|---|
| IP | `cam.ip` | `ip` |
| UID / DID | `cam.uid` | `uid` |
| VUID | `cam.vuid` | `vuid` |
| ID P2P real (17 chars) | `cam.p2pDid` | `p2p_did` |
| Dirección `DAS-…` | `cam.dasAddress` | `das_address` |
| Usuario de la cámara | `cam.user` (`admin`) | `user` |
| **Contraseña de la cámara** | `cam.password` (`CAMPASSWORD`) | `password` |
| **Email de la cuenta EYE4** | `cam.accountUser` (`TUCORREO`) | `account_user` |
| **Clave de la cuenta EYE4** | `cam.accountPassword` (`PASSWORD`) | `account_password` |
| Puerto RTSP fija | `cam.portFixed` (`10555`) | `port_fixed` |
| Puerto RTSP PTZ | `cam.portPtz` (`10554`) | `port_ptz` |
| Canal de voz P2P | `1` | `voice_channel` |
| Códec de voz | G.711 A-law | `voice_codec` |
| Transporte RTP | TCP interleaved (`true`) | `rtsp_tcp` |
| Conexión automática al abrir la app | — (defecto `true`) | `auto_connect` |
| Forzar decodificador por software | — (defecto `false`) | `prefer_software_decoder` |

Todo se persiste en SharedPreferences `camrevert_config` (local, sin nube).
Los valores viven en `CamConfig.DEFAULT_*`.

---

## 7. Ciclo de conexión implementado

`connect()` **no** hace una única llamada: recorre la **cascada** de la §5.5 y conserva el
primer handle que pase las tres comprobaciones. Cada intento usa **handle propio** (un
segundo `connect()` sobre el mismo devuelve `3` sin tocar la red):

```kotlin
P2PControlManager.init()                                   // JNIApi.init(...)

for (a in buildAttempts(did, vuid, ip)) {                  // 8 combinaciones, §5.5
    val h = JNIApi.create(did, "")                         // "" ≠ null y ≠ did2 de 17
    if (h == 0L) continue
    JNIApi.clientSetVuid(h, vuid)                          // handle+0x210, ANTES de connect

    // target = TargetID (DID/VUID) y cachedIps = lista de IPs (net_ips). ¡NO al revés!
    val rc = JNIApi.connect(h, a.mode, a.target, a.p2pType, a.cachedIps)   // ≥0 = session handle
    if (rc < 0) { destroy(h); continue }

    // Desambigúa rc=4: writeCgi exige handle+0x204 y responde «al instante» si no está.
    if (!JNIApi.writeCgi(h, "get_status.cgi?", 1500)) { destroy(h); continue }
    if (!JNIApi.login(h, user, password))            { destroy(h); continue }

    ONLINE; break                                      // túnel confirmado
}

// ★ Preludio OBLIGATORIO de sesión (§5.14): sin esto todo CGI devuelve result=-2.
val credential = Eye4Auth.fetch(cfg.accountUser, cfg.accountPassword)   // HTTPS, api.eye4.cn
JNIApi.writeCgi(h, "eye4_authentication.cgi?loginAccount=${credential.userId}" +
                   "&loginToken=${credential.token}&", 5000)             // token tal cual
// la cámara contesta `eye4_auth=1` → commandListener → authGate.complete(true)
// ⇒ authorized = true

JNIApi.writeCgi(h, "decoder_control.cgi?command=25&onestep=0&channel=0&", 5000)  // 5000 = timeout ms
JNIApi.write(h, voiceChannel, g711Frame, 5000)         // longitud = GetArrayLength
// respuesta → ClientCommandListener → SharedFlow<CgiResponse>
```

Expuesto en UI:
- `StateFlow<ConnState>` → `DISCONNECTED / CONNECTING / ONLINE / ERROR`
- `StateFlow<Boolean> retrying` → `true` mientras el supervisor tiene un reintento en
  vuelo. La barra superior lo usa para no enseñar un «Error P2P» seco: mientras esté
  activo pone **«Reconectando…»** en ámbar.
- `authorized` (con `eye4_auth=1`) → si la sesión acepta órdenes (§5.14)
- `SharedFlow<CgiResponse>` → respuestas crudas UTF-8 de la cámara
- `SharedFlow<P2pEvent>` → **consola diagnóstica en vivo**: cada retorno de
  `init/create/connect/login/setVuid/writeCgi/write`, cada código de `stateListener`,
  las cargas de `ClientCommandListener`, `ipsListener` y `releaseListener`, con marca de
  tiempo `HH:MM:SS.mmm`. Cada intento de la cascada va **etiquetado**
  (`[did+lan] connect: rc=4 en 1 ms — …`, `[121+lan] probe: … → false`), de modo que se
  ve exactamente qué combinación abrió el túnel. Se ve en el panel desplegable (botón
  terminal de la barra superior o la casilla «Consola P2P» de Funciones).

### 7.1 Supervisor de reconexión automática (`scheduleAutoRetry`)

> **Bug resuelto.** Existía un método `scheduleRetry(config)` que **nadie llamaba**
> (0 referencias en todo el proyecto). Consecuencia: en cuanto el túnel P2P caía
> —reinicio de la cámara, corte de WiFi, `client_connect_break`— la app se quedaba en
> `ERROR` **para siempre** y la única salida era pulsar «Conectar» a mano, o cerrar y
> volver a abrir la app. Es exactamente el síntoma descrito en el punto 6.

El supervisor reintenta la **cascada completa** (§5.5, con handle nuevo por intento)
mientras alguien *quiera* la sesión en pie:

| Pieza | Función |
|---|---|
| `userWantsOnline` (`@Volatile`) | Lo pone `connect()` (arranque automático o toque en «Conectar»); lo baja `disconnect()` explícito. Con él en `false` el supervisor calla: no se puede quedar reconectando a quien acaba de pedir que se desconecte. |
| `scheduleAutoRetry(reason)` | Idempotente (si ya hay un reintento en vuelo no encola otro). Lanza **un bucle**, no una recursión: backoff `AUTO_RETRY_BASE_MS = 2000` → 4 s → 8 s → 15 s (tope) y sale **solo** cuando `_state` es `ONLINE` (**o** `authorized`, ver abajo) o el usuario ya no quiere la sesión. |
| `cancelAutoRetry()` | **Solo para un corte pedido por el usuario** ([disconnect]): cancela el job y pone `retrying = false`. **Nunca** se llama desde un `connect()` (ver el bug de abajo). |
| Puntos de disparo | `stateListener` (3..9), `releaseListener`, el fallo de la cascada, los `catch` de `connect()` y el vigilante de túnel sordo (§5.15). Todos pasan por el mismo embudo. |

> **El bucle mira también `authorized`** (arreglado el 2026-10-18). El paso a `ONLINE`
> llega por el `stateListener` (hilo nativo) y puede tardar unos milisegundos **más** que
> el retorno de `connect()`, así que con la condición antigua (`_state == ONLINE`) el
> bucle lanzaba una **segunda cascada redundante** justo después de la primera: medido,
> dos `eye4_authentication.cgi` seguidos con sus dos tokens HTTPS tras cada reconexión
> forzada. Añadir `|| authorized` lo corta (una reconexión = una cascada = un token).

En la consola P2P se ve como `[retry] reconexión automática en XXXX ms: <motivo>`.

> **Bug que este punto destapó, y que solo aparece con un corte de verdad.** La primera
> versión del supervisor se cancelaba **a sí misma**: `connect()` llamaba a
> `cancelAutoRetry()` al tener éxito, y como el supervisor *es* la corrutina que ejecuta
> `connect()`, `retryJob.cancel()` mataba la propia conexión en curso. Síntoma exacto,
> capturado en un `reboot.cgi?` real:
>
> ```
> 21:22:45  [cascade] … TÚNEL CONFIRMADO (connect + writeCgi + login)
> 21:22:54  [eye4] ERROR JobCancellationException: StandaloneCoroutine was cancelled
> ```
>
> El túnel quedaba montado pero **el preludio `eye4_authentication.cgi` moría a mitad**
> (§5.14) → sesión sin autorizar → **los controles no respondían** hasta pulsar
> «Conectar». Dos arreglos: (1) el supervisor usa un **bucle** que mira el estado y
> termina solo, y (2) tanto `connect()` como el `code=2` de `stateListener` dejaron de
> llamar a `cancelAutoRetry()` — ese `code=2` llega **durante** el propio `connect()`, así
> que también se autocancelaba.

#### Verificado en equipo (2026-10-17) — reinicio real de la cámara

`reboot.cgi?` inyectado por el receptor de depuración, cámara real, sin tocar la app:

```
21:31:11  [stateListener] code=11 TCP_ERR_GET_HOSTNAME → code=5 TCP_ERR_CONNECT_FAILURE
21:31:11  [retry] reconexión automática en 2000 ms: stateListener: TCP_ERR_CONNECT_FAILURE
21:31:26  [eye4] token de cuenta obtenido → preludio enviado     ← ya NO se cancela
21:31:27  [eye4] AUTORIZADO (eye4_auth=1)                        ← sesión utilizable
21:31:30  Fija/PTZ: stream finalizado por el servidor → reintento automático
```

Y en pantalla, sin intervenir: `Sin conexión` durante el corte y después
**`Reproduciendo ↻1`** en las dos lentes. Una segunda prueba (corte del tráfico de entrada
con `iptables`, sin reiniciar la cámara) dio lo mismo: `Sin conexión · 0 KB/s` → `↻1`.

---

## 8. Interfaz

- **Barra superior**: punto de estado con color, texto P2P (pulsa para conectar /
  desconectar), **sonido global único** (no hay icono de altavoz por cámara), consola P2P
  y ajustes.
- **Conexión automática (punto 1)**: `MainActivity.maybeAutoConnect()` lanza la cascada P2P
  desde `onCreate` **sin pulsar nada** (es lo que hace el visor oficial), salvo que
  `auto_connect` esté apagado, falte la librería nativa o falten datos (IP/UID/VUID), casos
  que solo dejan un aviso en la consola. El interruptor vive en Configuración, junto a
  «Forzar decodificador de vídeo por software» (punto 3).
- **Selector de vista**: `Fija | Ambas | PTZ` — decide qué lentes se muestran; en
  horizontal las dos van en fila (separador vertical) y en vertical apiladas.
- **Área de vídeo**: dos `CameraStreamView`. Cada una con etiqueta de lente, HUD pasivo
  (`estado / resolución / KB/s`, más **`↻n`** con las reconexiones acumuladas de la
  sesión, y `Reconectando…` mientras hay una en curso), y una **barra compacta**
  `[⛶] · [-] 1.0x [+] · HD/SD`.
- **Nunca se tapa el vídeo**: en cuanto hay un fotograma decodificado, la tarjeta no
  vuelve a poner el recuadro central ni un recuadro de estado encima (§4). En su lugar el
  HUD **cuenta** lo que pasa, con esta prioridad:
  `Sin conexión` (sin fotogramas nuevos en 6 s, con el último fotograma congelado en
  pantalla) → `Reproduciendo` → `Reconectando…` (reintento en vuelo) → `Buffering` →
  `Finalizado`. Sin imagen todavía —arranque en frío— se avisa con `Conectando`. El
  **detector de atasco** es lo que convierte un «Buffering» eterno en un aviso claro de
  que la cámara no está, en vez del «Finalizado» mudo de antes.
- **Alturas responsivas (punto 3)** — `MainActivity.applyOrientation()` da a `camerasRoot`
  un alto **exacto** en píxeles, de forma que `tabContent` (que lleva
  `android:layout_weight="1"`) se queda con todo lo demás:

  | Caso | Fracción de pantalla para las cámaras | Alto de cada lente |
  |---|---|---|
  | Vertical + `Ambas` | **0,37** (≤42 % pedido) | `(cap − 1dp)/2 − 2·margin`, además ≤16:9 natural |
  | Vertical + una sola | 0,50 | ≤16:9 natural de ancho completo |
  | Horizontal | 0,60 | ≤16:9 natural de media pantalla |

  Con las barras (≈15 %) esto deja **≈48–50 % de alto para el panel de pestañas**, que es
  lo que evita que la cruceta PTZ quede cortada. El sobrante de ancho **no** genera bandas
  negras gracias al `CENTER_CROP` del `TextureView` (§4). En pantalla completa,
  `camerasRoot` vuelve a `MATCH_PARENT` y al salir `applyLens()` restaura la altura exacta.
- **Pantalla completa** `[⛶]`: oculta barra, selector, pestañas y la otra lente; aparece
  una superposición con **controles flotantes translúcidos**: salida `[-]` arriba a la
  derecha, etiqueta de lente, cruceta PTZ (4 ejes + centrar con `command=25`) abajo
  a la izquierda, y columna de zoom `+ / 1.0x / −`, sonido, foto y micrófono (PTT) abajo
  a la derecha. Ahí el feedback táctil es el **tinte verde del icono** (`fs_dpad_tint`),
  porque esos botones van sobre el vídeo y no llevan fondo. El overlay **no consume toques**,
  así que pinch-zoom y arrastre siguen funcionando sobre el vídeo.
- **Panel inferior con 4 pestañas**, cada una con raíz **`NestedScrollView`**
  (`fillViewport`, `overScrollMode="never"`, `clipToPadding="false"`) para que el contenido
  nunca quede recortado por la altura reducida del panel:
  1. **PTZ** — **rueda compacta** estilo O-KAM de 152×152 dp: **4 rodamientos de 46 dp**
     (arriba/abajo/izquierda/derecha) + **centro** de 46 dp (`command=25`), **sin
     diagonales** — el firmware de O-KAM Pro no las expone (no hay `command=8`). A su derecha
     van **«Corrección PTZ»** (`btnPtzCorrect`, mismo `command=25`) y los dos **cruceros**
     nativos del cabezal (`btnPtzCruiseV`/`H`, pares Foscam `26/27` y `28/29`). Debajo:
     presets (ir a / guardar) y mapeo invertible X/Y. Las 4 direcciones siguen con
     `ACTION_DOWN`/`ACTION_UP`, y el botón se pinta en **verde `cr_dpad_active`** mientras
     está pulsado (`sel_dpad` + `isPressed` a mano, porque el `OnTouchListener` consume el
     toque y `View.onTouchEvent` no llega a correr).
  2. **Luz** — visión nocturna (4 modos + sub-modo de color), **luz blanca real** con
     **un solo control**: el deslizador de brillo `0..100` (**`0` apaga**, 100 máxima;
     `whiteled` es el brillo *y* el interruptor) y el destello; **sin temporizador** (se
     retiró, §5.11). Cierra la pestaña la **fila de sirena** (rojo translúcido apagada /
     rojo sólido sonando, texto y icono a juego) y el **interruptor de estrobo**, ambos
     pintados con el estado **real** que reporta `cmd=2109&command=2&` y releídos al pulsar
     y al abrir la pestaña. §5.10, §5.11.
  3. **Funciones** — cuadrícula estilo O-KAM «Funciones Comunes»: PTZ, Sonido, Llamada,
     Detección de movimiento, Visión nocturna, Luz blanca, Sirena, Seguimiento de personas,
     Cuadro de detección, Voltear imagen, **Tomar foto** (instantánea del `TextureView`
     guardada en `Pictures/CamRevert`) y Consola P2P. Debajo: detección humana, estrobo,
     sensibilidad y «Aplicar detección».
     **Las casillas se pintan con el estado que devuelve la cámara**, no con un recordatorio
     local: al autorizar la sesión y al abrir la pestaña se piden `cmd=2127&command=1&`,
     `cmd=2126&command=1&mark=123456789&`, `get_camera_params.cgi?` y `get_status.cgi?`, y
     cada una vuelve a leerse tras enviar su orden. `alarm_status` (de `get_status.cgi?`)
     da la **detección de movimiento** y evita el fallo de que «Aplicar detección» la
     apagara sin que se viera. La **única** casilla que no puede reflejar la realidad es
     «Detección humana»: `humanDetection` no tiene lector (§5.12).
  4. **MicroSD** — ficha de tarjeta (total / libre / estado / **barra de ocupación**),
     grabación por programación, marcar archivo, formatear con confirmación, navegador de
     **fechas → horas → clips** (`get_record_file.cgi`), log de
     `get_status.cgi` y reinicio con confirmación.

     El navegador (rediseñado el 2026-10-17): los días van en **lista vertical** (antes era
     un `HorizontalScrollView` que se salía de la pantalla en una sola fila, que era el
     problema reportado), con la fecha en `dd/mm/aaaa · hoy`. Al abrir un día con más de
     `SD_DIRECT_LIMIT = 40` clips se muestra **una fila por hora** (`22:00–22:59 · 3 ·
     11,7 MB`) y al tocar una hora, sus clips. Cada clip es una fila **informativa**
     `HH:MM:SS · serie · tamaño` (`_100` / `_011`), lo más reciente arriba, **sin ▶ y sin
     pulsación** (punto 3, 2026-10-17): el botón no reproducía nada —el flujo del fichero no
     sale por el túnel (§5.13)— y encima dejaba la sesión **sorda** (§5.15). Cierra el panel
     la nota *«Para ver un clip hace falta el reproductor nativo del app oficial…»*.
     El «Atrás» es jerárquico (`‹ Horas` → `‹ Fechas`).
- **Diálogo Llamada** (Funciones → Llamada): modo **manos libres (dúplex)** con AEC+NS y
  `callstatus.cgi`, o modo **radio (push-to-talk)** que silencia los streams mientras se
  habla; indicador de efectos activos. La casilla PTT también se opera manteniendo pulsado
  el micrófono de la pantalla completa.
- **Consola P2P**: panel translúcido con log monoespaciado, «Limpiar» y «Cerrar».
- Dark mode fijo (es una app de monitoreo 24/7).
- Iconos vectoriales propios estilo Lucide (trazo 2dp, extremos redondeados, monocromos).

---

## 9. Verificación

```
$env:JAVA_HOME="C:\Program Files\Java\jdk-17"
.\gradlew.bat assembleDebug --console=plain
```

Salida esperada: `app\build\outputs\apk\debug\app-debug.apk` (~10.9 MB).

Avisos conocidos y no bloqueantes:
- `@OptIn(UnstableApi)` es un no-op: `UnstableApi` no está marcado `@RequiresOptIn`.
- `TabLayout.setSelectedTabIndicatorHeight` deprecado en Material 1.12.

---

## 10. Pendientes de confirmación en el equipo

> **RESUELTO (verificado en equipo, 2026-10-17).** El bloqueo real **no estaba en la
> cascada**: el túnel se monta con el intento `das/p2ptype1-nullips` y el `login()` pasa,
> pero la cámara **se niega a ejecutar cualquier CGI** hasta recibir el preludio de
> sesión `eye4_authentication.cgi` con un token válido de la cuenta EYE4 (§5.14). Con él,
> los 9 comandos PTZ (0..7 y 25) responden `var result="ok"` y el domo se mueve
> físicamente. El análisis de `serverStr`/`PPCS_Initialize` de los puntos 7 y 9 queda como
> historial del diagnóstico.
>
> **Latido `trans_cmd_string.cgi?cmd=2127&command=1&`: NO hace falta.** Medido con una
> sonda de 25 min (lecturas `get_whiteled_value.cgi?` cada 5 min sobre una sesión ya
> autorizada): respondió correctamente a los 5, 10, 15 y 20 minutos **sin ningún tráfico
> de mantenimiento**. No se implementa para no añadir una orden no verificada. Red de
> seguridad: `handleSessionAuth()` reautoriza **una vez** si aparece un `result=-2` sobre
> una sesión ya autorizada (§5.14).
>
> **Latido `trans_cmd_string.cgi?cmd=2131&command=1&DevActiveTime=10&`: NO implementado y
> NUNCA probado** en este firmware. Se menciona a veces como «keepalive»; al no haber
> ningún síntoma de caída de sesión, no se añade tráfico no verificado.

Todo lo marcado como **inferido** en §5, más:

1. **Canal de voz** de `JNIApi.write` (defecto 1) — el binario no lo fija; es ajustable
   desde Configuración y la consola P2P muestra el retorno de cada `write`.
2. **Recepción de audio** en modo dúplex: depende de `ClientCommandListener` (§5.4).
3. Mapeo dirección↔código PTZ y base `64` de los presets (§5.8).
4. ~~Parámetro `param=3` de volteo~~ → **RESUELTO (§5.9)**: el volteo es `param=5`
   (medido con relectura de `flip`); `param=3` no hace nada. Los sub-modos de visión
   nocturna siguen siendo mapeo de la app, pero el modo base ya se verifica leyendo
   `full_color_mode`.
   **Sin confirmar físicamente** (§5.11, §5.12): que la sirena **suene**, que el estrobo
   **parpadee**, que «Alta» detecte antes que «Baja» y qué hace exactamente
   `humanDetection` (no tiene lector).
5. Unidades de `sdtotal`/`sdfree` (§5.13).
6. **Reproducción de clip TF — diagnóstico cerrado, falta la integración (§5.13).**
   Queda descartado todo lo barato: el flujo del fichero **no** sale por el túnel CGI
   (`livestream.cgi?streamid=4` → `result= 0;` y 12 B), las rutas directas de fichero por
   el túnel no contestan, el RTSP no lo publica (`record_fastplay.cgi?ctrl=1` no cambia el
   directo) y el JNI no expone `client_read`. La vía real es el propio reproductor del app
   oficial, **`libOKSMARTPLAY.so` (10,8 MB, ya en el XAPK)**, que exporta una API Java
   completa (`Java_com_veepai_AppPlayerApi_*`: `createPlayer`, `setPlayerSource`, `start`,
   `speed`, `setProgress`/`setProgressCallback`, `screenshot`, y **`startDown`/`saveMP4`/
   `tsToMP4`** para descargar el clip). Trabajo pendiente, en orden:
   (a) sacar la firma de los ~8 *stubs* que hacen falta del desensamblado de
   `libOKSMARTPLAY.so`; (b) escribir la clase Kotlin `com.veepai.AppPlayerApi` con esos
   métodos nativos; (c) render en `SurfaceView` (el nativo dibuja en un `Surface`);
   (d) decidir si se usa para reproducir o para **descargar el clip a MP4** y verlo con
   Media3 (menos riesgo: un solo camino de escritura a fichero).

   **Mientras tanto el ▶ se retiró de la UI** (punto 3, 2026-10-17): no reproducía nada y
   dejaba la sesión sorda (§5.15). Los comandos siguen en `P2PControlManager`, sin llamantes.

7. **Qué combinación de la cascada (§5.5) abre el túnel — causa ya identificada en el
   binario.** Todo lo que sigue está verificado por desensamblado; falta confirmarlo en
   vivo con el log del SDK (§3.6).

   - El crudo que devuelven **todos** los intentos es **−5 =
     `ERROR_PPPP_INVALID_PARAMETER`** (§5.5, tabla de `P2ptErrStrGet`); los modos ≠121
     lo muestran traducido como `rc=4` por la tabla `0x28c00`.
   - El productor es `cs2p2p_PPPP_ConnectByServer` (`0x60168`), que devuelve −5
     **exactamente cuando su 4.º argumento (`ServerString`) es NULL**
     (`0x6025c cbz x20` → `0x60480 mov w0,#-0x5`), con el log
     `ERROR_PPCS_INVALID_PARAMETER (Hint: ServerString==NULL)` (`0x27890`).
     Su 1.er argumento (`TargetID`) NULL daría **−4** (`0x60418`, hint `INVALID_ID
     (Hint: TargetID is not a valid DID string)` @ `0x22ab0`): como sale −5 y no −4,
     **el DID que se le pasa sí es válido**.
   - `client_connect` construye ese `ServerString` en la local `serverStr` (`sp+0x50`)
     y la pasa como `x3` a la llamada de vtable (`0x42770 ldr x3,[sp,#0x50]`; `x5` =
     `cachedIps`, `0x42784`). Y **`serverStr` queda en NULL en nuestra configuración**:
     con `target` no vacío y sin `iface2` (`0x423c8`) se toma `0x423cc` → `0x424f8
     str xzr,[sp,#0x50]`. `iface2 = *(h+0x90)` es NULL porque `client_create` solo lo
     rellena si el `did2` mide **exactamente 17** (`0x41c30`), y la app pasa `""`.
   - Conclusión: **`ServerString` va a NULL en todos los intentos**, y por eso fallan
     todos igual con −5, sea cual sea `mode`/`target`/`cachedIps`. Las dos únicas vías
     del binario para que sea no-NULL son (a) `did2` de 17 chars → `serverStr = target`
     (`0x42428`), o (b) `target` vacío → `serverStr = GetServerString(h)` (`0x424e8`).
     Ambas dependen de que el SDK tenga la configuración de servidores, que es lo que
     aporta `PPCS_Initialize` (§10.9).

   **Una sola captura decide**:
   `adb logcat -d -s flutter:V pcs_shim:V CamRevert:V *:S`. Si aparece
   `-Exit PPCS_ConnectByServer() Ret=ERROR_PPCS_INVALID_PARAMETER (Hint:
   ServerString==NULL)` queda confirmado el diagnóstico; y la línea `PPCS_Initialize
   rc=…` (tag `pcs_shim`) confirma además si el shim llega a ejecutarse y con qué
   código.
8. **Búsqueda LAN por UDP 65530/65531** — `libOKSMARTPPCS.so` contiene
   `_se_lan_hole() … app send lan hole broadcast` (@ `0x27bd0`), `lib_broadcastListen`
   (@ `0x29808`) y el error `R_PPCS_UDP_PORT_BIND_FAILED (Hint: socket create failed,
   errno=%d)` (@ `0x271e0`), y la firma interna es
   `PPCS_Connect(TargetID, bEnableLanSearch, UDP_Port, ServerString)` (@ `0x285a4`). El
   paquete de 21 bytes que manda O-KAM Pro a `<ip>:65530` **no está extraído todavía**;
   mientras, la IP directa se pasa por `cachedIps` (`net_ips`), que es el mecanismo
   documentado del propio SDK para la conexión LAN.
9. **`PPCS_Initialize` y la licencia — CORREGIDO.** El InitString `BJHJAABPPKMB…`
   (108 chars, en `0x25d10`) **no** se inyecta por ningún camino del XAPK, así que el SDK
   nunca se inicializa. Pero la explicación anterior estaba mal en **dos** puntos:

   **Corrección 1 — «cero llamantes» era falso.** La búsqueda `bl <PPCS_Initialize>`
   fallaba porque la llamada va **por PLT**:
   - `PPCS_Initialize` (`0x6460c`) es un salto puro a `cs2p2p_PPPP_Initialize@plt`.
   - **`Initialize_V1` (`0x44b7c`) sí llama a `PPCS_Initialize@plt`** (`bl 0xaed70`) con
     el InitString de `0x26b3a`, y `Initialize_V1` ocupa la entrada `+0x00` de la
     **vtable de interfaz** (`0xb6838`, `R_AARCH64_ABS64`).
   - Lo que sí es cierto es que **nadie invoca esa entrada**: `client_create`
     (`0x41bac`) llama a `[iface1+0x10]` = `NetworkDetect_V1` (`0x450a4`), **no** a
     `Initialize_V1`, y no existe `.init_array`. Tampoco la exponen los 20 JNI de
     `JNIApi` (`Java_com_vstarcam_JNIApi_init`, `0x3f6c4`, solo registra los 4
     listeners con `NewGlobalRef`/`GetMethodID`).

   **Corrección 2 — −5 NO es `NOT_INITIALIZED`.** `P2ptErrStrGet` (`0x7da88`) prueba que
   `ERROR_PPPP_NOT_INITIALIZED` es el **−1** (`0x29a78`) y que **−5 =
   `ERROR_PPPP_INVALID_PARAMETER`** (`0x29b38`). El `result:-5` del equipo no es «SDK sin
   inicializar» sino «parámetro inválido»; el parámetro que el binario marca como
   inválido en esta ruta es el `ServerString` NULL de `cs2p2p_PPPP_ConnectByServer`
   (§10.7).

   `cs2p2p_PPPP_Initialize` (`0x4f9f8`) devuelve **−2** si ya estaba inicializado
   (`ldrb w8,[x20]` → `mov w0,#-0x2`) y **−5** si `InitString == NULL`
   (`4faa0 cbz x22` → `4fcb8 mov w0,#-0x5`). Acepta cadena plana (nuestra) o JSON
   `{"InitString":"%s","0x7X_Timeout":20,"SkipP2P":1}` (`0x2520d`), porque compara
   `str[0]`/`str[1]` contra `0x7b` (`'{'`).

   Implementación: `app/src/main/cpp/ppcs_shim.c` → `libpcs_shim.so` (4.8 KB), que
   hace `dlopen("libOKSMARTPPCS.so")` + `dlsym("PPCS_Initialize")` y le pasa el
   InitString copiado literal. Se enlaza desde Kotlin con
   `com.lgsusdev.camrevert.p2p.PpcsShim` (CMake 3.22.1, `-DANDROID_STL=none`) y se
   llama **una sola vez** en `P2PControlManager.init()`, tras `JNIApi.init()`.
   `-2` («ya inicializado») se cuenta como éxito.

   **Estado: sin verificar en equipo.** No hay ninguna captura que muestre la línea
   `PPCS_Initialize: rc=…` (la del shim va a logcat con tag propio, no a la consola
   in-app), así que no se sabe si se ejecuta ni qué devuelve. Y **inicializar no basta
   por sí solo** para esta ruta: aunque `PPCS_Initialize` deje puesta la config de
   servidores, `client_connect` seguirá pasando `serverStr = NULL` mientras `iface2` sea
   NULL (§10.7). Hacen falta las dos cosas.

10. **`get_params.cgi?` filtra secretos en claro — pendiente de enmascarar.** Su respuesta
    (24 KB, y la consola P2P la vuelca tal cual) trae `WebPwd="…"` (la contraseña web de la
    cámara), `wifi_ssid="…"` y `wifi_wpa_psk="…"` (la clave del WiFi). Medido el
    2026-10-17 al buscar el lector de `alarm_motion_armed`. La consecuencia práctica ya
    está resuelta —CamRevert **no** pide `get_params.cgi?` para el estado: usa
    `alarm_status` de `get_status.cgi?` (§5.12)—, pero si algún día se vuelve a pedir hay
    que **enmascarar** esos campos antes de registrarlos. Los valores no aparecen en
    `get_status.cgi?` ni en `get_camera_params.cgi?`.
11. **Túnel sordo — origen sin identificar y recuperación limitada a una por ejecución
    (§5.15).** Queda documentado y medido:
    - **No se sabe qué lo dispara exactamente.** Se reprodujo tres veces (23:43, 23:55 y
      00:03 del 2026-10-17/18), entre 30 s y 2 min después de mandar
      `record_fastplay.cgi?ctrl=1` + `livestream.cgi?streamid=4`, y **no** se reprodujo con
      esas mismas órdenes en otras diez pruebas. Con el ▶ fuera de la UI ya no hay camino
      conocido que lo provoque.
    - **Cerrar el handle sordo aborta la app** (`SIGABRT`, mutex destruido: §5.15). Por eso
      la recuperación se limita a **un abandono por ejecución**; al segundo episodio se avisa
      al usuario en vez de insistir (se midió que con dos abandonos la cascada ya no
      reconstruye el túnel, porque cada handle abandonado deja su sesión viva en el equipo).
    - **Alternativa no implementada, por si se prefiere**: reconexión por **auto-reinicio del
      proceso** (`exitProcess(0)` + relanzar la `MainActivity` con un `AlarmManager` a ~1 s,
      con enfriamiento en prefs para no entrar en bucle). Es el remedio que ya se usaba a
      mano y **no toca el handle**, así que no puede provocar el aborto; a cambio, la app
      parpadea y tarda lo que tarda un arranque en frío.
