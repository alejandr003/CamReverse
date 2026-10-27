# Hallazgos PTZ — el cuaderno de la investigación

> 🌐 **Español** · [English](en/PTZ-FINDINGS.md)

> **Resultado**: ✅ **resuelto**. La cámara exige un **token de la cuenta Eye4** en cada
> sesión (`eye4_authentication.cgi`) antes de ejecutar cualquier orden. En cuanto se envía,
> el domo se mueve: los 4 ejes, sus paradas, el centrado, los cruceros y los presets.
>
> Esta es la versión *publicable* del cuaderno (identificadores sustituidos por
> marcadores). El cuaderno crudo con las capturas completas vive en el equipo donde se hizo
> la investigación y **no se versiona**, porque contiene credenciales de la cámara.

---

## 1. El síntoma

CamRevert montaba el túnel P2P y `login()` devolvía `true`, y `get_status.cgi?` devolvía
datos reales de la cámara (2,5 KB). **Pero cualquier otro CGI** devolvía siempre lo mismo:

```
result= 0;  result=-2;                       (24 bytes)
```

El domo no se movía. Y lo peor: los mismos CGI que la app oficial enviaba con éxito a esa
misma cámara, desde CamRevert no hacían nada. Con `curl` directo contra la cámara
respondían `200 OK` **también** sin mover nada.

Hipótesis iniciales (todas razonables, todas falsas):

| Hipótesis | Por qué parecía cierta | Por qué era falsa |
|---|---|---|
| El comando PTZ estaba mal | No sabíamos el mapeo | Era exacto: `command=N&onestep=0&` |
| El timeout de `writeCgi` era corto | El oficial usaba 5 ms y nosotros 5000 | El timeout no era el problema |
| Faltaban credenciales `loginuse`/`loginpas` distintas | El oficial mandaba `userId=` extra | No era eso |
| Faltaba el preámbulo `trans_cmd_string.cgi?cmd=…` | El oficial manda un montón | Ninguno era necesario |
| Faltaba inicializar el SDK (`PPCS_Initialize`) | Y era cierto que faltaba… | …pero ya estaba arreglado: el túnel montaba |

---

## 2. La técnica: meterse en medio de la app oficial

El requisito era **capturar lo que manda el oficial sin usar Frida** ni modificar el APK de
forma detectable. Recorrido:

| Vía | Resultado |
|---|---|
| Frida / inyección | **Descartado** por requisitos del proyecto |
| `LD_PRELOAD` / `wrap.<pkg>` | **Muerto**: desactiva el puente de traducción ARM del emulador (`has unexpected e_machine: 183 (EM_AARCH64)`). Con `.so` x86_64 las librerías ARM de la app fallan igual |
| Sustituir `libOKSMARTPPCS.so` | Inviable: **1510 símbolos exportados** |
| Definir nosotros `Java_com_vstarcam_JNIApi_*` | **Pierde**: el propio `.so` define los mismos símbolos y gana en su `dlsym` |
| `dlsym(RTLD_DEFAULT, …)` desde un shim | Encontraría **nuestra** definición → se eliminan las nuestras |
| logcat desde un shim traducido | **Silencio total** |

### 2.1 La que sí funcionó: sustituir `libvp_log.so`

`libOKSMARTPPCS.so` tiene **una sola dependencia no-sistema**:

```
NEEDED: libvp_log.so, libm.so, libc.so, libz.so, liblog.so, libandroid.so, libdl.so
```

…y de `libvp_log.so` solo importa **dos** funciones (`vp_printf`, `vp_printf_p2p`). Es decir:
una librería de **13 KB** por la que pasa **todo** el log interno del SDK.

Se construyó un **shim arm64** que se instala en la app oficial con el mismo nombre
(`…/lib/arm64/libvp_log.so`), conservando propietario, modo y etiqueta SELinux. El shim:

1. Registra los mismos símbolos que el original (para que el SDK enlace sin sospechar).
2. **Escribe a fichero** todo lo que el SDK le pasa (logcat no servía por el puente ARM).
3. Deja que el SDK siga funcionando con normalidad.

Con eso se obtuvo **la conversación completa del SDK oficial**: `create`, `clientSetVuid`,
`connect`, `login`, cada `writeCgi` y todas las respuestas de la cámara.

> Restauración: se guarda copia del `.so` original (md5 conocido, 13.440 bytes) y un script
> para devolverlo; la app oficial queda intacta cuando no se está capturando.

---

## 3. Lo que se vio al leer al oficial

