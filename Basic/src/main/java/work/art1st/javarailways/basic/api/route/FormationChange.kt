package work.art1st.javarailways.basic.api.route

import work.art1st.javarailways.core.api.physics.train.CouplingApproachProof

/** Immutable stable formation identity; direction never changes stable car order. */
@ConsistentCopyVisibility
data class FormationTrainIdentity private constructor(
    val trainId: String,
    val carIds: List<String>,
    val reversed: Boolean
) {
    constructor(trainId: String, carIds: Collection<String>, reversed: Boolean) :
        this(trainId, java.util.List.copyOf(carIds), reversed)

    init {
        require(trainId.isNotBlank())
        require(carIds.isNotEmpty() && carIds.all(String::isNotBlank))
        require(carIds.distinct().size == carIds.size)
    }
}

/**
 * Fixed-event physical identities prepared under one authenticated admission service binding.
 * This DTO does not certify whole EMU units or actual control compatibility: the operating owner
 * must validate both before submission. Core still performs physical/controller preparation.
 */
sealed interface FixedFormationChangeIntent {
    val eventId: String

    data class Couple(
        override val eventId: String,
        val approachingGrantId: RouteGrantId,
        val stationaryGrantId: RouteGrantId,
        val proof: CouplingApproachProof,
        val output: FormationTrainIdentity,
        val maximumApproachSpeedMetersPerSecond: Double
    ) : FixedFormationChangeIntent {
        init {
            require(eventId.isNotBlank())
            require(approachingGrantId !== stationaryGrantId)
            require(maximumApproachSpeedMetersPerSecond.isFinite() && maximumApproachSpeedMetersPerSecond > 0.0)
        }
    }

    data class Split(
        override val eventId: String,
        val sourceGrantId: RouteGrantId,
        val source: FormationTrainIdentity,
        val beforeCarId: String,
        val retained: FormationTrainIdentity,
        val separated: FormationTrainIdentity
    ) : FixedFormationChangeIntent {
        init { require(eventId.isNotBlank() && beforeCarId.isNotBlank()) }
    }
}

/** Process-local capability, neither a persistence identity nor evidence of physical publication. */
class FormationChangeHandle internal constructor()

enum class FormationChangePhase {
    PREPARED,
    PUBLICATION_SEALED,
    PHYSICAL_OBSERVED,
    OUTPUTS_HELD,
    OUTPUTS_BOUND,
    OUTPUTS_ACTIVE,
    COMPLETED,
    ABORTED,
    RECOVERY_REQUIRED
}

/**
 * Detached evidence for the operating owner. Even OUTPUTS_HELD grants no movement authority.
 * Original claims and Core output fences remain held; live handles/fences must not be serialized.
 */
class FormationChangeSnapshot internal constructor(
    val handle: FormationChangeHandle,
    val eventId: String,
    val phase: FormationChangePhase,
    sources: Collection<FormationTrainIdentity>,
    outputs: Collection<FormationTrainIdentity>,
    outputGrants: Collection<RouteGrantSnapshot> = emptyList()
) {
    val sources: List<FormationTrainIdentity> = java.util.List.copyOf(sources)
    val outputs: List<FormationTrainIdentity> = java.util.List.copyOf(outputs)
    val outputGrants: List<RouteGrantSnapshot> = java.util.List.copyOf(outputGrants)
}

sealed interface FormationChangeResult {
    data class Accepted(val snapshot: FormationChangeSnapshot) : FormationChangeResult
    data class Rejected(val failure: RouteAdmissionFailure) : FormationChangeResult
}

enum class FormationRoutePurpose { COUPLING_APPROACH, OUTPUT_BODY, OUTPUT_DEPARTURE }

/** Host-created policy context; possession never grants a peer-occupancy exception. */
class FormationRouteContext internal constructor(
    val eventId: String,
    val purpose: FormationRoutePurpose,
    sources: Collection<FormationTrainIdentity>,
    outputs: Collection<FormationTrainIdentity>,
    retainedStableResources: Collection<work.art1st.javarailways.core.api.physics.infrastructure.StableInfrastructureResource>,
    val maximumSpeedMetersPerSecond: Double? = null
) {
    val sources: List<FormationTrainIdentity> = java.util.List.copyOf(sources)
    val outputs: List<FormationTrainIdentity> = java.util.List.copyOf(outputs)
    val retainedStableResources: List<work.art1st.javarailways.core.api.physics.infrastructure.StableInfrastructureResource> =
        java.util.List.copyOf(retainedStableResources)
}

/**
 * Provider-authenticated output clearance on the complete selected frozen route. A null boundary
 * explicitly certifies this output does not need this resource; absence is never that assertion.
 * The platform validates provider ownership. Basic verifies complete, unique resource coverage.
 */
data class FormationResourceClearance(
    val resource: work.art1st.javarailways.core.api.physics.infrastructure.StableInfrastructureResource,
    val releaseAfterTailPasses: RouteBoundary?
)

sealed interface FormationRoutePolicyResult {
    class Accepted(clearances: Collection<FormationResourceClearance>) : FormationRoutePolicyResult {
        val clearances: List<FormationResourceClearance> = java.util.List.copyOf(clearances)
    }
    data class Rejected(val failure: RouteAdmissionFailure) : FormationRoutePolicyResult
}

/**
 * Purpose-bound permission to reach one host-solved contact, not an ordinary stop-margin override.
 * Issued only after actual route/resource/provider admission. Consumers must match the exact peer
 * end, immutable target geometry and selected route; every unrelated following/signal restriction
 * remains effective. The contact boundary uses the containing grant's route coordinate.
 * @property maximumApproachSpeedMetersPerSecond Contact speed ceiling from the same admitted formation intent, in m/s.
 */
class CouplingContactAuthority internal constructor(
    val eventId: String,
    val proof: CouplingApproachProof,
    val contactBoundary: RouteBoundary,
    val maximumApproachSpeedMetersPerSecond: Double
) {
    init {
        require(maximumApproachSpeedMetersPerSecond.isFinite() && maximumApproachSpeedMetersPerSecond > 0.0)
    }
}
