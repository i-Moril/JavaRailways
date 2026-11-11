package work.art1st.javarailways.simpleato.timetable

import kotlinx.serialization.json.JsonObject
import work.art1st.javarailways.simpleato.api.timetable.*
import org.junit.jupiter.api.function.Executable
import org.junit.jupiter.api.Assertions.assertAll
import work.art1st.javarailways.core.physics.train.TestWorldFixture
import work.art1st.javarailways.core.util.IsolatedSimulationThreadMarker

import work.art1st.javarailways.core.physics.train.onOwnerForTest

import work.art1st.javarailways.simpleato.simpleAtoWorldData

import work.art1st.javarailways.core.physics.createRailForTest

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.basicWorldData
import work.art1st.javarailways.basic.BasicWorldData
import work.art1st.javarailways.basic.WayPoint
import work.art1st.javarailways.simpleato.api.ato.AtoSafetyProfile
import work.art1st.javarailways.simpleato.testing.KinematicTrainActuator
import work.art1st.javarailways.basic.api.operation.TrainOperation
import work.art1st.javarailways.basic.api.operation.TrainOperationExecutionResult
import work.art1st.javarailways.basic.api.operation.TrainOperationGateway
import work.art1st.javarailways.basic.api.operation.TrainOperationRequest
import work.art1st.javarailways.basic.api.operation.TrainOperationTicket
import work.art1st.javarailways.basic.api.operation.TrainOperationTicketSnapshot
import work.art1st.javarailways.basic.api.operation.TrainOperationTicketState
import work.art1st.javarailways.basic.api.operation.DoorSide
import work.art1st.javarailways.simpleato.api.station.DwellPlan
import work.art1st.javarailways.simpleato.api.station.Station
import work.art1st.javarailways.simpleato.api.station.StationBerth
import work.art1st.javarailways.simpleato.api.station.StationBerthRef
import work.art1st.javarailways.simpleato.api.station.StationCall
import work.art1st.javarailways.simpleato.api.station.StationId
import work.art1st.javarailways.simpleato.controller.SimpleATO
import work.art1st.javarailways.simpleato.restriction.emptyTrainRestrictionSnapshots
import work.art1st.javarailways.simpleato.restriction.emptyInspectionRuntime
import work.art1st.javarailways.simpleato.testing.TestSimpleATOWorldDataRegistry
import work.art1st.javarailways.simpleato.timetable.*
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.CoreTrain
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.createTrainWithConfig
import work.art1st.javarailways.core.util.AsyncTickTestSupport
import work.art1st.javarailways.core.api.threading.host.ThreadGuardHost
import work.art1st.javarailways.core.api.threading.host.ThreadGuardPlatformBridge
import work.art1st.javarailways.core.api.threading.host.ThreadGuardRegistration

class TimetableStationCallDispatcherTest {
    private val worldFixtures = mutableListOf<TestWorldFixture>()

    @Test fun `display progress captures exact origin arrival and departure despite prepared future missions`() =
        assertDisplayProgressDoorSide(DoorSide.NONE,DoorSide.NONE)

    @Test fun `ordinary train run displays capture actual progress through its default operational trip`() =
        assertDisplayProgressDoorSide(DoorSide.NONE, DoorSide.NONE, explicitUnitTrip = false)

    @Test fun `explicit OPEN_DOORS override is captured instead of inherited berth side`() =
        assertDisplayProgressDoorSide(DoorSide.LEFT,DoorSide.RIGHT,
            initialActions=listOf(TimetableAction.OpenDoors(DoorSide.RIGHT),TimetableAction.CloseDoors))

    @Test fun `origin station program override is captured instead of berth side`() =
        assertDisplayProgressDoorSide(DoorSide.LEFT,DoorSide.RIGHT,originPrograms=StationStopPrograms(
            "origin","origin-program",StationStopRole.ORIGIN,
            listOf(StationProgramAction.OpenDoors,StationProgramAction.End),
            listOf(StationProgramAction.CloseDoors,StationProgramAction.End),
            StationPreparationLead.constant(40L),100L,"origin@one",DoorSide.RIGHT))

    private fun assertDisplayProgressDoorSide(originSide: DoorSide, expectedOriginSide: DoorSide,
        initialActions: List<TimetableAction> = emptyList(), originPrograms: StationStopPrograms? = null,
        explicitUnitTrip: Boolean = true) {
        val gateway = ControllableOperationGateway()
        val f = fixture(firstTargetOffsetMeters = 40.0, nextTargetOffsetMeters = 100.0,
            canonicalOriginDoorSide = originSide, canonicalTarget = true,
            stationMovementTick = 100L, nextMovementTick = 1_000L,
            initialActions=initialActions,originPrograms=originPrograms,operationGateway=gateway)
        val world = f.world
        world.onOwnerForTest {
            val end = checkNotNull(f.data.wayPoints["D"])
            work.art1st.javarailways.simpleato.api.destination.OperationalDestinationService(world).register(
                work.art1st.javarailways.simpleato.api.destination.OperationalDestination("end@one",end.railId,
                    end.arcLength,ViewDirection.AB,
                    work.art1st.javarailways.simpleato.api.destination.DestinationRole.StationBerthRole(
                        StationId("end"),berthId="one",doorSide=DoorSide.NONE)))
            val lineId = work.art1st.javarailways.simpleato.api.line.LineId("progress-line")
            val stations = listOf(StationId("origin"),StationId("central"),StationId("end"))
            world.simpleAtoWorldData.lines.put(work.art1st.javarailways.simpleato.api.line.Line(
                lineId,"Progress",stations,stations.reversed()),world.simpleAtoWorldData.lines.snapshot().revision)
            val table = checkNotNull(world.simpleAtoWorldData.timetables["tt"])
            val source = table.services.single()
            val steps = source.steps.dropLast(1) + TimetableStep.At(1_000L,TimetableAction.GoTo("end@one"))
            val segment = TimetableRunSegment("run",lineId,0,steps.size,calls=listOf(
                TimetableRunCall("origin-call","origin","origin@one",TimetableServiceStopStatus.ORIGIN),
                TimetableRunCall("central-call","central","central@one",TimetableServiceStopStatus.STOP),
                TimetableRunCall("end-call","end","end@one",TimetableServiceStopStatus.TERMINAL)))
            world.simpleAtoWorldData.timetables.add(table.copy(services=listOf(source.copy(steps=steps,runSegments=listOf(segment))),
                lineSpeedLimits=listOf(TimetableLineSpeedLimit(lineId,8.0)),
                operationalTrips=if (explicitUnitTrip) listOf(TimetableOperationalTrip("trip",segments=listOf(
                    TimetableOperationalTripSegment("service","run",listOf(1))))) else emptyList()))
            assertNull(f.dispatcher.captureUnitDisplays(world).single().currentRun, "No entered run exists before service start")
            val result = f.dispatcher.startService(world,"tt","service")
            assertTrue(result is TimetableServiceStartResult.Started,result.toString())
            val origin = f.dispatcher.captureUnitDisplays(world).single()
            assertEquals("end", origin.currentRun!!.terminalStationId)
            assertEquals(listOf("origin-call", "central-call", "end-call"), origin.display!!.plannedStops.map { it.call.id })
            assertEquals(f.train.publishedFrame.cars.map { it.carId }, origin.carIds)
            if (explicitUnitTrip) assertEquals("trip", origin.display.operationalTripId)
            else {
                assertEquals(f.train.composition.units.map { it.unitId }, world.simpleAtoWorldData.depots.allFixedUnits().map { it.id })
                val default = world.simpleAtoWorldData.timetables["tt"]!!.operationalTrips.single { it.segments.single().unassignedUnits }
                assertEquals(default.id, origin.display.operationalTripId)
            }
            assertEquals(TimetableDisplayProgress(TimetableDisplayPhase.AT_STATION,0),origin.progress)
            assertEquals(expectedOriginSide,origin.doorSide)
            if (initialActions.isEmpty() && originPrograms == null)
                assertTrue(checkNotNull(f.dispatcher.status(TimetableServiceRunId("tt","service"))).missionRevision > 0,
                    "future mission has already been submitted at origin; controller consumes it on a later tick")
        }
        var departedOrigin = false
        var arrivedCentralWithFutureMission = false
        var departedCentral = false
        repeat(1_500) {
            if (departedCentral) return@repeat
            gateway.tickets.toList().forEach { it.succeed() }
            f.tick()
            world.onOwnerForTest {
                val context = f.dispatcher.captureUnitDisplays(world).single()
                if (context.progress == TimetableDisplayProgress(TimetableDisplayPhase.AT_STATION,0))
                    assertEquals(expectedOriginSide,context.doorSide)
                if (context.progress == TimetableDisplayProgress(TimetableDisplayPhase.IN_TRANSIT,1)) {
                    departedOrigin = true
                    assertNull(context.doorSide,"actual departure clears the origin's effective opening side")
                }
                if (context.progress == TimetableDisplayProgress(TimetableDisplayPhase.AT_STATION,1)) {
                    assertEquals(0.0,f.train.publishedFrame.speedMetersPerSecond)
                    assertEquals(DoorSide.NONE,context.doorSide)
                    val target = (f.train.controller as SimpleATO).currentMission?.legs?.lastOrNull()?.point?.target?.id
                    if (target == "end@one") arrivedCentralWithFutureMission = true
                }
                if (context.progress == TimetableDisplayProgress(TimetableDisplayPhase.IN_TRANSIT,2)) departedCentral = true
            }
        }
        assertTrue(departedOrigin,"real movement establishes en-route progress")
        assertTrue(arrivedCentralWithFutureMission,"preparing the next mission does not erase arrived occurrence")
        assertTrue(departedCentral,"only physical departure advances the visible station cursor")
        world.onOwnerForTest {
            assertTrue(f.dispatcher.stopService(world, TimetableServiceRunId("tt", "service")))
        }
        repeat(1_000) {
            if (f.dispatcher.status(TimetableServiceRunId("tt", "service"))?.state != TimetableServiceRunState.STOPPED) f.tick()
        }
        world.onOwnerForTest {
            assertEquals(TimetableServiceRunState.STOPPED, f.dispatcher.status(TimetableServiceRunId("tt", "service"))?.state)
            assertNull(f.dispatcher.captureUnitDisplays(world).single().currentRun, "Stopped run withdraws actual RUN context")
        }
    }

    @Test fun `owner batch resolves registered current berth proof then removes it on actual departure movement`() {
        val gateway = ControllableOperationGateway()
        // First Mission submission deliberately clears confirmedDoorStop even before scheduled
        // departure. Observe the real CHECK proof while an actual door ticket still holds it.
        val fixture = fixture(canonicalOriginDoorSide = DoorSide.LEFT, operationGateway = gateway,
            initialActions = listOf(TimetableAction.OpenDoors(), TimetableAction.CloseDoors))
        val world = fixture.world
        world.onOwnerForTest {
            val carIds = fixture.train.publishedFrame.cars.map { it.carId }
            assertEquals(fixture.train.composition.units.single().unitId,
                fixture.dispatcher.captureUnitDisplays(world).single().unitId)
            val contextless = fixture.dispatcher.captureUnitDisplays(world).single()
            assertEquals(carIds, contextless.carIds)
            assertNull(contextless.display); assertNull(contextless.timetableId); assertNull(contextless.currentBerth)
            val started = fixture.dispatcher.startService(world,"tt","service")
            assertTrue(started is TimetableServiceStartResult.Started, started.toString())
        }
        world.onOwnerForTest {
            val stopped = fixture.dispatcher.captureUnitDisplays(world).single()
            assertEquals(StationBerthRef(StationId("origin"),"one"),stopped.currentBerth,
                fixture.dispatcher.status(TimetableServiceRunId("tt","service")).toString())
            assertNull(stopped.display, "physical berth proof does not invent an operational trip")
            assertEquals(TrainOperation.OpenDoors(DoorSide.LEFT), gateway.requests.single().operation)
        }
        for (tick in 0 until 1000) {
            gateway.tickets.toList().forEach { it.succeed() }
            fixture.tick()
            if (fixture.train.publishedFrame.speedMetersPerSecond > 0.0) break
        }
        assertTrue(fixture.train.publishedFrame.speedMetersPerSecond > 0.0, "real controller must have departed the origin")
        world.onOwnerForTest {
            val moved = fixture.dispatcher.captureUnitDisplays(world).single()
            assertEquals(fixture.train.composition.units.single().unitId,moved.unitId)
            assertNull(moved.currentBerth, "previous confirmed berth must not survive movement")
        }
    }

    @Test fun `global reset releases a stopped train occupied route without tail clearance`() {
        val fixture = fixture(firstTargetOffsetMeters = 100.0)
        val world = fixture.world
        val id = TimetableServiceRunId("tt", "service")
        world.onOwnerForTest { fixture.dispatcher.startService(world, "tt", "service") }
        for (tick in 0 until 300) {
            fixture.tick()
            if (fixture.dispatcher.status(id)?.routeGrantActive == true) break
        }
        assertTrue(fixture.dispatcher.status(id)!!.routeGrantActive)
        world.onOwnerForTest { fixture.dispatcher.stopService(world, id) }
        for (tick in 0 until 1000) {
            fixture.tick()
            if (fixture.dispatcher.status(id)?.state == TimetableServiceRunState.STOPPED) break
        }
        assertEquals(TimetableServiceRunState.STOPPED, fixture.dispatcher.status(id)?.state)
        assertEquals(1, world.onOwnerForTest { fixture.dispatcher.forceDiscardTimetables(world, setOf("tt"), true) })
        assertNull(fixture.dispatcher.status(id))
        assertNull(fixture.train.controller)
    }

    @Test fun `global reset releases a stopped nonterminal run without waiting for logical completion`() {
        val fixture = fixture(
            includeStationAction = true,
            stopPrograms = programs(
                StationStopRole.STOP,
                arrival = listOf(StationProgramAction.WaitDuration(1_000), StationProgramAction.End)
            )
        )
        val world = fixture.world
        val id = TimetableServiceRunId("tt", "service")
        world.onOwnerForTest { fixture.dispatcher.startService(world, "tt", "service") }
        for (tick in 0 until 2_000) {
            fixture.tick()
            val status = fixture.dispatcher.status(id) ?: break
            if (status.routeGrantActive && status.state == TimetableServiceRunState.DWELLING &&
                fixture.train.speed == 0.0
            ) break
        }
        val status = fixture.dispatcher.status(id)!!
        assertEquals(TimetableServiceRunState.DWELLING, status.state)
        assertTrue(status.routeGrantActive)
        assertEquals(0.0, fixture.train.speed, 1.0e-9)

        assertEquals(1, world.onOwnerForTest {
            fixture.dispatcher.forceDiscardTimetables(world, setOf("tt"), true)
        })
        assertNull(fixture.dispatcher.status(id))
        assertNull(fixture.train.controller)
    }

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

