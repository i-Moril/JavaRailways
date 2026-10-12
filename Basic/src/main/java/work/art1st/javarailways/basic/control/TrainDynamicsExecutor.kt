package work.art1st.javarailways.basic.control

import work.art1st.javarailways.core.api.physics.train.Train
import work.art1st.javarailways.core.api.physics.train.Car
import java.util.IdentityHashMap

/** Stateful adapter around the pure [TrainDynamics] step shared by manual and automatic control. */
class TrainDynamicsExecutor(
    private val train: Train,
    val trainProfile: TrainDynamics.TrainProfile,
    carProfiles: List<TrainDynamics.CarProfile>,
    val gravityMetersPerSecondSquared: Double,
    initialSpeedMetersPerSecond: Double = train.speed
) {
    private val profilesByCar = IdentityHashMap<Car, TrainDynamics.CarProfile>()

    /** Profiles for the current consist, in its current order; removed cars are not included. */
    val carProfiles: List<TrainDynamics.CarProfile>
        get() = train.cars.map(::profileFor)

    @Volatile
    var state: TrainDynamics.State = TrainDynamics.State(speed = initialSpeedMetersPerSecond)
        private set

    @Volatile
    var lastResult: TrainDynamics.StepResult =
        TrainDynamics.StepResult(state, 0.0, 0.0, 0.0, 0.0, 0.0)
        private set

    init {
        require(gravityMetersPerSecondSquared.isFinite() && gravityMetersPerSecondSquared > 0.0)
        require(initialSpeedMetersPerSecond.isFinite() && initialSpeedMetersPerSecond >= 0.0)
        val cars = train.cars.toList()
        require(carProfiles.size == cars.size) {
            "car profile count ${carProfiles.size} does not match train car count ${cars.size}"
        }
        cars.forEachIndexed { index, car -> profilesByCar[car] = carProfiles[index] }
    }

    fun preview(
        currentSpeedMetersPerSecond: Double,
        handle: String,
        deltaTimeSeconds: Double
    ): TrainDynamics.StepResult = TrainDynamics.step(
        state = state.copy(speed = currentSpeedMetersPerSecond),
        config = trainProfile,
        cars = carInputs(),
        handle = handle,
        gravityMetersPerSecondSquared = gravityMetersPerSecondSquared,
        deltaSeconds = deltaTimeSeconds
    )

    fun step(
        currentSpeedMetersPerSecond: Double,
        handle: String,
        deltaTimeSeconds: Double
    ): TrainDynamics.StepResult = preview(currentSpeedMetersPerSecond, handle, deltaTimeSeconds).also {
        state = it.state
        lastResult = it
    }

    private fun profileFor(car: Car): TrainDynamics.CarProfile = checkNotNull(profilesByCar[car]) {
        "Train ${train.id} car '${car.id}' has no dynamics profile for this instance"
    }

    private fun carInputs(): List<TrainDynamics.CarInput> = train.cars.map { car ->
        TrainDynamics.CarInput(
            control = profileFor(car),
            grade = car.operatingDirection.y()
        )
    }
}
