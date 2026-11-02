package work.art1st.javarailways.simpleato.api.station

/** Main-thread result of resolving and queueing one StationCall for physics activation. */
sealed interface StationCallSubmissionResult {
    data class Accepted(val resolved: ResolvedStationCall) : StationCallSubmissionResult
    data class StaleRevision(
        val submittedRevision: Long,
        val latestAcceptedRevision: Long
    ) : StationCallSubmissionResult
    data class ResolutionFailure(
        val reason: StationCallResolutionFailure,
        val message: String
    ) : StationCallSubmissionResult
}

object StationBerthDetector {
    fun isBerthed(
        headPositionErrorMeters: Double,
        speedMetersPerSecond: Double
    ): Boolean {
        require(headPositionErrorMeters.isFinite())
        require(speedMetersPerSecond.isFinite() && speedMetersPerSecond >= 0.0)
        return kotlin.math.abs(headPositionErrorMeters) <= work.art1st.javarailways.basic.api.ato.AtoSpatialPolicy.POSITION_TOLERANCE_METERS &&
            speedMetersPerSecond == 0.0
    }
}

enum class StationCallNotificationType {
    APPROACHING,
    BERTHED,
    DEPARTED,
    REVERSE_READY
}

/** Immutable physics-to-main notification for one monotonic StationCall transition. */
data class StationCallNotification(
    val type: StationCallNotificationType,
    val callId: String,
    val missionRevision: Long,
    val trainId: String,
    val stationId: StationId,
    val berthRef: StationBerthRef,
    val physicsEpoch: Long,
    val speedMetersPerSecond: Double,
    /** Signed head error: positive means the stopping point is still ahead. */
    val headPositionErrorMeters: Double
) {
    init {
        require(callId.isNotBlank())
        require(missionRevision >= 0L)
        require(trainId.isNotBlank())
        require(berthRef.stationId == stationId)
        require(physicsEpoch >= 0L)
        require(speedMetersPerSecond.isFinite() && speedMetersPerSecond >= 0.0)
        require(headPositionErrorMeters.isFinite())
    }
}
