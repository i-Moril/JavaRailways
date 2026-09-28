package work.art1st.javarailways.simplesignals.runtime

import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import work.art1st.javarailways.basic.route.OperationalRouteLocation
import work.art1st.javarailways.basic.route.TopologyRoutePlanner
import work.art1st.javarailways.basic.route.TopologyRoutePlanningInput
import work.art1st.javarailways.basic.route.TopologyRoutePlanningResult
import work.art1st.javarailways.bukkit.api.infrastructure.InfrastructureResourceService
import work.art1st.javarailways.bukkit.api.signal.RoutePlanningRestrictionSourceService
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.simpleato.api.route.RoutePlanningRestrictionBinding
import work.art1st.javarailways.simplesignals.region.ProtectionBoundaryDefinition
import work.art1st.javarailways.simplesignals.region.ProtectionRegionDefinition
import work.art1st.javarailways.simplesignals.region.ProtectionRouteDefinition
import work.art1st.javarailways.simplesignals.region.ProtectionRouteTableDefinition
import work.art1st.javarailways.simplesignals.region.ProtectionRouteTraversal
import work.art1st.javarailways.simplesignals.region.endpoint
import work.art1st.javarailways.simplesignals.region.topologyState
import work.art1st.javarailways.simplesignals.persistence.SimpleSignalsWorldDefinitionStore
import work.art1st.javarailways.simplesignals.persistence.SimpleSignalsWorldDefinitions
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class SimpleSignalsWorldRuntimeManagerTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `captured host source follows atomic publications and changes Basic route selection`() {
        val fixture = Fixture()
        val infrastructure = MutableTopologyService(fixture.currentTopology)
        val registrations = CapturingRoutePlanningService()
        val store = store()
        val manager = SimpleSignalsWorldRuntimeManager(
            plugin(), infrastructure.service, registrations, store
        )

        val installed = manager.install(fixture.worldId)
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, installed.status)
        assertEquals(SimpleSignalsWorldInstallDefinitionSource.MISSING, installed.definitionSource)
        assertFalse(Files.exists(store.primaryFile(fixture.worldId)))
        assertFalse(Files.exists(store.backupFile(fixture.worldId)))
        val binding = registrations.single(fixture.worldId)
        assertEquals(PROTECTION_ROUTE_SOURCE_ID, binding.sourceId)
        assertEquals(
            SimpleSignalsWorldInstallStatus.ALREADY_INSTALLED,
            manager.install(fixture.worldId).status
        )
        assertEquals(1, registrations.replacementCount)
        val unrestricted = fixture.plan(binding, fixture.currentTopology)
        val unrestrictedRails = unrestricted.route.segments.map { it.railId }
        val selectedRoute = if (fixture.upperIn in unrestrictedRails) fixture.lowerRoute() else fixture.upperRoute()
        val excludedRail = if (selectedRoute.routeId == "test:upper") fixture.lowerIn else fixture.upperIn

        val publication = manager.publishDefinitions(
            fixture.worldId,
            1L,
            listOf(fixture.region),
            listOf(fixture.table(1L, selectedRoute))
        )

        assertEquals(SimpleSignalsDefinitionPublicationStatus.PUBLISHED, publication.status)
        val restricted = fixture.plan(binding, fixture.currentTopology)
        val restrictedRails = restricted.route.segments.map { it.railId }
        assertTrue(selectedRoute.traversals.map { it.railId }.all(restrictedRails::contains))
        assertFalse(excludedRail in restrictedRails)
        assertEquals(1, registrations.replacementCount)

        val retained = binding.source.restrictions(fixture.currentTopology)
        val rejected = manager.publishDefinitions(
            fixture.worldId,
            2L,
            listOf(fixture.region),
            listOf(fixture.table(2L, fixture.discontinuousRoute()))
        )
        assertEquals(SimpleSignalsDefinitionPublicationStatus.REJECTED, rejected.status)
        assertSame(retained, binding.source.restrictions(fixture.currentTopology))
    }

    @Test
    fun `topology change fails closed until republish and same UUID reload gets a fresh runtime`() {
        val fixture = Fixture()
        val infrastructure = MutableTopologyService(fixture.currentTopology)
        val registrations = CapturingRoutePlanningService()
        val manager = SimpleSignalsWorldRuntimeManager(
            plugin(), infrastructure.service, registrations, store()
        )
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(fixture.worldId).status)
        val firstBinding = registrations.single(fixture.worldId)
        val route = fixture.upperRoute()
        assertEquals(
            SimpleSignalsDefinitionPublicationStatus.PUBLISHED,
            manager.publishDefinitions(
                fixture.worldId, 1L, listOf(fixture.region), listOf(fixture.table(1L, route))
            ).status
        )

        val newerTopology = fixture.topology(42L)
        infrastructure.topology = newerTopology
        assertThrows(IllegalStateException::class.java) {
            firstBinding.source.restrictions(newerTopology)
        }
        assertEquals(
            SimpleSignalsDefinitionPublicationStatus.PUBLISHED,
            manager.publishDefinitions(
                fixture.worldId, 2L, listOf(fixture.region), listOf(fixture.table(1L, route))
            ).status
        )
        fixture.plan(firstBinding, newerTopology)

        manager.remove(fixture.worldId)
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(fixture.worldId).status)
        val reloadedBinding = registrations.single(fixture.worldId)
        assertNotSame(firstBinding, reloadedBinding)
        assertTrue(reloadedBinding.source.restrictions(newerTopology).pathReplacements.isEmpty())
        assertEquals(2, registrations.replacementCount)
    }

    @Test
    fun `missing host railway world leaves no partial runtime`() {
        val fixture = Fixture()
        val infrastructure = MutableTopologyService(null)
        val registrations = CapturingRoutePlanningService()
        val manager = SimpleSignalsWorldRuntimeManager(
            plugin(), infrastructure.service, registrations, store()
        )

        val unavailable = manager.install(fixture.worldId)

        assertEquals(SimpleSignalsWorldInstallStatus.UNAVAILABLE, unavailable.status)
        assertTrue(unavailable.detail.contains("not registered"))
        assertEquals(0, registrations.replacementCount)
        assertThrows(IllegalStateException::class.java) {
            manager.publishDefinitions(fixture.worldId, 1L, emptyList(), emptyList())
        }
    }

    @Test
    fun `persisted route table is compiled before its source is registered`() {
        val fixture = Fixture()
        val store = store()
        store.save(
            fixture.worldId,
            SimpleSignalsWorldDefinitions(
                fixture.worldId,
                1L,
                listOf(fixture.region),
                listOf(fixture.table(1L, fixture.upperRoute()))
            )
        )
        val registrations = CapturingRoutePlanningService()
        val manager = SimpleSignalsWorldRuntimeManager(
            plugin(), MutableTopologyService(fixture.currentTopology).service, registrations, store
        )

        val installed = manager.install(fixture.worldId)

        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, installed.status)
        assertEquals(SimpleSignalsWorldInstallDefinitionSource.PRIMARY, installed.definitionSource)
        val rails = fixture.plan(registrations.single(fixture.worldId), fixture.currentTopology)
            .route.segments.map { it.railId }
        assertTrue(fixture.upperIn in rails)
        assertTrue(fixture.upperOut in rails)
        assertFalse(fixture.lowerIn in rails)
        assertFalse(fixture.lowerOut in rails)
        assertEquals(1, registrations.replacementCount)
    }

    @Test
    fun `persisted orphan definition installs only a fail closed source`() {
        val fixture = Fixture()
        val missingRail = UUID(9L, 9L)
        val orphan = ProtectionRegionDefinition(
            "test:orphan",
            1L,
            listOf(
                ProtectionBoundaryDefinition(
                    "test:orphan-boundary",
                    RailPositionSnapshot(missingRail, ViewDirection.AB, 1.0)
                )
            )
        )
        val store = store()
        store.save(
            fixture.worldId,
            SimpleSignalsWorldDefinitions(fixture.worldId, 1L, listOf(orphan), emptyList())
        )
        val registrations = CapturingRoutePlanningService()
        val manager = SimpleSignalsWorldRuntimeManager(
            plugin(), MutableTopologyService(fixture.currentTopology).service, registrations, store
        )

        val unavailable = manager.install(fixture.worldId)

        assertEquals(SimpleSignalsWorldInstallStatus.UNAVAILABLE, unavailable.status)
        assertTrue(unavailable.detail.contains("rejected"))
        assertEquals(1, registrations.replacementCount)
        assertThrows(IllegalStateException::class.java) {
            registrations.single(fixture.worldId).source.restrictions(fixture.currentTopology)
        }
        assertThrows(IllegalStateException::class.java) {
            manager.publishDefinitions(fixture.worldId, 2L, emptyList(), emptyList())
        }
    }

    @Test
    fun `corrupted persisted definitions install only a fail closed source`() {
        val fixture = Fixture()
        val store = store()
        Files.createDirectories(store.primaryFile(fixture.worldId).parent)
        Files.writeString(store.primaryFile(fixture.worldId), "broken-primary")
        Files.writeString(store.backupFile(fixture.worldId), "broken-backup")
        val registrations = CapturingRoutePlanningService()
        val manager = SimpleSignalsWorldRuntimeManager(
            plugin(), MutableTopologyService(fixture.currentTopology).service, registrations, store
        )

        val unavailable = manager.install(fixture.worldId)

        assertEquals(SimpleSignalsWorldInstallStatus.UNAVAILABLE, unavailable.status)
        assertTrue(unavailable.detail.contains("invalid"))
        assertEquals(2, unavailable.diagnostics.size)
        assertEquals(1, registrations.replacementCount)
        assertThrows(IllegalStateException::class.java) {
            registrations.single(fixture.worldId).source.restrictions(fixture.currentTopology)
        }
    }

    private fun store() = SimpleSignalsWorldDefinitionStore(directory.toFile())

    private class MutableTopologyService(initial: TopologyState?) {
        var topology: TopologyState? = initial
        val service: InfrastructureResourceService = proxy(InfrastructureResourceService::class.java) { method, arguments ->
            when (method.name) {
                "currentTopology" -> topology
                    ?: throw IllegalStateException("Railway world is not registered: ${arguments?.single()}")
                else -> throw UnsupportedOperationException(method.name)
            }
        }
    }

    private class CapturingRoutePlanningService : RoutePlanningRestrictionSourceService {
        private val bindings = linkedMapOf<UUID, List<RoutePlanningRestrictionBinding>>()
        var replacementCount: Int = 0
            private set

        override fun replaceRoutePlanningSources(
            pluginOwner: Plugin,
            worldId: UUID,
            sources: Collection<RoutePlanningRestrictionBinding>
        ) {
            replacementCount++
            bindings[worldId] = java.util.List.copyOf(sources)
        }

        fun single(worldId: UUID): RoutePlanningRestrictionBinding = bindings.getValue(worldId).single()
    }

    private class Fixture {
        val worldId: UUID = UUID.randomUUID()
        val entry = rail(1)
        val upperIn = rail(2)
        val lowerIn = rail(3)
        val upperOut = rail(4)
        val lowerOut = rail(5)
        val exit = rail(6)
        val currentTopology: TopologyState = topology(41L)
        val region = ProtectionRegionDefinition(
            "test:throat",
            1L,
            listOf(
                ProtectionBoundaryDefinition(
                    "test:entry", RailPositionSnapshot(entry, ViewDirection.AB, 5.0)
                ),
                ProtectionBoundaryDefinition(
                    "test:exit", RailPositionSnapshot(exit, ViewDirection.BA, 5.0)
                )
            )
        )

        fun topology(revision: Long): TopologyState = topologyState(
            revision,
            listOf(entry, upperIn, lowerIn, upperOut, lowerOut, exit),
            listOf(
                endpoint(entry, RailEndpoint.B) to endpoint(upperIn, RailEndpoint.A),
                endpoint(entry, RailEndpoint.B) to endpoint(lowerIn, RailEndpoint.A),
                endpoint(upperIn, RailEndpoint.B) to endpoint(upperOut, RailEndpoint.A),
                endpoint(upperIn, RailEndpoint.B) to endpoint(lowerOut, RailEndpoint.A),
                endpoint(lowerIn, RailEndpoint.B) to endpoint(lowerOut, RailEndpoint.A),
                endpoint(lowerIn, RailEndpoint.B) to endpoint(upperOut, RailEndpoint.A),
                endpoint(upperOut, RailEndpoint.B) to endpoint(exit, RailEndpoint.A),
                endpoint(lowerOut, RailEndpoint.B) to endpoint(exit, RailEndpoint.A)
            )
        )

        fun upperRoute() = route("test:upper", upperIn, upperOut)

        fun lowerRoute() = route("test:lower", lowerIn, lowerOut)

        fun discontinuousRoute() = ProtectionRouteDefinition(
            "test:broken",
            "test:entry",
            "test:exit",
            listOf(entryTraversal(), exitTraversal())
        )

        fun table(revision: Long, route: ProtectionRouteDefinition) = ProtectionRouteTableDefinition(
            "test:throat-table", revision, region.regionId, region.revision, listOf(route)
        )

        fun plan(
            binding: RoutePlanningRestrictionBinding,
            topology: TopologyState
        ): TopologyRoutePlanningResult.Planned = TopologyRoutePlanner.plan(
            TopologyRoutePlanningInput(
                topology,
                binding.source.restrictions(topology),
                OperationalRouteLocation(entry, 0.0),
                OperationalRouteLocation(exit, 10.0),
                "test:plan",
                1L,
                ViewDirection.AB,
                ViewDirection.AB
            )
        ) as TopologyRoutePlanningResult.Planned

        private fun route(id: String, first: UUID, second: UUID) = ProtectionRouteDefinition(
            id,
            "test:entry",
            "test:exit",
            listOf(
                entryTraversal(),
                traversal(first),
                traversal(second),
                exitTraversal()
            )
        )

        private fun entryTraversal() =
            ProtectionRouteTraversal(entry, ViewDirection.AB, 5.0, 10.0)

        private fun exitTraversal() =
            ProtectionRouteTraversal(exit, ViewDirection.AB, 0.0, 5.0)

        private fun traversal(railId: UUID) =
            ProtectionRouteTraversal(railId, ViewDirection.AB, 0.0, 10.0)

        private fun rail(number: Long) = UUID(0L, number)
    }

    companion object {
        private fun plugin(): Plugin = proxy(Plugin::class.java) { method, _ ->
            when (method.name) {
                "isEnabled" -> true
                "getName" -> "SimpleSignals"
                "toString" -> "SimpleSignals"
                "hashCode" -> 1
                "equals" -> false
                else -> throw UnsupportedOperationException(method.name)
            }
        }

        private fun <T> proxy(
            type: Class<T>,
            handler: (java.lang.reflect.Method, Array<out Any?>?) -> Any?
        ): T = type.cast(
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, arguments ->
                handler(method, arguments)
            }
        )
    }
}
