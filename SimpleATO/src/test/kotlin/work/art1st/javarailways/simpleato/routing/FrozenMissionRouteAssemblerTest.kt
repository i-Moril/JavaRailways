package work.art1st.javarailways.simpleato.routing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.ato.mission.AtoMission
import work.art1st.javarailways.basic.api.ato.mission.AtoMissionLeg
import work.art1st.javarailways.basic.api.ato.mission.AtoMissionPoint
import work.art1st.javarailways.basic.api.ato.mission.WayPointRef
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.api.observation.RailwayObservation
import work.art1st.javarailways.basic.api.observation.RailwayStateStamp
import work.art1st.javarailways.basic.api.observation.RailwayTrainObservation
import work.art1st.javarailways.basic.route.ExpandedRoute
import work.art1st.javarailways.basic.route.PlannedRoute
import work.art1st.javarailways.basic.route.RailViewRef
import work.art1st.javarailways.basic.route.RouteSegment
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.RailwayOccupancySnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.api.physics.train.RailTraversalSegmentSnapshot
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot
import work.art1st.javarailways.core.physics.rail.topology.TestTopologyRail
import work.art1st.javarailways.core.physics.rail.topology.buildTestTopology
import java.util.UUID

class FrozenMissionRouteAssemblerTest {
    @Test
    fun `ordinary stop appends a separately revisioned mission without changing the route prefix`() {
        val rail = UUID(0L, 40L)
        val topology = buildTestTopology(
            revision = 13L,
            rails = listOf(TestTopologyRail(rail, 100.0)),
            continuations = emptyMap<RailEndpointRef, Set<RailEndpointRef>>()
        )
        val firstObservation = observation(
            topology.revision,
            RailPositionSnapshot(rail, ViewDirection.AB, 0.0),
            RailPositionSnapshot(rail, ViewDirection.AB, 10.0),
            listOf(traversal(rail, ViewDirection.AB, 0.0, 10.0))
        )
        val first = (FrozenMissionRouteAssembler.assemble(
            topology,
            FrozenRouteSelectionContext(firstObservation),
            TRAIN_ID,
            mission(
                segments = listOf(RouteSegment(RailViewRef(rail, ViewDirection.AB), 10.0, 20.0)),
                missionId = "first",
                revision = 0L
            ),
            missionHeadProgressMeters = 0.0
        ) as FrozenMissionRouteResult.Frozen).route
        val committedPrefix = first.selection.route.segments.toList()
        val stopObservation = observation(
            topology.revision,
            RailPositionSnapshot(rail, ViewDirection.AB, 10.0),
            RailPositionSnapshot(rail, ViewDirection.AB, 20.0),
            listOf(traversal(rail, ViewDirection.AB, 10.0, 20.0))
        )

        val appended = (FrozenMissionRouteAssembler.append(
            topology,
            FrozenRouteSelectionContext(stopObservation),
            first,
            mission(
                segments = listOf(RouteSegment(RailViewRef(rail, ViewDirection.AB), 20.0, 40.0)),
                missionId = "second",
                revision = 0L
            ),
            missionHeadProgressMeters = 0.25
        ) as FrozenMissionRouteResult.Frozen).route

        assertEquals(first.selection.route.routeId, appended.selection.route.routeId)
        assertEquals(first.selection.route.revision + 1L, appended.selection.route.revision)
        assertEquals(committedPrefix,
            appended.selection.route.segments.take(committedPrefix.size))
        assertEquals(0.0, appended.missionHeadProgressAtSelectionMeters, 1.0e-12)
        assertEquals(20.0, appended.selection.route.segments.last().startArcLengthMeters, 1.0e-12)
        assertEquals(20.0, appended.headDistanceFromRouteStartMeters, 1.0e-12)
        assertEquals(20.0, appended.routeProgressMeters(0.0), 1.0e-12)
        assertEquals(35.0, appended.routeProgressMeters(15.0), 1.0e-12)
    }

