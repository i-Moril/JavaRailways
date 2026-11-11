package work.art1st.javarailways.simpleato.control

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import work.art1st.javarailways.basic.WayPoint
import work.art1st.javarailways.basic.api.ato.ControlDemand
import work.art1st.javarailways.basic.basicWorldData
import work.art1st.javarailways.basic.control.HandleTrainActuator
import work.art1st.javarailways.basic.control.TrainDynamics
import work.art1st.javarailways.basic.rail.*
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.train.Train
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot
import work.art1st.javarailways.core.physics.createRailForTest
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.rail.RailLocationWithFacing
import work.art1st.javarailways.core.physics.train.*
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinator
import work.art1st.javarailways.core.util.AsyncTickTestSupport
import work.art1st.javarailways.simpleato.api.ato.AtoSafetyProfile
import work.art1st.javarailways.simpleato.api.mission.*
import work.art1st.javarailways.simpleato.controller.SimpleATO
import work.art1st.javarailways.simpleato.simpleAtoWorldData
import work.art1st.javarailways.simpleato.testing.TestSimpleATOWorldDataRegistry

class NativeBodySpeedLimitsTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `both drivers retain both targets under body and release after tail clears`(reverse: Boolean) {
        withFixture(reverse) { f ->
            val physical = f.train.publishedFrame
            assertTrue(physical.bodyTraversal.any { it.railId == f.short.id })
            assertTrue(f.train.cars.flatMap { it.bogies }.none { it.location.rail === f.short })
            assertNotEquals(f.short.id, physical.head.railId)
            assertEquals(if (reverse) ViewDirection.BA else ViewDirection.AB, physical.head.facing)
            f.plan()
            listOf(f.automaticTargets(), manualProtectionFrame(f.train, f.actuator.controlCapabilities(0.0)))
                .forEach { assertCurrentBodyLimit(it, f.short, true) }

            // Actual Core placement clears the short rail; no remembered-head release is involved.
            f.world.onOwnerForTest { f.train.moveTrainForward(25.0); f.train.commit() }
            assertTrue(f.train.publishedFrame.bodyTraversal.none { it.railId == f.short.id })
            f.plan()
            listOf(f.automaticTargets(), manualProtectionFrame(f.train, f.actuator.controlCapabilities(0.0)))
                .forEach { assertCurrentBodyLimit(it, f.short, false) }
            assertEquals(1.5, nativeBodySpeedLimits(f.train, physical)[f.short.id],
                "retained input frame must not be replaced by a new head/body sample")
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `forward native entry is still anticipated by both drivers`(reverse: Boolean) {
        withFixture(reverse, approaching = true) { f ->
            assertTrue(f.train.publishedFrame.bodyTraversal.none { it.railId == f.short.id })
            f.plan()
            listOf(f.automaticTargets(), manualProtectionFrame(f.train, f.actuator.controlCapabilities(0.0)))
                .forEach { frame ->
                    val target = frame.protectionTargets.first { it.targetSpeedMetersPerSecond == 1.5 }
                    assertTrue(target.distanceMeters > 0.0, target.toString())
                    assertTrue(frame.referenceTargets.any { it.id == target.id &&
                        it.distanceMeters == target.distanceMeters && it.targetSpeedMetersPerSecond == 1.5 })
                    assertCurrentBodyLimit(frame, f.short, false)
                }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `real Basic equipment obeys body limit and accelerates only after actual tail clearance`(reverse: Boolean) {
        withFixture(reverse) { f ->
            f.authorize()
            f.world.physicsCoordinator.start()
            var occupiedTicks = 0
            var sawCleared = false
            var acceleratedAfterClear = false
            for (tick in 0 until 1800) {
                val before = f.train.publishedFrame
                val occupied = before.bodyTraversal.any { it.railId == f.short.id }
                f.world.physicsCoordinator.stepSimulation(50.0).await()
                assertFalse(f.world.simpleAtoWorldData.atoCoordinator.isStalled,
                    f.world.simpleAtoWorldData.atoCoordinator.lastStall.toString())
                val after = f.train.publishedFrame
                val decision = requireNotNull(f.world.simpleAtoWorldData.controlRuntime.latestDecision(f.train))
                if (occupied) {
                    occupiedTicks++
                    assertTrue(decision.referenceSpeedMetersPerSecond <= 1.5,
                        "reference released before tail clearance: $decision")
                    assertTrue(after.speedMetersPerSecond <= 1.5 + 1e-9,
                        "actual Basic equipment exceeded body limit at tick $tick: ${after.speedMetersPerSecond}")
                } else {
                    sawCleared = true
                    if (after.speedMetersPerSecond > 2.0) {
                        acceleratedAfterClear = true
                        break
                    }
                }
            }
            assertTrue(occupiedTicks > 10, "test must actually move with the limit under its body")
            assertTrue(sawCleared)
            assertTrue(acceleratedAfterClear)
            assertEquals(2, f.actuator.lastDynamicsResult.state.devices.size)
            assertTrue(f.world.simpleAtoWorldData.controlRuntime.incidents.isEmpty(),
                f.world.simpleAtoWorldData.controlRuntime.incidents.toString())
        }
    }

    @Test
    fun `empty body is unavailable and manual composition captures only one physical frame`() {
        withFixture(false) { f ->
            val frame = f.train.publishedFrame
            val empty = TrainFrameSnapshot.create(frame.trainId, frame.trainName, frame.revision, frame.physicsEpoch,
                frame.speedMetersPerSecond, frame.derailed, frame.reversed, frame.cars, frame.head, frame.tail,
                frame.occupiedIntervals, emptyList(), frame.sweptSpans, frame.headTraversal, frame.tailTraversal)
            assertThrows(IllegalStateException::class.java) { nativeBodySpeedLimits(f.train, empty) }
            val missingBody = object : Train by f.train { override val publishedFrame = empty }
            assertThrows(IllegalStateException::class.java) {
                manualProtectionFrame(missingBody, f.actuator.controlCapabilities(0.0))
            }
            var reads = 0
            val singleFrame = object : Train by f.train {
                override val publishedFrame: TrainFrameSnapshot get() {
                    check(++reads == 1) { "second physical sample" }
                    return frame
                }
                override val headLocation get() = error("manual composition must use captured frame head")
            }
            assertCurrentBodyLimit(manualProtectionFrame(singleFrame, f.actuator.controlCapabilities(0.0)), f.short, true)
            assertEquals(1, reads)
        }
    }

    private fun assertCurrentBodyLimit(frame: TrainControlFrame, rail: CoreRail, present: Boolean) {
        val hard = frame.protectionTargets.filter { it.id.contains("body/") && it.id.contains(rail.id.toString()) }
        val reference = frame.referenceTargets.filter { it.id.contains("body/") && it.id.contains(rail.id.toString()) }
        assertEquals(if (present) 1 else 0, hard.size, frame.toString())
        assertEquals(hard.size, reference.size, frame.toString())
        if (present) {
            assertEquals(0.0, hard.single().distanceMeters)
            assertEquals(1.5, hard.single().targetSpeedMetersPerSecond)
            assertEquals(hard.single().id, reference.single().id)
            assertEquals(0.0, reference.single().distanceMeters)
            assertEquals(1.5, reference.single().targetSpeedMetersPerSecond)
        }
    }

    private class Fixture(val owned: TestWorldFixture, val reverse: Boolean, approaching: Boolean) {
        val world = owned.world
        val before = world.createRailForTest(straight(0.0, 114.0))
        val short = world.createRailForTest(straight(114.0, 115.0))
        val after = world.createRailForTest(straight(115.0, 300.0))
        val train: CoreTrain
        val actuator: HandleTrainActuator
        val ato: SimpleATO
        init {
            world.onOwnerForTest { CoreRail.connect(before, short); CoreRail.connect(short, after) }
            short.infrastructureMetadata = RailInfrastructureMetadata(speedLimits = SpeedLimitMetadata(listOf(
                SpeedLimitEntry(1.5, SpeedLimitType.CURVE), SpeedLimitEntry(3.0, SpeedLimitType.SUBGRADE))))
            train = createTrainWithConfig("body-speed-$reverse", before, world, listOf(10.0, 10.0))
            world.onOwnerForTest {
                train.setLocation(RailLocationWithFacing(after.viewAB, 5.0)) // body x=100..120
                train.commit()
                if (reverse) train.reverse()
                if (approaching) train.setLocation(if (reverse) RailLocationWithFacing(after.viewBA, 160.0)
                    else RailLocationWithFacing(before.viewAB, 75.0))
                train.commit()
            }
            val equipment = ReferenceEquipmentFixture()
            actuator = HandleTrainActuator(train, equipment.profile, equipment.cars, 9.80665)
            val safety = AtoSafetyProfile(
                serviceDecelerationMetersPerSecondSquared = TrainDynamics.flatServiceDeceleration(
                    equipment.profile, equipment.cars, 9.80665),
                reactionTimeSeconds = HandleTrainActuator.serviceBrakeReactionTimeSeconds(
                    equipment.profile, equipment.cars, 9.80665))
            ato = SimpleATO(train, world, actuator, safety) { 0.0 }
            train.setControllerForTest(ato)
        }
        fun plan() {
            AsyncTickTestSupport.run { ato.tick(0.0) }
            ato.commit()
            assertFalse(world.simpleAtoWorldData.atoCoordinator.isStalled,
                world.simpleAtoWorldData.atoCoordinator.lastStall.toString())
        }
        // Narrow observation of the existing protected composition, not a mock control path.
        // Actual commands and motion are independently checked through real coordinator epochs above.
        fun automaticTargets(): TrainControlFrame = SimpleATO::class.java
            .getDeclaredMethod("motionControlFrame", ControlDemand::class.java).let { method ->
                method.isAccessible = true
                method.invoke(ato, ControlDemand(0.0)) as TrainControlFrame
            }
        fun authorize() {
            val head = train.publishedFrame.head
            world.basicWorldData.wayPoints.add(WayPoint("origin", head.railId, head.arcLengthInAbMeters))
            world.basicWorldData.wayPoints.add(if (reverse) WayPoint("target", before.id, 20.0)
                else WayPoint("target", after.id, 145.0))
            val accepted = ato.submitMission(AtoMissionRequest("body-mission", 1, WayPointRef("origin"),
                listOf(AtoMissionPoint.Stop(WayPointRef("target"))), requiredStartDirection = head.facing))
                as AtoMissionSubmissionResult.Accepted
            ato.setMovementAuthority(1, ato.terminalMovementAuthorityLimitMeters(
                accepted.mission.legs.single().plannedRoute.expandedRoute.totalLength))
        }
    }

    private fun withFixture(reverse: Boolean, approaching: Boolean = false, test: (Fixture) -> Unit) {
        PhysicsCoordinator(1, automaticFallback = false).use { physics ->
            TestWorldFixture.create(physics).use { owned ->
                // Outer use covers setup failure; inner use closes physics before world teardown.
                physics.use {
                    TestSimpleATOWorldDataRegistry.getOrCreate(owned.world)
                    try { test(Fixture(owned, reverse, approaching)) }
                    finally { TestSimpleATOWorldDataRegistry.unregister(owned.world) }
                }
            }
        }
    }

    companion object {
        private fun straight(start: Double, end: Double): PrimeCurve = PrimeCurve.fromPoints(listOf(
            Curve.RawPoint(Vector3d(start, 64.0, 0.0), Vector3d(1.0, 0.0, 0.0),
                Vector3d(0.0, 1.0, 0.0), 0.0, Curve.SegmentType.LINE_START),
            Curve.RawPoint(Vector3d(end, 64.0, 0.0), Vector3d(1.0, 0.0, 0.0),
                Vector3d(0.0, 1.0, 0.0), end - start, Curve.SegmentType.LINE_END)))
    }
}
