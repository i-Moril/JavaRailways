package work.art1st.javarailways.core.api.render

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
 * Flat value-only render state. Keeping vector components inline avoids four short-lived value
 * objects for every car and bogie on every physics frame.
 */
data class RenderableFrameSnapshot(
    val positionX: Double,
    val positionY: Double,
    val positionZ: Double,
    val directionX: Double,
    val directionY: Double,
    val directionZ: Double,
    val normalX: Double,
    val normalY: Double,
    val normalZ: Double,
    val velocityX: Double,
    val velocityY: Double,
    val velocityZ: Double
) {
    init {
        require(
            positionX.isFinite() && positionY.isFinite() && positionZ.isFinite() &&
                directionX.isFinite() && directionY.isFinite() && directionZ.isFinite() &&
                normalX.isFinite() && normalY.isFinite() && normalZ.isFinite() &&
                velocityX.isFinite() && velocityY.isFinite() && velocityZ.isFinite()
        ) { "RenderableFrameSnapshot components must be finite" }
    }

    fun positionVector(): Vector3d = Vector3d(positionX, positionY, positionZ)
    fun directionVector(): Vector3d = Vector3d(directionX, directionY, directionZ)
    fun normalVector(): Vector3d = Vector3d(normalX, normalY, normalZ)
    fun velocityVector(): Vector3d = Vector3d(velocityX, velocityY, velocityZ)
    fun positionValue(): Vector3Snapshot = Vector3Snapshot(positionX, positionY, positionZ)

    companion object {
        fun create(
            position: Vector3Snapshot,
            direction: Vector3Snapshot,
            normal: Vector3Snapshot,
            velocity: Vector3Snapshot
        ): RenderableFrameSnapshot = RenderableFrameSnapshot(
            position.x, position.y, position.z,
            direction.x, direction.y, direction.z,
            normal.x, normal.y, normal.z,
            velocity.x, velocity.y, velocity.z
        )

        fun capture(renderable: Renderable): RenderableFrameSnapshot {
            val position = renderable.position
            val direction = renderable.direction
            val normal = renderable.normal
            val velocity = renderable.velocity
            return RenderableFrameSnapshot(
                positionX = position.x(),
                positionY = position.y(),
                positionZ = position.z(),
                directionX = direction.x(),
                directionY = direction.y(),
                directionZ = direction.z(),
                normalX = normal.x(),
                normalY = normal.y(),
                normalZ = normal.z(),
                velocityX = velocity.x(),
                velocityY = velocity.y(),
                velocityZ = velocity.z()
            )
        }
    }
}
