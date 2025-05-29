package com.albertd987.aimbotoverlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

class DrawView(context: Context) : View(context) {

    // Paints para dibujar
    private val trajectoryPaint = Paint().apply {
        color = Color.RED
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }

    private val fadedPaint = Paint().apply {
        color = Color.argb(100, 255, 0, 0)
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val buttonPaint = Paint().apply {
        color = Color.argb(180, 0, 0, 0) // Fondo semi-transparente
        style = Paint.Style.FILL
    }

    private val buttonTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = 40f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    private val targetPaint = Paint().apply {
        color = Color.YELLOW
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    // Estado del overlay
    private var isActive = false
    private var showTrajectory = false

    // Configuración de trayectoria
    private var startX = 500f
    private var startY = 1200f
    private var targetX = 800f
    private var targetY = 800f
    private var initialSpeed = 800f

    // Física de billar
    private val friction = 0.98f
    private val minSpeed = 50f
    private val maxSegments = 8

    // UI elementos
    private val buttonSize = 120f
    private val buttonMargin = 50f
    private var buttonRect = RectF()
    private var powerButtonRect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Configurar posiciones de botones
        setupButtons(canvas.width.toFloat(), canvas.height.toFloat())

        // Dibujar botón principal (ON/OFF)
        drawButton(canvas, buttonRect, if (isActive) "ON" else "OFF",
            if (isActive) Color.GREEN else Color.RED)

        // Dibujar botón de potencia solo si está activo
        if (isActive) {
            drawButton(canvas, powerButtonRect, "AIM", Color.BLUE)

            // Dibujar punto de origen (bola blanca fija para pruebas)
            canvas.drawCircle(startX, startY, 15f, targetPaint)

            // Si está en modo aim, dibujar trayectoria fija
            if (showTrajectory) {
                // Calcular y dibujar trayectoria
                val trajectory = calculateTrajectoryFixed(canvas.width.toFloat(), canvas.height.toFloat())
                drawTrajectory(canvas, trajectory)
            }
        }

        // Redibujar cada 100ms
        postInvalidateDelayed(100)
    }

    private fun setupButtons(screenWidth: Float, screenHeight: Float) {
        // Botón principal (esquina superior derecha)
        buttonRect.set(
            screenWidth - buttonSize - buttonMargin,
            buttonMargin,
            screenWidth - buttonMargin,
            buttonMargin + buttonSize
        )

        // Botón de aim (debajo del principal)
        powerButtonRect.set(
            screenWidth - buttonSize - buttonMargin,
            buttonMargin + buttonSize + 20f,
            screenWidth - buttonMargin,
            buttonMargin + buttonSize * 2 + 20f
        )
    }

    private fun drawButton(canvas: Canvas, rect: RectF, text: String, color: Int) {
        // Fondo del botón
        buttonPaint.color = Color.argb(180, 50, 50, 50)
        canvas.drawRoundRect(rect, 20f, 20f, buttonPaint)

        // Borde del botón
        val borderPaint = Paint().apply {
            this.color = color  // Usar 'this.color' para referirse a la propiedad del Paint
            strokeWidth = 3f
            style = Paint.Style.STROKE
        }
        canvas.drawRoundRect(rect, 20f, 20f, borderPaint)

        // Texto del botón
        buttonTextPaint.color = color
        canvas.drawText(text, rect.centerX(), rect.centerY() + 15f, buttonTextPaint)
    }

    private fun drawTrajectory(canvas: Canvas, trajectory: List<TrajectorySegment>) {
        for (i in trajectory.indices) {
            val segment = trajectory[i]

            // Usar paint normal para primeros segmentos, desvanecido para los últimos
            val currentPaint = if (i < 3) trajectoryPaint else fadedPaint

            canvas.drawLine(
                segment.startX, segment.startY,
                segment.endX, segment.endY,
                currentPaint
            )

            // Punto de impacto
            if (i < trajectory.size - 1) {
                canvas.drawCircle(segment.endX, segment.endY, 6f, currentPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val x = event.x
                val y = event.y

                // SOLO capturar touch si toca específicamente los botones
                if (buttonRect.contains(x, y)) {
                    isActive = !isActive
                    if (!isActive) {
                        showTrajectory = false
                    }
                    return true // Solo devolver true para botones
                }

                if (isActive && powerButtonRect.contains(x, y)) {
                    showTrajectory = !showTrajectory
                    return true // Solo devolver true para botones
                }

                // IMPORTANTE: Para cualquier otro toque, devolver false inmediatamente
                // Esto permite que el toque pase al juego
            }
        }

        // CRÍTICO: Siempre devolver false para permitir que los toques lleguen al juego
        return false
    }

    private fun calculateTrajectoryFixed(screenWidth: Float, screenHeight: Float): List<TrajectorySegment> {
        val segments = mutableListOf<TrajectorySegment>()

        // Ángulo fijo de 45 grados para pruebas
        val angle = Math.toRadians(45.0)

        var currentX = startX
        var currentY = startY
        var currentSpeed = initialSpeed

        var velocityX = cos(angle).toFloat() * currentSpeed
        var velocityY = sin(angle).toFloat() * currentSpeed

        for (i in 0 until maxSegments) {
            if (currentSpeed < minSpeed) break

            val collision = findNextCollision(
                currentX, currentY,
                velocityX, velocityY,
                screenWidth, screenHeight
            )

            segments.add(TrajectorySegment(
                currentX, currentY,
                collision.x, collision.y,
                currentSpeed
            ))

            currentX = collision.x
            currentY = collision.y

            when (collision.wall) {
                Wall.LEFT, Wall.RIGHT -> velocityX = -velocityX
                Wall.TOP, Wall.BOTTOM -> velocityY = -velocityY
            }

            currentSpeed *= friction
            val speedRatio = currentSpeed / sqrt(velocityX * velocityX + velocityY * velocityY)
            velocityX *= speedRatio
            velocityY *= speedRatio
        }

        return segments
    }

    private fun calculateTrajectory(screenWidth: Float, screenHeight: Float): List<TrajectorySegment> {
        val segments = mutableListOf<TrajectorySegment>()

        // Calcular ángulo basado en objetivo
        val deltaX = targetX - startX
        val deltaY = targetY - startY
        val angle = atan2(deltaY, deltaX)

        var currentX = startX
        var currentY = startY
        var currentSpeed = initialSpeed

        // Calcular componentes de velocidad iniciales
        var velocityX = cos(angle) * currentSpeed
        var velocityY = sin(angle) * currentSpeed

        for (i in 0 until maxSegments) {
            if (currentSpeed < minSpeed) break

            val collision = findNextCollision(
                currentX, currentY,
                velocityX, velocityY,
                screenWidth, screenHeight
            )

            segments.add(TrajectorySegment(
                currentX, currentY,
                collision.x, collision.y,
                currentSpeed
            ))

            currentX = collision.x
            currentY = collision.y

            // Rebote
            when (collision.wall) {
                Wall.LEFT, Wall.RIGHT -> velocityX = -velocityX
                Wall.TOP, Wall.BOTTOM -> velocityY = -velocityY
            }

            // Aplicar fricción
            currentSpeed *= friction
            val speedRatio = currentSpeed / sqrt(velocityX * velocityX + velocityY * velocityY)
            velocityX *= speedRatio
            velocityY *= speedRatio
        }

        return segments
    }

    private fun findNextCollision(
        x: Float, y: Float,
        vx: Float, vy: Float,
        screenWidth: Float, screenHeight: Float
    ): Collision {

        val timeToLeft = if (vx < 0) -x / vx else Float.MAX_VALUE
        val timeToRight = if (vx > 0) (screenWidth - x) / vx else Float.MAX_VALUE
        val timeToTop = if (vy < 0) -y / vy else Float.MAX_VALUE
        val timeToBottom = if (vy > 0) (screenHeight - y) / vy else Float.MAX_VALUE

        val minTime = minOf(timeToLeft, timeToRight, timeToTop, timeToBottom)

        val collisionX = x + vx * minTime
        val collisionY = y + vy * minTime

        val wall = when (minTime) {
            timeToLeft -> Wall.LEFT
            timeToRight -> Wall.RIGHT
            timeToTop -> Wall.TOP
            else -> Wall.BOTTOM
        }

        val finalX = collisionX.coerceIn(0f, screenWidth)
        val finalY = collisionY.coerceIn(0f, screenHeight)

        return Collision(finalX, finalY, wall)
    }

    data class TrajectorySegment(
        val startX: Float,
        val startY: Float,
        val endX: Float,
        val endY: Float,
        val speed: Float
    )

    data class Collision(
        val x: Float,
        val y: Float,
        val wall: Wall
    )

    enum class Wall {
        LEFT, RIGHT, TOP, BOTTOM
    }
}