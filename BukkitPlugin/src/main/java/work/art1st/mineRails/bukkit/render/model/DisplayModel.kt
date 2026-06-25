package work.art1st.mineRails.bukkit.render.model

import org.bukkit.Location
import org.bukkit.entity.Display
import org.joml.Quaternionf
import org.joml.Vector3dc
import org.joml.Vector3f
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
        // Teleport entity to world position for correct chunk-based visibility,
        // then use transformation only for rotation/scale (zero translation).
        val worldPos = state.worldTransform.position
        entity.teleport(Location(entity.world, worldPos.x(), worldPos.y(), worldPos.z()))
        entity.transformation = org.bukkit.util.Transformation(
            Vector3f(0f, 0f, 0f),
            Quaternionf(1f, 0f, 0f, 0f),
            state.rootRelativeTransform.scale.get(Vector3f()),
            state.rootRelativeTransform.rotation.get(Quaternionf())
        )
    }

    @MainThread
    override fun destroy() {
        super.destroy()
        entity.remove()
    }
}
