package work.art1st.javarailways.bukkit.http

import work.art1st.javarailways.core.api.physics.train.TrainComposition
import work.art1st.javarailways.core.api.physics.train.TrainUnitComposition
import kotlinx.serialization.json.jsonPrimitive

import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertNull
import work.art1st.javarailways.core.physics.train.onOwnerForTest
import work.art1st.javarailways.core.physics.createRailForTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.BasicPlugin
import work.art1st.javarailways.basic.BasicWorldData
import work.art1st.javarailways.basic.WayPoint
import work.art1st.javarailways.basic.basicWorldData
import work.art1st.javarailways.simpleato.api.destination.DestinationRole
import work.art1st.javarailways.simpleato.api.destination.OperationalDestination
import work.art1st.javarailways.simpleato.api.destination.OperationalDestinationService
import work.art1st.javarailways.simpleato.api.line.Line
import work.art1st.javarailways.simpleato.api.line.LineId
import work.art1st.javarailways.basic.api.operation.DoorSide
import work.art1st.javarailways.simpleato.api.station.*
import work.art1st.javarailways.simpleato.api.mission.WayPointRef
import work.art1st.javarailways.simpleato.timetable.Timetable
import work.art1st.javarailways.simpleato.timetable.TimetableFormationCatalog
import work.art1st.javarailways.simpleato.timetable.TimetableFormationEvent
import work.art1st.javarailways.simpleato.timetable.TimetableFormationOutput
import work.art1st.javarailways.simpleato.timetable.FormationOutputDisposition
import work.art1st.javarailways.simpleato.api.depot.*
import work.art1st.javarailways.simpleato.timetable.TimetableAction
import work.art1st.javarailways.simpleato.timetable.TimetableLineSpeedLimit
import work.art1st.javarailways.simpleato.timetable.TimetableService
import work.art1st.javarailways.simpleato.timetable.TimetableServiceRunState
import work.art1st.javarailways.simpleato.timetable.TimetableStep
import work.art1st.javarailways.simpleato.timetable.TrainSelector
import work.art1st.javarailways.simpleato.timetable.simulation.IsolatedTimetableSimulationRequest
import work.art1st.javarailways.simpleato.timetable.simulation.IsolatedTimetableSimulationResult
import work.art1st.javarailways.simpleato.timetable.TimetableValidator
import work.art1st.javarailways.simpleato.SimpleATOPlugin
import work.art1st.javarailways.simpleato.SimpleATOWorldData
import work.art1st.javarailways.simpleato.api.ato.AtoWorldCoordinator
import work.art1st.javarailways.simpleato.simpleAtoWorldData
import work.art1st.javarailways.simpleato.inspection.InspectionRuntime
import work.art1st.javarailways.simpleato.restriction.TrainRestrictionSnapshotStore
import work.art1st.javarailways.simpleato.signal.SignalEpochRuntime
import work.art1st.javarailways.simpleato.timetable.InfrastructureDependencyService
import work.art1st.javarailways.simpleato.timetable.TimetableDispatcher
import work.art1st.javarailways.simpleato.timetable.TimetableRouteAdmissionContextProvider
import work.art1st.javarailways.bukkit.config.model.CarControlConfig
import work.art1st.javarailways.bukkit.behavior.CapturedCarBehavior
import work.art1st.javarailways.bukkit.config.model.MotorControlConfig
import work.art1st.javarailways.bukkit.config.model.TrainControlConfig
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.createTestWorld
import work.art1st.javarailways.core.physics.train.createTrainWithConfig
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureDependencyMutationResult
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureDependencyMutationStatus
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureDependencyRequest
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureOwner
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.simpleato.api.timetable.TimetableConditionProvider
import work.art1st.javarailways.simpleato.api.timetable.TimetableConditionResult
import work.art1st.javarailways.simpleato.api.timetable.TimetableConditionSimulationFactory
import work.art1st.javarailways.simpleato.api.timetable.TimetableConditionSimulationPreparation
import work.art1st.javarailways.simpleato.api.timetable.TimetableExtensionDescriptor
import work.art1st.javarailways.simpleato.api.timetable.TimetableRuntimeContext

