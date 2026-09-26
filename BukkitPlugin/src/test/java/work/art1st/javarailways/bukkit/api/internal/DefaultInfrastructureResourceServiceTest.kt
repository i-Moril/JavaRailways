package work.art1st.javarailways.bukkit.api.internal

import org.joml.Vector3d
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.infrastructure.AppliedPlanDependencies
import work.art1st.javarailways.basic.api.infrastructure.AppliedPlanDependencyMutationStatus
import work.art1st.javarailways.basic.api.infrastructure.AppliedPlanDependencyRequest
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureFootprint
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureMutationStatus
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureResource
import work.art1st.javarailways.basic.api.infrastructure.ManualTurnoutSelectionRequest
import work.art1st.javarailways.basic.api.infrastructure.ManualTurnoutSelectionStatus
import work.art1st.javarailways.basic.api.infrastructure.RailIntervalResource
import work.art1st.javarailways.basic.api.infrastructure.StableInfrastructureResource
import work.art1st.javarailways.basic.api.infrastructure.TopologyEditImpact
import work.art1st.javarailways.basic.api.infrastructure.TopologyEditBlockerKind
import work.art1st.javarailways.basic.api.infrastructure.TopologyEditRequest
import work.art1st.javarailways.basic.api.infrastructure.TopologyEditStatus
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.api.route.ContributedRouteResource
import work.art1st.javarailways.basic.api.route.ResourcePreparationDescriptor
import work.art1st.javarailways.basic.api.route.ResourcePreparationSnapshot
import work.art1st.javarailways.basic.api.route.ResourcePreparationState
import work.art1st.javarailways.basic.api.route.ResourcePreparationTicket
import work.art1st.javarailways.basic.api.route.RouteAdmissionFailureCode
import work.art1st.javarailways.basic.api.route.RouteAdmissionResult
import work.art1st.javarailways.basic.api.route.RouteBoundary
import work.art1st.javarailways.basic.api.route.RouteResourceIntent
import work.art1st.javarailways.basic.api.observation.RailwayObservation
import work.art1st.javarailways.bukkit.api.infrastructure.InfrastructureResourceProvider
import work.art1st.javarailways.bukkit.api.infrastructure.InfrastructureResourceProviderRegistration
import work.art1st.javarailways.bukkit.api.infrastructure.InfrastructureResourcePreparationHandler
import work.art1st.javarailways.bukkit.util.BukkitThreadGuardBridge
import work.art1st.javarailways.core.api.physics.RailwayWorldFrameSnapshot
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.physics.rail.Rail
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.RailInterval
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.AtGradeRailCrossing
import work.art1st.javarailways.core.api.physics.rail.topology.RailCrossingPoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.core.api.physics.train.SweptRailSpanSnapshot
import work.art1st.javarailways.core.api.physics.train.Train
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot
import work.art1st.javarailways.core.api.util.plugin.Plugin as CorePlugin
import work.art1st.javarailways.core.api.util.plugin.ExtensionData
import work.art1st.javarailways.core.api.util.tick.TickManager
import work.art1st.javarailways.core.physics.rail.topology.TestTopologyRail
import work.art1st.javarailways.core.physics.rail.topology.buildTestTopology
import work.art1st.javarailways.core.physics.createRailForTest
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.train.createTestWorld
import java.lang.reflect.Proxy
import java.util.UUID

class DefaultInfrastructureResourceServiceTest {
    @Test
    fun `applied dependency denial returns exact diagnostics without installing an edit permit`() {
        val service = DefaultInfrastructureResourceService()
        val worldId = UUID.randomUUID()
        val planPlugin = plugin("signal")
        val editorPlugin = plugin("editor")
        val resource = StableInfrastructureResource("example.signal:signal", "home")
        val planOwner = InfrastructureOwner("example.signal:plan/main", 1L)
        val editorOwner = InfrastructureOwner("example.editor:mutation/main", 1L)
        service.registerResourceProvider(
            planPlugin,
            resource.typeId,
            rejectingRouteProvider { _, id -> id == resource.resourceId },
            emptyList()
        )
        registerWorld(service, worldId)
        assertEquals(
            AppliedPlanDependencyMutationStatus.APPLIED,
            service.setAppliedPlanDependencies(
                planPlugin,
                worldId,
                AppliedPlanDependencyRequest(
                    7L,
                    planOwner,
                    AppliedPlanDependencies(emptySet(), emptySet(), setOf(resource))
                )
            ).status
        )

        val denied = service.acquireEditPermit(
            editorPlugin,
            worldId,
            TopologyEditRequest(
                7L,
                editorOwner,
                TopologyEditImpact(emptySet(), emptySet(), setOf(resource))
            )
        )

        assertEquals(TopologyEditStatus.CONFLICT, denied.decision().status)
        assertTrue(denied.permit().isEmpty)
        val blocker = denied.decision().blockers.single()
        assertEquals(TopologyEditBlockerKind.APPLIED_PLAN_DEPENDENCY, blocker.kind)
        assertEquals(planOwner, blocker.owner)
        assertTrue(blocker.overlap.railIds.isEmpty())
        assertTrue(blocker.overlap.topologyNodeKeys.isEmpty())
        assertEquals(setOf(resource), blocker.overlap.resourceIds)
        assertEquals(listOf(planOwner), service.appliedPlanDependencies(worldId).map { it.owner })

        assertEquals(
            AppliedPlanDependencyMutationStatus.REMOVED,
            service.removeAppliedPlanDependencies(planPlugin, worldId, planOwner).status
        )
        val granted = service.acquireEditPermit(
            editorPlugin,
            worldId,
            TopologyEditRequest(
                7L,
                editorOwner,
                TopologyEditImpact(emptySet(), emptySet(), setOf(resource))
            )
        )
        val permit = granted.permit().orElseThrow()
        assertEquals(editorOwner, permit.owner())
        permit.close()
        permit.close()

        val reacquired = service.acquireEditPermit(
            editorPlugin,
            worldId,
            TopologyEditRequest(
                7L,
                InfrastructureOwner("example.editor:mutation/next", 1L),
                TopologyEditImpact(emptySet(), emptySet(), setOf(resource))
            )
        )
        assertEquals(TopologyEditStatus.GRANTED, reacquired.decision().status)
        reacquired.permit().orElseThrow().close()
        service.close()
    }

