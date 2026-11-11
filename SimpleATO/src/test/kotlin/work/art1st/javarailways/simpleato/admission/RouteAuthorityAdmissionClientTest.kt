package work.art1st.javarailways.simpleato.admission

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.simpleato.api.ato.AtoSafetyProfile
import work.art1st.javarailways.simpleato.api.constraint.ConstraintKey
import work.art1st.javarailways.simpleato.api.constraint.ConstraintManager
import work.art1st.javarailways.simpleato.api.constraint.ConstraintOwner
import work.art1st.javarailways.simpleato.api.constraint.ConstraintOwnerType
import work.art1st.javarailways.simpleato.api.constraint.FollowConstraint
import work.art1st.javarailways.simpleato.api.constraint.MovementAuthorityConstraint
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureOwner
import work.art1st.javarailways.basic.api.observation.RailwayStateStamp
import work.art1st.javarailways.basic.api.route.RouteAdmissionFailure
import work.art1st.javarailways.basic.api.route.RouteAdmissionFailureCode
import work.art1st.javarailways.basic.api.route.RouteAdmissionHandle
import work.art1st.javarailways.basic.api.route.RouteAdmissionResult
import work.art1st.javarailways.basic.api.route.RouteAdmissionTestFixtures
import work.art1st.javarailways.basic.api.route.RouteBoundary
import work.art1st.javarailways.basic.api.route.RouteGrantId
import work.art1st.javarailways.basic.api.route.RouteGrantReversalResult
import work.art1st.javarailways.basic.api.route.RouteGrantSnapshot
import work.art1st.javarailways.basic.api.route.RouteResourceAdmissionService
import work.art1st.javarailways.basic.api.route.RouteResourceIntent
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.simpleato.api.restriction.CurrentSpeedRestriction
import work.art1st.javarailways.simpleato.api.restriction.MovementAuthorityRestriction
import work.art1st.javarailways.simpleato.api.restriction.RouteSpeedRestriction
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublicationResult
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublicationStatus
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionSnapshot
import work.art1st.javarailways.simpleato.api.route.FrozenRouteBinding
import work.art1st.javarailways.simpleato.restriction.TrainRestrictionConstraintAdapter
import work.art1st.javarailways.simpleato.routing.FrozenRouteSelectionResult
import work.art1st.javarailways.simpleato.routing.RouteOperatingDirectionLimit
import work.art1st.javarailways.basic.api.route.RailOperatingDirections
import work.art1st.javarailways.core.physics.rail.topology.TestTopologyRail
import work.art1st.javarailways.core.physics.rail.topology.buildTestTopology
import java.util.UUID

class RouteAuthorityAdmissionClientTest {
    private val owner = InfrastructureOwner("simpleato:train/main", 7L)
    private val stamp = RailwayStateStamp("world", 10L, 4L)
    private val route = route()
    private val selection = selection(route = route)
    private val publisher = RecordingPublisher()
    private val service = FakeAdmissionService()
    private val client = client(service, publisher)

    @Test
    fun `station terminal arithmetic accepts roundoff but keeps real grant and rejects missing clearance`() {
        val terminalRoute = RouteSnapshot(route.routeId, route.revision, route.topologyRevision,
            listOf(RouteSegmentSnapshot(UUID(0L, 99L), ViewDirection.AB, 590.0, 610.3)))
        val selected = selection(terminalRoute).copy(stationTerminalAuthority =
            work.art1st.javarailways.simpleato.routing.StationTerminalAuthority(20.3))
        val actualEnd = terminalRoute.totalLengthMeters
        assertTrue(actualEnd < 20.3, "fixture must reproduce subtraction roundoff")
        client.prepareContinuation(selected)
        val partial = grant(actualEnd - 0.0001, terminalRoute)
        service.onRequest = { RouteAdmissionResult.Granted(partial) }
        client.request(selected, RouteBoundary(actualEnd - 0.0001))
        assertTrue(publisher.snapshots.last().restrictions.filterIsInstance<MovementAuthorityRestriction>()
            .all { it.stoppingMarginMeters == null }, "a genuinely short grant retains the global safety margin")

        service.onRequest = { RouteAdmissionResult.Granted(partial.copy(committedAuthority = RouteBoundary(actualEnd))) }
        client.request(selected, RouteBoundary(actualEnd))
        val authority = publisher.snapshots.last().restrictions.filterIsInstance<MovementAuthorityRestriction>().single()
        assertEquals(actualEnd, authority.routeDistanceMeters, "do not manufacture the missing floating-point length")
        assertEquals(AtoSafetyProfile.STATION_AUTHORITY_MARGIN_METERS, authority.stoppingMarginMeters)
        assertThrows(IllegalArgumentException::class.java) {
            client.prepareContinuation(selected.copy(stationTerminalAuthority =
                work.art1st.javarailways.simpleato.routing.StationTerminalAuthority(20.3001)))
        }
    }

