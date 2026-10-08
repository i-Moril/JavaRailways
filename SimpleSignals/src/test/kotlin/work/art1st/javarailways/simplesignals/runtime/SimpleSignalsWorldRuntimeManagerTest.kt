package work.art1st.javarailways.simplesignals.runtime

import work.art1st.javarailways.simplesignals.testing.signalWorldRuntime
import work.art1st.javarailways.simplesignals.testing.reconfigured
import work.art1st.javarailways.simplesignals.authoring.*
import work.art1st.javarailways.basic.api.observation.RailwayObservation
import work.art1st.javarailways.bukkit.api.observation.RailwayObservationService
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef

import work.art1st.javarailways.simplesignals.testing.fixedBlockPolicy

import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import work.art1st.javarailways.basic.route.OperationalRouteLocation
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureDependencies
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureDependencyRequest
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureDependencyMutationStatus
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureFootprint
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureMutationStatus
import work.art1st.javarailways.basic.api.infrastructure.RailIntervalResource
import work.art1st.javarailways.basic.api.infrastructure.StableInfrastructureResource
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.infrastructure.InfrastructureCoordinationRegistry
import work.art1st.javarailways.basic.infrastructure.InfrastructureClaimRequest
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
import work.art1st.javarailways.core.api.physics.RailwayWorldFrameSnapshot
import work.art1st.javarailways.core.api.physics.rail.topology.ActiveRailContinuationsSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.core.util.IsolatedMainThreadMarker
import work.art1st.javarailways.simpleato.api.route.RoutePlanningRestrictionBinding
import work.art1st.javarailways.simpleato.api.inspection.InspectionSource
import work.art1st.javarailways.simpleato.api.signal.SignalEpochSource
import work.art1st.javarailways.simpleato.signal.SignalEpochRuntime
import work.art1st.javarailways.simpleato.restriction.TrainRestrictionSnapshotStore
import work.art1st.javarailways.simplesignals.block.FixedBlockDefinition
import work.art1st.javarailways.simplesignals.block.FIXED_BLOCK_RESOURCE_TYPE
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
import work.art1st.javarailways.simplesignals.signal.FixedBlockSignalDefinition
import work.art1st.javarailways.simplesignals.signal.ProtectionSignalDefinition
import work.art1st.javarailways.simplesignals.signal.ProtectionSignalKind
import work.art1st.javarailways.simplesignals.signal.RouteIndicatorDefinition
import work.art1st.javarailways.simplesignals.signal.AttachedSignalDefinition
import work.art1st.javarailways.bukkit.api.model.ModelPose
import work.art1st.javarailways.bukkit.api.model.ModelHandle
import work.art1st.javarailways.bukkit.api.model.ModelPresentationService
import work.art1st.javarailways.bukkit.api.rail.RailTargetService
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import org.joml.Vector3d
import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.simplesignals.presentation.DeviceModelDefinition
import work.art1st.javarailways.simplesignals.presentation.ModelPlacement
import work.art1st.javarailways.simplesignals.presentation.SignalModelBindingTest.RecordingModels
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import work.art1st.javarailways.simplesignals.command.SimpleSignalsAdminCommand
import work.art1st.javarailways.simplesignals.persistence.SimpleSignalsDefinitionDrafts
import work.art1st.javarailways.simplesignals.persistence.definitionDraftPatch
import net.kyori.adventure.text.Component
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.command.BlockCommandSender
import org.bukkit.entity.Player
import org.bukkit.Server
import org.bukkit.World
import io.papermc.paper.threadedregions.scheduler.AsyncScheduler
import io.papermc.paper.threadedregions.scheduler.EntityScheduler
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import java.util.function.Consumer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SimpleSignalsWorldRuntimeManagerTest {
    @TempDir
    lateinit var directory: Path

    @Test fun `region wand completes new region and shortest table atomically with endpoint warnings and reload`() {
        val f = Fixture()
        val store = store()
        store.save(f.worldId, SimpleSignalsWorldDefinitions(f.worldId, 1, emptyList(), emptyList(),
            pendingProtectionBoundaries = listOf(f.region.boundaries.first())))
        val infrastructure = MutableTopologyService(f.currentTopology)
        val sources = CapturingSignalEpochSourceService()
        val plans = CapturingRoutePlanningService()
        val manager = runtimeManager(plugin(), infrastructure.service, plans, store, sources)
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(f.worldId).status)
        val backend = RuntimeSectionWandBackend(manager, infrastructure.service, 1000.0)
        val result = backend.perform(backend.capture(f.worldId)!!, SectionWandTool.REGION_CREATE, false,
            null, f.region.boundaries.last().position) { true }
        assertTrue(result.message.contains("generated 1 shortest"), result.message)
        val saved = manager.authoringRuntime(f.worldId)!!.currentDefinitions()
        assertEquals(2, saved.definitionRevision)
        assertTrue(saved.pendingProtectionBoundaries.isEmpty())
        assertEquals(1, saved.regions.size)
        val table = saved.routeTables.single()
        assertEquals(saved.regions.single().regionId, table.regionId)
        assertEquals(listOf(f.entry, f.upperIn, f.upperOut, f.exit), table.routes.single().traversals.map { it.railId })
        assertEquals(saved.regions.single().boundaries.toSet(), result.warnings.toSet())
        assertTrue(f.upperIn in f.plan(plans.single(f.worldId), f.currentTopology).route.segments.map { it.railId })
        manager.remove(f.worldId)
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(f.worldId).status)
        assertEquals(saved, manager.authoringRuntime(f.worldId)!!.currentDefinitions())
    }

    @Test fun `region completion with no permitted path preserves pending definition and disk`() {
        val f = Fixture()
        val store = store()
        val pending = SimpleSignalsWorldDefinitions(f.worldId, 1, emptyList(), emptyList(),
            pendingProtectionBoundaries = listOf(f.region.boundaries.first()))
        store.save(f.worldId, pending)
        val infrastructure = MutableTopologyService(f.currentTopology)
        infrastructure.operatingDirectionOverride = f.currentTopology.railProjection.keys.associateWith {
            if (it == f.entry || it == f.exit) ViewDirection.AB else ViewDirection.BA }
        val manager = runtimeManager(plugin(), infrastructure.service, CapturingRoutePlanningService(), store, CapturingSignalEpochSourceService())
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(f.worldId).status)
        val before = Files.readString(store.primaryFile(f.worldId))
        val backend = RuntimeSectionWandBackend(manager, infrastructure.service, 1000.0)
        val failure = assertThrows(IllegalArgumentException::class.java) {
            backend.perform(backend.capture(f.worldId)!!, SectionWandTool.REGION_CREATE, false, null,
                f.region.boundaries.last().position) { true }
        }
        assertTrue(failure.message!!.contains("No direction-legal"))
        assertEquals(pending, manager.authoringRuntime(f.worldId)!!.currentDefinitions())
        assertEquals(before, Files.readString(store.primaryFile(f.worldId)))
        assertEquals(0, infrastructure.permitAcquisitionCount)
    }

    @Test fun `blocked region completion never installs region without automatically generated table`() {
        val f = Fixture()
        val store = store()
        val pending = SimpleSignalsWorldDefinitions(f.worldId, 1, emptyList(), emptyList(),
            pendingProtectionBoundaries = listOf(f.region.boundaries.first()))
        store.save(f.worldId, pending)
        val registry = editRegistry { f.currentTopology }
        val infrastructure = MutableTopologyService(f.currentTopology, registry)
        val plans = CapturingRoutePlanningService()
        val manager = runtimeManager(plugin(), infrastructure.service, plans, store, CapturingSignalEpochSourceService())
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(f.worldId).status)
        val runtime = manager.authoringRuntime(f.worldId)!!
        val old = runtime.authoringPublication()
        val before = Files.readString(store.primaryFile(f.worldId))
        val unprotect = protect(registry, f.currentTopology, f.lowerIn, TopologyEditBlockerKind.LIVE_RUNTIME_CLAIM)
        try {
            val backend = RuntimeSectionWandBackend(manager, infrastructure.service, 1000.0)
            val result = backend.perform(backend.capture(f.worldId)!!, SectionWandTool.REGION_CREATE, false, null,
                f.region.boundaries.last().position) { true }
            assertFalse(result.message.contains("generated"))
            assertSame(old, runtime.authoringPublication())
            assertEquals(before, Files.readString(store.primaryFile(f.worldId)))
            assertTrue(plans.single(f.worldId).source.restrictions(f.currentTopology).pathReplacements.isEmpty())
        } finally { unprotect() }
    }

    @Test fun `region repair keeps manually selected route rather than regenerating shortest paths`() {
        val f = Fixture()
        val r = RecordingFixture(f, tables = listOf(f.table(1, f.lowerRoute())))
        val backend = RuntimeSectionWandBackend(r.manager, r.infrastructure.service, 1000.0)
        val before = r.runtime.currentDefinitions().routeTables
        backend.perform(backend.capture(f.worldId)!!, SectionWandTool.REGION_DELETE, false, null,
            f.region.boundaries.first().position) { true }
        val repaired = backend.perform(backend.capture(f.worldId)!!, SectionWandTool.REGION_CREATE, false, null,
            f.region.boundaries.first().position) { true }
        assertTrue(repaired.message.contains("Revalidated"), repaired.message)
        assertEquals(before, r.runtime.currentDefinitions().routeTables)
        assertEquals(f.lowerRoute().traversals, r.runtime.currentDefinitions().routeTables.single().routes.single().traversals)
    }

    @Test fun `existing multi row pair exact path remains no op and different path refuses arbitrary deletion`() {
        val f = Fixture()
        val table = ProtectionRouteTableDefinition("test:multiple", 1, f.region.regionId, f.region.revision,
            listOf(f.upperRoute(), f.lowerRoute()))
        val r = RecordingFixture(f, tables = listOf(table))
        val same = r.capture()
        assertTrue(r.recorder.record(same.first, same.second) { true }.contains("already recorded"))
        // Third physically legal route, not one of the two rows.
        r.active = ActiveRailContinuationsSnapshot.create(r.topology.revision, 3, mapOf(
            endpoint(f.entry, RailEndpoint.B) to endpoint(f.upperIn, RailEndpoint.A),
            endpoint(f.upperIn, RailEndpoint.B) to endpoint(f.lowerOut, RailEndpoint.A),
            endpoint(f.lowerOut, RailEndpoint.B) to endpoint(f.exit, RailEndpoint.A)))
        val third = r.capture()
        val before = r.runtime.authoringPublication()
        val disk = Files.readString(r.store.primaryFile(f.worldId))
        val failure = assertThrows(IllegalArgumentException::class.java) { r.recorder.record(third.first, third.second) { true } }
        assertTrue(failure.message!!.contains("JSON editor"))
        assertSame(before, r.runtime.authoringPublication())
        assertEquals(disk, Files.readString(r.store.primaryFile(f.worldId)))
    }

    @Test fun `record real preview creates sole table restricts Basic planning and survives reinstall`() {
        val r = RecordingFixture()
        assertTrue(r.binding.source.restrictions(r.topology).pathReplacements.isEmpty())
        val unrestricted = r.f.plan(r.binding, r.topology).route.segments.map { it.railId }
        val selectedBranch = if (r.f.upperIn in unrestricted) r.f.lowerIn else r.f.upperIn
        if (selectedBranch == r.f.lowerIn) r.lower() else r.upper()
        val (selection, preview) = r.capture()
        val message = r.recorder.record(selection, preview) { true }
        assertTrue(message.contains("Recorded region"), message)
        val saved = r.runtime.currentDefinitions()
        assertEquals(2L, saved.definitionRevision)
        val table = saved.routeTables.single()
        assertEquals(r.f.region.revision, table.regionRevision)
        assertTrue(table.tableId.startsWith("simplesignals:"))
        assertTrue(table.routes.single().routeId.startsWith("simplesignals:"))
        assertEquals(preview.path.traversals, table.routes.single().traversals)
        val recorded = r.f.plan(r.binding, r.topology).route.segments.map { it.railId }
        assertTrue(selectedBranch in recorded)
        assertFalse(selectedBranch in unrestricted)
        // Basic keeps the outside approach/departure spans separate from the complete table macro.
        assertEquals(listOf(r.f.entry) + preview.path.traversals.map { it.railId } + r.f.exit, recorded)
        r.manager.remove(r.f.worldId)
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, r.manager.install(r.f.worldId).status)
        assertEquals(saved, r.runtime.currentDefinitions())
        assertEquals(recorded, r.f.plan(r.binding, r.topology).route.segments.map { it.railId })
        assertThrows(IllegalArgumentException::class.java) { r.recorder.record(selection, preview) { true } }
    }

    @Test fun `duplicate recording leaves revision file backup sources and permits unchanged`() {
        val r = RecordingFixture()
        var capture = r.capture()
        r.recorder.record(capture.first, capture.second) { true }
        capture = r.capture()
        val before = r.runtime.authoringPublication()
        val file = Files.readString(r.store.primaryFile(r.f.worldId))
        val backup = Files.readString(r.store.backupFile(r.f.worldId))
        val sources = r.sources.sources.getValue(r.f.worldId)
        val permits = r.infrastructure.permitAcquisitionCount
        val result = r.recorder.record(capture.first, capture.second) { true }
        assertTrue(result.contains(before.definitions.routeTables.single().routes.single().routeId), result)
        assertTrue(result.contains("already recorded"), result)
        assertSame(before, r.runtime.authoringPublication())
        assertSame(sources, r.sources.sources.getValue(r.f.worldId))
        assertEquals(permits, r.infrastructure.permitAcquisitionCount)
        assertEquals(file, Files.readString(r.store.primaryFile(r.f.worldId)))
        assertEquals(backup, Files.readString(r.store.backupFile(r.f.worldId)))
    }

    @Test fun `same gates different branches replace while retaining route and device identity`() {
        val r = RecordingFixture()
        val first = r.capture()
        r.recorder.record(first.first, first.second) { true }
        val table = r.runtime.currentDefinitions().routeTables.single()
        val device = protectionSignal(ProtectionSignalKind.FIXED_ROUTE, table.routes.single())
        val indicator = RouteIndicatorDefinition("test:route-display", 0, table.routes.single().entryBoundaryId,
            "none", mapOf(table.routes.single().routeId to "selected"))
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED, r.manager.applyDefinitionPatch(r.f.worldId, 2,
            SimpleSignalsDefinitionPatch(upsertRouteTables = listOf(ProtectionRouteTableDefinition(table.tableId, 1,
                table.regionId, table.regionRevision, table.routes)),
                upsertProtectionSignals = listOf(device), upsertRouteIndicators = listOf(indicator))).status)
        val before = r.runtime.currentDefinitions().routeTables.single()
        r.lower()
        val next = r.capture()
        assertTrue(r.recorder.record(next.first, next.second) { true }.contains("Recorded region"))
        val after = r.runtime.currentDefinitions().routeTables.single()
        assertEquals(before.tableId, after.tableId)
        assertEquals(2L, after.revision)
        assertEquals(before.routes.single().routeId, after.routes.single().routeId)
        assertFalse(before.routes.single().traversals == after.routes.single().traversals)
        assertEquals(listOf(device), r.runtime.currentDefinitions().protectionSignals)
        assertEquals(listOf(indicator), r.runtime.currentDefinitions().routeIndicators)
        assertEquals(next.second.path.traversals, after.routes.last().traversals)
        assertEquals(1, r.binding.source.restrictions(r.topology).pathReplacements.single().permittedPaths.size)
    }

    @Test fun `record multi rail reverse BA preview without another trace or geometry sampling`() {
        val r = RecordingFixture()
        r.active = ActiveRailContinuationsSnapshot.create(r.topology.revision, 2, mapOf(
            endpoint(r.f.exit, RailEndpoint.A) to endpoint(r.f.upperOut, RailEndpoint.B),
            endpoint(r.f.upperOut, RailEndpoint.A) to endpoint(r.f.upperIn, RailEndpoint.B),
            endpoint(r.f.upperIn, RailEndpoint.A) to endpoint(r.f.entry, RailEndpoint.B)))
        val (selection, value) = r.capture(reverse = true)
        assertEquals(CurrentRouteEnd.REACHED_EXIT, value.path.end)
        assertEquals(4, value.path.traversals.size)
        assertTrue(value.path.traversals.all { it.facing == ViewDirection.BA })
        val curves = r.curveReads
        assertTrue(r.recorder.record(selection, value) { true }.contains("Recorded region"))
        assertEquals(curves, r.curveReads)
        assertEquals(value.path.traversals, r.runtime.currentDefinitions().routeTables.single().routes.single().traversals)
    }

    @Test fun `turnout change before capture is stale while change after freeze preserves captured path`() {
        val r = RecordingFixture()
        val capture = r.capture()
        r.lower()
        assertThrows(IllegalArgumentException::class.java) { r.recorder.record(capture.first, capture.second) { true } }
        r.upper()
        val frozen = r.capture()
        val curves = r.curveReads
        r.sources.beforeCommitAction = { r.lower() }
        assertTrue(r.recorder.record(frozen.first, frozen.second) { true }.contains("Recorded region"))
        assertEquals(frozen.second.path.traversals, r.runtime.currentDefinitions().routeTables.single().routes.single().traversals)
        assertEquals(curves, r.curveReads)
    }

    @Test fun `missing unsuccessful unknown region and cancelled previews never save`() {
        val r = RecordingFixture()
        r.active = ActiveRailContinuationsSnapshot.empty(r.topology.revision)
        val incomplete = r.capture()
        assertEquals(CurrentRouteEnd.UNSELECTED, incomplete.second.path.end)
        assertThrows(IllegalArgumentException::class.java) { r.recorder.record(incomplete.first, incomplete.second) { true } }
        r.upper()
        val capture = r.capture()
        assertThrows(IllegalArgumentException::class.java) { r.recorder.record(capture.first, capture.second) { false } }
        assertThrows(IllegalArgumentException::class.java) {
            r.recorder.record(capture.first.copy(regionId = "test:unknown"), capture.second) { true }
        }
        assertEquals(1L, r.runtime.currentDefinitions().definitionRevision)
        assertEquals(0, r.infrastructure.permitAcquisitionCount)
    }

    @Test fun `recording is blocked by real Basic static dependency and live claim with no state changes`() {
        val f = Fixture()
        val registry = editRegistry { f.currentTopology }
        val r = RecordingFixture(f = f, registry = registry)
        val capture = r.capture()
        val before = r.runtime.authoringPublication()
        val disk = Files.readString(r.store.primaryFile(f.worldId))
        val sources = r.sources.sources.getValue(f.worldId)
        for (kind in listOf(TopologyEditBlockerKind.INFRASTRUCTURE_DEPENDENCY, TopologyEditBlockerKind.LIVE_RUNTIME_CLAIM)) {
            val release = protect(registry, f.currentTopology, f.lowerIn, kind)
            val result = r.recorder.record(capture.first, capture.second) { true }
            assertTrue(result.contains("EDIT_BLOCKED"), result)
            assertTrue(result.contains(kind.name), result)
            assertSame(before, r.runtime.authoringPublication())
            assertSame(sources, r.sources.sources.getValue(f.worldId))
            assertEquals(disk, Files.readString(r.store.primaryFile(f.worldId)))
            release()
        }
        assertTrue(r.recorder.record(capture.first, capture.second) { true }.contains("Recorded region"))
        assertTrue(registry.claimSnapshots().isEmpty())
        assertTrue(r.infrastructure.lastEditRequest!!.impact.railIds.contains(f.lowerIn), "whole region is protected")
    }

    @Test fun `replacement rejects invalid retained other pair from prior region revision without filtering it`() {
        val f = Fixture()
        // This is the persisted state after region repair: the older authored table retains
        // both paths, while its compiled usable subset contains only the still-valid route.
        val table = ProtectionRouteTableDefinition("test:throat-table", 1, f.region.regionId, 0,
            listOf(f.upperRoute(), ProtectionRouteDefinition("test:broken", "test:exit", "test:entry", f.discontinuousRoute().traversals)))
        val r = RecordingFixture(f, tables = listOf(table))
        assertEquals(2, r.runtime.currentDefinitions().routeTables.single().routes.size)
        assertEquals(1, r.binding.source.restrictions(r.topology).pathReplacements.single().permittedPaths.size)
        val before = r.runtime.authoringPublication()
        val disk = Files.readString(r.store.primaryFile(r.f.worldId))
        r.lower()
        val capture = r.capture()
        assertEquals(CurrentRouteEnd.REACHED_EXIT, capture.second.path.end)
        val result = r.recorder.record(capture.first, capture.second) { true }
        assertTrue(result.contains("VALIDATION_REJECTED"), result)
        assertSame(before, r.runtime.authoringPublication())
        assertEquals(disk, Files.readString(r.store.primaryFile(r.f.worldId)))
    }

    @Test fun `recording save source and commit cancellation failures preserve primary and publication`() {
        val r = RecordingFixture()
        val capture = r.capture()
        val before = r.runtime.authoringPublication()
        val disk = Files.readString(r.store.primaryFile(r.f.worldId))
        var active = true
        r.sources.beforeCommitAction = { active = false }
        assertTrue(r.recorder.record(capture.first, capture.second) { active }.contains("WORLD_UNAVAILABLE"))
        assertSame(before, r.runtime.authoringPublication())
        assertEquals(disk, Files.readString(r.store.primaryFile(r.f.worldId)))
        r.sources.beforeCommitAction = {}
        r.sources.nextPrevalidationFailure = IllegalArgumentException("source rejected")
        assertTrue(r.recorder.record(capture.first, capture.second) { true }.contains("VALIDATION_REJECTED"))
        assertSame(before, r.runtime.authoringPublication())
        assertEquals(disk, Files.readString(r.store.primaryFile(r.f.worldId)))
        r.manager.remove(r.f.worldId)
        val failing = SimpleSignalsWorldDefinitionStore(directory.toFile()) { throw java.io.IOException("injected before primary replace") }
        val manager = runtimeManager(plugin(), r.infrastructure.service, r.routes, failing, r.sources)
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(r.f.worldId).status)
        val backend = r.backend(manager)
        val selected = backend.select(r.f.worldId, r.f.region.boundaries.last().position,
            backend.select(r.f.worldId, r.f.region.boundaries.first().position, null))
        val value = backend.compute(selected)
        val retained = manager.authoringRuntime(r.f.worldId)!!.authoringPublication()
        val result = CurrentRouteRecording(manager, r.infrastructure.service, backend).record(selected, value) { true }
        assertTrue(result.contains("PERSISTENCE_FAILED"), result)
        assertSame(retained, manager.authoringRuntime(r.f.worldId)!!.authoringPublication())
        assertEquals(disk, Files.readString(r.store.primaryFile(r.f.worldId)))
        assertEquals(r.infrastructure.permitAcquisitionCount, r.infrastructure.permitCloseCount)
    }

    /** Real preview/tracer + manager/store + Basic permit/planner, with host observations provided as values. */
    private inner class RecordingFixture(val f: Fixture = Fixture(), registry: InfrastructureCoordinationRegistry? = null,
        tables: List<ProtectionRouteTableDefinition> = emptyList()) {
        val topology = f.currentTopology
        val store = store()
        val infrastructure = MutableTopologyService(topology, registry)
        val routes = CapturingRoutePlanningService()
        val sources = CapturingSignalEpochSourceService()
        val manager: SimpleSignalsWorldRuntimeManager
        val runtime get() = checkNotNull(manager.authoringRuntime(f.worldId))
        val binding get() = routes.single(f.worldId)
        var active = ActiveRailContinuationsSnapshot.empty(topology.revision)
        var curveReads = 0
        init {
            store.save(f.worldId, SimpleSignalsWorldDefinitions(f.worldId, 1, listOf(f.region), tables))
            manager = runtimeManager(plugin(), infrastructure.service, routes, store, sources)
            assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(f.worldId).status)
            upper()
        }
        fun upper() { active = ActiveRailContinuationsSnapshot.create(topology.revision, 1, mapOf(
            endpoint(f.entry, RailEndpoint.B) to endpoint(f.upperIn, RailEndpoint.A),
            endpoint(f.upperIn, RailEndpoint.B) to endpoint(f.upperOut, RailEndpoint.A),
            endpoint(f.upperOut, RailEndpoint.B) to endpoint(f.exit, RailEndpoint.A))) }
        fun lower() { active = ActiveRailContinuationsSnapshot.create(topology.revision, 2, mapOf(
            endpoint(f.entry, RailEndpoint.B) to endpoint(f.lowerIn, RailEndpoint.A),
            endpoint(f.lowerIn, RailEndpoint.B) to endpoint(f.lowerOut, RailEndpoint.A),
            endpoint(f.lowerOut, RailEndpoint.B) to endpoint(f.exit, RailEndpoint.A))) }
        fun backend(manager: SimpleSignalsWorldRuntimeManager = this.manager): RuntimeRoutePreviewBackend {
            val observations = proxy(RailwayObservationService::class.java) { method, _ ->
                check(method.name == "latest")
                java.util.Optional.of(RailwayObservation.fromCore(f.worldId.toString(),
                    RailwayWorldFrameSnapshot.create(1, active.topologyRevision, emptyList(), active)))
            }
            val curves = fixtureRailTargets()
            val targets = proxy(RailTargetService::class.java) { method, args ->
                check(method.name == "findCurve"); curveReads++
                curves.findCurve(args!![0] as UUID, args[1] as UUID)
            }
            return RuntimeRoutePreviewBackend(plugin(), manager::authoringRuntime, infrastructure.service, observations, targets)
        }
        val preview by lazy { backend() }
        val recorder by lazy { CurrentRouteRecording(manager, infrastructure.service, preview) }
        fun capture(reverse: Boolean = false): Pair<RoutePreviewSelection, RoutePreviewComputation> {
            val region = runtime.currentDefinitions().regions.single()
            val entry = if (reverse) region.boundaries.last() else region.boundaries.first()
            val exit = if (reverse) region.boundaries.first() else region.boundaries.last()
            val selection = preview.select(f.worldId, exit.position, preview.select(f.worldId, entry.position, null))
            return selection to preview.compute(selection)
        }
    }

    @Test
    fun `zero remaining region markers preserve models tables and local refusal through restart and repair`() {
        val fixture = Fixture()
        val store = store()
        val initial = deviceModelDefinitions(fixture, "fixed-route", DeviceModelDefinition("demo_signals:home",
            ModelPlacement.Ground(ModelPose.IDENTITY)))
        store.save(fixture.worldId, initial)
        val infrastructure = MutableTopologyService(fixture.currentTopology)
        val sources = CapturingSignalEpochSourceService()
        val models = RecordingModels()
        val owner = plugin()
        val manager = runtimeManager(owner, infrastructure.service, CapturingRoutePlanningService(), store, sources,
            modelPresentation = models)
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(fixture.worldId).status)
        val runtime = manager.authoringRuntime(fixture.worldId)!!
        val handle = models.handles.single()
        val index = runtime.protectionRegionSnapshot().derivedTopology
        val draft = ProtectionRegionDefinition(fixture.region.regionId, 2, fixture.region.boundaries, emptyList())
        val result = manager.applyDefinitionPatch(fixture.worldId, 1, SimpleSignalsDefinitionPatch(upsertRegions = listOf(draft)))
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED, result.status, result.diagnostics.toString())
        assertEquals(initial.routeTables, runtime.currentDefinitions().routeTables)
        assertEquals(initial.protectionSignals, runtime.currentDefinitions().protectionSignals)
        assertSame(handle, models.handles.single())
        assertEquals(0, handle.closeCalls)
        assertSame(index, runtime.protectionRegionSnapshot().derivedTopology, "same-root edits reuse the topology index")
        val inside = RouteSnapshot("test:inside", 1, fixture.currentTopology.revision,
            listOf(RouteSegmentSnapshot(fixture.upperIn, ViewDirection.AB, 1.0, 9.0)))
        val unrelated = RouteSnapshot("test:outside", 1, fixture.currentTopology.revision,
            listOf(RouteSegmentSnapshot(fixture.exit, ViewDirection.AB, 6.0, 9.0)))
        fun intent(route: RouteSnapshot) = work.art1st.javarailways.basic.api.route.RouteResourceIntent(
            "test:request", InfrastructureOwner("test:direct", 1), "train",
            work.art1st.javarailways.basic.api.observation.RailwayStateStamp(fixture.worldId.toString(), 1, fixture.currentTopology.revision),
            route,
            work.art1st.javarailways.basic.api.route.RouteBoundary(route.segments.sumOf { it.lengthMeters }))
        val contributor = work.art1st.javarailways.simplesignals.block.FixedBlockRouteResourcePolicy(owner,
            validateRouteAdmission = manager::validateRouteAdmission, snapshotForWorld = manager::fixedBlockSnapshot)
        assertThrows(IllegalStateException::class.java) { contributor.contribute(fixture.worldId, intent(inside)) }
        assertTrue(contributor.contribute(fixture.worldId, intent(unrelated)).isEmpty())
        assertThrows(IllegalStateException::class.java) { runtime.protectionRegionInspectionSource.routeInfrastructureDependencies.dependencies(inside) }
        assertTrue(runtime.protectionRegionInspectionSource.routeInfrastructureDependencies.dependencies(unrelated).isEmpty())
        assertTrue(runtime.routePlanningBinding.source.restrictions(fixture.currentTopology).forbiddenTraversals.any { it.railId == fixture.upperIn })
        val epochs = SignalEpochRuntime(fixture.worldId.toString(), { value ->
            work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublicationResult(
                work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublicationStatus.ACCEPTED, value.revision) })
        epochs.replaceSources(sources.sources.getValue(fixture.worldId))
        epochs.process(RailwayWorldFrameSnapshot.create(1, fixture.currentTopology.revision, emptyList(),
            ActiveRailContinuationsSnapshot.empty(fixture.currentTopology.revision)))
        assertTrue(runtime.sectionSignalSnapshot()!!.states.getValue(initial.protectionSignals.single().signalId) is SectionSignalState.Unavailable)

        manager.remove(fixture.worldId)
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(fixture.worldId).status)
        val reloaded = manager.authoringRuntime(fixture.worldId)!!
        assertEquals(emptyList<ProtectionBoundaryDefinition>(), reloaded.currentDefinitions().regions.single().boundaryDraft)
        assertEquals(fixture.region.boundaries, reloaded.currentDefinitions().regions.single().boundaries)
        assertThrows(IllegalStateException::class.java) { manager.validateRouteAdmission(fixture.worldId, intent(inside)) }
        val restored = ProtectionRegionDefinition(fixture.region.regionId, 3, fixture.region.boundaries)
        val repair = manager.applyDefinitionPatch(fixture.worldId, 2, SimpleSignalsDefinitionPatch(upsertRegions = listOf(restored)))
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED, repair.status, repair.diagnostics.toString())
        assertTrue(reloaded.routePlanningBinding.source.restrictions(fixture.currentTopology).forbiddenTraversals.isEmpty())
        assertEquals(setOf(fixture.upperRoute().routeId), reloaded.authoringPublication().routeSelection.routesById.keys)
        assertEquals(initial.routeTables, reloaded.currentDefinitions().routeTables, "revalidation must not rewrite authored routes")
    }

    @Test
    fun `pending boundary edits protect only changed positions and reject canceled or stale selections`() {
        val fixture = Fixture()
        val topology = fixture.currentTopology
        val registry = editRegistry(topology)
        val infrastructure = MutableTopologyService(topology, registry)
        val sources = CapturingSignalEpochSourceService()
        val manager = runtimeManager(plugin(), infrastructure.service, CapturingRoutePlanningService(), store(), sources)
        manager.install(fixture.worldId)
        val first = ProtectionBoundaryDefinition("test:pending-first", RailPositionSnapshot(fixture.entry, ViewDirection.AB, 3.0))
        val second = ProtectionBoundaryDefinition("test:pending-second", RailPositionSnapshot(fixture.exit, ViewDirection.BA, 7.0))
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED, manager.applyDefinitionPatch(fixture.worldId, 0,
            SimpleSignalsDefinitionPatch(pendingProtectionBoundaries = listOf(first))).status)
        protect(registry, topology, fixture.entry, TopologyEditBlockerKind.INFRASTRUCTURE_DEPENDENCY)
        val patch = SimpleSignalsDefinitionPatch(pendingProtectionBoundaries = listOf(first, second))
        val added = manager.applyDefinitionPatch(fixture.worldId, 1, patch)
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED, added.status, added.diagnostics.toString())
        assertEquals(setOf(fixture.exit), infrastructure.lastEditRequest!!.impact.railIds)
        val runtime = manager.authoringRuntime(fixture.worldId)!!
        assertTrue(runtime.currentDefinitions().regions.isEmpty())
        assertTrue(runtime.protectionRegionSnapshot().derivedTopology.rails.isEmpty(),
            "pending-only edits validate native rail projections without deriving the world graph")
        val moved = ProtectionBoundaryDefinition(second.boundaryId, RailPositionSnapshot(fixture.exit, ViewDirection.BA, 8.0))
        val canceled = SimpleSignalsDefinitionPatch(pendingProtectionBoundaries = listOf(first, moved))
        var active = true
        sources.beforeCommitAction = { active = false }
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.WORLD_UNAVAILABLE,
            manager.applyDefinitionPatch(fixture.worldId, 2, canceled, runtime, topology) { active }.status)
        assertEquals(listOf(first, second), runtime.currentDefinitions().pendingProtectionBoundaries)
        sources.beforeCommitAction = {}
        infrastructure.topology = fixture.topology(topology.revision + 1)
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.STALE_TOPOLOGY,
            manager.applyDefinitionPatch(fixture.worldId, 2, canceled, runtime, topology).status)
        manager.remove(fixture.worldId)
        manager.install(fixture.worldId)
        assertEquals(listOf(first, second), manager.authoringRuntime(fixture.worldId)!!.currentDefinitions().pendingProtectionBoundaries)
    }

    @Test
    fun `pending-only publication lazily derives a shared topology index for the first completed section`() {
        val fixture = Fixture()
        val runtime = signalWorldRuntime(fixture.worldId)
        val pending = ProtectionBoundaryDefinition("test:pending", RailPositionSnapshot(fixture.exit, ViewDirection.BA, 7.0))
        val seed = SimpleSignalsWorldDefinitions(fixture.worldId, 1, emptyList(), emptyList(),
            pendingProtectionBoundaries = listOf(pending))
        assertEquals(SimpleSignalsDefinitionPublicationStatus.PUBLISHED,
            runtime.initializeDefinitions(fixture.currentTopology, seed).status)
        assertTrue(runtime.protectionRegionSnapshot().derivedTopology.rails.isEmpty())
        val block = FixedBlockDefinition("test:block", 1,
            listOf(FixedBlockTraversal(fixture.entry, ViewDirection.AB, 0.0, 2.0)))
        val next = SimpleSignalsWorldDefinitions(fixture.worldId, 2, emptyList(), emptyList(), listOf(block),
            pendingProtectionBoundaries = listOf(pending))
        val prepared = runtime.prepareDefinitions(fixture.currentTopology, next) as SimpleSignalsDefinitionPreparationResult.Prepared
        assertEquals(SimpleSignalsDefinitionCommitStatus.COMMITTED, runtime.commitPrepared(prepared.value))
        val index = runtime.protectionRegionSnapshot().derivedTopology
        assertEquals(fixture.currentTopology.railProjection.keys, index.rails.keys)
        assertSame(index, runtime.fixedBlockSnapshot().routeTopology)
        val moved = ProtectionBoundaryDefinition(pending.boundaryId, RailPositionSnapshot(fixture.exit, ViewDirection.BA, 8.0))
        val edit = runtime.prepareDefinitions(fixture.currentTopology, SimpleSignalsWorldDefinitions(fixture.worldId,
            3, emptyList(), emptyList(), listOf(block), pendingProtectionBoundaries = listOf(moved))) as
            SimpleSignalsDefinitionPreparationResult.Prepared
        assertEquals(SimpleSignalsDefinitionCommitStatus.COMMITTED, runtime.commitPrepared(edit.value))
        assertSame(index, runtime.protectionRegionSnapshot().derivedTopology)
        assertSame(index, runtime.fixedBlockSnapshot().routeTopology)
    }

    @Test
    fun `attached references collisions revision and orphan deletion reject before mutation while combined edits succeed`() {
        val fixture = Fixture()
        val seed = deviceModelDefinitions(fixture, "fixed-route", null)
        val fixed = FixedBlockSignalDefinition("test:fixed", 1, "test:block", 0.0, "automatic:block")
        val indicator = RouteIndicatorDefinition("test:indicator", 1, "test:entry", "stop",
            mapOf(fixture.upperRoute().routeId to "proceed"))
        val attached = AttachedSignalDefinition("test:attached", 1, fixed.signalId, railModel(fixture.entry))
        val initial = SimpleSignalsWorldDefinitions(fixture.worldId, 1, seed.regions, seed.routeTables,
            seed.fixedBlocks, listOf(fixed), seed.protectionSignals, listOf(indicator), listOf(attached))
        val store = store()
        store.save(fixture.worldId, initial)
        val infrastructure = MutableTopologyService(fixture.currentTopology)
        val sources = CapturingSignalEpochSourceService()
        val planning = CapturingRoutePlanningService()
        val models = RecordingModels()
        var queries = 0
        val delegate = fixtureRailTargets()
        val rails = proxy(RailTargetService::class.java) { _, args ->
            queries++
            delegate.findCurve(args!![0] as UUID, args[1] as UUID)
        }
        val manager = runtimeManager(plugin(), infrastructure.service, planning, store, sources,
            railTargets = rails, modelPresentation = models)
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(fixture.worldId).status)
        val runtime = manager.authoringRuntime(fixture.worldId)!!
        val previous = runtime.authoringPublication()
        val disk = Files.readString(store.primaryFile(fixture.worldId))
        val source = sources.sources.getValue(fixture.worldId)
        val handle = models.handles.single()
        val invalid = listOf("test:unknown", attached.signalId, indicator.indicatorId).map {
            attached.copy(revision = 2, primarySignalId = it)
        } + listOf(fixed.signalId, seed.protectionSignals.single().signalId, indicator.indicatorId).map {
            attached.copy(signalId = it, revision = 2)
        } + attached.copy(model = DeviceModelDefinition("demo_signals:wall", ModelPlacement.Ground(ModelPose.IDENTITY))) +
            attached.copy(revision = 0)
        invalid.forEach { definition ->
            val rejected = manager.applyDefinitionPatch(fixture.worldId, 1,
                SimpleSignalsDefinitionPatch(upsertAttachedSignals = listOf(definition)))
            assertEquals(SimpleSignalsDefinitionAuthoringStatus.VALIDATION_REJECTED, rejected.status, rejected.diagnostics.toString())
        }
        listOf(SimpleSignalsDefinitionPatch(upsertAttachedSignals = listOf(attached, attached)),
            SimpleSignalsDefinitionPatch(upsertAttachedSignals = listOf(attached), removeAttachedSignalIds = listOf(attached.signalId)),
            SimpleSignalsDefinitionPatch(removeAttachedSignalIds = listOf("test:missing")),
            SimpleSignalsDefinitionPatch(upsertAttachedSignals = listOf(attached))).forEach { patch ->
            assertEquals(SimpleSignalsDefinitionAuthoringStatus.PATCH_REJECTED,
                manager.applyDefinitionPatch(fixture.worldId, 1, patch).status)
        }
        val duplicate = withAttachments(initial, listOf(attached, attached), 2)
        assertInstanceOf(SimpleSignalsDefinitionPreparationResult.Rejected::class.java,
            runtime.prepareDefinitions(fixture.currentTopology, duplicate))
        val orphan = manager.applyDefinitionPatch(fixture.worldId, 1,
            SimpleSignalsDefinitionPatch(removeFixedBlockSignalIds = listOf(fixed.signalId)))
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.VALIDATION_REJECTED, orphan.status)
        assertSame(previous, runtime.authoringPublication())
        assertEquals(disk, Files.readString(store.primaryFile(fixture.worldId)))
        assertSame(source, sources.sources.getValue(fixture.worldId))
        assertEquals(0, infrastructure.permitAcquisitionCount)
        assertSame(handle, models.handles.single())
        assertEquals(0, handle.closeCalls)
        val rebound = manager.applyDefinitionPatch(fixture.worldId, 1, SimpleSignalsDefinitionPatch(
            removeFixedBlockSignalIds = listOf(fixed.signalId), upsertAttachedSignals = listOf(
                attached.copy(revision = 2, primarySignalId = seed.protectionSignals.single().signalId))))
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED, rebound.status, rebound.diagnostics.toString())
        assertSame(handle, models.handles.single())
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.VALIDATION_REJECTED,
            manager.applyDefinitionPatch(fixture.worldId, 2, SimpleSignalsDefinitionPatch(
                removeProtectionSignalIds = listOf(seed.protectionSignals.single().signalId))).status)
        val removed = manager.applyDefinitionPatch(fixture.worldId, 2, SimpleSignalsDefinitionPatch(
            removeProtectionSignalIds = listOf(seed.protectionSignals.single().signalId),
            removeAttachedSignalIds = listOf(attached.signalId)))
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED, removed.status)
        assertTrue(removed.definitions!!.attachedSignals.isEmpty())
        assertEquals(1, handle.closeCalls)
    }

    @Test
    fun `attachment rebind protects both placement rails and old block plus new protection territory`() {
        listOf(TopologyEditBlockerKind.INFRASTRUCTURE_DEPENDENCY, TopologyEditBlockerKind.LIVE_RUNTIME_CLAIM).forEach { kind ->
            (0..3).forEach { protectedIndex ->
                val fixture = Fixture()
                val oldLogical = UUID(0, 90)
                val oldPlacement = UUID(0, 91)
                val newPlacement = UUID(0, 92)
                val unrelated = UUID(0, 93)
                val topology = fixture.topology(41, listOf(oldLogical, oldPlacement, newPlacement, unrelated))
                val base = deviceModelDefinitions(fixture, "fixed-route", null)
                val fixed = FixedBlockSignalDefinition("test:old-primary", 1, "test:old-block", 0.0,
                    "automatic:block")
                val attached = AttachedSignalDefinition("test:attached", 1, fixed.signalId, railModel(oldPlacement))
                val initial = SimpleSignalsWorldDefinitions(fixture.worldId, 1, base.regions, base.routeTables,
                    base.fixedBlocks + FixedBlockDefinition("test:old-block", 1,
                        listOf(FixedBlockTraversal(oldLogical, ViewDirection.AB, 0.0, 4.0))),
                    listOf(fixed), base.protectionSignals, attachedSignals = listOf(attached))
                val store = store()
                store.save(fixture.worldId, initial)
                val registry = editRegistry(topology)
                val infrastructure = MutableTopologyService(topology, registry)
                val sources = CapturingSignalEpochSourceService()
                val models = RecordingModels()
                val manager = runtimeManager(plugin(), infrastructure.service, CapturingRoutePlanningService(), store,
                    sources, modelPresentation = models)
                assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(fixture.worldId).status)
                val runtime = manager.authoringRuntime(fixture.worldId)!!
                val publication = runtime.authoringPublication()
                val disk = Files.readString(store.primaryFile(fixture.worldId))
                val source = sources.sources.getValue(fixture.worldId)
                val handle = models.handles.single()
                protect(registry, topology, unrelated, TopologyEditBlockerKind.INFRASTRUCTURE_DEPENDENCY)
                val protected = listOf(oldPlacement, newPlacement, oldLogical, fixture.upperIn)[protectedIndex]
                val release = protect(registry, topology, protected, kind)
                val moved = attached.copy(revision = 2, primarySignalId = base.protectionSignals.single().signalId,
                    model = railModel(newPlacement, 2.0))
                val patch = SimpleSignalsDefinitionPatch(upsertAttachedSignals = listOf(moved))
                val expected = setOf(oldPlacement, newPlacement, oldLogical) + deviceLogicalRails(fixture, "fixed-route")
                val blocked = manager.applyDefinitionPatch(fixture.worldId, 1, patch)
                assertEquals(SimpleSignalsDefinitionAuthoringStatus.EDIT_BLOCKED, blocked.status, blocked.diagnostics.toString())
                assertTrue(infrastructure.lastEditRequest!!.impact.railIds.containsAll(expected))
                assertFalse(unrelated in infrastructure.lastEditRequest!!.impact.railIds)
                assertEquals(kind, blocked.editDecision!!.blockers.single().kind)
                assertSame(publication, runtime.authoringPublication())
                assertEquals(disk, Files.readString(store.primaryFile(fixture.worldId)))
                assertSame(source, sources.sources.getValue(fixture.worldId))
                assertSame(handle, models.handles.single())
                release()
                assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED,
                    manager.applyDefinitionPatch(fixture.worldId, 1, patch).status)
                assertTrue(infrastructure.lastEditRequest!!.impact.railIds.containsAll(expected))
                assertFalse(unrelated in infrastructure.lastEditRequest!!.impact.railIds)
                assertSame(handle, models.handles.single())
                assertEquals(2.0, handle.submissions.last().transform().x())
                assertEquals(0, handle.closeCalls)
            }
        }
    }

    @Test
    fun `display only edits omit upstream consumers while primary edits protect dependent attached placement`() {
        val fixture = Fixture()
        val placement = UUID(0, 91)
        val upstreamDisplay = UUID(0, 92)
        val topology = topologyState(41, listOf(fixture.entry, fixture.exit, placement, upstreamDisplay), listOf(
            endpoint(fixture.entry, RailEndpoint.B) to endpoint(fixture.exit, RailEndpoint.A)))
        val blocks = listOf("upstream" to fixture.entry, "primary" to fixture.exit).map { (name, rail) ->
            FixedBlockDefinition("test:$name-block", 1, listOf(FixedBlockTraversal(rail, ViewDirection.AB, 0.0, 4.0)))
        }
        fun signal(name: String, revision: Long = 1) = FixedBlockSignalDefinition("test:$name", revision,
            "test:$name-block", 0.0, "automatic:block")
        val attached = AttachedSignalDefinition("test:attached", 1, "test:primary", railModel(placement))
        val unrelated = AttachedSignalDefinition("test:upstream-display", 1, "test:upstream", railModel(upstreamDisplay))
        val store = store()
        store.save(fixture.worldId, SimpleSignalsWorldDefinitions(fixture.worldId, 1, emptyList(), emptyList(), blocks,
            listOf(signal("primary"), signal("upstream")), attachedSignals = listOf(attached, unrelated)))
        val registry = editRegistry(topology)
        val infrastructure = MutableTopologyService(topology, registry)
        val models = RecordingModels()
        val manager = runtimeManager(plugin(), infrastructure.service, CapturingRoutePlanningService(), store,
            CapturingSignalEpochSourceService(), modelPresentation = models)
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(fixture.worldId).status)
        val handle = models.byId("attached-signal/test:attached")
        val other = models.byId("attached-signal/test:upstream-display")
        val releaseUpstream = protect(registry, topology, fixture.entry, TopologyEditBlockerKind.INFRASTRUCTURE_DEPENDENCY)
        val releaseOther = protect(registry, topology, upstreamDisplay, TopologyEditBlockerKind.LIVE_RUNTIME_CLAIM)
        val moved = attached.copy(revision = 2, model = railModel(placement, 2.0))
        val applied = manager.applyDefinitionPatch(fixture.worldId, 1, SimpleSignalsDefinitionPatch(upsertAttachedSignals = listOf(moved)))
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED, applied.status, applied.diagnostics.toString())
        assertEquals(setOf(placement, fixture.exit), infrastructure.lastEditRequest!!.impact.railIds)
        assertSame(handle, models.byId(handle.id))
        assertSame(other, models.byId(other.id))
        releaseUpstream()
        releaseOther()
        val release = protect(registry, topology, placement, TopologyEditBlockerKind.INFRASTRUCTURE_DEPENDENCY)
        val blocked = manager.applyDefinitionPatch(fixture.worldId, 2,
            SimpleSignalsDefinitionPatch(upsertFixedBlockSignals = listOf(signal("primary", 2))))
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.EDIT_BLOCKED, blocked.status)
        assertEquals(setOf(placement, upstreamDisplay, fixture.entry, fixture.exit), infrastructure.lastEditRequest!!.impact.railIds)
        assertEquals(setOf(placement), blocked.editDecision!!.blockers.single().overlap.railIds)
        release()
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED, manager.applyDefinitionPatch(fixture.worldId, 2,
            SimpleSignalsDefinitionPatch(upsertFixedBlockSignals = listOf(signal("primary", 2)))).status)
        assertEquals(2, models.handles.size)
        assertTrue(models.handles.all { it.closeCalls == 0 })
    }

    @Test
    fun `attachment source persistence and stale commit failures preserve disk publication sources and handles`() {
        listOf("source", "persistence", "topology").forEach { failure ->
            val fixture = Fixture()
            val initial = deviceModelDefinitions(fixture, "attached-fixed", railModel(fixture.entry))
            store().save(fixture.worldId, initial)
            var failSave = false
            val store = SimpleSignalsWorldDefinitionStore(directory.toFile()) {
                if (failSave) throw java.io.IOException("injected attachment save failure")
            }
            val infrastructure = MutableTopologyService(fixture.currentTopology)
            val sources = CapturingSignalEpochSourceService()
            val models = RecordingModels()
            val manager = runtimeManager(plugin(), infrastructure.service, CapturingRoutePlanningService(), store,
                sources, modelPresentation = models)
            assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(fixture.worldId).status)
            val runtime = manager.authoringRuntime(fixture.worldId)!!
            val publication = runtime.authoringPublication()
            val disk = Files.readString(store.primaryFile(fixture.worldId))
            val source = sources.sources.getValue(fixture.worldId)
            val handle = models.handles.single()
            val submissions = handle.submissions.size
            when (failure) {
                "source" -> sources.nextPrevalidationFailure = IllegalArgumentException("injected source failure")
                "persistence" -> failSave = true
                "topology" -> sources.beforeCommitAction = { infrastructure.topology = fixture.topology(42) }
            }
            val result = manager.applyDefinitionPatch(fixture.worldId, 1, SimpleSignalsDefinitionPatch(
                upsertAttachedSignals = listOf(initial.attachedSignals.single().copy(revision = 2,
                    model = railModel(fixture.entry, 2.0)))))
            val expected = when (failure) {
                "source" -> SimpleSignalsDefinitionAuthoringStatus.VALIDATION_REJECTED
                "persistence" -> SimpleSignalsDefinitionAuthoringStatus.PERSISTENCE_FAILED
                else -> SimpleSignalsDefinitionAuthoringStatus.STALE_TOPOLOGY
            }
            assertEquals(expected, result.status, result.diagnostics.toString())
            assertSame(publication, runtime.authoringPublication())
            assertEquals(disk, Files.readString(store.primaryFile(fixture.worldId)))
            assertSame(source, sources.sources.getValue(fixture.worldId))
            assertSame(handle, models.handles.single())
            assertEquals(submissions, handle.submissions.size)
            assertEquals(0, handle.closeCalls)
            assertEquals(1, infrastructure.permitCloseCount)
        }
    }

    @Test
    fun `admin inspect export validate and apply include attached CRUD without validation allocations`() {
        val fixture = Fixture()
        val store = store()
        val initial = deviceModelDefinitions(fixture, "fixed", null)
        store.save(fixture.worldId, initial)
        val infrastructure = MutableTopologyService(fixture.currentTopology)
        val sources = CapturingSignalEpochSourceService()
        val models = RecordingModels()
        val manager = runtimeManager(plugin(), infrastructure.service, CapturingRoutePlanningService(), store,
            sources, modelPresentation = models)
        manager.install(fixture.worldId)
        val drafts = SimpleSignalsDefinitionDrafts(directory)
        val attached = AttachedSignalDefinition("test:attached", 1, "test:device", railModel(fixture.entry))
        val candidate = withAttachments(initial, listOf(attached))
        drafts.export("attached", candidate)
        val replies = mutableListOf<Component>()
        val command = SimpleSignalsAdminCommand(plugin(), manager, drafts, { true }, { fixture.worldId },
            { it() }, { _, message -> replies += message })
        val sender = commandSender(ConsoleCommandSender::class.java, true)
        fun run(operation: String, draft: String? = null) {
            val args = listOfNotNull(operation, "world", draft).toTypedArray()
            command.onCommand(sender, testCommand(), "simplesignals", args)
        }
        run("validate", "attached")
        assertTrue(replies.last().toString().contains("VALIDATED"))
        assertTrue(replies.last().toString().contains("create=1"))
        assertEquals(0, infrastructure.permitAcquisitionCount)
        assertTrue(models.handles.isEmpty())
        assertEquals(1, sources.atomicCommitCount)
        run("apply", "attached")
        assertTrue(replies.last().toString().contains("APPLIED"))
        run("inspect")
        assertTrue(replies.last().toString().contains("attachedSignals=1"))
        run("export", "current")
        val exported = drafts.read("current", fixture.worldId)
        assertEquals(listOf(attached), exported.attachedSignals)
        drafts.export("remove", withAttachments(exported, emptyList()))
        run("apply", "remove")
        assertTrue(replies.last().toString().contains("delete=1"))
        assertTrue(replies.last().toString().contains("APPLIED"))
        assertEquals(1, models.handles.single().closeCalls)
    }

    private fun withAttachments(base: SimpleSignalsWorldDefinitions, attached: List<AttachedSignalDefinition>,
        revision: Long = base.definitionRevision) = SimpleSignalsWorldDefinitions(base.worldId, revision, base.regions,
        base.routeTables, base.fixedBlocks, base.fixedBlockSignals, base.protectionSignals, base.routeIndicators, attached)

    @Test
    fun `queued world attachment cannot create in same UUID reload or after disable and cleanup result remains observable`() {
        val fixture = Fixture()
        val store = store()
        store.save(fixture.worldId, deviceModelDefinitions(fixture, "fixed", DeviceModelDefinition("demo_signals:home",
            ModelPlacement.Ground(ModelPose.IDENTITY))))
        val queued = mutableListOf<() -> Unit>()
        val models = RecordingModels()
        val manager = runtimeManager(plugin(), MutableTopologyService(fixture.currentTopology).service,
            CapturingRoutePlanningService(), store, signalSources(), modelPresentation = models, modelWorker = queued::add)
        manager.install(fixture.worldId)
        val old = manager.authoringRuntime(fixture.worldId)
        manager.remove(fixture.worldId)
        manager.install(fixture.worldId)
        assertNotSame(old, manager.authoringRuntime(fixture.worldId))
        queued.removeAt(0)()
        assertTrue(models.handles.isEmpty())
        queued.removeAt(0)()
        assertEquals(1, models.handles.size)
        manager.remove(fixture.worldId)
        manager.install(fixture.worldId)
        val cleanup = manager.clearLocal().toCompletableFuture()
        queued.removeAt(0)()
        assertEquals(1, models.handles.size)
        // It remains pending until the public service confirms entity cleanup.
        assertFalse(cleanup.isDone)
        models.cleanup.complete(null)
        cleanup.get(5, TimeUnit.SECONDS)
        assertEquals(1, models.closeOwnerCalls)
        assertTrue(models.handles.all { it.closed })
        assertNull(manager.authoringRuntime(fixture.worldId))
    }

    @Test
    fun `disable returns while handle setup is paused then closes the allocated binding before closeOwner`() {
        val fixture = Fixture()
        val store = store()
        store.save(fixture.worldId, deviceModelDefinitions(fixture, "fixed", DeviceModelDefinition("demo_signals:home",
            ModelPlacement.Ground(ModelPose.IDENTITY))))
        val queued = mutableListOf<() -> Unit>()
        val models = RecordingModels()
        val manager = runtimeManager(plugin(), MutableTopologyService(fixture.currentTopology).service,
            CapturingRoutePlanningService(), store, signalSources(), modelPresentation = models, modelWorker = queued::add)
        manager.install(fixture.worldId)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        models.beforeCreate = { started.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val setup = CompletableFuture.runAsync { queued.removeAt(0)() }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val cleanup = manager.clearLocal().toCompletableFuture()
        assertFalse(cleanup.isDone)
        assertEquals(0, models.closeOwnerCalls)
        release.countDown()
        setup.get(5, TimeUnit.SECONDS)
        models.cleanup.completeExceptionally(IllegalStateException("entity deletion failed"))
        assertThrows(java.util.concurrent.ExecutionException::class.java) { cleanup.get(5, TimeUnit.SECONDS) }
        assertTrue(models.handles.single().closed)
        assertTrue(models.handles.single().submissions.isEmpty())
        assertEquals(1, models.closeOwnerCalls)
    }

    @Test
    fun `world unload during pure model preparation rejects candidate before permit save and display change`() {
        val fixture = Fixture()
        val store = store()
        val infrastructure = MutableTopologyService(fixture.currentTopology)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val baseRails = fixtureRailTargets()
        val pausedRails = proxy(RailTargetService::class.java) { method, arguments ->
            check(method.name == "findCurve")
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            baseRails.findCurve(arguments!![0] as UUID, arguments[1] as UUID)
        }
        val models = RecordingModels()
        val manager = runtimeManager(plugin(), infrastructure.service, CapturingRoutePlanningService(), store,
            signalSources(), railTargets = pausedRails, modelPresentation = models)
        manager.install(fixture.worldId)
        val runtime = manager.authoringRuntime(fixture.worldId)!!
        val document = deviceModelDefinitions(fixture, "fixed", railModel(fixture.entry))
        val pending = CompletableFuture.supplyAsync {
            manager.applyDefinitionPatch(fixture.worldId, 0, definitionDraftPatch(runtime.currentDefinitions(), document))
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        manager.remove(fixture.worldId)
        manager.install(fixture.worldId)
        release.countDown()
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.WORLD_UNAVAILABLE, pending.get(5, TimeUnit.SECONDS).status)
        assertEquals(0, infrastructure.permitAcquisitionCount)
        assertFalse(Files.exists(store.primaryFile(fixture.worldId)))
        assertTrue(models.handles.isEmpty())
    }

    @Test
    fun `initial attachment prepared across definition edit catches up to the live binding`() {
        val fixture = Fixture()
        val store = store()
        val initial = deviceModelDefinitions(fixture, "fixed", railModel(fixture.entry))
        store.save(fixture.worldId, initial)
        val queued = mutableListOf<() -> Unit>()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val delegate = fixtureRailTargets()
        val rails = proxy(RailTargetService::class.java) { method, arguments ->
            check(method.name == "findCurve")
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            delegate.findCurve(arguments!![0] as UUID, arguments[1] as UUID)
        }
        val models = RecordingModels()
        val manager = runtimeManager(plugin(), MutableTopologyService(fixture.currentTopology).service,
            CapturingRoutePlanningService(), store, signalSources(), railTargets = rails, modelPresentation = models,
            modelWorker = queued::add)
        manager.install(fixture.worldId)
        val attachment = CompletableFuture.runAsync { queued.removeAt(0)() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val ground = ModelPose(50.0, 64.0, 0.0, 0.0, 0.0, 0.0, 1.0)
        val edited = deviceModelDefinitions(fixture, "fixed", DeviceModelDefinition("demo_signals:home",
            ModelPlacement.Ground(ground)), 2)
        val applied = manager.applyDefinitionPatch(fixture.worldId, 1, definitionDraftPatch(initial, edited))
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED, applied.status, applied.diagnostics.toString())
        release.countDown()
        attachment.get(5, TimeUnit.SECONDS)
        assertEquals(1, models.handles.size)
        assertEquals(ground, models.handles.single().submissions.single().transform())
        assertFalse(models.handles.single().closed)
    }

    @Test
    fun `concurrent model preparations commit one current base without duplicate handles or competing disk writes`() {
        val fixture = Fixture()
        val store = store()
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val delegate = fixtureRailTargets()
        val rails = proxy(RailTargetService::class.java) { method, arguments ->
            check(method.name == "findCurve")
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            delegate.findCurve(arguments!![0] as UUID, arguments[1] as UUID)
        }
        val infrastructure = MutableTopologyService(fixture.currentTopology)
        val models = RecordingModels()
        val manager = runtimeManager(plugin(), infrastructure.service, CapturingRoutePlanningService(), store,
            signalSources(), railTargets = rails, modelPresentation = models)
        manager.install(fixture.worldId)
        val current = manager.authoringRuntime(fixture.worldId)!!.currentDefinitions()
        val edits = listOf(1.0, 2.0).map { at -> CompletableFuture.supplyAsync {
            manager.applyDefinitionPatch(fixture.worldId, 0, definitionDraftPatch(current,
                deviceModelDefinitions(fixture, "fixed", railModel(fixture.entry, at))))
        } }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        release.countDown()
        assertEquals(setOf(SimpleSignalsDefinitionAuthoringStatus.APPLIED, SimpleSignalsDefinitionAuthoringStatus.REVISION_REJECTED),
            edits.map { it.get(5, TimeUnit.SECONDS).status }.toSet())
        assertEquals(1, infrastructure.permitAcquisitionCount)
        assertEquals(1, models.handles.size)
        assertFalse(models.handles.single().closed)
        assertEquals(manager.authoringRuntime(fixture.worldId)!!.currentDefinitions(),
            (store.load(fixture.worldId) as work.art1st.javarailways.simplesignals.persistence.SimpleSignalsWorldDefinitionLoadResult.Loaded).definitions)
    }

    @Test
    fun `broken startup packages report display unavailable while installed safety and backend-only edits remain usable`() {
        val fixture = Fixture()
        val store = store()
        val initial = deviceModelDefinitions(fixture, "fixed", railModel(fixture.entry))
        store.save(fixture.worldId, initial)
        val errors = mutableListOf<String>()
        val models = RecordingModels()
        val manager = SimpleSignalsWorldRuntimeManager(plugin(), MutableTopologyService(fixture.currentTopology).service,
            CapturingRoutePlanningService(), store, signalSources(), inspectionSources(), fixtureRailTargets(), models,
            { it() }, { context, _ -> errors += context })
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(fixture.worldId).status)
        val packages = directory.resolve("models")
        Files.createDirectories(packages.resolve("broken"))
        manager.loadModelPackages(packages)
        assertEquals(1, errors.size)
        assertTrue(errors.single().contains("rendering unavailable"))
        assertTrue(models.handles.isEmpty())
        assertNotNull(manager.fixedBlockSnapshot(fixture.worldId))
        val backend = deviceModelDefinitions(fixture, "fixed", null, 2)
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED,
            manager.applyDefinitionPatch(fixture.worldId, 1, definitionDraftPatch(initial, backend)).status)
        assertTrue(models.handles.isEmpty())
    }

    @Test
    fun `persisted package signal stays restrictive until startup catalog atomically replaces its epoch source`() {
        val fixture = Fixture()
        val block = FixedBlockDefinition("test:pack-block", 1,
            listOf(FixedBlockTraversal(fixture.entry, ViewDirection.AB, 0.0, 4.0)))
        val signal = FixedBlockSignalDefinition("test:pack-device", 1, block.blockId, 0.0,
            signalType = "automatic:block",
            placement = ModelPlacement.Ground(ModelPose(3.0, 64.0, 4.0, 0.0, 0.0, 0.0, 1.0)))
        val store = store()
        store.save(fixture.worldId, SimpleSignalsWorldDefinitions(fixture.worldId, 1, emptyList(), emptyList(),
            listOf(block), listOf(signal)))
        val sources = CapturingSignalEpochSourceService()
        val errors = mutableListOf<String>()
        val models = RecordingModels()
        val manager = runtimeManager(plugin(), MutableTopologyService(fixture.currentTopology).service,
            CapturingRoutePlanningService(), store, sources, modelPresentation = models,
            modelFailure = { context, _ -> errors += context }, loadDefaultSignalCatalog = false)
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(fixture.worldId).status)

        fun process(epochNumber: Long) {
            SignalEpochRuntime(fixture.worldId.toString(), TrainRestrictionSnapshotStore(fixture.worldId.toString())).also {
                it.replaceSources(sources.sources.getValue(fixture.worldId))
                it.process(RailwayWorldFrameSnapshot.create(epochNumber, fixture.currentTopology.revision, emptyList(),
                    ActiveRailContinuationsSnapshot.empty(fixture.currentTopology.revision)))
            }
        }
        process(1)
        val unavailable = manager.authoringRuntime(fixture.worldId)!!.sectionSignalSnapshot()!!.states.getValue(signal.signalId)
        assertInstanceOf(SectionSignalState.Unavailable::class.java, unavailable)
        assertEquals(0.0, unavailable.effectiveSpeedLimitMetersPerSecond)

        val root = writeAutomaticSignalPack()

        manager.loadSignalPacks(root)
        assertEquals(2, sources.atomicCommitCount)
        val preparedDevice = manager.authoringRuntime(fixture.worldId)!!.currentModels()!!.prepared.signals
            .getValue(signal.signalId).single()
        assertEquals("automatic:head", preparedDevice.model.id)
        assertEquals("fixed-block-signal/${signal.signalId}", models.handles.single().id)
        process(2)
        val available = manager.authoringRuntime(fixture.worldId)!!.sectionSignalSnapshot()!!.states.getValue(signal.signalId)
        assertInstanceOf(SectionSignalState.Available::class.java, available)
        assertEquals(25.0, available.effectiveSpeedLimitMetersPerSecond)
        assertTrue(errors.isEmpty(), errors.toString())
        models.cleanup.complete(null)
        manager.clearLocal().toCompletableFuture().join()
    }

    @Test
    fun `signal wand backend updates type revision placement persistence JS and model through one manager patch`() {
        val fixture = Fixture()
        val block = FixedBlockDefinition("test:wand-block", 1,
            listOf(FixedBlockTraversal(fixture.entry, ViewDirection.AB, 0.0, 4.0)))
        val originalPlacement = ModelPlacement.Ground(ModelPose(3.0, 64.0, 4.0, 0.0, 0.0, 0.0, 1.0))
        val signal = FixedBlockSignalDefinition("test:wand-device", 1, block.blockId, 2.0,
            "automatic:block", placement = originalPlacement)
        val definitions = store()
        definitions.save(fixture.worldId, SimpleSignalsWorldDefinitions(fixture.worldId, 1,
            emptyList(), emptyList(), listOf(block), listOf(signal)))
        val infrastructure = MutableTopologyService(fixture.currentTopology)
        val sources = CapturingSignalEpochSourceService()
        val models = RecordingModels()
        val manager = runtimeManager(plugin(), infrastructure.service, CapturingRoutePlanningService(), definitions,
            sources, modelPresentation = models, loadDefaultSignalCatalog = false)
        manager.loadSignalPacks(writeAutomaticSignalPack())
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(fixture.worldId).status)
        val backend = RuntimeSignalWandBackend(manager, infrastructure.service)
        assertEquals(listOf("automatic:block", "automatic:slow"),
            backend.availableSignalTypes()!!.map { it.signalType })
        val capture = backend.capture(fixture.worldId, "automatic:slow")
        val position = RailPositionSnapshot(fixture.entry, ViewDirection.AB, 0.25)

        val binding = backend.resolveEntrance(capture, position)
        val outcome = backend.place(capture, binding, position) { true }

        assertTrue(outcome.message.contains("Updated signal"), outcome.message)
        val live = manager.authoringRuntime(fixture.worldId)!!.currentDefinitions()
        val edited = live.fixedBlockSignals.single()
        assertEquals(2, live.definitionRevision)
        assertEquals(2, edited.revision)
        assertEquals("automatic:slow", edited.signalType)
        assertEquals(ModelPlacement.RailAttached(position), edited.placement)
        assertEquals(live, (definitions.load(fixture.worldId) as
            work.art1st.javarailways.simplesignals.persistence.SimpleSignalsWorldDefinitionLoadResult.Loaded).definitions)
        assertEquals(1, infrastructure.permitAcquisitionCount)
        assertEquals("automatic:slow-head", manager.authoringRuntime(fixture.worldId)!!.currentModels()!!.prepared
            .signals.getValue(signal.signalId).single().model.id)

        SignalEpochRuntime(fixture.worldId.toString(), TrainRestrictionSnapshotStore(fixture.worldId.toString())).also {
            it.replaceSources(sources.sources.getValue(fixture.worldId))
            it.process(RailwayWorldFrameSnapshot.create(1, fixture.currentTopology.revision, emptyList(),
                ActiveRailContinuationsSnapshot.empty(fixture.currentTopology.revision)))
        }
        assertEquals(5.0, manager.authoringRuntime(fixture.worldId)!!.sectionSignalSnapshot()!!.states
            .getValue(signal.signalId).effectiveSpeedLimitMetersPerSecond)
        models.cleanup.complete(null)
        manager.clearLocal().toCompletableFuture().join()
    }

    @Test
    fun `signal wand manager denial retains old type placement publication and disk atomically`() {
        val fixture = Fixture()
        val block = FixedBlockDefinition("test:wand-denied-block", 1,
            listOf(FixedBlockTraversal(fixture.entry, ViewDirection.AB, 0.0, 4.0)))
        val originalPlacement = ModelPlacement.Ground(ModelPose(3.0, 64.0, 4.0, 0.0, 0.0, 0.0, 1.0))
        val signal = FixedBlockSignalDefinition("test:wand-denied-device", 1, block.blockId, 2.0,
            "automatic:block", placement = originalPlacement)
        val definitions = store()
        val initial = SimpleSignalsWorldDefinitions(fixture.worldId, 1,
            emptyList(), emptyList(), listOf(block), listOf(signal))
        definitions.save(fixture.worldId, initial)
        val infrastructure = MutableTopologyService(fixture.currentTopology)
        val manager = runtimeManager(plugin(), infrastructure.service, CapturingRoutePlanningService(), definitions,
            CapturingSignalEpochSourceService(), loadDefaultSignalCatalog = false)
        manager.loadSignalPacks(writeAutomaticSignalPack())
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(fixture.worldId).status)
        val backend = RuntimeSignalWandBackend(manager, infrastructure.service)
        val capture = backend.capture(fixture.worldId, "automatic:slow")
        val publication = manager.authoringRuntime(fixture.worldId)!!.authoringPublication()
        val disk = Files.readString(definitions.primaryFile(fixture.worldId))
        infrastructure.nextEditDecision = TopologyEditDecision(TopologyEditStatus.CONFLICT,
            detail = "active route uses this rail")

        val position = RailPositionSnapshot(fixture.entry, ViewDirection.AB, 0.25)
        val outcome = backend.place(capture, backend.resolveEntrance(capture, position), position) { true }

        assertTrue(outcome.message.contains("active route uses this rail"), outcome.message)
        assertSame(publication, manager.authoringRuntime(fixture.worldId)!!.authoringPublication())
        assertEquals(initial, manager.authoringRuntime(fixture.worldId)!!.currentDefinitions())
        assertEquals(disk, Files.readString(definitions.primaryFile(fixture.worldId)))
        manager.clearLocal().toCompletableFuture().join()
    }

    @Test
    fun `signal wand deletion removes selected primary and direct attachments while retaining unrelated definitions`() {
        val fixture = Fixture()
        val selectedBlock = FixedBlockDefinition("test:wand-delete-block", 1,
            listOf(FixedBlockTraversal(fixture.entry, ViewDirection.AB, 0.0, 4.0)))
        val retainedBlock = FixedBlockDefinition("test:wand-retained-block", 1,
            listOf(FixedBlockTraversal(fixture.exit, ViewDirection.AB, 6.0, 10.0)))
        val selectedSignal = FixedBlockSignalDefinition("test:wand-delete-device", 1,
            selectedBlock.blockId, 12.0, "automatic:block")
        val retainedSignal = FixedBlockSignalDefinition("test:wand-retained-device", 1,
            retainedBlock.blockId, 7.0, "automatic:block")
        val placement = ModelPlacement.Ground(ModelPose(3.0, 64.0, 4.0, 0.0, 0.0, 0.0, 1.0))
        val selectedAttached = AttachedSignalDefinition("test:wand-delete-attached", 1,
            selectedSignal.signalId, DeviceModelDefinition("demo_signals:home", placement))
        val definitions = store()
        definitions.save(fixture.worldId, SimpleSignalsWorldDefinitions(fixture.worldId, 1,
            emptyList(), emptyList(), listOf(selectedBlock, retainedBlock),
            listOf(selectedSignal, retainedSignal), attachedSignals = listOf(selectedAttached)))
        val infrastructure = MutableTopologyService(fixture.currentTopology)
        val manager = runtimeManager(plugin(), infrastructure.service, CapturingRoutePlanningService(), definitions,
            CapturingSignalEpochSourceService(), loadDefaultSignalCatalog = false)
        manager.loadSignalPacks(writeAutomaticSignalPack())
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(fixture.worldId).status)
        val backend = RuntimeSignalWandBackend(manager, infrastructure.service)
        val capture = backend.capture(fixture.worldId, "automatic:block")

        val outcome = backend.delete(capture,
            RailPositionSnapshot(fixture.entry, ViewDirection.AB, 0.25)) { true }

        assertTrue(outcome.message.contains("1 attached device"), outcome.message)
        val live = manager.authoringRuntime(fixture.worldId)!!.currentDefinitions()
        assertEquals(listOf(selectedBlock, retainedBlock), live.fixedBlocks)
        assertEquals(listOf(retainedSignal), live.fixedBlockSignals)
        assertEquals(emptyList<AttachedSignalDefinition>(), live.attachedSignals)
        assertEquals(live, (definitions.load(fixture.worldId) as
            work.art1st.javarailways.simplesignals.persistence.SimpleSignalsWorldDefinitionLoadResult.Loaded).definitions)
        assertEquals(1, infrastructure.permitAcquisitionCount)
        manager.clearLocal().toCompletableFuture().join()
    }

    @Test
    fun `signal wand creates empty entrance with stable ID zero extra lead JS and model binding`() {
        val fixture = Fixture()
        val block = FixedBlockDefinition("test:wand-empty-block", 1,
            listOf(FixedBlockTraversal(fixture.entry, ViewDirection.AB, 0.0, 4.0)))
        val definitions = store()
        val initial = SimpleSignalsWorldDefinitions(fixture.worldId, 1,
            emptyList(), emptyList(), listOf(block))
        definitions.save(fixture.worldId, initial)
        val infrastructure = MutableTopologyService(fixture.currentTopology)
        val sources = CapturingSignalEpochSourceService()
        val models = RecordingModels()
        val manager = runtimeManager(plugin(), infrastructure.service, CapturingRoutePlanningService(), definitions,
            sources, modelPresentation = models, loadDefaultSignalCatalog = false)
        manager.loadSignalPacks(writeAutomaticSignalPack())
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(fixture.worldId).status)
        val backend = RuntimeSignalWandBackend(manager, infrastructure.service) { "test:generated-signal" }
        val capture = backend.capture(fixture.worldId, "automatic:block")
        val entrance = RailPositionSnapshot(fixture.entry, ViewDirection.AB, 0.25)
        val binding = backend.resolveEntrance(capture, entrance)

        val outcome = backend.place(capture, binding, entrance) { true }

        assertTrue(outcome.message.contains("Created signal test:generated-signal"), outcome.message)
        val live = manager.authoringRuntime(fixture.worldId)!!.currentDefinitions()
        val created = live.fixedBlockSignals.single()
        assertEquals("test:generated-signal", created.signalId)
        assertEquals(0L, created.revision)
        assertEquals(0.0, created.inspectionLeadTimeSeconds)
        assertEquals(block.blockId, created.controlledBlockId)
        assertEquals("automatic:block", created.signalType)
        assertEquals(ModelPlacement.RailAttached(entrance), created.placement)
        assertEquals(live, (definitions.load(fixture.worldId) as
            work.art1st.javarailways.simplesignals.persistence.SimpleSignalsWorldDefinitionLoadResult.Loaded).definitions)
        assertEquals("automatic:head", manager.authoringRuntime(fixture.worldId)!!.currentModels()!!.prepared
            .signals.getValue(created.signalId).single().model.id)

        SignalEpochRuntime(fixture.worldId.toString(), TrainRestrictionSnapshotStore(fixture.worldId.toString())).also {
            it.replaceSources(sources.sources.getValue(fixture.worldId))
            it.process(RailwayWorldFrameSnapshot.create(1, fixture.currentTopology.revision, emptyList(),
                ActiveRailContinuationsSnapshot.empty(fixture.currentTopology.revision)))
        }
        assertEquals(25.0, manager.authoringRuntime(fixture.worldId)!!.sectionSignalSnapshot()!!.states
            .getValue(created.signalId).effectiveSpeedLimitMetersPerSecond)
        assertEquals(1, infrastructure.permitAcquisitionCount)
        models.cleanup.complete(null)
        manager.clearLocal().toCompletableFuture().join()
    }

    @Test
    fun `signal wand creation rejects generated device ID collisions before mutation`() {
        val fixture = Fixture()
        val target = FixedBlockDefinition("test:wand-target-block", 1,
            listOf(FixedBlockTraversal(fixture.entry, ViewDirection.AB, 0.0, 4.0)))
        val existingBlock = FixedBlockDefinition("test:wand-existing-block", 1,
            listOf(FixedBlockTraversal(fixture.exit, ViewDirection.AB, 6.0, 10.0)))
        val existing = FixedBlockSignalDefinition("test:existing-device", 1,
            existingBlock.blockId, 8.0, "automatic:block")
        val initial = SimpleSignalsWorldDefinitions(fixture.worldId, 1,
            emptyList(), emptyList(), listOf(target, existingBlock), listOf(existing))
        val definitions = store()
        definitions.save(fixture.worldId, initial)
        val infrastructure = MutableTopologyService(fixture.currentTopology)
        val manager = runtimeManager(plugin(), infrastructure.service, CapturingRoutePlanningService(), definitions,
            CapturingSignalEpochSourceService(), loadDefaultSignalCatalog = false)
        manager.loadSignalPacks(writeAutomaticSignalPack())
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(fixture.worldId).status)
        val backend = RuntimeSignalWandBackend(manager, infrastructure.service) { existing.signalId }
        val capture = backend.capture(fixture.worldId, "automatic:block")
        val entrance = RailPositionSnapshot(fixture.entry, ViewDirection.AB, 0.25)
        val binding = backend.resolveEntrance(capture, entrance)
        val publication = manager.authoringRuntime(fixture.worldId)!!.authoringPublication()
        val retained = manager.authoringRuntime(fixture.worldId)!!.currentDefinitions()
        val disk = Files.readString(definitions.primaryFile(fixture.worldId))

        val failure = assertThrows(IllegalArgumentException::class.java) {
            backend.place(capture, binding, entrance) { true }
        }

        assertTrue(failure.message!!.contains("collides with an existing device"), failure.message)
        assertSame(publication, manager.authoringRuntime(fixture.worldId)!!.authoringPublication())
        assertEquals(retained, manager.authoringRuntime(fixture.worldId)!!.currentDefinitions())
        assertEquals(disk, Files.readString(definitions.primaryFile(fixture.worldId)))
        assertEquals(0, infrastructure.permitAcquisitionCount)
        manager.clearLocal().toCompletableFuture().join()
    }

    @Test
    fun `signal wand creation edit denial retains empty entrance disk and publication atomically`() {
        val fixture = Fixture()
        val block = FixedBlockDefinition("test:wand-denied-create-block", 1,
            listOf(FixedBlockTraversal(fixture.entry, ViewDirection.AB, 0.0, 4.0)))
        val initial = SimpleSignalsWorldDefinitions(fixture.worldId, 1,
            emptyList(), emptyList(), listOf(block))
        val definitions = store()
        definitions.save(fixture.worldId, initial)
        val infrastructure = MutableTopologyService(fixture.currentTopology)
        val manager = runtimeManager(plugin(), infrastructure.service, CapturingRoutePlanningService(), definitions,
            CapturingSignalEpochSourceService(), loadDefaultSignalCatalog = false)
        manager.loadSignalPacks(writeAutomaticSignalPack())
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(fixture.worldId).status)
        val backend = RuntimeSignalWandBackend(manager, infrastructure.service) { "test:denied-created-device" }
        val capture = backend.capture(fixture.worldId, "automatic:block")
        val entrance = RailPositionSnapshot(fixture.entry, ViewDirection.AB, 0.25)
        val binding = backend.resolveEntrance(capture, entrance)
        val publication = manager.authoringRuntime(fixture.worldId)!!.authoringPublication()
        val disk = Files.readString(definitions.primaryFile(fixture.worldId))
        infrastructure.nextEditDecision = TopologyEditDecision(TopologyEditStatus.CONFLICT,
            detail = "active route uses this rail")

        val outcome = backend.place(capture, binding, entrance) { true }

        assertTrue(outcome.message.contains("active route uses this rail"), outcome.message)
        assertSame(publication, manager.authoringRuntime(fixture.worldId)!!.authoringPublication())
        assertEquals(initial, manager.authoringRuntime(fixture.worldId)!!.currentDefinitions())
        assertEquals(disk, Files.readString(definitions.primaryFile(fixture.worldId)))
        assertEquals(1, infrastructure.permitAcquisitionCount)

        infrastructure.topology = fixture.topology(42L)
        val stale = assertThrows(IllegalArgumentException::class.java) {
            backend.place(capture, binding, entrance) { true }
        }
        assertTrue(stale.message!!.contains("expired"), stale.message)
        assertSame(publication, manager.authoringRuntime(fixture.worldId)!!.authoringPublication())
        assertEquals(initial, manager.authoringRuntime(fixture.worldId)!!.currentDefinitions())
        assertEquals(disk, Files.readString(definitions.primaryFile(fixture.worldId)))
        assertEquals(1, infrastructure.permitAcquisitionCount)
        manager.clearLocal().toCompletableFuture().join()
    }

    @Test
    fun `JS signal types publish a transitive downstream chain in one physical epoch`() {
        val rails = (1L..3L).map { UUID(10L, it) }
        val topology = topologyState(71, rails, rails.zipWithNext().map { (first, second) ->
            endpoint(first, RailEndpoint.B) to endpoint(second, RailEndpoint.A)
        })
        val worldId = UUID.randomUUID()
        val blocks = rails.mapIndexed { index, rail -> FixedBlockDefinition("test:js-block-$index", 1,
            listOf(FixedBlockTraversal(rail, ViewDirection.AB, 0.0, 5.0))) }
        val signals = blocks.mapIndexed { index, block -> FixedBlockSignalDefinition(
            "test:js-signal-$index", 1, block.blockId, 0.0, "automatic:block") }
        val definitions = store()
        definitions.save(worldId, SimpleSignalsWorldDefinitions(worldId, 1,
            emptyList(), emptyList(), blocks, signals))
        val sources = CapturingSignalEpochSourceService()
        val manager = runtimeManager(plugin(), MutableTopologyService(topology).service,
            CapturingRoutePlanningService(), definitions, sources, loadDefaultSignalCatalog = false)
        manager.loadSignalPacks(writeAutomaticSignalPack(script = """
            function evaluate(input, output) {
              let speed = 30;
              if (input.forward.length > 0 && input.forward[0].downstreamSignal !== undefined) {
                speed = input.forward[0].downstreamSignal.speedLimitMetersPerSecond - 5;
              }
              output.setSpeedLimit(speed);
              output.setModelVariant("clear");
            }
        """.trimIndent()))
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(worldId).status)

        val epoch = SignalEpochRuntime(worldId.toString(), TrainRestrictionSnapshotStore(worldId.toString()))
        epoch.replaceSources(sources.sources.getValue(worldId))
        epoch.process(RailwayWorldFrameSnapshot.create(1, topology.revision, emptyList(),
            ActiveRailContinuationsSnapshot.empty(topology.revision)))

        val states = manager.authoringRuntime(worldId)!!.sectionSignalSnapshot()!!.states
        assertEquals(listOf(20.0, 25.0, 30.0), signals.map {
            states.getValue(it.signalId).effectiveSpeedLimitMetersPerSecond
        })

        assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED,
            manager.applyDefinitionPatch(worldId, 1, SimpleSignalsDefinitionPatch(upsertFixedBlockSignals = listOf(
                signals.first().reconfigured(revision = 2, signalType = "automatic:slow")))).status)
        assertNull(manager.authoringRuntime(worldId)!!.sectionSignalSnapshot(),
            "a replacement source has no signal result and cannot expose the old permission before its baseline")
        epoch.replaceSources(sources.sources.getValue(worldId))
        epoch.process(RailwayWorldFrameSnapshot.create(2, topology.revision, emptyList(),
            ActiveRailContinuationsSnapshot.empty(topology.revision)))
        assertEquals(5.0, manager.authoringRuntime(worldId)!!.sectionSignalSnapshot()!!.states
            .getValue(signals.first().signalId).effectiveSpeedLimitMetersPerSecond)

        assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED,
            manager.applyDefinitionPatch(worldId, 2,
                SimpleSignalsDefinitionPatch(removeFixedBlockSignalIds = listOf(signals.first().signalId))).status)
        epoch.replaceSources(sources.sources.getValue(worldId))
        epoch.process(RailwayWorldFrameSnapshot.create(3, topology.revision, emptyList(),
            ActiveRailContinuationsSnapshot.empty(topology.revision)))
        assertFalse(manager.authoringRuntime(worldId)!!.sectionSignalSnapshot()!!.states
            .containsKey(signals.first().signalId), "source replacement must not retain a removed device state")
        manager.clearLocal().toCompletableFuture().join()
    }

    @Test
    fun `pending startup catalog permits edits which add no package binding and keeps them restrictive`() {
        val fixture = Fixture()
        val block = FixedBlockDefinition("test:pending-pack-block", 1,
            listOf(FixedBlockTraversal(fixture.entry, ViewDirection.AB, 0.0, 4.0)))
        val signal = FixedBlockSignalDefinition("test:pending-pack-device", 1, block.blockId, 0.0,
            signalType = "automatic:block",
            placement = ModelPlacement.Ground(ModelPose(3.0, 64.0, 4.0, 0.0, 0.0, 0.0, 1.0)))
        val definitions = store()
        definitions.save(fixture.worldId, SimpleSignalsWorldDefinitions(fixture.worldId, 1,
            emptyList(), emptyList(), listOf(block), listOf(signal)))
        val infrastructure = MutableTopologyService(fixture.currentTopology)
        val sources = CapturingSignalEpochSourceService()
        val manager = runtimeManager(plugin(), infrastructure.service, CapturingRoutePlanningService(), definitions,
            sources, loadDefaultSignalCatalog = false)
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(fixture.worldId).status)

        val editedBlock = FixedBlockDefinition(block.blockId, 2, block.traversals)
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.VALIDATED,
            manager.validateDefinitionPatch(fixture.worldId, 1,
                SimpleSignalsDefinitionPatch(upsertFixedBlocks = listOf(editedBlock))).also {
                    assertTrue(it.diagnostics.isEmpty(), it.diagnostics.toString())
                }.status)
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED,
            manager.applyDefinitionPatch(fixture.worldId, 1,
                SimpleSignalsDefinitionPatch(upsertFixedBlocks = listOf(editedBlock))).status)

        SignalEpochRuntime(fixture.worldId.toString(), TrainRestrictionSnapshotStore(fixture.worldId.toString())).also {
            it.replaceSources(sources.sources.getValue(fixture.worldId))
            it.process(RailwayWorldFrameSnapshot.create(1, fixture.currentTopology.revision, emptyList(),
                ActiveRailContinuationsSnapshot.empty(fixture.currentTopology.revision)))
        }
        val state = manager.authoringRuntime(fixture.worldId)!!.sectionSignalSnapshot()!!.states.getValue(signal.signalId)
        assertInstanceOf(SectionSignalState.Unavailable::class.java, state)
        assertEquals(0.0, state.effectiveSpeedLimitMetersPerSecond)

        val rebound = signal.reconfigured(revision = 2, signalType = "automatic:other")
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.VALIDATION_REJECTED,
            manager.validateDefinitionPatch(fixture.worldId, 2,
                SimpleSignalsDefinitionPatch(upsertFixedBlockSignals = listOf(rebound))).status)
        assertEquals(1, infrastructure.permitAcquisitionCount)
        manager.clearLocal().toCompletableFuture().join()
    }

    @Test
    fun `catalog becoming ready during an edit rejects the stale prepared binding without overwriting state`() {
        val fixture = Fixture()
        val block = FixedBlockDefinition("test:catalog-race-block", 1,
            listOf(FixedBlockTraversal(fixture.entry, ViewDirection.AB, 0.0, 4.0)))
        val originalPlacement = ModelPlacement.Ground(
            ModelPose(3.0, 64.0, 4.0, 0.0, 0.0, 0.0, 1.0))
        val signal = FixedBlockSignalDefinition("test:catalog-race-device", 1, block.blockId, 0.0,
            signalType = "automatic:block", placement = originalPlacement)
        val definitions = store()
        definitions.save(fixture.worldId, SimpleSignalsWorldDefinitions(fixture.worldId, 1,
            emptyList(), emptyList(), listOf(block), listOf(signal)))
        val originalDisk = Files.readString(definitions.primaryFile(fixture.worldId))
        val infrastructure = MutableTopologyService(fixture.currentTopology)
        val sources = CapturingSignalEpochSourceService()
        val models = RecordingModels()
        val manager = runtimeManager(plugin(), infrastructure.service, CapturingRoutePlanningService(), definitions,
            sources, modelPresentation = models, loadDefaultSignalCatalog = false)
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(fixture.worldId).status)
        val originalPublication = manager.authoringRuntime(fixture.worldId)!!.authoringPublication()
        sources.beforeCommitAction = {
            sources.beforeCommitAction = {}
            manager.loadSignalPacks(writeAutomaticSignalPack())
        }

        val result = manager.applyDefinitionPatch(fixture.worldId, 1,
            SimpleSignalsDefinitionPatch(upsertFixedBlockSignals = listOf(signal.reconfigured(
                revision = 2,
                placement = ModelPlacement.Ground(ModelPose(9.0, 64.0, 2.0, 0.0, 0.0, 0.0, 1.0))))))

        assertEquals(SimpleSignalsDefinitionAuthoringStatus.WORLD_UNAVAILABLE, result.status, result.diagnostics.toString())
        assertTrue(result.diagnostics.single().contains("catalog changed"))
        val runtime = manager.authoringRuntime(fixture.worldId)!!
        assertSame(originalPublication, runtime.authoringPublication())
        assertEquals(originalPlacement, runtime.currentDefinitions().fixedBlockSignals.single().placement)
        assertEquals(originalDisk, Files.readString(definitions.primaryFile(fixture.worldId)))
        assertEquals("automatic:head", runtime.currentModels()!!.prepared.signals
            .getValue(signal.signalId).single().model.id)
        assertEquals(1, infrastructure.permitAcquisitionCount)
        models.cleanup.complete(null)
        manager.clearLocal().toCompletableFuture().join()
    }

    @Test
    fun `package model validates the JS variant selected from the same catalog signal`() {
        val fixture = Fixture()
        val block = FixedBlockDefinition("test:variant-block", 1,
            listOf(FixedBlockTraversal(fixture.entry, ViewDirection.AB, 0.0, 4.0)))
        val signal = FixedBlockSignalDefinition("test:variant-device", 1, block.blockId, 0.0,
            signalType = "automatic:block",
            placement = ModelPlacement.Ground(ModelPose(3.0, 64.0, 4.0, 0.0, 0.0, 0.0, 1.0)))
        val definitions = store()
        definitions.save(fixture.worldId, SimpleSignalsWorldDefinitions(fixture.worldId, 1,
            emptyList(), emptyList(), listOf(block), listOf(signal)))
        val sources = CapturingSignalEpochSourceService()
        val models = RecordingModels()
        val manager = runtimeManager(plugin(), MutableTopologyService(fixture.currentTopology).service,
            CapturingRoutePlanningService(), definitions, sources, modelPresentation = models,
            loadDefaultSignalCatalog = false)
        manager.loadSignalPacks(writeAutomaticSignalPack("not-declared"))
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(fixture.worldId).status)

        SignalEpochRuntime(fixture.worldId.toString(), TrainRestrictionSnapshotStore(fixture.worldId.toString())).also {
            it.replaceSources(sources.sources.getValue(fixture.worldId))
            it.process(RailwayWorldFrameSnapshot.create(1, fixture.currentTopology.revision, emptyList(),
                ActiveRailContinuationsSnapshot.empty(fixture.currentTopology.revision)))
        }

        val state = manager.authoringRuntime(fixture.worldId)!!.sectionSignalSnapshot()!!.states.getValue(signal.signalId)
        assertInstanceOf(SectionSignalState.Unavailable::class.java, state)
        assertEquals(0.0, state.effectiveSpeedLimitMetersPerSecond)
        assertTrue((state as SectionSignalState.Unavailable).reason.contains("undeclared model variant"))
        assertEquals("automatic:head", manager.authoringRuntime(fixture.worldId)!!.currentModels()!!.prepared
            .signals.getValue(signal.signalId).single().model.id)
        assertEquals(1, models.handles.size)
        models.cleanup.complete(null)
        manager.clearLocal().toCompletableFuture().join()
    }

    @Test
    fun `failed package rebind preserves disk publication sources and existing model handle`() {
        val fixture = Fixture()
        val block = FixedBlockDefinition("test:rebind-block", 1,
            listOf(FixedBlockTraversal(fixture.entry, ViewDirection.AB, 0.0, 4.0)))
        val signal = FixedBlockSignalDefinition("test:rebind-device", 1, block.blockId, 0.0,
            signalType = "automatic:block",
            placement = ModelPlacement.Ground(ModelPose(3.0, 64.0, 4.0, 0.0, 0.0, 0.0, 1.0)))
        val definitions = store()
        definitions.save(fixture.worldId, SimpleSignalsWorldDefinitions(fixture.worldId, 1,
            emptyList(), emptyList(), listOf(block), listOf(signal)))
        val infrastructure = MutableTopologyService(fixture.currentTopology)
        val sources = CapturingSignalEpochSourceService()
        val models = RecordingModels()
        val manager = runtimeManager(plugin(), infrastructure.service, CapturingRoutePlanningService(), definitions,
            sources, modelPresentation = models, loadDefaultSignalCatalog = false)
        manager.loadSignalPacks(writeAutomaticSignalPack())
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(fixture.worldId).status)
        val runtime = manager.authoringRuntime(fixture.worldId)!!
        val publication = runtime.authoringPublication()
        val sourceSet = sources.sources.getValue(fixture.worldId)
        val disk = Files.readString(definitions.primaryFile(fixture.worldId))
        val handle = models.handles.single()

        val rejected = manager.applyDefinitionPatch(fixture.worldId, 1,
            SimpleSignalsDefinitionPatch(upsertFixedBlockSignals = listOf(
                signal.reconfigured(revision = 2, signalType = "automatic:missing"))))

        assertEquals(SimpleSignalsDefinitionAuthoringStatus.VALIDATION_REJECTED, rejected.status)
        assertSame(publication, runtime.authoringPublication())
        assertSame(sourceSet, sources.sources.getValue(fixture.worldId))
        assertEquals(disk, Files.readString(definitions.primaryFile(fixture.worldId)))
        assertFalse(handle.closed)
        assertEquals(0, infrastructure.permitAcquisitionCount)
        models.cleanup.complete(null)
        manager.clearLocal().toCompletableFuture().join()
    }

    @Test
    fun `stale package model preparation cannot overwrite a newer definition binding`() {
        val fixture = Fixture()
        val block = FixedBlockDefinition("test:stale-pack-block", 1,
            listOf(FixedBlockTraversal(fixture.entry, ViewDirection.AB, 0.0, 4.0)))
        val railPlacement = ModelPlacement.RailAttached(
            RailPositionSnapshot(fixture.entry, ViewDirection.AB, 2.0))
        val signal = FixedBlockSignalDefinition("test:stale-pack-device", 1, block.blockId, 0.0,
            signalType = "automatic:block", placement = railPlacement)
        val definitions = store()
        definitions.save(fixture.worldId, SimpleSignalsWorldDefinitions(fixture.worldId, 1,
            emptyList(), emptyList(), listOf(block), listOf(signal)))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val delegate = fixtureRailTargets()
        val pausedRails = proxy(RailTargetService::class.java) { method, arguments ->
            check(method.name == "findCurve")
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            delegate.findCurve(arguments!![0] as UUID, arguments[1] as UUID)
        }
        val models = RecordingModels()
        val manager = runtimeManager(plugin(), MutableTopologyService(fixture.currentTopology).service,
            CapturingRoutePlanningService(), definitions, CapturingSignalEpochSourceService(),
            railTargets = pausedRails, modelPresentation = models, loadDefaultSignalCatalog = false)
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(fixture.worldId).status)
        val loading = CompletableFuture.runAsync { manager.loadSignalPacks(writeAutomaticSignalPack()) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val ground = ModelPlacement.Ground(ModelPose(9.0, 64.0, 2.0, 0.0, 0.0, 0.0, 1.0))
        val applied = try {
            manager.applyDefinitionPatch(fixture.worldId, 1,
                SimpleSignalsDefinitionPatch(upsertFixedBlockSignals = listOf(
                    signal.reconfigured(revision = 2, placement = ground))))
        } finally {
            release.countDown()
        }
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED, applied.status, applied.diagnostics.toString())
        loading.get(5, TimeUnit.SECONDS)

        val live = manager.authoringRuntime(fixture.worldId)!!.currentModels()!!.prepared.signals
            .getValue(signal.signalId).single()
        assertEquals(ground, live.configuration.placement)
        assertEquals(1, models.handles.count { !it.closed })
        assertEquals(ground.pose, models.handles.single { !it.closed }.submissions.last().transform())
        models.cleanup.complete(null)
        manager.clearLocal().toCompletableFuture().join()
    }

    @Test
    fun `unknown package signal keeps the installed exact publication restrictive`() {
        val fixture = Fixture()
        val block = FixedBlockDefinition("test:unknown-pack-block", 1,
            listOf(FixedBlockTraversal(fixture.entry, ViewDirection.AB, 0.0, 4.0)))
        val signal = FixedBlockSignalDefinition("test:unknown-pack-device", 1, block.blockId, 0.0,
            signalType = "automatic:missing",
            placement = ModelPlacement.Ground(ModelPose(3.0, 64.0, 4.0, 0.0, 0.0, 0.0, 1.0)))
        val definitions = store()
        definitions.save(fixture.worldId, SimpleSignalsWorldDefinitions(fixture.worldId, 1,
            emptyList(), emptyList(), listOf(block), listOf(signal)))
        val sources = CapturingSignalEpochSourceService()
        val errors = mutableListOf<String>()
        val models = RecordingModels()
        val manager = runtimeManager(plugin(), MutableTopologyService(fixture.currentTopology).service,
            CapturingRoutePlanningService(), definitions, sources, modelPresentation = models,
            modelFailure = { context, _ -> errors += context }, loadDefaultSignalCatalog = false)
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(fixture.worldId).status)
        val publication = manager.authoringRuntime(fixture.worldId)!!.authoringPublication()

        manager.loadSignalPacks(writeAutomaticSignalPack())

        assertSame(publication, manager.authoringRuntime(fixture.worldId)!!.authoringPublication())
        assertEquals(1, sources.atomicCommitCount, "failed package compilation must not replace the restrictive source")
        assertTrue(errors.single().contains("bound devices remain restrictive"))
        assertTrue(models.handles.isEmpty(), "unknown package signal must not create a presentation handle")
        SignalEpochRuntime(fixture.worldId.toString(), TrainRestrictionSnapshotStore(fixture.worldId.toString())).also {
            it.replaceSources(sources.sources.getValue(fixture.worldId))
            it.process(RailwayWorldFrameSnapshot.create(1, fixture.currentTopology.revision, emptyList(),
                ActiveRailContinuationsSnapshot.empty(fixture.currentTopology.revision)))
        }
        assertInstanceOf(SectionSignalState.Unavailable::class.java,
            manager.authoringRuntime(fixture.worldId)!!.sectionSignalSnapshot()!!.states.getValue(signal.signalId))
        models.cleanup.complete(null)
        manager.clearLocal().toCompletableFuture().join()
    }

    @Test
    fun `strict full JSON draft creates updates and deletes all six definition kinds through one manager transaction`() {
        val fixture = Fixture()
        val infrastructure = MutableTopologyService(fixture.currentTopology)
        val definitions = store()
        val sources = CapturingSignalEpochSourceService()
        val manager = runtimeManager(plugin(), infrastructure.service, CapturingRoutePlanningService(), definitions, sources)
        manager.install(fixture.worldId)
        val runtime = manager.authoringRuntime(fixture.worldId)!!
        val drafts = SimpleSignalsDefinitionDrafts(directory)
        fun document(baseline: Long, revision: Long): SimpleSignalsWorldDefinitions {
            val region = ProtectionRegionDefinition(fixture.region.regionId, revision, fixture.region.boundaries)
            return SimpleSignalsWorldDefinitions(fixture.worldId, baseline, listOf(region),
                listOf(fixture.table(revision, fixture.upperRoute(), regionRevision = revision)),
                listOf(FixedBlockDefinition("test:block", revision, listOf(FixedBlockTraversal(fixture.entry, ViewDirection.AB, 0.0, 4.0)))),
                listOf(FixedBlockSignalDefinition("test:fixed", revision, "test:block", 0.0, "automatic:block")),
                listOf(ProtectionSignalDefinition("test:protection", revision, ProtectionSignalKind.FIXED_ROUTE,
                    "test:upper", 0.0, "automatic:block")),
                listOf(RouteIndicatorDefinition("test:indicator", revision, "test:entry",
                    "none", mapOf("test:upper" to "upper"))))
        }
        listOf(document(0, 1), document(1, 2), SimpleSignalsWorldDefinitions(fixture.worldId, 2, emptyList(), emptyList())).forEachIndexed { index, document ->
            drafts.export("step-$index", document)
            val decoded = drafts.read("step-$index", fixture.worldId)
            val result = manager.applyDefinitionPatch(fixture.worldId, decoded.definitionRevision,
                definitionDraftPatch(runtime.currentDefinitions(), decoded), runtime)
            assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED, result.status, result.diagnostics.toString())
            assertEquals(index.toLong() + 1, runtime.currentDefinitions().definitionRevision)
            assertEquals(result.definitions, (definitions.load(fixture.worldId) as work.art1st.javarailways.simplesignals.persistence.SimpleSignalsWorldDefinitionLoadResult.Loaded).definitions)
        }
        assertEquals(3, infrastructure.permitAcquisitionCount)
        assertEquals(3, infrastructure.permitCloseCount)
        assertTrue(runtime.currentDefinitions().fixedBlocks.isEmpty())
        assertTrue(sources.sources.getValue(fixture.worldId).isEmpty())
    }

    @Test
    fun `draft validation shares candidate checks without permit disk or source changes`() {
        val fixture = Fixture()
        val infrastructure = MutableTopologyService(fixture.currentTopology)
        val definitions = store()
        val sources = CapturingSignalEpochSourceService()
        val planning = CapturingRoutePlanningService()
        val manager = runtimeManager(plugin(), infrastructure.service, planning, definitions, sources)
        manager.install(fixture.worldId)
        val runtime = manager.authoringRuntime(fixture.worldId)!!
        val publication = runtime.authoringPublication()
        val oldSources = sources.sources.getValue(fixture.worldId)
        val target = SimpleSignalsWorldDefinitions(fixture.worldId, 0, listOf(fixture.region),
            listOf(fixture.table(1, fixture.upperRoute())))
        val patch = definitionDraftPatch(publication.definitions, target)
        val valid = manager.validateDefinitionPatch(fixture.worldId, 0, patch, runtime)
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.VALIDATED, valid.status, valid.diagnostics.toString())
        assertTrue(valid.affectedRailIds.contains(fixture.entry))
        assertEquals(1, valid.definitions!!.definitionRevision)
        val invalid = SimpleSignalsDefinitionPatch(upsertRouteTables = listOf(fixture.table(1, fixture.upperRoute())))
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.VALIDATION_REJECTED,
            manager.validateDefinitionPatch(fixture.worldId, 0, invalid, runtime).status)
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.VALIDATION_REJECTED,
            manager.applyDefinitionPatch(fixture.worldId, 0, invalid, runtime).status)
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.PATCH_REJECTED,
            manager.validateDefinitionPatch(fixture.worldId, 0, definitionDraftPatch(publication.definitions, publication.definitions)).status)
        assertEquals(0, infrastructure.permitAcquisitionCount)
        assertFalse(Files.exists(definitions.primaryFile(fixture.worldId)))
        assertSame(publication, runtime.authoringPublication())
        assertSame(oldSources, sources.sources.getValue(fixture.worldId))
        assertEquals(1, planning.replacementCount)

        val applied = manager.applyDefinitionPatch(fixture.worldId, 0, patch, runtime)
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED, applied.status)
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.REVISION_REJECTED,
            manager.applyDefinitionPatch(fixture.worldId, target.definitionRevision, patch, runtime).status)
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.REVISION_REJECTED,
            manager.validateDefinitionPatch(fixture.worldId, target.definitionRevision, patch, runtime).status)
        val captured = runtime
        manager.remove(fixture.worldId)
        manager.install(fixture.worldId)
        assertNotSame(captured, manager.authoringRuntime(fixture.worldId))
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.WORLD_UNAVAILABLE,
            manager.applyDefinitionPatch(fixture.worldId, 1, patch, captured).status)
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.WORLD_UNAVAILABLE,
            manager.validateDefinitionPatch(fixture.worldId, 1, patch, captured).status)
    }

    @Test
    fun `authoring snapshots do not wait on worker publication and disable prevents final commit`() {
        val fixture = Fixture()
        var enabled = true
        val owner = proxy(Plugin::class.java) { method, _ -> when (method.name) {
            "isEnabled" -> enabled
            else -> throw UnsupportedOperationException(method.name)
        } }
        val infrastructure = MutableTopologyService(fixture.currentTopology)
        val sources = CapturingSignalEpochSourceService()
        val manager = runtimeManager(owner, infrastructure.service, CapturingRoutePlanningService(), store(), sources)
        manager.install(fixture.worldId)
        val runtime = manager.authoringRuntime(fixture.worldId)!!
        val before = runtime.authoringPublication()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        sources.beforeCommitAction = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val patch = SimpleSignalsDefinitionPatch(upsertRegions = listOf(fixture.region))
        val edit = CompletableFuture.supplyAsync { manager.applyDefinitionPatch(fixture.worldId, 0, patch, runtime) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val lookup = CompletableFuture.supplyAsync { manager.authoringRuntime(fixture.worldId)!!.authoringPublication() }
            assertSame(before, lookup.get(1, TimeUnit.SECONDS))
            enabled = false
        } finally { release.countDown() }
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.WORLD_UNAVAILABLE, edit.get(5, TimeUnit.SECONDS).status)
        assertSame(before, runtime.authoringPublication())
        assertFalse(Files.exists(store().primaryFile(fixture.worldId)))
        assertEquals(1, infrastructure.permitCloseCount)
    }

    @Test
    fun `commands parse permissions explicit world drafts and discard queued disabled or reloaded edits`() {
        val fixture = Fixture()
        val definitions = store()
        val infrastructure = MutableTopologyService(fixture.currentTopology)
        val manager = runtimeManager(plugin(), infrastructure.service, CapturingRoutePlanningService(), definitions, signalSources())
        manager.install(fixture.worldId)
        val drafts = SimpleSignalsDefinitionDrafts(directory)
        val draft = SimpleSignalsWorldDefinitions(fixture.worldId, 0, listOf(fixture.region), emptyList())
        drafts.export("edit", draft)
        var enabled = true
        val tasks = mutableListOf<() -> Unit>()
        val replies = mutableListOf<Pair<CommandSender, Component>>()
        val command = SimpleSignalsAdminCommand(plugin(), manager, drafts, { enabled },
            { if (it == "world" || it == fixture.worldId.toString()) fixture.worldId else null },
            { tasks += it }, { sender, message -> replies += sender to message })
        val console = commandSender(ConsoleCommandSender::class.java, true)
        val player = commandSender(Player::class.java, true)
        val blockMessages = mutableListOf<Component>()
        val block = proxy(BlockCommandSender::class.java) { method, args ->
            check(method.name == "sendMessage"); blockMessages += args!!.last() as Component; Unit
        }
        command.onCommand(block, testCommand(), "simplesignals", arrayOf("apply", "world", "edit"))
        assertTrue(blockMessages.single().toString().contains("player or console"))
        assertTrue(tasks.isEmpty())
        assertTrue(replies.isEmpty())
        command.onCommand(commandSender(Player::class.java, false), testCommand(), "simplesignals", arrayOf("apply", "world", "edit"))
        assertTrue(replies.last().second.toString().contains("missing permission"))
        command.onCommand(player, testCommand(), "simplesignals", arrayOf("inspect"))
        command.onCommand(player, testCommand(), "simplesignals", arrayOf("apply", "world", "../bad"))
        command.onCommand(console, testCommand(), "simplesignals", arrayOf("inspect", "missing"))
        assertTrue(tasks.isEmpty())
        listOf(console, player).forEach { sender ->
            command.onCommand(sender, testCommand(), "simplesignals", arrayOf("inspect", fixture.worldId.toString()))
            tasks.removeAt(0).invoke()
            assertSame(sender, replies.last().first)
            assertTrue(replies.last().second.toString().contains("definitionRevision=0"))
        }
        command.onCommand(console, testCommand(), "simplesignals", arrayOf("validate", "world", "edit"))
        tasks.removeAt(0).invoke()
        assertTrue(replies.last().second.toString().contains("VALIDATED"))
        assertTrue(replies.last().second.toString().contains("create=1"))
        assertEquals(0, infrastructure.permitAcquisitionCount)
        assertFalse(Files.exists(definitions.primaryFile(fixture.worldId)))
        command.onCommand(console, testCommand(), "simplesignals", arrayOf("apply", "world", "edit"))
        enabled = false
        val count = replies.size
        tasks.removeAt(0).invoke()
        assertEquals(count, replies.size)
        assertEquals(0, infrastructure.permitAcquisitionCount)
        enabled = true
        command.onCommand(console, testCommand(), "simplesignals", arrayOf("apply", "world", "edit"))
        manager.remove(fixture.worldId)
        manager.install(fixture.worldId)
        tasks.removeAt(0).invoke()
        assertTrue(replies.last().second.toString().contains("WORLD_UNAVAILABLE"))
        assertEquals(0, infrastructure.permitAcquisitionCount)
        command.onCommand(console, testCommand(), "simplesignals", arrayOf("apply", "world", "edit"))
        tasks.removeAt(0).invoke()
        assertTrue(replies.last().second.toString().contains("APPLIED"))
        command.onCommand(console, testCommand(), "simplesignals", arrayOf("apply", "world", "edit"))
        tasks.removeAt(0).invoke()
        assertTrue(replies.last().second.toString().contains("REVISION_REJECTED"))
        assertEquals(1, infrastructure.permitAcquisitionCount)
        assertEquals(draft, drafts.read("edit", fixture.worldId))
    }

    @Test
    fun `native Paper worker and player or console reply schedulers preserve ownership and retired replies drop`() {
        val fixture = Fixture()
        val manager = runtimeManager(plugin(), MutableTopologyService(fixture.currentTopology).service,
            CapturingRoutePlanningService(), store(), signalSources())
        manager.install(fixture.worldId)
        val async = mutableListOf<Consumer<ScheduledTask>>()
        val global = mutableListOf<Consumer<ScheduledTask>>()
        val entity = mutableListOf<Consumer<ScheduledTask>>()
        val messages = mutableListOf<Component>()
        var online = true
        var enabled = true
        val asyncScheduler = proxy(AsyncScheduler::class.java) { method, args ->
            check(method.name == "runNow"); @Suppress("UNCHECKED_CAST") async.add(args!![1] as Consumer<ScheduledTask>); null
        }
        val globalScheduler = proxy(GlobalRegionScheduler::class.java) { method, args ->
            check(method.name == "run"); @Suppress("UNCHECKED_CAST") global.add(args!![1] as Consumer<ScheduledTask>); null
        }
        val entityScheduler = proxy(EntityScheduler::class.java) { method, args ->
            check(method.name == "run"); @Suppress("UNCHECKED_CAST") entity.add(args!![1] as Consumer<ScheduledTask>); null
        }
        val world = proxy(World::class.java) { method, _ -> check(method.name == "getUID"); fixture.worldId }
        val server = proxy(Server::class.java) { method, args -> when (method.name) {
            "getAsyncScheduler" -> asyncScheduler
            "getGlobalRegionScheduler" -> globalScheduler
            "getWorld" -> if (args!![0] == "world" || args[0] == fixture.worldId) world else null
            else -> throw UnsupportedOperationException(method.name)
        } }
        val owner = proxy(Plugin::class.java) { method, _ -> when (method.name) {
            "isEnabled" -> enabled
            "getServer" -> server
            else -> throw UnsupportedOperationException(method.name)
        } }
        val player = proxy(Player::class.java) { method, args -> when (method.name) {
            "hasPermission" -> true
            "getScheduler" -> entityScheduler
            "isOnline" -> online
            "sendMessage" -> { messages += args!!.last() as Component; Unit }
            else -> throw UnsupportedOperationException(method.name)
        } }
        val console = proxy(ConsoleCommandSender::class.java) { method, args -> when (method.name) {
            "hasPermission" -> true
            "sendMessage" -> { messages += args!!.last() as Component; Unit }
            else -> throw UnsupportedOperationException(method.name)
        } }
        val task = proxy(ScheduledTask::class.java) { _, _ -> null }
        val command = SimpleSignalsAdminCommand(owner, manager, SimpleSignalsDefinitionDrafts(directory))
        command.onCommand(player, testCommand(), "simplesignals", arrayOf("export", "world", "native"))
        assertFalse(Files.exists(directory.resolve("drafts/native.json")))
        assertTrue(entity.isEmpty())
        async.removeAt(0).accept(task)
        assertTrue(Files.exists(directory.resolve("drafts/native.json")))
        assertTrue(messages.isEmpty())
        entity.removeAt(0).accept(task)
        assertTrue(messages.single().toString().contains("Exported"))
        command.onCommand(console, testCommand(), "simplesignals", arrayOf("inspect", fixture.worldId.toString()))
        async.removeAt(0).accept(task)
        assertEquals(1, messages.size)
        global.removeAt(0).accept(task)
        assertEquals(2, messages.size)
        command.onCommand(player, testCommand(), "simplesignals", arrayOf("inspect", "world"))
        async.removeAt(0).accept(task)
        online = false
        entity.removeAt(0).accept(task)
        assertEquals(2, messages.size)
        command.onCommand(console, testCommand(), "simplesignals", arrayOf("apply", "world", "native"))
        enabled = false
        async.removeAt(0).accept(task)
        assertEquals(0L, manager.authoringRuntime(fixture.worldId)!!.currentDefinitions().definitionRevision)
        assertTrue(global.isEmpty())
    }

    @Test
    fun `blocker command diagnostics include original kind owner revision and overlap`() {
        val rail = UUID(0, 1)
        val node = work.art1st.javarailways.basic.api.infrastructure.StableTopologyNodeKey(
            listOf(endpoint(rail, RailEndpoint.A)))
        val resource = work.art1st.javarailways.basic.api.infrastructure.StableInfrastructureResource(
            "test:resource", "test:signal")
        val decision = TopologyEditDecision(TopologyEditStatus.CONFLICT, listOf(TopologyEditBlocker(
            TopologyEditBlockerKind.INFRASTRUCTURE_DEPENDENCY, InfrastructureOwner("test:timetable", 22),
            TopologyEditOverlap(setOf(rail), setOf(node), setOf(resource)))), "rail is protected")
        val text = SimpleSignalsAdminCommand.formatResult(SimpleSignalsDefinitionAuthoringResult(
            SimpleSignalsDefinitionAuthoringStatus.EDIT_BLOCKED, editDecision = decision, diagnostics = listOf(decision.detail)))
        assertTrue(text.contains("INFRASTRUCTURE_DEPENDENCY"))
        assertTrue(text.contains("test:timetable"))
        assertTrue(text.contains("revision=22"))
        assertTrue(text.contains(rail.toString()))
        assertTrue(text.contains(node.toString()))
        assertTrue(text.contains(resource.typeId))
        assertTrue(text.contains(resource.resourceId))
    }

    @Test
    fun `large authoring results retain full typed impact but bound command reply previews`() {
        val rails = (1L..1000L).map { UUID(0, it) }.toSet()
        val validated = SimpleSignalsDefinitionAuthoringResult(
            SimpleSignalsDefinitionAuthoringStatus.VALIDATED, affectedRailIds = rails)
        val validationText = SimpleSignalsAdminCommand.formatResult(validated)
        assertTrue(validationText.contains("count=1000"))
        assertTrue(validationText.contains("992 omitted"))
        assertTrue(validationText.length < 1000)
        assertEquals(1000, validated.affectedRailIds.size)

        val blockers = (1L..20L).map { revision -> TopologyEditBlocker(
            TopologyEditBlockerKind.INFRASTRUCTURE_DEPENDENCY, InfrastructureOwner("test:owner", revision),
            TopologyEditOverlap(rails, emptySet(), emptySet())) }
        val rejected = SimpleSignalsDefinitionAuthoringResult(
            SimpleSignalsDefinitionAuthoringStatus.EDIT_BLOCKED,
            editDecision = TopologyEditDecision(TopologyEditStatus.CONFLICT, blockers),
            diagnostics = List(20) { "x".repeat(1000) })
        val rejectionText = SimpleSignalsAdminCommand.formatResult(rejected)
        assertTrue(rejectionText.contains("12 blockers omitted"))
        assertTrue(rejectionText.contains("12 diagnostics omitted"))
        assertTrue(rejectionText.length < 8000)
        assertEquals(20, rejected.editDecision!!.blockers.size)
        assertEquals(1000, rejected.editDecision.blockers.first().overlap.railIds.size)
    }

    private fun <T : CommandSender> commandSender(type: Class<T>, permitted: Boolean): T = proxy(type) { method, _ ->
        check(method.name == "hasPermission"); permitted
    }

    private fun testCommand(): Command = object : Command("simplesignals") {
        override fun execute(sender: CommandSender, label: String, args: Array<out String>) = true
    }

    @Test
    fun `disjoint local edits succeed with real applied dependency and live claim elsewhere`() {
        val fixture = Fixture()
        val localRail = UUID(0L, 99L)
        val topology = fixture.topology(41L, listOf(localRail))
        var currentTopology = topology
        val registry = editRegistry { currentTopology }
        val infrastructure = MutableTopologyService(topology, registry)
        val definitions = store()
        val local = FixedBlockDefinition("test:local", 1, listOf(FixedBlockTraversal(localRail, ViewDirection.AB, 0.0, 4.0)))
        fun localSignal(revision: Long, @Suppress("UNUSED_PARAMETER") speed: Double) =
            FixedBlockSignalDefinition("test:local-signal", revision, local.blockId, 0.0, "automatic:block")
        definitions.save(fixture.worldId, SimpleSignalsWorldDefinitions(fixture.worldId, 1,
            listOf(fixture.region), listOf(fixture.table(1, fixture.upperRoute())), listOf(local), listOf(localSignal(1, 12.0))))
        val sources = CapturingSignalEpochSourceService()
        val manager = runtimeManager(plugin(), infrastructure.service, CapturingRoutePlanningService(), definitions, sources)
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(fixture.worldId).status)
        protect(registry, topology, fixture.upperIn, TopologyEditBlockerKind.INFRASTRUCTURE_DEPENDENCY)
        protect(registry, topology, fixture.lowerIn, TopologyEditBlockerKind.LIVE_RUNTIME_CLAIM)

        val edited = FixedBlockDefinition(local.blockId, 2, listOf(FixedBlockTraversal(localRail, ViewDirection.AB, 1.0, 5.0)))
        val applied = manager.applyDefinitionPatch(fixture.worldId, 1, SimpleSignalsDefinitionPatch(upsertFixedBlocks = listOf(edited)))
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED, applied.status, applied.diagnostics.toString())
        assertEquals(setOf(localRail), infrastructure.lastEditRequest!!.impact.railIds)
        val primary = Files.readString(definitions.primaryFile(fixture.worldId))
        val retained = sources.sources.getValue(fixture.worldId)
        val tableEdit = manager.applyDefinitionPatch(fixture.worldId, 2,
            SimpleSignalsDefinitionPatch(upsertRouteTables = listOf(fixture.table(2, fixture.lowerRoute()))))
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.EDIT_BLOCKED, tableEdit.status)
        assertEquals(setOf(TopologyEditBlockerKind.INFRASTRUCTURE_DEPENDENCY, TopologyEditBlockerKind.LIVE_RUNTIME_CLAIM),
            tableEdit.editDecision!!.blockers.map { it.kind }.toSet())
        assertEquals(primary, Files.readString(definitions.primaryFile(fixture.worldId)))
        assertSame(retained, sources.sources.getValue(fixture.worldId))
        assertEquals(1, infrastructure.permitCloseCount)
        val signalEdited = manager.applyDefinitionPatch(fixture.worldId, 2,
            SimpleSignalsDefinitionPatch(upsertFixedBlockSignals = listOf(localSignal(2, 6.0))))
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED, signalEdited.status)
        assertEquals(setOf(localRail), infrastructure.lastEditRequest!!.impact.railIds)
        // A fresh exact root with equivalent policy geometry (e.g. unrelated track construction)
        // must not bring the operated region back into this local edit's permit.
        currentTopology = fixture.topology(42L, listOf(localRail, UUID(0, 100)))
        infrastructure.topology = currentTopology
        val afterTopology = manager.applyDefinitionPatch(fixture.worldId, 3,
            SimpleSignalsDefinitionPatch(removeFixedBlockIds = listOf(local.blockId),
                removeFixedBlockSignalIds = listOf("test:local-signal")))
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED, afterTopology.status, afterTopology.diagnostics.toString())
        assertEquals(setOf(localRail), infrastructure.lastEditRequest!!.impact.railIds)
    }

    @Test
    fun `downstream edit and removal protect transitive upstream rules and connector geometry`() {
        val rails = (1L..4L).map { UUID(0L, it) }
        val topology = topologyState(41L, rails, rails.zipWithNext().map { (a, b) ->
            endpoint(a, RailEndpoint.B) to endpoint(b, RailEndpoint.A) })
        val worldId = UUID.randomUUID()
        val blocks = listOf(0, 2, 3).map { index -> FixedBlockDefinition("test:block-$index", 1,
            listOf(FixedBlockTraversal(rails[index], ViewDirection.AB, 0.0, 5.0))) }
        fun signal(index: Int, revision: Long = 1, @Suppress("UNUSED_PARAMETER") downstream: Int? = null) =
            FixedBlockSignalDefinition("test:signal-$index", revision, "test:block-$index", 0.0,
                "automatic:block")
        val definitions = store()
        definitions.save(worldId, SimpleSignalsWorldDefinitions(worldId, 1, emptyList(), emptyList(), blocks,
            listOf(signal(0, downstream = 2), signal(2, downstream = 3), signal(3))))
        val registry = editRegistry(topology)
        val infrastructure = MutableTopologyService(topology, registry)
        val sources = CapturingSignalEpochSourceService()
        val manager = runtimeManager(plugin(), infrastructure.service, CapturingRoutePlanningService(), definitions, sources)
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(worldId).status)
        val before = Files.readString(definitions.primaryFile(worldId))
        val retained = sources.sources.getValue(worldId)
        protect(registry, topology, rails[0], TopologyEditBlockerKind.INFRASTRUCTURE_DEPENDENCY)
        val downstreamEdit = manager.applyDefinitionPatch(worldId, 1,
            SimpleSignalsDefinitionPatch(upsertFixedBlockSignals = listOf(signal(3, 2))))
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.EDIT_BLOCKED, downstreamEdit.status)
        assertEquals(rails.toSet(), infrastructure.lastEditRequest!!.impact.railIds)
        assertEquals(setOf(rails[0]), downstreamEdit.editDecision!!.blockers.single().overlap.railIds)
        // Delete the old downstream node and its old edge in one valid candidate. Old reachability
        // must still protect the retained upstream signal even though that edge has disappeared.
        val deleted = manager.applyDefinitionPatch(worldId, 1, SimpleSignalsDefinitionPatch(
            upsertFixedBlockSignals = listOf(signal(2, 2)), removeFixedBlockSignalIds = listOf("test:signal-3"),
            removeFixedBlockIds = listOf("test:block-3")))
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.EDIT_BLOCKED, deleted.status)
        assertEquals(before, Files.readString(definitions.primaryFile(worldId)))
        assertSame(retained, sources.sources.getValue(worldId))
    }

    @Test
    fun `moved block protects old and new rail for dependencies and claims`() {
        listOf(TopologyEditBlockerKind.INFRASTRUCTURE_DEPENDENCY, TopologyEditBlockerKind.LIVE_RUNTIME_CLAIM).forEach { kind ->
            listOf(0, 1).forEach { protectedIndex ->
                val worldId = UUID.randomUUID()
                val rails = listOf(UUID(0, 1), UUID(0, 2))
                val topology = topologyState(41, rails, emptyList())
                val initial = FixedBlockDefinition("test:moved", 1, listOf(FixedBlockTraversal(rails[0], ViewDirection.AB, 0.0, 5.0)))
                val definitions = store()
                definitions.save(worldId, SimpleSignalsWorldDefinitions(worldId, 1, emptyList(), emptyList(), listOf(initial)))
                val registry = editRegistry(topology)
                val infrastructure = MutableTopologyService(topology, registry)
                val sources = CapturingSignalEpochSourceService()
                val manager = runtimeManager(plugin(), infrastructure.service, CapturingRoutePlanningService(), definitions, sources)
                assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(worldId).status)
                protect(registry, topology, rails[protectedIndex], kind)
                val before = Files.readString(definitions.primaryFile(worldId))
                val retained = sources.sources.getValue(worldId)
                val moved = FixedBlockDefinition(initial.blockId, 2, listOf(FixedBlockTraversal(rails[1], ViewDirection.AB, 0.0, 5.0)))
                val blocked = manager.applyDefinitionPatch(worldId, 1, SimpleSignalsDefinitionPatch(upsertFixedBlocks = listOf(moved)))
                assertEquals(SimpleSignalsDefinitionAuthoringStatus.EDIT_BLOCKED, blocked.status)
                assertEquals(kind, blocked.editDecision!!.blockers.single().kind)
                assertEquals(rails.toSet(), infrastructure.lastEditRequest!!.impact.railIds)
                assertEquals(before, Files.readString(definitions.primaryFile(worldId)))
                assertSame(retained, sources.sources.getValue(worldId))
            }
        }
    }

    @Test
    fun `reverse successor edits protect the predecessor region and region edits protect the whole block`() {
        val fixture = Fixture()
        val outside = UUID(0L, 91L)
        val topology = topologyState(41L,
            listOf(fixture.entry, fixture.upperIn, fixture.lowerIn, fixture.upperOut, fixture.lowerOut, fixture.exit, outside),
            listOf(
                endpoint(outside, RailEndpoint.B) to endpoint(fixture.entry, RailEndpoint.A),
                endpoint(fixture.entry, RailEndpoint.B) to endpoint(fixture.upperIn, RailEndpoint.A),
                endpoint(fixture.entry, RailEndpoint.B) to endpoint(fixture.lowerIn, RailEndpoint.A),
                endpoint(fixture.upperIn, RailEndpoint.B) to endpoint(fixture.upperOut, RailEndpoint.A),
                endpoint(fixture.upperIn, RailEndpoint.B) to endpoint(fixture.lowerOut, RailEndpoint.A),
                endpoint(fixture.lowerIn, RailEndpoint.B) to endpoint(fixture.lowerOut, RailEndpoint.A),
                endpoint(fixture.lowerIn, RailEndpoint.B) to endpoint(fixture.upperOut, RailEndpoint.A),
                endpoint(fixture.upperOut, RailEndpoint.B) to endpoint(fixture.exit, RailEndpoint.A),
                endpoint(fixture.lowerOut, RailEndpoint.B) to endpoint(fixture.exit, RailEndpoint.A)))
        val registry = editRegistry(topology)
        val infrastructure = MutableTopologyService(topology, registry)
        val successor = FixedBlockDefinition("test:reverse-successor", 1L, listOf(
            FixedBlockTraversal(outside, ViewDirection.AB, 0.0, 10.0),
            FixedBlockTraversal(fixture.entry, ViewDirection.AB, 0.0, 5.0)), true)
        val definitions = store()
        definitions.save(fixture.worldId, SimpleSignalsWorldDefinitions(fixture.worldId, 1L,
            listOf(fixture.region), listOf(fixture.table(1L, fixture.upperRoute())), listOf(successor)))
        val sources = CapturingSignalEpochSourceService()
        val manager = runtimeManager(plugin(), infrastructure.service, CapturingRoutePlanningService(), definitions, sources)
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(fixture.worldId).status)
        val runtime = manager.authoringRuntime(fixture.worldId)!!
        val before = Files.readString(definitions.primaryFile(fixture.worldId))
        val publication = runtime.authoringPublication()
        val retained = sources.sources.getValue(fixture.worldId)
        val withoutRevision = SimpleSignalsDefinitionPatch(upsertFixedBlocks = listOf(
            FixedBlockDefinition(successor.blockId, 1L, successor.traversals, false)))
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.VALIDATION_REJECTED,
            manager.applyDefinitionPatch(fixture.worldId, 1L, withoutRevision).status)
        assertEquals(0, infrastructure.permitAcquisitionCount)
        val releaseRegion = protect(registry, topology, fixture.upperIn, TopologyEditBlockerKind.INFRASTRUCTURE_DEPENDENCY)
        val disabling = SimpleSignalsDefinitionPatch(upsertFixedBlocks = listOf(
            FixedBlockDefinition(successor.blockId, 2L, successor.traversals, false)))
        listOf(disabling, SimpleSignalsDefinitionPatch(removeFixedBlockIds = listOf(successor.blockId))).forEach { patch ->
            assertEquals(SimpleSignalsDefinitionAuthoringStatus.EDIT_BLOCKED,
                manager.applyDefinitionPatch(fixture.worldId, 1L, patch).status)
            assertTrue(fixture.upperIn in infrastructure.lastEditRequest!!.impact.railIds)
            assertEquals(before, Files.readString(definitions.primaryFile(fixture.worldId)))
            assertSame(publication, runtime.authoringPublication())
            assertSame(retained, sources.sources.getValue(fixture.worldId))
        }
        releaseRegion()
        val releaseBlock = protect(registry, topology, outside, TopologyEditBlockerKind.LIVE_RUNTIME_CLAIM)
        val regionEdit = SimpleSignalsDefinitionPatch(
            upsertRegions = listOf(ProtectionRegionDefinition(fixture.region.regionId, 2L, fixture.region.boundaries)),
            upsertRouteTables = listOf(fixture.table(2L, fixture.upperRoute(), regionRevision = 2L)))
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.EDIT_BLOCKED,
            manager.applyDefinitionPatch(fixture.worldId, 1L, regionEdit).status)
        assertTrue(outside in infrastructure.lastEditRequest!!.impact.railIds)
        assertEquals(before, Files.readString(definitions.primaryFile(fixture.worldId)))
        assertSame(publication, runtime.authoringPublication())
        assertSame(retained, sources.sources.getValue(fixture.worldId))
        releaseBlock()
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED,
            manager.applyDefinitionPatch(fixture.worldId, 1L, disabling).status)
        assertFalse(runtime.authoringPublication().definitions.fixedBlocks.single().bidirectional)
    }

    @Test
    fun `successor block exit edit protects upstream region authority even without a signal rule`() {
        val fixture = Fixture()
        val registry = editRegistry(fixture.currentTopology)
        val infrastructure = MutableTopologyService(fixture.currentTopology, registry)
        val successor = FixedBlockDefinition("test:successor", 1,
            listOf(FixedBlockTraversal(fixture.exit, ViewDirection.AB, 5.0, 9.0)))
        val definitions = store()
        definitions.save(fixture.worldId, SimpleSignalsWorldDefinitions(fixture.worldId, 1,
            listOf(fixture.region), listOf(fixture.table(1, fixture.upperRoute())), listOf(successor)))
        val sources = CapturingSignalEpochSourceService()
        val manager = runtimeManager(plugin(), infrastructure.service, CapturingRoutePlanningService(), definitions, sources)
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(fixture.worldId).status)
        protect(registry, fixture.currentTopology, fixture.upperIn, TopologyEditBlockerKind.INFRASTRUCTURE_DEPENDENCY)
        val before = Files.readString(definitions.primaryFile(fixture.worldId))
        val retained = sources.sources.getValue(fixture.worldId)
        val changed = FixedBlockDefinition(successor.blockId, 2,
            listOf(FixedBlockTraversal(fixture.exit, ViewDirection.AB, 5.0, 8.0)))
        listOf(SimpleSignalsDefinitionPatch(upsertFixedBlocks = listOf(changed)),
            SimpleSignalsDefinitionPatch(removeFixedBlockIds = listOf(successor.blockId))).forEach { patch ->
            val blocked = manager.applyDefinitionPatch(fixture.worldId, 1, patch)
            assertEquals(SimpleSignalsDefinitionAuthoringStatus.EDIT_BLOCKED, blocked.status)
            assertTrue(fixture.upperIn in infrastructure.lastEditRequest!!.impact.railIds)
            assertEquals(before, Files.readString(definitions.primaryFile(fixture.worldId)))
            assertSame(retained, sources.sources.getValue(fixture.worldId))
        }
    }

    private fun editRegistry(topology: TopologyState) = editRegistry { topology }

    @Test
    fun `all device model candidates reject missing and out of bounds rails and unchanged revisions atomically`() {
        listOf("fixed", "fixed-route", "current-entry", "indicator", "attached-fixed", "attached-fixed-route", "attached-current-entry").forEach { kind ->
            val fixture = Fixture()
            val infrastructure = MutableTopologyService(fixture.currentTopology)
            val definitions = store()
            val initial = deviceModelDefinitions(fixture, kind, railModel(fixture.entry))
            definitions.save(fixture.worldId, initial)
            val sources = CapturingSignalEpochSourceService()
            val planning = CapturingRoutePlanningService()
            val manager = runtimeManager(plugin(), infrastructure.service, planning, definitions, sources)
            assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(fixture.worldId).status)
            val runtime = manager.authoringRuntime(fixture.worldId)!!
            val retained = runtime.authoringPublication()
            val retainedSources = sources.sources.getValue(fixture.worldId)
            val restrictions = planning.single(fixture.worldId).source.restrictions(fixture.currentTopology)
            val primary = Files.readString(definitions.primaryFile(fixture.worldId))
            val invalid = listOf(
                deviceModelDefinitions(fixture, kind, railModel(UUID(0, 999)), 2),
                deviceModelDefinitions(fixture, kind, railModel(fixture.entry, 10.0 + Curve.TOLERANCE / 2), 2),
                deviceModelDefinitions(fixture, kind, DeviceModelDefinition("demo_signals:wall", ModelPlacement.Ground(ModelPose.IDENTITY)))
            )
            invalid.forEachIndexed { index, candidate ->
                val patch = definitionDraftPatch(initial, candidate)
                val rejected = manager.applyDefinitionPatch(fixture.worldId, 1, patch)
                assertEquals(SimpleSignalsDefinitionAuthoringStatus.VALIDATION_REJECTED, rejected.status, "$kind: ${rejected.diagnostics}")
                if (index < 2) assertTrue(rejected.diagnostics.any { it.contains("test:device") }, rejected.diagnostics.toString())
                assertSame(retained, runtime.authoringPublication())
                assertSame(retainedSources, sources.sources.getValue(fixture.worldId))
                assertSame(restrictions, planning.single(fixture.worldId).source.restrictions(fixture.currentTopology))
                assertEquals(primary, Files.readString(definitions.primaryFile(fixture.worldId)))
                assertEquals(0, infrastructure.permitAcquisitionCount)
            }
            // Both exact rail endpoints are accepted against the installed fixture package.
            val updated = deviceModelDefinitions(fixture, kind, railModel(fixture.entry, 10.0), 2)
            val applied = manager.applyDefinitionPatch(fixture.worldId, 1, definitionDraftPatch(initial, updated))
            assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED, applied.status, applied.diagnostics.toString())
            assertEquals(2L, applied.definitions!!.definitionRevision)
            assertEquals(applied.definitions, (definitions.load(fixture.worldId) as
                work.art1st.javarailways.simplesignals.persistence.SimpleSignalsWorldDefinitionLoadResult.Loaded).definitions)
        }
    }

    @Test
    fun `model moves on every device protect both attachments with real dependencies and claims`() {
        listOf("fixed", "fixed-route", "current-entry", "indicator", "attached-fixed", "attached-fixed-route", "attached-current-entry").forEach { kind ->
            listOf(TopologyEditBlockerKind.INFRASTRUCTURE_DEPENDENCY, TopologyEditBlockerKind.LIVE_RUNTIME_CLAIM).forEach { blockerKind ->
                listOf(0, 1).forEach { protectedIndex ->
                    val fixture = Fixture()
                    val attachments = listOf(UUID(0, 91), UUID(0, 92))
                    val unrelated = UUID(0, 93)
                    val topology = fixture.topology(41, attachments + unrelated)
                    val initial = deviceModelDefinitions(fixture, kind, railModel(attachments[0]))
                    val candidate = deviceModelDefinitions(fixture, kind, railModel(attachments[1]), 2)
                    val definitions = store()
                    definitions.save(fixture.worldId, initial)
                    val registry = editRegistry(topology)
                    val infrastructure = MutableTopologyService(topology, registry)
                    val sources = CapturingSignalEpochSourceService()
                    val manager = runtimeManager(plugin(), infrastructure.service, CapturingRoutePlanningService(), definitions, sources)
                    assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(fixture.worldId).status)
                    val runtime = manager.authoringRuntime(fixture.worldId)!!
                    val retained = runtime.authoringPublication()
                    val retainedSources = sources.sources.getValue(fixture.worldId)
                    val primary = Files.readString(definitions.primaryFile(fixture.worldId))
                    protect(registry, topology, unrelated, TopologyEditBlockerKind.INFRASTRUCTURE_DEPENDENCY)
                    val release = protect(registry, topology, attachments[protectedIndex], blockerKind)
                    val patch = definitionDraftPatch(initial, candidate)
                    val blocked = manager.applyDefinitionPatch(fixture.worldId, 1, patch)
                    assertEquals(SimpleSignalsDefinitionAuthoringStatus.EDIT_BLOCKED, blocked.status, "$kind: ${blocked.diagnostics}")
                    assertEquals(blockerKind, blocked.editDecision!!.blockers.single().kind)
                    assertEquals(setOf(attachments[protectedIndex]), blocked.editDecision.blockers.single().overlap.railIds)
                    val expectedRails = attachments.toSet() + deviceLogicalRails(fixture, kind)
                    assertTrue(infrastructure.lastEditRequest!!.impact.railIds.containsAll(expectedRails), kind)
                    assertFalse(unrelated in infrastructure.lastEditRequest!!.impact.railIds, kind)
                    assertEquals(primary, Files.readString(definitions.primaryFile(fixture.worldId)))
                    assertSame(retained, runtime.authoringPublication())
                    assertSame(retainedSources, sources.sources.getValue(fixture.worldId))
                    release()
                    val applied = manager.applyDefinitionPatch(fixture.worldId, 1, patch)
                    assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED, applied.status, "$kind: ${applied.diagnostics}")
                    assertTrue(infrastructure.lastEditRequest!!.impact.railIds.containsAll(expectedRails), kind)
                    assertFalse(unrelated in infrastructure.lastEditRequest!!.impact.railIds, kind)
                    assertEquals(1, infrastructure.permitCloseCount)
                }
            }
        }
    }

    @Test
    fun `ground model edits keep every device logical protection and omit unrelated rails`() {
        listOf("fixed", "fixed-route", "current-entry", "indicator", "attached-fixed", "attached-fixed-route", "attached-current-entry").forEach { kind ->
            val fixture = Fixture()
            val unrelated = UUID(0, 93)
            val topology = fixture.topology(41, listOf(unrelated))
            val initial = deviceModelDefinitions(fixture, kind,
                DeviceModelDefinition("demo_signals:wall", ModelPlacement.Ground(ModelPose.IDENTITY)))
            val candidate = deviceModelDefinitions(fixture, kind,
                DeviceModelDefinition("demo_signals:wall", ModelPlacement.Ground(ModelPose(100.0, 64.0, 3.0, 0.0, 0.0, 0.0, 1.0))), 2)
            val definitions = store()
            definitions.save(fixture.worldId, initial)
            val registry = editRegistry(topology)
            val infrastructure = MutableTopologyService(topology, registry)
            val sources = CapturingSignalEpochSourceService()
            val manager = runtimeManager(plugin(), infrastructure.service, CapturingRoutePlanningService(), definitions, sources)
            assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(fixture.worldId).status)
            val runtime = manager.authoringRuntime(fixture.worldId)!!
            val retained = runtime.authoringPublication()
            val retainedSources = sources.sources.getValue(fixture.worldId)
            val primary = Files.readString(definitions.primaryFile(fixture.worldId))
            protect(registry, topology, unrelated, TopologyEditBlockerKind.INFRASTRUCTURE_DEPENDENCY)
            val logicalRail = if (kind.removePrefix("attached-") == "fixed") fixture.entry else fixture.upperIn
            val release = protect(registry, topology, logicalRail, TopologyEditBlockerKind.LIVE_RUNTIME_CLAIM)
            val patch = definitionDraftPatch(initial, candidate)
            val blocked = manager.applyDefinitionPatch(fixture.worldId, 1, patch)
            assertEquals(SimpleSignalsDefinitionAuthoringStatus.EDIT_BLOCKED, blocked.status, "$kind: ${blocked.diagnostics}")
            assertTrue(infrastructure.lastEditRequest!!.impact.railIds.containsAll(deviceLogicalRails(fixture, kind)), kind)
            assertFalse(unrelated in infrastructure.lastEditRequest!!.impact.railIds, kind)
            assertEquals(primary, Files.readString(definitions.primaryFile(fixture.worldId)))
            assertSame(retained, runtime.authoringPublication())
            assertSame(retainedSources, sources.sources.getValue(fixture.worldId))
            release()
            val applied = manager.applyDefinitionPatch(fixture.worldId, 1, patch)
            assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED, applied.status, "$kind: ${applied.diagnostics}")
            assertTrue(infrastructure.lastEditRequest!!.impact.railIds.containsAll(deviceLogicalRails(fixture, kind)), kind)
            assertFalse(unrelated in infrastructure.lastEditRequest!!.impact.railIds, kind)
        }
    }

    private fun railModel(rail: UUID, at: Double = 0.0) = DeviceModelDefinition("demo_signals:home",
        ModelPlacement.RailAttached(RailPositionSnapshot(rail, ViewDirection.BA, at)))

    private fun deviceLogicalRails(fixture: Fixture, kind: String) = if (kind.removePrefix("attached-") == "fixed") setOf(fixture.entry)
        else fixture.upperRoute().traversals.mapTo(linkedSetOf()) { it.railId }

    private fun deviceModelDefinitions(fixture: Fixture, kind: String, model: DeviceModelDefinition?, revision: Long = 1): SimpleSignalsWorldDefinitions {
        val route = fixture.upperRoute()
        val attached = kind.startsWith("attached-")
        val logicalKind = kind.removePrefix("attached-")
        val primaryId = if (attached) "test:primary" else "test:device"
        val primaryRevision = if (attached) 1 else revision
        val primaryModel = if (attached) null else model
        return SimpleSignalsWorldDefinitions(fixture.worldId, 1, listOf(fixture.region),
            listOf(fixture.table(1, route)), listOf(FixedBlockDefinition("test:block", 1,
                listOf(FixedBlockTraversal(fixture.entry, ViewDirection.AB, 0.0, 4.0)))),
            fixedBlockSignals = if (logicalKind == "fixed") listOf(FixedBlockSignalDefinition(primaryId, primaryRevision,
                "test:block", 0.0, "automatic:block", placement = primaryModel?.placement)) else emptyList(),
            protectionSignals = if (logicalKind == "fixed-route" || logicalKind == "current-entry") listOf(ProtectionSignalDefinition(
                primaryId, primaryRevision, if (logicalKind == "fixed-route") ProtectionSignalKind.FIXED_ROUTE else ProtectionSignalKind.CURRENT_ENTRY,
                if (logicalKind == "fixed-route") route.routeId else route.entryBoundaryId, 0.0, "automatic:block",
                placement = primaryModel?.placement)) else emptyList(),
            routeIndicators = if (kind == "indicator") listOf(RouteIndicatorDefinition("test:device", revision,
                route.entryBoundaryId, "stop", mapOf(route.routeId to "proceed"), model)) else emptyList(),
            attachedSignals = if (attached) listOf(AttachedSignalDefinition("test:device", revision, primaryId,
                requireNotNull(model))) else emptyList())
    }

    @Test
    fun `indicator edit protects old new placement and explicit entry binding without unrelated regions`() {
        val fixture = Fixture()
        val bindingRail = UUID(0, 91)
        val oldPlacement = UUID(0, 92)
        val newPlacement = UUID(0, 93)
        val newBindingRail = UUID(0, 94)
        val topology = fixture.topology(41, listOf(bindingRail, oldPlacement, newPlacement, newBindingRail))
        val localRegion = ProtectionRegionDefinition("test:local-region", 1, listOf(
            ProtectionBoundaryDefinition("test:local-entry", RailPositionSnapshot(bindingRail, ViewDirection.AB, 2.0)),
            ProtectionBoundaryDefinition("test:local-exit", RailPositionSnapshot(bindingRail, ViewDirection.BA, 8.0))))
        val localRoute = ProtectionRouteDefinition("test:local-route", "test:local-entry", "test:local-exit",
            listOf(ProtectionRouteTraversal(bindingRail, ViewDirection.AB, 2.0, 8.0)))
        val localTable = ProtectionRouteTableDefinition("test:local-table", 1, localRegion.regionId, 1, listOf(localRoute))
        val newRegion = ProtectionRegionDefinition("test:new-region", 1, listOf(
            ProtectionBoundaryDefinition("test:new-entry", RailPositionSnapshot(newBindingRail, ViewDirection.AB, 2.0)),
            ProtectionBoundaryDefinition("test:new-exit", RailPositionSnapshot(newBindingRail, ViewDirection.BA, 8.0))))
        val newRoute = ProtectionRouteDefinition("test:new-route", "test:new-entry", "test:new-exit",
            listOf(ProtectionRouteTraversal(newBindingRail, ViewDirection.AB, 2.0, 8.0)))
        val newTable = ProtectionRouteTableDefinition("test:new-table", 1, newRegion.regionId, 1, listOf(newRoute))
        fun indicator(revision: Long, placement: UUID, route: ProtectionRouteDefinition = localRoute) =
            RouteIndicatorDefinition("test:local-indicator", revision,
                route.entryBoundaryId, "stop", mapOf(route.routeId to "proceed"),
                DeviceModelDefinition("demo_signals:home", ModelPlacement.RailAttached(RailPositionSnapshot(placement, ViewDirection.AB, 1.0))))
        val definitions = store()
        definitions.save(fixture.worldId, SimpleSignalsWorldDefinitions(fixture.worldId, 1,
            listOf(fixture.region, localRegion, newRegion), listOf(fixture.table(1, fixture.upperRoute()), localTable, newTable),
            routeIndicators = listOf(indicator(1, oldPlacement))))
        val registry = editRegistry(topology)
        val infrastructure = MutableTopologyService(topology, registry)
        val sources = CapturingSignalEpochSourceService()
        val manager = runtimeManager(plugin(), infrastructure.service, CapturingRoutePlanningService(), definitions, sources)
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(fixture.worldId).status)
        protect(registry, topology, fixture.upperIn, TopologyEditBlockerKind.INFRASTRUCTURE_DEPENDENCY)
        val moved = manager.applyDefinitionPatch(fixture.worldId, 1,
            SimpleSignalsDefinitionPatch(upsertRouteIndicators = listOf(indicator(2, newPlacement, newRoute))))
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED, moved.status, moved.diagnostics.toString())
        assertEquals(setOf(bindingRail, newBindingRail, oldPlacement, newPlacement), infrastructure.lastEditRequest!!.impact.railIds)

        protect(registry, topology, newBindingRail, TopologyEditBlockerKind.LIVE_RUNTIME_CLAIM)
        val before = Files.readString(definitions.primaryFile(fixture.worldId))
        val retained = sources.sources.getValue(fixture.worldId)
        val removed = manager.applyDefinitionPatch(fixture.worldId, 2,
            SimpleSignalsDefinitionPatch(removeRouteIndicatorIds = listOf("test:local-indicator")))
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.EDIT_BLOCKED, removed.status)
        assertEquals(TopologyEditBlockerKind.LIVE_RUNTIME_CLAIM, removed.editDecision!!.blockers.single().kind)
        assertEquals(before, Files.readString(definitions.primaryFile(fixture.worldId)))
        assertSame(retained, sources.sources.getValue(fixture.worldId))
    }

    @Test
    fun `currently inactive downstream branch still protects the operating upstream signal`() {
        val fixture = Fixture()
        val upstreamAttachment = UUID(0, 99)
        val topology = fixture.topology(41, listOf(upstreamAttachment))
        val blocks = listOf("source" to fixture.entry, "upper" to fixture.upperIn, "lower" to fixture.lowerIn)
            .map { (name, rail) -> FixedBlockDefinition("test:$name-block", 1,
                listOf(FixedBlockTraversal(rail, ViewDirection.AB, 0.0, 5.0))) }
        fun signal(name: String, revision: Long = 1) =
            FixedBlockSignalDefinition("test:$name-signal", revision, "test:$name-block", 0.0,
                "automatic:block", placement = if (name == "source") railModel(upstreamAttachment).placement else null)
        val entry = endpoint(fixture.entry, RailEndpoint.B)
        val definitions = store()
        definitions.save(fixture.worldId, SimpleSignalsWorldDefinitions(fixture.worldId, 1, emptyList(), emptyList(), blocks,
            listOf(signal("source"), signal("upper"), signal("lower"))))
        val registry = editRegistry(topology)
        val infrastructure = MutableTopologyService(topology, registry)
        val sources = CapturingSignalEpochSourceService()
        val manager = runtimeManager(plugin(), infrastructure.service, CapturingRoutePlanningService(), definitions, sources)
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(fixture.worldId).status)
        val epoch = SignalEpochRuntime(fixture.worldId.toString(), TrainRestrictionSnapshotStore(fixture.worldId.toString()))
        epoch.replaceSources(sources.sources.getValue(fixture.worldId))
        val active = ActiveRailContinuationsSnapshot.create(topology.revision, 1,
            mapOf(entry to endpoint(fixture.upperIn, RailEndpoint.A)))
        epoch.process(RailwayWorldFrameSnapshot.create(1, topology.revision, emptyList(), active))
        assertEquals(endpoint(fixture.upperIn, RailEndpoint.A), active.selectedContinuation(entry))
        protect(registry, topology, fixture.entry, TopologyEditBlockerKind.INFRASTRUCTURE_DEPENDENCY)
        val before = Files.readString(definitions.primaryFile(fixture.worldId))
        val retained = sources.sources.getValue(fixture.worldId)
        val blocked = manager.applyDefinitionPatch(fixture.worldId, 1,
            SimpleSignalsDefinitionPatch(upsertFixedBlockSignals = listOf(signal("lower", 2))))
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.EDIT_BLOCKED, blocked.status, blocked.diagnostics.toString())
        assertEquals(setOf(fixture.entry), blocked.editDecision!!.blockers.single().overlap.railIds)
        assertTrue(fixture.lowerIn in infrastructure.lastEditRequest!!.impact.railIds)
        assertTrue(upstreamAttachment in infrastructure.lastEditRequest!!.impact.railIds)
        assertEquals(before, Files.readString(definitions.primaryFile(fixture.worldId)))
        assertSame(retained, sources.sources.getValue(fixture.worldId))
    }

    private fun editRegistry(topology: () -> TopologyState) = InfrastructureCoordinationRegistry(
        { topology().revision }, {
            it is RailIntervalResource && it.railId in topology().railProjection ||
                it is StableInfrastructureResource && it.typeId == FIXED_BLOCK_RESOURCE_TYPE
        },
        { it in topology().railProjection })

    private fun protect(registry: InfrastructureCoordinationRegistry, topology: TopologyState,
        rail: UUID, kind: TopologyEditBlockerKind
    ): () -> Unit {
        val platformOwner = Any()
        val owner = InfrastructureOwner("test:operator/$rail", 1)
        when (kind) {
            TopologyEditBlockerKind.INFRASTRUCTURE_DEPENDENCY -> assertEquals(InfrastructureDependencyMutationStatus.APPLIED,
                registry.setInfrastructureDependencies(platformOwner, InfrastructureDependencyRequest(topology.revision, owner,
                    InfrastructureDependencies(setOf(rail), emptySet(), emptySet()))).status)
            TopologyEditBlockerKind.LIVE_RUNTIME_CLAIM -> assertEquals(InfrastructureMutationStatus.ACQUIRED,
                registry.acquireClaim(platformOwner, InfrastructureClaimRequest(topology.revision, owner,
                    InfrastructureFootprint(listOf(RailIntervalResource(rail, 0.0, 5.0))))).status)
            else -> error("unsupported test blocker")
        }
        return {
            when (kind) {
                TopologyEditBlockerKind.INFRASTRUCTURE_DEPENDENCY -> assertEquals(InfrastructureDependencyMutationStatus.REMOVED,
                    registry.removeInfrastructureDependencies(platformOwner, owner).status)
                TopologyEditBlockerKind.LIVE_RUNTIME_CLAIM -> assertEquals(InfrastructureMutationStatus.RELEASED,
                    registry.releaseClaim(platformOwner, owner).status)
            }
        }
    }

    @Test
    fun `fixed route signal deletion is blocked by real static route dependency until release`() {
        assertProtectionSignalDeletionBlocked(ProtectionSignalKind.FIXED_ROUTE, TopologyEditBlockerKind.INFRASTRUCTURE_DEPENDENCY)
    }

    @Test
    fun `current entry signal deletion is blocked by real static route dependency until release`() {
        assertProtectionSignalDeletionBlocked(ProtectionSignalKind.CURRENT_ENTRY, TopologyEditBlockerKind.INFRASTRUCTURE_DEPENDENCY)
    }

    @Test
    fun `fixed route signal deletion is blocked by real live claim until release`() {
        assertProtectionSignalDeletionBlocked(ProtectionSignalKind.FIXED_ROUTE, TopologyEditBlockerKind.LIVE_RUNTIME_CLAIM)
    }

    @Test
    fun `current entry signal deletion is blocked by real live claim until release`() {
        assertProtectionSignalDeletionBlocked(ProtectionSignalKind.CURRENT_ENTRY, TopologyEditBlockerKind.LIVE_RUNTIME_CLAIM)
    }

    private fun assertProtectionSignalDeletionBlocked(kind: ProtectionSignalKind, blockerKind: TopologyEditBlockerKind) {
        val fixture = Fixture()
        val topology = fixture.currentTopology
        val signal = protectionSignal(kind, fixture.upperRoute())
        val definitions = store()
        definitions.save(fixture.worldId, SimpleSignalsWorldDefinitions(fixture.worldId, 1,
            listOf(fixture.region), listOf(fixture.table(1, fixture.upperRoute())), protectionSignals = listOf(signal)))
        val registry = editRegistry(topology)
        val infrastructure = MutableTopologyService(topology, registry)
        val sources = CapturingSignalEpochSourceService()
        val planning = CapturingRoutePlanningService()
        val manager = runtimeManager(plugin(), infrastructure.service, planning, definitions, sources)
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(fixture.worldId).status)
        val runtime = manager.authoringRuntime(fixture.worldId)!!
        val publication = runtime.authoringPublication()
        val retainedSources = sources.sources.getValue(fixture.worldId)
        val retainedPlanning = planning.single(fixture.worldId).source.restrictions(topology)
        val primary = Files.readString(definitions.primaryFile(fixture.worldId))
        val commitCount = sources.atomicCommitCount
        // Protect an interior rail of the controlled route, away from the entrance signal.
        val release = protect(registry, topology, fixture.upperIn, blockerKind)
        val patch = SimpleSignalsDefinitionPatch(removeProtectionSignalIds = listOf(signal.signalId))

        val blocked = manager.applyDefinitionPatch(fixture.worldId, 1, patch)

        assertEquals(SimpleSignalsDefinitionAuthoringStatus.EDIT_BLOCKED, blocked.status, blocked.diagnostics.toString())
        assertEquals(TopologyEditStatus.CONFLICT, blocked.editDecision!!.status)
        val blocker = blocked.editDecision.blockers.single()
        assertEquals(blockerKind, blocker.kind)
        assertEquals(InfrastructureOwner("test:operator/${fixture.upperIn}", 1), blocker.owner)
        assertEquals(setOf(fixture.upperIn), blocker.overlap.railIds)
        assertTrue(fixture.upperIn in infrastructure.lastEditRequest!!.impact.railIds)
        assertEquals(primary, Files.readString(definitions.primaryFile(fixture.worldId)))
        assertSame(publication, runtime.authoringPublication())
        assertSame(retainedSources, sources.sources.getValue(fixture.worldId))
        assertSame(retainedPlanning, planning.single(fixture.worldId).source.restrictions(topology))
        assertEquals(commitCount, sources.atomicCommitCount)
        assertEquals(0, infrastructure.permitCloseCount)

        release()
        val removed = manager.applyDefinitionPatch(fixture.worldId, 1, patch)

        assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED, removed.status, removed.diagnostics.toString())
        assertEquals(2L, removed.definitions!!.definitionRevision)
        assertTrue(removed.definitions.protectionSignals.isEmpty())
        assertEquals(publication.definitions.regions, removed.definitions.regions)
        assertEquals(publication.definitions.routeTables, removed.definitions.routeTables)
        assertEquals(removed.definitions,
            work.art1st.javarailways.simplesignals.persistence.SimpleSignalsWorldDefinitionsCodec.decode(
                Files.readString(definitions.primaryFile(fixture.worldId)), fixture.worldId))
        assertNotSame(publication, runtime.authoringPublication())
        assertNotSame(retainedSources, sources.sources.getValue(fixture.worldId))
        assertEquals(commitCount + 1, sources.atomicCommitCount)
        assertEquals(1, infrastructure.permitCloseCount)
    }

    @Test
    fun `unrelated static route dependency and live claim allow local protection signal deletion`() {
        ProtectionSignalKind.entries.forEach { kind ->
            val fixture = Fixture()
            val localRail = UUID(0, 99)
            val topology = fixture.topology(41, listOf(localRail))
            val localRegion = ProtectionRegionDefinition("test:local-region", 1, listOf(
                ProtectionBoundaryDefinition("test:local-entry", RailPositionSnapshot(localRail, ViewDirection.AB, 2.0)),
                ProtectionBoundaryDefinition("test:local-exit", RailPositionSnapshot(localRail, ViewDirection.BA, 8.0))))
            val localRoute = ProtectionRouteDefinition("test:local-route", "test:local-entry", "test:local-exit",
                listOf(ProtectionRouteTraversal(localRail, ViewDirection.AB, 2.0, 8.0)))
            val localTable = ProtectionRouteTableDefinition("test:local-table", 1, localRegion.regionId, 1, listOf(localRoute))
            val signal = protectionSignal(kind, localRoute)
            val definitions = store()
            definitions.save(fixture.worldId, SimpleSignalsWorldDefinitions(fixture.worldId, 1,
                listOf(fixture.region, localRegion), listOf(fixture.table(1, fixture.upperRoute()), localTable),
                protectionSignals = listOf(signal)))
            val registry = editRegistry(topology)
            val infrastructure = MutableTopologyService(topology, registry)
            val sources = CapturingSignalEpochSourceService()
            val manager = runtimeManager(plugin(), infrastructure.service, CapturingRoutePlanningService(), definitions, sources)
            assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(fixture.worldId).status)
            val releaseDependency = protect(registry, topology, fixture.upperIn, TopologyEditBlockerKind.INFRASTRUCTURE_DEPENDENCY)
            val releaseClaim = protect(registry, topology, fixture.lowerIn, TopologyEditBlockerKind.LIVE_RUNTIME_CLAIM)

            val removed = manager.applyDefinitionPatch(fixture.worldId, 1,
                SimpleSignalsDefinitionPatch(removeProtectionSignalIds = listOf(signal.signalId)))

            assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED, removed.status, "$kind: ${removed.diagnostics}")
            assertTrue(removed.definitions!!.protectionSignals.isEmpty())
            assertEquals(setOf(localRail), infrastructure.lastEditRequest!!.impact.railIds)
            assertEquals(1, infrastructure.permitCloseCount)
            releaseClaim()
            releaseDependency()
        }
    }

    private fun protectionSignal(kind: ProtectionSignalKind, route: ProtectionRouteDefinition) = ProtectionSignalDefinition(
        "test:protection-signal", 1, kind,
        if (kind == ProtectionSignalKind.FIXED_ROUTE) route.routeId else route.entryBoundaryId,
        0.0, "automatic:block")

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
            ProtectionSignalDefinition(id, revision, kind, binding, 2.0, "automatic:block")
        val fixed = signal("test:fixed", ProtectionSignalKind.FIXED_ROUTE, route.routeId)
        val current = signal("test:current", ProtectionSignalKind.CURRENT_ENTRY, route.entryBoundaryId)
        val indicator = RouteIndicatorDefinition("test:indicator", 1, route.entryBoundaryId,
            "custom/no-route",
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
                    indicator.entryBoundaryId, indicator.noRouteModelVariant,
                    indicator.modelVariantsByRouteId, indicator.model))))
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
            0.0, "automatic:block")
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
    fun `duplicate directional entry and reverse on one way reject candidate before disk source or permit changes`() {
        val f = Fixture()
        val block = FixedBlockDefinition("test:entry-block", 1,
            listOf(FixedBlockTraversal(f.entry, ViewDirection.AB, 1.0, 4.0)))
        val signal = FixedBlockSignalDefinition("test:forward", 1, block.blockId, 0.0,
            "automatic:block")
        val stored = store()
        stored.save(f.worldId, SimpleSignalsWorldDefinitions(f.worldId, 1, emptyList(), emptyList(), listOf(block), listOf(signal)))
        val infrastructure = MutableTopologyService(f.currentTopology)
        val sources = CapturingSignalEpochSourceService()
        val manager = runtimeManager(plugin(), infrastructure.service, CapturingRoutePlanningService(), stored, sources)
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(f.worldId).status)
        val originalDisk = Files.readString(stored.primaryFile(f.worldId))
        val originalSource = sources.sources.getValue(f.worldId).single()
        val originalRuntime = manager.authoringRuntime(f.worldId)!!
        val originalPublication = originalRuntime.authoringPublication()
        for (candidate in listOf(signal.reconfigured(signalId = "test:duplicate"),
            signal.reconfigured(signalId = "test:reverse", entryDirection = work.art1st.javarailways.simplesignals.signal.FixedBlockEntryDirection.REVERSE))) {
            val patch = SimpleSignalsDefinitionPatch(upsertFixedBlockSignals = listOf(candidate))
            assertEquals(SimpleSignalsDefinitionAuthoringStatus.VALIDATION_REJECTED,
                manager.applyDefinitionPatch(f.worldId, 1, patch).status)
            assertEquals(0, infrastructure.permitAcquisitionCount)
            assertEquals(originalDisk, Files.readString(stored.primaryFile(f.worldId)))
            assertSame(originalSource, sources.sources.getValue(f.worldId).single())
            assertSame(originalPublication, originalRuntime.authoringPublication())
        }
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
            "automatic:block"
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
        assertTrue(orphaned.diagnostics.single().contains("controls unknown block"))
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
    fun `selected partial block resource dependency guards real edits without claiming or blocking unrelated blocks`() {
        val fixture = Fixture()
        val unrelatedRail = UUID(0L, 20L)
        val topology = fixture.topology(41L, listOf(unrelatedRail))
        val selected = FixedBlockDefinition("test:selected", 1L,
            listOf(FixedBlockTraversal(fixture.entry, ViewDirection.AB, 0.0, 5.0)))
        val unrelated = FixedBlockDefinition("test:unrelated", 1L,
            listOf(FixedBlockTraversal(unrelatedRail, ViewDirection.AB, 0.0, 5.0)))
        val store = store()
        store.save(fixture.worldId, SimpleSignalsWorldDefinitions(fixture.worldId, 1L,
            emptyList(), emptyList(), listOf(selected, unrelated)))
        val plugin = plugin()
        var runtime: SimpleSignalsWorldRuntime? = null
        val provider = fixedBlockPolicy(plugin) { runtime?.fixedBlockSnapshot() }
        val registry = InfrastructureCoordinationRegistry({ topology.revision }, { resource ->
            when (resource) {
                is RailIntervalResource -> resource.railId in topology.railProjection
                is StableInfrastructureResource -> resource.typeId == FIXED_BLOCK_RESOURCE_TYPE &&
                    provider.exists(fixture.worldId, resource.resourceId)
            }
        }, { it in topology.railProjection })
        val infrastructure = MutableTopologyService(topology, registry)
        val sources = CapturingSignalEpochSourceService()
        val manager = runtimeManager(plugin, infrastructure.service, CapturingRoutePlanningService(), store, sources)
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(fixture.worldId).status)
        val installedRuntime = checkNotNull(manager.authoringRuntime(fixture.worldId))
        runtime = installedRuntime
        val route = RouteSnapshot("test:selected-timetable", 0L, topology.revision,
            listOf(RouteSegmentSnapshot(fixture.entry, ViewDirection.AB, 1.0, 3.0)))
        val resources = installedRuntime.fixedBlockInspectionSource.routeInfrastructureDependencies.dependencies(route)
        val token = StableInfrastructureResource(FIXED_BLOCK_RESOURCE_TYPE, selected.blockId)
        assertEquals(setOf(token), resources.toSet())
        val owner = InfrastructureOwner("test:applied-timetable", 1L)
        val planOwner = Any()
        assertEquals(InfrastructureDependencyMutationStatus.APPLIED,
            registry.setInfrastructureDependencies(planOwner, InfrastructureDependencyRequest(topology.revision, owner,
                InfrastructureDependencies(emptySet(), emptySet(), resources))).status)
        assertTrue(registry.claimSnapshots().isEmpty(), "edit dependencies acquire no train resources")

        // A dependency must also coexist with the actual train resource claim.
        val trainOwner = Any()
        val claim = InfrastructureOwner("test:train", 1L)
        assertEquals(InfrastructureMutationStatus.ACQUIRED,
            registry.acquireClaim(trainOwner, InfrastructureClaimRequest(topology.revision, claim,
                InfrastructureFootprint(listOf(token)))).status)
        assertEquals(InfrastructureMutationStatus.RELEASED, registry.releaseClaim(trainOwner, claim).status)

        assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED,
            manager.applyDefinitionPatch(fixture.worldId, 1L,
                SimpleSignalsDefinitionPatch(upsertFixedBlocks = listOf(
                    FixedBlockDefinition(unrelated.blockId, 2L, unrelated.traversals)))).status)
        val before = installedRuntime.currentDefinitions()
        val primary = Files.readString(store.primaryFile(fixture.worldId))
        val installedSources = sources.sources.getValue(fixture.worldId)
        listOf(
            SimpleSignalsDefinitionPatch(upsertFixedBlocks = listOf(
                FixedBlockDefinition(selected.blockId, 2L, selected.traversals))),
            SimpleSignalsDefinitionPatch(removeFixedBlockIds = setOf(selected.blockId))
        ).forEach { patch ->
            val blocked = manager.applyDefinitionPatch(fixture.worldId, 2L, patch)
            assertEquals(SimpleSignalsDefinitionAuthoringStatus.EDIT_BLOCKED, blocked.status, blocked.diagnostics.toString())
            val blocker = blocked.editDecision!!.blockers.single()
            assertEquals(owner, blocker.owner)
            assertEquals(TopologyEditBlockerKind.INFRASTRUCTURE_DEPENDENCY, blocker.kind)
            assertTrue(blocker.overlap.railIds.isEmpty(), "this regression requires the exact resource guard")
            assertEquals(setOf(token), blocker.overlap.resourceIds)
            assertSame(before, installedRuntime.currentDefinitions())
            assertSame(installedSources, sources.sources.getValue(fixture.worldId))
            assertEquals(primary, Files.readString(store.primaryFile(fixture.worldId)))
        }
        assertEquals(InfrastructureDependencyMutationStatus.REMOVED,
            registry.removeInfrastructureDependencies(planOwner, owner).status)
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED,
            manager.applyDefinitionPatch(fixture.worldId, 2L,
                SimpleSignalsDefinitionPatch(removeFixedBlockIds = setOf(selected.blockId))).status)
        assertTrue(registry.claimSnapshots().isEmpty())
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

    private fun writeAutomaticSignalPack(outputVariant: String = "clear", script: String? = null): Path {
        val root = directory.resolve("signal-packs")
        val pack = root.resolve("automatic")
        Files.createDirectories(pack.resolve("model"))
        Files.createDirectories(pack.resolve("logic"))
        Files.createDirectories(pack.resolve("signal"))
        Files.writeString(pack.resolve("pack.yml"), "config-version: 1\nname: automatic")
        Files.writeString(pack.resolve("model/head.yml"), """
            parts:
              - type: dummy
            default: {}
            variants:
              clear: {}
        """.trimIndent())
        Files.writeString(pack.resolve("model/slow-head.yml"), """
            parts:
              - type: dummy
            default: {}
            variants:
              clear: {}
        """.trimIndent())
        Files.writeString(pack.resolve("logic/block.yml"), "scan-distance-metres: 100")
        Files.writeString(pack.resolve("logic/block.js"), script ?: """
            function evaluate(input, output) {
              output.setSpeedLimit(25);
              output.setModelVariant("$outputVariant");
            }
        """.trimIndent())
        Files.writeString(pack.resolve("signal/block.yml"), """
            display-name: Automatic block
            model: automatic:head
            logic: automatic:block
        """.trimIndent())
        Files.writeString(pack.resolve("logic/slow.yml"), "scan-distance-metres: 100")
        Files.writeString(pack.resolve("logic/slow.js"), """
            function evaluate(input, output) {
              output.setSpeedLimit(5);
              output.setModelVariant("clear");
            }
        """.trimIndent())
        Files.writeString(pack.resolve("signal/slow.yml"), """
            display-name: Slow automatic block
            model: automatic:slow-head
            logic: automatic:slow
        """.trimIndent())
        return root
    }

    @Test
    fun `topology callback never waits for source publication and older worker cannot override related repair`() {
        val fixture = Fixture()
        val infrastructure = MutableTopologyService(fixture.currentTopology)
        val definitions = store()
        val block = FixedBlockDefinition("test:local", 1,
            listOf(FixedBlockTraversal(fixture.entry, ViewDirection.AB, 1.0, 3.0)), true)
        definitions.save(fixture.worldId, SimpleSignalsWorldDefinitions(fixture.worldId, 1,
            emptyList(), emptyList(), listOf(block)))
        val sources = CapturingSignalEpochSourceService()
        val queue = java.util.ArrayDeque<() -> Unit>()
        val manager = runtimeManager(plugin(), infrastructure.service, CapturingRoutePlanningService(), definitions,
            sources, topologyWorker = { queue += it })
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(fixture.worldId).status)
        val runtime = manager.authoringRuntime(fixture.worldId)!!
        val before = runtime.authoringPublication()
        infrastructure.commitTopology(fixture.topology(42), setOf(fixture.entry))
        assertEquals(1, queue.size)
        assertTrue(runtime.topologyGuard.sectionUnavailable(TopologySectionKind.BLOCK, block.blockId))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        sources.beforeCommitAction = {
            sources.beforeCommitAction = {}
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        val work = queue.removeFirst()
        val worker = CompletableFuture.runAsync { work() }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            CompletableFuture.runAsync { infrastructure.commitTopology(fixture.topology(43), setOf(fixture.entry)) }
                .get(1, TimeUnit.SECONDS)
            assertSame(before, runtime.authoringPublication())
        } finally { release.countDown() }
        worker.get(5, TimeUnit.SECONDS)
        assertEquals(43, runtime.authoringPublication().topology.revision)
        assertFalse(runtime.topologyGuard.sectionUnavailable(TopologySectionKind.BLOCK, block.blockId))
        assertEquals(2, sources.atomicCommitCount, "initial install plus one final local publication")
        manager.remove(fixture.worldId)
        assertTrue(infrastructure.listeners.isEmpty())
    }

    @Test
    fun `topology revalidation reprepares package model pose against the new exact root`() {
        val fixture = Fixture()
        val infrastructure = MutableTopologyService(fixture.currentTopology)
        val definitions = store()
        val block = FixedBlockDefinition("test:topology-model-block", 1,
            listOf(FixedBlockTraversal(fixture.entry, ViewDirection.AB, 1.0, 3.0)), true)
        val signal = FixedBlockSignalDefinition("test:topology-model-device", 1, block.blockId, 0.0,
            signalType = "automatic:block",
            placement = ModelPlacement.RailAttached(RailPositionSnapshot(fixture.entry, ViewDirection.AB, 2.0)))
        definitions.save(fixture.worldId, SimpleSignalsWorldDefinitions(fixture.worldId, 1,
            emptyList(), emptyList(), listOf(block), listOf(signal)))
        fun curve(startX: Double) = PrimeCurve.fromPoints(listOf(
            Curve.RawPoint(Vector3d(startX, 0.0, 0.0), Vector3d(1.0, 0.0, 0.0),
                Vector3d(0.0, 1.0, 0.0), 0.0, Curve.SegmentType.LINE_START),
            Curve.RawPoint(Vector3d(startX + 10.0, 0.0, 0.0), Vector3d(1.0, 0.0, 0.0),
                Vector3d(0.0, 1.0, 0.0), 10.0, Curve.SegmentType.LINE_END)))
        var currentCurve = curve(0.0)
        val rails = proxy(RailTargetService::class.java) { method, _ ->
            check(method.name == "findCurve")
            java.util.Optional.of(currentCurve)
        }
        val models = RecordingModels()
        val topologyQueue = java.util.ArrayDeque<() -> Unit>()
        val manager = runtimeManager(plugin(), infrastructure.service, CapturingRoutePlanningService(), definitions,
            CapturingSignalEpochSourceService(), railTargets = rails, modelPresentation = models,
            topologyWorker = { topologyQueue += it }, loadDefaultSignalCatalog = false)
        manager.loadSignalPacks(writeAutomaticSignalPack())
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(fixture.worldId).status)
        val handle = models.handles.single()
        assertEquals(2.0, handle.submissions.last().transform().x())

        currentCurve = curve(100.0)
        infrastructure.commitTopology(fixture.topology(42), setOf(fixture.entry))
        topologyQueue.removeFirst().invoke()

        val runtime = manager.authoringRuntime(fixture.worldId)!!
        assertSame(infrastructure.topology, runtime.currentModels()!!.prepared.topology)
        assertEquals(102.0, handle.submissions.last().transform().x())
        assertEquals(1, models.handles.size, "same captured package model keeps its handle")
        models.cleanup.complete(null)
        manager.clearLocal().toCompletableFuture().join()
    }

    @Test
    fun `preview observes pending topology invalidation and unchanged region recovery`() {
        val fixture = Fixture()
        val unrelated = UUID(0, 100)
        val infrastructure = MutableTopologyService(fixture.topology(41, listOf(unrelated)))
        val definitions = store()
        definitions.save(fixture.worldId, SimpleSignalsWorldDefinitions(fixture.worldId, 1,
            listOf(fixture.region), emptyList()))
        val queue = java.util.ArrayDeque<() -> Unit>()
        val manager = runtimeManager(plugin(), infrastructure.service, CapturingRoutePlanningService(), definitions,
            CapturingSignalEpochSourceService(), topologyWorker = { queue += it })
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(fixture.worldId).status)
        val runtime = manager.authoringRuntime(fixture.worldId)!!
        val changes = mutableListOf<PreviewRegionState>()
        val subscription = runtime.observePreviewRegion(fixture.region.regionId, changes::add)
        try {
            infrastructure.commitTopology(fixture.topology(42, listOf(unrelated)), setOf(fixture.entry))
            assertEquals(listOf(true), changes.map { it.unavailable }, "pending invalidation must notify before revalidation")
            assertEquals(1, queue.size)
            assertTrue(runtime.previewRegionState(fixture.region.regionId).unavailable)
            queue.removeFirst().invoke()
            assertEquals(listOf(true, false), changes.map { it.unavailable })
            assertTrue(changes.all { it.definition == fixture.region }, "recovery retains the unchanged definition")
            assertFalse(runtime.previewRegionState(fixture.region.regionId).unavailable)
            infrastructure.commitTopology(fixture.topology(43, listOf(unrelated)), setOf(unrelated))
            assertEquals(2, changes.size, "unrelated edits must not notify this preview")
            assertTrue(queue.isEmpty())
        } finally {
            subscription.close()
            manager.remove(fixture.worldId)
        }
    }

    private class MutableTopologyService(initial: TopologyState?, private val registry: InfrastructureCoordinationRegistry? = null) {
        private val registryOwner = Any()
        val listeners = java.util.concurrent.CopyOnWriteArrayList<work.art1st.javarailways.core.api.physics.rail.topology.RailTopologyChangeListener>()
        @Volatile
        var topology: TopologyState? = initial
        fun commitTopology(next: TopologyState, rails: Set<UUID>) {
            val change = work.art1st.javarailways.core.api.physics.rail.topology.RailTopologyChange(checkNotNull(topology), next, rails)
            listeners.forEach { it.onTopologyChange(change) }
            topology = next
        }
        var nextEditDecision = TopologyEditDecision(TopologyEditStatus.GRANTED)
        var operatingDirectionOverride: Map<UUID, ViewDirection?>? = null
        var afterPermitGranted: () -> Unit = {}
        var permitAcquisitionCount = 0
            private set
        var permitCloseCount = 0
            private set
        var lastEditRequest: TopologyEditRequest? = null
            private set
        val service: InfrastructureResourceService = proxy(InfrastructureResourceService::class.java) { method, arguments ->
            when (method.name) {
                "observeTopologyChanges" -> {
                    val listener = arguments!![2] as work.art1st.javarailways.core.api.physics.rail.topology.RailTopologyChangeListener
                    listeners += listener
                    AutoCloseable { listeners -= listener }
                }
                "currentTopology" -> topology
                    ?: throw IllegalStateException("Railway world is not registered: ${arguments?.single()}")
                "operatingDirections" -> {
                    @Suppress("UNCHECKED_CAST")
                    val rails = arguments!![2] as Collection<UUID>
                    work.art1st.javarailways.basic.api.route.RailOperatingDirections(
                        arguments[1] as TopologyState, rails.associateWith { rail ->
                            if (operatingDirectionOverride == null) ViewDirection.AB else operatingDirectionOverride!!.getValue(rail) })
                }
                "acquireEditPermit" -> {
                    permitAcquisitionCount++
                    lastEditRequest = arguments!![2] as TopologyEditRequest
                    val registryPermit = registry?.acquireEditPermit(registryOwner, lastEditRequest!!)
                    val decision = registryPermit?.decision ?: nextEditDecision
                    if (!decision.successful) {
                        InfrastructureEditPermitResult.denied(decision)
                    } else {
                        val owner = lastEditRequest!!.owner
                        val permit = object : InfrastructureEditPermit {
                            private var closed = false
                            override fun owner(): InfrastructureOwner = owner
                            override fun close() {
                                if (!closed) {
                                    closed = true
                                    permitCloseCount++
                                    registryPermit?.let { check(registry.releaseEditPermit(registryOwner, it.id)) }
                                }
                            }
                        }
                        afterPermitGranted()
                        InfrastructureEditPermitResult.granted(decision, permit)
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

        fun topology(revision: Long, extraRails: List<UUID> = emptyList()): TopologyState = topologyState(
            revision,
            listOf(entry, upperIn, lowerIn, upperOut, lowerOut, exit) + extraRails,
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
            inspectionSources: InspectionSourceService = inspectionSources(),
            railTargets: RailTargetService = fixtureRailTargets(),
            modelPresentation: ModelPresentationService = fixtureModelService(),
            modelWorker: (() -> Unit) -> Unit = { it() },
            modelFailure: (String, Throwable) -> Unit = { _, _ -> },
            topologyWorker: (() -> Unit) -> Unit = { CompletableFuture.runAsync(it) },
            loadDefaultSignalCatalog: Boolean = true
        ) = SimpleSignalsWorldRuntimeManager(
            plugin,
            infrastructure,
            routePlanningSources,
            definitions,
            signalEpochSources,
            inspectionSources,
            railTargets,
            modelPresentation,
            modelWorker,
            modelFailure,
            topologyWorker = topologyWorker
        ).also {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
            it.loadModelPackages(Path.of(requireNotNull(SimpleSignalsWorldRuntimeManagerTest::class.java
                .getResource("/model-packages")).toURI()))
            if (loadDefaultSignalCatalog) {
                it.loadSignalPacks(Path.of(requireNotNull(SimpleSignalsWorldRuntimeManagerTest::class.java
                    .getResource("/signal-packs")).toURI()))
            }
        }

        private fun fixtureRailTargets(): RailTargetService {
            val curve = PrimeCurve.fromPoints(listOf(
                Curve.RawPoint(Vector3d(), Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0), 0.0, Curve.SegmentType.LINE_START),
                Curve.RawPoint(Vector3d(10.0, 0.0, 0.0), Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0), 10.0, Curve.SegmentType.LINE_END)))
            return proxy(RailTargetService::class.java) { method, _ ->
                when (method.name) {
                    "findCurve" -> java.util.Optional.of(curve)
                    else -> throw UnsupportedOperationException(method.name)
                }
            }
        }

        private fun fixtureModelService(): ModelPresentationService = proxy(ModelPresentationService::class.java) { method, _ ->
            when (method.name) {
                "create" -> proxy(ModelHandle::class.java) { action, _ ->
                    when (action.name) { "submit", "close" -> Unit; else -> throw UnsupportedOperationException(action.name) }
                }
                "closeOwner" -> CompletableFuture.completedFuture<Void>(null)
                else -> throw UnsupportedOperationException(method.name)
            }
        }

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
