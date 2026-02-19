package work.art1st.mineRails.core.api.util

interface TickManager {
    fun register(obj: TickingObject)
    fun unregister(obj: TickingObject)
    fun preTick()
    fun postTick()
}