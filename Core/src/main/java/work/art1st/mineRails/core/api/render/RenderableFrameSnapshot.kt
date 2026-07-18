package work.art1st.mineRails.core.api.render

import org.joml.Vector3d
import org.joml.Vector3dc

/** Immutable value vector safe to publish across simulation and platform threads. */
data class Vector3Snapshot(
    val x: Double,
    val y: Double,
    val z: Double
) {
    init {
        require(x.isFinite() && y.isFinite() && z.isFinite()) { "Vector3Snapshot components must be finite" }
    }

    fun toVector3d(): Vector3d = Vector3d(x, y, z)

    companion object {
        fun capture(vector: Vector3dc): Vector3Snapshot = Vector3Snapshot(vector.x(), vector.y(), vector.z())
    }
}

/**
 * One atomically published renderable state. It deliberately retains neither live JOML vectors nor
 * Bukkit/Core world objects.
 */
data class RenderableFrameSnapshot(
    val position: Vector3Snapshot,
    val direction: Vector3Snapshot,
    val normal: Vector3Snapshot,
    val velocity: Vector3Snapshot
) {
    companion object {
        fun capture(renderable: Renderable): RenderableFrameSnapshot = RenderableFrameSnapshot(
            position = Vector3Snapshot.capture(renderable.position),
            direction = Vector3Snapshot.capture(renderable.direction),
            normal = Vector3Snapshot.capture(renderable.normal),
            velocity = Vector3Snapshot.capture(renderable.velocity)
        )
    }
}
