package work.art1st.javarailways.basic.infrastructure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureFootprint
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureMutationStatus
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureResource
import work.art1st.javarailways.basic.api.infrastructure.RailIntervalResource
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
    private val validResources = mutableSetOf<InfrastructureResource>(
        resource("turnout/a"),
        resource("turnout/b")
    )
    private val registry = InfrastructureLeaseRegistry(
        currentTopologyRevision = { topologyRevision },
        resourceExists = { resource ->
            resource in validResources || resource is RailIntervalResource && resource.railId == railId
        }
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
    fun `terminal start polling and cancellation are idempotent`() {
        val start = service { ProtectedRouteValidation.NotRequired }.acquire(Any(), request(null))
        val first = start.poll() as ProtectedRouteAdmissionProgress.Terminal

        start.cancel()
        start.cancel()
        val second = start.poll() as ProtectedRouteAdmissionProgress.Terminal

        assertSame(first.result, second.result)
        assertEquals(ProtectedRouteClaimStatus.NOT_REQUIRED, second.result.status)
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
    fun `immediate successful admission carries the initialized live claim owner`() {
        val start = service { validated(resource("turnout/a")) }.acquire(Any(), request(null))

        val terminal = start.poll() as ProtectedRouteAdmissionProgress.Terminal
        val release = terminal.liveClaim!!.consume(
            observation(epoch = observation.stamp.physicsEpoch + 1L)
        )

        assertEquals(ProtectedRouteClaimStatus.ACQUIRED, terminal.result.status)
        assertInstanceOf(LiveProtectedRouteClaimUpdate.Completed::class.java, release)
        assertTrue(registry.claimSnapshots().isEmpty())
        assertSame(terminal.liveClaim, (start.poll() as ProtectedRouteAdmissionProgress.Terminal).liveClaim)
    }

    @Test
    fun `pending successful admission carries the same live claim only after ready`() {
        val ticket = FakePreparationTicket(state = InfrastructurePreparationState.PREPARING)
        val start = service(
            preparationActuator = InfrastructurePreparationActuator {
                InfrastructurePreparationStart.Accepted(ticket)
            }
        ) { validated(resource("turnout/a")) }.acquire(Any(), request(null))

        assertTrue(start is ProtectedRouteAdmissionStart.Pending)
        assertEquals(ProtectedRouteAdmissionProgress.Pending, start.poll())
        ticket.state = InfrastructurePreparationState.READY
        val terminal = start.poll() as ProtectedRouteAdmissionProgress.Terminal

        assertEquals(ProtectedRouteClaimStatus.ACQUIRED, terminal.result.status)
        assertNotNull(terminal.liveClaim)
        assertSame(terminal.liveClaim, (start.poll() as ProtectedRouteAdmissionProgress.Terminal).liveClaim)
    }

    @Test
    fun `successful replacement carries a new live claim owner`() {
        val platformOwner = Any()
        val oldOwner = InfrastructureOwner("test:route/replace", 1L)
        val newOwner = InfrastructureOwner("test:route/replace", 2L)
        var validation = validated(resource("turnout/a"))
        val service = service { validation }
        val acquired = service.acquire(platformOwner, request(null, oldOwner)).poll()
            as ProtectedRouteAdmissionProgress.Terminal
        validation = validated(resource("turnout/b"))

        val replaced = service.replace(platformOwner, oldOwner, request(null, newOwner)).poll()
            as ProtectedRouteAdmissionProgress.Terminal

        assertEquals(ProtectedRouteClaimStatus.ACQUIRED, acquired.result.status)
        assertNotNull(acquired.liveClaim)
        assertEquals(ProtectedRouteClaimStatus.REPLACED, replaced.result.status)
        assertNotNull(replaced.liveClaim)
        assertTrue(replaced.liveClaim !== acquired.liveClaim)
        assertEquals(listOf(newOwner), registry.claimSnapshots().map { it.owner })
    }

    @Test
    fun `failed not-required and unchanged admissions do not invent live claim owners`() {
        val platformOwner = Any()
        val owner = InfrastructureOwner("test:route/live-owner", 1L)
        val admittedService = service { validated(resource("turnout/a")) }
        val admitted = admittedService.acquire(platformOwner, request(null, owner))
        assertNotNull((admitted.poll() as ProtectedRouteAdmissionProgress.Terminal).liveClaim)

        val unchanged = admittedService.acquire(platformOwner, request(null, owner)).poll()
            as ProtectedRouteAdmissionProgress.Terminal
        val notRequired = service { ProtectedRouteValidation.NotRequired }
            .acquire(Any(), request(null, InfrastructureOwner("test:none", 1L)))
            .poll() as ProtectedRouteAdmissionProgress.Terminal
        val rejected = service {
            ProtectedRouteValidation.Rejected(ProtectedRouteClaimStatus.PROTECTION_REQUIRED, "missing protection")
        }.acquire(Any(), request(null, InfrastructureOwner("test:rejected", 1L)))
            .poll() as ProtectedRouteAdmissionProgress.Terminal

        assertEquals(ProtectedRouteClaimStatus.UNCHANGED, unchanged.result.status)
        assertNull(unchanged.liveClaim)
        assertEquals(ProtectedRouteClaimStatus.NOT_REQUIRED, notRequired.result.status)
        assertNull(notRequired.liveClaim)
        assertEquals(ProtectedRouteClaimStatus.PROTECTION_REQUIRED, rejected.result.status)
        assertNull(rejected.liveClaim)
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
    fun `frozen route must start at the same observation tail before claim mutation`() {
        val plan = compiledAdmission(
            InfrastructureFootprint(listOf(resource("turnout/a"))),
            emptyList(),
            OccupancyQueryGeometry.create(emptyList()),
            testRouteProgressPath(railId, 100.0, routeStartAbMeters = 10.0)
        )
        val result = service { ProtectedRouteValidation.Validated(plan) }.acquire(
            Any(),
            request(null, routeStartMeters = 10.0)
        )

        assertEquals(ProtectedRouteClaimStatus.INVALID_REQUEST, result.status)
        assertTrue(registry.claimSnapshots().isEmpty())
    }

    @Test
    fun `replacement with a mismatched route start preserves the existing claim`() {
        val platformOwner = Any()
        val oldOwner = InfrastructureOwner("test:route/main", 1L)
        val newOwner = InfrastructureOwner("test:route/main", 2L)
        val service = service { request ->
            ProtectedRouteValidation.Validated(
                compiledAdmission(
                    InfrastructureFootprint(listOf(resource("turnout/a"))),
                    emptyList(),
                    OccupancyQueryGeometry.create(emptyList()),
                    testRouteProgressPath(
                        railId,
                        100.0,
                        request.route.segments.first().startArcLengthMeters
                    )
                )
            )
        }
        assertEquals(
            ProtectedRouteClaimStatus.ACQUIRED,
            service.acquire(platformOwner, request(null, oldOwner)).status
        )

        val rejected = service.replace(
            platformOwner,
            oldOwner,
            request(null, newOwner, routeStartMeters = 10.0)
        )

        assertEquals(ProtectedRouteClaimStatus.INVALID_REQUEST, rejected.status)
        assertEquals(listOf(oldOwner), registry.claimSnapshots().map { it.owner })
    }

    @Test
    fun `physical blockers are sorted immutable and prevent registry validation`() {
        observation = observation(
            train("train", 20.0, 30.0),
            train("z-blocker", 25.0, 35.0),
            train("a-blocker", 15.0, 25.0)
        )
        val missing = resource("turnout/missing")
        val plan = compiledAdmission(
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
        val plan = compiledAdmission(
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
        val service = service { request ->
            ProtectedRouteValidation.Validated(
                compiledAdmission(
                    InfrastructureFootprint(listOf(resource("turnout/a"))),
                    emptyList(),
                    OccupancyQueryGeometry.create(listOf(RailInterval(railId, 20.0, 30.0))),
                    testRouteProgressPath(
                        railId,
                        100.0,
                        request.route.segments.first().startArcLengthMeters
                    )
                )
            )
        }
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
    fun `validated plan copies preparation order and requires one claimed resource per preparation`() {
        val turnout = StableInfrastructureResource(TurnoutResourceIdentity.TYPE_ID, "test:turnout/main")
        val selection = ResolvedTurnoutSelection(
            turnout,
            RailEndpointRef(UUID(0L, 10L), RailEndpoint.B),
            RailEndpointRef(UUID(0L, 11L), RailEndpoint.A),
            12.0
        )
        val source = mutableListOf(selection)
        val geometry = OccupancyQueryGeometry.create(listOf(RailInterval(railId, 0.0, 1.0)))
        val plan = compiledAdmission(InfrastructureFootprint(listOf(turnout)), source, geometry)

        source.clear()

        assertEquals(listOf(selection), plan.preparations)
        assertThrows(IllegalArgumentException::class.java) {
            compiledAdmission(
                InfrastructureFootprint(listOf(resource("turnout/a"))),
                listOf(selection),
                geometry
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            compiledAdmission(
                InfrastructureFootprint(listOf(turnout)),
                listOf(selection, selection),
                geometry
            )
        }
    }

    @Test
    fun `claim service submits the plan footprint and starts preparations in route order`() {
        val turnout = StableInfrastructureResource(TurnoutResourceIdentity.TYPE_ID, "test:turnout/service")
        validResources += turnout
        val selection = ResolvedTurnoutSelection(
            turnout,
            RailEndpointRef(UUID(0L, 12L), RailEndpoint.B),
            RailEndpointRef(UUID(0L, 13L), RailEndpoint.A),
            8.0
        )
        val plan = compiledAdmission(
            InfrastructureFootprint(listOf(turnout)),
            listOf(selection),
            OccupancyQueryGeometry.create(listOf(RailInterval(railId, 0.0, 1.0)))
        )
        val committed = mutableListOf<List<InfrastructurePreparationRequirement>>()
        val service = service(
            preparationActuator = InfrastructurePreparationActuator { requirements ->
                committed += requirements
                accepted(InfrastructurePreparationState.READY)
            }
        ) { ProtectedRouteValidation.Validated(plan) }

        val acquired = service.acquire(Any(), request(window("test:region/a", 0.0, 80.0, 100.0)))

        assertEquals(ProtectedRouteClaimStatus.ACQUIRED, acquired.status)
        assertEquals(plan.resources, registry.claimSnapshots().single().footprint)
        assertEquals(listOf(listOf(selection)), committed)
    }

    @Test
    fun `zero and multiple turnout admissions preserve compiled preparation order`() {
        val first = turnoutSelection("test:turnout/first", 21L, 22L, 4.0)
        val second = turnoutSelection("test:turnout/second", 23L, 24L, 9.0)
        validResources += first.resource
        validResources += second.resource
        val committed = mutableListOf<List<InfrastructurePreparationRequirement>>()
        var plan = compiledAdmission(
            InfrastructureFootprint(listOf(resource("turnout/a"))),
            emptyList(),
            OccupancyQueryGeometry.create(emptyList())
        )
        val service = service(
            preparationActuator = InfrastructurePreparationActuator { requirements ->
                committed += requirements
                accepted(InfrastructurePreparationState.READY)
            }
        ) { ProtectedRouteValidation.Validated(plan) }
        val platform = Any()

        assertEquals(ProtectedRouteClaimStatus.ACQUIRED, service.acquire(platform, request(null)).status)
        service.release(platform, request(null).owner)
        plan = compiledAdmission(
            InfrastructureFootprint(listOf(first.resource, second.resource)),
            listOf(first, second),
            OccupancyQueryGeometry.create(emptyList())
        )
        assertEquals(ProtectedRouteClaimStatus.ACQUIRED, service.acquire(platform, request(null)).status)

        assertEquals(listOf(emptyList(), listOf(first, second)), committed)
    }

    @Test
    fun `turnout apply failure restores a newly acquired claim`() {
        var stalled = false
        val service = service(
            preparationActuator = InfrastructurePreparationActuator { accepted(InfrastructurePreparationState.RESTORED) },
            stallWorld = { _, _ -> stalled = true }
        ) { validated(resource("turnout/a")) }

        val terminal = service.acquire(Any(), request(window("test:region/a", 0.0, 80.0, 100.0))).poll()
            as ProtectedRouteAdmissionProgress.Terminal

        assertEquals(ProtectedRouteClaimStatus.PREPARATION_FAILED, terminal.result.status)
        assertNull(terminal.liveClaim)
        assertTrue(registry.claimSnapshots().isEmpty())
        assertFalse(stalled)
    }

    @Test
    fun `turnout apply failure restores the exact claim replaced before admission`() {
        val platformOwner = Any()
        val oldOwner = InfrastructureOwner("test:route/main", 1L)
        val newOwner = InfrastructureOwner("test:route/main", 2L)
        val oldPlan = validated(resource("turnout/a"))
        val newPlan = validated(resource("turnout/b"))
        var validation: ProtectedRouteValidation = oldPlan
        var preparationState = InfrastructurePreparationState.READY
        val service = service(
            preparationActuator = InfrastructurePreparationActuator { accepted(preparationState) }
        ) { validation }
        assertEquals(
            ProtectedRouteClaimStatus.ACQUIRED,
            service.acquire(platformOwner, request(null, oldOwner)).status
        )
        validation = newPlan
        preparationState = InfrastructurePreparationState.RESTORED

        val result = service.replace(platformOwner, oldOwner, request(null, newOwner))

        assertEquals(ProtectedRouteClaimStatus.PREPARATION_FAILED, result.status)
        val restored = registry.claimSnapshots().single()
        assertEquals(oldOwner, restored.owner)
        assertEquals(oldPlan.admission.resources, restored.footprint)
    }

    @Test
    fun `turnout rollback failure stalls world and retains the acquired claim`() {
        val platformOwner = Any()
        val owner = InfrastructureOwner("test:route/main", 1L)
        val stalls = mutableListOf<String>()
        val service = service(
            preparationActuator = InfrastructurePreparationActuator { accepted(InfrastructurePreparationState.UNSAFE) },
            stallWorld = { detail, _ -> stalls += detail }
        ) { validated(resource("turnout/a")) }

        val result = service.acquire(platformOwner, request(null, owner))

        assertEquals(ProtectedRouteClaimStatus.WORLD_STALLED, result.status)
        assertEquals(listOf(owner), registry.claimSnapshots().map { it.owner })
        assertEquals(1, stalls.size)
    }

    @Test
    fun `replacement rollback failure reserves both old and new footprints`() {
        val platformOwner = Any()
        val oldOwner = InfrastructureOwner("test:route/main", 1L)
        val newOwner = InfrastructureOwner("test:route/main", 2L)
        val oldResource = railResource(0.0, 10.0)
        val newResource = railResource(20.0, 30.0)
        validResources += oldResource
        validResources += newResource
        val oldPlan = validated(oldResource)
        val newPlan = validated(newResource)
        var validation: ProtectedRouteValidation = oldPlan
        var preparationState = InfrastructurePreparationState.READY
        val service = service(
            preparationActuator = InfrastructurePreparationActuator { accepted(preparationState) },
            stallWorld = { _, _ -> }
        ) { validation }
        assertEquals(
            ProtectedRouteClaimStatus.ACQUIRED,
            service.acquire(platformOwner, request(null, oldOwner)).status
        )
        validation = newPlan
        preparationState = InfrastructurePreparationState.UNSAFE

        assertEquals(
            ProtectedRouteClaimStatus.WORLD_STALLED,
            service.replace(platformOwner, oldOwner, request(null, newOwner)).status
        )

        val oldConflict = registry.acquireClaim(
            Any(),
            InfrastructureClaimRequest(
                topologyRevision,
                InfrastructureOwner("test:other-old", 1L),
                railFootprint(5.0, 6.0)
            )
        )
        val newConflict = registry.acquireClaim(
            Any(),
            InfrastructureClaimRequest(
                topologyRevision,
                InfrastructureOwner("test:other-new", 1L),
                railFootprint(25.0, 26.0)
            )
        )
        assertEquals(InfrastructureMutationStatus.RESOURCE_CONFLICT, oldConflict.status)
        assertEquals(InfrastructureMutationStatus.RESOURCE_CONFLICT, newConflict.status)
        assertEquals(listOf(newOwner), registry.claimSnapshots().map { it.owner })
    }

    @Test
    fun `identical retry is unchanged and does not reapply turnouts`() {
        var commits = 0
        val service = service(
            preparationActuator = InfrastructurePreparationActuator {
                commits++
                accepted(InfrastructurePreparationState.READY)
            }
        ) { validated(resource("turnout/a")) }
        val platformOwner = Any()
        val request = request(null)

        assertEquals(ProtectedRouteClaimStatus.ACQUIRED, service.acquire(platformOwner, request).status)
        assertEquals(ProtectedRouteClaimStatus.UNCHANGED, service.acquire(platformOwner, request).status)
        assertEquals(1, commits)
        assertEquals(1, registry.claimSnapshots().size)
    }

    @Test
    fun `pending actuation retains claim until confirmed and terminal polling is idempotent`() {
        val ticket = FakePreparationTicket(state = InfrastructurePreparationState.PREPARING)
        val platformOwner = Any()
        val owner = InfrastructureOwner("test:route/pending", 1L)
        val start = service(
            preparationActuator = InfrastructurePreparationActuator { InfrastructurePreparationStart.Accepted(ticket) }
        ) { validated(resource("turnout/a")) }.acquire(platformOwner, request(null, owner))

        assertTrue(start is ProtectedRouteAdmissionStart.Pending)
        assertEquals(ProtectedRouteAdmissionProgress.Pending, start.poll())
        assertEquals(ProtectedRouteAdmissionProgress.Pending, start.poll())
        assertEquals(listOf(owner), registry.claimSnapshots().map { it.owner })
        assertEquals(
            ProtectedRouteClaimStatus.OWNER_REVISION_CONFLICT,
            service { validated(resource("turnout/a")) }.release(platformOwner, owner).status
        )

        ticket.state = InfrastructurePreparationState.READY
        val firstTerminal = start.poll() as ProtectedRouteAdmissionProgress.Terminal
        val secondTerminal = start.poll() as ProtectedRouteAdmissionProgress.Terminal
        start.cancel()

        assertEquals(ProtectedRouteClaimStatus.ACQUIRED, firstTerminal.result.status)
        assertSame(firstTerminal.result, secondTerminal.result)
        assertEquals(0, ticket.cancelCalls)
        assertEquals(ProtectedRouteClaimStatus.RELEASED, service { validated() }.release(platformOwner, owner).status)
    }

    @Test
    fun `cancel delegates once and restoring remains pending until exact acquire rollback`() {
        val ticket = FakePreparationTicket(state = InfrastructurePreparationState.PREPARING)
        val start = service(
            preparationActuator = InfrastructurePreparationActuator { InfrastructurePreparationStart.Accepted(ticket) }
        ) { validated(resource("turnout/a")) }.acquire(Any(), request(null))

        start.cancel()
        start.cancel()

        assertEquals(1, ticket.cancelCalls)
        assertEquals(InfrastructurePreparationState.RESTORING, ticket.state)
        assertEquals(ProtectedRouteAdmissionProgress.Pending, start.poll())
        assertEquals(1, registry.claimSnapshots().size)

        ticket.state = InfrastructurePreparationState.RESTORED
        val terminal = start.poll() as ProtectedRouteAdmissionProgress.Terminal

        assertEquals(ProtectedRouteClaimStatus.PREPARATION_FAILED, terminal.result.status)
        assertTrue(registry.claimSnapshots().isEmpty())
        assertSame(terminal.result, (start.poll() as ProtectedRouteAdmissionProgress.Terminal).result)
    }

    @Test
    fun `cancel that loses race to confirmation finalizes normally`() {
        val ticket = FakePreparationTicket(
            state = InfrastructurePreparationState.PREPARING,
            cancelTransition = InfrastructurePreparationState.READY
        )
        val start = service(
            preparationActuator = InfrastructurePreparationActuator { InfrastructurePreparationStart.Accepted(ticket) }
        ) { validated(resource("turnout/a")) }.acquire(Any(), request(null))

        start.cancel()
        val terminal = start.poll() as ProtectedRouteAdmissionProgress.Terminal

        assertEquals(1, ticket.cancelCalls)
        assertEquals(ProtectedRouteClaimStatus.ACQUIRED, terminal.result.status)
        assertEquals(1, registry.claimSnapshots().size)
    }

    @Test
    fun `pending restored replacement recovers exact previous owner and footprint`() {
        val platformOwner = Any()
        val oldOwner = InfrastructureOwner("test:route/main", 1L)
        val newOwner = InfrastructureOwner("test:route/main", 2L)
        val oldPlan = validated(resource("turnout/a"))
        val newPlan = validated(resource("turnout/b"))
        var validation: ProtectedRouteValidation = oldPlan
        var ticket = FakePreparationTicket(state = InfrastructurePreparationState.READY)
        val service = service(
            preparationActuator = InfrastructurePreparationActuator { InfrastructurePreparationStart.Accepted(ticket) }
        ) { validation }
        assertEquals(
            ProtectedRouteClaimStatus.ACQUIRED,
            service.acquire(platformOwner, request(null, oldOwner)).status
        )
        validation = newPlan
        ticket = FakePreparationTicket(state = InfrastructurePreparationState.PREPARING)

        val replacement = service.replace(platformOwner, oldOwner, request(null, newOwner))
        assertTrue(replacement is ProtectedRouteAdmissionStart.Pending)
        assertEquals(
            InfrastructureMutationStatus.RESOURCE_CONFLICT,
            registry.acquireClaim(
                Any(),
                InfrastructureClaimRequest(
                    topologyRevision,
                    InfrastructureOwner("test:other-old", 1L),
                    oldPlan.admission.resources
                )
            ).status
        )
        assertEquals(
            InfrastructureMutationStatus.RESOURCE_CONFLICT,
            registry.acquireClaim(
                Any(),
                InfrastructureClaimRequest(
                    topologyRevision,
                    InfrastructureOwner("test:other-new", 1L),
                    newPlan.admission.resources
                )
            ).status
        )
        ticket.state = InfrastructurePreparationState.RESTORING
        assertEquals(ProtectedRouteAdmissionProgress.Pending, replacement.poll())
        ticket.state = InfrastructurePreparationState.RESTORED
        assertEquals(
            ProtectedRouteClaimStatus.PREPARATION_FAILED,
            (replacement.poll() as ProtectedRouteAdmissionProgress.Terminal).result.status
        )

        val restored = registry.claimSnapshots().single()
        assertEquals(oldOwner, restored.owner)
        assertEquals(oldPlan.admission.resources, restored.footprint)
    }

    @Test
    fun `unsupported preparation rejects before writes and rolls back claim without stalling`() {
        val turnout = turnoutSelection("test:turnout/prepared", 31L, 32L, 4.0)
        val unsupportedResource = resource("device/unsupported")
        val unsupported = object : InfrastructurePreparationRequirement {
            override val resource = unsupportedResource
        }
        validResources += turnout.resource
        validResources += unsupportedResource
        val plan = compiledAdmission(
            InfrastructureFootprint(listOf(turnout.resource, unsupportedResource)),
            listOf(turnout, unsupported),
            OccupancyQueryGeometry.create(emptyList())
        )
        var writes = 0
        var stalled = false
        val start = service(
            preparationActuator = InfrastructurePreparationActuator { requirements ->
                if (requirements.any { it !is ResolvedTurnoutSelection }) {
                    InfrastructurePreparationStart.Rejected(
                        IllegalArgumentException("unsupported preparation")
                    )
                } else {
                    writes++
                    accepted(InfrastructurePreparationState.READY)
                }
            },
            stallWorld = { _, _ -> stalled = true }
        ) { ProtectedRouteValidation.Validated(plan) }.acquire(Any(), request(null))

        assertEquals(ProtectedRouteClaimStatus.PREPARATION_FAILED, start.status)
        assertEquals(0, writes)
        assertTrue(registry.claimSnapshots().isEmpty())
        assertFalse(stalled)
    }

    @Test
    fun `ticket identity change stalls and retains claim`() {
        val ticket = FakePreparationTicket(state = InfrastructurePreparationState.PREPARING)
        var stalls = 0
        val start = service(
            preparationActuator = InfrastructurePreparationActuator { InfrastructurePreparationStart.Accepted(ticket) },
            stallWorld = { _, _ -> stalls++ }
        ) { validated(resource("turnout/a")) }.acquire(Any(), request(null))
        ticket.snapshotTicketId = "changed-ticket"

        val terminal = start.poll() as ProtectedRouteAdmissionProgress.Terminal

        assertEquals(ProtectedRouteClaimStatus.WORLD_STALLED, terminal.result.status)
        assertEquals(1, stalls)
        assertEquals(1, registry.claimSnapshots().size)
        assertSame(terminal.result, (start.poll() as ProtectedRouteAdmissionProgress.Terminal).result)
        assertEquals(1, stalls)
    }

    @Test
    fun `ticket snapshot exception stalls and retains claim`() {
        val ticket = object : InfrastructurePreparationTicket {
            override val ticketId = "test-ticket"
            override fun snapshot(): InfrastructurePreparationSnapshot = error("snapshot failed")
            override fun cancel() = Unit
        }
        var stalls = 0

        val start = service(
            preparationActuator = InfrastructurePreparationActuator { InfrastructurePreparationStart.Accepted(ticket) },
            stallWorld = { _, _ -> stalls++ }
        ) { validated(resource("turnout/a")) }.acquire(Any(), request(null))

        assertEquals(ProtectedRouteClaimStatus.WORLD_STALLED, start.status)
        assertEquals(1, stalls)
        assertEquals(1, registry.claimSnapshots().size)
    }

    @Test
    fun `ticket cancellation exception stalls once and retains claim`() {
        val ticket = object : InfrastructurePreparationTicket {
            override val ticketId = "test-ticket"
            override fun snapshot() = InfrastructurePreparationSnapshot(ticketId, InfrastructurePreparationState.PREPARING)
            override fun cancel(): Unit = error("cancel failed")
        }
        var stalls = 0
        val start = service(
            preparationActuator = InfrastructurePreparationActuator { InfrastructurePreparationStart.Accepted(ticket) },
            stallWorld = { _, _ -> stalls++ }
        ) { validated(resource("turnout/a")) }.acquire(Any(), request(null))

        start.cancel()
        start.cancel()
        val first = start.poll() as ProtectedRouteAdmissionProgress.Terminal
        val second = start.poll() as ProtectedRouteAdmissionProgress.Terminal

        assertEquals(ProtectedRouteClaimStatus.WORLD_STALLED, first.result.status)
        assertSame(first.result, second.result)
        assertEquals(1, stalls)
        assertEquals(1, registry.claimSnapshots().size)
    }

    @Test
    fun `accepted blank ticket identity stalls before polling and retains claim`() {
        val ticket = object : InfrastructurePreparationTicket {
            override val ticketId = ""
            override fun snapshot() = InfrastructurePreparationSnapshot("unreachable", InfrastructurePreparationState.PREPARING)
            override fun cancel() = Unit
        }
        var stalls = 0

        val start = service(
            preparationActuator = InfrastructurePreparationActuator { InfrastructurePreparationStart.Accepted(ticket) },
            stallWorld = { _, _ -> stalls++ }
        ) { validated(resource("turnout/a")) }.acquire(Any(), request(null))

        assertEquals(ProtectedRouteClaimStatus.WORLD_STALLED, start.status)
        assertEquals(1, stalls)
        assertEquals(1, registry.claimSnapshots().size)
    }

    @Test
    fun `actuator start exception stalls and retains claim`() {
        var stalls = 0

        val start = service(
            preparationActuator = InfrastructurePreparationActuator { error("start failed") },
            stallWorld = { _, _ -> stalls++ }
        ) { validated(resource("turnout/a")) }.acquire(Any(), request(null))

        assertEquals(ProtectedRouteClaimStatus.WORLD_STALLED, start.status)
        assertEquals(1, stalls)
        assertEquals(1, registry.claimSnapshots().size)
    }

    @Test
    fun `ticket state regression stalls and retains claim`() {
        val ticket = FakePreparationTicket(state = InfrastructurePreparationState.PREPARING)
        var stalls = 0
        val start = service(
            preparationActuator = InfrastructurePreparationActuator { InfrastructurePreparationStart.Accepted(ticket) },
            stallWorld = { _, _ -> stalls++ }
        ) { validated(resource("turnout/a")) }.acquire(Any(), request(null))
        ticket.state = InfrastructurePreparationState.RESTORING
        assertEquals(ProtectedRouteAdmissionProgress.Pending, start.poll())
        ticket.state = InfrastructurePreparationState.PREPARING

        val terminal = start.poll() as ProtectedRouteAdmissionProgress.Terminal

        assertEquals(ProtectedRouteClaimStatus.WORLD_STALLED, terminal.result.status)
        assertEquals(1, stalls)
        assertEquals(1, registry.claimSnapshots().size)
    }

    @Test
    fun `infrastructure preparation snapshot enforces stable identity and unsafe detail contract`() {
        assertThrows(IllegalArgumentException::class.java) {
            InfrastructurePreparationSnapshot("", InfrastructurePreparationState.PREPARING)
        }
        assertThrows(IllegalArgumentException::class.java) {
            InfrastructurePreparationSnapshot("ticket", InfrastructurePreparationState.UNSAFE)
        }
        assertEquals(
            InfrastructurePreparationState.RESTORING,
            InfrastructurePreparationSnapshot("ticket", InfrastructurePreparationState.RESTORING).state
        )
    }

    private fun service(
        latestObservation: () -> RailwayObservation? = { observation },
        preparationActuator: InfrastructurePreparationActuator = InfrastructurePreparationActuator {
            accepted(InfrastructurePreparationState.READY)
        },
        stallWorld: (String, Exception) -> Unit = { _, _ -> error("unexpected world stall") },
        validate: (ProtectedRouteRequest) -> ProtectedRouteValidation
    ) =
        ProtectedRouteClaimService(
            registry,
            currentTopologyRevision = { topologyRevision },
            latestObservation = latestObservation,
            validate = validate,
            preparationActuator = preparationActuator,
            stallWorld = stallWorld
        )

    private val ProtectedRouteAdmissionStart.result: work.art1st.javarailways.basic.api.route.ProtectedRouteClaimResult
        get() = (poll() as ProtectedRouteAdmissionProgress.Terminal).result

    private val ProtectedRouteAdmissionStart.status: ProtectedRouteClaimStatus
        get() = result.status

    private val ProtectedRouteAdmissionStart.successful: Boolean
        get() = result.successful

    private val ProtectedRouteAdmissionStart.occupyingTrainIds: List<String>
        get() = result.occupyingTrainIds

    private val ProtectedRouteAdmissionStart.blockers
        get() = result.blockers

    private fun accepted(state: InfrastructurePreparationState): InfrastructurePreparationStart.Accepted =
        InfrastructurePreparationStart.Accepted(FakePreparationTicket(state = state))

    private class FakePreparationTicket(
        override val ticketId: String = UUID.randomUUID().toString(),
        var state: InfrastructurePreparationState,
        var snapshotTicketId: String = ticketId,
        private val cancelTransition: InfrastructurePreparationState = InfrastructurePreparationState.RESTORING
    ) : InfrastructurePreparationTicket {
        var cancelCalls = 0

        override fun snapshot() = InfrastructurePreparationSnapshot(
            snapshotTicketId,
            state,
            if (state == InfrastructurePreparationState.UNSAFE) "injected unsafe infrastructure preparation state" else ""
        )

        override fun cancel() {
            if (state in setOf(InfrastructurePreparationState.PREPARING, InfrastructurePreparationState.RESTORING)) {
                cancelCalls++
                state = cancelTransition
            }
        }
    }

    private fun request(
        window: ProtectedRouteWindow?,
        owner: InfrastructureOwner = InfrastructureOwner("test:route/main", 1L),
        stamp: RailwayStateStamp = observation.stamp,
        routeStartMeters: Double = observation.trains
            .firstOrNull { it.trainId == "train" }
            ?.tail
            ?.arcLengthInAbMeters
            ?: 0.0
    ) = ProtectedRouteRequest(
        requestId = "test:request/main",
        owner = owner,
        trainId = "train",
        observationStamp = stamp,
        route = RouteSnapshot(
            "route",
            2L,
            4L,
            listOf(RouteSegmentSnapshot(railId, ViewDirection.AB, routeStartMeters, 100.0))
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

    private fun railResource(startMeters: Double, endMeters: Double) =
        RailIntervalResource(railId, startMeters, endMeters)

    private fun railFootprint(startMeters: Double, endMeters: Double) =
        InfrastructureFootprint(listOf(railResource(startMeters, endMeters)))

    private fun turnoutSelection(
        id: String,
        fromRail: Long,
        toRail: Long,
        distance: Double
    ) = ResolvedTurnoutSelection(
        StableInfrastructureResource(TurnoutResourceIdentity.TYPE_ID, id),
        RailEndpointRef(UUID(0L, fromRail), RailEndpoint.B),
        RailEndpointRef(UUID(0L, toRail), RailEndpoint.A),
        distance
    )

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

    private fun compiledAdmission(
        footprint: InfrastructureFootprint,
        preparations: Collection<InfrastructurePreparationRequirement>,
        occupancyGeometry: OccupancyQueryGeometry,
        routeProgressPath: CompiledRouteProgressPath = testRouteProgressPath(
            railId,
            100.0,
            observation.trains.firstOrNull { it.trainId == "train" }
                ?.tail
                ?.arcLengthInAbMeters
                ?: 0.0
        )
    ): CompiledRouteAdmission {
        return CompiledRouteAdmission.create(
            footprint,
            preparations,
            occupancyGeometry,
            footprint.resources.map { resource ->
                ResolvedTailReleaseEntry(resource, 0.0)
            },
            routeProgressPath
        )
    }

    private fun validated(vararg resources: InfrastructureResource) =
        ProtectedRouteValidation.Validated(
            compiledAdmission(
                InfrastructureFootprint(resources.toList()),
                emptyList(),
                OccupancyQueryGeometry.create(emptyList())
            )
        )
}
