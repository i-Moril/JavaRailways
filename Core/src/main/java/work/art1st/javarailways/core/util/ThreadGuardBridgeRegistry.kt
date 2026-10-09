package work.art1st.javarailways.core.util

import work.art1st.javarailways.core.api.threading.host.ThreadGuardPlatformBridge
import work.art1st.javarailways.core.api.threading.host.ThreadGuardRegistration
import java.util.concurrent.atomic.AtomicBoolean

/** Core-owned storage behind the public consumer guard and trusted host registration surface. */
internal object ThreadGuardBridgeRegistry {
    @Volatile
    private var current: Entry? = null

    fun isMainThread(): Boolean =
        IsolatedSimulationThreadMarker.isActive() || current?.bridge?.isMainThread() == true

    @Synchronized
    fun register(bridge: ThreadGuardPlatformBridge): ThreadGuardRegistration {
        check(current == null) { "a ThreadGuard platform bridge is already registered" }
        val entry = Entry(bridge)
        current = entry
        return Registration(entry)
    }

    private class Entry(val bridge: ThreadGuardPlatformBridge)

    private class Registration(
        private val entry: Entry
    ) : ThreadGuardRegistration {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            synchronized(ThreadGuardBridgeRegistry) {
                if (current === entry) current = null
            }
        }
    }
}
