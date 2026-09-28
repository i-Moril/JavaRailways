package work.art1st.javarailways.simplesignals.region

import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal sealed interface RegionRouteMatch {
    data object NotTraversed : RegionRouteMatch
    data class Matched(val window: ProtectionRegionRouteWindow) : RegionRouteMatch
    data class Rejected(val diagnostic: ProtectionRegionDiagnostic) : RegionRouteMatch
}

/**
 * Matches an already selected frozen route to one resolved region.
 *
 * This code performs no path search and does not try to duplicate Basic's structural route
 * validation. It only converts the route's facing-local segment coordinates through the captured
 * topology rail lengths, intersects them with the derived interior, and identifies one entry and
 * one exit boundary. Basic independently validates the same route before any resource claim.
 */
internal object ProtectionRegionRouteMatcher {
    fun matchIndexed(
        route: RouteSnapshot,
        topology: ProtectionRegionTopology,
        regionsById: Map<String, ResolvedProtectionRegion>,
        regionIdsByRail: Map<java.util.UUID, Set<String>>
    ): ProtectionRegionRouteMatchResult {
        val occurrences = when (val projected = project(route, topology)) {
            is RouteProjection.Projected -> projected.occurrences
            is RouteProjection.Rejected -> return ProtectionRegionRouteMatchResult.Rejected(
                listOf(projected.diagnostic)
            )
        }
        val occurrencesByRegion = linkedMapOf<String, MutableList<RouteOccurrence>>()
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
                is RegionRouteMatch.Matched -> windows += result.window
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

    private fun project(
        route: RouteSnapshot,
        topology: ProtectionRegionTopology
    ): RouteProjection {
        if (route.topologyRevision != topology.topologyRevision) {
            return RouteProjection.Rejected(
                ProtectionRegionDiagnostic(
                    ProtectionRegionValidationCode.STALE_TOPOLOGY,
                    "route topology revision ${route.topologyRevision} does not match " +
                        topology.topologyRevision
                )
            )
        }
        val occurrences = ArrayList<RouteOccurrence>(route.segments.size)
        var routeStart = 0.0
        for (segment in route.segments) {
            val rail = topology.rails[segment.railId]
                ?: return RouteProjection.Rejected(
                    ProtectionRegionDiagnostic(
                        ProtectionRegionValidationCode.INVALID_ROUTE,
                        "route references missing rail ${segment.railId}",
                        railId = segment.railId
                    )
                )
            if (segment.endArcLengthMeters > rail.lengthMeters + Curve.TOLERANCE) {
                return RouteProjection.Rejected(
                    ProtectionRegionDiagnostic(
                        ProtectionRegionValidationCode.INVALID_ROUTE,
                        "route segment exceeds rail ${segment.railId}",
                        railId = segment.railId
                    )
                )
            }
            val localStart = segment.startArcLengthMeters.coerceIn(0.0, rail.lengthMeters)
            val localEnd = segment.endArcLengthMeters.coerceIn(0.0, rail.lengthMeters)
            val canonicalStart = when (segment.facing) {
                ViewDirection.AB -> localStart
                ViewDirection.BA -> rail.lengthMeters - localStart
            }
            val canonicalEnd = when (segment.facing) {
                ViewDirection.AB -> localEnd
                ViewDirection.BA -> rail.lengthMeters - localEnd
            }
            occurrences += RouteOccurrence(
                railId = segment.railId,
                facing = segment.facing,
                canonicalStartAbMeters = canonicalStart,
                canonicalEndAbMeters = canonicalEnd,
                routeStartMeters = routeStart,
                routeEndMeters = routeStart + segment.lengthMeters
            )
            routeStart += segment.lengthMeters
        }
        return RouteProjection.Projected(java.util.List.copyOf(occurrences))
    }

    private fun match(
        region: ResolvedProtectionRegion,
        occurrences: List<RouteOccurrence>
    ): RegionRouteMatch {
        val intervals = interiorIntervals(region, occurrences)
        if (intervals.isEmpty()) return RegionRouteMatch.NotTraversed
        if (intervals.size != 1) {
            return rejected(
                region,
                ProtectionRegionValidationCode.AMBIGUOUS_TRAVERSAL,
                "route traverses region ${region.definition.regionId} ${intervals.size} times"
            )
        }
        val interval = intervals.single()
        val entry = boundaryAt(region, occurrences, interval.startMeters, entering = true)
            ?: return rejected(
                region,
                ProtectionRegionValidationCode.INVALID_ROUTE,
                "route enters region ${region.definition.regionId} without one matching authored boundary"
            )
        val exit = boundaryAt(region, occurrences, interval.endMeters, entering = false)
            ?: return rejected(
                region,
                ProtectionRegionValidationCode.INVALID_ROUTE,
                "route exits region ${region.definition.regionId} without one matching authored boundary"
            )
        return RegionRouteMatch.Matched(
            ProtectionRegionRouteWindow(
                regionId = region.definition.regionId,
                regionRevision = region.definition.revision,
                entry = entry,
                exit = exit
            )
        )
    }

    private fun boundaryAt(
        region: ResolvedProtectionRegion,
        occurrences: List<RouteOccurrence>,
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
        occurrences: List<RouteOccurrence>
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
                        occurrence.routeStartMeters + overlapLow - occurrence.canonicalStartAbMeters to
                            occurrence.routeStartMeters + overlapHigh - occurrence.canonicalStartAbMeters
                    ViewDirection.BA ->
                        occurrence.routeStartMeters + occurrence.canonicalStartAbMeters - overlapHigh to
                            occurrence.routeStartMeters + occurrence.canonicalStartAbMeters - overlapLow
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

    private fun RouteOccurrence.canonicalAt(routeDistanceMeters: Double): Double {
        val localOffset = (routeDistanceMeters - routeStartMeters)
            .coerceIn(0.0, routeEndMeters - routeStartMeters)
        return when (facing) {
            ViewDirection.AB -> canonicalStartAbMeters + localOffset
            ViewDirection.BA -> canonicalStartAbMeters - localOffset
        }
    }

    private fun ViewDirection.opposite(): ViewDirection = when (this) {
        ViewDirection.AB -> ViewDirection.BA
        ViewDirection.BA -> ViewDirection.AB
    }

    private fun samePosition(first: Double, second: Double): Boolean =
        abs(first - second) <= Curve.TOLERANCE

    private data class RouteOccurrence(
        val railId: java.util.UUID,
        val facing: ViewDirection,
        val canonicalStartAbMeters: Double,
        val canonicalEndAbMeters: Double,
        val routeStartMeters: Double,
        val routeEndMeters: Double
    )

    private data class RouteDistanceInterval(val startMeters: Double, val endMeters: Double)

    private sealed interface RouteProjection {
        data class Projected(val occurrences: List<RouteOccurrence>) : RouteProjection
        data class Rejected(val diagnostic: ProtectionRegionDiagnostic) : RouteProjection
    }
}
