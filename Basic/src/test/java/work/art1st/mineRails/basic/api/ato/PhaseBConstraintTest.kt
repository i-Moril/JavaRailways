package work.art1st.mineRails.basic.api.ato

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.mineRails.basic.api.ato.constraint.ConstraintKey
import work.art1st.mineRails.basic.api.ato.constraint.ConstraintManager
import work.art1st.mineRails.basic.api.ato.constraint.ConstraintOwner
import work.art1st.mineRails.basic.api.ato.constraint.ConstraintOwnerType
import work.art1st.mineRails.basic.api.ato.constraint.CurrentSpeedLimitConstraint
import work.art1st.mineRails.basic.api.ato.constraint.StopConstraint
import kotlin.math.sqrt

class PhaseBConstraintTest {
    private val profile = AtoSafetyProfile(reactionTimeSeconds = 0.0, stopToleranceMeters = 0.0)

    @Test
    fun `stop constraint creates a braking curve instead of an immediate zero limit`() {
        val constraint = StopConstraint(ConstraintKey("test", "stop"), remainingDistanceMeters = 50.0)

        assertEquals(
            sqrt(100.0),
            constraint.permittedSpeedMetersPerSecond(10.0, profile),
            1.0e-9
        )
        assertEquals(0.0, constraint.copy(remainingDistanceMeters = 0.0)
            .permittedSpeedMetersPerSecond(10.0, profile))
    }

    @Test
    fun `owner replacement is atomic and minimum constraint wins`() {
        val manager = ConstraintManager()
        val owner = ConstraintOwner(ConstraintOwnerType.OPERATIONS, "test")
        manager.replace(
            owner,
            listOf(CurrentSpeedLimitConstraint(ConstraintKey("test", "slow"), 8.0))
        )
        assertEquals(8.0, manager.permittedSpeedMetersPerSecond(0.0, 20.0, profile))

        manager.replace(
            owner,
            listOf(CurrentSpeedLimitConstraint(ConstraintKey("test", "slower"), 5.0))
        )
        assertEquals(5.0, manager.permittedSpeedMetersPerSecond(0.0, 20.0, profile))
        assertEquals(1, manager.snapshot().getValue(owner).size)
    }

    @Test
    fun `kinematic actuator respects train maximum speed and freezes`() {
        val actuator = KinematicTrainActuator(10.0, profile)
        val step = actuator.step(0.0, ControlDemand(20.0), 1.0)

        assertEquals(1.0, step.speedMetersPerSecond, 1.0e-9)
        assertEquals(0.5, step.distanceMeters, 1.0e-9)
        assertEquals(0.0, actuator.freeze().speedMetersPerSecond)
    }

    @Test
    fun `world coordinators stall independently`() {
        val first = AtoWorldCoordinator("first", reportSink = {})
        val second = AtoWorldCoordinator("second", reportSink = {})
        val report = AtoStallReport(
            worldId = "first",
            trainId = "T1",
            tick = 1L,
            errorType = "test",
            message = "failure",
            stackTrace = "stack",
            initialTrain = null,
            currentTrain = null,
            route = null,
            perception = null,
            activeProviderId = null,
            constraints = emptyMap()
        )

        assertTrue(first.stall(report))
        assertTrue(first.isStalled)
        assertFalse(second.isStalled)
    }
}
