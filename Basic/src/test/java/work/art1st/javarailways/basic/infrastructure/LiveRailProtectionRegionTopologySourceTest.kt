package work.art1st.javarailways.basic.infrastructure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.route.ProtectionBoundaryDefinition
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.createTestWorld

class LiveRailProtectionRegionTopologySourceTest {
    @Test
    fun `captures immutable public rail topology at the requested revision`() {
        val world = createTestWorld()
        try {
            val first = createStraightRailFromSegments(
                world,
                PrimeCurveGenerator.buildStraight(40.0, segmentCount = 2)
            )
            val revision = world.topologyRevision
            val source = LiveRailProtectionRegionTopologySource(
                currentTopologyRevision = { world.topologyRevision },
                railLookup = world::getRail
            )

            val result = source.capture(
                listOf(
                    ProtectionBoundaryDefinition(
                        "test:boundary/entry",
                        RailPositionSnapshot(first.id, ViewDirection.AB, 5.0)
                    )
                ),
                revision
            )

            val captured = assertInstanceOf(ProtectionRegionTopologyCapture.Captured::class.java, result)
            assertEquals(revision, captured.topology.topologyRevision)
            assertTrue(first.id in captured.topology.rails)
            assertEquals(first.curve.totalArcLength, captured.topology.rails.getValue(first.id).lengthMeters)
        } finally {
            world.physicsCoordinator.close()
        }
    }

    @Test
    fun `rejects stale revision before reading rails`() {
        val source = LiveRailProtectionRegionTopologySource(
            currentTopologyRevision = { 4L },
            railLookup = { error("stale capture must not read rails") }
        )

        val result = source.capture(emptyList(), 3L)

        assertInstanceOf(ProtectionRegionTopologyCapture.Rejected::class.java, result)
    }
}
