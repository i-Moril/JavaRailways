package work.art1st.javarailways.simpleato.timetable

import org.junit.jupiter.api.function.Executable
import org.junit.jupiter.api.Assertions.assertAll
import work.art1st.javarailways.core.physics.train.TestWorldFixture
import work.art1st.javarailways.core.util.IsolatedSimulationThreadMarker

import work.art1st.javarailways.core.physics.train.onOwnerForTest

import work.art1st.javarailways.core.physics.train.setControllerForTest

import work.art1st.javarailways.simpleato.simpleAtoWorldData

import work.art1st.javarailways.core.physics.createRailForTest

import kotlinx.serialization.json.JsonObject
import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.BasicWorldData
import work.art1st.javarailways.basic.WayPoint
import work.art1st.javarailways.simpleato.api.ato.AtoSafetyProfile
import work.art1st.javarailways.simpleato.api.ato.AtoWorldCoordinator
import work.art1st.javarailways.simpleato.api.mission.ObservedAtoMissionOrigin
import work.art1st.javarailways.simpleato.testing.KinematicTrainActuator
import work.art1st.javarailways.simpleato.api.depot.DepotId
import work.art1st.javarailways.simpleato.api.depot.DepotTrainSelector
import work.art1st.javarailways.simpleato.api.depot.RollingStockRecord
import work.art1st.javarailways.simpleato.api.depot.RollingStockState
import work.art1st.javarailways.simpleato.api.depot.StablingPointRef
import work.art1st.javarailways.simpleato.depot.DepotResetDispatchSummary
import work.art1st.javarailways.simpleato.api.destination.DestinationRole
import work.art1st.javarailways.simpleato.api.destination.OperationalDestination
import work.art1st.javarailways.simpleato.api.destination.OperationalDestinationService
import work.art1st.javarailways.simpleato.api.line.Line
import work.art1st.javarailways.simpleato.api.line.LineId
import work.art1st.javarailways.basic.api.operation.DoorSide
import work.art1st.javarailways.simpleato.api.station.Station
import work.art1st.javarailways.simpleato.api.station.StationBerth
import work.art1st.javarailways.simpleato.api.station.StationBerthRef
import work.art1st.javarailways.simpleato.api.station.StationId
import work.art1st.javarailways.simpleato.api.timetable.*
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureDependencyMutationResult
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureDependencyMutationStatus
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureDependencyRequest
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureOwner
import work.art1st.javarailways.core.api.physics.infrastructure.StableInfrastructureResource
import work.art1st.javarailways.simpleato.api.inspection.InspectionSource
import work.art1st.javarailways.simpleato.inspection.InspectionRuntime
import work.art1st.javarailways.simpleato.timetable.*
import work.art1st.javarailways.basic.controller.ManualController
import work.art1st.javarailways.simpleato.controller.SimpleATO
import work.art1st.javarailways.simpleato.restriction.emptyTrainRestrictionSnapshots
import work.art1st.javarailways.simpleato.restriction.emptyInspectionRuntime
import work.art1st.javarailways.simpleato.testing.TestSimpleATOWorldDataRegistry
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.rail.RailView
import work.art1st.javarailways.core.physics.rail.RailLocationWithFacing
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.createTrainWithConfig
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.util.AsyncTickTestSupport
import work.art1st.javarailways.core.api.threading.host.ThreadGuardHost
import work.art1st.javarailways.core.api.threading.host.ThreadGuardPlatformBridge
import work.art1st.javarailways.simpleato.persistence.SavedDispatcherV1
import kotlin.math.PI

class TimetableDispatcherTest {
    private val worldFixtures = mutableListOf<TestWorldFixture>()

    @AfterEach
    fun closeOwnedWorldFixtures() {
        val fixtures = worldFixtures.toList()
        worldFixtures.clear()
        assertAll(fixtures.flatMap { fixture ->
            listOf(
                Executable { TestSimpleATOWorldDataRegistry.unregister(fixture.world) },
                Executable { fixture.close() }
            )
        })
    }

