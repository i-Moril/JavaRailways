package work.art1st.mineRails.core.api.event

import work.art1st.mineRails.core.api.util.annotation.AsyncEventApi
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

    fun requestLink() {
        requestedResolution.set(CollisionResolution.LINK)
    }
}
