package work.art1st.javarailways.basic.api.ato

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.rail.RailInfrastructureMetadata
import work.art1st.javarailways.basic.rail.SpeedLimitEntry
import work.art1st.javarailways.basic.rail.SpeedLimitMetadata
import work.art1st.javarailways.basic.rail.SpeedLimitType
import work.art1st.javarailways.basic.rail.infrastructureMetadata
import work.art1st.javarailways.basic.testing.TestBasicWorldDataRegistry
import work.art1st.javarailways.core.api.physics.rail.RailLocation
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.train.Train
import work.art1st.javarailways.core.physics.rail.RailLocationWithFacing
import work.art1st.javarailways.core.physics.rail.RailLocationWithoutFacing
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.TestWorldFixture
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.createTrainWithConfig

class NativeRailSpeedLimitsTest {
    @Test
    fun `native rail query samples effective metadata and leaves earlier scalar detached`() {
        TestWorldFixture.create().use { fixture ->
            val world = fixture.world
            TestBasicWorldDataRegistry.getOrCreate(world)
            try {
                val rail = createStraightRailFromSegments(world,
                    PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1))
                assertNull(nativeRailSpeedLimit(rail))
                rail.infrastructureMetadata = RailInfrastructureMetadata(speedLimits = SpeedLimitMetadata(listOf(
                    SpeedLimitEntry(9.0, SpeedLimitType.SUBGRADE), SpeedLimitEntry(4.0, SpeedLimitType.CURVE))))
                val captured = nativeRailSpeedLimit(rail)
                assertEquals(4.0, captured)

                rail.infrastructureMetadata = RailInfrastructureMetadata(speedLimits = SpeedLimitMetadata(listOf(
                    SpeedLimitEntry(9.0, SpeedLimitType.SUBGRADE))))
                assertEquals(9.0, nativeRailSpeedLimit(rail))
                rail.infrastructureMetadata = RailInfrastructureMetadata()
                assertNull(nativeRailSpeedLimit(rail))
                assertEquals(4.0, captured)
            } finally {
                TestBasicWorldDataRegistry.unregister(world)
            }
        }
    }

    @Test
    fun `head context reads current head once without sampling frame or Basic metadata`() {
        TestWorldFixture.create().use { fixture ->
            val rail = createStraightRailFromSegments(fixture.world,
                PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1))
            val actual = createTrainWithConfig("native-head-context", rail, fixture.world, listOf(10.0))
            var reads = 0
            var head = RailLocationWithFacing(rail.viewBA, 40.0)
            val train = object : Train by actual {
                override val headLocation: RailLocation get() { reads++; return head }
                override val publishedFrame get() = error("head context must not sample a published frame")
            }
            // No Basic world data is installed: context capture must not query metadata.
            val first = nativeHeadSpeedLimitContext(train)
            assertEquals(1, reads)
            assertSame(rail, first.rail)
            assertEquals(ViewDirection.BA, first.facing)

            head = RailLocationWithFacing(rail.viewAB, 60.0)
            val second = nativeHeadSpeedLimitContext(train)
            assertEquals(2, reads)
            assertEquals(ViewDirection.AB, second.facing)
            assertEquals(ViewDirection.BA, first.facing)
        }
    }

    @Test
    fun `head without physical facing preserves the existing controller failure`() {
        TestWorldFixture.create().use { fixture ->
            val rail = createStraightRailFromSegments(fixture.world,
                PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1))
            val actual = createTrainWithConfig("native-head-no-facing", rail, fixture.world, listOf(10.0))
            val train = object : Train by actual {
                override val headLocation = RailLocationWithoutFacing(rail, 40.0)
            }
            val failure = assertThrows(IllegalStateException::class.java) { nativeHeadSpeedLimitContext(train) }
            assertEquals("SimpleATO requires a facing-aware train head location", failure.message)
        }
    }
}
