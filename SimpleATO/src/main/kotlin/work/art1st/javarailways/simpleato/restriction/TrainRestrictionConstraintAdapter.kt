package work.art1st.javarailways.simpleato.restriction

import work.art1st.javarailways.simpleato.api.constraint.AtoConstraint
import work.art1st.javarailways.simpleato.api.constraint.ConstraintKey
import work.art1st.javarailways.simpleato.api.constraint.ConstraintManager
import work.art1st.javarailways.simpleato.api.constraint.ConstraintOwner
import work.art1st.javarailways.simpleato.api.constraint.ConstraintOwnerType
import work.art1st.javarailways.simpleato.api.constraint.CurrentSpeedLimitConstraint
import work.art1st.javarailways.simpleato.api.constraint.DepartureHoldConstraint
import work.art1st.javarailways.simpleato.api.constraint.MovementAuthorityConstraint
import work.art1st.javarailways.simpleato.api.constraint.RouteSpeedLimitConstraint
import work.art1st.javarailways.simpleato.api.constraint.StopConstraint
import work.art1st.javarailways.basic.api.observation.RailwayStateStamp
import work.art1st.javarailways.basic.api.route.CouplingContactAuthority
import work.art1st.javarailways.simpleato.api.restriction.CouplingContactRestriction
import work.art1st.javarailways.simpleato.api.restriction.CurrentSpeedRestriction
import work.art1st.javarailways.simpleato.api.restriction.MovementAuthorityRestriction
import work.art1st.javarailways.simpleato.api.restriction.RouteSpeedRestriction
import work.art1st.javarailways.simpleato.api.restriction.StopTrainRestriction
import work.art1st.javarailways.simpleato.api.restriction.TrainRestriction
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublicationResult
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublicationStatus
import work.art1st.javarailways.simpleato.api.route.FrozenRouteBinding
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionSnapshot
import java.util.TreeMap

internal enum class TrainRestrictionEnforcementStatus {
    APPLIED,
    FAILED_CLOSED
}

internal data class TrainRestrictionSourceEnforcement(
    val sourceId: String,
    val revision: Long,
    val status: TrainRestrictionEnforcementStatus,
    val detail: String = ""
)

/**
 * Per-train bridge from revisioned signalling snapshots into the existing minimum-constraint
 * pipeline. Publication never calls signalling code; physics application only converts immutable
 * route-relative values and replaces owner-scoped constraints.
 */
