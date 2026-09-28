package work.art1st.javarailways.bukkit.api.internal

import org.bukkit.Bukkit
import org.bukkit.Server
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.PluginManager
import org.bukkit.plugin.SimpleServicesManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import work.art1st.javarailways.bukkit.api.infrastructure.InfrastructureResourceService
import work.art1st.javarailways.bukkit.api.observation.RailwayObservationService
import work.art1st.javarailways.bukkit.api.operation.PlayerOperationService
import work.art1st.javarailways.bukkit.api.rail.RailTargetService
import work.art1st.javarailways.bukkit.api.signal.InspectionSourceService
import work.art1st.javarailways.bukkit.api.signal.SignalEpochSourceService
import work.art1st.javarailways.bukkit.api.signal.RoutePlanningRestrictionSourceService
import java.lang.reflect.Proxy
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.logging.Logger

class JavaRailwaysServiceRegistrationTest {
    @Test
    fun `production registrar exposes all six public services through Bukkit discovery`() = withBukkitServer {
        val manager = SimpleServicesManager()
        val owner = plugin("JavaRailways")
        val railTargets = RailTargetService { _, _, _ ->
            CompletableFuture.completedFuture(Optional.empty())
        }
        val playerOperations = DefaultPlayerOperationService(Logger.getLogger("service-registration-test"))
        val observations = DefaultRailwayObservationService(Logger.getLogger("service-registration-test"))
        val signalSources = DefaultSignalEpochSourceService()
        val infrastructure = DefaultInfrastructureResourceService(signalSources)
        try {
            manager.registerJavaRailwaysServices(
                owner,
                railTargets,
                playerOperations,
                observations,
                infrastructure,
                signalSources,
                signalSources,
                signalSources
            )

            assertSame(railTargets, manager.load(RailTargetService::class.java))
            assertSame(playerOperations, manager.load(PlayerOperationService::class.java))
            assertSame(observations, manager.load(RailwayObservationService::class.java))
            assertSame(infrastructure, manager.load(InfrastructureResourceService::class.java))
            assertSame(signalSources, manager.load(SignalEpochSourceService::class.java))
            assertSame(signalSources, manager.load(InspectionSourceService::class.java))
            assertSame(
                signalSources,
                manager.load(RoutePlanningRestrictionSourceService::class.java)
            )
            assertEquals(7, manager.getRegistrations(owner).size)

            manager.unregisterAll(owner)
            assertNull(manager.load(RailTargetService::class.java))
            assertNull(manager.load(PlayerOperationService::class.java))
            assertNull(manager.load(RailwayObservationService::class.java))
            assertNull(manager.load(InfrastructureResourceService::class.java))
            assertNull(manager.load(SignalEpochSourceService::class.java))
            assertNull(manager.load(InspectionSourceService::class.java))
            assertNull(manager.load(RoutePlanningRestrictionSourceService::class.java))
        } finally {
            infrastructure.close()
            signalSources.close()
            observations.close()
            playerOperations.clear()
        }
    }

    private fun <T> withBukkitServer(block: () -> T): T = synchronized(Bukkit::class.java) {
        val serverField = Bukkit::class.java.getDeclaredField("server").also { it.isAccessible = true }
        val previous = serverField.get(null)
        val pluginManager = Proxy.newProxyInstance(
            PluginManager::class.java.classLoader,
            arrayOf(PluginManager::class.java)
        ) { _, method, _ -> primitiveDefault(method.returnType) } as PluginManager
        val logger = Logger.getLogger("service-registration-test")
        val server = Proxy.newProxyInstance(
            Server::class.java.classLoader,
            arrayOf(Server::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getPluginManager" -> pluginManager
                "getLogger" -> logger
                "getName" -> "TestServer"
                "getVersion", "getBukkitVersion" -> "test"
                else -> primitiveDefault(method.returnType)
            }
        } as Server
        serverField.set(null, server)
        try {
            block()
        } finally {
            serverField.set(null, previous)
        }
    }

    private fun plugin(name: String): Plugin {
        lateinit var proxy: Plugin
        proxy = Proxy.newProxyInstance(
            Plugin::class.java.classLoader,
            arrayOf(Plugin::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getName", "toString" -> name
                "isEnabled" -> true
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> primitiveDefault(method.returnType)
            }
        } as Plugin
        return proxy
    }

    private fun primitiveDefault(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Character.TYPE -> '\u0000'
        else -> null
    }
}
