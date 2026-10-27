# COMANDOS — Tabla maestra de la cámara O-KAM Pro / VStarcam (T23-N)

> 🌐 **Español** · [English](en/COMMANDS.md)

Documento vivo. Toda orden que CamRevert puede enviar, con su URL, qué hace y **la evidencia**.
Se actualiza cada vez que se descubre o se verifica algo.

Fuentes de evidencia:

| Código | Qué es |
|---|---|
| **[VIVO]** | Verificado contra la cámara real desde CamRevert (respuesta copiada tal cual) |
| **[OFICIAL]** | Plantilla extraída del binario oficial `libapp.so` de O-KAM (`C:\cam-reverse\endpoints.txt`) |
| **[CAPTURA]** | Observado en el tráfico del app oficial (shim `vphook.txt`, 6.142 CGIs) |
| **[NO EXISTE]** | Probado y la cámara responde `var result=-1` (CGI ausente en este firmware) |
| **[DESCARTADO]** | Aceptado por la cámara (`result=0`) pero sin efecto observable → no se usa |

---

## 1. Convenciones (importantes)

### 1.0 Preludio de autenticación — OBLIGATORIO

Antes de cualquier CGI hay que autenticar la sesión:

```
eye4_authentication.cgi?loginAccount=<uid>&loginToken=<token>&
```

Sin esto, **todas** las órdenes devuelven `result= 0;  result=-2;  ` (rechazado).

- El `loginToken` va **percent-encoded** en el app oficial (`%2F` `/`, `%2B` `+`, `%3D%3D` `==`).
- El token se obtiene con `POST https://api.eye4.cn/user/summary` (`name=<email>&oemid=VSTC`) y luego
  `GET https://api.eye4.cn/login/token?userid=<id>&password=<md5(key)>&type=PC` (header `client_version: 10.0.1`).
- Las credenciales viven **solo** en `secrets.properties` (gitignored). Nunca en el repo.

### 1.1 Todos los `Cgi.*` terminan en `&` o `?`

Excepciones: `GET /` y las credenciales. El `&` final no es cosmético: el firmware lo espera.

### 1.2 Vocabulario de respuestas

| Respuesta | Significado |
|---|---|
| `result= 0;  result=-2;  ` | **Rechazado** (sin auth, o sesión perdida, o dos clientes peleando) |
| `result= 0;  var result="ok";  ` | **Aceptado** |
| `var result=-1` | **Ese CGI no existe** en este firmware |
| `result= 0;  var cmd=<N>;  …` | Respuesta normal: el eco incluye los campos enviados + el estado real |
| `result= 0;  ` a secas | Aceptado, sin payload (p. ej. arrancar un stream) |

> **Matiz medido (2026-10-17)**: el «`result= 0;` a secas = rechazado» vale para
> `trans_cmd_string.cgi` y `set_alarm.cgi`, **no** para `camera_control.cgi`: ese contesta
> `result= 0;` **siempre**, incluso cuando aplica el cambio (`param=5&value=1&` → `flip=1`).
> La única forma de saber si `camera_control.cgi` hizo algo es **releer**
> `get_camera_params.cgi?`.

### 1.3 Regla de oro: **un solo cliente**

Dos clientes a la vez (móvil + emulador) **se pelean** por la cámara y producen lecturas falsas
(p. ej. `light=0` cuando en realidad está encendida). Lección documentada en `AGENTS.md`.

---

## 2. VERIFICADOS EN VIVO [VIVO]

Estos están probados contra la cámara y con la respuesta real copiada. **Son los que CamRevert debe usar.**

### 2.1 Luz blanca (LED) — el único control real es el brillo

| Comando | Qué hace | Respuesta real |
|---|---|---|
| `set_whiteled_value.cgi?whiteled=0&` | LED **apagado** | `var whiteled=0;` |
| `set_whiteled_value.cgi?whiteled=100&` | LED a tope | `var whiteled=100;` |
| `set_whiteled_value.cgi?whiteled=<0..100>&` | **Intensidad** (porcentaje) | `var whiteled=<n>;` |
| `get_whiteled_value.cgi?` | Leer el brillo actual | `var whiteled=<n>;` |

- `whiteled` es **el porcentaje** y es el único control necesario. No hace falta `light=1` ni `cmd=2109`.
- **[DESCARTADO]** `set_whiteled_value.cgi?mode=<0|1>&` → aceptado, sin efecto.
- **[NO EXISTE]** `display_pwm_ctrl.cgi` (sí está en `libapp.so`, pero la cámara no responde).
- **[DESCARTADO]** `set_whiteled_value.cgi?ledtimes=<n>&` → no verificado, ya no se envía.

### 2.2 Estrobo / "Luz intermitente de alarma" — **SÍ EXISTE**

| Comando | Qué hace | Respuesta real |
|---|---|---|
| `trans_cmd_string.cgi?cmd=2109&command=0&alarmLed=1&` | Estrobo **ON** | `var sirenStatus=0;  var alarmLed=1;  var alarmLedStatus=1;` |
| `trans_cmd_string.cgi?cmd=2109&command=0&alarmLed=0&` | Estrobo **OFF** | `var alarmLed=0;  var alarmLedStatus=0;` |
| `trans_cmd_string.cgi?cmd=2109&command=2&` | **Leer estado** | `var lightStatus=1;  var sirenStatus=0;  var alarmLedStatus=<0\|1>;` |
| `trans_cmd_string.cgi?cmd=2108&command=1&alarmLedMode=2&` | Modo del estrobo [OFICIAL] | por confirmar |

[VIVO] El `command=2` devuelve el estado de los **tres** actuadores de una vez
(`lightStatus`, `sirenStatus`, `alarmLedStatus`) → sirve para sincronizar la UI.

### 2.3 Sirena — **SÍ EXISTE**

