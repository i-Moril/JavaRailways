package work.art1st.javarailways.simpleato.api.restriction

import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.api.observation.RailwayStateStamp
import java.util.Collections

internal val NAMESPACED_RESTRICTION_ID = Regex("[a-z0-9_.-]+:[A-Za-z0-9_./-]+")

/**
 * Constant-time identity and range binding for one already frozen route.
 *
 * @property routeId stable route identity chosen by SimpleATO
 * @property routeRevision revision of that route identity, independent of topology revision
 * @property topologyRevision Core topology revision used to freeze the route
 * @property totalLengthMeters full frozen-route length in metres
 */
data class TrainRestrictionRouteBinding(
    val routeId: String,
    val routeRevision: Long,
    val topologyRevision: Long,
    val totalLengthMeters: Double
) {
    init {
        require(routeId.isNotBlank()) { "routeId must not be blank" }
        require(routeRevision >= 0L) { "routeRevision must be non-negative" }
        require(topologyRevision >= 0L) { "topologyRevision must be non-negative" }
        require(totalLengthMeters.isFinite() && totalLengthMeters >= 0.0) {
            "totalLengthMeters must be finite and non-negative"
        }
    }

    /** Conversion helpers for host-provided frozen routes. */
    companion object {
        /** Captures the constant-size identity and range fields from [route]. */
        @JvmStatic
        fun from(route: RouteSnapshot): TrainRestrictionRouteBinding = TrainRestrictionRouteBinding(
            route.routeId,
            route.revision,
            route.topologyRevision,
            route.totalLengthMeters
        )
    }
}

/** A signalling-owned restriction that can only tighten one train's permitted movement. */
sealed interface TrainRestriction {
    /** Stable namespaced identity unique within one source snapshot. */
    val restrictionId: String
}

/**
 * Immediate speed ceiling in metres per second.
 *
 * @property maximumSpeedMetersPerSecond non-negative speed ceiling applied at the current position
 */
data class CurrentSpeedRestriction(
    override val restrictionId: String,
    val maximumSpeedMetersPerSecond: Double
) : TrainRestriction {
    init {
        requireNamespacedRestrictionId(restrictionId)
        require(maximumSpeedMetersPerSecond.isFinite() && maximumSpeedMetersPerSecond >= 0.0) {
            "maximumSpeedMetersPerSecond must be finite and non-negative"
        }
    }
}

/**
 * Speed ceiling beginning at [routeDistanceMeters] measured from the frozen route start.
 *
 * @property routeDistanceMeters absolute target position in metres from the frozen route start
 * @property maximumSpeedMetersPerSecond non-negative speed ceiling at the target
 */
data class RouteSpeedRestriction(
    override val restrictionId: String,
    val routeDistanceMeters: Double,
    val maximumSpeedMetersPerSecond: Double
) : TrainRestriction {
    init {
        requireNamespacedRestrictionId(restrictionId)
        requireRouteDistance(routeDistanceMeters)
        require(maximumSpeedMetersPerSecond.isFinite() && maximumSpeedMetersPerSecond >= 0.0) {
            "maximumSpeedMetersPerSecond must be finite and non-negative"
        }
    }
}

/**
 * Braking target that requires zero speed at [routeDistanceMeters].
 *
 * @property routeDistanceMeters absolute stopping position in metres from the frozen route start
 * @property stoppingMarginMeters optional non-negative margin interpreted by the shared ATO profile
 */
data class StopTrainRestriction(
    override val restrictionId: String,
    val routeDistanceMeters: Double,
    val stoppingMarginMeters: Double? = null
) : TrainRestriction {
    init {
        requireNamespacedRestrictionId(restrictionId)
        requireRouteDistance(routeDistanceMeters)
        require(stoppingMarginMeters == null ||
            stoppingMarginMeters.isFinite() && stoppingMarginMeters >= 0.0
        ) { "stoppingMarginMeters must be null or finite and non-negative" }
    }
}

/**
 * Movement authority ending at [routeDistanceMeters].
 *
 * @property routeDistanceMeters absolute authority end in metres from the frozen route start
 */
data class MovementAuthorityRestriction(
    override val restrictionId: String,
    val routeDistanceMeters: Double
) : TrainRestriction {
    init {
        requireNamespacedRestrictionId(restrictionId)
        requireRouteDistance(routeDistanceMeters)
    }
}

