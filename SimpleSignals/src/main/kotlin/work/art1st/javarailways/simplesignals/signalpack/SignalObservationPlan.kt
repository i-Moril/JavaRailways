package work.art1st.javarailways.simplesignals.signalpack

import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.simplesignals.region.SIMPLE_SIGNALS_NAMESPACED_ID
import work.art1st.javarailways.simplesignals.runtime.RequiredActiveContinuation
import work.art1st.javarailways.simplesignals.runtime.SectionKind
import work.art1st.javarailways.simplesignals.runtime.SectionRef
import java.util.Collections

/**
 * Complete directed identity used for cycle detection on one observation branch.
 *
 * A section ID alone is insufficient: the same physical section may be entered from its other end,
 * through another authored boundary, or in another protection-route context without closing a loop.
 */
internal data class SignalObservationScanState(
    val section: SectionRef,
    val direction: ViewDirection,
    val entryBoundaryId: String,
    val protectionRouteId: String? = null
) : Comparable<SignalObservationScanState> {
    init {
        require(SIMPLE_SIGNALS_NAMESPACED_ID.matches(section.id)) {
            "signal observation section ID must be namespaced"
        }
        require(SIMPLE_SIGNALS_NAMESPACED_ID.matches(entryBoundaryId)) {
            "signal observation entry boundary ID must be namespaced"
        }
        require(protectionRouteId == null || SIMPLE_SIGNALS_NAMESPACED_ID.matches(protectionRouteId)) {
            "signal observation protection route ID must be namespaced"
        }
        require(section.kind == SectionKind.PROTECTION_ROUTE || protectionRouteId == null) {
            "only protection-route sections may carry a protection route context"
        }
    }

    override fun compareTo(other: SignalObservationScanState): Int = compareValuesBy(
        this,
        other,
        { it.section },
        { it.direction },
        { it.entryBoundaryId },
        { it.protectionRouteId.orEmpty() }
    )
}

/** Runtime selector metadata retained on one possible continuation; it never selects a route. */
internal class SignalObservationBranchRequirements(
    requiredContinuations: Collection<RequiredActiveContinuation> = emptyList(),
    val protectionRoute: ProtectionRouteChoice? = null
) {
    val requiredContinuations: List<RequiredActiveContinuation> = java.util.List.copyOf(requiredContinuations)

    init {
        require(this.requiredContinuations.map(RequiredActiveContinuation::entry).distinct().size ==
            this.requiredContinuations.size
        ) { "signal observation branch continuation entries must be unique" }
    }

    override fun equals(other: Any?): Boolean = other is SignalObservationBranchRequirements &&
        requiredContinuations == other.requiredContinuations && protectionRoute == other.protectionRoute

    override fun hashCode(): Int = 31 * requiredContinuations.hashCode() + protectionRoute.hashCode()
}

internal data class ProtectionRouteChoice(val entryBoundaryId: String, val routeId: String) :
    Comparable<ProtectionRouteChoice> {
    init {
        require(SIMPLE_SIGNALS_NAMESPACED_ID.matches(entryBoundaryId)) {
            "protection-route branch entry boundary ID must be namespaced"
        }
        require(SIMPLE_SIGNALS_NAMESPACED_ID.matches(routeId)) {
            "protection-route branch route ID must be namespaced"
        }
    }

    override fun compareTo(other: ProtectionRouteChoice): Int =
        compareValuesBy(this, other, ProtectionRouteChoice::entryBoundaryId, ProtectionRouteChoice::routeId)
}

/** A prepared graph edge. [distanceMeters] is unsectioned track between both section boundaries. */
internal data class SignalObservationContinuation(
    val target: SignalObservationScanState?,
    val distanceMeters: Double = 0.0,
    val requirements: SignalObservationBranchRequirements = SignalObservationBranchRequirements(),
    val terminalReason: SignalObservationEndReason? = null,
    /** Relative to the source section's exit, only for an unsectioned [SignalObservationEndReason.CYCLE]. */
    val cycleStartDistanceMeters: Double? = null,
    val cycleLengthMeters: Double? = null
) {
    init {
        require(distanceMeters.isFinite() && distanceMeters >= 0.0) {
            "signal observation continuation distance must be finite and non-negative"
        }
        require((target == null) != (terminalReason == null)) {
            "a signal observation continuation must have exactly one target or terminal reason"
        }
        require(terminalReason != SignalObservationEndReason.DISTANCE_LIMIT) {
            "distance termination is derived by the plan compiler"
        }
        require((terminalReason == SignalObservationEndReason.CYCLE) ==
            (cycleStartDistanceMeters != null && cycleLengthMeters != null)
        ) { "only an unsectioned cycle continuation may contain complete cycle metrics" }
        cycleStartDistanceMeters?.let {
            require(it.isFinite() && it >= 0.0 && it <= distanceMeters) {
                "unsectioned cycle start distance must lie on its continuation"
            }
        }
        cycleLengthMeters?.let {
            require(it.isFinite() && it > 0.0) {
                "unsectioned directed cycles must have positive length"
            }
            require(kotlin.math.abs(checkNotNull(cycleStartDistanceMeters) + it - distanceMeters) <=
                work.art1st.javarailways.core.api.physics.curve.Curve.TOLERANCE
            ) { "unsectioned cycle metrics must end at the continuation termination" }
        }
    }
}

