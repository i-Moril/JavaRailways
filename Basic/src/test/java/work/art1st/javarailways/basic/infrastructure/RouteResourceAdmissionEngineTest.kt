package work.art1st.javarailways.basic.infrastructure

import work.art1st.javarailways.basic.api.route.ResourcePreparationSnapshot as InfrastructurePreparationSnapshot
import work.art1st.javarailways.basic.api.route.ResourcePreparationState as InfrastructurePreparationState
import work.art1st.javarailways.basic.api.route.ResourcePreparationTicket as InfrastructurePreparationTicket

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
import work.art1st.javarailways.basic.api.route.ContributedRouteResource
import work.art1st.javarailways.basic.api.route.RouteAdmissionFailure
import work.art1st.javarailways.basic.api.route.RouteAdmissionFailureCode
import work.art1st.javarailways.basic.api.route.RouteAdmissionResult
import work.art1st.javarailways.basic.api.route.RouteBoundary
import work.art1st.javarailways.basic.api.route.RouteResourceIntent
import work.art1st.javarailways.basic.api.route.ResourcePreparationDescriptor
import work.art1st.javarailways.core.api.physics.RailwayWorldFrameSnapshot
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.RailInterval
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.train.SweptRailSpanSnapshot
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot
import work.art1st.javarailways.core.physics.rail.topology.TestTopologyRail
import work.art1st.javarailways.core.physics.rail.topology.buildTestTopology
import java.util.UUID
import java.util.IdentityHashMap

class RouteResourceAdmissionEngineTest {
    private val railId = UUID(0L, 101L)
    private val block = StableInfrastructureResource("test:block", "main")
    private val hostClaimOwner = Any()
    private val contributorOwner = Any()
    private val contributionsByIntent = IdentityHashMap<RouteResourceIntent, List<ContributedRouteResource>>()
    private var topology = buildTestTopology(7L, listOf(TestTopologyRail(railId, 100.0)), emptyMap())
    private var observation = observation(7L, train("first", 0.0, 1.0, 7L))
    private val registry = InfrastructureCoordinationRegistry(
        currentTopologyRevision = { topology.revision },
        resourceExists = { resource -> resource == block || resource is RailIntervalResource && resource.railId == railId }
    )
    private val catalog = RouteBoundResourceCatalog { topology }.also {
        assertEquals(RouteBoundResourceCatalogUpdate.Published, it.publish(topology, emptyList()))
    }
    private var additionValidation: (ContributedRouteResource) -> RouteAdmissionFailure? = { null }

    @Test
    fun `one admission claims physical rail and approved block then releases after the tail`() {
        val platformOwner = Any()
        val service = service()
        val intent = intent(contributions = listOf(ContributedRouteResource(block, RouteBoundary(20.0))))
        val grant = assertInstanceOf(RouteAdmissionResult.Granted::class.java,
            service.request(platformOwner, intent)).grant

        assertEquals(RouteBoundary(20.0), grant.committedAuthority)
        assertEquals(
            setOf(RailIntervalResource(railId, 0.0, 20.0), block),
            registry.claimSnapshots().single().footprint.resources.toSet()
        )
        registry.releasePlatformOwner(platformOwner)
        assertEquals(1, registry.claimSnapshots().size)
        assertFalse(service.endGrant(platformOwner, grant.grantId))
        assertEquals(1, service.requesterActivity(platformOwner).size)
        assertFalse(service.discardInactiveRequester(platformOwner))
        observation = observation(8L, train("first", 21.0, 22.0, 8L))
        assertInstanceOf(ProtectedRouteTailReleaseUpdate.Completed::class.java,
            service.consumeObservation(observation).single())
        assertTrue(registry.claimSnapshots().isEmpty())
        assertTrue(service.requesterActivity(platformOwner).isEmpty())
        assertFalse(service.endGrant(Any(), grant.grantId))
        assertTrue(service.discardInactiveRequester(platformOwner))
        assertFalse(service.endGrant(platformOwner, grant.grantId))
    }

    @Test
    fun `forced requester loss retains pending reservation and stalls once`() {
        val requester = Any()
        val ticket = MutableTicket()
        val stalls = mutableListOf<String>()
        val service = RouteResourceAdmissionEngine(
            "world", registry, hostClaimOwner, { topology }, { observation }, catalog,
            { _, contribution -> additionValidation(contribution) },
            InfrastructurePreparationActuator { InfrastructurePreparationStart.Accepted(ticket) },
            { reason, _ -> stalls += reason },
            ::authenticatedContributions
        )
        val pending = assertInstanceOf(
            RouteAdmissionResult.Pending::class.java,
            service.request(requester, intent(contributions = emptyList()))
        )

        val active = service.requesterActivity(requester).single()
        assertTrue(active.pendingRequest)
        assertTrue(active.heldResourceCount > 0)
        assertFalse(service.discardInactiveRequester(requester))

        assertEquals(active, service.failClosedRequesterLoss(requester, "test signal plugin").single())
        assertEquals(1, stalls.size)
        assertTrue(stalls.single().contains("test signal plugin"))
        assertTrue(stalls.single().contains(active.owner.ownerId))
        assertEquals(1, registry.claimSnapshots().size)

        service.failClosedRequesterLoss(requester, "test signal plugin")
        assertEquals(1, stalls.size)
        ticket.state = InfrastructurePreparationState.READY
        val snapshotsBeforeStalledProgress = ticket.snapshotCalls
        assertEquals(
            RouteAdmissionFailureCode.WORLD_STALLED,
            assertInstanceOf(RouteAdmissionResult.Rejected::class.java,
                service.progress(requester, pending.handle)).failure.code
        )
        assertEquals(snapshotsBeforeStalledProgress, ticket.snapshotCalls)
        assertEquals(1, registry.claimSnapshots().size)
    }

