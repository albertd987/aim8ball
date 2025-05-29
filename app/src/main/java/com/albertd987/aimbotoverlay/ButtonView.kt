package com.albertd987.aimbotoverlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

class ButtonView(context: Context, private val overlayService: OverlayService) : View(context) {

    private val buttonPaint = Paint().apply {
        color = Color.argb(180, 0, 0, 0)
        style = Paint.Style.FILL
    }

    private val buttonTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = 30f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    // Estado
    private var isActive = false
    private var showTrajectory = false

    // Botones (posiciones relativas a esta vista pequeña)
    private val buttonWidth = 100f
    private val buttonHeight = 60f
    private var mainButtonRect = RectF(10f, 10f, 110f, 70f)
    private var aimButtonRect = RectF(10f, 80f, 110f, 140f)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Dibujar botón principal
        drawButton(canvas, mainButtonRect, if (isActive) "ON" else "OFF",
            if (isActive) Color.GREEN else Color.RED)

        // Dibujar botón AIM solo si está activo
        if (isActive) {
            drawButton(canvas, aimButtonRect, "AIM",
                if (showTrajectory) Color.YELLOW else Color.BLUE)
        }

        // Redibujar cada 500ms (menos frecuente para botones)
        postInvalidateDelayed(500)
    }

    private fun drawButton(canvas: Canvas, rect: RectF, text: String, color: Int) {
        // Fondo
        buttonPaint.color = Color.argb(200, 30, 30, 30)
        canvas.drawRoundRect(rect, 15f, 15f, buttonPaint)

        // Borde
        val borderPaint = Paint().apply {
            this.color = color
            strokeWidth = 2f
            style = Paint.Style.STROKE
        }
        canvas.drawRoundRect(rect, 15f, 15f, borderPaint)

        // Texto
        buttonTextPaint.color = color
        canvas.drawText(text, rect.centerX(), rect.centerY() + 10f, buttonTextPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val x = event.x
                val y = event.y

                // Botón principal
                if (mainButtonRect.contains(x, y)) {
                    isActive = !isActive
                    if (!isActive) {
                        showTrajectory = false
                        overlayService.hideTrajectory()
                    }
                    return true
                }

                // Botón AIM (solo si está activo)
                if (isActive && aimButtonRect.contains(x, y)) {
                    showTrajectory = !showTrajectory
                    if (showTrajectory) {
                        overlayService.showTrajectory()
                    } else {
                        overlayService.hideTrajectory()
                    }
                    return true
                }
            }
        }
        return false
    }
}