package work.art1st.mineRails.core.api.util

/**
 * Object registered into world ticking manager.
 */
interface TickingObject : SynchronizedObject {
    fun tick(deltaT: Double)
    override fun commit() {}
}