    @Test
    fun `reversed continuation removes the mission prefix already covered by the train body`() {
        val rail = UUID(0L, 41L)
        val topology = buildTestTopology(
            revision = 14L,
            rails = listOf(TestTopologyRail(rail, 100.0)),
            continuations = emptyMap<RailEndpointRef, Set<RailEndpointRef>>()
        )
        val previousObservation = observation(
            topology.revision,
            RailPositionSnapshot(rail, ViewDirection.AB, 0.0),
            RailPositionSnapshot(rail, ViewDirection.AB, 10.0),
            listOf(traversal(rail, ViewDirection.AB, 0.0, 10.0))
        )
        val previous = FrozenMissionRoute(
            FrozenRouteSelectionResult.Selected(
                TRAIN_ID,
                previousObservation.stamp,
                RouteSnapshot(
                    "operating-route",
                    5L,
                    topology.revision,
                    listOf(RouteSegmentSnapshot(rail, ViewDirection.AB, 0.0, 60.0))
                )
            ),
            headDistanceFromRouteStartMeters = 10.0,
            missionHeadProgressAtSelectionMeters = 0.0
        )
        val reversedObservation = observation(
            topology.revision,
            RailPositionSnapshot(rail, ViewDirection.BA, 60.0),
            RailPositionSnapshot(rail, ViewDirection.BA, 50.0),
            listOf(traversal(rail, ViewDirection.BA, 60.0, 50.0))
        )
        val body = (FrozenMissionRouteAssembler.rebaseBodyAfterReversal(
            topology,
            FrozenRouteSelectionContext(reversedObservation),
            previous
        ) as FrozenMissionRouteResult.Frozen).route

        val appended = (FrozenMissionRouteAssembler.append(
            topology,
            FrozenRouteSelectionContext(reversedObservation),
            body,
            mission(
                segments = listOf(RouteSegment(RailViewRef(rail, ViewDirection.BA), 40.0, 80.0)),
                missionId = "return",
                revision = 0L
            ),
            missionHeadProgressMeters = 10.0
        ) as FrozenMissionRouteResult.Frozen).route

        assertEquals("operating-route", appended.selection.route.routeId)
        assertEquals(7L, appended.selection.route.revision)
        assertEquals(
            listOf(
                RouteSegmentSnapshot(rail, ViewDirection.BA, 40.0, 50.0),
                RouteSegmentSnapshot(rail, ViewDirection.BA, 50.0, 80.0)
            ),
            appended.selection.route.segments
        )
        assertEquals(10.0, appended.headDistanceFromRouteStartMeters, 1.0e-12)
        assertEquals(10.0, appended.missionHeadProgressAtSelectionMeters, 1.0e-12)
        assertEquals(10.0, appended.routeProgressMeters(10.0), 1.0e-12)
        assertEquals(20.0, appended.routeProgressMeters(20.0), 1.0e-12)
    }

