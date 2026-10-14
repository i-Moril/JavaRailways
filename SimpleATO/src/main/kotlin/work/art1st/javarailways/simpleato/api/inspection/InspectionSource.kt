package work.art1st.javarailways.simpleato.api.inspection

import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.infrastructure.StableInfrastructureResource
import work.art1st.javarailways.basic.api.observation.RailwayStateStamp
import work.art1st.javarailways.simpleato.api.NAMESPACED_SIMPLE_ATO_ID

/**
 * Host-selected route context used to compile one complete train inspection binding.
 *
 * The host invokes the provider only after SimpleATO has selected the exact production frozen
 * route. Plugins therefore do not discover routes through mutable trains or reconstruct them from
 * observations. [route] and [observationStamp] are coherent and belong to [trainId]. The source
 * resolves its own immutable, low-frequency train capability profile by [trainId]; the host does
 * not expose a live train or current speed through this compilation input.
 */
data class InspectionBindingInput(
    val observationStamp: RailwayStateStamp,
    val trainId: String,
    val route: RouteSnapshot
) {
    init {
        require(trainId.isNotBlank()) { "trainId must not be blank" }
    }
}

/**
 * Compiles a complete immutable binding for the exact route supplied by the host.
 *
 * The callback runs synchronously on the serialized railway owner. It must be bounded,
 * non-blocking, side-effect free and must not call back into the Bukkit source service.
 */
fun interface InspectionBindingProvider {
    fun bind(input: InspectionBindingInput): InspectionDeclarationBinding
}

/**
 * Collects exact registered runtime resource identities referenced by a selected timetable route.
 *
 * This pure, bounded callback reads an immutable definition/index publication and verifies its
 * topology binding. It neither requires a live train nor inspects occupancy, signal aspects or
 * claims. Returned identities are non-exclusive edit dependencies, not route resource requests.
 */
fun interface InspectionRouteDependencyProvider {
    fun dependencies(route: RouteSnapshot): Collection<StableInfrastructureResource>
}

/**
 * Supplies the physical continuation required after an operational endpoint, not movement authority.
 *
 * Called once for an admission candidate (and during static timetable preparation), against the
 * original selected route. Implementations read immutable geometry, validate its topology binding,
 * and return directed segments starting exactly at its endpoint. No live train, claim or signal
 * aspect is involved. The host reconciles sources; Basic independently validates the complete path.
 */
fun interface InspectionRouteContinuationProvider {
    /** null declines ownership; empty explicitly owns the terminal without additional geometry. */
    fun continuation(route: RouteSnapshot, proposedMovingRoute: RouteSnapshot): Collection<RouteSegmentSnapshot>?
}

/**
 * One independently implemented inspection source.
 *
 * [bindingProvider] is called only for a new train/route binding or an atomic source replacement;
 * current speed is intentionally absent from its input. [updateHandler] receives one baseline call
 * for each new binding, then runs only when the complete active declaration set changes or one of
 * those declarations is invalidated. Every call receives that complete set plus the controller's
 * existing perception snapshot.
 */
data class InspectionSource(
    val sourceId: String,
    val bindingProvider: InspectionBindingProvider,
    val updateHandler: InspectionUpdateHandler,
    val routeInfrastructureDependencies: InspectionRouteDependencyProvider =
        InspectionRouteDependencyProvider { emptyList() },
    val terminalRouteContinuation: InspectionRouteContinuationProvider =
        InspectionRouteContinuationProvider { _, _ -> null }
) {
    init {
        require(NAMESPACED_SIMPLE_ATO_ID.matches(sourceId)) { "sourceId must be namespaced" }
    }
}
