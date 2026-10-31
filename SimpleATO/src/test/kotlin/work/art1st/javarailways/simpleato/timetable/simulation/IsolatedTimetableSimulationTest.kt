package work.art1st.javarailways.simpleato.timetable.simulation

import work.art1st.javarailways.simpleato.api.timetable.TimetableActionProvider
import work.art1st.javarailways.simpleato.api.timetable.TimetableExtensionCall
import work.art1st.javarailways.simpleato.api.timetable.TimetableExtensionDescriptor
import work.art1st.javarailways.simpleato.api.timetable.TimetableRuntimeContext
import work.art1st.javarailways.simpleato.api.timetable.TimetableExtensionActionStartResult
import work.art1st.javarailways.simpleato.api.timetable.TimetableExtensionActionTicket
import work.art1st.javarailways.simpleato.api.timetable.TimetableExtensionActionSnapshot
import work.art1st.javarailways.simpleato.api.timetable.TimetableExtensionActionState
import work.art1st.javarailways.simpleato.api.simulation.PreparedSimulationFeature
import work.art1st.javarailways.simpleato.api.simulation.SimulationDomainTarget
import work.art1st.javarailways.simpleato.api.simulation.SimulationResourceProvider

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
import work.art1st.javarailways.core.physics.train.TestWorldFixture
import work.art1st.javarailways.core.physics.train.createTrainWithConfig
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureOwner
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureDependencies
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureDependencyRequest
import work.art1st.javarailways.core.api.physics.infrastructure.TopologyEditImpact
import work.art1st.javarailways.core.api.physics.infrastructure.TopologyEditRequest
import work.art1st.javarailways.core.api.physics.infrastructure.TopologyEditStatus
import work.art1st.javarailways.basic.api.observation.RailwayObservation
import work.art1st.javarailways.basic.api.route.ActiveRouteAdmissionUpdate
import work.art1st.javarailways.basic.api.route.RouteAdmissionResult
import work.art1st.javarailways.basic.api.route.RouteBoundary
import work.art1st.javarailways.basic.api.route.RouteResourceIntent
import work.art1st.javarailways.basic.rail.OperatingDirectionStore
import work.art1st.javarailways.core.api.physics.WorldExecutionMode
import work.art1st.javarailways.core.api.physics.infrastructure.StableInfrastructureResource
import work.art1st.javarailways.basic.api.route.FormationTrainIdentity
import work.art1st.javarailways.basic.api.route.FormationRouteContext
import work.art1st.javarailways.basic.api.route.FormationRoutePurpose
import work.art1st.javarailways.basic.api.route.FormationRoutePolicyResult
import work.art1st.javarailways.basic.api.route.FormationResourceClearance
import work.art1st.javarailways.basic.api.route.ContributedRouteResource
import work.art1st.javarailways.basic.api.route.ResourcePreparationDescriptor
import work.art1st.javarailways.basic.api.route.ResourcePreparationSnapshot
import work.art1st.javarailways.basic.api.route.ResourcePreparationState
import work.art1st.javarailways.basic.api.route.ResourcePreparationTicket
import work.art1st.javarailways.basic.api.route.RouteAdmissionFailure

