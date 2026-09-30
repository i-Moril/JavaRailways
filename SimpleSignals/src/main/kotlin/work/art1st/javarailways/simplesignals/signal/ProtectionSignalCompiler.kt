package work.art1st.javarailways.simplesignals.signal

import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.simplesignals.block.*
import work.art1st.javarailways.simplesignals.runtime.*

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
            signals: Collection<ProtectionSignalDefinition>, indicators: Collection<RouteIndicatorDefinition>
        ): Pair<CompiledFixedBlockSignals, CompiledProtectionSignals> {
            val byId = signals.associateBy { it.signalId }
            require(byId.size == signals.size) { "protection signal IDs must be unique" }
            require(byId.keys.none { id -> fixedSignals.any { it.signalId == id } }) {
                "signal identities must be unique across device kinds"
            }
            val routeIdsBySignal = signals.associate { signal ->
                val candidates = when (signal.kind) {
                    ProtectionSignalKind.FIXED_ROUTE -> listOf(requireNotNull(routes.routesById[signal.bindingId]) {
                        "signal ${signal.signalId} binds unknown route ${signal.bindingId}"
                    })
                    ProtectionSignalKind.CURRENT_ENTRY -> routes.routesById.values.filter {
                        it.definition.entryBoundaryId == signal.bindingId
                    }.also { require(it.isNotEmpty()) { "signal ${signal.signalId} binds unknown entry ${signal.bindingId}" } }
                }
                require(signal.downstreamSignalsByRouteId.keys.all { id -> candidates.any { it.definition.routeId == id } }) {
                    "signal ${signal.signalId} has dependencies for an unrelated route"
                }
                signal.signalId to candidates.map { it.definition.routeId }.sorted()
            }
            val geometryCatalog = FixedBlockCatalog()
            val observedRouteIds = routeIdsBySignal.values.flatten().toSortedSet()
            val geometryResult = geometryCatalog.publish(topology, revision, observedRouteIds.map { id ->
                val route = routes.routesById.getValue(id)
                FixedBlockDefinition(id, revision, route.definition.traversals.map {
                    FixedBlockTraversal(it.railId, it.facing, it.lowerAbMeters, it.upperAbMeters)
                })
            })
            require(geometryResult.status != FixedBlockCatalogStatus.REJECTED) {
                geometryResult.diagnostics.joinToString { it.detail }
            }
            val geometry = geometryCatalog.snapshot(topology)
            val signalGeometry = routeIdsBySignal.mapValues { (_, ids) -> geometry.blocksById.getValue(ids.first()) }
            val compiledFixed = CompiledFixedBlockSignals.compile(fixedSignals, blocks, signalGeometry, false)
            val allGeometry = signalGeometry + fixedSignals.associate {
                it.signalId to blocks.blocksById.getValue(it.controlledBlockId)
            }
            val bindings = signals.map { signal ->
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
                            path.requiredContinuations, routeId)
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
                        val limit = input.downstreamSignals.minOfOrNull { it.output.speedLimitMetersPerSecond }
                            ?: Double.POSITIVE_INFINITY
                        signal.clearLevels.last { it.minimumDownstreamSpeedMetersPerSecond <= limit }.output
                    }
                    SectionSignalOutput(selected.speedLimitMetersPerSecond, selected.modelVariant)
                }
            }
            val indicatorById = indicators.associateBy { it.indicatorId }
            require(indicatorById.size == indicators.size && indicatorById.keys.none { it in allGeometry }) {
                "device identities must be unique"
            }
            indicators.forEach { indicator ->
                val entryRoutes = routes.routesById.values.filter { it.definition.entryBoundaryId == indicator.entryBoundaryId }
                require(entryRoutes.isNotEmpty()) { "indicator ${indicator.indicatorId} binds unknown entry" }
                require(indicator.modelVariantsByRouteId.keys == entryRoutes.map { it.definition.routeId }.toSet()) {
                    "indicator ${indicator.indicatorId} must configure exactly its entry routes"
                }
                val rail = requireNotNull(topology.railProjection[indicator.position.railId]) {
                    "indicator ${indicator.indicatorId} references missing rail"
                }
                val length = rail.maxOf { maxOf(it.railStartAbMeters, it.railEndAbMeters) }
                require(indicator.position.arcLengthInAbMeters.isFinite() &&
                    indicator.position.arcLengthInAbMeters in 0.0..length) { "indicator placement is outside rail" }
            }
            val allBindings = compiledFixed.bindings + bindings
            val sections = blocks.blocksById.keys.map { SectionRef(SectionKind.FIXED_BLOCK, it) } +
                routes.routesById.keys.map { SectionRef(SectionKind.PROTECTION_ROUTE, it) }
            val runtime = allBindings.takeIf { it.isNotEmpty() }?.let { SectionSignalRuleRuntime.compile(sections, it) }
            return compiledFixed to CompiledProtectionSignals(geometry, routes, java.util.Map.copyOf(byId),
                java.util.Map.copyOf(indicatorById), java.util.Map.copyOf(routeIdsBySignal),
                java.util.List.copyOf(bindings), runtime)
        }
    }
}
