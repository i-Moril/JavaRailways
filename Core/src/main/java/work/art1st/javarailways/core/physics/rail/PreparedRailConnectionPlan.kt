package work.art1st.javarailways.core.physics.rail

import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.core.physics.CoreWorld
import java.util.Collections
import java.util.LinkedHashSet
import java.util.UUID

/** Exact final connection state for one explicitly named physical rail endpoint. */
class PreparedRailEndpointConnection(
    val endpoint: RailEndpointRef,
    connectedEndpoints: List<RailEndpointRef>,
    val activeEndpoint: RailEndpointRef? = connectedEndpoints.firstOrNull()
) {
    val connectedEndpoints: List<RailEndpointRef> =
        Collections.unmodifiableList(ArrayList(connectedEndpoints))

    init {
        require(connectedEndpoints.distinct().size == connectedEndpoints.size) {
            "connected endpoints must be unique"
        }
        require(activeEndpoint == null || activeEndpoint in connectedEndpoints) {
            "active endpoint must be one of the connected endpoints"
        }
    }
}

/**
 * Process-local plan for explicit endpoint connections in one prepared rail creation batch.
 *
 * Callers name every endpoint and final continuation directly. Capture performs no proximity
 * search and makes no stem/branch choice. Apply validates every source state before mutation and
 * writes only the recorded states.
 */
