package work.art1st.javarailways.bukkit.config.model

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class AtoReferenceConfigTest {
    private fun source() = mapOf<String, Any>(
        "config-version" to 1, "max-speed" to 30.0,
        "controller" to mapOf("power-notches" to listOf("P1"), "brake-notches" to listOf("B1", "EB")),
        "traction" to mapOf("notch-scales" to mapOf("P1" to 1.0)),
        "brake" to mapOf("notch-scales" to mapOf("B1" to 1.0, "EB" to 1.25), "apply-time" to 1.0, "release-time" to 1.0),
        "resistance" to mapOf("a" to 1.0, "b" to 1.0, "c" to 1.0),
        "adhesion" to mapOf("coefficient" to .2), "jerk" to mapOf("traction" to 1.0, "brake" to 1.0))
    @Test fun `existing current control files have explicit reference defaults without a version change`() {
        val config = TrainControlConfig.parse(source())
        assertEquals(1.0, config.nominalOperatingAcceleration)
        assertEquals(1.0, config.nominalOperatingDeceleration)
    }
    @Test fun `reference settings affect the nominal profile without changing physical brake equipment`() {
        val config = TrainControlConfig.parse(source() + ("ato" to mapOf("operating-acceleration" to .8, "operating-deceleration" to .7)))
        assertEquals(.8, config.nominalOperatingAcceleration)
        assertEquals(.7, config.nominalOperatingDeceleration)
        assertEquals(1.0, config.brakeApplyTime)
        assertEquals(1.25, config.normalizedBrakeScale("EB"))
    }
    @Test fun `custom force curve peaks bound traction even between performance sample bins`() {
        val motor = MotorControlConfig(enabled = true, maxForce = 100.0, maxPower = 10.0,
            forceCurve = listOf(SpeedForcePoint(0.0, 20.0), SpeedForcePoint(.123, 500.0), SpeedForcePoint(.25, 30.0)))
        val car = CarControlConfig(motor = motor)
        assertEquals(500.0, car.maximumMotorForceUpperBound)
        assertEquals(500.0, car.motorForceAt(.123))
        assertEquals(0.0, car.copy(motor = motor.copy(enabled = false)).maximumMotorForceUpperBound)
    }
    @Test fun `invalid reference rates and misspelled fields are rejected`() {
        for (value in listOf(0.0, -1.0, Double.NaN)) assertThrows(IllegalArgumentException::class.java) {
            TrainControlConfig.parse(source() + ("ato" to mapOf("operating-deceleration" to value)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TrainControlConfig.parse(source() + ("ato" to mapOf("operating-deceleraton" to .7)))
        }
    }
}
