package work.art1st.mineRails.basic.timetable

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.mineRails.basic.WayPoint
import work.art1st.mineRails.basic.pathFinding.TestBasicWorldDataRegistry
import work.art1st.mineRails.basic.pathFinding.basicWorldData
import work.art1st.mineRails.core.api.physics.rail.ViewDirection
import work.art1st.mineRails.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.mineRails.core.physics.train.createStraightRailFromSegments
import work.art1st.mineRails.core.physics.train.createTestWorld

class TimetableValidatorTest {
    @Test
    fun `validator expands route between waypoint pool and goto target`() {
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
                    trainSelector = TrainSelector.WayPointPool(listOf("A")),
                    steps = listOf(TimetableStep.At(0, TimetableAction.GoTo("B")))
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
                    trainSelector = TrainSelector.WayPointPool(listOf("A")),
                    steps = listOf(
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
}
