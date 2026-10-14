package work.art1st.javarailways.simpleato.depot

import work.art1st.javarailways.core.util.IsolatedSimulationThreadMarker

import work.art1st.javarailways.core.physics.train.onOwnerForTest

import work.art1st.javarailways.simpleato.simpleAtoWorldData

import work.art1st.javarailways.core.physics.createRailForTest

import org.joml.Vector3d
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.BasicWorldData
import work.art1st.javarailways.basic.WayPoint
import work.art1st.javarailways.simpleato.api.ato.AtoSafetyProfile
import work.art1st.javarailways.simpleato.api.ato.AtoStallReport
import work.art1st.javarailways.simpleato.api.ato.AtoWorldCoordinator
import work.art1st.javarailways.simpleato.testing.KinematicTrainActuator
import work.art1st.javarailways.simpleato.api.depot.*
import work.art1st.javarailways.simpleato.api.destination.DestinationRole
import work.art1st.javarailways.simpleato.api.destination.OperationalDestination
import work.art1st.javarailways.simpleato.api.destination.OperationalDestinationService
import work.art1st.javarailways.simpleato.controller.SimpleATO
import work.art1st.javarailways.simpleato.restriction.emptyInspectionRuntime
import work.art1st.javarailways.simpleato.restriction.emptyTrainRestrictionSnapshots
import work.art1st.javarailways.simpleato.testing.TestSimpleATOWorldDataRegistry
import work.art1st.javarailways.basic.basicWorldData
import work.art1st.javarailways.simpleato.timetable.Timetable
import work.art1st.javarailways.simpleato.timetable.TimetableAction
import work.art1st.javarailways.simpleato.timetable.TimetableDispatcher
import work.art1st.javarailways.simpleato.timetable.GrantingRouteAdmissionTestContext
import work.art1st.javarailways.simpleato.timetable.UnavailableRouteAdmissionTestContext
import work.art1st.javarailways.simpleato.timetable.TimetableAtoFactory
import work.art1st.javarailways.simpleato.timetable.TimetableService
import work.art1st.javarailways.simpleato.timetable.TimetableServiceRunId
import work.art1st.javarailways.simpleato.timetable.TimetableServiceStartResult
import work.art1st.javarailways.simpleato.timetable.acceptingInfrastructureDependencyService
import work.art1st.javarailways.simpleato.timetable.TimetableStep
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.control.TrainController
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.createTestWorld
import work.art1st.javarailways.core.physics.train.createTrainWithConfig
import work.art1st.javarailways.core.util.AsyncTickTestSupport
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinator
import java.time.Duration

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
        worlds.forEach { world ->
            TestSimpleATOWorldDataRegistry.unregister(world)
            world.physicsCoordinator.close()
            world.releaseRuntimeBinding()
            (world.tickManager as AutoCloseable).close()
        }
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
            infrastructureDependencyService = acceptingInfrastructureDependencyService(),
            atoFactory = TimetableAtoFactory { train, trainWorld ->
                SimpleATO(
                    train,
                    trainWorld,
                    KinematicTrainActuator(10.0, profile),
                    profile,
                    inspectionRuntime::maximumDeclaredLeadTimeSeconds
                )
            }
        )
        val coordinator = AtoWorldCoordinator("reset-world", reportSink = {})
        val data = BasicWorldData(safetyInterlock = coordinator.safetyInterlock)
        TestSimpleATOWorldDataRegistry.register(world, data, coordinator)
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
        world.simpleAtoWorldData.depots.registerRollingStock(
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
        world.simpleAtoWorldData.timetables.add(
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
            world.onOwnerForTest { dispatcher.startService(world, "tt", "s1") } is TimetableServiceStartResult.Started
        )
        val ato = train.controller as SimpleATO
        AsyncTickTestSupport.run { ato.tick(50.0) }
        ato.commit()
        assertFalse(dispatcher.status(TimetableServiceRunId("tt", "s1"))?.routeGrantActive == true)
        coordinator.stall(report(coordinator.worldId, train.id))
        world.drainPublishedTrainFrames()

        val result = world.onOwnerForTest { DepotResetCoordinator(world, dispatcher).reset(setOf(depotId)) }

        assertTrue(result is DepotResetResult.Succeeded, result.report.toString())
        val success = result.report
        assertEquals(DepotResetStatus.SUCCEEDED, success.status)
        assertEquals(1, success.removedRunCount)
        assertNull(dispatcher.status(TimetableServiceRunId("tt", "s1")))
        assertNull(train.controller)
        assertEquals(originArc, train.head.location.arcLength, 1.0e-6)
        assertEquals(RollingStockState.AVAILABLE, world.simpleAtoWorldData.depots.rollingStock(train.id)?.state)
        assertEquals(
            train.id,
            world.simpleAtoWorldData.depots.stablingPoint(StablingPointRef(depotId, "one"))?.occupantTrainId
        )
        assertTrue(coordinator.isStalled)
        assertSame(train.publishedFrame, world.latestWorldFrame().trains.single())
        assertSame(train.publishedFrame, world.drainPublishedTrainFrames().single())
        val encoded = DepotResetReportJson.encode(success)
        assertTrue(encoded.contains("\"schemaVersion\": 2"))
        assertTrue(encoded.contains("\"status\": \"SUCCEEDED\""))
        assertFalse(encoded.contains("releasedRouteSectionCount"))
    }

    @Test
    fun `failed placement restores every train including the train that threw mid-move`() {
        val world = createTestWorld(PhysicsCoordinator(1, automaticFallback = false)).also(worlds::add)
        val coordinator = AtoWorldCoordinator("reset-placement-rollback", reportSink = {})
        val data = BasicWorldData(safetyInterlock = coordinator.safetyInterlock)
        TestSimpleATOWorldDataRegistry.register(world, data, coordinator)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(140.0, segmentCount = 1)
        )
        val safeTrain = createTrainWithConfig("A-safe", rail, world, listOf(10.0))
        val riskyTrain = createTrainWithConfig("B-risky", rail, world, listOf(10.0))
        world.onOwnerForTest {
            riskyTrain.setLocation(safeTrain.head.location.forward(30.0)!!)
            world.publishOwnerChanges(listOf(riskyTrain))
        }
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
        world.simpleAtoWorldData.depots.registerRollingStock(
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
        world.simpleAtoWorldData.depots.registerRollingStock(
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
        world.onOwnerForTest { safeTrain.reverse() }
        world.onOwnerForTest { safeTrain.derail("test pre-reset state") }
        world.onOwnerForTest {
            riskyTrain.setController(object : TrainController(riskyTrain, world) {
                override fun tick(deltaT: Double) {
                    speed = 2.0
                    moveControlledTrain(0.1, 2.0)
                }
            })
        }
        world.physicsCoordinator.start()
        val ticket = world.physicsCoordinator.stepSimulation(50.0)
        assertTimeoutPreemptively(Duration.ofSeconds(5)) { ticket.await() }
        val beforeRiskyVelocity = riskyTrain.cars.map { Vector3d(it.motionState.tangentialVelocity) }
        val beforeRiskyAngularVelocity = riskyTrain.cars.map { Vector3d(it.motionState.angularVelocity) }
        val beforeRiskyBogieVelocity = riskyTrain.cars.flatMap { it.bogies }.map { Vector3d(it.velocity) }
        assertTrue(beforeRiskyVelocity.all { it.length() > 0.0 })
        val beforeSafeHead = safeTrain.head.location.arcLengthIn(ViewDirection.AB)
        val beforeRiskyHead = riskyTrain.head.location.arcLengthIn(ViewDirection.AB)
        val beforeRiskyBogies = riskyTrain.cars.flatMap { car -> car.bogies }
            .map { it.location.arcLengthIn(ViewDirection.AB) }
        val beforeStock = world.simpleAtoWorldData.depots.allRollingStock()
        val beforePoints = world.simpleAtoWorldData.depots.stablingPoints(depotId)
        val beforeSafeFrame = safeTrain.publishedFrame
        val beforeRiskyFrame = riskyTrain.publishedFrame
        val beforeWorldFrame = world.latestWorldFrame()
        world.drainPublishedTrainFrames()
        coordinator.stall(report(coordinator.worldId, riskyTrain.id))

        val result = world.onOwnerForTest {
            DepotResetCoordinator(world, TimetableDispatcher(
                UnavailableRouteAdmissionTestContext,
                emptyTrainRestrictionSnapshots,
                emptyInspectionRuntime(),
                acceptingInfrastructureDependencyService()
            ))
                .reset(setOf(depotId))
        }
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
        assertEquals(beforeStock, world.simpleAtoWorldData.depots.allRollingStock())
        assertEquals(beforePoints, world.simpleAtoWorldData.depots.stablingPoints(depotId))
        assertEquals(beforeRiskyVelocity, riskyTrain.cars.map { it.motionState.tangentialVelocity })
        assertEquals(beforeRiskyAngularVelocity, riskyTrain.cars.map { it.motionState.angularVelocity })
        assertEquals(beforeRiskyBogieVelocity, riskyTrain.cars.flatMap { it.bogies }.map { it.velocity })
        assertSame(beforeSafeFrame, safeTrain.publishedFrame)
        assertSame(beforeRiskyFrame, riskyTrain.publishedFrame)
        assertSame(beforeWorldFrame, world.latestWorldFrame())
        assertTrue(world.drainPublishedTrainFrames().isEmpty())
    }

    @Test
    fun `reset rejects actual train envelopes that overlap across connected rails`() {
        val world = createTestWorld().also(worlds::add)
        val coordinator = AtoWorldCoordinator("reset-cross-rail-envelope", reportSink = {})
        val data = BasicWorldData(safetyInterlock = coordinator.safetyInterlock)
        TestSimpleATOWorldDataRegistry.register(world, data, coordinator)
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
        IsolatedSimulationThreadMarker.run { CoreRail.connect(firstRail, secondRail) }
        val firstTrain = createTrainWithConfig("A-cross", firstRail, world, listOf(10.0))
        val secondTrain = createTrainWithConfig("B-cross", secondRail, world, listOf(10.0))
        world.onOwnerForTest {
            secondTrain.setLocation(secondRail.viewAB.getRailLocationAt(50.0))
            world.publishOwnerChanges(listOf(secondTrain))
        }
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
        world.simpleAtoWorldData.depots.registerRollingStock(
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
        world.simpleAtoWorldData.depots.registerRollingStock(
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
        val beforeFirstFrame = firstTrain.publishedFrame
        val beforeSecondFrame = secondTrain.publishedFrame
        val beforeWorldFrame = world.latestWorldFrame()
        world.drainPublishedTrainFrames()
        coordinator.stall(report(coordinator.worldId, firstTrain.id))

        val result = world.onOwnerForTest { DepotResetCoordinator(
            world,
            TimetableDispatcher(
                UnavailableRouteAdmissionTestContext,
                emptyTrainRestrictionSnapshots,
                emptyInspectionRuntime(),
                acceptingInfrastructureDependencyService()
            )
        ).reset(setOf(depotId)) }

        assertTrue(result is DepotResetResult.Failed)
        assertEquals("TRAIN_ENVELOPE", result.report.failureStage)
        assertEquals(beforeFirst, firstTrain.head.location)
        assertEquals(beforeSecond, secondTrain.head.location)
        assertSame(beforeFirstFrame, firstTrain.publishedFrame)
        assertSame(beforeSecondFrame, secondTrain.publishedFrame)
        assertSame(beforeWorldFrame, world.latestWorldFrame())
        assertTrue(world.drainPublishedTrainFrames().isEmpty())
        assertFalse(world.simpleAtoWorldData.depots.stablingPoints(depotId).any { it.occupantTrainId == null })
    }

    @Test
    fun `reset rejects placement into a train outside the reset plan without publication`() {
        val world = createTestWorld().also(worlds::add)
        val coordinator = AtoWorldCoordinator("reset-outsider", reportSink = {})
        TestSimpleATOWorldDataRegistry.register(
            world, BasicWorldData(safetyInterlock = coordinator.safetyInterlock), coordinator
        )
        val rail = createStraightRailFromSegments(
            world, PrimeCurveGenerator.buildStraight(180.0, segmentCount = 1)
        )
        val resetTrain = createTrainWithConfig("reset", rail, world, listOf(10.0))
        val outsider = createTrainWithConfig("outsider", rail, world, listOf(10.0))
        world.onOwnerForTest {
            outsider.setLocation(rail.viewAB.getRailLocationAt(100.0))
            world.publishOwnerChanges(listOf(outsider))
        }
        val depotId = DepotId("north")
        OperationalDestinationService(world).register(
            OperationalDestination(
                "north@one", "One", rail.id, 100.0, ViewDirection.AB,
                DestinationRole.DepotStablingPointRole("north", "one", usableLengthMeters = 20.0)
            )
        )
        world.simpleAtoWorldData.depots.registerRollingStock(
            RollingStockRecord(resetTrain.id, depotId, "test/train", emptySet(), 1, 10.0, 20.0, emptySet()),
            StablingPointRef(depotId, "one")
        )
        val beforeReset = resetTrain.publishedFrame
        val beforeOutsider = outsider.publishedFrame
        val beforeWorld = world.latestWorldFrame()
        val beforeStock = world.simpleAtoWorldData.depots.allRollingStock()
        val beforePoints = world.simpleAtoWorldData.depots.stablingPoints(depotId)
        world.drainPublishedTrainFrames()
        coordinator.stall(report(coordinator.worldId, resetTrain.id))

        val result = world.onOwnerForTest {
            DepotResetCoordinator(world, TimetableDispatcher(
                UnavailableRouteAdmissionTestContext,
                emptyTrainRestrictionSnapshots,
                emptyInspectionRuntime(),
                acceptingInfrastructureDependencyService()
            )).reset(setOf(depotId))
        }

        assertTrue(result is DepotResetResult.Failed)
        assertEquals("TRAIN_ENVELOPE", result.report.failureStage)
        assertSame(beforeReset, resetTrain.publishedFrame)
        assertSame(beforeOutsider, outsider.publishedFrame)
        assertSame(beforeWorld, world.latestWorldFrame())
        assertTrue(world.drainPublishedTrainFrames().isEmpty())
        assertEquals(beforeStock, world.simpleAtoWorldData.depots.allRollingStock())
        assertEquals(beforePoints, world.simpleAtoWorldData.depots.stablingPoints(depotId))
        assertTrue(coordinator.isStalled)
    }

    @Test
    fun `failed train preflight changes neither train nor depot ledger`() {
        val world = createTestWorld().also(worlds::add)
        val coordinator = AtoWorldCoordinator("reset-preflight", reportSink = {})
        TestSimpleATOWorldDataRegistry.register(
            world,
            BasicWorldData(safetyInterlock = coordinator.safetyInterlock),
            coordinator
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
        world.simpleAtoWorldData.depots.registerRollingStock(
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
        val beforeStock = world.simpleAtoWorldData.depots.allRollingStock()
        val beforePoints = world.simpleAtoWorldData.depots.stablingPoints(depotId)

        val result = world.onOwnerForTest { DepotResetCoordinator(
            world,
            TimetableDispatcher(
                UnavailableRouteAdmissionTestContext,
                emptyTrainRestrictionSnapshots,
                emptyInspectionRuntime(),
                acceptingInfrastructureDependencyService()
            )
        ).reset(setOf(depotId)) }

        assertTrue(result is DepotResetResult.Failed)
        assertEquals("TRAIN_RESOLUTION", result.report.failureStage)
        assertEquals(beforeStock, world.simpleAtoWorldData.depots.allRollingStock())
        assertEquals(beforePoints, world.simpleAtoWorldData.depots.stablingPoints(depotId))
        assertTrue(coordinator.isStalled)
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
        constraints = emptyMap()
    )
}
