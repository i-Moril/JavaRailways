package work.art1st.javarailways.simpleato.routing

import work.art1st.javarailways.core.util.IsolatedSimulationThreadMarker

import org.joml.Vector3d
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.WayPoint
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
import work.art1st.javarailways.core.physics.train.createTestWorld
import work.art1st.javarailways.simpleato.api.route.RoutePlanningRestrictionBinding
import work.art1st.javarailways.simpleato.simpleAtoWorldData
import work.art1st.javarailways.simpleato.testing.TestSimpleATOWorldDataRegistry

class OperationalRoutePlannerTest {
    private val worlds = mutableListOf<CoreWorld>()

    @AfterEach
    fun cleanup() {
        worlds.forEach(TestSimpleATOWorldDataRegistry::unregister)
    }

    @Test
    fun `adapts topology markers to positive expanded segments and preserves endpoint directions`() {
        val world = createTestWorld().also(worlds::add)
        TestSimpleATOWorldDataRegistry.getOrCreate(world)
        val first = world.createRailForTest(straight(0.0, 10.0))
        val second = world.createRailForTest(straight(10.0, 30.0))
        IsolatedSimulationThreadMarker.run { RailView.connect(first.viewAB, second.viewBA) }
        val planner = OperationalRoutePlanner(world)

        val result = planner.buildRoute(
            from = WayPoint("from", "From", first.id, 10.0),
            to = WayPoint("to", "To", second.id, 5.0),
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
                from = WayPoint("from", "From", first.id, 10.0),
                to = WayPoint("to", "To", second.id, 5.0),
                requiredGoalDirection = ViewDirection.BA,
                requiredStartDirection = ViewDirection.AB
            ) is RouteBuildResult.Failure
        )
    }

    @Test
    fun `registered restrictions are compiled once before an operational query`() {
        val world = createTestWorld().also(worlds::add)
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
            WayPoint("from", "From", rail.id, 10.0),
            WayPoint("to", "To", rail.id, 90.0)
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
