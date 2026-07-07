package work.art1st.mineRails.core.api.event

import work.art1st.mineRails.core.api.physics.train.Train

data class TrainReverseEvent(
    override val train: Train
) : TrainEvent
