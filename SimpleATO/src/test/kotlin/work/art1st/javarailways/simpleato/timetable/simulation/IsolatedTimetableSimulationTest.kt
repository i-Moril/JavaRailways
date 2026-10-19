package work.art1st.javarailways.simpleato.timetable.simulation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertThrows
import work.art1st.javarailways.basic.safety.SpeedSafetyMonitor
import work.art1st.javarailways.basic.rail.CurveSafetyMetadata
import work.art1st.javarailways.basic.rail.RailInfrastructureMetadata
import work.art1st.javarailways.basic.rail.SpeedLimitMetadata
import work.art1st.javarailways.basic.rail.SpeedLimitEntry
import work.art1st.javarailways.basic.rail.SpeedLimitType
import work.art1st.javarailways.basic.rail.OperationalSpeedLimitSignal
import work.art1st.javarailways.basic.rail.infrastructureMetadata
import work.art1st.javarailways.core.physics.train.setControllerForTest
import work.art1st.javarailways.core.api.control.TrainController
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.physics.train.Train
import work.art1st.javarailways.core.util.IsolatedSimulationThreadMarker
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.BasicPlugin
import work.art1st.javarailways.basic.BasicWorldData
import work.art1st.javarailways.basic.WayPoint
import work.art1st.javarailways.simpleato.api.ato.AtoSafetyProfile
import work.art1st.javarailways.simpleato.testing.KinematicTrainActuator
import work.art1st.javarailways.basic.api.observation.RailwayOccupancyAreaDefinition
import work.art1st.javarailways.basic.api.observation.RailwayOccupancyBoundary
import work.art1st.javarailways.simpleato.controller.SimpleATO
import work.art1st.javarailways.basic.basicWorldData
import work.art1st.javarailways.basic.persistence.BasicInfrastructureSnapshotV2
import work.art1st.javarailways.simpleato.persistence.SimpleATOInfrastructureSnapshotV1
import work.art1st.javarailways.simpleato.timetable.Timetable
import work.art1st.javarailways.simpleato.timetable.TimetableAction
import work.art1st.javarailways.simpleato.timetable.TimetableAtoFactory
import work.art1st.javarailways.simpleato.timetable.TimetableDispatcher
import work.art1st.javarailways.simpleato.SimpleATOPlugin
import work.art1st.javarailways.simpleato.api.signal.SignalEpochResult
import work.art1st.javarailways.simpleato.api.signal.SignalEpochSource
import work.art1st.javarailways.simpleato.simpleAtoWorldData
import work.art1st.javarailways.simpleato.timetable.TimetableService
import work.art1st.javarailways.simpleato.timetable.TimetableStep
import work.art1st.javarailways.simpleato.timetable.TrainSelector
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.api.physics.rail.RailInterval
import work.art1st.javarailways.core.api.physics.rail.RailObjectRef
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.createTestWorld
import work.art1st.javarailways.core.physics.train.createTrainWithConfig
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner
import work.art1st.javarailways.basic.api.observation.RailwayObservation
import work.art1st.javarailways.basic.api.route.ActiveRouteAdmissionUpdate
import work.art1st.javarailways.basic.api.route.RouteAdmissionResult
import work.art1st.javarailways.basic.api.route.RouteBoundary
import work.art1st.javarailways.basic.api.route.RouteResourceIntent
import work.art1st.javarailways.basic.rail.OperatingDirectionStore
import work.art1st.javarailways.core.api.physics.WorldExecutionMode
import work.art1st.javarailways.basic.api.infrastructure.StableInfrastructureResource
import work.art1st.javarailways.basic.api.route.ContributedRouteResource
import work.art1st.javarailways.basic.api.route.ResourcePreparationDescriptor
import work.art1st.javarailways.basic.api.route.ResourcePreparationSnapshot
import work.art1st.javarailways.basic.api.route.ResourcePreparationState
import work.art1st.javarailways.basic.api.route.ResourcePreparationTicket
import work.art1st.javarailways.basic.api.route.RouteAdmissionFailure

class IsolatedTimetableSimulationTest {
    @BeforeEach
    fun initializePlugin() {
        if (BasicPlugin.instanceOrNull() == null) BasicPlugin.INSTANCE = BasicPlugin(100)
        if (SimpleATOPlugin.instanceOrNull() == null) {
            SimpleATOPlugin.INSTANCE = SimpleATOPlugin(101)
        }
    }

