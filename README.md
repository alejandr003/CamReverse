# CamRevert

> 🌐 **Español** · [English](README.en.md)

Cliente **nativo Android** (Kotlin) para cámaras **O-KAM Pro / VStarcam / Eye4** de doble
lente (lente fija + **domo PTZ**): vídeo por RTSP directo, control PTZ real, luz, visión
nocturna, sirena, detección y MicroSD.

**Sin anuncios. Sin telemetría. Sin cuenta nuestra. El vídeo nunca sale de tu red.**

Reconstruido por **ingeniería inversa** del XAPK oficial (`com.okampro.oksmart`) reutilizando
sus propias librerías nativas: así el control habla exactamente el mismo protocolo que la
cámara espera, sin inventarse nada.

> Estado: **el control PTZ funciona de verdad** (los 4 ejes, sus paradas, el centrado, los
> cruceros y los presets), verificado contra hardware real. Ver
> [`docs/HALLAZGOS-PTZ.md`](docs/HALLAZGOS-PTZ.md).

---

## ¿Qué hace hoy?

| Función | Estado |
|---|---|
| **Vídeo por RTSP** (HD `av0_0` / SD `av0_1`, las dos lentes a la vez) | ✅ |
| Zoom digital 1.0x–8.0x, arrastre, doble toque, pantalla completa | ✅ |
| Selector de lente `Fija / Ambas / PTZ`, HUD (estado / resolución / KB/s) | ✅ |
| Reconexión automática + bajón HD→SD + conmutación TCP/UDP de RTP | ✅ |
| Foto (instantánea), sonido global | ✅ |
| **Túnel P2P** (`libOKSMARTPPCS.so`) + **autorización de sesión** | ✅ |
| **PTZ**: 4 ejes con parada propia, centrado (`command=25`) | ✅ Verificado en equipo |
| Feedback táctil PTZ (botón en verde al pulsar) | ✅ |
| **Cruceros** vertical (26/27) y horizontal (28/29) | ✅ Aceptados y ejecutados |
| **Presets**: guardar (`set_sensor_preset.cgi`) e ir (`command=64+N`) | ✅ Aceptado (ir a preset, por confirmar a la vista) |
| **Conexión automática** del túnel P2P al abrir la app | ✅ Conmutable en Configuración |
| Decodificación con **respaldo por software** (+ forzar decoder) | ✅ |
| Luz blanca (encendido, brillo), visión nocturna | ✅ El **brillo** es a la vez el interruptor y el nivel; la cámara lo devuelve al leerlo |
| Sirena policial y estrobo | ✅ por CGI, con el estado real releído del equipo |
| Detección humana / movimiento / seguimiento | ✅ por CGI |
| MicroSD: consulta, marcar archivo, formatear, navegador de clips | ✅ consulta y navegador; ver un clip necesita el reproductor nativo (ver *Limitaciones*) |
| Intercomunicador G.711 A-law (dúplex y push-to-talk) | ⚠️ Envío sí; **recepción** sin confirmar |
| **Acceso remoto** (fuera de tu wifi) | ⚠️ Con el túnel P2P; hoy en día se usa en LAN |
| Funcionar en un equipo **32 bits** | ❌ No hay binarios arm64-v8a alternativos |

---

## ¿Con qué cámaras funciona?

Está probado en **una** cámara concreta: una **O-KAM Pro de doble lente** ("gun-ball":
lente fija arriba + domo PTZ abajo), firmware de la familia **VStarcam/Eye4 con SDK P2P
"kaven" (`libOKSMARTPPCS.so`)** y RTSP en los puertos **10555** (fija) y **10554** (PTZ).

Debería funcionar con cualquier equipo que cumpla **las tres** condiciones:

1. **Usa el SDK P2P de VStarcam** (`libOKSMARTPPCS.so` / `cs2p2p_PPPP_*`). Es la familia
   O-KAM / O-KAM Pro / VStarcam / Eye4 / algunas clonas "gun-ball".
2. **Publica RTSP** en la LAN (en estos equipos, 10554/10555 con rutas `/tcp/av0_0` y
   `/tcp/av0_1`).
3. **Acepta los CGI clásicos** `decoder_control.cgi` / `camera_control.cgi`, que es el
   protocolo Foscam/VStarcam estándar.

