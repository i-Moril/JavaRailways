package work.art1st.javarailways.simpleato.routing

import org.junit.jupiter.api.function.Executable
import org.junit.jupiter.api.Assertions.assertAll
import work.art1st.javarailways.core.physics.train.TestWorldFixture
import work.art1st.javarailways.core.util.IsolatedSimulationThreadMarker

import org.joml.Vector3d
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.basicWorldData
import work.art1st.javarailways.basic.api.route.WayPoint
import work.art1st.javarailways.basic.api.operation.DoorSide
import work.art1st.javarailways.simpleato.api.destination.DestinationRole
import work.art1st.javarailways.simpleato.api.destination.OperationalDestination
import work.art1st.javarailways.simpleato.api.destination.OperationalDestinationService
import work.art1st.javarailways.simpleato.api.station.StationId
import work.art1st.javarailways.basic.api.route.planning.DirectedRailSpan
import work.art1st.javarailways.basic.api.route.planning.RoutePlanningRestrictions
import work.art1st.javarailways.basic.api.route.planning.RoutePlanningRestrictionSource
import work.art1st.javarailways.simpleato.routing.RouteBuildResult
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.createRailForTest
import work.art1st.javarailways.core.physics.rail.RailView
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.simpleato.api.route.RoutePlanningRestrictionBinding
import work.art1st.javarailways.simpleato.simpleAtoWorldData
import work.art1st.javarailways.simpleato.testing.TestSimpleATOWorldDataRegistry

class OperationalRoutePlannerTest {
    private val worldFixtures = mutableListOf<TestWorldFixture>()

    @AfterEach
    fun closeOwnedWorldFixtures() {
        val fixtures = worldFixtures.toList()
        worldFixtures.clear()
        assertAll(fixtures.flatMap { fixture ->
            listOf(
                Executable { TestSimpleATOWorldDataRegistry.unregister(fixture.world) },
                Executable { fixture.close() }
            )
        })
    }



