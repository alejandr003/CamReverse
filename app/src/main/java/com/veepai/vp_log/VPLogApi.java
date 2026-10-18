package com.veepai.vp_log;

/**
 * Fachada JNI sobre {@code libvp_log.so} (13 KB), que es dependencia {@code NEEDED}
 * de {@code libOKSMARTPPCS.so} y por tanto ya se carga con el SDK P2P.
 *
 * <p>Existe unicamente para enganchar el <b>log nativo</b> del SDK a la consola
 * diagnostica de la app. Sin esto, el motivo real por el que {@code connect()}
 * falla es invisible: solo se ve el {@code rc} normalizado.
 *
 * <h3>Circuito verificado en el binario</h3>
 * <pre>
 * setListener(l)
 *   struct@0xACD8 = { env, NewGlobalRef(l),
 *                     GetMethodID(GetObjectClass(l), "fileLogListener", "(Ljava/lang/String;)V") }
 *   vp_print_set_file(log_file_callback)   // 0xACF0 = log_file_callback  [.got, .rela.dyn]
 *
 * client_connect -> vp_printf(nivel = 4)     // "mov w0, #0x4" en cada log
 *   0x21D8 cmp w19, #4      // solo el nivel 4 despacha al callback
 *   0x21E8 blr  [0xACF0]    // -> log_file_callback
 *             -> CallVoidMethod(l, "fileLogListener", texto)
 * </pre>
 *
 * <p>{@code vp_printf} ademas escribe a logcat con tag {@code flutter} siempre que
 * {@code nivelGlobal <= nivelMensaje}; el nivel global arranca en 0 (.bss), asi que
 * esos mensajes tambien se pueden cazar con {@code adb logcat -s flutter}.
 *
 * <p>Los nombres de paquete, clase y metodos <b>no se pueden renombrar</b>: el
 * enlace resuelve por simbolo {@code Java_com_veepai_vp_1log_VPLogApi_<metodo>}
 * ({@code _1} codifica el guion bajo del paquete {@code vp_log}).
 */
public final class VPLogApi {

    /** Niveles tal y como los declara el SDK. */
    public static final int VP_LOG_INFO = 0;
    public static final int VP_LOG_DEBUG = 1;
    public static final int VP_LOG_WARN = 2;
    public static final int VP_LOG_ERROR = 3;
    public static final int VP_LOG_FILE = 4;

    static {
        System.loadLibrary("vp_log");
    }

    private VPLogApi() {
    }

    /** Corresponde a {@code vp_print_set_level}: guarda en {@code 0xACE8}. */
    private static native void setLevel(int level);

    /** Corresponde a {@code Java_com_veepai_vp_1log_VPLogApi_setListener}. */
    private static native void setListener(VPLogListener listener);

    public static void setLogLevel(int level) {
        setLevel(level);
    }

    public static void setLogListener(VPLogListener listener) {
        setListener(listener);
    }
}
