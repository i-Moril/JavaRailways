package work.art1st.javarailways.simpleato.api.inspection

import work.art1st.javarailways.basic.api.ato.snapshot.PerceptionSnapshot
import work.art1st.javarailways.basic.api.observation.RailwayStateStamp
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionSnapshot
import work.art1st.javarailways.simpleato.api.route.FrozenRouteBinding
import java.util.Collections

/**
 * Synchronous update callback for the current complete active declaration set.
 *
 * The callback runs in this train's physics control calculation before motion is planned. Different
 * trains may invoke the same handler concurrently. Read only immutable published policy/state and
 * use thread-safe per-train publication revisions; do not access Bukkit, mutable Dispatcher state,
 * perform blocking I/O or defer safety work asynchronously. Shared signal rules belong in the
 * serial signal epoch; this callback projects their published values onto one frozen route.
 * Every invocation must return exactly one complete replacement snapshot. Clearing an existing
 * restriction therefore uses a newer snapshot with an empty restriction list.
 */
fun interface InspectionUpdateHandler {
    /** Computes exactly one complete restriction snapshot for this source and train. */
    fun update(input: InspectionUpdateInput): Collection<TrainRestrictionSnapshot>
}

/**
 * Immutable route-local context for one inspection-source update.
 *
 * [activeDeclarations] is the complete set currently inside its inspection horizon. The callback
 * runs only for the first binding baseline, when that complete set changes, or when an active
 * declaration is invalidated. The list may be empty for a baseline or after the final declaration
 * leaves the horizon. [perception] is the controller's existing shared forward perception; the
 * inspection runtime never performs another route scan.
 *
 * @property observationStamp coherent world state used for any returned restriction snapshot
 * @property route active frozen route to which declarations and returned restrictions are bound
 * @property routeProgressMeters current train-head position measured from the frozen route start
 * @property perception controller's already-computed immutable forward perception
 * @property activeDeclarations immutable complete active declaration set
 */
@ConsistentCopyVisibility
data class InspectionUpdateInput private constructor(
    val observationStamp: RailwayStateStamp,
    val route: FrozenRouteBinding,
    val routeProgressMeters: Double,
    val perception: PerceptionSnapshot,
    val activeDeclarations: List<InspectionDeclaration>,
    /** Complete binding-local ranges, including internal starts and passed entrances. */
    val speedRanges: List<InspectionSpeedRange>
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
    }

    internal companion object {
        fun create(
            observationStamp: RailwayStateStamp,
            route: FrozenRouteBinding,
            routeProgressMeters: Double,
            perception: PerceptionSnapshot,
            activeDeclarations: Collection<InspectionDeclaration>,
            speedRanges: Collection<InspectionSpeedRange> = emptyList()
        ): InspectionUpdateInput = InspectionUpdateInput(
            observationStamp,
            route,
            routeProgressMeters,
            perception,
            Collections.unmodifiableList(ArrayList(activeDeclarations)),
            Collections.unmodifiableList(ArrayList(speedRanges))
        )
    }
}
