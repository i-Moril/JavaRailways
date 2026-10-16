package work.art1st.javarailways.simplesignals.region

import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import java.util.UUID

internal enum class ProtectionRegionCatalogStatus {
    PUBLISHED,
    UNCHANGED,
    REJECTED
}

internal data class ProtectionRegionCatalogResult(
    val status: ProtectionRegionCatalogStatus,
    val diagnostics: List<ProtectionRegionDiagnostic> = emptyList()
) {
    init {
        require(status == ProtectionRegionCatalogStatus.REJECTED || diagnostics.isEmpty()) {
            "diagnostics are only valid for rejected publications"
        }
    }
}

internal sealed interface ProtectionRegionRouteMatchResult {
    data class Matched(val windows: List<ProtectionRegionRouteWindow>) : ProtectionRegionRouteMatchResult
    data class Rejected(val diagnostics: List<ProtectionRegionDiagnostic>) : ProtectionRegionRouteMatchResult
}

/** Immutable region publication captured by low-frequency plugin compilers. */
internal class ProtectionRegionCompilationSnapshot(
    val topology: TopologyState,
    val definitionRevision: Long,
    private val compiledTopology: ProtectionRegionTopology,
    val regionsById: Map<String, ResolvedProtectionRegion>,
    val regionIdsByRail: Map<UUID, Set<String>>,
    val unavailableRegionIds: Set<String> = emptySet()
) {
    /** Authoring identity index; built with the publication, never reconstructed by Wand clicks. */
    val boundariesById: Map<String, ProtectionBoundaryDefinition> = boundaryIndex(regionsById.values)
    internal var liveTopology: (() -> TopologyState)? = null
    internal var requireUsable: (RouteSnapshot, Double) -> Unit = { _, _ -> }
    val derivedTopology: ProtectionRegionTopology
        get() = liveTopology?.invoke()?.takeUnless { it === topology }?.let(ProtectionRegionTopology::direct) ?: compiledTopology
    fun match(route: RouteSnapshot): ProtectionRegionRouteMatchResult {
        requireUsable(route, route.totalLengthMeters)
        if (regionsById.isEmpty()) {
            return ProtectionRegionRouteMatchResult.Matched(emptyList())
        }
        return ProtectionRegionRouteMatcher.matchIndexed(
            route,
            derivedTopology,
            regionsById,
            regionIdsByRail
        )
    }

    private companion object {
        fun boundaryIndex(regions: Collection<ResolvedProtectionRegion>): Map<String, ProtectionBoundaryDefinition> {
            val boundaries = regions.flatMap { it.definition.placedEntrances }
            val byId = boundaries.associateBy { it.boundaryId }
            require(byId.size == boundaries.size) { "published protection boundary IDs must be unique" }
            return java.util.Map.copyOf(byId)
        }
    }
}

/**
 * Atomically publishes plugin-owned protection definitions and their disposable topology index.
 * A rejected rebuild preserves the previous snapshot. Rebuild work occurs on authoring/load, not
 * in a train tick; route matching consults only regions indexed by rails on the frozen route.
 */
internal class ProtectionRegionCatalog {
    private val lock = Any()
    @Volatile
    private var snapshot: Snapshot? = null

    fun publish(
        topology: TopologyState,
        definitionRevision: Long,
        definitions: Collection<ProtectionRegionDefinition>,
        topologyIndex: ProtectionRegionTopology? = null
    ): ProtectionRegionCatalogResult = synchronized(lock) {
        require(definitionRevision >= 0L) { "definitionRevision must be non-negative" }
        val copiedDefinitions = java.util.List.copyOf(
            definitions.sortedBy(ProtectionRegionDefinition::regionId)
        )
        val current = snapshot
        if (current != null && definitionRevision < current.definitionRevision) {
            return@synchronized rejected(
                ProtectionRegionValidationCode.INVALID_DEFINITION_REVISION,
                "definition revision $definitionRevision precedes published revision " +
                    current.definitionRevision
            )
        }
        if (current != null &&
            definitionRevision == current.definitionRevision &&
            copiedDefinitions != current.definitions
        ) {
            return@synchronized rejected(
                ProtectionRegionValidationCode.INVALID_DEFINITION_REVISION,
                "definition content changed without advancing revision $definitionRevision"
            )
        }
        val duplicate = copiedDefinitions.groupBy(ProtectionRegionDefinition::regionId)
            .entries.firstOrNull { it.value.size > 1 }
        if (duplicate != null) {
            return@synchronized rejected(
                ProtectionRegionValidationCode.DUPLICATE_REGION_ID,
                "region ${duplicate.key} is defined more than once",
                duplicate.key
            )
        }

        val derivedTopology = try {
            if (topologyIndex != null) {
                require(topologyIndex.topologyState === topology) { "region index targets a different topology root" }
                topologyIndex
            } else if (copiedDefinitions.isEmpty()) {
                ProtectionRegionTopology.empty(topology)
            } else {
                ProtectionRegionTopology.derive(topology)
            }
        } catch (failure: Exception) {
            return@synchronized rejected(
                ProtectionRegionValidationCode.STALE_TOPOLOGY,
                "topology ${topology.revision} could not be indexed: ${failure.message.orEmpty()}"
            )
        }
        val resolved = ArrayList<ResolvedProtectionRegion>(copiedDefinitions.size)
        for (definition in copiedDefinitions) {
            when (val result = ProtectionRegionGraphResolver.resolve(definition, derivedTopology)) {
                is ProtectionRegionGraphResolution.Resolved -> resolved += result.region
                is ProtectionRegionGraphResolution.Rejected ->
                    return@synchronized ProtectionRegionCatalogResult(
                        ProtectionRegionCatalogStatus.REJECTED,
                        listOf(result.diagnostic)
                    )
            }
        }
        for (index in resolved.indices) {
            for (other in index + 1 until resolved.size) {
                ProtectionRegionGraphResolver.overlap(resolved[index], resolved[other])?.let {
                    return@synchronized ProtectionRegionCatalogResult(
                        ProtectionRegionCatalogStatus.REJECTED,
                        listOf(it)
                    )
                }
            }
        }

        val candidate = Snapshot.create(topology, definitionRevision, derivedTopology, resolved)
        if (snapshot?.equivalentTo(candidate) == true) {
            return@synchronized ProtectionRegionCatalogResult(ProtectionRegionCatalogStatus.UNCHANGED)
        }
        snapshot = candidate
        ProtectionRegionCatalogResult(ProtectionRegionCatalogStatus.PUBLISHED)
    }