    @Test
    fun `published body is prepended to the mission without another route search`() {
        val approach = UUID(0L, 1L)
        val missionBranch = UUID(0L, 2L)
        val shorterBranch = UUID(0L, 3L)
        val goal = UUID(0L, 4L)
        val firstJunction = setOf(
            RailEndpointRef(approach, RailEndpoint.B),
            RailEndpointRef(missionBranch, RailEndpoint.A),
            RailEndpointRef(shorterBranch, RailEndpoint.A)
        )
        val secondJunction = setOf(
            RailEndpointRef(missionBranch, RailEndpoint.B),
            RailEndpointRef(shorterBranch, RailEndpoint.B),
            RailEndpointRef(goal, RailEndpoint.A)
        )
        val continuations = (firstJunction + secondJunction).associateWith { port ->
            val junction = if (port in firstJunction) firstJunction else secondJunction
            junction - port
        }
        val topology = buildTestTopology(
            revision = 9L,
            rails = listOf(
                TestTopologyRail(approach, 20.0),
                TestTopologyRail(missionBranch, 20.0),
                TestTopologyRail(shorterBranch, 10.0),
                TestTopologyRail(goal, 20.0)
            ),
            continuations = continuations
        )
        val observation = observation(
            topologyRevision = topology.revision,
            tail = RailPositionSnapshot(approach, ViewDirection.AB, 2.0),
            head = RailPositionSnapshot(approach, ViewDirection.AB, 12.0),
            body = listOf(traversal(approach, ViewDirection.AB, 2.0, 12.0))
        )
        val mission = mission(
            listOf(
                RouteSegment(RailViewRef(approach, ViewDirection.AB), 12.0, 20.0),
                RouteSegment(RailViewRef(missionBranch, ViewDirection.AB), 0.0, 20.0),
                RouteSegment(RailViewRef(goal, ViewDirection.AB), 0.0, 10.0)
            )
        )

        val frozen = FrozenMissionRouteAssembler.assemble(
            topology,
            FrozenRouteSelectionContext(observation),
            TRAIN_ID,
            mission,
            missionHeadProgressMeters = 0.0
        ) as FrozenMissionRouteResult.Frozen

        assertEquals(10.0, frozen.route.headDistanceFromRouteStartMeters, 1.0e-12)
        assertEquals(10.0, frozen.route.routeProgressMeters(0.0), 1.0e-12)
        assertEquals(25.0, frozen.route.routeProgressMeters(15.0), 1.0e-12)
        assertEquals(
            listOf(approach, missionBranch, goal),
            frozen.route.selection.route.segments.map { it.railId }
        )
        assertEquals(2.0, frozen.route.selection.route.segments.first().startArcLengthMeters, 1.0e-12)
        assertEquals(20.0, frozen.route.selection.route.segments.first().endArcLengthMeters, 1.0e-12)
        assertEquals("mission", frozen.route.selection.route.routeId)
        assertEquals(5L, frozen.route.selection.route.revision)
    }

    @Test
    fun `body spanning rails remains ordered and joins the mission at the head`() {
        val first = UUID(0L, 10L)
        val second = UUID(0L, 11L)
        val third = UUID(0L, 12L)
        val firstJoin = setOf(RailEndpointRef(first, RailEndpoint.B), RailEndpointRef(second, RailEndpoint.A))
        val secondJoin = setOf(RailEndpointRef(second, RailEndpoint.B), RailEndpointRef(third, RailEndpoint.A))
        val topology = buildTestTopology(
            revision = 10L,
            rails = listOf(
                TestTopologyRail(first, 20.0),
                TestTopologyRail(second, 20.0),
                TestTopologyRail(third, 20.0)
            ),
            continuations = (firstJoin + secondJoin).associateWith { port ->
                val join = if (port in firstJoin) firstJoin else secondJoin
                join - port
            }
        )
        val observation = observation(
            topology.revision,
            RailPositionSnapshot(first, ViewDirection.AB, 15.0),
            RailPositionSnapshot(second, ViewDirection.AB, 7.0),
            listOf(
                traversal(first, ViewDirection.AB, 15.0, 20.0),
                traversal(second, ViewDirection.AB, 0.0, 7.0)
            )
        )

        val frozen = FrozenMissionRouteAssembler.assemble(
            topology,
            FrozenRouteSelectionContext(observation),
            TRAIN_ID,
            mission(
                listOf(
                    RouteSegment(RailViewRef(second, ViewDirection.AB), 7.0, 20.0),
                    RouteSegment(RailViewRef(third, ViewDirection.AB), 0.0, 8.0)
                )
            ),
            missionHeadProgressMeters = 0.0
        ) as FrozenMissionRouteResult.Frozen

        assertEquals(12.0, frozen.route.headDistanceFromRouteStartMeters, 1.0e-12)
        assertEquals(listOf(first, second, third), frozen.route.selection.route.segments.map { it.railId })
        assertEquals(0.0, frozen.route.selection.route.segments[1].startArcLengthMeters, 1.0e-12)
        assertEquals(20.0, frozen.route.selection.route.segments[1].endArcLengthMeters, 1.0e-12)
    }

