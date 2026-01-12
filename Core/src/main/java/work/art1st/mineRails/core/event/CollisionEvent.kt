package work.art1st.mineRails.core.event

import work.art1st.mineRails.core.physics.train.Train

class CollisionEvent(
    val trainA: Train,
    val trainB: Train
) : RailEvent {
}