package com.vstarcam.app_p2p_api;

/**
 * El SDK nativo avisa de que una sesion debe liberar su referencia Java.
 */
public interface ClientReleaseListener {
    void releaseListener(long handle);
}
