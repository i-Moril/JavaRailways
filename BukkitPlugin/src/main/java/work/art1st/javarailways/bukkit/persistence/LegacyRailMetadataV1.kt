package work.art1st.javarailways.bukkit.persistence

import kotlinx.serialization.Serializable
import work.art1st.javarailways.basic.rail.RailAssemblyMetadata
import work.art1st.javarailways.basic.rail.RailAuthoringMetadata
import work.art1st.javarailways.basic.rail.RailOperatingMetadata
import work.art1st.javarailways.basic.rail.CurveSafetyMetadata
import work.art1st.javarailways.basic.rail.SpeedLimitEntry
import work.art1st.javarailways.basic.rail.SpeedLimitMetadata
import work.art1st.javarailways.basic.rail.SpeedLimitType
import work.art1st.javarailways.basic.rail.RailGeometryMetadata
import work.art1st.javarailways.basic.rail.RailInfrastructureMetadata
import work.art1st.javarailways.basic.rail.RailSplitMetadata
import work.art1st.javarailways.basic.rail.TurnoutEndpointMetadata
import work.art1st.javarailways.core.api.physics.rail.ViewDirection

/** Migration-only shape of the metadata embedded in Core infrastructure format 1. */
@Serializable
internal data class LegacyRailMetadataV1(
    val designSpeedMetersPerSecond: Double? = null,
    val speedLimitMetersPerSecond: Double? = designSpeedMetersPerSecond,
    val derailSpeedMetersPerSecond: Double? = null,
    val allowedDirection: ViewDirection? = null,
    val geometry: RailGeometryMetadata = RailGeometryMetadata(),
    val assembly: RailAssemblyMetadata? = null,
    val createdBySplit: RailSplitMetadata? = null,
    val turnoutAtA: TurnoutEndpointMetadata? = null,
    val turnoutAtB: TurnoutEndpointMetadata? = null,
    val gradeLockedAtA: Boolean = false,
    val gradeLockedAtB: Boolean = false
) {
    fun toCurrent(): RailInfrastructureMetadata = RailInfrastructureMetadata(
        authoring = RailAuthoringMetadata(
            designSpeedMetersPerSecond = designSpeedMetersPerSecond,
            geometry = geometry,
            assembly = assembly,
            createdBySplit = createdBySplit,
            gradeLockedAtA = gradeLockedAtA,
            gradeLockedAtB = gradeLockedAtB
        ),
        operating = RailOperatingMetadata(turnoutAtA, turnoutAtB),
        speedLimits = SpeedLimitMetadata(buildList {
            designSpeedMetersPerSecond?.let { add(SpeedLimitEntry(it, SpeedLimitType.SUBGRADE)) }
            speedLimitMetersPerSecond?.takeIf {
                designSpeedMetersPerSecond == null || it < designSpeedMetersPerSecond
            }?.let { add(SpeedLimitEntry(it, SpeedLimitType.CURVE)) }
        }),
        curveSafety = CurveSafetyMetadata(derailSpeedMetersPerSecond)
    )
}
