package work.art1st.mineRails.bukkit.render.model

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.util.Vector
import org.joml.Quaterniond
import org.joml.Vector3d
import org.joml.Vector3dc
import work.art1st.mineRails.core.api.physics.World
import work.art1st.mineRails.core.api.util.annotation.MainThread
import work.art1st.mineRails.core.api.util.Destroyable
import java.util.concurrent.Callable

class CompositeModel internal constructor(
    private val world: World,
    private val models: List<AbstractModel>,
    private val rootEntity: Entity
) : Destroyable {

    // P4: 缓存上一帧的旋转，用于 dirty flag 优化
    private var lastRotation: Quaterniond? = null
    private var destroyed: Boolean = false

    override fun destroy() {
        if (destroyed) return
        destroyed = true
        if (Bukkit.isPrimaryThread()) {
            destroyNow()
        } else {
            world.tickManager.scheduleMainThreadAfterCommitTask {
                destroyNow()
            }
        }
    }

    private fun destroyNow() {
        models.forEach { it.destroy() }
        rootEntity.remove()
    }

    fun onMainThread(f: Callable<Unit>) {
        world.tickManager.scheduleMainThreadAfterCommitTask(f)
    }

    fun updateState(rootTransform: CompactTransform, rootVelocity: Vector3dc = Vector3d()) {
        // parentRelativeTransform carries the rotation; root for world-space uses identity
        val rotatedTransform = CompactTransform(Vector3d(), rootTransform.rotation)
        val worldRoot = CompactTransform(rootTransform.position, Quaterniond())

        // P4: 若旋转未变化，子模型只需 teleport 不需重设 transformation
        val rotationChanged = lastRotation == null || !lastRotation!!.equals(rootTransform.rotation, 1e-12)
        if (rotationChanged) lastRotation = Quaterniond(rootTransform.rotation)
        models.forEach { it.updateState(rotatedTransform, worldRoot, rootVelocity, rotationChanged) }

        // 根元素位置每帧更新（与旋转无关）
        onMainThread {
            rootEntity.teleport(
                Location(rootEntity.world, rootTransform.position.x(), rootTransform.position.y(), rootTransform.position.z())
            )
            rootEntity.velocity = Vector(rootVelocity.x(), rootVelocity.y(), rootVelocity.z())
        }
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
        models.forEach {
            it.forEachModelRecursive(f)
        }
    }

    @MainThread
    fun updatePlayerVisibility(player: Player) {
        forEachModelRecursive {
            if (it is LodDisplayModel) {
                it.updatePlayerVisibility(player)
            }
        }
    }
}
