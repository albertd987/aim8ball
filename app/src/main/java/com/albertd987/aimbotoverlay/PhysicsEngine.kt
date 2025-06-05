// PhysicsEngine.kt
package com.albertd987.aimbotoverlay

import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.*

class PhysicsEngine(private var config: PhysicsConfig = PhysicsConfig()) {

    companion object {
        private const val MAX_SIMULATION_TIME = 10f // segundos
        private const val MIN_COLLISION_DISTANCE = 1f
    }

    // Calcular trayectoria principal de la bola blanca
    fun calculateCueBallTrajectory(
        cueBall: Ball,
        direction: Float,
        power: Float,
        tableBounds: RectF,
        obstacles: List<Ball> = emptyList(),
        pockets: List<Pocket> = emptyList()
    ): TrajectoryPath {

        val points = mutableListOf<PointF>()
        val collisions = mutableListOf<CollisionEvent>()

        // Estado inicial
        var currentPos = PointF(cueBall.position.x, cueBall.position.y)
        var velocity = PointF(
            cos(direction) * config.initialVelocity * power,
            sin(direction) * config.initialVelocity * power
        )

        points.add(PointF(currentPos.x, currentPos.y))

        var simulationTime = 0f
        var stepCount = 0

        while (simulationTime < MAX_SIMULATION_TIME &&
            stepCount < config.maxTrajectorySteps &&
            getVelocityMagnitude(velocity) > config.minVelocity) {

            val timeStep = config.timeStep
            simulationTime += timeStep
            stepCount++

            // Calcular nueva posición
            val nextPos = PointF(
                currentPos.x + velocity.x * timeStep,
                currentPos.y + velocity.y * timeStep
            )

            // Verificar colisiones con otras bolas
            val ballCollision = checkBallCollisions(
                currentPos, nextPos, cueBall.radius, obstacles
            )

            if (ballCollision != null) {
                collisions.add(ballCollision)
                points.add(ballCollision.point)

                // Terminar simulación tras colisión con bola (por simplicidad)
                break
            }

            // Verificar colisiones con paredes
            val wallCollision = checkWallCollisions(
                currentPos, nextPos, tableBounds, cueBall.radius
            )

            if (wallCollision != null) {
                collisions.add(wallCollision)
                points.add(wallCollision.point)

                // Aplicar rebote
                velocity = applyWallBounce(velocity, wallCollision.wall!!)
                currentPos = wallCollision.point

                // Pérdida de energía en rebote
                velocity.x *= config.restitution
                velocity.y *= config.restitution
            } else {
                currentPos = nextPos
                points.add(PointF(currentPos.x, currentPos.y))
            }

            // Verificar si entra en tronera
            val pocketHit = checkPocketCollisions(currentPos, pockets)
            if (pocketHit != null) {
                return TrajectoryPath(
                    points = points,
                    collisions = collisions,
                    pocketPrediction = pocketHit,
                    confidence = calculateTrajectoryConfidence(points, collisions)
                )
            }

            // Aplicar fricción
            velocity.x *= config.friction
            velocity.y *= config.friction
        }

        return TrajectoryPath(
            points = points,
            collisions = collisions,
            pocketPrediction = null,
            confidence = calculateTrajectoryConfidence(points, collisions)
        )
    }

