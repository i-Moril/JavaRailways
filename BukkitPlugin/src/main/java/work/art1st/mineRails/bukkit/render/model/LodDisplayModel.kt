package work.art1st.mineRails.bukkit.render.model

import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.joml.Vector3dc
import work.art1st.mineRails.bukkit.BukkitPlugin
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

    override fun updateState(parentRelativeTransform: CompactTransform, rootTransform: CompactTransform, rootVelocity: Vector3dc) {
        super.updateState(parentRelativeTransform, rootTransform, rootVelocity)
        loEntity?.transformation = state.rootRelativeTransform.toBukkitTransformation()
        hiEntity?.transformation = state.rootRelativeTransform.toBukkitTransformation()
        // Display entity is attached to the root entity. No need to teleport.
        // Also ignore velocity due to attachment
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
                player.showEntity(BukkitPlugin, it)
            }
            hiEntity?.let {
                player.hideEntity(BukkitPlugin, it)
            }
        } else {
            hiEntity?.let {
                player.showEntity(BukkitPlugin, it)
            }
            loEntity?.let {
                player.hideEntity(BukkitPlugin, it)
            }
        }
    }
}
