package work.art1st.javarailways.bukkit.behavior

import work.art1st.javarailways.bukkit.config.model.TrainActionConfig
import work.art1st.javarailways.bukkit.render.Animation
import work.art1st.javarailways.bukkit.render.model.CompactTransform
import java.util.concurrent.CompletableFuture
import kotlin.math.ceil

/** The same real animation frames drive visible-independent LIVE and detached SIM clocks. */
internal class CarAnimationPlayback(
    private val parts: () -> List<Map<String, Animation>>,
    private val register: (Animation.ActiveAnimation) -> AutoCloseable,
    private val initialScenes: () -> Map<String, String>,
    private val project: (Int, Frame) -> Unit
) : AutoCloseable {
    data class Frame(val name: String?, val scene: String?, val transform: CompactTransform?,
        val display: work.art1st.javarailways.bukkit.render.AnimationDisplayState?)
    data class DoorClosePlan(
        val actions: List<TrainActionConfig>,
        val inProgress: Set<CompletableFuture<Unit>>,
        val durationTicks: Long
    )
    private class Slot {
        var name: String? = null
        var scene: String? = null
        var transform: CompactTransform? = null
        var active: Animation.ActiveAnimation? = null
        var display: work.art1st.javarailways.bukkit.render.AnimationDisplayState? = null
    }
    private val slots = mutableMapOf<Int, Slot>()
    private var closed = false

    fun play(name: String, scene: String?, continueClosing: Boolean = false): CompletableFuture<Unit> {
        check(!closed) { "car animation owner is closed" }
        val calls = mutableListOf<CompletableFuture<Unit>>()
        parts().forEachIndexed { index, animations ->
            val prototype = animations[name] ?: return@forEachIndexed
            if (scene != null && !prototype.hasScene(scene)) return@forEachIndexed
            val slot = slots.getOrPut(index) { Slot().also { state ->
                // Same ordered overwrite rule as CompositeModel.setAnimationScenes: last matching key wins.
                initialScene(animations)?.let { (key, value) ->
                    val frame = requireNotNull(animations[key]?.stateAtScene(value))
                    state.name = key; state.scene = value; state.transform = frame.first; state.display = frame.second
                }
            } }
            val same = slot.name == name
            if (continueClosing && same && scene != null) {
                val target = Animation.normalizeSceneName(scene)
                val active = slot.active
                if (active != null && active.targetScene == target) {
                    calls.add(active.completion)
                    return@forEachIndexed
                }
                if (active == null && Animation.normalizeSceneName(slot.scene) == target) {
                    calls.add(CompletableFuture.completedFuture(Unit))
                    return@forEachIndexed
                }
            }
            val sourceScene = if (same && slot.active == null) slot.scene else null
            val sourceTransform = if (same) slot.active?.currentTransform ?: slot.transform else null
            slot.active?.destroy()?.toCompletableFuture()?.getNow(null)
            var registration: AutoCloseable? = null
            val finished: (Animation.ActiveAnimation) -> Unit = { active ->
                registration?.close()
                if (slot.active === active) {
                    slot.active = null
                    slot.scene = if (active.keepFinalState && active.completedNormally) active.targetScene else null
                    slot.transform = if (active.keepFinalState && active.completedNormally) active.finalTransform else null
                    slot.display = if (active.keepFinalState && active.completedNormally) active.finalDisplayState else null
                    project(index, Frame(slot.name, slot.scene, slot.transform, slot.display))
                }
            }
            val commit: (Animation.ActiveAnimation) -> Unit = { active ->
                project(index, Frame(name, null, active.currentTransform, active.currentDisplayState))
            }
            val active = if (scene == null) prototype.activate(onDestroy = finished, onCommit = commit)
                else prototype.activateToScene(scene, sourceScene, sourceTransform, onDestroy = finished, onCommit = commit)
            slot.name = name
            slot.active = active
            calls.add(active.completion)
            project(index, Frame(name, null, active.currentTransform, active.currentDisplayState))
            if (active.durationSeconds == 0.0) {
                active.tick(0.0)
                active.commit()
            } else try { registration = register(active) } catch (failure: Exception) {
                active.completion.completeExceptionally(failure)
                active.destroy().toCompletableFuture().getNow(null)
            }
        }
        return if (calls.isEmpty()) CompletableFuture.failedFuture(IllegalStateException("animation unavailable: $name"))
        else CompletableFuture.allOf(*calls.toTypedArray()).thenApply { Unit }
    }

    fun projectCurrentFrames() {
        slots.forEach { (index, slot) ->
            val active = slot.active
            project(index, if (active != null) Frame(slot.name, null, active.currentTransform, active.currentDisplayState)
                else Frame(slot.name, slot.scene, slot.transform, slot.display))
        }
    }

    private fun initialScene(animations: Map<String, Animation>) =
        initialScenes().entries.lastOrNull { (key, value) -> animations[key]?.hasScene(value) == true }

    /** Inspect actual slots first, including animations running while the car is hidden. */
    fun planDoorClose(sides: List<List<TrainActionConfig>>): DoorClosePlan {
        check(!closed) { "car animation owner is closed" }
        val parts = parts()
        val actions = linkedSetOf<TrainActionConfig>()
        val inProgress = linkedSetOf<CompletableFuture<Unit>>()
        var end = 0.0
        for (side in sides) {
            var hasDoorScene = false
            var needsClose = false
            var playbackEnd = 0.0
            val closing = linkedSetOf<Animation.ActiveAnimation>()
            for (effect in side.flatMap { it.animations }) {
                val target = Animation.normalizeSceneName(effect.scene)
                parts.forEachIndexed { index, animations ->
                    val prototype = animations[effect.name] ?: return@forEachIndexed
                    if (target != null && !prototype.hasScene(target)) return@forEachIndexed
                    val slot = slots[index]
                    val seed = if (slot == null) initialScene(animations) else null
                    val active = slot?.active
                    val source = (slot?.scene ?: seed?.value)?.takeIf {
                        active == null && (slot?.name ?: seed?.key) == effect.name
                    }
                    if (target != null) {
                        hasDoorScene = true
                        if (active != null && slot.name == effect.name && active.targetScene == target) {
                            closing.add(active)
                            playbackEnd = maxOf(playbackEnd, ceil(effect.at * 20.0), ceil(active.remainingSeconds() * 20.0))
                            return@forEachIndexed
                        }
                        val atDefault = slot?.transform == null && seed == null && prototype.isDefaultScene(target)
                        if (active != null || (Animation.normalizeSceneName(source) != target && !atDefault)) {
                            needsClose = true
                        }
                    }
                    val hasCurrentTransform = slot?.name == effect.name &&
                        (active != null || slot.transform != null) && source == null
                    val duration = prototype.durationSeconds(target, source, hasCurrentTransform)
                    playbackEnd = maxOf(playbackEnd, ceil(effect.at * 20.0) + ceil(duration * 20.0))
                }
            }
            if (needsClose || !hasDoorScene) {
                actions.addAll(side)
                end = maxOf(end, playbackEnd)
                side.flatMap { it.sounds }.forEach { end = maxOf(end, ceil(it.at * 20.0)) }
            } else {
                closing.forEach { active ->
                    inProgress.add(active.completion)
                    end = maxOf(end, ceil(active.remainingSeconds() * 20.0))
                }
            }
        }
        require(end.isFinite() && end >= 0.0 && end <= 9_007_199_254_740_991.0) { "door duration is invalid" }
        return DoorClosePlan(actions.toList(), inProgress, end.toLong())
    }

    /** A changed model tree is a distinct resource boundary, unlike visibility-only reconstruction. */
    fun resetForSourceChange() {
        var failure: Throwable? = null
        // Logical teardown may run on the simulation/world owner rather than a display region.
        // Detach first: destruction settles futures and clock registrations, never projects visuals.
        val activeAnimations = slots.values.mapNotNull { slot -> slot.active.also { slot.active = null } }
        slots.clear()
        activeAnimations.forEach { active ->
            try { active.destroy().toCompletableFuture().getNow(null) } catch (value: Throwable) {
                if (failure == null) failure = value else if (failure !== value) failure!!.addSuppressed(value)
            }
        }
        failure?.let { throw it }
    }

    /** Restore every model part to its unanimated transform on the car's region owner. */
    fun resetToDefault() {
        check(!closed) { "car animation owner is closed" }
        val count = parts().size
        resetForSourceChange()
        repeat(count) { index -> project(index, Frame(null, null, null, null)) }
    }

    override fun close() {
        if (closed) return
        closed = true
        resetForSourceChange()
    }
}
