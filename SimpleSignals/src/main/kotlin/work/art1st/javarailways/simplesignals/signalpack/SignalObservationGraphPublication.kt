package work.art1st.javarailways.simplesignals.signalpack

import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyNode
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.simplesignals.block.FixedBlockReadSnapshot
import work.art1st.javarailways.simplesignals.block.FixedBlockTraversal
import work.art1st.javarailways.simplesignals.persistence.SimpleSignalsWorldDefinitions
import work.art1st.javarailways.simplesignals.region.ProtectionRegionCompilationSnapshot
import work.art1st.javarailways.simplesignals.region.ProtectionRegionTopology
import work.art1st.javarailways.simplesignals.runtime.RequiredActiveContinuation
import work.art1st.javarailways.simplesignals.runtime.SectionKind
import work.art1st.javarailways.simplesignals.runtime.SectionRef
import work.art1st.javarailways.simplesignals.signal.FixedBlockEntryDirection
import work.art1st.javarailways.simplesignals.signal.ProtectionRouteSelectionIndex
import work.art1st.javarailways.simplesignals.signal.ProtectionSignalKind
import java.util.Collections
import java.util.UUID
import kotlin.math.abs

/** One logical signal entry. A current protection entry may have one root for each authored route. */
internal data class SignalObservationRoot(
    val state: SignalObservationScanState,
    val requirements: SignalObservationBranchRequirements = SignalObservationBranchRequirements()
)

/**
 * Low-frequency graph publication compiled from one exact world-definition/topology publication.
 *
 * The retained topology and definitions are the cache identity. Consumers must compare them by
 * reference, or retain this object from the owning [SimpleSignalsRuntimePublication], rather than
 * accepting another publication with equal revision numbers.
 */
internal class SignalObservationGraphPublication internal constructor(
    val topology: TopologyState,
    val definitions: SimpleSignalsWorldDefinitions,
    val graph: SignalObservationGraph,
    logicalRootsBySignalId: Map<String, List<SignalObservationRoot>>,
    deviceRootsBySignalId: Map<String, List<SignalObservationRoot>>
) {
    val definitionRevision: Long get() = definitions.definitionRevision
    val logicalRootsBySignalId: Map<String, List<SignalObservationRoot>> = immutableRootMap(logicalRootsBySignalId)
    /** Includes attached display identities, which reuse their primary logical signal's roots. */
    val deviceRootsBySignalId: Map<String, List<SignalObservationRoot>> = immutableRootMap(deviceRootsBySignalId)

    fun isFor(topology: TopologyState, definitions: SimpleSignalsWorldDefinitions): Boolean =
        this.topology === topology && this.definitions === definitions
}