    @OptIn(work.art1st.javarailways.core.api.util.plugin.ExtensionData::class)
    private fun programs(role: StationStopRole, departure: Long? = 1000L,
        arrival: List<StationProgramAction> = listOf(StationProgramAction.OpenDoors, StationProgramAction.End),
        preparation: List<StationProgramAction> = listOf(StationProgramAction.CloseDoors, StationProgramAction.End),
        lead: Long = 40L, destination: String? = "C", minimumDwellTicks: Long? = null) = StationStopPrograms("central","stop-call",role,
            arrival,preparation,StationPreparationLead.constant(lead),departure,destination, minimumDwellTicks = minimumDwellTicks)

    @Test fun `ordinary door actions explicitly override at a normal waypoint and await real tickets`() {
        val gateway = ControllableOperationGateway()
        val fixture = fixture(operationGateway = gateway,
            initialActions = listOf(TimetableAction.OpenDoors(DoorSide.RIGHT), TimetableAction.CloseDoors))
        val id = TimetableServiceRunId("tt", "service")
        fixture.world.onOwnerForTest { fixture.dispatcher.startService(fixture.world, "tt", "service") }
        assertEquals(listOf(TrainOperation.OpenDoors(DoorSide.RIGHT)), gateway.requests.map { it.operation })
        repeat(5) { fixture.tick() }
        assertEquals(TimetableServiceRunState.WAITING_DOOR_OPERATION, fixture.dispatcher.status(id)!!.state)
        assertEquals(0.0, fixture.train.speed, 1e-9)
        gateway.tickets[0].succeed()
        fixture.tick()
        assertEquals(TrainOperation.CloseDoors(DoorSide.BOTH), gateway.requests[1].operation)
        repeat(5) { fixture.tick() }
        assertEquals(0.0, fixture.train.speed, 1e-9)
        gateway.tickets[1].succeed()
        repeat(1500) { if (fixture.dispatcher.status(id)!!.state != TimetableServiceRunState.COMPLETED) fixture.tick() }
        assertEquals(TimetableServiceRunState.COMPLETED, fixture.dispatcher.status(id)!!.state)
    }

    @Test fun `ordinary inherited opening requires an exact confirmed berth rather than a nearby station`() {
        val gateway = ControllableOperationGateway()
        val fixture = fixture(operationGateway = gateway, initialActions = listOf(TimetableAction.OpenDoors()))
        fixture.world.onOwnerForTest { fixture.dispatcher.startService(fixture.world, "tt", "service") }
        assertTrue(gateway.requests.isEmpty())
        assertTrue(fixture.world.simpleAtoWorldData.atoCoordinator.isStalled)
        assertTrue(fixture.dispatcher.status(TimetableServiceRunId("tt", "service"))!!.message!!.contains("confirmed current station berth"))
    }

    @Test fun `ordinary opening inherits canonical acquisition side and allows an explicit override`() {
        for (override in listOf(null, DoorSide.RIGHT)) {
            val gateway = ControllableOperationGateway()
            val fixture = fixture(operationGateway = gateway, canonicalOriginDoorSide = DoorSide.LEFT,
                initialActions = listOf(TimetableAction.OpenDoors(override), TimetableAction.CloseDoors))
            fixture.world.onOwnerForTest { fixture.dispatcher.startService(fixture.world, "tt", "service") }
            assertEquals(TrainOperation.OpenDoors(override ?: DoorSide.LEFT), gateway.requests.single().operation)
        }
    }

    @Test fun `NONE inherited berth performs no physical opening`() {
        val gateway = ControllableOperationGateway()
        val fixture = fixture(operationGateway = gateway, canonicalOriginDoorSide = DoorSide.NONE,
            initialActions = listOf(TimetableAction.OpenDoors()))
        fixture.world.onOwnerForTest { fixture.dispatcher.startService(fixture.world, "tt", "service") }
        repeat(1200) { if (fixture.dispatcher.status(TimetableServiceRunId("tt", "service"))!!.state != TimetableServiceRunState.COMPLETED) fixture.tick() }
        assertTrue(gateway.requests.isEmpty())
        assertFalse(fixture.world.simpleAtoWorldData.atoCoordinator.isStalled)
    }

    @Test fun `ordinary opening blocks movement until a successful BOTH close`() {
        val gateway = ControllableOperationGateway()
        val fixture = fixture(operationGateway = gateway, canonicalOriginDoorSide = DoorSide.LEFT,
            initialActions = listOf(TimetableAction.OpenDoors()))
        val started = fixture.world.onOwnerForTest { fixture.dispatcher.startService(fixture.world, "tt", "service") }
        assertTrue(started is TimetableServiceStartResult.Started, started.toString())
        assertEquals(TrainOperation.OpenDoors(DoorSide.LEFT), gateway.requests.single().operation)
        gateway.tickets.single().succeed()
        fixture.tick()
        assertTrue(fixture.world.simpleAtoWorldData.atoCoordinator.isStalled)
        assertTrue(fixture.dispatcher.status(TimetableServiceRunId("tt", "service"))!!.message!!.contains("door closing"))
        assertEquals(0.0, fixture.train.speed, 1e-9)
    }

    @Test fun `ordinary door pending timeout cancels and fails without launching movement`() {
        val gateway = ControllableOperationGateway()
        val fixture = fixture(operationGateway = gateway, operationTimeoutTicks = 3L,
            initialActions = listOf(TimetableAction.OpenDoors(DoorSide.LEFT), TimetableAction.CloseDoors))
        fixture.world.onOwnerForTest { fixture.dispatcher.startService(fixture.world, "tt", "service") }
        repeat(4) { fixture.tick() }
        assertTrue(fixture.world.simpleAtoWorldData.atoCoordinator.isStalled)
        assertEquals(TrainOperationTicketState.CANCELLED, gateway.tickets.single().snapshot().state)
        assertEquals(1, gateway.requests.size)
    }

    @Test fun `ordinary failed closing cannot release the movement door gate`() {
        val gateway = ControllableOperationGateway()
        val fixture = fixture(operationGateway = gateway,
            initialActions = listOf(TimetableAction.OpenDoors(DoorSide.LEFT), TimetableAction.CloseDoors))
        fixture.world.onOwnerForTest { fixture.dispatcher.startService(fixture.world, "tt", "service") }
        gateway.tickets.single().succeed()
        fixture.tick()
        gateway.tickets.last().fail("close animation failed")
        fixture.tick()
        assertTrue(fixture.world.simpleAtoWorldData.atoCoordinator.isStalled)
        assertEquals(0.0, fixture.train.speed, 1e-9)
    }

    @Test fun `completed canonical arrival establishes default side and reversal swaps it`() {
        val gateway = ControllableOperationGateway()
        val fixture = fixture(operationGateway = gateway, canonicalTarget = true, firstTargetOffsetMeters = 40.0,
            doorSide = DoorSide.LEFT, nextTargetOffsetMeters = 0.0,
            afterDestinationActions = listOf(TimetableAction.Reverse, TimetableAction.OpenDoors(), TimetableAction.CloseDoors))
        val id = TimetableServiceRunId("tt", "service")
        fixture.world.onOwnerForTest { fixture.dispatcher.startService(fixture.world, "tt", "service") }
        for (tick in 0 until 1600) {
            fixture.tick()
            gateway.tickets.forEach { it.succeed() }
            assertFalse(fixture.world.simpleAtoWorldData.atoCoordinator.isStalled,
                "reversed departure must consume its rebased grant: ${fixture.world.simpleAtoWorldData.atoCoordinator.lastStall}")
            if (fixture.dispatcher.status(id)!!.state == TimetableServiceRunState.COMPLETED) break
        }
        assertEquals(listOf(TrainOperation.OpenDoors(DoorSide.RIGHT), TrainOperation.CloseDoors(DoorSide.BOTH)), gateway.requests.map { it.operation })
        assertFalse(fixture.world.simpleAtoWorldData.atoCoordinator.isStalled)
        assertEquals(TimetableServiceRunState.COMPLETED, fixture.dispatcher.status(id)!!.state)
    }

    @Test fun `ordinary movement to a berth only opens doors when the following action requests it`() {
        val gateway = ControllableOperationGateway()
        val fixture = fixture(operationGateway = gateway, canonicalTarget = true, doorSide = DoorSide.LEFT,
            firstTargetOffsetMeters = 40.0, afterDestinationActions = listOf(TimetableAction.OpenDoors()))
        val id = TimetableServiceRunId("tt", "service")
        fixture.world.onOwnerForTest { fixture.dispatcher.startService(fixture.world, "tt", "service") }
        for (tick in 0 until 1500) {
            fixture.tick()
            gateway.tickets.forEach { it.succeed() }
            if (fixture.dispatcher.status(id)?.state == TimetableServiceRunState.COMPLETED) break
        }
        assertEquals(TimetableServiceRunState.COMPLETED, fixture.dispatcher.status(id)?.state)
        assertEquals(listOf(TrainOperation.OpenDoors(DoorSide.LEFT)), gateway.requests.map { it.operation })
        assertFalse(fixture.world.simpleAtoWorldData.atoCoordinator.isStalled)
    }

    @Test fun `moving away invalidates inherited berth context`() {
        val gateway = ControllableOperationGateway()
        val fixture = fixture(operationGateway = gateway, canonicalOriginDoorSide = DoorSide.LEFT,
            afterDestinationActions = listOf(TimetableAction.OpenDoors()))
        val id = TimetableServiceRunId("tt", "service")
        fixture.world.onOwnerForTest { fixture.dispatcher.startService(fixture.world, "tt", "service") }
        for (tick in 0 until 1500) {
            fixture.tick()
            if (fixture.world.simpleAtoWorldData.atoCoordinator.isStalled) break
        }
        assertTrue(gateway.requests.isEmpty())
        assertTrue(fixture.world.simpleAtoWorldData.atoCoordinator.isStalled)
        assertTrue(fixture.dispatcher.status(id)!!.message!!.contains("confirmed current station berth"))
    }

    @Test fun `confirmed station side preserves plugin resolution including explicit NONE`() {
        for (side in listOf(DoorSide.RIGHT, DoorSide.NONE)) {
            val gateway = ControllableOperationGateway()
            val fixture = fixture(operationGateway = gateway, includeStationAction = true, firstTargetOffsetMeters = 40.0,
                doorSide = DoorSide.LEFT, stopPrograms = programs(StationStopRole.TERMINAL, departure = null,
                    arrival = listOf(StationProgramAction.End)),
                afterDestinationActions = listOf(TimetableAction.OpenDoors(), TimetableAction.CloseDoors))
            fixture.world.simpleAtoWorldData.stationCallResolvers.register { context ->
                work.art1st.javarailways.simpleato.api.station.StationCallResolutionResult.Success(
                    context.defaultResolution.copy(doorSide = side))
            }
            val id = TimetableServiceRunId("tt", "service")
            fixture.world.onOwnerForTest { fixture.dispatcher.startService(fixture.world, "tt", "service") }
            for (tick in 0 until 1500) {
                fixture.tick()
                gateway.tickets.forEach { it.succeed() }
                if (fixture.dispatcher.status(id)!!.state == TimetableServiceRunState.COMPLETED) break
            }
            val expected = if (side == DoorSide.NONE) listOf(TrainOperation.CloseDoors(DoorSide.BOTH)) else
                listOf(TrainOperation.OpenDoors(side), TrainOperation.CloseDoors(DoorSide.BOTH))
            assertEquals(expected, gateway.requests.map { it.operation })
            assertFalse(fixture.world.simpleAtoWorldData.atoCoordinator.isStalled)
        }
    }

    @Test fun `retired confirmed berth cannot supply a default side`() {
        val gateway = ControllableOperationGateway()
        val fixture = fixture(operationGateway = gateway, canonicalOriginDoorSide = DoorSide.LEFT,
            initialActions = listOf(TimetableAction.OpenDoors(DoorSide.RIGHT), TimetableAction.CloseDoors, TimetableAction.OpenDoors()))
        fixture.world.onOwnerForTest { fixture.dispatcher.startService(fixture.world, "tt", "service") }
        work.art1st.javarailways.simpleato.api.destination.OperationalDestinationService(fixture.world).remove("origin@one")
        gateway.tickets[0].succeed()
        fixture.tick()
        gateway.tickets[1].succeed()
        fixture.tick()
        assertEquals(2, gateway.requests.size)
        assertTrue(fixture.world.simpleAtoWorldData.atoCoordinator.isStalled)
    }

    @Test fun `mismatched ordinary operation request fails and cancels its ticket`() {
        var cancelled = false
        val gateway = TrainOperationGateway { request ->
            TrainOperationExecutionResult.Accepted(object : TrainOperationTicket {
                override val request = request.copy(requestId = "another-request")
                override fun snapshot() = TrainOperationTicketSnapshot("wrong", this.request.requestId,
                    if (cancelled) TrainOperationTicketState.CANCELLED else TrainOperationTicketState.PENDING)
                override fun cancel() { cancelled = true }
            })
        }
        val fixture = fixture(operationGateway = gateway, canonicalOriginDoorSide = DoorSide.LEFT,
            initialActions = listOf(TimetableAction.OpenDoors()))
        val started = fixture.world.onOwnerForTest { fixture.dispatcher.startService(fixture.world, "tt", "service") }
        assertTrue(started is TimetableServiceStartResult.Failure, started.toString())
        assertTrue(cancelled)
        assertTrue(fixture.world.simpleAtoWorldData.atoCoordinator.isStalled)
    }

    @Test fun `shared destination program opens on actual arrival prepares early and awaits planned release`() {
        val gateway=ControllableOperationGateway()
        val fixture=fixture(nextTargetOffsetMeters=150.0,operationGateway=gateway,
            destinationOptions=TimetableAction.DestinationOptions("C",minimumDwellTicks=20L,doorSide=DoorSide.LEFT,
                stopPrograms=programs(StationStopRole.STOP)))
        val id=TimetableServiceRunId("tt","service")
        fixture.world.onOwnerForTest { fixture.dispatcher.startService(fixture.world,"tt","service") }
        var openedAt:Long?=null
        var closedAt:Long?=null
        for(tick in 0 until 2500) {
            fixture.tick()
            val status=fixture.dispatcher.status(id)!!
            gateway.requests.forEachIndexed { index,request ->
                if(request.operation is TrainOperation.OpenDoors && openedAt==null)openedAt=status.timetableElapsedTicks
                if(request.operation is TrainOperation.CloseDoors && closedAt==null)closedAt=status.timetableElapsedTicks
                gateway.tickets[index].succeed()
            }
            if(closedAt!=null && status.timetableElapsedTicks<1000L)assertEquals(0.0,fixture.train.speed,1e-8)
            if(status.state==TimetableServiceRunState.COMPLETED)break
        }
        assertNotNull(openedAt)
        assertEquals(960L,closedAt)
        assertEquals(2,gateway.requests.size)
        assertEquals(TimetableServiceRunState.COMPLETED,fixture.dispatcher.status(id)!!.state)
        assertEquals(fixture.targetArc,fixture.train.head.location.arcLengthIn(ViewDirection.AB),0.4)
        assertFalse(fixture.world.simpleAtoWorldData.atoCoordinator.isStalled)
    }

