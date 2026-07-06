package work.art1st.mineRails.bukkit.control

import work.art1st.mineRails.bukkit.config.model.CarControlConfig
import work.art1st.mineRails.bukkit.config.model.TrainControlConfig
import work.art1st.mineRails.core.api.control.TrainController
import work.art1st.mineRails.core.api.physics.World
import work.art1st.mineRails.core.api.physics.train.Car
import work.art1st.mineRails.core.api.physics.train.Train

class RealisticManualController(
    train: Train,
    world: World,
    private val controlConfig: TrainControlConfig,
    private val carControls: List<CarControlConfig>
) : TrainController(train, world) {
    private var dynamicsState = TrainDynamics.State()
    private var lastForces = TrainDynamics.StepResult(dynamicsState, 0.0, 0.0, 0.0, 0.0, 0.0)

    var handle: String = NEUTRAL_HANDLE
        private set

    /** Compatibility view for the older /mr train throttle command. */
    val throttle: Double
        get() = when {
            controlConfig.tractionNotchScales.containsKey(handle) -> controlConfig.normalizedPowerScale(handle)
            controlConfig.brakeNotchScales.containsKey(handle) -> -controlConfig.normalizedBrakeScale(handle)
            else -> 0.0
        }

    /** Compatibility view: realistic control has no target speed, only a speed limit. */
    val targetSpeed: Double
        get() = if (controlConfig.tractionNotchScales.containsKey(handle)) controlConfig.maxSpeed else 0.0

    val acceleration: Double get() = dynamicsState.acceleration
    val brakeScale: Double get() = dynamicsState.brakeScale
    val tractionForce: Double get() = lastForces.tractionForce
    val brakeForce: Double get() = lastForces.brakeForce
    val resistanceForce: Double get() = lastForces.resistanceForce
    val gradeForce: Double get() = lastForces.gradeForce
    val controlledTrain: Train get() = train

    fun setHandle(value: String): Boolean {
        val normalized = value.uppercase()
        val known = normalized == NEUTRAL_HANDLE ||
            controlConfig.tractionNotchScales.containsKey(normalized) ||
            controlConfig.brakeNotchScales.containsKey(normalized)
        if (!known) return false
        handle = normalized
        return true
    }

    fun setThrottle(value: Double) {
        val clamped = value.coerceIn(-1.0, 1.0)
        handle = when {
            clamped > 0.0 -> nearestNotch(controlConfig.tractionNotchScales, clamped) ?: NEUTRAL_HANDLE
            clamped < 0.0 -> nearestNotch(controlConfig.brakeNotchScales, -clamped) ?: NEUTRAL_HANDLE
            else -> NEUTRAL_HANDLE
        }
    }

    fun emergencyBrake() {
        handle = if (controlConfig.brakeNotchScales.containsKey("EB")) "EB" else {
            controlConfig.brakeNotchScales.maxByOrNull { it.value }?.key ?: NEUTRAL_HANDLE
        }
    }

    fun coast() {
        handle = NEUTRAL_HANDLE
    }

    override fun tick(deltaT: Double) {
        val deltaSeconds = deltaT.coerceAtLeast(0.0) * 0.001
        val cars = buildCarInputs(train.cars)
        lastForces = TrainDynamics.step(
            state = dynamicsState.copy(speed = speed),
            config = controlConfig,
            cars = cars,
            handle = handle,
            deltaSeconds = deltaSeconds
        )
        dynamicsState = lastForces.state
        speed = dynamicsState.speed
        train.moveTrainForward(lastForces.distance)
    }

    private fun buildCarInputs(cars: List<Car>): List<TrainDynamics.CarInput> {
        return cars.mapIndexed { index, car ->
            val control = carControls.getOrNull(index) ?: CarControlConfig()
            TrainDynamics.CarInput(
                control = control,
                grade = car.direction.y()
            )
        }
    }

    private fun nearestNotch(scales: Map<String, Double>, target: Double): String? {
        return scales.minByOrNull { (_, scale) -> kotlin.math.abs(scale - target) }?.key
    }

    companion object {
        const val NEUTRAL_HANDLE = "N"
    }
}
