package work.art1st.javarailways.basic.infrastructure.admission

import work.art1st.javarailways.basic.api.infrastructure.InfrastructureFootprint
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureResource
import work.art1st.javarailways.basic.api.infrastructure.RailIntervalResource
import work.art1st.javarailways.basic.api.infrastructure.StableInfrastructureResource
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.rail.RailInterval
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyNode
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import work.art1st.javarailways.basic.infrastructure.ProjectedRouteOccurrence
import work.art1st.javarailways.basic.infrastructure.ProjectedTopologyRoute
import work.art1st.javarailways.basic.infrastructure.ProjectedTurnoutTraversal
import work.art1st.javarailways.basic.infrastructure.RouteBoundResourceCatalog
import work.art1st.javarailways.basic.infrastructure.RouteBoundResourceHit
import work.art1st.javarailways.basic.infrastructure.RouteBoundResourceQuery
import work.art1st.javarailways.basic.infrastructure.TurnoutResourceIdentity

/** Explicit route-coordinate range whose unavoidable physical resources must be compiled. */
internal data class PhysicalRouteRange(
    val startRouteMeters: Double,
    val endRouteMeters: Double,
    val includeTurnoutAtStart: Boolean = true
) {
    init {
        require(startRouteMeters.isFinite() && startRouteMeters >= 0.0)
        require(endRouteMeters.isFinite() && endRouteMeters > startRouteMeters)
    }
}

internal enum class PhysicalRouteCompilationRejection {
    STALE_TOPOLOGY,
    INVALID_ROUTE,
    REQUEST_RANGE_TOO_LONG
}

internal sealed interface PhysicalRouteCompilation {
    data class Compiled(
        val admission: CompiledRouteAdmission
    ) : PhysicalRouteCompilation

    data class Rejected(
        val reason: PhysicalRouteCompilationRejection,
        val detail: String,
        val retryAtOrBeforeMeters: Double? = null
    ) : PhysicalRouteCompilation
}

/**
 * Compiles Basic's non-removable physical resource floor from an already projected frozen route.
 *
 * Protection regions and other signalling policies are deliberately absent from this component.
 */