    @Test
    fun `public service performs guarded manual turnout selection without retaining a claim`() {
        val service = DefaultInfrastructureResourceService()
        val worldId = UUID.randomUUID()
        val requester = plugin("operator")
        val world = createTestWorld()
        try {
            val stem = world.createRailForTest(straightCurve(-10.0, 0.0, 0.0))
            val first = world.createRailForTest(straightCurve(0.0, 10.0, -5.0))
            val second = world.createRailForTest(straightCurve(0.0, 10.0, 5.0))
            CoreRail.connect(stem, first)
            CoreRail.connect(stem, second)
            service.registerWorld(worldId, world::topologyRevision) { resource ->
                resource is RailIntervalResource && world.getRail(resource.railId) != null ||
                    resource is StableInfrastructureResource && service.stableResourceExists(worldId, resource)
            }
            var physicsWaits = 0
            service.attachInfrastructureRuntime(
                worldId,
                world,
                latestObservation = { null },
                awaitCurrentPhysics = { physicsWaits++ },
                turnoutLockingDistanceMeters = 5.0
            ) { reason, failure ->
                throw IllegalStateException("unexpected stall: $reason", failure)
            }

            val result = service.selectTurnout(
                requester,
                worldId,
                ManualTurnoutSelectionRequest(
                    world.topologyRevision,
                    RailEndpointRef(stem.id, RailEndpoint.B),
                    RailEndpointRef(first.id, RailEndpoint.A)
                )
            )

            assertEquals(ManualTurnoutSelectionStatus.SELECTED, result.status)
            assertSame(first.viewAB, stem.viewAB.nextActiveRail)
            assertSame(stem.viewBA, first.viewBA.nextActiveRail)
            assertEquals(1, physicsWaits)
            assertTrue(service.liveClaims(worldId).isEmpty())
        } finally {
            service.close()
            world.physicsCoordinator.close()
            (world.tickManager as? AutoCloseable)?.close()
        }
    }

    @Test
    fun `Basic-owned diamond is claimable without an external resource provider`() {
        val service = DefaultInfrastructureResourceService()
        val worldId = UUID.randomUUID()
        val firstRail = UUID(0L, 1L)
        val secondRail = UUID(0L, 2L)
        val requester = plugin("ato")
        val topology = buildTestTopology(
            revision = 7L,
            rails = listOf(TestTopologyRail(firstRail, 100.0), TestTopologyRail(secondRail, 100.0)),
            continuations = emptyMap(),
            atGradeCrossings = listOf(
                AtGradeRailCrossing(
                    RailCrossingPoint(firstRail, 10.0),
                    RailCrossingPoint(secondRail, 20.0)
                )
            )
        )
        val observation = routeObservation(worldId, topology.revision, 1L, firstRail, 0.0, 1.0)
        service.registerWorld(worldId, { topology.revision }) { resource ->
            resource is RailIntervalResource && resource.railId in setOf(firstRail, secondRail) ||
                resource is StableInfrastructureResource && service.stableResourceExists(worldId, resource)
        }
        service.attachInfrastructureRuntime(
            worldId, TestWorld(topology), { observation }, {}, 5.0
        ) { reason, failure ->
            throw IllegalStateException("unexpected stall: $reason", failure)
        }

        val result = service.routeAdmission(requester, worldId).request(
            RouteResourceIntent(
                "test.ato:request/diamond",
                InfrastructureOwner("test.ato:route/diamond", 1L),
                "train",
                observation.stamp,
                RouteSnapshot(
                    "test.ato:route/diamond",
                    1L,
                    topology.revision,
                    listOf(RouteSegmentSnapshot(firstRail, ViewDirection.AB, 0.0, 100.0))
                ),
                RouteBoundary(20.0)
            )
        )

        assertInstanceOf(RouteAdmissionResult.Granted::class.java, result)
        assertTrue(service.liveClaims(worldId).single().footprint.resources.any { resource ->
            resource is StableInfrastructureResource && resource.typeId == "javarailways:diamond"
        })
    }

