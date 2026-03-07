package work.art1st.mineRails.core.util

interface Factory<T> {
    fun create(vararg args: Any)
}