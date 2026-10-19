package work.art1st.javarailways.basic.infrastructure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner
import work.art1st.javarailways.basic.api.infrastructure.RailIntervalResource
import work.art1st.javarailways.basic.api.infrastructure.StableInfrastructureResource
import work.art1st.javarailways.basic.api.observation.RailwayObservation
import work.art1st.javarailways.basic.api.route.ResourcePreparationSnapshot
import work.art1st.javarailways.basic.api.route.ResourcePreparationState
import work.art1st.javarailways.basic.api.route.ResourcePreparationTicket
import work.art1st.javarailways.basic.api.route.RouteAdmissionBlocker
import work.art1st.javarailways.basic.api.route.RouteAdmissionFailureCode
import work.art1st.javarailways.basic.api.route.RouteAdmissionResult
import work.art1st.javarailways.basic.api.route.RouteBoundary
import work.art1st.javarailways.basic.api.route.RouteResourceIntent
import work.art1st.javarailways.core.api.physics.RailwayWorldFrameSnapshot
import work.art1st.javarailways.core.api.physics.rail.RailInterval
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.ActiveRailContinuationsSnapshot
import work.art1st.javarailways.core.api.physics.rail.topology.AtGradeRailCrossing
import work.art1st.javarailways.core.api.physics.rail.topology.RailCrossingPoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyNode
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.core.api.physics.train.SweptRailSpanSnapshot
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot
import java.util.UUID
import work.art1st.javarailways.basic.infrastructure.admission.InfrastructurePreparationActuator
import work.art1st.javarailways.basic.infrastructure.admission.InfrastructurePreparationStart
import work.art1st.javarailways.basic.infrastructure.admission.RouteResourceAdmissionEngine

class RouteResourceConflictIntegrationTest {
    @Test
    fun `crossing routes claim one Basic diamond without creating a continuation or selection`() {
        val firstRail = rail(1)
        val secondRail = rail(2)
        val crossing = AtGradeRailCrossing(
            RailCrossingPoint(firstRail, 5.0),
            RailCrossingPoint(secondRail, 5.0)
        )
        val topology = topologyState(
            revision = 1L,
            railIds = listOf(firstRail, secondRail),
            connections = emptyList(),
            atGradeCrossings = listOf(crossing)
        )
        var observation = observation(
            topology,
            1L,
            train("first", firstRail, ViewDirection.AB, 0.0, 1.0, 1L),
            train("second", secondRail, ViewDirection.AB, 0.0, 1.0, 1L)
        )
        val runtime = runtime(topology) { observation }
        val firstRoute = route(topology, "test:route/first", firstRail)
        val secondRoute = route(topology, "test:route/second", secondRail)

        val first = assertInstanceOf(
            RouteAdmissionResult.Granted::class.java,
            runtime.engine.request(Any(), intent(observation, "test:owner/first", "first", firstRoute, 10.0))
        )
        val diamond = PhysicalTopologyResources.diamondResource(crossing)
        assertTrue(
            runtime.registry.claimSnapshots().single().footprint.resources.contains(diamond),
            "a route on either crossing rail must use Basic's shared diamond identity"
        )

        val illegalCrossLineRoute = RouteSnapshot(
            "test:route/cross-line",
            1L,
            topology.revision,
            listOf(
                RouteSegmentSnapshot(secondRail, ViewDirection.AB, 0.0, 10.0),
                RouteSegmentSnapshot(firstRail, ViewDirection.AB, 0.0, 10.0)
            )
        )
        val invalid = assertInstanceOf(
            RouteAdmissionResult.Rejected::class.java,
            runtime.engine.request(
                Any(),
                intent(observation, "test:owner/cross-line", "second", illegalCrossLineRoute, 20.0)
            )
        )
        assertEquals(RouteAdmissionFailureCode.INVALID_ROUTE, invalid.failure.code)
        assertTrue(topology.nodes.values.none { it is TopologyNode.Turnout })

        val denied = assertInstanceOf(
            RouteAdmissionResult.Rejected::class.java,
            runtime.engine.request(Any(), intent(observation, "test:owner/second", "second", secondRoute, 10.0))
        )
        assertEquals(RouteAdmissionFailureCode.RESOURCE_CONFLICT, denied.failure.code)
        assertEquals(
            setOf(diamond),
            denied.failure.blockers
                .filterIsInstance<RouteAdmissionBlocker.ConflictingResource>()
                .map(RouteAdmissionBlocker.ConflictingResource::resource)
                .toSet()
        )

        observation = observation(
            topology,
            2L,
            train("first", firstRail, ViewDirection.AB, 6.0, 7.0, 2L),
            train("second", secondRail, ViewDirection.AB, 0.0, 1.0, 2L)
        )
        runtime.engine.consumeObservation(observation)
        assertFalse(runtime.registry.claimSnapshots().single().footprint.resources.contains(diamond))

        val second = assertInstanceOf(
            RouteAdmissionResult.Granted::class.java,
            runtime.engine.request(Any(), intent(observation, "test:owner/second", "second", secondRoute, 10.0))
        )
        assertEquals(RouteBoundary(10.0), first.grant.committedAuthority)
        assertEquals(RouteBoundary(10.0), second.grant.committedAuthority)
        assertEquals(2, runtime.registry.claimSnapshots().size)
    }

