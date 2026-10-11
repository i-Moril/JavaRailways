package work.art1st.javarailways.core.physics.rail.topology

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.api.physics.rail.topology.RailSliceRef
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyNode
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyNodeId
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.core.api.physics.rail.topology.TrackEdgeId
import work.art1st.javarailways.core.api.physics.rail.topology.TrackEdgeSlice
import java.util.UUID

class TopologyStateBuilderTest {
    @Test
    fun `ordinary degree two chain becomes one edge with lossless rail projection`() {
        val first = rail(1, 10.0)
        val middle = rail(2, 20.0)
        val last = rail(3, 30.0)

        val state = build(
            revision = 7L,
            rails = listOf(first, middle, last),
            connections = listOf(
                endpoint(first, RailEndpoint.B) to endpoint(middle, RailEndpoint.A),
                endpoint(middle, RailEndpoint.B) to endpoint(last, RailEndpoint.A)
            )
        )

        assertEquals(2, state.nodes.size)
        assertTrue(state.nodes.values.all { it is TopologyNode.Terminus })
        val edge = state.edges.values.single()
        assertEquals(TrackEdgeId(7L, 0), edge.id)
        assertEquals(60.0, edge.lengthMeters, 1e-12)
        assertEquals(
            listOf(
                RailSliceRef(first.id, 0.0, 10.0),
                RailSliceRef(middle.id, 0.0, 20.0),
                RailSliceRef(last.id, 0.0, 30.0)
            ),
            edge.railSlices
        )
        assertEquals(
            TrackEdgeSlice(edge.id, 10.0, 30.0, 0.0, 20.0),
            state.railProjection.getValue(middle.id).single()
        )
    }

    @Test
    fun `edge records canonical AB direction when a connected rail is traversed backwards`() {
        val first = rail(1, 10.0)
        val reversed = rail(2, 20.0)

        val state = build(
            revision = 3L,
            rails = listOf(first, reversed),
            connections = listOf(
                endpoint(first, RailEndpoint.B) to endpoint(reversed, RailEndpoint.B)
            )
        )

        val edge = state.edges.values.single()
        assertEquals(
            listOf(
                RailSliceRef(first.id, 0.0, 10.0),
                RailSliceRef(reversed.id, 20.0, 0.0)
            ),
            edge.railSlices
        )
        assertEquals(
            TrackEdgeSlice(edge.id, 10.0, 30.0, 20.0, 0.0),
            state.railProjection.getValue(reversed.id).single()
        )
        assertEquals(
            endpoint(reversed, RailEndpoint.B),
            state.ordinaryContinuationAt(endpoint(first, RailEndpoint.B))
        )
        assertEquals(
            endpoint(first, RailEndpoint.B),
            state.ordinaryContinuationAt(endpoint(reversed, RailEndpoint.B))
        )
    }

    @Test
    fun `turnout retains legal port transitions and separates its incident edges`() {
        val trunk = rail(1, 12.0)
        val straight = rail(2, 15.0)
        val branch = rail(3, 18.0)
        val trunkPort = endpoint(trunk, RailEndpoint.B)
        val straightPort = endpoint(straight, RailEndpoint.A)
        val branchPort = endpoint(branch, RailEndpoint.A)

        val state = build(
            revision = 11L,
            rails = listOf(trunk, straight, branch),
            connections = listOf(
                trunkPort to straightPort,
                trunkPort to branchPort
            )
        )

        val turnout = state.nodes.values.filterIsInstance<TopologyNode.Turnout>().single()
        assertEquals(setOf(straightPort, branchPort), turnout.legalContinuations.getValue(trunkPort))
        assertEquals(setOf(trunkPort), turnout.legalContinuations.getValue(straightPort))
        assertEquals(setOf(trunkPort), turnout.legalContinuations.getValue(branchPort))
        assertEquals(3, state.nodes.values.count { it is TopologyNode.Terminus })
        assertEquals(3, state.edges.size)
        assertEquals(setOf(12.0, 15.0, 18.0), state.edges.values.map { it.lengthMeters }.toSet())
    }

    @Test
    fun `node free cycle receives one deterministic anchor and one self edge`() {
        val first = rail(1, 10.0)
        val second = rail(2, 20.0)
        val third = rail(3, 30.0)
        val connections = listOf(
            endpoint(first, RailEndpoint.B) to endpoint(second, RailEndpoint.A),
            endpoint(second, RailEndpoint.B) to endpoint(third, RailEndpoint.A),
            endpoint(third, RailEndpoint.B) to endpoint(first, RailEndpoint.A)
        )

        val state = build(5L, listOf(first, second, third), connections)

        val anchor = state.nodes.values.filterIsInstance<TopologyNode.LoopAnchor>().single()
        val edge = state.edges.values.single()
        assertEquals(anchor.id, edge.first.nodeId)
        assertEquals(anchor.id, edge.second.nodeId)
        assertEquals(60.0, edge.lengthMeters, 1e-12)
        assertEquals(setOf(first.id, second.id, third.id), edge.railSlices.map { it.railId }.toSet())
        assertEquals(3, state.railProjection.size)
    }

