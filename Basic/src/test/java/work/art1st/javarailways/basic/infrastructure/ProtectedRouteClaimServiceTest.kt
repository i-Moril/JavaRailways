package work.art1st.javarailways.basic.infrastructure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureFootprint
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner
import work.art1st.javarailways.basic.api.infrastructure.StableInfrastructureResource
import work.art1st.javarailways.basic.api.observation.RailwayObservation
import work.art1st.javarailways.basic.api.observation.RailwayStateStamp
import work.art1st.javarailways.basic.api.route.DirectedRouteBoundary
import work.art1st.javarailways.basic.api.route.ExitClearanceEnvelope
import work.art1st.javarailways.basic.api.route.ProtectedRouteClaimStatus
import work.art1st.javarailways.basic.api.route.ProtectedRouteRequest
import work.art1st.javarailways.basic.api.route.ProtectedRouteWindow
import work.art1st.javarailways.basic.api.route.ProtectionRegionRef
import work.art1st.javarailways.core.api.physics.RailwayWorldFrameSnapshot
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.RailInterval
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.api.physics.train.SweptRailSpanSnapshot
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot
import java.util.UUID

class ProtectedRouteClaimServiceTest {
    private val railId = UUID.randomUUID()
    private var topologyRevision = 4L
    private var observation = observation()
    private val validResources = mutableSetOf(
        resource("turnout/a"),
        resource("turnout/b")
    )
    private val registry = InfrastructureLeaseRegistry(
        currentTopologyRevision = { topologyRevision },
        resourceExists = { it in validResources }
    )

    @Test
    fun `route without controlled resources succeeds without creating a claim`() {
        val service = service { ProtectedRouteValidation.NotRequired }

        val result = service.acquire(Any(), request(null))

        assertEquals(ProtectedRouteClaimStatus.NOT_REQUIRED, result.status)
        assertTrue(result.successful)
        assertTrue(registry.claimSnapshots().isEmpty())
    }

    @Test
    fun `missing protection is typed and leaves no partial claim`() {
        val service = service {
            ProtectedRouteValidation.Rejected(
                ProtectedRouteClaimStatus.PROTECTION_REQUIRED,
                "turnout/a is outside the protection window"
            )
        }

        val result = service.acquire(Any(), request(null))

        assertEquals(ProtectedRouteClaimStatus.PROTECTION_REQUIRED, result.status)
        assertTrue(registry.claimSnapshots().isEmpty())
    }

    @Test
    fun `validation failure is typed and leaves no partial claim`() {
        val service = service {
            error("broken validator")
        }

        val result = service.acquire(Any(), request(null))

        assertEquals(ProtectedRouteClaimStatus.VALIDATION_FAILED, result.status)
        assertTrue(registry.claimSnapshots().isEmpty())
    }

    @Test
    fun `one throat window acquires one atomic footprint and releases by exact owner`() {
        val platformOwner = Any()
        val routeOwner = InfrastructureOwner("test:route/main", 1L)
        val service = service {
            validated(resource("turnout/a"), resource("turnout/b"))
        }
        val request = request(
            window("test:region/a", 0.0, 80.0, 100.0),
            routeOwner
        )

        val acquired = service.acquire(platformOwner, request)

        assertEquals(ProtectedRouteClaimStatus.ACQUIRED, acquired.status)
        assertEquals(
            setOf(resource("turnout/a"), resource("turnout/b")),
            registry.claimSnapshots().single().footprint.resources.toSet()
        )
        assertEquals(ProtectedRouteClaimStatus.RELEASED, service.release(platformOwner, routeOwner).status)
        assertTrue(registry.claimSnapshots().isEmpty())
    }

    @Test
    fun `invalid member rejects the whole resolved footprint`() {
        val service = service {
            validated(resource("turnout/a"), resource("turnout/missing"))
        }

        val result = service.acquire(
            Any(),
            request(window("test:region/a", 0.0, 80.0, 100.0))
        )

        assertEquals(ProtectedRouteClaimStatus.INVALID_RESOURCE, result.status)
        assertTrue(registry.claimSnapshots().isEmpty())
    }

