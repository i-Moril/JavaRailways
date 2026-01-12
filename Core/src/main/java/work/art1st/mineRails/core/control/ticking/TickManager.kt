package work.art1st.mineRails.core.control.ticking

interface TickManager {
    fun register(tickingObject: Any)
    fun unregister(tickingObject: Any)
    fun shutdown()
}