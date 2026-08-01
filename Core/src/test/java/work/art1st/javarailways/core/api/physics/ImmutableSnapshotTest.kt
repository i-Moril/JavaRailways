package work.art1st.javarailways.core.api.physics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.rail.RailObject
import work.art1st.javarailways.core.api.physics.rail.RailObjectLifecycleState
import work.art1st.javarailways.core.api.physics.rail.RailObjectRef
import work.art1st.javarailways.core.api.physics.rail.RailObjectSnapshot
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.train.TrainSnapshot
import work.art1st.javarailways.core.physics.rail.RailLocationWithFacing
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.createTestWorld
import work.art1st.javarailways.core.physics.train.createTrainWithConfig

class ImmutableSnapshotTest {
    @Test
    fun `captures rail object without retaining the live object`() {
        val world = createTestWorld()
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0))
        val live = object : RailObject {
            override val ref = RailObjectRef("test", "marker/1")
            override var lifecycleState = RailObjectLifecycleState.ACTIVE
            override val location = RailLocationWithFacing(rail.viewAB, 10.0)
        }

        val snapshot = RailObjectSnapshot.capture(live, ViewDirection.AB)
        val repeated = RailObjectSnapshot.capture(live, ViewDirection.AB)
        live.lifecycleState = RailObjectLifecycleState.REMOVED

        assertEquals(snapshot, repeated)
        assertEquals(RailObjectLifecycleState.ACTIVE, snapshot.lifecycleState)
        assertEquals(10.0, snapshot.position.arcLengthInAbMeters, 1.0e-9)
    }

    @Test
    fun `captures train state using value-only rail positions`() {
        val world = createTestWorld()
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(200.0))
        val train = createTrainWithConfig("snapshot-train", rail, world, listOf(10.0))

        val snapshot = TrainSnapshot.capture(train, revision = 7L, facing = ViewDirection.AB)

        assertEquals("snapshot-train", snapshot.trainId)
        assertEquals(7L, snapshot.revision)
        assertEquals(rail.id, snapshot.head.railId)
        assertEquals(1, snapshot.carCount)
    }

    @Test
    fun `snapshot values reject invalid units and revisions`() {
        val world = createTestWorld()
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(20.0))
        assertThrows(IllegalArgumentException::class.java) {
            RailPositionSnapshot(rail.id, ViewDirection.AB, Double.NaN)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RailObjectRef("Invalid Namespace", "object")
        }
    }
}