| Comando | Qué hace | Respuesta real |
|---|---|---|
| `trans_cmd_string.cgi?cmd=2109&command=0&siren=1&` | Sirena **ON** | `var siren=1;  var sirenStatus=1;` |
| `trans_cmd_string.cgi?cmd=2109&command=0&siren=0&` | Sirena **OFF** | `var siren=0;  var sirenStatus=0;` |
| `trans_cmd_string.cgi?cmd=2108&command=1&sirenMode=<n>&` | Modo de sirena [OFICIAL] | por confirmar |

> **Cómo lo usa CamRevert (2026-10-17).** Con `command=0` la respuesta **ecoa la
> petición** (`siren`/`alarmLed` son lo que se acaba de mandar), así que no sirve para
> pintar la UI: para eso se manda aparte `cmd=2109&command=2&`, que **es** estado real
> (§2.2). La pestaña Luz manda la orden y **relee**; la fila de la sirena cambia de color
> (rojo translúcido = apagada, rojo sólido = sonando) y de texto según lo que contesta el
> equipo, y el interruptor del estrobo se sincroniza igual.
>
> **Falta la comprobación física** (oír la sirena, ver parpadear el estrobo) delante de la
> cámara: el estado se lee y se escribe bien, pero que el altavoz suene depende del equipo.

### 2.4 Detección: sensibilidad, encuadre humanoide y seguimiento

> **Auditoría en el equipo (2026-10-17): `cmd=4500&command=11&…` es un NO-OP en este
> firmware.** Los dos mandos que usa el panel de IA del app oficial para sensibilidad y
> seguimiento **no hacen nada**: responden sólo `result= 0;  var cmd=4500;` y la relectura
> deja el valor anterior. Los mandos reales son `cmd=2126` y `cmd=2127`.
>
> | Enviado | Respuesta | Relectura |
> |---|---|---|
> | `cmd=4500&command=11&sensitivity=1&` | `result= 0;  var cmd=4500;` | `sensitive=3` (**sin cambio**) |
> | `cmd=4500&command=11&track=1&` | `result= 0;  var cmd=4500;` | `enable=0` (**sin cambio**) |
> | `cmd=2126&command=0&sensitive=1&mark=123456789&` | `var sensitive=1;` | `sensitive=1` ✅ |
> | `cmd=2127&command=0&enable=1&` | `var enable=1;` | `enable=1` ✅ |

| Comando | Qué hace | Respuesta real |
|---|---|---|
| `trans_cmd_string.cgi?cmd=2126&command=0&sensitive=<1\|2\|3>&mark=123456789&` | Sensibilidad | `var sensitive=<n>;  var bHumanoidFrame=<0\|1>;` |
| `trans_cmd_string.cgi?cmd=2126&command=0&bHumanoidFrame=<0\|1>&` | Encuadre humanoide | `var sensitive=<n>;  var bHumanoidFrame=1;` |
| `trans_cmd_string.cgi?cmd=2126&command=0&humanoid_zoom=<n>&` | Zoom humanoide [OFICIAL] | por confirmar |
| `trans_cmd_string.cgi?cmd=2126&command=1&mark=123456789&` | **Leer** detección | `var sensitive=<n>;  var bHumanoidFrame=<0\|1>;` |
| `trans_cmd_string.cgi?cmd=2127&command=0&enable=<0\|1>&` | Seguimiento humano | `var enable=1;` |
| `trans_cmd_string.cgi?cmd=2127&command=1&` | **Leer** seguimiento | `var enable=<0\|1>;  var track_status=<n>;` |
| `trans_cmd_string.cgi?cmd=4500&command=11&track=<0\|1>&` | **[DESCARTADO]** seguimiento por la vía del oficial | responde sólo `var cmd=4500;` — **no cambia nada** |
| `trans_cmd_string.cgi?cmd=4500&command=11&sensitivity=<n>&` | **[DESCARTADO]** sensibilidad por la vía del oficial | responde sólo `var cmd=4500;` — **no cambia nada** |

**¡Ojo, escala INVERSA!**

| UI | `sensitive` |
|---|---|
| Alta | **1** |
| Media | **2** |
| Baja | **3** |

El `mark=123456789` es obligatorio en el setter. La escala inversa es la que usa CamRevert;
la comprobación física (que «Alta» dispare antes que «Baja») queda pendiente en el equipo.

**Los tres lectores de estado real** (así la UI no miente si la orden no llegó):

| Qué | Se lee de |
|---|---|
| Encendido/apagado y siguiendo ahora | `cmd=2127&command=1&` → `enable`, `track_status` |
| Sensibilidad y cuadro de detección | `cmd=2126&command=1&mark=123456789&` → `sensitive`, `bHumanoidFrame` |
| Volteo de imagen | `get_camera_params.cgi?` → `flip` |

### 2.5 Zonas / rejilla de detección

| Comando | Qué hace | Respuesta real |
|---|---|---|
| `trans_cmd_string.cgi?cmd=2123&command=3&sensor=0&` | **Leer** 18 zonas | `var pd_reign0..17=4194303;` |
| `trans_cmd_string.cgi?cmd=2123&command=3&sensor=1&` | Leer 2º sensor | igual |
| `trans_cmd_string.cgi?cmd=2123&command=<N>&` | **Escribir** zonas [OFICIAL] | pendiente de confirmar `N` y los `pd_reignX` |

- `sensor` parece ignorarse en esta cámara (mismos valores con 0 que con 1).
- El valor `4194303` = `0b111111111111111111111` = **21 bits** → la rejilla tiene 21 celdas por sensor.
- La app oficial **no envía la escritura por `writeCgi`** (ver §6, canal binario) → a CamRevert le toca
  probar el setter directamente.

### 2.6 Ocultar el LED de estado

| Comando | Qué hace | Respuesta real |
|---|---|---|
| `trans_cmd_string.cgi?cmd=2125&command=0&hide_led_disable=<0\|1>&` | Ocultar LED de estado | `var hide_led_disable=1;` |
| `trans_cmd_string.cgi?cmd=2125&command=1&` | Leer | `var hide_led_disable=<0\|1>;` |

