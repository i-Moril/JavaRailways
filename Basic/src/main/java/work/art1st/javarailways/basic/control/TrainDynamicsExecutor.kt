package work.art1st.javarailways.basic.control

import work.art1st.javarailways.core.api.physics.train.Train

/** Stateful adapter around the pure [TrainDynamics] step shared by manual and automatic control. */
class TrainDynamicsExecutor(
    private val train: Train,
    val trainProfile: TrainDynamics.TrainProfile,
    carProfiles: List<TrainDynamics.CarProfile>,
    val gravityMetersPerSecondSquared: Double,
    initialSpeedMetersPerSecond: Double = train.speed
) {
    val carProfiles: List<TrainDynamics.CarProfile> = carProfiles.toList()

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
        require(this.carProfiles.size == train.cars.size) {
            "car profile count ${this.carProfiles.size} does not match train car count ${train.cars.size}"
        }
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

    private fun carInputs(): List<TrainDynamics.CarInput> = train.cars.mapIndexed { index, car ->
        TrainDynamics.CarInput(
            control = carProfiles[index],
            grade = car.operatingDirection.y()
        )
    }
}
