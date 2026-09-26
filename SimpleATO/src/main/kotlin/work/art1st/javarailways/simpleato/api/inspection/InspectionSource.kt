package work.art1st.javarailways.simpleato.api.inspection

import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.api.observation.RailwayStateStamp
import work.art1st.javarailways.simpleato.api.NAMESPACED_SIMPLE_ATO_ID

/**
 * Host-selected route context used to compile one complete train inspection binding.
 *
 * The host invokes the provider only after SimpleATO has selected the exact production frozen
 * route. Plugins therefore do not discover routes through mutable trains or reconstruct them from
 * observations. [route] and [observationStamp] are coherent and belong to [trainId]. The source
 * resolves its own immutable, low-frequency train capability profile by [trainId]; the host does
 * not expose a live train or current speed through this compilation input.
 */
data class InspectionBindingInput(
    val observationStamp: RailwayStateStamp,
    val trainId: String,
    val route: RouteSnapshot
) {
    init {
        require(trainId.isNotBlank()) { "trainId must not be blank" }
        require(observationStamp.topologyRevision == route.topologyRevision) {
            "observation and inspection route must use the same topology revision"
        }
    }
}

/**
 * Compiles a complete immutable binding for the exact route supplied by the host.
 *
 * The callback runs synchronously on the serialized railway owner. It must be bounded,
 * non-blocking, side-effect free and must not call back into the Bukkit source service.
 */
fun interface InspectionBindingProvider {
    fun bind(input: InspectionBindingInput): InspectionDeclarationBinding
}

/**
 * One independently implemented inspection source.
 *
 * [bindingProvider] is called only for a new train/route binding or an atomic source replacement;
 * current speed is intentionally absent from its input. [updateHandler] receives one baseline call
 * for each new binding, then only newly active or explicitly dirtied declarations, always with the
 * controller's existing perception snapshot.
 */
data class InspectionSource(
    val sourceId: String,
    val bindingProvider: InspectionBindingProvider,
    val updateHandler: InspectionUpdateHandler
) {
    init {
        require(NAMESPACED_SIMPLE_ATO_ID.matches(sourceId)) { "sourceId must be namespaced" }
    }
}