/**
 * Complete immutable output from one signalling source for one train and one frozen route.
 *
 * [revision] is monotonic only within the `(sourceId, trainId)` stream. [observationStamp] records
 * the coherent observation used to compute this value; later physics epochs may continue to use it
 * because every distant target is anchored to [route]. A higher-revision empty [restrictions]
 * collection explicitly clears this source's active restrictions.
 *
 * @property sourceId stable namespaced identity of the publishing signal system or policy source
 * @property revision non-negative revision, strictly increasing for this source and train
 * @property trainId stable identity of the only train to which this snapshot applies
 * @property observationStamp coherent world, physics-epoch and topology observation used to compute it
 * @property route constant-size binding to the frozen route used for every distant target
 * @property restrictions complete replacement set for this source revision
 */
@ConsistentCopyVisibility
data class TrainRestrictionSnapshot private constructor(
    val sourceId: String,
    val revision: Long,
    val trainId: String,
    val observationStamp: RailwayStateStamp,
    val route: TrainRestrictionRouteBinding,
    val restrictions: List<TrainRestriction>
) {
    constructor(
        sourceId: String,
        revision: Long,
        trainId: String,
        observationStamp: RailwayStateStamp,
        route: TrainRestrictionRouteBinding,
        restrictions: Collection<TrainRestriction>
    ) : this(
        sourceId,
        revision,
        trainId,
        observationStamp,
        route,
        Collections.unmodifiableList(ArrayList(restrictions))
    )

    init {
        require(NAMESPACED_RESTRICTION_ID.matches(sourceId)) { "sourceId must be namespaced" }
        require(revision >= 0L) { "revision must be non-negative" }
        require(trainId.isNotBlank()) { "trainId must not be blank" }
        require(observationStamp.topologyRevision == route.topologyRevision) {
            "observation and route must use the same topology revision"
        }
        require(restrictions.map(TrainRestriction::restrictionId).distinct().size == restrictions.size) {
            "restriction IDs must be unique within one source snapshot"
        }
        restrictions.forEach { restriction ->
            val target = when (restriction) {
                is CurrentSpeedRestriction -> null
                is RouteSpeedRestriction -> restriction.routeDistanceMeters
                is StopTrainRestriction -> restriction.routeDistanceMeters
                is MovementAuthorityRestriction -> restriction.routeDistanceMeters
            }
            require(target == null || target <= route.totalLengthMeters) {
                "restriction ${restriction.restrictionId} exceeds the frozen route"
            }
        }
    }
}

/** Result category returned when a source publishes a restriction snapshot. */
enum class TrainRestrictionPublicationStatus {
    ACCEPTED,
    STALE_REVISION,
    WRONG_TRAIN,
    SOURCE_UNAVAILABLE
}

/**
 * Publication acknowledgement without applying or widening train authority.
 *
 * @property status accepted or rejected publication category
 * @property acceptedRevision revision currently retained for the source, when relevant
 */
data class TrainRestrictionPublicationResult(
    val status: TrainRestrictionPublicationStatus,
    val acceptedRevision: Long?
) {
    /** Whether the supplied snapshot became the retained source value. */
    val accepted: Boolean
        get() = status == TrainRestrictionPublicationStatus.ACCEPTED
}

/**
 * Platform-neutral synchronous publication boundary used after a signal epoch result is validated.
 *
 * Implementations must retain or apply the complete immutable replacement before returning and
 * must not defer safety work to Bukkit or another epoch. When called from the railway-epoch
 * runtime, any non-[TrainRestrictionPublicationStatus.ACCEPTED] result or thrown failure prevents
 * that Core candidate frame and exact ticket from being published.
 */
fun interface TrainRestrictionPublisher {
    /** Publishes one complete replacement snapshot and returns its authoritative acceptance result. */
    fun publish(snapshot: TrainRestrictionSnapshot): TrainRestrictionPublicationResult
}

private fun requireNamespacedRestrictionId(value: String) {
    require(NAMESPACED_RESTRICTION_ID.matches(value)) { "restrictionId must be namespaced" }
}

private fun requireRouteDistance(value: Double) {
    require(value.isFinite() && value >= 0.0) {
        "routeDistanceMeters must be finite and non-negative"
    }
}