    // Calcular trayectoria de bola objetivo tras colisión
    fun calculateSecondaryBallTrajectory(
        ball: Ball,
        impactVelocity: PointF,
        tableBounds: RectF,
        obstacles: List<Ball> = emptyList(),
        pockets: List<Pocket> = emptyList()
    ): TrajectoryPath {

        val points = mutableListOf<PointF>()
        val collisions = mutableListOf<CollisionEvent>()

        var currentPos = PointF(ball.position.x, ball.position.y)
        var velocity = PointF(impactVelocity.x * 0.7f, impactVelocity.y * 0.7f) // Transferencia de momento

        points.add(PointF(currentPos.x, currentPos.y))

        var simulationTime = 0f
        var stepCount = 0

        while (simulationTime < MAX_SIMULATION_TIME / 2f && // Menos tiempo para bolas secundarias
            stepCount < config.maxTrajectorySteps / 2 &&
            getVelocityMagnitude(velocity) > config.minVelocity) {

            val timeStep = config.timeStep
            simulationTime += timeStep
            stepCount++

            val nextPos = PointF(
                currentPos.x + velocity.x * timeStep,
                currentPos.y + velocity.y * timeStep
            )

            // Verificar colisiones con paredes
            val wallCollision = checkWallCollisions(
                currentPos, nextPos, tableBounds, ball.radius
            )

            if (wallCollision != null) {
                collisions.add(wallCollision)
                points.add(wallCollision.point)

                velocity = applyWallBounce(velocity, wallCollision.wall!!)
                currentPos = wallCollision.point

                velocity.x *= config.restitution
                velocity.y *= config.restitution
            } else {
                currentPos = nextPos
                points.add(PointF(currentPos.x, currentPos.y))
            }

            // Verificar troneras
            val pocketHit = checkPocketCollisions(currentPos, pockets)
            if (pocketHit != null) {
                return TrajectoryPath(
                    points = points,
                    collisions = collisions,
                    pocketPrediction = pocketHit,
                    confidence = calculateTrajectoryConfidence(points, collisions),
                    ballType = ball.type
                )
            }

            // Aplicar fricción
            velocity.x *= config.friction
            velocity.y *= config.friction
        }

        return TrajectoryPath(
            points = points,
            collisions = collisions,
            pocketPrediction = null,
            confidence = calculateTrajectoryConfidence(points, collisions),
            ballType = ball.type
        )
    }

    // Calcular múltiples trayectorias (bola blanca + objetivos impactados)
    fun calculateMultipleTrajectories(
        cueBall: Ball,
        direction: Float,
        power: Float,
        tableBounds: RectF,
        targetBalls: List<Ball>,
        pockets: List<Pocket>
    ): List<TrajectoryPath> {

        val trajectories = mutableListOf<TrajectoryPath>()

        // Trayectoria principal
        val mainTrajectory = calculateCueBallTrajectory(
            cueBall, direction, power, tableBounds, targetBalls, pockets
        )
        trajectories.add(mainTrajectory)

        // Trayectorias secundarias para bolas impactadas
        for (collision in mainTrajectory.collisions) {
            if (collision.type == CollisionType.BALL && collision.targetBall != null) {
                val impactVelocity = calculateImpactVelocity(collision)

                val secondaryTrajectory = calculateSecondaryBallTrajectory(
                    collision.targetBall!!,
                    impactVelocity,
                    tableBounds,
                    targetBalls.filter { it != collision.targetBall },
                    pockets
                )

                trajectories.add(secondaryTrajectory)
            }
        }

        return trajectories
    }

    // Verificar colisiones con otras bolas
    private fun checkBallCollisions(
        currentPos: PointF,
        nextPos: PointF,
        ballRadius: Float,
        obstacles: List<Ball>
    ): CollisionEvent? {

        for (obstacle in obstacles) {
            val distanceToObstacle = calculateDistance(nextPos, obstacle.position)
            val collisionDistance = ballRadius + obstacle.radius + MIN_COLLISION_DISTANCE

            if (distanceToObstacle <= collisionDistance) {
                // Calcular punto exacto de colisión
                val collisionPoint = calculateCollisionPoint(
                    currentPos, nextPos, obstacle.position, collisionDistance
                )

                val impactAngle = atan2(
                    obstacle.position.y - collisionPoint.y,
                    obstacle.position.x - collisionPoint.x
                )

                return CollisionEvent(
                    point = collisionPoint,
                    type = CollisionType.BALL,
                    targetBall = obstacle,
                    impactAngle = impactAngle,
                    impactForce = calculateImpactForce(currentPos, nextPos)
                )
            }
        }

        return null
    }

    // Verificar colisiones con paredes
    private fun checkWallCollisions(
        currentPos: PointF,
        nextPos: PointF,
        tableBounds: RectF,
        ballRadius: Float
    ): CollisionEvent? {

        val margin = ballRadius + 2f

        // Pared izquierda
        if (nextPos.x <= tableBounds.left + margin && currentPos.x > tableBounds.left + margin) {
            return CollisionEvent(
                point = PointF(tableBounds.left + margin, currentPos.y),
                type = CollisionType.WALL,
                wall = Wall.LEFT
            )
        }

        // Pared derecha
        if (nextPos.x >= tableBounds.right - margin && currentPos.x < tableBounds.right - margin) {
            return CollisionEvent(
                point = PointF(tableBounds.right - margin, currentPos.y),
                type = CollisionType.WALL,
                wall = Wall.RIGHT
            )
        }

        // Pared superior
        if (nextPos.y <= tableBounds.top + margin && currentPos.y > tableBounds.top + margin) {
            return CollisionEvent(
                point = PointF(currentPos.x, tableBounds.top + margin),
                type = CollisionType.WALL,
                wall = Wall.TOP
            )
        }

        // Pared inferior
        if (nextPos.y >= tableBounds.bottom - margin && currentPos.y < tableBounds.bottom - margin) {
            return CollisionEvent(
                point = PointF(currentPos.x, tableBounds.bottom - margin),
                type = CollisionType.WALL,
                wall = Wall.BOTTOM
            )
        }

        return null
    }

