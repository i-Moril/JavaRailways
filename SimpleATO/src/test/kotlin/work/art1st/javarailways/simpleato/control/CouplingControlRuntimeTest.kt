package work.art1st.javarailways.simpleato.control

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import work.art1st.javarailways.basic.api.ato.VehicleControlCommand
import work.art1st.javarailways.basic.api.ato.VehicleControlState
import work.art1st.javarailways.basic.api.route.CouplingContactAuthority
import work.art1st.javarailways.basic.api.route.RouteAdmissionTestFixtures
import work.art1st.javarailways.basic.api.route.RouteBoundary
import work.art1st.javarailways.basic.control.TrainDynamics
import work.art1st.javarailways.basic.rail.OperationalSpeedLimitSignal
import work.art1st.javarailways.basic.rail.RailInfrastructureMetadata
import work.art1st.javarailways.basic.rail.SpeedLimitEntry
import work.art1st.javarailways.basic.rail.SpeedLimitMetadata
import work.art1st.javarailways.basic.rail.SpeedLimitType
import work.art1st.javarailways.basic.rail.infrastructureMetadata
import work.art1st.javarailways.basic.testing.TestBasicWorldDataRegistry
import work.art1st.javarailways.core.api.physics.rail.RailObjectRef
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.train.CouplingApproachInspection
import work.art1st.javarailways.core.api.physics.train.Train
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.rail.RailLocationWithFacing
import work.art1st.javarailways.core.physics.rail.RailLocationWithoutFacing
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.*
import work.art1st.javarailways.core.util.AsyncTickTestSupport
import work.art1st.javarailways.simpleato.api.atp.AtpReason
import work.art1st.javarailways.simpleato.api.atp.AtpTarget

class CouplingControlRuntimeTest {
    @Test
    fun `manual coupling exempts only the exact peer end and retains native signal and obstacle protection`() =
        withContact { world, moving, target, authority ->
            val rail = checkNotNull(world.getRail(moving.publishedFrame.head.railId))
            val third = createTrainWithConfig("third", rail, world, listOf(10.0), carIdPrefix = "third-")
            world.onOwnerForTest {
                third.setLocation(RailLocationWithFacing(rail.viewAB, 85.0)); third.commitMaintenancePlacement()
            }
            rail.infrastructureMetadata = RailInfrastructureMetadata(speedLimits = SpeedLimitMetadata(
                listOf(SpeedLimitEntry(0.15, SpeedLimitType.SUBGRADE))))
            val signal = OperationalSpeedLimitSignal(RailObjectRef("test", "coupling-signal"),
                RailLocationWithoutFacing(rail, 55.0), ViewDirection.AB,
                SpeedLimitMetadata(listOf(SpeedLimitEntry(0.1, SpeedLimitType.SUBGRADE))))
            rail.signals += signal
            val equipment = ImmediateEquipmentFixture()
            val capabilities = equipment.model.snapshot(0.0, equipment.cars)
            val ordinary = manualProtectionFrame(moving, capabilities)
            val coupling = manualProtectionFrame(moving, capabilities, authority)
            val exemptedId = "following/${target.tail.ref.id}/0"
            val capId = "coupling/${authority.eventId}/maximum-approach-speed"
            assertTrue(ordinary.protectionTargets.any { it.id == exemptedId })
            assertFalse(coupling.protectionTargets.any { it.id == exemptedId })
            assertTrue(coupling.protectionTargets.any { it.id == "following/${target.head.ref.id}/0" })
            assertTrue(coupling.protectionTargets.any { it.id == "following/${third.tail.ref.id}/0" })
            assertEquals(ordinary.protectionTargets.filterNot { it.id == exemptedId },
                coupling.protectionTargets.filterNot { it.id == capId })
            val cap = coupling.protectionTargets.single { it.id == capId }
            assertEquals(0.0, cap.distanceMeters)
            assertEquals(0.2, cap.targetSpeedMetersPerSecond)
            assertEquals(cap.targetSpeedMetersPerSecond,
                coupling.referenceTargets.single { it.id == cap.id }.targetSpeedMetersPerSecond)
            assertTrue(coupling.protectionTargets.any { it.id == "infrastructure/body/${rail.id}" })
            assertTrue(coupling.protectionTargets.any { it.id == "infrastructure/${signal.ref.id}/0" })
        }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `routed manual and automatic driving receive the same coupling cap without removing other limits`(automatic: Boolean) =
        withContact { _, moving, _, authority ->
            val runtime = RailwayControlRuntime("contact-test")
            try {
                runtime.registerCouplingAuthority(moving, authority)
                val equipment = ImmediateEquipmentFixture()
                val state = TrainDynamics.initialState(listOf(equipment.car), 1.0)
                val capabilities = equipment.model.snapshot(1.0, equipment.cars)
                val controlState = VehicleControlState(1.0, state.acceleration, state.brakeScale,
                    VehicleControlCommand.COAST, state.devices.toList())
                val original = TrainControlFrame(listOf(MotionReferenceConstraint("retained-limit", 0.0, 8.0)),
                    listOf(AtpTarget("retained-limit", 0.0, 8.0)), 0.0)
                val command = if (automatic) runtime.automatic(moving, controlState, capabilities, original, 0.05)
                else {
                    runtime.registerProtectionSource(moving) { original }
                    runtime.supervise(moving, controlState, capabilities, VehicleControlCommand.COAST, 0.05)
                }
                assertTrue(command.emergencyBrake)
                val event = checkNotNull(runtime.latestIntervention).event
                assertTrue(event.judgement.witness?.target?.id?.startsWith("coupling/") == true,
                    event.judgement.toString())
                val frame = (event.context as AtpDiagnosticContext).frame
                assertTrue(frame.protectionTargets.containsAll(original.protectionTargets))
                assertTrue(frame.referenceTargets.containsAll(original.referenceTargets))
                val cap = frame.protectionTargets.single { it.id.startsWith("coupling/") }
                assertEquals(0.2, cap.targetSpeedMetersPerSecond)
                assertEquals(0.0, cap.distanceMeters)
                assertEquals(cap.targetSpeedMetersPerSecond,
                    frame.referenceTargets.single { it.id == cap.id }.targetSpeedMetersPerSecond)
                assertEquals(if (automatic) VehicleExecutionMode.AUTO else VehicleExecutionMode.MANUAL, event.mode)
            } finally { runtime.close() }
        }

