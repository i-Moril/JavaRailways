package work.art1st.javarailways.basic.infrastructure

import work.art1st.javarailways.basic.api.route.NAMESPACED_ROUTE_ID
import work.art1st.javarailways.basic.api.route.ProtectionRegionCatalogResult
import work.art1st.javarailways.basic.api.route.ProtectionRegionCatalogStatus
import work.art1st.javarailways.basic.api.route.ProtectionRegionDefinition
import work.art1st.javarailways.basic.api.route.ProtectionRegionDiagnostic
import work.art1st.javarailways.basic.api.route.ProtectionRegionProvider
import work.art1st.javarailways.basic.api.route.ProtectionRegionRef
import work.art1st.javarailways.basic.api.route.ProtectionRegionValidationCode

/**
 * World-local provider catalog that atomically publishes only fully validated region graphs.
 *
 * Provider callbacks and graph traversal run only during register/refresh. Readers observe either
 * the complete previous snapshot or the complete replacement; a rejected update changes neither
 * provider ownership nor resolved regions.
 */
internal class ProtectionRegionCatalog(
    private val currentTopologyRevision: () -> Long,
    private val topologySource: ProtectionRegionTopologySource
) {
    private val lock = Any()
    private val providers = linkedMapOf<String, ProtectionRegionProvider>()

    @Volatile
    private var published = CatalogSnapshot(currentTopologyRevision(), emptyMap())

    fun register(provider: ProtectionRegionProvider): ProtectionRegionCatalogResult = synchronized(lock) {
        if (!NAMESPACED_ROUTE_ID.matches(provider.providerId)) {
            return@synchronized rejected(
                ProtectionRegionValidationCode.INVALID_PROVIDER_ID,
                "providerId must be namespaced: ${provider.providerId}"
            )
        }
        if (provider.providerId in providers) {
            return@synchronized rejected(
                ProtectionRegionValidationCode.DUPLICATE_PROVIDER,
                "protection-region provider is already registered: ${provider.providerId}"
            )
        }
        val candidate = LinkedHashMap(providers)
        candidate[provider.providerId] = provider
        when (val rebuilt = rebuild(candidate)) {
            is RebuildResult.Rejected -> ProtectionRegionCatalogResult(
                ProtectionRegionCatalogStatus.REJECTED,
                listOf(rebuilt.diagnostic)
            )
            is RebuildResult.Valid -> {
                providers[provider.providerId] = provider
                published = rebuilt.snapshot
                ProtectionRegionCatalogResult(ProtectionRegionCatalogStatus.REGISTERED)
            }
        }
    }

    fun refresh(): ProtectionRegionCatalogResult = synchronized(lock) {
        if (providers.isEmpty()) {
            return@synchronized ProtectionRegionCatalogResult(ProtectionRegionCatalogStatus.UNCHANGED)
        }
        when (val rebuilt = rebuild(providers)) {
            is RebuildResult.Rejected -> ProtectionRegionCatalogResult(
                ProtectionRegionCatalogStatus.REJECTED,
                listOf(rebuilt.diagnostic)
            )
            is RebuildResult.Valid -> {
                if (rebuilt.snapshot == published) {
                    ProtectionRegionCatalogResult(ProtectionRegionCatalogStatus.UNCHANGED)
                } else {
                    published = rebuilt.snapshot
                    ProtectionRegionCatalogResult(ProtectionRegionCatalogStatus.REFRESHED)
                }
            }
        }
    }

    fun unregister(providerId: String): ProtectionRegionCatalogResult = synchronized(lock) {
        if (providers.remove(providerId) == null) {
            return@synchronized ProtectionRegionCatalogResult(ProtectionRegionCatalogStatus.NOT_FOUND)
        }
        published = published.withoutProvider(providerId)
        ProtectionRegionCatalogResult(ProtectionRegionCatalogStatus.UNREGISTERED)
    }

    fun lookup(region: ProtectionRegionRef): ProtectionRegionLookup {
        val snapshot = published
        val currentRevision = currentTopologyRevision()
        if (snapshot.topologyRevision != currentRevision) {
            return ProtectionRegionLookup.StaleTopology(snapshot.topologyRevision, currentRevision)
        }
        val resolved = snapshot.regions[region.regionId]
            ?: return ProtectionRegionLookup.Missing
        if (resolved.region.definition.region.revision != region.revision) {
            return ProtectionRegionLookup.StaleRegion(
                requestedRevision = region.revision,
                currentRevision = resolved.region.definition.region.revision
            )
        }
        return ProtectionRegionLookup.Found(resolved.region)
    }

    fun definitions(): List<ProtectionRegionDefinition> =
        published.regions.values.map { it.region.definition }.sortedBy { it.region.regionId }

    private fun rebuild(candidateProviders: Map<String, ProtectionRegionProvider>): RebuildResult {
        val topologyRevision = currentTopologyRevision()
        val definitionsById = linkedMapOf<String, OwnedRegionDefinition>()
        for ((providerId, provider) in candidateProviders) {
            val definitions = try {
                ArrayList(provider.definitions(topologyRevision)).sortedBy { it.region.regionId }
            } catch (failure: Exception) {
                return RebuildResult.Rejected(
                    ProtectionRegionDiagnostic(
                        code = ProtectionRegionValidationCode.PROVIDER_FAILED,
                        detail = "provider $providerId failed: ${failure::class.simpleName}: " +
                            failure.message.orEmpty()
                    )
                )
            }
            for (definition in definitions) {
                if (definition.topologyRevision != topologyRevision) {
                    return RebuildResult.Rejected(
                        ProtectionRegionDiagnostic(
                            code = ProtectionRegionValidationCode.STALE_TOPOLOGY,
                            detail = "region ${definition.region.regionId} uses topology revision " +
                                "${definition.topologyRevision}, expected $topologyRevision",
                            regionId = definition.region.regionId
                        )
                    )
                }
                val previous = definitionsById.putIfAbsent(
                    definition.region.regionId,
                    OwnedRegionDefinition(providerId, definition)
                )
                if (previous != null) {
                    return RebuildResult.Rejected(
                        ProtectionRegionDiagnostic(
                            code = ProtectionRegionValidationCode.DUPLICATE_REGION_ID,
                            detail = "region ${definition.region.regionId} is defined by both " +
                                "${previous.providerId} and $providerId",
                            regionId = definition.region.regionId
                        )
                    )
                }
            }
        }
        if (definitionsById.isEmpty()) {
            return RebuildResult.Valid(CatalogSnapshot(topologyRevision, emptyMap()))
        }
        val definitions = definitionsById.values
            .sortedBy { it.definition.region.regionId }
            .map(OwnedRegionDefinition::definition)
        val topology = when (
            val captured = topologySource.capture(
                definitions.flatMap(ProtectionRegionDefinition::boundaries),
                topologyRevision
            )
        ) {
            is ProtectionRegionTopologyCapture.Captured -> captured.topology
            is ProtectionRegionTopologyCapture.Rejected -> return RebuildResult.Rejected(captured.diagnostic)
        }
        val resolved = linkedMapOf<String, OwnedResolvedRegion>()
        for ((regionId, ownedDefinition) in definitionsById) {
            val region = when (
                val resolution = ProtectionRegionGraphResolver.resolve(ownedDefinition.definition, topology)
            ) {
                is ProtectionRegionGraphResolution.Resolved -> resolution.region
                is ProtectionRegionGraphResolution.Rejected ->
                    return RebuildResult.Rejected(resolution.diagnostic)
            }
            resolved[regionId] = OwnedResolvedRegion(ownedDefinition.providerId, region)
        }
        val regions = resolved.values.toList()
        for (firstIndex in regions.indices) {
            for (secondIndex in firstIndex + 1 until regions.size) {
                ProtectionRegionGraphResolver.overlap(
                    regions[firstIndex].region,
                    regions[secondIndex].region
                )?.let { return RebuildResult.Rejected(it) }
            }
        }
        val confirmedRevision = currentTopologyRevision()
        if (confirmedRevision != topologyRevision) {
            return RebuildResult.Rejected(
                ProtectionRegionDiagnostic(
                    code = ProtectionRegionValidationCode.STALE_TOPOLOGY,
                    detail = "topology changed during region resolution from " +
                        "$topologyRevision to $confirmedRevision"
                )
            )
        }
        return RebuildResult.Valid(CatalogSnapshot(topologyRevision, resolved.toMap()))
    }

    private fun rejected(code: ProtectionRegionValidationCode, detail: String) =
        ProtectionRegionCatalogResult(
            ProtectionRegionCatalogStatus.REJECTED,
            listOf(ProtectionRegionDiagnostic(code, detail))
        )

    private data class OwnedRegionDefinition(
        val providerId: String,
        val definition: ProtectionRegionDefinition
    )

    private data class OwnedResolvedRegion(
        val providerId: String,
        val region: ResolvedProtectionRegion
    )

    private data class CatalogSnapshot(
        val topologyRevision: Long,
        val regions: Map<String, OwnedResolvedRegion>
    ) {
        fun withoutProvider(providerId: String): CatalogSnapshot = copy(
            regions = regions.filterValues { it.providerId != providerId }
        )
    }

    private sealed interface RebuildResult {
        data class Valid(val snapshot: CatalogSnapshot) : RebuildResult
        data class Rejected(val diagnostic: ProtectionRegionDiagnostic) : RebuildResult
    }
}

internal sealed interface ProtectionRegionLookup {
    data class Found(val region: ResolvedProtectionRegion) : ProtectionRegionLookup
    data object Missing : ProtectionRegionLookup
    data class StaleRegion(val requestedRevision: Long, val currentRevision: Long) : ProtectionRegionLookup
    data class StaleTopology(val cachedRevision: Long, val currentRevision: Long) : ProtectionRegionLookup
}
