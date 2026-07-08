package work.art1st.mineRails.bukkit.render.model

import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.joml.Vector3dc
import work.art1st.mineRails.bukkit.BukkitPlugin
import work.art1st.mineRails.bukkit.extension.bukkitWorldData
import work.art1st.mineRails.bukkit.render.Animation
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
    private var mountedToRoot: Boolean = false

    init {
        loEntity?.isPersistent = false
        loEntity?.configureTrainDisplaySmoothing()
        hiEntity?.isPersistent = false
        hiEntity?.configureTrainDisplaySmoothing()
    }

    fun mountTo(parent: Entity) {
        var mountedAny = false
        loEntity?.let {
            if (it.vehicle != parent) {
                it.vehicle?.removePassenger(it)
                parent.addPassenger(it)
                mountedAny = true
            }
        }
        hiEntity?.let {
            if (it.vehicle != parent) {
                it.vehicle?.removePassenger(it)
                parent.addPassenger(it)
                mountedAny = true
            }
        }
        if (mountedAny) {
            RenderVerificationStats.onLodMount()
        }
        mountedToRoot = true
    }

    override fun updateState(parentRelativeTransform: CompactTransform, rootTransform: CompactTransform, rootVelocity: Vector3dc, transformDirty: Boolean) {
        super.updateState(parentRelativeTransform, rootTransform, rootVelocity, transformDirty)
        val worldPos = state.worldTransform.position
        val bukkitWorld = world.bukkitWorldData.bukkitWorld
        val loc = org.bukkit.Location(bukkitWorld, worldPos.x(), worldPos.y(), worldPos.z())
        var worldTeleports = 0L
        loEntity?.let {
            if (!mountedToRoot) {
                it.teleport(loc)
                worldTeleports++
            }
            if (transformDirty) {
                it.configureTrainDisplaySmoothing()
                it.transformation = org.bukkit.util.Transformation(
                    if (mountedToRoot) state.rootRelativeTransform.position.get(org.joml.Vector3f()) else org.joml.Vector3f(0f, 0f, 0f),
                    org.joml.Quaternionf(),
                    state.rootRelativeTransform.scale.get(org.joml.Vector3f()),
                    state.rootRelativeTransform.rotation.get(org.joml.Quaternionf())
                )
            }
        }
        hiEntity?.let {
            if (!mountedToRoot) {
                it.teleport(loc)
                worldTeleports++
            }
            if (transformDirty) {
                it.configureTrainDisplaySmoothing()
                it.transformation = org.bukkit.util.Transformation(
                    if (mountedToRoot) state.rootRelativeTransform.position.get(org.joml.Vector3f()) else org.joml.Vector3f(0f, 0f, 0f),
                    org.joml.Quaternionf(),
                    state.rootRelativeTransform.scale.get(org.joml.Vector3f()),
                    state.rootRelativeTransform.rotation.get(org.joml.Quaternionf())
                )
            }
        }
        if (mountedToRoot) {
            RenderVerificationStats.onLodMountedUpdate()
        } else if (worldTeleports > 0) {
            RenderVerificationStats.onLodWorldTeleport(worldTeleports)
        }
    }

    override fun destroy() {
        super.destroy()
        loEntity?.remove()
        hiEntity?.remove()
    }

    fun updatePlayerVisibility(player: Player) {
        val dx = player.location.x - state.position.x()
        val dy = player.location.y - state.position.y()
        val dz = player.location.z - state.position.z()
        val distanceSq = dx * dx + dy * dy + dz * dz
        val threshold = player.railViewDistanceSettings.lodThreshold
        if (distanceSq > threshold * threshold) {
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