**[VIVO] Verificado en el equipo**: `hide_led_disable=1` → relectura `1`; vuelta a `0` →
relectura `0`. Funciona, pero **CamRevert no lo expone en la UI** (no está en la lista de
Funciones del usuario).

### 2.7 Movimiento (alarma de movimiento)

| Comando | Qué hace | Respuesta real |
|---|---|---|
| `set_alarm.cgi?enable_alarm_audio=0&motion_armed=<0\|1>&` | Armar/desarmar detección | `result= 0;  var result="ok";` |
| `set_alarm.cgi?` | **No lee nada**: es sólo escritura, contesta `var result="ok"` y nada más | — |
| `get_params.cgi?` → `alarm_motion_armed` / `alarm_motion_sensitivity` | Leer | `var alarm_motion_armed=1;  var alarm_motion_sensitivity=5;` |
| **`get_status.cgi?` → `alarm_status`** | **Leer sin filtrar secretos** ✅ | `var alarm_status=<0\|1>;` |

**[VIVO] El lector bueno es `alarm_status`.** Medido con los dos valores:

| `set_alarm.cgi?motion_armed=` | `get_status.cgi?` → `alarm_status` | `get_params.cgi?` → `alarm_motion_armed` |
|---|---|---|
| `0` | **`0`** | `0` |
| `1` | **`1`** | `1` |

Por eso CamRevert usa `alarm_status` y **no** `get_params.cgi?`: ese último vuelca
`WebPwd` y `wifi_wpa_psk` en claro (§5) y su respuesta son 24 KB, mientras que
`get_status.cgi?` ya se pide para la tarjeta y no trae secretos.

`enable_alarm_audio=0` en la práctica no cambia nada (la grabación de audio va por
`set_recordsch.cgi?record_audio=`).

Campos relacionados en `get_params.cgi?`:
`alarm_motion_armed`, `alarm_motion_sensitivity`, `alarm_snapshot`, `alarm_record`, `disable_audio`.

### 2.8 PTZ (9 comandos verificados en dispositivo)

| Comando | Qué hace |
|---|---|
| `decoder_control.cgi?command=0&onestep=0&` | Arriba |
| `decoder_control.cgi?command=1&onestep=0&` | Parar |
| `decoder_control.cgi?command=2&onestep=0&` | Abajo |
| `decoder_control.cgi?command=3&onestep=0&` | Parar |
| `decoder_control.cgi?command=4&onestep=0&` | Izquierda |
| `decoder_control.cgi?command=5&onestep=0&` | Parar |
| `decoder_control.cgi?command=6&onestep=0&` | Derecha |
| `decoder_control.cgi?command=7&onestep=0&` | Parar |
| `decoder_control.cgi?command=25&onestep=0&channel=0&` | **Centrar** (volver a posición) |

No hay diagonales ni `command=8`: el firmware expone 4 ejes, cada uno con su propia parada.
Cruceros: `command=26/27` (vertical on/off) y `command=28/29` (horizontal on/off). Presets:
guardar con `set_sensor_preset.cgi?sensorid=255&presetid=N&`, ir con `command=64+N`.

Sin el preludio de auth (§1.0) devuelven `result=-2`. Detalle completo en `HALLAZGOS-PTZ.md`.

### 2.9 Vídeo en vivo

| Comando | Qué hace | Respuesta |
|---|---|---|
| `livestream.cgi?streamid=16&substream=0&` | Stream principal (2.304×1.296) | `result= 0;` |
| `livestream.cgi?streamid=16&substream=1&` | Substream | `result= 0;` |
| `livestream.cgi?streamid=10&substream=4&` | Stream de **vista previa** (lo usa el oficial al abrir zonas) | `result= 0;` |
| `audiostream.cgi?streamid=16&` | Audio | `result= 0;` |
| `callstatus.cgi?status=3&` | Estado de llamada | `result= 0;` |
| `snapshot.cgi?res=1&` | Foto | — |

### 2.10 MicroSD: disco, grabación y clips

| Comando | Qué hace | Respuesta real |
|---|---|---|
| `get_record.cgi?` | Estado del disco | `sdtotal=14816;  sdfree=5750;  record_sd_status=2;  tf_enable=1;  record_timer=5;  record_audio=1;` |
| `get_record_file.cgi?GetType=date&` | **Fechas con grabación** | `record_date[0]="20261017";  var record_datenum=1;` |
| `get_record_file.cgi?GetType=file&dirname=20261017&PageSize=1000&` | **Lista de clips** (28.135 B = **399 clips**) | `record_name[0]="20261017000040_100.mp4";  record_size[0]=9600553;  …` |
| `del_file.cgi?name=<archivo>&` | Borrar un clip | — |
| `set_formatsd.cgi?6` | Formatear SD [OFICIAL] | — |

**Formato del listado — corregido en el equipo (2026-10-17).** Es **con `[N]=`**, no sin él:

```
result= 0;  var record_name0=new Array();  var record_size0=new Array();
record_name[0]="20261017000040_100.mp4";  record_size[0]=9600553;
record_name[1]="20261017005401_100.mp4";  record_size[1]=5443004;   …
var record_filenum=47;  var totol_page=2;  var current_page=2;
```

Tres cosas que el listado esconde y que costaron un bug real:

1. **`PageSize` no es opcional.** Sin él la cámara manda sólo la **última página**
   (~96 clips, los del final del día) y parece que «el día sólo tiene eso». Con
   `PageSize=1000` devuelve el día completo en una sola respuesta (399 clips, 28 KB).
