package work.art1st.javarailways.simplesignals.signal

import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.simplesignals.block.FixedBlockCatalog
import work.art1st.javarailways.simplesignals.block.FixedBlockDefinition
import work.art1st.javarailways.simplesignals.block.FixedBlockReadSnapshot
import work.art1st.javarailways.simplesignals.block.FixedBlockTraversal
import work.art1st.javarailways.simplesignals.presentation.validatePlacement
import work.art1st.javarailways.simplesignals.region.ProtectionRouteTableDefinition
import work.art1st.javarailways.simplesignals.runtime.SectionKind
import work.art1st.javarailways.simplesignals.runtime.SectionRef

/** Disposable route geometry and device indexes compiled with one world publication. */
internal class CompiledProtectionSignals private constructor(
    val routeGeometry: FixedBlockReadSnapshot,
    val routes: ProtectionRouteSelectionIndex,
    val definitionsById: Map<String, ProtectionSignalDefinition>,
    val indicatorsById: Map<String, RouteIndicatorDefinition>,
    val routeIdsBySignal: Map<String, List<String>>
) {
    val indicatorsByEntry = indicatorsById.values.mapNotNull { indicator -> indicator.entryBoundaryId?.let { it to indicator } }
        .groupBy({ it.first }, { it.second })
    val signalIdsByRoute = routeIdsBySignal.entries.flatMap { (signal, routes) -> routes.map { it to signal } }
        .groupBy({ it.first }, { it.second })
    val signalIdsByDeclaration = definitionsById.keys.associateBy(::protectionSignalDeclarationId)
    fun routeSection(routeId: String) = SectionRef(SectionKind.PROTECTION_ROUTE, routeId)

    companion object {
        fun compile(
            topology: TopologyState,
            revision: Long,
            routes: ProtectionRouteSelectionIndex,
            blocks: FixedBlockReadSnapshot,
            fixedSignals: Collection<FixedBlockSignalDefinition>,
            signals: Collection<ProtectionSignalDefinition>,
            indicators: Collection<RouteIndicatorDefinition>,
            authoredTables: Collection<ProtectionRouteTableDefinition> = emptyList(),
            previous: Pair<CompiledFixedBlockSignals, CompiledProtectionSignals>? = null,
            changedSignalIds: Set<String> = emptySet(),
            revalidatingTopology: Boolean = false,
            authoredEntryBoundaryIds: Set<String> = emptySet()
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
                val candidates = if (signal.bindingId == null) emptyList() else when (signal.kind) {
                    ProtectionSignalKind.FIXED_ROUTE -> listOfNotNull(routes.routesById[signal.bindingId]).also {
                        require(it.isNotEmpty() || signal.bindingId in authoredRoutes) {
                            "signal ${signal.signalId} binds unknown route ${signal.bindingId}"
                        }
                    }
                    ProtectionSignalKind.CURRENT_ENTRY -> usableByEntry[signal.bindingId].orEmpty().also {
                        require(it.isNotEmpty() || signal.bindingId in authoredByEntry ||
                            signal.bindingId in authoredEntryBoundaryIds) {
                            "signal ${signal.signalId} binds unknown entry ${signal.bindingId}"
                        }
                    }
                }
                if (!revalidatingTopology && candidates.isNotEmpty()) {
                    signal.placement?.validatePlacement(topology, signal.signalId)
                }
                signal.signalId to candidates.map { it.definition.routeId }.sorted()
            }

            val geometryCatalog = FixedBlockCatalog(allowTurnoutGeometry = true)
            // Upstream signals observe every legal branch, including routes with no local device.
            // Keep the monitored section universe identical to the prepared observation graph.
            val geometryDefinitions = routes.routesById.keys.sorted().map { id ->
                val route = routes.routesById.getValue(id)
                FixedBlockDefinition(id, revision, route.definition.traversals.map {
                    FixedBlockTraversal(it.railId, it.facing, it.lowerAbMeters, it.upperAbMeters)
                })
            }
            val geometryBlocks = geometryDefinitions.associate { definition ->
                val retained = previous?.second?.routeGeometry?.blocksById?.get(definition.blockId)?.takeIf {
                    routes.routesById[definition.blockId] === previous.second.routes.routesById[definition.blockId]
                }
                val compiled = retained ?: when (val result = geometryCatalog.compile(definition, blocks.routeTopology)) {
                    is FixedBlockCatalog.Compilation.Compiled -> result.block
                    is FixedBlockCatalog.Compilation.Rejected -> throw IllegalArgumentException(result.diagnostic.detail)
                }
                definition.blockId to compiled
            }
            val geometry = FixedBlockCatalog.fromCompiled(
                topology, revision, geometryDefinitions, geometryBlocks, emptySet()
            ).snapshot(topology)

            val indicatorById = indicators.associateBy { it.indicatorId }
            require(indicatorById.size == indicators.size &&
                indicatorById.keys.none { it in byId || fixedSignals.any { signal -> signal.signalId == it } }) {
                "device identities must be unique"
            }
            indicators.forEach { indicator ->
                if (!revalidatingTopology) indicator.model?.validatePlacement(topology, indicator.indicatorId)
                if (indicator.entryBoundaryId == null) return@forEach
                val entryRoutes = usableByEntry[indicator.entryBoundaryId].orEmpty()
                val authoredEntry = authoredByEntry[indicator.entryBoundaryId].orEmpty()
                require(entryRoutes.isNotEmpty() || authoredEntry.isNotEmpty() ||
                    indicator.entryBoundaryId in authoredEntryBoundaryIds) {
                    "indicator ${indicator.indicatorId} binds unknown entry"
                }
                val entryRouteIds = (if (authoredEntry.isNotEmpty()) authoredEntry.map { it.routeId }
                    else entryRoutes.map { it.definition.routeId }).toSet()
                require(indicator.modelVariantsByRouteId.keys.all { it in entryRouteIds }) {
                    "indicator ${indicator.indicatorId} must only configure its entry routes"
                }
            }

            val compiledFixed = CompiledFixedBlockSignals.compile(fixedSignals, blocks, revalidatingTopology)
            return compiledFixed to CompiledProtectionSignals(
                geometry,
                routes,
                java.util.Map.copyOf(byId),
                java.util.Map.copyOf(indicatorById),
                java.util.Map.copyOf(routeIdsBySignal)
            )
        }
    }
}
