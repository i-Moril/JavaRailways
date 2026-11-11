package work.art1st.javarailways.core.api.physics.infrastructure

import java.util.UUID
import java.util.Collections

/** Stable infrastructure identity used by live claims and runtime resource arbitration. */
sealed interface InfrastructureResource {
    /** Returns true when both identities protect any common infrastructure. */
    fun overlaps(other: InfrastructureResource): Boolean
}

/**
 * One closed interval on exactly one rail, measured in metres from that rail's canonical A end.
 *
 * The endpoints are inclusive so an edit at a shared boundary cannot slip between two guards.
 * A range that follows a route across multiple rails is represented by one [InfrastructureFootprint]
 * containing one interval per traversed rail. If the route also crosses a turnout, diamond, or other
 * shared conflict node, the footprint must additionally contain its [StableInfrastructureResource];
 * the adjacent rail intervals alone do not express that shared-node conflict.
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

/**
 * Stable identity of an actual runtime or operational resource.
 *
 * Examples include a turnout actuator/conflict object or a signal-system-owned interlocking
 * resource. Structural topology nodes are referenced separately by [StableTopologyNodeKey].
 */
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

/**
 * Complete immutable live-claim resource set acquired or released as one atomic unit.
 *
 * For example, a protected range from rail A's 80 m point through its end and then to rail B's
 * 35 m point contains `RailIntervalResource(A, 80, lengthA)` and
 * `RailIntervalResource(B, 0, 35)`. Any required turnout or crossing resource is included in the
 * same footprint. Failure of any member rejects the whole request; no prefix is retained.
 */
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

enum class InfrastructureBlockerKind {
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
    /** An existing footprint was atomically replaced, possibly without changing its owner revision. */
    REPLACED,
    /** The exact owner revision was released. */
    RELEASED,
    /** The identical request was already held by the same platform owner. */
    UNCHANGED,
    /** The world is unloaded, disabled, or was never registered. */
    WORLD_UNAVAILABLE,
    /** A referenced rail/object does not exist according to its authoritative provider. */
    INVALID_RESOURCE,
    /** An authoritative resource provider threw while validating; nothing was acquired. */
    RESOURCE_PROVIDER_FAILED,
    /** The logical owner, expected revision, or platform owner did not match. */
    OWNER_REVISION_CONFLICT,
    /** At least one overlapping runtime claim or edit permit blocks the operation. */
    RESOURCE_CONFLICT,
    /** The exact live claim requested for release or update was not found. */
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

/** Immutable read-only diagnostic view of one live route claim. */
data class InfrastructureClaimSnapshot(
    val owner: InfrastructureOwner,
    val footprint: InfrastructureFootprint
)
