package work.art1st.javarailways.simpleato.admission

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.ato.AtoSafetyProfile
import work.art1st.javarailways.basic.api.ato.constraint.ConstraintKey
import work.art1st.javarailways.basic.api.ato.constraint.ConstraintManager
import work.art1st.javarailways.basic.api.ato.constraint.ConstraintOwner
import work.art1st.javarailways.basic.api.ato.constraint.ConstraintOwnerType
import work.art1st.javarailways.basic.api.ato.constraint.FollowConstraint
import work.art1st.javarailways.basic.api.ato.constraint.MovementAuthorityConstraint
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner
import work.art1st.javarailways.basic.api.observation.RailwayStateStamp
import work.art1st.javarailways.basic.api.route.RouteAdmissionFailure
import work.art1st.javarailways.basic.api.route.RouteAdmissionFailureCode
import work.art1st.javarailways.basic.api.route.RouteAdmissionHandle
import work.art1st.javarailways.basic.api.route.RouteAdmissionResult
import work.art1st.javarailways.basic.api.route.RouteAdmissionTestFixtures
import work.art1st.javarailways.basic.api.route.RouteBoundary
import work.art1st.javarailways.basic.api.route.RouteGrantId
import work.art1st.javarailways.basic.api.route.RouteGrantSnapshot
import work.art1st.javarailways.basic.api.route.RouteResourceAdmissionService
import work.art1st.javarailways.basic.api.route.RouteResourceIntent
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.simpleato.api.restriction.MovementAuthorityRestriction
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublicationResult
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublicationStatus
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionSnapshot
import work.art1st.javarailways.simpleato.restriction.TrainRestrictionConstraintAdapter
import work.art1st.javarailways.simpleato.routing.FrozenRouteSelectionResult
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
    fun `first immediate grant replaces initial zero authority with committed boundary`() {
        val grant = grant(40.0)
        service.onRequest = { RouteAdmissionResult.Granted(grant) }

        val result = client.request(40.0)

        assertSame(grant, (result as RouteAdmissionResult.Granted).grant)
        assertEquals(listOf(0.0, 40.0), publisher.authorities())
        assertEquals(1, service.intents.size)
        assertEquals(selection.route, service.intents.single().route)
        assertEquals(selection.observationStamp, service.intents.single().observationStamp)
        assertEquals("train", service.intents.single().trainId)
    }

    @Test
    fun `first pending request retains zero authority until progress commits a grant`() {
        val handle = RouteAdmissionTestFixtures.handle()
        val grant = grant(35.0)
        service.onRequest = { RouteAdmissionResult.Pending(handle, null) }
        service.onProgress = { supplied ->
            assertSame(handle, supplied)
            RouteAdmissionResult.Granted(grant)
        }

        val pending = client.request(35.0)

        assertSame(handle, (pending as RouteAdmissionResult.Pending).handle)
        assertEquals(listOf(0.0), publisher.authorities())

        val ready = client.progress()

        assertSame(grant, (ready as RouteAdmissionResult.Granted).grant)
        assertEquals(listOf(0.0, 35.0), publisher.authorities())
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
        assertEquals(listOf(0.0, 30.0), publisher.authorities())
    }

    @Test
    fun `denied extension retains the earlier committed authority`() {
        val grant = grant(30.0)
        var requests = 0
        service.onRequest = {
            if (requests++ == 0) RouteAdmissionResult.Granted(grant)
            else rejected(RouteAdmissionFailureCode.INVALID_BOUNDARY, "extension denied")
        }

        client.request(30.0)
        val denied = client.request(60.0, stamp.copy(physicsEpoch = 11L))

        assertEquals(
            RouteAdmissionFailureCode.INVALID_BOUNDARY,
            (denied as RouteAdmissionResult.Rejected).failure.code
        )
        assertEquals(listOf(0.0, 30.0), publisher.authorities())
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
        assertEquals(listOf(0.0), publisher.authorities())
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
        assertEquals(listOf(0.0), publisher.authorities())
        assertFalse(client.end())
        assertEquals(listOf(0.0), publisher.authorities())

        service.onRequest = { RouteAdmissionResult.Granted(grant(60.0)) }
        val retry = client.request(60.0)
        assertEquals(
            RouteAdmissionFailureCode.WORLD_STALLED,
            (retry as RouteAdmissionResult.Rejected).failure.code
        )
        assertEquals(1, service.intents.size)
        assertEquals(listOf(0.0), publisher.authorities())
    }

    @Test
    fun `explicit end waits for Basic and only then withdraws committed authority`() {
        val grant = grant(45.0)
        service.onRequest = { RouteAdmissionResult.Granted(grant) }
        service.endResult = false
        client.request(45.0)

        assertFalse(client.end())
        assertEquals(listOf(0.0, 45.0), publisher.authorities())

        service.endResult = true
        assertTrue(client.end())
        assertSame(grant.grantId, service.ended.last())
        assertTrue(publisher.snapshots.last().restrictions.isEmpty())
    }

    @Test
    fun `restriction publication failure after Basic end retries withdrawal without ending grant twice`() {
        val grant = grant(45.0)
        service.onRequest = { RouteAdmissionResult.Granted(grant) }
        client.request(45.0)
        publisher.nextFailure = IllegalStateException("publication failed")

        assertThrows(IllegalStateException::class.java) { client.end() }
        assertEquals(1, service.ended.size)
        assertEquals(45.0, publisher.authorities().last())

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
        assertEquals(listOf(0.0, 30.0), publisher.authorities())
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
        assertEquals(listOf(0.0), publisher.authorities())
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
        assertEquals(listOf(0.0), publisher.authorities())
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
        adapter.apply(stamp, work.art1st.javarailways.simpleato.api.restriction
            .TrainRestrictionRouteBinding.from(route), 20.0)
        manager.replace(
            ConstraintOwner(ConstraintOwnerType.SYSTEM_SAFETY, "test:following"),
            listOf(FollowConstraint(ConstraintKey("test", "following"), 0.0, 0.0))
        )

        val constraints = manager.snapshot().values.flatten()
        assertEquals(40.0, constraints.filterIsInstance<MovementAuthorityConstraint>()
            .single().remainingDistanceMeters)
        assertEquals(0.0, manager.permittedSpeedMetersPerSecond(0.0, 30.0, AtoSafetyProfile()))
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
        trainId: String = "train"
    ) = FrozenRouteSelectionResult.Selected(trainId, stamp, route)

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

        fun authorities(): List<Double> = snapshots.mapNotNull { snapshot ->
            snapshot.restrictions.filterIsInstance<MovementAuthorityRestriction>()
                .singleOrNull()?.routeDistanceMeters
        }
    }

    private class FakeAdmissionService : RouteResourceAdmissionService {
        var onRequest: (RouteResourceIntent) -> RouteAdmissionResult = { error("unexpected request") }
        var onProgress: (RouteAdmissionHandle) -> RouteAdmissionResult = { error("unexpected progress") }
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

        override fun endGrant(grantId: RouteGrantId): Boolean {
            ended += grantId
            return endResult
        }
    }
}
