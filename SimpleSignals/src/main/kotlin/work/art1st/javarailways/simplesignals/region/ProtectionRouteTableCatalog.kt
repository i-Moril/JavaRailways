package work.art1st.javarailways.simplesignals.region

import work.art1st.javarailways.basic.api.route.planning.DirectedRailSpan
import work.art1st.javarailways.basic.api.route.planning.PathReplacement
import work.art1st.javarailways.basic.api.route.planning.PhysicalTraversalPath
import work.art1st.javarailways.basic.api.route.planning.RoutePlanningRestrictionSource
import work.art1st.javarailways.basic.api.route.planning.RoutePlanningRestrictions
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import java.util.UUID

internal enum class ProtectionRouteTableCatalogStatus {
    PUBLISHED,
    UNCHANGED,
    REJECTED
}

internal enum class ProtectionRouteTableValidationCode {
    UNPUBLISHED_REGION_CATALOG,
    INVALID_DEFINITION_REVISION,
    INVALID_TABLE_REVISION,
    DUPLICATE_TABLE_ID,
    DUPLICATE_REGION_TABLE,
    UNKNOWN_REGION,
    STALE_REGION_REVISION,
    DUPLICATE_ROUTE_ID,
    UNKNOWN_BOUNDARY,
    SAME_ENTRY_AND_EXIT,
    ENTRY_MISMATCH,
    EXIT_MISMATCH,
    TRAVERSAL_OUTSIDE_REGION,
    DISCONTINUOUS_PATH
}

internal data class ProtectionRouteTableDiagnostic(
    val code: ProtectionRouteTableValidationCode,
    val detail: String,
    val tableId: String? = null,
    val routeId: String? = null,
    val regionId: String? = null
) {
    init {
        require(detail.isNotBlank()) { "diagnostic detail must not be blank" }
    }
}

internal data class ProtectionRouteTableCatalogResult(
    val status: ProtectionRouteTableCatalogStatus,
    val diagnostics: List<ProtectionRouteTableDiagnostic> = emptyList()
) {
    init {
        require(status == ProtectionRouteTableCatalogStatus.REJECTED || diagnostics.isEmpty()) {
            "diagnostics are only valid for rejected publications"
        }
    }
}

/**
 * Compiles plugin-private fixed route tables into one immutable Basic-neutral restriction snapshot.
 *
 * Compilation is performed only when definitions are published. The source callback performs
 * constant-time publication/topology checks and returns the already compiled value; it never
 * walks a region or invokes route-table policy from inside graph search.
 */
