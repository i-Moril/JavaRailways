package work.art1st.javarailways.simpleato.routing

import org.junit.jupiter.api.Assertions.assertTrue
import work.art1st.javarailways.basic.api.route.planning.OperatingDirectionOverlay

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.observation.RailwayObservation
import work.art1st.javarailways.basic.api.observation.RailwayStateStamp
import work.art1st.javarailways.basic.api.observation.RailwayTrainObservation
import work.art1st.javarailways.basic.api.route.planning.DirectedRailSpan
import work.art1st.javarailways.basic.api.route.planning.RoutePlanningRestrictions
import work.art1st.javarailways.basic.api.route.planning.OperationalRouteLocation
import work.art1st.javarailways.basic.api.route.planning.TopologyRoutePlanningRejection
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.RailwayOccupancySnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.api.physics.rail.topology.ActiveRailContinuationsSnapshot
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot
import work.art1st.javarailways.core.physics.rail.topology.TestTopologyRail
import work.art1st.javarailways.core.physics.rail.topology.buildTestTopology
import work.art1st.javarailways.simpleato.api.route.RoutePlanningRestrictionBinding
import java.util.UUID

class FrozenRouteSelectorTest {
    @Test
    fun `selected route starts at the authoritative observed tail with its facing`() {
        val rail = rail(1, 20.0)
        val topology = topology(7L, listOf(rail))
        val tail = RailPositionSnapshot(rail.id, ViewDirection.BA, 15.0)
        val observation = observation(topology.revision, "train-1", tail)

        val selected = select(
            topology = topology,
            observation = observation,
            trainId = "train-1",
            target = OperationalRouteLocation(rail.id, 3.0)
        ) as FrozenRouteSelectionResult.Selected

        assertEquals("train-1", selected.trainId)
        assertEquals(observation.stamp, selected.observationStamp)
        assertEquals(7L, selected.route.topologyRevision)
        assertEquals(23L, selected.route.revision)
        assertEquals("test-route", selected.route.routeId)
        assertEquals(ViewDirection.BA, selected.route.segments.first().facing)
        assertEquals(5.0, selected.route.segments.first().startArcLengthMeters, 1e-12)
        assertEquals(17.0, selected.route.segments.first().endArcLengthMeters, 1e-12)
    }

    @Test
    fun `route cannot begin opposite to the authoritative tail facing`() {
        val rail = rail(1, 20.0)
        val topology = topology(8L, listOf(rail))
        val observation = observation(
            topology.revision,
            "train-1",
            RailPositionSnapshot(rail.id, ViewDirection.AB, 15.0)
        )

        val rejected = select(
            topology = topology,
            observation = observation,
            trainId = "train-1",
            target = OperationalRouteLocation(rail.id, 3.0)
        ) as FrozenRouteSelectionResult.Rejected

        assertEquals(FrozenRouteSelectionRejection.PLANNER_REJECTED, rejected.reason)
        assertEquals(TopologyRoutePlanningRejection.UNREACHABLE, rejected.plannerReason)
    }

    @Test
    fun `older topology observations and directions are revalidated`() {
        val rail = rail(1, 20.0)
        val topology = topology(9L, listOf(rail))
        val tail = RailPositionSnapshot(rail.id, ViewDirection.AB, 2.0)

        val staleObservation = select(
            topology = topology,
            observation = observation(topology.revision - 1L, "train-1", tail),
            trainId = "train-1",
            target = OperationalRouteLocation(rail.id, 10.0)
        )
        assertTrue(staleObservation is FrozenRouteSelectionResult.Selected)

        val staleDirections = select(
            topology = topology,
            observation = observation(topology.revision, "train-1", tail),
            trainId = "train-1",
            target = OperationalRouteLocation(rail.id, 10.0),
            overlayTopologyRevision = topology.revision - 1L
        )
        assertTrue(staleDirections is FrozenRouteSelectionResult.Selected)
    }

    @Test
    fun `missing and derailed trains return train-specific rejection`() {
        val rail = rail(1, 20.0)
        val topology = topology(10L, listOf(rail))
        val tail = RailPositionSnapshot(rail.id, ViewDirection.AB, 2.0)

        val missing = select(
            topology = topology,
            observation = observation(topology.revision, "another-train", tail),
            trainId = "train-1",
            target = OperationalRouteLocation(rail.id, 10.0)
        ) as FrozenRouteSelectionResult.Rejected
        assertEquals(FrozenRouteSelectionRejection.TRAIN_NOT_FOUND, missing.reason)

        val invalid = select(
            topology = topology,
            observation = observation(topology.revision, "train-1", tail, derailed = true),
            trainId = "train-1",
            target = OperationalRouteLocation(rail.id, 10.0)
        ) as FrozenRouteSelectionResult.Rejected
        assertEquals(FrozenRouteSelectionRejection.INVALID_TRAIN, invalid.reason)

        val invalidTail = select(
            topology = topology,
            observation = observation(
                topology.revision,
                "train-1",
                RailPositionSnapshot(UUID(0L, 999L), ViewDirection.AB, 2.0)
            ),
            trainId = "train-1",
            target = OperationalRouteLocation(rail.id, 10.0)
        ) as FrozenRouteSelectionResult.Rejected
        assertEquals(FrozenRouteSelectionRejection.INVALID_TRAIN, invalidTail.reason)
    }

