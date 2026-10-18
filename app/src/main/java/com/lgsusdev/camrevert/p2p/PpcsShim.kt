package com.lgsusdev.camrevert.p2p

/**
 * Puente mínimo a `PPCS_Initialize()` de `libOKSMARTPPCS.so`.
 *
 * **Por qué hace falta.** Nada en el XAPK llama a `PPCS_Initialize`. La única
 * referencia del binario está en la vtable de interfaz (`0xb6838`): su entrada
 * `+0x00` es `Initialize_V1` (`0x44b7c`), que sí hace `bl PPCS_Initialize@plt` con el
 * InitString de `0x26b3a`. Pero **nadie invoca esa entrada**: `client_create`
 * (`0x41bac`) llama a `[iface1+0x10]`, que es `NetworkDetect_V1`, no
 * `Initialize_V1`, y no existe `.init_array`. Sin inicializar, el SDK no dispone de
 * la configuración de servidores que aporta el InitString/licencia
 * (`"Server_String"`, `"Server_IP"`, `{"LibType":"C","LicenseReq":""}`).
 *
 * **Semántica real de −5 (corregida).** `P2ptErrStrGet` (`0x7da88`) traduce
 * código→nombre: la rama de `w0 == -5` (`0x7de58`, `add x3,x3,#0xb38`) apunta a
 * `0x29b38` = **`ERROR_PPPP_INVALID_PARAMETER`**; `NOT_INITIALIZED` es el **−1**
 * (`0x29a78`). `cs2p2p_PPPP_Initialize` devuelve −5 solo por `InitString == NULL`
 * (`0x4faa0 cbz x22` → `0x4fcb8 mov w0,#-0x5`), y `cs2p2p_PPPP_ConnectByServer`
 * devuelve −5 cuando su 4.º argumento (`ServerString`) es NULL (`0x6025c cbz x20` →
 * `0x60480 mov w0,#-0x5`), con el log
 * `ERROR_PPCS_INVALID_PARAMETER (Hint: ServerString==NULL)` @ `0x27890`.
 * Es decir: el −5 de `client_connect` significa «parámetro inválido», no
 * «el SDK no está inicializado» — aunque una inicialización correcta es la vía
 * para que el SDK tenga `ServerString`. Ver §10.9 de AGENTS.md.
 *
 * `PPCS_Initialize` no es un símbolo `Java_*`, así que no puede enlazarse desde
 * Kotlin: hace falta este `.c` que lo localiza con `dlopen`/`dlsym`. El InitString
 * (108 chars) vive dentro del propio `.so` @ `0x25d10`; está copiado literal en
 * `cpp/ppcs_shim.c`.
 *
 * Se llama **una sola vez** antes de la cascada ([initialize] es idempotente a
 * nivel de app; a nivel de SDK una segunda llamada devuelve `-2`, que aquí se
 * traduce a `true` porque significa «ya inicializado»).
 */
object PpcsShim {

    /** `libpcs_shim.so`, compilada por CMake desde `app/src/main/cpp/`. */
    init {
        System.loadLibrary("pcs_shim")
    }

    @JvmStatic
    private external fun nativeInitialize(): Int

    @JvmStatic
    private external fun nativeInitString(): String

    /** Devuelve el InitString que se inyecta (para la consola diagnóstica). */
    fun initString(): String = try {
        nativeInitString()
    } catch (t: Throwable) {
        "<no disponible: ${t.javaClass.simpleName}>"
    }

    /**
     * Llama a `PPCS_Initialize(InitString)`.
     *
     * @return `true` si el SDK quedó inicializado. Los códigos `-1000`/`-1001`
     * (falta la librería o el símbolo) y `-1002` (excepción) también devuelven
     * `false`; `-2` del SDK («ya inicializado») cuenta como éxito.
     */
    fun initialize(): Boolean {
        val rc = try {
            nativeInitialize()
        } catch (t: Throwable) {
            log("PPCS_Initialize", "ERROR ${t.javaClass.simpleName}: ${t.message}")
            return false
        }
        log("PPCS_Initialize", "rc=$rc (${explain(rc)})")
        return rc == 0 || rc == -2
    }

    private fun explain(rc: Int): String = when {
        rc == 0 -> "PPCS_SUCCESSFUL"
        rc == -2 -> "PPCS_ALREADY_INITIALIZED — ya estaba inicializado"
        rc == -5 -> "ERROR_PPPP_INVALID_PARAMETER — InitString NULL/rechazado (NO es «no inicializado»)"
        rc == -1 -> "ERROR_PPPP_NOT_INITIALIZED"
        rc == -1000 -> "no se pudo dlopen(libOKSMARTPPCS.so)"
        rc == -1001 -> "PPCS_Initialize no está exportado"
        rc == -1002 -> "excepción JNI"
        else -> "revisar §10.9 de AGENTS.md"
    }

    /** Conexión al [P2pLogSink], inyectada por `P2PControlManager` para no
     *  acoplar este objeto a la consola diagnóstica. */
    internal var sink: ((String, String) -> Unit)? = null

    private fun log(where: String, msg: String) {
        sink?.invoke(where, msg) ?: android.util.Log.d("CamRevert", "$where: $msg")
    }
}
