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

    // P4: transformDirty 控制是否重设 transformation（teleport 总是执行）
    override fun updateState(parentRelativeTransform: CompactTransform, rootTransform: CompactTransform, rootVelocity: Vector3dc, transformDirty: Boolean) {
        super.updateState(parentRelativeTransform, rootTransform, rootVelocity, transformDirty)
        val worldPos = state.worldTransform.position
        entity.teleport(Location(entity.world, worldPos.x(), worldPos.y(), worldPos.z()))
        if (transformDirty) {
            entity.transformation = org.bukkit.util.Transformation(
                Vector3f(0f, 0f, 0f),
                Quaternionf(1f, 0f, 0f, 0f),
                state.rootRelativeTransform.scale.get(Vector3f()),
                state.rootRelativeTransform.rotation.get(Quaternionf())
            )
        }
    }

    @MainThread
    override fun destroy() {
        super.destroy()
        entity.remove()
    }
}
