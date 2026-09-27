package work.art1st.javarailways.simpleato.timetable

import work.art1st.javarailways.simpleato.simpleAtoWorldData

import work.art1st.javarailways.core.physics.createRailForTest

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.BasicWorldData
import work.art1st.javarailways.basic.WayPoint
import work.art1st.javarailways.basic.api.ato.AtoSafetyProfile
import work.art1st.javarailways.basic.api.ato.AtoWorldCoordinator
import work.art1st.javarailways.basic.api.ato.KinematicTrainActuator
import work.art1st.javarailways.basic.api.operation.TrainOperation
import work.art1st.javarailways.basic.api.operation.TrainOperationExecutionResult
import work.art1st.javarailways.basic.api.operation.TrainOperationGateway
import work.art1st.javarailways.basic.api.operation.TrainOperationRequest
import work.art1st.javarailways.basic.api.operation.TrainOperationTicket
import work.art1st.javarailways.basic.api.operation.TrainOperationTicketSnapshot
import work.art1st.javarailways.basic.api.operation.TrainOperationTicketState
import work.art1st.javarailways.basic.api.station.DoorSide
import work.art1st.javarailways.basic.api.station.DwellPlan
import work.art1st.javarailways.basic.api.station.Station
import work.art1st.javarailways.basic.api.station.StationBerth
import work.art1st.javarailways.basic.api.station.StationBerthRef
import work.art1st.javarailways.basic.api.station.StationCall
import work.art1st.javarailways.basic.api.station.StationId
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
import work.art1st.javarailways.core.physics.train.createTestWorld
import work.art1st.javarailways.core.physics.train.createTrainWithConfig
import work.art1st.javarailways.core.util.AsyncTickTestSupport
import work.art1st.javarailways.core.util.ThreadGuard
import work.art1st.javarailways.core.util.ThreadGuardPlatformBridge

class TimetableStationCallDispatcherTest {
    private val worlds = mutableListOf<work.art1st.javarailways.core.physics.CoreWorld>()

    private val profile = AtoSafetyProfile(
        accelerationMetersPerSecondSquared = 1.0,
        serviceDecelerationMetersPerSecondSquared = 1.0,
        reactionTimeSeconds = 0.0,
        safetyDistanceMeters = 1.0,
        stopToleranceMeters = 0.25
    )

    @BeforeEach
    fun installMainThreadBridge() {
        ThreadGuard.installPlatformBridge(object : ThreadGuardPlatformBridge {
            override fun isMainThread(): Boolean = true
        })
    }

    @AfterEach
    fun unregisterWorlds() {
        worlds.forEach(TestSimpleATOWorldDataRegistry::unregister)
        worlds.clear()
        ThreadGuard.clearPlatformBridge()
    }

    @Test
    fun `dispatcher dwells grants departure and releases berth behind the tail`() {
        val fixture = fixture()
        val id = TimetableServiceRunId("tt", "service")
        val started = fixture.dispatcher.startService(fixture.world, id.timetableId, id.serviceId)
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
        assertEquals(TimetableServiceRunState.WAITING_BERTH, fixture.dispatcher.status(id)?.state)
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
        assertFalse(fixture.data.atoCoordinator.isStalled)
    }

    @Test
    fun `terminal station navigation completes while remaining berthed`() {
        val fixture = fixture(includeStationAction = true, firstTargetOffsetMeters = 40.0)
        val id = TimetableServiceRunId("tt", "service")
        fixture.dispatcher.startService(fixture.world, "tt", "service") as
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
        fixture.dispatcher.startService(fixture.world, "tt", "service") as
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
        assertFalse(fixture.data.atoCoordinator.isStalled)
    }

    @Test
    fun `timetable station action schedules the call with its following movement`() {
        val fixture = fixture(includeStationAction = true)
        val id = TimetableServiceRunId("tt", "service")

        val started = fixture.dispatcher.startService(fixture.world, "tt", "service")
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
        assertFalse(fixture.data.atoCoordinator.isStalled)
    }