    // Verificar si la bola entra en una tronera
    private fun checkPocketCollisions(position: PointF, pockets: List<Pocket>): Pocket? {
        for (pocket in pockets) {
            val distance = calculateDistance(position, pocket.position)
            if (distance <= pocket.radius) {
                return pocket
            }
        }
        return null
    }

    // Aplicar rebote en pared
    private fun applyWallBounce(velocity: PointF, wall: Wall): PointF {
        return when (wall) {
            Wall.LEFT, Wall.RIGHT -> PointF(-velocity.x, velocity.y)
            Wall.TOP, Wall.BOTTOM -> PointF(velocity.x, -velocity.y)
        }
    }

    // Calcular velocidad de impacto para transferencia de momento
    private fun calculateImpactVelocity(collision: CollisionEvent): PointF {
        val baseVelocity = 200f // Velocidad base tras impacto
        val angle = collision.impactAngle
        val force = collision.impactForce

        return PointF(
            cos(angle) * baseVelocity * force,
            sin(angle) * baseVelocity * force
        )
    }

    // Calcular punto exacto de colisión
    private fun calculateCollisionPoint(
        start: PointF,
        end: PointF,
        obstacle: PointF,
        collisionDistance: Float
    ): PointF {

        // Proyectar punto de colisión en la línea de movimiento
        val dx = end.x - start.x
        val dy = end.y - start.y
        val length = sqrt(dx * dx + dy * dy)

        if (length == 0f) return start

        val unitX = dx / length
        val unitY = dy / length

        // Calcular distancia hasta el punto de colisión
        val distanceToObstacle = calculateDistance(start, obstacle)
        val projectionDistance = distanceToObstacle - collisionDistance

        return PointF(
            start.x + unitX * projectionDistance,
            start.y + unitY * projectionDistance
        )
    }

    // Calcular fuerza de impacto
    private fun calculateImpactForce(currentPos: PointF, nextPos: PointF): Float {
        val velocity = calculateDistance(currentPos, nextPos) / config.timeStep
        return (velocity / config.initialVelocity).coerceIn(0.1f, 1f)
    }

    // Calcular confianza de la trayectoria
    private fun calculateTrajectoryConfidence(
        points: List<PointF>,
        collisions: List<CollisionEvent>
    ): Float {
        var confidence = 1f

        // Reducir confianza por cada rebote
        confidence -= collisions.count { it.type == CollisionType.WALL } * 0.1f

        // Reducir confianza por trayectorias muy largas (más incertidumbre)
        if (points.size > 100) {
            confidence -= (points.size - 100) * 0.005f
        }

        // Reducir confianza por colisiones con bolas (transferencia de momento compleja)
        confidence -= collisions.count { it.type == CollisionType.BALL } * 0.2f

        return confidence.coerceIn(0f, 1f)
    }

    // Funciones auxiliares
    internal fun calculateDistance(p1: PointF, p2: PointF): Float {
        return sqrt((p1.x - p2.x).pow(2) + (p1.y - p2.y).pow(2))
    }

    private fun getVelocityMagnitude(velocity: PointF): Float {
        return sqrt(velocity.x.pow(2) + velocity.y.pow(2))
    }

    // Optimizar parámetros de física basado en resultados observados
    fun optimizeParameters(successfulShots: List<TrajectoryData>) {
        if (successfulShots.isEmpty()) return

        // Optimizar fricción
        val avgFriction = successfulShots.map { it.friction }.average().toFloat()
        config.friction = lerp(config.friction, avgFriction, 0.1f)

        // Optimizar coeficiente de rebote
        val avgRestitution = successfulShots.map { it.restitution }.average().toFloat()
        config.restitution = lerp(config.restitution, avgRestitution, 0.1f)

        // Mantener valores en rangos razonables
        config.friction = config.friction.coerceIn(0.95f, 0.995f)
        config.restitution = config.restitution.coerceIn(0.7f, 0.95f)
    }

