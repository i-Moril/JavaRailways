package work.art1st.mineRails.core.api.event

import work.art1st.mineRails.core.api.physics.train.Train
import work.art1st.mineRails.core.api.util.annotation.AsyncEventApi

@AsyncEventApi
class DerailEvent(
    override val train: Train,
    val msg: String
) : PhysicsTrainEvent
