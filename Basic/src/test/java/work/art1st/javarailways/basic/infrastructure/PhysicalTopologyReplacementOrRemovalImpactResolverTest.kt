package work.art1st.javarailways.basic.infrastructure

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.infrastructure.StableTopologyNodeKey
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyNode
import work.art1st.javarailways.core.physics.createRailForTest
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.train.createTestWorld

class PhysicalTopologyReplacementOrRemovalImpactResolverTest {
    @Test
    fun `replacement includes only the peer at an ordinary endpoint`() {
        val world = createTestWorld()
        try {
            val source = world.createRailForTest(curve(Vector3d(-5.0, 0.0, 0.0), Vector3d(0.0, 0.0, 0.0)))
            val peer = world.createRailForTest(curve(Vector3d(0.0, 0.0, 0.0), Vector3d(5.0, 0.0, 0.0)))
            val beyond = world.createRailForTest(curve(Vector3d(5.0, 0.0, 0.0), Vector3d(10.0, 0.0, 0.0)))
            CoreRail.connect(source, peer)
            CoreRail.connect(peer, beyond)

            val impact = PhysicalTopologyReplacementOrRemovalImpactResolver.resolve(
                world.topologyState,
                listOf(source.id)
            )
            val sourceOpenEnd = requireNotNull(
                world.topologyState.structuralNodeAt(RailEndpointRef(source.id, RailEndpoint.A))
            )

            assertEquals(setOf(source.id, peer.id), impact.railIds)
            assertEquals(setOf(StableTopologyNodeKey(sourceOpenEnd.ports)), impact.topologyNodeKeys)
            assertTrue(impact.resourceIds.isEmpty())
        } finally {
            world.physicsCoordinator.close()
            (world.tickManager as AutoCloseable).close()
        }
    }

    @Test
    fun `replacement excludes the neighbor opposite turnout and diamond`() {
        val world = createTestWorld()
        try {
            val stem = world.createRailForTest(curve(Vector3d(-5.0, 0.0, 0.0), Vector3d(0.0, 0.0, 0.0)))
            val straight = world.createRailForTest(curve(Vector3d(0.0, 0.0, 0.0), Vector3d(5.0, 0.0, 0.0)))
            val branch = world.createRailForTest(curve(Vector3d(0.0, 0.0, 0.0), Vector3d(5.0, 0.0, 2.0)))
            val sourceCrossingRail = world.createRailForTest(
                curve(Vector3d(-2.0, 0.0, -5.0), Vector3d(-2.0, 0.0, 5.0))
            )
            val farStraight = world.createRailForTest(curve(Vector3d(5.0, 0.0, 0.0), Vector3d(10.0, 0.0, 0.0)))
            val farBranch = world.createRailForTest(curve(Vector3d(5.0, 0.0, 0.0), Vector3d(10.0, 0.0, 2.0)))
            val neighborCrossingRail = world.createRailForTest(
                curve(Vector3d(3.0, 0.0, -5.0), Vector3d(3.0, 0.0, 5.0))
            )
            CoreRail.connect(stem, straight)
            CoreRail.connect(stem, branch)
            CoreRail.connect(straight, farStraight)
            CoreRail.connect(straight, farBranch)

            val topology = world.topologyState
            val sourceTurnout = requireNotNull(
                topology.structuralNodeAt(RailEndpointRef(stem.id, RailEndpoint.B))
            ) as TopologyNode.Turnout
            val neighborTurnout = requireNotNull(
                topology.structuralNodeAt(RailEndpointRef(straight.id, RailEndpoint.B))
            ) as TopologyNode.Turnout
            val sourceDiamond = topology.atGradeCrossings.single { crossing ->
                stem.id == crossing.first.railId || stem.id == crossing.second.railId
            }
            val neighborDiamond = topology.atGradeCrossings.single { crossing ->
                setOf(crossing.first.railId, crossing.second.railId) ==
                    setOf(straight.id, neighborCrossingRail.id)
            }
            val impact = PhysicalTopologyReplacementOrRemovalImpactResolver.resolve(topology, listOf(stem.id))
            val controlledResources = PhysicalTopologyResources.controlledResources(
                topology,
                PhysicalTopologyResources.routeBoundBindings(topology)
            )

            assertEquals(setOf(stem.id, straight.id, branch.id), impact.railIds)
            assertTrue(StableTopologyNodeKey(sourceTurnout.ports) in impact.topologyNodeKeys)
            assertTrue(TurnoutResourceIdentity.fromPorts(sourceTurnout.ports) in impact.resourceIds)
            assertTrue(PhysicalTopologyResources.diamondResource(sourceDiamond) in impact.resourceIds)
            assertTrue(sourceCrossingRail.id !in impact.railIds)
            assertTrue(StableTopologyNodeKey(neighborTurnout.ports) !in impact.topologyNodeKeys)
            assertTrue(TurnoutResourceIdentity.fromPorts(neighborTurnout.ports) !in impact.resourceIds)
            assertTrue(PhysicalTopologyResources.diamondResource(neighborDiamond) !in impact.resourceIds)
            assertTrue(controlledResources.containsAll(impact.resourceIds))
        } finally {
            world.physicsCoordinator.close()
            (world.tickManager as AutoCloseable).close()
        }
    }

