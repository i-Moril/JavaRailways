package work.art1st.mineRails.bukkit.render.model

import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.util.Vector
import org.joml.Quaterniond
import org.joml.Vector3d
import org.joml.Vector3dc
import work.art1st.mineRails.core.api.physics.World
import work.art1st.mineRails.core.api.util.annotation.MainThread
import work.art1st.mineRails.core.util.Destroyable
import java.util.concurrent.Callable

class CompositeModel internal constructor(
    private val world: World,
    private val models: List<AbstractModel>,
    private val rootEntity: Entity
) : Destroyable {
    override fun destroy() {
        world.tickManager.scheduleMainThreadAfterCommitTask {
            rootEntity.remove()
            models.forEach { it.destroy() }
        }
    }

    fun onMainThread(f: Callable<Unit>) {
        world.tickManager.scheduleMainThreadAfterCommitTask(f)
    }

    fun updateState(rootTransform: CompactTransform, rootVelocity: Vector3dc = Vector3d()) {
        // parentRelativeTransform carries the rotation; root for world-space uses identity
        // rotation to avoid double-applying carRotation in worldTransform computation.
        val rotatedTransform = CompactTransform(Vector3d(), rootTransform.rotation)
        val worldRoot = CompactTransform(rootTransform.position, org.joml.Quaterniond(1.0, 0.0, 0.0, 0.0))
        models.forEach { it.updateState(rotatedTransform, worldRoot, rootVelocity) }
        // 根元素，无rotation
        onMainThread {
            rootEntity.teleport(
                Location(rootEntity.world, rootTransform.position.x(), rootTransform.position.y(), rootTransform.position.z())
            )
            rootEntity.velocity = Vector(rootVelocity.x(), rootVelocity.y(), rootVelocity.z())
        }
    }

    fun applyAnimation(name: String) {
        forEachModelRecursive { it.applyAnimation(name) }
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
