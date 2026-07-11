package work.art1st.mineRails.bukkit.util

import org.joml.Vector3d
import org.joml.Vector3dc
import work.art1st.mineRails.basic.WayPoint
import work.art1st.mineRails.core.api.util.annotation.Heavy
import work.art1st.mineRails.core.physics.rail.CoreRail
import work.art1st.mineRails.core.util.ThreadGuard
import java.util.UUID

object HeavyRailQueries {
    data class NearestRailMatch(
        val railId: UUID,
        val arcLength: Double,
        val distance: Double
    )

    data class WayPointMatch(
        val wayPoint: WayPoint,
        val distance: Double
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
        targetPosition: Vector3dc
    ): WayPointMatch? {
        ThreadGuard.ensureOffMainThread("HeavyRailQueries.findNearestWayPoint")
        return wayPoints.asSequence()
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
    ): UUID? {
        ThreadGuard.ensureOffMainThread("HeavyRailQueries.findLookedAtRail")
        val normalizedDirection = Vector3d(direction)
        if (normalizedDirection.lengthSquared() < 1e-12) return null
        normalizedDirection.normalize()

        var bestRailId: UUID? = null
        var bestScore = Double.POSITIVE_INFINITY
        candidates.forEach { rail ->
            rail.curve.stream().use { stream ->
                val iterator = stream.iterator()
                while (iterator.hasNext()) {
                    val point = iterator.next()
                    val toPoint = Vector3d(point.position).sub(origin)
                    val projection = toPoint.dot(normalizedDirection)
                    if (projection < 0.0 || projection > maxDistance) continue
                    val closest = Vector3d(origin).fma(projection, normalizedDirection)
                    val distance = closest.distance(point.position)
                    if (distance > hitRadius) continue
                    val score = projection + distance * 8.0
                    if (score < bestScore) {
                        bestScore = score
                        bestRailId = rail.id
                    }
                }
            }
        }
        return bestRailId
    }
}
