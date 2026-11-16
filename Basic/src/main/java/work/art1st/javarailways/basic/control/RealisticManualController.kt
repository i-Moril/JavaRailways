package work.art1st.javarailways.basic.control

import work.art1st.javarailways.basic.api.ato.DynamicsProfileSource
import work.art1st.javarailways.basic.api.ato.CarDynamicsProfile
import work.art1st.javarailways.basic.api.ato.TrainDynamicsProfile
import work.art1st.javarailways.basic.api.ato.VehicleDeviceState
import work.art1st.javarailways.basic.basicWorldData
import work.art1st.javarailways.basic.api.ato.VehicleControlCommand
import work.art1st.javarailways.core.api.control.TrainController
import work.art1st.javarailways.core.api.control.PreparedControllerStateUpdate
import work.art1st.javarailways.core.api.physics.train.Car
import work.art1st.javarailways.core.api.physics.train.Train
import work.art1st.javarailways.core.physics.train.CoreTrain
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference

enum class TrainDriveDirection(val label: String) {
    FORWARD("F"),
    NEUTRAL("N"),
    REVERSE("R")
}

/** Platform-neutral manual handle controller; Bukkit owns only input and lifecycle wiring. */
class RealisticManualController(
    train: Train,
    controlProfile: TrainDynamicsProfile,
    carProfiles: List<CarDynamicsProfile>,
    gravityMetersPerSecondSquared: Double,
    initialSpeedMetersPerSecond: Double = train.speed,
    profileResolver: ((Car) -> CarDynamicsProfile?)? = null,
    initialDeviceSource: DynamicsProfileSource? = null
) : TrainController(train), DynamicsProfileSource {
    private val dynamics = TrainDynamicsExecutor(
        train,
        controlProfile,
        carProfiles,
        gravityMetersPerSecondSquared,
        initialSpeedMetersPerSecond,
        profileResolver,
        initialDeviceSource
    )
    private val controlProfile: TrainDynamicsProfile get() = dynamics.trainProfile
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

    override fun dynamicsProfileFor(car: Car): CarDynamicsProfile? = dynamics.dynamicsProfileFor(car)
    override fun dynamicsDeviceStateFor(car: Car): VehicleDeviceState? = dynamics.dynamicsDeviceStateFor(car)

    override fun prepareConsistChange(cars: List<Car>, counterpart: TrainController?): List<PreparedControllerStateUpdate> =
        listOf(dynamics.prepareConsistChange(cars, counterpart as? DynamicsProfileSource).update)

    fun prepareConfigurationChange(
        trainProfile: TrainDynamicsProfile,
        carProfiles: List<CarDynamicsProfile>
    ): List<PreparedControllerStateUpdate> {
        require(handle == NEUTRAL_HANDLE || (trainProfile.powerHandles + trainProfile.brakeHandles)
            .any { it.equals(handle, ignoreCase = true) }) {
            "Updated configuration does not contain active manual handle '$handle'"
        }
        return dynamics.prepareConfigurationChange(trainProfile, carProfiles)
    }

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

    fun setDirection(direction: TrainDriveDirection): CompletableFuture<Unit> {
        val controlled = controlledTrain as? CoreTrain
            ?: return CompletableFuture.failedFuture(IllegalStateException("Manual direction requires a Core train"))
        return controlled.world.physicsCoordinator.submitBetweenFrames { applyDirection(direction) }
    }

    /** Atomic owner-side counterpart for an already admitted manual-control takeover. */
    fun applyDirection(direction: TrainDriveDirection) {
        val controlled = controlledTrain as CoreTrain
        controlled.world.physicsCoordinator.ensureBetweenFrames("RealisticManualController.applyDirection")
        check(controlled.world.getTrain(controlled.id) === controlled && controlled.controller === this) {
            "Manual controller no longer owns train '${controlled.id}'"
        }
        when (direction) {
            TrainDriveDirection.FORWARD, TrainDriveDirection.REVERSE -> {
                if (runningDirectionRef.get() != direction) controlled.reverse()
                runningDirectionRef.set(direction)
                movementSign = if (direction == TrainDriveDirection.FORWARD) 1.0 else -1.0
            }
            TrainDriveDirection.NEUTRAL -> Unit
        }
        directionRef.set(direction)
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

    val isAtpAcknowledgementRequired: Boolean
        get() = controlledTrain.world.basicWorldData.commandSupervisor?.isAcknowledgementRequired(controlledTrain) ?: false

    fun acknowledgeAtp(): CompletableFuture<Boolean> {
        val controlled = controlledTrain as? CoreTrain ?: return CompletableFuture.completedFuture(false)
        return controlled.world.physicsCoordinator.submitBetweenFrames {
            check(controlled.world.getTrain(controlled.id) === controlled && controlled.controller === this)
            controlled.world.basicWorldData.commandSupervisor?.acknowledge(controlled) ?: false
        }
    }

    override fun tick(deltaT: Double) {
        require(deltaT.isFinite())
        val deltaSeconds = deltaT.coerceAtLeast(0.0) * 0.001
        if (deltaSeconds == 0.0) return
        if (controlledTrain.world.basicWorldData.safetyInterlock.isEngaged) emergencyBrake()
        val currentHandle = if (
            directionRef.get() == TrainDriveDirection.NEUTRAL &&
            controlProfile.normalizedPowerScale(handleRef.get()) > 0.0
        ) {
            NEUTRAL_HANDLE
        } else {
            handleRef.get()
        }
        val proposed = TrainDynamics.commandForHandle(controlProfile, currentHandle)
        val command = if (controlledTrain.world.basicWorldData.safetyInterlock.isEngaged) VehicleControlCommand.EMERGENCY_BRAKE
            else controlledTrain.world.basicWorldData.commandSupervisor?.supervise(controlledTrain,
                dynamics.controlState(activeSpeed), dynamics.controlCapabilities(activeSpeed), proposed, deltaSeconds) ?: proposed
        val result = dynamics.step(activeSpeed, command, deltaSeconds)
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
