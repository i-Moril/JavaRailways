package work.art1st.javarailways.core.physics.rail

import work.art1st.javarailways.core.util.IsolatedSimulationThreadMarker

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.createRailForTest
import work.art1st.javarailways.core.physics.train.createTrainWithConfig
import work.art1st.javarailways.core.physics.train.TestWorldFixture

class PreparedRailConnectionPlanTest {
    @Test
    fun `unordered prepared batch connects by identity and preserves declared branch order`() = withWorld { world ->
        val stem = world.createRailForTest(line(-10.0, 0.0))
        val prepared = world.prepareRailCreationBatch(listOf(
            line(10.0, 20.0), line(0.0, 30.0), line(0.0, 10.0)
        ))
        val (last, branch, first) = prepared.creations
        val stemB = RailEndpointRef(stem.id, RailEndpoint.B)
        val branchA = RailEndpointRef(branch.railId, RailEndpoint.A)
        val firstA = RailEndpointRef(first.railId, RailEndpoint.A)
        val firstB = RailEndpointRef(first.railId, RailEndpoint.B)
        val lastA = RailEndpointRef(last.railId, RailEndpoint.A)
        val plan = PreparedRailConnectionPlan.capture(world, prepared, listOf(
            PreparedRailEndpointConnection(firstB, listOf(lastA)),
            PreparedRailEndpointConnection(lastA, listOf(firstB)),
            PreparedRailEndpointConnection(stemB, listOf(branchA, firstA), firstA),
            PreparedRailEndpointConnection(firstA, listOf(stemB)),
            PreparedRailEndpointConnection(branchA, listOf(stemB))
        ))
        val update = plan.prepareTopologyUpdate()

        lateinit var installed: List<CoreRail>
        IsolatedSimulationThreadMarker.run { world.railTopologyTransaction(update) {
            installed = world.installPreparedRails(prepared)
            plan.apply(world, installed)
        } }

        assertEquals(listOf(last.railId, branch.railId, first.railId), installed.map { it.id })
        val (lastRail, branchRail, firstRail) = installed
        assertEquals(listOf(branchRail.viewAB, firstRail.viewAB), stem.viewAB.nextRails)
        assertSame(firstRail.viewAB, stem.viewAB.nextActiveRail)
        assertEquals(listOf(stem.viewBA), firstRail.viewBA.nextRails)
        assertEquals(listOf(stem.viewBA), branchRail.viewBA.nextRails)
        assertEquals(listOf(lastRail.viewAB), firstRail.viewAB.nextRails)
        assertEquals(listOf(firstRail.viewBA), lastRail.viewBA.nextRails)
        assertEquals(setOf(stemB), plan.affectedExistingEndpoints)
        assertEquals(update.targetRoot.railProjection.keys, world.topologyState.railProjection.keys)
    }

    @Test
    fun `endpoint validation rejects duplicate declarations before symmetry and distance errors`() = withWorld { world ->
        val prepared = world.prepareRailCreationBatch(listOf(line(0.0, 10.0), line(20.0, 30.0)))
        val firstB = RailEndpointRef(prepared.creations[0].railId, RailEndpoint.B)
        val secondA = RailEndpointRef(prepared.creations[1].railId, RailEndpoint.A)
        val root = world.topologyState
        val firstState = PreparedRailEndpointConnection(firstB, listOf(secondA))

        val duplicate = assertThrows(IllegalArgumentException::class.java) {
            PreparedRailConnectionPlan.capture(world, prepared, listOf(firstState, firstState))
        }
        assertEquals("Each explicitly changed endpoint may be declared only once", duplicate.message)
        val asymmetric = assertThrows(IllegalArgumentException::class.java) {
            PreparedRailConnectionPlan.capture(world, prepared, listOf(firstState))
        }
        assertEquals("Explicit connections must be declared symmetrically: $firstB <-> $secondA", asymmetric.message)
        val separated = assertThrows(IllegalArgumentException::class.java) {
            PreparedRailConnectionPlan.capture(world, prepared, listOf(
                firstState, PreparedRailEndpointConnection(secondA, listOf(firstB))
            ))
        }
        assertEquals("Explicitly connected endpoints are not colocated: $firstB <-> $secondA", separated.message)
        assertSame(root, world.topologyState)
        assertTrue(world.allRails.isEmpty())
    }