    @Test
    fun `station context reduces only proven terminal authority including protected direction suffix`() {
        val safe = UUID(0L, 91L)
        val forbidden = UUID(0L, 92L)
        val protected = RouteSnapshot(route.routeId, route.revision, route.topologyRevision, listOf(
            RouteSegmentSnapshot(safe, ViewDirection.AB, 0.0, 100.0),
            RouteSegmentSnapshot(forbidden, ViewDirection.AB, 0.0, 40.0)))
        val selected = selection(protected, directions = mapOf(forbidden to ViewDirection.BA)).copy(
            stationTerminalAuthority = work.art1st.javarailways.simpleato.routing.StationTerminalAuthority(99.8))
        val initial = grant(90.0, protected)
        service.onRequest = { RouteAdmissionResult.Granted(initial) }
        client.request(selected, RouteBoundary(90.0))
        assertTrue(publisher.snapshots.last().restrictions.filterIsInstance<MovementAuthorityRestriction>()
            .all { it.stoppingMarginMeters == null }, "intermediate grant keeps ordinary safety margin")
        val pending = RouteAdmissionTestFixtures.handle()
        service.onRequest = { RouteAdmissionResult.Pending(pending, initial) }
        service.onProgress = { RouteAdmissionResult.Granted(initial.copy(committedAuthority = RouteBoundary(140.0))) }
        client.request(selected, RouteBoundary(140.0))
        assertTrue(publisher.snapshots.last().restrictions.filterIsInstance<MovementAuthorityRestriction>()
            .all { it.stoppingMarginMeters == null })
        client.progress()
        assertEquals(140.0, client.committedGrantSnapshot!!.committedAuthority.distanceFromRouteStartMeters)
        val restrictions = publisher.snapshots.last().restrictions.filterIsInstance<MovementAuthorityRestriction>()
        assertEquals(listOf(100.0, 140.0), restrictions.map { it.routeDistanceMeters }.sorted())
        assertTrue(restrictions.all { it.stoppingMarginMeters == 0.3 })
        val manager = ConstraintManager()
        val adapter = TrainRestrictionConstraintAdapter("train", manager)
        adapter.publish(publisher.snapshots.last());adapter.apply(stamp, FrozenRouteBinding.from(protected), 99.5)
        assertTrue(manager.permittedSpeedMetersPerSecond(0.0, 30.0, AtoSafetyProfile()) > 0.0,
            "a proven station terminal is reachable before the lawful 100m direction boundary")
        manager.replace(ConstraintOwner(ConstraintOwnerType.SIGNALLING, "test:red"), listOf(
            MovementAuthorityConstraint(ConstraintKey("test:red", "authority"), 0.3)))
        assertEquals(0.0, manager.permittedSpeedMetersPerSecond(0.0, 30.0, AtoSafetyProfile()),
            "independent red authority keeps its ordinary margin and wins")
        assertThrows(IllegalArgumentException::class.java) {
            client.prepareContinuation(selected.copy(stationTerminalAuthority =
                work.art1st.javarailways.simpleato.routing.StationTerminalAuthority(100.3)))
        }
        val beforeForeign = publisher.snapshots.size
        assertThrows(IllegalArgumentException::class.java) {
            client.prepareContinuation(selected.copy(trainId = "foreign", stationTerminalAuthority = null))
        }
        assertThrows(IllegalArgumentException::class.java) {
            client.prepareContinuation(selection(protected, directions = mapOf(forbidden to ViewDirection.BA), worldId = "foreign"))
        }
        assertEquals(beforeForeign, publisher.snapshots.size)
        assertTrue(publisher.snapshots.last().restrictions.filterIsInstance<MovementAuthorityRestriction>().all { it.stoppingMarginMeters == 0.3 })
        client.prepareContinuation(selected.copy(stationTerminalAuthority = null))
        assertTrue(publisher.snapshots.last().restrictions.filterIsInstance<MovementAuthorityRestriction>()
            .all { it.stoppingMarginMeters == null }, "same frozen route cannot retain old station provenance")
    }

    @Test
    fun `new pending target withdraws previous station margin before granting its resources`() {
        val station = selection.copy(stationTerminalAuthority =
            work.art1st.javarailways.simpleato.routing.StationTerminalAuthority(90.3))
        val held = grant(95.0)
        service.onRequest = { RouteAdmissionResult.Granted(held) }
        client.request(station, RouteBoundary(95.0))
        assertEquals(0.3, publisher.snapshots.last().restrictions.filterIsInstance<MovementAuthorityRestriction>().single().stoppingMarginMeters)
        val pending = RouteAdmissionTestFixtures.handle()
        service.onRequest = { RouteAdmissionResult.Pending(pending, held) }
        client.request(selection, RouteBoundary(100.0))
        assertEquals(null, publisher.snapshots.last().restrictions.filterIsInstance<MovementAuthorityRestriction>().single().stoppingMarginMeters)
        val count = publisher.snapshots.size
        assertThrows(IllegalStateException::class.java) { client.prepareContinuation(station) }
        assertEquals(count, publisher.snapshots.size, "pending provenance cannot be rebound before its lifecycle check")
    }

