package work.art1st.javarailways.simplesignals.runtime

import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import work.art1st.javarailways.basic.route.OperationalRouteLocation
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner
import work.art1st.javarailways.basic.api.infrastructure.TopologyEditBlocker
import work.art1st.javarailways.basic.api.infrastructure.TopologyEditBlockerKind
import work.art1st.javarailways.basic.api.infrastructure.TopologyEditDecision
import work.art1st.javarailways.basic.api.infrastructure.TopologyEditOverlap
import work.art1st.javarailways.basic.api.infrastructure.TopologyEditRequest
import work.art1st.javarailways.basic.api.infrastructure.TopologyEditStatus
import work.art1st.javarailways.basic.route.TopologyRoutePlanner
import work.art1st.javarailways.basic.route.TopologyRoutePlanningInput
import work.art1st.javarailways.basic.route.TopologyRoutePlanningResult
import work.art1st.javarailways.bukkit.api.infrastructure.InfrastructureResourceService
import work.art1st.javarailways.bukkit.api.infrastructure.InfrastructureEditPermit
import work.art1st.javarailways.bukkit.api.infrastructure.InfrastructureEditPermitResult
import work.art1st.javarailways.bukkit.api.signal.RoutePlanningRestrictionSourceService
import work.art1st.javarailways.bukkit.api.signal.SignalEpochSourceService
import work.art1st.javarailways.bukkit.api.signal.InspectionSourceService
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.core.util.IsolatedMainThreadMarker
import work.art1st.javarailways.simpleato.api.route.RoutePlanningRestrictionBinding
import work.art1st.javarailways.simpleato.api.inspection.InspectionSource
import work.art1st.javarailways.simpleato.api.signal.SignalEpochSource
import work.art1st.javarailways.simplesignals.block.FixedBlockDefinition
import work.art1st.javarailways.simplesignals.block.FIXED_BLOCK_SIGNAL_SOURCE_ID
import work.art1st.javarailways.simplesignals.block.FixedBlockTraversal
import work.art1st.javarailways.simplesignals.region.ProtectionBoundaryDefinition
import work.art1st.javarailways.simplesignals.region.ProtectionRegionDefinition
import work.art1st.javarailways.simplesignals.region.PROTECTION_REGION_INSPECTION_SOURCE_ID
import work.art1st.javarailways.simplesignals.region.ProtectionRouteDefinition
import work.art1st.javarailways.simplesignals.region.ProtectionRouteTableDefinition
import work.art1st.javarailways.simplesignals.region.ProtectionRouteTraversal
import work.art1st.javarailways.simplesignals.region.endpoint
import work.art1st.javarailways.simplesignals.region.topologyState
import work.art1st.javarailways.simplesignals.persistence.SimpleSignalsWorldDefinitionStore
import work.art1st.javarailways.simplesignals.persistence.SimpleSignalsWorldDefinitions
import work.art1st.javarailways.simplesignals.signal.ConfiguredSignalOutput
import work.art1st.javarailways.simplesignals.signal.FixedBlockSignalClearLevel
import work.art1st.javarailways.simplesignals.signal.FixedBlockSignalDefinition
import work.art1st.javarailways.simplesignals.signal.ProtectionSignalDefinition
import work.art1st.javarailways.simplesignals.signal.ProtectionSignalKind
import work.art1st.javarailways.simplesignals.signal.RouteIndicatorDefinition
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class SimpleSignalsWorldRuntimeManagerTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `online three device patch persists atomically validates revisions and removes without orphaning`() {
        val fixture = Fixture()
        val infrastructure = MutableTopologyService(fixture.currentTopology)
        val sources = CapturingSignalEpochSourceService()
        val store = store()
        val manager = runtimeManager(plugin(), infrastructure.service, CapturingRoutePlanningService(), store, sources)
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(fixture.worldId).status)
        val route = fixture.upperRoute()
        fun signal(id: String, kind: ProtectionSignalKind, binding: String, revision: Long = 1L) =
            ProtectionSignalDefinition(id, revision, kind, binding, 2.0, ConfiguredSignalOutput(0.0, "custom/none"),
                ConfiguredSignalOutput(0.0, "custom/occupied"), listOf(
                    FixedBlockSignalClearLevel(0.0, ConfiguredSignalOutput(17.0, "custom/clear"))))
        val fixed = signal("test:fixed", ProtectionSignalKind.FIXED_ROUTE, route.routeId)
        val current = signal("test:current", ProtectionSignalKind.CURRENT_ENTRY, route.entryBoundaryId)
        val indicator = RouteIndicatorDefinition("test:indicator", 1, route.entryBoundaryId,
            RailPositionSnapshot(fixture.entry, ViewDirection.AB, 0.0), "custom/no-route",
            mapOf(route.routeId to "custom/upper"))
        val applied = manager.applyDefinitionPatch(fixture.worldId, 0, SimpleSignalsDefinitionPatch(
            upsertRegions = listOf(fixture.region), upsertRouteTables = listOf(fixture.table(1, route)),
            upsertProtectionSignals = listOf(fixed, current), upsertRouteIndicators = listOf(indicator)))
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED, applied.status, applied.diagnostics.toString())
        val encoded = Files.readString(store.primaryFile(fixture.worldId))
        val restored = work.art1st.javarailways.simplesignals.persistence.SimpleSignalsWorldDefinitionsCodec.decode(encoded, fixture.worldId)
        assertEquals(setOf(fixed, current), restored.protectionSignals.toSet())
        assertEquals(listOf(indicator), restored.routeIndicators)
        val installed = sources.sources.getValue(fixture.worldId).single()
        listOf(TopologyEditBlockerKind.INFRASTRUCTURE_DEPENDENCY,
            TopologyEditBlockerKind.LIVE_RUNTIME_CLAIM).forEach { kind ->
            infrastructure.nextEditDecision = TopologyEditDecision(TopologyEditStatus.CONFLICT, listOf(
                TopologyEditBlocker(kind, InfrastructureOwner("test:operator", 1),
                    TopologyEditOverlap(setOf(fixture.entry), emptySet(), emptySet()))))
            val blocked = manager.applyDefinitionPatch(fixture.worldId, 1, SimpleSignalsDefinitionPatch(
                upsertProtectionSignals = listOf(
                    signal(fixed.signalId, fixed.kind, fixed.bindingId, 2),
                    signal(current.signalId, current.kind, current.bindingId, 2)),
                upsertRouteIndicators = listOf(RouteIndicatorDefinition(indicator.indicatorId, 2,
                    indicator.entryBoundaryId, indicator.position, indicator.noRouteModelVariant,
                    indicator.modelVariantsByRouteId))))
            assertEquals(SimpleSignalsDefinitionAuthoringStatus.EDIT_BLOCKED, blocked.status)
            assertEquals(kind, blocked.editDecision?.blockers?.single()?.kind)
            assertSame(installed, sources.sources.getValue(fixture.worldId).single())
            assertEquals(encoded, Files.readString(store.primaryFile(fixture.worldId)))
        }
        infrastructure.nextEditDecision = TopologyEditDecision(TopologyEditStatus.GRANTED)
        val rejected = manager.applyDefinitionPatch(fixture.worldId, 1, SimpleSignalsDefinitionPatch(
            removeRouteTableIds = listOf("test:throat-table")))
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.VALIDATION_REJECTED, rejected.status)
        assertSame(installed, sources.sources.getValue(fixture.worldId).single())
        assertEquals(encoded, Files.readString(store.primaryFile(fixture.worldId)))
        val unchangedRevision = manager.applyDefinitionPatch(fixture.worldId, 1, SimpleSignalsDefinitionPatch(
            upsertProtectionSignals = listOf(signal(fixed.signalId, fixed.kind, "test:missing"))))
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.VALIDATION_REJECTED, unchangedRevision.status)
        val updated = manager.applyDefinitionPatch(fixture.worldId, 1, SimpleSignalsDefinitionPatch(
            upsertProtectionSignals = listOf(signal(fixed.signalId, fixed.kind, fixed.bindingId, 2))))
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED, updated.status)
        val removed = manager.applyDefinitionPatch(fixture.worldId, 2, SimpleSignalsDefinitionPatch(
            removeProtectionSignalIds = listOf(fixed.signalId, current.signalId),
            removeRouteIndicatorIds = listOf(indicator.indicatorId), removeRouteTableIds = listOf("test:throat-table")))
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED, removed.status)
        assertTrue(removed.definitions!!.protectionSignals.isEmpty())
        assertTrue(removed.definitions.routeIndicators.isEmpty())
        assertEquals(3, infrastructure.permitCloseCount)
    }

    @Test
    fun `protection device save failure keeps exact old disk and host runtime source`() {
        val fixture = Fixture()
        val route = fixture.upperRoute()
        val seedStore = store()
        seedStore.save(fixture.worldId, SimpleSignalsWorldDefinitions(fixture.worldId, 1,
            listOf(fixture.region), listOf(fixture.table(1, route))))
        val primary = Files.readString(seedStore.primaryFile(fixture.worldId))
        val failing = SimpleSignalsWorldDefinitionStore(directory.toFile()) {
            throw java.io.IOException("injected device staging failure")
        }
        val infrastructure = MutableTopologyService(fixture.currentTopology)
        val sources = CapturingSignalEpochSourceService()
        val manager = runtimeManager(plugin(), infrastructure.service, CapturingRoutePlanningService(), failing, sources)
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(fixture.worldId).status)
        val retained = sources.sources.getValue(fixture.worldId).single()
        val device = ProtectionSignalDefinition("test:fixed", 1, ProtectionSignalKind.FIXED_ROUTE, route.routeId,
            0.0, ConfiguredSignalOutput(0.0, "custom/none"), ConfiguredSignalOutput(0.0, "custom/occupied"),
            listOf(FixedBlockSignalClearLevel(0.0, ConfiguredSignalOutput(17.0, "custom/clear"))))
        val failed = manager.applyDefinitionPatch(fixture.worldId, 1,
            SimpleSignalsDefinitionPatch(upsertProtectionSignals = listOf(device)))
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.PERSISTENCE_FAILED, failed.status)
        assertSame(retained, sources.sources.getValue(fixture.worldId).single())
        assertEquals(primary, Files.readString(seedStore.primaryFile(fixture.worldId)))
        assertEquals(1, infrastructure.permitCloseCount)
    }

    @Test
    fun `definition authoring rejects platform owner thread before doing preparation work`() {
        val fixture = Fixture()
        val manager = runtimeManager(
            plugin(),
            MutableTopologyService(fixture.currentTopology).service,
            CapturingRoutePlanningService(),
            store(),
            CapturingSignalEpochSourceService()
        )

        val failure = assertThrows(IllegalStateException::class.java) {
            IsolatedMainThreadMarker.run {
                manager.applyDefinitionPatch(
                    fixture.worldId,
                    0L,
                    SimpleSignalsDefinitionPatch()
                )
            }
        }

        assertTrue(failure.message.orEmpty().contains("must not run on the main thread"))
    }

    @Test
    fun `captured host source follows atomic publications and changes Basic route selection`() {
        val fixture = Fixture()
        val infrastructure = MutableTopologyService(fixture.currentTopology)
        val registrations = CapturingRoutePlanningService()
        val store = store()
        val manager = runtimeManager(
            plugin(), infrastructure.service, registrations, store, signalSources()
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

        val publication = manager.applyDefinitionPatch(
            fixture.worldId,
            0L,
            SimpleSignalsDefinitionPatch(
                upsertRegions = listOf(fixture.region),
                upsertRouteTables = listOf(fixture.table(1L, selectedRoute))
            )
        )

        assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED, publication.status)
        val restricted = fixture.plan(binding, fixture.currentTopology)
        val restrictedRails = restricted.route.segments.map { it.railId }
        assertTrue(selectedRoute.traversals.map { it.railId }.all(restrictedRails::contains))
        assertFalse(excludedRail in restrictedRails)
        assertEquals(1, registrations.replacementCount)

        val retained = binding.source.restrictions(fixture.currentTopology)
        val rejected = manager.applyDefinitionPatch(
            fixture.worldId,
            1L,
            SimpleSignalsDefinitionPatch(
                upsertRouteTables = listOf(fixture.table(2L, fixture.discontinuousRoute()))
            )
        )
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.VALIDATION_REJECTED, rejected.status)
        assertSame(retained, binding.source.restrictions(fixture.currentTopology))
    }

    @Test
    fun `topology change fails closed until reload republishes a fresh runtime`() {
        val fixture = Fixture()
        val infrastructure = MutableTopologyService(fixture.currentTopology)
        val registrations = CapturingRoutePlanningService()
        val manager = runtimeManager(
            plugin(), infrastructure.service, registrations, store(), signalSources()
        )
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(fixture.worldId).status)
        val firstBinding = registrations.single(fixture.worldId)
        val route = fixture.upperRoute()
        assertEquals(
            SimpleSignalsDefinitionAuthoringStatus.APPLIED,
            manager.applyDefinitionPatch(
                fixture.worldId,
                0L,
                SimpleSignalsDefinitionPatch(
                    upsertRegions = listOf(fixture.region),
                    upsertRouteTables = listOf(fixture.table(1L, route))
                )
            ).status
        )

        val newerTopology = fixture.topology(42L)
        infrastructure.topology = newerTopology
        assertThrows(IllegalStateException::class.java) {
            firstBinding.source.restrictions(newerTopology)
        }
        manager.remove(fixture.worldId)
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(fixture.worldId).status)
        val reloadedBinding = registrations.single(fixture.worldId)
        assertNotSame(firstBinding, reloadedBinding)
        fixture.plan(reloadedBinding, newerTopology)
        assertFalse(reloadedBinding.source.restrictions(newerTopology).pathReplacements.isEmpty())
        assertEquals(2, registrations.replacementCount)
    }

    @Test
    fun `missing host railway world leaves no partial runtime`() {
        val fixture = Fixture()
        val infrastructure = MutableTopologyService(null)
        val registrations = CapturingRoutePlanningService()
        val manager = runtimeManager(
            plugin(), infrastructure.service, registrations, store(), signalSources()
        )

        val unavailable = manager.install(fixture.worldId)

        assertEquals(SimpleSignalsWorldInstallStatus.UNAVAILABLE, unavailable.status)
        assertTrue(unavailable.detail.contains("not registered"))
        assertEquals(0, registrations.replacementCount)
        assertEquals(
            SimpleSignalsDefinitionAuthoringStatus.WORLD_UNAVAILABLE,
            manager.applyDefinitionPatch(
                fixture.worldId,
                0L,
                SimpleSignalsDefinitionPatch()
            ).status
        )
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
        val manager = runtimeManager(
            plugin(), MutableTopologyService(fixture.currentTopology).service, registrations, store,
            signalSources()
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
    fun `persisted fixed block installs one physical section source while empty definitions install none`() {
        val fixture = Fixture()
        val persistedStore = store()
        val block = FixedBlockDefinition(
            "test:approach-block",
            1L,
            listOf(FixedBlockTraversal(fixture.entry, ViewDirection.AB, 0.0, 5.0))
        )
        persistedStore.save(
            fixture.worldId,
            SimpleSignalsWorldDefinitions(
                fixture.worldId,
                1L,
                emptyList(),
                emptyList(),
                listOf(block)
            )
        )
        val signalSources = CapturingSignalEpochSourceService()
        val manager = runtimeManager(
            plugin(),
            MutableTopologyService(fixture.currentTopology).service,
            CapturingRoutePlanningService(),
            persistedStore,
            signalSources
        )

        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(fixture.worldId).status)

        val source = signalSources.sources.getValue(fixture.worldId).single()
        assertEquals(SECTION_OCCUPANCY_SOURCE_ID, source.sourceId)
        assertEquals(block.blockId, source.areas.single().areaId.toString())

        val emptyWorldId = UUID.randomUUID()
        val emptyManager = runtimeManager(
            plugin(),
            MutableTopologyService(fixture.currentTopology).service,
            CapturingRoutePlanningService(),
            persistedStore,
            signalSources
        )
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, emptyManager.install(emptyWorldId).status)
        assertEquals(emptyList<SignalEpochSource>(), signalSources.sources.getValue(emptyWorldId))
    }

    @Test
    fun `world install registers fixed block and protection region inspection sources`() {
        val fixture = Fixture()
        val registered = linkedMapOf<UUID, List<InspectionSource>>()
        val inspectionService = proxy(InspectionSourceService::class.java) { method, arguments ->
            when (method.name) {
                "replaceInspectionSources" -> {
                    @Suppress("UNCHECKED_CAST")
                    registered[arguments!![1] as UUID] =
                        java.util.List.copyOf(arguments[2] as Collection<InspectionSource>)
                    Unit
                }
                else -> throw UnsupportedOperationException(method.name)
            }
        }
        val manager = runtimeManager(
            plugin(),
            MutableTopologyService(fixture.currentTopology).service,
            CapturingRoutePlanningService(),
            store(),
            signalSources(),
            inspectionService
        )

        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(fixture.worldId).status)

        assertEquals(
            listOf(FIXED_BLOCK_SIGNAL_SOURCE_ID, PROTECTION_REGION_INSPECTION_SOURCE_ID),
            registered.getValue(fixture.worldId).map(InspectionSource::sourceId)
        )
    }

    @Test
    fun `inspection source install failure clears partial sources and leaves route planning fail closed`() {
        val fixture = Fixture()
        val store = store()
        store.save(
            fixture.worldId,
            SimpleSignalsWorldDefinitions(
                fixture.worldId,
                1L,
                emptyList(),
                emptyList(),
                listOf(
                    FixedBlockDefinition(
                        "test:approach-block",
                        1L,
                        listOf(FixedBlockTraversal(fixture.entry, ViewDirection.AB, 0.0, 5.0))
                    )
                )
            )
        )
        val signalSources = CapturingSignalEpochSourceService()
        val routeSources = CapturingRoutePlanningService()
        val inspectionReplacementSizes = arrayListOf<Int>()
        var rejectFirstInstall = true
        val inspectionService = proxy(InspectionSourceService::class.java) { method, arguments ->
            when (method.name) {
                "replaceInspectionSources" -> {
                    @Suppress("UNCHECKED_CAST")
                    val sources = arguments!![2] as Collection<InspectionSource>
                    inspectionReplacementSizes += sources.size
                    if (sources.isNotEmpty() && rejectFirstInstall) {
                        rejectFirstInstall = false
                        throw IllegalStateException("injected inspection source install failure")
                    }
                    Unit
                }
                else -> throw UnsupportedOperationException(method.name)
            }
        }
        val manager = runtimeManager(
            plugin(),
            MutableTopologyService(fixture.currentTopology).service,
            routeSources,
            store,
            signalSources,
            inspectionService
        )

        val unavailable = manager.install(fixture.worldId)

        assertEquals(SimpleSignalsWorldInstallStatus.UNAVAILABLE, unavailable.status)
        assertEquals(listOf(1, 0), signalSources.replacements.map(List<SignalEpochSource>::size))
        assertEquals(listOf(2, 0), inspectionReplacementSizes)
        assertThrows(IllegalStateException::class.java) {
            routeSources.single(fixture.worldId).source.restrictions(fixture.currentTopology)
        }
        assertNull(manager.fixedBlockSnapshot(fixture.worldId))
    }

    @Test
    fun `route source install failure clears partial section registration and installs fail closed source`() {
        val fixture = Fixture()
        val store = store()
        store.save(
            fixture.worldId,
            SimpleSignalsWorldDefinitions(
                fixture.worldId,
                1L,
                emptyList(),
                emptyList(),
                listOf(
                    FixedBlockDefinition(
                        "test:approach-block",
                        1L,
                        listOf(FixedBlockTraversal(fixture.entry, ViewDirection.AB, 0.0, 5.0))
                    )
                )
            )
        )
        val signalSources = CapturingSignalEpochSourceService()
        val routeSources = CapturingRoutePlanningService(
            IllegalStateException("injected route source install failure")
        )
        val manager = runtimeManager(
            plugin(),
            MutableTopologyService(fixture.currentTopology).service,
            routeSources,
            store,
            signalSources
        )

        val unavailable = manager.install(fixture.worldId)

        assertEquals(SimpleSignalsWorldInstallStatus.UNAVAILABLE, unavailable.status)
        assertEquals(listOf(1, 0), signalSources.replacements.map(List<SignalEpochSource>::size))
        assertEquals(emptyList<SignalEpochSource>(), signalSources.sources.getValue(fixture.worldId))
        val failClosed = routeSources.single(fixture.worldId)
        assertThrows(IllegalStateException::class.java) {
            failClosed.source.restrictions(fixture.currentTopology)
        }
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
        val manager = runtimeManager(
            plugin(), MutableTopologyService(fixture.currentTopology).service, registrations, store,
            signalSources()
        )

        val unavailable = manager.install(fixture.worldId)

        assertEquals(SimpleSignalsWorldInstallStatus.UNAVAILABLE, unavailable.status)
        assertTrue(unavailable.detail.contains("rejected"))
        assertEquals(1, registrations.replacementCount)
        assertThrows(IllegalStateException::class.java) {
            registrations.single(fixture.worldId).source.restrictions(fixture.currentTopology)
        }
        assertEquals(
            SimpleSignalsDefinitionAuthoringStatus.WORLD_UNAVAILABLE,
            manager.applyDefinitionPatch(
                fixture.worldId,
                1L,
                SimpleSignalsDefinitionPatch()
            ).status
        )
    }

    @Test
    fun `persisted orphan fixed block installs only a fail closed source`() {
        val fixture = Fixture()
        val orphan = FixedBlockDefinition(
            "test:orphan-block",
            1L,
            listOf(FixedBlockTraversal(UUID(9L, 9L), ViewDirection.AB, 0.0, 1.0))
        )
        val store = store()
        store.save(
            fixture.worldId,
            SimpleSignalsWorldDefinitions(
                fixture.worldId,
                1L,
                emptyList(),
                emptyList(),
                listOf(orphan)
            )
        )
        val registrations = CapturingRoutePlanningService()
        val manager = runtimeManager(
            plugin(),
            MutableTopologyService(fixture.currentTopology).service,
            registrations,
            store,
            signalSources()
        )

        val unavailable = manager.install(fixture.worldId)

        assertEquals(SimpleSignalsWorldInstallStatus.UNAVAILABLE, unavailable.status)
        assertTrue(unavailable.detail.contains("rejected"))
        assertEquals(1, registrations.replacementCount)
        assertThrows(IllegalStateException::class.java) {
            registrations.single(fixture.worldId).source.restrictions(fixture.currentTopology)
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
        val manager = runtimeManager(
            plugin(), MutableTopologyService(fixture.currentTopology).service, registrations, store,
            signalSources()
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

    @Test
    fun `one patch can update a region remove a table and create its replacement`() {
        val fixture = Fixture()
        val store = store()
        store.save(
            fixture.worldId,
            SimpleSignalsWorldDefinitions(
                fixture.worldId,
                1L,
                listOf(fixture.region),
                listOf(fixture.table(1L, fixture.lowerRoute()))
            )
        )
        val infrastructure = MutableTopologyService(fixture.currentTopology)
        val registrations = CapturingRoutePlanningService()
        val manager = runtimeManager(
            plugin(), infrastructure.service, registrations, store, signalSources()
        )
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(fixture.worldId).status)
        val updatedRegion = ProtectionRegionDefinition(
            fixture.region.regionId,
            2L,
            fixture.region.boundaries
        )
        val replacement = fixture.table(
            revision = 1L,
            route = fixture.upperRoute(),
            tableId = "test:replacement-table",
            regionRevision = 2L
        )

        val result = manager.applyDefinitionPatch(
            fixture.worldId,
            1L,
            SimpleSignalsDefinitionPatch(
                upsertRegions = listOf(updatedRegion),
                removeRouteTableIds = listOf("test:throat-table"),
                upsertRouteTables = listOf(replacement)
            )
        )

        assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED, result.status)
        assertEquals(2L, result.definitions?.definitionRevision)
        assertEquals(2L, result.definitions?.regions?.single()?.revision)
        assertEquals("test:replacement-table", result.definitions?.routeTables?.single()?.tableId)
        assertEquals(1, infrastructure.permitCloseCount)
        assertTrue(fixture.upperIn in infrastructure.lastEditRequest!!.impact.railIds)
        assertTrue(fixture.lowerIn in infrastructure.lastEditRequest!!.impact.railIds)

        val stored = assertInstanceOf(
            work.art1st.javarailways.simplesignals.persistence.SimpleSignalsWorldDefinitionLoadResult.Loaded::class.java,
            store.load(fixture.worldId)
        ).definitions
        assertEquals(result.definitions, stored)
        val plannedRails = fixture.plan(registrations.single(fixture.worldId), fixture.currentTopology)
            .route.segments.map { it.railId }
        assertTrue(fixture.upperIn in plannedRails)
        assertFalse(fixture.lowerIn in plannedRails)
    }

    @Test
    fun `revision and malformed patches are rejected before an edit permit`() {
        val fixture = Fixture()
        val infrastructure = MutableTopologyService(fixture.currentTopology)
        val manager = runtimeManager(
            plugin(), infrastructure.service, CapturingRoutePlanningService(), store(), signalSources()
        )
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(fixture.worldId).status)

        assertEquals(
            SimpleSignalsDefinitionAuthoringStatus.REVISION_REJECTED,
            manager.applyDefinitionPatch(
                fixture.worldId,
                9L,
                SimpleSignalsDefinitionPatch(upsertRegions = listOf(fixture.region))
            ).status
        )
        assertEquals(
            SimpleSignalsDefinitionAuthoringStatus.PATCH_REJECTED,
            manager.applyDefinitionPatch(
                fixture.worldId,
                0L,
                SimpleSignalsDefinitionPatch()
            ).status
        )
        val malformed = manager.applyDefinitionPatch(
            fixture.worldId,
            0L,
            SimpleSignalsDefinitionPatch(
                upsertRegions = listOf(fixture.region, fixture.region),
                removeRegionIds = listOf(fixture.region.regionId, "test:missing")
            )
        )
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.PATCH_REJECTED, malformed.status)
        assertTrue(malformed.diagnostics.any { it.contains("more than once") })
        assertTrue(malformed.diagnostics.any { it.contains("upserted and removed") })
        assertTrue(malformed.diagnostics.any { it.contains("does not exist") })
        assertEquals(0, infrastructure.permitAcquisitionCount)
    }

    @Test
    fun `invalid references and retained revision rollback fail validation before permit`() {
        val fixture = Fixture()
        val store = store()
        store.save(
            fixture.worldId,
            SimpleSignalsWorldDefinitions(
                fixture.worldId,
                1L,
                listOf(fixture.region),
                listOf(fixture.table(2L, fixture.upperRoute()))
            )
        )
        val infrastructure = MutableTopologyService(fixture.currentTopology)
        val manager = runtimeManager(
            plugin(), infrastructure.service, CapturingRoutePlanningService(), store, signalSources()
        )
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(fixture.worldId).status)

        assertEquals(
            SimpleSignalsDefinitionAuthoringStatus.PATCH_REJECTED,
            manager.applyDefinitionPatch(
                fixture.worldId,
                1L,
                SimpleSignalsDefinitionPatch(upsertRegions = listOf(fixture.region))
            ).status
        )
        val missingRegionTable = ProtectionRouteTableDefinition(
            "test:missing-table",
            1L,
            "test:missing-region",
            1L,
            listOf(fixture.upperRoute())
        )
        assertEquals(
            SimpleSignalsDefinitionAuthoringStatus.VALIDATION_REJECTED,
            manager.applyDefinitionPatch(
                fixture.worldId,
                1L,
                SimpleSignalsDefinitionPatch(upsertRouteTables = listOf(missingRegionTable))
            ).status
        )
        assertEquals(
            SimpleSignalsDefinitionAuthoringStatus.VALIDATION_REJECTED,
            manager.applyDefinitionPatch(
                fixture.worldId,
                1L,
                SimpleSignalsDefinitionPatch(
                    upsertRouteTables = listOf(fixture.table(1L, fixture.lowerRoute()))
                )
            ).status
        )
        val changedWithoutRegionRevision = ProtectionRegionDefinition(
            fixture.region.regionId,
            fixture.region.revision,
            fixture.region.boundaries.reversed()
        )
        assertEquals(
            SimpleSignalsDefinitionAuthoringStatus.VALIDATION_REJECTED,
            manager.applyDefinitionPatch(
                fixture.worldId,
                1L,
                SimpleSignalsDefinitionPatch(upsertRegions = listOf(changedWithoutRegionRevision))
            ).status
        )
        val rolledBackRegion = ProtectionRegionDefinition(
            fixture.region.regionId,
            0L,
            fixture.region.boundaries
        )
        assertEquals(
            SimpleSignalsDefinitionAuthoringStatus.VALIDATION_REJECTED,
            manager.applyDefinitionPatch(
                fixture.worldId,
                1L,
                SimpleSignalsDefinitionPatch(upsertRegions = listOf(rolledBackRegion))
            ).status
        )
        assertEquals(0, infrastructure.permitAcquisitionCount)
    }

    @Test
    fun `edit conflict and topology change after permit are returned and permits close`() {
        val fixture = Fixture()
        val infrastructure = MutableTopologyService(fixture.currentTopology)
        val manager = runtimeManager(
            plugin(), infrastructure.service, CapturingRoutePlanningService(), store(), signalSources()
        )
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(fixture.worldId).status)
        TopologyEditBlockerKind.entries.forEach { blockerKind ->
            val blocked = TopologyEditDecision(
                TopologyEditStatus.CONFLICT,
                listOf(
                    TopologyEditBlocker(
                        blockerKind,
                        InfrastructureOwner("test:blocker", 1L),
                        TopologyEditOverlap(setOf(fixture.entry), emptySet(), emptySet())
                    )
                ),
                "in use"
            )
            infrastructure.nextEditDecision = blocked
            val denied = manager.applyDefinitionPatch(
                fixture.worldId,
                0L,
                SimpleSignalsDefinitionPatch(upsertRegions = listOf(fixture.region))
            )
            assertEquals(SimpleSignalsDefinitionAuthoringStatus.EDIT_BLOCKED, denied.status)
            assertSame(blocked, denied.editDecision)
            assertSame(blocked.blockers.single(), denied.editDecision?.blockers?.single())
        }
        assertEquals(0, infrastructure.permitCloseCount)

        infrastructure.nextEditDecision = TopologyEditDecision(TopologyEditStatus.GRANTED)
        val newerTopology = fixture.topology(42L)
        infrastructure.afterPermitGranted = { infrastructure.topology = newerTopology }
        val stale = manager.applyDefinitionPatch(
            fixture.worldId,
            0L,
            SimpleSignalsDefinitionPatch(upsertRegions = listOf(fixture.region))
        )

        assertEquals(SimpleSignalsDefinitionAuthoringStatus.STALE_TOPOLOGY, stale.status)
        assertEquals(TopologyEditStatus.STALE_TOPOLOGY, stale.editDecision?.status)
        assertEquals(1, infrastructure.permitCloseCount)
        assertFalse(Files.exists(store().primaryFile(fixture.worldId)))
    }

    @Test
    fun `save failure preserves disk and live source while closing permit`() {
        val fixture = Fixture()
        val seedStore = store()
        seedStore.save(
            fixture.worldId,
            SimpleSignalsWorldDefinitions(
                fixture.worldId,
                1L,
                listOf(fixture.region),
                listOf(fixture.table(1L, fixture.lowerRoute()))
            )
        )
        val originalPrimary = Files.readString(seedStore.primaryFile(fixture.worldId))
        val failingStore = SimpleSignalsWorldDefinitionStore(directory.toFile()) {
            throw java.io.IOException("injected before primary replace")
        }
        val infrastructure = MutableTopologyService(fixture.currentTopology)
        val registrations = CapturingRoutePlanningService()
        val manager = runtimeManager(
            plugin(), infrastructure.service, registrations, failingStore, signalSources()
        )
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(fixture.worldId).status)
        val binding = registrations.single(fixture.worldId)
        val retainedRestrictions = binding.source.restrictions(fixture.currentTopology)

        val failed = manager.applyDefinitionPatch(
            fixture.worldId,
            1L,
            SimpleSignalsDefinitionPatch(
                upsertRouteTables = listOf(fixture.table(2L, fixture.upperRoute()))
            )
        )

        assertEquals(SimpleSignalsDefinitionAuthoringStatus.PERSISTENCE_FAILED, failed.status)
        assertEquals(originalPrimary, Files.readString(seedStore.primaryFile(fixture.worldId)))
        assertFalse(Files.exists(seedStore.backupFile(fixture.worldId)))
        assertSame(retainedRestrictions, binding.source.restrictions(fixture.currentTopology))
        assertEquals(1, infrastructure.permitCloseCount)
    }

    @Test
    fun `non operational fixed blocks can be added updated and removed atomically at runtime`() {
        val fixture = Fixture()
        val infrastructure = MutableTopologyService(fixture.currentTopology)
        val store = store()
        val routeSources = CapturingRoutePlanningService()
        val signalSources = CapturingSignalEpochSourceService()
        val manager = runtimeManager(
            plugin(), infrastructure.service, routeSources, store, signalSources
        )
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(fixture.worldId).status)
        val first = FixedBlockDefinition(
            "test:approach-block",
            1L,
            listOf(
                FixedBlockTraversal(fixture.entry, ViewDirection.AB, 0.0, 5.0)
            )
        )

        val added = manager.applyDefinitionPatch(
            fixture.worldId,
            0L,
            SimpleSignalsDefinitionPatch(upsertFixedBlocks = listOf(first))
        )
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED, added.status)
        assertEquals(listOf(first), added.definitions?.fixedBlocks)
        assertEquals(SECTION_OCCUPANCY_SOURCE_ID, signalSources.sources.getValue(fixture.worldId).single().sourceId)

        val second = FixedBlockDefinition(
            first.blockId,
            2L,
            listOf(FixedBlockTraversal(fixture.exit, ViewDirection.AB, 1.0, 6.0))
        )
        val updated = manager.applyDefinitionPatch(
            fixture.worldId,
            1L,
            SimpleSignalsDefinitionPatch(upsertFixedBlocks = listOf(second))
        )
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED, updated.status)
        assertEquals(listOf(second), updated.definitions?.fixedBlocks)
        val updatedArea = signalSources.sources.getValue(fixture.worldId).single().areas.single()
        assertEquals(1.0, updatedArea.intervals.single().minArcLengthInAbMeters, 0.0)
        assertEquals(6.0, updatedArea.intervals.single().maxArcLengthInAbMeters, 0.0)
        assertTrue(fixture.entry in infrastructure.lastEditRequest!!.impact.railIds)
        assertTrue(fixture.exit in infrastructure.lastEditRequest!!.impact.railIds)

        val removed = manager.applyDefinitionPatch(
            fixture.worldId,
            2L,
            SimpleSignalsDefinitionPatch(removeFixedBlockIds = listOf(second.blockId))
        )

        assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED, removed.status)
        assertEquals(emptyList<FixedBlockDefinition>(), removed.definitions?.fixedBlocks)
        assertEquals(emptyList<SignalEpochSource>(), signalSources.sources.getValue(fixture.worldId))
        assertEquals(4, signalSources.atomicCommitCount) // install plus three authoring commits
        assertEquals(1, routeSources.replacementCount)
        assertEquals(3, infrastructure.permitAcquisitionCount)
        assertEquals(3, infrastructure.permitCloseCount)
        val stored = assertInstanceOf(
            work.art1st.javarailways.simplesignals.persistence.SimpleSignalsWorldDefinitionLoadResult.Loaded::class.java,
            store.load(fixture.worldId)
        ).definitions
        assertEquals(removed.definitions, stored)
    }

    @Test
    fun `fixed-block signal edits compile and persist with their controlled block`() {
        val fixture = Fixture()
        val store = store()
        val signalSources = CapturingSignalEpochSourceService()
        val manager = runtimeManager(
            plugin(),
            MutableTopologyService(fixture.currentTopology).service,
            CapturingRoutePlanningService(),
            store,
            signalSources
        )
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(fixture.worldId).status)
        val block = FixedBlockDefinition(
            "test:signal-block",
            1L,
            listOf(FixedBlockTraversal(fixture.entry, ViewDirection.AB, 0.0, 5.0))
        )
        val signal = FixedBlockSignalDefinition(
            "test:signal",
            1L,
            block.blockId,
            300.0,
            ConfiguredSignalOutput(0.0, "private/occupied"),
            listOf(
                FixedBlockSignalClearLevel(
                    0.0,
                    ConfiguredSignalOutput(24.0, "private/clear")
                )
            )
        )

        val applied = manager.applyDefinitionPatch(
            fixture.worldId,
            0L,
            SimpleSignalsDefinitionPatch(
                upsertFixedBlocks = listOf(block),
                upsertFixedBlockSignals = listOf(signal)
            )
        )

        assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED, applied.status)
        assertEquals(listOf(signal), applied.definitions?.fixedBlockSignals)
        assertEquals(SECTION_OCCUPANCY_SOURCE_ID, signalSources.sources.getValue(fixture.worldId).single().sourceId)
        val stored = assertInstanceOf(
            work.art1st.javarailways.simplesignals.persistence.SimpleSignalsWorldDefinitionLoadResult.Loaded::class.java,
            store.load(fixture.worldId)
        ).definitions
        assertEquals(listOf(signal), stored.fixedBlockSignals)

        val orphaned = manager.applyDefinitionPatch(
            fixture.worldId,
            1L,
            SimpleSignalsDefinitionPatch(removeFixedBlockIds = listOf(block.blockId))
        )
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.VALIDATION_REJECTED, orphaned.status)
        assertTrue(orphaned.diagnostics.single().contains("unknown fixed block"))
        val retained = assertInstanceOf(
            work.art1st.javarailways.simplesignals.persistence.SimpleSignalsWorldDefinitionLoadResult.Loaded::class.java,
            store.load(fixture.worldId)
        ).definitions
        assertEquals(listOf(block), retained.fixedBlocks)
        assertEquals(listOf(signal), retained.fixedBlockSignals)
    }

    @Test
    fun `fixed block source prevalidation failure preserves definitions disk and old source`() {
        val fixture = Fixture()
        val infrastructure = MutableTopologyService(fixture.currentTopology)
        val store = store()
        val signalSources = CapturingSignalEpochSourceService()
        val manager = runtimeManager(
            plugin(), infrastructure.service, CapturingRoutePlanningService(), store, signalSources
        )
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(fixture.worldId).status)
        val retainedSources = signalSources.sources.getValue(fixture.worldId)
        val block = FixedBlockDefinition(
            "test:approach-block",
            1L,
            listOf(FixedBlockTraversal(fixture.entry, ViewDirection.AB, 0.0, 5.0))
        )
        signalSources.nextPrevalidationFailure = IllegalArgumentException("injected invalid area union")

        val rejected = manager.applyDefinitionPatch(
            fixture.worldId,
            0L,
            SimpleSignalsDefinitionPatch(upsertFixedBlocks = listOf(block))
        )

        assertEquals(SimpleSignalsDefinitionAuthoringStatus.VALIDATION_REJECTED, rejected.status)
        assertSame(retainedSources, signalSources.sources.getValue(fixture.worldId))
        assertFalse(Files.exists(store.primaryFile(fixture.worldId)))
        assertEquals(1, signalSources.atomicCommitCount)
        assertEquals(1, infrastructure.permitCloseCount)

        val retried = manager.applyDefinitionPatch(
            fixture.worldId,
            0L,
            SimpleSignalsDefinitionPatch(upsertFixedBlocks = listOf(block))
        )
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED, retried.status)
        assertEquals(2, signalSources.atomicCommitCount)
    }

    @Test
    fun `same topology route table edit atomically refreshes selection with unchanged fixed block geometry`() {
        val fixture = Fixture()
        val infrastructure = MutableTopologyService(fixture.currentTopology)
        val store = store()
        val block = FixedBlockDefinition(
            "test:approach-block",
            1L,
            listOf(FixedBlockTraversal(fixture.entry, ViewDirection.AB, 0.0, 5.0))
        )
        store.save(
            fixture.worldId,
            SimpleSignalsWorldDefinitions(
                fixture.worldId,
                1L,
                listOf(fixture.region),
                listOf(fixture.table(1L, fixture.lowerRoute())),
                listOf(block)
            )
        )
        val signalSources = CapturingSignalEpochSourceService()
        val manager = runtimeManager(
            plugin(), infrastructure.service, CapturingRoutePlanningService(), store, signalSources
        )
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(fixture.worldId).status)
        val retainedSource = signalSources.sources.getValue(fixture.worldId).single { it.sourceId == SECTION_OCCUPANCY_SOURCE_ID }

        val applied = manager.applyDefinitionPatch(
            fixture.worldId,
            1L,
            SimpleSignalsDefinitionPatch(
                upsertRouteTables = listOf(fixture.table(2L, fixture.upperRoute()))
            )
        )

        assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED, applied.status)
        val installed = signalSources.sources.getValue(fixture.worldId)
        assertEquals(retainedSource.areas, installed.single { it.sourceId == SECTION_OCCUPANCY_SOURCE_ID }.areas)
        assertEquals(1, installed.size)
        assertEquals(2, signalSources.atomicCommitCount)
        assertEquals(1, infrastructure.permitCloseCount)
    }

    @Test
    fun `infrastructure dependency and live claim block fixed block edits before save or source callback`() {
        val fixture = Fixture()
        val infrastructure = MutableTopologyService(fixture.currentTopology)
        val store = store()
        val signalSources = CapturingSignalEpochSourceService()
        val manager = runtimeManager(
            plugin(), infrastructure.service, CapturingRoutePlanningService(), store, signalSources
        )
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(fixture.worldId).status)
        val block = FixedBlockDefinition(
            "test:approach-block",
            1L,
            listOf(FixedBlockTraversal(fixture.entry, ViewDirection.AB, 0.0, 5.0))
        )

        listOf(
            TopologyEditBlockerKind.INFRASTRUCTURE_DEPENDENCY,
            TopologyEditBlockerKind.LIVE_RUNTIME_CLAIM
        ).forEach { kind ->
            infrastructure.nextEditDecision = TopologyEditDecision(
                TopologyEditStatus.CONFLICT,
                listOf(
                    TopologyEditBlocker(
                        kind,
                        InfrastructureOwner("test:operator", 1L),
                        TopologyEditOverlap(setOf(fixture.entry), emptySet(), emptySet())
                    )
                ),
                "rail is operationally protected"
            )
            val blocked = manager.applyDefinitionPatch(
                fixture.worldId,
                0L,
                SimpleSignalsDefinitionPatch(upsertFixedBlocks = listOf(block))
            )
            assertEquals(SimpleSignalsDefinitionAuthoringStatus.EDIT_BLOCKED, blocked.status)
            assertEquals(kind, blocked.editDecision?.blockers?.single()?.kind)
        }

        assertEquals(1, signalSources.atomicCommitCount) // initial empty install only
        assertFalse(Files.exists(store.primaryFile(fixture.worldId)))
    }

    @Test
    fun `fixed block persistence failure retains old source publication and disk`() {
        val fixture = Fixture()
        val seedStore = store()
        val first = FixedBlockDefinition(
            "test:approach-block",
            1L,
            listOf(FixedBlockTraversal(fixture.entry, ViewDirection.AB, 0.0, 5.0))
        )
        seedStore.save(
            fixture.worldId,
            SimpleSignalsWorldDefinitions(
                fixture.worldId,
                1L,
                emptyList(),
                emptyList(),
                listOf(first)
            )
        )
        val originalPrimary = Files.readString(seedStore.primaryFile(fixture.worldId))
        val failingStore = SimpleSignalsWorldDefinitionStore(directory.toFile()) {
            throw java.io.IOException("injected before primary replace")
        }
        val infrastructure = MutableTopologyService(fixture.currentTopology)
        val signalSources = CapturingSignalEpochSourceService()
        val manager = runtimeManager(
            plugin(), infrastructure.service, CapturingRoutePlanningService(), failingStore, signalSources
        )
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(fixture.worldId).status)
        val retainedSource = signalSources.sources.getValue(fixture.worldId).single()
        val second = FixedBlockDefinition(
            first.blockId,
            2L,
            listOf(FixedBlockTraversal(fixture.entry, ViewDirection.AB, 1.0, 6.0))
        )

        val failed = manager.applyDefinitionPatch(
            fixture.worldId,
            1L,
            SimpleSignalsDefinitionPatch(upsertFixedBlocks = listOf(second))
        )

        assertEquals(SimpleSignalsDefinitionAuthoringStatus.PERSISTENCE_FAILED, failed.status)
        assertSame(retainedSource, signalSources.sources.getValue(fixture.worldId).single())
        assertEquals(1, signalSources.atomicCommitCount)
        assertEquals(originalPrimary, Files.readString(seedStore.primaryFile(fixture.worldId)))
        assertEquals(1, infrastructure.permitCloseCount)
        assertEquals(
            SimpleSignalsDefinitionAuthoringStatus.REVISION_REJECTED,
            manager.applyDefinitionPatch(
                fixture.worldId,
                2L,
                SimpleSignalsDefinitionPatch(upsertFixedBlocks = listOf(second))
            ).status
        )
    }

    @Test
    fun `exact topology root change recompiles and atomically replaces fixed block geometry`() {
        val fixture = Fixture()
        val infrastructure = MutableTopologyService(fixture.currentTopology)
        val store = store()
        val block = FixedBlockDefinition(
            "test:approach-block",
            1L,
            listOf(FixedBlockTraversal(fixture.entry, ViewDirection.AB, 0.0, 5.0))
        )
        store.save(
            fixture.worldId,
            SimpleSignalsWorldDefinitions(
                fixture.worldId,
                1L,
                listOf(fixture.region),
                listOf(fixture.table(1L, fixture.lowerRoute())),
                listOf(block)
            )
        )
        val storedBefore = Files.readString(store.primaryFile(fixture.worldId))
        val signalSources = CapturingSignalEpochSourceService()
        val manager = runtimeManager(
            plugin(),
            infrastructure.service,
            CapturingRoutePlanningService(),
            store,
            signalSources
        )
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(fixture.worldId).status)
        val initialSource = signalSources.sources.getValue(fixture.worldId).single { it.sourceId == SECTION_OCCUPANCY_SOURCE_ID }

        // The revision is deliberately equal: source geometry is bound to the root object, not only
        // to a numeric topology revision.
        infrastructure.topology = fixture.topology(fixture.currentTopology.revision)
        val applied = manager.applyDefinitionPatch(
            fixture.worldId,
            1L,
            SimpleSignalsDefinitionPatch(
                upsertRouteTables = listOf(fixture.table(2L, fixture.upperRoute()))
            )
        )

        assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED, applied.status)
        assertNotSame(initialSource, signalSources.sources.getValue(fixture.worldId).single { it.sourceId == SECTION_OCCUPANCY_SOURCE_ID })
        assertEquals(2, signalSources.atomicCommitCount)
        assertEquals(1, infrastructure.permitAcquisitionCount)
        assertTrue(storedBefore != Files.readString(store.primaryFile(fixture.worldId)))
    }

    @Test
    fun `topology change at the atomic callback rejects fixed block edit without publication`() {
        val fixture = Fixture()
        val infrastructure = MutableTopologyService(fixture.currentTopology)
        val store = store()
        val first = FixedBlockDefinition(
            "test:approach-block",
            1L,
            listOf(FixedBlockTraversal(fixture.entry, ViewDirection.AB, 0.0, 5.0))
        )
        store.save(
            fixture.worldId,
            SimpleSignalsWorldDefinitions(
                fixture.worldId,
                1L,
                emptyList(),
                emptyList(),
                listOf(first)
            )
        )
        val originalPrimary = Files.readString(store.primaryFile(fixture.worldId))
        val signalSources = CapturingSignalEpochSourceService()
        val manager = runtimeManager(
            plugin(), infrastructure.service, CapturingRoutePlanningService(), store, signalSources
        )
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(fixture.worldId).status)
        val retainedSource = signalSources.sources.getValue(fixture.worldId).single()
        signalSources.beforeCommitAction = {
            infrastructure.topology = fixture.topology(fixture.currentTopology.revision + 1L)
        }
        val second = FixedBlockDefinition(
            first.blockId,
            2L,
            listOf(FixedBlockTraversal(fixture.entry, ViewDirection.AB, 1.0, 6.0))
        )

        val rejected = manager.applyDefinitionPatch(
            fixture.worldId,
            1L,
            SimpleSignalsDefinitionPatch(upsertFixedBlocks = listOf(second))
        )

        assertEquals(SimpleSignalsDefinitionAuthoringStatus.STALE_TOPOLOGY, rejected.status)
        assertEquals(TopologyEditStatus.STALE_TOPOLOGY, rejected.editDecision?.status)
        assertSame(retainedSource, signalSources.sources.getValue(fixture.worldId).single())
        assertEquals(originalPrimary, Files.readString(store.primaryFile(fixture.worldId)))
        assertEquals(1, signalSources.atomicCommitCount)
        assertEquals(1, infrastructure.permitCloseCount)
    }

    @Test
    fun `definition patch defensively copies fixed block edits`() {
        val block = FixedBlockDefinition(
            "test:block",
            1L,
            listOf(FixedBlockTraversal(UUID(0L, 1L), ViewDirection.AB, 0.0, 1.0))
        )
        val upserts = mutableListOf(block)
        val removals = mutableListOf("test:removed")

        val patch = SimpleSignalsDefinitionPatch(
            upsertFixedBlocks = upserts,
            removeFixedBlockIds = removals
        )
        upserts.clear()
        removals.clear()

        assertEquals(listOf(block), patch.upsertFixedBlocks)
        assertEquals(listOf("test:removed"), patch.removeFixedBlockIds)
    }

    @Test
    fun `persisted fixed block and protection interior overlap rejects world installation`() {
        val fixture = Fixture()
        val infrastructure = MutableTopologyService(fixture.currentTopology)
        val store = store()
        val outsideBlock = FixedBlockDefinition(
            "test:approach-block",
            1L,
            listOf(FixedBlockTraversal(fixture.entry, ViewDirection.AB, 5.0, 10.0))
        )
        store.save(
            fixture.worldId,
            SimpleSignalsWorldDefinitions(
                fixture.worldId,
                1L,
                listOf(fixture.region),
                emptyList(),
                listOf(outsideBlock)
            )
        )
        val manager = runtimeManager(
            plugin(), infrastructure.service, CapturingRoutePlanningService(), store, signalSources()
        )

        val rejected = manager.install(fixture.worldId)

        assertEquals(SimpleSignalsWorldInstallStatus.UNAVAILABLE, rejected.status)
        assertTrue(rejected.detail.contains("overlaps protection region"))
        assertEquals(0, infrastructure.permitAcquisitionCount)
    }

    private fun store() = SimpleSignalsWorldDefinitionStore(directory.toFile())

    private class MutableTopologyService(initial: TopologyState?) {
        var topology: TopologyState? = initial
        var nextEditDecision = TopologyEditDecision(TopologyEditStatus.GRANTED)
        var afterPermitGranted: () -> Unit = {}
        var permitAcquisitionCount = 0
            private set
        var permitCloseCount = 0
            private set
        var lastEditRequest: TopologyEditRequest? = null
            private set
        val service: InfrastructureResourceService = proxy(InfrastructureResourceService::class.java) { method, arguments ->
            when (method.name) {
                "currentTopology" -> topology
                    ?: throw IllegalStateException("Railway world is not registered: ${arguments?.single()}")
                "acquireEditPermit" -> {
                    permitAcquisitionCount++
                    lastEditRequest = arguments!![2] as TopologyEditRequest
                    if (!nextEditDecision.successful) {
                        InfrastructureEditPermitResult.denied(nextEditDecision)
                    } else {
                        val owner = lastEditRequest!!.owner
                        val permit = object : InfrastructureEditPermit {
                            private var closed = false
                            override fun owner(): InfrastructureOwner = owner
                            override fun close() {
                                if (!closed) {
                                    closed = true
                                    permitCloseCount++
                                }
                            }
                        }
                        afterPermitGranted()
                        InfrastructureEditPermitResult.granted(nextEditDecision, permit)
                    }
                }
                else -> throw UnsupportedOperationException(method.name)
            }
        }
    }

    private class CapturingRoutePlanningService(
        private var nextFailure: RuntimeException? = null
    ) : RoutePlanningRestrictionSourceService {
        private val bindings = linkedMapOf<UUID, List<RoutePlanningRestrictionBinding>>()
        var replacementCount: Int = 0
            private set

        override fun replaceRoutePlanningSources(
            pluginOwner: Plugin,
            worldId: UUID,
            sources: Collection<RoutePlanningRestrictionBinding>
        ) {
            replacementCount++
            nextFailure?.let { failure ->
                nextFailure = null
                throw failure
            }
            bindings[worldId] = java.util.List.copyOf(sources)
        }

        fun single(worldId: UUID): RoutePlanningRestrictionBinding = bindings.getValue(worldId).single()
    }

    private class CapturingSignalEpochSourceService(
        var nextPrevalidationFailure: RuntimeException? = null
    ) : SignalEpochSourceService {
        val sources = linkedMapOf<UUID, List<SignalEpochSource>>()
        val replacements = mutableListOf<List<SignalEpochSource>>()
        var beforeCommitAction: () -> Unit = {}
        var atomicCommitCount = 0
            private set

        override fun replaceSources(
            pluginOwner: Plugin,
            worldId: UUID,
            sources: Collection<SignalEpochSource>
        ) = replaceSourcesAtomically(pluginOwner, worldId, sources) {}

        override fun replaceSourcesAtomically(
            pluginOwner: Plugin,
            worldId: UUID,
            sources: Collection<SignalEpochSource>,
            commitAction: Runnable
        ) {
            val copied = java.util.List.copyOf(sources)
            nextPrevalidationFailure?.let { failure ->
                nextPrevalidationFailure = null
                throw failure
            }
            beforeCommitAction()
            commitAction.run()
            atomicCommitCount++
            replacements += copied
            this.sources[worldId] = copied
        }
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

        fun table(
            revision: Long,
            route: ProtectionRouteDefinition,
            tableId: String = "test:throat-table",
            regionRevision: Long = region.revision
        ) = ProtectionRouteTableDefinition(
            tableId, revision, region.regionId, regionRevision, listOf(route)
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
        private fun runtimeManager(
            plugin: Plugin,
            infrastructure: InfrastructureResourceService,
            routePlanningSources: RoutePlanningRestrictionSourceService,
            definitions: SimpleSignalsWorldDefinitionStore,
            signalEpochSources: SignalEpochSourceService,
            inspectionSources: InspectionSourceService = inspectionSources()
        ) = SimpleSignalsWorldRuntimeManager(
            plugin,
            infrastructure,
            routePlanningSources,
            definitions,
            signalEpochSources,
            inspectionSources
        )

        private fun inspectionSources(): InspectionSourceService =
            proxy(InspectionSourceService::class.java) { method, _ ->
                when (method.name) {
                    "replaceInspectionSources" -> Unit
                    else -> throw UnsupportedOperationException(method.name)
                }
            }

        private fun signalSources(): SignalEpochSourceService =
            proxy(SignalEpochSourceService::class.java) { method, arguments ->
                when (method.name) {
                    "replaceSources" -> Unit
                    "replaceSourcesAtomically" -> {
                        (arguments!![3] as Runnable).run()
                        Unit
                    }
                    else -> throw UnsupportedOperationException(method.name)
                }
            }

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
