package work.art1st.mineRails.core.api.event

import work.art1st.mineRails.core.physics.train.CoreTrain

class DerailEvent(
    override val train: CoreTrain,
    val msg: String
) : TrainEvent {
}