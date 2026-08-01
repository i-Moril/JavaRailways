package work.art1st.javarailways.core.api.physics.rail

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
data class RailSplitMetadata(
    /** Identifies the split operation that produced the two current sibling segments. */
    val operationId: String,
    /** 0 is the segment before the split point in AB order; 1 is the segment after it. */
    val segmentIndex: Int,
    /** Previous lineage restored when these siblings are merged again. */
    val parent: RailSplitMetadata? = null
) {
    init {
        require(operationId.isNotBlank())
        require(segmentIndex in 0..1)
    }
}

@Serializable
data class TurnoutEndpointMetadata(
    val protectionDistanceMeters: Double,
    val lockingDistanceMeters: Double
) {
    init {
        require(protectionDistanceMeters.isFinite() && protectionDistanceMeters >= 0.0)
        require(lockingDistanceMeters.isFinite() && lockingDistanceMeters >= 0.0)
        require(lockingDistanceMeters <= protectionDistanceMeters)
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
    /** Null allows both directions; otherwise only the selected RailView direction is legal. */
    val allowedDirection: ViewDirection? = null,
    val geometry: RailGeometryMetadata = RailGeometryMetadata(),
    /** Non-null when this segment was created by RailWand split mode. */
    val createdBySplit: RailSplitMetadata? = null,
    /** Turnout settings at the canonical AB start endpoint. */
    val turnoutAtA: TurnoutEndpointMetadata? = null,
    /** Turnout settings at the canonical AB end endpoint. */
    val turnoutAtB: TurnoutEndpointMetadata? = null
) {
    init {
        require(designSpeedMetersPerSecond == null || designSpeedMetersPerSecond > 0.0)
        require(speedLimitMetersPerSecond == null || speedLimitMetersPerSecond >= 0.0)
        require(derailSpeedMetersPerSecond == null || derailSpeedMetersPerSecond >= 0.0)
    }
}
