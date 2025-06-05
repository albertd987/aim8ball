// SmartCalibrationSystem.kt
package com.albertd987.aimbotoverlay

import android.content.Context
import android.content.SharedPreferences
import android.graphics.*
import android.util.DisplayMetrics
import kotlinx.coroutines.*
import kotlin.math.*

class SmartCalibrationSystem(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "SmartCalibration"
        private const val CALIBRATION_SAMPLES = "calibration_samples"
        private const val DEVICE_PROFILE = "device_profile"
        private const val LEARNING_RATE = 0.1f
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Datos de calibración
    private var deviceProfile = DeviceProfile()
    private var calibrationHistory = mutableListOf<CalibrationSample>()
    private var currentConfig = DetectionConfig()

    // Callbacks
    private var onCalibrationUpdate: ((DetectionConfig) -> Unit)? = null
    private var onCalibrationComplete: ((CalibrationResult) -> Unit)? = null

    init {
        loadCalibrationData()
        setupDeviceProfile()
    }

    fun startAutoCalibration(bitmap: Bitmap, callback: (CalibrationResult) -> Unit) {
        onCalibrationComplete = callback

        scope.launch {
            val result = performAutoCalibration(bitmap)

            withContext(Dispatchers.Main) {
                callback(result)
            }
        }
    }

    private suspend fun performAutoCalibration(bitmap: Bitmap): CalibrationResult {
        val result = CalibrationResult()

        // 1. Detectar configuración de pantalla y orientación
        result.screenConfig = analyzeScreenConfiguration(bitmap)

        // 2. Auto-detectar límites de mesa con múltiples algoritmos
        result.tableBounds = detectTableBoundsMultiAlgorithm(bitmap)

        // 3. Calibrar tamaños de elementos
        result.elementSizes = calibrateElementSizes(bitmap, result.tableBounds)

        // 4. Ajustar colores según iluminación
        result.colorProfile = calibrateColorProfile(bitmap, result.tableBounds)

        // 5. Optimizar parámetros de física basado en historial
        result.physicsConfig = optimizePhysicsParameters()

        // 6. Calcular confianza de la calibración
        result.confidence = calculateCalibrationConfidence(result)

        // 7. Guardar como nueva muestra de aprendizaje
        if (result.confidence > 0.6f) {
            saveCalibrationSample(result)
        }

        return result
    }

    private fun analyzeScreenConfiguration(bitmap: Bitmap): ScreenConfig {
        val metrics = context.resources.displayMetrics

        return ScreenConfig(
            width = bitmap.width,
            height = bitmap.height,
            density = metrics.density,
            orientation = if (bitmap.width > bitmap.height)
                ScreenOrientation.LANDSCAPE else ScreenOrientation.PORTRAIT,
            aspectRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
        )
    }

    private fun detectTableBoundsMultiAlgorithm(bitmap: Bitmap): RectF? {
        val algorithms = listOf(
            ::detectTableByColor,
            ::detectTableByEdges,
            ::detectTableByContrast,
            ::detectTableByTemplate
        )

        val results = algorithms.mapNotNull { algorithm ->
            try {
                algorithm(bitmap)
            } catch (e: Exception) {
                null
            }
        }

        if (results.isEmpty()) return null

        // Combinar resultados usando consenso
        return findConsensusRectangle(results)
    }

    private fun detectTableByColor(bitmap: Bitmap): RectF? {
        // Buscar el color verde dominante de la mesa
        val greenChannelHistogram = IntArray(256)

        // Samplear imagen para crear histograma del canal verde
        for (y in 0 until bitmap.height step 4) {
            for (x in 0 until bitmap.width step 4) {
                val pixel = bitmap.getPixel(x, y)
                val green = Color.green(pixel)
                greenChannelHistogram[green]++
            }
        }

        // Encontrar el pico más prominente en el rango de verdes de mesa (100-180)
        var maxCount = 0
        var peakGreen = 0

        for (green in 100..180) {
            if (greenChannelHistogram[green] > maxCount) {
                maxCount = greenChannelHistogram[green]
                peakGreen = green
            }
        }

        if (maxCount < bitmap.width * bitmap.height * 0.1) return null

        // Buscar región conectada del color dominante
        val tableColor = Color.rgb(peakGreen - 20, peakGreen, peakGreen - 30)
        return findLargestConnectedRegion(bitmap, tableColor, 35)
    }

    private fun detectTableByEdges(bitmap: Bitmap): RectF? {
        // Aplicar detector de bordes Sobel simplificado
        val edges = applySobelEdgeDetection(bitmap)

        // Buscar líneas horizontales y verticales largas (bordes de mesa)
        val horizontalLines = findHorizontalLines(edges)
        val verticalLines = findVerticalLines(edges)

        if (horizontalLines.size < 2 || verticalLines.size < 2) return null

        // Encontrar rectángulo formado por las líneas más prominentes
        val topLine = horizontalLines.minByOrNull { it.y1 }
        val bottomLine = horizontalLines.maxByOrNull { it.y1 }
        val leftLine = verticalLines.minByOrNull { it.x1 }
        val rightLine = verticalLines.maxByOrNull { it.x1 }

        if (topLine != null && bottomLine != null && leftLine != null && rightLine != null) {
            return RectF(
                leftLine.x1.toFloat(),
                topLine.y1.toFloat(),
                rightLine.x1.toFloat(),
                bottomLine.y1.toFloat()
            )
        }

        return null
    }

    private fun detectTableByContrast(bitmap: Bitmap): RectF? {
        // Buscar región de alto contraste que indique el borde de la mesa
        val contrastMap = calculateContrastMap(bitmap)

        // Encontrar rectángulo con mayor contraste en los bordes
        return findHighContrastRectangle(contrastMap)
    }

    private fun detectTableByTemplate(bitmap: Bitmap): RectF? {
        // Usar templates de mesas conocidas basados en el historial
        if (calibrationHistory.isEmpty()) return null

        val templates = calibrationHistory
            .filter { it.confidence > 0.8f }
            .map { it.tableBounds }
            .distinct()

        for (template in templates) {
            val match = templateMatching(bitmap, template)
            if (match.confidence > 0.7f) {
                return match.bounds
            }
        }

        return null
    }

    private fun findConsensusRectangle(rectangles: List<RectF>): RectF? {
        if (rectangles.isEmpty()) return null
        if (rectangles.size == 1) return rectangles.first()

        // Calcular rectángulo promedio ponderado
        var weightedLeft = 0f
        var weightedTop = 0f
        var weightedRight = 0f
        var weightedBottom = 0f
        var totalWeight = 0f

        for (rect in rectangles) {
            // Peso basado en el área (rectángulos más grandes tienen más peso)
            val area = rect.width() * rect.height()
            val weight = sqrt(area)

            weightedLeft += rect.left * weight
            weightedTop += rect.top * weight
            weightedRight += rect.right * weight
            weightedBottom += rect.bottom * weight
            totalWeight += weight
        }

        if (totalWeight == 0f) return null

        return RectF(
            weightedLeft / totalWeight,
            weightedTop / totalWeight,
            weightedRight / totalWeight,
            weightedBottom / totalWeight
        )
    }

    private fun calibrateElementSizes(bitmap: Bitmap, tableBounds: RectF?): ElementSizes {
        if (tableBounds == null) return ElementSizes()

        val sizes = ElementSizes()

        // Estimar tamaño de bola basado en el tamaño de la mesa
        val tableWidth = tableBounds.width()

        // En 8 Ball Pool, las bolas son aproximadamente 1/40 del ancho de la mesa
        sizes.ballRadius = (tableWidth / 40f).coerceIn(8f, 25f)

        // Tamaño de troneras basado en bolas
        sizes.pocketRadius = sizes.ballRadius * 2.2f

        // Intentar detectar elementos reales para validar
        val detectedBallSize = detectActualBallSize(bitmap, tableBounds)
        if (detectedBallSize in 8f..25f) {
            sizes.ballRadius = detectedBallSize
            sizes.pocketRadius = detectedBallSize * 2.2f
        }

        return sizes
    }

    private fun detectActualBallSize(bitmap: Bitmap, tableBounds: RectF): Float {
        // Buscar círculos blancos (bola blanca) para medir tamaño real
        val whitePixels = mutableListOf<Point>()

        for (y in tableBounds.top.toInt() until tableBounds.bottom.toInt() step 3) {
            for (x in tableBounds.left.toInt() until tableBounds.right.toInt() step 3) {
                val pixel = bitmap.getPixel(x, y)
                if (isWhiteish(pixel, 30)) {
                    whitePixels.add(Point(x, y))
                }
            }
        }

        // Buscar clusters circulares
        val clusters = clusterPoints(whitePixels, 12f)

        for (cluster in clusters.filter { it.size >= 15 }) {
            val center = Point(
                cluster.map { it.x }.average().toInt(),
                cluster.map { it.y }.average().toInt()
            )

            val distances = cluster.map { point ->
                sqrt((point.x - center.x).toFloat().pow(2) + (point.y - center.y).toFloat().pow(2))
            }

            val avgRadius = distances.average().toFloat()
            val radiusVariance = distances.map { (it - avgRadius).pow(2) }.average()

            // Si es suficientemente circular y del tamaño correcto
            if (radiusVariance < avgRadius * 0.4 && avgRadius in 8f..25f) {
                return avgRadius
            }
        }

        return 12f // Valor por defecto
    }

    private fun calibrateColorProfile(bitmap: Bitmap, tableBounds: RectF?): ColorProfile {
        val profile = ColorProfile()

        if (tableBounds == null) return profile

        // Samplear colores en diferentes regiones de la mesa
        val samples = sampleTableColors(bitmap, tableBounds)

        // Calcular color promedio de la mesa
        profile.tableColor = calculateAverageColor(samples.tableRegion)

        // Ajustar tolerancia basada en variación de color
        val colorVariance = calculateColorVariance(samples.tableRegion)
        profile.colorTolerance = (25 + colorVariance * 0.5f).toInt().coerceIn(15, 50)

        // Detectar condiciones de iluminación
        profile.brightness = calculateAverageBrightness(samples.tableRegion)
        profile.contrast = calculateContrast(samples.tableRegion)

        return profile
    }

    private fun optimizePhysicsParameters(): PhysicsConfig {
        val config = PhysicsConfig()

        if (calibrationHistory.isEmpty()) return config

        // Analizar trayectorias exitosas del historial
        val successfulTrajectories = calibrationHistory
            .filter { it.confidence > 0.7f }
            .flatMap { it.trajectoryData }

        if (successfulTrajectories.isNotEmpty()) {
            // Optimizar fricción basada en trayectorias observadas
            config.friction = successfulTrajectories
                .map { it.friction }
                .average()
                .toFloat()
                .coerceIn(0.96f, 0.99f)

            // Optimizar coeficiente de rebote
            config.restitution = successfulTrajectories
                .map { it.restitution }
                .average()
                .toFloat()
                .coerceIn(0.7f, 0.9f)
        }

        return config
    }

    fun learnFromManualCorrection(correction: ManualCorrection) {
        scope.launch {
            // Actualizar configuración basada en corrección manual
            when (correction.type) {
                CorrectionType.CUE_BALL_POSITION -> {
                    updateBallDetectionParameters(correction)
                }
                CorrectionType.TABLE_BOUNDS -> {
                    updateTableDetectionParameters(correction)
                }
                CorrectionType.AIM_DIRECTION -> {
                    updateAimDetectionParameters(correction)
                }
                CorrectionType.PHYSICS_PARAMETERS -> {
                    updatePhysicsParameters(correction)
                }
                CorrectionType.BALL_DETECTION -> {
                    updateBallDetectionParameters(correction)
                }
                CorrectionType.POCKET_DETECTION -> {
                    updateTableDetectionParameters(correction)
                }
            }

            saveCalibrationData()
        }
    }

    private fun updateBallDetectionParameters(correction: ManualCorrection) {
        // Ajustar parámetros de detección de bolas basado en la corrección
        val learningRate = LEARNING_RATE

        correction.detectedPosition?.let { detected ->
            correction.correctedPosition?.let { corrected ->
                val error = PointF(
                    corrected.x - detected.x,
                    corrected.y - detected.y
                )

                // Actualizar offset de detección
                currentConfig.ballDetectionOffset.x += error.x * learningRate
                currentConfig.ballDetectionOffset.y += error.y * learningRate

                // Ajustar tolerancia de color si es necesario
                if (abs(error.x) > 10f || abs(error.y) > 10f) {
                    currentConfig.colorTolerance = (currentConfig.colorTolerance + 2).coerceAtMost(50)
                }
            }
        }
    }

    private fun calculateCalibrationConfidence(result: CalibrationResult): Float {
        var confidence = 0f

        // +0.3 si detectó mesa con buenas dimensiones
        result.tableBounds?.let { bounds ->
            val area = bounds.width() * bounds.height()
            val screenArea = result.screenConfig.width * result.screenConfig.height
            val tableRatio = area / screenArea

            if (tableRatio in 0.3f..0.8f) confidence += 0.3f
        }

        // +0.2 si los tamaños de elementos son realistas
        if (result.elementSizes.ballRadius in 8f..25f) confidence += 0.2f

        // +0.2 si el perfil de color es consistente
        if (result.colorProfile.brightness in 50f..200f) confidence += 0.2f

        // +0.3 si coincide con calibraciones anteriores exitosas
        val historicalMatch = findSimilarCalibration(result)
        if (historicalMatch != null && historicalMatch.confidence > 0.7f) {
            confidence += 0.3f
        }

        return confidence.coerceIn(0f, 1f)
    }

    private fun saveCalibrationSample(result: CalibrationResult) {
        val sample = CalibrationSample(
            timestamp = System.currentTimeMillis(),
            tableBounds = result.tableBounds ?: RectF(),
            elementSizes = result.elementSizes,
            colorProfile = result.colorProfile,
            screenConfig = result.screenConfig,
            confidence = result.confidence,
            trajectoryData = emptyList() // Se llenaría durante el uso
        )

        calibrationHistory.add(sample)

        // Mantener solo las últimas 50 muestras
        if (calibrationHistory.size > 50) {
            calibrationHistory = calibrationHistory
                .sortedByDescending { it.confidence }
                .take(50)
                .toMutableList()
        }

        saveCalibrationData()
    }

    private fun setupDeviceProfile() {
        val metrics = context.resources.displayMetrics

        deviceProfile = DeviceProfile(
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels,
            density = metrics.density,
            deviceModel = android.os.Build.MODEL,
            androidVersion = android.os.Build.VERSION.SDK_INT
        )
    }

    private fun loadCalibrationData() {
        // Cargar datos de calibración guardados
        val savedSamples = prefs.getString(CALIBRATION_SAMPLES, null)
        savedSamples?.let { json ->
            try {
                // En una implementación real, usarías JSON parsing
                // calibrationHistory = parseCalibrationHistory(json)
            } catch (e: Exception) {
                calibrationHistory.clear()
            }
        }
    }

    private fun saveCalibrationData() {
        // Guardar datos de calibración
        scope.launch {
            try {
                // En una implementación real, usarías JSON serialization
                // val json = serializeCalibrationHistory(calibrationHistory)
                // prefs.edit().putString(CALIBRATION_SAMPLES, json).apply()
            } catch (e: Exception) {
                // Log error
            }
        }
    }

    private fun updateTableDetectionParameters(correction: ManualCorrection) {
        // Placeholder para actualización de parámetros de mesa
    }

    private fun updateAimDetectionParameters(correction: ManualCorrection) {
        // Placeholder para actualización de parámetros de puntería
    }

    private fun updatePhysicsParameters(correction: ManualCorrection) {
        // Placeholder para actualización de parámetros de física
    }

    // ========== FUNCIONES AUXILIARES IMPLEMENTADAS ==========

    private fun applySobelEdgeDetection(bitmap: Bitmap): Array<Array<Float>> {
        val width = bitmap.width
        val height = bitmap.height
        val edges = Array(height) { Array(width) { 0f } }

        // Implementación básica de Sobel
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val gx = getGradientX(bitmap, x, y)
                val gy = getGradientY(bitmap, x, y)
                edges[y][x] = sqrt(gx * gx + gy * gy)
            }
        }

        return edges
    }

    private fun getGradientX(bitmap: Bitmap, x: Int, y: Int): Float {
        val pixel1 = Color.red(bitmap.getPixel(x - 1, y - 1)) + Color.red(bitmap.getPixel(x - 1, y)) * 2 + Color.red(bitmap.getPixel(x - 1, y + 1))
        val pixel2 = Color.red(bitmap.getPixel(x + 1, y - 1)) + Color.red(bitmap.getPixel(x + 1, y)) * 2 + Color.red(bitmap.getPixel(x + 1, y + 1))
        return (pixel2 - pixel1).toFloat()
    }

    private fun getGradientY(bitmap: Bitmap, x: Int, y: Int): Float {
        val pixel1 = Color.red(bitmap.getPixel(x - 1, y - 1)) + Color.red(bitmap.getPixel(x, y - 1)) * 2 + Color.red(bitmap.getPixel(x + 1, y - 1))
        val pixel2 = Color.red(bitmap.getPixel(x - 1, y + 1)) + Color.red(bitmap.getPixel(x, y + 1)) * 2 + Color.red(bitmap.getPixel(x + 1, y + 1))
        return (pixel2 - pixel1).toFloat()
    }

    private fun findHorizontalLines(edges: Array<Array<Float>>): List<Line> {
        val lines = mutableListOf<Line>()
        val threshold = 100f

        for (y in edges.indices) {
            var startX = -1
            for (x in edges[y].indices) {
                if (edges[y][x] > threshold) {
                    if (startX == -1) startX = x
                } else {
                    if (startX != -1 && x - startX > 20) {
                        lines.add(Line(startX, y, x, y, edges[y][x]))
                    }
                    startX = -1
                }
            }
        }

        return lines
    }

    private fun findVerticalLines(edges: Array<Array<Float>>): List<Line> {
        val lines = mutableListOf<Line>()
        val threshold = 100f

        for (x in 0 until edges[0].size) {
            var startY = -1
            for (y in edges.indices) {
                if (edges[y][x] > threshold) {
                    if (startY == -1) startY = y
                } else {
                    if (startY != -1 && y - startY > 20) {
                        lines.add(Line(x, startY, x, y, edges[y][x]))
                    }
                    startY = -1
                }
            }
        }

        return lines
    }

    private fun calculateContrastMap(bitmap: Bitmap): Array<Array<Float>> {
        val width = bitmap.width
        val height = bitmap.height
        val contrastMap = Array(height) { Array(width) { 0f } }

        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val centerPixel = bitmap.getPixel(x, y)
                val centerBrightness = Color.red(centerPixel) + Color.green(centerPixel) + Color.blue(centerPixel)

                var maxDiff = 0
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val neighborPixel = bitmap.getPixel(x + dx, y + dy)
                        val neighborBrightness = Color.red(neighborPixel) + Color.green(neighborPixel) + Color.blue(neighborPixel)
                        val diff = abs(centerBrightness - neighborBrightness)
                        if (diff > maxDiff) maxDiff = diff
                    }
                }

                contrastMap[y][x] = maxDiff.toFloat()
            }
        }

        return contrastMap
    }

    private fun findHighContrastRectangle(contrastMap: Array<Array<Float>>): RectF? {
        // Implementación básica: encontrar región con alto contraste promedio
        var bestRect: RectF? = null
        var bestScore = 0f

        val height = contrastMap.size
        val width = contrastMap[0].size

        for (y1 in 0 until height - 50) {
            for (x1 in 0 until width - 50) {
                for (y2 in y1 + 50 until height) {
                    for (x2 in x1 + 50 until width) {
                        val score = calculateRectangleContrastScore(contrastMap, x1, y1, x2, y2)
                        if (score > bestScore) {
                            bestScore = score
                            bestRect = RectF(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat())
                        }
                    }
                }
            }
        }

        return bestRect
    }

    private fun calculateRectangleContrastScore(contrastMap: Array<Array<Float>>, x1: Int, y1: Int, x2: Int, y2: Int): Float {
        var totalContrast = 0f
        var count = 0

        for (y in y1 until y2) {
            for (x in x1 until x2) {
                totalContrast += contrastMap[y][x]
                count++
            }
        }

        return if (count > 0) totalContrast / count else 0f
    }

    private fun templateMatching(bitmap: Bitmap, template: RectF): TemplateMatch {
        // Implementación básica de template matching
        val templateWidth = template.width().toInt()
        val templateHeight = template.height().toInt()

        var bestMatch = TemplateMatch(RectF(), 0f)
        var bestScore = 0f

        for (y in 0 until bitmap.height - templateHeight) {
            for (x in 0 until bitmap.width - templateWidth) {
                val score = calculateTemplateMatchScore(bitmap, x, y, template)
                if (score > bestScore) {
                    bestScore = score
                    bestMatch = TemplateMatch(
                        RectF(x.toFloat(), y.toFloat(), (x + templateWidth).toFloat(), (y + templateHeight).toFloat()),
                        score
                    )
                }
            }
        }

        return bestMatch
    }

    private fun calculateTemplateMatchScore(bitmap: Bitmap, x: Int, y: Int, template: RectF): Float {
        // Score basado en similitud de color promedio
        var score = 0f
        val templateColors = getTemplateColors(template)
        val regionColors = getRegionColors(bitmap, x, y, template.width().toInt(), template.height().toInt())

        if (templateColors.isNotEmpty() && regionColors.isNotEmpty()) {
            score = calculateColorSimilarity(templateColors, regionColors)
        }

        return score
    }

    private fun findLargestConnectedRegion(bitmap: Bitmap, targetColor: Int, tolerance: Int): RectF? {
        val visited = Array(bitmap.height) { BooleanArray(bitmap.width) }
        var largestRegion: List<Point>? = null
        var largestSize = 0

        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (!visited[y][x] && isColorSimilarForRegion(bitmap.getPixel(x, y), targetColor, tolerance)) {
                    val region = floodFill(bitmap, x, y, targetColor, tolerance, visited)
                    if (region.size > largestSize) {
                        largestSize = region.size
                        largestRegion = region
                    }
                }
            }
        }

        return largestRegion?.let { region ->
            val minX = region.minOf { it.x }.toFloat()
            val maxX = region.maxOf { it.x }.toFloat()
            val minY = region.minOf { it.y }.toFloat()
            val maxY = region.maxOf { it.y }.toFloat()
            RectF(minX, minY, maxX, maxY)
        }
    }

    private fun floodFill(bitmap: Bitmap, startX: Int, startY: Int, targetColor: Int, tolerance: Int, visited: Array<BooleanArray>): List<Point> {
        val region = mutableListOf<Point>()
        val queue = mutableListOf(Point(startX, startY))

        while (queue.isNotEmpty()) {
            val point = queue.removeAt(0)
            val x = point.x
            val y = point.y

            if (x < 0 || x >= bitmap.width || y < 0 || y >= bitmap.height || visited[y][x]) continue

            val pixelColor = bitmap.getPixel(x, y)
            if (!isColorSimilarForRegion(pixelColor, targetColor, tolerance)) continue

            visited[y][x] = true
            region.add(Point(x, y))

            // Agregar vecinos
            queue.add(Point(x + 1, y))
            queue.add(Point(x - 1, y))
            queue.add(Point(x, y + 1))
            queue.add(Point(x, y - 1))
        }

        return region
    }

    private fun isColorSimilarForRegion(color1: Int, color2: Int, tolerance: Int): Boolean {
        val r1 = Color.red(color1)
        val g1 = Color.green(color1)
        val b1 = Color.blue(color1)

        val r2 = Color.red(color2)
        val g2 = Color.green(color2)
        val b2 = Color.blue(color2)

        return abs(r1 - r2) <= tolerance &&
                abs(g1 - g2) <= tolerance &&
                abs(b1 - b2) <= tolerance
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

    private fun isWhiteish(pixel: Int, tolerance: Int): Boolean {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)

        // Verificar que todos los componentes estén cerca del blanco
        return r >= 255 - tolerance && g >= 255 - tolerance && b >= 255 - tolerance
    }

    private fun getRegionColors(bitmap: Bitmap, x: Int, y: Int, width: Int, height: Int): List<Int> {
        val colors = mutableListOf<Int>()
        for (dy in 0 until height step 5) {
            for (dx in 0 until width step 5) {
                if (x + dx < bitmap.width && y + dy < bitmap.height) {
                    colors.add(bitmap.getPixel(x + dx, y + dy))
                }
            }
        }
        return colors
    }

    // Funciones auxiliares simplificadas
    private fun sampleTableColors(bitmap: Bitmap, tableBounds: RectF): ColorSamples {
        return ColorSamples()
    }

    private fun calculateAverageColor(pixels: List<Int>): Int = Color.GREEN
    private fun calculateColorVariance(pixels: List<Int>): Float = 15f
    private fun calculateAverageBrightness(pixels: List<Int>): Float = 128f
    private fun calculateContrast(pixels: List<Int>): Float = 0.5f
    private fun findSimilarCalibration(result: CalibrationResult): CalibrationSample? = null
    private fun getTemplateColors(template: RectF): List<Int> = emptyList()
    private fun calculateColorSimilarity(colors1: List<Int>, colors2: List<Int>): Float = 0f
}