    @Test
    fun `normal unregister rejects active route resources and accepts an empty route state`() {
        val service = DefaultInfrastructureResourceService()
        val worldId = UUID.randomUUID()
        val railId = UUID.randomUUID()
        val requester = plugin("signal")
        val topology = buildTestTopology(7L, listOf(TestTopologyRail(railId, 100.0)), emptyMap())
        var observation = routeObservation(worldId, topology.revision, 1L, railId, 0.0, 1.0)
        service.registerWorld(worldId, { topology.revision }) { resource ->
            resource is RailIntervalResource && resource.railId == railId
        }
        service.attachInfrastructureRuntime(
            worldId, TestWorld(topology), { observation }, {}, 5.0
        ) { reason, failure ->
            throw IllegalStateException("unexpected stall: $reason", failure)
        }
        val admission = service.routeAdmission(requester, worldId)
        val intent = RouteResourceIntent(
            "test.signal:request/main",
            InfrastructureOwner("test.signal:route/main", 1L),
            "train",
            observation.stamp,
            RouteSnapshot(
                "test.signal:route/main",
                1L,
                topology.revision,
                listOf(RouteSegmentSnapshot(railId, ViewDirection.AB, 0.0, 100.0))
            ),
            RouteBoundary(20.0)
        )

        val grant = assertInstanceOf(RouteAdmissionResult.Granted::class.java,
            admission.request(intent)).grant
        assertEquals(1, service.liveClaims(worldId).size)
        val active = assertThrows(IllegalStateException::class.java) {
            service.unregisterOwner(requester)
        }
        assertTrue(active.message.orEmpty().contains("route=${intent.owner.ownerId}"))
        assertEquals(1, service.liveClaims(worldId).size)

        observation = routeObservation(worldId, topology.revision, 2L, railId, 21.0, 22.0)
        service.consumeObservation(worldId, observation)
        assertTrue(service.liveClaims(worldId).isEmpty())
        service.unregisterOwner(requester)
        assertFalse(admission.endGrant(grant.grantId))

        var pluginEnabled = true
        val laterDisabledPlugin = plugin("later-disabled") { pluginEnabled }
        val disabledBinding = service.routeAdmission(laterDisabledPlugin, worldId)
        pluginEnabled = false
        assertEquals(
            RouteAdmissionFailureCode.WORLD_UNAVAILABLE,
            assertInstanceOf(RouteAdmissionResult.Rejected::class.java,
                disabledBinding.request(intent)).failure.code
        )

        service.removeWorld(worldId)
        assertEquals(
            RouteAdmissionFailureCode.WORLD_UNAVAILABLE,
            assertInstanceOf(RouteAdmissionResult.Rejected::class.java,
                admission.request(intent)).failure.code
        )
        service.registerWorld(worldId, { topology.revision }) { false }
        service.attachInfrastructureRuntime(
            worldId, TestWorld(topology), { observation }, {}, 5.0
        ) { _, _ -> }
        assertEquals(
            RouteAdmissionFailureCode.WORLD_UNAVAILABLE,
            assertInstanceOf(RouteAdmissionResult.Rejected::class.java,
                admission.request(intent)).failure.code
        )
    }

    @Test
    fun `forced plugin disable retains active route claim and stalls its world`() {
        val service = DefaultInfrastructureResourceService()
        val worldId = UUID.randomUUID()
        val railId = UUID.randomUUID()
        var pluginEnabled = true
        val requester = plugin("signal") { pluginEnabled }
        val topology = buildTestTopology(7L, listOf(TestTopologyRail(railId, 100.0)), emptyMap())
        val observation = routeObservation(worldId, topology.revision, 1L, railId, 0.0, 1.0)
        val stalls = mutableListOf<String>()
        service.registerWorld(worldId, { topology.revision }) { resource ->
            resource is RailIntervalResource && resource.railId == railId
        }
        service.attachInfrastructureRuntime(
            worldId, TestWorld(topology), { observation }, {}, 5.0
        ) { reason, _ ->
            stalls += reason
        }
        val admission = service.routeAdmission(requester, worldId)
        val intent = RouteResourceIntent(
            "test.signal:request/main",
            InfrastructureOwner("test.signal:route/main", 1L),
            "train",
            observation.stamp,
            RouteSnapshot(
                "test.signal:route/main",
                1L,
                topology.revision,
                listOf(RouteSegmentSnapshot(railId, ViewDirection.AB, 0.0, 100.0))
            ),
            RouteBoundary(20.0)
        )
        assertInstanceOf(RouteAdmissionResult.Granted::class.java, admission.request(intent))

        pluginEnabled = false
        service.forcedDisableOwner(requester)

        assertEquals(1, service.liveClaims(worldId).size)
        assertEquals(1, stalls.size)
        assertTrue(stalls.single().contains("Bukkit plugin signal"))
        assertTrue(stalls.single().contains(intent.owner.ownerId))
        assertEquals(
            RouteAdmissionFailureCode.WORLD_UNAVAILABLE,
            assertInstanceOf(RouteAdmissionResult.Rejected::class.java,
                admission.request(intent)).failure.code
        )
    }

    @Test
    fun `forced contributor disable stalls even when it contributed no resource`() {
        val service = DefaultInfrastructureResourceService()
        val worldId = UUID.randomUUID()
        val railId = UUID.randomUUID()
        val requester = plugin("ato")
        var contributorEnabled = true
        val contributor = plugin("signal-policy") { contributorEnabled }
        val topology = buildTestTopology(7L, listOf(TestTopologyRail(railId, 100.0)), emptyMap())
        val observation = routeObservation(worldId, topology.revision, 1L, railId, 0.0, 1.0)
        val stalls = mutableListOf<String>()
        service.registerRouteResourceContributor(contributor) { _, _ -> emptyList() }
        service.registerWorld(worldId, { topology.revision }) { resource ->
            resource is RailIntervalResource && resource.railId == railId
        }
        service.attachInfrastructureRuntime(
            worldId, TestWorld(topology), { observation }, {}, 5.0
        ) { reason, _ ->
            stalls += reason
        }
        val intent = RouteResourceIntent(
            "test.ato:request/main",
            InfrastructureOwner("test.ato:route/main", 1L),
            "train",
            observation.stamp,
            RouteSnapshot(
                "test.ato:route/main",
                1L,
                topology.revision,
                listOf(RouteSegmentSnapshot(railId, ViewDirection.AB, 0.0, 100.0))
            ),
            RouteBoundary(20.0)
        )
        assertInstanceOf(RouteAdmissionResult.Granted::class.java,
            service.routeAdmission(requester, worldId).request(intent))

        contributorEnabled = false
        service.forcedDisableOwner(contributor)

        assertEquals(1, stalls.size)
        assertTrue(stalls.single().contains("route-resource contributor"))
        assertEquals(1, service.liveClaims(worldId).size)
    }

