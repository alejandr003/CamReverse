package com.lgsusdev.camrevert.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import androidx.annotation.RequiresApi
import com.lgsusdev.camrevert.data.CamConfig
import com.lgsusdev.camrevert.p2p.P2PControlManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Intercomunicador de la cámara: captura el micrófono, lo codifica y lo envía por
 * el túnel P2P, y reproduce lo que devuelve la cámara.
 *
 * ### Punto 4 — dos modos
 *
 * | Modo | CGI | Ecos | Uso |
 * |---|---|---|---|
 * | **0 · Llamada (manos libres / dúplex)** | `callstatus.cgi?status=1&` al entrar y `?status=0&` al salir | `AcousticEchoCanceler` + `NoiseSuppressor` | Se habla y se oye a la vez |
 * | **1 · Radio (push-to-talk)** | ninguno: solo se abre el flujo | `NoiseSuppressor` | Se habla mientras se mantiene pulsado; **el altavoz de los streams se silencia** |
 *
 * ### Formato de audio (evidencia)
 *
 * PCM **8000 Hz · 16 bits · mono** (el estándar de G.711 y el que
 * `app_check_sample_rate` valida en `libOKSMARTPLAY.so`), comprimido a
 * **G.711 A-law** por defecto (`CamConfig.voiceCodec` permite µ-law o PCM crudo).
 * La trama se envía cada 20 ms (160 muestras → 160 B alaw) con
 * `JNIApi.write(handle, canal, bytes, timeout)`.
 *
 * El **canal** (`CamConfig.voiceChannel`, por defecto 1) es distinto del 0, que
 * está reservado para CGI: `client_write_command` escribe ahí su cabecera
 * `01 0A 00 00 <len16>`. El canal exacto de voz no está determinado en el
 * binario (ver AGENTS.md §5.4) y es ajustable desde Configuración; la consola
 * diagnóstica P2P muestra el retorno de cada `write` para poder afinarlo.
 *
 * ### Límite heredado del SDK
 *
 * `libOKSMARTPPCS.so` solo expone al JNI `client_write` (**envío**). El `client_read`
 * interno no tiene símbolo `Java_com_vstarcam_JNIApi_*`, así que **la recepción de
 * audio depende de que llegue por `ClientCommandListener`**. `onIncoming()` está
 * cableado para eso: cualquier payload que no sea texto CGI se decodifica y se
 * reproduce. Ver AGENTS.md §5.4.
 */
object VoiceLink {

    enum class State { IDLE, PTT, DUPLEX }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Último error legible para la UI. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var sendJob: Job? = null
    private var playJob: Job? = null

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null

    // ------------------------------------------------------------------
    // Arranque / parada
    // ------------------------------------------------------------------

