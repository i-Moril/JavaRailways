package work.art1st.javarailways.simplesignals.runtime

import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.api.route.planning.DirectedRailSpan
import work.art1st.javarailways.basic.api.route.planning.RoutePlanningRestrictions
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.RailTopologyChange
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.simplesignals.authoring.ProtectionBoundarySearch
import work.art1st.javarailways.simplesignals.authoring.continuations
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.simplesignals.block.*
import work.art1st.javarailways.simplesignals.region.*
import work.art1st.javarailways.simplesignals.route.DirectedRouteProjectionResult
import work.art1st.javarailways.simplesignals.route.DirectedRouteProjector
import work.art1st.javarailways.simplesignals.signal.CompiledProtectionSignals
import work.art1st.javarailways.simplesignals.signal.ProtectionRouteSelectionIndex
import work.art1st.javarailways.simplesignals.signalpack.SignalObservationGraphBuilder
import java.util.UUID

internal enum class TopologySectionKind { BLOCK, REGION, SIGNAL }
internal data class TopologySection(val kind: TopologySectionKind, val id: String)

/** Disposable reverse dependencies. Failed objects retain both old and newly discovered rails. */
internal class SectionTopologyIndex(
    publication: SimpleSignalsRuntimePublication,
    previous: SectionTopologyIndex? = null,
    extraDependencies: Map<TopologySection, Set<UUID>> = emptyMap()
) {
    val footprints: Map<TopologySection, List<ProtectionRegionInteriorSlice>>
    val railsByObject: Map<TopologySection, Set<UUID>>
    val objectsByRail: Map<UUID, Set<TopologySection>>
    val signalConsumers: Map<String, List<String>>
    val unavailable: Set<TopologySection>
    val unavailableByRail: Map<UUID, List<ProtectionRegionInteriorSlice>>

    init {
        val regions = checkNotNull(publication.regions.compilationSnapshot())
        val blocks = publication.fixedBlocks.snapshot(publication.topology)
        val spans = linkedMapOf<TopologySection, List<ProtectionRegionInteriorSlice>>()
        val rails = linkedMapOf<TopologySection, MutableSet<UUID>>()
        publication.definitions.fixedBlocks.forEach { definition ->
            val key = TopologySection(TopologySectionKind.BLOCK, definition.blockId)
            spans[key] = definition.traversals.map { ProtectionRegionInteriorSlice(it.railId, it.lowerAbMeters, it.upperAbMeters) }
            rails[key] = definition.traversals.mapTo(linkedSetOf()) { it.railId }
        }
        regions.regionsById.values.forEach { region ->
            val key = TopologySection(TopologySectionKind.REGION, region.definition.regionId)
            spans[key] = region.interior
            rails[key] = region.interior.mapTo(linkedSetOf()) { it.railId }.also { ids ->
                (region.definition.boundaries + region.definition.effectiveBoundaries).forEach { ids += it.position.railId }
            }
        }
        val signalDependencies = publication.definitions.fixedBlockSignals.associate {
            it.signalId to it.downstreamSignals.map { dependency -> dependency.signalId }
        } + publication.definitions.protectionSignals.associate {
            it.signalId to (it.downstreamSignals + it.downstreamSignalsByRouteId.values.flatten()).map { d -> d.signalId }
        }
        signalConsumers = signalDependencies.flatMap { (id, providers) -> providers.map { it to id } }
            .groupBy({ it.first }, { it.second })
        val bindings = publication.fixedBlockSignals.bindings + publication.protectionSignals.bindings
        signalDependencies.keys.forEach { id -> rails[TopologySection(TopologySectionKind.SIGNAL, id)] = linkedSetOf() }
        publication.definitions.fixedBlockSignals.forEach { signal ->
            rails.getValue(TopologySection(TopologySectionKind.SIGNAL, signal.signalId))
                .addAll(rails[TopologySection(TopologySectionKind.BLOCK, signal.controlledBlockId)].orEmpty())
        }
        val authoredRoutes = publication.definitions.routeTables.flatMap { table -> table.routes.map { it.routeId to table.regionId } }.toMap()
        publication.protectionSignals.routeIdsBySignal.forEach { (id, routeIds) -> routeIds.forEach { route ->
            rails.getValue(TopologySection(TopologySectionKind.SIGNAL, id))
                .addAll(rails[TopologySection(TopologySectionKind.REGION, authoredRoutes[route].orEmpty())].orEmpty())
        } }
        bindings.forEach { binding ->
            binding.downstreamSignals.forEach { dependency ->
                rails.getValue(TopologySection(TopologySectionKind.SIGNAL, binding.signalId)).addAll(dependency.affectedRailIds)
            }
        }
        val unavailableObjects = blocks.unavailableBlockIds.mapTo(linkedSetOf()) { TopologySection(TopologySectionKind.BLOCK, it) }
        (regions.unavailableRegionIds + publication.routeTables.unavailableRegionIds()).forEach {
            unavailableObjects += TopologySection(TopologySectionKind.REGION, it)
        }
        val usableSignals = bindings.mapTo(hashSetOf()) { it.signalId }
        signalDependencies.keys.filter { it !in usableSignals }.forEach {
            unavailableObjects += TopologySection(TopologySectionKind.SIGNAL, it)
        }
        unavailableObjects.forEach { key ->
            rails.getOrPut(key, ::linkedSetOf).addAll(previous?.railsByObject?.get(key).orEmpty())
            rails.getValue(key).addAll(extraDependencies[key].orEmpty())
            if (key.kind != TopologySectionKind.SIGNAL) {
                val retained = previous?.footprints?.get(key).orEmpty()
                val extra = extraDependencies[key].orEmpty().mapNotNull { rail ->
                    publication.topology.railProjection[rail]?.singleOrNull()?.let {
                        ProtectionRegionInteriorSlice(rail, 0.0, maxOf(it.railStartAbMeters, it.railEndAbMeters))
                    }
                }
                spans[key] = (spans[key].orEmpty() + retained + extra).distinct()
            }
        }
        footprints = java.util.Map.copyOf(spans.mapValues { java.util.List.copyOf(it.value) })
        railsByObject = java.util.Map.copyOf(rails.mapValues { java.util.Set.copyOf(it.value) })
        objectsByRail = rails.flatMap { (key, ids) -> ids.map { it to key } }
            .groupBy({ it.first }, { it.second }).mapValues { java.util.Set.copyOf(it.value) }
        unavailable = java.util.Set.copyOf(unavailableObjects)
        unavailableByRail = unavailable.filter { it.kind != TopologySectionKind.SIGNAL }
            .flatMap { footprints[it].orEmpty() }.groupBy { it.railId }
    }

    fun affected(railIds: Collection<UUID>): Set<TopologySection> {
        val result = railIds.flatMapTo(linkedSetOf()) { objectsByRail[it].orEmpty() }
        val queue = java.util.ArrayDeque(result.filter { it.kind == TopologySectionKind.SIGNAL }.map { it.id })
        while (queue.isNotEmpty()) signalConsumers[queue.removeFirst()].orEmpty().forEach { signal ->
            if (result.add(TopologySection(TopologySectionKind.SIGNAL, signal))) queue += signal
        }
        return result
    }
}

