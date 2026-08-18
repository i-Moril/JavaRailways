package work.art1st.javarailways.basic.rail

import kotlinx.serialization.Serializable

@Serializable
enum class RailCurveKind {
    AUTO,
    STRAIGHT,
    TWO_POINT_STRAIGHT,
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
        require(spacingMeters.isFinite() && spacingMeters > 0.0)
    }
}

@Serializable
data class RailGeometryMetadata(
    val curveKind: RailCurveKind = RailCurveKind.UNKNOWN,
    val curveRadiusMeters: Double? = null,
    val automaticSuperelevation: Boolean = false,
    val maxSuperelevationMeters: Double = 0.15,
    val allowableCantDeficiencyMeters: Double = 0.075,
    val parallel: ParallelRailMetadata? = null
) {
    init {
        require(curveRadiusMeters == null || curveRadiusMeters.isFinite() && curveRadiusMeters > 0.0)
        require(maxSuperelevationMeters.isFinite() && maxSuperelevationMeters >= 0.0)
        require(allowableCantDeficiencyMeters.isFinite() && allowableCantDeficiencyMeters >= 0.0)
    }
}

@Serializable
enum class RailAssemblyKind {
    FIVE_SEGMENT_CURVE
}

@Serializable
enum class RailAssemblyRole {
    LEADING_TANGENT,
    CURVE,
    TRAILING_TANGENT
}

@Serializable
data class RailAssemblyMetadata(
    val groupId: String,
    val kind: RailAssemblyKind,
    val laneIndex: Int,
    val laneCount: Int,
    val segmentIndex: Int,
    val segmentCount: Int,
    val role: RailAssemblyRole
) {
    init {
        require(groupId.isNotBlank())
        require(laneCount > 0 && laneIndex in 0 until laneCount)
        require(segmentCount > 0 && segmentIndex in 0 until segmentCount)
        require(role != RailAssemblyRole.LEADING_TANGENT || segmentIndex == 0)
        require(role != RailAssemblyRole.TRAILING_TANGENT || segmentIndex == segmentCount - 1)
    }
}

@Serializable
data class RailSplitMetadata(
    val operationId: String,
    val segmentIndex: Int,
    val parent: RailSplitMetadata? = null,
    /** Original logical role needed to rejoin assemblies after a reversible split. */
    val sourceAssemblyRole: RailAssemblyRole? = null
) {
    init {
        require(operationId.isNotBlank())
        require(segmentIndex in 0..1)
    }
}

@Serializable
data class RailAuthoringMetadata(
    val designSpeedMetersPerSecond: Double? = null,
    val geometry: RailGeometryMetadata = RailGeometryMetadata(),
    val assembly: RailAssemblyMetadata? = null,
    val createdBySplit: RailSplitMetadata? = null,
    val gradeLockedAtA: Boolean = false,
    val gradeLockedAtB: Boolean = false,
    val curveSpeedLimitAutomatic: Boolean = true
) {
    constructor(
        curveKind: RailCurveKind,
        designSpeedMetersPerSecond: Double? = null,
        gradeLockedAtA: Boolean = false,
        gradeLockedAtB: Boolean = false
    ) : this(
        designSpeedMetersPerSecond = designSpeedMetersPerSecond,
        geometry = RailGeometryMetadata(curveKind = curveKind),
        gradeLockedAtA = gradeLockedAtA,
        gradeLockedAtB = gradeLockedAtB
    )

    init {
        require(
            designSpeedMetersPerSecond == null ||
                designSpeedMetersPerSecond.isFinite() && designSpeedMetersPerSecond > 0.0
        )
    }
}
