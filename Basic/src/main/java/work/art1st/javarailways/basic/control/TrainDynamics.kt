package work.art1st.javarailways.basic.control

import kotlin.math.abs

/** Pure longitudinal dynamics shared by live train control and isolated timetable simulation. */
object TrainDynamics {
    interface TrainProfile {
        val maxSpeed: Double
        val powerHandles: List<String>
        val brakeHandles: List<String>
        val brakeApplyTime: Double
        val brakeReleaseTime: Double
        val adhesionCoefficient: Double
        val tractionJerk: Double
        val brakeJerk: Double
        fun normalizedPowerScale(handle: String): Double
        fun normalizedBrakeScale(handle: String): Double
        fun resistanceForceAt(speed: Double): Double
    }

    interface CarProfile {
        val mass: Double
        val effectiveMass: Double
        val motorAdhesionMassRatio: Double
        val brakeMaxForce: Double
        val brakeAdhesionMassRatio: Double
        fun motorForceAt(speed: Double): Double
        fun resistanceForceAt(speed: Double, defaultResistance: (Double) -> Double): Double
    }

    data class CarInput(
        val control: CarProfile,
        /** sin(theta), positive when the train's forward direction is uphill. */
        val grade: Double = 0.0
    )

    data class State(
        val speed: Double = 0.0,
        val acceleration: Double = 0.0,
        val brakeScale: Double = 0.0
    )

    data class StepResult(
        val state: State,
        val distance: Double,
        val tractionForce: Double,
        val brakeForce: Double,
        val resistanceForce: Double,
        val gradeForce: Double
    )

    fun step(
        state: State,
        config: TrainProfile,
        cars: List<CarInput>,
        handle: String,
        gravityMetersPerSecondSquared: Double,
        deltaSeconds: Double
    ): StepResult {
        require(gravityMetersPerSecondSquared.isFinite() && gravityMetersPerSecondSquared > 0.0) {
            "gravityMetersPerSecondSquared must be finite and positive"
        }
        if (deltaSeconds <= 0.0 || cars.isEmpty()) {
            return StepResult(state, 0.0, 0.0, 0.0, 0.0, 0.0)
        }

        val normalizedHandle = handle.uppercase()
        val powerScale = config.normalizedPowerScale(normalizedHandle)
        val targetBrakeScale = config.normalizedBrakeScale(normalizedHandle)
        val brakeScale = approachBrakeScale(
            current = state.brakeScale,
            target = targetBrakeScale,
            applyTime = config.brakeApplyTime,
            releaseTime = config.brakeReleaseTime,
            deltaSeconds = deltaSeconds
        )

        val effectiveMass = totalEffectiveMass(cars.map { it.control })
        val speed = state.speed.coerceIn(0.0, config.maxSpeed)

        val tractionForce = if (speed >= config.maxSpeed && powerScale > 0.0) {
            0.0
        } else {
            cars.sumOf { car ->
                val raw = car.control.motorForceAt(speed) * powerScale
                val adhesionLimit = car.control.mass * car.control.motorAdhesionMassRatio *
                    gravityMetersPerSecondSquared *
                    config.adhesionCoefficient
                raw.coerceIn(0.0, adhesionLimit)
            }
        }
        val brakeForce = cars.sumOf { car ->
            val raw = car.control.brakeMaxForce * brakeScale
            val adhesionLimit = car.control.mass * car.control.brakeAdhesionMassRatio *
                gravityMetersPerSecondSquared *
                config.adhesionCoefficient
            raw.coerceIn(0.0, adhesionLimit)
        }
        val resistanceForce = cars.sumOf { car ->
            car.control.resistanceForceAt(speed, config::resistanceForceAt)
        }
        val gradeForce = cars.sumOf { car ->
            car.control.mass * gravityMetersPerSecondSquared * car.grade.coerceIn(-1.0, 1.0)
        }

        var targetAcceleration = (tractionForce - brakeForce - resistanceForce - gradeForce) / effectiveMass
        if (speed <= 0.0 && targetAcceleration < 0.0) targetAcceleration = 0.0

        val acceleration = applyJerkLimit(
            current = state.acceleration,
            target = targetAcceleration,
            tractionJerk = config.tractionJerk,
            brakeJerk = config.brakeJerk,
            deltaSeconds = deltaSeconds
        )

        var newSpeed = speed + acceleration * deltaSeconds
        if (newSpeed > config.maxSpeed) newSpeed = config.maxSpeed
        if (newSpeed < 0.0) newSpeed = 0.0

        val limitedAcceleration = (newSpeed - speed) / deltaSeconds
        val distance = ((speed + newSpeed) * 0.5 * deltaSeconds).coerceAtLeast(0.0)
        return StepResult(
            State(newSpeed, limitedAcceleration, brakeScale),
            distance,
            tractionForce,
            brakeForce,
            resistanceForce,
            gradeForce
        )
    }

