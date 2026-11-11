package work.art1st.javarailways.basic.infrastructure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import java.util.UUID
import work.art1st.javarailways.basic.infrastructure.admission.CompiledRouteProgressPath
import work.art1st.javarailways.basic.infrastructure.admission.RouteProgressCursor
import work.art1st.javarailways.basic.infrastructure.admission.RouteProgressCursorInitialization
import work.art1st.javarailways.basic.infrastructure.admission.RouteProgressCursorState
import work.art1st.javarailways.basic.infrastructure.admission.RouteProgressCursorUpdate
import work.art1st.javarailways.basic.infrastructure.admission.RouteProgressOccurrence

class CompiledRouteProgressPathTest {
    private val revision = 7L

    @Test
    fun `initialization verifies the authoritative tail at route start`() {
        val rail = rail(1)
        val path = path(lineTopologyState(revision, rail), segment(rail, ViewDirection.AB, 2.0, 8.0))

        val initialized = assertInstanceOf(
            RouteProgressCursorInitialization.Initialized::class.java,
            path.initializeCursor(position(rail, ViewDirection.AB, 2.0))
        )
        assertEquals(RouteProgressCursorState(0, 0.0), initialized.cursor.state)
        assertEquals(
            RouteProgressCursorInitialization.PositionDoesNotMatchRouteStart,
            path.initializeCursor(position(rail, ViewDirection.AB, 5.0))
        )
        assertEquals(
            RouteProgressCursorInitialization.PositionDoesNotMatchRouteStart,
            path.initializeCursor(position(rail, ViewDirection.BA, 2.0))
        )
        assertEquals(
            RouteProgressCursorInitialization.PositionDoesNotMatchRouteStart,
            path.initializeCursor(position(rail, ViewDirection.AB, 9.0))
        )
    }

    @Test
    fun `initial tail tolerance never advances release progress`() {
        val rail = rail(1)
        val path = path(
            lineTopologyState(revision, rail),
            segment(rail, ViewDirection.AB, 0.0, 5.0),
            segment(rail, ViewDirection.AB, 5.0, 10.0)
        )

        assertEquals(
            RouteProgressCursorState(0, 0.0),
            initializedCursor(path, position(rail, ViewDirection.AB, Curve.TOLERANCE / 2.0)).state
        )
        assertEquals(
            RouteProgressCursorInitialization.PositionDoesNotMatchRouteStart,
            path.initializeCursor(position(rail, ViewDirection.AB, Curve.TOLERANCE * 2.0))
        )
    }

    @Test
    fun `AB and BA positions use canonical rail coordinates and tolerance`() {
        val abRail = rail(1)
        val baRail = rail(2)
        val topology = topologyState(revision, listOf(abRail, baRail), emptyList())
        val ab = path(topology, segment(abRail, ViewDirection.AB, 2.0, 8.0))
        val ba = path(topology, segment(baRail, ViewDirection.BA, 2.0, 8.0))

        assertProgress(0.0, ab, position(abRail, ViewDirection.AB, 2.0), position(abRail, ViewDirection.AB, 2.0 - Curve.TOLERANCE / 2.0))
        assertProgress(3.0, ab, position(abRail, ViewDirection.AB, 2.0), position(abRail, ViewDirection.AB, 5.0))
        assertProgress(6.0, ab, position(abRail, ViewDirection.AB, 2.0), position(abRail, ViewDirection.AB, 8.0 + Curve.TOLERANCE / 2.0))
        assertProgress(0.0, ba, position(baRail, ViewDirection.BA, 8.0), position(baRail, ViewDirection.BA, 8.0 + Curve.TOLERANCE / 2.0))
        assertProgress(3.0, ba, position(baRail, ViewDirection.BA, 8.0), position(baRail, ViewDirection.BA, 5.0))
        assertProgress(6.0, ba, position(baRail, ViewDirection.BA, 8.0), position(baRail, ViewDirection.BA, 2.0 - Curve.TOLERANCE / 2.0))
    }