internal enum class SignalObservationEndReason {
    DISTANCE_LIMIT,
    TRACK_END,
    CYCLE,
    ROUTE_UNRESOLVED,
    TOPOLOGY_UNAVAILABLE
}

/**
 * One section in the low-frequency prepared graph. Dynamic occupancy and route selection are absent.
 *
 * [terminalReason] is only for a statically known leaf. A protection entry with candidate routes
 * uses protection-route continuation requirements instead; the later epoch projection chooses one
 * or reports ROUTE_UNRESOLVED without rebuilding this graph.
 */
internal class SignalObservationGraphNode(
    val state: SignalObservationScanState,
    val lengthMeters: Double,
    val signalId: String? = null,
    continuations: Collection<SignalObservationContinuation> = emptyList(),
    val terminalReason: SignalObservationEndReason? = null,
    additionalSignalIds: Collection<String> = emptyList()
) {
    val continuations: List<SignalObservationContinuation> = java.util.List.copyOf(continuations)
    /** Every logical signal at this directed entry; attached displays are resolved separately. */
    val signalIds: Set<String> = immutableSet(listOfNotNull(signalId) + additionalSignalIds)

    init {
        require(lengthMeters.isFinite() && lengthMeters > 0.0) {
            "signal observation section length must be finite and positive"
        }
        require(signalIds.all(SIMPLE_SIGNALS_NAMESPACED_ID::matches)) {
            "signal observation signal IDs must be namespaced"
        }
        require(terminalReason != SignalObservationEndReason.DISTANCE_LIMIT &&
            terminalReason != SignalObservationEndReason.CYCLE
        ) { "distance and cycle termination are derived by the plan compiler" }
        require(terminalReason == null || this.continuations.isEmpty()) {
            "a terminal signal observation section cannot have continuations"
        }
        require(this.continuations.distinct().size == this.continuations.size) {
            "signal observation section continuations must be unique"
        }
    }
}

/** Immutable section graph prepared from one exact topology/definition publication. */
internal class SignalObservationGraph(nodes: Collection<SignalObservationGraphNode>) {
    val nodesByState: Map<SignalObservationScanState, SignalObservationGraphNode>

    init {
        val ordered = linkedMapOf<SignalObservationScanState, SignalObservationGraphNode>()
        nodes.forEach { node ->
            require(ordered.putIfAbsent(node.state, node) == null) {
                "signal observation scan states must be unique"
            }
        }
        val states = ordered.keys
        ordered.values.forEach { node ->
            node.continuations.forEach { continuation ->
                continuation.target?.let { target ->
                    require(target in states) {
                        "signal observation continuation targets unknown state $target"
                    }
                }
                continuation.requirements.protectionRoute?.let { route ->
                    val target = ordered.getValue(checkNotNull(continuation.target)).state
                    require(target.section.kind == SectionKind.PROTECTION_ROUTE &&
                        target.entryBoundaryId == route.entryBoundaryId &&
                        target.protectionRouteId == route.routeId
                    ) {
                        "protection-route branch must target its matching directed route section"
                    }
                }
            }
        }
        nodesByState = Collections.unmodifiableMap(ordered)
    }
}

internal data class SignalObservationTermination(
    val reason: SignalObservationEndReason,
    val distanceMeters: Double,
    val cycleStartDistanceMeters: Double? = null,
    val cycleLengthMeters: Double? = null
) {
    init {
        require(distanceMeters.isFinite() && distanceMeters >= 0.0)
        require((reason == SignalObservationEndReason.CYCLE) ==
            (cycleStartDistanceMeters != null && cycleLengthMeters != null)
        ) { "only cycle termination may contain complete cycle metrics" }
        cycleStartDistanceMeters?.let { require(it.isFinite() && it >= 0.0 && it <= distanceMeters) }
        cycleLengthMeters?.let { require(it.isFinite() && it > 0.0) }
    }
}