class PreparedRailConnectionPlan private constructor(
    private val world: CoreWorld,
    private val preparedBatch: PreparedRailCreationBatch,
    val topologyRoot: TopologyState,
    private val affectedExistingRails: Map<UUID, CoreRail>,
    private val mutations: List<EndpointMutation>,
    private val initialRailCount: Int,
    affectedExistingEndpoints: Set<RailEndpointRef>,
    prospectiveCrossingExistingRailIds: Set<UUID>
) {
    val affectedExistingEndpoints: Set<RailEndpointRef> =
        Collections.unmodifiableSet(LinkedHashSet(affectedExistingEndpoints))
    val prospectiveCrossingExistingRailIds: Set<UUID> =
        Collections.unmodifiableSet(LinkedHashSet(prospectiveCrossingExistingRailIds))
    val affectedExistingRailIds: Set<UUID> = Collections.unmodifiableSet(
        LinkedHashSet<UUID>().apply {
            addAll(affectedExistingRails.keys)
            addAll(prospectiveCrossingExistingRailIds)
        }
    )

    fun isCurrent(candidateWorld: CoreWorld): Boolean =
        candidateWorld === world && candidateWorld.topologyState === topologyRoot &&
            candidateWorld.railInventorySize() == initialRailCount &&
            affectedExistingRails.all { (railId, rail) -> candidateWorld.getRail(railId) === rail } &&
            existingEndpointStatesAreCurrent(candidateWorld)

    fun requireCurrent(candidateWorld: CoreWorld) {
        check(isCurrent(candidateWorld)) {
            "Prepared explicit connection plan is stale; rebuild it from the current topology"
        }
    }

    /** Validates every live precondition before the prepared batch performs its first mutation. */
    fun requireReadyToApply(candidateWorld: CoreWorld) {
        requireCurrent(candidateWorld)
        require(candidateWorld.occupyingTrainIds(affectedExistingRailIds).isEmpty()) {
            "Cannot change occupied explicit connection endpoints"
        }
    }

    /** Builds the complete immutable target topology away from the owner thread. */
    fun prepareTopologyUpdate(): PreparedRailTopologyUpdate =
        world.prepareRailTopologyUpdate(this)

    @JvmSynthetic
    internal fun preparedBatch(): PreparedRailCreationBatch = preparedBatch

    @JvmSynthetic
    internal fun finalConnections(): List<PreparedRailEndpointConnection> =
        mutations.map(EndpointMutation::finalConnection)

    fun apply(candidateWorld: CoreWorld, installedRails: Collection<CoreRail>) {
        check(candidateWorld === world && candidateWorld.topologyState === topologyRoot) {
            "Prepared explicit connection plan is stale; rebuild it from the current topology"
        }
        check(affectedExistingRails.all { (railId, rail) -> candidateWorld.getRail(railId) === rail }) {
            "Prepared explicit connection plan references replaced rail instances"
        }
        check(existingEndpointStatesAreCurrent(candidateWorld)) {
            "A planned rail endpoint changed before explicit connections were applied"
        }
        val installedById = installedRails.associateBy(CoreRail::id)
        val expectedCreatedIds = preparedBatch.creations.mapTo(linkedSetOf()) { it.railId }
        require(installedRails.size == expectedCreatedIds.size && installedById.keys == expectedCreatedIds) {
            "Installed rails do not match the prepared explicit connection batch"
        }
        require(installedById.all { (railId, rail) -> candidateWorld.getRail(railId) === rail }) {
            "Installed rail instances do not match the prepared explicit connection batch"
        }
        require(candidateWorld.railInventorySize() == initialRailCount + expectedCreatedIds.size) {
            "Target rail inventory changed after explicit connection planning"
        }
        require(candidateWorld.preparedInstallIsOnlyInventoryMutation()) {
            "Rail inventory changed after the prepared batch was installed"
        }
        require(candidateWorld.occupyingTrainIds(affectedExistingRailIds).isEmpty()) {
            "Cannot change occupied explicit connection endpoints"
        }

        mutations.forEach { mutation ->
            resolveTargetEndpoint(candidateWorld, expectedCreatedIds, mutation.endpoint)
            mutation.finalConnection.connectedEndpoints.forEach {
                resolveTargetEndpoint(candidateWorld, expectedCreatedIds, it)
            }
        }
        mutations.forEach { mutation ->
            val view = resolveTargetEndpoint(candidateWorld, expectedCreatedIds, mutation.endpoint)
            val nextViews = mutation.finalConnection.connectedEndpoints.map { endpoint ->
                resolveTargetEndpoint(candidateWorld, expectedCreatedIds, endpoint).reversed
            }
            view.activeRail = null
            view.mutableNextRails.clear()
            view.mutableNextRails.addAll(nextViews)
            view.activeRail = mutation.finalConnection.activeEndpoint?.let { endpoint ->
                resolveTargetEndpoint(candidateWorld, expectedCreatedIds, endpoint).reversed
            }
        }
    }

    private fun resolveTargetEndpoint(
        candidateWorld: CoreWorld,
        createdRailIds: Set<UUID>,
        endpoint: RailEndpointRef
    ): RailView {
        require(endpoint.railId !in preparedBatch.excludedExistingRailIds || endpoint.railId in createdRailIds) {
            "Explicit connection target is removed by the prepared batch: $endpoint"
        }
        return endpointView(
            requireNotNull(candidateWorld.getRail(endpoint.railId)) { "Missing rail ${endpoint.railId}" },
            endpoint.endpoint
        )
    }

    private fun existingEndpointStatesAreCurrent(candidateWorld: CoreWorld): Boolean =
        mutations.asSequence().filter(EndpointMutation::wasExisting).all { mutation ->
            val rail = candidateWorld.getRail(mutation.endpoint.railId) ?: return@all false
            val view = endpointView(rail, mutation.endpoint.endpoint)
            view.mutableNextRails.map(::endpointOfAwayView) == mutation.initialConnections &&
                view.activeRail?.let(::endpointOfAwayView) == mutation.initialActiveEndpoint
        }

    companion object {
        fun capture(
            world: CoreWorld,
            preparedBatch: PreparedRailCreationBatch,
            finalConnections: Collection<PreparedRailEndpointConnection>
        ): PreparedRailConnectionPlan =
            world.capturePreparedRailConnectionPlan(preparedBatch, finalConnections)

        /** Derives exact final endpoint states from explicit replacement/addition declarations. */
        fun captureExplicit(
            world: CoreWorld,
            preparedBatch: PreparedRailCreationBatch,
            replacementEndpoints: Map<RailEndpointRef, RailEndpointRef> = emptyMap(),
            addedConnections: Collection<Pair<RailEndpointRef, RailEndpointRef>> = emptyList()
        ): PreparedRailConnectionPlan = world.capturePreparedRailConnectionPlan(
            preparedBatch,
            replacementEndpoints,
            addedConnections
        )

        @JvmSynthetic
        internal fun captureExplicitLocked(
            world: CoreWorld,
            preparedBatch: PreparedRailCreationBatch,
            replacementEndpoints: Map<RailEndpointRef, RailEndpointRef>,
            addedConnections: Collection<Pair<RailEndpointRef, RailEndpointRef>>
        ): PreparedRailConnectionPlan {
            require(replacementEndpoints.keys.all { it.railId in preparedBatch.excludedExistingRailIds })
            val createdIds = preparedBatch.creations.mapTo(hashSetOf()) { it.railId }
            require(replacementEndpoints.values.all { it.railId in createdIds })
            require(replacementEndpoints.values.toSet().size == replacementEndpoints.size) {
                "Each replacement endpoint must map to a different prepared endpoint"
            }
            preparedBatch.excludedExistingRailIds.forEach { railId ->
                val rail = requireNotNull(world.getRail(railId)) { "Missing replacement source rail $railId" }
                RailEndpoint.entries.forEach { endpoint ->
                    val source = RailEndpointRef(railId, endpoint)
                    val hasSurvivingConnection = endpointView(rail, endpoint).mutableNextRails
                        .map(::endpointOfAwayView)
                        .any { it.railId !in preparedBatch.excludedExistingRailIds }
                    require(!hasSurvivingConnection || source in replacementEndpoints) {
                        "Replacement endpoint with an external connection must be mapped: $source"
                    }
                }
            }
            val states = linkedMapOf<RailEndpointRef, MutableEndpointState>()

            fun translate(endpoint: RailEndpointRef): RailEndpointRef = replacementEndpoints[endpoint] ?: endpoint
            fun loadCurrent(endpoint: RailEndpointRef): MutableEndpointState {
                states[endpoint]?.let { return it }
                if (endpoint.railId in createdIds) return MutableEndpointState().also { states[endpoint] = it }
                require(endpoint.railId !in preparedBatch.excludedExistingRailIds) {
                    "Removed endpoint must be mapped before it can be connected: $endpoint"
                }
                val rail = requireNotNull(world.getRail(endpoint.railId)) {
                    "Missing explicit connection rail ${endpoint.railId}"
                }
                val view = endpointView(rail, endpoint.endpoint)
                val connected = view.mutableNextRails.map(::endpointOfAwayView)
                    .filterNot { it.railId in preparedBatch.excludedExistingRailIds && it !in replacementEndpoints }
                    .map(::translate)
                    .toMutableList()
                val active = view.activeRail?.let(::endpointOfAwayView)?.let(::translate)?.takeIf(connected::contains)
                return MutableEndpointState(connected, active).also { states[endpoint] = it }
            }

            replacementEndpoints.forEach { (oldEndpoint, newEndpoint) ->
                val oldRail = requireNotNull(world.getRail(oldEndpoint.railId))
                val oldView = endpointView(oldRail, oldEndpoint.endpoint)
                val connected = oldView.mutableNextRails.map(::endpointOfAwayView).map(::translate).toMutableList()
                val active = oldView.activeRail?.let(::endpointOfAwayView)?.let(::translate)?.takeIf(connected::contains)
                states[newEndpoint] = MutableEndpointState(connected, active)
            }

            addedConnections.forEach { (first, second) ->
                require(first != second)
                val translatedFirst = translate(first)
                val translatedSecond = translate(second)
                val firstState = loadCurrent(translatedFirst)
                val secondState = loadCurrent(translatedSecond)
                if (translatedSecond !in firstState.connected) firstState.connected += translatedSecond
                if (translatedFirst !in secondState.connected) secondState.connected += translatedFirst
                if (firstState.active == null) firstState.active = translatedSecond
                if (secondState.active == null) secondState.active = translatedFirst
            }

            val queue = ArrayDeque(states.keys)
            while (queue.isNotEmpty()) {
                val endpoint = queue.removeFirst()
                states.getValue(endpoint).connected.toList().forEach { connected ->
                    if (connected !in states) {
                        loadCurrent(connected)
                        queue += connected
                    }
                }
            }
            val finalConnections = states.map { (endpoint, state) ->
                require(state.connected.distinct().size <= 2) {
                    "A physical endpoint supports at most two explicit continuations"
                }
                PreparedRailEndpointConnection(endpoint, state.connected.distinct(), state.active)
            }
            return captureLocked(world, preparedBatch, finalConnections)
        }

        @JvmSynthetic
        internal fun captureLocked(
            world: CoreWorld,
            preparedBatch: PreparedRailCreationBatch,
            finalConnections: Collection<PreparedRailEndpointConnection>
        ): PreparedRailConnectionPlan {
            require(world.topologyState === preparedBatch.topologyRoot) {
                "Prepared rail batch is stale before explicit connection planning"
            }
            require(preparedBatch.ownerWorld === world) {
                "Prepared rail batch belongs to another world"
            }
            val copiedConnections = finalConnections.map { connection ->
                PreparedRailEndpointConnection(
                    connection.endpoint,
                    connection.connectedEndpoints,
                    connection.activeEndpoint
                )
            }
            val finalByEndpoint = copiedConnections.associateBy(PreparedRailEndpointConnection::endpoint)
            require(finalByEndpoint.size == finalConnections.size) {
                "Each explicitly changed endpoint may be declared only once"
            }
            val createdIds = preparedBatch.creations.mapTo(linkedSetOf()) { it.railId }
            fun isExistingTarget(railId: UUID): Boolean =
                railId !in preparedBatch.excludedExistingRailIds && world.getRail(railId) != null
            fun isTarget(railId: UUID): Boolean = railId in createdIds || isExistingTarget(railId)
            finalByEndpoint.forEach { (endpoint, connection) ->
                require(isTarget(endpoint.railId)) { "Unknown explicit endpoint: $endpoint" }
                connection.connectedEndpoints.forEach { target ->
                    require(isTarget(target.railId)) { "Unknown explicit connection target: $target" }
                    require(target != endpoint) { "An endpoint cannot connect to itself" }
                    require(finalByEndpoint[target]?.connectedEndpoints?.contains(endpoint) == true) {
                        "Explicit connections must be declared symmetrically: $endpoint <-> $target"
                    }
                    require(endpointPosition(world, preparedBatch, endpoint)
                        .distance(endpointPosition(world, preparedBatch, target)) <= Curve.TOLERANCE
                    ) { "Explicitly connected endpoints are not colocated: $endpoint <-> $target" }
                }
                require(connection.connectedEndpoints.size <= 2) {
                    "A physical endpoint supports at most two explicit continuations"
                }
            }

            val mutations = finalByEndpoint.values.map { connection ->
                val wasExisting = isExistingTarget(connection.endpoint.railId)
                val initialView = if (wasExisting) {
                    endpointView(requireNotNull(world.getRail(connection.endpoint.railId)), connection.endpoint.endpoint)
                } else null
                EndpointMutation(
                    endpoint = connection.endpoint,
                    initialConnections = initialView?.mutableNextRails?.map(::endpointOfAwayView).orEmpty(),
                    initialActiveEndpoint = initialView?.activeRail?.let(::endpointOfAwayView),
                    wasExisting = wasExisting,
                    finalConnection = connection
                )
            }
            mutations.asSequence().filter(EndpointMutation::wasExisting).forEach { mutation ->
                val finalTargets = mutation.finalConnection.connectedEndpoints.toSet()
                mutation.initialConnections.asSequence()
                    .filter { peer -> peer.railId !in preparedBatch.excludedExistingRailIds }
                    .filterNot(finalTargets::contains)
                    .forEach { removedPeer ->
                        val peerFinal = finalByEndpoint[removedPeer]
                        require(peerFinal != null && mutation.endpoint !in peerFinal.connectedEndpoints) {
                            "Removing an explicit connection requires both endpoints: " +
                                "${mutation.endpoint} <-> $removedPeer"
                        }
                    }
            }
            val affectedExistingEndpoints = linkedSetOf<RailEndpointRef>()
            val visitedEndpoints = hashSetOf<RailEndpointRef>()
            mutations.asSequence().filter { mutation ->
                !mutation.wasExisting ||
                    mutation.initialConnections != mutation.finalConnection.connectedEndpoints ||
                    mutation.initialActiveEndpoint != mutation.finalConnection.activeEndpoint
            }.forEach { changed ->
                val queue = ArrayDeque<RailEndpointRef>()
                queue += changed.endpoint
                while (queue.isNotEmpty()) {
                    val endpoint = queue.removeFirst()
                    if (!visitedEndpoints.add(endpoint)) continue
                    val mutation = finalByEndpoint.getValue(endpoint)
                    if (isExistingTarget(endpoint.railId)) affectedExistingEndpoints += endpoint
                    mutation.connectedEndpoints.forEach(queue::addLast)
                }
            }
            val crossingExistingIds = preparedBatch.crossings.asSequence()
                .flatMap { crossing -> sequenceOf(crossing.first.railId, crossing.second.railId) }
                .filter(::isExistingTarget)
                .toCollection(linkedSetOf())
            val boundExistingIds = LinkedHashSet<UUID>().apply {
                addAll(preparedBatch.excludedExistingRailIds)
                affectedExistingEndpoints.mapTo(this, RailEndpointRef::railId)
                addAll(crossingExistingIds)
            }
            val affectedRails = boundExistingIds.associateWith { railId ->
                requireNotNull(world.getRail(railId)) { "Explicit connection references missing rail $railId" }
            }
            return PreparedRailConnectionPlan(
                world,
                preparedBatch,
                preparedBatch.topologyRoot,
                affectedRails,
                mutations,
                world.railInventorySize(),
                affectedExistingEndpoints,
                crossingExistingIds
            ).also { it.requireCurrent(world) }
        }

        private fun endpointPosition(
            world: CoreWorld,
            batch: PreparedRailCreationBatch,
            endpoint: RailEndpointRef
        ) = batch.creations.firstOrNull { it.railId == endpoint.railId }?.curve?.let { curve ->
            if (endpoint.endpoint == RailEndpoint.A) curve.getPointAt(0.0).position
            else curve.getPointAt(curve.totalArcLength).position
        } ?: endpointView(requireNotNull(world.getRail(endpoint.railId)), endpoint.endpoint).endPoint.position
    }
}

private data class EndpointMutation(
    val endpoint: RailEndpointRef,
    val initialConnections: List<RailEndpointRef>,
    val initialActiveEndpoint: RailEndpointRef?,
    val wasExisting: Boolean,
    val finalConnection: PreparedRailEndpointConnection
)

private data class MutableEndpointState(
    val connected: MutableList<RailEndpointRef> = mutableListOf(),
    var active: RailEndpointRef? = null
)

private fun endpointView(rail: CoreRail, endpoint: RailEndpoint): RailView =
    if (endpoint == RailEndpoint.A) rail.viewBA else rail.viewAB

/** A next-away view starts at the canonical endpoint through which it is entered. */
private fun endpointOfAwayView(view: RailView): RailEndpointRef = RailEndpointRef(
    view.rail.id,
    if (view.viewDirection == ViewDirection.AB) RailEndpoint.A else RailEndpoint.B
)
