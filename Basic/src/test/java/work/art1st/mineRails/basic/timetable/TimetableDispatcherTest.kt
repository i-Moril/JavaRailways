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
import work.art1st.mineRails.basic.controller.SimpleATO
import work.art1st.mineRails.basic.pathFinding.TestBasicWorldDataRegistry
import work.art1st.mineRails.core.api.physics.curve.Curve
import work.art1st.mineRails.core.api.physics.curve.PrimeCurve
import work.art1st.mineRails.core.physics.CoreWorld
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
