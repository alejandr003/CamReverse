package com.vstarcam.app_p2p_api;

/**
 * Respuestas crudas de la camara al trafico CGI enviado con
 * {@link JNIApi#writeCgi}. Invocado desde el hilo nativo.
 */
public interface ClientCommandListener {
    /**
     * @param handle sesion de origen
     * @param data   payload UTF-8 (texto CGI o frame binario)
     * @param length longitud util de {@code data}
     */
    void commandListener(long handle, byte[] data, int length);
}