/** Short pre-publication invalidation; the monitor is never held by graph compilation or host calls. */
internal class SectionTopologyGuard {
    internal class View(val topology: TopologyState, val index: SectionTopologyIndex,
        val pending: Map<TopologySection, Set<UUID>>,
        val blockedByRail: Map<UUID, List<ProtectionRegionInteriorSlice>>)
    private val lock = Any()
    @Volatile private var view: View? = null

    fun capture(): View = checkNotNull(view)
    fun currentTopology(): TopologyState = capture().topology
    fun install(topology: TopologyState, index: SectionTopologyIndex) = synchronized(lock) {
        view = View(topology, index, emptyMap(), index.unavailableByRail)
    }
    fun invalidate(change: RailTopologyChange): Boolean = synchronized(lock) {
        val old = capture()
        val affected = old.index.affected(change.affectedRailIds).toMutableSet()
        old.pending.forEach { (key, rails) -> if (rails.any { it in change.affectedRailIds }) affected += key }
        val pending = if (affected.isEmpty()) old.pending else old.pending.toMutableMap().also { next ->
            affected.forEach { key ->
                val anchors = (old.index.railsByObject[key].orEmpty() + old.pending[key].orEmpty()).intersect(change.affectedRailIds)
                val related = linkedSetOf<UUID>()
                val queue = java.util.ArrayDeque(anchors)
                while (queue.isNotEmpty()) {
                    val rail = queue.removeFirst()
                    if (!related.add(rail)) continue
                    for (root in listOf(change.previous, change.current)) for (endpoint in RailEndpoint.entries) {
                        root.continuations(RailEndpointRef(rail, endpoint)).forEach {
                            if (it.railId in change.affectedRailIds && it.railId !in related) queue += it.railId
                        }
                    }
                }
                next[key] = java.util.Set.copyOf(old.pending[key].orEmpty() + related)
            }
        }.let { java.util.Map.copyOf(it) }
        view = View(change.current, old.index, pending,
            if (affected.isEmpty()) old.blockedByRail else old.blockedByRail.toMutableMap().also { blocked ->
                // Reuse cached invalid territory; touch geometry only for this transaction's indexed hits.
                affected.filter { it.kind != TopologySectionKind.SIGNAL }.forEach { key ->
                    val spans = old.index.footprints[key].orEmpty() + pending[key].orEmpty().mapNotNull { rail ->
                        change.current.railProjection[rail]?.singleOrNull()?.let {
                            ProtectionRegionInteriorSlice(rail, 0.0, maxOf(it.railStartAbMeters, it.railEndAbMeters))
                        }
                    }
                    spans.groupBy { it.railId }.forEach { (rail, additions) ->
                        blocked[rail] = (blocked[rail].orEmpty() + additions).distinct()
                    }
                }
            })
        pending.isNotEmpty()
    }
    fun commit(expected: View, index: SectionTopologyIndex, publish: () -> Unit): Boolean = synchronized(lock) {
        val current = capture()
        if (current.index !== expected.index || current.pending !== expected.pending) return@synchronized false
        publish()
        view = View(current.topology, index, emptyMap(), index.unavailableByRail)
        true
    }
    fun signalUnavailable(id: String): Boolean {
        val captured = capture()
        val key = TopologySection(TopologySectionKind.SIGNAL, id)
        return key in captured.pending || key in captured.index.unavailable
    }
    fun sectionUnavailable(kind: TopologySectionKind, id: String): Boolean {
        val captured = capture()
        val key = TopologySection(kind, id)
        return key in captured.pending || key in captured.index.unavailable
    }
    fun requireUsable(route: RouteSnapshot, authorityEnd: Double) {
        val captured = capture()
        val blocked = captured.blockedByRail
        if (route.segments.none { it.railId in blocked }) return
        val projected = DirectedRouteProjector.project(route, ProtectionRegionTopology.direct(captured.topology))
        check(projected is DirectedRouteProjectionResult.Projected) { "route crosses unavailable or missing signal territory" }
        projected.route.occurrences.forEach { occurrence ->
            if (occurrence.routeStartMeters >= authorityEnd) return@forEach
            val last = occurrence.canonicalAt(minOf(authorityEnd, occurrence.routeEndMeters))
            blocked[occurrence.railId].orEmpty().forEach { span ->
                check(minOf(maxOf(occurrence.canonicalStartAbMeters, last), span.endAbMeters) -
                    maxOf(minOf(occurrence.canonicalStartAbMeters, last), span.startAbMeters) <= Curve.TOLERANCE) {
                    "ATO admission is forbidden by unavailable signal territory on rail ${span.railId}"
                }
            }
        }
    }
    fun restrictions(base: RoutePlanningRestrictions): RoutePlanningRestrictions {
        val captured = capture()
        val blocked = captured.blockedByRail
        if (blocked.isEmpty() && base.topologyRevision == captured.topology.revision) return base
        val forbidden = (base.forbiddenTraversals + blocked.values.flatten().flatMap { span ->
            ViewDirection.entries.map { DirectedRailSpan(span.railId, it, span.startAbMeters, span.endAbMeters) }
        }).mapNotNull { span ->
            val rail = captured.topology.railProjection[span.railId]?.singleOrNull() ?: return@mapNotNull null
            val end = minOf(span.upperAbMeters, maxOf(rail.railStartAbMeters, rail.railEndAbMeters))
            if (end <= span.lowerAbMeters) null else DirectedRailSpan(span.railId, span.facing, span.lowerAbMeters, end)
        }
        val replacements = base.pathReplacements.filter { replacement ->
            replacement.replacedTraversals.none { span -> blocked[span.railId].orEmpty().any {
                minOf(it.endAbMeters, span.upperAbMeters) - maxOf(it.startAbMeters, span.lowerAbMeters) > Curve.TOLERANCE
            } }
        }
        return RoutePlanningRestrictions(captured.topology.revision, forbidden, replacements)
    }
}