2. **La respuesta llega troceada por el túnel.** El mismo día llega en **dos**
   devoluciones de `commandListener` (24.583 B + 3.552 B ≈ 28 KB). Un parser que
   *reemplaza* la lista en cada trozo se queda sólo con los 49 clips del último
   (daba «49 clips · 99,1 MB» con 399 reales). Hay que **acumular**. Y
   `record_filenum`/`totol_page`/`current_page` llegan **desfasados** (decían 47 y 2
   con 399 clips): no sirven para paginar.
3. **El `[N]` del medio es obligatorio en el regex.** El parser anterior buscaba
   `"name":"…"`, una forma que **no existe** en la respuesta; y como además buscaba
   `\b20\d{6}\b` en cualquier respuesta, los propios nombres de los ficheros metían
   fechas falsas en la fila de fechas.

**Sufijos**: `_100` (262 clips, 2.879 MB) y `_011` (137, 411 MB) — son las **dos series
simultáneas** del equipo; las de `_100` pesan ~4× más, así que son el flujo principal.
No hay parámetro que filtre por lente: `sensor=0|1` y `channel=0|1` fueron
**ignorados** (los cuatro sondeos devolvieron las dos series, 399 clips).

**Grabación por horas (dato real)**: los 399 clips del día se reparten en **18 horas**
(`00…14` y `20,21,22`); **entre las 15:00 y las 19:59 no hay ni un clip** en la tarjeta.
El navegador de CamRevert agrupa por horas y refleja ese hueco tal cual.

#### Reproducción de clips — la vía del app oficial

> **RETIRADA DE LA UI (2026-10-17, punto 3).** El ▶ de cada fila y los botones
> «Reproducir»/«Detener» **ya no existen** en CamRevert: no reproducían nada (el flujo del
> fichero no sale por el túnel) y **además dejaban el túnel sordo** — `writeCgi` devolvía
> `true` pero la cámara dejaba de contestar cualquier CGI durante el resto de la sesión
> (ver §2.10.1). Los métodos `playRecord`/`stopRecord`/`recordLiveStream` siguen en
> `P2PControlManager` porque son los del app oficial y los usará el reproductor nativo,
> pero **ya no los envía nadie desde la interfaz**. Toda orden de esta tabla está
> **prohibida desde la UI** hasta que exista el reproductor nativo.

| Comando | Qué hace | Respuesta real |
|---|---|---|
| `record_fastplay.cgi?ctrl=1&playlist=<archivo>&` | **Arrancar la reproducción del clip en el equipo** [OFICIAL] ⚠️ **deja el túnel sordo** | `result= 0;` (aceptado) |
| `livestream.cgi?streamid=4&filename=<archivo>&` | **Abrir el flujo del fichero** [OFICIAL] | `result= 0;` (12 B, sin carga útil) |
| `record_fastplay.cgi?ctrl=0&` | Detener la reproducción (no recupera el túnel sordo) | `result= 0;` |
| `get_record_idx.cgi?dirname=…&offset=0&` | **[NO EXISTE]** — el oficial lo manda, la cámara no responde | — |

Los dos comandos que manda el app oficial se envían bien desde CamRevert:

```
[writeCgi] OK   record_fastplay.cgi?ctrl=1&playlist=20261017221402_100.mp4&
[writeCgi] OK   livestream.cgi?streamid=4&filename=20261017221402_100.mp4&
```

**El flujo del fichero no sale por el túnel CGI** (medido, no supuesto):

| Intento | Resultado |
|---|---|
| `livestream.cgi?streamid=4&filename=<clip>&` | `result= 0;` y **12 bytes**: ninguna carga útil |
| `record_fastplay.cgi?ctrl=1&playlist=<clip>&` + mirar el RTSP desde el PC | el directo **sigue** siendo el directo (el OSD avanza con el reloj, no con el clip) |
| `<clip>?`, `sd/<clip>`, `record/20261017/<clip>`, `20261017/<clip>` por el túnel | **ni contestan** (no hay respuesta que leer) |
| `download.cgi?file=<clip>&` | `result= 0;` (12 B) |

Y **no hay forma de leer el flujo por el JNI**: `libOKSMARTPPCS.so` expone `write` pero
**no** `client_read` (§5.4), y `client_check_buffer` sólo devuelve metadatos
(`client_check_buffer(h, arg, out1, out2)` → `iface->fn(...)`, sin bytes).

**La salida real existe y está en el XAPK**: `libOKSMARTPLAY.so` (10,8 MB) exporta una
**API Java completa de reproductor P2P**, con símbolos `Java_com_veepai_AppPlayerApi_*`:

```
createPlayer · setPlayerSource · checkPlayerSource · start · pause · resume · stop
speed · setProgress (búsqueda) · setProgressCallback · screenshot · changeSize
setCacheDir · getTmpfileDirPath · switchCamera · enableSubPlayer …
startDown · stopDown · saveMP4 · saveMP4Progress · tsToMP4 · mergeTSFiles   ← descarga a MP4
startVoice · stopVoice · startVoiceRecord · stopVoiceRecord
```

El reproductor **y** el descargador del app oficial están en el paquete. Lo que falta no es
una vía de datos, sino **mapear esa API** (los *stubs* JNI no llevan firma: hay que deducir
los tipos de argumento del desensamblado) y pasar el render a `SurfaceView` (el nativo
dibuja en un `Surface`, no en un `TextureView`). Es un **ítem propio**, no un arreglo de UI.

#### 2.10.1 Efecto secundario medido: el túnel se queda **sordo**

Reproducido tres veces (23:43, 23:55 y 00:03 del 2026-10-17/18) entre 30 s y 2 min después
de mandar `ctrl=1` + `livestream`:

| Síntoma | Medición |
|---|---|
| `writeCgi` devuelve | `true` (el flag `handle+0x204` sigue a 1) |
| `commandListener` | **cero** devoluciones: ni una respuesta más |
| La sonda `get_status.cgi?` (la única que contesta siempre) | **silencio** |
| `stateListener` | sigue en `2` (`TCP_ERR_WORKING`): la app pinta «en línea» |
| RTSP | sigue reproduciendo con normalidad |
| `record_fastplay.cgi?ctrl=0&` para deshacerlo | **no llega**: su respuesta ya no entra |
| Recuperación | solo reiniciar la app |

