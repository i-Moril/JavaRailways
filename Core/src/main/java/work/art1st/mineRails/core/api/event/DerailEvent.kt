package work.art1st.mineRails.core.api.event

import work.art1st.mineRails.core.physics.train.Train

class DerailEvent(
    override val train: Train,
    val msg: String
) : TrainEvent {
}