    @Test
    fun `disjoint selected routes coexist without a synthetic region token and physical overlap conflicts`() {
        val upperEntry = rail(11)
        val upperCore = rail(12)
        val upperExit = rail(13)
        val lowerEntry = rail(14)
        val lowerCore = rail(15)
        val lowerExit = rail(16)
        val connector = rail(17)
        val topology = topologyState(
            revision = 2L,
            railIds = listOf(
                upperEntry, upperCore, upperExit,
                lowerEntry, lowerCore, lowerExit,
                connector
            ),
            connections = listOf(
                endpoint(upperEntry, RailEndpoint.B) to endpoint(upperCore, RailEndpoint.A),
                endpoint(upperCore, RailEndpoint.B) to endpoint(upperExit, RailEndpoint.A),
                endpoint(upperCore, RailEndpoint.B) to endpoint(connector, RailEndpoint.A),
                endpoint(lowerEntry, RailEndpoint.B) to endpoint(lowerCore, RailEndpoint.A),
                endpoint(lowerCore, RailEndpoint.B) to endpoint(lowerExit, RailEndpoint.A),
                endpoint(lowerCore, RailEndpoint.B) to endpoint(connector, RailEndpoint.B)
            )
        )
        var observation = observation(
            topology,
            1L,
            train("upper", upperEntry, ViewDirection.AB, 5.0, 6.0, 1L),
            train("lower", lowerEntry, ViewDirection.AB, 5.0, 6.0, 1L),
            train("conflicting", upperExit, ViewDirection.BA, 9.0, 8.0, 1L)
        )
        val runtime = runtime(topology) { observation }
        val upperRoute = RouteSnapshot(
            "test:region/upper",
            1L,
            topology.revision,
            listOf(
                RouteSegmentSnapshot(upperEntry, ViewDirection.AB, 5.0, 10.0),
                RouteSegmentSnapshot(upperCore, ViewDirection.AB, 0.0, 10.0),
                RouteSegmentSnapshot(upperExit, ViewDirection.AB, 0.0, 5.0)
            )
        )
        val lowerRoute = RouteSnapshot(
            "test:region/lower",
            1L,
            topology.revision,
            listOf(
                RouteSegmentSnapshot(lowerEntry, ViewDirection.AB, 5.0, 10.0),
                RouteSegmentSnapshot(lowerCore, ViewDirection.AB, 0.0, 10.0),
                RouteSegmentSnapshot(lowerExit, ViewDirection.AB, 0.0, 5.0)
            )
        )

        assertInstanceOf(
            RouteAdmissionResult.Granted::class.java,
            runtime.engine.request(
                Any(), intent(observation, "test:owner/upper", "upper", upperRoute, upperRoute.totalLengthMeters)
            )
        )
        assertInstanceOf(
            RouteAdmissionResult.Granted::class.java,
            runtime.engine.request(
                Any(), intent(observation, "test:owner/lower", "lower", lowerRoute, lowerRoute.totalLengthMeters)
            )
        )

        val liveClaims = runtime.registry.claimSnapshots()
        assertEquals(2, liveClaims.size)
        assertTrue(liveClaims[0].footprint.resources.none { first ->
            liveClaims[1].footprint.resources.any(first::overlaps)
        })
        assertTrue(
            liveClaims.flatMap { it.footprint.resources }
                .filterIsInstance<StableInfrastructureResource>()
                .none { it.typeId.startsWith("simplesignals:") },
            "a protection-region identity must not be added as a whole-region claim"
        )

        val overlappingUpperRoute = RouteSnapshot(
            "test:region/upper-reverse",
            1L,
            topology.revision,
            listOf(
                RouteSegmentSnapshot(upperExit, ViewDirection.BA, 1.0, 10.0),
                RouteSegmentSnapshot(upperCore, ViewDirection.BA, 0.0, 9.0)
            )
        )
        val denied = assertInstanceOf(
            RouteAdmissionResult.Rejected::class.java,
            runtime.engine.request(
                Any(),
                intent(
                    observation,
                    "test:owner/overlap",
                    "conflicting",
                    overlappingUpperRoute,
                    overlappingUpperRoute.totalLengthMeters
                )
            )
        )

        assertEquals(RouteAdmissionFailureCode.RESOURCE_CONFLICT, denied.failure.code)
        val conflictingResources = denied.failure.blockers
            .filterIsInstance<RouteAdmissionBlocker.ConflictingResource>()
            .map(RouteAdmissionBlocker.ConflictingResource::resource)
        assertTrue(conflictingResources.any { it is RailIntervalResource })
        assertTrue(conflictingResources.none {
            it is StableInfrastructureResource && it.typeId.startsWith("simplesignals:")
        })
    }