    @Test
    fun `clones static infrastructure without live trains and advances an isolated timetable`() {
        val fixture = fixture()
        fixture.runtime.use { runtime ->
            assertTrue(runtime.world.allTrains.none { it.id == "live-only" })
            val result = runtime.simulate(IsolatedTimetableSimulationRequest(fixture.timetable))

            assertTrue(result is IsolatedTimetableSimulationResult.Completed) { result.toString() }
            assertEquals(1, result.runs.size)
        }
    }

    @Test
    fun `same static inputs produce identical reports with parallel physics`() {
        val first = fixture()
        val second = fixture()
        val firstResult = first.runtime.use { it.simulate(IsolatedTimetableSimulationRequest(first.timetable)) }
        val secondResult = second.runtime.use { it.simulate(IsolatedTimetableSimulationRequest(second.timetable)) }

        assertEquals(firstResult::class, secondResult::class)
        assertEquals(firstResult.simulatedTicks, secondResult.simulatedTicks)
        assertEquals(
            firstResult.runs.map { it.copy(trainHeadRailId = null) },
            secondResult.runs.map { it.copy(trainHeadRailId = null) }
        )
    }

    @Test
    fun `isolated world installs its replaceable signal processor before physics starts`() {
        val fixture = fixture()
        fixture.runtime.use { runtime ->
            assertEquals(WorldExecutionMode.SIMULATION,
                runtime.world.executionMode)
            val rail = runtime.world.allRails.single()
            var callbacks = 0
            runtime.world.simpleAtoWorldData.signalEpochRuntime.replaceSources(listOf(
                SignalEpochSource(
                    sourceId = "test:isolated",
                    areas = listOf(RailwayOccupancyAreaDefinition.create(
                        areaId = RailObjectRef("test", "isolated-area"),
                        definitionRevision = 0L,
                        topologyRevision = runtime.world.topologyState.revision,
                        intervals = listOf(RailInterval(rail.id, 0.0, rail.curve.totalArcLength)),
                        boundaries = listOf(RailwayOccupancyBoundary(
                            RailObjectRef("test", "isolated-entry"),
                            RailPositionSnapshot(rail.id, ViewDirection.AB, 0.0)
                        ))
                    ))
                ) {
                    callbacks++
                    SignalEpochResult.empty()
                }
            ))

            val result = runtime.simulate(IsolatedTimetableSimulationRequest(fixture.timetable))

            assertTrue(result is IsolatedTimetableSimulationResult.Completed) { result.toString() }
            assertTrue(callbacks > 0)
        }
    }

    @Test
    fun `reversal retains its grant and completes the return journey`() {
        val fixture = fixture(steps = listOf(
            TimetableStep.Check("origin"),
            TimetableStep.At(0, TimetableAction.DestinationOptions(
                destinationId = "destination",
                stoppingOffsetMetersBefore = 0.5
            )),
            TimetableStep.At(0, TimetableAction.GoTo("destination")),
            TimetableStep.At(1_000, TimetableAction.Reverse),
            TimetableStep.At(1_000, TimetableAction.DestinationOptions(
                destinationId = "origin",
                stoppingOffsetMetersBefore = 0.5
            )),
            TimetableStep.At(1_000, TimetableAction.GoTo("origin"))
        ))
        fixture.runtime.use { runtime ->
            val result = runtime.simulate(IsolatedTimetableSimulationRequest(fixture.timetable))

            assertTrue(result is IsolatedTimetableSimulationResult.Completed) { result.toString() }
            result as IsolatedTimetableSimulationResult.Completed
            assertEquals(1, result.runs.size)
        }
    }

    @Test
    fun `stops at the first configured significant delay`() {
        val fixture = fixture(
            steps = listOf(
                TimetableStep.Check("origin"),
                TimetableStep.At(0, TimetableAction.GoTo("destination")),
                TimetableStep.At(1, TimetableAction.Stop(1))
            )
        )
        fixture.runtime.use { runtime ->
            val result = runtime.simulate(IsolatedTimetableSimulationRequest(
                fixture.timetable,
                delayThresholdTicks = 20
            ))

            assertTrue(result is IsolatedTimetableSimulationResult.DelayExceeded) { result.toString() }
            result as IsolatedTimetableSimulationResult.DelayExceeded
            assertTrue(result.observedDelayTicks >= 20)
            assertEquals("origin", result.delayedRun.currentWayPointId)
            assertEquals(2, result.delayedRun.nextStepIndex)
        }
    }

