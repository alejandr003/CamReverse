package com.lgsusdev.camrevert.ui

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import com.lgsusdev.camrevert.R
import com.lgsusdev.camrevert.data.CamConfig

/**
 * Vista de una lente: superficie de video + HUD pasivo + selector HD/SD.
 *
 * Encapsula el ciclo de vida del [ExoPlayer] RTSP para que la Activity solo
 * llame a [start], [stop] y [release].
 *
 * ### Punto 2 — medición pasiva, sin sondas TCP
 *
 * Se eliminó `StreamHealthMonitor` y con él **todos los sockets TCP de sondeo a
 * los puertos 10554/10555**: eran dos conexiones por cámara por segundo que el
 * propio servidor RTSP terminaba cerrando, y contribuían a los cortes de stream.
 *
 * El HUD mide ahora con lo que Media3 ya sabe, **cero tráfico añadido**, y con
 * **dos fuentes redundantes** para que nunca se quede fijo en `0 KB/s`:
 *
 *  - **KB/s (principal)** → [DefaultBandwidthMeter], que implementa
 *    [androidx.media3.datasource.TransferListener]. Media3 lo registra dentro
 *    de `RtspMediaSource.mediaTransferListener` y este lo pasa a los canales
 *    RTP (`TransferRtpDataChannel`/`UdpDataSourceRtpDataChannel` llaman a
 *    `addTransferListener`), así que cuenta los bytes reales ya transportados.
 *    El aviso llega por [BandwidthMeter.EventListener.onBandwidthSample].
 *  - **KB/s (respaldo)** → estimación a partir de los **fotogramas decodificados**:
 *    se muestrea [ExoPlayer.getVideoDecoderCounters] una vez por segundo y se
 *    multiplica el delta de `renderedOutputBufferCount` por el tamaño medio de
 *    trama que corresponde a la resolución. En equipo real el `BandwidthMeter`
 *    del canal RTP de Media3 no emite muestras, así que sin este respaldo el
 *    HUD se quedaba clavado en `0 KB/s`; ahora se usa el respaldo cada vez que
 *    la fuente principal lleva más de [REAL_SAMPLE_STALE_MS] sin reportar.
 *  - **Resolución** → [AnalyticsListener.onVideoSizeChanged].
 *  - **Estado** → [Player.Listener.onPlaybackStateChanged].
 *
 * El [ExoPlayer] **no se recrea** por cambios de UI (calidad, mute, zoom,
 * pantalla completa): solo `setMediaSource` + `prepare` para cambiar de stream
 * y `volume`/`playWhenReady` para los estados.
 */
