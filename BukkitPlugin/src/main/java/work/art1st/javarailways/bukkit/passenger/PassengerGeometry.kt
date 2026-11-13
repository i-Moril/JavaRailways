package work.art1st.javarailways.bukkit.passenger

import org.joml.Quaterniond
import org.joml.Vector3d
import work.art1st.javarailways.bukkit.render.model.CompactTransform
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Detached coordinates; no live model/JOML reference crosses the model owner boundary. */
internal data class PassengerPoint(val x: Double, val y: Double, val z: Double) {
    fun vector() = Vector3d(x, y, z)
    companion object {
        fun from(vector: org.joml.Vector3dc) = PassengerPoint(vector.x(), vector.y(), vector.z())
    }
}

internal data class PassengerPose(
    val position: PassengerPoint,
    private val qx: Double,
    private val qy: Double,
    private val qz: Double,
    private val qw: Double
) {
    fun transform() = CompactTransform(position.vector(), Quaterniond(qx, qy, qz, qw))
    fun toWorld(point: PassengerPoint): PassengerPoint =
        PassengerPoint.from(Quaterniond(qx, qy, qz, qw).transform(point.vector()).add(position.vector()))
    fun toLocal(point: PassengerPoint): PassengerPoint =
        PassengerPoint.from(Quaterniond(qx, qy, qz, qw).conjugate().transform(point.vector().sub(position.vector())))
    fun localDirection(direction: PassengerPoint): PassengerPoint =
        PassengerPoint.from(Quaterniond(qx, qy, qz, qw).conjugate().transform(direction.vector()))

    companion object {
        fun capture(transform: CompactTransform) = PassengerPose(PassengerPoint.from(transform.position),
            transform.rotation.x(), transform.rotation.y(), transform.rotation.z(), transform.rotation.w())
    }
}

internal data class PassengerSurface(
    val id: PassengerSurfaceSource,
    val pose: PassengerPose,
    val halfX: Double,
    val halfZ: Double
) {
    fun contains(point: PassengerPoint): Boolean =
        abs(point.x) <= halfX + GEOMETRY_EPSILON && abs(point.z) <= halfZ + GEOMETRY_EPSILON

    fun contact(worldFeet: PassengerPoint): PassengerPoint? {
        val local = pose.toLocal(worldFeet)
        return if (contains(local) && abs(local.y) <= CONTACT_HEIGHT_TOLERANCE)
            PassengerPoint(local.x, 0.0, local.z) else null
    }

    /** Project the horizontal view direction onto this support plane, keeping walking speed constant. */
    fun direction(worldDirection: PassengerPoint): PassengerPoint {
        val local = pose.localDirection(worldDirection)
        val length = sqrt(local.x * local.x + local.z * local.z)
        return if (length <= GEOMETRY_EPSILON) PassengerPoint(0.0, 0.0, 0.0)
        else PassengerPoint(local.x / length, 0.0, local.z / length)
    }

    fun distanceToExit(start: PassengerPoint, direction: PassengerPoint): Double {
        fun axis(position: Double, delta: Double, half: Double): Double = when {
            delta > GEOMETRY_EPSILON -> (half - position) / delta
            delta < -GEOMETRY_EPSILON -> (-half - position) / delta
            else -> Double.POSITIVE_INFINITY
        }
        return min(axis(start.x, direction.x, halfX), axis(start.z, direction.z, halfZ)).coerceAtLeast(0.0)
    }
}

internal data class PassengerWalk(val surface: PassengerSurface?, val local: PassengerPoint, val worldFeet: PassengerPoint)

internal object PassengerWalking {
    const val SPEED_METERS_PER_SECOND = 4.3
    const val DISTANCE_PER_TICK = SPEED_METERS_PER_SECOND / 20.0

    fun viewDirection(yawDegrees: Float, forward: Int, sideways: Int): PassengerPoint {
        val length = sqrt((forward * forward + sideways * sideways).toDouble())
        if (length == 0.0) return PassengerPoint(0.0, 0.0, 0.0)
        val yaw = Math.toRadians(yawDegrees.toDouble())
        // Bukkit yaw 0 faces +Z; positive sideways means D (world -X at yaw 0).
        return PassengerPoint((-sin(yaw) * forward - cos(yaw) * sideways) / length, 0.0,
            (cos(yaw) * forward - sin(yaw) * sideways) / length)
    }

    /** Walk the entire segment. An endpoint in B does not authorize crossing a gap between A and B. */
    fun advance(
        surfaces: List<PassengerSurface>,
        current: PassengerSurface,
        position: PassengerPoint,
        worldDirection: PassengerPoint,
        distance: Double = DISTANCE_PER_TICK
    ): PassengerWalk {
        var surface = current
        var local = position
        var remaining = distance
        val visited = hashSetOf(current.id)
        while (true) {
            val direction = surface.direction(worldDirection)
            if (direction.x == 0.0 && direction.z == 0.0)
                return PassengerWalk(surface, local, surface.pose.toWorld(local))
            val exit = surface.distanceToExit(local, direction)
            if (remaining <= exit + GEOMETRY_EPSILON) {
                local = move(local, direction, remaining)
                return PassengerWalk(surface, local, surface.pose.toWorld(local))
            }
            val boundary = move(local, direction, exit)
            val worldBoundary = surface.pose.toWorld(boundary)
            remaining -= exit
            val next = surfaces.firstOrNull { candidate ->
                if (candidate.id in visited) return@firstOrNull false
                val entry = candidate.pose.toLocal(worldBoundary)
                // Only floating point tolerance: no step-up or gap bridging.
                abs(entry.y) <= GEOMETRY_EPSILON && candidate.contains(entry) &&
                    candidate.distanceToExit(entry, candidate.direction(worldDirection)) > GEOMETRY_EPSILON
            }
            if (next == null) {
                val outside = move(boundary, direction, remaining)
                return PassengerWalk(null, outside, surface.pose.toWorld(outside))
            }
            surface = next
            val entry = next.pose.toLocal(worldBoundary)
            local = PassengerPoint(entry.x, 0.0, entry.z)
            visited += next.id
        }
    }

    private fun move(start: PassengerPoint, direction: PassengerPoint, distance: Double) =
        PassengerPoint(start.x + direction.x * distance, 0.0, start.z + direction.z * distance)
}

private const val GEOMETRY_EPSILON = 1.0e-7
// The player must be at the collision top, rather than anywhere in the old pre-activation volume.
private const val CONTACT_HEIGHT_TOLERANCE = 0.15
