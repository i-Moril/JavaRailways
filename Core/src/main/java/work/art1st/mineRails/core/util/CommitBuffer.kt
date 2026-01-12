package work.art1st.mineRails.core.util

class CommitBuffer<T>(
    default: T,
){
    private var active: Int = 0
    @Suppress("UNCHECKED_CAST")
    private var buffer: Array<T> = arrayOf<Any>(default as Any) as Array<T>

    fun set(value: T) {
        buffer[active] = value
    }

    fun get() : T {
        return buffer[1 - active]
    }

    fun getActive() : T {
        return buffer[active]
    }

    fun commit() {
        active = 1 - active
    }
}