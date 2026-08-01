package work.art1st.javarailways.bukkit.util

import org.joml.Vector3d
import org.joml.Vector3dc
import work.art1st.javarailways.basic.WayPoint
import work.art1st.javarailways.core.api.util.annotation.Heavy
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.util.ThreadGuard
import java.util.UUID

object HeavyRailQueries {
    const val DEFAULT_TARGET_MAX_DISTANCE = 96.0
    const val DEFAULT_TARGET_HIT_RADIUS = 1.5

    data class NearestRailMatch(
        val railId: UUID,
        val arcLength: Double,
        val distance: Double
    )

    data class WayPointMatch(
        val wayPoint: WayPoint,
        val distance: Double
    )

    data class LookedAtRailMatch(
        val railId: UUID,
        val arcLength: Double,
        val position: Vector3d,
        val distanceToRay: Double
    )

    @OptIn(Heavy::class)
    fun findNearestRail(rails: Collection<CoreRail>, position: Vector3dc): NearestRailMatch? {
        ThreadGuard.ensureOffMainThread("HeavyRailQueries.findNearestRail")
        return rails.asSequence()
            .map { rail ->
                val nearestPoint = rail.curve.getNearestPoint(position)
                NearestRailMatch(
                    railId = rail.id,
                    arcLength = nearestPoint.arcLength,
                    distance = nearestPoint.position.distance(position)
                )
            }
            .minByOrNull { it.distance }
    }

    fun findNearestWayPoint(
        wayPoints: Collection<WayPoint>,
        railsById: Map<UUID, CoreRail>,
        targetPosition: Vector3dc,
        requiredRailId: UUID? = null
    ): WayPointMatch? {
        ThreadGuard.ensureOffMainThread("HeavyRailQueries.findNearestWayPoint")
        return wayPoints.asSequence()
            .filter { requiredRailId == null || it.railId == requiredRailId }
            .mapNotNull { wayPoint ->
                val rail = railsById[wayPoint.railId] ?: return@mapNotNull null
                val point = rail.curve.getPointAt(wayPoint.arcLength)
                WayPointMatch(wayPoint, point.position.distance(targetPosition))
            }
            .minByOrNull { it.distance }
    }

    @OptIn(Heavy::class)
    fun findLookedAtRail(
        candidates: Collection<CoreRail>,
        origin: Vector3dc,
        direction: Vector3dc,
        maxDistance: Double,
        hitRadius: Double
    ): UUID? = findLookedAtRailPoint(candidates, origin, direction, maxDistance, hitRadius)?.railId

    @OptIn(Heavy::class)
    fun findLookedAtRailPoint(
        candidates: Collection<CoreRail>,
        origin: Vector3dc,
        direction: Vector3dc,
        maxDistance: Double,
        hitRadius: Double
    ): LookedAtRailMatch? {
        ThreadGuard.ensureOffMainThread("HeavyRailQueries.findLookedAtRailPoint")
        val normalizedDirection = Vector3d(direction)
        if (normalizedDirection.lengthSquared() < 1e-12) return null
        normalizedDirection.normalize()

        fun score(position: Vector3dc, enforceRadius: Boolean = true): Pair<Double, Double>? {
            val toPoint = Vector3d(position).sub(origin)
            val projection = toPoint.dot(normalizedDirection)
            if (projection !in 0.0..maxDistance) return null
            val closest = Vector3d(origin).fma(projection, normalizedDirection)
            val distance = closest.distance(position)
            if (enforceRadius && distance > hitRadius) return null
            return (projection + distance * 8.0) to distance
        }

        var best: LookedAtRailMatch? = null
        var bestScore = Double.POSITIVE_INFINITY
        candidates.forEach { rail ->
            var seedArc: Double? = null
            var seedScore = Double.POSITIVE_INFINITY
            rail.curve.stream().use { stream ->
                val iterator = stream.iterator()
                while (iterator.hasNext()) {
                    val point = iterator.next()
                    val candidateScore = score(point.position)?.first ?: continue
                    if (candidateScore < seedScore) {
                        seedScore = candidateScore
                        seedArc = point.arcLength
                    }
                }
            }
            val initialArc = seedArc ?: return@forEach
            var low = (initialArc - 2.0).coerceAtLeast(0.0)
            var high = (initialArc + 2.0).coerceAtMost(rail.curve.totalArcLength)
            repeat(28) {
                val first = low + (high - low) / 3.0
                val second = high - (high - low) / 3.0
                val firstScore = score(rail.curve.getPointAt(first).position, enforceRadius = false)?.first
                    ?: Double.POSITIVE_INFINITY
                val secondScore = score(rail.curve.getPointAt(second).position, enforceRadius = false)?.first
                    ?: Double.POSITIVE_INFINITY
                if (firstScore <= secondScore) high = second else low = first
            }
            val refinedArc = (low + high) / 2.0
            val point = rail.curve.getPointAt(refinedArc)
            val (refinedScore, distance) = score(point.position) ?: return@forEach
            if (refinedScore < bestScore) {
                bestScore = refinedScore
                best = LookedAtRailMatch(rail.id, refinedArc, Vector3d(point.position), distance)
            }
        }
        return best
    }
}