    @Test
    fun `provider loss finds another requesters pending added resource and stalls`() {
        val requester = Any()
        val ticket = MutableTicket()
        val stalls = mutableListOf<String>()
        val service = RouteResourceAdmissionEngine(
            "world", registry, hostClaimOwner, { topology }, { observation }, catalog,
            { _, contribution -> additionValidation(contribution) },
            InfrastructurePreparationActuator { InfrastructurePreparationStart.Accepted(ticket) },
            { reason, _ -> stalls += reason },
            ::authenticatedContributions
        )
        assertInstanceOf(
            RouteAdmissionResult.Pending::class.java,
            service.request(
                requester,
                intent(contributions = listOf(ContributedRouteResource(block, RouteBoundary(20.0))))
            )
        )

        val active = service.providerActivity(setOf(block.typeId)).single()
        assertEquals(setOf(block.typeId), active.resourceTypeIds)
        assertTrue(active.pendingRequest)

        assertEquals(
            active,
            service.failClosedProviderLoss(setOf(block.typeId), "test block provider").single()
        )
        assertEquals(1, stalls.size)
        assertTrue(stalls.single().contains("test block provider"))
        assertEquals(1, registry.claimSnapshots().size)
    }

    @Test
    fun `denied contribution occupancy and claim conflict do not make a partial claim`() {
        val platformOwner = Any()
        val service = service()
        val withBlock = intent(contributions = listOf(ContributedRouteResource(block, RouteBoundary(20.0))))
        additionValidation = {
            RouteAdmissionFailure(RouteAdmissionFailureCode.INVALID_CONTRIBUTION, "owner denied block")
        }
        val denied = assertInstanceOf(RouteAdmissionResult.Rejected::class.java,
            service.request(platformOwner, withBlock))
        assertEquals(RouteAdmissionFailureCode.INVALID_CONTRIBUTION, denied.failure.code)
        assertTrue(registry.claimSnapshots().isEmpty())
        additionValidation = { null }

        observation = observation(8L, train("first", 0.0, 1.0, 8L), train("second", 5.0, 6.0, 8L))
        val occupied = assertInstanceOf(RouteAdmissionResult.Rejected::class.java,
            service.request(platformOwner, intent(contributions = emptyList())))
        assertEquals(RouteAdmissionFailureCode.PHYSICALLY_OCCUPIED, occupied.failure.code)
        assertTrue(registry.claimSnapshots().isEmpty())

        observation = observation(9L, train("first", 0.0, 1.0, 9L))
        val first = assertInstanceOf(RouteAdmissionResult.Granted::class.java,
            service.request(platformOwner, intent(contributions = emptyList())))
        val conflict = assertInstanceOf(RouteAdmissionResult.Rejected::class.java,
            service.request(Any(), intent(ownerId = "test:route/other", contributions = emptyList())))
        assertEquals(RouteAdmissionFailureCode.RESOURCE_CONFLICT, conflict.failure.code)
        assertEquals(first.grant.owner, registry.claimSnapshots().single().owner)
    }

    @Test
    fun `resource owner validation is host bound and exceptions reject before claim`() {
        val platformOwner = Any()
        val service = RouteResourceAdmissionEngine(
            "world", registry, hostClaimOwner, { topology }, { observation }, catalog,
            { actualOwner, contribution ->
                assertSame(contributorOwner, actualOwner)
                assertEquals(block, contribution.resource)
                error("resource owner unavailable")
            },
            InfrastructurePreparationActuator { error("must not prepare") },
            { _, _ -> error("must not stall") },
            ::authenticatedContributions
        )
        val result = assertInstanceOf(RouteAdmissionResult.Rejected::class.java,
            service.request(platformOwner, intent(contributions = listOf(
                ContributedRouteResource(block, RouteBoundary(20.0))
            ))))
        assertEquals(RouteAdmissionFailureCode.INVALID_CONTRIBUTION, result.failure.code)
        assertTrue(registry.claimSnapshots().isEmpty())
    }

