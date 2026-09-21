package work.art1st.javarailways.basic.infrastructure

import work.art1st.javarailways.basic.api.infrastructure.InfrastructureFootprint
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureResource
import work.art1st.javarailways.basic.api.infrastructure.RailIntervalResource
import work.art1st.javarailways.basic.api.infrastructure.StableInfrastructureResource
import work.art1st.javarailways.basic.api.route.ProtectedRouteClaimStatus
import work.art1st.javarailways.basic.api.route.ProtectedRouteRequest
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.rail.RailInterval
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyNode
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/** Validates one frozen route/window and returns the single resource set that may be claimed. */
internal class ProtectedRouteRequestValidator(
    private val catalog: ProtectionRegionCatalog,
    private val routeBoundResources: RouteBoundResourceCatalog
) {
    fun validate(request: ProtectedRouteRequest): ProtectedRouteValidation =
        when (val resolved = catalog.resolveWindow(request)) {
            is ProtectionRegionWindowResolution.Rejected -> ProtectedRouteValidation.Rejected(
                status = when (resolved.reason) {
                    ProtectionRegionWindowRejection.STALE_TOPOLOGY ->
                        ProtectedRouteClaimStatus.STALE_TOPOLOGY
                    ProtectionRegionWindowRejection.MISSING_REGION,
                    ProtectionRegionWindowRejection.STALE_REGION,
                    ProtectionRegionWindowRejection.INVALID_ROUTE ->
                        ProtectedRouteClaimStatus.INVALID_REQUEST
                },
                detail = resolved.detail
            )
            is ProtectionRegionWindowResolution.Resolved -> validateResolved(resolved)
        }

    private fun validateResolved(
        resolved: ProtectionRegionWindowResolution.Resolved
    ): ProtectedRouteValidation {
        val window = resolved.window?.request
        val queryEnd = window?.exitClearance?.clearUntil?.routeDistanceMeters
            ?: resolved.projection.occurrences.last().routeEndMeters
        val routeBoundHits = when (
            val queried = routeBoundResources.query(resolved.projection, 0.0, queryEnd)
        ) {
            is RouteBoundResourceQuery.Complete -> queried.hits
            is RouteBoundResourceQuery.Unavailable -> return rejectedStale(queried.detail)
            is RouteBoundResourceQuery.StaleTopology -> return rejectedStale(queried.detail)
            is RouteBoundResourceQuery.InvalidRoute -> return ProtectedRouteValidation.Rejected(
                ProtectedRouteClaimStatus.INVALID_REQUEST,
                queried.detail
            )
        }
        if (window == null) {
            return if (resolved.projection.turnoutTraversals.isEmpty() && routeBoundHits.isEmpty()) {
                ProtectedRouteValidation.NotRequired
            } else {
                rejectedProtection("route contains a controlled resource but no protection window")
            }
        }

        val entry = window.entry.routeDistanceMeters
        val exit = window.exit.routeDistanceMeters
        val clearUntil = window.exitClearance.clearUntil.routeDistanceMeters
        val relevantTurnouts = resolved.projection.turnoutTraversals.filter { traversal ->
            traversal.routeDistanceMeters <= clearUntil + Curve.TOLERANCE
        }
        val unprotected = relevantTurnouts.firstOrNull { traversal ->
            traversal.routeDistanceMeters < entry - Curve.TOLERANCE ||
                traversal.routeDistanceMeters > exit + Curve.TOLERANCE
        }
        if (unprotected != null) {
            return rejectedProtection(
                "turnout ${unprotected.resource.resourceId} at route distance " +
                    "${unprotected.routeDistanceMeters} is outside the protected entry-to-exit window"
            )
        }
        val unprotectedRouteBound = routeBoundHits.firstOrNull { hit ->
            hit.routeStartMeters < entry - Curve.TOLERANCE ||
                hit.routeEndMeters > exit + Curve.TOLERANCE
        }
        if (unprotectedRouteBound != null) {
            return rejectedProtection(
                "resource ${unprotectedRouteBound.resource.resourceId} at route distance " +
                    "${unprotectedRouteBound.routeStartMeters}..${unprotectedRouteBound.routeEndMeters} " +
                    "is outside the protected entry-to-exit window"
            )
        }

        val turnoutResources = relevantTurnouts.map(ProjectedTurnoutTraversal::resource)
        if (turnoutResources.distinct().size != turnoutResources.size) {
            return ProtectedRouteValidation.Rejected(
                ProtectedRouteClaimStatus.INVALID_REQUEST,
                "protected route traverses the same turnout resource more than once"
            )
        }
        val selectedRouteIntervals = routeIntervals(resolved.projection.occurrences, entry, clearUntil)
        val resources = ArrayList<InfrastructureResource>()
        resources += selectedRouteIntervals
        resources += (turnoutResources + routeBoundHits.map(RouteBoundResourceHit::resource))
            .distinct()
            .sortedWith(
                compareBy(
                    StableInfrastructureResource::typeId,
                    StableInfrastructureResource::resourceId
                )
            )
        val footprint = InfrastructureFootprint(resources)
        val preparations = relevantTurnouts.map { it.toResolvedSelection() }
        val occupancyGeometry = OccupancyQueryGeometry.create(
            selectedRouteIntervals.map { it.toRailInterval() } +
                turnoutOccupancyIntervals(resolved.projection.topology, relevantTurnouts) +
                routeBoundHits.flatMap { hit ->
                    hit.occupancyAnchors.map { anchor ->
                        RailInterval(anchor.railId, anchor.startAbMeters, anchor.endAbMeters)
                    }
                }
        )
        val releaseSlices = releaseSlices(
            footprint = footprint,
            occurrences = resolved.projection.occurrences,
            entryRouteMeters = entry,
            clearUntilRouteMeters = clearUntil,
            topology = resolved.projection.topology,
            turnouts = relevantTurnouts,
            routeBoundHits = routeBoundHits
        )
        return ProtectedRouteValidation.Validated(
            CompiledRouteAdmission.create(
                footprint,
                preparations,
                occupancyGeometry,
                releaseSlices
            )
        )
    }

    /** Compiles one lifecycle entry for every member of the already-final claim footprint. */
    private fun releaseSlices(
        footprint: InfrastructureFootprint,
        occurrences: List<ProjectedRouteOccurrence>,
        entryRouteMeters: Double,
        clearUntilRouteMeters: Double,
        topology: TopologyState,
        turnouts: List<ProjectedTurnoutTraversal>,
        routeBoundHits: List<RouteBoundResourceHit>
    ): List<ResolvedReleaseSlice> {
        val evidence = LinkedHashMap<InfrastructureResource, MutableReleaseEvidence>()

        val railResourcesByRail = footprint.resources.filterIsInstance<RailIntervalResource>()
            .groupBy(RailIntervalResource::railId)
            .mapValues { (railId, resources) ->
                resources.sortedBy(RailIntervalResource::startAbMeters).also { sorted ->
                    check(sorted.zipWithNext().all { (first, second) ->
                        first.endAbMeters < second.startAbMeters
                    }) {
                        "release index requires disjoint claim intervals on rail $railId"
                    }
                }
            }
        railResourcesByRail.values.flatten().forEach { resource ->
            evidence.getOrPut(resource, ::MutableReleaseEvidence).intervals += resource.toRailInterval()
        }
        occurrences.forEach { occurrence ->
            val clippedStart = max(entryRouteMeters, occurrence.routeStartMeters)
            val clippedEnd = min(clearUntilRouteMeters, occurrence.routeEndMeters)
            if (clippedEnd <= clippedStart) return@forEach
            val firstCanonical = occurrence.canonicalAt(clippedStart)
            val secondCanonical = occurrence.canonicalAt(clippedEnd)
            val occurrenceStart = min(firstCanonical, secondCanonical)
            val occurrenceEnd = max(firstCanonical, secondCanonical)
            val railResources = railResourcesByRail[occurrence.segment.railId] ?: return@forEach

            // Resources on one rail are disjoint and ordered. Binary search skips the prefix that
            // ends before this occurrence, then only actual overlap candidates are inspected.
            var resourceIndex = railResources.lowerBoundByEnd(occurrenceStart)
            while (resourceIndex < railResources.size) {
                val resource = railResources[resourceIndex]
                if (resource.startAbMeters > occurrenceEnd) break
                val overlapStart = max(occurrenceStart, resource.startAbMeters)
                val overlapEnd = min(occurrenceEnd, resource.endAbMeters)
                if (overlapEnd >= overlapStart) {
                    val firstRoute = occurrence.routeAtCanonical(overlapStart)
                    val secondRoute = occurrence.routeAtCanonical(overlapEnd)
                    evidence.getValue(resource).recordPassage(
                        min(firstRoute, secondRoute),
                        max(firstRoute, secondRoute)
                    )
                }
                resourceIndex++
            }
        }

        turnouts.forEach { traversal ->
            val accumulator = evidence.getOrPut(traversal.resource, ::MutableReleaseEvidence)
            turnoutOccupancyIntervals(topology, listOf(traversal)).forEach { interval ->
                accumulator.record(interval, traversal.routeDistanceMeters, traversal.routeDistanceMeters)
            }
        }
        routeBoundHits.forEach { hit ->
            val accumulator = evidence.getOrPut(hit.resource, ::MutableReleaseEvidence)
            hit.occupancyAnchors.forEach { anchor ->
                accumulator.record(
                    RailInterval(anchor.railId, anchor.startAbMeters, anchor.endAbMeters),
                    hit.routeStartMeters,
                    hit.routeEndMeters
                )
            }
        }

        return footprint.resources.map { resource ->
            val resolved = checkNotNull(evidence[resource]) {
                "claimed resource has no release evidence: $resource"
            }
            check(resolved.hasPassage) {
                "claimed resource has no validated route occurrence: $resource"
            }
            ResolvedReleaseSlice.create(
                resources = listOf(resource),
                occupancyGeometry = OccupancyQueryGeometry.create(resolved.intervals),
                observeFromRouteMeters = resolved.observeFromRouteMeters,
                releaseAfterRouteMeters = resolved.releaseAfterRouteMeters
            )
        }.sortedWith(
            compareBy<ResolvedReleaseSlice>(ResolvedReleaseSlice::releaseAfterRouteMeters)
                .thenBy(ResolvedReleaseSlice::observeFromRouteMeters)
                .thenBy { it.resources.single().toString() }
        )
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
                railId = occurrence.segment.railId,
                startAbMeters = min(first, second).coerceIn(0.0, occurrence.railLengthMeters),
                endAbMeters = max(first, second).coerceIn(0.0, occurrence.railLengthMeters)
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
        resource = resource,
        fromPort = fromPort,
        toPort = toPort,
        routeDistanceMeters = routeDistanceMeters
    )

    /**
     * A Core turnout is a zero-length selectable continuation between rail endpoints, not a
     * separately occupied piece of track. Querying every incident endpoint catches a train spanning
     * the selectable node from an unselected branch without inventing a legacy locking distance.
     */
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
            val endpointMeters = when (port.endpoint) {
                RailEndpoint.A -> 0.0
                RailEndpoint.B -> railLength
            }
            RailInterval(port.railId, endpointMeters, endpointMeters)
        }
    }

    private fun RailIntervalResource.toRailInterval() = RailInterval(
        railId,
        startAbMeters,
        endAbMeters
    )

    private fun List<RailIntervalResource>.lowerBoundByEnd(canonicalMeters: Double): Int {
        var low = 0
        var high = size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (this[middle].endAbMeters < canonicalMeters) {
                low = middle + 1
            } else {
                high = middle
            }
        }
        return low
    }

    private class MutableReleaseEvidence {
        val intervals = ArrayList<RailInterval>()
        var observeFromRouteMeters: Double = Double.NaN
            private set
        var releaseAfterRouteMeters: Double = Double.NaN
            private set
        val hasPassage: Boolean
            get() = releaseAfterRouteMeters.isFinite()

        fun record(interval: RailInterval, observeFrom: Double, releaseAfter: Double) {
            intervals += interval
            recordPassage(observeFrom, releaseAfter)
        }

        fun recordPassage(observeFrom: Double, releaseAfter: Double) {
            if (releaseAfterRouteMeters.isNaN() || releaseAfter > releaseAfterRouteMeters ||
                releaseAfter == releaseAfterRouteMeters && observeFrom > observeFromRouteMeters
            ) {
                observeFromRouteMeters = observeFrom
                releaseAfterRouteMeters = releaseAfter
            }
        }
    }

    private fun rejectedProtection(detail: String) = ProtectedRouteValidation.Rejected(
        ProtectedRouteClaimStatus.PROTECTION_REQUIRED,
        detail
    )

    private fun rejectedStale(detail: String) = ProtectedRouteValidation.Rejected(
        ProtectedRouteClaimStatus.STALE_TOPOLOGY,
        detail
    )
}

internal sealed interface ProtectedRouteValidation {
    data object NotRequired : ProtectedRouteValidation

    data class Validated(val admission: CompiledRouteAdmission) : ProtectedRouteValidation

    data class Rejected(
        val status: ProtectedRouteClaimStatus,
        val detail: String
    ) : ProtectedRouteValidation {
        init {
            require(status in VALIDATION_REJECTION_STATUSES) {
                "route validation rejection must use a validation failure status"
            }
            require(detail.isNotBlank())
        }
    }

    private companion object {
        val VALIDATION_REJECTION_STATUSES = setOf(
            ProtectedRouteClaimStatus.PROTECTION_REQUIRED,
            ProtectedRouteClaimStatus.STALE_TOPOLOGY,
            ProtectedRouteClaimStatus.INVALID_REQUEST
        )
    }
}