    @Test
    fun `resource provider cannot unregister while another plugin holds its route resource`() {
        val service = DefaultInfrastructureResourceService()
        val worldId = UUID.randomUUID()
        val railId = UUID.randomUUID()
        var providerEnabled = true
        val providerOwner = plugin("block-provider") { providerEnabled }
        val requester = plugin("ato")
        val contributor = plugin("signal-policy")
        val block = StableInfrastructureResource("example.block:section", "main")
        val topology = buildTestTopology(7L, listOf(TestTopologyRail(railId, 100.0)), emptyMap())
        val observation = routeObservation(worldId, topology.revision, 1L, railId, 0.0, 1.0)
        val stalls = mutableListOf<String>()
        var contributionCalls = 0
        val registration = service.registerResourceProvider(providerOwner, block.typeId, object : InfrastructureResourceProvider {
            override fun exists(candidateWorld: UUID, resourceId: String) =
                candidateWorld == worldId && resourceId == block.resourceId

            override fun validateRouteContribution(
                actualContributor: Plugin,
                candidateWorld: UUID,
                resourceId: String,
                descriptor: ResourcePreparationDescriptor?
            ): RouteAdmissionFailureCode? {
                assertSame(contributor, actualContributor)
                return null
            }
        }, emptyList())
        val contributorRegistration = service.registerRouteResourceContributor(contributor) { candidateWorld, _ ->
            contributionCalls++
            if (candidateWorld == worldId) {
                listOf(ContributedRouteResource(block, RouteBoundary(20.0)))
            } else {
                emptyList()
            }
        }
        service.registerWorld(worldId, { topology.revision }) { resource ->
            resource is RailIntervalResource && resource.railId == railId ||
                resource is StableInfrastructureResource && service.stableResourceExists(worldId, resource)
        }
        service.attachInfrastructureRuntime(
            worldId, TestWorld(topology), { observation }, {}, 5.0
        ) { reason, _ ->
            stalls += reason
        }
        val admission = service.routeAdmission(requester, worldId)
        val intent = RouteResourceIntent(
            "test.signal:request/provider",
            InfrastructureOwner("test.signal:route/provider", 1L),
            "train",
            observation.stamp,
            RouteSnapshot(
                "test.signal:route/provider",
                1L,
                topology.revision,
                listOf(RouteSegmentSnapshot(railId, ViewDirection.AB, 0.0, 100.0))
            ),
            RouteBoundary(20.0)
        )
        assertInstanceOf(RouteAdmissionResult.Granted::class.java, admission.request(intent))
        assertEquals(1, contributionCalls)

        val contributorActive = assertThrows(IllegalStateException::class.java) {
            contributorRegistration.close()
        }
        assertTrue(contributorActive.message.orEmpty().contains("route states remain active"))
        assertThrows(IllegalStateException::class.java) {
            service.unregisterOwner(contributor)
        }
        val registrationActive = assertThrows(IllegalStateException::class.java) {
            registration.close()
        }
        assertTrue(registrationActive.message.orEmpty().contains(block.typeId))
        val active = assertThrows(IllegalStateException::class.java) {
            service.unregisterOwner(providerOwner)
        }
        assertTrue(active.message.orEmpty().contains("types=${block.typeId}"))
        assertEquals(1, service.liveClaims(worldId).size)

        providerEnabled = false
        service.forcedDisableOwner(providerOwner)
        assertEquals(1, service.liveClaims(worldId).size)
        assertEquals(1, stalls.size)
        assertTrue(stalls.single().contains("block-provider"))
        assertTrue(stalls.single().contains(block.typeId))
    }

    @Test
    fun `provider preparation keeps route pending until its ticket is ready`() {
        val service = DefaultInfrastructureResourceService()
        val worldId = UUID.randomUUID()
        val railId = UUID.randomUUID()
        val providerOwner = plugin("device-provider")
        val contributor = plugin("signal-policy")
        val requester = plugin("ato")
        val resource = StableInfrastructureResource("example.device:bridge", "main")
        val descriptor = ResourcePreparationDescriptor("example.device:align", emptyMap())
        val ticket = MutablePreparationTicket()
        var attemptId: UUID? = null
        var contributionCalls = 0
        val topology = buildTestTopology(7L, listOf(TestTopologyRail(railId, 100.0)), emptyMap())
        val observation = routeObservation(worldId, topology.revision, 1L, railId, 0.0, 1.0)
        service.registerResourceProvider(
            providerOwner,
            resource.typeId,
            object : InfrastructureResourceProvider {
                override fun exists(candidateWorld: UUID, resourceId: String) =
                    candidateWorld == worldId && resourceId == resource.resourceId

                override fun validateRouteContribution(
                    actualContributor: Plugin,
                    candidateWorld: UUID,
                    resourceId: String,
                    preparation: ResourcePreparationDescriptor?
                ): RouteAdmissionFailureCode? =
                    if (actualContributor === contributor && candidateWorld == worldId &&
                        resourceId == resource.resourceId && preparation == descriptor
                    ) null else RouteAdmissionFailureCode.INVALID_PREPARATION
            },
            listOf(object : InfrastructureResourcePreparationHandler {
                override fun kindId() = descriptor.kindId

                override fun start(
                    actualAttemptId: UUID,
                    candidateWorld: UUID,
                    actualResource: StableInfrastructureResource,
                    actualDescriptor: ResourcePreparationDescriptor
                ): ResourcePreparationTicket {
                    assertTrue(BukkitThreadGuardBridge.isMainThread())
                    assertEquals(worldId, candidateWorld)
                    assertEquals(resource, actualResource)
                    assertEquals(descriptor, actualDescriptor)
                    attemptId = actualAttemptId
                    return ticket
                }
            })
        )
        service.registerRouteResourceContributor(contributor) { candidateWorld, _ ->
            contributionCalls++
            if (candidateWorld == worldId) {
                listOf(ContributedRouteResource(resource, RouteBoundary(20.0), descriptor))
            } else emptyList()
        }
        service.registerWorld(worldId, { topology.revision }) { claimed ->
            claimed is RailIntervalResource && claimed.railId == railId ||
                claimed is StableInfrastructureResource && service.stableResourceExists(worldId, claimed)
        }
        service.attachInfrastructureRuntime(
            worldId, TestWorld(topology), { observation }, {}, 5.0
        ) { reason, failure ->
            throw IllegalStateException("unexpected stall: $reason", failure)
        }
        val admission = service.routeAdmission(requester, worldId)
        val intent = RouteResourceIntent(
            "test.ato:request/preparation",
            InfrastructureOwner("test.ato:route/preparation", 1L),
            "train",
            observation.stamp,
            RouteSnapshot(
                "test.ato:route/preparation",
                1L,
                topology.revision,
                listOf(RouteSegmentSnapshot(railId, ViewDirection.AB, 0.0, 100.0))
            ),
            RouteBoundary(20.0)
        )

        val pending = assertInstanceOf(RouteAdmissionResult.Pending::class.java, admission.request(intent))
        assertEquals(1, contributionCalls)
        assertTrue(attemptId != null)
        assertEquals(1, service.liveClaims(worldId).size)
        ticket.state = ResourcePreparationState.READY

        assertInstanceOf(RouteAdmissionResult.Granted::class.java, admission.progress(pending.handle))
        assertEquals(1, contributionCalls)
    }

