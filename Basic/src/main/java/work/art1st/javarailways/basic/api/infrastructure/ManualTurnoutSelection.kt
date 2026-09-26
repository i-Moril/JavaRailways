package work.art1st.javarailways.basic.api.infrastructure

import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import java.util.Collections

/**
 * Immutable request for one explicit turnout selection in a specific topology revision.
 *
 * [fromPort] and [toPort] are stable rail-endpoint identities and must describe one legal
 * continuation through the same turnout. The request is runtime-only and is not a persistence
 * document. It never carries or requests movement authority.
 *
 * @property topologyRevision authoritative world topology revision used to resolve both ports
 * @property fromPort stable incoming rail endpoint
 * @property toPort stable outgoing rail endpoint selected through the same turnout
 */
data class ManualTurnoutSelectionRequest(
    val topologyRevision: Long,
    val fromPort: RailEndpointRef,
    val toPort: RailEndpointRef
) {
    init {
        require(topologyRevision >= 0L) { "topologyRevision must be non-negative" }
        require(fromPort != toPort) { "manual turnout selection requires two different ports" }
    }
}

/** Terminal status of one synchronous manual turnout request. */
enum class ManualTurnoutSelectionStatus {
    SELECTED,
    UNCHANGED,
    WORLD_UNAVAILABLE,
    STALE_TOPOLOGY,
    INVALID_SELECTION,
    PHYSICALLY_OCCUPIED,
    RESOURCE_CONFLICT
}

/**
 * Detached outcome of one guarded manual turnout operation.
 *
 * Physical conflicts identify trains; resource conflicts identify the active runtime claim or
 * edit permit. The collections are immutable snapshots and contain no live world objects.
 *
 * @property status terminal operation status
 * @property blockingTrainIds stable train IDs occupying the locking area, only when physically occupied
 * @property blockers detached active-claim or edit-permit diagnostics, only on resource conflict
 * @property detail human-readable diagnostic detail; not a stable machine-readable token
 * @property successful true only when the requested alignment was selected or already active
 */
class ManualTurnoutSelectionResult(
    val status: ManualTurnoutSelectionStatus,
    blockingTrainIds: Collection<String> = emptyList(),
    blockers: Collection<InfrastructureBlocker> = emptyList(),
    val detail: String = ""
) {
    val blockingTrainIds: List<String> =
        Collections.unmodifiableList(blockingTrainIds.distinct().sorted())
    val blockers: List<InfrastructureBlocker> =
        Collections.unmodifiableList(ArrayList(blockers))

    val successful: Boolean
        get() = status == ManualTurnoutSelectionStatus.SELECTED ||
            status == ManualTurnoutSelectionStatus.UNCHANGED

    init {
        require(status == ManualTurnoutSelectionStatus.PHYSICALLY_OCCUPIED ||
            this.blockingTrainIds.isEmpty()) {
            "blocking train IDs are only valid for PHYSICALLY_OCCUPIED"
        }
        require(status == ManualTurnoutSelectionStatus.RESOURCE_CONFLICT || this.blockers.isEmpty()) {
            "resource blockers are only valid for RESOURCE_CONFLICT"
        }
    }
}
