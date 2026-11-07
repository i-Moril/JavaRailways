package work.art1st.javarailways.simplesignals.region

import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.simplesignals.route.DirectedRouteOccurrence
import work.art1st.javarailways.simplesignals.route.DirectedRouteProjectionFailureCode
import work.art1st.javarailways.simplesignals.route.DirectedRouteProjectionResult
import work.art1st.javarailways.simplesignals.route.DirectedRouteProjector
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal sealed interface RegionRouteMatch {
    data object NotTraversed : RegionRouteMatch
    data class Matched(val windows: List<ProtectionRegionRouteWindow>) : RegionRouteMatch
    data class Rejected(val diagnostic: ProtectionRegionDiagnostic) : RegionRouteMatch
}

/**
 * Matches an already selected frozen route to one resolved region.
 *
 * This code performs no path search and does not try to duplicate Basic's structural route
 * validation. It only converts the route's facing-local segment coordinates through the captured
 * topology rail lengths, intersects them with the derived interior, and identifies each complete
 * entry/exit visit. Basic independently validates the same route before any resource claim.
 */
internal object ProtectionRegionRouteMatcher {
    fun matchIndexed(
        route: RouteSnapshot,
        topology: ProtectionRegionTopology,
        regionsById: Map<String, ResolvedProtectionRegion>,
        regionIdsByRail: Map<java.util.UUID, Set<String>>
    ): ProtectionRegionRouteMatchResult {
        val occurrences = when (val projected = DirectedRouteProjector.project(route, topology)) {
            is DirectedRouteProjectionResult.Projected -> projected.route.occurrences
            is DirectedRouteProjectionResult.Rejected -> return ProtectionRegionRouteMatchResult.Rejected(
                listOf(
                    ProtectionRegionDiagnostic(
                        when (projected.failure.code) {
                            DirectedRouteProjectionFailureCode.STALE_TOPOLOGY ->
                                ProtectionRegionValidationCode.STALE_TOPOLOGY
                            DirectedRouteProjectionFailureCode.INVALID_ROUTE ->
                                ProtectionRegionValidationCode.INVALID_ROUTE
                        },
                        projected.failure.detail,
                        railId = projected.failure.railId
                    )
                )
            )
        }
        val occurrencesByRegion = linkedMapOf<String, MutableList<DirectedRouteOccurrence>>()
        for (occurrence in occurrences) {
            for (regionId in regionIdsByRail[occurrence.railId].orEmpty()) {
                occurrencesByRegion.getOrPut(regionId, ::mutableListOf) += occurrence
            }
        }
        val windows = ArrayList<ProtectionRegionRouteWindow>()
        val diagnostics = ArrayList<ProtectionRegionDiagnostic>()
        for ((regionId, relevantOccurrences) in occurrencesByRegion) {
            val region = regionsById.getValue(regionId)
            when (val result = match(region, relevantOccurrences)) {
                is RegionRouteMatch.Matched -> windows += result.windows
                RegionRouteMatch.NotTraversed -> Unit
                is RegionRouteMatch.Rejected -> diagnostics += result.diagnostic
            }
        }
        if (diagnostics.isNotEmpty()) {
            return ProtectionRegionRouteMatchResult.Rejected(java.util.List.copyOf(diagnostics))
        }
        return ProtectionRegionRouteMatchResult.Matched(
            java.util.List.copyOf(windows.sortedBy { it.entry.routeDistanceMeters })
        )
    }

    private fun match(
        region: ResolvedProtectionRegion,
        occurrences: List<DirectedRouteOccurrence>
    ): RegionRouteMatch {
        val intervals = interiorIntervals(region, occurrences)
        if (intervals.isEmpty()) return RegionRouteMatch.NotTraversed
        val windows = ArrayList<ProtectionRegionRouteWindow>(intervals.size)
        for (interval in intervals) {
            val entry = boundaryAt(region, occurrences, interval.startMeters, entering = true)
                ?: return rejected(region, ProtectionRegionValidationCode.INVALID_ROUTE,
                    "route enters region ${region.definition.regionId} without one matching authored boundary")
            val exit = boundaryAt(region, occurrences, interval.endMeters, entering = false)
                ?: return rejected(region, ProtectionRegionValidationCode.INVALID_ROUTE,
                    "route exits region ${region.definition.regionId} without one matching authored boundary")
            windows += ProtectionRegionRouteWindow(
                regionId = region.definition.regionId,
                regionRevision = region.definition.revision,
                entry = entry,
                exit = exit
            )
        }
        return RegionRouteMatch.Matched(java.util.List.copyOf(windows))
    }

