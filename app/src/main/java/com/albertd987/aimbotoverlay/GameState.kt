// GameState.kt
package com.albertd987.aimbotoverlay

import android.graphics.PointF
import android.graphics.RectF

// Estado principal del juego
data class GameState(
    var tableBounds: RectF? = null,
    var cueBall: Ball? = null,
    var targetBalls: MutableList<Ball> = mutableListOf(),
    var pockets: MutableList<Pocket> = mutableListOf(),
    var cueDirection: Float? = null,
    var aimTarget: PointF? = null,
    var isPlayerTurn: Boolean = true,
    var gameMode: GameMode = GameMode.EIGHT_BALL
)

// Representación de una bola
data class Ball(
    val position: PointF,
    val radius: Float,
    val type: BallType,
    val velocity: PointF = PointF(0f, 0f),
    val isVisible: Boolean = true
)

// Representación de una tronera
data class Pocket(
    val position: PointF,
    val radius: Float,
    val name: String = "",
    val isActive: Boolean = true
)

// Tipos de bolas en el juego
enum class BallType {
    CUE,           // Bola blanca
    SOLID_1,       // Sólida 1 (Amarilla)
    SOLID_2,       // Sólida 2 (Azul)
    SOLID_3,       // Sólida 3 (Roja)
    SOLID_4,       // Sólida 4 (Púrpura)
    SOLID_5,       // Sólida 5 (Naranja)
    SOLID_6,       // Sólida 6 (Verde)
    SOLID_7,       // Sólida 7 (Marrón)
    EIGHT_BALL,    // Bola 8 (Negra)
    STRIPE_9,      // Rayada 9
    STRIPE_10,     // Rayada 10
    STRIPE_11,     // Rayada 11
    STRIPE_12,     // Rayada 12
    STRIPE_13,     // Rayada 13
    STRIPE_14,     // Rayada 14
    STRIPE_15      // Rayada 15
}

// Modos de juego soportados
enum class GameMode {
    EIGHT_BALL,    // 8 Ball Pool clásico
    NINE_BALL,     // 9 Ball
    SNOOKER,       // Snooker
    CUSTOM         // Modo personalizado
}

// Estado manual del sistema híbrido
data class ManualGameState(
    var cueBallControl: ControlPoint? = null,
    var aimControl: ControlPoint? = null,
    var fineAdjustControls: List<ControlPoint> = emptyList(),
    var isConfigured: Boolean = false
) {
    fun isFullyConfigured(): Boolean {
        return cueBallControl != null && aimControl != null && isConfigured
    }

    fun reset() {
        cueBallControl = null
        aimControl = null
        isConfigured = false
    }
}

// Punto de control manual
data class ControlPoint(
    val position: PointF,
    val type: ControlType,
    val label: String,
    var isSelected: Boolean = false,
    var isDragging: Boolean = false
)

// Tipos de controles manuales
enum class ControlType {
    CUE_BALL,           // Control de posición de bola blanca
    AIM_TARGET,         // Control de dirección de tiro
    FINE_TUNE_UP,       // Ajuste fino hacia arriba
    FINE_TUNE_DOWN,     // Ajuste fino hacia abajo
    FINE_TUNE_LEFT,     // Ajuste fino hacia izquierda
    FINE_TUNE_RIGHT,    // Ajuste fino hacia derecha
    POWER_CONTROL,      // Control de potencia
    SPIN_CONTROL        // Control de efecto
}

// Modos del sistema de puntería
enum class AimingMode {
    AUTO_DETECT,    // Solo detección automática
    MANUAL_SETUP,   // Solo controles manuales
    HYBRID         // Automático con fallback manual
}

// Datos de trayectoria
data class TrajectoryPath(
    val points: List<PointF>,
    val collisions: List<CollisionEvent>,
    val pocketPrediction: Pocket?,
    val confidence: Float = 1f,
    val ballType: BallType = BallType.CUE
)

// Evento de colisión en la trayectoria
data class CollisionEvent(
    val point: PointF,
    val type: CollisionType,
    val targetBall: Ball? = null,
    val impactAngle: Float = 0f,
    val wall: Wall? = null,
    val impactForce: Float = 0f
)

// Tipos de colisión
enum class CollisionType {
    WALL,      // Colisión con banda
    BALL,      // Colisión con otra bola
    POCKET,    // Entrada a tronera
    CUSHION    // Rebote en cojín
}

// Paredes de la mesa
enum class Wall {
    LEFT, RIGHT, TOP, BOTTOM
}

// Configuración de detección
data class DetectionConfig(
    var tableBounds: RectF? = null,
    var ballRadius: Float = 12f,
    var colorTolerance: Int = 35,
    var pocketRadius: Float = 25f,
    var confidenceThreshold: Float = 0.7f,

    // Colores del juego
    var tableColor: Int = android.graphics.Color.rgb(46, 125, 50),
    var cueBallColor: Int = android.graphics.Color.WHITE,
    var eightBallColor: Int = android.graphics.Color.BLACK,

    // Offsets de detección (aprendidos)
    var ballDetectionOffset: PointF = PointF(0f, 0f),
    var aimDetectionOffset: Float = 0f
)

