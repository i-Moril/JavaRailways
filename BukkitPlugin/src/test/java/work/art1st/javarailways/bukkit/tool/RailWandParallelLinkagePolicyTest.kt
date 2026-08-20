package work.art1st.javarailways.bukkit.tool

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.rail.RailAuthoringMetadata
import work.art1st.javarailways.basic.rail.RailInfrastructureMetadata
import work.art1st.javarailways.basic.rail.SpeedLimitEntry
import work.art1st.javarailways.basic.rail.SpeedLimitMetadata
import work.art1st.javarailways.basic.rail.SpeedLimitType

class RailWandParallelLinkagePolicyTest {
    @Test
    fun `creation curve speed is a minimum while edit speed is an exact value`() {
        val automatic = 100.0 / 3.6

        assertEquals(
            automatic,
            resolveCurveOperatingLimitMetersPerSecond(
                automaticLimit = automatic,
                requestedLimit = 80.0 / 3.6,
                automatic = false,
                creationMinimum = true
            ),
            1.0e-9
        )
        assertEquals(
            80.0 / 3.6,
            resolveCurveOperatingLimitMetersPerSecond(
                automaticLimit = automatic,
                requestedLimit = 80.0 / 3.6,
                automatic = false,
                creationMinimum = false
            ),
            1.0e-9
        )
        val creationFailure = assertThrows(IllegalArgumentException::class.java) {
            resolveCurveOperatingLimitMetersPerSecond(
                automaticLimit = automatic,
                requestedLimit = 120.0 / 3.6,
                automatic = false,
                creationMinimum = true
            )
        }
        assertTrue(creationFailure.message!!.contains("自动生成的几何无法满足"))
        assertTrue(creationFailure.message!!.contains("自动运营限速为 100.0 km/h"))

        val rebuildFailure = assertThrows(IllegalArgumentException::class.java) {
            resolveCurveOperatingLimitMetersPerSecond(
                automaticLimit = automatic,
                requestedLimit = 120.0 / 3.6,
                automatic = false,
                creationMinimum = false
            )
        }
        assertTrue(rebuildFailure.message!!.contains("轨道重建后仍无法满足"))
        assertTrue(rebuildFailure.message!!.contains("重算后的自动运营限速为 100.0 km/h"))
        assertTrue(rebuildFailure.message!!.contains("调整锚点、半径或超高"))
    }

    @Test
    fun `edit speed rebuilds geometry only above automatic operating limit`() {
        assertFalse(curveSpeedRequiresGeometryRebuild(80.0, false, 100.0))
        assertFalse(curveSpeedRequiresGeometryRebuild(120.0, true, 100.0))
        assertTrue(curveSpeedRequiresGeometryRebuild(120.0, false, 100.0))
    }

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
        assertFalse(curveSpeedAutomaticForRebuiltLane(1, 0, true, companionLane))
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
