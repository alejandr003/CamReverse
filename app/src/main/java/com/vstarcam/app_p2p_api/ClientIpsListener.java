package com.vstarcam.app_p2p_api;

/**
 * Descubrimiento de direcciones alternativas del dispositivo (para reconexion
 * rapida cuando cambia de red).
 */
public interface ClientIpsListener {
    /**
     * @param uid identificador del dispositivo
     * @param ips lista de direcciones detectadas
     */
    void ipsListener(String uid, String ips);
}
