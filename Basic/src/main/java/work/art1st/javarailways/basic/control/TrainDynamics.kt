package work.art1st.javarailways.basic.control

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.max
import kotlinx.serialization.Serializable

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

    @Serializable
    data class Equipment(
        val nominalCylinderPressureKpa: Double = 350.0,
        val commandDelaySeconds: Double = 0.1,
        val electricBrakeMaxPowerWatts: Double = 400000.0,
        val electricBrakeFadeSpeedMetersPerSecond: Double = 3.0,
        val electricBrakeApplyRateNewtonsPerSecond: Double = 100000.0,
        val electricBrakeReleaseRateNewtonsPerSecond: Double = 100000.0,
        val tractionApplyRateNewtonsPerSecond: Double = 80000.0,
        val tractionReleaseRateNewtonsPerSecond: Double = 160000.0,
        val tractionReleasePressureKpa: Double = 5.0,
        val electricReleaseForceThresholdNewtons: Double = 1.0
    ) {
        init {
            require(nominalCylinderPressureKpa.isFinite() && nominalCylinderPressureKpa > 0.0)
            require(commandDelaySeconds.isFinite() && commandDelaySeconds >= 0.0)
            require(electricBrakeMaxPowerWatts.isFinite() && electricBrakeMaxPowerWatts >= 0.0)
            require(electricBrakeFadeSpeedMetersPerSecond.isFinite() && electricBrakeFadeSpeedMetersPerSecond > 0.0)
            listOf(electricBrakeApplyRateNewtonsPerSecond, electricBrakeReleaseRateNewtonsPerSecond,
                tractionApplyRateNewtonsPerSecond, tractionReleaseRateNewtonsPerSecond).forEach {
                require(it.isFinite() && it > 0.0) { "device force rates must be finite and positive" }
            }
            require(tractionReleasePressureKpa.isFinite() && tractionReleasePressureKpa in 0.0..nominalCylinderPressureKpa)
            require(electricReleaseForceThresholdNewtons.isFinite() && electricReleaseForceThresholdNewtons >= 0.0)
        }
    }

    interface CarProfile {
        val mass: Double
        val effectiveMass: Double
        val motorAdhesionMassRatio: Double
        val brakeMaxForce: Double
        val brakeAdhesionMassRatio: Double
        val electricBrakeMaxForce: Double get() = 0.0
        val equipment: Equipment get() = DEFAULT_EQUIPMENT
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
        val brakeScale: Double = 0.0,
        val devices: DeviceSnapshot = DeviceSnapshot.empty()
    )

    data class StepResult(
        val state: State,
        val distance: Double,
        val tractionForce: Double,
        val brakeForce: Double,
        val resistanceForce: Double,
        val gradeForce: Double
    )

    data class CarDeviceState(
        val pressureKpa: Double = 0.0,
        val electricBrakeForceNewtons: Double = 0.0,
        val tractionForceNewtons: Double = 0.0,
        val commandAgeSeconds: Double = 0.0,
        val previousDemand: Double = 0.0
    )

    /** Stable owned storage: no public mutable arrays, and no view of a speculative workspace. */
    class DeviceSnapshot private constructor(internal val values: DeviceArrays) {
        val size: Int get() = values.pressure.size
        operator fun get(index: Int) = CarDeviceState(values.pressure[index], values.electric[index],
            values.traction[index], values.age[index], values.previous[index])
        fun toList(): List<CarDeviceState> = (0 until size).map(::get)
        override fun equals(other: Any?): Boolean = other is DeviceSnapshot &&
            values.pressure.contentEquals(other.values.pressure) && values.electric.contentEquals(other.values.electric) &&
            values.traction.contentEquals(other.values.traction) && values.age.contentEquals(other.values.age) &&
            values.previous.contentEquals(other.values.previous)
        override fun hashCode(): Int {
            var hash = values.pressure.contentHashCode()
            hash = 31 * hash + values.electric.contentHashCode()
            hash = 31 * hash + values.traction.contentHashCode()
            hash = 31 * hash + values.age.contentHashCode()
            return 31 * hash + values.previous.contentHashCode()
        }
        companion object {
            private val EMPTY = DeviceSnapshot(DeviceArrays(0))
            fun empty(): DeviceSnapshot = EMPTY
            fun of(states: List<CarDeviceState>): DeviceSnapshot {
                val arrays = DeviceArrays(states.size)
                states.forEachIndexed { i, s ->
                    require(listOf(s.pressureKpa, s.electricBrakeForceNewtons, s.tractionForceNewtons, s.commandAgeSeconds)
                        .all { it.isFinite() && it >= 0.0 } && s.previousDemand.isFinite())
                    arrays.pressure[i] = s.pressureKpa
                    arrays.electric[i] = s.electricBrakeForceNewtons
                    arrays.traction[i] = s.tractionForceNewtons
                    arrays.age[i] = s.commandAgeSeconds
                    arrays.previous[i] = s.previousDemand
                }
                return DeviceSnapshot(arrays)
            }
            internal fun own(arrays: DeviceArrays) = DeviceSnapshot(arrays)
        }
    }

    internal class DeviceArrays(n: Int) {
        val pressure = DoubleArray(n)
        val electric = DoubleArray(n)
        val traction = DoubleArray(n)
        val age = DoubleArray(n)
        val previous = DoubleArray(n)
    }
    internal class PreviewWorkspace(n: Int) {
        val destination = DeviceArrays(n)
    }
    internal data class TrialState(val motion: State, val devices: DeviceArrays)
    internal data class TrialResult(val state: TrialState, val distance: Double, val tractionForce: Double,
        val brakeForce: Double, val resistanceForce: Double, val gradeForce: Double)

    fun initialState(cars: List<CarProfile>, speed: Double = 0.0, acceleration: Double = 0.0,
                     brakeScale: Double = if (speed == 0.0) 1.0 else 0.0): State = State(speed, acceleration, brakeScale,
        DeviceSnapshot.of(cars.map { car -> CarDeviceState(pressureKpa = car.equipment.nominalCylinderPressureKpa * brakeScale) }))

    private fun initialized(state: State, cars: List<CarInput>): State {
        // A new scalar State has no car count until its first dynamics invocation.
        if (state.devices.size == 0) return initialState(cars.map { it.control }, state.speed, state.acceleration, state.brakeScale)
        require(state.devices.size == cars.size) { "device count must match the captured consist" }
        return state
    }

    /** Preview results never escape the captured executor workspace. */
    internal class PreviewSession(
        state: State, internal val profile: TrainProfile, internal val cars: List<CarInput>,
        internal val gravity: Double, internal val delta: Double,
        private val workspace: PreviewWorkspace = PreviewWorkspace(cars.size)
    ) {
        private val stable = initialized(state, cars)
        val seed = TrialState(stable, stable.devices.values)
        fun trial(handle: String): TrialResult = compute(
            seed, workspace.destination, profile, cars, handle, gravity, delta
        )
    }

    fun step(state: State, config: TrainProfile, cars: List<CarInput>, handle: String,
             gravityMetersPerSecondSquared: Double, deltaSeconds: Double): StepResult {
        require(gravityMetersPerSecondSquared.isFinite() && gravityMetersPerSecondSquared > 0.0)
        require(deltaSeconds.isFinite())
        if (deltaSeconds <= 0.0 || cars.isEmpty()) return StepResult(state, 0.0, 0.0, 0.0, 0.0, 0.0)
        val source = initialized(state, cars)
        val destination = DeviceArrays(cars.size)
        val result = compute(TrialState(source, source.devices.values), destination, config, cars, handle,
            gravityMetersPerSecondSquared, deltaSeconds)
        return StepResult(result.state.motion.copy(devices = DeviceSnapshot.own(destination)), result.distance,
            result.tractionForce, result.brakeForce, result.resistanceForce, result.gradeForce)
    }

    private fun compute(source: TrialState, destination: DeviceArrays, config: TrainProfile,
                        cars: List<CarInput>, handle: String, gravity: Double, delta: Double): TrialResult {
        require(gravity.isFinite() && gravity > 0.0)
        require(delta.isFinite() && delta >= 0.0)
        if (delta == 0.0 || cars.isEmpty()) return TrialResult(source, 0.0, 0.0, 0.0, 0.0, 0.0)
        val normalized = handle.uppercase()
        val brake = config.normalizedBrakeScale(normalized)
        val power = config.normalizedPowerScale(normalized)
        val speed = source.motion.speed.coerceIn(0.0, config.maxSpeed)
        var traction = 0.0
        var braking = 0.0
        var resistance = 0.0
        var grade = 0.0
        var mass = 0.0
        var pressureScale = 0.0
        val old = source.devices
        for (i in cars.indices) {
            val input = cars[i]
            val car = input.control
            val equipment = car.equipment
            require(car.mass.isFinite() && car.mass > 0.0 && car.effectiveMass.isFinite() && car.effectiveMass > 0.0)
            require(car.electricBrakeMaxForce.isFinite() && car.electricBrakeMaxForce >= 0.0)
            val demand = if (brake > 0.0) - brake else power
            destination.age[i] = if (demand != old.previous[i]) 0.0 else old.age[i] + delta
            destination.previous[i] = demand
            val enabled = destination.age[i] >= equipment.commandDelaySeconds
            val electricTarget = if (enabled && brake > 0.0 && normalized != "EB") {
                min(car.electricBrakeMaxForce, equipment.electricBrakeMaxPowerWatts / max(speed, 0.5))*
                    min(1.0, speed / equipment.electricBrakeFadeSpeedMetersPerSecond) * min(brake, 1.0)
            } else 0.0
            destination.electric[i] = approach(old.electric[i], electricTarget,
                (if (electricTarget>old.electric[i]) equipment.electricBrakeApplyRateNewtonsPerSecond else equipment.electricBrakeReleaseRateNewtonsPerSecond) * delta)
            val pressureTarget = if (enabled) {
                if (car.brakeMaxForce > 0.0) ((car.brakeMaxForce * brake - destination.electric[i]).coerceAtLeast(0.0) / car.brakeMaxForce) * equipment.nominalCylinderPressureKpa
                else 0.0
            } else old.pressure[i]
            val airTime = if (pressureTarget>old.pressure[i]) config.brakeApplyTime else config.brakeReleaseTime
            destination.pressure[i] = if (airTime <= 0.0) pressureTarget else approach(old.pressure[i], pressureTarget,
                equipment.nominalCylinderPressureKpa / airTime * delta)
            val motorAdhesion = car.mass * car.motorAdhesionMassRatio * gravity * config.adhesionCoefficient
            val targetTraction = if (enabled && power > 0.0 && speed<config.maxSpeed &&
                destination.pressure[i] <= equipment.tractionReleasePressureKpa &&
                destination.electric[i] <= equipment.electricReleaseForceThresholdNewtons)(car.motorForceAt(speed) * power).coerceIn(0.0, motorAdhesion) else 0.0
            destination.traction[i] = approach(old.traction[i], targetTraction,
                (if (targetTraction>old.traction[i]) equipment.tractionApplyRateNewtonsPerSecond else equipment.tractionReleaseRateNewtonsPerSecond) * delta)
            val brakeAdhesion = car.mass * car.brakeAdhesionMassRatio * gravity * config.adhesionCoefficient
            traction += destination.traction[i].coerceIn(0.0, motorAdhesion)
            braking += (destination.electric[i] + car.brakeMaxForce * destination.pressure[i] / equipment.nominalCylinderPressureKpa).coerceIn(0.0, brakeAdhesion)
            resistance += car.resistanceForceAt(speed, config::resistanceForceAt)
            grade += car.mass * gravity * input.grade.coerceIn(-1.0, 1.0)
            mass += car.effectiveMass
            pressureScale += destination.pressure[i] / equipment.nominalCylinderPressureKpa
        }
        require(mass.isFinite() && mass > 0.0) { "total effective mass must be finite and positive" }
        var target = (traction - braking - resistance - grade) / mass
        if (speed == 0.0 && target < 0.0) target = 0.0
        val acceleration = applyJerkLimit(source.motion.acceleration, target, config.tractionJerk, config.brakeJerk, delta)
        val nextSpeed = (speed + acceleration * delta).coerceIn(0.0, config.maxSpeed)
        val motion = State(nextSpeed, (nextSpeed - speed) / delta, pressureScale / cars.size)
        return TrialResult(TrialState(motion, destination), ((speed + nextSpeed) * 0.5 * delta).coerceAtLeast(0.0), traction, braking, resistance, grade)
    }

    private fun approach(current: Double, target: Double, maxDelta: Double): Double = current + (target - current).coerceIn(-maxDelta, maxDelta)
    private val DEFAULT_EQUIPMENT = Equipment()

    fun strongestServiceBrakeHandle(config: TrainProfile): String = config.brakeHandles.asSequence()
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
            val raw = (car.brakeMaxForce * brakeScale - car.electricBrakeMaxForce).coerceAtLeast(0.0)
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
