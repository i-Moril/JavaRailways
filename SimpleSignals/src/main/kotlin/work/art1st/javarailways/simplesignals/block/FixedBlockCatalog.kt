package work.art1st.javarailways.simplesignals.block

import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.api.observation.RailwayOccupancyAreaDefinition
import work.art1st.javarailways.basic.api.observation.RailwayOccupancyBoundary
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.rail.RailInterval
import work.art1st.javarailways.core.api.physics.rail.RailObjectRef
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.simplesignals.region.ProtectionRegionTopology
import java.util.UUID

internal enum class FixedBlockCatalogStatus {
    PUBLISHED,
    UNCHANGED,
    REJECTED
}

internal enum class FixedBlockValidationCode {
    INVALID_DEFINITION_REVISION,
    INVALID_BLOCK_REVISION,
    DUPLICATE_BLOCK_ID,
    STALE_TOPOLOGY,
    MISSING_RAIL,
    TRAVERSAL_OUTSIDE_RAIL,
    DUPLICATE_BOUNDARY_POSITION,
    DISCONTINUOUS_PATH
}

internal data class FixedBlockDiagnostic(
    val code: FixedBlockValidationCode,
    val detail: String,
    val blockId: String? = null,
    val railId: UUID? = null
) {
    init {
        require(detail.isNotBlank()) { "diagnostic detail must not be blank" }
    }
}

internal data class FixedBlockCatalogResult(
    val status: FixedBlockCatalogStatus,
    val diagnostics: List<FixedBlockDiagnostic> = emptyList()
) {
    init {
        require(status == FixedBlockCatalogStatus.REJECTED || diagnostics.isEmpty()) {
            "diagnostics are only valid for rejected publications"
        }
    }
}

/** Immutable geometry compiled for one fixed block and one exact topology root. */
internal class CompiledFixedBlock(
    val definition: FixedBlockDefinition,
    traversals: Collection<FixedBlockTraversal>,
    val lengthMeters: Double,
    val occupancyArea: RailwayOccupancyAreaDefinition
) {
    /** Topology-normalized traversal; rail-end values within tolerance are snapped to the endpoint. */
    val traversals: List<FixedBlockTraversal> = java.util.List.copyOf(traversals)
}

/**
 * Immutable read view used by later section observation and route-local policy compilation.
 * Candidate discovery touches only block IDs indexed by rails present in the selected route.
 */
internal class FixedBlockReadSnapshot internal constructor(
    val topology: TopologyState,
    val definitionRevision: Long,
    blocksById: Map<String, CompiledFixedBlock>,
    blockIdsByRail: Map<UUID, Set<String>>
) {
    val blocksById: Map<String, CompiledFixedBlock> = java.util.Map.copyOf(blocksById)
    val blockIdsByRail: Map<UUID, Set<String>> = java.util.Map.copyOf(
        blockIdsByRail.mapValues { java.util.Set.copyOf(it.value) }
    )

    fun candidates(route: RouteSnapshot): List<CompiledFixedBlock> {
        require(route.topologyRevision == topology.revision) {
            "route topology revision ${route.topologyRevision} does not match fixed-block topology " +
                topology.revision
        }
        val candidateIds = linkedSetOf<String>()
        route.segments.forEach { segment ->
            blockIdsByRail[segment.railId]?.let(candidateIds::addAll)
        }
        return java.util.List.copyOf(candidateIds.sorted().map(blocksById::getValue))
    }
}

/**
 * Compiles fixed-block definitions only on load/authoring and atomically publishes one immutable
 * topology-bound read snapshot. Runtime readers never validate or walk the complete catalog.
 */
internal class FixedBlockCatalog {
    private val lock = Any()

    @Volatile
    private var publication: Publication? = null

