// HybridButtonView.kt
package com.albertd987.aimbotoverlay

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View

class HybridButtonView(
    context: Context,
    private val overlayService: EnhancedOverlayService
) : View(context) {

    private val buttonPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val buttonTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = 22f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
        isFakeBoldText = true
        setShadowLayer(2f, 1f, 1f, Color.BLACK)
    }

    private val borderPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
    }

    // Estados del sistema
    private var isSystemActive = false
    private var currentAimingMode = AimingMode.AUTO_DETECT
    private var isDetectionActive = false
    private var isTrajectoryVisible = false
    private var detectionConfidence = 0f

    // Botones
    private val buttonWidth = 90f
    private val buttonHeight = 35f
    private val buttonSpacing = 8f
    private val margin = 10f

    private var powerButtonRect = RectF()
    private var modeButtonRect = RectF()
    private var detectionButtonRect = RectF()
    private var aimButtonRect = RectF()
    private var calibrateButtonRect = RectF()

    init {
        setupButtonRects()
    }

    private fun setupButtonRects() {
        var currentY = margin

        // Botón principal ON/OFF
        powerButtonRect.set(margin, currentY, margin + buttonWidth, currentY + buttonHeight)
        currentY += buttonHeight + buttonSpacing

        // Botón de modo (AUTO/MANUAL/HYBRID)
        modeButtonRect.set(margin, currentY, margin + buttonWidth, currentY + buttonHeight)
        currentY += buttonHeight + buttonSpacing

        // Botón de detección
        detectionButtonRect.set(margin, currentY, margin + buttonWidth, currentY + buttonHeight)
        currentY += buttonHeight + buttonSpacing

        // Botón de aim/trayectorias
        aimButtonRect.set(margin, currentY, margin + buttonWidth, currentY + buttonHeight)
        currentY += buttonHeight + buttonSpacing

        // Botón de calibración
        calibrateButtonRect.set(margin, currentY, margin + buttonWidth, currentY + buttonHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Botón principal ON/OFF
        drawButton(
            canvas,
            powerButtonRect,
            if (isSystemActive) "ON" else "OFF",
            if (isSystemActive) Color.rgb(76, 175, 80) else Color.rgb(244, 67, 54)
        )

        // Solo mostrar otros botones si el sistema está activo
        if (isSystemActive) {
            // Botón de modo
            val modeText = when (currentAimingMode) {
                AimingMode.AUTO_DETECT -> "AUTO"
                AimingMode.MANUAL_SETUP -> "MANUAL"
                AimingMode.HYBRID -> "HÍBRIDO"
            }

            val modeColor = when (currentAimingMode) {
                AimingMode.AUTO_DETECT -> Color.rgb(33, 150, 243)    // Azul
                AimingMode.MANUAL_SETUP -> Color.rgb(156, 39, 176)   // Púrpura
                AimingMode.HYBRID -> {
                    if (detectionConfidence >= 0.7f) Color.rgb(76, 175, 80)  // Verde
                    else Color.rgb(255, 193, 7)  // Amarillo
                }
            }

            drawButton(canvas, modeButtonRect, modeText, modeColor)

            // Botón de detección (solo visible en modo AUTO o HYBRID)
            if (currentAimingMode != AimingMode.MANUAL_SETUP) {
                drawButton(
                    canvas,
                    detectionButtonRect,
                    "DETECT",
                    if (isDetectionActive) Color.rgb(255, 193, 7) else Color.rgb(96, 125, 139)
                )
            }

            // Botón de aim/trayectorias
            val aimText = when (currentAimingMode) {
                AimingMode.MANUAL_SETUP -> "SETUP"
                else -> "AIM"
            }

            drawButton(
                canvas,
                aimButtonRect,
                aimText,
                if (isTrajectoryVisible) Color.rgb(255, 87, 34) else Color.rgb(63, 81, 181)
            )

            // Botón de calibración
            drawButton(
                canvas,
                calibrateButtonRect,
                "CAL",
                Color.rgb(158, 158, 158)
            )

            // Indicador de confianza para modo híbrido
            if (currentAimingMode == AimingMode.HYBRID) {
                drawConfidenceIndicator(canvas)
            }
        }

        // Actualizar cada 200ms
        postInvalidateDelayed(200)
    }

    private fun drawButton(canvas: Canvas, rect: RectF, text: String, color: Int) {
        // Fondo con gradiente sutil
        val gradient = LinearGradient(
            rect.left, rect.top, rect.left, rect.bottom,
            Color.argb(220, Color.red(color), Color.green(color), Color.blue(color)),
            Color.argb(180,
                (Color.red(color) * 0.8f).toInt(),
                (Color.green(color) * 0.8f).toInt(),
                (Color.blue(color) * 0.8f).toInt()
            ),
            Shader.TileMode.CLAMP
        )

        buttonPaint.shader = gradient
        canvas.drawRoundRect(rect, 8f, 8f, buttonPaint)

        // Borde
        borderPaint.color = color
        canvas.drawRoundRect(rect, 8f, 8f, borderPaint)

        // Texto
        canvas.drawText(text, rect.centerX(), rect.centerY() + 6f, buttonTextPaint)

        // Reset shader
        buttonPaint.shader = null
    }

    private fun drawConfidenceIndicator(canvas: Canvas) {
        val indicatorWidth = buttonWidth
        val indicatorHeight = 8f
        val y = calibrateButtonRect.bottom + 10f

        // Fondo
        val bgPaint = Paint().apply {
            color = Color.argb(100, 0, 0, 0)
            style = Paint.Style.FILL
        }

        val bgRect = RectF(margin, y, margin + indicatorWidth, y + indicatorHeight)
        canvas.drawRoundRect(bgRect, 4f, 4f, bgPaint)

        // Barra de confianza
        val confidenceWidth = indicatorWidth * detectionConfidence
        val confidenceColor = when {
            detectionConfidence >= 0.7f -> Color.GREEN
            detectionConfidence >= 0.4f -> Color.YELLOW
            else -> Color.RED
        }

        val confidencePaint = Paint().apply {
            color = confidenceColor
            style = Paint.Style.FILL
        }

        if (confidenceWidth > 0) {
            val confidenceRect = RectF(margin, y, margin + confidenceWidth, y + indicatorHeight)
            canvas.drawRoundRect(confidenceRect, 4f, 4f, confidencePaint)
        }

        // Texto de confianza
        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 16f
            textAlign = Paint.Align.LEFT
            setShadowLayer(1f, 0f, 0f, Color.BLACK)
        }

        canvas.drawText(
            "${(detectionConfidence * 100).toInt()}%",
            margin, y + indicatorHeight + 20f,
            textPaint
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val x = event.x
                val y = event.y

                // Botón principal (ON/OFF)
                if (powerButtonRect.contains(x, y)) {
                    isSystemActive = !isSystemActive

                    if (!isSystemActive) {
                        // Apagar todo
                        isDetectionActive = false
                        isTrajectoryVisible = false
                        overlayService.stopDetection()
                        overlayService.hideTrajectory()
                        overlayService.setAimingMode(AimingMode.AUTO_DETECT)
                    } else {
                        // Activar con modo por defecto
                        overlayService.setAimingMode(currentAimingMode)
                    }

                    return true
                }

                // Solo procesar otros botones si el sistema está activo
                if (isSystemActive) {
                    // Botón de modo (ciclar entre modos)
                    if (modeButtonRect.contains(x, y)) {
                        currentAimingMode = when (currentAimingMode) {
                            AimingMode.AUTO_DETECT -> AimingMode.HYBRID
                            AimingMode.HYBRID -> AimingMode.MANUAL_SETUP
                            AimingMode.MANUAL_SETUP -> AimingMode.AUTO_DETECT
                        }

                        overlayService.setAimingMode(currentAimingMode)

                        // Resetear estados según el nuevo modo
                        when (currentAimingMode) {
                            AimingMode.MANUAL_SETUP -> {
                                isDetectionActive = false
                                overlayService.stopDetection()
                            }
                            AimingMode.AUTO_DETECT -> {
                                // Mantener estados actuales
                            }
                            AimingMode.HYBRID -> {
                                // Activar detección automáticamente en modo híbrido
                                if (!isDetectionActive) {
                                    isDetectionActive = true
                                    overlayService.startDetection()
                                }
                            }
                        }

                        return true
                    }

                    // Botón de detección (solo en modo AUTO o HYBRID)
                    if (currentAimingMode != AimingMode.MANUAL_SETUP &&
                        detectionButtonRect.contains(x, y)) {

                        isDetectionActive = !isDetectionActive

                        if (isDetectionActive) {
                            overlayService.startDetection()
                        } else {
                            overlayService.stopDetection()
                            // Si se apaga detección, también apagar trayectorias
                            if (currentAimingMode == AimingMode.AUTO_DETECT) {
                                isTrajectoryVisible = false
                                overlayService.hideTrajectory()
                            }
                        }

                        return true
                    }

                    // Botón de aim/setup
                    if (aimButtonRect.contains(x, y)) {
                        when (currentAimingMode) {
                            AimingMode.MANUAL_SETUP -> {
                                // En modo manual, el botón alterna setup/vista
                                isTrajectoryVisible = !isTrajectoryVisible
                                if (isTrajectoryVisible) {
                                    overlayService.showTrajectory()
                                    overlayService.enableManualSetup()
                                } else {
                                    overlayService.hideTrajectory()
                                    overlayService.disableManualSetup()
                                }
                            }

                            AimingMode.AUTO_DETECT -> {
                                // En modo auto, solo mostrar si hay detección activa
                                if (isDetectionActive) {
                                    isTrajectoryVisible = !isTrajectoryVisible
                                    if (isTrajectoryVisible) {
                                        overlayService.showTrajectory()
                                    } else {
                                        overlayService.hideTrajectory()
                                    }
                                }
                            }

                            AimingMode.HYBRID -> {
                                // En modo híbrido, siempre permitir
                                isTrajectoryVisible = !isTrajectoryVisible
                                if (isTrajectoryVisible) {
                                    overlayService.showTrajectory()
                                } else {
                                    overlayService.hideTrajectory()
                                }
                            }
                        }

                        return true
                    }

                    // Botón de calibración
                    if (calibrateButtonRect.contains(x, y)) {
                        overlayService.startCalibration()
                        return true
                    }
                }
            }
        }
        return false
    }

    // Métodos para actualizar estado desde el servicio
    fun updateDetectionState(isActive: Boolean) {
        isDetectionActive = isActive
        invalidate()
    }

    fun updateTrajectoryState(isVisible: Boolean) {
        isTrajectoryVisible = isVisible
        invalidate()
    }

    fun updateAimingMode(mode: AimingMode) {
        currentAimingMode = mode
        invalidate()
    }

    fun updateDetectionConfidence(confidence: Float) {
        detectionConfidence = confidence
        if (currentAimingMode == AimingMode.HYBRID) {
            invalidate()
        }
    }

    fun showModeChangeAnimation(newMode: AimingMode) {
        // Animación simple de feedback visual
        val originalSize = buttonWidth

        // Hacer que el botón de modo "pulse" brevemente
        animate()
            .scaleX(1.1f)
            .scaleY(1.1f)
            .setDuration(150)
            .withEndAction {
                animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(150)
            }
    }
}

// Extensión del EnhancedOverlayService para manejar el sistema híbrido
/*
Agregar estos métodos al EnhancedOverlayService:

fun setAimingMode(mode: AimingMode) {
    hybridAimingSystem.setMode(mode)
    hybridButtonView.updateAimingMode(mode)
}

fun enableManualSetup() {
    // Habilitar captura de touch para el setup manual
    enableTouchForView(hybridAimingSystem)
}

fun disableManualSetup() {
    // Deshabilitar captura de touch
    disableTouchForView(hybridAimingSystem)
}

fun startCalibration() {
    // Iniciar proceso de calibración
    calibrationManager.startAutoCalibration()
}

fun enableTouchForView(view: View) {
    // Cambiar flags de WindowManager para permitir touch en la vista específica
    val params = view.layoutParams as WindowManager.LayoutParams
    params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
    windowManager.updateViewLayout(view, params)
}

fun disableTouchForView(view: View) {
    // Cambiar flags para NO permitir touch
    val params = view.layoutParams as WindowManager.LayoutParams  
    params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
    windowManager.updateViewLayout(view, params)
}
*/