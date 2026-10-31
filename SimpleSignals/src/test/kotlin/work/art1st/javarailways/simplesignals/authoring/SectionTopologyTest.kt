package work.art1st.javarailways.simplesignals.authoring

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.AtGradeRailCrossing
import work.art1st.javarailways.core.api.physics.rail.topology.RailCrossingPoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.core.physics.rail.topology.TestTopologyRail
import work.art1st.javarailways.core.physics.rail.topology.buildTestTopology
import work.art1st.javarailways.simplesignals.region.ProtectionBoundaryDefinition
import java.util.UUID

class SectionTopologyTest {
    private val a = UUID(0, 1)
    private val b = UUID(0, 2)
    private val c = UUID(0, 3)
    private val d = UUID(0, 4)
    private fun point(rail: UUID, arc: Double, facing: ViewDirection = ViewDirection.AB) = RailPositionSnapshot(rail, facing, arc)
    private fun cut(rail: UUID, arc: Double, facing: ViewDirection = ViewDirection.AB) =
        ProtectionBoundaryDefinition("test:${rail.leastSignificantBits}-$arc", point(rail, arc, facing))

    @Test fun `definition preserves selected path order and mixed canonical directions`() {
        val topology = graph(mapOf(a to 10.0, b to 10.0), listOf(port(a, false) to port(b, false)))
        val path = BlockPathBuilder.path(topology, point(a, 2.0), point(b, 2.0))
        assertEquals(listOf(ViewDirection.AB, ViewDirection.BA), path.map { it.facing })
        val block = BlockPathBuilder.definition("test:block", path)
        assertEquals(path, block.traversals)
        assertEquals(listOf(a, b), block.traversals.map { it.railId })
        val reversePath = BlockPathBuilder.path(topology, point(b, 2.0), point(a, 2.0))
        val reverseBlock = BlockPathBuilder.definition("test:reverse", reversePath)
        assertEquals(listOf(b, a), reverseBlock.traversals.map { it.railId })
        assertEquals(reversePath, reverseBlock.traversals)
    }

    @Test fun `even the straight branch of a turnout is rejected`() {
        val topology = graph(mapOf(a to 10.0, b to 10.0, c to 10.0),
            listOf(port(a, false) to port(b, true), port(a, false) to port(c, true)))
        assertThrows(IllegalArgumentException::class.java) { BlockPathBuilder.path(topology, point(a, 2.0), point(b, 8.0)) }
    }

    @Test fun `block length has no protection region radius cap`() {
        val path = BlockPathBuilder.path(graph(mapOf(a to 3000.0)), point(a, 1.0), point(a, 2900.0))
        assertEquals(2899.0, path.single().lengthMeters)
    }

    @Test fun `arriving at the start of the final rail needs no invented zero length traversal`() {
        val path = BlockPathBuilder.path(graph(mapOf(a to 10.0, b to 10.0), listOf(port(a, false) to port(b, true))),
            point(a, 2.0), point(b, 0.0))
        assertEquals(1, path.size)
        assertEquals(a, path.single().railId)
        assertEquals(10.0, path.single().travelEndAbMeters)
    }

    @Test fun `closed region requires explicit cuts and correct inward directions`() {
        val topology = graph(mapOf(a to 10.0))
        val first = cut(a, 2.0)
        assertFalse(ProtectionBoundarySearch.search(topology, first, listOf(first), 100.0).complete)
        val last = cut(a, 8.0, ViewDirection.BA)
        val complete = ProtectionBoundarySearch.search(topology, first, listOf(first, last), 100.0)
        assertTrue(complete.complete)
        assertEquals(6.0, complete.farthestDistanceMeters)
        assertFalse(ProtectionBoundarySearch.search(topology, first,
            listOf(first, last.copy(position = last.position.copy(facing = ViewDirection.AB))), 100.0).complete)
        val shared = last.copy(position = last.position.copy(facing = ViewDirection.AB))
        val reused = ProtectionBoundarySearch.searchCuts(topology, first, listOf(first.cut(), shared.cut()), 100.0)
        assertTrue(reused.complete)
        assertEquals(ViewDirection.BA, reused.boundaries.single { it.boundaryId == shared.boundaryId }.insideFacing)
    }

    @Test fun `branch distances share stem and are never summed`() {
        val topology = graph(mapOf(a to 201.0, b to 301.0, c to 301.0),
            listOf(port(a, false) to port(b, true), port(a, false) to port(c, true)))
        val first = cut(a, 1.0)
        val cuts = listOf(first, cut(b, 300.0, ViewDirection.BA), cut(c, 300.0, ViewDirection.BA))
        val result = ProtectionBoundarySearch.search(topology, first, cuts, 500.0)
        assertTrue(result.complete, result.detail)
        assertEquals(500.0, result.farthestDistanceMeters)
        assertFalse(ProtectionBoundarySearch.search(topology, first, cuts, 499.0).complete)
    }

