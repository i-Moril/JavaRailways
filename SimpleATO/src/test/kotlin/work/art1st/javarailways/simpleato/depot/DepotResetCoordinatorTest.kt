package work.art1st.javarailways.simpleato.depot

import work.art1st.javarailways.core.physics.createRailForTest

import org.joml.Vector3d
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.BasicWorldData
import work.art1st.javarailways.basic.WayPoint
import work.art1st.javarailways.basic.api.ato.AtoSafetyProfile
import work.art1st.javarailways.basic.api.ato.AtoStallReport
import work.art1st.javarailways.basic.api.ato.AtoWorldCoordinator
import work.art1st.javarailways.basic.api.ato.KinematicTrainActuator
import work.art1st.javarailways.basic.api.depot.*
import work.art1st.javarailways.basic.api.destination.DestinationRole
import work.art1st.javarailways.basic.api.destination.OperationalDestination
import work.art1st.javarailways.basic.api.destination.OperationalDestinationService
import work.art1st.javarailways.simpleato.controller.SimpleATO
import work.art1st.javarailways.simpleato.restriction.emptyInspectionRuntime
import work.art1st.javarailways.simpleato.restriction.emptyTrainRestrictionSnapshots
import work.art1st.javarailways.basic.pathFinding.TestBasicWorldDataRegistry
import work.art1st.javarailways.basic.pathFinding.basicWorldData
import work.art1st.javarailways.basic.timetable.Timetable
import work.art1st.javarailways.basic.timetable.TimetableAction
import work.art1st.javarailways.simpleato.timetable.TimetableDispatcher
import work.art1st.javarailways.simpleato.timetable.GrantingRouteAdmissionTestContext
import work.art1st.javarailways.simpleato.timetable.UnavailableRouteAdmissionTestContext
import work.art1st.javarailways.simpleato.timetable.TimetableAtoFactory
import work.art1st.javarailways.basic.timetable.TimetableService
import work.art1st.javarailways.simpleato.timetable.TimetableServiceRunId
import work.art1st.javarailways.simpleato.timetable.TimetableServiceStartResult
import work.art1st.javarailways.simpleato.timetable.acceptingTimetableAppliedPlanService
import work.art1st.javarailways.basic.timetable.TimetableStep
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.createTestWorld
import work.art1st.javarailways.core.physics.train.createTrainWithConfig
import work.art1st.javarailways.core.util.AsyncTickTestSupport
import work.art1st.javarailways.core.util.ThreadGuard
import work.art1st.javarailways.core.util.ThreadGuardPlatformBridge

class DepotResetCoordinatorTest {
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
    fun `successful reset relocates trains rebuilds ledgers and keeps world stalled`() {
        val world = createTestWorld().also(worlds::add)
        val inspectionRuntime = emptyInspectionRuntime()
        val dispatcher = TimetableDispatcher(
            routeAdmissionContextProvider = GrantingRouteAdmissionTestContext { world },
            signalRestrictionSnapshots = emptyTrainRestrictionSnapshots,
            inspectionRuntime = inspectionRuntime,
            appliedPlanService = acceptingTimetableAppliedPlanService(),
            atoFactory = TimetableAtoFactory { train, trainWorld ->
                SimpleATO(
                    train,
                    trainWorld,
                    KinematicTrainActuator(10.0, profile),
                    profile,
                    inspectionRuntime::maximumDeclaredDistanceMeters
                )
            }
        )
        val coordinator = AtoWorldCoordinator("reset-world", reportSink = {})
        val data = BasicWorldData(atoCoordinator = coordinator)
        TestBasicWorldDataRegistry.register(world, data)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(120.0, segmentCount = 1)
        )
        val train = createTrainWithConfig("T1", rail, world, listOf(10.0))
        val originArc = train.head.location.arcLength
        val depotId = DepotId("north")
        OperationalDestinationService(world).register(
            OperationalDestination(
                "north@one",
                "North one",
                rail.id,
                originArc,
                ViewDirection.AB,
                DestinationRole.DepotStablingPointRole(
                    "north",
                    "one",
                    usableLengthMeters = 20.0
                )
            )
        )
        data.wayPoints.add(WayPoint("B", "B", rail.id, originArc + 60.0))
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
                "tt",
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
        assertTrue(
            dispatcher.startService(world, "tt", "s1") is TimetableServiceStartResult.Started
        )
        val ato = train.controller as SimpleATO
        AsyncTickTestSupport.run { ato.tick(50.0) }
        ato.commit()
        assertFalse(dispatcher.status(TimetableServiceRunId("tt", "s1"))?.routeGrantActive == true)
        coordinator.stall(report(coordinator.worldId, train.id))

