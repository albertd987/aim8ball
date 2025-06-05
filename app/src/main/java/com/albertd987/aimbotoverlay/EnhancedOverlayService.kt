// EnhancedOverlayService.kt
package com.albertd987.aimbotoverlay

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlinx.coroutines.*

class EnhancedOverlayService : Service(), DetectionCallback {

    private lateinit var windowManager: WindowManager
    private lateinit var hybridButtonView: HybridButtonView
    private lateinit var hybridAimingSystem: HybridAimingSystem
    private lateinit var gameDetectionService: GameDetectionService
    private lateinit var smartCalibrationSystem: SmartCalibrationSystem
    private lateinit var physicsEngine: PhysicsEngine

    private lateinit var buttonParams: WindowManager.LayoutParams
    private lateinit var aimingParams: WindowManager.LayoutParams

    private var currentGameState = GameState()
    private var confidenceState = ConfidenceState()
    private var isDetectionActive = false
    private var isSystemActive = false

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate() {
        super.onCreate()

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // Inicializar componentes
        initializeComponents()

        // Configurar vistas
        setupViews()

        // Inicializar motores
        physicsEngine = PhysicsEngine()
        smartCalibrationSystem = SmartCalibrationSystem(this)
        gameDetectionService = GameDetectionService()
    }

    private fun initializeComponents() {
        // Crear vistas principales
        hybridButtonView = HybridButtonView(this, this)
        hybridAimingSystem = HybridAimingSystem(this, this)
    }

    private fun setupViews() {
        // Configuración de la vista de botones (siempre visible, captura touch)
        buttonParams = WindowManager.LayoutParams(
            120, 220, // Tamaño fijo para botones
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        buttonParams.gravity = Gravity.TOP or Gravity.END
        buttonParams.x = 20
        buttonParams.y = 100

        // Configuración de la vista de puntería (pantalla completa, touch dinámico)
        aimingParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )

        // Agregar vistas al WindowManager
        windowManager.addView(hybridButtonView, buttonParams)
        windowManager.addView(hybridAimingSystem, aimingParams)
    }

    // ========== MÉTODOS PÚBLICOS PARA CONTROL ==========

    fun setSystemActive(active: Boolean) {
        isSystemActive = active

        if (!active) {
            stopDetection()
            hideTrajectory()
            setAimingMode(AimingMode.AUTO_DETECT)
        }

        hybridButtonView.updateSystemState(active)
    }

    fun setAimingMode(mode: AimingMode) {
        hybridAimingSystem.setMode(mode)
        hybridButtonView.updateAimingMode(mode)

        // Ajustar comportamiento según el modo
        when (mode) {
            AimingMode.AUTO_DETECT -> {
                disableTouchForView(hybridAimingSystem)
            }
            AimingMode.MANUAL_SETUP -> {
                stopDetection()
                enableTouchForView(hybridAimingSystem)
            }
            AimingMode.HYBRID -> {
                if (isDetectionActive) {
                    disableTouchForView(hybridAimingSystem)
                } else {
                    enableTouchForView(hybridAimingSystem)
                }
            }
        }
    }

    fun startDetection(mediaProjection: MediaProjection? = null) {
        if (!isDetectionActive && mediaProjection != null) {
            isDetectionActive = true

            serviceScope.launch {
                try {
                    gameDetectionService.startDetection(mediaProjection, this@EnhancedOverlayService)
                    hybridButtonView.updateDetectionState(true)
                } catch (e: Exception) {
                    onError(DetectionError.ScreenCaptureError)
                }
            }
        }
    }

    fun stopDetection() {
        if (isDetectionActive) {
            isDetectionActive = false
            gameDetectionService.stopDetection()
            hybridButtonView.updateDetectionState(false)
        }
    }

    fun showTrajectory() {
        hybridAimingSystem.showTrajectory()
        hybridButtonView.updateTrajectoryState(true)
    }

    fun hideTrajectory() {
        hybridAimingSystem.hideTrajectory()
        hybridButtonView.updateTrajectoryState(false)
    }

