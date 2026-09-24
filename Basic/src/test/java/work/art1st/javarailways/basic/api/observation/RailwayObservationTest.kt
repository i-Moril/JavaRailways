package work.art1st.javarailways.basic.api.observation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.RailwayWorldFrameSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.createTestWorld
import work.art1st.javarailways.core.physics.train.createTrainWithConfig

class RailwayObservationTest {
    @Test
    fun `projection preserves BA facing and canonical AB arcs without render data`() {
        val world = createTestWorld()
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        val train = createTrainWithConfig("ba-observation", rail, world, listOf(10.0))
        train.setLocation(rail.viewBA.getRailLocationAt(60.0))
        train.commit()
        val coreFrame = RailwayWorldFrameSnapshot.create(
            physicsEpoch = train.publishedFrame.physicsEpoch,
            topologyRevision = world.topologyRevision,
            trains = listOf(train.publishedFrame)
        )

        val observation = RailwayObservation.fromCore("world-id", coreFrame)
        val projected = observation.trains.single()

        assertEquals(ViewDirection.BA, projected.head.facing)
        assertEquals(ViewDirection.BA, projected.tail.facing)
        assertEquals(train.publishedFrame.head.arcLengthInAbMeters, projected.head.arcLengthInAbMeters)
        assertEquals(train.publishedFrame.tail.arcLengthInAbMeters, projected.tail.arcLengthInAbMeters)
        assertEquals(train.publishedFrame.revision, projected.frameRevision)
        assertEquals(train.publishedFrame.occupiedIntervals, projected.occupiedIntervals)
        assertEquals(train.publishedFrame.sweptSpans, projected.sweptSpans)
        assertEquals(train.publishedFrame.headTraversal, projected.headTraversal)
        assertEquals(train.publishedFrame.tailTraversal, projected.tailTraversal)
        assertSame(coreFrame.occupancy, observation.occupancy)
    }

    @Test
    fun `projection preserves ordered per-epoch train-end traversal`() {
        val world = createTestWorld()
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(60.0, segmentCount = 4)
        )
        val train = createTrainWithConfig("traversal-observation", rail, world, listOf(10.0))
        train.moveTrainForward(30.0)
        train.commit()
        val coreFrame = RailwayWorldFrameSnapshot.create(
            physicsEpoch = train.publishedFrame.physicsEpoch,
            topologyRevision = world.topologyRevision,
            trains = listOf(train.publishedFrame)
        )

        val projected = RailwayObservation.fromCore("world-id", coreFrame).trains.single()

        assertEquals(3, projected.headTraversal.size)
        assertEquals(3, projected.tailTraversal.size)
        assertEquals(train.publishedFrame.headTraversal, projected.headTraversal)
        assertEquals(train.publishedFrame.tailTraversal, projected.tailTraversal)
    }

    @Test
    fun `observation defensively copies and sorts train collection`() {
        val world = createTestWorld()
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0))
        val second = createTrainWithConfig("z-train", rail, world, listOf(10.0)).publishedFrame
        val first = createTrainWithConfig("a-train", rail, world, listOf(10.0)).publishedFrame
        val source = mutableListOf(second, first)

        val observation = RailwayObservation.fromCore(
            "world-id",
            RailwayWorldFrameSnapshot.create(0L, world.topologyRevision, source)
        )
        source.clear()

        assertEquals(listOf(first.trainId, second.trainId).sorted(), observation.trains.map { it.trainId })
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (observation.trains as MutableList<RailwayTrainObservation>).clear()
        }
    }

    @Test
    fun `core world frame rejects mixed train epochs`() {
        val world = createTestWorld()
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0))
        val train = createTrainWithConfig("epoch-mismatch", rail, world, listOf(10.0))

        assertThrows(IllegalArgumentException::class.java) {
            RailwayWorldFrameSnapshot.create(1L, world.topologyRevision, listOf(train.publishedFrame))
        }
    }
}
