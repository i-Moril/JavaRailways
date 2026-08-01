package work.art1st.javarailways.core.util

interface ThreadGuardPlatformBridge {
    fun isMainThread(): Boolean
}