    fun startCalibration() {
        serviceScope.launch {
            try {
                // Capturar frame actual para calibración
                val currentFrame = gameDetectionService.getCurrentFrame()
                if (currentFrame != null) {
                    smartCalibrationSystem.startAutoCalibration(currentFrame) { result ->
                        handleCalibrationResult(result)
                    }
                } else {
                    onError(DetectionError.CalibrationRequired)
                }
            } catch (e: Exception) {
                onError(DetectionError.CustomError("Error en calibración: ${e.message}"))
            }
        }
    }

    // ========== CALLBACKS DE DETECCIÓN ==========

    override fun onGameStateDetected(gameState: GameState) {
        currentGameState = gameState

        // Actualizar confianza
        confidenceState.detectionConfidence = calculateDetectionConfidence(gameState)
        confidenceState.updateOverallConfidence()

        // Notificar a las vistas
        hybridAimingSystem.onGameStateDetected(gameState)
        hybridButtonView.updateDetectionConfidence(confidenceState.detectionConfidence)

        // En modo híbrido, evaluar si cambiar de modo
        if (hybridAimingSystem.getCurrentMode() == AimingMode.HYBRID) {
            evaluateHybridMode()
        }
    }

    override fun onConfidenceChanged(confidence: ConfidenceState) {
        this.confidenceState = confidence
        hybridButtonView.updateDetectionConfidence(confidence.overallConfidence)
    }

    override fun onCalibrationNeeded() {
        // Auto-iniciar calibración si es necesario
        serviceScope.launch {
            delay(1000) // Esperar un poco antes de calibrar
            startCalibration()
        }
    }

    override fun onError(error: DetectionError) {
        when (error) {
            is DetectionError.TableNotFound -> {
                // Cambiar a modo manual temporalmente
                if (hybridAimingSystem.getCurrentMode() == AimingMode.HYBRID) {
                    enableTouchForView(hybridAimingSystem)
                }
            }
            is DetectionError.CalibrationRequired -> {
                startCalibration()
            }
            is DetectionError.ScreenCaptureError -> {
                stopDetection()
                hybridButtonView.showError("Error de captura")
            }
            else -> {
                // Log otros errores
                android.util.Log.w("EnhancedOverlay", "Detection error: ${error.message}")
            }
        }
    }

    // ========== GESTIÓN DE TOUCH ==========

    fun enableTouchForView(view: View) {
        serviceScope.launch {
            try {
                windowManager.removeView(view)

                val params = if (view == hybridAimingSystem) aimingParams else buttonParams
                params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()

                windowManager.addView(view, params)
            } catch (e: Exception) {
                android.util.Log.e("EnhancedOverlay", "Error enabling touch: ${e.message}")
            }
        }
    }

    fun disableTouchForView(view: View) {
        serviceScope.launch {
            try {
                windowManager.removeView(view)

                val params = if (view == hybridAimingSystem) aimingParams else buttonParams
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE

                windowManager.addView(view, params)
            } catch (e: Exception) {
                android.util.Log.e("EnhancedOverlay", "Error disabling touch: ${e.message}")
            }
        }
    }

    // ========== LÓGICA INTERNA ==========

    private fun calculateDetectionConfidence(gameState: GameState): Float {
        var confidence = 0f

        // +0.3 si detecta la mesa
        if (gameState.tableBounds != null) confidence += 0.3f

        // +0.4 si detecta la bola blanca
        if (gameState.cueBall != null) confidence += 0.4f

        // +0.2 si detecta dirección del taco
        if (gameState.cueDirection != null) confidence += 0.2f

        // +0.1 si detecta bolas objetivo
        if (gameState.targetBalls.isNotEmpty()) confidence += 0.1f

        return confidence.coerceIn(0f, 1f)
    }

    private fun evaluateHybridMode() {
        val threshold = 0.7f
        val currentMode = hybridAimingSystem.getCurrentMode()

        if (currentMode == AimingMode.HYBRID) {
            when {
                confidenceState.detectionConfidence >= threshold -> {
                    // Confianza alta: usar detección automática
                    disableTouchForView(hybridAimingSystem)
                }
                confidenceState.detectionConfidence < 0.3f -> {
                    // Confianza baja: permitir controles manuales
                    enableTouchForView(hybridAimingSystem)
                }
                // Entre 0.3 y 0.7: mantener estado actual
            }
        }
    }