    @Test
    fun `normalization is deterministic across input collection order`() {
        val first = rail(1, 10.0)
        val second = rail(2, 20.0)
        val third = rail(3, 30.0)
        val connections = listOf(
            endpoint(first, RailEndpoint.B) to endpoint(second, RailEndpoint.A),
            endpoint(second, RailEndpoint.B) to endpoint(third, RailEndpoint.A)
        )

        val forward = build(13L, listOf(first, second, third), connections)
        val reversed = build(13L, listOf(third, second, first), connections.reversed())

        assertEquals(forward, reversed)
        assertEquals(forward.nodes.keys.toList(), reversed.nodes.keys.toList())
        assertEquals(forward.edges.keys.toList(), reversed.edges.keys.toList())
        assertEquals(forward.railProjection.keys.toList(), reversed.railProjection.keys.toList())
    }

    @Test
    fun `disconnected chains and cycles retain ordered nodes edges and loop anchors`() {
        val rails = (1..5).map { rail(it, it * 10.0) }
        val connections = listOf(
            endpoint(rails[1], RailEndpoint.B) to endpoint(rails[2], RailEndpoint.A),
            endpoint(rails[2], RailEndpoint.B) to endpoint(rails[1], RailEndpoint.A),
            endpoint(rails[3], RailEndpoint.B) to endpoint(rails[4], RailEndpoint.A),
            endpoint(rails[4], RailEndpoint.B) to endpoint(rails[3], RailEndpoint.A)
        )

        val state = build(17L, rails, connections)
        val reordered = build(17L, rails.reversed(), connections.reversed())

        assertEquals(state, reordered)
        assertEquals((0..3).map { TopologyNodeId(17L, it) }, state.nodes.keys.toList())
        assertEquals(state.nodes.keys.toList(), reordered.nodes.keys.toList())
        assertEquals((0..2).map { TrackEdgeId(17L, it) }, state.edges.keys.toList())
        assertEquals(state.edges.keys.toList(), reordered.edges.keys.toList())
        assertEquals(rails.map { it.id }, state.railProjection.keys.toList())
        assertEquals(state.railProjection.keys.toList(), reordered.railProjection.keys.toList())
        assertEquals(2, state.nodes.values.count { it is TopologyNode.Terminus })
        val anchors = state.nodes.values.filterIsInstance<TopologyNode.LoopAnchor>()
        assertEquals(
            listOf(
                listOf(endpoint(rails[1], RailEndpoint.A), endpoint(rails[2], RailEndpoint.B)),
                listOf(endpoint(rails[3], RailEndpoint.A), endpoint(rails[4], RailEndpoint.B))
            ),
            anchors.map { it.ports }
        )
        assertEquals(listOf(10.0, 50.0, 90.0), state.edges.values.map { it.lengthMeters })
        state.edges.values.drop(1).zip(anchors).forEach { (edge, anchor) ->
            assertEquals(anchor.id, edge.first.nodeId)
            assertEquals(anchor.id, edge.second.nodeId)
        }
    }

    @Test
    fun `asymmetric continuation input is rejected`() {
        val first = rail(1, 10.0)
        val second = rail(2, 20.0)
        val firstPort = endpoint(first, RailEndpoint.B)
        val secondPort = endpoint(second, RailEndpoint.A)

        assertThrows(IllegalArgumentException::class.java) {
            TopologyStateBuilder.build(
                TopologyInput(
                    revision = 1L,
                    rails = listOf(first, second),
                    continuations = mapOf(firstPort to setOf(secondPort))
                )
            )
        }
    }

    private fun build(
        revision: Long,
        rails: List<TopologyInputRail>,
        connections: List<Pair<RailEndpointRef, RailEndpointRef>>
    ): TopologyState {
        val continuations = linkedMapOf<RailEndpointRef, MutableSet<RailEndpointRef>>()
        connections.forEach { (first, second) ->
            continuations.getOrPut(first, ::linkedSetOf).add(second)
            continuations.getOrPut(second, ::linkedSetOf).add(first)
        }
        return TopologyStateBuilder.build(
            TopologyInput(
                revision = revision,
                rails = rails,
                continuations = continuations.mapValues { (_, targets) -> targets.toSet() }
            )
        )
    }

    private fun rail(number: Int, lengthMeters: Double): TopologyInputRail =
        TopologyInputRail(UUID(0L, number.toLong()), lengthMeters)

    private fun endpoint(rail: TopologyInputRail, endpoint: RailEndpoint): RailEndpointRef =
        RailEndpointRef(rail.id, endpoint)
}
