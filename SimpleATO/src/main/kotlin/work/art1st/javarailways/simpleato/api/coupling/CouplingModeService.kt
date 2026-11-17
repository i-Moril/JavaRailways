package work.art1st.javarailways.simpleato.api.coupling

import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.util.annotation.MainThread
import work.art1st.javarailways.simpleato.simpleAtoWorldData

/** Ordinary coupling limits: gap between couplers in metres, approach speed in m/s. */
data class CouplingModeSettings(val maximumEntryGapMeters: Double = 5.0,
    val maximumApproachSpeedMetersPerSecond: Double = 0.5) {
    init {
        require(maximumEntryGapMeters.isFinite() && maximumEntryGapMeters >= 0.0)
        require(maximumApproachSpeedMetersPerSecond.isFinite() && maximumApproachSpeedMetersPerSecond > 0.0)
    }
}

enum class CouplingModePhase { PREPARING, APPROACHING, COMPLETED, REJECTED, RECOVERY_REQUIRED }

data class CouplingModeSnapshot(val eventId: String, val approachingTrainId: String,
    val stationaryTrainId: String, val phase: CouplingModePhase, val detail: String? = null)

sealed interface CouplingModeEntryResult {
    data class Accepted(val snapshot: CouplingModeSnapshot) : CouplingModeEntryResult
    data class Rejected(val detail: String) : CouplingModeEntryResult
}

/**
 * World-owner operations between complete physics frames; entry never waits or positions a train.
 * Both trains must already be stopped, with the approaching head behind the target's operating
 * tail on the selected track within the configured coupler gap. An existing ATO drives the
 * admitted approach automatically; an existing manual controller keeps driver input.
 *
 * Assigned trains remain dispatcher-owned. Ordinary unassigned trains create no Depot assignment.
 * Actual Core contact links them; the output stops and releases its consumed controllers, ready
 * for the ordinary manual-control entry point. Routine denial does not stall the world.
 */
interface CouplingModeService {
    @MainThread
    fun enter(approachingTrainId: String, stationaryTrainId: String): CouplingModeEntryResult

    /** Latest approach operation, or an active operation using this train as its target. */
    @MainThread
    fun status(trainId: String): CouplingModeSnapshot?
}

val World.couplingModes: CouplingModeService
    get() = checkNotNull(simpleAtoWorldData.couplingModes) { "ordinary coupling is not configured on this world" }
