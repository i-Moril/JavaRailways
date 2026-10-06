package work.art1st.javarailways.simplesignals.region

import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import java.util.UUID

/** One authored directed traversal in canonical rail AB metres. */
internal data class ProtectionRouteTraversal(
    val railId: UUID,
    val facing: ViewDirection,
    val lowerAbMeters: Double,
    val upperAbMeters: Double
) {
    init {
        require(lowerAbMeters.isFinite() && lowerAbMeters >= 0.0) {
            "lowerAbMeters must be finite and non-negative"
        }
        require(upperAbMeters.isFinite() && upperAbMeters > lowerAbMeters) {
            "upperAbMeters must be finite and greater than lowerAbMeters"
        }
    }

    val travelStartAbMeters: Double
        get() = if (facing == ViewDirection.AB) lowerAbMeters else upperAbMeters

    val travelEndAbMeters: Double
        get() = if (facing == ViewDirection.AB) upperAbMeters else lowerAbMeters
}
/** One complete, indivisible traversal from an authored entry gate to an authored exit gate. */
@ConsistentCopyVisibility
internal data class ProtectionRouteDefinition private constructor(
    val routeId: String,
    val entryBoundaryId: String,
    val exitBoundaryId: String,
    val traversals: List<ProtectionRouteTraversal>
) {
    constructor(
        routeId: String,
        entryBoundaryId: String,
        exitBoundaryId: String,
        traversals: Collection<ProtectionRouteTraversal>
    ) : this(routeId, entryBoundaryId, exitBoundaryId, java.util.List.copyOf(traversals))

    init {
        require(SIMPLE_SIGNALS_NAMESPACED_ID.matches(routeId)) { "routeId must be namespaced" }
        require(SIMPLE_SIGNALS_NAMESPACED_ID.matches(entryBoundaryId)) {
            "entryBoundaryId must be namespaced"
        }
        require(SIMPLE_SIGNALS_NAMESPACED_ID.matches(exitBoundaryId)) {
            "exitBoundaryId must be namespaced"
        }
        require(traversals.isNotEmpty()) { "a protection route must contain at least one traversal" }
    }
}

/** One fixed route table bound to exactly one revision of one protection region. */
@ConsistentCopyVisibility
internal data class ProtectionRouteTableDefinition private constructor(
    val tableId: String,
    val revision: Long,
    val regionId: String,
    val regionRevision: Long,
    val routes: List<ProtectionRouteDefinition>
) {
    constructor(
        tableId: String,
        revision: Long,
        regionId: String,
        regionRevision: Long,
        routes: Collection<ProtectionRouteDefinition>
    ) : this(
        tableId,
        revision,
        regionId,
        regionRevision,
        java.util.List.copyOf(routes)
    )

    init {
        require(SIMPLE_SIGNALS_NAMESPACED_ID.matches(tableId)) { "tableId must be namespaced" }
        require(SIMPLE_SIGNALS_NAMESPACED_ID.matches(regionId)) { "regionId must be namespaced" }
        require(revision >= 0L) { "route-table revision must be non-negative" }
        require(regionRevision >= 0L) { "region revision must be non-negative" }
        require(routes.isNotEmpty()) { "a fixed route table must contain at least one route" }
    }
}
