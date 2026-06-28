package work.art1st.mineRails.core.api.util

interface Destroyable {
    fun destroy()
    fun canDestroy(): Boolean = true
}
