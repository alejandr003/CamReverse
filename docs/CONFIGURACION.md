# Configuración — todas las claves, explicadas

> 🌐 **Español** · [English](en/CONFIGURATION.md)

CamRevert necesita **dos juegos de credenciales distintos** y varios identificadores. Es la
parte que más confunde, así que aquí está todo junto y con los nombres que verás en el
diálogo de la app.

> **Resumen de una línea**: `CAMPASSWORD` es la contraseña de **la cámara** (vídeo) y
> `PASSWORD` es la de **tu cuenta Eye4** (autorizar órdenes). No son la misma.

---

## 1. Los dos caminos para configurar

### A) Archivo de claves — `secrets.properties` (el `.env` del proyecto)

Se lee **al compilar** y sus valores se inyectan en `BuildConfig` como valores de fábrica
(es decir, la primera vez que abres la app ya están puestos).

```powershell
Copy-Item secrets.properties.example secrets.properties
notepad secrets.properties
```

- También se acepta llamarlo **`.env`** en la raíz (misma sintaxis `CLAVE=VALOR`). Si
  existen los dos, gana `secrets.properties`.
- Los dos nombres están en `.gitignore`: **nunca** se suben a GitHub.
- Sin ese archivo el proyecto **compila igual**, con valores vacíos.

### B) Desde el móvil — diálogo de *Configuración*

Botón de ajustes de la barra superior. Todo se guarda en `SharedPreferences` locales
(`camrevert_config`), 100% local, y la app reconecta al guardar.

Copia de seguridad rápida:

```powershell
adb shell "su -c 'cat /data/data/com.lgsusdev.camrevert/shared_prefs/camrevert_config.xml'"
```

---

## 2. Tabla completa de claves

Las tres columnas son: **nombre con el que te lo explico**, la **clave del archivo** y el
**campo del diálogo**.

### 2.1 Tu cámara

| Nombre | Clave en `secrets.properties` | Campo en Configuración | Qué es y de dónde sale |
|---|---|---|---|
| `TU_IP` | `cam.ip` | IP | IP de la cámara en tu red wifi. La ves en el router, en la app oficial, o con el botón **«Escanear»** del diálogo. |
| **`VUID`** | `cam.vuid` (y `cam.uid`) | VUID / UID | Identificador de la cámara: **13 caracteres** empezando por `VE`. Impreso en la etiqueta del equipo y visible en la app oficial. Es con lo que el SDK hace el *rendezvous*. |
| `ID P2P` | `cam.p2pDid` | ID P2P | **ID P2P real, 17 caracteres** (suele empezar por `VPTN`). Es distinto del VUID y es **obligatorio**: el SDK solo monta el túnel si mide exactamente 17. |
| `DAS` | `cam.dasAddress` | DAS | Dirección P2P del equipo: `DAS-` + 128 caracteres hex = **132**. El SDK la exige como `serverParam`. |
| `CAMUSER` | `cam.user` | Usuario | Usuario de **la cámara**. En estos modelos casi siempre `admin`. |
| **`CAMPASSWORD`** | `cam.password` | Contraseña | **Contraseña de la cámara**: la que pide la app oficial para ver el vídeo. Se usa para RTSP y para el `login()` del túnel P2P. |
| `PORT_FIXED` | `cam.portFixed` | Puerto RTSP lente fija | `10555` por defecto. |
| `PORT_PTZ` | `cam.portPtz` | Puerto RTSP PTZ | `10554` por defecto. |

### 2.2 Tu cuenta Eye4

| Nombre | Clave en `secrets.properties` | Campo en Configuración | Qué es |
|---|---|---|---|
| **`TUCORREO`** | `cam.accountUser` | Cuenta EYE4 (email) | El **email** con el que entras en la app oficial O-KAM Pro. |
| **`PASSWORD`** | `cam.accountPassword` | Clave de la cuenta | La **contraseña de esa cuenta** (no la de la cámara). |

Se usan **solo** en local, para pedir un token por HTTPS a `api.eye4.cn`; ver §3.

---

