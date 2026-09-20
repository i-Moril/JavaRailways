package work.art1st.javarailways.basic.infrastructure

import work.art1st.javarailways.basic.api.infrastructure.StableInfrastructureResource
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.RailSliceRef
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import java.util.Collections
import java.util.LinkedHashMap
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

internal data class RouteBoundResourceBinding(
    val resource: StableInfrastructureResource,
    val anchors: List<RailSliceRef>
)

internal sealed interface RouteBoundResourceCatalogUpdate {
    data object Published : RouteBoundResourceCatalogUpdate

    data class Rejected(val detail: String) : RouteBoundResourceCatalogUpdate {
        init {
            require(detail.isNotBlank())
        }
    }
}

internal data class RouteBoundResourceHit(
    val resource: StableInfrastructureResource,
    val routeStartMeters: Double,
    val routeEndMeters: Double
)

internal sealed interface RouteBoundResourceQuery {
    data class Complete(val hits: List<RouteBoundResourceHit>) : RouteBoundResourceQuery

    data class Unavailable(val detail: String) : RouteBoundResourceQuery

    data class StaleTopology(val detail: String) : RouteBoundResourceQuery

    data class InvalidRoute(val detail: String) : RouteBoundResourceQuery
}

/**
 * World-local immutable reverse index from canonical rail anchors to stable controlled resources.
 *
 * Publication is all-or-nothing. A null published snapshot means unavailable; a published snapshot
 * with no bindings is the distinct, complete-empty state for one exact [TopologyState] root.
 */
