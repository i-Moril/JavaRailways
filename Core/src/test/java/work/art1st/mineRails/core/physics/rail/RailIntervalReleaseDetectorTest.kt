package work.art1st.mineRails.core.physics.rail

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import work.art1st.mineRails.core.api.physics.rail.IntervalOccupancyState
import work.art1st.mineRails.core.api.physics.rail.RailInterval
import work.art1st.mineRails.core.api.physics.rail.RailIntervalReleaseDetector
import java.util.UUID

class RailIntervalReleaseDetectorTest {
    private val railId = UUID.randomUUID()
    private val resource = RailInterval(railId, 50.0, 50.0)

    @Test
    fun `reservation is not released before the train first occupies it`() {
        val detector = RailIntervalReleaseDetector(listOf(resource))

        assertEquals(
            IntervalOccupancyState.BEFORE_OCCUPANCY,
            detector.update(listOf(RailInterval(railId, 0.0, 20.0)))
        )
    }

    @Test
    fun `resource releases after complete interval separation`() {
        val detector = RailIntervalReleaseDetector(listOf(resource))

        assertEquals(
            IntervalOccupancyState.OCCUPIED,
            detector.update(listOf(RailInterval(railId, 40.0, 60.0)))
        )
        assertEquals(
            IntervalOccupancyState.RELEASED,
            detector.update(listOf(RailInterval(railId, 60.0, 80.0)))
        )
    }

    @Test
    fun `reversing head and tail does not change occupancy`() {
        val detector = RailIntervalReleaseDetector(listOf(resource))
        val samePhysicalTrainAfterReverse = RailInterval(railId, 40.0, 60.0)

        assertEquals(IntervalOccupancyState.OCCUPIED, detector.update(listOf(samePhysicalTrainAfterReverse)))
        assertEquals(IntervalOccupancyState.OCCUPIED, detector.update(listOf(samePhysicalTrainAfterReverse)))
        assertEquals(IntervalOccupancyState.RELEASED, detector.update(listOf(RailInterval(railId, 10.0, 30.0))))
    }
}
