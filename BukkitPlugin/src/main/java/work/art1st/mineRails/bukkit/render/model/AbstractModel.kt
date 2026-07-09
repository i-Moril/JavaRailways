package work.art1st.mineRails.bukkit.render.model

import org.joml.Vector3dc
import work.art1st.mineRails.bukkit.render.Animation
import work.art1st.mineRails.bukkit.render.AnimationDisplayState
import work.art1st.mineRails.core.api.physics.World
import work.art1st.mineRails.core.api.util.annotation.MainThread
import work.art1st.mineRails.core.api.util.Destroyable

abstract class AbstractModel(
    val world: World,
    val state: ModelState,
    val attachedModels: Collection<AbstractModel>,
    val animation: Map<String, Animation>
) : Destroyable {
    protected var activeAnimation: Animation.ActiveAnimation? = null
    protected var heldAnimationTransform: CompactTransform? = null
    protected var heldAnimationDisplayState: AnimationDisplayState? = null
    protected var heldAnimationName: String? = null
    protected var heldAnimationScene: String? = null
    private var lastParentRelativeTransform: CompactTransform? = null
    private var lastRootTransform: CompactTransform? = null
    private var lastRootVelocity: Vector3dc? = null
    protected val rootRelativeTransform: CompactTransform
        get() = state.rootRelativeTransform

    protected val currentDisplayState: AnimationDisplayState?
        get() = activeAnimation?.currentDisplayState ?: heldAnimationDisplayState

    /**
     * @param parentRelativeTransform 父元素相对根元素的transform
     * @param rootTransform 根元素的transform
     */
    // P4: transformDirty 控制是否需要重设 entity.transformation（position teleport 总是执行）
    open fun updateState(parentRelativeTransform: CompactTransform, rootTransform: CompactTransform, rootVelocity: Vector3dc, transformDirty: Boolean = true) {
        lastParentRelativeTransform = CompactTransform(parentRelativeTransform)
        lastRootTransform = CompactTransform(rootTransform)
        lastRootVelocity = rootVelocity
        val animationTransform = activeAnimation?.currentTransform ?: heldAnimationTransform
        state.applyRotation(parentRelativeTransform, rootTransform, animationTransform)
        attachedModels.forEach { it.updateState(state.rootRelativeTransform, rootTransform, rootVelocity, transformDirty) }
    }

    open fun applyAnimation(animationName: String): Boolean {
        var applied = false
        animation[animationName]?.let { animationPrototype ->
            startAnimation(
                animationName,
                animationPrototype.activate(
                    onDestroy = ::onAnimationFinished,
                    onCommit = ::onAnimationCommit
                )
            )
            applied = true
        }
        attachedModels.forEach { child ->
            applied = child.applyAnimation(animationName) || applied
        }
        return applied
    }

    open fun applyAnimation(animationName: String, sceneName: String): Boolean {
        var applied = false
        animation[animationName]?.let { animationPrototype ->
            if (animationPrototype.hasScene(sceneName)) {
                val currentScene = if (heldAnimationName == animationName) heldAnimationScene else null
                val currentTransform = if (heldAnimationName == animationName) heldAnimationTransform else null
                startAnimation(
                    animationName,
                    animationPrototype.activateToScene(
                        sceneName = sceneName,
                        currentScene = currentScene,
                        currentTransform = currentTransform,
                        onDestroy = ::onAnimationFinished,
                        onCommit = ::onAnimationCommit
                    )
                )
                applied = true
            }
        }
        attachedModels.forEach { child ->
            applied = child.applyAnimation(animationName, sceneName) || applied
        }
        return applied
    }

    fun collectHeldAnimationScenes(target: MutableMap<String, String> = linkedMapOf()): Map<String, String> {
        val animationName = heldAnimationName
        val sceneName = heldAnimationScene
        if (animationName != null && sceneName != null) {
            target[animationName] = sceneName
        }
        attachedModels.forEach { child -> child.collectHeldAnimationScenes(target) }
        return target
    }

    fun setAnimationScene(animationName: String, sceneName: String): Boolean {
        var applied = false
        animation[animationName]?.stateAtScene(sceneName)?.let { (sceneTransform, sceneDisplayState) ->
            activeAnimation?.let { world.tickManager.unregister(it) }
            activeAnimation = null
            heldAnimationName = animationName
            heldAnimationScene = Animation.normalizeSceneName(sceneName)
            heldAnimationTransform = CompactTransform(sceneTransform)
            heldAnimationDisplayState = sceneDisplayState
            applied = true
        }
        attachedModels.forEach { child ->
            applied = child.setAnimationScene(animationName, sceneName) || applied
        }
        return applied
    }

    private fun startAnimation(animationName: String, active: Animation.ActiveAnimation) {
        activeAnimation?.let { world.tickManager.unregister(it) }
        heldAnimationName = animationName
        activeAnimation = active
        world.tickManager.register(active)
    }

    private fun onAnimationCommit(animation: Animation.ActiveAnimation) {
        if (activeAnimation !== animation) return
        val parent = lastParentRelativeTransform ?: return
        val root = lastRootTransform ?: return
        val velocity = lastRootVelocity ?: return
        updateState(parent, root, velocity, transformDirty = true)
    }

    private fun onAnimationFinished(animation: Animation.ActiveAnimation) {
        world.tickManager.unregister(animation)
        if (activeAnimation === animation) {
            if (animation.keepFinalState) {
                heldAnimationTransform = CompactTransform(animation.finalTransform)
                heldAnimationDisplayState = animation.finalDisplayState
                heldAnimationScene = animation.targetScene
            } else {
                heldAnimationTransform = null
                heldAnimationDisplayState = null
                heldAnimationScene = null
            }
            onAnimationCommit(animation)
            activeAnimation = null
        }
    }

    /**
     * Finish runtime-only animation objects before unloading this model tree so reload can restore
     * the held target scene from the normal held-animation snapshot path.
     */
    fun prepareForUnload() {
        activeAnimation?.destroy()
        attachedModels.forEach { it.prepareForUnload() }
    }

    @MainThread
    override fun destroy() {
        activeAnimation?.destroy()
        attachedModels.forEach { it.destroy() }
    }

    fun forEachModelRecursive(f: (AbstractModel) -> Unit) {
        f.invoke(this)
        attachedModels.forEach { it.forEachModelRecursive(f) }
    }
}