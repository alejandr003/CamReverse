# Cómo funciona CamRevert (y cómo se averiguó)

Este documento explica **qué hace la app por dentro** y **cómo se descubrió**. La evidencia
cruda (direcciones de memoria, desensamblados, tablas de errores) está en
[`../AGENTS.md`](../AGENTS.md); aquí está lo entendible.

---

## 1. Vista general

La app hace **dos cosas independientes** con la misma cámara:

```
                ┌─────────────────────────────────────────────┐
   VÍDEO  ──►   │  RTSP directo (puerto 10555 / 10554)        │  ← no pasa por el SDK P2P
                └─────────────────────────────────────────────┘

                ┌─────────────────────────────────────────────┐
   CONTROL ──►  │  Túnel P2P (libOKSMARTPPCS.so)              │  ← los CGI van DENTRO del túnel
                │  + autorización de sesión (token Eye4)      │
                └─────────────────────────────────────────────┘

   AUTORIZACIÓN ► api.eye4.cn (HTTPS)  ← única salida a Internet, solo al conectar
```

**Por qué así:** el vídeo por RTSP es mucho más barato (decode por hardware, cero CPU del
SDK) y el control **tiene que** ir por el túnel P2P porque es la única vía que el firmware
atiende de verdad (ver §5).

---

## 2. El SDK nativo: por qué reutilizamos binarios del fabricante

`libOKSMARTPPCS.so` (728 KB) implementa el **protocolo P2P propietario** ("kaven") de
VStarcam: rendezvous, relay, NAT traversal y el *framing* CS2. Reimplementarlo es un
proyecto en sí mismo, así que la app **reutiliza el `.so` original, sin modificar**:

- `app/src/main/jniLibs/arm64-v8a/libOKSMARTPPCS.so` (del XAPK oficial)
- `app/src/main/jniLibs/arm64-v8a/libvp_log.so` (su logger, dependencia `NEEDED`)

Encima se pone una fachada Java con **los nombres exactos** que el binario espera
(`com.vstarcam.JNIApi` con sus 20 métodos nativos y `com.veepai.vp_log.VPLogApi`), porque el
enlace JNI resuelve **por símbolo** (`Java_com_vstarcam_JNIApi_<método>`): renombrar
cualquiera de esos métodos rompe el enlace.

### 2.1 El detalle que bloqueaba todo: `PPCS_Initialize`

En el XAPK, **nadie llama a `PPCS_Initialize`**: ni el `.init_array` (no existe) ni ninguno
de los 20 JNI. Sin inicializar, el SDK devuelve `-5` y el túnel nunca se monta.

CamRevert lo resuelve con un **shim propio de C** (`app/src/main/cpp/ppcs_shim.c` → 4.8 KB)
que hace `dlopen`/`dlsym` sobre `PPCS_Initialize` y le pasa la cadena de inicialización.
Con eso el `connect` dejó de devolver `-5`.

### 2.2 Ver dentro del SDK: el log nativo

El SDK escribe su log con `vp_printf` (nivel 4) y `libvp_log.so` solo lo entrega al callback
del *listener* si el nivel coincide. CamRevert registra ese *listener* y **vuelca el log
interno del `.so` a la consola de la app**. Sin eso, un `connect()` fallido solo devuelve un
código normalizado y el motivo real es invisible.

> Método alternativo que también funcionó durante la investigación: sustituir
> `libvp_log.so` en la app oficial por un *shim* que escribe a fichero. De ahí salieron las
> capturas de `run2`/`run3` que revelaron el preludio de autorización.

---

## 3. El túnel P2P: cómo se monta

Secuencia obligatoria (flags internos del SDK):

```
JNIApi.create(did, did2) → clientSetVuid(handle, vuid)
   → connect(handle, mode, target, p2pType, cachedIps)
   → writeCgi(sondeo)          ← desambigua el código 4
   → login(handle, user, CAMPASSWORD)
   → ★ eye4_authentication.cgi (autorización de sesión) ★
```

Tres cosas que costaron entender (todas verificadas en el binario):

1. **`target` es el TargetID (DID/VUID), no una IP.** Pasar una IP produce
   `ERROR_PPCS_INVALID_ID`. La IP va en el 5.º argumento, `cachedIps`.
2. **El 2.º argumento de `create` no es una licencia: es un *segundo* DID**, y el SDK solo
   lo usa si mide **exactamente 17 caracteres**. Por eso hay que pasar el **ID P2P real** y
   no el VUID de 13.
3. **El código `4` es ambiguo**: en el SDK significa tanto "sesión 4" como "error". Se
   desambigua con un `writeCgi` de sondeo, que devuelve `false` inmediatamente si el túnel
   no está montado.

