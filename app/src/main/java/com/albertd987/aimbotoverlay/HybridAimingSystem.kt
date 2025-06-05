// HybridAimingSystem.kt
package com.albertd987.aimbotoverlay

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

class HybridAimingSystem(
    context: Context,
    private val overlayService: EnhancedOverlayService
) : View(context), DetectionCallback {

    companion object {
        private const val DETECTION_CONFIDENCE_THRESHOLD = 0.7f
        private const val CONTROL_RADIUS = 50f
    }

    // ========== ESTADO DEL SISTEMA ==========
    private var currentMode = AimingMode.AUTO_DETECT
    private var isSetupMode = false
    private var detectionConfidence = 0f
    private var showLines = false

    // Estados del juego
    private var autoGameState = GameState()
    private var manualGameState = ManualGameState()
    private var finalGameState = GameState()

    // Controles manuales
    private var isDragging = false
    private var draggedControl: ControlPoint? = null
    private var showControls = false

    // ========== PAINTS ==========
    private val cueBallPaint = Paint().apply {
        color = Color.WHITE
        strokeWidth = 6f
        style = Paint.Style.STROKE
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }

    private val manualCueBallPaint = Paint().apply {
        color = Color.rgb(255, 215, 0)
        strokeWidth = 4f
        style = Paint.Style.STROKE
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }

    private val aimLinePaint = Paint().apply {
        color = Color.YELLOW
        strokeWidth = 5f
        style = Paint.Style.STROKE
        setShadowLayer(3f, 0f, 0f, Color.BLACK)
    }

    private val controlPaint = Paint().apply {
        color = Color.rgb(0, 150, 255)
        style = Paint.Style.FILL
        setShadowLayer(3f, 0f, 0f, Color.BLACK)
    }

    private val controlBorderPaint = Paint().apply {
        color = Color.WHITE
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    private val trajectoryPaint = Paint().apply {
        color = Color.RED
        strokeWidth = 4f
        style = Paint.Style.STROKE
        setShadowLayer(3f, 0f, 0f, Color.BLACK)
    }

    private val modePaint = Paint().apply {
        color = Color.WHITE
        textSize = 32f
        textAlign = Paint.Align.LEFT
        setShadowLayer(3f, 1f, 1f, Color.BLACK)
        isFakeBoldText = true
    }

    init {
        setupManualControls()
    }

    // ========== CONFIGURACIÓN INICIAL ==========
    private fun setupManualControls() {
        val screenWidth = resources.displayMetrics.widthPixels.toFloat()
        val screenHeight = resources.displayMetrics.heightPixels.toFloat()

        manualGameState.cueBallControl = ControlPoint(
            PointF(screenWidth * 0.3f, screenHeight * 0.6f),
            ControlType.CUE_BALL,
            "Bola Blanca"
        )

        manualGameState.aimControl = ControlPoint(
            PointF(screenWidth * 0.6f, screenHeight * 0.4f),
            ControlType.AIM_TARGET,
            "Dirección"
        )

        manualGameState.fineAdjustControls = listOf(
            ControlPoint(PointF(screenWidth * 0.9f, screenHeight * 0.2f), ControlType.FINE_TUNE_UP, "↑"),
            ControlPoint(PointF(screenWidth * 0.9f, screenHeight * 0.3f), ControlType.FINE_TUNE_DOWN, "↓"),
            ControlPoint(PointF(screenWidth * 0.85f, screenHeight * 0.25f), ControlType.FINE_TUNE_LEFT, "←"),
            ControlPoint(PointF(screenWidth * 0.95f, screenHeight * 0.25f), ControlType.FINE_TUNE_RIGHT, "→")
        )
    }

    // ========== MÉTODOS PÚBLICOS PRINCIPALES ==========
    fun setMode(mode: AimingMode) {
        currentMode = mode
        when (mode) {
            AimingMode.AUTO_DETECT -> {
                showControls = false
                isSetupMode = false
                disableTouchCapture()
            }
            AimingMode.MANUAL_SETUP -> {
                showControls = true
                isSetupMode = true
                enableTouchCapture()
            }
            AimingMode.HYBRID -> {
                evaluateAndSwitchMode()
            }
        }
        invalidate()
    }

    fun showTrajectory() {
        showLines = true
        invalidate()
    }

    fun hideTrajectory() {
        showLines = false
        invalidate()
    }

    fun enableSetupMode() {
        isSetupMode = true
        showControls = true
        invalidate()
    }

    fun disableSetupMode() {
        isSetupMode = false
        showControls = false
        invalidate()
    }

    fun getCurrentMode(): AimingMode = currentMode

    fun isInSetupMode(): Boolean = isSetupMode

    fun getManualGameState(): ManualGameState = manualGameState

    // ========== IMPLEMENTACIÓN DE DetectionCallback ==========
    override fun onGameStateDetected(gameState: GameState) {
        autoGameState = gameState
        detectionConfidence = calculateDetectionConfidence(gameState)

        if (currentMode == AimingMode.HYBRID) {
            evaluateAndSwitchMode()
        }

        updateFinalGameState()
        invalidate()
    }

    override fun onConfidenceChanged(confidence: ConfidenceState) {
        detectionConfidence = confidence.overallConfidence
        if (currentMode == AimingMode.HYBRID) {
            evaluateAndSwitchMode()
        }
        invalidate()
    }

    override fun onCalibrationNeeded() {
        if (currentMode == AimingMode.AUTO_DETECT || currentMode == AimingMode.HYBRID) {
            showControls = true
            enableTouchCapture()
        }
    }

    override fun onError(error: DetectionError) {
        when (error) {
            is DetectionError.TableNotFound -> {
                if (currentMode == AimingMode.HYBRID) {
                    showControls = true
                    enableTouchCapture()
                }
            }
            is DetectionError.CueBallNotFound -> {
                detectionConfidence *= 0.5f
            }
            else -> {
                if (currentMode != AimingMode.MANUAL_SETUP) {
                    showControls = true
                    enableTouchCapture()
                }
            }
        }
        invalidate()
    }

    // ========== LÓGICA INTERNA ==========
    private fun calculateDetectionConfidence(gameState: GameState): Float {
        var confidence = 0f
        if (gameState.tableBounds != null) confidence += 0.3f
        if (gameState.cueBall != null) confidence += 0.4f
        if (gameState.cueDirection != null) confidence += 0.2f
        if (gameState.targetBalls.isNotEmpty()) confidence += 0.1f
        return confidence.coerceIn(0f, 1f)
    }

    private fun evaluateAndSwitchMode() {
        when {
            detectionConfidence >= DETECTION_CONFIDENCE_THRESHOLD -> {
                if (showControls) {
                    showControls = false
                    disableTouchCapture()
                }
            }
            detectionConfidence < 0.3f -> {
                if (!showControls) {
                    showControls = true
                    isSetupMode = true
                    enableTouchCapture()
                }
            }
        }
    }

    private fun updateFinalGameState() {
        finalGameState = when (currentMode) {
            AimingMode.AUTO_DETECT -> autoGameState
            AimingMode.MANUAL_SETUP -> convertManualToGameState()
            AimingMode.HYBRID -> {
                if (detectionConfidence >= DETECTION_CONFIDENCE_THRESHOLD) {
                    autoGameState
                } else {
                    mergeAutoAndManual()
                }
            }
        }
    }

    private fun convertManualToGameState(): GameState {
        val gameState = GameState()

        manualGameState.cueBallControl?.let { control ->
            gameState.cueBall = Ball(control.position, 12f, BallType.CUE)
        }

        val cueBall = manualGameState.cueBallControl?.position
        val aimTarget = manualGameState.aimControl?.position

        if (cueBall != null && aimTarget != null) {
            gameState.cueDirection = atan2(aimTarget.y - cueBall.y, aimTarget.x - cueBall.x)
            gameState.aimTarget = aimTarget
        }

        gameState.tableBounds = autoGameState.tableBounds
        gameState.pockets = autoGameState.pockets
        gameState.targetBalls = autoGameState.targetBalls

        return gameState
    }

    private fun mergeAutoAndManual(): GameState {
        val merged = GameState()

        merged.cueBall = if (manualGameState.isFullyConfigured()) {
            manualGameState.cueBallControl?.let { Ball(it.position, 12f, BallType.CUE) }
        } else {
            autoGameState.cueBall
        }

        merged.cueDirection = if (manualGameState.isFullyConfigured()) {
            val cueBall = manualGameState.cueBallControl?.position
            val aimTarget = manualGameState.aimControl?.position
            if (cueBall != null && aimTarget != null) {
                atan2(aimTarget.y - cueBall.y, aimTarget.x - cueBall.x)
            } else autoGameState.cueDirection
        } else {
            autoGameState.cueDirection
        }

        merged.tableBounds = autoGameState.tableBounds
        merged.pockets = autoGameState.pockets
        merged.targetBalls = autoGameState.targetBalls
        merged.aimTarget = manualGameState.aimControl?.position ?: autoGameState.aimTarget

        return merged
    }

    // ========== RENDERIZADO ==========
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (showLines) {
            drawModeIndicator(canvas)
            drawGameElements(canvas)

            if (showControls) {
                drawManualControls(canvas)
            }

            if (currentMode == AimingMode.HYBRID) {
                drawConfidenceIndicator(canvas)
            }
        }

        postInvalidateDelayed(50)
    }

    private fun drawModeIndicator(canvas: Canvas) {
        val modeText = when (currentMode) {
            AimingMode.AUTO_DETECT -> "AUTO"
            AimingMode.MANUAL_SETUP -> "MANUAL"
            AimingMode.HYBRID -> "HÍBRIDO"
        }

        val color = when (currentMode) {
            AimingMode.AUTO_DETECT -> Color.GREEN
            AimingMode.MANUAL_SETUP -> Color.BLUE
            AimingMode.HYBRID -> if (detectionConfidence >= DETECTION_CONFIDENCE_THRESHOLD) Color.GREEN else Color.YELLOW
        }

        modePaint.color = color
        canvas.drawText("Modo: $modeText", 50f, 50f, modePaint)
    }

    private fun drawGameElements(canvas: Canvas) {
        finalGameState.cueBall?.let { cueBall ->
            val paint = if (currentMode == AimingMode.MANUAL_SETUP ||
                (currentMode == AimingMode.HYBRID && showControls)) {
                manualCueBallPaint
            } else {
                cueBallPaint
            }

            canvas.drawCircle(cueBall.position.x, cueBall.position.y, cueBall.radius + 5f, paint)
        }

        finalGameState.cueBall?.let { cueBall ->
            finalGameState.aimTarget?.let { target ->
                canvas.drawLine(cueBall.position.x, cueBall.position.y, target.x, target.y, aimLinePaint)
            }
        }

        drawTrajectoryPrediction(canvas)
    }

    private fun drawManualControls(canvas: Canvas) {
        manualGameState.cueBallControl?.let { control ->
            drawControl(canvas, control, if (draggedControl == control) 1.2f else 1f)
        }

        manualGameState.aimControl?.let { control ->
            drawControl(canvas, control, if (draggedControl == control) 1.2f else 1f)
        }

        for (control in manualGameState.fineAdjustControls) {
            drawFineControl(canvas, control)
        }

        val cueBall = manualGameState.cueBallControl?.position
        val aim = manualGameState.aimControl?.position
        if (cueBall != null && aim != null) {
            canvas.drawLine(cueBall.x, cueBall.y, aim.x, aim.y, aimLinePaint)
        }
    }

    private fun drawControl(canvas: Canvas, control: ControlPoint, scale: Float = 1f) {
        val radius = CONTROL_RADIUS * scale

        canvas.drawCircle(control.position.x, control.position.y, radius, controlPaint)
        canvas.drawCircle(control.position.x, control.position.y, radius, controlBorderPaint)
        canvas.drawCircle(control.position.x, control.position.y, radius * 0.3f, controlBorderPaint)

        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 24f
            textAlign = Paint.Align.CENTER
            setShadowLayer(2f, 1f, 1f, Color.BLACK)
        }

        canvas.drawText(control.label, control.position.x, control.position.y - radius - 15f, textPaint)
    }

    private fun drawFineControl(canvas: Canvas, control: ControlPoint) {
        val size = 30f
        val rect = RectF(
            control.position.x - size, control.position.y - size,
            control.position.x + size, control.position.y + size
        )

        canvas.drawRoundRect(rect, 8f, 8f, controlPaint)
        canvas.drawRoundRect(rect, 8f, 8f, controlBorderPaint)

        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 28f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }

        canvas.drawText(control.label, control.position.x, control.position.y + 8f, textPaint)
    }

    private fun drawConfidenceIndicator(canvas: Canvas) {
        val barWidth = 200f
        val barHeight = 20f
        val x = width - barWidth - 50f
        val y = 100f

        val bgPaint = Paint().apply {
            color = Color.argb(120, 0, 0, 0)
            style = Paint.Style.FILL
        }

        val bgRect = RectF(x, y, x + barWidth, y + barHeight)
        canvas.drawRoundRect(bgRect, 10f, 10f, bgPaint)

        val confidenceWidth = barWidth * detectionConfidence
        val confidenceColor = when {
            detectionConfidence >= 0.7f -> Color.GREEN
            detectionConfidence >= 0.4f -> Color.YELLOW
            else -> Color.RED
        }

        val confidencePaint = Paint().apply {
            color = confidenceColor
            style = Paint.Style.FILL
        }

        val confidenceRect = RectF(x, y, x + confidenceWidth, y + barHeight)
        canvas.drawRoundRect(confidenceRect, 10f, 10f, confidencePaint)

        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 24f
            textAlign = Paint.Align.LEFT
            setShadowLayer(2f, 1f, 1f, Color.BLACK)
        }

        canvas.drawText("Confianza: ${(detectionConfidence * 100).toInt()}%", x, y + barHeight + 30f, textPaint)
    }

    private fun drawTrajectoryPrediction(canvas: Canvas) {
        val cueBall = finalGameState.cueBall ?: return
        val direction = finalGameState.cueDirection ?: return

        val trajectoryPoints = calculateSimpleTrajectory(cueBall, direction)

        for (i in 0 until trajectoryPoints.size - 1) {
            val alpha = (255 * (1f - i.toFloat() / trajectoryPoints.size * 0.7f)).toInt()
            val paint = Paint(trajectoryPaint).apply { this.alpha = alpha }

            canvas.drawLine(
                trajectoryPoints[i].x, trajectoryPoints[i].y,
                trajectoryPoints[i + 1].x, trajectoryPoints[i + 1].y,
                paint
            )
        }
    }

    private fun calculateSimpleTrajectory(cueBall: Ball, direction: Float): List<PointF> {
        val points = mutableListOf<PointF>()
        var currentX = cueBall.position.x
        var currentY = cueBall.position.y
        var currentVelX = cos(direction) * 400f
        var currentVelY = sin(direction) * 400f

        points.add(PointF(currentX, currentY))

        for (step in 0 until 50) {
            currentX += currentVelX * 0.02f
            currentY += currentVelY * 0.02f

            if (currentX <= 0 || currentX >= width) currentVelX = -currentVelX * 0.8f
            if (currentY <= 0 || currentY >= height) currentVelY = -currentVelY * 0.8f

            currentVelX *= 0.98f
            currentVelY *= 0.98f

            points.add(PointF(currentX, currentY))

            if (abs(currentVelX) < 10f && abs(currentVelY) < 10f) break
        }

        return points
    }

    // ========== MANEJO DE TOUCH ==========
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!showControls) return false

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val touchPoint = PointF(event.x, event.y)
                draggedControl = findTouchedControl(touchPoint)
                isDragging = draggedControl != null
                return isDragging
            }

            MotionEvent.ACTION_MOVE -> {
                if (isDragging && draggedControl != null) {
                    handleControlDrag(draggedControl!!, PointF(event.x, event.y))
                    invalidate()
                    return true
                }
            }

            MotionEvent.ACTION_UP -> {
                if (isDragging) {
                    isDragging = false
                    draggedControl = null
                    updateFinalGameState()
                    return true
                }
            }
        }

        return false
    }

    private fun findTouchedControl(touchPoint: PointF): ControlPoint? {
        manualGameState.cueBallControl?.let { control ->
            if (isPointInControl(touchPoint, control.position, CONTROL_RADIUS)) {
                return control
            }
        }

        manualGameState.aimControl?.let { control ->
            if (isPointInControl(touchPoint, control.position, CONTROL_RADIUS)) {
                return control
            }
        }

        for (control in manualGameState.fineAdjustControls) {
            if (isPointInControl(touchPoint, control.position, 30f)) {
                return control
            }
        }

        return null
    }

    private fun isPointInControl(point: PointF, controlCenter: PointF, radius: Float): Boolean {
        val distance = sqrt((point.x - controlCenter.x).pow(2) + (point.y - controlCenter.y).pow(2))
        return distance <= radius
    }

    private fun handleControlDrag(control: ControlPoint, newPosition: PointF) {
        when (control.type) {
            ControlType.CUE_BALL -> {
                manualGameState.cueBallControl?.position?.set(newPosition.x, newPosition.y)
            }
            ControlType.AIM_TARGET -> {
                manualGameState.aimControl?.position?.set(newPosition.x, newPosition.y)
            }
            ControlType.FINE_TUNE_UP -> {
                manualGameState.cueBallControl?.position?.y =
                    (manualGameState.cueBallControl?.position?.y ?: 0f) - 2f
            }
            ControlType.FINE_TUNE_DOWN -> {
                manualGameState.cueBallControl?.position?.y =
                    (manualGameState.cueBallControl?.position?.y ?: 0f) + 2f
            }
            ControlType.FINE_TUNE_LEFT -> {
                manualGameState.cueBallControl?.position?.x =
                    (manualGameState.cueBallControl?.position?.x ?: 0f) - 2f
            }
            ControlType.FINE_TUNE_RIGHT -> {
                manualGameState.cueBallControl?.position?.x =
                    (manualGameState.cueBallControl?.position?.x ?: 0f) + 2f
            }
            else -> {}
        }
    }

    // ========== GESTIÓN DE TOUCH ==========
    private fun enableTouchCapture() {
        overlayService.enableTouchForView(this)
    }

    private fun disableTouchCapture() {
        overlayService.disableTouchForView(this)
    }
}