    @Test
    fun `waypoint progress observer reports actual stop time`() {
        val fixture = fixture(firstTargetOffsetMeters = 40.0)
        val id = TimetableServiceRunId("tt", "service")
        val observed = ArrayList<TimetableWaypointProgress>()
        val subscription = fixture.dispatcher.observeWaypointProgress(id) { observed += it }
        try {
            fixture.dispatcher.startService(fixture.world, "tt", "service") as
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
        val started = fixture.dispatcher.startService(fixture.world, "tt", "service") as
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
        assertFalse(fixture.data.atoCoordinator.isStalled)
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
        fixture.dispatcher.startService(fixture.world, "tt", "service") as
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
        assertFalse(fixture.data.atoCoordinator.isStalled)
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
        fixture.dispatcher.startService(fixture.world, "tt", "service") as
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
        fixture.dispatcher.startService(fixture.world, "tt", "service") as
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
        assertFalse(fixture.data.atoCoordinator.isStalled)
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
        fixture.dispatcher.startService(fixture.world, "tt", "service") as
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
            fixture.data.atoCoordinator.isStalled,
            fixture.data.atoCoordinator.lastStall.toString()
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
        fixture.dispatcher.startService(fixture.world, "tt", "service") as
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
            fixture.data.atoCoordinator.isStalled,
            fixture.data.atoCoordinator.lastStall.toString()
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
        fixture.dispatcher.startService(fixture.world, "tt", "service") as
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
        assertEquals(TimetableServiceRunState.COMPLETED, fixture.dispatcher.status(id)?.state)
        assertFalse(fixture.data.atoCoordinator.isStalled)
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
        fixture.dispatcher.startService(fixture.world, "tt", "service") as
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
        assertTrue(fixture.data.atoCoordinator.isStalled)
        assertEquals("TrainOperationFailure", fixture.data.atoCoordinator.lastStall?.errorType)
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
        fixture.dispatcher.startService(fixture.world, "tt", "service") as
            TimetableServiceStartResult.Started
        for (tick in 0 until 1_000) {
            fixture.tick()
            if (fixture.dispatcher.status(id)?.state == TimetableServiceRunState.FAILED) break
        }

        assertTrue(fixture.data.atoCoordinator.isStalled)
        assertTrue(fixture.dispatcher.status(id)?.berthHeld == true)
        assertThrows(IllegalStateException::class.java) {
            fixture.dispatcher.discardFailedRunsForAtoReset(fixture.world)
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
        fixture.dispatcher.startService(fixture.world, "tt", "service") as
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
        assertTrue(fixture.data.atoCoordinator.isStalled)
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
        fixture.dispatcher.startService(fixture.world, "tt", "service") as
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
        assertTrue(fixture.data.atoCoordinator.isStalled)
        assertEquals("TrainOperationFailure", fixture.data.atoCoordinator.lastStall?.errorType)
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
        fixture.dispatcher.startService(fixture.world, "tt", "service") as
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

        assertTrue(fixture.data.atoCoordinator.isStalled)
        assertEquals("TrainOperationFailure", fixture.data.atoCoordinator.lastStall?.errorType)
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
        fixture.dispatcher.startService(fixture.world, "tt", "service") as
            TimetableServiceStartResult.Started
        for (tick in 0 until 1_000) {
            fixture.tick()
            if (fixture.dispatcher.status(id)?.state ==
                TimetableServiceRunState.DOORS_OPENING
            ) break
        }
        assertEquals(TimetableServiceRunState.DOORS_OPENING, fixture.dispatcher.status(id)?.state)

        val result = fixture.dispatcher.suspendForControlTransfer(
            fixture.world,
            fixture.train.id,
            "driver-1"
        )

        assertTrue(result is TimetableControlTransferResult.Suspended)
        assertEquals(TrainOperationTicketState.CANCELLED, gateway.tickets.single().snapshot().state)
        assertEquals(TimetableServiceRunState.SUSPENDED, fixture.dispatcher.status(id)?.state)
        assertTrue(fixture.dispatcher.status(id)?.berthHeld == true)
        assertNull(fixture.train.controller)
        assertTrue(fixture.dispatcher.releaseControlTransfer(fixture.train.id, "driver-1"))
        assertTrue(
            fixture.dispatcher.replanService(fixture.world, id, "A") is
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
        fixture.dispatcher.startService(fixture.world, "tt", "service") as
            TimetableServiceStartResult.Started
        for (tick in 0 until 1_000) {
            fixture.tick()
            if (fixture.dispatcher.status(id)?.state ==
                TimetableServiceRunState.DOORS_OPENING
            ) break
        }
        val ato = fixture.train.controller

        val result = fixture.dispatcher.suspendForControlTransfer(
            fixture.world,
            fixture.train.id,
            "driver-1"
        )

        assertTrue(result is TimetableControlTransferResult.Failure)
        assertEquals(TimetableServiceRunState.DOORS_OPENING, fixture.dispatcher.status(id)?.state)
        assertSame(ato, fixture.train.controller)
        assertFalse(fixture.data.atoCoordinator.isStalled)
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
        fixture.dispatcher.startService(fixture.world, "tt", "service") as
            TimetableServiceStartResult.Started
        for (tick in 0 until 1_000) {
            fixture.tick()
            if (fixture.dispatcher.status(id)?.state ==
                TimetableServiceRunState.DOORS_OPENING
            ) break
        }
        gateway.tickets.single().fail("door failed before dispatcher poll")

        val transfer = fixture.dispatcher.suspendForControlTransfer(
            fixture.world,
            fixture.train.id,
            "driver-1"
        )

        assertTrue(transfer is TimetableControlTransferResult.Suspended)
        assertNull(fixture.train.controller)
        assertEquals(TimetableServiceRunState.SUSPENDED, fixture.dispatcher.status(id)?.state)
        fixture.dispatcher.tick(fixture.world)
        assertFalse(fixture.data.atoCoordinator.isStalled)
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
        fixture.dispatcher.startService(fixture.world, "tt", "service") as
            TimetableServiceStartResult.Started
        for (tick in 0 until 1_000) {
            fixture.tick()
            if (gateway.ticket != null) break
        }
        val ato = fixture.train.controller

        val transfer = fixture.dispatcher.suspendForControlTransfer(
            fixture.world,
            fixture.train.id,
            "driver-1"
        )

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
        fixture.dispatcher.startService(fixture.world, "tt", "service") as
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

        val result = fixture.dispatcher.suspendForControlTransfer(
            fixture.world,
            fixture.train.id,
            "driver-1"
        )

        assertTrue(result is TimetableControlTransferResult.Failure)
        assertTrue((result as TimetableControlTransferResult.Failure).reason.contains("snapshot"))
        assertEquals(TimetableServiceRunState.DOORS_OPENING, fixture.dispatcher.status(id)?.state)
        assertSame(ato, fixture.train.controller)
        assertFalse(fixture.data.atoCoordinator.isStalled)
    }

    @Test
    fun `berth ownership survives mission replacement until the old tail clears`() {
        val fixture = fixture(
            releaseDistanceMetersAfter = 55.0,
            firstTargetOffsetMeters = 60.0,
            nextTargetOffsetMeters = 110.0
        )
        val id = TimetableServiceRunId("tt", "service")
        val started = fixture.dispatcher.startService(fixture.world, "tt", "service")
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
        assertFalse(fixture.data.atoCoordinator.isStalled)
    }

    @Test
    fun `dwell may depart after the conflicting turnout resource tail releases`() {
        val world = createTestWorld().also(worlds::add)
        val inspectionRuntime = emptyInspectionRuntime()
        val dispatcher = TimetableDispatcher(
            routeAdmissionContextProvider = GrantingRouteAdmissionTestContext { world },
            signalRestrictionSnapshots = emptyTrainRestrictionSnapshots,
            inspectionRuntime = inspectionRuntime,
            appliedPlanService = acceptingTimetableAppliedPlanService(),
            atoFactory = TimetableAtoFactory { train, owningWorld ->
                SimpleATO(
                    train,
                    owningWorld,
                    KinematicTrainActuator(8.0, profile),
                    profile,
                    inspectionRuntime::maximumDeclaredDistanceMeters
                )
            }
        )
        val data = BasicWorldData(
            atoCoordinator = AtoWorldCoordinator("station-route-wait", reportSink = {})
        )
        TestSimpleATOWorldDataRegistry.register(world, data)
        val approach = rail(world, Vector3d(0.0, 0.0, 0.0), Vector3d(100.0, 0.0, 0.0))
        val mainBranch = rail(world, Vector3d(100.0, 0.0, 0.0), Vector3d(150.0, 0.0, 0.0))
        val blockingBranch = rail(world, Vector3d(100.0, 0.0, 0.0), Vector3d(150.0, 0.0, 20.0))
        CoreRail.connect(approach, mainBranch)
        CoreRail.connect(approach, blockingBranch)
        val mainTrain = createTrainWithConfig("main", approach, world, listOf(10.0))
        val blockingTrain = createTrainWithConfig("blocking", approach, world, listOf(10.0))
        blockingTrain.setLocation(mainTrain.head.location.forward(60.0)!!)
        blockingTrain.commit()
        data.wayPoints.add(WayPoint("M0", "M0", approach.id, mainTrain.head.location.arcLengthIn(ViewDirection.AB)))
        data.wayPoints.add(WayPoint("P", "P", approach.id, 70.0))
        data.wayPoints.add(WayPoint("M1", "M1", mainBranch.id, 20.0))
        data.wayPoints.add(WayPoint("B0", "B0", approach.id, blockingTrain.head.location.arcLengthIn(ViewDirection.AB)))
        data.wayPoints.add(WayPoint("B1", "B1", blockingBranch.id, 20.0))
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
                            TimetableStep.At(10L, TimetableAction.GoVia(listOf("P", "M1")))
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
        data.stations.register(Station(stationId, "Central", listOf(berthRef)), listOf(berth))
        val blockerId = TimetableServiceRunId("tt", "blocking")
        val mainId = TimetableServiceRunId("tt", "main")
        dispatcher.startService(world, "tt", "blocking") as TimetableServiceStartResult.Started
        val blockingAto = blockingTrain.controller as SimpleATO
        for (ignored in 0 until 2_000) {
            tick(blockingAto)
            dispatcher.tick(world)
            if (dispatcher.status(blockerId)?.routeGrantActive == true) break
        }
        val mainRun = dispatcher.startService(world, "tt", "main") as TimetableServiceStartResult.Started
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
            dispatcher.tick(world)
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
        assertFalse(data.atoCoordinator.isStalled)
    }

    @Test
    fun `conflicting explicit berth keeps the later service held without stalling`() {
        val world = createTestWorld().also(worlds::add)
        val inspectionRuntime = emptyInspectionRuntime()
        val dispatcher = TimetableDispatcher(
            routeAdmissionContextProvider = GrantingRouteAdmissionTestContext { world },
            signalRestrictionSnapshots = emptyTrainRestrictionSnapshots,
            inspectionRuntime = inspectionRuntime,
            appliedPlanService = acceptingTimetableAppliedPlanService(),
            atoFactory = TimetableAtoFactory { train, owningWorld ->
                SimpleATO(
                    train,
                    owningWorld,
                    KinematicTrainActuator(8.0, profile),
                    profile,
                    inspectionRuntime::maximumDeclaredDistanceMeters
                )
            }
        )
        val data = BasicWorldData(
            atoCoordinator = AtoWorldCoordinator("station-conflict", reportSink = {})
        )
        TestSimpleATOWorldDataRegistry.register(world, data)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(180.0, segmentCount = 1)
        )
        val firstTrain = createTrainWithConfig("T1", rail, world, listOf(10.0))
        val secondTrain = createTrainWithConfig("T2", rail, world, listOf(10.0))
        secondTrain.setLocation(firstTrain.head.location.forward(20.0)!!)
        secondTrain.commit()
        data.wayPoints.add(WayPoint("A1", "A1", rail.id, firstTrain.head.location.arcLengthIn(ViewDirection.AB)))
        data.wayPoints.add(WayPoint("A2", "A2", rail.id, secondTrain.head.location.arcLengthIn(ViewDirection.AB)))
        data.wayPoints.add(WayPoint("C", "C", rail.id, 160.0))
        world.simpleAtoWorldData.timetables.add(
            Timetable(
                id = "tt",
                services = listOf(
                    service("first", firstTrain.id, "A1"),
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
        data.stations.register(Station(stationId, "Central", listOf(berthRef)), listOf(berth))
        val firstId = TimetableServiceRunId("tt", "first")
        val secondId = TimetableServiceRunId("tt", "second")
        val firstRun = dispatcher.startService(world, "tt", "first") as TimetableServiceStartResult.Started
        val secondRun = dispatcher.startService(world, "tt", "second") as TimetableServiceStartResult.Started

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
        dispatcher.tick(world)

        assertTrue(dispatcher.status(firstId)?.berthHeld == true)
        assertEquals(TimetableServiceRunState.WAITING_BERTH, dispatcher.status(secondId)?.state)
        assertFalse(dispatcher.status(secondId)?.berthHeld == true)
        assertEquals(0.0, secondTrain.speed, 1.0e-9)

        assertTrue(dispatcher.stopService(world, firstId))
        tick(firstTrain.controller as SimpleATO)
        dispatcher.tick(world)
        assertTrue(dispatcher.status(firstId)?.berthHeld == true)
        assertFalse(data.atoCoordinator.isStalled)
    }

    private fun service(
        id: String,
        trainId: String,
        origin: String,
        target: String = "C",
        tick: Long = 0L
    ): TimetableService =
        TimetableService(
            id = id,
            trainSelector = TrainSelector.TrainId(trainId),
            steps = listOf(
                TimetableStep.Check(origin),
                TimetableStep.At(tick, TimetableAction.GoTo(target))
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
        destinationOptions: TimetableAction.DestinationOptions? = null,
        haltAfterDestination: Boolean = false,
        reverseAfterDestination: Boolean = false,
        doorSide: DoorSide = DoorSide.NONE,
        operationGateway: TrainOperationGateway = TrainOperationGateway {
            TrainOperationExecutionResult.Rejected("not configured in this fixture")
        },
        operationTimeoutTicks: Long = 100L,
        stationMovementTick: Long = 0L,
        nextMovementTick: Long = 1L,
        berthStoppingOffsetMetersBefore: Double = 0.0
    ): Fixture {
        val world = createTestWorld().also(worlds::add)
        val inspectionRuntime = emptyInspectionRuntime()
        val dispatcher = TimetableDispatcher(
            routeAdmissionContextProvider = GrantingRouteAdmissionTestContext { world },
            signalRestrictionSnapshots = emptyTrainRestrictionSnapshots,
            inspectionRuntime = inspectionRuntime,
            appliedPlanService = acceptingTimetableAppliedPlanService(),
            operationGateway = operationGateway,
            operationTimeoutTicks = operationTimeoutTicks,
            atoFactory = TimetableAtoFactory { train, owningWorld ->
                SimpleATO(
                    train,
                    owningWorld,
                    KinematicTrainActuator(8.0, profile),
                    profile,
                    inspectionRuntime::maximumDeclaredDistanceMeters
                )
            }
        )
        val data = BasicWorldData(
            atoCoordinator = AtoWorldCoordinator("station-dispatcher", reportSink = {})
        )
        TestSimpleATOWorldDataRegistry.register(world, data)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(180.0, segmentCount = 1)
        )
        val train = createTrainWithConfig("T1", rail, world, listOf(10.0))
        val originArc = train.head.location.arcLength
        val firstTargetArc = originArc + firstTargetOffsetMeters
        val targetArc = originArc + (nextTargetOffsetMeters ?: firstTargetOffsetMeters)
        data.wayPoints.add(WayPoint("A", "A", rail.id, originArc))
        data.wayPoints.add(WayPoint("C", "C", rail.id, firstTargetArc))
        if (nextTargetOffsetMeters != null) {
            data.wayPoints.add(WayPoint("D", "D", rail.id, targetArc))
        }
        world.simpleAtoWorldData.timetables.add(
            Timetable(
                id = "tt",
                services = listOf(
                    TimetableService(
                        id = "service",
                        trainSelector = TrainSelector.TrainId(train.id),
                        steps = buildList {
                            add(TimetableStep.Check("A"))
                            if (includeStationAction) {
                                add(
                                    TimetableStep.At(
                                        0L,
                                        TimetableAction.CallStation(
                                            stationId = "central",
                                            berthId = "one",
                                            minimumDwellTicks = 5L
                                        )
                                    )
                                )
                            }
                            destinationOptions?.let {
                                add(TimetableStep.At(stationMovementTick, it))
                            }
                            add(TimetableStep.At(stationMovementTick, TimetableAction.GoTo("C")))
                            if (reverseAfterDestination) {
                                add(TimetableStep.At(stationMovementTick, TimetableAction.Reverse))
                            }
                            when {
                                nextTargetOffsetMeters != null ->
                                    add(TimetableStep.At(nextMovementTick, TimetableAction.GoTo("D")))
                                haltAfterDestination ->
                                    add(TimetableStep.At(stationMovementTick, TimetableAction.Halt))
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
            positionToleranceMeters = 0.15,
            usableLengthMeters = 100.0,
            defaultDwellTicks = 5L,
            releaseDistanceMetersAfter = releaseDistanceMetersAfter,
            stoppingOffsetMetersBefore = berthStoppingOffsetMetersBefore,
            doorSide = doorSide
        )
        val station = Station(stationId, "Central", listOf(berthRef))
        data.stations.register(station, listOf(berth))
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
            dispatcher.tick(world)
        }
    }
}
