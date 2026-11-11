package work.art1st.javarailways.simpleato.timetable

import org.junit.jupiter.api.function.Executable
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.AfterEach
import work.art1st.javarailways.core.physics.train.TestWorldFixture
import work.art1st.javarailways.core.physics.train.onOwnerForTest

import work.art1st.javarailways.simpleato.simpleAtoWorldData

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.BasicWorldData
import work.art1st.javarailways.basic.WayPoint
import work.art1st.javarailways.simpleato.api.ato.AtoSafetyProfile
import work.art1st.javarailways.simpleato.api.ato.AtoWorldCoordinator
import work.art1st.javarailways.simpleato.testing.KinematicTrainActuator
import work.art1st.javarailways.simpleato.api.depot.DepotId
import work.art1st.javarailways.simpleato.api.depot.DepotTrainSelector
import work.art1st.javarailways.simpleato.api.depot.RollingStockRecord
import work.art1st.javarailways.simpleato.api.depot.StablingPointRef
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
import work.art1st.javarailways.simpleato.controller.SimpleATO
import work.art1st.javarailways.simpleato.restriction.emptyInspectionRuntime
import work.art1st.javarailways.simpleato.restriction.emptyTrainRestrictionSnapshots
import work.art1st.javarailways.simpleato.testing.TestSimpleATOWorldDataRegistry
import work.art1st.javarailways.basic.basicWorldData
import work.art1st.javarailways.simpleato.timetable.*
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.createTrainWithConfig

