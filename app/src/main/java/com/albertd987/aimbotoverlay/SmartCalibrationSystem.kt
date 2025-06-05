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
        val topLine = horizontalLines.minByOrNull { it.y }
        val bottomLine = horizontalLines.maxByOrNull { it.y }
        val leftLine = verticalLines.minByOrNull { it.x }
        val rightLine = verticalLines.maxByOrNull { it.x }

        if (topLine != null && bottomLine != null && leftLine != null && rightLine != null) {
            return RectF(
                leftLine.x.toFloat(),
                topLine.y.toFloat(),
                rightLine.x.toFloat(),
                bottomLine.y.toFloat()
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

        // Encontrar intersección que tenga consenso de al menos 60% de los algoritmos
        val minConsensus = (rectangles.size * 0.6f).toInt()

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
        val tableHeight = tableBounds.height()

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

    // Funciones auxiliares de procesamiento de imagen
    private fun applySobelEdgeDetection(bitmap: Bitmap): Array<IntArray> {
        val width = bitmap.width
        val height = bitmap.height
        val edges = Array(height) { IntArray(width) }

        // Kernels de Sobel
        val sobelX = arrayOf(
            intArrayOf(-1, 0, 1),
            intArrayOf(-2, 0, 2),
            intArrayOf(-1, 0, 1)
        )

        val sobelY = arrayOf(
            intArrayOf(-1, -2, -1),
            intArrayOf(0, 0, 0),
            intArrayOf(1, 2, 1)
        )

        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                var gx = 0
                var gy = 0

                for (ky in -1..1) {
                    for (kx in -1..1) {
                        val pixel = bitmap.getPixel(x + kx, y + ky)
                        val gray = (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3

                        gx += gray * sobelX[ky + 1][kx + 1]
                        gy += gray * sobelY[ky + 1][kx + 1]
                    }
                }

                edges[y][x] = sqrt((gx * gx + gy * gy).toDouble()).toInt().coerceAtMost(255)
            }
        }

        return edges
    }

    private fun findHorizontalLines(edges: Array<IntArray>): List<Line> {
        val lines = mutableListOf<Line>()
        val threshold = 100

        for (y in edges.indices) {
            var lineStart = -1
            var lineLength = 0

            for (x in edges[y].indices) {
                if (edges[y][x] > threshold) {
                    if (lineStart == -1) lineStart = x
                    lineLength++
                } else {
                    if (lineLength > edges[y].size * 0.3) { // Línea debe ser al menos 30% del ancho
                        lines.add(Line(lineStart, y, lineStart + lineLength, y))
                    }
                    lineStart = -1
                    lineLength = 0
                }
            }
        }

        return lines
    }

    private fun findVerticalLines(edges: Array<IntArray>): List<Line> {
        val lines = mutableListOf<Line>()
        val threshold = 100

        for (x in 0 until edges[0].size) {
            var lineStart = -1
            var lineLength = 0

            for (y in edges.indices) {
                if (edges[y][x] > threshold) {
                    if (lineStart == -1) lineStart = y
                    lineLength++
                } else {
                    if (lineLength > edges.size * 0.3) { // Línea debe ser al menos 30% de la altura
                        lines.add(Line(x, lineStart, x, lineStart + lineLength))
                    }
                    lineStart = -1
                    lineLength = 0
                }
            }
        }

        return lines
    }

    private fun calculateContrastMap(bitmap: Bitmap): Array<FloatArray> {
        val width = bitmap.width
        val height = bitmap.height
        val contrast = Array(height) { FloatArray(width) }

        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val pixels = mutableListOf<Int>()

                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val pixel = bitmap.getPixel(x + dx, y + dy)
                        val gray = (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3
                        pixels.add(gray)
                    }
                }

                val maxVal = pixels.maxOrNull() ?: 0
                val minVal = pixels.minOrNull() ?: 0
                contrast[y][x] = if (maxVal + minVal > 0) {
                    (maxVal - minVal).toFloat() / (maxVal + minVal)
                } else 0f
            }
        }

        return contrast
    }

    private fun findHighContrastRectangle(contrastMap: Array<FloatArray>): RectF? {
        // Buscar rectángulo con bordes de alto contraste
        val height = contrastMap.size
        val width = contrastMap[0].size
        val threshold = 0.3f

        var bestRect: RectF? = null
        var bestScore = 0f

        // Evaluar múltiples rectángulos candidatos
        for (top in 0 until height / 4) {
            for (left in 0 until width / 4) {
                for (bottom in height * 3 / 4 until height) {
                    for (right in width * 3 / 4 until width) {
                        val score = evaluateRectangleContrast(contrastMap, left, top, right, bottom, threshold)

                        if (score > bestScore) {
                            bestScore = score
                            bestRect = RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
                        }
                    }
                }
            }
        }

        return if (bestScore > 0.5f) bestRect else null
    }

    private fun evaluateRectangleContrast(
        contrastMap: Array<FloatArray>,
        left: Int, top: Int, right: Int, bottom: Int,
        threshold: Float
    ): Float {
        var edgePixels = 0
        var totalEdgePixels = 0

        // Evaluar borde superior
        for (x in left..right) {
            if (contrastMap[top][x] > threshold) edgePixels++
            totalEdgePixels++
        }

        // Evaluar borde inferior
        for (x in left..right) {
            if (contrastMap[bottom][x] > threshold) edgePixels++
            totalEdgePixels++
        }

        // Evaluar borde izquierdo
        for (y in top..bottom) {
            if (contrastMap[y][left] > threshold) edgePixels++
            totalEdgePixels++
        }

        // Evaluar borde derecho
        for (y in top..bottom) {
            if (contrastMap[y][right] > threshold) edgePixels++
            totalEdgePixels++
        }

        return if (totalEdgePixels > 0) edgePixels.toFloat() / totalEdgePixels else 0f
    }

    private fun templateMatching(bitmap: Bitmap, template: RectF): TemplateMatch {
        // Template matching simplificado
        val templateWidth = template.width().toInt()
        val templateHeight = template.height().toInt()

        var bestMatch = TemplateMatch(RectF(), 0f)

        // Buscar en diferentes posiciones y escalas
        for (scale in listOf(0.8f, 0.9f, 1.0f, 1.1f, 1.2f)) {
            val scaledWidth = (templateWidth * scale).toInt()
            val scaledHeight = (templateHeight * scale).toInt()

            for (y in 0 until bitmap.height - scaledHeight step 20) {
                for (x in 0 until bitmap.width - scaledWidth step 20) {
                    val match = calculateTemplateMatch(bitmap, x, y, scaledWidth, scaledHeight, template)

                    if (match.confidence > bestMatch.confidence) {
                        bestMatch = match
                    }
                }
            }
        }

        return bestMatch
    }

    private fun calculateTemplateMatch(
        bitmap: Bitmap, x: Int, y: Int, width: Int, height: Int, template: RectF
    ): TemplateMatch {
        // Calcular similitud basada en distribución de colores
        val regionColors = sampleRegionColors(bitmap, x, y, width, height)
        val templateColors = getTemplateColors(template)

        val similarity = calculateColorSimilarity(regionColors, templateColors)

        return TemplateMatch(
            bounds = RectF(x.toFloat(), y.toFloat(), (x + width).toFloat(), (y + height).toFloat()),
            confidence = similarity
        )
    }

    private fun sampleRegionColors(bitmap: Bitmap, x: Int, y: Int, width: Int, height: Int): List<Int> {
        val colors = mutableListOf<Int>()

        for (sy in y until y + height step 5) {
            for (sx in x until x + width step 5) {
                if (sx < bitmap.width && sy < bitmap.height) {
                    colors.add(bitmap.getPixel(sx, sy))
                }
            }
        }

        return colors
    }

    private fun isWhiteish(pixel: Int, tolerance: Int): Boolean {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)

        return r > 255 - tolerance && g > 255 - tolerance && b > 255 - tolerance &&
                abs(r - g) < tolerance && abs(g - b) < tolerance && abs(r - b) < tolerance
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

            if (cluster.size >= 10) {
                clusters.add(cluster)
            }
        }

        return clusters
    }

    private fun findLargestConnectedRegion(bitmap: Bitmap, targetColor: Int, tolerance: Int): RectF? {
        // Implementación simplificada de flood fill para encontrar la región más grande
        // En una implementación real, usarías algoritmos más eficientes

        val visited = Array(bitmap.height) { BooleanArray(bitmap.width) }
        var largestRegion = emptyList<Point>()

        for (y in 0 until bitmap.height step 10) {
            for (x in 0 until bitmap.width step 10) {
                if (!visited[y][x]) {
                    val region = floodFill(bitmap, x, y, targetColor, tolerance, visited)

                    if (region.size > largestRegion.size) {
                        largestRegion = region
                    }
                }
            }
        }

        if (largestRegion.isEmpty()) return null

        val minX = largestRegion.minOf { it.x }.toFloat()
        val maxX = largestRegion.maxOf { it.x }.toFloat()
        val minY = largestRegion.minOf { it.y }.toFloat()
        val maxY = largestRegion.maxOf { it.y }.toFloat()

        return RectF(minX, minY, maxX, maxY)
    }

    private fun floodFill(
        bitmap: Bitmap, startX: Int, startY: Int,
        targetColor: Int, tolerance: Int,
        visited: Array<BooleanArray>
    ): List<Point> {
        val region = mutableListOf<Point>()
        val queue = mutableListOf(Point(startX, startY))

        while (queue.isNotEmpty() && region.size < 10000) { // Límite para evitar regiones enormes
            val point = queue.removeAt(0)

            if (point.x < 0 || point.x >= bitmap.width ||
                point.y < 0 || point.y >= bitmap.height ||
                visited[point.y][point.x]) continue

            val pixel = bitmap.getPixel(point.x, point.y)
            if (!isColorSimilar(pixel, targetColor, tolerance)) continue

            visited[point.y][point.x] = true
            region.add(point)

            // Agregar vecinos
            queue.add(Point(point.x + 1, point.y))
            queue.add(Point(point.x - 1, point.y))
            queue.add(Point(point.x, point.y + 1))
            queue.add(Point(point.x, point.y - 1))
        }

        return region
    }

    private fun isColorSimilar(color1: Int, color2: Int, tolerance: Int): Boolean {
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

    // Funciones auxiliares que necesitarían implementación completa
    private fun sampleTableColors(bitmap: Bitmap, tableBounds: RectF): ColorSamples {
        // Implementar sampling de colores en diferentes regiones
        return ColorSamples()
    }

    private fun calculateAverageColor(pixels: List<Int>): Int = Color.GREEN // Placeholder
    private fun calculateColorVariance(pixels: List<Int>): Float = 15f // Placeholder
    private fun calculateAverageBrightness(pixels: List<Int>): Float = 128f // Placeholder
    private fun calculateContrast(pixels: List<Int>): Float = 0.5f // Placeholder
    private fun findSimilarCalibration(result: CalibrationResult): CalibrationSample? = null // Placeholder
    private fun getTemplateColors(template: RectF): List<Int> = emptyList() // Placeholder
    private fun calculateColorSimilarity(colors1: List<Int>, colors2: List<Int>): Float = 0f // Placeholder
}