class IsolatedTimetableSimulationFactoryTest {
    @Test
    fun `line measurement accepts real door completion at deadline but rejects one tick later`() {
        val source = createTestWorld()
        registerWorldData(source)
        try {
            val rail = createStraightRailFromSegments(source, PrimeCurveGenerator.buildStraight(300.0, 1))
            listOf("a" to 30.0, "b" to 150.0, "c" to 270.0).forEach { (id, arc) ->
                OperationalDestinationService(source).register(OperationalDestination("$id@one", rail.id, arc,
                    ViewDirection.AB, DestinationRole.StationBerthRole(StationId(id), berthId = "one",
                        doorSide = if (id == "b") DoorSide.LEFT else DoorSide.NONE, usableLengthMeters = 30.0)))
            }
            val line = LineId("doors")
            source.simpleAtoWorldData.lines.put(Line(line, listOf("a", "b", "c").map(::StationId),
                listOf("c", "b", "a").map(::StationId), maximumSpeedMetersPerSecond = 6.0), 0L)
            val calls = listOf("a" to "ORIGIN", "b" to "STOP", "c" to "TERMINAL").map { (id, kind) ->
                LineMeasurementCall("call-$id", id, "$id@one", kind, emptyList())
            }
            val request = LineMeasurementRequest("fixture", 100, 400, 400, calls, 10_000)
            fun program(id: String, role: String, lead: Long) = work.art1st.javarailways.simpleato.timetable.StationStopPrograms(
                id, "call-$id", work.art1st.javarailways.simpleato.timetable.StationStopRole.valueOf(role),
                listOf(work.art1st.javarailways.simpleato.timetable.StationProgramAction.OpenDoors,
                    work.art1st.javarailways.simpleato.timetable.StationProgramAction.End),
                listOf(work.art1st.javarailways.simpleato.timetable.StationProgramAction.CloseDoors,
                    work.art1st.javarailways.simpleato.timetable.StationProgramAction.End),
                work.art1st.javarailways.simpleato.timetable.StationPreparationLead.constant(lead),
                if (role == "TERMINAL") null else 100, "$id@one", if (id == "b") DoorSide.LEFT else DoorSide.NONE, 0)
            fun timetable(lead: Long): Timetable {
                val steps = listOf(TimetableStep.Check("a@one"),
                    TimetableStep.At(0, TimetableAction.BeginStationStop(program("a", "ORIGIN", 0))),
                    TimetableStep.At(100, TimetableAction.DestinationOptions("b@one", stopPrograms = program("b", "STOP", lead))),
                    TimetableStep.At(100, TimetableAction.GoTo("b@one")),
                    TimetableStep.At(100, TimetableAction.DestinationOptions("c@one", stopPrograms = program("c", "TERMINAL", 0))),
                    TimetableStep.At(100, TimetableAction.GoTo("c@one")))
                return Timetable("door-measurement", services = listOf(TimetableService("duty", TrainSelector.TrainId("scheduled"), steps,
                    lineId = line, runSegments = listOf(work.art1st.javarailways.simpleato.timetable.TimetableRunSegment("run", line, 0, steps.size, true,
                        calls = calls.map { work.art1st.javarailways.simpleato.timetable.TimetableRunCall(it.callId, it.stationId,
                            it.destinationId, work.art1st.javarailways.simpleato.timetable.TimetableServiceStopStatus.valueOf(it.kind)) })))),
                    lineSpeedLimits = listOf(TimetableLineSpeedLimit(line, 6.0)))
            }
            fun animation(seconds: Double) = work.art1st.javarailways.bukkit.render.Animation(listOf(
                work.art1st.javarailways.bukkit.render.Animation.AnimationCommand.INITIAL_STATE,
                work.art1st.javarailways.bukkit.render.Animation.AnimationCommand(
                    work.art1st.javarailways.bukkit.render.model.CompactTransform(), seconds)))
            val actions = listOf("door-left-open" to "open", "door-left-close" to "close").associate { (name, animation) ->
                name to work.art1st.javarailways.bukkit.config.model.TrainActionConfig(name, animations = listOf(
                    work.art1st.javarailways.bukkit.config.model.TrainActionAnimationConfig(animation)))
            }
            val behavior = CapturedCarBehavior(null, actions, listOf(mapOf("open" to animation(0.0), "close" to animation(0.1))))
            val profile = profile().let { it.copy(cars = it.cars.map { car -> car.copy(behavior = behavior) }) }
            work.art1st.javarailways.bukkit.render.model.ModelRuntimeTestSupport.bootstrap()
            val factory = IsolatedTimetableSimulationFactory(IsolatedSimulationProfileResolver { _, _, _ -> profile },
                9.80665, extensionPlugin = BasicPlugin.INSTANCE)
            fun capture(lead: Long): IsolatedTimetableSimulationSnapshot {
                val normalized = TimetableScriptNormalizer.normalize(
                    work.art1st.javarailways.simpleato.persistence.SavedTimetableV1.capture(timetable(lead)), source.simpleAtoWorldData.destinations)
                return factory.capture(source, prepareLineMeasurement(normalized, request).restore(),
                    listOf(TimetableProfileSelection("duty", SimulationProfileType.TRAIN_TEMPLATE, "template")))
            }
            val result = IsolatedLineSimulator.simulate(factory, capture(2), request, 1)
            assertEquals(400L, result.calls[1].departureTick!! - result.calls[1].arrivalTick!!)
            val rejected = assertThrows(LineDwellInsufficientException::class.java) {
                IsolatedLineSimulator.simulate(factory, capture(1), request, 1)
            }
            assertTrue(rejected.message!!.contains("b/call-b"), rejected.message)
            val times = requireNotNull(Regex("目标发车 (\\d+) Tick，站内作业完成 (\\d+) Tick").find(rejected.message!!))
            assertEquals(1L, times.groupValues[2].toLong() - times.groupValues[1].toLong(), rejected.message)
        } finally {
            source.physicsCoordinator.close()
            (source.tickManager as AutoCloseable).close()
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = ["normal", "reverse", "blocked"])
    fun `line measurement uses real continuous execution and its integer calls replay exactly`(scenario: String) {
        val reverseOrigin = scenario == "reverse"
        val source = createTestWorld()
        registerWorldData(source)
        try {
            val rail = createStraightRailFromSegments(source, PrimeCurveGenerator.buildStraight(400.0, 1))
            source.basicWorldData.railMetadata.put(rail.id, work.art1st.javarailways.basic.rail.RailInfrastructureMetadata(
                speedLimits = work.art1st.javarailways.basic.rail.SpeedLimitMetadata(listOf(
                    work.art1st.javarailways.basic.rail.SpeedLimitEntry(10.0, work.art1st.javarailways.basic.rail.SpeedLimitType.SUBGRADE)))))
            listOf("a" to 30.0, "b" to 100.0, "c" to 200.0, "d" to 320.0).forEach { (id, arc) ->
                OperationalDestinationService(source).register(OperationalDestination("$id@one", rail.id, arc,
                    if (id == "a" && reverseOrigin) ViewDirection.BA else ViewDirection.AB,
                    DestinationRole.StationBerthRole(StationId(id), berthId = "one",
                        doorSide = DoorSide.NONE, usableLengthMeters = 30.0)))
            }
            val line = LineId("measurement")
            source.simpleAtoWorldData.lines.put(Line(line, listOf("a", "b", "c", "d").map(::StationId),
                listOf("d", "c", "b", "a").map(::StationId), maximumSpeedMetersPerSecond = 6.0), 0L)
            val calls = listOf("a" to "ORIGIN", "b" to "PASS", "c" to "STOP", "d" to "TERMINAL").map { (id, kind) ->
                LineMeasurementCall("call-$id", id, "$id@one", kind, emptyList())
            }
            val request = LineMeasurementRequest("fixture", 100, 60, 40, calls, 10_000)
            fun program(id: String, role: String, departure: Long?) = work.art1st.javarailways.simpleato.timetable.StationStopPrograms(
                id, "call-$id", work.art1st.javarailways.simpleato.timetable.StationStopRole.valueOf(role),
                listOf(work.art1st.javarailways.simpleato.timetable.StationProgramAction.End),
                listOf(work.art1st.javarailways.simpleato.timetable.StationProgramAction.End),
                work.art1st.javarailways.simpleato.timetable.StationPreparationLead(kotlinx.serialization.json.buildJsonObject {
                    put("value", kotlinx.serialization.json.JsonPrimitive(0))
                }), departure, "$id@one", DoorSide.NONE, 0)
            val steps = listOf(TimetableStep.Check("a@one"),
                TimetableStep.At(0, TimetableAction.BeginStationStop(program("a", "ORIGIN", 100))),
                TimetableStep.At(100, TimetableAction.DestinationOptions("c@one", stoppingOffsetMetersBefore = 0.1,
                    stopPrograms = program("c", "STOP", 100))),
                TimetableStep.At(100, TimetableAction.GoVia(listOf("b@one", "c@one"))),
                TimetableStep.At(100, TimetableAction.DestinationOptions("d@one", stoppingOffsetMetersBefore = 2.0,
                    stopPrograms = program("d", "TERMINAL", null))),
                TimetableStep.At(100, TimetableAction.GoTo("d@one")))
            val timetable = Timetable("line-test", services = listOf(TimetableService("duty", TrainSelector.TrainId("scheduled"), steps,
                lineId = line, runSegments = listOf(work.art1st.javarailways.simpleato.timetable.TimetableRunSegment("run", line, 0, steps.size, true,
                    calls = calls.map { work.art1st.javarailways.simpleato.timetable.TimetableRunCall(it.callId, it.stationId,
                        it.destinationId, work.art1st.javarailways.simpleato.timetable.TimetableServiceStopStatus.valueOf(it.kind)) })))),
                lineSpeedLimits = listOf(TimetableLineSpeedLimit(line, 6.0)))
            val original = TimetableScriptNormalizer.normalize(
                work.art1st.javarailways.simpleato.persistence.SavedTimetableV1.capture(timetable), source.simpleAtoWorldData.destinations)
            val measured = prepareLineMeasurement(original, request, reverseOrigin)
            assertEquals(1, measured.operationalTrips.size)
            assertTrue(measured.operationalTrips.single().segments.single().unassignedUnits)
            assertEquals(original.operationalTrips, measured.operationalTrips)
            work.art1st.javarailways.bukkit.render.model.ModelRuntimeTestSupport.bootstrap()
            val feature = work.art1st.javarailways.bukkit.signal.SimpleSignalsSimulationFeatureFactory.fourLightFeature(
                java.nio.file.Path.of("../SimpleSignals/examples/jr-four-light/signal-packs"), listOf(rail.id), listOf(400.0), 500.0)
            val targetWorlds = linkedMapOf<String, CoreWorld>()
            source.simpleAtoWorldData.timetableExtensions.registerCondition(object : TimetableConditionProvider,
                TimetableConditionSimulationFactory {
                override val typeId = "test:line-target"
                override val version = 1
                override val descriptor = TimetableExtensionDescriptor("Target", "Test fixture world capture")
                override fun evaluate(parameters: kotlinx.serialization.json.JsonObject, context: TimetableRuntimeContext) = TimetableConditionResult.True
                override fun prepareSimulation() = TimetableConditionSimulationPreparation { target ->
                    val world = target as CoreWorld
                    targetWorlds[world.runtimeId] = world
                    object : TimetableConditionProvider {
                        override val typeId = "test:line-target"
                        override val version = 1
                        override val descriptor = TimetableExtensionDescriptor("Target", "Test fixture world capture")
                        override fun evaluate(parameters: kotlinx.serialization.json.JsonObject,
                            context: TimetableRuntimeContext) = TimetableConditionResult.True
                    }
                }
            })
            var blockRoute = false
            val heldMessages = arrayListOf<String>()
            val releasedAt = arrayListOf<Long>()
            val blockingFeature = object : work.art1st.javarailways.simpleato.api.simulation.PreparedSimulationFeature {
                override val id = feature.id
                override fun install(target: work.art1st.javarailways.simpleato.api.simulation.SimulationDomainTarget): AutoCloseable {
                    val signalToken = feature.install(target)
                    if (!blockRoute) return signalToken
                    val world = targetWorlds.getValue(target.worldId.toString())
                    val token = Any()
                    val owner = InfrastructureOwner("test:line-blocker", 1)
                    val registry = world.infrastructureCoordination
                    val claim = work.art1st.javarailways.core.physics.infrastructure.InfrastructureClaimRequest(owner,
                        work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureFootprint(listOf(
                            work.art1st.javarailways.core.api.physics.infrastructure.RailIntervalResource(rail.id, 31.0, 80.0))))
                    assertTrue(registry.acquireClaim(token, claim).successful)
                    var held = true
                    val clock = object : work.art1st.javarailways.core.api.util.tick.TickingObject,
                        work.art1st.javarailways.core.api.util.tick.Synchronizable {
                        override fun tick(deltaT: Double) = Unit
                        override fun commit() {
                            if (!held) return
                            val tick = world.latestWorldFrame().physicsEpoch
                            world.simpleAtoWorldData.dispatcher.statuses().forEach { status ->
                                if (status.message?.contains("RESOURCE_CONFLICT") == true) {
                                    heldMessages += status.message!!
                                    assertFalse(status.routeGrantActive)
                                    assertEquals(30.0, world.allTrains.single().publishedFrame.head.arcLengthInAbMeters)
                                    assertTrue(registry.claimSnapshots().any { it.owner == owner })
                                }
                            }
                            if (tick == 400L) world.physicsCoordinator.withStructuralMutation {
                                assertTrue(registry.releaseClaim(token, owner).successful)
                                held = false
                                releasedAt += tick
                            }
                        }
                    }
                    world.tickManager.register(clock)
                    return AutoCloseable {
                        world.tickManager.unregister(clock)
                        if (held) registry.releaseClaim(token, owner)
                        signalToken.close()
                    }
                }
            }
            val factory = IsolatedTimetableSimulationFactory(IsolatedSimulationProfileResolver { _, _, _ -> profile() }, 9.80665,
                requiredDomainFeatures = { setOf("simplesignals") }, prepareDomainFeatures = { listOf(blockingFeature) },
                extensionPlugin = BasicPlugin.INSTANCE)
            fun capture(saved: work.art1st.javarailways.simpleato.persistence.SavedTimetableV1) = factory.capture(source, saved.restore(),
                listOf(TimetableProfileSelection("duty", SimulationProfileType.TRAIN_TEMPLATE, "template")))
            val unblocked = if (scenario == "blocked") IsolatedLineSimulator.simulate(factory, capture(measured), request, 1) else null
            blockRoute = scenario == "blocked"
            val result = IsolatedLineSimulator.simulate(factory, capture(measured), request, 1)
            if (unblocked != null) {
                assertEquals(listOf(400L), releasedAt)
                assertTrue(heldMessages.isNotEmpty(), "real admission must reject the held Core resource")
                assertEquals(request.firstDepartureTick, result.calls.first().departureTick)
                assertTrue(result.calls[1].arrivalTick!! >= unblocked.calls[1].arrivalTick!! + 300,
                    "target-departure to arrival must include resource waiting")
                assertEquals((result.calls[1].arrivalTick!! - request.firstDepartureTick) / 20.0,
                    result.legs.first().travelSeconds)
            }
            assertEquals(calls.map { it.callId }, result.calls.map { it.callId })
            assertEquals(60L, result.calls[2].departureTick!! - result.calls[2].arrivalTick!!)
            assertNull(result.legs[0].stoppingErrorMeters)
            assertTrue(kotlin.math.abs(result.legs[1].stoppingErrorMeters!!) <= 0.3,
                "stopping error must be measured relative to the actual offset target")
            assertTrue(kotlin.math.abs(result.legs.last().stoppingErrorMeters!!) <= 0.3,
                "terminal 2m offset must be retained by actual execution")
            assertTrue(result.legs.all { it.lengthMeters > 0 && it.peakSpeedMetersPerSecond > 0 })
            val stopDeparture = result.calls[2].departureTick!!
            val replaySteps = measured.services.single().steps.mapIndexed { index, step ->
                step.copy(tick = if (index >= 4 + if (reverseOrigin) 1 else 0) stopDeparture else step.tick, action = step.action?.let { action ->
                    action.copy(stopPrograms = action.stopPrograms?.let { p -> p.copy(
                        minimumDwellTicks = if (p.role == "STOP") request.minimumDwellTicks else 0,
                        plannedDepartureTick = if (p.role == "STOP") stopDeparture else p.plannedDepartureTick) })
                })
            }
            val replay = measured.copy(services = listOf(measured.services.single().copy(steps = replaySteps)))
            factory.create(capture(replay), 1).use { sim ->
                val train = sim.world.allTrains.single()
                val placed = train.publishedFrame
                assertEquals(if (reverseOrigin) ViewDirection.BA else ViewDirection.AB, placed.head.facing)
                assertEquals(30.0, placed.head.arcLengthInAbMeters)
                assertFalse(placed.reversed, "unique placement must not pre-reverse or re-seed the train")
                var sawReversal = false
                val arrivals = arrayListOf<Long>()
                val arrivalErrors = linkedMapOf<String, Double>()
                val subscription = sim.world.simpleAtoWorldData.dispatcher.observeWaypointProgress(
                    work.art1st.javarailways.simpleato.timetable.TimetableServiceRunId("line-test", "duty")) {
                    if (it.pass) arrivals += it.timetableElapsedTicks
                }
                val readySubscription = sim.world.simpleAtoWorldData.dispatcher.observeStationProgramReady(
                    work.art1st.javarailways.simpleato.timetable.TimetableServiceRunId("line-test", "duty")) {
                    if (it.role != work.art1st.javarailways.simpleato.timetable.StationStopRole.ORIGIN) arrivals += it.arrivedTick
                }
                try {
                    val completed = sim.simulate(IsolatedTimetableSimulationRequest(replay.restore(), 1200, 10_000),
                        afterTick = { tick ->
                            val publication = (train.controller as work.art1st.javarailways.simpleato.controller.SimpleATO).publishedState
                            if (sim.world.simpleAtoWorldData.dispatcher.statuses().single().state == TimetableServiceRunState.BERTHED) {
                                publication.currentStationCall?.let { call ->
                                    arrivalErrors.putIfAbsent(call.call.stationId.value,
                                        requireNotNull(publication.runtimeDiagnostics.stationPositionErrorMeters))
                                }
                            }
                            if (reverseOrigin && !sawReversal && train.publishedFrame.reversed) {
                                sawReversal = true
                                assertTrue(tick < request.firstDepartureTick)
                                val reversed = train.publishedFrame
                                assertEquals(ViewDirection.AB, reversed.head.facing)
                                assertEquals(placed.tail.arcLengthInAbMeters, reversed.head.arcLengthInAbMeters)
                                assertEquals(placed.head.arcLengthInAbMeters, reversed.tail.arcLengthInAbMeters)
                                assertEquals(placed.occupiedIntervals.toSet(), reversed.occupiedIntervals.toSet())
                            }
                        })
                    assertTrue(completed is IsolatedTimetableSimulationResult.Completed, completed.toString())
                    assertEquals(result.calls.drop(1).map { it.arrivalTick }, arrivals)
                    assertEquals(listOf("c", "d"), arrivalErrors.keys.toList())
                    assertEquals(arrivalErrors.values.toList(), result.legs.drop(1).map { it.stoppingErrorMeters },
                        "measured StationCall errors must reuse the exact BERTHED publication, not another target calculation")
                    assertEquals(reverseOrigin, sawReversal)
                    if (unblocked != null) assertEquals(listOf(400L, 400L), releasedAt)
                } finally { readySubscription.close(); subscription.close() }
            }
            assertTrue(source.allTrains.isEmpty())
            assertEquals(0L, original.services.single().steps[2].action!!.stopPrograms!!.minimumDwellTicks)
            assertThrows(LineDwellInsufficientException::class.java) { request.copy(fixedDwellTicks = 39) }
            val tooLongSteps = measured.services.single().steps.map { step ->
                val action = step.action
                if (action?.stopPrograms?.role != "STOP") step else step.copy(action = action.copy(
                    stopPrograms = action.stopPrograms!!.copy(arrival = listOf(
                        work.art1st.javarailways.simpleato.persistence.SavedStationProgramAction(
                            work.art1st.javarailways.simpleato.persistence.SavedStationProgramActionType.WAIT_DURATION, durationTicks = 100),
                        work.art1st.javarailways.simpleato.persistence.SavedStationProgramAction(
                            work.art1st.javarailways.simpleato.persistence.SavedStationProgramActionType.END)))))
            }
            val tooLong = measured.copy(services = listOf(measured.services.single().copy(steps = tooLongSteps)))
            val rejected = assertThrows(LineDwellInsufficientException::class.java) {
                IsolatedLineSimulator.simulate(factory, capture(tooLong), request, 1)
            }
            assertTrue(rejected.message!!.contains("c/call-c"), rejected.message)
            assertThrows(IllegalStateException::class.java) {
                IsolatedLineSimulator.simulate(factory, capture(measured), request.copy(maximumDurationTicks = 1), 1)
            }
            if (scenario == "normal") {
                // Known Mission continuation limitation: a larger intermediate offset must not
                // produce successful timings when actual execution cannot reach the next call.
                val offsetSteps = measured.services.single().steps.map { step ->
                    if (step.action?.stopPrograms?.role != "STOP") step else step.copy(
                        action = step.action!!.copy(stoppingOffsetMetersBefore = 2.0))
                }
                val offsetInput = measured.copy(services = listOf(measured.services.single().copy(steps = offsetSteps)))
                val offsetFailure = assertThrows(IllegalStateException::class.java) {
                    IsolatedLineSimulator.simulate(factory, capture(offsetInput), request, 1)
                }
                assertEquals("train simulation/line-test/duty is not facing the planned route from c@one", offsetFailure.message)
            }
            source.basicWorldData.railMetadata.put(rail.id, work.art1st.javarailways.basic.rail.RailInfrastructureMetadata(
                speedLimits = work.art1st.javarailways.basic.rail.SpeedLimitMetadata(listOf(
                    work.art1st.javarailways.basic.rail.SpeedLimitEntry(2.0, work.art1st.javarailways.basic.rail.SpeedLimitType.SUBGRADE)))))
            val slow = IsolatedLineSimulator.simulate(factory, capture(measured), request, 1)
            assertTrue(slow.calls.last().arrivalTick!! > result.calls.last().arrivalTick!! + 500,
                "real local rail speed restriction must affect the measured line, not merely its line-wide cap")
        } finally {
            source.physicsCoordinator.close()
            (source.tickManager as AutoCloseable).close()
        }
    }

    @Test
    fun `capture rejects missing profile assignment and propagates unknown profile without fallback`() {
        val source = createTestWorld()
        registerWorldData(source)
        try {
            val timetable = Timetable("missing-profile", services = listOf(TimetableService("duty",
                TrainSelector.TrainId("scheduled"), listOf(TimetableStep.Check("origin")))))
            val unknown = IllegalArgumentException("world train not found: absent")
            var resolutions = 0
            val factory = IsolatedTimetableSimulationFactory(IsolatedSimulationProfileResolver { _, type, id ->
                resolutions++
                assertEquals(SimulationProfileType.WORLD_TRAIN, type)
                assertEquals("absent", id)
                throw unknown
            }, 9.80665, extensionPlugin = BasicPlugin.INSTANCE)
            val missing = assertThrows(IllegalArgumentException::class.java) {
                factory.capture(source, timetable, emptyList())
            }
            assertEquals("missing simulation profiles for services: duty", missing.message)
            assertEquals(0, resolutions)
            assertSame(unknown, assertThrows(IllegalArgumentException::class.java) {
                factory.capture(source, timetable, listOf(TimetableProfileSelection("duty", SimulationProfileType.WORLD_TRAIN, "absent")))
            })
            assertEquals(1, resolutions)
            assertTrue(source.allTrains.isEmpty())
        } finally {
            source.physicsCoordinator.close()
            (source.tickManager as AutoCloseable).close()
        }
    }

    @BeforeEach
    fun initializePlugin() {
        if (BasicPlugin.instanceOrNull() == null) BasicPlugin.INSTANCE = BasicPlugin(101)
        if (SimpleATOPlugin.instanceOrNull() == null) {
            SimpleATOPlugin.INSTANCE = SimpleATOPlugin(102)
        }
        work.art1st.javarailways.core.api.util.plugin.PluginRegistry.register(BasicPlugin.INSTANCE)
        work.art1st.javarailways.core.api.util.plugin.PluginRegistry.register(SimpleATOPlugin.INSTANCE)
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = [false, true])
    fun `standalone call places once in configured direction and optional reversal preserves its occupied rails`(reverseTrain: Boolean) {
        val source = createTestWorld()
        registerWorldData(source)
        try {
            val rail = createStraightRailFromSegments(source, PrimeCurveGenerator.buildStraight(300.0, 1))
            source.basicWorldData.operatingDirections.put(rail.id, ViewDirection.AB)
            // The waypoint's own direction wins even when the rail has another direction.
            OperationalDestinationService(source).register(OperationalDestination("origin", rail.id, 100.0,
                ViewDirection.BA, DestinationRole.Regular))
            val original = source.simpleAtoWorldData.destinations["origin"]
            val resolved = resolveProgramSimulationWayPoint(source, "origin")
            assertEquals(ViewDirection.BA, resolved.direction)
            assertEquals("WAYPOINT", resolved.directionSource)
            val timetable = standaloneCall(listOf(TimetableAction.Stop(7)), startTick = 17, reverseTrain = reverseTrain)
            val factory = testSimulationFactory()
            assertThrows(IllegalArgumentException::class.java) { TimetableValidator(source, standaloneServices()) }
            // This exception does not relax ordinary live timetable validation.
            assertFalse(TimetableValidator(source).validate(timetable).isSuccess)
            val snapshot = factory.capture(source, timetable, templateSelection(), standaloneServices())
            factory.create(snapshot, 2).use { simulation ->
                val train = simulation.world.allTrains.single()
                val initial = train.publishedFrame
                assertEquals(ViewDirection.BA, initial.head.facing)
                assertEquals(100.0, initial.head.arcLengthInAbMeters, 1e-7)
                assertTrue(initial.tail.arcLengthInAbMeters > 100.0)
                assertEquals(original, simulation.world.simpleAtoWorldData.destinations["origin"])
                val result = simulation.simulate(IsolatedTimetableSimulationRequest(timetable, maximumSimulationTicks = 100))
                assertTrue(result is IsolatedTimetableSimulationResult.Completed) { result.toString() }
                val run = result.runs.single()
                assertEquals("origin", run.currentWayPointId)
                val timing = run.taskTimings.single()
                assertTrue(timing.startTick >= 17L)
                assertEquals(7L, timing.endTick!! - timing.startTick)
                val finished = train.publishedFrame
                assertEquals(reverseTrain, finished.reversed)
                assertEquals(if (reverseTrain) ViewDirection.AB else ViewDirection.BA, finished.head.facing)
                assertEquals(if (reverseTrain) initial.tail.arcLengthInAbMeters else initial.head.arcLengthInAbMeters,
                    finished.head.arcLengthInAbMeters, 1e-7)
                assertEquals(if (reverseTrain) initial.head.arcLengthInAbMeters else initial.tail.arcLengthInAbMeters,
                    finished.tail.arcLengthInAbMeters, 1e-7)
                assertEquals(initial.occupiedIntervals.toSet(), finished.occupiedIntervals.toSet())
            }
            assertTrue(source.allTrains.isEmpty())
            assertSame(original, source.simpleAtoWorldData.destinations["origin"])
            assertEquals(ViewDirection.AB, source.basicWorldData.operatingDirections[rail.id])
        } finally {
            source.physicsCoordinator.close()
            (source.tickManager as AutoCloseable).close()
        }
    }

    @Test
    fun `explicit initial reversal moves and reverses back to the unchanged original directed waypoint`() {
        val source = createTestWorld()
        registerWorldData(source)
        try {
            val rail = createStraightRailFromSegments(source, PrimeCurveGenerator.buildStraight(300.0, 1))
            OperationalDestinationService(source).register(OperationalDestination("origin", rail.id, 100.0,
                ViewDirection.AB, DestinationRole.Regular))
            OperationalDestinationService(source).register(OperationalDestination("west", rail.id, 50.0,
                ViewDirection.BA, DestinationRole.Regular))
            val timetable = standaloneCall(listOf(TimetableAction.GoTo("west"), TimetableAction.Reverse,
                TimetableAction.GoTo("origin")), reverseTrain = true)
            val factory = testSimulationFactory()
            val snapshot = factory.capture(source, timetable, templateSelection(),
                standaloneServices())
            factory.create(snapshot, 2).use { simulation ->
                val result = simulation.simulate(IsolatedTimetableSimulationRequest(timetable, delayThresholdTicks = 20_000))
                assertTrue(result is IsolatedTimetableSimulationResult.Completed) { result.toString() }
                val train = simulation.world.allTrains.single()
                assertEquals(ViewDirection.AB, train.publishedFrame.head.facing)
                assertEquals(100.0, train.publishedFrame.head.arcLengthInAbMeters, 0.25)
                assertEquals("origin", result.runs.single().currentWayPointId)
                assertEquals(ViewDirection.AB, simulation.world.simpleAtoWorldData.destinations["origin"]!!.approachDirection)
                assertEquals(setOf("origin", "west"), simulation.world.basicWorldData.wayPoints.all().map { it.id }.toSet())
            }
            assertTrue(source.allTrains.isEmpty())
        } finally {
            source.physicsCoordinator.close()
            (source.tickManager as AutoCloseable).close()
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = [false, true])
    fun `a destination behind the initial head requires the explicit initial reversal`(reverseTrain: Boolean) {
        val source = createTestWorld()
        registerWorldData(source)
        try {
            val rail = createStraightRailFromSegments(source, PrimeCurveGenerator.buildStraight(300.0, 1))
            OperationalDestinationService(source).register(OperationalDestination("origin", rail.id, 100.0,
                ViewDirection.AB, DestinationRole.Regular))
            OperationalDestinationService(source).register(OperationalDestination("behind", rail.id, 40.0,
                ViewDirection.BA, DestinationRole.Regular))
            val timetable = standaloneCall(listOf(TimetableAction.GoTo("behind")), reverseTrain = reverseTrain)
            val factory = testSimulationFactory()
            factory.create(factory.capture(source, timetable, templateSelection(), standaloneServices()), 2).use { simulation ->
                val train = simulation.world.allTrains.single()
                val initial = train.publishedFrame
                val result = simulation.simulate(IsolatedTimetableSimulationRequest(timetable))
                if (reverseTrain) {
                    assertTrue(result is IsolatedTimetableSimulationResult.Completed) { result.toString() }
                    assertTrue(train.isReversed)
                    assertEquals(ViewDirection.BA, train.publishedFrame.head.facing)
                    assertEquals(40.0, train.publishedFrame.head.arcLengthInAbMeters, 0.25)
                } else {
                    assertTrue(result is IsolatedTimetableSimulationResult.Failed) { result.toString() }
                    assertFalse(train.isReversed)
                    assertEquals(initial.head, train.publishedFrame.head)
                    assertEquals(initial.occupiedIntervals, train.publishedFrame.occupiedIntervals)
                    assertEquals(0.0, train.publishedFrame.speedMetersPerSecond)
                }
            }
            assertTrue(source.allTrains.isEmpty())
        } finally {
            source.physicsCoordinator.close()
            (source.tickManager as AutoCloseable).close()
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = [false, true])
    fun `depot stabling starts resolve direction and explicit reversal obeys rail restrictions`(reverseTrain: Boolean) {
        val source = createTestWorld()
        registerWorldData(source)
        try {
            val unrestrictedRail = createStraightRailFromSegments(source, PrimeCurveGenerator.buildStraight(300.0, 1))
            val restrictedRail = createStraightRailFromSegments(source, PrimeCurveGenerator.buildStraight(300.0, 1))
            source.basicWorldData.operatingDirections.put(restrictedRail.id, ViewDirection.AB)
            val service = OperationalDestinationService(source)
            for ((pointId, rail, ownDirection) in listOf(
                Triple("own", unrestrictedRail, ViewDirection.BA),
                Triple("rail", restrictedRail, null)
            )) {
                val id = "home@$pointId"
                service.register(OperationalDestination(id, rail.id,
                    if (pointId == "own") 100.0 else 200.0, ownDirection,
                    DestinationRole.DepotStablingPointRole("home", pointId, "Home depot", usableLengthMeters = 30.0)))
                val original = source.simpleAtoWorldData.destinations[id]
                val sourcePoints = source.simpleAtoWorldData.depots.stablingPoints(DepotId("home"))
                assertEquals(id, source.basicWorldData.wayPoints[id]!!.id)
                assertTrue(sourcePoints.any { it.point.destinationId == id })
                // Point direction wins for seeding; it does not override permission to reverse.
                if (ownDirection != null) source.basicWorldData.operatingDirections.put(rail.id, ViewDirection.AB)
                val resolved = resolveProgramSimulationWayPoint(source, id)
                assertEquals(ownDirection ?: ViewDirection.AB, resolved.direction)
                assertEquals(if (ownDirection == null) "RAIL" else "WAYPOINT", resolved.directionSource)
                assertEquals(id, resolved.json()["id"]!!.jsonPrimitive.content)
                // Match the development depot: an own-BA point on a rail permitting both directions.
                if (ownDirection != null) source.basicWorldData.operatingDirections.put(rail.id, null)
                val sourceDirections = source.basicWorldData.operatingDirections.entries()
                val timetable = standaloneCall(listOf(TimetableAction.Stop(7)), startTick = 17,
                    wayPointId = id, reverseTrain = reverseTrain)
                val factory = testSimulationFactory()
                val snapshot = factory.capture(source, timetable, templateSelection(), standaloneServices())
                factory.create(snapshot, 2).use { simulation ->
                    val train = simulation.world.allTrains.single()
                    val initial = train.publishedFrame
                    assertEquals(resolved.direction, initial.head.facing)
                    assertEquals(original, simulation.world.simpleAtoWorldData.destinations[id])
                    assertEquals(sourceDirections, simulation.world.basicWorldData.operatingDirections.entries())
                    val result = simulation.simulate(IsolatedTimetableSimulationRequest(timetable, maximumSimulationTicks = 100))
                    val run = result.runs.single()
                    assertEquals(id, run.currentWayPointId)
                    if (ownDirection == null && reverseTrain) {
                        assertTrue(result is IsolatedTimetableSimulationResult.Failed) { result.toString() }
                        val failure = result as IsolatedTimetableSimulationResult.Failed
                        assertEquals("OperatingDirectionViolation", failure.reasonCode)
                        assertTrue(failure.reason.contains("reversed train body violates operating directions")) { failure.reason }
                        assertTrue(run.taskTimings.isEmpty())
                        assertFalse(train.publishedFrame.reversed)
                        assertEquals(initial.head, train.publishedFrame.head)
                        assertEquals(initial.tail, train.publishedFrame.tail)
                        assertEquals(0.0, train.publishedFrame.speedMetersPerSecond)
                    } else {
                        assertTrue(result is IsolatedTimetableSimulationResult.Completed) { result.toString() }
                        val timing = run.taskTimings.single()
                        assertTrue(timing.startTick >= 17L)
                        assertEquals(7L, timing.endTick!! - timing.startTick)
                        assertEquals(reverseTrain, train.publishedFrame.reversed)
                        assertEquals(if (reverseTrain) ViewDirection.AB else resolved.direction, train.publishedFrame.head.facing)
                    }
                    assertEquals(initial.occupiedIntervals.toSet(), train.publishedFrame.occupiedIntervals.toSet())
                }
                assertTrue(source.allTrains.isEmpty())
                assertSame(original, source.simpleAtoWorldData.destinations[id])
                assertEquals(sourcePoints, source.simpleAtoWorldData.depots.stablingPoints(DepotId("home")))
                assertEquals(sourceDirections, source.basicWorldData.operatingDirections.entries())
            }
        } finally {
            source.physicsCoordinator.close()
            (source.tickManager as AutoCloseable).close()
        }
    }

    @Test
    fun `saved directed depot destinations restore Basic mirrors before standalone simulation capture`() {
        val source = createTestWorld()
        registerWorldData(source)
        try {
            val rails = (1..6).map { createStraightRailFromSegments(source, PrimeCurveGenerator.buildStraight(300.0, 1)) }
            work.art1st.javarailways.basic.persistence.BasicInfrastructureSnapshotCodec.decode("""
                {"formatVersion":2,"infrastructureRevision":7,"wayPoints":[],"operatingDirections":[]}
            """).restore(source)
            val destinations = rails.mapIndexed { index, rail -> """
                {"id":"kasaidpt@${index + 1}","railId":"${rail.id}",
                 "arcLengthInAbMeters":100.0,"approachDirection":"BA","roleType":"DEPOT_STABLING_POINT",
                 "depotId":"kasaidpt","stablingPointId":"${index + 1}","depotDisplayName":"Kasai depot","usableLengthMeters":120.0}
            """ }.joinToString(",")
            val saved = work.art1st.javarailways.simpleato.persistence.SimpleATOInfrastructureSnapshotCodec.decode("""
                {"formatVersion":1,"infrastructureRevision":7,"destinations":[$destinations],"lines":[],"lineRevision":0}
            """)
            assertTrue(source.basicWorldData.wayPoints.all().isEmpty())
            saved.restore(source)
            assertTrue(source.basicWorldData.operatingDirections.entries().isEmpty())
            assertEquals((1..6).map { "kasaidpt@$it" }, source.basicWorldData.wayPoints.all().map { it.id })
            for (index in 1..6) {
                val point = resolveProgramSimulationWayPoint(source, "kasaidpt@$index")
                assertEquals("WAYPOINT", point.directionSource)
                assertEquals(ViewDirection.BA, point.direction)
            }
            assertEquals(6, source.simpleAtoWorldData.depots.stablingPoints(DepotId("kasaidpt")).size)
            val timetable = standaloneCall(listOf(TimetableAction.Stop(7)), wayPointId = "kasaidpt@3")
            val factory = testSimulationFactory()
            factory.create(factory.capture(source, timetable, templateSelection(), standaloneServices()), 2).use { simulation ->
                assertEquals(6, simulation.world.basicWorldData.wayPoints.all().size)
                assertEquals(ViewDirection.BA, simulation.world.allTrains.single().publishedFrame.head.facing)
                val result = simulation.simulate(IsolatedTimetableSimulationRequest(timetable, maximumSimulationTicks = 100))
                assertTrue(result is IsolatedTimetableSimulationResult.Completed) { result.toString() }
                val timing = result.runs.single().taskTimings.single()
                assertEquals(7L, timing.endTick!! - timing.startTick)
            }
            assertTrue(source.allTrains.isEmpty())
            assertEquals(6, source.simpleAtoWorldData.depots.stablingPoints(DepotId("kasaidpt")).size)
        } finally {
            source.physicsCoordinator.close()
            (source.tickManager as AutoCloseable).close()
        }
    }

    @Test
    fun `undirected or removed depot stabling points cannot bypass explicit simulation start eligibility`() {
        val source = createTestWorld()
        registerWorldData(source)
        try {
            val rail = createStraightRailFromSegments(source, PrimeCurveGenerator.buildStraight(300.0, 1))
            val service = OperationalDestinationService(source)
            service.register(OperationalDestination("home@one", rail.id, 100.0,
                role = DestinationRole.DepotStablingPointRole("home", "one", usableLengthMeters = 30.0)))
            val timetable = standaloneCall(listOf(TimetableAction.Stop(1)), wayPointId = "home@one")
            val factory = testSimulationFactory()
            assertThrows(IllegalArgumentException::class.java) { resolveProgramSimulationWayPoint(source, "home@one") }
            assertThrows(IllegalArgumentException::class.java) {
                factory.capture(source, timetable, templateSelection(), standaloneServices())
            }
            source.basicWorldData.operatingDirections.put(rail.id, ViewDirection.BA)
            assertEquals(ViewDirection.BA, resolveProgramSimulationWayPoint(source, "home@one").direction)
            service.remove("home@one")
            assertNull(source.basicWorldData.wayPoints["home@one"])
            assertThrows(IllegalArgumentException::class.java) { resolveProgramSimulationWayPoint(source, "home@one") }
            assertThrows(IllegalArgumentException::class.java) {
                factory.capture(source, timetable, templateSelection(), standaloneServices())
            }
            assertTrue(source.allTrains.isEmpty())
        } finally {
            source.physicsCoordinator.close()
            (source.tickManager as AutoCloseable).close()
        }
    }

    @Test
    fun `depot storage after reversal across mixed rail bases uses the retained waypoint direction`() {
        val source = createTestWorld()
        registerWorldData(source)
        try {
            val curves = PrimeCurveGenerator.buildStraight(200.0, 2)
            val tailRail = source.createRailForTest(curves[0].reversed)
            val headRail = source.createRailForTest(curves[1])
            work.art1st.javarailways.core.util.IsolatedSimulationThreadMarker.run {
                work.art1st.javarailways.core.physics.rail.RailView.connect(tailRail.viewBA, headRail.viewBA)
            }
            OperationalDestinationService(source).register(OperationalDestination("start", tailRail.id, 50.0,
                ViewDirection.BA, DestinationRole.Regular))
            OperationalDestinationService(source).register(OperationalDestination("turnback", headRail.id, 5.0,
                ViewDirection.AB, DestinationRole.Regular))
            OperationalDestinationService(source).register(OperationalDestination("home@one", tailRail.id, 50.0,
                ViewDirection.AB, DestinationRole.DepotStablingPointRole("home", "one", usableLengthMeters = 30.0)))
            val timetable = standaloneCall(listOf(TimetableAction.GoTo("turnback"), TimetableAction.Reverse,
                TimetableAction.StoreAtDepot(DepotId("home"))), wayPointId = "start")
            val factory = testSimulationFactory()
            factory.create(factory.capture(source, timetable, templateSelection(), standaloneServices()), 2).use { simulation ->
                val result = simulation.simulate(IsolatedTimetableSimulationRequest(timetable, delayThresholdTicks = 20_000))
                assertTrue(result is IsolatedTimetableSimulationResult.Completed) { result.toString() }
                val train = simulation.world.allTrains.single()
                assertEquals(tailRail.id, train.publishedFrame.head.railId)
                assertEquals(ViewDirection.AB, train.publishedFrame.head.facing)
                assertEquals(50.0, train.publishedFrame.head.arcLengthInAbMeters, 0.25)
                assertTrue(train.isReversed)
            }
            assertTrue(source.allTrains.isEmpty())
        } finally {
            source.physicsCoordinator.close()
            (source.tickManager as AutoCloseable).close()
        }
    }

    @Test
    fun `standalone initialization never relaxes later directed arrivals or pruning of other waypoints`() {
        val source = createTestWorld()
        registerWorldData(source)
        try {
            val rail = createStraightRailFromSegments(source, PrimeCurveGenerator.buildStraight(300.0, 1))
            OperationalDestinationService(source).register(OperationalDestination("origin", rail.id, 100.0,
                ViewDirection.AB, DestinationRole.Regular))
            OperationalDestinationService(source).register(OperationalDestination("east", rail.id, 200.0,
                ViewDirection.AB, DestinationRole.Regular))
            val factory = testSimulationFactory()
            val invalidReturn = standaloneCall(listOf(TimetableAction.GoTo("east"), TimetableAction.Reverse,
                TimetableAction.GoTo("origin")))
            factory.create(factory.capture(source, invalidReturn, templateSelection(), standaloneServices()), 2).use { simulation ->
                val result = simulation.simulate(IsolatedTimetableSimulationRequest(invalidReturn))
                assertTrue(result is IsolatedTimetableSimulationResult.Failed) { result.toString() }
                assertEquals(ViewDirection.AB, simulation.world.simpleAtoWorldData.destinations["origin"]!!.approachDirection)
            }
            source.basicWorldData.wayPoints.add(WayPoint("unlisted", rail.id, 150.0))
            val blocked = standaloneCall(listOf(TimetableAction.GoTo("east")))
            factory.create(factory.capture(source, blocked, templateSelection(), standaloneServices()), 2).use { simulation ->
                val result = simulation.simulate(IsolatedTimetableSimulationRequest(blocked))
                assertTrue(result is IsolatedTimetableSimulationResult.Failed) { result.toString() }
                assertTrue(result.runs.isEmpty())
            }
        } finally {
            source.physicsCoordinator.close()
            (source.tickManager as AutoCloseable).close()
        }
    }

    @Test
    fun `rail direction qualifies a bare point but an undirected point or insufficient track rejects placement`() {
        val source = createTestWorld()
        registerWorldData(source)
        try {
            val rail = createStraightRailFromSegments(source, PrimeCurveGenerator.buildStraight(100.0, 1))
            source.basicWorldData.wayPoints.add(WayPoint("origin", rail.id, 2.0))
            val factory = testSimulationFactory()
            val timetable = standaloneCall(emptyList())
            assertThrows(IllegalArgumentException::class.java) {
                factory.capture(source, timetable, templateSelection(), standaloneServices())
            }
            source.basicWorldData.operatingDirections.put(rail.id, ViewDirection.AB)
            val resolved = resolveProgramSimulationWayPoint(source, "origin")
            assertEquals("RAIL", resolved.directionSource)
            assertEquals(ViewDirection.AB, resolved.direction)
            // Head faces AB at arc2; a10m train would extend beyond the actual rail end.
            val snapshot = factory.capture(source, timetable, templateSelection(), standaloneServices())
            assertThrows(Exception::class.java) { factory.create(snapshot, 2).close() }
            assertTrue(source.allTrains.isEmpty())
            assertEquals(ViewDirection.AB, source.basicWorldData.operatingDirections[rail.id])
        } finally {
            source.physicsCoordinator.close()
            (source.tickManager as AutoCloseable).close()
        }
    }

    @Test
    fun `simulation refuses two complete body paths without relying on the selected turnout branch`() {
        val source = createTestWorld()
        registerWorldData(source)
        try {
            val curves = PrimeCurveGenerator.buildStraight(200.0, 2)
            val headRail = source.createRailForTest(curves[1])
            val firstBranch = source.createRailForTest(curves[0])
            val secondBranch = source.createRailForTest(curves[0])
            work.art1st.javarailways.core.util.IsolatedSimulationThreadMarker.run {
                work.art1st.javarailways.core.physics.rail.RailView.connect(firstBranch.viewAB, headRail.viewBA)
                work.art1st.javarailways.core.physics.rail.RailView.connect(secondBranch.viewAB, headRail.viewBA)
            }
            val selected = headRail.viewBA.nextActiveRail
            OperationalDestinationService(source).register(OperationalDestination("origin", headRail.id, 5.0,
                ViewDirection.AB, DestinationRole.Regular))
            val timetable = standaloneCall(listOf(TimetableAction.Stop(1)))
            val factory = testSimulationFactory()
            val snapshot = factory.capture(source, timetable, templateSelection(), standaloneServices())
            val failure = assertThrows(IllegalStateException::class.java) { factory.create(snapshot, 2).close() }
            assertTrue(failure.message!!.contains("multiple valid placement paths")) { failure.toString() }
            assertSame(selected, headRail.viewBA.nextActiveRail)
            assertTrue(source.allTrains.isEmpty())
        } finally {
            source.physicsCoordinator.close()
            (source.tickManager as AutoCloseable).close()
        }
    }

    @Test
    fun `formation clone preserves reversed canonical unit boundaries under synthetic identity mapping`() {
        val source = createTestWorld()
        registerWorldData(source)
        try {
            val rail = createStraightRailFromSegments(source, PrimeCurveGenerator.buildStraight(300.0, 1))
            val home = DepotId("clone-home")
            OperationalDestinationService(source).register(OperationalDestination("clone-home@one", rail.id, 100.0,
                ViewDirection.AB, DestinationRole.DepotStablingPointRole(home.value, "one")))
            val sourceIds = listOf("car-0", "car-1", "car-2")
            val original = TrainComposition.create(listOf(
                TrainUnitComposition("pair", listOf("car-1", "car-0")),
                TrainUnitComposition("single", listOf("car-2"))), sourceIds)
            val train = createTrainWithConfig("clone-source", rail, source, List(3) { 10.0 }, composition = original)
            source.onOwnerForTest {
                train.setLocation(work.art1st.javarailways.core.physics.rail.RailLocationWithFacing(rail.viewAB, 100.0))
                train.commitMaintenancePlacement()
                val depots = source.simpleAtoWorldData.depots
                depots.registerUnitControlCompatibility("pair", "emu")
                train.reverse()
                val base = profile()
                val profile = base.copy(cars = List(3) { base.cars.single() },
                    carControls = List(3) { base.carControls.single() })
                val (blueprint, _) = SimulationFormationBlueprint.capture(source,
                    TimetableProfileSelection("duty", SimulationProfileType.WORLD_TRAIN, train.id), profile)
                assertEquals(sourceIds.reversed(), blueprint.sourceCarIds)
                val targetIds = listOf("synthetic-0", "synthetic-1", "synthetic-2")
                val mapped = blueprint.compositionFor("synthetic", targetIds)
                assertEquals(listOf("synthetic/unit-1", "synthetic/unit-0"), mapped.units.map { it.unitId })
                assertEquals(listOf("synthetic-1", "synthetic-2"), mapped.units.last().carIds)
                assertFalse(mapped.units.last().isReversedIn(targetIds))
                assertEquals(mapOf("synthetic/unit-0" to "emu"), blueprint.controlCompatibilityFor("synthetic"))
                assertEquals(original, train.composition, "Capture and mapping cannot mutate source unit membership")
                assertEquals(mapped.units.map { it.carIds }, blueprint.compositionFor("another", targetIds).units.map { it.carIds })
                assertTrue(blueprint.compositionFor("another", targetIds).units.none { it.unitId.startsWith("synthetic/") })
            }
        } finally {
            source.physicsCoordinator.close()
            (source.tickManager as AutoCloseable).close()
        }
    }

    @Test
    fun `standalone fixed-unit actions capture exact registered units without requiring a formation event`() {
        val source = createTestWorld()
        registerWorldData(source)
        try {
            val rail = createStraightRailFromSegments(source, PrimeCurveGenerator.buildStraight(300.0, 1))
            val home = DepotId("home")
            OperationalDestinationService(source).register(OperationalDestination("home@one", rail.id, 100.0,
                ViewDirection.AB, DestinationRole.DepotStablingPointRole("home", "one")))
            val train = createTrainWithConfig("reference", rail, source, listOf(10.0),
                composition = TrainComposition.single(listOf("car-0"), "original-unit"))
            source.onOwnerForTest {
                train.setLocation(work.art1st.javarailways.core.physics.rail.RailLocationWithFacing(rail.viewAB, 100.0))
                train.commitMaintenancePlacement()
                val depots = source.simpleAtoWorldData.depots
                depots.registerUnitControlCompatibility("original-unit", "emu")
            }
            val timetable = standaloneCall(listOf(TimetableAction.Extension(
                work.art1st.javarailways.simpleato.api.timetable.TimetableExtensionCall("test:train-info"),
                mapOf("cars" to work.art1st.javarailways.simpleato.timetable.TrainInfoField.CAR_COUNT))),
                wayPointId = "home@one")
            val factory = testSimulationFactory()
            val snapshot = factory.capture(source, timetable,
                listOf(TimetableProfileSelection("function-simulation", SimulationProfileType.WORLD_TRAIN, train.id)),
                standaloneServices(), setOf("function-simulation"))
            factory.create(snapshot, 2).use { simulation ->
                val observed = arrayListOf<kotlinx.serialization.json.JsonObject>()
                simulation.world.simpleAtoWorldData.timetableExtensions.registerAction(object :
                    work.art1st.javarailways.simpleato.api.timetable.TimetableActionProvider {
                    override val typeId = "test:train-info"
                    override val version = 1
                    override val descriptor = TimetableExtensionDescriptor("Cars", "Read actual isolated cars")
                    override fun start(parameters: kotlinx.serialization.json.JsonObject, context: TimetableRuntimeContext):
                        work.art1st.javarailways.simpleato.api.timetable.TimetableExtensionActionStartResult {
                        observed += parameters
                        return work.art1st.javarailways.simpleato.api.timetable.TimetableExtensionActionStartResult.Accepted(
                            object : work.art1st.javarailways.simpleato.api.timetable.TimetableExtensionActionTicket {
                                override fun snapshot() = work.art1st.javarailways.simpleato.api.timetable.TimetableExtensionActionSnapshot(
                                    work.art1st.javarailways.simpleato.api.timetable.TimetableExtensionActionState.SUCCEEDED)
                                override fun cancel() = Unit
                            })
                    }
                })
                val seeded = simulation.world.allTrains.single()
                val units = simulation.world.simpleAtoWorldData.depots.fixedUnitsForFormation(seeded.cars.map { it.id })
                assertEquals(1, units.size)
                assertEquals("emu", units.single().controlCompatibilityKey)
                assertEquals(seeded.composition.units.single().unitId, units.single().id)
                assertEquals(seeded.cars.map { it.id }, units.single().carIds)
                assertFalse(units.single().carIds.any { it in train.cars.map { car -> car.id } })
                val result = simulation.simulate(IsolatedTimetableSimulationRequest(timetable))
                assertTrue(result is IsolatedTimetableSimulationResult.Completed) { result.toString() }
                assertEquals(listOf(kotlinx.serialization.json.buildJsonObject {
                    put("cars", kotlinx.serialization.json.JsonPrimitive(1))
                }), observed)
            }
            assertEquals(listOf("original-unit"), source.simpleAtoWorldData.depots.fixedUnitsForFormation(train.cars.map { it.id }).map { it.id })
            assertThrows(IllegalArgumentException::class.java) {
                factory.capture(source, timetable, templateSelection(), standaloneServices(), setOf("function-simulation"))
            }
        } finally {
            source.physicsCoordinator.close()
            (source.tickManager as AutoCloseable).close()
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(longs = [100, 1_400])
    fun `standalone long wait has an observed duration only when its actual task completes`(maximumTicks: Long) {
        val source = createTestWorld()
        registerWorldData(source)
        try {
            val rail = createStraightRailFromSegments(source, PrimeCurveGenerator.buildStraight(300.0, 1))
            OperationalDestinationService(source).register(OperationalDestination("origin", rail.id, 100.0,
                ViewDirection.AB, DestinationRole.Regular))
            val timetable = standaloneCall(listOf(TimetableAction.Stop(1_300)), startTick = 10)
            val factory = testSimulationFactory()
            val snapshot = factory.capture(source, timetable, templateSelection(),
                standaloneServices())
            factory.create(snapshot, 2).use { simulation ->
                val result = simulation.simulate(IsolatedTimetableSimulationRequest(timetable,
                    delayThresholdTicks = maximumTicks + 1, maximumSimulationTicks = maximumTicks))
                val timing = result.runs.single().taskTimings.single()
                assertEquals(10L, timing.startTick)
                if (maximumTicks == 100L) {
                    assertTrue(result is IsolatedTimetableSimulationResult.PeriodEnded) { result.toString() }
                    assertNull(timing.endTick)
                } else {
                    assertTrue(result is IsolatedTimetableSimulationResult.Completed) { result.toString() }
                    assertEquals(1_310L, timing.endTick)
                }
            }
        } finally {
            source.physicsCoordinator.close()
            (source.tickManager as AutoCloseable).close()
        }
    }

    @Test
    fun `absolute wait past one day compiles then reaches the requested simulation limit`(
        @org.junit.jupiter.api.io.TempDir directory: java.nio.file.Path
    ) {
        val source = createTestWorld()
        registerWorldData(source)
        try {
            val rail = createStraightRailFromSegments(source, PrimeCurveGenerator.buildStraight(300.0, 1))
            OperationalDestinationService(source).register(OperationalDestination("origin", rail.id, 100.0,
                ViewDirection.AB, DestinationRole.Regular))
            val input = ProgramSimulationRequest.parse("""
                {"programId":"p","definition":{"parameters":[],"steps":[
                  {"id":"wait","type":"WAIT_UNTIL","tick":{"value":48000}}]},"arguments":{},
                 "startTick":10,"executionTimeoutMillis":60000,"maximumDurationTicks":100,
                 "profile":{"type":"TRAIN_TEMPLATE","id":"template"},
                 "initialPosition":{"wayPointId":"origin","reverseTrain":false}}
            """)
            TimetableScriptSandbox(directory).use { sandbox ->
                val saved = sandbox.compileSource(input.source(), "standalone")
                val timetable = saved.restore()
                val factory = testSimulationFactory()
                val snapshot = factory.capture(source, timetable, templateSelection(), standaloneServices())
                factory.create(snapshot, 2).use { simulation ->
                    val result = simulation.simulate(IsolatedTimetableSimulationRequest(timetable,
                        delayThresholdTicks = input.maximumSimulationTicks + 1,
                        maximumSimulationTicks = input.maximumSimulationTicks))
                    assertTrue(result is IsolatedTimetableSimulationResult.PeriodEnded) { result.toString() }
                    val json = programSimulationResultJson(result, saved,
                        ProgramSimulationWayPoint("origin", rail.id.toString(), ViewDirection.AB, "WAYPOINT"), false)
                    assertEquals("LIMIT_REACHED", json["status"]!!.jsonPrimitive.content)
                    assertFalse(json.containsKey("durationTicks"))
                }
            }
        } finally {
            source.physicsCoordinator.close()
            (source.tickManager as AutoCloseable).close()
        }
    }

    private fun standaloneCall(actions: List<TimetableAction>, startTick: Long = 0, wayPointId: String = "origin",
        reverseTrain: Boolean = false) =
        Timetable("standalone", services = listOf(TimetableService("function-simulation", steps =
            listOf(TimetableStep.At(startTick, TimetableAction.TakeFromWaypoint(listOf(wayPointId)))) +
                (if (reverseTrain) listOf(TimetableStep.At(startTick, TimetableAction.Reverse)) else emptyList()) +
                TimetableStep.At(startTick, TimetableAction.TaskStart("function-call")) +
                actions.map { TimetableStep.At(startTick, it) } +
                TimetableStep.At(startTick, TimetableAction.TaskEnd("function-call")))))

    private fun standaloneServices() = setOf("function-simulation")

    private fun templateSelection() = listOf(TimetableProfileSelection("function-simulation",
        SimulationProfileType.TRAIN_TEMPLATE, "template"))

    private fun testSimulationFactory() = IsolatedTimetableSimulationFactory(
        IsolatedSimulationProfileResolver { _, _, _ -> profile() }, 9.80665, extensionPlugin = BasicPlugin.INSTANCE)

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = [false, true])
    fun `one physical service continues across stopped main and branch segments with their own speed caps`(returning: Boolean) {
        val source = createTestWorld()
        registerWorldData(source)
        val rail = createStraightRailFromSegments(source, PrimeCurveGenerator.buildStraight(340.0, 1))
        for ((id, arc) in listOf("a" to 20.0, "b" to 60.0, "c" to 110.0, "d" to 180.0, "e" to 280.0)) {
            OperationalDestinationService(source).register(OperationalDestination("$id@one", rail.id, arc,
                if (returning) ViewDirection.BA else ViewDirection.AB, DestinationRole.StationBerthRole(StationId(id),
                    berthId = "one", doorSide = DoorSide.NONE, usableLengthMeters = 30.0)))
        }
        val main = LineId("main")
        val branch = LineId("branch")
        val lines = source.simpleAtoWorldData.lines
        lines.put(Line(main, listOf("a", "b", "c", "d").map(::StationId),
            listOf("d", "c", "b", "a").map(::StationId)), 0L)
        lines.put(Line(branch, listOf("c", "e").map(::StationId),
            listOf("e", "c").map(::StationId)), 1L)
        fun call(id: String, status: work.art1st.javarailways.simpleato.timetable.TimetableServiceStopStatus,
                 arrival: Long? = null, departure: Long? = null, via: List<String> = emptyList()) =
            work.art1st.javarailways.simpleato.timetable.TimetableRunCall(id, id, "$id@one", status, arrival, departure, via)
        val start = if (returning) "e" else "a"
        val end = if (returning) "a" else "e"
        val firstLine = if (returning) branch else main
        val secondLine = if (returning) main else branch
        val direction = if (returning) work.art1st.javarailways.simpleato.api.line.LineDirection.DOWN else work.art1st.javarailways.simpleato.api.line.LineDirection.UP
        fun timetable(secondSpeed: Double) = Timetable("interline", services = listOf(TimetableService("duty",
            TrainSelector.TrainId("scheduled"), listOf(TimetableStep.Check("$start@one"),
                TimetableStep.At(0, TimetableAction.DestinationOptions("c@one", minimumDwellTicks = 10)),
                TimetableStep.At(0, if (returning) TimetableAction.GoVia(listOf("d@one", "c@one")) else TimetableAction.GoVia(listOf("b@one", "c@one"))),
                TimetableStep.At(500, TimetableAction.DestinationOptions("$end@one", minimumDwellTicks = 0)),
                TimetableStep.At(500, if (returning) TimetableAction.GoVia(listOf("b@one", "a@one")) else TimetableAction.GoVia(listOf("d@one", "e@one")))),
            runSegments = listOf(
                work.art1st.javarailways.simpleato.timetable.TimetableRunSegment("first-run", firstLine, 1, 3, true,
                    diagramLineId = firstLine.value, lineDirection = direction,
                    calls = listOf(call(start, work.art1st.javarailways.simpleato.timetable.TimetableServiceStopStatus.ORIGIN, departure = 0)) +
                        (if (returning) emptyList() else listOf(call("b", work.art1st.javarailways.simpleato.timetable.TimetableServiceStopStatus.PASS))) +
                        call("c", work.art1st.javarailways.simpleato.timetable.TimetableServiceStopStatus.TERMINAL, via = if (returning) listOf("d@one") else emptyList())),
                work.art1st.javarailways.simpleato.timetable.TimetableRunSegment("second-run", secondLine, 3, 5, true,
                    diagramLineId = secondLine.value, lineDirection = direction,
                    calls = listOf(call("c", work.art1st.javarailways.simpleato.timetable.TimetableServiceStopStatus.ORIGIN, departure = 500)) +
                        (if (returning) listOf(call("b", work.art1st.javarailways.simpleato.timetable.TimetableServiceStopStatus.PASS)) else emptyList()) +
                        call(end, work.art1st.javarailways.simpleato.timetable.TimetableServiceStopStatus.TERMINAL, via = if (returning) emptyList() else listOf("d@one")))
            ))), lineSpeedLimits = listOf(TimetableLineSpeedLimit(firstLine, 6.0), TimetableLineSpeedLimit(secondLine, secondSpeed)))
        val factory = IsolatedTimetableSimulationFactory(IsolatedSimulationProfileResolver { _, _, _ -> profile() },
            9.80665, atoSafetyDistanceMeters = 0.0, extensionPlugin = BasicPlugin.INSTANCE)
        try {
            fun run(definition: Timetable): Long {
                val captured = factory.capture(source, definition,
                    listOf(TimetableProfileSelection("duty", SimulationProfileType.TRAIN_TEMPLATE, "template")))
                return factory.create(captured, workerThreads = 1).use { simulation ->
                    val reached = mutableListOf<String>()
                    val listener = simulation.world.simpleAtoWorldData.dispatcher.observeWaypointProgress(
                        work.art1st.javarailways.simpleato.timetable.TimetableServiceRunId("interline", "duty"),
                        listener = work.art1st.javarailways.simpleato.timetable.TimetableWaypointProgressListener {
                            reached += it.waypointId
                        })
                    try {
                        val result = simulation.simulate(IsolatedTimetableSimulationRequest(definition, delayThresholdTicks = 8000))
                        assertTrue(result is IsolatedTimetableSimulationResult.Completed, result.toString())
                        assertEquals(1, result.runs.size)
                        assertEquals(1, simulation.world.allTrains.size)
                        assertEquals(1, reached.count { it == "c@one" })
                        assertEquals(1, reached.count { it == "$end@one" })
                        assertEquals("second-run", result.runs.single().currentRunId)
                        assertEquals(secondLine, result.runs.single().currentLineId)
                        assertEquals(if (returning) 20.0 else 280.0, simulation.world.allTrains.single().publishedFrame!!.head.arcLengthInAbMeters, 0.5)
                        result.simulatedTicks
                    } finally { listener.close() }
                }
            }
            val slow = run(timetable(1.5))
            val fast = run(timetable(6.0))
            assertTrue(slow > fast + 500, "branch cap must materially change real travel time: slow=$slow fast=$fast")
            assertTrue(source.allTrains.isEmpty(), "isolated train creation must not mutate source world")
        } finally {
            source.physicsCoordinator.close()
            (source.tickManager as? AutoCloseable)?.close()
        }
    }

    @Test
    fun `isolated dynamic station stop reverses and returns using actual selected marker`() {
        val source = createTestWorld()
        registerWorldData(source)
        val rail = createStraightRailFromSegments(source, PrimeCurveGenerator.buildStraight(180.0, 1))
        OperationalDestinationService(source).register(OperationalDestination("origin", rail.id, 20.0,
            ViewDirection.AB, DestinationRole.Regular))
        OperationalDestinationService(source).register(OperationalDestination("return", rail.id, 20.0,
            ViewDirection.BA, DestinationRole.Regular))
        val station = StationId("central")
        for ((id, arc) in listOf("short" to 120.0, "long" to 120.0)) {
            OperationalDestinationService(source).register(OperationalDestination("central@$id", rail.id, arc,
                ViewDirection.AB, DestinationRole.StationBerthRole(station, berthId = id, doorSide = DoorSide.NONE,
                    usableLengthMeters = if (id == "short") 5.0 else 30.0,
                    defaultDwellTicks = 10)))
        }
        val selection = work.art1st.javarailways.simpleato.api.station.StationStopSelection(station,
            listOf(work.art1st.javarailways.simpleato.api.station.StationStopCandidate("short", 0),
                work.art1st.javarailways.simpleato.api.station.StationStopCandidate("long", 1)),
            work.art1st.javarailways.simpleato.api.station.StationStopSelectionPolicy.AVAILABLE_PRIORITY)
        val timetable = Timetable("platform-turnback", services = listOf(TimetableService("duty", TrainSelector.TrainId("train"),
            listOf(TimetableStep.Check("origin"), TimetableStep.At(0, TimetableAction.SelectStop(selection)),
                TimetableStep.At(0, TimetableAction.Reverse), TimetableStep.At(0, TimetableAction.GoTo("return"))))))
        val factory = IsolatedTimetableSimulationFactory(IsolatedSimulationProfileResolver { _, _, id -> profile(id) },
            9.80665, atoSafetyDistanceMeters = 0.0, extensionPlugin = BasicPlugin.INSTANCE)
        try {
            val captured = factory.capture(source, timetable, listOf(TimetableProfileSelection("duty", SimulationProfileType.TRAIN_TEMPLATE, "template")))
            factory.create(captured, workerThreads = 1).use { simulation ->
                val result = simulation.simulate(IsolatedTimetableSimulationRequest(timetable))
                assertTrue(result is IsolatedTimetableSimulationResult.Completed, result.toString())
                val run = result.runs.single()
                assertEquals("long", run.selectedBerthRef?.berthId)
                assertTrue(run.selectedBerthCommitted)
                assertFalse(run.berthHeld)
                assertEquals(20.0, simulation.world.allTrains.single().publishedFrame!!.head.arcLengthInAbMeters, 0.5)
            }
        } finally {
            source.physicsCoordinator.close()
            (source.tickManager as? AutoCloseable)?.close()
        }
    }

    @Test
    fun `isolated simulation couples moves and splits the same two permanent units`() {
        simulateCoupleMoveSplit(withDisplays = false)
    }

    @Test
    fun `actual coupling and split keep the two unit display contexts separate`() {
        simulateCoupleMoveSplit(withDisplays = true)
    }

    private fun simulateCoupleMoveSplit(withDisplays: Boolean) {
        val source = createTestWorld()
        registerWorldData(source) { train ->
            TrainCandidateFacts.fromCars(train.cars.associate { car ->
                car.id to TrainCandidateFacts("model/${train.id}", emptySet(),
                    if (train.id == "one") 8.0 else 9.0, emptySet())
            })
        }
        val rail = createStraightRailFromSegments(source, PrimeCurveGenerator.buildStraight(300.0, segmentCount = 1))
        val home = DepotId("home")
        val depots = source.simpleAtoWorldData.depots
        val trains = source.onOwnerForTest {
            listOf("one" to 30.0, "two" to 41.5).map { (id, arc) ->
                val head = work.art1st.javarailways.core.physics.rail.RailLocationWithFacing(rail.viewAB, arc)
                val car = work.art1st.javarailways.core.physics.train.CoreCar.create("$id-car",
                    work.art1st.javarailways.core.physics.train.CoreBogie.create(head),
                    work.art1st.javarailways.core.physics.train.CoreBogie.create(head), length = 10.0,
                    floorHeight = 0.0, connectorLengthAtA = 0.0, connectorLengthAtB = 0.0,
                    bogieAOffset = 3.5, bogieBOffset = -3.5, world = source)
                work.art1st.javarailways.core.physics.train.CoreTrain.create(id, listOf(car), source,
                    composition = TrainComposition.single(listOf(car.id), "unit-$id")).also {
                    it.setLocation(head); it.commitMaintenancePlacement()
                    OperationalDestinationService(source).register(OperationalDestination(id, rail.id, arc,
                        ViewDirection.AB, DestinationRole.Regular))
                    OperationalDestinationService(source).register(OperationalDestination("home@$id", rail.id, arc,
                        ViewDirection.AB, role = DestinationRole.DepotStablingPointRole(home.value, id)))
                    depots.registerUnitControlCompatibility("unit-$id", "emu")
                }
            }
        }
        source.basicWorldData.wayPoints.add(WayPoint("end", rail.id, 120.0))
        val outputTarget = if (withDisplays) "d@one" else "end"
        if (withDisplays) {
            for ((id, station, arc) in listOf(Triple("c@one", "c", 41.5), Triple("d@one", "d", 120.0))) {
                OperationalDestinationService(source).register(OperationalDestination(id, rail.id, arc,
                    ViewDirection.AB, DestinationRole.StationBerthRole(StationId(station), berthId = "one", doorSide = DoorSide.NONE,
                        usableLengthMeters = 40.0)))
            }
            source.simpleAtoWorldData.lines.put(Line(LineId("joined-line"),
                listOf(StationId("c"), StationId("d")), listOf(StationId("d"), StationId("c"))), 0L)
        }
        fun at(action: TimetableAction) = TimetableStep.At(0, action)
        val definition = Timetable("join-divide", services = listOf(
            TimetableService("one", TrainSelector.TrainId("one"), listOf(TimetableStep.Check("one"), at(TimetableAction.Couple("join")),
                at(TimetableAction.GoTo(outputTarget)), at(TimetableAction.Split("divide")))),
            TimetableService("two", TrainSelector.TrainId("two"), listOf(TimetableStep.Check("two"), at(TimetableAction.Couple("join"))))),
            formationEvents = TimetableFormationCatalog(listOf(
                TimetableFormationEvent.Couple("join", "one", "one", "two",
                    work.art1st.javarailways.simpleato.timetable.FormationStationaryEnd.TAIL,
                    work.art1st.javarailways.simpleato.timetable.FormationRendezvous("one", "two"), 0.2,
                    TimetableFormationOutput("combined", FormationOutputDisposition.Duty("one"))),
                TimetableFormationEvent.Split("divide", "one", 1,
                    TimetableFormationOutput("first", FormationOutputDisposition.Hold),
                    TimetableFormationOutput("rest", FormationOutputDisposition.Hold)))))
        val timetable = if (!withDisplays) definition else {
            val segment = work.art1st.javarailways.simpleato.timetable.TimetableRunSegment("joined-run", LineId("joined-line"), 2, 3, true,
                diagramLineId = "joined-line", lineDirection = work.art1st.javarailways.simpleato.api.line.LineDirection.UP,
                calls = listOf(
                    work.art1st.javarailways.simpleato.timetable.TimetableRunCall("joined-c", "c", "c@one",
                        work.art1st.javarailways.simpleato.timetable.TimetableServiceStopStatus.ORIGIN, departureTick = 0),
                    work.art1st.javarailways.simpleato.timetable.TimetableRunCall("joined-d", "d", outputTarget,
                        work.art1st.javarailways.simpleato.timetable.TimetableServiceStopStatus.TERMINAL)))
            definition.copy(services = definition.services.map { if (it.id == "one") it.copy(runSegments = listOf(segment)) else it },
                lineSpeedLimits = listOf(TimetableLineSpeedLimit(LineId("joined-line"), 8.0)),
                operationalTrips = listOf("trip-D" to "D", "trip-E" to "E").mapIndexed { index, (id, destination) ->
                    work.art1st.javarailways.simpleato.timetable.TimetableOperationalTrip(id,
                        kotlinx.serialization.json.buildJsonObject { put("destination", kotlinx.serialization.json.JsonPrimitive(destination)) },
                        listOf(work.art1st.javarailways.simpleato.timetable.TimetableOperationalTripSegment("one", "joined-run", listOf(index + 1))))
                })
        }
        val factory = IsolatedTimetableSimulationFactory(IsolatedSimulationProfileResolver { _, _, id ->
            profile(id).copy(type = SimulationProfileType.WORLD_TRAIN)
        }, 9.80665, atoSafetyDistanceMeters = 0.0, extensionPlugin = BasicPlugin.INSTANCE)
        try {
            val captured = factory.capture(source, timetable, trains.map { TimetableProfileSelection(it.id, SimulationProfileType.WORLD_TRAIN, it.id) })
            var completedAt: Long? = null
            repeat(2) {
                factory.create(captured, workerThreads = 1).use { simulation ->
                    val initialCars = simulation.world.allTrains.flatMap { it.cars }.map { it.id }.toSet()
                    val initialUnits = simulation.world.simpleAtoWorldData.depots.allFixedUnits()
                    val initialFacts = simulation.world.allTrains.flatMap { train ->
                        simulation.world.simpleAtoWorldData.candidateFacts(train)!!.carFacts.entries
                    }.associate { it.key to it.value }
                    var linkedFacts: TrainCandidateFacts? = null
                    val listener = work.art1st.javarailways.core.api.event.processor.MainRailEventBus.register(object :
                        work.art1st.javarailways.core.api.event.processor.EventListener {
                        @work.art1st.javarailways.core.api.event.processor.HandlesEvent
                        fun linked(event: work.art1st.javarailways.core.api.event.TrainLinkSuccessEvent) {
                            if (event.train.world === simulation.world)
                                linkedFacts = simulation.world.simpleAtoWorldData.candidateFacts(event.train)
                        }
                    })
                    val result = try {
                        simulation.simulate(IsolatedTimetableSimulationRequest(timetable, delayThresholdTicks = 4000))
                    } finally { listener.close() }
                    assertEquals(TrainCandidateFacts.fromCars(initialFacts), linkedFacts)
                    assertNull(linkedFacts!!.modelPackageId)
                    assertEquals(8.0, linkedFacts!!.maximumSpeedMetersPerSecond)
                    simulation.world.allTrains.forEach { train ->
                        assertEquals(TrainCandidateFacts.fromCars(train.cars.associate { it.id to initialFacts.getValue(it.id) }),
                            simulation.world.simpleAtoWorldData.candidateFacts(train))
                    }
                    assertTrue(result is IsolatedTimetableSimulationResult.Completed, result.toString())
                    completedAt?.let { assertEquals(it, result.simulatedTicks) }
                    completedAt = result.simulatedTicks
                    assertEquals(2, simulation.world.allTrains.size)
                    assertEquals(initialCars, simulation.world.allTrains.flatMap { it.cars }.map { it.id }.toSet())
                    assertEquals(initialUnits, simulation.world.simpleAtoWorldData.depots.allFixedUnits())
                    assertTrue(simulation.world.allTrains.all { it.publishedFrame!!.head.arcLengthInAbMeters > 90.0 })
                    if (withDisplays) {
                        val displays = result.runs.single { it.id.serviceId == "one" }.unitDisplays
                        assertEquals(initialUnits.map { it.id }.toSet(), displays.map { it.unitId }.toSet())
                        assertEquals(setOf("trip-D", "trip-E"), displays.map { it.operationalTripId }.toSet())
                        assertEquals(setOf("D", "E"), displays.map {
                            (it.values.getValue("destination") as kotlinx.serialization.json.JsonPrimitive).content
                        }.toSet())
                        assertTrue(displays.all { it.remainingStops.isEmpty() }, displays.toString())
                        for (output in simulation.world.allTrains) {
                            val actualUnit = initialUnits.single { unit -> unit.carIds == output.cars.map { it.id } }
                            val selected = simulation.world.onOwnerForTest {
                                simulation.world.simpleAtoWorldData.dispatcher.unitDisplaysForTrain(
                                    simulation.world, output.id, timetable.id, 0L)
                            }
                            assertEquals(listOf(actualUnit.id), selected.map { it.unitId })
                            assertEquals(displays.single { it.unitId == actualUnit.id }.values, selected.single().values)
                        }
                    }
                }
            }
            assertEquals(listOf("one-car", "two-car"), trains.flatMap { it.cars }.map { it.id })
        } finally {
            source.physicsCoordinator.close()
            (source.tickManager as? AutoCloseable)?.close()
        }
    }

    @Test
    fun `structural simulation creates complete source units and derives child only from actual split`() {
        simulateSplitOutput(storeOutput = false)
    }

    @Test
    fun `split output stores at a physical depot point without registered inventory`() {
        simulateSplitOutput(storeOutput = true)
    }

    private fun simulateSplitOutput(storeOutput: Boolean) {
        val source = createTestWorld()
        val rail = createStraightRailFromSegments(source, PrimeCurveGenerator.buildStraight(300.0, segmentCount = 1))
        registerWorldData(source)
        OperationalDestinationService(source).register(OperationalDestination("origin", rail.id, 30.0,
            ViewDirection.AB, DestinationRole.Regular))
        source.basicWorldData.wayPoints.add(WayPoint("end", rail.id, 180.0))
        source.basicWorldData.wayPoints.add(WayPoint("child-end", rail.id, 120.0))
        val train = createTrainWithConfig("live-source", rail, source, listOf(10.0, 10.0),
            composition = TrainComposition.create(listOf(
                TrainUnitComposition("unit-0", listOf("car-0")),
                TrainUnitComposition("unit-1", listOf("car-1"))), listOf("car-0", "car-1")))
        val depot = DepotId("home")
        val point = StablingPointRef(depot, "point")
        val depots = source.simpleAtoWorldData.depots
        OperationalDestinationService(source).register(OperationalDestination("home@point", rail.id, 30.0, ViewDirection.AB,
            role = DestinationRole.DepotStablingPointRole(depot.value, "point", "Home")))
        val destinationDepot = DepotId("other")
        if (storeOutput) {
            OperationalDestinationService(source).register(OperationalDestination("other@point", rail.id, 240.0, ViewDirection.AB,
                role = DestinationRole.DepotStablingPointRole(destinationDepot.value, "point", "Other")))
        }
        val ids = train.cars.map { it.id }
        ids.indices.forEach { depots.registerUnitControlCompatibility("unit-$it", "emu") }
        val base = profile()
        val profile = base.copy(type = SimulationProfileType.WORLD_TRAIN, id = train.id,
            carControls = List(2) { base.carControls.single() }, cars = List(2) { base.cars.single() })
        val factory = IsolatedTimetableSimulationFactory(IsolatedSimulationProfileResolver { _, _, _ -> profile },
            9.80665, atoSafetyDistanceMeters = 0.0, extensionPlugin = BasicPlugin.INSTANCE)
        val timetable = Timetable("formation-sim", services = listOf(
            TimetableService("source", TrainSelector.TrainId("scheduled"), listOf(TimetableStep.Check("origin"),
                TimetableStep.At(0, TimetableAction.Split("split")), TimetableStep.At(0, TimetableAction.GoVia(listOf("child-end", "end")))) +
                if (storeOutput) listOf(TimetableStep.At(0, TimetableAction.StoreAtDepot(destinationDepot))) else emptyList()),
            TimetableService("child", TrainSelector.AnyTrain, listOf(TimetableStep.At(100, TimetableAction.TakeFromFormationOutput("split", "rest")),
                TimetableStep.At(100, TimetableAction.GoTo("child-end"))))),
            formationEvents = TimetableFormationCatalog(listOf(TimetableFormationEvent.Split("split", "source", 1,
                TimetableFormationOutput("first", FormationOutputDisposition.Duty("source")),
                TimetableFormationOutput("rest", FormationOutputDisposition.Duty("child"))))))
        val selection = TimetableProfileSelection("source", SimulationProfileType.WORLD_TRAIN, train.id)
        try {
            assertThrows(IllegalArgumentException::class.java) { factory.capture(source, timetable,
                listOf(selection, selection.copy(serviceId = "child"))) }
            assertThrows(IllegalArgumentException::class.java) { factory.capture(source, timetable,
                listOf(selection.copy(profileType = SimulationProfileType.TRAIN_TEMPLATE))) }
            val snapshot = factory.capture(source, timetable, listOf(selection))
            factory.create(snapshot, workerThreads = 1).use { simulation ->
                assertEquals(1, simulation.world.allTrains.size)
                val simDepots = simulation.world.simpleAtoWorldData.depots
                assertEquals(2, simDepots.allFixedUnits().size)
                assertTrue(simDepots.allFixedUnitAssignments().isEmpty(), "Seeds do not invent task ownership")
                assertTrue(simDepots.allFixedUnits().all { unit -> unit.carIds.none(ids::contains) })
                val result = simulation.simulate(IsolatedTimetableSimulationRequest(timetable))
                assertTrue(result is IsolatedTimetableSimulationResult.Completed, result.toString())
                assertEquals(2, simulation.world.allTrains.size)
                assertEquals(2, simDepots.allFixedUnits().size)
                assertEquals(if (storeOutput) 1 else 2, simDepots.allFixedUnitAssignments().size)
                if (storeOutput) {
                    val stored = simulation.world.allTrains.single { candidate ->
                        kotlin.math.abs(candidate.publishedFrame.head.arcLengthInAbMeters - 240.0) < 1.0
                    }
                    assertEquals(rail.id, stored.publishedFrame.head.railId)
                    assertEquals(0.0, stored.publishedFrame.speedMetersPerSecond)
                }
            }
            assertEquals(ids, train.cars.map { it.id })
        } finally {
            source.physicsCoordinator.close()
            (source.tickManager as? AutoCloseable)?.close()
        }
    }

    @Test
    fun `creates only synthetic timetable trains from a static world snapshot`() {
        val source = createTestWorld()
        val rail = createStraightRailFromSegments(
            source,
            PrimeCurveGenerator.buildStraight(300.0, segmentCount = 1)
        )
        registerWorldData(source)
        OperationalDestinationService(source).register(OperationalDestination("origin", rail.id, 20.0,
            ViewDirection.AB, DestinationRole.Regular))
        createTrainWithConfig("live-transient", rail, source, listOf(10.0))
        val timetable = Timetable("t", services = listOf(TimetableService(
            "duty",
            TrainSelector.TrainId("scheduled-train"),
            listOf(TimetableStep.Check("origin"), TimetableStep.At(0, TimetableAction.GoTo("origin")))
        )))
        val profile = profile()
        val factory = IsolatedTimetableSimulationFactory(
            IsolatedSimulationProfileResolver { _, _, _ -> profile },
            9.80665,
            extensionPlugin = BasicPlugin.INSTANCE
        )
        val snapshot = factory.capture(source, timetable, listOf(
            TimetableProfileSelection("duty", SimulationProfileType.TRAIN_TEMPLATE, "template")
        ))
        source.physicsCoordinator.close()
        (source.tickManager as? AutoCloseable)?.close()

        factory.create(snapshot, workerThreads = 2).use { simulation ->
            assertEquals(listOf("scheduled-train"), simulation.world.allTrains.map { it.name })
            assertFalse(simulation.world.allTrains.any { it.name == "live-transient" })
        }
    }

    @Test
    fun `isolated timetable operation honors the shared line maximum speed resolver`() {
        val source = createTestWorld()
        val rail = createStraightRailFromSegments(
            source,
            PrimeCurveGenerator.buildStraight(300.0, segmentCount = 1)
        )
        registerWorldData(source)
        OperationalDestinationService(source).register(OperationalDestination("origin", rail.id, 20.0,
            ViewDirection.AB, DestinationRole.Regular))
        source.basicWorldData.wayPoints.add(WayPoint("terminal", rail.id, 250.0))
        val destinationService = OperationalDestinationService(source)
        listOf("a" to 10.0, "b" to 280.0).forEach { (id, arcLength) ->
            destinationService.register(OperationalDestination(
                id = "$id@one",
                railId = rail.id,
                arcLengthInAbMeters = arcLength,
                approachDirection = ViewDirection.AB,
                role = DestinationRole.StationBerthRole(
                    stationId = StationId(id),
                    berthId = "one",
                    doorSide = DoorSide.LEFT
                )
            ))
        }
        val lineId = LineId("metro")
        source.simpleAtoWorldData.lines.put(
            Line(lineId, listOf(StationId("a"), StationId("b")), listOf(StationId("b"), StationId("a"))),
            0L
        )
        fun timetable(id: String, speed: Double) = Timetable(
            id,
            services = listOf(TimetableService(
                "duty",
                TrainSelector.TrainId("scheduled-$id"),
                listOf(
                    TimetableStep.Check("origin"),
                    TimetableStep.At(0, TimetableAction.GoTo("terminal"))
                ),
                lineId = lineId
            )),
            lineSpeedLimits = listOf(TimetableLineSpeedLimit(lineId, speed))
        )
        val factory = IsolatedTimetableSimulationFactory(
            IsolatedSimulationProfileResolver { _, _, _ -> profile() },
            9.80665,
            atoSafetyDistanceMeters = 7.5,
            extensionPlugin = BasicPlugin.INSTANCE
        )
        fun capture(timetable: Timetable) = factory.capture(source, timetable, listOf(
            TimetableProfileSelection("duty", SimulationProfileType.TRAIN_TEMPLATE, "template")
        ))
        val slowTimetable = timetable("slow", 3.0)
        val fastTimetable = timetable("fast", 9.0)
        val slowSnapshot = capture(slowTimetable)
        val fastSnapshot = capture(fastTimetable)
        assertEquals(7.5, slowSnapshot.atoSafetyDistanceMeters)
        assertEquals(7.5, fastSnapshot.atoSafetyDistanceMeters)

        val slowTicks = factory.create(slowSnapshot, workerThreads = 1).use { simulation ->
            val result = simulation.simulate(IsolatedTimetableSimulationRequest(slowTimetable))
            assertTrue(result is IsolatedTimetableSimulationResult.Completed, result.toString())
            result.simulatedTicks
        }
        val fastTicks = factory.create(fastSnapshot, workerThreads = 1).use { simulation ->
            val result = simulation.simulate(IsolatedTimetableSimulationRequest(fastTimetable))
            assertTrue(result is IsolatedTimetableSimulationResult.Completed, result.toString())
            result.simulatedTicks
        }

        assertTrue(slowTicks > fastTicks)
        source.physicsCoordinator.close()
        (source.tickManager as? AutoCloseable)?.close()
    }

    @Test
    fun `reuses one named synthetic train without consuming a second origin`() {
        val source = createTestWorld()
        val rail = createStraightRailFromSegments(
            source,
            PrimeCurveGenerator.buildStraight(300.0, segmentCount = 1)
        )
        registerWorldData(source)
        OperationalDestinationService(source).register(OperationalDestination("origin", rail.id, 20.0,
            ViewDirection.AB, DestinationRole.Regular))
        val services = listOf("a", "b").map { id -> TimetableService(
            id,
            TrainSelector.TrainId("shared"),
            listOf(TimetableStep.At(0, TimetableAction.TakeFromWaypoint(listOf("origin"))))
        ) }
        val profile = profile()
        val factory = IsolatedTimetableSimulationFactory(
            IsolatedSimulationProfileResolver { _, _, _ -> profile },
            9.80665,
            extensionPlugin = BasicPlugin.INSTANCE
        )
        val snapshot = factory.capture(source, Timetable("t", services = services), services.map {
            TimetableProfileSelection(it.id, SimulationProfileType.TRAIN_TEMPLATE, "template")
        })

        factory.create(snapshot, workerThreads = 1).use { simulation ->
            assertEquals(listOf("shared"), simulation.world.allTrains.map { it.name })
        }
        source.physicsCoordinator.close()
        (source.tickManager as? AutoCloseable)?.close()
    }

    @Test
    fun `rejects different profiles for structurally equivalent selectors`() {
        val source = createTestWorld()
        registerWorldData(source)
        val services = listOf("a", "b").map { id ->
            TimetableService(id, TrainSelector.TrainId("same-train"), emptyList())
        }
        val factory = IsolatedTimetableSimulationFactory(
            IsolatedSimulationProfileResolver { _, _, id -> profile(id) },
            9.80665,
            extensionPlugin = BasicPlugin.INSTANCE
        )

        val failure = assertThrows(IllegalArgumentException::class.java) {
            factory.capture(source, Timetable("t", services = services), listOf(
                TimetableProfileSelection("a", SimulationProfileType.TRAIN_TEMPLATE, "one"),
                TimetableProfileSelection("b", SimulationProfileType.TRAIN_TEMPLATE, "two")
            ))
        }
        assertTrue(failure.message!!.contains("equivalent selectors"))
        source.physicsCoordinator.close()
        (source.tickManager as? AutoCloseable)?.close()
    }

    @Test
    fun `capture rejects a station resolver without detached simulation preparation`() {
        val source = createTestWorld()
        registerWorldData(source)
        try {
            val rail = createStraightRailFromSegments(source, PrimeCurveGenerator.buildStraight(200.0, 1))
            OperationalDestinationService(source).register(OperationalDestination("origin", rail.id, 30.0,
                ViewDirection.AB, DestinationRole.Regular))
            source.onOwnerForTest {
                source.simpleAtoWorldData.stationCallResolvers.register(StationCallResolutionProvider {
                    StationCallResolutionResult.Success(it.defaultResolution)
                })
            }
            val failure = assertThrows(IllegalArgumentException::class.java) {
                testSimulationFactory().capture(source, standaloneCall(listOf(TimetableAction.Stop(1))),
                    templateSelection(), standaloneServices())
            }
            assertTrue(failure.message.orEmpty().contains("station call resolver"), failure.toString())
        } finally {
            source.physicsCoordinator.close()
            (source.tickManager as AutoCloseable).close()
        }
    }

    @Test
    fun `captured station resolver installs independent policies and never calls live policy again`() {
        val source = createTestWorld()
        registerWorldData(source)
        try {
            val rail = createStraightRailFromSegments(source, PrimeCurveGenerator.buildStraight(200.0, 1))
            OperationalDestinationService(source).register(OperationalDestination("origin", rail.id, 30.0,
                ViewDirection.AB, DestinationRole.Regular))
            val station = StationId("central")
            OperationalDestinationService(source).register(OperationalDestination("central@one", rail.id, 120.0,
                ViewDirection.AB, DestinationRole.StationBerthRole(station, berthId = "one", doorSide = DoorSide.NONE,
                    usableLengthMeters = 30.0)))
            var liveReleaseDistance = 2.0
            var preparations = 0
            var liveCalls = 0
            val targetCalls = mutableListOf<Int>()
            val targetProviders = mutableListOf<StationCallResolutionProvider>()
            val liveProvider = object : StationCallResolutionProvider, StationCallResolutionSimulationFactory {
                override fun resolve(context: StationCallResolutionContext): StationCallResolutionResult {
                    liveCalls++
                    return StationCallResolutionResult.Success(context.defaultResolution.copy(releaseDistanceMetersAfter = liveReleaseDistance))
                }
                override fun prepareSimulation(): StationCallResolutionSimulationPreparation {
                    preparations++
                    val capturedReleaseDistance = liveReleaseDistance
                    return StationCallResolutionSimulationPreparation {
                        val index = targetCalls.size
                        targetCalls += 0
                        StationCallResolutionProvider { context ->
                            targetCalls[index]++
                            StationCallResolutionResult.Success(context.defaultResolution.copy(releaseDistanceMetersAfter = capturedReleaseDistance))
                        }.also { targetProviders += it }
                    }
                }
            }
            source.onOwnerForTest { source.simpleAtoWorldData.stationCallResolvers.register(liveProvider) }
            val factory = testSimulationFactory()
            val timetable = standaloneCall(listOf(TimetableAction.Stop(1)))
            val captured = factory.capture(source, timetable, templateSelection(), standaloneServices())
            liveReleaseDistance = 7.0
            factory.create(captured, 1).use { first ->
                factory.create(captured, 1).use { second ->
                    assertNotSame(first.world.simpleAtoWorldData.stationCallResolvers,
                        second.world.simpleAtoWorldData.stationCallResolvers)
                    assertEquals(2, targetProviders.size)
                    assertNotSame(targetProviders[0], targetProviders[1])
                    assertTrue(targetProviders.none { it === liveProvider })
                    fun resolve(world: CoreWorld): ResolvedStationCall = world.onOwnerForTest {
                        val result = StationCallResolver(world).resolve(StationCallRequest("detached-test",
                            WayPointRef("origin"), StationCall("call", 1L, station, StationBerthRef(station, "one"),
                                dwellPlan = DwellPlan(0L))), trainLengthMeters = 10.0,
                            requiredStartDirection = ViewDirection.AB)
                        assertTrue(result is StationCallResolutionResult.Success, result.toString())
                        (result as StationCallResolutionResult.Success).resolved
                    }
                    assertEquals(2.0, resolve(first.world).releaseDistanceMetersAfter)
                    assertEquals(2.0, resolve(first.world).releaseDistanceMetersAfter)
                    assertEquals(listOf(2, 0), targetCalls)
                    assertEquals(2.0, resolve(second.world).releaseDistanceMetersAfter)
                    assertEquals(listOf(2, 1), targetCalls)
                    assertEquals(1, preparations)
                    assertEquals(0, liveCalls)
                }
            }
            assertTrue(source.allTrains.isEmpty())
        } finally {
            source.physicsCoordinator.close()
            (source.tickManager as AutoCloseable).close()
        }
    }

    @Test
    fun `capture rejects an installed domain feature without detached preparation`() {
        val source = createTestWorld()
        registerWorldData(source)
        try {
            val factory = IsolatedTimetableSimulationFactory(
                IsolatedSimulationProfileResolver { _, _, _ -> profile() }, 9.80665,
                requiredDomainFeatures = { setOf("simplesignals") },
                extensionPlugin = BasicPlugin.INSTANCE
            )
            val timetable = Timetable("unsupported", services = emptyList())
            val failure = assertThrows(IllegalArgumentException::class.java) {
                factory.capture(source, timetable, emptyList())
            }
            assertTrue(failure.message!!.contains("simplesignals"))
        } finally {
            source.physicsCoordinator.close()
            (source.tickManager as AutoCloseable).close()
        }
    }

    @Test
    fun `one captured snapshot creates new extension and domain state for every target`() {
        val source = createTestWorld()
        val rail = createStraightRailFromSegments(source, PrimeCurveGenerator.buildStraight(300.0, segmentCount = 1))
        registerWorldData(source)
        OperationalDestinationService(source).register(OperationalDestination("origin", rail.id, 20.0,
            ViewDirection.AB, DestinationRole.Regular))
        source.basicWorldData.wayPoints.add(WayPoint("terminal", rail.id, 100.0))
        val providerWorlds = arrayListOf<World>()
        source.simpleAtoWorldData.timetableExtensions.registerCondition(object :
            TimetableConditionProvider,
            TimetableConditionSimulationFactory {
            override val typeId = "test:captured"
            override val version = 1
            override val descriptor = TimetableExtensionDescriptor("Captured", "Target local")
            override fun evaluate(parameters: kotlinx.serialization.json.JsonObject,
                context: TimetableRuntimeContext) =
                TimetableConditionResult.True
            override fun prepareSimulation(): TimetableConditionSimulationPreparation {
                val capturedDescriptor = descriptor
                return TimetableConditionSimulationPreparation { target ->
                    providerWorlds += target
                    object : TimetableConditionProvider {
                        override val typeId = "test:captured"
                        override val version = 1
                        override val descriptor = capturedDescriptor
                        override fun evaluate(parameters: kotlinx.serialization.json.JsonObject,
                            context: TimetableRuntimeContext) =
                            TimetableConditionResult.True
                    }
                }
            }
        })
        val timetable = Timetable("t", services = listOf(TimetableService("duty", TrainSelector.TrainId("shared"),
            listOf(TimetableStep.Check("origin"), TimetableStep.At(0, TimetableAction.GoTo("terminal"))))))
        val sourceId = java.util.UUID.randomUUID()
        val features = work.art1st.javarailways.bukkit.api.internal.DefaultSimulationFeatureService()
        val epochs = linkedMapOf<java.util.UUID, java.util.concurrent.atomic.AtomicInteger>()
        val closed = arrayListOf<java.util.UUID>()
        val owner = java.lang.reflect.Proxy.newProxyInstance(org.bukkit.plugin.Plugin::class.java.classLoader,
            arrayOf(org.bukkit.plugin.Plugin::class.java)) { _, method, _ -> when (method.name) {
                "getName" -> "ExampleSignals"
                "isEnabled" -> true
                else -> null
            } } as org.bukkit.plugin.Plugin
        features.register(owner) { capturedId ->
            assertEquals(sourceId, capturedId)
            object : work.art1st.javarailways.simpleato.api.simulation.PreparedSimulationFeature {
                override val id = "examplesignals"
                override fun install(target: work.art1st.javarailways.simpleato.api.simulation.SimulationDomainTarget): AutoCloseable {
                    val calls = java.util.concurrent.atomic.AtomicInteger()
                    epochs[target.worldId] = calls
                    assertEquals(setOf(rail.id), target.topology.railProjection.keys)
                    assertEquals(setOf(rail.id), target.operatingDirections(listOf(rail.id)).directions.keys)
                    assertEquals(mapOf(rail.id to null), target.railSpeedLimits(listOf(rail.id)))
                    val token = target.installSignalSources(listOf(
                        work.art1st.javarailways.simpleato.api.signal.SignalEpochSource(
                            "example:captured", emptyList(), emptyList(), listOf(rail.id)) {
                            calls.incrementAndGet()
                            work.art1st.javarailways.simpleato.api.signal.SignalEpochResult.empty()
                        }))
                    return AutoCloseable { token.close(); closed += target.worldId }
                }
            }
        }
        val factory = IsolatedTimetableSimulationFactory(
            IsolatedSimulationProfileResolver { _, _, _ -> profile() }, 9.80665,
            requiredDomainFeatures = { setOf("examplesignals") },
            prepareDomainFeatures = { features.prepare(sourceId) },
            extensionPlugin = BasicPlugin.INSTANCE
        )
        try {
            val snapshot = work.art1st.javarailways.core.util.IsolatedSimulationThreadMarker.run { source.executionOwner.run {
                source.physicsCoordinator.processBetweenFrames {
                    factory.capture(source, timetable, listOf(
                        TimetableProfileSelection("duty", SimulationProfileType.TRAIN_TEMPLATE, "template")))
                }
            } }
            features.unregisterOwner(owner)
            factory.create(snapshot, 1).use { first -> factory.create(snapshot, 1).use { second ->
                assertEquals(listOf(first.world, second.world), providerWorlds)
                assertEquals(first.world.runtimeId, first.operationalRuntime.worldId)
                assertEquals(second.world.runtimeId, second.operationalRuntime.worldId)
                org.junit.jupiter.api.Assertions.assertNotEquals(first.operationalRuntime.worldId, second.operationalRuntime.worldId)
                assertNotSame(first.world.simpleAtoWorldData.timetableExtensions, second.world.simpleAtoWorldData.timetableExtensions)
                assertNotSame(first.world.simpleAtoWorldData.timetableExtensions.condition("test:captured"),
                    second.world.simpleAtoWorldData.timetableExtensions.condition("test:captured"))
                assertNotSame(first.world.simpleAtoWorldData.dispatcher, second.world.simpleAtoWorldData.dispatcher)
                assertNotSame(first.routeAdmissionHost, second.routeAdmissionHost)
                assertNotSame(first.world.basicWorldData.railMetadata, second.world.basicWorldData.railMetadata)
                val firstId = java.util.UUID.fromString(first.world.runtimeId)
                val secondId = java.util.UUID.fromString(second.world.runtimeId)
                assertNotSame(epochs[firstId], epochs[secondId])
                work.art1st.javarailways.core.util.IsolatedSimulationThreadMarker.run {
                    first.world.physicsCoordinator.stepSimulation(50.0).await()
                }
                assertEquals(1, epochs.getValue(firstId).get())
                assertEquals(0, epochs.getValue(secondId).get())
                first.close()
                assertEquals(listOf(firstId), closed)
                val result = second.simulate(IsolatedTimetableSimulationRequest(timetable))
                assertTrue(result is IsolatedTimetableSimulationResult.Completed, result.toString())
                assertTrue(epochs.getValue(secondId).get() > 0)
                assertEquals(1, epochs.getValue(firstId).get())
            } }
        } finally {
            features.close()
            source.physicsCoordinator.close()
            (source.tickManager as AutoCloseable).close()
        }
    }

    @Test
    @OptIn(work.art1st.javarailways.core.api.util.plugin.ExtensionData::class)
    fun `mixed installation survives seed and split rebuilds ATO from exact current car profiles`() {
        val source = createTestWorld()
        val rail = createStraightRailFromSegments(source, PrimeCurveGenerator.buildStraight(300.0, 1))
        registerWorldData(source)
        OperationalDestinationService(source).register(OperationalDestination("origin", rail.id, 60.0,
            ViewDirection.AB, DestinationRole.Regular))
        source.basicWorldData.wayPoints.add(WayPoint("terminal", rail.id, 250.0))
        val controls = listOf(20_000.0, 30_000.0, 40_000.0).map { CarControlConfig(mass = it) }
        val base = profile()
        val mixed = base.copy(carControls = controls, cars = controls.mapIndexed { index, control ->
            base.cars.single().copy(control = control, installedReversed = index != 1)
        })
        val timetable = Timetable("structure", services = listOf(TimetableService("duty",
            TrainSelector.TrainId("scheduled"), listOf(TimetableStep.Check("origin"),
                TimetableStep.At(0, TimetableAction.GoTo("terminal"))))))
        val factory = IsolatedTimetableSimulationFactory(
            IsolatedSimulationProfileResolver { _, _, _ -> mixed }, 9.80665,
            extensionPlugin = BasicPlugin.INSTANCE)
        val snapshot = factory.capture(source, timetable, listOf(
            TimetableProfileSelection("duty", SimulationProfileType.TRAIN_TEMPLATE, "template")))
        source.physicsCoordinator.close()
        (source.tickManager as AutoCloseable).close()
        factory.create(snapshot, 1).use { simulation ->
            val world = simulation.world
            val train = world.allTrains.single() as work.art1st.javarailways.core.physics.train.CoreTrain
            assertEquals(listOf(true, false, true), world.onOwnerForTest { train.save() }.cars.map { it.installedReversed })
            val originalCars = train.cars.toList()
            val originalExtensions = originalCars.map {
                it.getExtensionDataOrNull<work.art1st.javarailways.bukkit.operation.SimulationCarBehaviorExtension>(BasicPlugin.INSTANCE)
            }
            world.onOwnerForTest {
                world.simpleAtoWorldData.timetables.add(timetable)
                val started = world.simpleAtoWorldData.dispatcher.startService(world, timetable.id, "duty")
                assertTrue(started is work.art1st.javarailways.simpleato.timetable.TimetableServiceStartResult.Started, started.toString())
            }
            val originalAto = train.controller as work.art1st.javarailways.simpleato.controller.SimpleATO
            val split = train.splitBefore(originalCars[1].id, "separated")
            world.onOwnerForTest { world.physicsCoordinator.drainBetweenFramesOperations() }
            val separated = split.get(5, java.util.concurrent.TimeUnit.SECONDS) as work.art1st.javarailways.core.physics.train.CoreTrain
            assertSame(originalAto, train.controller)
            assertEquals(listOf(originalCars.first()), train.cars)
            assertSame(controls.first(), originalAto.dynamicsProfileFor(originalCars.first()))
            assertNull(originalAto.dynamicsProfileFor(originalCars[1]))
            assertNull(separated.controller)
            assertNotSame(train.getExtensionDataOrNull<work.art1st.javarailways.bukkit.extension.train.SimulationTrainExtension>(BasicPlugin.INSTANCE),
                separated.getExtensionDataOrNull<work.art1st.javarailways.bukkit.extension.train.SimulationTrainExtension>(BasicPlugin.INSTANCE))
            val newOrigin = "split-origin"
            world.onOwnerForTest {
                world.basicWorldData.wayPoints.add(WayPoint(newOrigin, rail.id,
                    separated.headLocation.arcLengthIn(ViewDirection.AB)))
                val detachedTimetable = Timetable("split-operation", services = listOf(TimetableService("split-duty",
                    TrainSelector.TrainId(separated.id), listOf(TimetableStep.Check(newOrigin),
                        TimetableStep.At(0, TimetableAction.GoVia(listOf("origin", "terminal")))))))
                world.simpleAtoWorldData.timetables.add(detachedTimetable)
                val started = world.simpleAtoWorldData.dispatcher.startService(world, detachedTimetable.id, "split-duty")
                assertTrue(started is work.art1st.javarailways.simpleato.timetable.TimetableServiceStartResult.Started, started.toString())
            }
            val newAto = separated.controller as work.art1st.javarailways.simpleato.controller.SimpleATO
            separated.cars.forEachIndexed { index, car ->
                assertSame(originalCars[index + 1], car)
                assertSame(originalExtensions[index + 1], car.getExtensionDataOrNull<work.art1st.javarailways.bukkit.operation.SimulationCarBehaviorExtension>(BasicPlugin.INSTANCE))
                assertSame(controls[index + 1], newAto.dynamicsProfileFor(car))
            }
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource("AB, 0.5, true, false", "BA, 0.5, true, false",
        "AB, 0.5, true, true", "BA, 0.5, true, true", "AB, 0.3, true, false", "BA, 0.3, true, false",
        "AB, 0.2, false, false", "BA, 0.2, false, false")
    fun `station terminal uses actual short lawful suffix through shared admission`(
        direction: ViewDirection, lawfulSuffix: Double, shouldComplete: Boolean, reverseAfter: Boolean
    ) {
        val source = createTestWorld()
        registerWorldData(source)
        try {
            val first = createStraightRailFromSegments(source, PrimeCurveGenerator.buildStraight(600.0, 2))
            val second = requireNotNull(first.viewAB.activeRail).rail
            val platform = if (direction == ViewDirection.AB) first else second
            val forbidden = if (direction == ViewDirection.AB) second else first
            source.basicWorldData.operatingDirections.put(forbidden.id,
                if (direction == ViewDirection.AB) ViewDirection.BA else ViewDirection.AB)
            val origin = 150.0
            val nominalStop = if (direction == ViewDirection.AB) 300.0 - lawfulSuffix else lawfulSuffix
            val destinations = OperationalDestinationService(source)
            destinations.register(OperationalDestination("origin", platform.id, origin, direction, DestinationRole.Regular))
            destinations.register(OperationalDestination("terminal@one", platform.id, nominalStop, direction,
                DestinationRole.StationBerthRole(StationId("terminal"), berthId = "one", doorSide = DoorSide.NONE, usableLengthMeters = 130.0)))
            if (reverseAfter) destinations.register(OperationalDestination("return", platform.id, origin,
                if (direction == ViewDirection.AB) ViewDirection.BA else ViewDirection.AB, DestinationRole.Regular))
            val originalDirections = source.basicWorldData.operatingDirections.snapshot(source.topologyState,
                listOf(first.id, second.id))
            val timetable = Timetable("short-station-authority", services = listOf(TimetableService("duty",
                TrainSelector.TrainId("scheduled"), listOf(TimetableStep.Check("origin"),
                    TimetableStep.At(0, TimetableAction.DestinationOptions("terminal@one", minimumDwellTicks = 0)),
                    TimetableStep.At(0, TimetableAction.GoTo("terminal@one"))) +
                    if (reverseAfter) listOf(TimetableStep.At(0, TimetableAction.Reverse),
                        TimetableStep.At(0, TimetableAction.GoTo("return"))) else emptyList())))
            val loaded = requireNotNull(work.art1st.javarailways.bukkit.config.model.PackageLoader(
                java.io.File("../examples/models/s100")).loadTrainPackage())
            val models = loaded.cars.values.associate { it.resourceName to
                work.art1st.javarailways.bukkit.config.model.SavedCarModelTree.build(it, loaded.partRegistry) }
            val saved = work.art1st.javarailways.bukkit.config.model.SavedTrainPackageSnapshot.build("s100",
                loaded.actions, loaded.behavior, loaded.control, loaded.cars.values.associate { it.resourceName to it.control })
            val version = work.art1st.javarailways.bukkit.config.model.TrainResourceVersion(saved, models,
                models.keys.associateWith { saved }, loaded.train.composition.map {
                    loaded.cars.getValue(it.substringAfterLast('/')).resourceName })
            val exactProfile = profileFromTemplate("template", version)
            assertEquals(5, exactProfile.cars.size)
            val factory = IsolatedTimetableSimulationFactory(IsolatedSimulationProfileResolver { _, _, _ -> exactProfile },
                9.80665, extensionPlugin = BasicPlugin.INSTANCE)
            val captured = factory.capture(source, timetable, listOf(
                TimetableProfileSelection("duty", SimulationProfileType.TRAIN_TEMPLATE, "template")))
            factory.create(captured, workerThreads = 1).use { simulation ->
                val result = simulation.simulate(IsolatedTimetableSimulationRequest(timetable,
                    delayThresholdTicks = 4000, maximumSimulationTicks = 6000))
                if (shouldComplete) {
                    assertTrue(result is IsolatedTimetableSimulationResult.Completed, result.toString())
                    val frame = simulation.world.allTrains.single().publishedFrame
                    assertEquals(platform.id, frame.head.railId)
                    if (reverseAfter) {
                        assertEquals(origin, frame.head.arcLengthInAbMeters, 0.1)
                        assertEquals(if (direction == ViewDirection.AB) ViewDirection.BA else ViewDirection.AB, frame.head.facing)
                    } else {
                        val signedError = (frame.head.arcLengthInAbMeters - nominalStop) *
                            if (direction == ViewDirection.AB) 1.0 else -1.0
                        assertTrue(signedError in -0.1..0.0, "station position error=$signedError")
                    }
                    assertEquals(0.0, frame.speedMetersPerSecond)
                    assertFalse(simulation.world.simpleAtoWorldData.atoCoordinator.isStalled)
                    assertEquals(TimetableServiceRunState.COMPLETED, result.runs.single().state)
                } else {
                    assertTrue(result is IsolatedTimetableSimulationResult.Failed, result.toString())
                    val reason = (result as IsolatedTimetableSimulationResult.Failed).reason
                    assertTrue(reason.contains("terminal", ignoreCase = true) ||
                        reason.contains("direction", ignoreCase = true), reason)
                    assertEquals(origin, simulation.world.allTrains.single().publishedFrame.head.arcLengthInAbMeters, 1e-8)
                }
            }
            assertTrue(source.allTrains.isEmpty(), "simulation must not place a source-world train")
            assertEquals(originalDirections.directions, source.basicWorldData.operatingDirections.snapshot(
                source.topologyState, listOf(first.id, second.id)).directions)
        } finally {
            source.physicsCoordinator.close()
            (source.tickManager as AutoCloseable).close()
        }
    }

    private fun profile(id: String = "template"): SimulationControlProfile {
        val carControl = CarControlConfig(
            motor = MotorControlConfig(enabled = true, maxForce = 80_000.0, maxPower = 800_000.0)
        )
        return SimulationControlProfile(
            SimulationProfileType.TRAIN_TEMPLATE,
            id,
            TrainControlConfig(maxSpeed = 10.0),
            listOf(carControl),
            listOf(SimulationCarBlueprint(10.0, 0.0, 0.0, 0.0, 3.5, -3.5, carControl, CapturedCarBehavior(null, emptyMap(), emptyList())))
        )
    }

    private fun registerWorldData(world: CoreWorld,
        candidateFacts: (work.art1st.javarailways.core.api.physics.train.Train) -> TrainCandidateFacts? = { null }) {
        val basicData = BasicWorldData()
        world.registerExtensionData(BasicPlugin.INSTANCE, basicData)
        val atoCoordinator = AtoWorldCoordinator(safetyInterlock = basicData.safetyInterlock)
        val worldId = atoCoordinator.worldId
        val restrictions = TrainRestrictionSnapshotStore(worldId)
        val inspection = InspectionRuntime(restrictions) { _, _, failure -> throw failure }
        val signalEpoch = SignalEpochRuntime(
            worldId,
            restrictions,
            restrictions::completeEpoch,
            inspection::markDirty
        )
        val dispatcher = TimetableDispatcher(
            TimetableRouteAdmissionContextProvider { null },
            restrictions,
            inspection,
            object : InfrastructureDependencyService {
                override fun set(request: InfrastructureDependencyRequest) =
                    InfrastructureDependencyMutationResult(
                        InfrastructureDependencyMutationStatus.APPLIED
                    )

                override fun remove(owner: InfrastructureOwner) =
                    InfrastructureDependencyMutationResult(
                        InfrastructureDependencyMutationStatus.REMOVED
                    )
            }
        )
        world.registerExtensionData(
            SimpleATOPlugin.INSTANCE,
            SimpleATOWorldData(
                atoCoordinator,
                dispatcher,
                signalEpoch,
                restrictions,
                inspection,
                basicData.railObjects,
                basicData.safetyInterlock,
                candidateFacts = candidateFacts,
                depots = DepotStore {
                    world.allTrains.flatMap { train ->
                        train.composition.units.map { FixedEmuUnit(it.unitId, it.carIds) }
                    }
                }
            )
        )
    }
}
