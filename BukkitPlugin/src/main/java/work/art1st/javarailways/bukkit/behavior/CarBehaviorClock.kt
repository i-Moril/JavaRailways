package work.art1st.javarailways.bukkit.behavior

import org.bukkit.Location
import work.art1st.javarailways.bukkit.render.Animation
import work.art1st.javarailways.bukkit.util.PlatformCommitAffinity
import work.art1st.javarailways.core.api.util.tick.Synchronizable
import work.art1st.javarailways.core.api.util.tick.TickManager
import work.art1st.javarailways.core.api.util.tick.TickBatchPreparing

/** One exact-car generic clock for delayed effects and playback; never executes JavaScript in tick. */
internal class CarBehaviorClock(
    private val manager: TickManager,
    private val monitor: Any,
    private val location: () -> Location? = { null }
) : TickBatchPreparing, Synchronizable, PlatformCommitAffinity, AutoCloseable {
    private class Delayed(val seconds: Double, val run: () -> Unit) {
        var at = 0.0
    }
    private val delayed = mutableListOf<Delayed>()
    private val animations = linkedSetOf<Animation.ActiveAnimation>()
    private val pendingAnimations = linkedSetOf<Animation.ActiveAnimation>()
    private val pendingDelayed = mutableListOf<Delayed>()
    private var elapsed = 0.0
    private var closed = false
    private var started = false

    fun start() = synchronized(monitor) {
        check(!closed) { "car clock is closed" }
        if (!started) { started = true; manager.register(this) }
    }

    fun register(animation: Animation.ActiveAnimation): AutoCloseable = synchronized(monitor) {
        check(!closed) { "car clock is closed" }
        pendingAnimations.add(animation)
        AutoCloseable { synchronized(monitor) {
            pendingAnimations.remove(animation)
            animations.remove(animation)
        } }
    }

    fun schedule(seconds: Double, run: () -> Unit): AutoCloseable = synchronized(monitor) {
        check(!closed) { "car clock is closed" }
        require(seconds.isFinite() && seconds >= 0.0) { "invalid effect delay" }
        val effect = Delayed(seconds, run)
        pendingDelayed.add(effect)
        AutoCloseable { synchronized(monitor) {
            pendingDelayed.remove(effect)
            delayed.remove(effect)
        } }
    }

    override fun prepareTick() = synchronized(monitor) {
        if (!closed) {
            animations.addAll(pendingAnimations)
            pendingAnimations.clear()
            pendingDelayed.forEach { it.at = elapsed + it.seconds }
            delayed.addAll(pendingDelayed)
            pendingDelayed.clear()
        }
    }

    override fun tick(deltaT: Double) = synchronized(monitor) {
        if (!closed && (animations.isNotEmpty() || delayed.isNotEmpty())) {
            elapsed += deltaT.coerceAtLeast(0.0) / 1000.0
            animations.toList().forEach { it.tick(deltaT) }
        }
    }

    override fun commitLocation(): Location? = location()

    override fun commit() = synchronized(monitor) {
        if (!closed && (animations.isNotEmpty() || delayed.isNotEmpty())) {
            // Finish already-computed frames before effects at this same instant replace a slot.
            animations.toList().forEach { it.commit() }
            val due = delayed.filter { elapsed + 1e-9 >= it.at }.sortedBy { it.at }
            due.forEach { effect -> if (delayed.remove(effect)) effect.run() }
        }
    }

    override fun close() = synchronized(monitor) {
        if (!closed) {
            closed = true
            delayed.clear()
            animations.clear()
            pendingDelayed.clear()
            pendingAnimations.clear()
            if (started) manager.unregister(this)
        }
    }
}
