package com.lgsusdev.camrevert.p2p

import android.os.SystemClock
import com.lgsusdev.camrevert.data.CamConfig
import com.veepai.vp_log.VPLogApi
import com.vstarcam.JNIApi
import com.vstarcam.app_p2p_api.ClientCommandListener
import com.vstarcam.app_p2p_api.ClientIpsListener
import com.vstarcam.app_p2p_api.ClientReleaseListener
import com.vstarcam.app_p2p_api.ClientStateListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Direcciones PTZ de `decoder_control.cgi?command=N&onestep=0&`.
 *
 * Mapeo **Foscam / VStarcam estándar**: 4 ejes, cada uno con **su propio** comando de
 * parada. Confirmado por dos vías independientes:
 *
 *  - Binario: O-KAM Pro usa `command` 0..7, **no existe `command=8`**, y contiene los
 *    pares de crucero `26/27` (vertical) y `28/29` (horizontal), que son exactamente
 *    los del protocolo Foscam.
 *  - UI de O-KAM Pro: el panel PTZ tiene **solo 4 flechas + centro** (sin diagonales),
 *    con «Corrección PTZ» y «Crucero» como acciones aparte. El firmware, por tanto, no
 *    expone movimientos diagonales.
 *
 * | Código | Acción | Parada |
 * |---|---|---|
 * | 0 | Arriba | 1 |
 * | 2 | Abajo | 3 |
 * | 4 | Izquierda | 5 |
 * | 6 | Derecha | 7 |
 *
 * `25` = centrar/corregir cabezal. La app permite invertir X/Y por si el modelo
 * concreto numerase los ejes al revés.
 */
enum class PtzDirection(val code: Int, val stopCode: Int) {
    UP(0, 1),
    DOWN(2, 3),
    LEFT(4, 5),
    RIGHT(6, 7);

    fun mapped(invertX: Boolean, invertY: Boolean): PtzDirection = when (this) {
        UP -> if (invertY) DOWN else UP
        DOWN -> if (invertY) UP else DOWN
        LEFT -> if (invertX) RIGHT else LEFT
        RIGHT -> if (invertX) LEFT else RIGHT
    }
}

/**
 * Cadena de comandos CGI. **Ninguna lleva `GET /` ni `loginuse`/`loginpas`**:
 * `client_write_cgi()` de `libOKSMARTPPCS.so` envuelve la petición con
 * `GET /%sloginuse=%s&loginpas=%s&user=%s&pwd=%s&` usando las credenciales que
 * se pasaron a `login()`, y exige que la cadena termine en `&` o `?` (no inserta
 * ningún separador antes de `loginuse`).
 */
object Cgi {
    // --- PTZ ---
    const val DECODER = "decoder_control.cgi"
    const val SET_SENSOR_PRESET = "set_sensor_preset.cgi"
    const val GET_PRESET = "get_sensor_preset.cgi"

    /**
     * Base de "ir a preset": `decoder_control.cgi?command=(BASE+id)&onestep=0&`.
     * **Inferida** (no hay cadena literal en el binario); ver AGENTS.md §5.1.
     */
    const val GOTO_PRESET_BASE = 64

    // --- Estado y salud ---
    const val GET_STATUS = "get_status.cgi?"
    const val GET_CAMERA_PARAMS = "get_camera_params.cgi?"
    const val REBOOT = "reboot.cgi?"
    const val GET_STATUS_NAME = "get_status.cgi?name="

    // --- Visión nocturna / luz ---
    const val CAMERA_CONTROL = "camera_control.cgi"
    const val WHITE_LED = "set_whiteled_value.cgi"
    const val GET_WHITE_LED = "get_whiteled_value.cgi?"

    // --- Sirena y estrobo (trans_cmd_string) ---
    const val TRANS_CMD = "trans_cmd_string.cgi"

    // --- Alarma / detección ---
    const val SET_ALARM = "set_alarm.cgi"

    // --- Audio / llamada ---
    const val CALL_STATUS = "callstatus.cgi"
    const val VOICE_TYPE = "trans_cmd_string.cgi"
    const val AUDIO_PLAY_MODE = "trans_cmd_string.cgi"

    // --- MicroSD ---
    const val GET_RECORD = "get_record.cgi?"
    const val GET_RECORD_FILE = "get_record_file.cgi"
    const val SET_RECORD_SCH = "set_recordsch.cgi?"
    const val FORMAT_SD = "set_formatsd.cgi?"
    const val RECORD_FASTPLAY = "record_fastplay.cgi"
    const val LIVESTREAM = "livestream.cgi"
    const val DEL_FILE = "del_file.cgi"
    const val SD_MARK = "trans_cmd_string.cgi"

    // --- Inicialización P2P ---
    const val LOGIN_PROBE = "get_status.cgi?name=admin&"

    /**
     * Preludio **obligatorio** de cada sesión: la cámara no ejecuta ningún CGI hasta
     * que se le presenta el token de la cuenta EYE4. Ver [Eye4Auth].
     *
     * Se envía completo como `eye4_authentication.cgi?loginAccount=<userId>&loginToken=<token>&`.
     */
    const val EYE4_AUTH = "eye4_authentication.cgi"
}

/** Flujo de respuestas CGI recibidas por `ClientCommandListener`. */
data class CgiResponse(val raw: String)

/**
 * Evento de la **consola diagnóstica P2P**: cada código de `stateListener`,
 * cada retorno de `create/connect/login`, y las cargas de `ClientCommandListener`.
 */
data class P2pEvent(
    val ts: Long = System.currentTimeMillis(),
    val origin: String,
    val detail: String,
) {
    val clock: String
        get() = "%02d:%02d:%02d.%03d".format(
            (ts / 3600000) % 24, (ts / 60000) % 60, (ts / 1000) % 60, ts % 1000
        )
}

enum class ConnState { DISCONNECTED, CONNECTING, ONLINE, ERROR }

/**
 * Singleton dueño del túnel P2P.
 *
 * ### Evidencia nativa (desensamblado de `libOKSMARTPPCS.so`)
 *
 * - `create(did, did2)` (`0x3fc6c` → `client_create` `0x41ac0`): `did` debe medir
 *   **1..63** caracteres; **`did2` es un *segundo DID* (`handle+0x40`), no una
 *   licencia ni un `serverParam`**, y solo se usa si mide **exactamente 17**
 *   (`0x41c30 cmp x0,#0x11`); cualquier otra longitud deja `handle+0x90 = NULL`
 *   (`0x41cc8`), que es exactamente la misma rama que `did2 = NULL`.
 *   **El fix del túnel pasa el ID P2P real de 17 chars (`CamConfig.p2pDid`) como
 *   `did2` en los intentos `vpid/…`**: con `iface2 != NULL`, `client_connect`
 *   construye un `serverStr` **no nulo** (`serverStr = target`, `0x42428`) y entra en
 *   la ruta buena — el log `client_connect server_param server` (`0x3fe1c`) y
 *   `ConnectByServer VP` que solo aparecen en la app oficial cuando conecta
 *   (`result:1`). Los intentos del bloque antiguo siguen pasando `""` (nunca `null`)
 *   para no exponer un `jstring` nulo al JNI.
 *   La licencia PPCS del original (InitString de 108 chars, ver §10.9) se inyecta en
 *   `PPCS_Initialize(..., APILicense)` **dentro** de la vtable de la interfaz: no
 *   tiene ningún camino desde los 20 JNI de `JNIApi`, así que no hay nada que
 *   pasarle a `create`.
 * - `connect(handle, mode, target, p2pType, cachedIps)` (`0x40320` →
 *   `client_connect` `0x42290`): **`target` es el TargetID (DID/VUID) y `cachedIps`
 *   es la lista de IPs en caché (`net_ips`)**. `rc >= 0` es el *session handle*
 *   (éxito, `handle+0x204 = 1` en `0x430a0`); los errores PPCS de −1..−20 se
 *   normalizan a **`4`** (`0x4311c`). Un segundo `connect()` sobre el mismo handle
 *   devuelve `3` sin tocar la red (`0x422c4`), por eso la cascada crea un handle
 *   nuevo en cada intento (ver [connect]).
 * - `clientSetVuid(handle, vuid)` copia el VUID en `handle+0x210` (máx. 64) y
 *   `client_connect()` lo lee desde ahí: **se llama antes de `connect()`**.
 * - `writeCgi(handle, cgi, X)`: **X es un timeout en milisegundos**, no la longitud
 *   de la cadena (la longitud real la calcula el propio nativo con `strlen`).
 *   `client_login()` lo invoca con `5000`. Pasar `cgi.size` (~40) producía un
 *   timeout de 40 ms y los comandos se perdían. Como exige `handle+0x204 != 0` y
 *   lo comprueba antes de esperar, sirve además de **sondeo barato** para saber si
 *   `connect()` montó de verdad el túnel.
 * - Orden obligatorio (flags internos): `create → clientSetVuid → connect → probe →
 *   login → writeCgi`.
 */
object P2PControlManager {

    private const val TAG = "CamRevert-P2P"
    private const val TIMEOUT_MS = 5000
    private const val MODE_DEFAULT = 126 // 0x7e, el valor por defecto que usa el plugin

    /** Modos especiales con rama propia en el nativo (`0x42c70` y `0x43014`). */
    private const val MODE_123 = 123
    private const val MODE_121 = 121

    /** Qué se usa como 3.er argumento de `connect()` (el TargetID). */
    private const val TARGET_DID = 0
    private const val TARGET_VUID = 1
    private const val TARGET_IP = 2

    /**
     * El **ID P2P real** ([CamConfig.p2pDid], 17 chars). Es lo que pasa O-KAM Pro como
     * TargetID (`clientId` en su log) cuando conecta con éxito.
     */
    private const val TARGET_VPID = 3

    /**
     * La dirección `DAS-…` de [CamConfig.dasAddress]. Es el `serverParam` que la capa
     * `hl_p2p_*` del SDK valida antes de montar la sesión (ver kdoc de
     * [CamConfig.dasAddress]); sin ella el error es **−101**.
     */
    private const val TARGET_DAS = 4

    /**
     * Timeout del `writeCgi` de sondeo. Solo se consume si el túnel **está** montado
     * (si `handle+0x204 == 0` el nativo devuelve `false` sin esperar).
     */
    private const val TUNNEL_PROBE_MS = 1500

    /**
     * Backoff del supervisor de reconexión P2P (punto 6). Empieza en 2 s —no en 3—
     * porque el caso típico es un reinicio de la cámara, que tarda ~20 s en volver:
     * llegar antes a la primera prueba ahorra una espera muerta.
     */
    private const val AUTO_RETRY_BASE_MS = 2_000L
    private const val AUTO_RETRY_MAX_MS = 15_000L

