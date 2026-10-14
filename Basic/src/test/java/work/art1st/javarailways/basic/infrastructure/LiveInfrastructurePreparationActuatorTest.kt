package work.art1st.javarailways.basic.infrastructure

import work.art1st.javarailways.core.util.IsolatedSimulationThreadMarker

import work.art1st.javarailways.core.physics.createRailForTest

import work.art1st.javarailways.basic.api.route.ResourcePreparationState as InfrastructurePreparationState
import work.art1st.javarailways.basic.api.route.ResourcePreparationTicket as InfrastructurePreparationTicket

import org.joml.Vector3d
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.infrastructure.StableInfrastructureResource
import work.art1st.javarailways.basic.api.route.ResourcePreparationDescriptor
import work.art1st.javarailways.basic.api.route.ResourcePreparationSnapshot
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
    fun `connected route aligns both turnouts in one immediate batch`() {
        val fixture = connectedTwoTurnoutRoute()
        fixture.entry.viewAB.selectNextRail(fixture.firstBranch.viewAB)
        fixture.firstBranch.viewBA.selectNextRail(fixture.entry.viewBA)
        fixture.middle.viewAB.selectNextRail(fixture.secondBranch.viewAB)
        fixture.secondBranch.viewBA.selectNextRail(fixture.middle.viewBA)
        val actuator = LiveInfrastructurePreparationActuator(fixture.world)

        val ticket = accepted(actuator.start(listOf(
            fixture.firstSelection(),
            fixture.secondSelection()
        )))

        assertEquals(InfrastructurePreparationState.READY, ticket.snapshot().state)
        assertSame(fixture.middle.viewAB, fixture.entry.viewAB.nextActiveRail)
        assertSame(fixture.entry.viewBA, fixture.middle.viewBA.nextActiveRail)
        assertSame(fixture.exit.viewAB, fixture.middle.viewAB.nextActiveRail)
        assertSame(fixture.middle.viewBA, fixture.exit.viewBA.nextActiveRail)
    }

    @Test
    fun `invalid second turnout rejects connected batch before first turnout changes`() {
        val fixture = connectedTwoTurnoutRoute()
        fixture.entry.viewAB.selectNextRail(fixture.firstBranch.viewAB)
        fixture.firstBranch.viewBA.selectNextRail(fixture.entry.viewBA)
        fixture.middle.viewAB.selectNextRail(fixture.secondBranch.viewAB)
        fixture.secondBranch.viewBA.selectNextRail(fixture.middle.viewBA)
        val missingSecondPort = fixture.secondSelection().copy(
            toPort = RailEndpointRef(java.util.UUID.randomUUID(), RailEndpoint.A)
        )

        val result = LiveInfrastructurePreparationActuator(fixture.world).start(listOf(
            fixture.firstSelection(),
            missingSecondPort
        ))

        assertTrue(result is InfrastructurePreparationStart.Rejected)
        assertSame(fixture.firstBranch.viewAB, fixture.entry.viewAB.nextActiveRail)
        assertSame(fixture.entry.viewBA, fixture.firstBranch.viewBA.nextActiveRail)
        assertSame(fixture.secondBranch.viewAB, fixture.middle.viewAB.nextActiveRail)
        assertSame(fixture.middle.viewBA, fixture.secondBranch.viewBA.nextActiveRail)
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
    fun `contributed preparations share one aggregate ticket with physical turnout work`() {
        val fixture = turnout()
        fixture.stem.viewAB.selectNextRail(fixture.first.viewAB)
        val firstResource = StableInfrastructureResource("test:device", "first")
        val secondResource = StableInfrastructureResource("test:device", "second")
        val descriptor = ResourcePreparationDescriptor("test:align", emptyMap())
        val firstTicket = MutableTicket()
        val secondTicket = MutableTicket()
        val actuator = LiveInfrastructurePreparationActuator(fixture.world) { resource, actualDescriptor ->
            assertEquals(descriptor, actualDescriptor)
            when (resource) {
                firstResource -> ({ firstTicket })
                secondResource -> ({ secondTicket })
                else -> null
            }
        }

        val ticket = accepted(actuator.start(listOf(
            fixture.selection(fixture.stem, fixture.second),
            ResolvedContributedResourcePreparation(firstResource, descriptor),
            ResolvedContributedResourcePreparation(secondResource, descriptor)
        )))

        assertSame(fixture.second.viewAB, fixture.stem.viewAB.nextActiveRail)
        assertEquals(InfrastructurePreparationState.PREPARING, ticket.snapshot().state)
        firstTicket.state = InfrastructurePreparationState.READY
        assertEquals(InfrastructurePreparationState.PREPARING, ticket.snapshot().state)
        secondTicket.state = InfrastructurePreparationState.READY
        assertEquals(InfrastructurePreparationState.READY, ticket.snapshot().state)
    }

    @Test
    fun `missing contributed handler rejects before turnout work starts`() {
        val fixture = turnout()
        fixture.stem.viewAB.selectNextRail(fixture.first.viewAB)
        val unsupported = ResolvedContributedResourcePreparation(
            StableInfrastructureResource("test:device", "missing"),
            ResourcePreparationDescriptor("test:align", emptyMap())
        )

        val result = LiveInfrastructurePreparationActuator(fixture.world).start(listOf(
            fixture.selection(fixture.stem, fixture.second),
            unsupported
        ))

        assertTrue(result is InfrastructurePreparationStart.Rejected)
        assertSame(fixture.first.viewAB, fixture.stem.viewAB.nextActiveRail)
    }

    @Test
    fun `aggregate abort asks only preparing children and becomes safely aborted`() {
        val world = world()
        val ready = MutableTicket(InfrastructurePreparationState.READY)
        val preparing = MutableTicket()
        val descriptor = ResourcePreparationDescriptor("test:align", emptyMap())
        val actuator = LiveInfrastructurePreparationActuator(world) { resource, _ ->
            when (resource.resourceId) {
                "ready" -> ({ ready })
                "preparing" -> ({ preparing })
                else -> null
            }
        }
        val ticket = accepted(actuator.start(listOf(
            ResolvedContributedResourcePreparation(
                StableInfrastructureResource("test:device", "ready"), descriptor
            ),
            ResolvedContributedResourcePreparation(
                StableInfrastructureResource("test:device", "preparing"), descriptor
            )
        )))

        ticket.abort()

        assertEquals(0, ready.abortCalls)
        assertEquals(1, preparing.abortCalls)
        assertEquals(InfrastructurePreparationState.ABORTED_SAFE, ticket.snapshot().state)
        ticket.abort()
        assertEquals(1, preparing.abortCalls)
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

    private class MutableTicket(
        var state: InfrastructurePreparationState = InfrastructurePreparationState.PREPARING
    ) : InfrastructurePreparationTicket {
        var abortCalls = 0

        override fun snapshot() = ResourcePreparationSnapshot(state)

        override fun abort() {
            abortCalls++
            if (state == InfrastructurePreparationState.PREPARING) {
                state = InfrastructurePreparationState.ABORTED_SAFE
            }
        }
    }

    private fun turnout(): Fixture = turnout(world(), 0.0)

    private fun connectedTwoTurnoutRoute(): ConnectedTwoTurnoutFixture {
        val world = world()
        val entry = rail(world, Vector3d(-10.0, 0.0, 0.0), Vector3d(0.0, 0.0, 0.0))
        val middle = rail(world, Vector3d(0.0, 0.0, 0.0), Vector3d(10.0, 0.0, 0.0))
        val firstBranch = rail(world, Vector3d(0.0, 0.0, 0.0), Vector3d(10.0, 0.0, 5.0))
        val exit = rail(world, Vector3d(10.0, 0.0, 0.0), Vector3d(20.0, 0.0, 0.0))
        val secondBranch = rail(world, Vector3d(10.0, 0.0, 0.0), Vector3d(20.0, 0.0, 5.0))
        IsolatedSimulationThreadMarker.run { CoreRail.connect(entry, middle) }
        IsolatedSimulationThreadMarker.run { CoreRail.connect(entry, firstBranch) }
        IsolatedSimulationThreadMarker.run { CoreRail.connect(middle, exit) }
        IsolatedSimulationThreadMarker.run { CoreRail.connect(middle, secondBranch) }
        return ConnectedTwoTurnoutFixture(world, entry, middle, firstBranch, exit, secondBranch)
    }

    private fun turnout(world: CoreWorld, offset: Double): Fixture {
        val stem = rail(world, Vector3d(offset - 10.0, 0.0, 0.0), Vector3d(offset, 0.0, 0.0))
        val first = rail(world, Vector3d(offset, 0.0, 0.0), Vector3d(offset + 10.0, 0.0, -5.0))
        val second = rail(world, Vector3d(offset, 0.0, 0.0), Vector3d(offset + 10.0, 0.0, 5.0))
        IsolatedSimulationThreadMarker.run { CoreRail.connect(stem, first) }
        IsolatedSimulationThreadMarker.run { CoreRail.connect(stem, second) }
        return Fixture(world, stem, first, second)
    }

    private fun world(): CoreWorld = createTestWorld().also(worlds::add)

    private fun rail(world: CoreWorld, from: Vector3d, to: Vector3d): CoreRail =
        world.createRailForTest(straightCurve(from, to))

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

    private data class ConnectedTwoTurnoutFixture(
        val world: CoreWorld,
        val entry: CoreRail,
        val middle: CoreRail,
        val firstBranch: CoreRail,
        val exit: CoreRail,
        val secondBranch: CoreRail
    ) {
        fun firstSelection() = selection(entry, middle, firstBranch, 10.0)

        fun secondSelection() = selection(middle, exit, secondBranch, 20.0)

        private fun selection(
            stem: CoreRail,
            selected: CoreRail,
            branch: CoreRail,
            routeDistanceMeters: Double
        ) = ResolvedTurnoutSelection(
            TurnoutResourceIdentity.fromPorts(listOf(
                RailEndpointRef(stem.id, RailEndpoint.B),
                RailEndpointRef(selected.id, RailEndpoint.A),
                RailEndpointRef(branch.id, RailEndpoint.A)
            )),
            RailEndpointRef(stem.id, RailEndpoint.B),
            RailEndpointRef(selected.id, RailEndpoint.A),
            routeDistanceMeters
        )
    }
}
