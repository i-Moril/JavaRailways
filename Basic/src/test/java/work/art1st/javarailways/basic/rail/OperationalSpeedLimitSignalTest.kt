package work.art1st.javarailways.basic.rail

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.testing.TestBasicWorldDataRegistry
import work.art1st.javarailways.basic.basicWorldData
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.TestWorldFixture

class OperationalSpeedLimitSignalTest {
    private val fixtures = mutableListOf<TestWorldFixture>()

    private fun newTestWorld() = TestWorldFixture.create().also { fixtures += it }.world

    @AfterEach
    fun closeTestWorlds() {
        var firstFailure: Throwable? = null
        fixtures.asReversed().forEach { fixture ->
            try {
                fixture.close()
            } catch (failure: Throwable) {
                val primary = firstFailure
                if (primary == null) firstFailure = failure else primary.addSuppressed(failure)
            }
        }
        fixtures.clear()
        firstFailure?.let { throw it }
    }

    @Test
    fun `materializes one direction-filtered entry signal per RailView`() {
        val world = newTestWorld()
        TestBasicWorldDataRegistry.getOrCreate(world)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(40.0, segmentCount = 1)
        )
        rail.infrastructureMetadata = RailInfrastructureMetadata(
            speedLimits = SpeedLimitMetadata(
                listOf(
                    SpeedLimitEntry(30.0, SpeedLimitType.SUBGRADE),
                    SpeedLimitEntry(20.0, SpeedLimitType.CURVE)
                )
            )
        )
        OperationalSpeedLimitSignalMaterializer.materialize(world)

        val ab = rail.viewAB.staticRailObjects.single() as OperationalSpeedLimitSignal
        val ba = rail.viewBA.staticRailObjects.single() as OperationalSpeedLimitSignal
        assertEquals(ViewDirection.AB, ab.appliesTo)
        assertEquals(ViewDirection.BA, ba.appliesTo)
        assertEquals(0.0, ab.location.arcLengthIn(ViewDirection.AB))
        assertEquals(0.0, ba.location.arcLengthIn(ViewDirection.BA))
        assertEquals(20.0, ab.speedLimitMetersPerSecond)
        assertEquals(20.0, ba.speedLimitMetersPerSecond)
        assertTrue(ab !in rail.viewBA.staticRailObjects)
        assertTrue(ba !in rail.viewAB.staticRailObjects)
    }

    @Test
    fun `empty metadata creates release transitions`() {
        val world = newTestWorld()
        TestBasicWorldDataRegistry.getOrCreate(world)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(40.0, segmentCount = 1)
        )

        OperationalSpeedLimitSignalMaterializer.materialize(world)

        val signal = rail.viewAB.staticRailObjects.single() as OperationalSpeedLimitSignal
        assertNull(signal.speedLimitMetersPerSecond)
    }
}
