package work.art1st.javarailways.simpleato.control

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.control.VehicleControlCapabilityModel
import work.art1st.javarailways.basic.rail.RailInfrastructureMetadata
import work.art1st.javarailways.basic.rail.SpeedLimitEntry
import work.art1st.javarailways.basic.rail.SpeedLimitMetadata
import work.art1st.javarailways.basic.rail.SpeedLimitType
import work.art1st.javarailways.basic.rail.infrastructureMetadata
import work.art1st.javarailways.basic.testing.TestBasicWorldDataRegistry
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.physics.train.Train
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot
import work.art1st.javarailways.core.physics.createRailForTest
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.train.TestWorldFixture
import work.art1st.javarailways.core.physics.train.createTrainWithConfig
import work.art1st.javarailways.core.physics.train.onOwnerForTest

class ManualProtectionFrameTest {
    @Test
    fun `unfinished same-side junction remains a zero speed target before the physical endpoint`() {
        TestWorldFixture.create().use { fixture ->
            val world = fixture.world
            TestBasicWorldDataRegistry.getOrCreate(world)
            try {
                val approach = world.createRailForTest(straight(0.0, 150.0))
                val from = Vector3d(150.0, 64.0, 0.0)
                val to = Vector3d(100.0, 64.0, 10.0)
                val tangent = Vector3d(to).sub(from).normalize()
                val branch = world.createRailForTest(PrimeCurve.fromPoints(listOf(
                    Curve.RawPoint(from, tangent, Vector3d(0.0, 1.0, 0.0), 0.0),
                    Curve.RawPoint(to, tangent, Vector3d(0.0, 1.0, 0.0), from.distance(to))
                )))
                world.onOwnerForTest { CoreRail.connect(approach, branch) }
                val train = createTrainWithConfig("manual-incomplete-junction", approach, world, listOf(10.0, 10.0))
                val equipment = ReferenceEquipmentFixture()
                val capabilities = VehicleControlCapabilityModel(equipment.profile, equipment.cars, 9.80665)
                    .snapshot(0.0, equipment.carInputs)

                val frame = manualProtectionFrame(train, capabilities)

                assertTrue(approach.viewAB.nextRails.isEmpty())
                val stop = frame.protectionTargets.single { it.id == "manual/observed-boundary" }
                assertEquals(150.0 - train.publishedFrame.head.arcLengthInAbMeters, stop.distanceMeters, 1e-9)
                assertEquals(0.0, stop.targetSpeedMetersPerSecond)
                assertTrue(frame.referenceTargets.any { it.id == stop.id && it.targetSpeedMetersPerSecond == 0.0 })
                assertFalse(train.isDerailed)
            } finally {
                TestBasicWorldDataRegistry.unregister(world)
            }
        }
    }

    @Test
    fun `current native speed restriction survives short rail between bogies under actual body`() {
        TestWorldFixture.create().use { fixture ->
            val world = fixture.world
            TestBasicWorldDataRegistry.getOrCreate(world)
            try {
                val before = world.createRailForTest(straight(0.0, 24.0))
                val short = world.createRailForTest(straight(24.0, 25.0))
                val after = world.createRailForTest(straight(25.0, 150.0))
                world.onOwnerForTest {
                    CoreRail.connect(before, short)
                    CoreRail.connect(short, after)
                }
                short.infrastructureMetadata = RailInfrastructureMetadata(speedLimits = SpeedLimitMetadata(
                    listOf(SpeedLimitEntry(1.5, SpeedLimitType.SUBGRADE))))
                // The real two-car placement spans x=10..30. Its bogies are at x=10,20,30,
                // while this one-metre rail lies strictly inside the leading car body.
                val train = createTrainWithConfig("manual-body-speed", before, world, listOf(10.0, 10.0))
                assertFalse(train.isDerailed)
                val published = train.publishedFrame
                assertTrue(published.bodyTraversal.any { it.railId == short.id })
                assertTrue(train.cars.flatMap { it.bogies }.none { it.location.rail === short })
                assertSame(after, train.headLocation.rail, "the short rail is behind the forward scan")
                val equipment = ReferenceEquipmentFixture()
                val capabilities = VehicleControlCapabilityModel(equipment.profile, equipment.cars, 9.80665)
                    .snapshot(0.0, equipment.carInputs)

                val frame = manualProtectionFrame(train, capabilities)

                val target = frame.protectionTargets.single { it.id == "infrastructure/body/${short.id}" }
                assertEquals(0.0, target.distanceMeters)
                assertEquals(1.5, target.targetSpeedMetersPerSecond)
                assertTrue(frame.referenceTargets.any {
                    it.id == target.id && it.distanceMeters == 0.0 && it.targetSpeedMetersPerSecond == 1.5
                })
                assertEquals(0.0, frame.mostAdverseGrade)
            } finally {
                TestBasicWorldDataRegistry.unregister(world)
            }
        }
    }

    @Test
    fun `missing published body evidence is rejected rather than assuming bogie coverage`() {
        TestWorldFixture.create().use { fixture ->
            val world = fixture.world
            TestBasicWorldDataRegistry.getOrCreate(world)
            try {
                val rail = world.createRailForTest(straight(0.0, 150.0))
                val train = createTrainWithConfig("manual-missing-body", rail, world, listOf(10.0, 10.0))
                val missing = object : Train by train {
                    override val publishedFrame: TrainFrameSnapshot? get() = null
                }
                val equipment = ReferenceEquipmentFixture()
                val capabilities = VehicleControlCapabilityModel(equipment.profile, equipment.cars, 9.80665)
                    .snapshot(0.0, equipment.carInputs)
                assertThrows(IllegalArgumentException::class.java) { manualProtectionFrame(missing, capabilities) }
            } finally {
                TestBasicWorldDataRegistry.unregister(world)
            }
        }
    }

    private fun straight(start: Double, end: Double): PrimeCurve = PrimeCurve.fromPoints(listOf(
        Curve.RawPoint(Vector3d(start, 64.0, 0.0), Vector3d(1.0, 0.0, 0.0),
            Vector3d(0.0, 1.0, 0.0), 0.0, Curve.SegmentType.LINE_START),
        Curve.RawPoint(Vector3d(end, 64.0, 0.0), Vector3d(1.0, 0.0, 0.0),
            Vector3d(0.0, 1.0, 0.0), end - start, Curve.SegmentType.LINE_END)
    ))
}
