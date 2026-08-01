package work.art1st.javarailways.basic.api.ato.provider

import work.art1st.javarailways.basic.api.ato.constraint.AtoConstraint
import work.art1st.javarailways.basic.api.ato.snapshot.PerceptionSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.core.api.physics.rail.RailLocation
import work.art1st.javarailways.core.api.physics.rail.RailObject
import work.art1st.javarailways.core.api.physics.rail.RailObjectLifecycleState
import work.art1st.javarailways.core.api.physics.rail.RailObjectRef
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.train.Train
import work.art1st.javarailways.core.api.physics.train.TrainSnapshot
import java.util.concurrent.ConcurrentHashMap

data class AtoProviderContext(
    val train: TrainSnapshot,
    val route: RouteSnapshot,
    val perception: PerceptionSnapshot
)

/** Optional signalling rule set added on top of SimpleATO's built-in constraints. */
interface AtoProvider {
    val id: String

    fun constraints(context: AtoProviderContext): Collection<AtoConstraint>
}

fun interface AtoProviderFactory {
    fun create(train: Train): AtoProvider
}

class AtoProviderRegistry {
    private val factories = ConcurrentHashMap<String, AtoProviderFactory>()

    fun register(id: String, factory: AtoProviderFactory) {
        require(id.isNotBlank())
        check(factories.putIfAbsent(id, factory) == null) { "ATO provider already registered: $id" }
    }

    fun unregister(id: String) {
        factories.remove(id)
    }

    fun create(id: String, train: Train): AtoProvider =
        factories[id]?.create(train) ?: error("ATO provider is not registered: $id")
}

enum class ProviderBoundaryAction {
    ACTIVATE,
    DEACTIVATE
}

/** Direction-aware RailObject that switches the optional signalling provider after it is passed. */
class AtoProviderBoundary(
    override val ref: RailObjectRef,
    override val location: RailLocation,
    val providerId: String,
    val action: ProviderBoundaryAction,
    val direction: ViewDirection? = null
) : RailObject {
    init {
        require(providerId.isNotBlank())
    }

    @Volatile
    override var lifecycleState: RailObjectLifecycleState = RailObjectLifecycleState.ACTIVE
        private set

    internal fun markRemoved() {
        lifecycleState = RailObjectLifecycleState.REMOVED
    }
}
