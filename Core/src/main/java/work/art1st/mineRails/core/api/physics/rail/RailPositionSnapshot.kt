package work.art1st.mineRails.core.api.physics.rail

import java.util.UUID

/** Immutable route-oriented position captured from published rail state. */
data class RailPositionSnapshot(
    val railId: UUID,
    val facing: ViewDirection,
    /** Arc length in the rail's canonical AB curve, in metres. */
    val arcLengthInAbMeters: Double
) {
    init {
        require(arcLengthInAbMeters.isFinite() && arcLengthInAbMeters >= 0.0) {
            "arcLengthInAbMeters must be finite and non-negative"
        }
    }

    companion object {
        /** Capture [location] without retaining its live Rail reference. */
        fun capture(location: RailLocation, facing: ViewDirection): RailPositionSnapshot =
            RailPositionSnapshot(
                railId = location.rail.id,
                facing = facing,
                arcLengthInAbMeters = location.arcLengthIn(ViewDirection.AB)
            )
    }
}
