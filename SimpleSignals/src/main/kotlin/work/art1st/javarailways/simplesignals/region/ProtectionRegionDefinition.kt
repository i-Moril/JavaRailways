package work.art1st.javarailways.simplesignals.region

import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import java.util.UUID

internal val SIMPLE_SIGNALS_NAMESPACED_ID = Regex("[a-z0-9_.-]+:[A-Za-z0-9_./-]+")

/** A geometric track location, independent of an entry or travel direction. */
internal data class RailCutPosition(val railId: UUID, val arcLengthInAbMeters: Double) {
    init {
        require(arcLengthInAbMeters.isFinite() && arcLengthInAbMeters >= 0.0) {
            "arcLengthInAbMeters must be finite and non-negative"
        }
    }
    fun facing(direction: ViewDirection) = RailPositionSnapshot(railId, direction, arcLengthInAbMeters)
}

internal fun RailPositionSnapshot.cutPosition() = RailCutPosition(railId, arcLengthInAbMeters)

/** A placed cut. Only a selected enclosed component can give it an inward direction. */
internal data class ProtectionBoundaryCut(val boundaryId: String, val position: RailCutPosition) {
    init { require(SIMPLE_SIGNALS_NAMESPACED_ID.matches(boundaryId)) { "boundaryId must be namespaced" } }
    fun facing(direction: ViewDirection) = ProtectionBoundaryDefinition(boundaryId, position.facing(direction))
}

/** One authored directional cut. Facing points from outside into the protected interior. */
internal data class ProtectionBoundaryDefinition(
    val boundaryId: String,
    val position: RailPositionSnapshot
) {
    init {
        require(SIMPLE_SIGNALS_NAMESPACED_ID.matches(boundaryId)) { "boundaryId must be namespaced" }
    }

    val insideFacing: ViewDirection
        get() = position.facing
    fun cut() = ProtectionBoundaryCut(boundaryId, position.cutPosition())
}

/** Plugin-owned authored region. Its interior is derived for one topology root at publication. */
@ConsistentCopyVisibility
internal data class ProtectionRegionDefinition private constructor(
    val regionId: String,
    val revision: Long,
    val boundaries: List<ProtectionBoundaryDefinition>,
    val boundaryDraft: List<ProtectionBoundaryCut>?
) {
    constructor(
        regionId: String,
        revision: Long,
        boundaries: Collection<ProtectionBoundaryDefinition>,
        boundaryDraft: Collection<ProtectionBoundaryCut>? = null
    ) : this(regionId, revision, java.util.List.copyOf(boundaries), boundaryDraft?.let { java.util.List.copyOf(it) })

    /** Placed markers; accepted cuts remain available to reconstruct local protection after restart. */
    val effectiveCuts: List<ProtectionBoundaryCut> get() = boundaryDraft ?: boundaries.map { it.cut() }

    /** New draft cuts have no entrance direction until the replacement region is accepted. */
    val placedEntrances: List<ProtectionBoundaryDefinition> get() {
        val draft = boundaryDraft ?: return boundaries
        val cutsById = draft.associateBy { it.boundaryId }
        return boundaries.filter { cutsById[it.boundaryId] == it.cut() }
    }

    init {
        require(SIMPLE_SIGNALS_NAMESPACED_ID.matches(regionId)) { "regionId must be namespaced" }
        require(revision >= 0L) { "region revision must be non-negative" }
        require(boundaries.isNotEmpty()) { "protection region must have at least one boundary" }
        require(boundaries.map(ProtectionBoundaryDefinition::boundaryId).distinct().size == boundaries.size) {
            "protection-region boundary IDs must be unique"
        }
        require(effectiveCuts.map { it.boundaryId }.distinct().size == effectiveCuts.size) {
            "draft boundary IDs must be unique"
        }
    }
}

internal enum class ProtectionRegionValidationCode {
    STALE_TOPOLOGY,
    INVALID_DEFINITION_REVISION,
    DUPLICATE_REGION_ID,
    DUPLICATE_BOUNDARY_POSITION,
    MISSING_RAIL,
    BOUNDARY_AT_RAIL_ENDPOINT,
    DISCONNECTED_INTERIOR,
    OUTSIDE_RECONNECTS,
    REGION_OVERLAP,
    INVALID_ROUTE,
    AMBIGUOUS_TRAVERSAL
}

internal data class ProtectionRegionDiagnostic(
    val code: ProtectionRegionValidationCode,
    val detail: String,
    val regionId: String? = null,
    val boundaryId: String? = null,
    val railId: UUID? = null
) {
    init {
        require(detail.isNotBlank()) { "diagnostic detail must not be blank" }
    }
}
