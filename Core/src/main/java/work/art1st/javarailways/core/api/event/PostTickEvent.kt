package work.art1st.javarailways.core.api.event

import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.physics.RailwayWorldFrameSnapshot
import work.art1st.javarailways.core.api.util.annotation.AsyncEventApi

/**
 * Synchronous coordinator notification after a complete world frame and its ticket commit.
 * Failed epochs have no Post. The frame is this epoch's immutable result, not platform completion.
 * Ticket completion does not wait for listeners or their asynchronous work.
 *
 * @property world exact runtime world that committed this epoch; not a cross-thread state snapshot
 * @property epoch successfully committed physics epoch
 * @property deltaMillis actual controller delta in milliseconds, after wall-clock clamping
 * @property frame immutable complete result of this epoch, retained even if the world advances
 */
@AsyncEventApi
class PostTickEvent(
    override val world: World,
    val epoch: Long,
    val deltaMillis: Double,
    val frame: RailwayWorldFrameSnapshot
) : PhysicsRailEvent, WorldEvent