    @Test
    fun `mission must continue from the physical body endpoint`() {
        val rail = UUID(0L, 20L)
        val topology = buildTestTopology(
            revision = 11L,
            rails = listOf(TestTopologyRail(rail, 100.0)),
            continuations = emptyMap<RailEndpointRef, Set<RailEndpointRef>>()
        )
        val observation = observation(
            topology.revision,
            RailPositionSnapshot(rail, ViewDirection.AB, 2.0),
            RailPositionSnapshot(rail, ViewDirection.AB, 12.0),
            listOf(traversal(rail, ViewDirection.AB, 2.0, 12.0))
        )

        val rejected = FrozenMissionRouteAssembler.assemble(
            topology,
            FrozenRouteSelectionContext(observation),
            TRAIN_ID,
            mission(listOf(RouteSegment(RailViewRef(rail, ViewDirection.AB), 13.0, 80.0))),
            missionHeadProgressMeters = 0.0
        ) as FrozenMissionRouteResult.Rejected

        assertEquals(FrozenMissionRouteRejection.INVALID_MISSION, rejected.reason)
    }

    @Test
    fun `existing mission progress trims the selected BA route without reprojecting an occurrence`() {
        val rail = UUID(0L, 30L)
        val topology = buildTestTopology(
            revision = 12L,
            rails = listOf(TestTopologyRail(rail, 100.0)),
            continuations = emptyMap<RailEndpointRef, Set<RailEndpointRef>>()
        )
        val observation = observation(
            topology.revision,
            RailPositionSnapshot(rail, ViewDirection.BA, 60.0),
            RailPositionSnapshot(rail, ViewDirection.BA, 50.0),
            listOf(traversal(rail, ViewDirection.BA, 60.0, 50.0))
        )

        val frozen = FrozenMissionRouteAssembler.assemble(
            topology,
            FrozenRouteSelectionContext(observation),
            TRAIN_ID,
            mission(listOf(RouteSegment(RailViewRef(rail, ViewDirection.BA), 0.0, 90.0))),
            missionHeadProgressMeters = 50.0
        ) as FrozenMissionRouteResult.Frozen

        assertEquals(10.0, frozen.route.headDistanceFromRouteStartMeters, 1.0e-12)
        assertEquals(50.0, frozen.route.missionHeadProgressAtSelectionMeters, 1.0e-12)
        assertEquals(15.0, frozen.route.routeProgressMeters(55.0), 1.0e-12)
        assertEquals(40.0, frozen.route.selection.route.segments.single().startArcLengthMeters, 1.0e-12)
        assertEquals(90.0, frozen.route.selection.route.segments.single().endArcLengthMeters, 1.0e-12)
    }

    private fun mission(
        segments: List<RouteSegment>,
        missionId: String = "mission",
        revision: Long = 5L
    ): AtoMission {
        val expanded = ExpandedRoute("origin", "target", segments)
        val planned = PlannedRoute(
            routeId = "$missionId/leg/0",
            revision = revision,
            expandedRoute = expanded
        )
        return AtoMission(
            id = missionId,
            revision = revision,
            origin = WayPointRef("origin"),
            legs = listOf(AtoMissionLeg(0, AtoMissionPoint.Stop(WayPointRef("target")), planned))
        )
    }

    private fun observation(
        topologyRevision: Long,
        tail: RailPositionSnapshot,
        head: RailPositionSnapshot,
        body: List<RailTraversalSegmentSnapshot>
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
            bodyTraversal = body,
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
                bodyTraversal = frame.bodyTraversal,
                sweptSpans = frame.sweptSpans,
                headTraversal = frame.headTraversal,
                tailTraversal = frame.tailTraversal
            )),
            occupancy = RailwayOccupancySnapshot.create(frame.physicsEpoch, listOf(frame))
        )
    }

    private fun traversal(
        railId: UUID,
        facing: ViewDirection,
        startAb: Double,
        endAb: Double
    ) = RailTraversalSegmentSnapshot(railId, facing, startAb, endAb)

    private companion object {
        const val TRAIN_ID = "train"
    }
}
