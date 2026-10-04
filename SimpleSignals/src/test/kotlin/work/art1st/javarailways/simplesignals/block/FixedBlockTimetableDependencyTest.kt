package work.art1st.javarailways.simplesignals.block

import work.art1st.javarailways.simplesignals.testing.signalWorldRuntime

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.BasicPlugin
import work.art1st.javarailways.basic.BasicWorldData
import work.art1st.javarailways.basic.WayPoint
import work.art1st.javarailways.basic.api.infrastructure.*
import work.art1st.javarailways.basic.infrastructure.InfrastructureClaimRequest
import work.art1st.javarailways.basic.infrastructure.InfrastructureCoordinationRegistry
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.util.plugin.ExtensionData
import work.art1st.javarailways.core.physics.createRailForTest
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.train.createTestWorld
import work.art1st.javarailways.core.physics.train.createTrainWithConfig
import work.art1st.javarailways.simpleato.SimpleATOPlugin
import work.art1st.javarailways.simpleato.SimpleATOWorldData
import work.art1st.javarailways.simpleato.api.ato.AtoWorldCoordinator
import work.art1st.javarailways.simpleato.api.inspection.InspectionSource
import work.art1st.javarailways.simpleato.inspection.InspectionRuntime
import work.art1st.javarailways.simpleato.persistence.SavedDispatcherV1
import work.art1st.javarailways.simpleato.persistence.SavedTimetableClockV1
import work.art1st.javarailways.simpleato.restriction.TrainRestrictionSnapshotStore
import work.art1st.javarailways.simpleato.signal.SignalEpochRuntime
import work.art1st.javarailways.simpleato.timetable.*
import work.art1st.javarailways.simplesignals.persistence.SimpleSignalsWorldDefinitions
import work.art1st.javarailways.simplesignals.runtime.*
import java.util.UUID

/** Real Dispatcher, SimpleSignals matcher/publication and Basic registry with deterministic edits. */
@OptIn(ExtensionData::class)
class FixedBlockTimetableDependencyTest {
    @Test
    fun `completed block edit before physical guard is matched from current publication and rejects divergent route`() =
        runEditOrdering(editBeforeGuard = true)

    @Test
    fun `block edit after resource collection is denied by installed physical guard`() =
        runEditOrdering(editBeforeGuard = false)

    @Test
    fun `checkpoint restore protects physical route before matching and installing block resources`() =
        runEditOrdering(editBeforeGuard = false, restore = true)