    @Test
    fun `shared adjacent and zero length boundary stays on the earlier occurrence`() {
        val rail = rail(1)
        val path = path(
            lineTopologyState(revision, rail),
            segment(rail, ViewDirection.AB, 0.0, 5.0),
            segment(rail, ViewDirection.AB, 5.0, 5.0),
            segment(rail, ViewDirection.AB, 5.0, 10.0)
        )
        val cursor = initializedCursor(path, position(rail, ViewDirection.AB, 0.0))
        assertResolved(0, 4.0, cursor.update(position(rail, ViewDirection.AB, 4.0)))

        assertResolved(0, 5.0, cursor.update(position(rail, ViewDirection.AB, 5.0)))
        assertEquals(RouteProgressCursorState(0, 5.0), cursor.state)
    }

    @Test
    fun `shared BA boundary also stays on the earlier occurrence`() {
        val rail = rail(1)
        val path = path(
            lineTopologyState(revision, rail),
            segment(rail, ViewDirection.BA, 0.0, 5.0),
            segment(rail, ViewDirection.BA, 5.0, 10.0)
        )
        val cursor = initializedCursor(path, position(rail, ViewDirection.BA, 10.0))
        assertResolved(0, 2.0, cursor.update(position(rail, ViewDirection.BA, 8.0)))

        assertResolved(0, 5.0, cursor.update(position(rail, ViewDirection.BA, 5.0)))
    }

    @Test
    fun `repeated rail does not jump while the current occurrence still matches`() {
        val first = rail(1)
        val second = rail(2)
        val third = rail(3)
        val path = repeatedRailPath(first, second, third)
        val cursor = initializedCursor(path, position(first, ViewDirection.AB, 0.0))
        assertResolved(0, 2.0, cursor.update(position(first, ViewDirection.AB, 2.0)))

        assertResolved(0, 3.0, cursor.update(position(first, ViewDirection.AB, 3.0)))
    }

    @Test
    fun `repeated rail advances only after intervening occurrences establish forward progress`() {
        val first = rail(1)
        val second = rail(2)
        val third = rail(3)
        val path = repeatedRailPath(first, second, third)
        val cursor = initializedCursor(path, position(first, ViewDirection.AB, 0.0))
        assertResolved(0, 8.0, cursor.update(position(first, ViewDirection.AB, 8.0)))

        assertResolved(1, 14.0, cursor.update(position(second, ViewDirection.AB, 4.0)))
        assertResolved(2, 27.0, cursor.update(position(third, ViewDirection.AB, 7.0)))
        assertResolved(3, 33.0, cursor.update(position(first, ViewDirection.AB, 3.0)))
    }

    @Test
    fun `initialization cannot jump to a later repeated rail`() {
        val first = rail(1)
        val second = rail(2)
        val third = rail(3)
        val path = repeatedRailPath(first, second, third)

        assertEquals(
            RouteProgressCursorInitialization.PositionDoesNotMatchRouteStart,
            path.initializeCursor(position(first, ViewDirection.AB, 3.0))
        )
    }

    @Test
    fun `cursor advances to the earliest forward match`() {
        val first = rail(1)
        val second = rail(2)
        val third = rail(3)
        val path = path(
            lineTopologyState(revision, first, second, third),
            segment(first, ViewDirection.AB),
            segment(second, ViewDirection.AB),
            segment(third, ViewDirection.AB)
        )
        val cursor = initializedCursor(path, position(first, ViewDirection.AB, 0.0))
        assertResolved(0, 8.0, cursor.update(position(first, ViewDirection.AB, 8.0)))

        assertResolved(1, 14.0, cursor.update(position(second, ViewDirection.AB, 4.0)))
        assertResolved(2, 27.0, cursor.update(position(third, ViewDirection.AB, 7.0)))
    }

