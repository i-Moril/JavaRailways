package work.art1st.javarailways.core.physics.rail

import kotlinx.serialization.Serializable
import work.art1st.javarailways.core.api.physics.rail.RailView
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef

/** Exact target identity in the current infrastructure document. */
@Serializable
data class SavedRailEndpointRef(val railId: String, val endpoint: RailEndpoint) {
    companion object {
        internal fun fromEndpoint(value: RailEndpointRef): SavedRailEndpointRef =
            SavedRailEndpointRef(value.railId.toString(), value.endpoint)

        internal fun fromAwayView(view: RailView): SavedRailEndpointRef = SavedRailEndpointRef(
            view.rail.id.toString(),
            if (view.viewDirection == ViewDirection.AB) RailEndpoint.A else RailEndpoint.B
        )
    }
}