        val result = DepotResetCoordinator(world, dispatcher).reset(setOf(depotId))

        assertTrue(result is DepotResetResult.Succeeded, result.report.toString())
        val success = result.report
        assertEquals(DepotResetStatus.SUCCEEDED, success.status)
        assertEquals(1, success.removedRunCount)
        assertNull(dispatcher.status(TimetableServiceRunId("tt", "s1")))
        assertNull(train.controller)
        assertEquals(originArc, train.head.location.arcLength, 1.0e-6)
        assertEquals(RollingStockState.AVAILABLE, data.depots.rollingStock(train.id)?.state)
        assertEquals(
            train.id,
            data.depots.stablingPoint(StablingPointRef(depotId, "one"))?.occupantTrainId
        )
        assertTrue(coordinator.isStalled)
        val encoded = DepotResetReportJson.encode(success)
        assertTrue(encoded.contains("\"schemaVersion\": 2"))
        assertTrue(encoded.contains("\"status\": \"SUCCEEDED\""))
        assertFalse(encoded.contains("releasedRouteSectionCount"))
    }

    @Test
    fun `failed placement restores every train including the train that threw mid-move`() {
        val world = createTestWorld().also(worlds::add)
        val coordinator = AtoWorldCoordinator("reset-placement-rollback", reportSink = {})
        val data = BasicWorldData(atoCoordinator = coordinator)
        TestBasicWorldDataRegistry.register(world, data)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(140.0, segmentCount = 1)
        )
        val safeTrain = createTrainWithConfig("A-safe", rail, world, listOf(10.0))
        val riskyTrain = createTrainWithConfig("B-risky", rail, world, listOf(10.0))
        riskyTrain.setLocation(safeTrain.head.location.forward(30.0)!!)
        riskyTrain.commit()
        val depotId = DepotId("north")
        OperationalDestinationService(world).register(
            OperationalDestination(
                "north@safe",
                "North safe",
                rail.id,
                100.0,
                ViewDirection.AB,
                DestinationRole.DepotStablingPointRole(
                    "north",
                    "safe",
                    usableLengthMeters = 20.0,
                    allowedTags = setOf("safe")
                )
            )
        )
        OperationalDestinationService(world).register(
            OperationalDestination(
                "north@risky",
                "North risky",
                rail.id,
                6.0,
                ViewDirection.AB,
                DestinationRole.DepotStablingPointRole(
                    "north",
                    "risky",
                    usableLengthMeters = 20.0,
                    allowedTags = setOf("risky")
                )
            )
        )
        data.depots.registerRollingStock(
            RollingStockRecord(
                safeTrain.id,
                depotId,
                "test/safe",
                setOf("safe"),
                1,
                10.0,
                20.0,
                emptySet()
            ),
            StablingPointRef(depotId, "safe")
        )
        data.depots.registerRollingStock(
            RollingStockRecord(
                riskyTrain.id,
                depotId,
                "test/risky",
                setOf("risky"),
                1,
                10.0,
                20.0,
                emptySet()
            ),
            StablingPointRef(depotId, "risky")
        )
        onMainThread { safeTrain.reverse() }
        onMainThread { safeTrain.derail("test pre-reset state") }
        val beforeSafeHead = safeTrain.head.location.arcLengthIn(ViewDirection.AB)
        val beforeRiskyHead = riskyTrain.head.location.arcLengthIn(ViewDirection.AB)
        val beforeRiskyBogies = riskyTrain.cars.flatMap { car -> car.bogies }
            .map { it.location.arcLengthIn(ViewDirection.AB) }
        val beforeStock = data.depots.allRollingStock()
        val beforePoints = data.depots.stablingPoints(depotId)
        coordinator.stall(report(coordinator.worldId, riskyTrain.id))

        val result = onMainThread {
            DepotResetCoordinator(world, TimetableDispatcher(
                UnavailableRouteAdmissionTestContext,
                emptyTrainRestrictionSnapshots,
                emptyInspectionRuntime(),
                acceptingTimetableAppliedPlanService()
            ))
                .reset(setOf(depotId))
        }
        riskyTrain.commit()

        assertTrue(result is DepotResetResult.Failed)
        assertEquals("TRAIN_PLACEMENT", result.report.failureStage)
        assertEquals(beforeSafeHead, safeTrain.head.location.arcLengthIn(ViewDirection.AB), 1.0e-9)
        assertTrue(safeTrain.isReversed)
        assertTrue(safeTrain.isDerailed)
        assertEquals(beforeRiskyHead, riskyTrain.head.location.arcLengthIn(ViewDirection.AB), 1.0e-9)
        assertEquals(
            beforeRiskyBogies,
            riskyTrain.cars.flatMap { car -> car.bogies }
                .map { it.location.arcLengthIn(ViewDirection.AB) }
        )
        assertEquals(beforeStock, data.depots.allRollingStock())
        assertEquals(beforePoints, data.depots.stablingPoints(depotId))
    }

    @Test
    fun `reset rejects actual train envelopes that overlap across connected rails`() {
        val world = createTestWorld().also(worlds::add)
        val coordinator = AtoWorldCoordinator("reset-cross-rail-envelope", reportSink = {})
        val data = BasicWorldData(atoCoordinator = coordinator)
        TestBasicWorldDataRegistry.register(world, data)
        val firstRail = rail(
            world,
            Vector3d(0.0, 0.0, 0.0),
            Vector3d(100.0, 0.0, 0.0)
        )
        val secondRail = rail(
            world,
            Vector3d(100.0, 0.0, 0.0),
            Vector3d(200.0, 0.0, 0.0)
        )
        CoreRail.connect(firstRail, secondRail)
        val firstTrain = createTrainWithConfig("A-cross", firstRail, world, listOf(10.0))
        val secondTrain = createTrainWithConfig("B-cross", secondRail, world, listOf(10.0))
        secondTrain.setLocation(secondRail.viewAB.getRailLocationAt(50.0))
        secondTrain.commit()
        val depotId = DepotId("cross")
        val destinations = OperationalDestinationService(world)
        destinations.register(
            OperationalDestination(
                "cross@first",
                "Cross first",
                firstRail.id,
                100.0,
                ViewDirection.AB,
                DestinationRole.DepotStablingPointRole(
                    "cross",
                    "first",
                    usableLengthMeters = 20.0,
                    allowedTags = setOf("first")
                )
            )
        )
        destinations.register(
            OperationalDestination(
                "cross@second",
                "Cross second",
                secondRail.id,
                5.0,
                ViewDirection.AB,
                DestinationRole.DepotStablingPointRole(
                    "cross",
                    "second",
                    usableLengthMeters = 20.0,
                    allowedTags = setOf("second")
                )
            )
        )
        data.depots.registerRollingStock(
            RollingStockRecord(
                firstTrain.id,
                depotId,
                "test/first",
                setOf("first"),
                1,
                10.0,
                20.0,
                emptySet()
            ),
            StablingPointRef(depotId, "first")
        )
        data.depots.registerRollingStock(
            RollingStockRecord(
                secondTrain.id,
                depotId,
                "test/second",
                setOf("second"),
                1,
                10.0,
                20.0,
                emptySet()
            ),
            StablingPointRef(depotId, "second")
        )
        val beforeFirst = firstTrain.head.location
        val beforeSecond = secondTrain.head.location
        coordinator.stall(report(coordinator.worldId, firstTrain.id))

        val result = DepotResetCoordinator(
            world,
            TimetableDispatcher(
                UnavailableRouteAdmissionTestContext,
                emptyTrainRestrictionSnapshots,
                emptyInspectionRuntime(),
                acceptingTimetableAppliedPlanService()
            )
        ).reset(setOf(depotId))

        assertTrue(result is DepotResetResult.Failed)
        assertEquals("TRAIN_ENVELOPE", result.report.failureStage)
        assertEquals(beforeFirst, firstTrain.head.location)
        assertEquals(beforeSecond, secondTrain.head.location)
        assertFalse(data.depots.stablingPoints(depotId).any { it.occupantTrainId == null })
    }

    @Test
    fun `failed train preflight changes neither train nor depot ledger`() {
        val world = createTestWorld().also(worlds::add)
        val coordinator = AtoWorldCoordinator("reset-preflight", reportSink = {})
        TestBasicWorldDataRegistry.register(
            world,
            BasicWorldData(atoCoordinator = coordinator)
        )
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        val depotId = DepotId("north")
        OperationalDestinationService(world).register(
            OperationalDestination(
                "north@one",
                "North one",
                rail.id,
                40.0,
                ViewDirection.AB,
                DestinationRole.DepotStablingPointRole("north", "one", usableLengthMeters = 20.0)
            )
        )
        world.basicWorldData.depots.registerRollingStock(
            RollingStockRecord(
                "ghost",
                depotId,
                "test/ghost",
                setOf("metro"),
                1,
                10.0,
                20.0,
                emptySet()
            ),
            StablingPointRef(depotId, "one")
        )
        coordinator.stall(report(coordinator.worldId, "ghost"))
        val beforeStock = world.basicWorldData.depots.allRollingStock()
        val beforePoints = world.basicWorldData.depots.stablingPoints(depotId)

        val result = DepotResetCoordinator(
            world,
            TimetableDispatcher(
                UnavailableRouteAdmissionTestContext,
                emptyTrainRestrictionSnapshots,
                emptyInspectionRuntime(),
                acceptingTimetableAppliedPlanService()
            )
        ).reset(setOf(depotId))

        assertTrue(result is DepotResetResult.Failed)
        assertEquals("TRAIN_RESOLUTION", result.report.failureStage)
        assertEquals(beforeStock, world.basicWorldData.depots.allRollingStock())
        assertEquals(beforePoints, world.basicWorldData.depots.stablingPoints(depotId))
        assertTrue(coordinator.isStalled)
    }

    private fun <T> onMainThread(block: () -> T): T {
        ThreadGuard.installPlatformBridge(object : ThreadGuardPlatformBridge {
            override fun isMainThread(): Boolean = true
        })
        return try {
            block()
        } finally {
            ThreadGuard.clearPlatformBridge()
        }
    }

    private fun rail(world: CoreWorld, from: Vector3d, to: Vector3d): CoreRail =
        world.createRailForTest(straightCurve(from, to))

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

    private fun report(worldId: String, trainId: String): AtoStallReport = AtoStallReport(
        worldId = worldId,
        trainId = trainId,
        tick = 1L,
        errorType = "TestStall",
        message = "test",
        stackTrace = "test",
        initialTrain = null,
        currentTrain = null,
        route = null,
        perception = null,
        activeProviderId = null,
        constraints = emptyMap()
    )
}