    @Test fun `actual train duration query schedules origin close and real ticket still prevents early movement`() {
        val gateway = ControllableOperationGateway().also { it.duration = java.util.concurrent.CompletableFuture() }
        val origin = StationStopPrograms("central", "origin", StationStopRole.ORIGIN,
            listOf(StationProgramAction.End), listOf(StationProgramAction.CloseDoors, StationProgramAction.End),
            StationPreparationLead(kotlinx.serialization.json.Json.parseToJsonElement("""{"doorCloseTicks":true}""")),
            100L, "A", DoorSide.LEFT)
        val fixture = fixture(originPrograms = origin, operationGateway = gateway, operationTimeoutTicks = 200L)
        val id = TimetableServiceRunId("tt", "service")
        fixture.world.onOwnerForTest { fixture.dispatcher.startService(fixture.world, "tt", "service") }
        repeat(30) { fixture.tick(); assertTrue(gateway.requests.isEmpty()) }
        assertEquals(listOf(fixture.train.id to DoorSide.LEFT), gateway.durationRequests)
        gateway.duration!!.complete(40L)
        var closeTick: Long? = null
        repeat(80) {
            fixture.tick()
            val status = fixture.dispatcher.status(id)!!
            if (gateway.requests.isNotEmpty() && closeTick == null) closeTick = status.timetableElapsedTicks
            assertEquals(0.0, fixture.train.speed, 1e-8)
            assertFalse(status.state == TimetableServiceRunState.COMPLETED)
        }
        assertEquals(60L, closeTick)
        assertEquals(1, gateway.requests.size)
        assertTrue(gateway.requests.single().operation is TrainOperation.CloseDoors)
        gateway.tickets.single().succeed()
        repeat(1500) { if (fixture.dispatcher.status(id)!!.state != TimetableServiceRunState.COMPLETED) fixture.tick() }
        assertEquals(TimetableServiceRunState.COMPLETED, fixture.dispatcher.status(id)!!.state)
        assertFalse(fixture.world.simpleAtoWorldData.atoCoordinator.isStalled)
    }

    @Test fun `station program minimum is anchored to BERTHED not later creation across timetable epochs`() {
        val gateway = ControllableOperationGateway()
        val stop = programs(StationStopRole.STOP, departure = 1L, lead = 2L, minimumDwellTicks = 100L)
        val fixture = fixture(includeStationAction = true, stopPrograms = stop, doorSide = DoorSide.LEFT,
            operationGateway = gateway)
        val id = TimetableServiceRunId("tt", "service")
        repeat(200) { fixture.world.onOwnerForTest { fixture.dispatcher.tick(fixture.world) } }
        val events = mutableListOf<TimetableStationProgramReady>()
        val subscription = fixture.dispatcher.observeStationProgramReady(id, listener = events::add)
        fixture.world.onOwnerForTest { fixture.dispatcher.startService(fixture.world, "tt", "service") }
        var berthed: Long? = null
        val submitted = mutableMapOf<Int, Long>()
        for (iteration in 0 until 2500) {
            val nextTick = fixture.dispatcher.status(id)!!.timetableElapsedTicks + 1L
            submitted.forEach { (index, tick) ->
                val duration = if (gateway.requests[index].operation is TrainOperation.OpenDoors) 10L else 2L
                if (nextTick >= tick + duration) gateway.tickets[index].succeed()
            }
            fixture.tick()
            val status = fixture.dispatcher.status(id)!!
            if (status.state == TimetableServiceRunState.BERTHED && berthed == null) berthed = status.timetableElapsedTicks
            gateway.requests.indices.forEach { submitted.putIfAbsent(it, status.timetableElapsedTicks) }
            if (events.isNotEmpty() || status.state == TimetableServiceRunState.FAILED) break
        }
        subscription.close()
        assertFalse(fixture.world.simpleAtoWorldData.atoCoordinator.isStalled)
        assertNotNull(berthed)
        assertEquals(berthed, events.single().arrivedTick)
        assertEquals(berthed!! + 98L, submitted.getValue(1))
        assertEquals(berthed + 100L, events.single().readyTick)
    }

    @Test fun `after commit completion is reported in timetable epoch without advancing station gates`() {
        val gateway = ControllableOperationGateway()
        val stop = programs(StationStopRole.STOP, departure = 1L, lead = 2L, minimumDwellTicks = 100L)
        val fixture = fixture(includeStationAction = true, stopPrograms = stop, doorSide = DoorSide.LEFT,
            operationGateway = gateway)
        val id = TimetableServiceRunId("tt", "service")
        repeat(200) { fixture.world.onOwnerForTest { fixture.dispatcher.tick(fixture.world) } }
        val events = mutableListOf<TimetableStationProgramReady>()
        val subscription = fixture.dispatcher.observeStationProgramReady(id, listener = events::add)
        fixture.world.onOwnerForTest { fixture.dispatcher.startService(fixture.world, "tt", "service") }
        var berthed: Long? = null
        var receivedAt: Long? = null
        val submitted = mutableMapOf<Int, Long>()
        for (iteration in 0 until 2500) {
            fixture.tick()
            val status = fixture.dispatcher.status(id)!!
            if (status.state == TimetableServiceRunState.BERTHED && berthed == null) berthed = status.timetableElapsedTicks
            gateway.requests.indices.forEach { submitted.putIfAbsent(it, status.timetableElapsedTicks) }
            submitted.forEach { (index, tick) ->
                val duration = if (gateway.requests[index].operation is TrainOperation.OpenDoors) 10L else 2L
                if (status.timetableElapsedTicks >= tick + duration) gateway.tickets[index].succeed()
            }
            fixture.world.onOwnerForTest { fixture.dispatcher.recordStationProgramCompletions(id) }
            if (berthed != null && status.timetableElapsedTicks == berthed + 100L) {
                assertTrue(events.isEmpty())
                assertEquals(TimetableServiceRunState.DWELLING, fixture.dispatcher.status(id)!!.state)
            }
            if (events.isNotEmpty()) { receivedAt = status.timetableElapsedTicks; break }
        }
        subscription.close()
        assertFalse(fixture.world.simpleAtoWorldData.atoCoordinator.isStalled)
        assertNotNull(berthed)
        assertEquals(berthed, events.single().arrivedTick)
        assertEquals(berthed!! + 100L, events.single().readyTick)
        assertEquals(berthed + 101L, receivedAt)
    }

    @Test fun `shared terminal program has no preparation and optional departure only delays duty handoff`() {
        val gateway=ControllableOperationGateway()
        val fixture=fixture(operationGateway=gateway,destinationOptions=TimetableAction.DestinationOptions("C",doorSide=DoorSide.LEFT,
            stopPrograms=programs(StationStopRole.TERMINAL)))
        val id=TimetableServiceRunId("tt","service")
        fixture.world.onOwnerForTest { fixture.dispatcher.startService(fixture.world,"tt","service") }
        repeat(999) {
            fixture.tick()
            gateway.tickets.forEach { it.succeed() }
            assertFalse(fixture.dispatcher.status(id)!!.state==TimetableServiceRunState.COMPLETED)
        }
        repeat(5) { fixture.tick() }
        assertEquals(TimetableServiceRunState.COMPLETED,fixture.dispatcher.status(id)!!.state)
        assertEquals(1,gateway.requests.size)
        assertTrue(gateway.requests.single().operation is TrainOperation.OpenDoors)
        assertFalse(fixture.world.simpleAtoWorldData.atoCoordinator.isStalled)
    }

    @Test fun `origin program runs preparation without waiting for a phantom arrival`() {
        var arrivalStarts=0
        var preparationStarts=0
        val extension=StationProgramAction.Extension(TimetableExtensionCall("test:origin"))
        val origin=programs(StationStopRole.ORIGIN,departure=100L,
            arrival=listOf(extension,StationProgramAction.End),preparation=listOf(extension,StationProgramAction.End),lead=20L,destination="A")
        val fixture=fixture(originPrograms=origin)
        fixture.world.simpleAtoWorldData.timetableExtensions.registerAction(object:TimetableActionProvider {
            override val typeId="test:origin"
            override val version=1
            override val descriptor=TimetableExtensionDescriptor("Origin","Origin program")
            override fun start(parameters:JsonObject,context:TimetableRuntimeContext):TimetableExtensionActionStartResult {
                if(context.currentWayPointId=="A")preparationStarts++ else arrivalStarts++
                assertTrue(context.timetableElapsedTicks>=80L)
                return TimetableExtensionActionStartResult.Accepted(object:TimetableExtensionActionTicket {
                    override fun snapshot()=TimetableExtensionActionSnapshot(TimetableExtensionActionState.SUCCEEDED)
                    override fun cancel()=Unit
                })
            }
        })
        val id=TimetableServiceRunId("tt","service")
        fixture.world.onOwnerForTest { fixture.dispatcher.startService(fixture.world,"tt","service") }
        repeat(79) {fixture.tick();assertEquals(0,preparationStarts)}
        repeat(1500) { if(fixture.dispatcher.status(id)!!.state!=TimetableServiceRunState.COMPLETED)fixture.tick() }
        assertEquals(1,preparationStarts)
        assertEquals(0,arrivalStarts)
        assertEquals(TimetableServiceRunState.COMPLETED,fixture.dispatcher.status(id)!!.state)
        assertFalse(fixture.world.simpleAtoWorldData.atoCoordinator.isStalled)
    }

    @Test fun `origin station program honors its containing scheduled action tick`() {
        var starts = 0
        val origin = programs(StationStopRole.ORIGIN, departure = 100L,
            preparation = listOf(StationProgramAction.Extension(TimetableExtensionCall("test:timed-origin")), StationProgramAction.End),
            lead = 100L, destination = "A")
        val fixture = fixture(originPrograms = origin, originProgramTick = 50L, stationMovementTick = 50L)
        fixture.world.simpleAtoWorldData.timetableExtensions.registerAction(object : TimetableActionProvider {
            override val typeId = "test:timed-origin"
            override val version = 1
            override val descriptor = TimetableExtensionDescriptor("Timed origin", "Scheduled origin entry")
            override fun start(parameters: JsonObject, context: TimetableRuntimeContext): TimetableExtensionActionStartResult {
                assertTrue(context.timetableElapsedTicks >= 50L)
                starts++
                return TimetableExtensionActionStartResult.Accepted(object : TimetableExtensionActionTicket {
                    override fun snapshot() = TimetableExtensionActionSnapshot(TimetableExtensionActionState.SUCCEEDED)
                    override fun cancel() = Unit
                })
            }
        })
        val started = fixture.world.onOwnerForTest { fixture.dispatcher.startService(fixture.world, "tt", "service") }
        assertTrue(started is TimetableServiceStartResult.Started, started.toString())
        repeat(49) { fixture.tick(); assertEquals(0, starts) }
        repeat(10) { fixture.tick() }
        assertEquals(1, starts)
        assertEquals(0.0, fixture.train.speed, 1e-8)
    }

    @Test fun `origin station program after reversal does not recheck the old head waypoint`() {
        var starts = 0
        val extension = StationProgramAction.Extension(TimetableExtensionCall("test:reversed-origin"))
        val origin = programs(StationStopRole.ORIGIN, departure = 1_000L,
            preparation = listOf(extension, StationProgramAction.End), lead = 1_000L, destination = "A")
        val fixture = fixture(initialActions = listOf(TimetableAction.Reverse), originPrograms = origin,
            stationMovementTick = 1_000L)
        fixture.world.simpleAtoWorldData.timetableExtensions.registerAction(object : TimetableActionProvider {
            override val typeId = "test:reversed-origin"
            override val version = 1
            override val descriptor = TimetableExtensionDescriptor("Reversed origin", "Origin program after reversal")
            override fun start(parameters: JsonObject, context: TimetableRuntimeContext): TimetableExtensionActionStartResult {
                starts++
                return TimetableExtensionActionStartResult.Accepted(object : TimetableExtensionActionTicket {
                    override fun snapshot() = TimetableExtensionActionSnapshot(TimetableExtensionActionState.SUCCEEDED)
                    override fun cancel() = Unit
                })
            }
        })

        val id = TimetableServiceRunId("tt", "service")
        val started = fixture.world.onOwnerForTest { fixture.dispatcher.startService(fixture.world, id.timetableId, id.serviceId) }
        assertTrue(started is TimetableServiceStartResult.Started, started.toString())
        var ticks = 0
        while (starts == 0 && ticks++ < 100) fixture.tick()

        assertEquals(1, starts, fixture.dispatcher.status(id).toString())
        assertFalse(fixture.world.simpleAtoWorldData.atoCoordinator.isStalled)
        assertFalse(fixture.dispatcher.status(id)?.state == TimetableServiceRunState.FAILED)
    }

    @Test fun `station train information reads each published direction and validates before start`() {
        val bindings = mapOf("name" to TrainInfoField.NAME, "cars" to TrainInfoField.CAR_COUNT,
            "direction" to TrainInfoField.DIRECTION)
        val read = StationProgramAction.Extension(TimetableExtensionCall("test:train-info"), bindings)
        val fixture = fixture(destinationOptions = TimetableAction.DestinationOptions("C", doorSide = DoorSide.NONE,
            stopPrograms = programs(StationStopRole.TERMINAL, departure = null,
                arrival = listOf(read, StationProgramAction.Reverse, read, StationProgramAction.End))))
        val observed = arrayListOf<JsonObject>()
        val cars = fixture.train.cars.map { it.id }
        var validations = 0
        fixture.world.simpleAtoWorldData.timetableExtensions.registerAction(object : TimetableActionProvider {
            override val typeId = "test:train-info"
            override val version = 1
            override val descriptor = TimetableExtensionDescriptor("Info", "Published train information")
            override fun validate(parameters: JsonObject): List<String> {
                validations++
                return if (parameters.keys == bindings.keys) emptyList() else listOf("missing current train information")
            }
            override fun start(parameters: JsonObject, context: TimetableRuntimeContext): TimetableExtensionActionStartResult {
                assertEquals(observed.size + 1, validations)
                assertEquals(kotlinx.serialization.json.JsonPrimitive(fixture.train.publishedFrame.trainName), parameters["name"])
                assertEquals(kotlinx.serialization.json.JsonPrimitive(cars.size), parameters["cars"])
                observed += parameters
                return TimetableExtensionActionStartResult.Accepted(object : TimetableExtensionActionTicket {
                    override fun snapshot() = TimetableExtensionActionSnapshot(TimetableExtensionActionState.SUCCEEDED)
                    override fun cancel() = Unit
                })
            }
        })
        val id = TimetableServiceRunId("tt", "service")
        val start = fixture.world.onOwnerForTest { fixture.dispatcher.startService(fixture.world, "tt", "service") }
        assertTrue(start is TimetableServiceStartResult.Started, start.toString())
        for (tick in 0 until 2200) {
            fixture.tick()
            if (fixture.dispatcher.status(id)!!.state == TimetableServiceRunState.COMPLETED) break
        }
        assertEquals(listOf("FORWARD", "REVERSED"), observed.map { it["direction"].toString().trim('"') })
        assertEquals(2, validations)
        assertFalse(fixture.world.simpleAtoWorldData.atoCoordinator.isStalled)
    }

