package work.art1st.javarailways.simpleato.api.station

import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.core.api.util.annotation.MainThread
import java.util.UUID

/** Detached marker values; no live RailLocation or mutable station store is exposed. */
data class StationCallBerthSnapshot(
    val berthRef: StationBerthRef,
    val railId: UUID,
    val arcLengthInAbMeters: Double,
    val approachDirection: ViewDirection?
)

/** Host-validated route and defaults for one candidate. Unknown static-validation length stays null. */
data class StationCallResolutionContext(
    val defaultResolution: ResolvedStationCall,
    val berth: StationCallBerthSnapshot,
    val trainLengthMeters: Double?,
    val requiredStartDirection: ViewDirection?,
    val topology: TopologyState
) {
    val request: StationCallRequest get() = defaultResolution.request
}

/**
 * World-local stopping policy, invoked under the world's owner after canonical route validation.
 * May reject a candidate or customize stopping parameters. It must not claim resources, mutate the
 * world, replace the request/route or stopping offset, widen the authored tolerance, or weaken the usable-length limit. Shared platform
 * exclusion belongs to route-resource admission, not to this callback.
 */
fun interface StationCallResolutionProvider {
    @MainThread
    fun resolve(context: StationCallResolutionContext): StationCallResolutionResult
}

/** Optional capture capability, invoked at the source world's stable owner boundary. */
fun interface StationCallResolutionSimulationFactory {
    fun prepareSimulation(): StationCallResolutionSimulationPreparation
}

/** Detached definitions only; create a fresh provider for every isolated target, never a live one. */
fun interface StationCallResolutionSimulationPreparation {
    fun create(world: World): StationCallResolutionProvider
}

/** Reusable captured definitions. Each target receives its own registry and provider. */
class StationCallSimulationExtensions internal constructor(
    private val preparation: StationCallResolutionSimulationPreparation?
) {
    fun create(world: World): StationCallResolverRegistry = StationCallResolverRegistry().also { registry ->
        preparation?.let { registry.register(it.create(world)) }
    }
}

/**
 * One policy per world. Registration and resolution belong to the world's owner.
 * Retiring a policy leaves an unavailable marker: existing timetables must never silently use
 * default semantics after plugin disable. Explicit registration can install a replacement.
 */
class StationCallResolverRegistry {
    private class Registration(val provider: StationCallResolutionProvider)
    private var registration: Registration? = null
    private var retired = false
    private var resolving = false

    @MainThread
    fun register(provider: StationCallResolutionProvider): AutoCloseable {
        check(!resolving) { "cannot replace station call resolver during resolution" }
        check(registration == null) { "station call resolver already registered" }
        val entry = Registration(provider)
        registration = entry
        retired = false
        return AutoCloseable {
            check(!resolving) { "cannot retire station call resolver during resolution" }
            if (registration === entry) {
                registration = null
                retired = true
            }
        }
    }

    @MainThread
    fun prepareSimulation(): StationCallSimulationExtensions {
        check(!retired) { "station call resolver is unavailable" }
        val provider = registration?.provider ?: return StationCallSimulationExtensions(null)
        require(provider is StationCallResolutionSimulationFactory) {
            "simulation preparation unavailable for station call resolver"
        }
        check(!resolving) { "cannot prepare station call resolver during a callback" }
        val preparation = try {
            resolving = true
            provider.prepareSimulation()
        } finally {
            resolving = false
        }
        return StationCallSimulationExtensions(preparation)
    }

    @MainThread
    internal fun resolve(context: StationCallResolutionContext): StationCallResolutionResult {
        if (retired) return failure(StationCallResolutionFailure.RESOLVER_UNAVAILABLE,
            "station call resolver was retired; register a replacement before resolving calls")
        val entry = registration ?: return StationCallResolutionResult.Success(context.defaultResolution)
        check(!resolving) { "station call resolution cannot recurse" }
        val result: StationCallResolutionResult? = try {
            resolving = true
            entry.provider.resolve(context)
        } catch (error: Exception) {
            return failure(StationCallResolutionFailure.RESOLVER_ERROR,
                "station call resolver failed: ${error.message ?: error.javaClass.simpleName}")
        } finally {
            resolving = false
        }
        if (registration !== entry) return failure(StationCallResolutionFailure.RESOLVER_UNAVAILABLE,
            "station call resolver changed during resolution")
        if (result == null) return invalid("station call resolver returned null")
        if (result is StationCallResolutionResult.Failure) return result
        val resolved = (result as StationCallResolutionResult.Success).resolved
        val defaults = context.defaultResolution
        if (resolved.request != defaults.request || resolved.plannedRoute !== defaults.plannedRoute) {
            return invalid("custom resolution must preserve the host request and validated route")
        }
        if (resolved.stoppingOffsetMetersBefore != defaults.stoppingOffsetMetersBefore) {
            return invalid("custom resolution cannot move the authored stopping position")
        }
        val usable = resolved.usableLengthMeters
        if (defaults.usableLengthMeters?.let { usable == null || usable > it } == true) {
            return invalid("custom resolution cannot weaken the berth usable length")
        }
        if (usable != null && context.trainLengthMeters?.let { it > usable } == true) {
            return failure(StationCallResolutionFailure.TRAIN_TOO_LONG,
                "train length ${context.trainLengthMeters} exceeds custom usable length $usable")
        }
        if (resolved.stoppingOffsetMetersBefore > resolved.plannedRoute.expandedRoute.totalLength) {
            return invalid("custom stopping position lies before the validated route")
        }
        return result
    }

    private fun invalid(message: String) = failure(StationCallResolutionFailure.RESOLVER_INVALID_RESULT, message)
    private fun failure(reason: StationCallResolutionFailure, message: String) =
        StationCallResolutionResult.Failure(reason, message)
}
