package com.lgsusdev.camrevert.audio

import kotlin.math.abs

/**
 * Codificación **G.711 A-law y µ-law** (y paso a/d PCM 16 bits), el códec de
 * intercomunicador que usa la cámara O-KAM / VStarcam.
 *
 * ### Evidencia (ingeniería inversa)
 *
 * - `libapp.so` contiene la cadena **`supportG711`** (flag de capacidad del equipo).
 * - `libOKSMARTPLAY.so` exporta `app_g711_encoder_create`,
 *   `app_g711_u_encoder_create`, `app_adpcm_encoder_create`,
 *   `app_adpcm_encoder_multiple`, `app_aac_encoder_create` y
 *   `app_check_sample_rate` → el equipo acepta **G.711 A (alaw)**, **G.711 µ (ulaw)**,
 *   **ADPCM** y **AAC**, siempre sobre PCM de 8 kHz mono.
 * - `Java_com_veepai_AppPlayerApi_startVoiceRecord` confirma que la app original
 *   captura PCM y lo codifica en el lado cliente antes de enviarlo.
 *
 * ### Implementación
 *
 * Decodificadores según la referencia **ITU-T G.711** (mismas fórmulas que
 * `g711.c` de Sun / el código de referencia del estándar), y **codificadores por
 * cuantizador de vecino más próximo**: se precalcula la tabla de los 256 niveles
 * de reconstrucción y se elige el código cuyo nivel esté más cerca de la muestra.
 * Esa es la regla de decisión óptima para un cuantizador no uniforme, así que es
 * equivalente (o mejor) que el codificador umbral del estándar.
 *
 * 8 kHz × 16 bits × mono = 16 000 B/s de PCM → 8 000 B/s comprimidos (2:1).
 * Trama típica de 20 ms = 160 muestras = 320 B PCM = 160 B G.711.
 */
object G711 {

    const val SAMPLE_RATE = 8000
    const val CHANNELS = 1
    /** Muestras por trama de 20 ms (el intervalo de packetización habitual). */
    const val FRAME_SAMPLES = SAMPLE_RATE / 50
    const val FRAME_BYTES_PCM = FRAME_SAMPLES * 2 // 16 bits
    const val FRAME_BYTES_ALAW = FRAME_SAMPLES // 1 byte por muestra

    // ------------------------------------------------------------------
    // Decodificadores (referencia ITU-T G.711)
    // ------------------------------------------------------------------

    private const val SIGN = 0x80
    private const val QUANT_MASK = 0x0f
    private const val SEG_MASK = 0x70
    private const val SEG_SHIFT = 4
    private const val BIAS = 0x84

    /** G.711 A-law → PCM 16 bits con signo. */
    fun decodeAlaw(code: Int): Int {
        val a = (code and 0xFF) xor 0x55 // deshace la inversión de bits alternos
        val q = a and QUANT_MASK
        val seg = (a and SEG_MASK) shr SEG_SHIFT
        val t = when (seg) {
            0 -> (q shl 4) + 8
            1 -> (q shl 4) + 0x108
            else -> (((q shl 4) + 0x108)) shl (seg - 1)
        }
        return if (a and SIGN != 0) t else -t
    }

    /** G.711 µ-law → PCM 16 bits con signo. */
    fun decodeUlaw(code: Int): Int {
        val u = code.inv() and 0xFF
        var t = ((u and QUANT_MASK) shl 3) + BIAS
        t = t shl ((u and SEG_MASK) shr SEG_SHIFT)
        return if (u and SIGN != 0) BIAS - t else t - BIAS
    }

    // ------------------------------------------------------------------
    // Tablas de reconstrucción + codificadores de vecino más próximo
    // ------------------------------------------------------------------

    private val A2L = IntArray(256) { decodeAlaw(it) }
    private val U2L = IntArray(256) { decodeUlaw(it) }

    /** PCM 16 bits → código A-law. */
    fun encodeAlaw(sample: Int): Int {
        var best = 0
        var bestErr = Int.MAX_VALUE
        val s = sample.coerceIn(-32768, 32767)
        for (i in 0..255) {
            val e = abs(A2L[i] - s)
            if (e < bestErr) {
                bestErr = e
                best = i
                if (e == 0) break
            }
        }
        return best
    }

    /** PCM 16 bits → código µ-law. */
    fun encodeUlaw(sample: Int): Int {
        var best = 0
        var bestErr = Int.MAX_VALUE
        val s = sample.coerceIn(-32768, 32767)
        for (i in 0..255) {
            val e = abs(U2L[i] - s)
            if (e < bestErr) {
                bestErr = e
                best = i
                if (e == 0) break
            }
        }
        return best
    }

    // ------------------------------------------------------------------
    // Conversión de buffers (little-endian, como Android AudioRecord entrega)
    // ------------------------------------------------------------------

    /** PCM 16 bits LE → G.711 A-law. */
    fun pcmToAlaw(pcm: ByteArray, offset: Int = 0, length: Int = pcm.size - offset): ByteArray {
        val out = ByteArray(length)
        var i = 0
        while (i + 1 < length) {
            val sample = (pcm[offset + i].toInt() and 0xFF) or
                (pcm[offset + i + 1].toInt() shl 8)
            out[i] = encodeAlaw(sample).toByte()
            i += 2
        }
        return out
    }

    /** PCM 16 bits LE → G.711 µ-law. */
    fun pcmToUlaw(pcm: ByteArray, offset: Int = 0, length: Int = pcm.size - offset): ByteArray {
        val out = ByteArray(length)
        var i = 0
        while (i + 1 < length) {
            val sample = (pcm[offset + i].toInt() and 0xFF) or
                (pcm[offset + i + 1].toInt() shl 8)
            out[i] = encodeUlaw(sample).toByte()
            i += 2
        }
        return out
    }

    /** G.711 A-law → PCM 16 bits LE. */
    fun alawToPcm(alaw: ByteArray, offset: Int = 0, length: Int = alaw.size - offset): ByteArray {
        val out = ByteArray(length * 2)
        for (i in 0 until length) {
            val v = decodeAlaw(alaw[offset + i].toInt())
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    /** G.711 µ-law → PCM 16 bits LE. */
    fun ulawToPcm(ulaw: ByteArray, offset: Int = 0, length: Int = ulaw.size - offset): ByteArray {
        val out = ByteArray(length * 2)
        for (i in 0 until length) {
            val v = decodeUlaw(ulaw[offset + i].toInt())
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }
}
