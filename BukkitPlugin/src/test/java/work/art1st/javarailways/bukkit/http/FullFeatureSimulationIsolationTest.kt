package work.art1st.javarailways.bukkit.http

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.BasicPlugin
import work.art1st.javarailways.basic.BasicWorldData
import work.art1st.javarailways.basic.WayPoint
import work.art1st.javarailways.basic.api.operation.DoorSide
import work.art1st.javarailways.basic.api.operation.TrainOperationTicketState
import work.art1st.javarailways.basic.basicWorldData
import work.art1st.javarailways.basic.rail.RailInfrastructureMetadata
import work.art1st.javarailways.basic.rail.SpeedLimitEntry
import work.art1st.javarailways.basic.rail.SpeedLimitMetadata
import work.art1st.javarailways.basic.rail.SpeedLimitType
import work.art1st.javarailways.bukkit.behavior.CapturedCarBehavior
import work.art1st.javarailways.bukkit.behavior.captureAnimationCapabilities
import work.art1st.javarailways.bukkit.config.model.*
import work.art1st.javarailways.bukkit.operation.SimulationBehaviorEffect
import work.art1st.javarailways.bukkit.operation.SimulationBehaviorEffectCollector
import work.art1st.javarailways.bukkit.operation.simulationBehaviorOrNull
import work.art1st.javarailways.bukkit.signal.SimpleSignalsSimulationFeatureFactory
import work.art1st.javarailways.core.api.event.TrainLinkSuccessEvent
import work.art1st.javarailways.core.api.event.processor.EventListener
import work.art1st.javarailways.core.api.event.processor.HandlesEvent
import work.art1st.javarailways.core.api.event.processor.MainRailEventBus
import work.art1st.javarailways.core.api.physics.infrastructure.*
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.util.tick.Synchronizable
import work.art1st.javarailways.core.api.util.tick.TickingObject
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.*
import work.art1st.javarailways.simpleato.SimpleATOPlugin
import work.art1st.javarailways.simpleato.SimpleATOWorldData
import work.art1st.javarailways.simpleato.api.ato.AtoWorldCoordinator
import work.art1st.javarailways.simpleato.api.destination.*
import work.art1st.javarailways.simpleato.controller.SimpleATO
import work.art1st.javarailways.simpleato.inspection.InspectionRuntime
import work.art1st.javarailways.simpleato.restriction.TrainRestrictionSnapshotStore
import work.art1st.javarailways.simpleato.signal.SignalEpochRuntime
import work.art1st.javarailways.simpleato.simpleAtoWorldData
import work.art1st.javarailways.simpleato.timetable.*
import work.art1st.javarailways.simpleato.timetable.simulation.*
import java.io.File
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real HTTP capture/assembly, official signal pack, per-car JS and Dispatcher completion gates. */
class FullFeatureSimulationIsolationTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun bootstrapPaperItems() = work.art1st.javarailways.bukkit.render.model.ModelRuntimeTestSupport.bootstrap()
    }

    @BeforeEach
    fun initializePlugin() {
        if (BasicPlugin.instanceOrNull() == null) BasicPlugin.INSTANCE = BasicPlugin(101)
        if (SimpleATOPlugin.instanceOrNull() == null) SimpleATOPlugin.INSTANCE = SimpleATOPlugin(102)
        work.art1st.javarailways.core.api.util.plugin.PluginRegistry.register(BasicPlugin.INSTANCE)
        work.art1st.javarailways.core.api.util.plugin.PluginRegistry.register(SimpleATOPlugin.INSTANCE)
    }

    @Test
    fun `official signals and s100 door completion advance independently in two same-capture targets`() {
        TestWorldFixture.create().use { fixture ->
            val source = fixture.world
            val rail = createStraightRailFromSegments(source, PrimeCurveGenerator.buildStraight(300.0, 2))
            val terminalRail = getViewsInOrder(rail, false).last().rail
            registerWorldData(source)
            source.onOwnerForTest {
                getViewsInOrder(rail, false).forEach { view ->
                    source.basicWorldData.operatingDirections.put(view.rail.id, ViewDirection.AB)
                    source.basicWorldData.railMetadata.put(view.rail.id, RailInfrastructureMetadata(
                        speedLimits = SpeedLimitMetadata(listOf(SpeedLimitEntry(10.0, SpeedLimitType.SUBGRADE)))))
                }
            }
            source.basicWorldData.wayPoints.add(WayPoint("origin", rail.id, 30.0))
            source.basicWorldData.wayPoints.add(WayPoint("terminal", terminalRail.id, 130.0))
            OperationalDestinationService(source).register(OperationalDestination(
                "station", terminalRail.id, 50.0, ViewDirection.AB, DestinationRole.Regular))
            val liveTrain = createTrainWithConfig("live-transient", rail, source, listOf(10.0))
            val liveFrame = liveTrain.publishedFrame
            val feature = SimpleSignalsSimulationFeatureFactory.fourLightFeature(
                signalPackDirectory, getViewsInOrder(rail, false).map { it.rail.id },
                getViewsInOrder(rail, false).map { it.curve.totalArcLength }, 300.0)
            val timetable = Timetable("doors", services = listOf(TimetableService("duty",
                TrainSelector.TrainId("scheduled-train"), listOf(
                    TimetableStep.Check("origin"),
                    TimetableStep.At(0, TimetableAction.DestinationOptions(
                        destinationId = "station", minimumDwellTicks = 5L, doorSide = DoorSide.LEFT)),
                    TimetableStep.At(0, TimetableAction.GoTo("station")),
                    TimetableStep.At(1, TimetableAction.GoTo("terminal"))))))
            val effects = SimulationBehaviorEffectCollector()
            val factory = IsolatedTimetableSimulationFactory(
                IsolatedSimulationProfileResolver { _, _, _ -> profile(2) }, 9.80665,
                requiredDomainFeatures = { setOf(SimpleSignalsSimulationFeatureFactory.OWNER_ID) },
                prepareDomainFeatures = { listOf(feature) }, extensionPlugin = BasicPlugin.INSTANCE, effectObserver = effects::record)
            val snapshot = source.onOwnerForTest { factory.capture(source, timetable, listOf(
                TimetableProfileSelection("duty", SimulationProfileType.TRAIN_TEMPLATE, "template"))) }
            factory.create(snapshot, 1).use { first ->
                factory.create(snapshot, 1).use { second ->
                    assertNotEquals(first.world.runtimeId, second.world.runtimeId)
                    val firstCars = first.world.allTrains.single().cars
                    val secondCars = second.world.allTrains.single().cars
                    assertTrue(first.world.allTrains.none { it.id == liveTrain.id })
                    assertNotSame(firstCars[0].simulationBehaviorOrNull, firstCars[1].simulationBehaviorOrNull)
                    assertNotSame(firstCars[0].simulationBehaviorOrNull, secondCars[0].simulationBehaviorOrNull)
                    Trace(first.world, effects).use { firstTrace ->
                        Trace(second.world, effects).use { secondTrace ->
                            val executor = Executors.newFixedThreadPool(2)
                            val held = CountDownLatch(1)
                            val release = CountDownLatch(1)
                            try {
                                val blocker = executor.submit {
                                    first.world.executionOwner.run {
                                        held.countDown()
                                        check(release.await(60, TimeUnit.SECONDS)) { "test owner barrier was not released" }
                                    }
                                }
                                assertTrue(held.await(5, TimeUnit.SECONDS))
                                val secondRun = executor.submit<IsolatedTimetableSimulationResult> {
                                    second.simulate(IsolatedTimetableSimulationRequest(timetable))
                                }
                                val secondResult = secondRun.get(60, TimeUnit.SECONDS)
                                assertTrue(secondResult is IsolatedTimetableSimulationResult.Completed, secondResult.toString())
                                assertEquals(0L, first.world.latestWorldFrame().physicsEpoch,
                                    "target A must remain blocked while B completes real physics, signals and Dispatcher")
                                assertTrue(firstCars.all { effects.snapshot(it).isEmpty() })
                                release.countDown()
                                blocker.get(5, TimeUnit.SECONDS)
                                val firstResult = first.simulate(IsolatedTimetableSimulationRequest(timetable))
                                assertTrue(firstResult is IsolatedTimetableSimulationResult.Completed, firstResult.toString())
                                assertEquals(secondResult.simulatedTicks, firstResult.simulatedTicks)
                                assertDoorGate(firstTrace)
                                assertDoorGate(secondTrace)
                                assertEquals(firstTrace.samples.map { it.state }, secondTrace.samples.map { it.state })
                                first.close()
                                // A's retirement cannot remove B's source/JS resources: B commits another epoch.
                                val before = second.world.latestWorldFrame().physicsEpoch
                                second.world.physicsCoordinator.stepSimulation(50.0).await()
                                assertEquals(before + 1, second.world.latestWorldFrame().physicsEpoch)
                            } finally {
                                release.countDown()
                                executor.shutdownNow()
                                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
                            }
                        }
                    }
                }
            }
            assertSame(liveFrame, liveTrain.publishedFrame)
            assertNull(liveTrain.controller)
            assertTrue(source.simpleAtoWorldData.dispatcher.statuses().isEmpty())
        }
    }

    @Test
    fun `real collision coupling keeps pending exact-car JS and resolves a controllerless incoming profile`() {
        TestWorldFixture.create().use { fixture ->
            val source = fixture.world
            val rail = createStraightRailFromSegments(source, PrimeCurveGenerator.buildStraight(300.0, 1))
            registerWorldData(source)
            source.basicWorldData.operatingDirections.put(rail.id, ViewDirection.AB)
            source.basicWorldData.wayPoints.add(WayPoint("lead-origin", rail.id, 50.0))
            source.basicWorldData.wayPoints.add(WayPoint("incoming-origin", rail.id, 40.0))
            source.basicWorldData.wayPoints.add(WayPoint("terminal", rail.id, 250.0))
            val leadProfile = profile(1).let { base ->
                val original = base.cars.single().behavior
                val script = requireNotNull(original.script)
                val continuity = original.copy(script = script.copy(source = script.source + """
                    let couplingCalls = 0;
                    defineAction('continuity', function() {
                        couplingCalls++;
                        return action(couplingCalls === 1 ? 'door-left-close' : 'door-right-close');
                    });
                """.trimIndent()))
                base.copy(cars = listOf(base.cars.single().copy(behavior = continuity)))
            }
            val incomingControl = CarControlConfig(mass = 35_000.0)
            val incomingProfile = profile(1).let { base -> base.copy(id = "incoming",
                carControls = listOf(incomingControl),
                cars = listOf(base.cars.single().copy(control = incomingControl))) }
            val timetable = Timetable("coupling", services = listOf(
                TimetableService("lead-duty", TrainSelector.TrainId("lead"), listOf(
                    TimetableStep.Check("lead-origin"), TimetableStep.At(100, TimetableAction.GoTo("terminal")))),
                TimetableService("incoming-duty", TrainSelector.TrainId("incoming"), listOf(
                    TimetableStep.Check("incoming-origin"), TimetableStep.At(100, TimetableAction.GoTo("terminal"))))))
            val effects = SimulationBehaviorEffectCollector()
            val factory = IsolatedTimetableSimulationFactory(IsolatedSimulationProfileResolver { _, _, id ->
                if (id == "incoming") incomingProfile else leadProfile
            }, 9.80665, extensionPlugin = BasicPlugin.INSTANCE, effectObserver = effects::record)
            val snapshot = source.onOwnerForTest { factory.capture(source, timetable, listOf(
                TimetableProfileSelection("lead-duty", SimulationProfileType.TRAIN_TEMPLATE, "template"),
                TimetableProfileSelection("incoming-duty", SimulationProfileType.TRAIN_TEMPLATE, "incoming"))) }
            factory.create(snapshot, 1).use { simulation ->
                val world = simulation.world
                val lead = world.getTrain("lead") as CoreTrain
                val incoming = world.getTrain("incoming") as CoreTrain
                val exactLead = lead.cars.single()
                val exactIncoming = incoming.cars.single()
                val leadBehavior = requireNotNull(exactLead.simulationBehaviorOrNull)
                val incomingBehavior = requireNotNull(exactIncoming.simulationBehaviorOrNull)
                val pending = CompletableFuture<Unit>()
                world.onOwnerForTest {
                    world.simpleAtoWorldData.timetables.add(timetable)
                    val started = world.simpleAtoWorldData.dispatcher.startService(world, timetable.id, "lead-duty")
                    assertTrue(started is TimetableServiceStartResult.Started, started.toString())
                    leadBehavior.action("continuity", false) { pending.completeFrom(it) }
                }
                val ato = lead.controller as SimpleATO
                assertNull(incoming.controller)
                assertFalse(pending.isDone)
                assertNull(ato.dynamicsProfileFor(exactIncoming))
                val notifications = mutableListOf<TrainLinkSuccessEvent>()
                MainRailEventBus.register(object : EventListener {
                    @HandlesEvent fun linked(event: TrainLinkSuccessEvent) {
                        if (event.world === world) notifications.add(event)
                    }
                }).use {
                    // Same collision-owner transaction as CoreTrainLinkTest, with real captured envelopes.
                    // Detection is covered in Core; this joint test exercises actual controller/extension preparation.
                    world.onOwnerForTest {
                        assertSame(lead, SimpleSignalsSimulationFeatureFactory.linkTouchingTrains(lead, incoming))
                    }
                }
                assertEquals(1, notifications.size)
                assertSame(world, notifications.single().world)
                assertEquals(listOf(lead), world.allTrains.toList())
                assertSame(ato, lead.controller)
                assertSame(lead, exactIncoming.train)
                assertSame(leadBehavior, exactLead.simulationBehaviorOrNull)
                assertSame(incomingBehavior, exactIncoming.simulationBehaviorOrNull)
                assertSame(incomingControl, ato.dynamicsProfileFor(exactIncoming),
                    "existing ATO must resolve the incoming car's captured profile without a counterpart controller")
                assertFalse(pending.isDone)
                advanceGeneric(world, 0)
                advanceGeneric(world, 650)
                pending.get(5, TimeUnit.SECONDS)
                assertTrue(effects.snapshot(exactIncoming).isEmpty(), "new cars cannot join an already accepted operation")
                assertTrue(effects.snapshot(exactLead).any {
                    it is SimulationBehaviorEffect.AnimationStarted && it.name == "doors_l" && it.scene == "closed"
                })
                val later = CompletableFuture<Unit>()
                world.onOwnerForTest { leadBehavior.action("continuity", false) { later.completeFrom(it) } }
                advanceGeneric(world, 650)
                advanceGeneric(world, 1300)
                later.get(5, TimeUnit.SECONDS)
                assertTrue(effects.snapshot(exactLead).any {
                    it is SimulationBehaviorEffect.AnimationStarted && it.name == "doors_r" && it.scene == "closed"
                }, "persistent JS counter must advance across coupling rather than restart")
            }
        }
    }

    private fun CompletableFuture<Unit>.completeFrom(result: Result<Unit>) {
        result.fold({ complete(Unit) }, { completeExceptionally(it) })
    }

    private fun advanceGeneric(world: CoreWorld, millis: Long) = world.onOwnerForTest {
        world.tickManager.preTick(millis)
        world.tickManager.postTick()
        world.tickManager.drainPendingCommits().forEach { it.commit() }
    }

    private fun assertDoorGate(trace: Trace) {
        assertTrue(trace.samples.any { it.signalSourceIds.contains("simplesignals:fixed-block-signals") },
            "official SimpleSignals must publish restrictions through real physics epochs")
        val closing = trace.samples.filter { it.state == TimetableServiceRunState.DOORS_CLOSING }
        assertTrue(closing.count { it.ticket == TrainOperationTicketState.PENDING } >= 11,
            "real 0.6-second s100 close delay must hold the Dispatcher for multiple logical ticks: $closing")
        assertTrue(closing.any { it.closedSides.all { side -> side.isEmpty() } }, "closing cannot complete at acceptance")
        val completedEffects = trace.samples.first { sample ->
            sample.closedSides.all { it.isNotEmpty() } && sample.closeSoundCounts.all { it == 1 }
        }
        assertTrue(completedEffects.epoch - closing.first().epoch >= 12,
            "both cars must wait for delayed animation/sound starts before departure")
        assertEquals(listOf(listOf("doors_l"), listOf("doors_r")), completedEffects.closedSides)
        assertTrue(trace.samples.any { it.state == TimetableServiceRunState.COMPLETED })
    }

    private data class Sample(
        val epoch: Long,
        val state: TimetableServiceRunState?,
        val ticket: TrainOperationTicketState?,
        val closedSides: List<List<String>>,
        val closeSoundCounts: List<Int>,
        val signalSourceIds: Set<String>
    )

    /** Only owner commits inspect mutable Dispatcher state; generic workers perform no observations. */
    private class Trace(private val world: CoreWorld, private val effects: SimulationBehaviorEffectCollector) : TickingObject, Synchronizable, AutoCloseable {
        val samples = mutableListOf<Sample>()
        init { world.tickManager.register(this) }
        override fun tick(deltaT: Double) = Unit
        override fun commit() {
            val train = world.allTrains.single()
            val run = world.simpleAtoWorldData.dispatcher.statuses().singleOrNull()
            samples.add(Sample(world.latestWorldFrame().physicsEpoch, run?.state, run?.operationTicketState,
                train.cars.map { car -> effects.snapshot(car)
                    .filterIsInstance<SimulationBehaviorEffect.AnimationStarted>()
                    .filter { it.scene == "closed" }.map { it.name } },
                train.cars.map { car -> effects.snapshot(car)
                    .filterIsInstance<SimulationBehaviorEffect.SoundStarted>()
                    .count { it.key == "minecraft:block.iron_door.close" } },
                world.simpleAtoWorldData.signalRestrictions.snapshotsFor(train.id).mapTo(linkedSetOf()) { it.sourceId }))
        }
        override fun close() { world.tickManager.unregister(this) }
    }

    private fun profile(carCount: Int): SimulationControlProfile {
        val loaded = requireNotNull(PackageLoader(File("../models/s100")).loadTrainPackage())
        val car = requireNotNull(loaded.cars["head"])
        val behavior = CapturedCarBehavior(loaded.behavior, loaded.actions,
            SavedCarModelTree.build(car, loaded.partRegistry).captureAnimationCapabilities())
        val control = CarControlConfig(motor = MotorControlConfig(enabled = true, maxForce = 80_000.0, maxPower = 800_000.0))
        val cars = List(carCount) { index -> SimulationCarBlueprint(10.0, 0.0, 0.0, 0.0, 3.5, -3.5,
            control, behavior, installedReversed = index == 1) }
        return SimulationControlProfile(SimulationProfileType.TRAIN_TEMPLATE, "template", TrainControlConfig(maxSpeed = 10.0),
            cars.map { it.control }, cars)
    }

    private fun registerWorldData(world: CoreWorld) {
        val basic = BasicWorldData()
        world.registerExtensionData(BasicPlugin.INSTANCE, basic)
        val ato = AtoWorldCoordinator(safetyInterlock = basic.safetyInterlock)
        val restrictions = TrainRestrictionSnapshotStore(ato.worldId)
        val inspection = InspectionRuntime(restrictions) { _, _, failure -> throw failure }
        val signals = SignalEpochRuntime(ato.worldId, restrictions, restrictions::completeEpoch, inspection::markDirty)
        val dispatcher = TimetableDispatcher(TimetableRouteAdmissionContextProvider { null }, restrictions, inspection,
            object : InfrastructureDependencyService {
                override fun set(request: InfrastructureDependencyRequest) =
                    InfrastructureDependencyMutationResult(InfrastructureDependencyMutationStatus.APPLIED)
                override fun remove(owner: InfrastructureOwner) =
                    InfrastructureDependencyMutationResult(InfrastructureDependencyMutationStatus.REMOVED)
            })
        world.registerExtensionData(SimpleATOPlugin.INSTANCE,
            SimpleATOWorldData(ato, dispatcher, signals, restrictions, inspection, basic.railObjects, basic.safetyInterlock))
    }

    private val signalPackDirectory: Path
        get() = Path.of("../SimpleSignals/examples/jr-four-light/signal-packs")
}