## 3. Por qué hacen falta `TUCORREO` / `PASSWORD`

La cámara **no ejecuta ninguna orden** (PTZ, luz, sirena…) hasta que se le presenta, dentro
de la misma sesión, un token firmado de tu cuenta Eye4:

```
eye4_authentication.cgi?loginAccount=<userid>&loginToken=<token>&
      → la cámara responde:  result= 0;  eye4_auth=1;
```

Sin eso responde `result= 0;  result=-2;  ` a **todo** CGI que no sea `get_status.cgi`:
el vídeo se ve, pero el domo no se mueve. Esto está **verificado en hardware real**
(ver [`HALLAZGOS-PTZ.md`](HALLAZGOS-PTZ.md)).

El token se obtiene así, en cada conexión y sin guardarlo:

| Paso | Petición |
|---|---|
| 1 | `POST https://api.eye4.cn/user/summary` — `name=<TUCORREO>&oemid=VSTC` → `{"userid":…}` |
| 2 | `GET https://api.eye4.cn/login/token?userid=<id>&password=<md5(PASSWORD)>&type=PC` → `{"token":"…"}` |

Consecuencias prácticas:

- **Es la única conexión a Internet** de la app. El vídeo nunca sale de tu red.
- Si dejas `TUCORREO`/`PASSWORD` vacíos, la app funciona igual pero los controles no
  responderán; la consola P2P avisa con `SIN TOKEN DE CUENTA`.
- La contraseña de la cuenta **se convierte en MD5** en el móvil para la petición; se manda
  igual que lo hace el visor oficial sobre HTTPS. Si te preocupa, usa una contraseña
  distinta para esa cuenta.

---

## 4. Cómo obtener el ID P2P y la dirección `DAS-`

La app **no** los adivina: los baja la app oficial de su nube. Dos formas:

### A) De las preferencias de la app oficial (Android con root)

```bash
# El archivo contiene el JSON de p2p_did, con el ID y la address (DAS-...)
adb shell "su -c 'cat /data/data/com.okampro.oksmart/shared_prefs/FlutterSharedPreferences.xml'"
```

Busca las claves que acaban en `_p2p_did`. De ahí salen `cam.p2pDid` y `cam.dasAddress`.

### B) De la API de cuentas (sin root, con `curl`)

```bash
# 1) userid
curl -s -X POST "https://api.eye4.cn/user/summary" \
     -H "client_version: 10.0.1" -H "oemid: VSTC" \
     --data-urlencode "name=TUCORREO" --data "oemid=VSTC"

# 2) token (md5 de la clave)
curl -s "https://api.eye4.cn/login/token?userid=<id>&password=<md5(PASSWORD)>&type=PC" \
     -H "client_version: 10.0.1"

# 3) lista de dispositivos (uid, nickname...)
curl -s "https://api.eye4.cn/PC/device/show?userid=<id>&pwd=<md5(PASSWORD)>" \
     -H "client_version: 10.0.1"
```

En el equipo probado, `PC/device/show` devolvió el `uid` y el `nickname`, pero **no** el ID
P2P ni la `DAS`: esos se sacan de la opción **A**.

---

## 5. Errores típicos de configuración

| Mensaje / síntoma | Qué pasa |
|---|---|
| `AVISO: p2pDid mide N, no 17` (consola) | El ID P2P está mal copiado o cortado. Debe medir **17**. |
| `Ninguna combinación abrió el túnel` | `p2pDid` o `dasAddress` incorrectos. La consola lista cada intento con su código de error. |
| `SIN TOKEN DE CUENTA` | Faltan `TUCORREO`/`PASSWORD`, o la cuenta no existe / la clave es incorrecta. |
| `result=-2` en todas las respuestas | Sesión sin autorizar (misma causa que arriba). |
| `get_status.cgi` → `result=-1;vuid=…` | **Normal** en este firmware. No es un fallo de credenciales. |
| El vídeo se ve pero sin audio | El audio del intercomunicador depende del túnel y de la recepción por `ClientCommandListener` (limitación conocida). |
