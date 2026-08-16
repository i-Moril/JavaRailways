package work.art1st.javarailways.basic.timetable

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.BasicWorldData
import work.art1st.javarailways.basic.WayPoint
import work.art1st.javarailways.basic.api.ato.AtoSafetyProfile
import work.art1st.javarailways.basic.api.ato.AtoWorldCoordinator
import work.art1st.javarailways.basic.api.ato.KinematicTrainActuator
import work.art1st.javarailways.basic.api.depot.DepotId
import work.art1st.javarailways.basic.api.depot.DepotTrainSelector
import work.art1st.javarailways.basic.api.depot.RollingStockRecord
import work.art1st.javarailways.basic.api.depot.StablingPointRef
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
import work.art1st.javarailways.basic.controller.SimpleATO
import work.art1st.javarailways.basic.pathFinding.TestBasicWorldDataRegistry
import work.art1st.javarailways.basic.pathFinding.basicWorldData
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.createTestWorld
import work.art1st.javarailways.core.physics.train.createTrainWithConfig

class TimetableValidatorTest {
    @Test
    fun `validator rejects unknown infrastructure lines and missing line maximum speeds`() {
        val world = createTestWorld()
        TestBasicWorldDataRegistry.getOrCreate(world)
        val known = LineId("known")
        world.basicWorldData.lines.put(
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
        val world = createTestWorld()
        TestBasicWorldDataRegistry.getOrCreate(world)
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0))
        world.basicWorldData.wayPoints.add(WayPoint("A", "A", rail.id, 10.0))
        world.basicWorldData.wayPoints.add(WayPoint("B", "B", rail.id, 70.0))

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
        val world = createTestWorld()
        TestBasicWorldDataRegistry.getOrCreate(world)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        world.basicWorldData.wayPoints.add(WayPoint("A", "A", rail.id, 10.0))
        world.basicWorldData.wayPoints.add(WayPoint("B", "B", rail.id, 70.0))
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
        assertTrue(result.errors.any { it.contains("首次 destination 前不得 stop") })
    }

    @Test
    fun `dynamic selector validates only compatible depot origins`() {
        val world = createTestWorld()
        val data = BasicWorldData()
        TestBasicWorldDataRegistry.register(world, data)
        val metroRail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(120.0, segmentCount = 1)
        )
        val freightRail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(120.0, segmentCount = 1)
        )
        val metroTrain = createTrainWithConfig("metro", metroRail, world, listOf(10.0))
        val freightTrain = createTrainWithConfig("freight", freightRail, world, listOf(10.0))
        val depotId = DepotId("north")
        val destinations = OperationalDestinationService(world)
        destinations.register(
            OperationalDestination(
                "north@metro",
                "North metro",
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
                "North freight",
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
        data.wayPoints.add(WayPoint("B", "B", metroRail.id, 90.0))
        data.depots.registerRollingStock(
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
        data.depots.registerRollingStock(
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
        val world = createTestWorld()
        TestBasicWorldDataRegistry.getOrCreate(world)
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0))
        world.basicWorldData.wayPoints.add(WayPoint("A", "A", rail.id, 10.0))
        world.basicWorldData.wayPoints.add(WayPoint("B", "B", rail.id, 70.0))
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
        val world = createTestWorld()
        TestBasicWorldDataRegistry.getOrCreate(world)
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
        val world = createTestWorld()
        val profile = AtoSafetyProfile(
            accelerationMetersPerSecondSquared = 1.0,
            serviceDecelerationMetersPerSecondSquared = 1.0,
            reactionTimeSeconds = 0.0,
            safetyDistanceMeters = 1.0,
            stopToleranceMeters = 0.25
        )
        val dispatcher = TimetableDispatcher { train, owningWorld ->
            SimpleATO(train, owningWorld, KinematicTrainActuator(8.0, profile), profile)
        }
        val coordinator = AtoWorldCoordinator("validator-station", reportSink = {})
        val data = BasicWorldData(dispatcher = dispatcher, atoCoordinator = coordinator)
        TestBasicWorldDataRegistry.register(world, data)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(120.0, segmentCount = 1)
        )
        val train = createTrainWithConfig("T1", rail, world, listOf(10.0))
        val originArc = train.head.location.arcLength
        data.wayPoints.add(WayPoint("A", "A", rail.id, originArc))
        data.wayPoints.add(WayPoint("B", "B", rail.id, originArc + 80.0))
        val stationId = StationId("central")
        val berthRef = StationBerthRef(stationId, "wrong-way")
        data.stations.register(
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
        data.timetables.add(timetable)

        val validation = TimetableValidator(world).validate(timetable)
        val start = dispatcher.startService(world, timetable.id, "service")

        assertFalse(validation.isSuccess)
        assertTrue(validation.errors.any { it.contains("车站 destination", ignoreCase = true) })
        assertTrue(start is TimetableServiceStartResult.Failure)
        assertFalse(coordinator.isStalled)
        assertEquals(null, dispatcher.status(TimetableServiceRunId(timetable.id, "service")))
        assertEquals(null, train.controller)
    }
}
