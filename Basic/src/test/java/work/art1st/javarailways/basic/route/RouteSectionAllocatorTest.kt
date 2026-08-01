package work.art1st.javarailways.basic.route

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.train.createTestWorld
import work.art1st.javarailways.core.physics.train.createTrainWithConfig

class RouteSectionAllocatorTest {
    @Test
    fun `conflict leaves every turnout in the rejected section unchanged`() {
        val world = createTestWorld()
        val free = junction(world, 0.0)
        val occupied = junction(world, 30.0)
        free.from.viewAB.selectNextRail(free.first.viewAB)
        occupied.from.viewAB.selectNextRail(occupied.first.viewAB)
        val allocator = RouteSectionAllocator(world)
        val firstOwner = RouteSectionOwner("service-1", "T1")
        val secondOwner = RouteSectionOwner("service-2", "T2")
        val occupiedRoute = plannedRoute(
            "occupied",
            listOf(requirement(occupied, occupied.first, 8.0))
        )
        val rejectedRoute = plannedRoute(
            "rejected",
            listOf(
                requirement(free, free.second, 3.0),
                requirement(occupied, occupied.second, 8.0)
            )
        )
        val occupiedResult = allocator.allocate(firstOwner, occupiedRoute, 0)
        assertTrue(occupiedResult is RouteSectionAllocationResult.Confirmed)

        val rejected = allocator.allocate(secondOwner, rejectedRoute, 0)

        assertTrue(rejected is RouteSectionAllocationResult.Conflict)
        assertSame(free.first.viewAB, free.from.viewAB.nextActiveRail)
        assertSame(occupied.first.viewAB, occupied.from.viewAB.nextActiveRail)
        assertEquals(1, allocator.confirmedWindows().size)
    }

    @Test
    fun `release after tail clearance can be represented without resetting turnout state`() {
        val world = createTestWorld()
        val junction = junction(world, 0.0)
        val allocator = RouteSectionAllocator(world)
        val firstOwner = RouteSectionOwner("service-1", "T1")
        val secondOwner = RouteSectionOwner("service-2", "T2")
        val firstRoute = plannedRoute("first", listOf(requirement(junction, junction.first, 5.0)))
        val secondRoute = plannedRoute("second", listOf(requirement(junction, junction.second, 5.0)))
        val confirmed = allocator.allocate(firstOwner, firstRoute, 0)
            as RouteSectionAllocationResult.Confirmed

        assertTrue(allocator.release(confirmed.window.key))
        assertFalse(allocator.release(confirmed.window.key))
        assertSame(junction.first.viewAB, junction.from.viewAB.nextActiveRail)

        val second = allocator.allocate(secondOwner, secondRoute, 0)

        assertTrue(second is RouteSectionAllocationResult.Confirmed)
        assertSame(junction.second.viewAB, junction.from.viewAB.nextActiveRail)
    }

    @Test
    fun `turnout cannot change while a train occupies its locking interval`() {
        val world = createTestWorld()
        val junction = junction(world, 0.0)
        val train = createTrainWithConfig("occupant", junction.from, world, listOf(10.0))
        train.setLocation(train.head.location.forward(9.0)!!)
        train.commit()
        junction.from.viewAB.selectNextRail(junction.first.viewAB)
        val allocator = RouteSectionAllocator(world)
        val route = plannedRoute(
            "locked",
            listOf(requirement(junction, junction.second, 5.0))
        )

        val result = allocator.allocate(RouteSectionOwner("service", train.id), route, 0)

        assertTrue(result is RouteSectionAllocationResult.PhysicallyLocked)
        assertSame(junction.first.viewAB, junction.from.viewAB.nextActiveRail)
    }

    @Test
    fun `same allocation key is idempotent`() {
        val world = createTestWorld()
        val junction = junction(world, 0.0)
        val allocator = RouteSectionAllocator(world)
        val owner = RouteSectionOwner("service-1", "T1")
        val route = plannedRoute("route", listOf(requirement(junction, junction.second, 5.0)))

        val first = allocator.allocate(owner, route, 0) as RouteSectionAllocationResult.Confirmed
        val second = allocator.allocate(owner, route, 0) as RouteSectionAllocationResult.Confirmed

        assertEquals(first.window, second.window)
        assertEquals(1, allocator.confirmedWindows().size)
    }

    private fun plannedRoute(
        routeId: String,
        requirements: List<TurnoutRequirement>
    ): PlannedRoute {
        val segment = RouteSegment(requirements.first().from, 0.0, 10.0)
        val expanded = ExpandedRoute("A", "B", listOf(segment))
        return PlannedRoute(
            routeId,
            1L,
            expanded,
            listOf(RouteSection(0, 0.0, 10.0, requirements))
        )
    }

    private fun requirement(
        junction: Junction,
        selected: CoreRail,
        distance: Double
    ): TurnoutRequirement = TurnoutRequirement(
        RailViewRef.from(junction.from.viewAB),
        RailViewRef.from(selected.viewAB),
        distance
    )

    private fun junction(world: CoreWorld, startX: Double): Junction {
        val from = rail(world, Vector3d(startX, 0.0, 0.0), Vector3d(startX + 30.0, 0.0, 0.0))
        val first = rail(world, Vector3d(startX + 30.0, 0.0, 0.0), Vector3d(startX + 50.0, 0.0, 0.0))
        val second = rail(
            world,
            Vector3d(startX + 30.0, 0.0, 0.0),
            Vector3d(startX + 50.0, 0.0, 5.0)
        )
        CoreRail.connect(from, first)
        CoreRail.connect(from, second)
        return Junction(from, first, second)
    }

    private fun rail(world: CoreWorld, from: Vector3d, to: Vector3d): CoreRail = CoreRail(
        world,
        straightCurve(from, to),
        mutableListOf(),
        mutableListOf()
    )

    private fun straightCurve(from: Vector3d, to: Vector3d): PrimeCurve {
        val tangent = Vector3d(to).sub(from).normalize()
        val normal = Vector3d(0.0, 1.0, 0.0)
        val length = from.distance(to)
        return PrimeCurve.fromPoints(listOf(
            Curve.RawPoint(from, tangent, normal, 0.0, Curve.SegmentType.LINE_START),
            Curve.RawPoint(to, tangent, normal, length, Curve.SegmentType.LINE_END)
        ))
    }

    private data class Junction(
        val from: CoreRail,
        val first: CoreRail,
        val second: CoreRail
    )
}
