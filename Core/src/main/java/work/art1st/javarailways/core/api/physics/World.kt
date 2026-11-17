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

    /**
     * Immutable upper bound on one controller physics step, in seconds. Applies to live and
     * isolated simulation steps; controllers use the issuing world's bound for braking proofs.
     * This is a timing limit, not the current delta or a promise of a fixed control cadence.
     */
    val maximumPhysicsDeltaSeconds: Double

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

    /**
     * Inspects a short selected coupler approach without changing either train or authorizing
     * movement. Immediate, nonblocking, serialized simulation-owner call between physics frames.
     * Unsupported worlds reject explicitly. The returned proof is process-local and frame-bound.
     */
    fun inspectCouplingApproach(
        request: work.art1st.javarailways.core.api.physics.train.CouplingApproachRequest
    ): work.art1st.javarailways.core.api.physics.train.CouplingApproachInspection =
        work.art1st.javarailways.core.api.physics.train.CouplingApproachInspection.Rejected(
            "world does not support coupling approach inspection")

    /**
     * Checks entry into rear-coupling mode, using the approaching operating head and the target's
     * operating tail. Both exact current trains must be stopped and the couplers must share a
     * selected connected path. [maximumGapMeters] bounds Core's actual coupler gap in metres,
     * independently of the solved head travel and raw leading-bogie travel to contact.
     * Immediate, nonblocking, serialized simulation-owner call between physics frames. Rejection
     * changes no train, direction, publication or movement authority. The accepted geometry remains
     * process-local; the operating owner must obtain route/resource admission before movement.
     */
    fun inspectCouplingModeEntry(
        approachingTrainId: String,
        stationaryTrainId: String,
        maximumGapMeters: Double
    ): work.art1st.javarailways.core.api.physics.train.CouplingApproachInspection =
        work.art1st.javarailways.core.api.physics.train.CouplingApproachInspection.Rejected(
            "world does not support coupling-mode entry inspection")

    /** Rechecks an issuing-world proof on the same serialized owner; never refreshes stale proof. */
    fun validateCouplingApproach(
        proof: work.art1st.javarailways.core.api.physics.train.CouplingApproachProof
    ): Boolean = false

    /** Rechecks the captured contact/target domain while the approaching train advances on it. */
    fun validateCouplingApproachGeometry(
        proof: work.art1st.javarailways.core.api.physics.train.CouplingApproachProof
    ): Boolean = false

    /**
     * Read-only worker-safe validation against one coherent immutable world publication. Checks
     * issuing world, source identity/direction, unchanged stopped target and captured topology /
     * selected continuations. It creates no movement authority and reads no writable geometry.
     */
    fun validatePublishedCouplingApproach(
        proof: work.art1st.javarailways.core.api.physics.train.CouplingApproachProof
    ): Boolean = false

    /**
     * Physics-worker-only distance clamp for the approaching controller's own tick. Computes raw
     * leading-bogie travel to Core's pre-solved contact, without moving it. Rejects stale/off-path
     * proof and invalid distances; returns no distance beyond contact and adds no authority.
     */
    fun clampCouplingApproachDistance(
        proof: work.art1st.javarailways.core.api.physics.train.CouplingApproachProof,
        requestedDistanceMeters: Double
    ): Double = throw UnsupportedOperationException("world does not support contact-distance clamping")

    /**
     * Releases only the exact live formation-output fence issued by this world. Owner-thread,
     * nonblocking; false for a foreign, retired or already released token. Configuration holds
     * remain effective. The operational owner must finish grant/ledger reconciliation first.
     */
    fun releaseFormationMovementHold(
        hold: work.art1st.javarailways.core.api.physics.train.FormationMovementHold
    ): Boolean = false

    /**
     * Reestablishes a dedicated fence while restoring an unresolved operational formation record,
     * before runtime ticks are enabled. Requires exact ordered current cars and a stopped published
     * frame. Owner-thread and nonblocking; rejection changes nothing. The caller owns persistence
     * validation of its unresolved record. This method neither restores a mission nor grants motion.
     */
    fun restoreFormationMovementHold(
        trainId: String,
        expectedCarIds: List<String>
    ): work.art1st.javarailways.core.api.physics.train.FormationMovementHoldRestoration =
        work.art1st.javarailways.core.api.physics.train.FormationMovementHoldRestoration.Rejected(
            "world does not support formation recovery holds")

    /** Find a rail by persistent UUID. */
    fun getRail(id: UUID): Rail?

    /**
     * Removes the exact registered instance and cleans its owned resources without blocking.
     * Repeating removal of an absent instance observes its original cleanup result; a replacement
     * with the same ID or a different world is rejected before any mutation.
     */
    fun removeRail(rail: Rail): java.util.concurrent.CompletionStage<Void>
}
