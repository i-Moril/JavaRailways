package work.art1st.javarailways.simpleato.routing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.ato.mission.AtoMission
import work.art1st.javarailways.basic.api.ato.mission.AtoMissionLeg
import work.art1st.javarailways.basic.api.ato.mission.AtoMissionPoint
import work.art1st.javarailways.basic.api.ato.mission.WayPointRef
import work.art1st.javarailways.basic.api.observation.RailwayObservation
import work.art1st.javarailways.basic.api.observation.RailwayStateStamp
import work.art1st.javarailways.basic.api.observation.RailwayTrainObservation
import work.art1st.javarailways.basic.route.ExpandedRoute
import work.art1st.javarailways.basic.route.PlannedRoute
import work.art1st.javarailways.basic.route.RailViewRef
import work.art1st.javarailways.basic.route.RouteSection
import work.art1st.javarailways.basic.route.RouteSegment
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.RailwayOccupancySnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot
import work.art1st.javarailways.core.physics.rail.topology.TestTopologyRail
import work.art1st.javarailways.core.physics.rail.topology.buildTestTopology
import java.util.UUID

class FrozenMissionRouteSelectorTest {
    @Test
    fun `tail-started route is accepted only as the same mission traversal after the head`() {
        val railId = UUID(0L, 1L)
        val topology = buildTestTopology(
            revision = 7L,
            rails = listOf(TestTopologyRail(railId, 100.0)),
            continuations = emptyMap<RailEndpointRef, Set<RailEndpointRef>>()
        )
        val observation = observation(
            topologyRevision = topology.revision,
            tail = RailPositionSnapshot(railId, ViewDirection.AB, 2.0),
            head = RailPositionSnapshot(railId, ViewDirection.AB, 12.0)
        )

        val result = FrozenMissionRouteSelector.select(
            topology = topology,
            context = FrozenRouteSelectionContext(observation),
            operatingDirections = OperatingDirectionOverlay(topology.revision, 3L, emptyMap()),
            trainId = TRAIN_ID,
            mission = mission(railId, ViewDirection.AB, 12.0, 80.0)
        ) as FrozenMissionRouteResult.Frozen

        assertEquals(10.0, result.route.headDistanceFromRouteStartMeters, 1.0e-12)
        assertEquals(2.0, result.route.selection.route.segments.single().startArcLengthMeters, 1.0e-12)
        assertEquals(80.0, result.route.selection.route.segments.single().endArcLengthMeters, 1.0e-12)
        assertEquals("mission", result.route.selection.route.routeId)
        assertEquals(5L, result.route.selection.route.revision)
    }

    @Test
    fun `opposite mission traversal is rejected instead of claiming another route`() {
        val railId = UUID(0L, 2L)
        val topology = buildTestTopology(
            revision = 8L,
            rails = listOf(TestTopologyRail(railId, 100.0)),
            continuations = emptyMap<RailEndpointRef, Set<RailEndpointRef>>()
        )
        val observation = observation(
            topologyRevision = topology.revision,
            tail = RailPositionSnapshot(railId, ViewDirection.AB, 2.0),
            head = RailPositionSnapshot(railId, ViewDirection.AB, 12.0)
        )

        val result = FrozenMissionRouteSelector.select(
            topology = topology,
            context = FrozenRouteSelectionContext(observation),
            operatingDirections = OperatingDirectionOverlay(topology.revision, 3L, emptyMap()),
            trainId = TRAIN_ID,
            mission = mission(railId, ViewDirection.BA, 20.0, 88.0)
        ) as FrozenMissionRouteResult.Rejected

        assertEquals(FrozenMissionRouteRejection.INVALID_MISSION, result.reason)
        assertTrue(result.detail.contains("head"))
    }

