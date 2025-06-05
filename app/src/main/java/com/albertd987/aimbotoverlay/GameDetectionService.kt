// GameDetectionService.kt
package com.albertd987.aimbotoverlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.*
import kotlin.math.*

class GameDetectionService : Service() {

    companion object {
        private const val TAG = "GameDetection"
        private const val DETECTION_INTERVAL = 100L // 10 FPS
        private const val MAX_DETECTION_FAILURES = 5
    }

    // Componentes de captura
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    // Control de detección
    private val detectionHandler = Handler(Looper.getMainLooper())
    private val detectionScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var isDetecting = false
    private var detectionCallback: DetectionCallback? = null

    // Estado y configuración
    private var currentGameState = GameState()
    private var detectionConfig = DetectionConfig()
    private var failureCount = 0
    private var lastFrameTime = 0L
    private var currentFrame: Bitmap? = null

    // Cache para optimización
    private val colorCache = mutableMapOf<Int, Boolean>()
    private var lastTableBounds: RectF? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "GameDetectionService created")
    }

    fun startDetection(projection: MediaProjection, callback: DetectionCallback) {
        if (isDetecting) {
            Log.w(TAG, "Detection already running")
            return
        }

        this.mediaProjection = projection
        this.detectionCallback = callback

        try {
            setupScreenCapture()
            startDetectionLoop()
            isDetecting = true
            Log.d(TAG, "Detection started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start detection", e)
            callback.onError(DetectionError.ScreenCaptureError)
        }
    }

    private fun setupScreenCapture() {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        // Crear ImageReader para capturar frames
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        // Crear VirtualDisplay
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "GameDetection",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null, null
        )

        // Configurar listener para frames
        imageReader?.setOnImageAvailableListener({ reader ->
            detectionScope.launch {
                processNewFrame(reader)
            }
        }, detectionHandler)
    }

    private fun startDetectionLoop() {
        detectionHandler.post(object : Runnable {
            override fun run() {
                if (isDetecting) {
                    // El procesamiento real se hace en el callback del ImageReader
                    // Aquí solo programamos el siguiente ciclo
                    detectionHandler.postDelayed(this, DETECTION_INTERVAL)
                }
            }
        })
    }

    private suspend fun processNewFrame(reader: ImageReader) {
        try {
            val image = reader.acquireLatestImage()
            image?.use { img ->
                val bitmap = imageToBitmap(img)
                bitmap?.let { bmp ->
                    currentFrame = bmp.copy(bmp.config, false) // Guardar copia para calibración

                    val newGameState = analyzeGameFrame(bmp)

                    withContext(Dispatchers.Main) {
                        handleDetectionResult(newGameState)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing frame", e)
            handleDetectionFailure()
        }
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        return try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val bitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            // Si hay padding, recortar al tamaño correcto
            if (rowPadding == 0) {
                bitmap
            } else {
                Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error converting image to bitmap", e)
            null
        }
    }

    private suspend fun analyzeGameFrame(bitmap: Bitmap): GameState {
        val newState = GameState()

        try {
            // 1. Detectar área de la mesa (cache si es estable)
            newState.tableBounds = detectTableBounds(bitmap)

            // 2. Solo continuar si encontramos la mesa
            newState.tableBounds?.let { bounds ->

                // 3. Detectar bola blanca
                newState.cueBall = detectCueBall(bitmap, bounds)

                // 4. Detectar bolas objetivo
                newState.targetBalls = detectTargetBalls(bitmap, bounds).toMutableList()

                // 5. Detectar dirección del taco
                newState.cueBall?.let { cueBall ->
                    val (direction, target) = detectCueDirection(bitmap, bounds, cueBall)
                    newState.cueDirection = direction
                    newState.aimTarget = target
                }

                // 6. Detectar troneras (cache si es estable)
                if (newState.pockets.isEmpty()) {
                    newState.pockets = detectPockets(bitmap, bounds).toMutableList()
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing frame", e)
        }

        return newState
    }

    private fun detectTableBounds(bitmap: Bitmap): RectF? {
        // Usar cache si la mesa no se ha movido
        lastTableBounds?.let { cached ->
            if (isTableBoundsStillValid(bitmap, cached)) {
                return cached
            }
        }

        val tableColor = detectionConfig.tableColor
        val tolerance = detectionConfig.colorTolerance

        // Buscar píxeles verdes (mesa) con muestreo optimizado
        val greenPoints = mutableListOf<Point>()

        // Muestrear cada 8 píxeles para velocidad
        for (y in 0 until bitmap.height step 8) {
            for (x in 0 until bitmap.width step 8) {
                val pixel = bitmap.getPixel(x, y)
                if (isColorSimilar(pixel, tableColor, tolerance)) {
                    greenPoints.add(Point(x, y))
                }
            }
        }

        if (greenPoints.size < 100) return null // Muy pocos puntos verdes

        // Encontrar rectángulo delimitador
        val minX = greenPoints.minOf { it.x }.toFloat()
        val maxX = greenPoints.maxOf { it.x }.toFloat()
        val minY = greenPoints.minOf { it.y }.toFloat()
        val maxY = greenPoints.maxOf { it.y }.toFloat()

        // Validar que el rectángulo tenga sentido
        val width = maxX - minX
        val height = maxY - minY
        val area = width * height
        val screenArea = bitmap.width * bitmap.height

        if (area / screenArea < 0.2f || area / screenArea > 0.8f) {
            return null // Área demasiado pequeña o grande
        }

        val bounds = RectF(minX, minY, maxX, maxY)
        lastTableBounds = bounds
        return bounds
    }

    private fun isTableBoundsStillValid(bitmap: Bitmap, bounds: RectF): Boolean {
        // Verificar algunos puntos clave para ver si la mesa sigue ahí
        val samplePoints = listOf(
            PointF(bounds.centerX(), bounds.centerY()),
            PointF(bounds.left + bounds.width() * 0.25f, bounds.top + bounds.height() * 0.25f),
            PointF(bounds.right - bounds.width() * 0.25f, bounds.bottom - bounds.height() * 0.25f)
        )

        var validPoints = 0
        for (point in samplePoints) {
            if (point.x >= 0 && point.x < bitmap.width && point.y >= 0 && point.y < bitmap.height) {
                val pixel = bitmap.getPixel(point.x.toInt(), point.y.toInt())
                if (isColorSimilar(pixel, detectionConfig.tableColor, detectionConfig.colorTolerance)) {
                    validPoints++
                }
            }
        }

        return validPoints >= 2 // Al menos 2 de 3 puntos siguen siendo verdes
    }

    private fun detectCueBall(bitmap: Bitmap, tableBounds: RectF): Ball? {
        val whiteColor = detectionConfig.cueBallColor
        val tolerance = 30

        val whitePoints = findColorPointsInRegion(bitmap, tableBounds, whiteColor, tolerance)

        if (whitePoints.size < 20) return null

        // Agrupar puntos blancos
        val clusters = clusterPoints(whitePoints, 15f)

        // Encontrar el cluster más circular y del tamaño correcto
        for (cluster in clusters) {
            if (cluster.size < 15) continue

            val center = calculateClusterCenter(cluster)
            val avgRadius = calculateAverageRadius(cluster, center)
            val circularity = calculateCircularity(cluster, center, avgRadius)

            // Validar que sea una bola (circular y tamaño correcto)
            if (circularity > 0.7f && avgRadius in 8f..25f) {
                return Ball(
                    position = center,
                    radius = avgRadius,
                    type = BallType.CUE
                )
            }
        }

        return null
    }

    private fun detectTargetBalls(bitmap: Bitmap, tableBounds: RectF): List<Ball> {
        val balls = mutableListOf<Ball>()

        // Colores de bolas conocidos
        val ballColors = mapOf(
            BallType.SOLID_1 to Color.rgb(255, 235, 59),    // Amarillo
            BallType.SOLID_2 to Color.rgb(33, 150, 243),    // Azul
            BallType.SOLID_3 to Color.rgb(244, 67, 54),     // Rojo
            BallType.SOLID_4 to Color.rgb(156, 39, 176),    // Púrpura
            BallType.SOLID_5 to Color.rgb(255, 152, 0),     // Naranja
            BallType.SOLID_6 to Color.rgb(76, 175, 80),     // Verde
            BallType.SOLID_7 to Color.rgb(121, 85, 72),     // Marrón
            BallType.EIGHT_BALL to Color.BLACK              // Negra
        )

        for ((ballType, color) in ballColors) {
            val ball = detectBallByColor(bitmap, tableBounds, color, ballType, 40)
            ball?.let { balls.add(it) }
        }

        return balls
    }

    private fun detectBallByColor(
        bitmap: Bitmap,
        tableBounds: RectF,
        targetColor: Int,
        ballType: BallType,
        tolerance: Int
    ): Ball? {

        val colorPoints = findColorPointsInRegion(bitmap, tableBounds, targetColor, tolerance)

        if (colorPoints.size < 10) return null

        val clusters = clusterPoints(colorPoints, 12f)
        val bestCluster = clusters.maxByOrNull { it.size } ?: return null

        if (bestCluster.size < 8) return null

        val center = calculateClusterCenter(bestCluster)
        val avgRadius = calculateAverageRadius(bestCluster, center)

        // Validar tamaño de bola
        if (avgRadius in 8f..25f) {
            return Ball(
                position = center,
                radius = avgRadius,
                type = ballType
            )
        }

        return null
    }

    private fun detectCueDirection(
        bitmap: Bitmap,
        tableBounds: RectF,
        cueBall: Ball
    ): Pair<Float?, PointF?> {

        // Buscar líneas de guía del juego (amarillas/blancas)
        val guideColors = listOf(
            Color.rgb(255, 255, 0),   // Amarillo
            Color.rgb(255, 255, 255), // Blanco
            Color.rgb(255, 200, 0)    // Amarillo claro
        )

        val allGuidePoints = mutableListOf<PointF>()

        for (guideColor in guideColors) {
            val points = findColorPointsInRegion(bitmap, tableBounds, guideColor, 40)
            allGuidePoints.addAll(points.map { PointF(it.x.toFloat(), it.y.toFloat()) })
        }

        if (allGuidePoints.size < 5) return Pair(null, null)

        // Filtrar puntos que estén en línea desde la bola blanca
        val alignedPoints = findPointsAlignedWithCueBall(allGuidePoints, cueBall.position)

        if (alignedPoints.size >= 3) {
            val avgX = alignedPoints.map { it.x }.average().toFloat()
            val avgY = alignedPoints.map { it.y }.average().toFloat()
            val aimTarget = PointF(avgX, avgY)

            val direction = atan2(
                aimTarget.y - cueBall.position.y,
                aimTarget.x - cueBall.position.x
            )

            return Pair(direction, aimTarget)
        }

        return Pair(null, null)
    }

    private fun findPointsAlignedWithCueBall(
        points: List<PointF>,
        cueBallPos: PointF
    ): List<PointF> {

        val alignedPoints = mutableListOf<PointF>()
        val maxDistance = 200f
        val alignmentThreshold = 15f // píxeles de tolerancia

        for (point in points) {
            val distance = calculateDistance(point, cueBallPos)
            if (distance < 50f || distance > maxDistance) continue // Muy cerca o muy lejos

            // Verificar si hay otros puntos alineados
            val direction = atan2(point.y - cueBallPos.y, point.x - cueBallPos.x)
            var alignedCount = 0

            for (otherPoint in points) {
                if (otherPoint == point) continue

                val otherDirection = atan2(otherPoint.y - cueBallPos.y, otherPoint.x - cueBallPos.x)
                val angleDiff = abs(direction - otherDirection)

                if (angleDiff < 0.2f || abs(angleDiff - PI) < 0.2f) { // Misma dirección o opuesta
                    val distanceFromLine = pointToLineDistance(otherPoint, cueBallPos, point)
                    if (distanceFromLine < alignmentThreshold) {
                        alignedCount++
                    }
                }
            }

            if (alignedCount >= 2) {
                alignedPoints.add(point)
            }
        }

        return alignedPoints
    }

    private fun detectPockets(bitmap: Bitmap, tableBounds: RectF): List<Pocket> {
        val pockets = mutableListOf<Pocket>()
        val pocketColor = Color.rgb(20, 20, 20) // Negro de las troneras
        val tolerance = 35

        // Posiciones típicas de troneras en 8 Ball Pool
        val pocketPositions = listOf(
            PointF(tableBounds.left, tableBounds.top) to "Esquina Superior Izquierda",
            PointF(tableBounds.centerX(), tableBounds.top) to "Centro Superior",
            PointF(tableBounds.right, tableBounds.top) to "Esquina Superior Derecha",
            PointF(tableBounds.left, tableBounds.bottom) to "Esquina Inferior Izquierda",
            PointF(tableBounds.centerX(), tableBounds.bottom) to "Centro Inferior",
            PointF(tableBounds.right, tableBounds.bottom) to "Esquina Inferior Derecha"
        )

        for ((position, name) in pocketPositions) {
            if (isPocketAtPosition(bitmap, position, pocketColor, tolerance)) {
                pockets.add(Pocket(position, 25f, name))
            }
        }

        return pockets
    }

    private fun isPocketAtPosition(
        bitmap: Bitmap,
        position: PointF,
        pocketColor: Int,
        tolerance: Int
    ): Boolean {

        val searchRadius = 30f
        var darkPixels = 0
        var totalPixels = 0

        for (dy in -searchRadius.toInt()..searchRadius.toInt() step 3) {
            for (dx in -searchRadius.toInt()..searchRadius.toInt() step 3) {
                val x = position.x + dx
                val y = position.y + dy

                if (x >= 0 && x < bitmap.width && y >= 0 && y < bitmap.height) {
                    val pixel = bitmap.getPixel(x.toInt(), y.toInt())
                    if (isColorSimilar(pixel, pocketColor, tolerance)) {
                        darkPixels++
                    }
                    totalPixels++
                }
            }
        }

        return totalPixels > 0 && darkPixels.toFloat() / totalPixels > 0.25f
    }

    // ========== FUNCIONES AUXILIARES ==========

    private fun findColorPointsInRegion(
        bitmap: Bitmap,
        region: RectF,
        targetColor: Int,
        tolerance: Int
    ): List<Point> {

        val points = mutableListOf<Point>()

        // Muestrear cada 4 píxeles para velocidad
        for (y in region.top.toInt() until region.bottom.toInt() step 4) {
            for (x in region.left.toInt() until region.right.toInt() step 4) {
                if (x >= 0 && x < bitmap.width && y >= 0 && y < bitmap.height) {
                    val pixel = bitmap.getPixel(x, y)
                    if (isColorSimilar(pixel, targetColor, tolerance)) {
                        points.add(Point(x, y))
                    }
                }
            }
        }

        return points
    }

    private fun isColorSimilar(color1: Int, color2: Int, tolerance: Int): Boolean {
        // Usar cache para colores frecuentes
        val cacheKey = (color1 shl 16) or (color2 and 0xFFFF) or (tolerance shl 24)
        colorCache[cacheKey]?.let { return it }

        val r1 = Color.red(color1)
        val g1 = Color.green(color1)
        val b1 = Color.blue(color1)

        val r2 = Color.red(color2)
        val g2 = Color.green(color2)
        val b2 = Color.blue(color2)

        val result = abs(r1 - r2) <= tolerance &&
                abs(g1 - g2) <= tolerance &&
                abs(b1 - b2) <= tolerance

        // Limitar cache para evitar memory leaks
        if (colorCache.size < 1000) {
            colorCache[cacheKey] = result
        }

        return result
    }

    private fun clusterPoints(points: List<Point>, maxDistance: Float): List<List<Point>> {
        val clusters = mutableListOf<MutableList<Point>>()
        val visited = mutableSetOf<Point>()

        for (point in points) {
            if (point in visited) continue

            val cluster = mutableListOf<Point>()
            val queue = mutableListOf(point)

            while (queue.isNotEmpty()) {
                val current = queue.removeAt(0)
                if (current in visited) continue

                visited.add(current)
                cluster.add(current)

                for (other in points) {
                    if (other !in visited) {
                        val distance = sqrt(
                            (current.x - other.x).toFloat().pow(2) +
                                    (current.y - other.y).toFloat().pow(2)
                        )
                        if (distance <= maxDistance) {
                            queue.add(other)
                        }
                    }
                }
            }

            if (cluster.size >= 5) {
                clusters.add(cluster)
            }
        }

        return clusters
    }

    private fun calculateClusterCenter(cluster: List<Point>): PointF {
        val avgX = cluster.map { it.x }.average().toFloat()
        val avgY = cluster.map { it.y }.average().toFloat()
        return PointF(avgX, avgY)
    }

    private fun calculateAverageRadius(cluster: List<Point>, center: PointF): Float {
        return cluster.map { point ->
            calculateDistance(PointF(point.x.toFloat(), point.y.toFloat()), center)
        }.average().toFloat()
    }

    private fun calculateCircularity(cluster: List<Point>, center: PointF, avgRadius: Float): Float {
        val radiusVariances = cluster.map { point ->
            val distance = calculateDistance(PointF(point.x.toFloat(), point.y.toFloat()), center)
            abs(distance - avgRadius)
        }

        val avgVariance = radiusVariances.average().toFloat()
        return (1f - avgVariance / avgRadius).coerceIn(0f, 1f)
    }

    private fun calculateDistance(p1: PointF, p2: PointF): Float {
        return sqrt((p1.x - p2.x).pow(2) + (p1.y - p2.y).pow(2))
    }

    private fun pointToLineDistance(point: PointF, lineStart: PointF, lineEnd: PointF): Float {
        val A = point.x - lineStart.x
        val B = point.y - lineStart.y
        val C = lineEnd.x - lineStart.x
        val D = lineEnd.y - lineStart.y

        val dot = A * C + B * D
        val lenSq = C * C + D * D

        if (lenSq == 0f) return calculateDistance(point, lineStart)

        val param = dot / lenSq
        val closestX = lineStart.x + param * C
        val closestY = lineStart.y + param * D

        return calculateDistance(point, PointF(closestX, closestY))
    }

    private fun handleDetectionResult(gameState: GameState) {
        if (hasSignificantChanges(currentGameState, gameState)) {
            currentGameState = gameState
            detectionCallback?.onGameStateDetected(gameState)
            failureCount = 0 // Reset failure count on success

            // Actualizar confidence state
            val confidence = calculateConfidenceState(gameState)
            detectionCallback?.onConfidenceChanged(confidence)
        }
    }

    private fun handleDetectionFailure() {
        failureCount++
        if (failureCount >= MAX_DETECTION_FAILURES) {
            detectionCallback?.onError(DetectionError.TableNotFound)
            failureCount = 0 // Reset counter
        }
    }

    private fun hasSignificantChanges(oldState: GameState, newState: GameState): Boolean {
        // Verificar si la bola blanca se movió significativamente
        if (oldState.cueBall != null && newState.cueBall != null) {
            val distance = calculateDistance(oldState.cueBall!!.position, newState.cueBall!!.position)
            if (distance > 5f) return true
        }

        // Verificar cambios en dirección del taco
        if (oldState.cueDirection != null && newState.cueDirection != null) {
            val angleDiff = abs(oldState.cueDirection!! - newState.cueDirection!!)
            if (angleDiff > 0.1f) return true
        }

        // Verificar cambios en número de bolas
        if (oldState.targetBalls.size != newState.targetBalls.size) return true

        return false
    }

    private fun calculateConfidenceState(gameState: GameState): ConfidenceState {
        val confidence = ConfidenceState()

        // Confianza de detección
        var detectionScore = 0f
        if (gameState.tableBounds != null) detectionScore += 0.3f
        if (gameState.cueBall != null) detectionScore += 0.4f
        if (gameState.cueDirection != null) detectionScore += 0.2f
        if (gameState.targetBalls.isNotEmpty()) detectionScore += 0.1f

        confidence.detectionConfidence = detectionScore
        confidence.trajectoryConfidence = if (gameState.cueBall != null && gameState.cueDirection != null) 0.8f else 0.2f
        confidence.physicsConfidence = 0.9f // Asumimos que la física es confiable

        confidence.updateOverallConfidence()
        return confidence
    }

    // ========== MÉTODOS PÚBLICOS ==========

    fun stopDetection() {
        isDetecting = false
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        detectionScope.cancel()
        Log.d(TAG, "Detection stopped")
    }

    fun updateConfig(config: DetectionConfig) {
        this.detectionConfig = config
        // Limpiar cache cuando cambia configuración
        colorCache.clear()
        lastTableBounds = null
    }

    fun getCurrentFrame(): Bitmap? = currentFrame

    override fun onDestroy() {
        super.onDestroy()
        stopDetection()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}