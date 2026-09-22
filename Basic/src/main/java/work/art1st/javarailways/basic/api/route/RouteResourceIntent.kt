package work.art1st.javarailways.basic.api.route

import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner
import work.art1st.javarailways.basic.api.infrastructure.StableInfrastructureResource
import work.art1st.javarailways.basic.api.observation.RailwayStateStamp
import java.util.Collections
import java.util.TreeMap

private val NAMESPACED_ROUTE_INTENT_ID = Regex("[a-z0-9_.-]+:[A-Za-z0-9_./-]+")

/**
 * A distance along one containing [RouteResourceIntent.route].
 *
 * [distanceFromRouteStartMeters] is measured in actual metres from the first point of the frozen
 * route and increases in traversal order. It is not a canonical rail-AB coordinate, including
 * while the route traverses a segment in the BA direction.
 *
 * @property distanceFromRouteStartMeters finite non-negative route distance in actual metres
 */
data class RouteBoundary(
    val distanceFromRouteStartMeters: Double
) {
    init {
        require(distanceFromRouteStartMeters.isFinite() && distanceFromRouteStartMeters >= 0.0) {
            "distanceFromRouteStartMeters must be finite and non-negative"
        }
    }
}

/**
 * One caller-added stable resource and the route position after which the authoritative train tail,
 * including Basic's safety margin, may release it. [preparation] is absent when the resource only
 * needs to be claimed.
 *
 * @property resource exact registered resource identity requested in addition to Basic's physical floor
 * @property releaseAfterTailPasses minimum route boundary through which the resource is held
 * @property preparation optional provider-owned runtime preparation request
 */
data class AdditionalResourceRequirement(
    val resource: StableInfrastructureResource,
    val releaseAfterTailPasses: RouteBoundary,
    val preparation: ResourcePreparationDescriptor? = null
)

/**
 * Immutable scalar data understood by a registered resource-preparation provider.
 *
 * These values contain no mutable train or world object and do not describe a persistence schema.
 */
sealed interface PreparationScalar {
    /** Immutable text argument. */
    data class Text(val value: String) : PreparationScalar

    /** Immutable signed integer argument. */
    data class Integer(val value: Long) : PreparationScalar

    /** Immutable finite decimal argument. */
    data class Decimal(val value: Double) : PreparationScalar {
        init {
            require(value.isFinite()) { "decimal preparation values must be finite" }
        }
    }

    /** Immutable boolean argument. */
    data class Flag(val value: Boolean) : PreparationScalar
}

/**
 * Opaque runtime-only preparation data dispatched to the owner of an added resource.
 *
 * [kindId] is a stable namespaced runtime identifier. [arguments] is defensively copied and
 * exposed in key order so equivalent requests have deterministic value semantics. This descriptor
 * is neither serialized JSON nor a persisted compatibility boundary.
 *
 * Constructing this value performs no registration, provider call, world access, or blocking work.
 * The world-local admission service later validates it against the registered resource owner.
 *
 * @property kindId namespaced stable preparation-capability identity
 * @property arguments immutable scalar arguments copied and ordered by key
 */
class ResourcePreparationDescriptor(
    val kindId: String,
    arguments: Map<String, PreparationScalar>
) {
    val arguments: Map<String, PreparationScalar> = immutableSortedArguments(arguments)

    init {
        require(NAMESPACED_ROUTE_INTENT_ID.matches(kindId)) { "kindId must be namespaced" }
        require(this.arguments.keys.all(String::isNotBlank)) {
            "preparation argument keys must not be blank"
        }
    }

    override fun equals(other: Any?): Boolean =
        other is ResourcePreparationDescriptor && kindId == other.kindId && arguments == other.arguments

    override fun hashCode(): Int = 31 * kindId.hashCode() + arguments.hashCode()

    override fun toString(): String =
        "ResourcePreparationDescriptor(kindId=$kindId, arguments=$arguments)"

    private companion object {
        fun immutableSortedArguments(
            source: Map<String, PreparationScalar>
        ): Map<String, PreparationScalar> = Collections.unmodifiableMap(TreeMap(source))
    }
}

/**
 * Immutable request data for admitting one authority range on an already selected frozen route.
 *
 * The route boundary values use actual metres from the first point of [route] and increase in
 * traversal order. The request contains only caller additions: it carries no raw footprint and
 * cannot replace the physical rail, turnout, or shared-conflict resources derived by Basic.
 * [additionalResources] is defensively copied and is unique by exact stable resource identity.
 *
 * This is an in-process runtime value. [requestId] is only a caller correlation identity; neither
 * it nor this request creates retry, persistence, or schema-compatibility semantics.
 * Construction is thread-independent value validation and performs no world access or blocking
 * work. Authentication, current observation lookup, resource registration, admission results, and
 * grant lifecycle belong to the later world-local admission service rather than this value.
 *
 * @property requestId namespaced caller correlation identity with no retry semantics
 * @property owner logical grant owner and non-negative plan revision
 * @property trainId non-blank stable train identity from the named observation
 * @property observationStamp coherent world, physics-epoch, and topology observation identity
 * @property route immutable selected route bound to the observation's topology revision
 * @property desiredAuthority requested route-metre authority boundary
 * @property additionalResources immutable unique caller additions; never a replacement footprint
 */
@ConsistentCopyVisibility
data class RouteResourceIntent private constructor(
    val requestId: String,
    val owner: InfrastructureOwner,
    val trainId: String,
    val observationStamp: RailwayStateStamp,
    val route: RouteSnapshot,
    val desiredAuthority: RouteBoundary,
    val additionalResources: List<AdditionalResourceRequirement>
) {
    constructor(
        requestId: String,
        owner: InfrastructureOwner,
        trainId: String,
        observationStamp: RailwayStateStamp,
        route: RouteSnapshot,
        desiredAuthority: RouteBoundary,
        additionalResources: Collection<AdditionalResourceRequirement>
    ) : this(
        requestId = requestId,
        owner = owner,
        trainId = trainId,
        observationStamp = observationStamp,
        route = route,
        desiredAuthority = desiredAuthority,
        additionalResources = immutableRequirements(additionalResources)
    )

    init {
        require(NAMESPACED_ROUTE_INTENT_ID.matches(requestId)) { "requestId must be namespaced" }
        require(trainId.isNotBlank()) { "trainId must not be blank" }
        require(route.segments.isNotEmpty()) { "route must contain at least one segment" }
        require(observationStamp.topologyRevision == route.topologyRevision) {
            "observationStamp and route must use the same topology revision"
        }
        require(desiredAuthority.distanceFromRouteStartMeters <= route.totalLengthMeters) {
            "desiredAuthority exceeds the frozen route"
        }
        require(additionalResources.all {
            it.releaseAfterTailPasses.distanceFromRouteStartMeters <=
                desiredAuthority.distanceFromRouteStartMeters
        }) {
            "additional resource release boundary exceeds desiredAuthority"
        }
        require(additionalResources.map(AdditionalResourceRequirement::resource).distinct().size ==
            additionalResources.size
        ) {
            "additionalResources contains duplicate stable resource identities"
        }
    }

    private companion object {
        fun immutableRequirements(
            source: Collection<AdditionalResourceRequirement>
        ): List<AdditionalResourceRequirement> = Collections.unmodifiableList(ArrayList(source))
    }
}