/** Converts the existing section catalogs and normalized Core topology into the plan compiler's graph. */
internal object SignalObservationGraphBuilder {
    fun compile(
        topology: TopologyState,
        definitions: SimpleSignalsWorldDefinitions,
        fixedBlocks: FixedBlockReadSnapshot,
        regions: ProtectionRegionCompilationSnapshot,
        routes: ProtectionRouteSelectionIndex,
        unavailableRegionIds: Set<String> = regions.unavailableRegionIds
    ): SignalObservationGraphPublication {
        require(fixedBlocks.topology === topology) {
            "signal observation fixed blocks target a different topology publication"
        }
        require(regions.topology === topology && routes.topology === topology) {
            "signal observation protection data targets a different topology publication"
        }
        require(fixedBlocks.definitionRevision == definitions.definitionRevision &&
            regions.definitionRevision == definitions.definitionRevision
        ) { "signal observation catalogs target a different definition publication" }

        val topologyView = ProtectionRegionTopology.direct(topology)
        val sections = linkedMapOf<SignalObservationScanState, DirectedSection>()
        val authoredBlocks = definitions.fixedBlocks.associateBy { it.blockId }
        require(authoredBlocks.size == definitions.fixedBlocks.size &&
            authoredBlocks.keys == fixedBlocks.blocksById.keys
        ) { "signal observation fixed-block catalog disagrees with world definitions" }

        fixedBlocks.blocksById.toSortedMap().forEach { (blockId, block) ->
            require(block.definition == authoredBlocks.getValue(blockId)) {
                "signal observation fixed block $blockId is not the authored definition"
            }
            addSection(
                sections,
                fixedSection(blockId, FixedBlockEntryDirection.FORWARD, block.traversals, block.lengthMeters,
                    blockId in fixedBlocks.unavailableBlockIds)
            )
            if (block.definition.bidirectional) {
                addSection(
                    sections,
                    fixedSection(blockId, FixedBlockEntryDirection.REVERSE, block.reverseTraversals,
                        block.lengthMeters, blockId in fixedBlocks.unavailableBlockIds)
                )
            }
        }

        val authoredRoutes = definitions.routeTables.flatMap { it.routes }.associateBy { it.routeId }
        require(authoredRoutes.size == definitions.routeTables.sumOf { it.routes.size }) {
            "signal observation route identities are not unique"
        }
        routes.routesById.toSortedMap().forEach { (routeId, route) ->
            // The enclosing index is already bound by identity to [topology]. An unchanged route
            // may deliberately retain its immutable compiled object from the previous root.
            require(route.definition == authoredRoutes[routeId]) {
                "signal observation protection route $routeId is inconsistent with its publication"
            }
            val traversals = route.definition.traversals.map {
                TravelSpan(it.railId, it.facing, it.travelStartAbMeters, it.travelEndAbMeters)
            }
            addSection(
                sections,
                DirectedSection(
                    state = SignalObservationScanState(
                        SectionRef(SectionKind.PROTECTION_ROUTE, routeId),
                        traversals.first().facing,
                        route.definition.entryBoundaryId,
                        routeId
                    ),
                    traversals = traversals,
                    lengthMeters = traversals.sumOf(TravelSpan::lengthMeters),
                    entryRequirements = SignalObservationBranchRequirements(
                        route.requiredContinuations,
                        ProtectionRouteChoice(route.definition.entryBoundaryId, routeId)
                    )
                )
            )
        }

        val logicalStates = logicalStates(definitions, sections, routes)
        val targets = arrayListOf<EntryTarget>()
        sections.values.forEach { section -> targets += EntryTarget.Section(section.entry, section) }
        addProtectionEntryStops(targets, definitions, regions, routes, unavailableRegionIds)
        val entries = EntryIndex(targets)

        val nodes = sections.values.sortedBy { it.state }.map { section ->
            val signalIds = logicalStates.entries.asSequence().filter { section.state in it.value }
                .map { it.key }.sorted().toList()
            val terminal = if (section.unavailable) SignalObservationEndReason.TOPOLOGY_UNAVAILABLE else null
            val continuations = if (terminal == null) {
                scanFrom(section.exit, entries, topology, topologyView)
            } else {
                emptyList()
            }
            SignalObservationGraphNode(
                section.state,
                section.lengthMeters,
                signalIds.firstOrNull(),
                continuations,
                terminal,
                signalIds.drop(1)
            )
        }
        val graph = SignalObservationGraph(nodes)

        val logicalRoots = logicalStates.mapValues { (_, states) ->
            states.map { state ->
                val section = sections.getValue(state)
                SignalObservationRoot(state, section.entryRequirements)
            }
        }
        val deviceRoots = logicalRoots.toMutableMap()
        definitions.attachedSignals.sortedBy { it.signalId }.forEach { attached ->
            deviceRoots[attached.signalId] = checkNotNull(logicalRoots[attached.primarySignalId]) {
                "attached signal ${attached.signalId} references a logical signal without an observation entry"
            }
        }
        return SignalObservationGraphPublication(topology, definitions, graph, logicalRoots, deviceRoots)
    }

    private fun fixedSection(
        blockId: String,
        direction: FixedBlockEntryDirection,
        traversals: List<FixedBlockTraversal>,
        lengthMeters: Double,
        unavailable: Boolean
    ): DirectedSection {
        val spans = traversals.map {
            TravelSpan(it.railId, it.facing, it.travelStartAbMeters, it.travelEndAbMeters)
        }
        return DirectedSection(
            SignalObservationScanState(
                SectionRef(SectionKind.FIXED_BLOCK, blockId),
                spans.first().facing,
                if (direction == FixedBlockEntryDirection.FORWARD) "$blockId/entry" else "$blockId/exit"
            ),
            spans,
            lengthMeters,
            unavailable = unavailable
        )
    }

