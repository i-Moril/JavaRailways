package work.art1st.javarailways.simplesignals.runtime

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
import work.art1st.javarailways.simplesignals.block.FixedBlockRouteResourcePolicy
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
import work.art1st.javarailways.simplesignals.signal.FixedBlockSignalDependency
import work.art1st.javarailways.simplesignals.signal.FixedBlockSignalContinuationRequirement
import work.art1st.javarailways.simplesignals.signal.ProtectionSignalDefinition
import work.art1st.javarailways.simplesignals.signal.ProtectionSignalKind
import work.art1st.javarailways.simplesignals.signal.RouteIndicatorDefinition
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

    @Test
    fun `committed device edits retain public handles and placement cache while reconciling only changed models`() {
        val fixture = Fixture()
        val store = store()
        val sources = CapturingSignalEpochSourceService()
        val models = RecordingModels()
        val infrastructure = MutableTopologyService(fixture.currentTopology)
        var queries = 0
        val delegate = fixtureRailTargets()
        val rails = proxy(RailTargetService::class.java) { method, arguments ->
            check(method.name == "findCurve")
            queries++
            delegate.findCurve(arguments!![0] as UUID, arguments[1] as UUID)
        }
        val blocks = listOf(FixedBlockDefinition("test:first-block", 1,
            listOf(FixedBlockTraversal(fixture.entry, ViewDirection.AB, 0.0, 4.0))),
            FixedBlockDefinition("test:second-block", 1,
                listOf(FixedBlockTraversal(fixture.exit, ViewDirection.AB, 6.0, 9.0))))
        fun signal(id: String, revision: Long = 1, model: DeviceModelDefinition? = railModel(fixture.entry),
            speed: Double = 12.0, variant: String = "proceed") = FixedBlockSignalDefinition("test:$id", revision,
            "test:$id-block", 0.0, ConfiguredSignalOutput(0.0, "stop"),
            listOf(FixedBlockSignalClearLevel(0.0, ConfiguredSignalOutput(speed, variant))), model = model)
        val firstDefinition = signal("first")
        val secondDefinition = signal("second", model = railModel(fixture.exit))
        store.save(fixture.worldId, SimpleSignalsWorldDefinitions(fixture.worldId, 1, emptyList(), emptyList(), blocks,
            listOf(firstDefinition, secondDefinition)))
        val manager = runtimeManager(plugin(), infrastructure.service, CapturingRoutePlanningService(), store,
            sources, railTargets = rails, modelPresentation = models)
        assertEquals(SimpleSignalsWorldInstallStatus.INSTALLED, manager.install(fixture.worldId).status)
        val runtime = manager.authoringRuntime(fixture.worldId)!!
        val first = models.byId("fixed-block-signal/test:first")
        val second = models.byId("fixed-block-signal/test:second")
        val epoch = SignalEpochRuntime(fixture.worldId.toString(), TrainRestrictionSnapshotStore(fixture.worldId.toString()))
        var number = 0L
        fun baseline() {
            epoch.replaceSources(sources.sources.getValue(fixture.worldId))
            epoch.process(RailwayWorldFrameSnapshot.create(++number, fixture.currentTopology.revision,
                emptyList(), ActiveRailContinuationsSnapshot.empty(fixture.currentTopology.revision)))
        }
        fun apply(signals: List<FixedBlockSignalDefinition>, editedBlocks: List<FixedBlockDefinition> = blocks) {
            val current = runtime.currentDefinitions()
            val target = SimpleSignalsWorldDefinitions(fixture.worldId, current.definitionRevision, emptyList(), emptyList(),
                editedBlocks, signals)
            val patch = definitionDraftPatch(current, target)
            assertEquals(SimpleSignalsDefinitionAuthoringStatus.VALIDATED,
                manager.validateDefinitionPatch(fixture.worldId, current.definitionRevision, patch).status)
            assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED,
                manager.applyDefinitionPatch(fixture.worldId, current.definitionRevision, patch).status)
            baseline()
        }
        baseline()
        assertEquals(2, queries)
        val ruleEdit = signal("first", 2, firstDefinition.model, 3.0, "stop")
        apply(listOf(ruleEdit, secondDefinition))
        assertEquals(2, queries, "same exact topology and placement must not sample rails on rule edits or validate")
        assertSame(first, models.byId(first.id))
        assertSame(second, models.byId(second.id))
        assertEquals(mapOf("lamp" to "red"), first.submissions.last().animationScenes())

        val beforeInvalid = runtime.authoringPublication()
        val beforeInvalidDisk = Files.readString(store.primaryFile(fixture.worldId))
        val beforeInvalidSources = sources.sources.getValue(fixture.worldId)
        val beforeInvalidPermits = infrastructure.permitAcquisitionCount
        val invalidPatch = SimpleSignalsDefinitionPatch(upsertFixedBlockSignals = listOf(
            signal("first", 3, ruleEdit.model, 3.0, "missing-variant")))
        for (result in listOf(
            manager.validateDefinitionPatch(fixture.worldId, beforeInvalid.definitions.definitionRevision, invalidPatch),
            manager.applyDefinitionPatch(fixture.worldId, beforeInvalid.definitions.definitionRevision, invalidPatch)
        )) {
            assertEquals(SimpleSignalsDefinitionAuthoringStatus.VALIDATION_REJECTED, result.status)
            assertTrue(result.diagnostics.any { it.contains("missing-variant") })
        }
        assertEquals(2, queries, "pose cache reuse must still reject an invalid configured variant")
        assertSame(beforeInvalid, runtime.authoringPublication())
        assertEquals(beforeInvalidDisk, Files.readString(store.primaryFile(fixture.worldId)))
        assertSame(beforeInvalidSources, sources.sources.getValue(fixture.worldId))
        assertEquals(beforeInvalidPermits, infrastructure.permitAcquisitionCount)
        assertEquals(2, models.handles.size)
        assertTrue(models.handles.all { it.closeCalls == 0 })

        val moved = signal("first", 3, railModel(fixture.entry, 2.0), 3.0, "stop")
        apply(listOf(moved, secondDefinition))
        assertEquals(4, queries, "validate and apply independently prepare the changed placement")
        assertSame(first, models.byId(first.id))
        assertEquals(2.0, first.submissions.last().transform().x())
        assertSame(second, models.byId(second.id))
        assertEquals(2, models.handles.size)
        assertTrue(models.handles.all { it.closeCalls == 0 })

        val beforeRotation = first.submissions.last()
        val placement = moved.model!!.placement as ModelPlacement.RailAttached
        val rotated = signal("first", 4, DeviceModelDefinition("demo_signals:home", placement.copy(
            adjustment = ModelPose(0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0))), 3.0, "stop")
        apply(listOf(rotated, secondDefinition))
        val afterRotation = first.submissions.last()
        assertEquals(6, queries, "rotation-only edits reprepare the pose on validate and apply")
        assertSame(first, models.byId(first.id))
        assertEquals(beforeRotation.transform().x(), afterRotation.transform().x())
        assertEquals(beforeRotation.transform().y(), afterRotation.transform().y())
        assertEquals(beforeRotation.transform().z(), afterRotation.transform().z())
        val oldPose = beforeRotation.transform()
        val newPose = afterRotation.transform()
        assertEquals(0.0, kotlin.math.abs(oldPose.rotationX() * newPose.rotationX() +
            oldPose.rotationY() * newPose.rotationY() + oldPose.rotationZ() * newPose.rotationZ() +
            oldPose.rotationW() * newPose.rotationW()), 1e-12, "model rotates by 180 degrees in place")
        assertEquals(beforeRotation.animationScenes(), afterRotation.animationScenes())
        assertTrue(afterRotation.revision() > beforeRotation.revision())
        assertSame(second, models.byId(second.id))
        assertEquals(2, models.handles.size)
        assertTrue(models.handles.all { it.closeCalls == 0 })

        val thirdBlock = FixedBlockDefinition("test:third-block", 1,
            listOf(FixedBlockTraversal(fixture.entry, ViewDirection.AB, 6.0, 8.0)))
        val third = signal("third", model = DeviceModelDefinition("demo_signals:home", ModelPlacement.Ground(ModelPose.IDENTITY)))
        apply(listOf(rotated, secondDefinition, third), blocks + thirdBlock)
        assertEquals(3, models.handles.size)
        val thirdHandle = models.byId("fixed-block-signal/test:third")
        assertSame(first, models.byId(first.id))
        assertSame(second, models.byId(second.id))
        apply(listOf(rotated, secondDefinition), blocks + thirdBlock)
        assertEquals(1, thirdHandle.closeCalls)
        assertEquals(3, models.handles.size)

        val different = signal("first", 5, DeviceModelDefinition("demo_signals:wall", rotated.model!!.placement), 3.0, "stop")
        apply(listOf(different, secondDefinition), blocks + thirdBlock)
        assertEquals(1, first.closeCalls)
        assertNotSame(first, models.byId(first.id))
        assertSame(second, models.byId(second.id))
        assertEquals(0, second.closeCalls)
        assertEquals(4, models.handles.size)
        val replacement = models.byId(first.id)
        apply(listOf(different, secondDefinition), blocks + FixedBlockDefinition(thirdBlock.blockId, 2, thirdBlock.traversals))
        assertSame(replacement, models.byId(first.id))
        assertSame(second, models.byId(second.id))
        val beforeQueries = queries
        infrastructure.topology = fixture.topology(fixture.currentTopology.revision)
        apply(listOf(different, secondDefinition), blocks + FixedBlockDefinition(thirdBlock.blockId, 3, thirdBlock.traversals))
        assertEquals(beforeQueries + 4, queries, "an equal-revision different topology root must reprepare both placements")
        assertEquals(4, models.handles.size)
        for (handle in listOf(first, second, replacement)) {
            assertEquals(handle.submissions.indices.map(Int::toLong), handle.submissions.map { it.revision() })
        }
        val cleanup = manager.clearLocal().toCompletableFuture()
        models.cleanup.complete(null)
        cleanup.get(5, TimeUnit.SECONDS)
        assertTrue(models.handles.all { it.closeCalls == 1 })
    }

    @Test
    fun `pure model validation allocates nothing and invalid model or rejected unrelated patch retains live display and disk`() {
        val fixture = Fixture()
        val store = store()
        val sources = CapturingSignalEpochSourceService()
        val models = RecordingModels()
        val manager = runtimeManager(plugin(), MutableTopologyService(fixture.currentTopology).service,
            CapturingRoutePlanningService(), store, sources, modelPresentation = models)
        manager.install(fixture.worldId)
        val runtime = manager.authoringRuntime(fixture.worldId)!!
        val document = deviceModelDefinitions(fixture, "fixed", DeviceModelDefinition("demo_signals:home",
            ModelPlacement.Ground(ModelPose.IDENTITY)))
        val patch = definitionDraftPatch(runtime.currentDefinitions(), document)
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.VALIDATED,
            manager.validateDefinitionPatch(fixture.worldId, 0, patch).status)
        assertTrue(models.handles.isEmpty())
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED, manager.applyDefinitionPatch(fixture.worldId, 0, patch).status)
        val handle = models.handles.single()
        val publication = runtime.authoringPublication()
        val disk = Files.readString(store.primaryFile(fixture.worldId))
        val signal = document.fixedBlockSignals.single()
        val invalid = FixedBlockSignalDefinition(signal.signalId, 2, signal.controlledBlockId,
            signal.inspectionLeadTimeSeconds, ConfiguredSignalOutput(0.0, "missing-variant"),
            signal.clearLevels, signal.downstreamSignals, signal.model)
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.VALIDATION_REJECTED,
            manager.applyDefinitionPatch(fixture.worldId, 1, SimpleSignalsDefinitionPatch(upsertFixedBlockSignals = listOf(invalid))).status)
        sources.nextPrevalidationFailure = IllegalArgumentException("reject candidate source")
        assertEquals(SimpleSignalsDefinitionAuthoringStatus.VALIDATION_REJECTED,
            manager.applyDefinitionPatch(fixture.worldId, 1, SimpleSignalsDefinitionPatch(upsertFixedBlocks = listOf(
                FixedBlockDefinition("test:unrelated", 1, listOf(FixedBlockTraversal(fixture.exit, ViewDirection.AB, 7.0, 9.0)))))).status)
        assertSame(publication, runtime.authoringPublication())
        assertEquals(disk, Files.readString(store.primaryFile(fixture.worldId)))
        assertEquals(1, models.handles.size)
        assertFalse(handle.closed)
    }

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
            val stop = ConfiguredSignalOutput(0.0, "stop")
            val levels = listOf(FixedBlockSignalClearLevel(0.0, ConfiguredSignalOutput(12.0, "clear")))
            val region = ProtectionRegionDefinition(fixture.region.regionId, revision, fixture.region.boundaries)
            return SimpleSignalsWorldDefinitions(fixture.worldId, baseline, listOf(region),
                listOf(fixture.table(revision, fixture.upperRoute(), regionRevision = revision)),
                listOf(FixedBlockDefinition("test:block", revision, listOf(FixedBlockTraversal(fixture.entry, ViewDirection.AB, 0.0, 4.0)))),
                listOf(FixedBlockSignalDefinition("test:fixed", revision, "test:block", 0.0, stop, levels)),
                listOf(ProtectionSignalDefinition("test:protection", revision, ProtectionSignalKind.FIXED_ROUTE,
                    "test:upper", 0.0, stop, stop, levels)),
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
        assertEquals(1000, rejected.editDecision!!.blockers.first().overlap.railIds.size)
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
        fun localSignal(revision: Long, speed: Double) = FixedBlockSignalDefinition("test:local-signal", revision,
            local.blockId, 0.0, ConfiguredSignalOutput(0.0, "occupied"),
            listOf(FixedBlockSignalClearLevel(0.0, ConfiguredSignalOutput(speed, "clear"))))
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
        fun signal(index: Int, revision: Long = 1, downstream: Int? = null) = FixedBlockSignalDefinition(
            "test:signal-$index", revision, "test:block-$index", 0.0, ConfiguredSignalOutput(0.0, "occupied"),
            listOf(FixedBlockSignalClearLevel(0.0, ConfiguredSignalOutput(12.0, "clear"))),
            downstream?.let { listOf(FixedBlockSignalDependency("test:signal-$it")) }.orEmpty())
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
        listOf("fixed", "fixed-route", "current-entry", "indicator").forEach { kind ->
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
        listOf("fixed", "fixed-route", "current-entry", "indicator").forEach { kind ->
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
                    assertEquals(expectedRails, infrastructure.lastEditRequest!!.impact.railIds)
                    assertEquals(primary, Files.readString(definitions.primaryFile(fixture.worldId)))
                    assertSame(retained, runtime.authoringPublication())
                    assertSame(retainedSources, sources.sources.getValue(fixture.worldId))
                    release()
                    val applied = manager.applyDefinitionPatch(fixture.worldId, 1, patch)
                    assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED, applied.status, "$kind: ${applied.diagnostics}")
                    assertEquals(expectedRails, infrastructure.lastEditRequest!!.impact.railIds)
                    assertEquals(1, infrastructure.permitCloseCount)
                }
            }
        }
    }

    @Test
    fun `ground model edits keep every device logical protection and omit unrelated rails`() {
        listOf("fixed", "fixed-route", "current-entry", "indicator").forEach { kind ->
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
            val logicalRail = if (kind == "fixed") fixture.entry else fixture.upperIn
            val release = protect(registry, topology, logicalRail, TopologyEditBlockerKind.LIVE_RUNTIME_CLAIM)
            val patch = definitionDraftPatch(initial, candidate)
            val blocked = manager.applyDefinitionPatch(fixture.worldId, 1, patch)
            assertEquals(SimpleSignalsDefinitionAuthoringStatus.EDIT_BLOCKED, blocked.status, "$kind: ${blocked.diagnostics}")
            assertEquals(deviceLogicalRails(fixture, kind), infrastructure.lastEditRequest!!.impact.railIds)
            assertEquals(primary, Files.readString(definitions.primaryFile(fixture.worldId)))
            assertSame(retained, runtime.authoringPublication())
            assertSame(retainedSources, sources.sources.getValue(fixture.worldId))
            release()
            val applied = manager.applyDefinitionPatch(fixture.worldId, 1, patch)
            assertEquals(SimpleSignalsDefinitionAuthoringStatus.APPLIED, applied.status, "$kind: ${applied.diagnostics}")
            assertEquals(deviceLogicalRails(fixture, kind), infrastructure.lastEditRequest!!.impact.railIds)
        }
    }

    private fun railModel(rail: UUID, at: Double = 0.0) = DeviceModelDefinition("demo_signals:home",
        ModelPlacement.RailAttached(RailPositionSnapshot(rail, ViewDirection.BA, at)))

    private fun deviceLogicalRails(fixture: Fixture, kind: String) = if (kind == "fixed") setOf(fixture.entry)
        else fixture.upperRoute().traversals.mapTo(linkedSetOf()) { it.railId }

    private fun deviceModelDefinitions(fixture: Fixture, kind: String, model: DeviceModelDefinition?, revision: Long = 1): SimpleSignalsWorldDefinitions {
        val stop = ConfiguredSignalOutput(0.0, "stop")
        val levels = listOf(FixedBlockSignalClearLevel(0.0, ConfiguredSignalOutput(12.0, "proceed")))
        val route = fixture.upperRoute()
        return SimpleSignalsWorldDefinitions(fixture.worldId, 1, listOf(fixture.region),
            listOf(fixture.table(1, route)), listOf(FixedBlockDefinition("test:block", 1,
                listOf(FixedBlockTraversal(fixture.entry, ViewDirection.AB, 0.0, 4.0)))),
            fixedBlockSignals = if (kind == "fixed") listOf(FixedBlockSignalDefinition("test:device", revision,
                "test:block", 0.0, stop, levels, model = model)) else emptyList(),
            protectionSignals = if (kind == "fixed-route" || kind == "current-entry") listOf(ProtectionSignalDefinition(
                "test:device", revision, if (kind == "fixed-route") ProtectionSignalKind.FIXED_ROUTE else ProtectionSignalKind.CURRENT_ENTRY,
                if (kind == "fixed-route") route.routeId else route.entryBoundaryId, 0.0, stop, stop, levels, model = model)) else emptyList(),
            routeIndicators = if (kind == "indicator") listOf(RouteIndicatorDefinition("test:device", revision,
                route.entryBoundaryId, "stop", mapOf(route.routeId to "proceed"), model)) else emptyList())
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
        fun signal(name: String, revision: Long = 1, dependencies: List<FixedBlockSignalDependency> = emptyList()) =
            FixedBlockSignalDefinition("test:$name-signal", revision, "test:$name-block", 0.0,
                ConfiguredSignalOutput(0.0, "stop"),
                listOf(FixedBlockSignalClearLevel(0.0, ConfiguredSignalOutput(12.0, "proceed"))), dependencies,
                model = if (name == "source") railModel(upstreamAttachment) else null)
        val entry = endpoint(fixture.entry, RailEndpoint.B)
        val dependencies = listOf("upper" to fixture.upperIn, "lower" to fixture.lowerIn).map { (name, rail) ->
            FixedBlockSignalDependency("test:$name-signal", listOf(FixedBlockSignalContinuationRequirement(
                entry, endpoint(rail, RailEndpoint.A)))) }
        val definitions = store()
        definitions.save(fixture.worldId, SimpleSignalsWorldDefinitions(fixture.worldId, 1, emptyList(), emptyList(), blocks,
            listOf(signal("source", dependencies = dependencies), signal("upper"), signal("lower"))))
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
        0.0, ConfiguredSignalOutput(0.0, "none"), ConfiguredSignalOutput(0.0, "occupied"),
        listOf(FixedBlockSignalClearLevel(0.0, ConfiguredSignalOutput(17.0, "clear"))))

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
        val provider = FixedBlockRouteResourcePolicy(plugin) { runtime?.fixedBlockSnapshot() }
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

    private class MutableTopologyService(initial: TopologyState?, private val registry: InfrastructureCoordinationRegistry? = null) {
        private val registryOwner = Any()
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
            modelFailure: (String, Throwable) -> Unit = { _, _ -> }
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
            modelFailure
        ).also {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
            it.loadModelPackages(Path.of(requireNotNull(SimpleSignalsWorldRuntimeManagerTest::class.java
                .getResource("/model-packages")).toURI()))
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
