package work.art1st.mineRails.bukkit.control

import work.art1st.mineRails.bukkit.config.model.CarControlConfig
import work.art1st.mineRails.bukkit.config.model.TrainControlConfig
import work.art1st.mineRails.core.api.control.TrainController
import work.art1st.mineRails.core.api.physics.World
import work.art1st.mineRails.core.api.physics.train.Car
import work.art1st.mineRails.core.api.physics.train.Train
import java.util.concurrent.atomic.AtomicReference

enum class TrainDriveDirection(val label: String) {
    FORWARD("F"),
    NEUTRAL("N"),
    REVERSE("R")
}

class RealisticManualController(
    train: Train,
    world: World,
    private val controlConfig: TrainControlConfig,
    private val carControls: List<CarControlConfig>
) : TrainController(train, world) {
    @Volatile
    private var dynamicsState = TrainDynamics.State()
    @Volatile
    private var lastForces = TrainDynamics.StepResult(dynamicsState, 0.0, 0.0, 0.0, 0.0, 0.0)
    private val handleRef = AtomicReference(NEUTRAL_HANDLE)
    private val directionRef = AtomicReference(TrainDriveDirection.NEUTRAL)

    val handle: String
        get() = handleRef.get()

    val direction: TrainDriveDirection
        get() = directionRef.get()

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

    val displaySpeed: Double get() = dynamicsState.speed
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
        handleRef.set(normalized)
        return true
    }

    fun setThrottle(value: Double) {
        val clamped = value.coerceIn(-1.0, 1.0)
        handleRef.set(when {
            clamped > 0.0 -> nearestNotch(controlConfig.tractionNotchScales, clamped) ?: NEUTRAL_HANDLE
            clamped < 0.0 -> nearestNotch(controlConfig.brakeNotchScales, -clamped) ?: NEUTRAL_HANDLE
            else -> NEUTRAL_HANDLE
        })
    }

    fun shiftHandleTowardPower(): String {
        return handleRef.updateAndGet { current -> shiftedHandle(current, +1) }
    }

    fun shiftHandleTowardBrake(): String {
        return handleRef.updateAndGet { current -> shiftedHandle(current, -1) }
    }

    fun setDirection(direction: TrainDriveDirection) {
        directionRef.set(direction)
    }

    fun emergencyBrake() {
        handleRef.set(if (controlConfig.brakeNotchScales.containsKey("EB")) "EB" else {
            controlConfig.brakeNotchScales.maxByOrNull { it.value }?.key ?: NEUTRAL_HANDLE
        })
    }

    fun coast() {
        handleRef.set(NEUTRAL_HANDLE)
    }

    override fun tick(deltaT: Double) {
        val deltaSeconds = deltaT.coerceAtLeast(0.0) * 0.001
        val currentDirection = directionRef.get()
        val currentHandle = if (currentDirection == TrainDriveDirection.NEUTRAL && controlConfig.tractionNotchScales.containsKey(handleRef.get())) {
            NEUTRAL_HANDLE
        } else {
            handleRef.get()
        }
        val cars = buildCarInputs(train.cars)
        val result = TrainDynamics.step(
            state = dynamicsState.copy(speed = speed),
            config = controlConfig,
            cars = cars,
            handle = currentHandle,
            deltaSeconds = deltaSeconds
        )
        lastForces = result
        dynamicsState = result.state
        speed = result.state.speed
        train.moveTrainForward(result.distance)
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

    private fun shiftedHandle(current: String, delta: Int): String {
        val order = controlConfig.brakeNotches.asReversed() + NEUTRAL_HANDLE + controlConfig.powerNotches
        val normalized = current.uppercase()
        val index = order.indexOf(normalized).takeIf { it >= 0 } ?: order.indexOf(NEUTRAL_HANDLE)
        return order[(index + delta).coerceIn(0, order.lastIndex)]
    }

    companion object {
        const val NEUTRAL_HANDLE = "N"
    }
}
