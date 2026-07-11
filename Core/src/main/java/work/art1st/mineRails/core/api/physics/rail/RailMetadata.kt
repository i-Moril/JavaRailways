package work.art1st.mineRails.core.api.physics.rail

import kotlinx.serialization.Serializable

/** Infrastructure properties persisted with one rail segment. */
@Serializable
enum class RailKind {
    NORMAL,
    TURNOUT_BRANCH
}

@Serializable
enum class RailCurveKind {
    AUTO,
    STRAIGHT,
    FIVE_SEGMENT,
    S_CURVE,
    TURNOUT,
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
    val parallel: ParallelRailMetadata? = null
) {
    init {
        require(curveRadiusMeters == null || curveRadiusMeters > 0.0)
        require(maxSuperelevationMeters >= 0.0)
    }
}

@Serializable
data class RailMetadata(
    /** Design speed in metres per second. Null means unspecified. */
    val designSpeedMetersPerSecond: Double? = null,
    val kind: RailKind = RailKind.NORMAL,
    val geometry: RailGeometryMetadata = RailGeometryMetadata()
) {
    init {
        require(designSpeedMetersPerSecond == null || designSpeedMetersPerSecond > 0.0) {
            "designSpeedMetersPerSecond must be > 0 when specified"
        }
    }
}