    private fun addSection(
        sections: MutableMap<SignalObservationScanState, DirectedSection>,
        section: DirectedSection
    ) {
        require(section.lengthMeters.isFinite() && section.lengthMeters > 0.0) {
            "signal observation section ${section.state.section.id} has invalid length"
        }
        require(abs(section.lengthMeters - section.traversals.sumOf(TravelSpan::lengthMeters)) <= Curve.TOLERANCE) {
            "signal observation section ${section.state.section.id} length disagrees with its route geometry"
        }
        require(sections.putIfAbsent(section.state, section) == null) {
            "signal observation section state ${section.state} is defined more than once"
        }
    }

    private fun logicalStates(
        definitions: SimpleSignalsWorldDefinitions,
        sections: Map<SignalObservationScanState, DirectedSection>,
        routes: ProtectionRouteSelectionIndex
    ): Map<String, List<SignalObservationScanState>> {
        val result = linkedMapOf<String, List<SignalObservationScanState>>()
        definitions.fixedBlockSignals.sortedBy { it.signalId }.forEach { signal ->
            val state = sections.keys.singleOrNull { state ->
                state.section == SectionRef(SectionKind.FIXED_BLOCK, signal.controlledBlockId) &&
                    state.entryBoundaryId == if (signal.entryDirection == FixedBlockEntryDirection.FORWARD)
                        "${signal.controlledBlockId}/entry" else "${signal.controlledBlockId}/exit"
            }
            result[signal.signalId] = listOf(checkNotNull(state) {
                "fixed-block signal ${signal.signalId} has no directed observation entry"
            })
        }
        val routeStates = sections.keys.filter { it.section.kind == SectionKind.PROTECTION_ROUTE }
            .associateBy { checkNotNull(it.protectionRouteId) }
        val routesByEntry = routes.routesById.values.groupBy { it.definition.entryBoundaryId }
        definitions.protectionSignals.sortedBy { it.signalId }.forEach { signal ->
            val routeIds = when (signal.kind) {
                ProtectionSignalKind.FIXED_ROUTE -> listOf(signal.bindingId)
                ProtectionSignalKind.CURRENT_ENTRY -> routesByEntry[signal.bindingId].orEmpty()
                    .map { it.definition.routeId }.sorted()
            }
            result[signal.signalId] = routeIds.mapNotNull(routeStates::get)
        }
        require(result.size == definitions.fixedBlockSignals.size + definitions.protectionSignals.size) {
            "signal observation logical signal identities are not unique"
        }
        return Collections.unmodifiableMap(result.mapValues { java.util.List.copyOf(it.value) })
    }

    private fun addProtectionEntryStops(
        targets: MutableCollection<EntryTarget>,
        definitions: SimpleSignalsWorldDefinitions,
        regions: ProtectionRegionCompilationSnapshot,
        routes: ProtectionRouteSelectionIndex,
        unavailableRegionIds: Set<String>
    ) {
        val usableEntries = routes.routesById.values.mapTo(hashSetOf()) { it.definition.entryBoundaryId }
        definitions.regions.forEach { definition ->
            val unavailable = definition.regionId in unavailableRegionIds ||
                definition.regionId !in regions.regionsById
            definition.boundaries.filter { it.boundaryId !in usableEntries }.forEach { boundary ->
                targets += EntryTarget.Terminal(
                    Cursor(
                        boundary.position.railId,
                        boundary.insideFacing,
                        boundary.position.arcLengthInAbMeters
                    ),
                    if (unavailable) SignalObservationEndReason.TOPOLOGY_UNAVAILABLE
                    else SignalObservationEndReason.ROUTE_UNRESOLVED
                )
            }
        }
    }

    private fun scanFrom(
        start: Cursor,
        entries: EntryIndex,
        topology: TopologyState,
        topologyView: ProtectionRegionTopology
    ): List<SignalObservationContinuation> {
        val results = arrayListOf<SignalObservationContinuation>()
        scan(start, 0.0, emptyList(), linkedMapOf(), entries, topology, topologyView, results)
        return results.distinct().sortedWith(
            compareBy<SignalObservationContinuation>({ it.distanceMeters }, { it.target }, { it.terminalReason })
        )
    }

