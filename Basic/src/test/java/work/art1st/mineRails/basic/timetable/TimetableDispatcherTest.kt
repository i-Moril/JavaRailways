package work.art1st.mineRails.basic.timetable

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
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
    private val profile = AtoSafetyProfile(
        accelerationMetersPerSecondSquared = 1.0,
        serviceDecelerationMetersPerSecondSquared = 1.0,
        reactionTimeSeconds = 0.0,
        safetyDistanceMeters = 2.0,
        stopToleranceMeters = 0.25
    )

    @Test
    fun `starts explicit train holds until first section is confirmed then releases`() {
        val world = createTestWorld()
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

        assertTrue(dispatcher.stopService(world, TimetableServiceRunId("tt", "s1")))
        assertNull(train.controller)
    }

    @Test
    fun `conflicting services keep the later train held without partial turnout changes`() {
        val world = createTestWorld()
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
        target: String
    ): TimetableService = TimetableService(
        id = serviceId,
        trainSelector = TrainSelector.TrainId(trainId),
        steps = listOf(
            TimetableStep.Check(origin),
            TimetableStep.At(0L, TimetableAction.GoTo(target))
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