    // Interpolar entre valores
    private fun lerp(start: Float, end: Float, factor: Float): Float {
        return start + (end - start) * factor
    }

    // Actualizar configuración de física
    fun updateConfig(newConfig: PhysicsConfig) {
        this.config = newConfig
    }

    // Obtener configuración actual
    fun getConfig(): PhysicsConfig = config.copy()

    // Calcular potencia óptima para alcanzar un objetivo
    fun calculateOptimalPower(
        cueBall: Ball,
        target: PointF,
        tableBounds: RectF,
        obstacles: List<Ball> = emptyList()
    ): Float {

        val distance = calculateDistance(cueBall.position, target)
        val baseDistance = sqrt(tableBounds.width().pow(2) + tableBounds.height().pow(2))

        // Potencia base según distancia
        var power = (distance / baseDistance).coerceIn(0.3f, 1f)

        // Ajustar por obstáculos en el camino
        val direction = atan2(
            target.y - cueBall.position.y,
            target.x - cueBall.position.x
        )

        val obstaclesInPath = countObstaclesInPath(cueBall, direction, distance, obstacles)
        power += obstaclesInPath * 0.15f // Más potencia si hay obstáculos

        return power.coerceIn(0.2f, 1f)
    }

    // Contar obstáculos en el camino
    private fun countObstaclesInPath(
        cueBall: Ball,
        direction: Float,
        distance: Float,
        obstacles: List<Ball>
    ): Int {

        val steps = (distance / 10f).toInt()
        var obstacleCount = 0

        for (step in 1..steps) {
            val checkPoint = PointF(
                cueBall.position.x + cos(direction) * step * 10f,
                cueBall.position.y + sin(direction) * step * 10f
            )

            for (obstacle in obstacles) {
                val distanceToObstacle = calculateDistance(checkPoint, obstacle.position)
                if (distanceToObstacle <= (cueBall.radius + obstacle.radius + 20f)) {
                    obstacleCount++
                    break
                }
            }
        }

        return obstacleCount
    }

    // Simular tiro completo para validación
    fun simulateCompleteShot(
        cueBall: Ball,
        direction: Float,
        power: Float,
        tableBounds: RectF,
        allBalls: List<Ball>,
        pockets: List<Pocket>
    ): ShotResult {

        val trajectories = calculateMultipleTrajectories(
            cueBall, direction, power, tableBounds,
            allBalls.filter { it.type != BallType.CUE }, pockets
        )

        val mainTrajectory = trajectories.firstOrNull() ?: return ShotResult.Miss

        // Evaluar resultado del tiro
        return when {
            mainTrajectory.pocketPrediction != null -> {
                // La bola blanca entró en tronera - falta
                ShotResult.Scratch
            }

            trajectories.any { it.pocketPrediction != null && it.ballType != BallType.CUE } -> {
                // Una bola objetivo entró en tronera
                val pocketedBall = trajectories.first {
                    it.pocketPrediction != null && it.ballType != BallType.CUE
                }
                ShotResult.Success(pocketedBall.ballType, pocketedBall.pocketPrediction!!)
            }

            mainTrajectory.collisions.any { it.type == CollisionType.BALL } -> {
                // Tocó una bola pero no embocó
                ShotResult.Contact
            }

            else -> {
                // No tocó ninguna bola
                ShotResult.Miss
            }
        }
    }
}

// Resultado de un tiro simulado
sealed class ShotResult {
    object Miss : ShotResult()                                    // No tocó bolas
    object Contact : ShotResult()                                 // Tocó bola pero no embocó
    object Scratch : ShotResult()                                 // Bola blanca en tronera
    data class Success(val ball: BallType, val pocket: Pocket) : ShotResult()  // Bola embocada
}

// Analizador de tiros para sugerir mejores opciones
class ShotAnalyzer(private val physicsEngine: PhysicsEngine) {

    // Encontrar el mejor tiro posible
    fun findBestShot(
        cueBall: Ball,
        targetBalls: List<Ball>,
        tableBounds: RectF,
        pockets: List<Pocket>,
        gameMode: GameMode = GameMode.EIGHT_BALL
    ): ShotSuggestion? {

        val suggestions = mutableListOf<ShotSuggestion>()

        // Evaluar tiros a cada tronera
        for (pocket in pockets) {
            for (targetBall in getValidTargets(targetBalls, gameMode)) {
                val shotSuggestion = evaluateShot(
                    cueBall, targetBall, pocket, tableBounds,
                    targetBalls.filter { it != targetBall }
                )

                if (shotSuggestion != null) {
                    suggestions.add(shotSuggestion)
                }
            }
        }

        // Devolver el mejor tiro (mayor puntuación)
        return suggestions.maxByOrNull { it.score }
    }

