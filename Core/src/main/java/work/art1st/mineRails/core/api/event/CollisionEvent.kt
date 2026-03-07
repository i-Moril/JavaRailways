package work.art1st.mineRails.core.api.event

import work.art1st.mineRails.core.physics.train.CoreTrain

class CollisionEvent(
    val trainA: CoreTrain,
    val trainB: CoreTrain
) : Event {
}