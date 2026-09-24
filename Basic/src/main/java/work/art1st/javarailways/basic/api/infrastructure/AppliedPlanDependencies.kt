package work.art1st.javarailways.basic.api.infrastructure

import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import java.util.Collections
import java.util.LinkedHashSet
import java.util.UUID

/**
 * Stable identity of one structural topology node, independent of a runtime topology revision.
 *
 * A Core [work.art1st.javarailways.core.api.physics.rail.topology.TopologyNodeId] is revision-local
 * and must not be retained by an applied plan. The canonical set of persistent rail endpoints is
 * stable until that physical node is itself edited.
 */
class StableTopologyNodeKey(ports: Collection<RailEndpointRef>) {
    val ports: List<RailEndpointRef> = Collections.unmodifiableList(ports.distinct().sorted())

    init {
        require(this.ports.isNotEmpty()) { "topology node key must contain at least one port" }
    }

    override fun equals(other: Any?): Boolean =
        other is StableTopologyNodeKey && ports == other.ports

    override fun hashCode(): Int = ports.hashCode()

    override fun toString(): String = "StableTopologyNodeKey(ports=$ports)"
}

/** Non-exclusive infrastructure references required by one applied operational plan. */
class AppliedPlanDependencies(
    railIds: Collection<UUID>,
    topologyNodeKeys: Collection<StableTopologyNodeKey>,
    resourceIds: Collection<StableInfrastructureResource>
) {
    val railIds: Set<UUID> = immutableSet(railIds)
    val topologyNodeKeys: Set<StableTopologyNodeKey> = immutableSet(topologyNodeKeys)
    val resourceIds: Set<StableInfrastructureResource> = immutableSet(resourceIds)

    init {
        require(this.railIds.isNotEmpty() || this.topologyNodeKeys.isNotEmpty() || this.resourceIds.isNotEmpty()) {
            "applied plan dependencies must not be empty"
        }
    }

    override fun equals(other: Any?): Boolean = other is AppliedPlanDependencies &&
        railIds == other.railIds && topologyNodeKeys == other.topologyNodeKeys && resourceIds == other.resourceIds

    override fun hashCode(): Int = 31 * (31 * railIds.hashCode() + topologyNodeKeys.hashCode()) + resourceIds.hashCode()
}

/** Applies or replaces one plan's complete dependency declaration against the current topology. */
data class AppliedPlanDependencyRequest(
    val topologyRevision: Long,
    val owner: InfrastructureOwner,
    val dependencies: AppliedPlanDependencies
) {
    init {
        require(topologyRevision >= 0L) { "topologyRevision must be non-negative" }
    }
}

data class AppliedPlanDependencySnapshot(
    val owner: InfrastructureOwner,
    val dependencies: AppliedPlanDependencies
)

enum class AppliedPlanDependencyMutationStatus {
    APPLIED,
    REPLACED,
    REMOVED,
    UNCHANGED,
    STALE_TOPOLOGY,
    INVALID_REFERENCE,
    OWNER_REVISION_CONFLICT,
    EDIT_IN_PROGRESS,
    WORLD_UNAVAILABLE,
    SERVICE_CLOSED
}

data class AppliedPlanDependencyMutationResult(
    val status: AppliedPlanDependencyMutationStatus,
    val detail: String = ""
) {
    val successful: Boolean
        get() = status == AppliedPlanDependencyMutationStatus.APPLIED ||
            status == AppliedPlanDependencyMutationStatus.REPLACED ||
            status == AppliedPlanDependencyMutationStatus.REMOVED ||
            status == AppliedPlanDependencyMutationStatus.UNCHANGED
}

private fun <T> immutableSet(values: Collection<T>): Set<T> =
    Collections.unmodifiableSet(LinkedHashSet(values))
