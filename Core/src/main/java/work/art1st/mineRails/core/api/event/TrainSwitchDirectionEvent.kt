package work.art1st.mineRails.core.api.event

import work.art1st.mineRails.core.api.physics.train.Train

/** Fired when a train switches its physical running direction. */
data class TrainSwitchDirectionEvent(
    override val train: Train
) : TrainEvent