/** One occurrence in the public path tree; merged physical graph nodes are deliberately duplicated. */
internal class SignalObservationPlanNode internal constructor(
    val state: SignalObservationScanState,
    val distanceMeters: Double,
    val lengthMeters: Double,
    val signalId: String?,
    signalIds: Collection<String>,
    val exposesDownstreamSignal: Boolean,
    branches: Collection<SignalObservationPlanBranch>,
    val termination: SignalObservationTermination?
) {
    val signalIds: Set<String> = immutableSet(signalIds)
    val branches: List<SignalObservationPlanBranch> = java.util.List.copyOf(branches)
}

internal class SignalObservationPlanBranch internal constructor(
    val requirements: SignalObservationBranchRequirements,
    val target: SignalObservationPlanNode?,
    val termination: SignalObservationTermination?
) {
    init {
        require((target == null) != (termination == null)) {
            "an observation branch must contain exactly one target or termination"
        }
    }
}

/** Complete immutable result compiled for one logical signal and one maximum scan distance. */
internal class SignalObservationPlan internal constructor(
    val maximumDistanceMeters: Double,
    val root: SignalObservationPlanNode,
    observedSections: Collection<SectionRef>,
    downstreamSignalIds: Collection<String>,
    watchedContinuationEntries: Collection<RailEndpointRef>,
    protectionRoutes: Collection<ProtectionRouteChoice>
) {
    val observedSections: Set<SectionRef> = immutableSet(observedSections)
    val downstreamSignalIds: Set<String> = immutableSet(downstreamSignalIds)
    val watchedContinuationEntries: Set<RailEndpointRef> = immutableSet(watchedContinuationEntries)
    val protectionRoutes: Set<ProtectionRouteChoice> = immutableSet(protectionRoutes)
}

/** Expands the prepared graph into path occurrences and terminates only the branch that closes a cycle. */
internal object SignalObservationPlanCompiler {
    fun compile(
        graph: SignalObservationGraph,
        rootState: SignalObservationScanState,
        maximumDistanceMeters: Double
    ): SignalObservationPlan {
        require(maximumDistanceMeters.isFinite() && maximumDistanceMeters > 0.0) {
            "signal observation maximum distance must be finite and positive"
        }
        require(rootState in graph.nodesByState) { "signal observation root state is absent from the graph" }

        val path = ArrayList<PathOccurrence>()
        val pathIndexes = HashMap<SignalObservationScanState, Int>()
        val mutableRoot = expand(graph, rootState, 0.0, maximumDistanceMeters, path, pathIndexes).node
        val root = freeze(mutableRoot)
        val observedSections = linkedSetOf<SectionRef>()
        val downstreamSignals = linkedSetOf<String>()
        val watchedEntries = linkedSetOf<RailEndpointRef>()
        val protectionRoutes = linkedSetOf<ProtectionRouteChoice>()
        collectDependencies(root, root = true, observedSections, downstreamSignals, watchedEntries, protectionRoutes)
        return SignalObservationPlan(
            maximumDistanceMeters,
            root,
            observedSections,
            downstreamSignals,
            watchedEntries,
            protectionRoutes
        )
    }

