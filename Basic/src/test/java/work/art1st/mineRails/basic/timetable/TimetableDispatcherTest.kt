package work.art1st.mineRails.basic.timetable

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import work.art1st.mineRails.basic.BasicWorldData
import work.art1st.mineRails.basic.WayPoint
import work.art1st.mineRails.basic.api.ato.AtoSafetyProfile
import work.art1st.mineRails.basic.api.ato.AtoWorldCoordinator
import work.art1st.mineRails.basic.api.ato.KinematicTrainActuator
import work.art1st.mineRails.basic.api.depot.DepotId
import work.art1st.mineRails.basic.api.depot.DepotTrainSelector
import work.art1st.mineRails.basic.api.depot.RollingStockRecord
import work.art1st.mineRails.basic.api.depot.RollingStockState
import work.art1st.mineRails.basic.api.depot.StablingPointRef
import work.art1st.mineRails.basic.api.destination.DestinationRole
import work.art1st.mineRails.basic.api.destination.OperationalDestination
import work.art1st.mineRails.basic.api.destination.OperationalDestinationService
import work.art1st.mineRails.basic.controller.ManualController
import work.art1st.mineRails.basic.controller.SimpleATO
import work.art1st.mineRails.basic.pathFinding.TestBasicWorldDataRegistry
import work.art1st.mineRails.core.api.physics.curve.Curve
import work.art1st.mineRails.core.api.physics.curve.PrimeCurve
import work.art1st.mineRails.core.physics.CoreWorld
import work.art1st.mineRails.core.api.physics.rail.ViewDirection
import work.art1st.mineRails.core.physics.rail.CoreRail
import work.art1st.mineRails.core.physics.train.createStraightRailFromSegments
import work.art1st.mineRails.core.physics.train.createTestWorld
import work.art1st.mineRails.core.physics.train.createTrainWithConfig
import work.art1st.mineRails.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.mineRails.core.util.AsyncTickTestSupport

class TimetableDispatcherTest {
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
    fun `starts explicit train only after first section confirmation and keeps final route held`() {
        val world = createTestWorld().also(worlds::add)
        val dispatcher = dispatcher()
        val data = BasicWorldData(
            dispatcher = dispatcher,
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
        assertEquals(0.0, train.speed, 1.0e-9)

        dispatcher.tick(world)
        assertEquals(
            TimetableServiceRunState.RUNNING,
            dispatcher.status(TimetableServiceRunId("tt", "s1"))?.state
        )
        tickAndPublish(ato, 1_000.0)

        assertEquals(1.0, train.speed, 1.0e-9)
        assertEquals(1, ato.currentConfirmedRouteWindows.size)

        repeat(600) { tickAndPublish(ato) }
        assertEquals(0.0, train.speed, profile.stoppedSpeedThresholdMetersPerSecond)
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
            TimetableServiceRunState.STOPPED,
            dispatcher.status(TimetableServiceRunId("tt", "s1"))?.state
        )
        assertNotNull(train.controller)
        assertEquals(1, dispatcher.status(TimetableServiceRunId("tt", "s1"))?.confirmedSectionCount)
    }