    @Test
    fun `simulation restores speed transitions and applies exact epoch overspeed through common owner phase`() {
        val fixture = fixture(speedSafety = SpeedSafetyMonitor.Config(true, 0.0, 1),
            configureSource = { source -> source.allRails.single().infrastructureMetadata = RailInfrastructureMetadata(
                speedLimits = SpeedLimitMetadata(listOf(SpeedLimitEntry(3.0, SpeedLimitType.SUBGRADE))),
                curveSafety = CurveSafetyMetadata(4.0)
            ) })
        fixture.runtime.use { simulation ->
            val world = simulation.world
            val rail = world.allRails.single()
            val train = world.allTrains.single()
            assertEquals(3.0, (rail.viewAB.staticRailObjects.single() as OperationalSpeedLimitSignal).getSpeedLimit(train))
            assertEquals(3.0, (rail.viewBA.staticRailObjects.single() as OperationalSpeedLimitSignal).getSpeedLimit(train))
            val controller = FixedSpeedController(train, world)
            train.setControllerForTest(controller)
            controller.publishSpeed(10.0)
            IsolatedSimulationThreadMarker.run {
                val ticket = world.physicsCoordinator.stepSimulation(50.0)
                ticket.await()
                assertFalse(train.isDerailed, "physics safety only queues a request")
                world.physicsCoordinator.processBetweenFrames {
                    simulation.operationalRuntime.afterPublication(ticket.epoch, {})
                }
                assertTrue(train.isDerailed, "the shared domain phase applies the real monitor request")
            }
        }
    }

    @Test
    fun `disabled captured safety never consumes another target's train with the same id`() {
        val first = fixture(speedSafety = SpeedSafetyMonitor.Config(true, 0.0, 1), configureSource = {
            it.allRails.single().infrastructureMetadata = RailInfrastructureMetadata(curveSafety = CurveSafetyMetadata(1.0))
        })
        val second = fixture(speedSafety = SpeedSafetyMonitor.Config(false, 0.0, 1), configureSource = {
            it.allRails.single().infrastructureMetadata = RailInfrastructureMetadata(curveSafety = CurveSafetyMetadata(1.0))
        })
        first.runtime.use { enabled -> second.runtime.use { disabled ->
            assertNotSame(enabled.operationalRuntime, disabled.operationalRuntime)
            assertNotSame(enabled.world.basicWorldData.safetyInterlock, disabled.world.basicWorldData.safetyInterlock)
            listOf(enabled, disabled).forEach { simulation ->
                val train = simulation.world.allTrains.single()
                val controller = FixedSpeedController(train, simulation.world)
                train.setControllerForTest(controller)
                controller.publishSpeed(10.0)
                IsolatedSimulationThreadMarker.run {
                    val ticket = simulation.world.physicsCoordinator.stepSimulation(50.0)
                    ticket.await()
                    simulation.world.physicsCoordinator.processBetweenFrames {
                        simulation.operationalRuntime.afterPublication(ticket.epoch, {})
                    }
                }
            }
            assertTrue(enabled.world.allTrains.single().isDerailed)
            assertFalse(disabled.world.allTrains.single().isDerailed)
        } }
    }

    @Test
    fun `owner phase discards an overspeed request from an older physical frame`() {
        val fixture = fixture(speedSafety = SpeedSafetyMonitor.Config(true, 0.0, 1), configureSource = {
            it.allRails.single().infrastructureMetadata = RailInfrastructureMetadata(curveSafety = CurveSafetyMetadata(1.0))
        })
        fixture.runtime.use { simulation ->
            val world = simulation.world
            val train = world.allTrains.single()
            val controller = FixedSpeedController(train, world)
            train.setControllerForTest(controller)
            controller.publishSpeed(10.0)
            IsolatedSimulationThreadMarker.run {
                world.physicsCoordinator.stepSimulation(50.0).await()
                world.physicsCoordinator.processBetweenFrames { controller.publishSpeed(0.0) }
                val current = world.physicsCoordinator.stepSimulation(50.0)
                current.await()
                world.physicsCoordinator.processBetweenFrames {
                    simulation.operationalRuntime.afterPublication(current.epoch, {})
                }
                assertFalse(train.isDerailed, "an older request must not act on the newer stopped frame")
            }
        }
    }

