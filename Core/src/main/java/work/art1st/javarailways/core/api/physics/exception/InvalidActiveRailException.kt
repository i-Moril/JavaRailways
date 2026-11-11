package work.art1st.javarailways.core.api.physics.exception

import work.art1st.javarailways.core.physics.rail.RailView

/** Captures the rejected selection as text without retaining either live rail view. */
class InvalidActiveRailException(railView: RailView, invalidNextActiveRail: RailView?) : RailException() {
    override val message: String =
        "Invalid active rail: source=${railView.rail.id}/${railView.viewDirection}, selected=" +
            (invalidNextActiveRail?.let { "${it.rail.id}/${it.viewDirection}" } ?: "null")
}
