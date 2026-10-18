package com.lgsusdev.camrevert.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.Log
import com.lgsusdev.camrevert.BuildConfig
import com.lgsusdev.camrevert.p2p.P2PControlManager
import com.vstarcam.JNIApi

/**
 * Inyector de CGI **solo para compilaciones DEBUG**.
 *
 * Permite enviar una cadena CGI cualquiera por el túnel P2P ya abierto, para comparar
 * variantes contra el equipo real sin recompilar la app en cada prueba. La respuesta
 * sigue saliendo por la consola diagnóstica y por `logcat -s CamRevert-P2P`.
 *
 * ```
 * $b64 = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("decoder_control.cgi?command=0&onestep=0&"))
 * adb shell am broadcast -n com.lgsusdev.camrevert/.debug.CgiDebugReceiver --es b64 $b64
 * ```
 *
 * Se usa base64 a propósito: los `&` y `?` del CGI rompen el paso de argumentos por
 * `adb shell`.
 *
 * Variantes seleccionables por extras (todas contra el handle activo):
 *
 * | Extra | Efecto |
 * |---|---|
 * | *(ninguno)* | [P2PControlManager.send] → `writeCgi` (envoltorio `GET /…loginuse=…`) |
 * | `--ez nvr true` | `writeCgiNVR` — **misma cabecera pero payload crudo**, sin el envoltorio |
 * | `--ei chan N` | Cabecera de `client_write_command` (`01 0A 00 00 <len> 00 00`) + payload, escritos en el canal `N` |
 * | `--ez deaf true` | Fuerza la condición de **túnel sordo** para probar el vigilante (§5.15) sin esperar al disparo real, que es intermitente |
 */
class CgiDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG) return

        // Acción suelta para repetir el preludio de autorización sin recompilar:
        //   adb shell am broadcast -n com.lgsusdev.camrevert/.debug.CgiDebugReceiver --ez reauth true
        if (intent.getBooleanExtra("reauth", false)) {
            P2PControlManager.reauthenticate()
            Log.d(TAG, "[debugCgi] reauthenticate() solicitado")
            return
        }

        // Condición de túnel sordo sin depender del disparo real (intermitente):
        //   adb shell am broadcast -n com.lgsusdev.camrevert/.debug.CgiDebugReceiver --ez deaf true
        if (intent.getBooleanExtra("deaf", false)) {
            val ok = P2PControlManager.simulateDeafTunnel()
            Log.d(TAG, "[debugCgi] simulateDeafTunnel() -> $ok")
            return
        }

        val b64 = intent.getStringExtra("b64") ?: return
        val s = runCatching { String(Base64.decode(b64, Base64.NO_WRAP)) }.getOrNull() ?: return

        val nvr = intent.getBooleanExtra("nvr", false)
        val chan = intent.getIntExtra("chan", -1)
        val h = P2PControlManager.debugHandle()
        if (h == 0L) {
            Log.d(TAG, "[debugCgi] SIN TÚNEL: $s")
            return
        }

        val ok = runCatching {
            when {
                nvr -> JNIApi.writeCgiNVR(h, s, TIMEOUT)
                chan >= 0 -> writeCommand(h, chan, s)
                else -> P2PControlManager.send(s)
            }
        }.getOrElse {
            Log.d(TAG, "[debugCgi] EXCEPCIÓN ${it.javaClass.simpleName}: ${it.message}")
            false
        }
        Log.d(TAG, "[debugCgi] ok=$ok nvr=$nvr chan=$chan s=$s")
    }

    /** Réplica de `client_write_command`: cabecera de 8 bytes + payload, en un canal dado. */
    private fun writeCommand(h: Long, channel: Int, s: String): Boolean {
        val payload = s.toByteArray(Charsets.UTF_8)
        val buf = ByteArray(8 + payload.size)
        buf[0] = 0x01
        buf[1] = 0x0A
        buf[2] = 0x00
        buf[3] = 0x00
        buf[4] = (payload.size and 0xFF).toByte()
        buf[5] = ((payload.size shr 8) and 0xFF).toByte()
        buf[6] = 0x00
        buf[7] = 0x00
        System.arraycopy(payload, 0, buf, 8, payload.size)
        val n = JNIApi.write(h, channel, buf, TIMEOUT)
        Log.d(TAG, "[debugCgi] write(ch=$channel) rc=$n cabecera+payload=${buf.size}B")
        return n > 0
    }

    private companion object {
        const val TAG = "CamRevert-P2P"
        const val TIMEOUT = 5000
    }
}