**Lo que hay que averiguar de tu equipo** (la app no lo adivina, ver
[`docs/CONFIGURACION.md`](docs/CONFIGURACION.md)):

- `VUID` — impreso en la etiqueta y visible en la app oficial.
- **ID P2P real de 17 caracteres** y **dirección `DAS-…`** — los baja la app oficial de su
  nube; hay que copiarlos de sus preferencias (`FlutterSharedPreferences` → `p2p_did`).
- **Usuario y contraseña de la cámara** — los que pide la app oficial para el vídeo.
- **Email y contraseña de tu cuenta Eye4** — solo para pedir el token de sesión.
- **Si tu firmware exige el preludio `eye4_authentication.cgi`.** En el equipo probado lo
  exige; si el tuyo no, simplemente ignora ese paso (la app lo envía igual y no estorba).

Si tu cámara tiene **una sola lente**, la app funciona igual: la lente "PTZ" mostrará el
mismo stream o fallará al conectar y basta con usar el selector en modo `Fija`.

---

## Instalación

### Opción A — APK ya compilada

1. Descarga `app-debug.apk` (o compílala, abajo).
2. Cópiala al móvil e instálala (permitir "instalar apps desconocidas").
3. Al abrir, rellena **Configuración** con tus datos (o compílala con tu
   `secrets.properties` y vendrán puestas).

> Requiere **Android 7.0+** y CPU **arm64-v8a** (cualquier móvil de los últimos ~8 años).

### Opción B — Compilar

Requisitos: **JDK 17**, Android SDK con **NDK 27.1.12297006** y **CMake 3.22.1** (los
instala solo el gestor de SDK de Android Studio).

```powershell
$env:JAVA_HOME="C:\Program Files\Java\jdk-17"
.\gradlew.bat assembleDebug --console=plain
```

Sale `app\build\outputs\apk\debug\app-debug.apk` (~11 MB).

---

## Configuración: tus claves (y cómo no filtrarlas)

Los datos de **tu** cámara y de **tu** cuenta **no están en el código**. Dos formas:

### 1. En tiempo de compilación: `secrets.properties`

`secrets.properties` es **el `.env` de este proyecto** (también vale llamarlo `.env`;
está en `.gitignore` y **nunca se sube a GitHub**).

```powershell
Copy-Item secrets.properties.example secrets.properties
notepad secrets.properties
```

| Clave | Qué va ahí | De dónde lo sacas |
|---|---|---|
| `cam.ip` | IP de la cámara en tu red | router / app oficial |
| `cam.vuid` (= `cam.uid`) | **VUID** (13 car., empieza por `VE`) | etiqueta del equipo |
| `cam.p2pDid` | **ID P2P real** (17 car.) | preferencias de la app oficial |
| `cam.dasAddress` | **dirección `DAS-…`** (132 car.) | preferencias de la app oficial |
| `cam.user` | usuario de la **cámara** (`admin`) | app oficial |
| `cam.password` | **CAMPASSWORD** — contraseña de la **cámara** | app oficial |
| `cam.accountUser` | **TUCORREO** — email de tu cuenta **Eye4** | la que uses en O-KAM Pro |
| `cam.accountPassword` | **PASSWORD** — clave de esa **cuenta** | ídem |
| `cam.portFixed` / `cam.portPtz` | puertos RTSP (`10555` / `10554`) | app oficial |

> ⚠️ **`CAMPASSWORD` no es `PASSWORD`.** La primera es la de la **cámara** (vídeo y `login`
> del SDK). La segunda es la de tu **cuenta Eye4**, y solo sirve para pedir el token de
> autorización de sesión (`eye4_authentication.cgi`). Ver
> [`docs/CONFIGURACION.md`](docs/CONFIGURACION.md).

### 2. Desde el propio móvil: diálogo de *Configuración*

Ignora los archivos por completo y escribe todo en la app. Se guarda en
`SharedPreferences` locales (`camrevert_config`), sin nube. La pestaña incluye
**«Cuenta EYE4 (email)»** y **«Clave de la cuenta»**.

Al guardar, la app reconecta el vídeo y el túnel P2P automáticamente.

---

## Uso

- **Barra superior**: estado P2P (pulsa para conectar/desconectar), sonido global, consola
  P2P y ajustes.