Como no hay documentación de qué combinación exacta abre el túnel en cada firmware,
`connect()` prueba una **cascada** de combinaciones, cada una con **su propio handle**, y en
la consola se ve cada intento con su código y su etiqueta (`[das/p2ptype1-nullips] connect:
rc=4 en 1 ms — …`). En el equipo probado, la que abre el túnel es
**`das/p2ptype1-nullips`**.

---

## 4. Control: los CGI viajan **dentro** del túnel

Detalle clave que explica mil horas perdidas: **la app no manda HTTP**. `client_write_cgi`
del `.so` **envuelve** la cadena internamente:

```c
snprintf(buf, len+1024, "GET /%sloginuse=%s&loginpas=%s&user=%s&pwd=%s&", cgi, …);
```

De ahí tres reglas de oro del proyecto:

1. Las cadenas **no** llevan `GET /` ni `loginuse=`/`loginpas=` (los añade el nativo).
2. Deben **terminar en `&` o `?`** (el nativo escribe `loginuse=` justo detrás).
3. El 3.er argumento de `writeCgi` es un **timeout**, no la longitud (pasar la longitud
   producía un timeout de ~40 ms y los comandos se perdían).

Por eso probar los CGI con `curl` contra la cámara **no sirve** para el control: el servidor
HTTP embebido es un residuo del SDK del SoC y responde `200 OK` sin despachar la orden a los
motores.

---

## 5. La autorización de sesión: `eye4_authentication.cgi`

**Ésta era la pieza que faltaba** y la que resolvió el problema (ver
[`HALLAZGOS-PTZ.md`](HALLAZGOS-PTZ.md)).

Aunque el túnel esté montado y `login()` devuelva `true`, la cámara **se niega a ejecutar
cualquier orden** hasta recibir un token firmado de la cuenta Eye4:

```
eye4_authentication.cgi?loginAccount=<userid>&loginToken=<token>&
      → result= 0;  eye4_auth=1;
```

- **Sin** el preludio: `result= 0;  result=-2;  ` para todo (la única excepción es
  `get_status.cgi`, que siempre contesta — por eso el sondeo parecía indicar que todo iba
  bien).
- **Con** el preludio: `var result="ok"` y el domo obedece.

El token se pide a `api.eye4.cn` en cada conexión (`/user/summary` → `/login/token`) y **no
se guarda**. Detalles e implementación en [`CONFIGURACION.md`](CONFIGURACION.md) y
`p2p/Eye4Auth.kt`.

---

## 6. Tabla de comandos

Todo va por `decoder_control.cgi` / `trans_cmd_string.cgi` / etc., con el envoltorio del
punto 4. Estado = lo que devolvió el **hardware real**.

| Acción | Cadena | Estado |
|---|---|---|
| Arriba / parar | `decoder_control.cgi?command=0&onestep=0&` / `…=1…` | ✅ verificado a la vista |
| Abajo / parar | `…command=2…` / `…=3…` | ✅ |
| Izquierda / parar | `…command=4…` / `…=5…` | ✅ |
| Derecha / parar | `…command=6…` / `…=7…` | ✅ |
| Centrar cabezal | `…command=25&onestep=0&channel=0&` | ✅ |
| Crucero vertical on/off | `…command=26…` / `…=27…` | ✅ aceptado y ejecutado |
| Crucero horizontal on/off | `…command=28…` / `…=29…` | ✅ |
| Guardar preset N | `set_sensor_preset.cgi?sensorid=255&presetid=N&` | ✅ aceptado |
| Ir al preset N | `…command=64+N&onestep=0&` | ✅ aceptado (por confirmar a la vista) |
| Consultar preset | `get_sensor_preset.cgi?` | ❌ no existe en este firmware (`result=-1`) |
| Luz blanca: brillo **y** on/off | `set_whiteled_value.cgi?whiteled=0..100&` — **`0` = apagada**, `100` = máxima | ✅ verificado a la vista |
| Estado de luz | `get_whiteled_value.cgi?` → `var whiteled=0..100; var light=0\|1;` | ✅ devuelve el valor exacto puesto |
| Brillo «PWM» | `display_pwm_ctrl.cgi?common=1&value=1..100&` | ❌ **no existe** en este firmware (0 bytes de respuesta) |
| `cmd=2109&command=0&light=0\|1&` (el del oficial) | — | ➖ **redundante**: `whiteled` ya enciende y apaga. Sus `lightStatus`/`sirenStatus` son **eco**, no estado |
| Sirena / estrobo | `trans_cmd_string.cgi?cmd=2109&command=0&siren=1&` / `…alarmLed=1&` | ✅ |
| Visión nocturna | `camera_control.cgi?param=37&value=0&fullcolormode=X&…` | ✅ |
| Detección humana / seguimiento | `trans_cmd_string.cgi?cmd=2106&command=4&humanDetection=1&`, `cmd=4500&command=11&track=1&` | ✅ |
| Estado general y MicroSD | `get_status.cgi?`, `get_record.cgi?`, `set_recordsch.cgi?…` | ✅ |
| Latido (el que usa el oficial) | `trans_cmd_string.cgi?cmd=2127&command=1&` | **No hace falta**: en la prueba de 25 min la sesión respondió a los 5/10/15/20 min **sin ningún latido**. Se dejó fuera para no introducir una orden no verificada. |