El disparo es **intermitente**: las mismas órdenes, repetidas en otras diez pruebas, no lo
provocaron. Consecuencia de diseño: **el ▶ se retiró** y la app incorporó un vigilante
(§7) que detecta el silencio y abandona el handle sordo **sin cerrarlo** — cerrarlo por
vía nativa (`disconnect`+`destroy`, o `clientConnectBreak`) **aborta la app con
`SIGABRT`** (`FORTIFY: pthread_mutex_lock called on a destroyed mutex`), porque el hilo
del SDK se queda atascado en el flujo del fichero y despierta cuando `destroy` ya liberó la
memoria del mutex `handle+0x1dc`.

### 2.11 Identidad y estado

| Comando | Qué hace | Respuesta real |
|---|---|---|
| `get_params.cgi?` | **Todo** el estado (¡ojo, filtra secretos!) | ver §5 |
| `get_status.cgi?vuid=<vuid>&` | Estado por VUID | — |
| `get_camera_params.cgi?` | Parámetros de vídeo | `cameratype=3;  resolution=5;  flip=0;  enc_bitrate=768;  MainStreamWidth=1280;  MainStreamHeight=720;  night_vision_mode=0;  full_color_mode=0;  full_color_default=1;  ircut=1;` |
| `get_image_param.cgi?` | Parámetros de imagen | — |
| `wifi_scan.cgi?` | Redes WiFi | `ap_number=1;  ap_ssid[]…` |
| `reboot.cgi?` | **Reiniciar la cámara** | `result= 0;` pero **el reinicio real tarda ~25 s** (la cámara responde antes de caer). **Medido:** de 3 intentos seguidos (cada ~4-5 min) solo tomaron efecto los **dos primeros**; el tercero fue aceptado pero la cámara siguió en pie (`runtime=402`). Parece tener **tiempo de espera entre reinicios** — para la prueba del punto 6, o se espera, o se corta el tráfico con `iptables` |
| `set_alias.cgi?alias=<nombre>&` | Nombre de la cámara [OFICIAL] | — |
| `set_datetime.cgi?tz=<n>&` | Zona horaria [OFICIAL] | — |

### 2.12 Secuencia de conexión real del app oficial [CAPTURA]

Orden exacto que sigue O-KAM al conectar (lo copiamos porque funciona):

```
get_params                            → estado completo
cmd=2017&type=N                       → planes/calendarios
cmd=2127                              → heartbeat de seguimiento (¡×2575 veces!)
cmd=8000&command=0                    → board version (audio_16k, get_board_version=10)
cmd=4121&action=3                     → handshake
cmd=2126                              → detección
cmd=2123&sensor=0|1                   → zonas
cmd=4109&command=0                    → modo 12h (osd_12h_mode)
cmd=4500&command=0                    → handshake
cmd=2131&command=1&DevActiveTime=10   → ¡keepalive periódico!
cmd=2109&command=2                    → estado luz/sirena/led
get_status?vuid=…
livestream.cgi?streamid=16&substream=0 + audiostream.cgi?streamid=16& + callstatus.cgi?status=3&
```

`cmd=2131&command=1&DevActiveTime=10&` es el **keepalive**: el app oficial lo repite sin parar.
Candidato directo para la estabilidad de sesión de CamRevert (§pendiente del usuario).

### 2.13 Visión nocturna y volteo de imagen — verificados

| Comando | Qué hace | Respuesta real |
|---|---|---|
| `camera_control.cgi?param=37&value=0&fullcolormode=<0\|1\|2>&fullcolordefault=0&` | Modo de visión nocturna | `result= 0;` (siempre) → releer 👇 |
| `get_camera_params.cgi?` → `full_color_mode` | **Leer** el modo | `var full_color_mode=<0\|1\|2>;  var night_vision_mode=<n>;` |
| **`camera_control.cgi?param=5&value=<0\|1>&`** | **Voltear imagen** ✅ | `result= 0;` → releer 👇 |
| `get_camera_params.cgi?` → `flip` | **Leer** el volteo | `var flip=<0\|1>;` |
| `camera_control.cgi?param=3&value=<0\|1>&` | **[DESCARTADO]** era el «volteo» de CamRevert | `flip` **no cambia** (medido) |

**[VIVO] Medido con relectura:**

| Enviado | `get_camera_params.cgi?` después |
|---|---|
| `param=3&value=1&` | `flip=0` (**no toca nada**) |
| `param=5&value=1&` | **`flip=1`** ✅ |
| `param=5&value=0&` | **`flip=0`** ✅ |
| `param=37&value=0&fullcolormode=1&fullcolordefault=0&` | **`full_color_mode=1`** ✅ |
| `param=37&value=0&fullcolormode=0&fullcolordefault=0&` | **`full_color_mode=0`** ✅ |

`param=3` sí existe en el binario oficial (`camera_control.cgi?param=3&value=`), pero en
**este** firmware no mueve `flip`. El que lo mueve es `param=5`.

### 2.14 Auditoría de la pestaña «Funciones» — veredicto (2026-10-17)

