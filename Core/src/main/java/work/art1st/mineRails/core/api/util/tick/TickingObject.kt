package work.art1st.mineRails.core.api.util.tick

/**
 * Object registered into world ticking manager.
 */
interface TickingObject {
    fun tick(deltaT: Double)
}