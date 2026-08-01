package work.art1st.javarailways.core.util

import kotlin.reflect.KProperty

class CommitBuffer<T>(
    default: T,
){
    @Volatile
    private var active: Int = 0
    private val buffer: Array<Any?> = arrayOf(default, default)

    operator fun getValue(thisRef: Any?, property: KProperty<*>) : T {
        return get()
    }

    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        set(value)
    }

    fun set(value: T) {
        buffer[active] = value
    }

    @Suppress("UNCHECKED_CAST")
    fun get() : T {
        val activeSnapshot = active
        return buffer[1 - activeSnapshot] as T
    }

    @Suppress("UNCHECKED_CAST")
    fun getActive() : T {
        return buffer[active] as T
    }

    /** Publishes the active slot. The volatile flip provides release/acquire visibility. */
    fun commit() {
        active = 1 - active
    }
}