    @Test
    fun `turnout branch replacement excludes its unconnected sibling branch rail`() {
        val world = createTestWorld()
        try {
            val stem = world.createRailForTest(curve(Vector3d(-5.0, 0.0, 0.0), Vector3d(0.0, 0.0, 0.0)))
            val firstBranch = world.createRailForTest(curve(Vector3d(0.0, 0.0, 0.0), Vector3d(5.0, 0.0, 1.0)))
            val siblingBranch = world.createRailForTest(curve(Vector3d(0.0, 0.0, 0.0), Vector3d(5.0, 0.0, -1.0)))
            CoreRail.connect(stem, firstBranch)
            CoreRail.connect(stem, siblingBranch)

            val topology = world.topologyState
            val turnout = requireNotNull(
                topology.structuralNodeAt(RailEndpointRef(firstBranch.id, RailEndpoint.A))
            ) as TopologyNode.Turnout
            val impact = PhysicalTopologyReplacementOrRemovalImpactResolver.resolve(
                topology,
                listOf(firstBranch.id)
            )

            assertEquals(setOf(firstBranch.id, stem.id), impact.railIds)
            assertTrue(StableTopologyNodeKey(turnout.ports) in impact.topologyNodeKeys)
            assertTrue(TurnoutResourceIdentity.fromPorts(turnout.ports) in impact.resourceIds)
            assertTrue(siblingBranch.id !in impact.railIds)
        } finally {
            world.physicsCoordinator.close()
            (world.tickManager as AutoCloseable).close()
        }
    }

    @Test
    fun `replacement anywhere in a pure loop includes its deterministic anchor`() {
        val world = createTestWorld()
        try {
            val first = world.createRailForTest(curve(Vector3d(0.0, 0.0, 0.0), Vector3d(5.0, 0.0, 0.0)))
            val second = world.createRailForTest(curve(Vector3d(5.0, 0.0, 0.0), Vector3d(5.0, 0.0, 5.0)))
            val third = world.createRailForTest(curve(Vector3d(5.0, 0.0, 5.0), Vector3d(0.0, 0.0, 0.0)))
            CoreRail.connect(first, second)
            CoreRail.connect(second, third)
            CoreRail.connect(third, first)

            val topology = world.topologyState
            val anchor = topology.nodes.values.filterIsInstance<TopologyNode.LoopAnchor>().single()
            val source = listOf(first, second, third).single { rail ->
                anchor.ports.none { port -> port.railId == rail.id }
            }

            val impact = PhysicalTopologyReplacementOrRemovalImpactResolver.resolve(topology, listOf(source.id))

            assertTrue(StableTopologyNodeKey(anchor.ports) in impact.topologyNodeKeys)
            assertEquals(
                setOf(
                    source.id,
                    topology.ordinaryContinuationAt(RailEndpointRef(source.id, RailEndpoint.A))!!.railId,
                    topology.ordinaryContinuationAt(RailEndpointRef(source.id, RailEndpoint.B))!!.railId
                ),
                impact.railIds
            )
        } finally {
            world.physicsCoordinator.close()
            (world.tickManager as AutoCloseable).close()
        }
    }

    private fun curve(from: Vector3d, to: Vector3d): PrimeCurve {
        val displacement = Vector3d(to).sub(from)
        val tangent = Vector3d(displacement).normalize()
        return PrimeCurve.fromPoints(listOf(
            Curve.RawPoint(Vector3d(from), Vector3d(tangent), Vector3d(0.0, 1.0, 0.0), 0.0),
            Curve.RawPoint(Vector3d(to), Vector3d(tangent), Vector3d(0.0, 1.0, 0.0), displacement.length())
        ))
    }
}