    /**
     * Inicia el intercomunicador.
     *
     * @param cfg     configuración (canal, códec)
     * @param duplex  `true` = modo 0 (llamada, con `callstatus` + AEC),
     *                `false` = modo 1 (radio PTT, sin `callstatus`)
     * @param onSpeakerDuck callback para silenciar/restaurar los streams de vídeo
     */
    @SuppressLint("MissingPermission")
    fun start(
        cfg: CamConfig,
        duplex: Boolean,
        onSpeakerDuck: (Boolean) -> Unit = {},
    ) {
        if (_state.value != State.IDLE) return
        _error.value = null

        val minBuf = AudioRecord.getMinBufferSize(
            G711.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE) {
            _error.value = "AudioRecord no soporta 8 kHz mono PCM16"
            return
        }
        val bufSize = maxOf(minBuf, G711.FRAME_BYTES_PCM * 4)

        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                G711.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize,
            )
        } catch (t: Throwable) {
            _error.value = "AudioRecord: ${t.message}"
            return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            _error.value = "AudioRecord no inicializado (¿permiso RECORD_AUDIO?)"
            return
        }
        audioRecord = rec

        // --- Efectos de audio ---
        // El modo 0 (dúplex) necesita AEC: sin él el altavoz de la cámara y el
        // micrófono del móvil forman un lazo. El NS sirve en ambos modos.
        val session = rec.audioSessionId
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            if (NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(session)?.also {
                    it.enabled = true
                }
            }
        }
        if (duplex && Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            if (AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(session)?.also {
                    it.enabled = true
                }
            }
        }

        // --- Altavoz de recepción (dúplex) ---
        if (duplex) {
            openTrack()
            _state.value = State.DUPLEX
            P2PControlManager.setCallStatus(true)
            onSpeakerDuck(true)
        } else {
            _state.value = State.PTT
            onSpeakerDuck(true)
        }

        try {
            rec.startRecording()
        } catch (t: Throwable) {
            _error.value = "startRecording: ${t.message}"
            // Limpieza síncrona: `start()` no es suspendida y todavía no hay jobs
            // que cancelar, así que no hace falta pasar por `stop()`.
            _state.value = State.IDLE
            if (duplex) P2PControlManager.setCallStatus(false)
            runCatching { rec.release() }
            audioRecord = null
            aec?.release(); aec = null
            ns?.release(); ns = null
            onSpeakerDuck(false)
            return
        }

        sendJob = scope.launch { sendLoop(cfg) }
        if (duplex) playJob = scope.launch { playLoop() }
    }

    /**
     * Detiene el intercomunicador y restaura los streams.
     *
     * @param duplex debe coincidir con el modo usado en [start]
     */
    suspend fun stop(cfg: CamConfig, onSpeakerDuck: (Boolean) -> Unit = {}) {
        val wasDuplex = _state.value == State.DUPLEX
        _state.value = State.IDLE

        sendJob?.cancelAndJoin()
        sendJob = null
        playJob?.cancelAndJoin()
        playJob = null

        if (wasDuplex) P2PControlManager.setCallStatus(false)

        audioRecord?.let { rec ->
            runCatching { rec.stop() }
            rec.release()
        }
        audioRecord = null

        audioTrack?.let { track ->
            runCatching { track.stop() }
            track.release()
        }
        audioTrack = null

        aec?.release()
        aec = null
        ns?.release()
        ns = null

        onSpeakerDuck(false)
    }

    /** Cambia PTT ↔ dúplex en caliente (reabre con los efectos correctos). */
    fun isActive(): Boolean = _state.value != State.IDLE

    /**
     * Variante no suspendida de [stop] para `onDestroy`/cierre: corre en el scope
     * propio del objeto, no en el `lifecycleScope` (que ya estaría cancelado).
     */
    fun stopAsync(cfg: CamConfig, onSpeakerDuck: (Boolean) -> Unit = {}) {
        scope.launch { stop(cfg, onSpeakerDuck) }
    }

    // ------------------------------------------------------------------
    // Captura y envío
    // ------------------------------------------------------------------

    private suspend fun sendLoop(cfg: CamConfig) {
        val rec = audioRecord ?: return
        val buffer = ByteArray(G711.FRAME_BYTES_PCM)
        val codec = cfg.voiceCodec

        while (scope.isActive && _state.value != State.IDLE) {
            var read = 0
            while (read < buffer.size) {
                val n = rec.read(buffer, read, buffer.size - read)
                if (n <= 0) break
                read += n
            }
            if (read <= 0) {
                if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) break
                continue
            }

            val payload = when (codec) {
                CamConfig.CODEC_G711U -> G711.pcmToUlaw(buffer, 0, read)
                CamConfig.CODEC_PCM16 -> buffer.copyOf(read)
                else -> G711.pcmToAlaw(buffer, 0, read)
            }

            val ok = P2PControlManager.writeBytes(cfg.voiceChannel, payload)
            if (!ok && _state.value == State.PTT && P2PControlManager.state.value !=
                com.lgsusdev.camrevert.p2p.ConnState.ONLINE
            ) {
                _error.value = "Túnel P2P caído: no se puede enviar voz"
                break
            }
        }
    }

    // ------------------------------------------------------------------
    // Recepción (mejor esfuerzo — ver kdoc)
    // ------------------------------------------------------------------

    private fun openTrack() {
        val minBuf = AudioTrack.getMinBufferSize(
            G711.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) return
        val buf = maxOf(minBuf, G711.FRAME_BYTES_PCM * 4)
        audioTrack = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(G711.SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(buf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } else {
            @Suppress("DEPRECATION")
            AudioTrack(
                AudioManager.STREAM_VOICE_CALL,
                G711.SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                buf,
                AudioTrack.MODE_STREAM,
            )
        }
        runCatching { audioTrack?.play() }
    }

    private fun playLoop() {
        val track = audioTrack ?: return
        while (scope.isActive && _state.value != State.IDLE) {
            val pending = pendingBuffer
            if (pending != null && pending.isNotEmpty()) {
                pendingBuffer = null
                runCatching { track.write(pending, 0, pending.size) }
            } else {
                Thread.sleep(20)
            }
        }
    }

    @Volatile
    private var pendingBuffer: ByteArray? = null

    /**
     * Alimenta la recepción de voz. Lo llama `P2PControlManager` con los payloads
     * binarios que llegan por `ClientCommandListener` (no son CGI).
     *
     * @param codec códec con el que la cámara está transmitiendo
     */
    @Synchronized
    fun onIncoming(data: ByteArray, codec: Int = CamConfig.CODEC_G711A) {
        if (_state.value != State.DUPLEX) return
        val pcm = when (codec) {
            CamConfig.CODEC_G711U -> G711.ulawToPcm(data)
            CamConfig.CODEC_PCM16 -> data
            else -> G711.alawToPcm(data)
        }
        // Acumula en una cola sencilla de un tramo: el consumidor es un solo hilo.
        val prev = pendingBuffer
        pendingBuffer = if (prev == null) pcm else prev + pcm
        if (pendingBuffer!!.size > MAX_PENDING) {
            pendingBuffer = pcm
        }
    }

    /** Efectos realmente activos, para mostrarlos en la UI. */
    data class Effects(val echoCanceler: Boolean, val noiseSuppressor: Boolean)

    fun effects(): Effects = Effects(echoCanceler = aec?.enabled == true, noiseSuppressor = ns?.enabled == true)

    private const val MAX_PENDING = 8192
}
