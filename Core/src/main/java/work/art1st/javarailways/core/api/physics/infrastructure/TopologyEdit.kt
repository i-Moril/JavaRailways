package work.art1st.javarailways.core.api.physics.infrastructure

import java.util.Collections
import java.util.LinkedHashSet
import java.util.UUID

/** Exact infrastructure identities that one structural edit may change. */
class TopologyEditImpact(
    railIds: Collection<UUID>,
    topologyNodeKeys: Collection<StableTopologyNodeKey>,
    resourceIds: Collection<StableInfrastructureResource>
) {
    val railIds: Set<UUID> = immutableEditSet(railIds)
    val topologyNodeKeys: Set<StableTopologyNodeKey> = immutableEditSet(topologyNodeKeys)
    val resourceIds: Set<StableInfrastructureResource> = immutableEditSet(resourceIds)

    init {
        require(this.railIds.isNotEmpty() || this.topologyNodeKeys.isNotEmpty() || this.resourceIds.isNotEmpty()) {
            "topology edit impact must not be empty"
        }
    }
}

data class TopologyEditRequest(
    val topologyRevision: Long,
    val owner: InfrastructureOwner,
    val impact: TopologyEditImpact
) {
    init {
        require(topologyRevision >= 0L) { "topologyRevision must be non-negative" }
    }
}

/** Exact field-wise intersection between an edit and one blocker. */
class TopologyEditOverlap(
    railIds: Collection<UUID>,
    topologyNodeKeys: Collection<StableTopologyNodeKey>,
    resourceIds: Collection<StableInfrastructureResource>
) {
    val railIds: Set<UUID> = immutableEditSet(railIds)
    val topologyNodeKeys: Set<StableTopologyNodeKey> = immutableEditSet(topologyNodeKeys)
    val resourceIds: Set<StableInfrastructureResource> = immutableEditSet(resourceIds)

    init {
        require(this.railIds.isNotEmpty() || this.topologyNodeKeys.isNotEmpty() || this.resourceIds.isNotEmpty()) {
            "topology edit overlap must not be empty"
        }
    }
}

enum class TopologyEditBlockerKind {
    INFRASTRUCTURE_DEPENDENCY,
    LIVE_RUNTIME_CLAIM,
    EDIT_PERMIT
}

data class TopologyEditBlocker(
    val kind: TopologyEditBlockerKind,
    val owner: InfrastructureOwner,
    val overlap: TopologyEditOverlap
)

enum class TopologyEditStatus {
    GRANTED,
    WORLD_UNAVAILABLE,
    STALE_TOPOLOGY,
    INVALID_REFERENCE,
    CONFLICT,
    SERVICE_CLOSED
}

class TopologyEditDecision(
    val status: TopologyEditStatus,
    blockers: Collection<TopologyEditBlocker> = emptyList(),
    val detail: String = ""
) {
    val blockers: List<TopologyEditBlocker> = Collections.unmodifiableList(ArrayList(blockers))
    val successful: Boolean get() = status == TopologyEditStatus.GRANTED

    init {
        require(status == TopologyEditStatus.CONFLICT || this.blockers.isEmpty()) {
            "blockers are only valid for CONFLICT"
        }
    }
}

private fun <T> immutableEditSet(values: Collection<T>): Set<T> =
    Collections.unmodifiableSet(LinkedHashSet(values))
