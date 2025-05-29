package com.albertd987.aimbotoverlay

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var buttonView: ButtonView
    private lateinit var trajectoryView: TrajectoryView
    private lateinit var trajectoryParams: WindowManager.LayoutParams

    override fun onCreate() {
        super.onCreate()

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // Vista pequeña para botones (SÍ captura touch)
        buttonView = ButtonView(this, this)
        val buttonParams = WindowManager.LayoutParams(
            250, // Ancho pequeño
            160, // Alto pequeño
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

        // Vista para líneas (configuración dinámica de touch)
        trajectoryView = TrajectoryView(this, this)
        trajectoryParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or // Inicialmente NO captura touch
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )

        windowManager.addView(buttonView, buttonParams)
        windowManager.addView(trajectoryView, trajectoryParams)
    }

    fun showTrajectory() {
        trajectoryView.showTrajectory()
        // Permitir que capture toques para setup
        enableTrajectoryTouch()
    }

    fun hideTrajectory() {
        trajectoryView.hideTrajectory()
        // Volver a modo no-touch
        disableTrajectoryTouch()
    }

    fun setupComplete() {
        // Una vez completado el setup, volver a modo no-touch
        disableTrajectoryTouch()
    }

    private fun enableTrajectoryTouch() {
        // Remover y re-agregar con flags que permiten touch
        windowManager.removeView(trajectoryView)
        trajectoryParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        windowManager.addView(trajectoryView, trajectoryParams)
    }

    private fun disableTrajectoryTouch() {
        // Remover y re-agregar con flags que NO permiten touch
        windowManager.removeView(trajectoryView)
        trajectoryParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        windowManager.addView(trajectoryView, trajectoryParams)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::buttonView.isInitialized) {
            windowManager.removeView(buttonView)
        }
        if (::trajectoryView.isInitialized) {
            windowManager.removeView(trajectoryView)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}