    @Test
    fun `world route admission is attached once consumes the published observation and closes with world`() {
        val service = DefaultInfrastructureResourceService()
        val worldId = UUID.randomUUID()
        val topology = buildTestTopology(7L, emptyList(), emptyMap())
        val world = TestWorld(topology)
        val observation = RailwayObservation.fromCore(
            worldId.toString(),
            RailwayWorldFrameSnapshot.create(1L, topology.revision, emptyList())
        )
        service.registerWorld(worldId, { topology.revision }) { false }
        service.attachInfrastructureRuntime(
            worldId, world, { observation }, {}, 5.0
        ) { reason, failure ->
            throw IllegalStateException("unexpected stall: $reason", failure)
        }

        service.consumeObservation(worldId, observation)
        assertThrows(IllegalStateException::class.java) {
            service.attachInfrastructureRuntime(
                worldId, world, { observation }, {}, 5.0
            ) { _, _ -> }
        }

        service.removeWorld(worldId)
        assertThrows(IllegalStateException::class.java) {
            service.consumeObservation(worldId, observation)
        }
    }

    @Test
    fun `route contributions require resource owner authorization beyond lease existence`() {
        val service = DefaultInfrastructureResourceService()
        val worldId = UUID.randomUUID()
        val owner = plugin("resource-owner")
        val allowed = plugin("allowed-signal")
        val denied = plugin("other-signal")
        val resource = StableInfrastructureResource("example.signal:block", "home")
        val contribution = ContributedRouteResource(resource, RouteBoundary(10.0))
        val preparation = ResourcePreparationDescriptor("example.signal:prepare", emptyMap())
        registerWorld(service, worldId)
        service.registerResourceProvider(owner, resource.typeId, object : InfrastructureResourceProvider {
            override fun exists(candidateWorld: UUID, resourceId: String) =
                candidateWorld == worldId && resourceId == "home"

            override fun validateRouteContribution(
                requester: Plugin,
                candidateWorld: UUID,
                resourceId: String,
                descriptor: ResourcePreparationDescriptor?
            ): RouteAdmissionFailureCode? {
                assertTrue(BukkitThreadGuardBridge.isMainThread())
                assertEquals(worldId, candidateWorld)
                assertEquals("home", resourceId)
                return when {
                    requester !== allowed -> RouteAdmissionFailureCode.INVALID_CONTRIBUTION
                    descriptor != null && descriptor.kindId != preparation.kindId ->
                        RouteAdmissionFailureCode.INVALID_PREPARATION
                    else -> null
                }
            }
        }, emptyList())

        assertEquals(null, service.validateRouteContribution(allowed, worldId, contribution))
        assertEquals(
            RouteAdmissionFailureCode.INVALID_PREPARATION,
            service.validateRouteContribution(allowed, worldId, contribution.copy(preparation = preparation))?.code
        )
        assertEquals(
            RouteAdmissionFailureCode.INVALID_CONTRIBUTION,
            service.validateRouteContribution(denied, worldId, contribution)?.code
        )
        assertEquals(
            RouteAdmissionFailureCode.INVALID_CONTRIBUTION,
            service.validateRouteContribution(
                allowed, worldId,
                ContributedRouteResource(resource.copy(resourceId = "missing"), RouteBoundary(10.0))
            )?.code
        )
        assertEquals(
            RouteAdmissionFailureCode.INVALID_PREPARATION,
            service.validateRouteContribution(
                allowed, worldId,
                contribution.copy(preparation = ResourcePreparationDescriptor("example.signal:wrong", emptyMap()))
            )?.code
        )
        assertEquals(
            RouteAdmissionFailureCode.WORLD_UNAVAILABLE,
            service.validateRouteContribution(allowed, UUID.randomUUID(), contribution)?.code
        )
        assertTrue(service.liveClaims(worldId).isEmpty())
    }