/** Recompiles only indexed affected objects. No persisted definition or device is removed. */
internal object SectionTopologyRevalidation {
    fun compile(previous: SimpleSignalsRuntimePublication, view: SectionTopologyGuard.View,
        maxDistanceMeters: Double): SimpleSignalsRuntimePublication {
        val topology = view.topology
        val definitions = previous.definitions
        val affectedRegions = view.pending.keys.filter { it.kind == TopologySectionKind.REGION }.mapTo(hashSetOf()) { it.id }
        val affectedBlocks = view.pending.keys.filter { it.kind == TopologySectionKind.BLOCK }.mapTo(hashSetOf()) { it.id }
        val affectedSignals = view.pending.keys.filter { it.kind == TopologySectionKind.SIGNAL }.mapTo(hashSetOf()) { it.id }
        val extraDependencies = view.pending.toMutableMap()
        fun retainOverlapDependency(first: TopologySection, second: TopologySection) {
            val cause = view.pending[first].orEmpty() + view.pending[second].orEmpty()
            extraDependencies[first] = extraDependencies[first].orEmpty() + cause
            extraDependencies[second] = extraDependencies[second].orEmpty() + cause
        }
        val oldRegions = checkNotNull(previous.regions.compilationSnapshot())
        val regions = oldRegions.regionsById.toMutableMap()
        val unavailableRegions = oldRegions.unavailableRegionIds.toMutableSet()
        definitions.regions.filter { it.regionId in affectedRegions }.forEach { definition ->
            val result = try {
                ProtectionBoundarySearch.search(topology, definition.boundaries.first(), definition.boundaries, maxDistanceMeters)
            } catch (_: IllegalArgumentException) { null }
            val complete = result?.complete == true && result.boundaries.map { it.boundaryId }.toSet() ==
                definition.boundaries.map { it.boundaryId }.toSet() && definition.boundaryDraft == null
            if (complete) unavailableRegions.remove(definition.regionId) else unavailableRegions.add(definition.regionId)
            if (result != null) regions[definition.regionId] = ResolvedProtectionRegion(definition,
                result.interior.sortedWith(compareBy(ProtectionRegionInteriorSlice::railId, ProtectionRegionInteriorSlice::startAbMeters)))
        }
        // Only changed interiors need checking against rail-indexed neighbours.
        affectedRegions.forEach { id ->
            val candidate = regions.getValue(id)
            val others = candidate.interior.flatMapTo(hashSetOf()) { oldRegions.regionIdsByRail[it.railId].orEmpty() } + affectedRegions
            others.filter { it != id }.forEach { other ->
                if (ProtectionRegionGraphResolver.overlap(candidate, regions.getValue(other)) != null) {
                    unavailableRegions += id
                    unavailableRegions += other
                    retainOverlapDependency(TopologySection(TopologySectionKind.REGION, id),
                        TopologySection(TopologySectionKind.REGION, other))
                }
            }
        }
        val oldBlocks = previous.fixedBlocks.snapshot(previous.topology)
        val blocks = oldBlocks.blocksById.toMutableMap()
        val unavailableBlocks = oldBlocks.unavailableBlockIds.toMutableSet()
        val blockCompiler = FixedBlockCatalog()
        val direct = ProtectionRegionTopology.direct(topology)
        definitions.fixedBlocks.filter { it.blockId in affectedBlocks }.forEach { definition ->
            when (val result = blockCompiler.compile(definition, direct)) {
                is FixedBlockCatalog.Compilation.Compiled -> { blocks[definition.blockId] = result.block; unavailableBlocks -= definition.blockId }
                is FixedBlockCatalog.Compilation.Rejected -> unavailableBlocks += definition.blockId
            }
        }
        val regionsByRail = regions.values.flatMap { region -> region.interior.map { it.railId to region } }
            .groupBy({ it.first }, { it.second })
        val separationBlocks = affectedBlocks + affectedRegions.flatMap { id ->
            regions.getValue(id).interior.flatMap { oldBlocks.blockIdsByRail[it.railId].orEmpty() }
        }
        separationBlocks.forEach { id -> blocks.getValue(id).occupancyArea.intervals.forEach { interval ->
            regionsByRail[interval.railId].orEmpty().forEach { region ->
                if (region.interior.any { it.railId == interval.railId &&
                    minOf(it.endAbMeters, interval.maxArcLengthInAbMeters) - maxOf(it.startAbMeters, interval.minArcLengthInAbMeters) > Curve.TOLERANCE }) {
                    unavailableBlocks += id
                    unavailableRegions += region.definition.regionId
                    retainOverlapDependency(TopologySection(TopologySectionKind.BLOCK, id),
                        TopologySection(TopologySectionKind.REGION, region.definition.regionId))
                }
            }
        } }
        val regionCatalog = ProtectionRegionCatalog.fromCompiled(topology, definitions.definitionRevision, regions.values, unavailableRegions)
        val blockCatalog = FixedBlockCatalog.fromCompiled(topology, definitions.definitionRevision, definitions.fixedBlocks, blocks, unavailableBlocks)
        val tables = ProtectionRouteTableCatalog(regionCatalog)
        val tableResult = tables.publish(definitions.definitionRevision, definitions.routeTables, previous.routeTables,
            affectedRegions + unavailableRegions, revalidateRoutes = true)
        check(tableResult.status != ProtectionRouteTableCatalogStatus.REJECTED) { tableResult.diagnostics.joinToString { it.detail } }
        val selection = ProtectionRouteSelectionIndex.compile(topology, tables.usableDefinitions(), previous.routeSelection,
            affectedRegions + unavailableRegions)
        val signals = CompiledProtectionSignals.compile(topology, definitions.definitionRevision, selection,
            blockCatalog.snapshot(topology), definitions.fixedBlockSignals, definitions.protectionSignals,
            definitions.routeIndicators, definitions.routeTables, previous.fixedBlockSignals to previous.protectionSignals,
            affectedSignals, revalidatingTopology = true)
        val observationGraph = SignalObservationGraphBuilder.compile(
            topology,
            definitions,
            blockCatalog.snapshot(topology),
            checkNotNull(regionCatalog.compilationSnapshot()),
            selection,
            tables.unavailableRegionIds()
        )
        return SimpleSignalsRuntimePublication(topology, definitions, regionCatalog, tables, selection, blockCatalog,
            signals.first, signals.second, observationGraph, previous, previous.directionPolicy).also {
            it.topologyIndex = SectionTopologyIndex(it, view.index, extraDependencies)
            it.topologyGuard = previous.topologyGuard
        }
    }
}
