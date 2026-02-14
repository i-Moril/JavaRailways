package work.art1st.mineRails.core.api.physics.exception

import work.art1st.mineRails.core.physics.rail.RailView

class InvalidActiveRailException(railView: RailView, invalidNextActiveRail: RailView?) : RailException() {
}