    @Test fun `single long rail is range bounded before its far endpoint`() {
        val topology = graph(mapOf(a to 10000.0))
        val first = cut(a, 1.0)
        val result = ProtectionBoundarySearch.search(topology, first, listOf(first, cut(a, 9000.0, ViewDirection.BA)), 1000.0)
        assertFalse(result.complete)
        assertTrue(result.detail.contains("maximum along-track"))
    }

    @Test fun `parallel routes measure farthest interior using both shortest wavefronts`() {
        val topology = graph(mapOf(a to 2.0, b to 10.0, c to 20.0, d to 2.0), listOf(
            port(a, false) to port(b, true), port(a, false) to port(c, true),
            port(b, false) to port(d, true), port(c, false) to port(d, true)))
        val first = cut(a, 1.0)
        val cuts = listOf(first, cut(d, 1.0, ViewDirection.BA))
        val result = ProtectionBoundarySearch.search(topology, first, cuts, 16.0)
        assertTrue(result.complete, result.detail)
        // On the 20 m branch, one end is 1 m from start, the other 11 m. The
        // farthest meeting point lies inside the rail at 16 m, not at a graph node.
        assertEquals(16.0, result.farthestDistanceMeters)
        assertFalse(ProtectionBoundarySearch.search(topology, first, cuts, 15.9).complete)
    }

    @Test fun `unrelated missing rail boundary does not expand or invalidate local search`() {
        val topology = graph(mapOf(a to 10.0))
        val first = cut(a, 1.0)
        assertTrue(ProtectionBoundarySearch.search(topology, first,
            listOf(first, cut(a, 9.0, ViewDirection.BA), cut(b, 5.0)), 100.0).complete)
    }

    @Test fun `crossing requires all conflict legs bounded without creating train continuations`() {
        val topology = buildTestTopology(1, listOf(TestTopologyRail(a, 10.0), TestTopologyRail(b, 10.0)),
            emptyMap(), atGradeCrossings = listOf(AtGradeRailCrossing(
                RailCrossingPoint(a, 5.0), RailCrossingPoint(b, 5.0))))
        val first = cut(a, 2.0)
        val ownCuts = listOf(first, cut(a, 8.0, ViewDirection.BA))
        assertFalse(ProtectionBoundarySearch.search(topology, first, ownCuts, 20.0).complete)
        val closed = ProtectionBoundarySearch.search(topology, first,
            ownCuts + listOf(cut(b, 2.0), cut(b, 8.0, ViewDirection.BA)), 6.0)
        assertTrue(closed.complete, closed.detail)
        assertEquals(setOf(a, b), closed.interior.map { it.railId }.toSet())
        assertEquals(6.0, closed.farthestDistanceMeters)
        assertFalse(ProtectionBoundarySearch.search(topology, first,
            ownCuts + listOf(cut(b, 2.0), cut(b, 8.0, ViewDirection.BA)), 5.9).complete)
        val beforeCrossing = listOf(first, cut(a, 4.0, ViewDirection.BA))
        assertTrue(ProtectionBoundarySearch.search(topology, first, beforeCrossing, 20.0).complete)
        assertThrows(IllegalArgumentException::class.java) {
            BlockPathBuilder.path(topology, point(a, 2.0), point(b, 8.0))
        }
    }

    @Test fun `loop with two physical paths is rejected rather than guessing a block path`() {
        val topology = graph(mapOf(a to 10.0, b to 10.0),
            listOf(port(a, false) to port(b, true), port(b, false) to port(a, true)))
        assertThrows(IllegalArgumentException::class.java) { BlockPathBuilder.path(topology, point(a, 2.0), point(b, 8.0)) }
    }

    private fun port(rail: UUID, atA: Boolean) = RailEndpointRef(rail, if (atA) RailEndpoint.A else RailEndpoint.B)
    private fun graph(lengths: Map<UUID, Double>, connections: List<Pair<RailEndpointRef, RailEndpointRef>> = emptyList()): TopologyState {
        val continuations = linkedMapOf<RailEndpointRef, MutableSet<RailEndpointRef>>()
        connections.forEach { (first, second) ->
            continuations.getOrPut(first, ::linkedSetOf) += second
            continuations.getOrPut(second, ::linkedSetOf) += first
        }
        return buildTestTopology(1, lengths.map { TestTopologyRail(it.key, it.value) }, continuations)
    }
}