    @Test
    fun `protection grant remains truthful while direction restriction limits actual movement`() {
        val safe = UUID(0L, 21L)
        val forbidden = UUID(0L, 22L)
        val protectedRoute = RouteSnapshot(route.routeId, route.revision, route.topologyRevision,
            listOf(RouteSegmentSnapshot(safe, ViewDirection.BA, 0.0, 60.0),
                RouteSegmentSnapshot(forbidden, ViewDirection.BA, 0.0, 40.0)))
        val limited = selection(protectedRoute, directions = mapOf(forbidden to ViewDirection.AB))
        val held = grant(100.0, protectedRoute)
        val handle = RouteAdmissionTestFixtures.handle()
        service.onRequest = { RouteAdmissionResult.Pending(handle, null) }
        service.onProgress = { RouteAdmissionResult.Granted(held) }

        client.request(limited, RouteBoundary(100.0))
        assertEquals(0.0, publisher.authorityOrHoldValues().last())
        client.progress()

        assertSame(held, client.committedGrantSnapshot)
        assertEquals(RouteBoundary(100.0), client.state.committedAuthority)
        val authorities = publisher.snapshots.last().restrictions.filterIsInstance<MovementAuthorityRestriction>()
        assertEquals(mapOf("simpleato:committed-authority" to 100.0, "simpleato:operating-direction" to 60.0),
            authorities.associate { it.restrictionId to it.routeDistanceMeters })
        val manager = ConstraintManager()
        val adapter = TrainRestrictionConstraintAdapter("train", manager)
        adapter.publish(publisher.snapshots.last())
        adapter.apply(stamp, FrozenRouteBinding.from(protectedRoute), 10.0)
        assertEquals(listOf(50.0, 90.0), manager.snapshot().values.flatten()
            .filterIsInstance<MovementAuthorityConstraint>().map { it.remainingDistanceMeters }.sorted())
        adapter.apply(stamp, FrozenRouteBinding.from(protectedRoute), 55.0)
        assertEquals(0.0, manager.permittedSpeedMetersPerSecond(0.0, 30.0,
            AtoSafetyProfile(safetyDistanceMeters = 5.0)),
            "the direction boundary uses the existing common stopping safety distance")
    }

    @Test
    fun `direction boundary survives pending denied continuation and formation adoption`() {
        val forbidden = UUID(0L, 23L)
        val protectedRoute = RouteSnapshot(route.routeId, route.revision, route.topologyRevision,
            listOf(route.segments.single(), RouteSegmentSnapshot(forbidden, ViewDirection.BA, 0.0, 40.0)))
        val limited = selection(protectedRoute, directions = mapOf(forbidden to ViewDirection.AB))
        val held = grant(140.0, protectedRoute)
        client.adoptFormationGrant(limited, held)
        fun assertRetained() {
            assertSame(held, client.committedGrantSnapshot)
            assertEquals(100.0, publisher.snapshots.last().restrictions
                .filterIsInstance<MovementAuthorityRestriction>()
                .single { it.restrictionId == "simpleato:operating-direction" }.routeDistanceMeters)
        }
        assertRetained()
        val appended = RouteSnapshot(protectedRoute.routeId, protectedRoute.revision + 1,
            protectedRoute.topologyRevision, protectedRoute.segments +
                RouteSegmentSnapshot(UUID(0L, 24L), ViewDirection.AB, 0.0, 30.0))
        val next = selection(appended, directions = mapOf(forbidden to ViewDirection.AB))
        val pending = RouteAdmissionTestFixtures.handle()
        service.onRequest = { RouteAdmissionResult.Pending(pending, held) }
        service.onProgress = { rejected(RouteAdmissionFailureCode.PREPARATION_ABORTED_SAFE, "preparation rejected") }
        client.prepareContinuation(next)
        client.request(next, RouteBoundary(appended.totalLengthMeters))
        assertRetained()
        client.progress()
        assertRetained()
        assertEquals(RouteBoundary(140.0), client.state.committedAuthority)
    }

