package work.art1st.javarailways.basic.infrastructure

import org.joml.Vector3d
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureFootprint
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner
import work.art1st.javarailways.basic.api.infrastructure.ManualTurnoutSelectionRequest
import work.art1st.javarailways.basic.api.infrastructure.ManualTurnoutSelectionStatus
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.physics.rail.RailInterval
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyNode
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.createRailForTest
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.train.createTestWorld

class ManualTurnoutSelectionRuntimeTest {
    private val worlds = mutableListOf<CoreWorld>()

    @AfterEach
    fun closeWorlds() {
        worlds.forEach { world ->
            world.physicsCoordinator.close()
            (world.tickManager as? AutoCloseable)?.close()
        }
    }

    @Test
    fun `manual selection temporarily claims resource and applies both directions`() {
        val fixture = turnout()
        var physicsWaits = 0
        val runtime = runtime(fixture) { physicsWaits++ }

        val result = runtime.select(Any(), fixture.request(fixture.first))

        assertEquals(ManualTurnoutSelectionStatus.SELECTED, result.status)
        assertSame(fixture.first.viewAB, fixture.stem.viewAB.nextActiveRail)
        assertSame(fixture.stem.viewBA, fixture.first.viewBA.nextActiveRail)
        assertEquals(1, physicsWaits)
        assertTrue(fixture.registry.claimSnapshots().isEmpty())
    }

    @Test
    fun `live route claim blocks manual selection before physics wait`() {
        val fixture = turnout()
        val routeOwner = InfrastructureOwner("test:route/main", 1L)
        fixture.registry.acquireClaim(
            Any(),
            InfrastructureClaimRequest(
                fixture.world.topologyState.revision,
                routeOwner,
                InfrastructureFootprint(listOf(fixture.resource))
            )
        )
        var physicsWaits = 0
        val runtime = runtime(fixture) { physicsWaits++ }

        val result = runtime.select(Any(), fixture.request(fixture.first))

        assertEquals(ManualTurnoutSelectionStatus.RESOURCE_CONFLICT, result.status)
        assertEquals(routeOwner, result.blockers.single().owner)
        assertSame(fixture.second.viewAB, fixture.stem.viewAB.nextActiveRail)
        assertEquals(0, physicsWaits)
    }

    @Test
    fun `physical locking occupancy rejects and releases temporary claim`() {
        val fixture = turnout()
        var queriedIntervals = emptyList<RailInterval>()
        val runtime = ManualTurnoutSelectionRuntime(
            fixture.world,
            fixture.registry,
            awaitCurrentPhysics = {},
            lockingDistanceMeters = 5.0,
            findBlockingTrainIds = { intervals ->
                queriedIntervals = intervals
                setOf("train-1")
            }
        )

        val result = runtime.select(Any(), fixture.request(fixture.first))

        assertEquals(ManualTurnoutSelectionStatus.PHYSICALLY_OCCUPIED, result.status)
        assertEquals(listOf("train-1"), result.blockingTrainIds)
        assertEquals(3, queriedIntervals.size)
        assertTrue(queriedIntervals.all { it.maxArcLengthInAbMeters - it.minArcLengthInAbMeters == 5.0 })
        assertSame(fixture.second.viewAB, fixture.stem.viewAB.nextActiveRail)
        assertTrue(fixture.registry.claimSnapshots().isEmpty())
    }

    @Test
    fun `stale topology request changes nothing`() {
        val fixture = turnout()
        val request = fixture.request(fixture.first).copy(
            topologyRevision = fixture.world.topologyState.revision - 1L
        )

        val result = runtime(fixture) {}.select(Any(), request)

        assertEquals(ManualTurnoutSelectionStatus.STALE_TOPOLOGY, result.status)
        assertSame(fixture.second.viewAB, fixture.stem.viewAB.nextActiveRail)
        assertTrue(fixture.registry.claimSnapshots().isEmpty())
    }

    private fun runtime(fixture: Fixture, awaitPhysics: () -> Unit) =
        ManualTurnoutSelectionRuntime(
            fixture.world,
            fixture.registry,
            awaitPhysics,
            lockingDistanceMeters = 5.0
        )

    private fun turnout(): Fixture {
        val world = createTestWorld().also(worlds::add)
        val stem = rail(world, Vector3d(-10.0, 0.0, 0.0), Vector3d(0.0, 0.0, 0.0))
        val first = rail(world, Vector3d(0.0, 0.0, 0.0), Vector3d(10.0, 0.0, -5.0))
        val second = rail(world, Vector3d(0.0, 0.0, 0.0), Vector3d(10.0, 0.0, 5.0))
        CoreRail.connect(stem, first)
        CoreRail.connect(stem, second)
        val turnout = world.topologyState.nodes.values.filterIsInstance<TopologyNode.Turnout>().single()
        val resource = TurnoutResourceIdentity.fromPorts(turnout.ports)
        val registry = InfrastructureCoordinationRegistry(
            currentTopologyRevision = { world.topologyState.revision },
            resourceExists = { it == resource }
        )
        return Fixture(world, stem, first, second, resource, registry)
    }

    private fun rail(world: CoreWorld, from: Vector3d, to: Vector3d): CoreRail =
        world.createRailForTest(straightCurve(from, to))

    private fun straightCurve(from: Vector3d, to: Vector3d): PrimeCurve {
        val tangent = Vector3d(to).sub(from).normalize()
        val normal = Vector3d(0.0, 1.0, 0.0)
        return PrimeCurve.fromPoints(listOf(
            Curve.RawPoint(from, tangent, normal, 0.0, Curve.SegmentType.LINE_START),
            Curve.RawPoint(to, tangent, normal, from.distance(to), Curve.SegmentType.LINE_END)
        ))
    }

    private data class Fixture(
        val world: CoreWorld,
        val stem: CoreRail,
        val first: CoreRail,
        val second: CoreRail,
        val resource: work.art1st.javarailways.basic.api.infrastructure.StableInfrastructureResource,
        val registry: InfrastructureCoordinationRegistry
    ) {
        fun request(branch: CoreRail) = ManualTurnoutSelectionRequest(
            world.topologyState.revision,
            RailEndpointRef(stem.id, RailEndpoint.B),
            RailEndpointRef(branch.id, RailEndpoint.A)
        )
    }
}
