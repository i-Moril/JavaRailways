package work.art1st.javarailways.simplesignals.region

import work.art1st.javarailways.simplesignals.testing.signalWorldRuntime

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.BasicPlugin
import work.art1st.javarailways.basic.BasicWorldData
import work.art1st.javarailways.basic.WayPoint
import work.art1st.javarailways.basic.api.infrastructure.*
import work.art1st.javarailways.core.api.physics.infrastructure.*
import work.art1st.javarailways.core.physics.infrastructure.InfrastructureClaimRequest
import work.art1st.javarailways.core.physics.infrastructure.InfrastructureCoordinationRegistry
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.util.plugin.ExtensionData
import work.art1st.javarailways.core.physics.createRailForTest
import work.art1st.javarailways.core.physics.train.createTestWorld
import work.art1st.javarailways.core.physics.train.createTrainWithConfig
import work.art1st.javarailways.core.physics.train.onOwnerForTest
import work.art1st.javarailways.simpleato.SimpleATOPlugin
import work.art1st.javarailways.simpleato.SimpleATOWorldData
import work.art1st.javarailways.simpleato.api.ato.AtoWorldCoordinator
import work.art1st.javarailways.simpleato.inspection.InspectionRuntime
import work.art1st.javarailways.simpleato.restriction.TrainRestrictionSnapshotStore
import work.art1st.javarailways.simpleato.signal.SignalEpochRuntime
import work.art1st.javarailways.simpleato.timetable.*
import work.art1st.javarailways.simplesignals.block.*
import work.art1st.javarailways.simplesignals.persistence.SimpleSignalsWorldDefinitions
import work.art1st.javarailways.simplesignals.runtime.*
import java.util.UUID

