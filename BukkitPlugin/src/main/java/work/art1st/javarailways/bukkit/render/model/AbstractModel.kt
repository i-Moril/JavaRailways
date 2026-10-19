package work.art1st.javarailways.bukkit.render.model

import java.util.concurrent.CompletionStage
import work.art1st.javarailways.core.api.util.Destruction
import work.art1st.javarailways.core.api.util.DestructionSteps

import org.bukkit.Location
import org.bukkit.entity.Player
import org.joml.Vector3dc
import work.art1st.javarailways.bukkit.api.model.ModelAnimation
import work.art1st.javarailways.bukkit.render.Animation
import work.art1st.javarailways.bukkit.render.AnimationDisplayState
import work.art1st.javarailways.bukkit.extension.bukkitWorldData
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.util.annotation.MainThread
import work.art1st.javarailways.core.api.util.Destroyable

abstract class AbstractModel(
    val world: World,
    val state: ModelState,
    val attachedModels: Collection<AbstractModel>,
    val animation: Map<String, Animation>
) : Destroyable {
    private val destruction = Destruction()

    protected var activeAnimation: Animation.ActiveAnimation? = null
    protected var heldAnimationTransform: CompactTransform? = null
    protected var heldAnimationDisplayState: AnimationDisplayState? = null
    protected var heldAnimationName: String? = null
    protected var heldAnimationScene: String? = null
    private var lastParentRelativeTransform: CompactTransform? = null
    @Volatile
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
                    commitLocation = ::animationCommitLocation,
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
                        commitLocation = ::animationCommitLocation,
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

    /** Exact held frame for the ordinary path; null restores the local baseline without playback. */
    internal open fun holdOrdinaryScene(frame: ModelAnimation.Frame?) {
        heldAnimationTransform = frame?.pose()?.toCompactTransform(frame.scale())
    }

    private fun startAnimation(animationName: String, active: Animation.ActiveAnimation) {
        activeAnimation?.let { world.tickManager.unregister(it) }
        heldAnimationName = animationName
        activeAnimation = active
        world.tickManager.register(active)
    }

    private fun animationCommitLocation(): Location? {
        val root = lastRootTransform?.position ?: return null
        return Location(world.bukkitWorldData.bukkitWorld, root.x(), root.y(), root.z())
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

    open fun showVisualsTo(player: Player) {
        attachedModels.forEach { it.showVisualsTo(player) }
    }

    open fun hideVisualsFrom(player: Player) {
        attachedModels.forEach { it.hideVisualsFrom(player) }
    }

    open fun refreshVisualsFor(player: Player) {
        attachedModels.forEach { it.refreshVisualsFor(player) }
    }

    /**
     * Finish runtime-only animation objects before unloading this model tree so reload can restore
     * the held target scene from the normal held-animation snapshot path.
     */
    fun prepareForUnload() {
        activeAnimation?.destroy()?.toCompletableFuture()?.getNow(null)
        attachedModels.forEach { it.prepareForUnload() }
    }

    @MainThread
    @Synchronized
    final override fun destroy(): CompletionStage<Void> = destruction.run {
        val steps = DestructionSteps()
        collectDestruction(steps)
        steps.finish()
    }

    protected open fun collectDestruction(steps: DestructionSteps) {
        activeAnimation?.let { animation -> steps.await { animation.destroy() } }
        attachedModels.forEach { child -> steps.await { child.destroy() } }
    }

    fun forEachModelRecursive(f: (AbstractModel) -> Unit) {
        f.invoke(this)
        attachedModels.forEach { it.forEachModelRecursive(f) }
    }
}
