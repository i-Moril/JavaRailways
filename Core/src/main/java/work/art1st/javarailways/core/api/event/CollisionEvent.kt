package work.art1st.javarailways.core.api.event

import work.art1st.javarailways.core.api.physics.World
import org.jetbrains.annotations.ApiStatus
import work.art1st.javarailways.core.api.util.annotation.AsyncEventApi
import java.util.concurrent.atomic.AtomicReference

/** Default collision outcome. Listeners may request a link; they never mutate trains directly. */
enum class CollisionResolution {
    DERAIL,
    LINK
}

/**
 * Synchronous physics decision for a detected collision; the outcome is applied next epoch.
 * Register through PhysicsRailEventBus and close the registration when its owner stops.
 * The World identifies the source; listeners must not mutate live trains or invoke platform APIs.
 *
 * @property epoch physics epoch in which the collision was detected
 * @property world exact runtime world containing both participants
 * @property trainAId stable ID of the first collision participant
 * @property trainBId stable ID of the second collision participant
 * @property relativeSpeed magnitude of the participants' relative velocity in metres per second
 */
@AsyncEventApi
class CollisionEvent(
    val epoch: Long,
    override val world: World,
    val trainAId: String,
    val trainBId: String,
    val relativeSpeed: Double
) : PhysicsRailEvent, WorldEvent {
    private val requestedResolution = AtomicReference(CollisionResolution.DERAIL)

    /** Requested outcome; defaults to derailment until a listener requests linking. */
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
