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
    private class Slot {
        var name: String? = null
        var scene: String? = null
        var transform: CompactTransform? = null
        var active: Animation.ActiveAnimation? = null
        var display: work.art1st.javarailways.bukkit.render.AnimationDisplayState? = null
    }
    private val slots = mutableMapOf<Int, Slot>()
    private var closed = false

    fun play(name: String, scene: String?): CompletableFuture<Unit> {
        check(!closed) { "car animation owner is closed" }
        val calls = mutableListOf<CompletableFuture<Unit>>()
        parts().forEachIndexed { index, animations ->
            val prototype = animations[name] ?: return@forEachIndexed
            if (scene != null && !prototype.hasScene(scene)) return@forEachIndexed
            val slot = slots.getOrPut(index) { Slot().also { state ->
                // Same ordered overwrite rule as CompositeModel.setAnimationScenes: last matching key wins.
                initialScenes().entries.lastOrNull { (key, value) -> animations[key]?.hasScene(value) == true }?.let { (key, value) ->
                    val frame = requireNotNull(animations[key]?.stateAtScene(value))
                    state.name = key; state.scene = value; state.transform = frame.first; state.display = frame.second
                }
            } }
            val same = slot.name == name
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

    /** Closing follows a completed opening plan, even if queried before arrival opens the doors. */
    fun closeDurationTicks(open: List<String>, close: List<String>, actions: Map<String, TrainActionConfig>): Long {
        check(!closed) { "car animation owner is closed" }
        val parts = parts()
        val scenes = mutableMapOf<Int, Pair<String, String?>>()
        fun measure(plan: List<String>): Double {
            var end = 0.0
            val effects = plan.flatMap { requireNotNull(actions[it]).animations }.sortedBy { it.at }
            effects.forEach { effect ->
                var matched = false
                parts.forEachIndexed { index, animations ->
                    val prototype = animations[effect.name] ?: return@forEachIndexed
                    if (effect.scene != null && !prototype.hasScene(effect.scene)) return@forEachIndexed
                    matched = true
                    val source = scenes[index]?.takeIf { it.first == effect.name }?.second
                    val duration = prototype.durationSeconds(effect.scene, source)
                    // Launch and playback each cross an owner-commit boundary on 20 Hz ticks.
                    end = maxOf(end, ceil(effect.at * 20.0) + ceil(duration * 20.0))
                    scenes[index] = effect.name to if (prototype.keepFinalState) Animation.normalizeSceneName(effect.scene) else null
                }
                require(matched) { "animation unavailable: ${effect.name}" }
            }
            plan.flatMap { requireNotNull(actions[it]).sounds }.forEach { end = maxOf(end, ceil(it.at * 20.0)) }
            return end
        }
        measure(open)
        val ticks = measure(close)
        require(ticks.isFinite() && ticks >= 0.0 && ticks <= 9_007_199_254_740_991.0) { "door duration is invalid" }
        return ticks.toLong()
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

    override fun close() {
        if (closed) return
        closed = true
        resetForSourceChange()
    }
}