    @Test
    fun `partial feature install failure releases prior targets in reverse order and suppresses cleanup failure`() {
        val closed = arrayListOf<String>()
        var installedTarget: SimulationDomainTarget? = null
        val failure = IllegalStateException("install failed")
        val features = listOf(
            object : PreparedSimulationFeature {
                override val id = "test:first"
                override fun install(target: SimulationDomainTarget): AutoCloseable {
                    installedTarget = target
                    return AutoCloseable { closed += id }
                }
            },
            object : PreparedSimulationFeature {
                override val id = "test:second"
                override fun install(target: SimulationDomainTarget) = AutoCloseable {
                    closed += id
                    error("cleanup failed")
                }
            },
            object : PreparedSimulationFeature {
                override val id = "test:broken"
                override fun install(target: SimulationDomainTarget): AutoCloseable = throw failure
            }
        )
        assertEquals(failure, assertThrows(IllegalStateException::class.java) { fixture(features = features) })
        assertEquals(listOf("test:second", "test:first"), closed)
        assertEquals(listOf("cleanup failed"), failure.suppressed.map { it.message })
        assertThrows(IllegalStateException::class.java) { checkNotNull(installedTarget).world.physicsCoordinator.stepSimulation(50.0) }
    }

    @Test
    fun `target feature receives real admission updates only from its local runtime`() {
        val updates = java.util.IdentityHashMap<CoreWorld, MutableList<ActiveRouteAdmissionUpdate>>()
        val preparations = java.util.IdentityHashMap<CoreWorld, Int>()
        val feature = object : PreparedSimulationFeature {
            override val id = "test:admission-source"
            override fun install(target: SimulationDomainTarget): AutoCloseable {
                val received = arrayListOf<ActiveRouteAdmissionUpdate>()
                updates[target.world] = received
                val resource = StableInfrastructureResource("test:device", "one")
                val preparation = ResourcePreparationDescriptor("test:set", emptyMap())
                val provider = target.routeAdmission.registerProvider(resource.typeId, object : SimulationResourceProvider {
                    override fun exists(resource: StableInfrastructureResource) = resource.resourceId == "one"
                    override fun validate(contributorId: String, contribution: ContributedRouteResource):
                        RouteAdmissionFailure? {
                        assertEquals("test:contributor", contributorId)
                        assertEquals(resource, contribution.resource)
                        return null
                    }
                    override fun prepare(resource: StableInfrastructureResource,
                        descriptor: ResourcePreparationDescriptor):
                        (() -> ResourcePreparationTicket) {
                        assertEquals(preparation, descriptor)
                        return {
                            preparations[target.world] = (preparations[target.world] ?: 0) + 1
                            object : ResourcePreparationTicket {
                                override fun snapshot() = ResourcePreparationSnapshot(
                                    ResourcePreparationState.READY)
                                override fun abort() = Unit
                            }
                        }
                    }
                })
                val contributor = target.routeAdmission.registerContributor("test:contributor") {
                    listOf(ContributedRouteResource(resource,
                        RouteBoundary(100.0), preparation))
                }
                target.domain.signalEpochRuntime.replaceSources(listOf(SignalEpochSource(
                    "test:admission-source", emptyList(), emptyList(), target.world.allRails.map { it.id }
                ) { input -> received += input.admissionUpdates; SignalEpochResult.empty() }))
                return AutoCloseable {
                    target.domain.signalEpochRuntime.replaceSources(emptyList())
                    contributor.close()
                    provider.close()
                }
            }
        }
        val first = fixture(features = listOf(feature))
        val second = fixture(features = listOf(feature))
        first.runtime.use { a -> second.runtime.use { b ->
            advance(a)
            advance(b)
            IsolatedSimulationThreadMarker.run {
                a.world.physicsCoordinator.processBetweenFrames {
                    val context = checkNotNull(a.routeAdmissionHost.contextProvider.current())
                    val rail = a.world.allRails.single()
                    val route = RouteSnapshot(
                        "test:route", 1L, a.world.topologyRevision, listOf(
                            RouteSegmentSnapshot(rail.id, ViewDirection.AB, context.observation.trains.single().tail.arcLengthInAbMeters, 250.0)))
                    val granted = context.service.request(RouteResourceIntent(
                        "test:request", InfrastructureOwner("test:owner", 0L),
                        a.world.allTrains.single().id, context.observation.stamp, route,
                        RouteBoundary(100.0)))
                    assertTrue(granted is RouteAdmissionResult.Granted, granted.toString())
                }
            }
            advance(a)
            advance(b)
            assertEquals(1, preparations[a.world])
            assertEquals(null, preparations[b.world])
            assertEquals(1, updates.getValue(a.world).size)
            assertTrue(updates.getValue(a.world).single().current != null)
            assertTrue(updates.getValue(b.world).isEmpty())
        } }
    }