    @Test
    fun `unreachable and invalid target preserve planner rejection`() {
        val first = rail(1, 20.0)
        val second = rail(2, 20.0)
        val topology = topology(11L, listOf(first, second))
        val observation = observation(
            topology.revision,
            "train-1",
            RailPositionSnapshot(first.id, ViewDirection.AB, 2.0)
        )

        val unreachable = select(
            topology = topology,
            observation = observation,
            trainId = "train-1",
            target = OperationalRouteLocation(second.id, 10.0)
        ) as FrozenRouteSelectionResult.Rejected
        assertEquals(FrozenRouteSelectionRejection.PLANNER_REJECTED, unreachable.reason)
        assertEquals(TopologyRoutePlanningRejection.UNREACHABLE, unreachable.plannerReason)

        val invalidTarget = select(
            topology = topology,
            observation = observation,
            trainId = "train-1",
            target = OperationalRouteLocation(first.id, 21.0)
        ) as FrozenRouteSelectionResult.Rejected
        assertEquals(FrozenRouteSelectionRejection.PLANNER_REJECTED, invalidTarget.reason)
        assertEquals(TopologyRoutePlanningRejection.INVALID_LOCATION, invalidTarget.plannerReason)

        val invalidDirections = select(
            topology = topology,
            observation = observation,
            trainId = "train-1",
            target = OperationalRouteLocation(first.id, 10.0),
            allowedDirections = mapOf(UUID(0L, 999L) to ViewDirection.AB)
        ) as FrozenRouteSelectionResult.Rejected
        assertEquals(FrozenRouteSelectionRejection.PLANNER_REJECTED, invalidDirections.reason)
        assertEquals(
            TopologyRoutePlanningRejection.INVALID_RESTRICTIONS,
            invalidDirections.plannerReason
        )
    }

    @Test
    fun `registered restrictions are compiled once before planning`() {
        val rail = rail(1, 20.0)
        val topology = topology(12L, listOf(rail))
        val observation = observation(
            topology.revision,
            "train-1",
            RailPositionSnapshot(rail.id, ViewDirection.AB, 2.0)
        )
        var calls = 0
        val restrictions = RoutePlanningRestrictionRuntime().also { runtime ->
            runtime.replaceSources(
                listOf(
                    RoutePlanningRestrictionBinding("test:route-table") { sourceTopology ->
                        calls++
                        RoutePlanningRestrictions(
                            sourceTopology.revision,
                            forbiddenTraversals = listOf(
                                DirectedRailSpan(rail.id, ViewDirection.AB, 5.0, 6.0)
                            )
                        )
                    }
                )
            )
        }

        val rejected = select(
            topology = topology,
            observation = observation,
            trainId = "train-1",
            target = OperationalRouteLocation(rail.id, 10.0),
            planningRestrictions = restrictions
        ) as FrozenRouteSelectionResult.Rejected

        assertEquals(1, calls)
        assertEquals(FrozenRouteSelectionRejection.PLANNER_REJECTED, rejected.reason)
        assertEquals(TopologyRoutePlanningRejection.UNREACHABLE, rejected.plannerReason)
    }

    private fun select(
        topology: TopologyState,
        observation: RailwayObservation,
        trainId: String,
        target: OperationalRouteLocation,
        overlayTopologyRevision: Long = topology.revision,
        allowedDirections: Map<UUID, ViewDirection> = emptyMap(),
        planningRestrictions: RoutePlanningRestrictionRuntime = RoutePlanningRestrictionRuntime()
    ): FrozenRouteSelectionResult = FrozenRouteSelector.select(
        FrozenRouteSelectionInput(
            topology = topology,
            context = FrozenRouteSelectionContext(observation),
            operatingDirections = OperatingDirectionOverlay(
                topologyRevision = overlayTopologyRevision,
                allowedDirections = allowedDirections
            ),
            planningRestrictions = planningRestrictions,
            trainId = trainId,
            target = target,
            routeId = "test-route",
            routeRevision = 23L
        )
    )

    private fun observation(
        topologyRevision: Long,
        trainId: String,
        tail: RailPositionSnapshot,
        derailed: Boolean = false
    ): RailwayObservation {
        val frame = TrainFrameSnapshot.create(
            trainId = trainId,
            trainName = trainId,
            revision = 3L,
            physicsEpoch = 5L,
            speedMetersPerSecond = 0.0,
            derailed = derailed,
            reversed = false,
            cars = emptyList(),
            head = tail,
            tail = tail,
            occupiedIntervals = emptyList(),
            bodyTraversal = listOf(work.art1st.javarailways.core.api.physics.train.RailTraversalSegmentSnapshot(
                tail.railId, tail.facing, tail.arcLengthInAbMeters, tail.arcLengthInAbMeters)),
            sweptSpans = emptyList(),
            headTraversal = emptyList(),
            tailTraversal = emptyList()
        )
        val train = RailwayTrainObservation.create(
            trainId = trainId,
            trainName = trainId,
            frameRevision = frame.revision,
            speedMetersPerSecond = frame.speedMetersPerSecond,
            derailed = frame.derailed,
            reversed = frame.reversed,
            head = frame.head,
            tail = frame.tail,
            occupiedIntervals = frame.occupiedIntervals,
            bodyTraversal = frame.bodyTraversal,
            sweptSpans = frame.sweptSpans,
            headTraversal = frame.headTraversal,
            tailTraversal = frame.tailTraversal
        )
        return RailwayObservation.create(
            stamp = RailwayStateStamp("test-world", frame.physicsEpoch, topologyRevision),
            trains = listOf(train),
            occupancy = RailwayOccupancySnapshot.create(frame.physicsEpoch, listOf(frame)),
            activeContinuations = ActiveRailContinuationsSnapshot.empty(topologyRevision)
        )
    }

    private fun topology(revision: Long, rails: List<TestTopologyRail>): TopologyState =
        buildTestTopology(
            revision = revision,
            rails = rails,
            continuations = emptyMap<RailEndpointRef, Set<RailEndpointRef>>()
        )

    private fun rail(number: Int, lengthMeters: Double) =
        TestTopologyRail(UUID(0L, number.toLong()), lengthMeters)
}