    /**
     * **Vigilante de túnel sordo** (§5.15). El SDK puede quedarse «sordo»: `writeCgi`
     * devuelve `true` (el flag `handle+0x204` sigue a 1 y `stateListener` no sale de
     * `2`), el RTSP sigue reproduciendo y la app pinta «en línea», pero **no llega ni
     * una respuesta** — `commandListener` deja de dispararse. Medido en el equipo
     * (2026-10-17): lo provoca `record_fastplay.cgi?ctrl=1&playlist=<clip>&`, y ni
     * `ctrl=0` ni ninguna otra orden lo recuperan; solo reiniciar la app.
     *
     * Como `stateListener` no baja de `2`, el supervisor de reconexión (§7.1) **nunca**
     * se entera. Este vigilante cubre ese hueco: si pasa [CGI_ANSWER_TIMEOUT_MS] sin
     * respuesta, manda la sonda [CGI_WATCHDOG_PROBE] (`get_status.cgi?`, el único CGI
     * que contesta incluso sin autorizar, §5.14); si la sonda tampoco contesta en
     * [CGI_PROBE_GRACE_MS], el túnel está sordo y se fuerza la reconexión.
     *
     * **Coste en reposo: cero tráfico.** El tick solo actúa si hay una petición
     * pendiente, y `pendingSinceMs` se limpia con cada respuesta.
     *
     * **Ajuste de los tiempos (medido, 2026-10-17).** La primera versión usaba 4 s y dio
     * un **falso positivo**: con la sesión sana, un `get_status.cgi?` de 2,5 KB tardó
     * **4,46 s** en contestar (medido en el equipo: envío a las 23:51:10.581, respuesta a
     * las 23:51:15.044), así que el vigilante mandó una sonda de más. Con 8 s + 6 s de
     * margen hay **9,5 s de colchón** sobre esa latencia, y el túnel sordo —que no
     * contesta **nunca**— se detecta igualmente a los 14 s de silencio.
     */
    private const val CGI_ANSWER_TIMEOUT_MS = 8_000L
    private const val CGI_PROBE_GRACE_MS = 6_000L
    private const val CGI_WATCHDOG_TICK_MS = 2_000L

    /** Sonda del vigilante: contesta siempre, autorizada o no (§5.14). */
    private const val CGI_WATCHDOG_PROBE = "get_status.cgi?"

    /** Techo de reconexiones forzadas por túnel sordo **en toda la ejecución**. */
    private const val DEAF_RECOVERY_MAX = 1

    /**
     * Tabla de normalización de `client_connect` (leída del binario en `0x28c00`).
     *
     * `client_connect` no devuelve nunca el error PPCS crudo: hace
     *
     * ```c
     * if (rc < 0) {                        // 0x43074  tbnz x24,#0x3f
     *     *(u8*)(h+0x204) = 0;             // 0x43100  ★ flag SIEMPRE limpiado
     *     if (crudo >= -19) rc = TABLA[-crudo];   // 0x4310c → 0x4311c
     *     else              rc = 4;               // 0x4311c  cmn x24,#0x14 ; b.ls
     * }
     * ```
     *
     * Índice 0 ⇒ error crudo **−19**; índice 18 ⇒ crudo **−1**.
     * Así, `rc` negativo ⇒ error crudo, y `rc` 4/6/7/8/9/10 ⇒ error traducido.
     *
     * **`rc=4` jamás significa éxito**: `4` es el cajón de sastre (lo producen los
     * crudos −2, −5, −7, −11…−16, −18 **y todo ≤ −20**). El único test que separa
     * «4 = handle 4» de «4 = error» es `writeCgi`, que exige `handle+0x204 != 0`.
     *
     * Excepción: el **modo 121** sale por `0x43030 mov w24,w0` y se salta la
     * normalización, así que su `rc` es el código crudo tal cual (por eso ese intento
     * enseña `-5` y los demás `4` — es el mismo fallo, traducido).
     */
    private val PPCS_ERR_TABLE = intArrayOf(
        10, 4, 9, 4, 4, 4, 4, 4, 4, 9, 6, 6, 4, 7, 4, 6, 8, 4, 9,
    )

    /** Inversa de [PPCS_ERR_TABLE]: qué códigos **crudos** producen este `rc`. */
    private fun rawPpcsErrors(rc: Int): String {
        val raws = PPCS_ERR_TABLE.indices
            .filter { PPCS_ERR_TABLE[it] == rc }
            .map { it - 19 } // índice 0 ⇒ −19, índice 18 ⇒ −1
        if (raws.isEmpty()) return "la tabla 0x28c00 no produce $rc"
        val base = raws.joinToString(", ")
        // El 4 es el único resultado que también sale de la rama "crudo ≤ −20", que la
        // tabla no recorre, así que la inversa no puede enumerarlo por completo.
        return if (rc == 4) "$base, o cualquier crudo ≤ -20" else base
    }

