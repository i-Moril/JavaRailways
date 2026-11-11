package work.art1st.javarailways.core.physics.rail

import java.util.concurrent.CompletionStage
import work.art1st.javarailways.core.api.util.Destruction
import work.art1st.javarailways.core.api.util.DestructionSteps
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import kotlinx.serialization.Serializable
import org.joml.Vector3dc
import work.art1st.javarailways.core.control.signal.Signal
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.train.TrainEnd
import work.art1st.javarailways.core.api.physics.curve.Curve.Companion.TOLERANCE
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.physics.exception.CannotConnectRailException
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveImpl
import work.art1st.javarailways.core.api.physics.rail.Rail
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.api.render.RenderableExtension
import work.art1st.javarailways.core.api.util.annotation.Concurrent
import work.art1st.javarailways.core.api.util.annotation.MainThread
import work.art1st.configLib.persistence.Loads
import work.art1st.configLib.persistence.PersistentBy
import work.art1st.javarailways.core.api.util.plugin.Extendable
import work.art1st.javarailways.core.api.util.plugin.ExtensionDataContainer
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.jvm.Throws

/**
 * 正方向：endPointA -> endpointB
 */
class CoreRail @MainThread private constructor(
    override val id: UUID,
    override val world: CoreWorld,
    curveAB: PrimeCurve,
    nextRailsA: Collection<RailView>,
    nextRailsB: Collection<RailView>,
    activeTrackAtA: RailView?,
    activeTrackAtB: RailView?,
    val signals: MutableCollection<Signal>,
    // trains is maintained only on runtime
    val trains: ConcurrentLinkedQueue<TrainEnd>,
    val extensionDataContainer: ExtensionDataContainer<RenderableExtension<Rail>>
) : Rail, Extendable<RenderableExtension<Rail>> by extensionDataContainer, PersistentBy<CoreRail.SavedRailV1> {
    internal val topologyIdentity: UUID = UUID.randomUUID()
    override val curve: PrimeCurve = curveAB
    val viewAB: RailView = RailView(
        this,
        ViewDirection.AB,
        curveAB,
        nextRailsB.toMutableList(),
        activeTrackAtB,
    )
    val viewBA: RailView = RailView(
        this,
        ViewDirection.BA,
        curveAB.reversed,
        nextRailsA.toMutableList(),
        activeTrackAtA
    )

    override fun getView(direction: ViewDirection): RailView {
        return when (direction) {
            ViewDirection.AB -> viewAB
            ViewDirection.BA -> viewBA
        }
    }

    internal fun moveTrainEndTo(x: TrainEnd, nextRail: CoreRail) {
        nextRail.trains.add(x)
        trains.remove(x)
    }

    /**
     * internal: 防止并发bug
     */
    @MainThread
    internal fun removeTrainEnd(x: TrainEnd) {
        trains.remove(x)
    }

    /**
     * internal: 防止并发bug
     */
    @MainThread
    internal fun addTrainEnd(x: TrainEnd) {
        trains.add(x)
    }

    private val destruction = Destruction()

    override fun destroy(): CompletionStage<Void> = world.removeRail(this)

    internal fun destroyResources(): CompletionStage<Void> = destruction.run {
        val steps = DestructionSteps()
        signals.toList().forEach { signal -> steps.await { signal.destroy() } }
        forEachExtensionData { extension -> steps.await { extension.destroy() } }
        steps.finish().whenComplete { _, failure ->
            if (failure != null) world.physicsCoordinator.stall("Rail cleanup failed: rail=$id", failure)
        }
    }

    override fun save(): SavedRailV1 {
        val curveSnapshot = viewAB.curve.save() as PrimeCurveImpl.SavedPrimeCurveV1
        return SavedRailV1(
            id.toString(),
            curveSnapshot,
            viewBA.nextRails.map(SavedRailEndpointRef::fromAwayView),
            viewAB.nextRails.map(SavedRailEndpointRef::fromAwayView),
            viewBA.nextActiveRail?.let(SavedRailEndpointRef::fromAwayView),
            viewAB.nextActiveRail?.let(SavedRailEndpointRef::fromAwayView),
            viewBA.joinedEndpoints.sorted().map(SavedRailEndpointRef::fromEndpoint),
            viewAB.joinedEndpoints.sorted().map(SavedRailEndpointRef::fromEndpoint)
        )
    }

    @Serializable
    data class SavedRailV1(
        val id: String,
        val curveAB: PrimeCurveImpl.SavedPrimeCurveV1,
        val connectionsAtA: List<SavedRailEndpointRef>,
        val connectionsAtB: List<SavedRailEndpointRef>,
        val activeConnectionAtA: SavedRailEndpointRef?,
        val activeConnectionAtB: SavedRailEndpointRef?,
        val joinedEndpointsAtA: List<SavedRailEndpointRef>,
        val joinedEndpointsAtB: List<SavedRailEndpointRef>
    ) : Loads<CoreRail, SavedRailV1.Dependency> {
        override fun load(dependency: Dependency): CoreRail =
            dependency.world.railTopologyTransaction {
                val rail = loadDisconnected(dependency)
                restoreConnections(rail, dependency)
                rail
            }

        fun loadDisconnected(dependency: Dependency): CoreRail {
            return loadDisconnected(dependency, curveAB.load(Unit))
        }

        internal fun loadDisconnected(dependency: Dependency, curve: PrimeCurve): CoreRail =
            dependency.world.createRailInTransaction(UUID.fromString(id), curve)

        fun restoreConnections(rail: CoreRail, dependency: Dependency) {
            require(rail.world === dependency.world) { "rail belongs to a different world" }
            dependency.world.railTopologyTransaction {
                restoreConnectionsInTransaction(rail, dependency)
            }
        }

        private fun restoreConnectionsInTransaction(rail: CoreRail, dependency: Dependency) {
            val curveById = { railId: UUID -> dependency.world.getRail(railId)?.curve }
            val stateA = decodeSavedRailEndpoint(
                RailEndpointRef(rail.id, RailEndpoint.A), connectionsAtA, activeConnectionAtA, curveById, joinedEndpointsAtA
            )
            val stateB = decodeSavedRailEndpoint(
                RailEndpointRef(rail.id, RailEndpoint.B), connectionsAtB, activeConnectionAtB, curveById, joinedEndpointsAtB
            )
            restoreValidatedConnections(rail, dependency, stateA, stateB)
        }

        internal fun restoreValidatedConnections(
            rail: CoreRail, dependency: Dependency, stateA: SnapshotEndpointState, stateB: SnapshotEndpointState
        ) {
            fun awayView(endpoint: RailEndpointRef): RailView {
                val target = requireNotNull(dependency.world.getRail(endpoint.railId))
                return if (endpoint.endpoint == RailEndpoint.A) target.viewAB else target.viewBA
            }
            // Validate and resolve both ends before changing either live endpoint.
            val nextAtA = stateA.connectedEndpoints.map(::awayView)
            val nextAtB = stateB.connectedEndpoints.map(::awayView)
            val activeAtA = stateA.activeEndpoint?.let(::awayView)
            val activeAtB = stateB.activeEndpoint?.let(::awayView)
            val affected = (listOf(rail.id) + rail.viewBA.joinedEndpoints.map { it.railId } +
                rail.viewAB.joinedEndpoints.map { it.railId } + stateA.joinedEndpoints.map { it.railId } +
                stateB.joinedEndpoints.map { it.railId }).toSet()
            dependency.world.ensureRailEditProtected(affected)
            require(dependency.world.occupyingTrainIds(affected).isEmpty()) { "Cannot restore occupied rail connections" }
            rail.viewBA.activeRail = null
            rail.viewBA.mutableNextRails.clear()
            rail.viewBA.mutableNextRails.addAll(nextAtA)
            rail.viewBA.mutableJoinedEndpoints.clear()
            rail.viewBA.mutableJoinedEndpoints.addAll(stateA.joinedEndpoints)
            rail.viewBA.activeRail = activeAtA
            rail.viewAB.activeRail = null
            rail.viewAB.mutableNextRails.clear()
            rail.viewAB.mutableNextRails.addAll(nextAtB)
            rail.viewAB.mutableJoinedEndpoints.clear()
            rail.viewAB.mutableJoinedEndpoints.addAll(stateB.joinedEndpoints)
            rail.viewAB.activeRail = activeAtB
            if (nextAtA.isEmpty()) rail.viewBA.selectDefaultConnection()
            if (nextAtB.isEmpty()) rail.viewAB.selectDefaultConnection()
        }

        data class Dependency(
            val world: CoreWorld
        )
    }

    companion object {
        /**
         * Constructs an unregistered rail for [CoreWorld]'s topology transaction owner.
         *
         * Kotlin module visibility and [JvmSynthetic] keep this out of add-on source APIs; registration
         * remains exclusively owned by `CoreWorld.createRailInTransaction`.
         */
        @JvmSynthetic
        @MainThread
        internal fun createUnregistered(
            id: UUID,
            world: CoreWorld,
            curveAB: PrimeCurve
        ): CoreRail = CoreRail(
            id = id,
            world = world,
            curveAB = curveAB,
            nextRailsA = emptyList(),
            nextRailsB = emptyList(),
            activeTrackAtA = null,
            activeTrackAtB = null,
            signals = CopyOnWriteArraySet(),
            trains = ConcurrentLinkedQueue(),
            extensionDataContainer = ExtensionDataContainer()
        )

        fun isSamePoint(pointA: Vector3dc, pointB: Vector3dc): Boolean {
            return pointA.distance(pointB) < TOLERANCE
        }

        @Throws(CannotConnectRailException::class)
        fun connect(rail1: CoreRail, rail2: CoreRail) {
            val a1 = rail1.viewBA.endPoint.position
            val b1 = rail1.viewAB.endPoint.position
            val a2 = rail2.viewBA.endPoint.position
            val b2 = rail2.viewAB.endPoint.position
            val dist = listOf(
                a1.distance(a2),
                a1.distance(b2),
                b1.distance(a2),
                b1.distance(b2)
            )
            val i = dist.withIndex().minByOrNull { it.value }?.index
            when (i) {
                0 -> RailView.connect(rail1.viewBA, rail2.viewBA)
                1 -> RailView.connect(rail1.viewBA, rail2.viewAB)
                2 -> RailView.connect(rail1.viewAB, rail2.viewBA)
                3 -> RailView.connect(rail1.viewAB, rail2.viewAB)
            }
        }
    }
}
