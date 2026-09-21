package work.art1st.javarailways.basic.infrastructure

import work.art1st.javarailways.basic.api.infrastructure.InfrastructureResource
import work.art1st.javarailways.core.api.physics.rail.IntervalOccupancyState
import work.art1st.javarailways.core.api.physics.rail.RailInterval
import work.art1st.javarailways.core.api.physics.rail.RailIntervalReleaseDetector

/**
 * One route-ordered part of a claim that may be released after its train has passed it.
 *
 * [resources] are members of the admission's single claim footprint. [occupancyGeometry] is
 * authoritative physical evidence for those members, not a second footprint. The two route gates
 * identify the final occurrence when a frozen route visits the same physical rail/resource more
 * than once: occupancy before [observeFromRouteMeters] cannot arm this slice, and release cannot be
 * reported before [releaseAfterRouteMeters].
 */
internal class ResolvedReleaseSlice private constructor(
    val resources: List<InfrastructureResource>,
    val occupancyGeometry: OccupancyQueryGeometry,
    val observeFromRouteMeters: Double,
    val releaseAfterRouteMeters: Double
) {
    companion object {
        fun create(
            resources: Collection<InfrastructureResource>,
            occupancyGeometry: OccupancyQueryGeometry,
            observeFromRouteMeters: Double,
            releaseAfterRouteMeters: Double
        ): ResolvedReleaseSlice {
            val immutableResources = java.util.List.copyOf(resources)
            require(immutableResources.isNotEmpty()) { "release slice must contain a claimed resource" }
            require(immutableResources.distinct().size == immutableResources.size) {
                "release slice contains duplicate resources"
            }
            require(occupancyGeometry.intervals.isNotEmpty()) {
                "release slice must contain authoritative occupancy geometry"
            }
            require(observeFromRouteMeters.isFinite() && observeFromRouteMeters >= 0.0) {
                "release observation gate must be finite and non-negative"
            }
            require(releaseAfterRouteMeters.isFinite() &&
                releaseAfterRouteMeters >= observeFromRouteMeters
            ) {
                "release completion gate must not precede the observation gate"
            }
            return ResolvedReleaseSlice(
                immutableResources,
                occupancyGeometry,
                observeFromRouteMeters,
                releaseAfterRouteMeters
            )
        }
    }
}

internal sealed interface RouteReleaseTrackingResult {
    /** Only resources in the newly cleared contiguous prefix, in release-plan order. */
    data class Advanced(val newlyClearedResources: List<InfrastructureResource>) :
        RouteReleaseTrackingResult

    /** Invalid progress permanently fails this tracker closed; no resource is reported released. */
    data class Rejected(val detail: String) : RouteReleaseTrackingResult {
        init {
            require(detail.isNotBlank())
        }
    }
}

/**
 * Owner-confined pure tracker for one compiled release plan.
 *
 * It reads only caller-supplied immutable route progress and physical train intervals. Later slices
 * may observe and clear internally, but they are never returned before every earlier slice clears.
 */
internal class CompiledRouteReleaseTracker(releaseSlices: Collection<ResolvedReleaseSlice>) {
    private val slices = java.util.List.copyOf(releaseSlices).map(::TrackedSlice)
    private var lastProgressMeters: Double? = null
    private var releasedPrefixSize = 0
    private var rejectedDetail: String? = null

    init {
        require(slices.isNotEmpty()) { "release tracker requires at least one slice" }
    }

    fun update(
        routeProgressMeters: Double,
        trainIntervals: Collection<RailInterval>
    ): RouteReleaseTrackingResult {
        rejectedDetail?.let { return RouteReleaseTrackingResult.Rejected(it) }
        val previous = lastProgressMeters
        if (!routeProgressMeters.isFinite() || routeProgressMeters < 0.0 ||
            previous != null && routeProgressMeters < previous
        ) {
            val detail = "route progress must be finite, non-negative, and monotonic: " +
                "previous=$previous, current=$routeProgressMeters"
            rejectedDetail = detail
            return RouteReleaseTrackingResult.Rejected(detail)
        }

        val immutableTrainIntervals = java.util.List.copyOf(trainIntervals)
        lastProgressMeters = routeProgressMeters
        slices.forEach { tracked ->
            if (routeProgressMeters >= tracked.slice.observeFromRouteMeters) {
                tracked.detector.update(immutableTrainIntervals)
            }
        }

        val newlyCleared = ArrayList<InfrastructureResource>()
        while (releasedPrefixSize < slices.size) {
            val next = slices[releasedPrefixSize]
            if (routeProgressMeters < next.slice.releaseAfterRouteMeters ||
                next.detector.state != IntervalOccupancyState.RELEASED
            ) {
                break
            }
            newlyCleared += next.slice.resources
            releasedPrefixSize++
        }
        return RouteReleaseTrackingResult.Advanced(java.util.List.copyOf(newlyCleared))
    }

    private class TrackedSlice(val slice: ResolvedReleaseSlice) {
        val detector = RailIntervalReleaseDetector(slice.occupancyGeometry.intervals)
    }
}
