package work.art1st.javarailways.basic.route

import work.art1st.javarailways.core.physics.createRailForTest

import work.art1st.javarailways.basic.rail.TurnoutEndpointMetadata
import work.art1st.javarailways.basic.pathFinding.TestBasicWorldDataRegistry
import work.art1st.javarailways.basic.rail.infrastructureMetadata as metadata
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
    fun `compiles selected turnout branch into contiguous atomic sections`() {
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
        listOf(start, selected, otherBranch, goal).forEach(::allowAbOnly)
        val expanded = ExpandedRoute(
            "A",
            "B",
            listOf(
                RouteSegment(RailViewRef.from(start.viewAB), 5.0, 10.0),
                RouteSegment(RailViewRef.from(selected.viewAB), 0.0, 10.0),
                RouteSegment(RailViewRef.from(goal.viewAB), 0.0, 5.0)
            )
        )

        val result = PlannedRouteCompiler(world).compile(
            routeId = "service-1/A-B",
            revision = 3L,
            route = expanded,
            protectionPointDistancesMeters = listOf(12.0)
        )

        assertTrue(result is PlannedRouteCompilationResult.Success)
        val planned = (result as PlannedRouteCompilationResult.Success).route
        assertEquals(3L, planned.revision)
        assertEquals(3, planned.sections.size)
        assertEquals(0.0, planned.sections[0].entryProtectionDistanceMeters)
        assertEquals(5.0, planned.sections[0].releaseDistanceMeters)
        assertEquals(5.0, planned.sections[1].entryProtectionDistanceMeters)
        assertEquals(12.0, planned.sections[1].releaseDistanceMeters)
        assertEquals(12.0, planned.sections[2].entryProtectionDistanceMeters)
        assertEquals(20.0, planned.sections[2].releaseDistanceMeters)
        val requirement = planned.sections[0].turnoutRequirements.single()
        assertEquals(RailViewRef.from(start.viewAB), requirement.from)
        assertEquals(RailViewRef.from(selected.viewAB), requirement.selectedNext)
        assertEquals(5.0, requirement.routeDistanceMeters)
        assertTrue(planned.sections[1].turnoutRequirements.isEmpty())
        assertTrue(planned.sections[2].turnoutRequirements.isEmpty())
    }

    @Test
    fun `trailing movement uses the same canonical turnout resource as facing movement`() {
        val world = createTestWorld()
        val stem = rail(world, 0.0, 10.0)
        val selected = rail(world, 10.0, 20.0)
        val other = world.createRailForTest(
            straightCurve(Vector3d(10.0, 0.0, 0.0), Vector3d(20.0, 0.0, 4.0))
        )
        CoreRail.connect(stem, selected)
        CoreRail.connect(stem, other)
        val route = ExpandedRoute(
            "branch",
            "stem",
            listOf(
                RouteSegment(RailViewRef.from(selected.viewBA), 0.0, 10.0),
                RouteSegment(RailViewRef.from(stem.viewBA), 0.0, 5.0)
            )
        )

        val result = PlannedRouteCompiler(world).compile("trailing", 1L, route)

        assertTrue(result is PlannedRouteCompilationResult.Success)
        val requirement = (result as PlannedRouteCompilationResult.Success)
            .route.sections.first().turnoutRequirements.single()
        assertEquals(RailViewRef.from(stem.viewAB), requirement.from)
        assertEquals(RailViewRef.from(selected.viewAB), requirement.selectedNext)
        assertEquals(10.0, requirement.routeDistanceMeters)
    }

    @Test
    fun `splits consecutive turnout selections using world turnout defaults`() {
        val world = createTestWorld()
        val first = rail(world, 0.0, 10.0)
        val second = rail(world, 10.0, 20.0)
        val third = rail(world, 20.0, 30.0)
        val firstBranch = world.createRailForTest(
            straightCurve(Vector3d(10.0, 0.0, 0.0), Vector3d(15.0, 0.0, 5.0))
        )
        val secondBranch = world.createRailForTest(
            straightCurve(Vector3d(20.0, 0.0, 0.0), Vector3d(25.0, 0.0, 5.0))
        )
        CoreRail.connect(first, second)
        CoreRail.connect(first, firstBranch)
        first.metadata = first.metadata.copy(
            turnoutAtB = TurnoutEndpointMetadata(30.0, 7.0)
        )
        CoreRail.connect(second, third)
        CoreRail.connect(second, secondBranch)
        listOf(first, second, third, firstBranch, secondBranch).forEach(::allowAbOnly)
        val expanded = ExpandedRoute(
            "A",
            "B",
            listOf(
                RouteSegment(RailViewRef.from(first.viewAB), 0.0, 10.0),
                RouteSegment(RailViewRef.from(second.viewAB), 0.0, 10.0),
                RouteSegment(RailViewRef.from(third.viewAB), 0.0, 5.0)
            )
        )

        val result = PlannedRouteCompiler(world).compile("route", 1L, expanded)

        assertTrue(result is PlannedRouteCompilationResult.Success)
        val sections = (result as PlannedRouteCompilationResult.Success).route.sections
        assertEquals(3, sections.size)
        assertEquals(listOf(10.0), sections[0].turnoutRequirements.map { it.routeDistanceMeters })
        assertEquals(TurnoutSafetyDefaults.DEFAULT_PROTECTION_DISTANCE_METERS,
            sections[0].turnoutRequirements.single().protectionDistanceMeters)
        assertEquals(TurnoutSafetyDefaults.DEFAULT_LOCKING_DISTANCE_METERS,
            sections[0].turnoutRequirements.single().lockingDistanceMeters)
        assertEquals(listOf(20.0), sections[1].turnoutRequirements.map { it.routeDistanceMeters })
        assertTrue(sections[2].turnoutRequirements.isEmpty())
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
        val sourceRequirements = mutableListOf<TurnoutRequirement>()
        val section = RouteSection(0, 0.0, 10.0, sourceRequirements)
        sourceRequirements += TurnoutRequirement(
            RailViewRef.from(rail.viewAB),
            RailViewRef.from(rail.viewBA),
            5.0
        )
        val sourceSections = mutableListOf(section)
        val planned = PlannedRoute("route", 1L, expanded, sourceSections)
        sourceSections.clear()

        assertEquals(1, expanded.segments.size)
        assertTrue(section.turnoutRequirements.isEmpty())
        assertEquals(1, planned.sections.size)
    }

    private fun rail(world: CoreWorld, fromX: Double, toX: Double): CoreRail =
        world.createRailForTest(straightCurve(Vector3d(fromX, 0.0, 0.0), Vector3d(toX, 0.0, 0.0)))

    private fun allowAbOnly(rail: CoreRail) {
        rail.metadata = rail.metadata.copy(allowedDirection = ViewDirection.AB)
    }

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
