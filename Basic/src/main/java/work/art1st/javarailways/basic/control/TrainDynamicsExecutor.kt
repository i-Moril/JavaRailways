package work.art1st.javarailways.basic.control

import work.art1st.javarailways.core.api.control.ControllerConsistState
import work.art1st.javarailways.core.api.control.PreparedControllerStateUpdate
import work.art1st.javarailways.core.api.physics.train.Car
import work.art1st.javarailways.core.api.physics.train.Train
import java.util.IdentityHashMap
import java.lang.ref.WeakReference
import work.art1st.javarailways.basic.api.ato.*
import work.art1st.javarailways.basic.basicWorldDataOrNull

/** Owns committed equipment state. Speculative banks never escape the executor. */
class TrainDynamicsExecutor(
    private val train: Train,
    trainProfile: TrainDynamics.TrainProfile,
    carProfiles: List<TrainDynamics.CarProfile>,
    val gravityMetersPerSecondSquared: Double,
    initialSpeedMetersPerSecond: Double = train.speed,
    private val profileResolver: ((Car) -> TrainDynamics.CarProfile?)? = null,
    initialDeviceSource: DynamicsProfileSource? = null
) : DynamicsProfileSource {
    private class Published(
        val state: TrainDynamics.State,
        val result: TrainDynamics.StepResult,
        val cars: List<Car>,
        val command: VehicleControlCommand = VehicleControlCommand.COAST
    ) {
        val indices = IdentityHashMap<Car, Int>().also { indices ->
            cars.forEachIndexed { index, car -> indices[car] = index }
        }
    }

    private class Binding(
        val profile: TrainDynamics.TrainProfile,
        val cars: List<Car>,
        val profiles: Map<Car, TrainDynamics.CarProfile>,
        initial: Published
    ) {
        @Volatile
        var published = initial
    }

    private val history = train.world.basicWorldDataOrNull?.trainDynamicsHistory
    private var knownHistorySnapshot: WorldTrainDynamicsHistory.Snapshot? = null
    private val current: ControllerConsistState<Binding>
    val trainProfile: TrainDynamics.TrainProfile get() = current.value.profile
    val carProfiles: List<TrainDynamics.CarProfile>
        get() = current.value.let { binding ->
            train.cars.map { car ->
                checkNotNull(binding.profiles[car]) {
                    "car '${car.id}' has no dynamics profile for this exact instance"
                }
            }
        }
    val state: TrainDynamics.State get() = current.value.published.state
    val lastResult: TrainDynamics.StepResult get() = current.value.published.result
    val lastCommand: VehicleControlCommand get() = current.value.published.command

    init {
        require(gravityMetersPerSecondSquared.isFinite() && gravityMetersPerSecondSquared > 0.0)
        require(initialSpeedMetersPerSecond.isFinite() && initialSpeedMetersPerSecond >= 0.0)
        val cars = train.cars.toList()
        require(carProfiles.size == cars.size) { "car profile count does not match train car count" }
        val profiles = IdentityHashMap<Car, TrainDynamics.CarProfile>()
        cars.forEachIndexed { index, car -> profiles[car] = carProfiles[index] }
        val seed = TrainDynamics.initialState(carProfiles, initialSpeedMetersPerSecond)
        // Structure preparation may already have installed incoming cars before the old owner
        // performs another step. Its exact-instance device binding is fresher than a history entry.
        val source = initialDeviceSource
        val retainedDevices = cars.mapIndexed { index, car -> source?.dynamicsDeviceStateFor(car) ?: history?.deviceForCar(car) ?: seed.devices[index] }
        val initial = seed.copy(devices = TrainDynamics.DeviceSnapshot.of(retainedDevices),
            brakeScale = retainedDevices.indices.sumOf {
                retainedDevices[it].pressureKpa / carProfiles[it].equipment.nominalCylinderPressureKpa
            } / retainedDevices.size)
        val inherited = history?.latest(train)
        val state = inherited?.let { adaptedHistory(it, initialSpeedMetersPerSecond, cars, carProfiles, initial) } ?: initial
        val command = if (state !== initial) inherited!!.lastCommand else VehicleControlCommand.COAST
        val result = TrainDynamics.StepResult(state, 0.0, 0.0, 0.0, 0.0, 0.0)
        current = ControllerConsistState(Binding(trainProfile, cars, profiles, Published(state, result, cars, command)))
        knownHistorySnapshot = inherited
    }

    override fun dynamicsProfileFor(car: Car): TrainDynamics.CarProfile? = current.value.profiles[car]

    override fun dynamicsDeviceStateFor(car: Car): VehicleDeviceState? {
        val published = current.value.published
        return published.indices[car]?.let { published.state.devices[it] }
    }

    internal fun prepareConsistChange(cars: List<Car>, source: DynamicsProfileSource?): PreparedConsistDynamics {
        val previous = current.value
        val profiles = IdentityHashMap<Car, TrainDynamics.CarProfile>()
        val devices = cars.map { car ->
            profiles[car] = dynamicsProfileFor(car) ?: source?.dynamicsProfileFor(car) ?: profileResolver?.invoke(car)
                ?: error("Train ${train.id} car '${car.id}' has no dynamics profile for this instance")
            dynamicsDeviceStateFor(car) ?: source?.dynamicsDeviceStateFor(car) ?: VehicleDeviceState(
                pressureKpa = if (previous.published.state.speed == 0.0) {
                    profiles.getValue(car).equipment.nominalCylinderPressureKpa
                } else 0.0
            )
        }
        val state = previous.published.state.copy(devices = TrainDynamics.DeviceSnapshot.of(devices))
        val capturedCars = cars.toList()
        val published = Published(state, previous.published.result, capturedCars, previous.published.command)
        val binding = Binding(previous.profile, capturedCars, profiles, published)
        return PreparedConsistDynamics(cars.map { profiles.getValue(it) }, current.prepare(binding))
    }

    fun prepareConfigurationChange(
        trainProfile: TrainDynamics.TrainProfile,
        carProfiles: List<TrainDynamics.CarProfile>
    ): List<PreparedControllerStateUpdate> {
        val previous = current.value
        val cars = train.cars.toList()
        require(carProfiles.size == cars.size) { "car profile count does not match current consist" }
        TrainDynamics.totalEffectiveMass(carProfiles)
        val profiles = IdentityHashMap<Car, TrainDynamics.CarProfile>()
        cars.forEachIndexed { index, car -> profiles[car] = carProfiles[index] }
        return listOf(current.prepare(Binding(trainProfile, previous.cars, profiles, previous.published)))
    }

    private data class Capture(
        val cars: List<Car>,
        val state: TrainDynamics.State,
        val inputs: List<TrainDynamics.CarInput>
    )

    private fun capture(binding: Binding, speed: Double): Capture {
        val cars = train.cars.toList()
        val published = binding.published
        val inputs = cars.map { car ->
            val profile = checkNotNull(binding.profiles[car]) {
                "car '${car.id}' has no dynamics profile for this exact instance"
            }
            TrainDynamics.CarInput(profile, car.operatingDirection.y())
        }
        val same = cars.size == published.cars.size && cars.indices.all { cars[it] === published.cars[it] }
        val state = if (same) published.state.copy(speed = speed) else published.state.copy(
            speed = speed,
            devices = TrainDynamics.DeviceSnapshot.of(cars.map { car ->
                published.state.devices[checkNotNull(published.indices[car])]
            })
        )
        return Capture(cars, state, inputs)
    }

    fun preview(currentSpeedMetersPerSecond: Double, handle: String, deltaTimeSeconds: Double): TrainDynamics.StepResult {
        val binding = current.value
        val capture = capture(binding, currentSpeedMetersPerSecond)
        return TrainDynamics.step(capture.state, binding.profile, capture.inputs, handle,
            gravityMetersPerSecondSquared, deltaTimeSeconds)
    }

    fun step(currentSpeedMetersPerSecond: Double, handle: String, deltaTimeSeconds: Double): TrainDynamics.StepResult =
        step(currentSpeedMetersPerSecond, TrainDynamics.commandForHandle(trainProfile, handle), deltaTimeSeconds)

    fun preview(currentSpeedMetersPerSecond: Double, command: VehicleControlCommand, deltaTimeSeconds: Double): TrainDynamics.StepResult {
        val binding = current.value
        val capture = capture(binding, currentSpeedMetersPerSecond)
        return TrainDynamics.step(capture.state, binding.profile, capture.inputs, command,
            gravityMetersPerSecondSquared, deltaTimeSeconds)
    }

    fun step(currentSpeedMetersPerSecond: Double, command: VehicleControlCommand, deltaTimeSeconds: Double): TrainDynamics.StepResult {
        require(currentSpeedMetersPerSecond.isFinite() && currentSpeedMetersPerSecond >= 0.0)
        require(deltaTimeSeconds.isFinite() && deltaTimeSeconds >= 0.0)
        synchronizePhysicalHistory(currentSpeedMetersPerSecond)
        val binding = current.value
        val capture = capture(binding, currentSpeedMetersPerSecond)
        val result = TrainDynamics.step(capture.state, binding.profile, capture.inputs, command,
            gravityMetersPerSecondSquared, deltaTimeSeconds)
        binding.published = Published(result.state, result, capture.cars, command)
        val snapshot = WorldTrainDynamicsHistory.Snapshot(result.state, command, capture.cars.map(::WeakReference))
        history?.record(train, snapshot)
        knownHistorySnapshot = snapshot
        return result
    }

    private fun adaptedHistory(snapshot: WorldTrainDynamicsHistory.Snapshot, speed: Double, cars: List<Car>,
        profiles: List<TrainDynamics.CarProfile>, fallback: TrainDynamics.State): TrainDynamics.State? {
        val stopped = speed == 0.0 && train.publishedFrame?.speedMetersPerSecond == 0.0
        if (!stopped && kotlin.math.abs(snapshot.state.speed - speed) > 1e-9) return null
        val indices = IdentityHashMap<Car, Int>()
        snapshot.cars.forEachIndexed { index, ref -> ref.get()?.let { indices[it] = index } }
        val devices = cars.mapIndexed { index, car ->
            indices[car]?.takeIf { it < snapshot.state.devices.size }?.let { snapshot.state.devices[it] }
                ?: fallback.devices.takeIf { it.size == cars.size }?.get(index)
                ?: return null
        }
        val brake = devices.indices.sumOf { devices[it].pressureKpa / profiles[it].equipment.nominalCylinderPressureKpa } / devices.size
        return snapshot.state.copy(speed = speed, acceleration = if (stopped) 0.0 else snapshot.state.acceleration,
            brakeScale = brake, devices = TrainDynamics.DeviceSnapshot.of(devices))
    }

    private fun synchronizePhysicalHistory(speed: Double) {
        val latest = history?.latest(train) ?: return
        val binding = current.value
        if (latest === knownHistorySnapshot && binding.published.state.speed == speed) return
        val captured = capture(binding, speed)
        val adapted = adaptedHistory(latest, speed, captured.cars, captured.inputs.map { it.control }, captured.state) ?: return
        binding.published = Published(adapted, binding.published.result, captured.cars, latest.lastCommand)
        knownHistorySnapshot = latest
    }

    fun controlState(speed: Double): VehicleControlState {
        require(speed.isFinite() && speed >= 0.0)
        synchronizePhysicalHistory(speed)
        val captured = capture(current.value, speed)
        return VehicleControlState(speed, captured.state.acceleration, captured.state.brakeScale, lastCommand,
            java.util.List.copyOf(captured.state.devices.toList()))
    }

    private var capabilityModel: VehicleControlCapabilityModel? = null
    private var capabilityProfile: TrainDynamics.TrainProfile? = null
    private var capabilityCars: List<Car> = emptyList()
    private var capabilityProfiles: List<TrainDynamics.CarProfile> = emptyList()

    fun controlCapabilities(speed: Double): VehicleControlCapabilities {
        val binding = current.value
        val captured = capture(binding, speed)
        val profiles = captured.inputs.map { it.control }
        if (capabilityProfile !== binding.profile || capabilityCars.size != captured.cars.size ||
            captured.cars.indices.any { captured.cars[it] !== capabilityCars[it] || profiles[it] !== capabilityProfiles[it] }) {
            capabilityModel = VehicleControlCapabilityModel(binding.profile, profiles, gravityMetersPerSecondSquared)
            capabilityProfile = binding.profile
            capabilityCars = captured.cars
            capabilityProfiles = profiles
        }
        return checkNotNull(capabilityModel).snapshot(speed, captured.inputs)
    }

}
