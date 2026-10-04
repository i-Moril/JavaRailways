package work.art1st.javarailways.simpleato.api.route

import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import java.util.Collections
import java.util.UUID

/** One detached policy read for exactly the requested rails. Null means both directions. */
class RailOperatingDirections(
    val topology: TopologyState,
    directions: Map<UUID, ViewDirection?>
) {
    val directions: Map<UUID, ViewDirection?> = Collections.unmodifiableMap(LinkedHashMap(directions))

    init {
        require(directions.keys.all(topology.railProjection::containsKey)) {
            "operating direction subset references a missing rail"
        }
    }
}
