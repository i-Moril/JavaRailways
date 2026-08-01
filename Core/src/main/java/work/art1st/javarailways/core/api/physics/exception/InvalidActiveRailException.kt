package work.art1st.javarailways.core.api.physics.exception

import work.art1st.javarailways.core.physics.rail.RailView

class InvalidActiveRailException(railView: RailView, invalidNextActiveRail: RailView?) : RailException() {
}