    // Evaluar un tiro específico
    private fun evaluateShot(
        cueBall: Ball,
        targetBall: Ball,
        pocket: Pocket,
        tableBounds: RectF,
        obstacles: List<Ball>
    ): ShotSuggestion? {

        // Calcular ángulo requerido para que la bola objetivo vaya a la tronera
        val ballToPocketAngle = atan2(
            pocket.position.y - targetBall.position.y,
            pocket.position.x - targetBall.position.x
        )

        // Calcular punto de contacto en la bola objetivo
        val contactPoint = PointF(
            targetBall.position.x - cos(ballToPocketAngle) * targetBall.radius * 2,
            targetBall.position.y - sin(ballToPocketAngle) * targetBall.radius * 2
        )

        // Calcular dirección requerida para la bola blanca
        val cueDirection = atan2(
            contactPoint.y - cueBall.position.y,
            contactPoint.x - cueBall.position.x
        )

        // Calcular potencia óptima
        val power = physicsEngine.calculateOptimalPower(
            cueBall, contactPoint, tableBounds, obstacles
        )

        // Simular el tiro
        val result = physicsEngine.simulateCompleteShot(
            cueBall, cueDirection, power, tableBounds,
            obstacles + targetBall, listOf(pocket)
        )

        // Calcular puntuación del tiro
        val score = calculateShotScore(
            cueBall, targetBall, pocket, cueDirection, power, result, obstacles
        )

        if (score > 0.3f) { // Solo sugerir tiros con puntuación razonable
            return ShotSuggestion(
                targetBall = targetBall,
                pocket = pocket,
                direction = cueDirection,
                power = power,
                score = score,
                difficulty = calculateDifficulty(cueBall, targetBall, pocket, obstacles),
                description = generateShotDescription(targetBall, pocket, score)
            )
        }

        return null
    }

    // Calcular puntuación de un tiro
    private fun calculateShotScore(
        cueBall: Ball,
        targetBall: Ball,
        pocket: Pocket,
        direction: Float,
        power: Float,
        result: ShotResult,
        obstacles: List<Ball>
    ): Float {

        var score = 0f

        // Puntuación base según resultado
        score += when (result) {
            is ShotResult.Success -> 1f
            is ShotResult.Contact -> 0.3f
            is ShotResult.Scratch -> -0.5f
            is ShotResult.Miss -> 0f
        }

        // Bonificación por distancia corta
        val distance = physicsEngine.calculateDistance(cueBall.position, targetBall.position)
        val maxDistance = 500f // Distancia máxima esperada
        score += (1f - distance / maxDistance) * 0.2f

        // Penalización por obstáculos
        val obstacleCount = countObstaclesInShot(cueBall, targetBall, obstacles)
        score -= obstacleCount * 0.15f

        // Bonificación por ángulo favorable
        val angleBonus = calculateAngleBonus(cueBall, targetBall, pocket)
        score += angleBonus * 0.3f

        // Penalización por potencia alta (menos precisión)
        if (power > 0.8f) {
            score -= (power - 0.8f) * 0.2f
        }

        return score.coerceIn(0f, 1f)
    }

    // Calcular dificultad del tiro
    private fun calculateDifficulty(
        cueBall: Ball,
        targetBall: Ball,
        pocket: Pocket,
        obstacles: List<Ball>
    ): ShotDifficulty {

        val distance = physicsEngine.calculateDistance(cueBall.position, targetBall.position)
        val obstacleCount = countObstaclesInShot(cueBall, targetBall, obstacles)
        val angle = calculateShotAngle(cueBall, targetBall, pocket)

        val difficultyScore = (distance / 300f) + (obstacleCount * 0.3f) + (abs(angle) / PI * 0.4f).toFloat()

        return when {
            difficultyScore < 0.3f -> ShotDifficulty.EASY
            difficultyScore < 0.6f -> ShotDifficulty.MEDIUM
            difficultyScore < 0.9f -> ShotDifficulty.HARD
            else -> ShotDifficulty.EXPERT
        }
    }

