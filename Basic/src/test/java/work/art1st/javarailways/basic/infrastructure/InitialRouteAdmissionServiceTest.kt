package work.art1st.javarailways.basic.infrastructure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner
import work.art1st.javarailways.basic.api.infrastructure.RailIntervalResource
import work.art1st.javarailways.basic.api.infrastructure.StableInfrastructureResource
import work.art1st.javarailways.basic.api.observation.RailwayObservation
import work.art1st.javarailways.basic.api.route.AdditionalResourceRequirement
import work.art1st.javarailways.basic.api.route.RouteAdmissionFailure
import work.art1st.javarailways.basic.api.route.RouteAdmissionFailureCode
import work.art1st.javarailways.basic.api.route.RouteAdmissionResult
import work.art1st.javarailways.basic.api.route.RouteBoundary
import work.art1st.javarailways.basic.api.route.RouteResourceIntent
import work.art1st.javarailways.core.api.physics.RailwayWorldFrameSnapshot
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.train.SweptRailSpanSnapshot
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot
import work.art1st.javarailways.core.physics.rail.topology.TestTopologyRail
import work.art1st.javarailways.core.physics.rail.topology.buildTestTopology
import java.util.UUID

class InitialRouteAdmissionServiceTest {
    private val railId = UUID(0L, 101L)
    private val block = StableInfrastructureResource("test:block", "main")
    private val hostClaimOwner = Any()
    private var topology = buildTestTopology(7L, listOf(TestTopologyRail(railId, 100.0)), emptyMap())
    private var observation = observation(7L, train("first", 0.0, 1.0, 7L))
    private val registry = InfrastructureLeaseRegistry(
        currentTopologyRevision = { topology.revision },
        resourceExists = { resource -> resource == block || resource is RailIntervalResource && resource.railId == railId }
    )
    private val catalog = RouteBoundResourceCatalog { topology }.also {
        assertEquals(RouteBoundResourceCatalogUpdate.Published, it.publish(topology, emptyList()))
    }
    private var additionValidation: (AdditionalResourceRequirement) -> RouteAdmissionFailure? = { null }

    @Test
    fun `one admission claims physical rail and approved block then releases after the tail`() {
        val platformOwner = Any()
        val service = service()
        val intent = intent(additions = listOf(AdditionalResourceRequirement(block, RouteBoundary(20.0))))
        val grant = assertInstanceOf(RouteAdmissionResult.Granted::class.java,
            service.admit(platformOwner, intent)).grant

        assertEquals(RouteBoundary(20.0), grant.committedAuthority)
        assertEquals(
            setOf(RailIntervalResource(railId, 0.0, 20.0), block),
            registry.claimSnapshots().single().footprint.resources.toSet()
        )
        registry.releasePlatformOwner(platformOwner)
        assertEquals(1, registry.claimSnapshots().size)
        assertFalse(service.endGrant(platformOwner, grant.grantId))
        observation = observation(8L, train("first", 21.0, 22.0, 8L))
        assertInstanceOf(ProtectedRouteTailReleaseUpdate.Completed::class.java,
            service.consumeObservation(observation).single())
        assertTrue(registry.claimSnapshots().isEmpty())
        assertFalse(service.endGrant(Any(), grant.grantId))
        assertTrue(service.endGrant(platformOwner, grant.grantId))
    }

