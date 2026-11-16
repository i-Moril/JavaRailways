package work.art1st.javarailways.basic.control

import work.art1st.javarailways.basic.api.ato.*

/** Pure prepared vehicle model shared by physical executors and detached travel previews. */
class VehicleControlCapabilityModel(
    private val profile: TrainDynamicsProfile,
    carProfiles: List<CarDynamicsProfile>,
    private val gravity: Double
) {
    private val profiles = carProfiles.toList()
    private val identity = Any()
    private val effectiveMass = TrainDynamics.totalEffectiveMass(profiles)
    private val mass = profiles.sumOf { it.mass }
    private val serviceRatio = profile.normalizedBrakeScale(TrainDynamics.strongestServiceBrakeHandle(profile))
    private val emergencyRatio = TrainDynamics.emergencyBrakeScale(profile)
    private val serviceDeceleration = TrainDynamics.flatServiceDeceleration(profile, profiles, gravity)
    private val emergencyDeceleration = brakeForce(emergencyRatio) / effectiveMass
    private val maximumTractionRatio = TrainDynamics.maximumTractionRatio(profile)
    private val maximumTraction = profiles.sumOf {
        val adhesion = it.mass * it.motorAdhesionMassRatio * gravity * profile.adhesionCoefficient
        val bound = it.maximumMotorForceUpperBound
        require(bound == null || bound.isFinite() && bound >= 0.0)
        if (bound == null) adhesion else minOf(bound * maximumTractionRatio, adhesion)
    } / effectiveMass
    private val samples: List<VehiclePerformanceSample>

    init {
        require(gravity.isFinite() && gravity > 0.0)
        require(profile.maxSpeed.isFinite() && profile.maxSpeed >= 0.0)
        val bins = kotlin.math.ceil(profile.maxSpeed / 0.25).toInt().coerceAtLeast(1)
        require(bins <= 10000) { "maximum speed exceeds performance table capacity" }
        samples = (0..bins).map { index ->
            val speed = minOf(index * 0.25, profile.maxSpeed)
            VehiclePerformanceSample(speed, tractionForce(speed, maximumTractionRatio) / effectiveMass,
                nominalBrakeForce(speed, serviceRatio, false) / effectiveMass, emergencyDeceleration)
        }
    }

    fun snapshot(speedMetersPerSecond: Double, inputs: List<TrainDynamics.CarInput>): VehicleControlCapabilities {
        require(speedMetersPerSecond.isFinite() && speedMetersPerSecond >= 0.0)
        require(inputs.size == profiles.size && inputs.indices.all { inputs[it].control === profiles[it] }) {
            "capability inputs must match prepared car profile identities and order"
        }
        require(inputs.all { it.grade.isFinite() && it.grade in -1.0..1.0 })
        val resistance = profiles.sumOf { it.resistanceForceAt(speedMetersPerSecond, profile::resistanceForceAt) }
        val gradeForce = inputs.sumOf { it.control.mass * gravity * it.grade }
        val handles = (profile.brakeHandles + "N" + profile.powerHandles).distinct().map { handle ->
            val command = TrainDynamics.commandForHandle(profile, handle)
            val traction = if (command.emergencyBrake) 0.0 else tractionForce(speedMetersPerSecond, command.tractionRatio)
            val brake = nominalBrakeForce(speedMetersPerSecond,
                if (command.emergencyBrake) emergencyRatio else command.brakeRatio, command.emergencyBrake)
            VehicleHandleCapability(handle, command, (traction - brake - resistance - gradeForce) / effectiveMass)
        }
        return VehicleControlCapabilities(identity, profile.maxSpeed,
            profile.nominalOperatingAcceleration, profile.nominalOperatingDeceleration,
            maximumTraction, serviceDeceleration, emergencyDeceleration, serviceRatio, emergencyRatio,
            profile.brakeApplyTime, profile.brakeReleaseTime, profile.brakeJerk, profile.tractionJerk,
            gravity, mass / effectiveMass, inputs.minOf { it.grade }, samples, handles,
            java.util.List.copyOf(profiles.map { car ->
                val e = car.equipment
                VehicleCarControlCapabilities(car.mass, car.effectiveMass, car.brakeMaxForce, car.electricBrakeMaxForce,
                    car.mass * car.motorAdhesionMassRatio * gravity * profile.adhesionCoefficient,
                    car.mass * car.brakeAdhesionMassRatio * gravity * profile.adhesionCoefficient,
                    e.nominalCylinderPressureKpa, e.commandDelaySeconds,
                    if (profile.brakeApplyTime == 0.0) Double.POSITIVE_INFINITY else e.nominalCylinderPressureKpa / profile.brakeApplyTime,
                    if (profile.brakeReleaseTime == 0.0) Double.POSITIVE_INFINITY else e.nominalCylinderPressureKpa / profile.brakeReleaseTime,
                    e.electricBrakeMaxPowerWatts, e.electricBrakeFadeSpeedMetersPerSecond,
                    e.electricBrakeApplyRateNewtonsPerSecond, e.electricBrakeReleaseRateNewtonsPerSecond,
                    e.tractionApplyRateNewtonsPerSecond, e.tractionReleaseRateNewtonsPerSecond,
                    e.tractionReleasePressureKpa, e.electricReleaseForceThresholdNewtons,
                    car.motorForceAt(speedMetersPerSecond.coerceAtMost(profile.maxSpeed)))
            }))
    }

    private fun tractionForce(speed: Double, ratio: Double) = profiles.sumOf {
        minOf(it.motorForceAt(speed) * ratio,
            it.mass * it.motorAdhesionMassRatio * gravity * profile.adhesionCoefficient)
    }

    private fun nominalBrakeForce(speed: Double, ratio: Double, emergency: Boolean) = profiles.sumOf { car ->
        val e = car.equipment
        val electric = if (emergency) 0.0 else minOf(car.electricBrakeMaxForce,
            e.electricBrakeMaxPowerWatts / maxOf(speed, 0.5)) *
            minOf(1.0, speed / e.electricBrakeFadeSpeedMetersPerSecond) * minOf(ratio, 1.0)
        minOf(maxOf(car.brakeMaxForce * ratio, electric),
            car.mass * car.brakeAdhesionMassRatio * gravity * profile.adhesionCoefficient)
    }

    private fun brakeForce(ratio: Double) = profiles.sumOf {
        minOf(it.brakeMaxForce * ratio,
            it.mass * it.brakeAdhesionMassRatio * gravity * profile.adhesionCoefficient)
    }
}