    @Test
    fun `explicit connection selects only the named endpoint`() = withWorld { world ->
        val selected = world.createRailForTest(line(0.0, 10.0))
        val nearbyButUnselected = world.createRailForTest(line(10.0, 15.0))
        val prepared = world.prepareRailCreation(line(10.0, 20.0))
        val createdA = RailEndpointRef(prepared.creations.single().railId, RailEndpoint.A)
        val selectedB = RailEndpointRef(selected.id, RailEndpoint.B)
        val plan = PreparedRailConnectionPlan.capture(
            world,
            prepared,
            listOf(
                PreparedRailEndpointConnection(selectedB, listOf(createdA), createdA),
                PreparedRailEndpointConnection(createdA, listOf(selectedB), selectedB)
            )
        )
        val topologyUpdate = plan.prepareTopologyUpdate()

        lateinit var created: CoreRail
        IsolatedSimulationThreadMarker.run { world.railTopologyTransaction(topologyUpdate) {
            created = world.installPreparedRails(prepared).single()
            plan.apply(world, listOf(created))
        } }

        assertEquals(listOf(created.viewAB), selected.viewAB.nextRails)
        assertEquals(listOf(selected.viewBA), created.viewBA.nextRails)
        assertTrue(nearbyButUnselected.viewBA.nextRails.isEmpty())
        assertEquals(setOf(selected.id), plan.affectedExistingRailIds)
        assertEquals(topologyUpdate.targetRoot.railProjection.keys, world.topologyState.railProjection.keys)
    }

    @Test
    fun `standalone prepared rail remains disconnected`() = withWorld { world ->
        world.createRailForTest(line(0.0, 10.0))
        val prepared = world.prepareRailCreation(line(10.0, 20.0))
        val plan = PreparedRailConnectionPlan.capture(world, prepared, emptyList())
        val topologyUpdate = plan.prepareTopologyUpdate()

        lateinit var created: CoreRail
        IsolatedSimulationThreadMarker.run { world.railTopologyTransaction(topologyUpdate) {
            created = world.installPreparedRails(prepared).single()
            plan.apply(world, listOf(created))
        } }

        assertTrue(created.viewAB.nextRails.isEmpty())
        assertTrue(created.viewBA.nextRails.isEmpty())
        assertTrue(plan.affectedExistingEndpoints.isEmpty())
        assertEquals(topologyUpdate.targetRoot.railProjection.keys, world.topologyState.railProjection.keys)
    }

    @Test
    fun `captured plan survives an unrelated topology change`() = withWorld { world ->
        val selected = world.createRailForTest(line(0.0, 10.0))
        val prepared = world.prepareRailCreation(line(10.0, 20.0))
        val createdA = RailEndpointRef(prepared.creations.single().railId, RailEndpoint.A)
        val selectedB = RailEndpointRef(selected.id, RailEndpoint.B)
        val plan = PreparedRailConnectionPlan.capture(
            world,
            prepared,
            listOf(
                PreparedRailEndpointConnection(selectedB, listOf(createdA), createdA),
                PreparedRailEndpointConnection(createdA, listOf(selectedB), selectedB)
            )
        )

        world.createRailForTest(line(1000.0, 1010.0))

        assertTrue(plan.isCurrent(world))
        plan.requireCurrent(world)
        assertTrue(selected.viewAB.nextRails.isEmpty())
    }

    @Test
    fun `prepared topology applies its delta after an unrelated edit`() = withWorld { world ->
        val prepared = world.prepareRailCreation(line(0.0, 10.0))
        val plan = PreparedRailConnectionPlan.capture(world, prepared, emptyList())
        val topologyUpdate = plan.prepareTopologyUpdate()
        val other = world.createRailForTest(line(1000.0, 1010.0))
        IsolatedSimulationThreadMarker.run { world.railTopologyTransaction(topologyUpdate) {
            val installed = world.installPreparedRails(prepared)
            plan.apply(world, installed)
        } }
        assertSame(other, world.getRail(other.id))
        assertEquals(2, world.allRails.size)
        assertTrue(world.getRail(prepared.creations.single().railId) != null)
    }

