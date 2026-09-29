package work.art1st.javarailways.core.api.physics.rail.topology

import java.util.Collections
import java.util.LinkedHashMap

/**
 * Immutable live branch selections captured with one railway world frame.
 *
 * Each map entry means that a vehicle arriving at the junction through the key endpoint would
 * currently continue through the value endpoint. Only selectable [TopologyNode.Turnout] ports are
 * included; absence means that the entry has no selected continuation in this snapshot. Both
 * endpoints use persistent rail identity and the canonical A/B endpoint convention.
 *
 * [selectionRevision] is a process-local runtime counter. It changes whenever a live branch
 * selection changes and is neither a structural topology revision nor a persistence schema version.
 *
 * @property topologyRevision structural topology revision whose turnout ports this snapshot uses
 * @property selectionRevision process-local monotonic live-selection revision
 * @property selectedContinuationByEntry immutable selected exit keyed by the arriving rail endpoint
 */
@ConsistentCopyVisibility
data class ActiveRailContinuationsSnapshot private constructor(
    val topologyRevision: Long,
    val selectionRevision: Long,
    val selectedContinuationByEntry: Map<RailEndpointRef, RailEndpointRef>
) {
    init {
        require(topologyRevision >= 0L) { "topologyRevision must be non-negative" }
        require(selectionRevision >= 0L) { "selectionRevision must be non-negative" }
        require(selectedContinuationByEntry.none { (entry, exit) -> entry == exit }) {
            "an active continuation cannot return through the same rail endpoint"
        }
    }

    /** Returns the selected exit for [entry], or `null` when that entry is currently unselected. */
    fun selectedContinuation(entry: RailEndpointRef): RailEndpointRef? =
        selectedContinuationByEntry[entry]

    /** Validated constructors for immutable active-continuation values. */
    companion object {
        /** Validates and defensively copies live selections in deterministic endpoint order. */
        @JvmStatic
        fun create(
            topologyRevision: Long,
            selectionRevision: Long,
            selectedContinuationByEntry: Map<RailEndpointRef, RailEndpointRef>
        ): ActiveRailContinuationsSnapshot {
            val sorted = LinkedHashMap<RailEndpointRef, RailEndpointRef>()
            selectedContinuationByEntry.entries.sortedBy(Map.Entry<RailEndpointRef, RailEndpointRef>::key)
                .forEach { (entry, exit) -> sorted[entry] = exit }
            return ActiveRailContinuationsSnapshot(
                topologyRevision,
                selectionRevision,
                Collections.unmodifiableMap(sorted)
            )
        }

        /** Creates an empty live-selection snapshot for a topology without selected turnouts. */
        @JvmStatic
        fun empty(topologyRevision: Long): ActiveRailContinuationsSnapshot =
            create(topologyRevision, 0L, emptyMap())
    }
}
