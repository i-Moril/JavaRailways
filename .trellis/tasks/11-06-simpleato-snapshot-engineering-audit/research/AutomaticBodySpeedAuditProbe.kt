package work.art1st.javarailways.simpleato.control

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.ato.ControlDemand
import work.art1st.javarailways.basic.rail.*
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.physics.createRailForTest
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.train.*
import work.art1st.javarailways.core.util.AsyncTickTestSupport
import work.art1st.javarailways.simpleato.api.ato.AtoSafetyProfile
import work.art1st.javarailways.simpleato.controller.SimpleATO
import work.art1st.javarailways.simpleato.simpleAtoWorldData
import work.art1st.javarailways.simpleato.testing.KinematicTrainActuator
import work.art1st.javarailways.simpleato.testing.TestSimpleATOWorldDataRegistry

/** Audit-only probe. Not wired into the repository build. Expected to fail on audit baseline. */
class AutomaticBodySpeedAuditProbe {
    @Test
    fun `automatic hard targets retain native limit under body after head enters unrestricted rail`() {
        TestWorldFixture.create().use { fixture ->
            val world = fixture.world
            TestSimpleATOWorldDataRegistry.getOrCreate(world)
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
                val train = createTrainWithConfig("automatic-body-speed", before, world, listOf(10.0, 10.0))
                assertFalse(train.isDerailed)
                assertTrue(train.publishedFrame.bodyTraversal.any { it.railId == short.id })
                assertTrue(train.cars.flatMap { it.bogies }.none { it.location.rail === short })
                assertSame(after, train.headLocation.rail)
                val profile = AtoSafetyProfile()
                val ato = SimpleATO(train, world, KinematicTrainActuator(10.0, profile), profile) { 0.0 }
                train.setControllerForTest(ato)
                // Populate the genuine controller constraints, but do not integrate synthetic dynamics.
                AsyncTickTestSupport.run { ato.tick(0.0) }
                ato.commit()
                assertFalse(world.simpleAtoWorldData.atoCoordinator.isStalled,
                    world.simpleAtoWorldData.atoCoordinator.lastStall.toString())
                val method = SimpleATO::class.java.getDeclaredMethod("motionControlFrame", ControlDemand::class.java)
                method.isAccessible = true
                val frame = method.invoke(ato, ControlDemand(0.0)) as TrainControlFrame
                assertTrue(frame.protectionTargets.any { it.distanceMeters == 0.0 &&
                    it.targetSpeedMetersPerSecond == 1.5 },
                    "Native occupied-rail limit is absent from automatic hard targets: ${frame.protectionTargets}")
            } finally {
                TestSimpleATOWorldDataRegistry.unregister(world)
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
