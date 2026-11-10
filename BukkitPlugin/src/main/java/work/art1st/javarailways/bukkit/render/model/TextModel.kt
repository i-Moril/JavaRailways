package work.art1st.javarailways.bukkit.render.model

import work.art1st.javarailways.bukkit.api.model.extension.PartExtensionText
import org.bukkit.entity.Player
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3dc
import org.joml.Vector3f
import work.art1st.javarailways.bukkit.render.backend.DisplayVisual
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.util.DestructionSteps

/** Host-owned text leaf. Its lifetime and transforms belong to one extension group. */
internal class TextModel(world: World, state: ModelState, private val visual: DisplayVisual, initial: PartExtensionText) :
    AbstractModel(world, state, emptyList(), emptyMap()) {
    private var content = initial
    private var primitiveState = state
    private var lastTransformation: Transformation? = null
    fun updateContent(next: PartExtensionText) {
        if (content.text != next.text) visual.setText(next.text)
        if (content.pose != next.pose || content.scale != next.scale)
            primitiveState = ModelState(next.pose.toCompactTransform(next.scale))
        content = next
    }
    fun mountTo(root: DisplayVisual) { check(root.addPassenger(visual)) { "Could not mount extension text to its model root" } }
    override fun updateState(parentRelativeTransform: CompactTransform, rootTransform: CompactTransform, rootVelocity: Vector3dc, transformDirty: Boolean) {
        primitiveState.applyRotation(parentRelativeTransform, rootTransform)
        val relative = primitiveState.rootRelativeTransform
        // Text geometry scales in its own glyph plane before the installation/car
        // rotation. Bukkit composes T * leftRotation * S * rightRotation.
        val transformation = Transformation(relative.position.get(Vector3f()), relative.rotation.get(Quaternionf()),
            relative.scale.get(Vector3f()), Quaternionf())
        if (lastTransformation != transformation) {
            visual.setTransformation(transformation)
            lastTransformation = transformation
        }
    }
    override fun showVisualsTo(player: Player) { visual.showTo(player) }
    override fun hideVisualsFrom(player: Player) { visual.hideFrom(player) }
    override fun collectDestruction(steps: DestructionSteps) { steps.await { visual.destroy() } }
}