class IsolatedTimetableSimulationTest {
    @Test
    fun `final extension duration ends at ticket success and rejection leaves task unfinished`() {
        for (reject in listOf(false, true)) {
            val f = fixture(trainSelector = TrainSelector.AnyTrain, steps = listOf(
                TimetableStep.At(0, TimetableAction.TakeFromWaypoint(listOf("origin"))),
                TimetableStep.At(10, TimetableAction.TaskStart("extension")),
                TimetableStep.At(10, TimetableAction.Extension(TimetableExtensionCall("test:timed"))),
                TimetableStep.At(10, TimetableAction.TaskEnd("extension")),
                TimetableStep.At(100, TimetableAction.GoTo("destination"))
            ))
            f.runtime.use { runtime ->
                var polls = 0
                runtime.world.simpleAtoWorldData.timetableExtensions.registerAction(object : TimetableActionProvider {
                    override val typeId = "test:timed"
                    override val version = 1
                    override val descriptor = TimetableExtensionDescriptor("Timed", "Final action duration")
                    override fun start(parameters: kotlinx.serialization.json.JsonObject, context: TimetableRuntimeContext): TimetableExtensionActionStartResult =
                        if (reject) TimetableExtensionActionStartResult.Rejected("test rejection") else
                            TimetableExtensionActionStartResult.Accepted(object : TimetableExtensionActionTicket {
                                override fun snapshot() = TimetableExtensionActionSnapshot(
                                    if (++polls < 4) TimetableExtensionActionState.PENDING else TimetableExtensionActionState.SUCCEEDED)
                                override fun cancel() = Unit
                            })
                })
                val result = runtime.simulate(IsolatedTimetableSimulationRequest(f.timetable))
                assertTrue(result.runs.isNotEmpty()) { result.toString() }
                val timing = result.runs.single().taskTimings.single()
                assertEquals(10L, timing.startTick)
                if (reject) {
                    assertTrue(result is IsolatedTimetableSimulationResult.Failed) { result.toString() }
                    assertEquals(null, timing.endTick)
                } else {
                    assertTrue(result is IsolatedTimetableSimulationResult.Completed) { result.toString() }
                    assertEquals(14L, timing.endTick)
                }
            }
        }
    }

    @Test
    fun `task measurement counts final dwell and wait-until but excludes scheduled idle`() {
        val f = fixture(trainSelector = TrainSelector.AnyTrain, steps = listOf(
            TimetableStep.At(0, TimetableAction.TakeFromWaypoint(listOf("origin"))),
            TimetableStep.At(10, TimetableAction.TaskStart("wait")),
            TimetableStep.At(20, TimetableAction.WaitUntil),
            TimetableStep.At(20, TimetableAction.Stop(7)),
            TimetableStep.At(20, TimetableAction.TaskEnd("wait")),
            TimetableStep.At(80, TimetableAction.TaskStart("empty")),
            TimetableStep.At(80, TimetableAction.TaskEnd("empty")),
            TimetableStep.At(100, TimetableAction.GoTo("destination"))
        ))
        f.runtime.use { runtime ->
            val result = runtime.simulate(IsolatedTimetableSimulationRequest(f.timetable))
            assertTrue(result is IsolatedTimetableSimulationResult.Completed) { result.toString() }
            val timings = result.runs.single().taskTimings
            assertEquals(listOf("wait", "empty"), timings.map { it.taskId })
            assertEquals(10L, timings[0].startTick)
            assertEquals(27L, timings[0].endTick)
            assertEquals(80L, timings[1].startTick)
            assertEquals(80L, timings[1].endTick)
        }
    }

    @Test
    fun `last movement completion is measured and a subsequent late task starts after completion`() {
        val f = fixture(steps = listOf(
            TimetableStep.Check("origin"),
            TimetableStep.At(0, TimetableAction.TaskStart("move")),
            TimetableStep.At(0, TimetableAction.GoTo("destination")),
            TimetableStep.At(0, TimetableAction.TaskEnd("move")),
            TimetableStep.At(1, TimetableAction.TaskStart("late")),
            TimetableStep.At(1, TimetableAction.Stop(5)),
            TimetableStep.At(1, TimetableAction.TaskEnd("late"))
        ))
        f.runtime.use { runtime ->
            val result = runtime.simulate(IsolatedTimetableSimulationRequest(f.timetable))
            assertTrue(result is IsolatedTimetableSimulationResult.Completed) { result.toString() }
            val timings = result.runs.single().taskTimings
            assertTrue(timings[0].endTick!! > timings[0].startTick)
            assertEquals(timings[0].endTick, timings[1].startTick)
            assertEquals(5L, timings[1].endTick!! - timings[1].startTick)
        }
    }

