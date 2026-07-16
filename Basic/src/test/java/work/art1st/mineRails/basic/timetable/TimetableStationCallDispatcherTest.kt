package work.art1st.mineRails.basic.timetable

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import work.art1st.mineRails.basic.BasicWorldData
import work.art1st.mineRails.basic.WayPoint
import work.art1st.mineRails.basic.api.ato.AtoSafetyProfile
import work.art1st.mineRails.basic.api.ato.AtoWorldCoordinator
import work.art1st.mineRails.basic.api.ato.KinematicTrainActuator
import work.art1st.mineRails.basic.api.operation.TrainOperation
import work.art1st.mineRails.basic.api.operation.TrainOperationExecutionResult
import work.art1st.mineRails.basic.api.operation.TrainOperationGateway
import work.art1st.mineRails.basic.api.operation.TrainOperationRequest
import work.art1st.mineRails.basic.api.operation.TrainOperationTicket
import work.art1st.mineRails.basic.api.operation.TrainOperationTicketSnapshot
import work.art1st.mineRails.basic.api.operation.TrainOperationTicketState
import work.art1st.mineRails.basic.api.station.DoorSide
import work.art1st.mineRails.basic.api.station.DwellPlan
import work.art1st.mineRails.basic.api.station.Station
import work.art1st.mineRails.basic.api.station.StationBerth
import work.art1st.mineRails.basic.api.station.StationBerthRef
import work.art1st.mineRails.basic.api.station.StationCall
import work.art1st.mineRails.basic.api.station.StationId
import work.art1st.mineRails.basic.controller.SimpleATO
import work.art1st.mineRails.basic.pathFinding.TestBasicWorldDataRegistry
import work.art1st.mineRails.core.api.physics.curve.Curve
import work.art1st.mineRails.core.api.physics.curve.PrimeCurve
import work.art1st.mineRails.core.api.physics.rail.ViewDirection
import work.art1st.mineRails.core.physics.rail.CoreRail
import work.art1st.mineRails.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.mineRails.core.physics.train.CoreTrain
import work.art1st.mineRails.core.physics.train.createStraightRailFromSegments
import work.art1st.mineRails.core.physics.train.createTestWorld
import work.art1st.mineRails.core.physics.train.createTrainWithConfig
import work.art1st.mineRails.core.util.AsyncTickTestSupport

class TimetableStationCallDispatcherTest {
    private val worlds = mutableListOf<work.art1st.mineRails.core.physics.CoreWorld>()

    private val profile = AtoSafetyProfile(
        accelerationMetersPerSecondSquared = 1.0,
        serviceDecelerationMetersPerSecondSquared = 1.0,
        reactionTimeSeconds = 0.0,
        safetyDistanceMeters = 1.0,
        stopToleranceMeters = 0.25,
        stoppedSpeedThresholdMetersPerSecond = 0.05
    )

    @AfterEach
    fun unregisterWorlds() {
        worlds.forEach(TestBasicWorldDataRegistry::unregister)
        worlds.clear()
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
        assertTrue(TimetableServiceRunState.DEPARTED in observed)
        assertEquals(TimetableServiceRunState.COMPLETED, fixture.dispatcher.status(id)?.state)
        assertFalse(fixture.data.atoCoordinator.isStalled)
    }

    @Test
    fun `door tickets gate station dwell and departure hold release`() {
        val gateway = ControllableOperationGateway()
        val fixture = fixture(
            includeStationAction = true,
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
                assertEquals(0.0, fixture.train.speed, profile.stoppedSpeedThresholdMetersPerSecond)
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
    fun `door operation timeout requires manual intervention and keeps departure hold`() {
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
                TimetableServiceRunState.MANUAL_INTERVENTION
            ) break
        }

        val status = fixture.dispatcher.status(id)!!
        assertEquals(TimetableServiceRunState.MANUAL_INTERVENTION, status.state)
        assertEquals(TrainOperationTicketState.CANCELLED, status.operationTicketState)
        assertTrue(status.berthHeld)
        assertEquals(0.0, fixture.train.speed, profile.stoppedSpeedThresholdMetersPerSecond)
        assertTrue(status.message!!.contains("timed out"))
        repeat(20) { fixture.tick() }
        assertEquals(TimetableServiceRunState.MANUAL_INTERVENTION, fixture.dispatcher.status(id)?.state)
        assertFalse(fixture.data.atoCoordinator.isStalled)
    }

    @Test
    fun `failed close ticket never grants departure`() {
        val gateway = ControllableOperationGateway()
        val fixture = fixture(
            includeStationAction = true,
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
                TimetableServiceRunState.MANUAL_INTERVENTION -> break
                else -> Unit
            }
        }

