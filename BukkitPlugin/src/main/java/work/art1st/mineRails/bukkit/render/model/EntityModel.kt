package work.art1st.mineRails.bukkit.render.model

import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.util.Vector
import org.joml.Vector3d
import org.joml.Vector3dc
import work.art1st.mineRails.bukkit.render.Animation
import work.art1st.mineRails.core.api.physics.World

abstract class EntityModel internal constructor(
    world: World,
    state: ModelState,
    attachedModels: Collection<AbstractModel>,
    animation: Map<String, Animation>,
    protected open var entity: Entity? = null
) : AbstractModel(world, state, attachedModels, animation) {
    abstract fun spawnEntity()

    override fun updateState(parentRelativeTransform: CompactTransform, rootTransform: CompactTransform, rootVelocity: Vector3dc, transformDirty: Boolean) {
        super.updateState(parentRelativeTransform, rootTransform, rootVelocity, transformDirty)
        entity?.let { syncEntity(it, rootVelocity) }
    }

    protected open fun syncEntity(entity: Entity, rootVelocity: Vector3dc) {
        val location = entityLocation(entity)
        entity.teleport(location)
        entity.setRotation(location.yaw, location.pitch)
        if (entity is LivingEntity) {
            entity.setBodyYaw(location.yaw)
        }
        entity.velocity = Vector(rootVelocity.x(), rootVelocity.y(), rootVelocity.z())
        // Ignore roll
    }

    protected open fun entityLocation(entity: Entity): Location {
        val (yaw, pitch) = entityYawPitch()
        return Location(entity.world, state.x, state.y, state.z, yaw, pitch)
    }

    protected fun entityYawPitch(): Pair<Float, Float> {
        val forward = Vector3d(0.0, 0.0, 1.0)
        state.worldTransform.rotation.transform(forward)
        val yaw = Math.toDegrees(kotlin.math.atan2(-forward.x(), forward.z())).toFloat()
        val pitch = Math.toDegrees(kotlin.math.asin(forward.y().coerceIn(-1.0, 1.0))).toFloat()
        return yaw to pitch
    }

    override fun destroy() {
        super.destroy()
        entity?.remove()
    }
}