    @Test
    fun `one update can cross multiple occurrences`() {
        val first = rail(1)
        val second = rail(2)
        val third = rail(3)
        val path = path(
            lineTopologyState(revision, first, second, third),
            segment(first, ViewDirection.AB),
            segment(second, ViewDirection.AB),
            segment(third, ViewDirection.AB)
        )
        val cursor = initializedCursor(path, position(first, ViewDirection.AB, 0.0))
        assertResolved(0, 8.0, cursor.update(position(first, ViewDirection.AB, 8.0)))

        assertResolved(2, 26.0, cursor.update(position(third, ViewDirection.AB, 6.0)))
    }

    @Test
    fun `projection does not advance accepted state until committed`() {
        val rail = rail(1)
        val path = path(lineTopologyState(revision, rail), segment(rail, ViewDirection.AB))
        val cursor = initializedCursor(path, position(rail, ViewDirection.AB, 0.0))

        val projected = assertInstanceOf(
            RouteProgressCursorUpdate.Resolved::class.java,
            cursor.project(position(rail, ViewDirection.AB, 6.0))
        )

        assertEquals(RouteProgressCursorState(0, 0.0), cursor.state)
        cursor.commit(projected)
        assertEquals(RouteProgressCursorState(0, 6.0), cursor.state)
    }

    @Test
    fun `off-path update preserves state and latches fail closed`() {
        val first = rail(1)
        val second = rail(2)
        val path = path(
            lineTopologyState(revision, first, second),
            segment(first, ViewDirection.AB),
            segment(second, ViewDirection.AB)
        )
        val cursor = initializedCursor(path, position(first, ViewDirection.AB, 0.0))
        assertResolved(0, 4.0, cursor.update(position(first, ViewDirection.AB, 4.0)))

        val failed = cursor.update(position(rail(99), ViewDirection.AB, 1.0))
        assertNotOnForwardPath(0, 4.0, failed)
        assertEquals(RouteProgressCursorState(0, 4.0), cursor.state)
        assertSame(failed, cursor.update(position(second, ViewDirection.AB, 5.0)))
    }

    @Test
    fun `cursor never scans backward after a forward transition`() {
        val first = rail(1)
        val second = rail(2)
        val third = rail(3)
        val path = path(
            lineTopologyState(revision, first, second, third),
            segment(first, ViewDirection.AB),
            segment(second, ViewDirection.AB),
            segment(third, ViewDirection.AB)
        )
        val cursor = initializedCursor(path, position(first, ViewDirection.AB, 0.0))
        assertResolved(0, 8.0, cursor.update(position(first, ViewDirection.AB, 8.0)))
        assertResolved(2, 22.0, cursor.update(position(third, ViewDirection.AB, 2.0)))

        assertNotOnForwardPath(2, 22.0, cursor.update(position(second, ViewDirection.AB, 9.0)))
        assertEquals(RouteProgressCursorState(2, 22.0), cursor.state)
    }

    @Test
    fun `same occurrence physical regression preserves state and latches fail closed`() {
        val rail = rail(1)
        val path = path(
            lineTopologyState(revision, rail),
            segment(rail, ViewDirection.AB)
        )
        val cursor = initializedCursor(path, position(rail, ViewDirection.AB, 0.0))
        assertResolved(0, 6.0, cursor.update(position(rail, ViewDirection.AB, 6.0)))

        assertNonMonotonic(0, 6.0, 5.0, cursor.update(position(rail, ViewDirection.AB, 5.0)))
        assertEquals(RouteProgressCursorState(0, 6.0), cursor.state)
        assertNonMonotonic(0, 6.0, 5.0, cursor.update(position(rail, ViewDirection.AB, 8.0)))
    }

    @Test
    fun `forward occurrence with lower route coordinate cannot advance cursor`() {
        val first = rail(1)
        val second = rail(2)
        val cursor = RouteProgressCursor(
            occurrences = listOf(
                RouteProgressOccurrence(first, ViewDirection.AB, 0.0, 10.0, 0.0, 10.0),
                RouteProgressOccurrence(second, ViewDirection.AB, 0.0, 10.0, 5.0, 10.0)
            ),
            occurrenceIndex = 0,
            routeDistanceMeters = 8.0
        )

        assertNonMonotonic(0, 8.0, 6.0, cursor.update(position(second, ViewDirection.AB, 1.0)))
        assertEquals(RouteProgressCursorState(0, 8.0), cursor.state)
    }