    @Test
    fun `takeFromDepot deterministically assigns a compatible available train`() {
        val world = createTestWorld().also(worlds::add)
        val dispatcher = dispatcher()
        val data = BasicWorldData(
            dispatcher = dispatcher,
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
                id = "north-one",
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
                                0L,
                                TimetableAction.TakeFromDepot(
                                    DepotTrainSelector(
                                        depotId,
                                        requiredTags = setOf("metro"),
                                        requiredCapabilities = setOf("doors")
                                    )
                                )
                            ),
                            TimetableStep.At(0L, TimetableAction.GoTo("B"))
                        )
                    )
                )
            )
        )

        val started = dispatcher.startService(world, "tt", "s1") as
            TimetableServiceStartResult.Started

        assertEquals(train.id, started.run.trainId)
        assertEquals(RollingStockState.IN_SERVICE, data.depots.rollingStock(train.id)?.state)
        assertEquals("tt/s1@cycle-0", data.depots.rollingStock(train.id)?.assignmentId)
        assertTrue(train.controller is SimpleATO)
    }

    @Test
    fun `storeAtDepot releases departure occupancy and completes availability handoff`() {
        val world = createTestWorld().also(worlds::add)
        val dispatcher = dispatcher()
        val data = BasicWorldData(
            dispatcher = dispatcher,
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
                "north-out",
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
                "north-in",
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
        assertTrue(status.confirmedSectionCount > 0)
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
        assertTrue(departure is TimetableServiceStartResult.Started, departure.toString())
        val departureAto = train.controller as SimpleATO
        repeat(2_000) {
            tickAndPublish(departureAto)
            dispatcher.tick(world)
        }
        val departureStatus = dispatcher.status(
            TimetableServiceRunId("tt-depart-again", "s1")
        )!!
        assertEquals(TimetableServiceRunState.COMPLETED, departureStatus.state)
        assertEquals(1, departureStatus.confirmedSectionCount)
        assertEquals(RollingStockState.IN_SERVICE, data.depots.rollingStock(train.id)?.state)
        assertEquals(null, data.depots.stablingPoint(StablingPointRef(depotId, "in"))?.occupantTrainId)
        assertTrue(!data.atoCoordinator.isStalled)
    }

    @Test
    fun `period boundary automatically creates the next dynamic service cycle`() {
        val world = createTestWorld().also(worlds::add)
        val dispatcher = dispatcher()
        val data = BasicWorldData(
            dispatcher = dispatcher,
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
                "north-one",
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
                "north-two",
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
        assertTrue(!data.atoCoordinator.isStalled)
    }

    @Test
    fun `next cycle never preempts a train still active in the previous cycle`() {
        val world = createTestWorld().also(worlds::add)
        val dispatcher = dispatcher()
        val coordinator = AtoWorldCoordinator("dispatcher-cycle-overlap", reportSink = {})
        val data = BasicWorldData(dispatcher = dispatcher, atoCoordinator = coordinator)
        TestBasicWorldDataRegistry.register(world, data)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        val train = createTrainWithConfig("T1", rail, world, listOf(10.0))
        val originArc = train.head.location.arcLength
        OperationalDestinationService(world).register(
            OperationalDestination(
                "north-one",
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
        val data = BasicWorldData(dispatcher = dispatcher, atoCoordinator = coordinator)
        TestBasicWorldDataRegistry.register(world, data)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        val train = createTrainWithConfig("T1", rail, world, listOf(10.0))
        val originArc = train.head.location.arcLength
        OperationalDestinationService(world).register(
            OperationalDestination(
                "north-one",
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
            dispatcher = dispatcher,
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
            dispatcher = lateDispatcher,
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
    fun `manual routed driver keeps dispatcher route assistance without automatic speed`() {
        val world = createTestWorld().also(worlds::add)
        val dispatcher = dispatcher()
        val data = BasicWorldData(
            dispatcher = dispatcher,
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
        assertTrue(completed.confirmedSectionCount > 0)
        assertTrue(!data.atoCoordinator.isStalled)
    }

    @Test
    fun `manual routed crossing of an unconfirmed protection point stalls the world`() {
        val world = createTestWorld().also(worlds::add)
        val dispatcher = dispatcher()
        val coordinator = AtoWorldCoordinator("dispatcher-manual-violation", reportSink = {})
        val data = BasicWorldData(dispatcher = dispatcher, atoCoordinator = coordinator)
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

        assertTrue(coordinator.isStalled)
        assertEquals("ManualRoutedSafetyViolation", coordinator.lastStall?.errorType)
        assertTrue(coordinator.lastStall?.message?.contains("unconfirmed protection") == true)
    }

    @Test
    fun `full manual requests no new route and release still requires replan`() {
        val world = createTestWorld().also(worlds::add)
        val dispatcher = dispatcher()
        val data = BasicWorldData(
            dispatcher = dispatcher,
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
        assertEquals(1, dispatcher.status(TimetableServiceRunId("tt", "s1"))?.confirmedSectionCount)
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
    fun `manual control transfer suspends ATO and requires explicit released replan`() {
        val world = createTestWorld().also(worlds::add)
        val dispatcher = dispatcher()
        val data = BasicWorldData(
            dispatcher = dispatcher,
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
            dispatcher = dispatcher,
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
        assertEquals(1, dispatcher.status(id)?.confirmedSectionCount)

        dispatcher.suspendForControlTransfer(world, train.id, "driver-1")
        dispatcher.tick(world)

        assertEquals(TimetableServiceRunState.SUSPENDED, dispatcher.status(id)?.state)
        assertEquals(1, dispatcher.status(id)?.confirmedSectionCount)
        assertTrue(dispatcher.releaseControlTransfer(train.id, "driver-1"))
        assertTrue(dispatcher.replanService(world, id, "A") is TimetableReplanResult.Failure)

        train.setLocation(train.head.location.forward(40.0)!!)
        train.commit()
        dispatcher.tick(world)

        assertEquals(0, dispatcher.status(id)?.confirmedSectionCount)
        assertEquals(TimetableServiceRunState.REPLAN_REQUIRED, dispatcher.status(id)?.state)
    }

    @Test
    fun `allocates the next leg by progress and releases only after the tail clears`() {
        val world = createTestWorld().also(worlds::add)
        val dispatcher = dispatcher()
        val data = BasicWorldData(
            dispatcher = dispatcher,
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
        assertEquals(1, dispatcher.status(TimetableServiceRunId("tt", "s1"))?.confirmedSectionCount)

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
                assertEquals(2, count)
            }
            if (train.tailBogie.location.arcLength >= viaArc && count == 1) {
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
            dispatcher = dispatcher,
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
    fun `unconfirmed later section stops at its protection point without stalling`() {
        val world = createTestWorld().also(worlds::add)
        val dispatcher = dispatcher()
        val data = BasicWorldData(
            dispatcher = dispatcher,
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
        blockingTrain.setLocation(mainTrain.head.location.forward(-10.0)!!)
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
        tickAndPublish(blockingAto)
        tickAndPublish(mainAto)
        dispatcher.tick(world)
        tickAndPublish(blockingAto)

        repeat(2_000) {
            tickAndPublish(mainAto)
            dispatcher.tick(world)
        }

        val status = dispatcher.status(TimetableServiceRunId("tt", "main"))!!
        assertEquals(TimetableServiceRunState.WAITING_ROUTE, status.state)
        assertEquals(0.0, mainTrain.speed, profile.stoppedSpeedThresholdMetersPerSecond)
        assertTrue(mainTrain.head.location.arcLength <= protectionArc)
        assertTrue(mainTrain.head.location.arcLength >= protectionArc - 4.0)
        assertTrue(!data.atoCoordinator.isStalled)
        assertSame(blockingBranch.viewAB, approach.viewAB.nextActiveRail)
    }

    @Test
    fun `conflicting services keep the later train held without partial turnout changes`() {
        val world = createTestWorld().also(worlds::add)
        val dispatcher = dispatcher()
        val data = BasicWorldData(
            dispatcher = dispatcher,
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
        secondTrain.setLocation(firstTrain.head.location.forward(40.0)!!)
        secondTrain.commit()
        data.wayPoints.add(WayPoint("A1", "A1", approach.id, firstTrain.head.location.arcLength))
        data.wayPoints.add(WayPoint("A2", "A2", approach.id, secondTrain.head.location.arcLength))
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
        assertTrue(dispatcher.startService(world, "tt", "s2") is TimetableServiceStartResult.Started)
        tickAndPublish(firstTrain.controller as SimpleATO)
        tickAndPublish(secondTrain.controller as SimpleATO)
        dispatcher.tick(world)

        assertEquals(
            TimetableServiceRunState.RUNNING,
            dispatcher.status(TimetableServiceRunId("tt", "s1"))?.state
        )
        assertEquals(
            TimetableServiceRunState.WAITING_ROUTE,
            dispatcher.status(TimetableServiceRunId("tt", "s2"))?.state
        )
        assertSame(firstBranch.viewAB, approach.viewAB.nextActiveRail)

        val firstAto = firstTrain.controller as SimpleATO
        tickAndPublish(firstAto)
        approach.viewAB.selectNextRail(secondBranch.viewAB)
        tickAndPublish(firstAto)
        assertTrue(data.atoCoordinator.isStalled)
        assertTrue(data.atoCoordinator.lastStall?.message?.contains("confirmed turnout mismatch") == true)
    }

    @Test
    fun `timetable tick orders simultaneous route conflicts`() {
        val world = createTestWorld().also(worlds::add)
        val dispatcher = dispatcher()
        val data = BasicWorldData(
            dispatcher = dispatcher,
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
        tickAndPublish(lateTrain.controller as SimpleATO)
        tickAndPublish(earlyTrain.controller as SimpleATO)
        dispatcher.tick(world)

        assertEquals(
            TimetableServiceRunState.RUNNING,
            dispatcher.status(TimetableServiceRunId("tt", "early"))?.state
        )
        assertEquals(
            TimetableServiceRunState.WAITING_ROUTE,
            dispatcher.status(TimetableServiceRunId("tt", "late"))?.state
        )
        assertSame(earlyBranch.viewAB, approach.viewAB.nextActiveRail)
    }

    private fun dispatcher(): TimetableDispatcher = TimetableDispatcher { train, world ->
        SimpleATO(train, world, KinematicTrainActuator(10.0, profile), profile)
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

    private fun rail(world: CoreWorld, from: Vector3d, to: Vector3d): CoreRail = CoreRail(
        world,
        straightCurve(from, to),
        mutableListOf(),
        mutableListOf()
    )

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
