package work.art1st.javarailways.bukkit.tool

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.rail.RailAuthoringMetadata
import work.art1st.javarailways.basic.rail.RailInfrastructureMetadata
import work.art1st.javarailways.basic.rail.SpeedLimitEntry
import work.art1st.javarailways.basic.rail.SpeedLimitMetadata
import work.art1st.javarailways.basic.rail.SpeedLimitType

class RailWandParallelLinkagePolicyTest {
    @Test
    fun `manual curve speed alone is not a geometry edit`() {
        assertFalse(
            railGeometryParametersChanged(
                currentDesignSpeedKmH = 120.0,
                currentRadiusMeters = 800.0,
                currentMaxSuperelevationMm = 150.0,
                newDesignSpeedKmH = 120.0,
                newRadiusMeters = 800.0,
                newMaxSuperelevationMm = 150.0
            )
        )
        assertTrue(
            railGeometryParametersChanged(
                currentDesignSpeedKmH = 120.0,
                currentRadiusMeters = 800.0,
                currentMaxSuperelevationMm = 150.0,
                newDesignSpeedKmH = 120.0,
                newRadiusMeters = 900.0,
                newMaxSuperelevationMm = 150.0
            )
        )
    }

    @Test
    fun `geometry rebuild changes manual curve speed only on selected lane`() {
        val selectedLane = listOf(metadataWithManualCurveLimit(70.0))
        val companionLane = listOf(metadataWithManualCurveLimit(50.0))

        assertEquals(
            90.0,
            curveSpeedLimitForRebuiltLane(0, 0, 90.0, selectedLane),
            1.0e-9
        )
        assertEquals(
            50.0,
            curveSpeedLimitForRebuiltLane(1, 0, 90.0, companionLane),
            1.0e-9
        )
    }

    @Test
    fun `automatic companion curve limit remains automatic during rebuild`() {
        assertEquals(
            0.0,
            curveSpeedLimitForRebuiltLane(
                laneIndex = 1,
                selectedLaneIndex = 0,
                editedCurveSpeedLimitKmH = 90.0,
                oldLane = listOf(RailInfrastructureMetadata())
            ),
            1.0e-9
        )
    }

    private fun metadataWithManualCurveLimit(limitKmH: Double): RailInfrastructureMetadata =
        RailInfrastructureMetadata(
            authoring = RailAuthoringMetadata(curveSpeedLimitAutomatic = false),
            speedLimits = SpeedLimitMetadata(
                listOf(SpeedLimitEntry(limitKmH / 3.6, SpeedLimitType.CURVE))
            )
        )
}
