package work.art1st.javarailways.basic.timetable.simulation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DetailedTimetableTravelTimeEstimatorTest {
    @Test
    fun `converts physical seconds to ticks and caches route profile combinations`() {
        var resolveCalls = 0
        val estimator = DetailedTimetableTravelTimeEstimator(
            DetailedTimetableRouteResolver { from, to ->
                resolveCalls++
                listOf(DetailedTravelLeg(from, to, 150.0))
            },
            mapOf(PROFILE_ID to profile())
        )
        val request = TimetableMovementEstimateRequest("s", 1, PROFILE_ID, "a", listOf("b"))

        val first = estimator.estimateTicks(request)
        val second = estimator.estimateTicks(request.copy(serviceId = "another", stepIndex = 9))

        assertEquals(first, second)
        assertTrue(first > 0L)
        assertEquals(1, resolveCalls)
        assertEquals(1, estimator.cachedEstimateCount())
        estimator.clearCache()
        assertEquals(0, estimator.cachedEstimateCount())
    }

    private fun profile(): DetailedTrainSimulationProfile {
        val train = object : TrainDynamics.TrainProfile {
            override val maxSpeed = 10.0
            override val powerHandles = listOf("P")
            override val brakeHandles = listOf("B")
            override val brakeApplyTime = 0.1
            override val brakeReleaseTime = 0.1
            override val adhesionCoefficient = 0.25
            override val tractionJerk = 3.0
            override val brakeJerk = 3.0
            override fun normalizedPowerScale(handle: String) = if (handle == "P") 1.0 else 0.0
            override fun normalizedBrakeScale(handle: String) = if (handle == "B") 1.0 else 0.0
            override fun resistanceForceAt(speed: Double) = 200.0
        }
        val car = object : TrainDynamics.CarProfile {
            override val mass = 30_000.0
            override val effectiveMass = 31_000.0
            override val motorAdhesionMassRatio = 1.0
            override val brakeMaxForce = 60_000.0
            override val brakeAdhesionMassRatio = 1.0
            override fun motorForceAt(speed: Double) = 40_000.0
            override fun resistanceForceAt(speed: Double, defaultResistance: (Double) -> Double) =
                defaultResistance(speed)
        }
        return DetailedTrainSimulationProfile(train, listOf(car))
    }

    companion object {
        private val PROFILE_ID = TimetableSimulationProfileId("template:test")
    }
}