@UnstableApi
class CameraStreamView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    fun interface OnFullscreenListener {
        fun onFullscreen(view: CameraStreamView)
    }

    private val zoomLayout: ZoomableVideoLayout
    private val tvLabel: TextView
    private val tvHud: TextView
    private val tvPlaceholder: TextView
    private val progressBuffer: ProgressBar
    private val btnFullscreen: ImageView
    private val btnHd: TextView
    private val btnSd: TextView
    private val btnZoomOut: TextView
    private val btnZoomIn: TextView
    private val tvZoomLevel: TextView

    private var player: ExoPlayer? = null
    private var config: CamConfig? = null
    private var bandwidthMeter: DefaultBandwidthMeter? = null

    /** 0 = lente fija, 1 = lente PTZ. */
    var cameraIndex: Int = 0

    var onFullscreen: OnFullscreenListener? = null

    private var hd: Boolean = true
    private var running = false

    // ---- Medición pasiva (ver kdoc) ----
    @Volatile
    private var kbps: Long = 0L

    /** Instante (ms monotónicos) de la última muestra **real** del `BandwidthMeter`. */
    @Volatile
    private var lastRealSampleAt = 0L

    /** Respaldo: bits/s estimados a partir de los fotogramas decodificados. */
    @Volatile
    private var kbpsEstimated: Long = 0L

    private var prevRenderedFrames = 0
    private var prevFrameSampleAt = 0L

    /**
     * Sello del **último fotograma nuevo decodificado**. Es la base del aviso
     * «Sin conexión» (punto 6): con la cámara reiniciándose, el reproductor se queda en
     * `BUFFERING` indefinidamente y sin esto el HUD diría «Buffering» para siempre en vez
     * de reconocer que la cámara no está.
     */
    private var lastFrameAtMs = 0L
    private var videoW = 0
    private var videoH = 0

    private var resolutionText = "--"
    private var lastErrorName: String? = null

    /** Fallos seguidos del stream; se reinician al volver a `STATE_READY`. */
    private var consecutiveErrors = 0

    /**
     * Fallos seguidos contados **por separado** para el respaldo de transporte
     * (TCP↔UDP). No se reutiliza [consecutiveErrors] porque el bajón HD→SD lo
     * reinicia a 0 y entonces uno de los dos mecanismos se comería los fallos
     * del otro.
     */
    private var transportErrors = 0

    /** Intentos de reconexión en curso, para el backoff exponencial. */
    private var reconnectAttempts = 0

    /**
     * ¿Ha llegado a decodificarse algún fotograma? **Es la clave del punto 5.**
     *
     * Mientras no haya imagen, la tarjeta no tiene nada que enseñar y el recuadro
     * central es útil. En cuanto hay imagen, **nunca más se tapa**: se conserva el
     * último fotograma (el `TextureView` lo mantiene) y el estado se cuenta en el
     * HUD. Antes, cada `onPlayerError` y cada `STATE_ENDED` ponían el recuadro
     * encima del vídeo, y **eso es exactamente el «se recarga cada pocos segundos»
     * que se veía** (verificado: la sesión RTSP no se caía ni una vez, era la UI).
     */
    private var hasVideo = false

    /** Reconexiones completadas, visibles en el HUD como `↻n`. */
    private var reconnectCount = 0

    /**
     * Muestra el spinner **solo si el buffering persiste**. Enseñarlo al instante
     * hacía parpadear la tarjeta en cada microcorte del WiFi, aunque el stream se
     * recuperase solo en milisegundos.
     */
    private val bufferOverlay = Runnable {
        if (running && player?.playbackState == Player.STATE_BUFFERING) {
            progressBuffer.visibility = VISIBLE
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val hudTicker = object : Runnable {
        override fun run() {
            if (!running) return
            sampleDecoderCounters()
            updateHud()
            mainHandler.postDelayed(this, HUD_PERIOD_MS)
        }
    }

    /**
     * Reintento automático del stream con backoff exponencial.
     *
     * Sin esto, cualquier corte del servidor RTSP (reinicio de la cámara, WiFi
     * saturado, cámara durmiendo) dejaba el hueco en blanco o en «error» hasta
     * que el usuario tocaba algo: eso es el *refresh* que se veía cada pocos
     * segundos. Ahora la vista se recupera sola.
     */
    private val reconnectRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            val cfg = config ?: return

            // Bajón automático HD → SD: los equipos de gama baja (p. ej. una
            // Lenovo Tab M10) no sostienen el main-stream de 1080p por RTSP y
            // acaban cayendo en bucle de rebuffering.
            if (hd && consecutiveErrors >= HD_FALLBACK_AFTER) {
                android.util.Log.i(
                    "CamRevert",
                    "$label: $consecutiveErrors fallos seguidos en HD → se baja a SD",
                )
                hd = false
                cfg.setHdQuality(cameraIndex, false)
                refreshQualityButtons()
                consecutiveErrors = 0
            }

            resetCounters()
            // Punto 6: la tarjeta se recupera sola y **lo cuenta** en el HUD (`↻n`),
            // para poder distinguir «no se ha caído» de «se cayó y volvió».
            reconnectCount++
            playSource(cfg.rtspUri(cameraIndex, hd))
        }
    }

    private val label: String get() = tvLabel.text?.toString().orEmpty()

    private fun scheduleReconnect() {
        if (!running) return
        mainHandler.removeCallbacks(reconnectRunnable)
        val attempt = reconnectAttempts
        val delayMs = (RECONNECT_BASE_MS shl attempt.coerceAtMost(4))
            .coerceAtMost(RECONNECT_MAX_MS)
        mainHandler.postDelayed(reconnectRunnable, delayMs)
        reconnectAttempts = attempt + 1
    }

    private fun cancelReconnect() {
        mainHandler.removeCallbacks(reconnectRunnable)
        reconnectAttempts = 0
        consecutiveErrors = 0
    }

    private val bandwidthListener =
        BandwidthMeter.EventListener { elapsedMs, bytesTransferred, _ ->
            if (elapsedMs > 0) {
                kbps = bytesTransferred * 8L / elapsedMs // bits/s
                lastRealSampleAt = android.os.SystemClock.elapsedRealtime()
            }
        }

    /**
     * Muestrea [ExoPlayer.getVideoDecoderCounters] para estimar el caudal cuando
     * el `BandwidthMeter` no reporta (caso real en el equipo: el canal RTP de
     * Media3 no emite `onBandwidthSample`, y el HUD se quedaba en `0 KB/s`).
     *
     * `renderedOutputBufferCount` es un contador **acumulado**, así que se mide el
     * delta entre muestras y se multiplica por los bits medios de un fotograma a la
     * resolución real del stream (≈0,05 bits/píxel, típico de H.264 en 1080p25:
     * 1920·1080·0,05 = 103 680 bit/trama · 25 fps ≈ 2,6 Mbit/s ≈ 324 KB/s).
     */
    private fun sampleDecoderCounters() {
        val exo = player ?: return
        val counters = try {
            exo.videoDecoderCounters
        } catch (_: Throwable) {
            null
        } ?: return
        try {
            counters.ensureUpdated()
        } catch (_: Throwable) {
            return
        }

        val rendered = counters.renderedOutputBufferCount
        val now = android.os.SystemClock.elapsedRealtime()
        val dt = now - prevFrameSampleAt
        val delta = rendered - prevRenderedFrames
        prevRenderedFrames = rendered
        prevFrameSampleAt = now
        // Un fotograma nuevo = el stream está vivo. Este sello es lo que permite pasar
        // de «Buffering» eterno a «Sin conexión» cuando la cámara no está (punto 6).
        if (delta > 0) lastFrameAtMs = now
        if (dt < MIN_SAMPLE_MS || delta <= 0) return

        val px = (videoW.toLong() * videoH.toLong()).coerceAtLeast(1L)
        val bitsPerFrame = (px * BITS_PER_PIXEL).toLong().coerceAtLeast(1_000L)
        kbpsEstimated = delta * bitsPerFrame * 1000L / dt
    }

    init {
        LayoutInflater.from(context).inflate(R.layout.view_camera, this, true)
        zoomLayout = findViewById(R.id.zoomLayout)
        tvLabel = findViewById(R.id.tvLabel)
        tvHud = findViewById(R.id.tvHud)
        tvPlaceholder = findViewById(R.id.tvPlaceholder)
        progressBuffer = findViewById(R.id.progressBuffer)
        btnFullscreen = findViewById(R.id.btnFullscreen)
        btnHd = findViewById(R.id.btnHd)
        btnSd = findViewById(R.id.btnSd)
        btnZoomOut = findViewById(R.id.btnZoomOut)
        btnZoomIn = findViewById(R.id.btnZoomIn)
        tvZoomLevel = findViewById(R.id.tvZoomLevel)

        btnFullscreen.setOnClickListener { onFullscreen?.onFullscreen(this) }
        btnHd.setOnClickListener { setQuality(hd = true) }
        btnSd.setOnClickListener { setQuality(hd = false) }
        btnZoomOut.setOnClickListener {
            zoomLayout.setScale(zoomLayout.currentScale / ZOOM_STEP)
            refreshZoomBar()
        }
        btnZoomIn.setOnClickListener {
            zoomLayout.setScale(zoomLayout.currentScale * ZOOM_STEP)
            refreshZoomBar()
        }
        zoomLayout.onSingleTap = { toggleControlsOverlay() }
        zoomLayout.onZoomChanged = ZoomableVideoLayout.OnZoomChangedListener { refreshZoomBar() }
        refreshZoomBar()
    }

    /** Configura etiqueta e índice de lente antes de [start]. */
    fun setup(config: CamConfig, cameraIndex: Int, label: String) {
        this.config = config
        this.cameraIndex = cameraIndex
        tvLabel.text = label
        this.hd = config.hdQuality(cameraIndex)
        refreshQualityButtons()
    }

    // ------------------------------------------------------------------
    // Ciclo de vida del stream
    // ------------------------------------------------------------------

    /**
     * Fábrica de renderers con **respaldo de decodificador** (punto 3).
     *
     * `setEnableDecoderFallback(true)` es el mecanismo oficial de Media3: si el
     * decodificador de hardware rechaza el perfil o la resolución, se prueba el
     * siguiente candidato de la lista y se acaba usando el de **software**
     * (`c2.android.avc.decoder` / `OMX.google.h264.decoder`). Sin esto, una Lenovo
     * Tab M10 no instanciaba el main-stream de 2304×1296 con el decoder de
     * hardware y el reproductor se quedaba en bucle de rebuffering.
     *
     * [CamConfig.preferSoftwareDecoder] permite además **empezar** directamente por
     * software (`MediaCodecSelector.PREFER_SOFTWARE`) en equipos muy justos.
     */
    private fun buildRenderersFactory(cfg: CamConfig): DefaultRenderersFactory {
        val factory = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
        if (cfg.preferSoftwareDecoder) {
            factory.setMediaCodecSelector(MediaCodecSelector.PREFER_SOFTWARE)
            android.util.Log.i("CamRevert", "$label: decodificador forzado a SOFTWARE")
        }
        return factory
    }

    /** Crea el reproductor y arranca el stream. Idempotente. */
    fun start() {
        if (running || config == null) return
        running = true
        val cfg = config ?: return

        // Medidor de caudal propio: es el TransferListener que Media3 registra
        // en la fuente RTSP (punto 2). Se crea una vez por sesión de player.
        val meter = DefaultBandwidthMeter.Builder(context).build()
        meter.addEventListener(mainHandler, bandwidthListener)
        bandwidthMeter = meter

        val exo = ExoPlayer.Builder(context, buildRenderersFactory(cfg))
            .setBandwidthMeter(meter)
            // Carga holgada: `LoadControl` es una cuestión de nivel *player* en
            // Media3 (no existe `MediaSourceFactory.setLoadControl`). Los valores
            // por defecto están pensados para ficheros progresivos y hacen
            // rebuffering innecesario con un stream en directo cuyo bitrate salta
            // de picos de varios Mbit/s a pocos cientos de kbit/s de golpe.
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        MIN_BUFFER_MS,
                        MAX_BUFFER_MS,
                        MIN_BUFFER_FOR_PLAYBACK_MS,
                        MIN_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
                    )
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build(),
            )
            .build()
        player = exo
        exo.volume = if (isMuted) 0f else 1f
        exo.playWhenReady = true
        exo.addListener(playerListener)
        exo.addAnalyticsListener(analyticsListener)
        zoomLayout.attachToPlayer(exo)
        resetCounters()
        hasVideo = false
        reconnectCount = 0
        playSource(cfg.rtspUri(cameraIndex, hd))
        mainHandler.removeCallbacks(hudTicker)
        mainHandler.post(hudTicker)
        updateHud()
    }

    /** Pausa y libera el reproductor manteniendo la vista (rotación, app en background). */
    fun stop() {
        running = false
        mainHandler.removeCallbacks(hudTicker)
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.removeCallbacks(bufferOverlay)
        player?.removeListener(playerListener)
        player?.removeAnalyticsListener(analyticsListener)
        bandwidthMeter?.removeEventListener(bandwidthListener)
        bandwidthMeter = null
        player?.release()
        player = null
        hasVideo = false
        tvPlaceholder.text = context.getString(R.string.state_idle)
        tvPlaceholder.visibility = VISIBLE
        progressBuffer.visibility = GONE
    }

    /** Alias semántico para el cierre definitivo. */
    fun release() = stop()

    /**
     * Cambia la fuente RTSP **sin recrear** el [ExoPlayer]: solo se sustituye el
     * `MediaSource` y se llama a `prepare()`.
     */
    private fun playSource(uri: String) {
        val exo = player ?: return
        val cfg = config
        val factory = RtspMediaSource.Factory()
            // ── La clave del «refresh» periódico ──────────────────────────────
            // `setTimeoutMs` NO es solo el timeout del handshake RTSP:
            // `TransferRtpDataChannelFactory(timeoutMs)` se lo pasa a
            // `TransferRtpDataChannel(long)`, es decir, es el **read-timeout del
            // socket de datos RTP** (y `UdpDataSourceRtpDataChannelFactory` lo usa
            // igual, como `socketTimeoutMs`). Con 8 s, cualquier pausa real de la
            // cámara de más de 8 s tiraba el stream con una excepción de E/S, y
            // eso es exactamente el ciclo que se veía cada pocos segundos.
            // 30 s sigue detectando un stream muerto, pero tolera las pausas
            // normales de una cámara WiFi barata.
            .setTimeoutMs(RTSP_TIMEOUT_MS)
            // Diálogo RTSP completo en logcat (tag `ExoPlayer:RtspMediaSource*`):
            // DESCRIBE/SETUP/PLAY y el transporte realmente negociado.
            .setDebugLoggingEnabled(true)

        // Transporte RTP. `false` = UDP con respaldo automático a TCP si el
        // servidor rechaza RTP/AVP; `true` = forzar interleaved sobre TCP, sin
        // respaldo. Se lee de prefs porque `CameraStreamView` lo puede voltear
        // solo ante fallos repetidos.
        factory.setForceUseRtpTcp(cfg?.rtspTransportTcp ?: true)

        exo.setMediaSource(factory.createMediaSource(MediaItem.fromUri(uri)))
        exo.prepare()
        // Punto 5 — aquí estaba el parpadeo. Antes se hacía, en cada intento:
        //     tvPlaceholder.visibility = GONE
        //     progressBuffer.visibility = VISIBLE
        // o sea, recuadro fuera y spinner dentro **de golpe**, para cada cambio de
        // calidad y cada reintento. Ahora:
        //  - Con imagen ya decodificada: NO se tapa nada. El `TextureView` conserva
        //    el último fotograma y el estado se cuenta en el HUD.
        //  - Sin imagen todavía: se dice qué está pasando, que es lo único útil.
        if (hasVideo) {
            progressBuffer.visibility = GONE
        } else {
            tvPlaceholder.text = context.getString(R.string.state_connecting)
            tvPlaceholder.visibility = VISIBLE
        }
        // El spinner solo sale si el buffering se alarga: en microcortes de WiFi el
        // stream se recupera antes y la tarjeta no parpadea.
        mainHandler.removeCallbacks(bufferOverlay)
        mainHandler.postDelayed(bufferOverlay, BUFFER_OVERLAY_DELAY_MS)
    }

    /** Cambio de calidad: **no** se reconstruye el reproductor. */
    private fun setQuality(hd: Boolean) {
        if (this.hd == hd) return
        this.hd = hd
        val cfg = config ?: return
        cfg.setHdQuality(cameraIndex, hd)
        refreshQualityButtons()
        if (running) {
            cancelReconnect()
            resetCounters()
            playSource(cfg.rtspUri(cameraIndex, hd))
        }
    }

    private fun refreshQualityButtons() {
        btnHd.setTextColor(if (hd) Color.WHITE else 0xFF93A3B4.toInt())
        btnSd.setTextColor(if (hd) 0xFF93A3B4.toInt() else Color.WHITE)
        btnHd.alpha = if (hd) 1f else 0.75f
        btnSd.alpha = if (hd) 0.75f else 1f
    }

    private fun toggleControlsOverlay() {
        val row = controlsRow ?: return
        val hidden = row.visibility == VISIBLE
        row.visibility = if (hidden) GONE else VISIBLE
        qualityRow?.visibility = row.visibility
    }

    private val controlsRow: android.view.View?
        get() = findViewById(R.id.controlsRow)

    private val qualityRow: android.view.View?
        get() = findViewById(R.id.qualityRow)

    // ------------------------------------------------------------------
    // Sonido (control único global en la barra superior)
    // ------------------------------------------------------------------

    /** `true` cuando la app tiene el sonido apagado (punto 6: un solo interruptor). */
    var isMuted: Boolean = true
        private set

    /** Lo llama `MainActivity` desde el único interruptor global: ya no hay altavoz por cámara. */
    fun applyGlobalSound(enabled: Boolean) {
        isMuted = !enabled
        player?.volume = if (isMuted) 0f else 1f
    }

    /** Silencia esta lente mientras suena el intercomunicador (punto 4, modo PTT). */
    fun duckForIntercom(duck: Boolean) {
        player?.volume = when {
            duck -> 0f
            isMuted -> 0f
            else -> 1f
        }
    }

    // ------------------------------------------------------------------
    // Zoom
    // ------------------------------------------------------------------

    /** Etiqueta de la lente («Fija» / «PTZ»), para la cabecera de pantalla completa. */
    val lensLabel: String get() = tvLabel.text.toString()

    /** Cambia el zoom programáticamente (botones de pantalla completa y barra `[-]/[+]`). */
    fun setScale(value: Float) {
        zoomLayout.setScale(value)
        refreshZoomBar()
    }

    /**
     * Muestra u oculta la barra `[⛶] · [-] 1x [+] · HD/SD` embebida.
     *
     * En pantalla completa (punto 6) se oculta porque mandan los controles
     * flotantes translúcidos del overlay; al salir se restaura.
     */
    fun setEmbeddedControlsVisible(visible: Boolean) {
        val v = if (visible) VISIBLE else GONE
        controlsRow?.visibility = v
        qualityRow?.visibility = v
    }

    val zoomLevel: Float get() = zoomLayout.currentScale

    fun resetZoom() {
        zoomLayout.resetZoom()
        refreshZoomBar()
    }

    private fun refreshZoomBar() {
        val scale = zoomLayout.currentScale
        tvZoomLevel.text = String.format(java.util.Locale.US, "%.1fx", scale)
        btnZoomOut.alpha = if (scale > ZoomableVideoLayout.MIN_SCALE + 0.01f) 1f else 0.4f
    }

    /**
     * Instantánea del frame actual (punto 6 — «Tomar foto»).
     *
     * Devuelve `null` si el `TextureView` todavía no tiene contenido. La imagen
     * se recorta con el **mismo encuadre `CENTER_CROP`** que el `Matrix` de zoom,
     * para que la foto no lleve bandas negras ni resulte deformada.
     */
    fun captureBitmap(): android.graphics.Bitmap? {
        val src = zoomLayout.textureView.bitmap ?: return null
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return null

        val viewAspect = if (height > 0) width.toFloat() / height.toFloat() else 1f
        val contentAspect = w.toFloat() / h.toFloat()
        if (kotlin.math.abs(viewAspect - contentAspect) < 0.01f) return src

        // CENTER_CROP: se recorta la dimensión SOBRANTE (la que sobresale), no la
        // que falta, de forma que el resultado cubre el encuadre de la vista.
        val cropW: Int
        val cropH: Int
        if (contentAspect > viewAspect) {
            // Contenido más ancho que la vista → se recorta a los lados.
            cropH = h
            cropW = (h * viewAspect).toInt().coerceAtMost(w)
        } else {
            // Contenido más alto que la vista → se recorta arriba/abajo.
            cropW = w
            cropH = (w / viewAspect).toInt().coerceAtMost(h)
        }
        val left = (w - cropW) / 2
        val top = (h - cropH) / 2
        return android.graphics.Bitmap.createBitmap(src, left, top, cropW, cropH)
    }

    // ------------------------------------------------------------------
    // Estado del reproductor
    // ------------------------------------------------------------------

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    // El spinner NO sale de golpe: se pospone (microcortes de WiFi).
                    tvPlaceholder.text = context.getString(R.string.state_buffering)
                    mainHandler.removeCallbacks(bufferOverlay)
                    mainHandler.postDelayed(bufferOverlay, BUFFER_OVERLAY_DELAY_MS)
                }

                Player.STATE_READY -> {
                    progressBuffer.visibility = GONE
                    tvPlaceholder.visibility = GONE
                    mainHandler.removeCallbacks(bufferOverlay)
                    // Stream recuperado: se cancela cualquier reintento pendiente.
                    consecutiveErrors = 0
                    transportErrors = 0
                    reconnectAttempts = 0
                    mainHandler.removeCallbacks(reconnectRunnable)
                }

                Player.STATE_ENDED -> {
                    // Un DIRECTO que «termina» no es que acabe la película: es que el
                    // servidor cerró el stream (la cámara se reinició, el WiFi cayó).
                    // Antes la tarjeta se quedaba en «Finalizado» para siempre y había
                    // que tocar algo a mano; ahora se reintenta sola (punto 6).
                    android.util.Log.i(
                        "CamRevert",
                        "$label: stream finalizado por el servidor → reintento automático",
                    )
                    if (!hasVideo) {
                        tvPlaceholder.text = context.getString(R.string.state_offline)
                        tvPlaceholder.visibility = VISIBLE
                    }
                    // OJO: **no** se tocan `consecutiveErrors`/`transportErrors`. Un
                    // `ENDED` es el servidor cerrando el stream, no un fallo de códec
                    // ni de transporte; contarlo degradaría la lente a SD sola (y
                    // persistido) cada vez que la cámara se reinicia.
                    scheduleReconnect()
                }

                Player.STATE_IDLE -> {
                    progressBuffer.visibility = GONE
                }
            }
            updateHud()
        }

        override fun onPlayerError(error: PlaybackException) {
            progressBuffer.visibility = GONE
            mainHandler.removeCallbacks(bufferOverlay)
            lastErrorName = error.errorCodeName
            consecutiveErrors++
            transportErrors++

            // Punto 6: mientras haya imagen decodificada, la tarjeta NO se tapa (se
            // conserva el último fotograma y el HUD dice «Reconectando…»). Sin imagen,
            // el aviso es explícito: «Sin conexión», no un «Finalizado» mudo.
            if (!hasVideo) {
                tvPlaceholder.text = context.getString(R.string.state_offline)
                tvPlaceholder.visibility = VISIBLE
            }

            // Cadena completa hasta logcat: `errorCodeName` alone se queda corto
            // («ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE» no dice quién lo lanzó),
            // y lo que importa es el mensaje de la causa de abajo del todo.
            android.util.Log.e(
                "CamRevert",
                "$label: stream caído " +
                    "(#${consecutiveErrors}) code=${error.errorCode} " +
                    "name=${error.errorCodeName} transporte=" +
                    (config?.rtspTransportTcp?.let { if (it) "TCP" } ?: "UDP") + "\n" +
                    describeCauses(error),
            )

            maybeFlipTransport()
            updateHud()
            scheduleReconnect()
        }
    }

    /**
     * Vuelca la cadena de `Throwable` completa. En RTSP el dato útil casi siempre
     * está en el `cause` de abajo del todo (`SocketTimeoutException`, un 401 de la
     * cámara, o el `RtspException` con el fallo de SETUP).
     */
    private fun describeCauses(error: Throwable): String = buildString {
        var t: Throwable? = error
        var depth = 0
        while (t != null && depth < 6) {
            append("  ".repeat(depth))
            append(t.javaClass.simpleName)
            t.message?.let { append(": ").append(it) }
            append('\n')
            t = t.cause
            depth++
        }
    }.trimEnd()

    /**
     * Respaldo de transporte: el camino TCP **no tiene fallback** en Media3
     * (`TransferRtpDataChannelFactory` no lo implementa), así que si el equipo no
     * se lleva bien con el interleaved hay que probarlo a mano. Aquí se hace solo,
     * con persistencia, para que no haga falta recompilar para probar la otra
     * vía.
     */
    private fun maybeFlipTransport() {
        if (transportErrors < TRANSPORT_FLIP_AFTER) return
        val cfg = config ?: return
        val wasTcp = cfg.rtspTransportTcp
        cfg.rtspTransportTcp = !wasTcp
        android.util.Log.w(
            "CamRevert",
            "$label: $transportErrors fallos seguidos → transporte RTP " +
                "${if (wasTcp) "TCP interleaved" else "UDP"} → " +
                "${if (!wasTcp) "TCP interleaved" else "UDP"} (persistido)",
        )
        transportErrors = 0
    }

    /** Fuente de la resolución (punto 2). El caudal lo da el `BandwidthMeter`. */
    private val analyticsListener = object : AnalyticsListener {
        override fun onVideoSizeChanged(
            eventTime: AnalyticsListener.EventTime,
            videoSize: androidx.media3.common.VideoSize,
        ) {
            videoW = videoSize.width
            videoH = videoSize.height
            resolutionText = if (videoSize.width > 0 && videoSize.height > 0) {
                "${videoSize.width}x${videoSize.height}"
            } else {
                "--"
            }
            if (videoSize.width > 0 && videoSize.height > 0) {
                zoomLayout.setContentAspect(videoSize.width, videoSize.height)
            }
            updateHud()
        }

        /**
         * Primer fotograma realmente pintado. **A partir de aquí la tarjeta nunca
         * más se tapa** con el recuadro de estado: el último fotograma se queda
         * congelado y el estado se cuenta en el HUD (punto 5). Es el dato que
         * distingue «arrancando» de «se cayó y volverá».
         */
        override fun onRenderedFirstFrame(
            eventTime: AnalyticsListener.EventTime,
            output: Any,
            renderTimeMs: Long,
        ) {
            hasVideo = true
            tvPlaceholder.visibility = GONE
            progressBuffer.visibility = GONE
            mainHandler.removeCallbacks(bufferOverlay)
            lastFrameAtMs = android.os.SystemClock.elapsedRealtime()
        }
    }

    private fun resetCounters() {
        kbps = 0L
        kbpsEstimated = 0L
        lastRealSampleAt = 0L
        prevRenderedFrames = 0
        prevFrameSampleAt = android.os.SystemClock.elapsedRealtime()
        // OJO: `lastFrameAtMs` **no** se borra aquí a propósito. `resetCounters()` corre
        // antes de cada reintento; si se borrase, el detector de atasco volvería a cero
        // en cada intento y una cámara caída se quedaría en «Buffering» eterno en vez de
        // decir «Sin conexión» (que es justo lo que se pidió en el punto 6). En arranque
        // en frío no hay falso aviso porque `hasVideo` es `false` hasta el primer
        // fotograma y el estado exige `hasVideo`.
        resolutionText = "--"
        lastErrorName = null
    }

    private fun updateHud() {
        if (!running) return
        val exo = player
        val now = android.os.SystemClock.elapsedRealtime()
        // Punto 6 — **detector de atasco**. Con la cámara reiniciándose el reproductor se
        // queda en `BUFFERING` eternamente; sin esto el HUD diría «Buffering» para
        // siempre en vez de reconocer que **la cámara no está**. `lastFrameAtMs` se
        // refresca con cada fotograma nuevo decodificado.
        val stalled = hasVideo && lastFrameAtMs > 0 && now - lastFrameAtMs > STALL_AFTER_MS
        val state = when {
            exo == null -> context.getString(R.string.state_idle)
            stalled -> context.getString(R.string.state_offline)
            exo.playbackState == Player.STATE_READY -> context.getString(R.string.state_playing)
            reconnectAttempts > 0 -> context.getString(R.string.state_reconnecting)
            exo.playbackState == Player.STATE_BUFFERING -> context.getString(R.string.state_buffering)
            exo.playbackState == Player.STATE_ENDED -> context.getString(R.string.state_ended)
            else -> context.getString(R.string.state_idle)
        }
        // Fuente principal = BandwidthMeter (bytes reales). Si lleva más de
        // REAL_SAMPLE_STALE_MS sin reportar, se usa la estimación por fotogramas
        // para que el HUD nunca se quede clavado en 0 KB/s.
        val realFresh = !stalled && now - lastRealSampleAt <= REAL_SAMPLE_STALE_MS
        // Si no llegan fotogramas, el caudal es **0**: conservar el último valor medido
        // hacía que el HUD se contradijera («Sin conexión» con 69 KB/s).
        val kb = if (stalled) 0.0 else (if (realFresh) kbps else kbpsEstimated) / 1000.0
        val err = lastErrorName
        tvHud.text = buildString {
            append(state)
            if (reconnectCount > 0) {
                append("  ↻")
                append(reconnectCount)
            }
            append('\n')
            append(resolutionText)
            append("  ")
            append(String.format(java.util.Locale.US, "%.0f KB/s", kb))
            // «~» = valor estimado a partir de fotogramas, no medido.
            if (!realFresh && !stalled) append(" ~")
            if (err != null) {
                append('\n')
                append(err)
            }
        }
    }

    companion object {
        /**
         * Timeout RTSP. Ojo: **no es solo el handshake** — Media3 lo entrega a
         * `TransferRtpDataChannelFactory(timeoutMs)` /
         * `UdpDataSourceRtpDataChannelFactory(socketTimeoutMs)`, con lo que acaba
         * siendo el read-timeout del **socket de datos RTP**. A 8 s cualquier
         * pausa de la cámara producía una excepción de E/S y el «refresh»
         * periódico; a 30 s se toleran las pausas reales de una cámara WiFi.
         */
        private const val RTSP_TIMEOUT_MS = 30_000L

        /**
         * Fallos seguidos tras los que se invierte el transporte RTP (TCP↔UDP).
         * Es un respaldo: si el TCP forzado se queda sin servicio no hay manera
         * de que Media3 lo negocie solo, y tampoco al revés.
         */
        /**
         * Fallos seguidos tras los que se invierte el transporte RTP (TCP↔UDP).
         * **Subido de 3 a 5**: invertir el transporte fuerza un cambio de stream (un
         * parpadeo real), así que solo se hace cuando el problema es persistente.
         */
        private const val TRANSPORT_FLIP_AFTER = 5

        // Tamaños de búfer de `DefaultLoadControl` para un stream en directo.
        private const val MIN_BUFFER_MS = 25_000
        private const val MAX_BUFFER_MS = 60_000

        /**
         * Reanudación tras un rebuffering. **Bajado de 2 s/5 s a 1 s/1,5 s**: los
         * valores por defecto están calibrados para ficheros progresivos y en un
         * directo solo alargan el hueco visible justo cuando el stream ya volvió.
         */
        private const val MIN_BUFFER_FOR_PLAYBACK_MS = 1_000
        private const val MIN_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 1_500

        private const val HUD_PERIOD_MS = 1000L
        private const val ZOOM_STEP = 1.5f

        /**
         * Retardo antes de enseñar el spinner de buffering (punto 5). Un microcorte
         * de WiFi que se resuelve en menos de ~1,5 s **no parpadea nada**: el
         * `TextureView` conserva el último fotograma y el HUD ya dice «Buffering».
         */
        private const val BUFFER_OVERLAY_DELAY_MS = 1_500L

        /**
         * Sin fotogramas nuevos durante este tiempo, el HUD deja de decir «Buffering» y
         * dice **«Sin conexión»** (punto 6). 6 s: por debajo quedan los tirones normales
         * de una cámara WiFi, y por encima el usuario ya está mirando una imagen
         * congelada que necesita explicación.
         */
        private const val STALL_AFTER_MS = 6_000L

        /** Antigüedad a partir de la cual la muestra real se considera perdida. */
        private const val REAL_SAMPLE_STALE_MS = 2_500L
        private const val MIN_SAMPLE_MS = 500L

        /**
         * Reintento automático del stream: backoff 0,5 s → 1 s → 2 s → 4 s → 8 s
         * (tope). Se reinicia al volver a `STATE_READY`. Antes empezaba en 1 s y
         * llegaba a 16 s; con la sesión ya estable lo que importa es que un corte
         * real se resuelva **cuanto antes**, porque cada segundo de espera es un
         * segundo de tarjeta parada.
         */
        private const val RECONNECT_BASE_MS = 500L
        private const val RECONNECT_MAX_MS = 8_000L

        /**
         * Fallos seguidos en HD tras los que se baja a SD automáticamente. **Subido
         * de 2 a 4**: con 2, un par de hipos del WiFi degradaban la lente a SD y
         * **lo dejaban persistido**, de modo que la app se quedaba en 448×256 para
         * siempre sin que se supiera por qué.
         */
        private const val HD_FALLBACK_AFTER = 4

        /** Bits por píxel de un fotograma H.264 (≈0,05 → 1080p25 ≈ 2,6 Mbit/s). */
        private const val BITS_PER_PIXEL = 0.05f
    }
}
