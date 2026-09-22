package work.art1st.javarailways.basic.infrastructure

import work.art1st.javarailways.basic.api.route.NAMESPACED_ROUTE_ID
import work.art1st.javarailways.basic.api.route.ProtectedRouteRequest
import work.art1st.javarailways.basic.api.route.ProtectedRouteWindow
import work.art1st.javarailways.basic.api.route.ProtectionRegionCatalogResult
import work.art1st.javarailways.basic.api.route.ProtectionRegionCatalogStatus
import work.art1st.javarailways.basic.api.route.ProtectionRegionDefinition
import work.art1st.javarailways.basic.api.route.ProtectionRegionDiagnostic
import work.art1st.javarailways.basic.api.route.ProtectionRegionProvider
import work.art1st.javarailways.basic.api.route.ProtectionRegionRef
import work.art1st.javarailways.basic.api.route.ProtectionRegionValidationCode
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState

/**
 * World-local provider catalog that atomically publishes only fully validated region graphs.
 *
 * Provider callbacks and graph traversal run only during register/refresh. Readers observe either
 * the complete previous snapshot or the complete replacement; a rejected update changes neither
 * provider ownership nor resolved regions.
 */
internal class ProtectionRegionCatalog(
    private val topologyState: () -> TopologyState
) {
    private val lock = Any()
    private val providers = linkedMapOf<String, ProtectionRegionProvider>()

    @Volatile
    private var published = CatalogSnapshot(
        topology = ProtectionRegionTopology.derive(topologyState()),
        regions = emptyMap()
    )

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
        val currentTopology = topologyState()
        if (snapshot.topology.topologyState !== currentTopology) {
            return ProtectionRegionLookup.StaleTopology(
                snapshot.topology.topologyRevision,
                currentTopology.revision
            )
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

    fun resolveWindow(request: ProtectedRouteRequest): ProtectionRegionWindowResolution {
        val snapshot = published
        val currentTopology = topologyState()
        if (snapshot.topology.topologyRevision != request.route.topologyRevision ||
            currentTopology.revision != request.route.topologyRevision ||
            snapshot.topology.topologyState !== currentTopology
        ) {
            return ProtectionRegionWindowResolution.Rejected(
                ProtectionRegionWindowRejection.STALE_TOPOLOGY,
                "route topology revision ${request.route.topologyRevision} does not match catalog " +
                    "revision ${snapshot.topology.topologyRevision} and current revision " +
                    currentTopology.revision
            )
        }
        val projection = when (
            val result = TopologyRouteProjector.project(request.route, snapshot.topology.topologyState)
        ) {
            is TopologyRouteProjectionResult.Projected -> result.route
            is TopologyRouteProjectionResult.Rejected -> {
                val reason = when (result.reason) {
                    TopologyRouteProjectionRejection.STALE_TOPOLOGY ->
                        ProtectionRegionWindowRejection.STALE_TOPOLOGY
                    TopologyRouteProjectionRejection.INVALID_ROUTE ->
                        ProtectionRegionWindowRejection.INVALID_ROUTE
                }
                return confirmWindowResolution(
                    snapshot,
                    currentTopology,
                    ProtectionRegionWindowResolution.Rejected(reason, result.detail)
                )
            }
        }
        val window = request.window
            ?: return confirmWindowResolution(
                snapshot,
                currentTopology,
                ProtectionRegionWindowResolution.Resolved(projection, null)
            )
        val owned = snapshot.regions[window.region.regionId]
            ?: return ProtectionRegionWindowResolution.Rejected(
                ProtectionRegionWindowRejection.MISSING_REGION,
                "protection region is not registered: ${window.region.regionId}"
            )
        val currentRegionRevision = owned.region.definition.region.revision
        if (currentRegionRevision != window.region.revision) {
            return ProtectionRegionWindowResolution.Rejected(
                ProtectionRegionWindowRejection.STALE_REGION,
                "protection region ${window.region.regionId} revision ${window.region.revision} " +
                    "does not match $currentRegionRevision"
            )
        }
        val resolvedWindow = ResolvedProtectionWindow(window, owned.region)
        val resolution = ProtectionRegionWindowResolver.resolve(projection, resolvedWindow)
        return confirmWindowResolution(snapshot, currentTopology, resolution)
    }

    /** Resolves a later window against an already validated frozen-route projection. */
    fun resolveWindow(
        projection: ProjectedTopologyRoute,
        window: ProtectedRouteWindow
    ): ProtectionRegionWindowResolution {
        val snapshot = published
        val currentTopology = topologyState()
        if (projection.topology !== currentTopology || snapshot.topology.topologyState !== currentTopology) {
            return ProtectionRegionWindowResolution.Rejected(
                ProtectionRegionWindowRejection.STALE_TOPOLOGY,
                "retained frozen route no longer uses the current topology root"
            )
        }
        val owned = snapshot.regions[window.region.regionId]
            ?: return ProtectionRegionWindowResolution.Rejected(
                ProtectionRegionWindowRejection.MISSING_REGION,
                "protection region is not registered: ${window.region.regionId}"
            )
        if (owned.region.definition.region.revision != window.region.revision) {
            return ProtectionRegionWindowResolution.Rejected(
                ProtectionRegionWindowRejection.STALE_REGION,
                "protection region ${window.region.regionId} revision ${window.region.revision} is stale"
            )
        }
        val resolution = ProtectionRegionWindowResolver.resolve(
            projection,
            ResolvedProtectionWindow(window, owned.region)
        )
        return confirmWindowResolution(snapshot, currentTopology, resolution)
    }

    private fun confirmWindowResolution(
        snapshot: CatalogSnapshot,
        capturedTopology: TopologyState,
        resolution: ProtectionRegionWindowResolution
    ): ProtectionRegionWindowResolution {
        val confirmedTopology = topologyState()
        if (confirmedTopology !== capturedTopology) {
            return ProtectionRegionWindowResolution.Rejected(
                ProtectionRegionWindowRejection.STALE_TOPOLOGY,
                "topology changed while resolving protection window from " +
                    "${capturedTopology.revision} to ${confirmedTopology.revision}"
            )
        }
        if (published !== snapshot) {
            return ProtectionRegionWindowResolution.Rejected(
                ProtectionRegionWindowRejection.STALE_REGION,
                "protection region catalog changed while resolving route window"
            )
        }
        return resolution
    }

    private fun rebuild(candidateProviders: Map<String, ProtectionRegionProvider>): RebuildResult {
        val capturedTopology = topologyState()
        val topologyRevision = capturedTopology.revision
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
        val topology = if (published.topology.topologyState === capturedTopology) {
            published.topology
        } else {
            ProtectionRegionTopology.derive(capturedTopology)
        }
        if (definitionsById.isEmpty()) {
            return confirmCurrent(capturedTopology, CatalogSnapshot(topology, emptyMap()))
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
        return confirmCurrent(capturedTopology, CatalogSnapshot(topology, resolved.toMap()))
    }

    private fun confirmCurrent(
        capturedTopology: TopologyState,
        snapshot: CatalogSnapshot
    ): RebuildResult {
        val confirmedTopology = topologyState()
        if (confirmedTopology !== capturedTopology) {
            return RebuildResult.Rejected(
                ProtectionRegionDiagnostic(
                    code = ProtectionRegionValidationCode.STALE_TOPOLOGY,
                    detail = "topology changed during region resolution from " +
                        "${capturedTopology.revision} to ${confirmedTopology.revision}"
                )
            )
        }
        return RebuildResult.Valid(snapshot)
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
        val topology: ProtectionRegionTopology,
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