    @Test
    fun `missing or mismatched direction proof cannot reach Basic or adopt a grant`() {
        assertThrows(IllegalArgumentException::class.java) {
            client.request(selection.copy(operatingDirectionLimit = null), RouteBoundary(30.0))
        }
        val different = route("simpleato:route/other")
        assertThrows(IllegalArgumentException::class.java) {
            client.request(selection.copy(route = different), RouteBoundary(30.0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            client.adoptFormationGrant(selection.copy(operatingDirectionLimit = null), grant(30.0))
        }
        assertTrue(service.intents.isEmpty())
        assertEquals(RouteAuthorityAdmissionState.EMPTY, client.state)
    }

    @Test
    fun `prepared continuation republishes only existing prefix and leaves Basic grant unchanged`() {
        val held = grant(route.totalLengthMeters)
        service.onRequest = { RouteAdmissionResult.Granted(held) }
        client.request(route.totalLengthMeters)
        val next = RouteSnapshot(route.routeId, route.revision + 1L, route.topologyRevision + 1L,
            route.segments + RouteSegmentSnapshot(UUID(0L, 9L), ViewDirection.AB, 0.0, 60.0))
        val continued = selection(next).copy(observationStamp = stamp.copy(topologyRevision = next.topologyRevision))
        client.prepareContinuation(continued)
        assertEquals(1, service.intents.size, "preparing a restriction binding must not acquire resources")
        assertEquals(held.committedAuthority, client.state.committedAuthority)
        assertEquals(FrozenRouteBinding.from(next), publisher.snapshots.last().route)
        assertEquals(route.totalLengthMeters,
            (publisher.snapshots.last().restrictions.single() as MovementAuthorityRestriction).routeDistanceMeters)
        val publications = publisher.snapshots.size
        client.prepareContinuation(continued)
        assertEquals(publications, publisher.snapshots.size, "unchanged preparation is not a tick publication")
        assertThrows(IllegalArgumentException::class.java) {
            client.prepareContinuation(selection(RouteSnapshot(route.routeId, next.revision + 1L,
                route.topologyRevision, next.segments.drop(1))))
        }
        assertEquals(FrozenRouteBinding.from(next), publisher.snapshots.last().route)
        assertEquals(1, service.intents.size)
    }

    @Test
    fun `first immediate grant replaces initial zero authority with committed boundary`() {
        val grant = grant(40.0)
        service.onRequest = { RouteAdmissionResult.Granted(grant) }

        assertEquals(RouteAuthorityAdmissionState.EMPTY, client.state)

        val result = client.request(40.0)

        assertSame(grant, (result as RouteAdmissionResult.Granted).grant)
        assertEquals(listOf(0.0, 40.0), publisher.authorityOrHoldValues())
        assertEquals(1, service.intents.size)
        assertEquals(selection.route, service.intents.single().route)
        assertEquals(selection.observationStamp, service.intents.single().observationStamp)
        assertEquals("train", service.intents.single().trainId)
        assertEquals(RouteBoundary(40.0), client.state.committedAuthority)
        assertFalse(client.state.pending)
    }

    @Test
    fun `complete route grant retains its truthful physical authority boundary`() {
        val grant = grant(route.totalLengthMeters)
        service.onRequest = { RouteAdmissionResult.Granted(grant) }

        client.request(route.totalLengthMeters)

        assertEquals(listOf(0.0, route.totalLengthMeters), publisher.authorityOrHoldValues())
        assertEquals(RouteBoundary(route.totalLengthMeters), client.state.committedAuthority)
    }

    @Test
    fun `first pending request holds speed without inventing a physical authority until a grant`() {
        val handle = RouteAdmissionTestFixtures.handle()
        val grant = grant(35.0)
        service.onRequest = { RouteAdmissionResult.Pending(handle, null) }
        service.onProgress = { supplied ->
            assertSame(handle, supplied)
            RouteAdmissionResult.Granted(grant)
        }

        val pending = client.request(35.0)

        assertSame(handle, (pending as RouteAdmissionResult.Pending).handle)
        assertEquals(listOf(0.0), publisher.authorityOrHoldValues())
        assertTrue(publisher.snapshots.last().restrictions.none { it is MovementAuthorityRestriction })
        assertEquals(0.0, (publisher.snapshots.last().restrictions.single() as CurrentSpeedRestriction).maximumSpeedMetersPerSecond)
        assertTrue(client.state.pending)
        assertEquals(null, client.state.committedAuthority)

        val ready = client.progress()

        assertSame(grant, (ready as RouteAdmissionResult.Granted).grant)
        assertEquals(listOf(0.0, 35.0), publisher.authorityOrHoldValues())
        assertFalse(client.state.pending)
        assertEquals(RouteBoundary(35.0), client.state.committedAuthority)
    }

    @Test
    fun `pending extension retains the earlier committed authority`() {
        val grant = grant(30.0)
        val handle = RouteAdmissionTestFixtures.handle()
        var requests = 0
        service.onRequest = {
            if (requests++ == 0) RouteAdmissionResult.Granted(grant)
            else RouteAdmissionResult.Pending(handle, grant)
        }

        client.request(30.0)
        val pending = client.request(60.0, stamp.copy(physicsEpoch = 11L))

        assertSame(grant, (pending as RouteAdmissionResult.Pending).committedGrant)
        assertEquals(listOf(0.0, 30.0), publisher.authorityOrHoldValues())
    }

    @Test
    fun `pending route continuation keeps old binding and ready switches the same grant`() {
        val grantId = RouteAdmissionTestFixtures.grantId()
        val first = grant(100.0, selectedRoute = route, id = grantId)
        val appendedRoute = RouteSnapshot(
            route.routeId,
            route.revision + 1L,
            route.topologyRevision,
            route.segments + RouteSegmentSnapshot(
                UUID.randomUUID(), ViewDirection.AB, 0.0, 60.0
            )
        )
        val appendedSelection = selection(appendedRoute)
        val handle = RouteAdmissionTestFixtures.handle()
        var requests = 0
        service.onRequest = {
            if (requests++ == 0) RouteAdmissionResult.Granted(first)
            else RouteAdmissionResult.Pending(handle, first)
        }
        service.onProgress = {
            RouteAdmissionResult.Granted(grant(140.0, appendedRoute, grantId))
        }

        client.request(100.0)
        val pending = client.request(appendedSelection, RouteBoundary(140.0))

        assertSame(first, (pending as RouteAdmissionResult.Pending).committedGrant)
        assertEquals(route.revision, publisher.snapshots.last().route.routeRevision)
        assertEquals(route.totalLengthMeters, publisher.snapshots.last().route.totalLengthMeters)

        val ready = assertInstanceOf(RouteAdmissionResult.Granted::class.java, client.progress()).grant
        assertSame(grantId, ready.grantId)
        assertEquals(appendedRoute.revision, publisher.snapshots.last().route.routeRevision)
        assertEquals(appendedRoute.totalLengthMeters, publisher.snapshots.last().route.totalLengthMeters)
        assertEquals(140.0, publisher.authorityOrHoldValues().last())
    }

    @Test
    fun `reversal keeps grant identity while rebasing route and authority at the new head`() {
        val grantId = RouteAdmissionTestFixtures.grantId()
        val first = grant(80.0, selectedRoute = route, id = grantId)
        service.onRequest = { RouteAdmissionResult.Granted(first) }
        service.onSuspendReversal = { supplied ->
            assertSame(grantId, supplied)
            RouteGrantReversalResult.Accepted(first)
        }
        val reversedRoute = route("simpleato:route/reverse", revision = 3L)
        val reversedStamp = stamp.copy(physicsEpoch = 11L)
        val reversedSelection = selection(reversedRoute).copy(observationStamp = reversedStamp)
        service.onRebaseReversal = { intent ->
            RouteGrantReversalResult.Accepted(
                first.copy(
                    routeId = reversedRoute.routeId,
                    routeRevision = reversedRoute.revision,
                    observationStamp = reversedStamp,
                    committedAuthority = intent.desiredAuthority
                )
            )
        }

        client.request(80.0)
        assertInstanceOf(RouteGrantReversalResult.Accepted::class.java,
            client.suspendReleaseForReversal())
        assertTrue(client.state.reversalPending)

        val rebased = assertInstanceOf(
            RouteAdmissionResult.Granted::class.java,
            client.rebaseAfterReversal(reversedSelection, RouteBoundary(10.0))
        ).grant

        assertSame(grantId, rebased.grantId)
        assertEquals(RouteBoundary(10.0), client.state.committedAuthority)
        assertFalse(client.state.reversalPending)
        assertEquals(reversedRoute.revision, publisher.snapshots.last().route.routeRevision)
        assertEquals(listOf(0.0, 80.0, 10.0), publisher.authorityOrHoldValues())
    }

    @Test
    fun `denied continuation retains the earlier authority and route binding`() {
        val grant = grant(30.0)
        val appendedRoute = RouteSnapshot(
            route.routeId,
            route.revision + 1L,
            route.topologyRevision,
            route.segments + RouteSegmentSnapshot(UUID.randomUUID(), ViewDirection.AB, 0.0, 60.0)
        )
        var requests = 0
        service.onRequest = {
            if (requests++ == 0) RouteAdmissionResult.Granted(grant)
            else rejected(RouteAdmissionFailureCode.INVALID_BOUNDARY, "continuation denied")
        }

        client.request(30.0)
        val denied = client.request(selection(appendedRoute), RouteBoundary(60.0))

        assertEquals(
            RouteAdmissionFailureCode.INVALID_BOUNDARY,
            (denied as RouteAdmissionResult.Rejected).failure.code
        )
        assertEquals(listOf(0.0, 30.0), publisher.authorityOrHoldValues())
        assertEquals(route.revision, publisher.snapshots.last().route.routeRevision)
        assertEquals(route.totalLengthMeters, publisher.snapshots.last().route.totalLengthMeters)
    }

    @Test
    fun `safe abort before first grant leaves stop in place and permits explicit end`() {
        val handle = RouteAdmissionTestFixtures.handle()
        service.onRequest = { RouteAdmissionResult.Pending(handle, null) }
        service.onProgress = {
            rejected(RouteAdmissionFailureCode.PREPARATION_ABORTED_SAFE, "device safely aborted")
        }

        client.request(40.0)
        val aborted = client.progress()

        assertEquals(
            RouteAdmissionFailureCode.PREPARATION_ABORTED_SAFE,
            (aborted as RouteAdmissionResult.Rejected).failure.code
        )
        assertEquals(listOf(0.0), publisher.authorityOrHoldValues())
        assertTrue(client.end())
        assertTrue(publisher.snapshots.last().restrictions.isEmpty())
        assertTrue(service.ended.isEmpty())
    }

    @Test
    fun `world stall keeps the zero authority hold and refuses lifecycle end`() {
        service.onRequest = {
            rejected(RouteAdmissionFailureCode.WORLD_STALLED, "preparation state is unprovable")
        }

        val stalled = client.request(40.0)

        assertEquals(
            RouteAdmissionFailureCode.WORLD_STALLED,
            (stalled as RouteAdmissionResult.Rejected).failure.code
        )
        assertEquals(listOf(0.0), publisher.authorityOrHoldValues())
        assertFalse(client.end())
        assertEquals(listOf(0.0), publisher.authorityOrHoldValues())

        service.onRequest = { RouteAdmissionResult.Granted(grant(60.0)) }
        val retry = client.request(60.0)
        assertEquals(
            RouteAdmissionFailureCode.WORLD_STALLED,
            (retry as RouteAdmissionResult.Rejected).failure.code
        )
        assertEquals(1, service.intents.size)
        assertEquals(listOf(0.0), publisher.authorityOrHoldValues())
    }

    @Test
    fun `explicit end waits for Basic and only then withdraws committed authority`() {
        val grant = grant(45.0)
        service.onRequest = { RouteAdmissionResult.Granted(grant) }
        service.endResult = false
        client.request(45.0)

        assertFalse(client.end())
        assertEquals(listOf(0.0, 45.0), publisher.authorityOrHoldValues())

        service.endResult = true
        assertTrue(client.end())
        assertSame(grant.grantId, service.ended.last())
        assertTrue(publisher.snapshots.last().restrictions.isEmpty())
        assertEquals(RouteAuthorityAdmissionState.EMPTY, client.state)
    }

    @Test
    fun `restriction publication failure after Basic end retries withdrawal without ending grant twice`() {
        val grant = grant(45.0)
        service.onRequest = { RouteAdmissionResult.Granted(grant) }
        client.request(45.0)
        publisher.nextFailure = IllegalStateException("publication failed")

        assertThrows(IllegalStateException::class.java) { client.end() }
        assertEquals(1, service.ended.size)
        assertEquals(45.0, publisher.authorityOrHoldValues().last())

        assertTrue(client.end())
        assertEquals(1, service.ended.size)
        assertTrue(publisher.snapshots.last().restrictions.isEmpty())
    }

    @Test
    fun `changed frozen route is rejected until the old route is explicitly ended`() {
        var nextGrantId = RouteAdmissionTestFixtures.grantId()
        service.onRequest = { intent ->
            RouteAdmissionResult.Granted(grant(
                authority = intent.desiredAuthority.distanceFromRouteStartMeters,
                selectedRoute = intent.route,
                id = nextGrantId
            ))
        }
        service.endResult = true
        client.request(30.0)
        val changedRoute = route(routeId = "simpleato:route/changed", revision = 8L)
        val changed = selection(changedRoute)

        val refused = client.request(changed, RouteBoundary(20.0))

        assertEquals(
            RouteAdmissionFailureCode.ROUTE_STATE_MISMATCH,
            (refused as RouteAdmissionResult.Rejected).failure.code
        )
        assertEquals(1, service.intents.size)

        assertTrue(client.end())
        nextGrantId = RouteAdmissionTestFixtures.grantId()
        assertTrue(client.request(changed, RouteBoundary(20.0)) is RouteAdmissionResult.Granted)
        assertEquals(2, service.intents.size)
    }

    @Test
    fun `Basic remains the owner of exact traversal matching`() {
        val grant = grant(30.0)
        var requests = 0
        service.onRequest = {
            if (requests++ == 0) RouteAdmissionResult.Granted(grant)
            else rejected(RouteAdmissionFailureCode.ROUTE_STATE_MISMATCH, "traversal changed")
        }
        client.request(30.0)
        val malformedSameRevision = RouteSnapshot(
            route.routeId,
            route.revision,
            route.topologyRevision,
            listOf(RouteSegmentSnapshot(UUID.randomUUID(), ViewDirection.AB, 0.0, 100.0))
        )

        val result = client.request(selection(malformedSameRevision), RouteBoundary(60.0))

        assertEquals(
            RouteAdmissionFailureCode.ROUTE_STATE_MISMATCH,
            (result as RouteAdmissionResult.Rejected).failure.code
        )
        assertEquals(2, service.intents.size)
        assertEquals(malformedSameRevision, service.intents.last().route)
        assertEquals(listOf(0.0, 30.0), publisher.authorityOrHoldValues())
    }

    @Test
    fun `Basic stale observation rejection is propagated with the exact coherent input`() {
        service.onRequest = {
            rejected(RouteAdmissionFailureCode.STALE_OBSERVATION, "observation is no longer retained")
        }
        val staleStamp = stamp.copy(physicsEpoch = 9L)

        val result = client.request(25.0, staleStamp)

        assertEquals(
            RouteAdmissionFailureCode.STALE_OBSERVATION,
            (result as RouteAdmissionResult.Rejected).failure.code
        )
        assertEquals(staleStamp, service.intents.single().observationStamp)
        assertEquals(listOf(0.0), publisher.authorityOrHoldValues())
    }

    @Test
    fun `wrong train selection is rejected before Basic under a zero authority hold`() {
        service.onRequest = { error("wrong-train selection must not reach Basic") }
        val wrong = selection(route, trainId = "other")

        val result = client.request(wrong, RouteBoundary(25.0))

        assertEquals(
            RouteAdmissionFailureCode.TRAIN_NOT_FOUND,
            (result as RouteAdmissionResult.Rejected).failure.code
        )
        assertTrue(service.intents.isEmpty())
        assertEquals(listOf(0.0), publisher.authorityOrHoldValues())
        assertTrue(client.end())
        assertTrue(publisher.snapshots.last().restrictions.isEmpty())
    }

    @Test
    fun `committed authority joins the existing most restrictive constraint manager`() {
        val manager = ConstraintManager()
        val adapter = TrainRestrictionConstraintAdapter("train", manager)
        val integrated = client(service, adapter::publish)
        service.onRequest = { RouteAdmissionResult.Granted(grant(60.0)) }

        integrated.request(60.0)
        adapter.apply(stamp, FrozenRouteBinding.from(route), 20.0)
        manager.replace(
            ConstraintOwner(ConstraintOwnerType.SYSTEM_SAFETY, "test:following"),
            listOf(FollowConstraint(ConstraintKey("test", "following"), 0.0, 0.0))
        )

        val constraints = manager.snapshot().values.flatten()
        assertEquals(40.0, constraints.filterIsInstance<MovementAuthorityConstraint>()
            .single().remainingDistanceMeters)
        assertEquals(0.0, manager.permittedSpeedMetersPerSecond(0.0, 30.0, AtoSafetyProfile()))
    }

    @Test
    fun `permissive plugin output cannot widen authority before grant or after its route becomes stale`() {
        val manager = ConstraintManager()
        val adapter = TrainRestrictionConstraintAdapter("train", manager)
        val integrated = client(service, adapter::publish)
        val pendingHandle = RouteAdmissionTestFixtures.handle()
        val committed = grant(60.0)
        service.onRequest = { RouteAdmissionResult.Pending(pendingHandle, null) }
        service.onProgress = { RouteAdmissionResult.Granted(committed) }
        adapter.publish(
            TrainRestrictionSnapshot(
                sourceId = "simplesignals:fixed-block-signals",
                revision = 0L,
                trainId = "train",
                observationStamp = stamp,
                route = FrozenRouteBinding.from(route),
                restrictions = listOf(RouteSpeedRestriction("simplesignals:block/main", 0.0, 8.0))
            )
        )

        integrated.request(60.0)
        adapter.apply(stamp, FrozenRouteBinding.from(route), 0.0)
        assertEquals(0.0, manager.permittedSpeedMetersPerSecond(0.0, 30.0, AtoSafetyProfile()))

        integrated.progress()
        adapter.apply(stamp, FrozenRouteBinding.from(route), 0.0)
        assertEquals(8.0, manager.permittedSpeedMetersPerSecond(0.0, 30.0, AtoSafetyProfile()))

        val otherRoute = route(routeId = "simpleato:route/replanned", revision = 3L)
        adapter.publish(
            TrainRestrictionSnapshot(
                sourceId = "simplesignals:fixed-block-signals",
                revision = 1L,
                trainId = "train",
                observationStamp = stamp,
                route = FrozenRouteBinding.from(otherRoute),
                restrictions = listOf(RouteSpeedRestriction("simplesignals:block/main", 0.0, 20.0))
            )
        )
        adapter.apply(stamp, FrozenRouteBinding.from(route), 0.0)
        assertEquals(0.0, manager.permittedSpeedMetersPerSecond(0.0, 30.0, AtoSafetyProfile()))
    }

    @Test
    fun `explicit formation grant adoption changes the binding without releasing source resources`() {
        service.onRequest = { RouteAdmissionResult.Granted(grant(40.0)) }
        client.request(40.0)
        val approach = route("simpleato:route/contact", 3L)
        val ready = grant(approach.totalLengthMeters, approach).copy(maximumSpeedMetersPerSecond = 0.2)
        client.adoptFormationGrant(selection(approach), ready)
        assertSame(ready, client.committedGrantSnapshot)
        assertEquals(FrozenRouteBinding.from(approach), publisher.snapshots.last().route)
        assertEquals(0.2, publisher.snapshots.last().restrictions.filterIsInstance<CurrentSpeedRestriction>().single().maximumSpeedMetersPerSecond)
        assertEquals(1, service.intents.size, "adoption must not submit or reacquire an ordinary route")
        assertTrue(service.ended.isEmpty(), "source resources remain with the Basic formation owner")
        assertThrows(IllegalStateException::class.java) { client.adoptFormationGrant(selection(approach), ready.copy(trainId = "foreign")) }
        assertSame(ready, client.committedGrantSnapshot)
    }

    @Test
    fun `admitted contact speed reaches the constraint adapter even with full route authority`() {
        val manager = ConstraintManager()
        val adapter = TrainRestrictionConstraintAdapter("train", manager)
        val integrated = client(service, adapter::publish)
        val committed = grant(route.totalLengthMeters).copy(maximumSpeedMetersPerSecond = 0.2)
        service.onRequest = { RouteAdmissionResult.Granted(committed) }
        integrated.request(route.totalLengthMeters)
        adapter.apply(stamp, FrozenRouteBinding.from(route), 0.0)
        assertEquals(0.2, manager.permittedSpeedMetersPerSecond(0.0, 30.0, AtoSafetyProfile()))
        adapter.apply(stamp, FrozenRouteBinding.from(route), route.totalLengthMeters - 1.0)
        assertTrue(manager.permittedSpeedMetersPerSecond(0.0, 30.0, AtoSafetyProfile()) <= 0.2)
    }

    @Test
    fun `pending admission and prepared continuation retain the committed speed ceiling`() {
        val held = grant(route.totalLengthMeters).copy(maximumSpeedMetersPerSecond = 0.2)
        service.onRequest = { RouteAdmissionResult.Granted(held) }
        client.request(route.totalLengthMeters)
        val next = RouteSnapshot(route.routeId, route.revision + 1L, route.topologyRevision,
            route.segments + RouteSegmentSnapshot(UUID(0L, 9L), ViewDirection.AB, 0.0, 60.0))
        val continued = selection(next)
        client.prepareContinuation(continued)
        assertEquals(0.2, publisher.snapshots.last().restrictions.filterIsInstance<CurrentSpeedRestriction>().single().maximumSpeedMetersPerSecond)
        val pending = RouteAdmissionTestFixtures.handle()
        service.onRequest = { RouteAdmissionResult.Pending(pending, held) }
        client.request(continued, RouteBoundary(next.totalLengthMeters))
        assertEquals(0.2, publisher.snapshots.last().restrictions.filterIsInstance<CurrentSpeedRestriction>().single().maximumSpeedMetersPerSecond)
        service.onProgress = { RouteAdmissionResult.Pending(pending, held.copy(maximumSpeedMetersPerSecond = null)) }
        assertThrows(IllegalStateException::class.java) { client.progress() }
        assertEquals(0.2, publisher.snapshots.last().restrictions.filterIsInstance<CurrentSpeedRestriction>().single().maximumSpeedMetersPerSecond)
    }

    private fun client(
        service: RouteResourceAdmissionService,
        publisher: work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublisher
    ) = RouteAuthorityAdmissionClient(
        trainId = "train",
        requestId = "simpleato:request/train",
        owner = owner,
        admissionService = service,
        restrictionPublisher = publisher
    )

    private fun RouteAuthorityAdmissionClient.request(
        authority: Double,
        observationStamp: RailwayStateStamp = stamp
    ) = request(selection.copy(observationStamp = observationStamp), RouteBoundary(authority))

    private fun selection(
        route: RouteSnapshot,
        trainId: String = "train",
        directions: Map<UUID, ViewDirection> = emptyMap(),
        worldId: String = stamp.worldId
    ): FrozenRouteSelectionResult.Selected {
        val topology = buildTestTopology(route.topologyRevision,
            route.segments.groupBy { it.railId }.map { (id, segments) ->
                TestTopologyRail(id, segments.maxOf { it.endArcLengthMeters })
            }, emptyMap())
        return FrozenRouteSelectionResult.Selected(trainId, stamp.copy(worldId = worldId), route,
            RouteOperatingDirectionLimit.calculate(worldId, route, topology,
                RailOperatingDirections(topology, route.segments.associate { it.railId to directions[it.railId] })))
    }

    private fun route(
        routeId: String = "simpleato:route/main",
        revision: Long = 2L
    ) = RouteSnapshot(
        routeId,
        revision,
        stamp.topologyRevision,
        listOf(RouteSegmentSnapshot(UUID.randomUUID(), ViewDirection.AB, 0.0, 100.0))
    )

    private fun grant(
        authority: Double,
        selectedRoute: RouteSnapshot = route,
        id: RouteGrantId = RouteAdmissionTestFixtures.grantId()
    ) = RouteGrantSnapshot(
        id,
        owner,
        "train",
        selectedRoute.routeId,
        selectedRoute.revision,
        stamp,
        RouteBoundary(authority)
    )

    private fun rejected(code: RouteAdmissionFailureCode, detail: String) =
        RouteAdmissionResult.Rejected(RouteAdmissionFailure(code, detail))

    private class RecordingPublisher :
        work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublisher {
        val snapshots = mutableListOf<TrainRestrictionSnapshot>()
        var nextFailure: RuntimeException? = null

        override fun publish(snapshot: TrainRestrictionSnapshot): TrainRestrictionPublicationResult {
            nextFailure?.let { failure ->
                nextFailure = null
                throw failure
            }
            snapshots += snapshot
            return TrainRestrictionPublicationResult(
                TrainRestrictionPublicationStatus.ACCEPTED,
                snapshot.revision
            )
        }

        /** Numeric trace convention: 0 denotes a no-grant speed hold, never a physical MA. */
        fun authorityOrHoldValues(): List<Double> = snapshots.mapNotNull { snapshot ->
            val value = snapshot.restrictions.singleOrNull { it.restrictionId == "simpleato:committed-authority" }
            when (value) {
                is MovementAuthorityRestriction -> value.routeDistanceMeters
                is CurrentSpeedRestriction -> 0.0.also { assertEquals(0.0, value.maximumSpeedMetersPerSecond) }
                else -> null
            }
        }
    }

    private class FakeAdmissionService : RouteResourceAdmissionService {
        var onRequest: (RouteResourceIntent) -> RouteAdmissionResult = { error("unexpected request") }
        var onProgress: (RouteAdmissionHandle) -> RouteAdmissionResult = { error("unexpected progress") }
        var onSuspendReversal: (RouteGrantId) -> RouteGrantReversalResult = {
            error("unexpected reversal suspension")
        }
        var onRebaseReversal: (RouteResourceIntent) -> RouteGrantReversalResult = {
            error("unexpected reversal rebase")
        }
        var endResult: Boolean = true
        val intents = mutableListOf<RouteResourceIntent>()
        val progressed = mutableListOf<RouteAdmissionHandle>()
        val ended = mutableListOf<RouteGrantId>()

        override fun request(intent: RouteResourceIntent): RouteAdmissionResult {
            intents += intent
            return onRequest(intent)
        }

        override fun progress(handle: RouteAdmissionHandle): RouteAdmissionResult {
            progressed += handle
            return onProgress(handle)
        }

        override fun suspendReleaseForReversal(grantId: RouteGrantId): RouteGrantReversalResult =
            onSuspendReversal(grantId)

        override fun rebaseAfterReversal(intent: RouteResourceIntent): RouteGrantReversalResult =
            onRebaseReversal(intent)

        override fun endGrant(grantId: RouteGrantId): Boolean {
            ended += grantId
            return endResult
        }
    }
}
