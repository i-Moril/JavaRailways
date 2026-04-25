package work.art1st.mineRails.bukkit.render.model

import org.joml.Vector3dc
import work.art1st.mineRails.bukkit.render.Animation
import work.art1st.mineRails.core.api.physics.World
import work.art1st.mineRails.core.api.util.annotation.MainThread
import work.art1st.mineRails.core.util.Destroyable
import kotlin.jvm.Throws

abstract class AbstractModel(
    val world: World,
    val state: ModelState,
    val attachedModels: Collection<AbstractModel>,
    val animation: Map<String, Animation>
) : Destroyable {
    protected var activeAnimation: Animation.ActiveAnimation? = null
    protected val rootRelativeTransform: CompactTransform
        get() = state.rootRelativeTransform

    /**
     * @param parentRelativeTransform 父元素相对根元素的transform
     * @param rootTransform 根元素的transform
     */
    open fun updateState(parentRelativeTransform: CompactTransform, rootTransform: CompactTransform, rootVelocity: Vector3dc) {
        val animationTransform = activeAnimation?.currentTransform
        state.applyRotation(parentRelativeTransform, rootTransform, animationTransform)
        attachedModels.forEach { it.updateState(parentRelativeTransform, rootTransform, rootVelocity) }
    }

    @Throws(IllegalArgumentException::class)
    open fun applyAnimation(animationName: String) {
        val animationPrototype = animation[animationName] ?: throw IllegalArgumentException("Animation not found")
        animationPrototype.activate(onDestroy = {
            world.tickManager.unregister(it)
            activeAnimation = null
        }).let {
            activeAnimation = it
            world.tickManager.register(it)
        }
    }

    @MainThread
    override fun destroy() {
        attachedModels.forEach { it.destroy() }
    }

    fun forEachModelRecursive(f: (AbstractModel) -> Unit) {
        f.invoke(this)
        attachedModels.forEach { it.forEachModelRecursive(f) }
    }
}