internal class PhysicalRouteResourceCompiler(
    private val routeBoundResources: RouteBoundResourceCatalog
) {
    fun compile(
        projection: ProjectedTopologyRoute,
        range: PhysicalRouteRange,
        retainedProgressPath: CompiledRouteProgressPath? = null,
        heldResources: Set<InfrastructureResource> = emptySet()
    ): PhysicalRouteCompilation {
        val routeEnd = projection.occurrences.lastOrNull()?.routeEndMeters
            ?: return invalid("projected route has no occurrences")
        if (range.endRouteMeters > routeEnd + Curve.TOLERANCE) {
            return invalid("physical route range exceeds the projected route")
        }
        val hits = when (val queried = routeBoundResources.query(
            projection,
            range.startRouteMeters,
            range.endRouteMeters,
            occurrenceIndexAt(projection.occurrences, range.startRouteMeters)
        )) {
            is RouteBoundResourceQuery.Complete -> queried.hits
            is RouteBoundResourceQuery.Unavailable -> return stale(queried.detail)
            is RouteBoundResourceQuery.StaleTopology -> return stale(queried.detail)
        }
        return compileResolved(
            projection,
            range,
            hits,
            retainedProgressPath,
            heldResources
        )
    }

    private fun compileResolved(
        projection: ProjectedTopologyRoute,
        range: PhysicalRouteRange,
        routeBoundHits: List<RouteBoundResourceHit>,
        retainedProgressPath: CompiledRouteProgressPath? = null,
        heldResources: Set<InfrastructureResource> = emptySet()
    ): PhysicalRouteCompilation {
        val outsideHit = routeBoundHits.firstOrNull {
            it.routeStartMeters < range.startRouteMeters - Curve.TOLERANCE ||
                it.routeEndMeters > range.endRouteMeters + Curve.TOLERANCE
        }
        if (outsideHit != null) {
            return invalid("route-bound resource hits must lie inside the physical route range")
        }

        val relevantTurnouts = projection.turnoutTraversals.filter { traversal ->
            (if (range.includeTurnoutAtStart) {
                traversal.routeDistanceMeters >= range.startRouteMeters - Curve.TOLERANCE
            } else {
                traversal.routeDistanceMeters > range.startRouteMeters + Curve.TOLERANCE
            }) && traversal.routeDistanceMeters <= range.endRouteMeters + Curve.TOLERANCE
        }
        val seenTurnouts = HashSet<StableInfrastructureResource>()
        val blockedTurnout = relevantTurnouts.firstOrNull {
            it.resource in heldResources || !seenTurnouts.add(it.resource)
        }
        if (blockedTurnout != null) return PhysicalRouteCompilation.Rejected(
            PhysicalRouteCompilationRejection.REQUEST_RANGE_TOO_LONG,
            "request must stop before the next use of turnout ${blockedTurnout.resource.resourceId}",
            // Both compiler range ends and turnout-start tests use Curve.TOLERANCE. nextDown alone
            // would still include this device, so leave an explicit two-tolerance gap.
            (blockedTurnout.routeDistanceMeters - 2.0 * Curve.TOLERANCE).coerceAtLeast(range.startRouteMeters)
        )
        val turnoutResources = relevantTurnouts.map(ProjectedTurnoutTraversal::resource)
        val controlledResources = turnoutResources + routeBoundHits.map(RouteBoundResourceHit::resource)

        val selectedRouteIntervals = routeIntervals(
            projection.occurrences,
            range.startRouteMeters,
            range.endRouteMeters
        )
        val resources = ArrayList<InfrastructureResource>()
        resources += selectedRouteIntervals
        resources += controlledResources.distinct().sortedWith(
            compareBy(StableInfrastructureResource::typeId, StableInfrastructureResource::resourceId)
        )
        val footprint = InfrastructureFootprint(resources)
        val occupancyGeometry = OccupancyQueryGeometry.create(
            selectedRouteIntervals.map { it.toRailInterval() } +
                turnoutOccupancyIntervals(projection.topology, relevantTurnouts) +
                routeBoundHits.flatMap { hit ->
                    hit.occupancyAnchors.map { anchor ->
                        RailInterval(anchor.railId, anchor.startAbMeters, anchor.endAbMeters)
                    }
                }
        )
        val progressPath = retainedProgressPath ?: CompiledRouteProgressPath.compile(projection)
        val admission = CompiledRouteAdmission.create(
            resources = footprint,
            preparations = relevantTurnouts.map { it.toResolvedSelection() },
            occupancyGeometry = occupancyGeometry,
            tailReleaseEntries = tailReleaseEntries(
                footprint,
                projection.occurrences,
                range.startRouteMeters,
                range.endRouteMeters,
                relevantTurnouts,
                routeBoundHits
            ),
            routeProgressPath = progressPath
        )
        return PhysicalRouteCompilation.Compiled(admission)
    }

    /**
     * Recalculates release positions for an existing claim after a stopped train reverses.
     *
     * Rail interval identities deliberately remain unchanged even when they extend beyond the new
     * body route. An interval that still overlaps the route is retained until the new tail clears
     * its final overlap; any retained resource with no new-route evidence becomes eligible at the
     * route origin. Stable-resource boundaries come from the newly validated physical/plugin
     * compilation and are therefore already expressed in the new route coordinate.
     */
    fun rebaseReleaseEntries(
        projection: ProjectedTopologyRoute,
        range: PhysicalRouteRange,
        retainedResources: Collection<InfrastructureResource>,
        stableBoundaries: Map<StableInfrastructureResource, Double>
    ): List<ResolvedTailReleaseEntry> {
        val boundaries = retainedResources.associateWithTo(LinkedHashMap()) { 0.0 }
        val retainedRails = retainedResources.filterIsInstance<RailIntervalResource>()
            .groupBy(RailIntervalResource::railId)
            .mapValues { (_, resources) ->
                val sorted = resources.sortedBy(RailIntervalResource::startAbMeters)
                // Rolling extensions retain exact identities, so intervals may overlap or nest.
                val maximumEnds = DoubleArray(sorted.size)
                sorted.indices.forEach { index ->
                    maximumEnds[index] = max(sorted[index].endAbMeters,
                        if (index == 0) Double.NEGATIVE_INFINITY else maximumEnds[index - 1])
                }
                sorted to maximumEnds
            }

        projection.occurrences.forEach { occurrence ->
            val clippedStart = max(range.startRouteMeters, occurrence.routeStartMeters)
            val clippedEnd = min(range.endRouteMeters, occurrence.routeEndMeters)
            if (clippedEnd <= clippedStart) return@forEach
            val firstCanonical = occurrence.canonicalAt(clippedStart)
            val secondCanonical = occurrence.canonicalAt(clippedEnd)
            val occurrenceStart = min(firstCanonical, secondCanonical)
            val occurrenceEnd = max(firstCanonical, secondCanonical)
            val (resources, maximumEnds) = retainedRails[occurrence.segment.railId] ?: return@forEach
            var low = 0
            var high = resources.size
            while (low < high) {
                val middle = (low + high) ushr 1
                if (maximumEnds[middle] < occurrenceStart) low = middle + 1 else high = middle
            }
            var resourceIndex = low
            while (resourceIndex < resources.size) {
                val resource = resources[resourceIndex]
                if (resource.startAbMeters > occurrenceEnd) break
                val overlapStart = max(occurrenceStart, resource.startAbMeters)
                val overlapEnd = min(occurrenceEnd, resource.endAbMeters)
                if (overlapEnd >= overlapStart) {
                    val firstRoute = occurrence.routeAtCanonical(overlapStart)
                    val secondRoute = occurrence.routeAtCanonical(overlapEnd)
                    boundaries[resource] = maxOf(boundaries.getValue(resource), firstRoute, secondRoute)
                }
                resourceIndex++
            }
        }
        stableBoundaries.forEach { (resource, boundary) ->
            if (resource in boundaries) boundaries[resource] = maxOf(boundaries.getValue(resource), boundary)
        }
        return boundaries.map { (resource, boundary) -> ResolvedTailReleaseEntry(resource, boundary) }
            .sortedWith(TAIL_RELEASE_ENTRY_ORDER)
    }

    private fun tailReleaseEntries(
        footprint: InfrastructureFootprint,
        occurrences: List<ProjectedRouteOccurrence>,
        startRouteMeters: Double,
        endRouteMeters: Double,
        turnouts: List<ProjectedTurnoutTraversal>,
        routeBoundHits: List<RouteBoundResourceHit>
    ): List<ResolvedTailReleaseEntry> {
        val finalTailClearBoundaries = LinkedHashMap<InfrastructureResource, Double>()
        val railResourcesByRail = footprint.resources.filterIsInstance<RailIntervalResource>()
            .groupBy(RailIntervalResource::railId)
            .mapValues { (railId, resources) ->
                resources.sortedBy(RailIntervalResource::startAbMeters).also { sorted ->
                    check(sorted.zipWithNext().all { (first, second) ->
                        first.endAbMeters < second.startAbMeters
                    }) { "release index requires disjoint claim intervals on rail $railId" }
                }
            }
        railResourcesByRail.values.flatten().forEach { finalTailClearBoundaries[it] = Double.NaN }
        occurrences.forEach { occurrence ->
            val clippedStart = max(startRouteMeters, occurrence.routeStartMeters)
            val clippedEnd = min(endRouteMeters, occurrence.routeEndMeters)
            if (clippedEnd <= clippedStart) return@forEach
            val firstCanonical = occurrence.canonicalAt(clippedStart)
            val secondCanonical = occurrence.canonicalAt(clippedEnd)
            val occurrenceStart = min(firstCanonical, secondCanonical)
            val occurrenceEnd = max(firstCanonical, secondCanonical)
            val railResources = railResourcesByRail[occurrence.segment.railId] ?: return@forEach
            var resourceIndex = railResources.lowerBoundByEnd(occurrenceStart)
            while (resourceIndex < railResources.size) {
                val resource = railResources[resourceIndex]
                if (resource.startAbMeters > occurrenceEnd) break
                val overlapStart = max(occurrenceStart, resource.startAbMeters)
                val overlapEnd = min(occurrenceEnd, resource.endAbMeters)
                if (overlapEnd >= overlapStart) {
                    val firstRoute = occurrence.routeAtCanonical(overlapStart)
                    val secondRoute = occurrence.routeAtCanonical(overlapEnd)
                    finalTailClearBoundaries[resource] = laterBoundary(
                        finalTailClearBoundaries.getValue(resource),
                        max(firstRoute, secondRoute)
                    )
                }
                resourceIndex++
            }
        }
        turnouts.forEach { traversal ->
            finalTailClearBoundaries[traversal.resource] = laterBoundary(
                finalTailClearBoundaries[traversal.resource] ?: Double.NaN,
                traversal.routeDistanceMeters
            )
        }
        routeBoundHits.forEach { hit ->
            finalTailClearBoundaries[hit.resource] = laterBoundary(
                finalTailClearBoundaries[hit.resource] ?: Double.NaN,
                hit.routeEndMeters
            )
        }
        return footprint.resources.map { resource ->
            val boundary = checkNotNull(finalTailClearBoundaries[resource]) {
                "claimed resource has no release evidence: $resource"
            }
            check(boundary.isFinite()) { "claimed resource has no validated route occurrence: $resource" }
            ResolvedTailReleaseEntry(resource, boundary)
        }.sortedWith(TAIL_RELEASE_ENTRY_ORDER)
    }

    private fun routeIntervals(
        occurrences: List<ProjectedRouteOccurrence>,
        startRouteMeters: Double,
        endRouteMeters: Double
    ): List<RailIntervalResource> {
        val intervals = occurrences.mapNotNull { occurrence ->
            val overlapStart = max(startRouteMeters, occurrence.routeStartMeters)
            val overlapEnd = min(endRouteMeters, occurrence.routeEndMeters)
            if (overlapEnd <= overlapStart) return@mapNotNull null
            val first = occurrence.canonicalAt(overlapStart)
            val second = occurrence.canonicalAt(overlapEnd)
            RailIntervalResource(
                occurrence.segment.railId,
                min(first, second).coerceIn(0.0, occurrence.railLengthMeters),
                max(first, second).coerceIn(0.0, occurrence.railLengthMeters)
            )
        }
        val merged = ArrayList<RailIntervalResource>()
        intervals.groupBy(RailIntervalResource::railId)
            .toSortedMap(compareBy(UUID::toString))
            .forEach { (_, railIntervals) ->
                railIntervals.sortedBy(RailIntervalResource::startAbMeters).forEach { interval ->
                    val previous = merged.lastOrNull()?.takeIf { it.railId == interval.railId }
                    if (previous != null && interval.startAbMeters <= previous.endAbMeters + Curve.TOLERANCE) {
                        merged[merged.lastIndex] = previous.copy(
                            endAbMeters = max(previous.endAbMeters, interval.endAbMeters)
                        )
                    } else {
                        merged += interval
                    }
                }
            }
        return merged
    }

    private fun ProjectedRouteOccurrence.canonicalAt(routeDistanceMeters: Double): Double {
        val offset = (routeDistanceMeters - routeStartMeters).coerceIn(0.0, segment.lengthMeters)
        return when (segment.facing) {
            ViewDirection.AB -> canonicalStartAbMeters + offset
            ViewDirection.BA -> canonicalStartAbMeters - offset
        }
    }

    private fun ProjectedRouteOccurrence.routeAtCanonical(canonicalAbMeters: Double): Double {
        val offset = when (segment.facing) {
            ViewDirection.AB -> canonicalAbMeters - canonicalStartAbMeters
            ViewDirection.BA -> canonicalStartAbMeters - canonicalAbMeters
        }
        return (routeStartMeters + offset).coerceIn(routeStartMeters, routeEndMeters)
    }

    private fun ProjectedTurnoutTraversal.toResolvedSelection() = ResolvedTurnoutSelection(
        resource,
        fromPort,
        toPort,
        routeDistanceMeters
    )

    private fun turnoutOccupancyIntervals(
        topology: TopologyState,
        traversals: List<ProjectedTurnoutTraversal>
    ): List<RailInterval> = traversals.flatMap { traversal ->
        val turnout = topology.nodes[traversal.nodeId] as? TopologyNode.Turnout
            ?: error("validated turnout traversal references a non-turnout node ${traversal.nodeId}")
        check(TurnoutResourceIdentity.fromPorts(turnout.ports) == traversal.resource) {
            "validated turnout traversal resource does not match retained topology"
        }
        turnout.ports.map { port ->
            val projection = topology.railProjection.getValue(port.railId).single()
            val railLength = max(projection.railStartAbMeters, projection.railEndAbMeters)
            val endpointMeters = if (port.endpoint == RailEndpoint.A) 0.0 else railLength
            RailInterval(port.railId, endpointMeters, endpointMeters)
        }
    }

    private fun RailIntervalResource.toRailInterval() =
        RailInterval(railId, startAbMeters, endAbMeters)

    private fun List<RailIntervalResource>.lowerBoundByEnd(canonicalMeters: Double): Int {
        var low = 0
        var high = size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (this[middle].endAbMeters < canonicalMeters) low = middle + 1 else high = middle
        }
        return low
    }

    private fun laterBoundary(current: Double, candidate: Double): Double =
        if (current.isNaN() || candidate > current) candidate else current

    private fun occurrenceIndexAt(
        occurrences: List<ProjectedRouteOccurrence>,
        routeMeters: Double
    ): Int = occurrences.indexOfFirst { it.routeEndMeters >= routeMeters - Curve.TOLERANCE }
        .takeIf { it >= 0 } ?: occurrences.lastIndex

    private fun stale(detail: String) = PhysicalRouteCompilation.Rejected(
        PhysicalRouteCompilationRejection.STALE_TOPOLOGY,
        detail
    )

    private fun invalid(detail: String) = PhysicalRouteCompilation.Rejected(
        PhysicalRouteCompilationRejection.INVALID_ROUTE,
        detail
    )
}