    @Test
    fun `captured plan rejects an active branch change`() = withWorld { world ->
        val stem = world.createRailForTest(line(0.0, 10.0))
        val first = world.createRailForTest(line(10.0, 20.0))
        val second = world.createRailForTest(line(10.0, 30.0))
        IsolatedSimulationThreadMarker.run { CoreRail.connect(stem, first) }
        IsolatedSimulationThreadMarker.run { CoreRail.connect(stem, second) }
        IsolatedSimulationThreadMarker.run { stem.viewAB.activeRail = first.viewAB }
        val prepared = world.prepareRailCreation(line(40.0, 50.0))
        val stemB = RailEndpointRef(stem.id, RailEndpoint.B)
        val firstA = RailEndpointRef(first.id, RailEndpoint.A)
        val secondA = RailEndpointRef(second.id, RailEndpoint.A)
        val plan = PreparedRailConnectionPlan.capture(
            world,
            prepared,
            listOf(
                PreparedRailEndpointConnection(stemB, listOf(firstA, secondA), firstA),
                PreparedRailEndpointConnection(firstA, listOf(stemB), stemB),
                PreparedRailEndpointConnection(secondA, listOf(stemB), stemB)
            )
        )

        IsolatedSimulationThreadMarker.run { stem.viewAB.activeRail = second.viewAB }
        assertFalse(plan.isCurrent(world))
        assertThrows(IllegalStateException::class.java) { plan.requireCurrent(world) }
    }

    @Test
    fun `removing a surviving connection requires both endpoint states`() = withWorld { world ->
        val first = world.createRailForTest(line(0.0, 10.0))
        val second = world.createRailForTest(line(10.0, 20.0))
        IsolatedSimulationThreadMarker.run { CoreRail.connect(first, second) }
        val prepared = world.prepareRailCreation(line(30.0, 40.0))

        assertThrows(IllegalArgumentException::class.java) {
            PreparedRailConnectionPlan.capture(
                world,
                prepared,
                listOf(PreparedRailEndpointConnection(
                    RailEndpointRef(first.id, RailEndpoint.B),
                    emptyList(),
                    null
                ))
            )
        }
        assertEquals(listOf(second.viewAB), first.viewAB.nextRails)
        assertEquals(listOf(first.viewBA), second.viewBA.nextRails)
    }

    @Test
    fun `occupied existing endpoint is rejected before installation`() = withWorld { world ->
        val selected = world.createRailForTest(line(0.0, 30.0))
        val existingPeer = world.createRailForTest(line(30.0, 60.0))
        IsolatedSimulationThreadMarker.run { CoreRail.connect(selected, existingPeer) }
        createTrainWithConfig("occupied-explicit-plan", existingPeer, world, listOf(8.0))
        val prepared = world.prepareRailCreation(line(30.0, 40.0))
        val createdA = RailEndpointRef(prepared.creations.single().railId, RailEndpoint.A)
        val selectedB = RailEndpointRef(selected.id, RailEndpoint.B)
        val existingPeerA = RailEndpointRef(existingPeer.id, RailEndpoint.A)
        val plan = PreparedRailConnectionPlan.capture(
            world,
            prepared,
            listOf(
                PreparedRailEndpointConnection(selectedB, listOf(existingPeerA, createdA), existingPeerA),
                PreparedRailEndpointConnection(existingPeerA, listOf(selectedB), selectedB),
                PreparedRailEndpointConnection(createdA, listOf(selectedB), selectedB)
            )
        )

        assertThrows(IllegalArgumentException::class.java) { plan.requireReadyToApply(world) }
        assertEquals(null, world.getRail(prepared.creations.single().railId))
        assertEquals(listOf(existingPeer.viewAB), selected.viewAB.nextRails)
        assertTrue(plan.affectedExistingRailIds.containsAll(setOf(selected.id, existingPeer.id)))
    }

    @Test
    fun `apply rejects another inventory mutation after prepared installation`() = withWorld { world ->
        val prepared = world.prepareRailCreation(line(0.0, 10.0))
        val plan = PreparedRailConnectionPlan.capture(world, prepared, emptyList())

        IsolatedSimulationThreadMarker.run { world.railTopologyTransaction {
            val installed = world.installPreparedRails(prepared)
            val unrelated = world.createRailForTest(line(20.0, 30.0))

            assertThrows(IllegalArgumentException::class.java) { plan.apply(world, installed) }

            world.removeRail(unrelated)
            world.removeRail(installed.single())
        } }
        assertTrue(world.allRails.isEmpty())
    }

    private fun withWorld(block: (CoreWorld) -> Unit) {
        TestWorldFixture.create().use { fixture -> block(fixture.world) }
    }

    private fun line(fromX: Double, toX: Double): PrimeCurve {
        val tangent = Vector3d(1.0, 0.0, 0.0)
        val normal = Vector3d(0.0, 1.0, 0.0)
        return PrimeCurve.fromPoints(listOf(
            Curve.RawPoint(Vector3d(fromX, 0.0, 0.0), Vector3d(tangent), Vector3d(normal), 0.0),
            Curve.RawPoint(Vector3d(toX, 0.0, 0.0), Vector3d(tangent), Vector3d(normal), toX - fromX)
        ))
    }
}