    fun definitions(): List<ProtectionRegionDefinition> = snapshot?.definitions.orEmpty()

    /**
     * Returns the exact immutable publication currently backing route matching.
     * Consumers compare this object's identity to detect a later region publication.
     */
    fun compilationSnapshot(): ProtectionRegionCompilationSnapshot? = snapshot?.compilationSnapshot

    fun match(route: RouteSnapshot): ProtectionRegionRouteMatchResult {
        val captured = snapshot ?: return ProtectionRegionRouteMatchResult.Rejected(
            listOf(
                ProtectionRegionDiagnostic(
                    ProtectionRegionValidationCode.STALE_TOPOLOGY,
                    "protection-region catalog has not been published"
                )
            )
        )
        return captured.compilationSnapshot.match(route)
    }

    private fun rejected(
        code: ProtectionRegionValidationCode,
        detail: String,
        regionId: String? = null
    ) = ProtectionRegionCatalogResult(
        ProtectionRegionCatalogStatus.REJECTED,
        listOf(ProtectionRegionDiagnostic(code, detail, regionId = regionId))
    )

    private data class Snapshot(
        val topology: TopologyState,
        val definitionRevision: Long,
        val derivedTopology: ProtectionRegionTopology,
        val definitions: List<ProtectionRegionDefinition>,
        val regionsById: Map<String, ResolvedProtectionRegion>,
        val regionIdsByRail: Map<UUID, Set<String>>,
        val compilationSnapshot: ProtectionRegionCompilationSnapshot
    ) {
        fun equivalentTo(other: Snapshot): Boolean =
            topology === other.topology &&
                definitionRevision == other.definitionRevision &&
                definitions == other.definitions

        companion object {
            fun create(
                topology: TopologyState,
                definitionRevision: Long,
                derivedTopology: ProtectionRegionTopology,
                regions: Collection<ResolvedProtectionRegion>,
                unavailable: Set<String> = emptySet()
            ): Snapshot {
                val definitions = regions.map(ResolvedProtectionRegion::definition)
                val byId = regions.associateBy { it.definition.regionId }
                val byRail = linkedMapOf<UUID, MutableSet<String>>()
                for (region in regions) {
                    for (slice in region.interior) {
                        byRail.getOrPut(slice.railId, ::linkedSetOf) += region.definition.regionId
                    }
                }
                val immutableById = java.util.Map.copyOf(byId)
                val immutableByRail = java.util.Map.copyOf(
                    byRail.mapValues { java.util.Set.copyOf(it.value) }
                )
                return Snapshot(
                    topology,
                    definitionRevision,
                    derivedTopology,
                    java.util.List.copyOf(definitions),
                    immutableById,
                    immutableByRail,
                    ProtectionRegionCompilationSnapshot(
                        topology,
                        definitionRevision,
                        derivedTopology,
                        immutableById,
                        immutableByRail,
                        java.util.Set.copyOf(unavailable)
                    )
                )
            }
        }
    }

    companion object {
        fun fromCompiled(topology: TopologyState, revision: Long,
            regions: Collection<ResolvedProtectionRegion>, unavailable: Set<String>): ProtectionRegionCatalog =
            ProtectionRegionCatalog().also {
                it.snapshot = Snapshot.create(topology, revision, ProtectionRegionTopology.direct(topology), regions, unavailable)
            }
    }
}