    @Test
    fun `explicit route denial and unregister prevent authorization`() {
        val service = DefaultInfrastructureResourceService()
        val worldId = UUID.randomUUID()
        val owner = plugin("resource-owner")
        val requester = plugin("signal")
        val resource = StableInfrastructureResource("example.signal:block", "home")
        val contribution = ContributedRouteResource(resource, RouteBoundary(10.0))
        registerWorld(service, worldId)
        val registration = service.registerResourceProvider(
            owner, resource.typeId, rejectingRouteProvider { _, _ -> true }, emptyList()
        )

        assertEquals(RouteAdmissionFailureCode.INVALID_CONTRIBUTION,
            service.validateRouteContribution(requester, worldId, contribution)?.code)
        registration.close()
        assertEquals(RouteAdmissionFailureCode.INVALID_CONTRIBUTION,
            service.validateRouteContribution(requester, worldId, contribution)?.code)
        assertTrue(service.liveClaims(worldId).isEmpty())
    }

    @Test
    fun `provider failure or removal during validation cannot authorize an contribution`() {
        val service = DefaultInfrastructureResourceService()
        val worldId = UUID.randomUUID()
        val owner = plugin("resource-owner")
        val requester = plugin("signal")
        val resource = StableInfrastructureResource("example.signal:block", "home")
        val contribution = ContributedRouteResource(resource, RouteBoundary(10.0))
        registerWorld(service, worldId)
        val failing = service.registerResourceProvider(owner, resource.typeId, object : InfrastructureResourceProvider {
            override fun exists(candidateWorld: UUID, resourceId: String) = true

            override fun validateRouteContribution(
                requester: Plugin,
                candidateWorld: UUID,
                resourceId: String,
                descriptor: ResourcePreparationDescriptor?
            ): RouteAdmissionFailureCode? = error("provider failed")
        }, emptyList())
        assertEquals(RouteAdmissionFailureCode.INVALID_CONTRIBUTION,
            service.validateRouteContribution(requester, worldId, contribution)?.code)
        failing.close()

        lateinit var registration: InfrastructureResourceProviderRegistration
        registration = service.registerResourceProvider(owner, resource.typeId, object : InfrastructureResourceProvider {
            override fun exists(candidateWorld: UUID, resourceId: String) = true

            override fun validateRouteContribution(
                requester: Plugin,
                candidateWorld: UUID,
                resourceId: String,
                descriptor: ResourcePreparationDescriptor?
            ): RouteAdmissionFailureCode? {
                registration.close()
                return null
            }
        }, emptyList())
        assertEquals(RouteAdmissionFailureCode.INVALID_CONTRIBUTION,
            service.validateRouteContribution(requester, worldId, contribution)?.code)
        assertTrue(service.liveClaims(worldId).isEmpty())
    }

    @Test
    fun `applied dependencies validate runtime resource identities without claiming them`() {
        val service = DefaultInfrastructureResourceService()
        val worldId = UUID.randomUUID()
        val plugin = plugin("signal")
        var providerRanInSimulationContext = false
        val provider = service.registerResourceProvider(plugin, "example.signal:signal", rejectingRouteProvider { candidateWorld, id ->
            providerRanInSimulationContext = BukkitThreadGuardBridge.isMainThread()
            candidateWorld == worldId && id == "home"
        }, emptyList())
        registerWorld(service, worldId)

        assertEquals(
            AppliedPlanDependencyMutationStatus.APPLIED,
            service.setAppliedPlanDependencies(
                plugin, worldId, dependencies("example.signal:plan/main", "example.signal:signal", "home")
            ).status
        )
        assertTrue(providerRanInSimulationContext)
        assertEquals(
            AppliedPlanDependencyMutationStatus.INVALID_REFERENCE,
            service.setAppliedPlanDependencies(
                plugin, worldId, dependencies("example.signal:plan/missing", "example.signal:signal", "missing")
            ).status
        )
        assertEquals(
            AppliedPlanDependencyMutationStatus.INVALID_REFERENCE,
            service.setAppliedPlanDependencies(
                plugin, worldId, dependencies("example.signal:plan/unknown", "other.plugin:signal", "home")
            ).status
        )

        assertThrows(IllegalStateException::class.java) { provider.close() }
        assertEquals(
            AppliedPlanDependencyMutationStatus.REMOVED,
            service.removeAppliedPlanDependencies(
                plugin,
                worldId,
                InfrastructureOwner("example.signal:plan/main", 1L)
            ).status
        )
        provider.close()
        provider.close()
        assertEquals(
            AppliedPlanDependencyMutationStatus.INVALID_REFERENCE,
            service.setAppliedPlanDependencies(
                plugin, worldId, dependencies("example.signal:plan/after-close", "example.signal:signal", "home")
            ).status
        )
    }