    @Test
    fun `invalid train tail rejects before calling added resource provider`() {
        observation = observation(8L, train("first", 2.0, 3.0, 8L))
        var providerCalled = false
        val service = RouteResourceAdmissionEngine(
            "world", registry, hostClaimOwner, { topology }, { observation }, catalog,
            { _, _ -> providerCalled = true; null },
            InfrastructurePreparationActuator { error("must not prepare") },
            { _, _ -> error("must not stall") },
            ::authenticatedContributions
        )

        val result = assertInstanceOf(RouteAdmissionResult.Rejected::class.java,
            service.request(Any(), intent(contributions = listOf(
                ContributedRouteResource(block, RouteBoundary(20.0))
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
            service.request(platformOwner, intent()))
        assertEquals(null, pending.committedGrant)
        assertEquals(1, registry.claimSnapshots().size)
        assertEquals(RouteAdmissionFailureCode.OWNER_MISMATCH,
            assertInstanceOf(RouteAdmissionResult.Rejected::class.java,
                service.progress(Any(), pending.handle)).failure.code)
        assertSame(pending.handle, assertInstanceOf(RouteAdmissionResult.Pending::class.java,
            service.progress(platformOwner, pending.handle)).handle)
        assertEquals(RouteAdmissionFailureCode.REQUEST_BUSY,
            assertInstanceOf(RouteAdmissionResult.Rejected::class.java,
                service.request(platformOwner, intent(authority = 40.0))).failure.code)

        ticket.state = InfrastructurePreparationState.ABORTED_SAFE
        val rejected = assertInstanceOf(RouteAdmissionResult.Rejected::class.java,
            service.progress(platformOwner, pending.handle))
        assertEquals(RouteAdmissionFailureCode.PREPARATION_ABORTED_SAFE, rejected.failure.code)
        assertEquals(RouteAdmissionFailureCode.OWNER_MISMATCH,
            assertInstanceOf(RouteAdmissionResult.Rejected::class.java,
                service.progress(Any(), pending.handle)).failure.code)
        assertSame(rejected, service.progress(platformOwner, pending.handle))
        assertTrue(registry.claimSnapshots().isEmpty())

        ticket.state = InfrastructurePreparationState.READY
        assertInstanceOf(RouteAdmissionResult.Granted::class.java,
            service.request(platformOwner, intent()))
    }

    @Test
    fun `rejected preparation clears an uncommitted route state for retry`() {
        var starts = 0
        val service = service(InfrastructurePreparationActuator {
            if (starts++ == 0) InfrastructurePreparationStart.Rejected(IllegalStateException("unavailable"))
            else InfrastructurePreparationStart.Accepted(MutableTicket(InfrastructurePreparationState.READY))
        })
        val requester = Any()
        val rejected = assertInstanceOf(RouteAdmissionResult.Rejected::class.java,
            service.request(requester, intent()))
        assertEquals(RouteAdmissionFailureCode.INVALID_PREPARATION, rejected.failure.code)
        assertTrue(registry.claimSnapshots().isEmpty())

        val granted = assertInstanceOf(RouteAdmissionResult.Granted::class.java,
            service.request(requester, intent()))
        assertEquals(RouteBoundary(20.0), granted.grant.committedAuthority)
    }

    @Test
    fun `one route state rejects changed requester owner train and frozen route`() {
        val requester = Any()
        val service = service()
        val first = assertInstanceOf(RouteAdmissionResult.Granted::class.java,
            service.request(requester, intent())).grant
        assertEquals(RouteAdmissionFailureCode.OWNER_MISMATCH,
            assertInstanceOf(RouteAdmissionResult.Rejected::class.java,
                service.request(Any(), intent(authority = 40.0))).failure.code)
        assertEquals(RouteAdmissionFailureCode.OWNER_MISMATCH,
            assertInstanceOf(RouteAdmissionResult.Rejected::class.java,
                service.request(requester, intent(authority = 40.0, ownerRevision = 2L))).failure.code)
        assertEquals(RouteAdmissionFailureCode.ROUTE_STATE_MISMATCH,
            assertInstanceOf(RouteAdmissionResult.Rejected::class.java,
                service.request(requester, intent(trainId = "other", authority = 40.0))).failure.code)
        assertEquals(RouteAdmissionFailureCode.ROUTE_STATE_MISMATCH,
            assertInstanceOf(RouteAdmissionResult.Rejected::class.java,
                service.request(requester, intent(authority = 40.0, routeStart = 1.0))).failure.code)
        assertEquals(RouteBoundary(20.0), first.committedAuthority)
        assertEquals(1, registry.claimSnapshots().size)
    }

    @Test
    fun `pending preparation publishes exactly one grant only after ready`() {
        val ticket = MutableTicket()
        val service = service(InfrastructurePreparationActuator { InfrastructurePreparationStart.Accepted(ticket) })
        val requester = Any()
        val pending = assertInstanceOf(RouteAdmissionResult.Pending::class.java,
            service.request(requester, intent()))
        assertEquals(1, registry.claimSnapshots().size)
        assertInstanceOf(RouteAdmissionResult.Pending::class.java, service.progress(requester, pending.handle))

        ticket.state = InfrastructurePreparationState.READY
        val granted = assertInstanceOf(RouteAdmissionResult.Granted::class.java,
            service.progress(requester, pending.handle))
        assertSame(granted, service.progress(requester, pending.handle))
        assertEquals(1, registry.claimSnapshots().size)
    }

    @Test
    fun `ready on a newer safe epoch grants with the current coherent stamp`() {
        val ticket = MutableTicket()
        val service = service(InfrastructurePreparationActuator { InfrastructurePreparationStart.Accepted(ticket) })
        val requester = Any()
        val pending = assertInstanceOf(RouteAdmissionResult.Pending::class.java,
            service.request(requester, intent()))
        observation = observation(8L, train("first", 0.0, 1.0, 8L))

        ticket.state = InfrastructurePreparationState.READY
        val grant = assertInstanceOf(RouteAdmissionResult.Granted::class.java,
            service.progress(requester, pending.handle)).grant

        assertEquals(observation.stamp, grant.observationStamp)
        assertInstanceOf(ProtectedRouteTailReleaseUpdate.Active::class.java,
            service.consumeObservation(observation(9L, train("first", 0.0, 1.0, 9L))).single())
    }

    @Test
    fun `ready with newly occupied route stalls and retains its claim`() {
        val ticket = MutableTicket()
        var stalls = 0
        val service = RouteResourceAdmissionEngine(
            "world", registry, hostClaimOwner, { topology }, { observation }, catalog,
            { _, _ -> null },
            InfrastructurePreparationActuator { InfrastructurePreparationStart.Accepted(ticket) },
            { _, _ -> stalls++ }
        )
        val requester = Any()
        val pending = assertInstanceOf(RouteAdmissionResult.Pending::class.java,
            service.request(requester, intent()))
        observation = observation(8L, train("first", 0.0, 1.0, 8L), train("second", 5.0, 6.0, 8L))

        ticket.state = InfrastructurePreparationState.READY
        val failed = assertInstanceOf(RouteAdmissionResult.Rejected::class.java,
            service.progress(requester, pending.handle))

        assertEquals(RouteAdmissionFailureCode.WORLD_STALLED, failed.failure.code)
        assertSame(failed, service.progress(requester, pending.handle))
        assertEquals(1, stalls)
        assertEquals(1, registry.claimSnapshots().size)
        assertTrue(service.consumeObservation(observation).isEmpty())
    }

    @Test
    fun `ready after topology root replacement stalls even at the same revision`() {
        val ticket = MutableTicket()
        var stalls = 0
        val service = RouteResourceAdmissionEngine(
            "world", registry, hostClaimOwner, { topology }, { observation }, catalog,
            { _, _ -> null },
            InfrastructurePreparationActuator { InfrastructurePreparationStart.Accepted(ticket) },
            { _, _ -> stalls++ }
        )
        val requester = Any()
        val pending = assertInstanceOf(RouteAdmissionResult.Pending::class.java,
            service.request(requester, intent()))
        topology = buildTestTopology(7L, listOf(TestTopologyRail(railId, 100.0)), emptyMap())
        observation = observation(8L, train("first", 0.0, 1.0, 8L))

        ticket.state = InfrastructurePreparationState.READY
        val failed = assertInstanceOf(RouteAdmissionResult.Rejected::class.java,
            service.progress(requester, pending.handle))

        assertEquals(RouteAdmissionFailureCode.WORLD_STALLED, failed.failure.code)
        assertEquals(1, stalls)
        assertEquals(1, registry.claimSnapshots().size)
    }

    @Test
    fun `ready after train tail leaves route start stalls and retains its claim`() {
        val ticket = MutableTicket()
        var stalls = 0
        val service = RouteResourceAdmissionEngine(
            "world", registry, hostClaimOwner, { topology }, { observation }, catalog,
            { _, _ -> null },
            InfrastructurePreparationActuator { InfrastructurePreparationStart.Accepted(ticket) },
            { _, _ -> stalls++ }
        )
        val requester = Any()
        val pending = assertInstanceOf(RouteAdmissionResult.Pending::class.java,
            service.request(requester, intent()))
        observation = observation(8L, train("first", 2.0, 3.0, 8L))

        ticket.state = InfrastructurePreparationState.READY
        val failed = assertInstanceOf(RouteAdmissionResult.Rejected::class.java,
            service.progress(requester, pending.handle))

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
        val service = RouteResourceAdmissionEngine(
            "world", registry, hostClaimOwner, { topology }, { observation }, catalog,
            { _, _ -> null },
            InfrastructurePreparationActuator {
                InfrastructurePreparationStart.Accepted(if (starts++ == 0) unsafeTicket else readyTicket)
            },
            { _, _ -> stalls++ }
        )
        val unsafeRequester = Any()
        val readyRequester = Any()
        val unsafe = assertInstanceOf(RouteAdmissionResult.Pending::class.java,
            service.request(unsafeRequester, intent()))
        val ready = assertInstanceOf(RouteAdmissionResult.Pending::class.java,
            service.request(readyRequester,
                intent(ownerId = "test:route/second", trainId = "second", routeStart = 40.0)))
        assertEquals(2, registry.claimSnapshots().size)

        unsafeTicket.state = InfrastructurePreparationState.UNSAFE
        assertEquals(RouteAdmissionFailureCode.WORLD_STALLED,
            assertInstanceOf(RouteAdmissionResult.Rejected::class.java,
                service.progress(unsafeRequester, unsafe.handle)).failure.code)
        readyTicket.state = InfrastructurePreparationState.READY
        val blocked = assertInstanceOf(RouteAdmissionResult.Rejected::class.java,
            service.progress(readyRequester, ready.handle))

        assertEquals(RouteAdmissionFailureCode.WORLD_STALLED, blocked.failure.code)
        assertSame(blocked, service.progress(readyRequester, ready.handle))
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
        val service = RouteResourceAdmissionEngine(
            "world", registry, hostClaimOwner, { topology }, { observation }, catalog,
            { _, _ -> null },
            InfrastructurePreparationActuator {
                InfrastructurePreparationStart.Accepted(if (starts++ == 0) firstTicket else secondTicket)
            },
            { _, _ -> stalls++ }
        )
        val firstRequester = Any()
        val secondRequester = Any()
        val first = assertInstanceOf(RouteAdmissionResult.Pending::class.java,
            service.request(firstRequester, intent()))
        val second = assertInstanceOf(RouteAdmissionResult.Pending::class.java,
            service.request(secondRequester,
                intent(ownerId = "test:route/second", trainId = "second", routeStart = 40.0)))

        firstTicket.state = InfrastructurePreparationState.UNSAFE
        secondTicket.state = InfrastructurePreparationState.UNSAFE
        assertInstanceOf(RouteAdmissionResult.Rejected::class.java,
            service.progress(firstRequester, first.handle))
        assertInstanceOf(RouteAdmissionResult.Rejected::class.java,
            service.progress(secondRequester, second.handle))

        assertEquals(1, stalls)
        assertEquals(2, registry.claimSnapshots().size)
    }

    @Test
    fun `unsafe preparation stalls and retains the complete reservation`() {
        val ticket = MutableTicket()
        var stalls = 0
        val service = RouteResourceAdmissionEngine(
            "world", registry, hostClaimOwner, { topology }, { observation }, catalog,
            { _, _ -> null },
            InfrastructurePreparationActuator { InfrastructurePreparationStart.Accepted(ticket) },
            { _, _ -> stalls++ }
        )
        val requester = Any()
        val pending = assertInstanceOf(RouteAdmissionResult.Pending::class.java,
            service.request(requester, intent()))
        ticket.state = InfrastructurePreparationState.UNSAFE
        val failed = assertInstanceOf(RouteAdmissionResult.Rejected::class.java,
            service.progress(requester, pending.handle))
        assertEquals(RouteAdmissionFailureCode.WORLD_STALLED, failed.failure.code)
        assertSame(failed, service.progress(requester, pending.handle))
        assertEquals(1, stalls)
        assertEquals(1, registry.claimSnapshots().size)
        assertEquals(RouteAdmissionFailureCode.WORLD_STALLED,
            assertInstanceOf(RouteAdmissionResult.Rejected::class.java,
                service.request(Any(), intent(ownerId = "test:route/after-stall"))).failure.code)
    }

    @Test
    fun `stale observation and unavailable route-bound catalog fail without a claim`() {
        val service = service()
        val oldIntent = intent()
        observation = observation(8L, train("first", 0.0, 1.0, 8L))
        assertEquals(RouteAdmissionFailureCode.STALE_OBSERVATION,
            assertInstanceOf(RouteAdmissionResult.Rejected::class.java,
                service.request(Any(), oldIntent)).failure.code)
        val unavailable = RouteResourceAdmissionEngine(
            "world", registry, hostClaimOwner, { topology }, { observation }, RouteBoundResourceCatalog { topology },
            { _, _ -> null },
            InfrastructurePreparationActuator { error("must not prepare") }, { _, _ -> error("must not stall") }
        )
        assertEquals(RouteAdmissionFailureCode.STALE_TOPOLOGY,
            assertInstanceOf(RouteAdmissionResult.Rejected::class.java,
                unavailable.request(Any(), intent())).failure.code)
        assertTrue(registry.claimSnapshots().isEmpty())
    }

    @Test
    fun `pending extension keeps old authority and held block while old rail releases`() {
        val ticket = MutableTicket()
        var starts = 0
        val service = service(InfrastructurePreparationActuator {
            InfrastructurePreparationStart.Accepted(if (starts++ == 0)
                MutableTicket(InfrastructurePreparationState.READY) else ticket)
        })
        val requester = Any()
        val first = assertInstanceOf(RouteAdmissionResult.Granted::class.java,
            service.request(requester, intent(contributions = listOf(
                ContributedRouteResource(block, RouteBoundary(20.0))
            )))).grant
        val pending = assertInstanceOf(RouteAdmissionResult.Pending::class.java,
            service.request(requester, intent(
                authority = 40.0,
                contributions = listOf(ContributedRouteResource(block, RouteBoundary(40.0)))
            )))
        assertEquals(first, pending.committedGrant)
        assertEquals(RouteAdmissionFailureCode.REQUEST_BUSY,
            assertInstanceOf(RouteAdmissionResult.Rejected::class.java,
                service.request(requester, intent(authority = 60.0))).failure.code)
        observation = observation(8L, train("first", 21.0, 22.0, 8L))
        service.consumeObservation(observation)
        assertEquals(setOf(block, RailIntervalResource(railId, 20.0, 40.0)),
            registry.claimSnapshots().single().footprint.resources.toSet())
        assertFalse(service.endGrant(requester, first.grantId))
        assertEquals(first, assertInstanceOf(RouteAdmissionResult.Pending::class.java,
            service.progress(requester, pending.handle)).committedGrant)

        ticket.state = InfrastructurePreparationState.READY
        val extended = assertInstanceOf(RouteAdmissionResult.Granted::class.java,
            service.progress(requester, pending.handle)).grant
        assertSame(first.grantId, extended.grantId)
        assertEquals(RouteBoundary(40.0), extended.committedAuthority)
        assertEquals(observation.stamp, extended.observationStamp)
        assertSame(extended, assertInstanceOf(RouteAdmissionResult.Granted::class.java,
            service.progress(requester, pending.handle)).grant)
        observation = observation(9L, train("first", 41.0, 42.0, 9L))
        assertInstanceOf(ProtectedRouteTailReleaseUpdate.Completed::class.java,
            service.consumeObservation(observation).single())
        assertTrue(registry.claimSnapshots().isEmpty())
        assertTrue(service.endGrant(requester, first.grantId))
    }

    @Test
    fun `safe extension abort removes only delta and releases overdue old hold`() {
        val ticket = MutableTicket()
        var starts = 0
        val service = service(InfrastructurePreparationActuator {
            InfrastructurePreparationStart.Accepted(if (starts++ == 0)
                MutableTicket(InfrastructurePreparationState.READY) else ticket)
        })
        val requester = Any()
        val first = assertInstanceOf(RouteAdmissionResult.Granted::class.java,
            service.request(requester, intent(contributions = listOf(
                ContributedRouteResource(block, RouteBoundary(20.0))
            )))).grant
        val pending = assertInstanceOf(RouteAdmissionResult.Pending::class.java,
            service.request(requester, intent(
                authority = 40.0,
                contributions = listOf(ContributedRouteResource(block, RouteBoundary(40.0)))
            )))
        observation = observation(8L, train("first", 21.0, 22.0, 8L))
        service.consumeObservation(observation)
        ticket.state = InfrastructurePreparationState.ABORTED_SAFE

        val aborted = assertInstanceOf(RouteAdmissionResult.Rejected::class.java,
            service.progress(requester, pending.handle))
        assertEquals(RouteAdmissionFailureCode.PREPARATION_ABORTED_SAFE, aborted.failure.code)
        assertTrue(registry.claimSnapshots().isEmpty())
        assertSame(aborted, service.progress(requester, pending.handle))
        assertTrue(service.endGrant(requester, first.grantId))
    }

    @Test
    fun `resource idle live grant reacquires farther suffix with the same grant id`() {
        val ticket = MutableTicket()
        var starts = 0
        val service = service(InfrastructurePreparationActuator {
            InfrastructurePreparationStart.Accepted(if (starts++ == 0)
                MutableTicket(InfrastructurePreparationState.READY) else ticket)
        })
        val requester = Any()
        val first = assertInstanceOf(RouteAdmissionResult.Granted::class.java,
            service.request(requester, intent())).grant
        observation = observation(8L, train("first", 21.0, 22.0, 8L))
        service.consumeObservation(observation)
        assertTrue(registry.claimSnapshots().isEmpty())
        val pending = assertInstanceOf(RouteAdmissionResult.Pending::class.java,
            service.request(requester, intent(authority = 40.0)))
        assertEquals(first, pending.committedGrant)
        assertFalse(service.endGrant(requester, first.grantId))
        ticket.state = InfrastructurePreparationState.READY
        val extended = assertInstanceOf(RouteAdmissionResult.Granted::class.java,
            service.progress(requester, pending.handle)).grant
        assertSame(first.grantId, extended.grantId)
        assertEquals(listOf(RailIntervalResource(railId, 20.0, 40.0)),
            registry.claimSnapshots().single().footprint.resources)
    }

    @Test
    fun `old queue can empty while a new suffix reservation is still preparing`() {
        val ticket = MutableTicket()
        var starts = 0
        val service = service(InfrastructurePreparationActuator {
            InfrastructurePreparationStart.Accepted(if (starts++ == 0)
                MutableTicket(InfrastructurePreparationState.READY) else ticket)
        })
        val requester = Any()
        val first = assertInstanceOf(RouteAdmissionResult.Granted::class.java,
            service.request(requester, intent())).grant
        val pending = assertInstanceOf(RouteAdmissionResult.Pending::class.java,
            service.request(requester, intent(authority = 40.0)))
        observation = observation(8L, train("first", 21.0, 22.0, 8L))

        assertInstanceOf(ProtectedRouteTailReleaseUpdate.Completed::class.java,
            service.consumeObservation(observation).single())
        assertEquals(listOf(RailIntervalResource(railId, 20.0, 40.0)),
            registry.claimSnapshots().single().footprint.resources)
        assertFalse(service.endGrant(requester, first.grantId))
        ticket.state = InfrastructurePreparationState.READY
        val extended = assertInstanceOf(RouteAdmissionResult.Granted::class.java,
            service.progress(requester, pending.handle)).grant
        assertSame(first.grantId, extended.grantId)
        assertEquals(RouteBoundary(40.0), extended.committedAuthority)
        observation = observation(9L, train("first", 41.0, 42.0, 9L))
        assertInstanceOf(ProtectedRouteTailReleaseUpdate.Completed::class.java,
            service.consumeObservation(observation).single())
        assertTrue(registry.claimSnapshots().isEmpty())
    }

    @Test
    fun `extension conflict leaves the old grant and claim unchanged`() {
        val requester = Any()
        val service = service()
        val first = assertInstanceOf(RouteAdmissionResult.Granted::class.java,
            service.request(requester, intent())).grant
        assertEquals(work.art1st.javarailways.basic.api.infrastructure.InfrastructureMutationStatus.ACQUIRED,
            registry.acquireClaim(Any(), InfrastructureClaimRequest(
                topology.revision, InfrastructureOwner("test:route/blocker", 1L),
                work.art1st.javarailways.basic.api.infrastructure.InfrastructureFootprint(
                    listOf(RailIntervalResource(railId, 25.0, 35.0)))
            )).status)

        val rejected = assertInstanceOf(RouteAdmissionResult.Rejected::class.java,
            service.request(requester, intent(authority = 40.0)))
        assertEquals(RouteAdmissionFailureCode.RESOURCE_CONFLICT, rejected.failure.code)
        assertEquals(RouteBoundary(20.0), first.committedAuthority)
        assertEquals(listOf(RailIntervalResource(railId, 0.0, 20.0)),
            registry.claimSnapshots().first { it.owner == first.owner }.footprint.resources)
    }

    @Test
    fun `unsafe extension retains delta and cannot publish a farther grant`() {
        val ticket = MutableTicket()
        var starts = 0
        var stalls = 0
        val service = RouteResourceAdmissionEngine(
            "world", registry, hostClaimOwner, { topology }, { observation }, catalog,
            { _, _ -> null }, InfrastructurePreparationActuator {
                InfrastructurePreparationStart.Accepted(if (starts++ == 0)
                    MutableTicket(InfrastructurePreparationState.READY) else ticket)
            }, { _, _ -> stalls++ }
        )
        val requester = Any()
        val first = assertInstanceOf(RouteAdmissionResult.Granted::class.java,
            service.request(requester, intent())).grant
        val pending = assertInstanceOf(RouteAdmissionResult.Pending::class.java,
            service.request(requester, intent(authority = 40.0)))
        ticket.state = InfrastructurePreparationState.UNSAFE

        val rejected = assertInstanceOf(RouteAdmissionResult.Rejected::class.java,
            service.progress(requester, pending.handle))
        assertEquals(RouteAdmissionFailureCode.WORLD_STALLED, rejected.failure.code)
        assertEquals(1, stalls)
        assertEquals(setOf(RailIntervalResource(railId, 0.0, 20.0),
            RailIntervalResource(railId, 20.0, 40.0)),
            registry.claimSnapshots().single().footprint.resources.toSet())
        assertTrue(service.consumeObservation(observation(8L, train("first", 21.0, 22.0, 8L))).isEmpty())
    }

    @Test
    fun `ready extension rechecks occupancy on the latest epoch and stalls without wider authority`() {
        val ticket = MutableTicket()
        var starts = 0
        var stalls = 0
        val service = RouteResourceAdmissionEngine(
            "world", registry, hostClaimOwner, { topology }, { observation }, catalog,
            { _, _ -> null }, InfrastructurePreparationActuator {
                InfrastructurePreparationStart.Accepted(if (starts++ == 0)
                    MutableTicket(InfrastructurePreparationState.READY) else ticket)
            }, { _, _ -> stalls++ }
        )
        val requester = Any()
        val first = assertInstanceOf(RouteAdmissionResult.Granted::class.java,
            service.request(requester, intent())).grant
        val pending = assertInstanceOf(RouteAdmissionResult.Pending::class.java,
            service.request(requester, intent(authority = 40.0)))
        observation = observation(8L,
            train("first", 1.0, 2.0, 8L), train("second", 30.0, 31.0, 8L))
        ticket.state = InfrastructurePreparationState.READY

        val rejected = assertInstanceOf(RouteAdmissionResult.Rejected::class.java,
            service.progress(requester, pending.handle))
        assertEquals(RouteAdmissionFailureCode.WORLD_STALLED, rejected.failure.code)
        assertEquals(1, stalls)
        assertEquals(RouteBoundary(20.0), first.committedAuthority)
        assertEquals(setOf(RailIntervalResource(railId, 0.0, 20.0),
            RailIntervalResource(railId, 20.0, 40.0)),
            registry.claimSnapshots().single().footprint.resources.toSet())
    }

    @Test
    fun `already prepared held contribution may extend its hold but cannot change preparation`() {
        val batches = mutableListOf<List<InfrastructurePreparationRequirement>>()
        val service = service(InfrastructurePreparationActuator { requirements ->
            batches += requirements.toList()
            InfrastructurePreparationStart.Accepted(MutableTicket(InfrastructurePreparationState.READY))
        })
        val requester = Any()
        val preparation = ResourcePreparationDescriptor("test:prepare", emptyMap())
        val first = assertInstanceOf(RouteAdmissionResult.Granted::class.java,
            service.request(requester, intent(contributions = listOf(
                ContributedRouteResource(block, RouteBoundary(20.0), preparation)
            )))).grant
        val changed = assertInstanceOf(RouteAdmissionResult.Rejected::class.java,
            service.request(requester, intent(
                authority = 40.0, contributions = listOf(ContributedRouteResource(
                    block, RouteBoundary(40.0), ResourcePreparationDescriptor("test:other", emptyMap())
                )))))
        assertEquals(RouteAdmissionFailureCode.INVALID_PREPARATION, changed.failure.code)
        assertEquals(1, batches.size)
        val extended = assertInstanceOf(RouteAdmissionResult.Granted::class.java,
            service.request(requester, intent(
                authority = 40.0, contributions = listOf(ContributedRouteResource(
                    block, RouteBoundary(40.0), preparation
                ))))).grant
        assertEquals(RouteBoundary(40.0), extended.committedAuthority)
        assertEquals(2, batches.size)
        assertTrue(batches.last().isEmpty())
    }

    private fun service(
        actuator: InfrastructurePreparationActuator = InfrastructurePreparationActuator {
            InfrastructurePreparationStart.Accepted(MutableTicket(InfrastructurePreparationState.READY))
        }
    ) = RouteResourceAdmissionEngine(
        "world", registry, hostClaimOwner, { topology }, { observation }, catalog,
        { _, contribution -> additionValidation(contribution) }, actuator,
        { _, _ -> error("unexpected world stall") },
        ::authenticatedContributions
    )

    private fun intent(
        ownerId: String = "test:route/main",
        ownerRevision: Long = 1L,
        contributions: List<ContributedRouteResource> = emptyList(),
        trainId: String = "first",
        routeStart: Double = 0.0,
        authority: Double = 20.0
    ): RouteResourceIntent {
        val intent = RouteResourceIntent(
            "test:request/main", InfrastructureOwner(ownerId, ownerRevision), trainId, observation.stamp,
            RouteSnapshot("test:route/main", 1L, topology.revision,
                listOf(RouteSegmentSnapshot(railId, ViewDirection.AB, routeStart, 100.0))),
            RouteBoundary(authority)
        )
        contributionsByIntent[intent] = contributions
        return intent
    }

    private fun authenticatedContributions(
        intent: RouteResourceIntent
    ): List<Pair<Any, ContributedRouteResource>> =
        contributionsByIntent[intent].orEmpty().map { contributorOwner to it }

    private fun observation(epoch: Long, vararg trains: TrainFrameSnapshot) = RailwayObservation.fromCore(
        "world", RailwayWorldFrameSnapshot.create(epoch, topology.revision, trains.toList())
    )

    private fun train(id: String, tail: Double, head: Double, epoch: Long) = TrainFrameSnapshot.create(
        trainId = id, trainName = id, revision = 1L, physicsEpoch = epoch,
        speedMetersPerSecond = 0.0, derailed = false, reversed = false, cars = emptyList(),
        head = RailPositionSnapshot(railId, ViewDirection.AB, head),
        tail = RailPositionSnapshot(railId, ViewDirection.AB, tail),
        occupiedIntervals = listOf(RailInterval(railId, tail, head)),
        sweptSpans = listOf(SweptRailSpanSnapshot(railId, tail, head)),
        headTraversal = emptyList(),
        tailTraversal = emptyList()
    )

    private class MutableTicket(var state: InfrastructurePreparationState = InfrastructurePreparationState.PREPARING) :
        InfrastructurePreparationTicket {
        var snapshotCalls = 0

        override fun snapshot(): InfrastructurePreparationSnapshot {
            snapshotCalls++
            return InfrastructurePreparationSnapshot(
                state, if (state == InfrastructurePreparationState.UNSAFE) "device state unknown" else ""
            )
        }
        override fun abort() = Unit
    }
}
