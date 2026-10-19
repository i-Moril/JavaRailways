package work.art1st.javarailways.core.api.event

import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.util.annotation.AsyncEventApi

/**
 * Synchronous coordinator notification before an epoch applies decisions or computes physics.
 * The World reference identifies the source and does not grant live-state mutation access.
 *
 * @property world exact runtime world executing this epoch
 * @property epoch physics epoch that is beginning computation
 * @property deltaMillis actual controller delta in milliseconds, after wall-clock clamping
 */
@AsyncEventApi
class PreTickEvent(
    override val world: World,
    val epoch: Long,
    val deltaMillis: Double
) : PhysicsRailEvent, WorldEvent