    private fun runEditOrdering(editBeforeGuard: Boolean, restore: Boolean = false) {
        val worldId = UUID.randomUUID()
        val world = createTestWorld()
        try {
            val entry = world.createRailForTest(straight(Vector3d(), Vector3d(40.0, 0.0, 0.0)))
            val upper = world.createRailForTest(straight(Vector3d(40.0, 0.0, 0.0), Vector3d(80.0, 0.0, 0.0)))
            val lower = world.createRailForTest(straight(Vector3d(40.0, 0.0, 0.0), Vector3d(80.0, 0.0, 10.0)))
            CoreRail.connect(entry, upper)
            CoreRail.connect(entry, lower)
            val topology = world.topologyState
            val train = createTrainWithConfig("timetable-race", entry, world, listOf(10.0))
            val origin = train.head.location.arcLength
            val originalBlock = FixedBlockDefinition("test:block", 1L,
                listOf(FixedBlockTraversal(entry.id, ViewDirection.AB, origin - 1.0, origin + 5.0)))
            val divergentBlock = FixedBlockDefinition(originalBlock.blockId, 2L, listOf(
                FixedBlockTraversal(entry.id, ViewDirection.AB, origin - 1.0, 40.0),
                FixedBlockTraversal(lower.id, ViewDirection.AB, 0.0, 5.0)))
            val signals = signalWorldRuntime(worldId)
            assertEquals(SimpleSignalsDefinitionPublicationStatus.PUBLISHED,
                signals.initializeDefinitions(topology, SimpleSignalsWorldDefinitions(worldId, 1L,
                    emptyList(), emptyList(), listOf(originalBlock))).status)
            val preparedEdit = (signals.prepareDefinitions(topology, SimpleSignalsWorldDefinitions(worldId, 2L,
                emptyList(), emptyList(), listOf(divergentBlock))) as SimpleSignalsDefinitionPreparationResult.Prepared).value
            val registry = InfrastructureCoordinationRegistry({ world.topologyState.revision }, { resource ->
                when (resource) {
                    is RailIntervalResource -> world.getRail(resource.railId) != null
                    is StableInfrastructureResource -> resource.typeId != FIXED_BLOCK_RESOURCE_TYPE ||
                        signals.fixedBlockSnapshot().blocksById.containsKey(resource.resourceId)
                }
            }, { world.getRail(it) != null }, { key ->
                topology.nodes.values.any { StableTopologyNodeKey(it.ports) == key }
            })
            val requester = Any()
            val editor = Any()
            var setCalls = 0
            var matched = false
            fun attemptEdit() {
                val permit = registry.acquireEditPermit(editor, TopologyEditRequest(topology.revision,
                    InfrastructureOwner("test:block-editor", 2L), checkNotNull(preparedEdit.editImpact)))
                if (editBeforeGuard) {
                    assertEquals(TopologyEditStatus.GRANTED, permit.decision.status)
                    try {
                        assertEquals(SimpleSignalsDefinitionCommitStatus.COMMITTED, signals.commitPrepared(preparedEdit))
                    } finally {
                        assertTrue(registry.releaseEditPermit(editor, permit.id))
                    }
                } else {
                    assertTrue(matched, "the attempted edit must occur after the real matcher returned")
                    assertEquals(TopologyEditStatus.CONFLICT, permit.decision.status)
                    assertEquals(TopologyEditBlockerKind.INFRASTRUCTURE_DEPENDENCY, permit.decision.blockers.single().kind)
                    assertTrue(entry.id in permit.decision.blockers.single().overlap.railIds)
                    assertEquals(1L, signals.currentDefinitions().definitionRevision)
                }
            }
            val dependencies = object : InfrastructureDependencyService {
                override fun set(request: InfrastructureDependencyRequest): InfrastructureDependencyMutationResult {
                    setCalls++
                    if (setCalls == if (editBeforeGuard) 1 else 2) attemptEdit()
                    return registry.setInfrastructureDependencies(requester, request)
                }
                override fun remove(owner: InfrastructureOwner) = registry.removeInfrastructureDependencies(requester, owner)
            }
            val coordinator = AtoWorldCoordinator(worldId.toString(), reportSink = {})
            val basic = BasicWorldData(safetyInterlock = coordinator.safetyInterlock)
            val restrictions = TrainRestrictionSnapshotStore(worldId.toString())
            val inspection = InspectionRuntime(restrictions) { _, _, failure -> throw failure }
            val actualSource = signals.fixedBlockInspectionSource
            inspection.replaceSources(listOf(InspectionSource(actualSource.sourceId, actualSource.bindingProvider,
                actualSource.updateHandler, { route ->
                    assertEquals(setOf(entry.id, upper.id), registry.dependencySnapshots().single().dependencies.railIds)
                    actualSource.routeInfrastructureDependencies.dependencies(route).also { matched = true }
                })))
            val dispatcher = TimetableDispatcher(TimetableRouteAdmissionContextProvider { null }, restrictions,
                inspection, dependencies)
            val epoch = SignalEpochRuntime(worldId.toString(), restrictions, restrictions::completeEpoch, inspection::markDirty)
            val ato = SimpleATOWorldData(coordinator, dispatcher, epoch, restrictions, inspection,
                basic.railObjects, basic.safetyInterlock)
            val basicPlugin = BasicPlugin.instanceOrNull() ?: BasicPlugin(0).also { BasicPlugin.INSTANCE = it }
            val atoPlugin = SimpleATOPlugin.instanceOrNull() ?: SimpleATOPlugin(101).also { SimpleATOPlugin.INSTANCE = it }
            world.registerExtensionData(basicPlugin, basic)
            world.registerExtensionData(atoPlugin, ato)
            basic.wayPoints.add(WayPoint("A", "Origin", entry.id, origin))
            basic.wayPoints.add(WayPoint("B", "Target", upper.id, 10.0))
            ato.timetables.add(Timetable("race", services = listOf(TimetableService("s1", TrainSelector.TrainId(train.id),
                listOf(TimetableStep.Check("A"), TimetableStep.At(0L, TimetableAction.GoTo("B")))))))

            if (restore) {
                assertEquals(TimetableCheckpointRestoreResult.Restored, dispatcher.restoreCheckpoint(world,
                    SavedDispatcherV1(1, 0L, 0L, listOf(SavedTimetableClockV1("race", 0L, 24_000L, 1L,
                        emptySet(), emptyMap())), emptyList())))
            } else if (editBeforeGuard) {
                val failed = assertInstanceOf(TimetableEpochStartResult.Failure::class.java, dispatcher.beginTimetable(world, "race"))
                assertTrue(failed.reason.contains("route leaves the directed traversal of fixed block"), failed.reason)
                assertFalse(dispatcher.scheduleInitialCycle("race"))
                assertTrue(registry.dependencySnapshots().isEmpty())
                assertEquals(2L, signals.currentDefinitions().definitionRevision)
            } else {
                assertTrue(dispatcher.beginTimetable(world, "race") is TimetableEpochStartResult.Started)
            }
            assertTrue(registry.claimSnapshots().isEmpty(), "guard and final dependencies are non-exclusive")
            if (!editBeforeGuard) {
                val token = StableInfrastructureResource(FIXED_BLOCK_RESOURCE_TYPE, originalBlock.blockId)
                assertTrue(token in registry.dependencySnapshots().single().dependencies.resourceIds)
                val claimOwner = InfrastructureOwner("test:train", 1L)
                assertEquals(InfrastructureMutationStatus.ACQUIRED, registry.acquireClaim(requester,
                    InfrastructureClaimRequest(topology.revision, claimOwner, InfrastructureFootprint(listOf(token)))).status)
                assertEquals(InfrastructureMutationStatus.RELEASED, registry.releaseClaim(requester, claimOwner).status)
                assertTrue(dispatcher.stopFutureCycles("race"))
                assertTrue(registry.dependencySnapshots().isEmpty())
            }
            assertEquals(if (editBeforeGuard) 1 else 2, setCalls)
            assertFalse(coordinator.isStalled)
        } finally {
            world.physicsCoordinator.close()
            (world.tickManager as? AutoCloseable)?.close()
        }
    }

    private fun straight(from: Vector3d, to: Vector3d): PrimeCurve {
        val tangent = Vector3d(to).sub(from).normalize()
        val normal = Vector3d(0.0, 1.0, 0.0)
        return PrimeCurve.fromPoints(listOf(
            Curve.RawPoint(from, tangent, normal, 0.0, Curve.SegmentType.LINE_START),
            Curve.RawPoint(to, tangent, normal, from.distance(to), Curve.SegmentType.LINE_END)))
    }
}
