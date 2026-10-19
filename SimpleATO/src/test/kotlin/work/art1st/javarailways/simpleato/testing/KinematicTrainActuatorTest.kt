package work.art1st.javarailways.simpleato.testing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import work.art1st.javarailways.basic.api.ato.BrakingGradeSegment
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.ato.ControlDemand
import work.art1st.javarailways.simpleato.api.ato.AtoSafetyProfile

class KinematicTrainActuatorTest {
    @Test
    fun `kinematic actuator respects train maximum speed and freezes`() {
        val profile = AtoSafetyProfile(reactionTimeSeconds = 0.0, stopToleranceMeters = 0.0)
        val actuator = KinematicTrainActuator(10.0, profile)
        val step = actuator.step(0.0, ControlDemand(20.0), 1.0)

        assertEquals(1.0, step.speedMetersPerSecond, 1.0e-9)
        assertEquals(0.5, step.distanceMeters, 1.0e-9)
        assertEquals(
            0.0,
            actuator.step(0.051605736934817736, ControlDemand(0.0), 0.1).speedMetersPerSecond
        )
        actuator.freeze()
    }
    @Test
    fun `braking capability requires a nonempty route`() {
        val actuator = KinematicTrainActuator(10.0, AtoSafetyProfile())
        assertThrows(IllegalArgumentException::class.java) { actuator.brakingEnvelope(emptyList()) }
        val envelope = actuator.brakingEnvelope(listOf(BrakingGradeSegment(100.0, 0.0)))
        assertEquals(100.0, envelope.totalDistanceMeters)
    }

}
