package work.art1st.javarailways.core.api.event

import work.art1st.javarailways.core.api.physics.train.Train

/** Fired when a train switches its physical running direction. */
data class TrainSwitchDirectionEvent(
    override val train: Train
) : MainThreadTrainEvent
