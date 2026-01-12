package work.art1st.mineRails.core.event

import work.art1st.mineRails.core.physics.train.Train

class DerailEvent(
    val train: Train,
    val msg: String
) : RailEvent {
}