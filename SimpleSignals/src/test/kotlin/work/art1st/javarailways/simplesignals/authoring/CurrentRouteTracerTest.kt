package work.art1st.javarailways.simplesignals.authoring

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.*
import work.art1st.javarailways.simplesignals.region.*
import java.util.UUID

class CurrentRouteTracerTest {
    private val a = UUID(0, 1)
    private val b = UUID(0, 2)
    private val c = UUID(0, 3)
    private val stem = endpoint(a, RailEndpoint.B)
    private val straight = endpoint(b, RailEndpoint.A)
    private val branch = endpoint(c, RailEndpoint.A)
    private fun cut(id: String, rail: UUID, ab: Double, facing: ViewDirection) =
        ProtectionBoundaryDefinition("test:$id", RailPositionSnapshot(rail, facing, ab))
    private val entry = cut("entry", a, 2.0, ViewDirection.AB)
    private val exit = cut("exit", b, 8.0, ViewDirection.BA)
    private val otherExit = cut("other", c, 8.0, ViewDirection.BA)
    private val region = ProtectionRegionDefinition("test:region", 0, listOf(entry, exit, otherExit))
    private val topology = topologyState(1, listOf(a, b, c), listOf(stem to straight, stem to branch))
    private fun active(selected: RailEndpointRef? = straight, revision: Long = 1) =
        ActiveRailContinuationsSnapshot.create(1, revision, if (selected == null) emptyMap() else mapOf(stem to selected))
    private fun trace(active: ActiveRailContinuationsSnapshot? = active()) =
        CurrentRouteTracer.trace(topology, active, region, entry.boundaryId, exit.boundaryId)

    @Test fun `current straight branch reaches requested exit and current side branch preserves wrong actual exit`() {
        val connected = trace()
        assertEquals(CurrentRouteEnd.REACHED_EXIT, connected.end)
        assertEquals(listOf(a, b), connected.traversals.map { it.railId })
        assertEquals(2.0, connected.traversals.first().travelStartAbMeters)
        assertEquals(8.0, connected.traversals.last().travelEndAbMeters)
        val wrong = trace(active(branch))
        assertEquals(CurrentRouteEnd.WRONG_EXIT, wrong.end)
        assertEquals(listOf(a, c), wrong.traversals.map { it.railId })
        assertEquals(otherExit.position.copy(facing = ViewDirection.AB), wrong.reached)
        assertEquals(otherExit.boundaryId, wrong.reachedBoundaryId)
        assertEquals(mapOf(stem to branch), wrong.watchedContinuations)
    }

    @Test fun `unselected and illegal continuations fail at actual junction and watch the missing entry`() {
        val missing = trace(active(null))
        assertEquals(CurrentRouteEnd.UNSELECTED, missing.end)
        assertTrue(missing.watchedContinuations.containsKey(stem))
        assertNull(missing.watchedContinuations[stem])
        assertEquals(10.0, missing.reached.arcLengthInAbMeters)
        assertEquals(1, missing.traversals.size)
        val illegal = trace(active(endpoint(c, RailEndpoint.B)))
        assertEquals(CurrentRouteEnd.ILLEGAL_CONTINUATION, illegal.end)
        assertEquals(missing.reached, illegal.reached)
    }

    @Test fun `same rail cuts stop at first forward boundary and reverse selects BA interval`() {
        val graph = lineTopologyState(1, a)
        val first = entry.copy(position = entry.position.copy(arcLengthInAbMeters = 1.0))
        val middle = cut("middle", a, 5.0, ViewDirection.BA)
        val last = cut("last", a, 9.0, ViewDirection.BA)
        val r = ProtectionRegionDefinition("test:region", 0, listOf(first, middle, last))
        val wrong = CurrentRouteTracer.trace(graph, ActiveRailContinuationsSnapshot.empty(1), r, first.boundaryId, last.boundaryId)
        assertEquals(CurrentRouteEnd.WRONG_EXIT, wrong.end)
        assertEquals(5.0, wrong.reached.arcLengthInAbMeters)
        val reverse = CurrentRouteTracer.trace(graph, ActiveRailContinuationsSnapshot.empty(1),
            ProtectionRegionDefinition("test:region", 0, listOf(first, last)), last.boundaryId, first.boundaryId)
        assertEquals(CurrentRouteEnd.REACHED_EXIT, reverse.end)
        assertEquals(ViewDirection.BA, reverse.traversals.single().facing)
        assertEquals(9.0, reverse.traversals.single().travelStartAbMeters)
        assertEquals(1.0, reverse.traversals.single().travelEndAbMeters)
    }

