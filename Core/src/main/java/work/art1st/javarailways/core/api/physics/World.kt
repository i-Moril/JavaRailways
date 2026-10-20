package work.art1st.javarailways.core.api.physics

import work.art1st.javarailways.core.api.physics.rail.Rail
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.core.api.physics.train.Train
import work.art1st.javarailways.core.api.util.plugin.Extendable
import work.art1st.javarailways.core.api.util.tick.TickManager
import java.util.UUID

/**
 * Platform-independent collection of rails, trains, Tick services, and module extension data.
 *
 * A platform adapter owns creation and destruction. The interface does not promise that returned
 * collections are immutable snapshots or safe for arbitrary concurrent iteration. Bukkit extensions
 * should perform structural mutations on the server main thread and observe physics publication rules.
 */
interface World : Extendable<Any> {
    /** Immutable runtime mode; not part of the world's persisted infrastructure or runtime data. */
    val executionMode: WorldExecutionMode

    /** General-purpose Tick manager; train physics uses a separate coordinator. */
    val tickManager: TickManager

    /** Currently loaded trains. Treat the collection as a live view unless an implementation says otherwise. */
    val allTrains: Collection<Train>

    /**
     * Current immutable physical-topology root.
     *
     * This non-blocking getter is safe from any thread. The returned value never changes, but it
     * may become stale after a later structural edit; compare [TopologyState.revision] before using
     * revision-local node or edge IDs. The state is derived at runtime and is not persisted.
     */
    val topologyState: TopologyState

    /**
     * Registers world-local bounded safety invalidation before each changed transaction publishes.
     * No-op and rolled-back transactions do not notify. The caller owns the returned idempotent
     * registration and closes it on teardown. Closing cannot cancel an already running callback.
     */
    fun registerTopologyChangeListener(
        listener: work.art1st.javarailways.core.api.physics.rail.topology.RailTopologyChangeListener
    ): AutoCloseable = throw UnsupportedOperationException("world does not support topology transactions")

    /** Find a train by its world-unique player-assigned name; implementations may also accept an internal ID. */
    fun getTrain(name: String): Train?

    /** Find a rail by persistent UUID. */
    fun getRail(id: UUID): Rail?

    /**
     * Removes the exact registered instance and cleans its owned resources without blocking.
     * Repeating removal of an absent instance observes its original cleanup result; a replacement
     * with the same ID or a different world is rejected before any mutation.
     */
    fun removeRail(rail: Rail): java.util.concurrent.CompletionStage<Void>
}