    @Test
    fun `unfinished task has no fabricated completion time`() {
        val f = fixture(trainSelector = TrainSelector.AnyTrain, steps = listOf(
            TimetableStep.At(0, TimetableAction.TakeFromWaypoint(listOf("origin"))),
            TimetableStep.At(0, TimetableAction.TaskStart("unfinished")),
            TimetableStep.At(0, TimetableAction.Stop(100)),
            TimetableStep.At(0, TimetableAction.TaskEnd("unfinished")),
            TimetableStep.At(100, TimetableAction.GoTo("destination"))
        ))
        f.runtime.use { runtime ->
            val result = runtime.simulate(IsolatedTimetableSimulationRequest(f.timetable, maximumSimulationTicks = 10))
            assertTrue(result is IsolatedTimetableSimulationResult.PeriodEnded) { result.toString() }
            assertEquals(null, result.runs.single().taskTimings.single().endTick)
        }
    }

    @Test
    fun `detached formation needs contributor opt-in and exact provider clearance`() {
        val resource = StableInfrastructureResource("test:formation", "block")
        fixture().runtime.use { simulation ->
            IsolatedSimulationThreadMarker.run { simulation.world.executionOwner.run {
                val host = simulation.routeAdmissionHost
                val contributor = host.registerContributor("test:policy") { emptyList() }
                val context = FormationRouteContext::class.java.constructors.single { it.parameterCount == 6 }.newInstance(
                    "test:event", FormationRoutePurpose.OUTPUT_BODY, listOf(FormationTrainIdentity("source", listOf("car"), false)),
                    listOf(FormationTrainIdentity("train", listOf("car"), false)), listOf(resource), null
                ) as FormationRouteContext
                val rail = simulation.world.allRails.single()
                val route = RouteSnapshot("test:formation", 1L, simulation.world.topologyRevision,
                    listOf(RouteSegmentSnapshot(rail.id, ViewDirection.AB, 0.0, 100.0)))
                val request = RouteResourceIntent("test:formation", InfrastructureOwner("test:formation", 1L), "train",
                    work.art1st.javarailways.basic.api.observation.RailwayStateStamp(simulation.world.runtimeId, 0L,
                        route.topologyRevision), route, RouteBoundary(2.0), context)
                assertTrue(host.authorizeFormationRoute(context, request) is FormationRoutePolicyResult.Rejected)
                assertThrows(IllegalStateException::class.java) {
                    host.registerFormationContributor("test:policy", { emptyList() }) { _, _ -> null }
                }
                contributor.close()
                val optIn = host.registerFormationContributor("test:policy", { emptyList() }) { _, _ -> null }
                var clearances = listOf(FormationResourceClearance(resource, RouteBoundary(90.0)))
                val provider = host.registerProvider(resource.typeId,
                    object : work.art1st.javarailways.simpleato.api.simulation.SimulationResourceProvider {
                        override fun exists(resource: StableInfrastructureResource) = true
                        override fun validate(contributorId: String, contribution: ContributedRouteResource): RouteAdmissionFailure? = null
                        override fun prepare(resource: StableInfrastructureResource, descriptor: ResourcePreparationDescriptor):
                            (() -> ResourcePreparationTicket)? = null
                        override fun authorizeFormationResource(resource: StableInfrastructureResource, context: FormationRouteContext,
                            intent: RouteResourceIntent) = FormationRoutePolicyResult.Accepted(clearances)
                    })
                assertEquals(RouteBoundary(90.0), (host.authorizeFormationRoute(context, request)
                    as FormationRoutePolicyResult.Accepted).clearances.single().releaseAfterTailPasses)
                clearances = listOf(FormationResourceClearance(resource.copy(resourceId = "forged"), null))
                assertTrue(host.authorizeFormationRoute(context, request) is FormationRoutePolicyResult.Rejected)
                clearances = emptyList()
                assertTrue(host.authorizeFormationRoute(context, request) is FormationRoutePolicyResult.Rejected)
                clearances = listOf(FormationResourceClearance(resource, RouteBoundary(101.0)))
                assertTrue(host.authorizeFormationRoute(context, request) is FormationRoutePolicyResult.Rejected)
                clearances = listOf(FormationResourceClearance(resource, null))
                assertEquals(null, (host.authorizeFormationRoute(context, request)
                    as FormationRoutePolicyResult.Accepted).clearances.single().releaseAfterTailPasses)
                optIn.close()
                val legacy = host.registerContributor("test:policy") { emptyList() }
                assertTrue(host.authorizeFormationRoute(context, request) is FormationRoutePolicyResult.Rejected)
                provider.close()
                legacy.close()
            } }
        }
    }

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
            assertEquals(3.0, (rail.viewAB.staticRailObjects.single() as OperationalSpeedLimitSignal).speedLimitMetersPerSecond)
            assertEquals(3.0, (rail.viewBA.staticRailObjects.single() as OperationalSpeedLimitSignal).speedLimitMetersPerSecond)
            val controller = FixedSpeedController(train)
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
                val controller = FixedSpeedController(train)
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
            val controller = FixedSpeedController(train)
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
        var installedWorld: CoreWorld? = null
        val failure = IllegalStateException("install failed")
        val features = listOf(
            object : PreparedSimulationFeature {
                override val id = "test:first"
                override fun install(target: SimulationDomainTarget): AutoCloseable {
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
        assertEquals(failure, assertThrows(IllegalStateException::class.java) {
            fixture(features = features, configureTarget = { installedWorld = it })
        })
        assertEquals(listOf("test:second", "test:first"), closed)
        assertEquals(listOf("cleanup failed"), failure.suppressed.map { it.message })
        assertThrows(IllegalStateException::class.java) { checkNotNull(installedWorld).physicsCoordinator.stepSimulation(50.0) }
    }

    @Test
    fun `construction failure closes owned operation resource after features and preserves cleanup failures`() {
        val order = arrayListOf<String>()
        val original = IllegalStateException("seed setup failed")
        val cleanup = IllegalArgumentException("operation cleanup failed")
        val failure = assertThrows(IllegalStateException::class.java) {
            fixture(
                operationResourceFactory = { AutoCloseable { order += "operation"; throw cleanup } },
                features = listOf(object : PreparedSimulationFeature {
                    override val id = "test:installed-before-operation-failure"
                    override fun install(target: SimulationDomainTarget): AutoCloseable = AutoCloseable { order += "feature" }
                }, object : PreparedSimulationFeature {
                    override val id = "test:broken-operation-owner"
                    override fun install(target: SimulationDomainTarget): AutoCloseable = throw original
                })
            )
        }
        assertEquals(original, failure)
        assertEquals(listOf("feature", "operation"), order)
        assertEquals(listOf(cleanup), failure.suppressed.toList())
    }

    @Test
    fun `target feature receives real admission updates only from its local runtime`() {
        val updates = java.util.HashMap<java.util.UUID, MutableList<ActiveRouteAdmissionUpdate>>()
        val preparations = java.util.HashMap<java.util.UUID, Int>()
        val feature = object : PreparedSimulationFeature {
            override val id = "test:admission-source"
            override fun install(target: SimulationDomainTarget): AutoCloseable {
                val received = arrayListOf<ActiveRouteAdmissionUpdate>()
                updates[target.worldId] = received
                val resource = StableInfrastructureResource("test:device", "one")
                val preparation = ResourcePreparationDescriptor("test:set", emptyMap())
                val provider = target.registerResourceProvider(resource.typeId, object : SimulationResourceProvider {
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
                            preparations[target.worldId] = (preparations[target.worldId] ?: 0) + 1
                            object : ResourcePreparationTicket {
                                override fun snapshot() = ResourcePreparationSnapshot(
                                    ResourcePreparationState.READY)
                                override fun abort() = Unit
                            }
                        }
                    }
                })
                val contributor = target.registerRouteContributor("test:contributor") {
                    listOf(ContributedRouteResource(resource,
                        RouteBoundary(100.0), preparation))
                }
                val signal = target.installSignalSources(listOf(SignalEpochSource(
                    "test:admission-source", emptyList(), emptyList(), target.topology.railProjection.keys.toList()
                ) { input -> received += input.admissionUpdates; SignalEpochResult.empty() }))
                return AutoCloseable {
                    signal.close()
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
                    val protectedRail = TopologyEditImpact(listOf(rail.id), emptyList(), emptyList())
                    val edit = a.world.infrastructureCoordination.acquireEditPermit(
                        this, TopologyEditRequest(a.world.topologyRevision,
                            InfrastructureOwner("test:edit-during-admission", 0L), protectedRail))
                    assertEquals(TopologyEditStatus.CONFLICT, edit.decision.status,
                        "simulation admission and Core topology edits use the same world arbiter")
                    assertThrows(IllegalStateException::class.java) {
                        a.world.infrastructureCoordination.ensureEditProtected(protectedRail)
                    }
                    assertTrue(a.routeAdmissionHost.infrastructureDependencyService.set(
                        InfrastructureDependencyRequest(InfrastructureOwner("test:static-plan", 0L),
                            InfrastructureDependencies(listOf(rail.id), emptyList(), emptyList()))).successful)
                }
            }
            advance(a)
            advance(b)
            assertEquals(1, preparations[java.util.UUID.fromString(a.world.runtimeId)])
            assertEquals(null, preparations[java.util.UUID.fromString(b.world.runtimeId)])
            assertEquals(1, updates.getValue(java.util.UUID.fromString(a.world.runtimeId)).size)
            assertTrue(updates.getValue(java.util.UUID.fromString(a.world.runtimeId)).single().current != null)
            assertTrue(updates.getValue(java.util.UUID.fromString(b.world.runtimeId)).isEmpty())
        } }
        val retired = first.runtime.world
        val editor = Any()
        val edit = retired.infrastructureCoordination.acquireEditPermit(editor, TopologyEditRequest(
            retired.topologyRevision, InfrastructureOwner("test:edit-after-admission-close", 0L),
            TopologyEditImpact(listOf(retired.allRails.single().id), emptyList(), emptyList())))
        assertEquals(TopologyEditStatus.GRANTED, edit.decision.status,
            "target retirement releases admission claims without closing the Core-owned arbiter")
        assertTrue(retired.infrastructureCoordination.releaseEditPermit(editor, edit.id))
    }

    @Test
    fun `feature retirement removes active inspection bindings without unloading another feature`() {
        val tokens = linkedMapOf<String, AutoCloseable>()
        fun feature(sourceId: String) = object : PreparedSimulationFeature {
            override val id = sourceId
            override fun install(target: SimulationDomainTarget): AutoCloseable {
                val source = work.art1st.javarailways.simpleato.api.inspection.InspectionSource(
                    sourceId,
                    { input -> work.art1st.javarailways.simpleato.api.inspection.InspectionDeclarationBinding(
                        sourceId, input.trainId,
                        work.art1st.javarailways.simpleato.api.route.FrozenRouteBinding.from(input.route),
                        1L, 1L, emptyList()) },
                    { emptyList() }
                )
                return target.installInspectionSources(listOf(source)).also { tokens[sourceId] = it }
            }
        }
        fixture(features = listOf(feature("test:first"), feature("test:second"))).runtime.use { simulation ->
            val runtime = simulation.operationalRuntime.inspectionRuntime
            val train = simulation.world.allTrains.single()
            val rail = simulation.world.allRails.single()
            val route = RouteSnapshot("test:active", 1L, simulation.world.topologyRevision,
                listOf(RouteSegmentSnapshot(rail.id, ViewDirection.AB, 0.0, 200.0)))
            IsolatedSimulationThreadMarker.run { simulation.world.executionOwner.run {
                runtime.synchronizeRoute(work.art1st.javarailways.basic.api.observation.RailwayStateStamp(
                    simulation.world.runtimeId, 0L, route.topologyRevision), train.id, route)
            } }
            assertEquals(2, runtime.activeBindings(setOf("test:first", "test:second")).size)
            IsolatedSimulationThreadMarker.run { simulation.world.executionOwner.run {
                simulation.world.physicsCoordinator.closeAndAwaitTermination()
                tokens.getValue("test:first").close()
                tokens.getValue("test:first").close()
                assertTrue(runtime.activeBindings(setOf("test:first")).isEmpty())
                assertEquals(1, runtime.activeBindings(setOf("test:second")).size)
            } }
            simulation.close()
            assertTrue(runtime.activeBindings(setOf("test:first", "test:second")).isEmpty())
        }
    }

    @Test
    @work.art1st.javarailways.core.testing.IsolateGlobalHalt
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
        val operationClosed = java.util.concurrent.CountDownLatch(1)
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
                val signal = target.installSignalSources(listOf(SignalEpochSource(
                    id, emptyList(), emptyList(), target.topology.railProjection.keys.toList()
                ) {
                    hold(physicsEntered, physicsInterrupted, physicsRelease)
                    SignalEpochResult.empty()
                }))
                return AutoCloseable { signal.close(); featureClosed.countDown() }
            }
        }), operationResourceFactory = { AutoCloseable { operationClosed.countDown() } })
        IsolatedSimulationThreadMarker.run {
            fixture.runtime.world.tickManager.register(object : work.art1st.javarailways.core.api.util.tick.TickingObject {
                override fun tick(deltaT: Double) = hold(genericEntered, genericInterrupted, genericRelease)
            })
            fixture.runtime.world.tickManager.postTick()
        }
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
            assertEquals(1L, operationClosed.count, "physical callback still owns operation dependencies")
            assertEquals(1L, finished.count)
            physicsRelease.countDown()
            assertTrue(genericInterrupted.await(10, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(1L, featureClosed.count, "in-flight generic tick still owns feature state")
            assertEquals(1L, operationClosed.count, "generic callback still owns operation dependencies")
            genericRelease.countDown()
            assertTrue(finished.await(10, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(null, threadFailure.get())
            assertTrue(result.get() is IsolatedTimetableSimulationResult.Failed)
            assertTrue(interruptedAfterClose.get(), "teardown preserves the simulation cancellation flag")
            assertEquals(0L, featureClosed.count)
            assertEquals(0L, operationClosed.count)
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

    private class FixedSpeedController(train: Train) : TrainController(train) {
        fun publishSpeed(value: Double) { speed = value; commit() }
    }

    private fun fixture(
        steps: List<TimetableStep> = listOf(
            TimetableStep.Check("origin"),
            TimetableStep.At(0, TimetableAction.GoTo("destination")),
            TimetableStep.At(1_000, TimetableAction.Stop(1))
        ),
        trainSelector: TrainSelector = TrainSelector.TrainId("simulation-train"),
        speedSafety: SpeedSafetyMonitor.Config = SpeedSafetyMonitor.Config(false, 0.0, 1),
        configureSource: (CoreWorld) -> Unit = {},
        features: List<PreparedSimulationFeature> = emptyList(),
        configureTarget: (CoreWorld) -> Unit = {},
        operationResourceFactory: (CoreWorld) -> AutoCloseable? = { null }
    ): Fixture {
        val revision = 1L
        val (coreSnapshot, basicSnapshot, railId) = TestWorldFixture.create().use { fixture ->
            val source = fixture.world
            val rail = createStraightRailFromSegments(source, PrimeCurveGenerator.buildStraight(300.0, segmentCount = 1))
            source.registerExtensionData(BasicPlugin.INSTANCE, BasicWorldData())
            source.basicWorldData.wayPoints.add(WayPoint("origin", rail.id, 20.0))
            source.basicWorldData.wayPoints.add(WayPoint("destination", rail.id, 100.0))
            createTrainWithConfig("live-only", rail, source, listOf(10.0))
            configureSource(source)
            val coreSnapshot = source.saveInfrastructure(revision)
            val basicSnapshot = BasicInfrastructureSnapshotV2.capture(
                source,
                revision,
                projectedWayPointIds = emptySet()
            )
            Triple(coreSnapshot, basicSnapshot, rail.id)
        }
        val timetable = Timetable(
            "simulation",
            services = listOf(TimetableService(
                "service",
                trainSelector,
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
            operationResourceFactory = operationResourceFactory,
            basicDataFactory = { target, directionChanges ->
                configureTarget(target)
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
                val cloneRail = requireNotNull(clone.getRail(railId))
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
