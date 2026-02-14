package work.art1st.mineRails.core.api.util

interface TickingObject : SynchronizedObject {
    fun tick(deltaT: Double)
    override fun commit() {}
}