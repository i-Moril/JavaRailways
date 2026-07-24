package work.art1st.mineRails.core.physics.train

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import work.art1st.mineRails.core.physics.rail.curve.PrimeCurveGenerator

class TrainNameTest {
    @Test
    fun `train names are unique per world and lookup uses the player name`() {
        val world = createTestWorld()
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(120.0, segmentCount = 1)
        )
        val first = createTrainWithConfig("id-1", rail, world, listOf(8.0), isLoop = false)
        val second = createTrainWithConfig("id-2", rail, world, listOf(8.0), isLoop = false)

        val previousRevision=first.publishedFrame.revision
        world.renameTrain(first, "Local 1")
        assertEquals(first, world.getTrain("Local 1"))
        assertEquals("Local 1",first.publishedFrame.trainName)
        assertEquals(previousRevision+1,first.publishedFrame.revision)
        assertEquals("Local 1",world.drainPublishedTrainFrames().single().trainName)
        assertEquals(first, world.getTrain("id-1"))

        assertThrows(IllegalArgumentException::class.java) {
            world.renameTrain(second, "Local 1")
        }
    }
}