    @Test
    fun `stale target keeps coupling fail closed until the owning operation detaches its authority`() =
        withContact { world, moving, target, authority ->
            val runtime = RailwayControlRuntime("contact-test")
            try {
                assertEquals(100.0, runtime.constrainMovementDistance(moving, 100.0))
                runtime.registerCouplingAuthority(moving, authority)
                val sameIdDifferentTrain = object : Train by moving {}
                assertNull(runtime.couplingAuthority(sameIdDifferentTrain))
                runtime.detachCouplingAuthority(sameIdDifferentTrain)
                assertSame(authority, runtime.couplingAuthority(moving))
                val clamped = AsyncTickTestSupport.run { runtime.constrainMovementDistance(moving, 100.0) }
                assertEquals(1.4, clamped, 0.002)
                assertEquals(50.0, moving.publishedFrame.head.arcLengthInAbMeters, 1e-8)
                world.onOwnerForTest {
                    target.setLocation(RailLocationWithFacing(checkNotNull(world.getRail(target.publishedFrame.head.railId)).viewAB, 63.0))
                    target.commitMaintenancePlacement()
                }
                val equipment = ImmediateEquipmentFixture()
                val state = TrainDynamics.initialState(listOf(equipment.car), 0.0)
                val capabilities = equipment.model.snapshot(0.0, equipment.cars)
                assertThrows(IllegalStateException::class.java) { manualProtectionFrame(moving, capabilities, authority) }
                val command = runtime.supervise(moving, VehicleControlState(0.0, state.acceleration,
                    state.brakeScale, VehicleControlCommand.COAST, state.devices.toList()), capabilities,
                    VehicleControlCommand.COAST, 0.05)
                assertTrue(command.emergencyBrake)
                assertEquals(AtpReason.INPUT_UNAVAILABLE, checkNotNull(runtime.latestIntervention).event.judgement.reason)
                assertThrows(IllegalStateException::class.java) {
                    AsyncTickTestSupport.run { runtime.constrainMovementDistance(moving, 1.0) }
                }
                assertSame(authority, runtime.couplingAuthority(moving))
                runtime.detachCouplingAuthority(moving)
                assertNull(runtime.couplingAuthority(moving))
                assertEquals(1.0, runtime.constrainMovementDistance(moving, 1.0))
            } finally { runtime.close() }
        }

    private fun withContact(action: (CoreWorld, CoreTrain, CoreTrain, CouplingContactAuthority) -> Unit) {
        TestWorldFixture.create().use { fixture ->
            val world = fixture.world
            TestBasicWorldDataRegistry.getOrCreate(world)
            try {
                val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(200.0, 1))
                val moving = createTrainWithConfig("moving", rail, world, listOf(10.0),
                    connectorLength = 0.3, carIdPrefix = "moving-")
                val target = createTrainWithConfig("target", rail, world, listOf(10.0),
                    connectorLength = 0.3, carIdPrefix = "target-")
                val proof = world.onOwnerForTest {
                    moving.setLocation(RailLocationWithFacing(rail.viewAB, 50.0)); moving.commitMaintenancePlacement()
                    target.setLocation(RailLocationWithFacing(rail.viewAB, 62.0)); target.commitMaintenancePlacement()
                    assertInstanceOf(CouplingApproachInspection.Accepted::class.java,
                        world.inspectCouplingModeEntry(moving.id, target.id, 5.0)).proof
                }
                val authority = RouteAdmissionTestFixtures.couplingContactAuthority("test:contact", proof,
                    RouteBoundary(proof.approachingFrame.bodyTraversal.sumOf {
                        kotlin.math.abs(it.endArcLengthInAbMeters - it.startArcLengthInAbMeters)
                    } + proof.distanceToContactMeters), 0.2)
                action(world, moving, target, authority)
            } finally { TestBasicWorldDataRegistry.unregister(world) }
        }
    }
}
