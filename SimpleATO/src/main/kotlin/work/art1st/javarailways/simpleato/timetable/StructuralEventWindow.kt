package work.art1st.javarailways.simpleato.timetable

import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot
import kotlin.math.abs

/** Existing Dispatcher Check/acquisition tolerance; structural eligibility shares this exact bound. */
internal const val TIMETABLE_ORIGIN_TOLERANCE_METERS = work.art1st.javarailways.basic.api.ato.AtoSpatialPolicy.POSITION_TOLERANCE_METERS

/**
 * An explicit same-rail structural eligibility window, independent of a controller's arrival
 * tolerance. It never certifies a source body or permits another topology/rail/direction.
 */
internal class StructuralEventWindow private constructor(
    val marker: RailPositionSnapshot,
    val lowerAbMeters: Double,
    val upperAbMeters: Double,
    private val topology: TopologyState
) {
    val forwardBoundary: RailPositionSnapshot get() = marker.copy(arcLengthInAbMeters =
        if (marker.facing == ViewDirection.AB) upperAbMeters else lowerAbMeters)

    fun contains(position: RailPositionSnapshot, currentTopology: TopologyState): Boolean =
        currentTopology === topology && position.railId == marker.railId && position.facing == marker.facing &&
            position.arcLengthInAbMeters in lowerAbMeters..upperAbMeters

    fun acceptsSource(frame: TrainFrameSnapshot, currentTopology: TopologyState): Boolean =
        !frame.derailed && frame.speedMetersPerSecond == 0.0 && contains(frame.head, currentTopology)

    companion object {
        fun at(marker: RailPositionSnapshot, topology: TopologyState,
            stationPositionToleranceMeters: Double? = null): StructuralEventWindow {
            require(stationPositionToleranceMeters == null ||
                stationPositionToleranceMeters.isFinite() && stationPositionToleranceMeters > 0.0)
            val rail = topology.railProjection[marker.railId]?.singleOrNull()
                ?: throw IllegalArgumentException("structural event marker has no unique physical rail")
            val length = abs(rail.railEndAbMeters - rail.railStartAbMeters)
            require(marker.arcLengthInAbMeters in 0.0..length) { "structural event marker lies outside physical rail" }
            val tolerance = minOf(TIMETABLE_ORIGIN_TOLERANCE_METERS,
                stationPositionToleranceMeters ?: TIMETABLE_ORIGIN_TOLERANCE_METERS)
            return StructuralEventWindow(marker, maxOf(0.0, marker.arcLengthInAbMeters - tolerance),
                minOf(length, marker.arcLengthInAbMeters + tolerance), topology)
        }
    }
}