    @Test
    fun `plugin cleanup requires explicit unapply and removes disposable ownership by identity`() {
        val service = DefaultInfrastructureResourceService()
        val worldId = UUID.randomUUID()
        val first = plugin("same-name")
        val second = plugin("same-name")
        service.registerResourceProvider(
            first, "example.signal:signal", rejectingRouteProvider { _, id -> id == "home" }, emptyList()
        )
        registerWorld(service, worldId)
        service.setAppliedPlanDependencies(
            first, worldId, dependencies("example.signal:plan/main", "example.signal:signal", "home")
        )
        val edit = service.acquireEditPermit(
            first,
            worldId,
            TopologyEditRequest(
                7L,
                InfrastructureOwner("example.signal:editor/main", 1L),
                editImpact("example.signal:other", "edit-target")
            )
        )
        // Register a second provider so the edit target has an authoritative identity.
        assertFalse(edit.decision().successful)
        service.registerResourceProvider(
            first, "example.signal:other", rejectingRouteProvider { _, id -> id == "edit-target" }, emptyList()
        )
        val heldEdit = service.acquireEditPermit(
            first,
            worldId,
            TopologyEditRequest(
                7L,
                InfrastructureOwner("example.signal:editor/main", 1L),
                editImpact("example.signal:other", "edit-target")
            )
        )
        assertTrue(heldEdit.decision().successful)

        service.unregisterOwner(second)
        assertEquals(1, service.appliedPlanDependencies(worldId).size)
        assertThrows(IllegalStateException::class.java) { service.unregisterOwner(first) }
        assertEquals(1, service.appliedPlanDependencies(worldId).size)
        assertEquals(
            AppliedPlanDependencyMutationStatus.REMOVED,
            service.removeAppliedPlanDependencies(
                first,
                worldId,
                InfrastructureOwner("example.signal:plan/main", 1L)
            ).status
        )
        service.unregisterOwner(first)
        assertTrue(service.appliedPlanDependencies(worldId).isEmpty())
        assertEquals(
            AppliedPlanDependencyMutationStatus.INVALID_REFERENCE,
            service.setAppliedPlanDependencies(
                second, worldId, dependencies("example.signal:plan/new", "example.signal:signal", "home")
            ).status
        )
        heldEdit.permit().orElseThrow().close()
    }

    @Test
    fun `forced plugin disable retains applied dependencies and stalls its world`() {
        val service = DefaultInfrastructureResourceService()
        val worldId = UUID.randomUUID()
        val railId = UUID.randomUUID()
        var pluginEnabled = true
        val plugin = plugin("signal") { pluginEnabled }
        val requester = plugin("ato")
        val topology = buildTestTopology(7L, listOf(TestTopologyRail(railId, 100.0)), emptyMap())
        val observation = routeObservation(worldId, topology.revision, 1L, railId, 0.0, 1.0)
        val stalls = mutableListOf<String>()
        service.registerResourceProvider(
            plugin,
            "example.signal:signal",
            rejectingRouteProvider { _, id -> id == "home" },
            emptyList()
        )
        service.registerWorld(worldId, { topology.revision }) { resource ->
            resource is RailIntervalResource && resource.railId == railId ||
                resource is StableInfrastructureResource && service.stableResourceExists(worldId, resource)
        }
        service.attachInfrastructureRuntime(
            worldId, TestWorld(topology), { observation }, {}, 5.0
        ) { reason, _ -> stalls += reason }
        assertEquals(
            AppliedPlanDependencyMutationStatus.APPLIED,
            service.setAppliedPlanDependencies(
                plugin,
                worldId,
                dependencies("example.signal:plan/main", "example.signal:signal", "home")
            ).status
        )

        pluginEnabled = false
        service.forcedDisableOwner(plugin)

        assertEquals(1, service.appliedPlanDependencies(worldId).size)
        assertEquals(1, stalls.size)
        assertTrue(stalls.single().contains("example.signal:plan/main@1"))
        val rejected = assertInstanceOf(
            RouteAdmissionResult.Rejected::class.java,
            service.routeAdmission(requester, worldId).request(
                RouteResourceIntent(
                    "test.ato:request/main",
                    InfrastructureOwner("test.ato:route/main", 1L),
                    "train",
                    observation.stamp,
                    RouteSnapshot(
                        "test.ato:route/main",
                        1L,
                        topology.revision,
                        listOf(RouteSegmentSnapshot(railId, ViewDirection.AB, 0.0, 100.0))
                    ),
                    RouteBoundary(20.0)
                )
            )
        )
        assertEquals(RouteAdmissionFailureCode.WORLD_STALLED, rejected.failure.code)
    }

    @Test
    fun `world removal and close invalidate service state and permits are idempotent`() {
        val service = DefaultInfrastructureResourceService()
        val worldId = UUID.randomUUID()
        val plugin = plugin("signal")
        service.registerResourceProvider(
            plugin, "example.signal:signal", rejectingRouteProvider { _, _ -> true }, emptyList()
        )
        registerWorld(service, worldId)
        val decision = service.acquireEditPermit(
            plugin,
            worldId,
            TopologyEditRequest(
                7L,
                InfrastructureOwner("example.signal:editor/main", 1L),
                editImpact("example.signal:signal", "home")
            )
        )
        val permit = decision.permit().orElseThrow()

        service.removeWorld(worldId)
        permit.close()
        permit.close()
        assertEquals(
            AppliedPlanDependencyMutationStatus.WORLD_UNAVAILABLE,
            service.setAppliedPlanDependencies(
                plugin, worldId, dependencies("example.signal:plan/main", "example.signal:signal", "home")
            ).status
        )

        service.close()
        service.close()
        assertThrows(IllegalStateException::class.java) {
            service.registerResourceProvider(
                plugin, "example.signal:late", rejectingRouteProvider { _, _ -> true }, emptyList()
            )
        }
    }

    @Test
    fun `topology revision is read from world at mutation time`() {
        val service = DefaultInfrastructureResourceService()
        val worldId = UUID.randomUUID()
        val plugin = plugin("signal")
        var currentRevision = 7L
        service.registerResourceProvider(
            plugin, "example.signal:signal", rejectingRouteProvider { _, _ -> true }, emptyList()
        )
        service.registerWorld(worldId, { currentRevision }) { resource ->
            resource is StableInfrastructureResource && service.stableResourceExists(worldId, resource)
        }

        currentRevision = 8L
        assertEquals(
            AppliedPlanDependencyMutationStatus.STALE_TOPOLOGY,
            service.setAppliedPlanDependencies(
                plugin, worldId, dependencies("example.signal:plan/main", "example.signal:signal", "home")
            ).status
        )
        assertTrue(service.appliedPlanDependencies(worldId).isEmpty())
    }