| Función | Lo que envía CamRevert | ¿Hace algo? | Lector del estado real | Veredicto |
|---|---|---|---|---|
| PTZ | `decoder_control.cgi?command=N&onestep=0&` | ✅ sí | — (se ve en el vídeo) | correcto |
| Sonido | `volume` del reproductor | local | — | correcto |
| Llamada | `callstatus.cgi?status=<0\|3>&` | ✅ aceptado | — | correcto |
| **Detección de movimiento** | `set_alarm.cgi?…motion_armed=` | ✅ sí (`var result="ok"`) | **`get_status.cgi?` → `alarm_status`** | **corregido**: la casilla ya refleja la cámara |
| Visión nocturna | `camera_control.cgi?param=37&…fullcolormode=` | ✅ sí | `get_camera_params.cgi?` → `full_color_mode` | correcto |
| Luz blanca | `set_whiteled_value.cgi?whiteled=<0..100>&` | ✅ sí | `get_whiteled_value.cgi?` | correcto |
| **Sirena** | `cmd=2109&command=0&siren=` | ✅ sí | `cmd=2109&command=2&` → `sirenStatus` | **corregido** (estado real) |
| **Estrobo** | `cmd=2109&command=0&alarmLed=` | ✅ sí | `cmd=2109&command=2&` → `alarmLedStatus` | **corregido** (estado real) |
| **Seguimiento de personas** | `cmd=2127&command=0&enable=` | ✅ sí | `cmd=2127&command=1&` → `enable` | **corregido**: antes mandaba `cmd=4500` (no-op) |
| Cuadro de detección | `cmd=2126&command=0&bHumanoidFrame=` | ✅ sí | `cmd=2126&command=1&` → `bHumanoidFrame` | correcto |
| **Voltear imagen** | `camera_control.cgi?param=5&value=` | ✅ sí | `get_camera_params.cgi?` → `flip` | **corregido**: antes mandaba `param=3` (no-op) |
| Tomar foto | `TextureView` local | local | — | correcto |
| Consola P2P | — | local | — | correcto |
| **Sensibilidad** | `cmd=2126&command=0&sensitive=&mark=123456789&` | ✅ sí | `cmd=2126&command=1&` → `sensitive` | **corregido**: antes mandaba `cmd=4500` (no-op) |
| **Detección humana** | `cmd=2106&command=4&humanDetection=` | ✅ aceptado | **no hay lector** | **sin verificar** |
| Ocultar LED de estado | *(no está en la UI)* | ✅ sí | `cmd=2125&command=1&` | verificado, no expuesto |

**La excepción es `humanDetection`**: `cmd=2106&command=4&humanDetection=1&` responde sólo
`var cmd=2106;` y **no existe** ningún CGI que publique su estado (`get_alarm.cgi?` no
existe; `get_params.cgi?` y `get_camera_params.cgi?` no traen ningún campo de detección
humana, sólo las *capacidades* `support_humanDetect` / `support_humanoidFrame` /
`support_AI`). Consecuencia práctica: la casilla «Detección humana» **no puede reflejar la
realidad**, así que «Aplicar detección» puede apagarla sin que se vea. Se deja así,
documentado, hasta poder confirmarlo delante de la cámara.

---

## 3. DESCUBIERTOS EN EL BINARIO OFICIAL [OFICIAL] — pendientes de probar

Las 305 plantillas salen de `C:\cam-reverse\endpoints.txt` (strings de `libapp.so`).
Muchas son de **otros modelos** (timbres, sensores de humo, temporizadores…). Estas son las que
podrían aplicar a esta cámara:

### 3.1 Audio / voz personalizada

| Comando | Qué haría |
|---|---|
| `trans_cmd_string.cgi?cmd=2135&command=0&switch=<n>&` | Activar audio personalizado |
| `trans_cmd_string.cgi?cmd=2135&command=0&urlJson=<json>&` | Subir definición de audios |
| `trans_cmd_string.cgi?cmd=2135&command=1&voicetype=<n>&` | Elegir voz |
| `trans_cmd_string.cgi?cmd=5001&command=2&audio_play_mode=<n>&` | Modo de reproducción |
| `trans_cmd_string.cgi?cmd=5001&command=3&audio_play_status=<n>&` | Estado |
| `trans_cmd_string.cgi?cmd=5001&command=6&urlJson==<json>&` | Lista de audios (¡nótese el `==`!) |

### 3.2 Planes / calendarios (`cmd=2017`)

| Comando | Qué haría |
|---|---|
| `trans_cmd_string.cgi?cmd=2017&command=1&mark=212&motion_record_plan1=<…>&` | Calendario de grabación por movimiento |
| `trans_cmd_string.cgi?cmd=2017&command=2&mark=212&motion_push_plan1=<…>&` | Calendario de notificaciones |
| `trans_cmd_string.cgi?cmd=2017&command=3&mark=212&record_plan1=<…>&` | Calendario de grabación continua |
| `trans_cmd_string.cgi?cmd=2017&command=5&mark=213&light_plan1=<…>&` | Calendario de luz |
| `trans_cmd_string.cgi?cmd=2017&command=6&mark=212&privacy_plan1=<…>&` | Calendario de privacidad |
| `trans_cmd_string.cgi?cmd=2017&command=11&mark=<m>&type=<1\|2\|3\|5\|6\|10\|14\|25>&` | Leer planes por tipo |
| `trans_cmd_string.cgi?cmd=2017&command=9&` | — |

[VIVO] Leído con `type=1|2|3`: `motion_record_plan1..21`, `motion_record_enable=0`,
`motion_push_plan…`/`motion_push_enable=5`, `record_plan…`/`record_plan_enable=0`. Todos los planes `-1` = sin definir.

### 3.3 Grabación

| Comando | Qué haría |
|---|---|
| `set_recordsch.cgi?record_audio=<0\|1>&` | Audio en grabación |
| `set_recordsch.cgi?record_cover=1&record_timer=<n>&` | Cobertura + duración de bloque |
| `set_recordsch.cgi?record_smart_mode=<n>&` | Grabación inteligente |
| `trans_cmd_string.cgi?cmd=4149&command=2&recordType=<n>&` | Tipo de grabación |
| `trans_cmd_string.cgi?cmd=2204&command=2&record_resolution=<n>&` | Resolución de grabación |

### 3.4 Imagen / vídeo

