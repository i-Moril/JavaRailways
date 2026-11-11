package work.art1st.javarailways.core.api.threading.host

import work.art1st.javarailways.core.util.ThreadGuardBridgeRegistry

/**
 * Trusted platform-host registration surface for JavaRailways thread ownership.
 *
 * This is a startup/shutdown integration API, not a consumer scheduling API. The first live
 * registration wins; another registration is rejected until its identity token is closed.
 */
object ThreadGuardHost {
    /**
     * Registers [bridge] process-wide and returns its exact lifecycle token.
     *
     * The trusted host calls this synchronously during startup before normal runtime work begins,
     * retains the token, and closes it during shutdown after dependent work has stopped. This fails
     * with [IllegalStateException] when a registration is already active.
     */
    fun registerPlatformBridge(bridge: ThreadGuardPlatformBridge): ThreadGuardRegistration =
        ThreadGuardBridgeRegistry.register(bridge)
}