    @Test fun `terminal station reverse awaits physical publication and retained body rebase`() {
        val fixture=fixture(destinationOptions=TimetableAction.DestinationOptions("C",doorSide=DoorSide.NONE,
            stopPrograms=programs(StationStopRole.TERMINAL,departure=null,
                arrival=listOf(StationProgramAction.Reverse,StationProgramAction.End))))
        val id=TimetableServiceRunId("tt","service")
        fixture.world.onOwnerForTest { fixture.dispatcher.startService(fixture.world,"tt","service") }
        var reversing=false
        for(tick in 0 until 2000) {
            fixture.tick()
            val status=fixture.dispatcher.status(id)!!
            if(status.state==TimetableServiceRunState.REVERSING)reversing=true
            if(status.state==TimetableServiceRunState.COMPLETED)break
        }
        assertTrue(reversing)
        assertTrue(fixture.train.isReversed)
        assertEquals(TimetableServiceRunState.COMPLETED,fixture.dispatcher.status(id)!!.state)
        assertEquals(0.0,fixture.train.speed,1e-8)
        assertFalse(fixture.world.simpleAtoWorldData.atoCoordinator.isStalled)
    }

    @Test fun `terminal open doors cannot be bypassed by a store at depot suffix`() {
        val staticallyRejected = fixture(storeAfterDestination=true,
            destinationOptions=TimetableAction.DestinationOptions("C",doorSide=DoorSide.LEFT,
                stopPrograms=programs(StationStopRole.TERMINAL,departure=null)))
        val authored = checkNotNull(staticallyRejected.world.simpleAtoWorldData.timetables["tt"])
        assertTrue(TimetableValidator(staticallyRejected.world).validate(authored).errors.any {
            it.contains("车门状态冲突") && it.contains("CLOSE_DOORS")
        })

        val gateway=ControllableOperationGateway()
        val fixture=fixture(storeAfterDestination=true,operationGateway=gateway,canonicalTarget=true,
            doorSide=DoorSide.LEFT,afterDestinationActions=listOf(TimetableAction.OpenDoors()))
        val id=TimetableServiceRunId("tt","service")
        val started=fixture.world.onOwnerForTest { fixture.dispatcher.startService(fixture.world,"tt","service") }
        assertTrue(started is TimetableServiceStartResult.Started,started.toString())
        var stoppedArrivalArc: Double? = null
        for(tick in 0 until 1500) {
            fixture.tick()
            if (gateway.requests.isNotEmpty()) {
                val actualArc = fixture.train.head.location.arcLengthIn(ViewDirection.AB)
                if (stoppedArrivalArc == null) stoppedArrivalArc = actualArc
                assertEquals(0.0, fixture.train.speed, 1e-8)
                assertEquals(checkNotNull(stoppedArrivalArc), actualArc, 1e-8, "train moved after terminal door operation began")
            }
            gateway.tickets.forEach { it.succeed() }
            if(fixture.dispatcher.status(id)!!.state==TimetableServiceRunState.FAILED)break
        }
        val status=fixture.dispatcher.status(id)!!
        assertEquals(TimetableServiceRunState.FAILED,status.state,status.toString())
        assertTrue(status.message.orEmpty().contains("successful door closing"),status.toString())
        assertEquals(1,gateway.requests.size)
        assertTrue(gateway.requests.single().operation is TrainOperation.OpenDoors)
        assertEquals(0.0,fixture.train.speed,1e-8)
        assertNotNull(stoppedArrivalArc)
        assertEquals(checkNotNull(stoppedArrivalArc),fixture.train.head.location.arcLengthIn(ViewDirection.AB),1e-8)
    }

    @Test fun `terminal station call closes doors before storage movement handoff`() {
        val gateway = ControllableOperationGateway()
        val fixture = fixture(
            includeStationAction = true,
            stopPrograms = programs(
                StationStopRole.TERMINAL,
                departure = null,
                arrival = listOf(StationProgramAction.OpenDoors, StationProgramAction.End),
                destination = "central@one"
            ),
            storeAfterDestination = true,
            canonicalTarget = true,
            doorSide = DoorSide.LEFT,
            operationGateway = gateway,
            afterDestinationActions = listOf(TimetableAction.CloseDoors)
        )
        val id = TimetableServiceRunId("tt", "service")
        val started = fixture.world.onOwnerForTest {
            fixture.dispatcher.startService(fixture.world, "tt", "service")
        }
        assertTrue(started is TimetableServiceStartResult.Started, started.toString())

        val observed = linkedSetOf<TimetableServiceRunState>()
        for (tick in 0 until 4_000) {
            fixture.tick()
            gateway.tickets.forEach { it.succeed() }
            val status = fixture.dispatcher.status(id)!!
            observed += status.state
            if (status.state == TimetableServiceRunState.COMPLETED ||
                fixture.world.simpleAtoWorldData.atoCoordinator.isStalled
            ) break
        }

        assertEquals(
            listOf(TrainOperation.OpenDoors(DoorSide.LEFT), TrainOperation.CloseDoors(DoorSide.BOTH)),
            gateway.requests.map { it.operation }
        )
        assertTrue(TimetableServiceRunState.DEPARTING in observed, "observed=$observed")
        assertTrue(TimetableServiceRunState.STORING in observed, "observed=$observed")
        assertEquals(TimetableServiceRunState.COMPLETED, fixture.dispatcher.status(id)!!.state)
        assertFalse(
            fixture.world.simpleAtoWorldData.atoCoordinator.isStalled,
            fixture.world.simpleAtoWorldData.atoCoordinator.lastStall.toString()
        )
    }

    @Test fun `operation shutdown cancels pending station task door tickets`() {
        val gateway=ControllableOperationGateway()
        val fixture=fixture(operationGateway=gateway,destinationOptions=TimetableAction.DestinationOptions("C",doorSide=DoorSide.LEFT,
            stopPrograms=programs(StationStopRole.TERMINAL,departure=null)))
        fixture.world.onOwnerForTest {fixture.dispatcher.startService(fixture.world,"tt","service")}
        repeat(1500) {if(gateway.requests.isEmpty())fixture.tick()}
        assertEquals(1,gateway.requests.size)
        fixture.dispatcher.cancelPendingActionsForShutdown(fixture.world)
        assertEquals(TrainOperationTicketState.CANCELLED,gateway.tickets.single().snapshot().state)
        assertEquals(0.0,fixture.train.speed,1e-8)
    }

    @Test fun `station program failure leaves departure hold and cancels every live operation`() {
        val gateway=ControllableOperationGateway()
        val invalid=programs(StationStopRole.STOP,arrival=listOf(StationProgramAction.OpenDoors,StationProgramAction.End))
        val fixture=fixture(includeStationAction=true,stopPrograms=invalid,doorSide=DoorSide.LEFT,operationGateway=gateway)
        val id=TimetableServiceRunId("tt","service")
        fixture.world.onOwnerForTest { fixture.dispatcher.startService(fixture.world,"tt","service") }
        repeat(1500) {if(gateway.requests.isEmpty())fixture.tick()}
        assertEquals(1,gateway.requests.size)
        gateway.tickets.single().fail("blocked door")
        fixture.tick()
        assertEquals(TimetableServiceRunState.FAILED,fixture.dispatcher.status(id)!!.state)
        assertEquals(0.0,fixture.train.speed,1e-8)
        assertEquals(1,gateway.requests.size)
    }

    @Test fun `shared terminal absolute wait gates completion without a second execution cursor`() {
        val fixture = fixture(destinationOptions = TimetableAction.DestinationOptions("C", minimumDwellTicks = 15,
            stopPrograms = programs(StationStopRole.TERMINAL, departure = null,
                arrival = listOf(StationProgramAction.WaitDuration(30), StationProgramAction.WaitUntil(2000), StationProgramAction.End))))
        val id = TimetableServiceRunId("tt", "service")
        fixture.world.onOwnerForTest { fixture.dispatcher.startService(fixture.world, "tt", "service") }
        repeat(1999) { fixture.tick(); assertFalse(fixture.dispatcher.status(id)!!.state == TimetableServiceRunState.COMPLETED) }
        repeat(5) { fixture.tick() }
        assertEquals(TimetableServiceRunState.COMPLETED, fixture.dispatcher.status(id)!!.state)
        assertFalse(fixture.world.simpleAtoWorldData.atoCoordinator.isStalled)
    }

    @Test fun `missing shared station provider is rejected before runtime installation`() {
        val fixture = fixture(destinationOptions = TimetableAction.DestinationOptions("C",
            stopPrograms = programs(StationStopRole.TERMINAL, departure = null,
                arrival = listOf(StationProgramAction.Extension(TimetableExtensionCall("missing:action")), StationProgramAction.End))))
        val result = fixture.world.onOwnerForTest { fixture.dispatcher.startService(fixture.world, "tt", "service") }
        assertTrue(result is TimetableServiceStartResult.Failure, result.toString())
        assertNull(fixture.dispatcher.status(TimetableServiceRunId("tt", "service")))
        assertFalse(fixture.train.controller is SimpleATO)
        assertFalse(fixture.world.simpleAtoWorldData.atoCoordinator.isStalled)
    }

    @Test
    fun `canonical marker edits cannot bypass a held berth`() {
        val fixture = fixture()
        val world = fixture.world
        world.registerExtensionData(
            work.art1st.javarailways.simpleato.SimpleATOPlugin.INSTANCE,
            world.simpleAtoWorldData.copy(dispatcher = fixture.dispatcher)
        )
        val service = work.art1st.javarailways.simpleato.api.destination.OperationalDestinationService(world)
        val role = work.art1st.javarailways.simpleato.api.destination.DestinationRole.StationBerthRole(
            stationId = fixture.station.id, stationDisplayName = "Central", berthId = "one", doorSide = DoorSide.NONE
        )
        val destination = work.art1st.javarailways.simpleato.api.destination.OperationalDestination(
            "central@one", fixture.berth.location.rail.id,
            fixture.berth.location.arcLengthIn(ViewDirection.AB), ViewDirection.AB, role
        )
        world.simpleAtoWorldData.stations.removeBerth(fixture.berth.berthRef)
        service.register(destination)
        val timetable = checkNotNull(world.simpleAtoWorldData.timetables["tt"])
        world.simpleAtoWorldData.timetables.add(timetable.copy(services = listOf(
            timetable.services.single().copy(steps = listOf(TimetableStep.Check("A"),
                TimetableStep.At(0L, TimetableAction.GoVia(listOf(destination.id, "C")))))
        )))
        val id = TimetableServiceRunId("tt", "service")
        val started = world.onOwnerForTest { fixture.dispatcher.startService(world, "tt", "service") } as TimetableServiceStartResult.Started
        val call = StationCall("held-call", started.run.missionRevision, fixture.station.id, fixture.berth.berthRef, dwellPlan = DwellPlan(5L))
        assertTrue(fixture.dispatcher.scheduleStationCall(world, id, call) is TimetableStationCallScheduleResult.Scheduled)
        val berthRef = fixture.berth.berthRef
        assertTrue(fixture.dispatcher.hasStationBerthAllocation(berthRef))
        assertThrows(IllegalStateException::class.java) { service.replace(destination.id, destination.copy(role = role.copy(usableLengthMeters = 90.0))) }
        assertThrows(IllegalStateException::class.java) { service.remove(destination.id) }
        val alias = destination.copy(id = "central@two", role = role.copy(berthId = "two"))
        service.register(alias)
        assertEquals(destination, service[destination.id])
        assertEquals(alias, service[alias.id])
        assertNotNull(world.basicWorldData.wayPoints[alias.id])
        assertTrue(fixture.dispatcher.hasStationBerthAllocation(berthRef))
    }

    @Test
    fun `interior station stop does not replace final operating anchor stopping margin`() {
        listOf(180.0, 179.0).forEach { anchorArc ->
            val fixture = fixture(includeStationAction = true, berthStoppingOffsetMetersBefore = 0.25)
            fixture.world.onOwnerForTest {
                val anchor = checkNotNull(fixture.data.wayPoints["C"])
                fixture.data.wayPoints.add(anchor.copy(arcLength = anchorArc))
            }
            val id = TimetableServiceRunId("tt", "service")
            val started = fixture.world.onOwnerForTest {
                fixture.dispatcher.startService(fixture.world, "tt", "service")
            }
            assertTrue(started is TimetableServiceStartResult.Started, started.toString())
            if (anchorArc == 180.0) {
                // startService installs a mission; its first coherent admission observation
                // prepares physical geometry later. Reject that candidate, not the start call.
                for (tick in 0 until 20) {
                    fixture.tick()
                    if (fixture.dispatcher.status(id)?.state == TimetableServiceRunState.REPLAN_REQUIRED) break
                }
                val status = checkNotNull(fixture.dispatcher.status(id))
                assertEquals(TimetableServiceRunState.REPLAN_REQUIRED, status.state, status.toString())
                assertFalse(status.routeGrantActive, "the interior berth cannot supply missing final stopping margin")
                assertTrue(status.message.orEmpty().contains("terminal margin"), status.toString())
            } else {
                for (tick in 0 until 2_000) {
                    fixture.tick()
                    if (fixture.dispatcher.status(id)?.state == TimetableServiceRunState.COMPLETED) break
                }
                val status = checkNotNull(fixture.dispatcher.status(id))
                assertEquals(TimetableServiceRunState.COMPLETED, status.state, status.toString())
                val operatingEnd = (fixture.train.controller as SimpleATO).currentMission!!.legs.last()
                    .plannedRoute.expandedRoute.segments.last()
                assertEquals(anchorArc, operatingEnd.endArcLength, 1.0e-9)
                assertFalse(fixture.world.simpleAtoWorldData.atoCoordinator.isStalled)
            }
        }
    }