// Clases de datos para calibración
data class CalibrationResult(
    var tableBounds: RectF? = null,
    var elementSizes: ElementSizes = ElementSizes(),
    var colorProfile: ColorProfile = ColorProfile(),
    var screenConfig: ScreenConfig = ScreenConfig(),
    var physicsConfig: PhysicsConfig = PhysicsConfig(),
    var confidence: Float = 0f
)

data class DetectionConfig(
    var ballDetectionOffset: PointF = PointF(0f, 0f),
    var colorTolerance: Int = 35,
    var ballRadius: Float = 12f
)

data class ElementSizes(
    var ballRadius: Float = 12f,
    var pocketRadius: Float = 25f
)

data class ColorProfile(
    var tableColor: Int = Color.rgb(46, 125, 50),
    var colorTolerance: Int = 35,
    var brightness: Float = 128f,
    var contrast: Float = 0.5f
)

data class ScreenConfig(
    var width: Int = 0,
    var height: Int = 0,
    var density: Float = 1f,
    var orientation: ScreenOrientation = ScreenOrientation.LANDSCAPE,
    var aspectRatio: Float = 16f/9f
)

data class DeviceProfile(
    var screenWidth: Int = 0,
    var screenHeight: Int = 0,
    var density: Float = 1f,
    var deviceModel: String = "",
    var androidVersion: Int = 0
)

