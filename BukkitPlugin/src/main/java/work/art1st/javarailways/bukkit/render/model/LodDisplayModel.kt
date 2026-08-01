package work.art1st.javarailways.bukkit.render.model

import org.bukkit.Location
import org.bukkit.entity.Player
import org.joml.Quaternionf
import org.joml.Vector3dc
import org.joml.Vector3f
import work.art1st.javarailways.bukkit.extension.bukkitWorldData
import work.art1st.javarailways.bukkit.render.Animation
import work.art1st.javarailways.bukkit.render.backend.DisplayVisual
import work.art1st.javarailways.bukkit.render.chunk.railViewDistanceSettings
import work.art1st.javarailways.core.api.physics.World

class LodDisplayModel internal constructor(
    world: World,
    state: ModelState,
    attachedModels: Collection<AbstractModel>,
    animation: Map<String, Animation>,
    private val loVisual: DisplayVisual?,
    private val hiVisual: DisplayVisual?
) : AbstractModel(world, state, attachedModels, animation) {
    override fun updateState(
        parentRelativeTransform: CompactTransform,
        rootTransform: CompactTransform,
        rootVelocity: Vector3dc,
        transformDirty: Boolean
    ) {
        super.updateState(parentRelativeTransform, rootTransform, rootVelocity, transformDirty)
        val bukkitWorld = world.bukkitWorldData.bukkitWorld
        val worldPos = state.worldTransform.position
        val location = Location(bukkitWorld, worldPos.x(), worldPos.y(), worldPos.z())
        val transformation = if (transformDirty) {
            org.bukkit.util.Transformation(
                Vector3f(),
                Quaternionf(),
                state.rootRelativeTransform.scale.get(Vector3f()),
                state.rootRelativeTransform.rotation.get(Quaternionf())
            )
        } else {
            null
        }

        // LOD alternatives remain world-space visuals. A hidden packet entity is absent client-side,
        // so mounting both alternatives would lose the passenger relation when the hidden LOD is spawned later.
        listOfNotNull(loVisual, hiVisual).forEach { visual ->
            visual.teleport(location)
            if (transformation != null) visual.setTransformation(transformation)
        }
    }

    override fun showVisualsTo(player: Player) {
        updatePlayerVisibility(player)
        super.showVisualsTo(player)
    }

    override fun refreshVisualsFor(player: Player) {
        updatePlayerVisibility(player)
        super.refreshVisualsFor(player)
    }

    override fun hideVisualsFrom(player: Player) {
        loVisual?.hideFrom(player)
        hiVisual?.hideFrom(player)
        super.hideVisualsFrom(player)
    }

    fun updatePlayerVisibility(player: Player) {
        val dx = player.location.x - state.position.x()
        val dy = player.location.y - state.position.y()
        val dz = player.location.z - state.position.z()
        val distanceSq = dx * dx + dy * dy + dz * dz
        val threshold = player.railViewDistanceSettings.lodThreshold
        if (distanceSq > threshold * threshold) {
            loVisual?.showTo(player)
            hiVisual?.hideFrom(player)
        } else {
            hiVisual?.showTo(player)
            loVisual?.hideFrom(player)
        }
    }

    override fun destroy() {
        super.destroy()
        loVisual?.destroy()
        hiVisual?.destroy()
    }
}