    private fun repeatedRailPath(
        first: UUID,
        second: UUID,
        third: UUID
    ): CompiledRouteProgressPath {
        val topology = topologyState(
            revision,
            listOf(first, second, third),
            listOf(
                endpoint(first, RailEndpoint.B) to endpoint(second, RailEndpoint.A),
                endpoint(second, RailEndpoint.B) to endpoint(third, RailEndpoint.A),
                endpoint(third, RailEndpoint.B) to endpoint(first, RailEndpoint.A)
            )
        )
        return path(
            topology,
            segment(first, ViewDirection.AB),
            segment(second, ViewDirection.AB),
            segment(third, ViewDirection.AB),
            segment(first, ViewDirection.AB, 0.0, 5.0)
        )
    }

    private fun path(
        topology: TopologyState,
        vararg segments: RouteSegmentSnapshot
    ): CompiledRouteProgressPath {
        val projection = assertInstanceOf(
            TopologyRouteProjectionResult.Projected::class.java,
            TopologyRouteProjector.project(
                RouteSnapshot("test:route/progress", 1L, revision, segments.asList()),
                topology
            )
        )
        return CompiledRouteProgressPath.compile(projection.route)
    }

    private fun initializedCursor(
        path: CompiledRouteProgressPath,
        position: RailPositionSnapshot
    ): RouteProgressCursor = assertInstanceOf(
        RouteProgressCursorInitialization.Initialized::class.java,
        path.initializeCursor(position)
    ).cursor

    private fun assertProgress(
        expectedDistance: Double,
        path: CompiledRouteProgressPath,
        startPosition: RailPositionSnapshot,
        position: RailPositionSnapshot
    ) {
        val cursor = initializedCursor(path, startPosition)
        if (expectedDistance > 0.0) {
            assertResolved(0, expectedDistance, cursor.update(position))
        }
        assertEquals(RouteProgressCursorState(0, expectedDistance), cursor.state)
    }

    private fun segment(
        railId: UUID,
        facing: ViewDirection,
        startMeters: Double = 0.0,
        endMeters: Double = 10.0
    ) = RouteSegmentSnapshot(railId, facing, startMeters, endMeters)

    private fun position(railId: UUID, facing: ViewDirection, canonicalMeters: Double) =
        RailPositionSnapshot(railId, facing, canonicalMeters)

    private fun rail(value: Long): UUID = UUID(0L, value)

    private fun assertResolved(
        expectedOccurrenceIndex: Int,
        expectedDistance: Double,
        actual: RouteProgressCursorUpdate
    ) {
        val resolved = assertInstanceOf(RouteProgressCursorUpdate.Resolved::class.java, actual)
        assertEquals(RouteProgressCursorState(expectedOccurrenceIndex, expectedDistance), resolved.state)
    }

    private fun assertNotOnForwardPath(
        expectedOccurrenceIndex: Int,
        expectedDistance: Double,
        actual: RouteProgressCursorUpdate
    ) {
        val rejected = assertInstanceOf(
            RouteProgressCursorUpdate.PositionNotOnCurrentOrForwardPath::class.java,
            actual
        )
        assertEquals(
            RouteProgressCursorState(expectedOccurrenceIndex, expectedDistance),
            rejected.currentState
        )
    }

    private fun assertNonMonotonic(
        expectedOccurrenceIndex: Int,
        expectedCurrentDistance: Double,
        expectedAttemptedDistance: Double,
        actual: RouteProgressCursorUpdate
    ) {
        val rejected = assertInstanceOf(
            RouteProgressCursorUpdate.NonMonotonicProgress::class.java,
            actual
        )
        assertEquals(
            RouteProgressCursorState(expectedOccurrenceIndex, expectedCurrentDistance),
            rejected.currentState
        )
        assertEquals(expectedAttemptedDistance, rejected.attemptedRouteDistanceMeters)
    }
}
