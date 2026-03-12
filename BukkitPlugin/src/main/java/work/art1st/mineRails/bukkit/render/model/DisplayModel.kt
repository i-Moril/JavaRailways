package work.art1st.mineRails.bukkit.render.model

import org.bukkit.entity.Display
import org.joml.Vector3dc
import work.art1st.mineRails.bukkit.render.Animation
import work.art1st.mineRails.core.api.physics.World
import work.art1st.mineRails.core.api.util.annotation.MainThread

class DisplayModel internal constructor(
    world: World,
    state: ModelState,
    attachedModels: Collection<AbstractModel>,
    animation: Map<String, Animation>,
    private val entity: Display
) : AbstractModel(world, state, attachedModels, animation) {
    init {
        entity.isPersistent = false
    }

    override fun updateState(parentRelativeTransform: CompactTransform, rootTransform: CompactTransform, rootVelocity: Vector3dc) {
        super.updateState(parentRelativeTransform, rootTransform, rootVelocity)
        entity.transformation = state.rootRelativeTransform.toBukkitTransformation()
        // Display entity is attached to the root entity. No need to teleport.
        // Also ignore velocity due to attachment
    }

    @MainThread
    override fun destroy() {
        super.destroy()
        entity.remove()
    }
}