class TrainRestrictionConstraintAdapter(
    private val trainId: String,
    private val constraintManager: ConstraintManager
) : AutoCloseable {
    private val lock = Any()
    private val constraintOwner = ConstraintOwner(
        ConstraintOwnerType.SIGNALLING,
        "simpleato:external-restrictions:$trainId"
    )
    private val snapshots = TreeMap<String, TrainRestrictionSnapshot>()
    private val unavailableSources = linkedSetOf<String>()
    private var closed = false
    internal var appliedContactAuthority: CouplingContactAuthority? = null
        private set

    init {
        require(trainId.isNotBlank()) { "trainId must not be blank" }
    }

    /** Accepts only a strictly newer value in this source/train stream. */
    fun publish(snapshot: TrainRestrictionSnapshot): TrainRestrictionPublicationResult = synchronized(lock) {
        if (snapshot.trainId != trainId) {
            return@synchronized publicationResult(TrainRestrictionPublicationStatus.WRONG_TRAIN)
        }
        if (closed || snapshot.sourceId in unavailableSources) {
            return@synchronized publicationResult(TrainRestrictionPublicationStatus.SOURCE_UNAVAILABLE)
        }
        val current = snapshots[snapshot.sourceId]
        if (current != null && snapshot.revision <= current.revision) {
            return@synchronized TrainRestrictionPublicationResult(
                TrainRestrictionPublicationStatus.STALE_REVISION,
                current.revision
            )
        }
        snapshots[snapshot.sourceId] = snapshot
        TrainRestrictionPublicationResult(TrainRestrictionPublicationStatus.ACCEPTED, snapshot.revision)
    }

    /** Forced source loss is sticky and becomes a zero-speed hold on the next [apply]. */
    fun markSourceUnavailable(sourceId: String) {
        synchronized(lock) {
            if (!closed) unavailableSources += sourceId
        }
    }

    /**
     * Replaces this train's complete external-signalling constraint set in one atomic manager call.
     *
     * A snapshot remains usable across later physics epochs while world and the complete frozen
     * route still match. The host invalidates affected route bindings at the topology transaction.
     */
    internal fun apply(
        currentStamp: RailwayStateStamp,
        currentRoute: FrozenRouteBinding,
        routeProgressMeters: Double,
        validateContact: (CouplingContactAuthority) -> Boolean = { false }
    ): List<TrainRestrictionSourceEnforcement> {
        require(routeProgressMeters.isFinite() && routeProgressMeters >= 0.0) {
            "routeProgressMeters must be finite and non-negative"
        }
        return synchronized(lock) {
            check(!closed) { "train restriction adapter is closed" }
            val compiled = snapshots.values.map { snapshot ->
                compile(snapshot, snapshot.sourceId in unavailableSources,
                    currentStamp, currentRoute, routeProgressMeters, validateContact)
            }
            val contacts = compiled.mapNotNull(CompiledSource::contact)
            check(contacts.size <= 1) { "multiple coupling contact authorities are active" }
            appliedContactAuthority = contacts.singleOrNull()
            constraintManager.replace(constraintOwner, compiled.flatMap(CompiledSource::constraints))
            java.util.List.copyOf(compiled.map(CompiledSource::enforcement))
        }
    }

    /** Removes this adapter's constraints only when the owning train/controller lifecycle ends. */
    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            appliedContactAuthority = null
            snapshots.clear()
            unavailableSources.clear()
        }
        constraintManager.remove(constraintOwner)
    }

    private fun compile(
        snapshot: TrainRestrictionSnapshot,
        unavailable: Boolean,
        currentStamp: RailwayStateStamp,
        currentRoute: FrozenRouteBinding,
        routeProgressMeters: Double,
        validateContact: (CouplingContactAuthority) -> Boolean
    ): CompiledSource {
        val contacts = snapshot.restrictions.filterIsInstance<CouplingContactRestriction>()
        val invalid = when {
            unavailable -> "restriction source is unavailable"
            currentStamp.worldId != snapshot.observationStamp.worldId -> "restriction belongs to another world"
            currentStamp.physicsEpoch < snapshot.observationStamp.physicsEpoch ->
                "restriction observation is newer than the current physics epoch"
            snapshot.restrictions.isNotEmpty() && currentRoute != snapshot.route ->
                "restriction frozen route is no longer active"
            routeProgressMeters > currentRoute.totalLengthMeters ->
                "current route progress exceeds the frozen route"
            contacts.size > 1 -> "multiple coupling contact authorities in one source"
            contacts.any { !validateContact(it.authority) } -> "coupling contact proof or mission is no longer valid"
            else -> null
        }
        if (invalid != null) {
            return CompiledSource(
                listOf(DepartureHoldConstraint(constraintKey(snapshot.sourceId, "fail-closed"))),
                TrainRestrictionSourceEnforcement(
                    snapshot.sourceId,
                    snapshot.revision,
                    TrainRestrictionEnforcementStatus.FAILED_CLOSED,
                    invalid
                )
            )
        }
        return CompiledSource(
            snapshot.restrictions.filter { restriction ->
                restriction !is RouteSpeedRestriction || restriction.endRouteDistanceMeters == null ||
                    routeProgressMeters <= restriction.endRouteDistanceMeters
            }.map { restriction ->
                restriction.toAtoConstraint(snapshot.sourceId, routeProgressMeters)
            },
            TrainRestrictionSourceEnforcement(
                snapshot.sourceId,
                snapshot.revision,
                TrainRestrictionEnforcementStatus.APPLIED
            ),
            contacts.singleOrNull()?.authority
        )
    }

    private fun TrainRestriction.toAtoConstraint(
        sourceId: String,
        routeProgressMeters: Double
    ): AtoConstraint {
        val key = constraintKey(sourceId, restrictionId)
        return when (this) {
            is CurrentSpeedRestriction -> CurrentSpeedLimitConstraint(key, maximumSpeedMetersPerSecond)
            is RouteSpeedRestriction -> RouteSpeedLimitConstraint(
                key,
                (routeDistanceMeters - routeProgressMeters).coerceAtLeast(0.0),
                maximumSpeedMetersPerSecond
            )
            is StopTrainRestriction -> StopConstraint(
                key,
                (routeDistanceMeters - routeProgressMeters).coerceAtLeast(0.0),
                stoppingMarginMeters
            )
            is MovementAuthorityRestriction -> MovementAuthorityConstraint(
                key,
                (routeDistanceMeters - routeProgressMeters).coerceAtLeast(0.0),
                stoppingMarginMeters
            )
            is CouplingContactRestriction -> StopConstraint(key,
                (authority.contactBoundary.distanceFromRouteStartMeters - routeProgressMeters).coerceAtLeast(0.0),
                stoppingMarginMeters = 0.0)
        }
    }

    private fun publicationResult(status: TrainRestrictionPublicationStatus): TrainRestrictionPublicationResult =
        TrainRestrictionPublicationResult(status, null)

    private data class CompiledSource(
        val constraints: List<AtoConstraint>,
        val enforcement: TrainRestrictionSourceEnforcement,
        val contact: CouplingContactAuthority? = null
    )

    private companion object {
        fun constraintKey(sourceId: String, restrictionId: String): ConstraintKey =
            ConstraintKey(sourceId, restrictionId)
    }
}
