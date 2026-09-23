package work.art1st.javarailways.basic.api.route

import work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureResource
import work.art1st.javarailways.basic.api.observation.RailwayStateStamp
import work.art1st.javarailways.core.api.util.annotation.MainThread
import java.util.Collections

/**
 * Opaque identity of one in-flight admission attempt.
 *
 * Only the Basic host creates handles. A caller may pass the received handle back to the same
 * world-local [RouteResourceAdmissionService]; it has no serialized form, persistence lifetime,
 * retry meaning, or validity in another process or service instance.
 */
class RouteAdmissionHandle internal constructor()

/**
 * Opaque identity of one live committed route grant.
 *
 * Only the Basic host creates grant IDs. The ID addresses process-local live state and is not a
 * persistence key, network token, or request-retry identity.
 */
class RouteGrantId internal constructor()

/**
 * Immutable caller-visible state of one committed route grant.
 *
 * [committedAuthority] is measured in route metres using the same traversal-oriented coordinate as
 * [RouteResourceIntent.desiredAuthority]. A pending request continues to expose the previous
 * snapshot, if any, until its new resources are ready and a new snapshot is published.
 *
 * @property grantId opaque identity of the host-owned live grant
 * @property owner logical grant owner and applied/request revision
 * @property trainId stable train identity from the coherent observation
 * @property routeId identity of the frozen selected route
 * @property routeRevision revision of that selected traversal
 * @property observationStamp coherent observation used to commit this grant version
 * @property committedAuthority route-metre boundary currently available to the train
 */
data class RouteGrantSnapshot(
    val grantId: RouteGrantId,
    val owner: InfrastructureOwner,
    val trainId: String,
    val routeId: String,
    val routeRevision: Long,
    val observationStamp: RailwayStateStamp,
    val committedAuthority: RouteBoundary
) {
    init {
        require(trainId.isNotBlank()) { "trainId must not be blank" }
        require(routeId.isNotBlank()) { "routeId must not be blank" }
        require(routeRevision >= 0L) { "routeRevision must be non-negative" }
    }
}

/** Stable public reasons for rejecting or failing a generic route admission. */
enum class RouteAdmissionFailureCode {
    WORLD_UNAVAILABLE,
    OWNER_MISMATCH,
    STALE_OBSERVATION,
    TRAIN_NOT_FOUND,
    STALE_TOPOLOGY,
    INVALID_ROUTE,
    INVALID_BOUNDARY,
    INVALID_INFRASTRUCTURE,
    INVALID_ADDITION,
    INVALID_PREPARATION,
    PHYSICALLY_OCCUPIED,
    RESOURCE_CONFLICT,
    ROUTE_STATE_MISMATCH,
    REQUEST_BUSY,
    PREPARATION_ABORTED_SAFE,
    WORLD_STALLED
}

/**
 * Immutable identity explaining an occupancy or claim conflict without exposing a live registry,
 * train, world, or claim object.
 */
sealed interface RouteAdmissionBlocker {
    /** Stable ID of a train physically occupying the requested route geometry. */
    data class OccupyingTrain(val trainId: String) : RouteAdmissionBlocker {
        init {
            require(trainId.isNotBlank()) { "blocking trainId must not be blank" }
        }
    }

    /** Exact infrastructure identity and logical owner of a conflicting committed claim. */
    data class ConflictingResource(
        val resource: InfrastructureResource,
        val owner: InfrastructureOwner
    ) : RouteAdmissionBlocker
}

/**
 * Immutable typed admission failure.
 *
 * [detail] is a diagnostic for operators rather than a programmatic status. [blockers] is copied
 * on construction and is present only for physical occupancy or resource conflicts. It contains
 * stable value identities and never exposes mutable registry state.
 *
 * @property code stable machine-readable reason
 * @property detail non-blank operator-facing diagnostic
 * @property blockers immutable conflict identities permitted only by the matching failure code
 */
@ConsistentCopyVisibility
data class RouteAdmissionFailure private constructor(
    val code: RouteAdmissionFailureCode,
    val detail: String,
    val blockers: List<RouteAdmissionBlocker>
) {
    constructor(
        code: RouteAdmissionFailureCode,
        detail: String,
        blockers: Collection<RouteAdmissionBlocker> = emptyList()
    ) : this(code, detail, immutableBlockers(blockers))

    init {
        require(detail.isNotBlank()) { "detail must not be blank" }
        when (code) {
            RouteAdmissionFailureCode.PHYSICALLY_OCCUPIED -> {
                require(blockers.isNotEmpty()) {
                    "PHYSICALLY_OCCUPIED requires an occupying-train blocker"
                }
                require(blockers.all { it is RouteAdmissionBlocker.OccupyingTrain }) {
                    "PHYSICALLY_OCCUPIED accepts only occupying-train blockers"
                }
            }

            RouteAdmissionFailureCode.RESOURCE_CONFLICT -> {
                require(blockers.isNotEmpty()) {
                    "RESOURCE_CONFLICT requires a conflicting-resource blocker"
                }
                require(blockers.all { it is RouteAdmissionBlocker.ConflictingResource }) {
                    "RESOURCE_CONFLICT accepts only conflicting-resource blockers"
                }
            }

            else -> require(blockers.isEmpty()) {
                "blockers are only valid for PHYSICALLY_OCCUPIED or RESOURCE_CONFLICT"
            }
        }
    }

    private companion object {
        fun immutableBlockers(
            source: Collection<RouteAdmissionBlocker>
        ): List<RouteAdmissionBlocker> = Collections.unmodifiableList(ArrayList(source))
    }
}

/**
 * Current result of an admission attempt, returned by both submission and later inspection.
 * A pending result carries the same process-local handle on every read; callers inspect that
 * handle rather than submitting the request again.
 */
sealed interface RouteAdmissionResult {
    /** The complete union is claimed, ready, and committed at [grant]'s authority boundary. */
    data class Granted(val grant: RouteGrantSnapshot) : RouteAdmissionResult

    /**
     * Preparation is still running under [handle]. [committedGrant] is null until this route has
     * published its first grant, and otherwise is the still-valid previous grant.
     */
    data class Pending(
        val handle: RouteAdmissionHandle,
        val committedGrant: RouteGrantSnapshot?
    ) : RouteAdmissionResult

    /** No wider authority was published. */
    data class Rejected(val failure: RouteAdmissionFailure) : RouteAdmissionResult
}

/**
 * World-local owner-thread entry point for generic route-resource admission.
 *
 * Every call is immediate and non-blocking. A call may start provider preparation and return
 * [RouteAdmissionResult.Pending], but it must not wait on device animation or another thread.
 * Pending handles and grants belong to this service instance and remain process-local. The host
 * owns live claims and their release lifecycle even if the requesting plugin is disabled; these
 * API values themselves provide no persistence or restart recovery.
 */
interface RouteResourceAdmissionService {
    /** Requests a farther authority boundary on one frozen route, with or without a prior grant. */
    @MainThread
    fun request(intent: RouteResourceIntent): RouteAdmissionResult

    /** Reads current or terminal state for a handle created by this same world-local service. */
    @MainThread
    fun progress(handle: RouteAdmissionHandle): RouteAdmissionResult
}