class TimetableValidatorTest {
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
    fun `validator reports terminal open doors before a doorless origin across run segments`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        TestSimpleATOWorldDataRegistry.getOrCreate(world)
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0, 1))
        world.basicWorldData.wayPoints.add(WayPoint("A", rail.id, 10.0))
        world.basicWorldData.wayPoints.add(WayPoint("B", rail.id, 80.0))
        world.basicWorldData.wayPoints.add(WayPoint("C", rail.id, 20.0))
        val stationId = StationId("terminal")
        val lineId = LineId("door-line")
        val otherStationId = StationId("origin")
        world.simpleAtoWorldData.lines.put(Line(lineId, "Door line",
            listOf(otherStationId, stationId), listOf(stationId, otherStationId)), 0L)
        val terminal = StationStopPrograms("terminal", "106P-terminal", StationStopRole.TERMINAL,
            listOf(StationProgramAction.OpenDoors, StationProgramAction.End),
            listOf(StationProgramAction.CloseDoors, StationProgramAction.End),
            StationPreparationLead.constant(0L), null, destinationId = "B", doorSide = DoorSide.LEFT)
        val origin = StationStopPrograms("terminal", "115N-origin", StationStopRole.ORIGIN,
            listOf(StationProgramAction.End), listOf(StationProgramAction.End),
            StationPreparationLead.constant(0L), 20L, destinationId = "B", doorSide = DoorSide.NONE)
        fun timetable(closeDoors: Boolean): Timetable {
            val steps = buildList {
                add(TimetableStep.Check("A"))
                add(TimetableStep.At(0L, TimetableAction.DestinationOptions("B", stoppingOffsetMetersBefore = 10.0,
                    doorSide = DoorSide.LEFT, stopPrograms = terminal)))
                add(TimetableStep.At(0L, TimetableAction.GoTo("B")))
                add(TimetableStep.At(10L, TimetableAction.Reverse))
                if (closeDoors) add(TimetableStep.At(10L, TimetableAction.CloseDoors))
                add(TimetableStep.At(20L, TimetableAction.BeginStationStop(origin)))
                add(TimetableStep.At(20L, TimetableAction.GoTo("C")))
            }
            return Timetable("door-handoff", services = listOf(TimetableService("circulation", TrainSelector.TrainId("train"),
                steps, lineId = lineId, runSegments = listOf(
                    TimetableRunSegment("106P", lineId, 0, 4),
                    TimetableRunSegment("115N", lineId, 4, steps.size)
                ))), lineSpeedLimits = listOf(TimetableLineSpeedLimit(lineId, 20.0)))
        }

        val invalid = TimetableValidator(world).validate(timetable(closeDoors = false))
        val doorErrors = invalid.errors.filter { it.contains("车门状态冲突") }
        assertEquals(1, doorErrors.size, invalid.errors.toString())
        assertTrue(doorErrors.single().contains("[door-handoff/circulation]"))
        assertTrue(doorErrors.single().contains("step#4"))
        assertTrue(doorErrors.single().contains("DoorSide.NONE"))
        assertTrue(doorErrors.single().contains("CLOSE_DOORS"))
        assertFalse(invalid.errors.any { it.contains("step#3 车门状态冲突") },
            "reversal itself intentionally preserves the open-door fact")

        val correctedSegmented = TimetableValidator(world).validate(
            timetable(closeDoors = true))
        assertFalse(correctedSegmented.errors.any { it.contains("车门状态冲突") }, correctedSegmented.errors.toString())
        assertEquals(invalid.errors.filterNot { it.contains("车门状态冲突") },
            correctedSegmented.errors.filterNot { it.contains("车门状态冲突") })
    }

    @Test
    fun `door proof keeps inherited unknown door opening before doorless origins valid`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        TestSimpleATOWorldDataRegistry.getOrCreate(world)
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0, 1))
        world.basicWorldData.wayPoints.add(WayPoint("A", rail.id, 10.0))
        world.basicWorldData.wayPoints.add(WayPoint("B", rail.id, 80.0))
        val stationId = StationId("origin")
        OperationalDestinationService(world).register(OperationalDestination("origin@one", rail.id, 10.0,
            ViewDirection.AB, DestinationRole.StationBerthRole(stationId, berthId = "one", doorSide = DoorSide.NONE)))
        val origin = StationStopPrograms("origin", "origin-call", StationStopRole.ORIGIN,
            listOf(StationProgramAction.End), listOf(StationProgramAction.End),
            StationPreparationLead.constant(0L), 0L, destinationId = "origin@one", doorSide = DoorSide.NONE)
        fun timetable(explicitOpen: Boolean): Timetable {
            val steps = buildList {
                add(TimetableStep.Check("origin@one"))
                if (explicitOpen) add(TimetableStep.At(0L, TimetableAction.OpenDoors(DoorSide.LEFT)))
                add(TimetableStep.At(0L, TimetableAction.OpenDoors()))
                add(TimetableStep.At(0L, TimetableAction.BeginStationStop(origin)))
                add(TimetableStep.At(0L, TimetableAction.GoTo("B")))
            }
            return Timetable("unknown-door", services = listOf(TimetableService("service",
                TrainSelector.TrainId("train"), steps)))
        }
        val unknownResult = TimetableValidator(world).validate(timetable(explicitOpen = false))
        assertTrue(unknownResult.isSuccess, unknownResult.errors.toString())

        val knownOpenResult = TimetableValidator(world).validate(timetable(explicitOpen = true))
        assertEquals(1, knownOpenResult.errors.count { it.contains("车门状态冲突") },
            knownOpenResult.errors.toString())
    }

    @Test
    fun `door proof uses station program door side before the berth fallback`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        TestSimpleATOWorldDataRegistry.getOrCreate(world)
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0, 1))
        world.basicWorldData.wayPoints.add(WayPoint("B", rail.id, 80.0))
        val stationId = StationId("origin")
        OperationalDestinationService(world).register(OperationalDestination("origin@none", rail.id, 10.0,
            ViewDirection.AB, DestinationRole.StationBerthRole(stationId, berthId = "none", doorSide = DoorSide.NONE)))
        OperationalDestinationService(world).register(OperationalDestination("origin@left", rail.id, 20.0,
            ViewDirection.AB, DestinationRole.StationBerthRole(stationId, berthId = "left", doorSide = DoorSide.LEFT)))

        fun validate(destinationId: String, programSide: DoorSide): TimetableValidationResult {
            val origin = StationStopPrograms("origin", "origin-call", StationStopRole.ORIGIN,
                listOf(StationProgramAction.End),
                listOf(StationProgramAction.CloseDoors, StationProgramAction.End),
                StationPreparationLead.constant(0L), 0L, destinationId = destinationId, doorSide = programSide)
            return TimetableValidator(world).validate(Timetable("program-side", services = listOf(
                TimetableService("service", TrainSelector.TrainId("train"), listOf(
                    TimetableStep.Check(destinationId),
                    TimetableStep.At(0L, TimetableAction.OpenDoors(DoorSide.LEFT)),
                    TimetableStep.At(0L, TimetableAction.BeginStationStop(origin)),
                    TimetableStep.At(0L, TimetableAction.GoTo("B")))))))
        }

        val programOpensDoorHandling = validate("origin@none", DoorSide.LEFT)
        assertFalse(programOpensDoorHandling.errors.any { it.contains("车门状态冲突") },
            programOpensDoorHandling.errors.toString())

        val programDisablesDoorHandling = validate("origin@left", DoorSide.NONE)
        val doorErrors = programDisablesDoorHandling.errors.filter { it.contains("车门状态冲突") }
        assertEquals(1, doorErrors.size, programDisablesDoorHandling.errors.toString())
        assertTrue(doorErrors.single().contains("step#2"))
        assertTrue(doorErrors.single().contains("DoorSide.NONE"))
    }

    @Test
    fun `extension validates nested JSON against registered provider and rejects missing provider`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        TestSimpleATOWorldDataRegistry.getOrCreate(world)
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0, 1))
        world.basicWorldData.wayPoints.add(WayPoint("A", rail.id, 20.0))
        world.basicWorldData.wayPoints.add(WayPoint("B", rail.id, 50.0))
        val parameters = kotlinx.serialization.json.Json.parseToJsonElement("""{"nested":{"values":[1,true,null,"ok"]}}""") as kotlinx.serialization.json.JsonObject
        fun timetable(call: work.art1st.javarailways.simpleato.api.timetable.TimetableExtensionCall) = Timetable("extensions",
            services = listOf(TimetableService("s", TrainSelector.TrainId("T"), listOf(TimetableStep.Check("A"),
                TimetableStep.At(0L, TimetableAction.GoTo("B")), TimetableStep.At(0L, TimetableAction.Extension(call))))))
        val call = work.art1st.javarailways.simpleato.api.timetable.TimetableExtensionCall("test:nested", parameters = parameters)
        assertTrue(TimetableValidator(world).validate(timetable(call)).errors.any { it.contains("provider is not registered") })
        var observed: kotlinx.serialization.json.JsonObject? = null
        world.simpleAtoWorldData.timetableExtensions.registerAction(object : work.art1st.javarailways.simpleato.api.timetable.TimetableActionProvider {
            override val typeId = "test:nested"
            override val version = 1
            override val descriptor = work.art1st.javarailways.simpleato.api.timetable.TimetableExtensionDescriptor("Nested", "Validate nested inputs")
            override fun validate(parameters: kotlinx.serialization.json.JsonObject): List<String> {
                observed = parameters
                return if (parameters == call.parameters) emptyList() else listOf("nested values rejected")
            }
            override fun start(parameters: kotlinx.serialization.json.JsonObject,
                context: work.art1st.javarailways.simpleato.api.timetable.TimetableRuntimeContext):
                work.art1st.javarailways.simpleato.api.timetable.TimetableExtensionActionStartResult = error("validation must not execute actions")
        })
        assertTrue(TimetableValidator(world).validate(timetable(call)).isSuccess)
        assertEquals(parameters, observed)
        val interrupted = Timetable("interrupted-extension", services = listOf(TimetableService("s", steps = listOf(
            TimetableStep.At(0L, TimetableAction.TakeFromWaypoint(listOf("A"))),
            TimetableStep.At(0L, TimetableAction.DestinationOptions("B")),
            TimetableStep.At(0L, TimetableAction.Extension(call)), TimetableStep.At(0L, TimetableAction.GoTo("B"))))))
        assertTrue(TimetableValidator(world).validate(interrupted).errors.any { it.contains("step#2 destination options") })
        val invalid = call.copy(parameters = kotlinx.serialization.json.JsonObject(emptyMap()))
        assertTrue(TimetableValidator(world).validate(timetable(invalid)).errors.any { it.contains("nested values rejected") })
    }

    @Test
    fun `retains every dynamic origin leg with explicit pass offset and stable association`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        TestSimpleATOWorldDataRegistry.getOrCreate(world)
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0, 1))
        listOf("A" to 20.0, "A2" to 20.0, "via" to 50.0, "B" to 80.0).forEach { (id, arc) ->
            world.basicWorldData.wayPoints.add(WayPoint(id, rail.id, arc))
        }
        val timetable = Timetable("dynamic", services = listOf(TimetableService("s", steps = listOf(
            TimetableStep.At(0L, TimetableAction.TakeFromWaypoint(listOf("A", "A2"))),
            TimetableStep.At(1L, TimetableAction.GoVia(listOf("via", "B")))))))
        val validation = TimetableValidator(world).validate(timetable)
        assertTrue(validation.isSuccess, validation.errors.toString())
        assertEquals(4, validation.retainedRoutes.size)
        assertEquals(4, validation.routes.size)
        for (origin in listOf("A", "A2")) for (leg in listOf(0, 1)) {
            val key = ValidatedTimetableRouteKey("s", 1, origin, leg, if (leg == 0) "via" else "B", ViewDirection.AB,
                stoppingOffsetMeters = if (leg == 0) null else 0.0)
            val evidence = validation.retainedRoutes.getValue(key)
            assertEquals(if (leg == 0) null else 0.0, evidence.stoppingOffsetMeters)
            assertTrue(validation.routes.any { it === evidence.route })
            assertTrue(evidence.topology === world.topologyState)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (validation.retainedRoutes as MutableMap).clear()
        }
    }

    @Test
    fun `relative wait before first movement requires acquisition and cannot interrupt destination options`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        TestSimpleATOWorldDataRegistry.getOrCreate(world)
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0, 1))
        world.basicWorldData.wayPoints.add(WayPoint("A", rail.id, 20.0))
        world.basicWorldData.wayPoints.add(WayPoint("B", rail.id, 50.0))
        val take = TimetableStep.At(0L, TimetableAction.TakeFromWaypoint(listOf("A")))
        val wait = TimetableStep.At(0L, TimetableAction.Stop(5L))
        val move = TimetableStep.At(0L, TimetableAction.GoTo("B"))
        fun validate(steps: List<TimetableStep>) = TimetableValidator(world).validate(
            Timetable("relative", services = listOf(TimetableService("s", steps = steps))))
        assertTrue(validate(listOf(take, wait, move)).isSuccess)
        assertTrue(validate(listOf(wait, move)).errors.any { it.contains("stop 前必须完成取车") })
        assertTrue(validate(listOf(take, TimetableStep.At(0L, TimetableAction.DestinationOptions("B")),
            wait, move)).errors.any { it.contains("step#2 destination options") })
    }

    @Test
    fun `waitUntil requires positioned acquisition and cannot interrupt destination options`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        TestSimpleATOWorldDataRegistry.getOrCreate(world)
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0, 1))
        world.basicWorldData.wayPoints.add(WayPoint("A", rail.id, 20.0))
        world.basicWorldData.wayPoints.add(WayPoint("B", rail.id, 50.0))
        fun validate(steps: List<TimetableStep>) = TimetableValidator(world).validate(
            Timetable("wait", services = listOf(TimetableService("s", TrainSelector.TrainId("T"), steps))))
        assertTrue(validate(listOf(TimetableStep.Check("A"), TimetableStep.At(5L, TimetableAction.WaitUntil), TimetableStep.At(5L, TimetableAction.GoTo("B")))).isSuccess)
        assertTrue(validate(listOf(TimetableStep.At(5L, TimetableAction.WaitUntil))).errors.any { it.contains("waitUntil") })
        val interrupted = validate(listOf(TimetableStep.Check("A"),
            TimetableStep.At(0L, TimetableAction.DestinationOptions("A")), TimetableStep.At(5L, TimetableAction.WaitUntil)))
        assertTrue(interrupted.errors.any { it.contains("step#2 destination options") })
    }

    @Test
    fun `cross depot storage retains target routes and terminal acquisition restrictions`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        TestSimpleATOWorldDataRegistry.getOrCreate(world)
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0, 1))
        val destinations = OperationalDestinationService(world)
        listOf("A" to 20.0, "B" to 70.0).forEach { (id, arc) ->
            destinations.register(OperationalDestination("$id@one", rail.id, arc, ViewDirection.AB,
                DestinationRole.DepotStablingPointRole(id, "one", usableLengthMeters = 20.0)))
        }
        world.simpleAtoWorldData.depots.registerRollingStock(
            RollingStockRecord("T", DepotId("A"), "test/emu", emptySet(), 1, 10.0, 20.0, emptySet()),
            StablingPointRef(DepotId("A"), "one"))
        val take = TimetableStep.At(0L, TimetableAction.TakeFromDepot(DepotTrainSelector(DepotId("A"))))
        val store = TimetableStep.At(1L, TimetableAction.StoreAtDepot(DepotId("B")))
        fun validate(steps: List<TimetableStep>) = TimetableValidator(world).validate(
            Timetable("cross", services = listOf(TimetableService("s", steps = steps))))
        world.basicWorldData.wayPoints.add(WayPoint("via", rail.id, 40.0))
        val move = TimetableStep.At(0L, TimetableAction.GoTo("via"))
        val result = validate(listOf(take, move, store))
        assertTrue(result.isSuccess, result.errors.toString())
        val conditional = Timetable("conditional", services = listOf(TimetableService("s",
            steps = listOf(take, move, store), activation =
                work.art1st.javarailways.simpleato.api.timetable.TimetableServiceActivation.Conditional(
                    work.art1st.javarailways.simpleato.api.timetable.TimetableCondition.Predicate(
                        work.art1st.javarailways.simpleato.api.timetable.TimetableExtensionCall("test:condition")),
                    work.art1st.javarailways.simpleato.api.timetable.TimetableActivationFalseBehavior.WAIT_UNTIL_TRUE))))
        assertTrue(TimetableValidator(world).validate(conditional).errors.any {
            it.contains("runtime service activation conditions are not editable-plan deterministic") })
        assertEquals(listOf(20.0, 30.0), result.routes.map { it.totalLength })
        assertTrue(validate(listOf(store)).errors.any { it.contains("有且仅有一次取车") })
        assertTrue(validate(listOf(take, store, TimetableStep.At(2L, TimetableAction.WaitUntil))).errors.any { it.contains("最后一步") })
        assertTrue(validate(listOf(take, TimetableStep.At(1L, TimetableAction.StoreAtDepot(DepotId("B"), setOf("missing"))))).errors.any { it.contains("StablingPoint") })
    }

    @Test
    fun `endpoint anchor retains existing track when actual destination stop has enough margin`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        TestSimpleATOWorldDataRegistry.getOrCreate(world)
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0, 1))
        world.basicWorldData.wayPoints.add(WayPoint("origin", rail.id, 10.0))
        world.basicWorldData.wayPoints.add(WayPoint("end", rail.id, 100.0))
        val timetable = Timetable("offset", services = listOf(TimetableService("s", TrainSelector.TrainId("train"), listOf(
            TimetableStep.Check("origin"),
            TimetableStep.At(0L, TimetableAction.DestinationOptions("end", stoppingOffsetMetersBefore = 10.0)),
            TimetableStep.At(0L, TimetableAction.GoTo("end"))
        ))))
        world.simpleAtoWorldData.timetables.add(timetable)
        val validation = TimetableValidator(world).validate(timetable)
        assertTrue(validation.isSuccess, validation.errors.toString())
        assertEquals(listOf(10.0), validation.routeStoppingOffsetsMeters)
        assertEquals(100.0, validation.routes.single().segments.last().endArcLength)
        val dispatcher = TimetableDispatcher(UnavailableRouteAdmissionTestContext, emptyTrainRestrictionSnapshots,
            emptyInspectionRuntime(), acceptingInfrastructureDependencyService(), authorityStoppingMarginMeters = 5.0)
        assertTrue(world.onOwnerForTest { dispatcher.beginTimetable(world, timetable.id) } is TimetableEpochStartResult.Started)
    }

    @Test
    fun `same physical anchor retains separate station and ordinary stop margin proofs`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        TestSimpleATOWorldDataRegistry.getOrCreate(world)
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0, 1))
        world.basicWorldData.wayPoints.add(WayPoint("origin", rail.id, 10.0))
        world.basicWorldData.wayPoints.add(WayPoint("anchor", rail.id, 99.5))
        OperationalDestinationService(world).register(OperationalDestination("central@one", rail.id, 99.5,
            ViewDirection.AB, DestinationRole.StationBerthRole(StationId("central"), berthId = "one",
                doorSide = DoorSide.LEFT)))
        val station = TimetableService("station", TrainSelector.TrainId("station-train"), listOf(
            TimetableStep.Check("origin"), TimetableStep.At(0L, TimetableAction.CallStation("central", "one")),
            TimetableStep.At(0L, TimetableAction.GoTo("anchor"))))
        val ordinary = TimetableService("ordinary", TrainSelector.TrainId("ordinary-train"), listOf(
            TimetableStep.Check("origin"), TimetableStep.At(0L, TimetableAction.DestinationOptions("anchor", stoppingOffsetMetersBefore = 10.0)),
            TimetableStep.At(0L, TimetableAction.GoTo("anchor"))))
        val timetable = Timetable("mixed-margins", services = listOf(station, ordinary))
        world.simpleAtoWorldData.timetables.add(timetable)
        val validation = TimetableValidator(world).validate(timetable)
        assertTrue(validation.isSuccess, validation.errors.toString())
        assertEquals(listOf(0.0, 10.0), validation.routeStoppingOffsetsMeters)
        assertEquals(listOf(true, false), validation.retainedRoutes.values.map { it.terminalStationStop })
        val dispatcher = TimetableDispatcher(UnavailableRouteAdmissionTestContext, emptyTrainRestrictionSnapshots,
            emptyInspectionRuntime(), acceptingInfrastructureDependencyService(), authorityStoppingMarginMeters = 5.0)
        assertTrue(world.onOwnerForTest { dispatcher.beginTimetable(world, timetable.id) } is TimetableEpochStartResult.Started,
            "station uses real0.3m suffix while the ordinary10m offset fits its conservative5m margin")
    }

    @Test
    fun `validation distinguishes pass through legs and preserves station effective stopping offset`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        TestSimpleATOWorldDataRegistry.getOrCreate(world)
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0, 1))
        world.basicWorldData.wayPoints.add(WayPoint("origin", rail.id, 10.0))
        world.basicWorldData.wayPoints.add(WayPoint("via", rail.id, 60.0))
        OperationalDestinationService(world).register(OperationalDestination("central@one", rail.id, 100.0,
            ViewDirection.AB, DestinationRole.StationBerthRole(StationId("central"), berthId = "one",
                doorSide = DoorSide.LEFT, stoppingOffsetMetersBefore = 10.0)))
        fun timetable(offset: Double?) = Timetable("station-offset", services = listOf(TimetableService("s",
            TrainSelector.TrainId("train"), listOf(TimetableStep.Check("origin"),
                TimetableStep.At(0L, TimetableAction.CallStation("central", "one", stoppingOffsetMetersBefore = offset)),
                TimetableStep.At(0L, TimetableAction.GoVia(listOf("via", "central@one")))))))
        listOf(null to 10.0, 12.0 to 12.0).forEach { (override, effective) ->
            val result = TimetableValidator(world).validate(timetable(override))
            assertTrue(result.isSuccess, result.errors.toString())
            assertEquals(listOf(null, effective), result.routeStoppingOffsetsMeters)
            assertEquals(listOf(false, true), result.routes.map { route -> result.retainedRoutes.values.single { it.route === route }.terminalStationStop })
            assertEquals(listOf(50.0, 40.0), result.routes.map { it.totalLength })
        }
        world.basicWorldData.wayPoints.add(WayPoint("beyond", rail.id, 100.0))
        OperationalDestinationService(world).register(OperationalDestination("interior@one", rail.id, 80.0,
            ViewDirection.AB, DestinationRole.StationBerthRole(StationId("interior"), berthId = "one",
                doorSide = DoorSide.LEFT, stoppingOffsetMetersBefore = 10.0)))
        val interior = Timetable("interior-call", services = listOf(TimetableService("s", TrainSelector.TrainId("train"), listOf(
            TimetableStep.Check("origin"),
            TimetableStep.At(0L, TimetableAction.CallStation("interior", "one")),
            TimetableStep.At(0L, TimetableAction.GoVia(listOf("via", "interior@one", "beyond")))
        ))))
        val interiorResult = TimetableValidator(world).validate(interior)
        assertTrue(interiorResult.isSuccess, interiorResult.errors.toString())
        assertEquals(listOf(null, null, 0.0), interiorResult.routeStoppingOffsetsMeters,
            "an interior station does not replace the final GoTo stopping target")
    }

    @Test
    fun `validation route offsets are aligned immutable and retained on invalid timetable`() {
        assertThrows(IllegalArgumentException::class.java) {
            TimetableValidationResult(emptyList(), emptyList(), listOf(1.0))
        }
        val offsets = mutableListOf<Double?>()
        val result = TimetableValidationResult(listOf("invalid"), emptyList(), offsets)
        offsets.add(1.0)
        assertFalse(result.isSuccess)
        assertTrue(result.routeStoppingOffsetsMeters.isEmpty())
        assertThrows(UnsupportedOperationException::class.java) {
            (result.routeStoppingOffsetsMeters as MutableList<Double?>).add(1.0)
        }
    }

    @Test
    fun `validator rejects unknown infrastructure lines and missing line maximum speeds`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        TestSimpleATOWorldDataRegistry.getOrCreate(world)
        val known = LineId("known")
        world.simpleAtoWorldData.lines.put(
            Line(known, "Known", listOf(StationId("a"), StationId("b")), listOf(StationId("b"), StationId("a"))),
            0L
        )
        val timetable = Timetable("line-validation", services = listOf(
            TimetableService("known-service", lineId = known),
            TimetableService("unknown-service", lineId = LineId("unknown"))
        ))

        val errors = TimetableValidator(world).validate(timetable).errors

        assertTrue(errors.any { it.contains("未配置线路最高速度: known") })
        assertTrue(errors.any { it.contains("引用不存在的 LineId: unknown") })
    }

    @Test
    fun `validator expands route between checked origin and goto target`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        TestSimpleATOWorldDataRegistry.getOrCreate(world)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        world.basicWorldData.wayPoints.add(WayPoint("A", rail.id, 10.0))
        world.basicWorldData.wayPoints.add(WayPoint("B", rail.id, 70.0))

        val timetable = Timetable(
            id = "demo",
            services = listOf(
                TimetableService(
                    id = "001",
                    trainSelector = TrainSelector.TrainId("T1"),
                    steps = listOf(
                        TimetableStep.Check("A"),
                        TimetableStep.At(0, TimetableAction.GoTo("B"))
                    )
                )
            )
        )

        val result = TimetableValidator(world).validate(timetable)

        assertTrue(result.isSuccess, result.errors.joinToString())
        assertEquals(1, result.routes.size)
        assertEquals(60.0, result.routes.single().totalLength, 1e-6)
        assertEquals(ViewDirection.AB, result.routes.single().segments.single().railViewRef.direction)
    }

    @Test
    fun `validator rejects selectors and origins the dispatcher cannot execute`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        TestSimpleATOWorldDataRegistry.getOrCreate(world)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        world.basicWorldData.wayPoints.add(WayPoint("A", rail.id, 10.0))
        world.basicWorldData.wayPoints.add(WayPoint("B", rail.id, 70.0))
        val services = listOf(
            TimetableService(
                "any-without-depot",
                steps = listOf(TimetableStep.At(0L, TimetableAction.GoTo("B")))
            ),
            TimetableService(
                "explicit-without-check",
                TrainSelector.TrainId("T1"),
                listOf(TimetableStep.At(0L, TimetableAction.GoTo("B")))
            ),
            TimetableService(
                "stop-before-first-movement",
                TrainSelector.TrainId("T2"),
                listOf(
                    TimetableStep.Check("A"),
                    TimetableStep.At(0L, TimetableAction.Stop(20L)),
                    TimetableStep.At(20L, TimetableAction.GoTo("B"))
                )
            )
        )

        val result = TimetableValidator(world).validate(Timetable("invalid-selectors", services = services))

        assertFalse(result.isSuccess)
        assertTrue(result.errors.any { it.contains("takeFromDepot") })
        assertTrue(result.errors.any { it.contains("初始", ignoreCase = true) })
        assertTrue(result.errors.any { it.contains("stop 前必须完成取车或 destination") })
    }

    @Test
    fun `dynamic selector validates only compatible depot origins`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        val data = BasicWorldData()
        TestSimpleATOWorldDataRegistry.register(world, data)
        val metroRail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(120.0, segmentCount = 1)
        )
        val freightRail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(120.0, segmentCount = 1)
        )
        fun depotTrain(id: String, rail: work.art1st.javarailways.core.physics.rail.CoreRail) = world.onOwnerForTest {
            val head = work.art1st.javarailways.core.physics.rail.RailLocationWithFacing(rail.viewAB, 20.0)
            val car = work.art1st.javarailways.core.physics.train.CoreCar.create("$id-car",
                work.art1st.javarailways.core.physics.train.CoreBogie.create(head),
                work.art1st.javarailways.core.physics.train.CoreBogie.create(head),
                length = 10.0, floorHeight = 0.0, connectorLengthAtA = 0.0, connectorLengthAtB = 0.0,
                bogieAOffset = 5.0, bogieBOffset = -5.0, world = world)
            work.art1st.javarailways.core.physics.train.CoreTrain.create(id, listOf(car), world).also {
                it.setLocation(head)
                it.commitMaintenancePlacement()
            }
        }
        val metroTrain = depotTrain("metro", metroRail)
        val freightTrain = depotTrain("freight", freightRail)
        val depotId = DepotId("north")
        val destinations = OperationalDestinationService(world)
        destinations.register(
            OperationalDestination(
                "north@metro",
                metroRail.id,
                metroTrain.head.location.arcLength,
                ViewDirection.AB,
                DestinationRole.DepotStablingPointRole(
                    "north",
                    "metro",
                    allowedTags = setOf("metro")
                )
            )
        )
        destinations.register(
            OperationalDestination(
                "north@freight",
                freightRail.id,
                freightTrain.head.location.arcLength,
                ViewDirection.AB,
                DestinationRole.DepotStablingPointRole(
                    "north",
                    "freight",
                    allowedTags = setOf("freight")
                )
            )
        )
        data.wayPoints.add(WayPoint("B", metroRail.id, 90.0))
        world.simpleAtoWorldData.depots.registerRollingStock(
            RollingStockRecord(
                metroTrain.id,
                depotId,
                "test/metro",
                setOf("metro"),
                1,
                10.0,
                20.0,
                emptySet()
            ),
            StablingPointRef(depotId, "metro")
        )
        world.simpleAtoWorldData.depots.registerRollingStock(
            RollingStockRecord(
                freightTrain.id,
                depotId,
                "test/freight",
                setOf("freight"),
                1,
                10.0,
                20.0,
                emptySet()
            ),
            StablingPointRef(depotId, "freight")
        )
        val timetable = Timetable(
            "metro-only",
            services = listOf(
                TimetableService(
                    "service",
                    steps = listOf(
                        TimetableStep.At(
                            500L,
                            TimetableAction.TakeFromDepot(
                                DepotTrainSelector(depotId, requiredTags = setOf("metro"))
                            )
                        ),
                        TimetableStep.At(600L, TimetableAction.GoTo("B"))
                    )
                )
            )
        )

        val result = TimetableValidator(world).validate(timetable)

        assertTrue(result.isSuccess, result.errors.joinToString())
    }

    @Test
    fun `validator rejects actions outside the declared logical game days`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        TestSimpleATOWorldDataRegistry.getOrCreate(world)
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0))
        world.basicWorldData.wayPoints.add(WayPoint("A", rail.id, 10.0))
        world.basicWorldData.wayPoints.add(WayPoint("B", rail.id, 70.0))
        val timetable = Timetable(
            id = "boundary",
            periodDays = 1L,
            services = listOf(
                TimetableService(
                    id = "001",
                    trainSelector = TrainSelector.TrainId("T1"),
                    steps = listOf(
                        TimetableStep.Check("A"),
                        TimetableStep.At(24_000L, TimetableAction.GoTo("B"))
                    )
                )
            )
        )

        val result = TimetableValidator(world).validate(timetable)

        assertFalse(result.isSuccess)
        assertTrue(result.errors.any { it.contains("超出周期") })
    }

    @Test
    fun `validator reports missing waypoint`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        TestSimpleATOWorldDataRegistry.getOrCreate(world)
        val timetable = Timetable(
            id = "demo",
            services = listOf(
                TimetableService(
                    id = "001",
                    steps = listOf(TimetableStep.At(0, TimetableAction.GoTo("missing")))
                )
            )
        )

        val result = TimetableValidator(world).validate(timetable)

        assertFalse(result.isSuccess)
        assertTrue(result.errors.any { it.contains("missing") })
    }

    @Test
    fun `invalid station approach is rejected before a service run is installed`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        val profile = AtoSafetyProfile(
            accelerationMetersPerSecondSquared = 1.0,
            serviceDecelerationMetersPerSecondSquared = 1.0,
            reactionTimeSeconds = 0.0,
            safetyDistanceMeters = 1.0
        )
        val inspectionRuntime = emptyInspectionRuntime()
        val dispatcher = TimetableDispatcher(
            authorityStoppingMarginMeters = profile.safetyDistanceMeters,
            routeAdmissionContextProvider = GrantingRouteAdmissionTestContext { world },
            signalRestrictionSnapshots = emptyTrainRestrictionSnapshots,
            inspectionRuntime = inspectionRuntime,
            infrastructureDependencyService = acceptingInfrastructureDependencyService(),
            atoFactory = TimetableAtoFactory { train, owningWorld ->
                SimpleATO(
                    train,
                    owningWorld,
                    KinematicTrainActuator(8.0, profile),
                    profile,
                    inspectionRuntime::maximumDeclaredLeadTimeSeconds
                )
            }
        )
        val coordinator = AtoWorldCoordinator("validator-station", reportSink = {})
        val data = BasicWorldData(safetyInterlock = coordinator.safetyInterlock)
        TestSimpleATOWorldDataRegistry.register(world, data, coordinator)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(120.0, segmentCount = 1)
        )
        val train = createTrainWithConfig("T1", rail, world, listOf(10.0))
        val originArc = train.head.location.arcLength
        data.wayPoints.add(WayPoint("A", rail.id, originArc))
        data.wayPoints.add(WayPoint("B", rail.id, originArc + 80.0))
        val stationId = StationId("central")
        val berthRef = StationBerthRef(stationId, "wrong-way")
        world.simpleAtoWorldData.stations.register(
            Station(stationId, "Central", listOf(berthRef)),
            listOf(
                StationBerth(
                    berthRef = berthRef,
                    location = train.head.location.forward(40.0)!!,
                    approachDirection = ViewDirection.BA,
                    usableLengthMeters = 100.0,
                    doorSide = DoorSide.LEFT
                )
            )
        )
        val timetable = Timetable(
            id = "station-invalid",
            services = listOf(
                TimetableService(
                    id = "service",
                    trainSelector = TrainSelector.TrainId(train.id),
                    steps = listOf(
                        TimetableStep.Check("A"),
                        TimetableStep.At(
                            0L,
                            TimetableAction.CallStation("central", "wrong-way")
                        ),
                        TimetableStep.At(0L, TimetableAction.GoTo("B"))
                    )
                )
            )
        )
        world.simpleAtoWorldData.timetables.add(timetable)

        val validation = TimetableValidator(world).validate(timetable)
        val start = world.onOwnerForTest { dispatcher.startService(world, timetable.id, "service") }

        assertFalse(validation.isSuccess)
        assertTrue(validation.errors.any { it.contains("车站 destination", ignoreCase = true) })
        assertTrue(start is TimetableServiceStartResult.Failure)
        assertFalse(coordinator.isStalled)
        assertEquals(null, dispatcher.status(TimetableServiceRunId(timetable.id, "service")))
        assertEquals(null, train.controller)
    }
}
