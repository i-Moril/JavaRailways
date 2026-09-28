package work.art1st.javarailways.simplesignals.runtime

import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotSame
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
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.simpleato.api.route.RoutePlanningRestrictionBinding
import work.art1st.javarailways.simplesignals.block.FixedBlockDefinition
import work.art1st.javarailways.simplesignals.block.FixedBlockTraversal
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
        val manager = SimpleSignalsWorldRuntimeManager(
            plugin(), infrastructure.service, registrations, store()
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
        val manager = SimpleSignalsWorldRuntimeManager(
            plugin(), infrastructure.service, registrations, store()
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
        val manager = SimpleSignalsWorldRuntimeManager(
            plugin(),
            MutableTopologyService(fixture.currentTopology).service,
            registrations,
            store
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
        val manager = SimpleSignalsWorldRuntimeManager(
            plugin(), infrastructure.service, registrations, store
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
        val manager = SimpleSignalsWorldRuntimeManager(
            plugin(), infrastructure.service, CapturingRoutePlanningService(), store()
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
        val manager = SimpleSignalsWorldRuntimeManager(
            plugin(), infrastructure.service, CapturingRoutePlanningService(), store
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
        val manager = SimpleSignalsWorldRuntimeManager(
            plugin(), infrastructure.service, CapturingRoutePlanningService(), store()
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
        val manager = SimpleSignalsWorldRuntimeManager(
            plugin(), infrastructure.service, registrations, failingStore
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
    fun `fixed block patch compiles persists and protects every affected rail with one publication`() {
        val fixture = Fixture()
        val infrastructure = MutableTopologyService(fixture.currentTopology)
        val store = store()
        val manager = SimpleSignalsWorldRuntimeManager(
            plugin(), infrastructure.service, CapturingRoutePlanningService(), store
        )
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(fixture.worldId).status)
        val block = FixedBlockDefinition(
            "test:approach-block",
            1L,
            listOf(
                FixedBlockTraversal(fixture.entry, ViewDirection.AB, 0.0, 5.0)
            )
        )

        val applied = manager.applyDefinitionPatch(
            fixture.worldId,
            0L,
            SimpleSignalsDefinitionPatch(upsertFixedBlocks = listOf(block))
        )

        assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED, applied.status)
        assertEquals(1L, applied.definitions?.definitionRevision)
        assertEquals(listOf(block), applied.definitions?.fixedBlocks)
        assertEquals(setOf(fixture.entry), infrastructure.lastEditRequest!!.impact.railIds)
        assertEquals(1, infrastructure.permitAcquisitionCount)
        assertEquals(1, infrastructure.permitCloseCount)
        val loaded = assertInstanceOf(
            work.art1st.javarailways.simplesignals.persistence.SimpleSignalsWorldDefinitionLoadResult.Loaded::class.java,
            store.load(fixture.worldId)
        ).definitions
        assertEquals(listOf(block), loaded.fixedBlocks)

        val rejected = manager.applyDefinitionPatch(
            fixture.worldId,
            1L,
            SimpleSignalsDefinitionPatch(
                upsertFixedBlocks = listOf(
                    FixedBlockDefinition(
                        block.blockId,
                        2L,
                        listOf(
                            FixedBlockTraversal(fixture.entry, ViewDirection.AB, 5.0, 10.0),
                            FixedBlockTraversal(fixture.lowerOut, ViewDirection.AB, 0.0, 10.0)
                        )
                    )
                )
            )
        )
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.VALIDATION_REJECTED, rejected.status)
        assertEquals(1, infrastructure.permitAcquisitionCount)
        assertEquals(listOf(block), assertInstanceOf(
            work.art1st.javarailways.simplesignals.persistence.SimpleSignalsWorldDefinitionLoadResult.Loaded::class.java,
            store.load(fixture.worldId)
        ).definitions.fixedBlocks)

        val removed = manager.applyDefinitionPatch(
            fixture.worldId,
            1L,
            SimpleSignalsDefinitionPatch(removeFixedBlockIds = listOf(block.blockId))
        )
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED, removed.status)
        assertEquals(2L, removed.definitions?.definitionRevision)
        assertEquals(emptyList<FixedBlockDefinition>(), removed.definitions?.fixedBlocks)
        assertEquals(setOf(fixture.entry), infrastructure.lastEditRequest!!.impact.railIds)
        assertEquals(2, infrastructure.permitAcquisitionCount)
        assertEquals(2, infrastructure.permitCloseCount)
        assertEquals(emptyList<FixedBlockDefinition>(), assertInstanceOf(
            work.art1st.javarailways.simplesignals.persistence.SimpleSignalsWorldDefinitionLoadResult.Loaded::class.java,
            store.load(fixture.worldId)
        ).definitions.fixedBlocks)
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
    fun `fixed block and protection interior overlap rejects the whole candidate publication`() {
        val fixture = Fixture()
        val infrastructure = MutableTopologyService(fixture.currentTopology)
        val store = store()
        val manager = SimpleSignalsWorldRuntimeManager(
            plugin(), infrastructure.service, CapturingRoutePlanningService(), store
        )
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(fixture.worldId).status)
        val outsideBlock = FixedBlockDefinition(
            "test:approach-block",
            1L,
            listOf(FixedBlockTraversal(fixture.entry, ViewDirection.AB, 0.0, 5.0))
        )
        val initial = manager.applyDefinitionPatch(
            fixture.worldId,
            0L,
            SimpleSignalsDefinitionPatch(
                upsertRegions = listOf(fixture.region),
                upsertFixedBlocks = listOf(outsideBlock)
            )
        )
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED, initial.status)
        val storedBefore = Files.readString(store.primaryFile(fixture.worldId))

        val overlap = FixedBlockDefinition(
            outsideBlock.blockId,
            2L,
            listOf(FixedBlockTraversal(fixture.entry, ViewDirection.AB, 5.0, 10.0))
        )
        val rejected = manager.applyDefinitionPatch(
            fixture.worldId,
            1L,
            SimpleSignalsDefinitionPatch(
                upsertFixedBlocks = listOf(overlap)
            )
        )

        assertEquals(SimpleSignalsDefinitionAuthoringStatus.VALIDATION_REJECTED, rejected.status)
        assertTrue(rejected.diagnostics.single().contains("overlaps protection region"))
        assertEquals(1, infrastructure.permitAcquisitionCount)
        assertEquals(storedBefore, Files.readString(store.primaryFile(fixture.worldId)))
        val retained = assertInstanceOf(
            work.art1st.javarailways.simplesignals.persistence.SimpleSignalsWorldDefinitionLoadResult.Loaded::class.java,
            store.load(fixture.worldId)
        ).definitions
        assertEquals(listOf(fixture.region), retained.regions)
        assertEquals(listOf(outsideBlock), retained.fixedBlocks)
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