    @Test
    fun `actual authority breach prevents queued station arrival and ordinary destination door operations`() {
        // This fixture uses scripted admission and a kinematic actuator. The overrun itself is a
        // real Core movement/publication; the test isolates Dispatcher transition ordering.
        listOf(true, false).forEach { station ->
            val gateway = ControllableOperationGateway()
            val fixture = fixture(firstTargetOffsetMeters = 40.0, includeStationAction = station,
                doorSide = DoorSide.LEFT, operationGateway = gateway,
                destinationOptions = if (station) null else TimetableAction.DestinationOptions(
                    destinationId = "C", minimumDwellTicks = 5L, doorSide = DoorSide.LEFT))
            val id = TimetableServiceRunId("tt", "service")
            val started = fixture.world.onOwnerForTest {
                fixture.dispatcher.startService(fixture.world, "tt", "service")
            }
            assertTrue(started is TimetableServiceStartResult.Started, started.toString())
            var injected = false
            for (tick in 0 until 2_000) {
                val ato = fixture.train.controller as SimpleATO
                AsyncTickTestSupport.run { ato.tick(50.0) }
                ato.commit()
                if (fixture.train.speed == 0.0 && fixture.train.head.location.arcLength > fixture.targetArc - 1.0) {
                    fixture.train.moveTrainForward(3.0)
                    fixture.train.commit()
                    injected = true
                }
                fixture.world.onOwnerForTest { fixture.dispatcher.tick(fixture.world) }
                if (injected) break
            }
            assertTrue(injected)
            assertEquals("RouteAuthorityViolation", fixture.world.simpleAtoWorldData.atoCoordinator.lastStall?.errorType)
            assertEquals(TimetableServiceRunState.FAILED, fixture.dispatcher.status(id)?.state)
            assertTrue(gateway.requests.isEmpty(), "no door operation may run after actual authority breach")
            assertTrue(fixture.dispatcher.status(id)?.routeGrantActive == true, "retain the physical claim")
        }
    }

    private var threadGuardRegistration: ThreadGuardRegistration? = null

    private val profile = AtoSafetyProfile(
        accelerationMetersPerSecondSquared = 1.0,
        serviceDecelerationMetersPerSecondSquared = 1.0,
        // The test actuator integrates in 50ms steps; include its discrete control response.
        reactionTimeSeconds = 0.1,
        safetyDistanceMeters = 1.0
    )

    @BeforeEach
    fun installMainThreadBridge() {
        threadGuardRegistration = ThreadGuardHost.registerPlatformBridge(object : ThreadGuardPlatformBridge {
            override fun isMainThread(): Boolean = true
        })
    }

    @AfterEach
    fun unregisterWorlds() {
        threadGuardRegistration?.close()
        threadGuardRegistration = null
    }

    @Test
    fun `dispatcher dwells grants departure and releases berth behind the tail`() {
        val fixture = fixture()
        val id = TimetableServiceRunId("tt", "service")
        val started = fixture.world.onOwnerForTest { fixture.dispatcher.startService(fixture.world, id.timetableId, id.serviceId) }
            as TimetableServiceStartResult.Started
        val call = StationCall(
            callId = "central-call",
            missionRevision = started.run.missionRevision,
            stationId = fixture.station.id,
            berthRef = fixture.berth.berthRef,
            dwellPlan = DwellPlan(
                minimumDwellTicks = 5L,
                plannedDepartureTick = 1_200L
            )
        )

        val scheduled = fixture.dispatcher.scheduleStationCall(fixture.world, id, call)
        assertTrue(scheduled is TimetableStationCallScheduleResult.Scheduled)
        assertEquals(TimetableServiceRunState.WAITING_MISSION, fixture.dispatcher.status(id)?.state)
        assertTrue(fixture.dispatcher.status(id)?.berthHeld == true)

        val observedStates = linkedSetOf<TimetableServiceRunState>()
        var headPastWhileTailInside = false
        var releasedAfterTail = false
        var berthedAtTick: Int? = null
        var departingAtTick: Int? = null
        val releaseArc = fixture.berth.location.arcLengthIn(ViewDirection.AB) +
            fixture.berth.releaseDistanceMetersAfter
        for (tick in 0 until 2_500) {
            fixture.tick()
            val status = fixture.dispatcher.status(id)!!
            observedStates += status.state
            if (status.state == TimetableServiceRunState.BERTHED && berthedAtTick == null) {
                berthedAtTick = tick + 1
            }
            if (status.state == TimetableServiceRunState.DEPARTING && departingAtTick == null) {
                departingAtTick = tick + 1
            }
            val headArc = fixture.train.head.location.arcLengthIn(ViewDirection.AB)
            val tailArc = fixture.train.tailBogie.location.arcLengthIn(ViewDirection.AB)
            if (headArc >= releaseArc && tailArc < releaseArc) {
                headPastWhileTailInside = true
                assertTrue(status.berthHeld)
            }
            if (tailArc >= releaseArc && !status.berthHeld) releasedAfterTail = true
            if (status.state == TimetableServiceRunState.COMPLETED && releasedAfterTail) break
        }

        assertTrue(TimetableServiceRunState.APPROACHING in observedStates)
        assertTrue(TimetableServiceRunState.BERTHED in observedStates)
        assertTrue(TimetableServiceRunState.DWELLING in observedStates)
        assertTrue(TimetableServiceRunState.READY_TO_DEPART in observedStates)
        assertTrue(TimetableServiceRunState.DEPARTING in observedStates)
        assertTrue(TimetableServiceRunState.DEPARTED in observedStates)
        assertTrue(headPastWhileTailInside)
        assertTrue(releasedAfterTail)
        assertTrue(departingAtTick!! >= berthedAtTick!! + 5)
        assertTrue(departingAtTick >= 1_200)
        assertEquals(TimetableServiceRunState.COMPLETED, fixture.dispatcher.status(id)?.state)
        assertEquals(fixture.targetArc, fixture.train.head.location.arcLengthIn(ViewDirection.AB), 0.4)
        assertFalse(fixture.world.simpleAtoWorldData.atoCoordinator.isStalled)
    }

    @Test
    fun `terminal station navigation completes while remaining berthed`() {
        val fixture = fixture(includeStationAction = true, firstTargetOffsetMeters = 40.0)
        val id = TimetableServiceRunId("tt", "service")
        fixture.world.onOwnerForTest { fixture.dispatcher.startService(fixture.world, "tt", "service") } as
            TimetableServiceStartResult.Started

        val observed = linkedSetOf<TimetableServiceRunState>()
        for (tick in 0 until 2_000) {
            fixture.tick()
            val state = fixture.dispatcher.status(id)!!.state
            observed += state
            if (state == TimetableServiceRunState.COMPLETED) break
        }

        assertTrue(TimetableServiceRunState.BERTHED in observed)
        assertTrue(TimetableServiceRunState.DWELLING in observed)
        assertFalse(TimetableServiceRunState.DEPARTING in observed)
        assertEquals(TimetableServiceRunState.COMPLETED, fixture.dispatcher.status(id)?.state)
        assertEquals(fixture.berth.location.arcLengthIn(ViewDirection.AB),
            fixture.train.head.location.arcLengthIn(ViewDirection.AB), 0.4)
    }

    @Test
    fun `terminal station navigation starts the following queued movement after dwell`() {
        val fixture = fixture(
            includeStationAction = true,
            firstTargetOffsetMeters = 40.0,
            nextTargetOffsetMeters = 100.0
        )
        val id = TimetableServiceRunId("tt", "service")
        fixture.world.onOwnerForTest { fixture.dispatcher.startService(fixture.world, "tt", "service") } as
            TimetableServiceStartResult.Started

        val observed = linkedSetOf<TimetableServiceRunState>()
        for (tick in 0 until 2_500) {
            fixture.tick()
            val state = fixture.dispatcher.status(id)!!.state
            observed += state
            if (state == TimetableServiceRunState.COMPLETED) break
        }

        assertTrue(TimetableServiceRunState.BERTHED in observed)
        assertTrue(TimetableServiceRunState.DEPARTING in observed)
        val finalStatus = fixture.dispatcher.status(id)
        assertEquals(TimetableServiceRunState.COMPLETED, finalStatus?.state, finalStatus.toString())
        assertFalse(finalStatus?.berthHeld == true)
        assertEquals(fixture.targetArc, fixture.train.head.location.arcLengthIn(ViewDirection.AB), 0.4)
        assertFalse(fixture.world.simpleAtoWorldData.atoCoordinator.isStalled)
    }

    @Test
    fun `timetable station action schedules the call with its following movement`() {
        val fixture = fixture(includeStationAction = true)
        val id = TimetableServiceRunId("tt", "service")

        val started = fixture.world.onOwnerForTest { fixture.dispatcher.startService(fixture.world, "tt", "service") }
            as TimetableServiceStartResult.Started

        assertTrue(started.run.stationCallId != null)
        assertTrue(started.run.berthHeld)
        val observed = linkedSetOf<TimetableServiceRunState>()
        for (tick in 0 until 2_000) {
            fixture.tick()
            val state = fixture.dispatcher.status(id)!!.state
            observed += state
            if (state == TimetableServiceRunState.COMPLETED) break
        }
        assertTrue(TimetableServiceRunState.BERTHED in observed)
        assertTrue(TimetableServiceRunState.DWELLING in observed)
        assertFalse(TimetableServiceRunState.DEPARTED in observed)
        assertEquals(TimetableServiceRunState.COMPLETED, fixture.dispatcher.status(id)?.state)
        assertFalse(fixture.world.simpleAtoWorldData.atoCoordinator.isStalled)
    }

    @Test
    fun `waypoint progress observer reports actual stop time`() {
        val fixture = fixture(firstTargetOffsetMeters = 40.0)
        val id = TimetableServiceRunId("tt", "service")
        val observed = ArrayList<TimetableWaypointProgress>()
        val subscription = fixture.dispatcher.observeWaypointProgress(id) { observed += it }
        try {
            fixture.world.onOwnerForTest { fixture.dispatcher.startService(fixture.world, "tt", "service") } as
                TimetableServiceStartResult.Started
            for (tick in 0 until 1_000) {
                fixture.tick()
                if (observed.isNotEmpty()) break
            }
        } finally {
            subscription.close()
        }

        assertEquals(1, observed.size)
        assertEquals("C", observed.single().waypointId)
        assertFalse(observed.single().pass)
        assertTrue(observed.single().timetableElapsedTicks > 0L)
    }

    @Test
    fun `destination tick is the route departure time`() {
        val fixture = fixture(
            includeStationAction = true,
            stationMovementTick = 300L
        )
        val id = TimetableServiceRunId("tt", "service")
        val started = fixture.world.onOwnerForTest { fixture.dispatcher.startService(fixture.world, "tt", "service") } as
            TimetableServiceStartResult.Started
        assertEquals(TimetableServiceRunState.WAITING_TIMETABLE_TIME, started.run.state)

        var status = fixture.dispatcher.status(id)!!
        while (status.timetableElapsedTicks < 300L) {
            assertEquals(TimetableServiceRunState.WAITING_TIMETABLE_TIME, status.state)
            assertEquals(0.0, fixture.train.speed, 1.0e-9)
            fixture.tick()
            status = fixture.dispatcher.status(id)!!
        }

        val departedOnSchedule = status
        assertEquals(300L, departedOnSchedule.timetableElapsedTicks)
        assertEquals(300L, departedOnSchedule.scheduledActionTick)
        assertFalse(fixture.world.simpleAtoWorldData.atoCoordinator.isStalled)
    }

    @Test
    fun `door tickets gate station dwell and departure hold release`() {
        val gateway = ControllableOperationGateway()
        val fixture = fixture(
            includeStationAction = true,
            firstTargetOffsetMeters = 40.0,
            nextTargetOffsetMeters = 100.0,
            doorSide = DoorSide.LEFT,
            operationGateway = gateway
        )
        val id = TimetableServiceRunId("tt", "service")
        fixture.world.onOwnerForTest { fixture.dispatcher.startService(fixture.world, "tt", "service") } as
            TimetableServiceStartResult.Started
        val observed = linkedSetOf<TimetableServiceRunState>()
        var openingCompleted = false
        var closingCompleted = false

        for (tick in 0 until 2_000) {
            fixture.tick()
            val status = fixture.dispatcher.status(id)!!
            observed += status.state
            if (status.state == TimetableServiceRunState.DOORS_OPENING && !openingCompleted) {
                assertTrue(gateway.requests.single().operation is TrainOperation.OpenDoors)
                assertEquals(TrainOperationTicketState.PENDING, status.operationTicketState)
                gateway.tickets.single().succeed()
                openingCompleted = true
            }
            if (status.state == TimetableServiceRunState.DOORS_CLOSING && !closingCompleted) {
                assertEquals(2, gateway.requests.size)
                assertTrue(gateway.requests.last().operation is TrainOperation.CloseDoors)
                assertEquals(0.0, fixture.train.speed)
                gateway.tickets.last().succeed()
                closingCompleted = true
            }
            if (status.state == TimetableServiceRunState.COMPLETED) break
        }

        assertTrue(openingCompleted)
        assertTrue(closingCompleted)
        assertTrue(TimetableServiceRunState.DOORS_OPENING in observed)
        assertTrue(TimetableServiceRunState.DWELLING in observed)
        assertTrue(TimetableServiceRunState.DOORS_CLOSING in observed)
        assertTrue(TimetableServiceRunState.READY_TO_DEPART in observed)
        assertTrue(TimetableServiceRunState.DEPARTING in observed)
        assertEquals(2, gateway.requests.size)
        assertEquals(TimetableServiceRunState.COMPLETED, fixture.dispatcher.status(id)?.state)
        assertFalse(fixture.world.simpleAtoWorldData.atoCoordinator.isStalled)
    }

    @Test
    fun `destination keeps doors open until minimum dwell and next at`() {
        val gateway = ControllableOperationGateway()
        val fixture = fixture(
            includeStationAction = true,
            firstTargetOffsetMeters = 40.0,
            nextTargetOffsetMeters = 100.0,
            nextMovementTick = 300L,
            doorSide = DoorSide.LEFT,
            operationGateway = gateway
        )
        val id = TimetableServiceRunId("tt", "service")
        fixture.world.onOwnerForTest { fixture.dispatcher.startService(fixture.world, "tt", "service") } as
            TimetableServiceStartResult.Started
        var openingCompleted = false
        var closeTick: Long? = null

        for (tick in 0 until 1_000) {
            fixture.tick()
            val status = fixture.dispatcher.status(id)!!
            if (status.state == TimetableServiceRunState.DOORS_OPENING && !openingCompleted) {
                gateway.tickets.single().succeed()
                openingCompleted = true
            }
            if (openingCompleted && status.timetableElapsedTicks < 300L) {
                assertFalse(status.state == TimetableServiceRunState.DOORS_CLOSING)
                assertEquals(1, gateway.requests.size)
            }
            if (status.state == TimetableServiceRunState.DOORS_CLOSING) {
                closeTick = status.timetableElapsedTicks
                break
            }
        }

        assertTrue(openingCompleted)
        assertEquals(300L, closeTick)
        assertTrue(gateway.requests.last().operation is TrainOperation.CloseDoors)
    }

