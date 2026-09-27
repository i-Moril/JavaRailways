package work.art1st.javarailways.basic.api.safety

import java.util.concurrent.atomic.AtomicBoolean

/** Generic world-level fail-closed latch consumed by physical and manual controllers. */
class WorldSafetyInterlock {
    private val engaged = AtomicBoolean(false)

    val isEngaged: Boolean
        get() = engaged.get()

    /** Returns true only for the transition that first engaged this latch. */
    fun engage(): Boolean = engaged.compareAndSet(false, true)

    /** Administrative reset after the world has been restored to a safe state. */
    fun reset() {
        engaged.set(false)
    }
}
