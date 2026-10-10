package work.art1st.javarailways.core.api.physics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.rail.RailObject
import work.art1st.javarailways.core.api.physics.rail.RailObjectLifecycleState
import work.art1st.javarailways.core.api.physics.rail.RailObjectRef
import work.art1st.javarailways.core.api.physics.rail.RailObjectSnapshot
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.train.RailTraversalSegmentSnapshot
import work.art1st.javarailways.core.api.physics.train.TrainSnapshot
import work.art1st.javarailways.core.physics.rail.RailLocationWithFacing
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.createTestWorld
import work.art1st.javarailways.core.physics.train.createTrainWithConfig
import java.util.UUID

class ImmutableSnapshotTest {
    @Test
    fun `captures rail object without retaining the live object`() {
        val world = createTestWorld()
        try {
            val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0))
            val live = object : RailObject {
                override val ref = RailObjectRef("test", "marker/1")
                override var lifecycleState = RailObjectLifecycleState.ACTIVE
                override var location = RailLocationWithFacing(rail.viewAB, 10.0)
            }

            val snapshot = RailObjectSnapshot.capture(live, ViewDirection.AB)
            val repeated = RailObjectSnapshot.capture(live, ViewDirection.AB)
            live.lifecycleState = RailObjectLifecycleState.REMOVED
            live.location = RailLocationWithFacing(rail.viewAB, 15.0)
            val changed = RailObjectSnapshot.capture(live, ViewDirection.AB)

            assertEquals(snapshot, repeated)
            assertEquals(RailObjectLifecycleState.ACTIVE, snapshot.lifecycleState)
            assertEquals(RailPositionSnapshot(rail.id, ViewDirection.AB, 10.0), snapshot.position)
            assertEquals(RailObjectLifecycleState.REMOVED, changed.lifecycleState)
            assertEquals(15.0, changed.position.arcLengthInAbMeters, 1.0e-9)
            assertNotEquals(snapshot, changed)
        } finally {
            world.physicsCoordinator.close()
            (world.tickManager as? AutoCloseable)?.close()
        }
    }

    @Test
    fun `captures train state using value-only rail positions`() {
        val world = createTestWorld()
        try {
            val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(200.0))
            val train = createTrainWithConfig("snapshot-train", rail, world, listOf(10.0))

            val snapshot = TrainSnapshot.capture(train, revision = 7L, facing = ViewDirection.AB)
            val initialHeadArc = train.headLocation.arcLengthIn(ViewDirection.AB)
            val initialTailArc = train.tailLocation.arcLengthIn(ViewDirection.AB)
            train.setLocation(requireNotNull(train.head.location.forward(30.0)))
            train.commit()
            val changed = TrainSnapshot.capture(train, revision = 8L, facing = ViewDirection.AB)

            assertEquals("snapshot-train", snapshot.trainId)
            assertEquals(7L, snapshot.revision)
            assertEquals(rail.id, snapshot.head.railId)
            assertEquals(1, snapshot.carCount)
            assertEquals(initialHeadArc, snapshot.head.arcLengthInAbMeters, 1.0e-9)
            assertEquals(initialTailArc, snapshot.tail.arcLengthInAbMeters, 1.0e-9)
            assertNotEquals(snapshot.head, changed.head)
            assertNotEquals(snapshot.tail, changed.tail)
        } finally {
            world.physicsCoordinator.close()
            (world.tickManager as? AutoCloseable)?.close()
        }
    }

    @Test
    fun `snapshot values reject invalid units and revisions`() {
        val railId = UUID.randomUUID()
        assertThrows(IllegalArgumentException::class.java) {
            RailPositionSnapshot(railId, ViewDirection.AB, Double.NaN)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RailObjectRef("Invalid Namespace", "object")
        }
        assertThrows(IllegalArgumentException::class.java) {
            RailTraversalSegmentSnapshot(railId, ViewDirection.AB, 2.0, 1.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RailTraversalSegmentSnapshot(railId, ViewDirection.BA, 1.0, 2.0)
        }
    }
}
