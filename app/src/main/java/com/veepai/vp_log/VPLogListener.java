package com.veepai.vp_log;

/**
 * Destino del log nativo de nivel 4 (FILE) que emite {@code vp_printf}.
 *
 * <p>Firma verificada sobre {@code libvp_log.so}: el metodo se llama
 * {@code fileLogListener} y su descriptor es {@code (Ljava/lang/String;)V}.
 * Ambos literales estan en {@code .rodata} ({@code 0x1101} y {@code 0x1202}) y
 * se los pasa {@code GetMethodID} en {@code Java_com_veepai_vp_1log_VPLogApi_setListener}.
 *
 * <p>El nombre y el paquete <b>no se pueden tocar</b>: el enlace JNI usa el
 * simbolo {@code Java_com_veepai_vp_1log_VPLogApi_setListener}.
 */
public interface VPLogListener {
    /**
     * @param line linea ya formateada por el SDK
     */
    void fileLogListener(String line);
}
