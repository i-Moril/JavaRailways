package work.art1st.javarailways.core.api.event

import org.jetbrains.annotations.ApiStatus
import work.art1st.javarailways.core.api.util.annotation.AsyncEventApi
import java.util.concurrent.atomic.AtomicReference

/** Default collision outcome. Listeners may request a link; they never mutate trains directly. */
enum class CollisionResolution {
    DERAIL,
    LINK
}

@AsyncEventApi
class CollisionEvent(
    val epoch: Long,
    val worldId: String,
    val trainAId: String,
    val trainBId: String,
    val relativeSpeed: Double
) : PhysicsRailEvent {
    private val requestedResolution = AtomicReference(CollisionResolution.DERAIL)

    val resolution: CollisionResolution
        get() = requestedResolution.get()

    /**
     * Request coupling rather than derailment. This API is experimental: signatures and semantics
     * may change without source or binary compatibility guarantees, including for Java callers.
     */
    @ApiStatus.Experimental
    fun requestLink() {
        requestedResolution.set(CollisionResolution.LINK)
    }
}
