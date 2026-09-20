package work.art1st.javarailways.basic.infrastructure

import work.art1st.javarailways.basic.api.infrastructure.InfrastructureFootprint
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureResource
import work.art1st.javarailways.basic.api.infrastructure.RailIntervalResource
import work.art1st.javarailways.basic.api.infrastructure.StableInfrastructureResource
import work.art1st.javarailways.basic.api.route.ProtectedRouteClaimStatus
import work.art1st.javarailways.basic.api.route.ProtectedRouteRequest
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/** Validates one frozen route/window and returns the single resource set that may be claimed. */
internal class ProtectedRouteRequestValidator(
    private val catalog: ProtectionRegionCatalog
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
        if (window == null) {
            return if (resolved.projection.turnoutTraversals.isEmpty()) {
                ProtectedRouteValidation.NotRequired
            } else {
                rejectedProtection("route contains a turnout but no protection window")
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

        val turnoutResources = relevantTurnouts.map(ProjectedTurnoutTraversal::resource)
        if (turnoutResources.distinct().size != turnoutResources.size) {
            return ProtectedRouteValidation.Rejected(
                ProtectedRouteClaimStatus.INVALID_REQUEST,
                "protected route traverses the same turnout resource more than once"
            )
        }
        val resources = ArrayList<InfrastructureResource>()
        resources += routeIntervals(resolved.projection.occurrences, entry, clearUntil)
        resources += turnoutResources.sortedWith(
            compareBy(StableInfrastructureResource::typeId, StableInfrastructureResource::resourceId)
        )
        return ProtectedRouteValidation.Validated(InfrastructureFootprint(resources))
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

    private fun rejectedProtection(detail: String) = ProtectedRouteValidation.Rejected(
        ProtectedRouteClaimStatus.PROTECTION_REQUIRED,
        detail
    )
}

internal sealed interface ProtectedRouteValidation {
    data object NotRequired : ProtectedRouteValidation

    data class Validated(val resources: InfrastructureFootprint) : ProtectedRouteValidation

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