- **Selector de vista**: `Fija | Ambas | PTZ`. En horizontal van en fila; en vertical,
  apiladas.
- **Pestaña PTZ**: rueda de 8 direcciones + centro (`command=25`), «Corrección PTZ»,
  «Patrulla», presets (ir / guardar) e inversión de ejes X/Y por si tu domo va al revés.
- **Pestaña Luz**: visión nocturna (IR / color / inteligente / starlight), luz blanca
  (deslizador de brillo, destello), sirena y estrobo.
- **Pestaña Funciones**: cuadrícula tipo O-KAM (PTZ, sonido, llamada, detección, visión
  nocturna, luz, sirena, seguimiento, cuadro de detección, voltear imagen, **foto**,
  consola P2P).
- **Pestaña MicroSD**: tarjeta (total/libre/estado), grabación programada, marcar archivo,
  formatear, navegador de fechas → clips, log de estado y reinicio.
- **Pantalla completa** `[⛶]`: vídeo a pantalla completa con controles flotantes
  translúcidos (PTZ, zoom, sonido, foto, micrófono) que **no bloquean** el pinch-zoom.
- **Consola P2P**: log en vivo de *todo* lo que ocurre en el túnel — cada intento de
  conexión, cada CGI y cada respuesta cruda de la cámara. Es la herramienta con la que se
  depuró todo esto y sigue siendo útil si algo no responde.

---

## Cómo funciona (resumen)

- **Vídeo**: RTSP directo a la cámara. No pasa por el SDK P2P (menos CPU y RAM).
- **Control**: los comandos van por el **túnel P2P** del propio SDK de VStarcam
  (`libOKSMARTPPCS.so`, del XAPK original). Los CGI **no** se envían por HTTP: el nativo
  los envuelve y los mete en el túnel.
- **Autorización**: la cámara no ejecuta ninguna orden hasta recibir un token de tu cuenta
  Eye4 (`eye4_authentication.cgi`). Ese token se pide por HTTPS en cada conexión y **no se
  almacena**. Es la **única** salida a Internet de la app.

Todo el detalle (protocolo, evidencia binaria, método de ingeniería inversa) está en
[`docs/COMO-FUNCIONA.md`](docs/COMO-FUNCIONA.md) y en la especificación interna
[`AGENTS.md`](AGENTS.md).

---

## Problemas frecuentes

| Síntoma | Causa y solución |
|---|---|
| **El vídeo se ve pero el PTZ no hace nada** (respuestas `result=-2`) | Falta la cuenta Eye4: rellena `cam.accountUser` / `cam.accountPassword` (o el diálogo de Configuración). La consola P2P dice `SIN TOKEN DE CUENTA`. |
| `get_status.cgi` responde `result=-1;vuid=…` | **Es normal** en este firmware, no es un fallo de credenciales. |
| `SIN TÚNEL, se descarta` | El túnel P2P no está montado: pulsa el estado P2P de la barra superior y mira la consola. |
| El vídeo va a tirones / se reconecta | Prueba `HD→SD` y deja que la app cambie sola entre RTP/UDP y TCP (o fuérzalo en Configuración). |
| El domo se mueve al revés | Pestaña PTZ → invertir ejes X/Y. |
| `Ninguna combinación abrió el túnel` | Revisa `cam.p2pDid` (deben ser **17** caracteres) y `cam.dasAddress`. La consola muestra cada intento con su código. |
| Un CGI responde `var result=-1` | Ese CGI **no existe** en tu firmware (p. ej. `get_sensor_preset.cgi` aquí). |

---

## Privacidad

- El vídeo y el audio **nunca** salen de tu red: van por RTSP a la IP de la cámara.
- La app **no** tiene analítica, ni publicidad, ni SDK de terceros.
- La **única** conexión a Internet es `api.eye4.cn` (HTTPS), y solo para pedir el token de
  sesión que la propia cámara exige. Si no configuras la cuenta, no hay ninguna.
- Tus claves viven en `secrets.properties` (en tu PC, ignorado por git) y en
  `SharedPreferences` del móvil.

---

## Estructura del proyecto

