package work.art1st.javarailways.simpleato.timetable

import work.art1st.javarailways.core.physics.train.setControllerForTest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.BasicWorldData
import work.art1st.javarailways.basic.WayPoint
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
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.createTestWorld
import work.art1st.javarailways.core.physics.train.createTrainWithConfig
import work.art1st.javarailways.core.util.IsolatedSimulationThreadMarker

class TimetableInitialPositionerTest {
    @Test
    fun `matches player train name and relocates atomically using rail direction`() {
        val world = createTestWorld()
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
        world.basicWorldData.wayPoints.add(WayPoint("yard@1", "Yard 1", rail.id, 70.0))
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

        val result = TimetableInitialPositioner(world).apply(timetable, tick = 0L)

        assertEquals(TimetableInitialPositionResult.Applied(1), result)
        assertEquals(70.0, train.headLocation.arcLengthIn(ViewDirection.AB), 0.01)
        assertEquals(ViewDirection.AB, train.head.location.facing)
        assertFalse(coordinator.isStalled)
    }

    @Test
    fun `depot id selector expands to distinct physical stabling points`() {
        val world = createTestWorld()
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
                "MSB $pointId",
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

        val result = TimetableInitialPositioner(world).apply(timetable, tick = 0L)

        assertEquals(TimetableInitialPositionResult.Applied(2), result)
        assertEquals(setOf(80.0, 180.0), setOf(
            first.headLocation.arcLengthIn(ViewDirection.AB),
            second.headLocation.arcLengthIn(ViewDirection.AB)
        ))
        assertFalse(coordinator.isStalled)
    }

    @Test
    fun `controlled exact-match train reports an explicit reset error`() {
        val world = createTestWorld()
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
        train.setControllerForTest(object : TrainController(train, world) {})
        world.basicWorldData.wayPoints.add(WayPoint("yard@1", "Yard", rail.id, 70.0))
        val timetable = Timetable(
            id = "tt",
            initialPositions = listOf(TimetableInitialPosition(
                TimetableObjectSelector.Regex("s100-1"),
                TimetableObjectSelector.Regex("yard@1")
            ))
        )

        val result = TimetableInitialPositioner(world).apply(timetable, tick = 0L)

        assertTrue(result is TimetableInitialPositionResult.Failure)
        val reason = (result as TimetableInitialPositionResult.Failure).reason
        assertTrue(reason.contains("cannot move controlled train(s): s100-1"), reason)
        assertTrue(coordinator.isStalled)
        assertEquals("TimetableInitialPositionFailure", coordinator.lastStall?.errorType)
    }

    @Test
    fun `missing operating direction permits both and defaults initial placement to AB`() {
        val world = createTestWorld()
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
        world.basicWorldData.wayPoints.add(WayPoint("undirected", "Undirected", rail.id, 70.0))
        val timetable = Timetable(
            id = "tt",
            initialPositions = listOf(
                TimetableInitialPosition(
                    TimetableObjectSelector.Regex("s100-1"),
                    TimetableObjectSelector.Regex("undirected")
                )
            )
        )

        val result = TimetableInitialPositioner(world).apply(timetable, tick = 3L)

        assertEquals(TimetableInitialPositionResult.Applied(1), result)
        assertEquals(70.0, train.headLocation.arcLengthIn(ViewDirection.AB), 0.01)
        assertFalse(coordinator.isStalled)
    }
}
