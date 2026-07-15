package work.art1st.mineRails.basic.route

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.mineRails.basic.BasicWorldData
import work.art1st.mineRails.basic.WayPoint
import work.art1st.mineRails.basic.pathFinding.TestBasicWorldDataRegistry
import work.art1st.mineRails.core.api.physics.curve.Curve
import work.art1st.mineRails.core.api.physics.curve.PrimeCurve
import work.art1st.mineRails.core.api.physics.rail.ViewDirection
import work.art1st.mineRails.core.physics.CoreWorld
import work.art1st.mineRails.core.physics.rail.CoreRail
import work.art1st.mineRails.core.physics.train.createTestWorld

class RoutePlannerWeightedTest {
    @Test
    fun `chooses shorter distance even when it uses more rail views`() {
        val world = createTestWorld()
        val data = BasicWorldData()
        TestBasicWorldDataRegistry.register(world, data)
        val start = rail(world, point(0.0, 0.0), point(10.0, 0.0))
        val longBranch = rail(
            world,
            point(10.0, 0.0),
            point(20.0, 20.0),
            point(30.0, 0.0)
        )
        val shortA = rail(world, point(10.0, 0.0), point(20.0, 0.0))
        val shortB = rail(world, point(20.0, 0.0), point(30.0, 0.0))
        val goal = rail(world, point(30.0, 0.0), point(40.0, 0.0))
        CoreRail.connect(start, longBranch)
        CoreRail.connect(start, shortA)
        CoreRail.connect(longBranch, goal)
        CoreRail.connect(shortA, shortB)
        CoreRail.connect(shortB, goal)
        listOf(start, longBranch, shortA, shortB, goal).forEach(::allowAbOnly)
        val from = WayPoint("A", "A", start.id, 5.0)
        val to = WayPoint("B", "B", goal.id, 5.0)

        val result = RoutePlanner(world).buildRoute(from, to)

        assertTrue(result is RouteBuildResult.Success)
        val route = (result as RouteBuildResult.Success).route
        assertEquals(
            listOf(start.id, shortA.id, shortB.id, goal.id),
            route.segments.map { it.railViewRef.railId }
        )
        assertEquals(30.0, route.totalLength, 1.0e-9)
    }

    @Test
    fun `reports unreachable waypoints on disconnected rails`() {
        val world = createTestWorld()
        val data = BasicWorldData()
        TestBasicWorldDataRegistry.register(world, data)
        val first = rail(world, point(0.0, 0.0), point(10.0, 0.0))
        val second = rail(world, point(20.0, 0.0), point(30.0, 0.0))

        val result = RoutePlanner(world).buildRoute(
            WayPoint("A", "A", first.id, 5.0),
            WayPoint("B", "B", second.id, 5.0)
        )

        assertTrue(result is RouteBuildResult.Failure)
    }

    @Test
    fun `can route around a loop to a point behind on the same rail view`() {
        val world = createTestWorld()
        val data = BasicWorldData()
        TestBasicWorldDataRegistry.register(world, data)
        val first = rail(world, point(0.0, 0.0), point(10.0, 0.0))
        val second = rail(world, point(10.0, 0.0), point(5.0, 8.0))
        val third = rail(world, point(5.0, 8.0), point(0.0, 0.0))
        CoreRail.connect(first, second)
        CoreRail.connect(second, third)
        CoreRail.connect(third, first)
        listOf(first, second, third).forEach(::allowAbOnly)
        val from = WayPoint("A", "A", first.id, 8.0)
        val to = WayPoint("B", "B", first.id, 2.0)

        val result = RoutePlanner(world).buildRoute(from, to)

        assertTrue(result is RouteBuildResult.Success)
        val route = (result as RouteBuildResult.Success).route
        assertEquals(
            listOf(first.id, second.id, third.id, first.id),
            route.segments.map { it.railViewRef.railId }
        )
        assertEquals(2.0, route.segments.first().length, 1.0e-9)
        assertEquals(2.0, route.segments.last().length, 1.0e-9)
    }

    private fun rail(world: CoreWorld, vararg points: Vector3d): CoreRail =
        CoreRail(world, curve(*points), mutableListOf(), mutableListOf())

    private fun allowAbOnly(rail: CoreRail) {
        rail.metadata = rail.metadata.copy(allowedDirection = ViewDirection.AB)
    }

    private fun curve(vararg points: Vector3d): PrimeCurve {
        require(points.size >= 2)
        var arcLength = 0.0
        return PrimeCurve.fromPoints(points.mapIndexed { index, current ->
            if (index > 0) arcLength += points[index - 1].distance(current)
            val tangent = when (index) {
                0 -> Vector3d(points[1]).sub(current)
                points.lastIndex -> Vector3d(current).sub(points[index - 1])
                else -> Vector3d(points[index + 1]).sub(points[index - 1])
            }.normalize()
            val segmentType = when (index) {
                0 -> Curve.SegmentType.LINE_START
                points.lastIndex -> Curve.SegmentType.LINE_END
                else -> Curve.SegmentType.DISCRETE
            }
            Curve.RawPoint(current, tangent, Vector3d(0.0, 1.0, 0.0), arcLength, segmentType)
        })
    }

    private fun point(x: Double, z: Double): Vector3d = Vector3d(x, 0.0, z)
}
