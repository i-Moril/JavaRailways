package work.art1st.javarailways.basic.api.ato.snapshot

import work.art1st.javarailways.core.api.physics.rail.RailObjectSnapshot
import work.art1st.javarailways.core.api.physics.train.TrainSnapshot
import java.util.Collections

/** Relationship of an observed subject to the previous perception snapshot. */
enum class ObservationTransition {
    ENTER,
    UPDATE,
    EXIT,
    PASS
}

/** Immutable observation ordered by distance along the selected route. */
data class RailObservationSnapshot(
    val subject: RailObjectSnapshot,
    val transition: ObservationTransition,
    val routeDistanceMeters: Double,
    val relativeSpeedMetersPerSecond: Double? = null
) {
    init {
        require(routeDistanceMeters.isFinite() && routeDistanceMeters >= 0.0) {
            "routeDistanceMeters must be finite and non-negative"
        }
        require(relativeSpeedMetersPerSecond == null || relativeSpeedMetersPerSecond.isFinite()) {
            "relativeSpeedMetersPerSecond must be finite when present"
        }
    }
}

/**
 * One immutable ATO perception result.
 *
 * The snapshot captures train and route revisions together so a planner can reject mixed or stale
 * inputs. [observations] is defensively copied and sorted by route distance by the producer.
 */
@ConsistentCopyVisibility
data class PerceptionSnapshot private constructor(
    val epoch: Long,
    val train: TrainSnapshot,
    val route: RouteSnapshot,
    val observations: List<RailObservationSnapshot>
) {
    constructor(
        epoch: Long,
        train: TrainSnapshot,
        route: RouteSnapshot,
        observations: Collection<RailObservationSnapshot>
    ) : this(epoch, train, route, immutableCopy(observations))

    init {
        require(epoch >= 0L) { "PerceptionSnapshot epoch must be non-negative" }
        require(observations.zipWithNext().all { (first, second) ->
            first.routeDistanceMeters <= second.routeDistanceMeters
        }) { "PerceptionSnapshot observations must be sorted by route distance" }
    }

    private companion object {
        fun immutableCopy(source: Collection<RailObservationSnapshot>): List<RailObservationSnapshot> =
            Collections.unmodifiableList(ArrayList(source))
    }
}