    private fun scan(
        cursor: Cursor,
        distanceMeters: Double,
        requirements: List<RequiredActiveContinuation>,
        path: MutableMap<Cursor, Double>,
        entries: EntryIndex,
        topology: TopologyState,
        topologyView: ProtectionRegionTopology,
        results: MutableCollection<SignalObservationContinuation>
    ) {
        path[cursor]?.let { cycleStart ->
            val cycleLength = distanceMeters - cycleStart
            require(cycleLength.isFinite() && cycleLength > 0.0) {
                "signal observation contains a zero-length unsectioned directed cycle at " +
                    "${cursor.railId} ${cursor.facing}"
            }
            results += SignalObservationContinuation(
                null,
                distanceMeters,
                SignalObservationBranchRequirements(requirements),
                SignalObservationEndReason.CYCLE,
                cycleStart,
                cycleLength
            )
            return
        }
        path[cursor] = distanceMeters
        try {
            entries.nearest(cursor)?.let { location ->
                val toEntry = cursor.distanceTo(location.positionAbMeters)
                val nextDistance = checkedDistance(distanceMeters, toEntry)
                location.targets.forEach { target ->
                    when (target) {
                        is EntryTarget.Section -> results += SignalObservationContinuation(
                            target.section.state,
                            nextDistance,
                            mergeRequirements(requirements, target.section.entryRequirements)
                        )
                        is EntryTarget.Terminal -> results += SignalObservationContinuation(
                            null,
                            nextDistance,
                            SignalObservationBranchRequirements(requirements),
                            target.reason
                        )
                    }
                }
                return
            }

            val rail = requireNotNull(topologyView.rails[cursor.railId]) {
                "signal observation scan references missing rail ${cursor.railId}"
            }
            val endpointPosition = if (cursor.facing == ViewDirection.AB) rail.lengthMeters else 0.0
            val atEndpoint = checkedDistance(distanceMeters, abs(endpointPosition - cursor.positionAbMeters))
            val entry = RailEndpointRef(
                cursor.railId,
                if (cursor.facing == ViewDirection.AB) RailEndpoint.B else RailEndpoint.A
            )
            val exits = legalExits(topology, entry)
            if (exits.isEmpty()) {
                results += SignalObservationContinuation(
                    null,
                    atEndpoint,
                    SignalObservationBranchRequirements(requirements),
                    SignalObservationEndReason.TRACK_END
                )
                return
            }
            exits.forEach { exit ->
                val nextRail = requireNotNull(topologyView.rails[exit.railId]) {
                    "signal observation continuation references missing rail ${exit.railId}"
                }
                val nextRequirements = if (topology.structuralNodeAt(entry) is TopologyNode.Turnout) {
                    mergeContinuation(requirements, RequiredActiveContinuation(entry, exit))
                } else {
                    requirements
                }
                scan(
                    Cursor(
                        exit.railId,
                        if (exit.endpoint == RailEndpoint.A) ViewDirection.AB else ViewDirection.BA,
                        if (exit.endpoint == RailEndpoint.A) 0.0 else nextRail.lengthMeters
                    ),
                    atEndpoint,
                    nextRequirements,
                    path,
                    entries,
                    topology,
                    topologyView,
                    results
                )
            }
        } finally {
            check(path.remove(cursor) != null) { "signal observation scan path lost its cursor" }
        }
    }

    private fun mergeRequirements(
        path: Collection<RequiredActiveContinuation>,
        target: SignalObservationBranchRequirements
    ): SignalObservationBranchRequirements = SignalObservationBranchRequirements(
        target.requiredContinuations.fold(path.toList(), ::mergeContinuation),
        target.protectionRoute
    )

    private fun mergeContinuation(
        requirements: List<RequiredActiveContinuation>,
        next: RequiredActiveContinuation
    ): List<RequiredActiveContinuation> {
        val retained = requirements.firstOrNull { it.entry == next.entry }
        require(retained == null || retained.selectedExit == next.selectedExit) {
            "signal observation path requires conflicting continuations at ${next.entry}"
        }
        return if (retained == null) requirements + next else requirements
    }

    private fun legalExits(topology: TopologyState, entry: RailEndpointRef): List<RailEndpointRef> {
        topology.ordinaryContinuationAt(entry)?.let { return listOf(it) }
        return when (val node = topology.structuralNodeAt(entry)) {
            is TopologyNode.Turnout -> node.legalContinuations.getValue(entry).sorted()
            is TopologyNode.LoopAnchor -> node.ports.filter { it != entry }.sorted()
            is TopologyNode.Terminus, null -> emptyList()
        }
    }

