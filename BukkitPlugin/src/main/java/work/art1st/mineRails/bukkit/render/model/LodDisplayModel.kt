package work.art1st.mineRails.bukkit.render.model

import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.joml.Vector3dc
import work.art1st.mineRails.bukkit.BukkitPlugin
import work.art1st.mineRails.bukkit.extension.bukkitWorldData
import work.art1st.mineRails.bukkit.render.Animation
import work.art1st.mineRails.bukkit.render.asVector3d
import work.art1st.mineRails.bukkit.render.chunk.railViewDistanceSettings
import work.art1st.mineRails.core.api.physics.World

/**
 * Note: Ensure entities are invisible by default. Will be managed by LodDisplayModel.
 *       Ensure the entities are mounted to the root entity.
 */
class LodDisplayModel internal constructor(
    world: World,
    state: ModelState,
    attachedModels: Collection<AbstractModel>,
    animation: Map<String, Animation>,
    private val loEntity: Display?,
    private val hiEntity: Display?
) : AbstractModel(world, state, attachedModels, animation) {
    init {
        loEntity?.isPersistent = false
        hiEntity?.isPersistent = false
    }

    override fun updateState(parentRelativeTransform: CompactTransform, rootTransform: CompactTransform, rootVelocity: Vector3dc, transformDirty: Boolean) {
        super.updateState(parentRelativeTransform, rootTransform, rootVelocity, transformDirty)
        val worldPos = state.worldTransform.position
        val bukkitWorld = world.bukkitWorldData.bukkitWorld
        val loc = org.bukkit.Location(bukkitWorld, worldPos.x(), worldPos.y(), worldPos.z())
        loEntity?.let {
            it.teleport(loc)
            if (transformDirty) {
                it.transformation = org.bukkit.util.Transformation(
                    org.joml.Vector3f(0f, 0f, 0f),
                    org.joml.Quaternionf(),
                    state.rootRelativeTransform.scale.get(org.joml.Vector3f()),
                    state.rootRelativeTransform.rotation.get(org.joml.Quaternionf())
                )
            }
        }
        hiEntity?.let {
            it.teleport(loc)
            if (transformDirty) {
                it.transformation = org.bukkit.util.Transformation(
                    org.joml.Vector3f(0f, 0f, 0f),
                    org.joml.Quaternionf(),
                    state.rootRelativeTransform.scale.get(org.joml.Vector3f()),
                    state.rootRelativeTransform.rotation.get(org.joml.Quaternionf())
                )
            }
        }
    }

    override fun destroy() {
        super.destroy()
        loEntity?.remove()
        hiEntity?.remove()
    }

    fun updatePlayerVisibility(player: Player) {
        val distance = player.location.asVector3d().distance(state.position)
        if (distance > player.railViewDistanceSettings.lodThreshold) {
            loEntity?.let {
                player.showEntity(BukkitPlugin.INSTANCE, it)
            }
            hiEntity?.let {
                player.hideEntity(BukkitPlugin.INSTANCE, it)
            }
        } else {
            hiEntity?.let {
                player.showEntity(BukkitPlugin.INSTANCE, it)
            }
            loEntity?.let {
                player.hideEntity(BukkitPlugin.INSTANCE, it)
            }
        }
    }

}
