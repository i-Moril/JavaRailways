package work.art1st.javarailways.basic.control

import work.art1st.javarailways.basic.basicWorldData
import work.art1st.javarailways.core.api.control.TrainController
import work.art1st.javarailways.core.api.control.PreparedControllerStateUpdate
import work.art1st.javarailways.core.api.physics.train.Car
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.physics.train.Train
import java.util.concurrent.atomic.AtomicReference

enum class TrainDriveDirection(val label: String) {
    FORWARD("F"),
    NEUTRAL("N"),
    REVERSE("R")
}

/** Platform-neutral manual handle controller; Bukkit owns only input and lifecycle wiring. */
class RealisticManualController(
    train: Train,
    world: World,
    private val controlProfile: TrainDynamics.TrainProfile,
    carProfiles: List<TrainDynamics.CarProfile>,
    gravityMetersPerSecondSquared: Double,
    initialSpeedMetersPerSecond: Double = train.speed,
    profileResolver: ((Car) -> TrainDynamics.CarProfile?)? = null
) : TrainController(train, world), DynamicsProfileSource {
    private val dynamics = TrainDynamicsExecutor(
        train,
        controlProfile,
        carProfiles,
        gravityMetersPerSecondSquared,
        initialSpeedMetersPerSecond,
        profileResolver
    )
    private val handleRef = AtomicReference(NEUTRAL_HANDLE)
    private val directionRef = AtomicReference(TrainDriveDirection.NEUTRAL)
    private val runningDirectionRef = AtomicReference(TrainDriveDirection.FORWARD)

    @Volatile
    private var movementSign: Double = 1.0

    init {
        speed = initialSpeedMetersPerSecond
    }

    val handle: String get() = handleRef.get()
    val direction: TrainDriveDirection get() = directionRef.get()
    val displaySpeed: Double get() = dynamics.state.speed * movementSign
    val acceleration: Double get() = dynamics.state.acceleration
    val brakeScale: Double get() = dynamics.state.brakeScale
    val tractionForce: Double get() = dynamics.lastResult.tractionForce
    val brakeForce: Double get() = dynamics.lastResult.brakeForce
    val resistanceForce: Double get() = dynamics.lastResult.resistanceForce
    val gradeForce: Double get() = dynamics.lastResult.gradeForce
    val controlledTrain: Train get() = train

    override fun dynamicsProfileFor(car: Car): TrainDynamics.CarProfile? = dynamics.dynamicsProfileFor(car)

    override fun prepareConsistChange(cars: List<Car>, counterpart: TrainController?): List<PreparedControllerStateUpdate> =
        listOf(dynamics.prepareConsistChange(cars, counterpart as? DynamicsProfileSource).update)

    fun setHandle(value: String): Boolean {
        val normalized = value.uppercase()
        val known = normalized == NEUTRAL_HANDLE ||
            controlProfile.powerHandles.any { it.equals(normalized, ignoreCase = true) } ||
            controlProfile.brakeHandles.any { it.equals(normalized, ignoreCase = true) }
        if (!known) return false
        handleRef.set(normalized)
        return true
    }

    fun shiftHandleTowardPower(): String =
        handleRef.updateAndGet { current -> shiftedHandle(current, +1) }

    fun shiftHandleTowardBrake(): String =
        handleRef.updateAndGet { current -> shiftedHandle(current, -1) }

    fun markCurrentPhysicalDirectionAsForward() {
        runningDirectionRef.set(TrainDriveDirection.FORWARD)
        if (directionRef.get() != TrainDriveDirection.REVERSE) movementSign = 1.0
    }

    fun setDirection(direction: TrainDriveDirection) {
        directionRef.set(direction)
        when (direction) {
            TrainDriveDirection.FORWARD, TrainDriveDirection.REVERSE -> {
                val previousRunningDirection = runningDirectionRef.getAndSet(direction)
                if (previousRunningDirection != direction) reverse()
                movementSign = if (direction == TrainDriveDirection.FORWARD) 1.0 else -1.0
            }
            TrainDriveDirection.NEUTRAL -> Unit
        }
    }

    fun emergencyBrake() {
        handleRef.set(
            controlProfile.brakeHandles.firstOrNull { it.equals(EMERGENCY_BRAKE_HANDLE, true) }
                ?: controlProfile.brakeHandles.maxByOrNull(controlProfile::normalizedBrakeScale)
                ?: NEUTRAL_HANDLE
        )
    }

    fun coast() {
        handleRef.set(NEUTRAL_HANDLE)
    }

    override fun tick(deltaT: Double) {
        if (controlledTrain.world.basicWorldData.safetyInterlock.isEngaged) emergencyBrake()
        val deltaSeconds = deltaT.coerceAtLeast(0.0) * 0.001
        val currentHandle = if (
            directionRef.get() == TrainDriveDirection.NEUTRAL &&
            controlProfile.normalizedPowerScale(handleRef.get()) > 0.0
        ) {
            NEUTRAL_HANDLE
        } else {
            handleRef.get()
        }
        val result = dynamics.step(activeSpeed, currentHandle, deltaSeconds)
        speed = result.state.speed
        moveControlledTrain(result.distance, result.state.speed)
    }

    private fun shiftedHandle(current: String, delta: Int): String {
        val order = controlProfile.brakeHandles.asReversed() +
            NEUTRAL_HANDLE + controlProfile.powerHandles
        val index = order.indexOfFirst { it.equals(current, ignoreCase = true) }
            .takeIf { it >= 0 } ?: order.indexOf(NEUTRAL_HANDLE)
        return order[(index + delta).coerceIn(0, order.lastIndex)]
    }

    companion object {
        const val NEUTRAL_HANDLE = "N"
        private const val EMERGENCY_BRAKE_HANDLE = "EB"
    }
}
