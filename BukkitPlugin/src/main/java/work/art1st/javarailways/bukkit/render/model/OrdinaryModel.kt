package work.art1st.javarailways.bukkit.render.model

import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.CustomModelData
import net.kyori.adventure.key.Key
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.joml.Quaterniond
import org.joml.Vector3d
import work.art1st.javarailways.bukkit.api.model.ItemAppearance
import work.art1st.javarailways.bukkit.api.model.ModelAnimation
import work.art1st.javarailways.bukkit.api.model.ModelPart
import work.art1st.javarailways.bukkit.api.model.ModelPose
import work.art1st.javarailways.bukkit.api.model.ModelScale
import work.art1st.javarailways.bukkit.render.backend.DisplayCleanupException

/**
 * Internal scene adapter over the existing renderer; construction and calls require the target owner.
 * The index is built once. Validation is O(selections + hits), reset visits the old/new hit union,
 * and the existing hierarchy update remains O(parts). No timeline is activated or package loaded.
 */
internal class OrdinaryModel(
    private val composite: CompositeModel,
    parts: List<Binding>,
    private val sceneIndex: OrdinarySceneIndex,
    private val visuals: List<work.art1st.javarailways.bukkit.render.backend.DisplayVisual>
) {
    internal val affinityEntity get() = composite.affinityEntity
    @Volatile private var destroyed = false
    internal class Binding(val model: AbstractModel, val path: String, val definition: ModelPart)
    private val modelsByPath = parts.associate { it.path to it.model }
    private var selection: Map<String, String> = emptyMap()
    private var selectedFrames: Map<AbstractModel, ModelAnimation.Frame> = emptyMap()
    private var lastPose: ModelPose? = null

    /** Complete exact-name selection. Invalid input is rejected before any model/visual mutation. */
    fun submit(pose: ModelPose, scenes: Map<String, String>): Boolean = submit(pose, sceneIndex.resolve(scenes))

    internal fun submit(pose: ModelPose, resolved: OrdinarySceneIndex.Selection): Boolean {
        val candidate = resolved.scenes
        if (candidate == selection && pose == lastPose) return false
        val next = resolved.frames.mapKeys { (path, _) -> modelsByPath.getValue(path) }

        var changed = false
        for (part in selectedFrames.keys + next.keys) {
            if (selectedFrames[part] != next[part]) {
                part.holdOrdinaryScene(next[part])
                changed = true
            }
        }
        val poseChanged = pose != lastPose
        if (changed || poseChanged) {
            composite.updateState(pose.toCompactTransform(), forceTransformUpdate = changed)
        }
        selection = candidate
        selectedFrames = next
        lastPose = pose
        return changed || poseChanged
    }

    // Kept out of the public API: dispatch, close gating and world lifecycle belong to the later handle.
    fun showTo(player: Player) { if (!destroyed) composite.showTo(player) }
    fun hideFrom(player: Player) { if (!destroyed) composite.hideFrom(player) }
    fun destroy() {
        if (destroyed) return
        destroyed = true
        var failure: Throwable? = null
        visuals.asReversed().forEach { visual ->
            try { visual.destroy() } catch (cleanup: Throwable) {
                if (failure == null) failure = cleanup else failure.addSuppressed(cleanup)
            }
        }
        failure?.let { throw DisplayCleanupException(it) }
    }
}

internal fun ModelPose.toCompactTransform(scale: ModelScale = ModelScale.UNIT): CompactTransform = CompactTransform(
    Vector3d(x(), y(), z()), Quaterniond(rotationX(), rotationY(), rotationZ(), rotationW()),
    Vector3d(scale.x(), scale.y(), scale.z())
)

/** Always builds the complete item; absent components cannot leak from a preceding scene. */
internal fun ItemAppearance.toItemStack(): ItemStack {
    val item = ItemStack.of(material())
    itemModelKey()?.let { item.setData(DataComponentTypes.ITEM_MODEL, Key.key(it)) }
    customModelData()?.let {
        item.setData(DataComponentTypes.CUSTOM_MODEL_DATA, CustomModelData.customModelData().addFloat(it.toFloat()).build())
    }
    return item
}