/** Real selected-route apply, plugin matching and Basic edit dependency registry; no live epoch. */
@OptIn(ExtensionData::class)
class ProtectionRegionTimetableDependencyTest {
    @Test
    fun `no-table crossing applies but internal endpoint rejects before clock and initial position changes`() {
        for (replacement in listOf(false, true)) {
            val worldId = UUID.randomUUID()
            val world = createTestWorld()
            try {
                val rail = world.createRailForTest(straight(80.0))
                val train = createTrainWithConfig("protection-timetable", rail, world, listOf(10.0))
                val origin = train.head.location.arcLength
                val region = ProtectionRegionDefinition("test:region", 1L, listOf(
                    ProtectionBoundaryDefinition("test:entry", RailPositionSnapshot(rail.id, ViewDirection.AB, origin + 5.0)),
                    ProtectionBoundaryDefinition("test:exit", RailPositionSnapshot(rail.id, ViewDirection.BA, origin + 15.0))))
                val block = FixedBlockDefinition("test:successor", 1L,
                    listOf(FixedBlockTraversal(rail.id, ViewDirection.AB, origin + 15.0, origin + 35.0)))
                val signals = signalWorldRuntime(worldId)
                assertEquals(SimpleSignalsDefinitionPublicationStatus.PUBLISHED,
                    signals.initializeDefinitions(world.topologyState, SimpleSignalsWorldDefinitions(worldId, 1L,
                        listOf(region), emptyList(), listOf(block))).status)
                val registry = InfrastructureCoordinationRegistry({ world.topologyState.revision }, { resource ->
                    when (resource) {
                        is RailIntervalResource -> resource.railId == rail.id
                        is StableInfrastructureResource -> resource.typeId == FIXED_BLOCK_RESOURCE_TYPE &&
                            signals.fixedBlockSnapshot().blocksById.containsKey(resource.resourceId)
                    }
                }, { it == rail.id }, { false })
                val requester = Any()
                val service = object : InfrastructureDependencyService {
                    override fun set(request: InfrastructureDependencyRequest) = registry.setInfrastructureDependencies(requester, request)
                    override fun remove(owner: InfrastructureOwner) = registry.removeInfrastructureDependencies(requester, owner)
                }
                val coordinator = AtoWorldCoordinator(worldId.toString(), reportSink = {})
                val basic = BasicWorldData(safetyInterlock = coordinator.safetyInterlock)
                val restrictions = TrainRestrictionSnapshotStore(worldId.toString())
                val inspection = InspectionRuntime(restrictions) { _, _, failure -> throw failure }
                inspection.replaceSources(listOf(signals.fixedBlockInspectionSource, signals.protectionRegionInspectionSource))
                val dispatcher = TimetableDispatcher(TimetableRouteAdmissionContextProvider { null }, restrictions, inspection, service)
                val epoch = SignalEpochRuntime(worldId.toString(), restrictions, restrictions::completeEpoch, inspection::markDirty)
                val ato = SimpleATOWorldData(coordinator, dispatcher, epoch, restrictions, inspection,
                    basic.railObjects, basic.safetyInterlock)
                val basicPlugin = BasicPlugin.instanceOrNull() ?: BasicPlugin(0).also { BasicPlugin.INSTANCE = it }
                val atoPlugin = SimpleATOPlugin.instanceOrNull() ?: SimpleATOPlugin(101).also { SimpleATOPlugin.INSTANCE = it }
                world.registerExtensionData(basicPlugin, basic)
                world.registerExtensionData(atoPlugin, ato)
                ato.routePlanningRestrictions.replaceSources(listOf(signals.routePlanningBinding))
                basic.wayPoints.add(WayPoint("A", "Origin", rail.id, origin))
                basic.wayPoints.add(WayPoint("B", "Beyond region", rail.id, origin + 25.0))
                fun timetable(start: String, target: String, moveInitially: Boolean = false) = Timetable("protection",
                    services = listOf(TimetableService("s1", TrainSelector.TrainId(train.id), listOf(
                        TimetableStep.Check(start), TimetableStep.At(0L, TimetableAction.GoTo(target))))),
                    initialPositions = if (moveInitially) listOf(TimetableInitialPosition(
                        TimetableObjectSelector.Regex(train.name), TimetableObjectSelector.Regex("C"))) else emptyList())
                if (replacement) {
                    ato.timetables.add(timetable("A", "B"))
                    assertTrue(world.onOwnerForTest { dispatcher.beginTimetable(world, "protection") } is TimetableEpochStartResult.Started)
                    assertEquals(setOf(StableInfrastructureResource(FIXED_BLOCK_RESOURCE_TYPE, block.blockId)),
                        registry.dependencySnapshots().single().dependencies.resourceIds)
                }
                // Temporary markers belong only to the rejected internal-endpoint candidate.
                basic.wayPoints.add(WayPoint("C", "Candidate initial position", rail.id, origin + 2.0))
                basic.wayPoints.add(WayPoint("D", "Internal target", rail.id, origin + 10.0))
                val previous = registry.dependencySnapshots().singleOrNull()
                val oldClocks = dispatcher.saveCheckpoint().clocks
                val oldLocation = train.head.location.arcLength
                ato.timetables.add(timetable("C", "D", moveInitially = true))

                val failure = assertInstanceOf(TimetableEpochStartResult.Failure::class.java,
                    world.onOwnerForTest { dispatcher.beginTimetable(world, "protection") })

                assertTrue(failure.reason.contains("simplesignals:protection-regions"), failure.reason)
                assertTrue(failure.reason.contains("without one matching authored boundary"), failure.reason)
                assertEquals(oldClocks, dispatcher.saveCheckpoint().clocks)
                assertEquals(oldLocation, train.head.location.arcLength)
                if (previous == null) assertTrue(registry.dependencySnapshots().isEmpty())
                else {
                    assertEquals(previous.dependencies, registry.dependencySnapshots().single().dependencies)
                    assertTrue(registry.dependencySnapshots().single().owner.revision > previous.owner.revision)
                }
                assertTrue(registry.claimSnapshots().isEmpty())
                assertFalse(inspection.hasActiveRoutes())
                assertTrue(inspection.activeBindings(setOf(signals.protectionRegionInspectionSource.sourceId,
                    signals.fixedBlockInspectionSource.sourceId)).isEmpty())
                assertNull(signals.sectionSignalSnapshot())
                assertNull(signals.sectionOccupancySnapshot())
                assertNull(signals.protectionRouteSelectionSnapshot())
                assertFalse(coordinator.isStalled)
                // Remove the rejected candidate's markers before the independent A-to-B query.
                basic.wayPoints.remove("C")
                basic.wayPoints.remove("D")
                // A fresh legal apply still succeeds, independently of absent signal/occupancy baseline.
                ato.timetables.add(timetable("A", "B"))
                assertTrue(world.onOwnerForTest { dispatcher.beginTimetable(world, "protection") } is TimetableEpochStartResult.Started)
                val token = StableInfrastructureResource(FIXED_BLOCK_RESOURCE_TYPE, block.blockId)
                val claimant = InfrastructureOwner("test:train", 1L)
                assertEquals(InfrastructureMutationStatus.ACQUIRED, registry.acquireClaim(requester,
                    InfrastructureClaimRequest(claimant, InfrastructureFootprint(listOf(token)))).status)
                assertEquals(InfrastructureMutationStatus.RELEASED, registry.releaseClaim(requester, claimant).status)
                assertTrue(world.onOwnerForTest { dispatcher.stopFutureCycles("protection") })
                assertTrue(registry.dependencySnapshots().isEmpty())
            } finally {
                world.physicsCoordinator.close()
                (world.tickManager as? AutoCloseable)?.close()
            }
        }
    }

    private fun straight(length: Double): PrimeCurve {
        val tangent = Vector3d(1.0, 0.0, 0.0)
        val normal = Vector3d(0.0, 1.0, 0.0)
        return PrimeCurve.fromPoints(listOf(
            Curve.RawPoint(Vector3d(), tangent, normal, 0.0, Curve.SegmentType.LINE_START),
            Curve.RawPoint(Vector3d(length, 0.0, 0.0), tangent, normal, length, Curve.SegmentType.LINE_END)))
    }
}
