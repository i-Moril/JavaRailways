package work.art1st.javarailways.core.physics

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyNode
import work.art1st.javarailways.core.physics.rail.AT_GRADE_CROSSING_TOLERANCE_METERS
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.rail.RailView
import work.art1st.javarailways.core.physics.train.createTestWorld

class CoreWorldAtGradeRailCrossingTest {
    @Test
    fun `at-grade crossing is published without changing connectivity`() = withWorld { world ->
        val eastWest = rail(world, Vector3d(-5.0, 0.0, 0.0), Vector3d(5.0, 0.0, 0.0))
        val northSouth = rail(world, Vector3d(0.0, 0.0, -5.0), Vector3d(0.0, 0.0, 5.0))

        val topology = world.topologyState
        val crossing = topology.atGradeCrossings.single()
        val arcsByRail = listOf(crossing.first, crossing.second)
            .associate { point -> point.railId to point.arcLengthAbMeters }

        assertEquals(5.0, arcsByRail.getValue(eastWest.id), 1.0e-9)
        assertEquals(5.0, arcsByRail.getValue(northSouth.id), 1.0e-9)
        assertEquals(2, topology.edges.size)
        assertEquals(4, topology.nodes.size)
        assertTrue(topology.edges.values.all { it.railSlices.size == 1 })
    }

    @Test
    fun `bridge and connected endpoint are not at-grade diamond resources`() = withWorld { world ->
        rail(world, Vector3d(-5.0, 0.0, 0.0), Vector3d(5.0, 0.0, 0.0))
        rail(world, Vector3d(0.0, 2.0, -5.0), Vector3d(0.0, 2.0, 5.0))
        assertTrue(world.topologyState.atGradeCrossings.isEmpty())

        lateinit var first: CoreRail
        lateinit var second: CoreRail
        world.railTopologyTransaction {
            first = rail(world, Vector3d(20.0, 0.0, 0.0), Vector3d(25.0, 0.0, 0.0))
            second = rail(world, Vector3d(25.0, 0.0, 0.0), Vector3d(25.0, 0.0, 5.0))
            RailView.connect(first.viewAB, second.viewBA)
        }

        assertTrue(world.topologyState.atGradeCrossings.isEmpty())
    }

    @Test
    fun `unconnected rails meeting at endpoints remain a diamond resource`() = withWorld { world ->
        val first = rail(world, Vector3d(20.0, 0.0, 0.0), Vector3d(25.0, 0.0, 0.0))
        val second = rail(world, Vector3d(25.0, 0.0, 0.0), Vector3d(25.0, 0.0, 5.0))

        val crossing = world.topologyState.atGradeCrossings.single()
        assertEquals(setOf(first.id, second.id), setOf(crossing.first.railId, crossing.second.railId))
    }

    @Test
    fun `nearby parallel rails are not a diamond resource`() = withWorld { world ->
        rail(world, Vector3d(0.0, 0.0, 0.0), Vector3d(5.0, 0.0, 0.0))
        rail(world, Vector3d(0.0, 0.0, 0.1), Vector3d(5.0, 0.0, 0.1))

        assertTrue(world.topologyState.atGradeCrossings.isEmpty())
    }

    @Test
    fun `crossing detection accepts small construction error but rejects larger vertical separation`() =
        withWorld { world ->
            val eastWest = rail(world, Vector3d(-5.0, 0.0, 0.0), Vector3d(5.0, 0.0, 0.0))
            val withinTolerance = rail(
                world,
                Vector3d(0.0, AT_GRADE_CROSSING_TOLERANCE_METERS - 0.01, -5.0),
                Vector3d(0.0, AT_GRADE_CROSSING_TOLERANCE_METERS - 0.01, 5.0)
            )
            rail(
                world,
                Vector3d(2.0, AT_GRADE_CROSSING_TOLERANCE_METERS + 0.01, -5.0),
                Vector3d(2.0, AT_GRADE_CROSSING_TOLERANCE_METERS + 0.01, 5.0)
            )

            val crossing = world.topologyState.atGradeCrossings.single()
            assertEquals(
                setOf(eastWest.id, withinTolerance.id),
                setOf(crossing.first.railId, crossing.second.railId)
            )
        }