    @Test
    fun `failed physical epoch never publishes an admission observation or runs post tick`() {
        val fixture = fixture()
        fixture.runtime.use { simulation ->
            var postTicks = 0
            simulation.world.tickManager.scheduleMainThreadPostTickTask { postTicks++ }
            simulation.world.simpleAtoWorldData.signalEpochRuntime.replaceSources(listOf(
                SignalEpochSource("test:failed", emptyList(), emptyList(), simulation.world.allRails.map { it.id }) { error("signal failed") }
            ))
            val result = simulation.simulate(IsolatedTimetableSimulationRequest(fixture.timetable))
            assertTrue(result is IsolatedTimetableSimulationResult.Failed, result.toString())
            assertEquals(null, simulation.routeAdmissionHost.contextProvider.current())
            assertEquals(0, postTicks)
        }
    }

    @Test
    fun `cancellation waits for physical and generic callbacks before closing feature state`() {
        val physicsEntered = java.util.concurrent.CountDownLatch(1)
        val physicsInterrupted = java.util.concurrent.CountDownLatch(1)
        val physicsRelease = java.util.concurrent.CountDownLatch(1)
        val genericEntered = java.util.concurrent.CountDownLatch(1)
        val genericInterrupted = java.util.concurrent.CountDownLatch(1)
        val genericRelease = java.util.concurrent.CountDownLatch(1)
        val featureClosed = java.util.concurrent.CountDownLatch(1)
        val finished = java.util.concurrent.CountDownLatch(1)
        val interruptedAfterClose = java.util.concurrent.atomic.AtomicBoolean()
        val threadFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val result = java.util.concurrent.atomic.AtomicReference<IsolatedTimetableSimulationResult>()
        fun hold(entered: java.util.concurrent.CountDownLatch, interrupted: java.util.concurrent.CountDownLatch,
            release: java.util.concurrent.CountDownLatch) {
            entered.countDown()
            var wasInterrupted = false
            while (true) {
                try { release.await(); break } catch (failure: InterruptedException) {
                    wasInterrupted = true
                    interrupted.countDown()
                }
            }
            if (wasInterrupted) Thread.currentThread().interrupt()
        }
        val fixture = fixture(features = listOf(object : PreparedSimulationFeature {
            override val id = "test:cancel"
            override fun install(target: SimulationDomainTarget): AutoCloseable {
                target.domain.signalEpochRuntime.replaceSources(listOf(SignalEpochSource(
                    id, emptyList(), emptyList(), target.world.allRails.map { it.id }
                ) {
                    hold(physicsEntered, physicsInterrupted, physicsRelease)
                    SignalEpochResult.empty()
                }))
                target.world.tickManager.register(object : work.art1st.javarailways.core.api.util.tick.TickingObject {
                    override fun tick(deltaT: Double) = hold(genericEntered, genericInterrupted, genericRelease)
                })
                return AutoCloseable { featureClosed.countDown() }
            }
        }))
        IsolatedSimulationThreadMarker.run { fixture.runtime.world.tickManager.postTick() }
        val worker = Thread {
            try {
                fixture.runtime.use { result.set(it.simulate(IsolatedTimetableSimulationRequest(fixture.timetable))) }
            } catch (failure: Throwable) { threadFailure.set(failure) }
            finally {
                interruptedAfterClose.set(Thread.currentThread().isInterrupted)
                finished.countDown()
            }
        }
        worker.start()
        try {
            assertTrue(physicsEntered.await(10, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(genericEntered.await(10, java.util.concurrent.TimeUnit.SECONDS))
            worker.interrupt()
            assertTrue(physicsInterrupted.await(10, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(1L, featureClosed.count, "in-flight physical callback still owns feature state")
            assertEquals(1L, finished.count)
            physicsRelease.countDown()
            assertTrue(genericInterrupted.await(10, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(1L, featureClosed.count, "in-flight generic tick still owns feature state")
            genericRelease.countDown()
            assertTrue(finished.await(10, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(null, threadFailure.get())
            assertTrue(result.get() is IsolatedTimetableSimulationResult.Failed)
            assertTrue(interruptedAfterClose.get(), "teardown preserves the simulation cancellation flag")
            assertEquals(0L, featureClosed.count)
        } finally {
            physicsRelease.countDown()
            genericRelease.countDown()
            worker.interrupt()
            worker.join(10_000)
            fixture.runtime.close()
        }
    }

    private fun advance(simulation: IsolatedTimetableSimulationWorld) = IsolatedSimulationThreadMarker.run {
        val ticket = simulation.world.physicsCoordinator.stepSimulation(50.0)
        ticket.await()
        simulation.world.physicsCoordinator.processBetweenFrames {
            simulation.operationalRuntime.afterPublication(ticket.epoch, {
                simulation.routeAdmissionHost.publish(RailwayObservation.fromCore(
                    simulation.operationalRuntime.worldId, simulation.world.latestWorldFrame()))
            })
        }
    }

    private class FixedSpeedController(train: Train, world: World) : TrainController(train, world) {
        fun publishSpeed(value: Double) { speed = value; commit() }
    }

    private fun fixture(
        steps: List<TimetableStep> = listOf(
            TimetableStep.Check("origin"),
            TimetableStep.At(0, TimetableAction.GoTo("destination")),
            TimetableStep.At(1_000, TimetableAction.Stop(1))
        ),
        speedSafety: SpeedSafetyMonitor.Config = SpeedSafetyMonitor.Config(false, 0.0, 1),
        configureSource: (CoreWorld) -> Unit = {},
        features: List<PreparedSimulationFeature> = emptyList()
    ): Fixture {
        val source = createTestWorld()
        val rail = createStraightRailFromSegments(source, PrimeCurveGenerator.buildStraight(300.0, segmentCount = 1))
        source.registerExtensionData(BasicPlugin.INSTANCE, BasicWorldData())
        source.basicWorldData.wayPoints.add(WayPoint("origin", "Origin", rail.id, 20.0))
        source.basicWorldData.wayPoints.add(WayPoint("destination", "Destination", rail.id, 100.0))
        createTrainWithConfig("live-only", rail, source, listOf(10.0))
        configureSource(source)
        val revision = 1L
        val coreSnapshot = source.saveInfrastructure(revision)
        val basicSnapshot = BasicInfrastructureSnapshotV2.capture(
            source,
            revision,
            projectedWayPointIds = emptySet()
        )
        source.physicsCoordinator.close()
        (source.tickManager as? AutoCloseable)?.close()
        val timetable = Timetable(
            "simulation",
            services = listOf(TimetableService(
                "service",
                TrainSelector.TrainId("simulation-train"),
                steps
            ))
        )
        val runtime = IsolatedTimetableSimulationWorld.create(
            coreSnapshot,
            basicSnapshot,
            SimpleATOInfrastructureSnapshotV1(
                SimpleATOInfrastructureSnapshotV1.CURRENT_FORMAT_VERSION,
                revision,
                emptyList(),
                emptyList(),
                0L
            ),
            workerThreads = 2,
            speedSafety = speedSafety,
            preparedFeatures = features,
            basicDataFactory = { _, directionChanges ->
                BasicWorldData(operatingDirections = OperatingDirectionStore(directionChanges))
            },
            dispatcherFactory = {
                    _, routeAdmissionContextProvider, signalRestrictions, inspectionRuntime, infrastructureDependencyService ->
                val profile = AtoSafetyProfile()
                TimetableDispatcher(
                    routeAdmissionContextProvider = routeAdmissionContextProvider,
                    signalRestrictionSnapshots = signalRestrictions,
                    inspectionRuntime = inspectionRuntime,
                    infrastructureDependencyService = infrastructureDependencyService,
                    atoFactory = TimetableAtoFactory { train, world ->
                        SimpleATO(
                            train,
                            world,
                            KinematicTrainActuator(10.0, profile),
                            profile,
                            inspectionRuntime::maximumDeclaredLeadTimeSeconds
                        )
                    }
                )
            },
            seed = { clone ->
                val cloneRail = requireNotNull(clone.getRail(rail.id))
                createTrainWithConfig("simulation-train", cloneRail, clone, listOf(10.0))
            }
        )
        return Fixture(runtime, timetable)
    }

    private data class Fixture(
        val runtime: IsolatedTimetableSimulationWorld,
        val timetable: Timetable
    )
}
