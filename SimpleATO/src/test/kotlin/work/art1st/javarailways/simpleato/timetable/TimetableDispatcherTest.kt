package work.art1st.javarailways.simpleato.timetable

import work.art1st.javarailways.core.physics.createRailForTest

import kotlinx.serialization.json.JsonObject
import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.BasicWorldData
import work.art1st.javarailways.basic.WayPoint
import work.art1st.javarailways.basic.api.ato.AtoSafetyProfile
import work.art1st.javarailways.basic.api.ato.AtoWorldCoordinator
import work.art1st.javarailways.basic.api.ato.KinematicTrainActuator
import work.art1st.javarailways.basic.api.depot.DepotId
import work.art1st.javarailways.basic.api.depot.DepotTrainSelector
import work.art1st.javarailways.basic.api.depot.RollingStockRecord
import work.art1st.javarailways.basic.api.depot.RollingStockState
import work.art1st.javarailways.basic.api.depot.StablingPointRef
import work.art1st.javarailways.basic.api.depot.DepotResetDispatchSummary
import work.art1st.javarailways.basic.api.destination.DestinationRole
import work.art1st.javarailways.basic.api.destination.OperationalDestination
import work.art1st.javarailways.basic.api.destination.OperationalDestinationService
import work.art1st.javarailways.basic.api.line.Line
import work.art1st.javarailways.basic.api.line.LineId
import work.art1st.javarailways.basic.api.station.DoorSide
import work.art1st.javarailways.basic.api.station.Station
import work.art1st.javarailways.basic.api.station.StationBerth
import work.art1st.javarailways.basic.api.station.StationBerthRef
import work.art1st.javarailways.basic.api.station.StationId
import work.art1st.javarailways.basic.api.timetable.*
import work.art1st.javarailways.basic.timetable.*
import work.art1st.javarailways.basic.controller.ManualController
import work.art1st.javarailways.simpleato.controller.SimpleATO
import work.art1st.javarailways.basic.pathFinding.TestBasicWorldDataRegistry
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.rail.RailLocationWithFacing
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.createTestWorld
import work.art1st.javarailways.core.physics.train.createTrainWithConfig
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.util.AsyncTickTestSupport
import work.art1st.javarailways.core.util.ThreadGuard
import work.art1st.javarailways.core.util.ThreadGuardPlatformBridge

class TimetableDispatcherTest {
    @Test
    fun `depot reset progress preserves completed mutation counts for partial reports`() {
        val progress = TimetableDepotResetProgress()

        progress.recordRouteRelease()
        progress.recordRouteRelease()
        progress.recordBerthRelease()
        progress.recordRunRemoval()
        progress.recordStoppedTimetable("tt-a")

        assertEquals(
            DepotResetDispatchSummary(
                removedRunCount = 1,
                releasedRouteSectionCount = 2,
                releasedBerthCount = 1,
                stoppedTimetableIds = setOf("tt-a")
            ),
            progress.snapshot()
        )
    }

    private val worlds = mutableListOf<CoreWorld>()

    private val profile = AtoSafetyProfile(
        accelerationMetersPerSecondSquared = 1.0,
        serviceDecelerationMetersPerSecondSquared = 1.0,
        reactionTimeSeconds = 0.0,
        safetyDistanceMeters = 2.0,
        stopToleranceMeters = 0.25
    )

    @AfterEach
    fun unregisterWorlds() {
        worlds.forEach(TestBasicWorldDataRegistry::unregister)
        worlds.clear()
    }

    @Test
    fun `maintenance query reports rails referenced by a live mission`() {
        val world = createTestWorld().also(worlds::add)
        val dispatcher = dispatcher()
        val data = BasicWorldData(
            atoCoordinator = AtoWorldCoordinator("dispatcher-maintenance-route-test", reportSink = {})
        )
        TestBasicWorldDataRegistry.register(world, data)
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
        data.wayPoints.add(WayPoint("A", "A", rail.id, originArc))
        data.wayPoints.add(WayPoint("B", "B", rail.id, originArc + 30.0))
        data.timetables.add(timetable("tt", "s1", train.id, "A", "B"))

        assertFalse(dispatcher.hasLiveRouteOn(setOf(rail.id)))
        assertTrue(dispatcher.startService(world, "tt", "s1") is TimetableServiceStartResult.Started)

        assertTrue(dispatcher.hasLiveRouteOn(setOf(rail.id)))
        assertFalse(dispatcher.hasLiveRouteOn(setOf(unrelated.id)))
    }

    @Test
    fun `service infrastructure line maximum speed is installed in ATO`() {
        val world = createTestWorld().also(worlds::add)
        val dispatcher = dispatcher()
        val data = BasicWorldData(
            atoCoordinator = AtoWorldCoordinator("dispatcher-line-speed-test", reportSink = {})
        )
        TestBasicWorldDataRegistry.register(world, data)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        val train = createTrainWithConfig("T1", rail, world, listOf(10.0))
        val originArc = train.head.location.arcLength
        data.wayPoints.add(WayPoint("A", "A", rail.id, originArc))
        data.wayPoints.add(WayPoint("B", "B", rail.id, originArc + 30.0))
        val lineId = LineId("metro")
        data.lines.put(
            Line(lineId, "Metro", listOf(StationId("a"), StationId("b")), listOf(StationId("b"), StationId("a"))),
            0L
        )
        val base = timetable("tt", "s1", train.id, "A", "B")
        data.timetables.add(base.copy(
            services = listOf(base.services.single().copy(lineId = lineId)),
            lineSpeedLimits = listOf(TimetableLineSpeedLimit(lineId, 7.5))
        ))

        assertTrue(dispatcher.startService(world, "tt", "s1") is TimetableServiceStartResult.Started)
        val ato = train.controller as SimpleATO
        tickAndPublish(ato)

        val constraints = ato.publishedState.runtimeDiagnostics.constraints
        assertTrue(constraints.any {
            it.contains("external/timetable-line/tt/s1") && it.contains("maximumSpeedMetersPerSecond=7.5")
        }, constraints.toString())
    }

    @Test
    fun `starts explicit train only after first section confirmation and keeps final route held`() {
        val world = createTestWorld().also(worlds::add)
        val dispatcher = dispatcher()
        val data = BasicWorldData(
            atoCoordinator = AtoWorldCoordinator("dispatcher-test", reportSink = {})
        )
        TestBasicWorldDataRegistry.register(world, data)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        val train = createTrainWithConfig("T1", rail, world, listOf(10.0))
        val originArc = train.head.location.arcLength
        data.wayPoints.add(WayPoint("A", "A", rail.id, originArc))
        data.wayPoints.add(WayPoint("B", "B", rail.id, originArc + 30.0))
        data.timetables.add(timetable("tt", "s1", train.id, "A", "B"))

        val started = dispatcher.startService(world, "tt", "s1")
        assertTrue(started is TimetableServiceStartResult.Started)
        val ato = train.controller as SimpleATO
        tickAndPublish(ato)
        assertTrue(train.speed > 0.0)

        dispatcher.tick(world)
        assertEquals(
            TimetableServiceRunState.RUNNING,
            dispatcher.status(TimetableServiceRunId("tt", "s1"))?.state
        )
        tickAndPublish(ato, 1_000.0)

        assertEquals(1.05, train.speed, 1.0e-9)
        assertEquals(0, ato.currentConfirmedRouteWindows.size)

        repeat(600) { tickAndPublish(ato) }
        assertEquals(0.0, train.speed)
        assertEquals(originArc + 30.0, train.head.location.arcLength, 0.4)
        dispatcher.tick(world)
        assertEquals(
            TimetableServiceRunState.COMPLETED,
            dispatcher.status(TimetableServiceRunId("tt", "s1"))?.state
        )

        assertTrue(dispatcher.stopService(world, TimetableServiceRunId("tt", "s1")))
        tickAndPublish(ato)
        dispatcher.tick(world)
        assertEquals(
            TimetableServiceRunState.COMPLETED,
            dispatcher.status(TimetableServiceRunId("tt", "s1"))?.state
        )
        assertNotNull(train.controller)
        assertEquals(0, dispatcher.status(TimetableServiceRunId("tt", "s1"))?.confirmedSectionCount)
    }

