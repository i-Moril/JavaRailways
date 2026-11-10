package work.art1st.javarailways.bukkit.render.model

import org.bukkit.entity.Player
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3dc
import org.joml.Vector3f
import work.art1st.javarailways.bukkit.render.backend.DisplayVisual
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.util.DestructionSteps

/** Host-owned static text leaf. Its lifetime and transforms belong to one extension group. */
internal class TextModel(world: World, state: ModelState, private val visual: DisplayVisual) :
    AbstractModel(world, state, emptyList(), emptyMap()) {
    fun mountTo(root: DisplayVisual) { check(root.addPassenger(visual)) { "Could not mount extension text to its model root" } }
    override fun updateState(parentRelativeTransform: CompactTransform, rootTransform: CompactTransform, rootVelocity: Vector3dc, transformDirty: Boolean) {
        super.updateState(parentRelativeTransform, rootTransform, rootVelocity, transformDirty)
        if (transformDirty) visual.setTransformation(Transformation(
            state.rootRelativeTransform.position.get(Vector3f()), Quaternionf(),
            state.rootRelativeTransform.scale.get(Vector3f()), state.rootRelativeTransform.rotation.get(Quaternionf())))
    }
    override fun showVisualsTo(player: Player) { visual.showTo(player) }
    override fun hideVisualsFrom(player: Player) { visual.hideFrom(player) }
    override fun collectDestruction(steps: DestructionSteps) { steps.await { visual.destroy() } }
}
