// EnhancedMainActivity.kt
package com.albertd987.aimbotoverlay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class EnhancedMainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_OVERLAY_PERMISSION = 1234
        private const val REQUEST_SCREEN_CAPTURE = 5678
        private const val PREFS_NAME = "AimbotSettings"
        private const val KEY_FIRST_RUN = "first_run"
        private const val KEY_TUTORIAL_SHOWN = "tutorial_shown"
    }

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    private var overlayServiceIntent: Intent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        overlayServiceIntent = Intent(this, EnhancedOverlayService::class.java)

        // Verificar si es la primera ejecución
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isFirstRun = prefs.getBoolean(KEY_FIRST_RUN, true)

        if (isFirstRun) {
            showWelcomeDialog()
        } else {
            checkAndRequestPermissions()
        }
    }

    private fun showWelcomeDialog() {
        AlertDialog.Builder(this)
            .setTitle("🎯 Aimbot para 8 Ball Pool")
            .setMessage("""
                ¡Bienvenido al asistente de puntería inteligente!
                
                Características:
                • Detección automática de elementos del juego
                • Predicción de trayectorias avanzada  
                • Sistema híbrido automático/manual
                • Calibración inteligente adaptativa
                
                Para funcionar necesita permisos de:
                • Overlay (mostrar encima de otras apps)
                • Captura de pantalla (analizar el juego)
                
                ¿Continuar con la configuración?
            """.trimIndent())
            .setPositiveButton("Continuar") { _, _ ->
                markFirstRunComplete()
                checkAndRequestPermissions()
            }
            .setNegativeButton("Cancelar") { _, _ ->
                finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun markFirstRunComplete() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_FIRST_RUN, false)
            .apply()
    }

    private fun checkAndRequestPermissions() {
        when {
            !Settings.canDrawOverlays(this) -> {
                requestOverlayPermission()
            }
            mediaProjection == null -> {
                requestScreenCapturePermission()
            }
            else -> {
                startAimbotService()
            }
        }
    }

    private fun requestOverlayPermission() {
        AlertDialog.Builder(this)
            .setTitle("Permiso de Overlay")
            .setMessage("""
                El aimbot necesita permiso para mostrarse encima de otras aplicaciones.
                
                En la siguiente pantalla:
                1. Busca "${getString(R.string.app_name)}"
                2. Activa "Permitir mostrar encima de otras apps"
                3. Vuelve a esta aplicación
            """.trimIndent())
            .setPositiveButton("Abrir Configuración") { _, _ ->
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION)
            }
            .setNegativeButton("Cancelar") { _, _ ->
                showPermissionDeniedDialog("overlay")
            }
            .show()
    }

    private fun requestScreenCapturePermission() {
        AlertDialog.Builder(this)
            .setTitle("Permiso de Captura de Pantalla")
            .setMessage("""
                El aimbot necesita capturar la pantalla para detectar automáticamente:
                • Posición de la bola blanca
                • Ubicación de las bolas objetivo
                • Límites de la mesa de juego
                • Dirección del taco
                
                Este permiso es necesario para el funcionamiento automático.
            """.trimIndent())
            .setPositiveButton("Conceder Permiso") { _, _ ->
                val intent = mediaProjectionManager.createScreenCaptureIntent()
                startActivityForResult(intent, REQUEST_SCREEN_CAPTURE)
            }
            .setNegativeButton("Solo Modo Manual") { _, _ ->
                startAimbotServiceManualOnly()
            }
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            REQUEST_OVERLAY_PERMISSION -> {
                if (Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "✅ Permiso de overlay concedido", Toast.LENGTH_SHORT).show()
                    checkAndRequestPermissions() // Continuar con siguiente permiso
                } else {
                    showPermissionDeniedDialog("overlay")
                }
            }

            REQUEST_SCREEN_CAPTURE -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
                    Toast.makeText(this, "✅ Permiso de captura concedido", Toast.LENGTH_SHORT).show()
                    startAimbotService()
                } else {
                    showScreenCapturePermissionDialog()
                }
            }
        }
    }

    private fun showPermissionDeniedDialog(permissionType: String) {
        val message = when (permissionType) {
            "overlay" -> """
                Sin el permiso de overlay, el aimbot no puede funcionar.
                
                Puedes:
                • Intentar de nuevo
                • Conceder manualmente en Configuración > Apps > Permisos especiales
            """.trimIndent()
            else -> "Permiso requerido para el funcionamiento."
        }

        AlertDialog.Builder(this)
            .setTitle("Permiso Denegado")
            .setMessage(message)
            .setPositiveButton("Reintentar") { _, _ ->
                checkAndRequestPermissions()
            }
            .setNegativeButton("Salir") { _, _ ->
                finish()
            }
            .show()
    }

    private fun showScreenCapturePermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle("Captura de Pantalla Denegada")
            .setMessage("""
                Sin captura de pantalla solo funcionará el modo manual.
                
                Opciones:
                • Reintentar para modo completo (automático + manual)
                • Continuar solo con controles manuales
                • Salir de la aplicación
            """.trimIndent())
            .setPositiveButton("Reintentar") { _, _ ->
                requestScreenCapturePermission()
            }
            .setNeutralButton("Solo Manual") { _, _ ->
                startAimbotServiceManualOnly()
            }
            .setNegativeButton("Salir") { _, _ ->
                finish()
            }
            .show()
    }

    private fun startAimbotService() {
        try {
            // Iniciar servicio con todos los permisos
            startService(overlayServiceIntent)

            // Pasar MediaProjection al servicio
            mediaProjection?.let { projection ->
                // En una implementación real, usarías un Binder o Intent extra
                // Para simplicidad, asumimos que el servicio puede acceder al projection
                passMediaProjectionToService(projection)
            }

            showSuccessDialog(hasScreenCapture = true)

        } catch (e: Exception) {
            showErrorDialog("Error al iniciar el servicio: ${e.message}")
        }
    }

    private fun startAimbotServiceManualOnly() {
        try {
            // Iniciar servicio solo con overlay (modo manual únicamente)
            startService(overlayServiceIntent)
            showSuccessDialog(hasScreenCapture = false)

        } catch (e: Exception) {
            showErrorDialog("Error al iniciar el servicio: ${e.message}")
        }
    }

    private fun passMediaProjectionToService(projection: MediaProjection) {
        // En una implementación real, usarías un mecanismo como:
        // 1. Binder service para pasar objetos complejos
        // 2. Singleton pattern para compartir el MediaProjection
        // 3. Intent con extras serializables

        // Por simplicidad, aquí usaremos un singleton pattern
        MediaProjectionHolder.setProjection(projection)
    }

    private fun showSuccessDialog(hasScreenCapture: Boolean) {
        val message = if (hasScreenCapture) {
            """
                🎉 ¡Aimbot iniciado correctamente!
                
                Características habilitadas:
                ✅ Detección automática de elementos
                ✅ Predicción de trayectorias
                ✅ Sistema híbrido inteligente
                ✅ Controles manuales de respaldo
                
                Busca los controles en la esquina superior derecha de la pantalla.
            """.trimIndent()
        } else {
            """
                ⚠️ Aimbot iniciado en modo manual
                
                Características disponibles:
                ✅ Controles manuales de puntería  
                ✅ Predicción de trayectorias
                ❌ Detección automática (sin captura)
                
                Para habilitar detección automática, reinicia y concede permisos completos.
            """.trimIndent()
        }

        AlertDialog.Builder(this)
            .setTitle("Aimbot Iniciado")
            .setMessage(message)
            .setPositiveButton("Entendido") { _, _ ->
                showTutorialIfNeeded()
            }
            .setNegativeButton("Detener") { _, _ ->
                stopService(overlayServiceIntent)
                finish()
            }
            .show()
    }

    private fun showTutorialIfNeeded() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val tutorialShown = prefs.getBoolean(KEY_TUTORIAL_SHOWN, false)

        if (!tutorialShown) {
            showTutorial()
            prefs.edit().putBoolean(KEY_TUTORIAL_SHOWN, true).apply()
        } else {
            finish() // Tutorial ya mostrado, cerrar MainActivity
        }
    }

    private fun showTutorial() {
        AlertDialog.Builder(this)
            .setTitle("📚 Tutorial Rápido")
            .setMessage("""
                Controles del Aimbot:
                
                🔘 ON/OFF - Activar/desactivar sistema
                🔄 MODO - Cambiar entre AUTO/MANUAL/HÍBRIDO
                👁️ DETECT - Iniciar/parar detección automática  
                🎯 AIM - Mostrar/ocultar trayectorias
                ⚙️ CAL - Calibración automática
                
                Modos de operación:
                • AUTO: Detección completamente automática
                • MANUAL: Controles tradicionales arrastrables
                • HÍBRIDO: Cambia automáticamente según confianza
                
                Consejos:
                • Usa HÍBRIDO para mejores resultados
                • Calibra si la detección falla
                • Los controles aparecen en la esquina superior derecha
            """.trimIndent())
            .setPositiveButton("¡Entendido!") { _, _ ->
                finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun showErrorDialog(message: String) {
        AlertDialog.Builder(this)
            .setTitle("Error")
            .setMessage(message)
            .setPositiveButton("Reintentar") { _, _ ->
                checkAndRequestPermissions()
            }
            .setNegativeButton("Salir") { _, _ ->
                finish()
            }
            .show()
    }

    override fun onResume() {
        super.onResume()

        // Verificar si los permisos siguen activos
        if (!Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle("Permiso Revocado")
                .setMessage("El permiso de overlay fue revocado. El aimbot necesita este permiso para funcionar.")
                .setPositiveButton("Reconfigurar") { _, _ ->
                    checkAndRequestPermissions()
                }
                .setNegativeButton("Salir") { _, _ ->
                    finish()
                }
                .show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        // Limpiar MediaProjection si no se está usando
        mediaProjection?.stop()
        mediaProjection = null
    }
}

// Singleton para compartir MediaProjection entre componentes
object MediaProjectionHolder {
    private var mediaProjection: MediaProjection? = null

    fun setProjection(projection: MediaProjection) {
        mediaProjection?.stop() // Limpiar anterior si existe
        mediaProjection = projection
    }

    fun getProjection(): MediaProjection? = mediaProjection

    fun clearProjection() {
        mediaProjection?.stop()
        mediaProjection = null
    }
}

// Actividad de configuración avanzada (opcional)
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Aquí podrías implementar una interfaz de configuración avanzada
        // con opciones como:
        // - Sensibilidad de detección
        // - Parámetros de física
        // - Colores personalizados
        // - Configuraciones por dispositivo

        AlertDialog.Builder(this)
            .setTitle("⚙️ Configuración Avanzada")
            .setMessage("""
                Configuraciones disponibles:
                
                🎯 Sensibilidad de detección
                ⚡ Parámetros de física del juego
                🎨 Calibración de colores
                📱 Optimización por dispositivo
                🔄 Restablecer configuración
                
                (Funcionalidad en desarrollo)
            """.trimIndent())
            .setPositiveButton("Cerrar") { _, _ ->
                finish()
            }
            .show()
    }
}