package work.art1st.javarailways.basic.infrastructure

import org.joml.Vector3d
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.infrastructure.StableInfrastructureResource
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.train.createTestWorld

class LiveInfrastructurePreparationActuatorTest {
    private val worlds = mutableListOf<CoreWorld>()

    @AfterEach
    fun closeWorlds() {
        worlds.forEach { world ->
            world.physicsCoordinator.close()
            (world.tickManager as? AutoCloseable)?.close()
        }
    }

    @Test
    fun `zero selections are immediately ready`() {
        val world = world()
        val actuator = LiveInfrastructurePreparationActuator(world)

        assertEquals(InfrastructurePreparationState.READY, accepted(actuator.start(emptyList())).snapshot().state)
    }

    @Test
    fun `reverse traversal aligns both stored directions of the same physical turnout`() {
        val fixture = turnout()
        fixture.stem.viewAB.selectNextRail(fixture.first.viewAB)
        fixture.second.viewBA.selectNextRail(null)

        val ticket = accepted(
            LiveInfrastructurePreparationActuator(fixture.world).start(
                listOf(fixture.selection(fixture.second, fixture.stem))
            )
        )

        assertEquals(InfrastructurePreparationState.READY, ticket.snapshot().state)
        assertSame(fixture.second.viewAB, fixture.stem.viewAB.nextActiveRail)
        assertSame(fixture.stem.viewBA, fixture.second.viewBA.nextActiveRail)
    }

    @Test
    fun `already aligned selection remains ready and aligned`() {
        val fixture = turnout()
        fixture.stem.viewAB.selectNextRail(fixture.second.viewAB)
        fixture.second.viewBA.selectNextRail(fixture.stem.viewBA)
        val actuator = LiveInfrastructurePreparationActuator(fixture.world)

        val ticket = accepted(actuator.start(listOf(fixture.selection(fixture.stem, fixture.second))))

        assertEquals(InfrastructurePreparationState.READY, ticket.snapshot().state)
        assertSame(fixture.second.viewAB, fixture.stem.viewAB.nextActiveRail)
        assertSame(fixture.stem.viewBA, fixture.second.viewBA.nextActiveRail)
    }

    @Test
    fun `multiple turnouts are aligned by one immediate batch`() {
        val world = world()
        val firstTurnout = turnout(world, 0.0)
        val secondTurnout = turnout(world, 100.0)
        firstTurnout.stem.viewAB.selectNextRail(firstTurnout.first.viewAB)
        secondTurnout.stem.viewAB.selectNextRail(secondTurnout.first.viewAB)
        val actuator = LiveInfrastructurePreparationActuator(world)

        val ticket = accepted(actuator.start(listOf(
            firstTurnout.selection(firstTurnout.stem, firstTurnout.second),
            secondTurnout.selection(secondTurnout.stem, secondTurnout.second)
        )))

        assertEquals(InfrastructurePreparationState.READY, ticket.snapshot().state)
        assertSame(firstTurnout.second.viewAB, firstTurnout.stem.viewAB.nextActiveRail)
        assertSame(secondTurnout.second.viewAB, secondTurnout.stem.viewAB.nextActiveRail)
    }

    @Test
    fun `resolution rejection occurs before any live write`() {
        val fixture = turnout()
        fixture.stem.viewAB.selectNextRail(fixture.first.viewAB)
        val actuator = LiveInfrastructurePreparationActuator(fixture.world)
        val missing = fixture.selection(fixture.stem, fixture.second).copy(
            toPort = RailEndpointRef(java.util.UUID.randomUUID(), RailEndpoint.A)
        )

        val result = actuator.start(listOf(missing))

        assertTrue(result is InfrastructurePreparationStart.Rejected)
        assertSame(fixture.first.viewAB, fixture.stem.viewAB.nextActiveRail)
    }

    @Test
    fun `unsupported requirement rejects whole batch before an earlier turnout write`() {
        val fixture = turnout()
        fixture.stem.viewAB.selectNextRail(fixture.first.viewAB)
        val actuator = LiveInfrastructurePreparationActuator(fixture.world)
        val unsupported = object : InfrastructurePreparationRequirement {
            override val resource = StableInfrastructureResource("test:device", "test:unsupported")
        }

        val result = actuator.start(listOf(
            fixture.selection(fixture.stem, fixture.second),
            unsupported
        ))

        assertTrue(result is InfrastructurePreparationStart.Rejected)
        assertSame(fixture.first.viewAB, fixture.stem.viewAB.nextActiveRail)
    }

    @Test
    fun `terminal ticket abort is idempotent no-op`() {
        val fixture = turnout()
        val ticket = accepted(
            LiveInfrastructurePreparationActuator(fixture.world).start(
                listOf(fixture.selection(fixture.stem, fixture.second))
            )
        )

        ticket.abort()
        ticket.abort()

        assertEquals(InfrastructurePreparationState.READY, ticket.snapshot().state)
        assertSame(fixture.second.viewAB, fixture.stem.viewAB.nextActiveRail)
    }

    private fun accepted(start: InfrastructurePreparationStart): InfrastructurePreparationTicket =
        (start as InfrastructurePreparationStart.Accepted).ticket

    private fun turnout(): Fixture = turnout(world(), 0.0)

    private fun turnout(world: CoreWorld, offset: Double): Fixture {
        val stem = rail(world, Vector3d(offset - 10.0, 0.0, 0.0), Vector3d(offset, 0.0, 0.0))
        val first = rail(world, Vector3d(offset, 0.0, 0.0), Vector3d(offset + 10.0, 0.0, -5.0))
        val second = rail(world, Vector3d(offset, 0.0, 0.0), Vector3d(offset + 10.0, 0.0, 5.0))
        CoreRail.connect(stem, first)
        CoreRail.connect(stem, second)
        return Fixture(world, stem, first, second)
    }

    private fun world(): CoreWorld = createTestWorld().also(worlds::add)

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

    private data class Fixture(
        val world: CoreWorld,
        val stem: CoreRail,
        val first: CoreRail,
        val second: CoreRail
    ) {
        fun selection(from: CoreRail, to: CoreRail): ResolvedTurnoutSelection {
            val fromPort = RailEndpointRef(
                from.id,
                if (from === stem) RailEndpoint.B else RailEndpoint.A
            )
            val toPort = RailEndpointRef(
                to.id,
                if (to === stem) RailEndpoint.B else RailEndpoint.A
            )
            return ResolvedTurnoutSelection(
                TurnoutResourceIdentity.fromPorts(listOf(
                    RailEndpointRef(stem.id, RailEndpoint.B),
                    RailEndpointRef(first.id, RailEndpoint.A),
                    RailEndpointRef(second.id, RailEndpoint.A)
                )),
                fromPort,
                toPort,
                10.0
            )
        }
    }
}
