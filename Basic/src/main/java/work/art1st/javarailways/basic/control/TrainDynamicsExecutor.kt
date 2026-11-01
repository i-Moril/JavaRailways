package work.art1st.javarailways.basic.control

import work.art1st.javarailways.core.api.control.ControllerConsistState
import work.art1st.javarailways.core.api.control.PreparedControllerStateUpdate
import work.art1st.javarailways.core.api.physics.train.Car
import work.art1st.javarailways.core.api.physics.train.Train
import java.util.IdentityHashMap

/** Owns committed equipment state. Speculative banks never escape the executor. */
class TrainDynamicsExecutor(
    private val train: Train,
    trainProfile: TrainDynamics.TrainProfile,
    carProfiles: List<TrainDynamics.CarProfile>,
    val gravityMetersPerSecondSquared: Double,
    initialSpeedMetersPerSecond: Double = train.speed,
    private val profileResolver: ((Car) -> TrainDynamics.CarProfile?)? = null
) : DynamicsProfileSource {
    private class Published(
        val state: TrainDynamics.State,
        val result: TrainDynamics.StepResult,
        val cars: List<Car>
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
        val workspace = TrainDynamics.PreviewWorkspace(cars.size)

        @Volatile
        var published = initial
    }

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

    init {
        require(gravityMetersPerSecondSquared.isFinite() && gravityMetersPerSecondSquared > 0.0)
        require(initialSpeedMetersPerSecond.isFinite() && initialSpeedMetersPerSecond >= 0.0)
        val cars = train.cars.toList()
        require(carProfiles.size == cars.size) { "car profile count does not match train car count" }
        val profiles = IdentityHashMap<Car, TrainDynamics.CarProfile>()
        cars.forEachIndexed { index, car -> profiles[car] = carProfiles[index] }
        val state = TrainDynamics.initialState(carProfiles, initialSpeedMetersPerSecond)
        val result = TrainDynamics.StepResult(state, 0.0, 0.0, 0.0, 0.0, 0.0)
        current = ControllerConsistState(Binding(trainProfile, cars, profiles, Published(state, result, cars)))
    }

    override fun dynamicsProfileFor(car: Car): TrainDynamics.CarProfile? = current.value.profiles[car]

    override fun dynamicsDeviceStateFor(car: Car): TrainDynamics.CarDeviceState? {
        val published = current.value.published
        return published.indices[car]?.let { published.state.devices[it] }
    }

    internal fun prepareConsistChange(cars: List<Car>, source: DynamicsProfileSource?): PreparedConsistDynamics {
        val previous = current.value
        val profiles = IdentityHashMap<Car, TrainDynamics.CarProfile>()
        val devices = cars.map { car ->
            profiles[car] = dynamicsProfileFor(car) ?: source?.dynamicsProfileFor(car) ?: profileResolver?.invoke(car)
                ?: error("Train ${train.id} car '${car.id}' has no dynamics profile for this instance")
            dynamicsDeviceStateFor(car) ?: source?.dynamicsDeviceStateFor(car) ?: TrainDynamics.CarDeviceState(
                pressureKpa = if (previous.published.state.speed == 0.0) {
                    profiles.getValue(car).equipment.nominalCylinderPressureKpa
                } else 0.0
            )
        }
        val state = previous.published.state.copy(devices = TrainDynamics.DeviceSnapshot.of(devices))
        val capturedCars = cars.toList()
        val published = Published(state, previous.published.result, capturedCars)
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

    internal fun snapshotPreview(
        currentSpeed: Double,
        deltaTimeSeconds: Double,
        worstGrade: Double? = null
    ): TrainDynamics.PreviewSession {
        val binding = current.value
        val capture = capture(binding, currentSpeed)
        val inputs = if (worstGrade == null) capture.inputs else {
            val commonGrade = minOf(worstGrade, capture.inputs.minOf { it.grade })
            capture.inputs.map { it.copy(grade = commonGrade) }
        }
        return TrainDynamics.PreviewSession(capture.state, binding.profile, inputs,
            gravityMetersPerSecondSquared, deltaTimeSeconds, binding.workspace)
    }

    fun step(currentSpeedMetersPerSecond: Double, handle: String, deltaTimeSeconds: Double): TrainDynamics.StepResult {
        val binding = current.value
        val capture = capture(binding, currentSpeedMetersPerSecond)
        val result = TrainDynamics.step(capture.state, binding.profile, capture.inputs, handle,
            gravityMetersPerSecondSquared, deltaTimeSeconds)
        binding.published = Published(result.state, result, capture.cars)
        return result
    }
}
