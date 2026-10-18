package com.lgsusdev.camrevert.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.max

/**
 * Contenedor de video con zoom digital (pinch + arrastre) sobre un
 * [TextureView].
 *
 * ### Punto 6 — `TextureView` en todas las versiones
 *
 * Antes se alternaba `SurfaceView` (API 29+) y `TextureView` (API 24-28). Esa
 * bifurcación causaba dos problemas: `SurfaceView` ignora `scaleX`/`translationX`
 * por debajo de Android 10, y en Android 10+ la escala se aplicaba al plano de
 * la superficie pero **no** a los controles superpuestos, además de que
 * `SurfaceView` recorta el video a la ventana y rompe el arrastre con zoom.
 *
 * Ahora se usa **`TextureView` siempre**: una sola ruta de render sobre todas
 * las versiones, con transformación por [Matrix] (`setTransform`), que es la
 * vía documentada para escalar/trasladar el contenido de un `TextureView`
 * sin tocar la vista (funciona igual en API 24 y en API 37).
 *
 * El [Matrix] se calcula a partir de [MIN_SCALE]..[MAX_SCALE] (1.0x – 8.0x),
 * el centro de la imagen y el desplazamiento del usuario, con `postScale`/
 * `postTranslate` alrededor del centro. `TextureView` no trunca el contenido:
 * el marco se pinta con `TextureView.getTransform()`.
 *
 * Nota: `TextureView` es una vista de hardware "normal", así que las vistas
 * hermanas (chips, HUD, barra `[-] 1x [+]`) se componen por encima en orden
 * de llegada, sin artefactos de z-order que daba el `SurfaceView`.
 */
class ZoomableVideoLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    fun interface OnZoomChangedListener {
        fun onZoomChanged(scale: Float)
    }

    var onZoomChanged: OnZoomChangedListener? = null
    var onSingleTap: (() -> Unit)? = null

    /** Siempre `null`: el render va por [textureView] en todas las versiones. */
    val surfaceView: android.view.SurfaceView? = null
    val textureView: TextureView

    private var scale = MIN_SCALE
    /** Desplazamiento del contenido ya normalizado al tamaño de la vista. */
    private var panX = 0f
    private var panY = 0f

    private var activePointerId = INVALID_POINTER
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var panning = false

    /** Relación de aspecto del contenido renderizado; se sincroniza desde el HUD. */
    private var contentAspect = VIDEO_ASPECT

    /**
     * Fija la relación de aspecto real del stream para que el encuadre
     * `fitCenter` no deforme el vídeo (lo llama `CameraStreamView` desde
     * `AnalyticsListener.onVideoSizeChanged`).
     */
    fun setContentAspect(widthPx: Int, heightPx: Int) {
        if (widthPx <= 0 || heightPx <= 0) return
        val aspect = widthPx.toFloat() / heightPx.toFloat()
        if (kotlin.math.abs(aspect - contentAspect) < 0.01f) return
        contentAspect = aspect
        applyTransform()
    }

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                // scaleFactor es relativo al frame anterior: se acumula.
                setScale(scale * detector.scaleFactor, detector.focusX, detector.focusY)
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                clampPan()
                applyTransform()
            }
        },
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onDoubleTap(e: MotionEvent): Boolean {
                // Doble toque: vuelve a 1.0x, o sube a 3.0x si ya estaba al 1.0x
                if (scale > MIN_SCALE + 0.05f) {
                    panX = 0f
                    panY = 0f
                    setScale(MIN_SCALE, e.x, e.y)
                } else {
                    setScale(3f, e.x, e.y)
                }
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                onSingleTap?.invoke()
                return true
            }
        },
    )

    init {
        val tv = TextureView(context)
        textureView = tv
        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                surface: SurfaceTexture,
                width: Int,
                height: Int,
            ) = applyTransform()

            override fun onSurfaceTextureSizeChanged(
                surface: SurfaceTexture,
                width: Int,
                height: Int,
            ) = applyTransform()

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
        addView(tv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        isClickable = true
    }

    /** Enlaza el reproductor con el [TextureView] (única superficie). */
    fun attachToPlayer(player: ExoPlayer) {
        player.setVideoTextureView(textureView)
        textureView.post { applyTransform() }
    }

    /** Zoom actual (1.0 = sin zoom). */
    val currentScale: Float get() = scale

    /**
     * Aplica el zoom manteniendo el punto de interés `(focusX, focusY)` estable.
     *
     * El transform final es `M = T(pan) · S(scale) · T(-center)` calculado con
     * `Matrix.postScale/postTranslate` sobre un [Matrix] identidad.
     */
    fun setScale(value: Float, focusX: Float = width / 2f, focusY: Float = height / 2f) {
        val newScale = value.coerceIn(MIN_SCALE, MAX_SCALE)
        if (newScale == scale) {
            applyTransform()
            return
        }
        // Mantiene bajo el dedo el mismo punto del contenido.
        val ratio = newScale / scale
        panX = focusX - width / 2f - (focusX - width / 2f - panX) * ratio
        panY = focusY - height / 2f - (focusY - height / 2f - panY) * ratio
        scale = newScale
        if (scale <= MIN_SCALE + 0.001f) {
            panX = 0f
            panY = 0f
        }
        clampPan()
        applyTransform()
        onZoomChanged?.onZoomChanged(scale)
    }

    fun resetZoom() {
        panX = 0f
        panY = 0f
        setScale(MIN_SCALE)
    }

    /**
     * Reconstruye el [Matrix] del [TextureView].
     *
     * ### Punto 2 — encuadre base `CENTER_CROP` (sin bandas negras)
     *
     * Antes se usaba `fitCenter`: el contenido se *encajaba dentro* de la vista, de
     * modo que cualquier desajuste entre la relación de aspecto de la vista y la del
     * stream dejaba **bandas negras arriba/abajo o a los lados**, justo el defecto
     * observable a 1.0x. Ahora la base es **`CENTER_CROP`**: la imagen siempre
     * **cubre** el contenedor y lo que sobra se recorta, de forma que a 1.0x no hay
     * ninguna banda.
     *
     * Ojo: `TextureView` estira el buffer sobre el rectángulo de la vista, así que la
     * corrección de aspecto es **no uniforme** (`setScale(sx, sy)`). Con
     *
     * ```
     * if (contentAspect > viewAspect) sx = contentAspect / viewAspect, sy = 1
     * else                            sx = 1,                             sy = viewAspect / contentAspect
     * ```
     *
     * el resultado es siempre indistorsionado y cubre la vista:
     *  - vista 16:9 y contenido 16:9 → `sx = sy = 1` (ni recorte ni deformación);
     *  - vista más ancha que el contenido → `sy > 1`: crece en alto y se recorta arriba/abajo;
     *  - vista más alta que el contenido → `sx > 1`: crece en ancho y se recorta a los lados.
     *
     * Sobre esa base se aplican el zoom ([MIN_SCALE]..[MAX_SCALE], 1.0x–8.0x) y el
     * desplazamiento del usuario, ambos alrededor del centro.
     */
    private fun applyTransform() {
        val v = textureView
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val (sx, sy) = coverScale(w, h)
        val m = Matrix()

        // 1) Base center-crop (corrige la deformación del buffer y cubre la vista).
        m.setScale(sx, sy, w / 2f, h / 2f)

        // 2) Zoom alrededor del centro.
        if (scale != MIN_SCALE) m.postScale(scale, scale, w / 2f, h / 2f)

        // 3) Desplazamiento del usuario.
        if (panX != 0f || panY != 0f) m.postTranslate(panX, panY)

        v.setTransform(m)
    }

    /**
     * Escala no uniforme que hace que el contenido **cubra** la vista sin
     * deformarse. `sx * w` y `sy * h` son las dimensiones reales ya dibujadas.
     */
    private fun coverScale(w: Float, h: Float): Pair<Float, Float> {
        val viewAspect = w / h
        return if (contentAspect > viewAspect) {
            (contentAspect / viewAspect) to 1f
        } else {
            1f to (viewAspect / contentAspect)
        }
    }

    /**
     * Impide arrastrar el marco fuera del contenido visible.
     *
     * Se calcula sobre el tamaño **ya cubierto** (`sx·w` × `sy·h`, ver [coverScale])
     * multiplicado por el zoom, de modo que en 1.0x solo se pueda mover lo que el
     * `CENTER_CROP` dejó recortado.
     */
    private fun clampPan() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val (sx, sy) = coverScale(w, h)
        val scaledW = w * sx * scale
        val scaledH = h * sy * scale
        val maxX = max(0f, (scaledW - w) / 2f)
        val maxY = max(0f, (scaledH - h) / 2f)
        panX = panX.coerceIn(-maxX, maxX)
        panY = panY.coerceIn(-maxY, maxY)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                lastTouchX = event.x
                lastTouchY = event.y
                panning = scale > MIN_SCALE && !scaleDetector.isInProgress
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                panning = false
            }

            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(activePointerId)
                val canPan = scale > MIN_SCALE &&
                    !scaleDetector.isInProgress &&
                    index >= 0 &&
                    event.pointerCount == 1
                if (canPan) {
                    panX += event.getX(index) - lastTouchX
                    panY += event.getY(index) - lastTouchY
                    lastTouchX = event.getX(index)
                    lastTouchY = event.getY(index)
                    clampPan()
                    applyTransform()
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val upIndex = event.actionIndex
                if (event.getPointerId(upIndex) == activePointerId) {
                    val newIndex = if (upIndex == 0) 1 else 0
                    activePointerId = event.getPointerId(newIndex)
                }
                lastTouchX = event.getX(event.findPointerIndex(activePointerId))
                lastTouchY = event.getY(event.findPointerIndex(activePointerId))
                panning = false
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activePointerId = INVALID_POINTER
                panning = false
            }
        }
        return true
    }

    companion object {
        const val MIN_SCALE = 1f
        const val MAX_SCALE = 8f

        /** Relación de aspecto por defecto hasta conocer la del stream (16:9). */
        private const val VIDEO_ASPECT = 16f / 9f
        private const val INVALID_POINTER = -1
    }
}
