package work.art1st.mineRails.bukkit.render.model

import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.util.Vector
import org.joml.Vector3dc
import work.art1st.mineRails.bukkit.render.Animation
import work.art1st.mineRails.core.api.physics.World

// TODO: 展示实体特判
open class EntityModel internal constructor(
    world: World,
    state: ModelState,
    attachedModels: Collection<EntityModel>,
    animation: Map<String, Animation>,
    private var entity: Entity? = null
) : AbstractModel(world, state, attachedModels, animation) {

    override fun updateState(parentRelativeTransform: CompactTransform, rootTransform: CompactTransform, rootVelocity: Vector3dc) {
        super.updateState(parentRelativeTransform, rootTransform, rootVelocity)
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