```
app/src/main/cpp/            puente C a PPCS_Initialize (libpcs_shim.so, ~5 KB)
app/src/main/jniLibs/        libOKSMARTPPCS.so + libvp_log.so (del XAPK, sin modificar)
app/src/main/java/
  com/lgsusdev/camrevert/    UI, configuración, RTSP, audio, escáner LAN, debug
  com/veepai/vp_log/         fachada JNI al log nativo del SDK
  com/vstarcam/              fachada JNI al SDK P2P (20 natives, nombres intactos)
  com/vstarcam/app_p2p_api/  interfaces de callback del SDK
docs/                        documentación
secrets.properties.example   plantilla de claves (la tuya va en secrets.properties)
AGENTS.md                    especificación técnica interna con la evidencia de la RE
```

> Los nombres de paquete y método de `com.vstarcam.*` y `com.veepai.vp_log.*` **no se pueden
> renombrar**: el enlace JNI resuelve por símbolo (`Java_com_vstarcam_JNIApi_<metodo>`).

---

## Documentación

| Documento | Contenido |
|---|---|
| [`docs/CONFIGURACION.md`](docs/CONFIGURACION.md) | Todas las claves explicadas (qué es `TUCORREO`, `PASSWORD`, `CAMPASSWORD`, `VUID`), `.env` vs `secrets.properties`, y cómo obtener el ID P2P y la dirección `DAS-`. |
| [`docs/COMO-FUNCIONA.md`](docs/COMO-FUNCIONA.md) | Arquitectura, protocolos (RTSP + P2P + CGI), la autorización de sesión, y **cómo se hizo**: método de ingeniería inversa, qué se leyó (binarios, logs, capturas), qué se probó. |
| [`docs/COMANDOS.md`](docs/COMANDOS.md) | La tabla maestra de comandos CGI: qué hace cada uno, su respuesta real y cuáles no existen en este firmware. |
| [`docs/HALLAZGOS-PTZ.md`](docs/HALLAZGOS-PTZ.md) | El cuaderno de la investigación PTZ: cómo se interceptó al oficial, qué se descubrió y cuál era la causa raíz. |
| [`AGENTS.md`](AGENTS.md) | Especificación técnica con direcciones de memoria, desensamblados y tablas de errores del SDK. |

> 🇬🇧 **English docs**: [`README.en.md`](README.en.md) · [`docs/en/`](docs/en/README.md) —
> todas las guías de usuario están también en inglés.

---

## Créditos y agradecimientos

Este proyecto se apoya en herramientas y trabajos de otras personas. Sin ellos habría sido
muchísimo más lento:

- **[`morluto/rea`](https://github.com/morluto/rea)** — la *skill* de **ingeniería inversa
  asistida** (Reverse Engineer Anything) con la que se planificó y ejecutó la RE de forma
  metódica: evidencia primero, artefactos después. La forma de trabajar de este repo
  (hipótesis → evidencia binaria → prueba en equipo) sale de ahí.
- **[`oleandor/okam-ha-native`](https://github.com/oleandor/okam-ha-native)** — cliente
  Python del **protocolo CS2 P2P** y del **flujo de cuentas de Eye4**. Sirvió para
  descubrir cómo se pide el `userid` y el `loginToken` a `api.eye4.cn` (la clave del
  arreglo del PTZ) y para entender el *framing* CS2 con sondas de laboratorio.

Herramientas usadas en la investigación: **JADX** (DEX → Java), **llvm-objdump**
(desensamblado de `libOKSMARTPPCS.so`), **Wireshark/pcap**, **adb + logcat**, un *shim*
propio de `libvp_log.so` para leer el log interno del SDK, y **asistencia de IA** para
leer los desensamblados y cruzar hipótesis.

---

## Licencia

**MIT** para el código fuente de este proyecto — ver [`LICENSE`](LICENSE).

⚠️ **Los binarios del fabricante no son MIT.** `app/src/main/jniLibs/arm64-v8a/`
contiene `libOKSMARTPPCS.so` y `libvp_log.so`, **propiedad de VStarcam/Veepai**, extraídos
del XAPK oficial y **sin modificar**. Se incluyen con el único propósito de
**interoperabilidad** con hardware que el usuario ha comprado. No están cubiertos por la
licencia MIT, no se redistribuye el APK original y este proyecto no tiene ninguna relación
con el fabricante.

Si vas a publicar este repositorio, revisa esa decisión: la alternativa es **no versionar
los `.so`** y pedir al usuario que los extraiga de su propio XAPK (o de su propio móvil).
