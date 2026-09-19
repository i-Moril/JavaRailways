package work.art1st.javarailways.basic.api.route

import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import java.util.Collections
import java.util.UUID

/**
 * One authored cut through a rail.
 *
 * [position] uses canonical AB metres. Its [RailPositionSnapshot.facing] is the direction from the
 * outside half of the cut into the protected interior; it is not an operating-direction rule.
 * Boundaries must lie strictly inside a rail so a cut never ambiguously selects multiple branches
 * at a topology node.
 *
 * @property boundaryId namespaced stable provider identity for this cut
 * @property position immutable rail position whose facing points into the region
 */
data class ProtectionBoundaryDefinition(
    val boundaryId: String,
    val position: RailPositionSnapshot
) {
    init {
        require(NAMESPACED_ROUTE_ID.matches(boundaryId)) {
            "boundaryId must be namespaced"
        }
    }

    /** Convenience view of the inward direction encoded by [position]. */
    val insideFacing: ViewDirection
        get() = position.facing
}

/**
 * Immutable provider-owned definition of one protection region for one topology revision.
 *
 * The host defensively copies [boundaries], derives the interior graph, and rejects overlapping
 * interiors before publishing the definition. Persistence belongs to the provider, not Basic.
 *
 * @property region stable region identity and provider revision
 * @property topologyRevision Core structural revision against which boundaries were authored
 * @property boundaries non-empty immutable cut set
 */
@ConsistentCopyVisibility
data class ProtectionRegionDefinition private constructor(
    val region: ProtectionRegionRef,
    val topologyRevision: Long,
    val boundaries: List<ProtectionBoundaryDefinition>
) {
    constructor(
        region: ProtectionRegionRef,
        topologyRevision: Long,
        boundaries: Collection<ProtectionBoundaryDefinition>
    ) : this(region, topologyRevision, immutableCopy(boundaries))

    init {
        require(topologyRevision >= 0L) { "topologyRevision must be non-negative" }
        require(boundaries.isNotEmpty()) { "protection region must have at least one boundary" }
        require(boundaries.map(ProtectionBoundaryDefinition::boundaryId).distinct().size == boundaries.size) {
            "protection-region boundary IDs must be unique"
        }
    }

    private companion object {
        fun immutableCopy(source: Collection<ProtectionBoundaryDefinition>): List<ProtectionBoundaryDefinition> =
            Collections.unmodifiableList(ArrayList(source))
    }
}

/**
 * Runtime source of authoritative protection-region definitions.
 *
 * [definitions] is called synchronously during catalog registration or explicit refresh, never per
 * train Tick. It must return promptly, must not mutate the world, and must return a coherent value
 * set for the requested topology revision. The host copies and validates the returned collection.
 */
interface ProtectionRegionProvider {
    /** Namespaced stable runtime provider identity. */
    val providerId: String

    /** Return a complete value snapshot for [topologyRevision]; never return live collections. */
    fun definitions(topologyRevision: Long): Collection<ProtectionRegionDefinition>
}

enum class ProtectionRegionValidationCode {
    /** Provider identity is not namespaced. */
    INVALID_PROVIDER_ID,
    /** Another live provider already owns the same identity. */
    DUPLICATE_PROVIDER,
    /** Provider callback threw while taking its definition snapshot. */
    PROVIDER_FAILED,
    /** Definition, capture, or completed resolution used a different topology revision. */
    STALE_TOPOLOGY,
    /** Two provider snapshots define the same stable region ID. */
    DUPLICATE_REGION_ID,
    /** Two boundaries occupy the same rail cut within tolerance. */
    DUPLICATE_BOUNDARY_POSITION,
    /** A boundary rail is absent from authoritative topology. */
    MISSING_RAIL,
    /** A boundary is too close to an ambiguous rail endpoint. */
    BOUNDARY_AT_RAIL_ENDPOINT,
    /** Inward half-edges do not reach one common interior component. */
    DISCONNECTED_INTERIOR,
    /** A boundary outside half-edge reconnects to the proposed interior. */
    OUTSIDE_RECONNECTS,
    /** Two resolved region interiors overlap by positive rail length. */
    REGION_OVERLAP
}

/**
 * Immutable location-rich diagnostic returned when a catalog update is rejected.
 *
 * @property code stable machine-readable rejection reason
 * @property detail human-readable context; not a replacement for [code]
 * @property regionId affected stable region identity when known
 * @property boundaryId affected stable boundary identity when known
 * @property railId affected rail identity when known
 */
data class ProtectionRegionDiagnostic(
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

enum class ProtectionRegionCatalogStatus {
    /** A new provider and its complete region snapshot were published. */
    REGISTERED,
    /** All registered providers were atomically rebuilt and published. */
    REFRESHED,
    /** One provider and only its published regions were removed. */
    UNREGISTERED,
    /** Refresh produced the already published value snapshot. */
    UNCHANGED,
    /** Validation failed and the preceding catalog remains published. */
    REJECTED,
    /** Unregister targeted no live provider. */
    NOT_FOUND
}

/**
 * Typed catalog mutation result; every rejected update preserves the preceding published catalog.
 *
 * @property status mutation outcome
 * @property diagnostics immutable rejection diagnostics, present only for `REJECTED`
 */
class ProtectionRegionCatalogResult(
    val status: ProtectionRegionCatalogStatus,
    diagnostics: Collection<ProtectionRegionDiagnostic> = emptyList()
) {
    val diagnostics: List<ProtectionRegionDiagnostic> =
        Collections.unmodifiableList(ArrayList(diagnostics))

    val successful: Boolean
        get() = status != ProtectionRegionCatalogStatus.REJECTED &&
            status != ProtectionRegionCatalogStatus.NOT_FOUND

    init {
        require(status == ProtectionRegionCatalogStatus.REJECTED || this.diagnostics.isEmpty()) {
            "diagnostics are only valid for REJECTED catalog updates"
        }
    }
}
