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

/**
 * Ordered connections at one physical endpoint. The two-argument constructor asks capture to
 * choose the straightest continuation; a supplied active endpoint, including null, is exact.
 */
class PreparedRailEndpointConnection private constructor(
    val endpoint: RailEndpointRef,
    connectedEndpoints: List<RailEndpointRef>,
    val activeEndpoint: RailEndpointRef?,
    internal val usesDefaultSelection: Boolean,
    joinedEndpoints: Collection<RailEndpointRef>
) {
    constructor(endpoint: RailEndpointRef, connectedEndpoints: List<RailEndpointRef>) :
        this(endpoint, connectedEndpoints, null, true, connectedEndpoints)

    constructor(endpoint: RailEndpointRef, connectedEndpoints: List<RailEndpointRef>, activeEndpoint: RailEndpointRef?) :
        this(endpoint, connectedEndpoints, activeEndpoint, false, connectedEndpoints)

    constructor(endpoint: RailEndpointRef, connectedEndpoints: List<RailEndpointRef>, activeEndpoint: RailEndpointRef?,
                joinedEndpoints: Collection<RailEndpointRef>) :
        this(endpoint, connectedEndpoints, activeEndpoint, false, joinedEndpoints)

    val joinedEndpoints: Set<RailEndpointRef> =
        Collections.unmodifiableSet(LinkedHashSet(joinedEndpoints))

    val connectedEndpoints: List<RailEndpointRef> =
        Collections.unmodifiableList(ArrayList(connectedEndpoints))

    init {
        require(joinedEndpoints.distinct().size == joinedEndpoints.size && endpoint !in joinedEndpoints) {
            "Joined endpoints must be unique peer ports"
        }
        require(connectedEndpoints.all { it in joinedEndpoints }) { "Legal connections must belong to the endpoint group" }
        require(connectedEndpoints.size <= 2) { "A rail endpoint supports at most two continuations" }
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
 * search. Missing selections use the straightest tangent, retaining connection order on ties.
 * Apply validates every source state before mutation and
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
        candidateWorld === world &&
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
        candidateWorld.ensureRailEditProtected(affectedExistingRailIds)
        check(candidateWorld === world) {
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
            view.mutableJoinedEndpoints.clear()
            view.mutableJoinedEndpoints.addAll(mutation.finalConnection.joinedEndpoints)
            view.activeRail = mutation.finalConnection.activeEndpoint?.let { endpoint ->
                resolveTargetEndpoint(candidateWorld, expectedCreatedIds, endpoint).reversed
            }
            if (nextViews.isEmpty()) view.selectDefaultConnection()
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
                view.activeRail?.let(::endpointOfAwayView) == mutation.initialActiveEndpoint &&
                view.joinedEndpoints == mutation.initialJoinedEndpoints
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
            world.requirePreparedCreationCurrent(preparedBatch)
            require(replacementEndpoints.keys.all { it.railId in preparedBatch.excludedExistingRailIds })
            val createdIds = preparedBatch.creations.mapTo(hashSetOf()) { it.railId }
            require(replacementEndpoints.values.all { it.railId in createdIds })
            require(replacementEndpoints.values.toSet().size == replacementEndpoints.size) {
                "Each replacement endpoint must map to a different prepared endpoint"
            }
            val excluded = preparedBatch.excludedExistingRailIds
            val root = world.topologyState
            excluded.forEach { railId ->
                requireNotNull(world.getRail(railId)) { "Missing replacement source rail $railId" }
                RailEndpoint.entries.forEach { end ->
                    val source = RailEndpointRef(railId, end)
                    require(root.endpointGroupAt(source).none { it.railId !in excluded } || source in replacementEndpoints) {
                        "Replacement endpoint with an external connection must be mapped: $source"
                    }
                }
            }
            val targetPorts = (root.railProjection.keys - excluded + createdIds).flatMap { railId ->
                RailEndpoint.entries.map { RailEndpointRef(railId, it) }
            }.toSet()
            val groups = targetPorts.associateWithTo(linkedMapOf()) { linkedSetOf(it) }
            fun translate(port: RailEndpointRef): RailEndpointRef = replacementEndpoints[port] ?: port
            fun join(ports: Collection<RailEndpointRef>) {
                val members = ports.flatMapTo(linkedSetOf()) { port ->
                    requireNotNull(groups[port]) { "Unknown explicit endpoint: $port" }
                }
                members.forEach { groups[it] = members }
            }
            root.railProjection.keys.forEach { railId ->
                RailEndpoint.entries.forEach { end ->
                    val original = RailEndpointRef(railId, end)
                    join(root.endpointGroupAt(original).map(::translate).filter { it in targetPorts })
                }
            }
            addedConnections.forEach { (first, second) ->
                require(first != second)
                join(listOf(translate(first), translate(second)))
            }
            val creations = preparedBatch.creations.associateBy(PreparedRailCreation::railId)
            fun curve(railId: UUID) = creations[railId]?.curve ?: requireNotNull(world.getRail(railId)).curve
            val replacementSources = replacementEndpoints.entries.associate { (old, new) -> new to old }
            fun previousView(port: RailEndpointRef): RailView? {
                val original = replacementSources[port] ?: port
                if (original.railId in createdIds && original !in replacementEndpoints) return null
                val rail = world.getRail(original.railId) ?: return null
                return endpointView(rail, original.endpoint)
            }
            val finalByPort = linkedMapOf<RailEndpointRef, PreparedRailEndpointConnection>()
            groups.values.distinctBy { it.sorted() }.forEach { members ->
                val legal = RailEndpointGeometry.legalContinuations(members, ::curve)
                members.forEach { port ->
                    val old = previousView(port)
                    val retained = old?.mutableNextRails.orEmpty().map(::endpointOfAwayView).map(::translate)
                        .filter { it in legal.getValue(port) }.distinct()
                    val connections = retained + (legal.getValue(port) - retained.toSet()).sorted()
                    val priorActive = old?.activeRail?.let(::endpointOfAwayView)?.let(::translate)
                    val active = when {
                        priorActive in connections -> priorActive
                        old != null && old.mutableNextRails.isNotEmpty() && priorActive == null -> null
                        else -> straightestEndpoint(port, connections, ::curve)
                    }
                    finalByPort[port] = PreparedRailEndpointConnection(port, connections, active, members - port)
                }
            }
            val touchedGroups = groups.values.distinctBy { it.sorted() }.filter { members ->
                members.any { port ->
                    val old = previousView(port)
                    val final = finalByPort.getValue(port)
                    old == null || port in replacementSources || old.joinedEndpoints != final.joinedEndpoints ||
                        old.mutableNextRails.map(::endpointOfAwayView) != final.connectedEndpoints ||
                        old.activeRail?.let(::endpointOfAwayView) != final.activeEndpoint
                }
            }
            val states = touchedGroups.flatMap { members -> members.map(finalByPort::getValue) }
            return captureLocked(world, preparedBatch, states)
        }

        @JvmSynthetic
        internal fun captureLocked(
            world: CoreWorld,
            preparedBatch: PreparedRailCreationBatch,
            finalConnections: Collection<PreparedRailEndpointConnection>
        ): PreparedRailConnectionPlan {
            world.requirePreparedCreationCurrent(preparedBatch)
            require(preparedBatch.ownerWorld === world) {
                "Prepared rail batch belongs to another world"
            }
            val creationsById = preparedBatch.creations.associateBy(PreparedRailCreation::railId)
            fun targetCurve(railId: UUID) = creationsById[railId]?.curve
                ?: requireNotNull(world.getRail(railId)) { "Missing explicit connection rail $railId" }.curve
            val declared = finalConnections.associateBy(PreparedRailEndpointConnection::endpoint)
            require(declared.size == finalConnections.size) { "Each explicitly changed endpoint may be declared only once" }
            finalConnections.forEach { connection ->
                connection.connectedEndpoints.forEach { target ->
                    require(declared[target]?.connectedEndpoints?.contains(connection.endpoint) == true) {
                        "Explicit connections must be declared symmetrically: ${connection.endpoint} <-> $target"
                    }
                    require(endpointPosition(world, creationsById, connection.endpoint)
                        .distance(endpointPosition(world, creationsById, target)) <= Curve.TOLERANCE) {
                        "Explicitly connected endpoints are not colocated: ${connection.endpoint} <-> $target"
                    }
                }
                connection.joinedEndpoints.forEach { peer ->
                    require(declared[peer]?.joinedEndpoints?.contains(connection.endpoint) == true) {
                        "Joined endpoint declarations must be symmetric: ${connection.endpoint} <-> $peer"
                    }
                }
            }
            fun members(seed: RailEndpointRef): Set<RailEndpointRef> {
                val result = linkedSetOf<RailEndpointRef>()
                val queue = ArrayDeque<RailEndpointRef>()
                queue += seed
                while (queue.isNotEmpty()) {
                    val port = queue.removeFirst()
                    if (result.add(port)) declared.getValue(port).joinedEndpoints.forEach(queue::addLast)
                }
                return result
            }
            val copiedConnections = finalConnections.map { connection ->
                val group = members(connection.endpoint)
                val legal = RailEndpointGeometry.legalContinuations(group, ::targetCurve)
                require(connection.connectedEndpoints.toSet() == legal.getValue(connection.endpoint)) {
                    "Explicit connections do not match endpoint group geometry: ${connection.endpoint}"
                }
                PreparedRailEndpointConnection(
                    connection.endpoint,
                    connection.connectedEndpoints,
                    if (connection.usesDefaultSelection) {
                        straightestEndpoint(connection.endpoint, connection.connectedEndpoints, ::targetCurve)
                    } else connection.activeEndpoint,
                    group - connection.endpoint
                )
            }
            val finalByEndpoint = copiedConnections.associateBy(PreparedRailEndpointConnection::endpoint)
            require(finalByEndpoint.size == finalConnections.size) {
                "Each explicitly changed endpoint may be declared only once"
            }
            fun isExistingTarget(railId: UUID): Boolean =
                railId !in preparedBatch.excludedExistingRailIds && world.getRail(railId) != null
            fun isTarget(railId: UUID): Boolean = railId in creationsById || isExistingTarget(railId)
            finalByEndpoint.forEach { (endpoint, connection) ->
                require(isTarget(endpoint.railId)) { "Unknown explicit endpoint: $endpoint" }
                connection.connectedEndpoints.forEach { target ->
                    require(isTarget(target.railId)) { "Unknown explicit connection target: $target" }
                    require(target != endpoint) { "An endpoint cannot connect to itself" }
                    require(finalByEndpoint[target]?.connectedEndpoints?.contains(endpoint) == true) {
                        "Explicit connections must be declared symmetrically: $endpoint <-> $target"
                    }
                    require(endpointPosition(world, creationsById, endpoint)
                        .distance(endpointPosition(world, creationsById, target)) <= Curve.TOLERANCE
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
                    initialJoinedEndpoints = initialView?.joinedEndpoints.orEmpty().toSet(),
                    wasExisting = wasExisting,
                    finalConnection = connection
                )
            }
            mutations.asSequence().filter(EndpointMutation::wasExisting).forEach { mutation ->
                val finalTargets = mutation.finalConnection.joinedEndpoints
                mutation.initialJoinedEndpoints.asSequence()
                    .filter { peer -> peer.railId !in preparedBatch.excludedExistingRailIds }
                    .filterNot(finalTargets::contains)
                    .forEach { removedPeer ->
                        val peerFinal = finalByEndpoint[removedPeer]
                        require(peerFinal != null && mutation.endpoint !in peerFinal.joinedEndpoints) {
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
                    mutation.initialJoinedEndpoints != mutation.finalConnection.joinedEndpoints ||
                    mutation.initialActiveEndpoint != mutation.finalConnection.activeEndpoint
            }.forEach { changed ->
                val queue = ArrayDeque<RailEndpointRef>()
                queue += changed.endpoint
                while (queue.isNotEmpty()) {
                    val endpoint = queue.removeFirst()
                    if (!visitedEndpoints.add(endpoint)) continue
                    val mutation = finalByEndpoint.getValue(endpoint)
                    if (isExistingTarget(endpoint.railId)) affectedExistingEndpoints += endpoint
                    mutation.joinedEndpoints.forEach(queue::addLast)
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
                world.topologyState,
                affectedRails,
                mutations,
                world.railInventorySize(),
                affectedExistingEndpoints,
                crossingExistingIds
            ).also { it.requireCurrent(world) }
        }

        private fun endpointPosition(
            world: CoreWorld,
            creationsById: Map<UUID, PreparedRailCreation>,
            endpoint: RailEndpointRef
        ) = creationsById[endpoint.railId]?.curve?.let { curve ->
            if (endpoint.endpoint == RailEndpoint.A) curve.getPointAt(0.0).position
            else curve.getPointAt(curve.totalArcLength).position
        } ?: endpointView(requireNotNull(world.getRail(endpoint.railId)), endpoint.endpoint).endPoint.position
    }
}

private data class EndpointMutation(
    val endpoint: RailEndpointRef,
    val initialConnections: List<RailEndpointRef>,
    val initialActiveEndpoint: RailEndpointRef?,
    val initialJoinedEndpoints: Set<RailEndpointRef>,
    val wasExisting: Boolean,
    val finalConnection: PreparedRailEndpointConnection
)

private fun endpointView(rail: CoreRail, endpoint: RailEndpoint): RailView =
    if (endpoint == RailEndpoint.A) rail.viewBA else rail.viewAB

/** A next-away view starts at the canonical endpoint through which it is entered. */
private fun endpointOfAwayView(view: RailView): RailEndpointRef = RailEndpointRef(
    view.rail.id,
    if (view.viewDirection == ViewDirection.AB) RailEndpoint.A else RailEndpoint.B
)

/** Geometric default only; caller-provided order resolves exact angle ties. */
internal fun straightestEndpoint(
    endpoint: RailEndpointRef,
    connected: List<RailEndpointRef>,
    curveFor: (UUID) -> work.art1st.javarailways.core.api.physics.curve.PrimeCurve
): RailEndpointRef? {
    fun tangentAt(port: RailEndpointRef): org.joml.Vector3dc {
        val curve = curveFor(port.railId)
        return curve.getPointAt(if (port.endpoint == RailEndpoint.A) 0.0 else curve.totalArcLength).tangent
    }
    val incoming = tangentAt(endpoint)
    val incomingSign = if (endpoint.endpoint == RailEndpoint.A) -1.0 else 1.0
    return connected.maxByOrNull { target ->
        val outgoing = tangentAt(target)
        val outgoingSign = if (target.endpoint == RailEndpoint.A) 1.0 else -1.0
        incomingSign * outgoingSign * incoming.dot(outgoing) / (incoming.length() * outgoing.length())
    }
}
