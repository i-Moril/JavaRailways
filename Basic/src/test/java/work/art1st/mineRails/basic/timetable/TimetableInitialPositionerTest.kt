package work.art1st.mineRails.basic.timetable

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.mineRails.basic.BasicWorldData
import work.art1st.mineRails.basic.WayPoint
import work.art1st.mineRails.basic.api.ato.AtoWorldCoordinator
import work.art1st.mineRails.basic.api.destination.DestinationRole
import work.art1st.mineRails.basic.api.destination.OperationalDestination
import work.art1st.mineRails.basic.api.destination.OperationalDestinationService
import work.art1st.mineRails.basic.pathFinding.TestBasicWorldDataRegistry
import work.art1st.mineRails.basic.pathFinding.basicWorldData
import work.art1st.mineRails.core.api.control.TrainController
import work.art1st.mineRails.core.api.physics.rail.ViewDirection
import work.art1st.mineRails.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.mineRails.core.physics.train.createStraightRailFromSegments
import work.art1st.mineRails.core.physics.train.createTestWorld
import work.art1st.mineRails.core.physics.train.createTrainWithConfig

class TimetableInitialPositionerTest {
    @Test
    fun `matches player train name and relocates atomically using rail direction`() {
        val world = createTestWorld()
        val coordinator = AtoWorldCoordinator("initial-position", reportSink = {})
        TestBasicWorldDataRegistry.register(world, BasicWorldData(atoCoordinator = coordinator))
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(120.0, segmentCount = 1)
        )
        rail.metadata = rail.metadata.copy(allowedDirection = ViewDirection.AB)
        val train = createTrainWithConfig("uuid-1", rail, world, listOf(8.0), isLoop = false)
        world.renameTrain(train, "s100-1")
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
        TestBasicWorldDataRegistry.register(world, BasicWorldData(atoCoordinator = coordinator))
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(240.0, segmentCount = 1)
        )
        rail.metadata = rail.metadata.copy(allowedDirection = ViewDirection.AB)
        val first = createTrainWithConfig("uuid-1", rail, world, listOf(8.0), isLoop = false)
        val second = createTrainWithConfig("uuid-2", rail, world, listOf(8.0), isLoop = false)
        world.renameTrain(first, "s100-1")
        world.renameTrain(second, "s100-2")
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
        TestBasicWorldDataRegistry.register(world, BasicWorldData(atoCoordinator = coordinator))
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(120.0, segmentCount = 1)
        )
        rail.metadata = rail.metadata.copy(allowedDirection = ViewDirection.AB)
        val train = createTrainWithConfig("uuid-1", rail, world, listOf(8.0), isLoop = false)
        world.renameTrain(train, "s100-1")
        train.setController(object : TrainController(train, world) {})
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
    fun `missing waypoint and rail direction stalls without moving train`() {
        val world = createTestWorld()
        val coordinator = AtoWorldCoordinator("initial-position-failure", reportSink = {})
        TestBasicWorldDataRegistry.register(world, BasicWorldData(atoCoordinator = coordinator))
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(120.0, segmentCount = 1)
        )
        val train = createTrainWithConfig("uuid-1", rail, world, listOf(8.0), isLoop = false)
        world.renameTrain(train, "s100-1")
        val originalArc = train.headLocation.arcLengthIn(ViewDirection.AB)
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

        assertTrue(result is TimetableInitialPositionResult.Failure)
        assertEquals(originalArc, train.headLocation.arcLengthIn(ViewDirection.AB), 0.0)
        assertTrue(coordinator.isStalled)
        assertEquals("TimetableInitialPositionFailure", coordinator.lastStall?.errorType)
    }
}