    private fun boundaryAt(
        region: ResolvedProtectionRegion,
        occurrences: List<DirectedRouteOccurrence>,
        routeDistanceMeters: Double,
        entering: Boolean
    ): ProtectionRouteBoundary? {
        val matches = ArrayList<ProtectionRouteBoundary>()
        for (occurrence in occurrences) {
            if (routeDistanceMeters < occurrence.routeStartMeters - Curve.TOLERANCE ||
                routeDistanceMeters > occurrence.routeEndMeters + Curve.TOLERANCE
            ) continue
            val canonical = occurrence.canonicalAt(routeDistanceMeters)
            val expectedFacing = if (entering) occurrence.facing else occurrence.facing.opposite()
            for (boundary in region.definition.boundaries) {
                if (boundary.position.railId == occurrence.railId &&
                    boundary.insideFacing == expectedFacing &&
                    samePosition(boundary.position.arcLengthInAbMeters, canonical)
                ) {
                    matches += ProtectionRouteBoundary(
                        boundary.boundaryId,
                        RailPositionSnapshot(occurrence.railId, occurrence.facing, canonical),
                        routeDistanceMeters
                    )
                }
            }
        }
        return matches.distinctBy(ProtectionRouteBoundary::boundaryId).singleOrNull()
    }

    private fun interiorIntervals(
        region: ResolvedProtectionRegion,
        occurrences: List<DirectedRouteOccurrence>
    ): List<RouteDistanceInterval> {
        val interiorByRail = region.interior.groupBy(ProtectionRegionInteriorSlice::railId)
        val intersections = ArrayList<RouteDistanceInterval>()
        for (occurrence in occurrences) {
            val routeLow = min(occurrence.canonicalStartAbMeters, occurrence.canonicalEndAbMeters)
            val routeHigh = max(occurrence.canonicalStartAbMeters, occurrence.canonicalEndAbMeters)
            for (interior in interiorByRail[occurrence.railId].orEmpty()) {
                val overlapLow = max(routeLow, interior.startAbMeters)
                val overlapHigh = min(routeHigh, interior.endAbMeters)
                if (overlapHigh - overlapLow <= Curve.TOLERANCE) continue
                val (start, end) = when (occurrence.facing) {
                    ViewDirection.AB ->
                        occurrence.routeDistanceAt(overlapLow) to occurrence.routeDistanceAt(overlapHigh)
                    ViewDirection.BA ->
                        occurrence.routeDistanceAt(overlapHigh) to occurrence.routeDistanceAt(overlapLow)
                }
                intersections += RouteDistanceInterval(start, end)
            }
        }
        if (intersections.isEmpty()) return emptyList()
        val merged = ArrayList<RouteDistanceInterval>()
        for (interval in intersections.sortedBy(RouteDistanceInterval::startMeters)) {
            val previous = merged.lastOrNull()
            if (previous != null && interval.startMeters <= previous.endMeters + Curve.TOLERANCE) {
                merged[merged.lastIndex] = RouteDistanceInterval(
                    previous.startMeters,
                    max(previous.endMeters, interval.endMeters)
                )
            } else {
                merged += interval
            }
        }
        return merged
    }

    private fun rejected(
        region: ResolvedProtectionRegion,
        code: ProtectionRegionValidationCode,
        detail: String
    ) = RegionRouteMatch.Rejected(
        ProtectionRegionDiagnostic(code, detail, regionId = region.definition.regionId)
    )

    private fun ViewDirection.opposite(): ViewDirection = when (this) {
        ViewDirection.AB -> ViewDirection.BA
        ViewDirection.BA -> ViewDirection.AB
    }

    private fun samePosition(first: Double, second: Double): Boolean =
        abs(first - second) <= Curve.TOLERANCE

    private data class RouteDistanceInterval(val startMeters: Double, val endMeters: Double)
}