> **Ciclo de vida de la sesión (medido)**: pasada la autorización, la sesión del equipo
> probado siguió aceptando órdenes **≈20 minutos** (última lectura correcta a los 19 min 57 s
> del `connect`) sin tráfico de mantenimiento. La app aun así se protege: si algún día un
> `result=-2` aparece sobre una sesión ya autorizada, **reautoriza sola una vez** (token nuevo).

> No existe `command=8` y **no hay diagonales**: el firmware expone 4 ejes, cada uno con su
> propia parada, más centrado y cruceros.

---

## 7. El vídeo: RTSP

| Lente | HD (principal) | SD (sub) |
|---|---|---|
| Fija | `rtsp://USER:CAMPASSWORD@IP:10555/tcp/av0_0` | `…av0_1` |
| PTZ | `…@IP:10554/tcp/av0_0` | `…av0_1` |

Decisiones de diseño (todas por pruebas en equipo):

- **RTP sobre UDP primero, con respaldo TCP.** En Media3, el camino UDP tiene *fallback*
  automático a TCP; el camino TCP interleaved **no tiene respaldo**. La app cambia sola si
  acumula 3 fallos seguidos y lo deja guardado.
- **Timeout de socket a 30 s.** `RtspMediaSource.setTimeoutMs` es el *read-timeout* del
  socket de datos: con los 8 s por defecto, cualquier pausa de la cámara WiFi producía un
  parpadeo constante.
- **`LoadControl` holgado** en el `ExoPlayer.Builder`, porque los valores por defecto están
  calibrados para ficheros y con un bitrate irregular provocan *rebuffering*.
- **`TextureView` + `CENTER_CROP`** en todas las versiones (SurfaceView ignora `scaleX` antes
  de Android 10 y da artefactos de z-order con los controles superpuestos).
- **Sin sondas TCP de salud**: medir con sockets propios a 10554/10555 abría conexiones que
  el servidor RTSP acababa cerrando y contribuía a los cortes. El HUD mide de forma pasiva.
- **Respaldo de decodificador**: el `ExoPlayer` se construye con
  `DefaultRenderersFactory.setEnableDecoderFallback(true)`, de modo que si el decodificador
  de hardware rechaza el perfil o la resolución (el main es de 2304×1296) Media3 cae al
  decodificador por **software**. Además, en Configuración se puede **forzar** el decoder
  por software (`MediaCodecSelector.PREFER_SOFTWARE`) para equipos con hardware muy justo.

---

## 8. Cómo se averiguó todo esto (método)

