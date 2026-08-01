package work.art1st.javarailways.core.api.util

interface Destroyable {
    fun destroy()
    fun canDestroy(): Boolean = true
}
