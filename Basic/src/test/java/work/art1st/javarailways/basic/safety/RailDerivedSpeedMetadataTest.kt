package work.art1st.javarailways.basic.safety

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.basicWorldData
import work.art1st.javarailways.basic.curve.Anchor
import work.art1st.javarailways.basic.curve.StandardTurnoutFactory
import work.art1st.javarailways.basic.curve.StandardTurnoutGeometry
import work.art1st.javarailways.basic.persistence.BasicInfrastructureSnapshotV2
import work.art1st.javarailways.basic.persistence.SavedRailInfrastructureMetadataV1
import work.art1st.javarailways.basic.rail.CurveSafetyMetadata
import work.art1st.javarailways.basic.rail.RailInfrastructureMetadata
import work.art1st.javarailways.basic.rail.RailAuthoringMetadata
import work.art1st.javarailways.basic.rail.SpeedLimitMetadata
import work.art1st.javarailways.basic.rail.SpeedLimitEntry
import work.art1st.javarailways.basic.rail.SpeedLimitType
import work.art1st.javarailways.basic.rail.OperationalSpeedLimitSignal
import work.art1st.javarailways.basic.rail.infrastructureMetadata
import work.art1st.javarailways.basic.testing.TestBasicWorldDataRegistry
import work.art1st.javarailways.core.api.util.annotation.Heavy
import work.art1st.javarailways.core.physics.createRailForTest
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.TestWorldFixture

@OptIn(Heavy::class)
class RailDerivedSpeedMetadataTest {
    private fun curve() = requireNotNull(StandardTurnoutFactory.build(
        Anchor("A", Vector3d(), Vector3d(1.0, 0.0, 0.0)),
        Vector3d(1.0, 0.0, -0.2), StandardTurnoutGeometry(9, 180.0, 30.0)
    ))

    private fun metadata(automatic: Boolean = true) = RailInfrastructureMetadata(
        authoring = RailAuthoringMetadata(designSpeedMetersPerSecond = 100.0, curveSpeedLimitAutomatic = automatic),
        speedLimits = SpeedLimitMetadata(listOf(
            SpeedLimitEntry(100.0, SpeedLimitType.SUBGRADE), SpeedLimitEntry(2.0, SpeedLimitType.CURVE)
        )),
        curveSafety = CurveSafetyMetadata(3.0)
    )

    @Test
    fun `restored stale snapshots refresh before both entry signals and static queries`() {
        TestWorldFixture.create().use { fixture ->
            val world = fixture.world
            TestBasicWorldDataRegistry.getOrCreate(world)
            try {
                val rail = world.createRailForTest(curve())
                val saved = metadata()
                BasicInfrastructureSnapshotV2(
                    BasicInfrastructureSnapshotV2.CURRENT_FORMAT_VERSION, 1L, emptyList(),
                    listOf(SavedRailInfrastructureMetadataV1.capture(rail.id, saved))
                ).restore(world)
                assertTrue(rail.viewAB.staticRailObjects.isEmpty())
                val physics = RailSpeedPhysics(curveSpeedLimitMultiplier = 1.5)
                RailDerivedSpeedMetadata.refreshWorld(world, physics, 3.0)
                val refreshed = rail.infrastructureMetadata
                val expected = RailSpeedCalculator.calculate(rail.curve, 100.0, 0.075, 3.0, physics)
                assertEquals(expected.operatingLimitMetersPerSecond,
                    refreshed.speedLimits.effectiveLimitMetersPerSecond())
                assertEquals(expected.derailSpeedMetersPerSecond,
                    refreshed.curveSafety.baseCurveDerailSpeedMetersPerSecond)
                assertEquals(saved.authoring, refreshed.authoring)
                listOf(rail.viewAB, rail.viewBA).forEach { view ->
                    assertEquals(expected.operatingLimitMetersPerSecond,
                        (view.staticRailObjects.single() as OperationalSpeedLimitSignal).speedLimitMetersPerSecond)
                }
                assertEquals(expected.operatingLimitMetersPerSecond,
                    world.basicWorldData.railMetadata.speedLimits(world.topologyState, listOf(rail.id))[rail.id])
                RailDerivedSpeedMetadata.refreshWorld(world, physics, 3.0)
                assertEquals(refreshed, rail.infrastructureMetadata)
                RailDerivedSpeedMetadata.refreshWorld(world, physics.copy(gravityMetersPerSecondSquared = 5.0), 2.0)
                assertNotEquals(refreshed.speedLimits, rail.infrastructureMetadata.speedLimits)
                assertNotEquals(refreshed.curveSafety, rail.infrastructureMetadata.curveSafety)
            } finally {
                TestBasicWorldDataRegistry.unregister(world)
            }
        }
    }

    @Test
    fun `manual values and subgrade remain author inputs while safety updates`() {
        val original = metadata(automatic = false)
        val refreshed = RailDerivedSpeedMetadata.refresh(original, curve(), RailSpeedPhysics(curveSpeedLimitMultiplier = 2.0), 3.0)
        assertEquals(original.speedLimits, refreshed.speedLimits)
        assertEquals(original.authoring, refreshed.authoring)
        assertNotEquals(original.curveSafety, refreshed.curveSafety)
        val automatic = RailDerivedSpeedMetadata.refresh(metadata(), curve(), RailSpeedPhysics(curveSpeedLimitMultiplier = 2.0), 3.0)
        assertEquals(100.0, automatic.speedLimits.value(SpeedLimitType.SUBGRADE))
        val subgrade = automatic.copy(speedLimits = SpeedLimitMetadata(listOf(
            SpeedLimitEntry(5.0, SpeedLimitType.SUBGRADE),
            SpeedLimitEntry(automatic.speedLimits.value(SpeedLimitType.CURVE)!!, SpeedLimitType.CURVE)
        )))
        assertEquals(5.0, subgrade.speedLimits.effectiveLimitMetersPerSecond())
    }

    @Test
    fun `straight edit removes automatic curve snapshot and keeps manual input`() {
        val straight = PrimeCurveGenerator.buildStraight(40.0, segmentCount = 1, step = 1.0).single()
        val refreshed = RailDerivedSpeedMetadata.refresh(metadata(), straight, RailSpeedPhysics(), 3.0)
        assertNull(refreshed.speedLimits.value(SpeedLimitType.CURVE))
        assertNull(refreshed.curveSafety.baseCurveDerailSpeedMetersPerSecond)
        assertEquals(100.0, refreshed.speedLimits.effectiveLimitMetersPerSecond())
        val manual = metadata(false)
        assertEquals(manual.speedLimits,
            RailDerivedSpeedMetadata.refresh(manual, straight, RailSpeedPhysics(), 3.0).speedLimits)
        assertEquals(RailInfrastructureMetadata.EMPTY,
            RailDerivedSpeedMetadata.refresh(RailInfrastructureMetadata.EMPTY, straight, RailSpeedPhysics(), 3.0))
    }
}
