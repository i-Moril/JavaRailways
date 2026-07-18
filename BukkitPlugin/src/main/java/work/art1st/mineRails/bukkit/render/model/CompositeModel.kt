package work.art1st.mineRails.bukkit.render.model

import org.bukkit.Location
import org.bukkit.entity.Player
import org.joml.Quaterniond
import org.joml.Vector3d
import org.joml.Vector3dc
import work.art1st.mineRails.bukkit.extension.bukkitWorldData
import work.art1st.mineRails.bukkit.render.backend.DisplayVisual
import work.art1st.mineRails.core.api.physics.World
import work.art1st.mineRails.core.api.util.Destroyable
import work.art1st.mineRails.core.api.util.annotation.MainThread

class CompositeModel internal constructor(
    private val world: World,
    private val models: List<AbstractModel>,
    private val rootVisual: DisplayVisual
) : Destroyable {
    init {
        forEachModelRecursive {
            if (it is DisplayModel) it.mountTo(rootVisual)
        }
    }

    private var lastRotation: Quaterniond? = null
    private var destroyed: Boolean = false

    override fun destroy() {
        if (destroyed) return
        destroyed = true
        destroyNow()
    }

    private fun destroyNow() {
        models.forEach { it.destroy() }
        rootVisual.destroy()
    }

    fun prepareForUnload() {
        models.forEach { it.prepareForUnload() }
    }

    fun updateState(rootTransform: CompactTransform, rootVelocity: Vector3dc = Vector3d()) {
        val rotatedTransform = CompactTransform(Vector3d(), rootTransform.rotation)
        val worldRoot = CompactTransform(rootTransform.position, Quaterniond())

        val rotationChanged = lastRotation == null || !lastRotation!!.equals(rootTransform.rotation, 1e-12)
        if (rotationChanged) lastRotation = Quaterniond(rootTransform.rotation)
        models.forEach { it.updateState(rotatedTransform, worldRoot, rootVelocity, rotationChanged) }

        updateRootPosition(rootTransform.position)
    }

    /** Move the shared root without traversing or recomputing the detailed child model tree. */
    fun updateRootPosition(position: Vector3dc) {
        val bukkitWorld = world.bukkitWorldData.bukkitWorld
        val location = Location(bukkitWorld, position.x(), position.y(), position.z())
        rootVisual.teleport(location)
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
