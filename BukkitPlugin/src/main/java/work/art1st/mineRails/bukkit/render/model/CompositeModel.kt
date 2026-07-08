package work.art1st.mineRails.bukkit.render.model

import io.papermc.paper.entity.TeleportFlag
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
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
    init {
        forEachModelRecursive {
            when (it) {
                is DisplayModel -> it.mountTo(rootEntity)
                is LodDisplayModel -> it.mountTo(rootEntity)
            }
        }
    }

    // P4: 缓存上一帧的完整旋转，用于 dirty flag 优化
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
        // root 只承担世界坐标锚点；完整姿态仍由 child display 的 transformation 表达。
        val rotatedTransform = CompactTransform(Vector3d(), rootTransform.rotation)
        val worldRoot = CompactTransform(rootTransform.position, Quaterniond())

        val rotationChanged = lastRotation == null || !lastRotation!!.equals(rootTransform.rotation, 1e-12)
        if (rotationChanged) lastRotation = Quaterniond(rootTransform.rotation)
        models.forEach { it.updateState(rotatedTransform, worldRoot, rootVelocity, rotationChanged) }

        val location = Location(rootEntity.world, rootTransform.position.x(), rootTransform.position.y(), rootTransform.position.z())
        if (Bukkit.isPrimaryThread()) {
            (rootEntity as? Display)?.configureTrainRootSmoothing()
            rootEntity.teleport(location, TeleportFlag.EntityState.RETAIN_PASSENGERS)
            RenderVerificationStats.onRootTeleport()
        } else {
            onMainThread {
                (rootEntity as? Display)?.configureTrainRootSmoothing()
                rootEntity.teleport(location, TeleportFlag.EntityState.RETAIN_PASSENGERS)
                RenderVerificationStats.onRootTeleport()
            }
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
