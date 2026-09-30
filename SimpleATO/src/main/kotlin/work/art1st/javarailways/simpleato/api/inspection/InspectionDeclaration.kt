package work.art1st.javarailways.simpleato.api.inspection

import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.simpleato.api.NAMESPACED_SIMPLE_ATO_ID
import work.art1st.javarailways.simpleato.api.route.FrozenRouteBinding
import java.util.Collections
import java.util.UUID

/**
 * One signal-plugin resource that must be inspected before its route-local trigger is reached.
 *
 * [triggerRouteDistanceMeters] identifies one occurrence on the frozen route in [InspectionDeclarationBinding.route].
 * [railId] and [facing] let the host verify that occurrence once when compiling the binding.
 * Runtime activation uses the validated absolute route coordinate and does not scan the route again.
 *
 * @property declarationId stable namespaced resource or signal identity within one source;
 * the same identity may occur at distinct trigger positions on one frozen route
 * @property railId persistent rail UUID containing the trigger
 * @property facing direction in which the train reaches the trigger
 * @property triggerRouteDistanceMeters absolute trigger position from the frozen-route start
 * @property inspectionLeadTimeSeconds non-negative plugin-declared preparation time in seconds;
 * SimpleATO converts it to a train-specific distance from current motion capabilities
 * @property minimumRequestedAuthorityEndRouteDistanceMeters optional route-local request floor;
 * once a nominal request crosses this declaration's trigger, SimpleATO asks Basic for at least
 * this boundary. Basic remains authoritative and may deny the larger request.
 */
data class InspectionDeclaration(
    val declarationId: String,
    val railId: UUID,
    val facing: ViewDirection,
    val triggerRouteDistanceMeters: Double,
    val inspectionLeadTimeSeconds: Double,
    val minimumRequestedAuthorityEndRouteDistanceMeters: Double? = null
) {
    init {
        require(NAMESPACED_SIMPLE_ATO_ID.matches(declarationId)) {
            "declarationId must be namespaced"
        }
        require(triggerRouteDistanceMeters.isFinite() && triggerRouteDistanceMeters >= 0.0) {
            "triggerRouteDistanceMeters must be finite and non-negative"
        }
        require(inspectionLeadTimeSeconds.isFinite() && inspectionLeadTimeSeconds >= 0.0) {
            "inspectionLeadTimeSeconds must be finite and non-negative"
        }
        minimumRequestedAuthorityEndRouteDistanceMeters?.let { requestEnd ->
            require(requestEnd.isFinite() && requestEnd >= triggerRouteDistanceMeters) {
                "minimum requested authority end must be finite and not precede the trigger"
            }
        }
    }
}

/**
 * Complete immutable inspection declaration replacement for one source, train and frozen route.
 *
 * The signal plugin computes this value only when its definition, the frozen route, or the train's
 * low-frequency capability profile changes. Current speed is intentionally absent: SimpleATO owns
 * the per-tick conversion from lead time to reachable distance and the stopping lookahead.
 *
 * @property sourceId stable namespaced signal-policy source
 * @property trainId stable target train identity
 * @property route constant-size binding to the route used to compile every declaration
 * @property definitionRevision non-negative revision of the plugin definitions used
 * @property trainProfileRevision non-negative revision of the low-frequency train capabilities used
 * @property declarations complete replacement set; an empty set is valid
 */
@ConsistentCopyVisibility
data class InspectionDeclarationBinding private constructor(
    val sourceId: String,
    val trainId: String,
    val route: FrozenRouteBinding,
    val definitionRevision: Long,
    val trainProfileRevision: Long,
    val declarations: List<InspectionDeclaration>
) {
    constructor(
        sourceId: String,
        trainId: String,
        route: FrozenRouteBinding,
        definitionRevision: Long,
        trainProfileRevision: Long,
        declarations: Collection<InspectionDeclaration>
    ) : this(
        sourceId,
        trainId,
        route,
        definitionRevision,
        trainProfileRevision,
        Collections.unmodifiableList(ArrayList(declarations))
    )

    init {
        require(NAMESPACED_SIMPLE_ATO_ID.matches(sourceId)) { "sourceId must be namespaced" }
        require(trainId.isNotBlank()) { "trainId must not be blank" }
        require(definitionRevision >= 0L) { "definitionRevision must be non-negative" }
        require(trainProfileRevision >= 0L) { "trainProfileRevision must be non-negative" }
        require(declarations.map { declaration ->
            declaration.declarationId to if (declaration.triggerRouteDistanceMeters == 0.0) 0.0
                else declaration.triggerRouteDistanceMeters
        }.distinct().size == declarations.size) {
            "declaration identity and trigger position must be unique within one binding"
        }
        declarations.forEach { declaration ->
            require(declaration.triggerRouteDistanceMeters <= route.totalLengthMeters) {
                "declaration ${declaration.declarationId} exceeds the frozen route"
            }
            declaration.minimumRequestedAuthorityEndRouteDistanceMeters?.let { requestEnd ->
                require(requestEnd <= route.totalLengthMeters) {
                    "declaration ${declaration.declarationId} authority request floor exceeds the frozen route"
                }
            }
        }
    }
}