    /** Traduce el `rc` devuelto por `JNIApi.connect` a lo que significa de verdad. */
    private fun explainRc(rc: Int, mode: Int): String = when {
        rc >= 0 ->
            "session handle $rc >= 0 => connect aceptado " +
                "(0x430a0 pone handle+0x204=1); decide el sondeo de abajo"

        mode == MODE_121 ->
            "error PPCS CRUDO = $rc — el modo 121 sale por 0x43030 (mov w24,w0) " +
                "y se salta la normalización, asi este es el codigo real del SDK"

        rc == 4 ->
            "error PPCS normalizado a 4; crudo posible: ${rawPpcsErrors(rc)} " +
                "— NO es exito, el flag handle+0x204 esta limpio (0x43100)"

        else ->
            "error PPCS normalizado a $rc por la tabla 0x28c00 => crudo " +
                rawPpcsErrors(rc)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initDone = AtomicBoolean(false)
    private val connectMutex = Mutex()

    var nativeAvailable = true
        private set

    /**
     * `true` cuando `PPCS_Initialize(InitString)` devolvió éxito y el SDK P2P
     * quedó arrancado. Sin esto `client_connect` devuelve -5 sin tocar la red.
     */
    @Volatile
    var ppcsInitialized = false
        private set

    @Volatile
    private var handle: Long = 0L

    // ---------------------------------------------------------------------
    // Autorización de sesión (cuenta EYE4)
    // ---------------------------------------------------------------------

    /**
     * `true` cuando la cámara ya confirmó `eye4_auth=1` en esta sesión, es decir,
     * cuando **acepta órdenes**. Sin esto el túnel está montado (el vídeo va por RTSP
     * aparte) pero todo CGI que no sea `get_status.cgi` devuelve `result=-2`.
     */
    @Volatile
    var authorized: Boolean = false
        private set

    /**
     * Token de cuenta vigente. Se pide una vez por conexión y se reutiliza; se tira al
     * desconectar porque el servicio lo firma por sesión.
     */
    @Volatile
    private var accountCredential: Eye4Auth.Credential? = null

    /**
     * Puerta que completa [commandListener] cuando llega el `eye4_auth=…` de la
     * respuesta: así [authenticateSession] **espera el veredicto de la cámara** en vez
     * de dar por hecho que el preludio funcionó.
     */
    @Volatile
    private var authGate: CompletableDeferred<Boolean>? = null

    /**
     * `true` solo tras una autorización correcta. Sirve para distinguir el `result=-2`
     * «normal» de antes del preludio (no hay que hacer nada) del `result=-2` que
     * delata que a una sesión **ya autorizada** se le cayó el token: en ese caso se
     * reautoriza una vez, y no más (el flag se desarma hasta la próxima autorización).
     */
    private val reauthArmed = AtomicBoolean(false)

    @Volatile
    private var authInFlight = false

    /** Tiempo máximo que se espera el `eye4_auth=…` antes de dar la sesión por no autorizada. */
    private const val AUTH_TIMEOUT_MS = 8_000L

    /** `eye4_auth=1` (admite espacios: la cámara responde `result= 0;  eye4_auth=1;  `). */
    private val EYE4_AUTH_RE = Regex("eye4_auth\\s*=\\s*(\\d)")

    /** `result=-2` / `result= -2`: comando rechazado por falta de autorización. */
    private val RESULT_MINUS_2 = Regex("result\\s*=\\s*-2\\b")

    /**
     * Último eje movido. En el esquema Foscam/VStarcam **cada eje tiene su propio**
     * comando de parada (0→1, 2→3, 4→5, 6→7), así que `stopPtz()` debe repetir la
     * parada del eje que se está moviendo, no una parada genérica.
     */
    private var lastPtzDir: PtzDirection? = null

    private val _state = MutableStateFlow(ConnState.DISCONNECTED)
    val state: StateFlow<ConnState> = _state.asStateFlow()

    private val _responses = MutableSharedFlow<CgiResponse>(extraBufferCapacity = 64)
    val responses: SharedFlow<CgiResponse> = _responses.asSharedFlow()

    // 512 y no 256: el log nativo (VPLogApi) añade ~15 líneas por intento de la cascada,
    // y con un buffer corto tryEmit() descartaría justo las líneas de error que interesan.
    private val _events = MutableSharedFlow<P2pEvent>(replay = 0, extraBufferCapacity = 512)
    /** Consola diagnóstica en vivo: todo código y toda carga que ve la app. */
    val events: SharedFlow<P2pEvent> = _events.asSharedFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private fun log(origin: String, detail: String) {
        android.util.Log.d(TAG, "[$origin] $detail")
        _events.tryEmit(P2pEvent(origin = origin, detail = detail))
    }

    // ---------------------------------------------------------------------
    // Punto 6 — supervisor de reconexión automática
    // ---------------------------------------------------------------------

    /**
     * `true` mientras alguien *quiere* la sesión en pie: la conexión automática del
     * arranque o un toque en «Conectar». Un [disconnect] explícito lo baja, y con él
     * el supervisor se calla (no se puede quedar reconectando a un usuario que acaba
     * de pedir que se desconecte).
     */
    @Volatile
    private var userWantsOnline = false

    /** Job del supervisor: uno solo a la vez. */
    private var retryJob: Job? = null

    /** Reintentos seguidos del supervisor, para el backoff exponencial. */
    private var retryAttempts = 0

    private val _retrying = MutableStateFlow(false)

    /**
     * `true` mientras hay un reintento en vuelo. La UI lo usa para no enseñar un
     * «Error P2P» seco: mientras esto esté activo, la barra dice «Reconectando…»
     * (punto 6).
     */
    val retrying: StateFlow<Boolean> = _retrying.asStateFlow()

    /**
     * **Esto era el agujero del punto 6.** Existía [scheduleRetry] pero **nadie la
     * llamaba**, así que cualquier caída del túnel (reinicio de la cámara, WiFi,
     * `client_connect_break`) dejaba la app en `ERROR` para siempre y había que
     * pulsar «Conectar» a mano — justo lo que se describía como «tengo que cerrar y
     * abrir la app».
     *
     * Reintenta la **cascada completa** con backoff 2→4→8→15 s (tope), se reinicia al
     * volver a `ONLINE` y es idempotente: si ya hay un reintento en vuelo no encola
     * otro. Se apoya en [connect], que ya sabe crear un handle nuevo por intento.
     */
    private fun scheduleAutoRetry(reason: String) {
        if (!userWantsOnline || !nativeAvailable) return
        if (retryJob?.isActive == true) return
        _retrying.value = true
        retryJob = scope.launch {
            try {
                // **Bucle, no recursión ni cancelación desde dentro.** La versión
                // anterior llamaba a `cancelAutoRetry()` al tener éxito `connect()`:
                // como el supervisor *es* la corrutina que ejecuta `connect()`, se
                // cancelaba a sí misma y **mataba el preludio `eye4_authentication.cgi`
                // a mitad** (visto en el reinicio real: `[eye4] ERROR
                // JobCancellationException: StandaloneCoroutine was cancelled`), con lo
                // que el túnel quedaba montado pero **sin autorizar** y los controles
                // muertos. El bucle sale solo cuando el estado es `ONLINE`.
                var first = true
                // `authorized` entra en la condición además de `ONLINE`: el paso a ONLINE
                // llega por el `stateListener` (hilo nativo) y puede tardar unos ms más
                // que el retorno de `connect()`, así que sin él el bucle lanzaba una
                // segunda cascada **redundante** (medido: dos `eye4_authentication.cgi`
                // seguidos, con sus dos tokens HTTPS, tras cada túnel sordo).
                while (userWantsOnline && _state.value != ConnState.ONLINE && !authorized) {
                    val attempt = retryAttempts
                    val delayMs = (AUTO_RETRY_BASE_MS shl attempt.coerceAtMost(5))
                        .coerceAtMost(AUTO_RETRY_MAX_MS)
                    retryAttempts = attempt + 1
                    if (first) {
                        log("retry", "reconexión automática en ${delayMs} ms: $reason")
                        first = false
                    } else {
                        log("retry", "nuevo intento en ${delayMs} ms (#$retryAttempts)")
                    }
                    delay(delayMs)
                    if (!userWantsOnline) break
                    if (_state.value == ConnState.ONLINE || authorized) break
                    connect()
                }
            } finally {
                // Limpieza solo si este job sigue siendo el vigente: si `disconnect()`
                // ya lanzó otro, no se le pisa la referencia.
                if (retryJob === coroutineContext[Job]) {
                    retryJob = null
                    retryAttempts = 0
                    _retrying.value = false
                }
            }
        }
    }

    /**
     * Solo para un **corte pedido por el usuario** ([disconnect]): un `ONLINE` deja que
     * el bucle del supervisor termine por su cuenta, así que nunca se le llama desde un
     * `connect()` que pueda estar corriendo dentro del propio job.
     */
    private fun cancelAutoRetry() {
        retryAttempts = 0
        retryJob?.cancel()
        retryJob = null
        _retrying.value = false
    }

    // ---------------------------------------------------------------------
    // Punto 3 — vigilante de túnel sordo (§5.15)
    // ---------------------------------------------------------------------

    /**
     * Marca de tiempo (ms de `elapsedRealtime`) de la **primera** orden enviada que
     * todavía no ha recibido respuesta. `0` = nada pendiente.
     *
     * Se fija en [sendRaw] y **no** se reescribe mientras siga pendiente: así la sonda
     * del vigilante no reinicia la ventana que está midiendo. Cualquier carga que
     * entre por [commandListener] la limpia.
     */
    @Volatile
    private var pendingSinceMs = 0L

    /** `true` si, dentro de la ventana pendiente, ya se disparó la sonda. */
    @Volatile
    private var probeSent = false

    /**
     * Reconexiones forzadas por túnel sordo **en esta ejecución** de la app. El techo es
     * [DEAF_RECOVERY_MAX] = 1 y **no se reinicia**: cada recuperación abandona un handle
     * ([abandonHandle]) que deja su sesión viva en el equipo, y está medido (2026-10-18)
     * que **con dos abandonos la cascada ya no reconstruye el túnel** (el equipo/P2P deja
     * de admitir sesiones nuevas): dos recuperaciones dejan la app peor que una. Con una
     * sola, la reconstrucción sí funciona (túnel confirmado + `eye4_auth=1` en ~10 s).
     */
    private var deafRecoveries = 0

    /** Con el techo agotado se deja de sondear y de reconectar hasta reiniciar la app. */
    @Volatile
    private var watchdogMuted = false

    /**
     * Job del vigilante. Uno solo, creado por [startWatchdog] desde [init]; el tick se
     * sale solo en cuanto anula `pendingSinceMs` (túnel sano) o dispara la reconexión.
     */
    private var watchdogJob: Job? = null

    private fun startWatchdog() {
        if (watchdogJob?.isActive == true) return
        watchdogJob = scope.launch {
            while (true) {
                delay(CGI_WATCHDOG_TICK_MS)
                try {
                    watchdogTick()
                } catch (t: Throwable) {
                    log("watchdog", "ERROR ${t.javaClass.simpleName}: ${t.message}")
                }
            }
        }
    }

    /**
     * Abre la ventana pendiente si no había ninguna. **Idempotente a propósito**: la
     * sonda del propio vigilante vuelve a pasar por aquí y no debe mover el reloj.
     */
    private fun markCgiPending() {
        if (pendingSinceMs != 0L) return
        pendingSinceMs = SystemClock.elapsedRealtime()
        probeSent = false
    }

    private fun clearCgiPending() {
        pendingSinceMs = 0L
        probeSent = false
    }

    private suspend fun watchdogTick() {
        if (watchdogMuted) return
        // El vigilante solo vigila una sesión **utilizable**: si no hay túnel o la
        // sesión aún no está autorizada, el preludio y su propio timeout mandan (§5.14).
        if (_state.value != ConnState.ONLINE || !authorized) {
            clearCgiPending()
            return
        }
        val since = pendingSinceMs
        if (since == 0L) return
        val waited = SystemClock.elapsedRealtime() - since
        if (!probeSent && waited >= CGI_ANSWER_TIMEOUT_MS) {
            probeSent = true
            log(
                "watchdog",
                "sin respuesta a la última orden en $waited ms → sonda $CGI_WATCHDOG_PROBE"
            )
            sendRaw(CGI_WATCHDOG_PROBE)
            return
        }
        if (probeSent && waited >= CGI_ANSWER_TIMEOUT_MS + CGI_PROBE_GRACE_MS) {
            log(
                "watchdog",
                "SONDA SIN RESPUESTA tras ${waited} ms → túnel sordo"
            )
            clearCgiPending()
            // Techo por ejecución: cada recuperación abandona un handle que deja su sesión
            // viva en el equipo, y con dos la cascada ya no reconstruye (ver [deafRecoveries]).
            if (deafRecoveries >= DEAF_RECOVERY_MAX) {
                watchdogMuted = true
                log(
                    "watchdog",
                    "túnel sordo reincidente ($deafRecoveries recuperaciones ya usadas) → " +
                        "se abandona el handle sin reconectar y se avisa"
                )
                // Se abandona también aquí para que un «Conectar» manual no intente
                // destruir el handle sordo (eso era el SIGABRT), pero **sin** relanzar la
                // cascada: reconstruir ahora es justo lo que no funciona.
                abandonHandle()
                _state.value = ConnState.ERROR
                scope.launch {
                    _messages.emit(
                        "El túnel P2P no responde: cierra y vuelve a abrir la app"
                    )
                }
                return
            }
            deafRecoveries++
            log("watchdog", "reconexión forzada #$deafRecoveries de $DEAF_RECOVERY_MAX")
            forceReconnect()
        }
    }

    /**
     * Corta un túnel que está **sordo pero se declara vivo** y deja que el supervisor
     * (§7.1) rehaga la cascada. A diferencia de [disconnect], **no** baja
     * [userWantsOnline]: el usuario sigue queriendo la sesión, es el túnel el que está
     * roto. Tampoco llama a [cancelAutoRetry] — meterlo aquí reproduciría el bug de
     * autocancelación documentado en [scheduleAutoRetry].
     *
     * **No cierra el handle por la vía nativa.** Tres intentos medidos, tres abortos:
     *
     * | Intento (2026-10-17/18) | Cómo cerraba | Resultado |
     * |---|---|---|
     * | 1.º | `clientConnectBreak` + `disconnect` + `destroy` | **SIGABRT** |
     * | 2.º | `disconnect` + `destroy` | **SIGABRT** |
     * | 3.º (actual) | nada: [abandonHandle] | pendiente de confirmar en equipo |
     *
     * El aborto es siempre el mismo:
     *
     * ```
     * Abort message: 'FORTIFY: pthread_mutex_lock called on a destroyed mutex (0x76388a32c5e0)'
     * signal 6 (SIGABRT)   Thread-22   com.lgsusdev.camrevert
     * backtrace: … libc (pthread_mutex_lock) ← libtcb.so ← [anon:Mem_0x20000000]
     * ```
     *
     * `libtcb.so` + `[anon:Mem_0x20000000]` es el **traductor ARM→x86 del emulador**: el
     * aborto nace dentro de `libOKSMARTPPCS.so`. Y el mutex `0x…c5e0` es exactamente
     * `handle 0x76388a32c404 + 0x1dc`, el que inicializa `client_create`
     * (`pthread_mutex_init(p + 0x1dc)`): el hilo del SDK está atascado con el flujo del
     * fichero, despierta **después** de que `destroy` libere esa memoria y revienta al
     * bloquear el mutex ya destruido. Quitar `clientConnectBreak` no bastó: el
     * responsable es el `destroy`.
     */
    private suspend fun forceReconnect() {
        connectMutex.withLock {
            val h = handle
            log(
                "watchdog",
                "abandonando el handle sordo 0x${java.lang.Long.toHexString(h)} " +
                    "(SIN llamadas nativas: disconnect/destroy de un handle sordo abortan)"
            )
            abandonHandle()
            _state.value = ConnState.ERROR
        }
        log("watchdog", "intención intacta; el supervisor reconstruye la cascada")
        scheduleAutoRetry("watchdog: túnel sordo")
    }

    /**
     * Suelta el handle **solo en el lado Kotlin**: el nativo no se toca.
     *
     * Es la lección de los dos cierres fallidos (ver [forceReconnect]): liberar un handle
     * **sordo** mata la app con `SIGABRT — pthread_mutex_lock called on a destroyed mutex`,
     * porque el hilo del SDK sigue vivo y despierta después del `destroy`.
     *
     * Coste: la struct (664 B) y su hilo quedan colgados hasta que el proceso muera — un
     * caso raro y una sola vez por episodio, frente a un cierre de app garantizado. El
     * túnel nuevo es un `client_create` **nuevo**, así que no reutiliza nada del
     * abandonado.
     */
    private fun abandonHandle() {
        handle = 0L
        authorized = false
        accountCredential = null
        reauthArmed.set(false)
        authGate = null
        clearCgiPending()
    }

    /**
     * **Solo para depuración** ([com.lgsusdev.camrevert.debug.CgiDebugReceiver]). Fuerza la
     * condición de túnel sordo para probar el vigilante sin depender del disparo real, que
     * es **intermitente** (medido: se reprodujo a las 23:43, 23:55 y 00:03 del 2026-10-17/18,
     * y ya no con las mismas órdenes). Abre la ventana pendiente 20 s en el pasado y marca
     * la sonda como enviada, así el siguiente tick declara el túnel sordo y ejecuta la
     * recuperación. Cualquier respuesta real que entre antes cancela la simulación.
     *
     * @return `false` si no hay sesión ONLINE autorizada (no hay nada que simular).
     */
    fun simulateDeafTunnel(): Boolean {
        if (_state.value != ConnState.ONLINE || !authorized) return false
        watchdogMuted = false
        pendingSinceMs = SystemClock.elapsedRealtime() - 20_000
        probeSent = true
        log("watchdog", "[debug] túnel sordo simulado (ventana abierta 20 s atrás)")
        return true
    }

    // ---------------------------------------------------------------------
    // Ciclo de vida
    // ---------------------------------------------------------------------

    fun init() {
        if (!initDone.compareAndSet(false, true)) return
        attachNativeLog()
        try {
            val ok = JNIApi.init(stateListener, commandListener, releaseListener, ipsListener)
            log("init", "JNIApi.init() = $ok  native=$nativeAvailable")
        } catch (e: UnsatisfiedLinkError) {
            nativeAvailable = false
            log("init", "UnsatisfiedLinkError: ${e.message}")
            scope.launch { _messages.emit("Sin librería P2P nativa: solo RTSP") }
            return
        } catch (t: Throwable) {
            nativeAvailable = false
            log("init", "ERROR ${t.javaClass.simpleName}: ${t.message}")
            return
        }
        initializePpcs()
        // Punto 3: vigilante de túnel sordo (§5.15). Sin tráfico en reposo.
        startWatchdog()
    }

    /**
     * Inicializa el SDK P2P con `PPCS_Initialize(InitString)`.
     *
     * **Esto es lo que faltaba para que el túnel se montara.** Todo el XAPK deja
     * el SDK sin inicializar: `PPCS_Initialize` (0x6460c) está exportado pero no
     * lo llama ni `.init_array` (no existe) ni ninguno de los 20 JNI de `JNIApi`.
     * `cs2p2p_PPPP_Initialize` devuelve **-5** cuando recibe `InitString == NULL`
     * (`0x4faa0 cbz x22` → `0x4fcb8 mov w0,#-0x5`), y ese -5 es exactamente el
     * `result:-5` de `client_connect` que hacía fallar **todas** las combinaciones
     * de la cascada (§10.9 de AGENTS.md, ahora resuelto).
     *
     * Devuelve `false` solo si la inicialización falla de verdad; la app sigue
     * funcionando igualmente por RTSP en ese caso.
     */
    private fun initializePpcs() {
        ppcsInitialized = try {
            // La asignación va DENTRO del try: `PpcsShim` hace `System.loadLibrary`
            // en su `<clinit>` y podría lanzar `UnsatisfiedLinkError` al primer acceso.
            PpcsShim.sink = { where, msg -> log(where, msg) }
            PpcsShim.initialize()
        } catch (t: Throwable) {
            log("init", "PPCS_Initialize no disponible: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
        if (ppcsInitialized) {
            log("init", "SDK P2P inicializado · InitString=${if (PpcsShim.hasLicense()) "presente" else "AUSENTE (cam.ppcsLicense)"}")
        } else {
            log("init", "AVISO: el SDK P2P sigue sin inicializar; el túnel no se podrá montar")
        }
    }

    /**
     * Conecta el **log nativo** del SDK P2P con la consola de la app.
     *
     * Sin esto el motivo real de un `connect()` fallido es invisible: el `rc` llega ya
     * normalizado (ver kdoc de [buildAttempts]) y las cadenas que explican el fallo —
     * `ERROR_PPCS_*`, `unknown version:...`, `_se_try_other_netcard() ... net_ips_len=...`
     * — solo se imprimen dentro de `libOKSMARTPPCS.so`.
     *
     * Circuito verificado en `libvp_log.so` (13 KB, `NEEDED` de `libOKSMARTPPCS.so`):
     *
     * ```
     * VPLogApi.setLogListener(l)
     *   → Java_com_veepai_vp_1log_VPLogApi_setListener
     *       struct@0xACD8 = { env, NewGlobalRef(l),
     *                         GetMethodID(GetObjectClass(l), "fileLogListener",
     *                                     "(Ljava/lang/String;)V") }   // literales 0x1101 / 0x1202
     *       vp_print_set_file(log_file_callback)     // .got@0x6BD0 = 0x1914 → 0xACF0
     *
     * client_connect → vp_printf(nivel 4)           // "mov w0, #0x4" en cada log
     *   0x21D8  cmp w19, #4                          // solo el nivel 4 despacha al callback
     *   0x21E8  blr [0xACF0] → log_file_callback
     *              → CallVoidMethod(l, "fileLogListener", texto)
     * ```
     *
     * `setp2pListener` (que es con el que empatja el nombre por similitud) **no sirve**:
     * su cola es `vp_p2p_set_file`, que está vacío (`ret` en `0x1f00`), de modo que
     * `log_p2p_callback` (`0x1a2c`) queda sin ningún llamante.
     *
     * Respaldo: `vp_printf` escribe a logcat con tag `flutter` cuando
     * `nivelGlobal <= nivelMensaje`, y el nivel global arranca en 0 (.bss), así que los
     * mismos mensajes se pueden cazar con `adb logcat -s flutter` si el enlace JNI
     * fallara. Los mensajes van siempre a nivel 4, y el listener solo se invoca en 4.
     */
    private fun attachNativeLog() {
        try {
            VPLogApi.setLogLevel(VPLogApi.VP_LOG_INFO)
            VPLogApi.setLogListener { line -> log("NATIVO", line) }
            log("init", "log nativo enganchado (vp_printf nivel 4 → fileLogListener)")
        } catch (t: Throwable) {
            log("init", "log nativo NO enganchado: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * Un intento de la cascada: los cuatro parámetros que acepta
     * `JNIApi.connect(handle, mode, target, p2pType, cachedIps)`.
     */
    private data class ConnectAttempt(
        val mode: Int,
        /** 0 = el DID/UID, 1 = el VUID, 2 = la IP (solo como último recurso). */
        val targetKind: Int,
        val p2pType: Int,
        /**
         * Lista de IPs en caché (`net_ips`).
         *
         * - `null` = el `jstring` nulo que pasa la **app original en su primer
         *   connect**: `getCachedIps()` hace
         *   `getSharedPreferences("app_p2p_api_ips").getString("did_<uid>", null)`,
         *   que es `null` hasta que `ClientIpsListener` persiste algo. Es seguro:
         *   el JNI lo detecta (`cbz x25` en `0x40388`) y entrega `x26 = 0`, y
         *   `client_connect` lo reenvía sin dereferenciar (`mov x5, x24` en `0x42784`)
         *   a la vtable. La única lectura de ese registro es `csel x8,x8,x24,eq` en
         *   `0x42544`, que solo elige entre el puntero y la cadena literal `"NULL"`
         *   **para imprimirla en el log**.
         * - `""` = cadena vacía; en la vtable equivale a `net_ips_len = 0`.
         *
         * Aunque el final suele ser el mismo, es el único caso de uso que aún no se ha
         * probado, y es el de partida de O-KAM Pro.
         */
        val cachedIps: String?,
        val label: String,
        /**
         * 1.er argumento de `JNIApi.create()` (el `did` de `iface1`). `null` = el `did`
         * global del intento. En O-KAM Pro es el **mismo vpid** que el `did2`.
         */
        val didArg: String? = null,
        /**
         * 2.º argumento de `JNIApi.create()` (`did2`). **Solo tiene efecto si mide
         * exactamente 17**: es lo que rellena `iface2` (`handle+0x90`) y lo que hace
         * que `client_connect` construya un `serverStr` no nulo (`serverStr = target`,
         * `0x42428`). Con `""` la rama es idéntica a `did2 = NULL` (`0x41cc8`).
         */
        val did2Arg: String = "",
        /** Valor de `clientSetVuid()` (null = el `vuid` global de la config). */
        val vuidArg: String? = null,
    )

    /**
     * Crea el cliente y conecta, probando en **cascada** las combinaciones de
     * parámetros que admite el SDK. Nunca pasa `null` a un `String` de JNI.
     *
     * ### Por qué hace falta una cascada — desensamblado de `client_connect` (`0x42290`)
     *
     * ```c
     * int client_connect(void* h, int mode, const char* target,
     *                    int p2pType, const char* cachedIps)
     * {
     *     if (!h)                    return 0;              // 0x4233c
     *     if (*(u8*)(h+0x204) != 0)  return 3;              // 0x422c4  «ya conectado»
     *
     *     void* iface1 = *(void**)(h+0x88);                 // 0x423a8  vtable por prefijo del DID
     *     void* iface2 = *(void**)(h+0x90);                 //           NULL si did2 no medía 17
     *     // x25 = target      → ¡es el **TargetID (DID)**, no una IP!
     *     // x24 = cachedIps   → campo `net_ips` (log _se_try_other_netcard()
     *     //                     net_ips_len / net_ips_idx / lip=%s)
     *
     *     if (sp[0x50] != NULL && (mode & 0xFF) != 121)     // 0x42a94 / 0x42a9c
     *         return 4;                                     // 0x42b28 «unknown version … error»
     *
     *     rc = iface1->connect(h, mode, 0, target, &out, cachedIps, ver);   // 0x42eb4
     *
     *     if (rc < 0) {                                     // 0x43074  tbnz x24,#0x3f
     *         *(u8*)(h+0x204) = 0;                          // 0x43100
     *         return (rc <= -21) ? tabla[-rc] : 4;          // 0x4311c  ★ rc=4
     *     }
     *
     *     *(u8*)(h+0x204) = 1;                              // 0x430a0  ★ SOLO aquí
     *     *(void**)(h+0x80)  = iface1;                      // 0x430a4
     *     *(void**)(h+0x208) = (ver == 3) ? out : rc;       // 0x4309c
     *     return rc;                                        // ≥ 0 = session handle
     * }
     * ```
     *
     * Cuatro consecuencias que obligan a la cascada y a **un handle nuevo por intento**:
     *
     * 1. **`target` es el TargetID (DID)**, no una dirección IP. Una IP produce
     *    `ERROR_PPCS_INVALID_ID (Hint: TargetID is not a valid DID string)`; la IP
     *    va en `cachedIps`. (Era el defecto del log de dispositivo: pasábamos
     *    `<ip-de-la-cámara>` como TargetID.)
     * 2. **`create`'s 2.º parámetro NO es la licencia PPCS**: es un *segundo DID*
     *    (`did2`, `handle+0x40`) que solo se usa si mide **exactamente 17**
     *    (`0x41c30 cmp x0,#0x11`). `""` recorre la rama `did2=NULL` idéntica a
     *    `NULL` (`0x41cc8`), así que `create(did, "")` es correcto y no hay ningún
     *    parámetro de `serverParam`/licencia que arreglar en esta ruta JNI.
     * 3. **`rc >= 0` es éxito** (session handle 0,1,2,…); el nativo normaliza todos
     *    los errores PPCS de −1..−20 a **`4`** (`0x4311c`), de modo que `4` es
     *    ambiguo. Se desambigúa de forma barata con un `writeCgi` de sondeo: si
     *    `handle+0x204` sigue a 0 devuelve `false` **al instante**, sin timeout.
     * 4. Un segundo `connect()` sobre el mismo handle devuelve `3` sin tocar la red
     *    (`0x422c4`): **cada intento necesita su propio `create()`**.
     */
    suspend fun connect() {
        if (!nativeAvailable) return
        // Cualquier `connect` —el del arranque o el de un toque— declara la intención:
        // el supervisor solo reintenta mientras siga viva (punto 6).
        userWantsOnline = true
        connectMutex.withLock {
            try {
                init()
                disconnectInternal()

                val cfg = CamConfigHolder.config
                val did = cfg.uid.ifBlank { cfg.vuid }.ifBlank { cfg.ip }
                if (did.isBlank()) {
                    _state.value = ConnState.ERROR
                    log("create", "UID vacío, no se puede crear el cliente")
                    return
                }

                // Estado de autorización limpio: cada sesión necesita su propio
                // `eye4_authentication.cgi` (el token se pide de nuevo).
                authorized = false
                accountCredential = null
                reauthArmed.set(false)
                authGate = null

                _state.value = ConnState.CONNECTING
                val vpid = cfg.p2pDid.trim()
                val das = cfg.dasAddress.trim()
                val attempts = buildAttempts(did, cfg.vuid.trim(), cfg.ip.trim(), vpid, das)
                val dasShort =
                    if (das.isEmpty()) "(vacío)" else das.take(12) + "…(" + das.length + " chars)"
                log(
                    "cascade",
                    "${attempts.size} intentos · did=\"$did\" vuid=\"${cfg.vuid}\" " +
                        "p2pDid=\"$vpid\" (${vpid.length}) das=$dasShort ip=\"${cfg.ip}\""
                )
                if (vpid.isNotEmpty() && vpid.length != 17) {
                    log(
                        "cascade",
                        "AVISO: p2pDid mide ${vpid.length}, no 17; client_create NO " +
                            "creará iface2 y la ruta buena de client_connect queda cerrada"
                    )
                }

                for (a in attempts) {
                    val h = tryAttempt(cfg, did, vpid, a) ?: continue
                    handle = h
                    _state.value = ConnState.ONLINE
                    _messages.emit("P2P en línea — ${a.label}")
                    // Sonda: dispara un primer intercambio para asentar el túnel.
                    sendRaw(Cgi.LOGIN_PROBE)
                    // Preludio obligatorio: sin `eye4_auth=1` la cámara responde
                    // `result=-2` a todo (probado en equipo). Ver [authenticateSession].
                    authenticateSession(cfg)
                    return
                }

                _state.value = ConnState.ERROR
                _messages.emit("Ninguna combinación abrió el túnel (ver consola)")
                scheduleAutoRetry("la cascada no abrió el túnel")
            } catch (e: UnsatisfiedLinkError) {
                nativeAvailable = false
                _state.value = ConnState.ERROR
                log("connect", "UnsatisfiedLinkError: ${e.message}")
            } catch (t: Throwable) {
                _state.value = ConnState.ERROR
                log("connect", "ERROR ${t.javaClass.simpleName}: ${t.message}")
                _messages.emit("Error P2P: ${t.message}")
                scheduleAutoRetry("${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    /**
     * Envía el **preludio de autorización de sesión** y espera a que la cámara lo
     * confirme con `eye4_auth=1`.
     *
     * ### Por qué es obligatorio (verificado en equipo, no inferido)
     *
     * El túnel P2P y el `login()` del SDK **no bastan**: sin este preludio la cámara
     * contesta `result= 0;  result=-2;  ` a **todo** CGI (la única excepción es
     * `get_status.cgi`) y no se mueve ni un motor. Con él — y solo con él — las nueve
     * órdenes PTZ (0..7 y 25) responden `result= 0;  var result="ok";  ` y el domo se
     * mueve físicamente en los cuatro ejes, sus paradas y el centrado.
     *
     * El `loginToken` lo firma `api.eye4.cn` por sesión, así que se pide uno nuevo en
     * cada conexión ([Eye4Auth.fetch]) y se espera el veredicto por [authGate] en vez
     * de asumir éxito.
     *
     * Nunca lanza: si no hay credenciales de cuenta o la cámara no contesta, deja
     * [authorized] en `false`, lo cuenta en la consola y **la conexión sigue en pie**
     * (el vídeo RTSP no depende de esto).
     *
     * @param force `true` para pedir un token nuevo aunque ya haya uno en memoria.
     */
    suspend fun authenticateSession(cfg: CamConfig, force: Boolean = false): Boolean {
        val h = handle
        if (h == 0L) {
            log("eye4", "sin túnel activo: no hay sesión que autorizar")
            return false
        }
        if (authInFlight) {
            log("eye4", "ya hay una autorización en curso; se ignora la nueva petición")
            return authorized
        }
        authInFlight = true
        try {
            val credential = if (force || accountCredential == null) {
                Eye4Auth.fetch(cfg.accountUser, cfg.accountPassword) { log("eye4", it) }
            } else {
                accountCredential
            }
            if (credential == null) {
                authorized = false
                log("eye4", "SIN TOKEN DE CUENTA — la cámara responderá result=-2 a todo CGI")
                _messages.emit("Sin token de cuenta: los controles no responderán")
                return false
            }
            accountCredential = credential

            val gate = CompletableDeferred<Boolean>()
            authGate = gate
            // El token se usa **tal cual lo devuelve el servicio**: ya viene
            // percent-encoded (`%2F`, `%2B`, `%3D`…), así que volver a codificarlo
            // lo corrompe (`%252F`). El visor oficial también lo inserta directo.
            val cgi = "${Cgi.EYE4_AUTH}?loginAccount=${credential.userId}" +
                "&loginToken=${credential.token}&"
            // El token se enmascara en el log: es una credencial de sesión.
            log(
                "eye4",
                "preludio: ${Cgi.EYE4_AUTH}?loginAccount=${credential.userId}" +
                    "&loginToken=<${credential.token.length} chars>&"
            )
            if (!sendRaw(cgi)) {
                authGate = null
                authorized = false
                log("eye4", "el SDK rechazó el envío del preludio")
                return false
            }

            val confirmed = withTimeoutOrNull(AUTH_TIMEOUT_MS) { gate.await() }
            authGate = null
            authorized = confirmed == true
            if (authorized) {
                reauthArmed.set(true)
                log("eye4", "AUTORIZADO (eye4_auth=1): la cámara ya acepta órdenes")
                _messages.emit("Sesión autorizada (eye4_auth=1)")
            } else {
                log("eye4", "SIN CONFIRMAR: no llegó eye4_auth=1 en ${AUTH_TIMEOUT_MS} ms")
                _messages.emit("La cámara no confirmó la autorización")
            }
            return authorized
        } catch (t: Throwable) {
            log("eye4", "ERROR ${t.javaClass.simpleName}: ${t.message}")
            authorized = false
            return false
        } finally {
            authInFlight = false
        }
    }

    /**
     * Reintenta el preludio con la configuración actual: se llama sola cuando una
     * respuesta trae `result=-2` sobre una sesión que **sí** estaba autorizada (token
     * caducado), y también la puede invocar la UI para forzarlo a mano.
     */
    fun reauthenticate() {
        scope.launch {
            val cfg = runCatching { CamConfigHolder.config }.getOrNull()
            if (cfg == null) {
                log("eye4", "reautorización sin configuración cargada")
                return@launch
            }
            authenticateSession(cfg, force = true)
        }
    }

    /** Orden de la cascada (ver kdoc de [connect]). `null` ⇒ handle ya destruido. */
    private fun buildAttempts(
        did: String,
        vuid: String,
        ip: String,
        vpid: String,
        das: String,
    ): List<ConnectAttempt> {
        // `did2` solo surte efecto con 17 caracteres exactos; con otra longitud se
        // manda "" (rama idéntica a NULL, 0x41cc8) para no ensuciar el log.
        val did2 = if (vpid.length == 17) vpid else ""
        val hasVpid = did2.isNotEmpty()

        val list = ArrayList<ConnectAttempt>(20)

        // ================================================================
        // BLOQUE 0 — la combinación EXACTA de O-KAM Pro, primero  ⭐⭐
        // ================================================================
        // El log del SDK de la app oficial enseña:
        //   create(VPTN…, VPTN…) → clientSetVuid(VPTN…) → connect(126, VPTN…)
        //   → ConnectByServer **VP** → connectType:0x7E result:1
        // y **no** pasa ninguna dirección `DAS-…`. La cascada probaba antes el
        // bloque `das/…`, que también monta túnel pero por esa dirección; si la
        // superficie CGI depende de la ruta de sesión (relé vs par), esa es la
        // única diferencia estructural que queda. Esto la pone la primera.
        if (hasVpid) {
            list += ConnectAttempt(
                MODE_DEFAULT, TARGET_VPID, 1, null, "0-vpid/p2ptype1-nullips",
                didArg = vpid, did2Arg = vpid, vuidArg = vpid
            )
            list += ConnectAttempt(
                MODE_DEFAULT, TARGET_VPID, 0, null, "0-vpid/ips-null",
                didArg = vpid, did2Arg = vpid, vuidArg = vpid
            )
            list += ConnectAttempt(
                MODE_DEFAULT, TARGET_VPID, 1, ip, "0-vpid/p2ptype1-lan",
                didArg = vpid, did2Arg = vpid, vuidArg = vpid
            )
            list += ConnectAttempt(
                MODE_DEFAULT, TARGET_VPID, 0, ip, "0-vpid/lan",
                didArg = vpid, did2Arg = vpid, vuidArg = vpid
            )
        }

        // ================================================================
        // BLOQUE D — la dirección `DAS-…` como TargetID/serverParam
        // ================================================================
        // Es la hipótesis fuerte: el SDK (capa HL) valida el 4.º argumento de
        // `ConnectByServer` en `hl_p2p_check_valid` (`0x94ee0`):
        //   - debe empezar por "DAS-"  (cmp w8,#0x2D534144)
        //   - strlen >= 20
        //   - (strlen+12) % 16 == 0
        // y en `hl_p2p_connect_async` (`0x97b54`) además strlen >= 60.
        // Con "VPTN…" (17) TODAS fallan y devuelve −101 — que es exactamente lo que
        // vemos. La app oficial pasa aquí la `address` que baja de su nube.
        if (hasVpid && das.isNotBlank()) {
            list += ConnectAttempt(
                MODE_DEFAULT, TARGET_DAS, 1, null, "das/p2ptype1-nullips",
                didArg = vpid, did2Arg = vpid, vuidArg = vpid
            )
            list += ConnectAttempt(
                MODE_DEFAULT, TARGET_DAS, 0, null, "das/p2ptype0-nullips",
                didArg = vpid, did2Arg = vpid, vuidArg = vpid
            )
            list += ConnectAttempt(
                MODE_DEFAULT, TARGET_DAS, 1, ip, "das/p2ptype1-lan",
                didArg = vpid, did2Arg = vpid, vuidArg = vpid
            )
            // El `did` corto (VE…) con did2 = vpid, por si iface1 debe salir del prefijo VE.
            list += ConnectAttempt(
                MODE_DEFAULT, TARGET_DAS, 1, null, "das/uid-did2",
                did2Arg = vpid, vuidArg = vpid
            )
            // Variante: la dirección viajando por `cachedIps` (net_ips) en vez de target.
            list += ConnectAttempt(
                MODE_DEFAULT, TARGET_VPID, 1, das, "vpid/dasips",
                didArg = vpid, did2Arg = vpid, vuidArg = vpid
            )
        }

        // ================================================================
        // BLOQUE A — clon de O-KAM Pro con el vpid como target
        // ================================================================
        // Secuencia observada en el log del SDK de la app oficial:
        //   create(VPTN…, VPTN…) → clientSetVuid(VPTN…) → connect(mode 126, VPTN…)
        //   → "client_connect server_param server" (línea 223, 0x3fe1c: SOLO si
        //     iface2 != NULL) → ConnectByServer **VP** → CheckConnectMode
        //   → "clientId:VPTN…  clientId2:VPTN…  connectType:0x7E  result:1"
        if (hasVpid) {
            // create(vpid, vpid): iface1 e iface2 salen del MISMO prefijo (VPTN), que es
            // justo lo que enseña el log bueno (clientId == clientId2).
            // `did2` de 17 ⇒ serverStr = target (0x42428) en vez de NULL (0x424f8).
            list += ConnectAttempt(
                MODE_DEFAULT, TARGET_VPID, 0, null, "vpid/did2+ips-null",
                didArg = vpid, did2Arg = vpid, vuidArg = vpid
            )
            list += ConnectAttempt(
                MODE_DEFAULT, TARGET_VPID, 0, ip, "vpid/did2+lan",
                didArg = vpid, did2Arg = vpid, vuidArg = vpid
            )
            // p2pType=1 + iface2 != NULL ⇒ el nativo entra por la vía de iface2 (0x426fc).
            list += ConnectAttempt(
                MODE_DEFAULT, TARGET_VPID, 1, ip, "vpid/did2+p2ptype1",
                didArg = vpid, did2Arg = vpid, vuidArg = vpid
            )
            list += ConnectAttempt(
                MODE_DEFAULT, TARGET_VPID, 1, null, "vpid/did2+p2ptype1-nullips",
                didArg = vpid, did2Arg = vpid, vuidArg = vpid
            )
            list += ConnectAttempt(
                MODE_DEFAULT, TARGET_VPID, 0, "", "vpid/did2-sin-ips",
                didArg = vpid, did2Arg = vpid, vuidArg = vpid
            )
            // Modos con rama propia; el 121 devuelve el error PPCS crudo (sonda).
            list += ConnectAttempt(
                MODE_123, TARGET_VPID, 0, ip, "vpid/did2+123",
                didArg = vpid, did2Arg = vpid, vuidArg = vpid
            )
            list += ConnectAttempt(
                MODE_121, TARGET_VPID, 0, ip, "vpid/did2+121",
                didArg = vpid, did2Arg = vpid, vuidArg = vpid
            )
        }

        // ================================================================
        // BLOQUE B — iface1 desde el UID/VUID de etiqueta y did2 = vpid
        // ================================================================
        // Por si el firmware quiere el `did` corto (VE…) con el segundo DID de 17.
        if (hasVpid && did != vpid) {
            list += ConnectAttempt(
                MODE_DEFAULT, TARGET_DID, 0, null, "uid+vpiddid2+ips-null", did2Arg = did2
            )
            list += ConnectAttempt(
                MODE_DEFAULT, TARGET_DID, 0, ip, "uid+vpiddid2+lan", did2Arg = did2
            )
            list += ConnectAttempt(
                MODE_DEFAULT, TARGET_VPID, 0, ip, "vpid+uidid2+lan", did2Arg = did
            )
        }

        // ================================================================
        // BLOQUE C — cascada original (did2 = ""), sin tocar
        // ================================================================
        // 1) El caso de partida de O-KAM Pro: `getCachedIps()` devuelve `null` en el
        //    primer connect, porque `ClientIpsListener` todavía no ha persistido nada
        //    en `app_p2p_api_ips`. Única combinación que faltaba por probar, y es la
        //    primera que ejecuta la app de referencia (ver kdoc de ConnectAttempt:
        //    el nulo es seguro en el JNI y no se dereferencea).
        list += ConnectAttempt(MODE_DEFAULT, TARGET_DID, 0, null, "did+ips-null")
        // 2) El correcto según el desensamblado: TargetID = DID, net_ips = IP de LAN.
        list += ConnectAttempt(MODE_DEFAULT, TARGET_DID, 0, ip, "did+lan")
        // 3) Solo rendezvous, sin IPs directas (descarta la IP malformada del TargetID).
        list += ConnectAttempt(MODE_DEFAULT, TARGET_DID, 0, "", "did-sin-ips")
        // 4) p2pType=1: en el nativo selecciona la ruta de la 2.ª interfaz (did2 de 17
        //    caracteres). Con did2="" cae igual en iface1, pero se prueba por si el
        //    firmware espera ese código.
        list += ConnectAttempt(MODE_DEFAULT, TARGET_DID, 1, ip, "did+p2ptype1")
        // 5/6) Modos especiales 123 y 121 (ramas propias en 0x42c70 y 0x43014). El 121
        //      devuelve el error PPCS crudo sin normalizar, así que sirve de sonda para
        //      descifrar qué código real producen los demás (`rc=4` de los otros).
        list += ConnectAttempt(MODE_123, TARGET_DID, 0, ip, "123+lan")
        list += ConnectAttempt(MODE_121, TARGET_DID, 0, ip, "121+lan")
        // 7) VUID como TargetID (algunos firmwares exigen el VUID, no el UID de etiqueta).
        if (vuid.isNotBlank() && vuid != did) {
            list += ConnectAttempt(MODE_DEFAULT, TARGET_VUID, 0, ip, "vuid+lan")
        }
        // 8) Compatibilidad con el comportamiento anterior (IP como TargetID), que es
        //    el que fallaba en el equipo; se deja al final a propósito.
        if (ip.isNotBlank()) {
            list += ConnectAttempt(MODE_DEFAULT, TARGET_IP, 0, ip, "ip-directa")
        }
        return list
    }

    /**
     * Ejecuta un intento de la cascada con **handle propio**: `create →
     * clientSetVuid → connect → sondeo de túnel → login`.
     *
     * Devuelve el handle si el túnel quedó confirmado, o `null` (con el handle ya
     * destruido) si este intento falló.
     */
    private suspend fun tryAttempt(
        cfg: CamConfig,
        did: String,
        vpid: String,
        a: ConnectAttempt,
    ): Long? {
        // `did` de create (posible override por intento) y su `did2` de 17.
        val didArg = (a.didArg ?: did).trim()
        val did2 = a.did2Arg.trim()
        val vuid = (a.vuidArg ?: cfg.vuid).trim()
        val target = when (a.targetKind) {
            TARGET_VUID -> vuid.ifBlank { didArg }
            TARGET_VPID -> vpid.ifBlank { didArg }
            TARGET_DAS -> cfg.dasAddress.trim().ifBlank { didArg }
            TARGET_IP -> cfg.ip.trim().ifBlank { didArg }
            else -> didArg
        }
        val ips = a.cachedIps

        // `did2` de 17 chars ⇒ iface2 != NULL ⇒ serverStr = target (0x42428).
        // "" o cualquier otra longitud ⇒ rama did2=NULL (0x41cc8), serverStr = NULL.
        val h = JNIApi.create(didArg, did2)
        log(
            "create",
            "[${a.label}] did=\"$didArg\" did2=\"$did2\" (${did2.length} chars; " +
                "solo surte efecto con 17) -> handle=0x${java.lang.Long.toHexString(h)}"
        )
        if (h == 0L) return null
        handle = h

        return try {
            if (vuid.isNotBlank()) {
                val sv = JNIApi.clientSetVuid(h, vuid)
                log("setVuid", "[${a.label}] vuid=\"$vuid\" -> $sv  (handle+0x210)")
            }

            log(
                "connect",
                "[${a.label}] mode=$a.mode target=\"$target\" p2pType=${a.p2pType} " +
                    "cachedIps=" + (ips?.let { "\"$it\"" } ?: "<null>")
            )
            val t0 = System.currentTimeMillis()
            val rc = JNIApi.connect(h, a.mode, target, a.p2pType, ips)
            val ms = System.currentTimeMillis() - t0
            // rc>=0 solo dice que el SDK aceptó la petición; el túnel aún no existe.
            // rc<0 es SIEMPRE error (el flag handle+0x204 se limpia en 0x43100).
            log("connect", "[${a.label}] rc=$rc en $ms ms — ${explainRc(rc, a.mode)}")

            // Desambiguar rc=4 sin coste: writeCgi exige handle+0x204 != 0 y, si no está,
            // devuelve false inmediatamente (sin esperar el timeout).
            val probe = JNIApi.writeCgi(h, Cgi.LOGIN_PROBE, TUNNEL_PROBE_MS)
            log(
                "probe",
                "[${a.label}] writeCgi(\"${Cgi.LOGIN_PROBE}\") -> $probe " +
                    "(flag handle+0x204, solo lo pone el éxito de connect)"
            )
            if (!probe) {
                log("probe", "[${a.label}] túnel NO montado, se descarta el intento")
                destroyHandle(h)
                return null
            }

            val user = cfg.user.ifBlank { "admin" }
            val pass = cfg.password.ifBlank { "" }
            val lr = JNIApi.login(h, user, pass)
            log("login", "[${a.label}] user=\"$user\" pass=${pass.length} chars -> $lr")
            if (!lr) {
                log("login", "[${a.label}] login() falló, se descarta el intento")
                destroyHandle(h)
                return null
            }

            log("cascade", "[${a.label}] TÚNEL CONFIRMADO (connect + writeCgi + login)")
            h
        } catch (t: Throwable) {
            log("cascade", "[${a.label}] ${t.javaClass.simpleName}: ${t.message}")
            destroyHandle(h)
            null
        }
    }

    /**
     * Desconexión **explícita del usuario**: baja la intención y para el supervisor,
     * para que no se reconecte solo a quien acaba de pedir que se desconecte.
     */
    suspend fun disconnect() {
        userWantsOnline = false
        cancelAutoRetry()
        connectMutex.withLock { disconnectInternal() }
    }

    private fun disconnectInternal() {
        val h = handle
        handle = 0L
        // Punto 3: al cerrar el túnel no hay nada que esperar.
        clearCgiPending()
        // La autorización es por sesión: al cerrar el túnel, el token deja de valer.
        authorized = false
        accountCredential = null
        reauthArmed.set(false)
        authGate = null
        if (h != 0L) {
            log("disconnect", "disconnect(0x${java.lang.Long.toHexString(h)})")
            destroyHandle(h)
        }
        if (_state.value != ConnState.ERROR) _state.value = ConnState.DISCONNECTED
    }

    /**
     * Libera un handle. `JNIApi.disconnect` tolera un túnel nunca montado, y
     * `destroy` libera la entrada de la tabla de handles (`0xbe140`), imprescindible
     * para que la cascada pueda crear varios handles seguidos.
     */
    private fun destroyHandle(h: Long) {
        if (handle == h) handle = 0L
        try {
            JNIApi.disconnect(h)
        } catch (t: Throwable) {
            log("disconnect", "ERROR ${t.message}")
        }
        try {
            JNIApi.destroy(h)
        } catch (t: Throwable) {
            log("destroy", "ERROR ${t.message}")
        }
    }

    fun destroy() {
        scope.launch { disconnect() }
    }

    /**
     * Reconexión diferida tras un error. Conserva la firma histórica, pero **ahora sí
     * se usa**: delega en [scheduleAutoRetry], que reintenta en bucle con backoff en
     * vez de una sola vez (punto 6).
     */
    fun scheduleRetry(config: CamConfig) {
        userWantsOnline = true
        scheduleAutoRetry("petición externa")
    }

    // ---------------------------------------------------------------------
    // Envío CGI
    // ---------------------------------------------------------------------

    /** Envía una cadena CGI completa (`path?query&`). Devuelve `true` si el SDK la aceptó. */
    fun send(cgi: String): Boolean = sendRaw(cgi)

    /**
     * Handle del túnel activo. **Solo para la consola de depuración** (`debug.CgiDebugReceiver`),
     * que necesita llamar a variantes nativas (`writeCgiNVR`, `write` binario) que la app
     * no usa en producción.
     */
    fun debugHandle(): Long = handle

    private fun sendRaw(cgi: String): Boolean {
        val h = handle
        if (h == 0L) {
            log("writeCgi", "SIN TÚNEL, se descarta: $cgi")
            return false
        }
        if (!cgi.endsWith("&") && !cgi.endsWith("?")) {
            log("writeCgi", "AVISO: la cadena debe terminar en '&' o '?': $cgi")
        }
        return try {
            val ok = JNIApi.writeCgi(h, cgi, TIMEOUT_MS)
            log("writeCgi", if (ok) "OK   $cgi" else "FALSE $cgi")
            // Punto 3: abre la ventana del vigilante. Solo si el SDK aceptó la orden
            // (con `false` el nativo ni la envió, así que no hay nada que esperar).
            if (ok) markCgiPending()
            ok
        } catch (t: Throwable) {
            log("writeCgi", "ERROR $cgi -> ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    /** Envía binario crudo por un canal arbitrario (usado por el intercomunicador). */
    fun writeBytes(channel: Int, data: ByteArray): Boolean {
        val h = handle
        if (h == 0L) return false
        return try {
            val n = JNIApi.write(h, channel, data, TIMEOUT_MS)
            if (n <= 0) log("write(ch=$channel)", "rc=$n len=${data.size}")
            n > 0
        } catch (t: Throwable) {
            log("write(ch=$channel)", "ERROR ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    fun isOnline(): Boolean = _state.value == ConnState.ONLINE && handle != 0L

    // ---------------------------------------------------------------------
    // Punto 3 — Luz blanca y visión nocturna
    // ---------------------------------------------------------------------

    /**
     * **Único mando del LED blanco** — verificado en equipo (2026-10-17):
     * `set_whiteled_value.cgi?whiteled=<0..100>&`.
     *
     * `whiteled` **no es un booleano, es el brillo en porcentaje**: `0` apaga y cualquier
     * valor por encima enciende a ese tanto por ciento. Confirmado en el equipo subiendo y
     * bajando el deslizador de 0 a 100 (apaga / enciende) y leyendo el estado con
     * [queryWhiteLed], que devuelve el valor exacto puesto.
     *
     * Dos cosas quedan **fuera** por evidencia, no por gusto:
     * - `display_pwm_ctrl.cgi` (el «brillo PWM» de la UI vieja) **no existe** en este
     *   firmware: no contesta ni un byte.
     * - `trans_cmd_string.cgi?cmd=2109&command=0&light=0|1&` (lo que manda la app oficial)
     *   es **redundante**: con `whiteled=0` la luz ya se apaga. Ver AGENTS.md §5.10.
     */
    fun setWhiteLedBrightness(percent: Int): Boolean =
        send("${Cgi.WHITE_LED}?whiteled=${percent.coerceIn(0, 100)}&")

    /**
     * Encendido/apagado de conveniencia sobre [setWhiteLedBrightness]: enciende a
     * [brightness] y apaga con **0 %**.
     *
     * Al encender el nivel se fuerza a ≥ 1 a propósito: el fallo histórico fue mandar
     * `whiteled=1` creyendo que era un booleano, lo que dejaba el LED al **1 %** de
     * brillo (invisible) y hacía pensar que la luz no funcionaba.
     */
    fun setWhiteLed(on: Boolean, brightness: Int = 100): Boolean =
        setWhiteLedBrightness(if (on) brightness.coerceIn(1, 100) else 0)

    /** `get_whiteled_value.cgi?` — estado real: `var whiteled=<0..100>; var light=<0|1>;`. */
    fun queryWhiteLed(): Boolean = send(Cgi.GET_WHITE_LED)

    /**
     * Estado **real** de sirena y estrobo: `trans_cmd_string.cgi?cmd=2109&command=2&`
     * responde `lightStatus=<0|1>; sirenStatus=<0|1>; alarmLedStatus=<0|1>;`.
     *
     * Verificado en equipo (2026-10-17): con `command=2` **no hay nada que echar de
     * vuelta**, así que estos campos son estado y no el eco de una petición (que es lo
     * que pasa con `command=0`, §5.10). Es la única lectura de `alarmLedStatus` que
     * existe en el firmware: no aparece en `get_status.cgi?`.
     *
     * `get_status.cgi?` también trae un `sirenStatus`, pero **suelto**: la app exige
     * los dos campos juntos para no confundir una respuesta con la otra.
     */
    fun querySirenStrobe(): Boolean = send("${Cgi.TRANS_CMD}?cmd=2109&command=2&")

    /**
     * `set_whiteled_value.cgi?ledtimes=X&` — **RETIRADO** (no se envía).
     *
     * La cámara lo acepta (`var result="ok"`), pero **su efecto nunca se pudo verificar**
     * en este firmware: apagado diferido no observado y la app oficial nunca lo envía al
     * manejar la luz. Se quitó de la UI junto con el «Temporizador de Luz» (punto 1 del
     * ítem de sirina/estrobo) para no ofrecer un mando sin efecto comprobado. El CGI
     * existe y queda documentado en §5.10 por si algún día se verifica.
     */

    /**
     * `set_whiteled_value.cgi?mode=X&` — «modo» de la luz según la app oficial.
     *
     * **No-op verificado**: enviar `mode=0` o `mode=1` no altera ni el brillo ni el
     * estado (`get_whiteled_value.cgi?` los mantiene). Se conserva porque el CGI existe
     * en el firmware, pero la UI no depende de él.
     */
    fun setWhiteLedMode(mode: Int): Boolean = send("${Cgi.WHITE_LED}?mode=$mode&")

    /**
     * Destello: sube el LED a [brightness] durante [ms] y lo deja apagado (0 %).
     * Usa **sólo** el mando verificado, así que el estado final siempre es «apagada».
     */
    suspend fun flashWhiteLed(ms: Long = 1500L, brightness: Int = 100) {
        setWhiteLedBrightness(brightness)
        delay(ms)
        setWhiteLedBrightness(0)
    }

    /** `trans_cmd_string.cgi?cmd=2108&command=1&lightMode=1&` — selector de modo de luz. */
    fun setLightMode(mode: Int): Boolean =
        send("${Cgi.TRANS_CMD}?cmd=2108&command=1&lightMode=$mode&")

    /**
     * Aplica uno de los 4 modos de visión nocturna con
     * `camera_control.cgi?param=37&value=0&fullcolormode=0|1|2&fullcolordefault=0&`.
     *
     * Para color/starlight envía además `set_whiteled_value.cgi?mode=` (el refuerzo que
     * usa la app oficial), aunque en este firmware ese `mode=` es un **no-op** (§5.10).
     */
    fun applyNightMode(mode: Int, colorSubMode: Int = 1): Boolean {
        val fullColor = when (mode) {
            NightMode.IR -> 0
            NightMode.FULL_COLOR -> colorSubMode // 1 normal, 2 suave, 3 starlight
            NightMode.SMART -> 2
            NightMode.STARLIGHT -> colorSubMode
            else -> 2
        }
        val ok = send("${Cgi.CAMERA_CONTROL}?param=37&value=0&fullcolormode=$fullColor&fullcolordefault=0&")
        if (mode == NightMode.STARLIGHT || mode == NightMode.FULL_COLOR) {
            setWhiteLedMode(colorSubMode)
        }
        log("night", "mode=$mode -> fullcolormode=$fullColor ok=$ok")
        return ok
    }

    // ---------------------------------------------------------------------
    // Punto 4 — Llamada / intercomunicador
    // ---------------------------------------------------------------------

    /** `callstatus.cgi?status=1&` / `?status=0&` — estado de llamada en la cámara. */
    fun setCallStatus(active: Boolean): Boolean =
        send("${Cgi.CALL_STATUS}?status=${if (active) 1 else 0}&")

    /** `trans_cmd_string.cgi?cmd=2135&command=1&voicetype=X&` — tipo de voz (0 off, 1 male, 2 female, 3 child). */
    fun setVoiceType(type: Int): Boolean =
        send("${Cgi.VOICE_TYPE}?cmd=2135&command=1&voicetype=$type&")

    /** `trans_cmd_string.cgi?cmd=5001&command=2&audio_play_mode=X&` — 1 mono, 2 estéreo. */
    fun setAudioPlayMode(mode: Int): Boolean =
        send("${Cgi.AUDIO_PLAY_MODE}?cmd=5001&command=2&audio_play_mode=$mode&")

    // ---------------------------------------------------------------------
    // Punto 5 — MicroSD
    // ---------------------------------------------------------------------

    /** Lee el estado general de la tarjeta. */
    fun requestSdStatus() { send(Cgi.GET_STATUS) }

    /** `get_record.cgi?` — esquema de grabación (grabando/no, programación). */
    fun requestRecordState() { send(Cgi.GET_RECORD) }

    /** `trans_cmd_string.cgi?cmd=2017&command=11&mark=212&type=6&` — marca de posición de archivo. */
    fun markRecord(): Boolean =
        send("${Cgi.SD_MARK}?cmd=2017&command=11&mark=212&type=6&")

    /** `set_recordsch.cgi?` — activa/desactiva la grabación por programación. */
    fun setRecordSchedule(on: Boolean): Boolean =
        send("${Cgi.SET_RECORD_SCH}enable=${if (on) 1 else 0}&")

    /** `set_formatsd.cgi?` — formateo; la UI debe pedir confirmación. */
    fun formatSd(): Boolean = send(Cgi.FORMAT_SD)

    /** `get_record_file.cgi?GetType=date&` — lista de fechas con grabaciones. */
    fun requestRecordDates() { send("${Cgi.GET_RECORD_FILE}?GetType=date&") }

    /**
     * `get_record_file.cgi?GetType=file&dirname=YYYYMMDD&PageSize=1000&` — clips del día.
     *
     * El `PageSize` **no es opcional**: medido en el equipo (2026-10-17), sin él la
     * cámara devuelve sólo la **última página** (~96 clips de las 21:xx) y el resto
     * del día no aparece. Con `1000` llega el día completo en una respuesta
     * (394 clips, 27 KB).
     */
    fun requestRecordFiles(dateDir: String) {
        send("${Cgi.GET_RECORD_FILE}?GetType=file&dirname=$dateDir&PageSize=1000&")
    }

    /**
     * `record_fastplay.cgi?ctrl=1&playlist=FILENAME&` — reproducción del archivo TF.
     *
     * **Sin llamantes desde la UI desde el punto 3 (2026-10-17).** La cámara lo acepta
     * (`result= 0;`, 12 B) pero el flujo del fichero **no sale por el túnel CGI**
     * (§5.13) y además dejaba la sesión **sorda** (§5.15). Se conserva —igual que
     * [stopRecord] y [recordLiveStream]— porque son los dos comandos verificados del
     * app oficial y los usará el reproductor nativo cuando se integre (§10.6).
     */
    fun playRecord(fileName: String) = send("${Cgi.RECORD_FASTPLAY}?ctrl=1&playlist=$fileName&")

    /** `record_fastplay.cgi?ctrl=0&` — detiene la reproducción TF. Sin llamantes (§ [playRecord]). */
    fun stopRecord() = send("${Cgi.RECORD_FASTPLAY}?ctrl=0&")

    /** `livestream.cgi?streamid=4&filename=FILENAME&` — stream del archivo TF. Sin llamantes (§ [playRecord]). */
    fun recordLiveStream(fileName: String) = send("${Cgi.LIVESTREAM}?streamid=4&filename=$fileName&")

    /** `del_file.cgi?name=FILENAME&` — borra un clip. */
    fun deleteRecord(fileName: String) = send("${Cgi.DEL_FILE}?name=$fileName&")

    // ---------------------------------------------------------------------
    // Resto de comandos (PTZ, IA, salud)
    // ---------------------------------------------------------------------

    fun movePtz(direction: PtzDirection, invertX: Boolean, invertY: Boolean): Boolean {
        val d = direction.mapped(invertX, invertY)
        lastPtzDir = d
        return send("${Cgi.DECODER}?command=${d.code}&onestep=0&")
    }

    /**
     * Parada del eje que se estaba moviendo (`command` 1/3/5/7). Foscam/VStarcam **no
     * tiene una parada única**: cada dirección lleva su pareja de parada, y `command=8`
     * no existe en el firmware. Si nunca se movió nada usa la parada de arriba.
     */
    fun stopPtz(): Boolean {
        val d = lastPtzDir ?: PtzDirection.UP
        return send("${Cgi.DECODER}?command=${d.stopCode}&onestep=0&")
    }

    /** `command=25` — centrar / corregir cabezal. */
    fun centerPtz(): Boolean = send("${Cgi.DECODER}?command=25&onestep=0&channel=0&")

    /**
     * Crucero vertical: `command=26` arranca, `27` detiene (par Foscam confirmado en
     * el binario de O-KAM Pro, que contiene ambos literales).
     */
    fun verticalCruise(on: Boolean): Boolean =
        send("${Cgi.DECODER}?command=${if (on) 26 else 27}&onestep=0&")

    /**
     * Crucero horizontal: `command=28` arranca, `29` detiene (mismo par confirmado en
     * el binario).
     */
    fun horizontalCruise(on: Boolean): Boolean =
        send("${Cgi.DECODER}?command=${if (on) 28 else 29}&onestep=0&")

    fun setPreset(id: Int): Boolean =
        send("${Cgi.SET_SENSOR_PRESET}?sensorid=255&presetid=$id&")

    fun gotoPreset(id: Int): Boolean =
        send("${Cgi.DECODER}?command=${Cgi.GOTO_PRESET_BASE + id}&onestep=0&")

    fun setSiren(on: Boolean) = send("${Cgi.TRANS_CMD}?cmd=2109&command=0&siren=${if (on) 1 else 0}&")
    fun setStrobe(on: Boolean) = send("${Cgi.TRANS_CMD}?cmd=2109&command=0&alarmLed=${if (on) 1 else 0}&")

    fun setMotionArmed(on: Boolean) =
        send("${Cgi.SET_ALARM}?enable_alarm_audio=0&motion_armed=${if (on) 1 else 0}&")

    fun setHumanDetection(on: Boolean) =
        send("${Cgi.TRANS_CMD}?cmd=2106&command=4&humanDetection=${if (on) 1 else 0}&")

    fun setDetectionFrame(on: Boolean) =
        send("${Cgi.TRANS_CMD}?cmd=2126&command=0&bHumanoidFrame=${if (on) 1 else 0}&")

    /**
     * Seguimiento automático de personas — **el mando es `cmd=2127`, NO `cmd=4500`**.
     *
     * Medido en el equipo (2026-10-17), con relectura:
     * - `cmd=4500&command=11&track=1&` responde sólo `result= 0;  var cmd=4500;` y **no
     *   cambia nada**: `cmd=2127&command=1&` sigue devolviendo `enable=0`.
     * - `cmd=2127&command=0&enable=1&` sí lo cambia y la relectura lo confirma
     *   (`enable=1`). Es el mando real de este firmware.
     */
    fun setTracking(on: Boolean) =
        send("${Cgi.TRANS_CMD}?cmd=2127&command=0&enable=${if (on) 1 else 0}&")

    /**
     * Estado **real** del seguimiento: `enable` (activado) y `track_status` (está
     * siguiendo algo ahora mismo). Verificado en equipo.
     */
    fun queryTracking(): Boolean = send("${Cgi.TRANS_CMD}?cmd=2127&command=1&")

    /**
     * Sensibilidad de la detección humana — **`cmd=2126`, NO `cmd=4500`**.
     *
     * Medido: `cmd=4500&command=11&sensitivity=N&` **no cambia** `sensitive` (la
     * relectura lo deja en el valor anterior); `cmd=2126&command=0&sensitive=N&` sí, y
     * el `mark=123456789` es obligatorio en la escritura. La escala del firmware es
     * **inversa** a la etiqueta: 1 = alta, 2 = media, 3 = baja (§5.12).
     */
    fun setMotionSensitivity(level: Int) =
        send("${Cgi.TRANS_CMD}?cmd=2126&command=0&sensitive=${level.coerceIn(1, 3)}&mark=123456789&")

    /**
     * Estado **real** de la detección humana: `sensitive` (1..3) y `bHumanoidFrame`.
     * Verificado en equipo.
     */
    fun queryDetection(): Boolean = send("${Cgi.TRANS_CMD}?cmd=2126&command=1&mark=123456789&")

    fun setPirPush(on: Boolean) =
        send("${Cgi.TRANS_CMD}?cmd=2106&command=9&pirPushSwitch=${if (on) 1 else 0}&")

    fun setAlarmSound(on: Boolean) =
        send("${Cgi.TRANS_CMD}?cmd=4500&command=11&alarm_sound=${if (on) 1 else 0}&")

    fun setAlarmSoundVolume(volume: Int) =
        send("${Cgi.TRANS_CMD}?cmd=4500&command=19&alarm_sound_volume=$volume&")

    /**
     * Voltear imagen — **`param=5`, NO `param=3`**.
     *
     * Medido: `camera_control.cgi?param=3&value=1&` **no toca** `flip` en
     * `get_camera_params.cgi?` (sigue en 0), mientras que `param=5&value=1&` lo deja en
     * `flip=1` y `value=0&` en `flip=0`. Aviso: `camera_control.cgi` responde
     * `result= 0;` **siempre**, incluso cuando aplica el cambio, así que esa respuesta
     * no delata un rechazo (el «refused» de `result= 0;` vale para
     * `trans_cmd_string.cgi` / `set_alarm.cgi`, no para este).
     */
    fun setFlip(on: Boolean) = send("${Cgi.CAMERA_CONTROL}?param=5&value=${if (on) 1 else 0}&")

    fun reboot() { send(Cgi.REBOOT) }

    fun requestStatus() { send(Cgi.GET_STATUS) }

    fun requestCameraParams() { send(Cgi.GET_CAMERA_PARAMS) }

    // ---------------------------------------------------------------------
    // Listeners JNI
    // ---------------------------------------------------------------------

    private val stateListener = ClientStateListener { h, stateCode ->
        val name = when (stateCode) {
            0 -> "PPCS_SUCCESS"
            1 -> "TCP_ERR_INITIALING"
            2 -> "TCP_ERR_WORKING"
            3 -> "TCP_ERR_INVAILD_SESSION"
            4 -> "TCP_ERR_TIME_OUT"
            5 -> "TCP_ERR_CONNECT_FAILURE"
            6 -> "TCP_ERR_NETWORK"
            7 -> "TCP_ERR_USER_DISCONNECT"
            8 -> "TCP_ERR_SERVER_DISCONNECT"
            9 -> "TCP_ERR_DEVICE_DISCONNECT"
            10 -> "TCP_ERR_RELAY_TEST"
            11 -> "TCP_ERR_GET_HOSTNAME"
            12 -> "TCP_ERR_UUID_INVALID"
            13 -> "TCP_ERR_DEVICE_EXIST"
            else -> "UNKNOWN($stateCode)"
        }
        log("stateListener", "h=0x${java.lang.Long.toHexString(h)} code=$stateCode $name")
        when (stateCode) {
            // OJO: aquí NO se cancela el supervisor. `code=2` llega **durante** un
            // `connect()` que puede estar corriendo dentro del propio job del
            // supervisor, y cancelarlo mataría el preludio `eye4_authentication.cgi`
            // (bug visto en un reinicio real). El bucle del supervisor mira el estado
            // y termina solo.
            2 -> if (_state.value != ConnState.ONLINE) _state.value = ConnState.ONLINE
            3, 4, 5, 6, 7, 8, 9 -> {
                if (_state.value == ConnState.ONLINE) {
                    _state.value = ConnState.ERROR
                    scope.launch { _messages.emit("P2P caído: $name") }
                    // Punto 6: el túnel se cayó (reinicio de la cámara, WiFi, etc.).
                    // Se reintenta solo, sin cerrar y abrir la app.
                    scheduleAutoRetry("stateListener: $name")
                }
            }
        }
    }

    private val commandListener = ClientCommandListener { h, data, length ->
        // OJO: el nativo puede declarar un `length` mayor que el array real (visto en
        // equipo: `len=24577` con un `byte[31]`), así que SIEMPRE se acota al tamaño
        // del array. Sin este tope, `isLikelyBinary`/`String(...)` se salían de rango
        // y provocaban `ArrayIndexOutOfBoundsException` en el hilo del callback (el
        // `SIG:9` que mataba la app justo tras confirmar el túnel).
        val arr = data
        val n = if (arr == null) 0 else (length ?: 0).coerceIn(0, arr.size)
        val payload = if (arr == null) "" else String(arr, 0, n, Charsets.UTF_8)
        log(
            "commandListener",
            "h=0x${java.lang.Long.toHexString(h)} len=$length usado=$n array=${arr?.size ?: 0}"
        )

        if (arr != null && n > 0 && isLikelyBinary(arr, n)) {
            // Punto 4: el SDK solo expone `client_write` al JNI, así que la voz
            // que la cámara manda llega —si llega— por este mismo listener.
            log("commandListener", "payload binario, se enruta a VoiceLink")
            com.lgsusdev.camrevert.audio.VoiceLink.onIncoming(arr.copyOf(n))
            return@ClientCommandListener
        }

        if (payload.isNotBlank()) {
            // Se registra la respuesta **completa**: los 160 caracteres de antes cortaban
            // justo lo que hay que parsear (las zonas `pd_reign0..N` de `cmd=2123`, los
            // planes de `cmd=2017`, las listas de clips de `get_record_file.cgi`…).
            // Logcat corta las líneas muy largas, así que se trocea en bloques de 900.
            val limpio = payload.replace('\r', ' ').replace('\n', ' ')
            if (limpio.length <= 900) {
                log("commandListener", "respuesta=\"$limpio\"")
            } else {
                var i = 0
                var bloque = 0
                while (i < limpio.length) {
                    val fin = minOf(i + 900, limpio.length)
                    log("commandListener", "respuesta[$bloque]=\"${limpio.substring(i, fin)}\"")
                    i = fin
                    bloque++
                }
            }
            handleSessionAuth(payload)
            // Punto 3: llegó algo del equipo → el túnel no está sordo; cierra la ventana
            // y rearma el vigilante si se había quedado mudo tras agotar el techo.
            clearCgiPending()
            if (watchdogMuted) {
                watchdogMuted = false
                log("watchdog", "el túnel vuelve a responder → vigilante rearmado")
            }
            _responses.tryEmit(CgiResponse(payload))
        }
    }

    /**
     * Interpreta las dos respuestas que gobiernan la **autorización de sesión**:
     *
     * - `eye4_auth=…` → veredicto del preludio; completa la puerta que espera
     *   [authenticateSession] y deja [authorized] en su valor real.
     * - `result=-2` → la cámara rechazó el comando por falta de autorización. Antes del
     *   preludio es lo normal (no se hace nada); si llega con la sesión **ya autorizada**,
     *   el token caducó y se reautoriza **una vez** ([reauthArmed] se desarma hasta el
     *   próximo éxito, así que un fallo persistente no genera un bucle).
     */
    private fun handleSessionAuth(payload: String) {
        val auth = EYE4_AUTH_RE.find(payload)
        if (auth != null) {
            val value = auth.groupValues[1]
            log("eye4", "la cámara respondió eye4_auth=$value")
            authorized = value == "1"
            authGate?.complete(authorized)
            return
        }
        if (RESULT_MINUS_2.containsMatchIn(payload)) {
            log("eye4", "result=-2: comando rechazado (sesión sin autorizar)")
            if (reauthArmed.compareAndSet(true, false)) {
                log("eye4", "se reautoriza una vez por posible token caducado")
                scope.launch {
                    val cfg = runCatching { CamConfigHolder.config }.getOrNull()
                    if (cfg != null) authenticateSession(cfg, force = true)
                }
            }
        }
    }

    /**
     * Las respuestas CGI son ASCII imprimible con `\r\n`; el audio/frames binarios
     * tienen bytes de control y datos UTF-8 inválidos.
     *
     * [length] ya viene acotado al tamaño real del array por el llamante.
     */
    private fun isLikelyBinary(data: ByteArray, length: Int): Boolean {
        var control = 0
        val limit = minOf(length, data.size, 64)
        for (i in 0 until limit) {
            val b = data[i].toInt() and 0xFF
            if (b < 0x20 && b != 0x09 && b != 0x0A && b != 0x0D) control++
        }
        return control > 2
    }

    private val releaseListener = ClientReleaseListener { h ->
        log("releaseListener", "h=0x${java.lang.Long.toHexString(h)}")
        // El SDK dispara este callback TAMBIÉN para sesiones vivas (verificado en
        // equipo: tras el `TÚNEL CONFIRMADO` seguía llegando `ClientCommandListener`
        // con datos reales de la cámara, p. ej. `get_status.cgi` con 2520 bytes, y los
        // `writeCgi` posteriores seguían devolviendo `true`). Por eso solo se considera
        // caída real si el estado NO estaba ya en línea; si lo estaba, se ignora y
        // mandan el `stateListener` y el resultado del sondeo `writeCgi`.
        if (h == handle && _state.value != ConnState.ONLINE) {
            handle = 0
            _state.value = ConnState.ERROR
            // Punto 6: también por aquí se pierde el túnel, así que el supervisor
            // tiene que enterarse igual que por `stateListener`.
            scheduleAutoRetry("releaseListener")
        }
    }

    private val ipsListener = ClientIpsListener { uid, ips ->
        log("ipsListener", "uid=$uid ips=$ips")
    }
}

/** Para que el resto de la app pueda leer la configuración sin dependencias cíclicas. */
object CamConfigHolder {
    @Volatile
    lateinit var config: CamConfig
}

/** Modos de visión nocturna. */
object NightMode {
    const val IR = 0            // infrarrojo (B/N)
    const val FULL_COLOR = 1    // color permanente
    const val SMART = 2         // inteligente (se alterna solo)
    const val STARLIGHT = 3     // starlight (sensor de baja luz)
}