    private fun runtime(
        topology: TopologyState,
        observation: () -> RailwayObservation
    ): RuntimeFixture {
        val physicalBindings = PhysicalTopologyResources.routeBoundBindings(topology)
        val controlledResources = PhysicalTopologyResources.controlledResources(topology, physicalBindings)
        val registry = InfrastructureCoordinationRegistry(
            currentTopologyRevision = { topology.revision },
            resourceExists = { resource ->
                when (resource) {
                    is RailIntervalResource -> resource.railId in topology.railProjection
                    is StableInfrastructureResource -> resource in controlledResources
                }
            }
        )
        val catalog = RouteBoundResourceCatalog { topology }
        assertEquals(RouteBoundResourceCatalogUpdate.Published, catalog.publish(topology, physicalBindings))
        val engine = RouteResourceAdmissionEngine(
            worldId = WORLD_ID,
            registry = registry,
            hostClaimOwner = Any(),
            currentTopology = { topology },
            latestObservation = observation,
            routeBoundResources = catalog,
            validateContribution = { _, _ -> error("these tests do not install plugin resources") },
            preparationActuator = InfrastructurePreparationActuator {
                InfrastructurePreparationStart.Accepted(ReadyTicket)
            },
            stallWorld = { _, failure -> throw failure }
        )
        return RuntimeFixture(registry, engine)
    }

    private fun intent(
        observation: RailwayObservation,
        ownerId: String,
        trainId: String,
        route: RouteSnapshot,
        authority: Double
    ) = RouteResourceIntent(
        requestId = "test:request/$trainId",
        owner = InfrastructureOwner(ownerId, 1L),
        trainId = trainId,
        observationStamp = observation.stamp,
        route = route,
        desiredAuthority = RouteBoundary(authority)
    )

    private fun route(
        topology: TopologyState,
        routeId: String,
        railId: UUID
    ) = RouteSnapshot(
        routeId,
        1L,
        topology.revision,
        listOf(RouteSegmentSnapshot(railId, ViewDirection.AB, 0.0, 10.0))
    )

    private fun observation(
        topology: TopologyState,
        epoch: Long,
        vararg trains: TrainFrameSnapshot
    ) = RailwayObservation.fromCore(
        WORLD_ID,
        RailwayWorldFrameSnapshot.create(
            epoch,
            topology.revision,
            trains.toList(),
            ActiveRailContinuationsSnapshot.empty(topology.revision)
        )
    )

    private fun train(
        id: String,
        railId: UUID,
        facing: ViewDirection,
        tailAbMeters: Double,
        headAbMeters: Double,
        epoch: Long
    ) = TrainFrameSnapshot.create(
        trainId = id,
        trainName = id,
        revision = 1L,
        physicsEpoch = epoch,
        speedMetersPerSecond = 0.0,
        derailed = false,
        reversed = false,
        cars = emptyList(),
        head = RailPositionSnapshot(railId, facing, headAbMeters),
        tail = RailPositionSnapshot(railId, facing, tailAbMeters),
        occupiedIntervals = listOf(
            RailInterval(railId, minOf(tailAbMeters, headAbMeters), maxOf(tailAbMeters, headAbMeters))
        ),
        bodyTraversal = emptyList(),
        sweptSpans = listOf(
            SweptRailSpanSnapshot(
                railId,
                minOf(tailAbMeters, headAbMeters),
                maxOf(tailAbMeters, headAbMeters)
            )
        ),
        headTraversal = emptyList(),
        tailTraversal = emptyList()
    )

    private data class RuntimeFixture(
        val registry: InfrastructureCoordinationRegistry,
        val engine: RouteResourceAdmissionEngine
    )

    private object ReadyTicket : ResourcePreparationTicket {
        override fun snapshot() = ResourcePreparationSnapshot(ResourcePreparationState.READY)

        override fun abort() = Unit
    }

    private fun rail(value: Long) = UUID(0L, value)

    private companion object {
        const val WORLD_ID = "test:world"
    }
}