    @Test
    fun `topology change during resolution rejects before claim mutation`() {
        val service = service {
            topologyRevision = 5L
            validated(resource("turnout/a"))
        }

        val result = service.acquire(
            Any(),
            request(window("test:region/a", 0.0, 80.0, 100.0))
        )

        assertEquals(ProtectedRouteClaimStatus.STALE_TOPOLOGY, result.status)
        assertTrue(registry.claimSnapshots().isEmpty())
    }

    @Test
    fun `stale observation world or epoch rejects before validation and claim mutation`() {
        var validations = 0
        val service = service {
            validations++
            validated(resource("turnout/a"))
        }

        val staleWorld = service.acquire(
            Any(),
            request(null, stamp = observation.stamp.copy(worldId = "other-world"))
        )
        val staleEpoch = service.acquire(
            Any(),
            request(null, stamp = observation.stamp.copy(physicsEpoch = observation.stamp.physicsEpoch - 1L))
        )

        assertEquals(ProtectedRouteClaimStatus.STALE_OBSERVATION, staleWorld.status)
        assertEquals(ProtectedRouteClaimStatus.STALE_OBSERVATION, staleEpoch.status)
        assertEquals(0, validations)
        assertTrue(registry.claimSnapshots().isEmpty())
    }

    @Test
    fun `missing latest observation fails closed before validation`() {
        var validations = 0
        val service = service(latestObservation = { null }) {
            validations++
            validated(resource("turnout/a"))
        }

        val result = service.acquire(Any(), request(null))

        assertEquals(ProtectedRouteClaimStatus.STALE_OBSERVATION, result.status)
        assertEquals(0, validations)
        assertTrue(registry.claimSnapshots().isEmpty())
    }

    @Test
    fun `observation topology must match both route and current topology`() {
        val result = service { validated(resource("turnout/a")) }.acquire(
            Any(),
            request(null, stamp = observation.stamp.copy(topologyRevision = topologyRevision + 1L))
        )

        assertEquals(ProtectedRouteClaimStatus.STALE_TOPOLOGY, result.status)
        assertTrue(registry.claimSnapshots().isEmpty())
    }

    @Test
    fun `request train must exist in the exact coherent observation`() {
        observation = observation(train("another-train", 10.0, 20.0))

        val result = service { validated(resource("turnout/a")) }.acquire(Any(), request(null))

        assertEquals(ProtectedRouteClaimStatus.INVALID_REQUEST, result.status)
        assertTrue(registry.claimSnapshots().isEmpty())
    }