    @Test
    fun `ordinary waypoint destination options run doors and minimum dwell`() {
        val gateway = ControllableOperationGateway()
        val options = TimetableAction.DestinationOptions(
            destinationId = "C",
            minimumDwellTicks = 5L,
            stoppingOffsetMetersBefore = 0.0,
            doorSide = DoorSide.BOTH
        )
        val fixture = fixture(
            includeStationAction = false,
            destinationOptions = options,
            firstTargetOffsetMeters = 40.0,
            nextTargetOffsetMeters = 100.0,
            operationGateway = gateway
        )
        val id = TimetableServiceRunId("tt", "service")
        fixture.world.onOwnerForTest { fixture.dispatcher.startService(fixture.world, "tt", "service") } as
            TimetableServiceStartResult.Started
        var openingCompleted = false
        var closingCompleted = false
        var dwellingStartedAt: Long? = null
        var closingStartedAt: Long? = null

        for (tick in 0 until 2_000) {
            fixture.tick()
            val status = fixture.dispatcher.status(id)!!
            if (status.state == TimetableServiceRunState.DOORS_OPENING && !openingCompleted) {
                assertTrue(gateway.requests.single().operation is TrainOperation.OpenDoors)
                gateway.tickets.single().succeed()
                openingCompleted = true
            }
            if (status.state == TimetableServiceRunState.DWELLING && dwellingStartedAt == null) {
                dwellingStartedAt = status.timetableElapsedTicks
            }
            if (status.state == TimetableServiceRunState.DOORS_CLOSING && !closingCompleted) {
                closingStartedAt = status.timetableElapsedTicks
                assertTrue(gateway.requests.last().operation is TrainOperation.CloseDoors)
                gateway.tickets.last().succeed()
                closingCompleted = true
            }
            if (status.state == TimetableServiceRunState.COMPLETED) break
        }

        assertTrue(openingCompleted)
        assertTrue(closingCompleted)
        assertTrue(closingStartedAt!! >= dwellingStartedAt!! + 5L)
        assertEquals(2, gateway.requests.size)
        assertEquals(TimetableServiceRunState.COMPLETED, fixture.dispatcher.status(id)?.state)
        assertEquals(fixture.targetArc, fixture.train.head.location.arcLengthIn(ViewDirection.AB), 0.4)
        assertFalse(fixture.world.simpleAtoWorldData.atoCoordinator.isStalled)
    }

    @Test
    fun `reverse closes open doors then rebases the retained route grant`() {
        val gateway = ControllableOperationGateway()
        val fixture = fixture(
            destinationOptions = TimetableAction.DestinationOptions(
                destinationId = "C",
                minimumDwellTicks = 3L,
                doorSide = DoorSide.LEFT,
                stoppingOffsetMetersBefore = 0.5
            ),
            reverseAfterDestination = true,
            operationGateway = gateway
        )
        val id = TimetableServiceRunId("tt", "service")
        fixture.world.onOwnerForTest { fixture.dispatcher.startService(fixture.world, "tt", "service") } as
            TimetableServiceStartResult.Started
        var reversedWhenCloseRequested: Boolean? = null

        for (tick in 0 until 2_000) {
            fixture.tick()
            val pending = gateway.tickets.lastOrNull()
            if (pending != null && pending.snapshot().state == TrainOperationTicketState.PENDING) {
                if (pending.request.operation is TrainOperation.CloseDoors) {
                    reversedWhenCloseRequested = fixture.train.isReversed
                }
                pending.succeed()
            }
            if (fixture.dispatcher.status(id)?.state == TimetableServiceRunState.COMPLETED) break
        }

        assertEquals(2, gateway.requests.size)
        assertTrue(gateway.requests[0].operation is TrainOperation.OpenDoors)
        assertTrue(gateway.requests[1].operation is TrainOperation.CloseDoors)
        assertEquals(false, reversedWhenCloseRequested)
        assertTrue(fixture.train.isReversed)
        val status = fixture.dispatcher.status(id)!!
        assertEquals(TimetableServiceRunState.COMPLETED, status.state, status.toString())
        assertFalse(
            fixture.world.simpleAtoWorldData.atoCoordinator.isStalled,
            fixture.world.simpleAtoWorldData.atoCoordinator.lastStall.toString()
        )
    }

    @Test
    fun `terminal station reverse rebases the retained route grant before completing`() {
        val fixture = fixture(
            releaseDistanceMetersAfter = 0.0,
            firstTargetOffsetMeters = 40.0,
            includeStationAction = true,
            reverseAfterDestination = true,
            berthStoppingOffsetMetersBefore = 0.5
        )
        val id = TimetableServiceRunId("tt", "service")
        fixture.world.onOwnerForTest { fixture.dispatcher.startService(fixture.world, "tt", "service") } as
            TimetableServiceStartResult.Started

        repeat(2_000) {
            fixture.tick()
            if (fixture.dispatcher.status(id)?.state == TimetableServiceRunState.COMPLETED) {
                return@repeat
            }
        }

        assertTrue(fixture.train.isReversed)
        val status = fixture.dispatcher.status(id)!!
        assertEquals(TimetableServiceRunState.COMPLETED, status.state, status.toString())
        assertFalse(
            fixture.world.simpleAtoWorldData.atoCoordinator.isStalled,
            fixture.world.simpleAtoWorldData.atoCoordinator.lastStall.toString()
        )
    }

    @Test
    fun `halt closes doors before executing model power off action`() {
        val gateway = ControllableOperationGateway()
        val fixture = fixture(
            destinationOptions = TimetableAction.DestinationOptions(
                destinationId = "C",
                minimumDwellTicks = 3L,
                doorSide = DoorSide.LEFT
            ),
            haltAfterDestination = true,
            operationGateway = gateway
        )
        val id = TimetableServiceRunId("tt", "service")
        fixture.world.onOwnerForTest { fixture.dispatcher.startService(fixture.world, "tt", "service") } as
            TimetableServiceStartResult.Started

        for (tick in 0 until 2_000) {
            fixture.tick()
            val pending = gateway.tickets.lastOrNull()
            if (pending != null && pending.snapshot().state == TrainOperationTicketState.PENDING) {
                pending.succeed()
            }
            if (fixture.dispatcher.status(id)?.state == TimetableServiceRunState.COMPLETED) break
        }

        assertEquals(3, gateway.requests.size)
        assertTrue(gateway.requests[0].operation is TrainOperation.OpenDoors)
        assertTrue(gateway.requests[1].operation is TrainOperation.CloseDoors)
        val powerOff = gateway.requests[2].operation as TrainOperation.ExecuteModelAction
        assertEquals("power-off", powerOff.actionName)
        val measured = fixture.dispatcher.status(id)!!.taskTimings.single()
        assertEquals("power-off", measured.taskId)
        assertTrue(measured.endTick!! > measured.startTick, "halt must await physical model ticket completion")
        assertEquals(TimetableServiceRunState.COMPLETED, fixture.dispatcher.status(id)?.state)
        assertFalse(fixture.world.simpleAtoWorldData.atoCoordinator.isStalled)
    }

    @Test
    fun `operation gateway failure logs original cause while preserving failed run state`() {
        val failure = IllegalStateException("gateway broke", IllegalArgumentException("root cause"))
        failure.addSuppressed(IllegalStateException("cleanup detail"))
        val records = java.util.concurrent.CopyOnWriteArrayList<java.util.logging.LogRecord>()
        val logger = java.util.logging.Logger.getLogger(TimetableDispatcher::class.java.name)
        val handler = object : java.util.logging.Handler() {
            override fun publish(record: java.util.logging.LogRecord) { records += record }
            override fun flush() = Unit
            override fun close() = Unit
        }
        logger.addHandler(handler)
        try {
            val fixture = fixture(includeStationAction = true, doorSide = DoorSide.RIGHT,
                operationGateway = object : TrainOperationGateway {
                    override fun execute(request: TrainOperationRequest): TrainOperationExecutionResult = throw failure
                })
            val id = TimetableServiceRunId("tt", "service")
            fixture.world.onOwnerForTest { fixture.dispatcher.startService(fixture.world, "tt", "service") }
            for (tick in 0 until 1_000) {
                fixture.tick()
                if (fixture.dispatcher.status(id)?.state == TimetableServiceRunState.FAILED) break
            }
            assertEquals(TimetableServiceRunState.FAILED, fixture.dispatcher.status(id)?.state)
            val record = records.single { it.thrown === failure }
            assertEquals(java.util.logging.Level.WARNING, record.level)
            assertTrue(record.message.contains("train=${fixture.train.id}"))
            assertTrue(record.message.contains("run="))
            assertEquals("root cause", record.thrown.cause!!.message)
            assertEquals("cleanup detail", record.thrown.suppressed.single().message)
        } finally { logger.removeHandler(handler) }
    }

    @Test
    fun `door operation timeout stalls world and keeps departure hold`() {
        val gateway = ControllableOperationGateway()
        val fixture = fixture(
            includeStationAction = true,
            doorSide = DoorSide.RIGHT,
            operationGateway = gateway,
            operationTimeoutTicks = 3L
        )
        val id = TimetableServiceRunId("tt", "service")
        fixture.world.onOwnerForTest { fixture.dispatcher.startService(fixture.world, "tt", "service") } as
            TimetableServiceStartResult.Started

        for (tick in 0 until 1_000) {
            fixture.tick()
            if (fixture.dispatcher.status(id)?.state ==
                TimetableServiceRunState.FAILED
            ) break
        }

        val status = fixture.dispatcher.status(id)!!
        assertEquals(TimetableServiceRunState.FAILED, status.state)
        assertEquals(TrainOperationTicketState.CANCELLED, status.operationTicketState)
        assertTrue(status.berthHeld)
        assertEquals(0.0, fixture.train.speed)
        assertTrue(status.message!!.contains("timed out"))
        repeat(20) { fixture.tick() }
        assertEquals(TimetableServiceRunState.FAILED, fixture.dispatcher.status(id)?.state)
        assertTrue(fixture.world.simpleAtoWorldData.atoCoordinator.isStalled)
        assertEquals("TrainOperationFailure", fixture.world.simpleAtoWorldData.atoCoordinator.lastStall?.errorType)
    }

    @Test
    fun `ato reset refuses to discard a failed run while Basic resources remain active`() {
        val gateway = ControllableOperationGateway()
        val fixture = fixture(
            includeStationAction = true,
            doorSide = DoorSide.RIGHT,
            operationGateway = gateway,
            operationTimeoutTicks = 3L
        )
        val id = TimetableServiceRunId("tt", "service")
        fixture.world.onOwnerForTest { fixture.dispatcher.startService(fixture.world, "tt", "service") } as
            TimetableServiceStartResult.Started
        for (tick in 0 until 1_000) {
            fixture.tick()
            if (fixture.dispatcher.status(id)?.state == TimetableServiceRunState.FAILED) break
        }

        assertTrue(fixture.world.simpleAtoWorldData.atoCoordinator.isStalled)
        assertTrue(fixture.dispatcher.status(id)?.berthHeld == true)
        assertThrows(IllegalStateException::class.java) {
            fixture.world.onOwnerForTest { fixture.dispatcher.discardFailedRunsForAtoReset(fixture.world) }
        }
        assertTrue(fixture.dispatcher.status(id) != null)
        assertTrue(fixture.train.controller != null)
    }

    @Test
    fun `door operation timeout reports cancellation failure`() {
        val fixture = fixture(
            includeStationAction = true,
            doorSide = DoorSide.RIGHT,
            operationGateway = ThrowingCancelOperationGateway(),
            operationTimeoutTicks = 3L
        )
        val id = TimetableServiceRunId("tt", "service")
        fixture.world.onOwnerForTest { fixture.dispatcher.startService(fixture.world, "tt", "service") } as
            TimetableServiceStartResult.Started

        for (tick in 0 until 1_000) {
            fixture.tick()
            if (fixture.dispatcher.status(id)?.state == TimetableServiceRunState.FAILED) break
        }

        val status = fixture.dispatcher.status(id)!!
        assertEquals(TimetableServiceRunState.FAILED, status.state)
        assertTrue(status.message!!.contains("timed out"))
        assertTrue(status.message!!.contains("cancellation failed"), status.message)
        assertTrue(status.message!!.contains("cancel failed"), status.message)
        assertTrue(fixture.world.simpleAtoWorldData.atoCoordinator.isStalled)
    }

    @Test
    fun `failed close ticket never grants departure`() {
        val gateway = ControllableOperationGateway()
        val fixture = fixture(
            includeStationAction = true,
            firstTargetOffsetMeters = 40.0,
            nextTargetOffsetMeters = 100.0,
            doorSide = DoorSide.BOTH,
            operationGateway = gateway
        )
        val id = TimetableServiceRunId("tt", "service")
        fixture.world.onOwnerForTest { fixture.dispatcher.startService(fixture.world, "tt", "service") } as
            TimetableServiceStartResult.Started
        var closeFailed = false

        for (tick in 0 until 1_500) {
            fixture.tick()
            when (fixture.dispatcher.status(id)?.state) {
                TimetableServiceRunState.DOORS_OPENING ->
                    if (gateway.tickets.single().snapshot().state ==
                        TrainOperationTicketState.PENDING
                    ) gateway.tickets.single().succeed()
                TimetableServiceRunState.DOORS_CLOSING -> if (!closeFailed) {
                    gateway.tickets.last().fail("door obstruction")
                    closeFailed = true
                }
                TimetableServiceRunState.FAILED -> break
                else -> Unit
            }
        }

        val status = fixture.dispatcher.status(id)!!
        assertTrue(closeFailed)
        assertEquals(TimetableServiceRunState.FAILED, status.state)
        assertEquals(TrainOperationTicketState.FAILED, status.operationTicketState)
        assertFalse(status.state == TimetableServiceRunState.DEPARTING)
        assertEquals(0.0, fixture.train.speed)
        assertTrue(status.berthHeld)
        assertTrue(fixture.world.simpleAtoWorldData.atoCoordinator.isStalled)
        assertEquals("TrainOperationFailure", fixture.world.simpleAtoWorldData.atoCoordinator.lastStall?.errorType)
    }

    @Test
    fun `unexpected door ticket cancellation stalls the world`() {
        val gateway = ControllableOperationGateway()
        val fixture = fixture(
            includeStationAction = true,
            doorSide = DoorSide.LEFT,
            operationGateway = gateway
        )
        val id = TimetableServiceRunId("tt", "service")
        fixture.world.onOwnerForTest { fixture.dispatcher.startService(fixture.world, "tt", "service") } as
            TimetableServiceStartResult.Started
        for (tick in 0 until 1_000) {
            fixture.tick()
            if (fixture.dispatcher.status(id)?.state ==
                TimetableServiceRunState.DOORS_OPENING
            ) break
        }
        gateway.tickets.single().cancel()
        assertEquals(
            TrainOperationTicketState.CANCELLED,
            gateway.tickets.single().snapshot().state
        )

        fixture.tick()

        assertTrue(fixture.world.simpleAtoWorldData.atoCoordinator.isStalled)
        assertEquals("TrainOperationFailure", fixture.world.simpleAtoWorldData.atoCoordinator.lastStall?.errorType)
        assertEquals(TimetableServiceRunState.FAILED, fixture.dispatcher.status(id)?.state)
        assertTrue(fixture.dispatcher.status(id)?.berthHeld == true)
    }

