package work.art1st.javarailways.simpleato.timetable

import org.junit.jupiter.api.function.Executable
import org.junit.jupiter.api.Assertions.assertAll
import work.art1st.javarailways.core.physics.train.setControllerForTest
import work.art1st.javarailways.core.physics.train.onOwnerForTest

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.BasicWorldData
import work.art1st.javarailways.basic.api.route.WayPoint
import work.art1st.javarailways.simpleato.api.ato.AtoWorldCoordinator
import work.art1st.javarailways.simpleato.api.destination.DestinationRole
import work.art1st.javarailways.simpleato.api.destination.OperationalDestination
import work.art1st.javarailways.simpleato.api.destination.OperationalDestinationService
import work.art1st.javarailways.simpleato.testing.TestSimpleATOWorldDataRegistry
import work.art1st.javarailways.simpleato.simpleAtoWorldData
import work.art1st.javarailways.basic.basicWorldData
import work.art1st.javarailways.core.api.control.TrainController
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.createTestWorld
import work.art1st.javarailways.core.physics.train.createTrainWithConfig
import work.art1st.javarailways.core.util.IsolatedSimulationThreadMarker

class TimetableInitialPositionerTest {
    private val worlds = mutableListOf<CoreWorld>()

    @AfterEach
    fun closeWorlds() {
        val owned = worlds.toList()
        worlds.clear()
        assertAll(owned.flatMap { world ->
            listOf(
                Executable { TestSimpleATOWorldDataRegistry.unregister(world) },
                Executable { world.physicsCoordinator.close() },
                Executable { (world.tickManager as? AutoCloseable)?.close() }
            )
        })
    }

    @Test
    fun `matches player train name and relocates atomically using rail direction`() {
        val world = createTestWorld().also(worlds::add)
        val coordinator = AtoWorldCoordinator("initial-position", reportSink = {})
        TestSimpleATOWorldDataRegistry.register(
            world,
            BasicWorldData(safetyInterlock = coordinator.safetyInterlock),
            coordinator
        )
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(120.0, segmentCount = 1)
        )
        world.basicWorldData.operatingDirections.put(rail.id, ViewDirection.AB)
        val train = createTrainWithConfig("uuid-1", rail, world, listOf(8.0), isLoop = false)
        IsolatedSimulationThreadMarker.run {
            world.physicsCoordinator.processBetweenFrames { world.renameTrain(train, "s100-1") }
        }
        world.basicWorldData.wayPoints.add(WayPoint("yard@1", rail.id, 70.0))
        val timetable = Timetable(
            id = "tt",
            initialPositions = listOf(
                TimetableInitialPosition(
                    TimetableObjectSelector.And(listOf(
                        TimetableObjectSelector.Regex("s100-.*"),
                        TimetableObjectSelector.Not(TimetableObjectSelector.Regex(".*-maintenance"))
                    )),
                    TimetableObjectSelector.Regex("yard@1")
                )
            )
        )

        val result = world.onOwnerForTest { TimetableInitialPositioner(world).apply(timetable, tick = 0L) }

