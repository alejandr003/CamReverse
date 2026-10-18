package com.lgsusdev.camrevert

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.util.UnstableApi
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.tabs.TabLayout
import com.lgsusdev.camrevert.audio.VoiceLink
import com.lgsusdev.camrevert.data.CamConfig
import com.lgsusdev.camrevert.p2p.CamConfigHolder
import com.lgsusdev.camrevert.net.LanScanner
import com.lgsusdev.camrevert.p2p.CgiResponse
import com.lgsusdev.camrevert.p2p.ConnState
import com.lgsusdev.camrevert.p2p.NightMode
import com.lgsusdev.camrevert.p2p.P2PControlManager
import com.lgsusdev.camrevert.p2p.P2pEvent
import com.lgsusdev.camrevert.p2p.PtzDirection
import com.lgsusdev.camrevert.ui.CameraStreamView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Actividad única de la app.
 *
 * Reorganizada en la refactorización de 7 puntos:
 *
 *  1. Preconfiguración, ciclo P2P sin `null` en JNI y **consola diagnóstica en vivo**.
 *  2. HUD pasivo (sin sondas TCP) — la lógica vive en [CameraStreamView].
 *  3. Luz blanca real y 4 modos de visión nocturna.
 *  4. Botón **Llamada** con radio PTT y manos libres dúplex ([VoiceLink]).
 *  5. Gestor completo de MicroSD (tarjeta, programación, formateo, navegador y reproductor).
 *  6. `TextureView` + zoom, sonido global, selector de lente, pantalla completa con
 *     controles flotantes y cuadrícula **Funciones Comunes**.
 *  7. `AGENTS.md` actualizado y build limpio.
 */
@OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity() {

    private lateinit var config: CamConfig

    // ---- Barra superior / estructura ----
    private lateinit var contentLayout: LinearLayout
    private lateinit var topBar: LinearLayout
    private lateinit var stateDot: View
    private lateinit var tvP2pState: TextView
    private lateinit var btnP2pToggle: TextView
    private lateinit var btnSoundGlobal: ImageView
    private lateinit var btnConsole: ImageView
    private lateinit var btnSettings: ImageView
    private lateinit var lensSelector: LinearLayout
    private lateinit var btnLensFixed: TextView
    private lateinit var btnLensBoth: TextView
    private lateinit var btnLensPtz: TextView
    private lateinit var camerasRoot: LinearLayout
    private lateinit var cameraDivider: View
    private lateinit var tabs: TabLayout
    private lateinit var tabContent: FrameLayout
    private lateinit var camFixed: CameraStreamView
    private lateinit var camPtz: CameraStreamView

    // ---- Pantalla completa ----
    private lateinit var fullscreenOverlay: FrameLayout
    private lateinit var tvFsLabel: TextView
    private lateinit var btnFsExit: ImageView
    private lateinit var tvFsZoom: TextView
    private lateinit var btnFsAudio: ImageView
    private lateinit var btnFsSound: ImageView

    // ---- Consola diagnóstica (punto 1) ----
    private lateinit var consoleOverlay: LinearLayout
    private lateinit var consoleText: TextView
    private lateinit var consoleScroll: ScrollView

    private var fullscreenCamera: CameraStreamView? = null
    private var scanJob: Job? = null

    private val tabViews = mutableMapOf<Int, View>()
    private val sdLog = ArrayDeque<String>()
    private val consoleLines = ArrayDeque<String>()

    /** Estado recordatorio de los interruptores de la pestaña Funciones. */
    private var motionOn = false
    private var trackingOn = false
    private var frameOn = false
    private var flipOn = false
    private var sirenOn = false
    private var ledOn = false
    private var cruiseVerticalOn = false
    private var cruiseHorizontalOn = false

    /** Referencias de la pestaña Luz, para reflejar el estado real sin reenviar comandos. */
    private var seekBrightness: SeekBar? = null
    private var tvBrightness: TextView? = null

    /**
     * Sirena y estrobo (punto 2). El estado que se pinta es el **real** que reporta la
     * cámara (`trans_cmd_string.cgi?cmd=2109&command=2&` → `sirenStatus`/`alarmLedStatus`),
     * no una variable local: así el botón no miente si la orden no llegó, si la cambió la
     * app oficial o si el equipo la deshizo por su cuenta.
     */
    private var strobeOn = false
    private var suspendStrobeEcho = false
    private var sirenRow: View? = null
    private var tvSirenState: TextView? = null
    private var tvSirenAction: TextView? = null
    private var ivSirenIcon: ImageView? = null
    private var tvStrobeState: TextView? = null
    private var lightSwStrobe: MaterialSwitch? = null
    private var funcsSwStrobe: MaterialSwitch? = null
    private var funcsSirenTile: View? = null

    /** Raíz de la pestaña Funciones, para pintar sus casillas con el estado real. */
    private var funcsView: View? = null

    /** Bitmaps pendientes de guardar en la galería (esperan permiso en API < 29). */
    private var pendingBitmap: Bitmap? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                when (permissionAction) {
                    ACTION_PHOTO -> pendingBitmap?.let { saveBitmapToGallery(it) }
                    ACTION_CALL -> startCall()
                }
            } else {
                Toast.makeText(this, R.string.mic_permission_needed, Toast.LENGTH_SHORT).show()
            }
            permissionAction = 0
        }
    private var permissionAction = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        config = CamConfig(this)
        // El gestor P2P y VoiceLink leen la config sin dependencias cíclicas.
        CamConfigHolder.config = config
        P2PControlManager.init()

        bindViews()
        setupInsets()
        setupCameras()
        setupTopBar()
        setupTabs()
        setupFullscreenOverlay()
        setupConsole()
        observeP2P()
        applyLens()
        applyOrientation()
        applyGlobalSound(config.soundEnabled)
        maybeAutoConnect()
    }

    /**
     * **Punto 1 — conexión automática del túnel al abrir la app.**
     *
     * Es el comportamiento del visor oficial: no hay que pulsar nada. Se omite en
     * silencio si el usuario la desactivó, si falta la librería nativa o si ya hay
     * una conexión/estado en curso, y avisa en la consola cuando faltan datos. La
     * cascada tarda unos segundos y corre en `Dispatchers.IO` dentro del gestor, así
     * que no bloquea la UI.
     */
    private fun maybeAutoConnect() {
        if (!config.autoConnect) return
        if (!P2PControlManager.nativeAvailable) return
        if (P2PControlManager.state.value != ConnState.DISCONNECTED) return

        val missing = buildList {
            if (config.ip.isBlank()) add("IP")
            if (config.uid.isBlank()) add("UID")
            if (config.vuid.isBlank()) add("VUID")
        }
        if (missing.isNotEmpty()) {
            appendConsole("[P2P] auto-conexión omitida: falta ${missing.joinToString("/")}")
            return
        }
        appendConsole("[P2P] auto-conexión al abrir la app")
        lifecycleScope.launch { P2PControlManager.connect() }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (fullscreenCamera == null) applyOrientation()
    }

    override fun onStart() {
        super.onStart()
        camFixed.start()
        camPtz.start()
    }

    override fun onStop() {
        camFixed.stop()
        camPtz.stop()
        super.onStop()
    }

    override fun onDestroy() {
        scanJob?.cancel()
        // VoiceLink corre en su propio scope: `lifecycleScope` ya está cancelado aquí.
        runCatching { VoiceLink.stopAsync(config) { duck -> onSpeakerDuck(duck) } }
        P2PControlManager.destroy()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Configuración inicial
    // ------------------------------------------------------------------

    private fun bindViews() {
        contentLayout = findViewById(R.id.contentLayout)
        topBar = findViewById(R.id.topBar)
        stateDot = findViewById(R.id.stateDot)
        tvP2pState = findViewById(R.id.tvP2pState)
        btnP2pToggle = findViewById(R.id.btnP2pToggle)
        btnSoundGlobal = findViewById(R.id.btnSoundGlobal)
        btnConsole = findViewById(R.id.btnConsole)
        btnSettings = findViewById(R.id.btnSettings)
        lensSelector = findViewById(R.id.lensSelector)
        btnLensFixed = findViewById(R.id.btnLensFixed)
        btnLensBoth = findViewById(R.id.btnLensBoth)
        btnLensPtz = findViewById(R.id.btnLensPtz)
        camerasRoot = findViewById(R.id.camerasRoot)
        cameraDivider = findViewById(R.id.cameraDivider)
        tabs = findViewById(R.id.tabs)
        tabContent = findViewById(R.id.tabContent)
        camFixed = findViewById(R.id.camFixed)
        camPtz = findViewById(R.id.camPtz)

        fullscreenOverlay = findViewById(R.id.fullscreenOverlay)
        tvFsLabel = findViewById(R.id.tvFsLabel)
        btnFsExit = findViewById(R.id.btnFsExit)
        tvFsZoom = findViewById(R.id.tvFsZoom)
        btnFsAudio = findViewById(R.id.btnFsAudio)
        btnFsSound = findViewById(R.id.btnFsSound)

        consoleOverlay = findViewById(R.id.consoleOverlay)
        consoleText = findViewById(R.id.consoleText)
        consoleScroll = findViewById(R.id.consoleScroll)
    }

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootLayout)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    private fun setupCameras() {
        camFixed.setup(config, CamConfig.LENS_FIXED, getString(R.string.cam_fixed))
        camPtz.setup(config, CamConfig.LENS_PTZ, getString(R.string.cam_ptz))
        camFixed.onFullscreen = CameraStreamView.OnFullscreenListener { toggleFullscreen(it) }
        camPtz.onFullscreen = CameraStreamView.OnFullscreenListener { toggleFullscreen(it) }
    }

    // ------------------------------------------------------------------
    // Punto 1 — barra superior: sonido global, consola, conexión P2P
    // ------------------------------------------------------------------

    private fun setupTopBar() {
        btnP2pToggle.setOnClickListener {
            val st = P2PControlManager.state.value
            if (st == ConnState.DISCONNECTED || st == ConnState.ERROR) {
                lifecycleScope.launch { P2PControlManager.connect() }
            } else {
                lifecycleScope.launch { P2PControlManager.disconnect() }
            }
        }
        btnSettings.setOnClickListener { showSettingsDialog() }
        btnConsole.setOnClickListener { toggleConsole(true) }

        // Punto 6: un único control de sonido para las dos lentes.
        btnSoundGlobal.setOnClickListener { applyGlobalSound(!config.soundEnabled) }

        btnLensFixed.setOnClickListener { config.activeLens = CamConfig.LENS_FIXED; applyLens() }
        btnLensBoth.setOnClickListener { config.activeLens = CamConfig.LENS_BOTH; applyLens() }
        btnLensPtz.setOnClickListener { config.activeLens = CamConfig.LENS_PTZ; applyLens() }
    }

    private fun applyGlobalSound(enabled: Boolean) {
        config.soundEnabled = enabled
        camFixed.applyGlobalSound(enabled)
        camPtz.applyGlobalSound(enabled)
        btnSoundGlobal.setImageResource(if (enabled) R.drawable.ic_volume_2 else R.drawable.ic_volume_x)
        btnSoundGlobal.contentDescription =
            getString(if (enabled) R.string.mute else R.string.unmute)
        btnFsSound.setImageResource(if (enabled) R.drawable.ic_volume_2 else R.drawable.ic_volume_x)
    }

    /** Silencia/restaura los dos streams mientras suena el intercomunicador (punto 4). */
    private fun onSpeakerDuck(duck: Boolean) {
        // `duckForIntercom` toca `ExoPlayer.volume`, que debe hacerse en el hilo
        // principal del reproductor; VoiceLink puede invocarlo desde Dispatchers.IO.
        runOnUiThread {
            camFixed.duckForIntercom(duck)
            camPtz.duckForIntercom(duck)
        }
    }

    // ------------------------------------------------------------------
    // Selector de lente (punto 6)
    // ------------------------------------------------------------------

    private fun applyLens() {
        val lens = config.activeLens
        val showFixed = lens != CamConfig.LENS_PTZ
        val showPtz = lens != CamConfig.LENS_FIXED
        camFixed.visibility = if (showFixed) View.VISIBLE else View.GONE
        camPtz.visibility = if (showPtz) View.VISIBLE else View.GONE
        cameraDivider.visibility = if (showFixed && showPtz) View.VISIBLE else View.GONE

        fun mark(v: TextView, active: Boolean) {
            v.setTextColor(if (active) 0xFF39A0FF.toInt() else 0xFF93A3B4.toInt())
        }
        mark(btnLensFixed, lens == CamConfig.LENS_FIXED)
        mark(btnLensBoth, lens == CamConfig.LENS_BOTH)
        mark(btnLensPtz, lens == CamConfig.LENS_PTZ)
        applyOrientation()
    }

    // ------------------------------------------------------------------
    // Pestañas
    // ------------------------------------------------------------------

    private fun setupTabs() {
        listOf(R.string.tab_ptz, R.string.tab_light, R.string.tab_funcs, R.string.tab_sd)
            .forEach { title -> tabs.addTab(tabs.newTab().setText(title)) }
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = showTab(tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        styleTabs()
        showTab(0)
    }

    /** Estilos de TabLayout aplicados en código (menos frágiles que en XML). */
    private fun styleTabs() {
        tabs.tabMode = TabLayout.MODE_FIXED
        tabs.tabGravity = TabLayout.GRAVITY_FILL
        tabs.setSelectedTabIndicatorColor(0xFF39A0FF.toInt())
        tabs.setSelectedTabIndicatorHeight(2.dp)
        tabs.setTabTextColors(0xFF93A3B4.toInt(), 0xFF39A0FF.toInt())
    }

    private fun showTab(index: Int) {
        tabContent.removeAllViews()
        val view = tabViews.getOrPut(index) {
            val layout = when (index) {
                0 -> R.layout.tab_ptz
                1 -> R.layout.tab_light
                2 -> R.layout.tab_funcs
                else -> R.layout.tab_sd
            }
            val v = layoutInflater.inflate(layout, tabContent, false)
            bindTab(index, v)
            v
        }
        tabContent.addView(view)
    }

    private fun bindTab(index: Int, view: View) {
        when (index) {
            0 -> bindPtzTab(view)
            1 -> bindLightTab(view)
            2 -> bindFuncsTab(view)
            else -> bindSdTab(view)
        }
    }

    /** Ejecuta una acción solo si el túnel P2P está en línea. */
    private fun requireP2P(action: () -> Unit) {
        if (!P2PControlManager.nativeAvailable) {
            Toast.makeText(this, R.string.p2p_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        if (P2PControlManager.state.value != ConnState.ONLINE) {
            Toast.makeText(this, R.string.p2p_connecting, Toast.LENGTH_SHORT).show()
            return
        }
        action()
    }

    // ---- Tab 0: PTZ ----

    private fun bindPtzTab(view: View) {
        // Solo 4 ejes: el firmware de O-KAM Pro no expone diagonales (no hay `command=8`
        // y el panel de la app oficial tiene 4 flechas + centro).
        val dpad = mapOf(
            R.id.btnUp to PtzDirection.UP,
            R.id.btnDown to PtzDirection.DOWN,
            R.id.btnLeft to PtzDirection.LEFT,
            R.id.btnRight to PtzDirection.RIGHT,
        )
        dpad.forEach { (id, direction) ->
            view.findViewById<ImageView>(id).setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        // Feedback visual: el selector `sel_dpad` pinta el fondo en
                        // verde. Hay que marcar `isPressed` a mano porque este
                        // listener consume el toque y `View.onTouchEvent` no corre.
                        v.isPressed = true
                        requireP2P { sendPtz(direction) }
                        true
                    }

                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL,
                    -> {
                        v.isPressed = false
                        P2PControlManager.stopPtz()
                        true
                    }

                    else -> false
                }
            }
        }

        view.findViewById<ImageView>(R.id.btnCenter).setOnClickListener {
            requireP2P { P2PControlManager.centerPtz() }
        }
        // «Corrección PTZ» a la derecha de la rueda compacta (punto 3): mismo código.
        view.findViewById<TextView>(R.id.btnPtzCorrect).setOnClickListener {
            requireP2P { P2PControlManager.centerPtz() }
        }

        val etPreset = view.findViewById<EditText>(R.id.etPresetId)
        fun presetId(): Int = etPreset.text.toString().toIntOrNull() ?: 1

        view.findViewById<TextView>(R.id.btnGotoPreset).setOnClickListener {
            requireP2P { P2PControlManager.gotoPreset(presetId()) }
        }
        view.findViewById<TextView>(R.id.btnSetPreset).setOnClickListener {
            requireP2P { P2PControlManager.setPreset(presetId()) }
        }

        // Crucero nativo del cabezal: pares Foscam 26/27 (vertical) y 28/29 (horizontal).
        // El «crucero de posición» de O-KAM es propiamente el sistema de presets de abajo.
        val tvCruise = view.findViewById<TextView>(R.id.tvCruiseState)
        val btnCruiseV = view.findViewById<TextView>(R.id.btnPtzCruiseV)
        val btnCruiseH = view.findViewById<TextView>(R.id.btnPtzCruiseH)
        fun refreshCruise() {
            btnCruiseV.setText(
                if (cruiseVerticalOn) R.string.ptz_cruise_stop else R.string.ptz_cruise_vertical,
            )
            btnCruiseH.setText(
                if (cruiseHorizontalOn) R.string.ptz_cruise_stop else R.string.ptz_cruise_horizontal,
            )
            tvCruise.setText(
                if (cruiseVerticalOn || cruiseHorizontalOn) R.string.ptz_cruise_on
                else R.string.ptz_cruise_off,
            )
        }
        btnCruiseV.setOnClickListener {
            cruiseVerticalOn = !cruiseVerticalOn
            requireP2P { P2PControlManager.verticalCruise(cruiseVerticalOn) }
            refreshCruise()
        }
        btnCruiseH.setOnClickListener {
            cruiseHorizontalOn = !cruiseHorizontalOn
            requireP2P { P2PControlManager.horizontalCruise(cruiseHorizontalOn) }
            refreshCruise()
        }
        refreshCruise()

        val swX = view.findViewById<MaterialSwitch>(R.id.swInvertX)
        val swY = view.findViewById<MaterialSwitch>(R.id.swInvertY)
        swX.isChecked = config.invertX
        swY.isChecked = config.invertY
        swX.setOnCheckedChangeListener { _, c -> config.invertX = c }
        swY.setOnCheckedChangeListener { _, c -> config.invertY = c }
    }

    private fun sendPtz(direction: PtzDirection) {
        P2PControlManager.movePtz(direction, config.invertX, config.invertY)
    }

    // ---- Tab 1: Luz y visión nocturna (punto 3) ----

    private fun bindLightTab(view: View) {
        val nightGroup = view.findViewById<RadioGroup>(R.id.nightGroup)
        val seek = view.findViewById<SeekBar>(R.id.seekBrightness)
        val tvBright = view.findViewById<TextView>(R.id.tvBrightness)
        val swStrobe = view.findViewById<MaterialSwitch>(R.id.swStrobe)

        nightGroup.check(
            when (config.nightMode) {
                NightMode.IR -> R.id.rbNightIr
                NightMode.FULL_COLOR -> R.id.rbNightColor
                NightMode.SMART -> R.id.rbNightSmart
                else -> R.id.rbNightStarlight
            },
        )
        nightGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.rbNightIr -> NightMode.IR
                R.id.rbNightColor -> NightMode.FULL_COLOR
                R.id.rbNightSmart -> NightMode.SMART
                else -> NightMode.STARLIGHT
            }
            config.nightMode = mode
            requireP2P { P2PControlManager.applyNightMode(mode, config.colorSubMode) }
        }

        fun markSub(selected: Int) {
            listOf(R.id.btnSub1, R.id.btnSub2, R.id.btnSub3).forEachIndexed { i, id ->
                view.findViewById<TextView>(id).setTextColor(
                    if (i + 1 == selected) 0xFF39A0FF.toInt() else 0xFF93A3B4.toInt(),
                )
            }
        }
        markSub(config.colorSubMode)
        mapOf(R.id.btnSub1 to 1, R.id.btnSub2 to 2, R.id.btnSub3 to 3).forEach { (id, sub) ->
            view.findViewById<TextView>(id).setOnClickListener {
                config.colorSubMode = sub
                markSub(sub)
                requireP2P {
                    P2PControlManager.setWhiteLedMode(sub)
                    P2PControlManager.applyNightMode(config.nightMode, sub)
                }
            }
        }

        seekBrightness = seek
        tvBrightness = tvBright

        // El deslizador **es** el interruptor: `whiteled` es el brillo en porcentaje y 0
        // apaga (verificado en equipo levantando y bajando de 0 a 100). No hay switch
        // aparte ni «brillo PWM»: `display_pwm_ctrl.cgi` no existe en este firmware.
        seek.progress = config.whiteLedBrightness.coerceIn(0, 100)
        tvBright.text = seek.progress.toString()

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                tvBright.text = progress.toString()
            }

            override fun onStartTrackingTouch(sb: SeekBar?) = Unit

            override fun onStopTrackingTouch(sb: SeekBar?) {
                setLightLevel(sb?.progress ?: 0)
            }
        })

        view.findViewById<TextView>(R.id.btnPulseLight).setOnClickListener {
            requireP2P { lifecycleScope.launch { P2PControlManager.flashWhiteLed() } }
        }

        // ---- Sirena y estrobo (punto 2) ----
        // El estado que se pinta es el que reporta la cámara, no una variable local:
        // al pulsar se manda la orden y enseguida se **relee** el estado real.
        sirenRow = view.findViewById(R.id.btnSiren)
        tvSirenState = view.findViewById(R.id.tvSirenState)
        tvSirenAction = view.findViewById(R.id.tvSirenAction)
        ivSirenIcon = view.findViewById(R.id.ivSirenIcon)
        tvStrobeState = view.findViewById(R.id.tvStrobeState)
        lightSwStrobe = swStrobe

        sirenRow?.setOnClickListener {
            sirenOn = !sirenOn
            requireP2P { P2PControlManager.setSiren(sirenOn) }
            refreshSirenStrobe()
        }
        swStrobe.setOnCheckedChangeListener { _, checked ->
            if (suspendStrobeEcho) return@setOnCheckedChangeListener
            requireP2P { P2PControlManager.setStrobe(checked) }
            refreshSirenStrobe()
        }
        paintSirenStrobe()

        // Si la sesión ya estaba autorizada antes de crear esta pestaña (lo normal: se
        // conecta en `onCreate` y el usuario abre «Luz» después), las respuestas de
        // estado llegaron cuando estos `View` no existían todavía. Se vuelve a preguntar
        // para que el deslizador y la fila de la sirena enseñen el estado **real**.
        if (P2PControlManager.state.value == ConnState.ONLINE) {
            P2PControlManager.queryWhiteLed()
            P2PControlManager.querySirenStrobe()
        }
    }

    /**
     * Pide el estado **real** de sirena y estrobo
     * (`trans_cmd_string.cgi?cmd=2109&command=2&`). Se llama tras cada orden —con un
     * respiro, para que la cámara la haya aplicado— y al autorizar la sesión.
     */
    private fun refreshSirenStrobe(delayMs: Long = 700L) {
        lifecycleScope.launch {
            delay(delayMs)
            requireP2P { P2PControlManager.querySirenStrobe() }
        }
    }

    /** Estado real leído de la cámara: se guarda y se repinta. */
    private fun syncSirenState(siren: Boolean, strobe: Boolean) {
        sirenOn = siren
        strobeOn = strobe
        appendConsole("[estado] sirena=${if (siren) "ON" else "off"} estrobo=${if (strobe) "ON" else "off"}")
        paintSirenStrobe()
    }

    /**
     * Pinta la fila de la sirena, la casilla del estrobo y la tarjeta «Sirena» de
     * Funciones con [sirenOn] / [strobeOn].
     */
    private fun paintSirenStrobe() {
        // Apagada: rojo translúcido, «lista para pulsar». Sonando: rojo sólido.
        sirenRow?.setBackgroundResource(if (sirenOn) R.drawable.bg_siren_on else R.drawable.bg_siren)
        tvSirenAction?.setText(if (sirenOn) R.string.siren_action_on else R.string.siren_action)
        ivSirenIcon?.imageTintList = android.content.res.ColorStateList.valueOf(
            if (sirenOn) 0xFFFFFFFF.toInt() else 0xFFFFA8A8.toInt(),
        )
        tvSirenState?.apply {
            setText(if (sirenOn) R.string.siren_state_on else R.string.siren_state_off)
            setTextColor(if (sirenOn) 0xFFFFFFFF.toInt() else 0xFF93A3B4.toInt())
        }
        tvStrobeState?.apply {
            setText(if (strobeOn) R.string.strobe_state_on else R.string.strobe_state_off)
            setTextColor(if (strobeOn) 0xFFF2A93B.toInt() else 0xFF93A3B4.toInt())
        }
        // Cambiar `isChecked` por código dispara el listener: se silencia para no
        // reenviar la orden que acabamos de leer de la cámara.
        suspendStrobeEcho = true
        lightSwStrobe?.isChecked = strobeOn
        funcsSwStrobe?.isChecked = strobeOn
        suspendStrobeEcho = false
        funcsSirenTile?.let { highlight(it, sirenOn) }
    }

    // ---- Tab 2: Funciones comunes (punto 6) ----

    private fun bindFuncsTab(view: View) {
        funcsView = view
        view.findViewById<LinearLayout>(R.id.funcPtz).setOnClickListener {
            tabs.getTabAt(0)?.select()
        }
        view.findViewById<LinearLayout>(R.id.funcConsole).setOnClickListener { toggleConsole(true) }

        view.findViewById<LinearLayout>(R.id.funcSound).setOnClickListener {
            applyGlobalSound(!config.soundEnabled)
        }

        view.findViewById<LinearLayout>(R.id.funcCall).setOnClickListener { showCallDialog() }

        view.findViewById<LinearLayout>(R.id.funcPhoto).setOnClickListener { takePhoto() }

        // --- Detección de movimiento ---
        // Su estado **sí** se puede leer: `get_status.cgi?` publica `alarm_status`, y está
        // medido en el equipo que sigue a `motion_armed` (0↔0, 1↔1). Sin esa lectura la
        // casilla arrancaba en `false` mientras la cámara podía tenerlo en `true`, y
        // «Aplicar detección» lo apagaba sin que se viera.
        view.findViewById<LinearLayout>(R.id.funcMotion).setOnClickListener {
            motionOn = !motionOn
            highlight(view.findViewById(R.id.funcMotion), motionOn)
            requireP2P { P2PControlManager.setMotionArmed(motionOn) }
            refreshFuncsStateLater()
        }
        highlight(view.findViewById(R.id.funcMotion), motionOn)

        // --- Visión nocturna: ciclo IR → Color → Inteligente → Starlight ---
        view.findViewById<LinearLayout>(R.id.funcNight).setOnClickListener {
            val next = when (config.nightMode) {
                NightMode.IR -> NightMode.FULL_COLOR
                NightMode.FULL_COLOR -> NightMode.SMART
                NightMode.SMART -> NightMode.STARLIGHT
                else -> NightMode.IR
            }
            config.nightMode = next
            requireP2P { P2PControlManager.applyNightMode(next, config.colorSubMode) }
            Toast.makeText(
                this,
                when (next) {
                    NightMode.IR -> R.string.night_ir
                    NightMode.FULL_COLOR -> R.string.night_color
                    NightMode.SMART -> R.string.night_smart
                    else -> R.string.night_starlight
                },
                Toast.LENGTH_SHORT,
            ).show()
        }
        highlight(view.findViewById(R.id.funcNight), config.nightMode != NightMode.IR)

        // --- Luz blanca ---
        view.findViewById<LinearLayout>(R.id.funcLight).setOnClickListener {
            applyWhiteLed(!ledOn)
        }
        ledOn = config.whiteLedOn
        highlight(view.findViewById(R.id.funcLight), ledOn)

        // --- Sirena (se pinta con el estado real que reporta la cámara) ---
        funcsSirenTile = view.findViewById(R.id.funcSiren)
        funcsSirenTile?.setOnClickListener {
            sirenOn = !sirenOn
            paintSirenStrobe()
            requireP2P { P2PControlManager.setSiren(sirenOn) }
            refreshSirenStrobe()
        }
        paintSirenStrobe()

        // --- Seguimiento de personas ---
        view.findViewById<LinearLayout>(R.id.funcTracking).setOnClickListener {
            trackingOn = !trackingOn
            highlight(view.findViewById(R.id.funcTracking), trackingOn)
            requireP2P { P2PControlManager.setTracking(trackingOn) }
            refreshFuncsStateLater()
        }
        highlight(view.findViewById(R.id.funcTracking), trackingOn)

        // --- Cuadro de detección ---
        view.findViewById<LinearLayout>(R.id.funcFrame).setOnClickListener {
            frameOn = !frameOn
            highlight(view.findViewById(R.id.funcFrame), frameOn)
            requireP2P { P2PControlManager.setDetectionFrame(frameOn) }
            refreshFuncsStateLater()
        }
        highlight(view.findViewById(R.id.funcFrame), frameOn)

        // --- Voltear imagen ---
        view.findViewById<LinearLayout>(R.id.funcFlip).setOnClickListener {
            flipOn = !flipOn
            highlight(view.findViewById(R.id.funcFlip), flipOn)
            requireP2P { P2PControlManager.setFlip(flipOn) }
            refreshFuncsStateLater()
        }
        highlight(view.findViewById(R.id.funcFlip), flipOn)

        // --- Detección IA ---
        val swHuman = view.findViewById<MaterialSwitch>(R.id.swHuman)
        val swStrobe = view.findViewById<MaterialSwitch>(R.id.swStrobe)
        // El estrobo se aplica al tocarlo (igual que en la pestaña Luz), no sólo al
        // pulsar «Aplicar detección»: así el estado que se ve es el que tiene la cámara.
        funcsSwStrobe = swStrobe
        swStrobe.setOnCheckedChangeListener { _, checked ->
            if (suspendStrobeEcho) return@setOnCheckedChangeListener
            requireP2P { P2PControlManager.setStrobe(checked) }
            refreshSirenStrobe()
        }
        val sensGroup = view.findViewById<RadioGroup>(R.id.sensitivityGroup)
        view.findViewById<TextView>(R.id.btnApplyAi).setOnClickListener {
            val sensitivity = sensitivityOf(sensGroup.checkedRadioButtonId)
            requireP2P {
                P2PControlManager.setHumanDetection(swHuman.isChecked)
                P2PControlManager.setMotionArmed(motionOn)
                P2PControlManager.setTracking(trackingOn)
                P2PControlManager.setDetectionFrame(frameOn)
                P2PControlManager.setMotionSensitivity(sensitivity)
                P2PControlManager.setStrobe(swStrobe.isChecked)
            }
            Toast.makeText(this, R.string.save, Toast.LENGTH_SHORT).show()
            refreshFuncsState()
        }

        // Igual que en la pestaña Luz: si la sesión ya estaba autorizada cuando se creó
        // esta pestaña, las lecturas de estado llegaron sin estos `View`. Se repiten.
        refreshFuncsState()
    }

    /**
     * Radio «Sensibilidad» → valor que entiende el firmware. La escala es **inversa** a
     * la etiqueta (§5.12): `1` = alta, `2` = media, `3` = baja.
     */
    private fun sensitivityOf(checkedId: Int): Int = when (checkedId) {
        R.id.rbSensHigh -> 1
        R.id.rbSensLow -> 3
        else -> 2
    }

    /**
     * Pregunta el estado **real** de las casillas de Funciones (seguimiento, cuadro de
     * detección, sensibilidad y volteo). Son tres respuestas pequeñas y se piden al
     * autorizar la sesión y al abrir la pestaña, igual que el brillo de la luz.
     */
    private fun refreshFuncsState() {
        if (P2PControlManager.state.value != ConnState.ONLINE) return
        P2PControlManager.queryTracking()
        P2PControlManager.queryDetection()
        P2PControlManager.requestCameraParams()
        P2PControlManager.requestStatus()   // trae `alarm_status` (detección de movimiento)
    }

    /** Relee el estado de las casillas tras enviar una orden (con un respiro). */
    private fun refreshFuncsStateLater(delayMs: Long = 700L) {
        lifecycleScope.launch {
            delay(delayMs)
            refreshFuncsState()
        }
    }

    private fun syncTracking(on: Boolean) {
        trackingOn = on
        funcsView?.let { highlight(it.findViewById(R.id.funcTracking), on) }
    }

    private fun syncDetectionFrame(on: Boolean) {
        frameOn = on
        funcsView?.let { highlight(it.findViewById(R.id.funcFrame), on) }
    }

    /**
     * Detección de movimiento. La cámara **no** expone `motion_armed` en ningún CGI
     * (`get_alarm.cgi?` no existe y `set_alarm.cgi?` solo escribe), pero su efecto sí:
     * `get_status.cgi?` → `alarm_status`, medido en el equipo con los dos valores
     * (`motion_armed=0` → `alarm_status=0`; `=1` → `=1`). Se usa ese campo.
     */
    private fun syncMotionArmed(on: Boolean) {
        motionOn = on
        funcsView?.let { highlight(it.findViewById(R.id.funcMotion), on) }
    }

    private fun syncFlip(on: Boolean) {
        flipOn = on
        funcsView?.let { highlight(it.findViewById(R.id.funcFlip), on) }
    }

    /** `sensitive` de la cámara (1 = alta … 3 = baja) → radio. */
    private fun syncSensitivity(value: Int) {
        val id = when (value) {
            1 -> R.id.rbSensHigh
            3 -> R.id.rbSensLow
            else -> R.id.rbSensMid
        }
        funcsView?.findViewById<RadioGroup>(R.id.sensitivityGroup)?.check(id)
    }

    private fun highlight(tile: View, on: Boolean) {
        tile.alpha = if (on) 1f else 0.62f
        tile.setBackgroundResource(if (on) R.drawable.bg_panel else R.drawable.bg_button)
    }

    // ---- Tab 3: MicroSD (punto 5) ----

    private fun bindSdTab(view: View) {
        val tvTotal = view.findViewById<TextView>(R.id.tvSdTotal)
        val tvFree = view.findViewById<TextView>(R.id.tvSdFree)
        val tvState = view.findViewById<TextView>(R.id.tvSdState)
        val tvPercent = view.findViewById<TextView>(R.id.tvSdPercent)
        val progress = view.findViewById<ProgressBar>(R.id.sdProgressBar)
        val tvEmpty = view.findViewById<TextView>(R.id.tvSdEmpty)

        view.findViewById<TextView>(R.id.btnRefreshSd).setOnClickListener {
            requireP2P {
                P2PControlManager.requestSdStatus()
                P2PControlManager.requestRecordState()
            }
        }

        val swSchedule = view.findViewById<MaterialSwitch>(R.id.swRecordSchedule)
        swSchedule.isChecked = config.recordScheduleEnabled
        swSchedule.setOnCheckedChangeListener { _, checked ->
            config.recordScheduleEnabled = checked
            requireP2P { P2PControlManager.setRecordSchedule(checked) }
        }

        view.findViewById<TextView>(R.id.btnSdMark).setOnClickListener {
            requireP2P { P2PControlManager.markRecord() }
        }

        view.findViewById<TextView>(R.id.btnSdFormat).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.sd_format_confirm_title)
                .setMessage(R.string.sd_format_confirm_message)
                .setPositiveButton(R.string.sd_format) { _, _ ->
                    requireP2P { P2PControlManager.formatSd() }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        view.findViewById<TextView>(R.id.btnLoadDates).setOnClickListener {
            val refs = sdRefs ?: return@setOnClickListener
            refs.datesList.removeAllViews()
            refs.filesList.removeAllViews()
            showSdDatesPane()
            tvEmpty.visibility = View.VISIBLE
            requireP2P { P2PControlManager.requestRecordDates() }
        }

        // Atrás jerárquico: clips de una hora → horas del día → fechas.
        view.findViewById<TextView>(R.id.btnSdBack).setOnClickListener {
            if (sdHour != null) {
                sdHour = null
                renderSdPane()
            } else {
                showSdDatesPane()
            }
        }

        view.findViewById<TextView>(R.id.btnRefreshStatus).setOnClickListener {
            requireP2P { P2PControlManager.requestStatus() }
        }

        view.findViewById<LinearLayout>(R.id.btnReboot).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.reboot_confirm_title)
                .setMessage(R.string.reboot_confirm_message)
                .setPositiveButton(R.string.reboot) { _, _ ->
                    requireP2P { P2PControlManager.reboot() }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        // Log histórico
        view.findViewById<TextView>(R.id.tvStatusLog).text = sdLog.joinToString("\n")

        // Referencias para el enrutado de respuestas
        sdRefs = SdRefs(
            tvTotal, tvFree, tvState, tvPercent, progress, tvEmpty,
            view.findViewById(R.id.datesList),
            view.findViewById(R.id.filesList),
            view.findViewById(R.id.tvSdDate),
            view.findViewById(R.id.btnSdBack),
        )
    }

    private data class SdRefs(
        val tvTotal: TextView,
        val tvFree: TextView,
        val tvState: TextView,
        val tvPercent: TextView,
        val progress: ProgressBar,
        val tvEmpty: TextView,
        val datesList: LinearLayout,
        val filesList: LinearLayout,
        val tvDate: TextView,
        val btnBack: TextView,
    )

    /** Fecha elegida en el navegador de la MicroSD (formato `YYYYMMDD`). */
    private var selectedSdDate: String? = null

    /** Vuelve a la lista de fechas (deja los clips ocultos). */
    private fun showSdDatesPane() {
        val refs = sdRefs ?: return
        sdUiHandler.removeCallbacks(sdRender)
        refs.datesList.visibility = View.VISIBLE
        refs.filesList.visibility = View.GONE
        refs.tvDate.text = getString(R.string.sd_files)
        refs.btnBack.visibility = View.GONE
        refs.btnBack.text = getString(R.string.sd_back_dates)
        selectedSdDate = null
        sdHour = null
    }

    /** Abre el panel de clips de una fecha mientras llega la respuesta de la cámara. */
    private fun showSdClipsPane(date: String) {
        val refs = sdRefs ?: return
        sdUiHandler.removeCallbacks(sdRender)
        refs.datesList.visibility = View.GONE
        refs.filesList.visibility = View.VISIBLE
        refs.btnBack.visibility = View.VISIBLE
        refs.filesList.removeAllViews()
        sdClips.clear()
        sdHour = null
        refs.tvDate.text = getString(R.string.sd_clips_of, prettyDate(date))
        refs.tvEmpty.visibility = View.GONE
    }

    /** Convierte `20261017` en `17/10/2026` (y marca «hoy»). */
    private fun prettyDate(date: String): String {
        if (date.length != 8) return date
        val pretty = "${date.substring(6, 8)}/${date.substring(4, 6)}/${date.substring(0, 4)}"
        val today = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
        return if (date == today) "$pretty · hoy" else pretty
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /**
     * Fila de una fecha: título + flecha. **Vertical**: es la lista que antes era
     * una fila horizontal que se salía de la pantalla.
     */
    private fun makeDateRow(date: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            tag = "d_$date"
            isClickable = true
            isFocusable = true
            setBackgroundResource(R.drawable.bg_panel)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(4) }
        }
        row.addView(
            TextView(this).apply {
                text = prettyDate(date)
                setTextColor(0xFFE8EEF4.toInt())
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        row.addView(
            TextView(this).apply {
                text = "›"
                setTextColor(0xFF8FA3B0.toInt())
                textSize = 18f
            },
        )
        row.setOnClickListener {
            selectedSdDate = date
            showSdClipsPane(date)
            // PageSize alto: sin él la cámara sólo manda la última página (~96 clips).
            requireP2P { P2PControlManager.requestRecordFiles(date) }
        }
        return row
    }

    /**
     * Fila de un clip: `HH:MM:SS · serie · tamaño`.
     *
     * El nombre real es `20261017205401_100.mp4`:
     * `YYYYMMDD` + `HHMMSS` + `_` + **serie** (`100` / `011`, las dos series que graba
     * este equipo; las de `_100` pesan ~4× más, o sea que son el flujo principal).
     *
     * **Sin ▶** (punto 3, 2026-10-17): el botón pedía el clip al equipo con los dos
     * comandos del app oficial, pero el flujo **no** sale por el túnel CGI (§5.13) y la
     * cámara se quedaba sorda por el camino (§5.15). Era un botón que no reproducía nada
     * y encima rompía la sesión: se retiró y la fila es solo informativa. Verlo dentro
     * de la app necesita el reproductor nativo (`libOKSMARTPLAY.so`, §10.6).
     */
    private fun makeClipRow(name: String, size: Long): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_button)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(3) }
        }
        row.addView(
            TextView(this).apply {
                text = clipTime(name)
                setTextColor(0xFFE8EEF4.toInt())
                textSize = 14f
                typeface = android.graphics.Typeface.MONOSPACE
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        row.addView(
            TextView(this).apply {
                text = clipSeries(name)
                setTextColor(0xFF8FA3B0.toInt())
                textSize = 11f
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(0, 0, dp(10), 0)
            },
        )
        row.addView(
            TextView(this).apply {
                text = humanSize(size)
                setTextColor(0xFFB9C7D2.toInt())
                textSize = 11f
                typeface = android.graphics.Typeface.MONOSPACE
            },
        )
        return row
    }

    /** `20261017205401_100.mp4` → `20:54:01`. */
    private fun clipTime(name: String): String {
        val stamp = name.take(14)
        if (stamp.length < 14 || !stamp.all(Char::isDigit)) return name
        return "${stamp.substring(8, 10)}:${stamp.substring(10, 12)}:${stamp.substring(12, 14)}"
    }

    /** `20261017205401_100.mp4` → `100`. */
    private fun clipSeries(name: String): String =
        name.substringBeforeLast('.').substringAfterLast('_', "-")

    private fun humanSize(bytes: Long): String = when {
        bytes <= 0L -> "--"
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        else -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    }

    /**
     * Nota de la pestaña MicroSD: **ya no hay ▶** (punto 3, 2026-10-17).
     *
     * El botón pedía el clip con los dos comandos del app oficial
     * (`record_fastplay.cgi?ctrl=1&playlist=` + `livestream.cgi?streamid=4&filename=`),
     * que la cámara acepta (`result= 0;`, 12 B) pero **sin carga útil**: el flujo del
     * fichero no sale por el túnel CGI (§5.13), y además dejaba la sesión **sorda**
     * (§5.15). Ver un clip exige el reproductor nativo (`libOKSMARTPLAY.so`, §10.6).
     */
    private var sdRefs: SdRefs? = null

    // ------------------------------------------------------------------
    // Punto 1 — consola diagnóstica
    // ------------------------------------------------------------------

    private fun setupConsole() {
        findViewById<TextView>(R.id.btnConsoleClose).setOnClickListener { toggleConsole(false) }
        findViewById<TextView>(R.id.btnConsoleClear).setOnClickListener {
            consoleLines.clear()
            consoleText.text = getString(R.string.console_empty)
        }
        renderConsole()
    }

    private fun toggleConsole(show: Boolean) {
        consoleOverlay.visibility = if (show) View.VISIBLE else View.GONE
        if (show) renderConsole()
    }

    private fun appendConsole(line: String) {
        consoleLines.addLast(line)
        while (consoleLines.size > MAX_CONSOLE_LINES) consoleLines.removeFirst()
        if (consoleOverlay.visibility == View.VISIBLE) renderConsole()
    }

    private fun renderConsole() {
        val text = if (consoleLines.isEmpty()) {
            getString(R.string.console_empty)
        } else {
            consoleLines.joinToString("\n")
        }
        consoleText.text = text
        consoleScroll.post { consoleScroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ------------------------------------------------------------------
    // P2P: estado, consola, respuestas CGI
    // ------------------------------------------------------------------

    private fun observeP2P() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    P2PControlManager.state.collect { state -> renderP2pState(state) }
                }
                launch {
                    // Punto 6: el supervisor reintenta solo; la barra lo dice.
                    P2PControlManager.retrying.collect { renderP2pState(P2PControlManager.state.value) }
                }
                launch {
                    P2PControlManager.events.collect { ev -> onP2pEvent(ev) }
                }
                launch {
                    P2PControlManager.responses.collect { resp -> onCgiResponse(resp) }
                }
                launch {
                    P2PControlManager.messages.collect { msg ->
                        appendLog(msg)
                        if (consoleOverlay.visibility == View.VISIBLE) appendConsole("» $msg")
                    }
                }
                launch {
                    VoiceLink.state.collect { st ->
                        if (st == VoiceLink.State.IDLE) refreshCallDialog()
                    }
                }
                launch {
                    VoiceLink.error.collect { err ->
                        if (err != null) Toast.makeText(this@MainActivity, err, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun onP2pEvent(ev: P2pEvent) {
        appendConsole("[${ev.clock}] ${ev.origin}: ${ev.detail}")
        if (ev.detail.contains("AUTORIZADO")) {
            // La sesión ya acepta órdenes: pide el brillo real para pintar el deslizador
            // (`get_whiteled_value.cgi?` es la única respuesta que lo devuelve) y el
            // estado real de sirena y estrobo (`cmd=2109&command=2&`).
            P2PControlManager.queryWhiteLed()
            refreshSirenStrobe(delayMs = 0L)
            refreshFuncsState()
        }
    }

    private fun renderP2pState(state: ConnState) {
        // Punto 6: en ERROR con un reintento en vuelo no se enseña un «Error» seco,
        // sino que la app está recuperándose sola.
        val retrying = P2PControlManager.retrying.value
        val (label, color) = when {
            state == ConnState.ERROR && retrying ->
                getString(R.string.state_reconnecting) to 0xFFF2A93B.toInt()
            state == ConnState.DISCONNECTED ->
                getString(R.string.p2p_disconnected) to 0xFF64748B.toInt()
            state == ConnState.CONNECTING ->
                getString(R.string.p2p_connecting) to 0xFFF2A93B.toInt()
            state == ConnState.ONLINE ->
                getString(R.string.p2p_online) to 0xFF37C878.toInt()
            else -> getString(R.string.p2p_error) to 0xFFFF5A5A.toInt()
        }
        val suffix = if (!P2PControlManager.nativeAvailable) {
            " — ${getString(R.string.p2p_unavailable)}"
        } else {
            ""
        }
        tvP2pState.text = label + suffix
        // Tintea el punto (sigue siendo la forma bg_dpad, que no se deforma a cuadrado).
        stateDot.background.setTint(color)
        btnP2pToggle.text = when (state) {
            ConnState.DISCONNECTED, ConnState.ERROR -> getString(R.string.p2p_connect)
            else -> getString(R.string.p2p_disconnect)
        }
        appendConsole("[P2P] estado -> $state")
    }

    /**
     * Refleja en la UI y en las preferencias un nivel de brillo (0 = apagado) **sin**
     * mandar nada a la cámara. Lo comparten las tres entradas de la luz: el deslizador,
     * la casilla de Funciones y la lectura de estado que devuelve la propia cámara.
     */
    private fun showLightLevel(level: Int) {
        val v = level.coerceIn(0, 100)
        val on = v > 0
        if (on) config.whiteLedBrightness = v
        ledOn = on
        config.whiteLedOn = on
        seekBrightness?.progress = v
        tvBrightness?.text = v.toString()
        tabViews[2]?.let { ft -> highlight(ft.findViewById(R.id.funcLight), on) }
    }

    /**
     * Deslizador de la pestaña **Luz**: fija el brillo (0 apaga, 100 máxima) y lo manda
     * a la cámara. Es el único control de luz, porque `whiteled` ya hace de interruptor.
     */
    private fun setLightLevel(level: Int) {
        val v = level.coerceIn(0, 100)
        showLightLevel(v)
        requireP2P { P2PControlManager.setWhiteLedBrightness(v) }
    }

    /**
     * Casilla **Luz blanca** de Funciones: un solo toque, sin deslizar. Apaga (0 %) o
     * enciende al último brillo que tuviera el deslizador.
     */
    private fun applyWhiteLed(on: Boolean) {
        val level = config.whiteLedBrightness.coerceIn(1, 100)
        showLightLevel(if (on) level else 0)
        requireP2P { P2PControlManager.setWhiteLed(on, level) }
    }

    /**
     * Refleja el estado **real** que reporta la cámara (`var whiteled=<0..100>;`) sin
     * reenviar comandos. El deslizador es la única fuente de verdad de la luz.
     */
    private fun syncLightState(brightness: Int) {
        showLightLevel(brightness)
    }

    private fun onCgiResponse(resp: CgiResponse) {
        val text = resp.raw
        appendLog(text.take(400).replace('\n', ' '))
        appendConsole("[RX  ] ${text.take(600).replace('\n', ' ')}")

        // Estado real de la luz: `get_whiteled_value.cgi?` responde `var whiteled=<0..100>;`
        // y es la **única** respuesta que trae ese campo. Los ecos de `cmd=2109` traen
        // `light`/`lightStatus`, que son la **petición**, no el estado (§5.10).
        val briM = Regex("\\bwhiteled\\s*=\\s*(\\d+)").find(text)
        if (briM != null) syncLightState(briM.groupValues[1].toInt())

        // Estado real de sirena y estrobo: `trans_cmd_string.cgi?cmd=2109&command=2&`
        // (lectura, no eco de una petición) trae **los dos** campos juntos, así que
        // exigirlos juntos evita confundir esta respuesta con el `sirenStatus` suelto
        // de `get_status.cgi?`.
        val sirenM = Regex("\\bsirenStatus\\s*=\\s*(\\d)").find(text)
        val strobeM = Regex("\\balarmLedStatus\\s*=\\s*(\\d)").find(text)
        if (sirenM != null && strobeM != null) {
            syncSirenState(sirenM.groupValues[1] == "1", strobeM.groupValues[1] == "1")
        }

        // --- Estado real de las casillas de Funciones (auditoría del punto 3) ---
        // Se discrimina por `cmd=` porque `enable=` y `flip=` son nombres que aparecen
        // en otras respuestas (`get_status.cgi` trae `dnsenable=0`, `upnp_status`…).
        if (text.contains("var cmd=2127")) {
            Regex("\\benable\\s*=\\s*(\\d)").find(text)
                ?.let { syncTracking(it.groupValues[1] == "1") }
        }
        if (text.contains("var cmd=2126")) {
            Regex("\\bbHumanoidFrame\\s*=\\s*(\\d)").find(text)
                ?.let { syncDetectionFrame(it.groupValues[1] == "1") }
            Regex("\\bsensitive\\s*=\\s*([123])\\b").find(text)
                ?.let { syncSensitivity(it.groupValues[1].toInt()) }
        }
        // `get_camera_params.cgi?` es el único que publica `flip` (y `full_color_mode`).
        if (text.contains("cameratype=")) {
            Regex("\\bflip\\s*=\\s*(\\d)").find(text)
                ?.let { syncFlip(it.groupValues[1] != "0") }
        }
        // `get_status.cgi?` publica `alarm_status`, que **sigue** a `motion_armed`
        // (medido con los dos valores): es el lector de la detección de movimiento.
        Regex("\\balarm_status\\s*=\\s*(\\d)").find(text)
            ?.let { syncMotionArmed(it.groupValues[1] == "1") }

        val sd = sdRefs
        if (sd != null) parseSdStatus(text, sd)

        // ---- MicroSD: fechas y clips de la tarjeta ----
        routeSdResponses(text)
    }

    /**
     * Enruta las respuestas del navegador de la MicroSD.
     *
     * Formatos **reales**, medidos en el equipo (2026-10-17) y no supuestos:
     *
     * ```
     * get_record_file.cgi?GetType=date&
     *     record_date"20261017";  record_datenum=1;
     *
     * get_record_file.cgi?GetType=file&dirname=20261017&PageSize=1000&
     *     record_name[0]="20261017000040_100.mp4";  record_size[0]=3322085;  …
     * ```
     *
     * El `[N]` del medio es lo que tumbaba al parser anterior: buscaba
     * `"name":"…"`, una forma que **no existe** en la respuesta. Y como buscaba
     * `\b20\d{6}\b` en **cualquier** respuesta, los nombres de los ficheros también
     * metían fechas en la fila de fechas.
     */
    private fun routeSdResponses(text: String) {
        val refs = sdRefs ?: return
        if (!text.contains("record_name") && !text.contains("record_date")) return

        // Fechas: `record_date"20261017"` / `record_date[0]="20261017"`.
        Regex("record_date(?:\\[\\d+\\])?\\s*[=:]?\\s*\"?(20\\d{6})").findAll(text)
            .map { it.groupValues[1] }
            .distinct()
            .forEach { date ->
                if (refs.datesList.findViewWithTag<View>("d_$date") == null) {
                    refs.datesList.addView(makeDateRow(date))
                }
            }

        // Clips: `record_name[N]="…"` + `record_size[N]=<bytes>`.
        val sizes = Regex("record_size\\[(\\d+)]\\s*=\\s*(\\d+)")
            .findAll(text)
            .associate { it.groupValues[1].toInt() to (it.groupValues[2].toLongOrNull() ?: 0L) }
        val clips = Regex("record_name\\[(\\d+)]\\s*=\\s*\"([^\"]+)\"")
            .findAll(text)
            .map { it.groupValues[2] to (sizes[it.groupValues[1].toInt()] ?: 0L) }
            .toList()
        if (clips.isEmpty()) return

        accumulateSdClips(selectedSdDate ?: clips.first().first.take(8), clips)
    }

    /**
     * Clips acumulados del día abierto (`nombre` → `bytes`).
     *
     * Hace falta acumular porque **el túnel parte la respuesta en varios trozos**:
     * medido en el equipo, la lista de un día (28 KB, 397 clips) llega en dos
     * devoluciones de `commandListener` (24 583 B + 3 552 B). Reemplazar la lista en
     * cada trozo dejaba sólo los 49 clips del último trozo.
     */
    private val sdClips = LinkedHashMap<String, Long>()

    /**
     * Por encima de este número de clips el día se abre **agrupado por horas**.
     *
     * No es sólo comodidad: medido en el equipo, pintar de golpe las 397 filas de un
     * día subía el heap nativo de 19 MB a 45 MB (total 102 → 126 MB) y el objetivo de
     * la app es < 70 MB. Con las horas nunca hay más de ~24 filas pintadas (≤ 40 en el
     * caso directo), así que el coste baja a ~2 MB. Además el panel de pestañas mide
     * ~140 px de alto: 397 filas ahí dentro son inusables.
     */
    private val SD_DIRECT_LIMIT = 40

    /** Hora elegida dentro del día (`null` = lista directa de clips). */
    private var sdHour: Int? = null

    /** Reagrupa la vista una sola vez por respuesta, cuando ya llegaron todos los trozos. */
    private val sdRender = Runnable { renderSdPane() }
    private val sdUiHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Une un trozo de la respuesta a la lista del día, sin duplicar. */
    private fun accumulateSdClips(date: String, clips: List<Pair<String, Long>>) {
        if (selectedSdDate != date) {          // llegó otro día: empezar de cero
            sdClips.clear()
            sdHour = null
        }
        clips.forEach { (name, size) -> sdClips[name] = size }
        // El túnel trocea la respuesta (24 583 B + 3 552 B), así que se espera un
        // momento a que acabe antes de pintar: si no, se repintaría con cada trozo.
        sdUiHandler.removeCallbacks(sdRender)
        sdUiHandler.postDelayed(sdRender, 400)
    }

    /** Pinta el panel de clips, agrupado por horas cuando el día trae muchos. */
    private fun renderSdPane() {
        val refs = sdRefs ?: return
        val date = selectedSdDate ?: return
        refs.filesList.removeAllViews()
        refs.filesList.visibility = View.VISIBLE
        refs.datesList.visibility = View.GONE
        refs.btnBack.visibility = View.VISIBLE
        if (sdClips.isEmpty()) {
            refs.tvDate.text = getString(R.string.sd_clips_of, prettyDate(date))
            refs.tvEmpty.visibility = View.VISIBLE
            return
        }
        refs.tvEmpty.visibility = View.GONE
        val mb = sdClips.values.sum() / 1024.0 / 1024.0
        val head = getString(R.string.sd_clips_of, prettyDate(date)) +
            " · ${sdClips.size} · " + String.format(Locale.US, "%.1f MB", mb)

        when {
            // Clips de una hora concreta.
            sdHour != null -> {
                val hour = sdHour ?: 0
                sdClips.entries.filter { clipHour(it.key) == hour }
                    .sortedByDescending { it.key }
                    .forEach { (n, s) -> refs.filesList.addView(makeClipRow(n, s)) }
                refs.tvDate.text = "$head · " +
                    String.format(Locale.US, "%02d:00–%02d:59", hour, hour)
                refs.btnBack.text = getString(R.string.sd_back_hours)
            }
            // Pocos clips: lista directa, lo más reciente arriba.
            sdClips.size <= SD_DIRECT_LIMIT -> {
                sdClips.entries.sortedByDescending { it.key }
                    .forEach { (n, s) -> refs.filesList.addView(makeClipRow(n, s)) }
                refs.tvDate.text = head
                refs.btnBack.text = getString(R.string.sd_back_dates)
            }
            // Día completo: una fila por hora con grabaciones.
            else -> {
                sdClips.entries.groupBy { clipHour(it.key) }
                    .entries.sortedByDescending { it.key }
                    .forEach { (h, list) -> refs.filesList.addView(makeHourRow(h, list)) }
                refs.tvDate.text = head
                refs.btnBack.text = getString(R.string.sd_back_dates)
            }
        }
    }

    /** Hora de un clip: `20261017205401_100.mp4` → `20`. */
    private fun clipHour(name: String): Int = name.drop(8).take(2).toIntOrNull() ?: -1

    /**
     * Fila de una hora del día: `20:00 – 20:59 · 18 clips · 180,2 MB`.
     * Es el nivel intermedio para los días completos (397 clips/día en este equipo).
     */
    private fun makeHourRow(hour: Int, clips: List<Map.Entry<String, Long>>): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setBackgroundResource(R.drawable.bg_button)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(3) }
        }
        row.addView(
            TextView(this).apply {
                text = String.format(Locale.US, "%02d:00–%02d:59", hour, hour)
                setTextColor(0xFFE8EEF4.toInt())
                textSize = 14f
                typeface = android.graphics.Typeface.MONOSPACE
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        row.addView(
            TextView(this).apply {
                val mb = clips.sumOf { it.value } / 1024.0 / 1024.0
                text = "${clips.size} · " + String.format(Locale.US, "%.1f MB", mb)
                setTextColor(0xFFB9C7D2.toInt())
                textSize = 11f
                setPadding(0, 0, dp(8), 0)
            },
        )
        row.addView(
            TextView(this).apply {
                text = "›"
                setTextColor(0xFF8FA3B0.toInt())
                textSize = 18f
            },
        )
        row.setOnClickListener {
            sdHour = hour
            renderSdPane()
        }
        return row
    }

    private fun ViewGroup.children(): Sequence<View> = sequence {
        for (i in 0 until childCount) yield(getChildAt(i))
    }

    /**
     * Extrae `sdtotal` / `sdfree` / `sdstatus` / `record_sd_status` de la
     * respuesta de `get_status.cgi` o `get_record.cgi`.
     *
     * Las cámaras VStarcam devuelven los tamaños **en KB** cuando el valor es
     * grande (una tarjeta de 64 GB aparece como ~63 896 576). Si el valor es
     * pequeño (< 1 000 000) se asume MB (tarjeta de pocos GB reportada en MB).
     * El crudo siempre queda en el log de estado por si la firma del firmware
     * fuera distinta.
     */
    private fun parseSdStatus(text: String, refs: SdRefs) {
        fun key(name: String): Long? =
            Regex("\"?$name\"?\\s*[:=]\\s*\"?(-?\\d+)\"?").find(text)?.groupValues?.get(1)?.toLongOrNull()

        val totalRaw = key("sdtotal") ?: key("sd_total")
        val freeRaw = key("sdfree") ?: key("sd_free")
        val sdStatus = key("sdstatus")
        val recStatus = key("record_sd_status") ?: key("record_status")

        if (totalRaw != null) {
            val totalGb = if (totalRaw >= 1_000_000) totalRaw / 1024.0 / 1024.0 else totalRaw / 1024.0
            refs.tvTotal.text = String.format(Locale.US, "%.1f GB", totalGb)
        }
        if (freeRaw != null) {
            val freeGb = if (freeRaw >= 1_000_000) freeRaw / 1024.0 / 1024.0 else freeRaw / 1024.0
            refs.tvFree.text = String.format(Locale.US, "%.1f GB", freeGb)
        }
        if (totalRaw != null && freeRaw != null && totalRaw > 0) {
            val used = ((totalRaw - freeRaw).coerceAtLeast(0)) * 100 / totalRaw
            refs.progress.progress = used.toInt()
            refs.tvPercent.text = "$used %"
        }
        if (sdStatus != null) {
            refs.tvState.text = when (sdStatus) {
                0L -> "sin tarjeta"
                1L -> "OK"
                2L -> "formateando"
                3L -> "error"
                else -> "sdstatus=$sdStatus"
            } + (if (recStatus != null) " · rec=$recStatus" else "")
        }
    }

    private fun appendLog(line: String) {
        sdLog.addLast(line)
        while (sdLog.size > MAX_LOG_LINES) sdLog.removeFirst()
        // Se reconstruye desde la cola acotada: el TextView muestra siempre las
        // últimas respuestas sin crecer sin límite con el flujo de `get_status`.
        val tvLog = tabViews[3]?.findViewById<TextView>(R.id.tvStatusLog) ?: return
        tvLog.text = sdLog.joinToString("\n")
    }

    // ------------------------------------------------------------------
    // Punto 4 — Llamada
    // ------------------------------------------------------------------

    private var callDialogView: View? = null

    private fun showCallDialog() {
        val v = layoutInflater.inflate(R.layout.dialog_call, null, false)
        callDialogView = v
        val group = v.findViewById<RadioGroup>(R.id.callModeGroup)
        val rbDuplex = v.findViewById<RadioButton>(R.id.rbCallDuplex)
        val rbPtt = v.findViewById<RadioButton>(R.id.rbCallPtt)
        val ptt = v.findViewById<TextView>(R.id.btnPtt)

        (if (config.callMode == CamConfig.CALL_MODE_PTT) rbPtt else rbDuplex).isChecked = true

        group.setOnCheckedChangeListener { _, checkedId ->
            config.callMode =
                if (checkedId == R.id.rbCallPtt) CamConfig.CALL_MODE_PTT else CamConfig.CALL_MODE_DUPLEX
            refreshCallDialog()
        }

        v.findViewById<LinearLayout>(R.id.btnCallToggle).setOnClickListener {
            if (VoiceLink.isActive()) stopCall() else startCall()
        }

        // Radio: mantener pulsado para hablar.
        ptt.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    if (!VoiceLink.isActive()) startCall()
                    true
                }

                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL,
                -> {
                    stopCall()
                    true
                }

                else -> false
            }
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.call_title)
            .setView(v)
            .setNegativeButton(R.string.close) { _, _ ->
                if (VoiceLink.isActive()) stopCall()
            }
            .create()
        callDialog = dialog
        dialog.show()
        refreshCallDialog()
    }

    private var callDialog: android.app.Dialog? = null

    private fun refreshCallDialog() {
        val v = callDialogView ?: return
        val active = VoiceLink.isActive()
        val pttMode = config.callMode == CamConfig.CALL_MODE_PTT
        v.findViewById<TextView>(R.id.tvCallToggle).setText(
            if (active) R.string.call_stop else R.string.call_start,
        )
        v.findViewById<TextView>(R.id.tvCallState).setText(
            if (active) R.string.call_active else R.string.state_idle,
        )
        v.findViewById<TextView>(R.id.btnPtt).visibility =
            if (active && pttMode) View.VISIBLE else View.GONE
        val fx = VoiceLink.effects()
        v.findViewById<TextView>(R.id.tvCallEffects).text = getString(
            R.string.call_effects,
            if (fx.echoCanceler) getString(R.string.on) else getString(R.string.off),
            if (fx.noiseSuppressor) getString(R.string.on) else getString(R.string.off),
        )
    }

    private fun startCall() {
        if (VoiceLink.isActive()) return
        val permission = Manifest.permission.RECORD_AUDIO
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            pendingBitmap = null
            permissionAction = ACTION_CALL
            permissionLauncher.launch(permission)
            return
        }
        doStartCall()
    }

    private fun doStartCall() {
        val duplex = config.callMode == CamConfig.CALL_MODE_DUPLEX
        VoiceLink.start(config, duplex) { duck -> onSpeakerDuck(duck) }
        refreshCallDialog()
        Toast.makeText(
            this,
            if (duplex) R.string.call_mode_duplex else R.string.call_mode_ptt,
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun stopCall() {
        lifecycleScope.launch {
            VoiceLink.stop(config) { duck -> onSpeakerDuck(duck) }
            refreshCallDialog()
        }
    }

    // ------------------------------------------------------------------
    // Punto 6 — pantalla completa con controles flotantes
    // ------------------------------------------------------------------

    private fun setupFullscreenOverlay() {
        btnFsExit.setOnClickListener { exitFullscreen() }

        val dpad = mapOf(
            R.id.fsBtnUp to PtzDirection.UP,
            R.id.fsBtnDown to PtzDirection.DOWN,
            R.id.fsBtnLeft to PtzDirection.LEFT,
            R.id.fsBtnRight to PtzDirection.RIGHT,
        )
        dpad.forEach { (id, direction) ->
            findViewById<ImageView>(id).setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        // Sobre el vídeo no hay fondo: el feedback es el tinte del
                        // icono (`fs_dpad_tint` → verde), activado con `isPressed`.
                        v.isPressed = true
                        requireP2P { sendPtz(direction) }
                        true
                    }

                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL,
                    -> {
                        v.isPressed = false
                        P2PControlManager.stopPtz()
                        true
                    }

                    else -> false
                }
            }
        }

        findViewById<ImageView>(R.id.fsBtnCenter).setOnClickListener {
            requireP2P { P2PControlManager.centerPtz() }
        }

        findViewById<TextView>(R.id.btnFsZoomIn).setOnClickListener {
            val cam = fullscreenCamera ?: return@setOnClickListener
            cam.setScale(cam.zoomLevel * 1.5f)
            tvFsZoom.text = String.format(Locale.US, "%.1fx", cam.zoomLevel)
        }
        findViewById<TextView>(R.id.btnFsZoomOut).setOnClickListener {
            val cam = fullscreenCamera ?: return@setOnClickListener
            cam.setScale(cam.zoomLevel / 1.5f)
            tvFsZoom.text = String.format(Locale.US, "%.1fx", cam.zoomLevel)
        }

        btnFsSound.setOnClickListener { applyGlobalSound(!config.soundEnabled) }
        findViewById<ImageView>(R.id.btnFsPhoto).setOnClickListener { takePhoto() }

        // Radio PTT con la pantalla completa.
        btnFsAudio.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    if (!VoiceLink.isActive()) startCall()
                    true
                }

                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL,
                -> {
                    stopCall()
                    true
                }

                else -> false
            }
        }
    }

    private fun toggleFullscreen(target: CameraStreamView) {
        if (fullscreenCamera == target) exitFullscreen() else enterFullscreen(target)
    }

    private fun enterFullscreen(target: CameraStreamView) {
        fullscreenCamera = target
        config.activeLens =
            if (target === camFixed) CamConfig.LENS_FIXED else CamConfig.LENS_PTZ

        topBar.visibility = View.GONE
        lensSelector.visibility = View.GONE
        tabs.visibility = View.GONE
        tabContent.visibility = View.GONE
        cameraDivider.visibility = View.GONE
        camFixed.visibility = if (target === camFixed) View.VISIBLE else View.GONE
        camPtz.visibility = if (target === camPtz) View.VISIBLE else View.GONE
        // En pantalla completa no hay panel de pestañas: las cámaras recuperan
        // todo el alto (el tamaño exacto de `applyOrientation` solo vale para el
        // modo normal). `applyLens()` lo restaura al salir.
        camerasRoot.updateLayoutParams<LinearLayout.LayoutParams> {
            height = ViewGroup.LayoutParams.MATCH_PARENT
        }
        target.updateLayoutParams<LinearLayout.LayoutParams> {
            width = ViewGroup.LayoutParams.MATCH_PARENT
            height = ViewGroup.LayoutParams.MATCH_PARENT
            weight = 1f
        }

        tvFsLabel.text = target.lensLabel
        tvFsZoom.text = String.format(Locale.US, "%.1fx", target.zoomLevel)
        fullscreenOverlay.visibility = View.VISIBLE
        // Oculta la barra de controles embebida: en pantalla completa mandan los
        // controles flotantes translúcidos.
        target.setEmbeddedControlsVisible(false)
    }

    private fun exitFullscreen() {
        val target = fullscreenCamera
        fullscreenCamera = null
        fullscreenOverlay.visibility = View.GONE
        topBar.visibility = View.VISIBLE
        lensSelector.visibility = View.VISIBLE
        tabs.visibility = View.VISIBLE
        tabContent.visibility = View.VISIBLE
        target?.setEmbeddedControlsVisible(true)
        applyLens()
    }

    // ------------------------------------------------------------------
    // Orientación (responsiva horizontal)
    // ------------------------------------------------------------------

    /**
     * Punto 3 — alturas responsivas del área de vídeo.
     *
     * En vertical con las dos lentes (`Ambas`) las cámaras se dan un alto **exacto**
     * que nunca supera ≈37 % de la pantalla (y además ≤ la altura natural 16:9 de
     * cada una), de forma que `tabContent` —que lleva `layout_weight="1"`— se queda
     * con ≈48–50 % del alto para la cruceta PTZ, la luz y el resto de pestañas.
     *
     * El excedente de ancho no produce bandas negras porque el encuadre base del
     * `TextureView` es `CENTER_CROP` (ver `ZoomableVideoLayout.applyTransform`).
     */
    private fun applyOrientation() {
        val landscape =
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val both = config.activeLens == CamConfig.LENS_BOTH
        camerasRoot.orientation =
            if (landscape || !both) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL

        if (both) {
            cameraDivider.visibility = View.VISIBLE
            cameraDivider.updateLayoutParams<LinearLayout.LayoutParams> {
                // Fila en horizontal → separador vertical; pila en vertical → separador horizontal.
                if (landscape) {
                    width = 1.dp
                    height = ViewGroup.LayoutParams.MATCH_PARENT
                } else {
                    width = ViewGroup.LayoutParams.MATCH_PARENT
                    height = 1.dp
                }
            }
        }

        val margin = 4.dp
        val screenH = resources.displayMetrics.heightPixels
        val screenW = resources.displayMetrics.widthPixels

        // Alto máximo que pueden ocupar las cámaras (fracción de la pantalla).
        val maxFraction = when {
            landscape -> LANDSCAPE_CAM_FRACTION
            both -> PORTRAIT_BOTH_CAM_FRACTION
            else -> PORTRAIT_SINGLE_CAM_FRACTION
        }
        val capTotal = (screenH * maxFraction).toInt()

        // Altura natural 16:9 de cada lente, para no superarla nunca.
        val naturalEachH = if (landscape) {
            // En horizontal las dos van en fila: cada una mide ~la mitad del ancho.
            ((screenW / 2 - margin * 2 - 1.dp) * 9 / 16).coerceAtLeast(1)
        } else {
            ((screenW - margin * 2) * 9 / 16).coerceAtLeast(1)
        }
        val eachH = if (both) {
            ((capTotal - 1.dp) / 2 - margin * 2).coerceAtLeast(1)
        } else {
            (capTotal - margin * 2).coerceAtLeast(1)
        }.coerceAtMost(naturalEachH)

        val total = if (both) eachH * 2 + margin * 4 + 1.dp else eachH + margin * 2
        camerasRoot.updateLayoutParams<LinearLayout.LayoutParams> {
            height = total
        }

        val onlyOne = !both
        listOf(camFixed, camPtz).forEach { camera ->
            camera.updateLayoutParams<LinearLayout.LayoutParams> {
                if (landscape || onlyOne) {
                    width = 0
                    height = ViewGroup.LayoutParams.MATCH_PARENT
                    weight = 1f
                } else {
                    width = ViewGroup.LayoutParams.MATCH_PARENT
                    height = eachH
                    weight = 0f
                }
                setMargins(margin, margin, margin, margin)
            }
        }
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------------
    // Punto 6 — captura de foto a la galería
    // ------------------------------------------------------------------

    private fun takePhoto() {
        val cam = fullscreenCamera ?: if (config.activeLens == CamConfig.LENS_PTZ) camPtz else camFixed
        val bmp = cam.captureBitmap()
        if (bmp == null) {
            Toast.makeText(this, R.string.photo_failed, Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingBitmap = bmp
            permissionAction = ACTION_PHOTO
            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        saveBitmapToGallery(bmp)
    }

    private fun saveBitmapToGallery(bmp: Bitmap) {
        val name = "CamRevert_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val ok = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveWithMediaStore(name, bmp)
            } else {
                saveLegacy(name, bmp)
            }
        } catch (t: Throwable) {
            false
        }
        Toast.makeText(this, if (ok) R.string.photo_saved else R.string.photo_failed, Toast.LENGTH_SHORT).show()
        pendingBitmap = null
    }

    /** Requiere API 29 (`MediaStore.Images.Media.IS_PENDING`). Solo se llama desde `saveBitmapToGallery`. */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun saveWithMediaStore(name: String, bmp: Bitmap): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/CamRevert",
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = contentResolver
        val uri: Uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return false
        resolver.openOutputStream(uri)?.use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
        } ?: return false
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return true
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(name: String, bmp: Bitmap): Boolean {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "CamRevert",
        )
        if (!dir.exists() && !dir.mkdirs()) return false
        val file = File(dir, "$name.jpg")
        FileOutputStream(file).use { out ->
            if (!bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)) return false
        }
        MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), arrayOf("image/jpeg"), null)
        return true
    }

    // ------------------------------------------------------------------
    // Configuración
    // ------------------------------------------------------------------

    private fun showSettingsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_settings, null, false)
        val etIp = dialogView.findViewById<EditText>(R.id.etIp)
        val etUid = dialogView.findViewById<EditText>(R.id.etUid)
        val etVuid = dialogView.findViewById<EditText>(R.id.etVuid)
        val etP2pDid = dialogView.findViewById<EditText>(R.id.etP2pDid)
        val etDasAddress = dialogView.findViewById<EditText>(R.id.etDasAddress)
        val etUser = dialogView.findViewById<EditText>(R.id.etUser)
        val etPassword = dialogView.findViewById<EditText>(R.id.etPassword)
        val etAccountUser = dialogView.findViewById<EditText>(R.id.etAccountUser)
        val etAccountPassword = dialogView.findViewById<EditText>(R.id.etAccountPassword)
        val etPortFixed = dialogView.findViewById<EditText>(R.id.etPortFixed)
        val etPortPtz = dialogView.findViewById<EditText>(R.id.etPortPtz)
        val etVoiceChannel = dialogView.findViewById<EditText>(R.id.etVoiceChannel)
        val spCodec = dialogView.findViewById<Spinner>(R.id.spinnerVoiceCodec)
        val btnScan = dialogView.findViewById<TextView>(R.id.btnScan)
        val tvScanResult = dialogView.findViewById<TextView>(R.id.tvScanResult)
        val swAutoConnect = dialogView.findViewById<MaterialSwitch>(R.id.swAutoConnect)
        val swPreferSoftware = dialogView.findViewById<MaterialSwitch>(R.id.swPreferSoftware)

        etIp.setText(config.ip)
        etUid.setText(config.uid)
        etVuid.setText(config.vuid)
        etP2pDid.setText(config.p2pDid)
        etDasAddress.setText(config.dasAddress)
        etUser.setText(config.user)
        etPassword.setText(config.password)
        etAccountUser.setText(config.accountUser)
        etAccountPassword.setText(config.accountPassword)
        etPortFixed.setText(config.rtspPortFixed.toString())
        etPortPtz.setText(config.rtspPortPtz.toString())
        etVoiceChannel.setText(config.voiceChannel.toString())
        swAutoConnect.isChecked = config.autoConnect
        swPreferSoftware.isChecked = config.preferSoftwareDecoder

        val codecLabels = listOf(
            getString(R.string.codec_g711a),
            getString(R.string.codec_g711u),
            getString(R.string.codec_pcm16),
        )
        spCodec.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            codecLabels,
        )
        spCodec.setSelection(config.voiceCodec.coerceIn(0, 2))

        btnScan.setOnClickListener {
            tvScanResult.text = getString(R.string.cfg_scan_hint)
            scanJob?.cancel()
            scanJob = lifecycleScope.launch {
                val found = LanScanner.scan(
                    baseIp = etIp.text.toString().ifBlank { config.ip },
                    onProgress = { ip ->
                        withContext(Dispatchers.Main) { tvScanResult.text = ip }
                    },
                )
                tvScanResult.text = if (found.isEmpty()) {
                    getString(R.string.cfg_scan_none)
                } else {
                    getString(R.string.cfg_scan_result, found.size) + "\n" +
                        found.joinToString("\n") { "${it.ip} : ${it.openPorts.joinToString(",")}" }
                }
                found.firstOrNull()?.let { etIp.setText(it.ip) }
            }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings)
            .setView(dialogView)
            .setPositiveButton(R.string.save) { _, _ ->
                config.ip = etIp.text.toString()
                config.uid = etUid.text.toString()
                config.vuid = etVuid.text.toString()
                config.p2pDid = etP2pDid.text.toString()
                config.dasAddress = etDasAddress.text.toString()
                config.user = etUser.text.toString()
                config.password = etPassword.text.toString()
                config.accountUser = etAccountUser.text.toString()
                config.accountPassword = etAccountPassword.text.toString()
                config.rtspPortFixed = etPortFixed.text.toString().toIntOrNull()
                    ?: CamConfig.DEFAULT_PORT_FIXED
                config.rtspPortPtz = etPortPtz.text.toString().toIntOrNull()
                    ?: CamConfig.DEFAULT_PORT_PTZ
                config.voiceChannel = etVoiceChannel.text.toString().toIntOrNull()
                    ?: CamConfig.DEFAULT_VOICE_CHANNEL
                config.voiceCodec = spCodec.selectedItemPosition.coerceIn(0, 2)
                config.autoConnect = swAutoConnect.isChecked
                config.preferSoftwareDecoder = swPreferSoftware.isChecked
                restartStreams()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun restartStreams() {
        camFixed.stop()
        camPtz.stop()
        camFixed.setup(config, CamConfig.LENS_FIXED, getString(R.string.cam_fixed))
        camPtz.setup(config, CamConfig.LENS_PTZ, getString(R.string.cam_ptz))
        camFixed.applyGlobalSound(config.soundEnabled)
        camPtz.applyGlobalSound(config.soundEnabled)
        camFixed.start()
        camPtz.start()
        lifecycleScope.launch {
            P2PControlManager.disconnect()
            P2PControlManager.connect()
        }
    }

    companion object {
        private const val MAX_LOG_LINES = 6
        private const val MAX_CONSOLE_LINES = 600
        private const val ACTION_PHOTO = 1
        private const val ACTION_CALL = 2

        /**
         * Punto 3: fracción de la pantalla que pueden ocupar las cámaras.
         *
         * En vertical «Ambas» se queda en 37 % para que el panel de pestañas
         * conserve ≈48–50 % de alto y la cruceta PTZ no quede cortada.
         */
        private const val PORTRAIT_BOTH_CAM_FRACTION = 0.37f
        private const val PORTRAIT_SINGLE_CAM_FRACTION = 0.50f
        private const val LANDSCAPE_CAM_FRACTION = 0.60f
    }
}