    @Test
    fun `manual transfer cancels door ticket but preserves berth ownership`() {
        val gateway = ControllableOperationGateway()
        val fixture = fixture(
            includeStationAction = true,
            doorSide = DoorSide.LEFT,
            operationGateway = gateway
        )
        val id = TimetableServiceRunId("tt", "service")
        fixture.world.onOwnerForTest { fixture.dispatcher.startService(fixture.world, "tt", "service") } as
            TimetableServiceStartResult.Started
        for (tick in 0 until 1_000) {
            fixture.tick()
            if (fixture.dispatcher.status(id)?.state ==
                TimetableServiceRunState.DOORS_OPENING
            ) break
        }
        assertEquals(TimetableServiceRunState.DOORS_OPENING, fixture.dispatcher.status(id)?.state)

        val result = fixture.world.onOwnerForTest { fixture.dispatcher.suspendForControlTransfer(
            fixture.world,
            fixture.train.id,
            "driver-1"
        ) }

        assertTrue(result is TimetableControlTransferResult.Suspended)
        assertEquals(TrainOperationTicketState.CANCELLED, gateway.tickets.single().snapshot().state)
        assertEquals(TimetableServiceRunState.SUSPENDED, fixture.dispatcher.status(id)?.state)
        assertTrue(fixture.dispatcher.status(id)?.berthHeld == true)
        assertNull(fixture.train.controller)
        assertTrue(fixture.world.onOwnerForTest { fixture.dispatcher.releaseControlTransfer(fixture.train.id, "driver-1") })
        assertTrue(
            fixture.world.onOwnerForTest { fixture.dispatcher.replanService(fixture.world, id, "A") } is
                TimetableReplanResult.Failure
        )
        assertTrue(fixture.dispatcher.status(id)?.berthHeld == true)
    }

    @Test
    fun `manual transfer is rejected when a pending door operation cannot be cancelled`() {
        val gateway = ThrowingCancelOperationGateway()
        val fixture = fixture(
            includeStationAction = true,
            doorSide = DoorSide.LEFT,
            operationGateway = gateway
        )
        val id = TimetableServiceRunId("tt", "service")
        fixture.world.onOwnerForTest { fixture.dispatcher.startService(fixture.world, "tt", "service") } as
            TimetableServiceStartResult.Started
        for (tick in 0 until 1_000) {
            fixture.tick()
            if (fixture.dispatcher.status(id)?.state ==
                TimetableServiceRunState.DOORS_OPENING
            ) break
        }
        val ato = fixture.train.controller

        val result = fixture.world.onOwnerForTest { fixture.dispatcher.suspendForControlTransfer(
            fixture.world,
            fixture.train.id,
            "driver-1"
        ) }

        assertTrue(result is TimetableControlTransferResult.Failure)
        assertEquals(TimetableServiceRunState.DOORS_OPENING, fixture.dispatcher.status(id)?.state)
        assertSame(ato, fixture.train.controller)
        assertFalse(fixture.world.simpleAtoWorldData.atoCoordinator.isStalled)
    }

    @Test
    fun `manual transfer may abandon a failed door operation before dispatcher poll`() {
        val gateway = ControllableOperationGateway()
        val fixture = fixture(
            includeStationAction = true,
            doorSide = DoorSide.LEFT,
            operationGateway = gateway
        )
        val id = TimetableServiceRunId("tt", "service")
        fixture.world.onOwnerForTest { fixture.dispatcher.startService(fixture.world, "tt", "service") } as
            TimetableServiceStartResult.Started
        for (tick in 0 until 1_000) {
            fixture.tick()
            if (fixture.dispatcher.status(id)?.state ==
                TimetableServiceRunState.DOORS_OPENING
            ) break
        }
        gateway.tickets.single().fail("door failed before dispatcher poll")

        val transfer = fixture.world.onOwnerForTest { fixture.dispatcher.suspendForControlTransfer(
            fixture.world,
            fixture.train.id,
            "driver-1"
        ) }

        assertTrue(transfer is TimetableControlTransferResult.Suspended)
        assertNull(fixture.train.controller)
        assertEquals(TimetableServiceRunState.SUSPENDED, fixture.dispatcher.status(id)?.state)
        fixture.world.onOwnerForTest { fixture.dispatcher.tick(fixture.world) }
        assertFalse(fixture.world.simpleAtoWorldData.atoCoordinator.isStalled)
        assertEquals(TimetableServiceRunState.SUSPENDED, fixture.dispatcher.status(id)?.state)
    }

    @Test
    fun `manual transfer rejects cancellation confirmation with a changed ticket identity`() {
        val gateway = IdentityChangingCancelOperationGateway()
        val fixture = fixture(
            includeStationAction = true,
            doorSide = DoorSide.LEFT,
            operationGateway = gateway
        )
        val id = TimetableServiceRunId("tt", "service")
        fixture.world.onOwnerForTest { fixture.dispatcher.startService(fixture.world, "tt", "service") } as
            TimetableServiceStartResult.Started
        for (tick in 0 until 1_000) {
            fixture.tick()
            if (gateway.ticket != null) break
        }
        val ato = fixture.train.controller

        val transfer = fixture.world.onOwnerForTest { fixture.dispatcher.suspendForControlTransfer(
            fixture.world,
            fixture.train.id,
            "driver-1"
        ) }

        assertTrue(transfer is TimetableControlTransferResult.Failure)
        assertTrue((transfer as TimetableControlTransferResult.Failure).reason.contains("identity"))
        assertSame(ato, fixture.train.controller)
        assertEquals(TimetableServiceRunState.DOORS_OPENING, fixture.dispatcher.status(id)?.state)
    }

    @Test
    fun `manual transfer is rejected when the operation ticket cannot be inspected`() {
        val fixture = fixture(
            includeStationAction = true,
            doorSide = DoorSide.LEFT,
            operationGateway = ThrowingSnapshotOperationGateway()
        )
        val id = TimetableServiceRunId("tt", "service")
        fixture.world.onOwnerForTest { fixture.dispatcher.startService(fixture.world, "tt", "service") } as
            TimetableServiceStartResult.Started
        for (tick in 0 until 1_000) {
            fixture.tick()
            if (fixture.dispatcher.status(id)?.state ==
                TimetableServiceRunState.DOORS_OPENING
            ) break
        }
        val ato = fixture.train.controller
        val status = fixture.dispatcher.status(id)!!
        assertTrue(status.operationTicketError?.contains("snapshot failed") == true)

        val result = fixture.world.onOwnerForTest { fixture.dispatcher.suspendForControlTransfer(
            fixture.world,
            fixture.train.id,
            "driver-1"
        ) }

        assertTrue(result is TimetableControlTransferResult.Failure)
        assertTrue((result as TimetableControlTransferResult.Failure).reason.contains("snapshot"))
        assertEquals(TimetableServiceRunState.DOORS_OPENING, fixture.dispatcher.status(id)?.state)
        assertSame(ato, fixture.train.controller)
        assertFalse(fixture.world.simpleAtoWorldData.atoCoordinator.isStalled)
    }

    @Test
    fun `berth ownership survives mission replacement until the old tail clears`() {
        val fixture = fixture(
            releaseDistanceMetersAfter = 55.0,
            firstTargetOffsetMeters = 60.0,
            nextTargetOffsetMeters = 110.0
        )
        val id = TimetableServiceRunId("tt", "service")
        val started = fixture.world.onOwnerForTest { fixture.dispatcher.startService(fixture.world, "tt", "service") }
            as TimetableServiceStartResult.Started
        fixture.dispatcher.scheduleStationCall(
            fixture.world,
            id,
            StationCall(
                callId = "cross-mission",
                missionRevision = started.run.missionRevision,
                stationId = fixture.station.id,
                berthRef = fixture.berth.berthRef,
                dwellPlan = DwellPlan(0L)
            )
        )

        var observedNewMissionWhileHeld = false
        var observedRelease = false
        for (tick in 0 until 2_000) {
            fixture.tick()
            val status = fixture.dispatcher.status(id)!!
            if (status.missionRevision > started.run.missionRevision && status.berthHeld) {
                observedNewMissionWhileHeld = true
            }
            if (observedNewMissionWhileHeld && !status.berthHeld) observedRelease = true
            if (status.state == TimetableServiceRunState.COMPLETED && observedRelease) break
        }

        assertTrue(observedNewMissionWhileHeld)
        assertTrue(observedRelease)
        assertEquals(TimetableServiceRunState.COMPLETED, fixture.dispatcher.status(id)?.state)
        assertFalse(fixture.world.simpleAtoWorldData.atoCoordinator.isStalled)
    }

    @Test
    fun `dwell may depart after the conflicting turnout resource tail releases`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
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
        val data = BasicWorldData()
        TestSimpleATOWorldDataRegistry.register(world, data)
        val approach = rail(world, Vector3d(0.0, 0.0, 0.0), Vector3d(100.0, 0.0, 0.0))
        val mainBranch = rail(world, Vector3d(100.0, 0.0, 0.0), Vector3d(150.0, 0.0, 0.0))
        val blockingBranch = rail(world, Vector3d(100.0, 0.0, 0.0), Vector3d(150.0, 0.0, 20.0))
        IsolatedSimulationThreadMarker.run { CoreRail.connect(approach, mainBranch) }
        IsolatedSimulationThreadMarker.run { CoreRail.connect(approach, blockingBranch) }
        val mainTrain = createTrainWithConfig("main", approach, world, listOf(10.0), carIdPrefix = "main" + "/car-")
        val blockingTrain = createTrainWithConfig("blocking", approach, world, listOf(10.0), carIdPrefix = "blocking" + "/car-")
        world.onOwnerForTest {
            blockingTrain.setLocation(mainTrain.head.location.forward(60.0)!!)
            blockingTrain.commit()
        }
        data.wayPoints.add(WayPoint("M0", approach.id, mainTrain.head.location.arcLengthIn(ViewDirection.AB)))
        data.wayPoints.add(WayPoint("P", approach.id, 70.0))
        data.wayPoints.add(WayPoint("M1", mainBranch.id, 20.0))
        data.wayPoints.add(WayPoint("B0", approach.id, blockingTrain.head.location.arcLengthIn(ViewDirection.AB)))
        data.wayPoints.add(WayPoint("B1", blockingBranch.id, 20.0))
        world.simpleAtoWorldData.timetables.add(
            Timetable(
                "tt",
                services = listOf(
                    service("blocking", blockingTrain.id, "B0", target = "B1", tick = 0L),
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
        val stationId = StationId("central")
        val berthRef = StationBerthRef(stationId, "one")
        val berth = StationBerth(
            berthRef,
            mainTrain.head.location.forward(40.0)!!,
            approachDirection = ViewDirection.AB,
            usableLengthMeters = 100.0
        )
        world.simpleAtoWorldData.stations.register(Station(stationId, "Central", listOf(berthRef)), listOf(berth))
        val blockerId = TimetableServiceRunId("tt", "blocking")
        val mainId = TimetableServiceRunId("tt", "main")
        world.onOwnerForTest { dispatcher.startService(world, "tt", "blocking") } as TimetableServiceStartResult.Started
        val blockingAto = blockingTrain.controller as SimpleATO
        for (ignored in 0 until 2_000) {
            tick(blockingAto)
            world.onOwnerForTest { dispatcher.tick(world) }
            if (dispatcher.status(blockerId)?.routeGrantActive == true) break
        }
        val mainRun = world.onOwnerForTest { dispatcher.startService(world, "tt", "main") } as TimetableServiceStartResult.Started
        dispatcher.scheduleStationCall(
            world,
            mainId,
            call("main-call", mainRun.run.missionRevision, stationId, berthRef)
        )
        val mainAto = mainTrain.controller as SimpleATO

        val observed = linkedSetOf<TimetableServiceRunState>()
        var readyWaitTicks = 0
        for (tick in 0 until 1_000) {
            tick(mainAto)
            world.onOwnerForTest { dispatcher.tick(world) }
            val state = dispatcher.status(mainId)!!.state
            observed += state
            if (state == TimetableServiceRunState.READY_TO_DEPART) readyWaitTicks++
            if (readyWaitTicks >= 20) break
        }

        assertTrue(
            TimetableServiceRunState.BERTHED in observed,
            "observed=$observed main=${dispatcher.status(mainId)} blocker=${dispatcher.status(blockerId)}"
        )
        assertTrue(TimetableServiceRunState.DWELLING in observed)
        assertTrue(TimetableServiceRunState.READY_TO_DEPART in observed)
        assertTrue(TimetableServiceRunState.DEPARTING in observed)
        assertEquals(TimetableServiceRunState.WAITING_ROUTE, dispatcher.status(mainId)?.state)
        assertEquals(0.0, mainTrain.speed)
        assertTrue(dispatcher.status(mainId)?.berthHeld == true)
        assertTrue(dispatcher.status(blockerId)?.routeGrantActive == true)
        assertFalse(world.simpleAtoWorldData.atoCoordinator.isStalled)
    }

    @Test
    fun `two explicit calls to the same berth submit without an exclusive station gate`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
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
        val data = BasicWorldData()
        TestSimpleATOWorldDataRegistry.register(world, data)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(180.0, segmentCount = 1)
        )
        val firstTrain = createTrainWithConfig("T1", rail, world, listOf(10.0), carIdPrefix = "T1" + "/car-")
        val secondTrain = createTrainWithConfig("T2", rail, world, listOf(10.0), carIdPrefix = "T2" + "/car-")
        world.onOwnerForTest {
            secondTrain.setLocation(firstTrain.head.location.forward(20.0)!!)
            secondTrain.commit()
        }
        data.wayPoints.add(WayPoint("A1", rail.id, firstTrain.head.location.arcLengthIn(ViewDirection.AB)))
        data.wayPoints.add(WayPoint("A2", rail.id, secondTrain.head.location.arcLengthIn(ViewDirection.AB)))
        data.wayPoints.add(WayPoint("C", rail.id, 160.0))
        world.simpleAtoWorldData.timetables.add(
            Timetable(
                id = "tt",
                services = listOf(
                    service("first", firstTrain.id, "A1", via = listOf("A2")),
                    service("second", secondTrain.id, "A2")
                )
            )
        )
        val stationId = StationId("central")
        val berthRef = StationBerthRef(stationId, "one")
        val berth = StationBerth(
            berthRef,
            firstTrain.head.location.forward(60.0)!!,
            approachDirection = ViewDirection.AB,
            usableLengthMeters = 100.0
        )
        world.simpleAtoWorldData.stations.register(Station(stationId, "Central", listOf(berthRef)), listOf(berth))
        val firstId = TimetableServiceRunId("tt", "first")
        val secondId = TimetableServiceRunId("tt", "second")
        val firstRun = world.onOwnerForTest { dispatcher.startService(world, "tt", "first") } as TimetableServiceStartResult.Started
        val secondRun = world.onOwnerForTest { dispatcher.startService(world, "tt", "second") } as TimetableServiceStartResult.Started

        assertTrue(
            dispatcher.scheduleStationCall(
                world,
                firstId,
                call("first-call", firstRun.run.missionRevision, stationId, berthRef)
            ) is TimetableStationCallScheduleResult.Scheduled
        )
        assertTrue(
            dispatcher.scheduleStationCall(
                world,
                secondId,
                call("second-call", secondRun.run.missionRevision, stationId, berthRef)
            ) is TimetableStationCallScheduleResult.Scheduled
        )
        tick(firstTrain.controller as SimpleATO)
        tick(secondTrain.controller as SimpleATO)
        world.onOwnerForTest { dispatcher.tick(world) }

        assertTrue(dispatcher.status(firstId)?.berthHeld == true)
        assertTrue(dispatcher.status(secondId)?.berthHeld == true)
        assertTrue(dispatcher.saveCheckpoint().runs.all { it.stationCallSubmittedToAto == true })
        assertEquals(0.0, secondTrain.speed, 1.0e-9)

        assertTrue(world.onOwnerForTest { dispatcher.stopService(world, firstId) })
        tick(firstTrain.controller as SimpleATO)
        world.onOwnerForTest { dispatcher.tick(world) }
        assertTrue(dispatcher.status(firstId)?.berthHeld == true)
        assertFalse(world.simpleAtoWorldData.atoCoordinator.isStalled)
    }