| Comando | Qué haría |
|---|---|
| `camera_control.cgi?param=<1..40>&value=<v>&` | Parámetros de imagen (brillo, contraste, flip…) |
| `camera_control.cgi?param=37&value=0&fullcolormode=<0\|1\|2>&fullcolordefault=<0\|1>&fullcolorstart=<…>&` | **Visión nocturna / color completo** |
| `camera_control.cgi?param=39&value=0&x_percent=<n>&` | Recorte/zoom |
| `set_image_param.cgi?set_default=1&` | Restaurar imagen por defecto |
| `trans_cmd_string.cgi?cmd=2105&command=2&videoFormat=<n>&` | Formato de vídeo |
| `trans_cmd_string.cgi?cmd=4109&command=1&osd_12h_mode=<0\|1>&` | **Reloj 12h/24h** |
| `trans_cmd_string.cgi?cmd=4106&command=1&black_mode=<n>&` | Modo negro |
| `trans_cmd_string.cgi?cmd=4120&command=11&play_mode=<n>&` | Modo de reproducción OSD |

### 3.5 Luz y alarmas avanzadas

| Comando | Qué haría |
|---|---|
| `trans_cmd_string.cgi?cmd=2017&command=5&mark=213&light_plan1=<…>&` | Programar la luz |
| `trans_cmd_string.cgi?cmd=4500&command=11&enable=<n>&alarm_light=<n>&alarm_sound=<n>&alarm_sound_volume=<n>&sensitivity=<n>&track=<n>&frame=<n>&` | **Vinculación completa de alarma** (luz + sonido + volumen + sensibilidad + seguimiento + encuadre) |
| `trans_cmd_string.cgi?cmd=4500&command=19&detect_type=<n>&alarm_light=<n>&alarm_sound=<n>&enable=<n>&` | Igual, por tipo de detección |
| `trans_cmd_string.cgi?cmd=2109&command=0&light=<n>&` | Luz directa (variante del `whiteled`) |
| `trans_cmd_string.cgi?cmd=2108&command=1&lightMode=<n>&` | Modo de luz — **[DESCARTADO]**: probado, `LightingLamp` no cambia |

### 3.6 Red / sistema / varios

| Comando | Qué haría |
|---|---|
| `trans_cmd_string.cgi?cmd=2106&command=15&WifiEnhancedMode=<n>&` | WiFi mejorado |
| `trans_cmd_string.cgi?cmd=2106&command=4&humanDetection=<n>&` | Detección humana (otra vía) |
| `trans_cmd_string.cgi?cmd=2106&command=4&trigger_interval=<n>&` | Intervalo entre disparos |
| `trans_cmd_string.cgi?cmd=2106&command=2&lowPower=<n>&` / `command=18&Smart_Electricity_Sleep_Switch=` | Bajo consumo |
| `trans_cmd_string.cgi?cmd=2106&command=21&aov_wake_interval=<n>&` | Intervalo de despertar (AOV) |
| `trans_cmd_string.cgi?cmd=2131&command=1&DevActiveTime=10&` | **Keepalive de sesión** (usar) |
| `trans_cmd_string.cgi?cmd=4105&command=1&reboot_time=<…>&` | Reinicio programado |
| `trans_cmd_string.cgi?cmd=4104&command=1&sleepflag=<n>&` | Dormir |
| `trans_cmd_string.cgi?cmd=4113&command=2&power_off=<n>&` | Apagar |
| `trans_cmd_string.cgi?cmd=4121&action=3&reconnect_jd=1&` | Variante de handshake con reconexión |
| `trans_cmd_string.cgi?cmd=8000&command=0&` | `audio_16k=0;  get_board_version=10` |
| `trans_cmd_string.cgi?cmd=2400&command=<0\|1>&AiType=<n>&` | Tipo de IA |
| `trans_cmd_string.cgi?cmd=2401&command=1&record_mode=<n>&` | Modo de grabación IA |
| `trans_cmd_string.cgi?cmd=2201&command=21&data=<…>&` | Datos genéricos |
| `set_users.cgi?pwd_change_realtime=1&…` | Cambio de contraseña (¡no usar sin pedirlo!) |
| `set_ipc_binding_info.cgi?binding_body=<…>&` | Vinculación |
| `set_update_push_user.cgi?` | Push |
| `auto_download_file.cgi?server=<…>&` | Descarga de archivos |
| `get_factory_param.cgi?` / `get_feeder_param.cgi?` / `get_gps_info.cgi?` | Parámetros varios |

### 3.7 De otros modelos (probablemente irrelevantes aquí)

`cmd=2206` (humo/fuego), `cmd=2301`/`2310`/`2311` (sensores), `cmd=4114` (timbre/música),
`cmd=4115` (lámpara), `cmd=4119` (SIM), `cmd=4120&command=9&list=` (lista de reproducción),
`cmd=4149&…recordType=`, `mutil_sensor_mipi.cgi`, `set_sensor_preset.cgi`, `cmd=2499&CmdType=`,
`cmd=2160`/`2161`/`2163`, `cmd=4100`/`4101` (gblinkage), `cmd=4116`, `cmd=4161`/`4162`, `cmd=9000&userid=`.

---

## 4. NO EXISTEN en este firmware [NO EXISTE]

| Comando | Evidencia |
|---|---|
| `get_record_idx.cgi?dirname=…&offset=0&` | Sin respuesta (el oficial lo manda, la cámara lo ignora) |
| `display_pwm_ctrl.cgi?…` | Sin respuesta |
| `trans_cmd_string.cgi?cmd=4121&action=3&` | Responde solo `var cmd=4121;` |
| `trans_cmd_string.cgi?cmd=4500&command=0&` | Responde solo `var cmd=4500;` |
| `trans_cmd_string.cgi?cmd=2131&command=1&DevActiveTime=10&` | Responde `var cmd=2131;` — **pero hace de keepalive** |

---

## 5. SEGURIDAD — `get_params.cgi?` filtra secretos en claro

La respuesta contiene, **en texto plano**:

- `WebPwd="…"` ← contraseña web de la cámara
- `wifi_ssid` y `wifi_wpa_psk` ← clave del WiFi
- `CPU="T23-N"`, `rtsp_auth_enable=1`, `onvif_support_enable=0`, `port=55580`

CamRevert **DEBE enmascararlos** antes de mostrarlos o registrarlos en el log.
Nunca subir estas respuestas a GitHub.

---

## 6. Canal binario (`JNIApi.write`) — el agujero conocido

El shim de captura (`C:\cam-reverse\hook\vplogshim.c`) engancha:

| Método JNI | Estado |
|---|---|
| `writeCgi` | ✅ enganchado (6.359 llamadas capturadas) |
| `writeCgiNVR` | ✅ enganchado (0 llamadas vistas) |
| `write` (canal binario) | ✅ enganchado **pero 0 llamadas vistas** |
| `create`, `connect`, `login`, `clientSetVuid` | ✅ enganchados |

**Conclusión:** los interruptores de la pantalla "Función de monitoreo" del app oficial
(encuadre humanoide, seguimiento, **luz intermitente**, guardado de la rejilla) **no salen por
`writeCgi` ni por `write`** → van por otra vía nativa todavía no identificada.

**Pero ya no importa:** las plantillas de `libapp.so` (§2.2, §2.4) dan los comandos exactos, y
**todos se han verificado en vivo contra la cámara**. No hace falta extender el shim.

El canal binario **sí** se usa para: audio (§5.4) y probablemente el *playback* de clips.

### 6.1 Vigilante de túnel sordo (2026-10-17/18)

El canal CGI puede morir **en silencio** (§2.10.1): `writeCgi` → `true`, `stateListener`
clavado en `2`, RTSP reproduciendo, y **ni una respuesta**. Como el estado no sale de
`ONLINE`, el supervisor de reintentos no se enteraba. CamRevert lo vigila así:

| Pieza | Valor / comportamiento |
|---|---|
| Ventana pendiente | `sendRaw` la abre tras cada `writeCgi` aceptado; **idempotente** (no se reescribe mientras siga abierta) |
| Cierre | Cualquier carga de `commandListener` la cierra |
| Ticker | Cada `CGI_WATCHDOG_TICK_MS = 2_000`; solo con `ONLINE` **y** `authorized` (0 tráfico en reposo) |
| Sonda | `CGI_WATCHDOG_PROBE = get_status.cgi?` — **la única que contesta siempre** |
| Umbrales | `CGI_ANSWER_TIMEOUT_MS = 8_000` (silencio → manda la sonda) + `CGI_PROBE_GRACE_MS = 6_000` (la sonda tampoco contesta → sordo) |
| Recuperación | `abandonHandle()` (**sin ninguna llamada nativa**) + cascada nueva → verificado: sordo → `TÚNEL CONFIRMADO` → `eye4_auth=1` en ~10 s, sin cerrar la app |
| Techo | **`DEAF_RECOVERY_MAX = 1`** por ejecución; al segundo episodio avisa «cierra y vuelve a abrir la app» y **no** insiste |
| Gancho de prueba | `adb shell am broadcast -n com.lgsusdev.camrevert/.debug.CgiDebugReceiver --ez deaf true` |

**Por qué no se cierra el handle por la vía nativa** (medido, dos abortos): matar el handle
sordo con `disconnect`+`destroy` provoca
`SIGABRT — FORTIFY: pthread_mutex_lock called on a destroyed mutex (handle+0x1dc)`, porque el
hilo del SDK sigue atascado en el flujo del fichero. `abandonHandle()` deja el handle al SDK
(fuga controlada) y la app rehace el túnel con un `create` nuevo. Los tiempos salen de un
**falso positivo real**: una sesión sana tardó 4,46 s en contestar un `get_status.cgi?` de
2,5 KB, por eso el umbral está en 8 s y no en 4 s.

---

## 7. Pendientes de esta tabla

- [ ] Confirmar el setter de la rejilla: `cmd=2123&command=<N>&pd_reign0..20=<bits>&`
- [ ] Confirmar `cmd=2108&command=1&alarmLedMode=<n>&` y `sirenMode=<n>&`
- [x] ~~Confirmar `cmd=2125&command=0&hide_led_disable=<0|1>&`~~ → **funciona** (§2.6); no está en la UI
- [x] ~~Probar `camera_control.cgi?param=37&…fullcolormode=` (color nocturno)~~ → **funciona**, lee `full_color_mode` (§2.13)
- [ ] Probar `cmd=4109&command=1&osd_12h_mode=` (reloj 12/24 h)
- [x] ~~Probar el playback completo: `streamid=4&filename=` + leer el stream por el canal binario~~ → **vía cerrada** (§2.10): no hay carga útil por el túnel ni `client_read` en el JNI. La única salida es `libOKSMARTPLAY.so` (ítem propio). **Prohibido desde la UI**: `record_fastplay.cgi?ctrl=1` dejaba el túnel **sordo** (§2.10.1)
- [ ] **Ítem propio**: integrar `libOKSMARTPLAY.so` (player o descargador a MP4) para ver los clips sin el ▶
- [ ] **Confirmar físicamente** que la sirena suena y el estrobo parpadea (§2.3)
- [ ] **Confirmar físicamente** que «Alta» detecta antes que «Baja» (§2.4, escala inversa)
- [ ] Averiguar si existe lector para `humanDetection` (§2.14): hoy la casilla no puede reflejar la realidad
- [ ] Confirmar `camera_control.cgi?param=39&value=0&x_percent=<n>&` (zoom digital)
- [ ] Enmascarar secretos de `get_params.cgi?` en CamRevert
- [ ] **Túnel sordo**: identificar el disparo exacto (intermitente; ya no hay camino desde la UI) y valorar el auto-reinicio del proceso como segunda recuperación (§2.10.1, §7)
