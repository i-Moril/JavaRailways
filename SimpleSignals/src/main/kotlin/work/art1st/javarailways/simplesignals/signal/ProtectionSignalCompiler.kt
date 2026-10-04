package work.art1st.javarailways.simplesignals.signal

import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.simplesignals.block.*
import work.art1st.javarailways.simplesignals.runtime.*
import work.art1st.javarailways.simplesignals.region.ProtectionRouteTableDefinition

/** Disposable route geometry only; these routes are never registered as fixed-block resources. */
internal class CompiledProtectionSignals private constructor(
    val routeGeometry: FixedBlockReadSnapshot,
    val routes: ProtectionRouteSelectionIndex,
    val definitionsById: Map<String, ProtectionSignalDefinition>,
    val indicatorsById: Map<String, RouteIndicatorDefinition>,
    val routeIdsBySignal: Map<String, List<String>>,
    val bindings: List<SectionSignalRuleBinding>,
    val ruleRuntime: SectionSignalRuleRuntime?
) {
    val indicatorsByEntry = indicatorsById.values.groupBy { it.entryBoundaryId }
    val signalIdsByRoute = routeIdsBySignal.entries.flatMap { (signal, routes) -> routes.map { it to signal } }
        .groupBy({ it.first }, { it.second })
    val signalIdsByDeclaration = definitionsById.keys.associateBy(::protectionSignalDeclarationId)
    fun routeSection(routeId: String) = SectionRef(SectionKind.PROTECTION_ROUTE, routeId)

    companion object {
        fun compile(topology: TopologyState, revision: Long, routes: ProtectionRouteSelectionIndex,
            blocks: FixedBlockReadSnapshot, fixedSignals: Collection<FixedBlockSignalDefinition>,
            signals: Collection<ProtectionSignalDefinition>, indicators: Collection<RouteIndicatorDefinition>,
            authoredTables: Collection<ProtectionRouteTableDefinition> = emptyList()
        ): Pair<CompiledFixedBlockSignals, CompiledProtectionSignals> {
            val byId = signals.associateBy { it.signalId }
            require(byId.size == signals.size) { "protection signal IDs must be unique" }
            require(byId.keys.none { id -> fixedSignals.any { it.signalId == id } }) {
                "signal identities must be unique across device kinds"
            }
            val authoredRoutes = authoredTables.flatMap { it.routes }.associateBy { it.routeId }
            val authoredByEntry = authoredRoutes.values.groupBy { it.entryBoundaryId }
            val usableByEntry = routes.routesById.values.groupBy { it.definition.entryBoundaryId }
            val routeIdsBySignal = signals.associate { signal ->
                signal.model?.validatePlacement(topology, signal.signalId)
                val candidates = when (signal.kind) {
                    ProtectionSignalKind.FIXED_ROUTE -> listOfNotNull(routes.routesById[signal.bindingId]).also {
                        require(it.isNotEmpty() || signal.bindingId in authoredRoutes) {
                            "signal ${signal.signalId} binds unknown route ${signal.bindingId}"
                        }
                    }
                    ProtectionSignalKind.CURRENT_ENTRY -> usableByEntry[signal.bindingId].orEmpty()
                    .also { require(it.isNotEmpty() || signal.bindingId in authoredByEntry) {
                        "signal ${signal.signalId} binds unknown entry ${signal.bindingId}" } }
                }
                require(signal.downstreamSignalsByRouteId.keys.all { id -> candidates.any { it.definition.routeId == id } ||
                    authoredRoutes[id]?.let {
                        (signal.kind == ProtectionSignalKind.FIXED_ROUTE && signal.bindingId == id ||
                            signal.kind == ProtectionSignalKind.CURRENT_ENTRY && signal.bindingId == it.entryBoundaryId) } == true }) {
                    "signal ${signal.signalId} has dependencies for an unrelated route"
                }
                signal.signalId to candidates.map { it.definition.routeId }.sorted()
            }
            val geometryCatalog = FixedBlockCatalog(allowTurnoutGeometry = true)
            val indicatorEntries = indicators.mapTo(hashSetOf()) { it.entryBoundaryId }
            val observedRouteIds = routeIdsBySignal.values.flatten().toSortedSet().also { ids ->
                routes.routesById.values.filter { it.definition.entryBoundaryId in indicatorEntries }
                    .forEach { ids += it.definition.routeId }
            }
            val geometryResult = geometryCatalog.publish(topology, revision, observedRouteIds.map { id ->
                val route = routes.routesById.getValue(id)
                FixedBlockDefinition(id, revision, route.definition.traversals.map {
                    FixedBlockTraversal(it.railId, it.facing, it.lowerAbMeters, it.upperAbMeters)
                })
            }, blocks.routeTopology.takeIf { it.rails.isNotEmpty() || observedRouteIds.isEmpty() })
            require(geometryResult.status != FixedBlockCatalogStatus.REJECTED) {
                geometryResult.diagnostics.joinToString { it.detail }
            }
            val geometry = geometryCatalog.snapshot(topology)
            val signalGeometry = routeIdsBySignal.filterValues { it.isNotEmpty() }
                .mapValues { (_, ids) -> geometry.blocksById.getValue(ids.first()) }
            // A retained invalid binding remains a known zero-speed source, including its upstream consumers.
            // Never erase a dependency and reinterpret its consumer as a permissive terminal signal.
            val dependencies = fixedSignals.associate { it.signalId to it.downstreamSignals.map { dependency -> dependency.signalId } } +
                signals.associate { it.signalId to (it.downstreamSignals + it.downstreamSignalsByRouteId.values.flatten()).map { dependency -> dependency.signalId } }
            OneWayInvalidationGraph.compile(dependencies.keys, dependencies.flatMap { (consumer, providers) ->
                providers.distinct().filter { it in dependencies }.map { InvalidationDependency(it, consumer) }
            })
            val unavailable = routeIdsBySignal.filterValues { it.isEmpty() }.keys.toMutableSet()
            val consumers = dependencies.flatMap { (id, providers) -> providers.map { it to id } }.groupBy({ it.first }, { it.second })
            val pending = java.util.ArrayDeque<String>()
            pending.addAll(unavailable)
            dependencies.forEach { (id, providers) -> if (providers.any { it !in dependencies } && unavailable.add(id)) pending += id }
            while (pending.isNotEmpty()) consumers[pending.removeFirst()].orEmpty().forEach { id ->
                if (unavailable.add(id)) pending += id
            }
            val compiledFixed = CompiledFixedBlockSignals.compile(fixedSignals, blocks, signalGeometry, false, unavailable)
            val allGeometry = signalGeometry + fixedSignals.associate {
                it.signalId to blocks.blocksById.getValue(it.controlledBlockId)
            }
            val bindings = signals.filter { it.signalId !in unavailable }.map { signal ->
                val candidateIds = routeIdsBySignal.getValue(signal.signalId)
                val prepared = candidateIds.associate { id ->
                    SectionRef(SectionKind.PROTECTION_ROUTE, id) to routes.routesById.getValue(id)
                }
                val dependencies = candidateIds.flatMap { routeId ->
                    val authored = signal.downstreamSignalsByRouteId[routeId] ?: signal.downstreamSignals
                    authored.map { dependency ->
                        val target = requireNotNull(allGeometry[dependency.signalId]) {
                            "signal ${signal.signalId} depends on unknown signal ${dependency.signalId}"
                        }
                        val path = CompiledFixedBlockSignals.compileDownstreamPath(signal.signalId, dependency,
                            geometry.blocksById.getValue(routeId), target,
                            if (blocks.blocksById.isEmpty()) geometry else blocks)
                        DownstreamSignalDependency(dependency.signalId, path.distanceMeters,
                            path.requiredContinuations, routeId, path.affectedRailIds)
                    }
                }
                SectionSignalRuleBinding(signal.signalId, prepared.keys.first(), dependencies,
                    java.util.List.copyOf(prepared.keys),
                    routes.routesById.getValue(candidateIds.first()).definition.entryBoundaryId,
                    java.util.Map.copyOf(prepared), protectionSignalDeclarationId(signal.signalId),
                    prepared.values.flatMap { it.requiredContinuations.map(RequiredActiveContinuation::entry) }.distinct(),
                    SectionSignalOutput(signal.unavailableOutput.speedLimitMetersPerSecond,
                        signal.unavailableOutput.modelVariant)
                ) { input ->
                    checkNotNull(input.preparedRoute) { "protection rule has no prepared route" }
                    val selected = if (input.controlledSection.occupants.isNotEmpty()) signal.occupiedOutput else {
                        val limit = input.downstreamSignals.minOfOrNull { it.effectiveSpeedLimitMetersPerSecond }
                            ?: Double.POSITIVE_INFINITY
                        signal.clearLevels.last { it.minimumDownstreamSpeedMetersPerSecond <= limit }.output
                    }
                    SectionSignalOutput(selected.speedLimitMetersPerSecond, selected.modelVariant)
                }
            }
            val indicatorById = indicators.associateBy { it.indicatorId }
            require(indicatorById.size == indicators.size && indicatorById.keys.none { it in dependencies }) {
                "device identities must be unique"
            }
            indicators.forEach { indicator ->
                indicator.model?.validatePlacement(topology, indicator.indicatorId)
                val entryRoutes = usableByEntry[indicator.entryBoundaryId].orEmpty()
                val authoredEntry = authoredByEntry[indicator.entryBoundaryId].orEmpty()
                require(entryRoutes.isNotEmpty() || authoredEntry.isNotEmpty()) { "indicator ${indicator.indicatorId} binds unknown entry" }
                require(indicator.modelVariantsByRouteId.keys == (if (authoredEntry.isNotEmpty()) authoredEntry.map { it.routeId }
                    else entryRoutes.map { it.definition.routeId }).toSet()) {
                    "indicator ${indicator.indicatorId} must configure exactly its entry routes"
                }
            }
            val allBindings = compiledFixed.bindings + bindings
            val sections = blocks.blocksById.keys.map { SectionRef(SectionKind.FIXED_BLOCK, it) } +
                routes.routesById.keys.map { SectionRef(SectionKind.PROTECTION_ROUTE, it) }
            val unavailableDeclarations = unavailable.associateWith { id ->
                fixedSignals.firstOrNull { it.signalId == id }?.let { fixedBlockSignalDeclarationId(it.controlledBlockId) }
                    ?: protectionSignalDeclarationId(id)
            }
            val runtime = if (allBindings.isEmpty() && unavailable.isEmpty()) null else
                SectionSignalRuleRuntime.compile(sections, allBindings, unavailableDeclarations,
                    fixedSignals.filter { it.signalId in unavailable }.associate { SectionRef(SectionKind.FIXED_BLOCK, it.controlledBlockId) to it.signalId })
            return compiledFixed to CompiledProtectionSignals(geometry, routes, java.util.Map.copyOf(byId),
                java.util.Map.copyOf(indicatorById), java.util.Map.copyOf(routeIdsBySignal),
                java.util.List.copyOf(bindings), runtime)
        }
    }
}
