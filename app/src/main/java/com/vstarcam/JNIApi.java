package com.vstarcam;

/**
 * Fachada JNI sobre libOKSMARTPPCS.so (SDK P2P de VStarcam / O-KAM).
 *
 * IMPORTANTE: el nombre de la clase y de cada método debe conservarse tal cual.
 * El enlace nativo se resuelve por simbolos "Java_com_vstarcam_JNIApi_<metodo>"
 * y cualquier renombrado rompe la carga en runtime.
 *
 * Firmas extraidas de la app original O-KAM Pro (com.okampro.oksmart v3.0.37),
 * ver AGENTS.md para la evidencia completa.
 */
public class JNIApi {

    static {
        // La libreria depende de libvp_log.so (tabla ELF NEEDED) que debe viajar
        // junto a ella en jniLibs.
        System.loadLibrary("OKSMARTPPCS");
    }

    private JNIApi() {
    }

    // ---- Ciclo de vida del canal P2P ----

    /**
     * Crea una sesion nativa.
     *
     * En {@code client_create} nativo se hace {@code strlen()} sobre ambos
     * argumentos: {@code uid} debe medir 1..64 caracteres (0 o >64 devuelve
     * handle 0) y {@code arg1} solo se usa si mide exactamente 17; cualquier
     * otra longitud lo ignora y deja {@code did2=NULL}.
     *
     * <p><b>Nunca se debe pasar {@code null}</b> como {@code String}: la app
     * original lo hace, pero en la prueba fija provocó SIGSEGV en
     * {@code libOKSMARTPPCS.so}. Pasa {@code ""} — recorre exactamente la misma
     * rama {@code did2=NULL} sin exponer un {@code jstring} nulo al JNI.
     *
     * @param uid  UID/DID del dispositivo (etiqueta P2P de la camara, 1..64)
     * @param arg1 DID secundario opcional; pasa {@code ""} si no se usa
     * @return handle usado en el resto de llamadas (0 = error)
     */
    public static native long create(String uid, String arg1);

    /**
     * Registra los cuatro callbacks nativos. Debe llamarse una sola vez.
     * Orden exacto del descriptor JNI original:
     * (Lcom/vstarcam/app_p2p_api/ClientStateListener;
     *  Lcom/vstarcam/app_p2p_api/ClientCommandListener;
     *  Lcom/vstarcam/app_p2p_api/ClientReleaseListener;
     *  Lcom/vstarcam/app_p2p_api/ClientIpsListener;)Z
     */
    public static native boolean init(com.vstarcam.app_p2p_api.ClientStateListener stateListener,
                                      com.vstarcam.app_p2p_api.ClientCommandListener commandListener,
                                      com.vstarcam.app_p2p_api.ClientReleaseListener releaseListener,
                                      com.vstarcam.app_p2p_api.ClientIpsListener ipsListener);

    /**
     * Abre el tunel hacia la camara.
     *
     * @param handle    sesion de {@link #create}
     * @param mode      modo de conexion (la app original usa 126 por defecto)
     * @param target    direccion destino: IP local o UID P2P
     * @param p2pType   tipo de transporte P2P (0 = automatico)
     * @param cachedIps lista de IPs cacheadas para reconexion rapida
     */
    public static native int connect(long handle, int mode, String target, int p2pType, String cachedIps);

    /**
     * Autentica la sesion. Debe llamarse tras {@link #connect} y antes de
     * {@link #writeCgi}: el flag interno {@code handle+0x204} que exige
     * {@code client_write_cgi} solo se pone a 1 en la ruta de exito de
     * {@code client_connect}, y {@code login} almacena usuario/contrasena en
     * {@code handle+0x98} y {@code handle+0x199}, que son los que
     * {@code client_write_cgi} rellena en el {@code GET /...loginuse=...}.
     */
    public static native boolean login(long handle, String user, String password);

    public static native boolean disconnect(long handle);

    public static native void destroy(long handle);

    /** Rompe la Conexion en curso sin destruir la sesion. */
    public static native boolean clientConnectBreak(long handle);

    /** Libera una referencia retenida por {@link #retain}. */
    public static native void release(long handle);

    /** Retiene la sesion mientras se ejecuta una operacion en el pool de hilos. */
    public static native void retain(long handle);

    // ---- Transporte de comandos CGI ----

    /**
     * Envia un comando CGI por el tunel autenticado (PTZ, luz, sirena, IA, SD...).
     * La respuesta llega de forma asincrona por {@link ClientCommandListener}.
     *
     * <p><b>El {@code String} se envuelve dentro del propio nativo.</b>
     * {@code client_write_cgi} hace
     * {@code snprintf(buf, len, "GET /%sloginuse=%s&loginpas=%s&user=%s&pwd=%s&",
     * cgi, handle+0x98, handle+0x199, "admin", "888888")}. Por eso:
     * <ul>
     *   <li><b>No</b> antepongas {@code "GET /"} ni {@code "loginuse"/"loginpas"}.</li>
     *   <li>La cadena <b>debe terminar en {@code &} o {?}</b>: el nativo inserta
     *       {@code loginuse=} justo detrás sin separador.</li>
     * </ul>
     *
     * @param cgi     comando p.ej. "decoder_control.cgi?command=25&onestep=0&"
     * @param timeout <b>timeout en milisegundos</b>, NO la longitud de la cadena.
     *                La longitud real la calcula el nativo con {@code strlen}.
     *                {@code client_login} usa 5000; pasar {@code cgi.length}
     *                (~40) producia un timeout de 40 ms y los comandos se perdian.
     */
    public static native boolean writeCgi(long handle, String cgi, int timeout);

    /** Variante para NVR (grabadores multiplexados). Mismos criterios que {@link #writeCgi}. */
    public static native boolean writeCgiNVR(long handle, String cgi, int timeout);

    /**
     * Envio binario generico por un canal arbitrario (0 = CGI, 6 = transferencia
     * de ficheros, canal de voz = ver {@code CamConfig.voiceChannel}).
     *
     * <p>El nativo llama a {@code GetArrayLength(data)} para saber cuantos bytes
     * enviar; <b>la longitud real es siempre la del array</b>.
     *
     * @param channel canal P2P de destino
     * @param data    buffer a enviar (su longitud es la que se transmite)
     * @param timeout <b>timeout en milisegundos</b> ({@code client_write} reintenta
     *                en bucles de {@code usleep(20000)} hasta agotarlo)
     * @return bytes escritos, o {@code <=0} si fallo
     */
    public static native int write(long handle, int channel, byte[] data, int timeout);

    public static native boolean sendBinaryFile(long handle, String path, int length);

    // ---- Estado y utilidades ----

    public static native boolean changeId(long handle, String uid);

    /**
     * Fija el VUID de la camara (prefijo {@code VE}, 13 caracteres).
     *
     * <p>{@code client_setvuid} nativo copia la cadena en {@code handle+0x210}
     * (buffer de 64 bytes, acepta 1..63) y <b>{@code client_connect()} lo lee de
     * ahi</b> (refs 0x42664 / 0x429a0 / 0x42f70) para resolver el rendezvous.
     * Debe llamarse <b>antes</b> de {@link #connect}.
     */
    public static native boolean clientSetVuid(long handle, String vuid);

    /** Consulta de parametros internos del dispositivo. */
    public static native int clientDevInfo(long handle, String arg, String arg2);

    public static native int[] checkBuffer(long handle, int arg);

    public static native int[] checkMode(long handle);

    public static native String getDid(long handle);

    public static native void clearIpsListener();
}
