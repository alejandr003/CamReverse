package com.lgsusdev.camrevert.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.lgsusdev.camrevert.BuildConfig

/**
 * Ajustes de la cámara persistidos en SharedPreferences (100% local, sin nube).
 *
 * Los valores por defecto salen de `secrets.properties` (el `.env` del proyecto, ver
 * `docs/CONFIGURACION.md`): IP, VUID, ID P2P, dirección `DAS-`, usuario y contraseña
 * de la cámara, cuenta EYE4 y puertos RTSP. **Ninguno está escrito en el código**, así
 * el repositorio se puede publicar sin credenciales.
 *
 * Equipo de referencia: O-KAM Pro de doble lente (fija + domo PTZ), RTSP 10555 (fija)
 * y 10554 (PTZ).
 */
class CamConfig(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("camrevert_config", Context.MODE_PRIVATE)

    // ---- Identidad y acceso ----

    var ip: String
        get() = prefs.getString(KEY_IP, DEFAULT_IP) ?: DEFAULT_IP
        set(value) = prefs.edit { putString(KEY_IP, value.trim()) }

    /** UID/DID del dispositivo, usado por `JNIApi.create()`. */
    var uid: String
        get() = prefs.getString(KEY_UID, DEFAULT_UID) ?: DEFAULT_UID
        set(value) = prefs.edit { putString(KEY_UID, value.trim()) }

    /**
     * VUID (prefijo `VE`) de la cámara. Se inyecta con `JNIApi.clientSetVuid()`
     * antes de `connect()`: `libOKSMARTPPCS.so` lo guarda en `handle+0x210` y
     * `client_connect()` lo consulta para resolver el rendezvous.
     */
    var vuid: String
        get() = prefs.getString(KEY_VUID, DEFAULT_VUID) ?: DEFAULT_VUID
        set(value) = prefs.edit { putString(KEY_VUID, value.trim()) }

    /**
     * **ID P2P real** de la cámara (17 caracteres, p. ej. `VPTN…`).
     *
     * Es distinto del [uid] de etiqueta (13 chars): en O-KAM Pro vive
     * en `p2p.did` de `FlutterSharedPreferences` y en el log del SDK aparece como
     * `clientId`/`clientId2`. Se usa como **`did2`** de `JNIApi.create()`: `client_create`
     * solo rellena `iface2` (`handle+0x90`) si mide **exactamente 17**
     * (`0x41c30 cmp x0,#0x11`), y sin `iface2` el SDK no entra en la ruta buena de
     * `client_connect` (`serverStr` queda NULL → `ConnectByServer` devuelve −5).
     * También actúa como TargetID de `connect()` en la app de referencia.
     */
    var p2pDid: String
        get() = prefs.getString(KEY_P2P_DID, DEFAULT_P2P_DID) ?: DEFAULT_P2P_DID
        set(value) = prefs.edit { putString(KEY_P2P_DID, value.trim()) }

    /**
     * **Dirección P2P `DAS-…`** del dispositivo (`DAS-` + 128 hex = 132 caracteres).
     *
     * Es el `serverParam` que exige la capa de alto nivel del SDK: `hl_p2p_check_valid`
     * (`0x94ee0`) valida que la cadena empiece por `DAS-` (compara los 4 primeros bytes
     * con `0x2D534144`), que mida **≥ 20** y que su longitud cumpla `len ≡ 4 (mod 16)`;
     * `hl_p2p_connect_async` (`0x97b54`) exige además **≥ 60**. Si no cuadra, devuelve
     * **−101** (uno de los `mov w0,#-0x65` de esas funciones) y el túnel no se monta.
     *
     * La app oficial la obtiene de su nube (`appp2pHost`, `log-operate-3.eye4.cn`) y la
     * guarda en `FlutterSharedPreferences` (`…_p2p_did` → `address`). CamRevert es 100 %
     * local, así que se copia a mano aquí (o en el diálogo de Configuración).
     */
    var dasAddress: String
        get() = prefs.getString(KEY_DAS_ADDRESS, DEFAULT_DAS_ADDRESS) ?: DEFAULT_DAS_ADDRESS
        set(value) = prefs.edit { putString(KEY_DAS_ADDRESS, value.trim()) }

    var user: String
        get() = prefs.getString(KEY_USER, DEFAULT_USER) ?: DEFAULT_USER
        set(value) = prefs.edit { putString(KEY_USER, value.trim()) }

    var password: String
        get() = prefs.getString(KEY_PASSWORD, DEFAULT_PASSWORD) ?: DEFAULT_PASSWORD
        set(value) = prefs.edit { putString(KEY_PASSWORD, value.trim()) }

    // ---- Cuenta EYE4 (nube de O-KAM Pro) ----

    /**
     * Email de la **cuenta EYE4** (la del visor oficial). No es un usuario de la
     * cámara: sirve para pedir el `loginToken` con el que la cámara autoriza cada
     * sesión (`eye4_authentication.cgi?loginAccount=…&loginToken=…&`).
     *
     * Sin este preludio la cámara contesta `result=-2` a **todo** CGI que no sea
     * `get_status.cgi` (probado en equipo). Ver [com.lgsusdev.camrevert.p2p.Eye4Auth].
     */
    var accountUser: String
        get() = prefs.getString(KEY_ACCOUNT_USER, DEFAULT_ACCOUNT_USER) ?: DEFAULT_ACCOUNT_USER
        set(value) = prefs.edit { putString(KEY_ACCOUNT_USER, value.trim()) }

    /**
     * Clave de la cuenta EYE4. **Se queda en el dispositivo**: solo se usa en local
     * para calcular `md5(clave)` y pedir el token a `api.eye4.cn` (HTTPS). Nunca se
     * manda a la cámara ni se sube a GitHub (`secrets.properties` está ignorado).
     */
    var accountPassword: String
        get() = prefs.getString(KEY_ACCOUNT_PASSWORD, DEFAULT_ACCOUNT_PASSWORD)
            ?: DEFAULT_ACCOUNT_PASSWORD
        set(value) = prefs.edit { putString(KEY_ACCOUNT_PASSWORD, value.trim()) }

    // ---- RTSP ----

    var rtspPortFixed: Int
        get() = prefs.getInt(KEY_PORT_FIXED, DEFAULT_PORT_FIXED)
        set(value) = prefs.edit { putInt(KEY_PORT_FIXED, value) }

    var rtspPortPtz: Int
        get() = prefs.getInt(KEY_PORT_PTZ, DEFAULT_PORT_PTZ)
        set(value) = prefs.edit { putInt(KEY_PORT_PTZ, value) }

    var hdFixed: Boolean
        get() = prefs.getBoolean(KEY_HD_FIXED, true)
        set(value) = prefs.edit { putBoolean(KEY_HD_FIXED, value) }

    var hdPtz: Boolean
        get() = prefs.getBoolean(KEY_HD_PTZ, true)
        set(value) = prefs.edit { putBoolean(KEY_HD_PTZ, value) }

    /** Calidad activa de la lente `cameraIndex` (0 = fija, 1 = PTZ). */
    fun hdQuality(cameraIndex: Int): Boolean =
        if (cameraIndex == 0) hdFixed else hdPtz

    fun setHdQuality(cameraIndex: Int, hd: Boolean) {
        if (cameraIndex == 0) hdFixed = hd else hdPtz = hd
    }

    /**
     * URI RTSP de una lente.
     *
     * `/tcp/av0_0` = stream principal (HD), `/tcp/av0_1` = sub-stream (SD).
     * **Ruta y puerto provienen de la especificación del equipo**, no aparecen
     * como literales en el binario (la app original usa su reproductor P2P).
     */
    fun rtspUri(cameraIndex: Int, hd: Boolean): String {
        val port = if (cameraIndex == 0) rtspPortFixed else rtspPortPtz
        val path = if (hd) "av0_0" else "av0_1"
        val u = this.user.ifBlank { DEFAULT_USER }
        val p = this.password.ifBlank { DEFAULT_PASSWORD }
        return "rtsp://$u:$p@$ip:$port/tcp/$path"
    }

    /** Sonido global de los dos streams (un único control, ver AGENTS.md §8). */
    var soundEnabled: Boolean
        get() = prefs.getBoolean(KEY_SOUND, false)
        set(value) = prefs.edit { putBoolean(KEY_SOUND, value) }

    // ---- Comportamiento ----

    /**
     * Conecta el túnel P2P **solo** al abrir la app (sin pulsar el botón).
     *
     * Es lo que hace la app oficial: el túnel se levanta en cuanto entras al visor.
     * La cascada tarda unos segundos y, si falta cualquier dato (IP/UID/VUID), se
     * omite con un aviso en la consola en vez de fallar. Se puede apagar desde
     * Configuración.
     */
    var autoConnect: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CONNECT, true)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_CONNECT, value) }

    /**
     * Fuerza el **decodificador de vídeo por software** (`MediaCodecSelector.PREFER_SOFTWARE`).
     *
     * Por defecto `false`: se usa el decodificador de hardware y, si rechaza el
     * perfil o la resolución del stream (`DefaultRenderersFactory
     * .setEnableDecoderFallback(true)`), Media3 cae solo al de software. En
     * equipos con decodificador muy justo (Lenovo Tab M10 con el main de
     * 2304×1296) conviene forzarlo aquí para evitar el tirón del primer fallo.
     */
    var preferSoftwareDecoder: Boolean
        get() = prefs.getBoolean(KEY_PREFER_SOFTWARE, false)
        set(value) = prefs.edit { putBoolean(KEY_PREFER_SOFTWARE, value) }

    // ---- PTZ ----

    var invertX: Boolean
        get() = prefs.getBoolean(KEY_INVERT_X, false)
        set(value) = prefs.edit { putBoolean(KEY_INVERT_X, value) }

    var invertY: Boolean
        get() = prefs.getBoolean(KEY_INVERT_Y, false)
        set(value) = prefs.edit { putBoolean(KEY_INVERT_Y, value) }

    // ---- Luz y visión nocturna ----

    var whiteLedOn: Boolean
        get() = prefs.getBoolean(KEY_LED_ON, false)
        set(value) = prefs.edit { putBoolean(KEY_LED_ON, value) }

    var whiteLedBrightness: Int
        get() = prefs.getInt(KEY_LED_BRIGHT, 80)
        set(value) = prefs.edit { putInt(KEY_LED_BRIGHT, value) }

    /**
     * Modo de visión nocturna: 0 = infrarrojo, 1 = color permanente,
     * 2 = inteligente, 3 = starlight (ver [com.lgsusdev.camrevert.p2p.NightMode]).
     */
    var nightMode: Int
        get() = prefs.getInt(KEY_NIGHT_MODE, 2)
        set(value) = prefs.edit { putInt(KEY_NIGHT_MODE, value) }

    /** Sub-modo de color cuando [nightMode] != IR (1 = normal, 2 = suave, 3 = starlight). */
    var colorSubMode: Int
        get() = prefs.getInt(KEY_COLOR_SUBMODE, 1)
        set(value) = prefs.edit { putInt(KEY_COLOR_SUBMODE, value) }

    // ---- Llamada / intercomunicador ----

    /** Modo del botón Llamada: 0 = manos libres (duplex), 1 = radio (push-to-talk). */
    var callMode: Int
        get() = prefs.getInt(KEY_CALL_MODE, 0)
        set(value) = prefs.edit { putInt(KEY_CALL_MODE, value) }

    /**
     * Canal P2P por el que viaja el audio de intercomunicador. El SDK usa el
     * canal 0 para CGI (cabecera `01 0A 00 00 <len>`); el audio va aparte.
     */
    var voiceChannel: Int
        get() = prefs.getInt(KEY_VOICE_CHANNEL, DEFAULT_VOICE_CHANNEL)
        set(value) = prefs.edit { putInt(KEY_VOICE_CHANNEL, value) }

    /**
     * Codificación del audio de intercomunicador que acepta la cámara:
     * 0 = G.711 A-law, 1 = G.711 µ-law, 2 = PCM 16 bits sin comprimir.
     * (G.711 A/U y ADPCM están implementados en `libOKSMARTPLAY.so`, ver AGENTS.md §5.3.)
     */
    var voiceCodec: Int
        get() = prefs.getInt(KEY_VOICE_CODEC, 0)
        set(value) = prefs.edit { putInt(KEY_VOICE_CODEC, value) }

    // ---- Transporte RTSP ----

    /**
     * `true` = RTP **interleaved sobre el socket TCP** de RTSP (lo que la app
     * usaba por defecto). `false` = RTP/AVP sobre **UDP**, que es lo que hace
     * VLC por defecto y lo que Media3 prefiere.
     *
     * La diferencia importa porque el camino UDP tiene **respaldo** y el TCP no:
     * `UdpDataSourceRtpDataChannelFactory.createFallbackDataChannelFactory()`
     * devuelve la fábrica TCP para cuando el servidor rechaza `RTP/AVP`, mientras
     * que `TransferRtpDataChannelFactory` no implementa ningún respaldo. Así que
     * «Auto» en la práctica es *UDP primero, TCP si el equipo lo pide*.
     *
     * `CameraStreamView` lo puede voltear solo ante fallos repetidos y lo deja
     * persistido, así que no hace falta tocarlo a mano.
     */
    var rtspTransportTcp: Boolean
        get() = prefs.getBoolean(KEY_RTSP_TCP, true)
        set(value) = prefs.edit { putBoolean(KEY_RTSP_TCP, value) }

    // ---- MicroSD ----

    var recordScheduleEnabled: Boolean
        get() = prefs.getBoolean(KEY_REC_SCHED, true)
        set(value) = prefs.edit { putBoolean(KEY_REC_SCHED, value) }

    // ---- Lente activa ----

    /**
     * Selector de vista de lente: [LENS_BOTH] (dos en paralelo, defecto),
     * [LENS_FIXED] o [LENS_PTZ]. En pantalla completa se sobrescribe con la
     * lente elegida para que el [LENS_BOTH] se restaure al salir.
     */
    var activeLens: Int
        get() = prefs.getInt(KEY_ACTIVE_LENS, LENS_BOTH)
        set(value) = prefs.edit { putInt(KEY_ACTIVE_LENS, value) }

    companion object {
        /**
         * Valores de fábrica, inyectados por Gradle desde `secrets.properties`
         * (archivado en `.gitignore`, ver `secrets.properties.example`).
         *
         * **No son `const val` a propósito**: vienen de `BuildConfig`, que solo
         * se resuelve en tiempo de compilación del módulo. Si no hay
         * `secrets.properties` quedan vacíos y la app pide los datos en el
         * diálogo de Configuración — así el repositorio se puede publicar sin
         * la contraseña de la cámara.
         */
        val DEFAULT_IP: String get() = BuildConfig.CAM_IP
        val DEFAULT_UID: String get() = BuildConfig.CAM_UID
        val DEFAULT_VUID: String get() = BuildConfig.CAM_VUID
        val DEFAULT_P2P_DID: String get() = BuildConfig.CAM_P2P_DID
        val DEFAULT_DAS_ADDRESS: String get() = BuildConfig.CAM_DAS_ADDRESS
        val DEFAULT_USER: String get() = BuildConfig.CAM_USER.ifBlank { "admin" }
        val DEFAULT_PASSWORD: String get() = BuildConfig.CAM_PASSWORD

        /** Cuenta EYE4 (nube). Ver [accountUser] / [accountPassword]. */
        val DEFAULT_ACCOUNT_USER: String get() = BuildConfig.CAM_ACCOUNT_USER
        val DEFAULT_ACCOUNT_PASSWORD: String get() = BuildConfig.CAM_ACCOUNT_PASSWORD
        val DEFAULT_PORT_FIXED: Int get() = BuildConfig.CAM_PORT_FIXED
        val DEFAULT_PORT_PTZ: Int get() = BuildConfig.CAM_PORT_PTZ

        /**
         * Canal P2P del intercomunicador. El 0 está reservado para CGI
         * (`client_write_command` escribe ahí la cabecera `01 0A ...`).
         */
        const val DEFAULT_VOICE_CHANNEL = 1

        const val LENS_BOTH = 2
        const val LENS_FIXED = 0
        const val LENS_PTZ = 1

        const val CALL_MODE_DUPLEX = 0
        const val CALL_MODE_PTT = 1

        const val CODEC_G711A = 0
        const val CODEC_G711U = 1
        const val CODEC_PCM16 = 2

        private const val KEY_IP = "ip"
        private const val KEY_UID = "uid"
        private const val KEY_VUID = "vuid"
        private const val KEY_P2P_DID = "p2p_did"
        private const val KEY_DAS_ADDRESS = "das_address"
        private const val KEY_USER = "user"
        private const val KEY_PASSWORD = "password"
        private const val KEY_ACCOUNT_USER = "account_user"
        private const val KEY_ACCOUNT_PASSWORD = "account_password"
        private const val KEY_PORT_FIXED = "port_fixed"
        private const val KEY_PORT_PTZ = "port_ptz"
        private const val KEY_HD_FIXED = "hd_fixed"
        private const val KEY_HD_PTZ = "hd_ptz"
        private const val KEY_SOUND = "sound"
        private const val KEY_AUTO_CONNECT = "auto_connect"
        private const val KEY_PREFER_SOFTWARE = "prefer_software_decoder"
        private const val KEY_INVERT_X = "invert_x"
        private const val KEY_INVERT_Y = "invert_y"
        private const val KEY_LED_ON = "led_on"
        private const val KEY_LED_BRIGHT = "led_bright"
        private const val KEY_NIGHT_MODE = "night_mode"
        private const val KEY_COLOR_SUBMODE = "color_submode"
        private const val KEY_CALL_MODE = "call_mode"
        private const val KEY_VOICE_CHANNEL = "voice_channel"
        private const val KEY_VOICE_CODEC = "voice_codec"
        private const val KEY_RTSP_TCP = "rtsp_tcp"
        private const val KEY_REC_SCHED = "rec_sched"
        private const val KEY_ACTIVE_LENS = "active_lens"
    }
}