El trabajo se organizó con la *skill* de ingeniería inversa
**[`morluto/rea`](https://github.com/morluto/rea)**: **primero la evidencia, después la
hipótesis, y al final la prueba en el equipo**. Cada afirmación de `AGENTS.md` lleva detrás
una dirección de memoria, un log o una captura.

### 8.1 Qué usamos

| Herramienta / fuente | Para qué |
|---|---|
| **[`morluto/rea`](https://github.com/morluto/rea)** (skill REA) | Método: desempacar el artefacto, localizar los símbolos, leer el desensamblado, formular hipótesis y verificarlas. |
| **[`oleandor/okam-ha-native`](https://github.com/oleandor/okam-ha-native)** | Cliente Python del protocolo CS2 y del flujo de cuentas de Eye4: de ahí salió **cómo se pide el `userid` y el `loginToken`** (la clave del arreglo) y el *framing* CS2 para sondas de laboratorio. |
| **JADX 1.5.6** | Descompilar los 5 DEX del APK a Java: inventario de CGI, puertos, constantes. |
| **llvm-objdump** | Desensamblado completo de `libOKSMARTPPCS.so` (`ppcs_disasm.txt`): así se leyeron `client_create`, `client_connect`, `client_write_cgi`, `client_login`, la tabla de errores PPCS y las validaciones de `serverParam`. |
| **Shim propio de `libvp_log.so`** | Sustituido en la app oficial para **capturar su log interno** a fichero. De ahí salieron las capturas del preludio de autorización. |
| **pcap (Wireshark)** | Conversación LAN del oficial contra la cámara, para ver el *framing* CS2 en claro. |
| **adb + logcat + un inyector de CGI** | Sondas en vivo contra la cámara sin recompilar (un *BroadcastReceiver* de debug que acepta un CGI en base64). |
| **Asistencia de IA** | Leer desensamblados largos, cruzar hipótesis, generar los clientes de sonda y no perder el hilo entre bibliotecas. |

### 8.2 Qué se descartó (y por qué)

- **Frida / inyección de código**: descartado por decisión del proyecto.
- **`LD_PRELOAD` / `wrap.<pkg>`**: rompe el puente de traducción ARM del emulador.
  (`has unexpected e_machine: 183`).
- **Sustituir `libOKSMARTPPCS.so`**: inviable (1510 símbolos exportados).
- **Definir nosotros los símbolos `Java_com_vstarcam_JNIApi_*`**: el `.so` define los mismos
  y gana en su propio `dlsym`.
- **Cliente P2P 100% Python** (`okam-ha-native`) para esta cámara: el esquema CS2 antiguo
  enrutaba a **otro dispositivo**. Se quedó como laboratorio de protocolo, no como vía de
  control.

### 8.3 Cómo se probó

1. **Captura del oficial**: interceptado su preludio y sus CGI, incluida la orden exacta del
   joystick (`decoder_control.cgi?command=N&onestep=0&`).
2. **Sondeo sistemático en el equipo**: un inyector de CGI por *broadcast* permitió probar
   decenas de variantes contra la cámara real sin recompilar, viendo la respuesta cruda en
   logcat.
3. **Confirmación a la vista**: los movimientos se verificaron **viendo la cámara** (4 ejes,
   paradas y centrado), no solo por la respuesta `ok`.

---

## 9. Qué se logró (y con qué evidencia)

| Logro | Evidencia |
|---|---|
| **Entender el protocolo de control** del fabricante | Desensamblado de `libOKSMARTPPCS.so` con direcciones y nombres de símbolos. |
| **Montar el túnel P2P desde nuestra app** | `TÚNEL CONFIRMADO (connect + writeCgi + login)` en el log del SDK. |
| **Descubrir la autorización de sesión** | Captura del log del oficial con `eye4_authentication.cgi` + prueba: sin ella `result=-2`, con ella `var result="ok"`. |
| **Descubrir el flujo de cuentas de Eye4** | Reproducido con `POST /user/summary` + `GET /login/token` (contrastado con `okam-ha-native`). |
| **Mover el domo de verdad** | Los 9 comandos PTZ aceptados y movimiento confirmado a la vista. |
| **Documentar todo** | `AGENTS.md` (evidencia) + estos documentos (explicación). |

---

## 10. Limitaciones conocidas

- **`client_read` no está expuesto al JNI**, así que el stream de vídeo y los clips de la
  MicroSD **no** se pueden recibir por el túnel: el vídeo va siempre por RTSP y la
  reproducción de clips por el túnel queda fuera de alcance.
- **Recepción de audio** del intercomunicador: depende de que la cámara lo mande por el
  canal de comandos (`ClientCommandListener`). El **envío** sí funciona; la recepción está
  sin confirmar.
- **Canal de audio** (por defecto 1) no está determinado en el binario: es ajustable.
- La app **no descubre** sola el ID P2P ni la dirección `DAS-`: hay que copiarlos (ver
  [`CONFIGURACION.md`](CONFIGURACION.md)).
- Parámetros de volteo, sub-modos de visión nocturna y unidades de `sdtotal`/`sdfree` siguen
  siendo **inferidos**.
- Solo **arm64-v8a** (no existen binarios de 32 bits en el XAPK original).

---

## 11. Créditos

- **[`morluto/rea`](https://github.com/morluto/rea)** — *skill* de ingeniería inversa con la
  que se organizó todo el trabajo. Sin su disciplina de "evidencia primero" esto habría sido
  una sucesión de conjeturas.
- **[`oleandor/okam-ha-native`](https://github.com/oleandor/okam-ha-native)** — cliente
  Python del protocolo CS2 y del flujo de cuentas Eye4: la pista que llevó a
  `loginAccount`/`loginToken`, y las sondas de laboratorio del *framing*.
- **VStarcam / Veepai**: autores del SDK que la app reutiliza (y sin el cual esto no
  existiría). Ver la nota legal en [`../LICENSE`](../LICENSE).
