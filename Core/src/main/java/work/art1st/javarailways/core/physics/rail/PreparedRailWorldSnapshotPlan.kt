package work.art1st.javarailways.core.physics.rail

import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.AtGradeRailCrossing
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.rail.topology.TopologyInput
import work.art1st.javarailways.core.physics.rail.topology.TopologyInputRail
import work.art1st.javarailways.core.physics.rail.topology.TopologyStateBuilder
import java.util.Collections
import java.util.LinkedHashSet
import java.util.UUID

/** One exact target endpoint state decoded from a saved rail snapshot. */
internal data class SnapshotEndpointState(
    val connectedEndpoints: List<RailEndpointRef>,
    val activeEndpoint: RailEndpointRef?
)

/**
 * Off-thread prepared structural part of one whole-world RailWand snapshot restore.
 *
 * Bukkit chooses the current/target snapshots. Core derives their exact physical diff, prepares
 * only added/replaced geometry through [PreparedRailCreationBatch], and binds the result to one
 * live topology root. The target snapshot is the sole source of connection and active-branch state;
 * no nearby endpoint search creates an undeclared connection.
 */
class PreparedRailWorldSnapshotPlan private constructor(
    @get:JvmSynthetic internal val ownerWorld: CoreWorld,
    val topologyRoot: TopologyState,
    currentRails: Map<UUID, CoreRail>,
    removedOrReplacedRailIds: Set<UUID>,
    createdOrReplacedRailIds: Set<UUID>,
    changedExistingEndpoints: Set<RailEndpointRef>,
    @get:JvmSynthetic internal val preparedCreations: PreparedRailCreationBatch?,
    @get:JvmSynthetic internal val targetEndpointStates: Map<RailEndpointRef, SnapshotEndpointState>,
    removedCrossings: Set<AtGradeRailCrossing>,
    affectedExistingRailIds: Set<UUID>,
    private val capturedEndpointStates: Map<RailEndpointRef, SnapshotEndpointState>,
    val topologyUpdate: PreparedRailTopologyUpdate
) {
    private val currentRails: Map<UUID, CoreRail> = Collections.unmodifiableMap(LinkedHashMap(currentRails))
    val removedOrReplacedRailIds: Set<UUID> =
        Collections.unmodifiableSet(LinkedHashSet(removedOrReplacedRailIds))
    val createdOrReplacedRailIds: Set<UUID> =
        Collections.unmodifiableSet(LinkedHashSet(createdOrReplacedRailIds))
    val changedExistingEndpoints: Set<RailEndpointRef> =
        Collections.unmodifiableSet(LinkedHashSet(changedExistingEndpoints))
    val removedCrossings: Set<AtGradeRailCrossing> =
        Collections.unmodifiableSet(LinkedHashSet(removedCrossings))
    val affectedExistingRailIds: Set<UUID> =
        Collections.unmodifiableSet(LinkedHashSet(affectedExistingRailIds))

    fun isCurrent(candidateWorld: CoreWorld): Boolean =
        candidateWorld === ownerWorld && candidateWorld.topologyState === topologyRoot &&
            currentRails.all { (railId, rail) -> candidateWorld.getRail(railId) === rail } &&
            capturedEndpointStates.all { (endpoint, state) ->
                candidateWorld.getRail(endpoint.railId)?.let { rail -> captureEndpoint(rail, endpoint.endpoint) } == state
            } &&
            (preparedCreations == null || preparedCreations.topologyRoot === topologyRoot)

    fun requireCurrent(candidateWorld: CoreWorld) {
        check(isCurrent(candidateWorld)) {
            "Prepared rail-world snapshot plan is stale; rebuild it from the current snapshot"
        }
    }

    fun requireReadyToApply(candidateWorld: CoreWorld) {
        requireCurrent(candidateWorld)
        val occupied = candidateWorld.occupyingTrainIds(affectedExistingRailIds)
        require(occupied.isEmpty()) {
            "Cannot restore rail-world snapshot; affected rails are occupied by ${occupied.sorted()}"
        }
    }

    @JvmSynthetic
    fun apply(candidateWorld: CoreWorld) = candidateWorld.applyPreparedRailWorldSnapshot(this)

    companion object {
        @JvmSynthetic
        internal fun create(
            world: CoreWorld,
            topologyRoot: TopologyState,
            currentById: Map<UUID, CoreRail>,
            currentSaved: Map<UUID, CoreRail.SavedRailV1>,
            targetSaved: Map<UUID, CoreRail.SavedRailV1>,
            removedOrReplacedRailIds: Set<UUID>,
            createdOrReplacedRailIds: Set<UUID>,
            preparedCreations: PreparedRailCreationBatch?,
            rawCurrentCrossings: List<AtGradeRailCrossing>
        ): PreparedRailWorldSnapshotPlan {
            val currentCurves = currentSaved.mapValues { (_, saved) -> saved.curveAB.load(Unit) }
            val targetCurves = targetSaved.mapValues { (_, saved) -> saved.curveAB.load(Unit) }
            val currentStates = decodeEndpointStates(currentSaved, currentCurves)
            val targetStates = decodeEndpointStates(targetSaved, targetCurves)
            val changedExistingEndpoints = currentSaved.keys.intersect(targetSaved.keys)
                .asSequence()
                .filterNot(removedOrReplacedRailIds::contains)
                .flatMap { railId -> RailEndpoint.entries.asSequence().map { RailEndpointRef(railId, it) } }
                .filterTo(linkedSetOf()) { endpoint ->
                    currentStates.getValue(endpoint) != targetStates.getValue(endpoint)
                }

            require((createdOrReplacedRailIds.isEmpty()) == (preparedCreations == null)) {
                "snapshot geometry preparation does not match its explicit stable IDs"
            }
            preparedCreations?.let { batch ->
                require(batch.ownerWorld === world && batch.topologyRoot === topologyRoot)
                require(batch.excludedExistingRailIds == removedOrReplacedRailIds)
                require(batch.creations.mapTo(linkedSetOf(), PreparedRailCreation::railId) == createdOrReplacedRailIds)
            }

            val rawCurrentCrossingsByPair = rawCurrentCrossings.associateBy { crossing ->
                AtGradeRailCrossingIndex.RailPair.of(crossing.first.railId, crossing.second.railId)
            }
            val rawTargetCrossings = rawCurrentCrossings
                .filterNot { crossing -> crossingTouches(crossing, removedOrReplacedRailIds) } +
                preparedCreations?.crossings.orEmpty()
            val structuralConnectionsChanged = currentStates.keys != targetStates.keys ||
                currentStates.any { (endpoint, state) ->
                    state.connectedEndpoints.toSet() !=
                        targetStates[endpoint]?.connectedEndpoints?.toSet()
                }
            val structuralSnapshotChanged = removedOrReplacedRailIds.isNotEmpty() ||
                createdOrReplacedRailIds.isNotEmpty() || structuralConnectionsChanged
            val targetTopology = if (structuralSnapshotChanged) {
                TopologyStateBuilder.build(
                    TopologyInput(
                        revision = Math.addExact(topologyRoot.revision, 1L),
                        rails = targetCurves.map { (railId, curve) ->
                            TopologyInputRail(railId, curve.totalArcLength)
                        },
                        continuations = targetStates.mapValues { (_, state) ->
                            state.connectedEndpoints.toSet()
                        },
                        atGradeCrossings = rawTargetCrossings
                    )
                )
            } else {
                topologyRoot
            }
            val removedCrossings = topologyRoot.atGradeCrossings.toSet() - targetTopology.atGradeCrossings.toSet()
            val introducedCrossings = targetTopology.atGradeCrossings.toSet() - topologyRoot.atGradeCrossings.toSet()
            val changedConnectionIds = linkedSetOf<UUID>().apply {
                changedExistingEndpoints.mapTo(this, RailEndpointRef::railId)
                changedExistingEndpoints.flatMapTo(this) { endpoint ->
                    val state = currentStates.getValue(endpoint)
                    state.connectedEndpoints.map(RailEndpointRef::railId)
                }
            }
            val affectedExistingIds = linkedSetOf<UUID>().apply {
                addAll(removedOrReplacedRailIds)
                addAll(changedConnectionIds)
                (removedCrossings + introducedCrossings).forEach { crossing ->
                    sequenceOf(crossing.first.railId, crossing.second.railId)
                        .filter(currentById::containsKey)
                        .forEach(::add)
                }
                removedOrReplacedRailIds.forEach { railId ->
                    RailEndpoint.entries.forEach { endpoint ->
                        currentStates.getValue(RailEndpointRef(railId, endpoint)).connectedEndpoints
                            .mapTo(this, RailEndpointRef::railId)
                    }
                }
            }
            val capturedStates = affectedExistingIds.asSequence()
                .flatMap { railId -> RailEndpoint.entries.asSequence().map { RailEndpointRef(railId, it) } }
                .associateWith(currentStates::getValue)

            val expectedConnections = linkedMapOf<RailEndpointRef, Set<RailEndpointRef>>()
            changedExistingEndpoints.forEach { endpoint ->
                expectedConnections[endpoint] = targetStates.getValue(endpoint).connectedEndpoints.toSet()
            }
            createdOrReplacedRailIds.forEach { railId ->
                RailEndpoint.entries.forEach { endpoint ->
                    val ref = RailEndpointRef(railId, endpoint)
                    expectedConnections[ref] = targetStates.getValue(ref).connectedEndpoints.toSet()
                }
            }
            val expectedCrossings = linkedMapOf<AtGradeRailCrossingIndex.RailPair, AtGradeRailCrossing?>().apply {
                rawCurrentCrossings.asSequence()
                    .filter { crossing -> crossingTouches(crossing, removedOrReplacedRailIds) }
                    .forEach { crossing ->
                        this[AtGradeRailCrossingIndex.RailPair.of(
                            crossing.first.railId,
                            crossing.second.railId
                        )] = null
                    }
                preparedCreations?.crossings.orEmpty().forEach { crossing ->
                    this[AtGradeRailCrossingIndex.RailPair.of(
                        crossing.first.railId,
                        crossing.second.railId
                    )] = crossing
                }
            }
            val baseExpectationRailIds = linkedSetOf<UUID>().apply {
                addAll(affectedExistingIds)
                addAll(createdOrReplacedRailIds)
            }
            val topologyUpdate = PreparedRailTopologyUpdate(
                ownerWorld = world,
                baseRoot = topologyRoot,
                targetRoot = targetTopology,
                expectedRailCount = targetCurves.size,
                baseRailCount = currentById.size,
                baseRails = baseExpectationRailIds.associateWith(currentById::get),
                baseConnections = affectedExistingIds.asSequence()
                    .flatMap { railId ->
                        RailEndpoint.entries.asSequence().map { endpoint -> RailEndpointRef(railId, endpoint) }
                    }
                    .associateWith { endpoint -> currentStates.getValue(endpoint).connectedEndpoints.toSet() },
                baseCrossings = expectedCrossings.keys.associateWith(rawCurrentCrossingsByPair::get),
                expectedAddedRailCurves = preparedCreations?.creations.orEmpty()
                    .associate { creation -> creation.railId to creation.curve },
                removedRailIds = removedOrReplacedRailIds,
                expectedConnections = expectedConnections,
                expectedCrossings = expectedCrossings
            )
            return PreparedRailWorldSnapshotPlan(
                world, topologyRoot, currentById.filterKeys(affectedExistingIds::contains),
                removedOrReplacedRailIds, createdOrReplacedRailIds, changedExistingEndpoints,
                preparedCreations, targetStates, removedCrossings, affectedExistingIds, capturedStates,
                topologyUpdate
            )
        }

        private fun decodeEndpointStates(
            savedById: Map<UUID, CoreRail.SavedRailV1>,
            curvesById: Map<UUID, work.art1st.javarailways.core.api.physics.curve.PrimeCurve>
        ): Map<RailEndpointRef, SnapshotEndpointState> {
            fun endpointPosition(railId: UUID, endpoint: RailEndpoint) = curvesById.getValue(railId).let { curve ->
                curve.getPointAt(if (endpoint == RailEndpoint.A) 0.0 else curve.totalArcLength).position
            }
            fun targetEndpoint(source: RailEndpointRef, targetRailId: UUID): RailEndpointRef {
                val sourcePosition = endpointPosition(source.railId, source.endpoint)
                val distanceA = sourcePosition.distance(endpointPosition(targetRailId, RailEndpoint.A))
                val distanceB = sourcePosition.distance(endpointPosition(targetRailId, RailEndpoint.B))
                val endpoint = if (distanceA < distanceB) RailEndpoint.A else RailEndpoint.B
                require(minOf(distanceA, distanceB) <= Curve.TOLERANCE) {
                    "snapshot connection endpoints are not colocated: $source -> $targetRailId"
                }
                return RailEndpointRef(targetRailId, endpoint)
            }
            val states = linkedMapOf<RailEndpointRef, SnapshotEndpointState>()
            savedById.forEach { (railId, saved) ->
                fun add(endpoint: RailEndpoint, nextIds: List<String>, activeId: String?) {
                    val source = RailEndpointRef(railId, endpoint)
                    val targets = nextIds.map { text ->
                        val targetId = UUID.fromString(text)
                        require(targetId in savedById) { "snapshot connection references missing rail $targetId" }
                        targetEndpoint(source, targetId)
                    }
                    require(targets.distinct().size == targets.size && targets.size <= 2)
                    val active = activeId?.let(UUID::fromString)?.let { activeRailId ->
                        requireNotNull(targets.singleOrNull { it.railId == activeRailId }) {
                            "snapshot active rail $activeRailId is not connected at $source"
                        }
                    }
                    states[source] = SnapshotEndpointState(targets, active)
                }
                add(RailEndpoint.A, saved.nextRailAtA, saved.activeTrackAtA)
                add(RailEndpoint.B, saved.nextRailAtB, saved.activeTrackAtB)
            }
            states.forEach { (source, state) ->
                state.connectedEndpoints.forEach { target ->
                    require(source in states.getValue(target).connectedEndpoints) {
                        "snapshot connection is not symmetric: $source <-> $target"
                    }
                }
            }
            return Collections.unmodifiableMap(states)
        }

        private fun crossingTouches(crossing: AtGradeRailCrossing, railIds: Set<UUID>): Boolean =
            crossing.first.railId in railIds || crossing.second.railId in railIds

        private fun captureEndpoint(rail: CoreRail, endpoint: RailEndpoint): SnapshotEndpointState {
            val view = if (endpoint == RailEndpoint.A) rail.viewBA else rail.viewAB
            return SnapshotEndpointState(
                view.mutableNextRails.map(::endpointOfAwayView),
                view.activeRail?.let(::endpointOfAwayView)
            )
        }

        private fun endpointOfAwayView(view: RailView): RailEndpointRef = RailEndpointRef(
            view.rail.id,
            if (view.viewDirection == ViewDirection.AB) RailEndpoint.A else RailEndpoint.B
        )
    }
}
