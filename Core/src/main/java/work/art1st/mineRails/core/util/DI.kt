package work.art1st.mineRails.core.util

interface DependencyContainer {
    fun <T> get(type: Class<T>): T {

    }
}