    @Test
    fun `denied addition occupancy and claim conflict do not make a partial claim`() {
        val platformOwner = Any()
        val service = service()
        val withBlock = intent(additions = listOf(AdditionalResourceRequirement(block, RouteBoundary(20.0))))
        additionValidation = {
            RouteAdmissionFailure(RouteAdmissionFailureCode.INVALID_ADDITION, "owner denied block")
        }
        val denied = assertInstanceOf(RouteAdmissionResult.Rejected::class.java,
            service.admit(platformOwner, withBlock))
        assertEquals(RouteAdmissionFailureCode.INVALID_ADDITION, denied.failure.code)
        assertTrue(registry.claimSnapshots().isEmpty())
        additionValidation = { null }

        observation = observation(8L, train("first", 0.0, 1.0, 8L), train("second", 5.0, 6.0, 8L))
        val occupied = assertInstanceOf(RouteAdmissionResult.Rejected::class.java,
            service.admit(platformOwner, intent(additions = emptyList())))
        assertEquals(RouteAdmissionFailureCode.PHYSICALLY_OCCUPIED, occupied.failure.code)
        assertTrue(registry.claimSnapshots().isEmpty())

        observation = observation(9L, train("first", 0.0, 1.0, 9L))
        val first = assertInstanceOf(RouteAdmissionResult.Granted::class.java,
            service.admit(platformOwner, intent(additions = emptyList())))
        val conflict = assertInstanceOf(RouteAdmissionResult.Rejected::class.java,
            service.admit(Any(), intent(ownerId = "test:route/other", additions = emptyList())))
        assertEquals(RouteAdmissionFailureCode.RESOURCE_CONFLICT, conflict.failure.code)
        assertEquals(first.grant.owner, registry.claimSnapshots().single().owner)
    }

    @Test
    fun `resource owner validation is host bound and exceptions reject before claim`() {
        val platformOwner = Any()
        val service = InitialRouteAdmissionService(
            "world", registry, hostClaimOwner, { topology }, { observation }, catalog,
            { actualOwner, addition ->
                assertSame(platformOwner, actualOwner)
                assertEquals(block, addition.resource)
                error("resource owner unavailable")
            },
            InfrastructurePreparationActuator { error("must not prepare") },
            { _, _ -> error("must not stall") }
        )
        val result = assertInstanceOf(RouteAdmissionResult.Rejected::class.java,
            service.admit(platformOwner, intent(additions = listOf(
                AdditionalResourceRequirement(block, RouteBoundary(20.0))
            ))))
        assertEquals(RouteAdmissionFailureCode.INVALID_ADDITION, result.failure.code)
        assertTrue(registry.claimSnapshots().isEmpty())
    }

    @Test
    fun `invalid train tail rejects before calling added resource provider`() {
        observation = observation(8L, train("first", 2.0, 3.0, 8L))
        var providerCalled = false
        val service = InitialRouteAdmissionService(
            "world", registry, hostClaimOwner, { topology }, { observation }, catalog,
            { _, _ -> providerCalled = true; null },
            InfrastructurePreparationActuator { error("must not prepare") },
            { _, _ -> error("must not stall") }
        )

        val result = assertInstanceOf(RouteAdmissionResult.Rejected::class.java,
            service.admit(Any(), intent(additions = listOf(
                AdditionalResourceRequirement(block, RouteBoundary(20.0))
            ))))

        assertEquals(RouteAdmissionFailureCode.INVALID_ROUTE, result.failure.code)
        assertFalse(providerCalled)
        assertTrue(registry.claimSnapshots().isEmpty())
    }

    @Test
    fun `pending preparation cannot grant before ready and safe abort removes only its claim`() {
        val ticket = MutableTicket()
        val service = service(InfrastructurePreparationActuator { InfrastructurePreparationStart.Accepted(ticket) })
        val platformOwner = Any()
        val pending = assertInstanceOf(RouteAdmissionResult.Pending::class.java,
            service.admit(platformOwner, intent()))
        assertEquals(1, registry.claimSnapshots().size)
        assertSame(pending.handle, assertInstanceOf(RouteAdmissionResult.Pending::class.java,
            service.progress(pending.handle)).handle)

        ticket.state = InfrastructurePreparationState.ABORTED_SAFE
        val rejected = assertInstanceOf(RouteAdmissionResult.Rejected::class.java, service.progress(pending.handle))
        assertEquals(RouteAdmissionFailureCode.PREPARATION_ABORTED_SAFE, rejected.failure.code)
        assertSame(rejected, service.progress(pending.handle))
        assertTrue(registry.claimSnapshots().isEmpty())

        ticket.state = InfrastructurePreparationState.READY
        assertInstanceOf(RouteAdmissionResult.Granted::class.java,
            service.admit(platformOwner, intent()))
    }

