package work.art1st.javarailways.basic.pathFinding

import work.art1st.javarailways.core.api.physics.rail.RailLocation
import work.art1st.javarailways.core.api.physics.rail.RailObject
import work.art1st.javarailways.core.api.physics.rail.RailObjectLifecycleState
import work.art1st.javarailways.core.api.physics.rail.RailObjectRef
import work.art1st.javarailways.core.api.physics.rail.RailView

class RailDestination(
    val railView: RailView,
    val label: String,
    override val ref: RailObjectRef = defaultRef(railView, label)
) : RailObject {
    init {
        require(label.isNotBlank()) { "RailDestination label must not be blank" }
    }

    @Volatile
    override var lifecycleState: RailObjectLifecycleState = RailObjectLifecycleState.ACTIVE
        private set

    override val location: RailLocation
        get() = railView.getRailLocationAt(0.0)

    internal fun markRemoved() {
        lifecycleState = RailObjectLifecycleState.REMOVED
    }

    companion object {
        private fun defaultRef(railView: RailView, label: String): RailObjectRef = RailObjectRef(
            namespace = "basic",
            id = "destination/${railView.rail.id}/${railView.viewDirection.name.lowercase()}/$label"
        )
    }
}
