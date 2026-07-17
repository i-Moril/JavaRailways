package work.art1st.mineRails.basic.api.depot

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.mineRails.basic.BasicWorldData
import work.art1st.mineRails.basic.WayPoint
import work.art1st.mineRails.basic.api.ato.AtoSafetyProfile
import work.art1st.mineRails.basic.api.ato.AtoStallReport
import work.art1st.mineRails.basic.api.ato.AtoWorldCoordinator
import work.art1st.mineRails.basic.api.ato.KinematicTrainActuator
import work.art1st.mineRails.basic.api.destination.DestinationRole
import work.art1st.mineRails.basic.api.destination.OperationalDestination
import work.art1st.mineRails.basic.api.destination.OperationalDestinationService
import work.art1st.mineRails.basic.controller.SimpleATO
import work.art1st.mineRails.basic.pathFinding.TestBasicWorldDataRegistry
import work.art1st.mineRails.basic.pathFinding.basicWorldData
import work.art1st.mineRails.basic.timetable.Timetable
import work.art1st.mineRails.basic.timetable.TimetableAction
import work.art1st.mineRails.basic.timetable.TimetableDispatcher
import work.art1st.mineRails.basic.timetable.TimetableService
import work.art1st.mineRails.basic.timetable.TimetableServiceRunId
import work.art1st.mineRails.basic.timetable.TimetableServiceStartResult
import work.art1st.mineRails.basic.timetable.TimetableStep
import work.art1st.mineRails.core.api.physics.rail.ViewDirection
import work.art1st.mineRails.core.physics.CoreWorld
import work.art1st.mineRails.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.mineRails.core.physics.train.createStraightRailFromSegments
import work.art1st.mineRails.core.physics.train.createTestWorld
import work.art1st.mineRails.core.physics.train.createTrainWithConfig
import work.art1st.mineRails.core.util.AsyncTickTestSupport

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
        val dispatcher = TimetableDispatcher { train, trainWorld ->
            SimpleATO(train, trainWorld, KinematicTrainActuator(10.0, profile), profile)
        }
        val coordinator = AtoWorldCoordinator("reset-world", reportSink = {})
        val data = BasicWorldData(dispatcher = dispatcher, atoCoordinator = coordinator)
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
                "north-one",
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
        dispatcher.tick(world)
        assertEquals(1, dispatcher.status(TimetableServiceRunId("tt", "s1"))?.confirmedSectionCount)
        coordinator.stall(report(coordinator.worldId, train.id))

        val result = DepotResetCoordinator(world).reset(setOf(depotId))

        assertTrue(result is DepotResetResult.Succeeded, result.report.toString())
        val success = result.report
        assertEquals(DepotResetStatus.SUCCEEDED, success.status)
        assertEquals(1, success.removedRunCount)
        assertEquals(1, success.releasedRouteSectionCount)
        assertNull(dispatcher.status(TimetableServiceRunId("tt", "s1")))
        assertNull(train.controller)
        assertEquals(originArc, train.head.location.arcLength, 1.0e-6)
        assertEquals(RollingStockState.AVAILABLE, data.depots.rollingStock(train.id)?.state)
        assertEquals(
            train.id,
            data.depots.stablingPoint(StablingPointRef(depotId, "one"))?.occupantTrainId
        )
        assertTrue(coordinator.isStalled)
        assertTrue(DepotResetReportJson.encode(success).contains("\"status\": \"SUCCEEDED\""))
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
                "north-safe",
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
                "north-risky",
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
                1.0,
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
                1.0,
                20.0,
                emptySet()
            ),
            StablingPointRef(depotId, "risky")
        )
        val beforeSafeHead = safeTrain.head.location.arcLengthIn(ViewDirection.AB)
        val beforeRiskyHead = riskyTrain.head.location.arcLengthIn(ViewDirection.AB)
        val beforeRiskyBogies = riskyTrain.cars.flatMap { car -> car.bogies }
            .map { it.location.arcLengthIn(ViewDirection.AB) }
        val beforeStock = data.depots.allRollingStock()
        val beforePoints = data.depots.stablingPoints(depotId)
        coordinator.stall(report(coordinator.worldId, riskyTrain.id))

        val result = DepotResetCoordinator(world).reset(setOf(depotId))
        riskyTrain.commit()

        assertTrue(result is DepotResetResult.Failed)
        assertEquals("TRAIN_PLACEMENT", result.report.failureStage)
        assertEquals(beforeSafeHead, safeTrain.head.location.arcLengthIn(ViewDirection.AB), 1.0e-9)
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
                "north-one",
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

        val result = DepotResetCoordinator(world).reset(setOf(depotId))

        assertTrue(result is DepotResetResult.Failed)
        assertEquals("TRAIN_RESOLUTION", result.report.failureStage)
        assertEquals(beforeStock, world.basicWorldData.depots.allRollingStock())
        assertEquals(beforePoints, world.basicWorldData.depots.stablingPoints(depotId))
        assertTrue(coordinator.isStalled)
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
