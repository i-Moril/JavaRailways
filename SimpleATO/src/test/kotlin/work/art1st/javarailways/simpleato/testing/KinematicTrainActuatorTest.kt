package work.art1st.javarailways.simpleato.testing

import org.junit.jupiter.api.Assertions.assertEquals
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
        assertEquals(0.0, actuator.freeze().speedMetersPerSecond)
    }
}
