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
import work.art1st.javarailways.core.physics.train.onOwnerForTest
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureDependencies
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureDependencyMutationStatus
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureDependencyRequest
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureFootprint
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureMutationStatus
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureOwner
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureResource
import work.art1st.javarailways.basic.api.infrastructure.ManualTurnoutSelectionRequest
import work.art1st.javarailways.basic.api.infrastructure.ManualTurnoutSelectionResult
import work.art1st.javarailways.basic.api.infrastructure.ManualTurnoutSelectionStatus
import work.art1st.javarailways.core.api.physics.infrastructure.RailIntervalResource
import work.art1st.javarailways.core.api.physics.infrastructure.StableInfrastructureResource
import work.art1st.javarailways.core.api.physics.infrastructure.TopologyEditImpact
import work.art1st.javarailways.core.api.physics.infrastructure.TopologyEditBlockerKind
import work.art1st.javarailways.core.api.physics.infrastructure.TopologyEditRequest
import work.art1st.javarailways.core.api.physics.infrastructure.TopologyEditStatus
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.api.route.ActiveRouteAdmissionState
import work.art1st.javarailways.basic.api.route.ActiveRouteAdmissionUpdate
import work.art1st.javarailways.basic.api.route.FormationTrainIdentity
import work.art1st.javarailways.basic.api.route.FormationRouteContext
import work.art1st.javarailways.basic.api.route.FormationRoutePurpose
import work.art1st.javarailways.basic.api.route.FormationRoutePolicyResult
import work.art1st.javarailways.basic.api.route.FormationResourceClearance
import work.art1st.javarailways.bukkit.api.infrastructure.RouteResourceContributor
import work.art1st.javarailways.basic.api.route.ContributedRouteResource
import work.art1st.javarailways.basic.api.route.ResourcePreparationDescriptor
import work.art1st.javarailways.basic.api.route.ResourcePreparationSnapshot
import work.art1st.javarailways.basic.api.route.ResourcePreparationState
import work.art1st.javarailways.basic.api.route.ResourcePreparationTicket
import work.art1st.javarailways.basic.api.route.RouteAdmissionFailureCode
import work.art1st.javarailways.basic.api.route.RouteAdmissionResult
import work.art1st.javarailways.basic.api.route.RouteBoundary
import work.art1st.javarailways.basic.api.route.RouteResourceIntent
import work.art1st.javarailways.basic.api.route.planning.RoutePlanningRestrictions
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
import work.art1st.javarailways.core.api.physics.rail.RailObjectRef
import work.art1st.javarailways.core.api.physics.rail.RailInterval
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.AtGradeRailCrossing
import work.art1st.javarailways.core.api.physics.rail.topology.ActiveRailContinuationsSnapshot
import work.art1st.javarailways.core.api.physics.rail.topology.RailCrossingPoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.core.api.physics.rail.topology.RailTopologyChange
import work.art1st.javarailways.core.api.physics.rail.topology.RailTopologyChangeListener
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
import work.art1st.javarailways.core.physics.train.createTrainWithConfig
import work.art1st.javarailways.core.physics.train.setControllerForTest
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinator
import work.art1st.javarailways.core.api.control.TrainController
import work.art1st.javarailways.core.util.IsolatedSimulationThreadMarker
import work.art1st.javarailways.basic.api.observation.RailwayStateStamp
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublicationResult
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublicationStatus
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublisher
import work.art1st.javarailways.simpleato.api.route.RoutePlanningRestrictionBinding
import work.art1st.javarailways.simpleato.inspection.InspectionRuntime
import work.art1st.javarailways.simpleato.routing.RoutePlanningRestrictionRuntime
import work.art1st.javarailways.simpleato.signal.SignalEpochRuntime
import work.art1st.javarailways.simpleato.api.signal.SignalEpochSource
import work.art1st.javarailways.simpleato.api.signal.SignalEpochResult
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DefaultInfrastructureResourceServiceTest {
    @Test
    fun `formation requires every contributor opt-in and exact provider clearance`() {
        val service = DefaultInfrastructureResourceService()
        val worldId = UUID.randomUUID()
        registerWorld(service, worldId)
        val owner = plugin("formation-policy")
        val resource = StableInfrastructureResource("test:block", "home")
        val context = FormationRouteContext::class.java.constructors.single { it.parameterCount == 6 }.newInstance("test:event",
            FormationRoutePurpose.OUTPUT_BODY, listOf(FormationTrainIdentity("source", listOf("car"), false)),
                    listOf(FormationTrainIdentity("train", listOf("car"), false)), listOf(resource), null) as FormationRouteContext
        val route = RouteSnapshot("test:route", 1L, 0L,
            listOf(RouteSegmentSnapshot(UUID.randomUUID(), ViewDirection.AB, 0.0, 20.0)))
        val intent = RouteResourceIntent("test:request", InfrastructureOwner("test:owner", 1L), "train",
            RailwayStateStamp(worldId.toString(), 1L, 0L), route, RouteBoundary(2.0), context)
        var clearances = listOf(FormationResourceClearance(resource, RouteBoundary(18.0)))
        var removeDuringValidation: (() -> Unit)? = null
        service.registerResourceProvider(owner, resource.typeId, object : InfrastructureResourceProvider {
            override fun exists(worldId: UUID, resourceId: String) = true
            override fun validateRouteContribution(contributor: Plugin, worldId: UUID, resourceId: String,
                preparation: ResourcePreparationDescriptor?): RouteAdmissionFailureCode? = null
            override fun authorizeFormationResource(worldId: UUID, resourceId: String, context: FormationRouteContext,
                intent: RouteResourceIntent): FormationRoutePolicyResult {
                removeDuringValidation?.invoke()
                return FormationRoutePolicyResult.Accepted(clearances)
            }
        }, emptyList())
        val legacy = service.registerRouteResourceContributor(owner, RouteResourceContributor { _, _ -> emptyList() })
        assertTrue(service.authorizeFormationRoute(worldId, context, intent) is FormationRoutePolicyResult.Rejected)
        legacy.close()
        val opted = service.registerRouteResourceContributor(owner, object : RouteResourceContributor {
            override fun contribute(worldId: UUID, intent: RouteResourceIntent) = emptyList<ContributedRouteResource>()
            override fun authorizeFormationRoute(worldId: UUID, context: FormationRouteContext,
                intent: RouteResourceIntent): RouteAdmissionFailureCode? = null
        })
        val accepted = service.authorizeFormationRoute(worldId, context, intent) as FormationRoutePolicyResult.Accepted
        assertEquals(RouteBoundary(18.0), accepted.clearances.single().releaseAfterTailPasses)
        clearances = emptyList()
        assertTrue(service.authorizeFormationRoute(worldId, context, intent) is FormationRoutePolicyResult.Rejected)
        clearances = listOf(FormationResourceClearance(resource.copy(resourceId = "forged"), null))
        assertTrue(service.authorizeFormationRoute(worldId, context, intent) is FormationRoutePolicyResult.Rejected)
        clearances = listOf(FormationResourceClearance(resource, RouteBoundary(21.0)))
        assertTrue(service.authorizeFormationRoute(worldId, context, intent) is FormationRoutePolicyResult.Rejected)
        clearances = listOf(FormationResourceClearance(resource, null))
        assertEquals(null, (service.authorizeFormationRoute(worldId, context, intent) as FormationRoutePolicyResult.Accepted)
            .clearances.single().releaseAfterTailPasses)
        removeDuringValidation = { opted.close() }
        assertTrue(service.authorizeFormationRoute(worldId, context, intent) is FormationRoutePolicyResult.Rejected)
        assertTrue(service.liveClaims(worldId).isEmpty())
        service.close()
    }

    @Test
    fun `planned owner removal awaits an in flight real signal epoch before checking restrictions`() {
        val guard = work.art1st.javarailways.core.api.threading.host.ThreadGuardHost.registerPlatformBridge(BukkitThreadGuardBridge)
        val sources = DefaultSignalEpochSourceService()
        val service = DefaultInfrastructureResourceService(sources)
        val world = createTestWorld(PhysicsCoordinator(1, automaticFallback = false))
        val worldId = UUID.randomUUID()
        val owner = plugin("signal")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val checked = CountDownLatch(1)
        val managementThread = java.util.concurrent.atomic.AtomicReference<Thread>()
        val checkedEpoch = java.util.concurrent.atomic.AtomicLong(-1L)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val rail = world.createRailForTest(straightCurve(0.0, 100.0, 0.0))
            service.registerWorld(worldId, { world.topologyState }, registry = world.infrastructureCoordination) { resource ->
                resource is RailIntervalResource && world.getRail(resource.railId) != null ||
                    resource is StableInfrastructureResource && service.stableResourceExists(worldId, resource)
            }
            service.attachInfrastructureRuntime(worldId, world,
                { RailwayObservation.fromCore(worldId.toString(), world.latestWorldFrame()) }) { reason, failure ->
                throw IllegalStateException(reason, failure)
            }
            val publisher = TrainRestrictionPublisher { snapshot ->
                TrainRestrictionPublicationResult(TrainRestrictionPublicationStatus.ACCEPTED, snapshot.revision)
            }
            val epochs = SignalEpochRuntime(worldId.toString(), publisher)
            sources.registerWorld(worldId, epochs, InspectionRuntime(publisher) { _, _, _ -> }, {
                checkedEpoch.set(world.latestWorldFrame().physicsEpoch)
                checked.countDown()
                emptyList()
            }, physicalWorld = world) { _, _ -> }
            sources.replaceSources(owner, worldId, listOf(SignalEpochSource(
                "test:actual-epoch", emptyList(), emptyList(), listOf(rail.id)) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                SignalEpochResult.empty()
            }))
            world.physicsCoordinator.registerRailwayEpochProcessor(epochs)
            world.physicsCoordinator.start()
            val ticket = world.physicsCoordinator.stepSimulation(50.0)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val removal = executor.submit {
                managementThread.set(Thread.currentThread())
                service.unregisterOwner(owner)
            }
            val bean = java.lang.management.ManagementFactory.getThreadMXBean()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (checked.count > 0L && managementThread.get()?.let {
                bean.getThreadInfo(it.threadId())?.lockInfo?.className
            } != "java.util.concurrent.CompletableFuture\$Signaller" && System.nanoTime() < deadline) Thread.yield()
            assertEquals(1L, checked.count, "restriction preflight must wait for the captured epoch")
            assertEquals("java.util.concurrent.CompletableFuture\$Signaller",
                bean.getThreadInfo(managementThread.get().threadId())?.lockInfo?.className)
            assertFalse(removal.isDone)
            assertFalse(ticket.isDone)
            release.countDown()
            ticket.await()
            removal.get(5, TimeUnit.SECONDS)
            assertEquals(ticket.epoch, checkedEpoch.get())
        } finally {
            release.countDown()
            executor.shutdownNow()
            service.close()
            sources.close()
            world.physicsCoordinator.close()
            (world.tickManager as AutoCloseable).close()
            guard.close()
        }
    }

    @Test
    fun `unregister check and commit exclude a new admission in another world`() {
        val sources = DefaultSignalEpochSourceService()
        val service = DefaultInfrastructureResourceService(sources)
        val firstId = UUID.randomUUID()
        val secondId = UUID.randomUUID()
        val railId = UUID.randomUUID()
        val owner = plugin("signal")
        val caller = plugin("ato")
        val checked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val startedRequest = CountDownLatch(1)
        val managementThread = java.util.concurrent.atomic.AtomicReference<Thread>()
        val requestingThread = java.util.concurrent.atomic.AtomicReference<Thread>()
        val executor = Executors.newFixedThreadPool(2)
        val topology = buildTestTopology(7L, listOf(TestTopologyRail(railId, 100.0)), emptyMap())
        val observation = routeObservation(secondId, 7L, 1L, railId, 0.0, 1.0)
        try {
            service.registerResourceProvider(owner, "test.signal:resource", rejectingRouteProvider { _, _ -> true }, emptyList())
            registerWorld(service, firstId)
            service.registerWorld(secondId, { topology }) { resource ->
                resource is RailIntervalResource && resource.railId == railId ||
                    resource is StableInfrastructureResource && service.stableResourceExists(secondId, resource)
            }
            service.attachInfrastructureRuntime(secondId, TestWorld(topology), { observation }) { reason, failure ->
                throw IllegalStateException(reason, failure)
            }
            var pauseCheck = false
            registerSignalWorld(sources, firstId, activeRestrictionStreams = {
                if (pauseCheck) {
                    checked.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
                emptyList()
            })
            sources.replaceSources(owner, firstId, listOf(SignalEpochSource(
                "test:managed", emptyList(), emptyList(), listOf(railId)) { SignalEpochResult.empty() }))
            val bound = service.routeAdmission(caller, secondId)
            pauseCheck = true
            val removed = executor.submit {
                managementThread.set(Thread.currentThread())
                service.unregisterOwner(owner)
            }
            assertTrue(checked.await(5, TimeUnit.SECONDS))
            val request = executor.submit<RouteAdmissionResult> {
                requestingThread.set(Thread.currentThread())
                startedRequest.countDown()
                bound.request(RouteResourceIntent("test.ato:request/main", InfrastructureOwner("test.ato:route/main", 1L),
                    "train", observation.stamp, RouteSnapshot("test.ato:route/main", 1L, 7L,
                        listOf(RouteSegmentSnapshot(railId, ViewDirection.AB, 0.0, 100.0))), RouteBoundary(20.0)))
            }
            assertTrue(startedRequest.await(5, TimeUnit.SECONDS))
            val bean = java.lang.management.ManagementFactory.getThreadMXBean()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (bean.getThreadInfo(requestingThread.get().threadId())?.lockOwnerId != managementThread.get().threadId() &&
                System.nanoTime() < deadline) Thread.yield()
            assertEquals(managementThread.get().threadId(), bean.getThreadInfo(requestingThread.get().threadId())?.lockOwnerId)
            assertFalse(request.isDone)
            release.countDown()
            removed.get(5, TimeUnit.SECONDS)
            assertInstanceOf(RouteAdmissionResult.Granted::class.java, request.get(5, TimeUnit.SECONDS))
            assertTrue(service.liveClaims(secondId).isNotEmpty())
        } finally {
            release.countDown()
            executor.shutdownNow()
            service.close()
            sources.close()
        }
    }

    @Test
    fun `bound route request waits for the exact physics ticket before turnout preparation`() {
        val service = DefaultInfrastructureResourceService()
        val world = createTestWorld(PhysicsCoordinator(1, automaticFallback = false))
        val worldId = UUID.randomUUID()
        val workerEntered = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)
        val requestStarted = CountDownLatch(1)
        val caller = Executors.newSingleThreadExecutor()
        try {
            val stem = world.createRailForTest(straightCurve(-100.0, 0.0, 0.0))
            val first = world.createRailForTest(straightCurve(0.0, 100.0, -50.0))
            val second = world.createRailForTest(straightCurve(0.0, 100.0, 50.0))
            IsolatedSimulationThreadMarker.run { CoreRail.connect(stem, first) }
            IsolatedSimulationThreadMarker.run { CoreRail.connect(stem, second) }
            world.onOwnerForTest { stem.viewAB.selectNextRail(second.viewAB) }
            val train = createTrainWithConfig("train", stem, world, listOf(10.0))
            train.setControllerForTest(object : TrainController(train) {
                override fun tick(deltaT: Double) {
                    workerEntered.countDown()
                    check(releaseWorker.await(5, TimeUnit.SECONDS))
                }
            })
            service.registerWorld(worldId, { world.topologyState }, registry = world.infrastructureCoordination) { resource ->
                resource is RailIntervalResource && world.getRail(resource.railId) != null ||
                    resource is StableInfrastructureResource && service.stableResourceExists(worldId, resource)
            }
            service.attachInfrastructureRuntime(
                worldId, world,
                latestObservation = {
                    world.physicsCoordinator.ensureBetweenFrames("bound admission observation")
                    RailwayObservation.fromCore(worldId.toString(), world.latestWorldFrame())
                }
            ) { reason, failure -> throw IllegalStateException(reason, failure) }
            val bound = service.routeAdmission(plugin("route"), worldId)
            world.physicsCoordinator.start()
            val ticket = world.physicsCoordinator.stepSimulation(50.0)
            assertTrue(workerEntered.await(5, TimeUnit.SECONDS))
            val request = RouteResourceIntent(
                "test:blocked/request", InfrastructureOwner("test:blocked/route", 1L), train.id,
                RailwayStateStamp(worldId.toString(), ticket.epoch, world.topologyRevision),
                RouteSnapshot("test:blocked/route", 1L, world.topologyRevision, listOf(
                    RouteSegmentSnapshot(stem.id, ViewDirection.AB,
                        train.publishedFrame.tail.arcLengthInAbMeters, stem.curve.totalArcLength),
                    RouteSegmentSnapshot(first.id, ViewDirection.AB, 0.0, first.curve.totalArcLength)
                )),
                RouteBoundary(120.0)
            )
            val result = caller.submit<RouteAdmissionResult> {
                IsolatedSimulationThreadMarker.run {
                    requestStarted.countDown()
                    bound.request(request)
                }
            }
            assertTrue(requestStarted.await(5, TimeUnit.SECONDS))
            assertFalse(result.isDone)
            assertSame(second.viewAB, stem.viewAB.nextActiveRail)
            releaseWorker.countDown()

            val granted = assertInstanceOf(RouteAdmissionResult.Granted::class.java, result.get(5, TimeUnit.SECONDS))
            assertSame(first.viewAB, stem.viewAB.nextActiveRail)
            assertSame(stem.viewBA, first.viewBA.nextActiveRail)
            assertEquals(ticket.epoch, world.latestWorldFrame().physicsEpoch)
            assertEquals(
                RailEndpointRef(first.id, RailEndpoint.A),
                world.latestWorldFrame().activeContinuations.selectedContinuation(RailEndpointRef(stem.id, RailEndpoint.B))
            )
            assertTrue(service.liveClaims(worldId).isNotEmpty())
            val unchangedFrame = world.latestWorldFrame()
            assertFalse(world.onOwnerForTest { bound.endGrant(granted.grant.grantId) })
            assertSame(unchangedFrame, world.latestWorldFrame(), "unchanged selection must not rebuild the world frame")
        } finally {
            releaseWorker.countDown()
            caller.shutdownNow()
            assertTrue(caller.awaitTermination(5, TimeUnit.SECONDS))
            service.close()
            world.physicsCoordinator.close()
            (world.tickManager as AutoCloseable).close()
        }
    }

    @Test
    fun `real unrelated transaction preserves live grant and idle signal while related edit remains blocked`() {
        val service = DefaultInfrastructureResourceService()
        val world = createTestWorld()
        val worldId = UUID.randomUUID()
        val owner = plugin("signal")
        try {
            val rail = world.createRailForTest(straightCurve(0.0, 100.0, 0.0))
            service.registerWorld(worldId, { world.topologyState }, registry = world.infrastructureCoordination,
                observeTopologyChanges = world::registerTopologyChangeListener) { resource ->
                resource is RailIntervalResource && world.getRail(resource.railId) != null ||
                    resource is StableInfrastructureResource && service.stableResourceExists(worldId, resource)
            }
            var observation = routeObservation(worldId, world.topologyRevision, 1L, rail.id, 14.0, 15.0)
            service.attachInfrastructureRuntime(worldId, world, { observation }) { reason, failure ->
                throw IllegalStateException(reason, failure)
            }
            var callbacks = 0
            val epochs = SignalEpochRuntime(worldId.toString(), TrainRestrictionPublisher {
                TrainRestrictionPublicationResult(TrainRestrictionPublicationStatus.ACCEPTED, it.revision)
            })
            epochs.replaceSources(listOf(SignalEpochSource("test:section", listOf(
                work.art1st.javarailways.basic.api.observation.RailwayOccupancyAreaDefinition.create(
                    RailObjectRef("test", "section"), 1L, world.topologyRevision,
                    listOf(RailInterval(rail.id, 10.0, 30.0)), listOf(
                        work.art1st.javarailways.basic.api.observation.RailwayOccupancyBoundary(
                            RailObjectRef("test", "entry"), RailPositionSnapshot(rail.id, ViewDirection.AB, 10.0)
                        )
                    )
                )
            )) {
                callbacks++
                SignalEpochResult.empty()
            }))
            service.observeTopologyChanges(owner, worldId, epochs::invalidateTopology)
            epochs.process(routeFrame(world.topologyRevision, 1L, rail.id, 14.0, 15.0))
            val route = RouteSnapshot("test:route", 1L, world.topologyRevision,
                listOf(RouteSegmentSnapshot(rail.id, ViewDirection.AB, 14.0, 100.0)))
            val admission = service.routeAdmission(owner, worldId)
            val routeOwner = InfrastructureOwner("test:train", 1L)
            val first = assertInstanceOf(RouteAdmissionResult.Granted::class.java, world.onOwnerForTest { admission.request(
                RouteResourceIntent("test:request", routeOwner, "train", observation.stamp, route, RouteBoundary(40.0))
            ) }).grant
            world.createRailForTest(straightCurve(1000.0, 1020.0, 1000.0))
            observation = routeObservation(worldId, world.topologyRevision, 2L, rail.id, 14.0, 15.0)
            service.consumeObservation(worldId, observation)
            epochs.process(routeFrame(world.topologyRevision, 2L, rail.id, 14.0, 15.0))
            val extended = assertInstanceOf(RouteAdmissionResult.Granted::class.java, world.onOwnerForTest { admission.request(
                RouteResourceIntent("test:request", routeOwner, "train", observation.stamp, route, RouteBoundary(60.0))
            ) }).grant
            assertSame(first.grantId, extended.grantId)
            assertSame(route, service.activeRouteAdmissions(worldId).single().route)
            assertEquals(1, callbacks)
            assertEquals(1, service.liveClaims(worldId).size)
            assertEquals(TopologyEditStatus.CONFLICT, service.acquireEditPermit(owner, worldId,
                TopologyEditRequest(world.topologyRevision, InfrastructureOwner("test:edit", 1L),
                    TopologyEditImpact(setOf(rail.id), emptySet(), emptySet()))).decision().status)
        } finally {
            service.close()
            world.physicsCoordinator.close()
        }
    }

    @Test
    fun `topology observers are scoped to owner registration and exact world lifetime`() {
        val service = DefaultInfrastructureResourceService()
        val worldId = UUID.randomUUID()
        val callbacks = linkedSetOf<RailTopologyChangeListener>()
        val root = buildTestTopology(7L, emptyList(), emptyMap())
        fun install() = service.registerWorld(worldId, { root }, observeTopologyChanges = { listener ->
            callbacks.add(listener)
            AutoCloseable { callbacks.remove(listener) }
        }) { false }
        install()
        val first = plugin("first")
        val second = plugin("second")
        val received = arrayListOf<String>()
        val change = RailTopologyChange(root, buildTestTopology(8L, emptyList(), emptyMap()), setOf(UUID.randomUUID()))
        val registration = service.observeTopologyChanges(first, worldId) {
            assertSame(change, it)
            assertSame(root, service.currentTopology(worldId))
            received += "first"
        }
        service.observeTopologyChanges(second, worldId) { received += "second" }
        val oldCallbacks = callbacks.toList()
        oldCallbacks.forEach { it.onTopologyChange(change) }
        assertEquals(listOf("first", "second"), received)
        registration.close()
        oldCallbacks.forEach { it.onTopologyChange(change) }
        assertEquals(listOf("first", "second", "second"), received)
        service.unregisterOwner(second)
        assertTrue(callbacks.isEmpty())
        service.observeTopologyChanges(first, worldId) { received += "unloaded" }
        val unloaded = callbacks.single()
        service.removeWorld(worldId)
        install()
        unloaded.onTopologyChange(change)
        assertEquals(3, received.size)
        service.observeTopologyChanges(first, worldId) { received += "disabled" }
        val disabled = callbacks.single()
        service.forcedDisableOwner(first)
        disabled.onTopologyChange(change)
        assertEquals(3, received.size)
        service.observeTopologyChanges(second, worldId) { received += "closed" }
        val closed = callbacks.single()
        service.close()
        closed.onTopologyChange(change)
        assertEquals(3, received.size)
        assertTrue(callbacks.isEmpty())
    }

    @Test
    fun `edit permit acquisition does not wait for the cross-world simulation lock`() {
        val service = DefaultInfrastructureResourceService()
        val worldId = UUID.randomUUID()
        val editor = plugin("editor")
        val resource = StableInfrastructureResource("example.editor:signal", "home")
        service.registerResourceProvider(
            editor,
            resource.typeId,
            rejectingRouteProvider { _, id -> id == resource.resourceId },
            emptyList()
        )
        registerWorld(service, worldId)
        val lockEntered = CountDownLatch(1)
        val releaseLock = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val heldSimulationLock = executor.submit {
                BukkitThreadGuardBridge.runInSimulationContext {
                    lockEntered.countDown()
                    check(releaseLock.await(5, TimeUnit.SECONDS))
                }
            }
            assertTrue(lockEntered.await(5, TimeUnit.SECONDS))

            val permitResult = executor.submit(java.util.concurrent.Callable {
                service.acquireEditPermit(
                    editor,
                    worldId,
                    TopologyEditRequest(
                        7L,
                        InfrastructureOwner("example.editor:mutation/main", 1L),
                        TopologyEditImpact(emptySet(), emptySet(), setOf(resource))
                    )
                )
            }).get(5, TimeUnit.SECONDS)

            assertEquals(TopologyEditStatus.GRANTED, permitResult.decision().status)
            permitResult.permit().orElseThrow().close()
            releaseLock.countDown()
            heldSimulationLock.get(5, TimeUnit.SECONDS)
        } finally {
            releaseLock.countDown()
            executor.shutdownNow()
            service.close()
        }
    }

    @Test
    fun `current topology returns the exact live root and same UUID reload does not revive it`() {
        val service = DefaultInfrastructureResourceService()
        val worldId = UUID.randomUUID()
        val first = buildTestTopology(7L, emptyList(), emptyMap())
        val reloaded = buildTestTopology(8L, emptyList(), emptyMap())

        service.registerWorld(worldId, { first }) { false }
        assertSame(first, service.currentTopology(worldId))

        service.removeWorld(worldId)
        assertThrows(IllegalStateException::class.java) { service.currentTopology(worldId) }

        service.registerWorld(worldId, { reloaded }) { false }
        assertSame(reloaded, service.currentTopology(worldId))
        service.close()
        assertThrows(IllegalStateException::class.java) { service.currentTopology(worldId) }
    }

    @Test
    fun `static speed subset is detached nullable immutable and rejects invalid provider results`() {
        val service = DefaultInfrastructureResourceService()
        val worldId = UUID.randomUUID()
        val rails = List(2) { UUID.randomUUID() }
        val topology = buildTestTopology(7, rails.map { TestTopologyRail(it, 100.0) }, emptyMap())
        var values: Map<UUID, Double?> = linkedMapOf(rails[0] to 20.0, rails[1] to null)
        var calls = 0
        service.registerWorld(worldId, { topology }, railSpeedLimits = { _, _ -> calls++; values }) { false }
        val captured = service.railSpeedLimits(worldId, topology, rails)
        assertEquals(1, calls)
        assertEquals(values, captured)
        (values as MutableMap)[rails[0]] = 10.0
        assertEquals(20.0, captured[rails[0]])
        assertThrows(UnsupportedOperationException::class.java) { (captured as MutableMap).clear() }
        listOf(
            emptyMap(), mapOf(rails[0] to 20.0),
            mapOf(rails[0] to 20.0, rails[1] to null, UUID.randomUUID() to 25.0),
            mapOf(rails[0] to Double.POSITIVE_INFINITY, rails[1] to null),
            mapOf(rails[0] to Double.NaN, rails[1] to null),
            mapOf(rails[0] to 0.0, rails[1] to null),
            mapOf(rails[0] to -1.0, rails[1] to null)
        ).forEach { invalid ->
            values = invalid
            assertThrows(IllegalStateException::class.java) { service.railSpeedLimits(worldId, topology, rails) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            service.railSpeedLimits(worldId, topology, listOf(UUID.randomUUID()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            service.railSpeedLimits(worldId, buildTestTopology(7, emptyList(), emptyMap()), rails)
        }
        service.removeWorld(worldId)
        assertThrows(IllegalStateException::class.java) { service.railSpeedLimits(worldId, topology, rails) }
        service.registerWorld(worldId, { topology }) { false }
        assertThrows(IllegalStateException::class.java) { service.railSpeedLimits(worldId, topology, rails) }
        service.close()
        assertThrows(IllegalStateException::class.java) { service.railSpeedLimits(worldId, topology, rails) }
    }

    @Test
    fun `static speed capture refuses topology replacement and world reload during provider read`() {
        val worldId = UUID.randomUUID()
        val railId = UUID.randomUUID()
        val rails = listOf(TestTopologyRail(railId, 100.0))
        val original = buildTestTopology(7, rails, emptyMap())
        val replacement = buildTestTopology(8, listOf(TestTopologyRail(railId, 100.0)), emptyMap())
        for (reload in listOf(false, true)) {
            val service = DefaultInfrastructureResourceService()
            var topology = original
            service.registerWorld(worldId, { topology }, railSpeedLimits = { _, _ ->
                if (reload) {
                    service.removeWorld(worldId)
                    service.registerWorld(worldId, { topology }, railSpeedLimits = { _, requested -> requested.associateWith { 20.0 } }) { false }
                } else topology = replacement
                mapOf(railId to 20.0)
            }) { false }
            assertThrows(IllegalStateException::class.java) { service.railSpeedLimits(worldId, original, listOf(railId)) }
            service.close()
        }
    }

    @Test
    fun `requested direction subset is immutable exact-root bound and taken in one store read`() {
        val service = DefaultInfrastructureResourceService()
        val worldId = UUID.randomUUID()
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val topology = buildTestTopology(7L, listOf(TestTopologyRail(first, 100.0), TestTopologyRail(second, 100.0)), emptyMap())
        val store = work.art1st.javarailways.basic.rail.OperatingDirectionStore()
        var calls = 0
        service.registerWorld(worldId, { topology }, operatingDirections = { root, rails ->
            calls++
            store.snapshot(root, rails)
        }) { false }
        store.put(first, ViewDirection.AB)
        val captured = service.operatingDirections(worldId, topology, listOf(first, second))
        assertEquals(1, calls)
        assertSame(topology, captured.topology)
        assertEquals(mapOf(first to ViewDirection.AB, second to null), captured.directions)
        store.put(first, ViewDirection.BA)
        assertEquals(ViewDirection.AB, captured.directions[first])
        assertThrows(UnsupportedOperationException::class.java) { (captured.directions as MutableMap).clear() }
        assertThrows(IllegalArgumentException::class.java) { service.operatingDirections(worldId, topology, listOf(UUID.randomUUID())) }
        assertThrows(IllegalArgumentException::class.java) { service.operatingDirections(worldId,
            buildTestTopology(7L, emptyList(), emptyMap()), listOf(first)) }
        service.removeWorld(worldId)
        assertThrows(IllegalStateException::class.java) { service.operatingDirections(worldId, topology, listOf(first)) }
        service.close()
    }

    @Test
    fun `direction read rejects incomplete extra and wrong-root provider subsets`() {
        val service = DefaultInfrastructureResourceService()
        val worldId = UUID.randomUUID()
        val rails = List(3) { UUID.randomUUID() }
        val topologyRails = rails.map { TestTopologyRail(it, 100.0) }
        val topology = buildTestTopology(7L, topologyRails, emptyMap())
        val otherRoot = buildTestTopology(7L, topologyRails, emptyMap())
        val requested = rails.take(2)
        val subsets = listOf(
            work.art1st.javarailways.basic.api.route.RailOperatingDirections(topology, mapOf(rails[0] to null)),
            work.art1st.javarailways.basic.api.route.RailOperatingDirections(topology, rails.associateWith { null }),
            work.art1st.javarailways.basic.api.route.RailOperatingDirections(otherRoot, requested.associateWith { null })
        )
        var subset = subsets.first()
        service.registerWorld(worldId, { topology }, operatingDirections = { _, _ -> subset }) { false }
        try {
            for (invalid in subsets) {
                subset = invalid
                assertThrows(IllegalStateException::class.java) {
                    service.operatingDirections(worldId, topology, requested)
                }
                assertSame(topology, service.currentTopology(worldId))
            }
        } finally {
            service.close()
        }
    }

    @Test
    fun `direction read rejects topology replacement during its store read`() {
        val service = DefaultInfrastructureResourceService()
        val worldId = UUID.randomUUID()
        val railId = UUID.randomUUID()
        val rails = listOf(TestTopologyRail(railId, 100.0))
        val topology = buildTestTopology(7L, rails, emptyMap())
        val replacement = buildTestTopology(8L, listOf(TestTopologyRail(railId, 100.0)), emptyMap())
        var current = topology
        val store = work.art1st.javarailways.basic.rail.OperatingDirectionStore()
        service.registerWorld(worldId, { current }, operatingDirections = { root, requested ->
            store.snapshot(root, requested).also { current = replacement }
        }) { false }
        try {
            assertThrows(IllegalStateException::class.java) {
                service.operatingDirections(worldId, topology, listOf(railId))
            }
            assertSame(replacement, service.currentTopology(worldId))
        } finally {
            service.close()
        }
    }

    @Test
    fun `direction read rejects a reloaded world binding even with the same topology root`() {
        val service = DefaultInfrastructureResourceService()
        val worldId = UUID.randomUUID()
        val railId = UUID.randomUUID()
        val topology = buildTestTopology(7L, listOf(TestTopologyRail(railId, 100.0)), emptyMap())
        val store = work.art1st.javarailways.basic.rail.OperatingDirectionStore()
        service.registerWorld(worldId, { topology }, operatingDirections = { root, requested ->
            store.snapshot(root, requested).also {
                service.removeWorld(worldId)
                service.registerWorld(worldId, { topology }, operatingDirections = store::snapshot) { false }
            }
        }) { false }
        try {
            assertThrows(IllegalStateException::class.java) {
                service.operatingDirections(worldId, topology, listOf(railId))
            }
            assertSame(topology, service.currentTopology(worldId))
        } finally {
            service.close()
        }
    }

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
            InfrastructureDependencyMutationStatus.APPLIED,
            service.setInfrastructureDependencies(
                planPlugin,
                worldId,
                InfrastructureDependencyRequest(
                    planOwner,
                    InfrastructureDependencies(emptySet(), emptySet(), setOf(resource))
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
        assertEquals(TopologyEditBlockerKind.INFRASTRUCTURE_DEPENDENCY, blocker.kind)
        assertEquals(planOwner, blocker.owner)
        assertTrue(blocker.overlap.railIds.isEmpty())
        assertTrue(blocker.overlap.topologyNodeKeys.isEmpty())
        assertEquals(setOf(resource), blocker.overlap.resourceIds)
        assertEquals(listOf(planOwner), service.infrastructureDependencies(worldId).map { it.owner })

        assertEquals(
            InfrastructureDependencyMutationStatus.REMOVED,
            service.removeInfrastructureDependencies(planPlugin, worldId, planOwner).status
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
        var enabled = true
        val requester = plugin("operator") { enabled }
        val world = createTestWorld()
        try {
            val stem = world.createRailForTest(straightCurve(-10.0, 0.0, 0.0))
            val first = world.createRailForTest(straightCurve(0.0, 10.0, -5.0))
            val second = world.createRailForTest(straightCurve(0.0, 10.0, 5.0))
            IsolatedSimulationThreadMarker.run { CoreRail.connect(stem, first) }
            IsolatedSimulationThreadMarker.run { CoreRail.connect(stem, second) }
            world.onOwnerForTest { stem.viewAB.selectNextRail(second.viewAB) }
            service.registerWorld(worldId, { world.topologyState }, registry = world.infrastructureCoordination) { resource ->
                resource is RailIntervalResource && world.getRail(resource.railId) != null ||
                    resource is StableInfrastructureResource && service.stableResourceExists(worldId, resource)
            }
            service.attachInfrastructureRuntime(
                worldId,
                world,
                latestObservation = { null }
            ) { reason, failure ->
                throw IllegalStateException("unexpected stall: $reason", failure)
            }

            val pending = service.selectTurnout(
                requester,
                worldId,
                ManualTurnoutSelectionRequest(
                    world.topologyRevision,
                    RailEndpointRef(stem.id, RailEndpoint.B),
                    RailEndpointRef(first.id, RailEndpoint.A)
                )
            )

            assertFalse(pending.isDone)
            assertSame(second.viewAB, stem.viewAB.nextActiveRail)
            world.onOwnerForTest { world.physicsCoordinator.drainBetweenFramesOperations() }
            val result = pending.get(5, TimeUnit.SECONDS)
            assertEquals(ManualTurnoutSelectionStatus.SELECTED, result.status)
            assertSame(first.viewAB, stem.viewAB.nextActiveRail)
            assertSame(stem.viewBA, first.viewBA.nextActiveRail)
            assertEquals(
                RailEndpointRef(first.id, RailEndpoint.A),
                world.latestWorldFrame().activeContinuations.selectedContinuation(RailEndpointRef(stem.id, RailEndpoint.B))
            )
            assertTrue(service.liveClaims(worldId).isEmpty())

            val reverseRequest = ManualTurnoutSelectionRequest(
                world.topologyRevision,
                RailEndpointRef(stem.id, RailEndpoint.B),
                RailEndpointRef(second.id, RailEndpoint.A)
            )
            val disabledRequest = service.selectTurnout(requester, worldId, reverseRequest)
            enabled = false
            world.onOwnerForTest { world.physicsCoordinator.drainBetweenFramesOperations() }
            assertEquals(ManualTurnoutSelectionStatus.WORLD_UNAVAILABLE, disabledRequest.get(5, TimeUnit.SECONDS).status)
            assertSame(first.viewAB, stem.viewAB.nextActiveRail)
            assertTrue(service.liveClaims(worldId).isEmpty())

            enabled = true
            val retiredRequest = service.selectTurnout(requester, worldId, reverseRequest)
            service.removeWorld(worldId)
            service.registerWorld(worldId, { world.topologyState }, registry = world.infrastructureCoordination) { resource ->
                resource is RailIntervalResource && world.getRail(resource.railId) != null ||
                    resource is StableInfrastructureResource && service.stableResourceExists(worldId, resource)
            }
            service.attachInfrastructureRuntime(worldId, world, { null }) { reason, failure ->
                throw IllegalStateException(reason, failure)
            }
            world.onOwnerForTest { world.physicsCoordinator.drainBetweenFramesOperations() }
            assertEquals(ManualTurnoutSelectionStatus.WORLD_UNAVAILABLE, retiredRequest.get(5, TimeUnit.SECONDS).status)
            assertSame(first.viewAB, stem.viewAB.nextActiveRail)
            assertTrue(service.liveClaims(worldId).isEmpty())
        } finally {
            service.close()
            world.physicsCoordinator.close()
            (world.tickManager as? AutoCloseable)?.close()
        }
    }

    @Test
    fun `external completion cancellation and timeout cannot control queued turnout selection`() {
        val service = DefaultInfrastructureResourceService()
        val worldId = UUID.randomUUID()
        val requester = plugin("operator")
        val world = createTestWorld()
        val changeWaitingResult: List<(CompletableFuture<ManualTurnoutSelectionResult>) -> Unit> = listOf(
            { future -> future.complete(ManualTurnoutSelectionResult(ManualTurnoutSelectionStatus.SELECTED)) },
            { future -> future.completeExceptionally(IllegalStateException("caller stopped waiting")) },
            { future -> future.cancel(false) },
            { future ->
                future.orTimeout(0, TimeUnit.MILLISECONDS)
                assertThrows(ExecutionException::class.java) { future.get(5, TimeUnit.SECONDS) }
            }
        )
        try {
            val stem = world.createRailForTest(straightCurve(-10.0, 0.0, 0.0))
            val first = world.createRailForTest(straightCurve(0.0, 10.0, -5.0))
            val second = world.createRailForTest(straightCurve(0.0, 10.0, 5.0))
            IsolatedSimulationThreadMarker.run { CoreRail.connect(stem, first) }
            IsolatedSimulationThreadMarker.run { CoreRail.connect(stem, second) }
            world.onOwnerForTest { stem.viewAB.selectNextRail(second.viewAB) }
            service.registerWorld(worldId, { world.topologyState }, registry = world.infrastructureCoordination) { resource ->
                resource is RailIntervalResource && world.getRail(resource.railId) != null ||
                    resource is StableInfrastructureResource && service.stableResourceExists(worldId, resource)
            }
            service.attachInfrastructureRuntime(worldId, world, { null }) { reason, failure ->
                throw IllegalStateException(reason, failure)
            }
            changeWaitingResult.forEachIndexed { index, changeResult ->
                val target = if (index % 2 == 0) first else second
                val previous = if (index % 2 == 0) second else first
                val pending = service.selectTurnout(requester, worldId, ManualTurnoutSelectionRequest(
                    world.topologyRevision,
                    RailEndpointRef(stem.id, RailEndpoint.B),
                    RailEndpointRef(target.id, RailEndpoint.A)
                ))
                assertFalse(pending.isDone)
                changeResult(pending)
                assertTrue(pending.isDone)
                assertSame(previous.viewAB, stem.viewAB.nextActiveRail,
                    "modifying the waiting result must not execute selection early")
                world.onOwnerForTest { world.physicsCoordinator.drainBetweenFramesOperations() }
                assertSame(target.viewAB, stem.viewAB.nextActiveRail,
                    "modifying the waiting result must not skip selection")
                assertEquals(RailEndpointRef(target.id, RailEndpoint.A),
                    world.latestWorldFrame().activeContinuations.selectedContinuation(RailEndpointRef(stem.id, RailEndpoint.B)))
            }
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
        service.registerWorld(worldId, { topology }) { resource ->
            resource is RailIntervalResource && resource.railId in setOf(firstRail, secondRail) ||
                resource is StableInfrastructureResource && service.stableResourceExists(worldId, resource)
        }
        service.attachInfrastructureRuntime(
            worldId, TestWorld(topology), { observation }
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
        val signalSources = DefaultSignalEpochSourceService()
        val service = DefaultInfrastructureResourceService(signalSources)
        val worldId = UUID.randomUUID()
        val railId = UUID.randomUUID()
        val requester = plugin("signal")
        val topology = buildTestTopology(7L, listOf(TestTopologyRail(railId, 100.0)), emptyMap())
        var observation = routeObservation(worldId, topology.revision, 1L, railId, 0.0, 1.0)
        service.registerWorld(worldId, { topology }) { resource ->
            resource is RailIntervalResource && resource.railId == railId
        }
        service.attachInfrastructureRuntime(
            worldId, TestWorld(topology), { observation }
        ) { reason, failure ->
            throw IllegalStateException("unexpected stall: $reason", failure)
        }
        registerSignalWorld(signalSources, worldId)
        signalSources.replaceRoutePlanningSources(
            requester,
            worldId,
            listOf(routePlanningSource(SIMPLE_SIGNALS_ROUTE_SOURCE_ID))
        )
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
        assertThrows(IllegalArgumentException::class.java) {
            signalSources.replaceRoutePlanningSources(
                plugin("other-signal"),
                worldId,
                listOf(routePlanningSource(SIMPLE_SIGNALS_ROUTE_SOURCE_ID))
            )
        }

        observation = routeObservation(worldId, topology.revision, 2L, railId, 21.0, 22.0)
        service.consumeObservation(worldId, observation)
        assertTrue(service.liveClaims(worldId).isEmpty())
        service.unregisterOwner(requester)
        signalSources.replaceRoutePlanningSources(
            plugin("replacement-signal"),
            worldId,
            listOf(routePlanningSource(SIMPLE_SIGNALS_ROUTE_SOURCE_ID))
        )
        assertFalse(admission.endGrant(grant.grantId))

        var pluginEnabled = true
        val laterDisabledPlugin = plugin("later-disabled") { pluginEnabled }
        val disabledBinding = service.routeAdmission(laterDisabledPlugin, worldId)
        pluginEnabled = false
        val formationIntent = work.art1st.javarailways.basic.api.route.FixedFormationChangeIntent.Split(
            "event", grant.grantId,
            work.art1st.javarailways.basic.api.route.FormationTrainIdentity("train", listOf("a", "b"), false), "b",
            work.art1st.javarailways.basic.api.route.FormationTrainIdentity("train", listOf("a"), false),
            work.art1st.javarailways.basic.api.route.FormationTrainIdentity("child", listOf("b"), false))
        assertEquals(RouteAdmissionFailureCode.WORLD_UNAVAILABLE,
            assertInstanceOf(work.art1st.javarailways.basic.api.route.FormationChangeResult.Rejected::class.java,
                disabledBinding.prepareFormationChange(formationIntent)).failure.code)
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
        assertEquals(RouteAdmissionFailureCode.WORLD_UNAVAILABLE,
            assertInstanceOf(work.art1st.javarailways.basic.api.route.FormationChangeResult.Rejected::class.java,
                admission.prepareFormationChange(formationIntent)).failure.code)
        service.registerWorld(worldId, { topology }) { false }
        service.attachInfrastructureRuntime(
            worldId, TestWorld(topology), { observation }
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
        service.registerWorld(worldId, { topology }) { resource ->
            resource is RailIntervalResource && resource.railId == railId
        }
        service.attachInfrastructureRuntime(
            worldId, TestWorld(topology), { observation }
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
        service.registerWorld(worldId, { topology }) { resource ->
            resource is RailIntervalResource && resource.railId == railId
        }
        service.attachInfrastructureRuntime(
            worldId, TestWorld(topology), { observation }
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
    fun `SimpleSignals provider cannot unregister while ATO holds its resource and forced loss is world local`() {
        val signalSources = DefaultSignalEpochSourceService()
        val service = DefaultInfrastructureResourceService(signalSources)
        val worldId = UUID.randomUUID()
        val idleWorldId = UUID.randomUUID()
        val railId = UUID.randomUUID()
        var signalPluginEnabled = true
        val signalPlugin = plugin("SimpleSignals") { signalPluginEnabled }
        val requester = plugin("ato")
        val block = StableInfrastructureResource(SIMPLE_SIGNALS_FIXED_BLOCK_TYPE, "main")
        val topology = buildTestTopology(7L, listOf(TestTopologyRail(railId, 100.0)), emptyMap())
        val observation = routeObservation(worldId, topology.revision, 1L, railId, 0.0, 1.0)
        val stalls = mutableListOf<String>()
        var contributionCalls = 0
        val registration = service.registerResourceProvider(signalPlugin, block.typeId, object : InfrastructureResourceProvider {
            override fun exists(candidateWorld: UUID, resourceId: String) =
                candidateWorld == worldId && resourceId == block.resourceId

            override fun validateRouteContribution(
                actualContributor: Plugin,
                candidateWorld: UUID,
                resourceId: String,
                descriptor: ResourcePreparationDescriptor?
            ): RouteAdmissionFailureCode? {
                assertSame(signalPlugin, actualContributor)
                return null
            }
        }, emptyList())
        val contributorRegistration = service.registerRouteResourceContributor(signalPlugin) { candidateWorld, _ ->
            contributionCalls++
            if (candidateWorld == worldId) {
                listOf(ContributedRouteResource(block, RouteBoundary(20.0)))
            } else {
                emptyList()
            }
        }
        service.registerWorld(worldId, { topology }) { resource ->
            resource is RailIntervalResource && resource.railId == railId ||
                resource is StableInfrastructureResource && service.stableResourceExists(worldId, resource)
        }
        service.attachInfrastructureRuntime(
            worldId, TestWorld(topology), { observation }
        ) { reason, _ ->
            stalls += reason
        }
        registerSignalWorld(signalSources, worldId, stallWorld = { reason, _ -> stalls += reason })
        registerSignalWorld(signalSources, idleWorldId, stallWorld = { reason, _ ->
            stalls += "idle-world: $reason"
        })
        signalSources.replaceRoutePlanningSources(
            signalPlugin,
            worldId,
            listOf(routePlanningSource(SIMPLE_SIGNALS_ROUTE_SOURCE_ID))
        )
        signalSources.replaceRoutePlanningSources(
            signalPlugin,
            idleWorldId,
            listOf(routePlanningSource(SIMPLE_SIGNALS_ROUTE_SOURCE_ID))
        )
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
            service.unregisterOwner(signalPlugin)
        }
        val registrationActive = assertThrows(IllegalStateException::class.java) {
            registration.close()
        }
        assertTrue(registrationActive.message.orEmpty().contains(block.typeId))
        val active = assertThrows(IllegalStateException::class.java) {
            service.unregisterOwner(signalPlugin)
        }
        assertTrue(active.message.orEmpty().contains("types=${block.typeId}"))
        assertEquals(1, service.liveClaims(worldId).size)

        signalPluginEnabled = false
        service.forcedDisableOwner(signalPlugin)
        assertEquals(1, service.liveClaims(worldId).size)
        assertEquals(1, stalls.size)
        assertTrue(stalls.single().contains("SimpleSignals"))
        assertTrue(stalls.single().contains(block.typeId))
        val replacement = plugin("replacement-signals")
        signalSources.replaceRoutePlanningSources(
            replacement,
            worldId,
            listOf(routePlanningSource(SIMPLE_SIGNALS_ROUTE_SOURCE_ID))
        )
        signalSources.replaceRoutePlanningSources(
            replacement,
            idleWorldId,
            listOf(routePlanningSource(SIMPLE_SIGNALS_ROUTE_SOURCE_ID))
        )
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
        val changes = arrayListOf<List<ActiveRouteAdmissionUpdate>>()
        val epochRuntime = SignalEpochRuntime(worldId.toString(), TrainRestrictionPublisher {
            TrainRestrictionPublicationResult(TrainRestrictionPublicationStatus.ACCEPTED, it.revision)
        }).also { epoch ->
            epoch.replaceSources(listOf(SignalEpochSource("test:admission-observation", emptyList(), emptyList(), listOf(railId)) {
                changes += it.admissionUpdates
                SignalEpochResult.empty()
            }))
        }
        epochRuntime.process(routeFrame(topology.revision, 1L, railId, 0.0, 1.0))
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
        service.registerWorld(worldId, { topology }) { claimed ->
            claimed is RailIntervalResource && claimed.railId == railId ||
                claimed is StableInfrastructureResource && service.stableResourceExists(worldId, claimed)
        }
        service.attachInfrastructureRuntime(
            worldId, TestWorld(topology), { observation },
            publishAdmissionUpdate = epochRuntime::publishAdmissionUpdate
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
        val preparing = service.activeRouteAdmissions(worldId).single()
        epochRuntime.process(routeFrame(topology.revision, 2L, railId, 0.0, 1.0))
        assertEquals(preparing, changes.last().single().current)
        assertEquals(intent.owner, preparing.owner)
        assertEquals(intent.trainId, preparing.trainId)
        assertEquals(intent.route, preparing.route)
        assertEquals(ActiveRouteAdmissionState.PREPARING, preparing.state)
        assertEquals(intent.desiredAuthority, preparing.requestedAuthority)
        assertEquals(null, preparing.committedAuthority)
        ticket.state = ResourcePreparationState.READY

        assertInstanceOf(RouteAdmissionResult.Granted::class.java, admission.progress(pending.handle))
        assertEquals(1, contributionCalls)
        val granted = service.activeRouteAdmissions(worldId).single()
        epochRuntime.process(routeFrame(topology.revision, 3L, railId, 0.0, 1.0))
        assertEquals(granted, changes.last().single().current)
        epochRuntime.process(routeFrame(topology.revision, 4L, railId, 0.0, 1.0))
        assertEquals(3, changes.size) // baseline, request, ready; no polling on unchanged epoch
        assertEquals(intent.route, granted.route)
        assertEquals(ActiveRouteAdmissionState.GRANTED, granted.state)
        assertEquals(intent.desiredAuthority, granted.requestedAuthority)
        assertEquals(intent.desiredAuthority, granted.committedAuthority)

        service.removeWorld(worldId)
        assertThrows(IllegalStateException::class.java) {
            service.activeRouteAdmissions(worldId)
        }
        service.close()
    }

    @Test
    fun `world route admission is attached once consumes the published observation and closes with world`() {
        val service = DefaultInfrastructureResourceService()
        val worldId = UUID.randomUUID()
        val topology = buildTestTopology(7L, emptyList(), emptyMap())
        val world = TestWorld(topology)
        val observation = RailwayObservation.fromCore(
            worldId.toString(),
            RailwayWorldFrameSnapshot.create(
                1L,
                topology.revision,
                emptyList(),
                ActiveRailContinuationsSnapshot.empty(topology.revision)
            )
        )
        service.registerWorld(worldId, { topology }) { false }
        service.attachInfrastructureRuntime(
            worldId, world, { observation }
        ) { reason, failure ->
            throw IllegalStateException("unexpected stall: $reason", failure)
        }

        service.consumeObservation(worldId, observation)
        assertThrows(IllegalStateException::class.java) {
            service.attachInfrastructureRuntime(
                worldId, world, { observation }
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
    fun `old provider handle cannot remove registration after owner cleanup and same object registration`() {
        val service = DefaultInfrastructureResourceService()
        val worldId = UUID.randomUUID()
        val owner = plugin("resource-owner")
        val resource = StableInfrastructureResource("example.signal:block", "home")
        val provider = rejectingRouteProvider { _, _ -> true }
        try {
            registerWorld(service, worldId)
            val oldRegistration = service.registerResourceProvider(owner, resource.typeId, provider, emptyList())
            assertTrue(service.stableResourceExists(worldId, resource))
            service.unregisterOwner(owner)
            assertFalse(service.stableResourceExists(worldId, resource))
            val replacement = service.registerResourceProvider(owner, resource.typeId, provider, emptyList())
            oldRegistration.close()
            oldRegistration.close()
            assertTrue(service.stableResourceExists(worldId, resource))
            replacement.close()
            assertFalse(service.stableResourceExists(worldId, resource))
        } finally {
            service.close()
        }
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
            InfrastructureDependencyMutationStatus.APPLIED,
            service.setInfrastructureDependencies(
                plugin, worldId, dependencies("example.signal:plan/main", "example.signal:signal", "home")
            ).status
        )
        assertTrue(providerRanInSimulationContext)
        assertEquals(
            InfrastructureDependencyMutationStatus.INVALID_REFERENCE,
            service.setInfrastructureDependencies(
                plugin, worldId, dependencies("example.signal:plan/missing", "example.signal:signal", "missing")
            ).status
        )
        assertEquals(
            InfrastructureDependencyMutationStatus.INVALID_REFERENCE,
            service.setInfrastructureDependencies(
                plugin, worldId, dependencies("example.signal:plan/unknown", "other.plugin:signal", "home")
            ).status
        )

        assertThrows(IllegalStateException::class.java) { provider.close() }
        assertEquals(
            InfrastructureDependencyMutationStatus.REMOVED,
            service.removeInfrastructureDependencies(
                plugin,
                worldId,
                InfrastructureOwner("example.signal:plan/main", 1L)
            ).status
        )
        provider.close()
        provider.close()
        assertEquals(
            InfrastructureDependencyMutationStatus.INVALID_REFERENCE,
            service.setInfrastructureDependencies(
                plugin, worldId, dependencies("example.signal:plan/after-close", "example.signal:signal", "home")
            ).status
        )
    }

    @Test
    fun `plugin cleanup requires explicit unapply and removes disposable ownership by identity`() {
        val signalSources = DefaultSignalEpochSourceService()
        val service = DefaultInfrastructureResourceService(signalSources)
        val worldId = UUID.randomUUID()
        val first = plugin("same-name")
        val second = plugin("same-name")
        service.registerResourceProvider(
            first, SIMPLE_SIGNALS_FIXED_BLOCK_TYPE, rejectingRouteProvider { _, id -> id == "home" }, emptyList()
        )
        registerWorld(service, worldId)
        registerSignalWorld(signalSources, worldId)
        signalSources.replaceRoutePlanningSources(
            first,
            worldId,
            listOf(routePlanningSource(SIMPLE_SIGNALS_ROUTE_SOURCE_ID))
        )
        service.setInfrastructureDependencies(
            first, worldId, dependencies("simplesignals:plan/main", SIMPLE_SIGNALS_FIXED_BLOCK_TYPE, "home")
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
        assertEquals(1, service.infrastructureDependencies(worldId).size)
        assertThrows(IllegalStateException::class.java) { service.unregisterOwner(first) }
        assertEquals(1, service.infrastructureDependencies(worldId).size)
        assertThrows(IllegalArgumentException::class.java) {
            signalSources.replaceRoutePlanningSources(
                second,
                worldId,
                listOf(routePlanningSource(SIMPLE_SIGNALS_ROUTE_SOURCE_ID))
            )
        }
        assertEquals(
            InfrastructureDependencyMutationStatus.REMOVED,
            service.removeInfrastructureDependencies(
                first,
                worldId,
                InfrastructureOwner("simplesignals:plan/main", 1L)
            ).status
        )
        service.unregisterOwner(first)
        assertTrue(service.infrastructureDependencies(worldId).isEmpty())
        signalSources.replaceRoutePlanningSources(
            second,
            worldId,
            listOf(routePlanningSource(SIMPLE_SIGNALS_ROUTE_SOURCE_ID))
        )
        assertEquals(
            InfrastructureDependencyMutationStatus.INVALID_REFERENCE,
            service.setInfrastructureDependencies(
                second, worldId, dependencies("simplesignals:plan/new", SIMPLE_SIGNALS_FIXED_BLOCK_TYPE, "home")
            ).status
        )
        heldEdit.permit().orElseThrow().close()
    }

    @Test
    fun `forced plugin disable retains applied dependencies and stalls its world`() {
        val signalSources = DefaultSignalEpochSourceService()
        val service = DefaultInfrastructureResourceService(signalSources)
        val worldId = UUID.randomUUID()
        val idleWorldId = UUID.randomUUID()
        val railId = UUID.randomUUID()
        var pluginEnabled = true
        val plugin = plugin("SimpleSignals") { pluginEnabled }
        val requester = plugin("ato")
        val topology = buildTestTopology(7L, listOf(TestTopologyRail(railId, 100.0)), emptyMap())
        val observation = routeObservation(worldId, topology.revision, 1L, railId, 0.0, 1.0)
        val stalls = mutableListOf<String>()
        service.registerResourceProvider(
            plugin,
            SIMPLE_SIGNALS_FIXED_BLOCK_TYPE,
            rejectingRouteProvider { _, id -> id == "home" },
            emptyList()
        )
        service.registerWorld(worldId, { topology }) { resource ->
            resource is RailIntervalResource && resource.railId == railId ||
                resource is StableInfrastructureResource && service.stableResourceExists(worldId, resource)
        }
        service.attachInfrastructureRuntime(
            worldId, TestWorld(topology), { observation }
        ) { reason, _ -> stalls += reason }
        registerSignalWorld(signalSources, worldId, stallWorld = { reason, _ -> stalls += reason })
        registerSignalWorld(signalSources, idleWorldId, stallWorld = { reason, _ ->
            stalls += "idle-world: $reason"
        })
        signalSources.replaceRoutePlanningSources(
            plugin,
            worldId,
            listOf(routePlanningSource(SIMPLE_SIGNALS_ROUTE_SOURCE_ID))
        )
        signalSources.replaceRoutePlanningSources(
            plugin,
            idleWorldId,
            listOf(routePlanningSource(SIMPLE_SIGNALS_ROUTE_SOURCE_ID))
        )
        assertEquals(
            InfrastructureDependencyMutationStatus.APPLIED,
            service.setInfrastructureDependencies(
                plugin, worldId,
                dependencies("simplesignals:plan/main", SIMPLE_SIGNALS_FIXED_BLOCK_TYPE, "home")
            ).status
        )

        pluginEnabled = false
        service.forcedDisableOwner(plugin)

        assertEquals(1, service.infrastructureDependencies(worldId).size)
        assertEquals(1, stalls.size)
        assertTrue(stalls.single().contains("simplesignals:plan/main@1"))
        val replacement = plugin("replacement-signals")
        signalSources.replaceRoutePlanningSources(
            replacement,
            worldId,
            listOf(routePlanningSource(SIMPLE_SIGNALS_ROUTE_SOURCE_ID))
        )
        signalSources.replaceRoutePlanningSources(
            replacement,
            idleWorldId,
            listOf(routePlanningSource(SIMPLE_SIGNALS_ROUTE_SOURCE_ID))
        )
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
            InfrastructureDependencyMutationStatus.WORLD_UNAVAILABLE,
            service.setInfrastructureDependencies(
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
    fun `unrelated topology revision does not expire stable infrastructure dependencies`() {
        val service = DefaultInfrastructureResourceService()
        val worldId = UUID.randomUUID()
        val plugin = plugin("signal")
        var currentTopology = buildTestTopology(7L, emptyList(), emptyMap())
        service.registerResourceProvider(
            plugin, "example.signal:signal", rejectingRouteProvider { _, _ -> true }, emptyList()
        )
        service.registerWorld(worldId, { currentTopology }) { resource ->
            resource is StableInfrastructureResource && service.stableResourceExists(worldId, resource)
        }

        currentTopology = buildTestTopology(8L, emptyList(), emptyMap())
        assertSame(currentTopology, service.currentTopology(worldId))
        assertEquals(
            InfrastructureDependencyMutationStatus.APPLIED,
            service.setInfrastructureDependencies(
                plugin, worldId, dependencies("example.signal:plan/main", "example.signal:signal", "home")
            ).status
        )
        assertEquals(1, service.infrastructureDependencies(worldId).size)
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

        val result = service.setInfrastructureDependencies(
            plugin,
            worldId,
            dependencies("example.signal:plan/main", "example.signal:signal", "home")
        )

        assertEquals(InfrastructureDependencyMutationStatus.INVALID_REFERENCE, result.status)
        assertTrue(result.detail.contains("provider offline"))
        assertTrue(service.infrastructureDependencies(worldId).isEmpty())
    }

    private fun DefaultInfrastructureResourceService.registerWorld(
        worldId: UUID,
        currentTopology: () -> TopologyState,
        railExists: (UUID) -> Boolean = { false },
        topologyNodeExists: (work.art1st.javarailways.core.api.physics.infrastructure.StableTopologyNodeKey) -> Boolean = { false },
        operatingDirections: ((TopologyState, Collection<UUID>) -> work.art1st.javarailways.basic.api.route.RailOperatingDirections)? = null,
        observeTopologyChanges: ((RailTopologyChangeListener) -> AutoCloseable)? = null,
        railSpeedLimits: ((TopologyState, Collection<UUID>) -> Map<UUID, Double?>)? = null,
        resourceExists: (InfrastructureResource) -> Boolean
    ) = registerWorld(worldId, currentTopology, operatingDirections, observeTopologyChanges, railSpeedLimits,
        work.art1st.javarailways.core.physics.infrastructure.InfrastructureCoordinationRegistry(
            { currentTopology().revision }, { false }, railExists, topologyNodeExists), resourceExists)

    private fun registerWorld(service: DefaultInfrastructureResourceService, worldId: UUID) {
        val topology = buildTestTopology(7L, emptyList(), emptyMap())
        service.registerWorld(worldId, { topology }) { resource: InfrastructureResource ->
            resource is StableInfrastructureResource && service.stableResourceExists(worldId, resource)
        }
    }

    private fun registerSignalWorld(
        service: DefaultSignalEpochSourceService,
        worldId: UUID,
        activeRestrictionStreams: (Set<String>) -> List<String> = { emptyList() },
        stallWorld: (String, Exception) -> Unit = { reason, failure ->
            throw IllegalStateException(reason, failure)
        }
    ) {
        val publisher = TrainRestrictionPublisher { snapshot ->
            TrainRestrictionPublicationResult(
                TrainRestrictionPublicationStatus.ACCEPTED,
                snapshot.revision
            )
        }
        service.registerWorld(
            worldId,
            SignalEpochRuntime(worldId.toString(), publisher),
            InspectionRuntime(publisher) { _, _, _ -> },
            activeRestrictionStreams,
            RoutePlanningRestrictionRuntime(),
            stallWorld = stallWorld
        )
    }

    private fun routePlanningSource(sourceId: String) = RoutePlanningRestrictionBinding(sourceId) {
        RoutePlanningRestrictions.unrestricted(it)
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

    private fun dependencies(ownerId: String, typeId: String, resourceId: String) = InfrastructureDependencyRequest(
        InfrastructureOwner(ownerId, 1L),
        InfrastructureDependencies(emptySet(), emptySet(), setOf(StableInfrastructureResource(typeId, resourceId)))
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
    ): RailwayObservation = RailwayObservation.fromCore(worldId.toString(),
        routeFrame(topologyRevision, epoch, railId, tail, head))

    private fun routeFrame(
        topologyRevision: Long,
        epoch: Long,
        railId: UUID,
        tail: Double,
        head: Double
    ): RailwayWorldFrameSnapshot {
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
        return RailwayWorldFrameSnapshot.create(
                epoch,
                topologyRevision,
                listOf(train),
                ActiveRailContinuationsSnapshot.empty(topologyRevision)
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
        override val executionMode = work.art1st.javarailways.core.api.physics.WorldExecutionMode.LIVE
        override val maximumPhysicsDeltaSeconds: Double = 0.2
        override fun registerTopologyChangeListener(listener: RailTopologyChangeListener): AutoCloseable = AutoCloseable {}
        override val tickManager: TickManager get() = error("tick manager is not used by this test")
        override val allTrains: Collection<Train> = emptyList()

        override fun getTrain(name: String): Train? = null
        override fun getRail(id: UUID): Rail? = null
        override fun removeRail(rail: Rail): java.util.concurrent.CompletionStage<Void> =
            java.util.concurrent.CompletableFuture.completedFuture(null)
        override fun <T : Any> getExtensionDataOrNull(plugin: CorePlugin): T? = null
        override fun <T : Any> requireExtensionData(plugin: CorePlugin): T = error("no extensions")
        override fun <T : Any> registerExtensionData(plugin: CorePlugin, data: T) = Unit
        override fun forEachExtensionData(action: (Any) -> Unit) = Unit
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

    private companion object {
        private const val SIMPLE_SIGNALS_FIXED_BLOCK_TYPE = "simplesignals:fixed-block"
        private const val SIMPLE_SIGNALS_ROUTE_SOURCE_ID = "simplesignals:fixed-protection-routes"
    }
}