    @Test
    fun `physical blockers are sorted immutable and prevent registry validation`() {
        observation = observation(
            train("train", 20.0, 30.0),
            train("z-blocker", 25.0, 35.0),
            train("a-blocker", 15.0, 25.0)
        )
        val missing = resource("turnout/missing")
        val plan = CompiledRouteAdmission.create(
            InfrastructureFootprint(listOf(missing)),
            emptyList(),
            OccupancyQueryGeometry.create(listOf(RailInterval(railId, 20.0, 30.0)))
        )

        val result = service { ProtectedRouteValidation.Validated(plan) }.acquire(Any(), request(null))

        assertEquals(ProtectedRouteClaimStatus.PHYSICALLY_OCCUPIED, result.status)
        assertEquals(listOf("a-blocker", "z-blocker"), result.occupyingTrainIds)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (result.occupyingTrainIds as MutableList<String>).clear()
        }
        assertTrue(registry.claimSnapshots().isEmpty())
    }

    @Test
    fun `request train is excluded from its own occupancy query`() {
        observation = observation(train("train", 20.0, 30.0))
        val plan = CompiledRouteAdmission.create(
            InfrastructureFootprint(listOf(resource("turnout/a"))),
            emptyList(),
            OccupancyQueryGeometry.create(listOf(RailInterval(railId, 20.0, 30.0)))
        )

        val result = service { ProtectedRouteValidation.Validated(plan) }.acquire(Any(), request(null))

        assertEquals(ProtectedRouteClaimStatus.ACQUIRED, result.status)
    }

    @Test
    fun `physical blocker prevents replacement and preserves the existing claim`() {
        val platformOwner = Any()
        val oldOwner = InfrastructureOwner("test:route/main", 1L)
        val newOwner = InfrastructureOwner("test:route/main", 2L)
        val plan = CompiledRouteAdmission.create(
            InfrastructureFootprint(listOf(resource("turnout/a"))),
            emptyList(),
            OccupancyQueryGeometry.create(listOf(RailInterval(railId, 20.0, 30.0)))
        )
        val service = service { ProtectedRouteValidation.Validated(plan) }
        assertEquals(
            ProtectedRouteClaimStatus.ACQUIRED,
            service.acquire(platformOwner, request(null, oldOwner)).status
        )
        observation = observation(
            train("train", 20.0, 30.0),
            train("blocker", 25.0, 35.0),
            epoch = observation.stamp.physicsEpoch + 1L
        )

        val result = service.replace(
            platformOwner,
            oldOwner,
            request(null, newOwner, observation.stamp)
        )

        assertEquals(ProtectedRouteClaimStatus.PHYSICALLY_OCCUPIED, result.status)
        assertEquals(listOf("blocker"), result.occupyingTrainIds)
        assertEquals(listOf(oldOwner), registry.claimSnapshots().map { it.owner })
    }

    @Test
    fun `observation change after occupancy query rejects before claim mutation`() {
        val first = observation
        val newer = observation(epoch = first.stamp.physicsEpoch + 1L)
        var reads = 0
        val service = service(
            latestObservation = {
                reads++
                if (reads < 3) first else newer
            }
        ) { validated(resource("turnout/a")) }

        val result = service.acquire(Any(), request(null, stamp = first.stamp))

        assertEquals(ProtectedRouteClaimStatus.STALE_OBSERVATION, result.status)
        assertTrue(registry.claimSnapshots().isEmpty())
    }

    @Test
    fun `conflicting complete requests expose blocker and preserve first claim`() {
        val service = service {
            validated(resource("turnout/a"))
        }
        val window = window("test:region/a", 0.0, 80.0, 100.0)
        val first = service.acquire(Any(), request(window, InfrastructureOwner("test:route/first", 1L)))

        val second = service.acquire(Any(), request(window, InfrastructureOwner("test:route/second", 1L)))

        assertEquals(ProtectedRouteClaimStatus.ACQUIRED, first.status)
        assertEquals(ProtectedRouteClaimStatus.RESOURCE_CONFLICT, second.status)
        assertEquals("test:route/first", second.blockers.single().owner.ownerId)
        assertEquals(listOf("test:route/first"), registry.claimSnapshots().map { it.owner.ownerId })
    }

    @Test
    fun `validated plan copies selection order and requires each selection in its footprint`() {
        val turnout = StableInfrastructureResource(TurnoutResourceIdentity.TYPE_ID, "test:turnout/main")
        val selection = ResolvedTurnoutSelection(
            turnout,
            RailEndpointRef(UUID(0L, 10L), RailEndpoint.B),
            RailEndpointRef(UUID(0L, 11L), RailEndpoint.A),
            12.0
        )
        val source = mutableListOf(selection)
        val geometry = OccupancyQueryGeometry.create(listOf(RailInterval(railId, 0.0, 1.0)))
        val plan = CompiledRouteAdmission.create(InfrastructureFootprint(listOf(turnout)), source, geometry)

        source.clear()

        assertEquals(listOf(selection), plan.turnoutSelections)
        assertThrows(IllegalArgumentException::class.java) {
            CompiledRouteAdmission.create(
                InfrastructureFootprint(listOf(resource("turnout/a"))),
                listOf(selection),
                geometry
            )
        }
    }

    @Test
    fun `claim service submits the plan footprint without applying turnout metadata`() {
        val turnout = StableInfrastructureResource(TurnoutResourceIdentity.TYPE_ID, "test:turnout/service")
        validResources += turnout
        val selection = ResolvedTurnoutSelection(
            turnout,
            RailEndpointRef(UUID(0L, 12L), RailEndpoint.B),
            RailEndpointRef(UUID(0L, 13L), RailEndpoint.A),
            8.0
        )
        val plan = CompiledRouteAdmission.create(
            InfrastructureFootprint(listOf(turnout)),
            listOf(selection),
            OccupancyQueryGeometry.create(listOf(RailInterval(railId, 0.0, 1.0)))
        )
        val service = service { ProtectedRouteValidation.Validated(plan) }

        val acquired = service.acquire(Any(), request(window("test:region/a", 0.0, 80.0, 100.0)))

        assertEquals(ProtectedRouteClaimStatus.ACQUIRED, acquired.status)
        assertEquals(plan.resources, registry.claimSnapshots().single().footprint)
        assertEquals(listOf(selection), plan.turnoutSelections)
    }

    private fun service(
        latestObservation: () -> RailwayObservation? = { observation },
        validate: (ProtectedRouteRequest) -> ProtectedRouteValidation
    ) =
        ProtectedRouteClaimService(
            registry,
            currentTopologyRevision = { topologyRevision },
            latestObservation = latestObservation,
            validate = validate
        )

    private fun request(
        window: ProtectedRouteWindow?,
        owner: InfrastructureOwner = InfrastructureOwner("test:route/main", 1L),
        stamp: RailwayStateStamp = observation.stamp
    ) = ProtectedRouteRequest(
        requestId = "test:request/main",
        owner = owner,
        trainId = "train",
        observationStamp = stamp,
        route = RouteSnapshot(
            "route",
            2L,
            4L,
            listOf(RouteSegmentSnapshot(railId, ViewDirection.AB, 0.0, 100.0))
        ),
        window = window
    )

    private fun window(
        regionId: String,
        entryMeters: Double,
        exitMeters: Double,
        clearanceMeters: Double
    ) = ProtectedRouteWindow(
        ProtectionRegionRef(regionId, 1L),
        boundary("$regionId/entry", entryMeters),
        boundary("$regionId/exit", exitMeters),
        ExitClearanceEnvelope(boundary("$regionId/clear", clearanceMeters))
    )

    private fun boundary(id: String, routeDistanceMeters: Double) = DirectedRouteBoundary(
        boundaryId = id,
        position = RailPositionSnapshot(railId, ViewDirection.AB, routeDistanceMeters),
        routeDistanceMeters = routeDistanceMeters
    )

    private fun resource(id: String) = StableInfrastructureResource("test:controlled", id)

    private fun observation(
        vararg trains: TrainFrameSnapshot,
        epoch: Long = 7L,
        worldId: String = "world"
    ): RailwayObservation {
        val sourceFrames = trains.takeIf { it.isNotEmpty() } ?: arrayOf(train("train", 0.0, 1.0, epoch))
        val frames = sourceFrames.map { frame ->
            if (frame.physicsEpoch == epoch) frame else train(
                frame.trainId,
                frame.sweptSpans.single().minArcAB,
                frame.sweptSpans.single().maxArcAB,
                epoch
            )
        }
        return RailwayObservation.fromCore(
            worldId,
            RailwayWorldFrameSnapshot.create(epoch, topologyRevision, frames)
        )
    }

    private fun train(
        id: String,
        minArcMeters: Double,
        maxArcMeters: Double,
        epoch: Long = 7L
    ) = TrainFrameSnapshot.create(
        trainId = id,
        trainName = id,
        revision = 1L,
        physicsEpoch = epoch,
        speedMetersPerSecond = 0.0,
        derailed = false,
        reversed = false,
        cars = emptyList(),
        head = RailPositionSnapshot(railId, ViewDirection.AB, maxArcMeters),
        tail = RailPositionSnapshot(railId, ViewDirection.AB, minArcMeters),
        sweptSpans = listOf(SweptRailSpanSnapshot(railId, minArcMeters, maxArcMeters))
    )

    private fun validated(vararg resources: StableInfrastructureResource) =
        ProtectedRouteValidation.Validated(
            CompiledRouteAdmission.create(
                InfrastructureFootprint(resources.toList()),
                emptyList(),
                OccupancyQueryGeometry.create(emptyList())
            )
        )
}
