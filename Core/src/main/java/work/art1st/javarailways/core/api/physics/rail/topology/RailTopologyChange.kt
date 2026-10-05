package work.art1st.javarailways.core.api.physics.rail.topology

import java.util.UUID

/**
 * One successful world-local structural transaction, delivered before [current] is published.
 *
 * Roots and persistent rail identities are immutable values. [affectedRailIds] includes removed
 * and added rails, changed connection peers and crossing participants; it is not a spatial query.
 * Revisions identify snapshots, not the validity of an unrelated operational dependency.
 */
class RailTopologyChange(
    val previous: TopologyState,
    val current: TopologyState,
    affectedRailIds: Collection<UUID>
) {
    val affectedRailIds: Set<UUID> = java.util.Set.copyOf(affectedRailIds)

    init {
        require(previous !== current) { "a topology change must replace its root" }
        require(current.revision == Math.addExact(previous.revision, 1L))
        require(this.affectedRailIds.isNotEmpty()) { "a topology change must identify its physical impact" }
    }
}

/**
 * Synchronous safety invalidation at a successful transaction boundary. Implementations may only
 * invalidate indexed local dependencies or enqueue worker work: no I/O, graph compilation or wait.
 * An exception faults the world; this is deliberately not an exception-isolating notification bus.
 */
fun interface RailTopologyChangeListener {
    fun onTopologyChange(change: RailTopologyChange)
}