    private fun service(
        id: String,
        trainId: String,
        origin: String,
        target: String = "C",
        tick: Long = 0L,
        via: List<String> = emptyList()
    ): TimetableService =
        TimetableService(
            id = id,
            trainSelector = TrainSelector.TrainId(trainId),
            steps = listOf(
                TimetableStep.Check(origin),
                TimetableStep.At(tick, if (via.isEmpty()) TimetableAction.GoTo(target) else TimetableAction.GoVia(via + target))
            )
        )

    private fun call(
        id: String,
        revision: Long,
        stationId: StationId,
        berthRef: StationBerthRef
    ): StationCall = StationCall(
        callId = id,
        missionRevision = revision,
        stationId = stationId,
        berthRef = berthRef,
        dwellPlan = DwellPlan(5L)
    )

    private fun tick(ato: SimpleATO) {
        AsyncTickTestSupport.run { ato.tick(50.0) }
        ato.commit()
    }

    private fun fixture(
        releaseDistanceMetersAfter: Double = 5.0,
        firstTargetOffsetMeters: Double = 100.0,
        nextTargetOffsetMeters: Double? = null,
        includeStationAction: Boolean = false,
        stopPrograms: StationStopPrograms? = null,
        originPrograms: StationStopPrograms? = null,
        destinationOptions: TimetableAction.DestinationOptions? = null,
        haltAfterDestination: Boolean = false,
        reverseAfterDestination: Boolean = false,
        storeAfterDestination: Boolean = false,
        originProgramTick: Long = 0L,
        doorSide: DoorSide = DoorSide.NONE,
        operationGateway: TrainOperationGateway = TrainOperationGateway {
            TrainOperationExecutionResult.Rejected("not configured in this fixture")
        },
        operationTimeoutTicks: Long = 100L,
        stationMovementTick: Long = 0L,
        nextMovementTick: Long = 1L,
        berthStoppingOffsetMetersBefore: Double = 0.0,
        initialActions: List<TimetableAction> = emptyList(),
        afterDestinationActions: List<TimetableAction> = emptyList(),
        canonicalOriginDoorSide: DoorSide? = null,
        canonicalTarget: Boolean = false
    ): Fixture {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        val inspectionRuntime = emptyInspectionRuntime()
        val dispatcher = TimetableDispatcher(
            authorityStoppingMarginMeters = profile.safetyDistanceMeters,
            routeAdmissionContextProvider = GrantingRouteAdmissionTestContext { world },
            signalRestrictionSnapshots = emptyTrainRestrictionSnapshots,
            inspectionRuntime = inspectionRuntime,
            infrastructureDependencyService = acceptingInfrastructureDependencyService(),
            operationGateway = operationGateway,
            operationTimeoutTicks = operationTimeoutTicks,
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
        val data = BasicWorldData()
        TestSimpleATOWorldDataRegistry.register(world, data)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(180.0, segmentCount = 1)
        )
        val train = createTrainWithConfig("T1", rail, world, listOf(10.0), carIdPrefix = "T1" + "/car-")
        val originArc = train.head.location.arcLength
        val firstTargetArc = originArc + firstTargetOffsetMeters
        val targetArc = originArc + (nextTargetOffsetMeters ?: firstTargetOffsetMeters)
        val originId = if (canonicalOriginDoorSide == null) "A" else "origin@one"
        val targetId = if (canonicalTarget) "central@one" else "C"
        if (canonicalOriginDoorSide == null) data.wayPoints.add(WayPoint("A", rail.id, originArc))
        else work.art1st.javarailways.simpleato.api.destination.OperationalDestinationService(world).register(
            work.art1st.javarailways.simpleato.api.destination.OperationalDestination(originId, rail.id,
                originArc, ViewDirection.AB, work.art1st.javarailways.simpleato.api.destination.DestinationRole.StationBerthRole(
                    StationId("origin"), berthId = "one", doorSide = canonicalOriginDoorSide)))
        if (!canonicalTarget) data.wayPoints.add(WayPoint("C", rail.id, firstTargetArc))
        else work.art1st.javarailways.simpleato.api.destination.OperationalDestinationService(world).register(
            work.art1st.javarailways.simpleato.api.destination.OperationalDestination(targetId, rail.id,
                firstTargetArc, ViewDirection.AB, work.art1st.javarailways.simpleato.api.destination.DestinationRole.StationBerthRole(
                    StationId("central"), berthId = "one", doorSide = doorSide)))
        if (nextTargetOffsetMeters != null) {
            data.wayPoints.add(WayPoint("D", rail.id, targetArc))
        }
        world.simpleAtoWorldData.timetables.add(
            Timetable(
                id = "tt",
                services = listOf(
                    TimetableService(
                        id = "service",
                        trainSelector = if (storeAfterDestination) TrainSelector.AnyTrain else TrainSelector.TrainId(train.id),
                        steps = buildList {
                            if (storeAfterDestination) add(TimetableStep.At(0L, TimetableAction.TakeFromWaypoint(listOf("A"))))
                            else add(TimetableStep.Check(originId))
                            initialActions.forEach { add(TimetableStep.At(0L, it)) }
                            originPrograms?.let { add(TimetableStep.At(originProgramTick, TimetableAction.BeginStationStop(it))) }
                            if (includeStationAction) {
                                add(
                                    TimetableStep.At(
                                        0L,
                                        TimetableAction.CallStation(
                                            stationId = "central",
                                            berthId = "one",
                                            minimumDwellTicks = 5L,
                                            stopPrograms = stopPrograms
                                        )
                                    )
                                )
                            }
                            destinationOptions?.let {
                                add(TimetableStep.At(stationMovementTick, it))
                            }
                            add(TimetableStep.At(stationMovementTick, TimetableAction.GoTo(targetId)))
                            afterDestinationActions.forEach { add(TimetableStep.At(stationMovementTick, it)) }
                            if (reverseAfterDestination) {
                                add(TimetableStep.At(stationMovementTick, TimetableAction.Reverse))
                            }
                            when {
                                nextTargetOffsetMeters != null ->
                                    add(TimetableStep.At(nextMovementTick, TimetableAction.GoTo("D")))
                                haltAfterDestination -> {
                                    add(TimetableStep.At(stationMovementTick, TimetableAction.TaskStart("power-off")))
                                    add(TimetableStep.At(stationMovementTick, TimetableAction.Halt))
                                    add(TimetableStep.At(stationMovementTick, TimetableAction.TaskEnd("power-off")))
                                }
                                storeAfterDestination -> add(TimetableStep.At(stationMovementTick,
                                    TimetableAction.StoreAtDepot(work.art1st.javarailways.simpleato.api.depot.DepotId("storage"))))
                            }
                        }
                    )
                )
            )
        )
        val stationId = StationId("central")
        val berthRef = StationBerthRef(stationId, "one")
        val berth = StationBerth(
            berthRef = berthRef,
            location = train.head.location.forward(40.0)!!,
            approachDirection = ViewDirection.AB,
            usableLengthMeters = 100.0,
            defaultDwellTicks = 5L,
            releaseDistanceMetersAfter = releaseDistanceMetersAfter,
            stoppingOffsetMetersBefore = berthStoppingOffsetMetersBefore,
            doorSide = doorSide
        )
        val station = Station(stationId, "Central", listOf(berthRef))
        if (!canonicalTarget) world.simpleAtoWorldData.stations.register(station, listOf(berth))
        if (storeAfterDestination) {
            work.art1st.javarailways.simpleato.api.destination.OperationalDestinationService(world).register(
                work.art1st.javarailways.simpleato.api.destination.OperationalDestination("storage@one",rail.id,
                    originArc+150.0, ViewDirection.AB,
                    work.art1st.javarailways.simpleato.api.destination.DestinationRole.DepotStablingPointRole("storage","one",usableLengthMeters=30.0)))
        }
        return Fixture(world, data, dispatcher, train, station, berth, targetArc)
    }

    private fun rail(
        world: work.art1st.javarailways.core.physics.CoreWorld,
        from: Vector3d,
        to: Vector3d
    ): CoreRail = world.createRailForTest(straightCurve(from, to))

    private fun straightCurve(from: Vector3d, to: Vector3d): PrimeCurve {
        val tangent = Vector3d(to).sub(from).normalize()
        val normal = Vector3d(0.0, 1.0, 0.0)
        val length = from.distance(to)
        return PrimeCurve.fromPoints(
            listOf(
                Curve.RawPoint(from, tangent, normal, 0.0, Curve.SegmentType.LINE_START),
                Curve.RawPoint(to, tangent, normal, length, Curve.SegmentType.LINE_END)
            )
        )
    }

    private class ThrowingCancelOperationGateway : TrainOperationGateway {
        override fun execute(request: TrainOperationRequest): TrainOperationExecutionResult =
            TrainOperationExecutionResult.Accepted(object : TrainOperationTicket {
                override val request: TrainOperationRequest = request

                override fun snapshot(): TrainOperationTicketSnapshot =
                    TrainOperationTicketSnapshot(
                        ticketId = "throwing:${request.requestId}",
                        requestId = request.requestId,
                        state = TrainOperationTicketState.PENDING
                    )

                override fun cancel() {
                    error("cancel failed")
                }
            })
    }

    private class IdentityChangingCancelOperationGateway : TrainOperationGateway {
        var ticket: TrainOperationTicket? = null
            private set

        override fun execute(request: TrainOperationRequest): TrainOperationExecutionResult {
            val created = object : TrainOperationTicket {
                override val request: TrainOperationRequest = request
                private var state = TrainOperationTicketState.PENDING
                private var changedIdentity = false

                override fun snapshot(): TrainOperationTicketSnapshot =
                    TrainOperationTicketSnapshot(
                        ticketId = if (changedIdentity) {
                            "changed:${request.requestId}"
                        } else {
                            "stable:${request.requestId}"
                        },
                        requestId = request.requestId,
                        state = state
                    )

                override fun cancel() {
                    state = TrainOperationTicketState.CANCELLED
                    changedIdentity = true
                }
            }
            ticket = created
            return TrainOperationExecutionResult.Accepted(created)
        }
    }

    private class ThrowingSnapshotOperationGateway : TrainOperationGateway {
        override fun execute(request: TrainOperationRequest): TrainOperationExecutionResult =
            TrainOperationExecutionResult.Accepted(object : TrainOperationTicket {
                override val request: TrainOperationRequest = request
                private var snapshotCount = 0

                override fun snapshot(): TrainOperationTicketSnapshot {
                    snapshotCount++
                    if (snapshotCount > 1) error("snapshot failed")
                    return TrainOperationTicketSnapshot(
                        ticketId = "throwing:${request.requestId}",
                        requestId = request.requestId,
                        state = TrainOperationTicketState.PENDING
                    )
                }

                override fun cancel() = Unit
            })
    }

    private class ControllableOperationGateway : TrainOperationGateway {
        val requests = ArrayList<TrainOperationRequest>()
        val tickets = ArrayList<ControllableOperationTicket>()
        val durationRequests = ArrayList<Pair<String, DoorSide>>()
        var duration: java.util.concurrent.CompletableFuture<Long>? = null
        override fun estimateDoorCloseDurationTicks(trainId: String, side: DoorSide): java.util.concurrent.CompletableFuture<Long> {
            durationRequests += trainId to side
            return duration ?: java.util.concurrent.CompletableFuture.failedFuture(IllegalStateException("duration not configured in test"))
        }

        override fun execute(request: TrainOperationRequest): TrainOperationExecutionResult {
            requests += request
            val ticket = ControllableOperationTicket(request)
            tickets += ticket
            return TrainOperationExecutionResult.Accepted(ticket)
        }
    }

    private class ControllableOperationTicket(
        override val request: TrainOperationRequest
    ) : TrainOperationTicket {
        private var state = TrainOperationTicketState.PENDING
        private var failure: String? = null

        fun succeed() {
            if (state == TrainOperationTicketState.PENDING) {
                state = TrainOperationTicketState.SUCCEEDED
            }
        }

        fun fail(reason: String) {
            if (state == TrainOperationTicketState.PENDING) {
                state = TrainOperationTicketState.FAILED
                failure = reason
            }
        }

        override fun snapshot(): TrainOperationTicketSnapshot = TrainOperationTicketSnapshot(
            ticketId = "test:${request.requestId}",
            requestId = request.requestId,
            state = state,
            message = failure
        )

        override fun cancel() {
            if (state == TrainOperationTicketState.PENDING) {
                state = TrainOperationTicketState.CANCELLED
            }
        }
    }

    private inner class Fixture(
        val world: work.art1st.javarailways.core.physics.CoreWorld,
        val data: BasicWorldData,
        val dispatcher: TimetableDispatcher,
        val train: CoreTrain,
        val station: Station,
        val berth: StationBerth,
        val targetArc: Double
    ) {
        fun tick() {
            val ato = train.controller as SimpleATO
            AsyncTickTestSupport.run { ato.tick(50.0) }
            ato.commit()
            world.onOwnerForTest { dispatcher.tick(world) }
        }
    }
}