    private fun checkedDistance(first: Double, second: Double): Double {
        val result = first + second
        require(result.isFinite() && result >= 0.0) { "signal observation distance overflow" }
        return result
    }

    private data class DirectedSection(
        val state: SignalObservationScanState,
        val traversals: List<TravelSpan>,
        val lengthMeters: Double,
        val entryRequirements: SignalObservationBranchRequirements = SignalObservationBranchRequirements(),
        val unavailable: Boolean = false
    ) {
        val entry: Cursor = traversals.first().start
        val exit: Cursor = traversals.last().end
    }

    private data class TravelSpan(
        val railId: UUID,
        val facing: ViewDirection,
        val startAbMeters: Double,
        val endAbMeters: Double
    ) {
        val lengthMeters: Double get() = abs(endAbMeters - startAbMeters)
        val start: Cursor get() = Cursor(railId, facing, startAbMeters)
        val end: Cursor get() = Cursor(railId, facing, endAbMeters)
    }

    private data class Cursor(
        val railId: UUID,
        val facing: ViewDirection,
        val positionAbMeters: Double
    ) {
        fun distanceTo(targetAbMeters: Double): Double = when (facing) {
            ViewDirection.AB -> targetAbMeters - positionAbMeters
            ViewDirection.BA -> positionAbMeters - targetAbMeters
        }.also { require(it >= -Curve.TOLERANCE) { "signal observation entry lies behind the scan cursor" } }
            .coerceAtLeast(0.0)
    }

    private sealed interface EntryTarget {
        val cursor: Cursor

        data class Section(override val cursor: Cursor, val section: DirectedSection) : EntryTarget
        data class Terminal(
            override val cursor: Cursor,
            val reason: SignalObservationEndReason
        ) : EntryTarget
    }

    private class EntryIndex(targets: Collection<EntryTarget>) {
        private data class DirectionKey(val railId: UUID, val facing: ViewDirection)
        data class Location(val positionAbMeters: Double, val targets: List<EntryTarget>)

        private val locations: Map<DirectionKey, List<Location>>

        init {
            locations = targets.groupBy { DirectionKey(it.cursor.railId, it.cursor.facing) }.mapValues { (_, values) ->
                val sorted = values.sortedBy { it.cursor.positionAbMeters }
                val grouped = arrayListOf<MutableList<EntryTarget>>()
                sorted.forEach { target ->
                    val current = grouped.lastOrNull()
                    if (current == null || abs(current.first().cursor.positionAbMeters -
                            target.cursor.positionAbMeters) > Curve.TOLERANCE
                    ) grouped += arrayListOf(target) else current += target
                }
                grouped.map { group ->
                    val distinct = group.distinct()
                    validateLocation(distinct)
                    Location(distinct.first().cursor.positionAbMeters, java.util.List.copyOf(distinct))
                }
            }
        }

        fun nearest(cursor: Cursor): Location? {
            val values = locations[DirectionKey(cursor.railId, cursor.facing)].orEmpty()
            if (values.isEmpty()) return null
            return when (cursor.facing) {
                ViewDirection.AB -> values.firstOrNull {
                    it.positionAbMeters + Curve.TOLERANCE >= cursor.positionAbMeters
                }
                ViewDirection.BA -> values.lastOrNull {
                    it.positionAbMeters - Curve.TOLERANCE <= cursor.positionAbMeters
                }
            }
        }

        private fun validateLocation(targets: List<EntryTarget>) {
            if (targets.size <= 1) return
            require(targets.all { it is EntryTarget.Section } &&
                targets.map { (it as EntryTarget.Section).section.state }.all {
                    it.section.kind == SectionKind.PROTECTION_ROUTE
                } && targets.map { (it as EntryTarget.Section).section.state.entryBoundaryId }.distinct().size == 1
            ) {
                "signal observation has ambiguous section entries at ${targets.first().cursor}"
            }
        }
    }
}

private fun immutableRootMap(values: Map<String, List<SignalObservationRoot>>): Map<String, List<SignalObservationRoot>> =
    Collections.unmodifiableMap(values.mapValues { java.util.List.copyOf(it.value) })
