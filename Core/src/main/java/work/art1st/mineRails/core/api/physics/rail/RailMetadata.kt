package work.art1st.mineRails.core.api.physics.rail

import kotlinx.serialization.Serializable

/** Infrastructure properties persisted with one rail segment. */
@Serializable
enum class RailKind {
    NORMAL,
    TURNOUT_BRANCH
}

@Serializable
data class RailMetadata(
    /** Design speed in metres per second. Null means unspecified. */
    val designSpeedMetersPerSecond: Double? = null,
    val kind: RailKind = RailKind.NORMAL
) {
    init {
        require(designSpeedMetersPerSecond == null || designSpeedMetersPerSecond > 0.0) {
            "designSpeedMetersPerSecond must be > 0 when specified"
        }
    }
}
