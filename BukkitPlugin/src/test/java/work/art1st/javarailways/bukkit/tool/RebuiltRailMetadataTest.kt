package work.art1st.javarailways.bukkit.tool

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.rail.RailAuthoringMetadata
import work.art1st.javarailways.basic.rail.RailInfrastructureMetadata
import work.art1st.javarailways.basic.rail.SpeedLimitEntry
import work.art1st.javarailways.basic.rail.SpeedLimitMetadata
import work.art1st.javarailways.basic.rail.SpeedLimitType
import work.art1st.javarailways.basic.safety.RailSpeedLimits

class RebuiltRailMetadataTest {
    @Test
    fun `curve rebuild preserves other groups and non curve limits`() {
        val original = RailInfrastructureMetadata(
            authoring = RailAuthoringMetadata(designSpeedMetersPerSecond = 30.0),
            speedLimits = SpeedLimitMetadata(listOf(
                SpeedLimitEntry(20.0, SpeedLimitType.SUBGRADE),
                SpeedLimitEntry(10.0, SpeedLimitType.CURVE)
            ))
        )

        val rebuilt = original.withRebuiltCurveSpeedLimits(RailSpeedLimits(25.0, 35.0))

        assertEquals(original.authoring, rebuilt.authoring)
        assertEquals(20.0, rebuilt.speedLimits.value(SpeedLimitType.SUBGRADE))
        assertEquals(25.0, rebuilt.speedLimits.value(SpeedLimitType.CURVE))
        assertEquals(35.0, rebuilt.curveSafety.baseCurveDerailSpeedMetersPerSecond)
        assertNull(original.withRebuiltCurveSpeedLimits(RailSpeedLimits(30.0, null))
            .speedLimits.value(SpeedLimitType.CURVE))
    }

    @Test
    fun `curve rebuild does not invent authoring metadata`() {
        val rebuilt = RailInfrastructureMetadata.EMPTY.withRebuiltCurveSpeedLimits(RailSpeedLimits(25.0, null))

        assertNull(rebuilt.authoring)
        assertEquals(25.0, rebuilt.speedLimits.value(SpeedLimitType.CURVE))
        assertNull(rebuilt.curveSafety.baseCurveDerailSpeedMetersPerSecond)
    }
}
