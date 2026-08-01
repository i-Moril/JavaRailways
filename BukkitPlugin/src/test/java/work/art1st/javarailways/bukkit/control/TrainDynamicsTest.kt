package work.art1st.javarailways.bukkit.control

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.bukkit.config.model.CarBrakeControlConfig
import work.art1st.javarailways.bukkit.config.model.CarControlConfig
import work.art1st.javarailways.bukkit.config.model.DavisResistanceConfig
import work.art1st.javarailways.bukkit.config.model.JerkLimitConfig
import work.art1st.javarailways.bukkit.config.model.MotorControlConfig
import work.art1st.javarailways.bukkit.config.model.TrainControlConfig

class TrainDynamicsTest {
    private fun immediateResponseConfig(): TrainControlConfig = TrainControlConfig(
        maxSpeed = 40.0,
        resistance = DavisResistanceConfig(a = 0.0, b = 0.0, c = 0.0),
        jerk = JerkLimitConfig(traction = 100.0, brake = 100.0)
    )

    @Test
    fun `additional trailer mass lowers acceleration`() {
        val config = immediateResponseConfig()
        val motor = TrainDynamics.CarInput(
            CarControlConfig(
                mass = 40_000.0,
                motor = MotorControlConfig(enabled = true, maxForce = 80_000.0),
                brake = CarBrakeControlConfig(maxForce = 50_000.0)
            )
        )
        val trailer = TrainDynamics.CarInput(
            CarControlConfig(
                mass = 35_000.0,
                motor = MotorControlConfig(enabled = false),
                brake = CarBrakeControlConfig(maxForce = 45_000.0)
            )
        )

        val motorOnly = TrainDynamics.step(TrainDynamics.State(), config, listOf(motor), "P5", 1.0)
        val withTrailer = TrainDynamics.step(TrainDynamics.State(), config, listOf(motor, trailer), "P5", 1.0)

        assertTrue(motorOnly.state.acceleration > withTrailer.state.acceleration)
    }

    @Test
    fun `additional motor car raises acceleration despite extra mass`() {
        val config = immediateResponseConfig()
        val motor = TrainDynamics.CarInput(
            CarControlConfig(
                mass = 40_000.0,
                motor = MotorControlConfig(enabled = true, maxForce = 80_000.0),
                brake = CarBrakeControlConfig(maxForce = 50_000.0)
            )
        )
        val trailer = TrainDynamics.CarInput(CarControlConfig(mass = 35_000.0))

        val oneMotorOneTrailer = TrainDynamics.step(TrainDynamics.State(), config, listOf(motor, trailer), "P5", 1.0)
        val twoMotorsOneTrailer = TrainDynamics.step(TrainDynamics.State(), config, listOf(motor, motor, trailer), "P5", 1.0)

        assertTrue(twoMotorsOneTrailer.state.acceleration > oneMotorOneTrailer.state.acceleration)
    }

    @Test
    fun `brake force builds over apply time`() {
        val config = TrainControlConfig()
        val car = TrainDynamics.CarInput(CarControlConfig())

        val first = TrainDynamics.step(TrainDynamics.State(speed = 10.0), config, listOf(car), "B7", 0.1)
        val later = TrainDynamics.step(first.state, config, listOf(car), "B7", 1.0)

        assertTrue(first.state.brakeScale in 0.0..1.0)
        assertTrue(later.state.brakeScale > first.state.brakeScale)
        assertTrue(later.brakeForce > first.brakeForce)
    }
}
