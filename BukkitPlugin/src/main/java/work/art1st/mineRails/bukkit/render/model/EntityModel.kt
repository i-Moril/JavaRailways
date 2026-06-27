package work.art1st.mineRails.bukkit.render.model

import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.util.Vector
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
        entity?.let {
            it.teleport(Location(it.world, state.x, state.y, state.z, state.yaw.toFloat(), state.pitch.toFloat()))
            it.velocity = Vector(rootVelocity.x(), rootVelocity.y(), rootVelocity.z())
            // Ignore roll
        }
    }

    override fun destroy() {
        super.destroy()
        entity?.remove()
    }
}