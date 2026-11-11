package work.art1st.javarailways.simpleato.api.station

import work.art1st.javarailways.core.api.util.annotation.MainThread

enum class StationStopSelectionPolicy { FIXED_PREFERENCE, AVAILABLE_PRIORITY }

data class StationStopCandidate(val berthId: String, val priority: Int) {
    init {
        require(berthId.isNotBlank() && berthId == berthId.trim() && berthId.none(Char::isISOControl))
        require(priority >= 0)
    }
}

/** Authored finite alternatives. Priority ties use stable berth ID, never mutable occupancy order. */
class StationStopSelection(
    val stationId: StationId,
    candidates: Collection<StationStopCandidate>,
    val policy: StationStopSelectionPolicy
) {
    val candidates: List<StationStopCandidate> = java.util.List.copyOf(candidates)
    init {
        require(this.candidates.size in 1..64) { "Station selection requires 1..64 candidates" }
        require(this.candidates.map { it.berthId }.distinct().size == this.candidates.size)
        this.candidates.forEach { StationBerthRef(stationId, it.berthId) }
    }
    override fun equals(other: Any?) = other is StationStopSelection && stationId == other.stationId &&
        candidates == other.candidates && policy == other.policy
    override fun hashCode() = 31 * (31 * stationId.hashCode() + candidates.hashCode()) + policy.hashCode()
}

data class StationStopCandidateRejection(
    val berthRef: StationBerthRef,
    val reason: StationCallResolutionFailure,
    val message: String
)

sealed interface StationStopSelectionResult {
    data class Selected(val resolved: ResolvedStationCall, val key: StationBerthRegistrationKey) : StationStopSelectionResult
    class NoEligibleCandidate(rejections: Collection<StationStopCandidateRejection>) : StationStopSelectionResult {
        val rejections: List<StationStopCandidateRejection> = java.util.List.copyOf(rejections)
    }
    /** Existing owner is retained until its normal stopped/cancellation/tail-release boundary. */
    data class ExistingSelectionMismatch(val held: StationBerthRegistrationKey) : StationStopSelectionResult
}

/**
 * Select one berth from exact resolver results. The caller supplies real train length and
 * retained incoming evidence to StationCallResolver, and validates every onward continuation before
 * this boundary. This helper does
 * not grant physical authority or exclude other calls targeting the same berth. AVAILABLE_PRIORITY
 * retries alternatives at the caller on real admission rejection, before the first physical grant.
 */
class StationStopSelector(private val registry: StationCallRegistry) {
    @MainThread
    fun select(
        selection: StationStopSelection,
        owner: StationBerthOwner,
        missionRevision: Long,
        resolutions: Map<String, StationCallResolutionResult>
    ): StationStopSelectionResult {
        require(missionRevision >= 0)
        require(resolutions.keys == selection.candidates.map { it.berthId }.toSet()) {
            "Every declared candidate requires exactly one current resolution"
        }
        val successes = linkedMapOf<StationBerthRegistrationKey, ResolvedStationCall>()
        val rejections = arrayListOf<StationStopCandidateRejection>()
        for (candidate in selection.candidates.sortedWith(compareBy({ it.priority }, { it.berthId }))) {
            val berth = StationBerthRef(selection.stationId, candidate.berthId)
            when (val result = resolutions.getValue(candidate.berthId)) {
                is StationCallResolutionResult.Failure -> rejections += StationStopCandidateRejection(berth, result.reason, result.message)
                is StationCallResolutionResult.Success -> {
                    val resolved = result.resolved
                    require(resolved.berthRef == berth && resolved.call.callId == owner.callId &&
                        resolved.call.missionRevision == missionRevision && resolved.request.serviceRunId == owner.serviceRunId) {
                        "Candidate resolution belongs to a different call, berth or revision"
                    }
                    successes[StationBerthRegistrationKey(owner, berth, missionRevision)] = resolved
                }
            }
        }
        if (successes.isEmpty()) return StationStopSelectionResult.NoEligibleCandidate(rejections)
        return when (val result = registry.registerChoice(successes.keys.toList())) {
            is StationBerthChoiceRegistration.Confirmed -> StationStopSelectionResult.Selected(successes.getValue(result.key), result.key)
            is StationBerthChoiceRegistration.ExistingMismatch -> StationStopSelectionResult.ExistingSelectionMismatch(result.held)
        }
    }
}