    @Test
    fun `train information extension validation fails before provider start`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        val data = BasicWorldData()
        TestSimpleATOWorldDataRegistry.register(world, data)
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0, 1))
        val train = createTrainWithConfig("T", rail, world, listOf(10.0))
        val origin = "A"
        data.wayPoints.add(WayPoint(origin, rail.id, train.head.location.arcLength))
        val acquisition = TimetableAction.TakeFromWaypoint(listOf(origin))
        data.wayPoints.add(WayPoint("B", rail.id, train.head.location.arcLength + 30.0))
        var starts = 0
        var validations = 0
        world.simpleAtoWorldData.timetableExtensions.registerAction(object : TimetableActionProvider {
            override val typeId = "test:acquired"
            override val version = 1
            override val descriptor = TimetableExtensionDescriptor("Acquired", "Observe acquired context")
            override fun validate(parameters: JsonObject): List<String> {
                validations++
                assertEquals(kotlinx.serialization.json.JsonPrimitive("T"), parameters["name"])
                return listOf("test rejects resolved values")
            }
            override fun start(parameters: JsonObject, context: TimetableRuntimeContext): TimetableExtensionActionStartResult {
                starts++
                assertEquals(origin, context.currentWayPointId)
                assertEquals(train.id, context.train?.trainId)
                return TimetableExtensionActionStartResult.Accepted(object : TimetableExtensionActionTicket {
                    override fun snapshot() = TimetableExtensionActionSnapshot(TimetableExtensionActionState.SUCCEEDED)
                    override fun cancel() = Unit
                })
            }
        })
        world.simpleAtoWorldData.timetables.add(Timetable("extension-first", services = listOf(TimetableService("s",
            steps = listOf(TimetableStep.At(0L, acquisition),
                TimetableStep.At(0L, TimetableAction.Extension(TimetableExtensionCall("test:acquired"), mapOf("name" to TrainInfoField.NAME))),
                TimetableStep.At(0L, TimetableAction.GoTo("B")))))))
        val dispatcher = dispatcher()
        val result = world.onOwnerForTest { dispatcher.startService(world, "extension-first", "s") }
        assertTrue(result is TimetableServiceStartResult.Failure, result.toString())
        val id = TimetableServiceRunId("extension-first", "s")
        assertEquals(0, starts)
        assertEquals(1, validations)
        assertTrue(dispatcher.status(id)?.state == TimetableServiceRunState.FAILED ||
            world.simpleAtoWorldData.atoCoordinator.isStalled, dispatcher.status(id).toString())
    }

    @Test
    fun `extension after stopped acquisition starts once without a first mission and holds until ticket success`() {
        for (depotTake in listOf(false, true)) {
            val world = TestWorldFixture.create().also(worldFixtures::add).world
            val data = BasicWorldData()
            TestSimpleATOWorldDataRegistry.register(world, data)
            val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0, 1))
            val train = createTrainWithConfig("T", rail, world, listOf(10.0))
            val origin = if (depotTake) "A@one" else "A"
            val acquisition = if (depotTake) {
                val depot = DepotId("A")
                OperationalDestinationService(world).register(OperationalDestination(origin, rail.id,
                    train.head.location.arcLength, ViewDirection.AB,
                    DestinationRole.DepotStablingPointRole("A", "one", usableLengthMeters = 30.0)))
                world.simpleAtoWorldData.depots.registerRollingStock(RollingStockRecord(train.id, depot,
                    "test/emu", emptySet(), 1, 10.0, 20.0, emptySet()), StablingPointRef(depot, "one"))
                TimetableAction.TakeFromDepot(DepotTrainSelector(depot))
            } else {
                data.wayPoints.add(WayPoint(origin, rail.id, train.head.location.arcLength))
                TimetableAction.TakeFromWaypoint(listOf(origin))
            }
            data.wayPoints.add(WayPoint("B", rail.id, train.head.location.arcLength + 30.0))
            var starts = 0
            var succeeded = false
            world.simpleAtoWorldData.timetableExtensions.registerAction(object : TimetableActionProvider {
                override val typeId = "test:acquired"
                override val version = 1
                override val descriptor = TimetableExtensionDescriptor("Acquired", "Observe acquired context")
                override fun start(parameters: JsonObject, context: TimetableRuntimeContext): TimetableExtensionActionStartResult {
                    starts++
                    assertEquals(origin, context.currentWayPointId)
                    assertEquals(train.id, context.train?.trainId)
                    return TimetableExtensionActionStartResult.Accepted(object : TimetableExtensionActionTicket {
                        override fun snapshot() = TimetableExtensionActionSnapshot(if (succeeded)
                            TimetableExtensionActionState.SUCCEEDED else TimetableExtensionActionState.PENDING)
                        override fun cancel() = Unit
                    })
                }
            })
            world.simpleAtoWorldData.timetables.add(Timetable("extension-first", services = listOf(TimetableService("s",
                steps = listOf(TimetableStep.At(0L, acquisition),
                    TimetableStep.At(0L, TimetableAction.Extension(TimetableExtensionCall("test:acquired"))),
                    TimetableStep.At(0L, TimetableAction.GoTo("B")))))))
            val dispatcher = dispatcher()
            val result = world.onOwnerForTest { dispatcher.startService(world, "extension-first", "s") }
            assertTrue(result is TimetableServiceStartResult.Started, result.toString())
            val id = TimetableServiceRunId("extension-first", "s")
            val ato = train.controller as SimpleATO
            val initialPosition = train.publishedFrame.head
            repeat(3) {
                tickAndPublish(ato); world.onOwnerForTest { dispatcher.tick(world) }
                assertEquals(TimetableServiceRunState.WAITING_EXTENSION_ACTION, dispatcher.status(id)?.state)
                assertEquals(initialPosition, train.publishedFrame.head)
                assertEquals(0L, dispatcher.status(id)?.missionRevision)
            }
            assertEquals(1, starts)
            succeeded = true
            tickAndPublish(ato); world.onOwnerForTest { dispatcher.tick(world) }
            assertEquals(3, dispatcher.status(id)?.nextStepIndex)
            repeat(10) { tickAndPublish(ato); world.onOwnerForTest { dispatcher.tick(world) } }
            assertEquals(1, starts)
            assertTrue(train.speed > 0.0, dispatcher.status(id).toString())
        }
    }

    @Test
    fun `parked undershoot keeps the first occurrence of a retained loop mission`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        val data = BasicWorldData()
        TestSimpleATOWorldDataRegistry.register(world, data)
        val (rail, returnRail) = depotLoop(world)
        val train = createTrainWithConfig("T", rail, world, listOf(10.0))
        val actualHead = train.publishedFrame.head
        val actualArc = actualHead.arcLengthInAbMeters
        // The authored route must go around the loop, without overlapping its own intervals.
        // The accepted 0.1m undershoot also belongs to the LAST occurrence of this rail.
        data.wayPoints.add(WayPoint("A", rail.id, actualArc + 0.1))
        data.wayPoints.add(WayPoint("B", rail.id, actualArc + 0.05))
        world.simpleAtoWorldData.timetables.add(Timetable("parked-loop", services = listOf(
            TimetableService("s", TrainSelector.TrainId(train.id), listOf(
                TimetableStep.Check("A"), TimetableStep.At(0L, TimetableAction.GoTo("B")))))))
        val dispatcher = dispatcher()
        val started = world.onOwnerForTest { dispatcher.startService(world, "parked-loop", "s") }
        assertTrue(started is TimetableServiceStartResult.Started, started.toString())
        val ato = train.controller as SimpleATO
        tickAndPublish(ato)
        val mission = requireNotNull(ato.currentMission)
        val segments = mission.legs.single().plannedRoute.expandedRoute.segments
        assertEquals(listOf(rail.id, returnRail.id, rail.id), segments.map { it.railViewRef.railId })
        assertEquals(actualArc, segments.first().startArcLength)
        assertEquals(actualArc + 0.05, segments.last().endArcLength)
        assertEquals(actualHead, train.publishedFrame.head)
    }

    @Test
    fun `undirected input retains only facing that avoids unspecified loop waypoints`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        val data = BasicWorldData()
        TestSimpleATOWorldDataRegistry.register(world, data)
        val (rail, _) = depotLoop(world)
        listOf("A" to 20.0, "B" to 50.0, "C" to 80.0).forEach { (id, arc) ->
            data.wayPoints.add(WayPoint(id, rail.id, arc))
        }
        val timetable = Timetable("facings", services = listOf(TimetableService("s", TrainSelector.TrainId("T"),
            listOf(TimetableStep.Check("A"), TimetableStep.At(0L, TimetableAction.GoTo("B")),
                TimetableStep.At(1L, TimetableAction.GoTo("C"))))))
        val result = TimetableValidator(world).validate(timetable)
        assertTrue(result.isSuccess, result.errors.toString())
        assertEquals(2, result.retainedRoutes.size)
        assertEquals(setOf(ViewDirection.AB), result.retainedRoutes.keys.filter { it.stepIndex == 1 }.map { it.startDirection }.toSet())
        assertEquals(setOf(ViewDirection.AB), result.retainedRoutes.keys.filter { it.stepIndex == 2 }.map { it.startDirection }.toSet())
    }

    @Test
    fun `delayed movement consumes retained validation route and refuses rebound endpoint without searching`() {
        for (rebind in listOf(false, true)) {
            val world = TestWorldFixture.create().also(worldFixtures::add).world
            val data = BasicWorldData()
            TestSimpleATOWorldDataRegistry.register(world, data)
            val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0, 1))
            val train = createTrainWithConfig("T", rail, world, listOf(10.0))
            val arc = train.head.location.arcLength
            data.wayPoints.add(WayPoint("A", rail.id, arc))
            data.wayPoints.add(WayPoint("B", rail.id, arc + 30.0))
            world.simpleAtoWorldData.timetables.add(Timetable("retained", services = listOf(TimetableService("s",
                TrainSelector.TrainId(train.id), listOf(TimetableStep.Check("A"),
                    TimetableStep.At(5L, TimetableAction.WaitUntil), TimetableStep.At(5L, TimetableAction.GoTo("B")))))))
            val dispatcher = dispatcher()
            val start = world.onOwnerForTest { dispatcher.startService(world, "retained", "s") }
            assertTrue(start is TimetableServiceStartResult.Started, start.toString())
            var searches = 0
            world.simpleAtoWorldData.routePlanningRestrictions.replaceSources(listOf(
                work.art1st.javarailways.simpleato.api.route.RoutePlanningRestrictionBinding("test:detect-replanning",
                    work.art1st.javarailways.basic.api.route.planning.RoutePlanningRestrictionSource {
                        searches++
                        error("runtime must consume the retained route instead of searching")
                    })))
            if (rebind) data.wayPoints.add(WayPoint("B", rail.id, arc + 50.0))
            val ato = train.controller as SimpleATO
            repeat(5) { tickAndPublish(ato); world.onOwnerForTest { dispatcher.tick(world) } }
            val status = dispatcher.status(TimetableServiceRunId("retained", "s"))!!
            assertEquals(0, searches)
            if (rebind) {
                assertEquals(TimetableServiceRunState.FAILED, status.state)
                assertTrue(status.message.orEmpty().contains("endpoint geometry changed"), status.toString())
            } else {
                // Neutral terminal-margin planning remains a separate safety operation.
                // The mission itself was installed above without re-entering its selector.
                world.simpleAtoWorldData.routePlanningRestrictions.replaceSources(emptyList())
                repeat(10) { tickAndPublish(ato); world.onOwnerForTest { dispatcher.tick(world) } }
                assertTrue(train.speed > 0.0, dispatcher.status(TimetableServiceRunId("retained", "s")).toString())
            }
        }
    }

    @Test
    fun `relative wait after physical acquisition starts at actual entry even when acquisition is late`() {
        for (depotTake in listOf(false, true)) for (lateTicks in listOf(0, 20)) for (absolute in listOf(false, true)) {
            val world = TestWorldFixture.create().also(worldFixtures::add).world
            val data = BasicWorldData()
            TestSimpleATOWorldDataRegistry.register(world, data)
            val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0, 1))
            val train = createTrainWithConfig("T", rail, world, listOf(10.0))
            val origin = if (depotTake) "A@one" else "A"
            val acquisition = if (depotTake) {
                val depot = DepotId("A")
                OperationalDestinationService(world).register(OperationalDestination(origin, rail.id,
                    train.head.location.arcLength, ViewDirection.AB,
                    DestinationRole.DepotStablingPointRole("A", "one", usableLengthMeters = 30.0)))
                world.simpleAtoWorldData.depots.registerRollingStock(RollingStockRecord(train.id, depot,
                    "test/emu", emptySet(), 1, 10.0, 20.0, emptySet()), StablingPointRef(depot, "one"))
                TimetableAction.TakeFromDepot(DepotTrainSelector(depot))
            } else {
                data.wayPoints.add(WayPoint(origin, rail.id, train.head.location.arcLength))
                TimetableAction.TakeFromWaypoint(listOf(origin))
            }
            data.wayPoints.add(WayPoint("B", rail.id, train.head.location.arcLength + 30.0))
            val wait = if (absolute) TimetableStep.At(lateTicks + 5L, TimetableAction.WaitUntil)
                else TimetableStep.At(0L, TimetableAction.Stop(5L))
            val movementTick = if (absolute) lateTicks + 5L else 0L
            world.simpleAtoWorldData.timetables.add(Timetable("relative", services = listOf(TimetableService("s",
                steps = listOf(TimetableStep.At(0L, acquisition), wait,
                    TimetableStep.At(movementTick, TimetableAction.GoTo("B")))))))
            val dispatcher = dispatcher()
            assertTrue(world.onOwnerForTest { dispatcher.beginTimetable(world, "relative") } is TimetableEpochStartResult.Started)
            repeat(lateTicks) { world.onOwnerForTest { dispatcher.tick(world) } }
            val started = world.onOwnerForTest { dispatcher.startService(world, "relative", "s") }
            assertTrue(started is TimetableServiceStartResult.Started, started.toString())
            val id = TimetableServiceRunId("relative", "s")
            val ato = train.controller as SimpleATO
            val startArc = train.head.location.arcLength
            val waitingState = if (absolute) TimetableServiceRunState.WAITING_TIMETABLE_TIME else TimetableServiceRunState.DWELLING
            assertEquals(waitingState, dispatcher.status(id)?.state)
            assertEquals(if (absolute) 1 else 2, dispatcher.status(id)?.nextStepIndex)
            repeat(4) {
                tickAndPublish(ato)
                world.onOwnerForTest { dispatcher.tick(world) }
                assertEquals(waitingState, dispatcher.status(id)?.state)
                assertEquals(startArc, train.head.location.arcLength, 1e-9)
                assertEquals(0.0, train.speed, 1e-9)
            }
            tickAndPublish(ato)
            world.onOwnerForTest { dispatcher.tick(world) }
            assertEquals(lateTicks + 5L, dispatcher.status(id)?.timetableElapsedTicks)
            assertEquals(3, dispatcher.status(id)?.nextStepIndex)
            assertTrue(dispatcher.status(id)?.state != TimetableServiceRunState.DWELLING)
            repeat(10) { tickAndPublish(ato); world.onOwnerForTest { dispatcher.tick(world) } }
            assertTrue(train.speed > 0.0, dispatcher.status(id).toString())
        }
    }

    @Test
    fun `physical depot acquisition passes exact Core car membership to fixed unit ledger`() {
        for (partial in listOf(false, true)) {
            val world = TestWorldFixture.create().also(worldFixtures::add).world
            val data = BasicWorldData()
            TestSimpleATOWorldDataRegistry.register(world, data)
            val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0, 1))
            val train = createTrainWithConfig("T", rail, world, listOf(10.0))
            val depot = DepotId("A")
            OperationalDestinationService(world).register(OperationalDestination("A@one", rail.id,
                train.head.location.arcLength, null,
                DestinationRole.DepotStablingPointRole("A", "one", usableLengthMeters = 30.0)))
            data.wayPoints.add(WayPoint("B", rail.id, train.head.location.arcLength + 30.0))
            val store = world.simpleAtoWorldData.depots
            store.registerRollingStock(RollingStockRecord(train.id, depot, "test/emu", emptySet(), 1,
                10.0, 20.0, emptySet()), StablingPointRef(depot, "one"))
            val registeredCars = train.cars.map { it.id } + if (partial) listOf("absent-car") else emptyList()
            store.registerFixedUnit(work.art1st.javarailways.simpleato.api.depot.FixedEmuUnit(
                "unit", depot, registeredCars, "emu/control"), registeredCars)
            world.simpleAtoWorldData.timetables.add(Timetable("unit", services = listOf(TimetableService("s",
                steps = listOf(TimetableStep.At(0L, TimetableAction.TakeFromDepot(DepotTrainSelector(depot))),
                    TimetableStep.At(0L, TimetableAction.GoTo("B")),
                    TimetableStep.At(100L, TimetableAction.Reverse),
                    TimetableStep.At(100L, TimetableAction.StoreAtDepot(depot)))))))
            val dispatcher = dispatcher()
            val result = world.onOwnerForTest { dispatcher.startService(world, "unit", "s") }
            if (partial) {
                assertTrue(result is TimetableServiceStartResult.Failure)
                assertEquals(RollingStockState.AVAILABLE, store.rollingStock(train.id)?.state)
                assertNull(train.controller)
            } else {
                assertTrue(result is TimetableServiceStartResult.Started, result.toString())
                assertEquals(RollingStockState.IN_SERVICE, store.rollingStock(train.id)?.state)
            }
        }
    }

    @Test
    fun `waitUntil holds stopped train then continues immediately without completing the service`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        val data = BasicWorldData()
        TestSimpleATOWorldDataRegistry.register(world, data)
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0, 1))
        val train = createTrainWithConfig("T1", rail, world, listOf(10.0))
        data.wayPoints.add(WayPoint("A", rail.id, train.head.location.arcLength))
        data.wayPoints.add(WayPoint("B", rail.id, train.head.location.arcLength + 30.0))
        world.simpleAtoWorldData.timetables.add(Timetable("wait", services = listOf(TimetableService("s",
            TrainSelector.TrainId(train.id), listOf(TimetableStep.Check("A"),
                TimetableStep.At(5L, TimetableAction.WaitUntil), TimetableStep.At(5L, TimetableAction.GoTo("B")))))))
        val dispatcher = dispatcher()
        val started = world.onOwnerForTest { dispatcher.startService(world, "wait", "s") } as TimetableServiceStartResult.Started
        val ato = train.controller as SimpleATO
        assertEquals(TimetableServiceRunState.WAITING_TIMETABLE_TIME, started.run.state)
        val id = TimetableServiceRunId("wait", "s")
        repeat(4) {
            tickAndPublish(ato)
            world.onOwnerForTest { dispatcher.tick(world) }
            assertEquals(TimetableServiceRunState.WAITING_TIMETABLE_TIME, dispatcher.status(id)?.state)
            assertEquals(0.0, train.speed, 1e-9)
        }
        tickAndPublish(ato)
        world.onOwnerForTest { dispatcher.tick(world) }
        assertEquals(5L, dispatcher.status(id)?.timetableElapsedTicks)
        assertTrue(dispatcher.status(id)?.state != TimetableServiceRunState.WAITING_TIMETABLE_TIME)
        assertTrue(dispatcher.status(id)?.state != TimetableServiceRunState.COMPLETED)
        repeat(10) {
            tickAndPublish(ato)
            world.onOwnerForTest { dispatcher.tick(world) }
        }
        assertTrue(train.speed > 0.0, dispatcher.status(id).toString())
    }

    @Test
    fun `terminal completion upgrades selected rail guard before resource collection and rolls back on upgrade failure`() {
        for (denyUpgrade in listOf(false, true)) {
            val world = TestWorldFixture.create().also(worldFixtures::add).world
            val data = BasicWorldData()
            TestSimpleATOWorldDataRegistry.register(world, data)
            val first = rail(world, Vector3d(0.0, 0.0, 0.0), Vector3d(50.0, 0.0, 0.0))
            val second = rail(world, Vector3d(50.0, 0.0, 0.0), Vector3d(100.0, 0.0, 0.0))
            IsolatedSimulationThreadMarker.run { CoreRail.connect(first, second) }
            val train = createTrainWithConfig("T1", first, world, listOf(10.0))
            data.wayPoints.add(WayPoint("A", first.id, train.head.location.arcLength))
            data.wayPoints.add(WayPoint("B", first.id, 30.0))
            world.simpleAtoWorldData.timetables.add(timetable("completed", "s1", train.id, "A", "B"))
            val plans = RecordingInfrastructureDependencyService()
            val service = object : InfrastructureDependencyService {
                override fun set(request: InfrastructureDependencyRequest): InfrastructureDependencyMutationResult {
                    if (denyUpgrade && second.id in request.dependencies.railIds) return InfrastructureDependencyMutationResult(
                        InfrastructureDependencyMutationStatus.EDIT_IN_PROGRESS, "test expanded guard refusal")
                    return plans.set(request)
                }
                override fun remove(owner: InfrastructureOwner) = plans.remove(owner)
            }
            var collected = false
            val inspection = emptyInspectionRuntime()
            inspection.replaceSources(listOf(InspectionSource("test:terminal", { error("no live train") }, { emptyList() },
                { completed ->
                    collected = true
                    assertEquals(setOf(first.id, second.id), plans.applied.values.single().dependencies.railIds)
                    assertEquals(second.id, completed.segments.last().railId)
                    emptyList()
                }, { selected, _ ->
                    assertEquals(setOf(first.id), plans.applied.values.single().dependencies.railIds)
                    val end = selected.segments.last()
                    listOf(work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot(
                        first.id, ViewDirection.AB, end.endArcLengthMeters, 50.0),
                        work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot(
                            second.id, ViewDirection.AB, 0.0, 20.0))
                })))
            val dispatcher = dispatcher(service, inspection)
            val result = world.onOwnerForTest { dispatcher.beginTimetable(world, "completed") }
            if (denyUpgrade) {
                assertTrue(result is TimetableEpochStartResult.Failure)
                assertFalse(collected, "resource callbacks cannot run without the extended rail guard")
                assertTrue(plans.applied.isEmpty(), "failed first apply must remove its original guard")
            } else {
                assertTrue(result is TimetableEpochStartResult.Started)
                assertTrue(collected)
                assertEquals(setOf(first.id, second.id), plans.applied.values.single().dependencies.railIds)
            }
        }
    }

    @Test
    fun `apply merges plugin resources and failed replacement keeps old dependencies and clock`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        val data = BasicWorldData()
        TestSimpleATOWorldDataRegistry.register(world, data)
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(50.0, 1))
        val train = createTrainWithConfig("T1", rail, world, listOf(10.0))
        val origin = train.head.location.arcLength
        data.wayPoints.add(WayPoint("A", rail.id, origin))
        data.wayPoints.add(WayPoint("B", rail.id, origin + 20.0))
        world.simpleAtoWorldData.timetables.add(timetable("tt-signal", "s1", train.id, "A", "B"))
        val token = StableInfrastructureResource("test:block", "selected")
        val replacement = StableInfrastructureResource("test:block", "replacement")
        var resources = listOf(token)
        var fail = false
        val plans = RecordingInfrastructureDependencyService()
        val inspection = emptyInspectionRuntime()
        inspection.replaceSources(listOf(InspectionSource("test:signals",
            { error("must not create train bindings on apply") },
            { error("must not read live signal state on apply") },
            { selected ->
                assertEquals(world.topologyState.revision, selected.topologyRevision)
                assertEquals(setOf(rail.id), selected.segments.map { it.railId }.toSet())
                assertEquals(setOf(rail.id), plans.applied.values.single().dependencies.railIds,
                    "physical dependencies must already protect matching")
                if (fail) assertEquals(setOf(token), plans.applied.values.single().dependencies.resourceIds,
                    "the guard retains the previous signal resources")
                if (fail) error("stale block definitions")
                assertEquals(origin + 30.0, selected.segments.last().endArcLengthMeters, 1.0e-9)
                resources
            }, terminalRouteContinuation = { selected, _ ->
                assertEquals(setOf(rail.id), plans.applied.values.single().dependencies.railIds,
                    "the selected physical route must be guarded before plugin geometry is read")
                val end = selected.segments.last()
                listOf(work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot(
                    rail.id, end.facing, end.endArcLengthMeters, end.endArcLengthMeters + 10.0))
            })))
        val dispatcher = dispatcher(plans, inspection)
        assertTrue(TimetableValidator(world).validate(world.simpleAtoWorldData.timetables["tt-signal"]!!).isSuccess)
        assertTrue(plans.applied.isEmpty(), "draft storage publishes no dependencies")
        assertTrue(world.onOwnerForTest { dispatcher.beginTimetable(world, "tt-signal") } is TimetableEpochStartResult.Started)
        val installed = plans.applied.values.single()
        assertEquals(setOf(rail.id), installed.dependencies.railIds)
        assertEquals(setOf(token), installed.dependencies.resourceIds)

        fail = true
        val rejected = world.onOwnerForTest { dispatcher.beginTimetable(world, "tt-signal") } as TimetableEpochStartResult.Failure
        assertTrue(rejected.reason.contains("test:signals"))
        assertEquals(installed.dependencies, plans.applied.values.single().dependencies)
        assertTrue(plans.applied.values.single().owner.revision > installed.owner.revision)
        assertEquals(4, plans.mutations.size)
        assertTrue(world.onOwnerForTest { dispatcher.scheduleInitialCycle("tt-signal") }, "failed replacement retains old clock")

        fail = false
        resources = listOf(replacement)
        assertTrue(world.onOwnerForTest { dispatcher.beginTimetable(world, "tt-signal") } is TimetableEpochStartResult.Started)
        assertEquals(setOf(replacement), plans.applied.values.single().dependencies.resourceIds)
        assertTrue(plans.applied.values.single().owner.revision > installed.owner.revision)
        assertTrue(world.onOwnerForTest { dispatcher.stopFutureCycles("tt-signal") })
        assertTrue(plans.applied.isEmpty())
    }

    @Test
    fun `guarded apply failures restore prior dependencies or clear first apply and retain failed rollback protection`() {
        for (previous in listOf(false, true)) for (cause in listOf("collect", "final", "rollback", "initial")) {
            val world = TestWorldFixture.create().also(worldFixtures::add).world
            val data = BasicWorldData()
            TestSimpleATOWorldDataRegistry.register(world, data)
            val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(50.0, 1))
            val train = createTrainWithConfig("T1", rail, world, listOf(10.0))
            val origin = train.head.location.arcLength
            data.wayPoints.add(WayPoint("A", rail.id, origin))
            data.wayPoints.add(WayPoint("B", rail.id, origin + 20.0))
            val timetable = timetable("guarded", "s1", train.id, "A", "B").copy(
                initialPositions = if (cause == "initial") listOf(TimetableInitialPosition(
                    TimetableObjectSelector.Regex(train.name), TimetableObjectSelector.Regex("A"))) else emptyList())
            world.simpleAtoWorldData.timetables.add(timetable)
            val oldToken = StableInfrastructureResource("test:block", "old")
            val nextToken = StableInfrastructureResource("test:block", "next")
            val plans = RecordingInfrastructureDependencyService()
            var failing = false
            var setCalls = 0
            val service = object : InfrastructureDependencyService {
                override fun set(request: InfrastructureDependencyRequest): InfrastructureDependencyMutationResult {
                    setCalls++
                    if (failing && setCalls == 2 && cause in listOf("final", "rollback")) {
                        return InfrastructureDependencyMutationResult(InfrastructureDependencyMutationStatus.EDIT_IN_PROGRESS,
                            "injected $cause failure")
                    }
                    val result = plans.set(request)
                    if (failing && setCalls == 2 && cause == "initial") train.setController(ManualController(train))
                    return result
                }
                override fun remove(owner: InfrastructureOwner): InfrastructureDependencyMutationResult =
                    if (failing && cause == "rollback")
                        InfrastructureDependencyMutationResult(InfrastructureDependencyMutationStatus.SERVICE_CLOSED)
                    else plans.remove(owner)
            }
            val inspection = emptyInspectionRuntime()
            inspection.replaceSources(listOf(InspectionSource("test:signals", { error("no live binding") },
                { error("no live inspection") }, {
                    val guard = plans.applied.values.single()
                    assertEquals(setOf(rail.id), guard.dependencies.railIds)
                    assertEquals(if (failing && previous) setOf(oldToken) else emptySet(), guard.dependencies.resourceIds)
                    if (failing && cause in listOf("collect", "rollback")) error("collection failed")
                    listOf(if (failing) nextToken else oldToken)
                })))
            val dispatcher = dispatcher(service, inspection)
            if (previous) assertTrue(world.onOwnerForTest { dispatcher.beginTimetable(world, "guarded") } is TimetableEpochStartResult.Started)
            val old = plans.applied.values.singleOrNull()
            val oldClocks = dispatcher.saveCheckpoint().clocks
            setCalls = 0
            failing = true

            val result = world.onOwnerForTest { dispatcher.beginTimetable(world, "guarded") }

            assertTrue(result is TimetableEpochStartResult.Failure, "$previous/$cause: $result")
            assertEquals(oldClocks, dispatcher.saveCheckpoint().clocks)
            if (cause == "rollback") {
                assertTrue(world.simpleAtoWorldData.atoCoordinator.isStalled)
                val guard = plans.applied.values.single()
                assertEquals(setOf(rail.id), guard.dependencies.railIds)
                assertEquals(if (previous) setOf(oldToken) else emptySet(), guard.dependencies.resourceIds)
                assertThrows(IllegalStateException::class.java) {
                    dispatcher.restoreCheckpoint(world, SavedDispatcherV1(1, 0L, 0L, emptyList(), emptyList()))
                }
                world.onOwnerForTest { dispatcher.tick(world) }
                assertSame(guard, plans.applied.values.single(), "stalled ticks must keep the tracked guard")
            } else if (old == null) {
                assertTrue(plans.applied.isEmpty(), "$cause must clear first-apply protection")
            } else {
                assertEquals(old.dependencies, plans.applied.values.single().dependencies)
                assertTrue(plans.applied.values.single().owner.revision > old.owner.revision)
            }
            assertEquals(cause in listOf("rollback", "initial"), world.simpleAtoWorldData.atoCoordinator.isStalled)
        }
    }

    @Test
    fun `timetable without known routes applies without creating an empty dependency`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        val data = BasicWorldData()
        TestSimpleATOWorldDataRegistry.register(world, data)
        world.simpleAtoWorldData.timetables.add(Timetable("empty"))
        val plans = RecordingInfrastructureDependencyService()
        val dispatcher = dispatcher(plans)

        assertTrue(world.onOwnerForTest { dispatcher.beginTimetable(world, "empty") } is TimetableEpochStartResult.Started)
        assertTrue(world.onOwnerForTest { dispatcher.scheduleInitialCycle("empty") })
        assertTrue(plans.applied.isEmpty())
    }

    @Test
    fun `timetable apply installs exact physical dependencies and releases them after drain`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        val data = BasicWorldData()
        TestSimpleATOWorldDataRegistry.register(world, data)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(50.0, segmentCount = 1)
        )
        val train = createTrainWithConfig("T1", rail, world, listOf(10.0))
        val origin = train.head.location.arcLength
        data.wayPoints.add(WayPoint("A", rail.id, origin))
        data.wayPoints.add(WayPoint("B", rail.id, origin + 20.0))
        world.simpleAtoWorldData.timetables.add(timetable("tt-plan", "s1", train.id, "A", "B"))
        val plans = RecordingInfrastructureDependencyService()
        val dispatcher = dispatcher(plans)

        assertTrue(world.onOwnerForTest { dispatcher.beginTimetable(world, "tt-plan") } is TimetableEpochStartResult.Started)
        assertEquals(setOf(rail.id), plans.applied.values.single().dependencies.railIds)

        assertTrue(world.onOwnerForTest { dispatcher.stopFutureCycles("tt-plan") })
        assertTrue(plans.applied.isEmpty())
    }

    @Test
    fun `timetable apply publishes no clock when dependency installation fails`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        val data = BasicWorldData()
        TestSimpleATOWorldDataRegistry.register(world, data)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(50.0, segmentCount = 1)
        )
        val train = createTrainWithConfig("T1", rail, world, listOf(10.0))
        val origin = train.head.location.arcLength
        data.wayPoints.add(WayPoint("A", rail.id, origin))
        data.wayPoints.add(WayPoint("B", rail.id, origin + 20.0))
        world.simpleAtoWorldData.timetables.add(timetable("tt-plan", "s1", train.id, "A", "B"))
        val plans = RecordingInfrastructureDependencyService().apply {
            setFailure = InfrastructureDependencyMutationResult(
                InfrastructureDependencyMutationStatus.EDIT_IN_PROGRESS,
                "test edit"
            )
        }
        val dispatcher = dispatcher(plans)

        val result = world.onOwnerForTest { dispatcher.beginTimetable(world, "tt-plan") }

        assertTrue(result is TimetableEpochStartResult.Failure)
        assertFalse(world.onOwnerForTest { dispatcher.scheduleInitialCycle("tt-plan") })
        assertTrue(plans.applied.isEmpty())
    }

    @Test
    fun `stopping recurrence retains dependencies while a run is active`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        val data = BasicWorldData()
        TestSimpleATOWorldDataRegistry.register(world, data)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(50.0, segmentCount = 1)
        )
        val train = createTrainWithConfig("T1", rail, world, listOf(10.0))
        val origin = train.head.location.arcLength
        data.wayPoints.add(WayPoint("A", rail.id, origin))
        data.wayPoints.add(WayPoint("B", rail.id, origin + 20.0))
        world.simpleAtoWorldData.timetables.add(timetable("tt-plan", "s1", train.id, "A", "B"))
        val plans = RecordingInfrastructureDependencyService()
        val dispatcher = dispatcher(plans)

        assertTrue(world.onOwnerForTest { dispatcher.beginTimetable(world, "tt-plan") } is TimetableEpochStartResult.Started)
        assertTrue(world.onOwnerForTest { dispatcher.scheduleInitialCycle("tt-plan") })
        world.onOwnerForTest { dispatcher.tick(world) }
        assertNotNull(dispatcher.status(TimetableServiceRunId("tt-plan", "s1")))

        assertTrue(world.onOwnerForTest { dispatcher.stopFutureCycles("tt-plan") })
        assertTrue(plans.applied.isNotEmpty())
    }

    @Test
    fun `depot reset progress preserves completed mutation counts for partial reports`() {
        val progress = TimetableDepotResetProgress()

        progress.recordBerthRelease()
        progress.recordRunRemoval()
        progress.recordStoppedTimetable("tt-a")

        assertEquals(
            DepotResetDispatchSummary(
                removedRunCount = 1,
                releasedBerthCount = 1,
                stoppedTimetableIds = setOf("tt-a")
            ),
            progress.snapshot()
        )
    }


    private val profile = AtoSafetyProfile(
        accelerationMetersPerSecondSquared = 1.0,
        serviceDecelerationMetersPerSecondSquared = 1.0,
        reactionTimeSeconds = 0.0,
        safetyDistanceMeters = 2.0
    )


    @Test
    fun `maintenance query reports rails referenced by a live mission`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        val dispatcher = dispatcher()
        val data = BasicWorldData()
        TestSimpleATOWorldDataRegistry.register(world, data)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        val unrelated = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(20.0, segmentCount = 1)
        )
        val train = createTrainWithConfig("T1", rail, world, listOf(10.0))
        val originArc = train.head.location.arcLength
        data.wayPoints.add(WayPoint("A", rail.id, originArc))
        data.wayPoints.add(WayPoint("B", rail.id, originArc + 30.0))
        world.simpleAtoWorldData.timetables.add(timetable("tt", "s1", train.id, "A", "B"))

        assertFalse(dispatcher.hasLiveRouteOn(setOf(rail.id)))
        assertTrue(world.onOwnerForTest { dispatcher.startService(world, "tt", "s1") } is TimetableServiceStartResult.Started)

        assertTrue(dispatcher.hasLiveRouteOn(setOf(rail.id)))
        assertFalse(dispatcher.hasLiveRouteOn(setOf(unrelated.id)))
    }

    @Test
    fun `service infrastructure line maximum speed is installed in ATO`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        val dispatcher = dispatcher()
        val data = BasicWorldData()
        TestSimpleATOWorldDataRegistry.register(world, data)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        val train = createTrainWithConfig("T1", rail, world, listOf(10.0))
        val originArc = train.head.location.arcLength
        data.wayPoints.add(WayPoint("A", rail.id, originArc))
        data.wayPoints.add(WayPoint("B", rail.id, originArc + 30.0))
        val lineId = LineId("metro")
        world.simpleAtoWorldData.lines.put(
            Line(lineId, "Metro", listOf(StationId("a"), StationId("b")), listOf(StationId("b"), StationId("a"))),
            0L
        )
        val base = timetable("tt", "s1", train.id, "A", "B")
        world.simpleAtoWorldData.timetables.add(base.copy(
            services = listOf(base.services.single().copy(lineId = lineId)),
            lineSpeedLimits = listOf(TimetableLineSpeedLimit(lineId, 7.5))
        ))

        assertTrue(world.onOwnerForTest { dispatcher.startService(world, "tt", "s1") } is TimetableServiceStartResult.Started)
        val ato = train.controller as SimpleATO
        tickAndPublish(ato)

        val constraints = ato.publishedState.runtimeDiagnostics.constraints
        assertTrue(constraints.any {
            it.contains("external/timetable-line/tt/s1") && it.contains("maximumSpeedMetersPerSecond=7.5")
        }, constraints.toString())
    }

    @Test
    fun `run origin station metadata does not replace the route anchor after an in-place reversal`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        val dispatcher = dispatcher()
        val data = BasicWorldData()
        TestSimpleATOWorldDataRegistry.register(world, data)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(120.0, segmentCount = 1)
        )
        val train = createTrainWithConfig("T1", rail, world, listOf(10.0))
        world.onOwnerForTest {
            train.setLocation(RailLocationWithFacing(rail.viewAB, 60.0))
            train.commitMaintenancePlacement()
        }
        val originStation = StationId("origin")
        val terminalStation = StationId("terminal")
        OperationalDestinationService(world).register(OperationalDestination(
            "origin@one", rail.id, 60.0, ViewDirection.AB,
            DestinationRole.StationBerthRole(originStation, berthId = "one", doorSide = DoorSide.NONE)
        ))
        OperationalDestinationService(world).register(OperationalDestination(
            "origin@metadata", rail.id, 60.0, ViewDirection.BA,
            DestinationRole.StationBerthRole(originStation, berthId = "metadata", doorSide = DoorSide.NONE)
        ))
        OperationalDestinationService(world).register(OperationalDestination(
            "terminal@one", rail.id, 20.0, ViewDirection.BA,
            DestinationRole.StationBerthRole(terminalStation, berthId = "one", doorSide = DoorSide.NONE)
        ))
        val lineId = LineId("reverse-line")
        world.simpleAtoWorldData.lines.put(Line(
            lineId, "Reverse line", listOf(originStation, terminalStation),
            listOf(terminalStation, originStation)
        ), 0L)
        val segment = TimetableRunSegment("152P", lineId, 2, 4, calls = listOf(
            TimetableRunCall("152P-origin", originStation.value, "origin@metadata", TimetableServiceStopStatus.ORIGIN),
            TimetableRunCall("152P-terminal", terminalStation.value, "terminal@one", TimetableServiceStopStatus.TERMINAL)
        ))
        val originProgram = StationStopPrograms(
            originStation.value, "152P-origin", StationStopRole.ORIGIN,
            listOf(StationProgramAction.End), listOf(StationProgramAction.End),
            StationPreparationLead.constant(0L), 1_000L, "origin@metadata", DoorSide.NONE
        )
        world.simpleAtoWorldData.timetables.add(Timetable(
            "reverse-origin",
            services = listOf(TimetableService(
                "service", TrainSelector.TrainId(train.id), listOf(
                    TimetableStep.Check("origin@one"),
                    TimetableStep.At(0L, TimetableAction.Reverse),
                    TimetableStep.At(1L, TimetableAction.BeginStationStop(originProgram)),
                    TimetableStep.At(1_000L, TimetableAction.GoTo("terminal@one"))
                ), runSegments = listOf(segment)
            )),
            lineSpeedLimits = listOf(TimetableLineSpeedLimit(lineId, 8.0))
        ))

        val id = TimetableServiceRunId("reverse-origin", "service")
        val started = world.onOwnerForTest { dispatcher.startService(world, id.timetableId, id.serviceId) }
        assertInstanceOf(TimetableServiceStartResult.Started::class.java, started, started.toString())
        val ato = train.controller as SimpleATO
        var ticks = 0
        while (dispatcher.status(id)?.state != TimetableServiceRunState.RUNNING &&
            !world.simpleAtoWorldData.atoCoordinator.isStalled && ticks++ < 1_100) {
            tickAndPublish(ato)
            world.onOwnerForTest { dispatcher.tick(world) }
        }

        val status = checkNotNull(dispatcher.status(id))
        assertEquals("152P", status.currentRunId, status.toString())
        assertEquals(TimetableServiceRunState.RUNNING, status.state, status.toString())
        assertEquals("origin@one", status.currentWayPointId, status.toString())
        val mission = checkNotNull(ato.currentMission)
        val observedOrigin = assertInstanceOf(ObservedAtoMissionOrigin::class.java, mission.origin)
        assertEquals(train.publishedFrame.head, observedOrigin.position)
        val firstRoute = mission.legs.first().plannedRoute.expandedRoute
        assertEquals(observedOrigin.id, firstRoute.fromWayPointId)
        assertEquals(70.0, firstRoute.segments.first().startArcLength, 1.0e-7)
        assertFalse(world.simpleAtoWorldData.atoCoordinator.isStalled)
    }

    @Test
    fun `starts explicit train only after the first Basic route grant`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        val dispatcher = dispatcher()
        val data = BasicWorldData()
        TestSimpleATOWorldDataRegistry.register(world, data)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        val train = createTrainWithConfig("T1", rail, world, listOf(10.0))
        val originArc = train.head.location.arcLength
        data.wayPoints.add(WayPoint("A", rail.id, originArc))
        data.wayPoints.add(WayPoint("B", rail.id, originArc + 30.0))
        world.simpleAtoWorldData.timetables.add(timetable("tt", "s1", train.id, "A", "B"))

        val started = world.onOwnerForTest { dispatcher.startService(world, "tt", "s1") }
        assertTrue(started is TimetableServiceStartResult.Started)
        val ato = train.controller as SimpleATO
        tickAndPublish(ato)
        assertEquals(0.0, train.speed)

        world.onOwnerForTest { dispatcher.tick(world) }
        assertEquals(
            TimetableServiceRunState.RUNNING,
            dispatcher.status(TimetableServiceRunId("tt", "s1"))?.state
        )
        assertTrue(dispatcher.status(TimetableServiceRunId("tt", "s1"))?.routeGrantActive == true)
        tickAndPublish(ato)
        assertTrue(train.speed > 0.0)
        tickAndPublish(ato, 1_000.0)

        assertEquals(1.05, train.speed, 1.0e-9)

        repeat(600) {
            tickAndPublish(ato)
            world.onOwnerForTest { dispatcher.tick(world) }
        }
        assertEquals(0.0, train.speed)
        assertEquals(originArc + 30.0, train.head.location.arcLength, 0.4)
        world.onOwnerForTest { dispatcher.tick(world) }
        assertEquals(
            TimetableServiceRunState.COMPLETED,
            dispatcher.status(TimetableServiceRunId("tt", "s1"))?.state
        )

        assertTrue(world.onOwnerForTest { dispatcher.stopService(world, TimetableServiceRunId("tt", "s1")) })
        tickAndPublish(ato)
        world.onOwnerForTest { dispatcher.tick(world) }
        assertEquals(
            TimetableServiceRunState.STOPPED,
            dispatcher.status(TimetableServiceRunId("tt", "s1"))?.state
        )
        assertNotNull(train.controller)
        assertTrue(dispatcher.status(TimetableServiceRunId("tt", "s1"))?.routeGrantActive == true)
    }

    @Test
    fun `takeFromDepot deterministically assigns a compatible available train`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        val dispatcher = dispatcher()
        val data = BasicWorldData()
        TestSimpleATOWorldDataRegistry.register(world, data)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        val train = createTrainWithConfig("T1", rail, world, listOf(10.0))
        val originArc = train.head.location.arcLength
        val depotId = DepotId("north")
        OperationalDestinationService(world).register(
            OperationalDestination(
                id = "north@one",
                railId = rail.id,
                arcLengthInAbMeters = originArc,
                approachDirection = ViewDirection.AB,
                role = DestinationRole.DepotStablingPointRole("north", "one")
            )
        )
        data.wayPoints.add(WayPoint("B", rail.id, originArc + 30.0))
        world.simpleAtoWorldData.depots.registerRollingStock(
            RollingStockRecord(
                trainId = train.id,
                homeDepotId = depotId,
                modelPackageId = "test/s100",
                tags = setOf("metro"),
                carCount = 1,
                lengthMeters = 10.0,
                maximumSpeedMetersPerSecond = 20.0,
                capabilities = setOf("doors")
            ),
            StablingPointRef(depotId, "one")
        )
        world.simpleAtoWorldData.timetables.add(
            Timetable(
                "tt",
                services = listOf(
                    TimetableService(
                        "s1",
                        steps = listOf(
                            TimetableStep.At(
                                5L,
                                TimetableAction.TakeFromDepot(
                                    DepotTrainSelector(
                                        depotId,
                                        requiredTags = setOf("metro"),
                                        requiredCapabilities = setOf("doors")
                                    )
                                )
                            ),
                            TimetableStep.At(5L, TimetableAction.GoTo("B"))
                        )
                    )
                )
            )
        )

        assertTrue(world.onOwnerForTest { dispatcher.beginTimetable(world, "tt") } is TimetableEpochStartResult.Started)
        assertTrue(world.onOwnerForTest { dispatcher.scheduleInitialCycle("tt") })
        repeat(4) { world.onOwnerForTest { dispatcher.tick(world) } }
        assertEquals(RollingStockState.AVAILABLE, world.simpleAtoWorldData.depots.rollingStock(train.id)?.state)
        assertEquals(null, dispatcher.status(TimetableServiceRunId("tt", "s1")))
        world.onOwnerForTest { dispatcher.tick(world) }
        val started = dispatcher.status(TimetableServiceRunId("tt", "s1"))!!

        assertEquals(train.id, started.trainId)
        assertEquals(RollingStockState.IN_SERVICE, world.simpleAtoWorldData.depots.rollingStock(train.id)?.state)
        assertEquals("tt/s1@cycle-0", world.simpleAtoWorldData.depots.rollingStock(train.id)?.assignmentId)
        assertTrue(train.controller is SimpleATO)
    }

    @Test
    fun `takeFromWaypoint acquires a stopped unassigned train at the scheduled tick`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        val dispatcher = dispatcher()
        val data = BasicWorldData()
        TestSimpleATOWorldDataRegistry.register(world, data)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        val train = createTrainWithConfig("platform-train", rail, world, listOf(10.0))
        val originArc = train.head.location.arcLength
        data.wayPoints.add(WayPoint("platform@1", rail.id, originArc))
        data.wayPoints.add(WayPoint("next", rail.id, originArc + 30.0))
        world.simpleAtoWorldData.timetables.add(
            Timetable(
                "waypoint-tt",
                services = listOf(
                    TimetableService(
                        "s1",
                        steps = listOf(
                            TimetableStep.At(
                                3L,
                                TimetableAction.TakeFromWaypoint(
                                    listOf("platform@1")
                                )
                            ),
                            TimetableStep.At(3L, TimetableAction.GoTo("next"))
                        )
                    )
                )
            )
        )
        data.wayPoints.add(WayPoint("empty-siding", rail.id, 5.0))

        val early = world.onOwnerForTest { dispatcher.startService(world, "waypoint-tt", "s1") }
        assertTrue(early is TimetableServiceStartResult.Failure)
        assertEquals(null, train.controller)
        repeat(3) { world.onOwnerForTest { dispatcher.tick(world) } }
        val started = world.onOwnerForTest { dispatcher.startService(world, "waypoint-tt", "s1") } as
            TimetableServiceStartResult.Started

        assertEquals(train.id, started.run.trainId)
        assertTrue(train.controller is SimpleATO)
        assertFalse(world.simpleAtoWorldData.atoCoordinator.isStalled)
    }

    @Test
    fun `takeFromWaypoint inherits a completed duty and its tail-held route`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        val dispatcher = dispatcher()
        val data = BasicWorldData()
        TestSimpleATOWorldDataRegistry.register(world, data)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(160.0, segmentCount = 1)
        )
        val train = createTrainWithConfig("handoff-train", rail, world, listOf(10.0))
        val originArc = train.head.location.arcLength
        data.wayPoints.add(WayPoint("origin", rail.id, originArc))
        data.wayPoints.add(WayPoint("handoff", rail.id, originArc + 40.0))
        data.wayPoints.add(WayPoint("next", rail.id, originArc + 80.0))
        world.simpleAtoWorldData.timetables.add(
            Timetable(
                "handoff-tt",
                services = listOf(
                    TimetableService(
                        "incoming",
                        TrainSelector.TrainId(train.id),
                        listOf(
                            TimetableStep.Check("origin"),
                            TimetableStep.At(0L, TimetableAction.GoTo("handoff"))
                        )
                    ),
                    TimetableService(
                        "outgoing",
                        steps = listOf(
                            TimetableStep.At(0L, TimetableAction.TakeFromWaypoint(listOf("handoff"))),
                            TimetableStep.At(0L, TimetableAction.GoTo("next"))
                        )
                    )
                )
            )
        )

        val incomingId = TimetableServiceRunId("handoff-tt", "incoming")
        val outgoingId = TimetableServiceRunId("handoff-tt", "outgoing")
        val incoming = world.onOwnerForTest {
            dispatcher.startService(world, incomingId.timetableId, incomingId.serviceId)
        }
        assertTrue(incoming is TimetableServiceStartResult.Started, incoming.toString())
        val ato = train.controller as SimpleATO
        var ticks = 0
        while (dispatcher.status(incomingId)?.state != TimetableServiceRunState.COMPLETED && ticks++ < 4_000) {
            tickAndPublish(ato)
            world.onOwnerForTest { dispatcher.tick(world) }
        }
        val completed = dispatcher.status(incomingId)!!
        assertEquals(TimetableServiceRunState.COMPLETED, completed.state)
        assertTrue(completed.routeGrantActive, "the terminal route remains protected for handoff")
        assertEquals("handoff", completed.currentWayPointId)

        val outgoing = world.onOwnerForTest {
            dispatcher.startService(world, outgoingId.timetableId, outgoingId.serviceId)
        }
        assertTrue(
            outgoing is TimetableServiceStartResult.Started,
            "$outgoing stall=${world.simpleAtoWorldData.atoCoordinator.lastStall?.message}"
        )
        assertSame(ato, train.controller, "the successor keeps the controller that owns the active grant")
        assertNull(dispatcher.status(incomingId), "the completed predecessor is replaced atomically")
        assertFalse(world.simpleAtoWorldData.atoCoordinator.isStalled)

        ticks = 0
        while (dispatcher.status(outgoingId)?.state != TimetableServiceRunState.COMPLETED && ticks++ < 4_000) {
            tickAndPublish(ato)
            world.onOwnerForTest { dispatcher.tick(world) }
        }
        assertEquals(TimetableServiceRunState.COMPLETED, dispatcher.status(outgoingId)?.state)
        assertEquals("next", dispatcher.status(outgoingId)?.currentWayPointId)
        assertFalse(world.simpleAtoWorldData.atoCoordinator.isStalled)
    }

    @Test
    fun `acquisition tick gate runs before service validation`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        val dispatcher = dispatcher()
        val data = BasicWorldData()
        TestSimpleATOWorldDataRegistry.register(world, data)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        val train = createTrainWithConfig("gate-train", rail, world, listOf(10.0))
        data.wayPoints.add(WayPoint("gate-origin", rail.id, train.head.location.arcLength))
        world.simpleAtoWorldData.timetables.add(
            Timetable(
                "gate-tt",
                services = listOf(
                    TimetableService(
                        "s1",
                        steps = listOf(
                            TimetableStep.At(3L, TimetableAction.TakeFromWaypoint(listOf("gate-origin"))),
                            // The missing target is intentionally invalid: the acquisition gate
                            // must defer validation instead of reporting this error before tick 3.
                            TimetableStep.At(3L, TimetableAction.GoTo("missing-target"))
                        )
                    )
                )
            )
        )

        val result = world.onOwnerForTest { dispatcher.startService(world, "gate-tt", "s1") }

        assertTrue(result is TimetableServiceStartResult.Failure)
        assertTrue(
            (result as TimetableServiceStartResult.Failure).reason.contains("waiting for train acquisition tick 3"),
            result.reason
        )
        assertNull(train.controller)
    }

    @Test
    fun `automatic service start backs off after validation failure and then resumes`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        val dispatcher = dispatcher()
        val data = BasicWorldData()
        TestSimpleATOWorldDataRegistry.register(world, data)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        val train = createTrainWithConfig("auto-retry-train", rail, world, listOf(10.0))
        val originArc = train.head.location.arcLength
        data.wayPoints.add(WayPoint("auto-origin", rail.id, originArc))
        data.wayPoints.add(WayPoint("auto-target", rail.id, originArc + 30.0))
        world.simpleAtoWorldData.timetables.add(
            Timetable(
                "auto-retry-tt",
                services = listOf(
                    TimetableService(
                        "s1",
                        steps = listOf(
                            TimetableStep.At(0L, TimetableAction.TakeFromWaypoint(listOf("auto-origin"))),
                            TimetableStep.At(0L, TimetableAction.GoTo("auto-target"))
                        )
                    )
                )
            )
        )
        assertTrue(world.onOwnerForTest { dispatcher.beginTimetable(world, "auto-retry-tt") } is
            TimetableEpochStartResult.Started)
        assertTrue(world.onOwnerForTest { dispatcher.scheduleInitialCycle("auto-retry-tt") })

        // The applied plan is valid; remove a target only for the start-time validation.
        world.onOwnerForTest { data.wayPoints.remove("auto-target") }
        world.onOwnerForTest { dispatcher.tick(world) }
        assertNull(dispatcher.status(TimetableServiceRunId("auto-retry-tt", "s1")))

        world.onOwnerForTest { data.wayPoints.add(WayPoint("auto-target", rail.id, originArc + 30.0)) }
        repeat(4) { world.onOwnerForTest { dispatcher.tick(world) } }
        assertNull(dispatcher.status(TimetableServiceRunId("auto-retry-tt", "s1")))

        world.onOwnerForTest { dispatcher.tick(world) }
        assertNotNull(dispatcher.status(TimetableServiceRunId("auto-retry-tt", "s1")))
        assertTrue(train.controller is SimpleATO)
    }

    @Test
    fun `automatic timetable disables after three failed start attempts`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        val dispatcher = dispatcher()
        val data = BasicWorldData()
        TestSimpleATOWorldDataRegistry.register(world, data)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        val train = createTrainWithConfig("auto-disable-train", rail, world, listOf(10.0))
        val originArc = train.head.location.arcLength
        data.wayPoints.add(WayPoint("disable-origin", rail.id, originArc))
        data.wayPoints.add(WayPoint("disable-target", rail.id, originArc + 30.0))
        world.simpleAtoWorldData.timetables.add(
            Timetable(
                "auto-disable-tt",
                services = listOf(
                    TimetableService(
                        "s1",
                        steps = listOf(
                            TimetableStep.At(0L, TimetableAction.TakeFromWaypoint(listOf("disable-origin"))),
                            TimetableStep.At(0L, TimetableAction.GoTo("disable-target"))
                        )
                    )
                )
            )
        )
        assertTrue(world.onOwnerForTest { dispatcher.beginTimetable(world, "auto-disable-tt") } is
            TimetableEpochStartResult.Started)
        assertTrue(world.onOwnerForTest { dispatcher.scheduleInitialCycle("auto-disable-tt") })

        world.onOwnerForTest { data.wayPoints.remove("disable-target") }
        // Attempts occur at ticks 1, 6 and 11; the third failure disables future automatic starts.
        repeat(11) { world.onOwnerForTest { dispatcher.tick(world) } }
        assertNull(dispatcher.status(TimetableServiceRunId("auto-disable-tt", "s1")))

        world.onOwnerForTest { data.wayPoints.add(WayPoint("disable-target", rail.id, originArc + 30.0)) }
        repeat(5) { world.onOwnerForTest { dispatcher.tick(world) } }
        assertNull(dispatcher.status(TimetableServiceRunId("auto-disable-tt", "s1")))
        assertEquals(null, train.controller)

        // Manual start is a separate path and is not blocked by automatic-start disablement.
        val manual = world.onOwnerForTest { dispatcher.startService(world, "auto-disable-tt", "s1") }
        assertTrue(manual is TimetableServiceStartResult.Started, manual.toString())
        assertTrue(train.controller is SimpleATO)
    }

    @Test
    fun `takeFromWaypoint failure stalls the world`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        val dispatcher = dispatcher()
        val data = BasicWorldData()
        TestSimpleATOWorldDataRegistry.register(world, data)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        data.wayPoints.add(WayPoint("empty", rail.id, 10.0))
        data.wayPoints.add(WayPoint("next", rail.id, 40.0))
        world.simpleAtoWorldData.timetables.add(
            Timetable(
                "waypoint-failure",
                services = listOf(
                    TimetableService(
                        "s1",
                        steps = listOf(
                            TimetableStep.At(0L, TimetableAction.TakeFromWaypoint(listOf("empty"))),
                            TimetableStep.At(0L, TimetableAction.GoTo("next"))
                        )
                    )
                )
            )
        )

        val result = world.onOwnerForTest { dispatcher.startService(world, "waypoint-failure", "s1") }

        assertTrue(result is TimetableServiceStartResult.Failure)
        assertTrue(world.simpleAtoWorldData.atoCoordinator.isStalled)
        assertEquals("TrainAcquisitionFailure", world.simpleAtoWorldData.atoCoordinator.lastStall?.errorType)
    }

    @Test
    fun `takeFromDepot selects an unregistered train physically stopped at a stabling point`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        val dispatcher = dispatcher()
        val data = BasicWorldData()
        TestSimpleATOWorldDataRegistry.register(world, data)
        val rail = rail(world, Vector3d(0.0, 0.0, 0.0), Vector3d(140.0, 0.0, 0.0))
        val train = createTrainWithConfig("T-physical", rail, world, listOf(10.0))
        val originArc = train.head.location.arcLength
        OperationalDestinationService(world).register(
            OperationalDestination(
                "north@1",
                rail.id,
                originArc,
                ViewDirection.AB,
                DestinationRole.DepotStablingPointRole("north", "1", usableLengthMeters = 20.0)
            )
        )
        data.wayPoints.add(WayPoint("next", rail.id, originArc + 80.0))
        world.simpleAtoWorldData.timetables.add(Timetable(
            "physical-depot",
            services = listOf(TimetableService(
                "s1",
                steps = listOf(
                    TimetableStep.At(
                        0L,
                        TimetableAction.TakeFromDepot(DepotTrainSelector(DepotId("north")))
                    ),
                    TimetableStep.At(0L, TimetableAction.GoTo("next"))
                )
            ))
        ))

        val result = world.onOwnerForTest { dispatcher.startService(world, "physical-depot", "s1") }

        assertTrue(result is TimetableServiceStartResult.Started, result.toString())
        assertEquals(train.id, (result as TimetableServiceStartResult.Started).run.trainId)
        assertEquals(null, world.simpleAtoWorldData.depots.rollingStock(train.id))
        assertTrue(!world.simpleAtoWorldData.atoCoordinator.isStalled)

        assertEquals(1, world.onOwnerForTest { dispatcher.forceDiscardTimetable(world, "physical-depot") })
        assertEquals(null, dispatcher.status(TimetableServiceRunId("physical-depot", "s1")))
        assertEquals(null, train.controller)
    }

    @Test
    fun `leading reverse enters the normal reversing state before the first Mission`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        val dispatcher = dispatcher()
        val data = BasicWorldData()
        TestSimpleATOWorldDataRegistry.register(world, data)
        val rail = rail(world, Vector3d(0.0, 0.0, 0.0), Vector3d(140.0, 0.0, 0.0))
        val train = createTrainWithConfig("T-reverse", rail, world, listOf(10.0))
        val originArc = train.head.location.arcLength
        OperationalDestinationService(world).register(OperationalDestination(
            "north@1",
            rail.id,
            originArc,
            ViewDirection.AB,
            DestinationRole.DepotStablingPointRole("north", "1", usableLengthMeters = 20.0)
        ))
        data.wayPoints.add(WayPoint("next", rail.id, originArc - 15.0))
        world.simpleAtoWorldData.timetables.add(Timetable(
            "reverse-start",
            services = listOf(TimetableService(
                "s1",
                steps = listOf(
                    TimetableStep.At(
                        0L,
                        TimetableAction.TakeFromDepot(DepotTrainSelector(DepotId("north")))
                    ),
                    TimetableStep.At(0L, TimetableAction.Reverse),
                    TimetableStep.At(0L, TimetableAction.GoTo("next"))
                )
            ))
        ))

        val startRegistration = ThreadGuardHost.registerPlatformBridge(object : ThreadGuardPlatformBridge {
            override fun isMainThread(): Boolean = true
        })
        val result = try {
            world.onOwnerForTest { dispatcher.startService(world, "reverse-start", "s1") }
        } finally {
            startRegistration.close()
        }

        assertTrue(result is TimetableServiceStartResult.Started, result.toString())
        val status = (result as TimetableServiceStartResult.Started).run
        assertEquals(TimetableServiceRunState.REVERSING, status.state)
        assertEquals(0L, status.missionRevision)
        assertFalse(train.isReversed)
        val stagedAto = train.controller as SimpleATO
        tickAndPublish(stagedAto, 1_000.0)
        val tickRegistration = ThreadGuardHost.registerPlatformBridge(object : ThreadGuardPlatformBridge {
            override fun isMainThread(): Boolean = true
        })
        try {
            world.onOwnerForTest { dispatcher.tick(world) }
        } finally {
            tickRegistration.close()
        }
        assertTrue(train.isReversed)
        assertEquals(0.0, train.speed, 1.0e-9)
        assertEquals(0.0, stagedAto.lifetimeTravelDistanceMeters, 1.0e-9)
        assertTrue(!world.simpleAtoWorldData.atoCoordinator.isStalled)
    }

    @Test
    fun `live and isolated services never turn a train to face a destination without an explicit reverse`() {
        for (mode in work.art1st.javarailways.core.api.physics.WorldExecutionMode.entries) {
            for (reverse in listOf(false, true)) {
                val world = TestWorldFixture.create(executionMode = mode).also(worldFixtures::add).world
                val dispatcher = dispatcher()
                val data = BasicWorldData()
                TestSimpleATOWorldDataRegistry.register(world, data)
                val rail = rail(world, Vector3d(0.0, 0.0, 0.0), Vector3d(140.0, 0.0, 0.0))
                val train = createTrainWithConfig("reference", rail, world, listOf(10.0))
                val initial = train.publishedFrame
                OperationalDestinationService(world).register(OperationalDestination("origin", rail.id,
                    initial.head.arcLengthInAbMeters, ViewDirection.AB, DestinationRole.Regular))
                OperationalDestinationService(world).register(OperationalDestination("behind", rail.id,
                    initial.head.arcLengthInAbMeters - 15.0, ViewDirection.BA, DestinationRole.Regular))
                val steps = listOf(TimetableStep.At(0, TimetableAction.TakeFromWaypoint(listOf("origin")))) +
                    (if (reverse) listOf(TimetableStep.At(0, TimetableAction.Reverse)) else emptyList()) +
                    TimetableStep.At(0, TimetableAction.GoTo("behind"))
                world.simpleAtoWorldData.timetables.add(Timetable("direction", services =
                    listOf(TimetableService("duty", steps = steps))))
                val registration = ThreadGuardHost.registerPlatformBridge(object : ThreadGuardPlatformBridge {
                    override fun isMainThread() = true
                })
                try {
                    val result = world.onOwnerForTest { dispatcher.startService(world, "direction", "duty") }
                    if (!reverse) {
                        assertTrue(result is TimetableServiceStartResult.Failure) { "$mode: $result" }
                        assertFalse(train.isReversed)
                        assertSame(initial, train.publishedFrame)
                        assertNull(train.controller)
                    } else {
                        assertTrue(result is TimetableServiceStartResult.Started) { "$mode: $result" }
                        val ato = train.controller as SimpleATO
                        tickAndPublish(ato)
                        world.onOwnerForTest { dispatcher.tick(world) }
                        assertTrue(train.isReversed)
                        assertEquals(initial.tail.arcLengthInAbMeters, train.publishedFrame.head.arcLengthInAbMeters, 1e-7)
                        assertEquals(initial.head.arcLengthInAbMeters, train.publishedFrame.tail.arcLengthInAbMeters, 1e-7)
                        assertEquals(initial.occupiedIntervals.toSet(), train.publishedFrame.occupiedIntervals.toSet())
                        tickAndPublish(ato)
                        world.onOwnerForTest { dispatcher.tick(world) }
                        assertFalse(world.simpleAtoWorldData.atoCoordinator.isStalled) {
                            "$mode: ${world.simpleAtoWorldData.atoCoordinator.lastStall}"
                        }
                        assertTrue(dispatcher.status(TimetableServiceRunId("direction", "duty"))!!.missionRevision > 0L)
                    }
                    assertEquals(0.0, train.publishedFrame.speedMetersPerSecond)
                } finally {
                    registration.close()
                }
            }
        }
    }

    @Test
    fun `repeated in-place reversals across mixed rail directions keep the original route anchor basis`() {
        for (mode in work.art1st.javarailways.core.api.physics.WorldExecutionMode.entries) {
            for (reverseCount in listOf(1, 2)) {
                val world = TestWorldFixture.create(executionMode = mode).also(worldFixtures::add).world
                val dispatcher = dispatcher()
                val data = BasicWorldData()
                TestSimpleATOWorldDataRegistry.register(world, data)
                val headRail = rail(world, Vector3d(100.0, 0.0, 0.0), Vector3d(240.0, 0.0, 0.0))
                val tailRail = rail(world, Vector3d(100.0, 0.0, 0.0), Vector3d(0.0, 0.0, 0.0))
                IsolatedSimulationThreadMarker.run { RailView.connect(headRail.viewBA, tailRail.viewBA) }
                val train = createTrainWithConfig("mixed", headRail, world, listOf(10.0))
                world.onOwnerForTest {
                    train.setLocation(RailLocationWithFacing(headRail.viewAB, 5.0))
                    train.commitMaintenancePlacement()
                }
                val initial = train.publishedFrame
                assertEquals(headRail.id, initial.head.railId)
                assertEquals(ViewDirection.AB, initial.head.facing)
                assertEquals(tailRail.id, initial.tail.railId)
                assertEquals(ViewDirection.BA, initial.tail.facing)
                assertEquals(2, initial.occupiedIntervals.size)
                OperationalDestinationService(world).register(OperationalDestination("origin", headRail.id,
                    initial.head.arcLengthInAbMeters, ViewDirection.AB, DestinationRole.Regular))
                val targetRail = if (reverseCount % 2 == 0) headRail else tailRail
                OperationalDestinationService(world).register(OperationalDestination("target", targetRail.id,
                    50.0, ViewDirection.AB, DestinationRole.Regular))
                val steps = listOf(TimetableStep.Check("origin")) +
                    List(reverseCount) { TimetableStep.At(0, TimetableAction.Reverse) } +
                    TimetableStep.At(0, TimetableAction.GoTo("target"))
                world.simpleAtoWorldData.timetables.add(Timetable("mixed-direction", services =
                    listOf(TimetableService("duty", TrainSelector.TrainId(train.id), steps))))
                val registration = ThreadGuardHost.registerPlatformBridge(object : ThreadGuardPlatformBridge {
                    override fun isMainThread() = true
                })
                try {
                    val result = world.onOwnerForTest { dispatcher.startService(world, "mixed-direction", "duty") }
                    assertTrue(result is TimetableServiceStartResult.Started) { "$mode/$reverseCount: $result" }
                    val ato = train.controller as SimpleATO
                    var observed = 0
                    var previousReversed = false
                    repeat(reverseCount * 2 + 1) {
                        tickAndPublish(ato)
                        world.onOwnerForTest { dispatcher.tick(world) }
                        val frame = train.publishedFrame
                        if (frame.reversed != previousReversed) {
                            observed++
                            previousReversed = frame.reversed
                            assertEquals(initial.occupiedIntervals.toSet(), frame.occupiedIntervals.toSet())
                            assertEquals(if (observed % 2 == 0) initial.head.railId else initial.tail.railId, frame.head.railId)
                            assertEquals(if (observed % 2 == 0) initial.head.arcLengthInAbMeters else initial.tail.arcLengthInAbMeters,
                                frame.head.arcLengthInAbMeters, 1e-7)
                            // Both rails' AB arrows point in opposite physical directions.
                            assertEquals(ViewDirection.AB, frame.head.facing)
                            assertEquals(0.0, frame.speedMetersPerSecond)
                        }
                    }
                    assertEquals(reverseCount, observed)
                    assertFalse(world.simpleAtoWorldData.atoCoordinator.isStalled) {
                        "$mode/$reverseCount: ${world.simpleAtoWorldData.atoCoordinator.lastStall}"
                    }
                    assertTrue(dispatcher.status(TimetableServiceRunId("mixed-direction", "duty"))!!.missionRevision > 0L)
                } finally {
                    registration.close()
                }
            }
        }
    }

    @Test
    fun `storeAtDepot releases departure occupancy and completes availability handoff`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        val dispatcher = dispatcher()
        val data = BasicWorldData()
        TestSimpleATOWorldDataRegistry.register(world, data)
        val (rail, inboundRail, returnRail) = depotBranchLoop(world)
        val train = createTrainWithConfig("T1", rail, world, listOf(10.0))
        val originArc = train.head.location.arcLength
        val destinations = OperationalDestinationService(world)
        destinations.register(
            OperationalDestination(
                "north@out",
                rail.id,
                originArc,
                ViewDirection.AB,
                DestinationRole.DepotStablingPointRole(
                    "north",
                    "out",
                    usableLengthMeters = 20.0
                )
            )
        )
        destinations.register(
            OperationalDestination(
                "north@in",
                inboundRail.id,
                originArc,
                ViewDirection.AB,
                DestinationRole.DepotStablingPointRole(
                    "north",
                    "in",
                    usableLengthMeters = 20.0
                )
            )
        )
        data.wayPoints.add(WayPoint("B", returnRail.id, 50.0))
        val depotId = DepotId("north")
        world.simpleAtoWorldData.depots.registerRollingStock(
            RollingStockRecord(
                train.id,
                depotId,
                "test/s100",
                setOf("metro"),
                1,
                10.0,
                20.0,
                setOf("doors")
            ),
            StablingPointRef(depotId, "out")
        )
        world.simpleAtoWorldData.timetables.add(
            Timetable(
                "tt",
                services = listOf(
                    TimetableService(
                        "s1",
                        steps = listOf(
                            TimetableStep.At(
                                0L,
                                TimetableAction.TakeFromDepot(DepotTrainSelector(depotId))
                            ),
                            TimetableStep.At(0L, TimetableAction.GoTo("B")),
                            TimetableStep.At(0L, TimetableAction.TaskStart("store")),
                            TimetableStep.At(
                                0L,
                                TimetableAction.StoreAtDepot(depotId, setOf("in"))
                            ),
                            TimetableStep.At(0L, TimetableAction.TaskEnd("store"))
                        )
                    )
                )
            )
        )

        val start = world.onOwnerForTest { dispatcher.startService(world, "tt", "s1") }
        assertTrue(start is TimetableServiceStartResult.Started, start.toString())
        val ato = train.controller as SimpleATO
        repeat(8_000) {
            tickAndPublish(ato)
            world.onOwnerForTest { dispatcher.tick(world) }
        }

        val status = dispatcher.status(TimetableServiceRunId("tt", "s1"))!!
        assertEquals(TimetableServiceRunState.COMPLETED, status.state)
        val measured = status.taskTimings.single()
        assertEquals("store", measured.taskId)
        assertTrue(measured.endTick!! > measured.startTick, "storage ends after physical arrival and availability handoff")
        assertTrue(status.routeGrantActive)
        assertEquals(inboundRail.id, train.head.location.rail.id)
        assertEquals(originArc, train.head.location.arcLength, 0.6)
        val rolling = world.simpleAtoWorldData.depots.rollingStock(train.id)!!
        assertEquals(RollingStockState.AVAILABLE, rolling.state)
        assertEquals(StablingPointRef(depotId, "in"), rolling.stablingPointRef)
        assertEquals(null, world.simpleAtoWorldData.depots.stablingPoint(StablingPointRef(depotId, "out"))?.occupantTrainId)
        assertEquals(train.id, world.simpleAtoWorldData.depots.stablingPoint(StablingPointRef(depotId, "in"))?.occupantTrainId)
        assertTrue(!world.simpleAtoWorldData.atoCoordinator.isStalled)

        data.wayPoints.add(WayPoint("C", returnRail.id, 100.0))
        world.simpleAtoWorldData.timetables.add(
            Timetable(
                "tt-depart-again",
                services = listOf(
                    TimetableService(
                        "s1",
                        steps = listOf(
                            TimetableStep.At(
                                0L,
                                TimetableAction.TakeFromDepot(
                                    DepotTrainSelector(depotId, requiredTags = setOf("metro"))
                                )
                            ),
                            TimetableStep.At(0L, TimetableAction.GoVia(listOf("B", "C")))
                        )
                    )
                )
            )
        )
        val departure = world.onOwnerForTest { dispatcher.startService(world, "tt-depart-again", "s1") }
        assertTrue(
            departure is TimetableServiceStartResult.Started,
            "$departure stall=${world.simpleAtoWorldData.atoCoordinator.lastStall?.message}"
        )
        val departureAto = train.controller as SimpleATO
        repeat(2_000) {
            tickAndPublish(departureAto)
            world.onOwnerForTest { dispatcher.tick(world) }
        }
        val departureStatus = dispatcher.status(
            TimetableServiceRunId("tt-depart-again", "s1")
        )!!
        assertEquals(TimetableServiceRunState.COMPLETED, departureStatus.state)
        assertTrue(departureStatus.routeGrantActive)
        assertEquals(RollingStockState.IN_SERVICE, world.simpleAtoWorldData.depots.rollingStock(train.id)?.state)
        assertEquals(null, world.simpleAtoWorldData.depots.stablingPoint(StablingPointRef(depotId, "in"))?.occupantTrainId)
        assertTrue(!world.simpleAtoWorldData.atoCoordinator.isStalled)
    }

    @Test
    fun `storeAtDepot without a compatible empty point stalls without partial occupancy`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        val dispatcher = dispatcher()
        val coordinator = AtoWorldCoordinator("dispatcher-store-capacity", reportSink = {})
        val data = BasicWorldData(safetyInterlock = coordinator.safetyInterlock)
        TestSimpleATOWorldDataRegistry.register(world, data, coordinator)
        val (outboundRail, inboundRail, returnRail) = depotBranchLoop(world)
        val train = createTrainWithConfig("T1", outboundRail, world, listOf(10.0))
        val originArc = train.head.location.arcLength
        val destinations = OperationalDestinationService(world)
        destinations.register(
            OperationalDestination(
                "north@out",
                outboundRail.id,
                originArc,
                ViewDirection.AB,
                DestinationRole.DepotStablingPointRole(
                    "north",
                    "out",
                    usableLengthMeters = 20.0
                )
            )
        )
        destinations.register(
            OperationalDestination(
                "north@in",
                inboundRail.id,
                originArc,
                ViewDirection.AB,
                DestinationRole.DepotStablingPointRole(
                    "north",
                    "in",
                    usableLengthMeters = 20.0
                )
            )
        )
        data.wayPoints.add(WayPoint("B", returnRail.id, 50.0))
        val depotId = DepotId("north")
        world.simpleAtoWorldData.depots.registerRollingStock(
            RollingStockRecord(
                train.id,
                depotId,
                "test/s100",
                setOf("metro"),
                1,
                10.0,
                20.0,
                emptySet()
            ),
            StablingPointRef(depotId, "out")
        )
        world.simpleAtoWorldData.depots.registerRollingStock(
            RollingStockRecord(
                "blocker",
                depotId,
                "test/blocker",
                setOf("blocker"),
                1,
                10.0,
                20.0,
                emptySet()
            ),
            StablingPointRef(depotId, "in")
        )
        world.simpleAtoWorldData.timetables.add(
            Timetable(
                "tt-store-capacity",
                services = listOf(
                    TimetableService(
                        "s1",
                        steps = listOf(
                            TimetableStep.At(
                                0L,
                                TimetableAction.TakeFromDepot(
                                    DepotTrainSelector(depotId, requiredTags = setOf("metro"))
                                )
                            ),
                            TimetableStep.At(0L, TimetableAction.GoTo("B")),
                            TimetableStep.At(
                                0L,
                                TimetableAction.StoreAtDepot(depotId, setOf("in"))
                            )
                        )
                    )
                )
            )
        )
        val start = world.onOwnerForTest { dispatcher.startService(world, "tt-store-capacity", "s1") }
        assertInstanceOf(TimetableServiceStartResult.Started::class.java, start, start.toString())
        val ato = train.controller as SimpleATO

        for (ignored in 0 until 8_000) {
            tickAndPublish(ato)
            world.onOwnerForTest { dispatcher.tick(world) }
            if (coordinator.isStalled) break
        }

        assertTrue(coordinator.isStalled)
        assertEquals("DepotAllocationFailure", coordinator.lastStall?.errorType)
        assertEquals(
            "blocker",
            world.simpleAtoWorldData.depots.stablingPoint(StablingPointRef(depotId, "in"))?.occupantTrainId
        )
        assertEquals(
            null,
            world.simpleAtoWorldData.depots.stablingPoint(StablingPointRef(depotId, "in"))?.reservedByAssignmentId
        )
        assertEquals(RollingStockState.IN_SERVICE, world.simpleAtoWorldData.depots.rollingStock(train.id)?.state)
    }

    @Test
    fun `period boundary automatically creates the next dynamic service cycle`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        val dispatcher = dispatcher()
        val data = BasicWorldData()
        TestSimpleATOWorldDataRegistry.register(world, data)
        val (outboundRail, returnRail) = depotLoop(world)
        val train = createTrainWithConfig("T1", outboundRail, world, listOf(10.0))
        val originArc = train.head.location.arcLength
        val destinations = OperationalDestinationService(world)
        destinations.register(
            OperationalDestination(
                "north@one",
                outboundRail.id,
                originArc,
                ViewDirection.AB,
                DestinationRole.DepotStablingPointRole(
                    "north",
                    "one",
                    usableLengthMeters = 20.0
                )
            )
        )
        data.wayPoints.add(WayPoint("B", returnRail.id, 50.0))
        val stationId = StationId("central")
        val berthRef = StationBerthRef(stationId, "one")
        world.simpleAtoWorldData.stations.register(
            Station(stationId, "Central", listOf(berthRef), defaultDwellTicks = 3L),
            listOf(
                StationBerth(
                    berthRef,
                    returnRail.viewAB.getRailLocationAt(25.0),
                    approachDirection = ViewDirection.AB,
                    usableLengthMeters = 20.0,
                    defaultDwellTicks = 3L,
                    doorSide = DoorSide.NONE
                )
            )
        )
        val depotId = DepotId("north")
        world.simpleAtoWorldData.depots.registerRollingStock(
            RollingStockRecord(
                train.id,
                depotId,
                "test/s100",
                setOf("metro"),
                1,
                10.0,
                20.0,
                emptySet()
            ),
            StablingPointRef(depotId, "one")
        )
        world.simpleAtoWorldData.timetables.add(
            Timetable(
                "tt-cycle",
                services = listOf(
                    TimetableService(
                        "cyclic",
                        steps = listOf(
                            TimetableStep.At(
                                0L,
                                TimetableAction.TakeFromDepot(
                                    DepotTrainSelector(depotId, requiredTags = setOf("metro"))
                                )
                            ),
                            TimetableStep.At(
                                0L,
                                TimetableAction.CallStation(
                                    stationId = "central",
                                    berthId = "one",
                                    minimumDwellTicks = 3L
                                )
                            ),
                            TimetableStep.At(0L, TimetableAction.GoTo("B")),
                            TimetableStep.At(
                                0L,
                                TimetableAction.StoreAtDepot(depotId)
                            )
                        )
                    )
                )
            )
        )

        assertTrue(world.onOwnerForTest { dispatcher.beginTimetable(world, "tt-cycle") } is TimetableEpochStartResult.Started)
        assertTrue(
            world.onOwnerForTest { dispatcher.startService(world, "tt-cycle", "cyclic") } is
                TimetableServiceStartResult.Started
        )
        val ato = train.controller as SimpleATO
        repeat(23_999) {
            tickAndPublish(ato)
            world.onOwnerForTest { dispatcher.tick(world) }
        }
        assertEquals(0L, dispatcher.status(TimetableServiceRunId("tt-cycle", "cyclic"))?.cycleIndex)
        assertEquals(RollingStockState.AVAILABLE, world.simpleAtoWorldData.depots.rollingStock(train.id)?.state)

        tickAndPublish(ato)
        world.onOwnerForTest { dispatcher.tick(world) }

        val next = dispatcher.status(TimetableServiceRunId("tt-cycle", "cyclic"))!!
        assertEquals(1L, next.cycleIndex)
        assertEquals(0L, next.timetableElapsedTicks)
        assertEquals(train.id, next.trainId)
        assertEquals(RollingStockState.IN_SERVICE, world.simpleAtoWorldData.depots.rollingStock(train.id)?.state)
        assertEquals("tt-cycle/cyclic@cycle-1", world.simpleAtoWorldData.depots.rollingStock(train.id)?.assignmentId)
        assertTrue(!world.simpleAtoWorldData.atoCoordinator.isStalled)

        assertTrue(world.onOwnerForTest { dispatcher.stopFutureCycles("tt-cycle") })
        repeat(24_000) {
            tickAndPublish(ato)
            world.onOwnerForTest { dispatcher.tick(world) }
        }
        assertEquals(1L, dispatcher.status(TimetableServiceRunId("tt-cycle", "cyclic"))?.cycleIndex)
        assertEquals(RollingStockState.AVAILABLE, world.simpleAtoWorldData.depots.rollingStock(train.id)?.state)
        assertTrue(!world.simpleAtoWorldData.atoCoordinator.isStalled, world.simpleAtoWorldData.atoCoordinator.lastStall.toString())
    }

    @Test
    fun `next cycle never preempts a train still active in the previous cycle`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        val dispatcher = dispatcher()
        val coordinator = AtoWorldCoordinator("dispatcher-cycle-overlap", reportSink = {})
        val data = BasicWorldData(safetyInterlock = coordinator.safetyInterlock)
        TestSimpleATOWorldDataRegistry.register(world, data, coordinator)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        val train = createTrainWithConfig("T1", rail, world, listOf(10.0))
        val originArc = train.head.location.arcLength
        OperationalDestinationService(world).register(
            OperationalDestination(
                "north@one",
                rail.id,
                originArc,
                ViewDirection.AB,
                DestinationRole.DepotStablingPointRole("north", "one")
            )
        )
        data.wayPoints.add(WayPoint("B", rail.id, originArc + 50.0))
        val depotId = DepotId("north")
        world.simpleAtoWorldData.depots.registerRollingStock(
            RollingStockRecord(
                train.id,
                depotId,
                "test/s100",
                setOf("metro"),
                1,
                10.0,
                20.0,
                emptySet()
            ),
            StablingPointRef(depotId, "one")
        )
        world.simpleAtoWorldData.timetables.add(
            Timetable(
                "tt-overlap",
                services = listOf(
                    TimetableService(
                        "s1",
                        steps = listOf(
                            TimetableStep.At(
                                0L,
                                TimetableAction.TakeFromDepot(DepotTrainSelector(depotId))
                            ),
                            TimetableStep.At(0L, TimetableAction.GoTo("B"))
                        )
                    )
                )
            )
        )
        world.onOwnerForTest { dispatcher.beginTimetable(world, "tt-overlap") }
        assertTrue(
            world.onOwnerForTest { dispatcher.startService(world, "tt-overlap", "s1") } is
                TimetableServiceStartResult.Started
        )

        repeat(24_000) { world.onOwnerForTest { dispatcher.tick(world) } }

        assertTrue(coordinator.isStalled)
        assertEquals("DepotAllocationFailure", coordinator.lastStall?.errorType)
        assertEquals(0L, dispatcher.status(TimetableServiceRunId("tt-overlap", "s1"))?.cycleIndex)
        assertEquals(RollingStockState.IN_SERVICE, world.simpleAtoWorldData.depots.rollingStock(train.id)?.state)
    }

    @Test
    fun `takeFromDepot miss stalls only its world`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        val dispatcher = dispatcher()
        val coordinator = AtoWorldCoordinator("dispatcher-depot-miss", reportSink = {})
        val data = BasicWorldData(safetyInterlock = coordinator.safetyInterlock)
        TestSimpleATOWorldDataRegistry.register(world, data, coordinator)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        val train = createTrainWithConfig("T1", rail, world, listOf(10.0))
        val originArc = train.head.location.arcLength
        OperationalDestinationService(world).register(
            OperationalDestination(
                "north@one",
                rail.id,
                originArc,
                ViewDirection.AB,
                DestinationRole.DepotStablingPointRole("north", "one")
            )
        )
        data.wayPoints.add(WayPoint("B", rail.id, originArc + 30.0))
        world.simpleAtoWorldData.timetables.add(
            Timetable(
                "tt",
                services = listOf(
                    TimetableService(
                        "s1",
                        steps = listOf(
                            TimetableStep.At(
                                0L,
                                TimetableAction.TakeFromDepot(
                                    DepotTrainSelector(DepotId("north"), requiredTags = setOf("metro"))
                                )
                            ),
                            TimetableStep.At(0L, TimetableAction.GoTo("B"))
                        )
                    )
                )
            )
        )

        val result = world.onOwnerForTest { dispatcher.startService(world, "tt", "s1") }

        assertTrue(result is TimetableServiceStartResult.Failure)
        assertTrue(coordinator.isStalled)
        assertEquals("DepotAllocationFailure", coordinator.lastStall?.errorType)
    }

    @Test
    fun `shared timetable epoch prevents an early first departure and records lateness`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        val dispatcher = dispatcher()
        val data = BasicWorldData()
        TestSimpleATOWorldDataRegistry.register(world, data)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        val train = createTrainWithConfig("T1", rail, world, listOf(10.0))
        val originArc = train.head.location.arcLength
        data.wayPoints.add(WayPoint("A", rail.id, originArc))
        data.wayPoints.add(WayPoint("B", rail.id, originArc + 30.0))
        world.simpleAtoWorldData.timetables.add(
            Timetable(
                id = "tt",
                periodDays = 2L,
                services = listOf(service("s1", train.id, "A", "B", tick = 5L))
            )
        )
        val epoch = world.onOwnerForTest { dispatcher.beginTimetable(world, "tt") } as TimetableEpochStartResult.Started
        assertEquals(48_000L, epoch.epoch.periodTicks)
        val id = TimetableServiceRunId("tt", "s1")
        val started = world.onOwnerForTest { dispatcher.startService(world, "tt", "s1") } as
            TimetableServiceStartResult.Started
        val ato = train.controller as SimpleATO
        assertEquals(TimetableServiceRunState.WAITING_TIMETABLE_TIME, started.run.state)

        repeat(4) {
            tickAndPublish(ato)
            world.onOwnerForTest { dispatcher.tick(world) }
            assertEquals(0.0, train.speed, 1.0e-9)
            assertEquals(TimetableServiceRunState.WAITING_TIMETABLE_TIME, dispatcher.status(id)?.state)
        }
        tickAndPublish(ato)
        world.onOwnerForTest { dispatcher.tick(world) }
        assertEquals(5L, dispatcher.status(id)?.timetableElapsedTicks)
        assertEquals(0L, dispatcher.status(id)?.lastLatenessTicks)
        tickAndPublish(ato)
        assertTrue(train.speed > 0.0)

        val lateWorld = TestWorldFixture.create().also(worldFixtures::add).world
        val lateDispatcher = dispatcher()
        val lateData = BasicWorldData()
        TestSimpleATOWorldDataRegistry.register(lateWorld, lateData)
        val lateRail = createStraightRailFromSegments(
            lateWorld,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        val lateTrain = createTrainWithConfig("T2", lateRail, lateWorld, listOf(10.0))
        val lateOrigin = lateTrain.head.location.arcLength
        lateData.wayPoints.add(WayPoint("A", lateRail.id, lateOrigin))
        lateData.wayPoints.add(WayPoint("B", lateRail.id, lateOrigin + 30.0))
        lateWorld.simpleAtoWorldData.timetables.add(timetable("late", "s1", lateTrain.id, "A", "B"))
        lateWorld.onOwnerForTest { lateDispatcher.beginTimetable(lateWorld, "late") }
        repeat(10) { lateWorld.onOwnerForTest { lateDispatcher.tick(lateWorld) } }

        val late = lateWorld.onOwnerForTest { lateDispatcher.startService(lateWorld, "late", "s1") } as
            TimetableServiceStartResult.Started

        assertEquals(TimetableServiceRunState.WAITING_MISSION, late.run.state)
        assertEquals(10L, late.run.lastLatenessTicks)
    }

    @Test
    fun `illegal controller replacement stalls the owning world`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        val dispatcher = dispatcher()
        val coordinator = AtoWorldCoordinator("dispatcher-controller-identity", reportSink = {})
        val data = BasicWorldData(safetyInterlock = coordinator.safetyInterlock)
        TestSimpleATOWorldDataRegistry.register(world, data, coordinator)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        val train = createTrainWithConfig("T1", rail, world, listOf(10.0))
        val originArc = train.head.location.arcLength
        data.wayPoints.add(WayPoint("A", rail.id, originArc))
        data.wayPoints.add(WayPoint("B", rail.id, originArc + 40.0))
        world.simpleAtoWorldData.timetables.add(timetable("tt", "s1", train.id, "A", "B"))
        world.onOwnerForTest { dispatcher.startService(world, "tt", "s1") }

        train.setControllerForTest(ManualController(train))
        world.onOwnerForTest { dispatcher.tick(world) }

        assertTrue(coordinator.isStalled)
        assertEquals("ControllerIdentityViolation", coordinator.lastStall?.errorType)
        assertEquals(
            TimetableServiceRunState.FAILED,
            dispatcher.status(TimetableServiceRunId("tt", "s1"))?.state
        )
    }

    @Test
    fun `manual routed driver keeps dispatcher route assistance without automatic speed`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        val dispatcher = dispatcher()
        val data = BasicWorldData()
        TestSimpleATOWorldDataRegistry.register(world, data)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(120.0, segmentCount = 1)
        )
        val train = createTrainWithConfig("T1", rail, world, listOf(10.0))
        val originArc = train.head.location.arcLength
        data.wayPoints.add(WayPoint("A", rail.id, originArc))
        data.wayPoints.add(WayPoint("P", rail.id, originArc + 30.0))
        data.wayPoints.add(WayPoint("B", rail.id, originArc + 60.0))
        world.simpleAtoWorldData.timetables.add(
            Timetable(
                "tt",
                services = listOf(
                    TimetableService(
                        "s1",
                        TrainSelector.TrainId(train.id),
                        listOf(
                            TimetableStep.Check("A"),
                            TimetableStep.At(0L, TimetableAction.GoVia(listOf("P", "B")))
                        )
                    )
                )
            )
        )
        assertTrue(world.onOwnerForTest { dispatcher.startService(world, "tt", "s1") } is TimetableServiceStartResult.Started)
        val transfer = world.onOwnerForTest { dispatcher.enterManualRoutedControl(world, train.id, "driver") }
        assertTrue(transfer is TimetableControlTransferResult.ManualRouted)
        val manual = ManualController(train, maxSpeed = 8.0)
        train.setControllerForTest(manual)
        assertTrue(world.onOwnerForTest { dispatcher.bindManualRoutedController(train.id, "driver", manual) })
        manual.setThrottle(0.75)
        val targetArc = originArc + 60.0

        world.onOwnerForTest { dispatcher.tick(world) }
        assertEquals(
            TimetableControlMode.MANUAL_ROUTED,
            dispatcher.status(TimetableServiceRunId("tt", "s1"))?.controlMode
        )
        for (ignored in 0 until 2_000) {
            if (train.head.location.arcLength >= targetArc - 12.0) manual.setThrottle(-1.0)
            AsyncTickTestSupport.run { manual.tick(50.0) }
            manual.commit()
            world.onOwnerForTest { dispatcher.tick(world) }
            if (dispatcher.status(TimetableServiceRunId("tt", "s1"))?.state ==
                TimetableServiceRunState.COMPLETED
            ) break
        }

        if (dispatcher.status(TimetableServiceRunId("tt", "s1"))?.state !=
            TimetableServiceRunState.COMPLETED
        ) {
            world.onOwnerForTest {
                train.setLocation(rail.viewAB.getRailLocationAt(targetArc))
                train.commit()
            }
            world.onOwnerForTest { dispatcher.tick(world) }
        }
        val completed = dispatcher.status(TimetableServiceRunId("tt", "s1"))!!
        assertEquals(TimetableServiceRunState.COMPLETED, completed.state)
        assertEquals(TimetableControlMode.MANUAL_ROUTED, completed.controlMode)
        assertTrue(completed.routeGrantActive)
        assertTrue(!world.simpleAtoWorldData.atoCoordinator.isStalled)
    }

    @Test
    fun `manual routed straight route receives Basic route assistance`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        val dispatcher = dispatcher()
        val coordinator = AtoWorldCoordinator("dispatcher-manual-violation", reportSink = {})
        val data = BasicWorldData(safetyInterlock = coordinator.safetyInterlock)
        TestSimpleATOWorldDataRegistry.register(world, data, coordinator)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        val train = createTrainWithConfig("T1", rail, world, listOf(10.0))
        val originArc = train.head.location.arcLength
        data.wayPoints.add(WayPoint("A", rail.id, originArc))
        data.wayPoints.add(WayPoint("B", rail.id, originArc + 50.0))
        world.simpleAtoWorldData.timetables.add(timetable("tt", "s1", train.id, "A", "B"))
        world.onOwnerForTest { dispatcher.startService(world, "tt", "s1") }
        world.onOwnerForTest { dispatcher.enterManualRoutedControl(world, train.id, "driver") }
        val manual = ManualController(train, maxSpeed = 10.0)
        train.setControllerForTest(manual)
        assertTrue(world.onOwnerForTest { dispatcher.bindManualRoutedController(train.id, "driver", manual) })
        manual.setThrottle(1.0)

        repeat(2) {
            AsyncTickTestSupport.run { manual.tick(1_000.0) }
            manual.commit()
        }
        world.onOwnerForTest { dispatcher.tick(world) }

        assertFalse(coordinator.isStalled)
        assertTrue(dispatcher.status(TimetableServiceRunId("tt", "s1"))?.routeGrantActive == true)
    }

    @Test
    fun `full manual requests no new route and release still requires replan`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        val dispatcher = dispatcher()
        val data = BasicWorldData()
        TestSimpleATOWorldDataRegistry.register(world, data)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        val train = createTrainWithConfig("T1", rail, world, listOf(10.0))
        val originArc = train.head.location.arcLength
        data.wayPoints.add(WayPoint("A", rail.id, originArc))
        data.wayPoints.add(WayPoint("P", rail.id, originArc + 25.0))
        data.wayPoints.add(WayPoint("B", rail.id, originArc + 50.0))
        world.simpleAtoWorldData.timetables.add(
            Timetable(
                "tt",
                services = listOf(
                    TimetableService(
                        "s1",
                        TrainSelector.TrainId(train.id),
                        listOf(
                            TimetableStep.Check("A"),
                            TimetableStep.At(0L, TimetableAction.GoVia(listOf("P", "B")))
                        )
                    )
                )
            )
        )
        world.onOwnerForTest { dispatcher.startService(world, "tt", "s1") }
        world.onOwnerForTest { dispatcher.enterManualRoutedControl(world, train.id, "driver") }
        val manual = ManualController(train, maxSpeed = 5.0)
        train.setControllerForTest(manual)
        assertTrue(world.onOwnerForTest { dispatcher.bindManualRoutedController(train.id, "driver", manual) })
        world.onOwnerForTest { dispatcher.tick(world) }
        assertTrue(dispatcher.status(TimetableServiceRunId("tt", "s1"))?.routeGrantActive == true)
        val suspended = world.onOwnerForTest { dispatcher.switchToFullManual(train.id, "driver") }
        assertTrue(suspended is TimetableControlTransferResult.Suspended)
        manual.setThrottle(1.0)

        repeat(200) {
            AsyncTickTestSupport.run { manual.tick(50.0) }
            manual.commit()
            world.onOwnerForTest { dispatcher.tick(world) }
        }

        val status = dispatcher.status(TimetableServiceRunId("tt", "s1"))!!
        assertEquals(TimetableControlMode.FULL_MANUAL, status.controlMode)
        assertEquals(train.publishedFrame.head.railId.toString(), status.trainHeadRailId)
        assertEquals(train.publishedFrame.head.arcLengthInAbMeters, status.trainHeadArcLengthInAbMeters)
        assertEquals(TimetableServiceRunState.SUSPENDED, status.state)
        assertFalse(status.routeGrantActive,
            "the tail has cleared the initial short grant; full manual must not acquire another")
        assertTrue(world.onOwnerForTest { dispatcher.releaseControlTransfer(train.id, "driver") })
        assertEquals(
            TimetableServiceRunState.REPLAN_REQUIRED,
            dispatcher.status(TimetableServiceRunId("tt", "s1"))?.state
        )
    }

    @Test
    fun `released ad hoc navigation can be discarded before replacement`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        val dispatcher = dispatcher()
        val data = BasicWorldData()
        TestSimpleATOWorldDataRegistry.register(world, data)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        val train = createTrainWithConfig("T1", rail, world, listOf(10.0))
        val originArc = train.head.location.arcLength
        data.wayPoints.add(WayPoint("A", rail.id, originArc))
        data.wayPoints.add(WayPoint("B", rail.id, originArc + 50.0))
        world.simpleAtoWorldData.timetables.add(
            Timetable(
                "manual-navigate-99",
                services = listOf(
                    TimetableService(
                        "navigate",
                        TrainSelector.TrainId(train.id),
                        listOf(
                            TimetableStep.Check("A"),
                            TimetableStep.At(0L, TimetableAction.GoTo("B"))
                        )
                    )
                )
            )
        )
        val id = TimetableServiceRunId("manual-navigate-99", "navigate")
        assertTrue(
            world.onOwnerForTest { dispatcher.startService(world, id.timetableId, id.serviceId) } is
                TimetableServiceStartResult.Started
        )
        assertTrue(
            world.onOwnerForTest { dispatcher.suspendForControlTransfer(world, train.id, "driver") } is
                TimetableControlTransferResult.Suspended
        )
        assertTrue(world.onOwnerForTest { dispatcher.releaseControlTransfer(train.id, "driver") })
        assertEquals(TimetableServiceRunState.REPLAN_REQUIRED, dispatcher.status(id)?.state)

        assertEquals(1, world.onOwnerForTest { dispatcher.discardReleasedManualNavigation(world, train.id) })
        assertNull(dispatcher.status(id))
        assertNull(train.controller)
    }

    @Test
    fun `manual control transfer suspends ATO and requires explicit released replan`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        val dispatcher = dispatcher()
        val data = BasicWorldData()
        TestSimpleATOWorldDataRegistry.register(world, data)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        val train = createTrainWithConfig("T1", rail, world, listOf(10.0))
        val originArc = train.head.location.arcLength
        data.wayPoints.add(WayPoint("A", rail.id, originArc))
        data.wayPoints.add(WayPoint("B", rail.id, originArc + 30.0))
        world.simpleAtoWorldData.timetables.add(timetable("tt", "s1", train.id, "A", "B"))
        val id = TimetableServiceRunId("tt", "s1")
        val started = world.onOwnerForTest { dispatcher.startService(world, "tt", "s1") } as
            TimetableServiceStartResult.Started

        val suspended = world.onOwnerForTest { dispatcher.suspendForControlTransfer(world, train.id, "driver-1") }

        assertTrue(suspended is TimetableControlTransferResult.Suspended)
        assertEquals(TimetableServiceRunState.SUSPENDED, dispatcher.status(id)?.state)
        assertEquals("driver-1", dispatcher.status(id)?.controlTransferOwner)
        assertNull(train.controller)
        assertTrue(
            world.onOwnerForTest { dispatcher.replanService(world, id, "A") } is TimetableReplanResult.Failure,
            "active transfer must not be replanned"
        )
        assertFalse(world.onOwnerForTest { dispatcher.releaseControlTransfer(train.id, "other-driver") })
        assertTrue(world.onOwnerForTest { dispatcher.releaseControlTransfer(train.id, "driver-1") })
        assertEquals(TimetableServiceRunState.REPLAN_REQUIRED, dispatcher.status(id)?.state)
        val reacquired = world.onOwnerForTest { dispatcher.enterManualRoutedControl(world, train.id, "driver-2") }
        assertTrue(reacquired is TimetableControlTransferResult.Suspended)
        assertEquals(TimetableControlMode.FULL_MANUAL, dispatcher.status(id)?.controlMode)
        assertEquals("driver-2", dispatcher.status(id)?.controlTransferOwner)
        assertTrue(world.onOwnerForTest { dispatcher.releaseControlTransfer(train.id, "driver-2") })

        val replanned = world.onOwnerForTest { dispatcher.replanService(world, id, "A") } as TimetableReplanResult.Resumed

        assertTrue(replanned.run.missionRevision > started.run.missionRevision)
        assertEquals(TimetableServiceRunState.WAITING_MISSION, replanned.run.state)
        assertTrue(train.controller is SimpleATO)
        val replacementAto = train.controller as SimpleATO
        for (tick in 0 until 2_000) {
            tickAndPublish(replacementAto)
            world.onOwnerForTest { dispatcher.tick(world) }
            if (dispatcher.status(id)?.state == TimetableServiceRunState.COMPLETED) break
        }
        assertEquals(TimetableServiceRunState.COMPLETED, dispatcher.status(id)?.state)
        assertEquals(originArc + 30.0, train.head.location.arcLength, 0.4)
    }

    @Test
    fun `suspension preserves active Basic route grant`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        val dispatcher = dispatcher()
        val data = BasicWorldData()
        TestSimpleATOWorldDataRegistry.register(world, data)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        val train = createTrainWithConfig("T1", rail, world, listOf(10.0))
        val originArc = train.head.location.arcLength
        data.wayPoints.add(WayPoint("A", rail.id, originArc))
        data.wayPoints.add(WayPoint("B", rail.id, originArc + 30.0))
        world.simpleAtoWorldData.timetables.add(timetable("tt", "s1", train.id, "A", "B"))
        val id = TimetableServiceRunId("tt", "s1")
        world.onOwnerForTest { dispatcher.startService(world, "tt", "s1") } as TimetableServiceStartResult.Started
        tickAndPublish(train.controller as SimpleATO)
        world.onOwnerForTest { dispatcher.tick(world) }
        assertTrue(dispatcher.status(id)?.routeGrantActive == true)

        world.onOwnerForTest { dispatcher.suspendForControlTransfer(world, train.id, "driver-1") }
        world.onOwnerForTest { dispatcher.tick(world) }

        assertEquals(TimetableServiceRunState.SUSPENDED, dispatcher.status(id)?.state)
        assertTrue(dispatcher.status(id)?.routeGrantActive == true)
        assertTrue(world.onOwnerForTest { dispatcher.releaseControlTransfer(train.id, "driver-1") })

        world.onOwnerForTest {
            train.setLocation(train.head.location.forward(40.0 + profile.safetyDistanceMeters)!!)
            train.commit()
        }
        world.onOwnerForTest { dispatcher.tick(world) }

        assertFalse(dispatcher.status(id)?.routeGrantActive == true)
        assertEquals(TimetableServiceRunState.REPLAN_REQUIRED, dispatcher.status(id)?.state)
    }

    @Test
    fun `straight route creates no synthetic resource claims across multiple legs`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        // The sampled kinematic policy needs one control tick of response, not zero delay.
        val dispatcher = dispatcher(safetyProfile = profile.copy(reactionTimeSeconds = 0.05))
        val data = BasicWorldData()
        TestSimpleATOWorldDataRegistry.register(world, data)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(140.0, segmentCount = 1)
        )
        val train = createTrainWithConfig("T1", rail, world, listOf(10.0))
        val originArc = train.head.location.arcLength
        val viaArc = originArc + 30.0
        val targetArc = originArc + 70.0
        data.wayPoints.add(WayPoint("A", rail.id, originArc))
        data.wayPoints.add(WayPoint("B", rail.id, viaArc))
        data.wayPoints.add(WayPoint("C", rail.id, targetArc))
        world.simpleAtoWorldData.timetables.add(
            Timetable(
                "tt",
                services = listOf(
                    TimetableService(
                        id = "s1",
                        trainSelector = TrainSelector.TrainId(train.id),
                        steps = listOf(
                            TimetableStep.Check("A"),
                            TimetableStep.At(0L, TimetableAction.GoVia(listOf("B", "C")))
                        )
                    )
                )
            )
        )

        assertTrue(world.onOwnerForTest { dispatcher.startService(world, "tt", "s1") } is TimetableServiceStartResult.Started)
        val ato = train.controller as SimpleATO
        tickAndPublish(ato)
        world.onOwnerForTest { dispatcher.tick(world) }
        assertTrue(dispatcher.status(TimetableServiceRunId("tt", "s1"))?.routeGrantActive == true)

        var observedHeadOnlyPastVia = false
        var completedHead: Double? = null
        repeat(4_000) {
            tickAndPublish(ato)
            world.onOwnerForTest { dispatcher.tick(world) }
            val grantActive = dispatcher.status(TimetableServiceRunId("tt", "s1"))?.routeGrantActive == true
            if (train.head.location.arcLength > viaArc + 0.2 &&
                train.tailBogie.location.arcLength < viaArc - 0.2
            ) {
                observedHeadOnlyPastVia = true
                assertTrue(grantActive)
            }
            if (dispatcher.status(TimetableServiceRunId("tt", "s1"))?.state ==
                TimetableServiceRunState.COMPLETED
            ) {
                if (completedHead == null) completedHead = train.head.location.arcLength
                assertEquals(0.0, train.speed, "a completed service must remain stopped in later physics frames")
                assertEquals(completedHead!!, train.head.location.arcLength, 1.0e-9)
                return@repeat
            }
        }

        assertTrue(observedHeadOnlyPastVia)
        assertEquals(targetArc, train.head.location.arcLength, 0.4)
        assertEquals(
            TimetableServiceRunState.COMPLETED,
            dispatcher.status(TimetableServiceRunId("tt", "s1"))?.state
        )
    }

    @Test
    fun `advances through dwell and a later movement mission`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        val dispatcher = dispatcher()
        val data = BasicWorldData()
        TestSimpleATOWorldDataRegistry.register(world, data)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(140.0, segmentCount = 1)
        )
        val train = createTrainWithConfig("T1", rail, world, listOf(10.0))
        val originArc = train.head.location.arcLength
        data.wayPoints.add(WayPoint("A", rail.id, originArc))
        data.wayPoints.add(WayPoint("B", rail.id, originArc + 30.0))
        data.wayPoints.add(WayPoint("C", rail.id, originArc + 65.0))
        world.simpleAtoWorldData.timetables.add(
            Timetable(
                "tt",
                services = listOf(
                    TimetableService(
                        id = "s1",
                        trainSelector = TrainSelector.TrainId(train.id),
                        steps = listOf(
                            TimetableStep.Check("A"),
                            TimetableStep.At(0L, TimetableAction.GoTo("B")),
                            TimetableStep.At(20L, TimetableAction.Stop(5L)),
                            TimetableStep.At(40L, TimetableAction.GoTo("C"))
                        )
                    )
                )
            )
        )

        assertTrue(world.onOwnerForTest { dispatcher.startService(world, "tt", "s1") } is TimetableServiceStartResult.Started)
        val ato = train.controller as SimpleATO
        var observedDwell = false
        var dwellPosition: Double? = null
        var firstRevision = -1L
        repeat(5_000) {
            tickAndPublish(ato)
            world.onOwnerForTest { dispatcher.tick(world) }
            val status = dispatcher.status(TimetableServiceRunId("tt", "s1"))!!
            if (firstRevision < 0L) firstRevision = status.missionRevision
            if (status.state == TimetableServiceRunState.DWELLING) {
                observedDwell = true
                assertEquals(0.0, train.speed, "ordinary dwell must retain an actually stopped train")
                if (dwellPosition == null) dwellPosition = train.head.location.arcLength
                assertEquals(dwellPosition!!, train.head.location.arcLength, 1.0e-9)
            }
            if (status.state == TimetableServiceRunState.COMPLETED) return@repeat
        }

        val completed = dispatcher.status(TimetableServiceRunId("tt", "s1"))!!
        assertTrue(observedDwell)
        assertTrue(completed.missionRevision > firstRevision)
        assertEquals(TimetableServiceRunState.COMPLETED, completed.state)
        assertEquals(originArc + 65.0, train.head.location.arcLength, 0.4)
    }

    @Test
    fun `stopped train immediately requests a turnout inside protection distance`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        val dispatcher = dispatcher()
        val data = BasicWorldData()
        TestSimpleATOWorldDataRegistry.register(world, data)
        val approach = rail(world, Vector3d(0.0, 0.0, 0.0), Vector3d(30.0, 0.0, 0.0))
        val straight = rail(world, Vector3d(30.0, 0.0, 0.0), Vector3d(60.0, 0.0, 0.0))
        val branch = rail(world, Vector3d(30.0, 0.0, 0.0), Vector3d(60.0, 0.0, 10.0))
        IsolatedSimulationThreadMarker.run { CoreRail.connect(approach, straight) }
        IsolatedSimulationThreadMarker.run { CoreRail.connect(approach, branch) }
        approach.viewAB.selectNextRail(straight.viewAB)
        val train = createTrainWithConfig("near-turnout", approach, world, listOf(10.0))
        val origin = train.head.location.arcLength
        assertTrue(30.0 - origin in 5.0..20.0)
        data.wayPoints.add(WayPoint("A", approach.id, origin))
        data.wayPoints.add(WayPoint("B", branch.id, 20.0))
        world.simpleAtoWorldData.timetables.add(Timetable("tt", services = listOf(service("s", train.id, "A", "B"))))

        assertTrue(world.onOwnerForTest { dispatcher.startService(world, "tt", "s") } is TimetableServiceStartResult.Started)
        tickAndPublish(train.controller as SimpleATO)
        world.onOwnerForTest { dispatcher.tick(world) }

        val status = dispatcher.status(TimetableServiceRunId("tt", "s"))
        assertTrue(status?.routeGrantActive == true, "$status occupied=${train.occupiedRailIntervals}")
        assertSame(branch.viewAB, approach.viewAB.nextActiveRail)
        assertFalse(world.simpleAtoWorldData.atoCoordinator.isStalled)
    }

    @Test
    fun `unconfirmed later section stops at its protection point without stalling`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        val dispatcher = dispatcher()
        val data = BasicWorldData()
        TestSimpleATOWorldDataRegistry.register(world, data)
        val approach = rail(world, Vector3d(0.0, 0.0, 0.0), Vector3d(100.0, 0.0, 0.0))
        val mainBranch = rail(world, Vector3d(100.0, 0.0, 0.0), Vector3d(150.0, 0.0, 0.0))
        val blockingBranch = rail(world, Vector3d(100.0, 0.0, 0.0), Vector3d(150.0, 0.0, 20.0))
        IsolatedSimulationThreadMarker.run { CoreRail.connect(approach, mainBranch) }
        IsolatedSimulationThreadMarker.run { CoreRail.connect(approach, blockingBranch) }
        val mainTrain = createTrainWithConfig("main", approach, world, listOf(10.0))
        val blockingTrain = createTrainWithConfig("blocking", approach, world, listOf(10.0))
        world.onOwnerForTest {
            blockingTrain.setLocation(mainTrain.head.location.forward(60.0)!!)
            blockingTrain.commit()
        }
        val mainOrigin = mainTrain.head.location.arcLength
        val protectionArc = 70.0
        data.wayPoints.add(WayPoint("M0", approach.id, mainOrigin))
        data.wayPoints.add(WayPoint("P", approach.id, protectionArc))
        data.wayPoints.add(WayPoint("M1", mainBranch.id, 20.0))
        data.wayPoints.add(WayPoint("B0", approach.id, blockingTrain.head.location.arcLength))
        data.wayPoints.add(WayPoint("B1", blockingBranch.id, 20.0))
        world.simpleAtoWorldData.timetables.add(
            Timetable(
                "tt",
                services = listOf(
                    service("blocking", blockingTrain.id, "B0", "B1", tick = 0L),
                    TimetableService(
                        id = "main",
                        trainSelector = TrainSelector.TrainId(mainTrain.id),
                        steps = listOf(
                            TimetableStep.Check("M0"),
                            TimetableStep.At(10L, TimetableAction.GoVia(listOf("P", "B0", "M1")))
                        )
                    )
                )
            )
        )

        assertTrue(world.onOwnerForTest { dispatcher.startService(world, "tt", "blocking") } is TimetableServiceStartResult.Started)
        assertTrue(world.onOwnerForTest { dispatcher.startService(world, "tt", "main") } is TimetableServiceStartResult.Started)
        val blockingAto = blockingTrain.controller as SimpleATO
        val mainAto = mainTrain.controller as SimpleATO
        for (ignored in 0 until 2_000) {
            tickAndPublish(blockingAto)
            tickAndPublish(mainAto)
            world.onOwnerForTest { dispatcher.tick(world) }
            if (dispatcher.status(TimetableServiceRunId("tt", "blocking"))
                    ?.routeGrantActive == true
            ) break
        }
        repeat(2_000) {
            tickAndPublish(mainAto)
            world.onOwnerForTest { dispatcher.tick(world) }
        }

        val status = dispatcher.status(TimetableServiceRunId("tt", "main"))!!
        val blockerStatus = dispatcher.status(TimetableServiceRunId("tt", "blocking"))
        assertEquals(
            TimetableServiceRunState.WAITING_ROUTE,
            status.state,
            "status=$status blocker=$blockerStatus selected=${approach.viewAB.nextActiveRail} " +
                "head=${mainTrain.head.location.arcLength} speed=${mainTrain.speed}"
        )
        assertEquals(0.0, mainTrain.speed)
        assertTrue(mainTrain.head.location.arcLength <= 80.0)
        assertTrue(!world.simpleAtoWorldData.atoCoordinator.isStalled)
        assertSame(blockingBranch.viewAB, approach.viewAB.nextActiveRail)
    }

    @Test
    fun `short turnout claim releases for a later conflicting service`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        val dispatcher = dispatcher()
        val data = BasicWorldData()
        TestSimpleATOWorldDataRegistry.register(world, data)
        val approach = rail(world, Vector3d(0.0, 0.0, 0.0), Vector3d(100.0, 0.0, 0.0))
        val firstBranch = rail(world, Vector3d(100.0, 0.0, 0.0), Vector3d(150.0, 0.0, 0.0))
        val secondBranch = rail(world, Vector3d(100.0, 0.0, 0.0), Vector3d(150.0, 0.0, 20.0))
        IsolatedSimulationThreadMarker.run { CoreRail.connect(approach, firstBranch) }
        IsolatedSimulationThreadMarker.run { CoreRail.connect(approach, secondBranch) }
        val firstTrain = createTrainWithConfig("T1", approach, world, listOf(10.0))
        val secondTrain = createTrainWithConfig("T2", approach, world, listOf(10.0))
        val secondOriginArc = firstTrain.head.location.arcLength + 40.0
        world.onOwnerForTest {
            secondTrain.setLocation(RailLocationWithFacing(secondBranch.viewAB, 40.0))
            secondTrain.commitMaintenancePlacement()
        }
        data.wayPoints.add(WayPoint("A1", approach.id, firstTrain.head.location.arcLength))
        data.wayPoints.add(WayPoint("A2", approach.id, secondOriginArc))
        data.wayPoints.add(WayPoint("B1", firstBranch.id, 20.0))
        data.wayPoints.add(WayPoint("B2", secondBranch.id, 20.0))
        world.simpleAtoWorldData.timetables.add(
            Timetable(
                id = "tt",
                services = listOf(
                    service("s1", firstTrain.id, "A1", "B1", viaIds = listOf("A2")),
                    service("s2", secondTrain.id, "A2", "B2")
                )
            )
        )

        assertTrue(world.onOwnerForTest { dispatcher.startService(world, "tt", "s1") } is TimetableServiceStartResult.Started)
        val firstAto = firstTrain.controller as SimpleATO
        repeat(2_000) {
            tickAndPublish(firstAto)
            world.onOwnerForTest { dispatcher.tick(world) }
        }
        assertEquals(
            TimetableServiceRunState.COMPLETED,
            dispatcher.status(TimetableServiceRunId("tt", "s1"))?.state,
            "status=${dispatcher.status(TimetableServiceRunId("tt", "s1"))} stall=${world.simpleAtoWorldData.atoCoordinator.lastStall}"
        )
        world.onOwnerForTest {
            secondTrain.setLocation(RailLocationWithFacing(approach.viewAB, secondOriginArc))
            secondTrain.commitMaintenancePlacement()
        }
        assertTrue(world.onOwnerForTest { dispatcher.startService(world, "tt", "s2") } is TimetableServiceStartResult.Started)
        val secondAto = secondTrain.controller as SimpleATO
        repeat(2_000) {
            tickAndPublish(secondAto)
            world.onOwnerForTest { dispatcher.tick(world) }
        }

        val firstStatus = dispatcher.status(TimetableServiceRunId("tt", "s1"))
        val secondStatus = dispatcher.status(TimetableServiceRunId("tt", "s2"))
        assertEquals(
            TimetableServiceRunState.COMPLETED,
            firstStatus?.state,
            "$firstStatus / $secondStatus / stall=${world.simpleAtoWorldData.atoCoordinator.lastStall}"
        )
        assertEquals(TimetableServiceRunState.COMPLETED, secondStatus?.state, "$firstStatus / $secondStatus")
        assertFalse(world.simpleAtoWorldData.atoCoordinator.isStalled)
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = [false, true])
    fun `derailed service retains its route while an independent train keeps moving`(manualRouted: Boolean) {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        val dispatcher = dispatcher()
        val coordinator = AtoWorldCoordinator("dispatcher-derail-isolation", reportSink = {})
        val data = BasicWorldData(safetyInterlock = coordinator.safetyInterlock)
        TestSimpleATOWorldDataRegistry.register(world, data, coordinator)
        val firstRail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(200.0, 1))
        val secondRail = rail(world, Vector3d(0.0, 0.0, 20.0), Vector3d(200.0, 0.0, 20.0))
        val failed = createTrainWithConfig("failed", firstRail, world, listOf(10.0))
        val healthy = createTrainWithConfig("healthy", secondRail, world, listOf(10.0))
        data.wayPoints.add(WayPoint("F0", firstRail.id, failed.head.location.arcLength))
        data.wayPoints.add(WayPoint("F1", firstRail.id, 180.0))
        data.wayPoints.add(WayPoint("H0", secondRail.id, healthy.head.location.arcLength))
        data.wayPoints.add(WayPoint("H1", secondRail.id, 180.0))
        world.simpleAtoWorldData.timetables.add(Timetable("tt-isolation", services = listOf(
            service("failed", failed.id, "F0", "F1"),
            service("healthy", healthy.id, "H0", "H1")
        )))
        val failedId = TimetableServiceRunId("tt-isolation", "failed")
        val healthyId = TimetableServiceRunId("tt-isolation", "healthy")
        listOf(failedId, healthyId).forEach { id ->
            assertTrue(world.onOwnerForTest { dispatcher.startService(world, id.timetableId, id.serviceId) }
                is TimetableServiceStartResult.Started)
        }
        val failedAto = failed.controller as SimpleATO
        val healthyAto = healthy.controller as SimpleATO
        repeat(40) {
            tickAndPublish(failedAto)
            tickAndPublish(healthyAto)
            world.onOwnerForTest { dispatcher.tick(world) }
        }
        assertTrue(healthy.speed > 0.0)
        assertTrue(dispatcher.status(failedId)!!.routeGrantActive)
        val speedBefore = healthy.speed
        val headBefore = healthy.head.location.arcLength
        if (manualRouted) {
            assertTrue(world.onOwnerForTest { dispatcher.enterManualRoutedControl(world, failed.id, "driver") }
                is TimetableControlTransferResult.ManualRouted)
            val manual = ManualController(failed, maxSpeed = 8.0)
            failed.setControllerForTest(manual)
            assertTrue(world.onOwnerForTest { dispatcher.bindManualRoutedController(failed.id, "driver", manual) })
        }
        world.onOwnerForTest { failed.derail("test single-train fault") }
        val failedHead = failed.head.location.arcLength
        world.onOwnerForTest { dispatcher.tick(world) }
        assertFalse(coordinator.isStalled)
        assertEquals(speedBefore, healthy.speed)
        assertEquals(TimetableServiceRunState.FAILED, dispatcher.status(failedId)!!.state)
        assertTrue(dispatcher.status(failedId)!!.message.orEmpty().contains("test single-train fault"))
        assertNull(failed.controller)
        repeat(40) {
            tickAndPublish(healthyAto)
            world.onOwnerForTest { dispatcher.tick(world) }
            assertFalse(coordinator.isStalled)
            assertTrue(dispatcher.status(failedId)!!.routeGrantActive)
            assertTrue(healthy.speed > 0.0)
        }
        assertTrue(healthy.head.location.arcLength > headBefore)
        assertEquals(failedHead, failed.head.location.arcLength)
        assertFalse(dispatcher.status(healthyId)!!.state == TimetableServiceRunState.FAILED)
    }

    @Test
    fun `world stall aborts remaining route allocations in the same dispatcher tick`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        val dispatcher = dispatcher()
        val coordinator = AtoWorldCoordinator("dispatcher-stall-boundary", reportSink = {})
        val data = BasicWorldData(safetyInterlock = coordinator.safetyInterlock)
        TestSimpleATOWorldDataRegistry.register(world, data, coordinator)
        val firstRail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        val secondRail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        val firstTrain = createTrainWithConfig("first", firstRail, world, listOf(10.0))
        val secondTrain = createTrainWithConfig("second", secondRail, world, listOf(10.0))
        data.wayPoints.add(WayPoint("F0", firstRail.id, firstTrain.head.location.arcLength))
        data.wayPoints.add(WayPoint("F1", firstRail.id, 80.0))
        data.wayPoints.add(WayPoint("S0", secondRail.id, secondTrain.head.location.arcLength))
        data.wayPoints.add(WayPoint("S1", secondRail.id, 80.0))
        world.simpleAtoWorldData.timetables.add(
            Timetable(
                "tt-stall-boundary",
                services = listOf(
                    service("first", firstTrain.id, "F0", "F1"),
                    service("second", secondTrain.id, "S0", "S1")
                )
            )
        )
        val firstId = TimetableServiceRunId("tt-stall-boundary", "first")
        val secondId = TimetableServiceRunId("tt-stall-boundary", "second")
        assertTrue(world.onOwnerForTest { dispatcher.startService(world, firstId.timetableId, firstId.serviceId) } is TimetableServiceStartResult.Started)
        assertTrue(world.onOwnerForTest { dispatcher.startService(world, secondId.timetableId, secondId.serviceId) } is TimetableServiceStartResult.Started)
        tickAndPublish(firstTrain.controller as SimpleATO)
        tickAndPublish(secondTrain.controller as SimpleATO)
        secondTrain.setControllerForTest(null)

        world.onOwnerForTest { dispatcher.tick(world) }

        assertTrue(coordinator.isStalled)
        assertEquals(TimetableServiceRunState.FAILED, dispatcher.status(secondId)?.state)
        assertTrue(dispatcher.status(firstId)?.routeGrantActive == true)
    }

    @Test
    fun `short claims let physically separated conflicting services proceed`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        val dispatcher = dispatcher()
        val data = BasicWorldData()
        TestSimpleATOWorldDataRegistry.register(world, data)
        val approach = rail(world, Vector3d(0.0, 0.0, 0.0), Vector3d(100.0, 0.0, 0.0))
        val lateBranch = rail(world, Vector3d(100.0, 0.0, 0.0), Vector3d(150.0, 0.0, 0.0))
        val earlyBranch = rail(world, Vector3d(100.0, 0.0, 0.0), Vector3d(150.0, 0.0, 20.0))
        IsolatedSimulationThreadMarker.run { CoreRail.connect(approach, lateBranch) }
        IsolatedSimulationThreadMarker.run { CoreRail.connect(approach, earlyBranch) }
        val lateTrain = createTrainWithConfig("late-train", approach, world, listOf(10.0))
        val earlyTrain = createTrainWithConfig("early-train", approach, world, listOf(10.0))
        world.onOwnerForTest {
            earlyTrain.setLocation(lateTrain.head.location.forward(40.0)!!)
            earlyTrain.commit()
        }
        data.wayPoints.add(WayPoint("L0", approach.id, lateTrain.head.location.arcLength))
        data.wayPoints.add(WayPoint("E0", approach.id, earlyTrain.head.location.arcLength))
        data.wayPoints.add(WayPoint("L1", lateBranch.id, 20.0))
        data.wayPoints.add(WayPoint("E1", earlyBranch.id, 20.0))
        world.simpleAtoWorldData.timetables.add(
            Timetable(
                "tt",
                services = listOf(
                    service("late", lateTrain.id, "L0", "L1", tick = 100L, viaIds = listOf("E0")),
                    service("early", earlyTrain.id, "E0", "E1", tick = 10L)
                )
            )
        )

        world.onOwnerForTest { dispatcher.beginTimetable(world, "tt") }
        repeat(100) { world.onOwnerForTest { dispatcher.tick(world) } }
        assertTrue(world.onOwnerForTest { dispatcher.startService(world, "tt", "late") } is TimetableServiceStartResult.Started)
        assertTrue(world.onOwnerForTest { dispatcher.startService(world, "tt", "early") } is TimetableServiceStartResult.Started)
        val lateAto = lateTrain.controller as SimpleATO
        val earlyAto = earlyTrain.controller as SimpleATO
        repeat(5_000) {
            if (earlyTrain.head.location.rail.id == approach.id &&
                earlyTrain.head.location.arcLength > 95.0
            ) assertSame(
                earlyBranch.viewAB,
                approach.viewAB.nextActiveRail,
                "early=${dispatcher.status(TimetableServiceRunId("tt", "early"))} " +
                    "late=${dispatcher.status(TimetableServiceRunId("tt", "late"))}"
            )
            tickAndPublish(lateAto)
            tickAndPublish(earlyAto)
            world.onOwnerForTest { dispatcher.tick(world) }
        }

        assertEquals(
            TimetableServiceRunState.COMPLETED,
            dispatcher.status(TimetableServiceRunId("tt", "early"))?.state,
            "early=${dispatcher.status(TimetableServiceRunId("tt", "early"))} " +
                "late=${dispatcher.status(TimetableServiceRunId("tt", "late"))} " +
                "stall=${world.simpleAtoWorldData.atoCoordinator.lastStall}"
        )
        assertEquals(
            TimetableServiceRunState.COMPLETED,
            dispatcher.status(TimetableServiceRunId("tt", "late"))?.state
        )
        assertFalse(world.simpleAtoWorldData.atoCoordinator.isStalled)
    }

    @Test
    fun `runtime activation rejects before evaluating provider or installing controller`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        val dispatcher = dispatcher()
        val data = BasicWorldData()
        TestSimpleATOWorldDataRegistry.register(world, data)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        val train = createTrainWithConfig("T1", rail, world, listOf(10.0))
        val originArc = train.head.location.arcLength
        data.wayPoints.add(WayPoint("A", rail.id, originArc))
        data.wayPoints.add(WayPoint("B", rail.id, originArc + 30.0))
        var enabled = false
        var evaluations = 0
        world.simpleAtoWorldData.timetableExtensions.registerCondition(object : TimetableConditionProvider {
            override val typeId = "test:demand"
            override val version = 1
            override val descriptor = TimetableExtensionDescriptor("Demand", "Test demand")
            override fun evaluate(parameters: JsonObject, context: TimetableRuntimeContext): TimetableConditionResult {
                evaluations++
                assertNull(context.train)
                return if (enabled) TimetableConditionResult.True else TimetableConditionResult.False
            }
        })
        world.simpleAtoWorldData.timetables.add(Timetable(
            "tt-activation",
            services = listOf(service("service", train.id, "A", "B").copy(
                activation = TimetableServiceActivation.Conditional(
                    TimetableCondition.Predicate(TimetableExtensionCall("test:demand")),
                    TimetableActivationFalseBehavior.WAIT_UNTIL_TRUE
                )
            ))
        ))

        val rejected = world.onOwnerForTest { dispatcher.beginTimetable(world, "tt-activation") }
        assertInstanceOf(TimetableEpochStartResult.Failure::class.java, rejected)
        assertEquals(0, evaluations)
        assertNull(train.controller)
        assertNull(dispatcher.status(TimetableServiceRunId("tt-activation", "service")))
        assertFalse(world.simpleAtoWorldData.atoCoordinator.isStalled)

    }

    @Test
    fun `compile-selected action starts once and waits for registered extension ticket`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        val dispatcher = dispatcher()
        val data = BasicWorldData()
        TestSimpleATOWorldDataRegistry.register(world, data)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        val train = createTrainWithConfig("T1", rail, world, listOf(10.0))
        val originArc = train.head.location.arcLength
        data.wayPoints.add(WayPoint("A", rail.id, originArc))
        data.wayPoints.add(WayPoint("B", rail.id, originArc + 30.0))

        var evaluations = 0
        var actionStarts = 0
        val extensionDescriptor = TimetableExtensionDescriptor("Test", "Test extension")
        world.simpleAtoWorldData.timetableExtensions.registerCondition(object : TimetableConditionProvider {
            override val typeId = "test:enabled"
            override val version = 1
            override val descriptor = extensionDescriptor
            override fun evaluate(parameters: JsonObject, context: TimetableRuntimeContext): TimetableConditionResult {
                evaluations++
                assertEquals("B", context.currentWayPointId)
                assertEquals(train.id, context.train?.trainId)
                return TimetableConditionResult.True
            }
        })
        world.simpleAtoWorldData.timetableExtensions.registerAction(object : TimetableActionProvider {
            override val typeId = "test:record"
            override val version = 1
            override val descriptor = extensionDescriptor
            override fun start(
                parameters: JsonObject,
                context: TimetableRuntimeContext
            ): TimetableExtensionActionStartResult {
                actionStarts++
                return TimetableExtensionActionStartResult.Accepted(object : TimetableExtensionActionTicket {
                    override fun snapshot() = TimetableExtensionActionSnapshot(
                        TimetableExtensionActionState.SUCCEEDED
                    )
                    override fun cancel() = Unit
                })
            }
        })
        world.simpleAtoWorldData.timetables.add(Timetable(
            "tt-extension",
            services = listOf(TimetableService(
                id = "service",
                trainSelector = TrainSelector.TrainId(train.id),
                steps = listOf(
                    TimetableStep.Check("A"),
                    TimetableStep.At(0L, TimetableAction.GoTo("B")),
                    TimetableStep.At(0L, TimetableAction.Extension(TimetableExtensionCall("test:record")))
                )
            ))
        ))

        assertTrue(world.onOwnerForTest { dispatcher.startService(world, "tt-extension", "service") } is TimetableServiceStartResult.Started)
        val ato = train.controller as SimpleATO
        tickAndPublish(ato)
        world.onOwnerForTest { dispatcher.tick(world) }
        // Rolling extensions require the dispatcher during travel. Stop immediately when the
        // action starts so this test still observes the ticket before its first poll.
        var movementTicks = 0
        while (dispatcher.status(TimetableServiceRunId("tt-extension", "service"))?.state !=
            TimetableServiceRunState.WAITING_EXTENSION_ACTION && movementTicks++ < 600) {
            tickAndPublish(ato)
            world.onOwnerForTest { dispatcher.tick(world) }
        }

        assertEquals(TimetableServiceRunState.WAITING_EXTENSION_ACTION,
            dispatcher.status(TimetableServiceRunId("tt-extension", "service"))?.state)
        assertEquals(0, evaluations)
        assertEquals(1, actionStarts)

        world.onOwnerForTest { dispatcher.tick(world) }
        assertEquals(TimetableServiceRunState.COMPLETED,
            dispatcher.status(TimetableServiceRunId("tt-extension", "service"))?.state)
        assertEquals(0, evaluations)
        assertEquals(1, actionStarts)
    }

    @Test
    fun `stop accepts terminal extension tickets before the next dispatcher poll`() {
        for (state in listOf(
            TimetableExtensionActionState.SUCCEEDED,
            TimetableExtensionActionState.FAILED,
            TimetableExtensionActionState.CANCELLED
        )) {
            assertExtensionStop(state, confirmCancellation = false, expectedSuccess = true)
        }
    }

    @Test
    fun `stop requires cancellation confirmation for pending extension tickets`() {
        assertExtensionStop(
            TimetableExtensionActionState.PENDING,
            confirmCancellation = true,
            expectedSuccess = true
        )
        assertExtensionStop(
            TimetableExtensionActionState.PENDING,
            confirmCancellation = false,
            expectedSuccess = false
        )
    }

    private fun assertExtensionStop(
        initialState: TimetableExtensionActionState,
        confirmCancellation: Boolean,
        expectedSuccess: Boolean
    ) {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        val dispatcher = dispatcher()
        val data = BasicWorldData()
        TestSimpleATOWorldDataRegistry.register(world, data)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        val train = createTrainWithConfig("T1", rail, world, listOf(10.0))
        val originArc = train.head.location.arcLength
        data.wayPoints.add(WayPoint("A", rail.id, originArc))
        data.wayPoints.add(WayPoint("B", rail.id, originArc + 30.0))
        var ticketState = initialState
        var cancellationCalls = 0
        var actionStarts = 0
        world.simpleAtoWorldData.timetableExtensions.registerAction(object : TimetableActionProvider {
            override val typeId = "test:stop"
            override val version = 1
            override val descriptor = TimetableExtensionDescriptor("Stop", "Stop regression")
            override fun start(
                parameters: JsonObject,
                context: TimetableRuntimeContext
            ): TimetableExtensionActionStartResult {
                actionStarts++
                assertEquals("B", context.currentWayPointId)
                return TimetableExtensionActionStartResult.Accepted(object : TimetableExtensionActionTicket {
                    override fun snapshot() = TimetableExtensionActionSnapshot(ticketState)
                    override fun cancel() {
                        cancellationCalls++
                        check(ticketState == TimetableExtensionActionState.PENDING) {
                            "terminal actions must not be cancelled"
                        }
                        if (confirmCancellation) ticketState = TimetableExtensionActionState.CANCELLED
                    }
                })
            }
        })
        world.simpleAtoWorldData.timetables.add(Timetable(
            "tt-extension-stop",
            services = listOf(TimetableService(
                id = "service",
                trainSelector = TrainSelector.TrainId(train.id),
                steps = listOf(
                    TimetableStep.Check("A"),
                    TimetableStep.At(0L, TimetableAction.GoTo("B")),
                    TimetableStep.At(0L, TimetableAction.Extension(TimetableExtensionCall("test:stop")))
                )
            ))
        ))
        val runId = TimetableServiceRunId("tt-extension-stop", "service")
        assertTrue(world.onOwnerForTest {
            dispatcher.startService(world, runId.timetableId, runId.serviceId)
        } is TimetableServiceStartResult.Started)
        val ato = train.controller as SimpleATO
        tickAndPublish(ato)
        world.onOwnerForTest { dispatcher.tick(world) }
        var movementTicks = 0
        while (dispatcher.status(runId)?.state != TimetableServiceRunState.WAITING_EXTENSION_ACTION &&
            movementTicks++ < 600) {
            tickAndPublish(ato)
            world.onOwnerForTest { dispatcher.tick(world) }
        }
        assertEquals(TimetableServiceRunState.WAITING_EXTENSION_ACTION, dispatcher.status(runId)?.state)
        assertEquals(1, actionStarts)
        assertFalse(world.simpleAtoWorldData.atoCoordinator.isStalled)

        // Stop in the real post-destination window, before the next dispatcher tick polls the ticket.
        assertEquals(expectedSuccess, world.onOwnerForTest { dispatcher.stopService(world, runId) })

        assertEquals(!expectedSuccess, world.simpleAtoWorldData.atoCoordinator.isStalled)
        if (initialState != TimetableExtensionActionState.PENDING) {
            assertEquals(0, cancellationCalls)
        } else {
            assertTrue(cancellationCalls >= 1)
        }
        if (expectedSuccess) {
            assertEquals(TimetableServiceRunState.STOPPING, dispatcher.status(runId)?.state)
        } else {
            assertTrue(dispatcher.status(runId)?.message.orEmpty().contains("remained PENDING"))
        }
    }

    @Test
    fun `compile-selected timed children preserve waits and extension scheduled ticks`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        val dispatcher = dispatcher()
        val data = BasicWorldData()
        TestSimpleATOWorldDataRegistry.register(world, data)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        val train = createTrainWithConfig("T1", rail, world, listOf(10.0))
        val originArc = train.head.location.arcLength
        data.wayPoints.add(WayPoint("A", rail.id, originArc))
        // This fixture deliberately advances physics independently of timetable ticks to test
        // absolute child times. Keep the setup movement inside the initial rolling window.
        data.wayPoints.add(WayPoint("B", rail.id, originArc + 10.0))

        var evaluations = 0
        var actionStarts = 0
        val scheduled = arrayListOf<Long?>()
        val extensionDescriptor = TimetableExtensionDescriptor("Test", "Test extension")
        world.simpleAtoWorldData.timetableExtensions.registerCondition(object : TimetableConditionProvider {
            override val typeId = "test:enabled"
            override val version = 1
            override val descriptor = extensionDescriptor
            override fun evaluate(parameters: JsonObject, context: TimetableRuntimeContext): TimetableConditionResult {
                evaluations++
                assertEquals("B", context.currentWayPointId)
                assertEquals(train.id, context.train?.trainId)
                return TimetableConditionResult.True
            }
        })
        world.simpleAtoWorldData.timetableExtensions.registerAction(object : TimetableActionProvider {
            override val typeId = "test:record"
            override val version = 1
            override val descriptor = extensionDescriptor
            override fun start(
                parameters: JsonObject,
                context: TimetableRuntimeContext
            ): TimetableExtensionActionStartResult {
                actionStarts++
                scheduled += context.scheduledTick
                return TimetableExtensionActionStartResult.Accepted(object : TimetableExtensionActionTicket {
                    override fun snapshot() = TimetableExtensionActionSnapshot(
                        TimetableExtensionActionState.SUCCEEDED
                    )
                    override fun cancel() = Unit
                })
            }
        })
        world.simpleAtoWorldData.timetables.add(Timetable(
            "tt-extension",
            services = listOf(TimetableService(
                id = "service",
                trainSelector = TrainSelector.TrainId(train.id),
                steps = listOf(
                    TimetableStep.Check("A"),
                    TimetableStep.At(0L, TimetableAction.GoTo("B")),
                    TimetableStep.At(10L, TimetableAction.WaitUntil),
                    TimetableStep.At(15L, TimetableAction.WaitUntil),
                    TimetableStep.At(15L, TimetableAction.Stop(2L)),
                    TimetableStep.At(20L, TimetableAction.Extension(TimetableExtensionCall("test:record"))),
                    TimetableStep.At(25L, TimetableAction.Extension(TimetableExtensionCall("test:record")))
                )
            ))
        ))

        assertTrue(world.onOwnerForTest { dispatcher.startService(world, "tt-extension", "service") } is TimetableServiceStartResult.Started)
        val ato = train.controller as SimpleATO
        tickAndPublish(ato)
        world.onOwnerForTest { dispatcher.tick(world) }
        repeat(600) { tickAndPublish(ato) }
        world.onOwnerForTest { dispatcher.tick(world) }

        val id = TimetableServiceRunId("tt-extension", "service")
        assertEquals(0, evaluations)
        assertEquals(0, actionStarts)
        assertEquals(10L, dispatcher.status(id)?.nextScheduledTick)
        assertEquals(emptyList<TimetableBranchEntry>(), dispatcher.status(id)?.nextStepBranchPath)
        repeat(8) { world.onOwnerForTest { dispatcher.tick(world) } }
        assertEquals(0, evaluations)
        assertEquals(15L, dispatcher.status(id)?.nextScheduledTick)
        assertEquals(emptyList<TimetableBranchEntry>(),
            dispatcher.status(id)?.nextStepBranchPath)
        repeat(5) { world.onOwnerForTest { dispatcher.tick(world) } }
        assertEquals(TimetableServiceRunState.DWELLING, dispatcher.status(id)?.state)
        assertEquals(0, actionStarts)
        repeat(2) { world.onOwnerForTest { dispatcher.tick(world) } }
        assertEquals(TimetableServiceRunState.WAITING_TIMETABLE_TIME, dispatcher.status(id)?.state)
        assertEquals(20L, dispatcher.status(id)?.nextScheduledTick)
        repeat(3) { world.onOwnerForTest { dispatcher.tick(world) } }
        assertEquals(listOf(20L), scheduled)
        assertEquals(TimetableServiceRunState.WAITING_EXTENSION_ACTION, dispatcher.status(id)?.state)
        repeat(5) { world.onOwnerForTest { dispatcher.tick(world) } }
        assertEquals(listOf(20L, 25L), scheduled)
        world.onOwnerForTest { dispatcher.tick(world) }
        assertEquals(TimetableServiceRunState.COMPLETED, dispatcher.status(id)?.state)
        assertEquals(0, evaluations, "compiled plans do not evaluate runtime conditions")
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = [false, true])
    fun `compile-selected movement preserves successor and actual onward origin`(chooseThen: Boolean) {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        val dispatcher = dispatcher()
        val data = BasicWorldData()
        TestSimpleATOWorldDataRegistry.register(world, data)
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(150.0, 1))
        val train = createTrainWithConfig("T1", rail, world, listOf(10.0))
        val origin = train.publishedFrame.head.arcLengthInAbMeters
        listOf("A" to origin, "B" to 50.0, "C" to 60.0, "D" to 100.0).forEach { (id, arc) ->
            data.wayPoints.add(WayPoint(id, rail.id, arc))
        }
        var evaluations = 0
        world.simpleAtoWorldData.timetableExtensions.registerCondition(object : TimetableConditionProvider {
            override val typeId = "test:branch-choice"
            override val version = 1
            override val descriptor = TimetableExtensionDescriptor("Choice", "Fixed test choice")
            override fun validate(parameters: JsonObject) = emptyList<String>()
            override fun evaluate(parameters: JsonObject, context: TimetableRuntimeContext): TimetableConditionResult {
                evaluations++
                return if (chooseThen) TimetableConditionResult.True else TimetableConditionResult.False
            }
        })
        val firstMovement = if (chooseThen) TimetableAction.GoTo("B") else TimetableAction.GoVia(listOf("B","C"))
        val onward = if (chooseThen) TimetableAction.GoVia(listOf("C","D")) else TimetableAction.GoTo("D")
        world.simpleAtoWorldData.timetables.add(Timetable("branch-motion", services = listOf(TimetableService("service",
            TrainSelector.TrainId(train.id), listOf(TimetableStep.Check("A"), TimetableStep.At(0L, firstMovement),
                TimetableStep.At(0L, onward))))))
        val id = TimetableServiceRunId("branch-motion", "service")
        world.onOwnerForTest {
            assertInstanceOf(TimetableEpochStartResult.Started::class.java, dispatcher.beginTimetable(world, id.timetableId))
            assertInstanceOf(TimetableServiceStartResult.Started::class.java, dispatcher.startService(world, id.timetableId, id.serviceId))
        }
        val ato = train.controller as SimpleATO
        val initial = checkNotNull(dispatcher.status(id))
        assertEquals(1, initial.activeStepIndex)
        assertEquals(emptyList<TimetableBranchEntry>(), initial.activeStepBranchPath)
        assertEquals(2, initial.nextStepIndex)
        val origins = linkedSetOf<String?>()
        var ticks = 0
        while (dispatcher.status(id)?.state != TimetableServiceRunState.COMPLETED && ticks++ < 1200) {
            tickAndPublish(ato)
            world.onOwnerForTest { dispatcher.tick(world) }
            val status = checkNotNull(dispatcher.status(id))
            origins += status.currentWayPointId
            assertFalse(status.state in setOf(TimetableServiceRunState.FAILED, TimetableServiceRunState.REPLAN_REQUIRED), status.toString())
        }
        assertEquals(TimetableServiceRunState.COMPLETED, dispatcher.status(id)?.state)
        assertEquals(100.0, train.publishedFrame.head.arcLengthInAbMeters, 0.5)
        assertTrue((if (chooseThen) "B" else "C") in origins, origins.toString())
        assertFalse((if (chooseThen) "C" else "B") in origins, origins.toString())
        assertEquals(0, evaluations)
    }

    @Test
    fun `selected stop resolves real length commits one berth and continues from selected marker`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        // The simplified kinematic actuator needs reaction lookahead for station braking.
        val dispatcher = dispatcher(safetyProfile = profile.copy(reactionTimeSeconds = 0.1))
        val data = BasicWorldData()
        TestSimpleATOWorldDataRegistry.register(world, data)
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(150.0, 1))
        val train = createTrainWithConfig("T1", rail, world, listOf(10.0))
        data.wayPoints.add(WayPoint("A", rail.id, train.publishedFrame.head.arcLengthInAbMeters))
        data.wayPoints.add(WayPoint("D", rail.id, 100.0))
        val station = StationId("station")
        for ((id, arc) in listOf("short" to 60.0, "long" to 60.0)) {
            OperationalDestinationService(world).register(OperationalDestination("station@$id", rail.id, arc,
                ViewDirection.AB, DestinationRole.StationBerthRole(station, berthId = id, doorSide = DoorSide.NONE,
                    usableLengthMeters = if (id == "short") 5.0 else 20.0)))
        }
        val selection = work.art1st.javarailways.simpleato.api.station.StationStopSelection(station,
            listOf(work.art1st.javarailways.simpleato.api.station.StationStopCandidate("short", 0),
                work.art1st.javarailways.simpleato.api.station.StationStopCandidate("long", 1)),
            work.art1st.javarailways.simpleato.api.station.StationStopSelectionPolicy.AVAILABLE_PRIORITY)
        world.simpleAtoWorldData.timetables.add(Timetable("select-stop", services = listOf(TimetableService("service",
            TrainSelector.TrainId(train.id), listOf(TimetableStep.Check("A"),
                TimetableStep.At(0L, TimetableAction.SelectStop(selection)), TimetableStep.At(0L, TimetableAction.GoTo("D")))))))
        val id = TimetableServiceRunId("select-stop", "service")
        val start = world.onOwnerForTest { dispatcher.startService(world, id.timetableId, id.serviceId) }
        assertInstanceOf(TimetableServiceStartResult.Started::class.java, start, start.toString())
        assertEquals(StationBerthRef(station, "long"), dispatcher.status(id)?.selectedBerthRef)
        assertFalse(checkNotNull(dispatcher.status(id)).selectedBerthCommitted)
        assertNull(dispatcher.status(id)?.stationCallId, "controller call must wait for physical admission")
        val ato = train.controller as SimpleATO
        val visited = linkedSetOf<String?>()
        var ticks = 0
        while (dispatcher.status(id)?.state != TimetableServiceRunState.COMPLETED && ticks++ < 1600) {
            tickAndPublish(ato)
            world.onOwnerForTest { dispatcher.tick(world) }
            val status = checkNotNull(dispatcher.status(id))
            visited += status.currentWayPointId
            assertFalse(status.state in setOf(TimetableServiceRunState.FAILED, TimetableServiceRunState.REPLAN_REQUIRED), status.toString())
        }
        assertEquals(TimetableServiceRunState.COMPLETED, dispatcher.status(id)?.state)
        assertTrue(checkNotNull(dispatcher.status(id)).selectedBerthCommitted)
        assertTrue("station@long" in visited)
        assertFalse("station@short" in visited)
        assertEquals(100.0, train.publishedFrame.head.arcLengthInAbMeters, 0.5)
        assertFalse(checkNotNull(dispatcher.status(id)).berthHeld)
    }

    @Test
    fun `available selected stop retries a physically occupied preferred marker before granting movement`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        // This state-machine fixture integrates a kinematic actuator at 50ms; retain control response
        // rather than relying on the former tolerance-derived station stopping offset.
        val dispatcher = dispatcher(safetyProfile = profile.copy(reactionTimeSeconds = 0.1))
        val data = BasicWorldData()
        TestSimpleATOWorldDataRegistry.register(world, data)
        // Put the divergent paths inside the first short window. A distant blocked berth is
        // no longer a reason to reselect once movement on the preferred path was committed.
        val rail = rail(world, Vector3d(0.0, 0.0, 0.0), Vector3d(30.0, 0.0, 0.0))
        val shortBranch = rail(world, Vector3d(30.0, 0.0, 0.0), Vector3d(150.0, 0.0, 0.0))
        val longBranch = rail(world, Vector3d(30.0, 0.0, 0.0), Vector3d(150.0, 0.0, 20.0))
        IsolatedSimulationThreadMarker.run { CoreRail.connect(rail, shortBranch) }
        IsolatedSimulationThreadMarker.run { CoreRail.connect(rail, longBranch) }
        val train = createTrainWithConfig("T1", rail, world, listOf(10.0))
        data.wayPoints.add(WayPoint("A", rail.id, train.publishedFrame.head.arcLengthInAbMeters))
        data.wayPoints.add(WayPoint("D", rail.id, 10.0))
        val station = StationId("station")
        for ((id, branch) in listOf("short" to shortBranch, "long" to longBranch)) {
            OperationalDestinationService(world).register(OperationalDestination("station@$id", branch.id, 30.0,
                ViewDirection.AB, DestinationRole.StationBerthRole(station, berthId = id, doorSide = DoorSide.NONE,
                    usableLengthMeters = 20.0)))
        }
        world.onOwnerForTest {
            val head = RailLocationWithFacing(longBranch.viewAB, 20.0)
            val car = work.art1st.javarailways.core.physics.train.CoreCar.create("blocking-car",
                work.art1st.javarailways.core.physics.train.CoreBogie.create(head),
                work.art1st.javarailways.core.physics.train.CoreBogie.create(head), length = 10.0,
                floorHeight = 0.0, connectorLengthAtA = 0.0, connectorLengthAtB = 0.0,
                bogieAOffset = 5.0, bogieBOffset = -5.0, world = world)
            work.art1st.javarailways.core.physics.train.CoreTrain.create("blocker", listOf(car), world).also {
                it.setLocation(head); it.commitMaintenancePlacement()
            }
        }
        val selection = work.art1st.javarailways.simpleato.api.station.StationStopSelection(station,
            listOf(work.art1st.javarailways.simpleato.api.station.StationStopCandidate("short", 1),
                work.art1st.javarailways.simpleato.api.station.StationStopCandidate("long", 0)),
            work.art1st.javarailways.simpleato.api.station.StationStopSelectionPolicy.AVAILABLE_PRIORITY)
        world.simpleAtoWorldData.timetables.add(Timetable("select-stop", services = listOf(TimetableService("service",
            TrainSelector.TrainId(train.id), listOf(TimetableStep.Check("A"),
                TimetableStep.At(0L, TimetableAction.SelectStop(selection)), TimetableStep.At(0L, TimetableAction.Reverse),
                TimetableStep.At(0L, TimetableAction.GoVia(listOf("A", "D"))))))))
        val id = TimetableServiceRunId("select-stop", "service")
        val start = world.onOwnerForTest { dispatcher.startService(world, id.timetableId, id.serviceId) }
        assertInstanceOf(TimetableServiceStartResult.Started::class.java, start, start.toString())
        assertEquals(StationBerthRef(station, "long"), dispatcher.status(id)?.selectedBerthRef)
        assertFalse(checkNotNull(dispatcher.status(id)).selectedBerthCommitted)
        assertNull(dispatcher.status(id)?.stationCallId, "controller call must wait for physical admission")
        val ato = train.controller as SimpleATO
        val initialHead = train.publishedFrame.head
        val visited = linkedSetOf<String?>()
        var ticks = 0
        while (dispatcher.status(id)?.state != TimetableServiceRunState.COMPLETED && ticks++ < 1600) {
            tickAndPublish(ato)
            world.onOwnerForTest { dispatcher.tick(world) }
            val status = checkNotNull(dispatcher.status(id))
            visited += status.currentWayPointId
            if (status.selectedBerthRef == StationBerthRef(station, "long")) {
                assertFalse(status.selectedBerthCommitted, status.toString())
                assertEquals(initialHead, train.publishedFrame.head,
                    "the occupied preferred path must be rejected before any movement")
            }
            assertFalse(status.state in setOf(TimetableServiceRunState.FAILED, TimetableServiceRunState.REPLAN_REQUIRED), status.toString())
        }
        assertEquals(TimetableServiceRunState.COMPLETED, dispatcher.status(id)?.state)
        assertTrue(checkNotNull(dispatcher.status(id)).selectedBerthCommitted)
        assertEquals(StationBerthRef(station, "short"), dispatcher.status(id)?.selectedBerthRef)
        assertTrue("station@short" in visited)
        assertFalse("station@long" in visited)
        assertEquals(10.0, train.publishedFrame.head.arcLengthInAbMeters, 0.6)
        assertFalse(checkNotNull(dispatcher.status(id)).berthHeld)
    }

    @Test
    fun `stopping before selected route admission releases only tentative berth`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        val dispatcher = dispatcher()
        val data = BasicWorldData()
        TestSimpleATOWorldDataRegistry.register(world, data)
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(150.0, 1))
        val train = createTrainWithConfig("T1", rail, world, listOf(10.0))
        data.wayPoints.add(WayPoint("A", rail.id, train.publishedFrame.head.arcLengthInAbMeters))
        data.wayPoints.add(WayPoint("D", rail.id, 100.0))
        val station = StationId("station")
        for ((id, arc) in listOf("short" to 60.0, "long" to 60.0)) {
            OperationalDestinationService(world).register(OperationalDestination("station@$id", rail.id, arc,
                ViewDirection.AB, DestinationRole.StationBerthRole(station, berthId = id, doorSide = DoorSide.NONE,
                    usableLengthMeters = if (id == "short") 5.0 else 20.0)))
        }
        val selection = work.art1st.javarailways.simpleato.api.station.StationStopSelection(station,
            listOf(work.art1st.javarailways.simpleato.api.station.StationStopCandidate("short", 0),
                work.art1st.javarailways.simpleato.api.station.StationStopCandidate("long", 1)),
            work.art1st.javarailways.simpleato.api.station.StationStopSelectionPolicy.AVAILABLE_PRIORITY)
        world.simpleAtoWorldData.timetables.add(Timetable("select-stop", services = listOf(TimetableService("service",
            TrainSelector.TrainId(train.id), listOf(TimetableStep.Check("A"),
                TimetableStep.At(0L, TimetableAction.SelectStop(selection)), TimetableStep.At(0L, TimetableAction.GoTo("D")))))))
        val id = TimetableServiceRunId("select-stop", "service")
        val start = world.onOwnerForTest { dispatcher.startService(world, id.timetableId, id.serviceId) }
        assertInstanceOf(TimetableServiceStartResult.Started::class.java, start, start.toString())
        assertEquals(StationBerthRef(station, "long"), dispatcher.status(id)?.selectedBerthRef)
        assertFalse(checkNotNull(dispatcher.status(id)).selectedBerthCommitted)
        assertNull(dispatcher.status(id)?.stationCallId, "controller call must wait for physical admission")
        val berthRef = StationBerthRef(station, "long")
        assertTrue(dispatcher.hasStationBerthAllocation(berthRef))
        assertTrue(world.onOwnerForTest { dispatcher.stopService(world, id) })
        assertFalse(dispatcher.hasStationBerthAllocation(berthRef))
        assertNull(dispatcher.status(id)?.selectedBerthRef)
        assertNull(dispatcher.status(id)?.stationCallId)
        assertEquals(0.0, train.publishedFrame.speedMetersPerSecond)
    }

    @Test
    fun `stopping selected stop with real pending admission retains reservation until safe settlement`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        var ticketState = work.art1st.javarailways.basic.api.route.ResourcePreparationState.PREPARING
        var starts = 0
        val ticket = object : work.art1st.javarailways.basic.api.route.ResourcePreparationTicket {
            override fun snapshot() = work.art1st.javarailways.basic.api.route.ResourcePreparationSnapshot(ticketState)
            override fun abort() { ticketState = work.art1st.javarailways.basic.api.route.ResourcePreparationState.ABORTED_SAFE }
        }
        val dispatcher = dispatcher(admissionPreparation = { starts++; ticket })
        val data = BasicWorldData()
        TestSimpleATOWorldDataRegistry.register(world, data)
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(150.0, 1))
        val train = createTrainWithConfig("T1", rail, world, listOf(10.0))
        data.wayPoints.add(WayPoint("A", rail.id, train.publishedFrame.head.arcLengthInAbMeters))
        data.wayPoints.add(WayPoint("D", rail.id, 100.0))
        val station = StationId("station")
        for ((id, arc) in listOf("short" to 60.0, "long" to 60.0)) {
            OperationalDestinationService(world).register(OperationalDestination("station@$id", rail.id, arc,
                ViewDirection.AB, DestinationRole.StationBerthRole(station, berthId = id, doorSide = DoorSide.NONE,
                    usableLengthMeters = if (id == "short") 5.0 else 20.0)))
        }
        val selection = work.art1st.javarailways.simpleato.api.station.StationStopSelection(station,
            listOf(work.art1st.javarailways.simpleato.api.station.StationStopCandidate("short", 0),
                work.art1st.javarailways.simpleato.api.station.StationStopCandidate("long", 1)),
            work.art1st.javarailways.simpleato.api.station.StationStopSelectionPolicy.AVAILABLE_PRIORITY)
        world.simpleAtoWorldData.timetables.add(Timetable("select-stop", services = listOf(TimetableService("service",
            TrainSelector.TrainId(train.id), listOf(TimetableStep.Check("A"),
                TimetableStep.At(0L, TimetableAction.SelectStop(selection)), TimetableStep.At(0L, TimetableAction.GoTo("D")))))))
        val id = TimetableServiceRunId("select-stop", "service")
        val start = world.onOwnerForTest { dispatcher.startService(world, id.timetableId, id.serviceId) }
        assertInstanceOf(TimetableServiceStartResult.Started::class.java, start, start.toString())
        assertEquals(StationBerthRef(station, "long"), dispatcher.status(id)?.selectedBerthRef)
        assertFalse(checkNotNull(dispatcher.status(id)).selectedBerthCommitted)
        assertNull(dispatcher.status(id)?.stationCallId, "controller call must wait for physical admission")
        val ato = train.controller as SimpleATO
        val initialHead = train.publishedFrame.head
        val berthRef = StationBerthRef(station, "long")
        repeat(12) { tickAndPublish(ato); world.onOwnerForTest { dispatcher.tick(world) } }
        assertEquals(1, starts, "one pending preparation is observed rather than resubmitted")
        assertFalse(checkNotNull(dispatcher.status(id)).selectedBerthCommitted)
        assertTrue(dispatcher.hasStationBerthAllocation(berthRef))
        assertTrue(world.onOwnerForTest { dispatcher.stopService(world, id) })
        repeat(3) { tickAndPublish(ato); world.onOwnerForTest { dispatcher.tick(world) } }
        assertTrue(dispatcher.hasStationBerthAllocation(berthRef), "pending candidate keeps its exact reservation until settled")
        ticketState = work.art1st.javarailways.basic.api.route.ResourcePreparationState.ABORTED_SAFE
        repeat(3) { tickAndPublish(ato); world.onOwnerForTest { dispatcher.tick(world) } }
        assertEquals(1, starts)
        assertFalse(dispatcher.hasStationBerthAllocation(berthRef))
        assertNull(dispatcher.status(id)?.selectedBerthRef)
        assertNull(dispatcher.status(id)?.stationCallId)
        assertEquals(TimetableServiceRunState.STOPPED, dispatcher.status(id)?.state)
        assertEquals(initialHead, train.publishedFrame.head)
        assertEquals(0.0, train.publishedFrame.speedMetersPerSecond)
    }

    private fun dispatcher(
        infrastructureDependencyService: InfrastructureDependencyService = acceptingInfrastructureDependencyService(),
        inspectionRuntime: InspectionRuntime = emptyInspectionRuntime(),
        safetyProfile: AtoSafetyProfile = profile,
        admissionPreparation: (() -> work.art1st.javarailways.basic.api.route.ResourcePreparationTicket)? = null
    ): TimetableDispatcher {
        var boundWorld: CoreWorld? = null
        return TimetableDispatcher(
            authorityStoppingMarginMeters = safetyProfile.safetyDistanceMeters,
            routeAdmissionContextProvider = GrantingRouteAdmissionTestContext(admissionPreparation) { boundWorld },
            signalRestrictionSnapshots = emptyTrainRestrictionSnapshots,
            inspectionRuntime = inspectionRuntime,
            infrastructureDependencyService = infrastructureDependencyService,
            atoFactory = TimetableAtoFactory { train, world ->
                boundWorld = world as CoreWorld
                SimpleATO(
                    train,
                    world,
                    KinematicTrainActuator(10.0, safetyProfile),
                    safetyProfile,
                    inspectionRuntime::maximumDeclaredLeadTimeSeconds
                )
            }
        )
    }

    private fun timetable(
        timetableId: String,
        serviceId: String,
        trainId: String,
        origin: String,
        target: String
    ): Timetable = Timetable(
        timetableId,
        services = listOf(service(serviceId, trainId, origin, target))
    )

    private fun service(
        serviceId: String,
        trainId: String,
        origin: String,
        target: String,
        tick: Long = 0L,
        viaIds: List<String> = emptyList()
    ): TimetableService = TimetableService(
        id = serviceId,
        trainSelector = TrainSelector.TrainId(trainId),
        steps = listOf(
            TimetableStep.Check(origin),
            TimetableStep.At(tick, if (viaIds.isEmpty()) TimetableAction.GoTo(target) else TimetableAction.GoVia(viaIds + target))
        )
    )

    private fun tickAndPublish(ato: SimpleATO, deltaMillis: Double = 50.0) {
        AsyncTickTestSupport.run { ato.tick(deltaMillis) }
        ato.commit()
    }

    private fun depotBranchLoop(world: CoreWorld): Triple<CoreRail, CoreRail, CoreRail> {
        val (branchCurve, returnCurve) = PrimeCurveGenerator.buildCircle(140.0 / PI, 2, step = 0.1)
        val outbound = world.createRailForTest(branchCurve)
        val inbound = world.createRailForTest(branchCurve)
        val shared = world.createRailForTest(returnCurve)
        IsolatedSimulationThreadMarker.run {
            RailView.connect(outbound.viewAB, shared.viewBA)
            RailView.connect(inbound.viewAB, shared.viewBA)
            RailView.connect(shared.viewAB, outbound.viewBA)
            RailView.connect(shared.viewAB, inbound.viewBA)
        }
        return Triple(outbound, inbound, shared)
    }

    private fun depotLoop(world: CoreWorld): Pair<CoreRail, CoreRail> {
        // Two tangent-continuous semicircles keep both original 140 m route coordinates.
        val (outboundCurve, returnCurve) = PrimeCurveGenerator.buildCircle(140.0 / PI, 2, step = 0.1)
        val outboundRail = world.createRailForTest(outboundCurve)
        val returnRail = world.createRailForTest(returnCurve)
        IsolatedSimulationThreadMarker.run { RailView.connect(outboundRail.viewAB, returnRail.viewBA) }
        IsolatedSimulationThreadMarker.run { RailView.connect(returnRail.viewAB, outboundRail.viewBA) }
        return outboundRail to returnRail
    }

    private fun rail(world: CoreWorld, from: Vector3d, to: Vector3d): CoreRail =
        world.createRailForTest(straightCurve(from, to))

    private fun straightCurve(from: Vector3d, to: Vector3d): PrimeCurve {
        val tangent = Vector3d(to).sub(from).normalize()
        val normal = Vector3d(0.0, 1.0, 0.0)
        val length = from.distance(to)
        return PrimeCurve.fromPoints(listOf(
            Curve.RawPoint(from, tangent, normal, 0.0, Curve.SegmentType.LINE_START),
            Curve.RawPoint(to, tangent, normal, length, Curve.SegmentType.LINE_END)
        ))
    }
}
