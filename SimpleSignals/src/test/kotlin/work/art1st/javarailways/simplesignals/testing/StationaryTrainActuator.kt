package work.art1st.javarailways.simplesignals.testing

import work.art1st.javarailways.basic.api.ato.*
import work.art1st.javarailways.basic.control.TrainDynamics
import work.art1st.javarailways.basic.control.VehicleControlCapabilityModel

/** Keeps geometry fixed; used only to test signal/planner publication boundaries. */
internal class StationaryTrainActuator(override val maximumSpeedMetersPerSecond: Double = 10.0) : TrainActuator {
        var steps = 0
        var lastCommand = VehicleControlCommand.COAST
        override fun step(currentSpeedMetersPerSecond: Double, command: VehicleControlCommand, deltaTimeSeconds: Double): ActuationStep {
            steps++
            lastCommand = command
            return ActuationStep(0.0, 0.0)
        }

        private val profile = object : TrainDynamicsProfile {
            override val maxSpeed = maximumSpeedMetersPerSecond
            override val powerHandles = listOf("P")
            override val brakeHandles = listOf("B", "EB")
            override val brakeApplyTime = 0.0
            override val brakeReleaseTime = 0.0
            override val adhesionCoefficient = 1.0
            override val tractionJerk = 0.0
            override val brakeJerk = 0.0
            override fun normalizedPowerScale(handle: String) = if (handle == "P") 1.0 else 0.0
            override fun normalizedBrakeScale(handle: String) = if (handle == "B" || handle == "EB") 1.0 else 0.0
            override fun resistanceForceAt(speed: Double) = 0.0
        }
        private val car = object : CarDynamicsProfile {
            override val mass = 1.0
            override val effectiveMass = 1.0
            override val motorAdhesionMassRatio = 1.0
            override val brakeAdhesionMassRatio = 1.0
            override val brakeMaxForce = 1.0
            override val maximumMotorForceUpperBound = 1.0
            override val equipment = TrainDynamics.Equipment(commandDelaySeconds = 0.0,
                tractionApplyRateNewtonsPerSecond = 1e10, tractionReleaseRateNewtonsPerSecond = 1e10)
            override fun motorForceAt(speed: Double) = 1.0
            override fun resistanceForceAt(speed: Double, defaultResistance: (Double) -> Double) = 0.0
        }
        private val model = VehicleControlCapabilityModel(profile, listOf(car), 9.80665)
        override fun controlState(currentSpeedMetersPerSecond: Double) = VehicleControlState(
            currentSpeedMetersPerSecond, 0.0, 0.0, lastCommand,
            listOf(VehicleDeviceState(0.0)))
        override fun controlCapabilities(currentSpeedMetersPerSecond: Double) = model.snapshot(
            currentSpeedMetersPerSecond, listOf(TrainDynamics.CarInput(car, 0.0)))

        override fun brakingEnvelope(
            segments: List<BrakingGradeSegment>,
            deltaTimeSeconds: Double,
            maximumControlDeltaSeconds: Double
        ): GradeAwareBrakingEnvelope =
            GradeAwareBrakingEnvelope.constant(1.0, segments)
    }
