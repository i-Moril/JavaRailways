package work.art1st.javarailways.basic.infrastructure

import work.art1st.javarailways.basic.api.route.ResourcePreparationSnapshot as InfrastructurePreparationSnapshot
import work.art1st.javarailways.basic.api.route.ResourcePreparationState as InfrastructurePreparationState
import work.art1st.javarailways.basic.api.route.ResourcePreparationTicket as InfrastructurePreparationTicket

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
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
import work.art1st.javarailways.core.physics.rail.topology.TestTopologyRail
import work.art1st.javarailways.core.physics.rail.topology.buildTestTopology
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
    fun `pending extension keeps prefix release active then appends ready delta to the same grant`() {
        val platform = Any()
        val owner = InfrastructureOwner("test:route/rolling", 1L)
        val initialRequest = request(window("test:region/a", 0.0, 5.0, 10.0), owner)
        val topology = buildTestTopology(
            topologyRevision,
            listOf(TestTopologyRail(railId, 100.0)),
            emptyMap()
        )
        val projection = (TopologyRouteProjector.project(initialRequest.route, topology)
            as TopologyRouteProjectionResult.Projected).route
        val initialResource = resource("turnout/a")
        val deltaResource = resource("turnout/b")
        val initialPlan = compiledAdmission(
            InfrastructureFootprint(listOf(initialResource)),
            emptyList(),
            OccupancyQueryGeometry.create(emptyList()),
            tailClearRouteMeters = 10.0
        )
        val deltaPlan = compiledAdmission(
            InfrastructureFootprint(listOf(deltaResource)),
            emptyList(),
            OccupancyQueryGeometry.create(emptyList()),
            tailClearRouteMeters = 20.0
        )
        val progressPath = CompiledRouteProgressPath.compile(projection)
        val initialContext = ProtectedRouteExtensionContext(
            projection,
            10.0,
            setOf(initialResource),
            progressPath,
            0
        )
        val nextContext = ProtectedRouteExtensionContext(
            projection,
            20.0,
            setOf(initialResource, deltaResource),
            progressPath,
            0
        )
        val ticket = FakePreparationTicket(InfrastructurePreparationState.PREPARING)
        var preparationStarts = 0
        val service = service(
            preparationActuator = InfrastructurePreparationActuator {
                preparationStarts++
                if (preparationStarts == 1) accepted(InfrastructurePreparationState.READY)
                else InfrastructurePreparationStart.Accepted(ticket)
            },
            compileExtension = { _, _ -> ProtectedRouteExtensionValidation.Validated(deltaPlan, nextContext) }
        ) { ProtectedRouteValidation.Validated(initialPlan, initialContext) }

        assertEquals(ProtectedRouteClaimStatus.ACQUIRED, service.acquire(platform, initialRequest).status)
        val extension = service.extend(
            platform,
            extensionRequest(initialRequest, window("test:region/a", 0.0, 5.0, 20.0))
        ) as ProtectedRouteAdmissionStart.Pending

        observation = observation(train("train", 12.0, 13.0, 8L), epoch = 8L)
        assertInstanceOf(
            ProtectedRouteTailReleaseUpdate.Completed::class.java,
            service.consumeObservation(observation).single().update
        )
        assertEquals(listOf(deltaResource), registry.claimSnapshots().single().footprint.resources)

        ticket.state = InfrastructurePreparationState.READY
        val terminal = extension.handle.poll() as ProtectedRouteAdmissionProgress.Terminal
        assertEquals(ProtectedRouteClaimStatus.ACQUIRED, terminal.result.status)
        assertEquals(listOf(deltaResource), registry.claimSnapshots().single().footprint.resources)

        observation = observation(train("train", 21.0, 22.0, 9L), epoch = 9L)
        assertInstanceOf(
            ProtectedRouteTailReleaseUpdate.Completed::class.java,
            service.consumeObservation(observation).single().update
        )
        assertTrue(registry.claimSnapshots().isEmpty())
    }

    @Test
    fun `extension rejects an observation from a different topology revision`() {
        val platform = Any()
        val owner = InfrastructureOwner("test:route/topology-coherence", 1L)
        val initialRequest = request(window("test:region/a", 0.0, 5.0, 10.0), owner)
        val topology = buildTestTopology(
            topologyRevision,
            listOf(TestTopologyRail(railId, 100.0)),
            emptyMap()
        )
        val projection = (TopologyRouteProjector.project(initialRequest.route, topology)
            as TopologyRouteProjectionResult.Projected).route
        val initialResource = resource("turnout/a")
        val initialContext = ProtectedRouteExtensionContext(
            projection,
            10.0,
            setOf(initialResource),
            CompiledRouteProgressPath.compile(projection),
            0
        )
        var extensionCompiled = false
        val service = service(
            compileExtension = { _, _ ->
                extensionCompiled = true
                error("extension compiler must not receive an incoherent observation")
            }
        ) {
            ProtectedRouteValidation.Validated(
                compiledAdmission(
                    InfrastructureFootprint(listOf(initialResource)),
                    emptyList(),
                    OccupancyQueryGeometry.create(emptyList()),
                    tailClearRouteMeters = 10.0
                ),
                initialContext
            )
        }
        assertEquals(ProtectedRouteClaimStatus.ACQUIRED, service.acquire(platform, initialRequest).status)

        observation = observation(epoch = 8L, observationTopologyRevision = topologyRevision - 1L)
        val result = service.extend(
            platform,
            extensionRequest(
                initialRequest,
                window("test:region/a", 0.0, 5.0, 20.0)
            ).copy(observationStamp = observation.stamp)
        ).result

        assertEquals(ProtectedRouteClaimStatus.STALE_TOPOLOGY, result.status)
        assertFalse(extensionCompiled)
        assertEquals(listOf(initialResource), registry.claimSnapshots().single().footprint.resources)
    }

    @Test
    fun `route without controlled resources succeeds without creating a claim`() {
        val service = service { ProtectedRouteValidation.NotRequired }

        val result = service.acquire(Any(), request(null))

        assertEquals(ProtectedRouteClaimStatus.NOT_REQUIRED, result.status)
        assertTrue(result.successful)
        assertTrue(registry.claimSnapshots().isEmpty())
    }

    @Test
    fun `terminal start polling and abort are idempotent`() {
        val start = service { ProtectedRouteValidation.NotRequired }.acquire(Any(), request(null))
        val first = start.poll() as ProtectedRouteAdmissionProgress.Terminal

        start.abort()
        start.abort()
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
    fun `immediate successful admission retains its live grant after resources release`() {
        val service = service { validated(resource("turnout/a")) }
        val start = service.acquire(Any(), request(null))

        val terminal = start.poll() as ProtectedRouteAdmissionProgress.Terminal
        val releases = service.consumeObservation(observation(epoch = observation.stamp.physicsEpoch + 1L))

        assertEquals(ProtectedRouteClaimStatus.ACQUIRED, terminal.result.status)
        assertInstanceOf(ProtectedRouteTailReleaseUpdate.Completed::class.java, releases.single().update)
        assertEquals(terminal.result, (start.poll() as ProtectedRouteAdmissionProgress.Terminal).result)
        assertTrue(registry.claimSnapshots().isEmpty())
        val later = service.consumeObservation(observation(epoch = observation.stamp.physicsEpoch + 2L))
        assertEquals(1, later.size)
        assertInstanceOf(ProtectedRouteTailReleaseUpdate.Completed::class.java, later.single().update)
    }

    @Test
    fun `pending successful admission installs tail release only after ready`() {
        val ticket = FakePreparationTicket(state = InfrastructurePreparationState.PREPARING)
        val service = service(
            preparationActuator = InfrastructurePreparationActuator {
                InfrastructurePreparationStart.Accepted(ticket)
            }
        ) { validated(resource("turnout/a")) }
        val start = service.acquire(Any(), request(null))

        assertTrue(start is ProtectedRouteAdmissionStart.Pending)
        assertEquals(ProtectedRouteAdmissionProgress.Pending, start.poll())
        assertTrue(service.consumeObservation(observation(epoch = observation.stamp.physicsEpoch + 1L)).isEmpty())
        ticket.state = InfrastructurePreparationState.READY
        val terminal = start.poll() as ProtectedRouteAdmissionProgress.Terminal

        assertEquals(ProtectedRouteClaimStatus.ACQUIRED, terminal.result.status)
        assertEquals(terminal.result, (start.poll() as ProtectedRouteAdmissionProgress.Terminal).result)
        assertEquals(1, service.consumeObservation(observation(epoch = observation.stamp.physicsEpoch + 1L)).size)
    }

    @Test
    fun `unchanged admission retains existing controller while other failures install nothing`() {
        val platformOwner = Any()
        val owner = InfrastructureOwner("test:route/live-owner", 1L)
        val admittedService = service { validated(resource("turnout/a")) }
        val admitted = admittedService.acquire(platformOwner, request(null, owner))
        assertEquals(ProtectedRouteClaimStatus.ACQUIRED, admitted.status)

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
        assertEquals(ProtectedRouteClaimStatus.NOT_REQUIRED, notRequired.result.status)
        assertEquals(ProtectedRouteClaimStatus.PROTECTION_REQUIRED, rejected.result.status)
        val retained = admittedService.consumeObservation(
            observation(epoch = observation.stamp.physicsEpoch + 1L)
        ).single()
        assertEquals(owner, retained.owner)
    }

    @Test
    fun `acquire cannot replace an existing protected route owner`() {
        val platformOwner = Any()
        val currentOwner = InfrastructureOwner("test:route/frozen", 1L)
        val nextOwner = currentOwner.copy(revision = 2L)
        var validation = validated(resource("turnout/a"))
        val service = service { validation }
        assertEquals(
            ProtectedRouteClaimStatus.ACQUIRED,
            service.acquire(platformOwner, request(null, currentOwner)).status
        )
        validation = validated(resource("turnout/b"))

        val rejected = service.acquire(platformOwner, request(null, nextOwner))

        assertEquals(ProtectedRouteClaimStatus.OWNER_REVISION_CONFLICT, rejected.status)
        val retained = registry.claimSnapshots().single()
        assertEquals(currentOwner, retained.owner)
        assertEquals(setOf(resource("turnout/a")), retained.footprint.resources.toSet())
        val dispatch = service.consumeObservation(
            observation(epoch = observation.stamp.physicsEpoch + 1L)
        ).single()
        assertEquals(currentOwner, dispatch.owner)
    }

    @Test
    fun `explicit release removes only the matching service-owned controller`() {
        val platformOwner = Any()
        val owner = InfrastructureOwner("test:route/release", 1L)
        val service = service { validated(resource("turnout/a")) }
        assertEquals(ProtectedRouteClaimStatus.ACQUIRED, service.acquire(platformOwner, request(null, owner)).status)

        assertEquals(
            ProtectedRouteClaimStatus.OWNER_REVISION_CONFLICT,
            service.release(platformOwner, owner.copy(revision = 2L)).status
        )
        assertEquals(owner, service.consumeObservation(observation).single().owner)
        assertEquals(ProtectedRouteClaimStatus.RELEASED, service.release(platformOwner, owner).status)

        assertTrue(service.consumeObservation(observation(epoch = observation.stamp.physicsEpoch + 1L)).isEmpty())
        assertTrue(registry.claimSnapshots().isEmpty())
    }

    @Test
    fun `exact explicit end removes an idle live grant after automatic tail release`() {
        val platformOwner = Any()
        val owner = InfrastructureOwner("test:route/idle-end", 1L)
        val service = service { validated(resource("turnout/a")) }
        assertEquals(
            ProtectedRouteClaimStatus.ACQUIRED,
            service.acquire(platformOwner, request(null, owner)).status
        )
        assertInstanceOf(
            ProtectedRouteTailReleaseUpdate.Completed::class.java,
            service.consumeObservation(observation(epoch = observation.stamp.physicsEpoch + 1L)).single().update
        )
        assertTrue(registry.claimSnapshots().isEmpty())

        assertEquals(ProtectedRouteClaimStatus.RELEASED, service.release(platformOwner, owner).status)
        assertTrue(service.consumeObservation(observation(epoch = observation.stamp.physicsEpoch + 2L)).isEmpty())
    }

    @Test
    fun `wrong platform or revision cannot end an idle live grant`() {
        val platformOwner = Any()
        val owner = InfrastructureOwner("test:route/idle-owned", 1L)
        val service = service { validated(resource("turnout/a")) }
        service.acquire(platformOwner, request(null, owner))
        service.consumeObservation(observation(epoch = observation.stamp.physicsEpoch + 1L))
        assertTrue(registry.claimSnapshots().isEmpty())

        assertEquals(
            ProtectedRouteClaimStatus.OWNER_REVISION_CONFLICT,
            service.release(Any(), owner).status
        )
        assertEquals(
            ProtectedRouteClaimStatus.OWNER_REVISION_CONFLICT,
            service.release(platformOwner, owner.copy(revision = 2L)).status
        )
        val retained = service.consumeObservation(observation(epoch = observation.stamp.physicsEpoch + 2L))
        assertEquals(owner, retained.single().owner)
        assertInstanceOf(ProtectedRouteTailReleaseUpdate.Completed::class.java, retained.single().update)
    }

    @Test
    fun `missing registry claim is not masked while live grant resources are active`() {
        val platformOwner = Any()
        val owner = InfrastructureOwner("test:route/active-missing", 1L)
        val held = resource("turnout/a")
        val service = service { request -> validatedRequest(request, held, 50.0) }
        val request = request(null, owner)
        assertEquals(ProtectedRouteClaimStatus.ACQUIRED, service.acquire(platformOwner, request).status)
        assertEquals(
            InfrastructureMutationStatus.RELEASED,
            registry.releaseClaim(platformOwner, owner).status
        )

        assertEquals(ProtectedRouteClaimStatus.NOT_FOUND, service.release(platformOwner, owner).status)
        assertEquals(ProtectedRouteClaimStatus.UNCHANGED, service.acquire(platformOwner, request).status)
    }

    @Test
    fun `observation dispatch resolves trains once and drives controllers in stable owner order`() {
        val ownerB = InfrastructureOwner("test:route/b", 1L)
        val ownerA = InfrastructureOwner("test:route/a", 1L)
        val resourceA = resource("dispatch/a")
        val resourceB = resource("dispatch/b")
        validResources += resourceA
        validResources += resourceB
        observation = observation(train("train-a", 0.0, 1.0), train("train-b", 0.0, 1.0))
        val service = service { request ->
            validatedRequest(request, if (request.owner == ownerA) resourceA else resourceB, 50.0)
        }

        assertEquals(
            ProtectedRouteClaimStatus.ACQUIRED,
            service.acquire(Any(), request(null, ownerB, trainId = "train-b")).status
        )
        assertEquals(
            ProtectedRouteClaimStatus.ACQUIRED,
            service.acquire(Any(), request(null, ownerA, trainId = "train-a")).status
        )

        val dispatch = service.consumeObservation(
            observation(
                train("train-b", 1.0, 2.0),
                train("train-a", 1.0, 2.0),
                epoch = observation.stamp.physicsEpoch + 1L
            )
        )

        assertEquals(listOf(ownerA, ownerB), dispatch.map { it.owner })
        assertEquals(listOf("train-a", "train-b"), dispatch.map { it.trainId })
        assertTrue(dispatch.all { it.update is ProtectedRouteTailReleaseUpdate.Active })
    }

    @Test
    fun `first fail-closed controller stops later stable-order dispatch`() {
        val ownerA = InfrastructureOwner("test:route/a", 1L)
        val ownerB = InfrastructureOwner("test:route/b", 1L)
        val resourceA = resource("failure/a")
        val resourceB = resource("failure/b")
        validResources += resourceA
        validResources += resourceB
        observation = observation(train("train-a", 0.0, 1.0), train("train-b", 0.0, 1.0))
        val stalls = mutableListOf<String>()
        val service = service(stallWorld = { detail, _ -> stalls += detail }) { request ->
            val resource = if (request.owner == ownerA) resourceA else resourceB
            val threshold = if (request.owner == ownerA) 50.0 else 0.0
            validatedRequest(request, resource, threshold)
        }
        service.acquire(Any(), request(null, ownerB, trainId = "train-b"))
        service.acquire(Any(), request(null, ownerA, trainId = "train-a"))

        val dispatch = service.consumeObservation(
            observation(train("train-b", 1.0, 2.0), epoch = observation.stamp.physicsEpoch + 1L)
        )

        assertEquals(listOf(ownerA), dispatch.map { it.owner })
        assertInstanceOf(ProtectedRouteTailReleaseUpdate.FailedClosed::class.java, dispatch.single().update)
        assertEquals(1, stalls.size)
        assertEquals(setOf(ownerA, ownerB), registry.claimSnapshots().map { it.owner }.toSet())
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
    fun `safely aborted preparation releases a newly acquired claim`() {
        var stalled = false
        val service = service(
            preparationActuator = InfrastructurePreparationActuator {
                accepted(InfrastructurePreparationState.ABORTED_SAFE)
            },
            stallWorld = { _, _ -> stalled = true }
        ) { validated(resource("turnout/a")) }

        val terminal = service.acquire(Any(), request(window("test:region/a", 0.0, 80.0, 100.0))).poll()
            as ProtectedRouteAdmissionProgress.Terminal

        assertEquals(ProtectedRouteClaimStatus.PREPARATION_FAILED, terminal.result.status)
        assertTrue(registry.claimSnapshots().isEmpty())
        assertFalse(stalled)
    }

    @Test
    fun `unsafe infrastructure preparation stalls world and retains the acquired claim`() {
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
        start.abort()

        assertEquals(ProtectedRouteClaimStatus.ACQUIRED, firstTerminal.result.status)
        assertSame(firstTerminal.result, secondTerminal.result)
        assertEquals(0, ticket.abortCalls)
        assertEquals(ProtectedRouteClaimStatus.RELEASED, service { validated() }.release(platformOwner, owner).status)
    }

    @Test
    fun `abort delegates once and remains pending until resource reports safe abort`() {
        val ticket = FakePreparationTicket(
            state = InfrastructurePreparationState.PREPARING,
            abortTransition = InfrastructurePreparationState.PREPARING
        )
        val start = service(
            preparationActuator = InfrastructurePreparationActuator { InfrastructurePreparationStart.Accepted(ticket) }
        ) { validated(resource("turnout/a")) }.acquire(Any(), request(null))

        start.abort()
        start.abort()

        assertEquals(1, ticket.abortCalls)
        assertEquals(InfrastructurePreparationState.PREPARING, ticket.state)
        assertEquals(ProtectedRouteAdmissionProgress.Pending, start.poll())
        assertEquals(1, registry.claimSnapshots().size)

        ticket.state = InfrastructurePreparationState.ABORTED_SAFE
        val terminal = start.poll() as ProtectedRouteAdmissionProgress.Terminal

        assertEquals(ProtectedRouteClaimStatus.PREPARATION_FAILED, terminal.result.status)
        assertTrue(registry.claimSnapshots().isEmpty())
        assertSame(terminal.result, (start.poll() as ProtectedRouteAdmissionProgress.Terminal).result)
    }

    @Test
    fun `abort that loses race to confirmation finalizes normally`() {
        val ticket = FakePreparationTicket(
            state = InfrastructurePreparationState.PREPARING,
            abortTransition = InfrastructurePreparationState.READY
        )
        val start = service(
            preparationActuator = InfrastructurePreparationActuator { InfrastructurePreparationStart.Accepted(ticket) }
        ) { validated(resource("turnout/a")) }.acquire(Any(), request(null))

        start.abort()
        val terminal = start.poll() as ProtectedRouteAdmissionProgress.Terminal

        assertEquals(1, ticket.abortCalls)
        assertEquals(ProtectedRouteClaimStatus.ACQUIRED, terminal.result.status)
        assertEquals(1, registry.claimSnapshots().size)
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
    fun `ticket snapshot exception stalls and retains claim`() {
        val ticket = object : InfrastructurePreparationTicket {
            override fun snapshot(): InfrastructurePreparationSnapshot = error("snapshot failed")
            override fun abort() = Unit
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
    fun `ticket abort exception stalls once and retains claim`() {
        val ticket = object : InfrastructurePreparationTicket {
            override fun snapshot() = InfrastructurePreparationSnapshot(InfrastructurePreparationState.PREPARING)
            override fun abort(): Unit = error("abort failed")
        }
        var stalls = 0
        val start = service(
            preparationActuator = InfrastructurePreparationActuator { InfrastructurePreparationStart.Accepted(ticket) },
            stallWorld = { _, _ -> stalls++ }
        ) { validated(resource("turnout/a")) }.acquire(Any(), request(null))

        start.abort()
        start.abort()
        val first = start.poll() as ProtectedRouteAdmissionProgress.Terminal
        val second = start.poll() as ProtectedRouteAdmissionProgress.Terminal

        assertEquals(ProtectedRouteClaimStatus.WORLD_STALLED, first.result.status)
        assertSame(first.result, second.result)
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
    fun `infrastructure preparation snapshot enforces unsafe detail contract`() {
        assertThrows(IllegalArgumentException::class.java) {
            InfrastructurePreparationSnapshot(InfrastructurePreparationState.UNSAFE)
        }
        assertEquals(
            InfrastructurePreparationState.ABORTED_SAFE,
            InfrastructurePreparationSnapshot(InfrastructurePreparationState.ABORTED_SAFE).state
        )
    }

    private fun service(
        latestObservation: () -> RailwayObservation? = { observation },
        preparationActuator: InfrastructurePreparationActuator = InfrastructurePreparationActuator {
            accepted(InfrastructurePreparationState.READY)
        },
        stallWorld: (String, Exception) -> Unit = { _, _ -> error("unexpected world stall") },
        compileExtension: (ProtectedRouteExtensionContext, ProtectedRouteExtensionRequest) ->
            ProtectedRouteExtensionValidation = { _, _ ->
                ProtectedRouteExtensionValidation.Rejected(
                    ProtectedRouteClaimStatus.INVALID_REQUEST,
                    "extension compiler is not used by this fixture"
                )
            },
        validate: (ProtectedRouteRequest) -> ProtectedRouteValidation
    ) =
        ProtectedRouteClaimService(
            registry,
            currentTopologyRevision = { topologyRevision },
            latestObservation = latestObservation,
            validate = validate,
            compileExtension = compileExtension,
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
        var state: InfrastructurePreparationState,
        private val abortTransition: InfrastructurePreparationState = InfrastructurePreparationState.ABORTED_SAFE
    ) : InfrastructurePreparationTicket {
        var abortCalls = 0

        override fun snapshot() = InfrastructurePreparationSnapshot(
            state,
            if (state == InfrastructurePreparationState.UNSAFE) "injected unsafe infrastructure preparation state" else ""
        )

        override fun abort() {
            if (state == InfrastructurePreparationState.PREPARING) {
                abortCalls++
                state = abortTransition
            }
        }
    }

    private fun request(
        window: ProtectedRouteWindow?,
        owner: InfrastructureOwner = InfrastructureOwner("test:route/main", 1L),
        stamp: RailwayStateStamp = observation.stamp,
        trainId: String = "train",
        routeStartMeters: Double = observation.trains
            .firstOrNull { it.trainId == trainId }
            ?.tail
            ?.arcLengthInAbMeters
            ?: 0.0
    ) = ProtectedRouteRequest(
        requestId = "test:request/main",
        owner = owner,
        trainId = trainId,
        observationStamp = stamp,
        route = RouteSnapshot(
            "route",
            2L,
            4L,
            listOf(RouteSegmentSnapshot(railId, ViewDirection.AB, routeStartMeters, 100.0))
        ),
        window = window
    )

    private fun extensionRequest(
        initial: ProtectedRouteRequest,
        window: ProtectedRouteWindow
    ) = ProtectedRouteExtensionRequest(
        initial.requestId,
        initial.owner,
        initial.trainId,
        initial.observationStamp,
        initial.route,
        window
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
        worldId: String = "world",
        observationTopologyRevision: Long = topologyRevision
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
            RailwayWorldFrameSnapshot.create(epoch, observationTopologyRevision, frames)
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
        sweptSpans = listOf(SweptRailSpanSnapshot(railId, minArcMeters, maxArcMeters)),
        headTraversal = emptyList(),
        tailTraversal = emptyList()
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
        ),
        tailClearRouteMeters: Double = 0.0
    ): CompiledRouteAdmission {
        return CompiledRouteAdmission.create(
            footprint,
            preparations,
            occupancyGeometry,
            footprint.resources.map { resource ->
                ResolvedTailReleaseEntry(resource, tailClearRouteMeters)
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

    private fun validatedRequest(
        request: ProtectedRouteRequest,
        resource: InfrastructureResource,
        tailClearRouteMeters: Double
    ) = ProtectedRouteValidation.Validated(
        compiledAdmission(
            InfrastructureFootprint(listOf(resource)),
            emptyList(),
            OccupancyQueryGeometry.create(emptyList()),
            tailClearRouteMeters = tailClearRouteMeters,
            routeProgressPath = testRouteProgressPath(
                railId,
                100.0,
                request.route.segments.first().startArcLengthMeters
            )
        )
    )
}
