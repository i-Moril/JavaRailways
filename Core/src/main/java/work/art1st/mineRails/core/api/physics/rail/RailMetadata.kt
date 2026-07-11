package work.art1st.mineRails.core.api.physics.rail

import kotlinx.serialization.Serializable

/** Infrastructure properties persisted with one rail segment. */
@Serializable
enum class RailCurveKind {
    AUTO,
    STRAIGHT,
    FIVE_SEGMENT,
    S_CURVE,
    UNKNOWN
}

@Serializable
data class ParallelRailMetadata(
    val groupId: String,
    val index: Int,
    val count: Int,
    val spacingMeters: Double
) {
    init {
        require(groupId.isNotBlank())
        require(count > 1)
        require(index in 0 until count)
        require(spacingMeters > 0.0)
    }
}

@Serializable
data class RailGeometryMetadata(
    val curveKind: RailCurveKind = RailCurveKind.UNKNOWN,
    /** Null means automatic radius selection. */
    val curveRadiusMeters: Double? = null,
    val straightUsesChordDirection: Boolean = false,
    val automaticSuperelevation: Boolean = false,
    val maxSuperelevationMeters: Double = 0.15,
    val allowableCantDeficiencyMeters: Double = 0.075,
    val parallel: ParallelRailMetadata? = null
) {
    init {
        require(curveRadiusMeters == null || curveRadiusMeters > 0.0)
        require(maxSuperelevationMeters >= 0.0)
        require(allowableCantDeficiencyMeters >= 0.0)
    }
}

@Serializable
data class RailMetadata(
    /** Requested design speed before geometry-derived limits are applied. */
    val designSpeedMetersPerSecond: Double? = null,
    /** Effective operating limit after geometry, cant deficiency and infrastructure limits. */
    val speedLimitMetersPerSecond: Double? = designSpeedMetersPerSecond,
    /** Hard geometry-derived derail threshold; null means no curvature derail threshold. */
    val derailSpeedMetersPerSecond: Double? = null,
    val geometry: RailGeometryMetadata = RailGeometryMetadata()
) {
    init {
        require(designSpeedMetersPerSecond == null || designSpeedMetersPerSecond > 0.0)
        require(speedLimitMetersPerSecond == null || speedLimitMetersPerSecond >= 0.0)
        require(derailSpeedMetersPerSecond == null || derailSpeedMetersPerSecond >= 0.0)
    }
}