    // Obtener objetivos válidos según el modo de juego
    private fun getValidTargets(balls: List<Ball>, gameMode: GameMode): List<Ball> {
        return when (gameMode) {
            GameMode.EIGHT_BALL -> {
                // En 8-ball, primero sólidas o rayadas, luego la 8
                val solids = balls.filter { it.type in BallType.SOLID_1..BallType.SOLID_7 }
                val stripes = balls.filter { it.type in BallType.STRIPE_9..BallType.STRIPE_15 }
                val eightBall = balls.filter { it.type == BallType.EIGHT_BALL }

                when {
                    solids.isNotEmpty() && stripes.isNotEmpty() -> solids + stripes
                    solids.isEmpty() && stripes.isNotEmpty() -> eightBall
                    stripes.isEmpty() && solids.isNotEmpty() -> eightBall
                    else -> eightBall
                }
            }
            GameMode.NINE_BALL -> {
                // En 9-ball, solo la bola con número más bajo
                balls.filter { it.type != BallType.CUE }
                    .minByOrNull { it.type.ordinal }
                    ?.let { listOf(it) } ?: emptyList()
            }
            else -> balls.filter { it.type != BallType.CUE }
        }
    }

    // Contar obstáculos en el tiro
    private fun countObstaclesInShot(
        cueBall: Ball,
        targetBall: Ball,
        obstacles: List<Ball>
    ): Int {
        var count = 0
        val direction = atan2(
            targetBall.position.y - cueBall.position.y,
            targetBall.position.x - cueBall.position.x
        )
        val distance = physicsEngine.calculateDistance(cueBall.position, targetBall.position)

        for (obstacle in obstacles) {
            if (isObstacleInPath(cueBall.position, direction, distance, obstacle)) {
                count++
            }
        }

        return count
    }

    // Verificar si un obstáculo está en el camino
    private fun isObstacleInPath(
        start: PointF,
        direction: Float,
        distance: Float,
        obstacle: Ball
    ): Boolean {

        // Calcular punto más cercano en la línea al obstáculo
        val dx = cos(direction)
        val dy = sin(direction)

        val t = ((obstacle.position.x - start.x) * dx + (obstacle.position.y - start.y) * dy) /
                (dx * dx + dy * dy)

        if (t < 0 || t > distance) return false

        val closestPoint = PointF(
            start.x + t * dx,
            start.y + t * dy
        )

        val distanceToLine = physicsEngine.calculateDistance(closestPoint, obstacle.position)
        return distanceToLine <= obstacle.radius + 15f // Margen de seguridad
    }

    // Calcular bonificación por ángulo
    private fun calculateAngleBonus(cueBall: Ball, targetBall: Ball, pocket: Pocket): Float {
        val angle = calculateShotAngle(cueBall, targetBall, pocket)

        // Ángulos entre 30° y 150° son más favorables
        val favorableAngle = abs(angle - PI/2).toFloat()
        return (1f - favorableAngle / (PI/2).toFloat()).coerceIn(0f, 1f)
    }

    // Calcular ángulo del tiro
    private fun calculateShotAngle(cueBall: Ball, targetBall: Ball, pocket: Pocket): Float {
        val toTarget = atan2(
            targetBall.position.y - cueBall.position.y,
            targetBall.position.x - cueBall.position.x
        )

        val toPocket = atan2(
            pocket.position.y - targetBall.position.y,
            pocket.position.x - targetBall.position.x
        )

        return abs(toTarget - toPocket)
    }

    // Generar descripción del tiro
    private fun generateShotDescription(
        targetBall: Ball,
        pocket: Pocket,
        score: Float
    ): String {
        val ballName = when (targetBall.type) {
            BallType.SOLID_1 -> "Amarilla"
            BallType.SOLID_2 -> "Azul"
            BallType.SOLID_3 -> "Roja"
            BallType.EIGHT_BALL -> "Negra"
            else -> "Bola ${targetBall.type.ordinal}"
        }

        val confidence = when {
            score > 0.8f -> "Excelente"
            score > 0.6f -> "Bueno"
            score > 0.4f -> "Regular"
            else -> "Difícil"
        }

        return "$ballName a ${pocket.name} - $confidence"
    }
}

// Sugerencia de tiro
data class ShotSuggestion(
    val targetBall: Ball,
    val pocket: Pocket,
    val direction: Float,
    val power: Float,
    val score: Float,
    val difficulty: ShotDifficulty,
    val description: String
)

// Niveles de dificultad
enum class ShotDifficulty {
    EASY, MEDIUM, HARD, EXPERT
}