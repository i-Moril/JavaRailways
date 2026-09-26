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
 * [railId] and [facing] let the host verify that occurrence and find it in the shared forward
 * perception without another route scan.
 *
 * @property declarationId stable namespaced resource or signal identity within one source
 * @property railId persistent rail UUID containing the trigger
 * @property facing direction in which the train reaches the trigger
 * @property triggerRouteDistanceMeters absolute trigger position from the frozen-route start
 * @property inspectionDistanceMeters non-negative plugin-declared lead distance in metres
 */
data class InspectionDeclaration(
    val declarationId: String,
    val railId: UUID,
    val facing: ViewDirection,
    val triggerRouteDistanceMeters: Double,
    val inspectionDistanceMeters: Double
) {
    init {
        require(NAMESPACED_SIMPLE_ATO_ID.matches(declarationId)) {
            "declarationId must be namespaced"
        }
        require(triggerRouteDistanceMeters.isFinite() && triggerRouteDistanceMeters >= 0.0) {
            "triggerRouteDistanceMeters must be finite and non-negative"
        }
        require(inspectionDistanceMeters.isFinite() && inspectionDistanceMeters >= 0.0) {
            "inspectionDistanceMeters must be finite and non-negative"
        }
    }
}

/**
 * Complete immutable inspection declaration replacement for one source, train and frozen route.
 *
 * The signal plugin computes this value only when its definition, the frozen route, or the train's
 * low-frequency capability profile changes. Current speed is intentionally absent: SimpleATO owns
 * the dynamic stopping lookahead used at activation time.
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
        require(declarations.map(InspectionDeclaration::declarationId).distinct().size == declarations.size) {
            "declaration IDs must be unique within one binding"
        }
        declarations.forEach { declaration ->
            require(declaration.triggerRouteDistanceMeters <= route.totalLengthMeters) {
                "declaration ${declaration.declarationId} exceeds the frozen route"
            }
        }
    }
}