        assertEquals(TimetableInitialPositionResult.Applied(1), result)
        assertEquals(70.0, train.headLocation.arcLengthIn(ViewDirection.AB), 0.01)
        assertEquals(ViewDirection.AB, train.head.location.facing)
        assertFalse(coordinator.isStalled)
    }

    @Test
    fun `depot id selector expands to distinct physical stabling points`() {
        val world = createTestWorld().also(worlds::add)
        val coordinator = AtoWorldCoordinator("initial-depot", reportSink = {})
        TestSimpleATOWorldDataRegistry.register(
            world,
            BasicWorldData(safetyInterlock = coordinator.safetyInterlock),
            coordinator
        )
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(240.0, segmentCount = 1)
        )
        world.basicWorldData.operatingDirections.put(rail.id, ViewDirection.AB)
        val first = createTrainWithConfig("uuid-1", rail, world, listOf(8.0), isLoop = false)
        val second = createTrainWithConfig("uuid-2", rail, world, listOf(8.0), isLoop = false)
        IsolatedSimulationThreadMarker.run {
            world.physicsCoordinator.processBetweenFrames {
                world.renameTrain(first, "s100-1")
                world.renameTrain(second, "s100-2")
            }
        }
        val destinations = OperationalDestinationService(world)
        listOf(80.0 to "1", 180.0 to "2").forEach { (arc, pointId) ->
            destinations.register(OperationalDestination(
                "msb@$pointId",
                rail.id,
                arc,
                ViewDirection.AB,
                DestinationRole.DepotStablingPointRole("msb", pointId, usableLengthMeters = 20.0)
            ))
        }
        val depotSelector = TimetableObjectSelector.Regex("msb")
        val timetable = Timetable(
            id = "tt",
            initialPositions = listOf(
                TimetableInitialPosition(TimetableObjectSelector.Regex("s100-1"), depotSelector),
                TimetableInitialPosition(TimetableObjectSelector.Regex("s100-2"), depotSelector)
            )
        )

        world.drainPublishedTrainFrames()
        val result = world.onOwnerForTest { TimetableInitialPositioner(world).apply(timetable, tick = 0L) }

        assertEquals(TimetableInitialPositionResult.Applied(2), result)
        assertEquals(setOf(80.0, 180.0), setOf(
            first.headLocation.arcLengthIn(ViewDirection.AB),
            second.headLocation.arcLengthIn(ViewDirection.AB)
        ))
        assertFalse(coordinator.isStalled)
        val frame = world.latestWorldFrame()
        assertEquals(listOf(first.publishedFrame, second.publishedFrame), frame.trains)
        assertEquals(frame.physicsEpoch, first.publishedFrame.physicsEpoch)
        assertEquals(frame.physicsEpoch, second.publishedFrame.physicsEpoch)
        assertEquals(setOf(first.publishedFrame, second.publishedFrame), world.drainPublishedTrainFrames().toSet())
    }

    @Test
    fun `controlled exact-match train reports an explicit reset error`() {
        val world = createTestWorld().also(worlds::add)
        val coordinator = AtoWorldCoordinator("initial-controlled", reportSink = {})
        TestSimpleATOWorldDataRegistry.register(
            world,
            BasicWorldData(safetyInterlock = coordinator.safetyInterlock),
            coordinator
        )
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(120.0, segmentCount = 1)
        )
        world.basicWorldData.operatingDirections.put(rail.id, ViewDirection.AB)
        val train = createTrainWithConfig("uuid-1", rail, world, listOf(8.0), isLoop = false)
        IsolatedSimulationThreadMarker.run {
            world.physicsCoordinator.processBetweenFrames { world.renameTrain(train, "s100-1") }
        }
        train.setControllerForTest(object : TrainController(train) {})
        world.basicWorldData.wayPoints.add(WayPoint("yard@1", rail.id, 70.0))
        val timetable = Timetable(
            id = "tt",
            initialPositions = listOf(TimetableInitialPosition(
                TimetableObjectSelector.Regex("s100-1"),
                TimetableObjectSelector.Regex("yard@1")
            ))
        )

        val result = world.onOwnerForTest { TimetableInitialPositioner(world).apply(timetable, tick = 0L) }

        assertTrue(result is TimetableInitialPositionResult.Failure)
        val reason = (result as TimetableInitialPositionResult.Failure).reason
        assertTrue(reason.contains("cannot move controlled train(s): s100-1"), reason)
        assertTrue(coordinator.isStalled)
        assertEquals("TimetableInitialPositionFailure", coordinator.lastStall?.errorType)
    }

    @Test
    fun `missing operating direction permits both and defaults initial placement to AB`() {
        val world = createTestWorld().also(worlds::add)
        val coordinator = AtoWorldCoordinator("initial-position-failure", reportSink = {})
        TestSimpleATOWorldDataRegistry.register(
            world,
            BasicWorldData(safetyInterlock = coordinator.safetyInterlock),
            coordinator
        )
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(120.0, segmentCount = 1)
        )
        val train = createTrainWithConfig("uuid-1", rail, world, listOf(8.0), isLoop = false)
        IsolatedSimulationThreadMarker.run {
            world.physicsCoordinator.processBetweenFrames { world.renameTrain(train, "s100-1") }
        }
        world.basicWorldData.wayPoints.add(WayPoint("undirected", rail.id, 70.0))
        val timetable = Timetable(
            id = "tt",
            initialPositions = listOf(
                TimetableInitialPosition(
                    TimetableObjectSelector.Regex("s100-1"),
                    TimetableObjectSelector.Regex("undirected")
                )
            )
        )

        val result = world.onOwnerForTest { TimetableInitialPositioner(world).apply(timetable, tick = 3L) }

        assertEquals(TimetableInitialPositionResult.Applied(1), result)
        assertEquals(70.0, train.headLocation.arcLengthIn(ViewDirection.AB), 0.01)
        assertFalse(coordinator.isStalled)
    }

    @Test
    fun `overlapping staged placements retain original train world and mailbox frames`() {
        val world = createTestWorld().also(worlds::add)
        val coordinator = AtoWorldCoordinator("initial-overlap", reportSink = {})
        TestSimpleATOWorldDataRegistry.register(
            world, BasicWorldData(safetyInterlock = coordinator.safetyInterlock), coordinator
        )
        val rail = createStraightRailFromSegments(
            world, PrimeCurveGenerator.buildStraight(240.0, segmentCount = 1)
        )
        val first = createTrainWithConfig("first", rail, world, listOf(8.0), isLoop = false)
        val second = createTrainWithConfig("second", rail, world, listOf(8.0), isLoop = false)
        world.onOwnerForTest {
            second.setLocation(rail.viewAB.getRailLocationAt(160.0))
            world.publishOwnerChanges(listOf(second))
        }
        world.basicWorldData.wayPoints.add(WayPoint("one", rail.id, 80.0))
        world.basicWorldData.wayPoints.add(WayPoint("two", rail.id, 84.0))
        val timetable = Timetable(
            "overlap", initialPositions = listOf(
                TimetableInitialPosition(TimetableObjectSelector.Regex("first"), TimetableObjectSelector.Regex("one")),
                TimetableInitialPosition(TimetableObjectSelector.Regex("second"), TimetableObjectSelector.Regex("two"))
            )
        )
        val beforeFirst = first.publishedFrame
        val beforeSecond = second.publishedFrame
        val beforeWorld = world.latestWorldFrame()
        world.drainPublishedTrainFrames()

        val result = world.onOwnerForTest { TimetableInitialPositioner(world).apply(timetable, 0L) }

        assertTrue(result is TimetableInitialPositionResult.Failure)
        assertSame(beforeFirst, first.publishedFrame)
        assertSame(beforeSecond, second.publishedFrame)
        assertSame(beforeWorld, world.latestWorldFrame())
        assertTrue(world.drainPublishedTrainFrames().isEmpty())
        assertTrue(coordinator.isStalled)
    }

    @Test
    fun `batch placement rejects train reuse across timetable definitions before publication`() {
        val world = createTestWorld().also(worlds::add)
        val coordinator = AtoWorldCoordinator("initial-batch", reportSink = {})
        TestSimpleATOWorldDataRegistry.register(
            world, BasicWorldData(safetyInterlock = coordinator.safetyInterlock), coordinator
        )
        val rail = createStraightRailFromSegments(
            world, PrimeCurveGenerator.buildStraight(180.0, segmentCount = 1)
        )
        val train = createTrainWithConfig("train", rail, world, listOf(8.0), isLoop = false)
        world.basicWorldData.wayPoints.add(WayPoint("one", rail.id, 60.0))
        world.basicWorldData.wayPoints.add(WayPoint("two", rail.id, 120.0))
        val before = train.headLocation.arcLengthIn(ViewDirection.AB)
        val selector = TimetableObjectSelector.Regex("train")

        val result = world.onOwnerForTest {
            TimetableInitialPositioner(world).apply(listOf(
                Timetable("a", initialPositions = listOf(TimetableInitialPosition(
                    selector, TimetableObjectSelector.Regex("one")
                ))),
                Timetable("b", initialPositions = listOf(TimetableInitialPosition(
                    selector, TimetableObjectSelector.Regex("two")
                )))
            ), 0L)
        }

        assertTrue(result is TimetableInitialPositionResult.Failure)
        assertEquals(before, train.headLocation.arcLengthIn(ViewDirection.AB), 0.01)
        assertTrue(coordinator.isStalled)
    }
}
