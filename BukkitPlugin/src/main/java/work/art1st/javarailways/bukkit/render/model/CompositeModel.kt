package work.art1st.javarailways.bukkit.render.model

import java.util.concurrent.CompletionStage
import work.art1st.javarailways.core.api.util.Destruction
import work.art1st.javarailways.core.api.util.DestructionSteps

import org.bukkit.Location
import org.bukkit.entity.Player
import org.joml.Quaterniond
import org.joml.Vector3d
import org.joml.Vector3dc
import work.art1st.javarailways.bukkit.extension.bukkitWorldData
import work.art1st.javarailways.bukkit.render.backend.DisplayVisual
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.util.Destroyable
import work.art1st.javarailways.core.api.util.annotation.MainThread

class CompositeModel internal constructor(
    private val world: World,
    private val models: List<AbstractModel>,
    private val rootVisual: DisplayVisual,
    private val targetWorld: org.bukkit.World? = null
) : Destroyable {
    internal val affinityEntity get() = rootVisual.affinityEntity
    private val extensionGroups = mutableListOf<ExtensionPartModel>()
    private val platforms = mutableListOf<PlatformModel>()
    init {
        forEachModelRecursive {
            if (it is DisplayModel) it.mountTo(rootVisual)
            if (it is PlatformModel) platforms.add(it)
            if (it is ExtensionPartModel) { it.bindRoot(rootVisual); extensionGroups.add(it) }
        }
    }

    val hasExtensionRenderers: Boolean get() = extensionGroups.any { it.hasRenderer }

    private var lastRotation: Quaterniond? = null
    private val destruction = Destruction()

    override fun destroy(): CompletionStage<Void> = destruction.run {
        val steps = DestructionSteps()
        models.forEach { model -> steps.await { model.destroy() } }
        steps.await { rootVisual.destroy() }
        steps.finish()
    }

    fun prepareForUnload() {
        models.forEach { it.prepareForUnload() }
    }

    fun updateState(rootTransform: CompactTransform, rootVelocity: Vector3dc = Vector3d(), forceTransformUpdate: Boolean = false) {
        val rotatedTransform = CompactTransform(Vector3d(), rootTransform.rotation)
        val worldRoot = CompactTransform(rootTransform.position, Quaterniond())
        // Display-relative transforms bake in this rotation. Passenger geometry must stay
        // car/bogie-local so a published train frame applies the root rotation only once.
        if (platforms.isNotEmpty()) {
            val inverseRootRotation = CompactTransform(rotation = Quaterniond(rootTransform.rotation).conjugate())
            platforms.forEach { it.passengerRootInverseRotation = inverseRootRotation }
        }

        val rotationChanged = lastRotation == null || !lastRotation!!.equals(rootTransform.rotation, 1e-12)
        if (rotationChanged) lastRotation = Quaterniond(rootTransform.rotation)
        models.forEach { it.updateState(rotatedTransform, worldRoot, rootVelocity, rotationChanged || forceTransformUpdate) }

        updateRootPosition(rootTransform.position)
    }

    fun invalidateExtensionContext() {
        extensionGroups.forEach { it.invalidateContext() }
    }

    /** Value-only input is supplied before the existing full or root-only model commit. */
    fun updateExtensionInput(data: kotlinx.serialization.json.JsonObject, tick: Long) {
        extensionGroups.forEach { it.updateInput(data, tick) }
    }

    /** Move the shared root without traversing or recomputing the detailed child model tree. */
    fun updateRootPosition(position: Vector3dc) {
        val bukkitWorld = targetWorld ?: world.bukkitWorldData.bukkitWorld
        val location = Location(bukkitWorld, position.x(), position.y(), position.z())
        rootVisual.teleport(location)
        extensionGroups.forEach { it.commitPending(location) }
    }

    fun applyAnimation(name: String): Boolean {
        return models.fold(false) { applied, model -> model.applyAnimation(name) || applied }
    }

    fun applyAnimation(name: String, sceneName: String): Boolean {
        return models.fold(false) { applied, model -> model.applyAnimation(name, sceneName) || applied }
    }

    fun collectHeldAnimationScenes(): Map<String, String> {
        val scenes = linkedMapOf<String, String>()
        models.forEach { model -> model.collectHeldAnimationScenes(scenes) }
        return scenes
    }

    fun setAnimationScenes(scenes: Map<String, String>): Boolean {
        var applied = false
        for ((animationName, sceneName) in scenes) {
            models.forEach { model ->
                applied = model.setAnimationScene(animationName, sceneName) || applied
            }
        }
        return applied
    }

    fun forEachModelRecursive(f: (AbstractModel) -> Unit) {
        models.forEach { it.forEachModelRecursive(f) }
    }

    @MainThread
    fun showTo(player: Player) {
        // Spawn children before the root sends its passenger packet.
        models.forEach { it.showVisualsTo(player) }
        rootVisual.showTo(player)
        if (extensionGroups.isNotEmpty()) rootVisual.refreshPassengersFor(player)
    }

    @MainThread
    fun hideFrom(player: Player) {
        rootVisual.hideFrom(player)
        models.forEach { it.hideVisualsFrom(player) }
    }

    @MainThread
    fun updatePlayerVisibility(player: Player) {
        models.forEach { it.refreshVisualsFor(player) }
    }
}