    @Test
    fun `pending preparation publishes exactly one grant only after ready`() {
        val ticket = MutableTicket()
        val service = service(InfrastructurePreparationActuator { InfrastructurePreparationStart.Accepted(ticket) })
        val pending = assertInstanceOf(RouteAdmissionResult.Pending::class.java,
            service.admit(Any(), intent()))
        assertEquals(1, registry.claimSnapshots().size)
        assertInstanceOf(RouteAdmissionResult.Pending::class.java, service.progress(pending.handle))

        ticket.state = InfrastructurePreparationState.READY
        val granted = assertInstanceOf(RouteAdmissionResult.Granted::class.java, service.progress(pending.handle))
        assertSame(granted, service.progress(pending.handle))
        assertEquals(1, registry.claimSnapshots().size)
    }

    @Test
    fun `ready on a newer safe epoch grants with the current coherent stamp`() {
        val ticket = MutableTicket()
        val service = service(InfrastructurePreparationActuator { InfrastructurePreparationStart.Accepted(ticket) })
        val pending = assertInstanceOf(RouteAdmissionResult.Pending::class.java, service.admit(Any(), intent()))
        observation = observation(8L, train("first", 0.0, 1.0, 8L))

        ticket.state = InfrastructurePreparationState.READY
        val grant = assertInstanceOf(RouteAdmissionResult.Granted::class.java,
            service.progress(pending.handle)).grant

        assertEquals(observation.stamp, grant.observationStamp)
        assertInstanceOf(ProtectedRouteTailReleaseUpdate.Active::class.java,
            service.consumeObservation(observation(9L, train("first", 0.0, 1.0, 9L))).single())
    }

    @Test
    fun `ready with newly occupied route stalls and retains its claim`() {
        val ticket = MutableTicket()
        var stalls = 0
        val service = InitialRouteAdmissionService(
            "world", registry, hostClaimOwner, { topology }, { observation }, catalog,
            { _, _ -> null },
            InfrastructurePreparationActuator { InfrastructurePreparationStart.Accepted(ticket) },
            { _, _ -> stalls++ }
        )
        val pending = assertInstanceOf(RouteAdmissionResult.Pending::class.java, service.admit(Any(), intent()))
        observation = observation(8L, train("first", 0.0, 1.0, 8L), train("second", 5.0, 6.0, 8L))

        ticket.state = InfrastructurePreparationState.READY
        val failed = assertInstanceOf(RouteAdmissionResult.Rejected::class.java, service.progress(pending.handle))

        assertEquals(RouteAdmissionFailureCode.WORLD_STALLED, failed.failure.code)
        assertSame(failed, service.progress(pending.handle))
        assertEquals(1, stalls)
        assertEquals(1, registry.claimSnapshots().size)
        assertTrue(service.consumeObservation(observation).isEmpty())
    }

    @Test
    fun `ready after topology root replacement stalls even at the same revision`() {
        val ticket = MutableTicket()
        var stalls = 0
        val service = InitialRouteAdmissionService(
            "world", registry, hostClaimOwner, { topology }, { observation }, catalog,
            { _, _ -> null },
            InfrastructurePreparationActuator { InfrastructurePreparationStart.Accepted(ticket) },
            { _, _ -> stalls++ }
        )
        val pending = assertInstanceOf(RouteAdmissionResult.Pending::class.java, service.admit(Any(), intent()))
        topology = buildTestTopology(7L, listOf(TestTopologyRail(railId, 100.0)), emptyMap())
        observation = observation(8L, train("first", 0.0, 1.0, 8L))

        ticket.state = InfrastructurePreparationState.READY
        val failed = assertInstanceOf(RouteAdmissionResult.Rejected::class.java, service.progress(pending.handle))

        assertEquals(RouteAdmissionFailureCode.WORLD_STALLED, failed.failure.code)
        assertEquals(1, stalls)
        assertEquals(1, registry.claimSnapshots().size)
    }