    @Test
    fun `a different shortest branch is rejected instead of replacing the dispatcher mission`() {
        val start = UUID(0L, 10L)
        val shortBranch = UUID(0L, 11L)
        val missionBranch = UUID(0L, 12L)
        val goal = UUID(0L, 13L)
        val firstJunction = setOf(
            RailEndpointRef(start, RailEndpoint.B),
            RailEndpointRef(shortBranch, RailEndpoint.A),
            RailEndpointRef(missionBranch, RailEndpoint.A)
        )
        val secondJunction = setOf(
            RailEndpointRef(shortBranch, RailEndpoint.B),
            RailEndpointRef(missionBranch, RailEndpoint.B),
            RailEndpointRef(goal, RailEndpoint.A)
        )
        val continuations = (firstJunction + secondJunction).associateWith { port ->
            val junction = if (port in firstJunction) firstJunction else secondJunction
            junction - port
        }
        val topology = buildTestTopology(
            revision = 9L,
            rails = listOf(
                TestTopologyRail(start, 20.0),
                TestTopologyRail(shortBranch, 10.0),
                TestTopologyRail(missionBranch, 20.0),
                TestTopologyRail(goal, 20.0)
            ),
            continuations = continuations
        )
        val observation = observation(
            topologyRevision = topology.revision,
            tail = RailPositionSnapshot(start, ViewDirection.AB, 2.0),
            head = RailPositionSnapshot(start, ViewDirection.AB, 12.0)
        )
        val mission = mission(
            listOf(
                RouteSegment(RailViewRef(start, ViewDirection.AB), 12.0, 20.0),
                RouteSegment(RailViewRef(missionBranch, ViewDirection.AB), 0.0, 20.0),
                RouteSegment(RailViewRef(goal, ViewDirection.AB), 0.0, 10.0)
            )
        )

        val result = FrozenMissionRouteSelector.select(
            topology = topology,
            context = FrozenRouteSelectionContext(observation),
            operatingDirections = OperatingDirectionOverlay(topology.revision, 3L, emptyMap()),
            trainId = TRAIN_ID,
            mission = mission
        ) as FrozenMissionRouteResult.Rejected

        assertEquals(FrozenMissionRouteRejection.SELECTED_ROUTE_DIFFERS_FROM_MISSION, result.reason)
    }

    private fun mission(
        railId: UUID,
        direction: ViewDirection,
        startInViewMeters: Double,
        endInViewMeters: Double
    ): AtoMission = mission(
        listOf(RouteSegment(RailViewRef(railId, direction), startInViewMeters, endInViewMeters))
    )

    private fun mission(segments: List<RouteSegment>): AtoMission {
        val expanded = ExpandedRoute("origin", "target", segments)
        val planned = PlannedRoute(
            routeId = "mission/leg/0",
            revision = 5L,
            expandedRoute = expanded,
            sections = listOf(RouteSection(0, 0.0, expanded.totalLength, emptyList()))
        )
        return AtoMission(
            id = "mission",
            revision = 5L,
            origin = WayPointRef("origin"),
            legs = listOf(AtoMissionLeg(0, AtoMissionPoint.Stop(WayPointRef("target")), planned))
        )
    }

    private fun observation(
        topologyRevision: Long,
        tail: RailPositionSnapshot,
        head: RailPositionSnapshot
    ): RailwayObservation {
        val frame = TrainFrameSnapshot.create(
            trainId = TRAIN_ID,
            trainName = TRAIN_ID,
            revision = 1L,
            physicsEpoch = 4L,
            speedMetersPerSecond = 0.0,
            derailed = false,
            reversed = false,
            cars = emptyList(),
            head = head,
            tail = tail,
            occupiedIntervals = emptyList(),
            sweptSpans = emptyList(),
            headTraversal = emptyList(),
            tailTraversal = emptyList()
        )
        return RailwayObservation.create(
            stamp = RailwayStateStamp("world", frame.physicsEpoch, topologyRevision),
            trains = listOf(RailwayTrainObservation.create(
                trainId = frame.trainId,
                trainName = frame.trainName,
                frameRevision = frame.revision,
                speedMetersPerSecond = frame.speedMetersPerSecond,
                derailed = frame.derailed,
                reversed = frame.reversed,
                head = frame.head,
                tail = frame.tail,
                occupiedIntervals = frame.occupiedIntervals,
                sweptSpans = frame.sweptSpans,
                headTraversal = frame.headTraversal,
                tailTraversal = frame.tailTraversal
            )),
            occupancy = RailwayOccupancySnapshot.create(frame.physicsEpoch, listOf(frame))
        )
    }

    private companion object {
        const val TRAIN_ID = "train"
    }
}