        val status = fixture.dispatcher.status(id)!!
        assertTrue(closeFailed)
        assertEquals(TimetableServiceRunState.MANUAL_INTERVENTION, status.state)
        assertEquals(TrainOperationTicketState.FAILED, status.operationTicketState)
        assertFalse(status.state == TimetableServiceRunState.DEPARTING)
        assertEquals(0.0, fixture.train.speed, profile.stoppedSpeedThresholdMetersPerSecond)
        assertTrue(status.berthHeld)
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
    fun `dwell does not release while the next route section conflicts`() {
        val world = createTestWorld().also(worlds::add)
        val dispatcher = TimetableDispatcher { train, owningWorld ->
            SimpleATO(train, owningWorld, KinematicTrainActuator(8.0, profile), profile)
        }
        val data = BasicWorldData(
            dispatcher = dispatcher,
            atoCoordinator = AtoWorldCoordinator("station-route-wait", reportSink = {})
        )
        TestBasicWorldDataRegistry.register(world, data)
        val approach = rail(world, Vector3d(0.0, 0.0, 0.0), Vector3d(100.0, 0.0, 0.0))
        val mainBranch = rail(world, Vector3d(100.0, 0.0, 0.0), Vector3d(150.0, 0.0, 0.0))
        val blockingBranch = rail(world, Vector3d(100.0, 0.0, 0.0), Vector3d(150.0, 0.0, 20.0))
        CoreRail.connect(approach, mainBranch)
        CoreRail.connect(approach, blockingBranch)
        val mainTrain = createTrainWithConfig("main", approach, world, listOf(10.0))
        val blockingTrain = createTrainWithConfig("blocking", approach, world, listOf(10.0))
        blockingTrain.setLocation(mainTrain.head.location.forward(-10.0)!!)
        blockingTrain.commit()
        data.wayPoints.add(WayPoint("M0", "M0", approach.id, mainTrain.head.location.arcLengthIn(ViewDirection.AB)))
        data.wayPoints.add(WayPoint("P", "P", approach.id, 70.0))
        data.wayPoints.add(WayPoint("M1", "M1", mainBranch.id, 20.0))
        data.wayPoints.add(WayPoint("B0", "B0", approach.id, blockingTrain.head.location.arcLengthIn(ViewDirection.AB)))
        data.wayPoints.add(WayPoint("B1", "B1", blockingBranch.id, 20.0))
        data.timetables.add(
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
        val mainRun = dispatcher.startService(world, "tt", "main") as TimetableServiceStartResult.Started
        dispatcher.scheduleStationCall(
            world,
            mainId,
            call("main-call", mainRun.run.missionRevision, stationId, berthRef)
        )
        val blockingAto = blockingTrain.controller as SimpleATO
        val mainAto = mainTrain.controller as SimpleATO
        tick(blockingAto)
        tick(mainAto)
        dispatcher.tick(world)
        tick(blockingAto)

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

        assertTrue(TimetableServiceRunState.BERTHED in observed)
        assertTrue(TimetableServiceRunState.DWELLING in observed)
        assertTrue(TimetableServiceRunState.READY_TO_DEPART in observed)
        assertFalse(TimetableServiceRunState.DEPARTING in observed)
        assertEquals(TimetableServiceRunState.READY_TO_DEPART, dispatcher.status(mainId)?.state)
        assertEquals(0.0, mainTrain.speed, profile.stoppedSpeedThresholdMetersPerSecond)
        assertTrue(dispatcher.status(mainId)?.berthHeld == true)
        assertTrue(dispatcher.status(blockerId)?.confirmedSectionCount == 1)
        assertFalse(data.atoCoordinator.isStalled)
    }

    @Test
    fun `conflicting explicit berth keeps the later service held without stalling`() {
        val world = createTestWorld().also(worlds::add)
        val dispatcher = TimetableDispatcher { train, owningWorld ->
            SimpleATO(train, owningWorld, KinematicTrainActuator(8.0, profile), profile)
        }
        val data = BasicWorldData(
            dispatcher = dispatcher,
            atoCoordinator = AtoWorldCoordinator("station-conflict", reportSink = {})
        )
        TestBasicWorldDataRegistry.register(world, data)
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
        data.timetables.add(
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
        doorSide: DoorSide = DoorSide.NONE,
        operationGateway: TrainOperationGateway = TrainOperationGateway {
            TrainOperationExecutionResult.Rejected("not configured in this fixture")
        },
        operationTimeoutTicks: Long = 100L
    ): Fixture {
        val world = createTestWorld().also(worlds::add)
        val dispatcher = TimetableDispatcher(
            operationGateway = operationGateway,
            operationTimeoutTicks = operationTimeoutTicks,
            atoFactory = TimetableAtoFactory { train, owningWorld ->
                SimpleATO(train, owningWorld, KinematicTrainActuator(8.0, profile), profile)
            }
        )
        val data = BasicWorldData(
            dispatcher = dispatcher,
            atoCoordinator = AtoWorldCoordinator("station-dispatcher", reportSink = {})
        )
        TestBasicWorldDataRegistry.register(world, data)
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
        data.timetables.add(
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
                            add(TimetableStep.At(1L, TimetableAction.GoTo("C")))
                            if (nextTargetOffsetMeters != null) {
                                add(TimetableStep.At(1L, TimetableAction.GoTo("D")))
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
            doorSide = doorSide
        )
        val station = Station(stationId, "Central", listOf(berthRef))
        data.stations.register(station, listOf(berth))
        return Fixture(world, data, dispatcher, train, station, berth, targetArc)
    }

    private fun rail(
        world: work.art1st.mineRails.core.physics.CoreWorld,
        from: Vector3d,
        to: Vector3d
    ): CoreRail = CoreRail(world, straightCurve(from, to), mutableListOf(), mutableListOf())

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
        val world: work.art1st.mineRails.core.physics.CoreWorld,
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
