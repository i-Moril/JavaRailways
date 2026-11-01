package work.art1st.javarailways.simpleato.timetable.simulation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.control.TrainDynamics

class DetailedTrainTravelSimulatorTest {
    @Test
    fun `pure detailed simulator is deterministic and uses fixed logical steps`() {
        val profile = profile()
        val legs = listOf(DetailedTravelLeg("a", "b", 250.0))

        val first = DetailedTrainTravelSimulator.simulate(legs, profile)
        val second = DetailedTrainTravelSimulator.simulate(legs, profile)

        assertEquals(first, second)
        assertTrue(first.legs.single().travelSeconds > 0.0)
        assertEquals(0.0, first.legs.single().travelSeconds % DetailedTrainTravelSimulator.STEP_SECONDS, 1.0e-7)
        assertTrue(kotlin.math.abs(first.legs.single().stoppingErrorMeters) < 3.0)

        val capped = DetailedTrainTravelSimulator.simulate(
            legs,
            profile.copy(maximumSpeedMetersPerSecond = 4.0)
        )
        assertTrue(capped.legs.single().peakSpeedMetersPerSecond <= 4.0)
        assertTrue(capped.legs.single().peakSpeedMetersPerSecond < first.legs.single().peakSpeedMetersPerSecond)
    }
    @Test fun `consecutive stops retain brake equipment and next departure releases it`() {
        val profile = profile()
        val legs = listOf(DetailedTravelLeg("a", "b", 250.0), DetailedTravelLeg("b", "c", 250.0))
        val first = DetailedTrainTravelSimulator.simulate(legs, profile)
        val second = DetailedTrainTravelSimulator.simulate(legs, profile)
        assertEquals(first, second)
        assertEquals(2, first.legs.size)
        assertTrue(first.legs.all { it.travelSeconds > 0 && it.peakSpeedMetersPerSecond > 1.0 })
        assertTrue(first.legs.all { kotlin.math.abs(it.stoppingErrorMeters) < 3.0 })
        assertTrue(first.legs[1].travelSeconds > profile.train.brakeReleaseTime,
            "the next stop group must complete a physical departure after its retained brake state")
    }

    private fun profile(): DetailedTrainSimulationProfile {
        val train = object : TrainDynamics.TrainProfile {
            override val maxSpeed = 12.0
            override val powerHandles = listOf("P1")
            override val brakeHandles = listOf("B1")
            override val brakeApplyTime = 0.2
            override val brakeReleaseTime = 0.2
            override val adhesionCoefficient = 0.25
            override val tractionJerk = 2.0
            override val brakeJerk = 2.0
            override fun normalizedPowerScale(handle: String) = if (handle == "P1") 1.0 else 0.0
            override fun normalizedBrakeScale(handle: String) = if (handle == "B1") 1.0 else 0.0
            override fun resistanceForceAt(speed: Double) = 300.0
        }
        val car = object : TrainDynamics.CarProfile {
            override val mass = 30_000.0
            override val effectiveMass = 31_000.0
            override val motorAdhesionMassRatio = 1.0
            override val brakeMaxForce = 60_000.0
            override val brakeAdhesionMassRatio = 1.0
            override fun motorForceAt(speed: Double) = 45_000.0
            override fun resistanceForceAt(speed: Double, defaultResistance: (Double) -> Double) =
                defaultResistance(speed)
        }
        return DetailedTrainSimulationProfile(train, listOf(car), 9.80665)
    }

}
