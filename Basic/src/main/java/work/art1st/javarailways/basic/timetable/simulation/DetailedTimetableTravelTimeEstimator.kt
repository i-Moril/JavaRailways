package work.art1st.javarailways.basic.timetable.simulation

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

/** Resolves one immutable route segment; implementations may return multiple physical legs. */
fun interface DetailedTimetableRouteResolver {
    fun resolve(originId: String, targetId: String): List<DetailedTravelLeg>
}

/**
 * Adapts the shared detailed dynamics simulator to timetable virtual time and memoizes immutable
 * route/profile combinations. The cache stores logical ticks, never wall-clock durations.
 */
class DetailedTimetableTravelTimeEstimator(
    private val routeResolver: DetailedTimetableRouteResolver,
    profiles: Map<TimetableSimulationProfileId, DetailedTrainSimulationProfile>,
    private val maximumCacheEntries: Int = 1_024
) : TimetableTravelTimeEstimator {
    private val profiles = profiles.toMap()
    private val cache = ConcurrentHashMap<CacheKey, Long>()
    private val cacheWriteLock = Any()

    init { require(maximumCacheEntries >= 0) }

    override fun estimateTicks(request: TimetableMovementEstimateRequest): Long {
        val origin = request.origin
            ?: throw IllegalArgumentException("service ${request.serviceId} has no origin before step#${request.stepIndex}")
        require(request.targets.isNotEmpty()) {
            "service ${request.serviceId} has no movement target at step#${request.stepIndex}"
        }
        val key = CacheKey(request.profileId, origin, request.targets.toList())
        cache[key]?.let { return it }
        val estimate = estimateUncached(key)
        if (maximumCacheEntries == 0) return estimate
        synchronized(cacheWriteLock) {
            cache[key]?.let { return it }
            if (cache.size < maximumCacheEntries) cache[key] = estimate
        }
        return estimate
    }

    fun clearCache() = cache.clear()
    fun cachedEstimateCount(): Int = cache.size

    private fun estimateUncached(key: CacheKey): Long {
        val profile = profiles[key.profileId]
            ?: throw IllegalArgumentException("unknown simulation profile ${key.profileId.value}")
        var from = key.origin
        val legs = key.targets.flatMap { target ->
            routeResolver.resolve(from, target).also {
                require(it.isNotEmpty()) { "no simulation route from $from to $target" }
                from = target
            }
        }
        val seconds = DetailedTrainTravelSimulator.simulate(legs, profile).totalTravelSeconds
        return ceil(seconds * LOGICAL_TICKS_PER_SECOND).toLong()
    }

    private data class CacheKey(
        val profileId: TimetableSimulationProfileId,
        val origin: String,
        val targets: List<String>
    )

    companion object { private const val LOGICAL_TICKS_PER_SECOND = 20.0 }
}