    @Test
    fun `waypoint pruning is opt in and ordered mission via points restore the route`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        TestSimpleATOWorldDataRegistry.getOrCreate(world)
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0, 1))
        val points = listOf(WayPoint("from", rail.id, 10.0), WayPoint("via", rail.id, 50.0), WayPoint("to", rail.id, 90.0))
        points.forEach(world.basicWorldData.wayPoints::add)
        val planner = OperationalRoutePlanner(world)
        assertTrue(planner.buildRoute("from", "to") is RouteBuildResult.Success)
        assertTrue(planner.buildRoute("from", "to", requireUniquePath = true, pruneNonTargetWaypoints = true) is RouteBuildResult.Failure)
        val request = work.art1st.javarailways.simpleato.api.mission.AtoMissionRequest("via-mission", 1L,
            work.art1st.javarailways.simpleato.api.mission.WayPointRef("from"), listOf(
                work.art1st.javarailways.simpleato.api.mission.AtoMissionPoint.Via(work.art1st.javarailways.simpleato.api.mission.WayPointRef("via")),
                work.art1st.javarailways.simpleato.api.mission.AtoMissionPoint.Stop(work.art1st.javarailways.simpleato.api.mission.WayPointRef("to"))
            ), requiredStartDirection = ViewDirection.AB, requireUniquePath = true, pruneNonTargetWaypoints = true)
        val resolved = work.art1st.javarailways.simpleato.api.mission.AtoMissionResolver(world).resolve(request)
            as work.art1st.javarailways.simpleato.api.mission.AtoMissionResolutionResult.Success
        assertEquals(listOf("via", "to"), resolved.mission.legs.map { it.point.target.id })
        assertEquals(80.0, resolved.mission.legs.sumOf { it.plannedRoute.expandedRoute.totalLength }, 1e-6)
    }

    @Test
    fun `waypoint pruning never blocks query endpoints without station roles`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        TestSimpleATOWorldDataRegistry.getOrCreate(world)
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0, 1))
        listOf(WayPoint("from", rail.id, 10.0), WayPoint("to", rail.id, 90.0))
            .forEach(world.basicWorldData.wayPoints::add)
        val planner = OperationalRoutePlanner(world)
        assertTrue(planner.buildRoute("from", "to", requireUniquePath = true, pruneNonTargetWaypoints = true)
            is RouteBuildResult.Success)
    }

    @Test
    fun `same station alternative berths do not prune but unrelated directed destinations do`() {
        for (otherDirection in listOf(ViewDirection.AB, ViewDirection.BA, null)) {
            val world = TestWorldFixture.create().also(worldFixtures::add).world
            TestSimpleATOWorldDataRegistry.getOrCreate(world)
            val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0, 1))
            val destinations = OperationalDestinationService(world)
            fun add(station: String, berth: String, arc: Double, direction: ViewDirection?) {
                destinations.register(OperationalDestination("$station@$berth", rail.id, arc, direction,
                    DestinationRole.StationBerthRole(StationId(station), berthId = berth, doorSide = DoorSide.NONE)))
            }
            add("origin", "selected", 10.0, ViewDirection.AB)
            add("origin", "alternative", 20.0, null)
            add("goal", "alternative", 80.0, ViewDirection.AB)
            add("goal", "selected", 90.0, ViewDirection.AB)
            val planner = OperationalRoutePlanner(world)
            fun query() = planner.buildRoute("origin@selected", "goal@selected", requiredStartDirection = ViewDirection.AB,
                requireUniquePath = true, pruneNonTargetWaypoints = true)
            assertTrue(query() is RouteBuildResult.Success)
            add("unrelated", "middle", 50.0, otherDirection)
            assertEquals(otherDirection == ViewDirection.BA, query() is RouteBuildResult.Success)
        }
    }

    @Test
    fun `adapts topology markers to positive expanded segments and preserves endpoint directions`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        TestSimpleATOWorldDataRegistry.getOrCreate(world)
        val first = world.createRailForTest(straight(0.0, 10.0))
        val second = world.createRailForTest(straight(10.0, 30.0))
        IsolatedSimulationThreadMarker.run { RailView.connect(first.viewAB, second.viewBA) }
        val planner = OperationalRoutePlanner(world)

        val result = planner.buildRoute(
            from = WayPoint("from", first.id, 10.0),
            to = WayPoint("to", second.id, 5.0),
            requiredGoalDirection = ViewDirection.AB,
            requiredStartDirection = ViewDirection.AB
        )
        assertTrue(result is RouteBuildResult.Success, (result as? RouteBuildResult.Failure)?.reason)
        val planned = result as RouteBuildResult.Success

        assertTrue(planned.route.segments.all { it.length > 0.0 })
        assertEquals(listOf(second.id), planned.route.segments.map { it.railViewRef.railId })
        assertEquals(ViewDirection.AB, planned.route.segments.single().railViewRef.direction)
        assertTrue(
            planner.buildRoute(
                from = WayPoint("from", first.id, 10.0),
                to = WayPoint("to", second.id, 5.0),
                requiredGoalDirection = ViewDirection.BA,
                requiredStartDirection = ViewDirection.AB
            ) is RouteBuildResult.Failure
        )
    }

    @Test
    fun `positive route adapter preserves exact interior query endpoints in both directions`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        TestSimpleATOWorldDataRegistry.getOrCreate(world)
        val prefix = world.createRailForTest(straight(0.0, 1000.0))
        val target = world.createRailForTest(straight(1000.0, 1110.0))
        val suffix = world.createRailForTest(straight(1110.0, 2110.0))
        IsolatedSimulationThreadMarker.run {
            RailView.connect(prefix.viewAB, target.viewBA)
            RailView.connect(target.viewAB, suffix.viewBA)
        }
        val planner = OperationalRoutePlanner(world)
        val low = 3.6701266737305
        val high = 109.1008889681433

        for (facing in ViewDirection.entries) {
            val start = if (facing == ViewDirection.AB) low else high
            val end = if (facing == ViewDirection.AB) high else low
            val result = planner.buildRoute(
                from = WayPoint("from", target.id, start),
                to = WayPoint("to", target.id, end),
                requiredStartDirection = facing,
                requiredGoalDirection = facing
            )
            assertTrue(result is RouteBuildResult.Success, (result as? RouteBuildResult.Failure)?.reason)
            val segment = (result as RouteBuildResult.Success).route.segments.single()
            assertTrue(segment.length > 0.0)
            assertEquals(facing, segment.railViewRef.direction)
            assertEquals(if (facing == ViewDirection.AB) start else target.curve.totalArcLength - start, segment.startArcLength)
            assertEquals(if (facing == ViewDirection.AB) end else target.curve.totalArcLength - end, segment.endArcLength)
        }
    }

    @Test
    fun `registered restrictions are compiled once before an operational query`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        TestSimpleATOWorldDataRegistry.getOrCreate(world)
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0, 1))
        var invocations = 0
        world.simpleAtoWorldData.routePlanningRestrictions.replaceSources(
            listOf(
                RoutePlanningRestrictionBinding(
                    "test:block-route",
                    RoutePlanningRestrictionSource { topology ->
                        invocations++
                        RoutePlanningRestrictions(
                            topology.revision,
                            forbiddenTraversals = ViewDirection.entries.map { direction ->
                                DirectedRailSpan(rail.id, direction, 0.0, 100.0)
                            }
                        )
                    }
                )
            )
        )

        val result = OperationalRoutePlanner(world).buildRoute(
            WayPoint("from", rail.id, 10.0),
            WayPoint("to", rail.id, 90.0)
        )

        assertTrue(result is RouteBuildResult.Failure)
        assertEquals(1, invocations)
    }

    private fun straight(from: Double, to: Double): PrimeCurve = PrimeCurve.fromPoints(
        listOf(
            Curve.RawPoint(
                Vector3d(from, 0.0, 0.0),
                Vector3d(1.0, 0.0, 0.0),
                Vector3d(0.0, 1.0, 0.0),
                0.0
            ),
            Curve.RawPoint(
                Vector3d(to, 0.0, 0.0),
                Vector3d(1.0, 0.0, 0.0),
                Vector3d(0.0, 1.0, 0.0),
                to - from
            )
        )
    )
}