    @Test
    fun `ready after train tail leaves route start stalls and retains its claim`() {
        val ticket = MutableTicket()
        var stalls = 0
        val service = InitialRouteAdmissionService(
            "world", registry, hostClaimOwner, { topology }, { observation }, catalog,
            { _, _ -> null },
            InfrastructurePreparationActuator { InfrastructurePreparationStart.Accepted(ticket) },
            { _, _ -> stalls++ }
        )
        val pending = assertInstanceOf(RouteAdmissionResult.Pending::class.java, service.admit(Any(), intent()))
        observation = observation(8L, train("first", 2.0, 3.0, 8L))

        ticket.state = InfrastructurePreparationState.READY
        val failed = assertInstanceOf(RouteAdmissionResult.Rejected::class.java, service.progress(pending.handle))

        assertEquals(RouteAdmissionFailureCode.WORLD_STALLED, failed.failure.code)
        assertEquals(1, stalls)
        assertEquals(1, registry.claimSnapshots().size)
    }

    @Test
    fun `another pending attempt cannot publish a grant after the world stalls`() {
        observation = observation(7L, train("first", 0.0, 1.0, 7L), train("second", 40.0, 41.0, 7L))
        val unsafeTicket = MutableTicket()
        val readyTicket = MutableTicket()
        var starts = 0
        var stalls = 0
        val service = InitialRouteAdmissionService(
            "world", registry, hostClaimOwner, { topology }, { observation }, catalog,
            { _, _ -> null },
            InfrastructurePreparationActuator {
                InfrastructurePreparationStart.Accepted(if (starts++ == 0) unsafeTicket else readyTicket)
            },
            { _, _ -> stalls++ }
        )
        val unsafe = assertInstanceOf(RouteAdmissionResult.Pending::class.java,
            service.admit(Any(), intent()))
        val ready = assertInstanceOf(RouteAdmissionResult.Pending::class.java,
            service.admit(Any(), intent(ownerId = "test:route/second", trainId = "second", routeStart = 40.0)))
        assertEquals(2, registry.claimSnapshots().size)

        unsafeTicket.state = InfrastructurePreparationState.UNSAFE
        assertEquals(RouteAdmissionFailureCode.WORLD_STALLED,
            assertInstanceOf(RouteAdmissionResult.Rejected::class.java,
                service.progress(unsafe.handle)).failure.code)
        readyTicket.state = InfrastructurePreparationState.READY
        val blocked = assertInstanceOf(RouteAdmissionResult.Rejected::class.java, service.progress(ready.handle))

        assertEquals(RouteAdmissionFailureCode.WORLD_STALLED, blocked.failure.code)
        assertSame(blocked, service.progress(ready.handle))
        assertEquals(1, stalls)
        assertEquals(2, registry.claimSnapshots().size)
        assertTrue(service.consumeObservation(observation).isEmpty())
    }

    @Test
    fun `multiple unsafe pending attempts report the first world stall only`() {
        observation = observation(7L, train("first", 0.0, 1.0, 7L), train("second", 40.0, 41.0, 7L))
        val firstTicket = MutableTicket()
        val secondTicket = MutableTicket()
        var starts = 0
        var stalls = 0
        val service = InitialRouteAdmissionService(
            "world", registry, hostClaimOwner, { topology }, { observation }, catalog,
            { _, _ -> null },
            InfrastructurePreparationActuator {
                InfrastructurePreparationStart.Accepted(if (starts++ == 0) firstTicket else secondTicket)
            },
            { _, _ -> stalls++ }
        )
        val first = assertInstanceOf(RouteAdmissionResult.Pending::class.java,
            service.admit(Any(), intent()))
        val second = assertInstanceOf(RouteAdmissionResult.Pending::class.java,
            service.admit(Any(), intent(ownerId = "test:route/second", trainId = "second", routeStart = 40.0)))

        firstTicket.state = InfrastructurePreparationState.UNSAFE
        secondTicket.state = InfrastructurePreparationState.UNSAFE
        assertInstanceOf(RouteAdmissionResult.Rejected::class.java, service.progress(first.handle))
        assertInstanceOf(RouteAdmissionResult.Rejected::class.java, service.progress(second.handle))

        assertEquals(1, stalls)
        assertEquals(2, registry.claimSnapshots().size)
    }

