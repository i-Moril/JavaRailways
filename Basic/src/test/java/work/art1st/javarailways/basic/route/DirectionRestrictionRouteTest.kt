package work.art1st.javarailways.basic.route

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.BasicWorldData
import work.art1st.javarailways.basic.DirectionRestrictionResolver
import work.art1st.javarailways.basic.WayPoint
import work.art1st.javarailways.basic.pathFinding.TestBasicWorldDataRegistry
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.createTestWorld

class DirectionRestrictionRouteTest {
    @Test
    fun `arc A to clicked B determines allowed direction`() {
        assertEquals(ViewDirection.AB, DirectionRestrictionResolver.fromArcPoints(10.0, 20.0))
        assertEquals(ViewDirection.BA, DirectionRestrictionResolver.fromArcPoints(20.0, 10.0))
        assertEquals(null, DirectionRestrictionResolver.fromArcPoints(10.0, 10.1))
    }

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
        rail.metadata = rail.metadata.copy(allowedDirection = ViewDirection.AB)

        assertTrue(RoutePlanner(world).buildRoute(pointA, pointB) is RouteBuildResult.Success)
        assertTrue(RoutePlanner(world).buildRoute(pointB, pointA) is RouteBuildResult.Failure)

        rail.metadata = rail.metadata.copy(allowedDirection = null)
        assertTrue(RoutePlanner(world).buildRoute(pointB, pointA) is RouteBuildResult.Success)
    }
}