internal class ProtectionRouteTableCatalog(
    private val regionCatalog: ProtectionRegionCatalog
) {
    private val lock = Any()

    @Volatile
    private var snapshot: Snapshot? = null

    /** Revalidated current geometry. Authored tables, including invalid old routes, remain in the world document. */
    fun usableDefinitions(): List<ProtectionRouteTableDefinition> = snapshot?.usableDefinitions.orEmpty()
    fun unavailableRegionIds(): Set<String> = snapshot?.unavailableRegionIds.orEmpty()

    val restrictionSource = RoutePlanningRestrictionSource { topology ->
        val captured = snapshot
            ?: error("fixed protection route tables have not been published")
        check(regionCatalog.compilationSnapshot() === captured.regionSnapshot) {
            "protection-region publication changed after fixed route tables were compiled"
        }
        check(topology.revision == captured.regionSnapshot.topology.revision) {
            "fixed route tables target topology revision " +
                "${captured.regionSnapshot.topology.revision}, not ${topology.revision}"
        }
        check(topology === captured.regionSnapshot.topology) {
            "fixed route tables target a different topology root with revision ${topology.revision}"
        }
        captured.restrictions
    }

    fun publish(
        definitionRevision: Long,
        definitions: Collection<ProtectionRouteTableDefinition>
    ): ProtectionRouteTableCatalogResult = synchronized(lock) {
        require(definitionRevision >= 0L) { "definitionRevision must be non-negative" }
        val copiedDefinitions = java.util.List.copyOf(
            definitions.sortedBy(ProtectionRouteTableDefinition::tableId)
        )
        val current = snapshot
        if (current != null && definitionRevision < current.definitionRevision) {
            return@synchronized rejected(
                ProtectionRouteTableValidationCode.INVALID_DEFINITION_REVISION,
                "definition revision $definitionRevision precedes published revision " +
                    current.definitionRevision
            )
        }
        if (current != null &&
            definitionRevision == current.definitionRevision &&
            copiedDefinitions != current.definitions
        ) {
            return@synchronized rejected(
                ProtectionRouteTableValidationCode.INVALID_DEFINITION_REVISION,
                "route-table content changed without advancing revision $definitionRevision"
            )
        }
        if (current != null) {
            val previousById = current.definitions.associateBy(ProtectionRouteTableDefinition::tableId)
            for (definition in copiedDefinitions) {
                val previous = previousById[definition.tableId] ?: continue
                if (definition.revision < previous.revision) {
                    return@synchronized rejected(
                        ProtectionRouteTableValidationCode.INVALID_TABLE_REVISION,
                        "route table ${definition.tableId} revision ${definition.revision} precedes " +
                            "published revision ${previous.revision}",
                        tableId = definition.tableId,
                        regionId = definition.regionId
                    )
                }
                if (definition.revision == previous.revision && definition != previous) {
                    return@synchronized rejected(
                        ProtectionRouteTableValidationCode.INVALID_TABLE_REVISION,
                        "route table ${definition.tableId} changed without advancing revision " +
                            definition.revision,
                        tableId = definition.tableId,
                        regionId = definition.regionId
                    )
                }
            }
        }

        duplicate(copiedDefinitions, ProtectionRouteTableDefinition::tableId)?.let { tableId ->
            return@synchronized rejected(
                ProtectionRouteTableValidationCode.DUPLICATE_TABLE_ID,
                "route table $tableId is defined more than once",
                tableId = tableId
            )
        }
        duplicate(copiedDefinitions, ProtectionRouteTableDefinition::regionId)?.let { regionId ->
            return@synchronized rejected(
                ProtectionRouteTableValidationCode.DUPLICATE_REGION_TABLE,
                "protection region $regionId has more than one fixed route table",
                regionId = regionId
            )
        }

        val regionSnapshot = regionCatalog.compilationSnapshot()
            ?: return@synchronized rejected(
                ProtectionRouteTableValidationCode.UNPUBLISHED_REGION_CATALOG,
                "protection regions must be published before fixed route tables"
            )
        val replacements = ArrayList<PathReplacement>(copiedDefinitions.size)
        val usable = arrayListOf<ProtectionRouteTableDefinition>()
        val unavailable = regionSnapshot.regionsById.values.filter { it.definition.boundaryDraft != null }
            .mapTo(linkedSetOf()) { it.definition.regionId }
        val routeIds = copiedDefinitions.flatMap { it.routes }.map { it.routeId }
        if (routeIds.distinct().size != routeIds.size) return@synchronized rejected(
            ProtectionRouteTableValidationCode.DUPLICATE_ROUTE_ID, "protection route IDs must be unique")
        for (definition in copiedDefinitions) {
            if (definition.defaultRouteIds.any { id -> definition.routes.none { it.routeId == id } })
                return@synchronized rejected(ProtectionRouteTableValidationCode.UNKNOWN_BOUNDARY,
                    "table ${definition.tableId} references an unknown default route")
            when (val compiled = compile(definition, regionSnapshot)) {
                is Compilation.Compiled -> {
                    compiled.replacement?.let(replacements::add)
                    compiled.usable?.let(usable::add) ?: unavailable.add(definition.regionId)
                }
                is Compilation.Rejected -> return@synchronized ProtectionRouteTableCatalogResult(
                    ProtectionRouteTableCatalogStatus.REJECTED,
                    listOf(compiled.diagnostic)
                )
            }
        }

        val candidate = Snapshot(
            regionSnapshot = regionSnapshot,
            definitionRevision = definitionRevision,
            definitions = copiedDefinitions,
            usableDefinitions = java.util.List.copyOf(usable),
            unavailableRegionIds = java.util.Set.copyOf(unavailable),
            restrictions = RoutePlanningRestrictions(
                topologyRevision = regionSnapshot.topology.revision,
                forbiddenTraversals = unavailable.flatMap { id -> regionSnapshot.regionsById.getValue(id).interior.flatMap { slice ->
                    ViewDirection.entries.map { DirectedRailSpan(slice.railId, it, slice.startAbMeters, slice.endAbMeters) }
                } },
                pathReplacements = replacements
            )
        )
        if (current?.equivalentTo(candidate) == true) {
            return@synchronized ProtectionRouteTableCatalogResult(
                ProtectionRouteTableCatalogStatus.UNCHANGED
            )
        }
        snapshot = candidate
        ProtectionRouteTableCatalogResult(ProtectionRouteTableCatalogStatus.PUBLISHED)
    }

    private fun compile(
        table: ProtectionRouteTableDefinition,
        snapshot: ProtectionRegionCompilationSnapshot
    ): Compilation {
        val region = snapshot.regionsById[table.regionId]
            ?: return rejectedCompilation(
                ProtectionRouteTableValidationCode.UNKNOWN_REGION,
                "route table ${table.tableId} references unknown region ${table.regionId}",
                table
            )
        if (table.regionRevision > region.definition.revision) {
            return rejectedCompilation(
                ProtectionRouteTableValidationCode.STALE_REGION_REVISION,
                "route table ${table.tableId} targets region revision ${table.regionRevision}; " +
                    "published revision is ${region.definition.revision}",
                table
            )
        }
        if (region.definition.boundaryDraft != null) return Compilation.Compiled(null, null)
        duplicate(table.routes, ProtectionRouteDefinition::routeId)?.let { routeId ->
            return rejectedCompilation(
                ProtectionRouteTableValidationCode.DUPLICATE_ROUTE_ID,
                "route $routeId is defined more than once in table ${table.tableId}",
                table,
                routeId
            )
        }

        val paths = ArrayList<PhysicalTraversalPath>(table.routes.size)
        val validRoutes = arrayListOf<ProtectionRouteDefinition>()
        val boundaries = region.definition.boundaries.associateBy(ProtectionBoundaryDefinition::boundaryId)
        val interiorByRail = region.interior.groupBy(ProtectionRegionInteriorSlice::railId)
        for (route in table.routes) {
            when (
                val compiled = compile(
                    route,
                    table,
                    region,
                    boundaries,
                    interiorByRail,
                    snapshot.derivedTopology
                )
            ) {
                is RouteCompilation.Compiled -> { paths += compiled.path; validRoutes += route }
                is RouteCompilation.Rejected -> if (table.regionRevision == region.definition.revision)
                    return Compilation.Rejected(compiled.diagnostic)
            }
        }
        val replaced = region.interior.flatMap { slice ->
            ViewDirection.entries.map { facing ->
                DirectedRailSpan(slice.railId, facing, slice.startAbMeters, slice.endAbMeters)
            }
        }
        val validIds = validRoutes.mapTo(hashSetOf()) { it.routeId }
        return if (paths.isEmpty()) Compilation.Compiled(null, null) else Compilation.Compiled(
            PathReplacement(replaced, paths), ProtectionRouteTableDefinition(table.tableId, table.revision,
                table.regionId, region.definition.revision, validRoutes, table.defaultRouteIds.filter { it in validIds }))
    }

    private fun compile(
        route: ProtectionRouteDefinition,
        table: ProtectionRouteTableDefinition,
        region: ResolvedProtectionRegion,
        boundaries: Map<String, ProtectionBoundaryDefinition>,
        interiorByRail: Map<UUID, List<ProtectionRegionInteriorSlice>>,
        topology: ProtectionRegionTopology
    ): RouteCompilation {
        val entry = boundaries[route.entryBoundaryId]
            ?: return rejectedRoute(
                ProtectionRouteTableValidationCode.UNKNOWN_BOUNDARY,
                "route ${route.routeId} references unknown entry boundary ${route.entryBoundaryId}",
                table,
                route
            )
        val exit = boundaries[route.exitBoundaryId]
            ?: return rejectedRoute(
                ProtectionRouteTableValidationCode.UNKNOWN_BOUNDARY,
                "route ${route.routeId} references unknown exit boundary ${route.exitBoundaryId}",
                table,
                route
            )
        if (entry.boundaryId == exit.boundaryId) {
            return rejectedRoute(
                ProtectionRouteTableValidationCode.SAME_ENTRY_AND_EXIT,
                "route ${route.routeId} must leave through a different boundary",
                table,
                route
            )
        }

        val first = route.traversals.first()
        if (first.railId != entry.position.railId ||
            first.facing != entry.insideFacing ||
            !sameReplacementPosition(first.travelStartAbMeters, entry.position.arcLengthInAbMeters)
        ) {
            return rejectedRoute(
                ProtectionRouteTableValidationCode.ENTRY_MISMATCH,
                "route ${route.routeId} does not start inward at boundary ${entry.boundaryId}",
                table,
                route
            )
        }
        val last = route.traversals.last()
        if (last.railId != exit.position.railId ||
            last.facing != exit.insideFacing.opposite() ||
            !sameReplacementPosition(last.travelEndAbMeters, exit.position.arcLengthInAbMeters)
        ) {
            return rejectedRoute(
                ProtectionRouteTableValidationCode.EXIT_MISMATCH,
                "route ${route.routeId} does not leave outward at boundary ${exit.boundaryId}",
                table,
                route
            )
        }

        for (traversal in route.traversals) {
            val inside = contains(interiorByRail[traversal.railId].orEmpty(), traversal)
            if (!inside) {
                return rejectedRoute(
                    ProtectionRouteTableValidationCode.TRAVERSAL_OUTSIDE_REGION,
                    "route ${route.routeId} leaves region ${region.definition.regionId} on rail " +
                        traversal.railId,
                    table,
                    route
                )
            }
        }
        route.traversals.zipWithNext().forEach { (current, next) ->
            if (!continuous(current, next, topology)) {
                return rejectedRoute(
                    ProtectionRouteTableValidationCode.DISCONTINUOUS_PATH,
                    "route ${route.routeId} is not physically continuous between rails " +
                        "${current.railId} and ${next.railId}",
                    table,
                    route
                )
            }
        }

        return RouteCompilation.Compiled(
            PhysicalTraversalPath(
                route.traversals.map { traversal ->
                    DirectedRailSpan(
                        traversal.railId,
                        traversal.facing,
                        traversal.lowerAbMeters,
                        traversal.upperAbMeters
                    )
                }
            )
        )
    }

    private fun contains(
        slices: List<ProtectionRegionInteriorSlice>,
        traversal: ProtectionRouteTraversal
    ): Boolean {
        var low = 0
        var high = slices.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (slices[middle].startAbMeters <=
                traversal.lowerAbMeters + REPLACEMENT_POSITION_EPSILON
            ) {
                low = middle + 1
            } else {
                high = middle
            }
        }
        if (low == 0) return false
        val candidate = slices[low - 1]
        return traversal.lowerAbMeters >=
            candidate.startAbMeters - REPLACEMENT_POSITION_EPSILON &&
            traversal.upperAbMeters <=
            candidate.endAbMeters + REPLACEMENT_POSITION_EPSILON
    }

    private fun continuous(
        current: ProtectionRouteTraversal,
        next: ProtectionRouteTraversal,
        topology: ProtectionRegionTopology
    ): Boolean {
        if (current.railId == next.railId) {
            return current.facing == next.facing &&
                sameTopologyPosition(current.travelEndAbMeters, next.travelStartAbMeters)
        }
        val currentRail = topology.rails[current.railId] ?: return false
        val nextRail = topology.rails[next.railId] ?: return false
        val exitEndpoint = endpointAt(current.travelEndAbMeters, currentRail.lengthMeters)
            ?: return false
        if (endpointAt(next.travelStartAbMeters, nextRail.lengthMeters) == null) return false
        val continuations = when (exitEndpoint) {
            RailEndpoint.A -> currentRail.connectionsFromA
            RailEndpoint.B -> currentRail.connectionsFromB
        }
        return continuations.any { connection ->
            connection.railId == next.railId && connection.enteringFacing == next.facing
        }
    }

    private fun endpointAt(position: Double, length: Double): RailEndpoint? = when {
        sameTopologyPosition(position, 0.0) -> RailEndpoint.A
        sameTopologyPosition(position, length) -> RailEndpoint.B
        else -> null
    }

    private fun ViewDirection.opposite(): ViewDirection = when (this) {
        ViewDirection.AB -> ViewDirection.BA
        ViewDirection.BA -> ViewDirection.AB
    }

    private fun rejected(
        code: ProtectionRouteTableValidationCode,
        detail: String,
        tableId: String? = null,
        regionId: String? = null
    ) = ProtectionRouteTableCatalogResult(
        ProtectionRouteTableCatalogStatus.REJECTED,
        listOf(ProtectionRouteTableDiagnostic(code, detail, tableId = tableId, regionId = regionId))
    )

    private fun rejectedCompilation(
        code: ProtectionRouteTableValidationCode,
        detail: String,
        table: ProtectionRouteTableDefinition,
        routeId: String? = null
    ) = Compilation.Rejected(
        ProtectionRouteTableDiagnostic(
            code,
            detail,
            tableId = table.tableId,
            routeId = routeId,
            regionId = table.regionId
        )
    )

    private fun rejectedRoute(
        code: ProtectionRouteTableValidationCode,
        detail: String,
        table: ProtectionRouteTableDefinition,
        route: ProtectionRouteDefinition
    ) = RouteCompilation.Rejected(
        ProtectionRouteTableDiagnostic(
            code,
            detail,
            tableId = table.tableId,
            routeId = route.routeId,
            regionId = table.regionId
        )
    )

    private fun <T> duplicate(values: Collection<T>, identity: (T) -> String): String? =
        values.groupingBy(identity).eachCount().entries.firstOrNull { it.value > 1 }?.key

    private fun sameTopologyPosition(first: Double, second: Double): Boolean =
        kotlin.math.abs(first - second) <= Curve.TOLERANCE

    private fun sameReplacementPosition(first: Double, second: Double): Boolean =
        kotlin.math.abs(first - second) <= REPLACEMENT_POSITION_EPSILON

    private data class Snapshot(
        val regionSnapshot: ProtectionRegionCompilationSnapshot,
        val definitionRevision: Long,
        val definitions: List<ProtectionRouteTableDefinition>,
        val usableDefinitions: List<ProtectionRouteTableDefinition>,
        val unavailableRegionIds: Set<String>,
        val restrictions: RoutePlanningRestrictions
    ) {
        fun equivalentTo(other: Snapshot): Boolean =
            regionSnapshot === other.regionSnapshot &&
                definitionRevision == other.definitionRevision &&
                definitions == other.definitions
    }

    private sealed interface Compilation {
        data class Compiled(val replacement: PathReplacement?, val usable: ProtectionRouteTableDefinition?) : Compilation
        data class Rejected(val diagnostic: ProtectionRouteTableDiagnostic) : Compilation
    }

    private sealed interface RouteCompilation {
        data class Compiled(val path: PhysicalTraversalPath) : RouteCompilation
        data class Rejected(val diagnostic: ProtectionRouteTableDiagnostic) : RouteCompilation
    }

    private companion object {
        const val REPLACEMENT_POSITION_EPSILON = 1e-9
    }
}