internal class RouteBoundResourceCatalog(
    private val topologyState: () -> TopologyState
) {
    private val lock = Any()

    @Volatile
    private var published: CatalogSnapshot? = null

    fun publish(
        topology: TopologyState,
        bindings: Collection<RouteBoundResourceBinding>
    ): RouteBoundResourceCatalogUpdate = synchronized(lock) {
        if (topologyState() !== topology) {
            return@synchronized RouteBoundResourceCatalogUpdate.Rejected(
                "route-bound resource catalog was built for a topology root that is no longer current"
            )
        }
        val candidate = when (val built = buildSnapshot(topology, bindings)) {
            is SnapshotBuild.Rejected ->
                return@synchronized RouteBoundResourceCatalogUpdate.Rejected(built.detail)
            is SnapshotBuild.Valid -> built.snapshot
        }
        if (topologyState() !== topology) {
            return@synchronized RouteBoundResourceCatalogUpdate.Rejected(
                "topology changed while building the route-bound resource catalog"
            )
        }
        published = candidate
        RouteBoundResourceCatalogUpdate.Published
    }

    fun query(
        route: ProjectedTopologyRoute,
        startRouteMeters: Double = 0.0,
        endRouteMeters: Double = route.occurrences.lastOrNull()?.routeEndMeters ?: 0.0
    ): RouteBoundResourceQuery {
        require(startRouteMeters.isFinite() && startRouteMeters >= 0.0)
        require(endRouteMeters.isFinite() && endRouteMeters >= startRouteMeters)
        val snapshot = published ?: return RouteBoundResourceQuery.Unavailable(
            "route-bound resource catalog has not published a complete snapshot"
        )
        val currentTopology = topologyState()
        if (snapshot.topology !== route.topology || currentTopology !== route.topology) {
            return RouteBoundResourceQuery.StaleTopology(
                "route, catalog, and current topology must retain the same topology root"
            )
        }

        val rawHits = ArrayList<RouteBoundResourceHit>()
        route.occurrences.forEach { occurrence ->
            val clippedStart = max(startRouteMeters, occurrence.routeStartMeters)
            val clippedEnd = min(endRouteMeters, occurrence.routeEndMeters)
            if (clippedEnd < clippedStart) return@forEach
            val firstCanonical = occurrence.canonicalAt(clippedStart)
            val secondCanonical = occurrence.canonicalAt(clippedEnd)
            val canonicalStart = min(firstCanonical, secondCanonical)
            val canonicalEnd = max(firstCanonical, secondCanonical)
            snapshot.anchorsByRail[occurrence.segment.railId]
                ?.overlapping(canonicalStart, canonicalEnd)
                ?.forEach { indexed ->
                    val overlapStart = max(canonicalStart, indexed.anchor.startAbMeters)
                    val overlapEnd = min(canonicalEnd, indexed.anchor.endAbMeters)
                    val firstRoute = occurrence.routeAtCanonical(overlapStart)
                    val secondRoute = occurrence.routeAtCanonical(overlapEnd)
                    rawHits += RouteBoundResourceHit(
                        indexed.resource,
                        min(firstRoute, secondRoute).coerceIn(clippedStart, clippedEnd),
                        max(firstRoute, secondRoute).coerceIn(clippedStart, clippedEnd)
                    )
                }
        }
        val merged = mergeOrRejectReentry(rawHits)
        val result = when (merged) {
            is MergedHits.Invalid -> RouteBoundResourceQuery.InvalidRoute(merged.detail)
            is MergedHits.Valid -> RouteBoundResourceQuery.Complete(merged.hits)
        }
        if (published !== snapshot || topologyState() !== currentTopology) {
            return RouteBoundResourceQuery.StaleTopology(
                "route-bound resource catalog or topology changed during query"
            )
        }
        return result
    }

    private fun buildSnapshot(
        topology: TopologyState,
        bindings: Collection<RouteBoundResourceBinding>
    ): SnapshotBuild {
        val bindingsByResource = LinkedHashMap<StableInfrastructureResource, RouteBoundResourceBinding>()
        val anchorsByRail = LinkedHashMap<UUID, MutableList<IndexedAnchor>>()
        for (binding in bindings) {
            if (bindingsByResource.putIfAbsent(binding.resource, binding) != null) {
                return SnapshotBuild.Rejected("duplicate route-bound resource identity: ${binding.resource}")
            }
            if (binding.anchors.isEmpty()) {
                return SnapshotBuild.Rejected("route-bound resource ${binding.resource} has no anchors")
            }
            if (binding.resource.typeId == DIAMOND_TYPE_ID &&
                (binding.anchors.size != 2 || binding.anchors.distinct().size != 2)
            ) {
                return SnapshotBuild.Rejected(
                    "diamond resource ${binding.resource.resourceId} must have two distinct anchors"
                )
            }
            val immutableAnchors = ArrayList<RailSliceRef>(binding.anchors.size)
            for (anchor in binding.anchors) {
                if (anchor.startAbMeters >= anchor.endAbMeters) {
                    return SnapshotBuild.Rejected(
                        "route-bound resource anchors must use ascending canonical AB coordinates"
                    )
                }
                val projections = topology.railProjection[anchor.railId]
                if (projections?.size != 1) {
                    return SnapshotBuild.Rejected(
                        "anchor rail ${anchor.railId} must have exactly one topology projection"
                    )
                }
                val projection = projections.single()
                val projectionStart = min(projection.railStartAbMeters, projection.railEndAbMeters)
                val projectionEnd = max(projection.railStartAbMeters, projection.railEndAbMeters)
                if (anchor.startAbMeters < projectionStart - Curve.TOLERANCE ||
                    anchor.endAbMeters > projectionEnd + Curve.TOLERANCE
                ) {
                    return SnapshotBuild.Rejected(
                        "anchor on rail ${anchor.railId} lies outside its topology projection"
                    )
                }
                immutableAnchors += anchor
                anchorsByRail.getOrPut(anchor.railId, ::ArrayList) += IndexedAnchor(binding.resource, anchor)
            }
            bindingsByResource[binding.resource] = RouteBoundResourceBinding(
                binding.resource,
                java.util.List.copyOf(immutableAnchors)
            )
        }
        val indexes = anchorsByRail.mapValues { (_, anchors) ->
            RailAnchorIndex(anchors.sortedWith(compareBy({ it.anchor.startAbMeters }, { it.anchor.endAbMeters })))
        }
        return SnapshotBuild.Valid(
            CatalogSnapshot(
                topology,
                Collections.unmodifiableMap(bindingsByResource),
                Collections.unmodifiableMap(LinkedHashMap(indexes))
            )
        )
    }

    private fun mergeOrRejectReentry(rawHits: List<RouteBoundResourceHit>): MergedHits {
        val merged = ArrayList<RouteBoundResourceHit>()
        rawHits.groupBy(RouteBoundResourceHit::resource)
            .toSortedMap(compareBy(StableInfrastructureResource::typeId, StableInfrastructureResource::resourceId))
            .forEach { (resource, resourceHits) ->
                var current: RouteBoundResourceHit? = null
                resourceHits.sortedBy(RouteBoundResourceHit::routeStartMeters).forEach { hit ->
                    val previous = current
                    if (previous == null) {
                        current = hit
                    } else if (hit.routeStartMeters <= previous.routeEndMeters + Curve.TOLERANCE) {
                        current = previous.copy(routeEndMeters = max(previous.routeEndMeters, hit.routeEndMeters))
                    } else {
                        return MergedHits.Invalid(
                            "route leaves and later re-enters controlled resource $resource"
                        )
                    }
                }
                current?.let(merged::add)
            }
        return MergedHits.Valid(java.util.List.copyOf(merged))
    }

    private fun ProjectedRouteOccurrence.canonicalAt(routeMeters: Double): Double {
        val offset = (routeMeters - routeStartMeters).coerceIn(0.0, segment.lengthMeters)
        return when (segment.facing) {
            ViewDirection.AB -> canonicalStartAbMeters + offset
            ViewDirection.BA -> canonicalStartAbMeters - offset
        }
    }

    private fun ProjectedRouteOccurrence.routeAtCanonical(canonicalMeters: Double): Double =
        routeStartMeters + when (segment.facing) {
            ViewDirection.AB -> canonicalMeters - canonicalStartAbMeters
            ViewDirection.BA -> canonicalStartAbMeters - canonicalMeters
        }

    private data class CatalogSnapshot(
        val topology: TopologyState,
        val bindings: Map<StableInfrastructureResource, RouteBoundResourceBinding>,
        val anchorsByRail: Map<UUID, RailAnchorIndex>
    )

    private data class IndexedAnchor(
        val resource: StableInfrastructureResource,
        val anchor: RailSliceRef
    )

    private class RailAnchorIndex(anchors: List<IndexedAnchor>) {
        private val sorted = java.util.List.copyOf(anchors)
        private val prefixMaximumEnd = DoubleArray(sorted.size).also { prefix ->
            sorted.indices.forEach { index ->
                prefix[index] = max(
                    sorted[index].anchor.endAbMeters,
                    if (index == 0) Double.NEGATIVE_INFINITY else prefix[index - 1]
                )
            }
        }

        fun overlapping(startAbMeters: Double, endAbMeters: Double): List<IndexedAnchor> {
            var low = 0
            var high = sorted.size
            val minimumEnd = startAbMeters - Curve.TOLERANCE
            while (low < high) {
                val middle = (low + high) ushr 1
                if (prefixMaximumEnd[middle] >= minimumEnd) high = middle else low = middle + 1
            }
            val matches = ArrayList<IndexedAnchor>()
            var index = low
            while (index < sorted.size && sorted[index].anchor.startAbMeters <= endAbMeters + Curve.TOLERANCE) {
                if (sorted[index].anchor.endAbMeters >= minimumEnd) matches += sorted[index]
                index++
            }
            return matches
        }
    }

    private sealed interface MergedHits {
        data class Valid(val hits: List<RouteBoundResourceHit>) : MergedHits
        data class Invalid(val detail: String) : MergedHits
    }

    private sealed interface SnapshotBuild {
        data class Valid(val snapshot: CatalogSnapshot) : SnapshotBuild
        data class Rejected(val detail: String) : SnapshotBuild
    }

    private companion object {
        const val DIAMOND_TYPE_ID = "javarailways:diamond"
    }
}
