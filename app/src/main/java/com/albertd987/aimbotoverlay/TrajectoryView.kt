package com.albertd987.aimbotoverlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

class TrajectoryView(context: Context, private val overlayService: OverlayService) : View(context) {

    private val cueBallPaint = Paint().apply {
        color = Color.WHITE
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }

    private val aimLinePaint = Paint().apply {
        color = Color.YELLOW
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    private val trajectoryPaint = Paint().apply {
        color = Color.RED
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }

    private val targetBallPaint = Paint().apply {
        color = Color.CYAN
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    private val pocketPaint = Paint().apply {
        color = Color.GREEN
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    // Estado del sistema
    private var showLines = false
    private var setupMode = false
    private var setupComplete = false

    // Posiciones
    private var cueBallX = 0f
    private var cueBallY = 0f
    private var aimX = 0f
    private var aimY = 0f
    private var hasCueBall = false
    private var hasAim = false

    // Configuración de mesa (basada en proporciones típicas de 8-ball pool)
    private var tableLeft = 0f
    private var tableRight = 0f
    private var tableTop = 0f
    private var tableBottom = 0f
    private val pockets = mutableListOf<Pocket>()

    // Física de billar
    private val ballRadius = 12f
    private val friction = 0.985f
    private val minSpeed = 30f

    fun showTrajectory() {
        showLines = true
        setupMode = true
        setupComplete = false
        hasCueBall = false
        hasAim = false
        setupTable()
    }

    fun hideTrajectory() {
        showLines = false
        setupMode = false
        setupComplete = false
        hasCueBall = false
        hasAim = false
    }

    private fun setupTable() {
        val screenWidth = resources.displayMetrics.widthPixels.toFloat()
        val screenHeight = resources.displayMetrics.heightPixels.toFloat()

        // Configurar límites de mesa (aproximados para 8 Ball Pool móvil)
        tableLeft = screenWidth * 0.05f
        tableRight = screenWidth * 0.95f
        tableTop = screenHeight * 0.25f
        tableBottom = screenHeight * 0.75f

        // Configurar troneras (6 troneras típicas del billar)
        pockets.clear()
        pockets.add(Pocket(tableLeft, tableTop, "Esquina Superior Izquierda"))
        pockets.add(Pocket(screenWidth * 0.5f, tableTop, "Banda Superior"))
        pockets.add(Pocket(tableRight, tableTop, "Esquina Superior Derecha"))
        pockets.add(Pocket(tableLeft, tableBottom, "Esquina Inferior Izquierda"))
        pockets.add(Pocket(screenWidth * 0.5f, tableBottom, "Banda Inferior"))
        pockets.add(Pocket(tableRight, tableBottom, "Esquina Inferior Derecha"))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (!showLines) return

        // Configurar mesa si aún no está hecho
        if (pockets.isEmpty()) {
            setupTable()
        }

        // Dibujar límites de mesa (para referencia)
        drawTable(canvas)

        // Dibujar troneras
        drawPockets(canvas)

        if (setupMode && !setupComplete) {
            // Modo configuración
            drawSetupInstructions(canvas)

            if (hasCueBall) {
                // Dibujar bola blanca
                canvas.drawCircle(cueBallX, cueBallY, ballRadius, cueBallPaint)

                if (hasAim) {
                    // Dibujar línea de puntería
                    canvas.drawLine(cueBallX, cueBallY, aimX, aimY, aimLinePaint)

                    // Setup completo - calcular trayectorias
                    if (!setupComplete) {
                        setupComplete = true
                        // Informar al servicio que puede volver a modo no-touch
                        post { overlayService.setupComplete() }
                    }
                }
            }
        }

        if (setupComplete) {
            // Dibujar bola blanca
            canvas.drawCircle(cueBallX, cueBallY, ballRadius, cueBallPaint)
            // Dibujar línea de puntería
            canvas.drawLine(cueBallX, cueBallY, aimX, aimY, aimLinePaint)
            // Calcular y dibujar trayectorias
            calculateAndDrawTrajectories(canvas)
        }

        postInvalidateDelayed(50)
    }

    private fun drawTable(canvas: Canvas) {
        val tablePaint = Paint().apply {
            color = Color.argb(80, 255, 255, 255)
            strokeWidth = 2f
            style = Paint.Style.STROKE
        }
        canvas.drawRect(tableLeft, tableTop, tableRight, tableBottom, tablePaint)
    }

    private fun drawPockets(canvas: Canvas) {
        for (pocket in pockets) {
            canvas.drawCircle(pocket.x, pocket.y, 20f, pocketPaint)
        }
    }

    private fun drawSetupInstructions(canvas: Canvas) {
        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 36f
            isAntiAlias = true
            setShadowLayer(4f, 2f, 2f, Color.BLACK)
        }

        when {
            !hasCueBall -> {
                canvas.drawText("1. Toca donde está la bola blanca", 50f, 150f, textPaint)
            }
            !hasAim -> {
                canvas.drawText("2. Toca hacia dónde apuntas", 50f, 150f, textPaint)
            }
        }
    }

    private fun calculateAndDrawTrajectories(canvas: Canvas) {
        // Calcular dirección de la bola blanca
        val deltaX = aimX - cueBallX
        val deltaY = aimY - cueBallY
        val distance = sqrt(deltaX * deltaX + deltaY * deltaY)

        if (distance < 10f) return

        // Normalizar dirección
        val dirX = deltaX / distance
        val dirY = deltaY / distance

        // Simular trayectoria de la bola blanca
        var currentX = cueBallX
        var currentY = cueBallY
        var velocityX = dirX * 400f // Velocidad inicial
        var velocityY = dirY * 400f
        var speed = 400f

        val trajectoryPoints = mutableListOf<Pair<Float, Float>>()
        trajectoryPoints.add(Pair(currentX, currentY))

        // Simular movimiento con fricción y rebotes
        for (step in 0 until 150) {
            if (speed < minSpeed) break

            // Paso de simulación
            val timeStep = 0.015f
            currentX += velocityX * timeStep
            currentY += velocityY * timeStep

            // Verificar colisiones con bandas
            var bounced = false
            if (currentX <= tableLeft + ballRadius || currentX >= tableRight - ballRadius) {
                velocityX = -velocityX * 0.85f // Rebote con pérdida de energía
                currentX = if (currentX <= tableLeft + ballRadius) tableLeft + ballRadius else tableRight - ballRadius
                bounced = true
            }

            if (currentY <= tableTop + ballRadius || currentY >= tableBottom - ballRadius) {
                velocityY = -velocityY * 0.85f
                currentY = if (currentY <= tableTop + ballRadius) tableTop + ballRadius else tableBottom - ballRadius
                bounced = true
            }

            // Verificar si está cerca de una tronera
            for (pocket in pockets) {
                val distToPocket = sqrt((currentX - pocket.x).pow(2) + (currentY - pocket.y).pow(2))
                if (distToPocket < 30f) {
                    drawPocketText(canvas, pocket, "¡TRONERA!")
                    trajectoryPoints.add(Pair(pocket.x, pocket.y))
                    drawTrajectoryPath(canvas, trajectoryPoints)
                    return
                }
            }

            // Aplicar fricción
            if (!bounced) {
                speed *= friction
                velocityX *= friction
                velocityY *= friction
            } else {
                speed *= 0.95f // Pérdida adicional en rebotes
            }

            trajectoryPoints.add(Pair(currentX, currentY))
        }

        // Dibujar trayectoria
        drawTrajectoryPath(canvas, trajectoryPoints)
    }

    private fun drawTrajectoryPath(canvas: Canvas, points: List<Pair<Float, Float>>) {
        for (i in 0 until points.size - 1) {
            val alpha = (255 * (1f - i.toFloat() / points.size * 0.7f)).toInt().coerceIn(80, 255)
            val pathPaint = Paint().apply {
                color = Color.argb(alpha, 255, 50, 50)
                strokeWidth = if (i < points.size / 3) 4f else 2f
                style = Paint.Style.STROKE
            }

            canvas.drawLine(
                points[i].first, points[i].second,
                points[i + 1].first, points[i + 1].second,
                pathPaint
            )
        }
    }

    private fun drawPocketText(canvas: Canvas, pocket: Pocket, text: String) {
        val textPaint = Paint().apply {
            color = Color.GREEN
            textSize = 28f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            setShadowLayer(3f, 1f, 1f, Color.BLACK)
        }
        canvas.drawText(text, pocket.x, pocket.y - 35f, textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!setupMode || setupComplete) return false

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val x = event.x
                val y = event.y

                if (!hasCueBall) {
                    cueBallX = x
                    cueBallY = y
                    hasCueBall = true
                    return true
                } else if (!hasAim) {
                    aimX = x
                    aimY = y
                    hasAim = true
                    return true
                }
            }
        }
        return false
    }

    data class Pocket(val x: Float, val y: Float, val name: String)
}