    fun publish(
        topology: TopologyState,
        definitionRevision: Long,
        definitions: Collection<FixedBlockDefinition>
    ): FixedBlockCatalogResult = synchronized(lock) {
        require(definitionRevision >= 0L) { "definitionRevision must be non-negative" }
        val copiedDefinitions = java.util.List.copyOf(definitions.sortedBy { it.blockId })
        val current = publication
        if (current != null && definitionRevision < current.definitionRevision) {
            return@synchronized rejected(
                FixedBlockValidationCode.INVALID_DEFINITION_REVISION,
                "definition revision $definitionRevision precedes published revision " +
                    current.definitionRevision
            )
        }
        if (current != null && definitionRevision == current.definitionRevision &&
            copiedDefinitions != current.definitions
        ) {
            return@synchronized rejected(
                FixedBlockValidationCode.INVALID_DEFINITION_REVISION,
                "definition content changed without advancing revision $definitionRevision"
            )
        }
        val duplicate = copiedDefinitions.groupingBy(FixedBlockDefinition::blockId)
            .eachCount().entries.firstOrNull { it.value > 1 }
        if (duplicate != null) {
            return@synchronized rejected(
                FixedBlockValidationCode.DUPLICATE_BLOCK_ID,
                "fixed block ${duplicate.key} is defined more than once",
                duplicate.key
            )
        }
        current?.definitions?.associateBy(FixedBlockDefinition::blockId)?.let { previous ->
            copiedDefinitions.forEach { definition ->
                val retained = previous[definition.blockId] ?: return@forEach
                if (definition.revision < retained.revision) {
                    return@synchronized rejected(
                        FixedBlockValidationCode.INVALID_BLOCK_REVISION,
                        "fixed block ${definition.blockId} revision ${definition.revision} precedes " +
                            "published revision ${retained.revision}",
                        definition.blockId
                    )
                }
                if (definition.revision == retained.revision && definition != retained) {
                    return@synchronized rejected(
                        FixedBlockValidationCode.INVALID_BLOCK_REVISION,
                        "fixed block ${definition.blockId} changed without advancing revision " +
                            definition.revision,
                        definition.blockId
                    )
                }
            }
        }

        val derivedTopology = try {
            if (copiedDefinitions.isEmpty()) {
                ProtectionRegionTopology.empty(topology)
            } else {
                ProtectionRegionTopology.derive(topology)
            }
        } catch (failure: Exception) {
            return@synchronized rejected(
                FixedBlockValidationCode.STALE_TOPOLOGY,
                "topology ${topology.revision} could not be indexed: ${failure.message.orEmpty()}"
            )
        }
        val blocks = linkedMapOf<String, CompiledFixedBlock>()
        val byRail = linkedMapOf<UUID, MutableSet<String>>()
        for (definition in copiedDefinitions) {
            when (val compilation = compile(definition, derivedTopology)) {
                is Compilation.Compiled -> {
                    blocks[definition.blockId] = compilation.block
                    definition.traversals.forEach { traversal ->
                        byRail.getOrPut(traversal.railId, ::linkedSetOf) += definition.blockId
                    }
                }
                is Compilation.Rejected -> return@synchronized FixedBlockCatalogResult(
                    FixedBlockCatalogStatus.REJECTED,
                    listOf(compilation.diagnostic)
                )
            }
        }
        val snapshot = FixedBlockReadSnapshot(topology, definitionRevision, blocks, byRail)
        val candidate = Publication(topology, definitionRevision, copiedDefinitions, snapshot)
        if (current?.equivalentTo(candidate) == true) {
            return@synchronized FixedBlockCatalogResult(FixedBlockCatalogStatus.UNCHANGED)
        }
        publication = candidate
        FixedBlockCatalogResult(FixedBlockCatalogStatus.PUBLISHED)
    }

    /** Returns the publication only for the exact immutable root used to compile it. */
    fun snapshot(topology: TopologyState): FixedBlockReadSnapshot {
        val captured = publication
            ?: error("fixed-block catalog has not been published")
        check(captured.topology === topology) {
            "fixed-block publication is stale for topology ${topology.revision}"
        }
        return captured.snapshot
    }

    private fun compile(
        definition: FixedBlockDefinition,
        topology: ProtectionRegionTopology
    ): Compilation {
        val compiledTraversals = ArrayList<FixedBlockTraversal>(definition.traversals.size)
        for (traversal in definition.traversals) {
            val rail = topology.rails[traversal.railId] ?: return rejectedCompilation(
                FixedBlockValidationCode.MISSING_RAIL,
                "fixed block ${definition.blockId} references missing rail ${traversal.railId}",
                definition,
                traversal.railId
            )
            if (traversal.upperAbMeters > rail.lengthMeters + Curve.TOLERANCE) {
                return rejectedCompilation(
                    FixedBlockValidationCode.TRAVERSAL_OUTSIDE_RAIL,
                    "fixed block ${definition.blockId} traversal on rail ${traversal.railId} " +
                        "ends at ${traversal.upperAbMeters}, beyond rail length ${rail.lengthMeters}",
                    definition,
                    traversal.railId
                )
            }
            val compiledUpper = minOf(traversal.upperAbMeters, rail.lengthMeters)
            val compiledLower = minOf(traversal.lowerAbMeters, rail.lengthMeters)
            if (compiledUpper <= compiledLower) {
                return rejectedCompilation(
                    FixedBlockValidationCode.TRAVERSAL_OUTSIDE_RAIL,
                    "fixed block ${definition.blockId} traversal on rail ${traversal.railId} " +
                        "has no positive range within rail length ${rail.lengthMeters}",
                    definition,
                    traversal.railId
                )
            }
            compiledTraversals += traversal.copy(
                lowerAbMeters = compiledLower,
                upperAbMeters = compiledUpper
            )
        }
        compiledTraversals.zipWithNext().forEach { (current, next) ->
            if (!continuous(current, next, topology)) {
                return rejectedCompilation(
                    FixedBlockValidationCode.DISCONTINUOUS_PATH,
                    "fixed block ${definition.blockId} is not physically continuous between rails " +
                        "${current.railId} and ${next.railId}",
                    definition
                )
            }
        }

        val ref = definition.blockId.toRailObjectRef()
        val first = compiledTraversals.first()
        val last = compiledTraversals.last()
        if (first.railId == last.railId &&
            samePosition(first.travelStartAbMeters, last.travelEndAbMeters)
        ) {
            return rejectedCompilation(
                FixedBlockValidationCode.DUPLICATE_BOUNDARY_POSITION,
                "fixed block ${definition.blockId} entry and exit resolve to the same rail position",
                definition,
                first.railId
            )
        }
        val area = RailwayOccupancyAreaDefinition.create(
            areaId = ref,
            definitionRevision = definition.revision,
            topologyRevision = topology.topologyRevision,
            intervals = mergeIntervals(compiledTraversals),
            boundaries = listOf(
                RailwayOccupancyBoundary(
                    RailObjectRef(ref.namespace, "${ref.id}/entry"),
                    RailPositionSnapshot(first.railId, first.facing, first.travelStartAbMeters)
                ),
                RailwayOccupancyBoundary(
                    RailObjectRef(ref.namespace, "${ref.id}/exit"),
                    RailPositionSnapshot(
                        last.railId,
                        last.facing.opposite(),
                        last.travelEndAbMeters
                    )
                )
            )
        )
        return Compilation.Compiled(
            CompiledFixedBlock(
                definition,
                compiledTraversals,
                compiledTraversals.sumOf(FixedBlockTraversal::lengthMeters),
                area
            )
        )
    }

