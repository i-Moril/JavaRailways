package work.art1st.javarailways.basic.route

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.BasicWorldData
import work.art1st.javarailways.basic.WayPoint
import work.art1st.javarailways.basic.pathFinding.TestBasicWorldDataRegistry
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.createTestWorld

class DirectionRestrictionRouteTest {
    @Test
    fun `route planner only uses allowed direction`() {
        val world = createTestWorld()
        val data = BasicWorldData()
        TestBasicWorldDataRegistry.register(world, data)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        val pointA = WayPoint("A", "A", rail.id, 10.0)
        val pointB = WayPoint("B", "B", rail.id, 90.0)
        data.wayPoints.add(pointA)
        data.wayPoints.add(pointB)
        var permittedDirection: ViewDirection? = ViewDirection.AB
        fun planner() = RoutePlanner(world) { view ->
            permittedDirection?.let { it == view.viewDirection } ?: true
        }

        assertTrue(planner().buildRoute(pointA, pointB) is RouteBuildResult.Success)
        assertTrue(planner().buildRoute(pointB, pointA) is RouteBuildResult.Failure)

        permittedDirection = null
        assertTrue(planner().buildRoute(pointB, pointA) is RouteBuildResult.Success)
    }
}
