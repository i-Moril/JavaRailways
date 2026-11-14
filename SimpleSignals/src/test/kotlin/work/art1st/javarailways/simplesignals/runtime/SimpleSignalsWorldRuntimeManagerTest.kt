package work.art1st.javarailways.simplesignals.runtime

import work.art1st.javarailways.basic.basicWorldData
import work.art1st.javarailways.basic.rail.infrastructureMetadata
import work.art1st.javarailways.core.physics.train.onOwnerForTest
import work.art1st.javarailways.simplesignals.testing.signalWorldRuntime
import work.art1st.javarailways.simplesignals.testing.reconfigured
import work.art1st.javarailways.simplesignals.testing.protectionTestEpoch
import work.art1st.javarailways.simplesignals.authoring.*
import work.art1st.javarailways.basic.api.observation.RailwayObservation
import work.art1st.javarailways.bukkit.api.observation.RailwayObservationService
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef

import work.art1st.javarailways.simplesignals.testing.fixedBlockPolicy

import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNotEquals
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
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureOwner
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureDependencies
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureDependencyRequest
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureDependencyMutationStatus
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureFootprint
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureMutationStatus
import work.art1st.javarailways.core.api.physics.infrastructure.RailIntervalResource
import work.art1st.javarailways.core.api.physics.infrastructure.StableInfrastructureResource
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.route.ActiveRouteAdmissionSnapshot
import work.art1st.javarailways.basic.api.route.ActiveRouteAdmissionState
import work.art1st.javarailways.basic.api.route.ActiveRouteAdmissionUpdate
import work.art1st.javarailways.basic.api.route.RouteBoundary
import work.art1st.javarailways.basic.api.route.RouteResourceAdmissionService
import work.art1st.javarailways.basic.api.route.RouteResourceIntent
import work.art1st.javarailways.basic.api.route.RouteAdmissionHandle
import work.art1st.javarailways.basic.api.route.RouteAdmissionResult
import work.art1st.javarailways.basic.api.route.RouteAdmissionFailure
import work.art1st.javarailways.basic.api.route.RouteGrantId
import work.art1st.javarailways.basic.control.HandleTrainActuator
import work.art1st.javarailways.basic.infrastructure.admission.RouteResourceAdmissionRuntime
import work.art1st.javarailways.bukkit.config.model.TrainControlConfig
import work.art1st.javarailways.bukkit.config.model.CarControlConfig
import work.art1st.javarailways.simpleato.api.ato.AtoSafetyProfile
import work.art1st.javarailways.simpleato.controller.SimpleATO
import work.art1st.javarailways.simpleato.runtime.OperationalWorldRuntime
import work.art1st.javarailways.simpleato.timetable.Timetable
import work.art1st.javarailways.simpleato.timetable.TimetableService
import work.art1st.javarailways.simpleato.timetable.TimetableStep
import work.art1st.javarailways.simpleato.timetable.TimetableAction
import work.art1st.javarailways.simpleato.timetable.TrainSelector
import work.art1st.javarailways.simpleato.timetable.TimetableDispatcher
import work.art1st.javarailways.simpleato.timetable.TimetableAtoFactory
import work.art1st.javarailways.simpleato.timetable.TimetableRouteAdmissionContext
import work.art1st.javarailways.simpleato.timetable.TimetableRouteAdmissionContextProvider
import work.art1st.javarailways.simpleato.timetable.TimetableServiceStartResult
import work.art1st.javarailways.simpleato.timetable.TimetableServiceRunState
import work.art1st.javarailways.simpleato.timetable.InfrastructureDependencyService
import work.art1st.javarailways.simplesignals.block.FixedBlockRouteResourcePolicy
import work.art1st.javarailways.core.physics.infrastructure.InfrastructureCoordinationRegistry
import work.art1st.javarailways.core.physics.infrastructure.InfrastructureClaimRequest
import work.art1st.javarailways.core.api.physics.infrastructure.TopologyEditBlocker
import work.art1st.javarailways.core.api.physics.infrastructure.TopologyEditBlockerKind
import work.art1st.javarailways.core.api.physics.infrastructure.TopologyEditDecision
import work.art1st.javarailways.core.api.physics.infrastructure.TopologyEditOverlap
import work.art1st.javarailways.core.api.physics.infrastructure.TopologyEditRequest
import work.art1st.javarailways.core.api.physics.infrastructure.TopologyEditStatus
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
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot
import work.art1st.javarailways.core.api.physics.rail.topology.ActiveRailContinuationsSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.core.util.IsolatedSimulationThreadMarker
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
import work.art1st.javarailways.simplesignals.region.lineTopologyState
import work.art1st.javarailways.simplesignals.region.topologyState
import work.art1st.javarailways.simplesignals.persistence.SimpleSignalsWorldDefinitionStore
import work.art1st.javarailways.simplesignals.persistence.SimpleSignalsWorldDefinitions
import work.art1st.javarailways.simplesignals.signal.FixedBlockEntryDirection
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
import work.art1st.javarailways.simplesignals.region.ProtectionBoundaryCut
import work.art1st.javarailways.simplesignals.region.RailCutPosition
import work.art1st.javarailways.simplesignals.region.cutPosition
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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SimpleSignalsWorldRuntimeManagerTest {
    @TempDir
    lateinit var directory: Path

    private val concurrentExecutor = Executors.newCachedThreadPool()

    @AfterEach
    fun closeConcurrentExecutor() {
        concurrentExecutor.shutdownNow()
        assertTrue(concurrentExecutor.awaitTermination(5, TimeUnit.SECONDS), "test concurrent tasks must terminate")
    }

    @Test fun `unbound ground devices delete through the atomic transaction without a fabricated rail permit`() {
        for (kind in listOf("fixed", "route", "entry", "indicator", "attached")) {
            val f = Fixture()
            val placement = ModelPlacement.Ground(ModelPose.IDENTITY)
            val model = DeviceModelDefinition("demo_signals:home", placement)
            val fixed = FixedBlockSignalDefinition("test:device", 1, null, 0.0, "automatic:block", placement = placement)
            val initial = SimpleSignalsWorldDefinitions(f.worldId, 1, emptyList(), emptyList(),
                fixedBlockSignals = if (kind in listOf("fixed", "attached")) listOf(fixed) else emptyList(),
                protectionSignals = if (kind in listOf("route", "entry")) listOf(ProtectionSignalDefinition(
                    "test:device", 1, if (kind == "route") ProtectionSignalKind.FIXED_ROUTE else ProtectionSignalKind.CURRENT_ENTRY,
                    null, 0.0, "automatic:block", placement)) else emptyList(),
                routeIndicators = if (kind == "indicator") listOf(RouteIndicatorDefinition(
                    "test:device", 1, null, "stop", emptyMap(), model)) else emptyList(),
                attachedSignals = if (kind == "attached") listOf(AttachedSignalDefinition(
                    "test:attached", 1, fixed.signalId, model)) else emptyList())
            val definitions = store().also { it.save(f.worldId, initial) }
            val infrastructure = MutableTopologyService(f.currentTopology, editRegistry { f.currentTopology })
            val sources = CapturingSignalEpochSourceService()
            val manager = runtimeManager(plugin(), infrastructure.service, CapturingRoutePlanningService(), definitions, sources)
            assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(f.worldId).status, kind)
            val patch = when (kind) {
                "fixed" -> SimpleSignalsDefinitionPatch(removeFixedBlockSignalIds = listOf("test:device"))
                "route", "entry" -> SimpleSignalsDefinitionPatch(removeProtectionSignalIds = listOf("test:device"))
                "indicator" -> SimpleSignalsDefinitionPatch(removeRouteIndicatorIds = listOf("test:device"))
                else -> SimpleSignalsDefinitionPatch(removeAttachedSignalIds = listOf("test:attached"))
            }
            val validation = manager.validateDefinitionPatch(f.worldId, 1, patch)
            assertEquals(SimpleSignalsDefinitionAuthoringStatus.VALIDATED, validation.status, "$kind: ${validation.diagnostics}")
            assertTrue(validation.affectedRailIds.isEmpty())
            val result = manager.applyDefinitionPatch(f.worldId, 1, patch)
            assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED, result.status, "$kind: ${result.diagnostics}")
            assertEquals(2L, result.definitions!!.definitionRevision)
            assertNull(result.editDecision, "no host infrastructure permit was requested")
            assertEquals(0, infrastructure.permitAcquisitionCount)
            assertEquals(2, sources.atomicCommitCount, "installation and deletion each replace sources atomically")
            assertTrue(manager.authoringRuntime(f.worldId)!!.currentDefinitions().attachedSignals.isEmpty())
            if (kind != "attached") assertTrue(result.definitions!!.fixedBlockSignals.isEmpty() &&
                result.definitions!!.protectionSignals.isEmpty() && result.definitions!!.routeIndicators.isEmpty())
            manager.remove(f.worldId)
            assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(f.worldId).status)
            assertEquals(result.definitions, manager.authoringRuntime(f.worldId)!!.currentDefinitions())
            manager.remove(f.worldId)
        }
    }

    @Test fun `unbound rail devices still require real infrastructure permits for deletion`() {
        for (kind in listOf(TopologyEditBlockerKind.INFRASTRUCTURE_DEPENDENCY, TopologyEditBlockerKind.LIVE_RUNTIME_CLAIM)) {
            val f = Fixture()
            val signal = FixedBlockSignalDefinition("test:device", 1, null, 0.0, "automatic:block",
                placement = ModelPlacement.RailAttached(RailPositionSnapshot(f.entry, ViewDirection.AB, 1.0)))
            val initial = SimpleSignalsWorldDefinitions(f.worldId, 1, emptyList(), emptyList(), fixedBlockSignals = listOf(signal))
            val definitions = store().also { it.save(f.worldId, initial) }
            val disk = Files.readString(definitions.primaryFile(f.worldId))
            val registry = editRegistry(f.currentTopology)
            val infrastructure = MutableTopologyService(f.currentTopology, registry)
            val sources = CapturingSignalEpochSourceService()
            val manager = runtimeManager(plugin(), infrastructure.service, CapturingRoutePlanningService(), definitions, sources)
            assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(f.worldId).status)
            val runtime = manager.authoringRuntime(f.worldId)!!
            val publication = runtime.authoringPublication()
            val source = sources.sources.getValue(f.worldId)
            val release = protect(registry, f.currentTopology, f.entry, kind)
            val patch = SimpleSignalsDefinitionPatch(removeFixedBlockSignalIds = listOf(signal.signalId))
            val blocked = manager.applyDefinitionPatch(f.worldId, 1, patch)
            assertEquals(SimpleSignalsDefinitionAuthoringStatus.EDIT_BLOCKED, blocked.status, blocked.diagnostics.toString())
            assertEquals(setOf(f.entry), infrastructure.lastEditRequest!!.impact.railIds)
            assertSame(publication, runtime.authoringPublication())
            assertSame(source, sources.sources.getValue(f.worldId))
            assertEquals(disk, Files.readString(definitions.primaryFile(f.worldId)))
            release()
            assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED, manager.applyDefinitionPatch(f.worldId, 1, patch).status)
            assertEquals(2, infrastructure.permitAcquisitionCount)
            assertEquals(1, infrastructure.permitCloseCount)
            manager.remove(f.worldId)
        }
    }

    @Test fun `empty impact device edits retain no-op revision source persistence and cancellation guards`() {
        for (failure in listOf("noop", "revision", "source", "persistence", "topology", "cancel")) {
            val f = Fixture()
            val initial = SimpleSignalsWorldDefinitions(f.worldId, 1, emptyList(), emptyList(),
                fixedBlockSignals = listOf(FixedBlockSignalDefinition("test:device", 1, null, 0.0, "automatic:block",
                    placement = ModelPlacement.Ground(ModelPose.IDENTITY))))
            val seed = store().also { it.save(f.worldId, initial) }
            val disk = Files.readString(seed.primaryFile(f.worldId))
            val definitions = if (failure == "persistence") SimpleSignalsWorldDefinitionStore(directory.toFile()) {
                throw java.io.IOException("injected before primary replace")
            } else seed
            val infrastructure = MutableTopologyService(f.currentTopology)
            val sources = CapturingSignalEpochSourceService()
            val manager = runtimeManager(plugin(), infrastructure.service, CapturingRoutePlanningService(), definitions, sources)
            assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(f.worldId).status)
            val runtime = manager.authoringRuntime(f.worldId)!!
            val publication = runtime.authoringPublication()
            val source = sources.sources.getValue(f.worldId)
            var active = true
            var topologyChanges = 0
            when (failure) {
                "source" -> sources.nextPrevalidationFailure = IllegalArgumentException("injected source rejection")
                "topology" -> sources.beforeCommitAction = {
                    topologyChanges++
                    infrastructure.topology = f.topology(41L + topologyChanges)
                }
                "cancel" -> sources.beforeCommitAction = { active = false }
            }
            val patch = if (failure == "noop") SimpleSignalsDefinitionPatch(upsertFixedBlockSignals = initial.fixedBlockSignals)
                else SimpleSignalsDefinitionPatch(removeFixedBlockSignalIds = listOf("test:device"))
            val result = manager.applyDefinitionPatch(f.worldId, if (failure == "revision") 0 else 1, patch,
                authoringGuard = { active })
            val expected = when (failure) {
                "noop" -> SimpleSignalsDefinitionAuthoringStatus.PATCH_REJECTED
                "revision" -> SimpleSignalsDefinitionAuthoringStatus.REVISION_REJECTED
                "source" -> SimpleSignalsDefinitionAuthoringStatus.VALIDATION_REJECTED
                "persistence" -> SimpleSignalsDefinitionAuthoringStatus.PERSISTENCE_FAILED
                // No selected infrastructure changed: one reprepare is allowed, then ask for a retry.
                "topology" -> SimpleSignalsDefinitionAuthoringStatus.RETRY_REQUIRED
                else -> SimpleSignalsDefinitionAuthoringStatus.WORLD_UNAVAILABLE
            }
            assertEquals(expected, result.status, "$failure: ${result.diagnostics}")
            assertSame(publication, runtime.authoringPublication(), failure)
            assertSame(source, sources.sources.getValue(f.worldId), failure)
            assertEquals(disk, Files.readString(seed.primaryFile(f.worldId)), failure)
            assertEquals(0, infrastructure.permitAcquisitionCount, failure)
            if (failure == "topology") assertEquals(2, topologyChanges, "one bounded reprepare before retry required")
            manager.remove(f.worldId)
        }
    }

    @Test fun `unbound signal issue disappears on deletion and unload preserves other producers`() {
        val f = Fixture()
        val issues = work.art1st.javarailways.bukkit.api.diagnostics.WorldIssueService()
        val definitions = store()
        definitions.save(f.worldId, SimpleSignalsWorldDefinitions(f.worldId, 1, emptyList(), emptyList(),
            fixedBlockSignals = listOf(FixedBlockSignalDefinition("test:unbound", 1, null, 0.0, "automatic:block",
                placement = ModelPlacement.RailAttached(RailPositionSnapshot(f.entry, ViewDirection.AB, 1.0))))))
        val manager = runtimeManager(plugin(), MutableTopologyService(f.currentTopology).service,
            CapturingRoutePlanningService(), definitions, CapturingSignalEpochSourceService(), worldIssues = issues)
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(f.worldId).status)
        assertTrue(issues.snapshot(f.worldId).any { it.objectId == "test:unbound" && it.code == "definition-unavailable" })
        val removed = manager.applyDefinitionPatch(f.worldId, 1,
            SimpleSignalsDefinitionPatch(removeFixedBlockSignalIds = listOf("test:unbound")))
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED, removed.status, removed.diagnostics.joinToString())
        assertTrue(issues.snapshot(f.worldId).isEmpty())
        issues.report(f.worldId, "other-plugin", work.art1st.javarailways.bukkit.api.diagnostics.WorldIssueInput(
            work.art1st.javarailways.bukkit.api.diagnostics.WorldIssueKind.TRAIN, "train", "failed", "Train failure"))
        manager.remove(f.worldId)
        assertEquals("other-plugin", issues.snapshot(f.worldId).single().source)
    }

    @Test fun `display allocation failure reports device and successful edit clears issue`() {
        val f = Fixture()
        val issues = work.art1st.javarailways.bukkit.api.diagnostics.WorldIssueService()
        val definitions = store()
        val initial = deviceModelDefinitions(f, "fixed", railModel(f.entry))
        definitions.save(f.worldId, initial)
        val models = RecordingModels().also { it.failCreate = 1 }
        val manager = runtimeManager(plugin(), MutableTopologyService(f.currentTopology).service,
            CapturingRoutePlanningService(), definitions, CapturingSignalEpochSourceService(),
            modelPresentation = models, worldIssues = issues)
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(f.worldId).status)
        assertTrue(issues.snapshot(f.worldId).any { it.objectId == "test:device" && it.code == "model-unavailable" })
        models.failCreate = -1
        val repaired = deviceModelDefinitions(f, "fixed", railModel(f.entry, 1.0), 2)
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED,
            manager.applyDefinitionPatch(f.worldId, 1, definitionDraftPatch(initial, repaired)).status)
        assertFalse(issues.snapshot(f.worldId).any { it.source == "simplesignals:models" })
        assertTrue(models.handles.any { !it.closed })
        manager.remove(f.worldId)
        assertTrue(issues.snapshot(f.worldId).isEmpty())
    }

    @Test fun `boundary names use sole metadata transaction preserve route devices and reload`() {
        val f = Fixture()
        val infrastructure = MutableTopologyService(f.currentTopology)
        val definitions = store()
        val indicator = RouteIndicatorDefinition("test:indicator", 7, "test:entry", "closed", mapOf("test:upper" to "1"))
        val original = SimpleSignalsWorldDefinitions(f.worldId, 1, listOf(f.region), listOf(f.table(1, f.upperRoute())), routeIndicators = listOf(indicator))
        definitions.save(f.worldId, original)
        val manager = runtimeManager(plugin(), infrastructure.service, CapturingRoutePlanningService(), definitions, CapturingSignalEpochSourceService())
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(f.worldId).status)
        val backend = RuntimeSectionWandBackend(manager, infrastructure.service, 1000.0)
        val selection = backend.naming(backend.capture(f.worldId)!!, f.region.boundaries.first().position.cutPosition())!!
        backend.rename(selection, " 西端 ") { true }
        val named = manager.authoringRuntime(f.worldId)!!.currentDefinitions()
        assertEquals(2, named.definitionRevision)
        assertEquals(listOf(work.art1st.javarailways.simplesignals.persistence.BoundaryName(f.region.boundaries.first().position.cutPosition(), "西端")), named.boundaryNames)
        assertEquals(setOf(f.entry), infrastructure.lastEditRequest!!.impact.railIds)
        assertEquals(original.regions, named.regions); assertEquals(original.routeTables, named.routeTables)
        assertEquals(original.routeIndicators, named.routeIndicators)
        assertThrows(IllegalArgumentException::class.java) { backend.rename(selection, "stale") { true } }
        manager.remove(f.worldId)
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(f.worldId).status)
        assertEquals(named, manager.authoringRuntime(f.worldId)!!.currentDefinitions())
        val clear = backend.naming(backend.capture(f.worldId)!!, f.region.boundaries.first().position.cutPosition())!!
        backend.rename(clear, "  ") { true }
        assertTrue(manager.authoringRuntime(f.worldId)!!.currentDefinitions().boundaryNames.isEmpty())
        manager.remove(f.worldId)
    }

    @Test fun `metadata save failure and edit denial leave disk publication and revision intact`() {
        val f = Fixture()
        val p = f.region.boundaries.first().position.cutPosition()
        val original = SimpleSignalsWorldDefinitions(f.worldId, 1, listOf(f.region), emptyList(),
            boundaryNames = listOf(work.art1st.javarailways.simplesignals.persistence.BoundaryName(p, "原名")))
        val seed = store(); seed.save(f.worldId, original)
        val bytes = Files.readString(seed.primaryFile(f.worldId))
        val failing = SimpleSignalsWorldDefinitionStore(directory.toFile()) { throw java.io.IOException("injected name save failure") }
        val infrastructure = MutableTopologyService(f.currentTopology)
        val sources = CapturingSignalEpochSourceService()
        val manager = runtimeManager(plugin(), infrastructure.service, CapturingRoutePlanningService(), failing, sources)
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(f.worldId).status)
        val runtime = manager.authoringRuntime(f.worldId)!!
        val publication = runtime.authoringPublication()
        val patch = SimpleSignalsDefinitionPatch(boundaryNames = listOf(work.art1st.javarailways.simplesignals.persistence.BoundaryName(p, "新名")))
        val result = manager.applyDefinitionPatch(f.worldId, 1, patch)
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.PERSISTENCE_FAILED, result.status)
        assertSame(publication, runtime.authoringPublication()); assertEquals(bytes, Files.readString(seed.primaryFile(f.worldId)))
        infrastructure.nextEditDecision = TopologyEditDecision(TopologyEditStatus.CONFLICT, emptyList(), "blocked")
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.EDIT_BLOCKED, manager.applyDefinitionPatch(f.worldId, 1, patch).status)
        assertSame(publication, runtime.authoringPublication()); assertEquals(bytes, Files.readString(seed.primaryFile(f.worldId)))
        assertEquals(1, sources.atomicCommitCount)
        manager.remove(f.worldId)
    }

    @Test fun `named pending cut survives block conversion and final owner deletion cleans metadata`() {
        val world = UUID(0, 812); val rail = UUID(0, 813)
        val infrastructure = MutableTopologyService(lineTopologyState(1, rail))
        val definitions = store()
        val p = RailCutPosition(rail, 0.0)
        definitions.save(world, SimpleSignalsWorldDefinitions(world, 1, emptyList(), emptyList(),
            pendingProtectionBoundaries = listOf(work.art1st.javarailways.simplesignals.region.ProtectionBoundaryCut("test:pending", p)),
            boundaryNames = listOf(work.art1st.javarailways.simplesignals.persistence.BoundaryName(p, "站口"))))
        val manager = runtimeManager(plugin(), infrastructure.service, CapturingRoutePlanningService(), definitions, CapturingSignalEpochSourceService())
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(world).status)
        val backend = RuntimeSectionWandBackend(manager, infrastructure.service, 1000.0)
        backend.perform(backend.capture(world)!!, SectionWandTool.CREATE, false, RailCutPosition(rail, 10.0), true) { true }
        val saved = manager.authoringRuntime(world)!!.currentDefinitions()
        assertEquals("站口", saved.boundaryNames.single().name)
        assertEquals(p, saved.boundaryNames.single().position)
        assertTrue(saved.pendingProtectionBoundaries.isEmpty())
        val removed = manager.applyDefinitionPatch(world, saved.definitionRevision,
            SimpleSignalsDefinitionPatch(removeFixedBlockIds = saved.fixedBlocks.map { it.blockId }))
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED, removed.status)
        assertTrue(removed.definitions!!.boundaryNames.isEmpty())
        manager.remove(world)
    }

    @Test fun `shared block boundary retains its name after one owner is removed and clears after last owner`() {
        val world = UUID(0, 814); val rail = UUID(0, 815)
        val infrastructure = MutableTopologyService(lineTopologyState(1, rail))
        val left = FixedBlockDefinition("test:left", 1, listOf(FixedBlockTraversal(rail, ViewDirection.AB, 0.0, 5.0)))
        val right = FixedBlockDefinition("test:right", 1, listOf(FixedBlockTraversal(rail, ViewDirection.AB, 5.0, 10.0)))
        val definitions = store()
        definitions.save(world, SimpleSignalsWorldDefinitions(world, 1, emptyList(), emptyList(), fixedBlocks = listOf(left, right)))
        val manager = runtimeManager(plugin(), infrastructure.service, CapturingRoutePlanningService(), definitions, CapturingSignalEpochSourceService())
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(world).status)
        val backend = RuntimeSectionWandBackend(manager, infrastructure.service, 1000.0)
        val shared = RailCutPosition(rail, 5.0)
        backend.rename(backend.naming(backend.capture(world)!!, shared)!!, "共用边界") { true }
        val result = manager.applyDefinitionPatch(world, 2, SimpleSignalsDefinitionPatch(removeFixedBlockIds = listOf(left.blockId)))
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED, result.status, result.diagnostics.toString())
        assertEquals(listOf(right), result.definitions!!.fixedBlocks)
        assertEquals(listOf(work.art1st.javarailways.simplesignals.persistence.BoundaryName(shared, "共用边界")), result.definitions!!.boundaryNames)
        val last = manager.applyDefinitionPatch(world, 3, SimpleSignalsDefinitionPatch(removeFixedBlockIds = listOf(right.blockId)))
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED, last.status)
        assertTrue(last.definitions!!.boundaryNames.isEmpty())
        val recreated = manager.applyDefinitionPatch(world, 4, SimpleSignalsDefinitionPatch(upsertFixedBlocks = listOf(right)))
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED, recreated.status)
        assertTrue(recreated.definitions!!.boundaryNames.isEmpty())
        manager.remove(world)
    }

    @Test fun `unified section wand persists endpoint cuts and creates a block beyond the protection search range`() {
        val world = UUID(0, 812)
        val rail = UUID(0, 813)
        val topology = lineTopologyState(1, rail)
        val infrastructure = MutableTopologyService(topology)
        val store = store()
        val manager = runtimeManager(plugin(), infrastructure.service, CapturingRoutePlanningService(), store, CapturingSignalEpochSourceService())
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(world).status)
        val backend = RuntimeSectionWandBackend(manager, infrastructure.service, 1.0)
        val first = backend.perform(backend.capture(world)!!, SectionWandTool.CREATE, false,
            RailCutPosition(rail, 0.0), true) { true }
        assertEquals(SectionWandKind.PENDING, first.kind)
        assertEquals(0.0, manager.authoringRuntime(world)!!.currentDefinitions().pendingProtectionBoundaries.single().position.arcLengthInAbMeters)
        manager.remove(world)
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(world).status)
        val closed = backend.perform(backend.capture(world)!!, SectionWandTool.CREATE, false,
            RailCutPosition(rail, 10.0), true) { true }
        assertEquals(SectionWandKind.BLOCK, closed.kind, closed.text.render(java.util.Locale.US))
        val saved = manager.authoringRuntime(world)!!.currentDefinitions()
        assertTrue(saved.regions.isEmpty())
        assertTrue(saved.pendingProtectionBoundaries.isEmpty())
        assertEquals(listOf(FixedBlockTraversal(rail, ViewDirection.BA, 0.0, 10.0)), saved.fixedBlocks.single().traversals,
            "the closing click selects 10m toward 0m; direction configuration must not reverse the authored path")
        val compiled = manager.authoringRuntime(world)!!.fixedBlockSnapshot().blocksById.getValue(saved.fixedBlocks.single().blockId)
        assertEquals(listOf(ViewDirection.BA, ViewDirection.AB), compiled.entryTraversals.map { it.facing })
        assertEquals(listOf(10.0, 0.0), compiled.entryTraversals.map { it.travelStartAbMeters })
        manager.remove(world)
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(world).status)
        assertEquals(saved, manager.authoringRuntime(world)!!.currentDefinitions())
        val illegalRegion = ProtectionRegionDefinition("test:endpoint-region", 0, listOf(
            ProtectionBoundaryDefinition("test:endpoint-a", RailPositionSnapshot(rail, ViewDirection.AB, 0.0)),
            ProtectionBoundaryDefinition("test:endpoint-b", RailPositionSnapshot(rail, ViewDirection.BA, 10.0))))
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.VALIDATION_REJECTED, manager.applyDefinitionPatch(world, saved.definitionRevision,
            SimpleSignalsDefinitionPatch(upsertRegions = listOf(illegalRegion))).status)
        assertEquals(saved, manager.authoringRuntime(world)!!.currentDefinitions())
    }

    @Test fun `region wand completes new region and shortest table atomically with endpoint warnings and reload`() {
        val f = Fixture()
        val store = store()
        store.save(f.worldId, SimpleSignalsWorldDefinitions(f.worldId, 1, emptyList(), emptyList(),
            pendingProtectionBoundaries = listOf(f.region.boundaries.first().cut()),
            boundaryNames = listOf(work.art1st.javarailways.simplesignals.persistence.BoundaryName(f.region.boundaries.first().position.cutPosition(), "入口"))))
        val infrastructure = MutableTopologyService(f.currentTopology)
        val sources = CapturingSignalEpochSourceService()
        val plans = CapturingRoutePlanningService()
        val manager = runtimeManager(plugin(), infrastructure.service, plans, store, sources)
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(f.worldId).status)
        val backend = RuntimeSectionWandBackend(manager, infrastructure.service, 1000.0)
        val result = backend.perform(backend.capture(f.worldId)!!, SectionWandTool.CREATE, false,
            f.region.boundaries.last().position.cutPosition(), true) { true }
        assertTrue(result.text.render(java.util.Locale.US).contains("generated 1 shortest"), result.text.render(java.util.Locale.US))
        val saved = manager.authoringRuntime(f.worldId)!!.currentDefinitions()
        assertEquals(2, saved.definitionRevision)
        assertTrue(saved.pendingProtectionBoundaries.isEmpty())
        assertEquals("入口", saved.boundaryNames.single().name)
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
            pendingProtectionBoundaries = listOf(f.region.boundaries.first().cut()))
        store.save(f.worldId, pending)
        val infrastructure = MutableTopologyService(f.currentTopology)
        infrastructure.operatingDirectionOverride = f.currentTopology.railProjection.keys.associateWith {
            if (it == f.entry || it == f.exit) ViewDirection.AB else ViewDirection.BA }
        val manager = runtimeManager(plugin(), infrastructure.service, CapturingRoutePlanningService(), store, CapturingSignalEpochSourceService())
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(f.worldId).status)
        val before = Files.readString(store.primaryFile(f.worldId))
        val backend = RuntimeSectionWandBackend(manager, infrastructure.service, 1000.0)
        val failure = assertThrows(IllegalArgumentException::class.java) {
            backend.perform(backend.capture(f.worldId)!!, SectionWandTool.CREATE, false,
                f.region.boundaries.last().position.cutPosition(), true) { true }
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
            pendingProtectionBoundaries = listOf(f.region.boundaries.first().cut()))
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
            val result = backend.perform(backend.capture(f.worldId)!!, SectionWandTool.CREATE, false,
                f.region.boundaries.last().position.cutPosition(), true) { true }
            assertFalse(result.text.render(java.util.Locale.US).contains("generated"))
            assertSame(old, runtime.authoringPublication())
            assertEquals(before, Files.readString(store.primaryFile(f.worldId)))
            assertTrue(plans.single(f.worldId).source.restrictions(f.currentTopology).pathReplacements.isEmpty())
        } finally { unprotect() }
    }

    @Test fun `region repair retains manually selected route and adds only missing endpoint pairs`() {
        val f = Fixture()
        val r = RecordingFixture(f, tables = listOf(f.table(1, f.lowerRoute())))
        r.infrastructure.operatingDirectionOverride = r.topology.railProjection.keys.associateWith { null }
        val backend = RuntimeSectionWandBackend(r.manager, r.infrastructure.service, 1000.0)
        val retained = f.region.boundaries.last().position.cutPosition()
        val naming = backend.naming(backend.capture(f.worldId)!!, retained)!!
        backend.rename(naming, "保留出口") { true }
        val before = r.runtime.currentDefinitions().routeTables
        backend.perform(backend.capture(f.worldId)!!, SectionWandTool.DELETE, false,
            f.region.boundaries.first().position.cutPosition(), true) { true }
        assertEquals("保留出口", r.runtime.currentDefinitions().boundaryNames.single().name)
        assertNotNull(r.runtime.currentDefinitions().regions.single().boundaryDraft)
        val repaired = backend.perform(backend.capture(f.worldId)!!, SectionWandTool.CREATE, false,
            f.region.boundaries.first().position.cutPosition(), true) { true }
        assertNull(r.runtime.currentDefinitions().regions.single().boundaryDraft)
        assertEquals(listOf(work.art1st.javarailways.simplesignals.persistence.BoundaryName(retained, "保留出口")),
            r.runtime.currentDefinitions().boundaryNames)
        assertTrue(repaired.text.render(java.util.Locale.US).contains("repaired"), repaired.text.render(java.util.Locale.US))
        val table = r.runtime.currentDefinitions().routeTables.single()
        assertEquals(before.single().tableId, table.tableId)
        assertEquals(before.single().revision + 1, table.revision)
        assertEquals(listOf(f.lowerRoute()), table.routes.filter { it.entryBoundaryId == "test:entry" })
        assertTrue(table.routes.any { it.entryBoundaryId == "test:exit" && it.exitBoundaryId == "test:entry" })
        assertTrue(r.runtime.authoringPublication().routeTables.unavailableRegionIds().isEmpty())
    }

    @Test fun `repair preserves multiple authored variants and all valid device identities`() {
        val f = Fixture()
        val routes = listOf(f.upperRoute(), f.lowerRoute())
        val table = ProtectionRouteTableDefinition("test:variants", 1, f.region.regionId, f.region.revision, routes)
        val model = DeviceModelDefinition("demo_signals:home", ModelPlacement.Ground(ModelPose.IDENTITY))
        val fixed = ProtectionSignalDefinition("test:fixed", 2, ProtectionSignalKind.FIXED_ROUTE,
            f.lowerRoute().routeId, 0.0, "automatic:block", model.placement)
        val current = fixed.copy(signalId = "test:current", kind = ProtectionSignalKind.CURRENT_ENTRY,
            bindingId = "test:entry")
        val indicator = RouteIndicatorDefinition("test:indicator", 3, "test:entry", "stop",
            mapOf("test:upper" to "proceed", "test:lower" to "stop"), model)
        val attached = AttachedSignalDefinition("test:repeat", 4, fixed.signalId, model)
        val initial = SimpleSignalsWorldDefinitions(f.worldId, 1, listOf(f.region), listOf(table),
            protectionSignals = listOf(fixed, current), routeIndicators = listOf(indicator), attachedSignals = listOf(attached))
        val store = store().also { it.save(f.worldId, initial) }
        val infrastructure = MutableTopologyService(f.currentTopology)
        val models = RecordingModels()
        val manager = runtimeManager(plugin(), infrastructure.service, CapturingRoutePlanningService(), store,
            CapturingSignalEpochSourceService(), modelPresentation = models)
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(f.worldId).status)
        val runtime = manager.authoringRuntime(f.worldId)!!
        val handles = models.handles.toList()
        val backend = RuntimeSectionWandBackend(manager, infrastructure.service, 1000.0)
        val boundary = f.region.boundaries.first().position.cutPosition()
        val deleted = backend.perform(backend.capture(f.worldId)!!, SectionWandTool.DELETE, false, boundary, true) { true }
        assertNotNull(runtime.currentDefinitions().regions.single().boundaryDraft, deleted.text.render(java.util.Locale.US))
        val result = backend.perform(backend.capture(f.worldId)!!, SectionWandTool.CREATE, false, boundary, true) { true }
        assertTrue(result.text.render(java.util.Locale.US).contains("repaired"), result.text.render(java.util.Locale.US))
        val saved = runtime.currentDefinitions()
        assertEquals(routes, saved.routeTables.single().routes)
        assertEquals(initial.protectionSignals.sortedBy { it.signalId }, saved.protectionSignals)
        assertEquals(initial.routeIndicators, saved.routeIndicators)
        assertEquals(initial.attachedSignals, saved.attachedSignals)
        assertEquals(handles.size, models.handles.size)
        handles.zip(models.handles).forEach { (before, after) -> assertSame(before, after); assertEquals(0, before.closeCalls) }
        assertTrue(runtime.authoringPublication().routeTables.unavailableRegionIds().isEmpty())
        assertEquals(saved, (store.load(f.worldId) as work.art1st.javarailways.simplesignals.persistence.SimpleSignalsWorldDefinitionLoadResult.Loaded).definitions)
    }

    @Test fun `re enclosing moved boundary removes invalid route unbinds devices and regenerates usable coverage`() {
        val f = Fixture()
        val r = RecordingFixture(f, tables = listOf(f.table(1, f.lowerRoute())))
        val initial = r.runtime.currentDefinitions()
        val fixed = ProtectionSignalDefinition("test:fixed", 0, ProtectionSignalKind.FIXED_ROUTE, "test:lower", 0.0, "automatic:block")
        val current = ProtectionSignalDefinition("test:current", 0, ProtectionSignalKind.CURRENT_ENTRY, "test:entry", 0.0, "automatic:block")
        val indicator = RouteIndicatorDefinition("test:indicator", 0, "test:entry", "none", mapOf("test:lower" to "go"))
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED, r.manager.applyDefinitionPatch(f.worldId,
            initial.definitionRevision, SimpleSignalsDefinitionPatch(upsertProtectionSignals = listOf(fixed, current),
                upsertRouteIndicators = listOf(indicator))).status)
        val backend = RuntimeSectionWandBackend(r.manager, r.infrastructure.service, 1000.0)
        backend.perform(backend.capture(f.worldId)!!, SectionWandTool.DELETE, false,
            f.region.boundaries.first().position.cutPosition(), true) { true }
        // Beyond boundary snapping range: this is a genuinely new endpoint with a new ID.
        val outcome = backend.perform(backend.capture(f.worldId)!!, SectionWandTool.CREATE, false,
            RailCutPosition(f.entry, 3.0), true) { true }
        assertTrue(outcome.text.render(java.util.Locale.US).contains("repaired"), outcome.text.render(java.util.Locale.US))
        val saved = r.runtime.currentDefinitions()
        assertNull(saved.regions.single().boundaryDraft)
        assertEquals(f.region.regionId, saved.regions.single().regionId)
        assertTrue(saved.routeTables.single().routes.isNotEmpty())
        assertTrue(saved.routeTables.single().routes.none { it.routeId == "test:lower" || it.entryBoundaryId == "test:entry" || it.exitBoundaryId == "test:entry" })
        assertTrue(saved.protectionSignals.all { it.bindingId == null && it.revision == 1L })
        assertNull(saved.routeIndicators.single().entryBoundaryId)
        assertTrue(saved.routeIndicators.single().modelVariantsByRouteId.isEmpty())
        assertTrue(r.runtime.authoringPublication().routeTables.unavailableRegionIds().isEmpty())
        val overlay = RuntimeSectionOverlayBackend(r.manager::authoringRuntime, r.infrastructure.service, fixtureRailTargets(), work.art1st.javarailways.bukkit.api.diagnostics.WorldIssueService())
        val drawing = overlay.prepare(overlay.capture(f.worldId)!!)
        assertTrue(drawing.labels.any { it.id().startsWith("region/${f.region.regionId}/0/") },
            "Repaired saved paths must produce internal coverage again")
        assertEquals(saved, (r.store.load(f.worldId) as work.art1st.javarailways.simplesignals.persistence.SimpleSignalsWorldDefinitionLoadResult.Loaded).definitions)
    }

    @Test fun `formal stale routes need explicit repair and never reuse a removed route identity`() {
        val f = Fixture()
        val r = RecordingFixture(f)
        val discovered = ProtectionRouteDiscovery.discover(r.topology, f.region,
            r.runtime.protectionRegionSnapshot().regionsById.getValue(f.region.regionId).interior,
            listOf(f.entry, f.upperIn, f.lowerIn, f.upperOut, f.lowerOut, f.exit).associateWith { null })
        val generated = discovered.routes.first()
        val broken = ProtectionRouteDefinition(generated.routeId, generated.entryBoundaryId, generated.exitBoundaryId,
            f.discontinuousRoute().traversals)
        // Historical table targets an earlier region revision, as occurs after old boundary repair.
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED, r.manager.applyDefinitionPatch(f.worldId, 1,
            SimpleSignalsDefinitionPatch(upsertRouteTables = listOf(ProtectionRouteTableDefinition("test:old", 0,
                f.region.regionId, 0, listOf(broken))))).status)
        val backend = RuntimeSectionWandBackend(r.manager, r.infrastructure.service, 1000.0)
        val before = r.runtime.authoringPublication()
        val disk = Files.readString(r.store.primaryFile(f.worldId))
        val selection = backend.prepareRepair(backend.capture(f.worldId)!!, RailCutPosition(f.upperIn, 5.0))!!
        assertSame(before, r.runtime.authoringPublication())
        assertEquals(disk, Files.readString(r.store.primaryFile(f.worldId)))
        assertTrue(selection.text.render(java.util.Locale.US).contains("remove 1 invalid routes"))
        assertFalse(selection.text.render(java.util.Locale.US).contains("{0}"))
        val repaired = backend.repair(selection) { true }
        assertTrue(repaired.text.render(java.util.Locale.US).contains("repaired"), repaired.text.render(java.util.Locale.US))
        val saved = r.runtime.currentDefinitions()
        assertTrue(saved.routeTables.single().routes.none { it.routeId == generated.routeId })
        assertTrue(r.runtime.authoringPublication().routeTables.unavailableRegionIds().isEmpty())
        assertNull(backend.prepareRepair(backend.capture(f.worldId)!!, RailCutPosition(f.upperIn, 5.0)))
        assertThrows(IllegalArgumentException::class.java) { backend.repair(selection) { true } }
    }

    @Test fun `prepared repair failures retain exact publication disk and host sources`() {
        val r = RecordingFixture()
        val backend = RuntimeSectionWandBackend(r.manager, r.infrastructure.service, 1000.0)
        val selected = backend.prepareRepair(backend.capture(r.f.worldId)!!, RailCutPosition(r.f.upperIn, 5.0))!!
        val before = r.runtime.authoringPublication()
        val disk = Files.readString(r.store.primaryFile(r.f.worldId))
        r.infrastructure.nextEditDecision = TopologyEditDecision(TopologyEditStatus.CONFLICT, emptyList(), "blocked")
        assertTrue(backend.repair(selected) { true }.text.render(java.util.Locale.US).contains("EDIT_BLOCKED"))
        assertSame(before, r.runtime.authoringPublication())
        assertEquals(disk, Files.readString(r.store.primaryFile(r.f.worldId)))
        r.infrastructure.nextEditDecision = TopologyEditDecision(TopologyEditStatus.GRANTED)
        r.sources.nextPrevalidationFailure = IllegalArgumentException("source rejected")
        assertTrue(backend.repair(selected) { true }.text.render(java.util.Locale.US).contains("VALIDATION_REJECTED"))
        assertSame(before, r.runtime.authoringPublication())
        assertEquals(disk, Files.readString(r.store.primaryFile(r.f.worldId)))
        var active = true
        r.sources.beforeCommitAction = { active = false }
        assertTrue(backend.repair(selected) { active }.text.render(java.util.Locale.US).contains("WORLD_UNAVAILABLE"))
        assertSame(before, r.runtime.authoringPublication())
        assertEquals(disk, Files.readString(r.store.primaryFile(r.f.worldId)))
        r.sources.beforeCommitAction = {}
        r.manager.remove(r.f.worldId)
        val failing = SimpleSignalsWorldDefinitionStore(directory.toFile()) { throw java.io.IOException("injected repair save failure") }
        val manager = runtimeManager(plugin(), r.infrastructure.service, r.routes, failing, r.sources)
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(r.f.worldId).status)
        val restarted = RuntimeSectionWandBackend(manager, r.infrastructure.service, 1000.0)
        val retained = manager.authoringRuntime(r.f.worldId)!!.authoringPublication()
        val repair = restarted.prepareRepair(restarted.capture(r.f.worldId)!!, RailCutPosition(r.f.upperIn, 5.0))!!
        assertTrue(restarted.repair(repair) { true }.text.render(java.util.Locale.US).contains("PERSISTENCE_FAILED"))
        assertSame(retained, manager.authoringRuntime(r.f.worldId)!!.authoringPublication())
        assertEquals(disk, Files.readString(r.store.primaryFile(r.f.worldId)))
        // The denied first request increments attempts but never returns an owned permit.
        assertEquals(r.infrastructure.permitAcquisitionCount - 1, r.infrastructure.permitCloseCount)
    }

    @Test fun `existing multi row pair exact path remains no op and different path refuses arbitrary deletion`() {
        val f = Fixture()
        val table = ProtectionRouteTableDefinition("test:multiple", 1, f.region.regionId, f.region.revision,
            listOf(f.upperRoute(), f.lowerRoute()))
        val r = RecordingFixture(f, tables = listOf(table))
        val same = r.capture()
        assertTrue(r.recorder.record(same.first, same.second) { true }.render(java.util.Locale.US).contains("already recorded"))
        // Third physically legal route, not one of the two rows.
        r.active = ActiveRailContinuationsSnapshot.create(r.topology.revision, 3, mapOf(
            endpoint(f.entry, RailEndpoint.B) to endpoint(f.upperIn, RailEndpoint.A),
            endpoint(f.upperIn, RailEndpoint.B) to endpoint(f.lowerOut, RailEndpoint.A),
            endpoint(f.lowerOut, RailEndpoint.B) to endpoint(f.exit, RailEndpoint.A)))
        val third = r.capture()
        val before = r.runtime.authoringPublication()
        val disk = Files.readString(r.store.primaryFile(f.worldId))
        val failure = assertThrows(IllegalArgumentException::class.java) { r.recorder.record(third.first, third.second) { true }.render(java.util.Locale.US) }
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
        val message = r.recorder.record(selection, preview) { true }.render(java.util.Locale.US)
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
        assertThrows(IllegalArgumentException::class.java) { r.recorder.record(selection, preview) { true }.render(java.util.Locale.US) }
    }

    @Test fun `duplicate recording leaves revision file backup sources and permits unchanged`() {
        val r = RecordingFixture()
        var capture = r.capture()
        r.recorder.record(capture.first, capture.second) { true }.render(java.util.Locale.US)
        capture = r.capture()
        val before = r.runtime.authoringPublication()
        val file = Files.readString(r.store.primaryFile(r.f.worldId))
        val backup = Files.readString(r.store.backupFile(r.f.worldId))
        val sources = r.sources.sources.getValue(r.f.worldId)
        val permits = r.infrastructure.permitAcquisitionCount
        val result = r.recorder.record(capture.first, capture.second) { true }.render(java.util.Locale.US)
        assertFalse(result.contains(before.definitions.routeTables.single().routes.single().routeId),
            "ordinary authoring feedback must hide generated route UUIDs")
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
        r.recorder.record(first.first, first.second) { true }.render(java.util.Locale.US)
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
        assertTrue(r.recorder.record(next.first, next.second) { true }.render(java.util.Locale.US).contains("Recorded region"))
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
        assertTrue(r.recorder.record(selection, value) { true }.render(java.util.Locale.US).contains("Recorded region"))
        assertEquals(curves, r.curveReads)
        assertEquals(value.path.traversals, r.runtime.currentDefinitions().routeTables.single().routes.single().traversals)
    }

    @Test fun `turnout change before capture is stale while change after freeze preserves captured path`() {
        val r = RecordingFixture()
        val capture = r.capture()
        r.lower()
        assertThrows(IllegalArgumentException::class.java) { r.recorder.record(capture.first, capture.second) { true }.render(java.util.Locale.US) }
        r.upper()
        val frozen = r.capture()
        val curves = r.curveReads
        r.sources.beforeCommitAction = { r.lower() }
        assertTrue(r.recorder.record(frozen.first, frozen.second) { true }.render(java.util.Locale.US).contains("Recorded region"))
        assertEquals(frozen.second.path.traversals, r.runtime.currentDefinitions().routeTables.single().routes.single().traversals)
        assertEquals(curves, r.curveReads)
    }

    @Test fun `missing unsuccessful unknown region and cancelled previews never save`() {
        val r = RecordingFixture()
        r.active = ActiveRailContinuationsSnapshot.empty(r.topology.revision)
        val incomplete = r.capture()
        assertEquals(CurrentRouteEnd.UNSELECTED, incomplete.second.path.end)
        assertThrows(IllegalArgumentException::class.java) { r.recorder.record(incomplete.first, incomplete.second) { true }.render(java.util.Locale.US) }
        r.upper()
        val capture = r.capture()
        assertThrows(IllegalArgumentException::class.java) { r.recorder.record(capture.first, capture.second) { false }.render(java.util.Locale.US) }
        assertThrows(IllegalArgumentException::class.java) {
            r.recorder.record(capture.first.copy(regionId = "test:unknown"), capture.second) { true }.render(java.util.Locale.US)
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
            val result = r.recorder.record(capture.first, capture.second) { true }.render(java.util.Locale.US)
            assertTrue(result.contains("EDIT_BLOCKED"), result)
            assertTrue(result.contains(kind.name), result)
            assertSame(before, r.runtime.authoringPublication())
            assertSame(sources, r.sources.sources.getValue(f.worldId))
            assertEquals(disk, Files.readString(r.store.primaryFile(f.worldId)))
            release()
        }
        assertTrue(r.recorder.record(capture.first, capture.second) { true }.render(java.util.Locale.US).contains("Recorded region"))
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
        val result = r.recorder.record(capture.first, capture.second) { true }.render(java.util.Locale.US)
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
        assertTrue(r.recorder.record(capture.first, capture.second) { active }.render(java.util.Locale.US).contains("WORLD_UNAVAILABLE"))
        assertSame(before, r.runtime.authoringPublication())
        assertEquals(disk, Files.readString(r.store.primaryFile(r.f.worldId)))
        r.sources.beforeCommitAction = {}
        r.sources.nextPrevalidationFailure = IllegalArgumentException("source rejected")
        assertTrue(r.recorder.record(capture.first, capture.second) { true }.render(java.util.Locale.US).contains("VALIDATION_REJECTED"))
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
        assertTrue(result.render(java.util.Locale.US).contains("PERSISTENCE_FAILED"), result.render(java.util.Locale.US))
        assertSame(retained, manager.authoringRuntime(r.f.worldId)!!.authoringPublication())
        assertEquals(disk, Files.readString(r.store.primaryFile(r.f.worldId)))
        assertEquals(r.infrastructure.permitAcquisitionCount, r.infrastructure.permitCloseCount)
    }

    @Test fun `whole region deletion confirms an exact retained interior and atomically retains or cascades devices`() {
        for (retain in listOf(true, false)) {
            val f = Fixture()
            val region = if (retain) ProtectionRegionDefinition(f.region.regionId, f.region.revision,
                f.region.boundaries, emptyList()) else f.region
            val route = f.upperRoute()
            val model = DeviceModelDefinition("demo_signals:home", ModelPlacement.Ground(ModelPose.IDENTITY))
            val fixed = ProtectionSignalDefinition("test:route-signal", 1, ProtectionSignalKind.FIXED_ROUTE,
                route.routeId, 0.0, "automatic:block", model.placement)
            val current = fixed.copy(signalId = "test:entry-signal", kind = ProtectionSignalKind.CURRENT_ENTRY,
                bindingId = route.entryBoundaryId)
            val unbound = fixed.copy(signalId = "test:already-unbound", bindingId = null)
            val indicator = RouteIndicatorDefinition("test:indicator", 1, route.entryBoundaryId,
                "stop", mapOf(route.routeId to "proceed"), model)
            val attached = AttachedSignalDefinition("test:repeat", 1, fixed.signalId, model)
            val block = FixedBlockDefinition("test:neighbour", 1, listOf(FixedBlockTraversal(f.entry, ViewDirection.AB, 0.0, 4.0)))
            val neighbour = FixedBlockSignalDefinition("test:neighbour-signal", 1, block.blockId, 0.0, "automatic:block")
            val neighbours = if (retain) listOf(block) else emptyList()
            val neighbourSignals = if (retain) listOf(neighbour) else emptyList()
            val alreadyUnbound = if (retain) listOf(unbound) else emptyList()
            val initial = SimpleSignalsWorldDefinitions(f.worldId, 1, listOf(region), listOf(f.table(1, route)),
                neighbours, neighbourSignals, listOf(fixed, current) + alreadyUnbound, listOf(indicator), listOf(attached))
            val definitions = store().also { it.save(f.worldId, initial) }
            val registry = editRegistry { f.currentTopology }
            val infrastructure = MutableTopologyService(f.currentTopology, registry)
            val sources = CapturingSignalEpochSourceService()
            val models = RecordingModels()
            val manager = runtimeManager(plugin(), infrastructure.service, CapturingRoutePlanningService(), definitions, sources,
                modelPresentation = models)
            assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(f.worldId).status)
            val runtime = manager.authoringRuntime(f.worldId)!!
            val display = models.byId("route-indicator/${indicator.indicatorId}")
            assertEquals(mapOf("lamp" to "red"), display.submissions.last().animationScenes())
            assertFalse(runtime.routeIndicatorStates().getValue(indicator.indicatorId).selection!!.available)
            assertEquals("stop", runtime.routeIndicatorStates().getValue(indicator.indicatorId).modelVariant)
            fun baseline(epoch: Long) {
                protectionTestEpoch(runtime, f.worldId.toString(), TrainRestrictionSnapshotStore(f.worldId.toString())).also {
                    it.replaceSources(sources.sources.getValue(f.worldId))
                    it.process(RailwayWorldFrameSnapshot.create(epoch, f.currentTopology.revision, emptyList(),
                        ActiveRailContinuationsSnapshot.empty(f.currentTopology.revision)))
                }
            }
            baseline(1)
            assertEquals(setOf(indicator.indicatorId), runtime.routeIndicatorStates().keys)
            assertNotNull(runtime.routeIndicatorStates().getValue(indicator.indicatorId).selection)
            val backend = RuntimeSectionWandBackend(manager, infrastructure.service, 1000.0)
            val selection = backend.prepareDeletion(backend.capture(f.worldId)!!, RailCutPosition(f.upperIn, 5.0), retain)!!
            val publication = runtime.authoringPublication()
            val disk = Files.readString(definitions.primaryFile(f.worldId))
            val oldSources = sources.sources.getValue(f.worldId)
            val release = protect(registry, f.currentTopology, f.lowerIn, TopologyEditBlockerKind.LIVE_RUNTIME_CLAIM)
            try { assertTrue(backend.delete(selection) { true }.text.render(java.util.Locale.US).contains("EDIT_BLOCKED")) }
            finally { release() }
            assertSame(publication, runtime.authoringPublication())
            assertEquals(disk, Files.readString(definitions.primaryFile(f.worldId)))
            assertEquals(oldSources, sources.sources.getValue(f.worldId))
            val deleted = backend.delete(selection) { true }
            assertEquals(SectionWandKind.REGION, deleted.kind, deleted.text.render(java.util.Locale.US))
            val saved = runtime.currentDefinitions()
            assertTrue(saved.regions.isEmpty())
            assertTrue(saved.routeTables.isEmpty())
            assertEquals(neighbours, saved.fixedBlocks)
            assertEquals(neighbourSignals, saved.fixedBlockSignals)
            assertEquals(if (retain) listOf(fixed.copy(revision = 2, bindingId = null),
                current.copy(revision = 2, bindingId = null), unbound).sortedBy { it.signalId }
                else emptyList<ProtectionSignalDefinition>(), saved.protectionSignals)
            assertEquals(if (retain) listOf(attached) else emptyList<AttachedSignalDefinition>(), saved.attachedSignals)
            if (retain) {
                assertEquals(listOf(RouteIndicatorDefinition(indicator.indicatorId, 2, null, "stop", emptyMap(), model)), saved.routeIndicators)
                assertNull(runtime.routeIndicatorStates().getValue(indicator.indicatorId).selection)
                assertEquals("stop", runtime.routeIndicatorStates().getValue(indicator.indicatorId).modelVariant)
                assertFalse(display.closed)
                assertEquals(mapOf("lamp" to "red"), display.submissions.last().animationScenes())
            } else {
                assertTrue(saved.routeIndicators.isEmpty())
                assertTrue(runtime.routeIndicatorStates().isEmpty())
                assertTrue(display.closed)
            }
            baseline(2)
            if (retain) assertNull(runtime.routeIndicatorStates().getValue(indicator.indicatorId).selection)
            else assertTrue(runtime.routeIndicatorStates().isEmpty())
            assertThrows(IllegalArgumentException::class.java) { backend.delete(selection) { true } }
            models.cleanup.complete(null)
            manager.clearLocal().toCompletableFuture().join()
            val reloaded = runtimeManager(plugin(), infrastructure.service, CapturingRoutePlanningService(), definitions,
                CapturingSignalEpochSourceService())
            assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, reloaded.install(f.worldId).status)
            assertEquals(saved, reloaded.authoringRuntime(f.worldId)!!.currentDefinitions())
            reloaded.clearLocal().toCompletableFuture().join()
        }
    }

    /** Real preview/tracer + manager/store + Basic permit/planner, with host observations provided as values. */
    private inner class RecordingFixture(val f: Fixture = Fixture(), registry: InfrastructureCoordinationRegistry? = null,
        tables: List<ProtectionRouteTableDefinition> = emptyList(), indicators: List<RouteIndicatorDefinition> = emptyList()) {
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
            store.save(f.worldId, SimpleSignalsWorldDefinitions(f.worldId, 1, listOf(f.region), tables, routeIndicators = indicators))
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
        assertEquals(emptyList<ProtectionBoundaryCut>(), reloaded.currentDefinitions().regions.single().boundaryDraft)
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
        val first = ProtectionBoundaryCut("test:pending-first", RailCutPosition(fixture.entry, 3.0))
        val second = ProtectionBoundaryCut("test:pending-second", RailCutPosition(fixture.exit, 7.0))
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
        val moved = ProtectionBoundaryCut(second.boundaryId, RailCutPosition(fixture.exit, 8.0))
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
        val pending = ProtectionBoundaryCut("test:pending", RailCutPosition(fixture.exit, 7.0))
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
        val moved = ProtectionBoundaryCut(pending.boundaryId, RailCutPosition(fixture.exit, 8.0))
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
        val edits = listOf(1.0, 2.0).map { at -> CompletableFuture.supplyAsync({
            manager.applyDefinitionPatch(fixture.worldId, 0, definitionDraftPatch(current,
                deviceModelDefinitions(fixture, "fixed", railModel(fixture.entry, at))))
        }, concurrentExecutor) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
        } finally { release.countDown() }
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
        val issues = work.art1st.javarailways.bukkit.api.diagnostics.WorldIssueService()
        val manager = SimpleSignalsWorldRuntimeManager(plugin(), MutableTopologyService(fixture.currentTopology).service,
            CapturingRoutePlanningService(), store, signalSources(), inspectionSources(), fixtureRailTargets(), models,
            { it() }, { context, _ -> errors += context }, worldIssues = issues)
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(fixture.worldId).status)
        val packages = directory.resolve("models")
        Files.createDirectories(packages.resolve("broken"))
        manager.loadModelPackages(packages)
        assertEquals(1, errors.size)
        assertTrue(errors.single().contains("rendering unavailable"))
        assertTrue(models.handles.isEmpty())
        assertTrue(issues.snapshot(fixture.worldId).any { it.objectId == "test:device" && it.code == "model-preparation-failed" })
        assertNotNull(manager.fixedBlockSnapshot(fixture.worldId))
        val backend = deviceModelDefinitions(fixture, "fixed", null, 2)
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED,
            manager.applyDefinitionPatch(fixture.worldId, 1, definitionDraftPatch(initial, backend)).status)
        assertTrue(models.handles.isEmpty())
        assertFalse(issues.snapshot(fixture.worldId).any { it.source == "simplesignals:model-preparation" })
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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = [false, true])
    fun `signal wand backend updates type revision placement persistence JS and model through one manager patch`(useRailPreset: Boolean) {
        val fixture = Fixture()
        val block = FixedBlockDefinition("test:wand-block", 1,
            listOf(FixedBlockTraversal(fixture.entry, ViewDirection.AB, 0.0, 4.0)))
        val originalPlacement = ModelPlacement.Ground(ModelPose(3.0, 64.0, 4.0, 0.0, 0.0, 0.0, 1.0))
        val signal = FixedBlockSignalDefinition("test:wand-device", 1, block.blockId, 2.0,
            "automatic:block", placement = originalPlacement)
        val retainedBlock = FixedBlockDefinition("test:wand-retained", 1,
            listOf(FixedBlockTraversal(fixture.entry, ViewDirection.AB, 4.0, 8.0)))
        val retainedSignal = FixedBlockSignalDefinition("test:wand-retained-device", 1, retainedBlock.blockId, 0.0,
            "automatic:block", placement = originalPlacement)
        val definitions = store()
        definitions.save(fixture.worldId, SimpleSignalsWorldDefinitions(fixture.worldId, 1,
            emptyList(), emptyList(), listOf(block, retainedBlock), listOf(signal, retainedSignal)))
        val infrastructure = MutableTopologyService(fixture.currentTopology)
        val sources = CapturingSignalEpochSourceService()
        val models = RecordingModels()
        val manager = runtimeManager(plugin(), infrastructure.service, CapturingRoutePlanningService(), definitions,
            sources, modelPresentation = models, loadDefaultSignalCatalog = false)
        val packRoot = writeAutomaticSignalPack()
        val slowModel = packRoot.resolve("automatic/model/slow-head.yml")
        Files.writeString(slowModel, Files.readString(slowModel) + """

            rail-placement:
              follow-rail-tilt: true
              presets:
                right:
                  offset: {y: 1.0, z: 2.0}
                  rotation: {y: 180.0}
        """.trimIndent().let { "\n$it\n" })
        manager.loadSignalPacks(packRoot)
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(fixture.worldId).status)
        val backend = RuntimeSignalWandBackend(manager, infrastructure.service)
        assertEquals(listOf("automatic:block", "automatic:slow"),
            backend.availableSignalTypes()!!.map { it.signalType })
        val capture = backend.capture(fixture.worldId, "automatic:slow")
        val position = RailPositionSnapshot(fixture.entry, ViewDirection.AB, 0.25)
        val railModel = checkNotNull(backend.railPlacementModel(SignalWandControlKind.FIXED_BLOCK,
            "automatic:slow", "demo_signals:home"))
        assertEquals("automatic:slow-head", railModel.modelId)
        assertEquals(listOf("right"), railModel.presets.keys.toList())
        val copiedPreset = railModel.presets.getValue("right")
        assertEquals(true, copiedPreset.followRailTilt)
        val railPlacement = ModelPlacement.RailAttached(RailPositionSnapshot(fixture.entry, ViewDirection.BA, 2.0))
            .applyPreset(copiedPreset)
        assertEquals(2.0, railPlacement.adjustment.z())
        assertTrue(railPlacement.followRailTilt)
        val placement = if (useRailPreset) railPlacement else
            ModelPlacement.Ground(ModelPose(9.0, 65.0, 4.0, 0.0, 0.0, 0.0, 1.0))

        val binding = backend.resolveEntrance(capture, SignalWandControlKind.FIXED_BLOCK,
            SignalWandMode.PLACEMENT, position).single().binding
        val outcome = backend.place(capture, binding, placement) { true }

        assertTrue(outcome.text.render(java.util.Locale.US).contains("Updated signal"), outcome.text.render(java.util.Locale.US))
        val live = manager.authoringRuntime(fixture.worldId)!!.currentDefinitions()
        val edited = live.fixedBlockSignals.single { it.signalId == signal.signalId }
        assertEquals(2, live.definitionRevision)
        assertEquals(2, edited.revision)
        assertEquals("automatic:slow", edited.signalType)
        assertEquals(placement, edited.placement)
        assertEquals(signal.controlledBlockId, edited.controlledBlockId)
        assertEquals(signal.entryDirection, edited.entryDirection)
        assertEquals(signal.inspectionLeadTimeSeconds, edited.inspectionLeadTimeSeconds)
        assertEquals(retainedSignal, live.fixedBlockSignals.single { it.signalId == retainedSignal.signalId })
        assertEquals(listOf(block, retainedBlock), live.fixedBlocks)
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
        assertEquals(25.0, manager.authoringRuntime(fixture.worldId)!!.sectionSignalSnapshot()!!.states
            .getValue(retainedSignal.signalId).effectiveSpeedLimitMetersPerSecond)
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
        val outcome = backend.place(capture, backend.resolveEntrance(capture,
            SignalWandControlKind.FIXED_BLOCK, SignalWandMode.PLACEMENT, position).single().binding,
            position) { true }

        assertTrue(outcome.text.render(java.util.Locale.US).contains("active route uses this rail"), outcome.text.render(java.util.Locale.US))
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

        val position = RailPositionSnapshot(fixture.entry, ViewDirection.AB, 0.25)
        val binding = backend.resolveEntrance(capture, SignalWandControlKind.FIXED_BLOCK,
            SignalWandMode.DELETION, position).single().binding
        val outcome = backend.delete(capture, binding) { true }

        assertTrue(outcome.text.render(java.util.Locale.US).contains("1 attached device"), outcome.text.render(java.util.Locale.US))
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
        val binding = backend.resolveEntrance(capture, SignalWandControlKind.FIXED_BLOCK,
            SignalWandMode.PLACEMENT, entrance).single().binding

        val outcome = backend.place(capture, binding, entrance) { true }

        assertTrue(outcome.text.render(java.util.Locale.US).contains("Created signal test:generated-signal"), outcome.text.render(java.util.Locale.US))
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
        val binding = backend.resolveEntrance(capture, SignalWandControlKind.FIXED_BLOCK,
            SignalWandMode.PLACEMENT, entrance).single().binding
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
        val binding = backend.resolveEntrance(capture, SignalWandControlKind.FIXED_BLOCK,
            SignalWandMode.PLACEMENT, entrance).single().binding
        val publication = manager.authoringRuntime(fixture.worldId)!!.authoringPublication()
        val disk = Files.readString(definitions.primaryFile(fixture.worldId))
        infrastructure.nextEditDecision = TopologyEditDecision(TopologyEditStatus.CONFLICT,
            detail = "active route uses this rail")

        val outcome = backend.place(capture, binding, entrance) { true }

        assertTrue(outcome.text.render(java.util.Locale.US).contains("active route uses this rail"), outcome.text.render(java.util.Locale.US))
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
    fun `signal wand updates creates and exactly deletes multiple current-entry protection primaries`() {
        val fixture = Fixture()
        val table = ProtectionRouteTableDefinition("test:wand-current-table", 1, fixture.region.regionId,
            fixture.region.revision, listOf(fixture.upperRoute()))
        val first = ProtectionSignalDefinition("test:current-a", 2, ProtectionSignalKind.CURRENT_ENTRY,
            "test:entry", 3.0, "automatic:block")
        val second = ProtectionSignalDefinition("test:current-b", 4, ProtectionSignalKind.CURRENT_ENTRY,
            "test:entry", 7.0, "automatic:block")
        val initial = SimpleSignalsWorldDefinitions(fixture.worldId, 1, listOf(fixture.region), listOf(table),
            protectionSignals = listOf(first, second))
        val definitions = store().also { it.save(fixture.worldId, initial) }
        val infrastructure = MutableTopologyService(fixture.currentTopology)
        val models = RecordingModels()
        val manager = runtimeManager(plugin(), infrastructure.service, CapturingRoutePlanningService(), definitions,
            CapturingSignalEpochSourceService(), modelPresentation = models, loadDefaultSignalCatalog = false)
        manager.loadSignalPacks(writeAutomaticSignalPack("proceed"))
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(fixture.worldId).status)
        val generatedId = "test:current-created"
        val backend = RuntimeSignalWandBackend(manager, infrastructure.service) { generatedId }
        val entrance = fixture.region.boundaries.first().position
        val placement = ModelPlacement.Ground(ModelPose(8.0, 66.0, 3.0, 0.0, 0.0, 0.0, 1.0))

        var capture = backend.capture(fixture.worldId, "automatic:block")
        var choices = backend.resolveEntrance(capture, SignalWandControlKind.PROTECTION_CURRENT_ENTRY,
            SignalWandMode.PLACEMENT, entrance)
        assertEquals(listOf("test:current-a", "test:current-b"), choices.drop(1).map {
            ((it.binding as ProtectionCurrentEntrySignalWandBinding).intent as ExistingProtectionSignal).signalId })
        val staleExactBinding = choices.last().binding
        val updateOutcome = backend.place(capture, staleExactBinding, placement) { true }
        assertTrue(updateOutcome.text.render(java.util.Locale.US).contains("Updated"), updateOutcome.text.render(java.util.Locale.US))
        var live = manager.authoringRuntime(fixture.worldId)!!.currentDefinitions()
        assertEquals(first, live.protectionSignals.single { it.signalId == first.signalId })
        val updated = live.protectionSignals.single { it.signalId == second.signalId }
        assertEquals(5, updated.revision)
        assertEquals(7.0, updated.inspectionLeadTimeSeconds)
        assertEquals("automatic:block", updated.signalType)
        assertEquals(placement, updated.placement)
        assertEquals("automatic:head", manager.authoringRuntime(fixture.worldId)!!.currentModels()!!.prepared
            .signals.getValue(second.signalId).single().model.id)

        capture = backend.capture(fixture.worldId, "automatic:block")
        val beforeStaleAttempt = manager.authoringRuntime(fixture.worldId)!!.authoringPublication()
        val staleFailure = assertThrows(IllegalArgumentException::class.java) {
            backend.place(capture, staleExactBinding, placement) { true }
        }
        assertTrue(staleFailure.message!!.contains("revision changed"), staleFailure.message)
        assertSame(beforeStaleAttempt, manager.authoringRuntime(fixture.worldId)!!.authoringPublication())

        capture = backend.capture(fixture.worldId, "automatic:block")
        choices = backend.resolveEntrance(capture, SignalWandControlKind.PROTECTION_CURRENT_ENTRY,
            SignalWandMode.PLACEMENT, entrance)
        backend.place(capture, choices.first().binding, placement) { true }
        live = manager.authoringRuntime(fixture.worldId)!!.currentDefinitions()
        val created = live.protectionSignals.single { it.signalId == generatedId }
        assertEquals(0L, created.revision)
        assertEquals(0.0, created.inspectionLeadTimeSeconds)
        assertEquals(ProtectionSignalKind.CURRENT_ENTRY, created.kind)
        assertEquals("test:entry", created.bindingId)

        capture = backend.capture(fixture.worldId, "automatic:block")
        choices = backend.resolveEntrance(capture, SignalWandControlKind.PROTECTION_CURRENT_ENTRY,
            SignalWandMode.DELETION, entrance)
        val firstChoice = choices.single {
            ((it.binding as ProtectionCurrentEntrySignalWandBinding).intent as ExistingProtectionSignal).signalId == first.signalId
        }
        backend.delete(capture, firstChoice.binding) { true }
        live = manager.authoringRuntime(fixture.worldId)!!.currentDefinitions()
        assertEquals(setOf(second.signalId, generatedId), live.protectionSignals.map { it.signalId }.toSet())
        assertEquals(live, (definitions.load(fixture.worldId) as
            work.art1st.javarailways.simplesignals.persistence.SimpleSignalsWorldDefinitionLoadResult.Loaded).definitions)
        models.cleanup.complete(null)
        manager.clearLocal().toCompletableFuture().join()
    }

    @Test
    fun `fixed-route protection chooser creates per usable authored route and preserves exact existing binding`() {
        val fixture = Fixture()
        val upper = fixture.upperRoute()
        val lower = fixture.lowerRoute()
        val table = ProtectionRouteTableDefinition("test:wand-fixed-table", 1, fixture.region.regionId,
            fixture.region.revision, listOf(upper, lower))
        val existing = ProtectionSignalDefinition("test:fixed-existing", 6, ProtectionSignalKind.FIXED_ROUTE,
            upper.routeId, 11.0, "automatic:block")
        val definitions = store().also { it.save(fixture.worldId, SimpleSignalsWorldDefinitions(fixture.worldId, 1,
            listOf(fixture.region), listOf(table), protectionSignals = listOf(existing),
                boundaryNames = fixture.region.boundaries.map { boundary ->
                    work.art1st.javarailways.simplesignals.persistence.BoundaryName(boundary.position.cutPosition(),
                        if (boundary.boundaryId == upper.entryBoundaryId) "西端" else "出口")
                })) }
        val infrastructure = MutableTopologyService(fixture.currentTopology)
        val manager = runtimeManager(plugin(), infrastructure.service, CapturingRoutePlanningService(), definitions,
            CapturingSignalEpochSourceService(), loadDefaultSignalCatalog = false)
        manager.loadSignalPacks(writeAutomaticSignalPack())
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(fixture.worldId).status)
        val backend = RuntimeSignalWandBackend(manager, infrastructure.service) { "test:fixed-created" }
        val entrance = fixture.region.boundaries.first().position
        val placement = ModelPlacement.Ground(ModelPose(7.0, 67.0, 2.0, 0.0, 0.0, 0.0, 1.0))
        var capture = backend.capture(fixture.worldId, "automatic:block")
        var choices = backend.resolveEntrance(capture, SignalWandControlKind.PROTECTION_FIXED_ROUTE,
            SignalWandMode.PLACEMENT, entrance)
        assertEquals(listOf(lower.routeId, upper.routeId), choices.take(2).map {
            (it.binding as ProtectionFixedRouteSignalWandBinding).routeId })
        choices.forEach { choice ->
            val label = choice.label.render(java.util.Locale.SIMPLIFIED_CHINESE)
            assertTrue(label.contains("西端"), label)
            assertTrue(label.contains("出口"), label)
        }
        backend.place(capture, choices.first {
            (it.binding as ProtectionFixedRouteSignalWandBinding).routeId == lower.routeId
        }.binding, placement) { true }

        capture = backend.capture(fixture.worldId, "automatic:slow")
        choices = backend.resolveEntrance(capture, SignalWandControlKind.PROTECTION_FIXED_ROUTE,
            SignalWandMode.PLACEMENT, entrance)
        val existingChoice = choices.single { choice ->
            ((choice.binding as ProtectionFixedRouteSignalWandBinding).intent as? ExistingProtectionSignal)
                ?.signalId == existing.signalId
        }
        backend.place(capture, existingChoice.binding, placement) { true }
        val live = manager.authoringRuntime(fixture.worldId)!!.currentDefinitions()
        val created = live.protectionSignals.single { it.signalId == "test:fixed-created" }
        assertEquals(lower.routeId, created.bindingId)
        assertEquals(ProtectionSignalKind.FIXED_ROUTE, created.kind)
        assertEquals(placement, created.placement)
        val updated = live.protectionSignals.single { it.signalId == existing.signalId }
        assertEquals(7, updated.revision)
        assertEquals(upper.routeId, updated.bindingId)
        assertEquals(11.0, updated.inspectionLeadTimeSeconds)
        assertEquals("automatic:slow", updated.signalType)
        assertEquals(placement, updated.placement)

        val runtime = manager.authoringRuntime(fixture.worldId)!!
        val unavailableRegion = ProtectionRegionDefinition(fixture.region.regionId, 2,
            fixture.region.boundaries, fixture.region.boundaries.map { it.cut() })
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED, manager.applyDefinitionPatch(
            fixture.worldId, live.definitionRevision,
            SimpleSignalsDefinitionPatch(upsertRegions = listOf(unavailableRegion)),
            runtime, fixture.currentTopology
        ).status)
        capture = backend.capture(fixture.worldId, "automatic:block")
        choices = backend.resolveEntrance(capture, SignalWandControlKind.PROTECTION_FIXED_ROUTE,
            SignalWandMode.PLACEMENT, entrance)
        assertTrue(choices.all { (it.binding as ProtectionFixedRouteSignalWandBinding).intent is ExistingProtectionSignal })
        assertTrue(choices.all { it.label.render(java.util.Locale.US).contains("unavailable") })
        val unavailableNew = ProtectionFixedRouteSignalWandBinding(fixture.worldId, "test:entry",
            lower.routeId, NewProtectionSignal)
        assertTrue(assertThrows(IllegalArgumentException::class.java) {
            backend.place(capture, unavailableNew, placement) { true }
        }.message!!.contains("not currently usable"))
        val retainedChoice = choices.single {
            ((it.binding as ProtectionFixedRouteSignalWandBinding).intent as ExistingProtectionSignal).signalId == existing.signalId
        }
        assertTrue(backend.place(capture, retainedChoice.binding, placement) { true }.text.render(java.util.Locale.US).contains("Updated"))

        val finalDefinitions = manager.authoringRuntime(fixture.worldId)!!.currentDefinitions()
        assertEquals(finalDefinitions, (definitions.load(fixture.worldId) as
            work.art1st.javarailways.simplesignals.persistence.SimpleSignalsWorldDefinitionLoadResult.Loaded).definitions)
        manager.clearLocal().toCompletableFuture().join()
    }

    @Test
    fun `protection signal deletion cascades only the exact selected primary attachments`() {
        val fixture = Fixture()
        val table = ProtectionRouteTableDefinition("test:wand-delete-table", 1, fixture.region.regionId,
            fixture.region.revision, listOf(fixture.upperRoute()))
        val selected = ProtectionSignalDefinition("test:protection-delete", 2,
            ProtectionSignalKind.CURRENT_ENTRY, "test:entry", 3.0, "automatic:block")
        val retained = ProtectionSignalDefinition("test:protection-retained", 2,
            ProtectionSignalKind.CURRENT_ENTRY, "test:entry", 3.0, "automatic:block")
        val selectedAttached = AttachedSignalDefinition("test:protection-delete-attached", 1,
            selected.signalId, railModel(fixture.entry))
        val retainedAttached = AttachedSignalDefinition("test:protection-retained-attached", 1,
            retained.signalId, railModel(fixture.entry))
        val definitions = store().also { it.save(fixture.worldId, SimpleSignalsWorldDefinitions(fixture.worldId, 1,
            listOf(fixture.region), listOf(table), protectionSignals = listOf(selected, retained),
            attachedSignals = listOf(selectedAttached, retainedAttached))) }
        val infrastructure = MutableTopologyService(fixture.currentTopology)
        val manager = runtimeManager(plugin(), infrastructure.service, CapturingRoutePlanningService(), definitions,
            CapturingSignalEpochSourceService())
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(fixture.worldId).status)
        val backend = RuntimeSignalWandBackend(manager, infrastructure.service)
        val capture = backend.capture(fixture.worldId, "automatic:block")
        val choices = backend.resolveEntrance(capture, SignalWandControlKind.PROTECTION_CURRENT_ENTRY,
            SignalWandMode.DELETION, fixture.region.boundaries.first().position)
        val selectedChoice = choices.single {
            ((it.binding as ProtectionCurrentEntrySignalWandBinding).intent as ExistingProtectionSignal).signalId == selected.signalId
        }

        assertTrue(backend.delete(capture, selectedChoice.binding) { true }.text.render(java.util.Locale.US).contains("1 attached device"))

        val live = manager.authoringRuntime(fixture.worldId)!!.currentDefinitions()
        assertEquals(listOf(retained), live.protectionSignals)
        assertEquals(listOf(retainedAttached), live.attachedSignals)
        assertEquals(live, (definitions.load(fixture.worldId) as
            work.art1st.javarailways.simplesignals.persistence.SimpleSignalsWorldDefinitionLoadResult.Loaded).definitions)
        manager.clearLocal().toCompletableFuture().join()
    }

    @Test
    fun `protection signal creation rejects protection indicator and attached device ID collisions`() {
        val fixture = Fixture()
        val route = fixture.upperRoute()
        val table = ProtectionRouteTableDefinition("test:wand-collision-table", 1, fixture.region.regionId,
            fixture.region.revision, listOf(route))
        val primary = ProtectionSignalDefinition("test:collision-primary", 1,
            ProtectionSignalKind.CURRENT_ENTRY, "test:entry", 0.0, "automatic:block")
        val indicator = RouteIndicatorDefinition("test:collision-indicator", 1, "test:entry", "none",
            mapOf(route.routeId to "route"))
        val attached = AttachedSignalDefinition("test:collision-attached", 1, primary.signalId,
            railModel(fixture.entry))
        val definitions = store().also { it.save(fixture.worldId, SimpleSignalsWorldDefinitions(fixture.worldId, 1,
            listOf(fixture.region), listOf(table), protectionSignals = listOf(primary),
            routeIndicators = listOf(indicator), attachedSignals = listOf(attached))) }
        val infrastructure = MutableTopologyService(fixture.currentTopology)
        val manager = runtimeManager(plugin(), infrastructure.service, CapturingRoutePlanningService(), definitions,
            CapturingSignalEpochSourceService())
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(fixture.worldId).status)
        val publication = manager.authoringRuntime(fixture.worldId)!!.authoringPublication()
        val entrance = fixture.region.boundaries.first().position
        listOf(primary.signalId, indicator.indicatorId, attached.signalId).forEach { collisionId ->
            val backend = RuntimeSignalWandBackend(manager, infrastructure.service) { collisionId }
            val capture = backend.capture(fixture.worldId, "automatic:block")
            val binding = backend.resolveEntrance(capture, SignalWandControlKind.PROTECTION_CURRENT_ENTRY,
                SignalWandMode.PLACEMENT, entrance).first().binding
            assertTrue(assertThrows(IllegalArgumentException::class.java) {
                backend.place(capture, binding, entrance) { true }
            }.message!!.contains("collides with an existing device"))
            assertSame(publication, manager.authoringRuntime(fixture.worldId)!!.authoringPublication())
        }
        assertEquals(0, infrastructure.permitAcquisitionCount)
        manager.clearLocal().toCompletableFuture().join()
    }

    @Test
    fun `signal wand creates updates and exactly deletes attached signals selected from a placed primary`() {
        val fixture = Fixture()
        val block = FixedBlockDefinition("test:attached-wand-block", 1,
            listOf(FixedBlockTraversal(fixture.entry, ViewDirection.AB, 0.0, 4.0)))
        val primaryPosition = RailPositionSnapshot(fixture.entry, ViewDirection.AB, 0.25)
        val primary = FixedBlockSignalDefinition("test:attached-wand-primary", 3, block.blockId, 0.0,
            "automatic:block", placement = ModelPlacement.RailAttached(primaryPosition))
        val reversePrimary = FixedBlockSignalDefinition("test:attached-wand-reverse-primary", 1, block.blockId, 0.0,
            "automatic:block", FixedBlockEntryDirection.REVERSE,
            ModelPlacement.RailAttached(primaryPosition.copy(facing = ViewDirection.BA)))
        val original = AttachedSignalDefinition("test:attached-wand-existing", 2, primary.signalId,
            DeviceModelDefinition("demo_signals:home", ModelPlacement.RailAttached(
                RailPositionSnapshot(fixture.entry, ViewDirection.AB, 1.0))))
        val definitions = store().also { it.save(fixture.worldId, SimpleSignalsWorldDefinitions(fixture.worldId, 1,
            emptyList(), emptyList(), listOf(block), listOf(primary, reversePrimary),
            attachedSignals = listOf(original))) }
        val infrastructure = MutableTopologyService(fixture.currentTopology)
        val manager = runtimeManager(plugin(), infrastructure.service, CapturingRoutePlanningService(), definitions,
            CapturingSignalEpochSourceService(), loadDefaultSignalCatalog = false)
        manager.loadSignalPacks(writeAutomaticSignalPack("proceed", modelVariants = listOf("stop", "proceed")))
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(fixture.worldId).status)
        val backend = RuntimeSignalWandBackend(manager, infrastructure.service) { "test:attached-wand-created" }
        val updatedPlacement = ModelPlacement.Ground(
            ModelPose(6.0, 68.0, 1.0, 0.0, 0.0, 0.0, 1.0))

        var capture = backend.capture(fixture.worldId, "automatic:slow", "demo_signals:home")
        var choices = backend.resolveEntrance(capture, SignalWandControlKind.ATTACHED_SIGNAL,
            SignalWandMode.PLACEMENT, primaryPosition)
        assertEquals(2, choices.size)
        assertTrue(choices.all { (it.binding as AttachedSignalWandBinding).primarySignalId == primary.signalId })
        val existingChoice = choices.single {
            ((it.binding as AttachedSignalWandBinding).intent as? ExistingAttachedSignal)?.signalId == original.signalId
        }
        val updateOutcome = backend.place(capture, existingChoice.binding, updatedPlacement) { true }
        assertTrue(updateOutcome.text.render(java.util.Locale.US).contains("Updated"), updateOutcome.text.render(java.util.Locale.US))
        assertTrue(updateOutcome.text.render(java.util.Locale.US).contains("with model demo_signals:home"), updateOutcome.text.render(java.util.Locale.US))
        var live = manager.authoringRuntime(fixture.worldId)!!.currentDefinitions()
        val updated = live.attachedSignals.single()
        assertEquals(3, updated.revision)
        assertEquals(primary.signalId, updated.primarySignalId)
        assertEquals("demo_signals:home", updated.model.modelId)
        assertEquals(updatedPlacement, updated.model.placement)
        assertTrue(assertThrows(IllegalArgumentException::class.java) {
            backend.capture(fixture.worldId, "automatic:block", "demo_signals:missing")
        }.message!!.contains("Unknown presentation model"))
        capture = backend.capture(fixture.worldId, "automatic:block", "demo_signals:home")
        val beforeStalePrimary = manager.authoringRuntime(fixture.worldId)!!.authoringPublication()
        val stalePrimary = AttachedSignalWandBinding(fixture.worldId, primary.signalId, 2,
            ExistingAttachedSignal(original.signalId, updated.revision))
        assertTrue(assertThrows(IllegalArgumentException::class.java) {
            backend.place(capture, stalePrimary, updatedPlacement) { true }
        }.message!!.contains("primary signal revision changed"))
        assertSame(beforeStalePrimary, manager.authoringRuntime(fixture.worldId)!!.authoringPublication())

        capture = backend.capture(fixture.worldId, "automatic:block", "demo_signals:home")
        choices = backend.resolveEntrance(capture, SignalWandControlKind.ATTACHED_SIGNAL,
            SignalWandMode.PLACEMENT, primaryPosition)
        val newChoice = choices.single { (it.binding as AttachedSignalWandBinding).intent === NewAttachedSignal }
        assertTrue(backend.place(capture, newChoice.binding, updatedPlacement) { true }.text.render(java.util.Locale.US).contains("Created"))
        live = manager.authoringRuntime(fixture.worldId)!!.currentDefinitions()
        assertEquals(setOf(original.signalId, "test:attached-wand-created"), live.attachedSignals.map { it.signalId }.toSet())

        capture = backend.capture(fixture.worldId, "automatic:block")
        choices = backend.resolveEntrance(capture, SignalWandControlKind.ATTACHED_SIGNAL,
            SignalWandMode.DELETION, primaryPosition)
        val deleteChoice = choices.single {
            ((it.binding as AttachedSignalWandBinding).intent as ExistingAttachedSignal).signalId == original.signalId
        }
        assertTrue(backend.delete(capture, deleteChoice.binding) { true }.text.render(java.util.Locale.US).contains(original.signalId))
        live = manager.authoringRuntime(fixture.worldId)!!.currentDefinitions()
        assertEquals(listOf("test:attached-wand-created"), live.attachedSignals.map { it.signalId })
        assertEquals(listOf(primary, reversePrimary), live.fixedBlockSignals)
        assertEquals(live, (definitions.load(fixture.worldId) as
            work.art1st.javarailways.simplesignals.persistence.SimpleSignalsWorldDefinitionLoadResult.Loaded).definitions)
        manager.clearLocal().toCompletableFuture().join()
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = [false, true])
    fun `signal wand preserves existing mapping on move and creates explicit new placed indicators`(useRailPreset: Boolean) {
        val fixture = Fixture()
        val route = fixture.upperRoute()
        val table = fixture.table(1, route)
        val indicator = RouteIndicatorDefinition("test:indicator-wand", 5, route.entryBoundaryId,
            "stop", mapOf(route.routeId to "proceed"))
        val definitions = store().also { it.save(fixture.worldId, SimpleSignalsWorldDefinitions(fixture.worldId, 1,
            listOf(fixture.region), listOf(table), routeIndicators = listOf(indicator))) }
        val infrastructure = MutableTopologyService(fixture.currentTopology)
        val manager = runtimeManager(plugin(), infrastructure.service, CapturingRoutePlanningService(), definitions,
            CapturingSignalEpochSourceService(), loadDefaultSignalCatalog = false)
        manager.loadSignalPacks(writeAutomaticSignalPack())
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(fixture.worldId).status)
        val backend = RuntimeSignalWandBackend(manager, infrastructure.service)
        val entrance = fixture.region.boundaries.first().position
        val railModel = checkNotNull(backend.railPlacementModel(SignalWandControlKind.ROUTE_INDICATOR,
            "automatic:slow", "demo_signals:home"))
        assertEquals("demo_signals:home", railModel.modelId)
        assertEquals(listOf("left", "right"), railModel.presets.keys.toList())
        val railPlacement = ModelPlacement.RailAttached(entrance).applyPreset(railModel.presets.getValue("right"))
        assertEquals(-2.0, railPlacement.adjustment.x())
        val placement = if (useRailPreset) railPlacement else
            ModelPlacement.Ground(ModelPose(5.0, 69.0, 0.0, 0.0, 0.0, 0.0, 1.0))

        var capture = backend.capture(fixture.worldId, "automatic:slow", "demo_signals:home")
        val binding = backend.resolveEntrance(capture, SignalWandControlKind.ROUTE_INDICATOR,
            SignalWandMode.PLACEMENT, entrance).single {
                (it.binding as RouteIndicatorSignalWandBinding).indicatorId == indicator.indicatorId
            }.binding
        val updateOutcome = backend.place(capture, binding, placement) { true }
        assertTrue(updateOutcome.text.render(java.util.Locale.US).contains("Updated"), updateOutcome.text.render(java.util.Locale.US))
        assertTrue(updateOutcome.text.render(java.util.Locale.US).contains("with model demo_signals:home"), updateOutcome.text.render(java.util.Locale.US))
        var live = manager.authoringRuntime(fixture.worldId)!!.currentDefinitions()
        val updated = live.routeIndicators.single()
        assertEquals(6, updated.revision)
        assertEquals(indicator.entryBoundaryId, updated.entryBoundaryId)
        assertEquals(indicator.noRouteModelVariant, updated.noRouteModelVariant)
        assertEquals(indicator.modelVariantsByRouteId, updated.modelVariantsByRouteId)
        assertEquals("demo_signals:home", updated.model!!.modelId)
        assertEquals(placement, updated.model.placement)
        assertEquals(listOf(fixture.region), live.regions)
        assertEquals(listOf(table), live.routeTables)
        assertEquals(live, (definitions.load(fixture.worldId) as
            work.art1st.javarailways.simplesignals.persistence.SimpleSignalsWorldDefinitionLoadResult.Loaded).definitions)
        capture = backend.capture(fixture.worldId, "automatic:block", "demo_signals:home")
        val beforeStaleIndicator = manager.authoringRuntime(fixture.worldId)!!.authoringPublication()
        assertTrue(assertThrows(IllegalArgumentException::class.java) {
            backend.place(capture, binding, placement) { true }
        }.message!!.contains("route indicator revision changed"))
        assertSame(beforeStaleIndicator, manager.authoringRuntime(fixture.worldId)!!.authoringPublication())

        capture = backend.capture(fixture.worldId, "automatic:block")
        val deleteBinding = backend.resolveEntrance(capture, SignalWandControlKind.ROUTE_INDICATOR,
            SignalWandMode.DELETION, entrance).single().binding
        assertTrue(backend.delete(capture, deleteBinding) { true }.text.render(java.util.Locale.US).contains(indicator.indicatorId))
        live = manager.authoringRuntime(fixture.worldId)!!.currentDefinitions()
        assertTrue(live.routeIndicators.isEmpty())
        capture = backend.capture(fixture.worldId, "automatic:block", "demo_signals:home")
        val newBinding = backend.resolveEntrance(capture, SignalWandControlKind.ROUTE_INDICATOR,
            SignalWandMode.PLACEMENT, entrance).single().binding as RouteIndicatorSignalWandBinding
        assertNull(newBinding.indicatorId)
        assertNull(newBinding.indicatorRevision)
        assertThrows(IllegalArgumentException::class.java) { backend.place(capture, newBinding, placement) { true } }
        val newEntry = requireNotNull(newBinding.entryBoundaryId)
        val options = backend.indicatorOptions(capture, newBinding, newEntry)
        assertEquals(listOf(route.routeId), options.routeIds)
        assertEquals(listOf("proceed", "stop"), options.variants)
        assertTrue(options.mappings.isEmpty())
        assertEquals(live, (definitions.load(fixture.worldId) as
            work.art1st.javarailways.simplesignals.persistence.SimpleSignalsWorldDefinitionLoadResult.Loaded).definitions)
        assertTrue(backend.saveIndicator(capture, newBinding, newEntry,
            "stop", emptyMap(), placement) { true }.text.render(java.util.Locale.US).contains("Created"))
        val created = manager.authoringRuntime(fixture.worldId)!!.currentDefinitions().routeIndicators.single()
        assertEquals(0, created.revision)
        assertEquals("stop", created.noRouteModelVariant)
        assertTrue(created.modelVariantsByRouteId.isEmpty())
        assertEquals(DeviceModelDefinition("demo_signals:home", placement), created.model)
        capture = backend.capture(fixture.worldId, "automatic:block", "demo_signals:home")
        // The original new-device intention must not turn into an update after another device appears.
        assertTrue(backend.saveIndicator(capture, newBinding, newEntry,
            "stop", mapOf(route.routeId to "proceed"), placement) { true }.text.render(java.util.Locale.US).contains("Created"))
        live = manager.authoringRuntime(fixture.worldId)!!.currentDefinitions()
        assertEquals(2, live.routeIndicators.size)
        assertEquals(created, live.routeIndicators.single { it.indicatorId == created.indicatorId })
        assertEquals(live, (definitions.load(fixture.worldId) as
            work.art1st.javarailways.simplesignals.persistence.SimpleSignalsWorldDefinitionLoadResult.Loaded).definitions)
        manager.clearLocal().toCompletableFuture().join()
    }

    @Test
    fun `indicator mapping editor rebinds an unbound device without moving or bypassing edit protection`() {
        val f = Fixture()
        val registry = editRegistry(f.currentTopology)
        val upper = f.upperRoute()
        val reverse = ProtectionRouteDefinition("test:reverse", upper.exitBoundaryId, upper.entryBoundaryId,
            upper.traversals.asReversed().map { it.copy(facing = ViewDirection.BA) })
        val placement = ModelPlacement.Ground(ModelPose(5.0, 64.0, 2.0, 0.0, 0.0, 0.0, 1.0))
        val indicator = RouteIndicatorDefinition("test:editor", 0, null, "stop",
            emptyMap(), DeviceModelDefinition("demo_signals:home", placement))
        // Retained unbound ground devices load from the saved document. Creating only this
        // device online has no rail impact and correctly cannot acquire an edit permit.
        val r = RecordingFixture(f, registry, listOf(ProtectionRouteTableDefinition("test:table", 1,
            f.region.regionId, 1, listOf(upper, reverse))), indicators = listOf(indicator))
        val backend = RuntimeSignalWandBackend(r.manager, r.infrastructure.service)
        // No Wand model preference is needed when editing an already placed indicator.
        val capture = backend.capture(f.worldId, "automatic:block")
        assertEquals(listOf(indicator), backend.indicatorDevices(capture))
        assertEquals(listOf("test:entry", "test:exit"), backend.indicatorEntries(capture))
        val binding = RouteIndicatorSignalWandBinding(f.worldId, indicator.entryBoundaryId,
            indicator.indicatorId, indicator.revision)
        val options = backend.indicatorOptions(capture, binding, reverse.entryBoundaryId)
        assertEquals(listOf(reverse.routeId), options.routeIds)
        assertEquals(listOf("proceed", "stop"), options.variants)
        assertTrue(options.mappings.isEmpty(), "an explicitly unbound device has no previous route mapping")
        val before = Files.readString(r.store.primaryFile(f.worldId))
        val publication = r.runtime.authoringPublication()
        val source = r.sources.sources.getValue(f.worldId)
        listOf(TopologyEditBlockerKind.INFRASTRUCTURE_DEPENDENCY, TopologyEditBlockerKind.LIVE_RUNTIME_CLAIM).forEach { kind ->
            val release = protect(registry, f.currentTopology, f.upperIn, kind)
            try { assertTrue(backend.saveIndicator(capture, binding, reverse.entryBoundaryId,
                "stop", mapOf(reverse.routeId to "proceed"), null) { true }.text.render(java.util.Locale.US).contains("EDIT_BLOCKED")) }
            finally { release() }
            assertEquals(before, Files.readString(r.store.primaryFile(f.worldId)))
            assertSame(publication, r.runtime.authoringPublication())
            assertSame(source, r.sources.sources.getValue(f.worldId))
        }
        listOf(mapOf(upper.routeId to "proceed"), mapOf(reverse.routeId to "missing")).forEach { mappings ->
            assertThrows(IllegalArgumentException::class.java) {
                backend.saveIndicator(capture, binding, reverse.entryBoundaryId, "stop", mappings, null) { true }
            }
            assertEquals(before, Files.readString(r.store.primaryFile(f.worldId)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            backend.saveIndicator(capture, binding, reverse.entryBoundaryId, "stop", emptyMap(), null) { false }
        }
        assertTrue(backend.saveIndicator(capture, binding, reverse.entryBoundaryId,
            "stop", mapOf(reverse.routeId to "proceed"), null) { true }.text.render(java.util.Locale.US).contains("placement unchanged"))
        val updated = r.runtime.currentDefinitions().routeIndicators.single()
        assertEquals(RouteIndicatorDefinition(indicator.indicatorId, 1, reverse.entryBoundaryId, "stop",
            mapOf(reverse.routeId to "proceed"), indicator.model), updated)
        assertThrows(IllegalArgumentException::class.java) {
            backend.saveIndicator(capture, binding, upper.entryBoundaryId, "stop", emptyMap(), null) { true }
        }
        val latest = backend.capture(f.worldId, "automatic:block", "demo_signals:wall")
        val exact = RouteIndicatorSignalWandBinding(f.worldId, updated.entryBoundaryId, updated.indicatorId, updated.revision)
        val saved = Files.readString(r.store.primaryFile(f.worldId))
        assertTrue(backend.saveIndicator(latest, exact, reverse.entryBoundaryId,
            updated.noRouteModelVariant, updated.modelVariantsByRouteId, null) { true }.text.render(java.util.Locale.US).contains("no changes"))
        assertEquals(saved, Files.readString(r.store.primaryFile(f.worldId)))
        assertEquals(indicator.model, r.runtime.currentDefinitions().routeIndicators.single().model)
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = [false, true])
    fun `scan plan captures static speed only in calculated mode and rebuilds only on topology preparation`(calculated: Boolean) {
        val rails = (1L..3L).map { UUID(12L, it) }
        fun topology(revision: Long) = topologyState(revision, rails, rails.zipWithNext().map { (first, second) ->
            endpoint(first, RailEndpoint.B) to endpoint(second, RailEndpoint.A)
        })
        val initial = topology(71)
        val worldId = UUID.randomUUID()
        val blocks = rails.mapIndexed { index, rail -> FixedBlockDefinition("test:scan-block-$index", 1,
            listOf(FixedBlockTraversal(rail, ViewDirection.AB, 0.0, 5.0))) }
        val signal = FixedBlockSignalDefinition("test:scan-head", 1, blocks.first().blockId, 0.0, "automatic:block")
        val definitions = store()
        definitions.save(worldId, SimpleSignalsWorldDefinitions(worldId, 1,
            emptyList(), emptyList(), blocks, listOf(signal)))
        val infrastructure = MutableTopologyService(initial)
        infrastructure.staticSpeedLimits = mapOf(rails[0] to 3.0, rails[1] to 99.0, rails[2] to 200.0)
        val sources = CapturingSignalEpochSourceService()
        val pending = arrayListOf<() -> Unit>()
        val manager = runtimeManager(plugin(), infrastructure.service, CapturingRoutePlanningService(), definitions,
            sources, topologyWorker = { pending += it }, loadDefaultSignalCatalog = false)
        val packs = writeAutomaticSignalPack(script = """
            function evaluate(input, output) {
              if ($calculated) {
                if (input.referenceDecelerationMetersPerSecondSquared !== 0.5 ||
                    input.scanDistanceMeters !== input.referenceSpeedMetersPerSecond ** 2) throw new Error('wrong calculation');
                if (input.referenceSpeedMetersPerSecond === 3 && input.forward[0].section !== undefined) {
                  throw new Error('short stopping distance exposed a distant section');
                }
              } else if (input.scanDistanceMeters !== 100 || 'referenceSpeedMetersPerSecond' in input ||
                         'referenceDecelerationMetersPerSecondSquared' in input) throw new Error('fixed mode leaked reference inputs');
              output.setSpeedLimit(input.scanDistanceMeters);
              output.setModelVariant('clear');
            }
        """.trimIndent())
        if (calculated) Files.writeString(packs.resolve("automatic/logic/block.yml"),
            "reference-deceleration-metres-per-second-squared: 0.5")
        manager.loadSignalPacks(packs)
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(worldId).status)
        fun process(number: Long) {
            val topology = infrastructure.topology!!
            SignalEpochRuntime(worldId.toString(), TrainRestrictionSnapshotStore(worldId.toString())).also {
                it.replaceSources(sources.sources.getValue(worldId))
                it.process(RailwayWorldFrameSnapshot.create(number, topology.revision, emptyList(),
                    ActiveRailContinuationsSnapshot.empty(topology.revision)))
            }
        }
        process(1)
        val captured = manager.authoringRuntime(worldId)!!.sectionSignalSnapshot()!!
        assertEquals(if (calculated) 9.0 else 100.0, captured.states.getValue(signal.signalId).effectiveSpeedLimitMetersPerSecond)
        assertEquals(if (calculated) 1 else 0, infrastructure.speedLimitReadCount)
        infrastructure.staticSpeedLimits = mapOf(rails[0] to 10.0, rails[1] to 99.0, rails[2] to 200.0)
        process(2)
        assertEquals(if (calculated) 9.0 else 100.0,
            manager.authoringRuntime(worldId)!!.sectionSignalSnapshot()!!.states.getValue(signal.signalId).effectiveSpeedLimitMetersPerSecond,
            "physical epochs reuse the captured plan and reference scalars")
        assertEquals(if (calculated) 1 else 0, infrastructure.speedLimitReadCount)
        infrastructure.commitTopology(topology(72), setOf(rails.first()))
        pending.removeFirst().invoke()
        process(3)
        assertEquals(100.0, manager.authoringRuntime(worldId)!!.sectionSignalSnapshot()!!.states
            .getValue(signal.signalId).effectiveSpeedLimitMetersPerSecond)
        assertEquals(if (calculated) 2 else 0, infrastructure.speedLimitReadCount)
        assertEquals(if (calculated) 9.0 else 100.0, captured.states.getValue(signal.signalId).effectiveSpeedLimitMetersPerSecond)
        manager.clearLocal().toCompletableFuture().join()
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = [false, true])
    fun `calculated scan captures whole block maximum in either direction and rejects any missing rail speed`(bothDirections: Boolean) {
        val rails = listOf(UUID(13, 1), UUID(13, 2))
        val topology = topologyState(71, rails,
            listOf(endpoint(rails[0], RailEndpoint.B) to endpoint(rails[1], RailEndpoint.A)))
        val worldId = UUID.randomUUID()
        val block = FixedBlockDefinition("test:directed-scan", 1, rails.map {
            FixedBlockTraversal(it, ViewDirection.AB, 0.0, 10.0)
        })
        val signals = listOf(FixedBlockEntryDirection.FORWARD, FixedBlockEntryDirection.REVERSE)
            .take(if (bothDirections) 2 else 1).map { direction ->
            FixedBlockSignalDefinition("test:scan-${direction.name.lowercase()}", 1, block.blockId, 0.0,
                "automatic:block", direction)
        }
        val definitions = store()
        definitions.save(worldId, SimpleSignalsWorldDefinitions(worldId, 1,
            emptyList(), emptyList(), listOf(block), signals))
        val infrastructure = MutableTopologyService(topology)
        infrastructure.operatingDirectionOverride = rails.associateWith { null }
        val sources = CapturingSignalEpochSourceService()
        val manager = runtimeManager(plugin(), infrastructure.service, CapturingRoutePlanningService(), definitions,
            sources, loadDefaultSignalCatalog = false)
        val packs = writeAutomaticSignalPack(script = """
            function evaluate(input, output) {
              output.setSpeedLimit(input.referenceSpeedMetersPerSecond);
              output.setModelVariant('clear');
            }
        """.trimIndent())
        Files.writeString(packs.resolve("automatic/logic/block.yml"),
            "reference-deceleration-metres-per-second-squared: 0.5")
        manager.loadSignalPacks(packs)
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(worldId).status)
        val runtime = manager.authoringRuntime(worldId)!!
        fun process(number: Long) = SignalEpochRuntime(worldId.toString(), TrainRestrictionSnapshotStore(worldId.toString())).also {
            it.replaceSources(sources.sources.getValue(worldId))
            it.process(RailwayWorldFrameSnapshot.create(number, topology.revision, emptyList(),
                ActiveRailContinuationsSnapshot.empty(topology.revision)))
        }
        process(1)
        assertTrue(runtime.sectionSignalSnapshot()!!.states.values.all { it is SectionSignalState.Unavailable },
            "missing finite static speed must retain startup fail-closed output")
        infrastructure.staticSpeedLimits = mapOf(rails[0] to 4.0, rails[1] to 8.0)
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED,
            manager.applyDefinitionPatch(worldId, 1, SimpleSignalsDefinitionPatch(upsertFixedBlockSignals = listOf(
                signals.first().reconfigured(revision = 2)))).status)
        process(2)
        assertEquals(signals.map { 8.0 }, signals.map {
            runtime.sectionSignalSnapshot()!!.states.getValue(it.signalId).effectiveSpeedLimitMetersPerSecond
        })
        val before = runtime.authoringPublication()
        val source = sources.sources.getValue(worldId)
        val disk = Files.readString(definitions.primaryFile(worldId))
        infrastructure.staticSpeedLimits = mapOf(rails[0] to 4.0, rails[1] to null)
        val rejected = manager.applyDefinitionPatch(worldId, 2, SimpleSignalsDefinitionPatch(upsertFixedBlockSignals = listOf(
            signals.first().reconfigured(revision = 3))))
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.VALIDATION_REJECTED, rejected.status)
        assertTrue(rejected.diagnostics.any { it.contains("no finite static speed limit") })
        assertSame(before, runtime.authoringPublication())
        assertSame(source, sources.sources.getValue(worldId))
        assertEquals(disk, Files.readString(definitions.primaryFile(worldId)))
        infrastructure.staticSpeedLimits = mapOf(rails[0] to Double.MAX_VALUE, rails[1] to 8.0)
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.VALIDATION_REJECTED,
            manager.applyDefinitionPatch(worldId, 2, SimpleSignalsDefinitionPatch(upsertFixedBlockSignals = listOf(
                signals.first().reconfigured(revision = 3)))).status, "overflowing scan distance cannot install a plan")
        assertSame(before, runtime.authoringPublication())
        manager.clearLocal().toCompletableFuture().join()
    }

    @Test
    fun `fixed signal observes uninstrumented protection branches through real epoch baseline and occupant updates`() {
        val f = Fixture()
        val routes = listOf(f.upperRoute(), f.lowerRoute())
        val block = FixedBlockDefinition("test:uninstrumented-approach", 1,
            listOf(FixedBlockTraversal(f.entry, ViewDirection.AB, 0.0, 4.0)))
        val signal = FixedBlockSignalDefinition("test:uninstrumented-head", 1, block.blockId, 0.0, "automatic:block")
        val definitions = store()
        val table = ProtectionRouteTableDefinition("test:uninstrumented-table", 1, f.region.regionId, 1, routes)
        definitions.save(f.worldId, SimpleSignalsWorldDefinitions(f.worldId, 1, listOf(f.region),
            listOf(table), listOf(block), listOf(signal))) // No protection signals or indicators register these routes.
        val sources = CapturingSignalEpochSourceService()
        val manager = runtimeManager(plugin(), MutableTopologyService(f.currentTopology).service,
            CapturingRoutePlanningService(), definitions, sources, loadDefaultSignalCatalog = false)
        manager.loadSignalPacks(writeAutomaticSignalPack(script = """
            function evaluate(input, output) {
              if (input.forward.length !== 2 || input.forward.some(branch =>
                  branch.section.lengthMeters !== 30 || branch.downstreamSignal !== undefined)) {
                throw new Error('both uninstrumented protection branches must be observable');
              }
              output.setSpeedLimit(input.forward.some(branch => branch.section.occupied) ? 0 : 25);
              output.setModelVariant('clear');
            }
        """.trimIndent()))
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(f.worldId).status)
        val runtime = manager.authoringRuntime(f.worldId)!!
        val epoch = SignalEpochRuntime(f.worldId.toString(), TrainRestrictionSnapshotStore(f.worldId.toString()))
        epoch.replaceSources(sources.sources.getValue(f.worldId))
        fun process(number: Long, occupied: Boolean) {
            val position = RailPositionSnapshot(f.lowerIn, ViewDirection.AB, 2.0)
            val trains = if (!occupied) emptyList() else listOf(TrainFrameSnapshot.create(
                "unselected-branch-train", "Branch train", number, number, 0.0, false, false, emptyList(),
                position, position,
                listOf(work.art1st.javarailways.core.api.physics.rail.RailInterval(f.lowerIn, 1.0, 2.0)),
                emptyList(), emptyList(), emptyList(), emptyList()))
            val continuations = runtime.authoringPublication().routeSelection.routesById.getValue(routes.first().routeId)
                .requiredContinuations.associate { it.entry to it.selectedExit }
            epoch.process(RailwayWorldFrameSnapshot.create(number, f.currentTopology.revision, trains,
                ActiveRailContinuationsSnapshot.create(f.currentTopology.revision, number, continuations)))
        }
        process(1, false)
        val baseline = runtime.sectionSignalSnapshot()!!
        assertInstanceOf(SectionSignalState.Available::class.java, baseline.states.getValue(signal.signalId),
            baseline.states.getValue(signal.signalId).toString())
        assertEquals(25.0, baseline.states.getValue(signal.signalId).effectiveSpeedLimitMetersPerSecond)
        val forward = runtime.signalDeviceDiagnostics(signal.signalId)!!.forward
        assertEquals(2, forward.size)
        assertTrue(forward.none { it.hasDownstreamSignal }, "missing downstream devices are not explicit clear outputs")
        assertTrue(forward.all { it.speedLimitMetersPerSecond == null })
        val sectionRefs = routes.map { SectionRef(SectionKind.PROTECTION_ROUTE, it.routeId) }.toSet()
        assertTrue(runtime.sectionOccupancySnapshot()!!.sections.keys.containsAll(sectionRefs))
        assertTrue(runtime.sectionOccupancySnapshot()!!.sections.filterKeys(sectionRefs::contains)
            .values.all { it.available && it.occupants.isEmpty() })

        process(2, true)
        val occupiedSnapshot = runtime.sectionOccupancySnapshot()!!
        assertEquals(setOf("unselected-branch-train"), occupiedSnapshot.sections
            .getValue(SectionRef(SectionKind.PROTECTION_ROUTE, routes.last().routeId)).occupants)
        assertTrue(occupiedSnapshot.sections.getValue(SectionRef(SectionKind.PROTECTION_ROUTE, routes.first().routeId))
            .occupants.isEmpty())
        assertEquals(0.0, runtime.sectionSignalSnapshot()!!.states.getValue(signal.signalId).effectiveSpeedLimitMetersPerSecond,
            "a real occupant on the other physical branch still reaches the full observation tree")
        assertEquals(2L, runtime.signalDeviceDiagnostics(signal.signalId)!!.stamp!!.physicsEpoch)

        process(3, false)
        assertEquals(25.0, runtime.sectionSignalSnapshot()!!.states.getValue(signal.signalId).effectiveSpeedLimitMetersPerSecond)
        assertEquals(25.0, baseline.states.getValue(signal.signalId).effectiveSpeedLimitMetersPerSecond,
            "later occupant updates do not mutate the completed baseline")
        manager.clearLocal().toCompletableFuture().join()
    }

    @Test
    fun `current protection signal captures maximum of its selected route without mixing sibling routes`() {
        val f = Fixture()
        val upper = f.upperRoute()
        val lower = f.lowerRoute()
        val signal = protectionSignal(ProtectionSignalKind.CURRENT_ENTRY, upper)
            .copy(signalType = "automatic:block")
        val fixed = protectionSignal(ProtectionSignalKind.FIXED_ROUTE, upper)
            .copy(signalId = "test:max-fixed", signalType = "automatic:block")
        val reports = mutableListOf<Pair<String, Throwable>>()
        val definitions = store()
        val table = ProtectionRouteTableDefinition("test:max-table", 1, f.region.regionId, 1, listOf(upper, lower))
        definitions.save(f.worldId, SimpleSignalsWorldDefinitions(f.worldId, 1, listOf(f.region),
            listOf(table), protectionSignals = listOf(signal, fixed)))
        val infrastructure = MutableTopologyService(f.currentTopology)
        infrastructure.staticSpeedLimits = mapOf(f.entry to 5.0, f.exit to 6.0,
            f.upperIn to 15.0, f.upperOut to 14.0, f.lowerIn to 30.0, f.lowerOut to 25.0)
        val sources = CapturingSignalEpochSourceService()
        val manager = runtimeManager(plugin(), infrastructure.service, CapturingRoutePlanningService(), definitions,
            sources, modelFailure = { context, failure -> reports += context to failure }, loadDefaultSignalCatalog = false)
        val packs = writeAutomaticSignalPack(script = """
            function evaluate(input, output) {
              if (input.scanDistanceMeters !== input.referenceSpeedMetersPerSecond ** 2 ||
                  input.referenceDecelerationMetersPerSecondSquared !== 0.5) throw new Error('wrong route distance');
              output.setSpeedLimit(input.referenceSpeedMetersPerSecond);
              output.setModelVariant('clear');
            }
        """.trimIndent())
        Files.writeString(packs.resolve("automatic/logic/block.yml"),
            "reference-deceleration-metres-per-second-squared: 0.5")
        manager.loadSignalPacks(packs)
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(f.worldId).status)
        val runtime = manager.authoringRuntime(f.worldId)!!
        val epoch = protectionTestEpoch(runtime, f.worldId.toString(), TrainRestrictionSnapshotStore(f.worldId.toString()))
        epoch.replaceSources(sources.sources.getValue(f.worldId))
        val owner = InfrastructureOwner("test:route-max-owner", 1)
        epoch.process(RailwayWorldFrameSnapshot.create(1, f.currentTopology.revision, emptyList(),
            ActiveRailContinuationsSnapshot.empty(f.currentTopology.revision)))
        fun assertWaiting() {
            val states = runtime.sectionSignalSnapshot()!!.states
            listOf(signal, fixed).forEach { device ->
                assertInstanceOf(SectionSignalState.Unavailable::class.java, states.getValue(device.signalId))
                assertEquals(0.0, states.getValue(device.signalId).effectiveSpeedLimitMetersPerSecond)
            }
            assertTrue((states.getValue(signal.signalId) as SectionSignalState.Unavailable).reason
                .endsWith("protection entry '${upper.entryBoundaryId}' has no ready route"))
            assertTrue((states.getValue(fixed.signalId) as SectionSignalState.Unavailable).reason
                .endsWith("fixed protection route '${upper.routeId}' is not ready"))
            assertTrue(reports.isEmpty(), "both normal protection waiting states must not produce rule errors: $reports")
        }
        assertWaiting() // Neither signal has a current train request.
        fun process(number: Long, selected: ProtectionRouteDefinition,
            state: ActiveRouteAdmissionState = ActiveRouteAdmissionState.GRANTED) {
            val route = RouteSnapshot("test:route-max-mission", number, f.currentTopology.revision,
                selected.traversals.mapIndexed { index, traversal ->
                    RouteSegmentSnapshot(traversal.railId, traversal.facing,
                        if (index == 0) 0.0 else traversal.lowerAbMeters,
                        if (index == selected.traversals.lastIndex) 10.0 else traversal.upperAbMeters)
                })
            epoch.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(owner, ActiveRouteAdmissionSnapshot(owner,
                "train", route, state, RouteBoundary(40.0),
                RouteBoundary(40.0).takeIf { state == ActiveRouteAdmissionState.GRANTED })))
            val position = RailPositionSnapshot(f.entry, ViewDirection.AB, 0.0)
            val continuations = runtime.authoringPublication().routeSelection.routesById.getValue(selected.routeId)
                .requiredContinuations.associate { it.entry to it.selectedExit }
            epoch.process(RailwayWorldFrameSnapshot.create(number, f.currentTopology.revision,
                listOf(TrainFrameSnapshot.create("train", "Train", number, number, 0.0, false, false,
                    emptyList(), position, position, emptyList(), emptyList(), emptyList(), emptyList(), emptyList())),
                ActiveRailContinuationsSnapshot.create(f.currentTopology.revision, number, continuations)))
        }
        process(2, upper, ActiveRouteAdmissionState.PREPARING)
        assertWaiting() // A selected request still has no completed authority/preparation.
        process(3, upper)
        assertEquals(15.0, runtime.sectionSignalSnapshot()!!.states.getValue(fixed.signalId).effectiveSpeedLimitMetersPerSecond,
            "fixed route must react to readiness alone with unchanged occupancy and continuations")
        val captured = runtime.sectionSignalSnapshot()!!
        assertEquals(15.0, captured.states.getValue(signal.signalId).effectiveSpeedLimitMetersPerSecond)
        process(4, lower)
        assertEquals(30.0, runtime.sectionSignalSnapshot()!!.states.getValue(signal.signalId).effectiveSpeedLimitMetersPerSecond)
        process(5, upper)
        assertEquals(15.0, runtime.sectionSignalSnapshot()!!.states.getValue(signal.signalId).effectiveSpeedLimitMetersPerSecond)
        assertEquals(15.0, runtime.sectionSignalSnapshot()!!.states.getValue(fixed.signalId).effectiveSpeedLimitMetersPerSecond)
        process(6, upper, ActiveRouteAdmissionState.PREPARING)
        assertWaiting() // Revoking readiness alone must also close the fixed-route signal.
        assertEquals(6L, runtime.signalDeviceDiagnostics(fixed.signalId)!!.stamp!!.physicsEpoch)
        assertEquals(1, infrastructure.speedLimitReadCount, "route changes select cached per-root speeds and distances")
        assertEquals(15.0, captured.states.getValue(signal.signalId).effectiveSpeedLimitMetersPerSecond)
        assertTrue(reports.isEmpty(), "a fixed-route signal waiting while a sibling route is selected is also normal")
        manager.clearLocal().toCompletableFuture().join()
    }

    @Test
    fun `startup and script rule issues clear when a current entry returns to normal restrictive waiting`() {
        val f = Fixture()
        val route = f.upperRoute()
        val signal = protectionSignal(ProtectionSignalKind.CURRENT_ENTRY, route)
            .copy(signalType = "automatic:block")
        val definitions = store()
        definitions.save(f.worldId, SimpleSignalsWorldDefinitions(f.worldId, 1, listOf(f.region),
            listOf(ProtectionRouteTableDefinition("test:issue-table", 1, f.region.regionId, 1, listOf(route))),
            protectionSignals = listOf(signal)))
        val sources = CapturingSignalEpochSourceService()
        val issues = work.art1st.javarailways.bukkit.api.diagnostics.WorldIssueService()
        val reports = mutableListOf<Pair<String, Throwable>>()
        val manager = runtimeManager(plugin(), MutableTopologyService(f.currentTopology).service,
            CapturingRoutePlanningService(), definitions, sources,
            modelFailure = { context, failure -> reports += context to failure },
            loadDefaultSignalCatalog = false, worldIssues = issues)
        try {
            assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(f.worldId).status)
            var runtime = manager.authoringRuntime(f.worldId)!!
            var epoch = protectionTestEpoch(runtime, f.worldId.toString(), TrainRestrictionSnapshotStore(f.worldId.toString()))
            epoch.replaceSources(sources.sources.getValue(f.worldId))
            fun process(number: Long, ready: Boolean? = null) {
                ready?.let { routeReady ->
                    val owner = InfrastructureOwner("test:issue-owner", 1)
                    val snapshot = RouteSnapshot("test:issue-mission", number, f.currentTopology.revision,
                        route.traversals.mapIndexed { index, traversal ->
                            RouteSegmentSnapshot(traversal.railId, traversal.facing,
                                if (index == 0) 0.0 else traversal.lowerAbMeters,
                                if (index == route.traversals.lastIndex) 10.0 else traversal.upperAbMeters)
                        })
                    epoch.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(owner, ActiveRouteAdmissionSnapshot(owner,
                        "train", snapshot, if (routeReady) ActiveRouteAdmissionState.GRANTED else ActiveRouteAdmissionState.PREPARING,
                        RouteBoundary(40.0), RouteBoundary(40.0).takeIf { routeReady })))
                }
                val position = RailPositionSnapshot(f.entry, ViewDirection.AB, 0.0)
                val continuations = runtime.authoringPublication().routeSelection.routesById.getValue(route.routeId)
                    .requiredContinuations.associate { it.entry to it.selectedExit }
                epoch.process(RailwayWorldFrameSnapshot.create(number, f.currentTopology.revision,
                    listOf(TrainFrameSnapshot.create("train", "Train", number, number, 0.0, false, false,
                        emptyList(), position, position, emptyList(), emptyList(), emptyList(), emptyList(), emptyList())),
                    ActiveRailContinuationsSnapshot.create(f.currentTopology.revision, number, continuations)))
            }
            fun ruleIssues() = issues.snapshot(f.worldId).filter {
                it.objectId == signal.signalId && it.code == "rule-unavailable"
            }
            fun assertWaiting() {
                val state = runtime.sectionSignalSnapshot()!!.states.getValue(signal.signalId)
                assertTrue((state as SectionSignalState.Unavailable).reason.endsWith("has no ready route"))
                assertEquals(0.0, state.effectiveSpeedLimitMetersPerSecond)
                assertTrue(ruleIssues().isEmpty(), "normal restrictive waiting must clear a previous rule issue")
            }
            process(1)
            assertEquals(1, ruleIssues().size)
            assertTrue((runtime.sectionSignalSnapshot()!!.states.getValue(signal.signalId) as SectionSignalState.Unavailable)
                .reason.contains("startup preparation has not completed"))
            manager.loadSignalPacks(writeAutomaticSignalPack(script = """
                function evaluate(input, output) { throw new Error('broken-current-entry-rule'); }
            """.trimIndent()))
            runtime = manager.authoringRuntime(f.worldId)!!
            epoch = protectionTestEpoch(runtime, f.worldId.toString(), TrainRestrictionSnapshotStore(f.worldId.toString()))
            epoch.replaceSources(sources.sources.getValue(f.worldId))
            process(2)
            assertWaiting()
            assertTrue(reports.isEmpty())
            process(3, true)
            assertEquals(1, ruleIssues().size)
            assertEquals(1, reports.size)
            assertTrue(reports.single().second.stackTraceToString().contains("broken-current-entry-rule"))
            process(4, false)
            assertWaiting()
            assertEquals(1, reports.size, "expected waiting does not report another script failure")
            process(5, true)
            assertEquals(1, ruleIssues().size)
            assertEquals(2, reports.size, "a script failure after normal waiting must be reported again")
        } finally {
            manager.clearLocal().toCompletableFuture().join()
        }
    }

    @Test
    fun `shared protection entrance combines only finite signal limits and preserves all clear as null`() {
        val f = Fixture()
        val selected = f.upperRoute()
        val block = FixedBlockDefinition("test:approach", 1,
            listOf(FixedBlockTraversal(f.entry, ViewDirection.AB, 0.0, 4.0)))
        val approach = FixedBlockSignalDefinition("test:approach-head", 1, block.blockId, 0.0, "automatic:block")
        val clear = protectionSignal(ProtectionSignalKind.FIXED_ROUTE, selected)
            .copy(signalType = "automatic:block")
        val peer = clear.copy(signalId = "test:peer", signalType = "automatic:slow")
        val definitions = store()
        definitions.save(f.worldId, SimpleSignalsWorldDefinitions(f.worldId, 1, listOf(f.region),
            listOf(f.table(1, selected)), listOf(block), listOf(approach), listOf(clear, peer)))
        val sources = CapturingSignalEpochSourceService()
        val infrastructure = MutableTopologyService(f.currentTopology)
        infrastructure.staticSpeedLimits = f.currentTopology.railProjection.keys.associateWith { 10.0 }
        val manager = runtimeManager(plugin(), infrastructure.service,
            CapturingRoutePlanningService(), definitions, sources, loadDefaultSignalCatalog = false)
        val packs = writeAutomaticSignalPack(script = """
            function evaluate(input, output) {
              if (input.self.lengthMeters === 4) {
                const speed = input.forward[0].downstreamSignal.speedLimitMetersPerSecond;
                if (speed === null) output.clearSpeedLimit(); else output.setSpeedLimit(speed);
              } else output.clearSpeedLimit();
              output.setModelVariant('clear');
            }
        """.trimIndent())
        Files.writeString(packs.resolve("automatic/logic/block.yml"),
            "reference-deceleration-metres-per-second-squared: 0.5")
        manager.loadSignalPacks(packs)
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(f.worldId).status)
        val runtime = manager.authoringRuntime(f.worldId)!!
        val route = RouteSnapshot("test:clear-mission", 1, f.currentTopology.revision,
            listOf(f.entry, f.upperIn, f.upperOut, f.exit).map {
                RouteSegmentSnapshot(it, ViewDirection.AB, 0.0, 10.0)
            })
        val owner = InfrastructureOwner("test:clear-owner", 1)
        val epoch = protectionTestEpoch(runtime, f.worldId.toString(), TrainRestrictionSnapshotStore(f.worldId.toString()))
        fun process(number: Long, grant: Boolean) {
            epoch.also {
                it.replaceSources(sources.sources.getValue(f.worldId))
                if (grant) it.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(owner, ActiveRouteAdmissionSnapshot(owner,
                    "train", route, ActiveRouteAdmissionState.GRANTED, RouteBoundary(40.0), RouteBoundary(40.0))))
                else it.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(owner, null))
                val position = RailPositionSnapshot(f.entry, ViewDirection.AB, 0.0)
                val continuations = runtime.authoringPublication().routeSelection.routesById.getValue(selected.routeId)
                    .requiredContinuations.associate { it.entry to it.selectedExit }
                it.process(RailwayWorldFrameSnapshot.create(number, f.currentTopology.revision,
                    listOf(TrainFrameSnapshot.create("train", "Train", number, number, 0.0, false, false,
                        emptyList(), position, position, emptyList(), emptyList(), emptyList(), emptyList(), emptyList())),
                    ActiveRailContinuationsSnapshot.create(f.currentTopology.revision, number, continuations)))
            }
        }
        process(1, true)
        assertNull(runtime.sectionSignalSnapshot()!!.states.getValue(clear.signalId).effectiveSpeedLimitMetersPerSecond)
        assertEquals(5.0, runtime.sectionSignalSnapshot()!!.states.getValue(approach.signalId).effectiveSpeedLimitMetersPerSecond)
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED,
            manager.applyDefinitionPatch(f.worldId, 1, SimpleSignalsDefinitionPatch(upsertProtectionSignals = listOf(
                peer.copy(revision = 2, signalType = "automatic:block")))).status)
        process(2, true)
        assertTrue(runtime.sectionSignalSnapshot()!!.states.values.all { it is SectionSignalState.Available })
        assertTrue(runtime.sectionSignalSnapshot()!!.states.values.all { it.effectiveSpeedLimitMetersPerSecond == null })
        process(3, false)
        assertEquals(0.0, runtime.sectionSignalSnapshot()!!.states.getValue(approach.signalId).effectiveSpeedLimitMetersPerSecond,
            "an unavailable protection entry is zero, not an unlimited downstream signal")
        val unavailableDiagnostic = runtime.signalDeviceDiagnostics(clear.signalId)!!
        assertInstanceOf(SectionSignalState.Unavailable::class.java, unavailableDiagnostic.state)
        assertEquals(3L, unavailableDiagnostic.stamp!!.physicsEpoch)
        assertNotNull(unavailableDiagnostic.evaluatedAt)
        assertNull(unavailableDiagnostic.selfOccupied,
            "failure before projection must not expose the last successful input")
        assertNull(unavailableDiagnostic.scanDistanceMeters)
        assertTrue(unavailableDiagnostic.forward.isEmpty())
        manager.clearLocal().toCompletableFuture().join()
    }

    @Test
    fun `explicit unlimited output stays available and reaches upstream as JSON null in the same epoch`() {
        val rails = (1L..3L).map { UUID(11L, it) }
        val topology = topologyState(71, rails, rails.zipWithNext().map { (first, second) ->
            endpoint(first, RailEndpoint.B) to endpoint(second, RailEndpoint.A)
        })
        val worldId = UUID.randomUUID()
        val blocks = rails.mapIndexed { index, rail -> FixedBlockDefinition("test:clear-block-$index", 1,
            listOf(FixedBlockTraversal(rail, ViewDirection.AB, 0.0, 5.0))) }
        val signals = blocks.mapIndexed { index, block -> FixedBlockSignalDefinition(
            "test:clear-signal-$index", 1, block.blockId, 0.0, "automatic:block") }
        val definitions = store()
        definitions.save(worldId, SimpleSignalsWorldDefinitions(worldId, 1,
            emptyList(), emptyList(), blocks, signals))
        val sources = CapturingSignalEpochSourceService()
        val manager = runtimeManager(plugin(), MutableTopologyService(topology).service,
            CapturingRoutePlanningService(), definitions, sources, loadDefaultSignalCatalog = false)
        manager.loadSignalPacks(writeAutomaticSignalPack(script = """
            function evaluate(input, output) {
              if (input.forward.length === 0 ||
                  (input.forward[0].end?.reason === 'track-end' && !Object.hasOwn(input.forward[0], 'section'))) {
                output.clearSpeedLimit();
              } else {
                const downstream = input.forward[0].downstreamSignal;
                if (downstream === undefined || !Object.hasOwn(downstream, 'speedLimitMetersPerSecond')) {
                  throw new Error('missing downstream output is not an explicit unlimited decision');
                }
                output.setSpeedLimit(downstream.speedLimitMetersPerSecond === null ? 12 :
                  downstream.speedLimitMetersPerSecond - 2);
              }
              output.setModelVariant('clear');
            }
        """.trimIndent()))
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(worldId).status)
        val runtime = manager.authoringRuntime(worldId)!!
        assertNull(runtime.signalDeviceDiagnostics(signals.first().signalId)!!.state,
            "a device without its first baseline is not an occupied or clear device")
        assertNull(runtime.signalDeviceDiagnostics("test:missing"))
        val epoch = SignalEpochRuntime(worldId.toString(), TrainRestrictionSnapshotStore(worldId.toString()))
        epoch.replaceSources(sources.sources.getValue(worldId))
        epoch.process(RailwayWorldFrameSnapshot.create(1, topology.revision, emptyList(),
            ActiveRailContinuationsSnapshot.empty(topology.revision)))
        val captured = runtime.sectionSignalSnapshot()!!
        assertTrue(signals.all { captured.states.getValue(it.signalId) is SectionSignalState.Available })
        assertEquals(listOf(10.0, 12.0, null), signals.map {
            captured.states.getValue(it.signalId).effectiveSpeedLimitMetersPerSecond
        })
        val diagnostic = runtime.signalDeviceDiagnostics(signals[1].signalId)!!
        assertEquals(1L, diagnostic.stamp!!.physicsEpoch)
        assertNotNull(diagnostic.evaluatedAt)
        assertNull(diagnostic.observedAt, "no wall-clock observation time is supplied by the host")
        assertEquals(false, diagnostic.selfOccupied)
        assertEquals(5.0, diagnostic.selfLengthMeters)
        assertEquals(100.0, diagnostic.scanDistanceMeters)
        assertTrue(diagnostic.forward.any { it.hasDownstreamSignal && it.speedLimitMetersPerSecond == null })
        assertTrue(diagnostic.forward.all { it.branchPath.isNotEmpty() && it.selection == "not-applicable" })
        assertTrue(diagnostic.terminations.any { it.reason == "track-end" })
        assertThrows(UnsupportedOperationException::class.java) {
            (diagnostic.forward as MutableList<*>).clear()
        }
        epoch.process(RailwayWorldFrameSnapshot.create(2, topology.revision, emptyList(),
            ActiveRailContinuationsSnapshot.empty(topology.revision)))
        assertSame(captured.evaluations.getValue(signals[1].signalId),
            runtime.sectionSignalSnapshot()!!.evaluations.getValue(signals[1].signalId),
            "unchanged cached input retains its real source instead of pretending refresh evaluated it")
        assertEquals(diagnostic.evaluatedAt, runtime.signalDeviceDiagnostics(signals[1].signalId)!!.evaluatedAt)
        assertEquals(1L, runtime.signalDeviceDiagnostics(signals[1].signalId)!!.stamp!!.physicsEpoch)
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED,
            manager.applyDefinitionPatch(worldId, 1, SimpleSignalsDefinitionPatch(upsertFixedBlockSignals = listOf(
                signals.last().reconfigured(revision = 2, signalType = "automatic:slow")))).status)
        epoch.replaceSources(sources.sources.getValue(worldId))
        epoch.process(RailwayWorldFrameSnapshot.create(3, topology.revision, emptyList(),
            ActiveRailContinuationsSnapshot.empty(topology.revision)))
        assertEquals(listOf(1.0, 3.0, 5.0), signals.map {
            manager.authoringRuntime(worldId)!!.sectionSignalSnapshot()!!.states.getValue(it.signalId)
                .effectiveSpeedLimitMetersPerSecond
        })
        assertNull(captured.states.getValue(signals.last().signalId).effectiveSpeedLimitMetersPerSecond,
            "publishing a finite output must not mutate the previous unlimited snapshot")
        manager.clearLocal().toCompletableFuture().join()
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = [false, true])
    fun `distributed four light package propagates actual same epoch occupancy through mixed and short blocks`(shortBlocks: Boolean) {
        val lengths = if (shortBlocks) listOf(10, 10, 10, 10) else listOf(500, 300, 150, 500)
        val rails = (1L..lengths.sum() / 10).map { UUID(14L, it) }
        val topology = topologyState(71, rails, rails.zipWithNext().map { (first, second) ->
            endpoint(first, RailEndpoint.B) to endpoint(second, RailEndpoint.A)
        })
        var start = 0
        val blocks = lengths.mapIndexed { index, length ->
            val blockRails = rails.subList(start, start + length / 10)
            start += length / 10
            FixedBlockDefinition("test:formal-block-$index", 1,
                blockRails.map { FixedBlockTraversal(it, ViewDirection.AB, 0.0, 10.0) })
        }
        val signals = blocks.mapIndexed { index, block -> FixedBlockSignalDefinition(
            "test:formal-signal-$index", 1, block.blockId, 0.0, "jr_four_light:block") }
        val worldId = UUID.randomUUID()
        val definitions = store()
        definitions.save(worldId, SimpleSignalsWorldDefinitions(worldId, 1,
            emptyList(), emptyList(), blocks, signals))
        val infrastructure = MutableTopologyService(topology)
        infrastructure.staticSpeedLimits = rails.associateWith { 100.0 / 3.6 }
        val sources = CapturingSignalEpochSourceService()
        val manager = runtimeManager(plugin(), infrastructure.service, CapturingRoutePlanningService(), definitions,
            sources, loadDefaultSignalCatalog = false)
        manager.loadSignalPacks(Path.of(requireNotNull(javaClass.getResource("/examples/jr-four-light/signal-packs")).toURI()))
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(worldId).status)
        val epoch = SignalEpochRuntime(worldId.toString(), TrainRestrictionSnapshotStore(worldId.toString()))
        epoch.replaceSources(sources.sources.getValue(worldId))
        val occupiedRail = blocks.last().traversals.first().railId
        val interval = work.art1st.javarailways.core.api.physics.rail.RailInterval(occupiedRail, 1.0, 2.0)
        val head = RailPositionSnapshot(occupiedRail, ViewDirection.AB, 2.0)
        val tail = RailPositionSnapshot(occupiedRail, ViewDirection.AB, 1.0)
        epoch.process(RailwayWorldFrameSnapshot.create(1, topology.revision,
            listOf(TrainFrameSnapshot.create("formal-train", "Formal train", 1, 1, 0.0, false, false, emptyList(),
                head, tail, listOf(interval), emptyList(), emptyList(), emptyList(), emptyList())),
            ActiveRailContinuationsSnapshot.empty(topology.revision)))
        val captured = manager.authoringRuntime(worldId)!!.sectionSignalSnapshot()!!
        assertTrue(captured.states.values.all { it is SectionSignalState.Available }, captured.states.toString())
        assertEquals(if (shortBlocks) listOf(0.0, 0.0, 0.0, 0.0) else listOf(null, 65.0 / 3.6, 45.0 / 3.6, 0.0),
            signals.map { captured.states.getValue(it.signalId).effectiveSpeedLimitMetersPerSecond })
        assertEquals(if (shortBlocks) listOf("stop", "stop", "stop", "stop")
            else listOf("proceed", "slow", "caution", "stop"),
            signals.map { (captured.states.getValue(it.signalId) as SectionSignalState.Available).output.modelVariant })
        epoch.process(RailwayWorldFrameSnapshot.create(2, topology.revision, emptyList(),
            ActiveRailContinuationsSnapshot.empty(topology.revision)))
        if (!shortBlocks) assertEquals(65.0 / 3.6, manager.authoringRuntime(worldId)!!.sectionSignalSnapshot()!!.states
            .getValue(signals.last().signalId).effectiveSpeedLimitMetersPerSecond,
            "the cleared last section still brakes toward its physical track end")
        assertEquals(0.0, captured.states.getValue(signals.last().signalId).effectiveSpeedLimitMetersPerSecond,
            "the completed first epoch snapshot remains immutable")
        assertEquals(1, infrastructure.speedLimitReadCount, "physical epochs reuse static prepared scanning")
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
        val awaiting = manager.authoringRuntime(worldId)!!.signalDeviceDiagnostics(signals.first().signalId)!!
        assertNull(awaiting.state)
        assertNull(awaiting.evaluatedAt)
        assertNull(awaiting.selfOccupied, "a replacement must not reuse old observation facts as current input")
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
        assertNull(manager.authoringRuntime(worldId)!!.signalDeviceDiagnostics(signals.first().signalId))
        assertFalse(manager.authoringRuntime(worldId)!!.sectionSignalSnapshot()!!.evaluations
            .containsKey(signals.first().signalId))
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
        val diagnostic = manager.authoringRuntime(fixture.worldId)!!.signalDeviceDiagnostics(signal.signalId)!!
        assertSame(state, diagnostic.state)
        assertEquals(1L, diagnostic.stamp!!.physicsEpoch)
        assertNotNull(diagnostic.evaluatedAt)
        assertEquals(false, diagnostic.selfOccupied,
            "JS/output validation failures still retain the actual input of that attempt")
        assertEquals(4.0, diagnostic.selfLengthMeters)
        assertEquals("automatic:head", manager.authoringRuntime(fixture.worldId)!!.currentModels()!!.prepared
            .signals.getValue(signal.signalId).single().model.id)
        assertEquals(1, models.handles.size)
        models.cleanup.complete(null)
        manager.clearLocal().toCompletableFuture().join()
    }

    @Test
    fun `rule failures retain original throwable and report only changed failure states rather than normal red`() {
        val f = Fixture()
        val block = FixedBlockDefinition("test:logging-block", 1,
            listOf(FixedBlockTraversal(f.entry, ViewDirection.AB, 0.0, 4.0)))
        val signal = FixedBlockSignalDefinition("test:logging-device", 1, block.blockId, 0.0, "automatic:block")
        val definitions = store()
        definitions.save(f.worldId, SimpleSignalsWorldDefinitions(f.worldId, 1,
            emptyList(), emptyList(), listOf(block), listOf(signal)))
        val reports = mutableListOf<Pair<String, Throwable>>()
        val sources = CapturingSignalEpochSourceService()
        val manager = runtimeManager(plugin(), MutableTopologyService(f.currentTopology).service,
            CapturingRoutePlanningService(), definitions, sources,
            modelFailure = { context, failure -> reports += context to failure }, loadDefaultSignalCatalog = false)
        manager.loadSignalPacks(writeAutomaticSignalPack(script = """
            function evaluate(input, output) {
              if (input.self.occupied) throw new Error('broken-rule');
              output.setSpeedLimit(0);
              output.setModelVariant('clear');
            }
        """.trimIndent()))
        try {
            assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(f.worldId).status)
            val epoch = SignalEpochRuntime(f.worldId.toString(), TrainRestrictionSnapshotStore(f.worldId.toString()))
            epoch.replaceSources(sources.sources.getValue(f.worldId))
            val runtime = manager.authoringRuntime(f.worldId)!!
            fun process(number: Long, trainId: String?) {
                val position = RailPositionSnapshot(f.entry, ViewDirection.AB, 2.0)
                val trains = trainId?.let { listOf(TrainFrameSnapshot.create(
                    it, it, number, number, 0.0, false, false, emptyList(), position, position,
                    listOf(work.art1st.javarailways.core.api.physics.rail.RailInterval(f.entry, 1.0, 2.0)),
                    emptyList(), emptyList(), emptyList(), emptyList())) }.orEmpty()
                epoch.process(RailwayWorldFrameSnapshot.create(number, f.currentTopology.revision, trains,
                    ActiveRailContinuationsSnapshot.empty(f.currentTopology.revision)))
            }
            process(1, null)
            assertTrue(reports.isEmpty(), "normal rule-selected stop is not an error")
            process(2, "first")
            assertEquals(1, reports.size)
            val (context, failure) = reports.single()
            assertTrue(context.contains("world=${f.worldId}"))
            assertTrue(context.contains("device=${signal.signalId}"))
            assertTrue(context.contains("logic=automatic:block"))
            assertTrue(context.contains("physicsEpoch=2"))
            assertTrue(context.contains("definition=1"))
            assertTrue(failure.stackTraceToString().contains("broken-rule"))
            assertNotNull(failure.cause, "do not replace the original JS exception chain with reason-only text")
            assertInstanceOf(SectionSignalState.Unavailable::class.java,
                runtime.sectionSignalSnapshot()!!.states.getValue(signal.signalId))
            process(3, "second") // Actual occupant change reevaluates JS but retains the same failure state.
            assertEquals(1, reports.size)
            assertEquals(3L, runtime.signalDeviceDiagnostics(signal.signalId)!!.stamp!!.physicsEpoch)
            process(4, null)
            assertEquals(1, reports.size)
            assertInstanceOf(SectionSignalState.Available::class.java,
                runtime.sectionSignalSnapshot()!!.states.getValue(signal.signalId))
            process(5, "first")
            assertEquals(2, reports.size, "a failure after a real recovery must be reported again")
        } finally {
            manager.clearLocal().toCompletableFuture().join()
        }
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
        val edit = CompletableFuture.supplyAsync({ manager.applyDefinitionPatch(fixture.worldId, 0, patch, runtime) }, concurrentExecutor)
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val lookup = CompletableFuture.supplyAsync({ manager.authoringRuntime(fixture.worldId)!!.authoringPublication() }, concurrentExecutor)
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
        val node = work.art1st.javarailways.core.api.physics.infrastructure.StableTopologyNodeKey(
            listOf(endpoint(rail, RailEndpoint.A)))
        val resource = work.art1st.javarailways.core.api.physics.infrastructure.StableInfrastructureResource(
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
            FixedBlockTraversal(fixture.entry, ViewDirection.AB, 0.0, 5.0)))
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
        val shortened = listOf(
            FixedBlockTraversal(outside, ViewDirection.AB, 1.0, 10.0),
            FixedBlockTraversal(fixture.entry, ViewDirection.AB, 0.0, 5.0))
        val withoutRevision = SimpleSignalsDefinitionPatch(upsertFixedBlocks = listOf(
            FixedBlockDefinition(successor.blockId, 1L, shortened)))
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.VALIDATION_REJECTED,
            manager.applyDefinitionPatch(fixture.worldId, 1L, withoutRevision).status)
        assertEquals(0, infrastructure.permitAcquisitionCount)
        val releaseRegion = protect(registry, topology, fixture.upperIn, TopologyEditBlockerKind.INFRASTRUCTURE_DEPENDENCY)
        val shortening = SimpleSignalsDefinitionPatch(upsertFixedBlocks = listOf(
            FixedBlockDefinition(successor.blockId, 2L, shortened)))
        listOf(shortening, SimpleSignalsDefinitionPatch(removeFixedBlockIds = listOf(successor.blockId))).forEach { patch ->
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
            manager.applyDefinitionPatch(fixture.worldId, 1L, shortening).status)
        assertEquals(shortened, runtime.authoringPublication().definitions.fixedBlocks.single().traversals)
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

    @Test
    fun `contextual signal type change preserves exact binding placement and edit protection`() {
        val f = Fixture()
        val initial = deviceModelDefinitions(f, "fixed", railModel(f.entry, 2.0))
        val definitions = store()
        definitions.save(f.worldId, initial)
        val registry = editRegistry(f.currentTopology)
        val infrastructure = MutableTopologyService(f.currentTopology, registry)
        val sources = CapturingSignalEpochSourceService()
        val manager = runtimeManager(plugin(), infrastructure.service, CapturingRoutePlanningService(), definitions, sources,
            loadDefaultSignalCatalog = false)
        manager.loadSignalPacks(writeAutomaticSignalPack("proceed", modelVariants = listOf("stop", "proceed")))
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(f.worldId).status)
        val backend = RuntimeSignalWandBackend(manager, infrastructure.service)
        val capture = backend.capture(f.worldId, null)
        val device = backend.placedDevices(capture).single()
        val runtime = manager.authoringRuntime(f.worldId)!!
        val publication = runtime.authoringPublication()
        val disk = Files.readString(definitions.primaryFile(f.worldId))
        backend.changeType(capture, device, "automatic:block", null) { true }
        assertSame(publication, runtime.authoringPublication(), "same type must not write or advance revision")
        assertEquals(0, infrastructure.permitAcquisitionCount)
        assertThrows(IllegalArgumentException::class.java) {
            backend.changeType(capture, device, "automatic:slow", null) { false }
        }
        val release = protect(registry, f.currentTopology, f.entry, TopologyEditBlockerKind.LIVE_RUNTIME_CLAIM)
        assertTrue(backend.changeType(capture, device, "automatic:slow", null) { true }.text.render(java.util.Locale.US).contains("EDIT_BLOCKED"))
        assertSame(publication, runtime.authoringPublication())
        assertEquals(disk, Files.readString(definitions.primaryFile(f.worldId)))
        release()
        backend.changeType(capture, device, "automatic:slow", null) { true }
        val changed = runtime.currentDefinitions()
        assertEquals(initial.fixedBlockSignals.single().copy(revision = device.revision + 1, signalType = "automatic:slow"),
            changed.fixedBlockSignals.single())
        assertEquals(initial.fixedBlocks, changed.fixedBlocks)
        assertEquals(initial.routeTables, changed.routeTables)
        assertEquals(changed, (definitions.load(f.worldId) as
            work.art1st.javarailways.simplesignals.persistence.SimpleSignalsWorldDefinitionLoadResult.Loaded).definitions)
        assertThrows(IllegalArgumentException::class.java) {
            backend.changeType(capture, device, "automatic:block", null) { true }
        }
        manager.clearLocal().toCompletableFuture().join()
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = ["fixed", "fixed-route", "current-entry", "attached-fixed", "indicator"])
    fun `numeric placement backend preserves device configuration and persists rail or ground edits atomically`(kind: String) {
        listOf(false, true).forEach { ground ->
            val f = Fixture()
            val originalModel = if (ground) DeviceModelDefinition("demo_signals:home", ModelPlacement.Ground(ModelPose.IDENTITY))
                else railModel(f.entry, 2.0)
            val initial = deviceModelDefinitions(f, kind, originalModel)
            val definitions = store()
            definitions.save(f.worldId, initial)
            val registry = editRegistry(f.currentTopology)
            val infrastructure = MutableTopologyService(f.currentTopology, registry)
            val sources = CapturingSignalEpochSourceService()
            val manager = runtimeManager(plugin(), infrastructure.service, CapturingRoutePlanningService(), definitions, sources,
                loadDefaultSignalCatalog = false)
            manager.loadSignalPacks(writeAutomaticSignalPack("proceed", modelVariants = listOf("stop", "proceed")))
            assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(f.worldId).status)
            val backend = RuntimeSignalWandBackend(manager, infrastructure.service)
            // A different Wand preference must not change the selected device's actual model or type.
            val capture = backend.capture(f.worldId, "automatic:slow", null)
            val device = backend.placedDevices(capture).single { it.id == "test:device" }
            assertEquals(originalModel.placement, device.placement)
            assertFalse(backend.placedDevices(capture).any { it.id == "test:primary" })
            val pose = ModelPose(2.0, 3.0, 4.0, 0.0, 1.0, 0.0, 0.0)
            val replacement = when (val old = device.placement) {
                is ModelPlacement.RailAttached -> old.copy(adjustment = pose, followRailTilt = true)
                is ModelPlacement.Ground -> old.copy(pose = pose)
            }
            val runtime = manager.authoringRuntime(f.worldId)!!
            val publication = runtime.authoringPublication()
            val source = sources.sources.getValue(f.worldId)
            val disk = Files.readString(definitions.primaryFile(f.worldId))
            assertTrue(backend.savePlacement(capture, device, device.placement) { true }.text.render(java.util.Locale.US).contains("no changes"))
            assertEquals(0, infrastructure.permitAcquisitionCount)
            assertThrows(IllegalArgumentException::class.java) {
                backend.savePlacement(capture, device, replacement) { false }
            }
            assertThrows(IllegalArgumentException::class.java) {
                backend.savePlacement(capture, device.copy(revision = device.revision + 1), replacement) { true }
            }
            val wrongAnchor = if (ground) ModelPlacement.RailAttached(RailPositionSnapshot(f.entry, ViewDirection.AB, 2.0))
                else (replacement as ModelPlacement.RailAttached).copy(position = RailPositionSnapshot(f.entry, ViewDirection.AB, 3.0))
            assertThrows(IllegalArgumentException::class.java) {
                backend.savePlacement(capture, device, wrongAnchor) { true }
            }
            listOf(TopologyEditBlockerKind.INFRASTRUCTURE_DEPENDENCY, TopologyEditBlockerKind.LIVE_RUNTIME_CLAIM).forEach { blocker ->
                val release = protect(registry, f.currentTopology, f.entry, blocker)
                assertTrue(backend.savePlacement(capture, device, replacement) { true }.text.render(java.util.Locale.US).contains("EDIT_BLOCKED"))
                assertSame(publication, runtime.authoringPublication())
                assertSame(source, sources.sources.getValue(f.worldId))
                assertEquals(disk, Files.readString(definitions.primaryFile(f.worldId)))
                release()
            }
            val outcome = backend.savePlacement(capture, device, replacement) { true }
            assertTrue(outcome.text.render(java.util.Locale.US).startsWith("Updated placement"), outcome.text.render(java.util.Locale.US))
            val expectedDevices = deviceModelDefinitions(f, kind, originalModel.copy(placement = replacement), 2)
            val expected = SimpleSignalsWorldDefinitions(expectedDevices.worldId, 2, expectedDevices.regions,
                expectedDevices.routeTables, expectedDevices.fixedBlocks, expectedDevices.fixedBlockSignals,
                expectedDevices.protectionSignals, expectedDevices.routeIndicators, expectedDevices.attachedSignals,
                expectedDevices.pendingProtectionBoundaries)
            assertEquals(expected, runtime.currentDefinitions())
            assertEquals(expected, (definitions.load(f.worldId) as
                work.art1st.javarailways.simplesignals.persistence.SimpleSignalsWorldDefinitionLoadResult.Loaded).definitions)
            assertThrows(IllegalArgumentException::class.java) {
                backend.savePlacement(capture, device, replacement) { true }
            }
            manager.clearLocal().toCompletableFuture().join()
            val restarted = runtimeManager(plugin(), infrastructure.service, CapturingRoutePlanningService(), definitions,
                CapturingSignalEpochSourceService(), loadDefaultSignalCatalog = false)
            restarted.loadSignalPacks(writeAutomaticSignalPack("proceed", modelVariants = listOf("stop", "proceed")))
            assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, restarted.install(f.worldId).status)
            assertEquals(expected, restarted.authoringRuntime(f.worldId)!!.currentDefinitions())
            restarted.clearLocal().toCompletableFuture().join()
        }
    }

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
                registry.setInfrastructureDependencies(platformOwner, InfrastructureDependencyRequest(owner,
                    InfrastructureDependencies(setOf(rail), emptySet(), emptySet()))).status)
            TopologyEditBlockerKind.LIVE_RUNTIME_CLAIM -> assertEquals(InfrastructureMutationStatus.ACQUIRED,
                registry.acquireClaim(platformOwner, InfrastructureClaimRequest(owner,
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

    @Test fun `wand route deletion lists authored region rows and deletes last row with device preserved`() {
        val f = Fixture()
        val r = RecordingFixture(f, tables = listOf(ProtectionRouteTableDefinition("test:table", 1,
            f.region.regionId, 1, listOf(f.upperRoute(), f.lowerRoute()))))
        val device = protectionSignal(ProtectionSignalKind.FIXED_ROUTE, f.lowerRoute())
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED, r.manager.applyDefinitionPatch(f.worldId, 1,
            SimpleSignalsDefinitionPatch(upsertProtectionSignals = listOf(device))).status)
        val selection = r.preview.select(f.worldId, f.region.boundaries.first().position, null)
        val choices = r.recorder.deletionChoices(selection)
        assertEquals(listOf("test:lower", "test:upper"), choices.map { it.route.routeId })
        assertNull(selection.exit)
        val before = Files.readString(r.store.primaryFile(f.worldId))
        assertThrows(IllegalArgumentException::class.java) { r.recorder.delete(choices.first()) { false }.render(java.util.Locale.US) }
        assertEquals(before, Files.readString(r.store.primaryFile(f.worldId)))
        assertTrue(r.recorder.delete(choices.first()) { true }.render(java.util.Locale.US).contains("Deleted route test:lower"))
        assertNull(r.runtime.currentDefinitions().protectionSignals.single().bindingId)
        assertThrows(IllegalArgumentException::class.java) { r.recorder.delete(choices.last()) { true }.render(java.util.Locale.US) }
        val last = r.recorder.deletionChoices(selection).single()
        assertTrue(r.recorder.delete(last) { true }.render(java.util.Locale.US).contains("Deleted route test:upper"))
        val empty = r.runtime.currentDefinitions().routeTables.single()
        assertEquals("test:table", empty.tableId)
        assertEquals(3L, empty.revision)
        assertTrue(empty.routes.isEmpty())
        assertTrue(r.binding.source.restrictions(f.currentTopology).forbiddenTraversals.isNotEmpty())
        assertEquals(device.copy(revision = 2, bindingId = null), r.runtime.currentDefinitions().protectionSignals.single())
        assertTrue(r.recorder.deletionChoices(selection).isEmpty())
    }

    @Test fun `unbound fixed devices keep models and require an exact guarded manual rebind`() {
        val f = Fixture()
        val placement = ModelPlacement.Ground(ModelPose(3.0, 64.0, 4.0, 0.0, 0.0, 0.0, 1.0))
        val fixed = FixedBlockSignalDefinition("test:unbound-fixed", 2, null, 5.0, "automatic:block", placement = placement)
        val other = fixed.copy(signalId = "test:other-unbound")
        val attached = AttachedSignalDefinition("test:unbound-attached", 1, fixed.signalId,
            DeviceModelDefinition("demo_signals:home", placement))
        val definitions = store().also { it.save(f.worldId, SimpleSignalsWorldDefinitions(f.worldId, 1, emptyList(), emptyList(),
            fixedBlockSignals = listOf(fixed, other), attachedSignals = listOf(attached))) }
        val registry = editRegistry(f.currentTopology)
        val infrastructure = MutableTopologyService(f.currentTopology, registry)
        val sources = CapturingSignalEpochSourceService()
        val models = RecordingModels()
        val manager = runtimeManager(plugin(), infrastructure.service, CapturingRoutePlanningService(), definitions, sources,
            modelPresentation = models, loadDefaultSignalCatalog = false)
        infrastructure.operatingDirectionOverride = f.currentTopology.railProjection.keys.associateWith { null }
        manager.loadSignalPacks(writeAutomaticSignalPack("proceed", modelVariants = listOf("stop", "proceed")))
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(f.worldId).status)
        val runtime = manager.authoringRuntime(f.worldId)!!
        assertTrue(runtime.authoringPublication().signalObservationGraph.logicalRootsByDeviceId.values.all { it.isEmpty() })
        assertTrue(sources.sources.getValue(f.worldId).all { it.areas.isEmpty() })
        SignalEpochRuntime(f.worldId.toString(), TrainRestrictionSnapshotStore(f.worldId.toString())).also {
            it.replaceSources(sources.sources.getValue(f.worldId))
            it.process(RailwayWorldFrameSnapshot.create(1, f.currentTopology.revision, emptyList(),
                ActiveRailContinuationsSnapshot.empty(f.currentTopology.revision)))
        }
        val states = runtime.sectionSignalSnapshot()!!.states
        assertEquals(setOf(fixed.signalId, other.signalId), states.keys)
        assertTrue(states.values.all { it is SectionSignalState.Unavailable })
        assertEquals(runtime.signalDeviceDiagnostics(fixed.signalId)!!.state,
            runtime.signalDeviceDiagnostics(attached.signalId)!!.state, "attached devices share the exact primary result")
        assertEquals(setOf("fixed-block-signal/${fixed.signalId}", "fixed-block-signal/${other.signalId}",
            "attached-signal/${attached.signalId}"), models.handles.map { it.id }.toSet())
        assertTrue(models.handles.all { it.submissions.isNotEmpty() })
        val invalid = manager.applyDefinitionPatch(f.worldId, 1,
            SimpleSignalsDefinitionPatch(upsertFixedBlockSignals = listOf(fixed.copy(revision = 3, controlledBlockId = "test:unknown"))))
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.VALIDATION_REJECTED, invalid.status)
        val block = FixedBlockDefinition("test:target", 0, listOf(FixedBlockTraversal(f.entry, ViewDirection.AB, 2.0, 8.0)))
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED, manager.applyDefinitionPatch(f.worldId, 1,
            SimpleSignalsDefinitionPatch(upsertFixedBlocks = listOf(block))).status)
        assertTrue(runtime.currentDefinitions().fixedBlockSignals.all { it.controlledBlockId == null })
        val backend = RuntimeSignalWandBackend(manager, infrastructure.service)
        val capture = backend.capture(f.worldId, "automatic:block")
        val device = backend.rebindDevices(capture).single { it.signalId == fixed.signalId }
        val targets = backend.rebindTargets(capture, device).filterIsInstance<FixedBlockSignalRebindTarget>()
        assertEquals(FixedBlockEntryDirection.entries.toSet(), targets.map { it.entry.direction }.toSet())
        val target = targets.first { it.entry.direction == FixedBlockEntryDirection.REVERSE }
        val before = Files.readString(definitions.primaryFile(f.worldId))
        assertThrows(IllegalArgumentException::class.java) { backend.rebind(capture, device, target) { false } }
        val release = protect(registry, f.currentTopology, f.entry, TopologyEditBlockerKind.LIVE_RUNTIME_CLAIM)
        try { assertTrue(backend.rebind(capture, device, target) { true }.text.render(java.util.Locale.US).contains("EDIT_BLOCKED")) }
        finally { release() }
        assertEquals(before, Files.readString(definitions.primaryFile(f.worldId)))
        assertTrue(backend.rebind(capture, device, target) { true }.text.render(java.util.Locale.US).contains("Rebound"))
        assertEquals(fixed.copy(revision = 3, controlledBlockId = block.blockId, entryDirection = FixedBlockEntryDirection.REVERSE),
            runtime.currentDefinitions().fixedBlockSignals.single { it.signalId == fixed.signalId })
        assertEquals(listOf(attached), runtime.currentDefinitions().attachedSignals)
        assertThrows(IllegalArgumentException::class.java) { backend.rebind(capture, device, targets.first()) { true } }
        models.cleanup.complete(null)
        manager.clearLocal().toCompletableFuture().join()
    }

    @Test fun `unbound indicator alone publishes no route on the first empty world baseline`() {
        val f = Fixture()
        val indicator = RouteIndicatorDefinition("test:unbound-indicator", 2, null, "stop", emptyMap(),
            DeviceModelDefinition("demo_signals:home", ModelPlacement.Ground(ModelPose.IDENTITY)))
        val definitions = store().also { it.save(f.worldId, SimpleSignalsWorldDefinitions(f.worldId, 1,
            emptyList(), emptyList(), routeIndicators = listOf(indicator))) }
        val sources = CapturingSignalEpochSourceService()
        val models = RecordingModels()
        val manager = runtimeManager(plugin(), MutableTopologyService(f.currentTopology).service,
            CapturingRoutePlanningService(), definitions, sources, modelPresentation = models, loadDefaultSignalCatalog = false)
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(f.worldId).status)
        assertEquals(1, sources.sources.getValue(f.worldId).size)
        assertTrue(sources.sources.getValue(f.worldId).single().areas.isEmpty())
        SignalEpochRuntime(f.worldId.toString(), TrainRestrictionSnapshotStore(f.worldId.toString())).also {
            it.replaceSources(sources.sources.getValue(f.worldId))
            it.process(RailwayWorldFrameSnapshot.create(1, f.currentTopology.revision, emptyList(),
                ActiveRailContinuationsSnapshot.empty(f.currentTopology.revision)))
        }
        val runtime = manager.authoringRuntime(f.worldId)!!
        val state = runtime.routeIndicatorStates().getValue(indicator.indicatorId)
        assertNull(state.selection)
        assertEquals("stop", state.modelVariant)
        assertEquals(mapOf("lamp" to "red"), models.byId("route-indicator/${indicator.indicatorId}").submissions.last().animationScenes())
        val invalid = manager.applyDefinitionPatch(f.worldId, 1, SimpleSignalsDefinitionPatch(upsertRouteIndicators = listOf(
            RouteIndicatorDefinition(indicator.indicatorId, 3, "test:unknown", "stop", emptyMap(), indicator.model))))
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.VALIDATION_REJECTED, invalid.status)
        models.cleanup.complete(null)
        manager.clearLocal().toCompletableFuture().join()
    }

    @Test fun `wand rebind restores unbound ground devices with exact revisions no pose changes and real edit protection`() {
        val f = Fixture()
        val registry = editRegistry { f.currentTopology }
        val r = RecordingFixture(f, registry, listOf(ProtectionRouteTableDefinition("test:table", 1,
            f.region.regionId, 1, listOf(f.upperRoute(), f.lowerRoute()))))
        val placement = ModelPlacement.Ground(ModelPose(2.0, 64.0, 3.0, 0.0, 0.0, 0.0, 1.0))
        val fixed = protectionSignal(ProtectionSignalKind.FIXED_ROUTE, f.upperRoute()).copy(bindingId = null,
            inspectionLeadTimeSeconds = 3.0, placement = placement)
        val current = fixed.copy(signalId = "test:current", kind = ProtectionSignalKind.CURRENT_ENTRY, placement = null)
        val attached = AttachedSignalDefinition("test:attached", 1, fixed.signalId,
            DeviceModelDefinition("demo_signals:home", placement))
        val created = r.manager.applyDefinitionPatch(f.worldId, 1, SimpleSignalsDefinitionPatch(
            upsertProtectionSignals = listOf(fixed.copy(revision = 0, bindingId = f.upperRoute().routeId),
                current.copy(revision = 0, bindingId = f.upperRoute().entryBoundaryId)),
            upsertAttachedSignals = listOf(attached)))
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED, created.status, created.diagnostics.toString())
        // Clearing existing bindings retains their old rail impact; creating only unbound ground
        // devices would correctly fail the manager's requirement for an affected rail.
        val unbound = r.manager.applyDefinitionPatch(f.worldId, 2,
            SimpleSignalsDefinitionPatch(upsertProtectionSignals = listOf(fixed, current)))
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED, unbound.status, unbound.diagnostics.toString())
        val backend = RuntimeSignalWandBackend(r.manager, r.infrastructure.service)
        fun capture() = backend.capture(f.worldId, "automatic:block")
        val captured = capture()
        val choices = backend.rebindDevices(captured)
        assertEquals(listOf(current.signalId, fixed.signalId), choices.map { it.signalId })
        val selected = choices.single { it is ProtectionSignalRebindChoice && it.definition == fixed }
        val targets = backend.rebindTargets(captured, selected)
        assertEquals(listOf("test:lower", "test:upper"), targets.map { it.bindingId })
        val target = targets.first()
        val before = Files.readString(r.store.primaryFile(f.worldId))
        listOf(TopologyEditBlockerKind.INFRASTRUCTURE_DEPENDENCY, TopologyEditBlockerKind.LIVE_RUNTIME_CLAIM).forEach { kind ->
            val release = protect(registry, f.currentTopology, f.lowerIn, kind)
            try { assertTrue(backend.rebind(captured, selected, target) { true }.text.render(java.util.Locale.US).contains("EDIT_BLOCKED")) }
            finally { release() }
            assertEquals(before, Files.readString(r.store.primaryFile(f.worldId)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            backend.rebind(captured, selected, ProtectionSignalRebindTarget("test:entry", "test:missing")) { true }
        }
        assertThrows(IllegalArgumentException::class.java) { backend.rebind(captured, selected, target) { false } }
        assertTrue(backend.rebind(captured, selected, target) { true }.text.render(java.util.Locale.US).contains("Rebound"))
        assertEquals(fixed.copy(revision = 2, bindingId = target.bindingId),
            r.runtime.currentDefinitions().protectionSignals.single { it.signalId == fixed.signalId })
        assertEquals(listOf(attached), r.runtime.currentDefinitions().attachedSignals)
        assertThrows(IllegalArgumentException::class.java) { backend.rebind(captured, selected, targets.last()) { true } }
        val latest = capture()
        val bound = backend.rebindDevices(latest).single { it.signalId == fixed.signalId }
        val saved = Files.readString(r.store.primaryFile(f.worldId))
        assertTrue(backend.rebind(latest, bound, target) { true }.text.render(java.util.Locale.US).contains("no changes"))
        assertEquals(saved, Files.readString(r.store.primaryFile(f.worldId)))
        val entryDevice = backend.rebindDevices(latest).single { it.signalId == current.signalId }
        val entryTarget = backend.rebindTargets(latest, entryDevice).single()
        assertEquals("test:entry", entryTarget.bindingId)
        assertTrue(backend.rebind(latest, entryDevice, entryTarget) { true }.text.render(java.util.Locale.US).contains("Rebound"))
        assertEquals(current.copy(revision = 2, bindingId = "test:entry"),
            r.runtime.currentDefinitions().protectionSignals.single { it.signalId == current.signalId })
    }

    @Test
    fun `adding an unmapped route preserves its selected identity and uses the explicit no route appearance`() {
        val f = Fixture()
        val upper = f.upperRoute()
        val lower = f.lowerRoute()
        val placement = ModelPlacement.Ground(ModelPose(3.0, 64.0, 5.0, 0.0, 0.0, 0.0, 1.0))
        // Deliberately different from the model's default dark scene: never fall back to defaults.
        val indicator = RouteIndicatorDefinition("test:partial-indicator", 1, upper.entryBoundaryId,
            "proceed", mapOf(upper.routeId to "stop"), DeviceModelDefinition("demo_signals:home", placement))
        val definitions = store()
        definitions.save(f.worldId, SimpleSignalsWorldDefinitions(f.worldId, 1, listOf(f.region),
            listOf(f.table(1, upper)), routeIndicators = listOf(indicator)))
        val infrastructure = MutableTopologyService(f.currentTopology)
        val sources = CapturingSignalEpochSourceService()
        val models = RecordingModels()
        val manager = runtimeManager(plugin(), infrastructure.service, CapturingRoutePlanningService(), definitions,
            sources, modelPresentation = models)
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(f.worldId).status)
        val runtime = manager.authoringRuntime(f.worldId)!!
        fun process(epoch: Long, selected: ProtectionRouteDefinition) {
            val active = manager.authoringRuntime(f.worldId)!!
            val compiled = active.authoringPublication().routeSelection.routesById.getValue(selected.routeId)
            val route = RouteSnapshot("test:partial-mission", epoch, f.currentTopology.revision,
                selected.traversals.mapIndexed { index, traversal ->
                    RouteSegmentSnapshot(traversal.railId, traversal.facing,
                        if (index == 0) 0.0 else traversal.lowerAbMeters,
                        if (index == selected.traversals.lastIndex) 10.0 else traversal.upperAbMeters)
                })
            val owner = InfrastructureOwner("test:partial-owner", 1)
            protectionTestEpoch(active, f.worldId.toString(), TrainRestrictionSnapshotStore(f.worldId.toString())).also {
                it.replaceSources(sources.sources.getValue(f.worldId))
                it.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(owner, ActiveRouteAdmissionSnapshot(owner,
                    "train", route, ActiveRouteAdmissionState.GRANTED, RouteBoundary(40.0), RouteBoundary(40.0))))
                val position = RailPositionSnapshot(f.entry, ViewDirection.AB, 0.0)
                it.process(RailwayWorldFrameSnapshot.create(epoch, f.currentTopology.revision,
                    listOf(TrainFrameSnapshot.create("train", "Train", epoch, epoch, 0.0, false, false,
                        emptyList(), position, position, emptyList(), emptyList(), emptyList(), emptyList(), emptyList())),
                    ActiveRailContinuationsSnapshot.create(f.currentTopology.revision, epoch,
                        compiled.requiredContinuations.associate { it.entry to it.selectedExit })))
            }
        }
        fun assertAppearance(routeId: String, variant: String, scene: String) {
            val state = manager.authoringRuntime(f.worldId)!!.routeIndicatorStates().getValue(indicator.indicatorId)
            assertTrue(requireNotNull(state.selection).available)
            assertEquals(routeId, requireNotNull(state.selection).routeId)
            assertEquals(variant, state.modelVariant)
            assertEquals(mapOf("lamp" to scene),
                models.byId("route-indicator/${indicator.indicatorId}").submissions.last().animationScenes())
        }
        process(1, upper)
        assertAppearance(upper.routeId, "stop", "red")
        val table = ProtectionRouteTableDefinition("test:throat-table", 2, f.region.regionId, 1, listOf(upper, lower))
        val added = manager.applyDefinitionPatch(f.worldId, 1,
            SimpleSignalsDefinitionPatch(upsertRouteTables = listOf(table)))
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED, added.status, added.diagnostics.toString())
        assertEquals(listOf(indicator), added.definitions!!.routeIndicators)
        process(2, lower) // First replacement baseline must already project an unmapped selection safely.
        assertAppearance(lower.routeId, "proceed", "green")
        process(3, upper)
        assertAppearance(upper.routeId, "stop", "red")

        val before = Files.readString(definitions.primaryFile(f.worldId))
        val publication = runtime.authoringPublication()
        val source = sources.sources.getValue(f.worldId)
        listOf(
            mapOf("test:missing" to "stop"),
            mapOf(upper.routeId to "unknown-variant")
        ).forEach { mapping ->
            val invalid = RouteIndicatorDefinition(indicator.indicatorId, 2, indicator.entryBoundaryId,
                indicator.noRouteModelVariant, mapping, indicator.model)
            assertEquals(SimpleSignalsDefinitionAuthoringStatus.VALIDATION_REJECTED,
                manager.applyDefinitionPatch(f.worldId, 2,
                    SimpleSignalsDefinitionPatch(upsertRouteIndicators = listOf(invalid))).status)
            assertEquals(before, Files.readString(definitions.primaryFile(f.worldId)))
            assertSame(publication, runtime.authoringPublication())
            assertSame(source, sources.sources.getValue(f.worldId))
        }
        val unmapped = RouteIndicatorDefinition(indicator.indicatorId, 2, indicator.entryBoundaryId,
            indicator.noRouteModelVariant, emptyMap(), indicator.model)
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED,
            manager.applyDefinitionPatch(f.worldId, 2,
                SimpleSignalsDefinitionPatch(upsertRouteIndicators = listOf(unmapped))).status)
        process(4, upper)
        assertAppearance(upper.routeId, "proceed", "green")
        manager.remove(f.worldId)
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(f.worldId).status)
        assertEquals(listOf(unmapped), manager.authoringRuntime(f.worldId)!!.currentDefinitions().routeIndicators)
        process(5, lower)
        assertAppearance(lower.routeId, "proceed", "green")
    }

    @Test
    fun `route deletion retains devices detaches only deleted route and keeps empty table closed across reload`() {
        val f = Fixture()
        val upper = f.upperRoute()
        val lower = f.lowerRoute()
        fun table(revision: Long, routes: List<ProtectionRouteDefinition>) =
            ProtectionRouteTableDefinition("test:throat-table", revision, f.region.regionId, 1, routes)
        val placement = ModelPlacement.Ground(ModelPose(3.0, 64.0, 5.0, 0.0, 0.0, 0.0, 1.0))
        val fixed = protectionSignal(ProtectionSignalKind.FIXED_ROUTE, upper).copy(placement = placement)
        val current = fixed.copy(signalId = "test:current", kind = ProtectionSignalKind.CURRENT_ENTRY,
            bindingId = upper.entryBoundaryId)
        val other = fixed.copy(signalId = "test:other", bindingId = lower.routeId)
        val attached = AttachedSignalDefinition("test:attached", 1, fixed.signalId,
            DeviceModelDefinition("demo_signals:home", placement))
        val indicator = RouteIndicatorDefinition("test:indicator", 1, upper.entryBoundaryId, "stop",
            mapOf(upper.routeId to "proceed", lower.routeId to "stop"),
            DeviceModelDefinition("demo_signals:home", placement))
        val store = store()
        store.save(f.worldId, SimpleSignalsWorldDefinitions(f.worldId, 1, listOf(f.region),
            listOf(table(1, listOf(upper, lower))), protectionSignals = listOf(fixed, current, other),
            routeIndicators = listOf(indicator), attachedSignals = listOf(attached)))
        val infrastructure = MutableTopologyService(f.currentTopology)
        val sources = CapturingSignalEpochSourceService()
        val plans = CapturingRoutePlanningService()
        val models = RecordingModels()
        val manager = runtimeManager(plugin(), infrastructure.service, plans, store, sources, modelPresentation = models)
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(f.worldId).status)
        val runtime = manager.authoringRuntime(f.worldId)!!
        val handles = models.handles.toList()
        val route = RouteSnapshot("test:deletion-mission", 1, f.currentTopology.revision,
            listOf(f.entry, f.upperIn, f.upperOut, f.exit).map {
                RouteSegmentSnapshot(it, ViewDirection.AB, 0.0, 10.0)
            })
        val owner = InfrastructureOwner("test:deletion-owner", 1)
        val continuations = runtime.authoringPublication().routeSelection.routesById.getValue(upper.routeId)
            .requiredContinuations.associate { it.entry to it.selectedExit }
        fun process(epoch: Long) {
            // Reuse the selector fixture's explicit 1m movement proof; this does not test live admission.
            val active = manager.authoringRuntime(f.worldId)!!
            protectionTestEpoch(active, f.worldId.toString(), TrainRestrictionSnapshotStore(f.worldId.toString())).also {
                it.replaceSources(sources.sources.getValue(f.worldId))
                it.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(owner, ActiveRouteAdmissionSnapshot(owner,
                    "train", route, ActiveRouteAdmissionState.GRANTED, RouteBoundary(40.0), RouteBoundary(40.0))))
                val position = RailPositionSnapshot(f.entry, ViewDirection.AB, 0.0)
                it.process(RailwayWorldFrameSnapshot.create(epoch, f.currentTopology.revision,
                    listOf(TrainFrameSnapshot.create("train", "Train", epoch, epoch, 0.0, false, false,
                        emptyList(), position, position, emptyList(), emptyList(), emptyList(), emptyList(), emptyList())),
                    ActiveRailContinuationsSnapshot.create(f.currentTopology.revision, epoch, continuations)))
            }
        }
        process(1)
        assertEquals(25.0, runtime.sectionSignalSnapshot()!!.states.getValue(fixed.signalId).effectiveSpeedLimitMetersPerSecond)
        assertEquals("proceed", runtime.routeIndicatorStates().getValue(indicator.indicatorId).modelVariant)
        assertEquals(mapOf("lamp" to "green"), models.byId("attached-signal/${attached.signalId}").submissions.last().animationScenes())
        assertEquals(mapOf("lamp" to "green"), models.byId("route-indicator/${indicator.indicatorId}").submissions.last().animationScenes())
        val patch = SimpleSignalsDefinitionPatch(upsertRouteTables = listOf(table(2, listOf(lower))))
        val before = Files.readString(store.primaryFile(f.worldId))
        val publication = runtime.authoringPublication()
        val source = sources.sources.getValue(f.worldId)
        listOf(TopologyEditBlockerKind.INFRASTRUCTURE_DEPENDENCY, TopologyEditBlockerKind.LIVE_RUNTIME_CLAIM).forEach { kind ->
            infrastructure.nextEditDecision = TopologyEditDecision(TopologyEditStatus.CONFLICT, listOf(
                TopologyEditBlocker(kind, InfrastructureOwner("test:operator", 1),
                    TopologyEditOverlap(setOf(f.upperIn), emptySet(), emptySet()))))
            assertEquals(SimpleSignalsDefinitionAuthoringStatus.EDIT_BLOCKED,
                manager.applyDefinitionPatch(f.worldId, 1, patch).status)
            assertEquals(before, Files.readString(store.primaryFile(f.worldId)))
            assertSame(publication, runtime.authoringPublication())
            assertSame(source, sources.sources.getValue(f.worldId))
        }
        infrastructure.nextEditDecision = TopologyEditDecision(TopologyEditStatus.GRANTED)
        listOf(
            fixed.copy(revision = 2, bindingId = upper.routeId),
            fixed.copy(revision = 2, bindingId = "test:typo"),
            fixed.copy(revision = 2, bindingId = null, signalType = "test:unknown-type")
        ).forEach { explicit ->
            val rejected = manager.applyDefinitionPatch(f.worldId, 1, SimpleSignalsDefinitionPatch(
                upsertRouteTables = patch.upsertRouteTables, upsertProtectionSignals = listOf(explicit)))
            assertEquals(SimpleSignalsDefinitionAuthoringStatus.VALIDATION_REJECTED, rejected.status)
            assertEquals(before, Files.readString(store.primaryFile(f.worldId)))
            assertSame(publication, runtime.authoringPublication())
        }
        val deleted = manager.applyDefinitionPatch(f.worldId, 1, patch)
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED, deleted.status, deleted.diagnostics.toString())
        val next = deleted.definitions!!
        assertEquals(fixed.copy(revision = 2, bindingId = null), next.protectionSignals.single { it.signalId == fixed.signalId })
        assertEquals(current, next.protectionSignals.single { it.signalId == current.signalId })
        assertEquals(other, next.protectionSignals.single { it.signalId == other.signalId })
        assertEquals(listOf(attached), next.attachedSignals)
        assertEquals(RouteIndicatorDefinition(indicator.indicatorId, 2, indicator.entryBoundaryId,
            indicator.noRouteModelVariant, mapOf(lower.routeId to "stop"), indicator.model), next.routeIndicators.single())
        assertEquals(handles, models.handles)
        assertTrue(handles.all { it.closeCalls == 0 })
        process(2)
        assertInstanceOf(SectionSignalState.Unavailable::class.java, runtime.sectionSignalSnapshot()!!.states.getValue(fixed.signalId))
        assertEquals(0.0, runtime.sectionSignalSnapshot()!!.states.getValue(fixed.signalId).effectiveSpeedLimitMetersPerSecond)
        assertEquals(mapOf("lamp" to "dark"), models.byId("attached-signal/${attached.signalId}").submissions.last().animationScenes())

        val empty = manager.applyDefinitionPatch(f.worldId, 2,
            SimpleSignalsDefinitionPatch(upsertRouteTables = listOf(table(3, emptyList()))))
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED, empty.status, empty.diagnostics.toString())
        assertEquals(listOf(table(3, emptyList())), empty.definitions!!.routeTables)
        assertEquals(listOf(attached), empty.definitions.attachedSignals)
        assertTrue(empty.definitions.routeIndicators.single().modelVariantsByRouteId.isEmpty())
        process(3)
        assertEquals("stop", runtime.routeIndicatorStates().getValue(indicator.indicatorId).modelVariant)
        assertEquals(mapOf("lamp" to "red"), models.byId("route-indicator/${indicator.indicatorId}").submissions.last().animationScenes())
        assertNull(runtime.routeIndicatorStates().getValue(indicator.indicatorId).selection?.routeId)
        assertTrue(plans.single(f.worldId).source.restrictions(f.currentTopology).forbiddenTraversals.isNotEmpty())
        assertEquals(handles, models.handles)
        assertTrue(handles.all { it.closeCalls == 0 })
        val saved = runtime.currentDefinitions()
        manager.remove(f.worldId)
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(f.worldId).status)
        assertEquals(saved, manager.authoringRuntime(f.worldId)!!.currentDefinitions())
        process(4)
        assertEquals("stop", manager.authoringRuntime(f.worldId)!!.routeIndicatorStates().getValue(indicator.indicatorId).modelVariant)
        // Recreating the route identity does not reattach the retained unbound signal.
        val restored = manager.applyDefinitionPatch(f.worldId, 3, SimpleSignalsDefinitionPatch(
            upsertRouteTables = listOf(table(4, listOf(upper))),
            upsertRouteIndicators = listOf(RouteIndicatorDefinition(indicator.indicatorId, 4,
                indicator.entryBoundaryId, "stop", mapOf(upper.routeId to "proceed"), indicator.model))))
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED, restored.status, restored.diagnostics.toString())
        val unbound = restored.definitions!!.protectionSignals.single { it.signalId == fixed.signalId }
        assertNull(unbound.bindingId)
        val rebound = manager.applyDefinitionPatch(f.worldId, 4, SimpleSignalsDefinitionPatch(
            upsertProtectionSignals = listOf(unbound.copy(revision = 3, bindingId = upper.routeId))))
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED, rebound.status, rebound.diagnostics.toString())
        assertEquals(fixed.copy(revision = 3), rebound.definitions!!.protectionSignals.single { it.signalId == fixed.signalId })
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = ["signal-overflow", "indicator-overflow", "save-failure"])
    fun `route deletion normalization failure preserves disk devices publication and source`(failure: String) {
        val f = Fixture()
        val route = f.upperRoute()
        val signal = protectionSignal(ProtectionSignalKind.FIXED_ROUTE, route).copy(
            revision = if (failure == "signal-overflow") Long.MAX_VALUE else 1)
        val indicator = RouteIndicatorDefinition("test:indicator",
            if (failure == "indicator-overflow") Long.MAX_VALUE else 1, route.entryBoundaryId,
            "stop", mapOf(route.routeId to "proceed"))
        val initial = SimpleSignalsWorldDefinitions(f.worldId, 1, listOf(f.region), listOf(f.table(1, route)),
            protectionSignals = listOf(signal), routeIndicators = listOf(indicator))
        val seedStore = store()
        seedStore.save(f.worldId, initial)
        val definitions = if (failure == "save-failure") SimpleSignalsWorldDefinitionStore(directory.toFile()) {
            throw java.io.IOException("injected route deletion staging failure")
        } else seedStore
        val infrastructure = MutableTopologyService(f.currentTopology)
        val sources = CapturingSignalEpochSourceService()
        val manager = runtimeManager(plugin(), infrastructure.service, CapturingRoutePlanningService(), definitions, sources)
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(f.worldId).status)
        val before = Files.readString(definitions.primaryFile(f.worldId))
        val publication = manager.authoringRuntime(f.worldId)!!.authoringPublication()
        val source = sources.sources.getValue(f.worldId)
        val table = ProtectionRouteTableDefinition("test:throat-table", 2, f.region.regionId, 1, emptyList())
        val result = manager.applyDefinitionPatch(f.worldId, 1,
            SimpleSignalsDefinitionPatch(upsertRouteTables = listOf(table)))
        assertEquals(if (failure == "save-failure") SimpleSignalsDefinitionAuthoringStatus.PERSISTENCE_FAILED
            else SimpleSignalsDefinitionAuthoringStatus.PATCH_REJECTED, result.status, result.diagnostics.toString())
        assertEquals(before, Files.readString(definitions.primaryFile(f.worldId)))
        assertEquals(initial, manager.authoringRuntime(f.worldId)!!.currentDefinitions())
        assertSame(publication, manager.authoringRuntime(f.worldId)!!.authoringPublication())
        assertSame(source, sources.sources.getValue(f.worldId))
    }

    @Test
    fun `online three device patch persists atomically validates revisions and removes without orphaning`() {
        val fixture = Fixture()
        val infrastructure = MutableTopologyService(fixture.currentTopology)
        val sources = CapturingSignalEpochSourceService()
        val store = store()
        val manager = runtimeManager(plugin(), infrastructure.service, CapturingRoutePlanningService(), store, sources)
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(fixture.worldId).status)
        val route = fixture.upperRoute()
        fun signal(id: String, kind: ProtectionSignalKind, binding: String?, revision: Long = 1L) =
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
        val rejected = manager.validateDefinitionPatch(fixture.worldId, 1, SimpleSignalsDefinitionPatch(
            removeRouteTableIds = listOf("test:throat-table")))
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.VALIDATED, rejected.status)
        assertNull(rejected.definitions!!.protectionSignals.single { it.signalId == fixed.signalId }.bindingId)
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
            IsolatedSimulationThreadMarker.run {
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
        assertEquals(listOf(1), signalSources.replacements.map(List<SignalEpochSource>::size))
        assertEquals(1, signalSources.retirements.single().epochSources.size)
        assertEquals(listOf(2), inspectionReplacementSizes)
        assertEquals(2, signalSources.retirements.single().inspectionSources.size)
        assertEquals(1, signalSources.retirements.single().routePlanningSources.size)
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
        assertEquals(listOf(1), signalSources.replacements.map(List<SignalEpochSource>::size))
        assertEquals(1, signalSources.retirements.single().epochSources.size)
        assertEquals(emptyList<SignalEpochSource>(), signalSources.sources.getValue(fixture.worldId))
        val failClosed = routeSources.single(fixture.worldId)
        assertThrows(IllegalStateException::class.java) {
            failClosed.source.restrictions(fixture.currentTopology)
        }
    }

    @Test
    fun `failed install detaches exact callbacks before engine close and late close preserves same UUID reinstall`() {
        val fixture = Fixture()
        val definitions = installationFailureDefinitions(fixture)
        val sources = CapturingSignalEpochSourceService()
        val routes = CapturingRoutePlanningService()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val retiring = CountDownLatch(1)
        val finishRetire = CountDownLatch(1)
        lateinit var manager: SimpleSignalsWorldRuntimeManager
        lateinit var old: SimpleSignalsWorldRuntime
        lateinit var logic: work.art1st.javarailways.simplesignals.signalpack.SignalLogicRuntime
        var first = true
        val routeService = proxy(RoutePlanningRestrictionSourceService::class.java) { _, arguments ->
            @Suppress("UNCHECKED_CAST")
            val bindings = arguments!![2] as Collection<RoutePlanningRestrictionBinding>
            if (first) {
                first = false
                old = reservedRuntime(manager, fixture.worldId)
                logic = actualLogic(old)
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                throw IllegalStateException("injected route source install failure")
            }
            routes.replaceRoutePlanningSources(arguments[0] as Plugin, arguments[1] as UUID, bindings)
        }
        sources.onRetire = { retired ->
            assertSame(old.currentSectionOccupancy()!!.sources.single(), retired.epochSources.single())
            assertSame(old.routePlanningBinding, retired.routePlanningSources.single())
            assertSame(old.fixedBlockInspectionSource, retired.inspectionSources[0])
            assertSame(old.protectionRegionInspectionSource, retired.inspectionSources[1])
            assertLogicOpen(logic)
            retiring.countDown()
            check(finishRetire.await(5, TimeUnit.SECONDS))
        }
        manager = runtimeManager(plugin(), MutableTopologyService(fixture.currentTopology).service,
            routeService, definitions, sources)
        val installation = CompletableFuture.supplyAsync { manager.install(fixture.worldId) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            release.countDown()
            assertTrue(retiring.await(5, TimeUnit.SECONDS))
            synchronized(logic) {
                finishRetire.countDown()
                assertEquals(SimpleSignalsWorldInstallStatus.UNAVAILABLE, installation.get(5, TimeUnit.SECONDS).status)
                assertTrue(sources.sources.getValue(fixture.worldId).isEmpty())
                assertLogicOpen(logic)
                sources.onRetire = {}
                assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(fixture.worldId).status)
                val replacement = manager.authoringRuntime(fixture.worldId)!!
                assertNotSame(old, replacement)
                assertSame(replacement.routePlanningBinding, routes.single(fixture.worldId))
            }
            awaitLogicClosed(logic)
            val replacement = manager.authoringRuntime(fixture.worldId)!!
            assertSame(replacement.currentSectionOccupancy()!!.sources.single(), sources.sources.getValue(fixture.worldId).single())
            assertSame(replacement.routePlanningBinding, routes.single(fixture.worldId))
            assertLogicOpen(actualLogic(replacement))
        } finally {
            release.countDown()
            finishRetire.countDown()
            installation.get(5, TimeUnit.SECONDS)
            manager.clearLocal().toCompletableFuture().join()
        }
    }

    @Test
    fun `installation failure preserves throwable chain separately from current content issue`() {
        val fixture = Fixture()
        val failure = IllegalStateException("route registration failed", IllegalArgumentException("root cause"))
        failure.addSuppressed(IllegalStateException("cleanup detail"))
        val reports = mutableListOf<Pair<String, Throwable>>()
        val issues = work.art1st.javarailways.bukkit.api.diagnostics.WorldIssueService()
        val manager = runtimeManager(plugin(), MutableTopologyService(fixture.currentTopology).service,
            CapturingRoutePlanningService(failure), installationFailureDefinitions(fixture),
            CapturingSignalEpochSourceService(), modelFailure = { context, error -> reports += context to error },
            worldIssues = issues)
        try {
            assertEquals(SimpleSignalsWorldInstallStatus.UNAVAILABLE, manager.install(fixture.worldId).status)
            val reported = reports.single { it.first.contains("route source could not be registered") }
            assertSame(failure, reported.second)
            assertTrue(reported.first.contains(fixture.worldId.toString()))
            assertEquals("root cause", reported.second.cause!!.message)
            assertEquals("cleanup detail", reported.second.suppressed.single().message)
            val issue = issues.snapshot(fixture.worldId).single { it.source == "simplesignals:installation" }
            assertTrue(issue.message.contains("route source"))
            assertFalse(issue.details.orEmpty().contains("at work.art1st"))
            issues.report(fixture.worldId, "other-plugin", work.art1st.javarailways.bukkit.api.diagnostics.WorldIssueInput(
                work.art1st.javarailways.bukkit.api.diagnostics.WorldIssueKind.WORLD,
                fixture.worldId.toString(), "unavailable", "Other content fault"))
            manager.remove(fixture.worldId)
            assertEquals("other-plugin", issues.snapshot(fixture.worldId).single().source)
            assertTrue(reports.any { it.second === failure }, "clearing current content must not erase error reports")
        } finally {
            manager.clearLocal().toCompletableFuture().join()
        }
    }

    @Test
    fun `failed source retirement retains engine and reservation until explicit cleanup succeeds`() {
        val fixture = Fixture()
        val definitions = installationFailureDefinitions(fixture)
        val sources = CapturingSignalEpochSourceService()
        val retirementFailure = IllegalStateException("injected source retirement failure")
        val reports = java.util.concurrent.CopyOnWriteArrayList<Pair<String, Throwable>>()
        lateinit var manager: SimpleSignalsWorldRuntimeManager
        lateinit var old: SimpleSignalsWorldRuntime
        val routes = CapturingRoutePlanningService(IllegalStateException("injected route source install failure"))
        sources.onRetire = {
            old = reservedRuntime(manager, fixture.worldId)
            throw retirementFailure
        }
        manager = runtimeManager(plugin(), MutableTopologyService(fixture.currentTopology).service,
            routes, definitions, sources, modelFailure = { context, error -> reports += context to error })
        try {
            val result = manager.install(fixture.worldId)
            assertEquals(SimpleSignalsWorldInstallStatus.UNAVAILABLE, result.status)
            assertTrue(result.diagnostics.any { it.contains("injected source retirement failure") })
            assertTrue(reports.any { it.first.contains("source cleanup") && it.second === retirementFailure })
            val logic = actualLogic(old)
            assertLogicOpen(logic)
            assertSame(old, reservedRuntime(manager, fixture.worldId))
            assertTrue(sources.sources.getValue(fixture.worldId).isNotEmpty())
            assertEquals(SimpleSignalsWorldInstallStatus.UNAVAILABLE, manager.install(fixture.worldId).status)
            assertEquals(1, sources.replacements.size, "a failed detach must block replacement, not orphan its engine")
            assertSame(retirementFailure, assertThrows(IllegalStateException::class.java) { manager.remove(fixture.worldId) })
            assertLogicOpen(logic)
            val clearFailure = assertThrows(java.util.concurrent.CompletionException::class.java) {
                manager.clearLocal().toCompletableFuture().join()
            }
            assertSame(retirementFailure, clearFailure.cause)
            assertSame(old, reservedRuntime(manager, fixture.worldId))
            assertLogicOpen(logic)
            sources.onRetire = {}
            manager.clearLocal().toCompletableFuture().join()
            assertTrue(sources.sources.getValue(fixture.worldId).isEmpty())
            awaitLogicClosed(logic)
        } finally {
            sources.onRetire = {}
            manager.clearLocal().toCompletableFuture().join()
        }
    }

    @Test
    fun `clear racing a partial install retains failed retirement for the next clear`() {
        val fixture = Fixture()
        val definitions = installationFailureDefinitions(fixture)
        val sources = CapturingSignalEpochSourceService()
        val retirementFailure = IllegalStateException("injected overlapping retirement failure")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        lateinit var manager: SimpleSignalsWorldRuntimeManager
        lateinit var old: SimpleSignalsWorldRuntime
        lateinit var logic: work.art1st.javarailways.simplesignals.signalpack.SignalLogicRuntime
        val routes = proxy(RoutePlanningRestrictionSourceService::class.java) { _, _ ->
            old = reservedRuntime(manager, fixture.worldId)
            logic = actualLogic(old)
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            throw IllegalStateException("injected partial install failure")
        }
        sources.onRetire = { throw retirementFailure }
        manager = runtimeManager(plugin(), MutableTopologyService(fixture.currentTopology).service,
            routes, definitions, sources)
        val installation = CompletableFuture.supplyAsync { manager.install(fixture.worldId) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val clear = manager.clearLocal().toCompletableFuture()
            assertFalse(clear.isDone, "clear waits for the candidate's actual management gate")
            release.countDown()
            assertEquals(SimpleSignalsWorldInstallStatus.UNAVAILABLE, installation.get(5, TimeUnit.SECONDS).status)
            val failure = assertThrows(java.util.concurrent.ExecutionException::class.java) { clear.get(5, TimeUnit.SECONDS) }
            assertSame(retirementFailure, failure.cause)
            assertSame(old, reservedRuntime(manager, fixture.worldId))
            assertLogicOpen(logic)
            sources.onRetire = {}
            manager.clearLocal().toCompletableFuture().get(5, TimeUnit.SECONDS)
            assertTrue(sources.sources.getValue(fixture.worldId).isEmpty())
            awaitLogicClosed(logic)
        } finally {
            release.countDown()
            installation.get(5, TimeUnit.SECONDS)
            sources.onRetire = {}
            manager.clearLocal().toCompletableFuture().join()
        }
    }

    private fun installationFailureDefinitions(fixture: Fixture): SimpleSignalsWorldDefinitionStore = store().also { definitions ->
        val block = FixedBlockDefinition("test:failed-install-block", 1,
            listOf(FixedBlockTraversal(fixture.entry, ViewDirection.AB, 0.0, 5.0)))
        definitions.save(fixture.worldId, SimpleSignalsWorldDefinitions(fixture.worldId, 1,
            emptyList(), emptyList(), listOf(block), listOf(
                FixedBlockSignalDefinition("test:failed-install-signal", 1, block.blockId, 0.0, "automatic:block"))))
    }

    private fun reservedRuntime(manager: SimpleSignalsWorldRuntimeManager, worldId: UUID): SimpleSignalsWorldRuntime {
        @Suppress("UNCHECKED_CAST")
        val directory = SimpleSignalsWorldRuntimeManager::class.java.getDeclaredField("runtimes")
            .also { it.isAccessible = true }.get(manager) as Map<UUID, SimpleSignalsWorldRuntime>
        return directory.getValue(worldId)
    }

    private fun assertLogicOpen(logic: work.art1st.javarailways.simplesignals.signalpack.SignalLogicRuntime) {
        assertThrows(work.art1st.javarailways.simplesignals.signalpack.SignalLogicException::class.java) {
            logic.evaluate("missing:test-rule", kotlinx.serialization.json.JsonObject(emptyMap()))
        }
    }

    private fun awaitLogicClosed(logic: work.art1st.javarailways.simplesignals.signalpack.SignalLogicRuntime) {
        val closed = CompletableFuture.runAsync {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (System.nanoTime() < deadline) {
                try { logic.evaluate("missing:test-rule", kotlinx.serialization.json.JsonObject(emptyMap())) }
                catch (_: work.art1st.javarailways.simplesignals.signalpack.SignalLogicException) { Thread.yield(); continue }
                catch (_: IllegalStateException) { return@runAsync }
            }
            error("failed installation's detached engine did not close")
        }
        closed.get(6, TimeUnit.SECONDS)
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
    fun `duplicate directional entry rejects atomically while the other entry can be added independently`() {
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
        val duplicate = signal.reconfigured(signalId = "test:duplicate")
        val patch = SimpleSignalsDefinitionPatch(upsertFixedBlockSignals = listOf(duplicate))
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.VALIDATION_REJECTED,
            manager.applyDefinitionPatch(f.worldId, 1, patch).status)
        assertEquals(0, infrastructure.permitAcquisitionCount)
        assertEquals(originalDisk, Files.readString(stored.primaryFile(f.worldId)))
        assertSame(originalSource, sources.sources.getValue(f.worldId).single())
        assertSame(originalPublication, originalRuntime.authoringPublication())
        val reverse = signal.reconfigured(signalId = "test:reverse", entryDirection = FixedBlockEntryDirection.REVERSE)
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED,
            manager.applyDefinitionPatch(f.worldId, 1, SimpleSignalsDefinitionPatch(
                upsertFixedBlockSignals = listOf(reverse))).status)
        assertEquals(setOf(signal.entry, reverse.entry), originalRuntime.currentDefinitions().fixedBlockSignals.map { it.entry }.toSet())
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
    fun `authoring cancellation inside atomic callback remains world unavailable and changes nothing`() {
        val fixture = Fixture()
        val store = store()
        store.save(
            fixture.worldId,
            SimpleSignalsWorldDefinitions(
                fixture.worldId,
                1L,
                emptyList(),
                emptyList()
            )
        )
        val primaryBefore = Files.readString(store.primaryFile(fixture.worldId))
        val infrastructure = MutableTopologyService(fixture.currentTopology)
        val sources = CapturingSignalEpochSourceService()
        val manager = runtimeManager(
            plugin(),
            infrastructure.service,
            CapturingRoutePlanningService(),
            store,
            sources
        )
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(fixture.worldId).status)
        val runtime = checkNotNull(manager.authoringRuntime(fixture.worldId))
        val publicationBefore = runtime.authoringPublication()
        val sourcesBefore = sources.sources.getValue(fixture.worldId)
        val commitCountBefore = sources.atomicCommitCount
        var guardCalls = 0

        val cancelled = manager.applyDefinitionPatch(
            fixture.worldId,
            1L,
            SimpleSignalsDefinitionPatch(upsertRegions = listOf(fixture.region)),
            authoringGuard = { ++guardCalls < 3 }
        )

        assertEquals(3, guardCalls, "the final guard must run after entering the atomic callback")
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.WORLD_UNAVAILABLE, cancelled.status)
        assertSame(publicationBefore, runtime.authoringPublication())
        assertSame(sourcesBefore, sources.sources.getValue(fixture.worldId))
        assertEquals(commitCountBefore, sources.atomicCommitCount)
        assertEquals(primaryBefore, Files.readString(store.primaryFile(fixture.worldId)))
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
        val updatedAreas = installed.single { it.sourceId == SECTION_OCCUPANCY_SOURCE_ID }.areas
        assertEquals(retainedSource.areas.single { it.areaId.toString() == block.blockId },
            updatedAreas.single { it.areaId.toString() == block.blockId })
        assertEquals(setOf("simplesignals:protection-route/test/lower", block.blockId), retainedSource.areas.map { it.areaId.toString() }.toSet())
        assertEquals(setOf("simplesignals:protection-route/test/upper", block.blockId), updatedAreas.map { it.areaId.toString() }.toSet())
        assertEquals(2L, updatedAreas.single { it.areaId.toString() != block.blockId }.definitionRevision)
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
            registry.setInfrastructureDependencies(planOwner, InfrastructureDependencyRequest(owner,
                InfrastructureDependencies(emptySet(), emptySet(), resources))).status)
        assertTrue(registry.claimSnapshots().isEmpty(), "edit dependencies acquire no train resources")

        // A dependency must also coexist with the actual train resource claim.
        val trainOwner = Any()
        val claim = InfrastructureOwner("test:train", 1L)
        assertEquals(InfrastructureMutationStatus.ACQUIRED,
            registry.acquireClaim(trainOwner, InfrastructureClaimRequest(claim,
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
    fun `unrelated topology change at atomic callback reprepares fixed block edit`() {
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
            signalSources.beforeCommitAction = {}
            infrastructure.topology = fixture.topology(fixture.currentTopology.revision + 1L,
                listOf(UUID(0, 99)), fixture.currentTopology.railIdentities)
        }
        val second = FixedBlockDefinition(
            first.blockId,
            2L,
            listOf(FixedBlockTraversal(fixture.entry, ViewDirection.AB, 1.0, 6.0))
        )

        val applied = manager.applyDefinitionPatch(
            fixture.worldId,
            1L,
            SimpleSignalsDefinitionPatch(upsertFixedBlocks = listOf(second))
        )

        assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED, applied.status)
        assertNotSame(retainedSource, signalSources.sources.getValue(fixture.worldId).single())
        assertNotEquals(originalPrimary, Files.readString(store.primaryFile(fixture.worldId)))
        assertEquals(2, signalSources.atomicCommitCount)
        assertEquals(2, infrastructure.permitCloseCount)
    }

    @Test
    fun `continued unrelated topology edits ask for retry after one authoring reprepare`() {
        val fixture = Fixture()
        val infrastructure = MutableTopologyService(fixture.currentTopology)
        val store = store()
        val original = FixedBlockDefinition("test:retry-block", 1L,
            listOf(FixedBlockTraversal(fixture.entry, ViewDirection.AB, 0.0, 5.0)))
        store.save(fixture.worldId, SimpleSignalsWorldDefinitions(fixture.worldId, 1L,
            emptyList(), emptyList(), listOf(original)))
        val originalPrimary = Files.readString(store.primaryFile(fixture.worldId))
        val sources = CapturingSignalEpochSourceService()
        val manager = runtimeManager(plugin(), infrastructure.service, CapturingRoutePlanningService(), store, sources)
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(fixture.worldId).status)
        val retained = sources.sources.getValue(fixture.worldId).single()
        var edits = 0
        sources.beforeCommitAction = {
            edits++
            infrastructure.topology = fixture.topology(fixture.currentTopology.revision + edits,
                listOf(UUID(0L, 90L + edits)), fixture.currentTopology.railIdentities)
        }

        val result = manager.applyDefinitionPatch(fixture.worldId, 1L, SimpleSignalsDefinitionPatch(
            upsertFixedBlocks = listOf(FixedBlockDefinition(original.blockId, 2L,
                listOf(FixedBlockTraversal(fixture.entry, ViewDirection.AB, 1.0, 6.0))))))

        assertEquals(SimpleSignalsDefinitionAuthoringStatus.RETRY_REQUIRED, result.status)
        assertEquals(2, edits, "only one full reprepare is allowed per authoring request")
        assertEquals(1, sources.atomicCommitCount, "only the initial installation committed; both stale callbacks aborted")
        assertEquals(2, infrastructure.permitCloseCount)
        assertSame(retained, sources.sources.getValue(fixture.worldId).single())
        assertEquals(originalPrimary, Files.readString(store.primaryFile(fixture.worldId)))
        assertEquals(1L, manager.authoringRuntime(fixture.worldId)!!.authoringPublication().definitions.definitionRevision)
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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource("false,false", "false,true", "true,true")
    fun `formal rule execution is independent across real live and simulation worlds`(firstSimulation: Boolean, secondSimulation: Boolean) {
        initializeDomainPlugins()
        val source = actualSignalWorld()
        val worlds = java.util.concurrent.ConcurrentHashMap<UUID, work.art1st.javarailways.core.physics.CoreWorld>()
        worlds[UUID.fromString(source.runtimeId)] = source
        val infrastructure = actualInfrastructure(worlds)
        val sources = CapturingSignalEpochSourceService()
        val manager = runtimeManager(plugin(), infrastructure, CapturingRoutePlanningService(), store(), sources,
            loadDefaultSignalCatalog = false)
        manager.loadSignalPacks(Path.of(requireNotNull(javaClass.getResource("/examples/jr-four-light/signal-packs")).toURI()))
        val sourceId = UUID.fromString(source.runtimeId)
        store().save(sourceId, actualDefinitions(source))
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(sourceId).status)
        val captured = manager.prepareSimulation(sourceId)
        val first = if (firstSimulation) actualSimulation(source, captured) else actualLive(source, manager, sources)
        val second = if (secondSimulation) actualSimulation(source, captured) else {
            val other = actualSignalWorld()
            val id = UUID.fromString(other.runtimeId)
            worlds[id] = other
            store().save(id, actualDefinitions(other))
            assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(id).status)
            actualLive(other, manager, sources)
        }
        val executor = java.util.concurrent.Executors.newCachedThreadPool()
        val locked = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val logic = actualLogic(first.runtime)
        val blocker = executor.submit {
            synchronized(logic) { locked.countDown(); check(release.await(10, java.util.concurrent.TimeUnit.SECONDS)) }
        }
        try {
            assertTrue(locked.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val waiting = executor.submit { first.step() }
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            var enteredActualMonitor = false
            while (System.nanoTime() < deadline && !enteredActualMonitor) {
                enteredActualMonitor = java.lang.management.ManagementFactory.getThreadMXBean().dumpAllThreads(false, false).any {
                    it.threadState == Thread.State.BLOCKED && it.lockInfo?.identityHashCode == System.identityHashCode(logic)
                }
                if (!enteredActualMonitor) Thread.yield()
            }
            assertTrue(enteredActualMonitor, "the real installed source must reach SignalLogicRuntime.evaluate's monitor")
            val headBefore = second.world.allTrains.single().publishedFrame.head.arcLengthInAbMeters
            executor.submit { second.step() }.get(5, java.util.concurrent.TimeUnit.SECONDS)
            assertFalse(waiting.isDone)
            assertTrue(second.world.allTrains.single().publishedFrame.head.arcLengthInAbMeters > headBefore)
            assertEquals(listOf("proceed", "slow", "caution", "stop"), actualAspects(second.runtime))
            assertEquals(second.world.latestWorldFrame().physicsEpoch, second.runtime.sectionSignalSnapshot()!!.stamp.physicsEpoch)
            assertEquals(listOf(null, 65.0 / 3.6, 45.0 / 3.6, 0.0), actualLimits(second.runtime))
            val sameStarted = CountDownLatch(1)
            val sameWorld = executor.submit { sameStarted.countDown(); first.step() }
            assertTrue(sameStarted.await(5, TimeUnit.SECONDS))
            assertFalse(sameWorld.isDone, "the same world cannot bypass its outstanding physics epoch")
            release.countDown()
            blocker.get(5, java.util.concurrent.TimeUnit.SECONDS)
            waiting.get(5, java.util.concurrent.TimeUnit.SECONDS)
            sameWorld.get(5, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(2L, first.world.latestWorldFrame().physicsEpoch)
            first.close()
            executor.submit { second.step() }.get(5, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(listOf("proceed", "slow", "caution", "stop"), actualAspects(second.runtime))
        } finally {
            release.countDown()
            executor.shutdownNow()
            executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)
            first.close()
            second.close()
            manager.clearLocal().toCompletableFuture().join()
            if (firstSimulation) closeActualWorld(source)
        }
    }

    @Test
    fun `detached simulation installs formal rules preserves captured values and updates real occupancy on release`() {
        initializeDomainPlugins()
        val source = actualSignalWorld()
        val sourceId = UUID.fromString(source.runtimeId)
        val worlds = java.util.concurrent.ConcurrentHashMap<UUID, work.art1st.javarailways.core.physics.CoreWorld>()
        worlds[sourceId] = source
        val sources = CapturingSignalEpochSourceService()
        val manager = runtimeManager(plugin(), actualInfrastructure(worlds), CapturingRoutePlanningService(), store(), sources,
            loadDefaultSignalCatalog = false)
        manager.loadSignalPacks(Path.of(requireNotNull(javaClass.getResource("/examples/jr-four-light/signal-packs")).toURI()))
        store().save(sourceId, actualDefinitions(source))
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(sourceId).status)
        val captured = manager.prepareSimulation(sourceId)
        manager.clearLocal().toCompletableFuture().join()
        actualSimulation(source, captured).use { simulation ->
            simulation.step()
            assertEquals(listOf("proceed", "slow", "caution", "stop"), actualAspects(simulation.runtime))
            val occupied = simulation.runtime.sectionSignalSnapshot()!!
            simulation.world.onOwnerForTest {
                simulation.world.allTrains.single().destroy()
            }.toCompletableFuture().join()
            simulation.step()
            assertEquals(65.0 / 3.6, actualLimits(simulation.runtime).last(),
                "an empty terminal block still brakes for the physical track end")
            assertEquals(0.0, occupied.states.getValue("test:actual-signal-3").effectiveSpeedLimitMetersPerSecond)
            assertEquals(simulation.world.runtimeId, simulation.runtime.sectionSignalSnapshot()!!.stamp.worldId)
            assertEquals(sourceId, actualDefinitions(source).worldId)
        }
        closeActualWorld(source)
    }

    @Test
    fun `capture create run and close preserve nonempty live claims dispatcher frames and formal signals`() {
        initializeDomainPlugins()
        val source = actualSignalWorld()
        val sourceId = UUID.fromString(source.runtimeId)
        val sourceRail = source.allRails.single()
        val ownerPlugin = plugin()
        val sources = CapturingSignalEpochSourceService()
        val manager = runtimeManager(ownerPlugin, actualInfrastructure(mapOf(sourceId to source)),
            CapturingRoutePlanningService(), store(), sources, loadDefaultSignalCatalog = false)
        manager.loadSignalPacks(Path.of(requireNotNull(javaClass.getResource("/examples/jr-four-light/signal-packs")).toURI()))
        store().save(sourceId, actualDefinitions(source))
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(sourceId).status)
        val liveSignal = manager.authoringRuntime(sourceId)!!
        val registry = source.infrastructureCoordination
        val claimHost = Any()
        val requester = Any()
        val policy = FixedBlockRouteResourcePolicy(ownerPlugin,
            manager::validateRouteAdmission, manager::fixedBlockSnapshot)
        val validator = registry.registerResourceValidator { resource ->
            resource is StableInfrastructureResource && resource.typeId == FIXED_BLOCK_RESOURCE_TYPE &&
                policy.exists(sourceId, resource.resourceId)
        }
        var observation: RailwayObservation? = null
        lateinit var admission: RouteResourceAdmissionRuntime
        val service = object : RouteResourceAdmissionService {
            override fun request(intent: RouteResourceIntent) = admission.request(requester, intent)
            override fun progress(handle: RouteAdmissionHandle) = admission.progress(requester, handle)
            override fun suspendReleaseForReversal(grantId: RouteGrantId) = admission.suspendReleaseForReversal(requester, grantId)
            override fun rebaseAfterReversal(intent: RouteResourceIntent) = admission.rebaseAfterReversal(requester, intent)
            override fun endGrant(grantId: RouteGrantId) = admission.endGrant(requester, grantId)
        }
        val contextProvider = TimetableRouteAdmissionContextProvider {
            observation?.let { TimetableRouteAdmissionContext(it, service) }
        }
        val dependencies = object : InfrastructureDependencyService {
            override fun set(request: InfrastructureDependencyRequest) = registry.setInfrastructureDependencies(requester, request)
            override fun remove(owner: InfrastructureOwner) = registry.removeInfrastructureDependencies(requester, owner)
        }
        val domain = OperationalWorldRuntime.create(source, source.runtimeId,
            basicDataFactory = { changed -> work.art1st.javarailways.basic.BasicWorldData(
                railMetadata = source.basicWorldData.railMetadata,
                operatingDirections = work.art1st.javarailways.basic.rail.OperatingDirectionStore(changed)).also {
                it.operatingDirections.put(sourceRail.id, ViewDirection.AB)
            } }, dispatcherFactory = { runtime ->
                admission = RouteResourceAdmissionRuntime.create(source.runtimeId, source, registry, claimHost,
                    { observation }, { intent -> policy.contribute(sourceId, intent).map { ownerPlugin to it } },
                    { _, contribution -> policy.validateRouteContribution(ownerPlugin, sourceId,
                        contribution.resource.resourceId, contribution.preparation)?.let { RouteAdmissionFailure(it, "invalid contribution") } },
                    { _, _ -> null }, runtime.signalEpochRuntime::publishAdmissionUpdate) { _, failure -> throw failure }
                TimetableDispatcher(contextProvider, runtime.signalRestrictions, runtime.inspectionRuntime, dependencies,
                    atoFactory = TimetableAtoFactory { train, world ->
                        SimpleATO(train, world, HandleTrainActuator(train, TrainControlConfig(),
                            train.cars.map { CarControlConfig() }, 9.81), AtoSafetyProfile(),
                            runtime.inspectionRuntime::maximumDeclaredLeadTimeSeconds)
                    })
            })
        domain.inspectionRuntime.replaceSources(listOf(liveSignal.fixedBlockInspectionSource, liveSignal.protectionRegionInspectionSource))
        val live = actualLive(source, manager, sources, domain, {
            observation = RailwayObservation.fromCore(source.runtimeId, source.latestWorldFrame())
            admission.consumeObservation(checkNotNull(observation))
        }) { world ->
            val train = work.art1st.javarailways.core.physics.train.createTrainWithConfig("actual", world.allRails.single(), world, listOf(10.0))
            world.onOwnerForTest {
                train.setLocation(work.art1st.javarailways.core.physics.rail.RailLocationWithFacing(world.allRails.single().viewAB, 1000.0))
                train.commit()
            }
        }
        fun request(context: TimetableRouteAdmissionContext, railId: UUID, topologyRevision: Long) = run {
            val train = context.observation.trains.single()
            val route = RouteSnapshot("test:preservation-route", 1, topologyRevision, listOf(
                RouteSegmentSnapshot(railId, ViewDirection.AB, train.tail.arcLengthInAbMeters, train.tail.arcLengthInAbMeters + 100.0)))
            assertInstanceOf(RouteAdmissionResult.Granted::class.java, context.service.request(RouteResourceIntent(
                "test:preservation-request", InfrastructureOwner("test:preservation-owner", 1), train.trainId,
                context.observation.stamp, route, RouteBoundary(60.0))))
        }
        try {
            live.step()
            val baselineSignal = liveSignal.sectionSignalSnapshot()!!
            assertEquals(source.latestWorldFrame().physicsEpoch, baselineSignal.stamp.physicsEpoch)
            source.onOwnerForTest {
                domain.basicData.wayPoints.add(work.art1st.javarailways.basic.WayPoint("live-origin", sourceRail.id, 1000.0))
                domain.basicData.wayPoints.add(work.art1st.javarailways.basic.WayPoint("live-target", sourceRail.id, 1300.0))
                domain.data.timetables.add(Timetable("live-preservation", services = listOf(TimetableService("service",
                    TrainSelector.TrainId("actual"), listOf(TimetableStep.Check("live-origin"),
                        TimetableStep.At(1000, TimetableAction.GoTo("live-target")))))))
                assertInstanceOf(TimetableServiceStartResult.Started::class.java,
                    domain.data.dispatcher.startService(source, "live-preservation", "service"))
            }
            live.step()
            source.onOwnerForTest { request(checkNotNull(contextProvider.current()), sourceRail.id, source.topologyRevision) }
            live.step()
            assertEquals(TimetableServiceRunState.WAITING_TIMETABLE_TIME, domain.data.dispatcher.statuses().single().state)
            val blockToken = StableInfrastructureResource(FIXED_BLOCK_RESOURCE_TYPE, "test:actual-block-3")
            assertTrue(registry.claimSnapshots().single().footprint.resources.any { it is RailIntervalResource })
            assertTrue(blockToken in registry.claimSnapshots().single().footprint.resources)
            assertEquals(listOf("proceed", "slow", "caution", "stop"), actualAspects(liveSignal))
            assertEquals(work.art1st.javarailways.core.api.physics.WorldExecutionMode.LIVE, source.executionMode)

            // The LIVE fixture has published real physics, a real claim and an acquired Dispatcher run.
            // Its fallback is disabled; from here only the two detached targets are advanced.
            val frame = source.latestWorldFrame()
            val trainFrames = source.allTrains.associate { it.id to it.publishedFrame }
            val claims = registry.claimSnapshots()
            val statuses = domain.data.dispatcher.statuses()
            val signal = liveSignal.sectionSignalSnapshot()!!
            val restrictions = domain.signalRestrictions.snapshotsFor("actual")
            val completed = domain.signalRestrictions.completedObservationStamp()
            // Unchanged signal inputs stay IDLE; the coherent completed boundary still advances.
            assertSame(baselineSignal, signal)
            assertTrue(signal.stamp.physicsEpoch < frame.physicsEpoch)
            assertEquals(frame.physicsEpoch, checkNotNull(completed).physicsEpoch)
            assertEquals(source.runtimeId, completed.worldId)
            fun assertLiveUnchanged() {
                assertSame(frame, source.latestWorldFrame())
                assertEquals(trainFrames.keys, source.allTrains.map { it.id }.toSet())
                source.allTrains.forEach { assertSame(trainFrames.getValue(it.id), it.publishedFrame) }
                assertEquals(claims, registry.claimSnapshots())
                assertEquals(statuses, domain.data.dispatcher.statuses())
                assertSame(signal, liveSignal.sectionSignalSnapshot())
                assertEquals(restrictions, domain.signalRestrictions.snapshotsFor("actual"))
                assertEquals(completed, domain.signalRestrictions.completedObservationStamp())
                assertEquals(ViewDirection.AB, source.basicWorldData.operatingDirections[sourceRail.id])
            }
            val captured = source.onOwnerForTest { manager.prepareSimulation(sourceId) }
            assertLiveUnchanged()
            actualSimulation(source, captured).use { first ->
                assertLiveUnchanged()
                actualSimulation(source, captured).use { second ->
                    assertLiveUnchanged()
                    assertEquals(listOf("actual"), first.world.allTrains.map { it.id })
                    assertEquals(listOf("actual"), second.world.allTrains.map { it.id })
                    assertNotSame(source.allTrains.single(), first.world.allTrains.single())
                    assertNotSame(first.world.allTrains.single(), second.world.allTrains.single())
                    assertNotEquals(source.runtimeId, first.world.runtimeId)
                    assertNotEquals(first.world.runtimeId, second.world.runtimeId)
                    listOf(first, second).forEach { simulation ->
                        simulation.step()
                        simulation.world.onOwnerForTest { request(checkNotNull(simulation.simulation!!.routeAdmissionHost.contextProvider.current()),
                            simulation.world.allRails.single().id, simulation.world.topologyRevision) }
                        assertTrue(blockToken in simulation.world.infrastructureCoordination.claimSnapshots().single().footprint.resources)
                        assertEquals(listOf("proceed", "slow", "caution", "stop"), actualAspects(simulation.runtime))
                        assertLiveUnchanged()
                    }
                    val beforeDirectionEdit = actualLimits(first.runtime)
                    first.world.onOwnerForTest { first.world.basicWorldData.operatingDirections.put(sourceRail.id, ViewDirection.BA) }
                    first.step()
                    assertEquals(beforeDirectionEdit, actualLimits(first.runtime), "track direction does not disable the physical block")
                    assertTrue(blockToken in first.world.infrastructureCoordination.claimSnapshots().single().footprint.resources)
                    assertLiveUnchanged()
                    first.close()
                    assertTrue(first.world.infrastructureCoordination.claimSnapshots().isEmpty())
                    assertLiveUnchanged()
                    val before = second.world.latestWorldFrame().physicsEpoch
                    second.step()
                    assertEquals(before + 1, second.world.latestWorldFrame().physicsEpoch)
                    assertEquals(listOf("proceed", "slow", "caution", "stop"), actualAspects(second.runtime))
                    assertTrue(second.world.infrastructureCoordination.claimSnapshots().isNotEmpty())
                    assertLiveUnchanged()
                }
                assertLiveUnchanged()
            }
            assertLiveUnchanged()
        } finally {
            live.close()
            admission.close()
            registry.releasePlatformOwner(claimHost)
            validator.close()
            domain.close()
            manager.clearLocal().toCompletableFuture().join()
        }
    }

    @Test
    fun `a world source commit wait does not hold manager directory or block another install or detached capture`() {
        val fixture = Fixture()
        val otherId = UUID.randomUUID()
        val infrastructure = MutableTopologyService(fixture.currentTopology)
        val sources = CapturingSignalEpochSourceService()
        val manager = runtimeManager(plugin(), infrastructure.service, CapturingRoutePlanningService(), store(), sources)
        manager.install(fixture.worldId)
        val runtime = manager.authoringRuntime(fixture.worldId)!!
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = java.util.concurrent.atomic.AtomicBoolean(true)
        sources.beforeCommitAction = {
            if (first.compareAndSet(true, false)) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        }
        val edit = CompletableFuture.supplyAsync({ manager.applyDefinitionPatch(fixture.worldId, 0,
            SimpleSignalsDefinitionPatch(upsertRegions = listOf(fixture.region)), runtime) }, concurrentExecutor)
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED,
                CompletableFuture.supplyAsync({ manager.install(otherId) }, concurrentExecutor).get(2, TimeUnit.SECONDS).status)
            // This is the exact owner->capture side of the former AB/BA hazard: capture must not wait for A's management gate.
            val captured = CompletableFuture.supplyAsync({ manager.prepareSimulation(fixture.worldId) }, concurrentExecutor).get(2, TimeUnit.SECONDS)
            assertEquals("simplesignals", captured.id)
            assertEquals(0L, runtime.currentDefinitions().definitionRevision)
            assertFalse(edit.isDone)
        } finally { release.countDown() }
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED, edit.get(5, TimeUnit.SECONDS).status)
        manager.clearLocal().toCompletableFuture().join()
    }

    @Test
    fun `real simulation admission claims fixed blocks releases by tail and keeps signals independent of direction edits`() {
        initializeDomainPlugins()
        val source = actualSignalWorld()
        val id = UUID.fromString(source.runtimeId)
        val sources = CapturingSignalEpochSourceService()
        val manager = runtimeManager(plugin(), actualInfrastructure(mapOf(id to source)), CapturingRoutePlanningService(), store(), sources,
            loadDefaultSignalCatalog = false)
        manager.loadSignalPacks(Path.of(requireNotNull(javaClass.getResource("/examples/jr-four-light/signal-packs")).toURI()))
        store().save(id, actualDefinitions(source))
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(id).status)
        val captured = manager.prepareSimulation(id)
        actualSimulation(source, captured).use { simulation ->
            val world = simulation.world
            val rail = world.allRails.single()
            val second = work.art1st.javarailways.core.physics.train.createTrainWithConfig("following", rail, world, listOf(10.0))
            world.onOwnerForTest {
                second.setLocation(work.art1st.javarailways.core.physics.rail.RailLocationWithFacing(rail.viewAB, 1300.0))
                second.commit()
            }
            simulation.step()
            fun request(trainId: String, requestId: String): work.art1st.javarailways.basic.api.route.RouteAdmissionResult = world.onOwnerForTest {
                val context = checkNotNull(simulation.simulation!!.routeAdmissionHost.contextProvider.current())
                val train = context.observation.trains.single { it.trainId == trainId }
                val route = RouteSnapshot("test:actual-route-$trainId", 1L, world.topologyRevision,
                    listOf(RouteSegmentSnapshot(rail.id, ViewDirection.AB, train.tail.arcLengthInAbMeters,
                        train.tail.arcLengthInAbMeters + 100.0)))
                context.service.request(work.art1st.javarailways.basic.api.route.RouteResourceIntent(requestId,
                    InfrastructureOwner("test:actual-owner-$trainId", 1L), trainId, context.observation.stamp, route, RouteBoundary(60.0)))
            }
            val first = request("actual", "test:first")
            assertTrue(first is work.art1st.javarailways.basic.api.route.RouteAdmissionResult.Granted, first.toString())
            val conflicting = request("following", "test:following")
            assertTrue(conflicting is work.art1st.javarailways.basic.api.route.RouteAdmissionResult.Rejected, conflicting.toString())
            conflicting as work.art1st.javarailways.basic.api.route.RouteAdmissionResult.Rejected
            assertEquals(work.art1st.javarailways.basic.api.route.RouteAdmissionFailureCode.RESOURCE_CONFLICT, conflicting.failure.code)
            assertTrue(conflicting.failure.blockers.any {
                it is work.art1st.javarailways.basic.api.route.RouteAdmissionBlocker.ConflictingResource &&
                    it.resource == StableInfrastructureResource(FIXED_BLOCK_RESOURCE_TYPE, "test:actual-block-3")
            }, "disjoint physical intervals conflict on the real SimpleSignals block token")
            simulation.step()
            repeat(175) { simulation.step(200.0) }
            val afterTail = request("following", "test:following-after-tail")
            assertTrue(afterTail is work.art1st.javarailways.basic.api.route.RouteAdmissionResult.Granted, afterTail.toString())
            val beforeDirectionEdit = actualLimits(simulation.runtime)
            val claimsBeforeDirectionEdit = world.infrastructureCoordination.claimSnapshots()
            world.onOwnerForTest {
                world.basicWorldData.operatingDirections.put(rail.id, ViewDirection.BA)
            }
            simulation.step()
            assertEquals(beforeDirectionEdit, actualLimits(simulation.runtime), "track direction does not disable fixed-block signals")
            assertEquals(claimsBeforeDirectionEdit, world.infrastructureCoordination.claimSnapshots())
            world.onOwnerForTest { world.basicWorldData.operatingDirections.put(rail.id, ViewDirection.AB) }
            simulation.step()
            assertEquals("stop", actualAspects(simulation.runtime).last())
            assertEquals(ViewDirection.AB, source.basicWorldData.operatingDirections[rail.id], "SIM edits cannot mutate LIVE values")
        }
        manager.clearLocal().toCompletableFuture().join()
        closeActualWorld(source)
    }

    @Test
    fun `detached simulation rule failure reaches registered module logger with throwable`() {
        initializeDomainPlugins()
        val source = actualSignalWorld()
        val records = java.util.concurrent.CopyOnWriteArrayList<java.util.logging.LogRecord>()
        val logger = java.util.logging.Logger.getLogger("work.art1st.javarailways.simplesignals")
        val handler = object : java.util.logging.Handler() {
            override fun publish(record: java.util.logging.LogRecord) { records += record }
            override fun flush() = Unit
            override fun close() = Unit
        }
        logger.addHandler(handler)
        try {
            val definitions = actualDefinitions(source)
            val failing = SimpleSignalsWorldDefinitions(definitions.worldId, 1, emptyList(), emptyList(),
                definitions.fixedBlocks, definitions.fixedBlockSignals.map { it.reconfigured(signalType = "automatic:block") })
            val rules = work.art1st.javarailways.simplesignals.signalpack.SignalPackLoader.load(
                writeAutomaticSignalPack(script = "function evaluate(input, output) { throw new Error('detached-rule-failure'); }")).rules
            actualSimulation(source, SimpleSignalsSimulationFeature("simplesignals", failing, rules, 5000.0)).use { simulation ->
                simulation.step()
                val errors = records.filter { it.message.contains("detached-rule-failure") ||
                    it.thrown?.message.orEmpty().contains("detached-rule-failure") }
                assertTrue(errors.isNotEmpty(), "real detached rule failures must reach the module logger")
                assertTrue(errors.all { it.level == java.util.logging.Level.SEVERE && it.thrown != null })
                assertTrue(errors.all { it.message.contains(simulation.world.runtimeId) })
                assertTrue(actualLimits(simulation.runtime).all { it == 0.0 })
            }
        } finally {
            logger.removeHandler(handler)
            closeActualWorld(source)
        }
    }

    @Test
    fun `a failed simulation installation cleans its own rules and leaves another real installation usable`() {
        initializeDomainPlugins()
        val source = actualSignalWorld()
        val id = UUID.fromString(source.runtimeId)
        val manager = runtimeManager(plugin(), actualInfrastructure(mapOf(id to source)), CapturingRoutePlanningService(), store(),
            CapturingSignalEpochSourceService(), loadDefaultSignalCatalog = false)
        manager.loadSignalPacks(Path.of(requireNotNull(javaClass.getResource("/examples/jr-four-light/signal-packs")).toURI()))
        store().save(id, actualDefinitions(source))
        manager.install(id)
        val feature = manager.prepareSimulation(id)
        val failing = object : work.art1st.javarailways.simpleato.api.simulation.PreparedSimulationFeature {
            override val id = feature.id
            override fun install(target: work.art1st.javarailways.simpleato.api.simulation.SimulationDomainTarget): AutoCloseable =
                feature.install(object : work.art1st.javarailways.simpleato.api.simulation.SimulationDomainTarget by target {
                    override fun installSignalSources(sources: Collection<SignalEpochSource>): AutoCloseable =
                        error("deliberate target installation failure")
                })
        }
        actualSimulation(source, feature).use { survivor ->
            assertEquals("deliberate target installation failure", assertThrows(IllegalStateException::class.java) {
                actualSimulation(source, failing)
            }.message)
            survivor.step()
            assertEquals(listOf("proceed", "slow", "caution", "stop"), actualAspects(survivor.runtime))
            actualSimulation(source, feature).use { replacement ->
                replacement.step()
                assertEquals(actualLimits(survivor.runtime), actualLimits(replacement.runtime))
            }
            survivor.step()
        }
        manager.clearLocal().toCompletableFuture().join()
        closeActualWorld(source)
    }

    @Test
    fun `installed formal protection signal receives real admission and inspection feedback without dispatcher ticks`() {
        initializeDomainPlugins()
        val source = actualSignalWorld()
        val id = UUID.fromString(source.runtimeId)
        val rail = source.allRails.single()
        source.basicWorldData.wayPoints.add(work.art1st.javarailways.basic.WayPoint("origin", rail.id, 480.0))
        source.basicWorldData.wayPoints.add(work.art1st.javarailways.basic.WayPoint("target", rail.id, 1100.0))
        val region = ProtectionRegionDefinition("test:actual-region", 1, listOf(
            ProtectionBoundaryDefinition("test:actual-entry", RailPositionSnapshot(rail.id, ViewDirection.AB, 500.0)),
            ProtectionBoundaryDefinition("test:actual-exit", RailPositionSnapshot(rail.id, ViewDirection.BA, 800.0))))
        val route = ProtectionRouteDefinition("test:actual-protection-route", "test:actual-entry", "test:actual-exit",
            listOf(ProtectionRouteTraversal(rail.id, ViewDirection.AB, 500.0, 800.0)))
        val definition = SimpleSignalsWorldDefinitions(id, 1, listOf(region), listOf(
            ProtectionRouteTableDefinition("test:actual-table", 1, region.regionId, 1, listOf(route))),
            protectionSignals = listOf(ProtectionSignalDefinition("test:actual-protection-signal", 1,
                ProtectionSignalKind.CURRENT_ENTRY, "test:actual-entry", 0.0, "jr_four_light:block")))
        val manager = runtimeManager(plugin(), actualInfrastructure(mapOf(id to source)), CapturingRoutePlanningService(), store(),
            CapturingSignalEpochSourceService(), loadDefaultSignalCatalog = false)
        manager.loadSignalPacks(Path.of(requireNotNull(javaClass.getResource("/examples/jr-four-light/signal-packs")).toURI()))
        store().save(id, definition)
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(id).status)
        val feature = manager.prepareSimulation(id)
        val actuator = work.art1st.javarailways.simplesignals.testing.StationaryTrainActuator(30.0)
        actualSimulation(source, feature, actuator) { world ->
            seedActualTrain(world)
            world.onOwnerForTest {
                val train = world.allTrains.single()
                train.setLocation(work.art1st.javarailways.core.physics.rail.RailLocationWithFacing(world.allRails.single().viewAB, 480.0))
                train.removeController()
                train.commit()
            }
        }.use { simulation ->
            val world = simulation.world
            simulation.step()
            assertEquals(0.0, simulation.runtime.sectionSignalSnapshot()!!.states
                .getValue("test:actual-protection-signal").effectiveSpeedLimitMetersPerSecond)
            val data = simulation.domain!!.data
            world.onOwnerForTest {
                data.timetables.add(work.art1st.javarailways.simpleato.timetable.Timetable("actual-protection", services = listOf(
                    work.art1st.javarailways.simpleato.timetable.TimetableService("actual-protection-service",
                        work.art1st.javarailways.simpleato.timetable.TrainSelector.TrainId("actual"), listOf(
                            work.art1st.javarailways.simpleato.timetable.TimetableStep.Check("origin"),
                            work.art1st.javarailways.simpleato.timetable.TimetableStep.At(0,
                                work.art1st.javarailways.simpleato.timetable.TimetableAction.GoTo("target")))))))
                assertTrue(data.dispatcher.startService(world, "actual-protection", "actual-protection-service") is
                    work.art1st.javarailways.simpleato.timetable.TimetableServiceStartResult.Started)
            }
            repeat(10) { simulation.step() }
            assertFalse(simulation.domain.atoCoordinator.isStalled, simulation.domain.atoCoordinator.lastStall.toString())
            val selected = simulation.runtime.protectionRouteSelectionSnapshot()!!.selections.getValue("test:actual-entry")
            assertEquals("test:actual-protection-route", selected.routeId)
            assertTrue(selected.routeReady, selected.toString())
            val ato = world.allTrains.single().controller as work.art1st.javarailways.simpleato.controller.SimpleATO
            assertTrue(ato.runtimeDiagnostics.permittedSpeedMetersPerSecond > 2.0)
            assertTrue(simulation.runtime.sectionSignalSnapshot()!!.states
                .getValue("test:actual-protection-signal").effectiveSpeedLimitMetersPerSecond.let { it == null || it > 0.0 })
            val occupant = work.art1st.javarailways.core.physics.train.createTrainWithConfig("protection-occupant", world.allRails.single(), world, listOf(10.0))
            world.onOwnerForTest {
                occupant.setLocation(work.art1st.javarailways.core.physics.rail.RailLocationWithFacing(world.allRails.single().viewAB, 650.0))
                occupant.commit()
            }
            repeat(3) { simulation.step(domainPhase = false) }
            assertEquals(0.0, simulation.runtime.sectionSignalSnapshot()!!.states
                .getValue("test:actual-protection-signal").effectiveSpeedLimitMetersPerSecond)
            assertTrue(ato.runtimeDiagnostics.permittedSpeedMetersPerSecond <= kotlin.math.sqrt(40.0) + 1e-9,
                "the real controller consumes the red protection signal through installed inspection")
            world.onOwnerForTest { occupant.destroy() }.toCompletableFuture().join()
            repeat(4) { simulation.step(domainPhase = false) }
            assertTrue(simulation.runtime.protectionRouteSelectionSnapshot()!!.selections.getValue("test:actual-entry").routeReady)
            assertTrue(ato.runtimeDiagnostics.permittedSpeedMetersPerSecond > kotlin.math.sqrt(40.0))
        }
        manager.clearLocal().toCompletableFuture().join()
        closeActualWorld(source)
    }

    @Test
    fun `real definition preparation and waiting model attachment do not capture the manager directory`() {
        val fixture = Fixture()
        val manager = runtimeManager(plugin(), MutableTopologyService(fixture.currentTopology).service,
            CapturingRoutePlanningService(), store(), CapturingSignalEpochSourceService())
        manager.install(fixture.worldId)
        val runtime = manager.authoringRuntime(fixture.worldId)!!
        val candidate = SimpleSignalsWorldDefinitions(fixture.worldId, 1, listOf(fixture.region), emptyList())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        // Freeze an actual candidate read while prepareDefinitions owns its real publication monitor.
        // The factory copies lists, so this test-local read-only wrapper is installed by reflection.
        val regions = SimpleSignalsWorldDefinitions::class.java.getDeclaredField("regions").also { it.isAccessible = true }
        val original = candidate.regions
        val once = java.util.concurrent.atomic.AtomicBoolean()
        regions.set(candidate, object : AbstractList<ProtectionRegionDefinition>() {
            override val size = original.size
            override fun get(index: Int): ProtectionRegionDefinition {
                if (once.compareAndSet(false, true)) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                return original[index]
            }
        })
        val preparation = CompletableFuture.supplyAsync({ runtime.prepareDefinitions(fixture.currentTopology, candidate) }, concurrentExecutor)
        val models = SimpleSignalsWorldRuntimeManager::class.java.getDeclaredField("modelPackages").also { it.isAccessible = true }
        models.set(manager, null)
        val attach = SimpleSignalsWorldRuntimeManager::class.java.getDeclaredMethod("attachModels", SimpleSignalsWorldRuntime::class.java)
            .also { it.isAccessible = true }
        var attachment: CompletableFuture<Void>? = null
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            attachment = CompletableFuture.runAsync({ attach.invoke(manager, runtime) }, concurrentExecutor)
            val monitor = SimpleSignalsWorldRuntime::class.java.getDeclaredField("lock").also { it.isAccessible = true }.get(runtime)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            var waiting = false
            while (!waiting && System.nanoTime() < deadline) {
                waiting = java.lang.management.ManagementFactory.getThreadMXBean().dumpAllThreads(false, false).any {
                    it.threadState == Thread.State.BLOCKED && it.lockInfo?.identityHashCode == System.identityHashCode(monitor)
                }
                if (!waiting) Thread.yield()
            }
            assertTrue(waiting, "real attach must wait on the real candidate's publication lock")
            assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED,
                CompletableFuture.supplyAsync({ manager.install(UUID.randomUUID()) }, concurrentExecutor).get(2, TimeUnit.SECONDS).status)
            assertFalse(preparation.isDone)
            assertFalse(attachment.isDone)
        } finally { release.countDown() }
        assertTrue(preparation.get(5, TimeUnit.SECONDS) is SimpleSignalsDefinitionPreparationResult.Prepared)
        attachment!!.get(5, TimeUnit.SECONDS)
        manager.clearLocal().toCompletableFuture().join()
    }

    @Test
    fun `subscription retirement failure still closes the owned engine and preserves another simulation`() {
        initializeDomainPlugins()
        val source = actualSignalWorld()
        val id = UUID.fromString(source.runtimeId)
        val cleaned = CountDownLatch(1)
        val failure = IllegalStateException("topology retirement failed")
        val manager = runtimeManager(plugin(), actualInfrastructure(mapOf(id to source)), CapturingRoutePlanningService(), store(),
            CapturingSignalEpochSourceService(), loadDefaultSignalCatalog = false,
            modelFailure = { context, error -> if (context.contains("cleanup") &&
                generateSequence(error) { it.cause }.any { it === failure }) cleaned.countDown() })
        manager.loadSignalPacks(Path.of(requireNotNull(javaClass.getResource("/examples/jr-four-light/signal-packs")).toURI()))
        store().save(id, actualDefinitions(source))
        manager.install(id)
        val runtime = manager.authoringRuntime(id)!!
        val logic = actualLogic(runtime)
        val feature = manager.prepareSimulation(id)
        actualSimulation(source, feature).use { survivor ->
            val subscription = runtime.topologySubscription
            runtime.topologySubscription = AutoCloseable { subscription?.close(); throw failure }
            assertSame(failure, assertThrows(IllegalStateException::class.java) { manager.remove(id) })
            assertTrue(cleaned.await(5, TimeUnit.SECONDS), "cleanup reports only after the owned logic has closed")
            assertThrows(IllegalStateException::class.java) { logic.evaluate("jr_four_light:block", kotlinx.serialization.json.JsonObject(emptyMap())) }
            assertNull(manager.authoringRuntime(id))
            assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(id).status)
            survivor.step()
            assertEquals(listOf("proceed", "slow", "caution", "stop"), actualAspects(survivor.runtime))
            val replacement = manager.authoringRuntime(id)!!
            val replacementLogic = actualLogic(replacement)
            replacement.topologySubscription = AutoCloseable { throw failure }
            val closed = manager.clearLocal().toCompletableFuture()
            val closeFailure = assertThrows(java.util.concurrent.ExecutionException::class.java) { closed.get(5, TimeUnit.SECONDS) }
            assertSame(failure, closeFailure.cause)
            assertThrows(IllegalStateException::class.java) { replacementLogic.evaluate("jr_four_light:block", kotlinx.serialization.json.JsonObject(emptyMap())) }
            survivor.step()
        }
        closeActualWorld(source)
    }

    @Test
    fun `late old removal cannot clear replacement world rule diagnostics`() {
        val fixture = Fixture()
        val sources = CapturingSignalEpochSourceService()
        val issues = work.art1st.javarailways.bukkit.api.diagnostics.WorldIssueService()
        val manager = runtimeManager(plugin(), MutableTopologyService(fixture.currentTopology).service,
            CapturingRoutePlanningService(), store(), sources, worldIssues = issues)
        manager.install(fixture.worldId)
        val old = manager.authoringRuntime(fixture.worldId)!!
        val held = CountDownLatch(1)
        val replace = CountDownLatch(1)
        val installed = CountDownLatch(1)
        val release = CountDownLatch(1)
        val replacement = CompletableFuture.runAsync({
            synchronized(old.managementLock) {
                held.countDown()
                check(replace.await(5, TimeUnit.SECONDS))
                val block = FixedBlockDefinition("test:diagnostic-block", 1,
                    listOf(FixedBlockTraversal(fixture.entry, ViewDirection.AB, 1.0, 3.0)))
                store().save(fixture.worldId, SimpleSignalsWorldDefinitions(fixture.worldId, 1, emptyList(), emptyList(), listOf(block),
                    listOf(FixedBlockSignalDefinition("test:diagnostic-signal", 1, block.blockId, 0.0, "missing:rule"))))
                assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(fixture.worldId).status)
                val epoch = SignalEpochRuntime(fixture.worldId.toString(), TrainRestrictionSnapshotStore(fixture.worldId.toString()))
                epoch.replaceSources(sources.sources.getValue(fixture.worldId))
                epoch.process(RailwayWorldFrameSnapshot.create(1, fixture.currentTopology.revision, emptyList(),
                    ActiveRailContinuationsSnapshot.empty(fixture.currentTopology.revision)))
                installed.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        }, concurrentExecutor)
        var removal: CompletableFuture<Void>? = null
        try {
            assertTrue(held.await(5, TimeUnit.SECONDS))
            removal = CompletableFuture.runAsync({ manager.remove(fixture.worldId) }, concurrentExecutor)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (!old.closed && System.nanoTime() < deadline) Thread.yield()
            assertTrue(old.closed)
            replace.countDown()
            assertTrue(installed.await(5, TimeUnit.SECONDS))
            assertTrue(issues.snapshot(fixture.worldId).any { it.objectId == "test:diagnostic-signal" && it.code == "rule-unavailable" })
        } finally { replace.countDown(); release.countDown() }
        replacement.get(5, TimeUnit.SECONDS)
        removal!!.get(5, TimeUnit.SECONDS)
        assertNotSame(old, manager.authoringRuntime(fixture.worldId))
        assertTrue(issues.snapshot(fixture.worldId).any { it.objectId == "test:diagnostic-signal" && it.code == "rule-unavailable" })
        manager.clearLocal().toCompletableFuture().join()
    }

    @Test
    fun `real simulation reversal retains the same physical block claim and releases after the reverse tail`() {
        initializeDomainPlugins()
        val source = actualSignalWorld()
        val id = UUID.fromString(source.runtimeId)
        val rail = source.allRails.single()
        source.basicWorldData.operatingDirections.put(rail.id, null)
        val block = FixedBlockDefinition("test:actual-reversible", 1,
            listOf(FixedBlockTraversal(rail.id, ViewDirection.AB, 0.0, 1450.0)))
        val definitions = SimpleSignalsWorldDefinitions(id, 1, emptyList(), emptyList(), listOf(block), listOf(
            FixedBlockSignalDefinition("test:forward", 1, block.blockId, 0.0, "jr_four_light:block"),
            FixedBlockSignalDefinition("test:reverse", 1, block.blockId, 0.0, "jr_four_light:block", FixedBlockEntryDirection.REVERSE)))
        val manager = runtimeManager(plugin(), actualInfrastructure(mapOf(id to source), null), CapturingRoutePlanningService(), store(),
            CapturingSignalEpochSourceService(), loadDefaultSignalCatalog = false)
        manager.loadSignalPacks(Path.of(requireNotNull(javaClass.getResource("/examples/jr-four-light/signal-packs")).toURI()))
        store().save(id, definitions)
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(id).status)
        actualSimulation(source, manager.prepareSimulation(id), seed = { world ->
            seedActualTrain(world)
            world.onOwnerForTest { world.allTrains.single().removeController() }
        }).use { simulation ->
            val world = simulation.world
            val train = world.allTrains.single()
            simulation.step()
            val host = simulation.simulation!!.routeAdmissionHost
            val registry = host.javaClass.getDeclaredField("registry").also { it.isAccessible = true }.get(host)
                as InfrastructureCoordinationRegistry
            val owner = InfrastructureOwner("test:reverse-owner", 1)
            val grant = world.onOwnerForTest {
                val context = checkNotNull(host.contextProvider.current())
                val tail = context.observation.trains.single().tail.arcLengthInAbMeters
                val route = RouteSnapshot("test:reverse-route", 1, world.topologyRevision,
                    listOf(RouteSegmentSnapshot(rail.id, ViewDirection.AB, tail, 1400.0)))
                val result = context.service.request(work.art1st.javarailways.basic.api.route.RouteResourceIntent(
                    "test:reverse-request", owner, train.id, context.observation.stamp, route, RouteBoundary(100.0)))
                assertTrue(result is work.art1st.javarailways.basic.api.route.RouteAdmissionResult.Granted, result.toString())
                (result as work.art1st.javarailways.basic.api.route.RouteAdmissionResult.Granted).grant
            }
            val retained = registry.claimSnapshots().single().footprint
            val token = StableInfrastructureResource(FIXED_BLOCK_RESOURCE_TYPE, block.blockId)
            assertTrue(token in retained.resources)
            world.onOwnerForTest {
                val context = checkNotNull(host.contextProvider.current())
                assertTrue(context.service.suspendReleaseForReversal(grant.grantId) is
                    work.art1st.javarailways.basic.api.route.RouteGrantReversalResult.Accepted)
                train.reverse()
            }
            simulation.step()
            assertTrue(train.isReversed)
            assertEquals(retained, registry.claimSnapshots().single().footprint)
            world.onOwnerForTest {
                val context = checkNotNull(host.contextProvider.current())
                val observed = context.observation.trains.single()
                val tail = 1450.0 - observed.tail.arcLengthInAbMeters
                val bodyLength = observed.tail.arcLengthInAbMeters - observed.head.arcLengthInAbMeters
                val route = RouteSnapshot("test:reverse-route", 2, world.topologyRevision,
                    listOf(RouteSegmentSnapshot(rail.id, ViewDirection.BA, tail, 750.0)))
                val rebased = context.service.rebaseAfterReversal(work.art1st.javarailways.basic.api.route.RouteResourceIntent(
                    "test:reverse-body", owner, train.id, context.observation.stamp, route, RouteBoundary(bodyLength)))
                assertTrue(rebased is work.art1st.javarailways.basic.api.route.RouteGrantReversalResult.Accepted, rebased.toString())
                assertSame(grant.grantId, (rebased as work.art1st.javarailways.basic.api.route.RouteGrantReversalResult.Accepted).grant.grantId)
                assertEquals(retained, registry.claimSnapshots().single().footprint)
                val extended = context.service.request(work.art1st.javarailways.basic.api.route.RouteResourceIntent(
                    "test:reverse-extension", owner, train.id, context.observation.stamp, route, RouteBoundary(60.0)))
                assertTrue(extended is work.art1st.javarailways.basic.api.route.RouteAdmissionResult.Granted, extended.toString())
                assertSame(grant.grantId, (extended as work.art1st.javarailways.basic.api.route.RouteAdmissionResult.Granted).grant.grantId)
                assertEquals(1, registry.claimSnapshots().count { token in it.footprint.resources })
                val controller = object : work.art1st.javarailways.core.api.control.TrainController(train) {
                    fun start() { speed = 2.0; commit() }
                }
                train.setController(controller)
                controller.start()
            }
            val before = train.publishedFrame.head.arcLengthInAbMeters
            repeat(175) { simulation.step(200.0) }
            assertTrue(train.publishedFrame.head.arcLengthInAbMeters < before - 60.0)
            assertTrue(registry.claimSnapshots().none { token in it.footprint.resources })
            world.onOwnerForTest { assertTrue(checkNotNull(host.contextProvider.current()).service.endGrant(grant.grantId)) }
            assertTrue(simulation.runtime.sectionSignalSnapshot()!!.states.values.all { it is SectionSignalState.Available })
        }
        manager.clearLocal().toCompletableFuture().join()
        closeActualWorld(source)
    }

    private class ActualSignalRun(
        val world: work.art1st.javarailways.core.physics.CoreWorld,
        val runtime: SimpleSignalsWorldRuntime,
        val domain: work.art1st.javarailways.simpleato.runtime.OperationalWorldRuntime? = null,
        val simulation: work.art1st.javarailways.simpleato.timetable.simulation.IsolatedTimetableSimulationWorld? = null,
        private val publishObservation: (() -> Unit)? = null,
        private val closeAction: () -> Unit
    ) : AutoCloseable {
        private val closed = java.util.concurrent.atomic.AtomicBoolean()
        fun step(deltaMillis: Double = 50.0, domainPhase: Boolean = true) = IsolatedSimulationThreadMarker.run {
            world.executionOwner.run {
                val ticket = world.physicsCoordinator.stepSimulation(deltaMillis)
                ticket.await()
                if (domain != null && domainPhase) world.physicsCoordinator.processBetweenFrames {
                    domain.afterPublication(ticket.epoch, publishObservation ?: { simulation!!.routeAdmissionHost.publish(
                        RailwayObservation.fromCore(world.runtimeId, world.latestWorldFrame())) })
                }
            }
        }
        override fun close() { if (closed.compareAndSet(false, true)) closeAction() }
    }

    private fun actualLive(world: work.art1st.javarailways.core.physics.CoreWorld,
        manager: SimpleSignalsWorldRuntimeManager, sources: CapturingSignalEpochSourceService,
        domain: OperationalWorldRuntime? = null, publishObservation: (() -> Unit)? = null,
        seed: (work.art1st.javarailways.core.physics.CoreWorld) -> Unit = ::seedActualTrain): ActualSignalRun {
        val id = UUID.fromString(world.runtimeId)
        val epoch = domain?.signalEpochRuntime ?: SignalEpochRuntime(world.runtimeId, TrainRestrictionSnapshotStore(world.runtimeId))
        epoch.replaceSources(sources.sources.getValue(id))
        if (domain == null) world.physicsCoordinator.registerRailwayEpochProcessor(epoch)
        seed(world)
        world.physicsCoordinator.start()
        return ActualSignalRun(world, manager.authoringRuntime(id)!!, domain, publishObservation = publishObservation) {
            closeActualWorld(world); manager.remove(id)
        }
    }

    private fun actualSimulation(source: work.art1st.javarailways.core.physics.CoreWorld,
        feature: work.art1st.javarailways.simpleato.api.simulation.PreparedSimulationFeature,
        actuator: work.art1st.javarailways.basic.api.ato.TrainActuator? = null,
        seed: (work.art1st.javarailways.core.physics.CoreWorld) -> Unit = ::seedActualTrain): ActualSignalRun {
        var installed: AutoCloseable? = null
        val captured = object : work.art1st.javarailways.simpleato.api.simulation.PreparedSimulationFeature {
            override val id = feature.id
            override fun install(target: work.art1st.javarailways.simpleato.api.simulation.SimulationDomainTarget): AutoCloseable =
                feature.install(target).also { installed = it }
        }
        val simulation = work.art1st.javarailways.simpleato.timetable.simulation.IsolatedTimetableSimulationWorld.create(
            source.saveInfrastructure(1),
            work.art1st.javarailways.basic.persistence.BasicInfrastructureSnapshotV2.capture(source, 1, emptySet()),
            work.art1st.javarailways.simpleato.persistence.SimpleATOInfrastructureSnapshotV1(1, 1, emptyList(), emptyList(), 0),
            workerThreads = 2,
            basicDataFactory = { _, changed -> work.art1st.javarailways.basic.BasicWorldData(
                operatingDirections = work.art1st.javarailways.basic.rail.OperatingDirectionStore(changed)) },
            preparedFeatures = listOf(captured),
            dispatcherFactory = { _, admission, restrictions, inspection, dependencies ->
                val profile = work.art1st.javarailways.simpleato.api.ato.AtoSafetyProfile(
                    accelerationMetersPerSecondSquared = 1.0, serviceDecelerationMetersPerSecondSquared = 1.0,
                    reactionTimeSeconds = 0.0, safetyDistanceMeters = 2.0)
                work.art1st.javarailways.simpleato.timetable.TimetableDispatcher(admission, restrictions, inspection, dependencies,
                    authorityStoppingMarginMeters = profile.safetyDistanceMeters,
                    atoFactory = actuator?.let { selectedActuator -> work.art1st.javarailways.simpleato.timetable.TimetableAtoFactory { train, world ->
                        work.art1st.javarailways.simpleato.controller.SimpleATO(train, world, selectedActuator, profile,
                            inspection::maximumDeclaredLeadTimeSeconds)
                    } }) },
            seed = seed)
        // Observe the actual feature-owned runtime without adding a production test hook or guest host access.
        val token = checkNotNull(installed)
        val runtime = token.javaClass.declaredFields.single { it.type == SimpleSignalsWorldRuntime::class.java }
            .also { it.isAccessible = true }.get(token) as SimpleSignalsWorldRuntime
        return ActualSignalRun(simulation.world, runtime, simulation.operationalRuntime, simulation) { simulation.close() }
    }

    private fun actualLogic(runtime: SimpleSignalsWorldRuntime): work.art1st.javarailways.simplesignals.signalpack.SignalLogicRuntime =
        SimpleSignalsWorldRuntime::class.java.getDeclaredField("signalLogic").also { it.isAccessible = true }
            .get(runtime) as work.art1st.javarailways.simplesignals.signalpack.SignalLogicRuntime

    private fun actualAspects(runtime: SimpleSignalsWorldRuntime) = (0..3).map {
        (runtime.sectionSignalSnapshot()!!.states.getValue("test:actual-signal-$it") as SectionSignalState.Available).output.modelVariant
    }
    private fun actualLimits(runtime: SimpleSignalsWorldRuntime) = (0..3).map {
        runtime.sectionSignalSnapshot()!!.states.getValue("test:actual-signal-$it").effectiveSpeedLimitMetersPerSecond
    }

    private fun actualDefinitions(world: work.art1st.javarailways.core.physics.CoreWorld): SimpleSignalsWorldDefinitions {
        val rail = world.allRails.single()
        val boundaries = listOf(0.0, 500.0, 800.0, 950.0, 1450.0)
        val blocks = (0..3).map { index -> FixedBlockDefinition("test:actual-block-$index", 1,
            listOf(FixedBlockTraversal(rail.id, ViewDirection.AB, boundaries[index], boundaries[index + 1]))) }
        return SimpleSignalsWorldDefinitions(UUID.fromString(world.runtimeId), 1, emptyList(), emptyList(), blocks,
            blocks.mapIndexed { index, block -> FixedBlockSignalDefinition("test:actual-signal-$index", 1,
                block.blockId, 0.0, "jr_four_light:block") })
    }

    private fun actualSignalWorld(): work.art1st.javarailways.core.physics.CoreWorld {
        val world = work.art1st.javarailways.core.physics.train.createTestWorld(
            work.art1st.javarailways.core.physics.tick.PhysicsCoordinator(2, automaticFallback = false))
        val rail = work.art1st.javarailways.core.physics.train.createStraightRailFromSegments(world,
            work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator.buildStraight(1450.0, 1, 10.0))
        val basic = work.art1st.javarailways.basic.BasicWorldData()
        world.registerExtensionData(work.art1st.javarailways.basic.BasicPlugin.INSTANCE, basic)
        basic.operatingDirections.put(rail.id, ViewDirection.AB)
        rail.infrastructureMetadata = work.art1st.javarailways.basic.rail.RailInfrastructureMetadata(
            speedLimits = work.art1st.javarailways.basic.rail.SpeedLimitMetadata(listOf(
                work.art1st.javarailways.basic.rail.SpeedLimitEntry(100.0 / 3.6,
                    work.art1st.javarailways.basic.rail.SpeedLimitType.SUBGRADE))))
        return world
    }

    private fun seedActualTrain(world: work.art1st.javarailways.core.physics.CoreWorld) {
        val rail = world.allRails.single()
        val train = work.art1st.javarailways.core.physics.train.createTrainWithConfig("actual", rail, world, listOf(10.0))
        world.onOwnerForTest {
            train.setLocation(work.art1st.javarailways.core.physics.rail.RailLocationWithFacing(rail.viewAB, 1000.0))
            train.commit()
            val controller = object : work.art1st.javarailways.core.api.control.TrainController(train) {
                fun start() { speed = 2.0; commit() }
            }
            train.setController(controller)
            controller.start()
        }
    }

    private fun actualInfrastructure(worlds: Map<UUID, work.art1st.javarailways.core.physics.CoreWorld>,
        direction: ViewDirection? = ViewDirection.AB): InfrastructureResourceService =
        proxy(InfrastructureResourceService::class.java) { method, args ->
            val world = worlds.getValue(args!![if (method.name == "observeTopologyChanges") 1 else 0] as UUID)
            when (method.name) {
                "currentTopology" -> world.topologyState
                "observeTopologyChanges" -> AutoCloseable {}
                "operatingDirections" -> {
                    @Suppress("UNCHECKED_CAST") val rails = args[2] as Collection<UUID>
                    work.art1st.javarailways.basic.api.route.RailOperatingDirections(world.topologyState,
                        rails.associateWith { direction })
                }
                "railSpeedLimits" -> {
                    @Suppress("UNCHECKED_CAST") val rails = args[2] as Collection<UUID>
                    rails.associateWith { 100.0 / 3.6 }
                }
                else -> throw UnsupportedOperationException(method.name)
            }
        }

    private fun initializeDomainPlugins() {
        if (work.art1st.javarailways.basic.BasicPlugin.instanceOrNull() == null)
            work.art1st.javarailways.basic.BasicPlugin.INSTANCE = work.art1st.javarailways.basic.BasicPlugin(100)
        if (work.art1st.javarailways.simpleato.SimpleATOPlugin.instanceOrNull() == null)
            work.art1st.javarailways.simpleato.SimpleATOPlugin.INSTANCE = work.art1st.javarailways.simpleato.SimpleATOPlugin(101)
    }

    private fun closeActualWorld(world: work.art1st.javarailways.core.physics.CoreWorld) = IsolatedSimulationThreadMarker.run {
        world.executionOwner.run {
            world.physicsCoordinator.closeAndAwaitTermination()
            (world.tickManager as work.art1st.javarailways.core.util.TickManagerImpl).closeAndAwaitTermination(world.runtimeId)
        }
    }

    private fun store() = SimpleSignalsWorldDefinitionStore(directory.toFile())

    private fun writeAutomaticSignalPack(
        outputVariant: String = "clear",
        script: String? = null,
        modelVariants: List<String> = listOf("clear")
    ): Path {
        val root = directory.resolve("signal-packs")
        val pack = root.resolve("automatic")
        Files.createDirectories(pack.resolve("model"))
        Files.createDirectories(pack.resolve("logic"))
        Files.createDirectories(pack.resolve("signal"))
        Files.writeString(pack.resolve("pack.yml"), "config-version: 1\nname: automatic")
        val headVariants = modelVariants.joinToString("\n") { "  $it: {}" }
        val headModel = """
            parts:
              - type: dummy
            default: {}
            variants:
        """.trimIndent() + "\n" + headVariants
        Files.writeString(pack.resolve("model/head.yml"), headModel)
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
    fun `catalog rules keep evaluating after unrelated topology edit and reject affected geometry`() {
        val f = Fixture()
        val unrelated = UUID.randomUUID()
        val initial = f.topology(41L, listOf(unrelated))
        val block = FixedBlockDefinition("test:local-block", 1,
            listOf(FixedBlockTraversal(f.entry, ViewDirection.AB, 0.0, 4.0)))
        val signal = FixedBlockSignalDefinition("test:local-signal", 1, block.blockId, 0.0, "automatic:block")
        val definitions = store()
        definitions.save(f.worldId, SimpleSignalsWorldDefinitions(f.worldId, 1, emptyList(), emptyList(),
            listOf(block), listOf(signal)))
        val infrastructure = MutableTopologyService(initial)
        val sources = CapturingSignalEpochSourceService()
        val queue = java.util.ArrayDeque<() -> Unit>()
        val manager = runtimeManager(plugin(), infrastructure.service, CapturingRoutePlanningService(), definitions,
            sources, topologyWorker = { queue += it }, loadDefaultSignalCatalog = false)
        try {
            manager.loadSignalPacks(writeAutomaticSignalPack(script = """
                function evaluate(input, output) {
                  output.setSpeedLimit(input.self.occupied ? 0 : 25);
                  output.setModelVariant('clear');
                }
            """.trimIndent()))
            assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(f.worldId).status)
            val runtime = manager.authoringRuntime(f.worldId)!!
            val publication = runtime.authoringPublication()
            val epoch = SignalEpochRuntime(f.worldId.toString(), TrainRestrictionSnapshotStore(f.worldId.toString()))
            epoch.replaceSources(sources.sources.getValue(f.worldId))
            fun process(number: Long, occupied: Boolean) {
                val position = RailPositionSnapshot(f.entry, ViewDirection.AB, 2.0)
                val trains = if (!occupied) emptyList() else listOf(TrainFrameSnapshot.create(
                    "local-train", "Local train", number, number, 0.0, false, false, emptyList(), position, position,
                    listOf(work.art1st.javarailways.core.api.physics.rail.RailInterval(f.entry, 1.0, 2.0)),
                    emptyList(), emptyList(), emptyList(), emptyList()))
                val revision = infrastructure.topology!!.revision
                epoch.process(RailwayWorldFrameSnapshot.create(number, revision, trains,
                    ActiveRailContinuationsSnapshot.empty(revision)))
            }
            fun change(next: TopologyState, affected: UUID) {
                val change = work.art1st.javarailways.core.api.physics.rail.topology.RailTopologyChange(
                    infrastructure.topology!!, next, setOf(affected))
                epoch.invalidateTopology(change)
                infrastructure.commitTopology(next, setOf(affected))
            }
            process(1, false)
            assertEquals(25.0, runtime.sectionSignalSnapshot()!!.states.getValue(signal.signalId).effectiveSpeedLimitMetersPerSecond)
            change(f.topology(42L, listOf(unrelated), initial.railIdentities), unrelated)
            assertSame(publication, runtime.authoringPublication(), "unrelated changes must retain the prepared plan")
            assertTrue(queue.isEmpty())
            process(2, true)
            assertEquals(0.0, runtime.sectionSignalSnapshot()!!.states.getValue(signal.signalId).effectiveSpeedLimitMetersPerSecond)
            assertEquals(2L, runtime.signalDeviceDiagnostics(signal.signalId)!!.stamp!!.physicsEpoch)
            process(3, false)
            assertEquals(25.0, runtime.sectionSignalSnapshot()!!.states.getValue(signal.signalId).effectiveSpeedLimitMetersPerSecond)
            change(f.topology(43L, listOf(unrelated), initial.railIdentities - f.entry), f.entry)
            assertTrue(queue.isNotEmpty()) // Keep revalidation pending while evaluating the old source.
            process(4, false)
            val unavailable = runtime.sectionSignalSnapshot()!!.states.getValue(signal.signalId)
            assertInstanceOf(SectionSignalState.Unavailable::class.java, unavailable)
            assertEquals(0.0, unavailable.effectiveSpeedLimitMetersPerSecond)
        } finally {
            manager.clearLocal().toCompletableFuture().join()
        }
    }

    @Test
    fun `topology callback never waits for source publication and older worker cannot override related repair`() {
        val fixture = Fixture()
        val infrastructure = MutableTopologyService(fixture.currentTopology)
        val definitions = store()
        val block = FixedBlockDefinition("test:local", 1,
            listOf(FixedBlockTraversal(fixture.entry, ViewDirection.AB, 1.0, 3.0)))
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
        val worker = CompletableFuture.runAsync({ work() }, concurrentExecutor)
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            CompletableFuture.runAsync({ infrastructure.commitTopology(fixture.topology(43), setOf(fixture.entry)) }, concurrentExecutor)
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
            listOf(FixedBlockTraversal(fixture.entry, ViewDirection.AB, 1.0, 3.0)))
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
        var staticSpeedLimits: Map<UUID, Double?> = emptyMap()
        var speedLimitReadCount = 0
            private set
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
                "railSpeedLimits" -> {
                    speedLimitReadCount++
                    check(arguments!![1] === topology)
                    @Suppress("UNCHECKED_CAST")
                    val rails = arguments[2] as Collection<UUID>
                    require(rails.all(topology!!.railProjection::containsKey))
                    rails.associateWith { staticSpeedLimits[it] }
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
                            override fun <T> execute(action: java.util.function.Supplier<T>): T {
                                check(!closed)
                                return if (registryPermit == null) action.get() else
                                    registry.withEditPermit(registryOwner, registryPermit.id) { action.get() }
                            }
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
        private val bindings = java.util.concurrent.ConcurrentHashMap<UUID, List<RoutePlanningRestrictionBinding>>()
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
        val sources = java.util.concurrent.ConcurrentHashMap<UUID, List<SignalEpochSource>>()
        val replacements = java.util.concurrent.CopyOnWriteArrayList<List<SignalEpochSource>>()
        data class Retirement(
            val epochSources: List<SignalEpochSource>,
            val inspectionSources: List<InspectionSource>,
            val routePlanningSources: List<RoutePlanningRestrictionBinding>
        )
        val retirements = java.util.concurrent.CopyOnWriteArrayList<Retirement>()
        var onRetire: (Retirement) -> Unit = {}

        override fun retireSources(
            pluginOwner: Plugin,
            worldId: UUID,
            epochSources: Collection<SignalEpochSource>,
            inspectionSources: Collection<InspectionSource>,
            routePlanningSources: Collection<RoutePlanningRestrictionBinding>
        ) {
            val retirement = Retirement(epochSources.toList(), inspectionSources.toList(), routePlanningSources.toList())
            retirements += retirement
            onRetire(retirement)
            sources.computeIfPresent(worldId) { _, current ->
                current.filterNot { source -> epochSources.any { it === source } }
            }
        }
        @Volatile var beforeCommitAction: () -> Unit = {}
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

        fun topology(revision: Long, extraRails: List<UUID> = emptyList(),
            preservedRailIdentities: Map<UUID, UUID> = emptyMap()): TopologyState = topologyState(
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
            ), preservedRailIdentities = preservedRailIdentities
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
            loadDefaultSignalCatalog: Boolean = true,
            worldIssues: work.art1st.javarailways.bukkit.api.diagnostics.WorldIssueService = work.art1st.javarailways.bukkit.api.diagnostics.WorldIssueService()
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
            topologyWorker = topologyWorker,
            worldIssues = worldIssues
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
                    "replaceSources", "retireSources" -> Unit
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
