package work.art1st.javarailways.basic.route

import work.art1st.javarailways.core.physics.createRailForTest

import work.art1st.javarailways.basic.pathFinding.TestBasicWorldDataRegistry
import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.train.createTestWorld as createCoreTestWorld

class PlannedRouteCompilerTest {
    private fun createTestWorld() = createCoreTestWorld().also { TestBasicWorldDataRegistry.getOrCreate(it) }

    @Test
    fun `allocation trigger includes braking setup reaction and margin distances`() {
        val timing = RouteAllocationTiming(
            switchSetupSeconds = 0.2,
            reactionTimeSeconds = 0.1,
            safetyMarginMeters = 10.0
        )

        assertEquals(216.0, timing.triggerDistanceMeters(20.0, 1.0), 1.0e-9)
    }

    @Test
    fun `validates selected turnout branch without synthesizing signal sections`() {
        val world = createTestWorld()
        val start = rail(world, 0.0, 10.0)
        val selected = rail(world, 10.0, 20.0)
        val otherBranch = world.createRailForTest(
            polylineCurve(
                Vector3d(10.0, 0.0, 0.0),
                Vector3d(15.0, 0.0, 5.0),
                Vector3d(20.0, 0.0, 0.0)
            )
        )
        val goal = rail(world, 20.0, 30.0)
        CoreRail.connect(start, selected)
        CoreRail.connect(start, otherBranch)
        CoreRail.connect(selected, goal)
        val expanded = ExpandedRoute(
            "A",
            "B",
            listOf(
                RouteSegment(RailViewRef.from(start.viewAB), 5.0, 10.0),
                RouteSegment(RailViewRef.from(selected.viewAB), 0.0, 10.0),
                RouteSegment(RailViewRef.from(goal.viewAB), 0.0, 5.0)
            )
        )

        val result = PlannedRouteCompiler(world) {
            it.viewDirection == ViewDirection.AB
        }.compile("service-1/A-B", 3L, expanded)

        assertTrue(result is PlannedRouteCompilationResult.Success)
        val planned = (result as PlannedRouteCompilationResult.Success).route
        assertEquals(3L, planned.revision)
        assertEquals(expanded, planned.expandedRoute)
    }

    @Test
    fun `rejects expanded route whose stable rail views are not connected`() {
        val world = createTestWorld()
        val first = rail(world, 0.0, 10.0)
        val second = rail(world, 10.0, 20.0)
        val expanded = ExpandedRoute(
            "A",
            "B",
            listOf(
                RouteSegment(RailViewRef.from(first.viewAB), 0.0, 10.0),
                RouteSegment(RailViewRef.from(second.viewAB), 0.0, 5.0)
            )
        )

        val result = PlannedRouteCompiler(world).compile("route", 1L, expanded)

        assertTrue(result is PlannedRouteCompilationResult.Failure)
        assertTrue((result as PlannedRouteCompilationResult.Failure).reason.contains("not connected"))
    }

    @Test
    fun `planned route values defensively copy their collections`() {
        val world = createTestWorld()
        val rail = rail(world, 0.0, 10.0)
        val sourceSegments = mutableListOf(
            RouteSegment(RailViewRef.from(rail.viewAB), 0.0, 10.0)
        )
        val expanded = ExpandedRoute("A", "B", sourceSegments)
        sourceSegments.clear()
        val planned = PlannedRoute("route", 1L, expanded)

        assertEquals(1, expanded.segments.size)
        assertEquals(1, planned.expandedRoute.segments.size)
    }

    private fun rail(world: CoreWorld, fromX: Double, toX: Double): CoreRail =
        world.createRailForTest(straightCurve(Vector3d(fromX, 0.0, 0.0), Vector3d(toX, 0.0, 0.0)))

    private fun straightCurve(from: Vector3d, to: Vector3d): PrimeCurve =
        polylineCurve(from, to)

    private fun polylineCurve(vararg points: Vector3d): PrimeCurve {
        var arcLength = 0.0
        return PrimeCurve.fromPoints(points.mapIndexed { index, current ->
            if (index > 0) arcLength += points[index - 1].distance(current)
            val tangent = when (index) {
                0 -> Vector3d(points[1]).sub(current)
                points.lastIndex -> Vector3d(current).sub(points[index - 1])
                else -> Vector3d(points[index + 1]).sub(points[index - 1])
            }.normalize()
            val type = when (index) {
                0 -> Curve.SegmentType.LINE_START
                points.lastIndex -> Curve.SegmentType.LINE_END
                else -> Curve.SegmentType.DISCRETE
            }
            Curve.RawPoint(current, tangent, Vector3d(0.0, 1.0, 0.0), arcLength, type)
        })
    }
}