// Configuración de física
data class PhysicsConfig(
    var friction: Float = 0.985f,              // Fricción de la mesa
    var restitution: Float = 0.85f,            // Coeficiente de rebote
    var initialVelocity: Float = 600f,         // Velocidad inicial
    var minVelocity: Float = 30f,              // Velocidad mínima
    var maxTrajectorySteps: Int = 200,         // Máximo pasos de simulación
    var timeStep: Float = 0.02f,               // Paso de tiempo
    var ballMass: Float = 1f,                  // Masa de las bolas
    var cushionDamping: Float = 0.1f           // Amortiguación en rebotes
)

// Resultado de calibración
data class CalibrationResult(
    var tableBounds: RectF? = null,
    var elementSizes: ElementSizes = ElementSizes(),
    var colorProfile: ColorProfile = ColorProfile(),
    var screenConfig: ScreenConfig = ScreenConfig(),
    var physicsConfig: PhysicsConfig = PhysicsConfig(),
    var confidence: Float = 0f,
    var calibrationTime: Long = System.currentTimeMillis()
)

// Tamaños de elementos detectados
data class ElementSizes(
    var ballRadius: Float = 12f,
    var pocketRadius: Float = 25f,
    var tableWidth: Float = 0f,
    var tableHeight: Float = 0f
)

// Perfil de colores calibrado
data class ColorProfile(
    var tableColor: Int = android.graphics.Color.rgb(46, 125, 50),
    var colorTolerance: Int = 35,
    var brightness: Float = 128f,
    var contrast: Float = 0.5f,
    var saturation: Float = 1f
)

// Configuración de pantalla
data class ScreenConfig(
    var width: Int = 0,
    var height: Int = 0,
    var density: Float = 1f,
    var orientation: ScreenOrientation = ScreenOrientation.LANDSCAPE,
    var aspectRatio: Float = 16f/9f
)

enum class ScreenOrientation {
    PORTRAIT, LANDSCAPE
}

// Muestra de calibración para aprendizaje
data class CalibrationSample(
    var timestamp: Long = 0,
    var tableBounds: RectF = RectF(),
    var elementSizes: ElementSizes = ElementSizes(),
    var colorProfile: ColorProfile = ColorProfile(),
    var screenConfig: ScreenConfig = ScreenConfig(),
    var confidence: Float = 0f,
    var trajectoryData: List<TrajectoryData> = emptyList(),
    var deviceInfo: String = ""
)

// Datos de trayectoria para aprendizaje
data class TrajectoryData(
    var friction: Float = 0.985f,
    var restitution: Float = 0.85f,
    var accuracy: Float = 0f,  // Qué tan precisa fue la predicción
    var success: Boolean = false  // Si el tiro fue exitoso
)

// Corrección manual para aprendizaje
data class ManualCorrection(
    var type: CorrectionType,
    var detectedPosition: PointF? = null,
    var correctedPosition: PointF? = null,
    var timestamp: Long = System.currentTimeMillis(),
    var confidence: Float = 0f
)

enum class CorrectionType {
    CUE_BALL_POSITION,    // Corrección de posición de bola blanca
    TABLE_BOUNDS,         // Corrección de límites de mesa
    AIM_DIRECTION,        // Corrección de dirección
    PHYSICS_PARAMETERS,   // Corrección de física
    BALL_DETECTION,       // Corrección de detección de bolas
    POCKET_DETECTION      // Corrección de detección de troneras
}

// Estado de confianza del sistema
data class ConfidenceState(
    var overallConfidence: Float = 0f,
    var detectionConfidence: Float = 0f,
    var trajectoryConfidence: Float = 0f,
    var physicsConfidence: Float = 0f,
    var lastUpdateTime: Long = 0
) {
    fun updateOverallConfidence() {
        overallConfidence = (detectionConfidence + trajectoryConfidence + physicsConfidence) / 3f
        lastUpdateTime = System.currentTimeMillis()
    }
}

// Interfaz para callbacks de detección
interface DetectionCallback {
    fun onGameStateDetected(gameState: GameState)
    fun onConfidenceChanged(confidence: ConfidenceState)
    fun onCalibrationNeeded()
    fun onError(error: DetectionError)
}

// Errores de detección
sealed class DetectionError(val message: String) {
    object TableNotFound : DetectionError("No se pudo detectar la mesa")
    object CueBallNotFound : DetectionError("No se pudo detectar la bola blanca")
    object LowLighting : DetectionError("Iluminación insuficiente")
    object ScreenCaptureError : DetectionError("Error en captura de pantalla")
    object CalibrationRequired : DetectionError("Calibración requerida")
    data class CustomError(val customMessage: String) : DetectionError(customMessage)
}

// Configuración del dispositivo
data class DeviceProfile(
    var screenWidth: Int = 0,
    var screenHeight: Int = 0,
    var density: Float = 1f,
    var deviceModel: String = "",
    var androidVersion: Int = 0,
    var hasGyroscope: Boolean = false,
    var hasAccelerometer: Boolean = false
)

// Línea para detección de bordes
data class Line(
    var x1: Int, var y1: Int,
    var x2: Int, var y2: Int,
    var strength: Float = 0f  // Fuerza de la línea detectada
)

// Resultado de template matching
data class TemplateMatch(
    var bounds: RectF,
    var confidence: Float,
    var scale: Float = 1f,
    var rotation: Float = 0f
)

// Muestras de color para calibración
data class ColorSamples(
    var tableRegion: List<Int> = emptyList(),
    var borderRegion: List<Int> = emptyList(),
    var ballRegion: List<Int> = emptyList(),
    var backgroundRegion: List<Int> = emptyList()
)