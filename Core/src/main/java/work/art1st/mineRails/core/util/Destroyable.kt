package work.art1st.mineRails.core.util

interface Destroyable {
    fun destroy()
    fun canDestroy(): Boolean = true
}