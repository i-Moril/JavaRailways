package work.art1st.javarailways.basic.api.infrastructure

import java.util.UUID
import java.util.Collections

/** Stable infrastructure identity used by applied plans, live claims, and edit guards. */
sealed interface InfrastructureResource {
    /** Returns true when both identities protect any common infrastructure. */
    fun overlaps(other: InfrastructureResource): Boolean
}

/**
 * A closed interval measured in metres from a rail's canonical A end.
 *
 * The endpoints are inclusive so an edit at a shared boundary cannot slip between two guards.
 */
data class RailIntervalResource(
    val railId: UUID,
    val startAbMeters: Double,
    val endAbMeters: Double
) : InfrastructureResource {
    init {
        require(startAbMeters.isFinite() && endAbMeters.isFinite())
        require(startAbMeters >= 0.0) { "startAbMeters must be non-negative" }
        require(endAbMeters >= startAbMeters) { "endAbMeters must not precede startAbMeters" }
    }

    override fun overlaps(other: InfrastructureResource): Boolean =
        other is RailIntervalResource && railId == other.railId &&
            startAbMeters <= other.endAbMeters && other.startAbMeters <= endAbMeters
}

/** Stable reference to a topology node, controlled resource, signal, region, or operational object. */
data class StableInfrastructureResource(
    val typeId: String,
    val resourceId: String
) : InfrastructureResource {
    init {
        require(TYPE_ID.matches(typeId)) { "typeId must be a namespaced stable identifier" }
        require(resourceId.isNotBlank()) { "resourceId must not be blank" }
    }

    override fun overlaps(other: InfrastructureResource): Boolean =
        other is StableInfrastructureResource && typeId == other.typeId && resourceId == other.resourceId

    private companion object {
        val TYPE_ID = Regex("[a-z0-9_.-]+:[a-z0-9_./-]+")
    }
}

/** Stable logical owner and its applied/request revision. */
data class InfrastructureOwner(
    val ownerId: String,
    val revision: Long
) {
    init {
        require(OWNER_ID.matches(ownerId)) { "ownerId must be namespaced" }
        require(revision >= 0L) { "revision must be non-negative" }
    }

    private companion object {
        val OWNER_ID = Regex("[a-z0-9_.-]+:[A-Za-z0-9_./-]+")
    }
}

/** Complete immutable footprint. Empty footprints are rejected rather than creating invisible locks. */
class InfrastructureFootprint(resources: Collection<InfrastructureResource>) {
    val resources: List<InfrastructureResource> = Collections.unmodifiableList(ArrayList(resources))

    init {
        require(this.resources.isNotEmpty()) { "infrastructure footprint must not be empty" }
        require(this.resources.distinct().size == this.resources.size) {
            "infrastructure footprint contains duplicate resources"
        }
    }

    override fun equals(other: Any?): Boolean =
        other is InfrastructureFootprint && resources == other.resources

    override fun hashCode(): Int = resources.hashCode()

    override fun toString(): String = "InfrastructureFootprint(resources=$resources)"
}

/** Atomic shared applied-plan lease request pinned to one authoritative topology revision. */
data class InfrastructureLeaseRequest(
    val topologyRevision: Long,
    val owner: InfrastructureOwner,
    val footprint: InfrastructureFootprint
) {
    init {
        require(topologyRevision >= 0L) { "topologyRevision must be non-negative" }
    }
}

/** Request for an exclusive permit held across the complete infrastructure mutation transaction. */
data class InfrastructureEditRequest(
    val topologyRevision: Long,
    val owner: InfrastructureOwner,
    val footprint: InfrastructureFootprint
) {
    init {
        require(topologyRevision >= 0L) { "topologyRevision must be non-negative" }
    }
}

enum class InfrastructureBlockerKind {
    /** Shared long-lived reference held by an applied operational plan. */
    APPLIED_PLAN_LEASE,
    /** Exclusive resource use held by an admitted runtime route. */
    LIVE_RUNTIME_CLAIM,
    /** Another infrastructure mutation is currently in progress. */
    EDIT_PERMIT
}

/** One owner/revision preventing an edit or exclusive live claim. */
class InfrastructureBlocker(
    val kind: InfrastructureBlockerKind,
    val owner: InfrastructureOwner,
    overlappingResources: Collection<InfrastructureResource>
) {
    val overlappingResources: List<InfrastructureResource> =
        Collections.unmodifiableList(ArrayList(overlappingResources))

    init {
        require(this.overlappingResources.isNotEmpty())
    }
}

enum class InfrastructureMutationStatus {
    /** A new complete footprint was recorded. */
    ACQUIRED,
    /** An expected older owner revision was atomically replaced. */
    REPLACED,
    /** The exact owner revision was released. */
    RELEASED,
    /** The identical request was already held by the same platform owner. */
    UNCHANGED,
    /** The world is unloaded, disabled, or was never registered. */
    WORLD_UNAVAILABLE,
    /** The request revision differs from the world's authoritative topology revision. */
    STALE_TOPOLOGY,
    /** A referenced rail/object does not exist according to its authoritative provider. */
    INVALID_RESOURCE,
    /** An authoritative resource provider threw while validating; nothing was acquired. */
    RESOURCE_PROVIDER_FAILED,
    /** The logical owner, expected revision, or platform owner did not match. */
    OWNER_REVISION_CONFLICT,
    /** At least one overlapping lease, runtime claim, or edit permit blocks the operation. */
    RESOURCE_CONFLICT,
    /** The exact lease or claim requested for release/replacement was not found. */
    NOT_FOUND,
    /** The world-local registry has already been closed. */
    SERVICE_CLOSED
}

/** Typed result; every non-successful mutation leaves the registry unchanged. */
class InfrastructureMutationResult(
    val status: InfrastructureMutationStatus,
    blockers: Collection<InfrastructureBlocker> = emptyList(),
    val detail: String = ""
) {
    val blockers: List<InfrastructureBlocker> = Collections.unmodifiableList(ArrayList(blockers))

    val successful: Boolean
        get() = status in SUCCESS_STATUSES

    init {
        require(status == InfrastructureMutationStatus.RESOURCE_CONFLICT || this.blockers.isEmpty()) {
            "blockers are only valid for RESOURCE_CONFLICT"
        }
    }

    private companion object {
        val SUCCESS_STATUSES = setOf(
            InfrastructureMutationStatus.ACQUIRED,
            InfrastructureMutationStatus.REPLACED,
            InfrastructureMutationStatus.RELEASED,
            InfrastructureMutationStatus.UNCHANGED
        )
    }
}

/** Immutable read-only view of one applied-plan lease. */
data class InfrastructureLeaseSnapshot(
    val owner: InfrastructureOwner,
    val footprint: InfrastructureFootprint
)

/** Immutable read-only diagnostic view of one live route claim. */
data class InfrastructureClaimSnapshot(
    val owner: InfrastructureOwner,
    val footprint: InfrastructureFootprint
)