    fun strongestServiceBrakeHandle(config: TrainProfile): String =
        config.brakeHandles.asSequence()
            .filterNot { it.equals("EB", ignoreCase = true) }
            .maxByOrNull(config::normalizedBrakeScale)
            ?: throw IllegalArgumentException("train profile has no non-emergency brake handle")

    fun steadyServiceBrakeForce(
        config: TrainProfile,
        cars: List<CarProfile>,
        gravityMetersPerSecondSquared: Double
    ): Double {
        require(gravityMetersPerSecondSquared.isFinite() && gravityMetersPerSecondSquared > 0.0)
        val brakeScale = config.normalizedBrakeScale(strongestServiceBrakeHandle(config))
        return cars.sumOf { car ->
            val raw = car.brakeMaxForce * brakeScale
            val adhesionLimit = car.mass * car.brakeAdhesionMassRatio *
                gravityMetersPerSecondSquared * config.adhesionCoefficient
            raw.coerceIn(0.0, adhesionLimit)
        }
    }

    fun flatServiceDeceleration(
        config: TrainProfile,
        cars: List<CarProfile>,
        gravityMetersPerSecondSquared: Double
    ): Double {
        require(cars.isNotEmpty()) { "at least one car profile is required" }
        val effectiveMass = totalEffectiveMass(cars)
        return steadyServiceBrakeForce(config, cars, gravityMetersPerSecondSquared) / effectiveMass
    }

    internal fun totalEffectiveMass(cars: List<CarProfile>): Double {
        val total = cars.sumOf { car ->
            require(car.mass.isFinite() && car.mass > 0.0) { "car mass must be finite and positive" }
            car.effectiveMass.also {
                require(it.isFinite() && it > 0.0) { "car effective mass must be finite and positive" }
            }
        }
        require(total.isFinite() && total > 0.0) { "total effective mass must be finite and positive" }
        return total
    }

    private fun approachBrakeScale(
        current: Double,
        target: Double,
        applyTime: Double,
        releaseTime: Double,
        deltaSeconds: Double
    ): Double {
        if (target == current) return current
        val time = if (target > current) applyTime else releaseTime
        if (time <= 0.0) return target
        val maxDelta = deltaSeconds / time
        return if (target > current) minOf(target, current + maxDelta)
        else maxOf(target, current - maxDelta)
    }

    private fun applyJerkLimit(
        current: Double,
        target: Double,
        tractionJerk: Double,
        brakeJerk: Double,
        deltaSeconds: Double
    ): Double {
        val delta = target - current
        if (abs(delta) < 1.0e-9) return target
        val jerk = if (delta >= 0.0) tractionJerk else brakeJerk
        if (jerk <= 0.0) return target
        val maxDelta = jerk * deltaSeconds
        return when {
            delta > maxDelta -> current + maxDelta
            delta < -maxDelta -> current - maxDelta
            else -> target
        }
    }
}