Simplificado (identificadores reales → marcadores):

```
>>> JNIApi.create("<ID_P2P_17>", "<ID_P2P_17>")        ← did2 = 17 caracteres
>>> JNIApi.clientSetVuid(h, "<VUID_13>")
>>> JNIApi.connect(… mode=126, target="<ID_P2P_17>", p2pType=1, ips=NULL)
      client_connect : version:4 clientId:<ID_P2P_17> connectType:0x7E result:1
>>> JNIApi.login(h, user="admin&userId=<ID_CUENTA>", pass="<TOKEN_LOGIN_19>")
>>> JNIApi.writeCgi(h, "eye4_authentication.cgi?loginAccount=<ID_CUENTA>&loginToken=<TOKEN>&")
>>> JNIApi.writeCgi(h, "decoder_control.cgi?command=6&onestep=0&")
      respuesta: result= 0;  var result="ok";
```

Dos hallazgos que **parecían** la respuesta y no lo eran:

1. **El `login` del oficial no usa la contraseña de la cámara**, sino un
   `user="admin&userId=<id>"` y un `pass` de 15 hex distinto en cada sesión. Se buscó su
   origen (341 candidatos md5/sha1/sha256) **sin encontrarlo**: era el token de sesión, no
   una credencial derivable.
2. **`loginuse` inyecta un `&userId=…` extra** en la query del CGI (visible en la captura
   del envoltorio `GET /…`).

Ambas cosas eran **síntomas** de lo mismo: la sesión del oficial estaba autenticada contra la
cuenta. CamRevert no.

### 3.1 La línea que lo explicaba todo

Entre el preámbulo del oficial apareció, **una sola vez y en cada conexión**:

```
>>> JNIApi.writeCgi(h, "eye4_authentication.cgi?loginAccount=<ID_CUENTA>&loginToken=<TOKEN>&")
      respuesta: result= 0;  eye4_auth=1;
```

---

## 4. La causa raíz (y su prueba)

**La cámara no ejecuta ninguna orden hasta recibir un token firmado de la cuenta Eye4 dentro
de la sesión.**

Prueba directa, en el equipo real:

| Momento | CGI enviado | Respuesta |
|---|---|---|
| Túnel montado, **sin** preludio | `decoder_control.cgi?command=0&onestep=0&` | `result= 0;  result=-2;` |
| Tras `eye4_authentication.cgi` (`eye4_auth=1`) | `decoder_control.cgi?command=0&onestep=0&` | `result= 0;  var result="ok";` |

Y con eso, **los nueve** comandos (0..7 y 25) devolvieron `var result="ok"` y el movimiento
se confirmó **a la vista**.

`get_status.cgi` es la única excepción (siempre contesta), y ese detalle es lo que hizo que
el diagnóstico tardara: el sondeo del túnel parecía confirmar que todo estaba bien.

### 4.1 De dónde sale el token