    @Test
    fun `resource provider failure is typed and cannot partially acquire`() {
        val service = DefaultInfrastructureResourceService()
        val worldId = UUID.randomUUID()
        val plugin = plugin("signal")
        service.registerResourceProvider(
            plugin, "example.signal:signal",
            rejectingRouteProvider { _, _ -> error("provider offline") }, emptyList()
        )
        registerWorld(service, worldId)

        val result = service.setAppliedPlanDependencies(
            plugin,
            worldId,
            dependencies("example.signal:plan/main", "example.signal:signal", "home")
        )

        assertEquals(AppliedPlanDependencyMutationStatus.INVALID_REFERENCE, result.status)
        assertTrue(result.detail.contains("provider offline"))
        assertTrue(service.appliedPlanDependencies(worldId).isEmpty())
    }

    private fun registerWorld(service: DefaultInfrastructureResourceService, worldId: UUID) {
        service.registerWorld(worldId, { 7L }) { resource: InfrastructureResource ->
            resource is StableInfrastructureResource && service.stableResourceExists(worldId, resource)
        }
    }

    private fun rejectingRouteProvider(exists: (UUID, String) -> Boolean) = object : InfrastructureResourceProvider {
        override fun exists(worldId: UUID, resourceId: String) = exists.invoke(worldId, resourceId)

        override fun validateRouteContribution(
            requester: Plugin,
            worldId: UUID,
            resourceId: String,
            descriptor: ResourcePreparationDescriptor?
        ) = RouteAdmissionFailureCode.INVALID_CONTRIBUTION
    }

    private class MutablePreparationTicket(
        var state: ResourcePreparationState = ResourcePreparationState.PREPARING
    ) : ResourcePreparationTicket {
        override fun snapshot() = ResourcePreparationSnapshot(state)

        override fun abort() {
            if (state == ResourcePreparationState.PREPARING) {
                state = ResourcePreparationState.ABORTED_SAFE
            }
        }
    }

    private fun dependencies(ownerId: String, typeId: String, resourceId: String) = AppliedPlanDependencyRequest(
        7L,
        InfrastructureOwner(ownerId, 1L),
        AppliedPlanDependencies(emptySet(), emptySet(), setOf(StableInfrastructureResource(typeId, resourceId)))
    )

    private fun editImpact(typeId: String, resourceId: String) =
        TopologyEditImpact(emptySet(), emptySet(), setOf(StableInfrastructureResource(typeId, resourceId)))

    private fun footprint(typeId: String, resourceId: String) =
        InfrastructureFootprint(listOf(StableInfrastructureResource(typeId, resourceId)))

    private fun routeObservation(
        worldId: UUID,
        topologyRevision: Long,
        epoch: Long,
        railId: UUID,
        tail: Double,
        head: Double
    ): RailwayObservation {
        val train = TrainFrameSnapshot.create(
            trainId = "train",
            trainName = "train",
            revision = 1L,
            physicsEpoch = epoch,
            speedMetersPerSecond = 0.0,
            derailed = false,
            reversed = false,
            cars = emptyList(),
            head = RailPositionSnapshot(railId, ViewDirection.AB, head),
            tail = RailPositionSnapshot(railId, ViewDirection.AB, tail),
            occupiedIntervals = listOf(RailInterval(railId, tail, head)),
            bodyTraversal = emptyList(),
            sweptSpans = listOf(SweptRailSpanSnapshot(railId, tail, head)),
            headTraversal = emptyList(),
            tailTraversal = emptyList()
        )
        return RailwayObservation.fromCore(
            worldId.toString(),
            RailwayWorldFrameSnapshot.create(epoch, topologyRevision, listOf(train))
        )
    }

    private fun plugin(name: String, enabled: () -> Boolean = { true }): Plugin {
        lateinit var proxy: Plugin
        proxy = Proxy.newProxyInstance(
            Plugin::class.java.classLoader,
            arrayOf(Plugin::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getName", "toString" -> name
                "isEnabled" -> enabled()
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> primitiveDefault(method.returnType)
            }
        } as Plugin
        return proxy
    }

    @OptIn(ExtensionData::class)
    private class TestWorld(override val topologyState: TopologyState) : World {
        override val tickManager: TickManager get() = error("tick manager is not used by this test")
        override val allTrains: Collection<Train> = emptyList()

        override fun getTrain(name: String): Train? = null
        override fun removeTrain(train: Train) = Unit
        override fun getRail(id: UUID): Rail? = null
        override fun removeRail(rail: Rail) = Unit
        override fun <T : Any> getExtensionDataOrNull(plugin: CorePlugin): T? = null
        override fun <T : Any> requireExtensionData(plugin: CorePlugin): T = error("no extensions")
        override fun <T : Any> registerExtensionData(plugin: CorePlugin, data: T) = Unit
        override fun forEachExtensionData(action: (Any) -> Unit) = Unit
        override fun <T> forEachExtensionData(action: (Any) -> T): List<T> = emptyList()
    }

    private fun primitiveDefault(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Character.TYPE -> '\u0000'
        else -> null
    }

    private fun straightCurve(fromX: Double, toX: Double, toZ: Double): PrimeCurve {
        val from = Vector3d(fromX, 0.0, 0.0)
        val to = Vector3d(toX, 0.0, toZ)
        val tangent = Vector3d(to).sub(from).normalize()
        val normal = Vector3d(0.0, 1.0, 0.0)
        return PrimeCurve.fromPoints(listOf(
            Curve.RawPoint(from, tangent, normal, 0.0, Curve.SegmentType.LINE_START),
            Curve.RawPoint(to, tangent, normal, from.distance(to), Curve.SegmentType.LINE_END)
        ))
    }
}
