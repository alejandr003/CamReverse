package com.vstarcam.app_p2p_api;

/**
 * Cambios de estado del tunel P2P.
 * Invocado desde el hilo nativo: implementar sin bloquear.
 */
public interface ClientStateListener {
    /**
     * @param handle sesion afectada
     * @param state  codigo de estado reportado por el SDK PPCS
     */
    void stateListener(long handle, int state);
}