    @Test
    fun `one rail pair publishes only its globally closest approach`() = withWorld { world ->
        val first = rail(world, Vector3d(0.0, 0.0, 0.0), Vector3d(10.0, 0.0, 0.0))
        val second = polylineRail(
            world,
            Vector3d(1.0, 0.0, 0.19),
            Vector3d(1.5, 0.0, 0.18),
            Vector3d(5.0, 0.0, 0.0),
            Vector3d(5.0, 0.0, 3.0)
        )

        val crossing = world.topologyState.atGradeCrossings.single()
        val arcsByRail = listOf(crossing.first, crossing.second)
            .associate { point -> point.railId to point.arcLengthAbMeters }
        val expectedSecondArc = Vector3d(1.0, 0.0, 0.19).distance(Vector3d(1.5, 0.0, 0.18)) +
            Vector3d(1.5, 0.0, 0.18).distance(Vector3d(5.0, 0.0, 0.0))

        assertEquals(5.0, arcsByRail.getValue(first.id), 1.0e-9)
        assertEquals(expectedSecondArc, arcsByRail.getValue(second.id), 1.0e-9)
    }

    @Test
    fun `three rails in one turnout endpoint group do not create a diamond resource`() = withWorld { world ->
        lateinit var stem: CoreRail
        lateinit var straight: CoreRail
        lateinit var branch: CoreRail
        world.railTopologyTransaction {
            stem = rail(world, Vector3d(-5.0, 0.0, 0.0), Vector3d(0.0, 0.0, 0.0))
            straight = rail(world, Vector3d(0.0, 0.0, 0.0), Vector3d(5.0, 0.0, 0.0))
            branch = polylineRail(
                world,
                Vector3d(0.0, 0.0, 0.0),
                Vector3d(1.0, 0.0, 0.0),
                Vector3d(2.0, 0.0, 0.05),
                Vector3d(5.0, 0.0, 2.0)
            )
            RailView.connect(stem.viewAB, straight.viewBA)
            RailView.connect(stem.viewAB, branch.viewBA)
        }

        assertEquals(1, world.topologyState.nodes.values.count { it is TopologyNode.Turnout })
        assertTrue(world.topologyState.atGradeCrossings.isEmpty())
    }

    @Test
    fun `removing a crossing rail removes the observation in the same revision`() = withWorld { world ->
        val retained = rail(world, Vector3d(-5.0, 0.0, 0.0), Vector3d(5.0, 0.0, 0.0))
        val removed = rail(world, Vector3d(0.0, 0.0, -5.0), Vector3d(0.0, 0.0, 5.0))
        assertEquals(1, world.topologyState.atGradeCrossings.size)

        world.removeRail(removed)

        assertTrue(world.topologyState.atGradeCrossings.isEmpty())
        assertEquals(setOf(retained.id), world.topologyState.railProjection.keys)
    }

    private fun rail(world: CoreWorld, from: Vector3d, to: Vector3d): CoreRail =
        world.createRail(straightCurve(from, to))

    private fun polylineRail(world: CoreWorld, vararg positions: Vector3d): CoreRail {
        require(positions.size >= 2)
        var arcLength = 0.0
        val points = positions.mapIndexed { index, position ->
            if (index > 0) arcLength += positions[index - 1].distance(position)
            val tangent = if (index < positions.lastIndex) {
                Vector3d(positions[index + 1]).sub(position)
            } else {
                Vector3d(position).sub(positions[index - 1])
            }.normalize()
            Curve.RawPoint(
                Vector3d(position),
                tangent,
                Vector3d(0.0, 1.0, 0.0),
                arcLength
            )
        }
        return world.createRail(PrimeCurve.fromPoints(points))
    }

    private fun straightCurve(from: Vector3d, to: Vector3d): PrimeCurve {
        val displacement = Vector3d(to).sub(from)
        val length = displacement.length()
        val tangent = Vector3d(displacement).normalize()
        val normal = Vector3d(0.0, 1.0, 0.0)
        return PrimeCurve.fromPoints(listOf(
            Curve.RawPoint(Vector3d(from), Vector3d(tangent), Vector3d(normal), 0.0),
            Curve.RawPoint(Vector3d(to), Vector3d(tangent), Vector3d(normal), length)
        ))
    }

    private fun withWorld(block: (CoreWorld) -> Unit) {
        val world = createTestWorld()
        try {
            block(world)
        } finally {
            world.physicsCoordinator.close()
            (world.tickManager as AutoCloseable).close()
        }
    }
}
