package work.art1st.javarailways.simpleato.api.inspection

import work.art1st.javarailways.basic.api.ato.snapshot.PerceptionSnapshot
import work.art1st.javarailways.basic.api.observation.RailwayStateStamp
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionSnapshot
import work.art1st.javarailways.simpleato.api.route.FrozenRouteBinding
import java.util.Collections

/**
 * Synchronous update callback for declarations that became active or were explicitly dirtied.
 *
 * The callback runs on the serialized railway owner. It must return before the next physics epoch,
 * must not call Bukkit or blocking I/O, and must not defer safety work asynchronously. An empty
 * collection means that this invocation publishes no restriction change; clearing an existing
 * restriction still requires a newer complete snapshot with an empty restriction list.
 */
fun interface InspectionUpdateHandler {
    /** Computes zero or one complete restriction snapshot for this source and train. */
    fun update(input: InspectionUpdateInput): Collection<TrainRestrictionSnapshot>
}

/**
 * Immutable route-local context for one inspection-source update.
 *
 * [declarations] contains only declarations newly active at [routeProgressMeters] or explicitly
 * dirtied since their previous successful dispatch. [perception] is the controller's existing
 * shared forward perception; the inspection runtime never performs another route scan.
 *
 * @property observationStamp coherent world state used for any returned restriction snapshot
 * @property route active frozen route to which declarations and returned restrictions are bound
 * @property routeProgressMeters current train-head position measured from the frozen route start
 * @property perception controller's already-computed immutable forward perception
 * @property declarations immutable nonempty declarations requiring this update
 */
@ConsistentCopyVisibility
data class InspectionUpdateInput private constructor(
    val observationStamp: RailwayStateStamp,
    val route: FrozenRouteBinding,
    val routeProgressMeters: Double,
    val perception: PerceptionSnapshot,
    val declarations: List<InspectionDeclaration>
) {
    init {
        require(routeProgressMeters.isFinite() && routeProgressMeters >= 0.0 &&
            routeProgressMeters <= route.totalLengthMeters
        ) { "routeProgressMeters must be within the frozen route" }
        require(observationStamp.topologyRevision == route.topologyRevision) {
            "observation and route must use the same topology revision"
        }
        require(perception.route.topologyRevision == route.topologyRevision) {
            "perception and route must use the same topology revision"
        }
        require(declarations.isNotEmpty()) { "inspection update must contain declarations" }
    }

    internal companion object {
        fun create(
            observationStamp: RailwayStateStamp,
            route: FrozenRouteBinding,
            routeProgressMeters: Double,
            perception: PerceptionSnapshot,
            declarations: Collection<InspectionDeclaration>
        ): InspectionUpdateInput = InspectionUpdateInput(
            observationStamp,
            route,
            routeProgressMeters,
            perception,
            Collections.unmodifiableList(ArrayList(declarations))
        )
    }
}