    private fun continuous(
        current: FixedBlockTraversal,
        next: FixedBlockTraversal,
        topology: ProtectionRegionTopology
    ): Boolean {
        if (current.railId == next.railId) {
            return current.facing == next.facing &&
                samePosition(current.travelEndAbMeters, next.travelStartAbMeters)
        }
        val currentRail = topology.rails[current.railId] ?: return false
        val nextRail = topology.rails[next.railId] ?: return false
        val exit = endpointAt(current.travelEndAbMeters, currentRail.lengthMeters) ?: return false
        if (endpointAt(next.travelStartAbMeters, nextRail.lengthMeters) == null) return false
        val continuations = when (exit) {
            RailEndpoint.A -> currentRail.connectionsFromA
            RailEndpoint.B -> currentRail.connectionsFromB
        }
        return continuations.any { connection ->
            connection.railId == next.railId && connection.enteringFacing == next.facing
        }
    }

    private fun mergeIntervals(traversals: List<FixedBlockTraversal>): List<RailInterval> {
        val merged = ArrayList<RailInterval>()
        traversals.groupBy(FixedBlockTraversal::railId).toSortedMap(compareBy(UUID::toString))
            .forEach { (railId, spans) ->
                val sorted = spans.sortedWith(
                    compareBy(FixedBlockTraversal::lowerAbMeters)
                        .thenBy(FixedBlockTraversal::upperAbMeters)
                )
                var lower = sorted.first().lowerAbMeters
                var upper = sorted.first().upperAbMeters
                sorted.drop(1).forEach { span ->
                    if (span.lowerAbMeters <= upper + Curve.TOLERANCE) {
                        upper = maxOf(upper, span.upperAbMeters)
                    } else {
                        merged += RailInterval(railId, lower, upper)
                        lower = span.lowerAbMeters
                        upper = span.upperAbMeters
                    }
                }
                merged += RailInterval(railId, lower, upper)
            }
        return merged
    }

    private fun endpointAt(position: Double, length: Double): RailEndpoint? = when {
        samePosition(position, 0.0) -> RailEndpoint.A
        samePosition(position, length) -> RailEndpoint.B
        else -> null
    }

    private fun samePosition(first: Double, second: Double): Boolean =
        kotlin.math.abs(first - second) <= Curve.TOLERANCE

    private fun ViewDirection.opposite(): ViewDirection = when (this) {
        ViewDirection.AB -> ViewDirection.BA
        ViewDirection.BA -> ViewDirection.AB
    }

    private fun String.toRailObjectRef(): RailObjectRef {
        val separator = indexOf(':')
        return RailObjectRef(substring(0, separator), substring(separator + 1))
    }

    private fun rejected(
        code: FixedBlockValidationCode,
        detail: String,
        blockId: String? = null
    ) = FixedBlockCatalogResult(
        FixedBlockCatalogStatus.REJECTED,
        listOf(FixedBlockDiagnostic(code, detail, blockId))
    )

    private fun rejectedCompilation(
        code: FixedBlockValidationCode,
        detail: String,
        definition: FixedBlockDefinition,
        railId: UUID? = null
    ) = Compilation.Rejected(
        FixedBlockDiagnostic(code, detail, definition.blockId, railId)
    )

    private sealed interface Compilation {
        data class Compiled(val block: CompiledFixedBlock) : Compilation
        data class Rejected(val diagnostic: FixedBlockDiagnostic) : Compilation
    }

    private data class Publication(
        val topology: TopologyState,
        val definitionRevision: Long,
        val definitions: List<FixedBlockDefinition>,
        val snapshot: FixedBlockReadSnapshot
    ) {
        fun equivalentTo(other: Publication): Boolean =
            topology === other.topology &&
                definitionRevision == other.definitionRevision &&
                definitions == other.definitions
    }
}