Del flujo de cuentas de Eye4, **contrastado con
[`oleandor/okam-ha-native`](https://github.com/oleandor/okam-ha-native)**:

```
POST https://api.eye4.cn/user/summary      name=<TUCORREO>&oemid=VSTC   → {"userid":…}
GET  https://api.eye4.cn/login/token       userid=<id>&password=<md5(PASSWORD)>&type=PC
                                                                       → {"token":"…"}
```

Detalle fino que costó un rato: **el `token` ya viene percent-encoded** (`%2F`, `%2B`,
`%3D`). Hay que pasarlo **tal cual**; codificarlo otra vez lo corrompe (`%` → `%25`).

---

## 5. Lo que se descartó definitivamente

- **El error `-5` / `rc=4` del `connect`** y toda la teoría del `serverStr`/`iface2`: el
  túnel se monta con `das/p2ptype1-nullips` y el `login` pasa. El bloqueo no estaba ahí.
- **El cliente Python puro** (`okam-ha-native`) para controlar **esta** cámara: con el
  esquema CS2 antiguo, el relay enrutaba a **otro dispositivo** (`vuid` ajeno). Se quedó como
  laboratorio de protocolo y como **fuente del flujo de cuentas**, que es lo que sí aportó.
- **El preámbulo completo del oficial** (`trans_cmd_string.cgi?cmd=…`, selección de sensor,
  "activar dispositivo"): funcionalidad del visor, no un desbloqueo.
- **Probar los CGI por HTTP con `curl`**: el servidor HTTP embebido responde `200 OK` sin
  despachar la orden a los motores. El control real va **dentro** del túnel P2P.

---

## 6. Implementación (ya en la app)

| Pieza | Qué hace |
|---|---|
| `p2p/Eye4Auth.kt` | Pide el token (2 HTTPS) y lo parsea. Única salida a Internet de la app. |
| `P2PControlManager.authenticateSession()` | Envía el preludio y **espera el veredicto** `eye4_auth=1` (con timeout). |
| `commandListener` → `handleSessionAuth()` | Completa esa espera; si un `result=-2` aparece sobre una sesión ya autorizada (token caducado), **reautoriza una vez**. |
| `CamConfig.accountUser` / `accountPassword` | Cuenta Eye4, en local. |
| Configuración (UI) | Campos «Cuenta EYE4 (email)» y «Clave de la cuenta». |
| `CgiDebugReceiver --ez reauth true` | Repite el preludio sin recompilar (solo DEBUG). |

Secuencia completa ya implementada:

```
create → clientSetVuid → connect → writeCgi(sondeo) → login
       → ★ eye4_authentication.cgi (esperar eye4_auth=1) ★
       → decoder_control.cgi?command=N&onestep=0&   ⇒ var result="ok"
```

---

## 7. Verificación final (2026-10-17, cámara real)

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

Movimiento **confirmado a la vista** en los cuatro ejes, sus paradas y el centrado.

---

## 8. Luz blanca (verificado en equipo, 2026-10-17)

**`whiteled` es el brillo en porcentaje, no un booleano**, y es el **único** mando que
necesita la luz:

```
set_whiteled_value.cgi?whiteled=0&     → LED apagado
set_whiteled_value.cgi?whiteled=100&   → LED encendido al máximo
set_whiteled_value.cgi?whiteled=N&     → LED al N %   (0 ≤ N ≤ 100)
get_whiteled_value.cgi?                → var whiteled=<0..100>;  var light=<0|1>;
```

| Acción | Lectura posterior | LED |
|---|---|---|
| `whiteled=0` | `var whiteled=0;  var light=1;` | apagado |
| `whiteled=100` | `var whiteled=100;  var light=1;` | **encendido a tope** |
| deslizador de la app, 0 ↔ 80 ↔ 100 | devuelve el valor exacto | **apaga / 80 % / a tope** (confirmado por el usuario) |

`whiteled` **basta**: es el brillo y el interruptor a la vez. El campo `light` de la
respuesta no se usa en la app: apareció en `0` con `whiteled=100` en una prueba, y la
causa era tener **dos clientes a la vez** (la app del móvil y la del emulador) mandando
luz sobre la misma cuenta. Con un solo cliente, el deslizador manda y el estado vuelve
tal cual.

El error de partida fue creer que `whiteled` era booleano y mandar `whiteled=1` para
encender: eso deja el LED al **1 %**, invisible de día, y hacía pensar que la luz no
funcionaba.

Descartados con evidencia, no por comodidad:

- `display_pwm_ctrl.cgi` — **no existe** en este firmware (no responde).
- `trans_cmd_string.cgi?cmd=2109&command=0&light=0|1&` — **redundante**: `whiteled` ya
  enciende y apaga. Sus campos `lightStatus`/`sirenStatus`/`alarmLedStatus` son **eco de
  la petición**, nunca estado real del equipo.
- `set_whiteled_value.cgi?mode=0|1&` — **no-op**: es justo lo que envía el botón directo
  de la app oficial, y por eso su botón «no responde».
- `set_whiteled_value.cgi?ledtimes=N&` — aceptado, **no altera el brillo**; el apagado
  diferido no está verificado.

Consecuencia en la app: la pestaña Luz quedó con **un solo control**, un deslizador
`0..100` (0 = apagado), y la casilla de Funciones da el encendido/apagado de un toque.

---

## 9. Notas de método

- Toda la investigación se organizó con la *skill* de ingeniería inversa
  **[`morluto/rea`](https://github.com/morluto/rea)**: evidencia antes que hipótesis, y
  prueba en el equipo antes de dar nada por bueno.
- Ninguna de las conclusiones de este documento es "porque lo parece": cada una tiene
  detrás un log, una captura o un desensamblado (en
  [`../AGENTS.md`](../AGENTS.md)).
- El aprendizaje más útil de todo el proceso: **un `result=-2` no significa "comando
  inválido", significa "sesión no autorizada"**. Antes de tocar la sintaxis de un comando,
  hay que asegurarse de que la sesión tiene permiso para ejecutarlo.