    private fun expand(
        graph: SignalObservationGraph,
        state: SignalObservationScanState,
        distanceMeters: Double,
        maximumDistanceMeters: Double,
        path: MutableList<PathOccurrence>,
        pathIndexes: MutableMap<SignalObservationScanState, Int>
    ): Expansion {
        val source = graph.nodesByState.getValue(state)
        val node = MutablePlanNode(source, distanceMeters)
        check(pathIndexes.put(state, path.size) == null) { "signal observation path state was already active" }
        path += PathOccurrence(distanceMeters)
        try {
            val sectionEnd = distanceMeters + source.lengthMeters
            if (!sectionEnd.isFinite()) throw IllegalArgumentException("signal observation distance overflow")
            if (sectionEnd >= maximumDistanceMeters) {
                node.termination = SignalObservationTermination(
                    SignalObservationEndReason.DISTANCE_LIMIT,
                    maximumDistanceMeters
                )
                return Expansion(node)
            }
            source.terminalReason?.let { reason ->
                node.termination = SignalObservationTermination(
                    reason,
                    if (reason == SignalObservationEndReason.TRACK_END) sectionEnd else distanceMeters
                )
                return Expansion(node)
            }
            if (source.continuations.isEmpty()) {
                node.termination = SignalObservationTermination(SignalObservationEndReason.TRACK_END, sectionEnd)
                return Expansion(node)
            }

            var earliestCycleStartIndex: Int? = null
            source.continuations.forEach { continuation ->
                val nextDistance = sectionEnd + continuation.distanceMeters
                if (!nextDistance.isFinite()) throw IllegalArgumentException("signal observation distance overflow")
                if (nextDistance >= maximumDistanceMeters) {
                    node.branches += MutablePlanBranch(
                        continuation.requirements,
                        termination = SignalObservationTermination(
                            SignalObservationEndReason.DISTANCE_LIMIT,
                            maximumDistanceMeters
                        )
                    )
                    return@forEach
                }
                continuation.terminalReason?.let { reason ->
                    node.branches += MutablePlanBranch(
                        continuation.requirements,
                        termination = if (reason == SignalObservationEndReason.CYCLE) {
                            SignalObservationTermination(
                                reason,
                                nextDistance,
                                sectionEnd + checkNotNull(continuation.cycleStartDistanceMeters),
                                checkNotNull(continuation.cycleLengthMeters)
                            )
                        } else {
                            SignalObservationTermination(reason, nextDistance)
                        }
                    )
                    return@forEach
                }
                val target = checkNotNull(continuation.target)
                val repeatedAt = pathIndexes[target]
                if (repeatedAt != null) {
                    val cycleStart = path[repeatedAt].distanceMeters
                    val cycleLength = nextDistance - cycleStart
                    require(cycleLength.isFinite() && cycleLength > 0.0) {
                        "signal observation contains a zero-length directed cycle"
                    }
                    earliestCycleStartIndex = minOf(earliestCycleStartIndex ?: repeatedAt, repeatedAt)
                    val repeated = MutablePlanNode(graph.nodesByState.getValue(target), nextDistance).also {
                        it.exposesDownstreamSignal = false
                        it.termination = SignalObservationTermination(
                            SignalObservationEndReason.CYCLE,
                            nextDistance,
                            cycleStart,
                            cycleLength
                        )
                    }
                    node.branches += MutablePlanBranch(continuation.requirements, target = repeated)
                } else {
                    val expansion = expand(
                        graph,
                        target,
                        nextDistance,
                        maximumDistanceMeters,
                        path,
                        pathIndexes
                    )
                    node.branches += MutablePlanBranch(
                        continuation.requirements,
                        target = expansion.node
                    )
                    expansion.earliestCycleStartIndex?.let { cycleStartIndex ->
                        earliestCycleStartIndex = minOf(
                            earliestCycleStartIndex ?: cycleStartIndex,
                            cycleStartIndex
                        )
                    }
                }
            }
            if (earliestCycleStartIndex != null && earliestCycleStartIndex <= path.lastIndex) {
                node.exposesDownstreamSignal = false
            }
            return Expansion(node, earliestCycleStartIndex)
        } finally {
            path.removeAt(path.lastIndex)
            check(pathIndexes.remove(state) != null) { "signal observation path index was lost" }
        }
    }

    private fun freeze(node: MutablePlanNode): SignalObservationPlanNode = SignalObservationPlanNode(
        node.source.state,
        node.distanceMeters,
        node.source.lengthMeters,
        node.source.signalId,
        node.source.signalIds,
        node.exposesDownstreamSignal,
        node.branches.map { branch ->
            SignalObservationPlanBranch(
                branch.requirements,
                branch.target?.let(::freeze),
                branch.termination
            )
        },
        node.termination
    )

    private fun collectDependencies(
        node: SignalObservationPlanNode,
        root: Boolean,
        sections: MutableSet<SectionRef>,
        signals: MutableSet<String>,
        entries: MutableSet<RailEndpointRef>,
        routes: MutableSet<ProtectionRouteChoice>
    ) {
        sections += node.state.section
        if (!root && node.exposesDownstreamSignal) signals.addAll(node.signalIds)
        node.branches.forEach { branch ->
            branch.requirements.requiredContinuations.mapTo(entries, RequiredActiveContinuation::entry)
            branch.requirements.protectionRoute?.let(routes::add)
            branch.target?.let { collectDependencies(it, false, sections, signals, entries, routes) }
        }
    }

    private data class PathOccurrence(val distanceMeters: Double)

    private data class Expansion(
        val node: MutablePlanNode,
        val earliestCycleStartIndex: Int? = null
    )

    private class MutablePlanNode(val source: SignalObservationGraphNode, val distanceMeters: Double) {
        var exposesDownstreamSignal: Boolean = true
        val branches = arrayListOf<MutablePlanBranch>()
        var termination: SignalObservationTermination? = null
    }

    private data class MutablePlanBranch(
        val requirements: SignalObservationBranchRequirements,
        val target: MutablePlanNode? = null,
        val termination: SignalObservationTermination? = null
    )
}

private fun <T> immutableSet(values: Collection<T>): Set<T> =
    Collections.unmodifiableSet(LinkedHashSet(values))
