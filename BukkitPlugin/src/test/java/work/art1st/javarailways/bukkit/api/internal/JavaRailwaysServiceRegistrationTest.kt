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
import work.art1st.javarailways.bukkit.api.model.ModelPresentationService
import work.art1st.javarailways.bukkit.api.editor.EditorTextService
import work.art1st.javarailways.bukkit.render.backend.DisplayRenderBackend
import work.art1st.javarailways.bukkit.render.backend.DisplayVisualDescriptor
import work.art1st.javarailways.bukkit.render.backend.DisplayVisual
import work.art1st.javarailways.bukkit.util.PlatformDispatcher
import work.art1st.javarailways.bukkit.api.signal.InspectionSourceService
import work.art1st.javarailways.bukkit.api.signal.SignalEpochSourceService
import work.art1st.javarailways.bukkit.api.signal.RoutePlanningRestrictionSourceService
import java.lang.reflect.Proxy
import work.art1st.javarailways.bukkit.logic.WorldManager
import java.util.logging.Logger

class JavaRailwaysServiceRegistrationTest {
    @Test
    fun `production registrar exposes all ten public services through Bukkit discovery`() = BukkitServiceTestSupport.withServer {
        val manager = SimpleServicesManager()
        val owner = plugin("JavaRailways")
        val worldManager = WorldManager()
        val railTargets = BukkitRailTargetService({ worldManager }, { error("No player query in this test") })
        val playerOperations = DefaultPlayerOperationService(Logger.getLogger("service-registration-test"))
        val observations = DefaultRailwayObservationService(Logger.getLogger("service-registration-test"))
        val simulationFeatures = DefaultSimulationFeatureService()
        val signalSources = DefaultSignalEpochSourceService()
        val infrastructure = DefaultInfrastructureResourceService(signalSources)
        val dispatcher = Proxy.newProxyInstance(PlatformDispatcher::class.java.classLoader, arrayOf(PlatformDispatcher::class.java)) {
            _, method, _ -> error("Registrar must not dispatch ${method.name}")
        } as PlatformDispatcher
        val models = DefaultModelPresentationService(owner, dispatcher, object : DisplayRenderBackend {
            override fun create(world: org.bukkit.World, descriptor: DisplayVisualDescriptor): DisplayVisual = error("Registrar must not create models")
        }, 1.0f, Logger.getLogger("service-registration-test"))
        val editorTexts = DefaultEditorTextService(owner, dispatcher, Logger.getLogger("service-registration-test"))
        try {
            manager.registerJavaRailwaysServices(
                owner,
                railTargets,
                playerOperations,
                observations,
                infrastructure,
                signalSources,
                signalSources,
                signalSources,
                models,
                editorTexts,
                simulationFeatures
            )

            assertSame(simulationFeatures, manager.load(work.art1st.javarailways.bukkit.api.simulation.SimulationFeatureService::class.java))
            assertSame(railTargets, manager.load(RailTargetService::class.java))
            assertSame(playerOperations, manager.load(PlayerOperationService::class.java))
            assertSame(models, manager.load(ModelPresentationService::class.java))
            assertSame(editorTexts, manager.load(EditorTextService::class.java))
            assertSame(observations, manager.load(RailwayObservationService::class.java))
            assertSame(infrastructure, manager.load(InfrastructureResourceService::class.java))
            assertSame(signalSources, manager.load(SignalEpochSourceService::class.java))
            assertSame(signalSources, manager.load(InspectionSourceService::class.java))
            assertSame(
                signalSources,
                manager.load(RoutePlanningRestrictionSourceService::class.java)
            )
            assertEquals(10, manager.getRegistrations(owner).size)

            manager.unregisterAll(owner)
            assertNull(manager.load(work.art1st.javarailways.bukkit.api.simulation.SimulationFeatureService::class.java))
            assertNull(manager.load(RailTargetService::class.java))
            assertNull(manager.load(PlayerOperationService::class.java))
            assertNull(manager.load(ModelPresentationService::class.java))
            assertNull(manager.load(EditorTextService::class.java))
            assertNull(manager.load(RailwayObservationService::class.java))
            assertNull(manager.load(InfrastructureResourceService::class.java))
            assertNull(manager.load(SignalEpochSourceService::class.java))
            assertNull(manager.load(InspectionSourceService::class.java))
            assertNull(manager.load(RoutePlanningRestrictionSourceService::class.java))
        } finally {
            simulationFeatures.close()
            editorTexts.close()
            models.close()
            infrastructure.close()
            signalSources.close()
            observations.close()
            playerOperations.clear()
            worldManager.stop()
            worldManager.finishStopping()
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