    private fun handleCalibrationResult(result: CalibrationResult) {
        if (result.confidence > 0.6f) {
            // Aplicar configuración calibrada
            gameDetectionService.updateConfig(result.toDetectionConfig())
            physicsEngine.updateConfig(result.physicsConfig)

            // Notificar éxito
            hybridButtonView.showCalibrationSuccess()
        } else {
            // Calibración falló, mantener configuración actual
            hybridButtonView.showCalibrationWarning()
        }
    }

    // ========== GESTIÓN DE CORRECCIONES MANUALES ==========

    fun onManualCorrection(correction: ManualCorrection) {
        // Aprender de la corrección manual
        smartCalibrationSystem.learnFromManualCorrection(correction)

        // Actualizar confianza
        confidenceState.detectionConfidence *= 0.9f // Reducir confianza tras corrección
        confidenceState.updateOverallConfidence()
    }

    // ========== CICLO DE VIDA ==========

    override fun onDestroy() {
        super.onDestroy()

        // Limpiar recursos
        stopDetection()
        serviceScope.cancel()

        // Remover vistas
        try {
            windowManager.removeView(hybridButtonView)
            windowManager.removeView(hybridAimingSystem)
        } catch (e: Exception) {
            android.util.Log.e("EnhancedOverlay", "Error removing views: ${e.message}")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ========== MÉTODOS DE EXTENSIÓN ==========

    fun getCurrentGameState(): GameState = currentGameState

    fun getConfidenceState(): ConfidenceState = confidenceState

    fun getPhysicsEngine(): PhysicsEngine = physicsEngine

    fun getHybridAimingSystem(): HybridAimingSystem = hybridAimingSystem

    fun isSystemActive(): Boolean = isSystemActive

    fun isDetectionActive(): Boolean = isDetectionActive

    // ========== MÉTODOS ADICIONALES PARA COMPATIBILIDAD ==========

    fun enableManualSetup() {
        // Habilitar modo de configuración manual
        enableTouchForView(hybridAimingSystem)
        hybridAimingSystem.enableSetupMode()
    }

    fun disableManualSetup() {
        // Deshabilitar modo de configuración manual
        disableTouchForView(hybridAimingSystem)
        hybridAimingSystem.disableSetupMode()
    }

    fun startDetection() {
        // Versión sin parámetros que usa MediaProjection guardado
        MediaProjectionHolder.getProjection()?.let { projection ->
            startDetection(projection)
        } ?: run {
            onError(DetectionError.ScreenCaptureError)
        }
    }
}

// Extensiones para conversión de datos
private fun CalibrationResult.toDetectionConfig(): DetectionConfig {
    return DetectionConfig(
        tableBounds = this.tableBounds,
        ballRadius = this.elementSizes.ballRadius,
        colorTolerance = this.colorProfile.colorTolerance,
        pocketRadius = this.elementSizes.pocketRadius,
        tableColor = this.colorProfile.tableColor
    )
}

// Extensiones para HybridAimingSystem
fun HybridAimingSystem.getCurrentMode(): AimingMode {
    // Acceder al modo actual del sistema
    return this.getCurrentMode()
}

// Extensiones para HybridButtonView
fun HybridButtonView.updateSystemState(active: Boolean) {
    // Actualizar estado del sistema en la vista
    this.updateDetectionState(active)
}

fun HybridButtonView.showError(message: String) {
    // Mostrar error en la interfaz
    // En una implementación real, podrías agregar un Toast o cambiar color de botón
    android.util.Log.e("ButtonView", "Error: $message")
}

fun HybridButtonView.showCalibrationSuccess() {
    // Mostrar éxito de calibración
    // Podría hacer que el botón CAL se ponga verde brevemente
    android.util.Log.i("ButtonView", "Calibración exitosa")
}

fun HybridButtonView.showCalibrationWarning() {
    // Mostrar advertencia de calibración
    // Podría hacer que el botón CAL se ponga amarillo brevemente
    android.util.Log.w("ButtonView", "Calibración con advertencias")
}

// Extensiones para GameDetectionService
fun GameDetectionService.getCurrentFrame(): android.graphics.Bitmap? {
    // Obtener frame actual para calibración
    return this.getCurrentFrame()
}

fun GameDetectionService.updateConfig(config: DetectionConfig) {
    // Actualizar configuración de detección
    this.updateConfig(config)
}