    @Test fun `mixed facing and defensive synthetic endpoint cut require no zero length invented traversal`() {
        val root = topologyState(1, listOf(a, b), listOf(stem to endpoint(b, RailEndpoint.B)))
        val out = cut("exit", b, 2.0, ViewDirection.AB)
        val result = CurrentRouteTracer.trace(root, ActiveRailContinuationsSnapshot.empty(1),
            ProtectionRegionDefinition("test:region", 0, listOf(entry, out)), entry.boundaryId, out.boundaryId)
        assertEquals(listOf(ViewDirection.AB, ViewDirection.BA), result.traversals.map { it.facing })
        assertEquals(CurrentRouteEnd.REACHED_EXIT, result.end)
        val joint = out.copy(position = out.position.copy(arcLengthInAbMeters = 10.0))
        val endpointResult = CurrentRouteTracer.trace(root, ActiveRailContinuationsSnapshot.empty(1),
            ProtectionRegionDefinition("test:region", 0, listOf(entry, joint)), entry.boundaryId, joint.boundaryId)
        assertEquals(CurrentRouteEnd.REACHED_EXIT, endpointResult.end)
        assertEquals(1, endpointResult.traversals.size)
        assertEquals(b, endpointResult.reached.railId)
    }

    @Test fun `dead end preserves prefix and diamond geometry never creates a continuation`() {
        val root = topologyState(1, listOf(a, b, c), listOf(stem to branch), listOf(AtGradeRailCrossing(
            RailCrossingPoint(a, 5.0), RailCrossingPoint(b, 5.0))))
        val result = CurrentRouteTracer.trace(root, ActiveRailContinuationsSnapshot.empty(1),
            ProtectionRegionDefinition("test:region", 0, listOf(entry, exit)), entry.boundaryId, exit.boundaryId)
        assertEquals(CurrentRouteEnd.DEAD_END, result.end)
        assertEquals(listOf(a, c), result.traversals.map { it.railId })
        assertEquals(c, result.reached.railId)
        assertEquals(10.0, result.reached.arcLengthInAbMeters)
        val ownLineExit = CurrentRouteTracer.trace(root, ActiveRailContinuationsSnapshot.empty(1), region,
            entry.boundaryId, exit.boundaryId)
        assertEquals(CurrentRouteEnd.WRONG_EXIT, ownLineExit.end)
        assertEquals(otherExit.boundaryId, ownLineExit.reachedBoundaryId)
        assertEquals(listOf(a, c), ownLineExit.traversals.map { it.railId })
    }

    @Test fun `selected loop terminates without exploring unselected exit branch`() {
        val d = UUID(0, 4)
        val root = topologyState(1, listOf(a, b, c, d), listOf(stem to straight,
            endpoint(b, RailEndpoint.B) to endpoint(c, RailEndpoint.A),
            endpoint(c, RailEndpoint.B) to endpoint(b, RailEndpoint.A), stem to endpoint(d, RailEndpoint.A)))
        val out = cut("exit", d, 8.0, ViewDirection.BA)
        val loop = CurrentRouteTracer.trace(root, ActiveRailContinuationsSnapshot.create(1, 1,
            mapOf(stem to straight, endpoint(c, RailEndpoint.B) to straight)),
            ProtectionRegionDefinition("test:region", 0, listOf(entry, out)), entry.boundaryId, out.boundaryId)
        assertEquals(CurrentRouteEnd.LOOP, loop.end)
        assertEquals(listOf(a, b, c), loop.traversals.map { it.railId })
        assertEquals(b, loop.reached.railId)
    }

    @Test fun `missing observation and incoherent graph wait while unavailable region never traces`() {
        assertTrue(trace(null).waitingForObservation)
        assertTrue(trace(ActiveRailContinuationsSnapshot.empty(2)).waitingForObservation)
        val result = CurrentRouteTracer.trace(topology, active(), region, entry.boundaryId, exit.boundaryId, false)
        assertEquals(CurrentRouteEnd.UNAVAILABLE, result.end)
        assertFalse(result.waitingForObservation)
        assertTrue(result.traversals.isEmpty())
        assertEquals(entry.position, result.reached)
    }

    @Test fun `returned path and watched null choice map cannot be modified`() {
        val result = trace(active(null))
        assertThrows(UnsupportedOperationException::class.java) { (result.traversals as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (result.watchedContinuations as MutableMap).clear() }
    }
}