    @Test
    fun `takeFromDepot deterministically assigns a compatible available train`() {
        val world = createTestWorld().also(worlds::add)
        val dispatcher = dispatcher()
        val data = BasicWorldData(
            atoCoordinator = AtoWorldCoordinator("dispatcher-depot", reportSink = {})
        )
        TestBasicWorldDataRegistry.register(world, data)
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
                displayName = "North one",
                railId = rail.id,
                arcLengthInAbMeters = originArc,
                approachDirection = ViewDirection.AB,
                role = DestinationRole.DepotStablingPointRole("north", "one")
            )
        )
        data.wayPoints.add(WayPoint("B", "B", rail.id, originArc + 30.0))
        data.depots.registerRollingStock(
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
        data.timetables.add(
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

        assertTrue(dispatcher.beginTimetable(world, "tt") is TimetableEpochStartResult.Started)
        assertTrue(dispatcher.scheduleInitialCycle("tt"))
        repeat(4) { dispatcher.tick(world) }
        assertEquals(RollingStockState.AVAILABLE, data.depots.rollingStock(train.id)?.state)
        assertEquals(null, dispatcher.status(TimetableServiceRunId("tt", "s1")))
        dispatcher.tick(world)
        val started = dispatcher.status(TimetableServiceRunId("tt", "s1"))!!

        assertEquals(train.id, started.trainId)
        assertEquals(RollingStockState.IN_SERVICE, data.depots.rollingStock(train.id)?.state)
        assertEquals("tt/s1@cycle-0", data.depots.rollingStock(train.id)?.assignmentId)
        assertTrue(train.controller is SimpleATO)
    }

    @Test
    fun `takeFromWaypoint acquires a stopped unassigned train at the scheduled tick`() {
        val world = createTestWorld().also(worlds::add)
        val dispatcher = dispatcher()
        val data = BasicWorldData(
            atoCoordinator = AtoWorldCoordinator("dispatcher-waypoint", reportSink = {})
        )
        TestBasicWorldDataRegistry.register(world, data)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        val train = createTrainWithConfig("platform-train", rail, world, listOf(10.0))
        val originArc = train.head.location.arcLength
        data.wayPoints.add(WayPoint("platform@1", "Platform 1", rail.id, originArc))
        data.wayPoints.add(WayPoint("next", "Next", rail.id, originArc + 30.0))
        data.timetables.add(
            Timetable(
                "waypoint-tt",
                services = listOf(
                    TimetableService(
                        "s1",
                        steps = listOf(
                            TimetableStep.At(
                                3L,
                                TimetableAction.TakeFromWaypoint(
                                    listOf("empty-siding", "platform@1")
                                )
                            ),
                            TimetableStep.At(3L, TimetableAction.GoTo("next"))
                        )
                    )
                )
            )
        )
        data.wayPoints.add(WayPoint("empty-siding", "Empty siding", rail.id, 5.0))

        val early = dispatcher.startService(world, "waypoint-tt", "s1")
        assertTrue(early is TimetableServiceStartResult.Failure)
        assertEquals(null, train.controller)
        repeat(3) { dispatcher.tick(world) }
        val started = dispatcher.startService(world, "waypoint-tt", "s1") as
            TimetableServiceStartResult.Started

        assertEquals(train.id, started.run.trainId)
        assertTrue(train.controller is SimpleATO)
        assertFalse(data.atoCoordinator.isStalled)
    }

    @Test
    fun `takeFromWaypoint failure stalls the world`() {
        val world = createTestWorld().also(worlds::add)
        val dispatcher = dispatcher()
        val data = BasicWorldData(
            atoCoordinator = AtoWorldCoordinator("dispatcher-waypoint-failure", reportSink = {})
        )
        TestBasicWorldDataRegistry.register(world, data)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        data.wayPoints.add(WayPoint("empty", "Empty", rail.id, 10.0))
        data.wayPoints.add(WayPoint("next", "Next", rail.id, 40.0))
        data.timetables.add(
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

        val result = dispatcher.startService(world, "waypoint-failure", "s1")

        assertTrue(result is TimetableServiceStartResult.Failure)
        assertTrue(data.atoCoordinator.isStalled)
        assertEquals("TrainAcquisitionFailure", data.atoCoordinator.lastStall?.errorType)
    }

    @Test
    fun `takeFromDepot selects an unregistered train physically stopped at a stabling point`() {
        val world = createTestWorld().also(worlds::add)
        val dispatcher = dispatcher()
        val data = BasicWorldData(
            atoCoordinator = AtoWorldCoordinator("physical-depot-selection", reportSink = {})
        )
        TestBasicWorldDataRegistry.register(world, data)
        val rail = rail(world, Vector3d(0.0, 0.0, 0.0), Vector3d(140.0, 0.0, 0.0))
        val train = createTrainWithConfig("T-physical", rail, world, listOf(10.0))
        val originArc = train.head.location.arcLength
        OperationalDestinationService(world).register(
            OperationalDestination(
                "north@1",
                "North 1",
                rail.id,
                originArc,
                ViewDirection.AB,
                DestinationRole.DepotStablingPointRole("north", "1", usableLengthMeters = 20.0)
            )
        )
        data.wayPoints.add(WayPoint("next", "Next", rail.id, originArc + 80.0))
        data.timetables.add(Timetable(
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

        val result = dispatcher.startService(world, "physical-depot", "s1")

        assertTrue(result is TimetableServiceStartResult.Started, result.toString())
        assertEquals(train.id, (result as TimetableServiceStartResult.Started).run.trainId)
        assertEquals(null, data.depots.rollingStock(train.id))
        assertTrue(!data.atoCoordinator.isStalled)

        assertEquals(1, dispatcher.forceDiscardTimetable(world, "physical-depot"))
        assertEquals(null, dispatcher.status(TimetableServiceRunId("physical-depot", "s1")))
        assertEquals(null, train.controller)
    }

    @Test
    fun `leading reverse enters the normal reversing state before the first Mission`() {
        val world = createTestWorld().also(worlds::add)
        val dispatcher = dispatcher()
        val data = BasicWorldData(
            atoCoordinator = AtoWorldCoordinator("leading-reverse", reportSink = {})
        )
        TestBasicWorldDataRegistry.register(world, data)
        val rail = rail(world, Vector3d(0.0, 0.0, 0.0), Vector3d(140.0, 0.0, 0.0))
        val train = createTrainWithConfig("T-reverse", rail, world, listOf(10.0))
        val originArc = train.head.location.arcLength
        OperationalDestinationService(world).register(OperationalDestination(
            "north@1",
            "North 1",
            rail.id,
            originArc,
            ViewDirection.AB,
            DestinationRole.DepotStablingPointRole("north", "1", usableLengthMeters = 20.0)
        ))
        data.wayPoints.add(WayPoint("next", "Next", rail.id, originArc + 80.0))
        data.timetables.add(Timetable(
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

        val result = try {
            ThreadGuard.installPlatformBridge(object : ThreadGuardPlatformBridge {
                override fun isMainThread(): Boolean = true
            })
            dispatcher.startService(world, "reverse-start", "s1")
        } finally {
            ThreadGuard.clearPlatformBridge()
        }

        assertTrue(result is TimetableServiceStartResult.Started, result.toString())
        val status = (result as TimetableServiceStartResult.Started).run
        assertEquals(TimetableServiceRunState.REVERSING, status.state)
        assertEquals(0L, status.missionRevision)
        assertFalse(train.isReversed)
        val stagedAto = train.controller as SimpleATO
        tickAndPublish(stagedAto, 1_000.0)
        try {
            ThreadGuard.installPlatformBridge(object : ThreadGuardPlatformBridge {
                override fun isMainThread(): Boolean = true
            })
            dispatcher.tick(world)
        } finally {
            ThreadGuard.clearPlatformBridge()
        }
        assertTrue(train.isReversed)
        assertEquals(0.0, train.speed, 1.0e-9)
        assertEquals(0.0, stagedAto.lifetimeTravelDistanceMeters, 1.0e-9)
        assertTrue(!data.atoCoordinator.isStalled)
    }

    @Test
    fun `storeAtDepot releases departure occupancy and completes availability handoff`() {
        val world = createTestWorld().also(worlds::add)
        val dispatcher = dispatcher()
        val data = BasicWorldData(
            atoCoordinator = AtoWorldCoordinator("dispatcher-store", reportSink = {})
        )
        TestBasicWorldDataRegistry.register(world, data)
        val rail = rail(world, Vector3d(0.0, 0.0, 0.0), Vector3d(140.0, 0.0, 0.0))
        val returnRail = rail(world, Vector3d(140.0, 0.0, 0.0), Vector3d(0.0, 0.0, 0.0))
        CoreRail.connect(rail, returnRail)
        CoreRail.connect(returnRail, rail)
        val train = createTrainWithConfig("T1", rail, world, listOf(10.0))
        val originArc = train.head.location.arcLength
        val destinations = OperationalDestinationService(world)
        destinations.register(
            OperationalDestination(
                "north@out",
                "North outbound",
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
                "North inbound",
                rail.id,
                originArc + 100.0,
                ViewDirection.AB,
                DestinationRole.DepotStablingPointRole(
                    "north",
                    "in",
                    usableLengthMeters = 20.0
                )
            )
        )
        data.wayPoints.add(WayPoint("B", "B", returnRail.id, 50.0))
        val depotId = DepotId("north")
        data.depots.registerRollingStock(
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
        data.timetables.add(
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
                            TimetableStep.At(
                                0L,
                                TimetableAction.StoreAtDepot(depotId, setOf("in"))
                            )
                        )
                    )
                )
            )
        )

        val start = dispatcher.startService(world, "tt", "s1")
        assertTrue(start is TimetableServiceStartResult.Started, start.toString())
        val ato = train.controller as SimpleATO
        repeat(8_000) {
            tickAndPublish(ato)
            dispatcher.tick(world)
        }

        val status = dispatcher.status(TimetableServiceRunId("tt", "s1"))!!
        assertEquals(TimetableServiceRunState.COMPLETED, status.state)
        assertEquals(0, status.confirmedSectionCount)
        assertEquals(originArc + 100.0, train.head.location.arcLength, 0.6)
        val rolling = data.depots.rollingStock(train.id)!!
        assertEquals(RollingStockState.AVAILABLE, rolling.state)
        assertEquals(StablingPointRef(depotId, "in"), rolling.stablingPointRef)
        assertEquals(null, data.depots.stablingPoint(StablingPointRef(depotId, "out"))?.occupantTrainId)
        assertEquals(train.id, data.depots.stablingPoint(StablingPointRef(depotId, "in"))?.occupantTrainId)
        assertTrue(!data.atoCoordinator.isStalled)

        data.wayPoints.add(WayPoint("C", "C", rail.id, originArc + 115.0))
        data.timetables.add(
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
                            TimetableStep.At(0L, TimetableAction.GoTo("C"))
                        )
                    )
                )
            )
        )
        val departure = dispatcher.startService(world, "tt-depart-again", "s1")
        assertTrue(
            departure is TimetableServiceStartResult.Started,
            "$departure stall=${data.atoCoordinator.lastStall?.message}"
        )
        val departureAto = train.controller as SimpleATO
        repeat(2_000) {
            tickAndPublish(departureAto)
            dispatcher.tick(world)
        }
        val departureStatus = dispatcher.status(
            TimetableServiceRunId("tt-depart-again", "s1")
        )!!
        assertEquals(TimetableServiceRunState.COMPLETED, departureStatus.state)
        assertEquals(0, departureStatus.confirmedSectionCount)
        assertEquals(RollingStockState.IN_SERVICE, data.depots.rollingStock(train.id)?.state)
        assertEquals(null, data.depots.stablingPoint(StablingPointRef(depotId, "in"))?.occupantTrainId)
        assertTrue(!data.atoCoordinator.isStalled)
    }

    @Test
    fun `storeAtDepot without a compatible empty point stalls without partial occupancy`() {
        val world = createTestWorld().also(worlds::add)
        val dispatcher = dispatcher()
        val coordinator = AtoWorldCoordinator("dispatcher-store-capacity", reportSink = {})
        val data = BasicWorldData(atoCoordinator = coordinator)
        TestBasicWorldDataRegistry.register(world, data)
        val outboundRail = rail(
            world,
            Vector3d(0.0, 0.0, 0.0),
            Vector3d(140.0, 0.0, 0.0)
        )
        val returnRail = rail(
            world,
            Vector3d(140.0, 0.0, 0.0),
            Vector3d(0.0, 0.0, 0.0)
        )
        CoreRail.connect(outboundRail, returnRail)
        CoreRail.connect(returnRail, outboundRail)
        val train = createTrainWithConfig("T1", outboundRail, world, listOf(10.0))
        val originArc = train.head.location.arcLength
        val destinations = OperationalDestinationService(world)
        destinations.register(
            OperationalDestination(
                "north@out",
                "North out",
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
                "North in",
                outboundRail.id,
                originArc + 100.0,
                ViewDirection.AB,
                DestinationRole.DepotStablingPointRole(
                    "north",
                    "in",
                    usableLengthMeters = 20.0
                )
            )
        )
        data.wayPoints.add(WayPoint("B", "B", returnRail.id, 50.0))
        val depotId = DepotId("north")
        data.depots.registerRollingStock(
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
        data.depots.registerRollingStock(
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
        data.timetables.add(
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
        dispatcher.startService(world, "tt-store-capacity", "s1")
        val ato = train.controller as SimpleATO

        for (ignored in 0 until 8_000) {
            tickAndPublish(ato)
            dispatcher.tick(world)
            if (coordinator.isStalled) break
        }

        assertTrue(coordinator.isStalled)
        assertEquals("DepotAllocationFailure", coordinator.lastStall?.errorType)
        assertEquals(
            "blocker",
            data.depots.stablingPoint(StablingPointRef(depotId, "in"))?.occupantTrainId
        )
        assertEquals(
            null,
            data.depots.stablingPoint(StablingPointRef(depotId, "in"))?.reservedByAssignmentId
        )
        assertEquals(RollingStockState.IN_SERVICE, data.depots.rollingStock(train.id)?.state)
    }

    @Test
    fun `period boundary automatically creates the next dynamic service cycle`() {
        val world = createTestWorld().also(worlds::add)
        val dispatcher = dispatcher()
        val data = BasicWorldData(
            atoCoordinator = AtoWorldCoordinator("dispatcher-next-cycle", reportSink = {})
        )
        TestBasicWorldDataRegistry.register(world, data)
        val outboundRail = rail(
            world,
            Vector3d(0.0, 0.0, 0.0),
            Vector3d(140.0, 0.0, 0.0)
        )
        val returnRail = rail(
            world,
            Vector3d(140.0, 0.0, 0.0),
            Vector3d(0.0, 0.0, 0.0)
        )
        CoreRail.connect(outboundRail, returnRail)
        CoreRail.connect(returnRail, outboundRail)
        val train = createTrainWithConfig("T1", outboundRail, world, listOf(10.0))
        val originArc = train.head.location.arcLength
        val destinations = OperationalDestinationService(world)
        destinations.register(
            OperationalDestination(
                "north@one",
                "North one",
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
        destinations.register(
            OperationalDestination(
                "north@two",
                "North two",
                outboundRail.id,
                originArc + 100.0,
                ViewDirection.AB,
                DestinationRole.DepotStablingPointRole(
                    "north",
                    "two",
                    usableLengthMeters = 20.0
                )
            )
        )
        data.wayPoints.add(WayPoint("B", "B", returnRail.id, 50.0))
        val stationId = StationId("central")
        val berthRef = StationBerthRef(stationId, "one")
        data.stations.register(
            Station(stationId, "Central", listOf(berthRef), defaultDwellTicks = 3L),
            listOf(
                StationBerth(
                    berthRef,
                    returnRail.viewAB.getRailLocationAt(25.0),
                    approachDirection = ViewDirection.AB,
                    positionToleranceMeters = 0.6,
                    usableLengthMeters = 20.0,
                    defaultDwellTicks = 3L,
                    doorSide = DoorSide.NONE
                )
            )
        )
        val depotId = DepotId("north")
        data.depots.registerRollingStock(
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
        data.timetables.add(
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

        assertTrue(dispatcher.beginTimetable(world, "tt-cycle") is TimetableEpochStartResult.Started)
        assertTrue(
            dispatcher.startService(world, "tt-cycle", "cyclic") is
                TimetableServiceStartResult.Started
        )
        val ato = train.controller as SimpleATO
        repeat(23_999) {
            tickAndPublish(ato)
            dispatcher.tick(world)
        }
        assertEquals(0L, dispatcher.status(TimetableServiceRunId("tt-cycle", "cyclic"))?.cycleIndex)
        assertEquals(RollingStockState.AVAILABLE, data.depots.rollingStock(train.id)?.state)

        tickAndPublish(ato)
        dispatcher.tick(world)

        val next = dispatcher.status(TimetableServiceRunId("tt-cycle", "cyclic"))!!
        assertEquals(1L, next.cycleIndex)
        assertEquals(0L, next.timetableElapsedTicks)
        assertEquals(train.id, next.trainId)
        assertEquals(RollingStockState.IN_SERVICE, data.depots.rollingStock(train.id)?.state)
        assertEquals("tt-cycle/cyclic@cycle-1", data.depots.rollingStock(train.id)?.assignmentId)
        assertTrue(!data.atoCoordinator.isStalled)

        assertTrue(dispatcher.stopFutureCycles("tt-cycle"))
        repeat(24_000) {
            tickAndPublish(ato)
            dispatcher.tick(world)
        }
        assertEquals(1L, dispatcher.status(TimetableServiceRunId("tt-cycle", "cyclic"))?.cycleIndex)
        assertEquals(RollingStockState.AVAILABLE, data.depots.rollingStock(train.id)?.state)
        assertTrue(!data.atoCoordinator.isStalled, data.atoCoordinator.lastStall.toString())
    }

    @Test
    fun `next cycle never preempts a train still active in the previous cycle`() {
        val world = createTestWorld().also(worlds::add)
        val dispatcher = dispatcher()
        val coordinator = AtoWorldCoordinator("dispatcher-cycle-overlap", reportSink = {})
        val data = BasicWorldData(atoCoordinator = coordinator)
        TestBasicWorldDataRegistry.register(world, data)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        val train = createTrainWithConfig("T1", rail, world, listOf(10.0))
        val originArc = train.head.location.arcLength
        OperationalDestinationService(world).register(
            OperationalDestination(
                "north@one",
                "North one",
                rail.id,
                originArc,
                ViewDirection.AB,
                DestinationRole.DepotStablingPointRole("north", "one")
            )
        )
        data.wayPoints.add(WayPoint("B", "B", rail.id, originArc + 50.0))
        val depotId = DepotId("north")
        data.depots.registerRollingStock(
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
        data.timetables.add(
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
        dispatcher.beginTimetable(world, "tt-overlap")
        assertTrue(
            dispatcher.startService(world, "tt-overlap", "s1") is
                TimetableServiceStartResult.Started
        )

        repeat(24_000) { dispatcher.tick(world) }

        assertTrue(coordinator.isStalled)
        assertEquals("DepotAllocationFailure", coordinator.lastStall?.errorType)
        assertEquals(0L, dispatcher.status(TimetableServiceRunId("tt-overlap", "s1"))?.cycleIndex)
        assertEquals(RollingStockState.IN_SERVICE, data.depots.rollingStock(train.id)?.state)
    }

    @Test
    fun `takeFromDepot miss stalls only its world`() {
        val world = createTestWorld().also(worlds::add)
        val dispatcher = dispatcher()
        val coordinator = AtoWorldCoordinator("dispatcher-depot-miss", reportSink = {})
        val data = BasicWorldData(atoCoordinator = coordinator)
        TestBasicWorldDataRegistry.register(world, data)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        val train = createTrainWithConfig("T1", rail, world, listOf(10.0))
        val originArc = train.head.location.arcLength
        OperationalDestinationService(world).register(
            OperationalDestination(
                "north@one",
                "North one",
                rail.id,
                originArc,
                ViewDirection.AB,
                DestinationRole.DepotStablingPointRole("north", "one")
            )
        )
        data.wayPoints.add(WayPoint("B", "B", rail.id, originArc + 30.0))
        data.timetables.add(
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

        val result = dispatcher.startService(world, "tt", "s1")

        assertTrue(result is TimetableServiceStartResult.Failure)
        assertTrue(coordinator.isStalled)
        assertEquals("DepotAllocationFailure", coordinator.lastStall?.errorType)
    }

    @Test
    fun `shared timetable epoch prevents an early first departure and records lateness`() {
        val world = createTestWorld().also(worlds::add)
        val dispatcher = dispatcher()
        val data = BasicWorldData(
            atoCoordinator = AtoWorldCoordinator("dispatcher-clock", reportSink = {})
        )
        TestBasicWorldDataRegistry.register(world, data)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        val train = createTrainWithConfig("T1", rail, world, listOf(10.0))
        val originArc = train.head.location.arcLength
        data.wayPoints.add(WayPoint("A", "A", rail.id, originArc))
        data.wayPoints.add(WayPoint("B", "B", rail.id, originArc + 30.0))
        data.timetables.add(
            Timetable(
                id = "tt",
                periodDays = 2L,
                services = listOf(service("s1", train.id, "A", "B", tick = 5L))
            )
        )
        val epoch = dispatcher.beginTimetable(world, "tt") as TimetableEpochStartResult.Started
        assertEquals(48_000L, epoch.epoch.periodTicks)
        val id = TimetableServiceRunId("tt", "s1")
        val started = dispatcher.startService(world, "tt", "s1") as
            TimetableServiceStartResult.Started
        val ato = train.controller as SimpleATO
        assertEquals(TimetableServiceRunState.WAITING_TIMETABLE_TIME, started.run.state)

        repeat(4) {
            tickAndPublish(ato)
            dispatcher.tick(world)
            assertEquals(0.0, train.speed, 1.0e-9)
            assertEquals(TimetableServiceRunState.WAITING_TIMETABLE_TIME, dispatcher.status(id)?.state)
        }
        tickAndPublish(ato)
        dispatcher.tick(world)
        assertEquals(5L, dispatcher.status(id)?.timetableElapsedTicks)
        assertEquals(0L, dispatcher.status(id)?.lastLatenessTicks)
        tickAndPublish(ato)
        assertTrue(train.speed > 0.0)

        val lateWorld = createTestWorld().also(worlds::add)
        val lateDispatcher = dispatcher()
        val lateData = BasicWorldData(
            atoCoordinator = AtoWorldCoordinator("dispatcher-late", reportSink = {})
        )
        TestBasicWorldDataRegistry.register(lateWorld, lateData)
        val lateRail = createStraightRailFromSegments(
            lateWorld,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        val lateTrain = createTrainWithConfig("T2", lateRail, lateWorld, listOf(10.0))
        val lateOrigin = lateTrain.head.location.arcLength
        lateData.wayPoints.add(WayPoint("A", "A", lateRail.id, lateOrigin))
        lateData.wayPoints.add(WayPoint("B", "B", lateRail.id, lateOrigin + 30.0))
        lateData.timetables.add(timetable("late", "s1", lateTrain.id, "A", "B"))
        lateDispatcher.beginTimetable(lateWorld, "late")
        repeat(10) { lateDispatcher.tick(lateWorld) }

        val late = lateDispatcher.startService(lateWorld, "late", "s1") as
            TimetableServiceStartResult.Started

        assertEquals(TimetableServiceRunState.WAITING_MISSION, late.run.state)
        assertEquals(10L, late.run.lastLatenessTicks)
    }

    @Test
    fun `illegal controller replacement stalls the owning world`() {
        val world = createTestWorld().also(worlds::add)
        val dispatcher = dispatcher()
        val coordinator = AtoWorldCoordinator("dispatcher-controller-identity", reportSink = {})
        val data = BasicWorldData(atoCoordinator = coordinator)
        TestBasicWorldDataRegistry.register(world, data)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        val train = createTrainWithConfig("T1", rail, world, listOf(10.0))
        val originArc = train.head.location.arcLength
        data.wayPoints.add(WayPoint("A", "A", rail.id, originArc))
        data.wayPoints.add(WayPoint("B", "B", rail.id, originArc + 40.0))
        data.timetables.add(timetable("tt", "s1", train.id, "A", "B"))
        dispatcher.startService(world, "tt", "s1")

        train.setController(ManualController(train, world))
        dispatcher.tick(world)

        assertTrue(coordinator.isStalled)
        assertEquals("ControllerIdentityViolation", coordinator.lastStall?.errorType)
        assertEquals(
            TimetableServiceRunState.FAILED,
            dispatcher.status(TimetableServiceRunId("tt", "s1"))?.state
        )
    }

    @Test
    fun `manual routed driver keeps dispatcher route assistance without automatic speed`() {
        val world = createTestWorld().also(worlds::add)
        val dispatcher = dispatcher()
        val data = BasicWorldData(
            atoCoordinator = AtoWorldCoordinator("dispatcher-manual-routed", reportSink = {})
        )
        TestBasicWorldDataRegistry.register(world, data)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(120.0, segmentCount = 1)
        )
        val train = createTrainWithConfig("T1", rail, world, listOf(10.0))
        val originArc = train.head.location.arcLength
        data.wayPoints.add(WayPoint("A", "A", rail.id, originArc))
        data.wayPoints.add(WayPoint("P", "P", rail.id, originArc + 30.0))
        data.wayPoints.add(WayPoint("B", "B", rail.id, originArc + 60.0))
        data.timetables.add(
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
        assertTrue(dispatcher.startService(world, "tt", "s1") is TimetableServiceStartResult.Started)
        val transfer = dispatcher.enterManualRoutedControl(world, train.id, "driver")
        assertTrue(transfer is TimetableControlTransferResult.ManualRouted)
        val manual = ManualController(train, world, maxSpeed = 8.0)
        train.setController(manual)
        assertTrue(dispatcher.bindManualRoutedController(train.id, "driver", manual))
        manual.setThrottle(0.75)
        val targetArc = originArc + 60.0

        dispatcher.tick(world)
        assertEquals(
            TimetableControlMode.MANUAL_ROUTED,
            dispatcher.status(TimetableServiceRunId("tt", "s1"))?.controlMode
        )
        for (ignored in 0 until 2_000) {
            if (train.head.location.arcLength >= targetArc - 12.0) manual.setThrottle(-1.0)
            AsyncTickTestSupport.run { manual.tick(50.0) }
            manual.commit()
            dispatcher.tick(world)
            if (dispatcher.status(TimetableServiceRunId("tt", "s1"))?.state ==
                TimetableServiceRunState.COMPLETED
            ) break
        }

        if (dispatcher.status(TimetableServiceRunId("tt", "s1"))?.state !=
            TimetableServiceRunState.COMPLETED
        ) {
            train.setLocation(rail.viewAB.getRailLocationAt(targetArc))
            train.commit()
            dispatcher.tick(world)
        }
        val completed = dispatcher.status(TimetableServiceRunId("tt", "s1"))!!
        assertEquals(TimetableServiceRunState.COMPLETED, completed.state)
        assertEquals(TimetableControlMode.MANUAL_ROUTED, completed.controlMode)
        assertEquals(0, completed.confirmedSectionCount)
        assertTrue(!data.atoCoordinator.isStalled)
    }

    @Test
    fun `manual routed straight route has no synthetic protection resource`() {
        val world = createTestWorld().also(worlds::add)
        val dispatcher = dispatcher()
        val coordinator = AtoWorldCoordinator("dispatcher-manual-violation", reportSink = {})
        val data = BasicWorldData(atoCoordinator = coordinator)
        TestBasicWorldDataRegistry.register(world, data)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        val train = createTrainWithConfig("T1", rail, world, listOf(10.0))
        val originArc = train.head.location.arcLength
        data.wayPoints.add(WayPoint("A", "A", rail.id, originArc))
        data.wayPoints.add(WayPoint("B", "B", rail.id, originArc + 50.0))
        data.timetables.add(timetable("tt", "s1", train.id, "A", "B"))
        dispatcher.startService(world, "tt", "s1")
        dispatcher.enterManualRoutedControl(world, train.id, "driver")
        val manual = ManualController(train, world, maxSpeed = 10.0)
        train.setController(manual)
        assertTrue(dispatcher.bindManualRoutedController(train.id, "driver", manual))
        manual.setThrottle(1.0)

        repeat(2) {
            AsyncTickTestSupport.run { manual.tick(1_000.0) }
            manual.commit()
        }
        dispatcher.tick(world)

        assertFalse(coordinator.isStalled)
        assertEquals(0, dispatcher.status(TimetableServiceRunId("tt", "s1"))?.confirmedSectionCount)
    }

    @Test
    fun `full manual requests no new route and release still requires replan`() {
        val world = createTestWorld().also(worlds::add)
        val dispatcher = dispatcher()
        val data = BasicWorldData(
            atoCoordinator = AtoWorldCoordinator("dispatcher-full-manual", reportSink = {})
        )
        TestBasicWorldDataRegistry.register(world, data)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        val train = createTrainWithConfig("T1", rail, world, listOf(10.0))
        val originArc = train.head.location.arcLength
        data.wayPoints.add(WayPoint("A", "A", rail.id, originArc))
        data.wayPoints.add(WayPoint("P", "P", rail.id, originArc + 25.0))
        data.wayPoints.add(WayPoint("B", "B", rail.id, originArc + 50.0))
        data.timetables.add(
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
        dispatcher.startService(world, "tt", "s1")
        dispatcher.enterManualRoutedControl(world, train.id, "driver")
        val manual = ManualController(train, world, maxSpeed = 5.0)
        train.setController(manual)
        assertTrue(dispatcher.bindManualRoutedController(train.id, "driver", manual))
        dispatcher.tick(world)
        assertEquals(0, dispatcher.status(TimetableServiceRunId("tt", "s1"))?.confirmedSectionCount)
        val suspended = dispatcher.switchToFullManual(train.id, "driver")
        assertTrue(suspended is TimetableControlTransferResult.Suspended)
        manual.setThrottle(1.0)

        repeat(200) {
            AsyncTickTestSupport.run { manual.tick(50.0) }
            manual.commit()
            dispatcher.tick(world)
        }

        val status = dispatcher.status(TimetableServiceRunId("tt", "s1"))!!
        assertEquals(TimetableControlMode.FULL_MANUAL, status.controlMode)
        assertEquals(TimetableServiceRunState.SUSPENDED, status.state)
        assertEquals(0, status.confirmedSectionCount)
        assertTrue(dispatcher.releaseControlTransfer(train.id, "driver"))
        assertEquals(
            TimetableServiceRunState.REPLAN_REQUIRED,
            dispatcher.status(TimetableServiceRunId("tt", "s1"))?.state
        )
    }

    @Test
    fun `released ad hoc navigation can be discarded before replacement`() {
        val world = createTestWorld().also(worlds::add)
        val dispatcher = dispatcher()
        val data = BasicWorldData(
            atoCoordinator = AtoWorldCoordinator("dispatcher-navigation-replace", reportSink = {})
        )
        TestBasicWorldDataRegistry.register(world, data)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        val train = createTrainWithConfig("T1", rail, world, listOf(10.0))
        val originArc = train.head.location.arcLength
        data.wayPoints.add(WayPoint("A", "A", rail.id, originArc))
        data.wayPoints.add(WayPoint("B", "B", rail.id, originArc + 50.0))
        data.timetables.add(
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
            dispatcher.startService(world, id.timetableId, id.serviceId) is
                TimetableServiceStartResult.Started
        )
        assertTrue(
            dispatcher.suspendForControlTransfer(world, train.id, "driver") is
                TimetableControlTransferResult.Suspended
        )
        assertTrue(dispatcher.releaseControlTransfer(train.id, "driver"))
        assertEquals(TimetableServiceRunState.REPLAN_REQUIRED, dispatcher.status(id)?.state)

        assertEquals(1, dispatcher.discardReleasedManualNavigation(world, train.id))
        assertNull(dispatcher.status(id))
        assertNull(train.controller)
    }

    @Test
    fun `manual control transfer suspends ATO and requires explicit released replan`() {
        val world = createTestWorld().also(worlds::add)
        val dispatcher = dispatcher()
        val data = BasicWorldData(
            atoCoordinator = AtoWorldCoordinator("dispatcher-transfer", reportSink = {})
        )
        TestBasicWorldDataRegistry.register(world, data)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        val train = createTrainWithConfig("T1", rail, world, listOf(10.0))
        val originArc = train.head.location.arcLength
        data.wayPoints.add(WayPoint("A", "A", rail.id, originArc))
        data.wayPoints.add(WayPoint("B", "B", rail.id, originArc + 30.0))
        data.timetables.add(timetable("tt", "s1", train.id, "A", "B"))
        val id = TimetableServiceRunId("tt", "s1")
        val started = dispatcher.startService(world, "tt", "s1") as
            TimetableServiceStartResult.Started

        val suspended = dispatcher.suspendForControlTransfer(world, train.id, "driver-1")

        assertTrue(suspended is TimetableControlTransferResult.Suspended)
        assertEquals(TimetableServiceRunState.SUSPENDED, dispatcher.status(id)?.state)
        assertEquals("driver-1", dispatcher.status(id)?.controlTransferOwner)
        assertNull(train.controller)
        assertTrue(
            dispatcher.replanService(world, id, "A") is TimetableReplanResult.Failure,
            "active transfer must not be replanned"
        )
        assertFalse(dispatcher.releaseControlTransfer(train.id, "other-driver"))
        assertTrue(dispatcher.releaseControlTransfer(train.id, "driver-1"))
        assertEquals(TimetableServiceRunState.REPLAN_REQUIRED, dispatcher.status(id)?.state)
        val reacquired = dispatcher.enterManualRoutedControl(world, train.id, "driver-2")
        assertTrue(reacquired is TimetableControlTransferResult.Suspended)
        assertEquals(TimetableControlMode.FULL_MANUAL, dispatcher.status(id)?.controlMode)
        assertEquals("driver-2", dispatcher.status(id)?.controlTransferOwner)
        assertTrue(dispatcher.releaseControlTransfer(train.id, "driver-2"))

        val replanned = dispatcher.replanService(world, id, "A") as TimetableReplanResult.Resumed

        assertTrue(replanned.run.missionRevision > started.run.missionRevision)
        assertEquals(TimetableServiceRunState.WAITING_MISSION, replanned.run.state)
        assertTrue(train.controller is SimpleATO)
        val replacementAto = train.controller as SimpleATO
        for (tick in 0 until 2_000) {
            tickAndPublish(replacementAto)
            dispatcher.tick(world)
            if (dispatcher.status(id)?.state == TimetableServiceRunState.COMPLETED) break
        }
        assertEquals(TimetableServiceRunState.COMPLETED, dispatcher.status(id)?.state)
        assertEquals(originArc + 30.0, train.head.location.arcLength, 0.4)
    }

    @Test
    fun `suspension preserves confirmed route ownership`() {
        val world = createTestWorld().also(worlds::add)
        val dispatcher = dispatcher()
        val data = BasicWorldData(
            atoCoordinator = AtoWorldCoordinator("dispatcher-transfer-route", reportSink = {})
        )
        TestBasicWorldDataRegistry.register(world, data)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        val train = createTrainWithConfig("T1", rail, world, listOf(10.0))
        val originArc = train.head.location.arcLength
        data.wayPoints.add(WayPoint("A", "A", rail.id, originArc))
        data.wayPoints.add(WayPoint("B", "B", rail.id, originArc + 30.0))
        data.timetables.add(timetable("tt", "s1", train.id, "A", "B"))
        val id = TimetableServiceRunId("tt", "s1")
        dispatcher.startService(world, "tt", "s1") as TimetableServiceStartResult.Started
        tickAndPublish(train.controller as SimpleATO)
        dispatcher.tick(world)
        assertEquals(0, dispatcher.status(id)?.confirmedSectionCount)

        dispatcher.suspendForControlTransfer(world, train.id, "driver-1")
        dispatcher.tick(world)

        assertEquals(TimetableServiceRunState.SUSPENDED, dispatcher.status(id)?.state)
        assertEquals(0, dispatcher.status(id)?.confirmedSectionCount)
        assertTrue(dispatcher.releaseControlTransfer(train.id, "driver-1"))

        train.setLocation(train.head.location.forward(40.0)!!)
        train.commit()
        dispatcher.tick(world)

        assertEquals(0, dispatcher.status(id)?.confirmedSectionCount)
        assertEquals(TimetableServiceRunState.REPLAN_REQUIRED, dispatcher.status(id)?.state)
    }

    @Test
    fun `straight route creates no synthetic resource claims across multiple legs`() {
        val world = createTestWorld().also(worlds::add)
        val dispatcher = dispatcher()
        val data = BasicWorldData(
            atoCoordinator = AtoWorldCoordinator("dispatcher-progress", reportSink = {})
        )
        TestBasicWorldDataRegistry.register(world, data)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(140.0, segmentCount = 1)
        )
        val train = createTrainWithConfig("T1", rail, world, listOf(10.0))
        val originArc = train.head.location.arcLength
        val viaArc = originArc + 30.0
        val targetArc = originArc + 70.0
        data.wayPoints.add(WayPoint("A", "A", rail.id, originArc))
        data.wayPoints.add(WayPoint("B", "B", rail.id, viaArc))
        data.wayPoints.add(WayPoint("C", "C", rail.id, targetArc))
        data.timetables.add(
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

        assertTrue(dispatcher.startService(world, "tt", "s1") is TimetableServiceStartResult.Started)
        val ato = train.controller as SimpleATO
        tickAndPublish(ato)
        dispatcher.tick(world)
        assertEquals(0, dispatcher.status(TimetableServiceRunId("tt", "s1"))?.confirmedSectionCount)

        var observedHeadOnlyPastVia = false
        var observedTailRelease = false
        repeat(4_000) {
            tickAndPublish(ato)
            dispatcher.tick(world)
            val count = dispatcher.status(TimetableServiceRunId("tt", "s1"))?.confirmedSectionCount
            if (train.head.location.arcLength > viaArc + 0.2 &&
                train.tailBogie.location.arcLength < viaArc - 0.2
            ) {
                observedHeadOnlyPastVia = true
                assertEquals(0, count)
            }
            if (train.tailBogie.location.arcLength >= viaArc && count == 0) {
                observedTailRelease = true
            }
            if (dispatcher.status(TimetableServiceRunId("tt", "s1"))?.state ==
                TimetableServiceRunState.COMPLETED
            ) return@repeat
        }

        assertTrue(observedHeadOnlyPastVia)
        assertTrue(observedTailRelease)
        assertEquals(targetArc, train.head.location.arcLength, 0.4)
        assertEquals(
            TimetableServiceRunState.COMPLETED,
            dispatcher.status(TimetableServiceRunId("tt", "s1"))?.state
        )
    }

    @Test
    fun `advances through dwell and a later movement mission`() {
        val world = createTestWorld().also(worlds::add)
        val dispatcher = dispatcher()
        val data = BasicWorldData(
            atoCoordinator = AtoWorldCoordinator("dispatcher-sequence", reportSink = {})
        )
        TestBasicWorldDataRegistry.register(world, data)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(140.0, segmentCount = 1)
        )
        val train = createTrainWithConfig("T1", rail, world, listOf(10.0))
        val originArc = train.head.location.arcLength
        data.wayPoints.add(WayPoint("A", "A", rail.id, originArc))
        data.wayPoints.add(WayPoint("B", "B", rail.id, originArc + 30.0))
        data.wayPoints.add(WayPoint("C", "C", rail.id, originArc + 65.0))
        data.timetables.add(
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

        assertTrue(dispatcher.startService(world, "tt", "s1") is TimetableServiceStartResult.Started)
        val ato = train.controller as SimpleATO
        var observedDwell = false
        var firstRevision = -1L
        repeat(5_000) {
            tickAndPublish(ato)
            dispatcher.tick(world)
            val status = dispatcher.status(TimetableServiceRunId("tt", "s1"))!!
            if (firstRevision < 0L) firstRevision = status.missionRevision
            if (status.state == TimetableServiceRunState.DWELLING) observedDwell = true
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
        val world = createTestWorld().also(worlds::add)
        val dispatcher = dispatcher()
        val data = BasicWorldData()
        TestBasicWorldDataRegistry.register(world, data)
        val approach = rail(world, Vector3d(0.0, 0.0, 0.0), Vector3d(30.0, 0.0, 0.0))
        val straight = rail(world, Vector3d(30.0, 0.0, 0.0), Vector3d(60.0, 0.0, 0.0))
        val branch = rail(world, Vector3d(30.0, 0.0, 0.0), Vector3d(60.0, 0.0, 10.0))
        CoreRail.connect(approach, straight)
        CoreRail.connect(approach, branch)
        approach.viewAB.selectNextRail(straight.viewAB)
        val train = createTrainWithConfig("near-turnout", approach, world, listOf(10.0))
        val origin = train.head.location.arcLength
        assertTrue(30.0 - origin in 5.0..20.0)
        data.wayPoints.add(WayPoint("A", "A", approach.id, origin))
        data.wayPoints.add(WayPoint("B", "B", branch.id, 20.0))
        data.timetables.add(Timetable("tt", services = listOf(service("s", train.id, "A", "B"))))

        assertTrue(dispatcher.startService(world, "tt", "s") is TimetableServiceStartResult.Started)
        tickAndPublish(train.controller as SimpleATO)
        dispatcher.tick(world)

        val status = dispatcher.status(TimetableServiceRunId("tt", "s"))
        assertEquals(1, status?.confirmedSectionCount, "$status occupied=${train.occupiedRailIntervals}")
        assertSame(branch.viewAB, approach.viewAB.nextActiveRail)
        assertFalse(data.atoCoordinator.isStalled)
    }

    @Test
    fun `unconfirmed later section stops at its protection point without stalling`() {
        val world = createTestWorld().also(worlds::add)
        val dispatcher = dispatcher()
        val data = BasicWorldData(
            atoCoordinator = AtoWorldCoordinator("dispatcher-authority", reportSink = {})
        )
        TestBasicWorldDataRegistry.register(world, data)
        val approach = rail(world, Vector3d(0.0, 0.0, 0.0), Vector3d(100.0, 0.0, 0.0))
        val mainBranch = rail(world, Vector3d(100.0, 0.0, 0.0), Vector3d(150.0, 0.0, 0.0))
        val blockingBranch = rail(world, Vector3d(100.0, 0.0, 0.0), Vector3d(150.0, 0.0, 20.0))
        CoreRail.connect(approach, mainBranch)
        CoreRail.connect(approach, blockingBranch)
        val mainTrain = createTrainWithConfig("main", approach, world, listOf(10.0))
        val blockingTrain = createTrainWithConfig("blocking", approach, world, listOf(10.0))
        blockingTrain.setLocation(mainTrain.head.location.forward(60.0)!!)
        blockingTrain.commit()
        val mainOrigin = mainTrain.head.location.arcLength
        val protectionArc = 70.0
        data.wayPoints.add(WayPoint("M0", "M0", approach.id, mainOrigin))
        data.wayPoints.add(WayPoint("P", "P", approach.id, protectionArc))
        data.wayPoints.add(WayPoint("M1", "M1", mainBranch.id, 20.0))
        data.wayPoints.add(WayPoint("B0", "B0", approach.id, blockingTrain.head.location.arcLength))
        data.wayPoints.add(WayPoint("B1", "B1", blockingBranch.id, 20.0))
        data.timetables.add(
            Timetable(
                "tt",
                services = listOf(
                    service("blocking", blockingTrain.id, "B0", "B1", tick = 0L),
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

        assertTrue(dispatcher.startService(world, "tt", "blocking") is TimetableServiceStartResult.Started)
        assertTrue(dispatcher.startService(world, "tt", "main") is TimetableServiceStartResult.Started)
        val blockingAto = blockingTrain.controller as SimpleATO
        val mainAto = mainTrain.controller as SimpleATO
        for (ignored in 0 until 2_000) {
            tickAndPublish(blockingAto)
            tickAndPublish(mainAto)
            dispatcher.tick(world)
            if (dispatcher.status(TimetableServiceRunId("tt", "blocking"))
                    ?.confirmedSectionCount == 1
            ) break
        }
        repeat(2_000) {
            tickAndPublish(mainAto)
            dispatcher.tick(world)
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
        assertTrue(mainTrain.head.location.arcLength >= 74.0)
        assertTrue(!data.atoCoordinator.isStalled)
        assertSame(blockingBranch.viewAB, approach.viewAB.nextActiveRail)
    }

    @Test
    fun `short turnout claim releases for a later conflicting service`() {
        val world = createTestWorld().also(worlds::add)
        val dispatcher = dispatcher()
        val data = BasicWorldData(
            atoCoordinator = AtoWorldCoordinator("dispatcher-conflict", reportSink = {})
        )
        TestBasicWorldDataRegistry.register(world, data)
        val approach = rail(world, Vector3d(0.0, 0.0, 0.0), Vector3d(100.0, 0.0, 0.0))
        val firstBranch = rail(world, Vector3d(100.0, 0.0, 0.0), Vector3d(150.0, 0.0, 0.0))
        val secondBranch = rail(world, Vector3d(100.0, 0.0, 0.0), Vector3d(150.0, 0.0, 20.0))
        CoreRail.connect(approach, firstBranch)
        CoreRail.connect(approach, secondBranch)
        val firstTrain = createTrainWithConfig("T1", approach, world, listOf(10.0))
        val secondTrain = createTrainWithConfig("T2", approach, world, listOf(10.0))
        val secondOriginArc = firstTrain.head.location.arcLength + 40.0
        secondTrain.setLocation(RailLocationWithFacing(secondBranch.viewAB, 40.0))
        secondTrain.commitMaintenancePlacement()
        data.wayPoints.add(WayPoint("A1", "A1", approach.id, firstTrain.head.location.arcLength))
        data.wayPoints.add(WayPoint("A2", "A2", approach.id, secondOriginArc))
        data.wayPoints.add(WayPoint("B1", "B1", firstBranch.id, 20.0))
        data.wayPoints.add(WayPoint("B2", "B2", secondBranch.id, 20.0))
        data.timetables.add(
            Timetable(
                id = "tt",
                services = listOf(
                    service("s1", firstTrain.id, "A1", "B1"),
                    service("s2", secondTrain.id, "A2", "B2")
                )
            )
        )

        assertTrue(dispatcher.startService(world, "tt", "s1") is TimetableServiceStartResult.Started)
        val firstAto = firstTrain.controller as SimpleATO
        repeat(2_000) {
            tickAndPublish(firstAto)
            dispatcher.tick(world)
        }
        assertEquals(
            TimetableServiceRunState.COMPLETED,
            dispatcher.status(TimetableServiceRunId("tt", "s1"))?.state,
            "status=${dispatcher.status(TimetableServiceRunId("tt", "s1"))} stall=${data.atoCoordinator.lastStall}"
        )
        secondTrain.setLocation(RailLocationWithFacing(approach.viewAB, secondOriginArc))
        secondTrain.commitMaintenancePlacement()
        assertTrue(dispatcher.startService(world, "tt", "s2") is TimetableServiceStartResult.Started)
        val secondAto = secondTrain.controller as SimpleATO
        repeat(2_000) {
            tickAndPublish(secondAto)
            dispatcher.tick(world)
        }

        val firstStatus = dispatcher.status(TimetableServiceRunId("tt", "s1"))
        val secondStatus = dispatcher.status(TimetableServiceRunId("tt", "s2"))
        assertEquals(
            TimetableServiceRunState.COMPLETED,
            firstStatus?.state,
            "$firstStatus / $secondStatus / stall=${data.atoCoordinator.lastStall}"
        )
        assertEquals(TimetableServiceRunState.COMPLETED, secondStatus?.state, "$firstStatus / $secondStatus")
        assertFalse(data.atoCoordinator.isStalled)
    }

    @Test
    fun `world stall aborts remaining route allocations in the same dispatcher tick`() {
        val world = createTestWorld().also(worlds::add)
        val dispatcher = dispatcher()
        val coordinator = AtoWorldCoordinator("dispatcher-stall-boundary", reportSink = {})
        val data = BasicWorldData(atoCoordinator = coordinator)
        TestBasicWorldDataRegistry.register(world, data)
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
        data.wayPoints.add(WayPoint("F0", "F0", firstRail.id, firstTrain.head.location.arcLength))
        data.wayPoints.add(WayPoint("F1", "F1", firstRail.id, 80.0))
        data.wayPoints.add(WayPoint("S0", "S0", secondRail.id, secondTrain.head.location.arcLength))
        data.wayPoints.add(WayPoint("S1", "S1", secondRail.id, 80.0))
        data.timetables.add(
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
        assertTrue(dispatcher.startService(world, firstId.timetableId, firstId.serviceId) is TimetableServiceStartResult.Started)
        assertTrue(dispatcher.startService(world, secondId.timetableId, secondId.serviceId) is TimetableServiceStartResult.Started)
        tickAndPublish(firstTrain.controller as SimpleATO)
        tickAndPublish(secondTrain.controller as SimpleATO)
        secondTrain.setController(null)

        dispatcher.tick(world)

        assertTrue(coordinator.isStalled)
        assertEquals(TimetableServiceRunState.FAILED, dispatcher.status(secondId)?.state)
        assertEquals(0, dispatcher.status(firstId)?.confirmedSectionCount)
    }

    @Test
    fun `short claims let physically separated conflicting services proceed`() {
        val world = createTestWorld().also(worlds::add)
        val dispatcher = dispatcher()
        val data = BasicWorldData(
            atoCoordinator = AtoWorldCoordinator("dispatcher-priority", reportSink = {})
        )
        TestBasicWorldDataRegistry.register(world, data)
        val approach = rail(world, Vector3d(0.0, 0.0, 0.0), Vector3d(100.0, 0.0, 0.0))
        val lateBranch = rail(world, Vector3d(100.0, 0.0, 0.0), Vector3d(150.0, 0.0, 0.0))
        val earlyBranch = rail(world, Vector3d(100.0, 0.0, 0.0), Vector3d(150.0, 0.0, 20.0))
        CoreRail.connect(approach, lateBranch)
        CoreRail.connect(approach, earlyBranch)
        val lateTrain = createTrainWithConfig("late-train", approach, world, listOf(10.0))
        val earlyTrain = createTrainWithConfig("early-train", approach, world, listOf(10.0))
        earlyTrain.setLocation(lateTrain.head.location.forward(40.0)!!)
        earlyTrain.commit()
        data.wayPoints.add(WayPoint("L0", "L0", approach.id, lateTrain.head.location.arcLength))
        data.wayPoints.add(WayPoint("E0", "E0", approach.id, earlyTrain.head.location.arcLength))
        data.wayPoints.add(WayPoint("L1", "L1", lateBranch.id, 20.0))
        data.wayPoints.add(WayPoint("E1", "E1", earlyBranch.id, 20.0))
        data.timetables.add(
            Timetable(
                "tt",
                services = listOf(
                    service("late", lateTrain.id, "L0", "L1", tick = 100L),
                    service("early", earlyTrain.id, "E0", "E1", tick = 10L)
                )
            )
        )

        dispatcher.beginTimetable(world, "tt")
        repeat(100) { dispatcher.tick(world) }
        assertTrue(dispatcher.startService(world, "tt", "late") is TimetableServiceStartResult.Started)
        assertTrue(dispatcher.startService(world, "tt", "early") is TimetableServiceStartResult.Started)
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
            dispatcher.tick(world)
        }

        assertEquals(
            TimetableServiceRunState.COMPLETED,
            dispatcher.status(TimetableServiceRunId("tt", "early"))?.state,
            "early=${dispatcher.status(TimetableServiceRunId("tt", "early"))} " +
                "late=${dispatcher.status(TimetableServiceRunId("tt", "late"))} " +
                "stall=${data.atoCoordinator.lastStall}"
        )
        assertEquals(
            TimetableServiceRunState.COMPLETED,
            dispatcher.status(TimetableServiceRunId("tt", "late"))?.state
        )
        assertFalse(data.atoCoordinator.isStalled)
    }

    @Test
    fun `wait-until-true activation starts a future cycle only after provider becomes true`() {
        val world = createTestWorld().also(worlds::add)
        val dispatcher = dispatcher()
        val data = BasicWorldData(
            atoCoordinator = AtoWorldCoordinator("dispatcher-activation", reportSink = {})
        )
        TestBasicWorldDataRegistry.register(world, data)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        val train = createTrainWithConfig("T1", rail, world, listOf(10.0))
        val originArc = train.head.location.arcLength
        data.wayPoints.add(WayPoint("A", "A", rail.id, originArc))
        data.wayPoints.add(WayPoint("B", "B", rail.id, originArc + 30.0))
        var enabled = false
        var evaluations = 0
        data.timetableExtensions.registerCondition(object : TimetableConditionProvider {
            override val typeId = "test:demand"
            override val version = 1
            override val descriptor = TimetableExtensionDescriptor("Demand", "Test demand")
            override fun evaluate(parameters: JsonObject, context: TimetableRuntimeContext): TimetableConditionResult {
                evaluations++
                assertNull(context.train)
                return if (enabled) TimetableConditionResult.True else TimetableConditionResult.False
            }
        })
        data.timetables.add(Timetable(
            "tt-activation",
            services = listOf(service("service", train.id, "A", "B").copy(
                activation = TimetableServiceActivation.Conditional(
                    TimetableCondition.Predicate(TimetableExtensionCall("test:demand")),
                    TimetableActivationFalseBehavior.WAIT_UNTIL_TRUE
                )
            ))
        ))

        assertTrue(dispatcher.beginTimetable(world, "tt-activation") is TimetableEpochStartResult.Started)
        assertTrue(dispatcher.scheduleInitialCycle("tt-activation"))
        dispatcher.tick(world)
        assertNull(dispatcher.status(TimetableServiceRunId("tt-activation", "service")))
        assertEquals(1, evaluations)

        enabled = true
        dispatcher.tick(world)

        val started = dispatcher.status(TimetableServiceRunId("tt-activation", "service"))
        assertNotNull(started)
        assertEquals(0L, started?.cycleIndex)
        assertEquals(2, evaluations)
    }

    @Test
    fun `evaluates runtime condition once and waits for registered extension action ticket`() {
        val world = createTestWorld().also(worlds::add)
        val dispatcher = dispatcher()
        val data = BasicWorldData(
            atoCoordinator = AtoWorldCoordinator("dispatcher-extension", reportSink = {})
        )
        TestBasicWorldDataRegistry.register(world, data)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        val train = createTrainWithConfig("T1", rail, world, listOf(10.0))
        val originArc = train.head.location.arcLength
        data.wayPoints.add(WayPoint("A", "A", rail.id, originArc))
        data.wayPoints.add(WayPoint("B", "B", rail.id, originArc + 30.0))

        var evaluations = 0
        var actionStarts = 0
        val extensionDescriptor = TimetableExtensionDescriptor("Test", "Test extension")
        data.timetableExtensions.registerCondition(object : TimetableConditionProvider {
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
        data.timetableExtensions.registerAction(object : TimetableActionProvider {
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
        data.timetables.add(Timetable(
            "tt-extension",
            services = listOf(TimetableService(
                id = "service",
                trainSelector = TrainSelector.TrainId(train.id),
                steps = listOf(
                    TimetableStep.Check("A"),
                    TimetableStep.At(0L, TimetableAction.GoTo("B")),
                    TimetableStep.At(0L, TimetableAction.Conditional(
                        condition = TimetableCondition.Predicate(
                            TimetableExtensionCall("test:enabled")
                        ),
                        thenActions = listOf(TimetableAction.Extension(
                            TimetableExtensionCall("test:record")
                        ))
                    ))
                )
            ))
        ))

        assertTrue(dispatcher.startService(world, "tt-extension", "service") is TimetableServiceStartResult.Started)
        val ato = train.controller as SimpleATO
        tickAndPublish(ato)
        dispatcher.tick(world)
        repeat(600) { tickAndPublish(ato) }
        dispatcher.tick(world)

        assertEquals(TimetableServiceRunState.WAITING_EXTENSION_ACTION,
            dispatcher.status(TimetableServiceRunId("tt-extension", "service"))?.state)
        assertEquals(1, evaluations)
        assertEquals(1, actionStarts)

        dispatcher.tick(world)
        assertEquals(TimetableServiceRunState.COMPLETED,
            dispatcher.status(TimetableServiceRunId("tt-extension", "service"))?.state)
        assertEquals(1, evaluations)
        assertEquals(1, actionStarts)
    }

    private fun dispatcher(): TimetableDispatcher = TimetableDispatcher(
        routeAdmissionContextProvider = null,
        atoFactory = TimetableAtoFactory { train, world ->
            SimpleATO(train, world, KinematicTrainActuator(10.0, profile), profile)
        }
    )

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
        tick: Long = 0L
    ): TimetableService = TimetableService(
        id = serviceId,
        trainSelector = TrainSelector.TrainId(trainId),
        steps = listOf(
            TimetableStep.Check(origin),
            TimetableStep.At(tick, TimetableAction.GoTo(target))
        )
    )

    private fun tickAndPublish(ato: SimpleATO, deltaMillis: Double = 50.0) {
        AsyncTickTestSupport.run { ato.tick(deltaMillis) }
        ato.commit()
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