data class CalibrationSample(
    var timestamp: Long = 0,
    var tableBounds: RectF = RectF(),
    var elementSizes: ElementSizes = ElementSizes(),
    var colorProfile: ColorProfile = ColorProfile(),
    var screenConfig: ScreenConfig = ScreenConfig(),
    var confidence: Float = 0f,
    var trajectoryData: List<TrajectoryData> = emptyList()
)

data class TrajectoryData(
    var friction: Float = 0.985f,
    var restitution: Float = 0.85f
)

data class ManualCorrection(
    var type: CorrectionType,
    var detectedPosition: PointF? = null,
    var correctedPosition: PointF? = null,
    var timestamp: Long = System.currentTimeMillis()
)

data class PhysicsConfig(
    var friction: Float = 0.985f,
    var restitution: Float = 0.85f,
    var initialVelocity: Float = 600f,
    var minVelocity: Float = 30f,
    var maxTrajectorySteps: Int = 200,
    var timeStep: Float = 0.02f
)

data class Line(
    var x1: Int, var y1: Int,
    var x2: Int, var y2: Int
)

data class TemplateMatch(
    var bounds: RectF,
    var confidence: Float
)

data class ColorSamples(
    var tableRegion: List<Int> = emptyList(),
    var borderRegion: List<Int> = emptyList(),
    var ballRegion: List<Int> = emptyList()
)

enum class ScreenOrientation {
    PORTRAIT, LANDSCAPE
}

enum class CorrectionType {
    CUE_BALL_POSITION,
    TABLE_BOUNDS,
    AIM_DIRECTION,
    PHYSICS_PARAMETERS
}