    @Test
    fun `unsafe preparation stalls and retains the complete reservation`() {
        val ticket = MutableTicket()
        var stalls = 0
        val service = InitialRouteAdmissionService(
            "world", registry, hostClaimOwner, { topology }, { observation }, catalog,
            { _, _ -> null },
            InfrastructurePreparationActuator { InfrastructurePreparationStart.Accepted(ticket) },
            { _, _ -> stalls++ }
        )
        val pending = assertInstanceOf(RouteAdmissionResult.Pending::class.java,
            service.admit(Any(), intent()))
        ticket.state = InfrastructurePreparationState.UNSAFE
        val failed = assertInstanceOf(RouteAdmissionResult.Rejected::class.java, service.progress(pending.handle))
        assertEquals(RouteAdmissionFailureCode.WORLD_STALLED, failed.failure.code)
        assertSame(failed, service.progress(pending.handle))
        assertEquals(1, stalls)
        assertEquals(1, registry.claimSnapshots().size)
        assertEquals(RouteAdmissionFailureCode.WORLD_STALLED,
            assertInstanceOf(RouteAdmissionResult.Rejected::class.java,
                service.admit(Any(), intent(ownerId = "test:route/after-stall"))).failure.code)
    }

    @Test
    fun `stale observation and unavailable route-bound catalog fail without a claim`() {
        val service = service()
        val oldIntent = intent()
        observation = observation(8L, train("first", 0.0, 1.0, 8L))
        assertEquals(RouteAdmissionFailureCode.STALE_OBSERVATION,
            assertInstanceOf(RouteAdmissionResult.Rejected::class.java,
                service.admit(Any(), oldIntent)).failure.code)
        val unavailable = InitialRouteAdmissionService(
            "world", registry, hostClaimOwner, { topology }, { observation }, RouteBoundResourceCatalog { topology },
            { _, _ -> null },
            InfrastructurePreparationActuator { error("must not prepare") }, { _, _ -> error("must not stall") }
        )
        assertEquals(RouteAdmissionFailureCode.STALE_TOPOLOGY,
            assertInstanceOf(RouteAdmissionResult.Rejected::class.java,
                unavailable.admit(Any(), intent())).failure.code)
        assertTrue(registry.claimSnapshots().isEmpty())
    }

    private fun service(
        actuator: InfrastructurePreparationActuator = InfrastructurePreparationActuator {
            InfrastructurePreparationStart.Accepted(MutableTicket(InfrastructurePreparationState.READY))
        }
    ) = InitialRouteAdmissionService(
        "world", registry, hostClaimOwner, { topology }, { observation }, catalog,
        { _, addition -> additionValidation(addition) }, actuator,
        { _, _ -> error("unexpected world stall") }
    )

    private fun intent(
        ownerId: String = "test:route/main",
        additions: List<AdditionalResourceRequirement> = emptyList(),
        trainId: String = "first",
        routeStart: Double = 0.0
    ) = RouteResourceIntent(
        "test:request/main", InfrastructureOwner(ownerId, 1L), trainId, observation.stamp,
        RouteSnapshot("test:route/main", 1L, topology.revision,
            listOf(RouteSegmentSnapshot(railId, ViewDirection.AB, routeStart, 100.0))),
        RouteBoundary(20.0), additions
    )

    private fun observation(epoch: Long, vararg trains: TrainFrameSnapshot) = RailwayObservation.fromCore(
        "world", RailwayWorldFrameSnapshot.create(epoch, topology.revision, trains.toList())
    )

    private fun train(id: String, tail: Double, head: Double, epoch: Long) = TrainFrameSnapshot.create(
        trainId = id, trainName = id, revision = 1L, physicsEpoch = epoch,
        speedMetersPerSecond = 0.0, derailed = false, reversed = false, cars = emptyList(),
        head = RailPositionSnapshot(railId, ViewDirection.AB, head),
        tail = RailPositionSnapshot(railId, ViewDirection.AB, tail),
        sweptSpans = listOf(SweptRailSpanSnapshot(railId, tail, head))
    )

    private class MutableTicket(var state: InfrastructurePreparationState = InfrastructurePreparationState.PREPARING) :
        InfrastructurePreparationTicket {
        override fun snapshot() = InfrastructurePreparationSnapshot(
            state, if (state == InfrastructurePreparationState.UNSAFE) "device state unknown" else ""
        )
        override fun abort() = Unit
    }
}
